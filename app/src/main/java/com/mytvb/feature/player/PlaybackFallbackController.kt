@file:Suppress("SpellCheckingInspection")

package com.mytvb.feature.player

import android.content.Context
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import com.mytvb.R
import com.mytvb.core.common.log.AppLog
import com.mytvb.core.common.media.VideoCodecSupport
import com.mytvb.model.player.PlayInfoModel
import com.mytvb.model.video.quality.VideoCodecEnum
import com.mytvb.model.video.quality.VideoQuality
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// VM 嵌套类型（internal/public）：同包不自动可见 nested，需显式引用全名。
internal typealias VmPlaybackRequest = VideoPlayerViewModel.PlaybackRequest
internal typealias VmPreparedPlayback = VideoPlayerViewModel.PreparedPlayback
internal typealias VmPlayRequestIdentity = VideoPlayerViewModel.PlayRequestIdentity
internal typealias VmPlayInfoFetchResult = VideoPlayerViewModel.PlayInfoFetchResult

/**
 * CDN/编码/画质 Fallback 的协作对象。
 *
 * 从 VideoPlayerViewModel 拆分而来，集中所有 fallback 相关逻辑：播放错误分类、CDN/编码切换、
 * playInfo 刷新与重派发、fallback 计划构建与签名去重、画质降级候选构造。
 *
 * 关键架构约束（不可破坏）：
 * 1. **共享状态双写防护**：`currentDashSession`/`currentStreamFallbackPlan`/`fallbackRouteIndex`/
 *    `fallbackCdnIndex` 四个共享状态【留在 ViewModel】，由加载主链 `applyPreparedPlayback` 与 fallback
 *    双向写入。Controller 不持有这四个可变引用，仅通过 [FallbackContext] 的 getter **实时读** VM 字段、
 *    通过 [FallbackContext.onDashSessionUpdated] / [FallbackContext.onStreamFallbackPlanUpdated]
 *    **提议**新值由 VM 落地。
 * 2. **同步原子性**：[handlePlaybackError] / [handlePlaybackStall] 在 ExoPlayer 主线程错误回调中
 *    **同步**串联触发重建（codec→cdn→refresh），Controller 保持同步语义，不把整条链包进协程。
 *    仅 [loadPlayUrlWithCurrentContext] 内部用注入的 [scope] 启协程。
 * 3. **不可拆散**：[tryDispatchPlaybackAttempt] 写 `selectedCodec`（经回调）+ 发 `VmPlaybackRequest`
 *    （经 [FallbackContext.emitPlaybackRequest]）必须原子；[tryDispatchDashPlaybackAttempt]
 *    写 `currentDashSession`（经回调）+ 发请求必须原子。
 */
