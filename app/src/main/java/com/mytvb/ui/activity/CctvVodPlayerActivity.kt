package com.mytvb.ui.activity

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import com.mytvb.R
import com.mytvb.core.common.log.AppLog
import com.mytvb.core.ui.base.AppToast
import com.mytvb.core.ui.base.BaseActivity
import com.mytvb.core.ui.base.ChoiceDialog
import com.mytvb.core.ui.system.ViewUtils
import com.mytvb.databinding.ActivityCctvVodPlayerBinding
import com.mytvb.feature.cctv.vod.CctvEpisode
import com.mytvb.feature.cctv.vod.CctvVodApi
import com.mytvb.feature.cctv.vod.CctvVodProgressStore
import com.mytvb.feature.player.settings.PlayerSettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.koin.android.ext.android.inject
import java.util.Locale

/**
 * 央视点播播放页：**WebView 载入央视自己的详情页负责解码播放，交互层由我们自己实现**。
 *
 * 为什么这样组合（2026-10-18 定稿）：
 * - 央视网页端的高码率转码源（720P）Android 解码器解不出来（硬解/软解/同步异步/Surface/Texture
 *   全部试过，一律花屏），只有官方播放器能正常播，所以解码必须交给 WebView；
 * - 但官方播放器的控件只认鼠标/触摸，遥控器按任何键都没反应（实测），TV 盒子没法用，
 *   所以播放控制与菜单（播放/暂停、快进快退、画质、选集）由我们自己的控制条承担，
 *   再通过 `evaluateJavascript` 驱动官方播放器。
 */
class CctvVodPlayerActivity : BaseActivity<ActivityCctvVodPlayerBinding>() {

    private val okHttpClient: OkHttpClient by inject()
    private val api: CctvVodApi by lazy { CctvVodApi(okHttpClient) }

    /** WebView 宿主：装了 X5 内核优先用 X5，否则回退系统 WebView。 */
    private lateinit var webHost: CctvVodWebViewHost

    private val handler = Handler(Looper.getMainLooper())
    private var pageUrl = ""
    private var albumId = ""
    private var episodeMode = 0
    private var episodes: List<CctvEpisode> = emptyList()
    private var episodesJob: Job? = null

    private var durationMs = 0L
    private var positionMs = 0L
    private var exitTime = 0L
    /** 本次触摸序列是否始于左上返回按钮（是则不旁听单点手势切换控制条）。 */
    private var touchOnBackButton = false
    /** 用户意图是否为暂停。页面侧会自己恢复播放（注入脚本重跑 / 换流后 autoplay），按此意图压回去。 */
    private var userPaused = false
    /** 最近一次页面回传的暂停状态，用于推导用户意图。 */
    private var pagePaused = false
    private var lastPauseCorrectAt = 0L
    /** 是否已拿到流元数据（duration>0）：用于加载超时判定与错误提示清除。 */
    private var playbackStarted = false
    /** 是否已把默认流拉到最高档（页面初始流只有 360P，菜单却标"超清/当前"）。 */
    private var qualityAutoApplied = false
    /** 最近一次处理返回键的时间：老设备走 KeyEvent、Android 13+ 走 OnBackInvoked 通道，用于去重。 */
    private var lastBackHandledAt = 0L
    private var playbackSession = 0
    /** 用户正在拖动进度条：此时不要用回传的播放位置覆盖进度，否则手指会被"拽回去"。 */
    private var seekingByUser = false
    /** 待续播位置（毫秒）：页面里视频可播后一次性 seek 过去。 */
    private var pendingResumeMs = 0L
    private var resumeApplied = false
    /** 本集播完后是否已触发连播（页面会反复回传 ended，需防重入）。 */
    private var autoNextTriggered = false

    /** 片尾页面收敛是否已执行：ended 状态每秒回传一次，防重复注入 restyle。 */
    private var endedRestyleApplied = false

    /** 最近一次文档开始加载时刻：片尾官方页面整文档自重载，会让 restarted 信号假阳性。 */
    private var lastPageStartedAt = 0L

    /** 最近一次文档开始加载的 URL：官方片尾连播走整文档跳转，按它精确确认换集。 */
    private var lastPageStartedUrl = ""
    private var currentEpisodeIndex = 0
    private var episodeTitle = ""
    private var saveTickCounter = 0
    /** 起播耗时打点：本次加载（进页/切集）开始时刻与各阶段开关（见 loadStage 日志）。 */
    private var loadStartAt = 0L
    private var loadStageFirstState = false
    private var loadStageReady2 = false
    private var qualitySwitchAt = 0L
    private var loadStageQualitySettled = false
    /** 上一轮回传的位置，用于识别"官方页面自己连播了"。 */
    private var lastPositionMs = 0L
    /** 最近一次页面状态回传时间（elapsedRealtime），用于检测 JS 线程卡死。 */
    private var lastStateAt = 0L
    /** 当前画质在官方控制条菜单里的下标（0=超清 / 1=高清 / 2=标清 / 3=流畅）。 */
    private var currentQualityIndex = 0
    private var customView: View? = null

    private val progressTicker = object : Runnable {
        override fun run() {
            updateProgress()
            // 页面侧每秒回传一次状态；超过 3 秒没有回传说明 WebView 的 JS 线程被卡住
            // （X5 上会表现为音频继续、画面冻结），打日志便于真机定位。
            val now = android.os.SystemClock.elapsedRealtime()
            if (lastStateAt > 0 && now - lastStateAt > 3_000L) {
                AppLog.w(TAG, "stateGap ${(now - lastStateAt) / 1000}s pos=${positionMs / 1000}s")
            }
            handler.postDelayed(this, PROGRESS_INTERVAL_MS)
        }
    }

    private val hideControllerRunnable = Runnable { hideController() }

    /** 「时间常驻显示」开关：开时右上角常驻时钟（顶栏呼出时避让）。 */
    private var alwaysOnClockEnabled = false

    /**
     * 加载超时兜底：老设备上页面播放器可能初始化失败（video 元素在但永远 ready=0），
     * 不给提示的话用户只能看转圈。拿到元数据（duration>0）即视为起播成功。
     */
    private val loadTimeoutRunnable = Runnable {
        if (!playbackStarted && !isFinishing && !isDestroyed) {
            AppLog.w(TAG, "loadTimeout after ${LOAD_TIMEOUT_MS}ms")
            showError(getString(R.string.cctv_vod_stream_failed))
        }
    }

    private val gestureDetector by lazy {
        android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            /** 双击：播放 / 暂停（视频播放器的通用交互）。 */
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (binding.controller.visibility == View.VISIBLE) {
                    val rect = android.graphics.Rect()
                    if (binding.controller.getGlobalVisibleRect(rect) &&
                        rect.contains(e.x.toInt(), e.y.toInt())
                    ) {
                        return false
                    }
                }
                showController()
                togglePlayPause()
                return true
            }

