package com.tutu.myblbl.feature.player

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
import com.tutu.myblbl.core.common.log.AppLog
import com.tutu.myblbl.core.lifecycle.AppBackgroundMonitor
import com.tutu.myblbl.core.common.content.ContentFilter
import com.tutu.myblbl.feature.player.settings.PlayerSettings
import com.tutu.myblbl.feature.player.settings.PlayerSettingsStore
import com.tutu.myblbl.core.common.time.TimeUtils
import com.tutu.myblbl.core.ui.navigation.navigateBackFromUi
import com.tutu.myblbl.core.ui.system.ViewUtils
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
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
        publishProgressStateProvider = { _binding != null && bottomProgressBar.isVisible },
        onProgressPublished = { positionMs, durationMs, publishProgressState ->
            viewModel.updatePlaybackPosition(positionMs, durationMs, publishProgressState)
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
            AppLog.i("VideoPlayerViewModel", "PLAYER_PERF [C] video decoder init ($decoderName) hw_init=${elapsedInitializationMs}ms +${now - trace.prepareMs}ms")
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
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            val isSeeking = playerView.seekSession?.isActive() == true
            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    viewModel.setLoading(true)
                    playerView.pauseDanmaku()
                }
                Player.STATE_READY -> {
                    startupTrace
                        ?.takeIf { !it.readyLogged }
                        ?.also {
                            it.readyLogged = true
                        }
                    viewModel.setLoading(false)
                    if (player?.playWhenReady == true && !isSeeking) {
                        playerView.resumeDanmaku()
                    }
                    hideNextPreview()
                }
                Player.STATE_ENDED -> {
                    viewModel.setLoading(false)
                    playerView.stopDanmaku()
                    handlePlaybackEnded()
                }
                Player.STATE_IDLE -> {
                    viewModel.setLoading(false)
                    playerView.pauseDanmaku()
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
            } else if (!isPlaying) {
                playerView.pauseDanmaku()
                progressCoordinator.stop()
                progressCoordinator.syncNow(publishProgressState = true)
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
        slimTimelineRenderer = SlimTimelineRenderer(bottomProgressBar) {
            ::playerSettings.isInitialized && playerSettings.showBottomProgressBar && latestControllerVisibility != View.VISIBLE
        }
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
        relatedAdapter = VideoAdapter(
            itemWidthPx = (resources.displayMetrics.widthPixels / 5).coerceAtLeast(1)
        )
        relatedAdapter.setOnItemClickListener { _, item ->
            playerView.hideController()
            hideContentPanel()
            hideNextPreview()
            sessionCoordinator.replacePlayQueue(PlayerActivity.buildPlayQueue(relatedAdapter.getItemsSnapshot(), item))
            sessionCoordinator.updateCurrentVideo(item)
            viewModel.playRelatedVideo(item)
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
            onPendingActionCleared = { viewModel.preloadPlayback(null) }
        )
        overlayUiController = VideoPlayerOverlayController(
            activity = requireActivity() as androidx.appcompat.app.AppCompatActivity,
            playerView = playerView,
            overlayCoordinator = overlayCoordinator,
            uiCoordinator = uiCoordinator,
            sessionCoordinator = sessionCoordinator,
            playerProvider = { player },
            latestVideoInfoProvider = { latestVideoInfo },
            relatedAdapter = relatedAdapter,
            viewRelated = viewRelated,
            dimBackground = binding.dimBackground,
            recyclerViewRelated = recyclerViewRelated,
            textMoreTitle = textMoreTitle,
            onPlayEpisode = { index -> playerView.hideController(); viewModel.playEpisode(index) },
            onPlayRelatedVideo = { video, playQueue ->
                playerView.hideController()
                sessionCoordinator.replacePlayQueue(playQueue)
                sessionCoordinator.updateCurrentVideo(video)
                updatePlaybackPreload()
                viewModel.playRelatedVideo(video)
            },
            onOpenFragmentFromHost = ::openFragmentFromPlayerHost,
            onHideNextPreview = { autoPlayController.hideNextPreview() },
            isViewActive = { _binding != null }
        )
        resumeHintController = VideoPlayerResumeHintController(
            activity = requireActivity() as androidx.appcompat.app.AppCompatActivity,
            playerProvider = { player },
            onCancelResume = { viewModel.cancelResumeProgress() },
            onClearResumeHint = { viewModel.clearResumeHint() }
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

    private fun updatePlaybackPreload() {
        viewModel.preloadPlayback(sessionCoordinator.buildPreloadTarget())
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
                if (!playbackRequest.replaceInPlace) {
                    playerView.getController()?.hideImmediately()
                    playerView.prepareForPlaybackTransition()
                    viewModel.resetPlaybackProgress()
                    latestPlaybackPositionMs = 0L
                    latestPlaybackDurationMs = 0L
                    if (::slimTimelineRenderer.isInitialized) {
                        slimTimelineRenderer.show(0L, 0L)
                    }
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
                    currentPlayer.stop()
                    currentPlayer.setMediaSource(playbackRequest.mediaSource, playbackRequest.seekPositionMs)
                    PlaybackStartupTrace.log(
                        traceId = activeStartupTraceId,
                        startElapsedMs = activeStartupTraceStartElapsedMs,
                        step = "media_source_set",
                        message = "seek=${playbackRequest.seekPositionMs}"
                    )
                    currentPlayer.prepare()
                    PlaybackStartupTrace.log(
                        traceId = activeStartupTraceId,
                        startElapsedMs = activeStartupTraceStartElapsedMs,
                        step = "player_prepare_called",
                        message = "playWhenReady=${playbackRequest.playWhenReady}"
                    )
                    currentPlayer.playWhenReady = playbackRequest.playWhenReady
                } finally {
                    suppressPlaybackEnvironmentSync = false
                }
                playerView.syncDanmakuPosition(playbackRequest.seekPositionMs, forceSeek = true)
                syncPlaybackEnvironment()
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
                        schedulePreloadAndHeaderRefresh()
                        updatePrimaryActionVisibility()
                    }
                }

                launch {
                    viewModel.resumeHint.collect { hint ->
                        // Toast 已在 ViewModel 中直接显示，仅消费 hint
                        if (hint != null) viewModel.clearResumeHint()
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
                            playerView.appendDanmakuData(update.items)
                        }
                    }
                }

                launch {
                    viewModel.danmaku.collect {
                        updateDanmakuSwitchVisibility()
                    }
                }

                launch {
                    viewModel.specialDanmaku.collect { data ->
                        PlaybackStartupTrace.log(
                            traceId = activeStartupTraceId,
                            startElapsedMs = activeStartupTraceStartElapsedMs,
                            step = "special_danmaku_ui_submitted",
                            message = "count=${data.size}"
                        )
                        playerView.setSpecialDanmakuData(data)
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
            updatePlaybackPreload()
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
        if (!::playerSettings.isInitialized || !playerSettings.showDebugInfo) {
            textDebug.isVisible = false
            textDebug.text = ""
            return
        }
        if (!latestErrorMessage.isNullOrBlank()) {
            textDebug.isVisible = true
            textDebug.text = latestErrorMessage
            return
        }
        val p = player
        if (p == null || p.playbackState == Player.STATE_IDLE) {
            textDebug.isVisible = true
            textDebug.text = getString(R.string.loading)
            return
        }
        textDebug.isVisible = true
        textDebug.text = buildDebugInfo(p)
    }

    private fun buildDebugInfo(p: ExoPlayer): String {
        val sb = StringBuilder()
        val videoFormat = p.videoFormat
        if (videoFormat != null) {
            val w = videoFormat.width
            val h = videoFormat.height
            sb.appendLine("分辨率: ${w}x${h}${formatAspectRatio(w, h)}")
            val codec = formatCodecName(videoFormat.sampleMimeType)
            val bitrate = if (videoFormat.bitrate > 0) " ${videoFormat.bitrate / 1000}kbps" else ""
            sb.appendLine("视频: $codec$bitrate")
        }
        val audioFormat = p.audioFormat
        if (audioFormat != null) {
            val codec = formatCodecName(audioFormat.sampleMimeType)
            val sr = if (audioFormat.sampleRate > 0) " ${audioFormat.sampleRate}Hz" else ""
            sb.appendLine("音频: $codec$sr")
        }
        if (p.duration > 0) {
            val pos = formatMs(p.currentPosition)
            val dur = formatMs(p.duration)
            val speed = p.playbackParameters.speed
            sb.appendLine("进度: $pos / $dur (${speed}x)")
        }
        val bufferedAhead = p.bufferedPosition - p.currentPosition
        if (bufferedAhead > 0) {
            sb.appendLine("缓冲: ${"%.1f".format(bufferedAhead / 1000.0)}s")
        }
        val stateLabel = when (p.playbackState) {
            Player.STATE_BUFFERING -> "缓冲中"
            Player.STATE_READY -> if (p.playWhenReady) "播放中" else "暂停"
            Player.STATE_ENDED -> "已结束"
            else -> ""
        }
        if (stateLabel.isNotEmpty()) {
            sb.append("状态: $stateLabel")
        }
        return sb.toString().trimEnd()
    }

    private fun formatCodecName(mimeType: String?): String {
        if (mimeType == null) return "未知"
        return when {
            mimeType.contains("avc") || mimeType.contains("h264") -> "AVC (H.264)"
            mimeType.contains("hevc") || mimeType.contains("h265") -> "HEVC (H.265)"
            mimeType.contains("av01") -> "AV1"
            mimeType.contains("vp9") -> "VP9"
            mimeType.contains("vp8") -> "VP8"
            mimeType.contains("aac") -> "AAC"
            mimeType.contains("opus") -> "Opus"
            mimeType.contains("mp4a") -> "AAC"
            mimeType.contains("ec-3") || mimeType.contains("eac3") -> "E-AC-3"
            mimeType.contains("ac-3") -> "AC-3"
            mimeType.contains("flac") -> "FLAC"
            mimeType.contains("vorbis") -> "Vorbis"
            else -> mimeType.substringAfterLast("/")
        }
    }

    private fun formatAspectRatio(w: Int, h: Int): String {
        if (w <= 0 || h <= 0) return ""
        val gcd = gcd(w, h)
        val rw = w / gcd
        val rh = h / gcd
        if (rw > 30 || rh > 30) return ""
        return " ($rw:$rh)"
    }

    private fun gcd(a: Int, b: Int): Int {
        var x = a
        var y = b
        while (y != 0) {
            val t = y
            y = x % y
            x = t
        }
        return x
    }

    private fun formatMs(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format(Locale.getDefault(), "%d:%02d:%02d", h, m, s)
        else String.format(Locale.getDefault(), "%02d:%02d", m, s)
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
        val shouldShow = uiCoordinator.bottomOccupant == PlaybackUiCoordinator.BottomOccupant.SlimTimeline
                && uiCoordinator.seekState == PlaybackUiCoordinator.SeekState.None
        if (shouldShow) {
            slimTimelineRenderer.show(latestPlaybackPositionMs, latestPlaybackDurationMs)
        } else {
            slimTimelineRenderer.hide()
        }
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
        val hasDanmaku = viewModel.danmaku.value.orEmpty().isNotEmpty() ||
            viewModel.specialDanmaku.value.orEmpty().isNotEmpty()
        playerView.showHideDmSwitchButton(playerSettings.showDanmakuSwitch && hasDanmaku)
    }

    private fun updatePrimaryActionVisibility() {
        val view = latestVideoInfo?.view
        val hasOwner = view?.owner?.mid?.let { it > 0L } == true
        val hasVideoIdentity = (view?.aid ?: 0L) > 0L || !view?.bvid.isNullOrBlank()
        playerView.setShowHideOwnerInfo(hasOwner)
        playerView.showHideActionButton(hasVideoIdentity)
        playerView.showSettingButton(hasVideoIdentity)
    }

    private fun renderPlayerHeader() {
        val video = latestVideoInfo?.view ?: return
        val selectedEpisode = sessionCoordinator.getSelectedEpisode()
        playerView.setTitle(buildHeaderTitle(video.title, selectedEpisode))

        val metaParts = buildList {
            video.owner?.name?.takeIf { it.isNotBlank() }?.let(::add)
            video.stat?.view?.takeIf { it > 0 }?.let { add("${formatCount(it)}播放") }
            if (video.pubDate > 0) {
                add(TimeUtils.formatTime(video.pubDate))
            }
        }
        playerView.setSubTitle(metaParts.joinToString(" · "))
    }

    private fun buildHeaderTitle(
        videoTitle: String,
        selectedEpisode: VideoPlayerViewModel.PlayableEpisode?
    ): String {
        val episodeTitle = selectedEpisode
            ?.title
            ?.trim()
            .orEmpty()
        if (episodeTitle.isBlank() || episodeTitle == videoTitle) {
            return videoTitle
        }
        return "$episodeTitle ｜ $videoTitle"
    }

    private fun restartProgressUpdates() {
        progressCoordinator.restart()
    }

    private fun stopProgressUpdates() {
        progressCoordinator.stop()
        autoPlayController.cancelPendingAction()
    }

    private fun handlePlaybackEnded() {
        when (
            val plan = sessionCoordinator.buildContinuationPlan(
                afterPlayMode = playerView.getAfterPlayMode(),
                exitPlayerWhenPlaybackFinished = playerSettings.exitPlayerWhenPlaybackFinished,
                hasNextEpisode = viewModel.hasNextEpisode(),
                nextEpisode = viewModel.getNextEpisode(),
                playNextEpisode = { viewModel.playNext() },
                playVideo = {
                    sessionCoordinator.updateCurrentVideo(it)
                    viewModel.playRelatedVideo(it)
                }
            )
        ) {
            is PlayerSessionCoordinator.ContinuationPlan.PlayNextEpisode -> {
                viewModel.preloadPlayback(plan.preloadTarget)
                autoPlayController.queueNextAction(plan.title, plan.coverUrl, plan.perform)
            }

            is PlayerSessionCoordinator.ContinuationPlan.PlayVideo -> {
                viewModel.preloadPlayback(plan.preloadTarget)
                autoPlayController.queueNextAction(plan.title, plan.coverUrl, plan.perform)
            }

            PlayerSessionCoordinator.ContinuationPlan.ExitPlayer -> {
                exitPlayerHost()
            }

            PlayerSessionCoordinator.ContinuationPlan.ShowController -> {
                playerView.showController()
            }
        }
    }

    private fun hideNextPreview() {
        autoPlayController.hideNextPreview()
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
                video.viewCount.takeIf { it > 0L }?.let { add("${formatCount(it)}播放") }
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

    private fun formatCount(count: Long): String {
        return when {
            count >= 100000000L -> String.format(Locale.getDefault(), "%.1f亿", count / 100000000.0)
            count >= 10000L -> String.format(Locale.getDefault(), "%.1f万", count / 10000.0)
            else -> count.toString()
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
        val info = latestVideoInfo?.view ?: return
        val episodes = sessionCoordinator.getEpisodes()
        val selectedIndex = sessionCoordinator.getSelectedEpisodeIndex()
        if (episodes.isNotEmpty() && selectedIndex in episodes.indices) {
            val episode = episodes[selectedIndex]
            if (episode.epId > 0L) {
                appEventHub.dispatch(
                    AppEventHub.Event.EpisodePlaybackProgressUpdated(
                        episodeId = episode.epId,
                        progressMs = positionMs.coerceAtLeast(0L).plus(1L),
                        episodeIndex = episode.title
                    )
                )
                return
            }
        }
        val progressMs = positionMs.coerceAtLeast(0L).plus(1L)
        appEventHub.dispatch(
            AppEventHub.Event.PlaybackProgressUpdated(
                aid = info.aid,
                cid = info.cid,
                progressMs = progressMs
            )
        )
    }

    private fun syncChromeStateToCoordinator(visibility: Int) {
        uiCoordinator.withState { coord ->
            coord.chromeState = when (visibility) {
                View.VISIBLE -> PlaybackUiCoordinator.ChromeState.Full
                View.GONE -> PlaybackUiCoordinator.ChromeState.Hidden
                else -> coord.chromeState
            }
            coord.bottomOccupant = when (visibility) {
                View.VISIBLE -> PlaybackUiCoordinator.BottomOccupant.FullChrome
                View.GONE -> if (playerSettings.showBottomProgressBar) PlaybackUiCoordinator.BottomOccupant.SlimTimeline else PlaybackUiCoordinator.BottomOccupant.None
                else -> coord.bottomOccupant
            }
            coord.hudState = when (visibility) {
                View.VISIBLE -> PlaybackUiCoordinator.HudState.Chrome
                View.GONE -> PlaybackUiCoordinator.HudState.Ambient
                else -> coord.hudState
            }
        }
    }
}
