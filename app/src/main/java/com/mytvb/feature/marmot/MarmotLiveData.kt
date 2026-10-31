package com.mytvb.feature.marmot

import com.mytvb.core.common.json.GsonHolder
import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.mytvb.core.common.log.AppLog
import com.mytvb.feature.marmot.domain.MarmotModels.DataWrapper
import com.mytvb.feature.marmot.domain.MarmotModels.Live
import com.mytvb.feature.marmot.domain.MarmotModels.Vod
import java.io.File

/**
 * 频道数据管理与切台导航（对标参考 utao `UpdateService.initTvData` + `liveNext` + `FileUtil.readExt`）。
 *
 * - [load] 读取 tv-web 频道表（tv2.json），构建 index/tag/url 三个导航 Map。
 * - [liveNext] 四向环形切台（up/down/left/right）。
 * - [readFileWithFallback] 读取 tv-web 资源：优先 filesDir/tv-web（云端更新后），无则 assets/tv-web（内置种子）。
 *
 * 必须在 IO 线程调用 [load]。
 */
object MarmotLiveData {
    private const val TAG = "MarmotLiveData"
    private const val TV_JSON_PATH = "tv-web/js/cctv/tv2.json"
    private val gson = GsonHolder.DEFAULT

    /** key("tagIndex_detailIndex") → Vod */
    private val indexVodMap = HashMap<String, Vod>()
    /** tagIndex → 该分组最大 detailIndex */
    private val tagMaxMap = HashMap<Int, Int>()
    /** url → key */
    private val urlKeyMap = HashMap<String, String>()
    /** 全部分组 */
    private val lives = ArrayList<Live>()

    @Volatile
    private var loaded = false

    /**
     * 加载 tv.json 频道表并构建导航 Map。线程安全，重复调用只加载一次。
     * @return true 加载成功。
     */
    @Synchronized
    fun load(context: Context): Boolean {
        if (loaded) return true
        // 打印两份数据源的可达性/大小，方便定位「频道数据加载失败」到底是哪一份数据出问题
        val localFile = File(context.filesDir, TV_JSON_PATH)
        val assetsReachable = runCatching {
            context.assets.open(TV_JSON_PATH).use { it.readBytes().size }
        }.getOrNull()
        AppLog.i(TAG, "load: filesDir=${localFile.absolutePath} exists=${localFile.exists()} len=${localFile.length()}; assetsLen=$assetsReachable")
        Log.i(TAG, "load: filesDir exists=${localFile.exists()} len=${localFile.length()}; assetsLen=$assetsReachable")

        val type = object : TypeToken<DataWrapper<Live>>() {}.type
        // 解析+校验频道表：优先 filesDir（云端更新后），解析失败/空则回退 assets 内置种子，
        // 再失败才判定加载失败。避免 filesDir 写入半截/损坏的 tv2.json 导致「频道数据加载失败」。
        val parsed: List<Live>? = parseTvTable(context, TV_JSON_PATH, type, preferAssets = false)
            ?: run {
                AppLog.w(TAG, "filesDir 频道表不可用，回退 assets 内置种子")
                Log.w(TAG, "filesDir 频道表不可用，回退 assets 内置种子")
                parseTvTable(context, TV_JSON_PATH, type, preferAssets = true)
            }
        val list = parsed
        if (list.isNullOrEmpty()) {
            AppLog.e(TAG, "频道表加载失败（filesDir + assets 均不可用），filesDir.exists=${localFile.exists()} assetsLen=$assetsReachable")
            Log.e(TAG, "频道表加载失败（filesDir + assets 均不可用）")
            return false
        }
        // 合并代码化补充频道（兜底：tv2.json 可能被第三方云端热更新覆盖，
        // 央视频超清组 4K/8K/CGTN/剧场以代码表为准，按 URL 去重幂等）
        val mergedList = mergeExtraChannels(list)
        return try {
            indexVodMap.clear(); tagMaxMap.clear(); urlKeyMap.clear(); lives.clear()
            var i = 0
            for (life in mergedList) {
                var j = 0
                for (vod in life.vods) {
                    vod.tagIndex = i
                    vod.detailIndex = j
                    val key = "${i}_$j"
                    vod.key = key
                    indexVodMap[key] = vod
                    urlKeyMap[vod.url] = key
                    j++
                }
                tagMaxMap[i] = j - 1
                lives.add(life)
                i++
            }
            loaded = true
            AppLog.i(TAG, "已加载 ${lives.size} 个分组，${indexVodMap.size} 个频道")
            Log.i(TAG, "已加载 ${lives.size} 个分组，${indexVodMap.size} 个频道")
            true
        } catch (t: Throwable) {
            AppLog.e(TAG, "加载频道表失败: ${t.javaClass.simpleName}: ${t.message}", t)
            Log.e(TAG, "加载频道表失败", t)
            false
        }
    }