            /** 单击：切换控制条显隐（双击不会走到这里，GestureDetector 已区分）。 */
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (binding.controller.visibility == View.VISIBLE) {
                    val rect = android.graphics.Rect()
                    if (binding.controller.getGlobalVisibleRect(rect) &&
                        rect.contains(e.x.toInt(), e.y.toInt())
                    ) {
                        return false
                    }
                    hideController()
                } else {
                    showController()
                }
                return true
            }
        })
    }

    override fun getViewBinding(): ActivityCctvVodPlayerBinding {
        return ActivityCctvVodPlayerBinding.inflate(layoutInflater)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pageUrl = intent.getStringExtra(EXTRA_PAGE_URL).orEmpty()
        albumId = intent.getStringExtra(EXTRA_ALBUM_ID).orEmpty()
        episodeMode = intent.getIntExtra(EXTRA_EPISODE_MODE, 0)
        currentEpisodeIndex = intent.getIntExtra(EXTRA_EPISODE_INDEX, 0)
        episodeTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        binding.textTitle.text = episodeTitle
        pendingResumeMs = CctvVodProgressStore.load(this, albumId, currentEpisodeIndex)
        AppLog.i(TAG, "resumePlan album=$albumId idx=$currentEpisodeIndex pending=$pendingResumeMs")
        binding.playerRoot.requestFocus()
        // 「时间常驻显示」：开关开时右上角常驻时钟（顶栏呼出时避让，顶栏本身带时钟）
        alwaysOnClockEnabled = PlayerSettingsStore.load(this).showAlwaysOnClock
        binding.alwaysClock.visibility = if (alwaysOnClockEnabled) View.VISIBLE else View.GONE
        setupButtons()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = handleBackKey()
        })
        ViewUtils.keepScreenOn(this, true)
        setupWebHost()
        installFocusGuard()
        if (pageUrl.isBlank()) {
            showError(getString(R.string.cctv_vod_stream_failed))
            return
        }
        resetLoadStage()
        AppLog.i(TAG, "load page=$pageUrl")
        showLoading(true)
        webHost.loadUrl(pageUrl)
        // 预加载分集列表：既让选集菜单秒开，也是识别"官方自己连播到第几集"的唯一依据
        preloadEpisodes()
        scheduleControllerHide()
        handler.postDelayed(loadTimeoutRunnable, LOAD_TIMEOUT_MS)
    }

    private fun preloadEpisodes() {
        if (albumId.isBlank() || episodes.isNotEmpty() || episodesJob?.isActive == true) return
        episodesJob = lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) { api.fetchEpisodes(albumId, episodeMode) }
            if (isFinishing || isDestroyed) return@launch
            if (list.isNotEmpty()) {
                episodes = list
                AppLog.i(TAG, "episodesPreloaded size=${list.size}")
            }
        }
    }

    private fun setupButtons() {
        binding.buttonBack.touchPressFeedback = true
        binding.buttonBack.setOnClickListener { finish() }
        binding.buttonPlay.setOnClickListener { togglePlayPause() }
        binding.buttonQuality.setOnClickListener { showQualityDialog() }
        binding.buttonEpisode.setOnClickListener { showEpisodeDialog() }
        binding.seekBar.addListener(object : androidx.media3.ui.TimeBar.OnScrubListener {
            override fun onScrubStart(timeBar: androidx.media3.ui.TimeBar, position: Long) {
                seekingByUser = true
                handler.removeCallbacks(hideControllerRunnable)
            }

            override fun onScrubMove(timeBar: androidx.media3.ui.TimeBar, position: Long) {
                // 拖动过程中先本地预览时间，松手才真正跳转
                binding.textPosition.text = formatTime(position)
            }

            override fun onScrubStop(
                timeBar: androidx.media3.ui.TimeBar,
                position: Long,
                canceled: Boolean
            ) {
                seekingByUser = false
                js("seekTo", position)
                scheduleControllerHide()
            }
        })
        // 自动隐藏不因焦点停留而取消：按键会重置 5 秒计时，停手后控制条隐藏、
        // 焦点由 hideController() 收回播放区（与普通播放器一致）。
        updateQualityButton(getString(R.string.cctv_quality))
    }

    /** 控制条上的播放/暂停图标随实际状态切换（暂停时显示播放三角）。 */
    private fun updatePlayPauseIcon(paused: Boolean) {
        val res = if (paused) R.drawable.ic_play else R.drawable.ic_pause
        if (binding.buttonPlay.tag != res) {
            binding.buttonPlay.tag = res
            binding.buttonPlay.setImageResource(res)
        }
    }

    /** 右下角大暂停图标：与普通视频播放器的暂停指示同款，暂停且已就绪时显示。 */
    private fun updatePauseIndicator(paused: Boolean, ready: Int) {
        binding.imagePauseIndicator.visibility =
            if (paused && ready >= 2) View.VISIBLE else View.GONE
    }

    override fun onStart() {
        super.onStart()
        handler.post(progressTicker)
    }

    override fun onStop() {
        handler.removeCallbacks(progressTicker)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        webHost.onResume()
        ViewUtils.keepScreenOn(this, true)
        // 暂停状态下从后台回来不要自动续播
        if (!userPaused) js("play")
        // X5 的 onResume 会把焦点吸回网页层，回到页面后重新确认焦点归属
        binding.playerRoot.post {
            if (isFinishing || isDestroyed) return@post
            val cur = currentFocus
            if (cur == null || webHost.isInsideWebView(cur)) focusView(binding.playerRoot, "resume")
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        val cur = currentFocus
        if (cur != null && webHost.isInsideWebView(cur)) {
            // 窗口焦点回来时若仍停在网页层（X5 的独立窗口关闭后常见），收回到播放区
            focusView(binding.playerRoot, "windowFocus")
        }
    }

    override fun onPause() {
        persistProgress()
        webHost.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        persistProgress()
        handler.removeCallbacksAndMessages(null)
        episodesJob?.cancel()
        playbackSession++
        hideCustomView()
        webHost.removeJavascriptInterface(BRIDGE_NAME)
        webHost.destroy()
        ViewUtils.keepScreenOn(this, false)
        super.onDestroy()
    }

    // ---------------------------------------------------------------- WebView

    /** 与播放无关、且会以 document.write 串行阻塞解析的脚本（按 URL 路径片段匹配）。 */
    private fun isUnrelatedScript(url: String): Boolean =
        SKIP_SCRIPT_KEYWORDS.any { url.contains(it) }

    /**
     * 广告投放请求（issue#105）：galaxy.bjcathay.com 是央视网广告系统（galaxy），点播页
     * 的暂停/片尾/角标/横幅广告位都按排期向它拉投放（实测 URL 带频道/专辑/视频定向参数），
     * 拉到什么页面就往播放器里叠什么（电视剧频道预告横幅、商业角标等）。拦成空响应即可：
     * 广告拉不到＝不投放，官方脚本对空投放本就有容错（广告位空置是常态）。
     */
    private fun isAdRequest(url: String): Boolean = url.contains("galaxy.bjcathay.com")

    /**
     * 老内核兜底：自己请求主文档，在 `<head>` 后插入 postMessage 补丁再交给 WebView。
     * 必须透传 Cookie（页面风控标识）与 Set-Cookie（HMF_CI 等），否则播放器取流会失败。
     */
    private fun injectIntoMainDocument(url: String, cookie: String?): ByteArray? = runCatching {
        val builder = okhttp3.Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
        if (!cookie.isNullOrBlank()) builder.header("Cookie", cookie)
        okHttpClient.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                AppLog.w(TAG, "injectMainDocument http=${response.code}")
                return@use null
            }
            val html = response.body?.string() ?: return@use null
            val script = "<script>$POST_MESSAGE_PATCH_SCRIPT</script>"
            val injected = when {
                html.contains("<head", ignoreCase = true) ->
                    html.replaceFirst(Regex("(?i)<head[^>]*>"), "\$0$script")
                html.contains("<html", ignoreCase = true) ->
                    html.replaceFirst(Regex("(?i)<html[^>]*>"), "\$0$script")
                else -> script + html
            }
            response.headers("Set-Cookie").forEach { webHost.setCookie(url, it) }
            AppLog.i(TAG, "injectMainDocument size=${html.length} ok=${injected != html}")
            injected.toByteArray(Charsets.UTF_8)
        }
    }.onFailure { AppLog.w(TAG, "injectMainDocument failed ${it.message}") }.getOrNull()

    /**
     * 尽早注入 postMessage 兼容补丁：新 WebView 走文档开始注入（先于页面脚本），
     * 不支持的旧版（含 X5 内核）退回 onPageStarted 尽力注入（补丁本身幂等）。
     */
    /**
     * 创建 WebView 宿主（X5 优先、失败回退系统）并挂上全部回调。
     * 两个内核统一走主文档注入：补丁必须早于页面脚本，而老设备（Chrome 66）
     * 的文档开始注入/onPageStarted 注入都会落在旧文档上。
     */
    private fun setupWebHost() {
        webHost = CctvVodWebViewHost(
            context = this,
            bridgeName = BRIDGE_NAME,
            bridge = Bridge(),
            userAgent = USER_AGENT,
            listener = object : CctvVodWebViewHost.Listener {
                override fun onPageStarted(url: String?) {
                    AppLog.i(TAG, "pageStarted url=$url")
                    lastPageStartedAt = android.os.SystemClock.elapsedRealtime()
                    lastPageStartedUrl = url.orEmpty()
                    // 幂等补丁：能赶在页面脚本前执行就赚到，赶不上还有主文档注入兜底
                    webHost.evaluateJavascript(POST_MESSAGE_PATCH_SCRIPT)
                    // 官方连播是页面自己整文档跳转到下一集 URL（issue#105），片尾还有
                    // 本集自重载：这两条路径都不经过 switchEpisode，若不在此盖层，
                    // 新文档从加载到播放器出画期间网页原文（含广告位）直接暴露给用户。
                    // 与进页/手动切集统一为「文档开始加载即盖层，起播(ready>=2)自动收」；
                    // showLoading 幂等，手动切集已盖过时重复调用无副作用。
                    showLoading(true)
                }

                override fun onPageFinished(url: String?) {
                    AppLog.i(TAG, "pageFinished url=$url")
                    injectPlayerModeRepeatedly()
                    // 官方页面在片尾会自行重载文档（实测：露出完整网页布局、状态回传
                    // 中断 stateGap 持续增长）。此时 playbackStarted 已为 true，上面的
                    // 注入轮询会全部跳过——补两发延迟注入把 postMessage 补丁、页面收敛
                    // 与状态回传定时器装到新文档上；第二发兜底补上第一发之后才出现的
                    // video 元素（其 autoplay/监听初始化是按元素幂等的）。
                    val session = playbackSession
                    INJECT_RELOAD_DELAYS_MS.forEach { delay ->
                        handler.postDelayed({
                            if (session != playbackSession || isFinishing || isDestroyed) {
                                return@postDelayed
                            }
                            webHost.evaluateJavascript(PLAYER_MODE_SCRIPT) { result ->
                                AppLog.i(TAG, "inject tail-reload +${delay}ms result=$result")
                            }
                        }, delay)
                    }
                }

                override fun onConsole(message: String) {
                    AppLog.i(TAG, "webConsole $message")
                }

                override fun onMainFrameError(description: String) {
                    AppLog.e(TAG, "webError desc=$description")
                    showError(getString(R.string.cctv_vod_load_failed))
                }

                override fun onShowCustomView(view: View) {
                    if (customView != null) return
                    customView = view
                    binding.playerRoot.addView(
                        view,
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    )
                    webHost.setVisible(false)
                }

                override fun onHideCustomView() = hideCustomView()

                override fun shouldSkipScript(url: String): Boolean {
                    val skip = isUnrelatedScript(url)
                    if (skip) AppLog.i(TAG, "skipScript ${url.takeLast(48)}")
                    return skip
                }

                override fun shouldBlockRequest(url: String): Boolean {
                    val block = isAdRequest(url)
                    if (block) AppLog.i(TAG, "blockAd ${url.take(96)}")
                    return block
                }

                override fun mainDocumentHtml(url: String, cookie: String?): ByteArray? {
                    // X5 内核较新（Chromium 77+），保持页面原生加载最稳；
                    // 只有老系统内核（如 Chrome 66）才需要主文档注入补丁
                    if (webHost.usingX5) return null
                    return injectIntoMainDocument(url, cookie)
                }
            }
        )
        binding.webContainer.addView(
            webHost.view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        webHost.setKeepScreenOn(true)
        AppLog.i(
            TAG,
            "webHost engine=${if (webHost.usingX5) "x5" else "system"} " +
                "ua=${WebSettings.getDefaultUserAgent(this)}"
        )
    }

    private fun hideCustomView() {
        val view = customView ?: return
        binding.playerRoot.removeView(view)
        customView = null
        webHost.notifyCustomViewHidden()
        webHost.setVisible(true)
    }

    private fun injectPlayerModeRepeatedly() {
        val session = playbackSession
        repeat(INJECT_ATTEMPTS) { index ->
            handler.postDelayed({
                if (session != playbackSession || isFinishing || isDestroyed) return@postDelayed
                // 已经起播（拿到时长）就不再重复注入：X5 上每次注入都会走一遍页面样式检查，
                // 低端设备上会抢占 JS 主线程造成画面卡顿，音频却还在继续。
                if (playbackStarted) {
                    AppLog.i(TAG, "inject stop attempt=${index + 1} playback started")
                    return@postDelayed
                }
                webHost.evaluateJavascript(PLAYER_MODE_SCRIPT) { result ->
                    AppLog.i(TAG, "inject attempt=${index + 1} result=$result")
                }
            }, index * INJECT_INTERVAL_MS)
        }
    }

    /** 向页面里的官方播放器下发一条命令。 */
    private fun js(command: String, arg: Long = 0L) {
        if (isFinishing || isDestroyed) return
        val script = "(function(){try{var cmd='%s';var arg=%d;%s}catch(e){return 'err '+e;}})();"
            .format(command, arg, JS_HELPERS)
        webHost.evaluateJavascript(script) { result ->
            AppLog.i(TAG, "js $command($arg) -> $result")
        }
    }

    // ---------------------------------------------------------------- 播放控制

    private fun togglePlayPause() {
        // 页面侧读到的 paused 可能与本地不同步，翻转交给页面自己做；这里只记录意图
        userPaused = !pagePaused
        js("toggle")
    }

    private fun seekBy(deltaMs: Long) {
        js("seek", deltaMs)
    }

    // ---------------------------------------------------------------- 控制条

    private fun updateProgress() {
        if (durationMs > 0) {
            binding.seekBar.setDuration(durationMs)
            if (!seekingByUser) {
                binding.seekBar.setPosition(positionMs.coerceIn(0, durationMs))
                binding.textPosition.text = formatTime(positionMs)
            }
            binding.textDuration.text = formatTime(durationMs)
        } else {
            binding.seekBar.setDuration(0)
            if (!seekingByUser) {
                binding.seekBar.setPosition(0)
                binding.textPosition.text = formatTime(0)
            }
            binding.textDuration.text = formatTime(0)
        }
    }

    private fun formatTime(millis: Long): String {
        val safe = (millis / 1000L).coerceAtLeast(0L)
        val hours = safe / 3600
        val minutes = (safe % 3600) / 60
        val seconds = safe % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    private fun showController() {
        binding.controller.visibility = View.VISIBLE
        binding.topBar.visibility = View.VISIBLE
        binding.alwaysClock.visibility = View.GONE
        binding.buttonBack.visibility = View.VISIBLE
        scheduleControllerHide()
    }

    private fun hideController() {
        handler.removeCallbacks(hideControllerRunnable)
        binding.controller.visibility = View.GONE
        binding.topBar.visibility = View.GONE
        binding.alwaysClock.visibility = if (alwaysOnClockEnabled) View.VISIBLE else View.GONE
        binding.buttonBack.visibility = View.GONE
        if (!binding.playerRoot.isFocused) {
            focusView(binding.playerRoot, "hideController")
        }
    }

    private fun scheduleControllerHide() {
        handler.removeCallbacks(hideControllerRunnable)
        handler.postDelayed(hideControllerRunnable, CONTROLLER_HIDE_DELAY_MS)
    }

    /** 画质按钮改成图标后不再显示当前档位文字，只更新无障碍描述（当前档位由菜单选中态体现）。 */
    private fun updateQualityButton(text: String) {
        binding.buttonQuality.contentDescription = text
    }

    // ---------------------------------------------------------------- 菜单

    /** 画质菜单：项的顺序与官方控制条里的列表一致（超清/高清/标清/流畅）。 */
    private fun showQualityDialog() {
        showController()
        // 弹窗是独立窗口，按键不经过本页 dispatchKeyEvent，自动隐藏计时不会被重置；
        // 5 秒到点会把弹窗底下的控制条藏掉并把焦点收回播放区，弹窗关闭后体验割裂。
        // 弹窗期间暂停计时，关闭后重新计时。
        handler.removeCallbacks(hideControllerRunnable)
        val options = QUALITY_LABELS
        ChoiceDialog.show(
            context = this,
            title = getString(R.string.cctv_quality),
            options = options,
            selectedIndex = currentQualityIndex,
            onDismiss = { scheduleControllerHide() }
        ) { index ->
            val label = options.getOrNull(index) ?: return@show
            currentQualityIndex = index
            // 用户手动选过档位后不再自动升档，否则选标清/流畅会被立刻拉回超清
            qualityAutoApplied = true
            updateQualityButton(label)
            js("quality", index.toLong())
        }
    }

    private fun showEpisodeDialog() {
        showController()
        if (albumId.isBlank()) return
        if (episodes.isNotEmpty()) {
            displayEpisodePicker()
            return
        }
        AppToast.show(this, R.string.cctv_vod_episodes_loading)
        episodesJob?.cancel()
        episodesJob = lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) { api.fetchEpisodes(albumId, episodeMode) }
            if (isFinishing || isDestroyed) return@launch
            if (list.isEmpty()) {
                AppToast.show(this@CctvVodPlayerActivity, R.string.cctv_vod_stream_failed)
                return@launch
            }
            episodes = list
            displayEpisodePicker()
        }
    }

    private fun displayEpisodePicker() {
        val options = episodes.map { episode ->
            episode.title.ifBlank { getString(R.string.cctv_vod_episode_title_format, episode.index) }
        }
        // "当前"优先按内部集号推导（switchEpisode/连播误判都同步维护它），
        // 页面 URL 匹配只作兜底；两者对不上说明内部状态已漂移，打日志便于定位
        // "实际播的是某集、弹窗却把当前标在另一集"这类问题。
        val byIndex = episodes.indexOfFirst { it.index == currentEpisodeIndex }
        val byUrl = episodes.indexOfFirst { it.pageUrl == pageUrl }
        if (byIndex != byUrl) {
            AppLog.w(TAG, "epPickerDrift byIndex=$byIndex byUrl=$byUrl index=$currentEpisodeIndex page=$pageUrl")
        }
        val selected = when {
            byIndex >= 0 -> byIndex
            byUrl >= 0 -> byUrl
            else -> 0
        }
        // 弹窗期间暂停控制条自动隐藏（见 showQualityDialog 内注释）
        handler.removeCallbacks(hideControllerRunnable)
        ChoiceDialog.show(
            context = this,
            title = getString(R.string.choose_episode),
            options = options,
            selectedIndex = selected,
            onDismiss = { scheduleControllerHide() }
        ) { index ->
            val episode = episodes.getOrNull(index) ?: return@show
            if (episode.pageUrl == pageUrl) return@show
            switchEpisode(episode)
        }
    }

    private fun switchEpisode(episode: CctvEpisode) {
        AppLog.i(TAG, "switchEpisode index=${episode.index} page=${episode.pageUrl}")
        // 切走前先把当前进度落盘
        persistProgress()
        pageUrl = episode.pageUrl
        currentEpisodeIndex = episode.index
        episodeTitle = episode.title
        binding.textTitle.text = episodeTitle
        durationMs = 0L
        positionMs = 0L
        currentQualityIndex = 0
        updateQualityButton(QUALITY_LABELS.first())
        showLoading(true)
        playbackSession++
        // 新一集：读它自己的本地进度并续播
        pendingResumeMs = CctvVodProgressStore.load(this, albumId, currentEpisodeIndex)
        resumeApplied = false
        autoNextTriggered = false
        endedRestyleApplied = false
        userPaused = false
        pagePaused = false
        qualityAutoApplied = false
        playbackStarted = false
        handler.removeCallbacks(loadTimeoutRunnable)
        handler.postDelayed(loadTimeoutRunnable, LOAD_TIMEOUT_MS)
        saveTickCounter = 0
        lastPositionMs = 0L
        resetLoadStage()
        webHost.loadUrl(pageUrl)
    }

    /** 本集播完：有下一集就自动连播，没有（最后一集）就停在结束画面。 */
    private fun playNextEpisode() {
        if (autoNextTriggered) return
        autoNextTriggered = true
        val next = episodes.firstOrNull { it.index == currentEpisodeIndex + 1 }
        if (next == null) {
            AppLog.i(TAG, "allEpisodesFinished index=$currentEpisodeIndex")
            AppToast.show(this, R.string.cctv_vod_all_episodes_finished)
            showController()
            return
        }
        AppLog.i(TAG, "autoNext -> index=${next.index}")
        AppToast.show(this, getString(R.string.cctv_vod_playing_next, next.index))
        switchEpisode(next)
    }

    /** 把当前集推进到 [episode]（官方片尾连播的两条路径共用），同步全部会话状态。 */
    private fun advanceToEpisode(episode: CctvEpisode, reason: String) {
        AppLog.i(TAG, "$reason -> index=${episode.index}")
        persistProgress()
        currentEpisodeIndex = episode.index
        episodeTitle = episode.title
        binding.textTitle.text = episodeTitle
        pageUrl = episode.pageUrl
        pendingResumeMs = 0L
        resumeApplied = true
        autoNextTriggered = false
        endedRestyleApplied = false
        saveTickCounter = 0
    }

    /** 把当前进度写入本地（临近片尾会清掉记录）。 */
    private fun persistProgress() {
        if (albumId.isBlank() || currentEpisodeIndex <= 0) return
        CctvVodProgressStore.save(
            context = this,
            albumId = albumId,
            episodeIndex = currentEpisodeIndex,
            positionMs = positionMs,
            durationMs = durationMs
        )
    }

    /** 重置起播耗时打点（进页与切集共用；各阶段日志见 Bridge.onVideoState 内 loadStage）。 */
    private fun resetLoadStage() {
        loadStartAt = android.os.SystemClock.elapsedRealtime()
        loadStageFirstState = false
        loadStageReady2 = false
        qualitySwitchAt = 0L
        loadStageQualitySettled = false
    }

    // ---------------------------------------------------------------- 状态回传

    private inner class Bridge {
        @JavascriptInterface
        fun onVideoState(state: String) {
            // 会话隔离：切集（switchEpisode）后旧页面的残留回调（ended、临尾位置等）
            // 可能晚于状态重置到达，不丢弃的话会被当成新集状态，触发错误的连播/
            // 连播误判，表现为"手动选了某集却被推到别的集"。回调归属按捕获时的
            // 会话号判定：捕获后发生过切集 ⇒ 这是旧页面的状态，整体丢弃。
            val session = playbackSession
            handler.post {
                if (session != playbackSession) {
                    AppLog.i(TAG, "dropStaleState session=$session now=$playbackSession")
                    return@post
                }
                val ready = Regex("\"readyState\":(\\d+)").find(state)
                    ?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                val paused = state.contains("\"paused\":true")
                val ended = state.contains("\"ended\":true")
                val cur = Regex("\"currentTime\":([\\d.]+)").find(state)
                    ?.groupValues?.getOrNull(1)?.toDoubleOrNull() ?: 0.0
                val dur = Regex("\"duration\":([\\d.]+)").find(state)
                    ?.groupValues?.getOrNull(1)?.toDoubleOrNull() ?: 0.0
                val videoW = Regex("\"w\":(\\d+)").find(state)
                    ?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                val videoH = Regex("\"h\":(\\d+)").find(state)
                    ?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                val buffered = Regex("\"buffered\":([\\d.]+)").find(state)
                    ?.groupValues?.getOrNull(1)?.toDoubleOrNull() ?: 0.0
                val nowMs = android.os.SystemClock.elapsedRealtime()
                if (!loadStageFirstState) {
                    loadStageFirstState = true
                    AppLog.i(TAG, "loadStage firstState ${nowMs - loadStartAt}ms ready=$ready")
                }
                if (dur > 0) {
                    durationMs = (dur * 1000).toLong()
                    if (!playbackStarted) {
                        playbackStarted = true
                        handler.removeCallbacks(loadTimeoutRunnable)
                        binding.textError.visibility = View.GONE
                        AppLog.i(TAG, "loadStage metadata ${nowMs - loadStartAt}ms")
                    }
                }
                positionMs = (cur * 1000).toLong()
                pagePaused = paused
                lastStateAt = nowMs
                updatePlayPauseIcon(paused)
                updatePauseIndicator(paused, ready)
                AppLog.i(TAG, "state ready=$ready paused=$paused ended=$ended pos=${positionMs / 1000}s dur=${durationMs / 1000}s buf=${buffered.toLong()}s video=${videoW}x$videoH")
                // 官方页面的片尾连播有两条路径，都要跟着推进集号（否则下一集的进度
                // 会被记到上一集头上）：
                // ①整文档跳转（实测主路径）：官方脚本 location 跳到下一集 URL，
                //   pageStarted 已给出确切目标——按 URL 匹配直接推进，精确无猜测；
                // ②页面内换流（无文档加载）：URL 不变，只能按行为特征判断——
                //   上一轮位置已接近片尾、这一轮却从头开始 ⇒ 切到下一集。
                //   文档刚加载过的 10 秒压制窗内不走该启发式：整文档跳转/重载后回传
                //   恢复同样"从头开始"，压住防止与①重复推进或误推进。
                val startedUrl = lastPageStartedUrl
                if (startedUrl.isNotEmpty() && startedUrl != pageUrl) {
                    episodes.firstOrNull { it.pageUrl == startedUrl && it.index != currentEpisodeIndex }
                        ?.let { advanceToEpisode(it, "pageJumped") }
                }
                val nearEndLastRound = durationMs > 0 && lastPositionMs >= durationMs - NEAR_END_MS
                val restartedNow = positionMs < RESTART_MS && lastPositionMs > 60_000L
                val docReloadedRecently = nowMs - lastPageStartedAt < PAGE_RELOAD_ADVANCE_GUARD_MS
                if (nearEndLastRound && restartedNow && !docReloadedRecently) {
                    episodes.firstOrNull { it.index == currentEpisodeIndex + 1 }
                        ?.let { advanceToEpisode(it, "pageSelfAdvanced") }
                }
                lastPositionMs = positionMs

                if (ready >= 2 && !loadStageReady2) {
                    loadStageReady2 = true
                    AppLog.i(TAG, "loadStage ready2 ${nowMs - loadStartAt}ms")
                }
                // 升档换流完成的时刻。必须放在下方 autoQualityUp 之前判定：
                // 触发升档的同一条回传里 ready>=2 也成立，放在后面会把刚触发的
                // 升档误判为已完成（0ms）。
                if (qualitySwitchAt > 0 && !loadStageQualitySettled &&
                    ready >= 2 && !paused && durationMs > 0
                ) {
                    loadStageQualitySettled = true
                    AppLog.i(TAG, "loadStage qualitySettled ${nowMs - qualitySwitchAt}ms total=${nowMs - loadStartAt}ms")
                }
                if (ready >= 2 && !paused) showLoading(false)

                // 元数据一到位就按本地记录续播（ready=1 时 duration 已有），
                // 不必等到 ready>=2，少等一次缓冲
                if (!resumeApplied && ready >= 1 && durationMs > 0) {
                    resumeApplied = true
                    if (pendingResumeMs > 0) {
                        AppLog.i(TAG, "resume from $pendingResumeMs")
                        js("seekTo", pendingResumeMs)
                    }
                }

                // 每约 5 秒落一次进度（回传本身是 1 秒一次）
                saveTickCounter++
                if (saveTickCounter >= 5) {
                    saveTickCounter = 0
                    if (!seekingByUser) persistProgress()
                }

                if (ended) {
                    playNextEpisode()
                    // 官方页面在片尾会滚回顶部并展示网页自身布局（header 等）盖过
                    // 视频区，这里重放一次收敛样式把页面压回纯播放态。本集只做
                    // 一次（ended 每秒回传），切集时复位；若已连播到下一集，新
                    // 页面的首次样式段自会执行，这次重放无副作用。
                    if (!endedRestyleApplied && playbackStarted) {
                        endedRestyleApplied = true
                        js("restyle")
                    }
                }

                // 页面初始流是 360P（实测 640x360），而菜单默认勾着"超清"，
                // 不主动切一次的话用户看到的就是标清画质。源最高只有 720P。
                if (!qualityAutoApplied && ready >= 2 && durationMs > 0 &&
                    videoH in 1 until MAX_VIDEO_HEIGHT
                ) {
                    qualityAutoApplied = true
                    qualitySwitchAt = nowMs
                    loadStageQualitySettled = false
                    AppLog.i(TAG, "loadStage autoQualityUp ${nowMs - loadStartAt}ms (from ${videoW}x$videoH)")
                    js("quality", 0L)
                }

                // 页面自己把播放恢复起来了（注入脚本重跑 / 换流后 autoplay）：
                // 用户意图是暂停就压回去，否则表现为"暂停一会自己又播了"。
                if (userPaused && !paused && !ended && ready >= 2) {
                    val now = System.currentTimeMillis()
                    if (now - lastPauseCorrectAt > PAUSE_CORRECT_INTERVAL_MS) {
                        lastPauseCorrectAt = now
                        AppLog.i(TAG, "pageResumedWhilePaused -> pause again")
                        js("pause")
                    }
                }
            }
        }
    }

    private fun showLoading(show: Boolean) {
        binding.loading.visibility = if (show) View.VISIBLE else View.GONE
        binding.textLoading.visibility = if (show) View.VISIBLE else View.GONE
        // 起播盖层随 loading 同步显隐：loading 结束时 video 已出画面、收敛样式
        // 也已注入，收起后露出的是纯播放态而不是网页原文。
        binding.webCover.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun showError(message: String) {
        // 出错时只收转圈与提示文字：盖层保持盖住网页原文（黑底上居中显示错误，
        // 起播失败时露出的网页对用户只有干扰）
        binding.loading.visibility = View.GONE
        binding.textLoading.visibility = View.GONE
        binding.textError.text = message
        binding.textError.visibility = View.VISIBLE
        showController()
    }

    // ---------------------------------------------------------------- 按键

    /** 焦点是否落在本页自己的控件上（播放区 / 控制条按钮 / 进度条 / 左上返回）。 */
    private fun isOwnView(v: View?): Boolean = when (v) {
        null -> false
        binding.playerRoot, binding.buttonPlay, binding.seekBar,
        binding.buttonQuality, binding.buttonEpisode, binding.buttonBack -> true
        else -> false
    }

    private fun describeFocus(): String =
        currentFocus?.let { "${it.javaClass.name}#${it.id}" } ?: "null"

    /** 请求焦点并记录结果；X5 内核可能异步抢回焦点，失败时下一帧重试一次。 */
    private fun focusView(target: View, reason: String): Boolean {
        val cur = currentFocus
        // 目标控件与网页层互抢时先清掉网页层焦点，否则 X5 会把 requestFocus 驳回
        if (cur != null && cur !== target && ::webHost.isInitialized &&
            webHost.isInsideWebView(cur)
        ) {
            cur.clearFocus()
        }
        val ok = target.requestFocus()
        AppLog.i(TAG, "focus $reason ok=$ok cur=${describeFocus()}")
        if (ok) return true
        target.post {
            if (isFinishing || isDestroyed) return@post
            val retry = target.requestFocus()
            AppLog.i(TAG, "focus $reason retry=$retry cur=${describeFocus()}")
        }
        return false
    }

    /**
     * X5 的 WebView 是 FrameLayout 壳，内部子 View 会在 attach/onResume/按键后自行
     * requestFocus，把焦点抢进网页层——系统内核不会。焦点一旦进入网页层，方向键就
     * 再也落不到我们的控制条上。这里全局监听：先封掉该内部 View 的可聚焦性（否则
     * 每次 requestFocus 都会被它再抢回去），再把焦点收回播放区。
     */
    private fun installFocusGuard() {
        binding.playerRoot.viewTreeObserver.addOnGlobalFocusChangeListener { oldFocus, newFocus ->
            if (newFocus == null || isOwnView(newFocus)) return@addOnGlobalFocusChangeListener
            if (!webHost.isInsideWebView(newFocus)) return@addOnGlobalFocusChangeListener
            // X5 内部视图不会出现在我们的布局里，也不会被业务使用，直接断掉它的可聚焦性
            if (newFocus.isFocusable) {
                newFocus.isFocusable = false
                newFocus.isFocusableInTouchMode = false
            }
            AppLog.w(
                TAG,
                "focusStolen old=${oldFocus?.javaClass?.name} new=${newFocus.javaClass.name} -> reclaim"
            )
            binding.playerRoot.post {
                if (!isFinishing && !isDestroyed && !isOwnView(currentFocus)) {
                    focusView(binding.playerRoot, "reclaim")
                }
            }
        }
    }

    /** 焦点是否落在某个控制条按钮上（播放 / 画质 / 选集 / 返回）。 */
    private fun isActionButtonFocused(): Boolean =
        binding.buttonPlay.isFocused || binding.buttonQuality.isFocused ||
            binding.buttonEpisode.isFocused || binding.buttonBack.isFocused

    private fun isSeekBarFocused(): Boolean = binding.seekBar.isFocused

    private fun moveButtonFocus(delta: Int): Boolean {
        // 只有按钮行（播放/画质/选集）参与横向移动，进度条与左上返回各占一行
        val row = listOf(binding.buttonPlay, binding.buttonQuality, binding.buttonEpisode)
            .filter { it.visibility == View.VISIBLE }
        if (row.isEmpty()) return false
        val current = row.indexOfFirst { it.isFocused }
        if (current < 0) return focusView(row[0], "row")
        val target = (current + delta).coerceIn(0, row.lastIndex)
        return focusView(row[target], "row")
    }

    /**
     * BACK 与各方向键在这里统一处理：官方播放器控件不响应遥控器，所以不能把按键放给 WebView。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            AppLog.i(
                TAG,
                "key code=${event.keyCode} focus=${currentFocus?.let { it.javaClass.simpleName + "#" + it.id }} " +
                    "btnFocus=${isActionButtonFocused()} seekFocus=${isSeekBarFocused()} ctrl=${binding.controller.visibility == View.VISIBLE}"
            )
        }
        val controllerVisible = binding.controller.visibility == View.VISIBLE
        val buttonFocused = isActionButtonFocused()

        // 操作控制条期间重置自动隐藏计时：停手 5 秒后才隐藏（与普通播放器一致）
        if (controllerVisible && event.action == KeyEvent.ACTION_DOWN) {
            scheduleControllerHide()
        }

        // 返回键（遥控器 BACK / 键盘 ESC）在 ACTION_DOWN 就处理：部分遥控器长按只发 DOWN，
        // 等 ACTION_UP 会"按了没反应"；两态都吞掉，避免事件继续下传造成重复处理。
        if (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) handleBackKey()
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)

        return when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                when {
                    buttonFocused -> {
                        if (event.repeatCount == 0) {
                            when {
                                binding.buttonBack.isFocused -> binding.buttonBack.performClick()
                                binding.buttonPlay.isFocused -> togglePlayPause()
                                binding.buttonQuality.isFocused -> binding.buttonQuality.performClick()
                                binding.buttonEpisode.isFocused -> binding.buttonEpisode.performClick()
                            }
                        }
                        true
                    }
                    else -> {
                        // 进度条上按确定不做事（拖拽才是它的交互），播放/暂停交给播放区语义
                        if (event.repeatCount == 0) {
                            showController()
                            togglePlayPause()
                        }
                        true
                    }
                }
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                // 进度条上左右同为快进快退（对齐主播放器"进度条聚焦时左右=seek"的行为，
                // 长按按 repeat 连续快进）；离开进度条走上下层级导航（上→返回 / 下→播放），
                // 不存在"左右永远在调进度走不出来"的问题。
                if (buttonFocused) moveButtonFocus(-1) else {
                    showController()
                    seekBy(-SEEK_STEP_MS)
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (buttonFocused) moveButtonFocus(1) else {
                    showController()
                    seekBy(SEEK_STEP_MS)
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                // 上下键按视觉层级走：播放区 → 按钮行 → 进度条 → 左上返回。
                when {
                    !controllerVisible -> {
                        showController()
                        focusView(binding.buttonPlay, "up-show")
                    }
                    binding.buttonBack.isFocused -> true // 已在最上层
                    isSeekBarFocused() -> focusView(binding.buttonBack, "up")
                    else -> focusView(binding.seekBar, "up") // 按钮行 / 播放区 → 进度条
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                // 与上键反向：左上返回 → 进度条 → 按钮行 → 播放区。
                when {
                    binding.buttonBack.isFocused -> focusView(binding.seekBar, "down")
                    isSeekBarFocused() -> focusView(binding.buttonPlay, "down")
                    buttonFocused -> focusView(binding.playerRoot, "down")
                    !controllerVisible -> {
                        // 控制条隐藏时按"下"要能把它叫出来（原来直接 return false，
                        // 系统找不到可聚焦目标，表现成"按下键没反应"）
                        showController()
                        focusView(binding.buttonPlay, "down-show")
                    }
                    else -> focusView(binding.buttonPlay, "down")
                }
                true
            }
            KeyEvent.KEYCODE_MENU -> {
                if (event.repeatCount == 0) showEpisodeDialog()
                true
            }
            else -> super.dispatchKeyEvent(event)
        }
    }

    /**
     * 触摸：单击画面切换我们自己的控制条显隐。
     *
     * **不能吞掉事件**：早先的实现消费了 ACTION_UP，WebView 只收到 ACTION_DOWN、
     * 一直等不到抬起，被判定成长按，弹出了 video.js 的上下文菜单（里面是播放器版本号
     * `core/wrap`）。这里改为把事件原样转发给 WebView，只用自己的 GestureDetector 旁听单点。
     */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // 左上返回按钮自己处理点击（点它不该顺带切换控制条显隐），
        // 以 DOWN 落点决定整条手势序列是否旁听，避免只喂半条序列给 GestureDetector。
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            touchOnBackButton = isInsideBackButton(event.x, event.y)
        }
        if (!touchOnBackButton) {
            gestureDetector.onTouchEvent(event)
        }
        return super.dispatchTouchEvent(event)
    }

    private fun isInsideBackButton(x: Float, y: Float): Boolean {
        if (binding.buttonBack.visibility != View.VISIBLE) return false
        val rect = android.graphics.Rect()
        return binding.buttonBack.getGlobalVisibleRect(rect) && rect.contains(x.toInt(), y.toInt())
    }

    /**
     * 返回键的关闭优先级：全屏画面 → 播放 UI（控制条）→ 退出播放页。
     * 只有前面都关干净了，再按一次才提示"再按一次退出"。
     */
    private fun handleBackKey() {
        val now = System.currentTimeMillis()
        // KeyEvent 与 OnBackInvoked 两条通道在个别设备上会各派发一次，去重防连跳两级
        if (now - lastBackHandledAt < BACK_DEDUPE_MS) return
        lastBackHandledAt = now
        val controllerVisible = binding.controller.visibility == View.VISIBLE
        AppLog.i(
            TAG,
            "backPressed controllerVisible=$controllerVisible customView=${customView != null}"
        )
        if (customView != null) {
            hideCustomView()
            return
        }
        if (controllerVisible) {
            hideController()
            return
        }
        if (now - exitTime <= EXIT_INTERVAL_MS) {
            finish()
        } else {
            exitTime = now
            AppToast.show(applicationContext, R.string.activity_exit_player_hint)
        }
    }

    companion object {
        const val EXTRA_PAGE_URL = "cctv_vod_page_url"
        const val EXTRA_TITLE = "cctv_vod_title"
        const val EXTRA_GUID = "cctv_vod_episode_guid"
        const val EXTRA_ALBUM_ID = "cctv_vod_album_id"
        const val EXTRA_EPISODE_MODE = "cctv_vod_episode_mode"
        const val EXTRA_EPISODE_INDEX = "cctv_vod_episode_index"

        private const val TAG = "CctvVodPlayer"
        private const val BRIDGE_NAME = "MyBiliCctvVodBridge"
        private const val INJECT_ATTEMPTS = 12
        private const val INJECT_INTERVAL_MS = 1_500L

        /** 片尾官方页面自重载后的补注入延迟：第一发装收敛样式，第二发补 video 初始化。 */
        private val INJECT_RELOAD_DELAYS_MS = listOf(800L, 3_000L)

        /** 文档加载后的压制窗口：其内的 restarted 信号视为自重载回播而非换集。 */
        private const val PAGE_RELOAD_ADVANCE_GUARD_MS = 10_000L
        private const val PROGRESS_INTERVAL_MS = 500L
        private const val CONTROLLER_HIDE_DELAY_MS = 5_000L
        private const val SEEK_STEP_MS = 10_000L
        private const val EXIT_INTERVAL_MS = 2_000L
        /** 返回键去重窗口：KeyEvent 与 OnBackInvoked 双通道各自派发时的间隔远小于此值。 */
        private const val BACK_DEDUPE_MS = 300L
        /** 暂停纠偏的最小间隔：页面顽固自播时避免每 500ms 回传都压一次。 */
        private const val PAUSE_CORRECT_INTERVAL_MS = 2_000L
        /** 源最高档高度：低于它说明拿到的是页面默认的 360P 流，需要主动升档。 */
        private const val MAX_VIDEO_HEIGHT = 720
        /** 起播超时：超过这么久还没拿到流元数据（duration）就提示失败，不再无限转圈。 */
        private const val LOAD_TIMEOUT_MS = 25_000L

        /**
         * 老 WebView 上 `window.postMessage` 要求 targetOrigin 参数，而央视页面播放器
         * （P2P 初始化）存在单参调用，会抛 `TypeError: 2 arguments required` 并中断初始化，
         * 表现为 video 元素存在但 ready 永远为 0（投影仪实测）。
         */
        private const val POST_MESSAGE_PATCH_SCRIPT = """
            (function(){
              try{
                if(window.__mybiliPmPatched)return;
                window.__mybiliPmPatched=true;
                console.log('mybili pm patch ready='+document.readyState);
                var orig=window.postMessage;
                window.postMessage=function(msg,origin,transfer){
                  try{
                    if(arguments.length<2)return orig.call(window,msg,'*');
                    return orig.apply(window,arguments);
                  }catch(e){console.log('mybili postMessage patch '+e);}
                };
              }catch(e){}
            })();
        """
        /** 距片尾多近算"看完"（与进度存储保持一致）。 */
        private const val NEAR_END_MS = 60_000L
        /** 位置小于这个值视为"从头开始"。 */
        private const val RESTART_MS = 15_000L

        /** 与官方控制条里画质菜单项的顺序一致。 */
        private val QUALITY_LABELS = listOf("超清", "高清", "标清", "流畅")

        /**
         * 播放页里与播放无关、却会以 document.write 串行阻塞解析的脚本。
         * 登录加密类（md5login/crypto-js/jsencrypt/sha256）暂不拦，避免影响取流鉴权。
         */
        private val SKIP_SCRIPT_KEYWORDS = listOf(
            "daohang211126.js",
            "top2023newindex.js",
            "gray20221130.js",
            "jquery.qrcode.min.js",
            "shareindex.js",
            "dianshibao.js",
            "wakeApp.js",
            "danmutjs",
            "cntv_Advertise.js",
            "sbu_hc.js",
            "phonejs/index.js",
            "mapjs/index.js",
            "bottomjs/index.js",
            "2019dlbhyjs",
            "login190120home.js",
            "login_new.js",
            "mylogin.js",
            // 2026-10-29 扩充（vset 详情页实抓脚本清单）。注意不可拦的三个库：
            // swiper4/echo 被 cctvnew_video_h5.js（播放器创建脚本）直接调用，
            // jiezi.js 的 wrapText 在 window.onload 被调用——拦任何一个都会让
            // 播放器创建流程抛 ReferenceError 中断（MuMu 实测 videoCount=0）。
            "2019bar/",         // www.cctv.com/newcctv/2019bar/ 系列（顶栏/侧栏/提示条）
            "2019whitetop",     // 顶部白条导航
            "pc_nav",           // tv.cctv.com/common/pc_nav PC 版导航
            "nativeShareXY.js", // 分享
            "jweixin",          // 微信 JSSDK
            "dingtalk.js",      // 钉钉 SDK
            // 底层图文评论模块（电视剧页）。**只能拦 tuwen，不能整个拦 "diceng_"**
            // （issue#103）：纪录片/特别节目模板（jlp/tb）在窄视口（TV WebView 恒
            // ≤1024）下，播放器创建源头 playerlist() 被安排在 cctvnew_video_h5.js
            // 的 $.getScript(swiper4) 回调里，回调第一步 pinglun() 依赖 #pl_textarea
            // 评论输入框——该元素由 phonejs（已拦）或 diceng_pinglun 动态创建、互为
            // 兜底。v2.1.20 曾用 "diceng_" 把两个兜底全拦，回调在 pinglun() 抛
            // TypeError 中断，playerlist() 永不执行 → video 元素不创建 → 25s 超时
            // 报「播放地址获取失败」；电视剧模板的创建链在 index_data_dsj.js、不经
            // 此回调，故不受影响（浏览器逐脚本删改复现+修复验证，pl:0→3）。
            "diceng_tuwen"
        )

        /**
         * 必须用桌面 Chrome UA：央视页面按 UA 分发播放器，带 "Android" 会被判成移动端，
         * 拿到的是没有完整控制条的移动版（实测 `#_video_player` 根本不存在）。
         */
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

        /**
         * 页面里执行的命令实现（与 [js] 拼接）。
         *
         * 播放/暂停优先用 video.js 的播放器实例（页面是 video.js + P2P，直接操作 DOM 的
         * video 元素状态可能与它不同步，表现为"按了没反应"），实例拿不到才退回 DOM 操作。
         * quality 切换后官方播放器会换流并从头播，这里记下切换前的位置、等新流可用后恢复。
         */
        private const val JS_HELPERS = """
            function pl(){try{var ps=(window.videojs&&videojs.getPlayers)?videojs.getPlayers():{};if(ps&&ps['_video_player'])return ps['_video_player'];}catch(e){}return null;}
            function markPaused(p){window.__mybiliUserPaused=!!p;}
            var P=pl();
            if(cmd==='toggle'){
              if(P){if(P.paused()){P.play();markPaused(false);}else{P.pause();markPaused(true);}return 'ok';}
              var v0=document.getElementsByTagName('video')[0];if(!v0)return 'novideo';
              if(v0.paused){v0.play();markPaused(false);}else{v0.pause();markPaused(true);}return 'ok';
            }
            var v=document.getElementsByTagName('video')[0];
            if(!v)return 'novideo';
            if(cmd==='play'){if(P){P.play();}else{v.play();}markPaused(false);return 'ok';}
            if(cmd==='pause'){if(P){P.pause();}else{v.pause();}markPaused(true);return 'ok';}
            if(cmd==='seek'){var d=v.duration||0;var t=v.currentTime+arg/1000;if(t<0)t=0;if(d>0&&t>d)t=d;v.currentTime=t;return 'ok';}
            if(cmd==='seekTo'){var d0=v.duration||0;var t0=arg/1000;if(t0<0)t0=0;if(d0>0&&t0>d0)t0=d0;v.currentTime=t0;return 'ok';}
            if(cmd==='restyle'){
              if(window.__mybiliRestyle){window.__mybiliRestyle();}
              try{window.scrollTo(0,0);}catch(e){}
              try{
                var hp=document.querySelector('.headernew');
                var vfix=(function(){var vp=document.getElementsByTagName('video')[0];if(!vp)return 'novideo';var pe=vp;for(var di=0;di<4&&pe;di++){if(getComputedStyle(pe).position==='fixed')return 'fixed@depth'+di;pe=pe.parentElement;}return 'notfixed';})();
                console.log('mybili restyle probe headernew='+(hp?(hp.tagName||'?')+'/display='+getComputedStyle(hp).display:'absent')+' scrollY='+window.scrollY+' video='+vfix);
              }catch(e){}
              return 'ok';
            }
            if(cmd==='quality'){
              var li=document.querySelectorAll('#_video_player .vjs-playback-quality li');
              if(!li||!li[arg])return 'noitems';
              var labels=[];for(var i=0;i<li.length;i++)labels.push((li[i].textContent||'').trim());
              var resumeAt=v.currentTime;var wasPaused=v.paused;
              li[arg].click();
              var tries=0;
              var timer=setInterval(function(){
                tries++;
                var nv=document.getElementsByTagName('video')[0];
                if(nv&&nv.readyState>=1){
                  if(Math.abs((nv.currentTime||0)-resumeAt)>2){try{nv.currentTime=resumeAt;}catch(e){}}
                  // 原本暂停就保持暂停（新流带 autoplay，不压回去会自己播起来）
                  if(wasPaused){try{nv.pause();}catch(e){}}else{try{nv.play();}catch(e){}}
                  clearInterval(timer);
                }
                if(tries>25)clearInterval(timer);
              },300);
              return 'ok clicked='+(li[arg].textContent||'').trim()+' items='+labels.join('/');
            }
            return 'unknown';
        """

        /**
         * 页面收敛：只铺满外层容器，**不要动 video 元素的 position/尺寸/z-index**
         * （官方控制条与 video 是同级兄弟，把 video 拉全屏会盖掉控制条），
         * 播放器本体靠 video.js 的 vjs-fill 撑满。
         */
        private val PLAYER_MODE_SCRIPT = """
            (function() {
              try {
                if (!document.documentElement || !document.body) return 'not-ready';

                // 页面收敛样式为可重入函数（__mybiliRestyle）：官方页面在片尾等状态
                // 会滚回顶部并展示网页自身布局（header 等）盖过视频，此时由 restyle
                // 命令低频重放（每次操作均幂等，频度极低不构成 X5 重排卡顿）；
                // 首次进入仍只执行一次，避免注入轮询期间的反复重排。
                window.__mybiliRestyle = function() {
                  document.documentElement.style.background = '#000';
                  document.body.style.background = '#000';
                  document.body.style.margin = '0';
                  document.body.style.padding = '0';
                  document.body.style.overflow = 'hidden';

                  function hardFix(el) {
                    if (!el) return false;
                    el.style.setProperty('position', 'fixed', 'important');
                    el.style.setProperty('left', '0', 'important');
                    el.style.setProperty('top', '0', 'important');
                    el.style.setProperty('right', 'auto', 'important');
                    el.style.setProperty('bottom', 'auto', 'important');
                    el.style.setProperty('width', '100%', 'important');
                    el.style.setProperty('height', '100%', 'important');
                    el.style.setProperty('max-width', 'none', 'important');
                    el.style.setProperty('max-height', 'none', 'important');
                    el.style.setProperty('margin', '0', 'important');
                    el.style.setProperty('padding', '0', 'important');
                    el.style.setProperty('background', '#000', 'important');
                    el.style.setProperty('z-index', '2147483647', 'important');
                    el.style.setProperty('overflow', 'hidden', 'important');
                    return true;
                  }
                  ['#videolist1', '.title19600', '.title18847', '.jingxuantanceng', '.headernew', '.nav', '.page_bottom', '.footer', '.column_wrapper', '.context-menu']
                    .forEach(function(sel) {
                      var el = document.querySelector(sel);
                      if (el) el.style.setProperty('display', 'none', 'important');
                    });
                  // 官方控制条对遥控器无效（只认鼠标/触摸），而我们自己那层功能等价且遥控器可用，
                  // 两套并存会重复显示时间与画质入口，这里把官方的收起。
                  // 注意用 visibility 而非 display：画质菜单项仍需可被 JS click 命中。
                  ['#_video_player .vjs-control-bar', '#_video_player .vjs-big-play-button', '#_video_player .vjs-play-control-mini', '#_video_player .vjs-picture-in-picture-control']
                    .forEach(function(sel) {
                      var el = document.querySelector(sel);
                      if (el) {
                        el.style.setProperty('visibility', 'hidden', 'important');
                        el.style.setProperty('opacity', '0', 'important');
                        el.style.setProperty('pointer-events', 'none', 'important');
                      }
                    });
                  ['.allcontent', '.video19600', '.playingVideo', '.video', '.video_left']
                    .forEach(function(sel) {
                      var el = document.querySelector(sel);
                      if (el) hardFix(el);
                    });
                  ['#video', '#_video', '#_video_player'].forEach(function(sel) {
                    var el = document.querySelector(sel);
                    if (el) {
                      el.style.setProperty('width', '100%', 'important');
                      el.style.setProperty('height', '100%', 'important');
                    }
                  });
                };
                if (!window.__mybiliVodStyled) {
                  window.__mybiliRestyle();
                  window.__mybiliVodStyled = true;
                }

                // 广告组件隐藏（issue#105）：官方播放器自带横幅(banner-ad-overlay，带关闭
                // 按钮)/暂停(pause-ad-overlay)/倒计时/角标提示等 DOM 广告层，按排期投放时
                // 直接叠在画面上。用 style 规则而非逐元素 style：投放类元素（角标）可能
                // 晚于本脚本创建，常驻规则对后建元素依然生效。与 galaxy 请求拦截互为双保险。
                // 控制条隐藏同样改为常驻规则（issue#106）：原先是逐元素内联 visibility，
                // 暂停/换集时 video.js 重建控制条 DOM 后内联样式丢失、官方控制条重现并与
                // App 控制条叠加。常驻规则对后建元素自动命中；visibility 而非 display 仍为
                // 画质菜单项保留 JS click 命中能力。原生 media-controls 全套一并隐藏，
                // 防官方脚本给 video 加 controls 属性时系统控制条混入。
                if (!window.__mybiliAdCss) {
                  window.__mybiliAdCss = true;
                  try {
                    var adStyle = document.createElement('style');
                    adStyle.id = 'mybili-ad-block';
                    adStyle.textContent = '#_video_player .banner-ad-overlay,' +
                      ' #_video_player .pause-ad-overlay,' +
                      ' #_video_player .ad-countdown-time,' +
                      ' #_video_player .ads-label-text,' +
                      ' #_video_player .ad-label,' +
                      ' #_video_player .ad-vip-skip-hint { display: none !important; }' +
                      ' #_video_player .vjs-control-bar,' +
                      ' #_video_player .vjs-big-play-button,' +
                      ' #_video_player .vjs-play-control-mini,' +
                      ' #_video_player .vjs-picture-in-picture-control' +
                      ' { visibility: hidden !important; opacity: 0 !important; pointer-events: none !important; }' +
                      ' video::-webkit-media-controls,' +
                      ' video::-webkit-media-controls-enclosure,' +
                      ' video::-webkit-media-controls-panel,' +
                      ' video::-webkit-media-controls-start-playback-button' +
                      ' { display: none !important; -webkit-appearance: none !important; }';
                    (document.head || document.documentElement).appendChild(adStyle);
                    console.log('mybili ad css applied');
                  } catch (e) {}
                }

                var videos = Array.prototype.slice.call(document.getElementsByTagName('video'));
                videos.forEach(function(video) {
                  // 每个 video 只初始化一次（autoplay/事件监听），避免反复改属性触发重排
                  if (!video.__mybiliVodApplied) {
                    video.__mybiliVodApplied = true;
                    video.autoplay = true;
                    video.muted = false;
                    ['waiting', 'stalled', 'playing', 'pause', 'seeking', 'seeked'].forEach(function(name) {
                      video.addEventListener(name, function() {
                        console.log('cctv vod event=' + name + ' t=' + (video.currentTime || 0).toFixed(1) + ' rs=' + video.readyState);
                      });
                    });
                  }
                  // 用户暂停期间不能把它抢播回去；只在确实暂停时才补一次 play
                  if (!window.__mybiliUserPaused && video.paused && !video.ended) {
                    video.play && video.play().catch(function(e) { console.log('cctv vod play rejected ' + e); });
                  }
                });

                if (!window.__mybiliVodTimer) {
                  window.__mybiliVodTimer = setInterval(function() {
                    try {
                      var v = document.getElementsByTagName('video')[0];
                      if (window.MyBiliCctvVodBridge && v) {
                        window.MyBiliCctvVodBridge.onVideoState(JSON.stringify({
                          readyState: v.readyState || 0,
                          paused: !!v.paused,
                          currentTime: v.currentTime || 0,
                          duration: v.duration || 0,
                          ended: !!v.ended,
                          w: v.videoWidth || 0,
                          h: v.videoHeight || 0,
                          buffered: (v.buffered && v.buffered.length) ? v.buffered.end(v.buffered.length - 1) : 0,
                          title: document.title
                        }));
                      }
                    } catch (e) {}
                  }, 1000);
                }
                return 'ok videoCount=' + videos.length + ' adCss=' + !!window.__mybiliAdCss;
              } catch (error) {
                console.log('cctv vod inject error ' + error);
                return 'error ' + error;
              }
            })();
        """.trimIndent()

        fun intentForEpisode(
            context: Context,
            episode: CctvEpisode,
            title: String,
            albumId: String,
            episodeMode: Int
        ): Intent = Intent(context, CctvVodPlayerActivity::class.java)
            .putExtra(EXTRA_PAGE_URL, episode.pageUrl)
            .putExtra(EXTRA_TITLE, title)
            .putExtra(EXTRA_GUID, episode.guid)
            .putExtra(EXTRA_ALBUM_ID, albumId)
            .putExtra(EXTRA_EPISODE_MODE, episodeMode)
            .putExtra(EXTRA_EPISODE_INDEX, episode.index)
    }
}
