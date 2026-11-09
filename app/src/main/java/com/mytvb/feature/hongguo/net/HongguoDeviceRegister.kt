package com.mytvb.feature.hongguo.net

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.SecureRandom
import java.util.TreeMap
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 红果匿名设备注册：对照自研 Go 版 passport_device.go 翻译。
 * 注册绝不携带手机号/登录码/账号票据（匿名引导）。
 *
 * 隔离约束：本类使用自建 OkHttpClient，不经过 B 站 NetworkManager；
 * 成功身份写入 HongguoSession（独立 SP）。
 */
internal object HongguoDeviceRegister {

    private const val DEVICE_API = "https://log.snssdk.com"
    private const val APP_VERSION = "7.3.5.32"
    internal const val USER_AGENT =
        "com.phoenix.read/73532 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)"
    private val KDF_SALT = HongguoCrypto.hexToBytes(
        "4dd4c2e6b83162090e52b3c7a6733ba41cb2462b829ab58a196b39db57177524f49baf7f08e8d68d26a72e37c1a95a2f1f05a51892aef2949732b62a38aadd58"
    )

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .build()
    }

    fun ensureRegistered(session: HongguoSession) {
        if (session.registered) return
        if (session.cdid.isEmpty()) {
            val rnd = SecureRandom()
            session.cdid = uuidV4(rnd)
            session.clientUdid = uuidV4(rnd)
            val open = ByteArray(8)
            rnd.nextBytes(open)
            session.openUdid = HongguoCrypto.toHex(open)
            session.deviceCreated = System.currentTimeMillis()
        }
        registerDevice(session)
    }

    /** 真注册身份（multi_video_model 播放接口要求，不吃一次性随机值）。 */
    data class Identity(
        val deviceId: String,
        val installId: String,
        val cdid: String,
        val openUdid: String,
        val cookies: Map<String, String> = emptyMap()
    )

    /**
     * 注册内核（不落盘）：调用方负责持久化。多视频模型等播放接口
     * 校验真实设备身份，匿名随机 id 会被空响应拒绝（Node/Go 版同行为）。
     */
    fun registerFresh(): Identity {
        val rnd = SecureRandom()
        val cdid = uuidV4(rnd)
        val open = ByteArray(8)
        rnd.nextBytes(open)
        val openUdid = HongguoCrypto.toHex(open)
        val holder = InMemorySession(cdid, uuidV4(rnd), openUdid)
        registerDevice(holder)
        return Identity(holder.deviceId, holder.installId, cdid, openUdid, holder.cookies)
    }

    /** 注册流程复用的最小会话视图（不依赖 Android Context）。 */
    private class InMemorySession(
        override var cdid: String,
        override var clientUdid: String,
        override var openUdid: String
    ) : SessionView {
        override var deviceId: String = ""
        override var installId: String = ""
        override var deviceCreated: Long = System.currentTimeMillis()
        override var cookies: MutableMap<String, String> = mutableMapOf()
    }

    /** registerDevice 只依赖这组读写点（HongguoSession 与 InMemorySession 各自实现）。 */
    interface SessionView {
        var deviceId: String
        var installId: String
        var cdid: String
        var openUdid: String
        var clientUdid: String
        var deviceCreated: Long
        var cookies: MutableMap<String, String>
    }

    private fun uuidV4(rnd: SecureRandom): String {
        val b = ByteArray(16)
        rnd.nextBytes(b)
        b[6] = ((b[6].toInt() and 0x0F) or 0x40).toByte()
        b[8] = ((b[8].toInt() and 0x3F) or 0x80).toByte()
        val hex = HongguoCrypto.toHex(b)
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
            "${hex.substring(16, 20)}-${hex.substring(20, 32)}"
    }

    private fun deviceKey(seed: ByteArray): ByteArray {
        val h = HongguoCrypto.sha512(seed)
        return HongguoCrypto.sha512(h + KDF_SALT)
    }

    /** TTEncrypt V5：对照 passportTTEncrypt。 */
    private fun ttEncrypt(plain: ByteArray, seed: ByteArray): ByteArray {
        require(seed.size == 32) { "seed invalid" }
        val z = HongguoGzip.compress(plain)
        val hash = HongguoCrypto.sha512(z)
        var p = hash + z
        val padding = 16 - p.size % 16
        p += ByteArray(padding) { padding.toByte() }
        val key = deviceKey(seed)
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.copyOfRange(0, 16), "AES"), IvParameterSpec(key.copyOfRange(16, 32)))
        val enc = cipher.doFinal(p)
        return byteArrayOf(0x74, 0x63, 5, 0x10, 0, 0) + seed + enc
    }

    /** TTDecrypt：对照 passportTTDecrypt。 */
    private fun ttDecrypt(b: ByteArray): ByteArray {
        require(b.size >= 38 + 80 && (b.size - 38) % 16 == 0) { "encrypted response invalid" }
        require(b[0] == 0x74.toByte() && b[1] == 0x63.toByte() && b[2] == 5.toByte()) { "bad magic" }
        val seed = b.copyOfRange(6, 38)
        val key = deviceKey(seed)
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key.copyOfRange(0, 16), "AES"), IvParameterSpec(key.copyOfRange(16, 32)))
        var p = cipher.doFinal(b.copyOfRange(38, b.size))
        val padding = p[p.size - 1].toInt() and 0xFF
        require(padding in 1..16) { "padding invalid" }
        p = p.copyOfRange(0, p.size - padding)
        require(p.size >= 64) { "payload too short" }
        val hash = HongguoCrypto.sha512(p.copyOfRange(64, p.size))
        require(hash.contentEquals(p.copyOfRange(0, 64))) { "integrity check failed" }
        return HongguoGzip.decompress(p.copyOfRange(64, p.size))
    }

    private fun payload(session: SessionView, stamp: Long): ByteArray {
        val header = JSONObject()
        header.put("aid", 8662)
        header.put("access", "wifi")
        header.put("app_version", APP_VERSION)
        header.put("app_version_minor", "")
        header.put("channel", "update_64")
        header.put("device_brand", "Redmi")
        header.put("device_manufacturer", "Redmi")
        header.put("device_model", "25053RT47C")
        header.put("device_category", "phone")
        header.put("device_platform", "android")
        header.put("os", "Android")
        header.put("os_api", 36)
        header.put("os_version", "16")
        header.put("density_dpi", 520)
        header.put("display_density", "xxxhdpi")
        header.put("resolution", "2772x1280")
        header.put("language", "zh")
        header.put("timezone", 8)
        header.put("tz_name", "Asia/Shanghai")
        header.put("tz_offset", 28800)
        header.put("region", "CN")
        header.put("package", "com.phoenix.read")
        header.put("version_code", 73532)
        header.put("manifest_version_code", 73532)
        header.put("update_version_code", 73532)
        header.put("apk_first_install_time", session.deviceCreated)
        header.put("cdid", session.cdid)
        header.put("clientudid", session.clientUdid)
        header.put("openudid", session.openUdid)
        header.put("cpu_abi", "arm64-v8a")
        header.put("display_name", "红果短剧")
        header.put("sdk_version", "3.7.2")
        header.put("sdk_target_version", 29)
        header.put("sdk_flavor", "china")
        header.put("guest_mode", 0)
        header.put("is_system_app", 0)
        header.put("not_request_sender", 0)
        header.put("oaid_may_support", true)
        header.put("ipv6_list", JSONArray0())
        header.put("sim_serial_number", JSONArray0())
        header.put("custom", JSONObject().apply {
            put("is_android_fold", 0)
            put("filter_warn", 0)
            put("web_ua", "")
            put("is_android_pad", 0)
            put("client_ipv4", "")
        })
        val root = JSONObject()
        root.put("magic_tag", "ss_app_log")
        root.put("header", header)
        root.put("_gen_time", stamp)
        return root.toString().toByteArray(Charsets.UTF_8)
    }

    private fun JSONArray0(): org.json.JSONArray = org.json.JSONArray()

    private fun registerDevice(session: SessionView) {
        val stamp = System.currentTimeMillis()
        val nowSec = stamp / 1000
        val seed = ByteArray(32)
        SecureRandom().nextBytes(seed)
        val body = ttEncrypt(payload(session, stamp), seed)

        val q = TreeMap<String, String>()
        q["ac"] = "wifi"; q["aid"] = "8662"; q["app_name"] = "novelread"
        q["channel"] = "update_64"; q["device_brand"] = "Redmi"
        q["device_platform"] = "android"; q["device_type"] = "25053RT47C"
        q["dpi"] = "520"; q["language"] = "zh"
        q["manifest_version_code"] = "73532"; q["os"] = "android"
        q["os_api"] = "36"; q["os_version"] = "16"
        q["resolution"] = "1280*2772"; q["ssmix"] = "a"
        q["update_version_code"] = "73532"; q["version_code"] = "73532"
        q["version_name"] = APP_VERSION
        q["cdid"] = session.cdid
        q["openudid"] = session.openUdid
        q["tt_data"] = "a"
        q["ts"] = nowSec.toString()
        q["_rticket"] = stamp.toString()
        q["package"] = "com.phoenix.read"
        val rawQuery = HongguoCrypto.encodeQuery(q)

        val nonce = HongguoCrypto.randomU32()
        val stub = HongguoCrypto.md5(body)
        val req = Request.Builder()
            .url("$DEVICE_API/service/2/device_register/?$rawQuery")
            .post(body.toRequestBody(null))
            .header("User-Agent", USER_AGENT)
            .header("Content-Type", "application/octet-stream;tt-data=a")
            .header("Accept", "application/json; charset=utf-8,application/x-protobuf")
            .header("Content-Encoding", "gzip")
            .header("X-Tt-Request-Tag", "t=0;n=1")
            .header("Log-Encode-Type", "gzip")
            .header("Lc", "102")
            .header("Sdk-Version", "2")
            .header("Passport-Sdk-Version", "60755")
            .header("Passport-Sdk-Settings", "device_transfer_s_0,device_transfer_ab_1")
            .header("X-SS-DP", "8662")
            .header("X-Vc-Bdturing-Sdk-Version", "4.0.3.cn")
            .header("X-Tt-Store-Region", "cn")
            .header("X-Tt-Store-Region-Src", "did")
            .header("X-Khronos", nowSec.toString())
            .header("X-Argus", HongguoCrypto.base64(
                byteArrayOf(
                    nowSec.toByte(), (nowSec ushr 8).toByte(),
                    (nowSec ushr 16).toByte(), (nowSec ushr 24).toByte()
                )
            ))
            .header("X-SS-Req-Ticket", stamp.toString())
            .header("X-SS-STUB", HongguoCrypto.toHex(stub))
            .header("X-Gorgon", HongguoCrypto.gorgon(rawQuery, body, nowSec.toInt(), nonce and 0xFFFF))
            .build()
        http.newCall(req).execute().use { resp ->
            require(resp.isSuccessful) { "设备注册返回 HTTP ${resp.code}" }
            val bytes = resp.body?.bytes() ?: throw IllegalStateException("设备注册响应为空")
            val jsonText = if (bytes.size > 6 && bytes[0] == 0x74.toByte() && bytes[1] == 0x63.toByte()) {
                String(ttDecrypt(bytes), Charsets.UTF_8)
            } else {
                String(bytes, Charsets.UTF_8)
            }
            val obj = JSONObject(jsonText)
            fun idOf(a: String, b2: String): String {
                val v = obj.optString(a)
                return if (v.isNotEmpty()) v else obj.optString(b2)
            }
            val did = idOf("device_id_str", "device_id")
            val iid = idOf("install_id_str", "install_id")
            require(did.isNotEmpty() && iid.isNotEmpty() && did != "0" && iid != "0") {
                "设备注册未返回有效身份"
            }
            session.deviceId = did
            session.installId = iid
            val cookies = session.cookies.toMutableMap()
            for (name in listOf("store-region", "store-region-src", "ttreq", "odin_tt")) {
                resp.headers("Set-Cookie").firstOrNull { it.startsWith("$name=") }?.let { header ->
                    val value = header.substringAfter("$name=").substringBefore(';')
                    cookies[name] = value
                }
            }
            cookies["install_id"] = iid
            session.cookies = cookies
        }
    }
}