    /**
     * 从单一来源（filesDir 或 assets）读取+解析+校验频道表。
     *
     * 与 [readFileWithFallback] 不同：后者只在「文件读抛异常」时回退，
     * 一旦云端更新写入了半截/损坏的 tv2.json，readText 成功但内容非法，
     * 解析会在 [load] 抛异常却已错过 assets 兜底。这里把「读取+解析+校验」
     * 合并为一个原子单元，任一环节失败即返回 null，由 [load] 决定回退策略。
     *
     * @param preferAssets true=强制只读 assets 内置种子（filesDir 损坏时回退用）；
     *                     false=只读 filesDir（云端更新后的，可能损坏）。
     * @return 解析成功且非空返回分组列表；否则 null。
     */
    private fun parseTvTable(
        context: Context,
        path: String,
        type: java.lang.reflect.Type,
        preferAssets: Boolean
    ): List<Live>? {
        val source = if (preferAssets) "assets" else "filesDir"
        val json = if (preferAssets) readAssets(context, path) else readFileDir(context, path)
        if (json.isNullOrBlank()) {
            AppLog.w(TAG, "parseTvTable($source): 内容为空或读取失败")
            return null
        }
        return try {
            val wrapper: DataWrapper<Live> = gson.fromJson(json, type)
            val list = wrapper.data
            if (list.isNullOrEmpty()) {
                // data 为空但未抛异常：典型是 Gson 反射不到字段（R8 keep 缺失），JSON 本身可能合法。
                // 打印 json 前缀以便区分「文件损坏」与「反射置 null」。
                AppLog.w(TAG, "parseTvTable($source): data 为空（json 长度=${json.length} 前 80 字符=${json.take(80)}）")
                null
            } else {
                AppLog.i(TAG, "parseTvTable($source): 解析成功 ${list.size} 个分组")
                list
            }
        } catch (t: Throwable) {
            AppLog.w(TAG, "parseTvTable($source): 解析失败 ${t.javaClass.simpleName}: ${t.message}（json 前 80 字符=${json.take(80)}）", t)
            Log.w(TAG, "频道表解析失败(preferAssets=$preferAssets): ${t.message}")
            // filesDir 文件损坏：删除，避免下次再被 readFileWithFallback 优先选中。
            if (!preferAssets) {
                runCatching { File(context.filesDir, path).delete() }
                    .onFailure { AppLog.w(TAG, "删除损坏的 filesDir tv2.json 失败: ${it.message}") }
            }
            null
        }
    }

    /** 读 filesDir/<path>，文件不存在/为空/读异常返回 null。 */
    private fun readFileDir(context: Context, path: String): String? {
        val f = File(context.filesDir, path)
        if (!f.exists() || f.length() == 0L) return null
        return runCatching { f.readText() }.getOrNull()
    }

