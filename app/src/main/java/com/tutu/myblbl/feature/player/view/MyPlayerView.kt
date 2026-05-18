package com.tutu.myblbl.feature.player.view

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.AttributeSet
import android.view.FrameMetrics
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.view.Window
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import androidx.media3.ui.AspectRatioFrameLayout
import com.kuaishou.akdanmaku.ui.DanmakuView
import com.tutu.myblbl.R
import com.tutu.myblbl.core.ui.image.ImageLoader
import com.tutu.myblbl.model.dm.DmMaskRepository
import com.tutu.myblbl.model.dm.DmModel
import com.tutu.myblbl.model.dm.SpecialDanmakuModel
import com.tutu.myblbl.model.player.VideoSnapshotData
import com.tutu.myblbl.model.subtitle.SubtitleInfoModel
import com.tutu.myblbl.model.video.quality.AudioQuality
import com.tutu.myblbl.model.video.quality.VideoCodecEnum
import com.tutu.myblbl.model.video.quality.VideoQuality
import com.tutu.myblbl.core.common.ext.isAdvancedDanmakuEnabled
import com.tutu.myblbl.core.common.ext.getDanmakuSmartFilterLevel
import com.tutu.myblbl.feature.player.LiveQualityInfo
import com.tutu.myblbl.feature.player.PlaybackStartupTrace

@OptIn(UnstableApi::class)
class MyPlayerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    companion object {
        const val SHOW_BUFFERING_NEVER = 0
        const val SHOW_BUFFERING_WHEN_PLAYING = 1
        const val SHOW_BUFFERING_ALWAYS = 2

