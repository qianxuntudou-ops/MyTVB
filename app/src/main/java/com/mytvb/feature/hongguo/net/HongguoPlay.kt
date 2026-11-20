package com.mytvb.feature.hongguo.net

import android.util.Base64
import com.mytvb.core.common.log.AppLog
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
     * @param identity 真注册设备身份（multi_video_model 校验设备真实性，
     * 匿名随机 id 会被空响应拒绝）。可用 [HongguoDeviceRegister.registerFresh] 获取后持久化复用。
     * @param preferredPixels 目标画质档位（竖屏剧取 min(width,height)，如 1080/720/540/480）；
     * 0 = 默认取最高档。目标档缺失时就近取不超目标的最高档，全超目标取最低档。
     */
    fun playUrl(
        vid: String,
        identity: HongguoDeviceRegister.Identity,
        preferredPixels: Int = 0,
    ): PlayUrl {
        require(vid.matches(Regex("\\d{1,30}"))) { "分集编号无效" }
        // 主通道=player/v1 + V5 播放协议（x-medusa/x-helios 签名，2026-11-17 接入；
        // 服务端 11-14 前后切 V5，旧 Argus/Ladon 头集只能拿到 200 空 body——
        // 沿革：11-13 弃用 73932/71332 档案时是 code=100001，11-14 起连 74532 也
        // 只回空 body，签名头集整体换血）。video_platform 首发 1024（V5 模板原值），
        // ByteVC2-only 时下方换 platform=3 重试（通用 h264/hevc）。
        val hgtvBody = playbackBody("player", listOf(vid))
        val entry = fetchModelEntry(
            vid,
            listOf(
                "/novel/player/multi_video_model/player/v1" to hgtvBody,
                "/novel/player/multi_video_model/v1/" to m1ModelBody(vid, 1024)
            ),
            identity
        )
        return try {
            parseEntry(vid, entry, preferredPixels)
        } catch (e: PlayException) {
            if (e.code != "UNSUPPORTED_BYTEVC2") throw e
            // 2026-11-10：服务端对新转码剧集仅下发 ByteVC2（内容维度必现，换号无效）。
            // 同端点换 video_platform=3 重发一次（1024 会拿到自研编码，3 请求通用
            // h264/hevc，实测有效）；首发仍 1024（V5 模板原值），重试失败不影响原报错。
            AppLog.w(TAG, "bytevc2-only; retry with video_platform=3 vid=$vid")
            val retry = runCatching {
                fetchModelEntry(
                    vid,
                    listOf(
                        "/novel/player/multi_video_model/player/v1" to playbackBody("player", listOf(vid), 3),
                        "/novel/player/multi_video_model/v1/" to m1ModelBody(vid, 3)
                    ),
                    identity
                )
            }.getOrElse { throw e }
            parseEntry(vid, retry, preferredPixels)
        }
    }

    /** player/v1 与 preload/v1 共用 body（V5 协议形态：device_level=1 /
     *  use_server_dns=false / video_platform=1024，PR#13 PlaybackV5Body；仅 dr_scene 不同）。 */
    private fun playbackBody(drScene: String, vids: List<String>, videoPlatform: Int = 1024): ByteArray = JSONObject()
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
                .put("video_platform", videoPlatform)
        )
        .put("dr_scene", drScene)
        .put(
            "mixed_video_id_map",
            JSONObject().put("1004", org.json.JSONArray().put(vids[0]).also { arr ->
                for (i in 1 until vids.size) arr.put(vids[i])
            })
        )
        .toString().toByteArray(Charsets.UTF_8)

    /**
     * 批量地址预取（官方 `multi_video_model/preload/v1`，dr_scene="preload"）：
     * 一次请求带多集 vid（官方 App 在播放页/剧场页即批量预取，2026-11-13 抓包 14 次）。
     * 响应 data 按 vid 分 key、每集 entry 与 player/v1 同构（parseEntry 直接复用）。
     * 尽力而为语义：任一集解析失败只跳过该集，不整体抛；全部失败抛给调用方记日志。
     * 地址有效期约 6h，调用方缓存 TTL 须留余量（Repository 5h）。
     */
    fun preloadEntries(
        vids: List<String>,
        identity: HongguoDeviceRegister.Identity,
        preferredPixels: Int = 0
    ): Map<String, PlayUrl> {
        require(vids.isNotEmpty()) { "预取分集为空" }
        vids.forEach { require(it.matches(Regex("\\d{1,30}"))) { "分集编号无效" } }
        val body = playbackBody("preload", vids)
        var lastError: Exception? = null
        for (round in 0 until 2) {
            val host = PLAY_HOSTS[round % PLAY_HOSTS.size]
            try {
                val payload = postVideoModelV5(
                    "/novel/player/multi_video_model/preload/v1", body, identity, hostOverride = host
                )
                val code = payload.optInt("Code", payload.optInt("code", 0))
                if (code != 0) {
                    throw PlayException("预取接口 code=$code", "BUSINESS_REJECTED")
                }
                val data0 = payload.optJSONObject("data") ?: JSONObject()
                // V5 分组响应：data["1004"][vid]（与 player/v1 同构）
                val data = data0.optJSONObject("1004") ?: data0
                val out = LinkedHashMap<String, PlayUrl>()
                for (vid in vids) {
                    val entry = data.optJSONObject(vid) ?: continue
                    runCatching { out[vid] = parseEntry(vid, entry, preferredPixels) }
                }
                if (out.isNotEmpty()) return out
                lastError = PlayException("预取接口未返回任何分集", "MISSING_MODEL")
            } catch (e: EmptyBodyException) {
                lastError = e
            } catch (e: PlayException) {
                lastError = e
            }
        }
        throw lastError ?: EmptyBodyException()
    }

    /** K-M1 body（multi/v1，Node playbackRequest 验证版）。video_platform：1024 会
     *  拿到 ByteVC2 自研编码，3 请求通用 h264/hevc（与 player/v1 hgtvBody 同参数）。 */
    private fun m1ModelBody(vid: String, videoPlatform: Int): ByteArray = JSONObject()
        .put(
            "biz_param", JSONObject()
                .put("detail_page_version", 0)
                .put("device_level", 3)
                .put("disable_digg_stat", false)
                .put("need_all_video_definition", true)
                .put("need_mp4_align", false)
                .put("use_os_player", false)
                .put("use_server_dns", false)
                .put("video_platform", videoPlatform)
        )
        .put("mixed_video_id_map", JSONObject().put("1004", org.json.JSONArray().put(vid)))
        .toString().toByteArray(Charsets.UTF_8)

    // ---- 播放签名 POST：HGTV 号模型（持久化复用+空响应自愈），host 集群轮询 ----
    private fun fetchModelEntry(
        vid: String,
        endpoints: List<Pair<String, ByteArray>>,
        identity: HongguoDeviceRegister.Identity
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
            // 每轮=新 host（host 集群轮询），号不变，V5 签名（postVideoModelV5）
            // + JVM okhttp 传输。
            val host = PLAY_HOSTS[round % PLAY_HOSTS.size]
            try {
                AppLog.d(
                    TAG, "play round=$round host=$host device=${current.deviceId} " +
                        "path=${hgtvEndpoint.first} vid=$vid via=okhttp"
                )
                val payload = postVideoModelV5(hgtvEndpoint.first, hgtvEndpoint.second, current, hostOverride = host)
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
        // 兜底①：M1 端点（sinfonlineb multi/v1 + 74532 档案 + 明文 body）——
        // player/v1 收紧后的实际可用端点（HGTV 本尊 fallback_api 二跳同路径）。
        // 沿用当前号；空响应再自愈一次重发（两发保 K-M1 在两种环境下都首发可过）。
        val m1Body = endpoints.last { it.first.contains("multi_video_model/v1/") }.second
        runCatching { return fetchM1Entry(vid, m1Body, current, allowRotate = true) }
            .onFailure { AppLog.w(TAG, "M1 fallback fail: ${it.message}") }
        throw lastError ?: PlayException("播放接口无响应", "EMPTY_RESPONSE")
    }

    /**
     * M1 兜底请求：sinfonlineb multi/v1 + 74532 档案 + 明文 body，空响应换号重发一次。
     * bytevc2-only 重试传 allowRotate=false（编码与号无关，换号不改变下发编码集）。
     * （2026-11-14：71332 与 73932 同批被服务端弃用——player/v1 全量 code=100001，
     *  单变量实验 74532 通过；版本档案全链对齐官方 App 7.4.5.32。）
     */
    private fun fetchM1Entry(
        vid: String,
        body: ByteArray,
        startIdentity: HongguoDeviceRegister.Identity,
        allowRotate: Boolean
    ): JSONObject {
        var current = startIdentity
        val m1Url = "https://api5-normal-sinfonlineb.fqnovel.com/novel/player/multi_video_model/v1/"
        val ver = HongguoVersionProfiles.default
        for (m1Attempt in 0 until 2) {
            if (m1Attempt == 1 && allowRotate) current = runCatching { rotateIdentity() }.getOrElse { current }
            val m1q = java.util.LinkedHashMap<String, String>()
            m1q["device_id"] = current.deviceId
            m1q["iid"] = current.installId
            if (current.deviceToken.isNotEmpty()) m1q["device_token"] = current.deviceToken
            m1q["ac"] = "wifi"; m1q["channel"] = "update_64"; m1q["aid"] = "8662"
            m1q["app_name"] = "novelread"
            m1q["version_code"] = ver.code; m1q["version_name"] = ver.versionName
            m1q["device_platform"] = "android"; m1q["os"] = "android"; m1q["ssmix"] = "a"
            m1q["device_type"] = "25053RT47C"; m1q["device_brand"] = "Redmi"; m1q["language"] = "zh"
            m1q["os_api"] = "36"; m1q["os_version"] = "16"
            m1q["manifest_version_code"] = ver.code
            m1q["resolution"] = "1280*2772"; m1q["dpi"] = "520"
            m1q["update_version_code"] = ver.code; m1q["host_abi"] = "arm64-v8a"
            m1q["dragon_device_type"] = "phone"; m1q["pv_player"] = ver.code
            m1q["compliance_status"] = "0"; m1q["need_personal_recommend"] = "1"
            m1q["player_so_load"] = "1"; m1q["is_android_pad_screen"] = "0"
            AppLog.d(TAG, "play fallback K-M1 vid=$vid attempt=$m1Attempt device=${current.deviceId}")
            val text = kotlinSignedPost(m1Url, m1q, body, ver.versionName, current.deviceId,
                Triple("25053RT47C", "16", "update_64"), HongguoEnv.ua(ver.code), gzipHeader = false)
            if (text.isNotEmpty()) return extractEntry(vid, JSONObject(text))
        }
        throw EmptyBodyException()
    }

    private fun extractEntry(vid: String, payload: JSONObject): JSONObject {
        val code = payload.optInt("Code", payload.optInt("code", 0))
        if (code != 0) {
            val msg = payload.optString("message", payload.optString("Message", ""))
            AppLog.w(TAG, "play biz code=$code msg=$msg")
            throw PlayException("播放接口 code=$code${if (msg.isNotEmpty()) "：$msg" else ""}", "BUSINESS_REJECTED")
        }
        val data0 = payload.optJSONObject("data") ?: JSONObject()
        // V5 分组响应（2026-11-17 服务端切换）：data["1004"][vid].video_model
        val data = data0.optJSONObject("1004") ?: data0
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

    }

    /** player/v1 通道（V5 协议，2026-11-17 接入）：gzip body 同字节签名发送 +
     *  74332 手机端模板 query（保序、无 ts）+ x-medusa/x-helios 独立播放签名。
     *  旧 Argus/Ladon 头集已被服务端切协议弃用（HTTP 200 空 body）。 */
    private fun postVideoModelV5(
        path: String, raw: ByteArray, identity: HongguoDeviceRegister.Identity,
        hostOverride: String? = null
    ): JSONObject {
        val body = gzipDeflate(raw)
        // V5 查询模板（PR#13 PlaybackV5Pairs：顺序参与签名；签名时追加 _rticket 挑分支）
        val pairs = buildList {
            add("aid" to "8662"); add("app_name" to "novelread")
            add("version_code" to "74332"); add("version_name" to "7.4.3.32")
            add("channel" to "vivo_8662_64"); add("device_platform" to "android")
            add("device_type" to "PGT-AN10"); add("os_version" to "12")
            add("update_version_code" to "74332")
            add("iid" to identity.installId); add("cdid" to identity.cdid)
            add("device_id" to identity.deviceId)
            // 真注册号带 device_token（注册响应字段；匿名号无此值，PR 模板不含）
            if (identity.deviceToken.isNotEmpty()) add("device_token" to identity.deviceToken)
            if (identity.klinkEgdi.isNotEmpty()) add("klink_egdi" to identity.klinkEgdi)
        }
        val signed = HongguoPlaybackV5.sign(pairs, System.currentTimeMillis(), body)
        val host = hostOverride ?: PLAY_HOSTS.first()
        val url = "$host$path?${signed.query}"
        val headers = signed.headers + listOf(
            "User-Agent" to HongguoPlaybackV5.PLAYBACK_UA,
            "Accept" to "application/json",
            "Content-Encoding" to "gzip"
        )
        val req = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
            .build()
        http.newCall(req).execute().use { resp ->
            val bytes = resp.body?.bytes() ?: ByteArray(0)
            AppLog.d(
                TAG, "play V5 resp http=${resp.code} len=${bytes.size} " +
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
            AppLog.d(
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
        false -> 2  // bytevc2 自研编码不支持（占位保序）
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
