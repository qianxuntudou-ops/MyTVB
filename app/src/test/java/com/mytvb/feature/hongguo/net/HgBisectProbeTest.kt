package com.mytvb.feature.hongguo.net

import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * 2026-11-23 二分探针：HGTV 1.3.7.9 F3.c 对读后定位到的候选不等价项逐项实弹——
 * ① x-medusa 摘要分支（HGTV 按 query 实际 iv&15 查表，我方固定分支 12 参数）
 * ② 端点 player/v1（HGTV 主通道）vs preload/v1（我方现行）
 * ③ 注册 Cookie、版本 74332、query ts 参数。
 * 试金石=V5 签名 video_tab GET（长观测 90s 间隔 6/6 放行）。枪间隔 25s 防频控。
 * 真网测试，失败不挂断言只打日志。
 */
class HgBisectProbeTest {

    private val appHttp: Call.Factory get() = HongguoHttp.client()

    private val vid = "7701937936731737112"
    private val hostB = "https://api5-normal-sinfonlineb.fqnovel.com"
    private val tabPath = "/reading/bookapi/video_tab/multi_video_model/v1/"

    private val recoveryUa = "com.phoenix.read/73932 (Linux; U; Android 16; zh_CN; 25053RT47C; " +
        "Build/BP2A.250605.031.A3; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)"

    private fun appUa(code: String) =
        "com.phoenix.read/$code (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3; " +
            "Cronet/TTNetVersion:8d40f833 2026-03-03 QuicVersion:21ac1950 2025-11-18)"

    /** gorgon 签名链定稿件。 */
    private class GSigned(
        val query: String, val ts: Int, val ticket: String, val stub: String, val low: Int,
        val gorgon: String, val helios: String
    )

    /**
     * gorgon 链签名并钉到指定 iv 低半字节分支（[want]=null 即 signRequest 口径
     * `(iv&15)%3==0` 任取；否则必须 ==want，如 12）。
     */
    private fun signAt(
        q: java.util.LinkedHashMap<String, String>, body: ByteArray?, stampMs: Long, want: Int?
    ): GSigned {
        val ts = (stampMs / 1000).toInt()
        val tuned = java.util.LinkedHashMap(q)
        for (twk in 0 until 4096L) {
            tuned["_rticket"] = (stampMs + twk).toString()
            val raw = HongguoCrypto.encodeQuery(tuned)
            val b = HongguoCrypto.branch(raw, body, ts)
            val low = b.iv and 15
            if (if (want == null) low % 3 == 0 else low == want) {
                return GSigned(
                    raw, ts, tuned["_rticket"]!!, if (body != null) HongguoCrypto.toHex(b.bodyMd5).uppercase() else "",
                    low,
                    HongguoCrypto.gorgon(raw, body, ts, HongguoCrypto.randomU32() and 0xFFFF),
                    HongguoCrypto.helios(ts, HongguoCrypto.randomU32())
                )
            }
        }
        throw IllegalStateException("no branch found")
    }

    /** 我方现行 x-medusa（HongguoPlaybackV5.medusa：固定分支 12 摘要参数）。 */
    private fun ourMedusa(s: GSigned, body: ByteArray?): String {
        val bodyMd5 = if (body != null && body.isNotEmpty()) HongguoCrypto.md5(body) else ByteArray(16)
        val random = java.security.SecureRandom()
        fun u32() = random.nextInt()
        return HongguoPlaybackV5.medusa(
            s.query, HongguoCrypto.sm3(s.query.toByteArray(Charsets.UTF_8)), bodyMd5, s.ts,
            HongguoPlaybackV5.Entropy(u32(), u32(), u32(), u32())
        )
    }

    private fun gzip(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(bos).use { it.write(data) }
        return bos.toByteArray()
    }

    private fun traceId(): String {
        val hex = java.util.UUID.randomUUID().toString().replace("-", "")
        return "00-$hex-${hex.substring(0, 16)}-01"
    }

