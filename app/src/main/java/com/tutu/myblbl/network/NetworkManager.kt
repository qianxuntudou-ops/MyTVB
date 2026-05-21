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
    private val sessionInitLock = Any()
    private val coreInitLock = Any()

    @Volatile
    private var sessionInitialized = false

    @Volatile
    private var coreInitialized = false

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
        // 显式不挂 okhttp3.Cache：
        // - HeaderInterceptor 给所有请求强制 Cache-Control: max-age=0，本身就要 revalidate；
        // - B 站推荐/动态/搜索这些主力 API 不返回 ETag/Last-Modified，服务器只会回 200，
        //   永远命中不了 304，cache 实际只写不读；
        // - 但每次冷启动都要付 100~200ms 打开 DiskLruCache journal（debug.txt 实测 175ms
        //   contention，落在 IO 协程上拉长 getRecommendList 的首包时延）。
        // 真正需要 disk 缓存的是图片，那边已经走 Coil 的独立 DiskCache，不依赖这里。
        NetworkClientFactory.createOkHttpClient(
            cookieManager = internalCookieManager,
            userAgentProvider = { currentUserAgentValue },
            acceptLanguageProvider = { getAcceptLanguage() },
            cacheDir = null,
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

    fun initSession(context: Context, syncWebViewCookies: Boolean = true) {
        if (sessionInitialized) return
        synchronized(sessionInitLock) {
            if (sessionInitialized) return
            val startMs = android.os.SystemClock.elapsedRealtime()
            val applicationContext = context.applicationContext
            appContext = applicationContext
            internalCookieManager.init(applicationContext, syncWebViewCookies)
            userAgentStore.init(applicationContext)
            sessionStore.initPersistence(
                applicationContext.getSharedPreferences("network_session_store", Context.MODE_PRIVATE)
            )
            sessionInitialized = true
            AppLog.i(TAG, "initSession elapsed=${android.os.SystemClock.elapsedRealtime() - startMs}ms")
        }
    }

    fun init(context: Context, syncWebViewCookies: Boolean = true) {
        initSession(context, syncWebViewCookies)
        if (coreInitialized) return
        synchronized(coreInitLock) {
            if (coreInitialized) return
            val startMs = android.os.SystemClock.elapsedRealtime()
            val applicationContext = context.applicationContext
            appContext = applicationContext
            // 在后台一次性构造 OkHttp / Retrofit / ApiService。
            // 这样首个真实请求不会和 UI 主线程争 SynchronizedLazyImpl 锁。
            internalOkHttpClient
            retrofit
            apiService
            BiliClient.init(internalOkHttpClient)
            // 旧版本（< 这次重构前）的 OkHttp HTTP cache 现已不再使用，残留目录可能占几十 MB。
            // 异步清理，不阻塞主线程：deleteRecursively 在低端 TV 上同步执行 50~200ms。
            scheduleHttpCacheCleanup(applicationContext)
            coreInitialized = true
            AppLog.i(TAG, "initNetworkCore elapsed=${android.os.SystemClock.elapsedRealtime() - startMs}ms")
        }
    }

    private fun scheduleHttpCacheCleanup(applicationContext: Context) {
        Thread({
            val sp = applicationContext.getSharedPreferences(
                "network_http_cache_meta",
                Context.MODE_PRIVATE
            )
            if (sp.getInt(KEY_HTTP_CACHE_SCHEMA, 0) >= HTTP_CACHE_SCHEMA) return@Thread
            runCatching {
                java.io.File(applicationContext.cacheDir, "http_cache").deleteRecursively()
            }
            sp.edit().putInt(KEY_HTTP_CACHE_SCHEMA, HTTP_CACHE_SCHEMA).apply()
        }, "http-cache-cleanup").apply {
            priority = Thread.MIN_PRIORITY
            isDaemon = true
        }.start()
    }

    /**
     * 触发 lazy 字段初始化：OkHttp client、Gson、Retrofit、ApiService。
     * 实际上 [init] 里已经主线程同步触发过；这里保留是为了向下兼容老代码。
     */
    fun warmUp() {
        internalOkHttpClient
        gson
        retrofit
        apiService
    }

    // 已删除 preheatApiHosts()：实测两个 appScope.launch 并发启动时 preheat 抢不过 preload，
    // 收益为 0；如果将来想真正预连，需要让 preheat 早于 preloadFirstPage 调用且独占初始 connection。

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