    /** 读 assets/<path>，不存在/读异常返回 null。 */
    private fun readAssets(context: Context, path: String): String? {
        return try {
            context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (t: Throwable) { null }
    }

    /** 全部分组（含 vods）。加载前调用返回空列表。 */
    fun getLives(): List<Live> = lives

    /**
     * 合并 [CctvExtraChannels] 补充频道到「央视频超清」组（组不存在则新建追加到表尾，
     * 排在少儿组之后；存在则按 URL 去重后并入）。幂等：tv2.json 已含这些频道时不重复加。
     */
    private fun mergeExtraChannels(list: List<Live>): MutableList<Live> {
        if (CctvExtraChannels.EXTRA_CHANNELS.isEmpty()) return toMutableList(list)
        val existingUrls = list.flatMap { it.vods }.map { it.url }.toHashSet()
        val toAdd = CctvExtraChannels.EXTRA_CHANNELS.filter { it.url !in existingUrls }
        if (toAdd.isEmpty()) return toMutableList(list)
        val out: MutableList<Live> = toMutableList(list)
        val group = out.firstOrNull { it.name == CctvExtraChannels.GROUP_NAME || it.name == "央视源2" }
        if (group != null) {
            // 组名不改（Live.name 为 val）：旧数据名「央视源2」由 MarmotLiveActivity 显示层统一为「央视频超清」
            group.vods.addAll(toAdd)
        } else {
            out.add(
                Live(tag = "ysp", name = CctvExtraChannels.GROUP_NAME, index = out.size,
                    vods = toAdd.toMutableList())
            )
        }
        AppLog.i(TAG, "mergeExtraChannels: 合并补充频道 ${toAdd.size} 条（代码化兜底）")
        return out
    }

    private fun toMutableList(list: List<Live>): MutableList<Live> =
        (list as? MutableList<Live>) ?: ArrayList(list)

    /** 按 key 取频道。 */
    fun getByKey(key: String): Vod? = indexVodMap[key]

    /** 按 url 取频道。 */
    fun getByUrl(url: String): Vod? = urlKeyMap[url]?.let { indexVodMap[it] }

    /**
     * 计算下一频道 key（对应参考 `UpdateService.liveNext`）。
     * @param tagIndexNow 当前分组索引
     * @param detailIndexNow 当前频道索引
     * @param direction up/down/left/right
     */
    fun liveNext(tagIndexNow: Int, detailIndexNow: Int, direction: String): String {
        return when (direction) {
            "up" -> {
                if (detailIndexNow == 0) "${tagIndexNow}_${tagMaxMap[tagIndexNow] ?: 0}"
                else "${tagIndexNow}_${detailIndexNow - 1}"
            }
            "down" -> {
                val max = tagMaxMap[tagIndexNow] ?: 0
                if (detailIndexNow == max) "${tagIndexNow}_0"
                else "${tagIndexNow}_${detailIndexNow + 1}"
            }
            "left" -> {
                if (tagIndexNow == 0) "${(tagMaxMap.size - 1)}_0"
                else "${tagIndexNow - 1}_0"
            }
            "right" -> {
                if (tagIndexNow == tagMaxMap.size - 1) "0_0"
                else "${tagIndexNow + 1}_0"
            }
            else -> "0_0"
        }
    }

    /**
     * 读取 tv-web 资源：优先 filesDir/tv-web（云端更新后的），无则 assets/tv-web（内置种子）。
     * 对应参考 `FileUtil.readExt` 的优先级（filesDir → assets 兜底）。
     */
    fun readFileWithFallback(context: Context, path: String): String {
        // 1. filesDir/<path>（云端更新写入的位置）
        readFileDir(context, path)?.let { return it }
        // 2. assets/<path>（内置种子）
        return readAssets(context, path) ?: ""
    }

    /** 读取 tv-web 资源为 InputStream（用于 shouldInterceptRequest 返回二进制资源）。 */
    fun readStreamWithFallback(context: Context, path: String): java.io.InputStream? {
        val localFile = File(context.filesDir, path)
        if (localFile.exists() && localFile.length() > 0) {
            runCatching { return java.io.FileInputStream(localFile) }
        }
        return try {
            context.assets.open(path)
        } catch (t: Throwable) { null }
    }
}
