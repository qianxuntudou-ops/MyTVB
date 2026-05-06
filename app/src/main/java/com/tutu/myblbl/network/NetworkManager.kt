@file:Suppress("SpellCheckingInspection")

package com.tutu.myblbl.network

import android.content.Context
import com.tutu.myblbl.model.BaseResponse
import com.tutu.myblbl.model.user.UserDetailInfoModel
import com.tutu.myblbl.network.api.ApiService
import com.tutu.myblbl.network.http.NetworkClientFactory
import com.tutu.myblbl.network.response.Base2Response
import com.tutu.myblbl.network.response.BaseBaseResponse
import com.tutu.myblbl.network.security.AppSignUtils
import com.tutu.myblbl.network.security.BiliSecurityCoordinator
import com.tutu.myblbl.network.session.AuthContext
import com.tutu.myblbl.network.session.NetworkSessionStore
import com.tutu.myblbl.network.ua.DesktopUserAgentStore
import com.tutu.myblbl.core.common.log.AppLog
import com.tutu.myblbl.core.common.settings.AppSettingsDataStore
import com.tutu.myblbl.network.cookie.CookieManager
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.koin.mp.KoinPlatform
import retrofit2.Retrofit

object NetworkManager {

    private const val TAG = "NetworkManager"
    private const val API_BASE = "https://api.bilibili.com/"
    private const val DEFAULT_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36"
    private const val PREF_NAME = "app_settings"
    private const val KEY_CURRENT_UA = "currentUA"
    private const val AUTH_INVALID_CODE = -101
    private const val KEY_REFRESH_TOKEN = "bili_refresh_token"
    private const val KEY_HTTP_CACHE_SCHEMA = "http_cache_schema"
    /** 修改 HTTP 协商缓存结构时递增此值，可以在用户下次冷启动时一次性清空旧缓存。*/
    private const val HTTP_CACHE_SCHEMA = 1

    private var appContext: Context? = null

    private val userAgentStore = DesktopUserAgentStore(
        defaultUserAgent = DEFAULT_UA,
        preferenceName = PREF_NAME,
        preferenceKey = KEY_CURRENT_UA
    )
    private val sessionStore = NetworkSessionStore(authInvalidCode = AUTH_INVALID_CODE)

    private val currentUserAgentValue: String
        get() = userAgentStore.getCurrentUserAgent()

    private val internalCookieManager: CookieManager by lazy { CookieManager() }

    private val internalOkHttpClient: OkHttpClient by lazy {
        val settings: AppSettingsDataStore? = runCatching {
            KoinPlatform.getKoin().get<AppSettingsDataStore>()
        }.getOrNull()
        NetworkClientFactory.createOkHttpClient(
            cookieManager = internalCookieManager,
            userAgentProvider = { currentUserAgentValue },
            acceptLanguageProvider = { getAcceptLanguage() },
            cacheDir = appContext?.cacheDir,
            ipv4OnlyEnabled = { settings?.getCachedString("ipv4_only") != "关" },
            deviceBuvidProvider = { internalCookieManager.getCookieValue("buvid3").orEmpty() }
        )
    }

    private val noCookieOkHttpClient: OkHttpClient by lazy {
        internalOkHttpClient.newBuilder()
            .cookieJar(okhttp3.CookieJar.NO_COOKIES)
            .build()
    }

    private val gson by lazy {
        NetworkClientFactory.createGson()
    }

    private val retrofit: Retrofit by lazy {
        NetworkClientFactory.createRetrofit(
            baseUrl = API_BASE,
            client = internalOkHttpClient,
            gson = gson
        )
    }

    private val noCookieRetrofit: Retrofit by lazy {
        NetworkClientFactory.createRetrofit(
            baseUrl = API_BASE,
            client = noCookieOkHttpClient,
            gson = gson
        )
    }

    val apiService: ApiService by lazy {
        retrofit.create(ApiService::class.java)
    }

    val noCookieApiService: ApiService by lazy {
        noCookieRetrofit.create(ApiService::class.java)
    }

    private val securityCoordinator: BiliSecurityCoordinator by lazy {
        BiliSecurityCoordinator(
            tag = TAG,
            apiService = apiService,
            noCookieApiService = noCookieApiService,
            okHttpClient = internalOkHttpClient,
            cookieManager = internalCookieManager,
            userAgentProvider = { currentUserAgentValue },
            refreshUserAgent = ::refreshUserAgent,
            syncUserSession = ::syncUserSession,
            refreshTokenProvider = { getRefreshToken() },
            refreshTokenSaver = { token -> saveRefreshToken(token) },
            updateWbiKeys = { img, sub -> sessionStore.setWbiInfo(img, sub) }
        )
    }

