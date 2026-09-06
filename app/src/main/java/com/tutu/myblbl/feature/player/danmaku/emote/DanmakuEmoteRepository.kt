package com.tutu.myblbl.feature.player.danmaku.emote

import android.content.Context
import com.tutu.myblbl.core.common.log.AppLog
import com.tutu.myblbl.network.BiliClient
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 表情词典：token → 图片 URL，用于把弹幕里的 `[doge]` 渲染成表情。
 *
 * 数据源：x/emote/user/panel/web?business=reply（评论区表情面板，与弹幕共用同一套
 * token 命名；B 站弹幕 proto 只给纯文本，不含表情元数据）。
 *
 * - 磁盘缓存（filesDir/emote_cache/reply_panel_v1.json），24h 刷新一次；
 * - [version] 随词典内容变化递增，调用方据此失效已缓存的分段解析结果；
 * - 全程无阻塞：warmup 立即返回，加载/刷新在 IO 协程。
 *
 * 对齐 blbl.cat3399.core.emote.ReplyEmotePanelRepository。
 */
internal object DanmakuEmoteRepository {
    private const val TAG = "DanmakuEmote"
    private const val TTL_SEC: Long = 24L * 60 * 60

    private const val CACHE_DIR = "emote_cache"
    private const val CACHE_FILE = "reply_panel_v1.json"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    @Volatile
    private var initStarted: Boolean = false

    @Volatile
    private var refreshJob: Job? = null

    @Volatile
    private var fetchedAtSec: Long = 0L

    @Volatile
    private var emoteMap: Map<String, String> = emptyMap()

    @Volatile
    private var versionValue: Int = 0

    fun warmup(context: Context) {
        val appContext = context.applicationContext
        init(appContext)
        maybeRefresh(appContext)
    }

    fun init(context: Context) {
        if (initStarted) return
        synchronized(lock) {
            if (initStarted) return
            initStarted = true
        }
        val appContext = context.applicationContext
        scope.launch {
            loadFromDisk(appContext)
            maybeRefresh(appContext)
        }
    }

    /** 词典版本：内容每次变化（磁盘加载/网络刷新）递增；0 表示尚未就绪。 */
    fun version(): Int = versionValue

    fun urlForToken(token: String): String? = emoteMap[token]

    private fun maybeRefresh(context: Context) {
        val nowSec = System.currentTimeMillis() / 1000
        val needRefresh = emoteMap.isEmpty() || nowSec - fetchedAtSec >= TTL_SEC
        if (!needRefresh) return

        val active = refreshJob
        if (active != null && active.isActive) return

        val appContext = context.applicationContext
        refreshJob = scope.launch {
            refreshFromNetwork(appContext)
        }
    }

    private fun cacheFile(context: Context): File {
        val dir = File(context.filesDir, CACHE_DIR)
        runCatching { dir.mkdirs() }
        return File(dir, CACHE_FILE)
    }

    private fun loadFromDisk(context: Context) {
        val file = cacheFile(context)
        if (!file.exists()) return
        val raw = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return

        runCatching {
            val root = JSONObject(raw)
            val ts = root.optLong("fetched_at_sec", 0L).coerceAtLeast(0L)
            val mapObj = root.optJSONObject("map") ?: JSONObject()
            val out = HashMap<String, String>(mapObj.length().coerceAtLeast(0))
            val it = mapObj.keys()
            while (it.hasNext()) {
                val k = it.next().trim()
                if (k.isBlank()) continue
                val url = mapObj.optString(k, "").trim()
                if (!url.startsWith("http")) continue
                out[k] = url
            }
            if (out.isNotEmpty()) {
                fetchedAtSec = ts
                emoteMap = out
                versionValue++
                AppLog.i(TAG, "loadFromDisk size=${out.size} fetchedAtSec=$ts")
            }
        }.onFailure { t ->
            // 坏文件改名保留现场（对齐 blbl），下次刷新重建。
            AppLog.w(TAG, "loadFromDisk parse failed; keep empty and refresh", t)
            val bad = File(file.parentFile ?: context.filesDir, "${file.nameWithoutExtension}.bad_${System.currentTimeMillis()}.${file.extension}")
            runCatching { file.renameTo(bad) }
        }
    }

    private suspend fun refreshFromNetwork(context: Context) {
        val url = "https://api.bilibili.com/x/emote/user/panel/web?business=reply"
        val json = runCatching { BiliClient.getJson(url) }.getOrNull() ?: return
        val code = json.optInt("code", 0)
        if (code != 0) {
            val msg = json.optString("message", json.optString("msg", ""))
            AppLog.w(TAG, "refresh failed code=$code msg=$msg")
            return
        }

        val data = json.optJSONObject("data") ?: JSONObject()
        val packages = data.optJSONArray("packages") ?: return
        val out = HashMap<String, String>(512)
        for (i in 0 until packages.length()) {
            val pkg = packages.optJSONObject(i) ?: continue
            val emotes = pkg.optJSONArray("emote") ?: continue
            for (j in 0 until emotes.length()) {
                val e = emotes.optJSONObject(j) ?: continue
                val text = e.optString("text", "").trim()
                if (!looksLikeToken(text)) continue
                val urlStr = e.optString("url", "").trim()
                if (!urlStr.startsWith("http")) continue
                out[text] = urlStr
            }
        }
        if (out.isEmpty()) return

        val nowSec = System.currentTimeMillis() / 1000
        fetchedAtSec = nowSec
        emoteMap = out
        versionValue++
        AppLog.i(TAG, "refresh ok size=${out.size} fetchedAtSec=$nowSec")

        persistToDisk(context, nowSec, out)
    }

    private fun persistToDisk(context: Context, fetchedAtSec: Long, map: Map<String, String>) {
        val file = cacheFile(context)
        runCatching {
            val mapObj = JSONObject()
            for ((k, v) in map) mapObj.put(k, v)
            val root = JSONObject()
                .put("fetched_at_sec", fetchedAtSec)
                .put("map", mapObj)
            file.writeText(root.toString(), Charsets.UTF_8)
        }.onFailure { t ->
            AppLog.w(TAG, "persist failed file=${file.absolutePath}", t)
        }
    }

    private fun looksLikeToken(text: String): Boolean {
        if (text.length < 3) return false
        return text.first() == '[' && text.last() == ']'
    }
}
