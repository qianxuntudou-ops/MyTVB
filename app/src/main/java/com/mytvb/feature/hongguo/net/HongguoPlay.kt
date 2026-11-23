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
 * 红果播放地址解析。取源路由按 [HongguoNetProfiles.playRoutes] 顺序回退（version.json
 * `hg_net.play_routes` 热更，默认只开 v5tab）：
 * - `v5tab`（2026-11-23 起首选）：V5 签名 `GET /reading/bookapi/video_tab/multi_video_model/v1`。
 *   服务端 11-23 起把 POST 播放端点（preload/v1、player/v1、video_model/v1）全部收成
 *   200 空 body，只放行这一条；它只认 x-medusa，尾斜线/版本/stub/UA 等形态细节不敏感，
 *   注册方式与入口 IP 无关（果映TV 11-23 ff4d1d2 独立实证同结论）；
 * - `preload`（次选，默认关）：preload/v1 批量 POST + Argus/Ladon/Gorgon 全头集，11-21~11-22 放行。
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
     * 按路由顺序取源：首个返回片源的 entry 胜出；全部失败抛最后一路的异常
     * （code 透传给上层恢复链判定）。空 body 即服务端限流，进入熔断后不再回退
     * 下一路由（同样会空，且续封）。
     */
    private fun fetchEntry(vid: String, identity: HongguoDeviceRegister.Identity): JSONObject {
        checkCooldown()
        val routes = HongguoNetProfiles.playRoutes()
        var lastError: Exception? = null
        for ((index, route) in routes.withIndex()) {
            try {
                val entry = when (route) {
                    HongguoNetProfiles.ROUTE_V5TAB -> fetchTabEntry(vid, identity)
                    HongguoNetProfiles.ROUTE_PRELOAD -> fetchPreloadEntry(vid, identity)
                    // 路由名在 HongguoNetProfiles 解析时已过滤，此处仅防御
                    else -> throw PlayException("未知取源路由 $route", "UNKNOWN_ROUTE")
                }
                noteSuccess()
                return entry
            } catch (e: Exception) {
                if (e is PlayException && e.code == EMPTY_BODY) throw e
                lastError = e
                if (index < routes.lastIndex) {
                    AppLog.w(TAG, "play route=$route failed, fall back to ${routes[index + 1]}: ${e.message}")
                }
            }
        }
        throw lastError ?: PlayException("没有可用的取源路由", "NO_ROUTE")
    }

    /** 收到空 body：记连续次数、按 [backoffMs] 进入冷却，并以 [EMPTY_BODY] 抛出。 */
    private fun onEmptyBody(path: String): Nothing {
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
     * video_tab 响应 → 本集 entry（含 `video_model` 键，交 [parseEntry] 二跳）。
     * 行在 `data.video_model_data[vid]`，少数形态直接 `data[vid]`；都没有则取首行兜底。
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
            ?: runCatching { rows.optJSONObject(rows.keys().next()) }.getOrNull()
            ?: throw PlayException("播放接口未返回本集片源", "MISSING_MODEL")
    }

    /**
     * 后续剧集地址预取：逐集走与 [playUrl] 相同的路由（video_tab 是单集接口），集间限速。
     * 任一集**取址**失败即止——服务端回空 body 时继续打只会多制造空响应；单集**解析**
     * 失败（如片源不兼容）只跳过该集。返回成功解析的 vid→PlayUrl（保持入参顺序），
     * 未命中的集切到时实时解析兜底。
     */
    fun playUrlBatch(
        vids: List<String>,
        identity: HongguoDeviceRegister.Identity,
        preferredPixels: Int = 0,
    ): Map<String, PlayUrl> {
        require(vids.isNotEmpty()) { "预取集数为空" }
        require(vids.all { it.matches(Regex("\\d{1,30}")) }) { "分集编号无效" }
        val out = LinkedHashMap<String, PlayUrl>()
        for ((index, vid) in vids.withIndex()) {
            val entry = try {
                fetchEntry(vid, identity)
            } catch (e: Exception) {
                if (e is PlayException && e.code == COOLDOWN) {
                    // 冷却期内预取静默放弃（不发网络；前台起播时会给出限流提示）
                    AppLog.d(TAG, "prefetch skip: cooldown")
                } else {
                    AppLog.w(TAG, "prefetch stop at vid=$vid: ${e.message}")
                }
                break
            }
            runCatching { out[vid] = parseEntry(vid, entry, preferredPixels) }
            if (index < vids.lastIndex) Thread.sleep(PREFETCH_GAP_MS)
        }
        return out
    }

    /**
     * `preload` 路由（次选，默认关）：2026-11-20 晚~11-22 的放行端点，11-23 起 200 空 body。
     * 对齐 HGTV 1.3.7.1 主通道（V 函数 multi 分支 + kotlinSignedPost 11-10 形态传输）。要点：
     * - body：`mixed_video_id_map` 单集形态 + `dr_scene="preload"`、gzip；device_level
     *   空响应 4→5 升级重发、video_platform=1024、use_server_dns=true（对齐 HGTV 1.3.8.6 o0）；
     * - 传输：老 Argus/Ladon/Gorgon 全头集（kotlinSignedPost 复活；V5 medusa/helios
     *   纯头集与端点组合在探针实验中全挂）；
     * - 画像三处同源：query device_type/brand + Argus deviceProfile + UA 一律
     *   Redmi 25053RT47C / Android 16 / update_64（探针通过组合，勿用运行设备
     *   真实型号——模拟器型号未验证）；
     * - 版本走 [HongguoVersionProfiles.default]（74532 探针实证通过；73932 亦过，
     *   保持热更档案能力）；
     * - 响应 data 按 vid 分 key（1004 分组兜底），entry.video_model 字符串二跳
     *   由 [parseEntry] 解开。
     */
    private fun fetchPreloadEntry(
        vid: String,
        identity: HongguoDeviceRegister.Identity
    ): JSONObject {
        val data = fetchPreloadData(listOf(vid), identity)
        return data.optJSONObject(vid)
            ?: data.optJSONObject("video_model")
            ?: runCatching { data.optJSONObject(data.keys().next()) }.getOrNull()
            ?: throw PlayException("播放接口未返回本集片源", "MISSING_MODEL")
    }

    /** preload/v1 数据获取（单集/批量共用主体），返回 vid→entry 的 data 对象。
     *  空 body 自动重试对齐 HGTV o0：device_level 4→5 升级立即重发一次（会话级
     *  单调不降，[deviceLevel]），到顶仍空才判失败——这是它唯一的自动重试策略。 */
    private fun fetchPreloadData(
        vids: List<String>,
        identity: HongguoDeviceRegister.Identity
    ): JSONObject {
        AppLog.d(TAG, "play preload/v1 vids=${vids.size} first=${vids.first()} level=$deviceLevel device=${identity.deviceId}")
        val ver = HongguoVersionProfiles.default
        val q = java.util.LinkedHashMap<String, String>()
        q["device_id"] = identity.deviceId
        q["iid"] = identity.installId
        if (identity.deviceToken.isNotEmpty()) q["device_token"] = identity.deviceToken
        q["ac"] = "wifi"; q["channel"] = HongguoNetProfiles.channel(); q["aid"] = "8662"
        q["app_name"] = "novelread"
        q["version_code"] = ver.code; q["version_name"] = ver.versionName
        q["device_platform"] = "android"; q["os"] = "android"; q["ssmix"] = "a"
        q["device_type"] = HongguoNetProfiles.deviceType()
        q["device_brand"] = HongguoNetProfiles.deviceBrand(); q["language"] = "zh"
        q["os_api"] = "36"; q["os_version"] = HongguoNetProfiles.osVersion()
        q["manifest_version_code"] = ver.code
        q["resolution"] = "1280*2772"; q["dpi"] = "520"
        q["update_version_code"] = ver.code; q["host_abi"] = "arm64-v8a"
        q["dragon_device_type"] = "phone"; q["pv_player"] = ver.code
        q["compliance_status"] = "0"; q["need_personal_recommend"] = "1"
        q["player_so_load"] = "1"; q["is_android_pad_screen"] = "0"
        // 诊断期：响应摘要塞进异常 message（android_logs 只回传异常行，message 可观测；
        // 真机验证稳定后 resp 探针可缩短或移除）
        var respProbe = ""
        return runCatching {
            var text: String
            while (true) {
                // gzip body 同字节签名发送（对齐 HGTV o0：GZIPOutputStream + Content-Encoding）
                text = kotlinSignedPost(
                    "${HongguoNetProfiles.playHost()}$PRELOAD_PATH",
                    q, gzipDeflate(preloadBody(vids, deviceLevel)), ver.versionName, identity.deviceId, ua(ver.code)
                )
                respProbe = text.take(1200)
                if (text.isNotEmpty()) break
                if (deviceLevel < 5) {
                    AppLog.w(TAG, "play preload empty body, escalate device_level $deviceLevel->${deviceLevel + 1}")
                    deviceLevel += 1
                    continue
                }
                onEmptyBody(PRELOAD_PATH)
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

    /** preload/v1 批量 body（对齐 HGTV 1.3.8.6 o0 形态：device_level 4~5 /
     *  use_server_dns=true + cold_start_stage / video_platform=1024 /
     *  dr_scene=preload / mixed_video_id_map；1.3.8.6 对响应无 server_dns
     *  特殊解析，结构与 false 相同）。 */
    private fun preloadBody(vids: List<String>, deviceLevel: Int): ByteArray {
        val arr = org.json.JSONArray()
        vids.forEach { arr.put(it) }
        return JSONObject()
            .put(
                "biz_param", JSONObject()
                    .put("detail_page_version", 0)
                    .put("device_level", deviceLevel)
                    .put("disable_digg_stat", false)
                    .put("disable_video_relate_book", false)
                    .put("need_all_video_definition", true)
                    .put("need_mp4_align", false)
                    .put("use_os_player", false)
                    .put("use_server_dns", true)
                    .put("use_server_dns_scene", "cold_start_stage")
                    .put("video_platform", 1024)
            )
            .put("dr_scene", "preload")
            .put("mixed_video_id_map", JSONObject().put("1004", arr))
            .toString().toByteArray(Charsets.UTF_8)
    }

    /** 设备能力档（对齐 HGTV c0176k0.f2393w0：初始 4，空响应升 5 重发，会话级
     *  单调不降——成功后停留在成功档，下次起播免再撞空）。 */
    @Volatile private var deviceLevel = 4

    /** 标准 gzip（HGTV G0/o0 用 GZIPOutputStream；同一份字节既签名又发送）。 */
    private fun gzipDeflate(data: ByteArray): ByteArray {
        val bos = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(bos).use { it.write(data) }
        return bos.toByteArray()
    }

    companion object {
        private const val TAG = "Hongguo"

        /** 播放 host 与画像四元组：改读 HongguoNetProfiles（version.json `hg_net`
         *  热更覆盖，2026-11-21 起；探针实证通过的组合就是其内置档——b 线 host +
         *  Redmi 25053RT47C / Android 16 / update_64，HGTV F0.n3.f2118b 集群 a/b/c
         *  + lf/lq/hl 共 6 个候选）。 */

        /** video_tab 路径（果映形态无尾斜线；带不带尾斜线实测都放行）。 */
        private const val TAB_PATH = "/reading/bookapi/video_tab/multi_video_model/v1"
        private const val PRELOAD_PATH = "/novel/player/multi_video_model/preload/v1"

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

        /** 批量预取集间限速：密集请求会让服务端把后续请求一并回空（2026-11-23 探针实证）。 */
        private const val PREFETCH_GAP_MS = 250L

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

        /** 播放 UA（X3.d 形态，与 query 画像同源；勿用 HongguoEnv 运行设备型号——
         *  模拟器型号未验证，探针通过的是本硬编码形态）。 */
        private fun ua(versionCode: String): String =
            "com.phoenix.read/$versionCode (Linux; U; Android ${HongguoNetProfiles.osVersion()}; zh_CN; " +
                "${HongguoNetProfiles.deviceType()}; Build/BP2A.250605.031.A3; " +
                "Cronet/TTNetVersion:8d40f833 2026-03-03 QuicVersion:21ac1950 2025-11-18)"
    }

    /**
     * 纯 Kotlin 签名传输（2026-11-10 去黑盒定稿形态，11-20 晚随 preload/v1 切换复活，
     * 现为 `preload` 路由的传输层）：按 HGTV F0.x1.d 形态构造 Argus/Ladon/Gorgon 全头集
     * （E0 外层+G0 改写后）发送。明文 body（preload 探针实证形态）。
     */
    private fun kotlinSignedPost(
        baseUrl: String, q: java.util.LinkedHashMap<String, String>, body: ByteArray,
        versionName: String, deviceId: String, ua: String
    ): String {
        val signed = HongguoCrypto.signRequest(
            q, body, System.currentTimeMillis(), deviceId, targetBranch = 0, version = versionName
        )
        val nowSec = signed.headers["X-Khronos"]?.toIntOrNull() ?: (System.currentTimeMillis() / 1000).toInt()
        // argus/ladon 对齐 HGTV 1.3.8.6 假头（J3.h: b64(le32(ts)) / b64(rand4)）——
        // 播放接口真正的内容签名是 gorgon+medusa，装饰头用最便宜形态；真 Argus
        // （HongguoArgus 完整实现）反而是画像异类（正常客户端这里是 8 字符）。
        fun b64le32(v: Int) = java.util.Base64.getEncoder().encodeToString(
            java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(v).array()
        )
        val fakeArgus = b64le32(nowSec)
        val fakeLadon = java.util.Base64.getEncoder().encodeToString(ByteArray(4).also {
            java.security.SecureRandom().nextBytes(it)
        })
        val ticket = signed.headers["X-SS-Req-Ticket"] ?: ""
        val headers = mutableListOf(
            // E0 外层 7 头
            "User-Agent" to ua,
            "Accept-Language" to "zh-CN,zh;q=0.9",
            "X-SS-DP" to "8662",
            "X-Tt-Store-Region" to "cn",
            "X-Tt-Store-Region-Src" to "did",
            "X-XS-From-Web" to "0",
            "Sdk-Version" to "2",
            // F0.x1.d 签名头集（G0 改写后）
            "Accept" to "application/json; charset=utf-8,application/x-protobuf",
            "x-ss-req-ticket" to ticket,
            "sdk-version" to "2",
            "passport-sdk-version" to "5051452",
            "x-xs-from-web" to "0",
            "x-khronos" to (signed.headers["X-Khronos"] ?: ""),
            "x-ladon" to fakeLadon,
            "x-argus" to fakeArgus,
            "x-gorgon" to (signed.headers["X-Gorgon"] ?: ""),
            "x-helios" to HongguoArgus.helios(nowSec),
            // x-medusa（2026-11-21 补齐）：HGTV 1.3.8.6 播放主力内容签名（G1.c v5），
            // 当前服务端未强制但属最大未对齐点——开启校验时兜底。
            "x-medusa" to HongguoPlaybackV5.medusaHeaderFor(signed.query, body, nowSec),
            "Content-Type" to "application/json; charset=UTF-8",
            "x-vc-bdturing-sdk-version" to "4.0.3.cn",
            "x-reading-request" to "$ticket-${java.security.SecureRandom().nextLong() and 0xffffffffL}",
            "x-ss-stub" to (signed.headers["X-SS-STUB"] ?: ""),
            // gzip body 声明（2026-11-21 对齐 HGTV o0：签名与发送同一份 gzip 字节）
            "Content-Encoding" to "gzip",
            // G0 追加
            "lc" to "101",
            "x-ss-dp" to "8662",
            "x-tt-trace-id" to traceId()
        )
        val url = "$baseUrl?${signed.query}"
        val req = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json; charset=UTF-8".toMediaType()))
            .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
            .build()
        http.newCall(req).execute().use { resp ->
                val bytes = resp.body?.bytes() ?: ByteArray(0)
                AppLog.i(TAG, "play preload resp http=${resp.code} len=${bytes.size}")
                if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                return String(bytes, Charsets.UTF_8)
        }
    }


    /** x-tt-trace-id（W3C traceparent 变体，对齐 HGTV W2.b()）。 */
    private fun traceId(): String {
        val hex = java.util.UUID.randomUUID().toString().replace("-", "")
        return "00-$hex-${hex.substring(0, 16)}-01"
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
