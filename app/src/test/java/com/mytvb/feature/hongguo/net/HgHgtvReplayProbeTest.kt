package com.mytvb.feature.hongguo.net

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * 通道 21（2026-11-23）：HGTV 1.3.8.13 wire 请求原样重放 + 二分。
 * frida hook H0.m0.p 抓到的 K3（url/headers 保序/body），由 .trae/hg-log-1123/hgtv-frida/
 * run_capture.py 落盘为 hgtv_play_reqs.json。MuMu 实抓：同一 sinfonlineb 上 POST
 * video_model/v1/ 与 preload/v1 均放行（26KB / 314KB），同刻我方自构 preload 回空 body。
 * 本探针在同一 JVM/OkHttp 出口重放，定位放行差异落在哪个字段。
 *
 * 枪间隔 ≥25s（短时密集请求触发服务端 200 空 body 限流）。签名有时效，抓完尽快跑。
 */
class HgHgtvReplayProbeTest {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private class Shot(
        val url: String, val headers: List<Pair<String, String>>, val body: ByteArray,
        val method: String = "POST"
    )

    private fun load(): List<Shot> {
        val f = File(dir, "hgtv_play_reqs.json")
        val arr = JSONArray(f.readText())
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val hs = o.getJSONArray("headers")
            Shot(
                o.getString("url"),
                (0 until hs.length()).map { j -> hs.getJSONArray(j).getString(0) to hs.getJSONArray(j).getString(1) },
                Base64.getDecoder().decode(o.getString("body_b64"))
            )
        }
    }

    private fun fire(tag: String, s: Shot): String {
        val hb = okhttp3.Headers.Builder()
        s.headers.forEach { (k, v) -> hb.add(k, v) }
        val builder = Request.Builder().url(s.url).headers(hb.build())
        val req = (if (s.method == "GET") builder.get() else builder.post(s.body.toRequestBody(jsonType))).build()
        val out = try {
            http.newCall(req).execute().use { r ->
                val b = r.body?.bytes() ?: ByteArray(0)
                val code = Regex("\"\"code\"\"\\s*:\\s*(-?\\d+)").find(b.toString(Charsets.UTF_8))?.groupValues?.get(1)
                "http=${r.code} len=${b.size}" + (code?.let { " biz=$it" } ?: "")
            }
        } catch (e: Exception) { "EXC ${e.message}" }
        println("HG-REPLAY[$tag] $out")
        return out
    }

    /** 原样重放 preload/v1：判 HGTV 请求从本机 JVM 出口是否同样放行。 */
    @Test
    fun probeExactReplay() {
        val shots = load()
        val preload = shots.firstOrNull { it.url.contains("preload/v1") } ?: run { println("HG-REPLAY no preload"); return }
        val model = shots.firstOrNull { it.url.contains("video_model/v1") }
        println("HG-REPLAY loaded=${shots.size} preload hdrs=${preload.headers.size} body=${preload.body.size}")
        fire("R0-preload-exact", preload)
        if (model != null) {
            Thread.sleep(25_000)
            fire("R1-model-exact", model)
        }
    }

    /**
     * 二分第二批：以 HGTV 原样请求（已知放行）为基底，khronos/_rticket/query 串/body 全部保持原值，
     * 用我方 V5 签名器按相同输入重算，每枪只换一个签名头——定位被拒的是 gorgon / medusa / helios 哪一个。
     * gorgon 无随机成分，实现等价则应与 HGTV 原值逐字节相同。
     */
    @Test
    fun probeBisect2() {
        val base = load().first { it.url.contains("preload/v1") }
        val origQuery = base.url.substringAfter("?")
        val q = parseQuery(base.url)
        val rt = q["_rticket"]?.toLong() ?: 0L
        val signed = HongguoPlaybackV5.sign(q.entries.map { it.key to it.value }, rt, base.body)
        fun ours(name: String) = signed.headers.firstOrNull { it.first.equals(name, true) }?.second
        val hgtv = base.headers.associate { it.first.lowercase() to it.second }
        println("HG-REPLAY query identical=${signed.query == origQuery}")
        if (signed.query != origQuery) {
            println("HG-REPLAY   ours=$signed.query")
            println("HG-REPLAY   hgtv=$origQuery")
        }
        for (name in listOf("x-khronos", "x-gorgon", "x-helios", "x-medusa", "x-argus", "x-ladon")) {
            val o = ours(name)
            val h = hgtv[name]
            println("HG-REPLAY $name ours=${o?.take(64)}(${o?.length}) hgtv=${h?.take(64)}(${h?.length}) same=${o == h}")
        }
        fun swap(vararg names: String): Shot = Shot(
            base.url,
            base.headers.map { (k, v) -> ours(k)?.let { k to it } ?: (k to v) },
            base.body
        )
        fun swapOnly(vararg names: String): Shot = Shot(
            base.url,
            base.headers.map { (k, v) -> if (k.lowercase() in names) (ours(k)?.let { k to it } ?: (k to v)) else (k to v) },
            base.body
        )
        fire("H0-ours-all-headers", swap())
        Thread.sleep(25_000)
        fire("H1-swap-gorgon-only", swapOnly("x-gorgon"))
        Thread.sleep(25_000)
        fire("H2-swap-medusa-only", swapOnly("x-medusa"))
        Thread.sleep(25_000)
        fire("H3-swap-helios-only", swapOnly("x-helios"))
    }

    /**
     * 二分第三批：
     * H2' = HGTV 基底只换 x-medusa，且我方 medusa 按**原始 query 串**算（上一批按重编码 query 算，实验无效）；
     * V1 = 我方现行代码（新注册身份）此刻打 GET video_tab；
     * V2 = 我方现行代码 + HGTV 设备身份/Cookie 打 GET video_tab（判设备维度）。
     */
    @Test
    fun probeBisect3() {
        val base = load().first { it.url.contains("preload/v1") }
        val origQuery = base.url.substringAfter("?")
        val q = parseQuery(base.url)
        val ts = (q["_rticket"]?.toLong() ?: 0L).div(1000).toInt()
        val vid = JSONObject(java.util.zip.GZIPInputStream(base.body.inputStream()).readBytes().toString(Charsets.UTF_8))
            .getJSONObject("mixed_video_id_map").getJSONArray("1004").getString(0)
        val cookie = base.headers.firstOrNull { it.first.equals("Cookie", true) }?.second ?: ""

        val ourMedusa = HongguoPlaybackV5.medusaHeaderFor(origQuery, base.body, ts)
        fire(
            "H2p-medusa-origquery", Shot(
                base.url,
                base.headers.map { (k, v) -> if (k.lowercase() == "x-medusa") k to ourMedusa else k to v },
                base.body
            )
        )

        Thread.sleep(25_000)
        HongguoPlay.resetCooldownForTest()
        val fresh = HongguoDeviceRegister.registerFresh()
        println("HG-REPLAY V1 fresh device=${fresh.deviceId} iid=${fresh.installId}")
        val out1 = runCatching { "OK url=" + HongguoPlay().playUrl(vid, fresh).toString().take(80) }
            .fold({ it }, { "FAIL ${it.javaClass.simpleName}: ${it.message}" })
        println("HG-REPLAY[V1-tab-fresh] $out1")

        Thread.sleep(25_000)
        HongguoPlay.resetCooldownForTest()
        val hgtvId = HongguoDeviceRegister.Identity(
            deviceId = q["device_id"] ?: "", installId = q["iid"] ?: "", cdid = "", openUdid = "",
            cookies = cookie.split(";").filter { it.contains('=') }.associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() },
            klinkEgdi = q["klink_egdi"] ?: ""
        )
        val out2 = runCatching { "OK url=" + HongguoPlay().playUrl(vid, hgtvId).toString().take(80) }
            .fold({ it }, { "FAIL ${it.javaClass.simpleName}: ${it.message}" })
        println("HG-REPLAY[V2-tab-hgtv-identity] $out2")
    }

    // Gradle 测试进程的 user.dir 是模块目录 app/，回溯一级即仓库根（跨机器免配置）
    private val dir = File(File(System.getProperty("user.dir")!!).parentFile, ".trae/hg-log-1123/hgtv-frida")

    private val hostB = "https://api5-normal-sinfonlineb.fqnovel.com"

    /** video_tab 22 键（HongguoPlay.fetchTabEntry 同构），按传入身份填充。 */
    private fun tabQuery(id: HongguoDeviceRegister.Identity): JSONObject = JSONObject().apply {
        put("aid", "8662"); put("app_name", "novelread")
        put("version_code", "74532"); put("version_name", "7.4.5.32")
        put("channel", "vivo_8662_64"); put("device_platform", "android"); put("device_type", "PGT-AN10")
        put("os_version", "12"); put("update_version_code", "74532")
        put("iid", id.installId); put("cdid", id.cdid); put("device_id", id.deviceId)
    }

    /**
     * 第一步：生成待签名请求 sign_requests.json（M1 新身份 preload 我方形态 / M2 新身份 video_tab /
     * M3 HGTV 老身份 video_tab），由 rpc_sign.py 借 HGTV 签名器签出金标准。
     */
    @Test
    fun prepareSign() {
        val vid = "7709197486916652056"
        val base = load().first { it.url.contains("preload/v1") }
        val hq = parseQuery(base.url)
        val cookie = base.headers.firstOrNull { it.first.equals("Cookie", true) }?.second ?: ""
        val fresh = HongguoDeviceRegister.registerFresh()
        val q1 = java.util.LinkedHashMap<String, String>().apply {
            this["device_id"] = fresh.deviceId; this["iid"] = fresh.installId
            if (fresh.deviceToken.isNotEmpty()) this["device_token"] = fresh.deviceToken
            this["ac"] = "wifi"; this["channel"] = "update_64"; this["aid"] = "8662"; this["app_name"] = "novelread"
            this["version_code"] = "74532"; this["version_name"] = "7.4.5.32"
            this["device_platform"] = "android"; this["os"] = "android"; this["ssmix"] = "a"
            this["device_type"] = "25053RT47C"; this["device_brand"] = "Redmi"; this["language"] = "zh"
            this["os_api"] = "36"; this["os_version"] = "16"; this["manifest_version_code"] = "74532"
            this["resolution"] = "1280*2772"; this["dpi"] = "520"
            this["update_version_code"] = "74532"; this["host_abi"] = "arm64-v8a"
            this["dragon_device_type"] = "phone"; this["pv_player"] = "74532"
            this["compliance_status"] = "0"; this["need_personal_recommend"] = "1"
            this["player_so_load"] = "1"; this["is_android_pad_screen"] = "0"
        }
        val body = JSONObject()
            .put("biz_param", JSONObject().put("detail_page_version", 0).put("device_level", 4)
                .put("disable_digg_stat", false).put("disable_video_relate_book", false)
                .put("need_all_video_definition", true).put("need_mp4_align", false)
                .put("use_os_player", false).put("use_server_dns", true)
                .put("use_server_dns_scene", "cold_start_stage").put("video_platform", 1024))
            .put("dr_scene", "preload")
            .put("mixed_video_id_map", JSONObject().put("1004", JSONArray().put(vid)))
            .toString().toByteArray(Charsets.UTF_8)
        fun entry(name: String, url: String, query: JSONObject, bodyB64: String?, method: String, ck: String, did: String, live: Boolean = false) =
            JSONObject().put("name", name).put("base", url).put("query", query).put("body_b64", bodyB64 ?: JSONObject.NULL)
                .put("method", method).put("cookie", ck).put("device_id", did ?: "").put("use_live", live)
        fun freshCookie() = fresh.cookies.entries.sortedBy { it.key }.joinToString("; ") { "${it.key}=${it.value}" }
        val tabBase = "$hostB/reading/bookapi/video_tab/multi_video_model/v1"
        val t2 = tabQuery(fresh).put("video_id", vid).put("video_id_type", "0").put("device_level", "1")
            .put("need_all_video_definition", "1").put("need_mp4_align", "0").put("use_os_player", "0")
            .put("video_platform", "1024").put("source", "0").put("from_video_id", "")
        val t3 = tabQuery(HongguoDeviceRegister.Identity("", hq["iid"] ?: "", "", ""))
            .put("device_id", hq["device_id"] ?: "").put("video_id", vid).put("video_id_type", "0").put("device_level", "1")
            .put("need_all_video_definition", "1").put("need_mp4_align", "0").put("use_os_player", "0")
            .put("video_platform", "1024").put("source", "0").put("from_video_id", "")
        // M4/M5：HGTV 原内容（原 query 键序 + 原 body 明文），分别用新建实例 / 进程内真实实例签名
        val hqObj = JSONObject()
        for ((k, v) in hq) hqObj.put(k, v)
        val hgtvBodyRaw = java.util.zip.GZIPInputStream(base.body.inputStream()).readBytes()
        val hgtvBodyB64 = Base64.getEncoder().encodeToString(hgtvBodyRaw)
        val preloadBase = base.url.substringBefore("?")
        val arr = JSONArray()
            .put(entry("M4-hgtv-content-newinst", preloadBase, hqObj, hgtvBodyB64, "POST", cookie, hq["device_id"] ?: ""))
            .put(entry("M5-hgtv-content-liveinst", preloadBase, hqObj, hgtvBodyB64, "POST", cookie, hq["device_id"] ?: "", live = true))
            .put(entry("M6-preload-fresh-liveinst", "$hostB/novel/player/multi_video_model/preload/v1", JSONObject(q1 as Map<*, *>),
                Base64.getEncoder().encodeToString(body), "POST", freshCookie(), fresh.deviceId, live = true))
            .put(entry("M1-preload-fresh-liveinst", "$hostB/novel/player/multi_video_model/preload/v1", JSONObject(q1 as Map<*, *>),
                Base64.getEncoder().encodeToString(body), "POST", freshCookie(), fresh.deviceId, live = true))
            .put(entry("M2-tab-fresh-liveinst", tabBase, t2, null, "GET", freshCookie(), fresh.deviceId, live = true))
            .put(entry("M3-tab-hgtv-liveinst", tabBase, t3, null, "GET", cookie, hq["device_id"] ?: "", live = true))
        File(dir, "sign_requests.json").writeText(arr.toString(2))
        println("HG-REPLAY prepared ${arr.length()} sign requests, fresh device=${fresh.deviceId}")
    }

    /** 第二步：重放 HGTV 签名器签出的金标准请求。 */
    @Test
    fun fireSigned() {
        val arr = JSONArray(File(dir, "sign_results.json").readText())
        var first = true
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (!first) Thread.sleep(25_000)
            first = false
            if (o.has("err")) { println("HG-REPLAY[${o.getString("name")}] SIGN-ERR ${o.getString("err").take(200)}"); continue }
            val hs = mutableListOf<Pair<String, String>>()
            val h = o.getJSONArray("headers")
            for (j in 0 until h.length()) hs.add(h.getJSONArray(j).getString(0) to h.getJSONArray(j).getString(1))
            if (!o.isNull("cookie") && o.getString("cookie").isNotBlank()) hs.add("Cookie" to o.getString("cookie"))
            val body = if (o.isNull("body_b64")) ByteArray(0) else Base64.getDecoder().decode(o.getString("body_b64"))
            fire(o.getString("name"), Shot(o.getString("url"), hs, body, o.getString("method")))
        }
    }

    private fun parseQuery(url: String): java.util.LinkedHashMap<String, String> {
        val q = java.util.LinkedHashMap<String, String>()
        url.substringAfter("?").split("&").forEach {
            val i = it.indexOf('=')
            if (i > 0) q[it.take(i)] = java.net.URLDecoder.decode(it.substring(i + 1), "UTF-8")
        }
        return q
    }

    private val signHeaders = setOf(
        "x-khronos", "x-ladon", "x-argus", "x-gorgon", "x-helios", "x-medusa",
        "x-ss-req-ticket", "x-ss-stub", "x-reading-request"
    )

    /**
     * 以 HGTV 请求为基底：query/body/非签名头全保留，签名头全部换成我方签名器生成
     * （HongguoCrypto.signRequest + helios/medusa/argus/ladon，7.4.3.32）。[mutate] 做单变量替换。
     */
    private fun resignShot(
        base: Shot, deviceId: String,
        mutate: (java.util.LinkedHashMap<String, String>, MutableList<Pair<String, String>>, ByteArray) -> ByteArray =
            { _, _, body -> body }
    ): Shot {
        val q = parseQuery(base.url)
        val hs = base.headers.toMutableList()
        val body = mutate(q, hs, base.body)
        val signed = HongguoCrypto.signRequest(q, body, System.currentTimeMillis(), deviceId, targetBranch = 0, version = "7.4.3.32")
        val sec = signed.headers["X-Khronos"]?.toIntOrNull() ?: (System.currentTimeMillis() / 1000).toInt()
        val ticket = signed.headers["X-SS-Req-Ticket"] ?: ""
        val replaced = hs.map { (k, v) ->
            when (k.lowercase()) {
                "x-khronos" -> k to sec.toString()
                "x-ladon" -> k to java.util.Base64.getEncoder().encodeToString(ByteArray(4).also { java.security.SecureRandom().nextBytes(it) })
                "x-argus" -> k to java.util.Base64.getEncoder().encodeToString(
                    java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(sec).array()
                )
                "x-gorgon" -> k to (signed.headers["X-Gorgon"] ?: "")
                "x-helios" -> k to HongguoArgus.helios(sec)
                "x-medusa" -> k to HongguoPlaybackV5.medusaHeaderFor(signed.query, body, sec)
                "x-ss-req-ticket" -> k to ticket
                "x-ss-stub" -> k to (signed.headers["X-SS-STUB"] ?: "")
                "x-reading-request" -> k to "$ticket-${java.security.SecureRandom().nextLong() and 0xffffffffL}"
                else -> k to v
            }
        }.toMutableList()
        // 基底里没有的签名头补在末尾，保证我方头集完整
        val have = replaced.map { it.first.lowercase() }.toSet()
        if ("x-ss-stub" !in have) replaced.add("x-ss-stub" to (signed.headers["X-SS-STUB"] ?: ""))
        return Shot(base.url.substringBefore("?") + "?" + signed.query, replaced, body)
    }

    private fun gzip(data: ByteArray): ByteArray {
        val bos = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(bos).use { it.write(data) }
        return bos.toByteArray()
    }

    /** 我方现行 preload body（HongguoPlay.fetchPreloadData 同构，单集）。 */
    private fun appPreloadBody(vid: String, level: Int): ByteArray = JSONObject()
        .put(
            "biz_param", JSONObject()
                .put("detail_page_version", 0).put("device_level", level)
                .put("disable_digg_stat", false).put("disable_video_relate_book", false)
                .put("need_all_video_definition", true).put("need_mp4_align", false)
                .put("use_os_player", false).put("use_server_dns", true)
                .put("use_server_dns_scene", "cold_start_stage").put("video_platform", 1024)
        )
        .put("dr_scene", "preload")
        .put("mixed_video_id_map", JSONObject().put("1004", JSONArray().put(vid)))
        .toString().toByteArray(Charsets.UTF_8)

    /**
     * 二分第一批：G1 = HGTV 内容 + 我方重签（判签名实现等价）；
     * T0 = 我方现行 preload 形态 + 新注册身份（判此刻是否时间维度放行）。
     */
    @Test
    fun probeBisect1() {
        val base = load().first { it.url.contains("preload/v1") }
        val hgtvDid = parseQuery(base.url)["device_id"] ?: ""
        val firstVid = JSONObject(java.util.zip.GZIPInputStream(base.body.inputStream()).readBytes().toString(Charsets.UTF_8))
            .getJSONObject("mixed_video_id_map").getJSONArray("1004").getString(0)

        fire("G1-hgtv-content-our-sign", resignShot(base, hgtvDid))

        Thread.sleep(25_000)
        val id = HongguoDeviceRegister.registerFresh()
        println("HG-REPLAY registered device=${id.deviceId} iid=${id.installId} cookies=${id.cookies.keys}")
        // T0：我方现行形态（单集 body、74532、我方 UA/头集）
        val body = gzip(appPreloadBody(firstVid, 4))
        val q = java.util.LinkedHashMap<String, String>().apply {
            this["device_id"] = id.deviceId; this["iid"] = id.installId
            if (id.deviceToken.isNotEmpty()) this["device_token"] = id.deviceToken
            this["ac"] = "wifi"; this["channel"] = "update_64"; this["aid"] = "8662"; this["app_name"] = "novelread"
            this["version_code"] = "74532"; this["version_name"] = "7.4.5.32"
            this["device_platform"] = "android"; this["os"] = "android"; this["ssmix"] = "a"
            this["device_type"] = "25053RT47C"; this["device_brand"] = "Redmi"; this["language"] = "zh"
            this["os_api"] = "36"; this["os_version"] = "16"; this["manifest_version_code"] = "74532"
            this["resolution"] = "1280*2772"; this["dpi"] = "520"
            this["update_version_code"] = "74532"; this["host_abi"] = "arm64-v8a"
            this["dragon_device_type"] = "phone"; this["pv_player"] = "74532"
            this["compliance_status"] = "0"; this["need_personal_recommend"] = "1"
            this["player_so_load"] = "1"; this["is_android_pad_screen"] = "0"
        }
        val signed = HongguoCrypto.signRequest(q, body, System.currentTimeMillis(), id.deviceId, targetBranch = 0, version = "7.4.5.32")
        val sec = signed.headers["X-Khronos"]?.toIntOrNull() ?: (System.currentTimeMillis() / 1000).toInt()
        val ticket = signed.headers["X-SS-Req-Ticket"] ?: ""
        val hex = java.util.UUID.randomUUID().toString().replace("-", "")
        val hs = mutableListOf(
            "User-Agent" to "com.phoenix.read/74532 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3; " +
                "Cronet/TTNetVersion:8d40f833 2026-03-03 QuicVersion:21ac1950 2025-11-18)",
            "Accept-Language" to "zh-CN,zh;q=0.9", "X-SS-DP" to "8662",
            "X-Tt-Store-Region" to "cn", "X-Tt-Store-Region-Src" to "did",
            "X-XS-From-Web" to "0", "Sdk-Version" to "2",
            "Accept" to "application/json; charset=utf-8,application/x-protobuf",
            "x-ss-req-ticket" to ticket, "sdk-version" to "2", "passport-sdk-version" to "5051452",
            "x-xs-from-web" to "0", "x-khronos" to sec.toString(),
            "x-ladon" to java.util.Base64.getEncoder().encodeToString(ByteArray(4).also { java.security.SecureRandom().nextBytes(it) }),
            "x-argus" to java.util.Base64.getEncoder().encodeToString(
                java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(sec).array()
            ),
            "x-gorgon" to (signed.headers["X-Gorgon"] ?: ""), "x-helios" to HongguoArgus.helios(sec),
            "x-medusa" to HongguoPlaybackV5.medusaHeaderFor(signed.query, body, sec),
            "x-vc-bdturing-sdk-version" to "4.0.3.cn",
            "x-reading-request" to "$ticket-${java.security.SecureRandom().nextLong() and 0xffffffffL}",
            "x-ss-stub" to (signed.headers["X-SS-STUB"] ?: ""), "lc" to "101", "x-ss-dp" to "8662",
            "x-tt-trace-id" to "00-$hex-${hex.substring(0, 16)}-01", "Content-Encoding" to "gzip"
        )
        fire("T0-app-preload-now", Shot(base.url.substringBefore("?") + "?" + signed.query, hs, body))
    }
}
