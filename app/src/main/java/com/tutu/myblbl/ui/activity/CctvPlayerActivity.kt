package com.tutu.myblbl.ui.activity

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.tutu.myblbl.R
import com.tutu.myblbl.databinding.ActivityCctvPlayerBinding
import com.tutu.myblbl.core.common.log.AppLog
import com.tutu.myblbl.core.ui.base.BaseActivity
import com.tutu.myblbl.core.ui.system.ViewUtils
import com.tutu.myblbl.feature.cctv.CctvChannel
import com.tutu.myblbl.feature.cctv.CctvChannels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koin.android.ext.android.inject

class CctvPlayerActivity : BaseActivity<ActivityCctvPlayerBinding>() {

    private val okHttpClient: OkHttpClient by inject()
    private val handler = Handler(Looper.getMainLooper())
    private val channels = CctvChannels.list()
    private lateinit var gestureDetector: GestureDetector
    private var currentIndex = 0
    private var playbackSession = 0
    private var dynamicScriptUrls: List<String>? = null
    private var usingDynamicScripts = false
    private var recoveryInProgress = false
    private var scriptRecoverySession = -1
    private var playbackTimeoutSession = -1
    private var probeExtends = 0
    private var playbackStartMs = 0L
    private var preferredQuality = CctvQuality.P1080
    private var selectedQuality = CctvQuality.P1080
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var exitTime = 0L

    override fun getViewBinding(): ActivityCctvPlayerBinding {
        return ActivityCctvPlayerBinding.inflate(layoutInflater)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentIndex = CctvChannels.defaultIndex(intent.getIntExtra(EXTRA_CHANNEL_INDEX, 0))
        gestureDetector = GestureDetector(this, PlayerGestureListener())
        binding.playerRoot.requestFocus()
        configureWebView(binding.webPlayer)
        setupController()
        playCurrentChannel(showHint = false)
    }