    fun init(context: Context, syncWebViewCookies: Boolean = true) {
        val applicationContext = context.applicationContext
        appContext = applicationContext
        maybeMigrateHttpCache(applicationContext)
        internalCookieManager.init(applicationContext, syncWebViewCookies)
        userAgentStore.init(applicationContext)
        sessionStore.initPersistence(
            applicationContext.getSharedPreferences("network_session_store", Context.MODE_PRIVATE)
        )
    }

    /**
     * HTTP 协商缓存默认在冷启动间复用，只有 schema 升级时才一次性清空，
     * 否则像首页推荐这种带 max-age 的接口每次启动都会全量重下，电视上尤其影响首屏。
     *
     * 使用 SharedPreferences 同步读写：AppSettingsDataStore 的 initCache 是异步的，
     * 冷启动早期还未加载完毕时读 schema 会误判为 0 而把缓存删干净。
     */
    private fun maybeMigrateHttpCache(applicationContext: Context) {
        val sp = applicationContext.getSharedPreferences(
            "network_http_cache_meta",
            Context.MODE_PRIVATE
        )
        val current = sp.getInt(KEY_HTTP_CACHE_SCHEMA, 0)
        if (current >= HTTP_CACHE_SCHEMA) return
        runCatching {
            java.io.File(applicationContext.cacheDir, "http_cache").deleteRecursively()
        }
        sp.edit().putInt(KEY_HTTP_CACHE_SCHEMA, HTTP_CACHE_SCHEMA).apply()
    }

    /**
     * 触发 lazy 字段初始化：OkHttp client、Gson、Retrofit、ApiService。
     * 不做 DNS / TLS 预连（首次 API 请求自身即承担首包建连，预连接对 TV
     * 上 connection pool 5min 过期窗口内的复用增益有限，反而占用一次连接配额）。
     */
    fun warmUp() {
        internalOkHttpClient
        gson
        retrofit
        apiService
    }

    fun syncCookiesFromWebView() {
        internalCookieManager.syncFromWebView()
    }

    fun setWbiInfo(imgKey: String, subKey: String) {
        sessionStore.setWbiInfo(imgKey, subKey)
    }

    fun getWbiKeys(): Pair<String, String> {
        return sessionStore.getWbiKeys()
    }

    fun areWbiKeysStale(): Boolean {
        return sessionStore.areWbiKeysStale()
    }

    fun getCookieManager(): CookieManager = internalCookieManager

    fun getCsrfToken(): String {
        return internalCookieManager.getCsrfToken()
    }

    fun isLoggedIn(): Boolean {
        return internalCookieManager.hasSessionCookie()
    }

    fun clearUserSession(clearCookies: Boolean = true, reason: String = "unknown") {
        sessionStore.clearUserSession()
        resetSessionLifecycleState(clearCookies = clearCookies, reason = reason)
    }

    private fun softClearUserSession(reason: String) {
        sessionStore.softClearUserSession()
        securityCoordinator.resetRuntimeState()
        AppLog.w(TAG, "softClearUserSession: reason=$reason")
    }

    suspend fun tryRecoverExpiredSession(): Boolean {
        if (!internalCookieManager.hasSessionCookie()) {
            AppLog.w(TAG, "tryRecoverExpiredSession: no SESSDATA, cannot recover")
            hardClearAndNotify("recovery_no_sessdata")
            return false
        }
        if (getRefreshToken().isNullOrBlank()) {
            AppLog.w(TAG, "tryRecoverExpiredSession: no refresh_token, cannot recover")
            hardClearAndNotify("recovery_no_refresh_token")
            return false
        }
        return try {
            securityCoordinator.forceCookieRefresh()
            val navResponse = apiService.getUserDetailInfo()
            if (navResponse.isSuccess && navResponse.data != null) {
                sessionStore.updateUserSession(navResponse.data)
                AppLog.i(TAG, "tryRecoverExpiredSession: session recovered successfully")
                notifySessionChanged()
                true
            } else if (navResponse.code == -101) {
                AppLog.w(TAG, "tryRecoverExpiredSession: still -101 after refresh, session truly expired")
                hardClearAndNotify("recovery_still_expired")
                false
            } else {
                AppLog.w(TAG, "tryRecoverExpiredSession: nav check failed after refresh, code=${navResponse.code}")
                hardClearAndNotify("recovery_nav_failed")
                false
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "tryRecoverExpiredSession: exception during recovery", e)
            hardClearAndNotify("recovery_exception")
            false
        }
    }

    private fun hardClearAndNotify(reason: String) {
        clearUserSession(clearCookies = true, reason = reason)
        notifySessionChanged()
    }

    private fun notifySessionChanged() {
        runCatching {
            KoinPlatform.getKoin().get<com.tutu.myblbl.event.AppEventHub>()
        }.getOrNull()
            ?.dispatch(com.tutu.myblbl.event.AppEventHub.Event.UserSessionChanged)
    }

