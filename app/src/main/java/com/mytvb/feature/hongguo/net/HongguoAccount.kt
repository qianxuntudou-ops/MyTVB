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

    /** 验证码登录：成功存票据返回资料；遇短信上行 MFA 抛 [MfaRequiredException]。 */
    fun smsLogin(phone: String, code: String): Profile {
        val mobile = normalizePhone(phone)
        if (!code.matches(Regex("\\d{4,8}"))) throw AccountException("验证码格式无效")
        val form = "mobile=${xor05Hex(mobile)}" +
            "&code=${xor05Hex(code)}" +
            "&account_sdk_source=app" +
            "&passport_support_flow=captcha%2Cverify" +
            "&mix_mode=1"
        val payload = passportPost("/passport/mobile/sms_login/", form)
        return finishSmsLogin(payload, mobile, code)
    }

    /**
     * MFA 短信上行确认（对照 HGTV F0.O1.h：同接口重发，conf 键值展开回传 +
     * passport_mfa_retry_tag=1；conf 缺 sms_code_key 时用 [extraCode] 补）。
     * 用户用手机把 [MfaChallenge.smsContent] 发到 [MfaChallenge.channelMobile] 后调用。
     * 响应仍是 MFA 挑战时抛新的 [MfaRequiredException]（服务端可能更新短信内容）。
     */
    fun smsLoginMfa(phone: String, code: String, challenge: MfaChallenge, extraCode: String = ""): Profile {
        val mobile = normalizePhone(phone)
        val form = LinkedHashMap<String, String>()
        form["mobile"] = xor05Hex(mobile)
        form["code"] = xor05Hex(code)
        form["account_sdk_source"] = "app"
        form["passport_support_flow"] = "captcha,verify"
        form["mix_mode"] = "1"
        var hasSmsCodeKey = false
        try {
            val conf = JSONObject(challenge.rawConf)
            for (key in conf.keys()) {
                val value = conf.optString(key)
                if (value.isEmpty()) continue
                form[key] = if (key == "sms_code_key") xor05Hex(value) else value
                if (key == "sms_code_key") hasSmsCodeKey = true
            }
        } catch (_: Exception) {
            // conf 损坏：按字段直拼兜底
            if (challenge.smsCodeKey.isNotEmpty()) {
                form["sms_code_key"] = xor05Hex(challenge.smsCodeKey)
                hasSmsCodeKey = true
            }
            challenge.verifyTicket.takeIf { it.isNotEmpty() }?.let { form["verify_ticket"] = it }
            challenge.encryptUid.takeIf { it.isNotEmpty() }?.let { form["encrypt_uid"] = it }
        }
        form["passport_mfa_retry_tag"] = "1"
        if (!hasSmsCodeKey) form["sms_code_key"] = xor05Hex(extraCode)
        val payload = passportPost(
            "/passport/mobile/sms_login/",
            form.entries.joinToString("&") { (k, v) ->
                "$k=${java.net.URLEncoder.encode(v, "UTF-8")}"
            }
        )
        return finishSmsLogin(payload, mobile, code)
    }

    /** sms_login 响应收口：MFA 挑战识别（O1.j 同款字段链）或完成登录。 */
    private fun finishSmsLogin(payload: JSONObject, mobile: String, code: String): Profile {
        val data = payload.optJSONObject("data")
            ?: throw AccountException(payload.optString("message").ifEmpty { "登录失败" })
        parseMfaChallenge(payload, data)?.let { throw MfaRequiredException(it) }
        val uid = data.optString("user_id_str").ifEmpty { data.optString("user_id") }
        val name = data.optString("screen_name").ifEmpty { data.optString("name") }.ifEmpty { "红果用户" }
        val avatar = data.optString("avatar_url")
        val sid = cookieValue("sessionid").ifEmpty { cookieValue("sessionid_ss") }
        if (sid.isEmpty()) throw AccountException("登录未取得会话票据")
        return saveAccount(uid, name, avatar, "phone")
    }

    /** MFA 短信上行挑战（passport verify_center 下发的 mobile_up_sms_verify）。 */
    data class MfaChallenge(
        val encryptUid: String,
        val smsCodeKey: String,
        val logId: String,
        /** 用户发短信的目标号码。 */
        val channelMobile: String,
        /** 用户要发送的短信内容。 */
        val smsContent: String,
        val verifyTicket: String,
        val mobileTicket: String,
        val verifyScene: String,
        /** conf 原文（确认时键值展开回传）。 */
        val rawConf: String
    )

    class MfaRequiredException(val challenge: MfaChallenge) :
        IllegalStateException("该账号需要短信上行二次验证")

    /**
     * 从 sms_login 响应解析 MFA 挑战（对照 O1.j：verify_center_secondary_decision_conf
     * 对象/字符串双形态 + verify_ways 数组里 verify_way=mobile_up_sms_verify 兜底）。
     * 非 MFA 响应返回 null。
     */
    private fun parseMfaChallenge(payload: JSONObject, data: JSONObject): MfaChallenge? {
        fun confOf(obj: JSONObject?): JSONObject? {
            if (obj == null) return null
            obj.optJSONObject("verify_center_secondary_decision_conf")?.let { return it }
            obj.optString("verify_center_secondary_decision_conf").takeIf { it.isNotEmpty() }?.let {
                return try {
                    JSONObject(it)
                } catch (_: Exception) {
                    null
                }
            }
            return null
        }

        var conf = confOf(payload) ?: confOf(data) ?: confOf(payload.optJSONObject("verify_center"))
        if (conf == null) {
            // 兜底：verify_ways 数组
            val ways = data.optJSONArray("verify_ways") ?: payload.optJSONArray("verify_ways")
            if (ways != null) {
                for (i in 0 until ways.length()) {
                    val w = ways.optJSONObject(i) ?: continue
                    if (w.optString("verify_way") == "mobile_up_sms_verify" &&
                        w.optString("channel_mobile").isNotEmpty() &&
                        w.optString("sms_content").isNotEmpty()
                    ) {
                        conf = w
                        break
                    }
                }
            }
        }
        if (conf == null) return null
        if (conf.optString("subtype") != "mobile_up_sms_verify") return null
        val channelMobile = conf.optString("channel_mobile")
        val smsContent = conf.optString("sms_content")
        if (channelMobile.isEmpty() || smsContent.isEmpty()) return null
        val logId = conf.optString("log_id").ifEmpty {
            conf.optJSONObject("event_params")?.optString("log_id").orEmpty()
        }
        return MfaChallenge(
            encryptUid = conf.optString("encrypt_uid"),
            smsCodeKey = conf.optString("sms_code_key"),
            logId = logId,
            channelMobile = channelMobile,
            smsContent = smsContent,
            verifyTicket = conf.optString("verify_ticket").ifEmpty { conf.optString("ticket") },
            mobileTicket = conf.optString("mobile_ticket"),
            verifyScene = conf.optString("verify_scene").ifEmpty {
                conf.optString("passport_scene").ifEmpty { conf.optString("scene") }
            },
            rawConf = conf.toString()
        )
    }

    // ---- 导入通道（cookieStore.xml / sessionid / cookie 串） ----

    /**
     * 导入登录态，粘贴三种内容之一（对照 HGTV 1.3.6 L0.M0 + F0.AbstractC0133c）：
     * 1. 官方 App 的 cookieStore.xml 全文（TTNet 把 cookie 按 `https://snssdk.com/|name` 存
     *    shared_prefs，值是 TLV hex 编码的字符串数组）——取 snssdk.com 域、按白名单拼完整 cookie 串；
     * 2. 32 位十六进制 sessionid；
     * 3. 含 sessionid=... 的 cookie 串。
     * 导入即校验（拉资料失败不落盘）。
     */
    fun importSession(rawInput: String): Profile {
        val input = rawInput.trim()
        if (input.contains("<map") || input.contains("<string name=")) {
            importCookieStoreXml(input)
        } else {
            val sid = when {
                input.matches(Regex("^[0-9a-fA-F]{32}$")) -> input
                else -> Regex("sessionid(?:_ss)?=([0-9a-fA-F]{32})").find(input)?.groupValues?.get(1)
                    ?: throw AccountException("粘贴官方 App 的 cookieStore.xml 全文，或一个 sessionid")
            }
            putAccountCookie("sessionid", sid)
        }
        val profile = fetchProfile()
            ?: throw AccountException("登录态已失效或拉取资料失败（cookieStore.xml 要取最新的那一份）")
        saveAccount(profile.uid, profile.name, profile.avatar, "import")
        return profile
    }

    /**
     * 解析官方 App 的 cookieStore.xml（[HongguoCookieStoreParser.parse]）：
     * 只收 snssdk.com 域条目，值经 TLV hex 解码。与 HGTV 一致不做 sid_guard 过期本地判定
     * （失效由资料拉取兜底报错）。
     */
    private fun importCookieStoreXml(xml: String) {
        val found = HongguoCookieStoreParser.parse(xml)
        for (name in HongguoCookieStoreParser.COOKIE_ORDER) found[name]?.let { putAccountCookie(name, it) }
    }

    private fun putAccountCookie(name: String, value: String) {
        cookieStore.getOrPut("hongguoduanju.com") { ConcurrentHashMap() }[name] =
            Cookie.Builder().name(name).value(value)
                .domain("hongguoduanju.com").path("/").build()
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
                // 完整 cookie 串（白名单序，对照 HGTV AbstractC0133c.c——比单发 sessionid 更接近官方 App 形态）
                .header(
                    "Cookie",
                    HongguoCookieStoreParser.COOKIE_ORDER.mapNotNull { name ->
                        (cookieValue(name).ifEmpty { session.accountCookies[name].orEmpty() })
                            .takeIf { it.isNotEmpty() }?.let { "$name=$it" }
                    }.joinToString("; ").ifEmpty { "sessionid=$sid" }
                )
                .build()
        ).execute().use { resp ->
            // x-tt-token 滚动（C0051b:68 同款：X-Tt-Token 响应头 >=99 字符直接收）
            resp.headers.values("X-Tt-Token").firstOrNull()?.takeIf { it.length >= 99 }?.let {
                session.accountToken = it
            }
            resp.body?.string().orEmpty()
        }
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
        // x-tt-token 第二票据（passport Set-Cookie 下发；请求 header 随带+响应滚动续期，对照 HGTV H0/b.java）
        cookies["x-tt-token"]?.takeIf { it.length >= 99 }?.let { session.accountToken = it }
        // sid_guard 过期解析（AbstractC0133c.b：`<uid>|<登录ts>|<有效秒>|...`，%7C 解码）
        cookies["sid_guard"]?.let { guard ->
            val parts = guard.replace("%7C", "|").split("|")
            if (parts.size >= 3) {
                val ts = parts[1].toLongOrNull()
                val ttl = parts[2].toLongOrNull()
                if (ts != null && ttl != null) session.accountExpires = ts + ttl
            }
        }
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

