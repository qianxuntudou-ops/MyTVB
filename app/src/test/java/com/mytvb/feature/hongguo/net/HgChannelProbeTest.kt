package com.mytvb.feature.hongguo.net

import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * 2026-11-23 播放全挂排查探针：五路取源通道逐条真网实弹（对照 aston314/hongguo-tv
 * 2026-11-20 取源序列：video_tab GET → V5 player/v1（主 b/备 a）→ 签名 video_tab GET →
 * 旧 multi → video_model/v1），外加现行 App 通道（preload/v1 + Argus 全头集）复刻。
 * 出口默认 [HongguoHttp.client()]（注册/播放同池，保连接会话模型），另备自建 client
 * 作系统 DNS 对照。真网测试，失败不挂断言只打日志。
 */
class HgChannelProbeTest {

    private val plain = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).build()

    private val appHttp: Call.Factory get() = HongguoHttp.client()

    private val vid = "7701937936731737112"
    private val hostB = "https://api5-normal-sinfonlineb.fqnovel.com"
    private val hostA = "https://api5-normal-sinfonlinea.fqnovel.com"
    private val tabPath = "/reading/bookapi/video_tab/multi_video_model/v1/"

    /** aston miniReelPlaybackUA（video_tab 无签名 GET）。 */
    private val tabUa =
        "com.phoenix.read/71532 (Linux; U; Android 9; SM-N9860; Build/PQ3A.190705.10241111;tt-ok/3.12.13.20)"

    /** aston miniReelMobileV5RecoveryUA（V5 签名 video_tab GET）。 */
    private val recoveryUa = "com.phoenix.read/73932 (Linux; U; Android 16; zh_CN; 25053RT47C; " +
        "Build/BP2A.250605.031.A3; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)"

    private val interesting = setOf(
        "content-type", "content-length", "server", "retry-after", "x-tt-logid", "x-tt-trace-host",
        "x-tt-trace-tag", "x-tt-set-error-code", "x-tt-set-dm-status", "via", "eagleeye-traceid"
    )

    private fun gzip(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(bos).use { it.write(data) }
        return bos.toByteArray()
    }

    private fun traceId(): String {
        val hex = java.util.UUID.randomUUID().toString().replace("-", "")
        return "00-$hex-${hex.substring(0, 16)}-01"
    }

    /** Go url.Values.Encode 等价：键字典序 + QueryEscape（空格→+，其余 %XX 大写）。 */
    private fun goEncode(params: Map<String, String>): String =
        params.toSortedMap().entries.joinToString("&") { (k, v) ->
            fun esc(s: String) = buildString {
                for (ch in s.toByteArray(Charsets.UTF_8)) {
                    val c = ch.toInt() and 0xFF
                    when {
                        c in 0x41..0x5A || c in 0x61..0x7A || c in 0x30..0x39 -> append(c.toChar())
                        c == '-'.code || c == '_'.code || c == '.'.code || c == '~'.code -> append(c.toChar())
                        c == ' '.code -> append('+')
                        else -> append('%').append(String.format("%02X", c))
                    }
                }
            }
            "${esc(k)}=${esc(v)}"
        }

    /** 注册 Cookie 白名单（aston miniReelPlaybackCookieHeader）。 */
    private fun tabCookie(id: HongguoDeviceRegister.Identity): String =
        id.cookies.filterKeys { it in setOf("install_id", "ttreq", "store-region", "store-region-src") }
            .filterValues { it.isNotBlank() }.toSortedMap()
            .entries.joinToString("; ") { "${it.key}=${it.value}" }

    private fun send(
        tag: String, method: String, url: String, headers: List<Pair<String, String>>,
        body: ByteArray? = null, client: Call.Factory = appHttp
    ): String {
        val req = Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> header(k, v) }
            if (method == "POST") {
                post((body ?: ByteArray(0)).toRequestBody("application/json; charset=utf-8".toMediaType()))
            }
        }.build()
        val out = try {
            client.newCall(req).execute().use { resp ->
                val bytes = resp.body?.bytes() ?: ByteArray(0)
                val text = String(bytes, Charsets.UTF_8)
                val head = if (bytes.isEmpty()) "" else text.take(260)
                val hs = resp.headers.names().filter { it.lowercase() in interesting }
                    .joinToString(" ") { n -> "$n=${resp.header(n)}" }
                "http=${resp.code} len=${bytes.size} proto=${resp.protocol} tls=${resp.handshake?.tlsVersion} " +
                    "[$hs] $head"
            }
        } catch (e: Exception) {
            "EXC ${e.javaClass.simpleName}: ${e.message}"
        }
        println("HG1123[$tag] $out")
        return out
    }

    // ---- 现行 App 通道复刻（HongguoPlay.fetchPreloadData + kotlinSignedPost） ----

    private fun appPreloadQuery(id: HongguoDeviceRegister.Identity, code: String, name: String) =
        java.util.LinkedHashMap<String, String>().apply {
            this["device_id"] = id.deviceId
            this["iid"] = id.installId
            if (id.deviceToken.isNotEmpty()) this["device_token"] = id.deviceToken
            this["ac"] = "wifi"; this["channel"] = "update_64"; this["aid"] = "8662"
            this["app_name"] = "novelread"
            this["version_code"] = code; this["version_name"] = name
            this["device_platform"] = "android"; this["os"] = "android"; this["ssmix"] = "a"
            this["device_type"] = "25053RT47C"
            this["device_brand"] = "Redmi"; this["language"] = "zh"
            this["os_api"] = "36"; this["os_version"] = "16"
            this["manifest_version_code"] = code
            this["resolution"] = "1280*2772"; this["dpi"] = "520"
            this["update_version_code"] = code; this["host_abi"] = "arm64-v8a"
            this["dragon_device_type"] = "phone"; this["pv_player"] = code
            this["compliance_status"] = "0"; this["need_personal_recommend"] = "1"
            this["player_so_load"] = "1"; this["is_android_pad_screen"] = "0"
        }

    private fun preloadBody(vid: String, level: Int): ByteArray = JSONObject()
        .put(
            "biz_param", JSONObject()
                .put("detail_page_version", 0)
                .put("device_level", level)
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
        .put("mixed_video_id_map", JSONObject().put("1004", JSONArray().put(vid)))
        .toString().toByteArray(Charsets.UTF_8)

    /** Argus/Ladon/Gorgon/Helios/Medusa 全头集（HongguoPlay.kotlinSignedPost 同构）。 */
    private fun argusHeaders(
        q: java.util.LinkedHashMap<String, String>, body: ByteArray, versionName: String,
        deviceId: String, ua: String, gzipped: Boolean
    ): Pair<String, List<Pair<String, String>>> {
        val signed = HongguoCrypto.signRequest(
            q, body, System.currentTimeMillis(), deviceId, targetBranch = 0, version = versionName
        )
        val nowSec = signed.headers["X-Khronos"]?.toIntOrNull() ?: (System.currentTimeMillis() / 1000).toInt()
        fun b64le32(v: Int) = java.util.Base64.getEncoder().encodeToString(
            java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(v).array()
        )
        val ticket = signed.headers["X-SS-Req-Ticket"] ?: ""
        val headers = mutableListOf(
            "User-Agent" to ua,
            "Accept-Language" to "zh-CN,zh;q=0.9",
            "X-SS-DP" to "8662",
            "X-Tt-Store-Region" to "cn",
            "X-Tt-Store-Region-Src" to "did",
            "X-XS-From-Web" to "0",
            "Sdk-Version" to "2",
            "Accept" to "application/json; charset=utf-8,application/x-protobuf",
            "x-ss-req-ticket" to ticket,
            "sdk-version" to "2",
            "passport-sdk-version" to "5051452",
            "x-xs-from-web" to "0",
            "x-khronos" to (signed.headers["X-Khronos"] ?: ""),
            "x-ladon" to java.util.Base64.getEncoder().encodeToString(
                ByteArray(4).also { java.security.SecureRandom().nextBytes(it) }
            ),
            "x-argus" to b64le32(nowSec),
            "x-gorgon" to (signed.headers["X-Gorgon"] ?: ""),
            "x-helios" to HongguoArgus.helios(nowSec),
            "x-medusa" to HongguoPlaybackV5.medusaHeaderFor(signed.query, body, nowSec),
            "x-vc-bdturing-sdk-version" to "4.0.3.cn",
            "x-reading-request" to "$ticket-${java.security.SecureRandom().nextLong() and 0xffffffffL}",
            "x-ss-stub" to (signed.headers["X-SS-STUB"] ?: ""),
            "lc" to "101",
            "x-ss-dp" to "8662",
            "x-tt-trace-id" to traceId()
        )
        if (gzipped) headers.add("Content-Encoding" to "gzip")
        return signed.query to headers
    }

    private fun appUa(code: String) =
        "com.phoenix.read/$code (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3; " +
            "Cronet/TTNetVersion:8d40f833 2026-03-03 QuicVersion:21ac1950 2025-11-18)"

    // ---- aston V5 通道 ----

    private fun v5Query(id: HongguoDeviceRegister.Identity) = listOf(
        "aid" to "8662", "app_name" to "novelread", "version_code" to "74332",
        "version_name" to "7.4.3.32", "channel" to "vivo_8662_64", "device_platform" to "android",
        "device_type" to "PGT-AN10", "os_version" to "12", "update_version_code" to "74332",
        "iid" to id.installId, "cdid" to id.cdid, "device_id" to id.deviceId
    )

    /** Go map 序列化键序（字典序）：biz_param / dr_scene / mixed_video_id_map。 */
    private fun v5Body(vid: String) = JSONObject()
        .put(
            "biz_param", JSONObject()
                .put("detail_page_version", 0)
                .put("device_level", 1)
                .put("disable_digg_stat", false)
                .put("disable_video_relate_book", false)
                .put("need_all_video_definition", true)
                .put("need_mp4_align", false)
                .put("use_os_player", false)
                .put("use_server_dns", false)
                .put("video_platform", 1024)
        )
        .put("dr_scene", "player")
        .put("mixed_video_id_map", JSONObject().put("1004", JSONArray().put(vid)))
        .toString().toByteArray(Charsets.UTF_8)

    private fun v5Post(tag: String, url: String, id: HongguoDeviceRegister.Identity, body: ByteArray) {
        val gz = gzip(body)
        val signed = HongguoPlaybackV5.sign(v5Query(id), System.currentTimeMillis(), gz)
        send(
            tag, "POST", "$url?${signed.query}",
            signed.headers + listOf(
                "User-Agent" to HongguoPlaybackV5.PLAYBACK_UA,
                "Content-Encoding" to "gzip",
                "Accept" to "application/json"
            ),
            gz
        )
    }

    /** aston V5 签名 video_tab GET 请求件（recoverVideoTab：无 body/无 SS-STUB，22 键定序）。 */
    private fun v5TabRequest(id: HongguoDeviceRegister.Identity): Pair<String, List<Pair<String, String>>> {
        val pairs = v5Query(id) + listOf(
            "video_id" to vid, "video_id_type" to "0", "device_level" to "1",
            "need_all_video_definition" to "1", "need_mp4_align" to "0", "use_os_player" to "0",
            "video_platform" to "1024", "source" to "0", "from_video_id" to ""
        )
        val signed = HongguoPlaybackV5.sign(pairs, System.currentTimeMillis(), null)
        return "$hostB$tabPath?${signed.query}" to (signed.headers + listOf(
            "User-Agent" to recoveryUa,
            "Accept" to "application/json; charset=utf-8",
            "Accept-Language" to "zh-CN,zh;q=0.9"
        ))
    }

    private fun v5TabGet(tag: String, id: HongguoDeviceRegister.Identity) {
        val (url, headers) = v5TabRequest(id)
        send(tag, "GET", url, headers)
    }

    /** aston video_tab 无签名 GET（71332 + 注册 Cookie 白名单）。 */
    private fun tabGet(tag: String, id: HongguoDeviceRegister.Identity, client: Call.Factory = appHttp) {
        val params = mapOf(
            "_rticket" to System.currentTimeMillis().toString(),
            "ac" to "wifi", "aid" to "8662", "app_name" to "novelread", "channel" to "update_64",
            "cdid" to id.cdid, "device_brand" to "Redmi", "device_id" to id.deviceId,
            "device_platform" to "android", "device_type" to "25053RT47C", "dpi" to "520",
            "dragon_device_type" to "phone", "host_abi" to "arm64-v8a", "iid" to id.installId,
            "is_android_pad_screen" to "0", "language" to "zh", "manifest_version_code" to "71332",
            "need_personal_recommend" to "1", "os" to "android", "os_api" to "36", "os_version" to "16",
            "resolution" to "1280*2772", "player_so_load" to "1", "pv_player" to "71332", "ssmix" to "a",
            "update_version_code" to "71332", "version_code" to "71332", "version_name" to "7.1.3.32",
            "compliance_status" to "0", "video_id" to vid, "video_id_type" to "0",
            "device_level" to "1", "need_all_video_definition" to "1", "need_mp4_align" to "0",
            "use_os_player" to "0", "video_platform" to "1024", "source" to "0", "from_video_id" to ""
        )
        send(
            tag, "GET", "$hostB$tabPath?${goEncode(params)}",
            listOf(
                "User-Agent" to tabUa,
                "Accept" to "application/json; charset=utf-8",
                "Cookie" to tabCookie(id)
            ),
            client = client
        )
    }

    private fun pause() = Thread.sleep(1500)

    private fun register(tag: String): HongguoDeviceRegister.Identity? = try {
        HongguoDeviceRegister.registerFresh().also {
            println("HG1123[$tag] registered device=${it.deviceId} iid=${it.installId} cdid=${it.cdid} cookies=${it.cookies.keys} token=${it.deviceToken.length}ch klink=${it.klinkEgdi.length}ch")
        }
    } catch (e: Exception) {
        println("HG1123[$tag] register FAIL ${e.javaClass.simpleName}: ${e.message}")
        null
    }

    /** 全通道：同一注册身份顺序实弹（每枪间隔 1.5s）。 */
    @Test
    fun probeAllChannels() {
        val id = register("reg") ?: return
        pause()

        // T0：现行 App 通道（preload/v1 + Argus 全头集 + gzip，74532/Redmi/A16）
        for (level in listOf(4, 5)) {
            val body = gzip(preloadBody(vid, level))
            val (query, headers) = argusHeaders(
                appPreloadQuery(id, "74532", "7.4.5.32"), body, "7.4.5.32", id.deviceId, appUa("74532"), true
            )
            send("T0-app-preload-L$level", "POST", "$hostB/novel/player/multi_video_model/preload/v1?$query", headers, body)
            pause()
        }

        // T1：aston video_tab 无签名 GET（App 出口）
        tabGet("T1-tab-plain", id)
        pause()
        // T1b：同枪换自建 client（系统 DNS，非 TNC 直连）
        tabGet("T1b-tab-plain-sysdns", id, plain)
        pause()

        // T2/T3：aston V5 player/v1 主域 b → 备域 a
        v5Post("T2-v5-player-b", "$hostB/novel/player/multi_video_model/player/v1", id, v5Body(vid))
        pause()
        v5Post("T3-v5-player-a", "$hostA/novel/player/multi_video_model/player/v1", id, v5Body(vid))
        pause()

        // T4：aston V5 签名 video_tab GET
        v5TabGet("T4-v5-tab-signed", id)
        pause()

        // T5：V5 video_model/v1 直取（11-20 白天通过形态）
        val direct = JSONObject()
            .put("video_id", vid).put("content_type", 1004)
            .put(
                "biz_param", JSONObject()
                    .put("detail_page_version", 0).put("device_level", 4)
                    .put("disable_digg_stat", false).put("disable_video_relate_book", false)
                    .put("from_video_id", "").put("need_all_video_definition", true)
                    .put("need_mp4_align", false).put("use_os_player", false)
                    .put("use_server_dns", true).put("use_server_dns_scene", "cold_start_stage")
                    .put("source", 4).put("video_platform", 3)
            ).toString().toByteArray(Charsets.UTF_8)
        v5Post("T5-v5-direct", "$hostB/novel/player/video_model/v1/", id, direct)
        pause()

        // T6：V5 preload/v1（纯 V5 头集打现行端点）
        v5Post("T6-v5-preload", "$hostB/novel/player/multi_video_model/preload/v1", id, v5Body(vid))
        pause()

        // T7：旧 multi_video_model/v1/（Argus 全头集，未压缩，device_level=3）
        val oldBody = JSONObject()
            .put(
                "biz_param", JSONObject()
                    .put("detail_page_version", 0).put("device_level", 3)
                    .put("disable_digg_stat", false).put("need_all_video_definition", true)
                    .put("need_mp4_align", false).put("use_os_player", false)
                    .put("use_server_dns", false).put("video_platform", 1024)
            )
            .put("mixed_video_id_map", JSONObject().put("1004", JSONArray().put(vid)))
            .toString().toByteArray(Charsets.UTF_8)
        val (oldQuery, oldHeaders) = argusHeaders(
            appPreloadQuery(id, "74532", "7.4.5.32"), oldBody, "7.4.5.32", id.deviceId, appUa("74532"), false
        )
        send("T7-multi-v1", "POST", "$hostB/novel/player/multi_video_model/v1/?$oldQuery", oldHeaders, oldBody)
    }

    /** 独立复测：每枪全新注册身份（排除"号一次性"变量）。 */
    @Test
    fun probeFreshIdentityPerShot() {
        val shots: List<Pair<String, (HongguoDeviceRegister.Identity) -> Unit>> = listOf(
            "F0-app-preload" to { id ->
                val body = gzip(preloadBody(vid, 4))
                val (query, headers) = argusHeaders(
                    appPreloadQuery(id, "74532", "7.4.5.32"), body, "7.4.5.32", id.deviceId, appUa("74532"), true
                )
                send("F0-app-preload", "POST", "$hostB/novel/player/multi_video_model/preload/v1?$query", headers, body)
            },
            "F1-tab-plain" to { id -> tabGet("F1-tab-plain", id) },
            "F2-v5-player-b" to { id -> v5Post("F2-v5-player-b", "$hostB/novel/player/multi_video_model/player/v1", id, v5Body(vid)) },
            "F3-v5-tab-signed" to { id -> v5TabGet("F3-v5-tab-signed", id) }
        )
        for ((tag, shot) in shots) {
            val id = register(tag) ?: continue
            pause()
            shot(id)
            pause()
        }
    }

    /** 精简发送：只回 http 码/字节数（长周期观测用）。 */
    private fun sendLite(url: String, headers: List<Pair<String, String>>, body: ByteArray?): String = try {
        appHttp.newCall(
            Request.Builder().url(url).apply {
                headers.forEach { (k, v) -> header(k, v) }
                if (body != null) post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            }.build()
        ).execute().use { "${it.code}/${it.body?.bytes()?.size ?: -1}" }
    } catch (e: Exception) {
        "EXC ${e.javaClass.simpleName}"
    }

    /**
     * 长周期观测：同一持久身份每 90s 一轮 × 20 轮（约 30 分钟），每轮两枪——
     * P=现行 App 通道（preload/v1）、G=V5 签名 video_tab GET；判定放行随时间/频次的变化。
     * 结果逐枪追加落盘 longwatch.txt（防 gradle 输出截断）。
     */
    @Test
    fun probeLongWatch() {
        val log = java.io.File(
            java.io.File(System.getProperty("user.dir")).parentFile, ".trae/hg-log-1123/longwatch.txt"
        )
        val clock = java.text.SimpleDateFormat("HH:mm:ss")
        val id = register("watch") ?: return
        Thread.sleep(1500)
        for (round in 1..20) {
            val body = gzip(preloadBody(vid, 4))
            val (query, headers) = argusHeaders(
                appPreloadQuery(id, "74532", "7.4.5.32"), body, "7.4.5.32", id.deviceId, appUa("74532"), true
            )
            val p = sendLite("$hostB/novel/player/multi_video_model/preload/v1?$query", headers, body)
            Thread.sleep(2500)
            val (tabUrl, tabHeaders) = v5TabRequest(id)
            val g = sendLite(tabUrl, tabHeaders, null)
            val line = "round=$round t=${clock.format(java.util.Date())} preload=$p v5tab=$g"
            println("HG1123[watch] $line")
            log.appendText(line + "\n")
            if (round < 20) Thread.sleep(90_000)
        }
    }
}