        private const val SURFACE_TYPE_SURFACE_VIEW = 1
        private const val SURFACE_TYPE_TEXTURE_VIEW = 2
        private const val KEYCODE_SYSTEM_NAVIGATION_UP_COMPAT = 280
        private const val KEYCODE_SYSTEM_NAVIGATION_DOWN_COMPAT = 281
        private const val KEYCODE_SYSTEM_NAVIGATION_LEFT_COMPAT = 282
        private const val KEYCODE_SYSTEM_NAVIGATION_RIGHT_COMPAT = 283
        private const val STARTUP_BUFFERING_INDICATOR_DELAY_MS = 700L
        private const val REBUFFER_BUFFERING_INDICATOR_DELAY_MS = 150L
        private const val SHUTTER_FADE_DURATION_MS = 180L
        private const val SHUTTER_TIMEOUT_MS = 8_000L
    }

    private var contentFrame: AspectRatioFrameLayout? = null
    private var shutterView: View? = null
    private var bufferingView: ImageView? = null
    private var errorMessageView: TextView? = null
    private var videoSurfaceView: View? = null

    fun getVideoSurfaceView(): View? = videoSurfaceView

    private var controller: MyPlayerControlView? = null
    private var settingView: MyPlayerSettingView? = null
    private var tapOverlayView: YouTubeOverlay? = null
    private var dmkView: DanmakuView? = null
    private var specialDmkOverlayView: SpecialDanmakuOverlayView? = null
    private var dmkMaskHost: DanmakuMaskHostLayout? = null
    private var pauseIndicatorView: View? = null

    private var player: ExoPlayer? = null
    private var showBuffering: Int = SHOW_BUFFERING_WHEN_PLAYING
    private var controllerShowTimeoutMs: Int = MyPlayerControlView.DEFAULT_SHOW_TIMEOUT_MS
    private var controllerHideOnTouch: Boolean = true
    private var controllerAutoShow: Boolean = false
    private var useController: Boolean = true
    private var isDoubleTapEnabled: Boolean = true
    private var keepContentOnPlayerReset: Boolean = false
    private var customErrorMessage: CharSequence? = null

    private var controllerVisibilityListener: ControllerVisibilityListener? = null

    private var touchInterceptListener: ((MotionEvent) -> Boolean)? = null

    private val controllerComponentListener = object : MyPlayerControlView.VisibilityListener {
        override fun onVisibilityChange(visibility: Int) {
            if (visibility == View.VISIBLE) {
                uiCoordinator?.clearSeekPreview()
            }
            updateContentDescription()
            controllerVisibilityListener?.onVisibilityChanged(visibility)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val bufferingIndicatorRunnable = Runnable {
        val buffering = bufferingView ?: return@Runnable
        val currentPlayer = player ?: run {
            buffering.visibility = GONE
            return@Runnable
        }
        buffering.visibility = if (shouldShowBufferingIndicator(currentPlayer)) VISIBLE else GONE
    }
    private val shutterTimeoutRunnable = Runnable {
        if (!hasRenderedFirstFrame && player != null) {
            forceOpenShutter()
        }
    }
    private val gestureListener = PlayerDoubleTapGestureListener(this)
    private val gestureDetector = GestureDetector(context, gestureListener)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    // Encapsulates danmaku config, lifecycle and data transforms so this view stays focused on UI.
    private val danmakuController = MyPlayerDanmakuController(
        context = context,
        danmakuViewProvider = { dmkView }
    ).also {
        it.playerPositionProvider = { player?.currentPosition ?: 0L }
    }
    private val specialDanmakuController = MyPlayerSpecialDanmakuController(
        overlayViewProvider = { specialDmkOverlayView }
    )
    private val dmMaskController = DmMaskController(
        maskHostProvider = { dmkMaskHost },
        repository = DmMaskRepository()
    ).also {
        it.playerPositionProvider = { player?.currentPosition ?: 0L }
        // Choreographer vsync 驱动 mask 更新，不再依赖 DanmakuView.onDraw
    }

    /**
     * ExoPlayer 在 playback thread 上每帧调用一次，告诉我们"PTS=ptsUs 这一帧将于
     * wall_clock=releaseNs release 到 surface"。把锚点推给 mask 控制器后，mask
     * frameCallback 就能严格推算"自身上屏时刻视频显示的 PTS"。
     *
     * 性能：仅写 3 个 @Volatile 字段，<1µs/帧，30~60Hz 调用对 playback thread 无影响。
     */
    private val videoFrameAnchorListener = VideoFrameMetadataListener {
        presentationTimeUs, releaseTimeNs, _, _ ->
        dmMaskController.onVideoFrameAnchor(presentationTimeUs, releaseTimeNs)
    }

    /**
     * Window FrameMetrics 处理：API 24+ 持续推送上一帧的渲染管道时延。我们把它实测值
     * 推给 mask 控制器做 EMA，让"mask 上屏延迟估计"自适应当前设备/负载状态，
     * 替代原来的硬编码 33ms。
     *
     * 在独立的 [HandlerThread] 上接收 metrics，避免主线程被 metric 计算挤占。
     */
    private var frameMetricsThread: HandlerThread? = null
    private var frameMetricsHandler: Handler? = null
    private var frameMetricsListener: Window.OnFrameMetricsAvailableListener? = null
    private var downTouchX = 0f
    private var downTouchY = 0f
    private var isSwipeSeeking = false
    private var swipeSeekUsesControllerPreview = false
    private var swipeSeekStartPositionMs = 0L
    private var swipeSeekTargetPositionMs = 0L
    private var hasRenderedFirstFrame = false
    private var persistentBottomProgressEnabled = false
    private var uiCoordinator: com.tutu.myblbl.feature.player.PlaybackUiCoordinator? = null

    var seekSession: com.tutu.myblbl.feature.player.SeekSession? = null
    private val heldSeekKeyCodes = mutableSetOf<Int>()
    private var pendingExitSeekProgressOnly: Runnable? = null
    private var pendingHoldStartRunnable: Runnable? = null
    private val holdStartDelayMs = 200L

    // --- Tap accumulation (shared by both seek paths) ---
    private var tapAccumulateBaseMs = 0L
    private var tapAccumulateDeltaMs = 0L
    private var tapCommitRunnable: Runnable? = null
    private val tapCommitDelayMs = 500L

    // --- Timebar-focused seek state (fixed 10s step, interval decreases with hold time) ---
    private var timebarSeekActive = false
    private var timebarSeekForward = true
    private var timebarSeekRunnable: Runnable? = null
    private var timebarSeekIdleRunnable: Runnable? = null
    private var timebarSeekStartMs = 0L
    private var pendingTimebarHoldStartRunnable: Runnable? = null
    private var timebarSeekTargetMs = 0L
    private val timebarSeekIdleTimeoutMs = 200L

    // 必须放在 init {} 之前，否则 init 中的 setupSurfaceView() 会先访问到 null 字段，
    // 造成 ViewRoot performLayout 时 OnLayoutChangeListener 列表里有 null → NPE 崩溃。
    private val maskBoundsUpdater = Runnable { updateMaskVideoBounds() }

    private val maskBoundsLayoutListener = OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        // post 到下一帧，等待 surface view 完成 layout 后再读尺寸，避免拿到中间状态。
        handler.removeCallbacks(maskBoundsUpdater)
        handler.post(maskBoundsUpdater)
    }

    interface ControllerVisibilityListener {
        fun onVisibilityChanged(visibility: Int)
    }

    interface SeekPreviewUpdateListener {
        fun onSeekPreviewUpdated()
    }

    interface RenderEventListener {
        fun onRenderedFirstFrame()
    }

    var onResumeProgressCancelled: (() -> Boolean)? = null
    private var renderEventListener: RenderEventListener? = null
    var seekPreviewUpdateListener: SeekPreviewUpdateListener? = null
    var onUserSeekListener: ((Long) -> Unit)? = null

    private val componentListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            updateBuffering()
            updateErrorMessage()
            if (events.contains(Player.EVENT_PLAYBACK_PARAMETERS_CHANGED)) {
                val speed = player.playbackParameters.speed
                settingView?.setCurrentSpeed(speed)
                danmakuController.updatePlaybackSpeed(speed)
                specialDanmakuController.updatePlaybackSpeed(speed)
                // mask 用 anchor 推算时按 speed 缩放时间差，倍速播放时也能严格对齐。
                dmMaskController.setPlaybackSpeed(speed)
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            updateBuffering()
            updateControllerVisibility()
            updatePauseIndicator()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            updateBuffering()
            updateErrorMessage()
            updateControllerVisibility()
            updatePauseIndicator()
            danmakuController.notifyPlaybackStateChanged(playbackState, player?.isPlaying == true)
            // mask 控制器需要知道 player 是否真的在解码、可以输出新帧。
            // STATE_READY 后再加一段稳定窗口才放开 mask 渲染，避免 seek 完成瞬间 mask 比视频先一帧。
            dmMaskController.setPlaybackReady(playbackState == Player.STATE_READY)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // mask 控制器需要知道是否正在播放：播放时引入 lookahead 补偿管道延迟，暂停时严格对齐当前帧。
            dmMaskController.setPlaying(isPlaying)
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK ||
                reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT
            ) {
                // seek 后 video 解码追上前不渲染 mask，避免 200ms 错位窗口。
                dmMaskController.onSeek()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            updateErrorMessage()
        }

        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
            updateAspectRatio()
            // 视频分辨率/像素宽高比变化会让 letterbox 矩形改变，立即同步给 mask。
            updateMaskVideoBounds()
        }

        override fun onRenderedFirstFrame() {
            hasRenderedFirstFrame = true
            handler.removeCallbacks(shutterTimeoutRunnable)
            updateBuffering()
            shutterView?.animate()?.cancel()
            shutterView?.animate()
                ?.alpha(0f)
                ?.setDuration(SHUTTER_FADE_DURATION_MS)
                ?.withEndAction {
                    if (hasRenderedFirstFrame) {
                        shutterView?.visibility = INVISIBLE
                    }
                }
                ?.start()
            renderEventListener?.onRenderedFirstFrame()
        }
    }

    init {
        LayoutInflater.from(context).inflate(R.layout.my_exo_styled_player_view, this)
        descendantFocusability = FOCUS_AFTER_DESCENDANTS

        contentFrame = findViewById(R.id.exo_content_frame)
        shutterView = findViewById(R.id.exo_shutter)
        bufferingView = findViewById<ImageView>(R.id.exo_buffering).also {
            ImageLoader.loadDrawableRes(it, R.drawable.load_data)
        }
        errorMessageView = findViewById(R.id.exo_error_message)
        dmkView = findViewById(R.id.dmk_view)
        specialDmkOverlayView = findViewById(R.id.special_dmk_overlay)
        dmkMaskHost = findViewById(R.id.dmk_mask_host)
        pauseIndicatorView = findViewById(R.id.image_pause_indicator)

        setupSurfaceView()

        bufferingView?.visibility = GONE
        errorMessageView?.visibility = GONE

        setupController()
        setupSettingView()
        setupYouTubeOverlay()

        isClickable = true
        isFocusable = true
        descendantFocusability = FOCUS_AFTER_DESCENDANTS

        isDoubleTapEnabled = true
    }

    private fun setupSurfaceView() {
        contentFrame?.let { frame ->
            val layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            val surfaceView = SurfaceView(context)
            surfaceView.layoutParams = layoutParams
            videoSurfaceView = surfaceView
            frame.addView(surfaceView, 0)
            // 监听 video surface 与 mask host 的 layout，让 mask 缩放始终对齐视频实际显示矩形。
            surfaceView.addOnLayoutChangeListener(maskBoundsLayoutListener)
            dmkMaskHost?.addOnLayoutChangeListener(maskBoundsLayoutListener)
        }
    }

    private fun setupController() {
        val placeholder: View? = findViewById(R.id.exo_controller_placeholder)
        placeholder?.let { ph ->
            controller = MyPlayerControlView(context)
            controller?.id = R.id.exo_controller
            controller?.layoutParams = ph.layoutParams
            controller?.descendantFocusability = FOCUS_AFTER_DESCENDANTS

            val parent = ph.parent as ViewGroup
            val index = parent.indexOfChild(ph)
            parent.descendantFocusability = FOCUS_AFTER_DESCENDANTS
            parent.removeView(ph)
            parent.addView(controller, index)

            controller?.setOnMenuShowImpl(object : OnMenuShowImpl {
                override fun onShowHide(isShowing: Boolean) {
                    showHideSettingView(isShowing)
                }
            })

            controller?.setOnDmEnableChangeImpl(object : OnDmEnableChangeImpl {
                override fun onDmEnable(enabled: Boolean) {
                    if (settingView?.getDmEnable() != enabled) {
                        settingView?.dmEnableClick()
                    }
                    danmakuController.setEnabled(enabled)
                    specialDanmakuController.setEnabled(enabled)
                }
            })
            controller?.setOnSeekCommitListener { positionMs ->
                onUserSeekListener?.invoke(positionMs)
                syncDanmakuPosition(positionMs, forceSeek = true)
            }
            controller?.setProgressOnlyUiEnabled(!persistentBottomProgressEnabled)
            controller?.addVisibilityListener(controllerComponentListener)
        }

        controllerShowTimeoutMs = if (controller != null) {
            MyPlayerControlView.DEFAULT_SHOW_TIMEOUT_MS
        } else {
            0
        }
        controller?.hideImmediately()
        useController = controller != null
        updateContentDescription()
    }

    private fun setupSettingView() {
        settingView = findViewById(R.id.setting_view)
        settingView?.setOnVisibilityStateChangedListener { isShowing ->
            if (isShowing) {
                controller?.removeHideCallbacks()
            } else if (controller?.isFullyVisible() == true) {
                controller?.restoreRememberedFocus()
                controller?.resetHideCallbacks()
            }
        }
        settingView?.setOnPlayerSettingInnerChange(object : OnPlayerSettingInnerChange {
            override fun onAspectRatioChange(ratio: Int) {
                setResizeMode(ratio)
            }

            override fun onDmEnableChange(enabled: Boolean) {
                controller?.dmEnableButtonChange(enabled)
                danmakuController.setEnabled(enabled)
            }

            override fun onPlaybackSpeedChange(speed: Float) {
                player?.playbackParameters = PlaybackParameters(speed)
                danmakuController.updatePlaybackSpeed(speed)
                specialDanmakuController.updatePlaybackSpeed(speed)
            }

            override fun onDmAlpha(alpha: Float) {
                syncDanmakuSettings()
            }

            override fun onDmTextSize(size: Int) {
                syncDanmakuSettings()
            }

            override fun onDmScreenArea(area: Int) {
                syncDanmakuSettings()
            }

            override fun onDmSpeed(speed: Int) {
                syncDanmakuSettings()
            }

            override fun onDmAllowTop(allow: Boolean) {
                syncDanmakuSettings()
            }

            override fun onDmAllowBottom(allow: Boolean) {
                syncDanmakuSettings()
            }

            override fun onDmMergeDuplicate(merge: Boolean) {
                syncDanmakuSettings()
            }

            override fun onDmSmartShield(enabled: Boolean) {
                dmMaskController.setEnabled(enabled)
            }
        })
        syncDanmakuSettings()
    }

    private fun setupYouTubeOverlay() {
        tapOverlayView = findViewById(R.id.view_youtube_overlay)
        tapOverlayView?.setPlayerView(this)
        tapOverlayView?.setPersistentBottomProgressEnabled(persistentBottomProgressEnabled)
        tapOverlayView?.setCallback(object : YouTubeOverlay.Callback {
            override fun onAnimationStart(displayMode: YouTubeOverlay.DisplayMode) = Unit

            override fun onAnimationEnd(displayMode: YouTubeOverlay.DisplayMode) = Unit

            override fun shouldForward(player: Player, playerView: MyPlayerView, x: Float): Boolean? {
                val currentPosition = player.currentPosition
                val duration = player.duration
                if (
                    player.playbackState == Player.STATE_ENDED ||
                    player.playbackState == Player.STATE_IDLE ||
                    player.playbackState == Player.STATE_BUFFERING
                ) {
                    playerView.cancelInDoubleTapMode()
                    return null
                }
                if (currentPosition > 500 && x < playerView.width * 0.35) {
                    return false
                }
                if (currentPosition >= duration || x <= playerView.width * 0.65) {
                    return null
                }
                return true
            }
        })
        gestureListener.setCallback { _, _ ->
            togglePlaybackByDoubleTap()
        }
    }

    fun setUiCoordinator(coordinator: com.tutu.myblbl.feature.player.PlaybackUiCoordinator?) {
        uiCoordinator = coordinator
        controller?.uiCoordinator = coordinator
        tapOverlayView?.setUiCoordinator(coordinator)
    }

    fun setPlayer(player: ExoPlayer?) {
        val previousPlayer = this.player
        previousPlayer?.removeListener(componentListener)
        // 反注册 video frame metadata listener，避免泄漏到上一个 player。
        previousPlayer?.clearVideoFrameMetadataListener(videoFrameAnchorListener)
        when (val surfaceView = videoSurfaceView) {
            is SurfaceView -> previousPlayer?.clearVideoSurfaceView(surfaceView)
            is TextureView -> previousPlayer?.clearVideoTextureView(surfaceView)
        }
        if (!keepContentOnPlayerReset) {
            closeShutter()
        }
        this.player = player
        player?.addListener(componentListener)
        // 注册到新 player：每帧拿到 (PTS, releaseTimeNs) 锚点驱动 mask 时间戳精确对齐。
        player?.setVideoFrameMetadataListener(videoFrameAnchorListener)
        // player 切换会带来新的播放参数，立即同步当前速度，避免 mask 在拿到首个
        // PARAMETERS_CHANGED 事件前用旧速度推算。
        player?.let { dmMaskController.setPlaybackSpeed(it.playbackParameters.speed) }
        when (val surfaceView = videoSurfaceView) {
            is SurfaceView -> player?.setVideoSurfaceView(surfaceView)
            is TextureView -> player?.setVideoTextureView(surfaceView)
        }

        controller?.setPlayer(player)
        controller?.setRepeatMode(player?.repeatMode ?: Player.REPEAT_MODE_OFF)
        tapOverlayView?.setPlayer(player)

        updateBuffering()
        updateErrorMessage()
    }

    private fun closeShutter() {
        hasRenderedFirstFrame = false
        shutterView?.animate()?.cancel()
        shutterView?.alpha = 1f
        shutterView?.visibility = VISIBLE
        handler.removeCallbacks(shutterTimeoutRunnable)
        handler.postDelayed(shutterTimeoutRunnable, SHUTTER_TIMEOUT_MS)
    }

    fun forceOpenShutter() {
        handler.removeCallbacks(shutterTimeoutRunnable)
        if (!hasRenderedFirstFrame) {
            shutterView?.animate()?.cancel()
            shutterView?.animate()
                ?.alpha(0f)
                ?.setDuration(SHUTTER_FADE_DURATION_MS)
                ?.withEndAction {
                    if (!hasRenderedFirstFrame) {
                        shutterView?.visibility = INVISIBLE
                    }
                }
                ?.start()
        }
    }

    fun prepareForPlaybackTransition() {
        closeShutter()
        handler.removeCallbacks(bufferingIndicatorRunnable)
        bufferingView?.visibility = GONE
    }

    private fun updateAspectRatio() {
        val videoSize = player?.videoSize ?: return
        val width = videoSize.width
        val height = videoSize.height
        if (width == 0 || height == 0) return

        val pixelWidthHeightRatio = videoSize.pixelWidthHeightRatio
        val aspectRatio = (width * pixelWidthHeightRatio) / height.toFloat()
        contentFrame?.setAspectRatio(aspectRatio)
    }

    /**
     * 把 video surface 的当前显示矩形换算到 maskHost 坐标系，推送给 [DmMaskController]。
     * 触发时机：video size 变化、resize mode 切换、surface view 重新 layout、mask host 自身 layout 变化。
     */
    private fun updateMaskVideoBounds() {
        val surface = videoSurfaceView ?: return
        val maskHost = dmkMaskHost ?: return
        val w = surface.width
        val h = surface.height
        if (w <= 0 || h <= 0) return
        // surface view 与 mask host 都以 player view 为根，可直接用 view-tree 内坐标差。
        val surfaceLoc = IntArray(2)
        val hostLoc = IntArray(2)
        surface.getLocationInWindow(surfaceLoc)
        maskHost.getLocationInWindow(hostLoc)
        val left = surfaceLoc[0] - hostLoc[0]
        val top = surfaceLoc[1] - hostLoc[1]
        dmMaskController.setVideoBounds(left, top, w, h)
    }

    private fun updateBuffering() {
        val buffering = bufferingView ?: return
        val currentPlayer = player ?: run {
            handler.removeCallbacks(bufferingIndicatorRunnable)
            buffering.visibility = GONE
            return
        }

        if (!shouldShowBufferingIndicator(currentPlayer)) {
            handler.removeCallbacks(bufferingIndicatorRunnable)
            buffering.visibility = GONE
            return
        }

        if (buffering.visibility == VISIBLE) {
            return
        }

        handler.removeCallbacks(bufferingIndicatorRunnable)
        val delayMs = if (hasRenderedFirstFrame) {
            REBUFFER_BUFFERING_INDICATOR_DELAY_MS
        } else {
            STARTUP_BUFFERING_INDICATOR_DELAY_MS
        }
        handler.postDelayed(bufferingIndicatorRunnable, delayMs)
    }

    private fun shouldShowBufferingIndicator(currentPlayer: Player): Boolean {
        val isBuffering = currentPlayer.playbackState == Player.STATE_BUFFERING
        return when (showBuffering) {
            SHOW_BUFFERING_ALWAYS -> true
            SHOW_BUFFERING_WHEN_PLAYING -> isBuffering && currentPlayer.playWhenReady
            else -> false
        }
    }

    private fun updateErrorMessage() {
        val view = errorMessageView ?: return
        val message = customErrorMessage?.toString().takeUnless { it.isNullOrBlank() }

        if (message.isNullOrBlank()) {
            view.visibility = GONE
            view.text = ""
        } else {
            view.text = message
            view.visibility = VISIBLE
        }
    }

    private fun updateControllerVisibility() {
        maybeShowController(false)
    }

    private fun updatePauseIndicator() {
        val p = player ?: return
        val isPaused = !p.playWhenReady && p.playbackState == Player.STATE_READY
        pauseIndicatorView?.visibility = if (isPaused) VISIBLE else GONE
    }

    private fun maybeShowController(isForced: Boolean) {
        if (!useController() || player == null) return
        if (tapOverlayView?.isOverlayShowing() == true) return

        val shouldShowIndefinitely = shouldShowControllerIndefinitely()
        val controller = controller ?: return

        if (controller.isFullyVisible()) {
            if (shouldShowIndefinitely) {
                controller.setShowTimeoutMs(0)
            } else if (isForced) {
                controller.resetHideCallbacks()
            }
            return
        }

        if (!isForced && !shouldShowIndefinitely) return

        showController(shouldShowIndefinitely)
    }

    private fun shouldShowControllerIndefinitely(): Boolean {
        val currentPlayer = player ?: return true

        if (controllerAutoShow) {
            if (currentPlayer.currentTimeline.isEmpty) {
                return false
            }
            if (
                currentPlayer.playbackState == Player.STATE_IDLE ||
                currentPlayer.playbackState == Player.STATE_ENDED
            ) {
                return true
            }
            if (!currentPlayer.playWhenReady) {
                return true
            }
        }
        return false
    }

    private fun showController(indefinitely: Boolean) {
        if (!useController()) return

        controller?.setShowTimeoutMs(if (indefinitely) 0 else controllerShowTimeoutMs)
        controller?.show(focusPlayPause = true)
    }

    fun hideController() {
        controller?.hide()
    }

    fun getController(): MyPlayerControlView? = controller

    fun showController() {
        showController(shouldShowControllerIndefinitely())
    }

    fun isControllerFullyVisible(): Boolean = controller?.isFullyVisible() ?: false

    fun isSettingViewShowing(): Boolean = settingView?.isShowing() ?: false

    fun removeControllerHideCallbacks() {
        controller?.removeHideCallbacks()
    }

    fun resetControllerHideCallbacks() {
        controller?.resetHideCallbacks()
    }

    private fun toggleControllerVisibility() {
        if (!useController() || player == null) return

        if (controller?.isFullyVisible() == true && controllerHideOnTouch) {
            controller?.hide()
        } else {
            maybeShowController(true)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val isBackKey = event.keyCode == KeyEvent.KEYCODE_BACK
        if (player == null) return super.dispatchKeyEvent(event)
        if (controller == null) return false

        // If focus is outside this MyPlayerView (e.g. on the related-videos panel),
        // let the event propagate normally so D-pad navigation works within the panel.
        if (isDpadKey(event.keyCode)) {
            val focused = findFocus()
            if (focused != null && !isViewDescendant(focused)) {
                return super.dispatchKeyEvent(event)
            }
        }

        if (event.keyCode == KeyEvent.KEYCODE_MENU &&
            event.action == KeyEvent.ACTION_DOWN &&
            settingView?.isShowing() != true
        ) {
            showHideSettingView(true)
            return true
        }

        if (event.keyCode == KeyEvent.KEYCODE_BACK &&
            event.action == KeyEvent.ACTION_DOWN &&
            settingView?.isShowing() != true &&
            onResumeProgressCancelled?.invoke() == true
        ) {
            return true
        }

        if (event.keyCode == KeyEvent.KEYCODE_BACK &&
            event.action == KeyEvent.ACTION_DOWN &&
            settingView?.isShowing() == true
        ) {
            settingView?.onBack()
            return true
        }

        if (settingView?.isShowing() == true) {
            return settingView?.dispatchKeyEvent(event) ?: super.dispatchKeyEvent(event)
        }

        if (seekSession?.isActive() == true) {
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                || event.keyCode == KEYCODE_SYSTEM_NAVIGATION_LEFT_COMPAT || event.keyCode == KEYCODE_SYSTEM_NAVIGATION_RIGHT_COMPAT) {
                return handleSeekSessionKeyEvent(event)
            }
            // Non-seek key during active session (e.g. BACK): cancel session and clean up UI
            seekSession?.cancel()
            cancelPendingHoldStart()
            cancelPendingExitSeekProgressOnly()
            controller?.cancelSeekPreview()
            tapOverlayView?.cancelSwipeSeek()
            controller?.exitSeekProgressOnly()
        }

        // Cancel any seek on BACK key
        if (isBackKey && event.action == KeyEvent.ACTION_DOWN) {
            val wasTimebarSeek = timebarSeekActive
            val wasSeeking = wasTimebarSeek || tapCommitRunnable != null
            if (timebarSeekActive) {
                cancelTimebarSeekLoop()
                cancelTimebarSeekIdle()
                timebarSeekActive = false
            }
            if (tapCommitRunnable != null) {
                cancelTapCommit()
                tapAccumulateDeltaMs = 0L
                tapAccumulateBaseMs = 0L
            }
            if (wasSeeking) {
                cancelPendingHoldStart()
                cancelPendingExitSeekProgressOnly()
                controller?.cancelSeekPreview()
                tapOverlayView?.cancelSwipeSeek()
                controller?.exitSeekProgressOnly()
                // Restore appropriate UI state
                if (wasTimebarSeek) {
                    controller?.show()
                    controller?.startProgressUpdates()
                }
                uiCoordinator?.transition(com.tutu.myblbl.feature.player.UiEvent.SeekCancelled)
                return true
            }
        }

        // Timebar-focused seek has priority: always route LEFT/RIGHT to timebar when it's focused
        if (timebarSeekActive && (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                || event.keyCode == KEYCODE_SYSTEM_NAVIGATION_LEFT_COMPAT || event.keyCode == KEYCODE_SYSTEM_NAVIGATION_RIGHT_COMPAT)) {
            val forward = event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                || event.keyCode == KEYCODE_SYSTEM_NAVIGATION_RIGHT_COMPAT
            return handleTimebarSeekKeyEvent(event, forward)
        }

        // When tap accumulation is active (commit pending), controller is in progress-only mode.
        // Route seek keys to handleSeekSessionKeyEvent to avoid falling through to maybeShowController.
        if (tapCommitRunnable != null &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                || event.keyCode == KEYCODE_SYSTEM_NAVIGATION_LEFT_COMPAT || event.keyCode == KEYCODE_SYSTEM_NAVIGATION_RIGHT_COMPAT)) {
            return handleSeekSessionKeyEvent(event)
        }

        if (gestureListener.isDoubleTapping) {
            if (event.action == KeyEvent.ACTION_DOWN &&
                (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                    || event.keyCode == KEYCODE_SYSTEM_NAVIGATION_LEFT_COMPAT || event.keyCode == KEYCODE_SYSTEM_NAVIGATION_RIGHT_COMPAT)
            ) {
                gestureListener.cancelInDoubleTapMode()
                handleSeekSessionKeyEvent(event)
            } else {
                gestureListener.handleKeyDown(event)
            }
            return true
        }

        val isDpadKey = isDpadKey(event.keyCode)
        val isSeekKey = event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT
            || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
            || event.keyCode == KEYCODE_SYSTEM_NAVIGATION_LEFT_COMPAT
            || event.keyCode == KEYCODE_SYSTEM_NAVIGATION_RIGHT_COMPAT

        if (isDpadKey && useController) {
            val controllerVisible = controller?.isFullyVisible() == true
            if (isSeekKey && controller?.isTimebarFocused() == true) {
                val forward = event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                    || event.keyCode == KEYCODE_SYSTEM_NAVIGATION_RIGHT_COMPAT
                return handleTimebarSeekKeyEvent(event, forward)
            }
            if (isSeekKey && event.action == KeyEvent.ACTION_DOWN) {
                if (!controllerVisible || controller?.isScrubbingTimeBar() == true) {
                    return handleSeekSessionKeyEvent(event)
                }
            } else if (isSeekKey && event.action == KeyEvent.ACTION_UP) {
                if (seekSession?.isActive() == true || pendingHoldStartRunnable != null) {
                    return handleSeekSessionKeyEvent(event)
                }
            }
            // When controller is visible and a button (not timebar) has focus,
            // pressing DOWN opens the related videos panel if the related button is visible.
            if (controllerVisible
                && event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                && event.action == KeyEvent.ACTION_DOWN
                && controller?.isTimebarFocused() != true
                && controller?.isRelatedButtonVisible() == true
            ) {
                controller?.onRelatedButtonClick()
                return true
            }
            if (!controllerVisible) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    if (!gestureListener.handleKeyDown(event) && !gestureListener.isDoubleTapping) {
                        maybeShowController(true)
                        controller?.focusButtonByKeyDown(event)
                    }
                }
                return true
            }
        }

        if (controller?.dispatchMediaKeyEvent(event) == true) {
            maybeShowController(true)
            return true
        }

        val superResult = super.dispatchKeyEvent(event)
        if (superResult) {
            maybeShowController(true)
            return true
        }

        if (isDpadKey && useController && event.action == KeyEvent.ACTION_DOWN) {
            maybeShowController(true)
            val handled = controller?.handleDpadWhenSuperNotHandled(event) ?: false
            return handled
        }

        return false
    }

    private fun isDpadKey(keyCode: Int): Boolean {
        return keyCode == KeyEvent.KEYCODE_DPAD_UP ||
            keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
            keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
            keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
            keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KEYCODE_SYSTEM_NAVIGATION_UP_COMPAT ||
            keyCode == KEYCODE_SYSTEM_NAVIGATION_DOWN_COMPAT ||
            keyCode == KEYCODE_SYSTEM_NAVIGATION_LEFT_COMPAT ||
            keyCode == KEYCODE_SYSTEM_NAVIGATION_RIGHT_COMPAT
    }

    private fun isViewDescendant(view: View): Boolean {
        var v: View? = view
        while (v != null) {
            if (v === this) return true
            v = v.parent as? View
        }
        return false
    }

    private fun handleSeekSessionKeyEvent(event: KeyEvent): Boolean {
        val session = seekSession ?: return false
        val forward = event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
            || event.keyCode == KEYCODE_SYSTEM_NAVIGATION_RIGHT_COMPAT

        if (event.action == KeyEvent.ACTION_DOWN) {
            cancelPendingExitSeekProgressOnly()
            if (event.repeatCount == 0) {
                heldSeekKeyCodes.add(event.keyCode)
            }
            if (!session.isActive()) {
                uiCoordinator?.transition(com.tutu.myblbl.feature.player.UiEvent.SeekTypeChanged(
                    com.tutu.myblbl.feature.player.SeekType.HOLD))
                controller?.enterSeekProgressOnly()
                controller?.requestTimeBarFocus()
                session.startHoldSeek(forward)
                // Delay tick loop start so short press can be detected
                cancelPendingHoldStart()
                val startRunnable = Runnable {
                    if (seekSession?.isActive() == true) {
                        seekSession?.beginHoldTickLoop()
                    }
                    pendingHoldStartRunnable = null
                }
                pendingHoldStartRunnable = startRunnable
                postDelayed(startRunnable, holdStartDelayMs)
            } else if (session.isForwardDirection() != forward) {
                cancelPendingHoldStart()
                session.changeDirection(forward)
                // Restart hold delay for new direction
                val startRunnable = Runnable {
                    if (seekSession?.isActive() == true) {
                        seekSession?.beginHoldTickLoop()
                    }
                    pendingHoldStartRunnable = null
                }
                pendingHoldStartRunnable = startRunnable
                postDelayed(startRunnable, holdStartDelayMs)
            }
            return true
        } else if (event.action == KeyEvent.ACTION_UP) {
            heldSeekKeyCodes.remove(event.keyCode)
            if (heldSeekKeyCodes.isEmpty() && session.isActive()) {
                val holdStarted = pendingHoldStartRunnable != null
                cancelPendingHoldStart()
                if (holdStarted) {
                    // Short press: accumulate instead of immediate seek
                    handleTapAccumulate(forward)
                    session.resetSilently()
                } else {
                    // Long press: clear tap accumulation, finish hold seek
                    resetTapAccumulate()
                    val finishRunnable = Runnable {
                        if (seekSession?.isActive() == true) {
                            seekSession?.finishSeek()
                            controller?.cancelSeekPreview()
                            controller?.exitSeekProgressOnly()
                            tapOverlayView?.finishSwipeSeek()
                            syncDanmakuPosition(
                                player?.currentPosition?.coerceAtLeast(0L) ?: 0L,
                                forceSeek = true
                            )
                            if (player?.isPlaying == true) {
                                resumeDanmaku()
                            }
                        }
                    }
                    pendingExitSeekProgressOnly = finishRunnable
                    postDelayed(finishRunnable, 150L)
                }
            }
            return true
        }
        return session.isActive()
    }

    private fun cancelPendingHoldStart() {
        pendingHoldStartRunnable?.let { removeCallbacks(it) }
        pendingHoldStartRunnable = null
    }

    private fun cancelPendingExitSeekProgressOnly() {
        pendingExitSeekProgressOnly?.let { removeCallbacks(it) }
        pendingExitSeekProgressOnly = null
    }

    // ==================== Tap accumulation (shared by both seek paths) ====================

    private fun handleTapAccumulate(forward: Boolean) {
        val currentPlayer = player ?: return
        val duration = currentPlayer.duration
        if (duration <= 0L) return

        cancelTapCommit()

        if (tapAccumulateDeltaMs == 0L) {
            tapAccumulateBaseMs = currentPlayer.currentPosition
        }

        tapAccumulateDeltaMs += 10_000L * if (forward) 1 else -1
        val targetMs = (tapAccumulateBaseMs + tapAccumulateDeltaMs).coerceIn(0L, duration)

        controller?.beginSeekPreview(targetMs)
        val seekSeconds = kotlin.math.abs(tapAccumulateDeltaMs / 1000L).toInt().coerceAtLeast(1)
        tapOverlayView?.showSwipeSeek(
            targetPositionMs = targetMs,
            durationMs = duration,
            deltaMs = tapAccumulateDeltaMs,
            showBottomProgress = false,
            showThumbnails = false,
            seekSeconds = seekSeconds
        )

        val commitRunnable = Runnable {
            val p = player ?: return@Runnable
            val wasTimebarSeek = timebarSeekActive
            val finalTarget = (tapAccumulateBaseMs + tapAccumulateDeltaMs).coerceIn(0L, p.duration.coerceAtLeast(0L))
            p.seekTo(finalTarget)
            onUserSeekListener?.invoke(finalTarget)
            syncDanmakuPosition(finalTarget, forceSeek = true)
            controller?.cancelSeekPreview()
            tapOverlayView?.finishSwipeSeek()
            if (wasTimebarSeek) {
                timebarSeekActive = false
                timebarSeekStartMs = 0L
                controller?.show()
                controller?.startProgressUpdates()
                controller?.requestTimeBarFocus()
                uiCoordinator?.transition(com.tutu.myblbl.feature.player.UiEvent.SeekFinished)
            } else {
                controller?.exitSeekProgressOnly()
                uiCoordinator?.transition(com.tutu.myblbl.feature.player.UiEvent.SeekFinished)
            }
            tapAccumulateDeltaMs = 0L
            tapAccumulateBaseMs = 0L
            tapCommitRunnable = null
        }
        tapCommitRunnable = commitRunnable
        postDelayed(commitRunnable, tapCommitDelayMs)
    }

    private fun cancelTapCommit() {
        tapCommitRunnable?.let { removeCallbacks(it) }
        tapCommitRunnable = null
    }

    private fun resetTapAccumulate() {
        cancelTapCommit()
        tapAccumulateDeltaMs = 0L
        tapAccumulateBaseMs = 0L
    }

    // ==================== Timebar-focused seek (accelerated, preview-only) ====================

    private fun handleTimebarSeekKeyEvent(event: KeyEvent, forward: Boolean): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            cancelTimebarSeekIdle()
            if (!timebarSeekActive) {
                timebarSeekActive = true
                timebarSeekForward = forward
                timebarSeekStartMs = 0L
                uiCoordinator?.transition(com.tutu.myblbl.feature.player.UiEvent.SeekTypeChanged(
                    com.tutu.myblbl.feature.player.SeekType.TAP))
                controller?.enterSeekProgressOnly()
            }
            // Only schedule hold start on initial press (repeatCount == 0).
            // Repeat events would keep pushing the delay forward, preventing the hold from ever starting.
            if (event.repeatCount == 0) {
                cancelPendingTimebarHoldStart()
                val startRunnable = Runnable {
                    if (timebarSeekActive) {
                        if (timebarSeekForward != forward) {
                            timebarSeekForward = forward
                            timebarSeekStartMs = 0L
                        }
                        doTimebarSeekTick()
                        startTimebarSeekLoop()
                    }
                    pendingTimebarHoldStartRunnable = null
                }
                pendingTimebarHoldStartRunnable = startRunnable
                postDelayed(startRunnable, holdStartDelayMs)
            }
            return true
        } else if (event.action == KeyEvent.ACTION_UP) {
            val holdStarted = pendingTimebarHoldStartRunnable != null
            cancelPendingTimebarHoldStart()
            cancelTimebarSeekLoop()
            if (holdStarted) {
                // Short press: accumulate, don't start idle (commit will clean up)
                handleTapAccumulate(forward)
            } else {
                // Long press: clear tap accumulation, seek to target
                resetTapAccumulate()
                if (timebarSeekActive && player != null) {
                    player?.seekTo(timebarSeekTargetMs)
                    onUserSeekListener?.invoke(timebarSeekTargetMs)
                    syncDanmakuPosition(timebarSeekTargetMs, forceSeek = true)
                }
                controller?.cancelSeekPreview()
                startTimebarSeekIdle()
            }
            return true
        }
        return timebarSeekActive
    }

    private fun cancelPendingTimebarHoldStart() {
        pendingTimebarHoldStartRunnable?.let { removeCallbacks(it) }
        pendingTimebarHoldStartRunnable = null
    }

    private fun doTimebarSeekTick() {
        val currentPlayer = player ?: return
        val duration = currentPlayer.duration
        if (duration <= 0L) return

        if (timebarSeekStartMs == 0L) {
            timebarSeekTargetMs = currentPlayer.currentPosition
            timebarSeekStartMs = android.os.SystemClock.uptimeMillis()
        }

        val step = 10_000L * if (timebarSeekForward) 1 else -1
        timebarSeekTargetMs = (timebarSeekTargetMs + step).coerceIn(0L, duration)

        controller?.beginSeekPreview(timebarSeekTargetMs)
    }

    private fun startTimebarSeekLoop() {
        cancelTimebarSeekLoop()
        val interval = getTimebarSeekIntervalMs()
        val runnable = Runnable {
            if (timebarSeekActive) {
                doTimebarSeekTick()
                startTimebarSeekLoop()
            }
        }
        timebarSeekRunnable = runnable
        postDelayed(runnable, interval)
    }

    private fun getTimebarSeekIntervalMs(): Long {
        val elapsed = android.os.SystemClock.uptimeMillis() - timebarSeekStartMs
        return when {
            elapsed < 1000L -> 100L
            elapsed < 2000L -> 50L
            elapsed < 3000L -> 25L
            else -> 10L
        }
    }

    private fun cancelTimebarSeekLoop() {
        timebarSeekRunnable?.let { removeCallbacks(it) }
        timebarSeekRunnable = null
    }

    private fun startTimebarSeekIdle() {
        cancelTimebarSeekIdle()
        val runnable = Runnable {
            if (timebarSeekActive) {
                timebarSeekActive = false
                timebarSeekStartMs = 0L
                controller?.show()
                controller?.startProgressUpdates()
                uiCoordinator?.transition(com.tutu.myblbl.feature.player.UiEvent.SeekFinished)
            }
        }
        timebarSeekIdleRunnable = runnable
        postDelayed(runnable, timebarSeekIdleTimeoutMs)
    }

    private fun cancelTimebarSeekIdle() {
        timebarSeekIdleRunnable?.let { removeCallbacks(it) }
        timebarSeekIdleRunnable = null
    }

    // ==================== End timebar seek ====================

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        touchInterceptListener?.let { if (it(event)) return true }
        if (isSwipeSeeking && handleSwipeSeekTouch(event)) {
            return true
        }
        if (controller?.isTouchWithinInteractiveArea(event.x, event.y) == true) {
            return false
        }
        if (handleSwipeSeekTouch(event)) {
            return true
        }
        if (isDoubleTapEnabled) {
            gestureDetector.onTouchEvent(event)
            return true
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        toggleControllerVisibility()
        return super.performClick()
    }

    fun setResizeMode(resizeMode: Int) {
        contentFrame?.setResizeMode(resizeMode)
        settingView?.setCurrentScreenRatio(resizeMode)
        // resize mode 变化会改变 letterbox 大小；surface view 完成 layout 后自然会回调
        // maskBoundsLayoutListener，这里再 post 一次防止边界 case 漏掉。
        handler.removeCallbacks(maskBoundsUpdater)
        handler.post(maskBoundsUpdater)
    }

    fun setTitle(title: String?) {
        controller?.setTitle(title)
    }

    fun setSubTitle(subTitle: String?) {
        controller?.setSubTitle(subTitle)
    }

    fun setLiveDuration(text: String) {
        controller?.setLiveDuration(text)
    }

    fun setCustomErrorMessage(message: CharSequence?) {
        customErrorMessage = message
        updateErrorMessage()
    }

    fun setOnPlayerSettingChange(listener: OnPlayerSettingChange?) {
        settingView?.setOnPlayerSettingChange(listener)
    }

    fun setOnVideoSettingChangeListener(listener: OnVideoSettingChangeListener?) {
        controller?.setOnVideoSettingChangeListener(listener)
    }

    fun setControllerVisibilityListener(listener: ControllerVisibilityListener?) {
        controllerVisibilityListener = listener
        updateContentDescription()
    }

    fun setRenderEventListener(listener: RenderEventListener?) {
        renderEventListener = listener
    }

    fun setTouchInterceptListener(listener: ((MotionEvent) -> Boolean)?) {
        touchInterceptListener = listener
    }

    fun setPersistentBottomProgressEnabled(enabled: Boolean) {
        persistentBottomProgressEnabled = enabled
        tapOverlayView?.setPersistentBottomProgressEnabled(enabled)
        controller?.setProgressOnlyUiEnabled(!enabled)
        if (!enabled) {
            uiCoordinator?.clearSeekPreview()
        }
    }

    fun showHoldSeekOverlay(targetPositionMs: Long, durationMs: Long, deltaMs: Long) {
        tapOverlayView?.showSwipeSeek(
            targetPositionMs = targetPositionMs,
            durationMs = durationMs,
            deltaMs = deltaMs,
            showBottomProgress = false,
            showThumbnails = false
        )
    }

    fun finishHoldSeekOverlay() {
        tapOverlayView?.finishSwipeSeek()
    }

    fun setShowBuffering(mode: Int) {
        showBuffering = mode
        updateBuffering()
    }

    fun setKeepContentOnPlayerReset(keepContentOnPlayerReset: Boolean) {
        this.keepContentOnPlayerReset = keepContentOnPlayerReset
    }

    fun setQualities(qualities: List<VideoQuality>) {
        settingView?.setVideoQualities(qualities)
    }

    fun setLiveQualities(qualities: List<LiveQualityInfo>) {
        settingView?.setLiveQualities(qualities)
    }

    fun setSubtitles(models: List<SubtitleInfoModel>) {
        settingView?.setSubtitles(models)
    }

    fun selectQuality(quality: VideoQuality) {
        settingView?.setCurrentVideoQuality(quality)
    }

    fun selectLiveQuality(qn: Int) {
        settingView?.selectLiveQuality(qn)
    }

    fun showLiveQualityMenu() {
        controller?.rememberCurrentFocusTarget()
        settingView?.showLiveQualityMenu()
    }

    fun setAudiosSelect(qualities: List<AudioQuality>) {
        settingView?.setAudioQualities(qualities)
    }

    fun selectAudio(audioQuality: AudioQuality) {
        settingView?.setCurrentAudioQuality(audioQuality)
    }

    fun setVideoCodec(codecs: List<VideoCodecEnum>) {
        settingView?.setVideoCodecs(codecs)
    }

    fun selectVideoCodec(videoCodec: VideoCodecEnum) {
        settingView?.setCurrentVideoCodec(videoCodec)
    }

    fun selectSubtitle(position: Int) {
        settingView?.setCurrentSubtitlePosition(position)
    }

    fun setPlaySpeed(speed: Float) {
        player?.playbackParameters = PlaybackParameters(speed)
        settingView?.setCurrentSpeed(speed)
        danmakuController.updatePlaybackSpeed(speed)
        specialDanmakuController.updatePlaybackSpeed(speed)
    }

    fun setAfterPlayMode(mode: com.tutu.myblbl.feature.player.settings.AfterPlayMode) {
        settingView?.setAfterPlayMode(mode)
    }

    fun getAfterPlayMode(): com.tutu.myblbl.feature.player.settings.AfterPlayMode {
        return settingView?.getAfterPlayMode() ?: com.tutu.myblbl.feature.player.settings.AfterPlayMode.NEXT_EPISODE
    }

    fun showSubtitleSettingView() {
        controller?.rememberCurrentFocusTarget()
        settingView?.showSubtitleMenu()
    }

    fun setRepeatMode(repeatMode: Int) {
        controller?.setRepeatMode(repeatMode)
    }

    fun setSeekSecond(seconds: Int) {
        tapOverlayView?.seekSeconds = seconds
        controller?.setFfDuration(seconds.coerceAtLeast(1).toLong() * 1000L)
    }

    fun showHideSettingView(show: Boolean) {
        if (show) {
            if (seekSession?.isActive() == true) {
                seekSession?.cancel()
            }
            settingView?.setCurrentSpeed(player?.playbackParameters?.speed ?: 1f)
            controller?.rememberCurrentFocusTarget()
        }
        settingView?.showHide(show)
        if (!show) {
            val currentPositionMs = player?.currentPosition?.coerceAtLeast(0L) ?: 0L
            syncDanmakuPosition(currentPositionMs, forceSeek = true)
            restoreControllerAfterGesture(showIndefinitely = true)
        }
    }

    fun showHideDmSwitchButton(show: Boolean) {
        controller?.showHideDmSwitchButton(show)
    }

    fun setMirrorEnabled(enabled: Boolean) {
        val currentPlayer = player ?: return
        val frame = contentFrame ?: return

        val currentSurface = videoSurfaceView
        if (currentSurface != null && (currentSurface is TextureView) == enabled) return

        when (currentSurface) {
            is SurfaceView -> currentPlayer.clearVideoSurfaceView(currentSurface)
            is TextureView -> currentPlayer.clearVideoTextureView(currentSurface)
        }
        frame.removeView(currentSurface)
        currentSurface?.removeOnLayoutChangeListener(maskBoundsLayoutListener)

        val layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        if (enabled) {
            val textureView = TextureView(context)
            textureView.layoutParams = layoutParams
            textureView.scaleX = -1f
            videoSurfaceView = textureView
            frame.addView(textureView, 0)
            textureView.addOnLayoutChangeListener(maskBoundsLayoutListener)
            currentPlayer.setVideoTextureView(textureView)
        } else {
            val surfaceView = SurfaceView(context)
            surfaceView.layoutParams = layoutParams
            videoSurfaceView = surfaceView
            frame.addView(surfaceView, 0)
            surfaceView.addOnLayoutChangeListener(maskBoundsLayoutListener)
            currentPlayer.setVideoSurfaceView(surfaceView)
        }
    }

    fun showHideMirrorButton(show: Boolean) {
        controller?.showHideMirrorButton(show)
    }

    fun showHideNextPrevious(show: Boolean) {
        controller?.showHideNextPrevious(show)
    }

    fun showHideFfRe(show: Boolean) {
        controller?.showHideFfRe(show)
    }

    fun setSimpleKeyPressEnabled(enabled: Boolean) {
        controller?.setSimpleKeyPressEnabled(enabled)
    }

    fun setEpisodeNavigationEnabled(previousEnabled: Boolean, nextEnabled: Boolean) {
        controller?.setEpisodeNavigationEnabled(previousEnabled, nextEnabled)
    }

    fun showHideEpisodeButton(show: Boolean) {
        controller?.showHideEpisodeButton(show)
    }

    fun requestPlayPauseFocus() {
        controller?.requestPlayPauseFocus()
    }

    fun requestEpisodeButtonFocus() {
        controller?.requestEpisodeButtonFocus()
    }

    fun showHideActionButton(show: Boolean) {
        controller?.showHideActionButton(show)
    }

    fun requestMoreButtonFocus() {
        controller?.requestMoreButtonFocus()
    }

    fun showHideRelatedButton(show: Boolean) {
        controller?.showHideRelatedButton(show)
    }

    fun requestRelatedButtonFocus() {
        controller?.requestRelatedButtonFocus()
    }

    fun requestOwnerButtonFocus() {
        controller?.requestOwnerButtonFocus()
    }

    fun rememberCurrentFocusTarget() {
        controller?.rememberCurrentFocusTarget()
    }

    fun restoreRememberedFocus() {
        controller?.restoreRememberedFocus()
    }

    fun showHideRepeatButton(show: Boolean) {
        controller?.showHideRepeatButton(show)
    }

    fun showHideSubtitleButton(show: Boolean) {
        controller?.showHideSubtitleButton(show)
    }

    fun showHideLiveSettingButton(show: Boolean) {
        controller?.showHideLiveSettingButton(show)
    }

    fun showHideRefreshButton(show: Boolean) {
        controller?.showHideRefreshButton(show)
    }

    fun showHideTimeBar(show: Boolean) {
        controller?.showHideTimeBar(show)
    }

    fun showHideTimeText(show: Boolean) {
        controller?.showHideTimeText(show)
    }


    fun showSettingButton(show: Boolean) {
        controller?.showSettingButton(show)
    }

    fun setShowHideOwnerInfo(show: Boolean) {
        controller?.setShowHideOwnerButton(show)
    }

    fun play() {
        player?.play()
    }

    fun pause() {
        player?.pause()
    }

    private fun togglePlaybackByDoubleTap() {
        val currentPlayer = player ?: return
        when {
            currentPlayer.playWhenReady -> currentPlayer.pause()
            currentPlayer.playbackState == Player.STATE_IDLE -> {
                currentPlayer.prepare()
                currentPlayer.play()
            }
            currentPlayer.playbackState == Player.STATE_ENDED -> {
                currentPlayer.seekTo(currentPlayer.currentMediaItemIndex, C.TIME_UNSET)
                currentPlayer.play()
            }
            else -> currentPlayer.play()
        }
    }

    /**
     * 将 player 的 video surface 解绑，释放视频解码器。
     * 用于 onStop 中提前释放解码器资源，避免后台持有硬件解码器。
     */
    fun detachVideoSurface() {
        val currentPlayer = player ?: return
        when (val surfaceView = videoSurfaceView) {
            is SurfaceView -> currentPlayer.clearVideoSurfaceView(surfaceView)
            is TextureView -> currentPlayer.clearVideoTextureView(surfaceView)
            else -> currentPlayer.clearVideoSurface()
        }
    }

    /**
     * 将 player 的 video surface 重新绑定，恢复视频渲染。
     * 用于 onStart 中恢复前台播放。
     */
    fun reattachVideoSurface() {
        val currentPlayer = player ?: return
        when (val surfaceView = videoSurfaceView) {
            is SurfaceView -> currentPlayer.setVideoSurfaceView(surfaceView)
            is TextureView -> currentPlayer.setVideoTextureView(surfaceView)
        }
    }

    fun destroy() {
        controller?.clearVideoSettingChangeListener()
        val currentPlayer = player
        currentPlayer?.removeListener(componentListener)
        currentPlayer?.clearVideoFrameMetadataListener(videoFrameAnchorListener)
        when (val surfaceView = videoSurfaceView) {
            is SurfaceView -> currentPlayer?.clearVideoSurfaceView(surfaceView)
            is TextureView -> currentPlayer?.clearVideoTextureView(surfaceView)
        }
        controller?.removeVisibilityListener(controllerComponentListener)
        handler.removeCallbacksAndMessages(null)
        // 反注册 FrameMetrics 并停掉后台线程（onDetachedFromWindow 通常会先走，但 destroy 兜底）。
        uninstallFrameMetricsListener()
        danmakuController.release()
        specialDanmakuController.release()
        dmMaskController.dispose()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // FrameMetrics API 24+，旧设备静默回退到 [DmMaskController] 内部硬编码默认值。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            installFrameMetricsListener()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        uninstallFrameMetricsListener()
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun installFrameMetricsListener() {
        if (frameMetricsListener != null) return
        val activity = context as? Activity ?: return
        val window = activity.window ?: return
        val thread = HandlerThread("mask-frame-metrics", Thread.NORM_PRIORITY - 1).apply { start() }
        val handler = Handler(thread.looper)
        // 各阶段毫秒粒度延迟：合并起来 ≈ 应用层渲染管道总耗时。
        // 不取 TOTAL_DURATION，因为它包含了"等待下一个 vsync"的空闲时间，会高估实际工作量。
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            try {
                val draw = metrics.getMetric(FrameMetrics.DRAW_DURATION)
                val sync = metrics.getMetric(FrameMetrics.SYNC_DURATION)
                val issue = metrics.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION)
                val swap = metrics.getMetric(FrameMetrics.SWAP_BUFFERS_DURATION)
                val app = draw + sync + issue + swap
                if (app > 0L) {
                    dmMaskController.onMaskFrameMetrics(app)
                }
            } catch (_: Throwable) {
                // 某些 metric 在特定设备上不可用：忽略此帧，不影响主功能。
            }
        }
        window.addOnFrameMetricsAvailableListener(listener, handler)
        frameMetricsThread = thread
        frameMetricsHandler = handler
        frameMetricsListener = listener
    }

    private fun uninstallFrameMetricsListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val activity = context as? Activity
            val listener = frameMetricsListener
            if (activity != null && listener != null) {
                activity.window?.removeOnFrameMetricsAvailableListener(listener)
            }
        }
        frameMetricsThread?.quitSafely()
        frameMetricsThread = null
        frameMetricsHandler = null
        frameMetricsListener = null
    }

    fun cancelInDoubleTapMode() {
        gestureListener.cancelInDoubleTapMode()
    }

    fun keepInDoubleTapMode() {
        gestureListener.keepInDoubleTapMode()
    }

    fun setDoubleTapEnabled(enabled: Boolean) {
        isDoubleTapEnabled = enabled
    }

    fun isDoubleTapEnabled(): Boolean = isDoubleTapEnabled

    fun setDoubleTapDelay(delayMs: Long) {
        gestureListener.doubleTapDelay = delayMs
    }

    fun getDoubleTapDelay(): Long = gestureListener.doubleTapDelay

    fun setSeekPreviewSnapshot(snapshot: VideoSnapshotData?) {
        tapOverlayView?.setSeekPreviewSnapshot(snapshot)
    }

    fun setControllerAutoShow(autoShow: Boolean) {
        controllerAutoShow = autoShow
    }

    fun getControllerAutoShow(): Boolean = controllerAutoShow

    fun setTimeBarMinUpdateInterval(intervalMs: Int) {
        controller?.setTimeBarMinUpdateInterval(intervalMs)
    }

    fun setShowMultiWindowTimeBar(show: Boolean) {
        controller?.setShowMultiWindowTimeBar(show)
    }

    fun setDanmakuData(
        data: List<DmModel>,
        startupTraceId: String = PlaybackStartupTrace.NO_TRACE,
        startupTraceStartElapsedMs: Long = 0L
    ) {
        danmakuController.setData(data, startupTraceId, startupTraceStartElapsedMs)
    }

    fun setSpecialDanmakuData(data: List<SpecialDanmakuModel>) {
        specialDanmakuController.setData(data)
    }

    fun appendDanmakuData(data: List<DmModel>) {
        danmakuController.appendData(data)
    }

    fun startLiveDanmaku() {
        danmakuController.startLive()
    }

    fun addLiveDanmaku(dm: DmModel) {
        danmakuController.addLiveDanmaku(dm)
    }

    fun setDanmakuEnabled(enabled: Boolean) {
        if ((settingView?.getDmEnable() ?: enabled) != enabled) {
            settingView?.dmEnableClick()
        }
        danmakuController.setEnabled(enabled)
        specialDanmakuController.setEnabled(enabled)
    }

    fun pauseDanmaku() {
        danmakuController.pause()
        specialDanmakuController.pause()
    }

    fun resumeDanmaku() {
        danmakuController.resume()
        specialDanmakuController.resume()
    }

    fun stopDanmaku() {
        danmakuController.stop()
        specialDanmakuController.stop()
    }

    fun syncDanmakuPosition(positionMs: Long, forceSeek: Boolean = false) {
        danmakuController.syncPosition(positionMs, forceSeek)
        specialDanmakuController.syncPosition(positionMs, forceSeek)
        // mask buffer 的尺寸必须用 maskHost（最终绘制目标）而不是 dmkView，否则与
        // [updateMaskVideoBounds] 推过去的 videoBounds（也是 maskHost 坐标系）不在同一参考系，
        // path 缩放后会出现像素级偏移，看起来像"贴合不上"。
        val host = dmkMaskHost
        dmMaskController.onViewSizeChanged(host?.width ?: 0, host?.height ?: 0)
        // 关键：mask 的连续播放更新由 [DmMaskController.frameCallback] 独占（vsync 60Hz +
        // 视频帧 anchor 精确推算）。这里**只在 seek 时**额外推一次位置，让 mask 立即清空旧内容
        // 并按目标位置重渲染；非 seek 路径若并行 push 会与 frameCallback 用不同的 lookahead 公式
        // 互相打架，导致 mask 帧在两个相邻帧之间"瞬移跳变"，肉眼即"对不齐 / 抖动"。
        if (forceSeek) {
            dmMaskController.onSeek()
            dmMaskController.onPositionChanged(positionMs)
        }
    }

    fun setDmMaskRepository(repository: com.tutu.myblbl.model.dm.DmMaskRepository) {
        dmMaskController.setRepository(repository)
    }

    suspend fun loadDmMask(maskUrl: String, cid: Long, fps: Int): Boolean {
        val success = dmMaskController.loadMask(maskUrl, cid, fps)
        if (success) {
            dmMaskController.onViewSizeChanged(dmkView?.width ?: 0, dmkView?.height ?: 0)
            // 从设置读取开关状态
            val shieldEnabled = settingView?.getDmSmartShield() ?: true
            dmMaskController.setEnabled(shieldEnabled)
        }
        return success
    }

    fun releaseDmMask() {
        dmMaskController.release()
    }

    fun setDmSmartShieldEnabled(enabled: Boolean) {
        dmMaskController.setEnabled(enabled)
    }

    fun setUseController(use: Boolean) {
        if (useController == use) return
        useController = use
        if (use && controller != null) {
            controller?.setPlayer(player)
        } else {
            controller?.hide()
            controller?.setPlayer(null)
        }
        updateContentDescription()
    }

    private fun updateContentDescription() {
        contentDescription = when {
            !useController() -> null
            controller?.isFullyVisible() != true -> resources.getString(R.string.exo_controls_show)
            controllerHideOnTouch -> resources.getString(R.string.exo_controls_hide)
            else -> null
        }
    }

    private fun useController(): Boolean {
        return useController && controller != null
    }

    override fun setVisibility(visibility: Int) {
        super.setVisibility(visibility)
        (videoSurfaceView as? SurfaceView)?.visibility = visibility
    }

    private fun syncDanmakuSettings() {
        val snapshot = buildDanmakuSettingsSnapshot()
        danmakuController.applySettings(snapshot)
        specialDanmakuController.applySettings(snapshot)
    }

    // Keep the mapping from setting panel state to danmaku config in one place.
    private fun buildDanmakuSettingsSnapshot(): MyPlayerDanmakuController.SettingsSnapshot {
        return MyPlayerDanmakuController.SettingsSnapshot(
            enabled = settingView?.getDmEnable() ?: true,
            showAdvancedDanmaku = context.isAdvancedDanmakuEnabled(),
            alpha = settingView?.getDmAlpha() ?: 1f,
            textSize = settingView?.getDmTextScaleParam() ?: 40,
            speed = settingView?.getDmSpeedParam() ?: 4,
            screenArea = settingView?.getScreenPartParam() ?: 3,
            allowTop = settingView?.getDmAllowTop() ?: true,
            allowBottom = settingView?.getDmAllowBottom() ?: true,
            smartFilterLevel = context.getDanmakuSmartFilterLevel(),
            mergeDuplicate = settingView?.getDmMergeDuplicate() ?: true
        )
    }

    private fun handleSwipeSeekTouch(event: MotionEvent): Boolean {
        val currentPlayer = player ?: return false
        if (settingView?.isShowing() == true || currentPlayer.duration <= 0L || !currentPlayer.isCurrentMediaItemSeekable) {
            return false
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downTouchX = event.x
                downTouchY = event.y
                isSwipeSeeking = false
                swipeSeekUsesControllerPreview = false
                swipeSeekStartPositionMs = currentPlayer.currentPosition.coerceAtLeast(0L)
                swipeSeekTargetPositionMs = swipeSeekStartPositionMs
            }

            MotionEvent.ACTION_MOVE -> {
                val deltaX = event.x - downTouchX
                val deltaY = event.y - downTouchY
                if (!isSwipeSeeking) {
                    val horizontalDrag =
                        kotlin.math.abs(deltaX) > touchSlop &&
                            kotlin.math.abs(deltaX) > kotlin.math.abs(deltaY) * 1.2f
                    if (!horizontalDrag) {
                        return false
                    }
                    isSwipeSeeking = true
                    swipeSeekUsesControllerPreview = true
                    uiCoordinator?.transition(com.tutu.myblbl.feature.player.UiEvent.SeekTypeChanged(
                        com.tutu.myblbl.feature.player.SeekType.SWIPE
                    ))
                    controller?.enterSeekProgressOnly()
                    parent?.requestDisallowInterceptTouchEvent(true)
                    val deltaMs = (deltaX / width.coerceAtLeast(1)) * currentPlayer.duration
                    renderSwipeSeekPreview(
                        targetPositionMs = swipeSeekStartPositionMs + deltaMs.toLong()
                            .coerceIn(0L, currentPlayer.duration),
                        durationMs = currentPlayer.duration,
                        deltaMs = deltaMs.toLong()
                    )
                }
                val durationMs = currentPlayer.duration.coerceAtLeast(0L)
                val widthPx = width.coerceAtLeast(1)
                val offsetRatio = (deltaX / widthPx.toFloat()).coerceIn(-1f, 1f)
                val targetPositionMs =
                    (swipeSeekStartPositionMs + (durationMs * offsetRatio).toLong())
                        .coerceIn(0L, durationMs)
                swipeSeekTargetPositionMs = targetPositionMs
                val deltaMs = swipeSeekTargetPositionMs - swipeSeekStartPositionMs
                renderSwipeSeekPreview(
                    targetPositionMs = targetPositionMs,
                    durationMs = durationMs,
                    deltaMs = deltaMs
                )
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!isSwipeSeeking) {
                    return false
                }
                currentPlayer.seekTo(swipeSeekTargetPositionMs)
                onUserSeekListener?.invoke(swipeSeekTargetPositionMs)
                syncDanmakuPosition(swipeSeekTargetPositionMs, forceSeek = true)
                controller?.endSeekPreview(swipeSeekTargetPositionMs, 180L)
                uiCoordinator?.transition(com.tutu.myblbl.feature.player.UiEvent.SeekFinished)
                controller?.exitSeekProgressOnly()
                tapOverlayView?.finishSwipeSeek()
                isSwipeSeeking = false
                swipeSeekUsesControllerPreview = false
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                if (!isSwipeSeeking) {
                    return false
                }
                controller?.cancelSeekPreview()
                uiCoordinator?.clearSeekPreview()
                uiCoordinator?.transition(com.tutu.myblbl.feature.player.UiEvent.SeekCancelled)
                controller?.exitSeekProgressOnly()
                tapOverlayView?.cancelSwipeSeek()
                isSwipeSeeking = false
                swipeSeekUsesControllerPreview = false
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return false
    }

    /**
     * DO NOT CALL: dead code. Only use if explicitly requested.
     * Originally did a single immediate seek ±N seconds with showControllerSeek overlay.
     * Replaced by handleTapAccumulate which uses showSwipeSeek (centered arrow, no circle clip).
     */
    private fun doSingleKeySeek(forward: Boolean) {
        val currentPlayer = player ?: return
        if (currentPlayer.playbackState == Player.STATE_ENDED ||
            currentPlayer.playbackState == Player.STATE_IDLE
        ) return
        if (!currentPlayer.isCurrentMediaItemSeekable || currentPlayer.duration <= 0L) return

        val seekMs = (tapOverlayView?.seekSeconds ?: 10) * 1000L
        val deltaMs = seekMs * if (forward) 1 else -1
        val targetMs = (currentPlayer.currentPosition + deltaMs).coerceIn(0L, currentPlayer.duration)
        currentPlayer.seekTo(targetMs)
        syncDanmakuPosition(targetMs, forceSeek = true)

        tapOverlayView?.showControllerSeek(
            targetPositionMs = targetMs,
            durationMs = currentPlayer.duration,
            deltaMs = deltaMs,
            showBottomProgress = false
        )
    }

    private fun restoreControllerAfterGesture(showIndefinitely: Boolean = false) {
        if (showIndefinitely) {
            showController(true)
        } else {
            maybeShowController(true)
        }
        controller?.resetHideCallbacks()
    }

    private fun renderSwipeSeekPreview(targetPositionMs: Long, durationMs: Long, deltaMs: Long) {
        controller?.beginSeekPreview(targetPositionMs)
        tapOverlayView?.showSwipeSeek(
            targetPositionMs = targetPositionMs,
            durationMs = durationMs,
            deltaMs = deltaMs,
            showBottomProgress = false
        )
    }

}

