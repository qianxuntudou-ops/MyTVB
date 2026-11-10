package com.mytvb.feature.hongguo.net

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 红果账号三通道登录：扫码（网页免签）/ 短信（passport 签名）/ 导入 sessionid。
 * 对照自研 Go 版 account_scan.go / account_phone.go / HGTV F0.java 导入解析。
 * 票据存 HongguoSession（account_cookies/account_token/profile），与 B 站零共享。
 */
class HongguoAccount(private val session: HongguoSession) {

    data class Profile(val uid: String, val name: String, val avatar: String)

    class AccountException(message: String, val code: String = "ACCOUNT_ERROR") : IllegalStateException(message)

    private val cookieStore = ConcurrentHashMap<String, MutableMap<String, Cookie>>()

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(false)
            .cookieJar(object : CookieJar {
                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                    val map = cookieStore[url.host] ?: ConcurrentHashMap<String, Cookie>().also {
                        cookieStore[url.host] = it
                    }
                    for (c in cookies) {
                        if (c.expiresAt == 0L || (c.expiresAt in 1 until System.currentTimeMillis())) map.remove(c.name) else map[c.name] = c
                    }
                }

                override fun loadForRequest(url: HttpUrl): List<Cookie> =
                    cookieStore[url.host]?.values?.toList() ?: emptyList()
            })
            .build()
    }

    private fun cookieHeader(url: HttpUrl): String =
        cookieStore[url.host]?.entries?.joinToString("; ") { "${it.key}=${it.value.value}" } ?: ""

    private fun cookieValue(name: String): String =
        cookieStore.values.firstNotNullOfOrNull { it[name]?.value } ?: ""

    // ---- 扫码登录（hongguoduanju.com 网页通道，无签名） ----

    /** 申请二维码：返回 PNG bitmap + token + 过期秒数。 */
    fun startQrLogin(): QrTicket {
        val url = "https://hongguoduanju.com/passport/web/get_qrcode/" +
            "?aid=513755&passport_jssdk_version=3.4.10&passport_jssdk_type=normal" +
            "&account_sdk_source=web&is_from_ttaccountsdk=1&language=zh" +
            "&next=https%3A%2F%2Fhongguoduanju.com%2F"
        val text = http.newCall(
            Request.Builder().url(url)
                .header("User-Agent", DESKTOP_UA)
                .header("Referer", "https://hongguoduanju.com/")
                .header("Accept", "application/json, text/javascript")
                .build()
        ).execute().use { it.body?.string().orEmpty() }
        val payload = JSONObject(text)
        if (payload.optInt("error_code", payload.optInt("code", 0)) != 0) {
            throw AccountException("二维码申请失败：${payload.optString("message")}")
        }
        val data = payload.optJSONObject("data") ?: throw AccountException("二维码响应异常")
        val token = data.optString("token")
        val qrcode = data.optString("qrcode")
        var expire = data.optLong("expire_time", 180L)
        if (expire > 1_000_000_000_000L) expire /= 1000L
        expire = expire.coerceIn(60L, 600L)
        if (token.isEmpty() || !qrcode.startsWith("data:image/png;base64,")) {
            throw AccountException("二维码数据无效")
        }
        val png = Base64.decode(qrcode.removePrefix("data:image/png;base64,"), Base64.DEFAULT)
        val bitmap = BitmapFactory.decodeByteArray(png, 0, png.size)
            ?: throw AccountException("二维码解码失败")
        return QrTicket(token, bitmap, expire)
    }

    data class QrTicket(val token: String, val bitmap: Bitmap, val expireSec: Long) {
        /** 1 待扫码 / 2 已扫码待确认 / 3 已确认（拿 redirectUrl）/ 4 取消 / 5 过期。 */
        var status: Int = 1
        var redirectUrl: String = ""
    }

    /** 轮询一次扫码状态（调用方控制 1.8s 间隔与超时）。 */
    fun pollQrStatus(ticket: QrTicket) {
        val url = "https://hongguoduanju.com/passport/web/check_qrconnect/" +
            "?aid=513755&passport_jssdk_version=3.4.10&passport_jssdk_type=normal" +
            "&account_sdk_source=web&is_from_ttaccountsdk=1&language=zh" +
            "&next=https%3A%2F%2Fhongguoduanju.com%2F&token=${ticket.token}"
        val text = http.newCall(
            Request.Builder().url(url)
                .header("User-Agent", DESKTOP_UA)
                .header("Referer", "https://hongguoduanju.com/")
                .header("Accept", "application/json, text/javascript")
                .build()
        ).execute().use { it.body?.string().orEmpty() }
        if (text.isBlank()) return
        val data = JSONObject(text).optJSONObject("data") ?: return
        when (data.optInt("status", 1)) {
            1 -> ticket.status = 1
            2 -> ticket.status = 2
            3 -> {
                ticket.status = 3
                ticket.redirectUrl = data.optString("redirect_url")
            }
            4 -> ticket.status = 4
            5 -> ticket.status = 5
        }
    }

    /** 扫码确认后完成登录：跟随 redirect（≤5 跳，仅 hongguoduanju.com）收 sessionid → 拉资料 → 存票据。 */
    fun finishQrLogin(ticket: QrTicket): Profile {
        var url = ticket.redirectUrl
        if (url.isEmpty()) throw AccountException("缺少确认跳转地址")
        for (hop in 0 until 5) {
            val parsed = url.toHttpUrlOrNull() ?: throw AccountException("跳转地址无效")
            if (!parsed.isHttps || !(parsed.host == "hongguoduanju.com" || parsed.host.endsWith(".hongguoduanju.com"))) {
                throw AccountException("跳转域不受支持", "SECURITY_BLOCKED")
            }
            val resp = http.newCall(
                Request.Builder().url(url)
                    .header("User-Agent", DESKTOP_UA)
                    .header("Referer", "https://hongguoduanju.com/")
                    .build()
            ).execute()
            val location = resp.header("Location")
            resp.close()
            if (location.isNullOrEmpty()) break
            url = location
        }
        val sid = cookieValue("sessionid").ifEmpty { cookieValue("sessionid_ss") }
        if (sid.isEmpty()) throw AccountException("扫码未取得会话票据")
        // 网页域账号资料
        val info = http.newCall(
            Request.Builder().url("https://hongguoduanju.com/passport/account/info/v2/")
                .header("User-Agent", DESKTOP_UA)
                .header("Referer", "https://hongguoduanju.com/")
                .build()
        ).execute().use { it.body?.string().orEmpty() }
        var uid = ""
        var name = "红果用户"
        var avatar = ""
        if (info.isNotBlank()) {
            val data = JSONObject(info).optJSONObject("data")
            uid = data?.optString("user_id_str").orEmpty().ifEmpty { data?.optString("user_id").orEmpty() }
            name = data?.optString("screen_name").orEmpty()
                .ifEmpty { data?.optString("name").orEmpty() }
                .ifEmpty { data?.optString("username").orEmpty() }
                .ifEmpty { "红果用户" }
            avatar = data?.optString("avatar_url").orEmpty()
        }
        return saveAccount(uid, name, avatar, "qr")
    }

    // ---- 短信登录（api5-normal-lq passport 通道，签名 form POST） ----

    /** 发送验证码。@param phone E.164 或 11 位手机号。 */
    fun smsSendCode(phone: String) {
        val mobile = normalizePhone(phone)
        val form = "mobile=${xor05Hex(mobile)}" +
            "&account_sdk_source=app" +
            "&passport_support_flow=captcha%2Cverify" +
            "&mix_mode=1&auto_read=0&unbind_exist=34" +
            "&type=${xor05Hex("24")}"
        val payload = passportPost("/passport/mobile/send_code/v1/", form)
        val message = payload.optString("message")
        val errCode = payload.optJSONObject("data")?.optString("error_code").orEmpty()
        if (message != "success" || (errCode.isNotEmpty() && errCode != "0")) {
            val retry = payload.optJSONObject("data")?.optInt("retry_time", 0) ?: 0
            throw AccountException(
                if (retry > 0) "发送太频繁，${retry}秒后重试" else "验证码发送失败：$message"
            )
        }
    }

    /** 验证码登录：成功存票据返回资料。 */
    fun smsLogin(phone: String, code: String): Profile {
        val mobile = normalizePhone(phone)
        if (!code.matches(Regex("\\d{4,8}"))) throw AccountException("验证码格式无效")
        val form = "mobile=${xor05Hex(mobile)}" +
            "&code=${xor05Hex(code)}" +
            "&account_sdk_source=app" +
            "&passport_support_flow=captcha%2Cverify" +
            "&mix_mode=1"
        val payload = passportPost("/passport/mobile/sms_login/", form)
        val data = payload.optJSONObject("data")
            ?: throw AccountException(payload.optString("message").ifEmpty { "登录失败" })
        if (data.has("encrypt_uid") && data.optString("encrypt_uid").isNotEmpty()) {
            throw AccountException("该账号需要短信上行二次验证，暂不支持", "MFA_REQUIRED")
        }
        val uid = data.optString("user_id_str").ifEmpty { data.optString("user_id") }
        val name = data.optString("screen_name").ifEmpty { data.optString("name") }.ifEmpty { "红果用户" }
        val avatar = data.optString("avatar_url")
        val sid = cookieValue("sessionid").ifEmpty { cookieValue("sessionid_ss") }
        if (sid.isEmpty()) throw AccountException("登录未取得会话票据")
        return saveAccount(uid, name, avatar, "phone")
    }

    // ---- 导入通道（sessionid / cookie 串） ----

    /** 导入 32 位十六进制 sessionid（或含 sessionid=... 的 cookie 串），导入即校验。 */
    fun importSession(rawInput: String): Profile {
        val input = rawInput.trim()
        val sid = when {
            input.matches(Regex("^[0-9a-fA-F]{32}$")) -> input
            else -> Regex("sessionid(?:_ss)?=([0-9a-fA-F]{32})").find(input)?.groupValues?.get(1)
                ?: throw AccountException("需要 32 位十六进制 sessionid 或含它的 cookie 串")
        }
        cookieStore.getOrPut("hongguoduanju.com") { ConcurrentHashMap() }["sessionid"] =
            Cookie.Builder().name("sessionid").value(sid)
                .domain("hongguoduanju.com").path("/").build()
        val profile = fetchProfile()
            ?: throw AccountException("sessionid 已失效或拉取资料失败")
        saveAccount(profile.uid, profile.name, profile.avatar, "import")
        return profile
    }

    /** 已登录时拉资料（/reading/user/info/v1 带 cookie；未登录返回 null）。 */
    fun fetchProfile(): Profile? {
        val sid = cookieValue("sessionid").ifEmpty { session.accountCookies["sessionid"].orEmpty() }
        if (sid.isEmpty()) return null
        val q = TreeMap<String, String>()
        q["ac"] = "wifi"; q["aid"] = "8662"; q["app_name"] = "novelread"
        q["channel"] = "update_64"; q["device_brand"] = "Redmi"
        q["device_id"] = session.deviceId
        q["device_platform"] = "android"; q["device_type"] = "25053RT47C"
        q["dpi"] = "520"; q["iid"] = session.installId
        q["cdid"] = session.cdid
        q["language"] = "zh"; q["manifest_version_code"] = "73532"
        q["os"] = "android"; q["os_api"] = "36"; q["os_version"] = "16"
        q["resolution"] = "1280*2772"; q["ssmix"] = "a"
        q["host_abi"] = "arm64-v8a"; q["dragon_device_type"] = "phone"
        q["pv_player"] = "73532"; q["compliance_status"] = "0"
        q["need_personal_recommend"] = "1"; q["player_so_load"] = "1"
        q["is_android_pad_screen"] = "0"
        q["update_version_code"] = "73532"; q["version_code"] = "73532"
        q["version_name"] = "7.3.5.32"
        val signed = HongguoCrypto.signRequest(q, null, System.currentTimeMillis(), session.deviceId)
        val text = http2.newCall(
            Request.Builder()
                .url("https://api5-normal-lq.fqnovel.com/reading/user/info/v1/?${signed.query}")
                .get()
                .header("User-Agent", HongguoDeviceRegister.USER_AGENT)
                .header("Accept", "application/json")
                .apply { signed.headers.forEach { (k, v) -> header(k, v) } }
                .header("Sdk-Version", "2")
                .header("X-SS-DP", "8662")
                .header("Cookie", "sessionid=$sid")
                .build()
        ).execute().use { it.body?.string().orEmpty() }
        if (text.isBlank()) return null
        val data = JSONObject(text).optJSONObject("data") ?: return null
        val uid = data.optString("biz_user_id").ifEmpty { data.optString("user_id") }
        val name = data.optString("user_name").ifEmpty { "红果用户" }
        val avatar = data.optString("avatar_url").ifEmpty { data.optString("expand_avatar_url") }
        if (uid.isEmpty() && name == "红果用户" && avatar.isEmpty()) return null
        return Profile(uid, name, avatar)
    }

    // ---- 票据存取 ----

    private fun saveAccount(uid: String, name: String, avatar: String, method: String): Profile {
        val cookies = HashMap<String, String>()
        for (hostMap in cookieStore.values) {
            for ((k, c) in hostMap) if (!cookies.containsKey(k)) cookies[k] = c.value
        }
        session.accountCookies = cookies
        session.loginMethod = method
        val profile = Profile(uid, name, avatar)
        session.profile = JSONObject()
            .put("uid", uid).put("name", name).put("avatar", avatar)
            .toString()
        return profile
    }

    fun storedProfile(): Profile? {
        val raw = session.profile ?: return null
        if (session.accountCookies["sessionid"].isNullOrEmpty()) return null
        return try {
            val obj = JSONObject(raw)
            Profile(obj.optString("uid"), obj.optString("name"), obj.optString("avatar"))
        } catch (_: Exception) {
            null
        }
    }

    /** 退出：清账号票据（设备身份保留，对照 Go logout 语义）。 */
    fun logout() {
        session.accountCookies = mutableMapOf()
        session.profile = null
        session.loginMethod = ""
        cookieStore.clear()
    }

    // ---- passport form POST（签名 + passport 头） ----

    private val http2: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private fun passportPost(path: String, form: String): JSONObject {
        // 设备须已注册（未注册的 device_id 会被 Passport 拒）
        if (!session.registered) {
            throw AccountException("设备身份未注册，请稍后重试")
        }
        val q = TreeMap<String, String>()
        q["ac"] = "wifi"; q["aid"] = "8662"; q["app_name"] = "novelread"
        q["channel"] = "update_64"; q["device_brand"] = "Redmi"
        q["device_id"] = session.deviceId
        q["device_platform"] = "android"; q["device_type"] = "25053RT47C"
        q["dpi"] = "520"; q["iid"] = session.installId
        q["cdid"] = session.cdid
        q["openudid"] = session.openUdid
        q["language"] = "zh"; q["manifest_version_code"] = "73532"
        q["os"] = "android"; q["os_api"] = "36"; q["os_version"] = "16"
        q["resolution"] = "1280*2772"; q["ssmix"] = "a"
        q["host_abi"] = "arm64-v8a"; q["dragon_device_type"] = "phone"
        q["pv_player"] = "73532"; q["compliance_status"] = "0"
        q["need_personal_recommend"] = "1"; q["player_so_load"] = "1"
        q["is_android_pad_screen"] = "0"
        q["update_version_code"] = "73532"; q["version_code"] = "73532"
        q["version_name"] = "7.3.5.32"
        val raw = form.toByteArray(Charsets.UTF_8)
        val signed = HongguoCrypto.signRequest(
            q, raw, System.currentTimeMillis(), session.deviceId,
            targetBranch = 2, version = "7.3.5.32"
        )
        val text = http2.newCall(
            Request.Builder()
                .url("https://api5-normal-lq.fqnovel.com$path?${signed.query}")
                .post(raw.toRequestBody("application/x-www-form-urlencoded; charset=UTF-8".toMediaType()))
                .header("User-Agent", HongguoDeviceRegister.USER_AGENT)
                .header("Accept", "application/json")
                .apply { signed.headers.forEach { (k, v) -> header(k, v) } }
                .header("Passport-Sdk-Version", "5051452")
                .header("X-Vc-Bdturing-Sdk-Version", "4.0.3.cn")
                .header("Lc", "101")
                .header("Sdk-Version", "2")
                .header("X-SS-DP", "8662")
                .build()
        ).execute().use { it.body?.string().orEmpty() }
        if (text.isBlank()) throw AccountException("登录接口无响应")
        return JSONObject(text)
    }

    // ---- 工具 ----

    /** E.164 或 11 位号 → +86XXXXXXXXXXX。 */
    private fun normalizePhone(phone: String): String {
        val digits = phone.filter { it.isDigit() }
        return when {
            digits.length == 11 && !phone.startsWith("+") -> "+86$digits"
            phone.startsWith("+") -> "+$digits"
            else -> throw AccountException("手机号格式无效")
        }
    }

    /** 每字节 XOR 0x05 后 hex（对照 Go passport_client.go mobileCode）。 */
    private fun xor05Hex(value: String): String {
        val sb = StringBuilder(value.length * 2)
        for (b in value.toByteArray(Charsets.UTF_8)) {
            val v = (b.toInt() and 0xFF) xor 0x05
            sb.append("%02x".format(v))
        }
        return sb.toString()
    }

    private companion object {
        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
    }
}
