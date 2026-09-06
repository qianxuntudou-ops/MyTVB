package com.tutu.myblbl.feature.player

import com.tutu.myblbl.feature.player.PlayerScreenLogic
import com.tutu.myblbl.feature.player.PlayerContentGuards
import com.tutu.myblbl.core.common.format.NumberUtils
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.os.SystemClock
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.tutu.myblbl.feature.player.sponsor.SponsorProgressMarkerView
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import com.tutu.myblbl.core.common.ext.toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.RecyclerView
import com.tutu.myblbl.R
import com.tutu.myblbl.databinding.FragmentVideoPlayerBinding
import com.tutu.myblbl.event.AppEventHub
import com.tutu.myblbl.model.video.VideoModel
import com.tutu.myblbl.model.video.detail.VideoDetailModel
import com.tutu.myblbl.model.video.quality.AudioQuality
import com.tutu.myblbl.model.video.quality.VideoCodecEnum
import com.tutu.myblbl.model.video.quality.VideoQuality
import com.tutu.myblbl.ui.activity.GaiaVgateActivity
import com.tutu.myblbl.ui.activity.MainActivity
import com.tutu.myblbl.ui.activity.PlayerActivity
import com.tutu.myblbl.ui.adapter.VideoAdapter
import com.tutu.myblbl.feature.player.interaction.InteractionOverlayView
import com.tutu.myblbl.feature.player.view.MyPlayerView
import com.tutu.myblbl.feature.player.view.OnPlayerSettingChange
import com.tutu.myblbl.feature.player.view.OnVideoSettingChangeListener
import com.tutu.myblbl.feature.player.view.DouyinModeKeyListener
import com.tutu.myblbl.feature.player.view.DouyinModePreview
import com.tutu.myblbl.core.common.log.AppLog
import com.tutu.myblbl.core.lifecycle.AppBackgroundMonitor
import com.tutu.myblbl.core.common.content.ContentFilter
import com.tutu.myblbl.feature.player.settings.PlayerSettings
import com.tutu.myblbl.feature.player.settings.PlayerSettingsStore
import com.tutu.myblbl.feature.player.settings.AfterPlayMode
import com.tutu.myblbl.core.common.time.TimeUtils
import com.tutu.myblbl.core.ui.navigation.navigateBackFromUi
import com.tutu.myblbl.core.ui.system.ViewUtils
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import androidx.media3.common.Format
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.common.C

private const val RISK_CONTROL_USER_HINT = "账号被风控了，请到设置中完成验证"
private val riskControlUserHintShown = AtomicBoolean(false)

@UnstableApi
class VideoPlayerFragment : Fragment() {

    companion object {
        private const val TAG = "VideoPlayerFragment"
        const val ARG_AID = "aid"
        const val ARG_BVID = "bvid"
        const val ARG_CID = "cid"
        const val ARG_EP_ID = "ep_id"
        const val ARG_SEASON_ID = "season_id"
        const val ARG_SEEK_POSITION_MS = "seek_position_ms"
        const val ARG_START_EPISODE = "start_episode"
        const val ARG_IS_STEINS_GATE = "is_steins_gate"

        fun newInstance(
            aid: Long = 0L,
            bvid: String = "",
            cid: Long = 0L,
            epId: Long = 0L,
            seasonId: Long = 0L,
            seekPositionMs: Long = 0L,
            startEpisodeIndex: Int = -1,
            isSteinsGate: Boolean = false
        ): VideoPlayerFragment {
            return VideoPlayerFragment().apply {
                arguments = Bundle().apply {
                    putLong(ARG_AID, aid)
                    putString(ARG_BVID, bvid)
                    putLong(ARG_CID, cid)
                    putLong(ARG_EP_ID, epId)
                    putLong(ARG_SEASON_ID, seasonId)
                    putLong(ARG_SEEK_POSITION_MS, seekPositionMs)
                    putInt(ARG_START_EPISODE, startEpisodeIndex)
                    putBoolean(ARG_IS_STEINS_GATE, isSteinsGate)
                }
            }
        }
    }

    private var _binding: FragmentVideoPlayerBinding? = null
    private val binding get() = _binding!!

    private val appEventHub: AppEventHub by inject()
    private val viewModel: VideoPlayerViewModel by viewModel()

    private var player: ExoPlayer? = null
    private val uiCoordinator = PlaybackUiCoordinator()
    private val overlayCoordinator = PlayerOverlayCoordinator()
    private var backgroundListener: AppBackgroundMonitor.BackgroundStateListener? = null

    private lateinit var playerView: MyPlayerView
    private lateinit var bottomProgressBar: SponsorProgressMarkerView
    private lateinit var textClock: TextClock
    private lateinit var textSubtitle: TextView
    private lateinit var textDebug: TextView
    private lateinit var viewNext: View
    private lateinit var viewRelated: View
    private lateinit var recyclerViewRelated: RecyclerView
    private lateinit var textMoreTitle: TextView
    private lateinit var buttonCloseRelated: View
    private lateinit var imageNext: AppCompatImageView
    private lateinit var textNext: TextView
    private lateinit var countdownView: com.tutu.myblbl.feature.player.view.CountdownView
    private lateinit var interactionView: InteractionOverlayView

    private lateinit var relatedAdapter: VideoAdapter
    private lateinit var autoPlayController: VideoPlayerAutoPlayController
    private lateinit var overlayUiController: VideoPlayerOverlayController
    private lateinit var resumeHintController: VideoPlayerResumeHintController

    private var latestErrorMessage: String? = null
    private var latestLoadingState: Boolean = false
    private var latestVideoInfo: VideoDetailModel? = null
    private lateinit var playerSettings: PlayerSettings
    private var latestControllerVisibility: Int = View.GONE
    private var latestPlaybackPositionMs: Long = 0L
    private var latestPlaybackDurationMs: Long = 0L
    private lateinit var slimTimelineRenderer: SlimTimelineRenderer
    private val sessionCoordinator = PlayerSessionCoordinator()
    private val douyinModeManager: com.tutu.myblbl.feature.player.douyin.DouyinModeManager by inject()
    private var douyinSourceAid: Long = 0L
    private var douyinSourceKey: String = ""
    private var douyinInitializing: Boolean = false
    private var douyinTransitionRunning: Boolean = false
    private var douyinInternalNavigationKey: String = ""
    private var douyinPendingNextAfterInit: Boolean = false
    private var resumePlaybackWhenStarted: Boolean = false
    private var startupTrace: StartupTrace? = null
    private var startupTraceSequence: Int = 0
    private var suppressPlaybackEnvironmentSync: Boolean = false
    private var lastKeepScreenOnState: Boolean? = null

