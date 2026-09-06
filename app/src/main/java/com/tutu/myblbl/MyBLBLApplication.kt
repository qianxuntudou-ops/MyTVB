package com.tutu.myblbl

import android.app.Application
import android.os.SystemClock
import com.tutu.myblbl.core.common.cache.FileCacheManager
import com.tutu.myblbl.core.common.log.AppLog
import com.tutu.myblbl.core.common.settings.AppSettingsDataStore
import com.tutu.myblbl.core.common.update.ApkUpdater
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
        private const val LEGACY_KEY_DANMAKU_LITE_ENGINE = "danmaku_lite_engine"

        lateinit var instance: MyBLBLApplication
            private set
    }

    /**
     * Android P+ WebView 数据目录隔离（对标参考 `MyApplication.initPieWebView`）。
     * 非 主进程使用 WebView 时设置独立数据目录后缀，避免数据目录冲突崩溃。
     */
    private fun initPieWebViewDataDir() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            val processName = currentProcessName()
            val pkgName = packageName
            // 仅对非主进程设置后缀（主进程用默认目录）
            if (processName != null && processName != pkgName) {
                android.webkit.WebView.setDataDirectorySuffix(processName.substringAfterLast(":"))
                AppLog.i(TAG, "WebView 数据目录隔离: process=$processName")
            }
        }
    }

    private fun currentProcessName(): String? {
        return try {
            val pid = android.os.Process.myPid()
            val am = getSystemService(android.app.ActivityManager::class.java)
            am.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName
        } catch (e: Throwable) { null }
    }
    
    override fun onCreate() {
        val startMs = SystemClock.elapsedRealtime()
        super.onCreate()
        instance = this
        // Android P+ WebView 数据目录隔离（对标参考 MyApplication.initPieWebView）：
        // 多进程使用 WebView 时数据目录冲突会导致 X5/系统 WebView 初始化崩溃。
        initPieWebViewDataDir()
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
            // 清理已下线的"弹幕引擎"设置（老包可能存"关"=功能优先引擎，该引擎已删除，
            // 播放器现已固定轻量引擎）。removeAsync 幂等，key 不存在时不会写盘。
            cleanupLegacyDanmakuEngineSetting()
            // 青少年模式：设置缓存就绪后刷新休息状态，让 restingFlow 反映持久化中的真实值
            com.tutu.myblbl.core.common.content.TeenModeTimer.initOnStartup()
            trace("initNetworkSession", startMs) { initNetworkSession() }
            // 设置缓存已同步就绪：后台扫描缓存目录并按当前上限 trim 一次。
            // 既能收敛旧版 totalSize 计数 bug 遗留的超额，也避免后续 Fragment 首次访问时
            // 才支付扫描成本。init() 内部是 Thread.start() fire-and-forget，不阻塞启动。
            trace("initFileCache", startMs) { initFileCache() }
            // 上一次会话遗留的升级包（update/）已无跨会话用途，启动时后台删掉，
            // 避免几十 MB 的 APK 一直算在"清除缓存"的数字里。
            ApkUpdater.cleanupStaleDownload(this)
            trace("initImageSettings", startMs) { ImageLoader.prewarmSettings() }
            sessionRuntimeReady.set(true)
            AppLog.i(TAG, "STARTUP sessionRuntimeInit end reason=$reason elapsed=${SystemClock.elapsedRealtime() - startMs}ms")
            // 青少年模式：后台预加载公益广告分P列表（失败静默，下次 tick 触发时会再拉）
            CoroutineScope(Dispatchers.IO).launch {
                runCatching {
                    com.tutu.myblbl.core.common.content.PsasRepository.ensureLoaded()
                }
            }
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

    private fun cleanupLegacyDanmakuEngineSetting() {
        KoinPlatform.getKoin().get<AppSettingsDataStore>()
            .removeAsync(LEGACY_KEY_DANMAKU_LITE_ENGINE)
    }

    private fun initFileCache() {
        FileCacheManager.init()
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
            // 等首屏接口响应的空闲期顺手把内容过滤的关键词自动机构建好（首启一次性 ~20ms）。
            // 单开子协程并行：不推迟下面的首屏请求发出。
            launch { com.tutu.myblbl.core.common.content.ContentFilter.prewarm() }
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
