package com.mytvb.feature.hongguo.net

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.util.zip.CRC32

/**
 * 红果会话存储：独立 SharedPreferences 文件（hongguo_session），与 B 站
 * NetworkSessionStore / CookieManager 零共享——红果身份失效只影响红果模块。
 */
class HongguoSession(context: Context) : HongguoDeviceRegister.SessionView {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("hongguo_session", Context.MODE_PRIVATE)

    override var deviceId: String
        get() = prefs.getString(K_DEVICE_ID, "") ?: ""
        set(v) = prefs.edit().putString(K_DEVICE_ID, v).apply()

    override var installId: String
        get() = prefs.getString(K_INSTALL_ID, "") ?: ""
        set(v) = prefs.edit().putString(K_INSTALL_ID, v).apply()

    override var cdid: String
        get() = prefs.getString(K_CDID, "") ?: ""
        set(v) = prefs.edit().putString(K_CDID, v).apply()

    override var openUdid: String
        get() = prefs.getString(K_OPEN_UDID, "") ?: ""
        set(v) = prefs.edit().putString(K_OPEN_UDID, v).apply()

    override var clientUdid: String
        get() = prefs.getString(K_CLIENT_UDID, "") ?: ""
        set(v) = prefs.edit().putString(K_CLIENT_UDID, v).apply()

    override var deviceCreated: Long
        get() = prefs.getLong(K_CREATED, 0L)
        set(v) = prefs.edit().putLong(K_CREATED, v).apply()

    /** 服务器下发的会话 cookie（install_id/store-region/odin_tt 等）。 */
    override var cookies: MutableMap<String, String>
        get() {
            val raw = prefs.getString(K_COOKIES, null) ?: return mutableMapOf()
            return try {
                val obj = JSONObject(raw)
                mutableMapOf<String, String>().apply {
                    for (key in obj.keys()) put(key, obj.getString(key))
                }
            } catch (_: Exception) {
                mutableMapOf()
            }
        }
        set(v) {
            val obj = JSONObject()
            for ((k, value) in v) obj.put(k, value)
            prefs.edit().putString(K_COOKIES, obj.toString()).apply()
        }

    /** 注册响应下发的设备令牌（播放 query klink_egdi）。 */
    override var klinkEgdi: String
        get() = prefs.getString(K_KLINK, "") ?: ""
        set(v) = prefs.edit().putString(K_KLINK, v).apply()

    /** 注册响应 device_token（HGTV 每个 API 请求 query 随带）。 */
    override var deviceToken: String
        get() = prefs.getString(K_DEVICE_TOKEN, "") ?: ""
        set(v) = prefs.edit().putString(K_DEVICE_TOKEN, v).apply()

    val registered: Boolean
        get() = deviceId.isNotEmpty() && installId.isNotEmpty() &&
            deviceId != "0" && installId != "0"

    // ---- 账号票据（登录态；退出仅清这些，设备身份保留） ----

    var accountCookies: MutableMap<String, String>
        get() {
            val raw = prefs.getString(K_ACCOUNT_COOKIES, null) ?: return mutableMapOf()
            return try {
                val obj = JSONObject(raw)
                mutableMapOf<String, String>().apply {
                    for (key in obj.keys()) put(key, obj.getString(key))
                }
            } catch (_: Exception) {
                mutableMapOf()
            }
        }
        set(v) {
            val obj = JSONObject()
            for ((k, value) in v) obj.put(k, value)
            prefs.edit().putString(K_ACCOUNT_COOKIES, obj.toString()).apply()
        }

    var profile: String?
        get() = prefs.getString(K_PROFILE, null)
        set(v) = prefs.edit().putString(K_PROFILE, v).apply()

    var loginMethod: String
        get() = prefs.getString(K_LOGIN_METHOD, "") ?: ""
        set(v) = prefs.edit().putString(K_LOGIN_METHOD, v).apply()

    /** 登录票据第二因子 x-tt-token（每个登录态请求 header 随带+响应滚动续期，对照 HGTV H0/b.java）。 */
    var accountToken: String
        get() = prefs.getString(K_ACCOUNT_TOKEN, "") ?: ""
        set(v) = prefs.edit().putString(K_ACCOUNT_TOKEN, v).apply()

    /** 登录态绝对过期时间（秒，sid_guard 解析；临期 3 天触发 beat 校验，对照 HGTV j()）。 */
    var accountExpires: Long
        get() = prefs.getLong(K_ACCOUNT_EXPIRES, 0L)
        set(v) = prefs.edit().putLong(K_ACCOUNT_EXPIRES, v).apply()

