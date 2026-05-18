@file:Suppress("SpellCheckingInspection", "OVERRIDE_DEPRECATION")

package com.tutu.myblbl.ui.activity

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.core.app.ActivityOptionsCompat
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.TypedValue
import android.view.View
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

import androidx.lifecycle.lifecycleScope
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.common.C
import androidx.recyclerview.widget.RecyclerView
import com.tutu.myblbl.R
import com.tutu.myblbl.core.common.content.ContentFilter
import com.tutu.myblbl.core.common.log.AppLog
import com.tutu.myblbl.core.lifecycle.AppBackgroundMonitor
import com.tutu.myblbl.core.common.time.TimeUtils
import com.tutu.myblbl.core.ui.base.BaseActivity
import com.tutu.myblbl.core.ui.system.ViewUtils
import com.tutu.myblbl.databinding.FragmentVideoPlayerBinding
import com.tutu.myblbl.event.AppEventHub
import com.tutu.myblbl.feature.player.PlayerInstancePool
import com.tutu.myblbl.feature.player.PlaybackStartupTrace
import com.tutu.myblbl.feature.player.PlaybackUiCoordinator
import com.tutu.myblbl.feature.player.PlayerOverlayCoordinator
import com.tutu.myblbl.feature.player.PlayerSessionCoordinator
import com.tutu.myblbl.feature.player.SeekSession
import com.tutu.myblbl.feature.player.SlimTimelineRenderer
import com.tutu.myblbl.feature.player.VideoPlayerAutoPlayController
import com.tutu.myblbl.feature.player.VideoPlayerOverlayController
import com.tutu.myblbl.feature.player.VideoPlayerProgressCoordinator
import com.tutu.myblbl.feature.player.VideoPlayerResumeHintController
import com.tutu.myblbl.feature.player.VideoPlayerViewModel
import com.tutu.myblbl.feature.player.settings.PlayerSettings
import com.tutu.myblbl.feature.player.settings.PlayerSettingsStore
import com.tutu.myblbl.feature.player.interaction.InteractionOverlayView
import com.tutu.myblbl.feature.player.view.MyPlayerView
import com.tutu.myblbl.feature.player.view.OnPlayerSettingChange
import com.tutu.myblbl.feature.player.view.OnVideoSettingChangeListener
import com.tutu.myblbl.model.video.VideoModel
import com.tutu.myblbl.model.video.detail.VideoDetailModel
import com.tutu.myblbl.model.video.quality.AudioQuality
import com.tutu.myblbl.model.video.quality.VideoCodecEnum
import com.tutu.myblbl.model.video.quality.VideoQuality
import com.tutu.myblbl.ui.adapter.VideoAdapter
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

private const val RISK_CONTROL_USER_HINT = "账号被风控了，请到设置中完成验证"
private val riskControlUserHintShown = AtomicBoolean(false)

@UnstableApi
class PlayerActivity : BaseActivity<FragmentVideoPlayerBinding>() {