    private val gaiaVgateLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == androidx.appcompat.app.AppCompatActivity.RESULT_OK) {
            val gaiaVtoken = result.data?.getStringExtra(GaiaVgateActivity.EXTRA_GAIA_VTOKEN)
            if (!gaiaVtoken.isNullOrBlank()) {
                viewModel.onGaiaVgateResult(gaiaVtoken)
            }
        }
    }

    private val resumePlaybackRunnable = Runnable {
        resumePlaybackIfNeeded(reason = "delayed_resume")
    }
    private val progressCoordinator = VideoPlayerProgressCoordinator(
        playerProvider = { player },
        publishProgressStateProvider = { _binding != null },
        onProgressPublished = { positionMs, durationMs, publishProgressState ->
            viewModel.updatePlaybackPosition(positionMs, durationMs, publishProgressState)
            if (publishProgressState && _binding != null) {
                latestPlaybackPositionMs = positionMs.coerceAtLeast(0L)
                latestPlaybackDurationMs = durationMs.coerceAtLeast(0L)
                renderBottomProgressBar()
            }
        },
        onPlaybackPositionChanged = { positionMs ->
            playerView.syncDanmakuPosition(positionMs)
            interactionView.onPositionUpdate(positionMs)
            renderDebugState()
        },
        onPlaybackStalled = { positionMs, stalledMs ->
            recoverFromPlaybackStall(positionMs, stalledMs)
        },
        onHeartbeatTick = {
            viewModel.reportPlaybackHeartbeat()
        }
    )

    private fun cancelResume(): Boolean = resumeHintController.cancelResume()

    private val playerPerfListener = object : AnalyticsListener {
        override fun onLoadStarted(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData
        ) {
            val trace = playerPerfTrace
            AppLog.i("VideoPlayerViewModel", "PLAYER_PERF onLoadStarted dataType=${mediaLoadData.dataType} trace=$trace uri=${loadEventInfo.uri}")
            if (trace == null || trace.firstLoadStartedMs != 0L) return
            trace.firstLoadStartedMs = System.currentTimeMillis()
            AppLog.i("VideoPlayerViewModel", "PLAYER_PERF [A] load started (CDN) +${trace.firstLoadStartedMs - trace.prepareMs}ms")
        }

        override fun onLoadCompleted(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData
        ) {
            val trace = playerPerfTrace
            if (trace == null || trace.firstLoadCompletedMs != 0L) return
            trace.firstLoadCompletedMs = System.currentTimeMillis()
            AppLog.i("VideoPlayerViewModel", "PLAYER_PERF [B] load completed (buffer) +${trace.firstLoadCompletedMs - trace.prepareMs}ms bytes=${loadEventInfo.bytesLoaded}")
        }

        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            elapsedInitializationMs: Long
        ) {
            val trace = playerPerfTrace ?: return
            val now = System.currentTimeMillis()
            trace.videoDecoderInitMs = now
            // 解码器分类：与 VideoCodecSupport.isHardwareDecoder 同款前缀逻辑，用于诊断是否落到软解。
            // google/android 前缀为软解器，其余 c2./OMX. 视为硬解。
            val hw = isHardwareDecoderName(decoderName)
            AppLog.i(
                "VideoPlayerViewModel",
                "PLAYER_PERF [C] video decoder init ($decoderName hw=$hw) hw_init=${elapsedInitializationMs}ms +${now - trace.prepareMs}ms"
            )
            if (!hw) {
                AppLog.w("PlaybackPerf", "video running on SOFTWARE decoder=$decoderName (may cause stutter)")
            }
        }

        override fun onAudioDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            elapsedInitializationMs: Long
        ) {
            val trace = playerPerfTrace ?: return
            val now = System.currentTimeMillis()
            trace.audioDecoderInitMs = now
            AppLog.i("VideoPlayerViewModel", "PLAYER_PERF [C] audio decoder init ($decoderName) hw_init=${elapsedInitializationMs}ms +${now - trace.prepareMs}ms")
        }

        // —— 帧率/流畅度可观测性（Media3 帧率匹配黑盒化问题的诊断入口）——

        override fun onVideoInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?
        ) {
            val fps = format.frameRate
            // Format.NO_VALUE（-1）对 float 字段同样适用；frameRate 无效时为 NO_VALUE。
            val hasFps = fps > 1f
            AppLog.i(
                "PlaybackPerf",
                "video_format ${format.width}x${format.height} fps=${if (hasFps) fps.toInt() else "?"} " +
                    "mime=${format.sampleMimeType ?: "?"} codec=${format.codecs ?: "?"}"
            )
            // 关键诊断：流无帧率元数据 → Media3 不会自动调 Surface.setFrameRate()，
            // 帧率匹配无法生效（常见于 b 站部分老番/裸流）。当前不做盲目 setFrameRate（无真实帧率依据，
            // 乱设会强制刷新率切换反而更糟），仅告警暴露问题。后续可从 DashVideo.realFrameRate 反推兜底。
            if (!hasFps) {
                AppLog.w("PlaybackPerf", "video stream has NO frame rate metadata, Surface.setFrameRate skipped by Media3")
            }
            // 无缝清晰度切换：从多清晰度 MPD 的 Representation id 解析出真实生效的 (qn, codec)，
            // 回读给 VM 纠正 UI 选择态与心跳上报档位；非无缝源 id 解析为 null，无副作用。
            parseVideoRepresentationId(format.id)?.let { (qn, codecid) ->
                viewModel.onSeamlessVideoTrackChanged(qn, codecid)
            }
        }

        override fun onDroppedVideoFrames(
            eventTime: AnalyticsListener.EventTime,
            droppedFrames: Int,
            elapsedMs: Long
        ) {
            droppedVideoFramesAccumulator += droppedFrames
            val now = SystemClock.elapsedRealtime()
            // 节流：每 2 秒汇总一次，避免逐帧刷屏（参考 maybeLogMaskGeometry 的 lastXxxLogMs 模式）。
            if (now - lastDroppedFramesLogMs >= 2_000L && droppedVideoFramesAccumulator > 0) {
                AppLog.w(
                    "PlaybackPerf",
                    "dropped_frames=${droppedVideoFramesAccumulator} over ${now - lastDroppedFramesLogMs}ms"
                )
                droppedVideoFramesAccumulator = 0
                lastDroppedFramesLogMs = now
            }
        }

        override fun onVideoFrameProcessingOffset(
            eventTime: AnalyticsListener.EventTime,
            totalProcessingOffsetUs: Long,
            frameCount: Int
        ) {
            if (frameCount <= 0) return
            // 平均每帧处理偏移：正值=帧晚到（解码/渲染跟不上），是最强的"帧率匹配/流畅度健康度"信号。
            // 超过约一帧时长（按 60fps 取 16ms 阈值）视为明显异常。
            val avgOffsetMs = totalProcessingOffsetUs / 1000.0 / frameCount
            if (avgOffsetMs > 16.0) {
                AppLog.w(
                    "PlaybackPerf",
                    "frame_processing_offset avg=${String.format("%.1f", avgOffsetMs)}ms frames=$frameCount (late, possible judder)"
                )
            }
        }
    }

    /**
     * 按 MediaCodec 名称前缀判断是否硬件解码器。
     * 与 [com.tutu.myblbl.core.common.media.VideoCodecSupport.isHardwareDecoder] 同款规则，
     * 因后者为 private，此处内联复刻用于运行时解码器分类诊断。
     */
    private fun isHardwareDecoderName(name: String): Boolean {
        val n = name.lowercase()
        return when {
            n.startsWith("omx.google.") -> false
            n.startsWith("c2.android.") -> false
            n.startsWith("c2.google.") -> false
            n.contains(".sw.") -> false
            n.contains("software") -> false
            n.contains("ffmpeg") -> false
            else -> n.startsWith("omx.") || n.startsWith("c2.")
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            val isSeeking = playerView.seekSession?.isActive() == true
            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    viewModel.setLoading(true)
                }
                Player.STATE_READY -> {
                    startupTrace
                        ?.takeIf { !it.readyLogged }
                        ?.also {
                            it.readyLogged = true
                    }
                    viewModel.setLoading(false)
                    hideNextPreview()
                }
                Player.STATE_ENDED -> {
                    viewModel.setLoading(false)
                    playerView.stopDanmaku()
                    handlePlaybackEnded()
                }
                Player.STATE_IDLE -> {
                    viewModel.setLoading(false)
                }
            }
            syncPlaybackEnvironment()
        }

        override fun onPlayerError(error: PlaybackException) {
            startupTrace = null
            viewModel.setLoading(false)
            viewModel.handlePlaybackError(error, player?.currentPosition ?: 0L)
            AppLog.e(TAG, "player error: ${error.message}", error)
            playerView.pauseDanmaku()
            syncPlaybackEnvironment()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val isSeeking = playerView.seekSession?.isActive() == true
            if (isPlaying && !isSeeking) {
                playerView.resumeDanmaku()
                progressCoordinator.restart()
                // 青少年模式：累计观看时长埋点（仅时间限制启用时计时）
                com.tutu.myblbl.core.common.content.TeenModeTimer.onPlayStart()
            } else if (!isPlaying) {
                if (player?.playbackState != Player.STATE_BUFFERING) {
                    playerView.pauseDanmaku()
                }
                progressCoordinator.stop()
                progressCoordinator.syncNow(publishProgressState = true)
                // 青少年模式：结算本段观看时长，达上限则触发休息
                com.tutu.myblbl.core.common.content.TeenModeTimer.onPlayStop()
            }
            syncPlaybackEnvironment()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            resumePlaybackWhenStarted = playWhenReady
            syncPlaybackEnvironment()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentVideoPlayerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initViews(view)
        playerSettings = PlayerSettingsStore.load(requireContext())
        consumeLaunchContext()
        setupOverlayController()
        setupPlayer()
        setupObservers()
        view.post {
            if (_binding == null) return@post
            setupAdapters()
            setupBackHandler()
        }

        resolveLaunchContext()?.let { launchContext ->
            if (
                launchContext.aid > 0L ||
                launchContext.bvid.isNotBlank() ||
                launchContext.epId > 0L ||
                launchContext.seasonId > 0L
            ) {
                viewModel.loadVideoInfo(
                    aid = launchContext.aid,
                    bvid = launchContext.bvid,
                    cid = launchContext.cid,
                    seasonId = launchContext.seasonId,
                    epId = launchContext.epId,
                    seekPositionMs = launchContext.seekPositionMs,
                    startEpisodeIndex = launchContext.startEpisodeIndex,
                    isSteinsGate = arguments?.getBoolean(ARG_IS_STEINS_GATE, false) == true
                )
            } else {
                val snapshot = viewModel.consumeSavedSnapshot()
                if (snapshot != null) {
                    viewModel.loadVideoInfo(
                        aid = snapshot.aid,
                        bvid = snapshot.bvid,
                        cid = snapshot.cid,
                        seasonId = snapshot.seasonId,
                        epId = snapshot.epId,
                        seekPositionMs = snapshot.seekPositionMs,
                        startEpisodeIndex = snapshot.episodeIndex,
                        preferredQualityId = snapshot.qualityId,
                        preferredAudioQualityId = snapshot.audioQualityId
                    )
                }
            }
        }
    }

    private fun initViews(rootView: View) {
        playerView = binding.playerView
        playerView.setUiCoordinator(uiCoordinator)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            playerView.defaultFocusHighlightEnabled = false
        }
        bottomProgressBar = binding.bottomProgressBar
        slimTimelineRenderer = SlimTimelineRenderer(bottomProgressBar)
        textClock = binding.textClock
        textSubtitle = binding.textSubtitle
        textDebug = binding.textDebug
        viewNext = binding.viewNext
        viewRelated = binding.viewRelated
        recyclerViewRelated = binding.recyclerViewRelated
        textMoreTitle = rootView.findViewById(R.id.title_more)
        buttonCloseRelated = rootView.findViewById(R.id.button_close_related)
        imageNext = rootView.findViewById(R.id.imageView_next)
        textNext = rootView.findViewById(R.id.text_next)
        countdownView = rootView.findViewById(R.id.countdown_view)
        interactionView = playerView.findViewById(R.id.interaction_view)

        viewRelated.visibility = View.GONE
        viewNext.visibility = View.GONE
        textSubtitle.visibility = View.GONE
        interactionView.visibility = View.GONE
        interactionView.setEngine(viewModel.getInteractionEngine())
        interactionView.setCallback(object : InteractionOverlayView.Callback {
            override fun onPauseVideo() {
                player?.pause()
                playerView.pauseDanmaku()
            }

            override fun onResumeVideo() {
                player?.play()
                playerView.resumeDanmaku()
            }

            override fun onHidePlayerUI() {
                playerView.hideController()
                playerView.removeControllerHideCallbacks()
            }

            override fun onShowPlayerUI() {
                playerView.showController()
                playerView.resumeDanmaku()
            }

            override fun onJumpToChoice(targetEdgeId: Long, targetCid: Long) {
                viewModel.playInteractionChoice(targetCid, targetEdgeId)
            }

            override fun onGoBackToNode(edgeId: Long, cid: Long) {
                viewModel.playInteractionChoice(cid, edgeId)
            }

            override fun onGetVideoSurfaceRect(): android.graphics.Rect? {
                val surface = playerView.getVideoSurfaceView() ?: return null
                val loc = IntArray(2)
                surface.getLocationInWindow(loc)
                return android.graphics.Rect(loc[0], loc[1], loc[0] + surface.width, loc[1] + surface.height)
            }
        })

        buttonCloseRelated.setOnClickListener {
            hideContentPanel()
        }
    }

    private fun setupAdapters() {
        sessionCoordinator.setContentGate(
            isVideoAllowed = { video -> !isVideoBlockedByMinorProtection(video) },
            isEpisodeAllowed = { episode -> !isEpisodeBlockedByMinorProtection(episode) }
        )
        relatedAdapter = VideoAdapter(
            itemWidthPx = (resources.displayMetrics.widthPixels / 5).coerceAtLeast(1)
        )
        relatedAdapter.setOnItemClickListener { _, item ->
            if (isVideoBlockedByMinorProtection(item)) {
                requireContext().toast("青少年模式已拦截该视频")
            } else {
                playerView.hideController()
                hideContentPanel()
                hideNextPreview()
                sessionCoordinator.replacePlayQueue(PlayerActivity.buildPlayQueue(relatedAdapter.getItemsSnapshot(), item))
                sessionCoordinator.updateCurrentVideo(item)
                viewModel.playRelatedVideo(item)
            }
        }
    }

    private fun setupOverlayController() {
        autoPlayController = VideoPlayerAutoPlayController(
            activity = requireActivity() as androidx.appcompat.app.AppCompatActivity,
            viewNext = viewNext,
            imageNext = imageNext,
            textNext = textNext,
            countdownView = countdownView,
            canExecutePendingAction = { _binding != null && player?.playbackState == Player.STATE_ENDED },
            onExecutePendingSession = { sessionId -> viewModel.playContinuation(sessionId) },
            onPendingActionCleared = { viewModel.clearPendingContinuation() }
        )
        overlayUiController = VideoPlayerOverlayController(
            activity = requireActivity() as androidx.appcompat.app.AppCompatActivity,
            playerView = playerView,
            overlayCoordinator = overlayCoordinator,
            uiCoordinator = uiCoordinator,
            sessionCoordinator = sessionCoordinator,
            latestVideoInfoProvider = { latestVideoInfo },
            relatedAdapter = relatedAdapter,
            viewRelated = viewRelated,
            dimBackground = binding.dimBackground,
            recyclerViewRelated = recyclerViewRelated,
            textMoreTitle = textMoreTitle,
            onPlayEpisode = { index ->
                if (!sessionCoordinator.canPlayEpisode(index)) {
                    requireContext().toast("青少年模式已拦截该视频")
                } else {
                    playerView.hideController()
                    viewModel.playEpisode(index)
                }
            },
            onPlayRelatedVideo = { video, playQueue ->
                if (isVideoBlockedByMinorProtection(video)) {
                    requireContext().toast("青少年模式已拦截该视频")
                } else {
                    playerView.hideController()
                    sessionCoordinator.replacePlayQueue(playQueue)
                    sessionCoordinator.updateCurrentVideo(video)
                    viewModel.playRelatedVideo(video)
                }
            },
            onOpenFragmentFromHost = ::openFragmentFromPlayerHost,
            onHideNextPreview = { autoPlayController.hideNextPreview() },
            isViewActive = { _binding != null }
        )
        resumeHintController = VideoPlayerResumeHintController(
            activity = requireActivity() as androidx.appcompat.app.AppCompatActivity,
            playerProvider = { player },
            onCancelResume = { viewModel.cancelResumeProgress() },
            onClearResumeHint = { viewModel.clearResumeHint() },
            onShowResumeHint = { text -> playerView.showResumeHint(text) },
            onHideResumeHint = { playerView.hideResumeHint() }
        )
    }

    private fun setupPlayer() {
        val startMs = SystemClock.elapsedRealtime()
        AppLog.i("VideoPlayerViewModel", "PLAYER_PERF setupPlayer() called")
        player = PlayerInstancePool.acquire(requireContext()).also {
            it.playWhenReady = false
            if (::playerSettings.isInitialized) {
                it.playbackParameters = PlaybackParameters(playerSettings.defaultPlaybackSpeed)
            }
            it.addListener(playerListener)
            it.addAnalyticsListener(playerPerfListener)
        }
        playerView.setPlayer(player)
        playerView.setRenderEventListener(object : MyPlayerView.RenderEventListener {
            override fun onRenderedFirstFrame() {
                val trace = playerPerfTrace
                if (trace != null) {
                    trace.firstFrameMs = System.currentTimeMillis()
                    val prepareToFrame = trace.firstFrameMs - trace.prepareMs
                    val loadStartDelta = if (trace.firstLoadStartedMs > 0) trace.firstLoadStartedMs - trace.prepareMs else -1
                    val loadDoneDelta = if (trace.firstLoadCompletedMs > 0) trace.firstLoadCompletedMs - trace.prepareMs else -1
                    val videoDecoderDelta = if (trace.videoDecoderInitMs > 0) trace.videoDecoderInitMs - trace.prepareMs else -1
                    AppLog.i("VideoPlayerViewModel", "PLAYER_PERF first-frame breakdown: total=${prepareToFrame}ms " +
                        "cdn_connect=${loadStartDelta}ms buffer=${if (loadStartDelta > 0 && loadDoneDelta > 0) loadDoneDelta - loadStartDelta else -1}ms " +
                        "decoder_init=${if (loadDoneDelta > 0 && videoDecoderDelta > 0) videoDecoderDelta - loadDoneDelta else -1}ms " +
                        "decode_render=${if (videoDecoderDelta > 0) trace.firstFrameMs - trace.videoDecoderInitMs else -1}ms")
                    playerPerfTrace = null
                }
                if (!activeStartupFirstFrameLogged) {
                    activeStartupFirstFrameLogged = true
                    PlaybackStartupTrace.log(
                        traceId = activeStartupTraceId,
                        startElapsedMs = activeStartupTraceStartElapsedMs,
                        step = "first_frame"
                    )
                }
                viewModel.onPlaybackFirstFrame()
                startupTrace
                    ?.takeIf { !it.firstFrameLogged }
                    ?.also {
                        it.firstFrameLogged = true
                    }
            }
        })
        playerView.setControllerVisibilityListener(object : MyPlayerView.ControllerVisibilityListener {
            override fun onVisibilityChanged(visibility: Int) {
                renderControllerChrome(visibility)
                if (visibility != View.VISIBLE) {
                    progressCoordinator.syncNow(publishProgressState = true)
                }
            }
        })
        playerView.setControllerAutoShow(false)
        playerView.hideController()
        playerView.douyinModeKeyListener = object : DouyinModeKeyListener {
            override fun isDouyinModeActive(): Boolean {
                val currentVideo = sessionCoordinator.getCurrentVideo()
                return douyinModeManager.isApplicable(currentVideo)
            }
            override fun onDouyinNavigateNext(): Boolean = handleDouyinNext()
            override fun onDouyinNavigatePrevious(): Boolean = handleDouyinPrevious()
            override fun peekDouyinNext(): DouyinModePreview? {
                return douyinModeManager.peekNext()?.toDouyinPreview()
                    ?: if (!douyinModeManager.hasList()) {
                        DouyinModePreview(title = "加载推荐中...", coverUrl = "")
                    } else {
                        null
                    }
            }
            override fun peekDouyinPrevious(): DouyinModePreview? = douyinModeManager.peekPrevious()?.toDouyinPreview()
        }
        playerView.onResumeProgressCancelled = {
            cancelResume()
        }
        playerView.seekPreviewUpdateListener = object : MyPlayerView.SeekPreviewUpdateListener {
            override fun onSeekPreviewUpdated() {
                renderBottomProgressBar()
            }
        }
        playerView.seekSession = SeekSession(
            coordinator = uiCoordinator,
            playerProvider = { player },
            seekPreviewRenderer = { targetMs, durationMs ->
                val controllerHandling = playerView.getController()?.isVisible() == true
                if (controllerHandling) {
                    playerView.getController()?.beginSeekPreview(targetMs)
                }
                // 控制器进度条已处理 preview 时，不更新细进度条，避免 hide/show 互相打架
                if (!controllerHandling && ::slimTimelineRenderer.isInitialized) {
                    slimTimelineRenderer.showPreview(targetMs, durationMs)
                }
            },
            danmakuSync = { positionMs -> playerView.syncDanmakuPosition(positionMs, forceSeek = true) },
            holdSeekOverlayRenderer = { targetMs, durationMs, deltaMs ->
                playerView.showHoldSeekOverlay(targetMs, durationMs, deltaMs)
            }
        )
        playerView.showSettingButton(false)
        playerView.showHideNextPrevious(false)
        playerView.showHideFfRe(playerSettings.showRewindFastForward)
        playerView.showHideActionButton(false)
        playerView.showHideEpisodeButton(false)
        playerView.showHideRelatedButton(false)
        playerView.showHideDmSwitchButton(false)
        playerView.showHideLiveSettingButton(false)
        playerView.showHideSubtitleButton(false)
        playerView.setShowHideOwnerInfo(false)
        playerView.setRepeatMode(Player.REPEAT_MODE_OFF)
        applyPlayerSettings(playerSettings)
        syncPlaybackEnvironment()
        playerView.setOnPlayerSettingChange(object : OnPlayerSettingChange {
            override fun onVideoQualityChange(quality: VideoQuality) {
                val playbackSnapshot = capturePlaybackSnapshot()
                viewModel.selectVideoQuality(
                    quality = quality,
                    currentPositionMs = playbackSnapshot.first,
                    playWhenReady = playbackSnapshot.second
                )
                playerView.showHideSettingView(false)
            }

            override fun onAudioQualityChange(quality: AudioQuality) {
                val playbackSnapshot = capturePlaybackSnapshot()
                viewModel.selectAudioQuality(
                    quality = quality,
                    currentPositionMs = playbackSnapshot.first,
                    playWhenReady = playbackSnapshot.second
                )
                playerView.showHideSettingView(false)
            }

            override fun onPlaybackSpeedChange(speed: Float) {
                playerView.setPlaySpeed(speed)
            }

            override fun onSubtitleChange(position: Int) {
                viewModel.selectSubtitle(position)
                playerView.showHideSettingView(false)
            }

            override fun onVideoCodecChange(codec: VideoCodecEnum) {
                val playbackSnapshot = capturePlaybackSnapshot()
                viewModel.selectVideoCodec(
                    codec = codec,
                    currentPositionMs = playbackSnapshot.first,
                    playWhenReady = playbackSnapshot.second
                )
                playerView.showHideSettingView(false)
            }

            override fun onAspectRatioChange(ratio: Int) {
            }

            override fun onScreenMirrorChange(enabled: Boolean) {
                playerView.setMirrorEnabled(enabled)
            }

            override fun onAfterPlayModeChange(mode: AfterPlayMode) {
                playerSettings = playerSettings.copy(afterPlayMode = mode)
                playerView.setAfterPlayMode(mode)
                PlayerSettingsStore.saveAfterPlayMode(mode)
            }
        })
        playerView.setOnVideoSettingChangeListener(object : OnVideoSettingChangeListener {
            override fun onPrevious() {
                viewModel.playPrevious()
            }

            override fun onNext() {
                viewModel.playNext()
            }

            override fun onClose() {
                exitPlayerHost()
            }

            override fun onChooseEpisode() {
                showChooseEpisodeDialog()
            }

            override fun onRelated() {
                showRelatedPanel()
            }

            override fun onUpInfo() {
                showOwnerDetailDialog()
            }

            override fun onMore() {
                showPlayerActionDialog()
            }

            override fun onVideoInfo() {
                showVideoInfoDialog()
            }

            override fun onSubtitle() {
                if (viewModel.subtitles.value.orEmpty().isNotEmpty()) {
                    playerView.showSubtitleSettingView()
                }
            }

            override fun onRepeat() {
                val currentPlayer = player ?: return
                currentPlayer.repeatMode = if (currentPlayer.repeatMode == Player.REPEAT_MODE_ONE) {
                    Player.REPEAT_MODE_OFF
                } else {
                    Player.REPEAT_MODE_ONE
                }
                playerView.setRepeatMode(currentPlayer.repeatMode)
                Toast.makeText(
                    requireContext(),
                    if (currentPlayer.repeatMode == Player.REPEAT_MODE_ONE) "单集循环" else "顺序播放",
                    Toast.LENGTH_SHORT
                ).show()
            }

            override fun onDmEnableChange(enabled: Boolean) {
                playerView.setDanmakuEnabled(enabled)
            }
        })
        playerView.onUserSeekListener = { positionMs ->
            viewModel.sponsorUserSeek(positionMs)
        }

        backgroundListener = object : AppBackgroundMonitor.BackgroundStateListener {
            override fun onAppBackgroundStateChanged(isInBackground: Boolean) {
                val p = player ?: return
                if (isInBackground) {
                    p.trackSelectionParameters = p.trackSelectionParameters
                        .buildUpon()
                        .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, true)
                        .build()
                } else {
                    val pos = p.currentPosition
                    p.trackSelectionParameters = p.trackSelectionParameters
                        .buildUpon()
                        .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, false)
                        .build()
                    if (p.playbackState == Player.STATE_READY || p.playbackState == Player.STATE_BUFFERING) {
                        p.seekTo(pos)
                    }
                }
            }
        }.also(AppBackgroundMonitor::addListener)
        renderControllerChrome(View.GONE)
    }

    private fun setupBackHandler() {
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (cancelResume()) {
                        return
                    }
                    uiCoordinator.handleBackPress(
                        isSettingShowing = playerView.isSettingViewShowing(),
                        hideSetting = { playerView.showHideSettingView(false) },
                        isControllerFullyVisible = playerView.isControllerFullyVisible(),
                        hideController = { playerView.hideController() },
                        hidePanel = { hideContentPanel() },
                        exitPlayer = {
                            isEnabled = false
                            exitPlayerHost()
                            isEnabled = true
                        },
                    )
                }
            }
        )
    }

    private fun resolvePlaybackStartSeekPosition(
        playbackRequest: VideoPlayerViewModel.PlaybackRequest,
        currentPlayer: Player
    ): Long {
        return PlayerScreenLogic.resolvePlaybackStartSeekPosition(playbackRequest, currentPlayer, TAG)
    }

    private fun setupObservers() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.playbackRequest.collect { request ->
                val currentPlayer = player ?: return@collect
                val playbackRequest = request ?: return@collect
                viewModel.setErrorMessage(null)
                resumePlaybackWhenStarted = playbackRequest.playWhenReady

                if (playbackRequest.replaceInPlace) {
                    playerView.showController()
                    playerView.removeControllerHideCallbacks()
                }
                progressCoordinator.reset()
                val startSeekPositionMs = resolvePlaybackStartSeekPosition(playbackRequest, currentPlayer)
                if (!playbackRequest.replaceInPlace) {
                    playerView.getController()?.hideImmediately()
                    playerView.prepareForPlaybackTransition(startSeekPositionMs)
                    viewModel.resetPlaybackProgress()
                    latestPlaybackPositionMs = 0L
                    latestPlaybackDurationMs = playbackRequest.durationMs.coerceAtLeast(0L)
                    renderBottomProgressBar()
                } else if (playbackRequest.durationMs > 0L) {
                    latestPlaybackDurationMs = playbackRequest.durationMs
                    renderBottomProgressBar()
                }
                startupTrace = StartupTrace(
                    sequence = ++startupTraceSequence,
                    startedAtMs = SystemClock.elapsedRealtime()
                )
                activeStartupTraceId = playbackRequest.startupTraceId
                activeStartupTraceStartElapsedMs = playbackRequest.startupTraceStartElapsedMs
                activeStartupFirstFrameLogged = false
                playerPerfTrace = PlayerPerfTrace(prepareMs = System.currentTimeMillis())
                AppLog.i("VideoPlayerViewModel", "PLAYER_PERF trace created prepareMs=${playerPerfTrace!!.prepareMs}")
                suppressPlaybackEnvironmentSync = true
                try {
                    currentPlayer.playWhenReady = false
                    if (playbackRequest.reuseSameSource) {
                        // 暖路径：MediaSource 仍挂载在 player 上，跳过 setMediaSource()
                        PlaybackStartupTrace.log(
                            traceId = activeStartupTraceId,
                            startElapsedMs = activeStartupTraceStartElapsedMs,
                            step = "warm_reuse_prepare",
                            message = "seek=$startSeekPositionMs"
                        )
                        currentPlayer.prepare()
                        currentPlayer.seekTo(startSeekPositionMs)
                        currentPlayer.playWhenReady = playbackRequest.playWhenReady
                    } else {
                        currentPlayer.stop()
                        currentPlayer.setMediaSource(playbackRequest.mediaSource, startSeekPositionMs)
                        // 记录 player 实际挂载的源，供后续 zero_overhead_reuse 查询，
                        // 避免"VM 缓存命中但 player 挂的是别的视频"导致串台。
                        PlayerInstancePool.rememberAttachedSource(playbackRequest.bvid, playbackRequest.cid)
                        PlaybackStartupTrace.log(
                            traceId = activeStartupTraceId,
                            startElapsedMs = activeStartupTraceStartElapsedMs,
                            step = "media_source_set",
                            message = "intentId=${playbackRequest.playbackIntentId} seek=$startSeekPositionMs"
                        )
                        currentPlayer.prepare()
                        PlaybackStartupTrace.log(
                            traceId = activeStartupTraceId,
                            startElapsedMs = activeStartupTraceStartElapsedMs,
                            step = "player_prepare_called",
                            message = "playWhenReady=${playbackRequest.playWhenReady}"
                        )
                        currentPlayer.playWhenReady = playbackRequest.playWhenReady
                    }
                } finally {
                    suppressPlaybackEnvironmentSync = false
                }
                playerView.awaitDouyinPageTransitionFirstFrame()
                playerView.syncDanmakuPosition(startSeekPositionMs, forceSeek = true)
                syncPlaybackEnvironment()
                scheduleDouyinPreloadAfterPlaybackRequest(playbackRequest)
            }
        }

        AppLog.d(TAG, "setupObservers: viewModel=${viewModel.hashCode()}, onDmMaskReady was=${viewModel.onDmMaskReady}")
        viewModel.onDmMaskReady = { maskUrl, cid, fps ->
            AppLog.d(TAG, "onDmMaskReady callback: cid=$cid, fps=$fps, vm=${viewModel.hashCode()}")
            playerView.setDmMaskRepository(viewModel.dmMaskRepository)
            viewLifecycleOwner.lifecycleScope.launch {
                AppLog.d(TAG, "loadDmMask: cid=$cid, fps=$fps")
                val success = playerView.loadDmMask(maskUrl, cid, fps)
                AppLog.d(TAG, "loadDmMask result: $success")
            }
        }
        viewModel.onDmMaskReset = {
            AppLog.d(TAG, "onDmMaskReset callback")
            playerView.releaseDmMask()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.riskControlVVoucher.collect { vVoucher ->
                        if (vVoucher.isNullOrBlank()) return@collect
                        viewModel.consumeRiskControlVVoucher() ?: return@collect
                        AppLog.w(TAG, "risk-control v_voucher received, launching GaiaVgateActivity")
                        val intent = Intent(requireContext(), GaiaVgateActivity::class.java).apply {
                            putExtra(GaiaVgateActivity.EXTRA_V_VOUCHER, vVoucher)
                        }
                        gaiaVgateLauncher.launch(intent)
                    }
                }

                launch {
                    viewModel.riskControlTryLookBypass.collect { bypassed ->
                        if (bypassed != true) return@collect
                        if (riskControlUserHintShown.compareAndSet(false, true)) {
                            Toast.makeText(requireContext(), RISK_CONTROL_USER_HINT, Toast.LENGTH_LONG).show()
                        }
                    }
                }

                launch {
                    viewModel.videoInfo.collect { info ->
                        latestVideoInfo = info
                        sessionCoordinator.updateVideoInfo(info)
                        resetDouyinModeIfNeeded()
                        ensureDouyinQueueStarted()
                        schedulePreloadAndHeaderRefresh()
                        updatePrimaryActionVisibility()
                    }
                }

                launch {
                    viewModel.resumeHint.collect { hint ->
                        resumeHintController.onHintChanged(hint)
                    }
                }

                launch {
                    viewModel.qualities.collect { qualities ->
                        playerView.setQualities(qualities)
                    }
                }

                launch {
                    viewModel.selectedQuality.collect { quality ->
                        quality?.let(playerView::selectQuality)
                    }
                }

                launch {
                    viewModel.audioQualities.collect { qualities ->
                        playerView.setAudiosSelect(qualities)
                    }
                }

                launch {
                    viewModel.selectedAudioQuality.collect { quality ->
                        quality?.let(playerView::selectAudio)
                    }
                }

                launch {
                    viewModel.videoCodecs.collect { codecs ->
                        playerView.setVideoCodec(codecs)
                    }
                }

                launch {
                    viewModel.selectedVideoCodec.collect { codec ->
                        codec?.let(playerView::selectVideoCodec)
                    }
                }

                launch {
                    viewModel.subtitles.collect { subtitles ->
                        playerView.setSubtitles(subtitles)
                        playerView.showHideSubtitleButton(subtitles.isNotEmpty())
                    }
                }

                launch {
                    viewModel.selectedSubtitleIndex.collect { index ->
                        playerView.selectSubtitle(index)
                    }
                }

                launch {
                    viewModel.currentSubtitleText.collect { subtitle ->
                        val visible = !subtitle.isNullOrBlank()
                        textSubtitle.isVisible = visible
                        textSubtitle.text = subtitle.orEmpty()
                        if (::playerSettings.isInitialized) {
                            textSubtitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, playerSettings.subtitleTextSizePx.toFloat())
                        }
                    }
                }

                launch {
                    viewModel.danmakuUpdates.collect { update ->
                        if (update.replace) {
                            PlaybackStartupTrace.log(
                                traceId = activeStartupTraceId,
                                startElapsedMs = activeStartupTraceStartElapsedMs,
                                step = "danmaku_ui_submitted",
                                message = "replace=true count=${update.items.size}"
                            )
                            playerView.setDanmakuData(
                                data = update.items,
                                filterContext = update.filterContext,
                                startupTraceId = activeStartupTraceId,
                                startupTraceStartElapsedMs = activeStartupTraceStartElapsedMs
                            )
                        } else {
                            PlaybackStartupTrace.log(
                                traceId = activeStartupTraceId,
                                startElapsedMs = activeStartupTraceStartElapsedMs,
                                step = "danmaku_ui_submitted",
                                message = "replace=false count=${update.items.size}"
                            )
                            playerView.appendDanmakuData(update.items, update.filterContext)
                        }
                    }
                }

                launch {
                    viewModel.danmaku.collect {
                        updateDanmakuSwitchVisibility()
                    }
                }

                launch {
                    viewModel.interactionModel.collect { model ->
                        if (model == null) {
                            interactionView.hideAll()
                        } else {
                            interactionView.visibility = View.VISIBLE
                            interactionView.onNodeLoaded(model)
                        }
                    }
                }

                launch {
                    viewModel.interactionHiddenVars.collect { hiddenVars ->
                        interactionView.updateVariablesDisplay(hiddenVars)
                    }
                }

                launch {
                    viewModel.videoSnapshot.collect { snapshot ->
                        playerView.setSeekPreviewSnapshot(snapshot)
                    }
                }

                launch {
                    viewModel.episodes.collect { episodes ->
                        sessionCoordinator.updateEpisodes(episodes)
                        schedulePreloadAndHeaderRefresh()
                        playerView.showHideEpisodeButton(episodes.isNotEmpty())
                        updateEpisodeNavigationVisibility()
                    }
                }

                launch {
                    viewModel.selectedEpisodeIndex.collect { index ->
                        sessionCoordinator.updateSelectedEpisodeIndex(index)
                        schedulePreloadAndHeaderRefresh()
                        updateEpisodeNavigationVisibility()
                    }
                }

                launch {
                    viewModel.relatedVideos.collect { rawRelated ->
                        val related = ContentFilter.filterVideos(requireContext(), rawRelated)
                        sessionCoordinator.updateRelatedVideos(related)
                        schedulePreloadAndHeaderRefresh()
                        relatedAdapter.setData(related)
                        playerView.showHideRelatedButton(related.isNotEmpty())
                    }
                }

                launch {
                    viewModel.currentPosition.collect { position ->
                        latestPlaybackPositionMs = position.coerceAtLeast(0L)
                        renderBottomProgressBar()
                    }
                }

                launch {
                    viewModel.duration.collect { duration ->
                        latestPlaybackDurationMs = duration.coerceAtLeast(0L)
                        renderBottomProgressBar()
                    }
                }

                launch {
                    viewModel.isLoading.collect { isLoading ->
                        latestLoadingState = isLoading
                        renderDebugState()
                    }
                }

                launch {
                    viewModel.error.collect { error ->
                        latestErrorMessage = error
                        if (!error.isNullOrBlank()) {
                            AppLog.e(TAG, "viewModel error: $error")
                            playerView.forceOpenShutter()
                        }
                        playerView.setCustomErrorMessage(error)
                        renderDebugState()
                    }
                }

                launch {
                    viewModel.sponsorSkipState.collect { state ->
                        when (state) {
                            is VideoPlayerViewModel.SponsorSkipUiState.Hidden -> {}
                            is VideoPlayerViewModel.SponsorSkipUiState.ShowButton -> {}
                            is VideoPlayerViewModel.SponsorSkipUiState.AutoSkipped -> {
                                context?.toast("已跳过: ${state.segment.categoryName()}")
                                player?.seekTo(state.segment.endTimeMs)
                            }
                        }
                    }
                }

                launch {
                    viewModel.sponsorSegments.collect { segments ->
                        val controller = playerView.getController()
                        controller?.setSponsorSegments(segments)
                        controller?.setSponsorDuration(viewModel.duration.value)
                        if (::slimTimelineRenderer.isInitialized) {
                            slimTimelineRenderer.setSegments(segments)
                            slimTimelineRenderer.setSponsorDuration(viewModel.duration.value)
                        }
                    }
                }
                launch {
                    viewModel.duration.collect { durationMs ->
                        playerView.getController()?.setSponsorDuration(durationMs)
                        if (::slimTimelineRenderer.isInitialized) {
                            slimTimelineRenderer.setSponsorDuration(durationMs)
                        }
                    }
                }
            }
        }
    }

    private var preloadHeaderRefreshPosted = false

    private fun schedulePreloadAndHeaderRefresh() {
        if (preloadHeaderRefreshPosted) return
        preloadHeaderRefreshPosted = true
        view?.post {
            preloadHeaderRefreshPosted = false
            renderPlayerHeader()
        }
    }

    private fun syncPlaybackEnvironment() {
        if (suppressPlaybackEnvironmentSync) {
            return
        }
        val currentPlayer = player
        val keepScreenOn = currentPlayer != null &&
            currentPlayer.playWhenReady &&
            currentPlayer.playbackState != Player.STATE_IDLE &&
            currentPlayer.playbackState != Player.STATE_ENDED
        if (lastKeepScreenOnState == keepScreenOn) {
            return
        }
        lastKeepScreenOnState = keepScreenOn
        activity?.let { ViewUtils.keepScreenOn(it, keepScreenOn) }
    }

    private fun recoverFromPlaybackStall(positionMs: Long, stalledMs: Long) {
        val currentPlayer = player ?: return
        if (
            currentPlayer.playbackState != Player.STATE_READY ||
            !currentPlayer.playWhenReady
        ) {
            return
        }
        AppLog.w(
            TAG,
            "playback stall detected: position=$positionMs, stalledMs=$stalledMs, isPlaying=${currentPlayer.isPlaying}, bufferedPosition=${currentPlayer.bufferedPosition}"
        )
        if (viewModel.handlePlaybackStall(positionMs, stalledMs)) {
            viewModel.setLoading(true)
            playerView.pauseDanmaku()
            progressCoordinator.restart()
            return
        }
        val snapshotPosition = currentPlayer.currentPosition
        currentPlayer.seekTo(snapshotPosition.coerceAtLeast(0L))
        playerView.syncDanmakuPosition(snapshotPosition, forceSeek = true)
        progressCoordinator.restart()
    }

    private fun Int.toPlaybackStateName(): String {
        return when (this) {
            Player.STATE_IDLE -> "IDLE"
            Player.STATE_BUFFERING -> "BUFFERING"
            Player.STATE_READY -> "READY"
            Player.STATE_ENDED -> "ENDED"
            else -> "UNKNOWN($this)"
        }
    }

    private fun renderDebugState() {
        val text = PlayerScreenLogic.debugOverlayText(
            showDebugInfo = ::playerSettings.isInitialized && playerSettings.showDebugInfo,
            errorMessage = latestErrorMessage,
            player = player,
            loadingText = getString(R.string.loading)
        )
        if (text == null) {
            textDebug.isVisible = false
            textDebug.text = ""
        } else {
            textDebug.isVisible = true
            textDebug.text = text
        }
    }

    private fun buildDebugInfo(p: ExoPlayer): String {
        return PlayerScreenLogic.buildDebugInfo(p)
    }

    private fun applyPlayerSettings(settings: PlayerSettings) {
        playerView.setPlaySpeed(settings.defaultPlaybackSpeed)
        playerView.setSeekSecond(settings.fastSeekSeconds)
        playerView.setSimpleKeyPressEnabled(settings.simpleKeyPress)
        playerView.setPersistentBottomProgressEnabled(settings.showBottomProgressBar)
        playerView.showHideFfRe(settings.showRewindFastForward)
        textSubtitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, settings.subtitleTextSizePx.toFloat())
        playerView.setAfterPlayMode(settings.afterPlayMode)
        renderDebugState()
        updateEpisodeNavigationVisibility()
        updateDanmakuSwitchVisibility()
        renderControllerChrome()
    }

    private fun renderControllerChrome(visibility: Int = latestControllerVisibility) {
        latestControllerVisibility = visibility
        syncChromeStateToCoordinator(visibility)
        val subtitleBottomMarginRes = when (uiCoordinator.bottomOccupant) {
            PlaybackUiCoordinator.BottomOccupant.FullChrome -> R.dimen.px300
            PlaybackUiCoordinator.BottomOccupant.SlimTimeline -> R.dimen.px80
            PlaybackUiCoordinator.BottomOccupant.BottomPanel -> R.dimen.px300
            PlaybackUiCoordinator.BottomOccupant.None -> R.dimen.px60
        }
        (textSubtitle.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
            val targetMargin = resources.getDimensionPixelSize(subtitleBottomMarginRes)
            if (params.bottomMargin != targetMargin) {
                params.bottomMargin = targetMargin
                textSubtitle.layoutParams = params
            }
        }
        renderBottomProgressBar()
        textClock.visibility = when (uiCoordinator.hudState) {
            PlaybackUiCoordinator.HudState.Chrome -> View.VISIBLE
            else -> View.GONE
        }
    }

    private fun renderBottomProgressBar() {
        if (!::slimTimelineRenderer.isInitialized) {
            bottomProgressBar.isVisible = false
            return
        }
        refreshAmbientSlimTimelineState()
        val shouldShow = ::playerSettings.isInitialized && PlayerScreenLogic.shouldShowSlimTimeline(
            showBottomProgressBar = playerSettings.showBottomProgressBar,
            controllerVisibility = latestControllerVisibility,
            coordinator = uiCoordinator
        )
        if (shouldShow) {
            slimTimelineRenderer.show(latestPlaybackPositionMs, latestPlaybackDurationMs)
        } else {
            slimTimelineRenderer.hide()
        }
    }

    private fun refreshAmbientSlimTimelineState() {
        val showBottomProgressBar =
            if (::playerSettings.isInitialized) playerSettings.showBottomProgressBar else null
        if (!PlayerScreenLogic.shouldRefreshAmbientChrome(
                showBottomProgressBar = showBottomProgressBar,
                controllerVisibility = latestControllerVisibility,
                coordinator = uiCoordinator
            )
        ) {
            return
        }
        uiCoordinator.syncAmbientChrome(playerSettings.showBottomProgressBar)
    }

    private fun updateEpisodeNavigationVisibility() {
        val episodes = sessionCoordinator.getEpisodes()
        val showNavigation = playerSettings.showNextPrevious && episodes.size > 1
        playerView.showHideNextPrevious(showNavigation)
        playerView.setEpisodeNavigationEnabled(
            previousEnabled = showNavigation && viewModel.hasPreviousEpisode(),
            nextEnabled = showNavigation && viewModel.hasNextEpisode()
        )
    }

    private fun updateDanmakuSwitchVisibility() {
        val hasDanmaku = viewModel.danmaku.value.orEmpty().isNotEmpty()
        playerView.showHideDmSwitchButton(playerSettings.showDanmakuSwitch && hasDanmaku)
    }

    private fun updatePrimaryActionVisibility() {
        val (hasOwner, hasVideoIdentity) = PlayerScreenLogic.primaryActionFlags(latestVideoInfo?.view)
        playerView.setShowHideOwnerInfo(hasOwner)
        playerView.showHideActionButton(hasVideoIdentity)
        playerView.showSettingButton(hasVideoIdentity)
    }

    private fun renderPlayerHeader() {
        val video = latestVideoInfo?.view ?: return
        val selectedEpisode = sessionCoordinator.getSelectedEpisode()
        playerView.setTitle(PlayerScreenLogic.buildHeaderTitle(video.title, selectedEpisode))
        playerView.setSubTitle(PlayerScreenLogic.buildHeaderMetaParts(video).joinToString(" · "))
    }


    private fun restartProgressUpdates() {
        progressCoordinator.restart()
    }

    private fun stopProgressUpdates() {
        progressCoordinator.stop()
        autoPlayController.cancelPendingAction()
    }

    private fun handlePlaybackEnded() {
        val afterPlayMode = playerSettings.afterPlayMode
        val hasNextEpisode = viewModel.hasNextEpisode()
        val nextEpisode = viewModel.getNextEpisode()
        when (
            val plan = sessionCoordinator.buildContinuationPlan(
                afterPlayMode = afterPlayMode,
                exitPlayerWhenPlaybackFinished = playerSettings.exitPlayerWhenPlaybackFinished,
                hasNextEpisode = hasNextEpisode,
                nextEpisode = nextEpisode
            )
        ) {
            is PlayerSessionCoordinator.ContinuationPlan.PlayIntent -> {
                val intent = plan.intent
                AppLog.i(TAG, "autoplay plan=${intent.kind} mode=$afterPlayMode cid=${intent.target.cid} id=${intent.id}")
                val session = viewModel.prepareContinuation(intent) ?: return
                autoPlayController.queueNextSession(intent.title, intent.coverUrl, session.id)
            }

            PlayerSessionCoordinator.ContinuationPlan.ExitPlayer -> {
                AppLog.i(TAG, "autoplay plan=exit mode=$afterPlayMode hasNext=$hasNextEpisode nextCid=${nextEpisode?.cid}")
                exitPlayerHost()
            }

            PlayerSessionCoordinator.ContinuationPlan.ShowController -> {
                AppLog.i(TAG, "autoplay plan=show_controller mode=$afterPlayMode hasNext=$hasNextEpisode nextCid=${nextEpisode?.cid}")
                playerView.showController()
            }
        }
    }

    private fun hideNextPreview() {
        autoPlayController.hideNextPreview()
    }

    private fun isVideoBlockedByMinorProtection(video: VideoModel): Boolean {
        return PlayerContentGuards.isVideoBlocked(requireContext(), video)
    }

    private fun isEpisodeBlockedByMinorProtection(episode: VideoPlayerViewModel.PlayableEpisode): Boolean {
        return PlayerContentGuards.isEpisodeBlocked(requireContext(), episode)
    }

    private fun showChooseEpisodeDialog() {
        overlayUiController.showChooseEpisodeDialog()
    }

    private fun showRelatedPanel() {
        overlayUiController.showRelatedPanel()
    }

    private fun hideContentPanel(restoreFocus: Boolean = true) {
        overlayUiController.hideContentPanel(restoreFocus)
    }

    private fun showVideoInfoDialog() {
        overlayUiController.showVideoInfoDialog()
    }

    private fun consumeLaunchContext() {
        sessionCoordinator.consumeLaunchContext(arguments) ?: return
        renderLaunchHeader()
    }

    private fun resolveLaunchContext(): PlayerLaunchContext? {
        return sessionCoordinator.resolveLaunchContext(arguments)
    }

    private fun renderLaunchHeader() {
        val video = sessionCoordinator.getLaunchVideo() ?: return
        playerView.setTitle(video.title)
        playerView.setSubTitle(
            buildList {
                video.owner?.name?.takeIf { it.isNotBlank() }?.let(::add)
                video.viewCount.takeIf { it > 0L }?.let { add("${NumberUtils.formatCount(it)}播放") }
                val publishTime = when {
                    video.pubDate > 0L -> video.pubDate
                    video.createTime > 0L -> video.createTime
                    else -> 0L
                }
                if (publishTime > 0L) {
                    add(TimeUtils.formatTime(publishTime))
                }
            }.joinToString(" · ")
        )
    }

    private fun showPlayerActionDialog() {
        overlayUiController.showPlayerActionDialog()
    }

    private fun exitPlayerHost() {
        navigateBackFromUi(skipNextFocusRestore = activity is MainActivity)
    }

    private fun showOwnerDetailDialog() {
        overlayUiController.showOwnerDetailDialog()
    }

    private fun openFragmentFromPlayerHost(fragment: Fragment, tag: String) {
        val mainActivity = activity as? MainActivity
        if (mainActivity != null) {
            mainActivity.openInHostContainer(fragment)
            return
        }
        parentFragmentManager.commit {
            replace(R.id.player_container, fragment, tag)
            addToBackStack(tag)
        }
    }

    override fun onStart() {
        super.onStart()
        resumePlaybackIfNeeded(reason = "onStart")
        if (resumePlaybackWhenStarted) {
            playerView.resumeDanmaku()
        } else {
            playerView.pauseDanmaku()
        }
        syncPlaybackEnvironment()
        if (player != null) {
            restartProgressUpdates()
        }
        playerView.removeCallbacks(resumePlaybackRunnable)
        playerView.postDelayed(resumePlaybackRunnable, 250L)
    }

    override fun onStop() {
        super.onStop()
        playerView.removeCallbacks(resumePlaybackRunnable)
        progressCoordinator.syncNow()
        val (snapshotPositionMs, snapshotPlayWhenReady) = capturePlaybackSnapshot()
        postPlaybackProgressEvent(snapshotPositionMs)
        viewModel.reportPlaybackHeartbeat()
        viewModel.savePlayerSnapshot()
        resumePlaybackWhenStarted = snapshotPlayWhenReady
        player?.playWhenReady = false
        playerView.pauseDanmaku()
        stopProgressUpdates()
        syncPlaybackEnvironment()
    }

    override fun onDestroyView() {
        playerView.removeCallbacks(resumePlaybackRunnable)
        stopProgressUpdates()
        resumeHintController.release()
        backgroundListener?.let(AppBackgroundMonitor::removeListener)
        backgroundListener = null
        player?.removeListener(playerListener)
        // playerPerfListener 此前未移除：player 是 PlayerInstancePool 的共享单例，
        // 不移除会跨 Fragment 重建累积泄漏，每次重建都叠加一份 AnalyticsListener。
        player?.removeAnalyticsListener(playerPerfListener)
        playerView.destroy()
        playerView.stopDanmaku()
        PlayerInstancePool.softDetach(player)
        lastKeepScreenOnState = false
        activity?.let { ViewUtils.keepScreenOn(it, false) }
        viewRelated.clearAnimation()
        player = null
        progressCoordinator.reset()
        _binding = null
        super.onDestroyView()
    }

    private fun capturePlaybackSnapshot(): Pair<Long, Boolean> {
        val currentPlayer = player
        val positionMs = currentPlayer?.currentPosition?.coerceAtLeast(0L)
            ?: viewModel.currentPosition.value
            ?: 0L
        val playWhenReady = currentPlayer?.playWhenReady ?: resumePlaybackWhenStarted
        return positionMs to playWhenReady
    }

    private data class StartupTrace(
        val sequence: Int,
        val startedAtMs: Long,
        var readyLogged: Boolean = false,
        var firstFrameLogged: Boolean = false
    )

    /** 追踪单次播放请求的 ExoPlayer 内部阶段耗时 */
    private var playerPerfTrace: PlayerPerfTrace? = null
    /** 丢帧累计计数器（playerPerfListener 节流汇总用，每 2s 一报） */
    private var droppedVideoFramesAccumulator: Int = 0
    private var lastDroppedFramesLogMs: Long = 0L
    private var activeStartupTraceId: String = PlaybackStartupTrace.NO_TRACE
    private var activeStartupTraceStartElapsedMs: Long = 0L
    private var activeStartupFirstFrameLogged: Boolean = false

    private data class PlayerPerfTrace(
        val prepareMs: Long,       // prepare() 被调用的时间
        var firstLoadStartedMs: Long = 0L,   // 首个媒体数据开始加载
        var firstLoadCompletedMs: Long = 0L, // 首个媒体数据加载完成
        var videoDecoderInitMs: Long = 0L,   // 视频解码器初始化完成
        var audioDecoderInitMs: Long = 0L,   // 音频解码器初始化完成
        var firstFrameMs: Long = 0L          // 首帧渲染
    )

    private fun resumePlaybackIfNeeded(reason: String) {
        val currentPlayer = player ?: return
        if (!resumePlaybackWhenStarted) {
            return
        }
        if (currentPlayer.playbackState == Player.STATE_ENDED) {
            return
        }
        if (currentPlayer.playbackState == Player.STATE_IDLE) {
            if (currentPlayer.mediaItemCount <= 0) {
                return
            }
            currentPlayer.prepare()
        }
        currentPlayer.playWhenReady = true
        currentPlayer.play()
    }

    private fun postPlaybackProgressEvent(positionMs: Long) {
        PlayerScreenLogic.postPlaybackProgressEvent(
            appEventHub = appEventHub,
            latestView = latestVideoInfo?.view,
            sessionCoordinator = sessionCoordinator,
            positionMs = positionMs
        )
    }

    private fun syncChromeStateToCoordinator(visibility: Int) {
        PlayerScreenLogic.syncChromeStateToCoordinator(
            coordinator = uiCoordinator,
            showBottomProgressBar = playerSettings.showBottomProgressBar,
            visibility = visibility
        )
    }

    // region 抖音模式

    private fun handleDouyinNext(): Boolean {
        val currentVideo = sessionCoordinator.getCurrentVideo() ?: return false
        if (!douyinModeManager.isApplicable(currentVideo)) return false

        if (!douyinModeManager.hasList() && !douyinInitializing) {
            douyinPendingNextAfterInit = true
            startDouyinQueueInitialization(currentVideo)
            return true
        }
        if (douyinInitializing) {
            douyinPendingNextAfterInit = true
            return true
        }

        val next = douyinModeManager.next()
        if (next == null) {
            playerView.cancelDouyinPageTransition()
            playerView.showDouyinBoundaryBounce(1)
            Toast.makeText(requireContext(), "该视频无推荐", Toast.LENGTH_SHORT).show()
            return true
        }
        playDouyinVideo(next, direction = 1)
        return true
    }

    private fun handleDouyinPrevious(): Boolean {
        val currentVideo = sessionCoordinator.getCurrentVideo() ?: return false
        if (!douyinModeManager.isApplicable(currentVideo)) return false

        // 抖音模式激活时，始终消费上键事件，不弹出控制器
        if (!douyinModeManager.hasList()) {
            playerView.cancelDouyinPageTransition()
            playerView.showDouyinBoundaryBounce(-1)
            return true
        }
        val prev = douyinModeManager.previous()
        if (prev != null) {
            playDouyinVideo(prev, direction = -1)
        } else {
            playerView.cancelDouyinPageTransition()
            playerView.showDouyinBoundaryBounce(-1)
        }
        return true
    }

    /**
     * 播放抖音模式视频
     * @param direction 1=向上滑出（下键下一个），-1=向下滑出（上键上一个），0=无动画
     */
    private fun playDouyinVideo(video: com.tutu.myblbl.model.video.VideoModel?, direction: Int = 0) {
        if (video == null) return
        if (isVideoBlockedByMinorProtection(video)) {
            requireContext().toast("青少年模式已拦截该视频")
            return
        }
        playerView.hideController()
        douyinInternalNavigationKey = video.douyinIdentityKey()
        sessionCoordinator.updateCurrentVideo(video)
        renderDouyinTargetHeader(video)

        val playTarget = {
            viewModel.playRelatedVideo(video, preferLastPlayTime = false)
        }

        if (direction != 0 && playerView.consumeDouyinGestureTransition()) {
            playTarget()
        } else if (direction != 0 && !douyinTransitionRunning) {
            douyinTransitionRunning = true
            val transitionStarted = playerView.startDouyinPageTransition(
                direction,
                targetPreview = video.toDouyinPreview()
            ) {
                playTarget()
                douyinTransitionRunning = false
            }
            if (!transitionStarted) {
                playTarget()
                douyinTransitionRunning = false
            }
        } else {
            playTarget()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            douyinModeManager.appendMore()
        }
    }

    private fun scheduleDouyinPreloadAfterPlaybackRequest(playbackRequest: VideoPlayerViewModel.PlaybackRequest) {
        if (!douyinModeManager.hasList()) return
        val currentVideo = sessionCoordinator.getCurrentVideo() ?: return
        if (!douyinModeManager.isApplicable(currentVideo)) return
        val requestMatchesCurrent = when {
            playbackRequest.cid > 0L && currentVideo.cid > 0L -> playbackRequest.cid == currentVideo.cid
            playbackRequest.aid != null && currentVideo.aid > 0L -> playbackRequest.aid == currentVideo.aid
            !playbackRequest.bvid.isNullOrBlank() && currentVideo.bvid.isNotBlank() -> playbackRequest.bvid == currentVideo.bvid
            else -> false
        }
        if (!requestMatchesCurrent) return
        viewLifecycleOwner.lifecycleScope.launch {
            douyinModeManager.appendMore()
            preloadNextDouyinVideo()
        }
    }

    /** 预加载抖音列表中下一个视频的 MediaSource */
    private fun preloadNextDouyinVideo() {
        val nextVideo = douyinModeManager.peekNext() ?: return
        val aid = nextVideo.aid.takeIf { it > 0 }
        val bvid = nextVideo.bvid.takeIf { it.isNotBlank() }
        val cid = nextVideo.cid
        if (cid <= 0L) return
        viewModel.preloadPlayback(
            PlaybackPreloadTarget(
                aid = aid,
                bvid = bvid,
                cid = cid,
                source = PlaybackPreloadTarget.Source.DOUYIN_MODE
            )
        )
    }

    private fun renderDouyinTargetHeader(video: com.tutu.myblbl.model.video.VideoModel) {
        playerView.setTitle(video.title)
        playerView.setSubTitle(video.authorName.takeIf { it.isNotBlank() }.orEmpty())
    }

    private fun resetDouyinModeIfNeeded() {
        val currentVideo = sessionCoordinator.getCurrentVideo()
        val currentAid = currentVideo?.aid ?: 0L
        val currentKey = currentVideo?.douyinIdentityKey().orEmpty()
        if (currentKey.isNotBlank() && currentKey == douyinInternalNavigationKey) {
            douyinSourceAid = currentAid
            douyinSourceKey = currentKey
            douyinInternalNavigationKey = ""
            return
        }
        if (currentKey.isNotBlank() && currentKey != douyinSourceKey) {
            AppLog.i(TAG, "DouyinQueue reset_external current=${currentVideo?.douyinDebugId().orEmpty()} sourceKey=$douyinSourceKey internalKey=$douyinInternalNavigationKey")
            douyinSourceAid = currentAid
            douyinSourceKey = currentKey
            douyinModeManager.reset()
            douyinInitializing = false
            douyinInternalNavigationKey = ""
            douyinPendingNextAfterInit = false
        }
    }

    private fun ensureDouyinQueueStarted() {
        val currentVideo = sessionCoordinator.getCurrentVideo() ?: return
        if (!douyinModeManager.isApplicable(currentVideo)) {
            if (douyinModeManager.hasList()) {
                AppLog.i(TAG, "DouyinQueue ensure_reset_unplayable current=${currentVideo.douyinDebugId()}")
                douyinModeManager.reset()
            }
            douyinInitializing = false
            return
        }
        if (douyinModeManager.hasList() || douyinInitializing) return

        startDouyinQueueInitialization(currentVideo)
    }

    private fun startDouyinQueueInitialization(currentVideo: com.tutu.myblbl.model.video.VideoModel) {
        if (douyinInitializing) return
        douyinInitializing = true
        viewLifecycleOwner.lifecycleScope.launch {
            douyinModeManager.initialize(currentVideo)
            douyinInitializing = false
            val latestVideo = sessionCoordinator.getCurrentVideo()
            if (!currentVideo.isSameDouyinVideo(latestVideo)) {
                AppLog.i(TAG, "DouyinQueue ensure_stale initialized=${currentVideo.douyinDebugId()} latest=${latestVideo?.douyinDebugId().orEmpty()}")
                douyinPendingNextAfterInit = false
                return@launch
            }
            if (!douyinModeManager.hasList()) {
                if (douyinPendingNextAfterInit) {
                    playerView.cancelDouyinPageTransition()
                    playerView.showDouyinBoundaryBounce(1)
                    Toast.makeText(requireContext(), "该视频无推荐", Toast.LENGTH_SHORT).show()
                }
                douyinPendingNextAfterInit = false
                return@launch
            }

            if (douyinPendingNextAfterInit) {
                douyinPendingNextAfterInit = false
                val next = douyinModeManager.next()
                if (next == null) {
                    playerView.cancelDouyinPageTransition()
                    playerView.showDouyinBoundaryBounce(1)
                    Toast.makeText(requireContext(), "该视频无推荐", Toast.LENGTH_SHORT).show()
                } else {
                    playDouyinVideo(next, direction = 1)
                }
            } else {
                preloadNextDouyinVideo()
            }
        }
    }

    // endregion
}

