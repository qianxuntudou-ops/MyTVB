package com.mytvb.feature.hongguo.net

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.TreeMap
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 红果播放地址解析：multi_video_model/v1（签名 POST）→ video_model 内联/fallback 二跳
 * → video_list 编码过滤 → spade_a 密钥派生 → URL 解密 + CDN 域名白名单。
 * 对照 Node 版 app-source.js fetchAppPlayUrl/parseEntryPlayInfo（线上验证链路）。
 */
class HongguoPlay(
    private val client: HongguoClient = HongguoClient(),
    /**
     * 设备自愈钩子（HGTV b.java "device healed" 同款语义）：空响应=号被拒时换新号。
     * 注入方负责持久化（HGTV O0/E.java registered-* 持久化复用模型）；不注入则现场注册不落盘。
     */
    private val rotateIdentity: () -> HongguoDeviceRegister.Identity =
        { HongguoDeviceRegister.registerFresh() }
) {

    private val http: OkHttpClient by lazy {
        // 共享连接池单例：播放必须复用注册连接（连接会话模型，见 HongguoHttp）
        HongguoHttp.ok
    }

    data class PlayUrl(
        val url: String,
        val contentKeyHex: String?,
        val codec: String,
        val quality: String,
        val width: Int,
        val height: Int,
        val durationSec: Long,
        val vid: String
    )

    class PlayException(message: String, val code: String) : IllegalStateException(message)

    /**
     * @param identity 真注册设备身份（multi_video_model 校验设备真实性，
     * 匿名随机 id 会被空响应拒绝）。可用 [HongguoDeviceRegister.registerFresh] 获取后持久化复用。
     */
    fun playUrl(
        vid: String,
        identity: HongguoDeviceRegister.Identity,
        allowBytevc2: Boolean = false,
        transport: ((url: String, headers: Map<String, String>, body: ByteArray) -> String)? = null
    ): PlayUrl {
        require(vid.matches(Regex("\\d{1,30}"))) { "分集编号无效" }
        // 通道 A：guoapp 通道（video_model/v1 单视频端点，随机号即可，线上成品在用）
        val singleBody = JSONObject()
            .put("video_id", vid)
            .put("content_type", 1)
            .put(
                "biz_param", JSONObject()
                    .put("need_all_video_definition", true)
                    .put("video_platform", 3)
            )
        // 通道 B：multi_video_model（Node playbackRequest 验证版 body）
        val multiBody = JSONObject()
            .put(
                "biz_param", JSONObject()
                    .put("detail_page_version", 0)
                    .put("device_level", 3)
                    .put("disable_digg_stat", false)
                    .put("need_all_video_definition", true)
                    .put("need_mp4_align", false)
                    .put("use_os_player", false)
                    .put("use_server_dns", false)
                    .put("video_platform", 1024)
            )
            .put("mixed_video_id_map", JSONObject().put("1004", org.json.JSONArray().put(vid)))
        // 通道 C：HGTV 完整复刻（player/v1 + gzip + 73932；body 带其扩展字段）
        // video_platform=3（HGTV T1 默认）：1024 会请求到 ByteVC2 自研编码，
        // 3 请求通用 h264/hevc（2026-11-10 实测 7703473857562151961 修复）
        val hgtvBody = JSONObject()
            .put(
                "biz_param", JSONObject()
                    .put("detail_page_version", 0)
                    .put("device_level", 3)
                    .put("disable_digg_stat", false)
                    .put("disable_video_relate_book", false)
                    .put("need_all_video_definition", true)
                    .put("need_mp4_align", false)
                    .put("use_os_player", false)
                    .put("use_server_dns", true)
                    .put("use_server_dns_scene", "cold_start_stage")
                    .put("video_platform", 3)
            )
            .put("dr_scene", "player")
            .put("mixed_video_id_map", JSONObject().put("1004", org.json.JSONArray().put(vid)))
        // 端点策略（2026-11-10 实测定案）：主=player/v1（73932 档案，大部分剧）；
        // player/v1 对部分内容报 code=100001 参数错误 → fetchModelEntry 内置
        // L1-M1 兜底（sinfonlineb multi/v1 + 71332 档案，编码直接下发 hevc/h264）。
        val entry = fetchModelEntry(
            vid,
            listOf(
                "/novel/player/multi_video_model/player/v1" to hgtvBody.toString().toByteArray(Charsets.UTF_8),
                "/novel/player/video_model/v1/" to singleBody.toString().toByteArray(Charsets.UTF_8),
                "/novel/player/multi_video_model/v1/" to multiBody.toString().toByteArray(Charsets.UTF_8)
            ),
            identity, transport
        )
        return parseEntry(vid, entry, allowBytevc2)
    }

    // ---- 播放签名 POST：HGTV 号模型（持久化复用+空响应自愈），host 集群轮询 ----
    private fun fetchModelEntry(
        vid: String,
        endpoints: List<Pair<String, ByteArray>>,
        identity: HongguoDeviceRegister.Identity,
        transport: ((String, Map<String, String>, ByteArray) -> String)?
    ): JSONObject {
        var lastError: Exception? = null
        val hgtvEndpoint = endpoints.first { it.first.contains("player/v1") }
        // 2026-11-10 夜：改 HGTV 号模型（对照 1.3.6 O0/E.java registered-* 持久化 +
        // b.java:2818 "device healed" 自愈）——一个号贯穿全部 host 轮询，仅空响应
        // （设备被拒信号）时经 [rotateIdentity] 自愈换号一次；player/v1 的 100001
        // 是业务拒绝不换号。替代旧「每号首请求」模型（每轮 registerFresh，一次
        // 播放 3-5 个号）：真机号可长期复用=0 注册，模拟器最坏 1-2 次/播放。
        var current = identity
        var rotated = false
        for (round in 0 until 3) {
            // 每轮=新 host（HGTV F0.n3.f() 同款 host 集群轮询），号不变，
            // 全部纯 Kotlin 签名+JVM okhttp 传输（2026-11-10 与字节码通道实测等价后 L1 已拆除）。
            val host = PLAY_HOSTS[round % PLAY_HOSTS.size]
            try {
                android.util.Log.d(
                    TAG, "play round=$round host=$host device=${current.deviceId} " +
                        "path=${hgtvEndpoint.first} vid=$vid via=${if (transport != null) "webview" else "okhttp"}"
                )
                val payload = postVideoModel(hgtvEndpoint.first, hgtvEndpoint.second, current, transport, hostOverride = host)
                return extractEntry(vid, payload)
            } catch (e: EmptyBodyException) {
                lastError = e
                // 设备自愈：整轮播放最多换一次号（旧号在 MuMu 环境次发即拒，真机不受影响）
                if (!rotated) {
                    current = runCatching { rotateIdentity() }.getOrElse { current }
                    rotated = true
                }
            } catch (e: PlayException) {
                // 业务拒绝/编码不兼容：换端点（platform=3 兜底）再试，不换号
                lastError = e
            }
        }
        // 兜底①：M1 端点（sinfonlineb multi/v1 + 71332 档案 + 明文 body）——
        // player/v1 收紧后的实际可用端点（HGTV 本尊 fallback_api 二跳同路径）。
        // 沿用当前号；空响应再自愈一次重发（两发保 K-M1 在两种环境下都首发可过）。
        val m1Body = endpoints.last { it.first.contains("multi_video_model/v1/") }.second
        val m1Url = "https://api5-normal-sinfonlineb.fqnovel.com/novel/player/multi_video_model/v1/"
        runCatching {
            var text = ""
            for (m1Attempt in 0 until 2) {
                if (m1Attempt == 1) current = runCatching { rotateIdentity() }.getOrElse { current }
                val m1q = java.util.LinkedHashMap<String, String>()
                m1q["device_id"] = current.deviceId
                m1q["iid"] = current.installId
                if (current.deviceToken.isNotEmpty()) m1q["device_token"] = current.deviceToken
                m1q["ac"] = "wifi"; m1q["channel"] = "update_64"; m1q["aid"] = "8662"
                m1q["app_name"] = "novelread"
                m1q["version_code"] = "71332"; m1q["version_name"] = "7.1.3.32"
                m1q["device_platform"] = "android"; m1q["os"] = "android"; m1q["ssmix"] = "a"
                m1q["device_type"] = "25053RT47C"; m1q["device_brand"] = "Redmi"; m1q["language"] = "zh"
                m1q["os_api"] = "36"; m1q["os_version"] = "16"
                m1q["manifest_version_code"] = "71332"
                m1q["resolution"] = "1280*2772"; m1q["dpi"] = "520"
                m1q["update_version_code"] = "71332"; m1q["host_abi"] = "arm64-v8a"
                m1q["dragon_device_type"] = "phone"; m1q["pv_player"] = "71332"
                m1q["compliance_status"] = "0"; m1q["need_personal_recommend"] = "1"
                m1q["player_so_load"] = "1"; m1q["is_android_pad_screen"] = "0"
                android.util.Log.d(TAG, "play fallback K-M1 vid=$vid attempt=$m1Attempt device=${current.deviceId}")
                text = kotlinSignedPost(m1Url, m1q, m1Body, "7.1.3.32", current.deviceId,
                    Triple("25053RT47C", "16", "update_64"), HongguoEnv.ua("71332"), gzipHeader = false)
                if (text.isNotEmpty()) return extractEntry(vid, JSONObject(text))
            }
            throw EmptyBodyException()
        }.onFailure { android.util.Log.w(TAG, "M1 fallback fail: ${it.message}") }
        // 兜底②：platform=3 与 std 端点（当前号直发，不再自愈省号）
        for ((path, raw) in endpoints.drop(1)) {
            try {
                android.util.Log.d(TAG, "play fallback path=$path vid=$vid device=${current.deviceId}")
                return extractEntry(vid, postVideoModel(path, raw, current, transport))
            } catch (e: EmptyBodyException) {
                lastError = e
            } catch (e: PlayException) {
                lastError = e
            }
        }
        throw lastError ?: PlayException("播放接口无响应", "EMPTY_RESPONSE")
    }

    private fun extractEntry(vid: String, payload: JSONObject): JSONObject {
        val code = payload.optInt("Code", payload.optInt("code", 0))
        if (code != 0) {
            val msg = payload.optString("message", payload.optString("Message", ""))
            android.util.Log.w(TAG, "play biz code=$code msg=$msg")
            throw PlayException("播放接口 code=$code${if (msg.isNotEmpty()) "：$msg" else ""}", "BUSINESS_REJECTED")
        }
        val data = payload.optJSONObject("data") ?: JSONObject()
        return data.optJSONObject("video_model")
            ?: data.optJSONObject(vid)
            ?: runCatching { data.optJSONObject(data.keys().next()) }.getOrNull()
            ?: throw PlayException("播放接口未返回所选分集", "MISSING_MODEL")
    }

    private class EmptyBodyException : IllegalStateException("empty body")

    private companion object {
        const val TAG = "Hongguo"

        /** 播放 host 集群（HGTV F0.n3.f2118b：a/b/c + lf/lq/hl 共 6 个，取前 3 轮询）。 */
        val PLAY_HOSTS = listOf(
            "https://api5-normal-sinfonlinea.fqnovel.com",
            "https://api5-normal-sinfonlineb.fqnovel.com",
            "https://api5-normal-sinfonlinec.fqnovel.com"
        )

        /** 播放 UA（kotlin-tv VendorConstants.VIDEO_UA：71332 + Cronet 后缀）。 */
        const val PLAYBACK_UA =
            "com.phoenix.read/71332 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)"

        /** 固定老设备号（kotlin-tv VendorConstants.device 同款，drpys 公开信誉号）。 */
        const val VENDOR_DEVICE_ID = "1905892595378490"
        const val VENDOR_IID = "1905892595382586"
    }

    private fun postVideoModel(
        path: String, raw: ByteArray, identity: HongguoDeviceRegister.Identity,
        transport: ((String, Map<String, String>, ByteArray) -> String)?,
        hostOverride: String? = null
    ): JSONObject {
        val hgtvLane = path.contains("player/v1")
        // 通道 C（HGTV 实测形态）：gzip body + 73932 query + sinfonlinea + 注册身份；
        // 通道 A/B（kotlin-tv/guoapp）：明文 body + 71332 + sinfonlineb + 固定老号。
        val body = if (hgtvLane) gzipDeflate(raw) else raw
        val q = LinkedHashMap<String, String>()
        if (hgtvLane) {
            // 2026-11-10 frida 实抓 HGTV E0 实测 query（保插入序，klink_egdi 最前）：
            // 真注册号 + 如实设备环境（HongguoEnv）+ 73932 档案 + cdid。
            // 旧「内置老号/伪装 Redmi」两形态均已证伪（环境矛盾触发风控）。
            if (identity.klinkEgdi.isNotEmpty()) q["klink_egdi"] = identity.klinkEgdi
            // device_token：HGTV O0/E.java:4397 每个 API 请求随带（注册响应字段，
            // 开源参考线全丢弃——号「次发即拒」的头号嫌疑，2026-11-10 夜补齐）
            if (identity.deviceToken.isNotEmpty()) q["device_token"] = identity.deviceToken
            q["iid"] = identity.installId
            q["device_id"] = identity.deviceId
            q["ac"] = "wifi"; q["channel"] = HongguoEnv.CHANNEL; q["aid"] = "8662"
            q["app_name"] = "novelread"
            q["version_code"] = "73932"; q["version_name"] = "7.3.9.32"
            q["device_platform"] = "android"
            q["os"] = "android"; q["ssmix"] = "a"
            q["device_type"] = HongguoEnv.deviceType
            q["device_brand"] = HongguoEnv.deviceBrand
            q["language"] = "zh"
            q["os_api"] = HongguoEnv.osApi
            q["os_version"] = HongguoEnv.osVersion
            q["manifest_version_code"] = "73932"
            q["resolution"] = HongguoEnv.resolution
            q["dpi"] = HongguoEnv.dpi
            q["update_version_code"] = "73932"; q["host_abi"] = HongguoEnv.HOST_ABI
            q["dragon_device_type"] = "phone"; q["pv_player"] = "73932"
            q["compliance_status"] = "0"; q["need_personal_recommend"] = "1"
            q["player_so_load"] = "1"; q["is_android_pad_screen"] = "1"
            q["rom_version"] = HongguoEnv.romVersion
            q["cdid"] = identity.cdid
            q["ts"] = (System.currentTimeMillis() / 1000).toString()
        } else {
            // kotlin-tv 同款：固定老号 + ts
            q["iid"] = VENDOR_IID
            q["device_id"] = VENDOR_DEVICE_ID
            q["ac"] = "wifi"; q["channel"] = "update_64"; q["aid"] = "8662"
            q["app_name"] = "novelread"; q["version_code"] = "71332"; q["version_name"] = "7.1.3.32"
            q["device_platform"] = "android"; q["os"] = "android"; q["ssmix"] = "a"
            q["device_type"] = "25053RT47C"; q["device_brand"] = "Redmi"; q["language"] = "zh"
            q["os_api"] = "36"; q["os_version"] = "16"; q["manifest_version_code"] = "71332"
            q["resolution"] = "1280*2772"; q["dpi"] = "520"; q["update_version_code"] = "71332"
            q["host_abi"] = "arm64-v8a"; q["dragon_device_type"] = "phone"; q["pv_player"] = "71332"
            q["compliance_status"] = "0"; q["need_personal_recommend"] = "1"; q["player_so_load"] = "1"
            q["is_android_pad_screen"] = "0"
            q["ts"] = (System.currentTimeMillis() / 1000).toString()
        }
        // 通道（2026-11-10 定案）：纯 Kotlin 签名（与 HGTV F0.x1 逐字段对拍+实测等价）
        // + JVM okhttp（HongguoTls）传输。L1 字节码通道已拆除（embed dex +1.9MB 不值得）。

        val versionName = if (hgtvLane) "7.3.9.32" else "7.1.3.32"
        val deviceId = if (hgtvLane) identity.deviceId else VENDOR_DEVICE_ID
        val signed = HongguoCrypto.signRequest(q, body, System.currentTimeMillis(), deviceId, targetBranch = 0, version = versionName)
        val nowSec = signed.headers["X-Khronos"]?.toIntOrNull() ?: (System.currentTimeMillis() / 1000).toInt()

        val host = if (hgtvLane)
            // host 集群（HGTV F0.n3.f2118b 同款，2026-11-10 起轮询）：
            // sinfonlinea=字节直连机房；b/c/lq/hl=金山云 CDN 等备用入口——
            // player/v1 收紧时 HGTV 本尊即靠多 host 轮询续命（1.3.6「播放重试」修复）。
            (HongguoPlayDebug.baseUrlOverride ?: hostOverride
                ?: "https://api5-normal-sinfonlinea.fqnovel.com")
        else
            (HongguoPlayDebug.baseUrlOverride ?: "https://api5-normal-sinfonlineb.fqnovel.com")
        val url = "$host$path?${signed.query}"
        // 真版 Ladon/Argus（tsukiyo 骨架 + HGTV AbstractC0240o 字段表；
        // field23 三元组与 query 环境同源——HGTV 运行时从环境 map 动态取）
        val realLadonArgus = HongguoArgus.generate(
            signed.query, body, nowSec, versionName, deviceId,
            deviceProfile = if (hgtvLane) HongguoEnv.argusProfile else Triple("25053RT47C", "16", "update_64")
        )
        val ticket = signed.headers["X-SS-Req-Ticket"] ?: ""
        val ua = if (hgtvLane) HongguoEnv.playbackUa() else PLAYBACK_UA
        // 头集（2026-11-10 frida 实抓 HGTV E0 实测）：E0 外层头 + C0257s1.d 签名头集
        // （G0 改写后：无 x-tt-request-tag/x-tt-dt、passport=5051452、加 lc/x-ss-dp/
        // x-vc-bdturing/x-tt-trace-id/Content-Encoding/x-reading-request）。
        // UA 与 X-SS-DP 双份是 E0 层先设+M2 再 addHeader 的真实报文形态；
        // 实测无 x-medusa（M1 的 medusa 必带是假环境下的误判）。
        val headers: List<Pair<String, String>> = if (hgtvLane) listOf(
            // E0 外层（b.E0 先设 7 头）
            "User-Agent" to ua,
            "Accept-Language" to "zh-CN,zh;q=0.9",
            "X-SS-DP" to "8662",
            "X-Tt-Store-Region" to "cn",
            "X-Tt-Store-Region-Src" to "did",
            "X-XS-From-Web" to "0",
            "Sdk-Version" to "2",
            // C0257s1.d 签名头集（G0 改写后）
            "User-Agent" to ua,
            "Accept" to "application/json; charset=utf-8,application/x-protobuf",
            "x-ss-req-ticket" to ticket,
            "sdk-version" to "2",
            "passport-sdk-version" to "5051452",
            "x-xs-from-web" to "0",
            "x-khronos" to (signed.headers["X-Khronos"] ?: ""),
            "x-ladon" to realLadonArgus.ladon,
            "x-argus" to realLadonArgus.argus,
            "x-gorgon" to (signed.headers["X-Gorgon"] ?: ""),
            // HGTV 实测 x-helios 是 C0257s1.d 的 Simon34 版（52 字符），
            // 非 Go 版实现——两者同长度不同值，用 HGTV 同款（HongguoArgus.helios）
            "x-helios" to HongguoArgus.helios(nowSec),
            "Content-Type" to "application/json; charset=UTF-8",
            "x-vc-bdturing-sdk-version" to "4.0.3.cn",
            "x-reading-request" to "$ticket-${java.security.SecureRandom().nextLong() and 0xffffffffL}",
            "x-ss-stub" to (signed.headers["X-SS-STUB"] ?: ""),
            // G0 追加
            "lc" to "101",
            "x-ss-dp" to "8662",
            "x-tt-trace-id" to traceId(),
            "Content-Encoding" to "gzip"
        ) else listOf(
            // kotlin-tv Signer.sign（body!=null 分支）
            "User-Agent" to PLAYBACK_UA,
            "Accept" to "application/json; charset=utf-8,application/x-protobuf",
            "x-ss-req-ticket" to ticket,
            "x-tt-request-tag" to "t=0;n=0",
            "sdk-version" to "2",
            "passport-sdk-version" to "50561",
            "x-xs-from-web" to "0",
            "x-khronos" to (signed.headers["X-Khronos"] ?: ""),
            "x-ladon" to realLadonArgus.ladon,
            "x-argus" to realLadonArgus.argus,
            "x-gorgon" to (signed.headers["X-Gorgon"] ?: ""),
            "x-helios" to (signed.headers["X-Helios"] ?: ""),
            "x-medusa" to (signed.headers["X-Medusa"] ?: ""),
            "x-tt-dt" to "",
            "Content-Type" to "application/json; charset=UTF-8",
            "x-vc-bdturing-sdk-version" to "3.7.2.cn",
            "x-ss-stub" to (signed.headers["X-SS-STUB"] ?: "")
        ).let { rows ->
            // 环境 Cookie 仅 std 通道带（HGTV 播放实测无 Cookie 头）
            if (!hgtvLane) {
                val cookieHeader = identity.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
                if (cookieHeader.isNotEmpty()) rows + ("Cookie" to cookieHeader) else rows
            } else rows
        }

        // 排障 dump：完整请求写 sdcard（Node 中继验证签名用）
        if (hgtvLane && (HongguoPlayDebug.dumpRequests || java.io.File("/sdcard/hg_dump").exists())) {
            runCatching {
                val dump = org.json.JSONObject()
                    .put("url", url)
                    .put("body", HongguoCrypto.base64(body))
                val h = org.json.JSONObject()
                headers.forEach { (k, v) -> h.put(k, v) }
                dump.put("headers", h)
                java.io.File("/sdcard/Download/hg_request.json").writeText(dump.toString())
            }
        }

        // Kotlin 签名链的传输层：JVM okhttp（HongguoTls，与 HGTV 字节码传输实测等价）。

        // WebView 通道（Chromium TLS 指纹）：OkHttp 被空响应拒的传输层替代方案
        if (transport != null) {
            val text = transport(url, headers.toMap(), body)
            android.util.Log.d(TAG, "play resp(webview) len=${text?.length ?: -1}")
            if (text.isNullOrEmpty()) throw EmptyBodyException()
            return JSONObject(text)
        }

        val req = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json; charset=UTF-8".toMediaType()))
            .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
            .build()
        http.newCall(req).execute().use { resp ->
            val bytes = resp.body?.bytes() ?: ByteArray(0)
            android.util.Log.d(
                TAG, "play resp http=${resp.code} len=${bytes.size} lane=${if (hgtvLane) "hgtv" else "std"} " +
                    "tls=${resp.handshake?.tlsVersion} cipher=${resp.handshake?.cipherSuite}"
            )
            if (bytes.isEmpty()) throw EmptyBodyException()
            return JSONObject(String(bytes, Charsets.UTF_8))
        }
    }

    /** 标准 gzip（HGTV G0 用 GZIPOutputStream；同一份字节既签名又发送）。 */
    private fun gzipDeflate(data: ByteArray): ByteArray {
        val bos = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(bos).use { it.write(data) }
        return bos.toByteArray()
    }

    /**
     * 纯 Kotlin 签名通道（2026-11-10 去黑盒）：按 F0.x1.d 形态构造签名头集
     * （E0 外层+G0 改写后）并用 JVM okhttp（HongguoTls）发送。
     * 播放接口实测与 L1 字节码签名+传输行为完全等价（三通道同响应）。
     */
    private fun kotlinSignedPost(
        baseUrl: String, q: java.util.LinkedHashMap<String, String>, body: ByteArray,
        versionName: String, deviceId: String,
        deviceProfile: Triple<String, String, String>, ua: String,
        gzipHeader: Boolean
    ): String {
        val signed = HongguoCrypto.signRequest(q, body, System.currentTimeMillis(), deviceId, targetBranch = 0, version = versionName)
        val nowSec = signed.headers["X-Khronos"]?.toIntOrNull() ?: (System.currentTimeMillis() / 1000).toInt()
        val realLadonArgus = HongguoArgus.generate(signed.query, body, nowSec, versionName, deviceId, deviceProfile)
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
            "User-Agent" to ua,
            "Accept" to "application/json; charset=utf-8,application/x-protobuf",
            "x-ss-req-ticket" to ticket,
            "sdk-version" to "2",
            "passport-sdk-version" to "5051452",
            "x-xs-from-web" to "0",
            "x-khronos" to (signed.headers["X-Khronos"] ?: ""),
            "x-ladon" to realLadonArgus.ladon,
            "x-argus" to realLadonArgus.argus,
            "x-gorgon" to (signed.headers["X-Gorgon"] ?: ""),
            "x-helios" to HongguoArgus.helios(nowSec),
            "Content-Type" to "application/json; charset=UTF-8",
            "x-vc-bdturing-sdk-version" to "4.0.3.cn",
            "x-reading-request" to "$ticket-${java.security.SecureRandom().nextLong() and 0xffffffffL}",
            "x-ss-stub" to (signed.headers["X-SS-STUB"] ?: ""),
            // G0 追加
            "lc" to "101",
            "x-ss-dp" to "8662",
            "x-tt-trace-id" to traceId()
        )
        if (gzipHeader) headers.add("Content-Encoding" to "gzip")
        val url = "$baseUrl?${signed.query}"
        val req = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json; charset=UTF-8".toMediaType()))
            .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            android.util.Log.d(
                TAG, "Kotlin-signed resp http=${resp.code} len=${text.length} " +
                    "tls=${resp.handshake?.tlsVersion}"
            )
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            return text
        }
    }

    /** x-tt-trace-id（W3C traceparent 变体，对齐 HGTV W2.b()）。 */
    private fun traceId(): String {
        val hex = java.util.UUID.randomUUID().toString().replace("-", "")
        return "00-$hex-${hex.substring(0, 16)}-01"
    }

    /** 排障钩子：本地报文 dump/代理。 */
    object HongguoPlayDebug {
        var baseUrlOverride: String? = null
        var dumpRequests = false
    }

    // ---- entry 解析：video_model 内联或 fallback_api 二跳（对照 parseEntryPlayInfo） ----
    private fun parseEntry(vid: String, entry: JSONObject, allowBytevc2: Boolean): PlayUrl {
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

        // video_list 双形态：multi/v1=JSONObject(键值)；player/v1(fplay 同构)=JSONArray
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
            val codecRank = when {
                codec in listOf("h264", "avc", "avc1") -> 0
                codec in listOf("hevc", "h265", "hvc1", "hev1", "bytevc1") -> 1
                allowBytevc2 && codec in listOf("bytevc2", "bvc2") -> 2
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
            // 排障 dump：看 fplay/multi 响应里到底给了什么（2026-11-10 player/v1 形态）
            runCatching {
                android.util.Log.w(TAG, "no codec candidates; info.keys=" + info.keys().asSequence().toList() +
                    " video_list=" + info.optJSONObject("video_list")?.toString()?.take(600))
            }
            if (candidates.any { it.codec in listOf("bytevc2", "bvc2") } && !hasCompatible) {
                throw PlayException("本集使用 ByteVC2 编码，当前播放器暂不支持", "UNSUPPORTED_BYTEVC2")
            }
            throw PlayException("本集暂未提供兼容的 H.264/HEVC 片源", "MISSING_MODEL")
        }
        val best = rows.first()
        val spade = best.obj.optString("spade_a").ifEmpty {
            best.obj.optJSONObject("encrypt_info")?.optString("spade_a") ?: ""
        }
        // K-M1（multi/v1）响应的 spade_a 可能是非常规形态（base64 解不出/长度不足），
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
                    decryptSpadeUrl(candidate, Base64.decode(keySeed, Base64.DEFAULT))
                } else {
                    cleanUrl(candidate)
                }
                val checked = checkedUrl(media)
                android.util.Log.d(
                    TAG, "play picked codec=${best.codec} quality=${best.quality} " +
                        "${best.width}x${best.height} encrypted=${contentKey != null} host=${java.net.URI(checked).host}"
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
                    vid = vid
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
        val raw = Base64.decode(value, Base64.DEFAULT)
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