    companion object {
        private const val TAG = "PlayerActivity"
        private const val EXTRA_AID = "player_aid"
        private const val EXTRA_BVID = "player_bvid"
        private const val EXTRA_CID = "player_cid"
        private const val EXTRA_EP_ID = "player_ep_id"
        private const val EXTRA_SEASON_ID = "player_season_id"
        private const val EXTRA_SEEK_MS = "player_seek_ms"
        private const val EXTRA_START_EPISODE = "player_start_episode"
        private const val EXTRA_STARTUP_TRACE_ID = "player_startup_trace_id"
        private const val EXTRA_STARTUP_TRACE_START_MS = "player_startup_trace_start_ms"
        private var pendingPlayQueue: List<VideoModel> = emptyList()

        fun start(
            context: Context,
            aid: Long = 0L,
            bvid: String = "",
            cid: Long = 0L,
            epId: Long = 0L,
            seasonId: Long = 0L,
            seekPositionMs: Long = 0L,
            initialVideo: VideoModel? = null,
            playQueue: List<VideoModel> = emptyList(),
            startEpisodeIndex: Int = -1,
            startupTraceId: String = PlaybackStartupTrace.NO_TRACE,
            startupTraceStartElapsedMs: Long = 0L
        ) {
            val resolvedAid = aid.takeIf { it > 0L } ?: initialVideo?.aid ?: 0L
            val resolvedBvid = bvid.takeIf { it.isNotBlank() } ?: initialVideo?.bvid.orEmpty()
            val resolvedCid = cid.takeIf { it > 0L } ?: initialVideo?.cid ?: 0L
            val resolvedEpId = epId.takeIf { it > 0L } ?: initialVideo?.playbackEpId ?: 0L
            val resolvedSeasonId = seasonId.takeIf { it > 0L }
                ?: initialVideo?.playbackSeasonId
                ?: 0L
            pendingPlayQueue = playQueue.filter(::isPlayableVideo)
            val intent = Intent(context, PlayerActivity::class.java).apply {
                if (context !is Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(EXTRA_AID, resolvedAid)
                putExtra(EXTRA_BVID, resolvedBvid)
                putExtra(EXTRA_CID, resolvedCid)
                putExtra(EXTRA_EP_ID, resolvedEpId)
                putExtra(EXTRA_SEASON_ID, resolvedSeasonId)
                putExtra(EXTRA_SEEK_MS, seekPositionMs.coerceAtLeast(0L))
                putExtra(EXTRA_START_EPISODE, startEpisodeIndex)
                putExtra(EXTRA_STARTUP_TRACE_ID, startupTraceId)
                putExtra(EXTRA_STARTUP_TRACE_START_MS, startupTraceStartElapsedMs)
            }
            val options = ActivityOptionsCompat.makeCustomAnimation(
                context, R.anim.slide_in_to_right, R.anim.slide_out_to_left
            )
            if (context is Activity) {
                context.startActivity(intent, options.toBundle())
            } else {
                context.startActivity(intent)
            }
        }

        fun start(
            context: Context,
            video: VideoModel,
            seekPositionMs: Long = 0L,
            playQueue: List<VideoModel> = emptyList(),
            startEpisodeIndex: Int = -1,
            startupTraceId: String = PlaybackStartupTrace.NO_TRACE,
            startupTraceStartElapsedMs: Long = 0L
        ) {
            start(
                context = context,
                aid = video.aid,
                bvid = video.bvid,
                cid = video.cid,
                epId = video.playbackEpId,
                seasonId = video.playbackSeasonId,
                seekPositionMs = seekPositionMs,
                initialVideo = video,
                playQueue = playQueue,
                startEpisodeIndex = startEpisodeIndex,
                startupTraceId = startupTraceId,
                startupTraceStartElapsedMs = startupTraceStartElapsedMs
            )
        }

        fun start(
            context: Context,
            bvid: String,
            cid: Long,
            epId: Long = 0L,
            seasonId: Long = 0L,
            seekPositionMs: Long = 0L
        ) {
            start(
                context = context,
                aid = 0L,
                bvid = bvid,
                cid = cid,
                epId = epId,
                seasonId = seasonId,
                seekPositionMs = seekPositionMs
            )
        }

        fun buildPlayQueue(items: List<VideoModel>, current: VideoModel): ArrayList<VideoModel> {
            if (items.isEmpty()) {
                return arrayListOf()
            }
            val currentIndex = items.indexOfFirst { isSameVideo(it, current) }
            val queueSource = when {
                currentIndex >= 0 && currentIndex < items.lastIndex -> items.subList(currentIndex + 1, items.size)
                currentIndex >= 0 -> emptyList()
                else -> items.filterNot { isSameVideo(it, current) }
            }
            return ArrayList(queueSource.filter(::isPlayableVideo))
        }

        private fun isPlayableVideo(video: VideoModel): Boolean {
            return video.hasPlaybackIdentity
        }

        private fun isSameVideo(left: VideoModel, right: VideoModel): Boolean {
            return when {
                left.playbackEpId > 0L && right.playbackEpId > 0L -> left.playbackEpId == right.playbackEpId
                left.bvid.isNotBlank() && right.bvid.isNotBlank() -> left.bvid == right.bvid
                left.aid > 0L && right.aid > 0L -> left.aid == right.aid
                left.cid > 0L && right.cid > 0L -> left.cid == right.cid
                else -> left.title == right.title && left.coverUrl == right.coverUrl
            }
        }
    }

    private val appEventHub: AppEventHub by inject()
    private val videoRepository: com.tutu.myblbl.repository.VideoRepository by inject()

    override fun getViewBinding(): FragmentVideoPlayerBinding =
        FragmentVideoPlayerBinding.inflate(layoutInflater)

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
    private var exitTime: Long = 0
    private val exitInterval = 2000L

    private val gaiaVgateLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val gaiaVtoken = result.data?.getStringExtra(GaiaVgateActivity.EXTRA_GAIA_VTOKEN)
            if (!gaiaVtoken.isNullOrBlank()) {
                viewModel.onGaiaVgateResult(gaiaVtoken)
            }
        }
    }

    private val resumePlaybackRunnable = Runnable {
        resumePlaybackIfNeeded()
    }

    private val progressCoordinator = VideoPlayerProgressCoordinator(
        playerProvider = { player },
        publishProgressStateProvider = { bottomProgressBar.isVisible },
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
            if (trace == null || trace.firstLoadStartedMs != 0L) return
            trace.firstLoadStartedMs = System.currentTimeMillis()
            AppLog.i("VideoPlayerViewModel", "PLAYER_PERF [A] load started +${trace.firstLoadStartedMs - trace.prepareMs}ms dataType=${mediaLoadData.dataType}")
        }

        override fun onLoadCompleted(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData
        ) {
            val trace = playerPerfTrace
            if (trace == null || trace.firstLoadCompletedMs != 0L) return
            trace.firstLoadCompletedMs = System.currentTimeMillis()
            AppLog.i("VideoPlayerViewModel", "PLAYER_PERF [B] load completed +${trace.firstLoadCompletedMs - trace.prepareMs}ms bytes=${loadEventInfo.bytesLoaded}")
        }

        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            elapsedInitializationMs: Long
        ) {
            val trace = playerPerfTrace ?: return
            trace.videoDecoderInitMs = System.currentTimeMillis()
            AppLog.i("VideoPlayerViewModel", "PLAYER_PERF [C] video decoder ($decoderName) hw=${elapsedInitializationMs}ms +${trace.videoDecoderInitMs - trace.prepareMs}ms")
        }

        override fun onAudioDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            elapsedInitializationMs: Long
        ) {
            val trace = playerPerfTrace ?: return
            trace.audioDecoderInitMs = System.currentTimeMillis()
            AppLog.i("VideoPlayerViewModel", "PLAYER_PERF [C] audio decoder ($decoderName) hw=${elapsedInitializationMs}ms +${trace.audioDecoderInitMs - trace.prepareMs}ms")
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
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
                    if (player?.playWhenReady == true) {
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
            if (isPlaying) {
                playerView.resumeDanmaku()
                progressCoordinator.restart()
            } else {
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

    private var initialized = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        val aid = intent.getLongExtra(EXTRA_AID, 0L)
        val bvid = intent.getStringExtra(EXTRA_BVID).orEmpty()
        val cid = intent.getLongExtra(EXTRA_CID, 0L)
        val epId = intent.getLongExtra(EXTRA_EP_ID, 0L)
        val seasonId = intent.getLongExtra(EXTRA_SEASON_ID, 0L)
        val playQueue = pendingPlayQueue
        pendingPlayQueue = emptyList()
        val startupTraceId = intent.getStringExtra(EXTRA_STARTUP_TRACE_ID).orEmpty()
        val startupTraceStartMs = intent.getLongExtra(EXTRA_STARTUP_TRACE_START_MS, 0L)
        PlaybackStartupTrace.log(
            traceId = startupTraceId,
            startElapsedMs = startupTraceStartMs,
            step = "activity_handle_intent",
            message = "aid=$aid bvid=$bvid cid=$cid epId=$epId seasonId=$seasonId"
        )
        if (aid <= 0L && bvid.isBlank() && epId <= 0L && seasonId <= 0L) {
            finish()
            return
        }

        if (!initialized) {
            initialized = true
            initViews()
            playerSettings = PlayerSettingsStore.load(this)
            setupAdapters()
            setupOverlayController()
            setupPlayer()
            setupObservers()
            binding.root.post {
                setupBackHandler()
            }
        }

        startPlayback(
            aid = aid,
            bvid = bvid,
            cid = cid,
            seasonId = seasonId,
            epId = epId,
            playQueue = playQueue,
            seekPositionMs = intent.getLongExtra(EXTRA_SEEK_MS, 0L),
            startEpisodeIndex = intent.getIntExtra(EXTRA_START_EPISODE, -1),
            startupTraceId = startupTraceId,
            startupTraceStartElapsedMs = startupTraceStartMs
        )
    }

    private fun startPlayback(
        aid: Long,
        bvid: String,
        cid: Long,
        seasonId: Long,
        epId: Long,
        playQueue: List<VideoModel>,
        seekPositionMs: Long,
        startEpisodeIndex: Int,
        startupTraceId: String,
        startupTraceStartElapsedMs: Long
    ) {
        playerView.getController()?.hideImmediately()
        playerView.pauseDanmaku()
        tagCheckDoneForCurrentVideo = false
        sessionCoordinator.replacePlayQueue(playQueue)
        viewModel.loadVideoInfo(
            aid = aid,
            bvid = bvid,
            cid = cid,
            seasonId = seasonId,
            epId = epId,
            seekPositionMs = seekPositionMs,
            startEpisodeIndex = startEpisodeIndex,
            startupTraceId = startupTraceId,
            startupTraceStartElapsedMs = startupTraceStartElapsedMs,
            isSteinsGate = playQueue.firstOrNull()?.isSteinsGate == true
        )
    }

    private fun initViews() {
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
        textMoreTitle = binding.root.findViewById(R.id.title_more)
        buttonCloseRelated = binding.root.findViewById(R.id.button_close_related)
        imageNext = binding.root.findViewById(R.id.imageView_next)
        textNext = binding.root.findViewById(R.id.text_next)
        countdownView = binding.root.findViewById(R.id.countdown_view)
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
        buttonCloseRelated.setOnClickListener { hideContentPanel() }
    }

    private fun setupAdapters() {
        relatedAdapter = VideoAdapter(
            itemWidthPx = (resources.displayMetrics.widthPixels / 5).coerceAtLeast(1)
        )
        relatedAdapter.setOnItemClickListener { _, item ->
            playerView.hideController()
            hideContentPanel()
            hideNextPreview()
            sessionCoordinator.replacePlayQueue(buildPlayQueue(relatedAdapter.getItemsSnapshot(), item))
            sessionCoordinator.updateCurrentVideo(item)
            viewModel.playRelatedVideo(item)
        }
    }

    private fun setupOverlayController() {
        autoPlayController = VideoPlayerAutoPlayController(
            activity = this,
            viewNext = viewNext,
            imageNext = imageNext,
            textNext = textNext,
            countdownView = countdownView,
            canExecutePendingAction = { player?.playbackState == Player.STATE_ENDED },
            onPendingActionCleared = { viewModel.preloadPlayback(null) }
        )
        overlayUiController = VideoPlayerOverlayController(
            activity = this,
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
            onOpenFragmentFromHost = { _, _ -> },
            onHideNextPreview = { autoPlayController.hideNextPreview() },
            isViewActive = { true }
        )
        resumeHintController = VideoPlayerResumeHintController(
            activity = this,
            playerProvider = { player },
            onCancelResume = { viewModel.cancelResumeProgress() },
            onClearResumeHint = { viewModel.clearResumeHint() }
        )
    }

    private fun setupPlayer() {
        player = PlayerInstancePool.acquire(this).also {
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
                    val total = trace.firstFrameMs - trace.prepareMs
                    val cdn = if (trace.firstLoadStartedMs > 0) trace.firstLoadStartedMs - trace.prepareMs else -1
                    val buffer = if (trace.firstLoadStartedMs > 0 && trace.firstLoadCompletedMs > 0) trace.firstLoadCompletedMs - trace.firstLoadStartedMs else -1
                    val decoder = if (trace.firstLoadCompletedMs > 0 && trace.videoDecoderInitMs > 0) trace.videoDecoderInitMs - trace.firstLoadCompletedMs else -1
                    val render = if (trace.videoDecoderInitMs > 0) trace.firstFrameMs - trace.videoDecoderInitMs else -1
                    AppLog.i("VideoPlayerViewModel", "PLAYER_PERF breakdown: total=${total}ms cdn=${cdn}ms buffer=${buffer}ms decoder=${decoder}ms render=${render}ms")
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
        playerView.onResumeProgressCancelled = { cancelResume() }
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
                val snapshot = capturePlaybackSnapshot()
                viewModel.selectVideoQuality(quality = quality, currentPositionMs = snapshot.first, playWhenReady = snapshot.second)
                playerView.showHideSettingView(false)
            }
            override fun onAudioQualityChange(quality: AudioQuality) {
                val snapshot = capturePlaybackSnapshot()
                viewModel.selectAudioQuality(quality = quality, currentPositionMs = snapshot.first, playWhenReady = snapshot.second)
                playerView.showHideSettingView(false)
            }
            override fun onPlaybackSpeedChange(speed: Float) { playerView.setPlaySpeed(speed) }
            override fun onSubtitleChange(position: Int) {
                viewModel.selectSubtitle(position)
                playerView.showHideSettingView(false)
            }
            override fun onVideoCodecChange(codec: VideoCodecEnum) {
                val snapshot = capturePlaybackSnapshot()
                viewModel.selectVideoCodec(codec = codec, currentPositionMs = snapshot.first, playWhenReady = snapshot.second)
                playerView.showHideSettingView(false)
            }
            override fun onAspectRatioChange(ratio: Int) {}
            override fun onScreenMirrorChange(enabled: Boolean) {
                playerView.setMirrorEnabled(enabled)
            }
        })
        playerView.setOnVideoSettingChangeListener(object : OnVideoSettingChangeListener {
            override fun onPrevious() { viewModel.playPrevious() }
            override fun onNext() { viewModel.playNext() }
            override fun onClose() { finish() }
            override fun onChooseEpisode() { showChooseEpisodeDialog() }
            override fun onRelated() { showRelatedPanel() }
            override fun onUpInfo() { showOwnerDetailDialog() }
            override fun onMore() { showPlayerActionDialog() }
            override fun onVideoInfo() { showVideoInfoDialog() }
            override fun onSubtitle() {
                if (viewModel.subtitles.value.isNotEmpty()) {
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
                Toast.makeText(applicationContext, if (currentPlayer.repeatMode == Player.REPEAT_MODE_ONE) "单集循环" else "顺序播放", Toast.LENGTH_SHORT).show()
            }
            override fun onDmEnableChange(enabled: Boolean) { playerView.setDanmakuEnabled(enabled) }
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
                        .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                        .build()
                } else {
                    val pos = p.currentPosition
                    p.trackSelectionParameters = p.trackSelectionParameters
                        .buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
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
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                uiCoordinator.handleBackPress(
                    isSettingShowing = playerView.isSettingViewShowing(),
                    hideSetting = { playerView.showHideSettingView(false) },
                    isControllerFullyVisible = playerView.isControllerFullyVisible(),
                    hideController = { playerView.hideController() },
                    hidePanel = { hideContentPanel() },
                    exitPlayer = {
                        if (System.currentTimeMillis() - exitTime <= exitInterval) {
                            finish()
                        } else {
                            exitTime = System.currentTimeMillis()
                            Toast.makeText(applicationContext, "再按一次退出播放", Toast.LENGTH_SHORT).show()
                        }
                    },
                )
            }
        })
    }

    private var preloadHeaderRefreshPosted = false

    private fun setupObservers() {
        lifecycleScope.launch {
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

        lifecycleScope.launch {
            viewModel.riskControlVVoucher.collect { vVoucher ->
                if (vVoucher.isNullOrBlank()) return@collect
                viewModel.consumeRiskControlVVoucher() ?: return@collect
                AppLog.w(TAG, "risk-control v_voucher received, launching GaiaVgateActivity")
                val intent = Intent(this@PlayerActivity, GaiaVgateActivity::class.java).apply {
                    putExtra(GaiaVgateActivity.EXTRA_V_VOUCHER, vVoucher)
                }
                gaiaVgateLauncher.launch(intent)
            }
        }

        lifecycleScope.launch {
            viewModel.riskControlTryLookBypass.collect { bypassed ->
                if (!bypassed) return@collect
                if (riskControlUserHintShown.compareAndSet(false, true)) {
                    Toast.makeText(applicationContext, RISK_CONTROL_USER_HINT, Toast.LENGTH_LONG).show()
                }
            }
        }

        lifecycleScope.launch {
            viewModel.videoInfo.collect { info ->
                latestVideoInfo = info
                sessionCoordinator.updateVideoInfo(info)
                schedulePreloadAndHeaderRefresh()
                updatePrimaryActionVisibility()
                checkTagsAndExitIfNeeded(info)
            }
        }

        lifecycleScope.launch {
            viewModel.isLoading.collect { loading ->
                latestLoadingState = loading
                renderDebugState()
            }
        }

        lifecycleScope.launch {
            viewModel.error.collect { error ->
                latestErrorMessage = error
                if (!error.isNullOrBlank()) {
                    AppLog.e(TAG, "viewModel error: $error")
                }
                playerView.setCustomErrorMessage(error)
                renderDebugState()
            }
        }

        lifecycleScope.launch { viewModel.currentPosition.collect { positionMs -> latestPlaybackPositionMs = positionMs.coerceAtLeast(0L); renderBottomProgressBar() } }
        lifecycleScope.launch { viewModel.duration.collect { durationMs -> latestPlaybackDurationMs = durationMs.coerceAtLeast(0L); renderBottomProgressBar() } }

        lifecycleScope.launch {
            viewModel.currentSubtitleText.collect { subtitle ->
                val visible = !subtitle.isNullOrBlank()
                textSubtitle.isVisible = visible
                textSubtitle.text = subtitle.orEmpty()
                if (::playerSettings.isInitialized) {
                    textSubtitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, playerSettings.subtitleTextSizePx.toFloat())
                }
            }
        }

        lifecycleScope.launch {
            viewModel.qualities.collect { qualities -> playerView.setQualities(qualities) }
        }
        lifecycleScope.launch {
            viewModel.selectedQuality.collect { quality -> quality?.let(playerView::selectQuality) }
        }
        lifecycleScope.launch {
            viewModel.audioQualities.collect { qualities -> playerView.setAudiosSelect(qualities) }
        }
        lifecycleScope.launch {
            viewModel.selectedAudioQuality.collect { quality -> quality?.let(playerView::selectAudio) }
        }
        lifecycleScope.launch {
            viewModel.videoCodecs.collect { codecs -> playerView.setVideoCodec(codecs) }
        }
        lifecycleScope.launch {
            viewModel.selectedVideoCodec.collect { codec -> codec?.let(playerView::selectVideoCodec) }
        }

        lifecycleScope.launch {
            viewModel.subtitles.collect { subtitles ->
                playerView.setSubtitles(subtitles)
                playerView.showHideSubtitleButton(subtitles.isNotEmpty())
            }
        }
        lifecycleScope.launch {
            viewModel.selectedSubtitleIndex.collect { index ->
                playerView.selectSubtitle(index)
            }
        }

        lifecycleScope.launch {
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

        lifecycleScope.launch {
            viewModel.danmaku.collect {
                updateDanmakuSwitchVisibility()
            }
        }

        viewModel.onDmMaskReady = { maskUrl, cid, fps ->
            playerView.setDmMaskRepository(viewModel.dmMaskRepository)
            lifecycleScope.launch {
                val success = playerView.loadDmMask(maskUrl, cid, fps)
                AppLog.d(TAG, "loadDmMask result: $success, cid=$cid")
            }
        }
        viewModel.onDmMaskReset = {
            playerView.releaseDmMask()
        }

        lifecycleScope.launch {
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

        lifecycleScope.launch {
            viewModel.episodes.collect { episodes ->
                sessionCoordinator.updateEpisodes(episodes)
                schedulePreloadAndHeaderRefresh()
                playerView.showHideEpisodeButton(episodes.isNotEmpty())
                updateEpisodeNavigationVisibility()
            }
        }
        lifecycleScope.launch {
            viewModel.selectedEpisodeIndex.collect { index ->
                sessionCoordinator.updateSelectedEpisodeIndex(index)
                schedulePreloadAndHeaderRefresh()
                updateEpisodeNavigationVisibility()
            }
        }

        lifecycleScope.launch {
            viewModel.relatedVideos.collect { rawRelated ->
                val related = ContentFilter.filterVideos(this@PlayerActivity, rawRelated)
                sessionCoordinator.updateRelatedVideos(related)
                schedulePreloadAndHeaderRefresh()
                relatedAdapter.setData(related)
                playerView.showHideRelatedButton(related.isNotEmpty())
            }
        }

        lifecycleScope.launch {
            viewModel.resumeHint.collect { hint ->
                // Toast 已在 ViewModel 中直接显示，仅消费 hint
                if (hint != null) viewModel.clearResumeHint()
            }
        }

        lifecycleScope.launch {
            viewModel.interactionModel.collect { model ->
                if (model == null) {
                    interactionView.hideAll()
                } else {
                    interactionView.visibility = View.VISIBLE
                    interactionView.onNodeLoaded(model)
                }
            }
        }

        lifecycleScope.launch {
            viewModel.interactionHiddenVars.collect { hiddenVars ->
                interactionView.updateVariablesDisplay(hiddenVars)
            }
        }

        lifecycleScope.launch {
            viewModel.videoSnapshot.collect { snapshot ->
                snapshot?.let { playerView.setSeekPreviewSnapshot(it) }
            }
        }

        lifecycleScope.launch {
            viewModel.sponsorSkipState.collect { state ->
                when (state) {
                    is VideoPlayerViewModel.SponsorSkipUiState.Hidden -> {}
                    is VideoPlayerViewModel.SponsorSkipUiState.ShowButton -> {}
                    is VideoPlayerViewModel.SponsorSkipUiState.AutoSkipped -> {
                        toast("已跳过: ${state.segment.categoryName()}")
                        player?.seekTo(state.segment.endTimeMs)
                    }
                }
            }
        }

        lifecycleScope.launch {
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
        lifecycleScope.launch {
            viewModel.duration.collect { durationMs ->
                playerView.getController()?.setSponsorDuration(durationMs)
                if (::slimTimelineRenderer.isInitialized) {
                    slimTimelineRenderer.setSponsorDuration(durationMs)
                }
            }
        }
    }

    // --- Lifecycle ---

    override fun onStart() {
        super.onStart()
        // 重新绑定 video surface，恢复视频解码器
        playerView.reattachVideoSurface()
        resumePlaybackIfNeeded()
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
        player?.pause()
        player?.playWhenReady = false
        playerView.stopDanmaku()
        stopProgressUpdates()
        // 提前释放视频解码器，避免后台持有硬件解码器资源
        playerView.detachVideoSurface()
        syncPlaybackEnvironment()
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.slide_in_to_left, R.anim.slide_out_to_right)
    }

    override fun onDestroy() {
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
        ViewUtils.keepScreenOn(this, false)
        viewRelated.clearAnimation()
        player = null
        progressCoordinator.reset()
        super.onDestroy()
    }

    // --- Helper methods (delegated from Fragment logic) ---

    private data class StartupTrace(
        val sequence: Int,
        val startedAtMs: Long,
        var firstFrameLogged: Boolean = false,
        var readyLogged: Boolean = false
    )

    private var playerPerfTrace: PlayerPerfTrace? = null
    private var activeStartupTraceId: String = PlaybackStartupTrace.NO_TRACE
    private var activeStartupTraceStartElapsedMs: Long = 0L
    private var activeStartupFirstFrameLogged: Boolean = false

    private data class PlayerPerfTrace(
        val prepareMs: Long,
        var firstLoadStartedMs: Long = 0L,
        var firstLoadCompletedMs: Long = 0L,
        var videoDecoderInitMs: Long = 0L,
        var audioDecoderInitMs: Long = 0L,
        var firstFrameMs: Long = 0L
    )

    private fun updatePlaybackPreload() {
        viewModel.preloadPlayback(sessionCoordinator.buildPreloadTarget())
    }

    private fun schedulePreloadAndHeaderRefresh() {
        if (preloadHeaderRefreshPosted) return
        preloadHeaderRefreshPosted = true
        binding.root.post {
            preloadHeaderRefreshPosted = false
            updatePlaybackPreload()
            renderPlayerHeader()
        }
    }

    private var tagCheckDoneForCurrentVideo = false

    private fun checkTagsAndExitIfNeeded(info: VideoDetailModel?) {
        if (info == null || tagCheckDoneForCurrentVideo) return
        val view = info.view ?: return
        if (view.aid <= 0L && view.bvid.isBlank()) return
        tagCheckDoneForCurrentVideo = true

        if (!ContentFilter.isBlockedByTags(this, info.tags)) return

        AppLog.i(TAG, "Video blocked by tags: aid=${view.aid}, bvid=${view.bvid}, tags=${info.tags?.map { it.tagName }}")
        ContentFilter.addBlockedVideo(
            this,
            aid = view.aid,
            bvid = view.bvid,
            title = view.title,
            coverUrl = view.pic
        )
        appEventHub.dispatch(AppEventHub.Event.VideoBlockedByMinorProtection(
            aid = view.aid,
            bvid = view.bvid
        ))
        lifecycleScope.launch {
            runCatching {
                val video = VideoModel(aid = view.aid, bvid = view.bvid, title = view.title, pic = view.pic)
                videoRepository.dislikeFeed(video, 1)
            }
            finish()
        }
    }

    private fun updatePrimaryActionVisibility() {
        val view = latestVideoInfo?.view
        val hasOwner = view?.owner?.mid?.let { it > 0L } == true
        val hasVideoIdentity = (view?.aid ?: 0L) > 0L || !view?.bvid.isNullOrBlank()
        playerView.setShowHideOwnerInfo(hasOwner)
        playerView.showHideActionButton(hasVideoIdentity)
        playerView.showSettingButton(hasVideoIdentity)
    }

    private fun updateDanmakuSwitchVisibility() {
        val hasDanmaku = viewModel.danmaku.value.isNotEmpty() ||
            viewModel.specialDanmaku.value.isNotEmpty()
        playerView.showHideDmSwitchButton(playerSettings.showDanmakuSwitch && hasDanmaku)
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
        val episodeTitle = selectedEpisode?.title?.trim().orEmpty()
        if (episodeTitle.isBlank() || episodeTitle == videoTitle) {
            return videoTitle
        }
        return "$episodeTitle ｜ $videoTitle"
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

    private fun formatCount(count: Long): String {
        return when {
            count >= 100000000L -> String.format(Locale.getDefault(), "%.1f亿", count / 100000000.0)
            count >= 10000L -> String.format(Locale.getDefault(), "%.1f万", count / 10000.0)
            else -> count.toString()
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
        ViewUtils.keepScreenOn(this, keepScreenOn)
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

    private fun capturePlaybackSnapshot(): Pair<Long, Boolean> {
        val positionMs = player?.currentPosition?.coerceAtLeast(0L) ?: 0L
        val playWhenReady = player?.playWhenReady ?: resumePlaybackWhenStarted
        return positionMs to playWhenReady
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

    private fun resumePlaybackIfNeeded() {
        val currentPlayer = player ?: return
        if (!resumePlaybackWhenStarted) {
            return
        }
        if (currentPlayer.playbackState == Player.STATE_ENDED) {
            return
        }
        if (currentPlayer.playbackState == Player.STATE_IDLE) {
            currentPlayer.prepare()
        }
        currentPlayer.playWhenReady = true
        currentPlayer.play()
    }

    private fun restartProgressUpdates() { progressCoordinator.restart() }
    private fun stopProgressUpdates() { progressCoordinator.stop(); autoPlayController.cancelPendingAction() }

    private fun recoverFromPlaybackStall(positionMs: Long, stalledMs: Long) {
        val currentPlayer = player ?: return
        if (stalledMs > 3000L) {
            currentPlayer.seekTo(positionMs + stalledMs)
        }
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
            is PlayerSessionCoordinator.ContinuationPlan.ExitPlayer -> finish()
            is PlayerSessionCoordinator.ContinuationPlan.ShowController -> {
                playerView.showController()
            }
        }
    }

    private fun hideNextPreview() { autoPlayController.hideNextPreview() }

    private fun hideContentPanel() {
        overlayUiController.hideContentPanel()
    }

    private fun showChooseEpisodeDialog() { overlayUiController.showChooseEpisodeDialog() }
    private fun showRelatedPanel() { overlayUiController.showRelatedPanel() }
    private fun showOwnerDetailDialog() { overlayUiController.showOwnerDetailDialog() }
    private fun showPlayerActionDialog() { overlayUiController.showPlayerActionDialog() }
    private fun showVideoInfoDialog() { overlayUiController.showVideoInfoDialog() }

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
