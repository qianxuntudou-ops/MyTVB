@file:Suppress("SpellCheckingInspection")

package com.tutu.myblbl.feature.player

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import java.util.Locale
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.source.MediaSource
import com.google.gson.Gson
import com.tutu.myblbl.R
import com.tutu.myblbl.core.common.media.VideoCodecSupport

import com.tutu.myblbl.model.dm.AdvancedDanmakuParser
import com.tutu.myblbl.model.dm.DmColorfulStyleParser
import com.tutu.myblbl.model.dm.DmMaskInfo
import com.tutu.myblbl.model.dm.DmMaskRepository
import com.tutu.myblbl.model.dm.DmModel
import com.tutu.myblbl.model.dm.SpecialDanmakuModel
import com.tutu.myblbl.model.dm.SpecialDanmakuParser
import com.tutu.myblbl.model.interaction.InteractionModel
import com.tutu.myblbl.model.interaction.InteractionVariableModel
import com.tutu.myblbl.feature.player.interaction.InteractionEngine
import com.tutu.myblbl.feature.player.interaction.InteractionRepository
import com.tutu.myblbl.model.player.PlayInfoModel
import com.tutu.myblbl.model.player.VideoSnapshotData
import com.tutu.myblbl.model.proto.DmProtoParser
import com.tutu.myblbl.model.proto.DmWebViewReplyProto
import com.tutu.myblbl.model.subtitle.SubtitleData
import com.tutu.myblbl.model.subtitle.SubtitleInfoModel
import com.tutu.myblbl.model.subtitle.SubtitleItem
import com.tutu.myblbl.model.video.VideoModel
import com.tutu.myblbl.model.video.detail.SubtitleItem as DetailSubtitleItem
import com.tutu.myblbl.model.video.detail.VideoDetailModel
import com.tutu.myblbl.model.video.quality.AudioQuality
import com.tutu.myblbl.model.video.quality.VideoCodecEnum
import com.tutu.myblbl.model.video.quality.VideoQuality
import com.tutu.myblbl.network.api.ApiService
import com.tutu.myblbl.network.security.NetworkSecurityGateway
import com.tutu.myblbl.network.session.NetworkSessionGateway
import com.tutu.myblbl.network.response.Base2Response
import com.tutu.myblbl.network.WbiGenerator
import com.tutu.myblbl.core.common.log.AppLog
import com.tutu.myblbl.core.common.settings.AppSettingsDataStore
import com.tutu.myblbl.feature.player.cache.PlayerMediaCache
import com.tutu.myblbl.network.cookie.CookieManager
import com.tutu.myblbl.feature.player.settings.PlayerSettings
import com.tutu.myblbl.feature.player.settings.PlayerSettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URL
import kotlinx.coroutines.withContext
import org.koin.mp.KoinPlatform
import java.util.concurrent.TimeUnit
import java.util.UUID
import com.tutu.myblbl.feature.player.sponsor.SponsorBlockUseCase
import com.tutu.myblbl.feature.player.sponsor.SponsorSegment

