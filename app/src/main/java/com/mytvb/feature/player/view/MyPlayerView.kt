package com.mytvb.feature.player.view

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Choreographer
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.view.ViewStub
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.annotation.OptIn
import androidx.appcompat.widget.AppCompatTextView
import androidx.media3.common.C
import com.mytvb.core.ui.base.ScaledTextView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import com.mytvb.R
import com.mytvb.core.common.log.AppLog
import com.mytvb.core.ui.image.ImageLoader
import com.mytvb.model.dm.DmMaskRepository
import com.mytvb.model.dm.DmModel
import com.mytvb.model.player.VideoSnapshotData
import com.mytvb.model.subtitle.SubtitleInfoModel
import com.mytvb.model.video.quality.AudioQuality
import com.mytvb.model.video.quality.VideoCodecEnum
import com.mytvb.model.video.quality.VideoQuality
import com.mytvb.core.common.ext.getDanmakuSmartFilterLevel
import com.mytvb.feature.player.LiveLineInfo
import com.mytvb.feature.player.LiveQualityInfo
import com.mytvb.feature.player.PlaybackStartupTrace
import com.mytvb.feature.player.danmaku.BlblDanmakuController
import com.mytvb.feature.player.danmaku.DanmakuView
import com.mytvb.feature.player.danmaku.common.DanmakuSettingsSnapshot
import com.mytvb.feature.player.danmaku.common.DanmakuController
import com.mytvb.feature.player.danmaku.common.LiveDanmakuController
import com.mytvb.feature.player.DanmakuFilterContext
import com.mytvb.feature.player.sponsor.SponsorSegment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

interface DouyinModeKeyListener {
    /** 抖音模式是否激活（开关开启 + 当前视频适用） */
    fun isDouyinModeActive(): Boolean
    fun onDouyinNavigateNext(): Boolean
    fun onDouyinNavigatePrevious(): Boolean
    fun peekDouyinNext(): DouyinModePreview?
    fun peekDouyinPrevious(): DouyinModePreview?
}

data class DouyinModePreview(
    val title: String,
    val coverUrl: String
)