    private fun resetSessionLifecycleState(clearCookies: Boolean, reason: String) {
        if (clearCookies) {
            internalCookieManager.clearCookies()
            clearRefreshToken()
        }
        securityCoordinator.resetRuntimeState()
    }

    private fun getRefreshToken(): String? {
        return runCatching {
            KoinPlatform.getKoin().get<AppSettingsDataStore>().getCachedString(KEY_REFRESH_TOKEN)
        }.getOrNull()
    }

    private fun saveRefreshToken(token: String) {
        runCatching {
            KoinPlatform.getKoin().get<AppSettingsDataStore>().putStringAsync(KEY_REFRESH_TOKEN, token)
        }.onFailure {
            AppLog.e(TAG, "saveRefreshToken failed: ${it.message}")
        }
    }

    private fun clearRefreshToken() {
        runCatching {
            KoinPlatform.getKoin().get<AppSettingsDataStore>().putStringAsync(KEY_REFRESH_TOKEN, null)
        }
    }

    fun clearWebRefreshToken() {
        clearRefreshToken()
    }

    suspend fun activateAfterLogin() {
        securityCoordinator.activateAfterLogin()
    }

    suspend fun ensureWebFingerprintCookies() {
        securityCoordinator.ensureWebFingerprintCookies()
    }

    fun saveLoginRefreshToken(token: String) {
        saveRefreshToken(token)
    }

    fun getOkHttpClient(): OkHttpClient = internalOkHttpClient

    fun getCurrentUserAgent(): String = currentUserAgentValue

    fun getAcceptLanguage(): String {
        return userAgentStore.getAcceptLanguage()
    }

    fun refreshUserAgent(): String {
        val newUserAgent = userAgentStore.refreshUserAgent(appContext)
        return newUserAgent
    }

    suspend fun ensureHealthyForPlay() {
        securityCoordinator.ensureHealthyForPlay()
    }

    suspend fun forceCookieRefresh() {
        securityCoordinator.forceCookieRefresh()
    }

    suspend fun prewarmWebSession(forceUaRefresh: Boolean = false): Boolean {
        return securityCoordinator.prewarmWebSession(forceUaRefresh)
    }

    fun buildPiliWebHeaders(targetUrl: String, includeCookie: Boolean = true): Map<String, String> {
        return securityCoordinator.buildPiliWebHeaders(targetUrl, includeCookie)
    }

    suspend fun ensureWbiKeys() {
        securityCoordinator.ensureWbiKeys()
    }

    fun getUserInfo(): UserDetailInfoModel? {
        return sessionStore.getUserInfo()
    }

    fun updateUserSession(info: UserDetailInfoModel?) {
        sessionStore.updateUserSession(info)
    }

    fun syncUserSession(
        response: BaseResponse<UserDetailInfoModel>,
        source: String,
        context: AuthContext = AuthContext.FOREGROUND
    ): UserDetailInfoModel? {
        val info = sessionStore.syncUserSession(response, context) {
            softClearUserSession(reason = "$source code=${response.code}")
        }
        if (info != null) {
            return info
        }
        return null
    }

    fun handleAuthFailureCode(code: Int, source: String) {
        sessionStore.handleAuthFailureCode(code) {
            softClearUserSession(reason = "$source code=$code")
        }
    }

    fun <T> syncAuthState(
        response: BaseResponse<T>,
        source: String,
        context: AuthContext = AuthContext.FOREGROUND
    ): BaseResponse<T> {
        return sessionStore.syncAuthState(response, context) {
            softClearUserSession(reason = "$source code=${response.code}")
        }
    }

    fun syncAuthState(
        response: BaseBaseResponse,
        source: String,
        context: AuthContext = AuthContext.FOREGROUND
    ): BaseBaseResponse {
        return sessionStore.syncAuthState(response, context) {
            softClearUserSession(reason = "$source code=${response.code}")
        }
    }

    fun <T> syncAuthState(
        response: Base2Response<T>,
        source: String,
        context: AuthContext = AuthContext.FOREGROUND
    ): Base2Response<T> {
        return sessionStore.syncAuthState(response, context) {
            softClearUserSession(reason = "$source code=${response.code}")
        }
    }

    suspend fun postFormJson(
        url: String,
        form: Map<String, String>,
        extraHeaders: Map<String, String>? = null
    ): JSONObject {
        return securityCoordinator.postFormJson(url, form, extraHeaders)
    }

    suspend fun requestJson(
        url: String,
        extraHeaders: Map<String, String>? = null
    ): JSONObject {
        return securityCoordinator.requestJson(url, extraHeaders)
    }
}
