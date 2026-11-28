package com.mytvb.feature.hongguo.net

import android.util.Base64
import com.mytvb.core.common.log.AppLog
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 红果播放地址解析。取源路由内置为 [HongguoNetProfiles.ROUTE_VIDEO_MODEL]（2026-11-25
 * 起取消云端热更），四路形态的签名都是 [HongguoMedusa]（HGTV 精确移植，selector 调谐到 12）：
 * - `video_model`（**默认，2026-11-25 真网验证**）：`POST https://reading.snssdk.com/
 *   novel/player/video_model/v1/`，官方 7.4.5.32 当前客户端的单集直取形态——21 键精简
 *   query + Redmi 25053RT47C/Android 16 手机画像 + 系统 UA + `device_level=2` + 无 Cookie；
 * - `player`：`POST /novel/player/multi_video_model/player/v1`（官方抓包 35 键全形态）。
 *   11-24 放行，11-24 21:32 服务端配置切换后对旧形态一律回 200 空 body；
 * - `preload`：`POST /novel/player/multi_video_model/preload/v1`（同日起同样空）；
 * - `v5tab`：V5 签名 `GET /reading/bookapi/video_tab/multi_video_model/v1`（果映TV 形态）。
 *
 * 拿到 entry 后 → video_model 内联/fallback 二跳 → video_list 编码过滤 → spade_a 密钥派生 →
 * URL 解密 + CDN 域名白名单（[parseEntry]，对照 Node 版 app-source.js parseEntryPlayInfo）。
 */
class HongguoPlay {

    private val http: okhttp3.Call.Factory get() =
        // 共享出口单例：播放必须复用注册连接（连接会话模型，见 HongguoHttp）
        HongguoHttp.client()

    data class PlayUrl(
        val url: String,
        val contentKeyHex: String?,
        val codec: String,
        val quality: String,
        val width: Int,
        val height: Int,
        val durationSec: Long,
        val vid: String,
        /** 当前所选档位（竖屏取 min(width,height)，无尺寸信息时 0）。 */
        val pixels: Int = 0,
        /** 本集全部可用档位（兼容编码候选，pixels 降序去重）；空 = 片源未带尺寸信息。 */
        val availableQualities: List<Int> = emptyList(),
        /** 官方进度条雪碧图（player/v1 big_thumbs，HEIC 网格）；null = 本集未下发，不启用预览图。 */
        val bigThumbs: BigThumbs? = null,
        /** CENC 内容密钥 ID（hex 32 字符；来自 encrypt_info.kid，与 contentKeyHex 配对，
         *  ClearKey 流式解密用——MediaDrm 会话按 kid 匹配 mp4 pssh/tenc）。null=未下发。 */
        val kidHex: String? = null
    )

    /**
     * 官方进度预览雪碧图（video_model.big_thumbs，2026-11-13 官方抓包实锤）：
     * HEIC 容器整图（实测 2400×1360 ≈ 278KB，签名 URL 约 6h 有效，图床模板转换 403 锁死），
     * 按 [intervalSec] 每秒一格铺 [xLen] 列网格，格子尺寸 [cellWidth]×[cellHeight]
     * （横屏剧 240×136 / 竖屏剧 136×240，按字段取勿假设方向）。
     */
    data class BigThumbs(
        val imageUrl: String,
        val cellWidth: Int,
        val cellHeight: Int,
        val xLen: Int,
        val intervalSec: Int,
        val frameCount: Int
    ) {
        /** 官方格位公式：idx = 秒/interval，钳到 img_num-1（img_num 缺失时钳 0）。
         *  除法结果在 Long 域钳界后再转 Int（防极端 positionMs 溢出回绕成负）。 */
        fun cellIndexFor(positionMs: Long): Int {
            val maxIdx = if (frameCount > 0) frameCount - 1 else 0
            val raw = positionMs.coerceAtLeast(0L) / 1000L / intervalSec
            return raw.coerceAtMost(maxIdx.toLong()).toInt()
        }
    }

    class PlayException(message: String, val code: String) : IllegalStateException(message)

    /**
     * @param identity 真注册设备身份（播放接口校验设备真实性，匿名随机 id 会被空响应拒绝）。
     * 可用 [HongguoDeviceRegister.registerFresh] 获取后持久化复用。
     * @param preferredPixels 目标画质档位（竖屏剧取 min(width,height)，如 1080/720/540/480）；
     * 0 = 默认取最高档。目标档缺失时就近取不超目标的最高档，全超目标取最低档。
     */
    fun playUrl(
        vid: String,
        identity: HongguoDeviceRegister.Identity,
        preferredPixels: Int = 0,
    ): PlayUrl {
        require(vid.matches(Regex("\\d{1,30}"))) { "分集编号无效" }
        val entry = fetchEntry(vid, identity)
        return parseEntry(vid, entry, preferredPixels)
    }

    /**
     * 取源：**只走一条主路由，失败即失败，不做端点回退**（2026-11-24 定案）。
     * 理由：11-23 实证 `preload`/`v5tab` 一起回空，回退一次没救回来；而非空失败时
     * 换端点重发，会让服务端在几十秒内看到同一设备用两种不同形态请求播放接口——
     * 比单一路由失败更像异常客户端。多形态并存只保留「配置可切换」，不保留「自动串联」。
     * 换通道改 `hg_net.play_routes` 首位（或直接改这一条），其余路由留作手动切换备选。
     */
    private fun fetchEntry(vid: String, identity: HongguoDeviceRegister.Identity): JSONObject {
        checkCooldown()
        val route = HongguoNetProfiles.activeRoute()
        val entry = when (route) {
            HongguoNetProfiles.ROUTE_PLAYER -> fetchModelEntry(PLAYER_PATH, DR_PLAYER, vid, identity)
            HongguoNetProfiles.ROUTE_PRELOAD -> fetchModelEntry(PRELOAD_PATH, DR_PRELOAD, vid, identity)
            HongguoNetProfiles.ROUTE_VIDEO_MODEL -> fetchVideoModelEntry(vid, identity)
            HongguoNetProfiles.ROUTE_V5TAB -> fetchTabEntry(vid, identity)
            // 路由名在 HongguoNetProfiles 解析时已过滤，此处仅防御
            else -> throw PlayException("未知取源路由 $route", "UNKNOWN_ROUTE")
        }
        noteSuccess()
        return entry
    }