/**
 * 官方 App cookieStore.xml 解析（对照 HGTV 1.3.6 L0.M0 + F0.AbstractC0133c）。
 * xml 形态：`<string name="https://snssdk.com/|cookie名">hex编码的字符串数组</string>`，
 * 值为 TLV 序列化（'t' + 2 字节 BE 长度 + 可打印 ASCII payload），cookie 名的下一项即值。
 */
internal object HongguoCookieStoreParser {

    /** cookie 拼串白名单与顺序（HGTV AbstractC0133c.f1871a，16 项）。 */
    val COOKIE_ORDER = listOf(
        "sessionid", "sessionid_ss", "sid_tt", "uid_tt", "uid_tt_ss", "d_ticket",
        "sid_guard", "session_tlb_tag", "store-region", "store-region-src",
        "install_id", "n_mh", "odin_tt", "ttreq", "passport_csrf_token",
        "passport_csrf_token_default"
    )

    /** 解析：只收 snssdk.com 域条目；无 sessionid/sessionid_ss 抛错。 */
    fun parse(xml: String): Map<String, String> {
        val found = LinkedHashMap<String, String>()
        val entry = Regex("<string name=\"(https?://[^\"]+?)/\\|([^\"]+)\">([^<]*)</string>")
        for (m in entry.findAll(xml)) {
            val host = m.groupValues[1]
                .removePrefix("http://").removePrefix("https://").removeSuffix("/")
            if (host != "snssdk.com") continue
            val name = m.groupValues[2]
            val value = decodeTlvCookieValue(m.groupValues[3], name) ?: continue
            found[name] = value
        }
        if (found["sessionid"].isNullOrEmpty() && found["sessionid_ss"].isNullOrEmpty()) {
            throw HongguoAccount.AccountException("没解析到 sessionid，请确认是官方 App 的 cookieStore.xml")
        }
        return found
    }