    private fun shoot(tag: String, method: String, url: String, headers: List<Pair<String, String>>, body: ByteArray?) {
        val req = Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> header(k, v) }
            if (method == "POST") post((body ?: ByteArray(0)).toRequestBody("application/json; charset=UTF-8".toMediaType()))
        }.build()
        val out = try {
            appHttp.newCall(req).execute().use { "${it.code}/${it.body?.bytes()?.size ?: -1}" }
        } catch (e: Exception) {
            "EXC ${e.javaClass.simpleName}: ${e.message}"
        }
        println("HGBIS[$tag] $out")
    }

    private fun cookie(id: HongguoDeviceRegister.Identity) = id.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

    // ---- 现行 App 全参 query（HongguoPlay.fetchPreloadData 同构） ----
    private fun appQuery(id: HongguoDeviceRegister.Identity, code: String, name: String) =
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

    private fun sceneBody(scene: String): ByteArray = JSONObject()
        .put(
            "biz_param", JSONObject()
                .put("detail_page_version", 0).put("device_level", 4)
                .put("disable_digg_stat", false).put("disable_video_relate_book", false)
                .put("need_all_video_definition", true).put("need_mp4_align", false)
                .put("use_os_player", false).put("use_server_dns", true)
                .put("use_server_dns_scene", "cold_start_stage").put("video_platform", 1024)
        )
        .put("dr_scene", scene)
        .put("mixed_video_id_map", JSONObject().put("1004", JSONArray().put(vid)))
        .toString().toByteArray(Charsets.UTF_8)

    /** kotlinSignedPost 全头集（分支/Medusa/Cookie/ts 可控）。 */
    private fun fullHeaders(
        s: GSigned, ua: String, body: ByteArray, medusa: String?, withCookie: String?
    ): List<Pair<String, String>> {
        fun b64le32(v: Int) = java.util.Base64.getEncoder().encodeToString(
            java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(v).array()
        )
        val rnd = ByteArray(4).also { java.security.SecureRandom().nextBytes(it) }
        val h = mutableListOf(
            "User-Agent" to ua,
            "Accept-Language" to "zh-CN,zh;q=0.9",
            "X-SS-DP" to "8662",
            "X-Tt-Store-Region" to "cn",
            "X-Tt-Store-Region-Src" to "did",
            "X-XS-From-Web" to "0",
            "Sdk-Version" to "2",
            "Accept" to "application/json; charset=utf-8,application/x-protobuf",
            "x-ss-req-ticket" to s.ticket,
            "sdk-version" to "2",
            "passport-sdk-version" to "5051452",
            "x-xs-from-web" to "0",
            "x-khronos" to s.ts.toString(),
            "x-ladon" to java.util.Base64.getEncoder().encodeToString(rnd),
            "x-argus" to b64le32(s.ts),
            "x-gorgon" to s.gorgon,
            "x-helios" to s.helios,
            "x-vc-bdturing-sdk-version" to "4.0.3.cn",
            "x-reading-request" to "${s.ticket}-${java.security.SecureRandom().nextLong() and 0xffffffffL}",
            "x-ss-stub" to s.stub,
            "lc" to "101",
            "x-ss-dp" to "8662",
            "x-tt-trace-id" to traceId()
        )
        if (medusa != null) h.add("x-medusa" to medusa)
        if (withCookie != null) h.add("Cookie" to withCookie)
        h.add("Content-Encoding" to "gzip")
        return h
    }

    /** aston 22 键 video_tab query（gorgon 链与 V5 链共用同一参数序）。 */
    private fun tabParams(id: HongguoDeviceRegister.Identity) = java.util.LinkedHashMap<String, String>().apply {
        put("aid", "8662"); put("app_name", "novelread"); put("version_code", "74332")
        put("version_name", "7.4.3.32"); put("channel", "vivo_8662_64"); put("device_platform", "android")
        put("device_type", "PGT-AN10"); put("os_version", "12"); put("update_version_code", "74332")
        put("iid", id.installId); put("cdid", id.cdid); put("device_id", id.deviceId)
        put("video_id", vid); put("video_id_type", "0"); put("device_level", "1")
        put("need_all_video_definition", "1"); put("need_mp4_align", "0"); put("use_os_player", "0")
        put("video_platform", "1024"); put("source", "0"); put("from_video_id", "")
    }

    /** 试金石 G 系列单枪：分支 / 是否带 Medusa / 是否带 Helios。 */
    private data class GShot(val tag: String, val want: Int, val medusa: Boolean, val helios: Boolean)

    /** 试金石 G 系列：video_tab GET + gorgon 链，按分支钉死归因 Medusa 摘要。 */
    @Test
    fun probeTabBranch() {
        val id = try { HongguoDeviceRegister.registerFresh() } catch (e: Exception) {
            println("HGBIS[reg] FAIL ${e.message}"); return
        }
        println("HGBIS[reg] device=${id.deviceId}")
        Thread.sleep(1500)
        val tabUrl = "$hostB$tabPath"
        val getHeads = listOf(
            "User-Agent" to recoveryUa,
            "Accept" to "application/json; charset=utf-8",
            "Accept-Language" to "zh-CN,zh;q=0.9"
        )
        for (g in listOf(
            // 我方 Medusa 在此分支自洽
            GShot("G2-tab-b12-medusa", 12, true, true),
            // 我方 Medusa 参数表用错（HGTV 此处查 f2594a.get(0/6)）
            GShot("G1-tab-b0-medusa", 0, true, true),
            GShot("G5-tab-b6-medusa", 6, true, true),
            // 去掉 Medusa：服务端是否强制
            GShot("G3-tab-b0-nomedusa", 0, false, true),
            // 只留 gorgon
            GShot("G4-tab-b0-gorgononly", 0, false, false)
        )) {
            val s = signAt(tabParams(id), null, System.currentTimeMillis(), g.want)
            val h = getHeads + listOf(
                "x-khronos" to s.ts.toString(),
                "x-gorgon" to s.gorgon,
                "x-ss-req-ticket" to s.ticket
            ) + (if (g.helios) listOf("x-helios" to s.helios) else emptyList()) +
                (if (g.medusa) listOf("x-medusa" to ourMedusa(s, null)) else emptyList())
            println("HGBIS[${g.tag}] branch=${s.low}")
            shoot(g.tag, "GET", "$tabUrl?${s.query}", h, null)
            Thread.sleep(25_000)
        }
    }

    /** S 系列单枪配置：端点 / 版本 / 分支 / Medusa / Cookie 逐项叠加。 */
    private data class Shot(
        val tag: String, val path: String, val scene: String,
        val code: String, val name: String, val branch: Int?, val medusa: Boolean, val cookie: Boolean
    )

    /** S 系列：POST 端点逐项对齐 HGTV 1.3.7.9（现行形态 → 分支 12 → player/v1 → 74332 → Cookie）。 */
    @Test
    fun probePostAlign() {
        val id = try {
            HongguoDeviceRegister.registerFresh()
        } catch (e: Exception) {
            println("HGBIS[reg2] FAIL ${e.message}"); return
        }
        println("HGBIS[reg2] device=${id.deviceId}")
        Thread.sleep(1500)
        val preload = "/novel/player/multi_video_model/preload/v1"
        val player = "/novel/player/multi_video_model/player/v1"
        for (s in listOf(
            // 现行 App 形态复测（任意 %3==0 分支），确认基线仍空
            Shot("S0-preload-asis", preload, "preload", "74532", "7.4.5.32", null, true, false),
            // 只钉分支 12：我方 Medusa 摘要自洽
            Shot("S1-preload-b12", preload, "preload", "74532", "7.4.5.32", 12, true, false),
            // 钉分支 0：Medusa 明确不自洽，作对照
            Shot("S2-preload-b0", preload, "preload", "74532", "7.4.5.32", 0, true, false),
            // 去掉 x-medusa：服务端是否强制
            Shot("S3-preload-nomedusa", preload, "preload", "74532", "7.4.5.32", null, false, false),
            // 换 HGTV 主端点 player/v1
            Shot("S4-player-b12", player, "player", "74532", "7.4.5.32", 12, true, false),
            // 版本对齐 HGTV 74332 / 7.4.3.32
            Shot("S5-player-74332-b12", player, "player", "74332", "7.4.3.32", 12, true, false),
            // 带注册 Cookie
            Shot("S6-player-74332-cookie-b12", player, "player", "74332", "7.4.3.32", 12, true, true),
            // 同 S6 但分支任意：分支是否决定成败
            Shot("S7-player-74332-cookie-asis", player, "player", "74332", "7.4.3.32", null, true, true)
        )) {
            val body = gzip(sceneBody(s.scene))
            val signed = signAt(appQuery(id, s.code, s.name), body, System.currentTimeMillis(), s.branch)
            val h = fullHeaders(
                signed, appUa(s.code), body,
                if (s.medusa) ourMedusa(signed, body) else null,
                if (s.cookie) cookie(id) else null
            )
            println("HGBIS[${s.tag}] branch=${signed.low}")
            shoot(s.tag, "POST", "$hostB${s.path}?${signed.query}", h, body)
            Thread.sleep(25_000)
        }
    }
}