    /** 收到空 body：记连续次数、按 [backoffMs] 进入冷却，并以 [EMPTY_BODY] 抛出。
     *  [countBackoff]=false 用于后台预取：预取失败不该把前台起播打进熔断
     *  （2026-11-25 实测：起播成功后预取连打 3 个空响应，直接把下一次起播拦了 3 分钟）。 */
    private fun onEmptyBody(path: String, countBackoff: Boolean = true): Nothing {
        if (!countBackoff) {
            AppLog.w(TAG, "play empty body (background) path=$path")
            throw PlayException("empty body path=$path", EMPTY_BODY)
        }
        val backoff = noteEmptyBody()
        AppLog.w(TAG, "play empty body path=$path cooldown=${backoff / 1000}s")
        throw PlayException("empty body path=$path", EMPTY_BODY)
    }

    /** 冷却期内的取址直接拒绝（不发请求），剩余分钟数写进 message 供界面提示。 */
    private fun checkCooldown() {
        val remaining = cooldownRemainingMs()
        if (remaining <= 0L) return
        throw PlayException("红果服务端限流，约 ${(remaining + 59_999) / 60_000} 分钟后再试", COOLDOWN)
    }

    /**
     * 形态「V5 签名 GET video_tab」（对齐果映TV MobilePlayback.getVideoTab / aston
     * recoverVideoTab）：query=V5 模板公共参数 + 单集业务参数，签名走 [HongguoPlaybackV5.sign]
     * （_rticket 调谐分支 12，头只有 x-khronos/x-helios/x-medusa），无 body 无 SS-STUB。
     */
    private fun fetchTabEntry(vid: String, identity: HongguoDeviceRegister.Identity): JSONObject {
        val ver = HongguoVersionProfiles.default
        val pairs = listOf(
            "aid" to "8662", "app_name" to "novelread",
            "version_code" to ver.code, "version_name" to ver.versionName,
            "channel" to V5_CHANNEL, "device_platform" to "android",
            "device_type" to V5_DEVICE_TYPE, "os_version" to V5_OS_VERSION,
            "update_version_code" to ver.code,
            "iid" to identity.installId, "cdid" to identity.cdid, "device_id" to identity.deviceId,
            "video_id" to vid, "video_id_type" to "0", "device_level" to "1",
            "need_all_video_definition" to "1", "need_mp4_align" to "0",
            "use_os_player" to "0", "video_platform" to "1024",
            "source" to "0", "from_video_id" to ""
        )
        val signed = HongguoPlaybackV5.sign(pairs, System.currentTimeMillis(), null)
        val req = Request.Builder()
            .url("${HongguoNetProfiles.playHost()}$TAB_PATH?${signed.query}")
            .header("User-Agent", HongguoPlaybackV5.PLAYBACK_UA)
            .header("Accept", "application/json")
            .apply { signed.headers.forEach { (k, v) -> header(k, v) } }
            .build()
        AppLog.d(TAG, "play video_tab req vid=$vid device=${identity.deviceId}")
        return http.newCall(req).execute().use { resp ->
            val bytes = resp.body?.bytes() ?: ByteArray(0)
            if (!resp.isSuccessful) {
                AppLog.w(TAG, "play video_tab failed http=${resp.code}")
                throw IllegalStateException("HTTP ${resp.code}")
            }
            if (bytes.isEmpty()) onEmptyBody(TAB_PATH)
            AppLog.i(TAG, "play video_tab ok http=${resp.code} len=${bytes.size}")
            tabEntryOf(vid, String(bytes, Charsets.UTF_8))
        }
    }

    /**
     * `video_model/v1` 单集直取（2026-11-25 接入，参照 tsukiyo-zero/hongguoTV 0.1.40 的
     * 线上验证形态）：host=`reading.snssdk.com` + 21 键精简 query（官方当前公共参数集 +
     * Redmi 25053RT47C / Android 16 / os_api 36 手机画像）+ 系统默认 UA + `video_id` +
     * `biz_param(device_level=2)` 的 gzip body + 无 Cookie。签名仍是 V5（medusa field21=312，
     * 与官方 7.4.5.32 客户端一致；377 才会被判无效）。
     * 背景：2026-11-24 21:32 服务端配置切换后，`fqnovel` 域的 player/preload 两路对旧形态
     * 一律回 200 空 body（同一时刻 feed/详情正常），本路由用于验证「官方当前客户端形态」。
     */
    private fun fetchVideoModelEntry(
        vid: String, identity: HongguoDeviceRegister.Identity, countBackoff: Boolean = true
    ): JSONObject {
        val ver = HongguoVersionProfiles.default
        val pairs = listOf(
            "aid" to "8662", "app_name" to "novelread",
            "version_code" to ver.code, "version_name" to ver.versionName,
            "update_version_code" to ver.code, "manifest_version_code" to ver.code,
            "os" to "android", "ssmix" to "a", "channel" to CURRENT_CHANNEL,
            "device_platform" to "android", "device_type" to CURRENT_DEVICE_TYPE,
            "device_brand" to CURRENT_DEVICE_BRAND, "os_version" to CURRENT_OS_VERSION,
            "os_api" to CURRENT_OS_API, "ac" to "wifi", "language" to "zh",
            "host_abi" to "arm64-v8a",
            "iid" to identity.installId, "cdid" to identity.cdid,
            "device_id" to identity.deviceId,
        )
        val body = gzipDeflate(videoModelBody(vid, 2))
        AppLog.d(
            TAG,
            "play video_model first=$vid level=2 device=${identity.deviceId} " +
                "host=$VIDEO_MODEL_HOST type=$CURRENT_DEVICE_TYPE ua=system"
        )
        val req = buildVideoModelRequest(VIDEO_MODEL_HOST + VIDEO_MODEL_PATH, pairs, body)
        return http.newCall(req).execute().use { resp ->
            val bytes = resp.body?.bytes() ?: ByteArray(0)
            AppLog.i(TAG, "play video_model resp http=${resp.code} len=${bytes.size}")
            if (bytes.isEmpty()) {
                val hdrs = (0 until resp.headers.size).joinToString(" | ") { i ->
                    val name = resp.headers.name(i)
                    val value = if (name.equals("Set-Cookie", true)) "<omitted>" else resp.headers.value(i).take(160)
                    "$name=$value"
                }
                AppLog.w(TAG, "play empty body headers [$hdrs]")
                onEmptyBody(VIDEO_MODEL_PATH, countBackoff)
            }
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            val payload = JSONObject(String(bytes, Charsets.UTF_8))
            val code = payload.optInt("code", payload.optInt("Code", 0))
            if (code != 0) throw PlayException("播放接口 code=$code", "BIZ_$code")
            val data = payload.optJSONObject("data")
                ?: throw PlayException("播放信息为空", "MISSING_MODEL")
            // 单集直取：data.video_model；兼容批量形态 data.video_model_data[vid]
            if (data.opt("video_model") != null) return data
            val rows = data.optJSONObject("video_model_data") ?: data
            return rows.optJSONObject(vid) ?: data
        }
    }