@UnstableApi
internal class PlaybackFallbackController(
    private val streamResolver: VideoPlayerStreamResolver,
    private val dashMediaSourceFactory: VideoPlayerDashMediaSourceFactory,
    private val qualityPolicy: VideoPlayerQualityPolicy,
    private val playInfoGateway: VideoPlayerPlayInfoGateway,
    private val scope: CoroutineScope,
    private val appContext: Context,
    private val context: FallbackContext
) {

    /**
     * Fallback 逻辑读取/写入的 ViewModel 上下文，由 ViewModel 用 inner class 实现。
     *
     * 共享状态 getter 必须**实时读** VM 字段（不缓存快照）；setter 仅"提议"新值由 VM 落地，
     * 保证加载主链与 fallback 双写的一致性。
     */
    interface FallbackContext {
        // ===== 只读播放上下文 =====
        val currentPlayInfo: PlayInfoModel?
        val currentCid: Long
        val currentAid: Long?
        val currentBvid: String?
        val currentEpId: Long?
        val currentSeasonId: Long?
        val selectedQualityId: Int?
        val selectedCodec: VideoCodecEnum?
        val requestedQualityId: Int?
        val requestedCodec: VideoCodecEnum?
        val useDashPlayback: Boolean
        val hardwareSupportedVideoCodecs: Set<VideoCodecEnum>
        val activePlaybackIntentId: String
        val startupTraceId: String
        val startupTraceStartElapsedMs: Long

        // ===== 共享状态读（实时读 VM 字段，不缓存） =====
        val dashSession: VideoPlaybackSession?
        val streamFallbackPlan: VideoPlayerStreamResolver.StreamFallbackPlan?
        val fallbackRouteIndex: Int
        val fallbackCdnIndex: Int
        val cdnStates: List<VideoPlayerCdnFailoverState>

        // ===== 共享状态写（提议新值，由 VM 主线程落地） =====
        fun onDashSessionUpdated(session: VideoPlaybackSession?)
        fun onStreamFallbackPlanUpdated(
            plan: VideoPlayerStreamResolver.StreamFallbackPlan?,
            routeIndex: Int,
            cdnIndex: Int
        )
        fun onCdnStatesUpdated(states: List<VideoPlayerCdnFailoverState>)

        // ===== VM 私有方法转发 =====
        fun currentPlayRequestIdentity(): VmPlayRequestIdentity?
        fun emitRiskControlTryLookBypass()

        // ===== UI/派发写 =====
        fun emitPlaybackRequest(request: VmPlaybackRequest)
        fun setSelectedCodec(codec: VideoCodecEnum)
        fun clearError()
        fun reportError(message: String)

        // ===== 加载主链回调 =====
        suspend fun requestPreparedPlayback(
            identity: VmPlayRequestIdentity,
            preferLastPlayTime: Boolean,
            replaceInPlace: Boolean,
            playbackPositionMs: Long,
            playWhenReady: Boolean,
            qualityCandidates: List<Int>
        ): VmPreparedPlayback?

        fun applyPreparedPlayback(
            preparedPlayback: VmPreparedPlayback,
            resetFallbackAttempts: Boolean,
            countCurrentAttemptAsFallback: Boolean
        )
    }

    companion object {
        private const val TAG = "VideoPlayerViewModel"
        private const val MAX_FALLBACK_ATTEMPTS = 8
        private const val MAX_PLAYINFO_REFRESH_RETRY = 2
    }

    /** 播放错误分类（原 VM 内私有 enum，随 classifyPlaybackError 迁入）。 */
    private enum class PlaybackErrorType {
        DECODER,
        NETWORK,
        UNKNOWN
    }

    // ===== 迁入的可变状态字段 =====
    private var playInfoRefreshRetryCount: Int = 0
    private var fallbackAttemptCount: Int = 0
    private val attemptedFallbackSignatures = linkedSetOf<String>()
    private var lastPlaybackPositionMs: Long = 0L
    /** 已触发过降档重建的锁定画质，防止同档失败后反复重建。 */
    private val qualityStepdownAttemptedIds = linkedSetOf<Int>()

    // ===== 公开 API（供 ViewModel 调用） =====

    /** ExoPlayer 错误回调入口，同步串联触发 fallback 重建链。 */
    fun handlePlaybackError(error: PlaybackException, currentPositionMs: Long) {
        lastPlaybackPositionMs = currentPositionMs.coerceAtLeast(0L)
        val dashSession = context.dashSession
        val streamPlan = context.streamFallbackPlan
        val hasDashFallback = dashSession?.routePlan?.routes?.isNotEmpty() == true && context.useDashPlayback
        val hasProgressiveFallback = streamPlan?.routes?.isNotEmpty() == true
        if (!hasDashFallback && !hasProgressiveFallback) {
            context.reportError(error.message ?: appContext.getString(R.string.player_load_failed))
            return
        }

        val errorType = classifyPlaybackError(error)
        val isDash = dashSession != null && context.useDashPlayback
        // CDN 健康分：网络/未知类错误多半与线路相关，对当前 host 记一次播放错误
        // （DECODER 类是解码问题，不是 CDN 的锅，不记）。换线重建前记，
        // 让重建用的候选排序和下次播放都能避开该 host。
        if (errorType != PlaybackErrorType.DECODER) {
            context.cdnStates.forEach { state ->
                state.currentHostUrl()?.let { url ->
                    CdnHealthProfile.recordEvent(url, CdnHealthProfile.CdnHealthEvent.PLAYBACK_ERROR)
                }
            }
        }

        val handled = when (errorType) {
            PlaybackErrorType.DECODER -> {
                trySwitchToAvc(lastPlaybackPositionMs, reason = "decoder_error_prefer_avc") ||
                    trySwitchCodec(lastPlaybackPositionMs, reason = "decoder_error") ||
                    tryNextCdnInCurrentCodec(lastPlaybackPositionMs, reason = "decoder_error_cdn_retry") ||
                    tryRefreshPlayInfo(reason = "decoder_error_refresh")
            }
            PlaybackErrorType.NETWORK -> {
                trySwitchToAvc(lastPlaybackPositionMs, reason = "network_error_prefer_avc") ||
                    tryNextCdnInCurrentCodec(lastPlaybackPositionMs, reason = "network_error") ||
                    tryRefreshPlayInfo(reason = "network_error_refresh") ||
                    trySwitchCodec(lastPlaybackPositionMs, reason = "network_error_codec_switch")
            }
            PlaybackErrorType.UNKNOWN -> {
                trySwitchToAvc(lastPlaybackPositionMs, reason = "unknown_error_prefer_avc") ||
                    tryNextCdnInCurrentCodec(lastPlaybackPositionMs, reason = "unknown_error") ||
                    trySwitchCodec(lastPlaybackPositionMs, reason = "unknown_error_codec_switch") ||
                    tryRefreshPlayInfo(reason = "unknown_error_refresh")
            }
        }

        if (!handled) {
            if (tryLowerQualityRebuild()) {
                return
            }
            val qualityLocked = dashSession?.routePlan?.qualityId
                ?: streamPlan?.qualityId
                ?: 0
            AppLog.e(TAG, "fallback exhausted: qualityLocked=$qualityLocked, isDash=$isDash, attempts=$fallbackAttemptCount")
            context.reportError(appContext.getString(R.string.player_error_all_routes_unavailable))
        }
    }

    /** 卡顿回调入口，返回是否已派发新 fallback。 */
    fun handlePlaybackStall(positionMs: Long, stalledMs: Long): Boolean {
        lastPlaybackPositionMs = positionMs.coerceAtLeast(0L)
        // 卡顿降权：把当前正在用的 CDN host 记一条惩罚性 TTFB，让后续视频的 sortUrlsByLatency
        // 把它排到 candidates 末尾，避免重复踩坑。借助 CdnLatencyProfile 的 5 条淘汰机制自动稀释，
        // 一次卡顿不会判死刑。放在 fallback 重建之前，确保本次重建的 state 也是基于降权后的排序。
        context.cdnStates.forEach { it.penalizeCurrentHost() }
        val handled =
            trySwitchToAvc(lastPlaybackPositionMs, reason = "stall_${stalledMs}ms_prefer_avc") ||
                tryNextCdnInCurrentCodec(lastPlaybackPositionMs, reason = "stall_${stalledMs}ms") ||
                tryRefreshPlayInfo(reason = "stall_${stalledMs}ms_refresh") ||
                trySwitchCodec(lastPlaybackPositionMs, reason = "stall_${stalledMs}ms_codec_switch")
        return handled
    }

    /**
     * 首帧看门狗超时入口：起播阶段长期未出首帧。stall 检测只覆盖 READY 之后的卡顿，
     * 起播 BUFFERING 卡死原本要等 read 超时（约 15s）才走 onPlayerError 错误链，
     * 此处把救援提前到 2.5s。对当前 host 记 FIRST_FRAME_TIMEOUT 后立即换线：
     * 起播慢不该先切 AVC，优先同档换线，不行再刷新 playurl（签名去重与
     * [MAX_FALLBACK_ATTEMPTS] 上限照常生效）。两条路都失败则放回原链兜底。
     */
    fun handleFirstFrameTimeout(positionMs: Long): Boolean {
        lastPlaybackPositionMs = positionMs.coerceAtLeast(0L)
        context.cdnStates.forEach { state ->
            state.currentHostUrl()?.let { url ->
                CdnHealthProfile.recordEvent(url, CdnHealthProfile.CdnHealthEvent.FIRST_FRAME_TIMEOUT)
            }
        }
        return tryNextCdnInCurrentCodec(lastPlaybackPositionMs, reason = "first_frame_timeout") ||
            tryRefreshPlayInfo(reason = "first_frame_timeout_refresh")
    }

    /** 切集/重置入口：清空所有 fallback 状态（含降档去重记忆）。 */
    fun reset() {
        resetAttemptState()
        qualityStepdownAttemptedIds.clear()
    }

    /**
     * 同视频内重建（applyPreparedPlayback）的计数复位：保留降档去重记忆。
     *
     * [qualityStepdownAttemptedIds] 若在此处一并清空，tryLowerQualityRebuild 自己触发的
     * 降档重建（resetFallbackAttempts=true）会把"已试过降档"的记录抹掉，同一视频可以
     * 无限次重复降档，错误风暴永不收敛（海信 DV runtime error 现场实测）。
     */
    fun resetAttemptState() {
        context.onStreamFallbackPlanUpdated(plan = null, routeIndex = 0, cdnIndex = 0)
        playInfoRefreshRetryCount = 0
        fallbackAttemptCount = 0
        attemptedFallbackSignatures.clear()
    }

    /**
     * 全路由耗尽后的画质降档重建：HDR/杜比/4K 等高画质档在该档内往往只有 HEVC 一种流，
     * 没有 AVC 兜底路由，解码失败只会在同档换 CDN/刷新打转；此时跳到下一档重新拉流，
     * 请求层（requestPlayInfoWithQualityFallback）会自动落到服务端实际可用的最高档。
     *
     * 服务端回落陷阱（海信 DV runtime error 现场实测）：候选档（如 129 HDR Vivid/125 HDR10）
     * 在该视频若无独立流，playinfo 响应回落到流内最高档——恰好又是降档起始档（126 杜比视界），
     * 重建后播的还是同一条流，错误照旧。此处对"实际锁定档 == 降档起始档"的候选逐个跳过，
     * 直到真正落到更低档；全部候选都回落同档才认输报错。
     */
    private fun tryLowerQualityRebuild(): Boolean {
        val lockedQualityId = context.dashSession?.routePlan?.qualityId
            ?: context.streamFallbackPlan?.qualityId
            ?: 0
        if (lockedQualityId <= 80 || lockedQualityId in qualityStepdownAttemptedIds) {
            return false
        }
        val candidates = qualityPolicy.buildCandidates(lockedQualityId).drop(1)
        val nextQualityId = candidates.firstOrNull() ?: return false
        val identity = context.currentPlayRequestIdentity() ?: return false
        qualityStepdownAttemptedIds.add(lockedQualityId)
        AppLog.i(
            TAG,
            "fallback:quality_stepdown from=$lockedQualityId to=$nextQualityId position=$lastPlaybackPositionMs"
        )
        scope.launch {
            var remainingCandidates = candidates
            var preparedPlayback: VmPreparedPlayback? = null
            while (remainingCandidates.isNotEmpty()) {
                val attempt = context.requestPreparedPlayback(
                    identity = identity,
                    preferLastPlayTime = false,
                    replaceInPlace = true,
                    playbackPositionMs = lastPlaybackPositionMs,
                    playWhenReady = true,
                    qualityCandidates = remainingCandidates
                ) ?: break
                val actualQualityId = attempt.dashSession?.routePlan?.qualityId
                    ?: attempt.selectionSnapshot.selectedQualityId
                if (actualQualityId == null || actualQualityId == lockedQualityId) {
                    AppLog.i(
                        TAG,
                        "fallback:quality_stepdown_skip candidate=${remainingCandidates.firstOrNull()} " +
                            "falls back to locked=$lockedQualityId"
                    )
                    remainingCandidates = remainingCandidates.drop(1)
                    continue
                }
                preparedPlayback = attempt
                break
            }
            if (preparedPlayback != null) {
                context.applyPreparedPlayback(
                    preparedPlayback = preparedPlayback,
                    resetFallbackAttempts = true,
                    countCurrentAttemptAsFallback = false
                )
            } else {
                context.reportError(appContext.getString(R.string.player_error_all_routes_unavailable))
            }
        }
        return true
    }

    /**
     * 记录当前 fallback 尝试签名，返回是否为新签名。
     * 紧随 [reset] / applyPreparedPlayback 之后调用，顺序不可反。
     */
    fun rememberCurrentFallbackAttempt(countAsFallback: Boolean): Boolean {
        val plan = context.streamFallbackPlan ?: return false
        val routeIndex = context.fallbackRouteIndex
        val cdnIndex = context.fallbackCdnIndex
        val route = plan.routes.getOrNull(routeIndex) ?: return false
        val videoUrl = route.videoUrls.getOrNull(cdnIndex)
            ?: route.videoUrls.firstOrNull()
            ?: return false
        val audioUrl = route.audioUrls
            .takeIf { it.isNotEmpty() }
            ?.getOrElse(cdnIndex) { route.audioUrls.first() }
        val signature = buildFallbackSignature(plan.qualityId, route.codec, videoUrl, audioUrl)
        val added = attemptedFallbackSignatures.add(signature)
        if (added && countAsFallback) {
            fallbackAttemptCount += 1
        }
        return added
    }

    /** 画质降级请求：按候选画质逐档尝试 PlayInfo 拉取，返回首个有可播媒体的结果。 */
    suspend fun requestPlayInfoWithQualityFallback(
        identity: VmPlayRequestIdentity,
        qualityCandidates: List<Int>,
        suppressUiSignals: Boolean
    ): VmPlayInfoFetchResult? {
        val attemptedQualities = linkedSetOf<Int>()
        val requestedQualityId = qualityCandidates.firstOrNull()
            ?: context.requestedQualityId
            ?: context.selectedQualityId
            ?: 80
        var lastResult: VmPlayInfoFetchResult? = null
        var allowWbi = true
        qualityCandidates.forEach { qualityId ->
            if (!attemptedQualities.add(qualityId)) {
                return@forEach
            }
            val response = playInfoGateway.requestPlayInfo(
                aid = identity.aid,
                bvid = identity.bvid,
                cid = identity.cid,
                epId = identity.epId,
                qualityId = qualityId,
                fnval = streamResolver.buildFnval(qualityId),
                fourk = streamResolver.buildFourk(qualityId),
                allowWbi = allowWbi,
                seasonId = context.currentSeasonId ?: 0L
            ) ?: return@forEach
            allowWbi = false
            lastResult = VmPlayInfoFetchResult(
                requestedQualityId = qualityId,
                response = response
            )
            val playInfo = response.data
            if (response.isSuccess && hasPlayableMedia(playInfo)) {
                if (response.isTryLookBypass) {
                    if (!suppressUiSignals) {
                        context.emitRiskControlTryLookBypass()
                    }
                }
                return lastResult
            }
            if (response.code == -351 || response.code == -412 || response.code == -352) {
                return lastResult
            }
            if (response.code == 0 && playInfo != null && !hasPlayableMedia(playInfo)) {
                if (shouldContinueQualityFallback(qualityId, response.message, playInfo)) {
                    return@forEach
                }
                return lastResult
            }
        }
        return lastResult
    }

    /** PlayInfo 是否含 dash 视频或 durl 音视频流。 */
    fun hasPlayableMedia(playInfo: PlayInfoModel?): Boolean {
        if (playInfo == null) {
            return false
        }
        val hasDashVideo = playInfo.dash?.video.orEmpty().isNotEmpty()
        val hasDurl = playInfo.durl.orEmpty().any { it.url.isNotBlank() }
        return hasDashVideo || hasDurl
    }

    /** 是否应该继续尝试更低画质（消息含"画质太高"或声明的最高画质低于请求画质）。 */
    fun shouldContinueQualityFallback(
        requestedQualityId: Int,
        message: String,
        playInfo: PlayInfoModel
    ): Boolean {
        val normalizedMessage = message.lowercase()
        if (
            normalizedMessage.contains("请求的画质太高") ||
            normalizedMessage.contains("画质太高") ||
            normalizedMessage.contains("quality is too high") ||
            normalizedMessage.contains("requested quality is too high")
        ) {
            return true
        }

        val highestDeclaredQuality = buildList {
            addAll(playInfo.acceptQuality.orEmpty())
            addAll(playInfo.supportFormats.orEmpty().map { it.quality })
            add(playInfo.quality)
        }.filter { it > 0 }.maxOrNull()

        return highestDeclaredQuality != null && highestDeclaredQuality < requestedQualityId
    }

    /** 解析最终可播画质：优先 dash 流中的画质，其次 fallback 到声明画质列表。 */
    fun resolvePlayableQualityId(
        requestedQualityId: Int,
        playInfo: PlayInfoModel,
        availableQualities: List<VideoQuality>
    ): Int {
        // 无 DV 解码器时把 126 视为不可选（与 buildQualityList 的设备能力过滤对齐），
        // 否则 UI 已隐藏杜比视界档、实际却仍按流内最高档播 126，列表与实际不一致。
        val dolbyVisionBlocked = requestedQualityId == VideoQuality.QUALITY_DOLBY_VISION.id &&
            !VideoCodecSupport.isDolbyVisionSupported()
        val streamQualityIds = playInfo.dash?.video
            .orEmpty()
            .map { it.id }
            .distinct()
            .let { ids ->
                if (dolbyVisionBlocked) ids.filterNot { it == VideoQuality.QUALITY_DOLBY_VISION.id } else ids
            }
        if (streamQualityIds.isNotEmpty()) {
            if (requestedQualityId in streamQualityIds) {
                return requestedQualityId
            }
            val fallbackQualityId = playInfo.quality
                .takeIf { it in streamQualityIds }
                ?: streamQualityIds.maxOrNull()
                ?: requestedQualityId
            return fallbackQualityId
        }
        if (availableQualities.isEmpty()) {
            return requestedQualityId
        }
        if (availableQualities.any { it.id == requestedQualityId }) {
            return requestedQualityId
        }
        val fallbackQualityId = availableQualities.maxByOrNull { it.id }?.id
            ?: availableQualities.firstOrNull()?.id
            ?: requestedQualityId
        return fallbackQualityId
    }

    /**
     * 使用当前请求上下文重新加载播放 URL（经 [scope] 异步）。
     * 供 onGaiaVgateResult / tryRefreshPlayInfo 等回环路径调用。
     */
    fun loadPlayUrlWithCurrentContext(reason: String): Boolean {
        val identity = context.currentPlayRequestIdentity() ?: return false
        val lockedQualityId = context.requestedQualityId ?: context.selectedQualityId ?: 80
        AppLog.i(
            TAG,
            "fallback:refresh reason=$reason cid=${identity.cid} quality=$lockedQualityId " +
                "position=$lastPlaybackPositionMs"
        )
        scope.launch {
            val preparedPlayback = context.requestPreparedPlayback(
                identity = identity,
                preferLastPlayTime = false,
                replaceInPlace = true,
                playbackPositionMs = lastPlaybackPositionMs,
                playWhenReady = true,
                qualityCandidates = qualityPolicy.buildCandidates(lockedQualityId)
            )
            if (preparedPlayback != null) {
                context.applyPreparedPlayback(
                    preparedPlayback = preparedPlayback,
                    resetFallbackAttempts = false,
                    countCurrentAttemptAsFallback = true
                )
                return@launch
            }
            if (!trySwitchCodec(lastPlaybackPositionMs, reason = "refresh_failed")) {
                context.reportError(appContext.getString(R.string.player_error_playback_failed_retry_later))
            }
        }
        return true
    }

    /** gaia 验证通过后清零 refresh 重试计数并重新加载。 */
    fun onGaiaVgateVerifiedAndRetry() {
        playInfoRefreshRetryCount = 0
        loadPlayUrlWithCurrentContext(reason = "gaia_vgate_verified")
    }

    // ===== 内部 fallback 链 =====

    private fun classifyPlaybackError(error: PlaybackException): PlaybackErrorType {
        val code = error.errorCode
        if (code in 4000..4999) {
            return PlaybackErrorType.DECODER
        }
        if (code in 2000..2999) {
            return PlaybackErrorType.NETWORK
        }
        val message = buildString {
            append(error.message.orEmpty())
            append(' ')
            append(error.cause?.javaClass?.name.orEmpty())
            append(' ')
            append(error.cause?.message.orEmpty())
        }.lowercase()
        return when {
            message.contains("decoder") ||
                message.contains("mediacodec") ||
                message.contains("codec init") ||
                message.contains("no suitable decoder") -> PlaybackErrorType.DECODER
            message.contains("timeout") ||
                message.contains("network") ||
                message.contains("http") ||
                message.contains("source error") ||
                message.contains("connection") -> PlaybackErrorType.NETWORK
            else -> PlaybackErrorType.UNKNOWN
        }
    }

    private fun tryNextCdnInCurrentCodec(positionMs: Long, reason: String): Boolean {
        val dashSession = context.dashSession
        if (dashSession != null && context.useDashPlayback) {
            return tryDispatchDashPlaybackAttempt(
                routeIndex = dashSession.fallbackRouteIndex,
                seekPositionMs = positionMs,
                reason = reason
            )
        }
        val plan = context.streamFallbackPlan ?: return false
        val routeIndex = context.fallbackRouteIndex
        if (routeIndex !in plan.routes.indices) {
            return false
        }
        return tryDispatchPlaybackAttempt(
            routeIndex = routeIndex,
            startCdnIndex = context.fallbackCdnIndex + 1,
            seekPositionMs = positionMs,
            reason = reason
        )
    }

    private fun trySwitchCodec(positionMs: Long, reason: String): Boolean {
        val dashSession = context.dashSession
        if (dashSession != null && context.useDashPlayback) {
            val routePlan = dashSession.routePlan ?: return false
            for (routeIndex in (dashSession.fallbackRouteIndex + 1) until routePlan.routes.size) {
                if (tryDispatchDashPlaybackAttempt(routeIndex, positionMs, reason)) {
                    return true
                }
            }
            return false
        }
        val plan = context.streamFallbackPlan ?: return false
        for (routeIndex in (context.fallbackRouteIndex + 1) until plan.routes.size) {
            if (tryDispatchPlaybackAttempt(routeIndex, 0, positionMs, reason)) {
                return true
            }
        }
        return false
    }

    private fun trySwitchToAvc(
        positionMs: Long,
        reason: String
    ): Boolean {
        val dashSession = context.dashSession
        if (dashSession != null && context.useDashPlayback) {
            val routePlan = dashSession.routePlan ?: return false
            if (dashSession.actualCodec == VideoCodecEnum.AVC) {
                return false
            }
            val routeIndex = routePlan.routes.indexOfFirst { it.codec == VideoCodecEnum.AVC }
            if (routeIndex < 0) {
                return false
            }
            return tryDispatchDashPlaybackAttempt(routeIndex, positionMs, reason)
        }
        val plan = context.streamFallbackPlan ?: return false
        if (context.selectedCodec == VideoCodecEnum.AVC) {
            return false
        }
        val routeIndex = plan.routes.indexOfFirst { it.codec == VideoCodecEnum.AVC }
        if (routeIndex < 0) {
            return false
        }
        return tryDispatchPlaybackAttempt(routeIndex, 0, positionMs, reason)
    }

    private fun tryRefreshPlayInfo(reason: String): Boolean {
        if (playInfoRefreshRetryCount >= MAX_PLAYINFO_REFRESH_RETRY) {
            return false
        }
        playInfoRefreshRetryCount += 1
        return loadPlayUrlWithCurrentContext(reason = reason)
    }

    private fun tryDispatchPlaybackAttempt(
        routeIndex: Int,
        startCdnIndex: Int,
        seekPositionMs: Long,
        reason: String
    ): Boolean {
        val plan = context.streamFallbackPlan ?: return false
        if (routeIndex !in plan.routes.indices) {
            return false
        }
        if (fallbackAttemptCount >= MAX_FALLBACK_ATTEMPTS) {
            return false
        }
        val route = plan.routes[routeIndex]
        if (route.videoUrls.isEmpty()) {
            return false
        }
        val audioVariantCount = route.audioUrls.size.takeIf { it > 0 } ?: 1
        val totalVariants = maxOf(route.videoUrls.size, audioVariantCount)
        for (cdnIndex in startCdnIndex until totalVariants) {
            val videoUrl = route.videoUrls.getOrElse(cdnIndex) { route.videoUrls.first() }
            val audioUrl = route.audioUrls
                .takeIf { it.isNotEmpty() }
                ?.getOrElse(cdnIndex) { route.audioUrls.first() }
            val signature = buildFallbackSignature(plan.qualityId, route.codec, videoUrl, audioUrl)
            if (!attemptedFallbackSignatures.add(signature)) {
                continue
            }
            fallbackAttemptCount += 1
            AppLog.i(
                TAG,
                "fallback:progressive reason=$reason route=$routeIndex cdn=$cdnIndex codec=${route.codec}"
            )
            // 原子写：写共享状态（routeIndex/cdnIndex/selectedCodec）+ 发 PlaybackRequest。
            context.onStreamFallbackPlanUpdated(
                plan = plan,
                routeIndex = routeIndex,
                cdnIndex = cdnIndex
            )
            context.setSelectedCodec(route.codec)
            val selection = streamResolver.buildMediaSourceForRoute(
                route = route,
                videoUrl = videoUrl,
                audioUrl = audioUrl,
                availableCodecs = plan.routes.map { it.codec },
                selectedCodec = route.codec
            )
            context.onCdnStatesUpdated(selection.cdnFailoverStates)
            context.clearError()
            context.emitPlaybackRequest(VmPlaybackRequest(
                mediaSource = selection.mediaSource,
                aid = context.currentAid,
                bvid = context.currentBvid,
                cid = context.currentCid,
                seekPositionMs = seekPositionMs,
                playWhenReady = true,
                replaceInPlace = true,
                durationMs = plan.durationMs,
                playbackIntentId = context.activePlaybackIntentId,
                startupTraceId = context.startupTraceId,
                startupTraceStartElapsedMs = context.startupTraceStartElapsedMs
            ))
            return true
        }
        return false
    }

    private fun tryDispatchDashPlaybackAttempt(
        routeIndex: Int,
        seekPositionMs: Long,
        reason: String
    ): Boolean {
        val session = context.dashSession ?: return false
        val routePlan = session.routePlan ?: return false
        if (routeIndex !in routePlan.routes.indices) {
            return false
        }
        if (fallbackAttemptCount >= MAX_FALLBACK_ATTEMPTS) {
            return false
        }
        val route = routePlan.routes[routeIndex]
        val signature = "dash|${routePlan.qualityId}|${route.codec.name}|${route.videoRepresentation.baseUrl}"
        if (!attemptedFallbackSignatures.add(signature)) {
            return false
        }
        fallbackAttemptCount += 1
        try {
            AppLog.i(TAG, "fallback:dash reason=$reason route=$routeIndex codec=${route.codec}")
            val sourceWithState = dashMediaSourceFactory.createMediaSourceWithCdnState(route)
            val mediaSource = sourceWithState.mediaSource
            // 原子写：写 currentDashSession（经回调）+ 写 selectedCodec + 写 cdnStates + 发 PlaybackRequest。
            context.setSelectedCodec(route.codec)
            context.onCdnStatesUpdated(sourceWithState.cdnFailoverStates)
            context.onDashSessionUpdated(session.copy(
                currentRoute = route,
                actualCodec = route.codec,
                fallbackRouteIndex = routeIndex,
                fallbackAttemptCount = fallbackAttemptCount
            ))
            context.clearError()
            context.emitPlaybackRequest(VmPlaybackRequest(
                mediaSource = mediaSource,
                aid = context.currentAid,
                bvid = context.currentBvid,
                cid = context.currentCid,
                seekPositionMs = seekPositionMs,
                playWhenReady = true,
                replaceInPlace = true,
                durationMs = route.durationMs,
                playbackIntentId = context.activePlaybackIntentId,
                startupTraceId = context.startupTraceId,
                startupTraceStartElapsedMs = context.startupTraceStartElapsedMs
            ))
            return true
        } catch (e: Exception) {
            AppLog.e(TAG, "fallback:codec: dash failed route=$routeIndex error=${e.message}", e)
            return false
        }
    }

    private fun buildFallbackSignature(
        qualityId: Int,
        codec: VideoCodecEnum,
        videoUrl: String,
        audioUrl: String?
    ): String {
        return "$qualityId|${codec.name}|$videoUrl|${audioUrl.orEmpty()}"
    }
}
