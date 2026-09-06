@file:Suppress("SpellCheckingInspection")

package com.tutu.myblbl.feature.player

import com.tutu.myblbl.core.common.json.GsonHolder
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.source.MediaSource
import com.google.gson.Gson
import com.tutu.myblbl.core.common.media.VideoCodecSupport

import com.tutu.myblbl.model.dm.DmMaskRepository
import com.tutu.myblbl.model.dm.DmModel
import com.tutu.myblbl.model.interaction.InteractionModel
import com.tutu.myblbl.model.interaction.InteractionVariableModel
import com.tutu.myblbl.feature.player.interaction.InteractionEngine
import com.tutu.myblbl.feature.player.interaction.InteractionRepository
import com.tutu.myblbl.model.player.PlayInfoModel
import com.tutu.myblbl.model.player.VideoSnapshotData
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
import com.tutu.myblbl.core.common.log.AppLog
import com.tutu.myblbl.core.common.settings.AppSettingsDataStore
import com.tutu.myblbl.feature.player.cache.PlayerMediaCache
import com.tutu.myblbl.network.cookie.CookieManager
import com.tutu.myblbl.repository.UserRepository
import com.tutu.myblbl.feature.player.settings.PlayerSettings
import com.tutu.myblbl.feature.player.settings.PlayerSettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.CacheControl
import okhttp3.Request
import java.net.URL
import kotlinx.coroutines.withContext
import org.koin.mp.KoinPlatform
import java.util.concurrent.TimeUnit
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
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
    private val userRepository: UserRepository,
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

        // 同视频复用：缓存最近一次已准备完成的播放状态。
        @UnstableApi
        internal data class CachedPlayback(
            val bvid: String?,
            val cid: Long,
            val mediaSource: MediaSource,
            val playInfo: PlayInfoModel,
            val selectionSnapshot: VideoPlayerStreamResolver.SelectionSnapshot,
            val seamlessCatalog: SeamlessQualityCatalog? = null,
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
            selectionSnapshot: VideoPlayerStreamResolver.SelectionSnapshot,
            seamlessCatalog: SeamlessQualityCatalog? = null
        ) {
            trimExpiredCachedPlaybacks()
            // 诊断：写入缓存时记录 uri，定位是否"写入即串台"（原因B）
            // 与读出时的 zero_overhead_reuse_hit.cacheUri 对照
            val putUri = runCatching {
                mediaSource.mediaItem.localConfiguration?.uri?.toString()
            }.getOrNull()?.substringAfterLast('/')
            AppLog.w(
                TAG,
                "putCachedPlayback bvid=$bvid cid=$cid uri=$putUri sizeBefore=${cachedPlaybacks.size}"
            )
            cachedPlaybacks[cachePlaybackKey(bvid, cid)] = CachedPlayback(
                bvid = bvid,
                cid = cid,
                mediaSource = mediaSource,
                playInfo = playInfo,
                selectionSnapshot = selectionSnapshot,
                seamlessCatalog = seamlessCatalog,
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
        val startupTraceStartElapsedMs: Long,
        val cdnStates: List<VideoPlayerCdnFailoverState> = emptyList(),
        val seamlessCatalog: SeamlessQualityCatalog? = null
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

    private fun publishResumeHint(positionMs: Long) {
        _resumeHint.value = ResumeProgressHint(targetPositionMs = positionMs)
    }

    private val gson = GsonHolder.DEFAULT
    private val appContext = context.applicationContext
    private val ipv4OnlyEnabled: () -> Boolean = {
        runCatching { KoinPlatform.getKoin().get<AppSettingsDataStore>() }
            .getOrNull()
            ?.getCachedString("ipv4_only") != "关"
    }
    private val playerOkHttpClient = OkHttpClient.Builder()
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
    private val cdnPreconnector = CdnPreconnector(playerOkHttpClient)
    private val streamResolver = VideoPlayerStreamResolver(
        dataSourceFactory = dataSourceFactory,
        urlNormalizer = VideoPlayerUrlUtils::normalizeUrl
    )
    private val dashMediaSourceFactory = VideoPlayerDashMediaSourceFactory(
        dataSourceFactory = cacheDataSourceFactory,
        urlNormalizer = VideoPlayerUrlUtils::normalizeUrl
    )
    private val seamlessDashMediaSourceFactory = SeamlessDashMediaSourceFactory(
        baseDataSourceFactory = cacheDataSourceFactory,
        urlNormalizer = VideoPlayerUrlUtils::normalizeUrl
    )
    // 当前会话若挂的是多清晰度 DASH MPD 源则非空，selectVideoQuality 据此走无缝切换。
    private var currentSeamlessCatalog: SeamlessQualityCatalog? = null
    // 无缝会话当前生效的编码（初始=目录初始值；渲染器回读真实值后更新，见 onSeamlessVideoTrackChanged）。
    private var currentSeamlessCodec: VideoCodecEnum? = null
    private val seamlessQualitySwitchEnabled: Boolean
        get() = com.tutu.myblbl.feature.player.settings.PlayerSettingsStore
            .load(appContext).seamlessQualitySwitch
    private val douyinWarmupManager = DouyinPlaybackWarmupManager(
        dataSourceFactory = cacheDataSourceFactory,
        urlNormalizer = VideoPlayerUrlUtils::normalizeUrl
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




    private val subtitleController = SubtitlePlaybackController(
        scope = viewModelScope,
        okHttpClient = okHttpClient,
        playInfoGateway = playInfoGateway,
        savedStateHandle = savedStateHandle,
        currentAid = { currentAid },
        currentBvid = { currentBvid },
        currentCid = { currentCid },
        currentPositionMs = { _currentPosition.value }
    )
    val subtitles: StateFlow<List<SubtitleInfoModel>> get() = subtitleController.subtitles
    val selectedSubtitleIndex: StateFlow<Int> get() = subtitleController.selectedSubtitleIndex
    val currentSubtitleText: StateFlow<String?> get() = subtitleController.currentSubtitleText
    fun selectSubtitle(index: Int) = subtitleController.selectSubtitle(index)

    // ==================== 弹幕系统（转发到 DanmakuPlaybackController）====================
    val danmaku: StateFlow<List<DmModel>> get() = danmakuController.danmaku
    internal val danmakuUpdates: Flow<DanmakuPlaybackController.DanmakuUpdate> get() = danmakuController.danmakuUpdates
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
    private var currentGraphVersion: Long = 0L
    private var currentSettings: PlayerSettings = PlayerSettingsStore.load(appContext)
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
        override val cdnStates: List<VideoPlayerCdnFailoverState>
            get() = this@VideoPlayerViewModel.currentCdnStates

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

        override fun onCdnStatesUpdated(states: List<VideoPlayerCdnFailoverState>) {
            this@VideoPlayerViewModel.currentCdnStates = states
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
    // 当前激活播放使用的 CDN failover state（video + audio 各一个，最多 2 个）。
    // 卡顿时由 PlaybackFallbackController 通过 FallbackContext 读取并调 penalizeCurrentHost 降权。
    private var currentCdnStates: List<VideoPlayerCdnFailoverState> = emptyList()
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
        savedStateHandle[SAVED_EPISODE_INDEX] = _selectedEpisodeIndex.value
        savedStateHandle[SAVED_SEEK_POSITION_MS] = pendingSeekPositionMs.coerceAtLeast(0L)
        savedStateHandle[SAVED_QUALITY_ID] = requestedQualityId ?: selectedQualityId ?: 0
        savedStateHandle[SAVED_AUDIO_QUALITY_ID] = requestedAudioId ?: selectedAudioId ?: 0
        subtitleController.persistSelection()
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
        val subtitleIndex = subtitleController.consumePersistedSelection()
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
            currentGraphVersion = 0L
            AppLog.i(TAG, "subtitle_trace reset_by_loadVideoInfo cid=$currentCid bvid=$currentBvid")
            loadedPlayerExtrasCid = 0L
            pendingPlayerExtrasCid = 0L
            requestedQualityId = preferredQualityId.takeIf { it > 0 } ?: currentSettings.defaultVideoQualityId
            requestedAudioId = preferredAudioQualityId.takeIf { it > 0 } ?: currentSettings.defaultAudioQualityId
            requestedCodec = currentSettings.defaultVideoCodec
            selectedQualityId = null
            selectedAudioId = null
            selectedCodec = null
            didApplyLastPlayPosition = pendingSeekPositionMs > 0L
            heartbeatReporter.clear()
            fallbackController.reset()
            // 自动连播倒计时已经准备好的同一目标不能在入口重置时被清掉，否则会退回冷启动链路。
            clearPreloadedPlaybackIfDifferent(currentPlayRequestIdentity(), cancelJob = false)
            hasReachedFirstFrame = false
            currentDashSession = null
            subtitleController.resetForNewVideo(currentSettings.showSubtitleByDefault)
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
        val episodes = _episodes.value
        val targetIndex = _selectedEpisodeIndex.value - 1
        if (targetIndex in episodes.indices) {
            playEpisode(targetIndex)
        }
    }

    fun hasPreviousEpisode(): Boolean {
        val previousIndex = _selectedEpisodeIndex.value - 1
        return previousIndex in _episodes.value.indices
    }

    fun playNext(preferLastPlayTime: Boolean = true) {
        val episodes = _episodes.value
        val targetIndex = _selectedEpisodeIndex.value + 1
        if (targetIndex in episodes.indices) {
            playEpisode(targetIndex, preferLastPlayTime = preferLastPlayTime)
        }
    }

    fun hasNextEpisode(): Boolean {
        val episodes = _episodes.value
        val nextIndex = _selectedEpisodeIndex.value + 1
        return nextIndex in episodes.indices
    }

    fun getNextEpisode(): PlayableEpisode? {
        val nextIndex = _selectedEpisodeIndex.value + 1
        return _episodes.value.getOrNull(nextIndex)
    }

    fun playEpisode(index: Int, preferLastPlayTime: Boolean = true) {
        val episode = _episodes.value.getOrNull(index) ?: return
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
        currentAid = episode.aid.takeIf { it > 0 } ?: currentAid
        currentBvid = episode.bvid.takeIf { it.isNotBlank() } ?: targetBvid ?: currentBvid.orEmpty()
        currentSeasonId = targetSeasonId ?: currentSeasonId
        currentEpId = targetEpId ?: currentEpId
        subtitleController.resetSession()
        AppLog.i(TAG, "subtitle_trace reset_by_selectEpisode cid=$currentCid bvid=$currentBvid")
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
        pendingSeekPositionMs = 0L
        pendingPlayWhenReady = true
        didApplyLastPlayPosition = false
        currentSeasonId = null
        currentEpId = null
        subtitleController.resetSession()
        danmakuController.markDmMaskIdle()
        clearPreloadedPlaybackIfDifferent(currentPlayRequestIdentity(), cancelJob = false)
        loadPlayUrl(preferLastPlayTime = false)
        loadInteractionInfo(edgeId)
        AppLog.i(TAG, "subtitle_trace reset_by_playInteractionChoice cid=$currentCid bvid=$currentBvid")
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
        // 无缝切换：当前挂的是多清晰度 DASH MPD 且目标档在同编码下可用时，
        // 只改 TrackSelection 目标并丢弃旧档缓冲，不重建 MediaSource、不黑屏不重seek。
        val catalog = currentSeamlessCatalog
        val codec = currentSeamlessCodec
        if (catalog != null && codec != null && catalog.hasTrack(quality.id, codec)) {
            capturePlaybackSnapshot(currentPositionMs, playWhenReady)
            SeamlessQualitySelector.setTarget(quality.id, codec.id)
            currentDashSession = currentDashSession?.copy(actualQualityId = quality.id)
            AppLog.i(
                TAG,
                "seamless quality switch qn=${quality.id} codec=${codec.id} " +
                    "pos=${currentPositionMs}ms available=${catalog.qualityIds}"
            )
            return
        }
        capturePlaybackSnapshot(currentPositionMs, playWhenReady)
        loadPlayUrl(preferLastPlayTime = false, replaceInPlace = true)
    }

    /**
     * 渲染器层真实生效的视频轨回读（来自 onVideoInputFormatChanged 解析 Representation id）。
     * 初始挂载或目标档不可用时实际档可能与期望不一致，以实际为准纠正 UI 选择态与心跳档位。
     */
    fun onSeamlessVideoTrackChanged(qn: Int, codecid: Int) {
        if (currentSeamlessCatalog == null) return
        val codec = VideoCodecEnum.fromId(codecid)
        currentSeamlessCodec = codec
        if (qn > 0) {
            currentDashSession = currentDashSession?.copy(actualQualityId = qn, actualCodec = codec)
            val selected = _selectedQuality.value
            if (selected?.id != qn) {
                val actual = VideoQuality.fromId(qn)
                if (actual.id == qn) {
                    _selectedQuality.value = actual
                    requestedQualityId = qn
                    AppLog.i(TAG, "seamless actual track qn=$qn codec=$codec corrected from=${selected?.id}")
                }
            }
        }
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
                "pos=${currentPositionMs}ms playWhenReady=$playWhenReady"
        )
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
        subtitleController.updateSubtitleText(sanitizedPositionMs)
        checkSponsorBlock(sanitizedPositionMs)
        // 弹幕分段同步：检测 seek 跳变并补齐目标位置弹幕，同时更新 lastSync 和加载分段。
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
        val episode = episodeIndex?.let { _episodes.value.getOrNull(it) }
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
        return cid == identity.cid &&
            (identity.epId == null || epId == identity.epId) &&
            (identity.bvid.isNullOrBlank() || bvid.isBlank() || bvid == identity.bvid)
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
        var rebuiltSeamlessCatalog: SeamlessQualityCatalog? = null
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
                val sessionExpiryMs = VideoPlayerUrlUtils.resolveSessionExpiryMs(route)
                try {
                    val sourceWithState = dashMediaSourceFactory.createMediaSourceWithCdnState(route)
                    dashMediaSource = sourceWithState.mediaSource
                    currentCdnStates = sourceWithState.cdnFailoverStates
                    if (seamlessQualitySwitchEnabled) {
                        val seamlessCatalog = streamResolver.buildSeamlessCatalog(
                            playInfo = playInfo,
                            initialQualityId = selectionSnapshot.selectedQualityId ?: return,
                            selectedAudioId = selectionSnapshot.selectedAudioId,
                            initialCodec = route.codec
                        )
                        val seamlessSource = seamlessCatalog?.let { catalog ->
                            runCatching { seamlessDashMediaSourceFactory.createMediaSource(catalog) }
                                .onFailure { e ->
                                    AppLog.w(TAG, "seamless rebuild failed, fallback progressive: ${e.message}", e)
                                }
                                .getOrNull()
                        }
                        if (seamlessSource != null) {
                            dashMediaSource = seamlessSource.mediaSource
                            currentCdnStates = seamlessSource.cdnFailoverStates
                            rebuiltSeamlessCatalog = seamlessSource.catalog
                        }
                    }
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

        val progressiveSelection = if (dashMediaSource == null) {
            streamResolver.buildMediaSource(
                playInfo = playInfo,
                selectedQualityId = selectionSnapshot.selectedQualityId,
                selectedAudioId = selectionSnapshot.selectedAudioId,
                selectedCodec = selectionSnapshot.selectedCodec
            )
        } else null
        val mediaSource = dashMediaSource ?: progressiveSelection?.mediaSource ?: run {
            _error.value = "当前清晰度/音轨组合不可播放"
            return
        }
        // progressive 兜底路径也需要接管 CDN state；DASH 路径已在上面回填。
        if (progressiveSelection != null) {
            currentCdnStates = progressiveSelection.cdnFailoverStates
        }
        applySelectionSnapshot(selectionSnapshot)
        // 与 applyPreparedPlayback 同步维护无缝会话状态（切换编码/音轨重建后保持无缝能力）。
        currentSeamlessCatalog = rebuiltSeamlessCatalog
        if (rebuiltSeamlessCatalog != null) {
            currentSeamlessCodec = rebuiltSeamlessCatalog.initialCodec
            SeamlessQualitySelector.setTarget(
                rebuiltSeamlessCatalog.initialQualityId,
                rebuiltSeamlessCatalog.initialCodec.id
            )
        } else {
            currentSeamlessCodec = null
            SeamlessQualitySelector.clearTarget()
        }
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
        _relatedVideos.value = emptyList()
        subtitleController.setSubtitles(emptyList())
        AppLog.i(TAG, "subtitle_trace tracks_clear_source=pgc cid=$currentCid bvid=$currentBvid")


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
        if (initialIdentity != null &&
            initialIdentity.cid > 0L &&
            isRecentlyPlayed(initialIdentity.cid) &&
            !initialIdentity.bvid.isNullOrBlank() &&
            !activePlaybackIntentId.startsWith("douyin:")
        ) {
            val replayBvid = initialIdentity.bvid
            // Check PlayInfo cache for interaction flag (set by x/player/v2 on first play)
            val cachedIsSteinsGate = VideoPlayerPlayInfoCache.isSteinsGate(
                replayBvid, initialIdentity.cid
            )
            if (cachedIsSteinsGate) isSteinsGateVideo = true

            // ── Zero-overhead reuse: same bvid+cid, player still has MediaSource ──
            val cachedPlayback = getCachedPlayback(replayBvid, initialIdentity.cid)
            // 双重确认：VM 缓存命中只是"我有这个视频的复用快照"，不代表 player 实例当前
            // 挂的就是它（VM 缓存容量 2，player 单例只能挂 1 个，退出看别的视频后 player
            // 已被覆盖）。必须向 PlayerInstancePool 查询 player 实际挂载状态——它是唯一的
            // 事实源。不一致则放弃暖路径，fall through 到下方 cachedPlayInfo 分支重建源。
            val playerHasSameSource = PlayerInstancePool.isAttachedSource(
                replayBvid, initialIdentity.cid
            )
            if (cachedPlayback != null && playerHasSameSource) {
                // 诊断：对照"请求身份"与"VM 缓存命中内容"，防止跨视频串台。
                val cachedUri = runCatching {
                    cachedPlayback.mediaSource.mediaItem.localConfiguration?.uri?.toString()
                }.getOrNull()
                AppLog.w(
                    TAG,
                    "zero_overhead_reuse_hit reqBvid=$replayBvid reqCid=${initialIdentity.cid} " +
                        "cacheBvid=${cachedPlayback.bvid} cacheCid=${cachedPlayback.cid} " +
                        "cacheUri=${cachedUri?.substringAfterLast('/')}"
                )
                PlaybackStartupTrace.log(
                    traceId = currentStartupTraceId,
                    startElapsedMs = currentStartupTraceStartElapsedMs,
                    step = "zero_overhead_reuse",
                    message = "bvid=$replayBvid cid=${initialIdentity.cid} " +
                        "cacheBvid=${cachedPlayback.bvid} cacheCid=${cachedPlayback.cid} " +
                        "cacheUri=${cachedUri?.substringAfterLast('/')}"
                )
                applySelectionSnapshot(cachedPlayback.selectionSnapshot)
                currentPlayInfo = cachedPlayback.playInfo
                // zero-overhead 复用：player 上仍挂着同一 MediaSource（可能是多清晰度 MPD），
                // TrackSelection target 无人改动、保持用户上次选择的档位即可，这里只恢复 VM 侧目录状态。
                currentSeamlessCatalog = cachedPlayback.seamlessCatalog
                currentSeamlessCodec = cachedPlayback.seamlessCatalog?.initialCodec
                val playInfo = cachedPlayback.playInfo
                val resumePositionMs = if (preferLastPlayTime && !isSteinsGateVideo && currentGraphVersion <= 0L) {
                    val cachedResume = VideoPlayerPlayInfoCache.get(
                        replayBvid, initialIdentity.cid
                    )?.lastPlayTime?.takeIf { it > 5000L }
                    val serverResume = playInfo.lastPlayTime.takeIf { it > 5000L && playInfo.lastPlayCid == initialIdentity.cid }
                    (cachedResume ?: serverResume ?: pendingSeekPositionMs)
                        .takeIf { it > 5000L && (playInfo.timeLength - it) > 5000L }
                } else null
                val effectiveSeekMs = resumePositionMs ?: 0L
                _playbackRequest.value = PlaybackRequest(
                    mediaSource = cachedPlayback.mediaSource,
                    aid = initialIdentity.aid,
                    bvid = replayBvid,
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
                viewModelScope.launch {
                    val detailResponse = apiService.getVideoDetail(detailAid, detailBvid)
                    if (!isActiveVideoLoad(loadGeneration) || currentCid != initialIdentity.cid) {
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
                            it.cid == initialIdentity.cid || (it.bvid.isNotBlank() && it.bvid == replayBvid)
                        }.takeIf { it >= 0 } ?: 0
                        val related = detail.related.orEmpty()
                        val detailSubtitleTracks = detail.view?.subtitle?.list
                            ?.map { it.toSubtitleInfoModel() }
                            .orEmpty()
                        subtitleController.setSubtitles(detailSubtitleTracks)
                        AppLog.i(
                            TAG,
                            "subtitle_trace tracks_set_source=detail_hot cid=$currentCid bvid=$currentBvid " +
                                "detailCid=${detail.view?.cid} detailBvid=${detail.view?.bvid} " +
                                "count=${detailSubtitleTracks.size} " +
                                "stale=${initialIdentity.cid != currentCid} " +
                                "tracks=${subtitleTracksSummary(detailSubtitleTracks)}"
                        )
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

            // 诊断：VM 缓存命中但 player 挂载的不是同一视频，放弃暖路径。
            // 会 fall through 到下方 cachedPlayInfo 分支重建 MediaSource（省 getPlayUrl）。
            if (cachedPlayback != null) {
                AppLog.w(
                    TAG,
                    "zero_overhead_reuse_skip reason=player_source_mismatch " +
                        "reqBvid=$replayBvid reqCid=${initialIdentity.cid} " +
                        "(VM 缓存命中但 player 挂的是别的视频，降级走 setMediaSource 冷路径)"
                )
                PlaybackStartupTrace.log(
                    traceId = currentStartupTraceId,
                    startElapsedMs = currentStartupTraceStartElapsedMs,
                    step = "zero_overhead_reuse_skip",
                    message = "reason=player_source_mismatch bvid=$replayBvid cid=${initialIdentity.cid}"
                )
            }

            val cachedPlayInfo = VideoPlayerPlayInfoCache.get(bvid = replayBvid, cid = initialIdentity.cid)
                ?.takeIf { fallbackController.hasPlayableMedia(it) }

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
                viewModelScope.launch {
                    val detailResponse = apiService.getVideoDetail(detailAid, detailBvid)
                    if (!isActiveVideoLoad(loadGeneration) || currentCid != initialIdentity.cid) {
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
                            it.cid == initialIdentity.cid || (it.bvid.isNotBlank() && it.bvid == replayBvid)
                        }.takeIf { it >= 0 } ?: 0
                        val related = detail.related.orEmpty()
                        val detailSubtitleTracks = detail.view?.subtitle?.list
                            ?.map { it.toSubtitleInfoModel() }
                            .orEmpty()
                        subtitleController.setSubtitles(detailSubtitleTracks)
                        AppLog.i(
                            TAG,
                            "subtitle_trace tracks_set_source=detail_cached cid=$currentCid bvid=$currentBvid " +
                                "detailCid=${detail.view?.cid} detailBvid=${detail.view?.bvid} " +
                                "count=${detailSubtitleTracks.size} " +
                                "stale=${initialIdentity.cid != currentCid} " +
                                "tracks=${subtitleTracksSummary(detailSubtitleTracks)}"
                        )
                        if (related.isNotEmpty()) {
                            _relatedVideos.value = related
                        }
                    }
                }
                return@coroutineScope
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

        // 分P 选择：优先按 cid 精确匹配。
        // 注意：不能加 bvid 匹配作为 fallback——多P视频所有分P共用同一个 bvid，
        // 会导致 indexOfFirst 永远命中第一个分P，覆盖掉调用方传入的目标 cid（公益广告场景踩到）。
        var selectedIndex = if (currentCid > 0L) {
            episodeItems.indexOfFirst { it.cid == currentCid }.takeIf { it >= 0 } ?: 0
        } else {
            0
        }

        // ── 续播定位：当前未指定 cid（如收藏夹入口）时，先查历史记录接口定位最后播放分P，
        // 未命中再降级为遍历探测（用所有分P cid 并发请求 playurl，取 last_play_time 最大的分P）。
        // 历史接口一次请求即可拿全「最后观看分P + 进度」，优于遍历探测的 O(N) 次 playurl 请求。
        if (currentCid <= 0L && episodeItems.size > 1) {
            val historyResume = resolveResumeFromHistory(episodeItems)
            if (historyResume != null) {
                val (historyIndex, historyProgressMs) = historyResume
                AppLog.i(
                    TAG,
                    "history_resume applied: index=$historyIndex cid=${episodeItems[historyIndex].cid} " +
                        "progressMs=$historyProgressMs"
                )
                selectedIndex = historyIndex
                if (historyProgressMs > 0L) {
                    pendingSeekPositionMs = historyProgressMs
                }
            } else {
                val probeResult = probeLastPlayEpisode(episodeItems)
                if (probeResult != null) {
                    val (probeIndex, probeTime) = probeResult
                    AppLog.i(TAG, "probe_resume found: index=$probeIndex cid=${episodeItems[probeIndex].cid} lastPlayTime=${probeTime}ms")
                    selectedIndex = probeIndex
                } else {
                    AppLog.i(TAG, "probe_resume not_found: no episode has last_play_time > 5000ms")
                }
            }
        }
        _selectedEpisodeIndex.value = selectedIndex
        val selectedEpisode = episodeItems.getOrNull(selectedIndex)
        currentCid = selectedEpisode?.cid
            ?: detail.view?.cid
            ?: currentCid
        currentAid = selectedEpisode?.aid?.takeIf { it > 0L } ?: currentAid
        currentBvid = selectedEpisode?.bvid?.takeIf { it.isNotBlank() } ?: currentBvid

        // 提前启动弹幕 view 请求，和 PlayInfo 并行
        danmakuController.preloadViewIfNeeded(loadGeneration)

        if (currentCid <= 0L) {
            _error.value = "未找到可播放分P"
            return@coroutineScope
        }

        val related = detail.related.orEmpty()
        val detailSubtitleTracks = detail.view?.subtitle?.list
            ?.map { it.toSubtitleInfoModel() }
            .orEmpty()
        subtitleController.setSubtitles(detailSubtitleTracks)
        AppLog.i(
            TAG,
            "subtitle_trace tracks_set_source=detail_sync cid=$currentCid bvid=$currentBvid " +
                "detailCid=${detail.view?.cid} detailBvid=${detail.view?.bvid} " +
                "count=${detailSubtitleTracks.size} " +
                "tracks=${subtitleTracksSummary(detailSubtitleTracks)}"
        )
        subtitleController.maybeAutoSelectSubtitle()
        val resolvedIdentity = currentPlayRequestIdentity()
        val canReuse = preparedPlaybackDeferred != null &&
            canReusePreparedPlayback(initialIdentity, resolvedIdentity)
        val preparedPlayback = initialPreloadedPlayback ?: if (canReuse) {
            preparedPlaybackDeferred.await()
        } else null
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

    /**
     * 先查历史记录接口定位用户最后播放的分P。
     *
     * 为什么优先历史接口而非遍历 playurl：历史接口 `x/web-interface/history/cursor` 一次请求
     * 就能同时拿到「最后观看分P（history.cid）」与「进度（progress）」，而遍历 playurl 需要对
     * 全部分P逐个请求（125P 最坏 25 批、5 秒），服务端压力与首屏延迟都明显更大。
     *
     * 匹配方式：历史记录列表从新到旧，用 aid/bvid 匹配目标视频，命中即用其 history.cid 定位分P。
     * `history.cid` 即用户最后观看的分P cid（语义最准，而非遍历法的 last_play_time 间接推断）。
     *
     * 分页策略：ps 上限 30，最多翻 3 页（90 条）。翻 3 页仍未命中则放弃（用户最近 90 条观看记录
     * 中都没有该视频，说明很久没看，继续翻页收益低、代价大），降级到遍历探测兜底。
     *
     * @param episodes 分P列表（用于把 history.cid 映射为分P索引）
     * @return Pair<分P索引, 进度毫秒>，未命中返回 null。
     */
    private suspend fun resolveResumeFromHistory(
        episodes: List<PlayableEpisode>
    ): Pair<Int, Long>? {
        val aid = currentAid
        val bvid = currentBvid?.takeIf { it.isNotBlank() }
        if ((aid == null || aid <= 0L) && bvid.isNullOrBlank()) return null
        if (!userRepository.isLoggedIn()) return null

        val resumeStartMs = System.currentTimeMillis()
        val maxPages = 6
        val pageSize = 30
        var viewAt = 0L
        val matchedAid = aid?.takeIf { it > 0L }
        val matchedBvid = bvid

        for (page in 1..maxPages) {
            val result = userRepository.getHistory(viewAt, pageSize).getOrNull()
            val history = result?.data ?: return null
            val list = history.list
            if (list.isEmpty()) break

            for (item in list) {
                val itemAid = item.history?.oid ?: item.kid
                val itemBvid = item.history?.bvid?.ifEmpty { item.bvid } ?: item.bvid
                val isMatch = (matchedAid != null && itemAid == matchedAid) ||
                    (matchedBvid != null && itemBvid == matchedBvid)
                if (!isMatch) continue

                val resumeCid = item.history?.cid
                if (resumeCid == null || resumeCid <= 0L) continue
                val episodeIndex = episodes.indexOfFirst { it.cid == resumeCid }
                if (episodeIndex < 0) continue

                // progress 字段单位是「秒」，转成毫秒与播放器进度对齐
                val resumeMs = (item.progress.takeIf { it > 0L } ?: 0L) * 1000L
                val durationMs = System.currentTimeMillis() - resumeStartMs
                AppLog.i(
                    TAG,
                    "history_resume hit: page=$page index=$episodeIndex cid=$resumeCid " +
                        "progressMs=$resumeMs durationMs=$durationMs"
                )
                return episodeIndex to resumeMs
            }

            viewAt = history.cursor?.viewAt
                ?: list.lastOrNull()?.viewAt
                ?: 0L
            if (viewAt <= 0L) break
        }

        val durationMs = System.currentTimeMillis() - resumeStartMs
        AppLog.i(TAG, "history_resume not_found: pages=$maxPages durationMs=$durationMs")
        return null
    }

    /**
     * 从前往后分批探测用户最后播放的分P。
     *
     * 为什么不用二分：实测数据显示 playurl 的 last_play_time 在分P列表中呈极度稀疏分布
     * （125P 中仅 1 个分P有记录），二分采样命中率约 2%，几乎必定退化到全量扫描。
     *
     * 为什么从前往后：用户从第1P开始看，看到哪里停在哪里。从前往后按播放顺序找到
     * 第一个（也是唯一）有播放记录的分P即停止。
     *
     * 策略：从第0P开始，每批并发 5 个请求，批次内从后往前取最后一个 last_play_time > 5000ms 的分P。
     * 命中即停止，未命中继续下一批。最坏情况扫描全量。
     *
     * @return Pair<分P索引, last_play_time毫秒>，若所有分P均无播放记录则返回 null。
     */
    private suspend fun probeLastPlayEpisode(
        episodes: List<PlayableEpisode>
    ): Pair<Int, Long>? = coroutineScope {
        if (episodes.isEmpty()) return@coroutineScope null

        val aid = currentAid
        val bvid = currentBvid?.takeIf { it.isNotBlank() }
        if ((aid == null || aid <= 0L) && bvid.isNullOrBlank()) return@coroutineScope null

        val qualityId = requestedQualityId ?: selectedQualityId ?: 80
        val fnval = 16
        val fourk = 0
        val batchSize = 5
        val probeStartMs = System.currentTimeMillis()

        suspend fun queryEpisode(index: Int): Pair<Int, Long> {
            val ep = episodes[index]
            val result = playInfoGateway.requestPlayInfo(
                aid = aid, bvid = bvid, cid = ep.cid,
                epId = null, qualityId = qualityId,
                fnval = fnval, fourk = fourk,
                allowWbi = true, seasonId = 0L
            )
            val lastPlayTime = result?.data?.lastPlayTime ?: 0L
            AppLog.d(TAG, "probe_resume episode[$index] cid=${ep.cid} lastPlayTime=${lastPlayTime}ms")
            return index to lastPlayTime
        }

        // 从前往后分批探测
        var offset = 0
        while (offset < episodes.size) {
            val end = minOf(offset + batchSize - 1, episodes.size - 1)
            val batchIndices = (offset..end).toList()
            val batchResults = batchIndices.map { idx -> async { queryEpisode(idx) } }.awaitAll()

            // 在批次内从后往前找最后一个有效的
            val hit = batchResults.lastOrNull { it.second > 5000L }
            if (hit != null) {
                val probeDurationMs = System.currentTimeMillis() - probeStartMs
                val batchesDone = (offset / batchSize) + 1
                AppLog.i(TAG, "probe_resume hit: index=${hit.first} lastPlayTime=${hit.second}ms durationMs=$probeDurationMs batches=$batchesDone")
                return@coroutineScope hit.first to hit.second
            }

            offset = end + 1
        }

        val probeDurationMs = System.currentTimeMillis() - probeStartMs
        AppLog.i(TAG, "probe_resume not_found: episodes=${episodes.size} durationMs=$probeDurationMs")
        return@coroutineScope null
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
        val (initialPlayInfo, effectiveRequestedQualityId) = if (cachedPlayInfo != null) {
            cachedPlayInfo to preferredQualityId
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
            message = "cid=${identity.cid} cached=${cachedPlayInfo != null} quality=$effectiveRequestedQualityId durationMs=${System.currentTimeMillis() - requestStartMs}"
        )

        viewModelScope.launch(Dispatchers.IO) {
            cdnPreconnector.forPlayInfo(initialPlayInfo)
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
                availableQualities = initialQualities
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
            var preparedCdnStates: List<VideoPlayerCdnFailoverState> = emptyList()
            var preparedSeamlessCatalog: SeamlessQualityCatalog? = null
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
                        cdnPreconnector.forRoute(firstRoute)
                    }

                    val sessionExpiryMs = VideoPlayerUrlUtils.resolveSessionExpiryMs(firstRoute)
                    try {
                        val sourceWithState = dashMediaSourceFactory.createMediaSourceWithCdnState(firstRoute)
                        dashMediaSource = sourceWithState.mediaSource
                        preparedCdnStates = sourceWithState.cdnFailoverStates
                        // 无缝优先：满足条件时把单档 Progressive+Merging 源替换为多清晰度 DASH MPD 源，
                        // 后续切档只改 track selection 不重建；构建失败静默回退旧链路。
                        if (seamlessQualitySwitchEnabled) {
                            val seamlessCatalog = streamResolver.buildSeamlessCatalog(
                                playInfo = initialPlayInfo,
                                initialQualityId = resolvedQualityId,
                                selectedAudioId = selectionSnapshot.selectedAudioId,
                                initialCodec = firstRoute.codec
                            )
                            if (seamlessCatalog != null) {
                                val seamlessSource = runCatching {
                                    seamlessDashMediaSourceFactory.createMediaSource(seamlessCatalog)
                                }.onFailure { e ->
                                    AppLog.w(TAG, "seamless source build failed, fallback progressive: ${e.message}", e)
                                }.getOrNull()
                                if (seamlessSource != null) {
                                    dashMediaSource = seamlessSource.mediaSource
                                    preparedCdnStates = seamlessSource.cdnFailoverStates
                                    preparedSeamlessCatalog = seamlessSource.catalog
                                }
                            } else {
                                AppLog.i(
                                    TAG,
                                    "seamless catalog unavailable cid=${identity.cid} quality=$resolvedQualityId, use progressive merge"
                                )
                            }
                        }
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
                        preparedCdnStates = emptyList()
                    }
                }
            }

            val progressiveSelection = if (dashMediaSource == null) {
                streamResolver.buildMediaSource(
                    playInfo = initialPlayInfo,
                    selectedQualityId = resolvedQualityId,
                    selectedAudioId = selectionSnapshot.selectedAudioId,
                    selectedCodec = selectionSnapshot.selectedCodec
                )
            } else null
            val mediaSource: MediaSource = dashMediaSource ?: progressiveSelection?.mediaSource ?: run {
                AppLog.e(TAG, "loadPlayUrl mediaSource missing: cid=${identity.cid}")
                return@withContext null
            }
            // progressive 兜底路径接管 CDN state；DASH 路径的 state 已在 preparedCdnStates 中。
            val preparedCdnStatesFinal = preparedCdnStates.ifEmpty { progressiveSelection?.cdnFailoverStates.orEmpty() }

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
                startupTraceStartElapsedMs = startupTraceStartElapsedMs,
                cdnStates = preparedCdnStatesFinal,
                seamlessCatalog = preparedSeamlessCatalog
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
        currentCdnStates = preparedPlayback.cdnStates
        applySelectionSnapshot(preparedPlayback.selectionSnapshot)
        // 无缝会话状态：挂新源前重置 TrackSelection 目标，避免上一个视频的档位串到新视频。
        val seamlessCatalog = preparedPlayback.seamlessCatalog
        currentSeamlessCatalog = seamlessCatalog
        if (seamlessCatalog != null) {
            currentSeamlessCodec = seamlessCatalog.initialCodec
            SeamlessQualitySelector.setTarget(seamlessCatalog.initialQualityId, seamlessCatalog.initialCodec.id)
        } else {
            currentSeamlessCodec = null
            SeamlessQualitySelector.clearTarget()
        }
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
            selectionSnapshot = preparedPlayback.selectionSnapshot,
            seamlessCatalog = preparedPlayback.seamlessCatalog
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

        viewModelScope.launch(Dispatchers.Default) {
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
            withContext(Dispatchers.Main) {
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
            // 首帧后先把弹幕链路放出去，心跳/扩展信息延后一个很短的窗口，降低首显附近主线程抖动。
            delay(FIRST_FRAME_DEFERRED_WORK_DELAY_MS.milliseconds)
            if (currentCid != cid) return@launch
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
            delay(FIRST_FRAME_SPONSOR_LOAD_DELAY_MS.milliseconds)
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
                message = "requested=$identity"
            )
            AppLog.i(TAG, "playback_preload_absent requested=$identity")
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
                delay(750.milliseconds)
                playInfoGateway.requestPlayerInfoData(
                    aid = aid,
                    bvid = bvid,
                    cid = cid
                )
            }
            val snapshotDeferred = async {
                delay(2_500.milliseconds)
                playInfoGateway.requestVideoSnapshot(
                    aid = aid,
                    bvid = bvid,
                    cid = cid
                )
            }

            playerInfoDeferred.await()?.let { initialWrapper ->
                // playerInfo 整包归属校验：首帧后，这个请求会延迟 750ms 加网络往返后返回。
                // 如果请求期间切换视频，返回的数据包属于旧视频，应整体丢弃，避免字幕、蒙版和互动数据串台。
                // 与下方 snapshot 分支的陈旧性校验保持一致。
                if (currentCid != cid || currentAid != aid || currentBvid != bvid) {
                    AppLog.w(
                        TAG,
                        "subtitle_trace tracks_drop_source=playerInfo_stale " +
                            "reqCid=$cid reqBvid=$bvid curCid=$currentCid curBvid=$currentBvid"
                    )
                    return@let
                }
                var wrapper = initialWrapper
                var subtitleTracks = wrapper.subtitle?.subtitles.orEmpty()
                var detailTracks = subtitleController.subtitlesValue()
                var trustedTracks = trustedPlayerInfoSubtitleTracks(detailTracks, subtitleTracks)
                if (shouldRetryPlayerInfoSubtitleTracks(detailTracks, subtitleTracks)) {
                    AppLog.w(
                        TAG,
                        "subtitle_trace tracks_retry_source=playerInfo cid=$cid bvid=$bvid " +
                            "playerInfoCount=${subtitleTracks.size}"
                    )
                    delay(1_000.milliseconds)
                    val retryWrapper = playInfoGateway.requestPlayerInfoData(
                        aid = aid,
                        bvid = bvid,
                        cid = cid,
                        cacheBustTimestamp = System.currentTimeMillis()
                    )
                    if (currentCid != cid || currentAid != aid || currentBvid != bvid) {
                        AppLog.w(
                            TAG,
                            "subtitle_trace tracks_drop_source=playerInfo_retry_stale " +
                                "reqCid=$cid reqBvid=$bvid curCid=$currentCid curBvid=$currentBvid"
                        )
                        return@let
                    }
                    if (retryWrapper != null) {
                        wrapper = retryWrapper
                        subtitleTracks = wrapper.subtitle?.subtitles.orEmpty()
                        detailTracks = subtitleController.subtitlesValue()
                        trustedTracks = trustedPlayerInfoSubtitleTracks(detailTracks, subtitleTracks)
                    }
                }
                if (subtitleTracks.isNotEmpty()) {
                    AppLog.i(
                        TAG,
                        "subtitle_trace tracks_compare cid=$currentCid bvid=$currentBvid " +
                            "detailCount=${detailTracks.size} " +
                            "playerInfoCount=${subtitleTracks.size} " +
                            "trustedCount=${trustedTracks.size} " +
                            "detailTracks=${subtitleTracksSummary(detailTracks)} " +
                            "playerInfoTracks=${subtitleTracksSummary(subtitleTracks)}"
                    )
                    if (trustedTracks.isNotEmpty()) {
                        subtitleController.setSubtitles(trustedTracks)
                        AppLog.i(
                            TAG,
                            "subtitle_trace tracks_set_source=playerInfo cid=$currentCid bvid=$currentBvid " +
                                "count=${trustedTracks.size}"
                        )
                        subtitleController.maybeAutoSelectSubtitle()
                    } else {
                        AppLog.w(
                            TAG,
                            "subtitle_trace tracks_drop_source=playerInfo_unmatched cid=$currentCid " +
                                "bvid=$currentBvid"
                        )
                    }
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

            // 预加载可见选项对应的下一跳节点。
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









    // [诊断] 把字幕轨道列表压缩成 "lan=url尾段" 的短摘要，便于在日志里对照
    // detail 与 playerInfo 两个接口返回的轨道是否一致、是否串台。
    // url 只取最后一个 '/' 之后的部分并截断，避免日志被超长 url 淹没。


    private fun DetailSubtitleItem.toSubtitleInfoModel(): SubtitleInfoModel {
        return SubtitleInfoModel(
            id = id,
            idStr = "",
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

    fun sponsorUserSeek(positionMs: Long) {
        sponsorBlockUseCase.onUserSeek(positionMs)
        _sponsorSkipState.value = SponsorSkipUiState.Hidden
    }
}