    /** `video_model/v1` 请求体（手写键序，gzip 后与 SS-STUB 同字节）。 */
    internal fun videoModelBody(vid: String, deviceLevel: Int): ByteArray =
        ("{\"video_id\":\"$vid\",\"biz_param\":{\"detail_page_version\":0,\"device_level\":$deviceLevel," +
            "\"disable_digg_stat\":false,\"disable_video_relate_book\":false," +
            "\"need_all_video_definition\":true,\"need_mp4_align\":false," +
            "\"use_os_player\":false,\"use_server_dns\":false,\"video_platform\":1024}}")
            .toByteArray(Charsets.UTF_8)

    /** `video_model/v1` 请求构造：V5 签名 + 系统 UA + Accept/Content-Encoding（无 Cookie）。 */
    private fun buildVideoModelRequest(
        baseUrl: String, pairs: List<Pair<String, String>>, body: ByteArray
    ): Request {
        val signed = HongguoPlaybackV5.sign(pairs, System.currentTimeMillis(), body)
        return Request.Builder()
            .url("$baseUrl?${signed.query}")
            // 官方当前客户端这一路的 UA 是系统默认（Dalvik/…Build/…），不是 App UA
            .header("User-Agent", System.getProperty("http.agent") ?: "okhttp/4.12.0")
            .header("Accept", "application/json")
            .header("Content-Encoding", "gzip")
            .apply { signed.headers.forEach { (k, v) -> header(k, v) } }
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
    }

    /**
     * video_tab 响应 → 本集 entry（含 `video_model` 键，交 [parseEntry] 二跳）。
     * 行在 `data.video_model_data[vid]`，少数形态直接 `data[vid]`。只接受目标剧集的
     * 行：2026-11-28 删「取首行兜底」——响应键与本集 vid 不符说明服务端回了别的集/
     * 剧的模型（果映TV 33e4aa94 同款串集教训），静默取首行会播错集，直接 MISSING_MODEL。
     * 纯函数，可单测。
     */
    internal fun tabEntryOf(vid: String, text: String): JSONObject {
        val payload = JSONObject(text)
        val code = payload.optInt("Code", payload.optInt("code", 0))
        // 业务码透传进 code（BIZ_101000=设备拒收→上层恢复链触发重注册的判据）
        if (code != 0) throw PlayException("播放接口 code=$code", "BIZ_$code")
        val data = payload.optJSONObject("data") ?: JSONObject()
        val rows = data.optJSONObject("video_model_data") ?: data
        return rows.optJSONObject(vid)
            ?: data.optJSONObject(vid)
            ?: throw PlayException("播放接口未返回本集片源", "MISSING_MODEL")
    }

    /**
     * 后续剧集地址预取。2026-11-25 起改走 `video_model/v1` **逐集**请求：
     * 旧的 `preload/v1`（fqnovel 域 + 官方抓包形态）已被服务端 11-24 21:32 的配置切换关掉，
     * 再打只会空 body 并把前台起播打进熔断；`video_model/v1` 是单集接口，所以逐集发。
     * 失败静默（切集时实时解析兜底，同路由），单集解析失败只跳过该集。
     */
    fun playUrlBatch(
        vids: List<String>,
        identity: HongguoDeviceRegister.Identity,
        preferredPixels: Int = 0,
    ): Map<String, PlayUrl> {
        require(vids.isNotEmpty()) { "预取集数为空" }
        require(vids.all { it.matches(Regex("\\d{1,30}")) }) { "分集编号无效" }
        val out = LinkedHashMap<String, PlayUrl>()
        for (vid in vids) {
            val entry = try {
                fetchVideoModelEntry(vid, identity, countBackoff = false)
            } catch (e: Exception) {
                if (e is PlayException && e.code == COOLDOWN) {
                    AppLog.d(TAG, "prefetch skip: cooldown")
                    break
                }
                AppLog.w(TAG, "prefetch failed vid=$vid: ${e.message}")
                continue
            }
            runCatching { out[vid] = parseEntry(vid, entry, preferredPixels) }
        }
        return out
    }

    /**
     * `player`/`preload` 两条同构路由：官方起播是 `player/v1` + `dr_scene=player`，预取是
     * `preload/v1` + `dr_scene=preload`（2026-11-13 抓包：9 条 player、20 条 preload）。
     * 请求形态 2026-11-24 起整体照抄官方 7.4.5.32：
     * - query 35 个参数位同序 + 官方那个空参数段（[playQuery]）；
     * - body `biz_param` 键序一致、`device_level=3`、`use_server_dns=false`（[modelBody]）；
     * - gzip level 9、X-SS-STUB = MD5(gzip body)（[gzipDeflate]）；
     * - 14 个头同名同序（[buildPlayRequest]）。
     * 签名层（x-medusa）与此前的 HGTV 精确移植相同，query 的 selector 由 [v5SignedPost] 调谐到 12。
     *
     * 本函数取两路共同的 data 结构里本集的 entry（`data.1004[vid]` 分组兜底）。
     */
    private fun fetchModelEntry(
        path: String,
        scene: String,
        vid: String,
        identity: HongguoDeviceRegister.Identity
    ): JSONObject {
        val data = fetchModelData(path, listOf(vid), scene, identity)
        return data.optJSONObject(vid)
            ?: data.optJSONObject("video_model")
            ?: runCatching { data.optJSONObject(data.keys().next()) }.getOrNull()
            ?: throw PlayException("播放接口未返回本集片源", "MISSING_MODEL")
    }