private fun com.tutu.myblbl.model.video.VideoModel.toDouyinPreview(): DouyinModePreview {
    return DouyinModePreview(
        title = title,
        coverUrl = effectiveCoverUrl
    )
}

private fun com.tutu.myblbl.model.video.VideoModel.douyinDebugId(): String {
    return "aid=$aid,bvid=$bvid,title=${title.take(18)}"
}

private fun com.tutu.myblbl.model.video.VideoModel.douyinIdentityKey(): String {
    val epKey = playbackEpId.takeIf { it > 0L }?.let { "ep:$it" }
    val bvidKey = bvid.takeIf { it.isNotBlank() }?.let { "bvid:$it" }
    val aidKey = aid.takeIf { it > 0L }?.let { "aid:$it" }
    val cidKey = cid.takeIf { it > 0L }?.let { "cid:$it" }
    return listOfNotNull(epKey, bvidKey, aidKey, cidKey).joinToString("|")
}

private fun com.tutu.myblbl.model.video.VideoModel.isSameDouyinVideo(other: com.tutu.myblbl.model.video.VideoModel?): Boolean {
    if (other == null) return false
    return when {
        aid > 0L && other.aid > 0L -> aid == other.aid
        bvid.isNotBlank() && other.bvid.isNotBlank() -> bvid == other.bvid
        else -> false
    }
}