    /** 上次 beat 心跳时刻（毫秒，30 分钟阈值，对照 HGTV j() 的 1800000ms）。 */
    var lastBeatAt: Long
        get() = prefs.getLong(K_LAST_BEAT_AT, 0L)
        set(v) = prefs.edit().putLong(K_LAST_BEAT_AT, v).apply()

    val loggedIn: Boolean
        get() = !accountCookies["sessionid"].isNullOrEmpty() || !accountCookies["sessionid_ss"].isNullOrEmpty()

    /** x-tt-token 滚动续期（C0051b:68：X-Tt-Token 响应头 / Set-Cookie，>=99 字符直接收）。 */
    fun rollTokenFrom(headers: okhttp3.Headers) {
        headers.values("X-Tt-Token").firstOrNull()?.takeIf { it.length >= 99 }?.let {
            accountToken = it
            return
        }
        headers.values("Set-Cookie").forEach { raw ->
            if (raw.startsWith("x-tt-token=", ignoreCase = true)) {
                raw.substringAfter('=').substringBefore(';').takeIf { it.length >= 99 }
                    ?.let { accountToken = it }
            }
        }
    }

    /** 仅清账号票据（登出），设备身份保留。 */
    fun clearAccount() {
        prefs.edit()
            .remove(K_ACCOUNT_COOKIES).remove(K_PROFILE).remove(K_LOGIN_METHOD)
            .remove(K_ACCOUNT_TOKEN).remove(K_ACCOUNT_EXPIRES).remove(K_LAST_BEAT_AT)
            .apply()
    }

    /** 清除全部红果身份数据（设备身份+票据），设置页「清除红果数据」入口。 */
    fun clearAll() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val K_DEVICE_ID = "device_id"
        private const val K_INSTALL_ID = "install_id"
        private const val K_CDID = "cdid"
        private const val K_OPEN_UDID = "openudid"
        private const val K_CLIENT_UDID = "clientudid"
        private const val K_CREATED = "created"
        private const val K_COOKIES = "cookies"
        private const val K_ACCOUNT_COOKIES = "account_cookies"
        private const val K_PROFILE = "account_profile"
        private const val K_LOGIN_METHOD = "login_method"
        private const val K_ACCOUNT_TOKEN = "account_token"
        private const val K_ACCOUNT_EXPIRES = "account_expires"
        private const val K_LAST_BEAT_AT = "last_beat_at"
        private const val K_KLINK = "klink_egdi"
        private const val K_DEVICE_TOKEN = "device_token"
    }
}

/** 手写 gzip 流：与 Go 版 TTEncrypt 的确定性 10 字节头（mtime/XFL/OS 全零）字节级一致。 */
internal object HongguoGzip {

    fun compress(plain: ByteArray): ByteArray {
        val deflater = java.util.zip.Deflater(9, true)
        deflater.setInput(plain)
        deflater.finish()
        val buf = ByteArray(4096)
        val out = java.io.ByteArrayOutputStream(plain.size / 2 + 16)
        while (!deflater.finished()) {
            val n = deflater.deflate(buf)
            out.write(buf, 0, n)
        }
        deflater.end()
        val crc = CRC32().apply { update(plain) }
        val body = out.toByteArray()
        val result = ByteArray(10 + body.size + 8)
        result[0] = 0x1f; result[1] = 0x8b.toByte(); result[2] = 8
        // 3..9 固定零（Go 版 copy 覆盖后的确定性头）
        System.arraycopy(body, 0, result, 10, body.size)
        val c = crc.value
        result[10 + body.size] = (c and 0xFF).toByte()
        result[10 + body.size + 1] = ((c ushr 8) and 0xFF).toByte()
        result[10 + body.size + 2] = ((c ushr 16) and 0xFF).toByte()
        result[10 + body.size + 3] = ((c ushr 24) and 0xFF).toByte()
        val len = plain.size
        result[10 + body.size + 4] = (len and 0xFF).toByte()
        result[10 + body.size + 5] = ((len ushr 8) and 0xFF).toByte()
        result[10 + body.size + 6] = ((len ushr 16) and 0xFF).toByte()
        result[10 + body.size + 7] = ((len ushr 24) and 0xFF).toByte()
        return result
    }

    fun decompress(gz: ByteArray): ByteArray {
        val inflater = java.util.zip.Inflater(true)
        inflater.setInput(gz, 10, gz.size - 10 - 8)
        val buf = ByteArray(4096)
        val out = java.io.ByteArrayOutputStream(gz.size)
        while (!inflater.finished()) {
            val n = inflater.inflate(buf)
            if (n == 0 && inflater.needsInput()) break
            out.write(buf, 0, n)
        }
        inflater.end()
        return out.toByteArray()
    }
}