    /** TTNet cookieStore 值解码：hex 字节流里扫 't' + len16(BE) + ASCII payload 的字符串数组，取 name 的下一项。 */
    fun decodeTlvCookieValue(raw: String, name: String): String? {
        if (raw.isEmpty() || raw.length % 2 != 0) return null
        if (!Regex("^[0-9a-fA-F]+$").matches(raw)) return null
        return try {
            val bytes = ByteArray(raw.length / 2) {
                Integer.parseInt(raw.substring(it * 2, it * 2 + 2), 16).toByte()
            }
            val parts = ArrayList<String>()
            var i = 0
            while (i < bytes.size - 2) {
                val len = ((bytes[i + 1].toInt() and 0xFF) shl 8) or (bytes[i + 2].toInt() and 0xFF)
                if (bytes[i] == 0x74.toByte() && len in 1 until 2049 && i + 3 + len <= bytes.size) {
                    val payload = bytes.copyOfRange(i + 3, i + 3 + len)
                    if (payload.all { it in 32..126 }) {
                        parts.add(String(payload, Charsets.UTF_8))
                        i += len + 3
                        continue
                    }
                }
                i++
            }
            val idx = parts.indexOf(name)
            if (idx >= 0 && idx + 1 < parts.size) parts[idx + 1] else null
        } catch (_: Exception) {
            null
        }
    }
}
