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
internal class HongguoPlay(
    private val client: HongguoClient = HongguoClient()
) {

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
            .build()
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
    fun playUrl(vid: String, identity: HongguoDeviceRegister.Identity, allowBytevc2: Boolean = false): PlayUrl {
        require(vid.matches(Regex("\\d{1,30}"))) { "分集编号无效" }
        val payload = JSONObject()
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
        val entry = fetchModelEntry(vid, payload.toString().toByteArray(Charsets.UTF_8), identity)
        return parseEntry(vid, entry, allowBytevc2)
    }

    // ---- multi_video_model 签名 POST（空响应轮换重试，信封同 landpage） ----
    private fun fetchModelEntry(vid: String, raw: ByteArray, identity: HongguoDeviceRegister.Identity): JSONObject {
        var current = identity
        var lastError: Exception? = null
        for (attempt in 0 until 3) {
            try {
                val payload = postVideoModel(raw, current)
                val code = payload.optInt("Code", payload.optInt("code", 0))
                if (code != 0) {
                    throw PlayException("播放接口 code=$code", "BUSINESS_REJECTED")
                }
                val data = payload.optJSONObject("data") ?: JSONObject()
                val entry = data.optJSONObject(vid)
                    ?: throw PlayException("播放接口未返回所选分集", "MISSING_MODEL")
                return entry
            } catch (e: EmptyBodyException) {
                lastError = e
                if (attempt == 2) break
                // 空响应=设备身份被拒：换新注册身份重试（对照 Node getPlaybackDevice.reset）
                current = HongguoDeviceRegister.registerFresh()
                Thread.sleep(120)
            }
        }
        throw lastError ?: PlayException("播放接口无响应", "EMPTY_RESPONSE")
    }

    private class EmptyBodyException : IllegalStateException("empty body")

    private fun postVideoModel(raw: ByteArray, identity: HongguoDeviceRegister.Identity): JSONObject {
        val q = TreeMap<String, String>()
        q["ac"] = "wifi"; q["aid"] = "8662"; q["app_name"] = "novelread"
        q["channel"] = "update_64"; q["device_brand"] = "Redmi"
        q["device_id"] = identity.deviceId
        q["device_platform"] = "android"; q["device_type"] = "25053RT47C"
        q["dpi"] = "520"; q["iid"] = identity.installId
        q["cdid"] = identity.cdid
        q["openudid"] = identity.openUdid
        q["language"] = "zh"; q["manifest_version_code"] = "71332"
        q["os"] = "android"; q["os_api"] = "36"; q["os_version"] = "16"
        q["resolution"] = "1280*2772"; q["ssmix"] = "a"
        q["host_abi"] = "arm64-v8a"; q["dragon_device_type"] = "phone"
        q["pv_player"] = "71332"; q["compliance_status"] = "0"
        q["need_personal_recommend"] = "1"; q["player_so_load"] = "1"
        q["is_android_pad_screen"] = "0"
        q["update_version_code"] = "71332"; q["version_code"] = "71332"
        q["version_name"] = "7.1.3.32"

        val signed = HongguoCrypto.signRequest(q, raw, System.currentTimeMillis(), identity.deviceId, targetBranch = 0, version = "7.1.3.32")
        // 与签名 X-Khronos 严格同秒（跨秒边界会不一致）
        val nowSec = signed.headers["X-Khronos"]?.toIntOrNull() ?: (System.currentTimeMillis() / 1000).toInt()
        fun be32(n: Int): ByteArray = byteArrayOf(
            ((n shr 24) and 0xFF).toByte(), ((n shr 16) and 0xFF).toByte(),
            ((n shr 8) and 0xFF).toByte(), (n and 0xFF).toByte()
        )

        fun le32(n: Int): ByteArray = byteArrayOf(
            (n and 0xFF).toByte(), ((n shr 8) and 0xFF).toByte(),
            ((n shr 16) and 0xFF).toByte(), ((n shr 24) and 0xFF).toByte()
        )

        // 信封精确对齐 Node playbackRequest：仅这些头，无 Req-Ticket/Request-Tag/Store-Region
        val req = Request.Builder()
            .url("${HongguoPlayDebug.baseUrlOverride ?: "https://api5-normal-sinfonlineb.fqnovel.com"}/novel/player/multi_video_model/v1/?${signed.query}")
            .post(raw.toRequestBody("application/json; charset=UTF-8".toMediaType()))
            .header("User-Agent", PLAYBACK_UA)
            .header("Accept", "application/json; charset=utf-8,application/x-protobuf")
            .header("Accept-Encoding", "identity")
            .header("Sdk-Version", "2")
            .header("X-SS-DP", "8662")
            .header("X-Khronos", signed.headers["X-Khronos"] ?: "")
            .header("X-SS-Req-Ticket", signed.headers["X-SS-Req-Ticket"] ?: "")
            .header("X-SS-STUB", signed.headers["X-SS-STUB"] ?: "")
            .header("X-Gorgon", signed.headers["X-Gorgon"] ?: "")
            .header("X-Ladon", HongguoCrypto.base64(be32(nowSec)))
            .header("X-Argus", HongguoCrypto.base64(le32(nowSec)))
            .header("X-Helios", signed.headers["X-Helios"] ?: "")
            .header("X-Medusa", signed.headers["X-Medusa"] ?: "")
            .build()
        http.newCall(req).execute().use { resp ->
            val bytes = resp.body?.bytes() ?: ByteArray(0)
            if (bytes.isEmpty()) throw EmptyBodyException()
            return JSONObject(String(bytes, Charsets.UTF_8))
        }
    }

    private companion object {
        /** 播放接口专用 UA（对照 Node PLAYBACK_UA：71532 三星机型）。 */
        const val PLAYBACK_UA =
            "com.phoenix.read/71532 (Linux; U; Android 9; SM-N9860; Build/PQ3A.190705.10241111;tt-ok/3.12.13.20)"
    }

    /** 排障钩子：本地报文 dump/代理。 */
    object HongguoPlayDebug {
        var baseUrlOverride: String? = null
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

        val videoList = info.optJSONObject("video_list") ?: JSONObject()
        data class Candidate(
            val obj: JSONObject, val codec: String, val codecRank: Int,
            val quality: String, val width: Int, val height: Int, val pixels: Int
        )
        val candidates = ArrayList<Candidate>()
        for (key in videoList.keys()) {
            val item = videoList.optJSONObject(key) ?: continue
            if (item.optString("main_url").isEmpty() && item.optString("backup_url").isEmpty() &&
                item.optJSONArray("backup_url_list") == null
            ) continue
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
        val hasCompatible = candidates.any { it.codecRank < 2 }
        val rows = candidates.filter { it.codecRank < if (hasCompatible) 2 else 3 }
            .sortedWith(compareBy({ it.codecRank }, { -it.pixels }))
        if (rows.isEmpty()) {
            if (candidates.any { it.codec in listOf("bytevc2", "bvc2") } && !hasCompatible) {
                throw PlayException("本集使用 ByteVC2 编码，当前播放器暂不支持", "UNSUPPORTED_BYTEVC2")
            }
            throw PlayException("本集暂未提供兼容的 H.264/HEVC 片源", "MISSING_MODEL")
        }
        val best = rows.first()
        val spade = best.obj.optString("spade_a").ifEmpty {
            best.obj.optJSONObject("encrypt_info")?.optString("spade_a") ?: ""
        }
        val contentKey = if (spade.isNotEmpty()) deriveContentKey(spade) else null
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
