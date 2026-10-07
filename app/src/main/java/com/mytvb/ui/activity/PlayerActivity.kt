@file:Suppress("SpellCheckingInspection", "OVERRIDE_DEPRECATION")

package com.mytvb.ui.activity

import com.mytvb.feature.player.PlayerScreenLogic
import com.mytvb.feature.player.PlayerContentGuards
import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.core.app.ActivityOptionsCompat
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.util.Printer
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import com.mytvb.feature.player.sponsor.SponsorProgressMarkerView
import android.widget.TextView
import android.widget.Toast
import com.mytvb.core.common.ext.toast
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
import com.mytvb.R
import com.mytvb.core.common.content.ContentFilter
import com.mytvb.core.common.log.AppLog
import com.mytvb.core.ui.base.BaseActivity
import com.mytvb.core.ui.base.ScaledTextView
import com.mytvb.core.ui.system.ViewUtils
import com.mytvb.databinding.FragmentVideoPlayerBinding
import com.mytvb.event.AppEventHub
import com.mytvb.feature.player.PlayerInstancePool
import com.mytvb.feature.player.PlaybackStartSeekResolver
import com.mytvb.feature.player.PlaybackStartupTrace
import com.mytvb.feature.player.PlaybackUiCoordinator
import com.mytvb.feature.player.PlayerOverlayCoordinator
import com.mytvb.feature.player.PlayerSessionCoordinator
import com.mytvb.feature.player.SeekSession
import com.mytvb.feature.player.SlimTimelineRenderer
import com.mytvb.feature.player.VideoPlayerAutoPlayController
import com.mytvb.feature.player.VideoPlayerOverlayController
import com.mytvb.feature.player.VideoPlayerProgressCoordinator
import com.mytvb.feature.player.VideoPlayerResumeHintController
import com.mytvb.feature.player.VideoPlayerViewModel
import com.mytvb.feature.player.settings.AfterPlayMode
import com.mytvb.feature.player.settings.PlayerSettings
import com.mytvb.feature.player.settings.PlayerSettingsStore
import com.mytvb.feature.player.interaction.InteractionOverlayView
import com.mytvb.feature.player.PlaybackPreloadTarget
import com.mytvb.feature.player.douyin.DouyinModeManager
import com.mytvb.feature.player.douyin.DouyinPlaybackCoordinator
import com.mytvb.feature.player.view.DouyinModeKeyListener
import com.mytvb.feature.player.view.DouyinModePreview
import com.mytvb.feature.player.view.MyPlayerView
import com.mytvb.feature.player.view.OnPlayerSettingChange
import com.mytvb.feature.player.view.OnVideoSettingChangeListener
import com.mytvb.model.video.VideoModel
import com.mytvb.model.video.detail.VideoDetailModel
import com.mytvb.model.video.quality.AudioQuality
import com.mytvb.model.video.quality.VideoCodecEnum
import com.mytvb.model.video.quality.VideoQuality
import com.mytvb.ui.adapter.VideoAdapter
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

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
        /** 青少年模式-公益广告锁死模式：屏蔽所有操作，播完自动退出。 */
        private const val EXTRA_PSAS_LOCKED = "player_psas_locked"
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
            startupTraceStartElapsedMs: Long = 0L,
            psasLocked: Boolean = false
        ) {
            val resolvedAid = aid.takeIf { it > 0L } ?: initialVideo?.aid ?: 0L
            val resolvedBvid = bvid.takeIf { it.isNotBlank() } ?: initialVideo?.bvid.orEmpty()
            val resolvedCid = cid.takeIf { it > 0L } ?: initialVideo?.cid ?: 0L
            val resolvedEpId = epId.takeIf { it > 0L } ?: initialVideo?.playbackEpId ?: 0L
            val resolvedSeasonId = seasonId.takeIf { it > 0L }
                ?: initialVideo?.playbackSeasonId
                ?: 0L
            // 锁死模式（公益广告）不污染全局播放队列，避免影响下次正常播放
            if (!psasLocked) {
                pendingPlayQueue = playQueue.filter(::isPlayableVideo)
            }
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
                if (psasLocked) putExtra(EXTRA_PSAS_LOCKED, true)
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
    private val videoRepository: com.mytvb.repository.VideoRepository by inject()
    private val douyinModeManager: DouyinModeManager by inject()

    override fun getViewBinding(): FragmentVideoPlayerBinding {
        val startMs = SystemClock.elapsedRealtime()
        return FragmentVideoPlayerBinding.inflate(layoutInflater).also {
            AppLog.i(TAG, "PLAYER_STARTUP binding inflate elapsed=${SystemClock.elapsedRealtime() - startMs}ms")
        }
    }

    private val viewModel: VideoPlayerViewModel by viewModel()

    private var player: ExoPlayer? = null
    private val uiCoordinator = PlaybackUiCoordinator()

    // coordinator 状态（面板开关、chrome 超时等）变化后即时重算字幕让位高度
    private val coordinatorStateListener = object : PlaybackUiCoordinator.OnStateChangedListener {
        override fun onStateChanged(coordinator: PlaybackUiCoordinator) {
            if (::textSubtitle.isInitialized && ::playerSettings.isInitialized) {
                renderControllerChrome()
            }
        }
    }
    private val overlayCoordinator = PlayerOverlayCoordinator()

    private lateinit var playerView: MyPlayerView
    private lateinit var bottomProgressBar: SponsorProgressMarkerView
    private lateinit var textSubtitle: TextView
    private lateinit var viewNext: View
    private lateinit var viewRelated: View
    private lateinit var recyclerViewRelated: RecyclerView
    private lateinit var textMoreTitle: TextView
    private lateinit var buttonCloseRelated: View
    private lateinit var imageNext: AppCompatImageView
    private lateinit var textNext: TextView
    private lateinit var countdownView: com.mytvb.feature.player.view.CountdownView
    private lateinit var interactionView: InteractionOverlayView

    private lateinit var relatedAdapter: VideoAdapter
    private lateinit var autoPlayController: VideoPlayerAutoPlayController
    private lateinit var overlayUiController: VideoPlayerOverlayController
    private lateinit var resumeHintController: VideoPlayerResumeHintController

    private var latestVideoInfo: VideoDetailModel? = null
    private lateinit var playerSettings: PlayerSettings
    private var latestControllerVisibility: Int = View.GONE
    private var latestPlaybackPositionMs: Long = 0L
    private var latestPlaybackDurationMs: Long = 0L

    /** 青少年模式累计观看时长定时器：每 15 秒结算一次，达上限触发休息退出。 */
    private val teenModeHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val teenModeTicker = object : Runnable {
        override fun run() {
            com.mytvb.core.common.content.TeenModeTimer.tick()
            // 公益广告触发：达间隔且广告列表就绪 → 暂停原播放 + 启动锁死播放
            if (com.mytvb.core.common.content.TeenModeTimer.checkAndConsumePsasTrigger()) {
                player?.pause()
                if (com.mytvb.core.common.content.PsasRepository.launchRandomPsas(this@PlayerActivity)) {
                    // 启动成功，本次 tick 结束（onResume 会恢复播放）
                    teenModeHandler.postDelayed(this, 15_000L)
                    return
                }
            }
            teenModeHandler.postDelayed(this, 15_000L)
        }
    }

    private fun startTeenModeTicker() {
        // 公益广告锁死模式：自身不计入观看时长，也不触发休息/公益广告
        if (psasLocked) return
        teenModeHandler.removeCallbacks(teenModeTicker)
        teenModeHandler.postDelayed(teenModeTicker, 15_000L)
    }

    private fun stopTeenModeTicker() {
        teenModeHandler.removeCallbacks(teenModeTicker)
    }
    private var pendingRelatedVideosBeforeFirstFrame: List<VideoModel>? = null
    private lateinit var slimTimelineRenderer: SlimTimelineRenderer
    private val sessionCoordinator = PlayerSessionCoordinator()
    private var resumePlaybackWhenStarted: Boolean = false
    /**
     * 前台恢复（onStart）的起点时间戳，配合首帧渲染统计切后台回来后的黑屏时长。
     * 0 表示当前不在"等待恢复首帧"状态。
     */
    private var surfaceRecoverStartMs: Long = 0L
    /**
     * 标记 onResume 是否紧随 onStart（正常的前台进入/后台返回）。
     * onStart 已做 surface 恢复，紧随的 onResume 应跳过自愈，避免重复 seekTo。
     * 仅在 onPause→onResume（如系统弹窗返回、未走 onStop）的独立 onResume 里触发自愈。
     */
    private var resumedFromStart: Boolean = false
    private var startupTrace: StartupTrace? = null
    private var startupTraceSequence: Int = 0
    private var suppressPlaybackEnvironmentSync: Boolean = false
    private var lastKeepScreenOnState: Boolean? = null
    private var exitTime: Long = 0
    private val exitInterval = 2000L

    /** 青少年模式-公益广告锁死模式：屏蔽所有操作，播完自动退出。仅通过 EXTRA_PSAS_LOCKED 进入。 */
    private var psasLocked: Boolean = false

    // 抖音模式（编排逻辑已迁出至 DouyinPlaybackCoordinator）
    private lateinit var douyinCoordinator: DouyinPlaybackCoordinator

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
            if (publishProgressState) {
                latestPlaybackPositionMs = positionMs.coerceAtLeast(0L)
                latestPlaybackDurationMs = durationMs.coerceAtLeast(0L)
                renderBottomProgressBar()
            }
        },
        onPlaybackPositionChanged = { positionMs ->
            playerView.syncDanmakuPosition(positionMs)
            interactionView.onPositionUpdate(positionMs)
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
            // 仅记录首次初始化时刻用于 render 统计。解码器可能因 reattach/硬解探测被重建,
            // 若每次都覆盖 videoDecoderInitMs 会导致 render = 首帧 - 偏早时刻 = 虚高。
            val trace = playerPerfTrace
            if (trace != null && trace.videoDecoderInitMs == 0L) {
                trace.videoDecoderInitMs = System.currentTimeMillis()
                AppLog.i("VideoPlayerViewModel", "PLAYER_PERF [C] video decoder ($decoderName) hw=${elapsedInitializationMs}ms +${trace.videoDecoderInitMs - trace.prepareMs}ms")
            }
            if (decoderName.isSoftwareVideoDecoderName()) {
                val positionMs = player?.currentPosition ?: eventTime.eventPlaybackPositionMs
                AppLog.w(
                    "PlaybackPerf",
                    "software_decoder decoder=$decoderName pos=${positionMs}ms " +
                        "quality=${viewModel.selectedQuality.value?.id} codec=${viewModel.selectedVideoCodec.value}"
                )
                viewModel.onSoftwareVideoDecoderDetected(
                    decoderName = decoderName,
                    currentPositionMs = positionMs,
                    playWhenReady = player?.playWhenReady ?: true
                )
            }
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

        override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
            AppLog.i(
                "PlaybackPerf",
                "state=${state.nameOfPlaybackState()} pos=${eventTime.eventPlaybackPositionMs}ms " +
                    "buffered=${player?.bufferedPosition ?: -1L}ms playWhenReady=${player?.playWhenReady}"
            )
        }

        override fun onAudioUnderrun(
            eventTime: AnalyticsListener.EventTime,
            bufferSize: Int,
            bufferSizeMs: Long,
            elapsedSinceLastFeedMs: Long
        ) {
            AppLog.w(
                "PlaybackPerf",
                "audio_underrun pos=${eventTime.eventPlaybackPositionMs}ms " +
                    "bufferSize=${bufferSize} bufferSizeMs=${bufferSizeMs} " +
                    "elapsedSinceLastFeedMs=${elapsedSinceLastFeedMs} " +
                    "state=${player?.playbackState?.nameOfPlaybackState()} isPlaying=${player?.isPlaying}"
            )
        }

        override fun onAudioSinkError(eventTime: AnalyticsListener.EventTime, audioSinkError: Exception) {
            AppLog.w(
                "PlaybackPerf",
                "audio_sink_error pos=${eventTime.eventPlaybackPositionMs}ms " +
                    "error=${audioSinkError.javaClass.simpleName}:${audioSinkError.message}"
            )
        }

        override fun onDroppedVideoFrames(
            eventTime: AnalyticsListener.EventTime,
            droppedFrames: Int,
            elapsedMs: Long
        ) {
            AppLog.w(
                "PlaybackPerf",
                "video_dropped_frames pos=${eventTime.eventPlaybackPositionMs}ms " +
                    "dropped=${droppedFrames} windowMs=${elapsedMs}"
            )
        }

        override fun onVideoFrameProcessingOffset(
            eventTime: AnalyticsListener.EventTime,
            totalProcessingOffsetUs: Long,
            frameCount: Int
        ) {
            if (frameCount <= 0) return
            val avgOffsetMs = totalProcessingOffsetUs / frameCount / 1000L
            if (avgOffsetMs > 20L) {
                AppLog.w(
                    "PlaybackPerf",
                    "video_frame_offset pos=${eventTime.eventPlaybackPositionMs}ms " +
                        "avgOffsetMs=${avgOffsetMs} frames=${frameCount}"
                )
            }
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
                    maybeShowPreviewHint()
                    hideNextPreview()
                }
                Player.STATE_ENDED -> {
                    viewModel.setLoading(false)
                    playerView.stopDanmaku()
                    // 播完瞬间强制补发收尾心跳+直写历史（对齐 blbl），需覆盖下方提前 return 的路径。
                    progressCoordinator.syncNow()
                    viewModel.reportPlaybackHeartbeat(force = true)
                    // 公益广告锁死模式：播完唯一出口，先停播放器再退出，避免被 repeatMode/连播逻辑重新触发
                    if (psasLocked) {
                        player?.repeatMode = Player.REPEAT_MODE_OFF
                        player?.pause()
                        finish()
                        return
                    }
                    // 试看流播完：提示后停在控制器，不自动连播（连播只会得到下一集的又一段试看）
                    if (viewModel.isPreviewPlayback) {
                        AppLog.i(TAG, "preview playback ended, show controller instead of autoplay")
                        Toast.makeText(
                            applicationContext,
                            R.string.activity_preview_ended_hint,
                            Toast.LENGTH_LONG
                        ).show()
                        playerView.showController()
                        return
                    }
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
            // 不在此同步 resumePlaybackWhenStarted：该字段语义是"切到后台前的播放意图"，
            // 仅由 onStop 快照写入、onStart 读取恢复。若在此实时覆盖，onStop 里
            // player.playWhenReady=false 会触发本回调把它改成 false，导致回到前台后
            // 弹幕和播放都不恢复（实测切后台回来弹幕消失）。
            syncPlaybackEnvironment()
        }
    }

    private var initialized = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installMainThreadJankMonitor()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        psasLocked = intent.getBooleanExtra(EXTRA_PSAS_LOCKED, false)
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

        val seekPositionMs = intent.getLongExtra(EXTRA_SEEK_MS, 0L)
        val startEpisodeIndex = intent.getIntExtra(EXTRA_START_EPISODE, -1)
        val shouldStartLoadBeforeUiInit = !initialized
        if (shouldStartLoadBeforeUiInit) {
            tagCheckDoneForCurrentVideo = false
            sessionCoordinator.replacePlayQueue(playQueue)
            beginPlaybackLoad(
                aid = aid,
                bvid = bvid,
                cid = cid,
                seasonId = seasonId,
                epId = epId,
                playQueue = playQueue,
                seekPositionMs = seekPositionMs,
                startEpisodeIndex = startEpisodeIndex,
                startupTraceId = startupTraceId,
                startupTraceStartElapsedMs = startupTraceStartMs
            )
        }

        if (!initialized) {
            initialized = true
            val initStartMs = SystemClock.elapsedRealtime()
            initViews()
            val settingsStartMs = SystemClock.elapsedRealtime()
            playerSettings = PlayerSettingsStore.load(this)
            uiCoordinator.addListener(coordinatorStateListener)
            AppLog.i(TAG, "PLAYER_STARTUP PlayerSettingsStore.load elapsed=${SystemClock.elapsedRealtime() - settingsStartMs}ms")
            setupAdapters()
            setupOverlayController()
            val setupPlayerStartMs = SystemClock.elapsedRealtime()
            setupPlayer()
            AppLog.i(TAG, "PLAYER_STARTUP setupPlayer elapsed=${SystemClock.elapsedRealtime() - setupPlayerStartMs}ms")
            setupObservers()
            AppLog.i(TAG, "PLAYER_STARTUP first init block elapsed=${SystemClock.elapsedRealtime() - initStartMs}ms")
            binding.root.post {
                setupBackHandler()
            }
            preparePlaybackUiForNewRequest()
        }

        if (!shouldStartLoadBeforeUiInit) {
            startPlayback(
                aid = aid,
                bvid = bvid,
                cid = cid,
                seasonId = seasonId,
                epId = epId,
                playQueue = playQueue,
                seekPositionMs = seekPositionMs,
                startEpisodeIndex = startEpisodeIndex,
                startupTraceId = startupTraceId,
                startupTraceStartElapsedMs = startupTraceStartMs
            )
        }
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
        preparePlaybackUiForNewRequest()
        sessionCoordinator.replacePlayQueue(playQueue)
        beginPlaybackLoad(
            aid = aid,
            bvid = bvid,
            cid = cid,
            seasonId = seasonId,
            epId = epId,
            playQueue = playQueue,
            seekPositionMs = seekPositionMs,
            startEpisodeIndex = startEpisodeIndex,
            startupTraceId = startupTraceId,
            startupTraceStartElapsedMs = startupTraceStartElapsedMs
        )
    }

    private fun preparePlaybackUiForNewRequest() {
        if (::playerView.isInitialized) {
            playerView.hideController()
            playerView.pauseDanmaku()
        }
        tagCheckDoneForCurrentVideo = false
    }

    private fun beginPlaybackLoad(
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
        slimTimelineRenderer = SlimTimelineRenderer(bottomProgressBar)
        textSubtitle = binding.textSubtitle
        // 字幕有独立字号设置，不与 UI 文字缩放叠加
        ScaledTextView.exempt(textSubtitle)
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
        sessionCoordinator.setContentGate(
            isVideoAllowed = { video -> !isVideoBlockedByMinorProtection(video) },
            isEpisodeAllowed = { episode -> !isEpisodeBlockedByMinorProtection(episode) }
        )
        relatedAdapter = VideoAdapter(
            itemWidthPx = (resources.displayMetrics.widthPixels / 5).coerceAtLeast(1)
        )
        relatedAdapter.setOnItemClickListener { _, item ->
            if (isVideoBlockedByMinorProtection(item)) {
                toast(getString(R.string.activity_teen_mode_blocked_video))
            } else {
                playerView.hideController()
                hideContentPanel()
                hideNextPreview()
                sessionCoordinator.replacePlayQueue(buildPlayQueue(relatedAdapter.getItemsSnapshot(), item))
                sessionCoordinator.updateCurrentVideo(item)
                viewModel.playRelatedVideo(item)
            }
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
            onExecutePendingSession = { sessionId ->
                // 公益广告锁死模式：禁止连播下一个，由 STATE_ENDED 直接 finish
                if (!psasLocked) viewModel.playContinuation(sessionId)
            },
            onPendingActionCleared = { viewModel.clearPendingContinuation() }
        )
        overlayUiController = VideoPlayerOverlayController(
            activity = this,
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
                    toast(getString(R.string.activity_teen_mode_blocked_video))
                } else {
                    playerView.hideController()
                    viewModel.playEpisode(index)
                }
            },
            onPlayRelatedVideo = { video, playQueue ->
                if (isVideoBlockedByMinorProtection(video)) {
                    toast(getString(R.string.activity_teen_mode_blocked_video))
                } else {
                    playerView.hideController()
                    sessionCoordinator.replacePlayQueue(playQueue)
                    sessionCoordinator.updateCurrentVideo(video)
                    viewModel.playRelatedVideo(video)
                }
            },
            onOpenFragmentFromHost = { _, _ -> },
            onHideNextPreview = { autoPlayController.hideNextPreview() },
            isViewActive = { true }
        )
        resumeHintController = VideoPlayerResumeHintController(
            activity = this,
            playerProvider = { player },
            onCancelResume = { viewModel.cancelResumeProgress() },
            onClearResumeHint = { viewModel.clearResumeHint() },
            onShowResumeHint = { text -> playerView.showResumeHint(text) },
            onHideResumeHint = { playerView.hideResumeHint() }
        )
    }

    private fun setupPlayer() {
        douyinCoordinator = DouyinPlaybackCoordinator(
            douyinModeManager = douyinModeManager,
            sessionCoordinator = sessionCoordinator,
            playerView = playerView,
            viewModel = viewModel,
            lifecycleScope = lifecycleScope,
            context = this,
            host = object : DouyinPlaybackCoordinator.Host {
                override fun toast(message: String) = this@PlayerActivity.toast(message)
                override fun isVideoBlockedByMinorProtection(video: VideoModel) =
                    this@PlayerActivity.isVideoBlockedByMinorProtection(video)
            }
        )
        player = PlayerInstancePool.acquire(
            context = this,
            expectBvid = intent.getStringExtra(EXTRA_BVID),
            expectCid = intent.getLongExtra(EXTRA_CID, 0L)
        ).also {
            it.playWhenReady = false
            enableVideoTrack(it)
            if (::playerSettings.isInitialized) {
                it.playbackParameters = PlaybackParameters(playerSettings.defaultPlaybackSpeed)
            }
            it.addListener(playerListener)
            it.addAnalyticsListener(playerPerfListener)
        }
        playerView.setPlayer(player)
        // 公益广告锁死模式：关闭控制器（不显示暂停/进度条/设置面板），隐藏各种按钮，强制不循环
        if (psasLocked) {
            playerView.setUseController(false)
            playerView.showSettingButton(false)
            playerView.showHideNextPrevious(false)
            playerView.showHideFfRe(false)
            playerView.showHideActionButton(false)
            playerView.showHideEpisodeButton(false)
            playerView.showHideRelatedButton(false)
            playerView.showHideCommentButton(false)
            playerView.showHideDmSwitchButton(false)
            playerView.showHideLiveSettingButton(false)
            playerView.showHideSubtitleButton(false)
            playerView.setRepeatMode(Player.REPEAT_MODE_OFF)
            player?.repeatMode = Player.REPEAT_MODE_OFF
            playerView.hideController()
        }
        playerView.setRenderEventListener(object : MyPlayerView.RenderEventListener {
            override fun onRenderedFirstFrame() {
                // 统计切后台回来后的黑屏时长（onStart 到首帧渲染）。
                val recoverStart = surfaceRecoverStartMs
                if (recoverStart > 0L) {
                    surfaceRecoverStartMs = 0L
                    val blackMs = SystemClock.elapsedRealtime() - recoverStart
                    AppLog.i("SurfaceLifecycle", "first_frame_after_resume blackMs=$blackMs")
                }
                val trace = playerPerfTrace
                if (trace != null) {
                    trace.firstFrameMs = System.currentTimeMillis()
                    val total = trace.firstFrameMs - trace.prepareMs
                    val cdn = if (trace.firstLoadStartedMs > 0) trace.firstLoadStartedMs - trace.prepareMs else -1
                    val buffer = if (trace.firstLoadStartedMs > 0 && trace.firstLoadCompletedMs > 0) trace.firstLoadCompletedMs - trace.firstLoadStartedMs else -1
                    val decoder = if (trace.firstLoadCompletedMs > 0 && trace.videoDecoderInitMs > 0) trace.videoDecoderInitMs - trace.firstLoadCompletedMs else -1
                    val render = if (trace.videoDecoderInitMs > 0) trace.firstFrameMs - trace.videoDecoderInitMs else -1
                    // endToEnd = card_click→首帧(基于 elapsedRealtime，与 PLAYBACK_TRACE 同源)。
                    // 它与 total(prepare→首帧) 的差值即为 card_click→prepare 的盲区(playinfo+媒源构建)。
                    val endToEndMs = if (activeStartupTraceStartElapsedMs > 0L) {
                        SystemClock.elapsedRealtime() - activeStartupTraceStartElapsedMs
                    } else -1L
                    AppLog.i("VideoPlayerViewModel", "PLAYER_PERF breakdown: total=${total}ms cdn=${cdn}ms buffer=${buffer}ms decoder=${decoder}ms render=${render}ms endToEnd=${endToEndMs}ms blindSpot=${endToEndMs - total}ms")
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
                pendingRelatedVideosBeforeFirstFrame?.let { rawRelated ->
                    pendingRelatedVideosBeforeFirstFrame = null
                    applyRelatedVideos(rawRelated)
                }
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
            override fun isDouyinModeActive(): Boolean = douyinCoordinator.isModeActive()
            override fun onDouyinNavigateNext(): Boolean = douyinCoordinator.handleNext()
            override fun onDouyinNavigatePrevious(): Boolean = douyinCoordinator.handlePrevious()
            override fun peekDouyinNext(): DouyinModePreview? = douyinCoordinator.peekNextPreview()
            override fun peekDouyinPrevious(): DouyinModePreview? = douyinCoordinator.peekPreviousPreview()
        }
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
        playerView.showHideCommentButton(false)
        playerView.showHideDmSwitchButton(false)
        playerView.showHideLiveSettingButton(false)
        playerView.showHideSubtitleButton(false)
        playerView.setShowHideOwnerInfo(false)
        playerView.setRepeatMode(Player.REPEAT_MODE_OFF)
        applyPlayerSettings(playerSettings)
        // 公益广告锁死模式：applyPlayerSettings 之后再次强制不循环（用户可能保存了单集循环设置）
        if (psasLocked) {
            playerView.setRepeatMode(Player.REPEAT_MODE_OFF)
            player?.repeatMode = Player.REPEAT_MODE_OFF
        }
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
            override fun onAfterPlayModeChange(mode: AfterPlayMode) {
                playerSettings = playerSettings.copy(afterPlayMode = mode)
                playerView.setAfterPlayMode(mode)
                PlayerSettingsStore.saveAfterPlayMode(mode)
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
            override fun onComments() { showCommentPanel() }
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
                Toast.makeText(applicationContext, if (currentPlayer.repeatMode == Player.REPEAT_MODE_ONE) R.string.activity_repeat_one else R.string.activity_repeat_all, Toast.LENGTH_SHORT).show()
            }
            override fun onDmEnableChange(enabled: Boolean) { playerView.setDanmakuEnabled(enabled) }
            override fun onPlaybackSpeedClick() { playerView.showPlaybackSpeedSettingView() }
        })
        playerView.onUserSeekListener = { positionMs ->
            viewModel.sponsorUserSeek(positionMs)
        }
        renderControllerChrome(View.GONE)
    }

    private fun enableVideoTrack(targetPlayer: ExoPlayer) {
        targetPlayer.trackSelectionParameters = targetPlayer.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
            .build()
    }

    private fun setupBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // 公益广告锁死模式：吞掉返回键，不允许退出（dispatchKeyEvent 已拦截，这里是双重保险）
                if (psasLocked) return
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
                        if (System.currentTimeMillis() - exitTime <= exitInterval) {
                            finish()
                        } else {
                            exitTime = System.currentTimeMillis()
                            Toast.makeText(applicationContext, R.string.activity_exit_player_hint, Toast.LENGTH_SHORT).show()
                        }
                    },
                )
            }
        })
    }

    /**
     * 公益广告锁死模式：在 Activity 层吞掉所有按键事件，只放行音量/系统键。
     * 返回键在这里也被吞掉（不放行到 onBackPressedDispatcher）。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (psasLocked) {
            // 只放行音量键，让用户能调音量；其余全部吞掉
            if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
                event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
                event.keyCode == KeyEvent.KEYCODE_VOLUME_MUTE
            ) {
                return super.dispatchKeyEvent(event)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private var preloadHeaderRefreshPosted = false

    private fun resolvePlaybackStartSeekPosition(
        playbackRequest: VideoPlayerViewModel.PlaybackRequest,
        currentPlayer: Player
    ): Long {
        return PlayerScreenLogic.resolvePlaybackStartSeekPosition(playbackRequest, currentPlayer, TAG)
    }

    private fun setupObservers() {
        startTeenModeTicker()
        lifecycleScope.launch {
            viewModel.playbackRequest.collect { request ->
                val currentPlayer = player ?: return@collect
                val playbackRequest = request ?: return@collect
                viewModel.setErrorMessage(null)
                resumePlaybackWhenStarted = playbackRequest.playWhenReady
                if (playbackRequest.replaceInPlace) {
                    if (playerView.isControllerFullyVisible()) {
                        playerView.resetControllerHideCallbacks()
                    } else {
                        playerView.hideController()
                    }
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
                pendingRelatedVideosBeforeFirstFrame = null
                playerPerfTrace = PlayerPerfTrace(prepareMs = System.currentTimeMillis())
                // 锚定 breakdown 起点(prepare)相对 card_click 的偏移，暴露 card_click→prepare 的盲区。
                // 该区间含 playinfo 网络请求 + DASH 解析 + 媒源构建，是 breakdown total 未覆盖的部分。
                PlaybackStartupTrace.log(
                    traceId = activeStartupTraceId,
                    startElapsedMs = activeStartupTraceStartElapsedMs,
                    step = "prepare_cold_start"
                )
                suppressPlaybackEnvironmentSync = true
                try {
                    currentPlayer.playWhenReady = false
                    if (playbackRequest.reuseSameSource) {
                        // 暖路径：MediaSource 仍挂载在 player 上，跳过 setMediaSource()
                        // player 被 stop() 过（STATE_IDLE），需 prepare() 重新起播
                        // 诊断：记录 player 实例当前实际挂载的 MediaItem uri，
                        // 对照请求 bvid/cid 与 VM 缓存命中 uri，定位是否串台。
                        val playerUri = runCatching {
                            currentPlayer.currentMediaItem
                                ?.localConfiguration?.uri?.toString()
                        }.getOrNull()
                        AppLog.w(
                            TAG,
                            "warm_reuse_player_state reqBvid=${playbackRequest.bvid} reqCid=${playbackRequest.cid} " +
                                "playerUri=${playerUri?.substringAfterLast('/')} " +
                                "playerMediaCount=${currentPlayer.mediaItemCount}"
                        )
                        PlaybackStartupTrace.log(
                            traceId = activeStartupTraceId,
                            startElapsedMs = activeStartupTraceStartElapsedMs,
                            step = "warm_reuse_prepare",
                            message = "seek=$startSeekPositionMs reqBvid=${playbackRequest.bvid} " +
                                "reqCid=${playbackRequest.cid} playerUri=${playerUri?.substringAfterLast('/')} " +
                                "playerMediaCount=${currentPlayer.mediaItemCount}"
                        )
                        currentPlayer.prepare()
                        currentPlayer.seekTo(startSeekPositionMs)
                        currentPlayer.playWhenReady = playbackRequest.playWhenReady
                    } else {
                        // 冷路径：不同视频，完整重建管线
                        currentPlayer.stop()
                        currentPlayer.setMediaSource(playbackRequest.mediaSource, startSeekPositionMs)
                        // 记录 player 实际挂载的源，供后续 zero_overhead_reuse 查询，
                        // 避免"VM 缓存命中但 player 挂的是别的视频"导致串台。
                        PlayerInstancePool.rememberAttachedSource(playbackRequest.bvid, playbackRequest.cid)
                        // 诊断对照基准：冷路径实际 set 的 uri，与暖路径 playerUri 对照
                        val coldSetUri = runCatching {
                            playbackRequest.mediaSource.mediaItem.localConfiguration?.uri?.toString()
                        }.getOrNull()
                        AppLog.w(
                            TAG,
                            "cold_media_source_set reqBvid=${playbackRequest.bvid} reqCid=${playbackRequest.cid} " +
                                "setUri=${coldSetUri?.substringAfterLast('/')}"
                        )
                        PlaybackStartupTrace.log(
                            traceId = activeStartupTraceId,
                            startElapsedMs = activeStartupTraceStartElapsedMs,
                            step = "media_source_set",
                            message = "intentId=${playbackRequest.playbackIntentId} seek=$startSeekPositionMs " +
                                "reqBvid=${playbackRequest.bvid} reqCid=${playbackRequest.cid} " +
                                "setUri=${coldSetUri?.substringAfterLast('/')}"
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
                douyinCoordinator.schedulePreloadAfterPlaybackRequest(playbackRequest)
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
                    Toast.makeText(applicationContext, R.string.activity_risk_control_settings_hint, Toast.LENGTH_LONG).show()
                }
            }
        }

        lifecycleScope.launch {
            viewModel.videoInfo.collect { info ->
                latestVideoInfo = info
                sessionCoordinator.updateVideoInfo(info)
                douyinCoordinator.resetIfNeeded()
                douyinCoordinator.ensureQueueStarted()
                schedulePreloadAndHeaderRefresh()
                updatePrimaryActionVisibility()
                applyMusicZoneDefaultSpeedIfNeeded(info)
                checkTagsAndExitIfNeeded(info)
            }
        }

        lifecycleScope.launch {
            viewModel.error.collect { error ->
                if (!error.isNullOrBlank()) {
                    AppLog.e(TAG, "viewModel error: $error")
                }
                playerView.setCustomErrorMessage(error)
            }
        }

        lifecycleScope.launch { viewModel.currentPosition.collect { positionMs ->
            latestPlaybackPositionMs = positionMs.coerceAtLeast(0L)
            renderBottomProgressBar()
        } }
        lifecycleScope.launch { viewModel.duration.collect { durationMs -> latestPlaybackDurationMs = durationMs.coerceAtLeast(0L); renderBottomProgressBar() } }

        // 青少年模式：观看时长达上限进入休息时，立即退出播放器并提示还需休息多久
        lifecycleScope.launch {
            com.mytvb.core.common.content.TeenModeTimer.restingFlow.collect { resting ->
                if (resting) {
                    val restMin = com.mytvb.core.common.content.TeenModeTimer.getRestLimitMin()
                        .coerceAtLeast(1)
                    toast(getString(R.string.activity_teen_rest_needed_format, restMin))
                    finish()
                }
            }
        }

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

        lifecycleScope.launch {
            viewModel.danmaku.collect {
                updateDanmakuSwitchVisibility()
            }
        }

        viewModel.onDmMaskReady = { maskUrl, cid, fps ->
            playerView.setDmMaskRepository(viewModel.dmMaskRepository)
            lifecycleScope.launch {
                playerView.loadDmMask(maskUrl, cid, fps)
            }
        }
        viewModel.onDmMaskReset = {
            playerView.releaseDmMask()
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
                if (!activeStartupFirstFrameLogged && rawRelated.isNotEmpty()) {
                    pendingRelatedVideosBeforeFirstFrame = rawRelated
                    PlaybackStartupTrace.log(
                        traceId = activeStartupTraceId,
                        startElapsedMs = activeStartupTraceStartElapsedMs,
                        step = "related_filter_deferred",
                        message = "count=${rawRelated.size}"
                    )
                    return@collect
                }
                applyRelatedVideos(rawRelated)
            }
        }

        lifecycleScope.launch {
            viewModel.resumeHint.collect { hint ->
                resumeHintController.onHintChanged(hint)
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
                        toast(getString(R.string.activity_sponsor_skipped_format, state.segment.categoryName()))
                        player?.seekTo(state.segment.endTimeMs)
                    }
                }
            }
        }

        lifecycleScope.launch {
            viewModel.sponsorSegments.collect { segments ->
                playerView.setSponsorSegments(segments)
                playerView.setSponsorDuration(viewModel.duration.value)
                if (::slimTimelineRenderer.isInitialized) {
                    slimTimelineRenderer.setSegments(segments)
                    slimTimelineRenderer.setSponsorDuration(viewModel.duration.value)
                }
            }
        }
        lifecycleScope.launch {
            viewModel.duration.collect { durationMs ->
                playerView.setSponsorDuration(durationMs)
                if (::slimTimelineRenderer.isInitialized) {
                    slimTimelineRenderer.setSponsorDuration(durationMs)
                }
            }
        }
    }

    // --- Lifecycle ---

    override fun onStart() {
        super.onStart()
        val t0 = SystemClock.elapsedRealtime()
        val stateBefore = player?.playbackState
        // 重新绑定 video surface：setVideoSurfaceView 会让 video renderer 在下次 doSomeWork
        // 重建解码器并 attach（仅一次）。不再单独 enableVideoTrack + seekTo：那会触发解码器
        // 被重建两次（实测恢复黑屏多 ~270ms）。onStop 已不再 disable video track。
        playerView.reattachVideoSurface()
        // seekTo 当前位置作为兜底：某些设备上 surface 变更后解码器虽重建但不出新帧，
        // seek 一次逼它重出（此处 renderer 未被 reset，不会二次重建解码器）。
        player?.let { p ->
            if (p.playbackState == Player.STATE_READY || p.playbackState == Player.STATE_BUFFERING) {
                p.seekTo(p.currentPosition)
            }
        }
        // 记录前台恢复起点，配合 onRenderedFirstFrame 统计黑屏时长。
        surfaceRecoverStartMs = t0
        AppLog.i("SurfaceLifecycle", "onStart resumeIntent=$resumePlaybackWhenStarted stateBefore=$stateBefore elapsed=${SystemClock.elapsedRealtime() - t0}ms")
        resumedFromStart = true
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

    override fun onResume() {
        super.onResume()
        // 系统设置（字幕大小/底部进度条等）可能在播放器后台期间被修改，
        // 任何 onResume 都先重载并应用，再走原有的 surface 自愈逻辑。
        playerSettings = PlayerSettingsStore.load(this)
        if (::textSubtitle.isInitialized) {
            textSubtitle.setTextSize(
                TypedValue.COMPLEX_UNIT_PX,
                playerSettings.subtitleTextSizePx.toFloat()
            )
            renderControllerChrome()
        }
        // onStart 一定会先于 onResume 执行，且 onStart 里已做 surface 恢复，
        // 这里跳过紧随 onStart 的那次 onResume，避免重复 seekTo。
        if (resumedFromStart) {
            resumedFromStart = false
            return
        }
        // 仅 onPause→onResume（如系统弹窗返回）而未走 onStop→onStart 的场景：
        // Surface 可能被系统静默销毁重建，解码器输出悬空导致画面冻结/黑屏。
        // 重新绑定 Surface + seekTo 当前位置逼解码器重出新帧。
        playerView.recoverVideoRenderIfNeeded(reason = "on_resume_after_pause")
    }

    override fun onStop() {
        super.onStop()
        playerView.removeCallbacks(resumePlaybackRunnable)
        progressCoordinator.syncNow()
        val (snapshotPositionMs, snapshotPlayWhenReady) = capturePlaybackSnapshot()
        postPlaybackProgressEvent(snapshotPositionMs)
        viewModel.reportPlaybackHeartbeat(force = true)
        viewModel.savePlayerSnapshot()
        resumePlaybackWhenStarted = snapshotPlayWhenReady
        player?.pause()
        player?.playWhenReady = false
        playerView.stopDanmaku()
        stopProgressUpdates()
        // 提前释放视频解码器，避免后台持有硬件解码器资源。
        // detachVideoSurface (clearVideoSurfaceView) 本身就会释放 video decoder，
        // 无需再 disable video track：disable 后恢复时 enableVideoTrack + seekTo
        // 会触发解码器被重建两次（实测多花 ~270ms），恢复黑屏更久。
        playerView.detachVideoSurface()
        syncPlaybackEnvironment()
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.slide_in_to_left, R.anim.slide_out_to_right)
    }

    override fun onDestroy() {
        uninstallMainThreadJankMonitor()
        uiCoordinator.removeListener(coordinatorStateListener)
        playerView.removeCallbacks(resumePlaybackRunnable)
        stopProgressUpdates()
        stopTeenModeTicker()
        resumeHintController.release()
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

    /**
     * 主线程慢消息诊断（AppLog 开启时安装）：单条消息 dispatch 超过 10ms 即打点，
     * 用于把 UI 掉帧归因到具体消息（Choreographer doFrame / 进度刷新 / 其他回调）。
     * Printer 每条消息由框架拼一次字符串，仅在诊断期安装，onDestroy 卸载。
     */
    private var mainThreadJankPrinter: Printer? = null
    private var jankMsgStartNs = 0L
    private var jankMsgText: String = ""
    private var lastJankLogAtMs = 0L

    private fun installMainThreadJankMonitor() {
        if (!AppLog.isEnabled) return
        if (mainThreadJankPrinter != null) return
        val printer = Printer { text ->
            if (text.startsWith(">")) {
                jankMsgStartNs = System.nanoTime()
                jankMsgText = text
            } else if (jankMsgStartNs != 0L) {
                val costMs = (System.nanoTime() - jankMsgStartNs) / 1_000_000L
                jankMsgStartNs = 0L
                if (costMs >= 10L) {
                    val now = SystemClock.uptimeMillis()
                    if (now - lastJankLogAtMs >= 200L) {
                        lastJankLogAtMs = now
                        AppLog.w("MainThreadJank", "main msg cost=${costMs}ms ${jankMsgText.take(140)}")
                    }
                }
            }
        }
        Looper.getMainLooper().setMessageLogging(printer)
        mainThreadJankPrinter = printer
    }

    private fun uninstallMainThreadJankMonitor() {
        val printer = mainThreadJankPrinter ?: return
        mainThreadJankPrinter = null
        Looper.getMainLooper().setMessageLogging(null)
    }

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

    private fun schedulePreloadAndHeaderRefresh() {
        if (preloadHeaderRefreshPosted) return
        preloadHeaderRefreshPosted = true
        binding.root.post {
            preloadHeaderRefreshPosted = false
            renderPlayerHeader()
        }
    }

    private var tagCheckDoneForCurrentVideo = false

    private fun applyRelatedVideos(rawRelated: List<VideoModel>) {
        val related = ContentFilter.filterVideos(this@PlayerActivity, rawRelated)
        sessionCoordinator.updateRelatedVideos(related)
        schedulePreloadAndHeaderRefresh()
        relatedAdapter.setData(related)
        playerView.showHideRelatedButton(related.isNotEmpty())
    }

    private fun isVideoBlockedByMinorProtection(video: VideoModel): Boolean {
        return PlayerContentGuards.isVideoBlocked(this, video)
    }

    private fun isEpisodeBlockedByMinorProtection(episode: VideoPlayerViewModel.PlayableEpisode): Boolean {
        return PlayerContentGuards.isEpisodeBlocked(this, episode)
    }

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
        val (hasOwner, hasVideoIdentity) = PlayerScreenLogic.primaryActionFlags(latestVideoInfo?.view)
        playerView.setShowHideOwnerInfo(hasOwner)
        playerView.showHideCommentButton(hasVideoIdentity)
        playerView.showHideActionButton(hasVideoIdentity)
        playerView.showSettingButton(hasVideoIdentity)
    }

    private fun updateDanmakuSwitchVisibility() {
        val hasDanmaku = viewModel.danmaku.value.isNotEmpty()
        playerView.showHideDmSwitchButton(playerSettings.showDanmakuSwitch && hasDanmaku)
    }

    /** 播放速度按键：设置开关打开即常驻显示在控制栏。 */
    private fun updatePlaySpeedButtonVisibility() {
        playerView.showHidePlaySpeedButton(playerSettings.showPlaySpeedButton)
    }

    private var lastVideoKeyForMusicSpeed: String? = null

    /** 音乐区视频起播默认 1 倍速：开关打开且视频属音乐区时生效，每个视频只应用一次。 */
    private fun applyMusicZoneDefaultSpeedIfNeeded(info: VideoDetailModel?) {
        val view = info?.view ?: return
        val key = "${view.bvid}:${view.cid}"
        if (key == lastVideoKeyForMusicSpeed) return
        lastVideoKeyForMusicSpeed = key
        if (!::playerSettings.isInitialized || !playerSettings.musicZoneNormalSpeed) return
        AppLog.d(TAG, "MusicZoneSpeed tid=${view.tid} isMusic=${PlayerScreenLogic.isMusicZone(view)}")
        if (!PlayerScreenLogic.isMusicZone(view)) return
        playerView.setPlaySpeed(1f)
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
        playerView.setTitle(PlayerScreenLogic.buildHeaderTitle(video.title, selectedEpisode))
        playerView.setSubTitle(PlayerScreenLogic.buildHeaderMetaParts(this, video).joinToString(" · "))
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
        // 字幕让位高度以实际可见的 UI 为准，不依赖 coordinator 的名义占用：
        // 面板关闭后名义状态可能仍是 FullChrome（如 ProgressOnly 分支），而控制栏早已收起，
        // 旧逻辑会让字幕一直停在高位（px300）。
        val subtitleBottomMarginRes = when {
            uiCoordinator.bottomOccupant == PlaybackUiCoordinator.BottomOccupant.BottomPanel ||
                visibility == View.VISIBLE -> R.dimen.px300

            playerSettings.showBottomProgressBar -> R.dimen.px80
            else -> R.dimen.px60
        }
        (textSubtitle.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
            val targetMargin = resources.getDimensionPixelSize(subtitleBottomMarginRes)
            if (params.bottomMargin != targetMargin) {
                params.bottomMargin = targetMargin
                textSubtitle.layoutParams = params
            }
        }
        renderBottomProgressBar()
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

    private fun capturePlaybackSnapshot(): Pair<Long, Boolean> {
        val positionMs = player?.currentPosition?.coerceAtLeast(0L) ?: 0L
        val playWhenReady = player?.playWhenReady ?: resumePlaybackWhenStarted
        return positionMs to playWhenReady
    }

    private fun postPlaybackProgressEvent(positionMs: Long) {
        PlayerScreenLogic.postPlaybackProgressEvent(
            appEventHub = appEventHub,
            latestView = latestVideoInfo?.view,
            sessionCoordinator = sessionCoordinator,
            positionMs = positionMs
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
            is PlayerSessionCoordinator.ContinuationPlan.ExitPlayer -> {
                AppLog.i(TAG, "autoplay plan=exit mode=$afterPlayMode hasNext=$hasNextEpisode nextCid=${nextEpisode?.cid}")
                finish()
            }
            is PlayerSessionCoordinator.ContinuationPlan.ShowController -> {
                AppLog.i(TAG, "autoplay plan=show_controller mode=$afterPlayMode hasNext=$hasNextEpisode nextCid=${nextEpisode?.cid}")
                playerView.showController()
            }
        }
    }

    private fun hideNextPreview() { autoPlayController.hideNextPreview() }

    /** 试看提示已提示过的 cid，避免 fallback 重建/STATE_READY 反复进入时重复弹 Toast。 */
    private var previewHintShownCid = Long.MIN_VALUE

    /** 起播检测到试看流时提示一次；同一集（cid）只提示一次，切集后重新提示。 */
    private fun maybeShowPreviewHint() {
        if (!viewModel.isPreviewPlayback) return
        val cid = viewModel.previewContextCid
        if (cid == previewHintShownCid) return
        previewHintShownCid = cid
        Toast.makeText(
            applicationContext,
            R.string.activity_preview_start_hint,
            Toast.LENGTH_LONG
        ).show()
    }

    private fun hideContentPanel() {
        overlayUiController.hideContentPanel()
    }

    private fun showChooseEpisodeDialog() { overlayUiController.showChooseEpisodeDialog() }
    private fun showRelatedPanel() { overlayUiController.showRelatedPanel() }
    private fun showOwnerDetailDialog() { overlayUiController.showOwnerDetailDialog() }
    private fun showPlayerActionDialog() { overlayUiController.showPlayerActionDialog() }
    private fun showCommentPanel() { overlayUiController.showCommentPanel() }
    private fun showVideoInfoDialog() { overlayUiController.showVideoInfoDialog() }

    private fun applyPlayerSettings(settings: PlayerSettings) {
        // 音乐区 1 倍速开关打开且当前是音乐区视频时，忽略默认倍速配置
        val musicZoneNormalSpeed = settings.musicZoneNormalSpeed &&
            PlayerScreenLogic.isMusicZone(latestVideoInfo?.view)
        playerView.setPlaySpeed(if (musicZoneNormalSpeed) 1f else settings.defaultPlaybackSpeed)
        playerView.showPlaybackRateIndicator(settings.showPlaybackRate)
        playerView.setSeekSecond(settings.fastSeekSeconds)
        playerView.setSimpleKeyPressEnabled(settings.simpleKeyPress)
        playerView.setPersistentBottomProgressEnabled(settings.showBottomProgressBar)
        playerView.showHideFfRe(settings.showRewindFastForward)
        textSubtitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, settings.subtitleTextSizePx.toFloat())
        playerView.setAfterPlayMode(settings.afterPlayMode)
        playerView.setupDanmakuEngine()
        updateEpisodeNavigationVisibility()
        updateDanmakuSwitchVisibility()
        updatePlaySpeedButtonVisibility()
        renderControllerChrome()
    }

    private fun syncChromeStateToCoordinator(visibility: Int) {
        PlayerScreenLogic.syncChromeStateToCoordinator(
            coordinator = uiCoordinator,
            showBottomProgressBar = playerSettings.showBottomProgressBar,
            visibility = visibility
        )
    }

    // 抖音模式编排已迁出至 DouyinPlaybackCoordinator
}

private fun Int.nameOfPlaybackState(): String = when (this) {
    Player.STATE_IDLE -> "IDLE"
    Player.STATE_BUFFERING -> "BUFFERING"
    Player.STATE_READY -> "READY"
    Player.STATE_ENDED -> "ENDED"
    else -> toString()
}

private fun String.isSoftwareVideoDecoderName(): Boolean {
    val name = lowercase(Locale.US)
    return name.startsWith("omx.google.") ||
        name.startsWith("c2.android.") ||
        name.startsWith("c2.google.") ||
        name.contains(".sw.") ||
        name.contains("software") ||
        name.contains("ffmpeg")
}
