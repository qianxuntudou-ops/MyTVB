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
 * 2026-11-20 播放 200 空 body 风控排查探针：按 HGTV 1.3.8.6 逆向特征逐项对齐，
 * 找出决定性差异。真网测试，失败不挂断言只打日志。
 */
class HongguoPlayProbeTest {

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS)
        .build()

    private fun gzip(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(bos).use { it.write(data) }
        return bos.toByteArray()
    }

    private fun traceId(): String {
        val hex = java.util.UUID.randomUUID().toString().replace("-", "")
        return "00-$hex-${hex.substring(0, 16)}-01"
    }

    /** 轮次配置：body / host / query 模板 / UA / 附加头逐项叠加。 */
    private data class Round(
        val tag: String,
        val serverDns: Boolean,
        val host: String,
        val fullQuery: Boolean,
        val ua: String,
        val extraHeaders: Boolean
    )

    private fun body(vid: String, serverDns: Boolean): ByteArray {
        val biz = JSONObject()
            .put("detail_page_version", 0)
            .put("device_level", 4)
            .put("disable_digg_stat", false)
            .put("disable_video_relate_book", false)
            .put("from_video_id", "")
            .put("need_all_video_definition", true)
            .put("need_mp4_align", false)
            .put("use_os_player", false)
            .put("use_server_dns", serverDns)
        if (serverDns) biz.put("use_server_dns_scene", "cold_start_stage")
        biz.put("source", 4).put("video_platform", 3)
        return JSONObject()
            .put("video_id", vid)
            .put("content_type", 1004)
            .put("biz_param", biz)
            .toString().toByteArray(Charsets.UTF_8)
    }

    private fun query(
        id: HongguoDeviceRegister.Identity,
        full: Boolean
    ): List<Pair<String, String>> = if (!full) buildList {
        // 现行 app 模板（11-20 直取）
        add("aid" to "8662"); add("app_name" to "novelread")
        add("version_code" to "74332"); add("version_name" to "7.4.3.32")
        add("channel" to "vivo_8662_64"); add("device_platform" to "android")
        add("device_type" to "PGT-AN10"); add("os_version" to "12")
        add("update_version_code" to "74332")
        add("iid" to id.installId); add("cdid" to id.cdid)
        add("device_id" to id.deviceId)
        if (id.deviceToken.isNotEmpty()) add("device_token" to id.deviceToken)
        if (id.klinkEgdi.isNotEmpty()) add("klink_egdi" to id.klinkEgdi)
    } else buildList {
        // HGTV 1.3.8.6 f2303b1 白名单序（X3.c + M() 组装形态）
        add("device_platform" to "android"); add("os" to "android"); add("ssmix" to "v")
        add("cdid" to id.cdid); add("channel" to "update_64")
        add("aid" to "8662"); add("app_name" to "novelread")
        add("version_code" to "74332"); add("version_name" to "7.4.3.32")
        add("manifest_version_code" to "74332"); add("update_version_code" to "74332")
        add("resolution" to "1280*2772"); add("dpi" to "520")
        add("device_type" to "25053RT47C"); add("device_brand" to "Redmi")
        add("language" to "zh"); add("os_api" to "36"); add("os_version" to "16")
        add("ac" to "wifi"); add("host_abi" to "arm64-v8a")
        add("dragon_device_type" to "phone"); add("pv_player" to "74332")
        add("compliance_status" to "0"); add("need_personal_recommend" to "0")
        add("player_so_load" to "0"); add("is_android_pad_screen" to "0")
        add("rom_version" to "BP2A.250605.031.A3")
        add("iid" to id.installId); add("device_id" to id.deviceId)
        if (id.klinkEgdi.isNotEmpty()) add("klink_egdi" to id.klinkEgdi)
    }

    private fun shoot(r: Round, vid: String, id: HongguoDeviceRegister.Identity): String {
        val raw = body(vid, r.serverDns)
        val gz = gzip(raw)
        val signed = HongguoPlaybackV5.sign(query(id, r.fullQuery), System.currentTimeMillis(), gz)
        val url = "https://${r.host}/novel/player/video_model/v1/?${signed.query}"
        val headers = signed.headers + listOf(
            "User-Agent" to r.ua,
            "Accept" to "application/json",
            "Content-Encoding" to "gzip",
            "passport-sdk-version" to "5051452",
            "lc" to "101",
            "x-ss-dp" to "8662",
            "x-vc-bdturing-sdk-version" to "4.0.3.cn",
            "x-tt-trace-id" to traceId()
        ) + if (r.extraHeaders) listOf(
            // 1.3.8.6 J3.h 全头集补齐（无 argus/gorgon/ladon 实现，先验证非加密头）
            "x-tt-dt" to "",
            "sdk-version" to "2",
            "x-ss-req-ticket" to (System.currentTimeMillis().toString()),
            "x-tt-request-tag" to "t=0;n=0",
            "x-xs-from-web" to "0",
            "passport-sdk-version" to "6031190"
        ) else emptyList()
        val req = Request.Builder().url(url)
            .post(gz.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
            .build()
        http.newCall(req).execute().use { resp ->
            val bytes = resp.body?.bytes() ?: ByteArray(0)
            val head = if (bytes.isEmpty()) "" else String(bytes, Charsets.UTF_8).take(100)
            return "http=${resp.code} len=${bytes.size} $head"
        }
    }

    /** 老通道全头集（kotlinSignedPost 11-10 形态：Argus/Ladon/Gorgon 全家桶）。 */
    private fun argusPost(
        url: String, q: java.util.LinkedHashMap<String, String>, body: ByteArray,
        versionName: String, deviceId: String, ua: String
    ): String {
        val signed = HongguoCrypto.signRequest(
            q, body, System.currentTimeMillis(), deviceId, targetBranch = 0, version = versionName
        )
        val nowSec = signed.headers["X-Khronos"]?.toIntOrNull() ?: (System.currentTimeMillis() / 1000).toInt()
        val la = HongguoArgus.generate(signed.query, body, nowSec, versionName, deviceId, Triple("25053RT47C", "16", "update_64"))
        val ticket = signed.headers["X-SS-Req-Ticket"] ?: ""
        val headers = listOf(
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
            "x-ladon" to la.ladon,
            "x-argus" to la.argus,
            "x-gorgon" to (signed.headers["X-Gorgon"] ?: ""),
            "x-helios" to HongguoArgus.helios(nowSec),
            "Content-Type" to "application/json; charset=UTF-8",
            "x-vc-bdturing-sdk-version" to "4.0.3.cn",
            "x-reading-request" to "$ticket-${java.security.SecureRandom().nextLong() and 0xffffffffL}",
            "x-ss-stub" to (signed.headers["X-SS-STUB"] ?: ""),
            "lc" to "101",
            "x-ss-dp" to "8662",
            "x-tt-trace-id" to traceId()
        )
        val req = Request.Builder().url("$url?${signed.query}")
            .post(body.toRequestBody("application/json; charset=UTF-8".toMediaType()))
            .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
            .build()
        http.newCall(req).execute().use { resp ->
            val bytes = resp.body?.bytes() ?: ByteArray(0)
            val head = if (bytes.isEmpty()) "" else String(bytes, Charsets.UTF_8).take(80)
            return "http=${resp.code} len=${bytes.size} $head"
        }
    }

    /** 全参 query（K-M1 11-14 形态，Redmi 画像）。 */
    private fun argusQuery(
        id: HongguoDeviceRegister.Identity, code: String, name: String
    ): java.util.LinkedHashMap<String, String> {
        val q = java.util.LinkedHashMap<String, String>()
        q["device_id"] = id.deviceId
        q["iid"] = id.installId
        if (id.deviceToken.isNotEmpty()) q["device_token"] = id.deviceToken
        q["ac"] = "wifi"; q["channel"] = "update_64"; q["aid"] = "8662"
        q["app_name"] = "novelread"
        q["version_code"] = code; q["version_name"] = name
        q["device_platform"] = "android"; q["os"] = "android"; q["ssmix"] = "a"
        q["device_type"] = "25053RT47C"; q["device_brand"] = "Redmi"; q["language"] = "zh"
        q["os_api"] = "36"; q["os_version"] = "16"
        q["manifest_version_code"] = code
        q["resolution"] = "1280*2772"; q["dpi"] = "520"
        q["update_version_code"] = code; q["host_abi"] = "arm64-v8a"
        q["dragon_device_type"] = "phone"; q["pv_player"] = code
        q["compliance_status"] = "0"; q["need_personal_recommend"] = "1"
        q["player_so_load"] = "1"; q["is_android_pad_screen"] = "0"
        return q
    }

    @Test
    fun probeArgusMatrix() {
        val id = try {
            HongguoDeviceRegister.registerFresh()
        } catch (e: Exception) {
            println("HG-ARGUS register fail: ${e.message}")
            return
        }
        println("HG-ARGUS identity device=${id.deviceId}")
        Thread.sleep(800)
        val vid = "7701937936731737112"
        val m1Body = JSONObject()
            .put("biz_param", JSONObject()
                .put("detail_page_version", 0).put("device_level", 3)
                .put("disable_digg_stat", false).put("need_all_video_definition", true)
                .put("need_mp4_align", false).put("use_os_player", false)
                .put("use_server_dns", false).put("video_platform", 1024))
            .put("mixed_video_id_map", JSONObject().put("1004", org.json.JSONArray().put(vid)))
            .toString().toByteArray(Charsets.UTF_8)
        val preloadBody = JSONObject()
            .put("biz_param", JSONObject()
                .put("detail_page_version", 0).put("device_level", 1)
                .put("disable_digg_stat", false).put("disable_video_relate_book", false)
                .put("need_all_video_definition", true).put("need_mp4_align", false)
                .put("use_os_player", false).put("use_server_dns", false)
                .put("video_platform", 1024))
            .put("dr_scene", "preload")
            .put("mixed_video_id_map", JSONObject().put("1004", org.json.JSONArray().put(vid)))
            .toString().toByteArray(Charsets.UTF_8)
        val directBody = JSONObject()
            .put("video_id", vid).put("content_type", 1004)
            .put("biz_param", JSONObject()
                .put("detail_page_version", 0).put("device_level", 4)
                .put("disable_digg_stat", false).put("disable_video_relate_book", false)
                .put("from_video_id", "").put("need_all_video_definition", true)
                .put("need_mp4_align", false).put("use_os_player", false)
                .put("use_server_dns", true).put("use_server_dns_scene", "cold_start_stage")
                .put("source", 4).put("video_platform", 3))
            .toString().toByteArray(Charsets.UTF_8)
        val b = "https://api5-normal-sinfonlineb.fqnovel.com"
        val rounds = listOf(
            // G: K-M1 复现（11-14 通过组合）
            Triple("G-KM1-74532", "$b/novel/player/multi_video_model/v1/", "74532" to "7.4.5.32"),
            // H: 1.3.7.1 主通道（73932 静态档案 + preload）
            Triple("H-1371-73932", "$b/novel/player/multi_video_model/preload/v1", "73932" to "7.3.9.32"),
            // I: 1.3.8.6 直取端点 + 74332
            Triple("I-1386direct-74332", "$b/novel/player/video_model/v1/", "74332" to "7.4.3.32"),
            // J: K-M1 端点 + 73932
            Triple("J-M1-73932", "$b/novel/player/multi_video_model/v1/", "73932" to "7.3.9.32")
        )
        for ((tag, url, ver) in rounds) {
            val q = argusQuery(id, ver.first, ver.second)
            val body = when {
                url.contains("preload") -> preloadBody
                url.contains("video_model/v1/") -> directBody
                else -> m1Body
            }
            val ua = HongguoEnv.ua(ver.first)
            val out = try {
                argusPost(url, q, body, ver.second, id.deviceId, ua)
            } catch (e: Exception) {
                "EXC ${e.message}"
            }
            println("HG-ARGUS[$tag] $out")
            Thread.sleep(1200)
        }
    }

    @Test
    fun probeIsolate() {
        val id = try {
            HongguoDeviceRegister.registerFresh()
        } catch (e: Exception) {
            println("HG-ISO register fail: ${e.message}")
            return
        }
        println("HG-ISO identity device=${id.deviceId}")
        Thread.sleep(800)
        val vid = "7701937936731737112"
        val preloadBody = JSONObject()
            .put("biz_param", JSONObject()
                .put("detail_page_version", 0).put("device_level", 1)
                .put("disable_digg_stat", false).put("disable_video_relate_book", false)
                .put("need_all_video_definition", true).put("need_mp4_align", false)
                .put("use_os_player", false).put("use_server_dns", false)
                .put("video_platform", 1024))
            .put("dr_scene", "preload")
            .put("mixed_video_id_map", JSONObject().put("1004", org.json.JSONArray().put(vid)))
            .toString().toByteArray(Charsets.UTF_8)
        val b = "https://api5-normal-sinfonlineb.fqnovel.com"
        // K: H 组合重复×2（稳定性）+ 换 vid（泛化性）
        // L: preload 端点 + 74532（版本是否唯一关键）
        // M: video_model 直取 + 73932（端点是否唯一关键）
        for ((tag, combo) in listOf(
            "K1-repeat" to Triple("$b/novel/player/multi_video_model/preload/v1", "73932", "7.3.9.32"),
            "K2-repeat" to Triple("$b/novel/player/multi_video_model/preload/v1", "73932", "7.3.9.32"),
            "L-preload-74532" to Triple("$b/novel/player/multi_video_model/preload/v1", "74532", "7.4.5.32"),
            "M-direct-73932" to Triple("$b/novel/player/video_model/v1/", "73932", "7.3.9.32")
        )) {
            val (url, code, name) = combo
            val q = argusQuery(id, code, name)
            val out = try {
                argusPost(url, q, preloadBody, name, id.deviceId, HongguoEnv.ua(code))
            } catch (e: Exception) {
                "EXC ${e.message}"
            }
            println("HG-ISO[$tag] $out")
            Thread.sleep(1200)
        }
    }

    @Test
    fun probeMatrix() {
        val id = try {
            HongguoDeviceRegister.registerFresh()
        } catch (e: Exception) {
            println("HG-PROBE register fail: ${e.message}")
            return
        }
        println("HG-PROBE identity device=${id.deviceId}")
        Thread.sleep(800)
        val vid = "7701937936731737112"
        val rounds = listOf(
            Round("A-base", false, "api5-normal-sinfonlinea.fqnovel.com", false, HongguoPlaybackV5.PLAYBACK_UA, false),
            Round("B-dns", true, "api5-normal-sinfonlinea.fqnovel.com", false, HongguoPlaybackV5.PLAYBACK_UA, false),
            Round("C-hostb", true, "api5-normal-sinfonlineb.fqnovel.com", false, HongguoPlaybackV5.PLAYBACK_UA, false),
            Round("D-query", true, "api5-normal-sinfonlineb.fqnovel.com", true, HongguoPlaybackV5.PLAYBACK_UA, false),
            Round(
                "E-ua", true, "api5-normal-sinfonlineb.fqnovel.com", true,
                "com.phoenix.read/74332 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3; Cronet/TTNetVersion:8d40f833 2026-03-03 QuicVersion:21ac1950 2025-11-18)",
                false
            ),
            Round(
                "F-headers", true, "api5-normal-sinfonlineb.fqnovel.com", true,
                "com.phoenix.read/74332 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3; Cronet/TTNetVersion:8d40f833 2026-03-03 QuicVersion:21ac1950 2025-11-18)",
                true
            )
        )
        for (r in rounds) {
            val out = try {
                shoot(r, vid, id)
            } catch (e: Exception) {
                "EXC ${e.message}"
            }
            println("HG-PROBE[${r.tag}] $out")
            Thread.sleep(1200)
        }
    }
}
