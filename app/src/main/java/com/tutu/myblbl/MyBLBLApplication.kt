package com.tutu.myblbl

import android.app.Application
import android.os.SystemClock
import com.tutu.myblbl.core.common.log.AppLog
import com.tutu.myblbl.core.common.settings.AppSettingsDataStore
import com.tutu.myblbl.core.lifecycle.AppBackgroundMonitor
import com.tutu.myblbl.core.ui.image.ImageLoader
import com.tutu.myblbl.di.appModules
import com.tutu.myblbl.feature.home.RecommendFeedRepository
import com.tutu.myblbl.feature.player.PlayerInstancePool
import com.tutu.myblbl.network.NetworkManager
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.logger.Level
import org.koin.mp.KoinPlatform

class MyBLBLApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val startupPrewarmScheduled = AtomicBoolean(false)
    private val uiRuntimeReady = AtomicBoolean(false)
    private val sessionRuntimeReady = AtomicBoolean(false)
    private val dataRuntimeReady = AtomicBoolean(false)
    private val firstPagePreloadScheduled = AtomicBoolean(false)
    private val uiRuntimeInitLock = Any()
    private val sessionRuntimeInitLock = Any()
    private val dataRuntimeInitLock = Any()
    
    companion object {
        private const val TAG = "AppStartup"

        lateinit var instance: MyBLBLApplication
            private set
    }
    
    override fun onCreate() {
        val startMs = SystemClock.elapsedRealtime()
        super.onCreate()
        instance = this
        AppLog.init(this)
        AppLog.i(TAG, "STARTUP T0 app.onCreate start")
        AppLog.i(TAG, "STARTUP T1 app.onCreate end minimal elapsed=${SystemClock.elapsedRealtime() - startMs}ms")
    }

    fun ensureUiRuntimeReady(reason: String) {
        if (uiRuntimeReady.get()) return
        synchronized(uiRuntimeInitLock) {
            if (uiRuntimeReady.get()) return
            val startMs = SystemClock.elapsedRealtime()
            AppLog.i(TAG, "STARTUP uiRuntimeInit start reason=$reason")
            // UI 首帧只需要 DI 和生命周期监听。设置缓存只启动异步读取，不再卡住 Main 首帧。
            trace("initKoin", startMs) { initKoin() }
            trace("startSettingsCacheAsync", startMs) { startSettingsCacheAsync() }
            trace("initBackgroundMonitor", startMs) { AppBackgroundMonitor.init(this) }
            uiRuntimeReady.set(true)
            AppLog.i(TAG, "STARTUP uiRuntimeInit end reason=$reason elapsed=${SystemClock.elapsedRealtime() - startMs}ms")
        }
    }

    fun ensureSessionRuntimeReady(reason: String) {
        if (sessionRuntimeReady.get()) return
        ensureUiRuntimeReady("$reason/session")
        synchronized(sessionRuntimeInitLock) {
            if (sessionRuntimeReady.get()) return
            val startMs = SystemClock.elapsedRealtime()
            AppLog.i(TAG, "STARTUP sessionRuntimeInit start reason=$reason")
            // 登录态只需要设置缓存、Cookie 和持久化用户资料，不需要先构造 OkHttp/Retrofit。
            trace("initSettingsBlocking", startMs) { initSettingsBlocking(reason) }
            trace("initNetworkSession", startMs) { initNetworkSession() }
            trace("initImageSettings", startMs) { ImageLoader.prewarmSettings() }
            sessionRuntimeReady.set(true)
            AppLog.i(TAG, "STARTUP sessionRuntimeInit end reason=$reason elapsed=${SystemClock.elapsedRealtime() - startMs}ms")
        }
    }

    fun ensureDataRuntimeReady(reason: String) {
        if (dataRuntimeReady.get()) return
        ensureSessionRuntimeReady("$reason/data")
        synchronized(dataRuntimeInitLock) {
            if (dataRuntimeReady.get()) return
            val startMs = SystemClock.elapsedRealtime()
            AppLog.i(TAG, "STARTUP dataRuntimeInit start reason=$reason")
            // HTTP 客户端和 Retrofit 到 Main 壳可见后再准备，避免拉长 bg_splash。
            trace("initNetworkCore", startMs) { initNetworkCore() }
            dataRuntimeReady.set(true)
            AppLog.i(TAG, "STARTUP dataRuntimeInit end reason=$reason elapsed=${SystemClock.elapsedRealtime() - startMs}ms")
        }
    }

    fun ensureMainRuntimeReady(reason: String) {
        ensureDataRuntimeReady(reason)
    }

    private inline fun trace(name: String, appStartMs: Long, block: () -> Unit) {
        val stepStartMs = SystemClock.elapsedRealtime()
        block()
        AppLog.i(
            TAG,
            "$name end step=${SystemClock.elapsedRealtime() - stepStartMs}ms total=${SystemClock.elapsedRealtime() - appStartMs}ms"
        )
    }

    private fun startSettingsCacheAsync() {
        KoinPlatform.getKoin().get<AppSettingsDataStore>().initCache()
    }

    private fun initSettingsBlocking(reason: String) {
        KoinPlatform.getKoin().get<AppSettingsDataStore>().initCacheBlocking(reason)
    }
    
    private fun initKoin() {
        startKoin {
            androidLogger(if (BuildConfig.DEBUG) Level.ERROR else Level.NONE)
            androidContext(this@MyBLBLApplication)
            modules(appModules)
        }
    }
    
    private fun initNetworkSession() {
        NetworkManager.initSession(this, syncWebViewCookies = false)
    }

    private fun initNetworkCore() {
        NetworkManager.init(this, syncWebViewCookies = false)
    }

    fun scheduleStartupFirstPagePreload(delayMillis: Long = 0L) {
        if (!firstPagePreloadScheduled.compareAndSet(false, true)) {
            return
        }
        appScope.launch {
            if (delayMillis > 0) {
                delay(delayMillis)
            }
            val startMs = SystemClock.elapsedRealtime()
            AppLog.i(TAG, "STARTUP firstPagePreload schedule start delay=${delayMillis}ms")
            ensureDataRuntimeReady("firstPagePreload")
            // 推荐首页只依赖本地持久化的 cookies。CookieJar 在 NetworkManager.init 阶段
            // 已经从 SP 加载完毕（initSettings 同步阻塞保证 cache 就绪），此处直接发请求即可。
            // 不再做 WebView cookie 同步：webCookieManager 里的 cookie 全部来自 OkHttp
            // saveFromResponse 反向写入，跟我们的 SP 同源，冷启动期没有任何"新 cookie"
            // 可同步；而 WebView 冷加载在电视上 300~800ms，是纯负担。
            if (NetworkManager.isLoggedIn()) {
                KoinPlatform.getKoin().get<com.tutu.myblbl.event.AppEventHub>()
                    .dispatch(com.tutu.myblbl.event.AppEventHub.Event.UserSessionChanged)
            }
            runCatching {
                KoinPlatform.getKoin().get<RecommendFeedRepository>().preloadFirstPage()
            }.onFailure {
                firstPagePreloadScheduled.set(false)
                AppLog.w(TAG, "STARTUP firstPagePreload failed: ${it.message}")
            }
            AppLog.i(TAG, "STARTUP firstPagePreload schedule end elapsed=${SystemClock.elapsedRealtime() - startMs}ms")
        }
    }

    fun scheduleDeferredSessionPrewarm(delayMillis: Long = 300L) {
        if (!startupPrewarmScheduled.compareAndSet(false, true)) {
            return
        }
        appScope.launch {
            if (delayMillis > 0) {
                delay(delayMillis)
            }
            ensureDataRuntimeReady("sessionPrewarm")
            val startMs = SystemClock.elapsedRealtime()
            if (NetworkManager.isLoggedIn()) {
                AppLog.i(TAG, "STARTUP sessionPrewarm start")
                val success = NetworkManager.prewarmWebSession()
                AppLog.i(
                    TAG,
                    "STARTUP sessionPrewarm end success=$success elapsed=${SystemClock.elapsedRealtime() - startMs}ms"
                )
            } else {
                AppLog.i(TAG, "STARTUP sessionPrewarm skip loggedOut")
                startupPrewarmScheduled.set(false)
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        when {
            level >= 80 -> { // TRIM_MEMORY_COMPLETE
                ImageLoader.clearMemory(this)
                if (!PlayerInstancePool.isAttached()) {
                    PlayerInstancePool.releaseNow("trimMemory_complete")
                }
            }
            level >= 60 -> { // TRIM_MEMORY_MODERATE
                ImageLoader.clearMemory(this)
            }
            level >= 10 -> { // TRIM_MEMORY_RUNNING_LOW
                if (!PlayerInstancePool.isAttached()) {
                    PlayerInstancePool.releaseNow("trimMemory_running_low")
                }
            }
        }
    }
}
