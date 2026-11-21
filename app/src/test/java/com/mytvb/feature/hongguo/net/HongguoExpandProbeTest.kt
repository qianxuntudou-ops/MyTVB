package com.mytvb.feature.hongguo.net

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * 2026-11-21 三通道扩展探针（HGTV 1.3.8.6 骨架挖掘后续，判定第二通道/动态配置/心跳可行性）：
 * 1. multi_video_detail/preload/v1 —— 批量详情预热通道（HGTV U() 形态：series_id 装的是
 *    vid 逗号串、每批 6、版本 74332、body 未 gzip、biz_param 带 image_shrink_datas_str）
 * 2. tnc3 /service/settings/v3/ —— TNC 配置下发（字节 TTNet 全局配置：ttnet_http_dns_addr
 *    等；命中即白嫖官方 host→IP 池热更能力）
 * 3. security.snssdk.com /passport/token/beat/v2 —— 会话心跳（匿名结构探测）
 * 真网测试，失败不挂断言只打日志。
 */
class HongguoExpandProbeTest {

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS)
        .build()

    private fun traceId(): String {
        val hex = java.util.UUID.randomUUID().toString().replace("-", "")
        return "00-$hex-${hex.substring(0, 16)}-01"
    }

    private fun cookieHeader(id: HongguoDeviceRegister.Identity): String =
        id.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

    /** 全参 query（K-M1 形态 Redmi 画像，74332——detail 通道实测版本，HGTV U() 同款）。 */
    private fun fullQuery(
        id: HongguoDeviceRegister.Identity, code: String = "74332"
    ): java.util.LinkedHashMap<String, String> {
        val q = java.util.LinkedHashMap<String, String>()
        q["device_platform"] = "android"; q["os"] = "android"; q["ssmix"] = "v"
        q["cdid"] = id.cdid; q["channel"] = "update_64"
        q["aid"] = "8662"; q["app_name"] = "novelread"
        q["version_code"] = code; q["version_name"] = "7.4.3.32"
        q["manifest_version_code"] = code; q["update_version_code"] = code
        q["resolution"] = "1280*2772"; q["dpi"] = "520"
        q["device_type"] = "25053RT47C"; q["device_brand"] = "Redmi"
        q["language"] = "zh"; q["os_api"] = "36"; q["os_version"] = "16"
        q["ac"] = "wifi"; q["host_abi"] = "arm64-v8a"
        q["dragon_device_type"] = "phone"; q["pv_player"] = code
        q["compliance_status"] = "0"; q["need_personal_recommend"] = "0"
        q["player_so_load"] = "0"; q["is_android_pad_screen"] = "0"
        q["rom_version"] = "BP2A.250605.031.A3"
        q["iid"] = id.installId; q["device_id"] = id.deviceId
        if (id.klinkEgdi.isNotEmpty()) q["klink_egdi"] = id.klinkEgdi
        return q
    }

    private fun ua(code: String = "74332"): String =
        "com.phoenix.read/$code (Linux; U; Android 16; zh_CN; 25053RT47C; " +
            "Build/BP2A.250605.031.A3; Cronet/TTNetVersion:8d40f833 2026-03-03 " +
            "QuicVersion:21ac1950 2025-11-18)"

    /** 假 argus+medusa 头集（现行 App kotlinSignedPost 放行组合的测试内复刻）。 */
    private fun headerSet(
        q: java.util.LinkedHashMap<String, String>, body: ByteArray,
        deviceId: String
    ): List<Pair<String, String>> {
        val signed = HongguoCrypto.signRequest(
            q, body, System.currentTimeMillis(), deviceId, targetBranch = 0, version = "7.4.3.32"
        )
        val nowSec = signed.headers["X-Khronos"]?.toIntOrNull()
            ?: (System.currentTimeMillis() / 1000).toInt()
        fun b64le32(v: Int) = java.util.Base64.getEncoder().encodeToString(
            java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .putInt(v).array()
        )
        val ticket = signed.headers["X-SS-Req-Ticket"] ?: ""
        return signed.headers.entries.map { it.key to it.value } + listOf(
            "User-Agent" to ua(),
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
            "x-khronos" to (signed.headers["X-Khronos"] ?: ""),
            "x-ladon" to java.util.Base64.getEncoder().encodeToString(
                ByteArray(4).also { java.security.SecureRandom().nextBytes(it) }
            ),
            "x-argus" to b64le32(nowSec),
            "x-gorgon" to (signed.headers["X-Gorgon"] ?: ""),
            "x-helios" to HongguoArgus.helios(nowSec),
            "x-medusa" to HongguoPlaybackV5.medusaHeaderFor(signed.query, body, nowSec),
            "Content-Type" to "application/json; charset=UTF-8",
            "x-vc-bdturing-sdk-version" to "4.0.3.cn",
            "x-reading-request" to "$ticket-${java.security.SecureRandom().nextLong() and 0xffffffffL}",
            "x-ss-stub" to (signed.headers["X-SS-STUB"] ?: ""),
            "lc" to "101",
            "x-ss-dp" to "8662",
            "x-tt-trace-id" to traceId()
        )
    }

    private fun send(
        url: String, headers: List<Pair<String, String>>,
        method: String = "GET", body: ByteArray? = null
    ): String {
        val req = Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> addHeader(k, v) }
            if (method == "POST" && body != null) {
                post(body.toRequestBody("application/json; charset=UTF-8".toMediaType()))
            }
        }.build()
        return try {
            http.newCall(req).execute().use { resp ->
                val bytes = resp.body?.bytes() ?: ByteArray(0)
                val head = if (bytes.isEmpty()) "" else String(bytes, Charsets.UTF_8).take(220)
                "http=${resp.code} len=${bytes.size} $head"
            }
        } catch (e: Exception) {
            "EXC ${e.message}"
        }
    }

    /** 通道 1：multi_video_detail/preload/v1（HGTV U() 原样：未 gzip body）。 */
    @Test
    fun probeDetailChannel() {
        val id = try {
            HongguoDeviceRegister.registerFresh()
        } catch (e: Exception) {
            println("HG-DETAIL register fail: ${e.message}"); return
        }
        Thread.sleep(800)
        val vids = listOf("7705826165692304409", "7705826348597529625", "7705826451638817989")
        val biz = JSONObject()
            .put("detail_page_version", 0)
            .put("disable_digg_stat", false)
            .put("disable_video_relate_book", false)
            .put("image_shrink_datas_str", "W3siaW1hZ2VfdHlwZSI6MywiaW1hZ2Vfd2lkdGgiOjEwODAsInNocmlua190eXBlIjozfSx7ImltYWdlX3R5cGUiOjQsImltYWdlX3dpZHRoIjo0Nywic2hyaW5rX3R5cGUiOjR9XQ==")
            .put("need_all_video_definition", false)
            .put("need_mp4_align", false)
            .put("screen_width_px", "1080")
            .put("source", 7)
            .put("use_os_player", false)
            .put("use_server_dns", false)
        val body = JSONObject()
            .put("biz_param", biz)
            .put("dr_scene", "preload")
            .put("series_id", vids.joinToString(","))
            .toString().toByteArray(Charsets.UTF_8)

        val q = fullQuery(id)
        val headers = headerSet(q, body, id.deviceId)
        val url = "https://api5-normal-sinfonlineb.fqnovel.com/novel/player/multi_video_detail/preload/v1?" +
            HongguoCrypto.encodeQuery(q)
        println("HG-DETAIL raw-plain: " + send(url, headers, "POST", body))

        // 对照：gzip body + Content-Encoding（我们主通道形态）
        val bos = ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(bos).use { it.write(body) }
        val gz = bos.toByteArray()
        val headersGz = headerSet(q, gz, id.deviceId) + ("Content-Encoding" to "gzip")
        println("HG-DETAIL gzip:      " + send(url, headersGz, "POST", gz))
    }

    /** 通道 2：TNC settings/v3（字节全局配置下发）。 */
    @Test
    fun probeTnc() {
        val id = try {
            HongguoDeviceRegister.registerFresh()
        } catch (e: Exception) {
            println("HG-TNC register fail: ${e.message}"); return
        }
        Thread.sleep(800)
        val q = fullQuery(id).apply {
            // HGTV D.O.a 传 app=SdkVersion.MINI_VERSION，jadx 错位引用 umeng 常量，实值 "1"
            put("app", "1"); put("settings_time", "0")
            put("_rticket", System.currentTimeMillis().toString())
        }
        val cookie = cookieHeader(id)
        // tnc3 集群变体矩阵（找 404 根因：is.snssdk 通但不下发 ttnet 网络层配置）
        for ((tag, host) in listOf(
            "tnc3-aliec2-https" to "https://tnc3-aliec2.zijieapi.com",
            "tnc3-aliec2-http" to "http://tnc3-aliec2.zijieapi.com",
            "tnc3-bjlgy-https" to "https://tnc3-bjlgy.zijieapi.com",
            "tnc11-aliec2-https" to "https://tnc11-aliec2.zijieapi.com"
        )) {
            val u = "$host/service/settings/v3/?${HongguoCrypto.encodeQuery(q)}"
            println("HG-TNC3 $tag: " + send(u, listOf(
                "User-Agent" to ua(), "Accept" to "application/json",
                "Cookie" to cookie, "x-tt-token" to id.deviceToken
            )))
        }
        // is.snssdk.com 实证可达，重点解析 HGTV T3.h 消费的四个 data 直下字段
        val url = "https://is.snssdk.com/service/settings/v3/?${HongguoCrypto.encodeQuery(q)}"
        val req = Request.Builder().url(url).apply {
            addHeader("User-Agent", ua()); addHeader("Accept", "application/json")
            addHeader("Cookie", cookie); addHeader("x-tt-token", id.deviceToken)
        }.build()
        try {
            http.newCall(req).execute().use { resp ->
                val bytes = resp.body?.bytes() ?: ByteArray(0)
                println("HG-TNC http=${resp.code} len=${bytes.size}")
                if (bytes.isNotEmpty()) {
                    val data = JSONObject(String(bytes, Charsets.UTF_8)).optJSONObject("data")
                    if (data == null) { println("HG-TNC no data node") }
                    else {
                        for (k in listOf("ttnet_http_dns_addr", "tnc_config", "tnc_update_interval", "ttnet_preconnect_urls")) {
                            val v = data.opt(k)
                            println("HG-TNC key=$k -> " + when {
                                v == null -> "MISS"
                                v is org.json.JSONArray -> "ARR[${v.length()}] " + v.take(1).toString().take(120)
                                else -> v.toString().take(160)
                            })
                        }
                        // http_dns 池内容采样（hostname → IP 列表）
                        val dns = data.optJSONObject("ttnet_http_dns_addr")
                        if (dns != null) {
                            dns.keys().asSequence().take(8).forEach { host2 ->
                                println("HG-TNC dns $host2 -> ${dns.opt(host2)}")
                            }
                        }
                        // 扫 settings 全量 key 找 ttnet/tnc 系配置
                        val settings = data.optJSONObject("settings")
                        if (settings != null) {
                            val keys = settings.keys().asSequence().toList()
                            println("HG-TNC settings total=${keys.size}")
                            keys.filter { it.contains("tnc") || it.contains("ttnet") || it.contains("dns") || it.contains("host") }
                                .forEach { println("HG-TNC settings-hit $it -> ${settings.opt(it).toString().take(120)}") }
                        }
                    }
                }
            }
        } catch (e: Exception) { println("HG-TNC EXC ${e.message}") }
    }

    /** 通道 4：multi_video_model/preload/v1 host×版本矩阵（2026-11-21 15:2x 起全空后的放行组合搜寻）。 */
    @Test
    fun probeModelHosts() {
        val id = try {
            HongguoDeviceRegister.registerFresh()
        } catch (e: Exception) {
            println("HG-MODEL register fail: ${e.message}"); return
        }
        Thread.sleep(800)
        val vid = "7705826165692304409"
        fun preloadBody(): ByteArray {
            val arr = org.json.JSONArray().apply { put(vid) }
            return JSONObject()
                .put("biz_param", JSONObject()
                    .put("detail_page_version", 0)
                    .put("device_level", 4)
                    .put("disable_digg_stat", false)
                    .put("disable_video_relate_book", false)
                    .put("need_all_video_definition", true)
                    .put("need_mp4_align", false)
                    .put("use_os_player", false)
                    .put("use_server_dns", true)
                    .put("use_server_dns_scene", "cold_start_stage")
                    .put("video_platform", 1024))
                .put("dr_scene", "preload")
                .put("mixed_video_id_map", JSONObject().put("1004", arr))
                .toString().toByteArray(Charsets.UTF_8)
        }

        fun shootModel(tag: String, host: String, code: String, name: String, serverDns: Boolean = true) {
            val raw = preloadBody()
            val bos = ByteArrayOutputStream(); java.util.zip.GZIPOutputStream(bos).use { it.write(raw) }
            val gz = bos.toByteArray()
            val q = fullQuery(id, code).apply {
                put("version_name", name)
                if (!serverDns) remove("x-dns") // 无此键，占位无效无害
            }
            // 版本名进签名（signRequest version 参数）
            val signed = HongguoCrypto.signRequest(
                q, gz, System.currentTimeMillis(), id.deviceId, targetBranch = 0, version = name
            )
            val hs = headerSet(q, gz, id.deviceId) + ("Content-Encoding" to "gzip")
            val url = "https://$host/novel/player/multi_video_model/preload/v1?${signed.query}"
            val req = Request.Builder().url(url).apply {
                hs.forEach { (k, v) -> addHeader(k, v) }
                post(gz.toRequestBody("application/json; charset=UTF-8".toMediaType()))
            }.build()
            val out = try {
                http.newCall(req).execute().use { r ->
                    val b = r.body?.bytes() ?: ByteArray(0)
                    "http=${r.code} len=${b.size}" + if (b.isEmpty()) "" else " " + String(b, Charsets.UTF_8).take(80)
                }
            } catch (e: Exception) { "EXC ${e.message}" }
            println("HG-MODEL[$tag] $out")
            Thread.sleep(1200)
        }

        // 6 host 线 × 74532
        for (h in listOf("api5-normal-sinfonlinea", "api5-normal-sinfonlineb", "api5-normal-sinfonlinec",
                         "api5-normal-lf", "api5-normal-lq", "api5-normal-hl")) {
            shootModel("host-${h.substringAfterLast('-')}@74532", "$h.fqnovel.com", "74532", "7.4.5.32")
        }
        // b 线 × 旧版本 73932
        shootModel("host-b@73932", "api5-normal-sinfonlineb.fqnovel.com", "73932", "7.3.9.32")
        // b 线 × 74332
        shootModel("host-b@74332", "api5-normal-sinfonlineb.fqnovel.com", "74332", "7.4.3.32")
    }

    /** 通道 5：真 Argus 全头集 × preload/v1（2026-11-21 15:2x 假argus组合全空后的头集回溯）。 */
    @Test
    fun probeArgusModel() {
        val id = try {
            HongguoDeviceRegister.registerFresh()
        } catch (e: Exception) {
            println("HG-ARGM register fail: ${e.message}"); return
        }
        Thread.sleep(800)
        val vid = "7705826165692304409"
        val arr = org.json.JSONArray().apply { put(vid) }
        val raw = JSONObject()
            .put("biz_param", JSONObject()
                .put("detail_page_version", 0).put("device_level", 4)
                .put("disable_digg_stat", false).put("disable_video_relate_book", false)
                .put("need_all_video_definition", true).put("need_mp4_align", false)
                .put("use_os_player", false).put("use_server_dns", true)
                .put("use_server_dns_scene", "cold_start_stage").put("video_platform", 1024))
            .put("dr_scene", "preload")
            .put("mixed_video_id_map", JSONObject().put("1004", arr))
            .toString().toByteArray(Charsets.UTF_8)
        val bos = ByteArrayOutputStream(); java.util.zip.GZIPOutputStream(bos).use { it.write(raw) }
        val gz = bos.toByteArray()

        fun shootArgus(tag: String, withMedusa: Boolean) {
            val q = fullQuery(id, "74532").apply { put("version_name", "7.4.5.32") }
            val signed = HongguoCrypto.signRequest(
                q, gz, System.currentTimeMillis(), id.deviceId, targetBranch = 0, version = "7.4.5.32"
            )
            val nowSec = signed.headers["X-Khronos"]?.toIntOrNull() ?: (System.currentTimeMillis() / 1000).toInt()
            val la = HongguoArgus.generate(
                signed.query, gz, nowSec, "7.4.5.32", id.deviceId,
                Triple("25053RT47C", "16", "update_64")
            )
            val ticket = signed.headers["X-SS-Req-Ticket"] ?: ""
            val hs = listOf(
                "User-Agent" to ua("74532"),
                "Accept-Language" to "zh-CN,zh;q=0.9",
                "X-SS-DP" to "8662", "X-Tt-Store-Region" to "cn", "X-Tt-Store-Region-Src" to "did",
                "X-XS-From-Web" to "0", "Sdk-Version" to "2",
                "Accept" to "application/json; charset=utf-8,application/x-protobuf",
                "x-ss-req-ticket" to ticket, "sdk-version" to "2", "passport-sdk-version" to "5051452",
                "x-khronos" to (signed.headers["X-Khronos"] ?: ""),
                "x-ladon" to la.ladon, "x-argus" to la.argus,
                "x-gorgon" to (signed.headers["X-Gorgon"] ?: ""),
                "x-helios" to HongguoArgus.helios(nowSec),
                "Content-Type" to "application/json; charset=UTF-8",
                "x-vc-bdturing-sdk-version" to "4.0.3.cn",
                "x-reading-request" to "$ticket-${java.security.SecureRandom().nextLong() and 0xffffffffL}",
                "x-ss-stub" to (signed.headers["X-SS-STUB"] ?: ""),
                "Content-Encoding" to "gzip",
                "lc" to "101", "x-ss-dp" to "8662", "x-tt-trace-id" to traceId()
            ) + if (withMedusa) listOf(
                "x-medusa" to HongguoPlaybackV5.medusaHeaderFor(signed.query, gz, nowSec)
            ) else emptyList()
            val url = "https://api5-normal-sinfonlineb.fqnovel.com/novel/player/multi_video_model/preload/v1?${signed.query}"
            val req = Request.Builder().url(url).apply {
                hs.forEach { (k, v) -> addHeader(k, v) }
                post(gz.toRequestBody("application/json; charset=UTF-8".toMediaType()))
            }.build()
            val out = try {
                http.newCall(req).execute().use { r ->
                    val b = r.body?.bytes() ?: ByteArray(0)
                    "http=${r.code} len=${b.size}" + if (b.isEmpty()) "" else " " + String(b, Charsets.UTF_8).take(80)
                }
            } catch (e: Exception) { "EXC ${e.message}" }
            println("HG-ARGM[$tag] $out")
            Thread.sleep(1200)
        }
        shootArgus("argus-full", withMedusa = false)
        shootArgus("argus+medusa", withMedusa = true)
    }

    /** 通道 6：1.3.7.1 x3 头集形态矩阵（dns/头集/±medusa 逐项对齐活着的 1.3.7.1）。 */
    @Test
    fun probeX3Form() {
        val id = try {
            HongguoDeviceRegister.registerFresh()
        } catch (e: Exception) {
            println("HG-X3 register fail: ${e.message}"); return
        }
        Thread.sleep(800)
        val vid = "7705826165692304409"

        fun shootX3(
            tag: String, serverDns: Boolean, addTT: Boolean, sdkVer: String,
            withMedusa: Boolean, code: String = "73932"
        ) {
            val arr = org.json.JSONArray().apply { put(vid) }
            val biz = JSONObject()
                .put("detail_page_version", 0).put("device_level", 4)
                .put("disable_digg_stat", false).put("disable_video_relate_book", false)
                .put("need_all_video_definition", true).put("need_mp4_align", false)
                .put("use_os_player", false).put("use_server_dns", serverDns)
                .put("video_platform", 1024)
            if (serverDns) biz.put("use_server_dns_scene", "cold_start_stage")
            val raw = JSONObject()
                .put("biz_param", biz).put("dr_scene", "preload")
                .put("mixed_video_id_map", JSONObject().put("1004", arr))
                .toString().toByteArray(Charsets.UTF_8)
            val bos = ByteArrayOutputStream(); java.util.zip.GZIPOutputStream(bos).use { it.write(raw) }
            val gz = bos.toByteArray()
            val name = if (code == "73932") "7.3.9.32" else "7.4.5.32"
            val q = fullQuery(id, code).apply { put("version_name", name) }
            val signed = HongguoCrypto.signRequest(
                q, gz, System.currentTimeMillis(), id.deviceId, targetBranch = 0, version = name
            )
            val nowSec = signed.headers["X-Khronos"]?.toIntOrNull() ?: (System.currentTimeMillis() / 1000).toInt()
            val hs = headerSet(q, gz, id.deviceId).toMutableList().apply {
                removeAll { it.first.equals("passport-sdk-version", true) }
                add("passport-sdk-version" to sdkVer)
                if (addTT) {
                    add("x-tt-dt" to "")
                    add("x-tt-request-tag" to "t=0;n=0")
                }
                if (!withMedusa) removeAll { it.first.equals("x-medusa", true) }
            }
            val url = "https://api5-normal-sinfonlineb.fqnovel.com/novel/player/multi_video_model/preload/v1?${signed.query}"
            val req = Request.Builder().url(url).apply {
                hs.forEach { (k, v) -> addHeader(k, v) }
                post(gz.toRequestBody("application/json; charset=UTF-8".toMediaType()))
            }.build()
            val out = try {
                http.newCall(req).execute().use { r ->
                    val b = r.body?.bytes() ?: ByteArray(0)
                    "http=${r.code} len=${b.size}" + if (b.isEmpty()) "" else " " + String(b, Charsets.UTF_8).take(80)
                }
            } catch (e: Exception) { "EXC ${e.message}" }
            println("HG-X3[$tag] $out")
            Thread.sleep(1200)
        }
        // A: 仅 body dns=false（1.3.7.1 形态），其余现行
        shootX3("dns-false", serverDns = false, addTT = false, sdkVer = "5051452", withMedusa = true)
        // B: + x3 头集补齐（x-tt-dt/x-tt-request-tag/passport 6031190）
        shootX3("x3-headers", serverDns = false, addTT = true, sdkVer = "6031190", withMedusa = true)
        // C: B 去掉 x-medusa（判 medusa 是否画蛇添足）
        shootX3("no-medusa", serverDns = false, addTT = true, sdkVer = "6031190", withMedusa = false)
        // D: B 回 dns=true 对照（应与现行同空）
        shootX3("x3-dns-true", serverDns = true, addTT = true, sdkVer = "6031190", withMedusa = true)
        // E: B 换 74532 版本
        shootX3("x3@74532", serverDns = false, addTT = true, sdkVer = "6031190", withMedusa = true, code = "74532")
    }

    /** 通道 7：100001 稳定性判定——同形态隔时重打，稳定=参数问题可二分，抖动=服务端滚动。 */
    @Test
    fun probeStability() {
        val id = try {
            HongguoDeviceRegister.registerFresh()
        } catch (e: Exception) {
            println("HG-STAB register fail: ${e.message}"); return
        }
        Thread.sleep(800)
        val vid = "7705826165692304409"
        val arr = org.json.JSONArray().apply { put(vid) }
        val raw = JSONObject()
            .put("biz_param", JSONObject()
                .put("detail_page_version", 0).put("device_level", 4)
                .put("disable_digg_stat", false).put("disable_video_relate_book", false)
                .put("need_all_video_definition", true).put("need_mp4_align", false)
                .put("use_os_player", false).put("use_server_dns", true)
                .put("use_server_dns_scene", "cold_start_stage").put("video_platform", 1024))
            .put("dr_scene", "preload")
            .put("mixed_video_id_map", JSONObject().put("1004", arr))
            .toString().toByteArray(Charsets.UTF_8)
        val bos = ByteArrayOutputStream(); java.util.zip.GZIPOutputStream(bos).use { it.write(raw) }
        val gz = bos.toByteArray()
        val q = fullQuery(id, "74532").apply { put("version_name", "7.4.5.32") }
        val signed = HongguoCrypto.signRequest(
            q, gz, System.currentTimeMillis(), id.deviceId, targetBranch = 0, version = "7.4.5.32"
        )
        val url = "https://api5-normal-sinfonlineb.fqnovel.com/novel/player/multi_video_model/preload/v1?${signed.query}"
        repeat(3) { n ->
            val hs = headerSet(q, gz, id.deviceId) + ("Content-Encoding" to "gzip")
            val req = Request.Builder().url(url).apply {
                hs.forEach { (k, v) -> addHeader(k, v) }
                post(gz.toRequestBody("application/json; charset=UTF-8".toMediaType()))
            }.build()
            val out = try {
                http.newCall(req).execute().use { r ->
                    val b = r.body?.bytes() ?: ByteArray(0)
                    "http=${r.code} len=${b.size}" + if (b.isEmpty()) "" else " " + String(b, Charsets.UTF_8).take(100)
                }
            } catch (e: Exception) { "EXC ${e.message}" }
            println("HG-STAB[$n] $out")
            Thread.sleep(2000)
        }
    }

    /** 通道 3：passport beat/v2 心跳（匿名结构探测）。 */
    @Test
    fun probeBeat() {
        val id = try {
            HongguoDeviceRegister.registerFresh()
        } catch (e: Exception) {
            println("HG-BEAT register fail: ${e.message}"); return
        }
        Thread.sleep(800)
        val cookie = cookieHeader(id)
        val url = "https://security.snssdk.com/passport/token/beat/v2/?scene=boot&first_beat=true"
        println("HG-BEAT anon:     " + send(url, listOf(
            "User-Agent" to ua(), "Accept" to "application/json",
            "sdk-version" to "2", "passport-sdk-version" to "5051452"
        )))
        println("HG-BEAT with-cookie: " + send(url, listOf(
            "User-Agent" to ua(), "Accept" to "application/json",
            "sdk-version" to "2", "passport-sdk-version" to "5051452",
            "Cookie" to cookie, "x-tt-token" to id.deviceToken
        )))
    }
}