@UnstableApi
class VideoPlayerViewModel(
    private val apiService: ApiService,
    private val okHttpClient: OkHttpClient,
    private val cookieManager: CookieManager,
    private val sessionGateway: NetworkSessionGateway,
    private val securityGateway: NetworkSecurityGateway,
    private val appSettings: AppSettingsDataStore,
    private val noCookieApiService: ApiService,
    context: Context,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    enum class EpisodeCatalogSource {
        PAGES,
        UGC_SEASON,
        PGC_EPISODES
    }

    companion object {
        private const val TAG = "VideoPlayerViewModel"
        private const val FIRST_FRAME_DEFERRED_WORK_DELAY_MS = 250L
        private const val FIRST_FRAME_SPONSOR_LOAD_DELAY_MS = 1_500L

        const val SAVED_AID = "saved_player_aid"
        const val SAVED_BVID = "saved_player_bvid"
        const val SAVED_CID = "saved_player_cid"
        const val SAVED_EP_ID = "saved_player_ep_id"
        const val SAVED_SEASON_ID = "saved_player_season_id"
        const val SAVED_EPISODE_INDEX = "saved_player_episode_index"
        const val SAVED_SEEK_POSITION_MS = "saved_player_seek_position_ms"
        const val SAVED_QUALITY_ID = "saved_player_quality_id"
        const val SAVED_AUDIO_QUALITY_ID = "saved_player_audio_quality_id"
        const val SAVED_SUBTITLE_INDEX = "saved_player_subtitle_index"

        // 跨 VM 实例记录最近播放过的 cid，用于同视频重播热路径检测
        private val recentlyPlayedCids = linkedSetOf<Long>()
        private const val MAX_RECENTLY_PLAYED = 8

        @Synchronized
        fun isRecentlyPlayed(cid: Long): Boolean = cid in recentlyPlayedCids

        @Synchronized
        fun markRecentlyPlayed(cid: Long) {
            recentlyPlayedCids.remove(cid)
            recentlyPlayedCids.add(cid)
            if (recentlyPlayedCids.size > MAX_RECENTLY_PLAYED) {
                recentlyPlayedCids.remove(recentlyPlayedCids.first())
            }
        }

        // 同视频零开销复用：缓存最近一次完整准备好的播放状态
        @UnstableApi
        internal data class CachedPlayback(
            val bvid: String?,
            val cid: Long,
            val mediaSource: MediaSource,
            val playInfo: PlayInfoModel,
            val selectionSnapshot: VideoPlayerStreamResolver.SelectionSnapshot,
            val expiresAtMs: Long
        )
        private val cachedPlaybacks = LinkedHashMap<String, CachedPlayback>(2, 0.75f, true)
        private const val LAST_PLAYBACK_TTL_MS = 120_000L
        private const val MAX_CACHED_PLAYBACKS = 2

        @Synchronized
        internal fun getCachedPlayback(bvid: String?, cid: Long): CachedPlayback? {
            trimExpiredCachedPlaybacks()
            val cached = cachedPlaybacks[cachePlaybackKey(bvid, cid)] ?: return null
            if (System.currentTimeMillis() > cached.expiresAtMs) {
                cachedPlaybacks.remove(cachePlaybackKey(bvid, cid))
                return null
            }
            if (cached.bvid != bvid || cached.cid != cid) return null
            return cached
        }

        @UnstableApi
        @Synchronized
        internal fun putCachedPlayback(
            bvid: String?,
            cid: Long,
            mediaSource: MediaSource,
            playInfo: PlayInfoModel,
            selectionSnapshot: VideoPlayerStreamResolver.SelectionSnapshot
        ) {
            trimExpiredCachedPlaybacks()
            cachedPlaybacks[cachePlaybackKey(bvid, cid)] = CachedPlayback(
                bvid = bvid,
                cid = cid,
                mediaSource = mediaSource,
                playInfo = playInfo,
                selectionSnapshot = selectionSnapshot,
                expiresAtMs = System.currentTimeMillis() + LAST_PLAYBACK_TTL_MS
            )
            while (cachedPlaybacks.size > MAX_CACHED_PLAYBACKS) {
                cachedPlaybacks.remove(cachedPlaybacks.entries.first().key)
            }
        }

        @Synchronized
        fun clearCachedPlayback() {
            cachedPlaybacks.clear()
        }

        @Synchronized
        fun hasCachedPlayback(): Boolean {
            trimExpiredCachedPlaybacks()
            return cachedPlaybacks.isNotEmpty()
        }

        private fun cachePlaybackKey(bvid: String?, cid: Long): String {
            return "${bvid.orEmpty()}#$cid"
        }

        private fun trimExpiredCachedPlaybacks() {
            val now = System.currentTimeMillis()
            val iterator = cachedPlaybacks.entries.iterator()
            while (iterator.hasNext()) {
                if (iterator.next().value.expiresAtMs <= now) {
                    iterator.remove()
                }
            }
        }
    }

    data class PlayableEpisode(
        val cid: Long,
        val title: String,
        val panelTitle: String = title,
        val subtitle: String = "",
        val cover: String = "",
        val aid: Long = 0,
        val bvid: String = "",
        val epId: Long = 0L,
        val seasonId: Long = 0L,
        val source: EpisodeCatalogSource = EpisodeCatalogSource.PAGES
    )

    data class PlaybackRequest(
        val mediaSource: MediaSource,
        val aid: Long? = null,
        val bvid: String? = null,
        val cid: Long = 0L,
        val seekPositionMs: Long,
        val playWhenReady: Boolean,
        val replaceInPlace: Boolean,
        val reuseSameSource: Boolean = false,
        val durationMs: Long = 0L,
        val playbackIntentId: String = "",
        val continuationIntentId: String? = null,
        val startupTraceId: String = PlaybackStartupTrace.NO_TRACE,
        val startupTraceStartElapsedMs: Long = 0L
    )

    data class ContinuationSession(
        val id: String,
        val intent: ContinuationPlaybackIntent
    )

    data class ResumeProgressHint(
        val targetPositionMs: Long
    )

    internal data class PlayRequestIdentity(
        val aid: Long?,
        val bvid: String?,
        val cid: Long,
        val epId: Long?
    )

    private enum class PlaybackStartSource {
        NORMAL,
        CONTINUATION
    }

    private data class PlaybackStartIntent(
        val id: String,
        val source: PlaybackStartSource,
        val aid: Long?,
        val bvid: String?,
        val cid: Long,
        val seasonId: Long,
        val epId: Long,
        val seekPositionMs: Long,
        val startEpisodeIndex: Int,
        val preferredQualityId: Int,
        val preferredAudioQualityId: Int,
        val startupTraceId: String,
        val startupTraceStartElapsedMs: Long,
        val isSteinsGate: Boolean,
        val preferLastPlayTime: Boolean?
    )

    internal data class PreparedPlayback(
        val identity: PlayRequestIdentity,
        val playInfo: PlayInfoModel,
        val selectionSnapshot: VideoPlayerStreamResolver.SelectionSnapshot,
        val mediaSource: MediaSource,
        val dashSession: VideoPlaybackSession?,
        val seekToStart: Long,
        val playWhenReady: Boolean,
        val resumeHintPositionMs: Long?,
        val replaceInPlace: Boolean,
        val playbackIntentId: String,
        val continuationIntentId: String?,
        val requestDurationMs: Long,
        val startupTraceId: String,
        val startupTraceStartElapsedMs: Long
    )

    private data class PreloadedPlayback(
        val source: PlaybackPreloadTarget.Source,
        val preparedPlayback: PreparedPlayback
    )

    internal data class PlayInfoFetchResult(
        val requestedQualityId: Int,
        val response: VideoPlayerPlayInfoGateway.PlayInfoResult
    )

    private val _resumeHint = MutableStateFlow<ResumeProgressHint?>(null)
    val resumeHint: StateFlow<ResumeProgressHint?> = _resumeHint

    fun cancelResumeProgress() {
        _resumeHint.value = null
    }

    fun clearResumeHint() {
        _resumeHint.value = null
    }

    private var resumeToast: Toast? = null

    private fun showResumePositionToast(positionMs: Long) {
        resumeToast?.cancel()
        val totalSeconds = positionMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        val timeStr = if (hours > 0) {
            String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
        }
        resumeToast = Toast.makeText(
            appContext,
            appContext.getString(R.string.tip_play_from_history, timeStr),
            Toast.LENGTH_SHORT
        ).also { it.show() }
    }

    private fun publishResumeHint(positionMs: Long) {
        _resumeHint.value = ResumeProgressHint(targetPositionMs = positionMs)
        if (hasReachedFirstFrame) {
            showResumePositionToast(positionMs)
        } else {
            pendingResumeToastPositionMs = positionMs
        }
    }

    private val gson = Gson()
    private val appContext = context.applicationContext
    private val ipv4OnlyEnabled: () -> Boolean = {
        runCatching { KoinPlatform.getKoin().get<AppSettingsDataStore>() }
            .getOrNull()
            ?.getCachedString("ipv4_only") != "关"
    }
    private val playerOkHttpClient = okhttp3.OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .connectionPool(okhttp3.ConnectionPool(5, 30, TimeUnit.SECONDS))
        .dns(object : okhttp3.Dns {
            override fun lookup(hostname: String): List<java.net.InetAddress> {
                val host = hostname.trim()
                if (host.isBlank()) throw java.net.UnknownHostException("hostname is blank")
                val addresses = okhttp3.Dns.SYSTEM.lookup(host)
                if (!ipv4OnlyEnabled()) return addresses
                val ipv4 = addresses.filterIsInstance<java.net.Inet4Address>()
                if (ipv4.isNotEmpty()) return ipv4
                throw java.net.UnknownHostException("No IPv4 address for $host")
            }
        })
        .build()
    private val upstreamDataSourceFactory = OkHttpDataSource.Factory(playerOkHttpClient)
        .setUserAgent(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36"
        )
        .setDefaultRequestProperties(
            mapOf(
                "Origin" to "https://www.bilibili.com",
                "Referer" to "https://www.bilibili.com"
            )
        )
    private val cacheDataSourceFactory = PlayerMediaCache.buildDataSourceFactory(
        appContext,
        upstreamDataSourceFactory
    )
    private val dataSourceFactory = DefaultDataSource.Factory(
        appContext,
        cacheDataSourceFactory
    )

    var useDashPlayback: Boolean = true

    // Keeps stream selection and fallback policy out of the ViewModel's lifecycle code.
    private val streamResolver = VideoPlayerStreamResolver(
        dataSourceFactory = dataSourceFactory,
        urlNormalizer = ::normalizeUrl
    )
    private val dashMediaSourceFactory = VideoPlayerDashMediaSourceFactory(
        dataSourceFactory = cacheDataSourceFactory,
        urlNormalizer = ::normalizeUrl
    )
    private val douyinWarmupManager = DouyinPlaybackWarmupManager(
        dataSourceFactory = cacheDataSourceFactory,
        urlNormalizer = ::normalizeUrl
    )
    private var currentDashSession: VideoPlaybackSession? = null
    private val qualityPolicy = VideoPlayerQualityPolicy()
    // Keeps episode-list construction and PGC header mapping out of playback request flow.
    private val episodeCatalogBuilder = VideoPlayerEpisodeCatalogBuilder(apiService)
    // Encapsulates PGC/UGC play-info retries and WBI-dependent requests away from UI state changes.
    private val playInfoGateway = VideoPlayerPlayInfoGateway(
        apiService = apiService,
        noCookieApiService = noCookieApiService,
        okHttpClient = okHttpClient,
        cookieManager = cookieManager,
        sessionGateway = sessionGateway,
        securityGateway = securityGateway,
        logTag = TAG
    )

    private val subtitleCache = object : LinkedHashMap<String, SubtitleData>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SubtitleData>): Boolean {
            return size > 8
        }
    }

    private val _videoInfo = MutableStateFlow<VideoDetailModel?>(null)
    val videoInfo: StateFlow<VideoDetailModel?> = _videoInfo

    private val _relatedVideos = MutableStateFlow<List<VideoModel>>(emptyList())
    val relatedVideos: StateFlow<List<VideoModel>> = _relatedVideos

    private val _episodes = MutableStateFlow<List<PlayableEpisode>>(emptyList())
    val episodes: StateFlow<List<PlayableEpisode>> = _episodes

    private val _selectedEpisodeIndex = MutableStateFlow(0)
    val selectedEpisodeIndex: StateFlow<Int> = _selectedEpisodeIndex

    private val _playbackRequest = MutableStateFlow<PlaybackRequest?>(null)
    val playbackRequest: StateFlow<PlaybackRequest?> = _playbackRequest

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition

    private val sponsorBlockUseCase = SponsorBlockUseCase()

    sealed interface SponsorSkipUiState {
        data object Hidden : SponsorSkipUiState
        data class ShowButton(val segment: SponsorSegment) : SponsorSkipUiState
        data class AutoSkipped(val segment: SponsorSegment) : SponsorSkipUiState
    }

    private val _sponsorSkipState = MutableStateFlow<SponsorSkipUiState>(SponsorSkipUiState.Hidden)
    val sponsorSkipState: StateFlow<SponsorSkipUiState> = _sponsorSkipState
    private var sponsorSkipPending = false
    private var sponsorLoadJob: Job? = null
    private var pendingSponsorBvid: String? = null
    private var pendingSponsorCid: Long = 0L
    private var pendingSponsorLoadGeneration: Long = 0L

    private val _sponsorSegments = MutableStateFlow<List<SponsorSegment>>(emptyList())
    val sponsorSegments: StateFlow<List<SponsorSegment>> = _sponsorSegments

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _qualities = MutableStateFlow<List<VideoQuality>>(emptyList())
    val qualities: StateFlow<List<VideoQuality>> = _qualities

    private val _selectedQuality = MutableStateFlow<VideoQuality?>(null)
    val selectedQuality: StateFlow<VideoQuality?> = _selectedQuality

    private val _audioQualities = MutableStateFlow<List<AudioQuality>>(emptyList())
    val audioQualities: StateFlow<List<AudioQuality>> = _audioQualities

    private val _selectedAudioQuality = MutableStateFlow<AudioQuality?>(null)
    val selectedAudioQuality: StateFlow<AudioQuality?> = _selectedAudioQuality

    private val _videoCodecs = MutableStateFlow<List<VideoCodecEnum>>(emptyList())
    val videoCodecs: StateFlow<List<VideoCodecEnum>> = _videoCodecs

    private val _selectedVideoCodec = MutableStateFlow<VideoCodecEnum?>(null)
    val selectedVideoCodec: StateFlow<VideoCodecEnum?> = _selectedVideoCodec

    private val _subtitles = MutableStateFlow<List<SubtitleInfoModel>>(emptyList())
    val subtitles: StateFlow<List<SubtitleInfoModel>> = _subtitles

    private val _selectedSubtitleIndex = MutableStateFlow(-1)
    val selectedSubtitleIndex: StateFlow<Int> = _selectedSubtitleIndex

    private val _currentSubtitleText = MutableStateFlow<String?>(null)
    val currentSubtitleText: StateFlow<String?> = _currentSubtitleText

    // ==================== 弹幕系统（转发到 DanmakuPlaybackController）====================
    val danmaku: StateFlow<List<DmModel>> get() = danmakuController.danmaku
    internal val danmakuUpdates: SharedFlow<DanmakuPlaybackController.DanmakuUpdate> get() = danmakuController.danmakuUpdates
    val specialDanmaku: StateFlow<List<SpecialDanmakuModel>> get() = danmakuController.specialDanmaku
    internal val dmMaskState: StateFlow<DanmakuPlaybackController.DmMaskState> get() = danmakuController.dmMaskState
    var onDmMaskReady: ((maskUrl: String, cid: Long, fps: Int) -> Unit)?
        get() = danmakuController.onDmMaskReady
        set(value) { danmakuController.onDmMaskReady = value }
    var onDmMaskReset: (() -> Unit)?
        get() = danmakuController.onDmMaskReset
        set(value) { danmakuController.onDmMaskReset = value }

    // ==================== 互动视频 ====================
    private val interactionEngine = InteractionEngine()
    private val interactionRepository = InteractionRepository(apiService)

    private val _interactionModel = MutableStateFlow<InteractionModel?>(null)
    val interactionModel: StateFlow<InteractionModel?> = _interactionModel

    private val _interactionHiddenVars = MutableStateFlow<List<InteractionVariableModel>?>(null)
    val interactionHiddenVars: StateFlow<List<InteractionVariableModel>?> = _interactionHiddenVars

    private var interactionProgressRestored = false
    private var interactionLoadingEdgeId: Long = -1L
    private var isSteinsGateVideo = false

    fun getInteractionEngine(): InteractionEngine = interactionEngine

    val dmMaskRepository = DmMaskRepository()

    private val _videoSnapshot = MutableStateFlow<VideoSnapshotData?>(null)
    val videoSnapshot: StateFlow<VideoSnapshotData?> = _videoSnapshot

    private val _currentCidLive = MutableStateFlow(0L)
    val currentCidLive: StateFlow<Long> = _currentCidLive

    private val _riskControlVVoucher = MutableStateFlow<String?>(null)
    val riskControlVVoucher: StateFlow<String?> = _riskControlVVoucher

    private val _riskControlTryLookBypass = MutableStateFlow(false)
    val riskControlTryLookBypass: StateFlow<Boolean> = _riskControlTryLookBypass

    fun consumeRiskControlVVoucher(): String? {
        val value = _riskControlVVoucher.value
        _riskControlVVoucher.value = null
        return value
    }

    fun onGaiaVgateResult(gaiaVtoken: String) {
        val expiresAt = System.currentTimeMillis() + 12 * 60 * 60 * 1000L
        cookieManager.saveCookies(
            listOf(
                "x-bili-gaia-vtoken=$gaiaVtoken; domain=bilibili.com; path=/; secure; expires=$expiresAt"
            )
        )
        fallbackController.onGaiaVgateVerifiedAndRetry()
    }

    private var currentAid: Long? = null
    private var currentBvid: String? = null
    private var currentCid: Long = 0L
    private var currentSeasonId: Long? = null
    private var currentEpId: Long? = null
    private var currentPlayInfo: PlayInfoModel? = null
    private var currentSubtitleData: SubtitleData? = null
    private var currentSubtitleCueIndex: Int = 0
    private var currentGraphVersion: Long = 0L
    private var currentSettings: PlayerSettings = PlayerSettingsStore.load(appContext)
    private var shouldAutoSelectSubtitle = currentSettings.showSubtitleByDefault
    private val heartbeatReporter = PlaybackHeartbeatReporter(
        apiService = apiService,
        sessionGateway = sessionGateway,
        scope = viewModelScope,
        context = HeartbeatContextImpl()
    )

    private inner class HeartbeatContextImpl : PlaybackHeartbeatReporter.HeartbeatContext {
        override val currentAid: Long? get() = this@VideoPlayerViewModel.currentAid
        override val currentCid: Long get() = this@VideoPlayerViewModel.currentCid
        override val currentBvid: String? get() = this@VideoPlayerViewModel.currentBvid
        override val pendingSeekPositionMs: Long get() = this@VideoPlayerViewModel.pendingSeekPositionMs
        override val currentPositionMs: Long get() = _currentPosition.value
        override val durationMs: Long get() = _duration.value
        override val playInfoDurationMs: Long get() = currentPlayInfo?.timeLength ?: 0L
        override val qualityId: Int
            get() = (_selectedQuality.value?.id ?: selectedQualityId ?: currentPlayInfo?.quality ?: 0)
                .takeIf { it > 0 } ?: 80
    }

    private val danmakuController = DanmakuPlaybackController(
        playInfoGateway = playInfoGateway,
        scope = viewModelScope,
        context = DanmakuContextImpl()
    )

    private val fallbackController = PlaybackFallbackController(
        streamResolver = streamResolver,
        dashMediaSourceFactory = dashMediaSourceFactory,
        qualityPolicy = qualityPolicy,
        playInfoGateway = playInfoGateway,
        scope = viewModelScope,
        context = FallbackContextImpl()
    )

    private inner class DanmakuContextImpl : DanmakuPlaybackController.DanmakuPlaybackContext {
        override val currentCid: Long get() = this@VideoPlayerViewModel.currentCid
        override val currentAid: Long? get() = this@VideoPlayerViewModel.currentAid
        override val hasReachedFirstFrame: Boolean get() = this@VideoPlayerViewModel.hasReachedFirstFrame
        override val pendingSeekPositionMs: Long get() = this@VideoPlayerViewModel.pendingSeekPositionMs
        override val currentPositionMs: Long get() = _currentPosition.value
        override val durationMs: Long get() = _duration.value
        override val startupTraceId: String get() = currentStartupTraceId
        override val startupTraceStartElapsedMs: Long get() = currentStartupTraceStartElapsedMs
        override val videoLoadGeneration: Long get() = this@VideoPlayerViewModel.videoLoadGeneration
        override fun isActiveVideoLoad(loadGeneration: Long): Boolean =
            this@VideoPlayerViewModel.isActiveVideoLoad(loadGeneration)
    }

    private inner class FallbackContextImpl : PlaybackFallbackController.FallbackContext {
        // ===== 只读播放上下文（实时读 VM 字段） =====
        override val currentPlayInfo: PlayInfoModel? get() = this@VideoPlayerViewModel.currentPlayInfo
        override val currentCid: Long get() = this@VideoPlayerViewModel.currentCid
        override val currentAid: Long? get() = this@VideoPlayerViewModel.currentAid
        override val currentBvid: String? get() = this@VideoPlayerViewModel.currentBvid
        override val currentEpId: Long? get() = this@VideoPlayerViewModel.currentEpId
        override val currentSeasonId: Long? get() = this@VideoPlayerViewModel.currentSeasonId
        override val selectedQualityId: Int? get() = this@VideoPlayerViewModel.selectedQualityId
        override val selectedCodec: VideoCodecEnum? get() = this@VideoPlayerViewModel.selectedCodec
        override val requestedQualityId: Int? get() = this@VideoPlayerViewModel.requestedQualityId
        override val requestedCodec: VideoCodecEnum? get() = this@VideoPlayerViewModel.requestedCodec
        override val useDashPlayback: Boolean get() = this@VideoPlayerViewModel.useDashPlayback
        override val hardwareSupportedVideoCodecs: Set<VideoCodecEnum>
            get() = this@VideoPlayerViewModel.hardwareSupportedVideoCodecs
        override val activePlaybackIntentId: String get() = this@VideoPlayerViewModel.activePlaybackIntentId
        override val startupTraceId: String get() = this@VideoPlayerViewModel.currentStartupTraceId
        override val startupTraceStartElapsedMs: Long get() = this@VideoPlayerViewModel.currentStartupTraceStartElapsedMs

        // ===== 共享状态读（实时读 VM 字段，不缓存） =====
        override val dashSession: VideoPlaybackSession? get() = this@VideoPlayerViewModel.currentDashSession
        override val streamFallbackPlan: VideoPlayerStreamResolver.StreamFallbackPlan?
            get() = this@VideoPlayerViewModel.currentStreamFallbackPlan
        override val fallbackRouteIndex: Int get() = this@VideoPlayerViewModel.fallbackRouteIndex
        override val fallbackCdnIndex: Int get() = this@VideoPlayerViewModel.fallbackCdnIndex

        // ===== 共享状态写（提议新值，由 VM 主线程落地） =====
        override fun onDashSessionUpdated(session: VideoPlaybackSession?) {
            this@VideoPlayerViewModel.currentDashSession = session
        }

        override fun onStreamFallbackPlanUpdated(
            plan: VideoPlayerStreamResolver.StreamFallbackPlan?,
            routeIndex: Int,
            cdnIndex: Int
        ) {
            this@VideoPlayerViewModel.currentStreamFallbackPlan = plan
            this@VideoPlayerViewModel.fallbackRouteIndex = routeIndex
            this@VideoPlayerViewModel.fallbackCdnIndex = cdnIndex
        }

        // ===== VM 私有方法转发 =====
        override fun currentPlayRequestIdentity(): PlayRequestIdentity? =
            this@VideoPlayerViewModel.currentPlayRequestIdentity()

        override fun emitRiskControlTryLookBypass() {
            _riskControlTryLookBypass.value = true
        }

        // ===== UI/派发写 =====
        override fun emitPlaybackRequest(request: PlaybackRequest) {
            _playbackRequest.value = request
        }

        override fun setSelectedCodec(codec: VideoCodecEnum) {
            this@VideoPlayerViewModel.selectedCodec = codec
            _selectedVideoCodec.value = codec
        }

        override fun clearError() {
            _error.value = null
        }

        override fun reportError(message: String) {
            _error.value = message
        }

        // ===== 加载主链回调（转发到 VM 私有方法） =====
        override suspend fun requestPreparedPlayback(
            identity: PlayRequestIdentity,
            preferLastPlayTime: Boolean,
            replaceInPlace: Boolean,
            playbackPositionMs: Long,
            playWhenReady: Boolean,
            qualityCandidates: List<Int>
        ): PreparedPlayback? = this@VideoPlayerViewModel.requestPreparedPlayback(
            identity = identity,
            preferLastPlayTime = preferLastPlayTime,
            replaceInPlace = replaceInPlace,
            playbackPositionMs = playbackPositionMs,
            playWhenReady = playWhenReady,
            qualityCandidates = qualityCandidates
        )

        override fun applyPreparedPlayback(
            preparedPlayback: PreparedPlayback,
            resetFallbackAttempts: Boolean,
            countCurrentAttemptAsFallback: Boolean
        ) = this@VideoPlayerViewModel.applyPreparedPlayback(
            preparedPlayback = preparedPlayback,
            resetFallbackAttempts = resetFallbackAttempts,
            countCurrentAttemptAsFallback = countCurrentAttemptAsFallback
        )
    }

    private var requestedQualityId: Int? = null
    private var requestedAudioId: Int? = null
    private var requestedCodec: VideoCodecEnum? = null
    private var selectedQualityId: Int? = null
    private var selectedAudioId: Int? = null
    private var selectedCodec: VideoCodecEnum? = null
    private var pendingSeekPositionMs: Long = 0L
    private var pendingPlayWhenReady: Boolean = true
    private var didApplyLastPlayPosition = false
    private var launchStartEpisodeIndex: Int = -1
    private var videoLoadGeneration: Long = 0L
    private var douyinWarmupJob: Job? = null

    private var currentStreamFallbackPlan: VideoPlayerStreamResolver.StreamFallbackPlan? = null
    private var fallbackRouteIndex: Int = 0
    private var fallbackCdnIndex: Int = 0
    private var pendingResumeToastPositionMs: Long? = null
    private var preloadedPlayback: PreloadedPlayback? = null
    private var preloadingIdentity: PlayRequestIdentity? = null
    private var preloadJob: Job? = null
    private val continuationSessions = linkedMapOf<String, ContinuationPlaybackIntent>()
    private var activePlaybackIntentId: String = ""
    private var pendingContinuationIntentId: String? = null
    private var hasReachedFirstFrame: Boolean = false
    private var currentStartupTraceId: String = PlaybackStartupTrace.NO_TRACE
    private var currentStartupTraceStartElapsedMs: Long = 0L
    private var pendingPlayerExtrasCid: Long = 0L
    private var loadedPlayerExtrasCid: Long = 0L
    private val hardwareSupportedVideoCodecs: Set<VideoCodecEnum>
        get() = VideoCodecSupport.getHardwareSupportedCodecs()


    data class SavedPlayerSnapshot(
        val aid: Long,
        val bvid: String,
        val cid: Long,
        val epId: Long,
        val seasonId: Long,
        val episodeIndex: Int,
        val seekPositionMs: Long,
        val qualityId: Int,
        val audioQualityId: Int,
        val subtitleIndex: Int
    )

    fun savePlayerSnapshot() {
        val aid = currentAid ?: 0L
        val bvid = currentBvid.orEmpty()
        val cid = currentCid
        if (cid <= 0L && aid <= 0L && bvid.isBlank()) return
        val positionMs = _currentPosition.value.coerceAtLeast(0L)
            .takeIf { it > 0L } ?: pendingSeekPositionMs.coerceAtLeast(0L)
        if (bvid.isNotBlank() && cid > 0L && positionMs > 0L) {
            VideoPlayerPlayInfoCache.updateLastPlayPosition(bvid, cid, positionMs, cid)
        }
        savedStateHandle[SAVED_AID] = aid
        savedStateHandle[SAVED_BVID] = bvid
        savedStateHandle[SAVED_CID] = cid
        savedStateHandle[SAVED_EP_ID] = currentEpId ?: 0L
        savedStateHandle[SAVED_SEASON_ID] = currentSeasonId ?: 0L
        savedStateHandle[SAVED_EPISODE_INDEX] = _selectedEpisodeIndex.value ?: 0
        savedStateHandle[SAVED_SEEK_POSITION_MS] = pendingSeekPositionMs.coerceAtLeast(0L)
        savedStateHandle[SAVED_QUALITY_ID] = requestedQualityId ?: selectedQualityId ?: 0
        savedStateHandle[SAVED_AUDIO_QUALITY_ID] = requestedAudioId ?: selectedAudioId ?: 0
        savedStateHandle[SAVED_SUBTITLE_INDEX] = _selectedSubtitleIndex.value ?: -1
    }

    fun consumeSavedSnapshot(): SavedPlayerSnapshot? {
        val aid = savedStateHandle.remove<Long>(SAVED_AID) ?: return null
        val bvid = savedStateHandle.remove<String>(SAVED_BVID).orEmpty()
        val cid = savedStateHandle.remove<Long>(SAVED_CID) ?: 0L
        if (cid <= 0L && aid <= 0L && bvid.isBlank()) return null
        val epId = savedStateHandle.remove<Long>(SAVED_EP_ID) ?: 0L
        val seasonId = savedStateHandle.remove<Long>(SAVED_SEASON_ID) ?: 0L
        val episodeIndex = savedStateHandle.remove<Int>(SAVED_EPISODE_INDEX) ?: 0
        val seekPositionMs = savedStateHandle.remove<Long>(SAVED_SEEK_POSITION_MS) ?: 0L
        val qualityId = savedStateHandle.remove<Int>(SAVED_QUALITY_ID) ?: 0
        val audioQualityId = savedStateHandle.remove<Int>(SAVED_AUDIO_QUALITY_ID) ?: 0
        val subtitleIndex = savedStateHandle.remove<Int>(SAVED_SUBTITLE_INDEX) ?: -1
        return SavedPlayerSnapshot(
            aid = aid,
            bvid = bvid,
            cid = cid,
            epId = epId,
            seasonId = seasonId,
            episodeIndex = episodeIndex,
            seekPositionMs = seekPositionMs,
            qualityId = qualityId,
            audioQualityId = audioQualityId,
            subtitleIndex = subtitleIndex
        )
    }

    fun loadVideoInfo(
        aid: Long? = null,
        bvid: String? = null,
        cid: Long = 0L,
        seasonId: Long = 0L,
        epId: Long = 0L,
        seekPositionMs: Long = 0L,
        startEpisodeIndex: Int = -1,
        preferredQualityId: Int = 0,
        preferredAudioQualityId: Int = 0,
        startupTraceId: String = PlaybackStartupTrace.NO_TRACE,
        startupTraceStartElapsedMs: Long = 0L,
        isSteinsGate: Boolean = false,
        preferLastPlayTime: Boolean? = null,
        playbackIntentId: String = UUID.randomUUID().toString()
    ) {
        startPlayback(
            PlaybackStartIntent(
                id = playbackIntentId,
                source = if (playbackIntentId == pendingContinuationIntentId) {
                    PlaybackStartSource.CONTINUATION
                } else {
                    PlaybackStartSource.NORMAL
                },
                aid = aid,
                bvid = bvid,
                cid = cid,
                seasonId = seasonId,
                epId = epId,
                seekPositionMs = seekPositionMs,
                startEpisodeIndex = startEpisodeIndex,
                preferredQualityId = preferredQualityId,
                preferredAudioQualityId = preferredAudioQualityId,
                startupTraceId = startupTraceId,
                startupTraceStartElapsedMs = startupTraceStartElapsedMs,
                isSteinsGate = isSteinsGate,
                preferLastPlayTime = preferLastPlayTime
            )
        )
    }

    private fun startPlayback(startIntent: PlaybackStartIntent) {
        val aid = startIntent.aid
        val bvid = startIntent.bvid
        val cid = startIntent.cid
        val seasonId = startIntent.seasonId
        val epId = startIntent.epId
        val seekPositionMs = startIntent.seekPositionMs
        val startEpisodeIndex = startIntent.startEpisodeIndex
        val preferredQualityId = startIntent.preferredQualityId
        val preferredAudioQualityId = startIntent.preferredAudioQualityId
        val startupTraceId = startIntent.startupTraceId
        val startupTraceStartElapsedMs = startIntent.startupTraceStartElapsedMs
        val isSteinsGate = startIntent.isSteinsGate
        val preferLastPlayTime = startIntent.preferLastPlayTime

        activePlaybackIntentId = startIntent.id
        currentStartupTraceId = startupTraceId
        currentStartupTraceStartElapsedMs = startupTraceStartElapsedMs
        danmakuController.resetStartupTraceState()
        pendingResumeToastPositionMs = null
        PlaybackStartupTrace.log(
            traceId = currentStartupTraceId,
            startElapsedMs = currentStartupTraceStartElapsedMs,
            step = "playback_intent_created",
            message = "id=${startIntent.id} source=${startIntent.source} aid=${aid ?: 0L} " +
                "bvid=${bvid.orEmpty()} cid=$cid epId=$epId seasonId=$seasonId seek=$seekPositionMs"
        )
        PlaybackStartupTrace.log(
            traceId = currentStartupTraceId,
            startElapsedMs = currentStartupTraceStartElapsedMs,
            step = "load_video_info",
            message = "intentId=${startIntent.id} aid=${aid ?: 0L} bvid=${bvid.orEmpty()} cid=$cid epId=$epId seasonId=$seasonId"
        )
        currentSettings = PlayerSettingsStore.load(appContext)
        currentAid = aid?.takeIf { it > 0L }
        currentBvid = bvid?.takeIf { it.isNotBlank() }
        currentCid = cid
        currentSeasonId = seasonId.takeIf { it > 0L }
        currentEpId = epId.takeIf { it > 0L }
        pendingSeekPositionMs = seekPositionMs.coerceAtLeast(0L)
        pendingPlayWhenReady = true
        launchStartEpisodeIndex = startEpisodeIndex
        val loadGeneration = ++videoLoadGeneration
        val chainStartMs = System.currentTimeMillis()
        val isSameVideoReplay = cid > 0L && isRecentlyPlayed(cid)
        if (isSameVideoReplay) {
        }
        // 先清理上一条播放的预加载，再启动当前视频弹幕预热；否则后续初始化会把刚启动的弹幕预热取消掉。
        clearPreloadedPlaybackIfDifferent(currentPlayRequestIdentity(), cancelJob = true)
        viewModelScope.launch {
            runCatching { playInfoGateway.warmupWbiKeys() }
        }

        // 入口只预取弹幕 view 元数据；分片解析放到首帧后，避免与解码起播抢 CPU。
        if (cid > 0L && (aid ?: 0L) > 0L) {
            danmakuController.preloadView(cid = cid, aid = aid ?: 0L, loadGeneration = loadGeneration)
        }

        prepareDeferredSponsorLoad(
            bvid = bvid,
            cid = cid,
            loadGeneration = loadGeneration
        )

        viewModelScope.launch {
            _isLoading.value = true
            currentPlayInfo = null
            currentSubtitleData = null
            currentGraphVersion = 0L
            loadedPlayerExtrasCid = 0L
            pendingPlayerExtrasCid = 0L
            requestedQualityId = preferredQualityId.takeIf { it > 0 } ?: currentSettings.defaultVideoQualityId
            requestedAudioId = preferredAudioQualityId.takeIf { it > 0 } ?: currentSettings.defaultAudioQualityId
            requestedCodec = currentSettings.defaultVideoCodec
            selectedQualityId = null
            selectedAudioId = null
            selectedCodec = null
            didApplyLastPlayPosition = pendingSeekPositionMs > 0L
            shouldAutoSelectSubtitle = currentSettings.showSubtitleByDefault
            heartbeatReporter.clear()
            fallbackController.reset()
            // 自动连播倒计时已经准备好的同一目标不能在入口重置时被清掉，否则会退回冷启动链路。
            clearPreloadedPlaybackIfDifferent(currentPlayRequestIdentity(), cancelJob = false)
            hasReachedFirstFrame = false
            currentDashSession = null
            _selectedSubtitleIndex.value = -1
            _currentSubtitleText.value = null
            currentSubtitleCueIndex = 0
            danmakuController.clear()
            sponsorLoadJob?.cancel()
            sponsorBlockUseCase.reset()
            _sponsorSkipState.value = SponsorSkipUiState.Hidden
            _sponsorSegments.value = emptyList()
            danmakuController.resetDmMask()
            _interactionModel.value = null
            _interactionHiddenVars.value = null
            interactionEngine.reset()
            interactionRepository.clearCache()
            interactionProgressRestored = false
            interactionLoadingEdgeId = -1L
            isSteinsGateVideo = isSteinsGate
            _videoSnapshot.value = null
            _error.value = null
            _qualities.value = emptyList()
            _selectedQuality.value = null
            _audioQualities.value = emptyList()
            _selectedAudioQuality.value = null
            _videoCodecs.value = emptyList()
            _selectedVideoCodec.value = null
            try {
                val effectivePreferLastPlayTime = preferLastPlayTime ?: currentSettings.resumePlayback
                if (isPgcPlayback()) {
                    loadPgcVideoInfo(
                        preferLastPlayTime = effectivePreferLastPlayTime,
                        loadGeneration = loadGeneration
                    )
                    return@launch
                }
                loadUgcVideoInfo(
                    preferLastPlayTime = effectivePreferLastPlayTime,
                    loadGeneration = loadGeneration
                )
            } catch (e: Exception) {
                AppLog.e(TAG, "loadVideoInfo exception: ${e.message}", e)
                _error.value = e.message ?: "播放器初始化失败"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun playPrevious() {
        val episodes = _episodes.value.orEmpty()
        val targetIndex = (_selectedEpisodeIndex.value ?: 0) - 1
        if (targetIndex in episodes.indices) {
            playEpisode(targetIndex)
        }
    }

    fun hasPreviousEpisode(): Boolean {
        val previousIndex = (_selectedEpisodeIndex.value ?: 0) - 1
        return previousIndex in _episodes.value.orEmpty().indices
    }

    fun playNext(preferLastPlayTime: Boolean = true) {
        val episodes = _episodes.value.orEmpty()
        val targetIndex = (_selectedEpisodeIndex.value ?: 0) + 1
        if (targetIndex in episodes.indices) {
            playEpisode(targetIndex, preferLastPlayTime = preferLastPlayTime)
        }
    }

    fun hasNextEpisode(): Boolean {
        val episodes = _episodes.value.orEmpty()
        val nextIndex = (_selectedEpisodeIndex.value ?: 0) + 1
        return nextIndex in episodes.indices
    }

    fun getNextEpisode(): PlayableEpisode? {
        val nextIndex = (_selectedEpisodeIndex.value ?: 0) + 1
        return _episodes.value.orEmpty().getOrNull(nextIndex)
    }

    fun playEpisode(index: Int, preferLastPlayTime: Boolean = true) {
        val episode = _episodes.value.orEmpty().getOrNull(index) ?: return
        reportPlaybackHeartbeat()
        savePlayerSnapshot()
        val targetBvid = episode.bvid.takeIf { it.isNotBlank() }
        val targetSeasonId = episode.seasonId.takeIf { it > 0L }
        val targetEpId = episode.epId.takeIf { it > 0L }
        if (
            isPgcPlayback() &&
            targetSeasonId != null &&
            currentSeasonId != null &&
            targetSeasonId != currentSeasonId
        ) {
            loadVideoInfo(
                aid = episode.aid,
                bvid = targetBvid,
                cid = episode.cid,
                seasonId = targetSeasonId,
                epId = targetEpId ?: 0L,
                preferLastPlayTime = preferLastPlayTime
            )
            return
        }
        if (
            !isPgcPlayback() &&
            targetBvid != null &&
            targetBvid != currentBvid
        ) {
            loadVideoInfo(
                aid = episode.aid,
                bvid = targetBvid,
                cid = episode.cid,
                preferLastPlayTime = preferLastPlayTime
            )
            return
        }
        _selectedEpisodeIndex.value = index
        pendingSeekPositionMs = 0L
        pendingPlayWhenReady = true
        didApplyLastPlayPosition = false
        currentCid = episode.cid
        _currentCidLive.value = currentCid
        currentAid = episode.aid.takeIf { it > 0 } ?: currentAid
        currentBvid = episode.bvid.takeIf { it.isNotBlank() } ?: targetBvid ?: currentBvid.orEmpty()
        currentSeasonId = targetSeasonId ?: currentSeasonId
        currentEpId = targetEpId ?: currentEpId
        currentSubtitleData = null
        currentSubtitleCueIndex = 0
        _selectedSubtitleIndex.value = -1
        _currentSubtitleText.value = null
        _interactionModel.value = null
        _interactionHiddenVars.value = null
        interactionEngine.reset()
        interactionRepository.clearCache()
        interactionProgressRestored = false
        interactionLoadingEdgeId = -1L
        danmakuController.markDmMaskIdle()
        _videoSnapshot.value = null
        _error.value = null
        clearPreloadedPlaybackIfDifferent(currentPlayRequestIdentity(), cancelJob = false)
        loadPlayUrl(preferLastPlayTime = preferLastPlayTime)
    }

    fun playRelatedVideo(video: VideoModel, preferLastPlayTime: Boolean = true) {
        val targetAid = video.aid.takeIf { it > 0L } ?: currentAid
        val targetBvid = video.bvid.takeIf { it.isNotBlank() } ?: currentBvid
        val targetSeasonId = video.playbackSeasonId.takeIf { it > 0L }
        val targetEpId = video.playbackEpId.takeIf { it > 0L }
        if (targetAid == null && targetBvid.isNullOrBlank() && targetEpId == null && targetSeasonId == null) {
            _error.value = "相关推荐缺少视频标识"
            return
        }
        reportPlaybackHeartbeat()
        val targetIdentity = PlayRequestIdentity(
            aid = targetAid,
            bvid = targetBvid?.takeIf { it.isNotBlank() },
            cid = video.cid,
            epId = targetEpId
        )
        clearPreloadedPlaybackIfDifferent(targetIdentity, cancelJob = false)
        loadVideoInfo(
            aid = targetAid,
            bvid = targetBvid,
            cid = video.cid,
            seasonId = targetSeasonId ?: 0L,
            epId = targetEpId ?: 0L,
            preferLastPlayTime = preferLastPlayTime,
            playbackIntentId = "douyin:${UUID.randomUUID()}"
        )
    }

    fun playInteractionChoice(cid: Long, edgeId: Long) {
        if (cid <= 0L) return
        AppLog.d(TAG, "playInteractionChoice: cid=$cid, edgeId=$edgeId")
        reportPlaybackHeartbeat()
        currentCid = cid
        _currentCidLive.value = cid
        pendingSeekPositionMs = 0L
        pendingPlayWhenReady = true
        didApplyLastPlayPosition = false
        currentSeasonId = null
        currentEpId = null
        currentSubtitleData = null
        currentSubtitleCueIndex = 0
        _selectedSubtitleIndex.value = -1
        _currentSubtitleText.value = null
        danmakuController.markDmMaskIdle()
        clearPreloadedPlaybackIfDifferent(currentPlayRequestIdentity(), cancelJob = false)
        loadPlayUrl(preferLastPlayTime = false)
        loadInteractionInfo(edgeId)
        loadVideoSnapshot()
    }

    fun selectVideoQuality(
        quality: VideoQuality,
        currentPositionMs: Long,
        playWhenReady: Boolean
    ) {
        requestedQualityId = quality.id
        _selectedQuality.value = quality
        savePlayerSnapshot()
        capturePlaybackSnapshot(currentPositionMs, playWhenReady)
        loadPlayUrl(preferLastPlayTime = false, replaceInPlace = true)
    }

    fun selectAudioQuality(
        quality: AudioQuality,
        currentPositionMs: Long,
        playWhenReady: Boolean
    ) {
        AppLog.i(TAG, "selectAudioQuality: id=${quality.id} name=${quality.name} bandwidth=${quality.bandwidth} codecId=${quality.codecId}")
        requestedAudioId = quality.id
        _selectedAudioQuality.value = quality
        savePlayerSnapshot()
        capturePlaybackSnapshot(currentPositionMs, playWhenReady)
        rebuildPlayback()
    }

    fun selectVideoCodec(
        codec: VideoCodecEnum,
        currentPositionMs: Long,
        playWhenReady: Boolean
    ) {
        requestedCodec = codec
        _selectedVideoCodec.value = codec
        capturePlaybackSnapshot(currentPositionMs, playWhenReady)
        rebuildPlayback()
    }

    fun onSoftwareVideoDecoderDetected(
        decoderName: String,
        currentPositionMs: Long,
        playWhenReady: Boolean
    ) {
        // 仅记录诊断信息，不主动降级画质。卡顿与否交给用户自行判断并手动切换画质，
        // 避免设备支持硬解却被一次误判永久封顶到 720P 的问题。
        val currentQualityId = selectedQualityId ?: requestedQualityId ?: currentPlayInfo?.quality ?: 0
        AppLog.w(
            TAG,
            "software decoder detected (no auto-downgrade): decoder=$decoderName " +
                "quality=$currentQualityId codec=${selectedCodec ?: requestedCodec} " +
                "pos=${currentPositionMs}ms"
        )
    }

    fun selectSubtitle(index: Int) {
        _selectedSubtitleIndex.value = index
        savedStateHandle[SAVED_SUBTITLE_INDEX] = index
        if (index < 0) {
            currentSubtitleData = null
            currentSubtitleCueIndex = 0
            _currentSubtitleText.value = null
            return
        }
        val subtitle = _subtitles.value.orEmpty().getOrNull(index) ?: return
        viewModelScope.launch {
            currentSubtitleData = loadSubtitleData(subtitle)
            currentSubtitleCueIndex = 0
            updateSubtitleText(_currentPosition.value ?: 0L)
        }
    }

    fun updatePlaybackPosition(
        positionMs: Long,
        durationMs: Long,
        publishProgressState: Boolean = true
    ) {
        val sanitizedPositionMs = positionMs.coerceAtLeast(0L)
        val sanitizedDurationMs = durationMs.takeIf { it > 0L } ?: 0L
        if (!sponsorSkipPending) {
            pendingSeekPositionMs = sanitizedPositionMs
        }
        if (publishProgressState) {
            if (_currentPosition.value != sanitizedPositionMs) {
                _currentPosition.value = sanitizedPositionMs
            }
            if (_duration.value != sanitizedDurationMs) {
                _duration.value = sanitizedDurationMs
            }
        }
        updateSubtitleText(sanitizedPositionMs)
        checkSponsorBlock(sanitizedPositionMs)
        // 弹幕分段同步：seek 跳变检测 → 补全目标位置弹幕；更新 lastSync；分段切换加载。
        // 整体由 controller 封装，pendingSeekPositionMs 已在上方写入。
        danmakuController.onPositionChanged(sanitizedPositionMs)
    }

    fun setLoading(loading: Boolean) {
        _isLoading.value = loading
    }

    fun resetPlaybackProgress() {
        _currentPosition.value = 0L
        _duration.value = 0L
    }

    fun setErrorMessage(message: String?) {
        _error.value = message
    }

    fun prepareContinuation(intent: ContinuationPlaybackIntent?): ContinuationSession? {
        if (intent == null) {
            clearPendingContinuation()
            return null
        }
        val identity = intent.target.toPlayRequestIdentity() ?: run {
            AppLog.w(TAG, "continuation_created_failed id=${intent.id} mode=${intent.mode} reason=invalid_identity")
            return null
        }
        continuationSessions[intent.id] = intent
        while (continuationSessions.size > 2) {
            continuationSessions.remove(continuationSessions.keys.first())
        }
        AppLog.i(
            TAG,
            "continuation_created id=${intent.id} mode=${intent.mode} kind=${intent.kind} " +
                "cid=${identity.cid} epId=${identity.epId ?: 0L} preferLastPlayTime=${intent.preferLastPlayTime}"
        )
        PlaybackStartupTrace.log(
            traceId = currentStartupTraceId,
            startElapsedMs = currentStartupTraceStartElapsedMs,
            step = "continuation_created",
            message = "id=${intent.id} mode=${intent.mode} kind=${intent.kind} cid=${identity.cid} epId=${identity.epId ?: 0L}"
        )
        preloadPlayback(intent.target, continuationIntentId = intent.id)
        return ContinuationSession(id = intent.id, intent = intent)
    }

    fun clearPendingContinuation() {
        continuationSessions.clear()
        pendingContinuationIntentId = null
        clearPreloadedPlayback(cancelJob = true)
        AppLog.i(TAG, "continuation_cleared")
    }

    fun playContinuation(sessionId: String) {
        val intent = continuationSessions.remove(sessionId) ?: run {
            AppLog.w(TAG, "continuation_play_missing id=$sessionId")
            return
        }
        continuationSessions.clear()
        val identity = intent.target.toPlayRequestIdentity() ?: run {
            _error.value = "连播目标缺少视频标识"
            AppLog.w(TAG, "continuation_play_failed id=$sessionId reason=invalid_identity")
            return
        }
        activePlaybackIntentId = intent.id
        pendingContinuationIntentId = intent.id
        val hasReadyPreload = preloadedPlayback?.preparedPlayback?.identity == identity
        val hasRunningPreload = preloadingIdentity == identity
        AppLog.i(
            TAG,
            "continuation_play id=${intent.id} mode=${intent.mode} kind=${intent.kind} " +
                "cid=${identity.cid} epId=${identity.epId ?: 0L} readyPreload=$hasReadyPreload runningPreload=$hasRunningPreload"
        )
        PlaybackStartupTrace.log(
            traceId = currentStartupTraceId,
            startElapsedMs = currentStartupTraceStartElapsedMs,
            step = "continuation_play",
            message = "id=${intent.id} cid=${identity.cid} readyPreload=$hasReadyPreload runningPreload=$hasRunningPreload"
        )
        playContinuationIntent(intent, identity)
    }

    private fun playContinuationIntent(
        intent: ContinuationPlaybackIntent,
        identity: PlayRequestIdentity
    ) {
        // 优先按原分集下标播放；如果列表已刷新导致下标不可信，则退回到 intent 里的精确身份。
        val episodeIndex = intent.episodeIndex
        val episode = episodeIndex?.let { _episodes.value.orEmpty().getOrNull(it) }
        if (episode != null && episode.matches(identity)) {
            playEpisode(episodeIndex, preferLastPlayTime = intent.preferLastPlayTime)
            return
        }

        reportPlaybackHeartbeat()
        savePlayerSnapshot()
        loadVideoInfo(
            aid = identity.aid,
            bvid = identity.bvid,
            cid = identity.cid,
            seasonId = intent.target.seasonId ?: 0L,
            epId = identity.epId ?: 0L,
            seekPositionMs = intent.startPositionMs,
            preferLastPlayTime = intent.preferLastPlayTime,
            playbackIntentId = intent.id
        )
    }

    private fun PlayableEpisode.matches(identity: PlayRequestIdentity): Boolean {
        if (cid != identity.cid) return false
        val targetEpId = identity.epId
        if (targetEpId != null && epId != targetEpId) return false
        val targetBvid = identity.bvid
        if (!targetBvid.isNullOrBlank() && bvid.isNotBlank() && bvid != targetBvid) return false
        return true
    }

    fun preloadPlayback(target: PlaybackPreloadTarget?) {
        preloadPlayback(target, continuationIntentId = null)
    }

    private fun preloadPlayback(target: PlaybackPreloadTarget?, continuationIntentId: String?) {
        val identity = target?.toPlayRequestIdentity()
        val currentIdentity = currentPlayRequestIdentity()

        if (target == null) {
            AppLog.i(TAG, "playback_preload_clear reason=null_target")
            clearPreloadedPlayback(cancelJob = true)
            return
        }
        if (!hasReachedFirstFrame && target.source != PlaybackPreloadTarget.Source.AUTOPLAY_COUNTDOWN && target.source != PlaybackPreloadTarget.Source.DOUYIN_MODE) {
            AppLog.i(TAG, "playback_preload_skip reason=before_first_frame source=${target.source}")
            return
        }
        if (
            target.source != PlaybackPreloadTarget.Source.AUTOPLAY_COUNTDOWN
            && target.source != PlaybackPreloadTarget.Source.DOUYIN_MODE
        ) {
            AppLog.i(TAG, "playback_preload_skip reason=unsupported_source source=${target.source}")
            return
        }

        if (identity == null || identity == currentIdentity) {
            AppLog.i(TAG, "playback_preload_clear reason=invalid_or_current identity=$identity current=$currentIdentity source=${target.source}")
            clearPreloadedPlayback(cancelJob = true)
            return
        }
        if (identity.cid <= 0L) {
            AppLog.i(TAG, "playback_preload_clear reason=invalid_cid identity=$identity source=${target.source}")
            clearPreloadedPlayback(cancelJob = true)
            return
        }
        val cachedPreload = preloadedPlayback
        if (cachedPreload?.preparedPlayback?.identity == identity && cachedPreload.source == target.source) {
            AppLog.i(TAG, "playback_preload_keep identity=$identity source=${target.source}")
            return
        }
        if (preloadingIdentity == identity) {
            AppLog.i(TAG, "playback_preload_keep_running identity=$identity source=${target.source}")
            return
        }


        preloadJob?.cancel()
        preloadedPlayback = null
        preloadingIdentity = identity
        AppLog.i(TAG, "playback_preload_begin identity=$identity current=$currentIdentity source=${target.source}")
        PlaybackStartupTrace.log(
            traceId = currentStartupTraceId,
            startElapsedMs = currentStartupTraceStartElapsedMs,
            step = "continuation_preload_started",
            message = "id=${continuationIntentId.orEmpty()} source=${target.source} cid=${identity.cid} epId=${identity.epId ?: 0L}"
        )
        preloadJob = viewModelScope.launch {
            val preparedPlayback = runCatching {
                requestPreparedPlayback(
                    identity = identity,
                    preferLastPlayTime = false,
                    replaceInPlace = false,
                    playbackPositionMs = 0L,
                    playWhenReady = true,
                    playbackIntentId = continuationIntentId.orEmpty(),
                    continuationIntentId = continuationIntentId,
                    suppressUiSignals = true
                )
            }.getOrNull()
            if (preloadingIdentity != identity) {
                return@launch
            }
            preloadingIdentity = null
            preloadJob = null
            if (preparedPlayback == null) {
                AppLog.w(TAG, "playback_preload_failed identity=$identity source=${target.source}")
                return@launch
            }
            preloadedPlayback = PreloadedPlayback(
                source = target.source,
                preparedPlayback = preparedPlayback
            )
            AppLog.i(TAG, "playback_preload_ready identity=$identity source=${target.source}")
            val preloadAid = identity.aid?.takeIf { it > 0L }
            preloadAid?.let { aid ->
                danmakuController.preloadView(cid = identity.cid, aid = aid, loadGeneration = videoLoadGeneration)
            }
            if (target.source == PlaybackPreloadTarget.Source.DOUYIN_MODE) {
                warmupDouyinPlayback(
                    identity = identity,
                    aid = preloadAid,
                    preparedPlayback = preparedPlayback
                )
            }
            PlaybackStartupTrace.log(
                traceId = currentStartupTraceId,
                startElapsedMs = currentStartupTraceStartElapsedMs,
                step = "continuation_preload_ready",
                message = "id=${continuationIntentId.orEmpty()} source=${target.source} cid=${identity.cid} epId=${identity.epId ?: 0L} " +
                    "quality=${preparedPlayback.selectionSnapshot.selectedQualityId} " +
                    "codec=${preparedPlayback.selectionSnapshot.selectedCodec} " +
                    "playWhenReady=${preparedPlayback.playWhenReady}"
            )
        }
    }

    private fun warmupDouyinPlayback(
        identity: PlayRequestIdentity,
        aid: Long?,
        preparedPlayback: PreparedPlayback
    ) {
        douyinWarmupJob?.cancel()
        douyinWarmupJob = viewModelScope.launch(Dispatchers.IO) {
            val mediaJob = launch {
                douyinWarmupManager.warmup(
                    playInfo = preparedPlayback.playInfo,
                    selection = preparedPlayback.selectionSnapshot
                )
            }
            val danmakuJob = aid
                ?.takeIf { it > 0L }
                ?.let {
                    launch {
                        danmakuController.warmupDouyinDanmakuSegment(
                            cid = identity.cid,
                            aid = it,
                            segmentIndex = 1
                        )
                    }
            }
            mediaJob.join()
            danmakuJob?.join()
            if (douyinWarmupJob == this.coroutineContext[Job]) {
                douyinWarmupJob = null
            }
        }
    }

    fun reportPlaybackHeartbeat(playType: Int = 0) {
        heartbeatReporter.reportPlaybackHeartbeat(playType)
    }

    private fun rebuildPlayback() {
        val playInfo = currentPlayInfo ?: return
        val selectionSnapshot = resolveSelectionSnapshot(playInfo) ?: run {
            _error.value = "当前清晰度/音轨组合不可播放"
            return
        }

        var dashMediaSource: MediaSource? = null
        if (useDashPlayback) {
            val dashRoutePlan = streamResolver.resolveDashRoutePlan(
                playInfo = playInfo,
                lockedQualityId = selectionSnapshot.selectedQualityId ?: return,
                selectedAudioId = selectionSnapshot.selectedAudioId,
                preferredCodec = selectionSnapshot.selectedCodec,
                hardwareSupportedCodecs = hardwareSupportedVideoCodecs
            )
            if (dashRoutePlan != null && dashRoutePlan.routes.isNotEmpty()) {
                val route = dashRoutePlan.routes.first()
                val sessionExpiryMs = resolveSessionExpiryMs(route)
                try {
                    dashMediaSource = dashMediaSourceFactory.createMediaSource(route)
                    currentDashSession = VideoPlaybackSession(
                        identity = currentDashSession?.identity ?: SessionIdentity(
                            aid = currentAid,
                            bvid = currentBvid,
                            cid = currentCid,
                            epId = currentEpId
                        ),
                        requestedQualityId = selectionSnapshot.selectedQualityId,
                        requestedAudioId = selectionSnapshot.selectedAudioId,
                        requestedCodec = selectionSnapshot.selectedCodec,
                        actualQualityId = selectionSnapshot.selectedQualityId,
                        actualAudioId = dashRoutePlan.selectedAudioId,
                        actualCodec = route.codec,
                        playInfo = playInfo,
                        routePlan = dashRoutePlan,
                        currentRoute = route,
                        expiresAtMs = sessionExpiryMs
                    )
                } catch (_: Exception) {
                    dashMediaSource = null
                }
            }
        }

        val mediaSource = dashMediaSource ?: streamResolver.buildMediaSource(
            playInfo = playInfo,
            selectedQualityId = selectionSnapshot.selectedQualityId,
            selectedAudioId = selectionSnapshot.selectedAudioId,
            selectedCodec = selectionSnapshot.selectedCodec
        )?.mediaSource ?: run {
            _error.value = "当前清晰度/音轨组合不可播放"
            return
        }
        applySelectionSnapshot(selectionSnapshot)
        _playbackRequest.value = PlaybackRequest(
            mediaSource = mediaSource,
            aid = currentAid,
            bvid = currentBvid,
            cid = currentCid,
            seekPositionMs = pendingSeekPositionMs,
            playWhenReady = pendingPlayWhenReady,
            replaceInPlace = true,
            durationMs = playInfo.timeLength,
            playbackIntentId = activePlaybackIntentId,
            startupTraceId = currentStartupTraceId,
            startupTraceStartElapsedMs = currentStartupTraceStartElapsedMs
        )
    }

    private fun loadPlayUrl(
        preferLastPlayTime: Boolean,
        replaceInPlace: Boolean = false,
        loadGeneration: Long = videoLoadGeneration
    ) {
        val identity = currentPlayRequestIdentity()
        if (identity == null) {
            _error.value = "CID 无效"
            return
        }
        if (!isPgcPlayback() && identity.bvid.isNullOrBlank()) {
            _error.value = "BVID 无效"
            return
        }

        viewModelScope.launch {
            _isLoading.value = true
            try {
                val preparedPlayback = consumePreloadedPlayback(
                    identity = identity,
                    preferLastPlayTime = preferLastPlayTime,
                    replaceInPlace = replaceInPlace
                )
                if (preparedPlayback != null) {
                } else {
                }
                val resolvedPlayback = preparedPlayback ?: requestPreparedPlayback(
                    identity = identity,
                    preferLastPlayTime = preferLastPlayTime,
                    replaceInPlace = replaceInPlace
                )
                if (!isActiveVideoLoad(loadGeneration)) {
                    return@launch
                }
                if (resolvedPlayback == null) {
                    _error.value = "播放地址请求失败"
                    return@launch
                }
                applyPreparedPlayback(resolvedPlayback)
            } catch (e: Exception) {
                AppLog.e(TAG, "loadPlayUrl exception: ${e.message}", e)
                _error.value = e.message ?: "播放地址加载失败"
            } finally {
                _isLoading.value = false
            }
        }
    }

    private suspend fun loadPgcVideoInfo(
        preferLastPlayTime: Boolean,
        loadGeneration: Long
    ): Unit = coroutineScope {
        val seasonId = currentSeasonId
        val epId = currentEpId
        val initialIdentity = currentPlayRequestIdentity()
        val initialPreloadedPlayback = initialIdentity?.let { identity ->
            consumePreloadedPlayback(
                identity = identity,
                preferLastPlayTime = false,
                replaceInPlace = false
            )
        }
        val preparedPlaybackDeferred = initialIdentity
            ?.takeIf { initialPreloadedPlayback == null }
            ?.takeIf { it.cid > 0L && it.epId != null }
            ?.let { identity ->
                async {
                    requestPreparedPlayback(
                        identity = identity,
                        preferLastPlayTime = preferLastPlayTime,
                        replaceInPlace = false
                    )
                }
            }
        securityGateway.prewarmWebSession()
        val sectionsDeferred = async {
            seasonId?.takeIf { it > 0L }?.let {
                runCatching { apiService.getVideoEpisodeSections(it) }.getOrNull()
            }
        }
        val detailResponse = apiService.getVideoEpisodes(seasonId, epId)
        val detail = detailResponse.result
        if (!detailResponse.isSuccess || detail == null) {
            if (shouldFallbackToUgcPlayback(detailResponse)) {
                currentSeasonId = null
                currentEpId = null
                loadUgcVideoInfo(preferLastPlayTime = preferLastPlayTime, loadGeneration = loadGeneration)
                return@coroutineScope
            }
            AppLog.e(
                TAG,
                "loadPgcVideoInfo failure: code=${detailResponse.code}, message=${detailResponse.errorMessage}, seasonId=${seasonId ?: 0L}, epId=${epId ?: 0L}"
            )
            _error.value = detailResponse.message.ifBlank { "番剧详情加载失败" }
            return@coroutineScope
        }

        val resolvedSeasonId = detail.seasonId.takeIf { it > 0L } ?: seasonId
        val sectionResult = sectionsDeferred.await()?.result ?: resolvedSeasonId
            ?.takeIf { it > 0L && it != seasonId }
            ?.let { apiService.getVideoEpisodeSections(it).result }
        val mergedDetail = detail.copy(
            episodes = sectionResult?.mainSection?.episodes.orEmpty(),
            section = sectionResult?.section.orEmpty()
        )
        val episodeItems = episodeCatalogBuilder.buildPgcEpisodes(mergedDetail)
        val selectedIndex = episodeCatalogBuilder.resolvePgcEpisodeIndex(
            episodes = episodeItems,
            targetEpId = currentEpId,
            targetCid = currentCid,
            targetBvid = currentBvid,
            fallbackIndex = launchStartEpisodeIndex
        )
        val selectedEpisode = episodeItems.getOrNull(selectedIndex)

        currentSeasonId = resolvedSeasonId
        currentEpId = selectedEpisode?.epId ?: epId
        currentCid = selectedEpisode?.cid ?: currentCid
        currentAid = selectedEpisode?.aid?.takeIf { it > 0L } ?: currentAid
        currentBvid = selectedEpisode?.bvid?.takeIf { it.isNotBlank() } ?: currentBvid

        // 提前启动弹幕 view 请求，和 PlayInfo 并行
        danmakuController.preloadViewIfNeeded(loadGeneration)

        _videoInfo.value = episodeCatalogBuilder.buildPgcVideoDetail(
            detail = mergedDetail,
            selectedEpisode = selectedEpisode,
            fallbackAid = currentAid ?: 0L,
            fallbackBvid = currentBvid.orEmpty(),
            fallbackCid = currentCid
        )
        _episodes.value = episodeItems
        _selectedEpisodeIndex.value = selectedIndex
        _currentCidLive.value = currentCid
        _relatedVideos.value = emptyList()
        _subtitles.value = emptyList()


        if (currentCid <= 0L) {
            _error.value = "未找到可播放剧集"
            return@coroutineScope
        }

        val resolvedIdentity = currentPlayRequestIdentity()
        val canReuseInitialPlayback = canReusePreparedPlayback(initialIdentity, resolvedIdentity)
        val preparedPlayback = if (initialPreloadedPlayback != null && canReuseInitialPlayback) {
            initialPreloadedPlayback
        } else if (
            preparedPlaybackDeferred != null &&
            canReuseInitialPlayback
        ) {
            PlaybackStartupTrace.log(
                traceId = currentStartupTraceId,
                startElapsedMs = currentStartupTraceStartElapsedMs,
                step = "pgc_prepared_playback_reused",
                message = "cid=${resolvedIdentity?.cid ?: 0L} epId=${resolvedIdentity?.epId ?: 0L}"
            )
            preparedPlaybackDeferred.await()
        } else {
            PlaybackStartupTrace.log(
                traceId = currentStartupTraceId,
                startElapsedMs = currentStartupTraceStartElapsedMs,
                step = "pgc_prepared_playback_not_reused",
                message = "initial=$initialIdentity resolved=$resolvedIdentity hasDeferred=${preparedPlaybackDeferred != null}"
            )
            null
        }
        if (!isActiveVideoLoad(loadGeneration)) {
            return@coroutineScope
        }
        if (preparedPlayback != null) {
            applyPreparedPlayback(preparedPlayback)
        } else {
            loadPlayUrl(preferLastPlayTime = preferLastPlayTime, loadGeneration = loadGeneration)
        }
    }

    private suspend fun loadUgcVideoInfo(preferLastPlayTime: Boolean, loadGeneration: Long) = coroutineScope {
        val initialIdentity = currentPlayRequestIdentity()

        // ── Same-video replay hot path ──────────────────────────────
        // When the user replays the exact same cid (e.g. exits player and
        // re-enters, or presses replay), we can skip getVideoDetail entirely
        // if we still have a valid cached PlayInfo.  This avoids one HTTP
        // round-trip and allows the early-PlayInfo async to finish faster
        // because it will hit the PlayInfo cache.
        val isSameVideoReplay = initialIdentity != null &&
            initialIdentity.cid > 0L &&
            isRecentlyPlayed(initialIdentity.cid) &&
            !initialIdentity.bvid.isNullOrBlank() &&
            !activePlaybackIntentId.startsWith("douyin:")

        if (isSameVideoReplay) {
            // Check PlayInfo cache for interaction flag (set by x/player/v2 on first play)
            val cachedIsSteinsGate = VideoPlayerPlayInfoCache.isSteinsGate(
                initialIdentity!!.bvid.orEmpty(), initialIdentity.cid
            )
            if (cachedIsSteinsGate) isSteinsGateVideo = true

            // ── Zero-overhead reuse: same bvid+cid, player still has MediaSource ──
            val cachedPlayback = getCachedPlayback(initialIdentity!!.bvid, initialIdentity.cid)
            if (cachedPlayback != null) {
                PlaybackStartupTrace.log(
                    traceId = currentStartupTraceId,
                    startElapsedMs = currentStartupTraceStartElapsedMs,
                    step = "zero_overhead_reuse",
                    message = "bvid=${initialIdentity.bvid} cid=${initialIdentity.cid}"
                )
                applySelectionSnapshot(cachedPlayback.selectionSnapshot)
                currentPlayInfo = cachedPlayback.playInfo
                val playInfo = cachedPlayback.playInfo
                val resumePositionMs = if (preferLastPlayTime && !isSteinsGateVideo && currentGraphVersion <= 0L) {
                    val cachedResume = VideoPlayerPlayInfoCache.get(
                        initialIdentity.bvid.orEmpty(), initialIdentity.cid
                    )?.lastPlayTime?.takeIf { it > 5000L }
                    val serverResume = playInfo.lastPlayTime.takeIf { it > 5000L && playInfo.lastPlayCid == initialIdentity.cid }
                    (cachedResume ?: serverResume ?: pendingSeekPositionMs)
                        .takeIf { it > 5000L && (playInfo.timeLength - it) > 5000L }
                } else null
                val effectiveSeekMs = resumePositionMs ?: 0L
                _playbackRequest.value = PlaybackRequest(
                    mediaSource = cachedPlayback.mediaSource,
                    aid = initialIdentity.aid,
                    bvid = initialIdentity.bvid,
                    cid = initialIdentity.cid,
                    seekPositionMs = effectiveSeekMs,
                    playWhenReady = true,
                    replaceInPlace = false,
                    reuseSameSource = true,
                    durationMs = playInfo.timeLength,
                    playbackIntentId = activePlaybackIntentId,
                    startupTraceId = currentStartupTraceId,
                    startupTraceStartElapsedMs = currentStartupTraceStartElapsedMs
                )
                _error.value = null
                if (resumePositionMs != null) {
                    didApplyLastPlayPosition = true
                    publishResumeHint(resumePositionMs)
                }
                // 热起播不能被详情接口拖慢，但后台回写必须确认仍是同一次起播。
                val detailAid = currentAid
                val detailBvid = currentBvid
                val detailIdentity = initialIdentity
                val detailLoadGeneration = loadGeneration
                viewModelScope.launch {
                    val detailResponse = apiService.getVideoDetail(detailAid, detailBvid)
                    if (!isActiveVideoLoad(detailLoadGeneration) || currentCid != detailIdentity.cid) {
                        return@launch
                    }
                    if (detailResponse.isSuccess && detailResponse.data != null) {
                        val detail = detailResponse.data
                        _videoInfo.value = detail
                        currentAid = detail.view?.aid ?: currentAid
                        currentBvid = detail.view?.bvid?.takeIf { it.isNotBlank() } ?: currentBvid
                        val episodeItems = episodeCatalogBuilder.buildUgcEpisodes(detail)
                        _episodes.value = episodeItems
                        _selectedEpisodeIndex.value = episodeItems.indexOfFirst {
                            it.cid == detailIdentity.cid || (it.bvid.isNotBlank() && it.bvid == detailIdentity.bvid)
                        }.takeIf { it >= 0 } ?: 0
                        val related = detail.related.orEmpty()
                        _subtitles.value = detail.view?.subtitle?.list
                            ?.map { it.toSubtitleInfoModel() }
                            .orEmpty()
                        if (related.isNotEmpty()) {
                            _relatedVideos.value = related
                        }
                    }
                }
                if (loadedPlayerExtrasCid != initialIdentity.cid) {
                    pendingPlayerExtrasCid = initialIdentity.cid
                }
                return@coroutineScope
            }
            // ── End zero-overhead reuse ──────────────────────────────

            val cachedPlayInfo = initialIdentity!!.bvid!!.let { bvid ->
                VideoPlayerPlayInfoCache.get(bvid = bvid, cid = initialIdentity.cid)
            }?.takeIf { fallbackController.hasPlayableMedia(it) }

            if (cachedPlayInfo != null) {

                // Reuse the existing PlayInfo cache — the early-PlayInfo async
                // inside requestPreparedPlayback will pick it up automatically.
                val preparedPlayback = requestPreparedPlayback(
                    identity = initialIdentity,
                    preferLastPlayTime = preferLastPlayTime,
                    replaceInPlace = false
                )
                if (!isActiveVideoLoad(loadGeneration)) return@coroutineScope
                if (preparedPlayback != null) {
                    // Interactive video: override seek to 0 regardless of what
                    // requestPreparedPlayback decided (cache flag is authoritative)
                    val effectivePlayback = if (isSteinsGateVideo && preparedPlayback.seekToStart > 0L) {
                        preparedPlayback.copy(seekToStart = 0L, resumeHintPositionMs = null)
                    } else {
                        preparedPlayback
                    }
                    applyPreparedPlayback(effectivePlayback)
                } else {
                    // Cache was present but build failed (e.g. codec issue) — fall through to cold path
                }
                // 热起播不能被详情接口拖慢，但后台回写必须确认仍是同一次起播。
                val detailAid = currentAid
                val detailBvid = currentBvid
                val detailIdentity = initialIdentity
                val detailLoadGeneration = loadGeneration
                viewModelScope.launch {
                    val detailResponse = apiService.getVideoDetail(detailAid, detailBvid)
                    if (!isActiveVideoLoad(detailLoadGeneration) || currentCid != detailIdentity.cid) {
                        return@launch
                    }
                    if (detailResponse.isSuccess && detailResponse.data != null) {
                        val detail = detailResponse.data
                        _videoInfo.value = detail
                        currentAid = detail.view?.aid ?: currentAid
                        currentBvid = detail.view?.bvid?.takeIf { it.isNotBlank() } ?: currentBvid
                        val episodeItems = episodeCatalogBuilder.buildUgcEpisodes(detail)
                        _episodes.value = episodeItems
                        _selectedEpisodeIndex.value = episodeItems.indexOfFirst {
                            it.cid == detailIdentity.cid || (it.bvid.isNotBlank() && it.bvid == detailIdentity.bvid)
                        }.takeIf { it >= 0 } ?: 0
                        val related = detail.related.orEmpty()
                        _subtitles.value = detail.view?.subtitle?.list
                            ?.map { it.toSubtitleInfoModel() }
                            .orEmpty()
                        if (related.isNotEmpty()) {
                            _relatedVideos.value = related
                        }
                    }
                }
                return@coroutineScope
            } else {
            }
        }
        // ── End same-video replay hot path ──────────────────────────

        val initialPreloadedPlayback = initialIdentity?.let { identity ->
            consumePreloadedPlayback(
                identity = identity,
                preferLastPlayTime = false,
                replaceInPlace = false
            )
        }
        val preparedPlaybackDeferred = initialIdentity
            ?.takeIf { initialPreloadedPlayback == null }
            ?.takeIf { it.cid > 0L && !it.bvid.isNullOrBlank() }
            ?.let { identity ->
                async {
                    requestPreparedPlayback(
                        identity = identity,
                        preferLastPlayTime = preferLastPlayTime,
                        replaceInPlace = false
                    )
                }
            }
        if (preparedPlaybackDeferred == null && initialIdentity != null) {
        }
        var detailResponse = apiService.getVideoDetail(currentAid, currentBvid)
        // bvid 为空且 /view/detail 失败时，回退到 /view?aid= 接口
        if (!detailResponse.isSuccess && currentBvid.isNullOrBlank() && (currentAid ?: 0L) > 0L) {
            AppLog.i(TAG, "loadUgcVideoInfo: /view/detail failed(${detailResponse.code}), fallback /view?aid=$currentAid")
            detailResponse = apiService.getVideoDetailByAid(currentAid!!)
        }
        if (!detailResponse.isSuccess || detailResponse.data == null) {
            // 移动端推荐视频: web API 无法识别超大 aid，但有有效 cid，跳过详情直接播放
            if (currentBvid.isNullOrBlank() && (currentAid ?: 0L) > 0L && currentCid > 0L) {
                AppLog.i(TAG, "loadUgcVideoInfo: web detail failed, skip to playUrl with avid=$currentAid cid=$currentCid")
                loadPlayUrl(preferLastPlayTime = preferLastPlayTime, loadGeneration = loadGeneration)
                return@coroutineScope
            }
            AppLog.e(
                TAG,
                "loadUgcVideoInfo detail failure: code=${detailResponse.code}, message=${detailResponse.errorMessage}"
            )
            _error.value = detailResponse.message.ifBlank { "视频详情加载失败" }
            return@coroutineScope
        }

        val detail = detailResponse.data
        _videoInfo.value = detail
        currentAid = detail.view?.aid ?: currentAid
        currentBvid = detail.view?.bvid?.takeIf { it.isNotBlank() } ?: currentBvid
        isSteinsGateVideo = detail.view?.steinsGate == true

        // Auto-detect PGC from redirectUrl: if video detail points to a bangumi page,
        // switch to PGC playback path so the episode list shows all episodes.
        val redirectUrl = detail.view?.redirectUrl.orEmpty()
        val pgcEpId = parseEpIdFromBangumiUrl(redirectUrl)
        if (pgcEpId > 0L) {
            val pgcSeasonId = parseSeasonIdFromBangumiUrl(redirectUrl)
            currentEpId = pgcEpId
            currentSeasonId = pgcSeasonId.takeIf { it > 0L }
            loadPgcVideoInfo(
                preferLastPlayTime = preferLastPlayTime,
                loadGeneration = loadGeneration
            )
            return@coroutineScope
        }

        val episodeItems = episodeCatalogBuilder.buildUgcEpisodes(detail)
        _episodes.value = episodeItems
        val selectedIndex = episodeItems.indexOfFirst {
            it.cid == currentCid || (it.bvid.isNotBlank() && it.bvid == currentBvid)
        }.takeIf { it >= 0 } ?: 0
        _selectedEpisodeIndex.value = selectedIndex
        val selectedEpisode = episodeItems.getOrNull(selectedIndex)
        currentCid = selectedEpisode?.cid
            ?: detail.view?.cid
            ?: currentCid
        currentAid = selectedEpisode?.aid?.takeIf { it > 0L } ?: currentAid
        currentBvid = selectedEpisode?.bvid?.takeIf { it.isNotBlank() } ?: currentBvid
        _currentCidLive.value = currentCid

        // 提前启动弹幕 view 请求，和 PlayInfo 并行
        danmakuController.preloadViewIfNeeded(loadGeneration)

        if (currentCid <= 0L) {
            _error.value = "未找到可播放分P"
            return@coroutineScope
        }

        val related = detail.related.orEmpty()
        _subtitles.value = detail.view?.subtitle?.list
            ?.map { it.toSubtitleInfoModel() }
            .orEmpty()
        maybeAutoSelectSubtitle()
        val resolvedIdentity = currentPlayRequestIdentity()
        val canReuse = preparedPlaybackDeferred != null &&
            canReusePreparedPlayback(initialIdentity, resolvedIdentity)
        val preparedPlayback = initialPreloadedPlayback ?: if (canReuse) {
            preparedPlaybackDeferred!!.await()
        } else {
            if (preparedPlaybackDeferred != null) {
            }
            null
        }
        if (!isActiveVideoLoad(loadGeneration)) {
            return@coroutineScope
        }
        if (preparedPlayback != null) {
            val effectivePlayback = if (isSteinsGateVideo && preparedPlayback.seekToStart > 0L) {
                preparedPlayback.copy(seekToStart = 0L, resumeHintPositionMs = null)
            } else {
                preparedPlayback
            }
            applyPreparedPlayback(effectivePlayback)
        } else {
            loadPlayUrl(preferLastPlayTime = preferLastPlayTime, loadGeneration = loadGeneration)
        }

        if (related.isNotEmpty()) {
            _relatedVideos.value = related
        } else {
            viewModelScope.launch {
                val latestRelated = runCatching {
                    apiService.getRelated(currentAid, currentBvid).data.orEmpty()
                }.getOrDefault(emptyList())
                if (currentCid == detail.view?.cid || currentBvid == detail.view?.bvid) {
                    _relatedVideos.value = latestRelated
                }
            }
        }
    }

    private fun canReusePreparedPlayback(
        initialIdentity: PlayRequestIdentity?,
        resolvedIdentity: PlayRequestIdentity?
    ): Boolean {
        if (initialIdentity == null || resolvedIdentity == null) {
            return false
        }
        if (initialIdentity.cid != resolvedIdentity.cid) {
            return false
        }
        if (initialIdentity.epId != resolvedIdentity.epId) {
            return false
        }
        if (initialIdentity.epId != null) {
            return true
        }
        val initialBvid = initialIdentity.bvid.orEmpty()
        val resolvedBvid = resolvedIdentity.bvid.orEmpty()
        return initialBvid.isNotBlank() && initialBvid == resolvedBvid
    }

    private suspend fun requestPreparedPlayback(
        identity: PlayRequestIdentity,
        preferLastPlayTime: Boolean,
        replaceInPlace: Boolean,
        playbackPositionMs: Long = pendingSeekPositionMs,
        playWhenReady: Boolean = pendingPlayWhenReady,
        qualityCandidates: List<Int> = qualityPolicy.buildCandidates(
            requestedQualityId ?: selectedQualityId
        ),
        playbackIntentId: String = activePlaybackIntentId,
        continuationIntentId: String? = pendingContinuationIntentId,
        suppressUiSignals: Boolean = false
    ): PreparedPlayback? {
        val requestStartMs = System.currentTimeMillis()
        val hardwareCodecs = hardwareSupportedVideoCodecs
        val preferredQualityId = qualityCandidates.firstOrNull()
            ?: requestedQualityId
            ?: selectedQualityId
            ?: 80

        val cachedPlayInfo = identity.bvid
            ?.takeIf { !replaceInPlace && it.isNotBlank() && identity.epId == null }
            ?.let { bvid -> VideoPlayerPlayInfoCache.get(bvid = bvid, cid = identity.cid) }
            ?.takeIf { fallbackController.hasPlayableMedia(it) }
        val usedCachedPlayInfo = cachedPlayInfo != null
        if (usedCachedPlayInfo) {
        }

        if (cachedPlayInfo != null) {
        } else {
        }

        val (initialPlayInfo, effectiveRequestedQualityId) = if (usedCachedPlayInfo) {
            cachedPlayInfo!! to preferredQualityId
        } else {
            val playInfoFetch = fallbackController.requestPlayInfoWithQualityFallback(
                identity = identity,
                qualityCandidates = qualityCandidates,
                suppressUiSignals = suppressUiSignals
            )
            if (playInfoFetch == null) {
                AppLog.e(TAG, "loadPlayUrl requestPlayInfoWithQualityFallback returned null")
                return null
            }
            val response = playInfoFetch.response
            if (!response.isSuccess || response.data == null) {
                val vVoucher = response.vVoucher.trim()
                if (vVoucher.isNotBlank()) {
                    if (!suppressUiSignals) {
                        appSettings.putStringAsync("gaia_vgate_v_voucher", vVoucher)
                        appSettings.putStringAsync("gaia_vgate_v_voucher_saved_at_ms", System.currentTimeMillis().toString())
                        _riskControlVVoucher.value = vVoucher
                        _error.value = "账号被风控，正在请求人机验证…"
                    }
                } else if (response.isTryLookBypass) {
                    if (!suppressUiSignals) {
                        _error.value = "账号被风控，已降级为试看模式"
                        _riskControlTryLookBypass.value = true
                    }
                }
                AppLog.e(
                    TAG,
                    "loadPlayUrl response failure: code=${response.code}, message=${response.message}"
                )
                return null
            }
            response.data to playInfoFetch.requestedQualityId
        }

        identity.bvid
            ?.takeIf { it.isNotBlank() && identity.epId == null }
            ?.let { bvid -> VideoPlayerPlayInfoCache.put(bvid = bvid, cid = identity.cid, playInfo = initialPlayInfo) }

        PlaybackStartupTrace.log(
            traceId = currentStartupTraceId,
            startElapsedMs = currentStartupTraceStartElapsedMs,
            step = "playinfo_ready",
            message = "cid=${identity.cid} cached=$usedCachedPlayInfo quality=$effectiveRequestedQualityId durationMs=${System.currentTimeMillis() - requestStartMs}"
        )

        viewModelScope.launch(Dispatchers.IO) {
            triggerCdnPreconnectForPlayInfo(initialPlayInfo)
        }

        val preferredAudioId = requestedAudioId ?: selectedAudioId
        val preferredCodec = requestedCodec ?: selectedCodec
        val dashPlaybackEnabled = useDashPlayback
        val cachedSteinsGate = VideoPlayerPlayInfoCache.isSteinsGate(identity.bvid.orEmpty(), identity.cid)
        val interactionVideo = currentGraphVersion > 0L || isSteinsGateVideo || cachedSteinsGate
        val startupTraceId = currentStartupTraceId
        val startupTraceStartElapsedMs = currentStartupTraceStartElapsedMs

        return withContext(Dispatchers.Default) {
            val initialQualities = streamResolver.buildQualityList(initialPlayInfo)
            val resolvedQualityId = fallbackController.resolvePlayableQualityId(
                requestedQualityId = effectiveRequestedQualityId,
                playInfo = initialPlayInfo,
                availableQualities = initialQualities,
                reason = "initial_request"
            )

            var selectionSnapshot = streamResolver.resolveSelections(
                playInfo = initialPlayInfo,
                preferredQualityId = resolvedQualityId,
                preferredAudioId = preferredAudioId,
                preferredCodec = preferredCodec,
                hardwareSupportedCodecs = hardwareCodecs
            )
            if (selectionSnapshot.selectedQualityId != resolvedQualityId) {
                selectionSnapshot = selectionSnapshot.copy(selectedQualityId = resolvedQualityId)
            }

            var dashMediaSource: MediaSource? = null
            var preparedDashSession: VideoPlaybackSession? = null
            if (dashPlaybackEnabled) {
                val dashRoutePlan = streamResolver.resolveDashRoutePlan(
                    playInfo = initialPlayInfo,
                    lockedQualityId = resolvedQualityId,
                    selectedAudioId = selectionSnapshot.selectedAudioId,
                    preferredCodec = selectionSnapshot.selectedCodec,
                    hardwareSupportedCodecs = hardwareCodecs
                )
                if (dashRoutePlan != null && dashRoutePlan.routes.isNotEmpty()) {
                    val firstRoute = dashRoutePlan.routes.first()

                    viewModelScope.launch(Dispatchers.IO) {
                        triggerCdnPreconnectForRoute(firstRoute)
                    }

                    val sessionExpiryMs = resolveSessionExpiryMs(firstRoute)
                    try {
                        dashMediaSource = dashMediaSourceFactory.createMediaSource(firstRoute)
                        preparedDashSession = VideoPlaybackSession(
                            identity = SessionIdentity(
                                aid = identity.aid,
                                bvid = identity.bvid,
                                cid = identity.cid,
                                epId = identity.epId
                            ),
                            requestedQualityId = resolvedQualityId,
                            requestedAudioId = selectionSnapshot.selectedAudioId,
                            requestedCodec = selectionSnapshot.selectedCodec,
                            actualQualityId = resolvedQualityId,
                            actualAudioId = dashRoutePlan.selectedAudioId,
                            actualCodec = firstRoute.codec,
                            playInfo = initialPlayInfo,
                            routePlan = dashRoutePlan,
                            currentRoute = firstRoute,
                            expiresAtMs = sessionExpiryMs
                        )
                    } catch (e: Exception) {
                        AppLog.e(TAG, "dashMediaSource:failed cid=${identity.cid} error=${e.message}", e)
                        dashMediaSource = null
                        preparedDashSession = null
                    }
                }
            }

            val mediaSource: MediaSource = dashMediaSource ?: run {
                val progressiveSelection = streamResolver.buildMediaSource(
                    playInfo = initialPlayInfo,
                    selectedQualityId = resolvedQualityId,
                    selectedAudioId = selectionSnapshot.selectedAudioId,
                    selectedCodec = selectionSnapshot.selectedCodec
                )
                progressiveSelection?.mediaSource ?: run {
                    AppLog.e(TAG, "loadPlayUrl mediaSource missing: cid=${identity.cid}")
                    return@withContext null
                }
            }

            // 互动视频不使用进度恢复，始终从头开始
            val effectivePreferLastPlayTime = preferLastPlayTime && !interactionVideo

            val useServerResume = effectivePreferLastPlayTime &&
                initialPlayInfo.lastPlayCid == identity.cid &&
                initialPlayInfo.lastPlayTime > 5000L

            val rawResumePosition = when {
                useServerResume -> initialPlayInfo.lastPlayTime
                else -> playbackPositionMs
            }

            val shouldResume = (replaceInPlace && playbackPositionMs > 0L) ||
                (effectivePreferLastPlayTime &&
                    rawResumePosition > 5000L &&
                    (initialPlayInfo.timeLength - rawResumePosition) > 5000L)

            val startPosition = rawResumePosition.takeIf { shouldResume } ?: 0L

            val resumeHintPositionMs = startPosition.takeIf { shouldResume && !replaceInPlace }
            PreparedPlayback(
                identity = identity,
                playInfo = initialPlayInfo,
                selectionSnapshot = selectionSnapshot,
                mediaSource = mediaSource,
                dashSession = preparedDashSession,
                seekToStart = startPosition,
                playWhenReady = playWhenReady,
                resumeHintPositionMs = resumeHintPositionMs,
                replaceInPlace = replaceInPlace,
                playbackIntentId = playbackIntentId,
                continuationIntentId = continuationIntentId,
                requestDurationMs = System.currentTimeMillis() - requestStartMs,
                startupTraceId = startupTraceId,
                startupTraceStartElapsedMs = startupTraceStartElapsedMs
            )
        }
    }

    private fun applyPreparedPlayback(
        preparedPlayback: PreparedPlayback,
        resetFallbackAttempts: Boolean = true,
        countCurrentAttemptAsFallback: Boolean = false
    ) {
        clearPreloadedPlayback(cancelJob = false)
        currentPlayInfo = preparedPlayback.playInfo
        currentDashSession = preparedPlayback.dashSession
        applySelectionSnapshot(preparedPlayback.selectionSnapshot)
        if (preparedPlayback.resumeHintPositionMs != null) {
            didApplyLastPlayPosition = true
        }
        if (!preparedPlayback.replaceInPlace) {
            heartbeatReporter.beginNewReportSession()
            hasReachedFirstFrame = false
        }

        if (resetFallbackAttempts) {
            fallbackController.reset()
        }
        fallbackController.rememberCurrentFallbackAttempt(countAsFallback = countCurrentAttemptAsFallback)

        _playbackRequest.value = PlaybackRequest(
            mediaSource = preparedPlayback.mediaSource,
            aid = preparedPlayback.identity.aid,
            bvid = preparedPlayback.identity.bvid,
            cid = preparedPlayback.identity.cid,
            seekPositionMs = preparedPlayback.seekToStart,
            playWhenReady = preparedPlayback.playWhenReady,
            replaceInPlace = preparedPlayback.replaceInPlace,
            durationMs = preparedPlayback.playInfo.timeLength,
            playbackIntentId = preparedPlayback.playbackIntentId,
            continuationIntentId = preparedPlayback.continuationIntentId,
            startupTraceId = preparedPlayback.startupTraceId,
            startupTraceStartElapsedMs = preparedPlayback.startupTraceStartElapsedMs
        )
        if (pendingContinuationIntentId == preparedPlayback.continuationIntentId) {
            pendingContinuationIntentId = null
        }
        putCachedPlayback(
            bvid = preparedPlayback.identity.bvid,
            cid = preparedPlayback.identity.cid,
            mediaSource = preparedPlayback.mediaSource,
            playInfo = preparedPlayback.playInfo,
            selectionSnapshot = preparedPlayback.selectionSnapshot
        )
        PlaybackStartupTrace.log(
            traceId = preparedPlayback.startupTraceId,
            startElapsedMs = preparedPlayback.startupTraceStartElapsedMs,
            step = "playback_request_emitted",
            message = "cid=${preparedPlayback.identity.cid} seek=${preparedPlayback.seekToStart} " +
                "intentId=${preparedPlayback.playbackIntentId} continuationId=${preparedPlayback.continuationIntentId.orEmpty()} " +
                "replace=${preparedPlayback.replaceInPlace} " +
                "playWhenReady=${preparedPlayback.playWhenReady} " +
                "quality=${preparedPlayback.selectionSnapshot.selectedQualityId} " +
                "codec=${preparedPlayback.selectionSnapshot.selectedCodec} " +
                "requestDurationMs=${preparedPlayback.requestDurationMs}"
        )
        preparedPlayback.resumeHintPositionMs?.let { targetPositionMs ->
            publishResumeHint(targetPositionMs)
        }
        _error.value = null
        if (loadedPlayerExtrasCid != preparedPlayback.identity.cid) {
            pendingPlayerExtrasCid = preparedPlayback.identity.cid
        }
        if (danmakuController.loadedCid != preparedPlayback.identity.cid) {
            pendingSeekPositionMs = preparedPlayback.seekToStart
            val danmakuAid = currentAid
                ?: preparedPlayback.identity.aid
                ?: 0L
            val fallbackSegmentCount = maxOf(
                1,
                ((preparedPlayback.playInfo.timeLength.coerceAtLeast(1L) - 1L) /
                    DanmakuPlaybackController.DANMAKU_SEGMENT_DURATION_MS + 1L).toInt()
            )
            val preloadSegment = ((preparedPlayback.seekToStart.coerceAtLeast(0L) /
                DanmakuPlaybackController.DANMAKU_SEGMENT_DURATION_MS) + 1L).toInt().coerceIn(1, fallbackSegmentCount)
            danmakuController.preloadInitialSegment(
                cid = preparedPlayback.identity.cid,
                aid = danmakuAid,
                segmentIndex = preloadSegment
            )
            if (hasReachedFirstFrame) {
                danmakuController.loadDanmaku(
                    cid = preparedPlayback.identity.cid,
                    aid = danmakuAid,
                    durationMs = preparedPlayback.playInfo.timeLength
                )
            } else {
                PlaybackStartupTrace.log(
                    traceId = preparedPlayback.startupTraceId,
                    startElapsedMs = preparedPlayback.startupTraceStartElapsedMs,
                    step = "danmaku_deferred_until_first_frame",
                    message = "cid=${preparedPlayback.identity.cid} aid=$danmakuAid"
                )
            }
        }

        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            val fbStartMs = System.currentTimeMillis()
            val plan = streamResolver.buildStreamFallbackPlan(
                playInfo = preparedPlayback.playInfo,
                lockedQualityId = requestedQualityId
                    ?: preparedPlayback.selectionSnapshot.selectedQualityId
                    ?: selectedQualityId
                    ?: 80,
                selectedAudioId = requestedAudioId ?: preparedPlayback.selectionSnapshot.selectedAudioId,
                preferredCodec = requestedCodec ?: preparedPlayback.selectionSnapshot.selectedCodec,
                hardwareSupportedCodecs = hardwareSupportedVideoCodecs
            )
            val routeIdx = plan
                ?.routes
                ?.indexOfFirst { it.codec == (requestedCodec ?: preparedPlayback.selectionSnapshot.selectedCodec) }
                ?.takeIf { it >= 0 }
                ?: 0
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                currentStreamFallbackPlan = plan
                fallbackRouteIndex = routeIdx
                fallbackCdnIndex = 0
            }
        }
    }

    fun onPlaybackFirstFrame() {
        hasReachedFirstFrame = true
        val cid = currentCid.takeIf { it > 0L }
        if (cid == null) return
        val resumeToastPositionMs = pendingResumeToastPositionMs
        pendingResumeToastPositionMs = null
        if (danmakuController.loadedCid != cid) {
            val danmakuAid = currentAid ?: 0L
            viewModelScope.launch {
                // loadedCid 由 loadDanmaku 内部设置，此处调用前仍是旧值，
                // 仅用 currentCid 做切集检测；防重复由入口 loadedCid 门控 + loadDanmaku 内部 generation 保证。
                if (currentCid != cid) return@launch
                danmakuController.loadDanmaku(
                    cid = cid,
                    aid = danmakuAid,
                    durationMs = currentPlayInfo?.timeLength ?: 0L
                )
            }
        }
        scheduleDeferredSponsorLoadAfterFirstFrame(cid)
        viewModelScope.launch {
            // 首帧后先把弹幕链路放出去，Toast/心跳/扩展信息延后一个很短的窗口，降低首显附近主线程抖动。
            delay(FIRST_FRAME_DEFERRED_WORK_DELAY_MS)
            if (currentCid != cid) return@launch
            resumeToastPositionMs?.let { showResumePositionToast(it) }
            markRecentlyPlayed(cid)
            reportPlaybackHeartbeat(playType = PlaybackHeartbeatReporter.PLAY_TYPE_START)
            if (pendingPlayerExtrasCid == cid && loadedPlayerExtrasCid != cid) {
                pendingPlayerExtrasCid = 0L
                loadedPlayerExtrasCid = cid
                loadPlayerExtras()
            }
        }
    }

    private fun prepareDeferredSponsorLoad(
        bvid: String?,
        cid: Long,
        loadGeneration: Long
    ) {
        sponsorLoadJob?.cancel()
        pendingSponsorBvid = null
        pendingSponsorCid = 0L
        pendingSponsorLoadGeneration = 0L
        if (bvid.isNullOrBlank() || cid <= 0L || !currentSettings.sponsorBlockEnabled) {
            return
        }
        pendingSponsorBvid = bvid
        pendingSponsorCid = cid
        pendingSponsorLoadGeneration = loadGeneration
        PlaybackStartupTrace.log(
            traceId = currentStartupTraceId,
            startElapsedMs = currentStartupTraceStartElapsedMs,
            step = "sponsor_load_deferred",
            message = "cid=$cid bvid=$bvid"
        )
    }

    private fun scheduleDeferredSponsorLoadAfterFirstFrame(cid: Long) {
        val bvid = pendingSponsorBvid ?: return
        val targetCid = pendingSponsorCid.takeIf { it == cid } ?: return
        val generation = pendingSponsorLoadGeneration
        if (generation <= 0L) return
        sponsorLoadJob?.cancel()
        sponsorLoadJob = viewModelScope.launch {
            delay(FIRST_FRAME_SPONSOR_LOAD_DELAY_MS)
            if (!isActiveVideoLoad(generation) || currentCid != targetCid) {
                return@launch
            }
            PlaybackStartupTrace.log(
                traceId = currentStartupTraceId,
                startElapsedMs = currentStartupTraceStartElapsedMs,
                step = "sponsor_load_started",
                message = "cid=$targetCid bvid=$bvid"
            )
            sponsorBlockUseCase.loadSegments(bvid, targetCid)
            if (!isActiveVideoLoad(generation) || currentCid != targetCid) {
                return@launch
            }
            sponsorBlockUseCase.lastError?.let { error ->
                AppLog.w(TAG, "sponsor load skipped: $error")
            }
            _sponsorSegments.value = sponsorBlockUseCase.getSegments()
            PlaybackStartupTrace.log(
                traceId = currentStartupTraceId,
                startElapsedMs = currentStartupTraceStartElapsedMs,
                step = "sponsor_load_ready",
                message = "cid=$targetCid count=${_sponsorSegments.value.size}"
            )
        }
    }

    fun handlePlaybackError(error: androidx.media3.common.PlaybackException, currentPositionMs: Long) =
        fallbackController.handlePlaybackError(error, currentPositionMs)

    fun handlePlaybackStall(positionMs: Long, stalledMs: Long): Boolean =
        fallbackController.handlePlaybackStall(positionMs, stalledMs)


    private fun currentPlayRequestIdentity(): PlayRequestIdentity? {
        val cid = currentCid.takeIf { it > 0L } ?: return null
        return PlayRequestIdentity(
            aid = currentAid,
            bvid = currentBvid?.takeIf { it.isNotBlank() },
            cid = cid,
            epId = currentEpId?.takeIf { it > 0L }
        )
    }

    private fun PlaybackPreloadTarget.toPlayRequestIdentity(): PlayRequestIdentity? {
        val resolvedAid = aid?.takeIf { it > 0L }
        val resolvedBvid = bvid?.takeIf { it.isNotBlank() }
        val resolvedEpId = epId?.takeIf { it > 0L }
        if (resolvedAid == null && resolvedBvid.isNullOrBlank() && resolvedEpId == null) {
            return null
        }
        return PlayRequestIdentity(
            aid = resolvedAid,
            bvid = resolvedBvid,
            cid = cid.coerceAtLeast(0L),
            epId = resolvedEpId
        )
    }

    private fun consumePreloadedPlayback(
        identity: PlayRequestIdentity,
        preferLastPlayTime: Boolean,
        replaceInPlace: Boolean
    ): PreparedPlayback? {
        if (preferLastPlayTime || replaceInPlace) {
            AppLog.i(TAG, "playback_preload_skip_consume requested=$identity preferLast=$preferLastPlayTime replace=$replaceInPlace")
            return null
        }
        val preloaded = preloadedPlayback ?: run {
            PlaybackStartupTrace.log(
                traceId = currentStartupTraceId,
                startElapsedMs = currentStartupTraceStartElapsedMs,
                step = "playback_preload_absent",
                message = "requested=$identity preferLast=$preferLastPlayTime replace=$replaceInPlace"
            )
            AppLog.i(TAG, "playback_preload_absent requested=$identity preferLast=$preferLastPlayTime replace=$replaceInPlace")
            return null
        }
        if (preloaded.preparedPlayback.identity != identity) {
            PlaybackStartupTrace.log(
                traceId = currentStartupTraceId,
                startElapsedMs = currentStartupTraceStartElapsedMs,
                step = "playback_preload_miss",
                message = "requested=$identity preloaded=${preloaded.preparedPlayback.identity} source=${preloaded.source}"
            )
            AppLog.i(TAG, "playback_preload_miss requested=$identity preloaded=${preloaded.preparedPlayback.identity} source=${preloaded.source}")
            return null
        }
        preloadedPlayback = null
        // 自动连播倒计时/抖音模式触发后必须直接播放，不能继承 ENDED/IDLE 阶段刷出来的暂停态。
        val effectivePlayWhenReady = if (
            preloaded.source == PlaybackPreloadTarget.Source.AUTOPLAY_COUNTDOWN
            || preloaded.source == PlaybackPreloadTarget.Source.DOUYIN_MODE
        ) {
            true
        } else {
            pendingPlayWhenReady
        }
        PlaybackStartupTrace.log(
            traceId = currentStartupTraceId,
            startElapsedMs = currentStartupTraceStartElapsedMs,
            step = "playback_preload_consumed",
            message = "cid=${identity.cid} epId=${identity.epId ?: 0L} source=${preloaded.source} " +
                "intentId=${preloaded.preparedPlayback.continuationIntentId.orEmpty()} " +
                "playWhenReady=$effectivePlayWhenReady " +
                "quality=${preloaded.preparedPlayback.selectionSnapshot.selectedQualityId} " +
                "codec=${preloaded.preparedPlayback.selectionSnapshot.selectedCodec}"
        )
        AppLog.i(
            TAG,
            "playback_preload_consumed requested=$identity source=${preloaded.source} playWhenReady=$effectivePlayWhenReady " +
                "quality=${preloaded.preparedPlayback.selectionSnapshot.selectedQualityId} " +
                "codec=${preloaded.preparedPlayback.selectionSnapshot.selectedCodec}"
        )
        return preloaded.preparedPlayback.copy(
            playWhenReady = effectivePlayWhenReady,
            replaceInPlace = false
        )
    }

    private fun clearPreloadedPlayback(cancelJob: Boolean) {
        if (preloadedPlayback != null || preloadingIdentity != null || preloadJob != null || douyinWarmupJob != null) {
            AppLog.i(
                TAG,
                "playback_preload_clear cancelJob=$cancelJob preloaded=${preloadedPlayback?.preparedPlayback?.identity} " +
                    "preloading=$preloadingIdentity hasJob=${preloadJob != null} hasWarmup=${douyinWarmupJob != null}"
            )
        }
        preloadedPlayback = null
        if (cancelJob) {
            preloadJob?.cancel()
            preloadJob = null
            preloadingIdentity = null
            danmakuController.cancelPreload()
            douyinWarmupJob?.cancel()
            douyinWarmupJob = null
        }
    }

    private fun clearPreloadedPlaybackIfDifferent(
        identity: PlayRequestIdentity?,
        cancelJob: Boolean
    ) {
        if (identity != null && preloadedPlayback?.preparedPlayback?.identity == identity) {
            AppLog.i(TAG, "playback_preload_preserve_preloaded identity=$identity")
            return
        }
        if (identity != null && preloadingIdentity == identity) {
            AppLog.i(TAG, "playback_preload_preserve_running identity=$identity")
            return
        }
        if (preloadedPlayback != null || preloadingIdentity != null) {
            AppLog.i(
                TAG,
                "playback_preload_clear_different target=$identity preloaded=${preloadedPlayback?.preparedPlayback?.identity} " +
                    "preloading=$preloadingIdentity cancelJob=$cancelJob"
            )
        }
        clearPreloadedPlayback(cancelJob = cancelJob)
    }

    private fun isActiveVideoLoad(loadGeneration: Long): Boolean {
        return loadGeneration == videoLoadGeneration
    }

    private fun shouldFallbackToUgcPlayback(response: Base2Response<*>): Boolean {
        if ((currentAid ?: 0L) <= 0L && currentBvid.isNullOrBlank()) {
            return false
        }
        val message = response.message.trim()
        return response.code == -404 ||
            message.contains("啥都木有") ||
            message.contains("啥都没有")
    }

    private fun loadPlayerExtras() {
        val cid = currentCid
        if (cid <= 0L) {
            _videoSnapshot.value = null
            return
        }

        viewModelScope.launch {
            val aid = currentAid
            val bvid = currentBvid
            val playerInfoDeferred = async {
                delay(750L)
                playInfoGateway.requestPlayerInfoData(
                    aid = aid,
                    bvid = bvid,
                    cid = cid
                )
            }
            val snapshotDeferred = async {
                delay(2_500L)
                playInfoGateway.requestVideoSnapshot(
                    aid = aid,
                    bvid = bvid,
                    cid = cid
                )
            }

            playerInfoDeferred.await()?.let { wrapper ->
                val subtitleTracks = wrapper.subtitle?.subtitles.orEmpty()
                if (subtitleTracks.isNotEmpty()) {
                    _subtitles.value = subtitleTracks
                    maybeAutoSelectSubtitle()
                }
                val interaction = wrapper.interaction
                if (interaction != null && interaction.graphVersion > 0L && !currentBvid.isNullOrBlank() && (currentAid ?: 0L) > 0L) {
                    currentGraphVersion = interaction.graphVersion
                    VideoPlayerPlayInfoCache.markAsSteinsGate(currentBvid!!, currentCid)
                    // 仅在引擎未初始化（首次加载）时触发 loadInteractionInfo
                    // playInteractionChoice 已单独调用 loadInteractionInfo，避免竞态覆盖
                    if (interactionEngine.state.graphVersion == 0L) {
                        AppLog.d(TAG, "interaction: first load, graphVersion=${interaction.graphVersion}")
                        loadInteractionInfo(0L, interaction.graphVersion)
                    } else {
                        AppLog.d(TAG, "interaction: engine already initialized, skip reload")
                    }
                } else if (interaction == null) {
                    currentGraphVersion = 0L
                    _interactionModel.value = null
                    _interactionHiddenVars.value = null
                    interactionEngine.reset()
                    interactionRepository.clearCache()
                    interactionProgressRestored = false
                    interactionLoadingEdgeId = -1L
                }
                val dmMask = wrapper.dmMask
                if (dmMask != null && dmMask.maskUrl.isNotBlank()) {
                    danmakuController.applyDmMask(dmMask)
                } else {
                    danmakuController.applyDmMask(null)
                }
            } ?: AppLog.e(TAG, "loadPlayerExtras failed: cid=$cid")

            val snapshot = snapshotDeferred.await()
            if (currentCid == cid && currentAid == aid && currentBvid == bvid) {
                _videoSnapshot.value = snapshot
            }
        }
    }

    private fun loadVideoSnapshot() {
        val cid = currentCid.takeIf { it > 0L } ?: run {
            _videoSnapshot.value = null
            return
        }
        val aid = currentAid
        val bvid = currentBvid
        if ((aid == null || aid <= 0L) && bvid.isNullOrBlank()) {
            _videoSnapshot.value = null
            return
        }

        viewModelScope.launch {
            val snapshot = playInfoGateway.requestVideoSnapshot(
                aid = aid,
                bvid = bvid,
                cid = cid
            )
            if (currentCid == cid && currentAid == aid && currentBvid == bvid) {
                _videoSnapshot.value = snapshot
            }
        }
    }

    private fun loadInteractionInfo(edgeId: Long = 0L, graphVersion: Long? = null) {
        val bvid = currentBvid ?: return
        val aid = currentAid ?: return
        val resolvedGraphVersion = graphVersion ?: currentGraphVersion
        if (resolvedGraphVersion <= 0L) return

        interactionLoadingEdgeId = edgeId
        AppLog.d(TAG, "loadInteractionInfo: edgeId=$edgeId, graphVersion=$resolvedGraphVersion")

        viewModelScope.launch {
            val model = interactionRepository.loadNode(bvid, aid, resolvedGraphVersion, edgeId)
            if (model == null) {
                AppLog.w(TAG, "loadInteractionInfo: failed to load node, edgeId=$edgeId")
                _interactionModel.value = null
                return@launch
            }

            // 防竞态：如果在此期间已有更新的 loadInteractionInfo 调用，丢弃本次结果
            if (interactionLoadingEdgeId != edgeId) {
                AppLog.w(TAG, "loadInteractionInfo: stale result for edgeId=$edgeId, current=${interactionLoadingEdgeId}")
                return@launch
            }

            if (edgeId == 0L) {
                interactionEngine.initialize(resolvedGraphVersion, model)
                AppLog.d(TAG, "loadInteractionInfo: initialized engine, edgeId=${model.edgeId}")
            } else {
                interactionEngine.processNode(model)
                AppLog.d(TAG, "loadInteractionInfo: processed node, edgeId=${model.edgeId}")
            }

            _interactionModel.value = model
            _interactionHiddenVars.value = model.hiddenVars

            // 预加载可见选项的下一跳节点
            val questions = model.edges?.questions
            if (!questions.isNullOrEmpty()) {
                val visibleChoices = interactionEngine.getVisibleChoices(questions)
                val nextEdgeIds = visibleChoices.map { it.id }.distinct()
                if (nextEdgeIds.isNotEmpty()) {
                    interactionRepository.preloadNodes(bvid, aid, resolvedGraphVersion, nextEdgeIds)
                }
            }
        }
    }

    private fun capturePlaybackSnapshot(positionMs: Long, playWhenReady: Boolean) {
        pendingSeekPositionMs = positionMs.coerceAtLeast(0L)
        pendingPlayWhenReady = playWhenReady
    }

    private fun applySelectionSnapshot(snapshot: VideoPlayerStreamResolver.SelectionSnapshot) {
        selectedQualityId = snapshot.selectedQualityId
        selectedAudioId = snapshot.selectedAudioId
        selectedCodec = snapshot.selectedCodec
        val audio = snapshot.audios.firstOrNull { it.id == selectedAudioId }
        AppLog.i(TAG, "applySelection: qualityId=$selectedQualityId audioId=$selectedAudioId " +
            "audioName=${audio?.name} audioCodecId=${audio?.codecId} audioBandwidth=${audio?.bandwidth} codec=$selectedCodec")
        _qualities.value = snapshot.qualities
        _selectedQuality.value = snapshot.qualities.firstOrNull { it.id == selectedQualityId }
        _audioQualities.value = snapshot.audios
        _selectedAudioQuality.value = snapshot.audios.firstOrNull { it.id == selectedAudioId }
        _videoCodecs.value = snapshot.codecs
        _selectedVideoCodec.value = snapshot.selectedCodec
    }

    private fun resolveSelectionSnapshot(
        playInfo: PlayInfoModel
    ): VideoPlayerStreamResolver.SelectionSnapshot? {
        val selectionSnapshot = streamResolver.resolveSelections(
            playInfo = playInfo,
            preferredQualityId = requestedQualityId ?: selectedQualityId,
            preferredAudioId = requestedAudioId ?: selectedAudioId,
            preferredCodec = requestedCodec ?: selectedCodec,
            hardwareSupportedCodecs = hardwareSupportedVideoCodecs
        )
        return selectionSnapshot.takeIf { it.selectedQualityId != null || it.selectedAudioId != null || it.selectedCodec != null }
    }

    private suspend fun loadSubtitleData(track: SubtitleInfoModel): SubtitleData? =
        withContext(Dispatchers.IO) {
            val normalizedUrl = normalizeUrl(track.subtitleUrl)
            subtitleCache[normalizedUrl]?.let { return@withContext it }

            runCatching {
                val request = Request.Builder()
                    .url(normalizedUrl)
                    .header("Referer", "https://www.bilibili.com")
                    .header(
                        "User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                            "(KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36"
                    )
                    .build()
                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@use null
                    }
                    response.body?.charStream()?.use { reader ->
                        gson.fromJson(reader, SubtitleData::class.java)
                    }
                }
            }.getOrNull()?.also {
                subtitleCache[normalizedUrl] = it
            }
        }

    private fun maybeAutoSelectSubtitle() {
        if (!shouldAutoSelectSubtitle) {
            return
        }
        val subtitles = _subtitles.value.orEmpty()
        if (subtitles.isEmpty()) {
            return
        }
        shouldAutoSelectSubtitle = false
        selectSubtitle(0)
    }

    private fun updateSubtitleText(positionMs: Long) {
        val data = currentSubtitleData?.body.orEmpty()
        if ((_selectedSubtitleIndex.value ?: -1) < 0 || data.isEmpty()) {
            currentSubtitleCueIndex = 0
            if (_currentSubtitleText.value != null) {
                _currentSubtitleText.value = null
            }
            return
        }
        val positionSeconds = positionMs / 1000f
        val cue = data.findCueAt(positionSeconds)
        val subtitleText = cue?.content
        if (_currentSubtitleText.value != subtitleText) {
            _currentSubtitleText.value = subtitleText
        }
    }

    private fun List<SubtitleItem>.findCueAt(positionSeconds: Float): SubtitleItem? {
        if (isEmpty()) {
            currentSubtitleCueIndex = 0
            return null
        }
        var index = currentSubtitleCueIndex.coerceIn(0, lastIndex)
        if (positionSeconds < this[index].from) {
            while (index > 0 && positionSeconds < this[index].from) {
                index--
            }
        } else {
            while (index < lastIndex && positionSeconds > this[index].to) {
                index++
            }
        }
        currentSubtitleCueIndex = index
        val cue = this[index]
        return cue.takeIf { positionSeconds >= it.from && positionSeconds <= it.to }
    }

    private fun normalizeUrl(rawUrl: String): String {
        return when {
            rawUrl.startsWith("//") -> "https:$rawUrl"
            rawUrl.startsWith("http://") || rawUrl.startsWith("https://") -> rawUrl
            else -> "https://$rawUrl"
        }
    }

    private fun extractUrlExpiryMs(url: String): Long {
        val uri = android.net.Uri.parse(url)
        val expiresParam = uri.getQueryParameter("expires")
            ?: uri.getQueryParameter("deadline")
            ?: return 0L
        val expiresSeconds = expiresParam.toLongOrNull() ?: return 0L
        return expiresSeconds * 1000L
    }

    private fun resolveSessionExpiryMs(route: DashRoute): Long {
        val videoExpiry = extractUrlExpiryMs(route.videoRepresentation.baseUrl)
        val audioExpiry = route.audioRepresentation?.baseUrl?.let(::extractUrlExpiryMs) ?: Long.MAX_VALUE
        return minOf(videoExpiry, audioExpiry).takeIf { it > 0L } ?: 0L
    }

    private fun DetailSubtitleItem.toSubtitleInfoModel(): SubtitleInfoModel {
        return SubtitleInfoModel(
            id = id,
            lan = lan,
            lanDoc = lanDoc,
            isLock = isLock,
            subtitleUrl = subtitleUrl
        )
    }

    private fun isPgcPlayback(): Boolean {
        return (currentEpId ?: 0L) > 0L || (currentSeasonId ?: 0L) > 0L
    }

    private fun parseEpIdFromBangumiUrl(url: String): Long {
        if (!url.contains("/bangumi/play/ep")) return 0L
        return url.substringAfter("/bangumi/play/ep", "")
            .takeWhile { it.isDigit() }
            .toLongOrNull() ?: 0L
    }

    private fun parseSeasonIdFromBangumiUrl(url: String): Long {
        if (!url.contains("/bangumi/play/ss")) return 0L
        return url.substringAfter("/bangumi/play/ss", "")
            .takeWhile { it.isDigit() }
            .toLongOrNull() ?: 0L
    }

    private suspend fun preconnectCdnHosts(videoUrls: List<String>, audioUrls: List<String>) {
        val uniqueHosts = mutableSetOf<String>()
        val startTime = System.currentTimeMillis()

        for (url in videoUrls + audioUrls) {
            try {
                val host = URL(url).host
                if (host.isNotBlank()) {
                    uniqueHosts.add(host)
                }
            } catch (e: Exception) {
                continue
            }
        }

        if (uniqueHosts.isEmpty()) {
            return
        }

        coroutineScope {
            uniqueHosts.forEach { host ->
                launch(Dispatchers.IO) {
                    runCatching {
                        val preconnectStart = System.currentTimeMillis()
                        val request = Request.Builder()
                            .url("https://$host")
                            .head()
                            .build()
                        val call = playerOkHttpClient.newCall(request)
                        val response = call.execute()
                        response.close()
                        val elapsed = System.currentTimeMillis() - preconnectStart
                    }.onFailure { e ->
                    }
                }
            }
        }

        val totalElapsed = System.currentTimeMillis() - startTime
    }

    private suspend fun triggerCdnPreconnectForRoute(route: DashRoute?) {
        if (route == null) return
        try {
            val allUrls = route.videoUrls + route.audioUrls
            if (allUrls.isEmpty()) return
            preconnectCdnHosts(route.videoUrls, route.audioUrls)
        } catch (e: Exception) {
        }
    }

    private suspend fun triggerCdnPreconnectForPlayInfo(playInfo: PlayInfoModel) {
        try {
            val dash = playInfo.dash ?: return
            val videoUrls = dash.video?.mapNotNull { it.realBaseUrl.ifEmpty { null } }.orEmpty()
            val audioUrls = dash.audio?.mapNotNull { it.realBaseUrl.ifEmpty { null } }.orEmpty()
            val allUrls = videoUrls + audioUrls
            if (allUrls.isEmpty()) return
            preconnectCdnHosts(videoUrls, audioUrls)
        } catch (e: Exception) {
        }
    }

    private fun checkSponsorBlock(positionMs: Long) {
        val settings = currentSettings
        if (!settings.sponsorBlockEnabled) return
        if (sponsorSkipPending) {
            if (positionMs >= pendingSeekPositionMs - 500L) {
                sponsorSkipPending = false
                pendingSeekPositionMs = positionMs
            }
            return
        }
        val result = sponsorBlockUseCase.checkPosition(positionMs, autoSkip = true)
        when {
            result == null -> {
                val current = _sponsorSkipState.value
                if (current is SponsorSkipUiState.ShowButton) {
                    _sponsorSkipState.value = SponsorSkipUiState.Hidden
                }
            }
            result.action == SponsorBlockUseCase.SkipAction.AUTO_SKIP -> {
                _sponsorSkipState.value = SponsorSkipUiState.AutoSkipped(result.segment)
                pendingSeekPositionMs = result.segment.endTimeMs
                sponsorSkipPending = true
            }
            result.action == SponsorBlockUseCase.SkipAction.SHOW_BUTTON -> {
                _sponsorSkipState.value = SponsorSkipUiState.ShowButton(result.segment)
            }
        }
    }

    fun sponsorSkip(): Long? {
        val targetMs = sponsorBlockUseCase.skipCurrent() ?: return null
        _sponsorSkipState.value = SponsorSkipUiState.Hidden
        pendingSeekPositionMs = targetMs
        return targetMs
    }

    fun sponsorDismiss() {
        sponsorBlockUseCase.dismissCurrent()
        _sponsorSkipState.value = SponsorSkipUiState.Hidden
    }

    fun sponsorUserSeek(positionMs: Long) {
        sponsorBlockUseCase.onUserSeek(positionMs)
        _sponsorSkipState.value = SponsorSkipUiState.Hidden
    }
}