    /**
     * 播放接口 query，**键集与顺序照抄官方 App 抓包**（2026-11-13 com.phoenix.read 7.4.5.32，
     * `/novel/player/` 系列全 35 个参数位，含官方自己发出来的一个空参数段 `&&`）。
     * 取值保持我方已验证的设备画像（[HongguoNetProfiles] + UA Build/V417IR），只补官方有、
     * 我方此前缺的键：`cdid`、`is_64_runtime`、`is_64_device`、`is_x86_device`、
     * `rom_version`、`klink_egdi`；官方没有的 `device_token` 已去掉。
     *
     * `_rticket` 这里只占位，[HongguoPlaybackV5.sign] 会在原位调谐到 medusa selector==12。
     */
    internal fun playQuery(identity: HongguoDeviceRegister.Identity): List<Pair<String, String>> {
        val ver = HongguoVersionProfiles.default
        return listOf(
            "device_platform" to "android",
            "os" to "android",
            "ssmix" to "a",
            "_rticket" to "",
            "cdid" to identity.cdid,
            "channel" to HongguoNetProfiles.channel(),
            "aid" to "8662",
            "app_name" to "novelread",
            "version_code" to ver.code,
            "version_name" to ver.versionName,
            "manifest_version_code" to ver.code,
            "update_version_code" to ver.code,
            // 设备环境全部如实取运行设备（[HongguoEnv]），与注册/详情同源；官方抓包同款字段
            "resolution" to HongguoEnv.resolution,
            "dpi" to HongguoEnv.dpi,
            "device_type" to HongguoNetProfiles.deviceType(),
            "device_brand" to HongguoNetProfiles.deviceBrand(),
            "language" to "zh",
            "os_api" to HongguoEnv.osApi,
            "os_version" to HongguoNetProfiles.osVersion(),
            "ac" to "wifi",
            "is_64_runtime" to HongguoEnv.is64Runtime,
            "is_64_device" to HongguoEnv.is64Device,
            // 官方抓包里就有的空参数段（`...is_64_device=true&&host_abi=...`），照抄
            "" to "",
            "host_abi" to HongguoEnv.HOST_ABI,
            "dragon_device_type" to "phone",
            "pv_player" to ver.code,
            "is_x86_device" to HongguoEnv.isX86Device,
            "compliance_status" to "0",
            "need_personal_recommend" to "1",
            "player_so_load" to "1",
            "is_android_pad_screen" to "0",
            "rom_version" to HongguoEnv.romVersionQuery,
            "iid" to identity.installId,
            "device_id" to identity.deviceId,
            "klink_egdi" to identity.klinkEgdi,
        )
    }

    /**
     * `player`/`preload` 的数据获取（单集/批量共用主体），返回 vid→entry 的 data 对象。
     * 空 body 自动重试对齐 HGTV：device_level 升档立即重发一次（会话级单调不降，
     * [deviceLevel]），到 5 仍空才判失败——这是它唯一的自动重试策略。
     */
    private fun fetchModelData(
        path: String,
        vids: List<String>,
        scene: String,
        identity: HongguoDeviceRegister.Identity
    ): JSONObject {
        AppLog.d(TAG, "play $path vids=${vids.size} first=${vids.first()} scene=$scene level=$deviceLevel device=${identity.deviceId}")
        val ver = HongguoVersionProfiles.default
        val q = playQuery(identity)
        // 诊断期：响应摘要塞进异常 message（android_logs 只回传异常行，message 可观测；
        // 真机验证稳定后 resp 探针可缩短或移除）
        var respProbe = ""
        return runCatching {
            var text: String
            while (true) {
                // gzip body 同字节签名发送（官方 7.4.5.32 实测同样是 zlib level 9 的 gzip：
                // X-SS-STUB == MD5(gzip(body))，2026-11-24 抓包核对）
                text = v5SignedPost(
                    "${HongguoNetProfiles.playHost()}$path",
                    q, gzipDeflate(modelBody(vids, deviceLevel, scene)), ua(ver.code)
                )
                respProbe = text.take(1200)
                if (text.isNotEmpty()) break
                if (deviceLevel < 5) {
                    AppLog.w(TAG, "play $path empty body, escalate device_level $deviceLevel->${deviceLevel + 1}")
                    deviceLevel += 1
                    continue
                }
                onEmptyBody(path)
            }
            val payload = JSONObject(text)
            val code = payload.optInt("Code", payload.optInt("code", 0))
            // 业务码透传进 code（BIZ_101000=设备拒收→上层恢复链触发重注册的判据，
            // 对齐 HGTV 1371「101000→备用设备」语义；BIZ_100001=窗口/参数特征不触发）
            if (code != 0) throw PlayException("播放接口 code=$code", "BIZ_$code")
            val data0 = payload.optJSONObject("data") ?: JSONObject()
            data0.optJSONObject("1004") ?: data0
        }.getOrElse { e ->
            // PlayException（空 body 熔断 / 业务码）原样抛出，其余（HTTP 码/解析）归入路由失败
            if (e is PlayException) throw e
            throw PlayException("${e.message} || resp=$respProbe", "PRELOAD_FAIL")
        }
    }

    /** 播放接口 body，**照抄官方 App 抓包形态**（`biz_param` 键序一致）：device_level 3 起
     *  （官方 9 条 player/20 条 preload 样本全是 3）、use_server_dns=false（官方 14/15）、
     *  video_platform=1024、dr_scene=player|preload、mixed_video_id_map.1004。 */

    /** preload/v1 批量 body（对齐 HGTV 1.3.8.6 o0 形态：device_level 4~5 /
     *  use_server_dns=true + cold_start_stage / video_platform=1024 /
     *  dr_scene=preload / mixed_video_id_map；1.3.8.6 对响应无 server_dns
     *  特殊解析，结构与 false 相同）。 */
    internal fun modelBody(vids: List<String>, deviceLevel: Int, scene: String): ByteArray {
        // 手写字符串而不是 JSONObject：官方抓包的 body 是固定键序的紧凑 JSON，org.json 的键序
        // 在单测环境（HashMap 实现）不保证，手写才能与之逐字节一致。vid 已由上层校验为纯数字。
        val ids = vids.joinToString(",") { "\"$it\"" }
        val json = "{\"biz_param\":{\"detail_page_version\":0,\"device_level\":$deviceLevel," +
            "\"disable_digg_stat\":false,\"disable_video_relate_book\":false," +
            "\"need_all_video_definition\":true,\"need_mp4_align\":false," +
            "\"use_os_player\":false,\"use_server_dns\":false,\"video_platform\":1024}," +
            "\"dr_scene\":\"$scene\",\"mixed_video_id_map\":{\"1004\":[$ids]}}"
        return json.toByteArray(Charsets.UTF_8)
    }

    /** 设备能力档：官方 App 实测恒为 3；空响应按 HGTV 习惯升 4→5 重发，会话级单调不降
     *  （成功后停留在成功档，下次起播免再撞空）。 */
    @Volatile private var deviceLevel = 3