    private fun setupController() {
        binding.controller.visibility = View.VISIBLE
        binding.buttonRefresh.setOnClickListener {
            playCurrentChannel(showHint = true)
        }
        binding.buttonQuality.setOnClickListener {
            showQualityDialog()
        }
        // 焦点进入按钮行后取消自动隐藏，避免操作中途控制栏消失
        val cancelAutoHide = View.OnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                handler.removeCallbacks(hideControllerRunnable)
            }
        }
        binding.buttonQuality.onFocusChangeListener = cancelAutoHide
        binding.buttonRefresh.onFocusChangeListener = cancelAutoHide
        updateQualityButton()
        scheduleControllerHide()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(webView: WebView) {
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.keepScreenOn = true
        webView.setBackgroundColor(Color.BLACK)
        webView.isFocusable = false
        webView.isFocusableInTouchMode = false
        webView.addJavascriptInterface(Bridge(), BRIDGE_NAME)
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                AppLog.i(TAG, "webPageStarted url=$url")
                logPlaybackStage(playbackSession, "page_started", "url=$url")
                binding.progressBar.visibility = View.VISIBLE
                binding.textError.visibility = View.GONE
                super.onPageStarted(view, url, favicon)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                AppLog.i(TAG, "webPageFinished url=$url")
                logPlaybackStage(playbackSession, "page_finished", "url=$url")
                injectPlayerModeRepeatedly()
                super.onPageFinished(view, url)
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame) {
                    val message = "网页加载失败 ${error.errorCode}"
                    AppLog.e(TAG, "$message url=${request.url} desc=${error.description}")
                    recoverPlayback(playbackSession, message, allowQualityFallback = false)
                }
                super.onReceivedError(view, request, error)
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: WebResourceResponse
            ) {
                if (request.isForMainFrame) {
                    AppLog.e(
                        TAG,
                        "webHttpError status=${errorResponse.statusCode} reason=${errorResponse.reasonPhrase} url=${request.url}"
                    )
                }
                super.onReceivedHttpError(view, request, errorResponse)
            }

            override fun onReceivedSslError(
                view: WebView,
                handler: SslErrorHandler,
                error: SslError
            ) {
                AppLog.e(TAG, "webSslError primary=${error.primaryError} url=${error.url}")
                handler.cancel()
                recoverPlayback(playbackSession, "网页证书错误", allowQualityFallback = false)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val allowed = CCTV_HOSTS.any { request.url.host?.endsWith(it) == true }
                if (!allowed) {
                    AppLog.w(TAG, "webNavigationBlocked url=${request.url}")
                    return true
                }
                return false
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (customView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                binding.fullscreenContainer.addView(
                    view,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
                binding.fullscreenContainer.visibility = View.VISIBLE
                binding.webPlayer.visibility = View.GONE
                binding.progressBar.visibility = View.GONE
            }

            override fun onHideCustomView() {
                hideCustomView()
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                AppLog.i(
                    TAG,
                    "webConsole ${consoleMessage.messageLevel()} ${consoleMessage.sourceId()}:${consoleMessage.lineNumber()} ${consoleMessage.message()}"
                )
                return true
            }
        }
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = false
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = USER_AGENT
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                safeBrowsingEnabled = false
            }
        }
    }

    private fun playCurrentChannel(showHint: Boolean) {
        val channel = channels.getOrNull(currentIndex) ?: return
        playbackSession++
        val session = playbackSession
        selectedQuality = preferredQuality
        playbackStartMs = SystemClock.elapsedRealtime()
        usingDynamicScripts = false
        recoveryInProgress = false
        scriptRecoverySession = -1
        hideCustomView()
        updateController(channel)
        if (showHint) {
            showChannelHint(channel.title)
        }
        logPlaybackStage(session, "start_channel", "channel=${channel.id}")
        loadChannelHtml(channel, DEFAULT_SCRIPT_URLS, session, "default")
        ViewUtils.keepScreenOn(this, true)
    }

    private fun loadChannelHtml(
        channel: CctvChannel,
        scriptUrls: List<String>,
        session: Int,
        scriptSource: String
    ) {
        binding.progressBar.visibility = View.VISIBLE
        binding.textError.visibility = View.GONE
        val html = buildPlayerHtml(channel.id, scriptUrls)
        val baseUrl = "https://tv.cctv.com/live/${channel.id}/m/"
        AppLog.i(
            TAG,
            "webLoad ${channel.title} channelId=${channel.id} quality=${selectedQuality.label} br=${selectedQuality.br} baseUrl=$baseUrl scripts=$scriptSource count=${scriptUrls.size}"
        )
        logPlaybackStage(
            session,
            "load_html",
            "quality=${selectedQuality.label} br=${selectedQuality.br} scripts=$scriptSource count=${scriptUrls.size}"
        )
        binding.webPlayer.loadDataWithBaseURL(baseUrl, html, "text/html", "UTF-8", null)
        schedulePlaybackTimeout(session)
    }

    private fun updateController(channel: CctvChannel) {
        binding.textChannelNumber.text = channel.number.toString().padStart(2, '0')
        binding.textChannelTitle.text = channel.title
        updateQualityButton()
    }

    private fun updateQualityButton() {
        binding.buttonQuality.text = selectedQuality.label
    }

    private fun showQualityDialog() {
        showController()
        val qualities = CctvQuality.values()
        val labels = qualities.map { it.label }.toTypedArray()
        val checkedIndex = qualities.indexOf(preferredQuality).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.cctv_quality))
            .setSingleChoiceItems(labels, checkedIndex) { dialog, which ->
                val nextQuality = qualities.getOrNull(which) ?: return@setSingleChoiceItems
                dialog.dismiss()
                if (nextQuality == preferredQuality) return@setSingleChoiceItems
                preferredQuality = nextQuality
                selectedQuality = nextQuality
                updateQualityButton()
                playCurrentChannel(showHint = true)
            }
            .show()
    }

    private fun switchChannel(delta: Int) {
        if (channels.isEmpty()) return
        currentIndex = (currentIndex + delta + channels.size) % channels.size
        playCurrentChannel(showHint = true)
    }

    private fun showController() {
        binding.controller.visibility = View.VISIBLE
        binding.textClock.visibility = View.VISIBLE
        scheduleControllerHide()
    }

    private fun toggleController() {
        if (binding.controller.visibility == View.VISIBLE) {
            hideController()
        } else {
            showController()
        }
    }

    private fun hideController() {
        handler.removeCallbacks(hideControllerRunnable)
        binding.controller.visibility = View.GONE
        binding.textClock.visibility = View.GONE
        // 收起后焦点回到播放区，避免落在已隐藏的按钮上
        if (!binding.playerRoot.isFocused) {
            binding.playerRoot.requestFocus()
        }
    }

    private fun scheduleControllerHide() {
        handler.removeCallbacks(hideControllerRunnable)
        handler.postDelayed(hideControllerRunnable, CONTROLLER_HIDE_DELAY_MS)
    }

    private val hideControllerRunnable = Runnable {
        hideController()
    }

    private fun showChannelHint(text: String) {
        binding.textChannelHint.text = text
        binding.textChannelHint.visibility = View.VISIBLE
        handler.removeCallbacks(hideChannelHintRunnable)
        handler.postDelayed(hideChannelHintRunnable, CHANNEL_HINT_DELAY_MS)
    }

    private val hideChannelHintRunnable = Runnable {
        binding.textChannelHint.visibility = View.GONE
    }

    private fun showError(message: String) {
        handler.removeCallbacks(playbackTimeoutRunnable)
        binding.progressBar.visibility = View.GONE
        binding.textError.text = message
        binding.textError.visibility = View.VISIBLE
        showController()
    }

    private val playbackTimeoutRunnable = Runnable {
        if (playbackTimeoutSession != playbackSession || isFinishing || isDestroyed) {
            return@Runnable
        }
        // 已成功起播（progressBar 隐藏）则不再探测
        if (binding.progressBar.visibility != View.VISIBLE) {
            return@Runnable
        }
        // 超时先探测缓冲状态，区分「正在缓冲」与「真失败」，避免误降级
        probePlaybackBuffer(playbackTimeoutSession)
    }

    private fun schedulePlaybackTimeout(session: Int) {
        playbackTimeoutSession = session
        probeExtends = 0
        handler.removeCallbacks(playbackTimeoutRunnable)
        // 1080P 码率高、起播慢，给更宽的初始窗口；低档维持原阈值
        handler.postDelayed(playbackTimeoutRunnable, initialPlaybackTimeoutMs())
    }

    private fun initialPlaybackTimeoutMs(): Long =
        if (selectedQuality == CctvQuality.P1080) PLAYBACK_TIMEOUT_1080_MS else PLAYBACK_TIMEOUT_MS

    private fun probePlaybackBuffer(session: Int) {
        binding.webPlayer.evaluateJavascript(BUFFER_PROBE_SCRIPT) { result ->
            if (session != playbackSession || isFinishing || isDestroyed) {
                return@evaluateJavascript
            }
            val verdict = result?.trim()?.trim('"') ?: "novideo"
            val canExtend = probeExtends < PLAYBACK_PROBE_MAX_EXTENDS
            logPlaybackStage(
                session,
                "buffer_probe",
                "verdict=$verdict extends=$probeExtends/$PLAYBACK_PROBE_MAX_EXTENDS canExtend=$canExtend"
            )
            when (verdict) {
                "failed" -> recoverPlayback(session, "播放器加载超时", allowQualityFallback = true)
                else -> if (canExtend) {
                    schedulePlaybackExtension(session, verdict)
                } else {
                    val reason = if (verdict == "streaming") "播放器缓冲超时" else "播放器加载超时"
                    recoverPlayback(session, reason, allowQualityFallback = true)
                }
            }
        }
    }

    private fun schedulePlaybackExtension(session: Int, reason: String) {
        probeExtends++
        playbackTimeoutSession = session
        handler.removeCallbacks(playbackTimeoutRunnable)
        handler.postDelayed(playbackTimeoutRunnable, PLAYBACK_PROBE_EXTEND_MS)
        logPlaybackStage(session, "buffer_probe_extend", "reason=$reason extends=$probeExtends")
    }

    private fun recoverPlayback(session: Int, reason: String, allowQualityFallback: Boolean) {
        if (session != playbackSession || isFinishing || isDestroyed) {
            return
        }
        if (allowQualityFallback && downgradeQuality(session, reason)) {
            return
        }
        recoverWithOfficialScripts(session, reason)
    }

    private fun downgradeQuality(session: Int, reason: String): Boolean {
        val nextQuality = selectedQuality.nextLower() ?: return false
        val channel = channels.getOrNull(currentIndex) ?: return false
        selectedQuality = nextQuality
        updateQualityButton()
        showChannelHint("已降级到 ${nextQuality.label}")
        val scriptUrls = if (usingDynamicScripts) {
            dynamicScriptUrls ?: DEFAULT_SCRIPT_URLS
        } else {
            DEFAULT_SCRIPT_URLS
        }
        val scriptSource = if (usingDynamicScripts) "quality-fallback-official" else "quality-fallback"
        AppLog.w(TAG, "qualityFallback reason=$reason next=${nextQuality.label} br=${nextQuality.br}")
        logPlaybackStage(session, "quality_fallback", "reason=$reason next=${nextQuality.label} br=${nextQuality.br}")
        loadChannelHtml(channel, scriptUrls, session, scriptSource)
        return true
    }

    private fun recoverWithOfficialScripts(session: Int, reason: String) {
        if (session != playbackSession || isFinishing || isDestroyed) {
            return
        }
        logPlaybackStage(session, "recovery_requested", "reason=$reason dynamic=$usingDynamicScripts")
        if (usingDynamicScripts || scriptRecoverySession == session) {
            AppLog.e(TAG, "webRecoveryFailed reason=$reason dynamic=$usingDynamicScripts")
            showError(reason)
            return
        }
        if (recoveryInProgress) {
            return
        }
        val channel = channels.getOrNull(currentIndex) ?: return
        val cachedScripts = dynamicScriptUrls
        if (!cachedScripts.isNullOrEmpty()) {
            AppLog.i(TAG, "webRecoveryUseCachedScripts reason=$reason count=${cachedScripts.size}")
            scriptRecoverySession = session
            usingDynamicScripts = true
            loadChannelHtml(channel, cachedScripts, session, "official-cache")
            return
        }

        recoveryInProgress = true
        scriptRecoverySession = session
        binding.progressBar.visibility = View.VISIBLE
        binding.textError.visibility = View.GONE
        AppLog.i(TAG, "webRecoveryFetchOfficialPage reason=$reason channelId=${channel.id}")
        logPlaybackStage(session, "recovery_fetch_official", "channel=${channel.id}")
        lifecycleScope.launch {
            val scripts = withContext(Dispatchers.IO) {
                fetchOfficialScriptUrls(channel.id)
            }
            recoveryInProgress = false
            if (session != playbackSession || isFinishing || isDestroyed) {
                return@launch
            }
            if (scripts.isEmpty()) {
                showError("央视播放器脚本更新失败")
                return@launch
            }
            dynamicScriptUrls = scripts
            usingDynamicScripts = true
            logPlaybackStage(session, "recovery_reload", "scripts=${scripts.size}")
            loadChannelHtml(channel, scripts, session, "official")
        }
    }

    private fun fetchOfficialScriptUrls(channelId: String): List<String> {
        val pageUrl = "https://tv.cctv.com/live/$channelId/m/"
        val request = Request.Builder()
            .url(pageUrl)
            .header("User-Agent", USER_AGENT)
            .header("Referer", "https://tv.cctv.com/")
            .build()
        return runCatching {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    AppLog.e(TAG, "officialPageHttpError code=${response.code} url=$pageUrl")
                    return@use emptyList()
                }
                val html = response.body?.string().orEmpty()
                parseOfficialScriptUrls(html, pageUrl)
            }
        }.getOrElse { error ->
            AppLog.e(TAG, "officialPageFetchError ${error.message}")
            emptyList()
        }
    }

    private fun parseOfficialScriptUrls(html: String, pageUrl: String): List<String> {
        val urls = SCRIPT_SRC_REGEX.findAll(html)
            .map { match -> match.groupValues[1].trim() }
            .mapNotNull { src -> normalizeScriptUrl(src, pageUrl) }
            .filter(::isAllowedCctvScriptUrl)
            .distinct()
            .toList()
        val playerUrls = urls.filter { url ->
            PLAYER_SCRIPT_KEYWORDS.any { keyword -> url.contains(keyword, ignoreCase = true) }
        }
        if (playerUrls.none { it.contains("liveplayer", ignoreCase = true) }) {
            AppLog.e(TAG, "officialPageNoLivePlayer scripts=${playerUrls.joinToString()}")
            return emptyList()
        }
        AppLog.i(TAG, "officialPageScripts ${playerUrls.joinToString()}")
        return playerUrls
    }

    private fun normalizeScriptUrl(src: String, pageUrl: String): String? {
        if (src.isBlank()) return null
        val normalized = when {
            src.startsWith("//") -> "https:$src"
            src.startsWith("http://") || src.startsWith("https://") -> src
            src.startsWith("/") -> {
                val base = Uri.parse(pageUrl)
                "${base.scheme}://${base.host}$src"
            }
            else -> Uri.parse(pageUrl).buildUpon().appendEncodedPath(src).build().toString()
        }
        return if (normalized.startsWith("https://") || normalized.startsWith("http://")) {
            normalized
        } else {
            null
        }
    }

    private fun isAllowedCctvScriptUrl(url: String): Boolean {
        val host = Uri.parse(url).host ?: return false
        return CCTV_HOSTS.any { allowedHost -> host == allowedHost || host.endsWith(".$allowedHost") }
    }

    private fun injectPlayerModeRepeatedly() {
        val session = playbackSession
        repeat(INJECT_ATTEMPTS) { index ->
            handler.postDelayed({
                if (session != playbackSession || isFinishing || isDestroyed) {
                    return@postDelayed
                }
                binding.webPlayer.evaluateJavascript(PLAYER_MODE_SCRIPT) { result ->
                    AppLog.i(TAG, "webInject attempt=${index + 1} result=$result")
                }
            }, index * INJECT_INTERVAL_MS)
        }
    }

    private fun hideCustomView() {
        if (customView == null) return
        binding.fullscreenContainer.removeAllViews()
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        binding.fullscreenContainer.visibility = View.GONE
        binding.webPlayer.visibility = View.VISIBLE
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) {
            return super.dispatchKeyEvent(event)
        }
        val controllerVisible = binding.controller.visibility == View.VISIBLE
        return when (event.keyCode) {
            // 上下：控制栏隐藏时切台；呼出后用于离开/进入按钮行（不再切台）
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (!controllerVisible) {
                    switchChannel(if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1)
                    true
                } else if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    // 离开按钮行，收起控制栏回到切台模式
                    hideController()
                    true
                } else {
                    // 下：进入按钮行（已在内则不重复聚焦）
                    if (!isControllerButtonFocused()) {
                        binding.buttonQuality.requestFocus()
                    }
                    true
                }
            }
            // 左右：隐藏时呼出控制栏；呼出后在 画质↔刷新 间移动焦点
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (!controllerVisible) {
                    showController()
                    true
                } else if (!isControllerButtonFocused()) {
                    binding.buttonQuality.requestFocus()
                    true
                } else {
                    val target =
                        if (binding.buttonQuality.isFocused) binding.buttonRefresh else binding.buttonQuality
                    target.requestFocus()
                    true
                }
            }
            // OK：隐藏时呼出控制栏；呼出后激活当前按钮
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (!controllerVisible) {
                    showController()
                    true
                } else if (!isControllerButtonFocused()) {
                    binding.buttonQuality.requestFocus()
                    true
                } else if (event.repeatCount == 0) {
                    when {
                        binding.buttonQuality.isFocused -> {
                            binding.buttonQuality.performClick()
                            true
                        }
                        binding.buttonRefresh.isFocused -> {
                            binding.buttonRefresh.performClick()
                            true
                        }
                        else -> super.dispatchKeyEvent(event)
                    }
                } else {
                    true // 长按重复不重复触发
                }
            }
            else -> super.dispatchKeyEvent(event)
        }
    }

    private fun isControllerButtonFocused(): Boolean =
        binding.buttonQuality.isFocused || binding.buttonRefresh.isFocused

    override fun onBackPressed() {
        if (binding.controller.visibility == View.VISIBLE || binding.textError.visibility == View.VISIBLE) {
            finish()
            return
        }
        val now = System.currentTimeMillis()
        if (now - exitTime <= EXIT_INTERVAL_MS) {
            finish()
        } else {
            exitTime = now
            Toast.makeText(applicationContext, "再按一次退出播放", Toast.LENGTH_SHORT).show()
            showController()
        }
    }

    override fun onResume() {
        super.onResume()
        binding.webPlayer.onResume()
        injectPlayerModeRepeatedly()
        ViewUtils.keepScreenOn(this, true)
    }

    override fun onPause() {
        binding.webPlayer.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        playbackSession++
        hideCustomView()
        binding.webPlayer.apply {
            stopLoading()
            loadUrl("about:blank")
            removeJavascriptInterface(BRIDGE_NAME)
            webChromeClient = null
            webViewClient = WebViewClient()
            destroy()
        }
        ViewUtils.keepScreenOn(this, false)
        super.onDestroy()
    }

    private inner class Bridge {
        @JavascriptInterface
        fun onVideoState(state: String) {
            handler.post {
                AppLog.i(TAG, "webVideoState $state")
                if (state.contains("\"readyState\":0") || state.contains("\"paused\":true")) {
                    return@post
                }
                handler.removeCallbacks(playbackTimeoutRunnable)
                logPlaybackStage(playbackSession, "video_playable", state.take(160))
                binding.progressBar.visibility = View.GONE
                binding.textError.visibility = View.GONE
            }
        }
    }

    private inner class PlayerGestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            toggleController()
            return true
        }

        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            val start = e1 ?: return false
            val deltaY = e2.y - start.y
            val deltaX = e2.x - start.x
            if (abs(deltaY) < SWIPE_DISTANCE || abs(deltaY) < abs(deltaX)) {
                return false
            }
            if (deltaY < 0) {
                switchChannel(1)
            } else {
                switchChannel(-1)
            }
            return true
        }
    }

    private fun buildPlayerHtml(channelId: String, scriptUrls: List<String>): String {
        val safeId = channelId.ifBlank { "cctv1" }
        val scriptTags = scriptUrls.joinToString("\n") { url ->
            """              <script src="${url.escapeHtmlAttribute()}"></script>"""
        }
        return """
            <!doctype html>
            <html>
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
              <title>MyBili $safeId</title>
              <style>
                html,body,#player{width:100%;height:100%;margin:0;padding:0;background:#000;overflow:hidden;}
                #player{position:fixed;left:0;top:0;z-index:1;}
                video,canvas,object,embed,iframe{
                  position:fixed!important;
                  left:0!important;
                  top:0!important;
                  width:100vw!important;
                  height:100vh!important;
                  max-width:none!important;
                  max-height:none!important;
                  object-fit:contain!important;
                  background:#000!important;
                }
                .control_bar,.controlbar,.title,.logo,.advertising,[id*="ad"],[class*="ad"],[class*="share"]{display:none!important;}
              </style>
$scriptTags
            </head>
            <body>
              <div id="player"></div>
              <script>
                (function() {
                  var channel = '$safeId';
                  function size() {
                    return {
                      w: Math.max(document.documentElement.clientWidth || 0, window.innerWidth || 0, 1280),
                      h: Math.max(document.documentElement.clientHeight || 0, window.innerHeight || 0, 720)
                    };
                  }
                  function report() {
                    try {
                      var video = document.getElementsByTagName('video')[0];
                      if (window.MyBiliCctvBridge && video) {
                        window.MyBiliCctvBridge.onVideoState(JSON.stringify({
                          currentTime: video.currentTime || 0,
                          readyState: video.readyState || 0,
                          paused: !!video.paused,
                          videoWidth: video.videoWidth || 0,
                          videoHeight: video.videoHeight || 0,
                          src: video.currentSrc || video.src || ''
                        }));
                      }
                    } catch (e) {
                      console.log('mybili cctv report error ' + e);
                    }
                  }
                  function fitAndPlay() {
                    var videos = Array.prototype.slice.call(document.getElementsByTagName('video'));
                    videos.forEach(function(video) {
                      video.autoplay = true;
                      video.muted = false;
                      video.controls = false;
                      video.style.objectFit = 'contain';
                      video.play && video.play().catch(function(error) {
                        console.log('mybili cctv play rejected ' + error);
                      });
                    });
                    report();
                  }
                  function start() {
                    if (typeof createLivePlayer !== 'function') {
                      console.log('mybili cctv wait createLivePlayer');
                      setTimeout(start, 300);
                      return;
                    }
                    var s = size();
                    var playerParas = {
                      divId: 'player',
                      w: s.w,
                      h: s.h,
                      t: channel,
                      isAutoPlay: 'true',
                      ruleVisible: 'false',
                      br: '${selectedQuality.br.escapeJsString()}',
                      posterImg: '',
                      isLive4k: 'false',
                      isHttps: 'true',
                      wmode: 'opaque',
                      hasBarrage: 'false',
                      playerType: 'hw',
                      webFullScreenOn: 'false',
                      isLeftBottom: 'false',
                      jumpToApp: 'false',
                      others: ''
                    };
                    console.log('mybili cctv createLivePlayer ' + channel + ' ' + s.w + 'x' + s.h);
                    createLivePlayer(playerParas);
                    setInterval(fitAndPlay, 1000);
                    setTimeout(fitAndPlay, 300);
                    setTimeout(fitAndPlay, 1200);
                    setTimeout(fitAndPlay, 3000);
                  }
                  window.addEventListener('resize', fitAndPlay);
                  start();
                })();
              </script>
            </body>
            </html>
        """.trimIndent()
    }

    private fun String.escapeHtmlAttribute(): String {
        return replace("&", "&amp;").replace("\"", "&quot;")
    }

    private fun String.escapeJsString(): String {
        return replace("\\", "\\\\").replace("'", "\\'")
    }

    private fun logPlaybackStage(session: Int, stage: String, detail: String = "") {
        val elapsed = (SystemClock.elapsedRealtime() - playbackStartMs).coerceAtLeast(0L)
        AppLog.i(TAG, "playbackStage session=$session stage=$stage elapsed=${elapsed}ms $detail")
    }

    companion object {
        const val EXTRA_CHANNEL_INDEX = "channel_index"
        private const val TAG = "CctvPlayerActivity"
        private const val BRIDGE_NAME = "MyBiliCctvBridge"
        private const val INJECT_ATTEMPTS = 12
        private const val INJECT_INTERVAL_MS = 1_500L
        private const val CONTROLLER_HIDE_DELAY_MS = 5_000L
        private const val CHANNEL_HINT_DELAY_MS = 1_500L
        private const val PLAYBACK_TIMEOUT_MS = 6_000L
        private const val PLAYBACK_TIMEOUT_1080_MS = 8_000L
        private const val PLAYBACK_PROBE_EXTEND_MS = 4_000L
        private const val PLAYBACK_PROBE_MAX_EXTENDS = 2
        private const val EXIT_INTERVAL_MS = 2_000L
        private const val SWIPE_DISTANCE = 90
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; TV; MyBili) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0 Mobile Safari/537.36"
        private val CCTV_HOSTS = setOf(
            "cctv.com",
            "cntv.cn",
            "cctvpic.com",
            "player.cntv.cn",
            "data.cctv.com",
            "alicdn.com",
            "qq.com"
        )
        private val DEFAULT_SCRIPT_URLS = listOf(
            "https://r.img.cctvpic.com/photoAlbum/templet/js/jquery-1.7.2.min.js",
            "https://js.player.cntv.cn/creator/swfobject.js",
            "https://js.player.cntv.cn/creator/liveplayer.js"
        )
        private val PLAYER_SCRIPT_KEYWORDS = listOf(
            "jquery",
            "swfobject",
            "liveplayer"
        )
        private val SCRIPT_SRC_REGEX =
            Regex("""<script\b[^>]*\bsrc\s*=\s*["']?([^"'>\s]+)""", RegexOption.IGNORE_CASE)

        private val PLAYER_MODE_SCRIPT = """
            (function() {
              try {
                if (!document.documentElement || !document.body) {
                  return 'not-ready';
                }
                document.documentElement.style.background = '#000';
                document.body.style.background = '#000';
                document.body.style.margin = '0';
                document.body.style.padding = '0';
                document.body.style.overflow = 'hidden';

                var style = document.getElementById('mybili-cctv-web-player-style');
                if (!style) {
                  style = document.createElement('style');
                  style.id = 'mybili-cctv-web-player-style';
                  style.innerHTML = [
                    'html,body{width:100%!important;height:100%!important;margin:0!important;padding:0!important;background:#000!important;overflow:hidden!important;}',
                    '.headernew,.page_bottom,.ELMTg32sIxqsfKtFXmdLumkd231024,.ggcontainer,.swiper-container,.footer,.nav,.column_wrapper,.vspace,[class*="tuijian"],[class*="recommend"],[class*="xuqiu"]{display:none!important;}',
                    '.page_wrap,.page_body,.bg_top_h_tile,.bg_top_owner,.bg_bottom_h_tile,.bg_bottom_owner,.ELMTJ3vol41qoeuF7qDWG00e231024,.ind_video_xq18570,.ind_video_xq18570new,.video_box,#player{position:fixed!important;left:0!important;top:0!important;width:100vw!important;height:100vh!important;margin:0!important;padding:0!important;background:#000!important;z-index:2147483647!important;overflow:hidden!important;}',
                    'video,canvas,object,embed,iframe{position:fixed!important;left:0!important;top:0!important;width:100vw!important;height:100vh!important;max-width:none!important;max-height:none!important;background:#000!important;object-fit:contain!important;z-index:2147483647!important;}'
                  ].join('\n');
                  document.head.appendChild(style);
                }

                var videos = Array.prototype.slice.call(document.getElementsByTagName('video'));
                videos.forEach(function(video) {
                  video.autoplay = true;
                  video.playsInline = false;
                  video.webkitPlaysInline = false;
                  video.muted = false;
                  video.controls = false;
                  video.style.objectFit = 'contain';
                  video.play && video.play().catch(function(error) {
                    console.log('mybili cctv play rejected ' + error);
                  });
                });

                var candidates = Array.prototype.slice.call(document.querySelectorAll('button,a,div,span'));
                candidates.some(function(el) {
                  var text = (el.innerText || el.title || el.className || el.id || '').toString();
                  if (/播放|继续|play|start/i.test(text)) {
                    el.click();
                    return true;
                  }
                  return false;
                });

                var video = videos[0] || null;
                if (window.MyBiliCctvBridge && video) {
                  window.MyBiliCctvBridge.onVideoState(JSON.stringify({
                    currentTime: video.currentTime || 0,
                    readyState: video.readyState || 0,
                    paused: !!video.paused,
                    videoWidth: video.videoWidth || 0,
                    videoHeight: video.videoHeight || 0,
                    src: video.currentSrc || video.src || ''
                  }));
                } else if (window.MyBiliCctvBridge) {
                  window.MyBiliCctvBridge.onVideoState(JSON.stringify({
                    currentTime: 0,
                    readyState: 0,
                    paused: true,
                    videoWidth: 0,
                    videoHeight: 0,
                    src: '',
                    videoCount: videos.length
                  }));
                }
                return 'ok videoCount=' + videos.length + ' title=' + document.title;
              } catch (error) {
                console.log('mybili cctv inject error ' + error);
                return 'error ' + error;
              }
            })();
        """.trimIndent()

        private val BUFFER_PROBE_SCRIPT = """
            (function() {
                try {
                    var v = document.getElementsByTagName('video')[0];
                    if (!v) return 'novideo';
                    var ns = v.networkState, rs = v.readyState, vw = v.videoWidth || 0;
                    // ns=3 NO_SOURCE 视为真失败；vw>0 / readyState>=1 / 正在加载(ns=2) 视为缓冲中
                    if (ns === 3) return 'failed';
                    if (vw > 0 || rs >= 1 || ns === 2) return 'streaming';
                    return 'idle';
                } catch (e) {
                    return 'error';
                }
            })();
        """.trimIndent()
    }

    private enum class CctvQuality(
        val label: String,
        val br: String
    ) {
        P1080("1080P", "1080"),
        P720("720P", "720"),
        P540("540P", "540"),
        P360("360P", "360");

        fun nextLower(): CctvQuality? {
            val qualities = values()
            return qualities.getOrNull(qualities.indexOf(this) + 1)
        }
    }
}
