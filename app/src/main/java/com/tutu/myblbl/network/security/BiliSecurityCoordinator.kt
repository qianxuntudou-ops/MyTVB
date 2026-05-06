@file:Suppress("SpellCheckingInspection")

package com.tutu.myblbl.network.security

import android.util.Base64
import com.tutu.myblbl.model.BaseResponse
import com.tutu.myblbl.model.user.UserDetailInfoModel
import com.tutu.myblbl.network.WbiGenerator
import com.tutu.myblbl.network.api.ApiService
import com.tutu.myblbl.core.common.log.AppLog
import com.tutu.myblbl.network.cookie.CookieManager
import java.security.KeyFactory
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class BiliSecurityCoordinator(
    private val tag: String,
    private val apiService: ApiService,
    private val noCookieApiService: ApiService,
    private val okHttpClient: OkHttpClient,
    private val cookieManager: CookieManager,
    private val userAgentProvider: () -> String,
    private val refreshUserAgent: () -> String,
    private val syncUserSession: (BaseResponse<UserDetailInfoModel>, String) -> UserDetailInfoModel?,
    private val refreshTokenProvider: () -> String?,
    private val refreshTokenSaver: (String) -> Unit,
    private val updateWbiKeys: (String, String) -> Unit
) {

    companion object {
        private const val PREWARM_INTERVAL_MS = 5 * 60 * 1000L
        private const val BILI_TICKET_KEY_ID = "ec02"
        private const val BILI_TICKET_HMAC_KEY = "XgwSnGZ1p"
        private const val EXCLIMB_MAX_RETRIES = 2
        private const val EXCLIMB_RETRY_DELAY_MS = 2000L
        private const val COOKIE_INFO_CHECK_INTERVAL_MS = 30 * 60 * 1000L
    }

    private val prewarmMutex = Mutex()
    private val ensureHealthyMutex = Mutex()
    private val wbiKeysMutex = Mutex()
    private val cookieRefreshMutex = Mutex()

    private var lastPrewarmTimestampMs: Long = 0L
    private var lastEnsureHealthyForPlayMs: Long = 0L
    private var biliTicketCheckedDay: Long = 0L
    private var buvidActivatedMid: Long = 0L
    private var buvidActivatedDay: Long = 0L
    private var lastCookieInfoCheckMs: Long = 0L
    private var cookieRefreshCheckedDay: Long = 0L

    private val correspondPublicKey: PublicKey by lazy {
        val derBase64 =
            "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDLgd2OAkcGVtoE3ThUREbio0Eg" +
                "Uc/prcajMKXvkCKFCWhJYJcLkcM2DKKcSeFpD/j6Boy538YXnR6VhcuUJOhH2x71" +
                "nzPjfdTcqMz7djHum0qSZA0AyCBDABUqCrfNgCiJ00Ra7GmRj+YCK1NJEuewlb40" +
                "JNrRuoEUXpabUzGB8QIDAQAB"
        val keyBytes = Base64.decode(derBase64, Base64.DEFAULT)
        val spec = X509EncodedKeySpec(keyBytes)
        KeyFactory.getInstance("RSA").generatePublic(spec)
    }

    suspend fun ensureHealthyForPlay() {
        ensureHealthyMutex.withLock {
            val now = System.currentTimeMillis()
            if (now - lastEnsureHealthyForPlayMs < 60_000L) return@withLock

            coroutineScope {
                launch { ensureWebFingerprintCookies() }
                launch { ensureBiliTicket() }
                launch { ensureBuvidActiveOncePerDay() }
                launch { refreshCookieIfServerSaysNeeded() }
            }

            lastEnsureHealthyForPlayMs = System.currentTimeMillis()
        }
    }

    suspend fun activateAfterLogin() {
        runCatching { ensureWebFingerprintCookies() }
        runCatching { ensureBuvidActiveOncePerDay() }
    }

    suspend fun prewarmWebSession(forceUaRefresh: Boolean = false): Boolean {
        return prewarmMutex.withLock {
            val now = System.currentTimeMillis()
            if (!forceUaRefresh &&
                now - lastPrewarmTimestampMs < PREWARM_INTERVAL_MS &&
                hasBaselineIdentityCookies()
            ) {
                return@withLock true
            }
            cookieManager.syncFromWebView()
            if (forceUaRefresh) {
                refreshUserAgent()
            }
            val (mainPageLoaded, navSuccess) = coroutineScope {
                val mainPageDeferred = async {
                    runCatching {
                        apiService.getMainPage().use { body ->
                            body.source().request(1)
                        }
                    }.onFailure { throwable ->
                        AppLog.e(tag, "prewarmWebSession main page failed: ${throwable.message}", throwable)
                    }.isSuccess
                }
                val navDeferred = async {
                    runCatching {
                        val navResponse = apiService.getUserDetailInfo()
                        if (navResponse.isSuccess && navResponse.data != null) {
                            val imgKey = navResponse.data.wbiImg?.imgUrl?.let(WbiGenerator::extractKeyFromUrl).orEmpty()
                            val subKey = navResponse.data.wbiImg?.subUrl?.let(WbiGenerator::extractKeyFromUrl).orEmpty()
                            if (imgKey.isNotBlank() && subKey.isNotBlank()) {
                                updateWbiKeys(imgKey, subKey)
                            }
                            syncUserSession(navResponse, "$tag/prewarmWebSession")
                            true
                        } else {
                            if (navResponse.code == -101 && cookieManager.hasSessionCookie()) {
                                AppLog.w(tag, "prewarmWebSession: session expired (code=-101), triggering cookie refresh")
                                runCatching { doCookieRefresh() }
                            }
                            AppLog.w(tag, "prewarmWebSession nav failed: code=${navResponse.code} msg=${navResponse.message}")
                            false
                        }
                    }.onFailure { throwable ->
                        AppLog.e(tag, "prewarmWebSession nav failed: ${throwable.message}", throwable)
                    }.getOrDefault(false)
                }
                mainPageDeferred.await() to navDeferred.await()
            }
            val success = mainPageLoaded || navSuccess
            if (mainPageLoaded) {
                cookieManager.syncFromWebView()
            }
            if (success) {
                lastPrewarmTimestampMs = now
            }
            success
        }
    }

    fun buildPiliWebHeaders(targetUrl: String, includeCookie: Boolean = true): Map<String, String> {
        val midStr = cookieManager.getCookieValue("DedeUserID")?.trim().orEmpty()
        val mid = midStr.toLongOrNull()?.takeIf { it > 0 } ?: 0L
        val headers = mutableMapOf(
            "env" to "prod",
            "x-bili-aurora-zone" to "sh001",
            "Referer" to "https://www.bilibili.com",
            "X-Blbl-Skip-Origin" to "1"
        )
        if (mid > 0) {
            headers["x-bili-mid"] = mid.toString()
            genAuroraEid(mid)?.let { headers["x-bili-aurora-eid"] = it }
        }
        if (includeCookie) {
            val cookie = cookieManager.getCookieHeaderFor(targetUrl)
            if (!cookie.isNullOrBlank()) headers["Cookie"] = cookie
        }
        return headers
    }

    suspend fun postFormJson(
        url: String,
        form: Map<String, String>,
        extraHeaders: Map<String, String>? = null
    ): JSONObject {
        return withContext(Dispatchers.IO) {
            val formBody = FormBody.Builder().apply {
                form.forEach { (k, v) -> add(k, v) }
            }.build()
            val reqBuilder = okhttp3.Request.Builder()
                .url(url)
                .post(formBody)
                .header("User-Agent", userAgentProvider())
                .header("Referer", "https://www.bilibili.com")
                .header("Origin", "https://www.bilibili.com")
                .header("Content-Type", "application/x-www-form-urlencoded")
            extraHeaders?.forEach { (k, v) -> reqBuilder.header(k, v) }
            okHttpClient.newCall(reqBuilder.build()).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                JSONObject(body)
            }
        }
    }

    suspend fun requestJson(
        url: String,
        extraHeaders: Map<String, String>? = null
    ): JSONObject {
        return withContext(Dispatchers.IO) {
            val reqBuilder = okhttp3.Request.Builder()
                .url(url)
                .header("User-Agent", userAgentProvider())
                .header("Referer", "https://www.bilibili.com")
            extraHeaders?.forEach { (k, v) -> reqBuilder.header(k, v) }
            okHttpClient.newCall(reqBuilder.build()).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                JSONObject(body)
            }
        }
    }

    suspend fun ensureWbiKeys() {
        wbiKeysMutex.withLock {
            ensureWebFingerprintCookies()
            ensureBiliTicket()
            ensureWbiKeysFromNav()
        }
    }

    private suspend fun ensureWbiKeysFromNav() {
        runCatching {
            val navResponse = apiService.getUserDetailInfo()
            if (navResponse.data != null) {
                val imgKey = navResponse.data.wbiImg?.imgUrl?.let(WbiGenerator::extractKeyFromUrl).orEmpty()
                val subKey = navResponse.data.wbiImg?.subUrl?.let(WbiGenerator::extractKeyFromUrl).orEmpty()
                if (imgKey.isNotBlank() && subKey.isNotBlank()) {
                    updateWbiKeys(imgKey, subKey)
                }
            }
        }.onFailure {
            AppLog.w(tag, "ensureWbiKeysFromNav failed: ${it.message}")
        }
    }

    fun resetRuntimeState() {
        lastPrewarmTimestampMs = 0L
        lastEnsureHealthyForPlayMs = 0L
        biliTicketCheckedDay = 0L
        buvidActivatedMid = 0L
        buvidActivatedDay = 0L
        lastCookieInfoCheckMs = 0L
    }

    suspend fun forceCookieRefresh() {
        lastCookieInfoCheckMs = 0L
        lastEnsureHealthyForPlayMs = 0L
        ensureHealthyForPlay()
    }

    suspend fun ensureWebFingerprintCookies() {
        withContext(Dispatchers.IO) {
            val hasBuvid3 = !cookieManager.getCookieValue("buvid3").isNullOrBlank()
            val hasBNut = !cookieManager.getCookieValue("b_nut").isNullOrBlank()
            val hasBuvid4 = !cookieManager.getCookieValue("buvid4").isNullOrBlank()

            if (!hasBuvid3 || !hasBNut) {
                runCatching {
                    apiService.getMainPage().use { body ->
                        body.source().request(1)
                    }
                }.onFailure {
                    AppLog.w(tag, "ensureWebFingerprintCookies homepage failed: ${it.message}")
                }
            }

            if (!hasBuvid4) {
                runCatching {
                    val request = okhttp3.Request.Builder()
                        .url("https://api.bilibili.com/x/frontend/finger/spi")
                        .build()
                    val response = okHttpClient.newCall(request).execute()
                    val body = response.body?.string().orEmpty()
                    val json = JSONObject(body)
                    val data = json.optJSONObject("data") ?: JSONObject()
                    val b3 = data.optString("b_3", "").trim()
                    val b4 = data.optString("b_4", "").trim()
                    val expiresAt = System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000
                    val cookies = mutableListOf<Cookie>()
                    if (b3.isNotBlank() && cookieManager.getCookieValue("buvid3").isNullOrBlank()) {
                        cookies.add(buildCookie("buvid3", b3, expiresAt))
                    }
                    if (b4.isNotBlank()) {
                        cookies.add(buildCookie("buvid4", b4, expiresAt))
                    }
                    if (cookies.isNotEmpty()) {
                        cookieManager.saveCookies(cookies.map { encodeCookieDirect(it) })
                    }
                }.onFailure {
                    AppLog.w(tag, "ensureWebFingerprintCookies spi failed: ${it.message}")
                }
            }
        }
    }

    private suspend fun ensureBiliTicket() {
        withContext(Dispatchers.IO) {
            val nowMs = System.currentTimeMillis()
            val epochDay = nowMs / 86_400_000L
            if (biliTicketCheckedDay == epochDay) return@withContext
            biliTicketCheckedDay = epochDay

            runCatching {
                val ts = (nowMs / 1000).toString()
                val csrf = cookieManager.getCsrfToken()
                val hexsign = hmacSha256Hex(key = BILI_TICKET_HMAC_KEY, message = "ts$ts")
                val url = "https://api.bilibili.com/bapis/bilibili.api.ticket.v1.Ticket/GenWebTicket" +
                    "?key_id=$BILI_TICKET_KEY_ID&hexsign=$hexsign&context[ts]=$ts&csrf=$csrf"
                val request = okhttp3.Request.Builder()
                    .url(url)
                    .post(ByteArray(0).toRequestBody("application/json".toMediaType()))
                    .build()
                val response = okHttpClient.newCall(request).execute()
                val body = response.body?.string().orEmpty()
                val json = JSONObject(body)
                val data = json.optJSONObject("data") ?: return@runCatching
                val ticket = data.optString("ticket", "").trim()
                val createdAt = data.optLong("created_at", 0L)
                val ttl = data.optLong("ttl", 0L)
                if (ticket.isBlank() || createdAt <= 0L || ttl <= 0L) return@runCatching
                val expiresSec = createdAt + ttl
                val expiresAt = expiresSec * 1000L
                cookieManager.saveCookies(
                    listOf(
                        buildCookie("bili_ticket", ticket, expiresAt),
                        buildCookie("bili_ticket_expires", expiresSec.toString(), expiresAt)
                    ).map { encodeCookieDirect(it) }
                )

                val nav = data.optJSONObject("nav")
                if (nav != null) {
                    val imgKey = WbiGenerator.extractKeyFromUrl(nav.optString("img", ""))
                    val subKey = WbiGenerator.extractKeyFromUrl(nav.optString("sub", ""))
                    if (imgKey.isNotBlank() && subKey.isNotBlank()) {
                        updateWbiKeys(imgKey, subKey)
                    }
                }
            }.onFailure {
                AppLog.w(tag, "ensureBiliTicket failed: ${it.message}")
            }
        }
    }

    private suspend fun ensureBuvidActiveOncePerDay() {
        withContext(Dispatchers.IO) {
            val midStr = cookieManager.getCookieValue("DedeUserID")?.trim().orEmpty()
            val mid = midStr.toLongOrNull()?.takeIf { it > 0 } ?: return@withContext
            val epochDay = System.currentTimeMillis() / 86_400_000L
            if (buvidActivatedMid == mid && buvidActivatedDay == epochDay) return@withContext

            var success = false
            repeat(EXCLIMB_MAX_RETRIES) { attempt ->
                if (success) return@repeat
                if (attempt > 0) {
                    delay(EXCLIMB_RETRY_DELAY_MS)
                }
                runCatching {
                    WbiGenerator.ensureBRet()
                    val rand = ByteArray(32 + 8 + 4)
                    SecureRandom().nextBytes(rand)
                    rand[32] = 0; rand[33] = 0; rand[34] = 0; rand[35] = 0
                    rand[36] = 73; rand[37] = 69; rand[38] = 78; rand[39] = 68
                    val tail = ByteArray(4)
                    SecureRandom().nextBytes(tail)
                    System.arraycopy(tail, 0, rand, 40, 4)
                    val randPngEnd = Base64.encodeToString(rand, Base64.NO_WRAP)

                    val jsonData = JSONObject()
                        .put("3064", 1)
                        .put("39c8", "333.1387.fp.risk")
                        .put("3c43", JSONObject().put("adca", "Linux").put("bfe9", randPngEnd.takeLast(50)))
                        .toString()

                    val payload = JSONObject().put("payload", jsonData).toString()

                    val cookieHeader = buildList {
                        listOf("SESSDATA", "bili_jct", "DedeUserID", "DedeUserID__ckMd5", "sid", "buvid3").forEach { name ->
                            val value = cookieManager.getCookieValue(name)?.takeIf { it.isNotBlank() } ?: return@forEach
                            add("$name=$value")
                        }
                    }.joinToString("; ")

                    val request = okhttp3.Request.Builder()
                        .url("https://api.bilibili.com/x/internal/gaia-gateway/ExClimbWuzhi")
                        .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                        .header("Content-Type", "application/json")
                        .header("env", "prod")
                        .header("x-bili-aurora-zone", "sh001")
                        .header("x-bili-mid", mid.toString())
                        .apply {
                            genAuroraEid(mid)?.let { header("x-bili-aurora-eid", it) }
                        }
                        .header("Referer", "https://www.bilibili.com")
                        .header("Cookie", cookieHeader)
                        .build()

                    okHttpClient.newCall(request).execute().use { resp ->
                        if (resp.isSuccessful) {
                            buvidActivatedMid = mid
                            buvidActivatedDay = epochDay
                            success = true
                        }
                    }
                }.onFailure {
                    AppLog.w(tag, "ensureBuvidActiveOncePerDay failed (attempt=${attempt + 1}/$EXCLIMB_MAX_RETRIES): ${it.message}")
                }
            }
            if (!success) {
                AppLog.e(tag, "ensureBuvidActiveOncePerDay all $EXCLIMB_MAX_RETRIES attempts failed for mid=$mid")
            }
        }
    }

    private suspend fun refreshCookieIfServerSaysNeeded() {
        if (!cookieManager.hasSessionCookie()) return
        val now = System.currentTimeMillis()
        if (now - lastCookieInfoCheckMs < COOKIE_INFO_CHECK_INTERVAL_MS) return

        if (!cookieRefreshMutex.tryLock()) {
            AppLog.d(tag, "refreshCookie: another refresh is in progress, skipping")
            return
        }

        try {
            if (now - lastCookieInfoCheckMs < COOKIE_INFO_CHECK_INTERVAL_MS) return

            runCatching {
                val csrf = cookieManager.getCsrfToken()
                if (csrf.isBlank()) {
                    AppLog.w(tag, "refreshCookie: no bili_jct found, skipping cookie info check")
                    lastCookieInfoCheckMs = now
                    return@runCatching
                }

                val infoUrl = "https://passport.bilibili.com/x/passport-login/web/cookie/info?csrf=$csrf"
                val infoRequest = okhttp3.Request.Builder()
                    .url(infoUrl)
                    .header("User-Agent", userAgentProvider())
                    .header("Referer", "https://www.bilibili.com")
                    .build()
                val infoBody = withContext(Dispatchers.IO) {
                    okHttpClient.newCall(infoRequest).execute().use { resp ->
                        resp.body?.string().orEmpty()
                    }
                }
                val infoJson = JSONObject(infoBody)
                if (infoJson.optInt("code", -1) != 0) {
                    AppLog.w(tag, "refreshCookie: cookie/info check failed: code=${infoJson.optInt("code")} msg=${infoJson.optString("message")}")
                    lastCookieInfoCheckMs = now
                    return@runCatching
                }

                val needRefresh = infoJson.optJSONObject("data")?.optBoolean("refresh", false) ?: false
                lastCookieInfoCheckMs = System.currentTimeMillis()
                if (needRefresh) {
                    AppLog.i(tag, "refreshCookie: server says refresh needed, starting cookie refresh")
                    doCookieRefresh()
                    return@runCatching
                }

                val navResponse = apiService.getUserDetailInfo()
                if (navResponse.code == -101) {
                    AppLog.w(tag, "refreshCookie: cookie/info says OK but nav returns -101, forcing cookie refresh")
                    doCookieRefresh()
                } else if (navResponse.isSuccess && navResponse.data != null) {
                    syncUserSession(navResponse, "$tag/refreshCookieCheck")
                    AppLog.d(tag, "refreshCookie: session validated OK, mid=${navResponse.data.mid}")
                }
            }.onFailure {
                AppLog.w(tag, "refreshCookie info check failed: ${it.message}")
            }
        } finally {
            cookieRefreshMutex.unlock()
        }
    }

    private suspend fun doCookieRefresh() {
        val oldRefreshToken = refreshTokenProvider()
        if (oldRefreshToken.isNullOrBlank()) {
            AppLog.w(tag, "doCookieRefresh: no refresh_token stored")
            return
        }

        val ts = System.currentTimeMillis()
        val correspondPath = getCorrespondPath(ts)
        val correspondUrl = "https://www.bilibili.com/correspond/1/$correspondPath"
        val correspondRequest = okhttp3.Request.Builder()
            .url(correspondUrl)
            .header("User-Agent", userAgentProvider())
            .header("Referer", "https://www.bilibili.com")
            .build()
        val html = withContext(Dispatchers.IO) {
            okHttpClient.newCall(correspondRequest).execute().use { resp ->
                resp.body?.string().orEmpty()
            }
        }
        val csrfMatch = Regex("<div\\s+id=\"1-name\">\\s*([0-9a-fA-F]{16,})\\s*</div>")
            .find(html)
        val refreshCsrf = csrfMatch?.groupValues?.get(1)?.trim().orEmpty()
        if (refreshCsrf.isBlank()) {
            AppLog.w(tag, "doCookieRefresh: no csrf found in correspond page")
            return
        }

        val csrf = cookieManager.getCsrfToken()
        if (csrf.isBlank()) {
            AppLog.w(tag, "doCookieRefresh: no bili_jct found")
            return
        }

        val formBody = FormBody.Builder()
            .add("csrf", csrf)
            .add("refresh_csrf", refreshCsrf)
            .add("source", "main_web")
            .add("refresh_token", oldRefreshToken)
            .build()
        val refreshRequest = okhttp3.Request.Builder()
            .url("https://passport.bilibili.com/x/passport-login/web/cookie/refresh")
            .header("User-Agent", userAgentProvider())
            .header("Referer", "https://www.bilibili.com")
            .post(formBody)
            .build()
        val refreshBody = withContext(Dispatchers.IO) {
            okHttpClient.newCall(refreshRequest).execute().use { resp ->
                resp.body?.string().orEmpty()
            }
        }
        val refreshJson = JSONObject(refreshBody)
        if (refreshJson.optInt("code", -1) != 0) {
            AppLog.w(tag, "doCookieRefresh failed: code=${refreshJson.optInt("code")} msg=${refreshJson.optString("message")}")
            return
        }

        val newRefreshToken = refreshJson.optJSONObject("data")
            ?.optString("refresh_token", "").orEmpty().trim()
        if (newRefreshToken.isNotBlank()) {
            refreshTokenSaver(newRefreshToken)
        }

        runCatching {
            val newCsrf = cookieManager.getCsrfToken()
            val confirmForm = FormBody.Builder()
                .add("csrf", newCsrf)
                .add("refresh_token", oldRefreshToken)
                .build()
            val confirmRequest = okhttp3.Request.Builder()
                .url("https://passport.bilibili.com/x/passport-login/web/confirm/refresh")
                .header("User-Agent", userAgentProvider())
                .header("Referer", "https://www.bilibili.com")
                .post(confirmForm)
                .build()
            withContext(Dispatchers.IO) {
                okHttpClient.newCall(confirmRequest).execute().use { resp ->
                    resp.body?.string()
                }
            }
        }.onFailure {
            AppLog.w(tag, "doCookieRefresh confirm failed: ${it.message}")
        }

        AppLog.i(tag, "doCookieRefresh: cookie refresh completed successfully")
    }

    private fun hasBaselineIdentityCookies(): Boolean {
        val identityCookieNames = listOf(
            "buvid3",
            "buvid4",
            "b_nut",
            "_uuid",
            "b_lsid"
        )
        return identityCookieNames.any { cookieName ->
            !cookieManager.getCookieValue(cookieName).isNullOrBlank()
        }
    }

    private fun genAuroraEid(mid: Long): String? {
        if (mid <= 0) return null
        val key = "ad1va46a7lza".toByteArray()
        val input = mid.toString().toByteArray()
        val out = ByteArray(input.size)
        for (i in input.indices) out[i] = (input[i].toInt() xor key[i % key.size].toInt()).toByte()
        return Base64.encodeToString(out, Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun buildCookie(name: String, value: String, expiresAt: Long): Cookie {
        return Cookie.Builder()
            .name(name)
            .value(value)
            .domain("bilibili.com")
            .path("/")
            .expiresAt(expiresAt)
            .secure()
            .build()
    }

    private fun encodeCookieDirect(cookie: Cookie): String {
        val sb = StringBuilder()
        sb.append(cookie.name).append("=").append(cookie.value)
        sb.append("; domain=").append(cookie.domain.removePrefix("."))
        sb.append("; path=").append(cookie.path)
        if (cookie.secure) sb.append("; secure")
        if (cookie.expiresAt != Long.MAX_VALUE) sb.append("; expires=").append(cookie.expiresAt)
        return sb.toString()
    }

    private fun hmacSha256Hex(key: String, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val out = mac.doFinal(message.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(out.size * 2)
        for (b in out) sb.append(String.format("%02x", b))
        return sb.toString()
    }

    private fun getCorrespondPath(timestampMs: Long): String {
        val plaintext = "refresh_${timestampMs}"
        val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            correspondPublicKey,
            OAEPParameterSpec(
                "SHA-256",
                "MGF1",
                MGF1ParameterSpec.SHA256,
                PSource.PSpecified.DEFAULT
            )
        )
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return encrypted.joinToString("") { "%02x".format(it) }
    }
}