private enum class DouyinSwipeDirection(val value: Int) {
    Next(1),
    Previous(-1)
}

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

        private const val KEYCODE_SYSTEM_NAVIGATION_UP_COMPAT = 280
        private const val KEYCODE_SYSTEM_NAVIGATION_DOWN_COMPAT = 281
        private const val KEYCODE_SYSTEM_NAVIGATION_LEFT_COMPAT = 282
        private const val KEYCODE_SYSTEM_NAVIGATION_RIGHT_COMPAT = 283
        private const val STARTUP_BUFFERING_INDICATOR_DELAY_MS = 700L
        // 对齐 blbl DEFAULT_BUFFERING_OVERLAY_SHOW_DELAY_MS：播放中 BUFFERING 满 1s
        // 才弹遮罩，seek 后几百 ms 的正常缓冲不打扰画面（原 150ms 近乎立弹）
        private const val REBUFFER_BUFFERING_INDICATOR_DELAY_MS = 1_000L
        private const val DM_MASK_STARTUP_LOAD_DELAY_MS = 1500L
        private const val SHUTTER_FADE_DURATION_MS = 180L
        private const val SHUTTER_TIMEOUT_MS = 8_000L
        private const val MASK_GEOMETRY_LOG_INTERVAL_MS = 2_000L
        private const val RESUME_HINT_MARGIN_START_DP = 28
        private const val RESUME_HINT_MARGIN_BOTTOM_DP = 22
        private const val RESUME_HINT_CONTROLLER_OFFSET_DP = 130
        private const val RESUME_HINT_ANIMATION_MS = 120L

        /** 是否为 seek 键（左右方向键及部分电视 ROM 的系统导航兼容键码）。 */
        internal fun isSeekKeyCode(keyCode: Int): Boolean {
            return keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT ||
                keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT ||
                keyCode == KEYCODE_SYSTEM_NAVIGATION_LEFT_COMPAT ||
                keyCode == KEYCODE_SYSTEM_NAVIGATION_RIGHT_COMPAT
        }

        /** seek 键是否为「前进」方向。 */
        internal fun isForwardSeekKeyCode(keyCode: Int): Boolean {
            return keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT ||
                keyCode == KEYCODE_SYSTEM_NAVIGATION_RIGHT_COMPAT
        }
    }

    private var contentFrame: AspectRatioFrameLayout? = null
    private var shutterView: View? = null
    private var bufferingView: ImageView? = null
    private var errorMessageView: TextView? = null
    private var videoSurfaceView: View? = null

    fun getVideoSurfaceView(): View? = videoSurfaceView

    private var controller: MyPlayerControlView? = null
    private var settingView: MyPlayerSettingView? = null
    private var seekOverlayView: SeekOverlayView? = null
    private var dmkMaskHost: DanmakuMaskHostLayout? = null
    private var pauseIndicatorView: View? = null
    private var resumeHintView: LinearLayout? = null
    private var resumeHintPositionText: TextView? = null

    private var player: ExoPlayer? = null
    private var showBuffering: Int = SHOW_BUFFERING_WHEN_PLAYING
    private var controllerShowTimeoutMs: Int = MyPlayerControlView.DEFAULT_SHOW_TIMEOUT_MS
    private var controllerHideOnTouch: Boolean = true
    private var controllerAutoShow: Boolean = false
    private var suppressControllerShowUntilFirstFrame: Boolean = true
    private var useController: Boolean = true
    private var isDoubleTapEnabled: Boolean = true
    private var keepContentOnPlayerReset: Boolean = false
    private var customErrorMessage: CharSequence? = null

    private var controllerVisibilityListener: ControllerVisibilityListener? = null
    var douyinModeKeyListener: DouyinModeKeyListener? = null
    private var pendingTitle: String? = null
    private var pendingSubTitle: String? = null
    private var pendingLiveDuration: String? = null
    private var pendingPlayerSettingChangeListener: OnPlayerSettingChange? = null
    private var pendingVideoSettingChangeListener: OnVideoSettingChangeListener? = null
    private var pendingRepeatMode: Int = Player.REPEAT_MODE_OFF
    private var pendingAfterPlayMode: com.mytvb.feature.player.settings.AfterPlayMode =
        com.mytvb.feature.player.settings.AfterPlayMode.RECOMMEND
    private var pendingSeekSeconds: Int? = null
    private var pendingTimeBarMinUpdateIntervalMs: Int = MyPlayerControlView.DEFAULT_TIME_BAR_MIN_UPDATE_INTERVAL_MS
    private var pendingShowMultiWindowTimeBar: Boolean = false
    private var pendingSimpleKeyPressEnabled: Boolean? = null
    private var pendingEpisodeNavigationEnabled: Pair<Boolean, Boolean>? = null
    private var pendingDmSwitchVisible: Boolean? = null
    private var pendingMirrorVisible: Boolean? = null
    private var pendingPlaySpeedButtonVisible: Boolean? = null
    private var pendingNextPreviousVisible: Boolean? = null
    private var pendingFfReVisible: Boolean? = null
    private var pendingEpisodeButtonVisible: Boolean? = null
    private var pendingActionButtonVisible: Boolean? = null
    private var pendingRelatedButtonVisible: Boolean? = null
    private var pendingCommentButtonVisible: Boolean? = null
    private var pendingRepeatButtonVisible: Boolean? = null
    private var pendingSubtitleButtonVisible: Boolean? = null
    private var pendingLiveSettingButtonVisible: Boolean? = null
    private var pendingRefreshButtonVisible: Boolean? = null
    private var pendingLineButtonVisible: Boolean? = null
    private var pendingTimeBarVisible: Boolean? = null
    private var pendingTimeTextVisible: Boolean? = null
    private var pendingSettingButtonVisible: Boolean? = null
    private var pendingOwnerButtonVisible: Boolean? = null
    private var pendingSeekPreviewSnapshot: VideoSnapshotData? = null
    private var pendingSponsorSegments: List<SponsorSegment> = emptyList()
    private var pendingSponsorDurationMs: Long = 0L
    private var pendingDmMaskRequest: DmMaskRequest? = null

    private var touchInterceptListener: ((MotionEvent) -> Boolean)? = null
    private var douyinPreviewLayer: FrameLayout? = null
    private var douyinPreviewImage: ImageView? = null
    private var douyinPreviewTitle: AppCompatTextView? = null
    private var isDouyinDragging = false
    private var douyinDragDirection: DouyinSwipeDirection? = null
    private var douyinDragHasTarget = false
    private var douyinTransitionRunning = false
    private var douyinGestureCommittedTransition = false
    private var douyinTouchStartedInInteractiveArea = false
    private var douyinPendingTargetOffset = 0f
    private var douyinFirstFrameWaitRegistered = false
    private val douyinCommitThresholdRatio = 0.22f
    private val douyinAnimationDurationMs = 220L

    private val controllerComponentListener = object : MyPlayerControlView.VisibilityListener {
        override fun onVisibilityChange(visibility: Int) {
            if (visibility == View.VISIBLE) {
                uiCoordinator?.clearSeekPreview()
            }
            updateContentDescription()
            updateResumeHintPosition(animate = true)
            controllerVisibilityListener?.onVisibilityChanged(visibility)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val maskRetryScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
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

    // 性能优先弹幕引擎（唯一引擎）。由 setupDanmakuEngine() 创建并挂到蒙版宿主。
    private var liteDanmakuView: DanmakuView? = null
    private var liteDanmakuController: BlblDanmakuController? = null
    private val dmMaskController = DmMaskController(
        maskHostProvider = { dmkMaskHost },
        repository = DmMaskRepository()
    ).also {
        it.playerPositionProvider = { player?.currentPosition ?: 0L }
    }

    private fun activeDanmakuController(): DanmakuController? = liteDanmakuController

    private fun activeLiveDanmakuController(): LiveDanmakuController? = liteDanmakuController

    private var uiFrameMonitorStarted = false
    private var lastUiFrameTimeNs = 0L

    /**
     * 弹幕位置供数闸门：媒体源切换（onMediaItemTransition）到新源首帧渲染
     * （onRenderedFirstFrame）之间为 false——player 实例跨视频复用，此窗口内
     * player.currentPosition 仍是旧视频位置，直接供数会污染弹幕引擎时钟
     * （init 锚定到旧位置 + 单调高水位被抬回 + timer 前向硬锚吞掉脏值），
     * 表现为换视频后弹幕长时间不出现或卡住。冻结期间供 0（新视频起播位置）。
     */
    @Volatile
    private var danmakuPositionArmed = false

    /**
     * 直播弹幕独立时钟基准（elapsedRealtime，0=未激活）。直播弹幕时间线与
     * player.currentPosition 解耦（对齐 blbl LivePlayerActivity.liveDanmakuPositionMs）：
     * 直播流的 currentPosition 实际推进速率可比墙钟慢 5~10%（低延迟流追帧/解码抖动），
     * 绑 player 会让平滑时钟周期性累积负偏差、触发追赶回拉，表现为弹幕周期性颤动；
     * 且切后台返回后播放器追帧突进会造成弹幕位置跳变。独立墙钟下两者消失：
     * 短暂离开恢复即无缝续滚；离开超过追赶阈值时恢复首帧平滑时钟直接对齐墙钟，
     * 在屏旧弹幕一帧内超龄退场（等效"清屏重来"），新弹幕从返回点正常流入。
     */
    @Volatile
    private var liveDanmakuClockBaseMs: Long = 0L

    private fun liveDanmakuClockMs(): Long {
        val base = liveDanmakuClockBaseMs
        if (base <= 0L) return 0L
        return (SystemClock.elapsedRealtime() - base)
            .coerceIn(0L, Int.MAX_VALUE.toLong())
    }
    private val uiFrameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNs: Long) {
            val lastFrameTimeNs = lastUiFrameTimeNs
            if (lastFrameTimeNs > 0L && player?.isPlaying == true) {
                val intervalMs = (frameTimeNs - lastFrameTimeNs) / 1_000_000L
                if (intervalMs >= 40L) {
                    AppLog.w("PlaybackPerf", "ui_frame_jank interval=${intervalMs}ms")
                }
            }
            lastUiFrameTimeNs = frameTimeNs
            if (uiFrameMonitorStarted) {
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
    }
    private var downTouchX = 0f
    private var downTouchY = 0f
    private var isSwipeSeeking = false
    private var swipeSeekUsesControllerPreview = false
    private var swipeSeekStartPositionMs = 0L
    private var swipeSeekTargetPositionMs = 0L
    private var hasRenderedFirstFrame = false

    /**
     * 后台主动 detach 标志位。
     * detachVideoSurface() 置 true（onStop 走的路径），reattachVideoSurface() 清 false。
     * 区分两种 Surface 失效场景：
     * - 后台 detach：player 已主动 clearVideoSurface，恢复交给 onStart 里的 reattach+seekTo，无需自愈。
     * - 前台静默失效（系统弹窗返回 / 投影仪焦点切换）：没走到 onStop，Surface 被系统销毁重建，
     *   代码没人感知，解码器输出到了死掉的 Surface，导致画面冻结、音频弹幕继续。
     *   此时靠 Activity.onResume 调 recoverVideoRenderIfNeeded 自愈，强制重绑 Surface + seekTo 逼解码器重出帧。
     */
    private var surfaceDetachedForBackground = false

    private var persistentBottomProgressEnabled = false
    private var uiCoordinator: com.mytvb.feature.player.PlaybackUiCoordinator? = null

    var seekSession: com.mytvb.feature.player.SeekSession? = null
    private val heldSeekKeyCodes = mutableSetOf<Int>()
    private var pendingExitSeekProgressOnly: Runnable? = null
    private var pendingHoldStartRunnable: Runnable? = null
    private val holdStartDelayMs = 200L

    // --- SeekDiag: 诊断 seek 后画面卡死(纯观测,不改播放逻辑)---
    // 目标:坐实"async MediaCodec adapter flush 后丢失首帧回调"是不是根因。
    // 复现后看是否出现 "NO_FIRST_FRAME_AFTER_SEEK" → 视频管线断裂铁证。
    private var seekDiagStartedAtElapsedMs = 0L
    private var seekDiagWatchdogRunnable: Runnable? = null
    private var seekDiagHeartbeatRunnable: Runnable? = null
    private var seekDiagLastDroppedFrames = 0L
    private val seekDiagWatchdogTimeoutMs = 3000L
    private val seekDiagHeartbeatIntervalMs = 500L
    private val seekDiagHeartbeatDurationMs = 6000L

    // --- Tap accumulation (shared by both seek paths) ---
    private var tapAccumulateBaseMs = 0L
    private var tapAccumulateDeltaMs = 0L
    private var tapCommitRunnable: Runnable? = null
    private val tapCommitDelayMs = 450L

    // --- Hold scrub playback freeze（对齐 blbl：长按拖动期间画面定格）---
    // 进入长按 tick 时暂停播放器，松手提交 seek 后恢复原播放状态；
    // 幂等：已冻结时重复进入不重复 pause。
    private var holdPlaybackFrozen = false
    private var holdPlaybackWasPlaying = false

    // OK/Enter 在控制器隐藏时的 DOWN 由自定义路径切换了播放/暂停，
    // 必须吞掉对应的 ACTION_UP，否则 UP 落到刚获焦的 buttonPlay 上会被框架
    // 默认行为 performClick 再次切换，导致一次按下切换两次、互相抵消（小米电视复现）。
    private var consumedOkKeyUp = false

    // --- OK 长按临时倍速：按住超过阈值进入"当前倍速×2"，松手还原原速 ---
    // 短按（未到阈值松手）仍走原有播放/暂停逻辑。
    private val okLongPressSpeedDelayMs = 500L
    private val okLongPressSpeedMultiplier = 2f
    private var okLongPressSpeedRunnable: Runnable? = null
    // DOWN 已按下、尚在等待长按阈值（未触发）
    private var okLongPressPending = false
    // 长按已触发、正处于临时倍速中
    private var okLongPressTriggered = false
    private var okLongPressOriginalSpeed = 1f
    // 从"控制栏可见"状态进入的长按：DOWN 不吞（短按要放行给焦点按钮），只做计时
    private var okLongPressFromController = false
    // 临时倍速结束后吞掉 OK 抖动的窗口：部分遥控器松手会多送一次 DOWN/UP，
    // 不吞会被当成短按误触播放/暂停并召回控制栏。
    private val okReleaseDebounceMs = 500L
    private var okReleaseDebounceDeadlineMs = 0L

    // 右下角倍速角标：长按加速期间常驻显示；"常驻显示播放倍率"开关打开且
    // 非 1 倍速时也常驻显示。懒创建，与宿主同生命周期。
    private var speedBadgeView: TextView? = null
    private var persistentRateIndicatorEnabled = false

    // --- Timebar-focused seek state（blbl 模型：40ms 恒定 tick + 步长按片长自适应，长按走完全片时长恒定）---
    private var timebarSeekActive = false
    private var timebarSeekForward = true
    private var timebarSeekRunnable: Runnable? = null
    private var timebarSeekIdleRunnable: Runnable? = null
    private var timebarSeekStartMs = 0L
    private var pendingTimebarHoldStartRunnable: Runnable? = null
    private var timebarSeekTargetMs = 0L

    // --- Timeline thumb preview（跟随进度条滑块的缩略图浮窗，blbl videoShotPreview 形态）---
    private var timelineThumbPreview: TimelineThumbPreviewView? = null
    private val timelineTrackBounds = Rect()
    private val timelineHostLoc = IntArray(2)
    private val timebarSeekIdleTimeoutMs = 200L
    private val timebarSeekTickMs = 40L
    private val timebarSeekTraverseMs = 10_000L

    // mask provider 的复用容器：videoBoundsProvider 由 host.dispatchDraw 60Hz 调用，
    // 每帧 new Rect + 2 个 IntArray 会触发可观的 minor GC，复用单例消除分配。
    // 仅主线程访问（dispatchDraw 在主线程），无需同步。
    private val reusableMaskBoundsRect = Rect()
    private val reusableSurfaceLoc = IntArray(2)
    private val reusableHostLoc = IntArray(2)
    private var maskVideoWidth = 0
    private var maskVideoHeight = 0
    private var maskVideoPixelRatio = 1f
    private var maskVideoRotationDegrees = 0
    private var maskResizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
    private var lastMaskGeometryLogMs = 0L
    private var lastMaskGeometryLogKey = ""

    private val maskVideoBoundsProvider: () -> Rect = {
        computeMaskVideoBounds()
    }

    private val maskPtsProvider: () -> Long = {
        dmMaskController.currentVideoPtsMs()
    }

    private val maskShouldRenderProvider: () -> Boolean = {
        dmMaskController.shouldRenderMask()
    }

    private val maskIsSeekingProvider: () -> Boolean = {
        dmMaskController.isSeeking()
    }

    private val maskFrameQueryReporter: (Long, Long) -> Unit = { queryPts, framePts ->
        dmMaskController.reportFrameQuery(queryPts, framePts)
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
                activeDanmakuController()?.updatePlaybackSpeed(speed)
                dmMaskController.onPlayerClockChanged(speed, player.currentPosition.coerceAtLeast(0L))
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
            activeDanmakuController()?.notifyPlaybackStateChanged(playbackState, player?.playWhenReady == true)
            // mask 控制器需要知道 player 是否真的在解码、可以输出新帧。
            // STATE_READY 后立即同步当前播放器 clock，贴齐参考 onPlayerClockChanged。
            dmMaskController.setPlaybackReady(playbackState == Player.STATE_READY)
            if (playbackState == Player.STATE_READY) {
                player?.let {
                    dmMaskController.onPlayerClockChanged(
                        it.playbackParameters.speed,
                        it.currentPosition.coerceAtLeast(0L)
                    )
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // 长按临时倍速期间播放被中断（暂停/结束），立即还原原速，避免卡在加速态
            if (!isPlaying) {
                cancelOkLongPressSpeed()
            }
            activeDanmakuController()?.notifyIsPlayingChanged(isPlaying)
            // 进入/退出播放态时立即推一次播放器 clock，和官方 onPlayerClockChanged 时机对齐。
            player?.let {
                dmMaskController.onPlayerClockChanged(
                    it.playbackParameters.speed,
                    it.currentPosition.coerceAtLeast(0L)
                )
            }
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
                armSeekDiag(oldPosition.positionMs, newPosition.positionMs)
                // 位置不连续必须转发弹幕层（含播放器内部 SEEK_ADJUSTMENT 前跳，不只是用户 seek）：
                // 不通知时弹幕平滑时钟与媒体钟错开，恢复播放瞬间在屏弹幕 elapsed 虚增
                // → 提前退场（半路消失）或整屏位置跳变。用户主动 seek 路径会再次转发，
                // 引擎侧重建幂等（同位置重建跳过在屏条目），兼容引擎有 300ms 去重。
                syncDanmakuPosition(newPosition.positionMs, forceSeek = true)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            updateErrorMessage()
        }

        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
            updateMaskVideoSize(videoSize)
            updateAspectRatio()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // 媒体源切换：player.currentPosition 在新源首帧前仍可能返回旧视频位置
            // （player 实例跨视频复用），期间弹幕位置供数冻结为 0，见 danmakuPositionArmed。
            danmakuPositionArmed = false
        }

        override fun onRenderedFirstFrame() {
            onSeekDiagFirstFrame()
            dmMaskController.onPositionChanged()
            // 新源首帧已渲染，currentPosition 必然属于当前媒体，弹幕位置供数解冻。
            danmakuPositionArmed = true
            hasRenderedFirstFrame = true
            suppressControllerShowUntilFirstFrame = false
            activeDanmakuController()?.notifyPlaybackFirstFrame()
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
        val initStartMs = SystemClock.elapsedRealtime()
        val layoutStartMs = SystemClock.elapsedRealtime()
        LayoutInflater.from(context).inflate(R.layout.my_exo_styled_player_view, this)
        AppLog.i("PlayerViewPerf", "my_exo_styled_player_view inflate elapsed=${SystemClock.elapsedRealtime() - layoutStartMs}ms")
        descendantFocusability = FOCUS_AFTER_DESCENDANTS

        contentFrame = findViewById(R.id.exo_content_frame)
        shutterView = findViewById(R.id.exo_shutter)
        bufferingView = findViewById<ImageView>(R.id.exo_buffering).also {
            ImageLoader.loadDrawableRes(it, R.drawable.load_data)
        }
        errorMessageView = findViewById(R.id.exo_error_message)
        dmkMaskHost = findViewById(R.id.dmk_mask_host)
        pauseIndicatorView = findViewById(R.id.image_pause_indicator)

        val surfaceStartMs = SystemClock.elapsedRealtime()
        setupSurfaceView()
        AppLog.i("PlayerViewPerf", "setupSurfaceView elapsed=${SystemClock.elapsedRealtime() - surfaceStartMs}ms")

        bufferingView?.visibility = GONE
        errorMessageView?.visibility = GONE
        setupDouyinPreviewLayer()

        AppLog.i("PlayerViewPerf", "setupController deferred")
        val settingStartMs = SystemClock.elapsedRealtime()
        setupSettingView()
        AppLog.i("PlayerViewPerf", "setupSettingView elapsed=${SystemClock.elapsedRealtime() - settingStartMs}ms")
        restoreOverlayZOrder()
        isClickable = true
        isFocusable = true
        descendantFocusability = FOCUS_AFTER_DESCENDANTS

        isDoubleTapEnabled = true
        gestureListener.setCallback { _, _ ->
            togglePlaybackByDoubleTap()
        }
        AppLog.i("PlayerViewPerf", "MyPlayerView init elapsed=${SystemClock.elapsedRealtime() - initStartMs}ms")
    }

    private fun setupSurfaceView() {
        contentFrame?.let { frame ->
            val layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            val surfaceView = SurfaceView(context)
            surfaceView.layoutParams = layoutParams
            videoSurfaceView = surfaceView
            frame.addView(surfaceView, 0)
        }
    }


    private fun setupDouyinPreviewLayer() {
        if (douyinPreviewLayer != null) return
        val layer = FrameLayout(context).apply {
            visibility = GONE
            alpha = 0f
            setBackgroundColor(Color.BLACK)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        val image = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        val dim = View(context).apply {
            setBackgroundColor(0x66000000)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        val title = ScaledTextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(
                android.util.TypedValue.COMPLEX_UNIT_PX,
                resources.getDimension(R.dimen.px44)
            )
            maxLines = 2
            includeFontPadding = false
            setShadowLayer(6f, 0f, 2f, Color.BLACK)
            val horizontal = resources.getDimensionPixelSize(R.dimen.px50)
            val bottom = resources.getDimensionPixelSize(R.dimen.px120)
            setPadding(horizontal, 0, horizontal, 0)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM
                bottomMargin = bottom
            }
        }
        layer.addView(image)
        layer.addView(dim)
        layer.addView(title)
        addView(layer)
        douyinPreviewLayer = layer
        douyinPreviewImage = image
        douyinPreviewTitle = title
    }

    private fun ensureController(reason: String): MyPlayerControlView? {
        controller?.let { return it }
        val startMs = SystemClock.elapsedRealtime()
        val placeholder: View? = findViewById(R.id.exo_controller_placeholder)
        placeholder?.let { ph ->
            val newController = MyPlayerControlView(context)
            newController.id = R.id.exo_controller
            newController.layoutParams = ph.layoutParams
            newController.descendantFocusability = FOCUS_AFTER_DESCENDANTS

            val parent = ph.parent as ViewGroup
            val index = parent.indexOfChild(ph)
            parent.descendantFocusability = FOCUS_AFTER_DESCENDANTS
            parent.removeView(ph)
            parent.addView(newController, index)
            controller = newController

            newController.setOnMenuShowImpl(object : OnMenuShowImpl {
                override fun onShowHide(isShowing: Boolean) {
                    showHideSettingView(isShowing)
                }
            })

            newController.setOnDmEnableChangeImpl(object : OnDmEnableChangeImpl {
                override fun onDmEnable(enabled: Boolean) {
                    if (settingView?.getDmEnable() != enabled) {
                        settingView?.dmEnableClick()
                    }
                    activeDanmakuController()?.setEnabled(enabled)
                    dmMaskController.setDanmakuVisible(enabled)
                    updateVideoFrameRateStrategy(enabled)
                }
            })
            newController.setOnSeekCommitListener { positionMs ->
                onUserSeekListener?.invoke(positionMs)
                syncDanmakuPosition(positionMs, forceSeek = true)
            }
            newController.uiCoordinator = uiCoordinator
            newController.setPlayer(player)
            newController.setProgressOnlyUiEnabled(!persistentBottomProgressEnabled)
            newController.addVisibilityListener(controllerComponentListener)
            applyPendingControllerState(newController)
            restoreOverlayZOrder()
        }

        controllerShowTimeoutMs = if (controller != null) {
            MyPlayerControlView.DEFAULT_SHOW_TIMEOUT_MS
        } else {
            0
        }
        controller?.hideImmediately()
        AppLog.i("PlayerViewPerf", "setupController lazy reason=$reason elapsed=${SystemClock.elapsedRealtime() - startMs}ms")
        updateContentDescription()
        return controller
    }

    private fun applyPendingControllerState(target: MyPlayerControlView) {
        target.setRepeatMode(pendingRepeatMode)
        pendingTitle?.let(target::setTitle)
        pendingSubTitle?.let(target::setSubTitle)
        pendingLiveDuration?.let(target::setLiveDuration)
        target.setOnVideoSettingChangeListener(pendingVideoSettingChangeListener)
        pendingSeekSeconds?.let { target.setFfDuration(it.coerceAtLeast(1).toLong() * 1000L) }
        target.setTimeBarMinUpdateInterval(pendingTimeBarMinUpdateIntervalMs)
        target.setShowMultiWindowTimeBar(pendingShowMultiWindowTimeBar)
        pendingSimpleKeyPressEnabled?.let(target::setSimpleKeyPressEnabled)
        pendingEpisodeNavigationEnabled?.let { target.setEpisodeNavigationEnabled(it.first, it.second) }
        pendingDmSwitchVisible?.let(target::showHideDmSwitchButton)
        pendingMirrorVisible?.let(target::showHideMirrorButton)
        pendingPlaySpeedButtonVisible?.let(target::showHidePlaySpeedButton)
        pendingNextPreviousVisible?.let(target::showHideNextPrevious)
        pendingFfReVisible?.let(target::showHideFfRe)
        pendingEpisodeButtonVisible?.let(target::showHideEpisodeButton)
        pendingActionButtonVisible?.let(target::showHideActionButton)
        pendingRelatedButtonVisible?.let(target::showHideRelatedButton)
        pendingCommentButtonVisible?.let(target::showHideCommentButton)
        pendingRepeatButtonVisible?.let(target::showHideRepeatButton)
        pendingSubtitleButtonVisible?.let(target::showHideSubtitleButton)
        pendingLiveSettingButtonVisible?.let(target::showHideLiveSettingButton)
        pendingRefreshButtonVisible?.let(target::showHideRefreshButton)
        pendingLineButtonVisible?.let(target::showHideLineButton)
        pendingTimeBarVisible?.let(target::showHideTimeBar)
        pendingTimeTextVisible?.let(target::showHideTimeText)
        pendingSettingButtonVisible?.let(target::showSettingButton)
        pendingOwnerButtonVisible?.let(target::setShowHideOwnerButton)
        target.setSponsorSegments(pendingSponsorSegments)
        target.setSponsorDuration(pendingSponsorDurationMs)
    }

    private fun setupSettingView() {
        settingView = findViewById(R.id.setting_view)
        settingView?.setOnPlayerSettingChange(pendingPlayerSettingChangeListener)
        settingView?.setAfterPlayMode(pendingAfterPlayMode)
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
                activeDanmakuController()?.setEnabled(enabled)
                dmMaskController.setDanmakuVisible(enabled)
                updateVideoFrameRateStrategy(enabled)
            }

            override fun onPlaybackSpeedChange(speed: Float) {
                player?.playbackParameters = PlaybackParameters(speed)
                activeDanmakuController()?.updatePlaybackSpeed(speed)
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
                if (enabled) {
                    retryPendingDmMaskLoad()
                }
            }
        })
        syncDanmakuSettings()
        dmMaskController.setEnabled(settingView?.getDmSmartShield() ?: false)
    }

    private fun ensureSeekOverlay(reason: String): SeekOverlayView? {
        seekOverlayView?.let { return it }
        val startMs = SystemClock.elapsedRealtime()
        val overlay = findViewById<SeekOverlayView>(R.id.view_seek_overlay)
            ?: findViewById<ViewStub>(R.id.view_seek_overlay_stub)?.inflate() as? SeekOverlayView
            ?: return null
        seekOverlayView = overlay
        overlay.setPlayerView(this)
        overlay.setPlayer(player)
        overlay.setUiCoordinator(uiCoordinator)
        overlay.setPersistentBottomProgressEnabled(persistentBottomProgressEnabled)
        pendingSeekSeconds?.let { overlay.seekSeconds = it }
        overlay.setSeekPreviewSnapshot(pendingSeekPreviewSnapshot)
        restoreOverlayZOrder()
        overlay.setCallback(object : SeekOverlayView.Callback {
            override fun onAnimationStart(displayMode: SeekOverlayView.DisplayMode) = Unit

            override fun onAnimationEnd(displayMode: SeekOverlayView.DisplayMode) = Unit

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
        AppLog.i("PlayerViewPerf", "setupSeekOverlay lazy reason=$reason elapsed=${SystemClock.elapsedRealtime() - startMs}ms")
        return overlay
    }

    fun setUiCoordinator(coordinator: com.mytvb.feature.player.PlaybackUiCoordinator?) {
        uiCoordinator = coordinator
        controller?.uiCoordinator = coordinator
        seekOverlayView?.setUiCoordinator(coordinator)
    }

    fun setPlayer(player: ExoPlayer?) {
        val previousPlayer = this.player
        previousPlayer?.removeListener(componentListener)
        when (val surfaceView = videoSurfaceView) {
            is SurfaceView -> previousPlayer?.clearVideoSurfaceView(surfaceView)
            is TextureView -> previousPlayer?.clearVideoTextureView(surfaceView)
        }
        if (!keepContentOnPlayerReset) {
            closeShutter()
        }
        this.player = player
        updateVideoFrameRateStrategy(buildDanmakuSettingsSnapshot().enabled)
        player?.addListener(componentListener)
        // player 切换会带来新的播放参数，立即同步当前速度，避免 mask 在拿到首个
        // PARAMETERS_CHANGED 事件前用旧速度推算。
        player?.let {
            dmMaskController.onPlayerClockChanged(
                it.playbackParameters.speed,
                it.currentPosition.coerceAtLeast(0L)
            )
            updateMaskVideoSize(it.videoSize)
        }
        when (val surfaceView = videoSurfaceView) {
            is SurfaceView -> player?.setVideoSurfaceView(surfaceView)
            is TextureView -> player?.setVideoTextureView(surfaceView)
        }

        controller?.setPlayer(player)
        controller?.setRepeatMode(player?.repeatMode ?: Player.REPEAT_MODE_OFF)
        seekOverlayView?.setPlayer(player)

        updateBuffering()
        updateErrorMessage()
    }

    /**
     * 监听下一次视频首帧渲染，触发后自动移除监听。
     * 用于抖音模式切换动画：等新视频首帧就绪后再滑出遮罩。
     */
    fun observeNextFirstFrame(onFirstFrame: () -> Unit) {
        val p = player ?: run { onFirstFrame(); return }
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                p.removeListener(this)
                onFirstFrame()
            }
        }
        p.addListener(listener)
    }

    private fun closeShutter() {
        hasRenderedFirstFrame = false
        suppressControllerShowUntilFirstFrame = true
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

    fun prepareForPlaybackTransition(startPositionMs: Long = 0L) {
        closeShutter()
        handler.removeCallbacks(bufferingIndicatorRunnable)
        bufferingView?.visibility = GONE
        activeDanmakuController()?.resetForPlaybackStart(startPositionMs)
        releaseDmMask()
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

    private fun updateMaskVideoSize(videoSize: androidx.media3.common.VideoSize) {
        maskVideoWidth = videoSize.width
        maskVideoHeight = videoSize.height
        maskVideoPixelRatio = videoSize.pixelWidthHeightRatio
            .takeIf { it.isFinite() && it > 0f }
            ?: 1f
        @Suppress("DEPRECATION")
        maskVideoRotationDegrees = videoSize.unappliedRotationDegrees
    }

    /**
     * 把 video 的真实显示 Surface 矩形换算到 maskHost 坐标系。
     *
     * 官方链路里这一步对应 VideoSizeChange(origin/size/scale/translation/rotation)：
     * Media3 的 AspectRatioFrameLayout 已经把屏占比/zoom 应用到 Surface 测量尺寸，
     * 这里不能重复计算 resizeMode，否则会把同一个缩放应用两次。
     *
     * 触发时机：每次 DanmakuMaskHostLayout.dispatchDraw 时实时读取。
     */
    private fun computeMaskVideoBounds(): Rect {
        val rect = reusableMaskBoundsRect
        val surface = videoSurfaceView
        val maskHost = dmkMaskHost
        if (surface == null || maskHost == null) {
            rect.setEmpty()
            return rect
        }
        val w = surface.width
        val h = surface.height
        if (w <= 0 || h <= 0) {
            rect.setEmpty()
            return rect
        }
        surface.getLocationInWindow(reusableSurfaceLoc)
        maskHost.getLocationInWindow(reusableHostLoc)
        val surfaceLeft = reusableSurfaceLoc[0] - reusableHostLoc[0]
        val surfaceTop = reusableSurfaceLoc[1] - reusableHostLoc[1]
        rect.set(surfaceLeft, surfaceTop, surfaceLeft + w, surfaceTop + h)
        maybeLogMaskGeometry(w, h, rect)
        return rect
    }

    private fun maybeLogMaskGeometry(
        surfaceW: Int,
        surfaceH: Int,
        hostRect: Rect
    ) {
        // 每帧都会被 computeMaskVideoBounds 调到：日志关闭时连 key 字符串都不构建。
        if (!AppLog.isEnabled) return
        val now = SystemClock.elapsedRealtime()
        val key = "${maskVideoWidth}x$maskVideoHeight@$maskVideoPixelRatio/" +
            "$maskVideoRotationDegrees/$maskResizeMode/$surfaceW:$surfaceH/" +
            "${hostRect.left},${hostRect.top},${hostRect.right},${hostRect.bottom}"
        if (key == lastMaskGeometryLogKey && now - lastMaskGeometryLogMs < MASK_GEOMETRY_LOG_INTERVAL_MS) {
            return
        }
        lastMaskGeometryLogKey = key
        lastMaskGeometryLogMs = now
        AppLog.d(
            "DmMaskGeometry",
            "video=${maskVideoWidth}x$maskVideoHeight pixel=$maskVideoPixelRatio " +
                "rotation=$maskVideoRotationDegrees resizeMode=$maskResizeMode " +
                "surface=${surfaceW}x$surfaceH host=$hostRect"
        )
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
        if (seekOverlayView?.isOverlayShowing() == true) return

        val shouldShowIndefinitely = shouldShowControllerIndefinitely()
        val currentController = controller

        if (currentController?.isFullyVisible() == true) {
            if (shouldShowIndefinitely) {
                currentController.setShowTimeoutMs(0)
            } else {
                currentController.setShowTimeoutMs(controllerShowTimeoutMs)
                currentController.resetHideCallbacks()
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
        if (suppressControllerShowUntilFirstFrame && !hasRenderedFirstFrame) {
            AppLog.i("PlayerViewPerf", "controller show suppressed before first frame")
            return
        }

        val controller = controller ?: ensureController("show") ?: return
        controller.setShowTimeoutMs(if (indefinitely) 0 else controllerShowTimeoutMs)
        controller.show(focusPlayPause = true)
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

    /**
     * 播放器按键分发序列。各分支的判定顺序与拆分前完全一致（按键分发顺序敏感，
     * 例如 BACK 必须先查设置面板、再查 seek 会话、才是退出逻辑）——调整顺序前
     * 务必理解现有依赖。各步骤职责见对应 handleXxx 方法。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        logDpadCenterDiagnostics(event)
        // 吞掉"控制器隐藏时 DOWN 已切换播放/暂停"对应的 ACTION_UP（厂商 ROM 兼容）
        if (consumeVendorOkKeyUp(event)) {
            return true
        }
        if (!ensurePlayerAndControllerForKey(event)) {
            return super.dispatchKeyEvent(event)
        }
        // If focus is outside this MyPlayerView (e.g. on the related-videos panel),
        // let the event propagate normally so D-pad navigation works within the panel.
        if (shouldDeferKeyToOutsideFocus(event)) {
            return super.dispatchKeyEvent(event)
        }
        handleSettingPanelKeys(event)?.let { return it }
        handleActiveSeekSessionKeys(event)?.let { return it }
        handleBackCancelSeekKeys(event)?.let { return it }
        handleTimebarSeekKeys(event)?.let { return it }
        handleTapCommitSeekKeys(event)?.let { return it }
        handleDoubleTapModeKeys(event)?.let { return it }
        handleOkLongPressSpeedKeys(event)?.let { return it }
        handleControllerDpadKeys(event)?.let { return it }
        return dispatchMediaAndSuperFallbackKeys(event)
    }

    /** [DEBUG] 诊断小米电视确定键播放/暂停失效问题，定位后删除。 */
    private fun logDpadCenterDiagnostics(event: KeyEvent) {
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER) {
            AppLog.d("DpadCenter", "dispatchKeyEvent code=${event.keyCode} action=${event.action} repeat=${event.repeatCount} ctrlVisible=${controller?.isFullyVisible()} player=${player != null}")
        }
    }

    /** player/controller 就绪检查：未就绪时返回 false，事件交给 super。 */
    private fun ensurePlayerAndControllerForKey(event: KeyEvent): Boolean {
        if (player == null) return false
        if (controller == null && event.action == KeyEvent.ACTION_DOWN && event.keyCode != KeyEvent.KEYCODE_BACK) {
            ensureController("key")
        }
        return controller != null
    }

    /** 焦点在本 View 之外（如相关视频面板）时放行，事件正常传播供面板内 D-pad 导航。 */
    private fun shouldDeferKeyToOutsideFocus(event: KeyEvent): Boolean {
        if (isDpadKey(event.keyCode)) {
            val focused = findFocus()
            if (focused != null && !isViewDescendant(focused)) {
                return true
            }
        }
        return false
    }

    /**
     * 设置面板相关按键：MENU 呼出、BACK 逐级返回/取消恢复进度弹层、面板显示期间
     * 的事件整体转发。返回 null 表示与本组无关，继续后续分发。
     */
    private fun handleSettingPanelKeys(event: KeyEvent): Boolean? {
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
        return null
    }

    /**
     * 活跃 seek 会话期间的按键：seek 键继续喂给会话；其它键（如 BACK）取消会话并
     * 清理 UI 后返回 null 继续后续分发（取消本身不消费按键）。
     */
    private fun handleActiveSeekSessionKeys(event: KeyEvent): Boolean? {
        if (seekSession?.isActive() == true) {
            if (isSeekKeyCode(event.keyCode)) {
                return handleSeekSessionKeyEvent(event)
            }
            seekSession?.cancel()
            cancelPendingHoldStart()
            cancelPendingExitSeekProgressOnly()
            endHoldPlaybackFreeze()
            controller?.cancelSeekPreview()
            seekOverlayView?.cancelSwipeSeek()
            controller?.exitSeekProgressOnly()
        }
        return null
    }

    /** BACK 取消进行中的 timebar seek / 轻点累积提交；确有取消动作时消费按键。 */
    private fun handleBackCancelSeekKeys(event: KeyEvent): Boolean? {
        if (event.keyCode != KeyEvent.KEYCODE_BACK || event.action != KeyEvent.ACTION_DOWN) {
            return null
        }
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
            endHoldPlaybackFreeze()
            controller?.cancelSeekPreview()
            seekOverlayView?.cancelSwipeSeek()
            hideTimelineThumbPreviewNow()
            controller?.exitSeekProgressOnly()
            // Restore appropriate UI state
            if (wasTimebarSeek) {
                controller?.show()
                controller?.startProgressUpdates()
            }
            uiCoordinator?.transition(com.mytvb.feature.player.UiEvent.SeekCancelled)
            return true
        }
        return null
    }

    /** timebar 持焦 seek 优先：LEFT/RIGHT 始终路由给进度条。 */
    private fun handleTimebarSeekKeys(event: KeyEvent): Boolean? {
        if (timebarSeekActive && isSeekKeyCode(event.keyCode)) {
            return handleTimebarSeekKeyEvent(event, isForwardSeekKeyCode(event.keyCode))
        }
        return null
    }

    /** 轻点累积提交挂起中：seek 键路由给 seek 会话，避免落入 maybeShowController。 */
    private fun handleTapCommitSeekKeys(event: KeyEvent): Boolean? {
        if (tapCommitRunnable != null && isSeekKeyCode(event.keyCode)) {
            return handleSeekSessionKeyEvent(event)
        }
        return null
    }

    /** 双击手势模式（长按 seek）期间接管全部按键。 */
    private fun handleDoubleTapModeKeys(event: KeyEvent): Boolean? {
        if (!gestureListener.isDoubleTapping) {
            return null
        }
        if (event.action == KeyEvent.ACTION_DOWN && isSeekKeyCode(event.keyCode)) {
            gestureListener.cancelInDoubleTapMode()
            handleSeekSessionKeyEvent(event)
        } else {
            gestureListener.handleKeyDown(event)
        }
        return true
    }

    /**
     * OK/Enter 长按临时倍速：正在播放（且无 seek/双击会话）时按住超过
     * [okLongPressSpeedDelayMs] 进入"当前倍速×[okLongPressSpeedMultiplier]"，松手还原。
     * - 控制栏显示中：长按触发前先收起控制栏；DOWN 不吞（短按仍能点击焦点按钮）；
     * - 控制栏隐藏中：DOWN 吞掉自己计时，短按（未到阈值松手）复现原有
     *   maybeShowController + togglePlayPause 行为；
     * - 临时倍速结束后 [okReleaseDebounceMs] 内吞掉 OK 抖动，避免误触播放/暂停。
     * 返回 true 表示消费；null 表示不处理、继续后续分发。
     */
    private fun handleOkLongPressSpeedKeys(event: KeyEvent): Boolean? {
        if (event.keyCode != KeyEvent.KEYCODE_DPAD_CENTER &&
            event.keyCode != KeyEvent.KEYCODE_ENTER
        ) {
            return null
        }
        if (!useController()) return null

        // 抖动屏蔽窗口：吞掉多余的 DOWN/UP，不触发任何播放/UI 动作
        if (SystemClock.elapsedRealtime() < okReleaseDebounceDeadlineMs) {
            cancelOkLongPressSpeed()
            return true
        }

        if (event.action == KeyEvent.ACTION_DOWN) {
            // 计时中/已触发的 repeat 事件吞掉，避免落到焦点按钮触发长按
            if (okLongPressPending || okLongPressTriggered) return true
            // 只从首次 DOWN 起计时（repeat 的重复 DOWN 不再起表，否则会把
            // "暂停时按下、缓冲恢复转播放"误判成持续长按）
            if (event.repeatCount > 0) return null
            // 仅正在播放时提供长按加速；暂停/缓冲态按原逻辑走
            if (player?.isPlaying != true) return null
            // 双击手势 / timebar seek / 滑动 seek 会话优先，放行给对应处理器
            if (gestureListener.isDoubleTapping || timebarSeekActive || seekSession?.isActive() == true) {
                return null
            }

            val controllerVisible = controller?.isFullyVisible() == true
            okLongPressFromController = controllerVisible
            okLongPressOriginalSpeed = player?.playbackParameters?.speed ?: 1f
            okLongPressPending = true
            val triggerRunnable = Runnable {
                if (!okLongPressPending) return@Runnable
                okLongPressPending = false
                okLongPressTriggered = true
                // 控制栏显示中：先收起再进入临时倍速，避免加速角标被控制栏遮挡
                if (controllerVisible) {
                    hideController()
                }
                setPlaySpeed(okLongPressOriginalSpeed * okLongPressSpeedMultiplier)
            }
            okLongPressSpeedRunnable = triggerRunnable
            postDelayed(triggerRunnable, okLongPressSpeedDelayMs)
            // 控制栏可见：DOWN 放行给焦点按钮（短按要能正常点击），仅后台计时
            return if (controllerVisible) null else true
        }

        if (event.action == KeyEvent.ACTION_UP) {
            if (!okLongPressPending && !okLongPressTriggered) return null
            okLongPressSpeedRunnable?.let { removeCallbacks(it) }
            okLongPressSpeedRunnable = null
            val fromController = okLongPressFromController
            okLongPressPending = false
            if (okLongPressTriggered) {
                okLongPressTriggered = false
                setPlaySpeed(okLongPressOriginalSpeed)
                // 开抖动屏蔽窗口：吞掉遥控器松手多送的 DOWN/UP，防止还原瞬间误暂停
                okReleaseDebounceDeadlineMs = SystemClock.elapsedRealtime() + okReleaseDebounceMs
                return true
            }
            // 短按：控制栏可见时放行给原路径（焦点按钮点击）；隐藏态复现原有
            // OK 直切播放/暂停行为（见 handleControllerHiddenDpadKeyDown，此处
            // DOWN 已被本处理器吞掉，原路径收不到，必须自行复现）
            if (fromController) {
                return null
            }
            maybeShowController(true)
            controller?.togglePlayPauseFromKey()
            return true
        }
        return null
    }

    /** 倍速角标显隐统一入口：临时加速期间 / 常驻倍率开关 + 非 1 倍速时显示。 */
    private fun refreshSpeedBadge() {
        val speed = player?.playbackParameters?.speed ?: 1f
        when {
            okLongPressTriggered -> showSpeedBadge(acceleratedSpeedBadgeText())
            persistentRateIndicatorEnabled && speed != 1f -> showSpeedBadge(formatSpeedBadgeText(speed))
            else -> hideSpeedBadge()
        }
    }

    /** 加速角标文案：原速 1x 显示"2x"；原速非 1x 显示"2x1.5x"（在原速上再×2）。 */
    private fun acceleratedSpeedBadgeText(): String {
        if (okLongPressOriginalSpeed == 1f) return "2x"
        return "2x${formatSpeedBadgeText(okLongPressOriginalSpeed)}"
    }

    /** 档位文案：整数倍速显示"2x"，小数档显示"1.25x"。 */
    private fun formatSpeedBadgeText(speed: Float): String {
        val rounded = kotlin.math.round(speed * 100f) / 100f
        return if (rounded % 1f == 0f) "${rounded.toInt()}x" else "${rounded}x"
    }

    private fun showSpeedBadge(text: String) {
        val badge = speedBadgeView ?: createSpeedBadge().also { speedBadgeView = it }
        badge.text = text
        badge.visibility = VISIBLE
        badge.bringToFront()
    }

    private fun hideSpeedBadge() {
        speedBadgeView?.visibility = GONE
    }

    /** "常驻显示播放倍率"设置入口。 */
    fun showPlaybackRateIndicator(show: Boolean) {
        persistentRateIndicatorEnabled = show
        refreshSpeedBadge()
    }

    private fun createSpeedBadge(): TextView {
        val badge = AppCompatTextView(context).apply {
            text = "2x"
            setTextColor(Color.WHITE)
            setTextSize(
                android.util.TypedValue.COMPLEX_UNIT_PX,
                resources.getDimension(R.dimen.px34)
            )
            gravity = Gravity.CENTER
            val padHorizontal = dp(10)
            val padVertical = dp(4)
            setPadding(padHorizontal, padVertical, padHorizontal, padVertical)
            background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(0xB3000000.toInt())
            }
            visibility = GONE
        }
        addView(
            badge,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                bottomMargin = dp(16)
                marginEnd = dp(16)
            }
        )
        return badge
    }

    /** 取消 OK 长按临时倍速并还原原速（视图离屏、播放中断等异常路径兜底）。 */
    private fun cancelOkLongPressSpeed() {
        okLongPressSpeedRunnable?.let { removeCallbacks(it) }
        okLongPressSpeedRunnable = null
        okLongPressPending = false
        if (okLongPressTriggered) {
            okLongPressTriggered = false
            setPlaySpeed(okLongPressOriginalSpeed)
        } else {
            refreshSpeedBadge()
        }
    }

    /**
     * 控制栏可见性相关的 D-pad 分发。可见态：seek 键路由（timebar 持焦优先）、
     * DOWN 打开相关视频面板；隐藏态：抖音式导航、OK 直切播放暂停（厂商补丁）、
     * 方向键聚焦按钮，隐藏态一律消费按键。返回 null 继续媒体键/super 兜底。
     */
    private fun handleControllerDpadKeys(event: KeyEvent): Boolean? {
        if (!isDpadKey(event.keyCode) || !useController) {
            return null
        }
        val isSeekKey = isSeekKeyCode(event.keyCode)
        val controllerVisible = controller?.isFullyVisible() == true
        if (isSeekKey && controller?.isTimebarFocused() == true) {
            return handleTimebarSeekKeyEvent(event, isForwardSeekKeyCode(event.keyCode))
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
        // 控制栏可见且焦点在进度条（最上一行）时，UP 直接收起控制栏，与返回键等效
        if (controllerVisible
            && event.keyCode == KeyEvent.KEYCODE_DPAD_UP
            && event.action == KeyEvent.ACTION_DOWN
            && controller?.isTimebarFocused() == true
        ) {
            hideController()
            return true
        }
        // 对齐 blbl：控制栏可见时 UP 显式聚焦进度条（blbl UP = focusSeekBar）。
        // 不依赖系统焦点导航——控制栏淡入/布局变化时 FocusFinder 会把 UP 导到
        // 别的按钮上，进度条聚焦不稳定，长按/点按的 timebar 路径随之失效。
        if (controllerVisible
            && event.keyCode == KeyEvent.KEYCODE_DPAD_UP
            && event.action == KeyEvent.ACTION_DOWN
            && controller?.isTimebarFocused() != true
        ) {
            val focused = findFocus()
            if (focused == null || isViewDescendant(focused)) {
                controller?.requestTimeBarFocus()
                return true
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
                handleControllerHiddenDpadKeyDown(event)
            }
            return true
        }
        return null
    }

    /**
     * 控制栏隐藏态的 DOWN 键处理：抖音式导航 → 手势接管 → 显示控制栏并路由焦点。
     * OK/Enter 直接切换播放/暂停：不走 focusButtonByKeyDown 的 performClick 路径——
     * 部分 Android 9 ROM（小米电视）在控制器刚淡入、Button 未完成布局/获焦时会
     * 丢弃 performClick，导致首次按确定键只弹出播控栏却无法暂停/播放。
     */
    private fun handleControllerHiddenDpadKeyDown(event: KeyEvent) {
        if (handleDouyinNavigationKey(event)) {
            return
        }
        // [DEBUG] 诊断小米电视确定键播放/暂停失效问题，定位后删除
        val dtDouble = gestureListener.isDoubleTapping
        AppLog.d("DpadCenter", "!ctrlVisible branch code=${event.keyCode} dtDouble=$dtDouble")
        if (!gestureListener.handleKeyDown(event) && !gestureListener.isDoubleTapping) {
            maybeShowController(true)
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                event.keyCode == KeyEvent.KEYCODE_ENTER
            ) {
                AppLog.d("DpadCenter", "calling togglePlayPauseFromKey code=${event.keyCode}")
                controller?.togglePlayPauseFromKey()
                // DOWN 已切换，标记吞掉对应 UP，防止 UP 触发 buttonPlay.performClick 二次切换
                consumedOkKeyUp = true
            } else {
                AppLog.d("DpadCenter", "calling focusButtonByKeyDown code=${event.keyCode}")
                controller?.focusButtonByKeyDown(event)
            }
        }
    }

    /** 媒体键与框架兜底：媒体键拦截 → super 分发 → D-pad 兜底路由。 */
    private fun dispatchMediaAndSuperFallbackKeys(event: KeyEvent): Boolean {
        if (controller?.dispatchMediaKeyEvent(event) == true) {
            maybeShowController(true)
            return true
        }

        val superResult = super.dispatchKeyEvent(event)
        if (superResult) {
            maybeShowController(true)
            return true
        }

        if (isDpadKey(event.keyCode) && useController && event.action == KeyEvent.ACTION_DOWN) {
            maybeShowController(true)
            val handled = controller?.handleDpadWhenSuperNotHandled(event) ?: false
            return handled
        }

        return false
    }

    /**
     * 厂商 ROM 兼容（小米电视 Android 9）：控制器隐藏时按 OK/Enter，DOWN 已直接
     * togglePlayPauseFromKey（见 dispatchKeyEvent 主流程），此处吞掉对应 ACTION_UP——
     * 否则 UP 落到刚获焦的 buttonPlay，框架默认 performClick 二次切换、与 DOWN 抵消，
     * 表现为按确定键无反应（小米电视首次按下必现）。其它键的 DOWN 清除标记，
     * 避免误吞后续不相关的 UP。
     */
    private fun consumeVendorOkKeyUp(event: KeyEvent): Boolean {
        if (consumedOkKeyUp &&
            event.action == KeyEvent.ACTION_UP &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER)
        ) {
            AppLog.d("DpadCenter", "consume ACTION_UP code=${event.keyCode} (DOWN already toggled)")
            consumedOkKeyUp = false
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN &&
            event.keyCode != KeyEvent.KEYCODE_DPAD_CENTER &&
            event.keyCode != KeyEvent.KEYCODE_ENTER
        ) {
            consumedOkKeyUp = false
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
                uiCoordinator?.transition(com.mytvb.feature.player.UiEvent.SeekTypeChanged(
                    com.mytvb.feature.player.SeekType.HOLD))
                controller?.enterSeekProgressOnly()
                controller?.requestTimeBarFocus()
                session.startHoldSeek(forward)
                // Delay tick loop start so short press can be detected
                cancelPendingHoldStart()
                val startRunnable = Runnable {
                    if (seekSession?.isActive() == true) {
                        beginHoldPlaybackFreeze()
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
                        beginHoldPlaybackFreeze()
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
                    // Long press: clear tap accumulation, finish hold seek.
                    // 立即停 tick 并提交——原实现 postDelayed(150ms) 后才 finishSeek，
                    // 150ms 窗口内 tick 仍在推进，松手后凭空多跳（用户反馈"快进过头"根因之一）。
                    resetTapAccumulate()
                    finishHoldSeekNow()
                }
            }
            return true
        }
        return session.isActive()
    }

    /** 长按会话收尾：停 tick → 提交 seek → 解冻恢复播放 → 清 UI。 */
    private fun finishHoldSeekNow() {
        val session = seekSession ?: return
        if (!session.isActive()) return
        session.finishSeek()
        val resumedPlayback = endHoldPlaybackFreeze()
        // 长按松手同 blbl：进度条停留淡出，浮窗 500ms 先走
        controller?.finishSeekPreviewProgressOnly()
        timelineThumbPreview?.hideAfter(500L)
        seekOverlayView?.finishSwipeSeek()
        syncDanmakuPosition(
            player?.currentPosition?.coerceAtLeast(0L) ?: 0L,
            forceSeek = true
        )
        // play() 后 isPlaying 异步翻转，恢复弹幕以"本次解冻确实恢复了播放"为准；
        // 长按前就在暂停的用户，松手后保持暂停
        if (resumedPlayback || player?.isPlaying == true) {
            resumeDanmaku()
        }
    }

    /** 长按拖动期间暂停播放器（画面定格），幂等。 */
    private fun beginHoldPlaybackFreeze() {
        if (holdPlaybackFrozen) return
        val currentPlayer = player ?: return
        holdPlaybackWasPlaying = currentPlayer.playWhenReady
        holdPlaybackFrozen = true
        if (holdPlaybackWasPlaying) {
            currentPlayer.pause()
        }
    }

    /**
     * 长按结束后恢复原播放状态；未冻结时为空操作。
     * 返回值：本次调用是否把播放从冻结暂停中恢复（长按前就在暂停的用户返回 false）。
     */
    private fun endHoldPlaybackFreeze(): Boolean {
        if (!holdPlaybackFrozen) return false
        holdPlaybackFrozen = false
        val shouldResume = holdPlaybackWasPlaying
        holdPlaybackWasPlaying = false
        if (shouldResume) {
            player?.play()
        }
        return shouldResume
    }

    /**
     * 视图离屏兜底：只清冻结标志不恢复播放——离屏后播放器的播放/暂停
     * 交还生命周期管理（onStop 已强制 playWhenReady=false，此处再 play()
     * 会造成后台播放）。
     */
    private fun discardHoldPlaybackFreeze() {
        holdPlaybackFrozen = false
        holdPlaybackWasPlaying = false
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
        showTimelineThumbPreview(targetMs, duration)
        val seekSeconds = kotlin.math.abs(tapAccumulateDeltaMs / 1000L).toInt().coerceAtLeast(1)
        // blbl smartSeek：中央只有"快进 Xs"文字提示，缩略图由跟随浮窗展示
        ensureSeekOverlay("tap_seek")?.showSwipeSeek(
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
            if (wasTimebarSeek) {
                // timebar 场景焦点始终在进度条上、语义是完整控制栏：恢复信息层即可
                // （finishSeekPreviewProgressOnly 在持久细条模式下会 exit 整个控制器，
                //  show() 随之把焦点抢给播放按钮——短按后焦点丢失的回归根因）
                controller?.cancelSeekPreview()
                hideTimelineThumbPreviewNow()
                timebarSeekActive = false
                timebarSeekStartMs = 0L
                controller?.show()
                controller?.startProgressUpdates()
                controller?.requestTimeBarFocus()
                uiCoordinator?.transition(com.mytvb.feature.player.UiEvent.SeekFinished)
            } else {
                // blbl commitDeferredKeySeekPreview → scheduleHideVideoShotPreviewAfterSeek
                controller?.finishSeekPreviewProgressOnly()
                timelineThumbPreview?.hideAfter(500L)
                uiCoordinator?.transition(com.mytvb.feature.player.UiEvent.SeekFinished)
            }
            seekOverlayView?.finishSwipeSeek()
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
                uiCoordinator?.transition(com.mytvb.feature.player.UiEvent.SeekTypeChanged(
                    com.mytvb.feature.player.SeekType.TAP))
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
                        // 首跳不立即执行（blbl 手感：起手画面静止），tick 循环 40ms 后开始推进
                        beginHoldPlaybackFreeze()
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
                if (endHoldPlaybackFreeze()) {
                    resumeDanmaku()
                }
                controller?.cancelSeekPreview()
                timelineThumbPreview?.hideAfter(500L)
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

        // 步长按片长自适应：长按 timebarSeekTraverseMs 走完全片（与 UI 隐藏长按
        // 路径同一模型），替代原固定 60s 步长（30 分钟剧 3 秒后即 2000s/s，快进过头）
        val step = timebarScrubStepMs(durationMs = duration) * if (timebarSeekForward) 1 else -1
        timebarSeekTargetMs = (timebarSeekTargetMs + step).coerceIn(0L, duration)

        controller?.beginSeekPreview(timebarSeekTargetMs)
        // blbl 形态：缩略图浮窗跟随进度条滑块（不是中央 overlay）
        showTimelineThumbPreview(timebarSeekTargetMs, duration)
    }

    /** 长按拖动步长：duration × tick / traverse，长按恒定时长走完全片。 */
    private fun timebarScrubStepMs(durationMs: Long): Long {
        val duration = durationMs.coerceAtLeast(0L)
        if (duration <= 0L) return 0L
        val step = duration.toDouble() * timebarSeekTickMs.toDouble() / timebarSeekTraverseMs.toDouble()
        return kotlin.math.round(step).toLong().coerceAtLeast(1L)
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

    private fun getTimebarSeekIntervalMs(): Long = timebarSeekTickMs

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
                uiCoordinator?.transition(com.mytvb.feature.player.UiEvent.SeekFinished)
            }
        }
        timebarSeekIdleRunnable = runnable
        postDelayed(runnable, timebarSeekIdleTimeoutMs)
    }

    private fun cancelTimebarSeekIdle() {
        timebarSeekIdleRunnable?.let { removeCallbacks(it) }
        timebarSeekIdleRunnable = null
    }

    // ==================== Timeline thumb preview helpers ====================

    private fun ensureTimelineThumbPreview(): TimelineThumbPreviewView {
        timelineThumbPreview?.let { return it }
        val preview = TimelineThumbPreviewView(context)
        addView(
            preview,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        )
        preview.translationZ = 3f
        pendingSeekPreviewSnapshot?.let { preview.setSnapshot(it) }
        timelineThumbPreview = preview
        return preview
    }

    /** 把缩略图浮窗定位到进度条上方、水平跟随预览位置（blbl positionVideoShotPreviewX 语义）。 */
    private fun showTimelineThumbPreview(positionMs: Long, durationMs: Long) {
        val controllerRef = controller ?: return
        if (!controllerRef.getTimeBarTrackBounds(timelineTrackBounds)) return
        getLocationInWindow(timelineHostLoc)
        timelineTrackBounds.offset(-timelineHostLoc[0], -timelineHostLoc[1])
        ensureTimelineThumbPreview().show(positionMs, durationMs, timelineTrackBounds)
    }

    private fun hideTimelineThumbPreviewNow() {
        timelineThumbPreview?.hideNow()
    }

    // ==================== End timebar seek ====================

    private fun handleDouyinSwipeTouch(event: MotionEvent): Boolean {
        val listener = douyinModeKeyListener ?: return false
        if (!listener.isDouyinModeActive()) return false
        if (isSettingViewShowing() || douyinTransitionRunning || isSwipeSeeking) {
            return false
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downTouchX = event.x
                downTouchY = event.y
                douyinTouchStartedInInteractiveArea =
                    controller?.isTouchWithinInteractiveArea(event.x, event.y) == true
                return false
            }

            MotionEvent.ACTION_MOVE -> {
                if (douyinTouchStartedInInteractiveArea) return false
                val deltaX = event.x - downTouchX
                val deltaY = event.y - downTouchY
                if (!isDouyinDragging) {
                    val verticalDrag = kotlin.math.abs(deltaY) > touchSlop &&
                        kotlin.math.abs(deltaY) > kotlin.math.abs(deltaX) * 1.25f
                    if (!verticalDrag) return false

                    val direction = if (deltaY < 0f) DouyinSwipeDirection.Next else DouyinSwipeDirection.Previous
                    beginDouyinDrag(direction)
                }
                updateDouyinDrag(deltaY)
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!isDouyinDragging) return false
                val deltaY = event.y - downTouchY
                val shouldCommit = douyinDragHasTarget &&
                    kotlin.math.abs(deltaY) >= height.coerceAtLeast(1) * douyinCommitThresholdRatio
                val direction = douyinDragDirection
                if (shouldCommit && direction != null) {
                    finishDouyinSwipe(direction)
                } else {
                    cancelDouyinSwipe()
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                if (!isDouyinDragging) return false
                cancelDouyinSwipe()
                return true
            }
        }
        return false
    }

    private fun handleDouyinNavigationKey(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (settingView?.isShowing() == true || seekSession?.isActive() == true || timebarSeekActive || tapCommitRunnable != null) {
            return false
        }
        if (douyinModeKeyListener?.isDouyinModeActive() != true) return false
        return when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN,
            KEYCODE_SYSTEM_NAVIGATION_DOWN_COMPAT -> {
                hideController()
                douyinModeKeyListener?.onDouyinNavigateNext()
                true
            }
            KeyEvent.KEYCODE_DPAD_UP,
            KEYCODE_SYSTEM_NAVIGATION_UP_COMPAT -> {
                hideController()
                douyinModeKeyListener?.onDouyinNavigatePrevious()
                true
            }
            else -> false
        }
    }

    private fun beginDouyinDrag(direction: DouyinSwipeDirection) {
        val preview = douyinPreview(direction)
        isDouyinDragging = true
        douyinDragDirection = direction
        douyinDragHasTarget = preview != null
        parent?.requestDisallowInterceptTouchEvent(true)
        hideController()
        prepareDouyinPreview(direction, preview)
    }

    private fun douyinPreview(direction: DouyinSwipeDirection): DouyinModePreview? {
        val listener = douyinModeKeyListener ?: return null
        return when (direction) {
            DouyinSwipeDirection.Next -> listener.peekDouyinNext()
            DouyinSwipeDirection.Previous -> listener.peekDouyinPrevious()
        }
    }

    private fun prepareDouyinPreview(direction: DouyinSwipeDirection, preview: DouyinModePreview?) {
        val layer = douyinPreviewLayer ?: return
        layer.animate().cancel()
        contentFrame?.animate()?.cancel()
        dmkMaskHost?.animate()?.cancel()
        val h = height.coerceAtLeast(1).toFloat()
        douyinPendingTargetOffset = if (direction == DouyinSwipeDirection.Next) h else -h
        douyinPreviewTitle?.text = preview?.title.orEmpty()
        if (preview?.coverUrl?.isNotBlank() == true) {
            douyinPreviewImage?.let { ImageLoader.loadVideoCover(it, preview.coverUrl) }
        } else {
            douyinPreviewImage?.setImageResource(R.drawable.default_video)
        }
        layer.translationY = douyinPendingTargetOffset
        layer.alpha = if (preview == null) 0f else 1f
        layer.visibility = if (preview == null) GONE else VISIBLE
        restoreOverlayZOrder()
    }

    private fun updateDouyinDrag(deltaY: Float) {
        val direction = douyinDragDirection ?: return
        val h = height.coerceAtLeast(1).toFloat()
        val signedDelta = when (direction) {
            DouyinSwipeDirection.Next -> deltaY.coerceAtMost(0f)
            DouyinSwipeDirection.Previous -> deltaY.coerceAtLeast(0f)
        }
        val dragOffset = if (douyinDragHasTarget) {
            signedDelta.coerceIn(-h, h)
        } else {
            (signedDelta * 0.18f).coerceIn(-h * 0.16f, h * 0.16f)
        }
        applyDouyinContentTranslation(dragOffset)
        douyinPreviewLayer?.let { layer ->
            layer.translationY = douyinPendingTargetOffset + dragOffset
            layer.alpha = if (douyinDragHasTarget) 1f else 0f
        }
    }

    private fun finishDouyinSwipe(direction: DouyinSwipeDirection) {
        isDouyinDragging = false
        douyinTransitionRunning = true
        val h = height.coerceAtLeast(1).toFloat()
        val exitOffset = if (direction == DouyinSwipeDirection.Next) -h else h
        animateDouyinLayers(
            contentOffset = exitOffset,
            previewOffset = 0f,
            durationMs = douyinAnimationDurationMs
        ) {
            douyinGestureCommittedTransition = true
            val handled = when (direction) {
                DouyinSwipeDirection.Next -> douyinModeKeyListener?.onDouyinNavigateNext() == true
                DouyinSwipeDirection.Previous -> douyinModeKeyListener?.onDouyinNavigatePrevious() == true
            }
            if (!handled) {
                resetDouyinVisualState()
                douyinTransitionRunning = false
                return@animateDouyinLayers
            }
            postDelayed({
                if (douyinTransitionRunning) {
                    resetDouyinVisualState()
                    douyinTransitionRunning = false
                }
            }, 3000)
        }
    }

    private fun cancelDouyinSwipe() {
        isDouyinDragging = false
        animateDouyinLayers(
            contentOffset = 0f,
            previewOffset = douyinPendingTargetOffset,
            durationMs = 180L,
            useOvershoot = true
        ) {
            resetDouyinVisualState()
        }
    }

    fun startDouyinPageTransition(
        directionValue: Int,
        targetPreview: DouyinModePreview? = null,
        onReady: () -> Unit
    ): Boolean {
        if (douyinGestureCommittedTransition) {
            douyinGestureCommittedTransition = false
            onReady()
            return true
        }
        if (douyinTransitionRunning || isDouyinDragging) return false
        val direction = if (directionValue >= 0) DouyinSwipeDirection.Next else DouyinSwipeDirection.Previous
        douyinTransitionRunning = true
        prepareDouyinPreview(direction, targetPreview ?: douyinPreview(direction))
        val h = height.coerceAtLeast(1).toFloat()
        val exitOffset = if (direction == DouyinSwipeDirection.Next) -h else h
        animateDouyinLayers(
            contentOffset = exitOffset,
            previewOffset = 0f,
            durationMs = douyinAnimationDurationMs
        ) {
            onReady()
            postDelayed({
                if (douyinTransitionRunning) {
                    resetDouyinVisualState()
                    douyinTransitionRunning = false
                }
            }, 3000)
        }
        return true
    }

    fun consumeDouyinGestureTransition(): Boolean {
        if (!douyinGestureCommittedTransition) return false
        douyinGestureCommittedTransition = false
        return true
    }

    fun awaitDouyinPageTransitionFirstFrame() {
        if (!douyinTransitionRunning || douyinFirstFrameWaitRegistered) return
        douyinFirstFrameWaitRegistered = true
        observeNextFirstFrame {
            resetDouyinVisualState()
            douyinTransitionRunning = false
        }
    }

    fun cancelDouyinPageTransition() {
        douyinGestureCommittedTransition = false
        douyinTransitionRunning = false
        isDouyinDragging = false
        resetDouyinVisualState()
    }

    fun showDouyinBoundaryBounce(directionValue: Int) {
        if (douyinTransitionRunning || isDouyinDragging) return
        val direction = if (directionValue >= 0) DouyinSwipeDirection.Next else DouyinSwipeDirection.Previous
        val h = height.coerceAtLeast(1).toFloat()
        val bounceOffset = h * 0.08f * if (direction == DouyinSwipeDirection.Next) -1f else 1f
        applyDouyinContentTranslation(bounceOffset)
        contentFrame?.animate()
            ?.translationY(0f)
            ?.setDuration(180L)
            ?.setInterpolator(OvershootInterpolator(0.7f))
            ?.start()
        dmkMaskHost?.animate()
            ?.translationY(0f)
            ?.setDuration(180L)
            ?.setInterpolator(OvershootInterpolator(0.7f))
            ?.start()
    }

    private fun animateDouyinLayers(
        contentOffset: Float,
        previewOffset: Float,
        durationMs: Long,
        useOvershoot: Boolean = false,
        onEnd: () -> Unit
    ) {
        val interpolator = if (useOvershoot) OvershootInterpolator(0.7f) else DecelerateInterpolator()
        var remaining = 2
        fun markEnd() {
            remaining--
            if (remaining <= 0) onEnd()
        }
        val contentAnimator = contentFrame?.animate()
        val maskAnimator = dmkMaskHost?.animate()
        if (contentAnimator == null && maskAnimator == null) {
            remaining--
        } else {
            contentAnimator?.translationY(contentOffset)
                ?.setDuration(durationMs)
                ?.setInterpolator(interpolator)
                ?.withEndAction { markEnd() }
                ?.start()
            maskAnimator?.translationY(contentOffset)
                ?.setDuration(durationMs)
                ?.setInterpolator(interpolator)
                ?.start()
            if (contentAnimator == null) markEnd()
        }
        val layer = douyinPreviewLayer
        if (layer == null) {
            markEnd()
        } else {
            layer.animate()
                .translationY(previewOffset)
                .alpha(1f)
                .setDuration(durationMs)
                .setInterpolator(interpolator)
                .withEndAction { markEnd() }
                .start()
        }
    }

    private fun applyDouyinContentTranslation(offset: Float) {
        contentFrame?.translationY = offset
        dmkMaskHost?.translationY = offset
    }

    private fun resetDouyinVisualState() {
        parent?.requestDisallowInterceptTouchEvent(false)
        douyinPreviewLayer?.animate()?.cancel()
        contentFrame?.animate()?.cancel()
        dmkMaskHost?.animate()?.cancel()
        applyDouyinContentTranslation(0f)
        douyinPreviewLayer?.visibility = GONE
        douyinPreviewLayer?.alpha = 0f
        douyinPreviewLayer?.translationY = 0f
        douyinDragDirection = null
        douyinDragHasTarget = false
        douyinTouchStartedInInteractiveArea = false
        douyinPendingTargetOffset = 0f
        douyinFirstFrameWaitRegistered = false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        touchInterceptListener?.let { if (it(event)) return true }
        if (isDouyinDragging && handleDouyinSwipeTouch(event)) {
            return true
        }
        if (handleDouyinSwipeTouch(event)) {
            return true
        }
        return super.dispatchTouchEvent(event)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // [DEBUG] 诊断小米电视确定键播放/暂停失效问题，定位后删除
        AppLog.d("DpadCenter", "onTouchEvent action=${event.action} src=${event.source}")
        if (isDouyinDragging && handleDouyinSwipeTouch(event)) {
            return true
        }
        if (isSwipeSeeking && handleSwipeSeekTouch(event)) {
            return true
        }
        if (controller?.isTouchWithinInteractiveArea(event.x, event.y) == true) {
            return false
        }
        if (handleDouyinSwipeTouch(event)) {
            return true
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
        // [DEBUG] 诊断小米电视确定键播放/暂停失效问题，定位后删除
        AppLog.d("DpadCenter", "performClick -> toggleControllerVisibility")
        toggleControllerVisibility()
        return super.performClick()
    }

    fun setResizeMode(resizeMode: Int) {
        maskResizeMode = resizeMode
        contentFrame?.setResizeMode(resizeMode)
        settingView?.setCurrentScreenRatio(resizeMode)
        dmkMaskHost?.invalidate()
    }

    fun setTitle(title: String?) {
        pendingTitle = title
        controller?.setTitle(title)
    }

    fun setSubTitle(subTitle: String?) {
        pendingSubTitle = subTitle
        controller?.setSubTitle(subTitle)
    }

    fun setLiveDuration(text: String) {
        pendingLiveDuration = text
        controller?.setLiveDuration(text)
    }

    fun setCustomErrorMessage(message: CharSequence?) {
        customErrorMessage = message
        updateErrorMessage()
    }

    fun setOnPlayerSettingChange(listener: OnPlayerSettingChange?) {
        pendingPlayerSettingChangeListener = listener
        settingView?.setOnPlayerSettingChange(listener)
    }

    fun setOnVideoSettingChangeListener(listener: OnVideoSettingChangeListener?) {
        pendingVideoSettingChangeListener = listener
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
        seekOverlayView?.setPersistentBottomProgressEnabled(enabled)
        controller?.setProgressOnlyUiEnabled(!enabled)
        if (!enabled) {
            uiCoordinator?.clearSeekPreview()
        }
    }

    fun showHoldSeekOverlay(targetPositionMs: Long, durationMs: Long, deltaMs: Long) {
        // blbl 长按 scrub 无中央指示（startHoldScrubSeek 只走 showSeekOsd）：
        // 瞬时进度条由 seekPreviewRenderer 驱动，这里只驱动跟随滑块的缩略图浮窗
        showTimelineThumbPreview(targetPositionMs, durationMs)
    }

    fun finishHoldSeekOverlay() {
        seekOverlayView?.finishSwipeSeek()
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

    fun setLiveLines(lines: List<LiveLineInfo>, selectedIndex: Int) {
        settingView?.setLiveLines(lines, selectedIndex)
    }

    fun selectLiveLine(index: Int) {
        settingView?.selectLiveLine(index)
    }

    fun showLiveLineMenu() {
        controller?.rememberCurrentFocusTarget()
        settingView?.showLiveLineMenu()
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
        activeDanmakuController()?.updatePlaybackSpeed(speed)
        refreshSpeedBadge()
    }

    fun setAfterPlayMode(mode: com.mytvb.feature.player.settings.AfterPlayMode) {
        pendingAfterPlayMode = mode
        settingView?.setAfterPlayMode(mode)
    }

    fun getAfterPlayMode(): com.mytvb.feature.player.settings.AfterPlayMode {
        return settingView?.getAfterPlayMode() ?: pendingAfterPlayMode
    }

    fun showSubtitleSettingView() {
        controller?.rememberCurrentFocusTarget()
        settingView?.showSubtitleMenu()
    }

    fun setRepeatMode(repeatMode: Int) {
        pendingRepeatMode = repeatMode
        controller?.setRepeatMode(repeatMode)
    }

    fun setSeekSecond(seconds: Int) {
        pendingSeekSeconds = seconds
        seekOverlayView?.seekSeconds = seconds
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
            // 关闭设置面板不是 seek：此处没有 player.seekTo()，不能按 forceSeek 通知引擎重建场景。
            // 此前用 player.currentPosition 强制 forceSeek，与弹幕平滑位置的几十毫秒固有偏差
            // 会被引擎误判为"位置回退"→ 清空防重放历史 → 最近一个滚动窗口的弹幕整体重放
            // （用户视角："弹幕滚完又出现一遍"）。设置变更由 updateConfig 路径自行处理，
            // 位置同步由 positionProvider 自动跟随，这里无需任何弹幕同步。
            restoreControllerAfterGesture(showIndefinitely = true)
        }
    }

    fun showHideDmSwitchButton(show: Boolean) {
        pendingDmSwitchVisible = show
        controller?.showHideDmSwitchButton(show)
    }

    fun showHidePlaySpeedButton(show: Boolean) {
        pendingPlaySpeedButtonVisible = show
        controller?.showHidePlaySpeedButton(show)
    }

    /** 控制栏"播放速度"按键入口：打开设置面板的倍速子菜单。 */
    fun showPlaybackSpeedSettingView() {
        controller?.rememberCurrentFocusTarget()
        settingView?.showPlaybackSpeedMenu()
    }

    fun setMirrorEnabled(enabled: Boolean) {
        val currentPlayer = player ?: return
        settingView?.setScreenMirrorEnabled(enabled)
        if (enabled) {
            dmMaskController.setEnabled(false)
        }

        val currentSurface = videoSurfaceView

        if (currentSurface is TextureView) {
            currentSurface.scaleX = if (enabled) -1f else 1f
            AppLog.i(
                "PlayerViewMirror",
                "mirror=$enabled surface=TextureView scaleX=${currentSurface.scaleX}"
            )
            restoreOverlayZOrder()
            return
        }

        if (!enabled || currentSurface !is SurfaceView) {
            currentSurface?.scaleX = 1f
            restoreOverlayZOrder()
            return
        }

        // 首次开镜像：SurfaceView -> TextureView 热切换。
        // 直接 new TextureView 并 setVideoTextureView 会让解码器同时持有旧 SurfaceView 的输出引用，
        // 在 Amlogic 硬解（OMX.amlogic.hevc.decoder.awesome2）上触发解码器半死状态：
        // 持续 audio_underrun / video_dropped / BUFFERING↔READY 振荡，直到切下一个视频重建解码器才恢复。
        // 这里照搬 onStart 的恢复手段：先 clearVideoSurfaceView 干净解绑 -> 移除旧 SurfaceView ->
        // 建新 TextureView -> 绑定后 seekTo(pos) 逼解码器重出帧，规避半死状态。
        val frame = contentFrame ?: return
        val pos = currentPlayer.currentPosition.coerceAtLeast(0L)
        val wasPlaying = currentPlayer.isPlaying
        val stateBefore = currentPlayer.playbackState
        AppLog.i(
            "PlayerViewMirror",
            "mirror=true switch SurfaceView->TextureView pos=${pos}ms state=$stateBefore playing=$wasPlaying"
        )

        currentPlayer.clearVideoSurfaceView(currentSurface)
        if (currentSurface.parent === frame) {
            frame.removeView(currentSurface)
        }

        val textureView = TextureView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            isOpaque = true
            scaleX = -1f
        }
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                textureView.surfaceTextureListener = null
                videoSurfaceView = textureView
                textureView.scaleX = -1f
                currentPlayer.setVideoTextureView(textureView)
                // seek 到原位置逼解码器丢弃向旧 Surface 的悬空输出、重出新帧，
                // 对齐 PlayerActivity.onStart 的 surface 变更恢复手段。
                if (stateBefore == Player.STATE_READY || stateBefore == Player.STATE_BUFFERING) {
                    currentPlayer.seekTo(pos)
                }
                if (wasPlaying) {
                    currentPlayer.playWhenReady = true
                }
                // 出帧后再归位 Z 序，避免切换瞬间弹幕层被新 surface 盖住。
                observeNextFirstFrame {
                    restoreOverlayZOrder()
                }
                AppLog.i("PlayerViewMirror", "mirror=true surface=TextureView created bound seekTo=${pos}ms")
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }

        frame.addView(textureView, 0)
        restoreOverlayZOrder()
    }

    private fun restoreOverlayZOrder() {
        val controllerLayer: View? = controller ?: findViewById<View>(R.id.exo_controller_placeholder)
        dmkMaskHost?.bringToFront()
        findViewById<View>(R.id.interaction_view)?.bringToFront()
        pauseIndicatorView?.bringToFront()
        douyinPreviewLayer?.bringToFront()
        controllerLayer?.bringToFront()
        resumeHintView?.bringToFront()
        seekOverlayView?.bringToFront()
        settingView?.bringToFront()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            dmkMaskHost?.translationZ = 1f
            findViewById<View>(R.id.interaction_view)?.translationZ = 2f
            pauseIndicatorView?.translationZ = 3f
            douyinPreviewLayer?.translationZ = 4f
            controllerLayer?.translationZ = 5f
            resumeHintView?.translationZ = 6f
            seekOverlayView?.translationZ = 7f
            settingView?.translationZ = 8f
        }
    }

    fun showResumeHint(text: String) {
        val hintView = ensureResumeHintView()
        resumeHintPositionText?.text = text
        updateResumeHintPosition(animate = false)
        hintView.animate().cancel()
        hintView.alpha = 1f
        hintView.visibility = VISIBLE
        restoreOverlayZOrder()
    }

    fun hideResumeHint() {
        resumeHintView?.let { view ->
            view.animate().cancel()
            view.visibility = GONE
            view.alpha = 1f
        }
    }

    private fun ensureResumeHintView(): LinearLayout {
        resumeHintView?.let { return it }
        val hint = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = false
            isClickable = false
            alpha = 1f
            visibility = GONE
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = GradientDrawable().apply {
                cornerRadius = dp(2).toFloat()
                setColor(0xCC111216.toInt())
            }
        }
        val positionText = ScaledTextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(
                android.util.TypedValue.COMPLEX_UNIT_PX,
                resources.getDimension(R.dimen.px30)
            )
            includeFontPadding = false
            typeface = Typeface.DEFAULT_BOLD
        }
        val actionText = ScaledTextView(context).apply {
            text = context.getString(R.string.resume_hint_play_from_start)
            setTextColor(0xFFFF5A9E.toInt())
            setTextSize(
                android.util.TypedValue.COMPLEX_UNIT_PX,
                resources.getDimension(R.dimen.px30)
            )
            includeFontPadding = false
            typeface = Typeface.DEFAULT_BOLD
        }
        hint.addView(
            positionText,
            LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        )
        hint.addView(
            actionText,
            LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(14)
            }
        )
        addView(
            hint,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.START or Gravity.BOTTOM
                marginStart = dp(RESUME_HINT_MARGIN_START_DP)
                bottomMargin = dp(RESUME_HINT_MARGIN_BOTTOM_DP)
            }
        )
        resumeHintView = hint
        resumeHintPositionText = positionText
        return hint
    }

    private fun updateResumeHintPosition(animate: Boolean) {
        val hint = resumeHintView ?: return
        val targetTranslationY = if (controller?.isFullyVisible() == true) {
            -dp(RESUME_HINT_CONTROLLER_OFFSET_DP).toFloat()
        } else {
            0f
        }
        if (animate && hint.visibility == VISIBLE) {
            hint.animate()
                .translationY(targetTranslationY)
                .setDuration(RESUME_HINT_ANIMATION_MS)
                .setInterpolator(DecelerateInterpolator())
                .start()
        } else {
            hint.animate().cancel()
            hint.translationY = targetTranslationY
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
    }

    fun showHideMirrorButton(show: Boolean) {
        pendingMirrorVisible = show
        controller?.showHideMirrorButton(show)
    }

    fun showHideNextPrevious(show: Boolean) {
        pendingNextPreviousVisible = show
        controller?.showHideNextPrevious(show)
    }

    fun showHideFfRe(show: Boolean) {
        pendingFfReVisible = show
        controller?.showHideFfRe(show)
    }

    fun setSimpleKeyPressEnabled(enabled: Boolean) {
        pendingSimpleKeyPressEnabled = enabled
        controller?.setSimpleKeyPressEnabled(enabled)
    }

    fun setEpisodeNavigationEnabled(previousEnabled: Boolean, nextEnabled: Boolean) {
        pendingEpisodeNavigationEnabled = previousEnabled to nextEnabled
        controller?.setEpisodeNavigationEnabled(previousEnabled, nextEnabled)
    }

    fun showHideEpisodeButton(show: Boolean) {
        pendingEpisodeButtonVisible = show
        controller?.showHideEpisodeButton(show)
    }

    fun requestPlayPauseFocus() {
        controller?.requestPlayPauseFocus()
    }

    fun requestEpisodeButtonFocus() {
        controller?.requestEpisodeButtonFocus()
    }

    fun showHideActionButton(show: Boolean) {
        pendingActionButtonVisible = show
        controller?.showHideActionButton(show)
    }

    fun requestMoreButtonFocus() {
        controller?.requestMoreButtonFocus()
    }

    fun showHideRelatedButton(show: Boolean) {
        pendingRelatedButtonVisible = show
        controller?.showHideRelatedButton(show)
    }

    fun showHideCommentButton(show: Boolean) {
        pendingCommentButtonVisible = show
        controller?.showHideCommentButton(show)
    }

    fun requestRelatedButtonFocus() {
        controller?.requestRelatedButtonFocus()
    }

    fun requestOwnerButtonFocus() {
        controller?.requestOwnerButtonFocus()
    }

    fun requestCommentButtonFocus() {
        controller?.requestCommentButtonFocus()
    }

    fun rememberCurrentFocusTarget() {
        controller?.rememberCurrentFocusTarget()
    }

    fun restoreRememberedFocus() {
        controller?.restoreRememberedFocus()
    }

    fun showHideRepeatButton(show: Boolean) {
        pendingRepeatButtonVisible = show
        controller?.showHideRepeatButton(show)
    }

    fun showHideSubtitleButton(show: Boolean) {
        pendingSubtitleButtonVisible = show
        controller?.showHideSubtitleButton(show)
    }

    fun showHideLiveSettingButton(show: Boolean) {
        pendingLiveSettingButtonVisible = show
        controller?.showHideLiveSettingButton(show)
    }

    fun showHideRefreshButton(show: Boolean) {
        pendingRefreshButtonVisible = show
        controller?.showHideRefreshButton(show)
    }

    fun showHideLineButton(show: Boolean) {
        pendingLineButtonVisible = show
        controller?.showHideLineButton(show)
    }

    fun showHideTimeBar(show: Boolean) {
        pendingTimeBarVisible = show
        controller?.showHideTimeBar(show)
    }

    fun showHideTimeText(show: Boolean) {
        pendingTimeTextVisible = show
        controller?.showHideTimeText(show)
    }


    fun showSettingButton(show: Boolean) {
        pendingSettingButtonVisible = show
        controller?.showSettingButton(show)
    }

    fun setShowHideOwnerInfo(show: Boolean) {
        pendingOwnerButtonVisible = show
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
        surfaceDetachedForBackground = true
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
        surfaceDetachedForBackground = false
        when (val surfaceView = videoSurfaceView) {
            is SurfaceView -> currentPlayer.setVideoSurfaceView(surfaceView)
            is TextureView -> currentPlayer.setVideoTextureView(surfaceView)
        }
        // 后台回前台：重置 jank EMA，防止脏数据触发自动关 mask
        dmMaskController.onResume()
    }

    /**
     * 前台 Surface 静默失效后的自愈，供 Activity.onResume 调用。
     *
     * 触发场景：投影仪/TV 盒子弹出系统弹窗（权限框、悬浮通知等）再返回，
     * 这类弹窗通常只触发 Activity 的 onPause/onResume，不会走 onStop/onStart，
     * 因此 onStart 里那套"reattach + seekTo 重建解码器"的恢复逻辑不会执行。
     * 而此时底层 Surface 可能已被系统销毁重建，解码器仍向旧（已死）的 Surface 输出，
     * 表现为：画面冻结/黑屏，但音频和弹幕继续，点暂停/播放画面也不动。
     *
     * 这里重新绑定 Surface 并 seekTo 当前位置，逼解码器丢弃向死 Surface 的输出、重出新帧，
     * 与 onStart 的恢复手段一致。只在"前台、且 player 未主动 detach"时执行，避免和后台路径冲突。
     *
     * 不通过注册额外 SurfaceHolder.Callback 实现：在部分机型（如 gracelte/API28）上，
     * 自定义 callback 与 SurfaceView 内部 updateSurface 回调竞态会导致
     * "Exception configuring surface" NPE，反而让首帧无法输出（实测黑屏）。
     * onResume 时机晚于 SurfaceView 完成配置，无此竞态。
     */

    // ==================== SeekDiag: seek 后画面卡死诊断（纯观测）====================
    // 不改任何播放逻辑，只打日志。坐实"async flush 后首帧回调丢失"假设后即移除。
    private fun armSeekDiag(oldPosMs: Long, newPosMs: Long) {
        cancelSeekDiag()
        val currentPlayer = player ?: return
        seekDiagStartedAtElapsedMs = SystemClock.elapsedRealtime()
        val dropped = droppedFramesSnapshot()
        seekDiagLastDroppedFrames = dropped
        AppLog.d("SeekDiag",
            "seek ${oldPosMs}ms→${newPosMs}ms state=${stateName(currentPlayer.playbackState)} " +
                "playWhenReady=${currentPlayer.playWhenReady} dropped=$dropped")
        // 看门狗：3s 内没收到首帧 → 视频管线断裂铁证
        val watchdog = Runnable {
            val startedAt = seekDiagStartedAtElapsedMs
            if (startedAt == 0L) return@Runnable
            val p = player
            val nowDropped = droppedFramesSnapshot()
            AppLog.w("SeekDiag",
                "NO_FIRST_FRAME_AFTER_SEEK ${seekDiagWatchdogTimeoutMs}ms " +
                    "state=${if (p != null) stateName(p.playbackState) else "null"} " +
                    "playWhenReady=${p?.playWhenReady} pos=${p?.currentPosition}ms " +
                    "dropped=+${nowDropped - seekDiagLastDroppedFrames}")
            seekDiagWatchdogRunnable = null
        }
        seekDiagWatchdogRunnable = watchdog
        postDelayed(watchdog, seekDiagWatchdogTimeoutMs)
        // 心跳：seek 后 6s 内每 500ms 记录 state/dropped,观察卡死期间解码器挣扎情况
        scheduleSeekDiagHeartbeat()
    }

    private fun scheduleSeekDiagHeartbeat() {
        val startedAt = seekDiagStartedAtElapsedMs
        if (startedAt == 0L) return
        val heartbeat = Runnable {
            val s = seekDiagStartedAtElapsedMs
            if (s == 0L) return@Runnable
            val p = player ?: return@Runnable
            val elapsed = SystemClock.elapsedRealtime() - s
            if (elapsed > seekDiagHeartbeatDurationMs) {
                seekDiagHeartbeatRunnable = null
                return@Runnable
            }
            val nowDropped = droppedFramesSnapshot()
            AppLog.d("SeekDiag",
                "heartbeat elapsed=${elapsed}ms state=${stateName(p.playbackState)} " +
                    "pos=${p.currentPosition}ms dropped=+${nowDropped - seekDiagLastDroppedFrames}")
            seekDiagLastDroppedFrames = nowDropped
            scheduleSeekDiagHeartbeat()
        }
        seekDiagHeartbeatRunnable = heartbeat
        postDelayed(heartbeat, seekDiagHeartbeatIntervalMs)
    }

    private fun onSeekDiagFirstFrame() {
        val startedAt = seekDiagStartedAtElapsedMs
        if (startedAt == 0L) return
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        val currentPlayer = player
        AppLog.d("SeekDiag",
            "first_frame_after_seek elapsed=${elapsed}ms " +
                "state=${if (currentPlayer != null) stateName(currentPlayer.playbackState) else "null"}")
        cancelSeekDiag()
    }

    private fun cancelSeekDiag() {
        seekDiagWatchdogRunnable?.let { removeCallbacks(it) }
        seekDiagWatchdogRunnable = null
        seekDiagHeartbeatRunnable?.let { removeCallbacks(it) }
        seekDiagHeartbeatRunnable = null
        seekDiagStartedAtElapsedMs = 0L
    }

    private fun droppedFramesSnapshot(): Long {
        // dropped frames 在 media3 里分散在各 Renderer 的 DecoderCounters 上,
        // Player/ExoPlayer 接口不直接暴露。此处返回 -1 表示未知,
        // 完整 dropped 统计已由 PlaybackPerf 的 "video_dropped_frames" 日志覆盖。
        return -1L
    }

    private fun stateName(state: Int): String = when (state) {
        androidx.media3.common.Player.STATE_IDLE -> "IDLE"
        androidx.media3.common.Player.STATE_BUFFERING -> "BUFFERING"
        androidx.media3.common.Player.STATE_READY -> "READY"
        androidx.media3.common.Player.STATE_ENDED -> "ENDED"
        else -> "UNKNOWN($state)"
    }

    fun recoverVideoRenderIfNeeded(reason: String) {
        if (surfaceDetachedForBackground) return
        val currentPlayer = player ?: return
        // 同时支持 SurfaceView 和 TextureView：镜像开启后 videoSurfaceView 会变成 TextureView，
        // 此时若发生前台 Surface 静默失效也需要重绑 + seekTo 自愈。
        val surfaceView = videoSurfaceView ?: return
        AppLog.w("SurfaceLifecycle", "recoverVideoRenderIfNeeded reason=$reason surface=${surfaceView.javaClass.simpleName} state=${currentPlayer.playbackState} pos=${currentPlayer.currentPosition}")
        when (surfaceView) {
            is SurfaceView -> currentPlayer.setVideoSurfaceView(surfaceView)
            is TextureView -> currentPlayer.setVideoTextureView(surfaceView)
            else -> return
        }
        val state = currentPlayer.playbackState
        if (state == Player.STATE_READY || state == Player.STATE_BUFFERING) {
            currentPlayer.seekTo(currentPlayer.currentPosition)
        }
        dmMaskController.onResume()
    }

    fun destroy() {
        cancelSeekDiag()
        hideResumeHint()
        controller?.clearVideoSettingChangeListener()
        val currentPlayer = player
        currentPlayer?.removeListener(componentListener)
        when (val surfaceView = videoSurfaceView) {
            is SurfaceView -> currentPlayer?.clearVideoSurfaceView(surfaceView)
            is TextureView -> currentPlayer?.clearVideoTextureView(surfaceView)
        }
        controller?.removeVisibilityListener(controllerComponentListener)
        handler.removeCallbacksAndMessages(null)
        stopUiFrameMonitor()
        liteDanmakuController?.release()
        liteDanmakuView?.let { view -> (view.parent as? ViewGroup)?.removeView(view) }
        liteDanmakuView = null
        liteDanmakuController = null
        dmMaskController.dispose()
        maskRetryScope.cancel()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startUiFrameMonitor()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelOkLongPressSpeed()
        cancelPendingHoldStart()
        cancelPendingExitSeekProgressOnly()
        cancelTimebarSeekLoop()
        cancelTimebarSeekIdle()
        timebarSeekActive = false
        seekSession?.cancel()
        discardHoldPlaybackFreeze()
        hideTimelineThumbPreviewNow()
        stopUiFrameMonitor()
    }

    private fun startUiFrameMonitor() {
        if (uiFrameMonitorStarted) return
        uiFrameMonitorStarted = true
        lastUiFrameTimeNs = 0L
        Choreographer.getInstance().postFrameCallback(uiFrameCallback)
    }

    private fun stopUiFrameMonitor() {
        if (!uiFrameMonitorStarted) return
        uiFrameMonitorStarted = false
        lastUiFrameTimeNs = 0L
        Choreographer.getInstance().removeFrameCallback(uiFrameCallback)
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
        pendingSeekPreviewSnapshot = snapshot
        seekOverlayView?.setSeekPreviewSnapshot(snapshot)
        timelineThumbPreview?.setSnapshot(snapshot)
    }

    fun setControllerAutoShow(autoShow: Boolean) {
        controllerAutoShow = autoShow
    }

    fun getControllerAutoShow(): Boolean = controllerAutoShow

    fun setTimeBarMinUpdateInterval(intervalMs: Int) {
        pendingTimeBarMinUpdateIntervalMs = intervalMs
        controller?.setTimeBarMinUpdateInterval(intervalMs)
    }

    fun setShowMultiWindowTimeBar(show: Boolean) {
        pendingShowMultiWindowTimeBar = show
        controller?.setShowMultiWindowTimeBar(show)
    }

    fun setSponsorSegments(segments: List<SponsorSegment>) {
        pendingSponsorSegments = segments
        controller?.setSponsorSegments(segments)
    }

    fun setSponsorDuration(durationMs: Long) {
        pendingSponsorDurationMs = durationMs
        controller?.setSponsorDuration(durationMs)
    }

    /**
     * 创建弹幕引擎（性能优先轻量引擎，唯一实现）。需在 setData 之前、播放器 setup 时调用，
     * 保证引擎收到播放起始位置/首帧等早期通知；幂等，可安全重复调用。
     * 引擎作为蒙版宿主的子层，防挡蒙版独立于弹幕渲染。
     */
    fun setupDanmakuEngine() {
        if (liteDanmakuController != null) return
        val view = DanmakuView(context).apply {
            layoutParams = FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            isClickable = false
            isFocusable = false
        }
        dmkMaskHost?.addView(view) ?: addView(view)
        liteDanmakuView = view
        liteDanmakuController = BlblDanmakuController(context) { liteDanmakuView }.also {
            it.playerPositionProvider = {
                when {
                    liveDanmakuClockBaseMs > 0L -> liveDanmakuClockMs()
                    danmakuPositionArmed -> player?.currentPosition ?: 0L
                    else -> 0L
                }
            }
        }
        restoreOverlayZOrder()
    }

    fun setDanmakuData(
        data: List<DmModel>,
        filterContext: DanmakuFilterContext = DanmakuFilterContext.EMPTY,
        startupTraceId: String = PlaybackStartupTrace.NO_TRACE,
        startupTraceStartElapsedMs: Long = 0L
    ) {
        liveDanmakuClockBaseMs = 0L
        syncDanmakuSettings()
        setupDanmakuEngine()
        activeDanmakuController()?.setData(data, filterContext, startupTraceId, startupTraceStartElapsedMs)
    }

    fun appendDanmakuData(
        data: List<DmModel>,
        filterContext: DanmakuFilterContext = DanmakuFilterContext.EMPTY
    ) {
        activeDanmakuController()?.appendData(data, filterContext)
    }

    fun startLiveDanmaku() {
        syncDanmakuSettings()
        setupDanmakuEngine()
        // 每次进入直播都重锚独立时钟（含切房间），配合 startLive 清空时间线。
        liveDanmakuClockBaseMs = SystemClock.elapsedRealtime()
        activeLiveDanmakuController()?.startLive()
    }

    fun addLiveDanmaku(dm: DmModel) {
        activeLiveDanmakuController()?.addLiveDanmaku(dm)
    }

    fun setDanmakuEnabled(enabled: Boolean) {
        if ((settingView?.getDmEnable() ?: enabled) != enabled) {
            settingView?.dmEnableClick()
        }
        activeDanmakuController()?.setEnabled(enabled)
        dmMaskController.setDanmakuVisible(enabled)
        updateVideoFrameRateStrategy(enabled)
    }

    fun pauseDanmaku() {
        activeDanmakuController()?.pause()
    }

    fun resumeDanmaku() {
        activeDanmakuController()?.resume()
    }

    fun stopDanmaku() {
        activeDanmakuController()?.stop()
    }

    fun syncDanmakuPosition(positionMs: Long, forceSeek: Boolean = false) {
        activeDanmakuController()?.syncPosition(positionMs, forceSeek)
        // 注入 providers 到 host layout（如果尚未注入）
        dmkMaskHost?.let { host ->
            if (host.ptsProvider == null) {
                host.ptsProvider = maskPtsProvider
                host.videoBoundsProvider = maskVideoBoundsProvider
                host.shouldRenderMask = maskShouldRenderProvider
                host.isSeeking = maskIsSeekingProvider
                host.frameQueryReporter = maskFrameQueryReporter
            }
        }
        if (forceSeek) {
            dmMaskController.onSeek()
        }
        val speed = player?.playbackParameters?.speed ?: 1f
        dmMaskController.onPlayerClockChanged(speed, positionMs)
        dmMaskController.pushMaskUpdate()
    }

    fun setDmMaskRepository(repository: com.mytvb.model.dm.DmMaskRepository) {
        dmMaskController.setRepository(repository)
    }

    suspend fun loadDmMask(maskUrl: String, cid: Long, fps: Int): Boolean {
        pendingDmMaskRequest = DmMaskRequest(maskUrl, cid, fps)
        val shieldEnabled = settingView?.getDmSmartShield() ?: false
        if (!shieldEnabled) {
            AppLog.d("DmMask", "loadDmMask skipped: smart shield disabled, cid=$cid")
            return false
        }
        val success = loadDmMaskInternal(maskUrl, cid, fps, delayForDanmakuStartup = true)
        if (success && pendingDmMaskRequest == DmMaskRequest(maskUrl, cid, fps)) {
            pendingDmMaskRequest = null
        }
        return success
    }

    private suspend fun loadDmMaskInternal(
        maskUrl: String,
        cid: Long,
        fps: Int,
        delayForDanmakuStartup: Boolean = false
    ): Boolean {
        val shouldDelay = delayForDanmakuStartup && !dmMaskController.hasCachedMask(cid)
        if (shouldDelay) {
            delay(DM_MASK_STARTUP_LOAD_DELAY_MS)
            val pending = pendingDmMaskRequest
            if (pending == null ||
                pending.maskUrl != maskUrl ||
                pending.cid != cid ||
                pending.fps != fps
            ) {
                AppLog.d("DmMask", "loadDmMask abandoned: stale request, cid=$cid")
                return false
            }
        }
        if (settingView?.getDmSmartShield() != true) {
            AppLog.d("DmMask", "loadDmMask abandoned: smart shield disabled, cid=$cid")
            return false
        }
        val success = dmMaskController.loadMask(maskUrl, cid, fps)
        if (success) {
            dmkMaskHost?.let { host ->
                host.ptsProvider = maskPtsProvider
                host.videoBoundsProvider = maskVideoBoundsProvider
                host.shouldRenderMask = maskShouldRenderProvider
                host.isSeeking = maskIsSeekingProvider
                host.frameQueryReporter = maskFrameQueryReporter
            }
            dmMaskController.setEnabled(settingView?.getDmSmartShield() ?: false)
            player?.let {
                dmMaskController.onPlayerClockChanged(
                    it.playbackParameters.speed,
                    it.currentPosition.coerceAtLeast(0L)
                )
            }
        }
        return success
    }

    private fun retryPendingDmMaskLoad() {
        val request = pendingDmMaskRequest ?: return
        handler.post {
            maskRetryScope.launch {
                val success = loadDmMaskInternal(
                    request.maskUrl,
                    request.cid,
                    request.fps,
                    delayForDanmakuStartup = true
                )
                AppLog.d("DmMask", "retry pending mask: cid=${request.cid} success=$success")
                if (success && pendingDmMaskRequest == request) {
                    pendingDmMaskRequest = null
                }
            }
        }
    }

    fun releaseDmMask() {
        pendingDmMaskRequest = null
        dmMaskController.release()
    }

    fun setDmSmartShieldEnabled(enabled: Boolean) {
        dmMaskController.setEnabled(enabled)
        if (enabled) {
            retryPendingDmMaskLoad()
        }
    }

    private data class DmMaskRequest(
        val maskUrl: String,
        val cid: Long,
        val fps: Int
    )

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
        return useController
    }

    override fun setVisibility(visibility: Int) {
        super.setVisibility(visibility)
        when (val surface = videoSurfaceView) {
            is SurfaceView -> surface.visibility = visibility
            is TextureView -> surface.visibility = visibility
        }
    }

    private fun syncDanmakuSettings() {
        val snapshot = buildDanmakuSettingsSnapshot()
        dmMaskController.setDanmakuVisible(snapshot.enabled)
        updateVideoFrameRateStrategy(snapshot.enabled)
        activeDanmakuController()?.applySettings(snapshot)
    }

    private fun updateVideoFrameRateStrategy(danmakuEnabled: Boolean) {
        // 弹幕是独立 UI 覆盖层。29.97fps 视频若让 Surface 切到约 30Hz，两套引擎都会
        // 出现运动拖影；关闭弹幕后恢复 Media3 默认策略，保留 24/25/50fps 视频匹配。
        player?.setVideoChangeFrameRateStrategy(
            if (danmakuEnabled) {
                C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF
            } else {
                C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS
            }
        )
    }

    // Keep the mapping from setting panel state to danmaku config in one place.
    private fun buildDanmakuSettingsSnapshot(): DanmakuSettingsSnapshot {
        return DanmakuSettingsSnapshot(
            enabled = settingView?.getDmEnable() ?: true,
            alpha = settingView?.getDmAlpha() ?: 1f,
            textSize = settingView?.getDmTextScaleParam() ?: 40,
            speed = settingView?.getDmSpeedParam() ?: 4,
            screenArea = settingView?.getScreenPartParam() ?: 3,
            allowTop = settingView?.getDmAllowTop() ?: true,
            allowBottom = settingView?.getDmAllowBottom() ?: true,
            smartFilterLevel = getDanmakuSmartFilterLevel(),
            mergeDuplicate = settingView?.getDmMergeDuplicate() ?: true,
            trackSpacing = settingView?.getDmTrackSpacingPref() ?: "standard"
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
                uiCoordinator?.transition(com.mytvb.feature.player.UiEvent.SeekTypeChanged(
                    com.mytvb.feature.player.SeekType.SWIPE
                ))
                // controller 懒加载：从未呼出过控制栏的会话（外链 PlayerActivity 宿主 autoShow=false，
                // 或主宿主起播即滑）controller 为 null，瞬时进度条/浮窗会被 controller?. 全部吞掉
                (controller ?: ensureController("swipe_seek"))?.enterSeekProgressOnly()
                parent?.requestDisallowInterceptTouchEvent(true)
                val deltaMs = computeSwipeSeekDeltaMs(deltaX, currentPlayer.duration)
                renderSwipeSeekPreview(
                    targetPositionMs = (swipeSeekStartPositionMs + deltaMs)
                        .coerceIn(0L, currentPlayer.duration),
                    durationMs = currentPlayer.duration
                )
            }
            val durationMs = currentPlayer.duration.coerceAtLeast(0L)
            val deltaMs = computeSwipeSeekDeltaMs(deltaX, durationMs)
            val targetPositionMs =
                (swipeSeekStartPositionMs + deltaMs)
                    .coerceIn(0L, durationMs)
            swipeSeekTargetPositionMs = targetPositionMs
            renderSwipeSeekPreview(
                targetPositionMs = targetPositionMs,
                durationMs = durationMs
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
                // blbl 节奏：松手后进度条+时间停留 ~1s 再动画淡出，浮窗 500ms 先走
                controller?.finishSeekPreviewProgressOnly()
                timelineThumbPreview?.hideAfter(500L)
                uiCoordinator?.transition(com.mytvb.feature.player.UiEvent.SeekFinished)
                seekOverlayView?.finishSwipeSeek()
                isSwipeSeeking = false
                swipeSeekUsesControllerPreview = false
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                if (!isSwipeSeeking) {
                    return false
                }
                controller?.finishSeekPreviewProgressOnly()
                hideTimelineThumbPreviewNow()
                uiCoordinator?.clearSeekPreview()
                uiCoordinator?.transition(com.mytvb.feature.player.UiEvent.SeekCancelled)
                seekOverlayView?.cancelSwipeSeek()
                isSwipeSeeking = false
                swipeSeekUsesControllerPreview = false
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return false
    }


    private fun restoreControllerAfterGesture(showIndefinitely: Boolean = false) {
        if (showIndefinitely) {
            showController(true)
        } else {
            maybeShowController(true)
        }
        controller?.resetHideCallbacks()
    }

    /** 触摸滑动预览（blbl 形态）：瞬时进度条+右下时间由 beginSeekPreview 驱动，缩略图浮窗跟随进度条滑块。 */
    private fun renderSwipeSeekPreview(targetPositionMs: Long, durationMs: Long) {
        controller?.beginSeekPreview(targetPositionMs)
        showTimelineThumbPreview(targetPositionMs, durationMs)
    }

    /**
     * 触摸滑动快进映射（保留 MyBLBL 原有手感，用户指定不采用 blbl 的 18% 方案）：
     * 滑满一整屏宽 = 100% 时长，滑动比例即时长比例。
     */
    private fun computeSwipeSeekDeltaMs(deltaX: Float, durationMs: Long): Long {
        val widthPx = width.coerceAtLeast(1).toFloat()
        val offsetRatio = (deltaX / widthPx).coerceIn(-1f, 1f)
        return (durationMs * offsetRatio).toLong()
    }

}