    /** gzip（官方 7.4.5.32 实测 zlib level 9、OS 字节 0xFF：把抓包 body 按这个参数压出的
     *  字节，其 MD5 与官方实际发出的 X-SS-STUB 完全相等，2026-11-24 抓包核对）。
     *  爪哇 GZIPOutputStream 固定 level 6 且 `def` 字段 protected 改不了，故手写 gzip 头/尾。
     *  同一份字节既签名又发送。 */
    internal fun gzipDeflate(data: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(0x1f, 0x8b.toByte(), 0x08, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xff.toByte()))
        val def = java.util.zip.Deflater(java.util.zip.Deflater.BEST_COMPRESSION, true)
        java.util.zip.DeflaterOutputStream(out, def).use { it.write(data) }
        val crc = java.util.zip.CRC32().apply { update(data) }.value
        out.write(
            byteArrayOf(
                (crc and 0xff).toByte(), ((crc shr 8) and 0xff).toByte(),
                ((crc shr 16) and 0xff).toByte(), ((crc shr 24) and 0xff).toByte(),
                (data.size and 0xff).toByte(), ((data.size shr 8) and 0xff).toByte(),
                ((data.size shr 16) and 0xff).toByte(), ((data.size shr 24) and 0xff).toByte()
            )
        )
        return out.toByteArray()
    }

    companion object {
        private const val TAG = "Hongguo"

        /** 播放 host 与画像四元组：改读 HongguoNetProfiles（version.json `hg_net`
         *  热更覆盖，2026-11-21 起；探针实证通过的组合就是其内置档——b 线 host +
         *  运行设备真实画像（HongguoEnv），HGTV F0.n3.f2118b 集群 a/b/c
         *  + lf/lq/hl 共 6 个候选）。 */

        /** video_tab 路径（果映形态无尾斜线；带不带尾斜线实测都放行）。 */
        private const val TAB_PATH = "/reading/bookapi/video_tab/multi_video_model/v1"
        internal const val PLAYER_PATH = "/novel/player/multi_video_model/player/v1"
        internal const val PRELOAD_PATH = "/novel/player/multi_video_model/preload/v1"

        /** `video_model/v1` 单集直取（官方当前客户端形态，2026-11-25 接入）：
         *  域名是 `reading.snssdk.com`（不是 fqnovel 域），路径带尾斜线。 */
        internal const val VIDEO_MODEL_HOST = "https://reading.snssdk.com"
        internal const val VIDEO_MODEL_PATH = "/novel/player/video_model/v1/"

        /** 官方当前客户端公共参数集里的固定画像（官方 7.4.5.32 常见机型；这一路要求
         *  「像官方手机客户端」，与注册身份的真实机型刻意分离）。 */
        private const val CURRENT_CHANNEL = "update_64"
        private const val CURRENT_DEVICE_TYPE = "25053RT47C"
        private const val CURRENT_DEVICE_BRAND = "Redmi"
        private const val CURRENT_OS_VERSION = "16"
        private const val CURRENT_OS_API = "36"

        /** `dr_scene`：官方起播用 player、预取用 preload（2026-11-13 抓包 9/20 条样本一致）。 */
        internal const val DR_PLAYER = "player"
        internal const val DR_PRELOAD = "preload"

        /** 播放接口 200 空 body 的异常 code：服务端限流特征，与设备身份无关（换注册方式
         *  同样空），不触发上层重注册，只触发熔断。 */
        const val EMPTY_BODY = "EMPTY_BODY"

        /** 熔断冷却期内的异常 code（未发网络请求）。 */
        const val COOLDOWN = "COOLDOWN"

        /** V5 模板画像：与 [HongguoPlaybackV5] 签名 proto 同源（proto 从 query 取
         *  channel/device_type/os_version），版本号另走 [HongguoVersionProfiles.default]。 */
        private const val V5_CHANNEL = "vivo_8662_64"
        private const val V5_DEVICE_TYPE = "PGT-AN10"
        private const val V5_OS_VERSION = "12"

        // 预取 2026-11-25 起改走 video_model 逐集（旧的批量 preload/v1 已失效且会污染熔断），
        // 不再需要集间限速；原先的 PREFETCH_GAP_MS(250→800) 随逐集请求一起删掉。

        // ---- 空响应熔断（2026-11-23 探针实证）----
        // 服务端对短时密集的播放接口请求回 200 空 body，封禁期内继续请求不提前解除，
        // 完全静默 8~30 分钟后恢复（当天 4 次变空全部发生在密集请求之后）。收到空 body
        // 即按连续次数递增冷却，冷却期内的取址直接拒绝、不发请求；成功一次清零。进程内存态。

        private const val COOLDOWN_BASE_MS = 3L * 60_000L
        private const val COOLDOWN_MAX_MS = 30L * 60_000L

        @Volatile private var blockedUntil = 0L
        @Volatile private var emptyStreak = 0

        /** 递增冷却：3 → 6 → 12 → 24 → 30 分钟封顶。 */
        internal fun backoffMs(streak: Int): Long =
            (COOLDOWN_BASE_MS shl (streak - 1).coerceIn(0, 4)).coerceAtMost(COOLDOWN_MAX_MS)

        /** 记一次空响应并进入冷却，返回本次冷却时长。 */
        internal fun noteEmptyBody(): Long {
            emptyStreak += 1
            val backoff = backoffMs(emptyStreak)
            blockedUntil = System.currentTimeMillis() + backoff
            return backoff
        }

        /** 冷却剩余毫秒；0 = 可发请求。 */
        internal fun cooldownRemainingMs(now: Long = System.currentTimeMillis()): Long =
            (blockedUntil - now).coerceAtLeast(0L)

        /** 取址成功：连续空响应计数与冷却清零。 */
        internal fun noteSuccess() {
            emptyStreak = 0
            blockedUntil = 0L
        }

        internal fun resetCooldownForTest() = noteSuccess()

        /** 播放 UA：照抄官方 App 抓包格式（`zh_CN_#Hans` + `;tt-ok/…`），机型/系统版本取
         *  [HongguoEnv]（与 query 画像同源）。 */
        internal fun ua(versionCode: String): String = HongguoEnv.uaPlayback(versionCode)
    }

    /**
     * `preload` 路由的传输（2026-11-23 改走 V5 签名）：query 经 [HongguoPlaybackV5.sign] 把 medusa
     * selector 调谐到 12，头集对齐 HGTV 1.3.8.x V5 分支（L3.h，21 个头同名同序）。这份形态
     * 2026-11-24 00:02 在服务端收紧 medusa 校验后真网放行（`HgMedusaVerifyProbeTest`）。
     *
     * 此前这里用 Argus 通道的 signRequest 出 query 再补 medusa：query 的 selector 没调谐，
     * 15/16 的概率与只实现了 12 号分支的 medusa 不自洽。[body] 为 gzip 后的字节，签名与发送同一份。
     */
    private fun v5SignedPost(
        baseUrl: String, q: List<Pair<String, String>>, body: ByteArray, ua: String
    ): String {
        val req = buildPlayRequest(baseUrl, q, body, ua)
        http.newCall(req).execute().use { resp ->
            val bytes = resp.body?.bytes() ?: ByteArray(0)
            // 路径带上端点名：player/preload 两路共用本函数，写死 "preload" 会误导排障
            AppLog.i(TAG, "play ${baseUrl.substringAfterLast("/novel/player/")} resp http=${resp.code} len=${bytes.size}")
            // 空 body 诊断（2026-11-25）：被拒时唯一线索只能在响应头（logid / Retry-After /
            // Server / Set-Cookie / Content-Length）。Set-Cookie 只留名字不打值，避免票据入日志。
            if (bytes.isEmpty()) {
                val hdrs = (0 until resp.headers.size).joinToString(" | ") { i ->
                    val name = resp.headers.name(i)
                    val value = if (name.equals("Set-Cookie", true)) "<omitted>" else resp.headers.value(i).take(160)
                    "$name=$value"
                }
                AppLog.w(TAG, "play empty body headers [$hdrs]")
            }
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            return String(bytes, Charsets.UTF_8)
        }
    }

    /**
     * 构造 `player`/`preload` 路由的请求（不发送）：V5 签名 + 头集组装都在这里，
     * 签名与 body 为同一份字节。
     *
     * 头集**照抄官方 App 7.4.5.32 的 14 个头、同序**（2026-11-13 抓包，9 条 player/v1 完全一致）：
     * X-SS-STUB / X-Xs-From-Web / X-SS-REQ-TICKET / x-reading-request / x-vc-bdturing-sdk-version /
     * x-tt-store-region / x-tt-store-region-src / User-Agent / X-Ladon / X-Khronos / X-Argus /
     * X-Gorgon / X-Helios / X-Medusa。此前沿用 HGTV 的 21 头集（多 Accept、sdk-version、
     * passport-sdk-version、lc、x-ss-dp），官方起播请求里没有这些，已去掉。
     */
    internal fun buildPlayRequest(
        baseUrl: String, q: List<Pair<String, String>>, body: ByteArray, ua: String
    ): Request {
        val signed = HongguoPlaybackV5.sign(q, System.currentTimeMillis(), body)
        val signedHeaders = signed.headers.toMap()
        val nowSec = requireNotNull(signedHeaders["x-khronos"]).toInt()
        val ticket = HongguoMedusa.queryValue(signed.query, "_rticket") ?: ""
        val random = java.security.SecureRandom()
        // argus/ladon 装饰头（b64(le32(ts)) / b64(rand4)）：播放接口真正的内容签名是
        // gorgon + helios + medusa，这两个只需形状合法
        fun b64le32(v: Int) = java.util.Base64.getEncoder().encodeToString(
            java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(v).array()
        )
        val fakeLadon = java.util.Base64.getEncoder().encodeToString(ByteArray(4).also { random.nextBytes(it) })
        val headers = listOf(
            "X-SS-STUB" to requireNotNull(signedHeaders["x-ss-stub"]),
            "X-Xs-From-Web" to "0",
            "X-SS-REQ-TICKET" to ticket,
            "x-reading-request" to "$ticket-${random.nextLong() and 0xffffffffL}",
            "x-vc-bdturing-sdk-version" to "4.0.3.cn",
            "x-tt-store-region" to "cn-sc",
            "x-tt-store-region-src" to "did",
            "User-Agent" to ua,
            "X-Ladon" to fakeLadon,
            "X-Khronos" to nowSec.toString(),
            "X-Argus" to b64le32(nowSec),
            "X-Gorgon" to HongguoCrypto.gorgon(signed.query, body, nowSec, random.nextInt(65536)),
            "X-Helios" to requireNotNull(signedHeaders["x-helios"]),
            "X-Medusa" to requireNotNull(signedHeaders["x-medusa"]),
            // 传输层（抓包 hook 没记这两个，但 body 确实是 gzip：X-SS-STUB == MD5(gzip body)）
            "Content-Type" to "application/json; charset=utf-8",
            "Content-Encoding" to "gzip",
        )
        return Request.Builder()
            .url("$baseUrl?${signed.query}")
            .post(body.toRequestBody("application/json; charset=UTF-8".toMediaType()))
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()
    }

    // ---- entry 解析：video_model 内联或 fallback_api 二跳（对照 parseEntryPlayInfo） ----
    private fun parseEntry(vid: String, entry: JSONObject, preferredPixels: Int = 0): PlayUrl {
        var model = entry.optJSONObject("video_model")
        if (model == null) {
            val modelStr = entry.optString("video_model")
            if (modelStr.isNotEmpty()) {
                try { model = JSONObject(modelStr) } catch (_: Exception) { }
            }
        }
        if (model == null) {
            throw PlayException("播放接口未返回本集片源模型", "MISSING_MODEL")
        }
        var info = model
        var inline = info.optJSONArray("video_list")?.length() ?: 0 > 0
        if (info.optJSONArray("video_list") == null) inline = false
        if (!inline) {
            // fallback_api 二跳：GET 返回 video_info.data
            val fallbackRaw = model.opt("fallback_api")
            val fallbackUrl = when (fallbackRaw) {
                is String -> {
                    val parsed = try { JSONObject(fallbackRaw) } catch (_: Exception) { null }
                    parsed?.optString("fallback_api")?.ifEmpty { fallbackRaw } ?: fallbackRaw
                }
                is org.json.JSONArray -> fallbackRaw.optString(0)
                else -> {
                    val obj = fallbackRaw as? JSONObject
                    obj?.optString("fallback_api") ?: ""
                }
            }
            if (fallbackUrl.isEmpty()) throw PlayException("播放接口未返回片源地址", "MISSING_MODEL")
            val outerText = http.newCall(
                Request.Builder().url(fallbackUrl)
                    .header("User-Agent", HongguoDeviceRegister.USER_AGENT)
                    .header("Accept", "application/json")
                    .build()
            ).execute().use { resp ->
                if (!resp.isSuccessful) throw PlayException("片源接口 HTTP ${resp.code}", "BUSINESS_REJECTED")
                resp.body?.string() ?: ""
            }
            if (outerText.isBlank()) throw PlayException("片源接口空响应", "EMPTY_RESPONSE")
            val outer = JSONObject(outerText)
            val outerCode = outer.optInt("Code", outer.optInt("code", 0))
            if (outerCode != 0) throw PlayException("片源接口拒绝", "BUSINESS_REJECTED")
            info = outer.optJSONObject("video_info")?.optJSONObject("data")
                ?: throw PlayException("片源接口缺少数据", "MISSING_MODEL")
        }

        // video_list 双形态：直取端点两种都见——JSONObject(键值)与 JSONArray(下标)
        data class Candidate(
            val obj: JSONObject, val codec: String, val codecRank: Int,
            val quality: String, val width: Int, val height: Int, val pixels: Int
        )
        val candidates = ArrayList<Candidate>()
        fun addItem(item: JSONObject?) {
            if (item == null) return
            if (item.optString("main_url").isEmpty() && item.optString("backup_url").isEmpty() &&
                item.optJSONArray("backup_url_list") == null
            ) return
            val meta = item.optJSONObject("video_meta") ?: JSONObject()
            val codec = item.optString("codec_type").ifEmpty { meta.optString("codec_type") }.lowercase()
            // 编码兼容优先：h264=0 / hevc=1；其余（bytevc2 等自研编码不支持）一律 3
            // 占位 rank2 已删——when 永假分支不可达，bytevc2 专属报错在 rows 为空时统一判
            val codecRank = when {
                codec in listOf("h264", "avc", "avc1") -> 0
                codec in listOf("hevc", "h265", "hvc1", "hev1", "bytevc1") -> 1
                else -> 3
            }
            val quality = item.optString("quality_desc").ifEmpty {
                item.optString("definition").ifEmpty { meta.optString("definition") }
            }
            var width = 0; var height = 0
            for (pair in listOf(
                listOf(item.optInt("vwidth", 0), item.optInt("vheight", 0)),
                listOf(item.optInt("width", 0), item.optInt("height", 0)),
                listOf(meta.optInt("vwidth", 0), meta.optInt("vheight", 0)),
                listOf(meta.optInt("width", 0), meta.optInt("height", 0))
            )) {
                if (pair[0] > 0 && pair[1] > 0) { width = pair[0]; height = pair[1]; break }
            }
            val pixels = if (width > 0 && height > 0) minOf(width, height)
            else Regex("\\d+").find(quality)?.value?.toIntOrNull() ?: 0
            candidates.add(Candidate(item, codec, codecRank, quality, width, height, pixels))
        }
        when (val vl = info.opt("video_list")) {
            is org.json.JSONArray -> for (i in 0 until vl.length()) addItem(vl.optJSONObject(i))
            is JSONObject -> for (key in vl.keys()) addItem(vl.optJSONObject(key))
        }
        val hasCompatible = candidates.any { it.codecRank < 2 }
        val rows = candidates.filter { it.codecRank < if (hasCompatible) 2 else 3 }
            .sortedWith(compareBy({ it.codecRank }, { -it.pixels }))
        if (rows.isEmpty()) {
            // 排障 dump：看 fplay/multi 响应里到底给了什么（2026-11-10 player/v1 形态；
            // video_list 为 JSONArray 时 optJSONObject 恒 null，附 codecs 汇总与二跳线索）
            runCatching {
                val codecs = candidates.joinToString(",") { it.codec }
                AppLog.w(TAG, "no codec candidates; info.keys=" + info.keys().asSequence().toList() +
                    " codecs=[" + codecs + "]" +
                    " video_list=" + (info.optJSONArray("video_list")?.toString()
                        ?.take(600) ?: "null") +
                    " fallback_api=" + model.opt("fallback_api")?.toString()?.take(200))
            }
            if (candidates.any { it.codec in listOf("bytevc2", "bvc2") } && !hasCompatible) {
                throw PlayException("本集使用 ByteVC2 编码，当前播放器暂不支持", "UNSUPPORTED_BYTEVC2")
            }
            throw PlayException("本集暂未提供兼容的 H.264/HEVC 片源", "MISSING_MODEL")
        }
        // 按目标档位选候选（Candidate 是本函数局部类，匹配逻辑收在函数内）：
        // preferred<=0 或候选全无尺寸信息 = 默认取最高档（rows.first）；
        // 有目标时取「不超过目标的最高档」，全超目标取最低档（就近降级），
        // 同档内保持编码兼容优先（rows 已按编码兼容优先、像素降序排好）。
        fun pickByPreference(preferred: Int): Candidate {
            if (preferred <= 0) return rows.first()
            val withPixels = rows.filter { it.pixels > 0 }
            if (withPixels.isEmpty()) return rows.first()
            val target = withPixels.filter { it.pixels <= preferred }.maxByOrNull { it.pixels }
                ?: withPixels.minByOrNull { it.pixels }!!
            return rows.first { it.pixels == target.pixels }
        }
        val best = pickByPreference(preferredPixels)
        // 进度条雪碧图（big_thumbs 与 video_list 平级，恒在 video_model 层；多条取格子最大者）
        val bigThumbs = model.optJSONArray("big_thumbs")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                .maxByOrNull { it.optInt("img_x_size", 0) }
                ?.let { t ->
                    val u = t.optString("img_url").ifEmpty {
                        t.optJSONArray("img_urls")?.optString(0) ?: ""
                    }
                    val cellW = t.optInt("img_x_size", 0)
                    val cellH = t.optInt("img_y_size", 0)
                    val xLen = t.optInt("img_x_len", 0)
                    val interval = t.optInt("interval", 0)
                    if (u.isNotEmpty() && cellW > 0 && cellH > 0 && xLen > 0 && interval > 0) {
                        BigThumbs(
                            imageUrl = u,
                            cellWidth = cellW,
                            cellHeight = cellH,
                            xLen = xLen,
                            intervalSec = interval,
                            frameCount = t.optInt("img_num", 0)
                        )
                    } else null
                }
        }
        // 本集全部可用档位（兼容编码候选，降序去重）供播放 UI 画质弹窗展示
        val availableQualities = candidates
            .filter { it.codecRank < (if (hasCompatible) 2 else 3) && it.pixels > 0 }
            .map { it.pixels }.distinct().sortedDescending()
        val spade = best.obj.optString("spade_a").ifEmpty {
            best.obj.optJSONObject("encrypt_info")?.optString("spade_a") ?: ""
        }
        // kid（CENC key id）：encrypt_info.kid / model 顶层 / 选中流对象三处依次找
        // （同集各档同 kid，openhongguo 2026-10-24 观察）。形态可能是 hex(32) 或 base64(24/22)，
        // 统一归一到 32 位 hex；解不出时留 null（ClearKey 不可用，回落整集解密）。
        val kidRaw = best.obj.optJSONObject("encrypt_info")?.optString("kid")?.takeIf { it.isNotEmpty() }
            ?: model.optString("kid").takeIf { it.isNotEmpty() }
            ?: best.obj.optString("kid").takeIf { it.isNotEmpty() }
        val kidHex = kidRaw?.let { raw -> normalizeKeyHex(raw) }
        if (kidRaw != null && kidHex == null) {
            AppLog.w(TAG, "kid unparsable len=${kidRaw.length} vid=$vid")
        }
        // spade_a 可能是非常规形态（base64 解不出/长度不足，直取端点偶见），
        // 容错按无密钥明文处理（URL 为明文流时正确；真加密流会在播放期自然报错）
        val contentKey = if (spade.isNotEmpty()) runCatching { deriveContentKey(spade) }.getOrNull() else null
        val keySeed = info.optString("key_seed")
        val urls = ArrayList<String>()
        best.obj.optString("main_url").takeIf { it.isNotEmpty() }?.let { urls.add(it) }
        best.obj.optString("backup_url").takeIf { it.isNotEmpty() }?.let { urls.add(it) }
        best.obj.optJSONArray("backup_url_list")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optString(i).takeIf { it.isNotEmpty() }?.let { urls.add(it) }
            }
        }
        for (candidate in urls) {
            try {
                val media = if (keySeed.isNotEmpty()) {
                    decryptSpadeUrl(candidate, decodeFlexibleBase64(keySeed))
                } else {
                    val plain = cleanUrl(candidate)
                    if (plain.startsWith("http")) plain
                    else runCatching {
                        // V5 起 URL 可能以 Base64 文本下发（PR#13 playbackURL 明文变体）
                        String(decodeFlexibleBase64(candidate), Charsets.UTF_8)
                            .trimEnd('\u0000').trim()
                    }.getOrElse { plain }
                }
                val checked = checkedUrl(media)
                AppLog.d(
                    TAG, "play picked codec=${best.codec} quality=${best.quality} " +
                        "${best.width}x${best.height} encrypted=${contentKey != null} host=${java.net.URI(checked).host}" +
                        " preferred=$preferredPixels available=$availableQualities"
                )
                return PlayUrl(
                    url = checked,
                    contentKeyHex = contentKey?.let { HongguoCrypto.toHex(it) },
                    codec = when (best.codec) {
                        "bytevc1" -> "hevc"
                        "bvc2" -> "bytevc2"
                        else -> best.codec
                    },
                    quality = best.quality.ifEmpty { if (best.pixels > 0) "${best.pixels}p" else "" },
                    width = best.width,
                    height = best.height,
                    durationSec = model.optLong("video_duration", info.optLong("video_duration", 0L)),
                    vid = vid,
                    pixels = best.pixels,
                    availableQualities = availableQualities,
                    bigThumbs = bigThumbs,
                    kidHex = kidHex
                )
            } catch (_: Exception) {
                // 尝试下一候选 URL
            }
        }
        throw PlayException("本集片源地址无法解析", "INVALID_RESPONSE")
    }

    // ---- spade_a 内容密钥派生（对照 deriveContentKey） ----
    private fun deriveContentKey(value: String): ByteArray {
        val normalized = value.replace("-", "+").replace("_", "/")
        val raw = Base64.decode(normalized, Base64.DEFAULT)
        require(raw.size >= 3) { "spade_a too short" }
        var v8 = raw.size - ((raw[0].toInt() and 0xFF) xor (raw[1].toInt() and 0xFF) xor (raw[2].toInt() and 0xFF)) + 47
        v8 = minOf(v8, raw.size - 1)
        require(v8 >= 33) { "spade_a key length invalid" }
        val work = raw.copyOfRange(1, 1 + v8)
        var a = 85; var b = 246
        for (i in 0 until v8) {
            val old = work[i].toInt() and 0xFF
            val previous = if (i and 1 == 1) a else b
            if (i and 1 == 1) a = old else b = old
            val pop = Integer.bitCount(i)
            work[i] = ((-21 - pop + (previous xor old)) and 0xFF).toByte()
        }
        val hex = String(work.copyOfRange(1, 33), Charsets.US_ASCII)
        require(hex.matches(Regex("^[0-9a-fA-F]{32}$"))) { "App 视频密钥格式无效" }
        return HongguoCrypto.hexToBytes(hex.lowercase())
    }

    // ---- spade URL 解密（对照 decryptSpadeUrl：头 0xA8 xx 0x01 0x00 + AES-128-CBC） ----
    private fun decryptSpadeUrl(value: String, seed: ByteArray): String {
        val raw = decodeFlexibleBase64(value)
        require(raw.size >= 5) { "spade URL too short" }
        require((raw[0].toInt() and 0xFF) == 0xA8 && (raw[2].toInt() and 0xFF) == 0x01 && (raw[3].toInt() and 0xFF) == 0x00) {
            "spade URL header format error"
        }
        val cipherLength = (raw.size - 4) / 16 * 16
        require(cipherLength > 0) { "spade URL no block" }
        val constants = HongguoCrypto.hexToBytes(
            "4dd4c2e6b83162090e52b3c7a6733ba41cb2462b829ab58a196b39db57177524f49baf7f08e8d68d26a72e37c1a95a2f1f05a51892aef2949732b62a38aadd58"
        )
        val h1 = HongguoCrypto.sha512(seed)
        val h2 = HongguoCrypto.sha512(h1 + constants)
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(h2.copyOfRange(0, 16), "AES"), IvParameterSpec(h2.copyOfRange(16, 32)))
        var plaintext = cipher.doFinal(raw.copyOfRange(4, 4 + cipherLength))
        if (plaintext.isNotEmpty()) {
            val padding = plaintext[plaintext.size - 1].toInt() and 0xFF
            if (padding in 1..16 && padding <= plaintext.size) {
                plaintext = plaintext.copyOfRange(0, plaintext.size - padding)
            }
        }
        var end = plaintext.size
        while (end > 0 && plaintext[end - 1] == 0.toByte()) end--
        return String(plaintext.copyOfRange(0, end), Charsets.UTF_8)
    }

    private fun cleanUrl(value: String): String {
        // 去掉可能的外层引号与空白
        return value.trim().trim('"')
    }

    /** URL/地址字段可能是明文 http(s)、标准 Base64 或 URL-safe 无填充变体（V5 起，
     *  PR#13 decodePlaybackBase64）；统一规范化到标准填充形态再解。 */
    private fun decodeFlexibleBase64(value: String): ByteArray {
        val normalized = value.trim().replace("-", "+").replace("_", "/")
            .let { it + "=".repeat((4 - it.length % 4) % 4) }
        return Base64.decode(normalized, Base64.DEFAULT)
    }

    /** kid/key 字段形态归一：hex(32) 原样；base64(24/22, 可能 URL-safe) 解码转 hex；
     *  其余返回 null（调用方回落旧路径）。 */
    private fun normalizeKeyHex(raw: String): String? {
        val s = raw.trim()
        if (s.matches(Regex("^[0-9a-fA-F]{32}$"))) return s.lowercase()
        if (s.length in 22..24) {
            return runCatching {
                HongguoCrypto.toHex(decodeFlexibleBase64(s))
            }.getOrNull()?.takeIf { it.length == 32 }
        }
        return null
    }

    private fun checkedUrl(value: String): String {
        val uri = java.net.URI(value)
        val host = uri.host ?: throw PlayException("片源地址格式无效", "INVALID_RESPONSE")
        val domains = listOf("qznovelvod.com", "fqnovelvod.com", "douyinvod.com")
        val okHost = domains.any { host == it || host.endsWith(".$it") }
        if (uri.scheme != "https" || !okHost) {
            throw PlayException("片源返回了不受支持的服务地址", "SECURITY_BLOCKED")
        }
        return value
    }
}
