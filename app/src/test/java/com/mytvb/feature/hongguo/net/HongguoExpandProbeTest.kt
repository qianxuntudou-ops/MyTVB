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

    /**
     * 通道 4：log5-applog /service/2/app_log/ —— 事件埋点上报（哑巴设备嫌疑的对拍实验）。
     * 逆向结论：HGTV 1.3.8.6 自己也不发埋点（dex 字符串表仅 device_register 端点，
     * 无 magic_tag/events 组包代码）——本通道不是对齐 HGTV，而是补齐真红果的
     * 行为上报史（注册后零上报的 device_id 有被清洗嫌疑）。
     * 形态：gzip(明文 JSON{magic_tag:ss_app_log, header, events, seq}) + device_register
     * 同款签名头（假 Argus+Gorgon+Khronos+X-SS-STUB）。同引擎（AppLog）同校验源。
     * 验收判据：resp message==success 且 success_count>0（服务端点收事件）。
     */
    @Test
    fun probeApplog() {
        val id = try {
            HongguoDeviceRegister.registerFresh()
        } catch (e: Exception) {
            println("HG-APPLOG register fail: ${e.message}"); return
        }
        Thread.sleep(800)
        val now = System.currentTimeMillis()
        val nowSec = now / 1000

        fun applogBody(events: List<Pair<String, JSONObject>>): ByteArray {
            val header = JSONObject()
                .put("aid", 8662).put("app_id", 8662)
                .put("appver", "7.4.5.32").put("app_version", "7.4.5.32")
                .put("channel", HongguoEnv.CHANNEL)
                .put("device_brand", HongguoEnv.deviceBrand)
                .put("device_model", HongguoEnv.deviceType)
                .put("device_platform", "android")
                .put("os", "android")
                .put("os_api", HongguoEnv.osApi.toInt())
                .put("os_version", HongguoEnv.osVersion)
                .put("resolution", HongguoEnv.resolution.replace('*', 'x'))
                .put("language", "zh").put("region", "CN")
                .put("timezone", 8).put("tz_name", "Asia/Shanghai").put("tz_offset", 28800)
                .put("access", "wifi").put("package", "com.phoenix.read")
                .put("version_code", "74532")
                .put("device_id", id.deviceId)
                .put("iid", id.installId)
                .put("openudid", id.openUdid)
                .put("cdid", id.cdid)
                .put("sdk_version", "3.7.0-rc.34-fanqie-xiaoshuo")
                .put("sdk_flavor", "china")
            if (id.deviceToken.isNotEmpty()) header.put("ss_app_token", id.deviceToken)
            val arr = org.json.JSONArray()
            events.forEach { (name, params) ->
                arr.put(JSONObject().put("time", now).put("event", name).put("params", params))
            }
            return JSONObject()
                .put("magic_tag", "ss_app_log")
                .put("header", header)
                .put("events", arr)
                .put("seq", 1)
                .put("_gen_time", now)
                .toString().toByteArray(Charsets.UTF_8)
        }

        fun shoot(tag: String, host: String, gzip: Boolean, extraEvents: Boolean) {
            val events = buildList {
                add("__activate" to JSONObject())
                if (extraEvents) {
                    add("app_launch" to JSONObject().put("launch_from", "cold").put("duration_ms", 0))
                }
            }
            val raw = applogBody(events)
            val body = if (gzip) {
                val bos = ByteArrayOutputStream(); java.util.zip.GZIPOutputStream(bos).use { it.write(raw) }
                bos.toByteArray()
            } else raw
            val q = fullQuery(id, "74532").apply {
                put("version_name", "7.4.5.32")
                put("openudid", id.openUdid)
                put("tt_data", "a")
                put("ts", nowSec.toString())
                put("_rticket", now.toString())
            }
            val rawQuery = HongguoCrypto.encodeQuery(q)
            val nonce = HongguoCrypto.randomU32()
            val req = Request.Builder()
                .url("https://$host/service/2/app_log/?$rawQuery")
                .apply {
                    header("User-Agent", HongguoEnv.ua("74532"))
                    header("Content-Type", "application/json; charset=utf-8")
                    header("Accept", "application/json; charset=utf-8,application/x-protobuf")
                    if (gzip) header("Content-Encoding", "gzip")
                    header("X-Tt-Request-Tag", "t=0;n=1")
                    header("Lc", "102")
                    header("Sdk-Version", "2")
                    header("X-SS-DP", "8662")
                    header("X-Vc-Bdturing-Sdk-Version", "4.0.3.cn")
                    header("X-Tt-Store-Region", "cn")
                    header("X-Tt-Store-Region-Src", "did")
                    header("X-Khronos", nowSec.toString())
                    header(
                        "X-Argus",
                        java.util.Base64.getEncoder().encodeToString(
                            java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                                .putInt(nowSec.toInt()).array()
                        )
                    )
                    header("X-Ladon", java.util.Base64.getEncoder().encodeToString(
                        ByteArray(4).also { java.security.SecureRandom().nextBytes(it) }
                    ))
                    header("X-SS-Req-Ticket", now.toString())
                    header("X-SS-STUB", HongguoCrypto.toHex(HongguoCrypto.md5(body)))
                    header("X-Gorgon", HongguoCrypto.gorgon(rawQuery, body, nowSec.toInt(), nonce and 0xFFFF))
                    post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                }.build()
            val out = try {
                http.newCall(req).execute().use { r ->
                    val b = r.body?.bytes() ?: ByteArray(0)
                    "http=${r.code} len=${b.size}" + if (b.isEmpty()) "" else " " + String(b, Charsets.UTF_8).take(300)
                }
            } catch (e: Exception) { "EXC ${e.message}" }
            println("HG-APPLOG[$tag] $out")
        }

        shoot("A-gzip-1ev", "log5-applog.fqnovel.com", gzip = true, extraEvents = false)
        Thread.sleep(1200)
        shoot("B-plain-1ev", "log5-applog.fqnovel.com", gzip = false, extraEvents = false)
        Thread.sleep(1200)
        shoot("C-gzip-2ev", "log5-applog.fqnovel.com", gzip = true, extraEvents = true)
        Thread.sleep(1200)
        shoot("D-lq-host", "log5-applog-lq.fqnovel.com", gzip = true, extraEvents = true)

        // E：App 真实组包产物（HongguoApplog.buildReport 原样字节）——链路收官验证
        Thread.sleep(1200)
        runCatching {
            val r = HongguoApplog.buildReport(id, System.currentTimeMillis())
            val q2 = fullQuery(id, HongguoVersionProfiles.default.code).apply {
                put("version_name", HongguoVersionProfiles.default.versionName)
                put("tt_data", "a"); put("ts", r.nowSec.toString()); put("_rticket", r.now.toString())
            }
            val rq = HongguoCrypto.encodeQuery(q2)
            val reqE = Request.Builder()
                .url("https://log5-applog.fqnovel.com/service/2/app_log/?$rq")
                .apply {
                    header("User-Agent", HongguoEnv.ua(HongguoVersionProfiles.default.code))
                    header("Content-Type", "application/json; charset=utf-8")
                    header("Content-Encoding", "gzip")
                    header("X-Khronos", r.nowSec.toString())
                    header("X-Argus", java.util.Base64.getEncoder().encodeToString(
                        java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                            .putInt(r.nowSec.toInt()).array()
                    ))
                    header("X-Ladon", java.util.Base64.getEncoder().encodeToString(
                        ByteArray(4).also { java.security.SecureRandom().nextBytes(it) }
                    ))
                    header("X-SS-Req-Ticket", r.now.toString())
                    header("X-SS-STUB", HongguoCrypto.toHex(HongguoCrypto.md5(r.gzBody)))
                    header("X-Gorgon", HongguoCrypto.gorgon(rq, r.gzBody, r.nowSec.toInt(), HongguoCrypto.randomU32() and 0xFFFF))
                    post(r.gzBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
                }.build()
            http.newCall(reqE).execute().use { resp ->
                val b = resp.body?.bytes() ?: ByteArray(0)
                println(
                    "HG-APPLOG[E-app-pkg] http=${resp.code} len=${b.size} " +
                        if (b.isEmpty()) "" else String(b, Charsets.UTF_8).take(200)
                )
            }
        }.onFailure { println("HG-APPLOG[E-app-pkg] EXC ${it.message}") }
    }

    /**
     * 通道 5：reading.snssdk.com /service/settings/v3/ —— 真机 TNC 主通道深挖
     * （11-13 真机抓包实证形态：87 参设备 query + 全签名头，响应按 aid 下发
     * 红果专属 SDK 配置；此前 probeTnc 打的 is.snssdk.com 是字节通用配置且
     * 网络层四 key 全 MISS）。目标：确认我们身份能否拉到红果配置+key 全景。
     */
    @Test
    fun probeTncDeep() {
        val id = try {
            HongguoDeviceRegister.registerFresh()
        } catch (e: Exception) {
            println("HG-TNC register fail: ${e.message}"); return
        }
        Thread.sleep(800)
        val profile = HongguoVersionProfiles.default
        val now = System.currentTimeMillis()

        fun tncQuery(full: Boolean, caller: String?): java.util.LinkedHashMap<String, String> {
            val q = java.util.LinkedHashMap<String, String>()
            q["country"] = "CN"; q["channel"] = HongguoEnv.CHANNEL
            q["device_type"] = HongguoEnv.deviceType; q["language"] = "zh"
            q["update_version_code"] = profile.code
            q["tz_offset"] = "28800"
            q["ac"] = "wifi"; q["package"] = "com.phoenix.read"
            q["device_id"] = id.deviceId
            q["version_code"] = profile.code
            q["device_platform"] = "android"; q["region"] = "cn"; q["aid"] = "8662"
            q["rom_version"] = HongguoEnv.romVersion.substringBefore(' ')
            q["iid"] = id.installId
            q["caller_name"] = "PushSDK"
            q["os_api"] = HongguoEnv.osApi; q["os"] = "android"
            q["tz_name"] = "Asia/Shanghai"
            q["dpi"] = HongguoEnv.dpi
            q["os_version"] = HongguoEnv.osVersion
            q["app_name"] = "novelread"; q["version_name"] = profile.versionName
            q["device_brand"] = HongguoEnv.deviceBrand
            q["device_manufacturer"] = HongguoEnv.deviceBrand
            q["ssmix"] = "a"
            q["_rticket"] = now.toString()
            q["cdid"] = id.cdid
            q["manifest_version_code"] = profile.code
            q["resolution"] = HongguoEnv.resolution
            q["is_64_runtime"] = "true"; q["is_64_device"] = "true"
            q["host_abi"] = "arm64-v8a"
            q["dragon_device_type"] = "phone"
            q["compliance_status"] = "0"; q["need_personal_recommend"] = "1"
            q["player_so_load"] = "1"; q["is_android_pad_screen"] = "0"
            q["network_type"] = "4"; q["charging"] = "0"
            q["battery_pct"] = "88"; q["screen_brightness"] = "42"
            q["down_speed"] = "60000"
            if (id.klinkEgdi.isNotEmpty()) q["klink_egdi"] = id.klinkEgdi
            if (caller != null) q["caller_name"] = caller
            q["settings_time"] = "0"
            return q
        }

        fun shoot(tag: String, host: String, withSign: Boolean, caller: String? = "PushSDK", app: String? = null) {
            val q = tncQuery(true, caller)
            if (app != null) q["app"] = app
            val headers = if (withSign) {
                headerSet(q, ByteArray(0), id.deviceId)
            } else listOf(
                "User-Agent" to ua(profile.code),
                "Accept" to "application/json",
                "x-vc-bdturing-sdk-version" to "4.0.3.cn"
            )
            val url = "https://$host/service/settings/v3/?${HongguoCrypto.encodeQuery(q)}"
            val out = try {
                http.newCall(
                    Request.Builder().url(url).apply { headers.forEach { (k, v) -> addHeader(k, v) } }.build()
                ).execute().use { r ->
                    val b = r.body?.bytes() ?: ByteArray(0)
                    val text = if (b.isEmpty()) "" else String(b, Charsets.UTF_8)
                    val keys = runCatching {
                        org.json.JSONObject(text).optJSONObject("data")
                            ?.optJSONObject("settings")?.keys()?.asSequence()?.toList()
                    }.getOrNull()
                    // data 顶层字段（HGTV T3/V3 解析的网络配置在 data 直下而非 settings map）
                    runCatching {
                        val data = org.json.JSONObject(text).getJSONObject("data")
                        data.keys().asSequence().toList().forEach { k ->
                            println("HG-TNC-DEEP-DATA[$tag] $k = ${data.opt(k).toString().take(180)}")
                        }
                    }
                    if (keys != null) {
                        // 逐 key 打印（深挖全景）
                        val settings = org.json.JSONObject(text).getJSONObject("data").getJSONObject("settings")
                        keys.forEach { k ->
                            println("HG-TNC-DEEP-KEY[$tag] $k = ${settings.opt(k).toString().take(160)}")
                        }
                    }
                    "http=${r.code} len=${b.size} keys=${keys?.joinToString(",")?.take(200)}"
                }
            } catch (e: Exception) { "EXC ${e.message}" }
            println("HG-TNC-DEEP[$tag] $out")
        }

        shoot("A-reading-signed", "reading.snssdk.com", withSign = true)
        Thread.sleep(1200)
        shoot("B-reading-plain", "reading.snssdk.com", withSign = false)
        Thread.sleep(1200)
        shoot("C-is-signed", "is.snssdk.com", withSign = true)
        Thread.sleep(1200)
        // D/E：无 caller（HGTV 引擎通道形态）——网络层四字段按 T3.h 解析路径在 data 直下
        shoot("D-reading-nocaller", "reading.snssdk.com", withSign = true, caller = null)
        Thread.sleep(1200)
        shoot("E-is-nocaller", "is.snssdk.com", withSign = false, caller = null)
        Thread.sleep(1200)
        // F/G：app=1（HGTV D/O.java 引擎参数）——全量应用配置+data 直下网络字段
        shoot("F-reading-app1", "reading.snssdk.com", withSign = true, caller = null, app = "1")
        Thread.sleep(1200)
        shoot("G-is-app1", "is.snssdk.com", withSign = false, caller = null, app = "1")
    }

    /**
     * 通道 6：/reading/msgapi/syncAll/v —— 消息同步 HTTP 通道（klink 长连接的
     * 拉模式降级形态，真机抓包实证：body={"synclist":[{"count":0,"data_type":0,
     * "max_seq":LONG_MAX,"min_seq":<游标>}]}）。klink 私有 TCP 协议无素材
     * （HGTV 无实现/纯 Java 无 so，HTTP 抓包只有凭证随带），此通道是唯一可
     * 对齐的「设备在线+消息同步」形态。验证判据：code=0。
     */
    @Test
    fun probeSyncAll() {
        val id = try {
            HongguoDeviceRegister.registerFresh()
        } catch (e: Exception) {
            println("HG-SYNC register fail: ${e.message}"); return
        }
        Thread.sleep(800)
        val body = JSONObject()
            .put(
                "synclist",
                org.json.JSONArray().put(
                    JSONObject()
                        .put("count", 0).put("data_type", 0)
                        .put("max_seq", java.lang.Long.MAX_VALUE)
                        .put("min_seq", 0)
                )
            )
            .toString().toByteArray(Charsets.UTF_8)
        val q = fullQuery(id, HongguoVersionProfiles.default.code).apply {
            put("version_name", HongguoVersionProfiles.default.versionName)
        }
        val url = "https://api5-normal-sinfonlineb.fqnovel.com/reading/msgapi/syncAll/v?${HongguoCrypto.encodeQuery(q)}"
        val hs = headerSet(q, body, id.deviceId)
        val out = try {
            http.newCall(
                Request.Builder().url(url).apply {
                    hs.forEach { (k, v) -> addHeader(k, v) }
                    post(body.toRequestBody("application/json; charset=UTF-8".toMediaType()))
                }.build()
            ).execute().use { r ->
                val b = r.body?.bytes() ?: ByteArray(0)
                "http=${r.code} len=${b.size}" + if (b.isEmpty()) "" else " " + String(b, Charsets.UTF_8).take(260)
            }
        } catch (e: Exception) { "EXC ${e.message}" }
        println("HG-SYNC[min_seq=0] $out")
    }

    /**
     * 通道 12：landpage 推荐流 recommend_info 实证 —— recommend_stat/report 曝光回传
     * 的前置侦察。官方手机版 bookmall/cell/change 的 item 已实证内嵌 recommend_info
     * （原样回传即官方行为）；我们的 landpage 流 item 是否携带同款字段待证——
     * 有=可无缝回传；无=回传需自造 request_id（露馅风险，改走只回 group_id 的最小形态）。
     * dump 首个 video_data item 的 keys/content_type/series_id/recommend_info。
     */
    @Test
    fun probeLandpageReco() {
        val id = try {
            HongguoDeviceRegister.registerFresh()
        } catch (e: Exception) {
            println("HG-RECO register fail: ${e.message}"); return
        }
        Thread.sleep(800)
        val select = JSONObject()
        for (kind in listOf(
            "genre", "category_dim_theme", "category_dim_role", "category_dim_epoch",
            "sort", "gender", "online_time", "creation_status"
        )) {
            val arr = org.json.JSONArray()
            when (kind) {
                "genre" -> arr.put("short_play")
                "sort" -> arr.put("hot_score")
            }
            select.put(kind, arr)
        }
        val body = JSONObject()
            .put("filter_ids", "").put("req_scene", "default")
            .put("offset", 0).put("need_selector_panel", false)
            .put("limit", 15).put("select_items", select)
            .put("session_id", java.util.UUID.randomUUID().toString())
            .put("req_type", "only_content").put("client_req_type", 3)
            .toString().toByteArray(Charsets.UTF_8)
        val q = fullQuery(id, HongguoVersionProfiles.default.code).apply {
            put("version_name", HongguoVersionProfiles.default.versionName)
        }
        val url = "https://api5-normal-sinfonlineb.fqnovel.com/reading/distribution/category/landpage/v/?${HongguoCrypto.encodeQuery(q)}"
        val hs = headerSet(q, body, id.deviceId)
        val out = try {
            http.newCall(
                Request.Builder().url(url).apply {
                    hs.forEach { (k, v) -> addHeader(k, v) }
                    post(body.toRequestBody("application/json; charset=UTF-8".toMediaType()))
                }.build()
            ).execute().use { r ->
                val b = r.body?.bytes() ?: ByteArray(0)
                if (b.isEmpty()) "http=${r.code} len=0"
                else {
                    val sb = StringBuilder("http=${r.code} len=${b.size}")
                    val data = JSONObject(String(b, Charsets.UTF_8)).optJSONObject("data")
                    val rows = data?.optJSONArray("video_data") ?: org.json.JSONArray()
                    sb.append(" items=${rows.length()}")
                    rows.optJSONObject(0)?.let { first ->
                        sb.append(" keys=").append(first.keys().asSequence().toList())
                        sb.append(" ct=").append(first.opt("content_type"))
                        sb.append(" sid=").append(first.opt("series_id_str") ?: first.opt("series_id"))
                        sb.append(" reco=").append(first.opt("recommend_info")?.toString()?.take(240))
                    }
                    sb.toString()
                }
            }
        } catch (e: Exception) { "EXC ${e.message}" }
        println("HG-RECO[landpage] $out")
    }

    /**
     * 通道 13：recommend_stat/report/v1 曝光回传真网验证（官方行为足迹第 3 项）。
     * body 形态照抄官方抓包（scene=1+stat_data[]{client_time,content_type,group_id,
     * recommend_info,stat_item_type:1}，recommend_info 用官方样本原串）。一发即止。
     */
    @Test
    fun probeRecoReport() {
        val id = try {
            HongguoDeviceRegister.registerFresh()
        } catch (e: Exception) {
            println("HG-RECO-REPORT register fail: ${e.message}"); return
        }
        Thread.sleep(800)
        val body = JSONObject()
            .put("scene", 1)
            .put(
                "stat_data", org.json.JSONArray().put(
                    JSONObject()
                        .put("client_time", System.currentTimeMillis())
                        .put("content_type", 1004)
                        .put("group_id", "7705826348597529625")
                        .put(
                            "recommend_info",
                            "{\"channel_id\":\"94349618414\",\"request_id\":\"202611132102294CAEC965009A539D46A3\"," +
                                "\"rank\":\"1\",\"queue_names\":\"shortplay\",\"gid\":\"7705826348597529625\"}"
                        )
                        .put("stat_item_type", 1)
                )
            )
            .toString().toByteArray(Charsets.UTF_8)
        val q = fullQuery(id, HongguoVersionProfiles.default.code).apply {
            put("version_name", HongguoVersionProfiles.default.versionName)
        }
        val url = "https://api5-normal-sinfonlineb.fqnovel.com/reading/distribution/recommend_stat/report/v1/?${HongguoCrypto.encodeQuery(q)}"
        val hs = headerSet(q, body, id.deviceId)
        println("HG-RECO-REPORT[v1] " + send(url, hs, "POST", body))
    }

    /**
     * 通道 14：TLS 指纹取证（第三窗口定案后续：豁免=完整客户端形态，头号嫌疑
     * TLS/JA4 指纹——okhttp 与 Cronet 的 ClientHello 差异）。用 tls.peet.ws
     * 回显服务拿真实指纹：A=探针默认 OkHttpClient（JVM JSSE）；B=定制
     * ConnectionSpec（Cronet 同款 cipher 列表+顺序，模拟 Chrome 家族）。
     * 对照 JA4 前缀（Chrome 家族 t13d1516h2_*）判伪装成败。
     */
    @Test
    fun probeTlsEcho() {
        val echo = { c: OkHttpClient, tag: String ->
            try {
                c.newCall(Request.Builder().url("https://tls.peet.ws/api/all").build())
                    .execute().use { r ->
                        val t = r.body?.string() ?: ""
                        val ja3 = Regex("\"ja3\":\\s*\"([^\"]+)\"").find(t)?.groupValues?.get(1) ?: "?"
                        val ja4 = Regex("\"ja4\":\\s*\"([^\"]+)\"").find(t)?.groupValues?.get(1) ?: "?"
                        val tlsV = Regex("\"tls_version\":\\s*\"([^\"]+)\"").find(t)?.groupValues?.get(1) ?: "?"
                        val h2 = t.contains("\"http_version\": \"HTTP/2\"") || t.contains("\"http_version\":\"h2\"")
                        println("HG-TLS[$tag] http=${r.code} tls=$tlsV h2=$h2")
                        println("HG-TLS[$tag] ja3=$ja3")
                        println("HG-TLS[$tag] ja4=$ja4")
                    }
            } catch (e: Exception) {
                println("HG-TLS[$tag] EXC ${e.message}")
            }
        }
        echo(http, "A-default")
        // B：Cronet/Chrome 家族 cipher 列表（BoringSSL 默认启用顺序，TLS1.3 三件套
        // 在前+TLS1.2 主力套件），配合 TLS1.3+1.2 与 ALPN h2/http1.1
        val cronetSpec = okhttp3.ConnectionSpec.Builder(okhttp3.ConnectionSpec.MODERN_TLS)
            .tlsVersions(okhttp3.TlsVersion.TLS_1_3, okhttp3.TlsVersion.TLS_1_2)
            .cipherSuites(
                // TLS 1.3（JSSE 侧固定集合，顺序由引擎决定）
                okhttp3.CipherSuite.TLS_AES_128_GCM_SHA256,
                okhttp3.CipherSuite.TLS_AES_256_GCM_SHA384,
                okhttp3.CipherSuite.TLS_CHACHA20_POLY1305_SHA256,
                // TLS 1.2（BoringSSL 默认启用集，顺序对齐 Cronet）
                okhttp3.CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
                okhttp3.CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
                okhttp3.CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,
                okhttp3.CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
                okhttp3.CipherSuite.TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256,
                okhttp3.CipherSuite.TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256,
                okhttp3.CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA,
                okhttp3.CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA
            )
            .build()
        val httpB = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS)
            .connectionSpecs(listOf(cronetSpec))
            .build()
        echo(httpB, "B-cronet-cipher")
    }

    /**
     * 通道 15：边缘集群假设验证（2026-11-22 长窗口）。取证：HGTV 1.3.8.12 同机
     * 同 IP 能播，其 dns-v1.txt（TNC httpDns 直连表）用 api3/api5-normal-{lf,hl,lq}
     * + 运营商边缘 IP（124.227.185.x/103.52.103.x/182.242.45.x），**无 sinfonline**；
     * 我们走系统 DNS 解析 sinfonlineb→113.96.133.x（金山云公网入口）。
     * 假设：服务端按边缘集群下发清洗（公网入口集群全清，运营商边缘正常）。
     * A/B=lf 域名+固定边缘 IP 直连（okhttp Dns 覆写，SNI/Host 不变）；C=对照系统 DNS。
     */
    @Test
    fun probeEdgeIp() {
        val id = try {
            HongguoDeviceRegister.registerFresh()
        } catch (e: Exception) {
            println("HG-EDGE register fail: ${e.message}"); return
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

        fun run(tag: String, host: String, pinnedIp: String?) {
            val client = if (pinnedIp == null) http else OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS)
                .dns(object : okhttp3.Dns {
                    override fun lookup(hostname: String) =
                        java.net.InetAddress.getAllByName(pinnedIp).toList()
                })
                .build()
            val q = fullQuery(id, HongguoVersionProfiles.default.code).apply {
                put("version_name", HongguoVersionProfiles.default.versionName)
            }
            val url = "https://$host/novel/player/multi_video_model/preload/v1?${HongguoCrypto.encodeQuery(q)}"
            val hs = headerSet(q, gz, id.deviceId) + ("Content-Encoding" to "gzip")
            val out = try {
                client.newCall(
                    Request.Builder().url(url).apply {
                        hs.forEach { (k, v) -> addHeader(k, v) }
                        post(gz.toRequestBody("application/json; charset=UTF-8".toMediaType()))
                    }.build()
                ).execute().use { r -> "http=${r.code} len=${r.body?.bytes()?.size ?: 0}" }
            } catch (e: Exception) { "EXC ${e.message}" }
            println("HG-EDGE[$tag] $out")
            Thread.sleep(2000)
        }
        run("A-lf-edge-124.227.185.79", "api5-normal-lf.fqnovel.com", "124.227.185.79")
        run("B-lf-edge-103.52.103.203", "api5-normal-lf.fqnovel.com", "103.52.103.203")
        run("C-lf-system-dns", "api5-normal-lf.fqnovel.com", null)
        run("D-lf-edge-182.242.45.70", "api5-normal-lf.fqnovel.com", "182.242.45.70")
    }

    /**
     * 通道 7：HGTV 内置作者共享号 × 我们请求形态 —— 窗口期耐扛真因定案实验。
     * 逆向实锤：HGTV 1.3.7.1(M3)/1.3.8.6(X3) 两版硬编码同一对作者号
     * device_id=1905892595378490 / iid=1905892595382586（全部用户共用、
     * 注册失败/身份缺失时兜底顶上），画像 25053RT47C/Redmi/16/update_64/74332
     * 与我们完全同源（我们就是抄它的）。同窗口 HGTV 通我们空——若作者号配
     * 我们形态也通=耐扛真因是**号的信誉**（共享活号行为史深厚），形态无关。
     * 温和打法（活号，只打一发×2）。
     */
    @Test
    fun probeSharedIdentity() {
        val shared = HongguoDeviceRegister.Identity(
            deviceId = "1905892595378490",
            installId = "1905892595382586",
            cdid = "", openUdid = ""
        )
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

        // A：完全复刻 X3.f2060a 的 query（内置号绑定的画像=74332+25053RT47C）
        val qa = java.util.LinkedHashMap<String, String>()
        qa["iid"] = shared.installId; qa["device_id"] = shared.deviceId
        qa["ac"] = "wifi"; qa["channel"] = "update_64"; qa["aid"] = "8662"
        qa["app_name"] = "novelread"; qa["version_code"] = "74332"
        qa["version_name"] = "7.4.3.32"; qa["device_platform"] = "android"
        qa["os"] = "android"; qa["ssmix"] = "a"
        qa["device_type"] = "25053RT47C"; qa["device_brand"] = "Redmi"
        qa["language"] = "zh"; qa["os_api"] = "36"; qa["os_version"] = "16"
        qa["manifest_version_code"] = "74332"; qa["resolution"] = "1280*2772"
        qa["dpi"] = "520"; qa["update_version_code"] = "74332"
        qa["host_abi"] = "arm64-v8a"; qa["dragon_device_type"] = "phone"
        qa["pv_player"] = "74332"; qa["compliance_status"] = "0"
        qa["need_personal_recommend"] = "1"; qa["player_so_load"] = "1"
        qa["is_android_pad_screen"] = "0"
        val hsA = headerSet(qa, gz, shared.deviceId) + ("Content-Encoding" to "gzip")
        val urlA = "https://api5-normal-sinfonlineb.fqnovel.com/novel/player/multi_video_model/preload/v1?${HongguoCrypto.encodeQuery(qa)}"
        println("HG-SHARED[A-74332] " + send(urlA, hsA, "POST", gz))

        Thread.sleep(2500)

        // B：同号升 74532（现行 default profile）对照——排除版本变量
        val qb = java.util.LinkedHashMap(qa).apply {
            put("version_code", "74532"); put("version_name", "7.4.5.32")
            put("manifest_version_code", "74532"); put("update_version_code", "74532")
            put("pv_player", "74532")
        }
        val hsB = headerSet(qb, gz, shared.deviceId) + ("Content-Encoding" to "gzip")
        val urlB = "https://api5-normal-sinfonlineb.fqnovel.com/novel/player/multi_video_model/preload/v1?${HongguoCrypto.encodeQuery(qb)}"
        println("HG-SHARED[B-74532] " + send(urlB, hsB, "POST", gz))
    }

    /**
     * 通道 8：MuMu 上 HGTV 的活号 × 我们形态 —— 耐扛真因对照实验。
     * 2026-11-21 adb root 实测 MuMu：HGTV 注册成功用自己号 577656266427931
     * （非作者内置号），如实画像 HONOR SDY-AN00/Android15/35/1920*1080/280/
     * channel 69310258a，11-11 前后注册约 10 天龄。对照组=我们 MuMu 号
     * 1175790363888444（1175 段 16 位，号段不同）。同机同刻打 preload/v1，
     * 观察 code/len/响应特征差异。HGTV 号是活号，温和打法。
     */
    @Test
    fun probeHgtvLiveIdentity() {
        val hgtv = HongguoDeviceRegister.Identity(
            deviceId = "577656266427931",
            installId = "577656266432027",
            cdid = "b47c9f5a-0f74-4869-b156-00d77b87e589",
            openUdid = "b17430ac4c001686",
            klinkEgdi = "AALniwQczN8cJfrrFGTh7zkJ8mv3cS6GAz4GDWwhhsLu7Na4L18m0O6d",
            cookies = mapOf(
                "install_id" to "577656266432027",
                "store-region" to "cn-sc",
                "ttreq" to "1\$68d073a6ebeed170bff11453b19bbf879a9f0861",
                "store-region-src" to "did"
            )
        )
        val ours = HongguoDeviceRegister.Identity(
            deviceId = "1175790363888444",
            installId = "", cdid = "", openUdid = ""
        )
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

        /** HGTV 如实画像 query（channel 69310258a=HongguoEnv.CHANNEL 同款）。 */
        fun hgtvQuery(id: HongguoDeviceRegister.Identity, code: String, name: String): java.util.LinkedHashMap<String, String> {
            val q = java.util.LinkedHashMap<String, String>()
            q["device_platform"] = "android"; q["os"] = "android"; q["ssmix"] = "v"
            q["cdid"] = id.cdid; q["channel"] = "69310258a"
            q["aid"] = "8662"; q["app_name"] = "novelread"
            q["version_code"] = code; q["version_name"] = name
            q["manifest_version_code"] = code; q["update_version_code"] = code
            q["resolution"] = "1920*1080"; q["dpi"] = "280"
            q["device_type"] = "SDY-AN00"; q["device_brand"] = "HONOR"
            q["language"] = "zh"; q["os_api"] = "35"; q["os_version"] = "15"
            q["ac"] = "wifi"; q["host_abi"] = "arm64-v8a"
            q["dragon_device_type"] = "phone"; q["pv_player"] = code
            q["compliance_status"] = "0"; q["need_personal_recommend"] = "0"
            q["player_so_load"] = "0"; q["is_android_pad_screen"] = "1"
            q["rom_version"] = "V417IR release-keys"
            q["iid"] = id.installId; q["device_id"] = id.deviceId
            if (id.klinkEgdi.isNotEmpty()) q["klink_egdi"] = id.klinkEgdi
            return q
        }

        fun shoot(tag: String, id: HongguoDeviceRegister.Identity, code: String, name: String) {
            val q = hgtvQuery(id, code, name)
            val hs = headerSet(q, gz, id.deviceId) +
                ("Content-Encoding" to "gzip") +
                ("Cookie" to cookieHeader(id))
            val url = "https://api5-normal-sinfonlineb.fqnovel.com/novel/player/multi_video_model/preload/v1?${HongguoCrypto.encodeQuery(q)}"
            val out = try {
                http.newCall(
                    Request.Builder().url(url).apply {
                        hs.forEach { (k, v) -> addHeader(k, v) }
                        post(gz.toRequestBody("application/json; charset=UTF-8".toMediaType()))
                    }.build()
                ).execute().use { r ->
                    val b = r.body?.bytes() ?: ByteArray(0)
                    val t = if (b.isEmpty()) "" else String(b, Charsets.UTF_8)
                    val mainUrlLen = runCatching {
                        JSONObject(t).getJSONObject("data").getJSONObject(vid)
                            .getJSONArray("video_model").getJSONObject(0).optInt("definition", -1)
                    }.getOrDefault(-1)
                    "http=${r.code} len=${b.size} def=$mainUrlLen"
                }
            } catch (e: Exception) { "EXC ${e.message}" }
            println("HG-LIVE[$tag] $out")
        }

        shoot("A-hgtv-id-73932", hgtv, "73932", "7.3.9.32")
        Thread.sleep(2500)
        shoot("B-hgtv-id-74332", hgtv, "74332", "7.4.3.32")
        Thread.sleep(2500)
        // C：我们号×HGTV 画像（号变量单切：若 A/B 通 C 空=号问题；C 也通=画像问题被排除，纯号段）
        shoot("C-ours-id-hgtv-profile", ours.copy(installId = ours.deviceId), "73932", "7.3.9.32")
    }

    /**
     * 通道 9：信誉信号对比 —— 同刻同视频四路对照（不用等封禁窗口）：
     * HGTV MuMu 活号(10天自然使用)/我们 MuMu 号/全新注册号/作者共享号。
     * 记录 expire_time(播放授权时长)/video_model definition 档/响应长度差异
     * ——找「设备信誉分」的可观测代理指标；若某指标随号龄/行为史单调变化，
     * 养号效果就可以量化验收。
     */
    @Test
    fun probeReputationSignals() {
        val hgtv = HongguoDeviceRegister.Identity(
            deviceId = "577656266427931", installId = "577656266432027",
            cdid = "b47c9f5a-0f74-4869-b156-00d77b87e589",
            openUdid = "b17430ac4c001686",
            klinkEgdi = "AALniwQczN8cJfrrFGTh7zkJ8mv3cS6GAz4GDWwhhsLu7Na4L18m0O6d"
        )
        val ours = HongguoDeviceRegister.Identity(
            deviceId = "1175790363888444", installId = "1175790363888444", cdid = "", openUdid = ""
        )
        val author = HongguoDeviceRegister.Identity(
            deviceId = "1905892595378490", installId = "1905892595382586", cdid = "", openUdid = ""
        )
        val fresh = try {
            HongguoDeviceRegister.registerFresh()
        } catch (e: Exception) {
            println("HG-REP fresh register fail: ${e.message}"); null
        }

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

        fun shoot(tag: String, id: HongguoDeviceRegister.Identity) {
            // HGTV 号绑如实画像；其余绑现行 Redmi 画像（各自真实使用画像）
            val isHgtv = id.deviceId.startsWith("577")
            val q = if (isHgtv) java.util.LinkedHashMap<String, String>().apply {
                put("device_platform", "android"); put("os", "android"); put("ssmix", "v")
                put("cdid", id.cdid); put("channel", "69310258a")
                put("aid", "8662"); put("app_name", "novelread")
                put("version_code", "73932"); put("version_name", "7.3.9.32")
                put("manifest_version_code", "73932"); put("update_version_code", "73932")
                put("resolution", "1920*1080"); put("dpi", "280")
                put("device_type", "SDY-AN00"); put("device_brand", "HONOR")
                put("language", "zh"); put("os_api", "35"); put("os_version", "15")
                put("ac", "wifi"); put("host_abi", "arm64-v8a")
                put("dragon_device_type", "phone"); put("pv_player", "73932")
                put("compliance_status", "0"); put("need_personal_recommend", "0")
                put("player_so_load", "0"); put("is_android_pad_screen", "1")
                put("rom_version", "V417IR release-keys")
                put("iid", id.installId); put("device_id", id.deviceId)
                put("klink_egdi", id.klinkEgdi)
            } else fullQuery(id, "74532").apply { put("version_name", "7.4.5.32") }
            val hs = headerSet(q, gz, id.deviceId) + ("Content-Encoding" to "gzip")
            val url = "https://api5-normal-sinfonlineb.fqnovel.com/novel/player/multi_video_model/preload/v1?${HongguoCrypto.encodeQuery(q)}"
            val out = try {
                http.newCall(
                    Request.Builder().url(url).apply {
                        hs.forEach { (k, v) -> addHeader(k, v) }
                        post(gz.toRequestBody("application/json; charset=UTF-8".toMediaType()))
                    }.build()
                ).execute().use { r ->
                    val b = r.body?.bytes() ?: ByteArray(0)
                    val t = if (b.isEmpty()) "" else String(b, Charsets.UTF_8)
                    val entry = runCatching {
                        JSONObject(t).getJSONObject("data").getJSONObject(vid)
                    }.getOrNull()
                    val expire = entry?.optLong("expire_time", -1) ?: -1L
                    val nowSec = System.currentTimeMillis() / 1000
                    val ttlH = if (expire > 0) (expire - nowSec) / 3600 else -1
                    val defs = runCatching {
                        val models = org.json.JSONArray(entry!!.optJSONObject("video_model")?.toString() ?: "[]")
                        // video_model 是字符串化 JSON，取其内层数组
                        val inner = JSONObject(entry.optString("video_model"))
                        val arr2 = inner.optJSONArray("video_model") ?: org.json.JSONArray()
                        (0 until arr2.length()).mapNotNull { i ->
                            arr2.optJSONObject(i)?.optString("definition")?.takeIf { it.isNotEmpty() }
                        }
                    }.getOrDefault(emptyList<String>())
                    "http=${r.code} len=${b.size} ttl=${ttlH}h defs=$defs"
                }
            } catch (e: Exception) { "EXC ${e.message}" }
            println("HG-REP[$tag] $out")
        }

        shoot("hgtv-live", hgtv)
        Thread.sleep(2000)
        shoot("ours-mumu", ours)
        Thread.sleep(2000)
        fresh?.let { shoot("fresh", it) }
        Thread.sleep(2000)
        shoot("author", author)
    }

    /**
     * 通道 10：窗口触发机制实验（不等封禁，自己造小窗口）：
     * - B 裸新号直接 preload×N（间隔 3s）——单号中频能否触发该号清洗？
     * - A 另一新号先走「浏览流前置」（feed 一发+detail 一发，间隔 30s）再同款
     *   preload×N——行为前置能否提升耐扛？
     * 判读：都通=此频率不触发（窗口是全局策略，非号级行为反应）；B空A通=养号
     * 假设强化；都空=清洗是号级即时反应（可再测恢复时长）。
     * 温和约束：每号 ≤8 发，一次性新号，不碰 MuMu 老号/作者号/HGTV 号。
     */
    @Test
    fun probeWindowTrigger() {
        val idB = try {
            HongguoDeviceRegister.registerFresh()
        } catch (e: Exception) {
            println("HG-TRIG register B fail: ${e.message}"); return
        }
        Thread.sleep(800)
        val idA = try {
            HongguoDeviceRegister.registerFresh()
        } catch (e: Exception) {
            println("HG-TRIG register A fail: ${e.message}"); return
        }

        val vid = "7705826165692304409"
        fun preloadBody(): ByteArray {
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
            return bos.toByteArray()
        }

        fun shootPreload(tag: String, id: HongguoDeviceRegister.Identity): String {
            val gz = preloadBody()
            val q = fullQuery(id, "74532").apply { put("version_name", "7.4.5.32") }
            val hs = headerSet(q, gz, id.deviceId) + ("Content-Encoding" to "gzip")
            val url = "https://api5-normal-sinfonlineb.fqnovel.com/novel/player/multi_video_model/preload/v1?${HongguoCrypto.encodeQuery(q)}"
            val out = try {
                http.newCall(
                    Request.Builder().url(url).apply {
                        hs.forEach { (k, v) -> addHeader(k, v) }
                        post(gz.toRequestBody("application/json; charset=UTF-8".toMediaType()))
                    }.build()
                ).execute().use { r ->
                    val b = r.body?.bytes() ?: ByteArray(0)
                    val code = runCatching { JSONObject(String(b, Charsets.UTF_8)).optInt("code", 0) }.getOrDefault(0)
                    "http=${r.code} len=${b.size}" + if (code != 0) " srvcode=$code" else ""
                }
            } catch (e: Exception) { "EXC ${e.message}" }
            println("HG-TRIG[$tag] $out")
            return out
        }

        // A 号浏览流前置：feed 一发 + detail 一发（模拟正常用户进 App 先逛再播）
        runCatching {
            val fq = fullQuery(idA, "74532").apply { put("version_name", "7.4.5.32") }
            val feedUrl = "https://api5-normal-sinfonlineb.fqnovel.com/novel/feed/v?device_platform=android&aid=8662&version_code=74532"
            val feedOut = send(
                "https://api5-normal-sinfonlineb.fqnovel.com/luckycat_duanju/v1/djfeed/video/feed/v?${HongguoCrypto.encodeQuery(fq)}",
                headerSet(fq, ByteArray(0), idA.deviceId)
            )
            println("HG-TRIG[A-feed-pre] $feedOut")
        }
        Thread.sleep(30000)
        runCatching {
            val dq = fullQuery(idA, "74532").apply {
                put("version_name", "7.4.5.32")
                put("item_id", vid)
            }
            val dOut = send(
                "https://api5-normal-sinfonlineb.fqnovel.com/novel/bookapi/new_book_cell/v1/?${HongguoCrypto.encodeQuery(dq)}",
                headerSet(dq, ByteArray(0), idA.deviceId)
            )
            println("HG-TRIG[A-detail-pre] $dOut")
        }
        Thread.sleep(30000)

        // B 裸号×8 与 A 前置号×8 交错打（同刻对照）
        repeat(8) { n ->
            shootPreload("B-bare-$n", idB)
            shootPreload("A-fed-$n", idA)
            Thread.sleep(3000)
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

    /**
     * 通道 16（2026-11-22）：device_token query 参数 + Cookie 回传放行实验。
     * 背景：HGTV 1.3.8.12 卸载重装能播 + 它是纯 okhttp 栈（无网络 so，TLS 与我们同款）
     * + query 工厂 Z3.f2112a 与我们 fullQuery 逐字段同款 → 差异收敛到两个我们从没带过的
     * 字段：query 的 device_token（HGTV L3.java:250 签名时读取，每请求随带；开源参考线
     * 全丢弃）与 Cookie 头（registered-cookies 持久化 install_id/store-region/ttreq，
     * okhttp 无 CookieJar 从不回传）。四枪递进：
     * A=我们号+token+cookie；B=A 去 Cookie；C=A 去 token；D=HGTV 原装身份（MuMu 提取）。
     */
    @Test
    fun probeTokenCookie() {
        val id = try {
            HongguoDeviceRegister.registerFresh()
        } catch (e: Exception) {
            println("HG-TC register fail: ${e.message}"); return
        }
        Thread.sleep(800)
        println("HG-TC id=${id.deviceId} iid=${id.installId} cookies=${id.cookies} token=${id.deviceToken.take(16)}… egdi=${id.klinkEgdi.take(16)}…")

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

        // HGTV 原装身份（MuMu /data/data/com.hgtv.star/shared_prefs/native-session-v1.xml）
        val hgtvId = HongguoDeviceRegister.Identity(
            deviceId = "577656266427931", installId = "577656266432027",
            cdid = "79b944d0-5563-42cb-8782-e07738f19618",
            openUdid = "b17430ac4c001686",
            cookies = mapOf(
                "install_id" to "577656266432027", "store-region" to "cn-sc",
                "ttreq" to "1\$68d073a6ebeed170bff11453b19bbf879a9f0861",
                "store-region-src" to "did"
            ),
            klinkEgdi = "AAL2CI6sA_54XbvP4VGIroJlCEoIjPrdeYhgvMx7-1Z-Tg5fi1DYGYTT",
            deviceToken = "AAAWMH44GD5H64JLWSRK6U532XESTTPQEIS5CEIN3JSD6A3HUNCEFWDUIU42Z2A5UX6GYU4CCHYK3R4RVK7WF5ANYKKRKBVMSXLESJKJ5SY3GOC4B63TD5W7FZDO6C7PLHR3BLCS4JHVJSG36GFGUZI"
        )

        fun shoot(
            tag: String, who: HongguoDeviceRegister.Identity,
            withToken: Boolean, withCookie: Boolean
        ) {
            val q = fullQuery(who).apply {
                if (withToken && who.deviceToken.isNotEmpty()) put("device_token", who.deviceToken)
            }
            val signed = HongguoCrypto.signRequest(
                q, gz, System.currentTimeMillis(), who.deviceId, targetBranch = 0, version = "7.4.3.32"
            )
            val hs = headerSet(q, gz, who.deviceId).toMutableList().apply {
                if (withCookie) add("Cookie" to cookieHeader(who))
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
            println("HG-TC[$tag] $out")
            Thread.sleep(1500)
        }

        shoot("A-ours-token+cookie", id, withToken = true, withCookie = true)
        shoot("B-ours-cookie-only", id, withToken = false, withCookie = true)
        shoot("C-ours-token-only", id, withToken = true, withCookie = false)
        shoot("D-hgtv-full", hgtvId, withToken = true, withCookie = true)
        shoot("E-hgtv-no-extras", hgtvId, withToken = false, withCookie = false)
    }

    /**
     * 通道 17（2026-11-22）：TNC httpDns 入口 IP 直连实验（豁免机制定案）。
     * 背景：HGTV 同刻在 MuMu 能播（弹幕活），其 TNC 实时配置（字节官方 tnc 端点下发）
     * 给 sinfonlinea/b/c 独占入口 101.126.1.155~162 段，其运行连接正在该段上；
     * 而公网 DNS 把同域名解析到 222.210.37.x/125.64.109.x 段（我们一直在用的），
     * 200 空 body 清洗只挂公网入口段。四枪：
     * A=sinfonlineb→101.126.1.161；B=sinfonlinea→101.126.1.155（HGTV lastGoodHost）；
     * C=sinfonlineb→101.126.1.156；D=系统 DNS 对照（应空）。
     */
    @Test
    fun probeTncIp() {
        val id = try {
            HongguoDeviceRegister.registerFresh()
        } catch (e: Exception) {
            println("HG-TNC register fail: ${e.message}"); return
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

        fun shoot(tag: String, host: String, pinnedIp: String?) {
            val client = if (pinnedIp == null) http else OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS)
                .dns(object : okhttp3.Dns {
                    override fun lookup(hostname: String) =
                        java.net.InetAddress.getAllByName(pinnedIp).toList()
                })
                .build()
            val q = fullQuery(id)
            val signed = HongguoCrypto.signRequest(
                q, gz, System.currentTimeMillis(), id.deviceId, targetBranch = 0, version = "7.4.3.32"
            )
            val hs = headerSet(q, gz, id.deviceId) + ("Content-Encoding" to "gzip")
            val url = "https://$host/novel/player/multi_video_model/preload/v1?${signed.query}"
            val out = try {
                client.newCall(
                    Request.Builder().url(url).apply {
                        hs.forEach { (k, v) -> addHeader(k, v) }
                        post(gz.toRequestBody("application/json; charset=UTF-8".toMediaType()))
                    }.build()
                ).execute().use { r ->
                    val b = r.body?.bytes() ?: ByteArray(0)
                    "http=${r.code} len=${b.size}" + if (b.isEmpty()) "" else " " + String(b, Charsets.UTF_8).take(80)
                }
            } catch (e: Exception) { "EXC ${e.message}" }
            println("HG-TNC[$tag] $out")
            Thread.sleep(1500)
        }

        shoot("A-sinfonb-101.126.1.161", "api5-normal-sinfonlineb.fqnovel.com", "101.126.1.161")
        shoot("B-sinfona-101.126.1.155", "api5-normal-sinfonlinea.fqnovel.com", "101.126.1.155")
        shoot("C-sinfonb-101.126.1.156", "api5-normal-sinfonlineb.fqnovel.com", "101.126.1.156")
        shoot("D-system-dns-ctrl", "api5-normal-sinfonlineb.fqnovel.com", null)
    }

    /**
     * 通道 18（2026-11-22）：TNC settings 拉取复刻（动态 httpDns 跟随可行性）。
     * HGTV D.O：GET https://<tncHost>/service/settings/v3/?<query>（app=0 settings_time=0
     * _rticket=now + 标准 query），同款签名头管线，成功后按 tnc_update_interval（1h）刷新。
     * 关键差异：HGTV 用 tnc0 集群（tnc0-alisc1/tnc0-aliec2），此前探针 MISS 是打的 tnc3。
     * 成功判据：data.ttnet_http_dns_addr 出现 sinfonlinea/b/c → 101.126.1.x 映射。
     */
    @Test
    fun probeTncFetch() {
        val id = try {
            HongguoDeviceRegister.registerFresh()
        } catch (e: Exception) {
            println("HG-TNCF register fail: ${e.message}"); return
        }
        Thread.sleep(800)

        fun fetch(tag: String, host: String, pinnedIp: String?, withAuth: Boolean = false) {
            val client = if (pinnedIp == null) http else OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS)
                .dns(object : okhttp3.Dns {
                    override fun lookup(hostname: String) =
                        java.net.InetAddress.getAllByName(pinnedIp).toList()
                })
                .build()
            val q = fullQuery(id).apply {
                put("app", "0"); put("settings_time", "0")
                put("_rticket", System.currentTimeMillis().toString())
            }
            val empty = ByteArray(0)
            val signed = HongguoCrypto.signRequest(
                q, empty, System.currentTimeMillis(), id.deviceId, targetBranch = 0, version = "7.4.3.32"
            )
            val hs = headerSet(q, empty, id.deviceId).toMutableList().apply {
                if (withAuth) {
                    add("Cookie" to cookieHeader(id))
                    if (id.deviceToken.isNotEmpty()) add("x-tt-token" to id.deviceToken)
                }
            }
            val url = "https://$host/service/settings/v3/?${signed.query}"
            val out = try {
                client.newCall(
                    Request.Builder().url(url).apply {
                        hs.forEach { (k, v) -> addHeader(k, v) }
                        get()
                    }.build()
                ).execute().use { r ->
                    val b = r.body?.bytes() ?: ByteArray(0)
                    "http=${r.code} len=${b.size} " + when {
                        b.isEmpty() -> "EMPTY"
                        else -> {
                            val s = String(b, Charsets.UTF_8)
                            val keys = runCatching {
                                JSONObject(s).optJSONObject("data")
                                    ?.optJSONObject("settings")?.keySet()?.toList()
                            }.getOrNull().orEmpty()
                            val st = runCatching { JSONObject(s).optJSONObject("data")?.optJSONObject("settings") }.getOrNull()
                            val mdl = st?.optString("mdl_dns_config_v637").orEmpty()
                            "dns_key=${s.contains("ttnet_http_dns_addr")} " +
                                "101.126=${s.contains("101.126.")} nKeys=${keys.size} " +
                                "mdl_dns=${if (mdl.isEmpty()) "MISS" else mdl.take(700)}"
                        }
                    }
                }
            } catch (e: Exception) { "EXC ${e.message}" }
            println("HG-TNCF[$tag] $out")
            Thread.sleep(1500)
        }

        fetch("D-is-dns", "is.snssdk.com", null)
        fetch("E-is-auth", "is.snssdk.com", null, withAuth = true)
        fetch("F-tnc3-aliec2-auth", "tnc3-aliec2.zijieapi.com", null, withAuth = true)
    }

    /**
     * 通道 19（2026-11-22）：video_model/v1 单视频直取通道（HGTV 1.3.8.12 播放主通道）。
     * 逆向定案（AbstractC0172j1.P:153 + r0.T/U）：HGTV 播放主请求是
     * POST /novel/player/video_model/v1/，body={video_id,content_type,biz_param{…
     * from_video_id:"",source:4}}（gzip）——**不是**我们一直打的
     * multi_video_model/preload/v1（那是预加载通道）。窗口期同刻 HGTV 能播我们空，
     * 号/token/cookie/IP 段/TLS 全排除后 body+path 是最后一块没对拍的差异。
     */
    @Test
    fun probeVideoModel() {
        val id = try {
            HongguoDeviceRegister.registerFresh()
        } catch (e: Exception) {
            println("HG-VM register fail: ${e.message}"); return
        }
        Thread.sleep(800)
        val vid = "7705826165692304409"

        fun shoot(tag: String, host: String, level: Int = 4) {
            val body = JSONObject()
                .put("video_id", vid)
                .put("content_type", 1004)
                .put("biz_param", JSONObject()
                    .put("detail_page_version", 0).put("device_level", level)
                    .put("disable_digg_stat", false).put("disable_video_relate_book", false)
                    .put("from_video_id", "").put("need_all_video_definition", true)
                    .put("need_mp4_align", false).put("use_os_player", false)
                    .put("use_server_dns", true).put("use_server_dns_scene", "cold_start_stage")
                    .put("source", 4).put("video_platform", 1024))
                .toString().toByteArray(Charsets.UTF_8)
            val bos = ByteArrayOutputStream(); java.util.zip.GZIPOutputStream(bos).use { it.write(body) }
            val gz = bos.toByteArray()
            val q = fullQuery(id)
            val signed = HongguoCrypto.signRequest(
                q, gz, System.currentTimeMillis(), id.deviceId, targetBranch = 0, version = "7.4.3.32"
            )
            val hs = headerSet(q, gz, id.deviceId) + ("Content-Encoding" to "gzip")
            val url = "https://$host/novel/player/video_model/v1/?${signed.query}"
            val out = try {
                http.newCall(
                    Request.Builder().url(url).apply {
                        hs.forEach { (k, v) -> addHeader(k, v) }
                        post(gz.toRequestBody("application/json; charset=UTF-8".toMediaType()))
                    }.build()
                ).execute().use { r ->
                    val b = r.body?.bytes() ?: ByteArray(0)
                    "http=${r.code} len=${b.size}" + if (b.isEmpty()) "" else " " + String(b, Charsets.UTF_8).take(100)
                }
            } catch (e: Exception) { "EXC ${e.message}" }
            println("HG-VM[$tag] $out")
            Thread.sleep(1500)
        }

        shoot("A-sinfonb", "api5-normal-sinfonlineb.fqnovel.com")
        shoot("B-sinfona", "api5-normal-sinfonlinea.fqnovel.com")
        shoot("C-lf", "api5-normal-lf.fqnovel.com")
    }

    /**
     * 通道 20（2026-11-22）：frida 实抓 HGTV wire 请求的**原样重放**（终局定案）。
     * hook H0.m0.p（HGTV 全 API 执行入口）抓到的 K3 四元组（url/headers 保序/body），
     * 存 .zcode/hgtv-frida/reqs.json。已实证：原样重放=394KB 放行（同刻我们自构请求空）
     * → 豁免=请求内容。本探针四枪二分定位内容差异（签名外字段先行，避免重签名污染）：
     * A=去 Cookie；B=去 Content-Encoding；C=换我们 headerSet（重签名，判签名实现+头序）；
     * D=原样头+我们自构 body（重签名，判 body）。
     */
    @Test
    fun probeExactClone() {
        val reqsFile = java.io.File("G:\\MyTool\\MyBLBL\\.zcode\\hgtv-frida\\reqs.json")
        if (!reqsFile.exists()) { println("HG-CLONE reqs.json missing"); return }
        val reqs = org.json.JSONArray(reqsFile.readText())
        val play = (0 until reqs.length()).map { reqs.getJSONObject(it) }
            .firstOrNull { it.getString("url").contains("multi_video_model") }
            ?: run { println("HG-CLONE no play req"); return }

        val url = play.getString("url")
        val headers = play.getJSONObject("headers")
        val body = java.util.Base64.getDecoder().decode(play.getString("body_b64"))
        println("HG-CLONE base ${url.take(80)}… hdrs=${headers.length()} body=${body.size}")

        fun fire(tag: String, dropHeaders: Set<String> = emptySet()) {
            val hb = okhttp3.Headers.Builder()
            for (k in headers.keys()) if (k !in dropHeaders) hb.add(k, headers.getString(k))
            val req = Request.Builder().url(url).headers(hb.build())
                .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            val out = try {
                http.newCall(req).execute().use { r ->
                    val b = r.body?.bytes() ?: ByteArray(0)
                    "http=${r.code} len=${b.size}" + if (b.isEmpty()) "" else " code-hit"
                }
            } catch (e: Exception) { "EXC ${e.message}" }
            println("HG-CLONE[$tag] $out")
            Thread.sleep(1200)
        }

        fire("A-drop-cookie", dropHeaders = setOf("Cookie"))
        fire("B-drop-cenc", dropHeaders = setOf("Content-Encoding"))
        fire("C-drop-store-region", dropHeaders = setOf("x-tt-store-region"))

        // 第二轮：重签名二分（HongguoCrypto 与 HGTV 同源 V5 算法，签名覆盖 query+body+khronos）
        val qHgtv = java.util.LinkedHashMap<String, String>()
        url.substringAfter("?").split("&").forEach {
            val i = it.indexOf('='); if (i > 0) qHgtv[it.take(i)] = java.net.URLDecoder.decode(it.substring(i + 1), "UTF-8")
        }
        fun resignKeyes(
            tag: String, mutateQuery: (java.util.LinkedHashMap<String, String>) -> Unit = {},
            uaOverride: String? = null
        ) {
            val q = java.util.LinkedHashMap(qHgtv)
            mutateQuery(q)
            val now = System.currentTimeMillis()
            val signed = HongguoCrypto.signRequest(
                q, body, now, q["device_id"] ?: "", targetBranch = 0, version = "7.4.3.32"
            )
            val newTicket = signed.headers["X-SS-Req-Ticket"] ?: now.toString()
            val hb = okhttp3.Headers.Builder()
            for (k in headers.keys()) {
                when (k) {
                    "x-gorgon" -> hb.add(k, signed.headers["X-Gorgon"] ?: "")
                    "x-khronos" -> hb.add(k, signed.headers["X-Khronos"] ?: "")
                    "x-ss-req-ticket" -> hb.add(k, newTicket)
                    "x-reading-request" -> hb.add(k, "$newTicket-${java.security.SecureRandom().nextLong() and 0xffffffffL}")
                    "User-Agent" -> hb.add(k, uaOverride ?: headers.getString(k))
                    else -> hb.add(k, headers.getString(k))
                }
            }
            val newUrl = url.substringBefore("?") + "?" + signed.query
            val req = Request.Builder().url(newUrl).headers(hb.build())
                .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            val out = try {
                http.newCall(req).execute().use { r ->
                    val b = r.body?.bytes() ?: ByteArray(0)
                    "http=${r.code} len=${b.size}" + if (b.isEmpty()) "" else " hit"
                }
            } catch (e: Exception) { "EXC ${e.message}" }
            println("HG-CLONE[$tag] $out")
            Thread.sleep(1200)
        }

        // D：query 原样（SDY-AN00 全套）+ 我们重签名 → 验签名实现等价+头集足够
        resignKeyes("D-resign-asis")
        // E：画像组换回我们 Redmi 全套 → 判画像
        resignKeyes("E-redmi", mutateQuery = { q ->
            q["ssmix"] = "v"; q["channel"] = "update_64"
            q["resolution"] = "1280*2772"; q["dpi"] = "520"
            q["device_type"] = "25053RT47C"; q["device_brand"] = "Redmi"
            q["os_api"] = "36"; q["os_version"] = "16"
            q["need_personal_recommend"] = "0"; q["player_so_load"] = "0"
            q["is_android_pad_screen"] = "0"; q["rom_version"] = "BP2A.250605.031.A3"
        })
        // F：仅 UA 换 Redmi 版（query 保持 SDY-AN00）→ 判 UA 与画像的交叉校验
        resignKeyes("F-ua-redmi", uaOverride = ua())
        // D2：全套重算（gorgon/khronos/helios/medusa/argus/ladon 一套我们的，自洽）
        val q2 = java.util.LinkedHashMap(qHgtv)
        val now2 = System.currentTimeMillis()
        val signed2 = HongguoCrypto.signRequest(q2, body, now2, q2["device_id"] ?: "", targetBranch = 0, version = "7.4.3.32")
        val sec2 = signed2.headers["X-Khronos"]?.toIntOrNull() ?: (now2 / 1000).toInt()
        val ticket2 = signed2.headers["X-SS-Req-Ticket"] ?: now2.toString()
        val hb2 = okhttp3.Headers.Builder()
        for (k in headers.keys()) {
            when (k) {
                "x-gorgon" -> hb2.add(k, signed2.headers["X-Gorgon"] ?: "")
                "x-khronos" -> hb2.add(k, sec2.toString())
                "x-helios" -> hb2.add(k, HongguoArgus.helios(sec2))
                "x-medusa" -> hb2.add(k, HongguoPlaybackV5.medusaHeaderFor(signed2.query, body, sec2))
                "x-argus" -> hb2.add(k, java.util.Base64.getEncoder().encodeToString(
                    java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(sec2).array()))
                "x-ss-req-ticket" -> hb2.add(k, ticket2)
                "x-reading-request" -> hb2.add(k, "$ticket2-${java.security.SecureRandom().nextLong() and 0xffffffffL}")
                else -> hb2.add(k, headers.getString(k))
            }
        }
        val req2 = Request.Builder().url(url.substringBefore("?") + "?" + signed2.query).headers(hb2.build())
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        println("HG-CLONE[D2-full-resign] " + try {
            http.newCall(req2).execute().use { r -> "http=${r.code} len=${r.body?.bytes()?.size ?: 0}" }
        } catch (e: Exception) { "EXC ${e.message}" })
    }
}
