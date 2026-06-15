package com.tutu.myblbl.feature.player.view

import android.content.Context
import android.graphics.Color
import android.os.SystemClock
import androidx.media3.common.Player
import com.kuaishou.akdanmaku.DanmakuConfig
import com.kuaishou.akdanmaku.data.DanmakuItemData
import com.kuaishou.akdanmaku.data.DanmakuVipGradientStyle
import com.kuaishou.akdanmaku.filter.DanmakuDataFilter
import com.kuaishou.akdanmaku.filter.TypeFilter
import com.kuaishou.akdanmaku.render.SimpleRenderer
import com.kuaishou.akdanmaku.ui.DanmakuPlayer
import com.kuaishou.akdanmaku.ui.DanmakuView
import com.tutu.myblbl.feature.player.DanmakuFilterContext
import com.tutu.myblbl.feature.player.PlaybackStartupTrace
import com.tutu.myblbl.core.common.log.AppLog
import com.tutu.myblbl.model.dm.DmModel
import com.tutu.myblbl.core.common.ext.isVipColorfulDanmakuAllowed as isVipColorfulDanmakuSettingAllowed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max

private typealias RawWindowRange = DanmakuWindowRangePolicy.Range

/**
 * Owns danmaku-specific state so MyPlayerView only coordinates player UI and gestures.
 */
class MyPlayerDanmakuController(
    private val context: Context,
    private val danmakuViewProvider: () -> DanmakuView?
) {

    companion object {
        private const val TAG = "DanmakuCtrl"
        private const val MERGE_DUPLICATE_WINDOW_MS = 2_000
        private const val MERGE_DUPLICATE_MIN_COUNT = 2
        private const val MAX_SYNC_DRIFT_MS = 1200L
        private const val DRIFT_SYNC_INTERVAL_NORMAL_MS = 3200L
        private const val DRIFT_SYNC_INTERVAL_HIGH_MS = 900L
        private const val DRIFT_SYNC_INTERVAL_MEDIUM_MS = 1200L
        private const val DRIFT_SYNC_INTERVAL_SLOW_MS = 3000L
        // drift sync 三段阈值：
        //  - drift <= NEUTRAL：完全忽略，弹幕引擎使用用户设定的播放速度。
        //  - drift <= SOFT_SYNC：通过 timer factor 微调（±5%）软同步，避免清空 retainer。
        //  - drift  > HARD_SEEK_NORMAL/HIGH：硬 seek，触发引擎重新布局。
        private const val DRIFT_NEUTRAL_TOLERANCE_MS = 250L
        private const val DRIFT_SOFT_SYNC_LIMIT_MS = 900L
        private const val DRIFT_TOLERANCE_HIGH_SPEED_MS = 800L
        private const val DRIFT_TOLERANCE_NORMAL_MS = 2000L
        private const val DRIFT_SOFT_CORRECTION = 0.05f
        private const val COLORFUL_VIP_GRADIENT = 0xEA61
        private const val SEEK_DEDUP_WINDOW_MS = 300L
        private const val SEEK_DEDUP_POSITION_TOLERANCE_MS = 80L
        private const val SMART_FILTER_LEVEL_OFF = 0
        private const val SMART_FILTER_LEVEL_MAX = 10
        private const val LIVE_THROTTLE_WINDOW_MS = 100L
        private const val LIVE_THROTTLE_MAX_ITEMS = 30
        private const val LIVE_MERGE_BUFFER_MS = 800L
        private const val LIVE_DENSITY_TRACK_MS = 5000L
        private const val INITIAL_WINDOW_BEHIND_MS = 6_000L
        private const val INITIAL_WINDOW_AHEAD_MS = 16_000L
        private const val INITIAL_WINDOW_MAX_ITEMS = 144
        private const val INITIAL_IMMEDIATE_ITEMS = 8
        private const val ACTIVE_WINDOW_BEHIND_MS = 6_000L
        private const val ACTIVE_WINDOW_AHEAD_MS = 16_000L
        private const val ACTIVE_WINDOW_APPEND_BATCH_SIZE = 96
        private const val ACTIVE_WINDOW_FULL_SUBMIT_MAX_ITEMS = 180
        private const val ACTIVE_WINDOW_MIN_WARM_ITEMS = 144
        private const val WINDOW_REFRESH_AHEAD_THRESHOLD_MS = 3_000L
        private const val WINDOW_REFRESH_MIN_PROGRESS_MS = 6_000L
        private const val WINDOW_REFRESH_MIN_INTERVAL_MS = 1_000L
        private const val STARTUP_DATA_DEFER_MS = 1_200L
        private const val SMART_FILTER_PROFILE_LOG_MS = 2L
        private const val DANMAKU_PRE_CACHE_TIME_MS = 900L
    }

    data class SettingsSnapshot(
        val enabled: Boolean,
        val showAdvancedDanmaku: Boolean,
        val alpha: Float,
        val textSize: Int,
        val speed: Int,
        val screenArea: Int,
        val allowTop: Boolean,
        val allowBottom: Boolean,
        val smartFilterLevel: Int,
        val mergeDuplicate: Boolean
    )

    private data class PreparedWindow(
        val data: List<DanmakuItemData>,
        val rawCount: Int,
        val range: RawWindowRange,
        val positionMs: Long,
        val coveredUntilMs: Long
    )

    private data class DanmakuTimeline(
        val data: List<DmModel>,
        val signature: Long
    ) {
        val count: Int
            get() = data.size

        companion object {
            val EMPTY = DanmakuTimeline(emptyList(), 0L)
        }
    }

    private var danmakuPlayer: DanmakuPlayer? = null
    private var danmakuConfig = DanmakuConfig(
        preCacheTimeMs = DANMAKU_PRE_CACHE_TIME_MS,
        dataFilter = listOf(TypeFilter())
    )
    private var danmakuTimeline: DanmakuTimeline = DanmakuTimeline.EMPTY
    private var filterContext: DanmakuFilterContext = DanmakuFilterContext.EMPTY
    private var danmakuData: List<DanmakuItemData> = emptyList()
    private var rawDanmakuData: List<DmModel> = emptyList()
    private var activeWindowStartMs: Long = Long.MIN_VALUE
    private var activeWindowEndMs: Long = Long.MIN_VALUE
    private var activeWindowCoveredUntilMs: Long = Long.MIN_VALUE
    private var activeWindowRawCount: Int = 0
    private var activeWindowAnchorMs: Long = Long.MIN_VALUE
    private var activeWindowSubmittedStartIndex: Int = -1
    private var activeWindowSubmittedEndIndex: Int = -1
    private var activeWindowNaturalEndIndex: Int = -1
    private var danmakuPositionMs: Long = 0L
    private var isDanmakuStarted = false
    private var isDanmakuPaused = false
    private var liveEngineStarted = false
    private var liveThrottleWindowStart = 0L
    private var liveThrottleCount = 0
    private val liveMergeBuffer = mutableMapOf<MergeDuplicateKey, LiveMergeEntry>()
    private val liveSentTimestamps = ArrayDeque<Long>()
    private var liveFlushJob: Job? = null
    private var liveDanmakuIdCounter = 0L
    private var mergeDuplicate = true
    private var screenPart = 1.0f
    private var smartFilterLevel = SMART_FILTER_LEVEL_OFF
    private var lastSettingsSnapshot: SettingsSnapshot? = null
    private var rawDanmakuSignature: Long = 0L
    private var rawDanmakuCount: Int = 0
    private var preparedDanmakuSignature: Long = 0L
    private var preparedDanmakuCount: Int = 0
    private var lastSeekPositionMs: Long = Long.MIN_VALUE
    private var lastSeekRealtimeMs: Long = 0L
    private val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var prepareJob: Job? = null
    private var preloadTextureJob: Job? = null
    private var windowRefreshJob: Job? = null
    private var batchUpdateJob: Job? = null
    private var startupDataApplyJob: Job? = null
    private var driftSyncJob: Job? = null
    private var prepareGeneration: Long = 0L
    private var currentPlaybackSpeed: Float = 1f
    private var appliedTimerFactor: Float = 1f
    private var wasBufferingWhilePlaying: Boolean = false
    private var lastResumeRealtimeMs: Long = 0L
    private var lastFirstFrameRealtimeMs: Long = 0L
    private var lastWindowApplyRealtimeMs: Long = 0L

    var playerPositionProvider: (() -> Long)? = null

    fun setData(
        data: List<DmModel>,
        filterContext: DanmakuFilterContext = DanmakuFilterContext.EMPTY,
        startupTraceId: String = PlaybackStartupTrace.NO_TRACE,
        startupTraceStartElapsedMs: Long = 0L
    ) {
        prepareJob?.cancel()
        windowRefreshJob?.cancel()
        this.filterContext = filterContext
        val generation = ++prepareGeneration
        prepareJob = controllerScope.launch {
            val sortedData = data.sortedBy { it.progress }
            val rawSignature = sortedData.fastRawSignature()
            val timeline = DanmakuTimeline(sortedData, rawSignature)
            val allowVipColorful = isVipColorfulDanmakuAllowed()
            val positionSnapshotMs = withContext(Dispatchers.Main.immediate) {
                syncSnapshotPosition()
                danmakuPositionMs
            }
            val preparedWindow = sortedData.buildPreparedWindow(
                positionMs = positionSnapshotMs,
                behindMs = INITIAL_WINDOW_BEHIND_MS,
                aheadMs = INITIAL_WINDOW_AHEAD_MS,
                maxItems = INITIAL_WINDOW_MAX_ITEMS,
                allowVipColorful = allowVipColorful,
                stage = "initial_window"
            )
            val preparedSignature = preparedWindow.data.fastPreparedSignature()
            scheduleVipTexturePreload(
                styles = preparedWindow.data.map { it.vipGradientStyle }.filter { it.hasTexture },
                generation = generation
            )
            withContext(Dispatchers.Main.immediate) {
                if (prepareGeneration != generation) {
                    return@withContext
                }
                danmakuTimeline = timeline
                rawDanmakuData = timeline.data
                rawDanmakuSignature = rawSignature
                rawDanmakuCount = timeline.count
                applyPreparedWindowState(preparedWindow, preparedSignature)
                preparedDanmakuSignature = preparedSignature
                PlaybackStartupTrace.log(
                    traceId = startupTraceId,
                    startElapsedMs = startupTraceStartElapsedMs,
                    step = "danmaku_initial_window_ready",
                    message = "initial=${preparedWindow.data.size} rawInitial=${preparedWindow.rawCount} " +
                        "raw=${timeline.count} position=$positionSnapshotMs " +
                        "window=[${preparedWindow.range.windowStartMs},${preparedWindow.range.windowEndMs}] " +
                        "coveredUntil=${preparedWindow.coveredUntilMs} capped=${preparedWindow.range.isCapped}"
                )
                val existingPlayer = danmakuPlayer
                if (existingPlayer != null) {
                    applyPreparedDataToPlayer(
                        player = existingPlayer,
                        generation = generation,
                        deferDuringStartup = false,
                        startupTraceId = startupTraceId,
                        startupTraceStartElapsedMs = startupTraceStartElapsedMs,
                        appliedCount = preparedWindow.data.size,
                        rawCount = preparedWindow.rawCount
                    )
                } else {
                    initPlayer(
                        startupTraceId = startupTraceId,
                        startupTraceStartElapsedMs = startupTraceStartElapsedMs,
                        appliedCount = preparedWindow.data.size,
                        rawCount = preparedWindow.rawCount,
                        deferInitialData = false
                    )
                }
            }
        }
    }

    fun appendData(
        data: List<DmModel>,
        filterContext: DanmakuFilterContext = DanmakuFilterContext.EMPTY
    ) {
        if (data.isEmpty()) {
            return
        }
        this.filterContext = filterContext
        val previousJob = prepareJob
        val generation = ++prepareGeneration
        prepareJob = controllerScope.launch {
            previousJob?.join()
            // 主线程只取 timeline 快照（引用拷贝 O(1)）；合并、排序、签名全部在 Default 后台完成，
            // 避免主线程对上万条数据做全量拼接与排序（P0 主线程阻塞，rawTotal 最高 25580）。
            val existingData = withContext(Dispatchers.Main.immediate) {
                danmakuTimeline.data
            }
            val mergedRawData = mergeSortedTimelines(existingData, data)
            val rawSignature = mergedRawData.fastRawSignature()
            val timeline = DanmakuTimeline(mergedRawData, rawSignature)
            val positionSnapshotMs = withContext(Dispatchers.Main.immediate) {
                syncSnapshotPosition()
                danmakuPositionMs
            }
            withContext(Dispatchers.Main.immediate) {
                if (prepareGeneration != generation) {
                    return@withContext
                }
                danmakuTimeline = timeline
                rawDanmakuData = timeline.data
                rawDanmakuSignature = rawSignature
                rawDanmakuCount = timeline.count
                scheduleActiveWindowRefresh(
                    positionMs = positionSnapshotMs,
                    force = false,
                    reason = "append",
                    ignoreFreshWindow = true
                )
            }
        }
    }

    /**
     * 直播模式：立即启动弹幕引擎（不等待数据），然后用引擎当前时间作为 position 注入弹幕
     */
    fun startLive() {
        AppLog.d(TAG, "startLive: player=${danmakuPlayer != null} started=$isDanmakuStarted")
        isDanmakuStarted = true
        isDanmakuPaused = false
        ensurePlayer()
        danmakuPlayer?.setLiveMode(true)
        danmakuPlayer?.start(danmakuConfig)
        AppLog.d(TAG, "startLive: after start player=${danmakuPlayer != null}")
    }

    fun addLiveDanmaku(dm: DmModel) {
        if (!isDanmakuStarted || danmakuPlayer == null) {
            startLive()
        }
        val player = danmakuPlayer
        if (player == null) {
            AppLog.w(TAG, "addLiveDanmaku: player is null!")
            return
        }
        val now = SystemClock.uptimeMillis()
        if (now - liveThrottleWindowStart >= LIVE_THROTTLE_WINDOW_MS) {
            liveThrottleWindowStart = now
            liveThrottleCount = 0
        }
        if (liveThrottleCount >= LIVE_THROTTLE_MAX_ITEMS) {
            return
        }
        liveThrottleCount++
        if (!liveEngineStarted) {
            player.start(danmakuConfig)
            liveEngineStarted = true
        }

        flushExpiredLiveEntries()

        val key = MergeDuplicateKey(
            content = dm.content.trim().lowercase(),
            mode = dm.mode,
            color = dm.color,
            colorful = dm.colorful,
            colorfulSrc = dm.colorfulSrc.trim()
        )
        val existing = liveMergeBuffer[key]
        if (existing != null && now - existing.createdAt <= LIVE_MERGE_BUFFER_MS) {
            existing.count++
        } else {
            liveMergeBuffer[key] = LiveMergeEntry(
                firstItem = dm,
                count = 1,
                createdAt = now
            )
        }
        scheduleLiveFlush()
    }

    private fun flushExpiredLiveEntries() {
        val now = SystemClock.uptimeMillis()
        pruneLiveSentTimestamps(now)

        val effectiveThreshold = estimateLiveMergeCapacity()
        val bufferTotal = liveMergeBuffer.values.sumOf { it.count }

        val expiredKeys = mutableListOf<MergeDuplicateKey>()
        for ((key, entry) in liveMergeBuffer) {
            if (now - entry.createdAt < LIVE_MERGE_BUFFER_MS) continue
            expiredKeys.add(key)
            val N = entry.count
            val other = liveSentTimestamps.size + bufferTotal - N
            val budget = effectiveThreshold - other
            sendMergedLiveItems(entry.firstItem, N, budget)
        }
        expiredKeys.forEach { liveMergeBuffer.remove(it) }
    }

    private fun flushAllLiveEntries() {
        val now = SystemClock.uptimeMillis()
        pruneLiveSentTimestamps(now)

        val effectiveThreshold = estimateLiveMergeCapacity()
        val bufferTotal = liveMergeBuffer.values.sumOf { it.count }

        for ((_, entry) in liveMergeBuffer) {
            val N = entry.count
            val other = liveSentTimestamps.size + bufferTotal - N
            val budget = effectiveThreshold - other
            sendMergedLiveItems(entry.firstItem, N, budget)
        }
        liveMergeBuffer.clear()
    }

    private fun sendMergedLiveItems(firstItem: DmModel, N: Int, budget: Int) {
        val player = danmakuPlayer ?: return
        val now = SystemClock.uptimeMillis()
        when {
            N <= 1 || N <= budget -> {
                repeat(N) {
                    doSendLiveDanmaku(firstItem, player)
                    liveSentTimestamps.add(now)
                }
            }
            budget >= 1 -> {
                repeat(budget - 1) {
                    doSendLiveDanmaku(firstItem, player)
                    liveSentTimestamps.add(now)
                }
                val merged = firstItem.copy(
                    content = "${firstItem.content} ×${N - budget + 1}",
                    fontSize = max(firstItem.fontSize, 12) + 2
                )
                doSendLiveDanmaku(merged, player)
                liveSentTimestamps.add(now)
            }
            else -> {
                val merged = firstItem.copy(
                    content = "${firstItem.content} ×$N",
                    fontSize = max(firstItem.fontSize, 12) + 2
                )
                doSendLiveDanmaku(merged, player)
                liveSentTimestamps.add(now)
            }
        }
    }

    private fun estimateLiveMergeCapacity(): Int {
        val metrics = context.resources.displayMetrics
        val visibleHeight = metrics.heightPixels * screenPart
        val trackHeight = 36f * danmakuConfig.textSizeScale
        val tracks = (visibleHeight / trackHeight.coerceAtLeast(36f)).toInt().coerceAtLeast(3)
        return (tracks * 2).coerceIn(6, 160)
    }

    private fun doSendLiveDanmaku(dm: DmModel, player: DanmakuPlayer) {
        val currentTime = player.getCurrentTimeMs()
        val color = dm.color.toDanmakuColor()
        val data = DanmakuItemData(
            danmakuId = ++liveDanmakuIdCounter,
            position = currentTime.coerceAtLeast(0L),
            content = dm.content,
            mode = DanmakuItemData.DANMAKU_MODE_ROLLING,
            textSize = dm.fontSize.coerceAtLeast(12),
            textColor = color,
            score = 0,
            renderFlags = DanmakuItemData.RENDER_FLAG_NONE,
            vipGradientStyle = DanmakuVipGradientStyle.NONE
        )
        player.send(data)
    }

    private fun scheduleLiveFlush() {
        if (liveMergeBuffer.isEmpty()) return
        liveFlushJob?.cancel()
        liveFlushJob = controllerScope.launch(Dispatchers.Main) {
            delay(LIVE_MERGE_BUFFER_MS)
            flushExpiredLiveEntries()
        }
    }

    private fun pruneLiveSentTimestamps(now: Long) {
        while (liveSentTimestamps.isNotEmpty() && now - liveSentTimestamps.first() > LIVE_DENSITY_TRACK_MS) {
            liveSentTimestamps.removeFirst()
        }
    }

    /**
     * Applies the full setting snapshot in one place so partial UI callbacks do not leave
     * danmaku config in an inconsistent intermediate state.
     */
    fun applySettings(snapshot: SettingsSnapshot) {
        if (lastSettingsSnapshot == snapshot) {
            return
        }
        lastSettingsSnapshot = snapshot
        val normalizedSmartFilterLevel = snapshot.smartFilterLevel.normalizeSmartFilterLevel()
        val durationMs = snapshot.speed.toDanmakuDurationMs()
        val fontBorder = filterContext.playerConfig.fontBorder.toDanmakuFontBorder()
        val newConfig = danmakuConfig.copy(
            visibility = snapshot.enabled,
            preCacheTimeMs = DANMAKU_PRE_CACHE_TIME_MS,
            alpha = snapshot.alpha.coerceIn(0.1f, 1f),
            textSizeScale = snapshot.textSize.toDanmakuTextScale(),
            durationMs = durationMs,
            rollingDurationMs = durationMs,
            screenPart = snapshot.screenArea.toDanmakuScreenPart(),
            fontBorder = fontBorder
        )
        val filterChanged = applyTypeFilterState(
            config = newConfig,
            type = DanmakuItemData.DANMAKU_MODE_CENTER_TOP,
            visible = snapshot.allowTop
        ) or applyTypeFilterState(
            config = newConfig,
            type = DanmakuItemData.DANMAKU_MODE_CENTER_BOTTOM,
            visible = snapshot.allowBottom
        )
        if (filterChanged) {
            newConfig.updateFilter()
        }
        updateConfig(newConfig)
        updatePreparationOptions(
            mergeDuplicateEnabled = snapshot.mergeDuplicate,
            smartFilterLevel = normalizedSmartFilterLevel,
            screenPartValue = snapshot.screenArea.toDanmakuScreenPart()
        )
    }

    fun updatePlaybackSpeed(speed: Float) {
        val resolved = speed.coerceAtLeast(0.1f)
        currentPlaybackSpeed = resolved
        appliedTimerFactor = resolved
        danmakuPlayer?.updatePlaySpeed(resolved)
    }

    fun notifyPlaybackStateChanged(playbackState: Int, playWhenReady: Boolean) {
        when (playbackState) {
            Player.STATE_BUFFERING -> {
                val player = danmakuPlayer
                if (playWhenReady && isDanmakuStarted && !isDanmakuPaused && player != null) {
                    wasBufferingWhilePlaying = true
                    player.pause()
                }
            }
            Player.STATE_READY -> {
                if (wasBufferingWhilePlaying && playWhenReady) {
                    resumeAfterBuffering()
                } else if (!wasBufferingWhilePlaying) {
                    wasBufferingWhilePlaying = false
                }
            }
            Player.STATE_ENDED -> {
                wasBufferingWhilePlaying = false
            }
        }
    }

    fun notifyIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying && wasBufferingWhilePlaying) {
            resumeAfterBuffering()
        }
    }

    private fun resumeAfterBuffering() {
        wasBufferingWhilePlaying = false
        val player = danmakuPlayer ?: return
        if (!isDanmakuStarted || isDanmakuPaused) return
        val videoPos = playerPositionProvider?.invoke()?.coerceAtLeast(0L)
        if (videoPos != null) {
            danmakuPositionMs = videoPos
            val enginePos = player.getCurrentTimeMs()
            AppLog.d(
                TAG,
                "danmaku buffering resume: videoPos=$videoPos enginePos=$enginePos " +
                    "drift=${enginePos - videoPos} hardSeek=false"
            )
        }
        ensureTimerFactor(player, currentPlaybackSpeed)
        player.start(danmakuConfig)
        startDriftSync()
    }

    fun setEnabled(enabled: Boolean) {
        lastSettingsSnapshot = lastSettingsSnapshot?.copy(enabled = enabled)
        updateVisibility(enabled)
        val danmakuView = danmakuViewProvider()
        if (enabled) {
            danmakuView?.visibility = android.view.View.VISIBLE
            resume()
        } else {
            danmakuView?.visibility = android.view.View.INVISIBLE
            pause()
        }
    }

    fun pause() {
        if (isDanmakuPaused) {
            return
        }
        isDanmakuPaused = true
        stopDriftSync()
        danmakuPositionMs = danmakuPlayer?.getCurrentTimeMs() ?: danmakuPositionMs
        danmakuPlayer?.pause()
    }

    fun resume() {
        if (isDanmakuStarted && !isDanmakuPaused) {
            danmakuPlayer?.let { startPreparedPlayerIfNeeded(it) }
            return
        }
        isDanmakuStarted = true
        isDanmakuPaused = false
        lastResumeRealtimeMs = SystemClock.elapsedRealtime()
        if (!hasPreparedData() && !liveEngineStarted) {
            return
        }
        ensurePlayer()
        danmakuPlayer?.start(danmakuConfig)
        val provider = playerPositionProvider
        if (provider != null) {
            val videoPos = provider().coerceAtLeast(0L)
            scheduleActiveWindowRefresh(
                positionMs = videoPos,
                force = false,
                reason = "resume"
            )
            danmakuPlayer?.let { player ->
                // 上一段播放过程中如果 drift sync 留了软同步因子，恢复回用户设定的速度。
                ensureTimerFactor(player, currentPlaybackSpeed)
                val enginePos = player.getCurrentTimeMs()
                if (abs(enginePos - videoPos) > MAX_SYNC_DRIFT_MS) {
                    seekPlayerTo(
                        player = player,
                        targetPositionMs = videoPos,
                        currentTimeMs = enginePos,
                        forceSeek = true,
                        reason = "resume_sync"
                    )
                }
            }
        }
        startDriftSync()
    }

    fun resetForPlaybackStart(positionMs: Long) {
        prepareJob?.cancel()
        preloadTextureJob?.cancel()
        prepareGeneration++
        stop()
        danmakuTimeline = DanmakuTimeline.EMPTY
        rawDanmakuData = emptyList()
        rawDanmakuSignature = 0L
        rawDanmakuCount = 0
        preparedDanmakuSignature = 0L
        preparedDanmakuCount = 0
        danmakuPositionMs = positionMs.coerceAtLeast(0L)
        if (danmakuPositionMs > 0L) {
            danmakuPlayer?.seekTo(danmakuPositionMs)
        }
    }

    fun stop() {
        isDanmakuStarted = false
        isDanmakuPaused = false
        liveEngineStarted = false
        liveThrottleCount = 0
        liveFlushJob?.cancel()
        windowRefreshJob?.cancel()
        batchUpdateJob?.cancel()
        startupDataApplyJob?.cancel()
        stopDriftSync()
        liveMergeBuffer.clear()
        liveSentTimestamps.clear()
        danmakuPositionMs = 0L
        clearActiveWindowState()
        lastFirstFrameRealtimeMs = 0L
        danmakuPlayer?.setLiveMode(false)
        danmakuPlayer?.stop()
    }

    fun syncPosition(positionMs: Long, forceSeek: Boolean = false) {
        val safePosition = positionMs.coerceAtLeast(0L)
        danmakuPositionMs = safePosition
        val player = danmakuPlayer ?: return
        if (!isDanmakuStarted) {
            return
        }
        val currentTime = player.getCurrentTimeMs()
        if (!forceSeek) {
            scheduleActiveWindowRefresh(
                positionMs = safePosition,
                force = false,
                reason = "playback_tick"
            )
        }
        if (!forceSeek && safePosition < currentTime) {
            return
        }
        if (forceSeek) {
            seekPlayerTo(
                player = player,
                targetPositionMs = safePosition,
                currentTimeMs = currentTime,
                forceSeek = true,
                reason = "sync"
            )
        }
    }

    fun release() {
        prepareJob?.cancel()
        preloadTextureJob?.cancel()
        windowRefreshJob?.cancel()
        batchUpdateJob?.cancel()
        startupDataApplyJob?.cancel()
        driftSyncJob?.cancel()
        liveFlushJob?.cancel()
        liveMergeBuffer.clear()
        liveSentTimestamps.clear()
        controllerScope.cancel()
        releasePlayer()
    }

    private fun rebuildAndApplyData() {
        prepareJob?.cancel()
        val generation = ++prepareGeneration
        val capturedGeneration = generation
        prepareJob = controllerScope.launch {
            delay(200L)
            if (prepareGeneration != capturedGeneration) return@launch
            val allowVipColorful = isVipColorfulDanmakuAllowed()
            val timeline = withContext(Dispatchers.Main.immediate) {
                danmakuTimeline
            }
            val positionSnapshotMs = withContext(Dispatchers.Main.immediate) {
                syncSnapshotPosition()
                danmakuPositionMs
            }
            val preparedWindow = timeline.data.buildPreparedWindow(
                positionMs = positionSnapshotMs,
                behindMs = ACTIVE_WINDOW_BEHIND_MS,
                aheadMs = ACTIVE_WINDOW_AHEAD_MS,
                maxItems = ACTIVE_WINDOW_APPEND_BATCH_SIZE,
                allowVipColorful = allowVipColorful,
                stage = "settings_window"
            )
            val rebuildTextureStyles = ArrayList<DanmakuVipGradientStyle>(preparedWindow.data.size)
            for (item in preparedWindow.data) {
                if (item.vipGradientStyle.hasTexture) {
                    rebuildTextureStyles.add(item.vipGradientStyle)
                }
            }
            scheduleVipTexturePreload(
                styles = rebuildTextureStyles,
                generation = generation
            )
            withContext(Dispatchers.Main.immediate) {
                if (prepareGeneration != generation) {
                    return@withContext
                }
                val signature = preparedWindow.data.fastPreparedSignature()
                if (preparedDanmakuCount == preparedWindow.data.size &&
                    preparedDanmakuSignature == signature &&
                    danmakuPlayer != null
                ) {
                    return@withContext
                }
                applyPreparedWindowState(preparedWindow, signature)
                val existingPlayer = danmakuPlayer
                if (existingPlayer != null) {
                    applyPreparedDataToPlayer(existingPlayer, generation, deferDuringStartup = false)
                } else {
                    initPlayer()
                }
            }
        }
    }

    fun notifyPlaybackFirstFrame() {
        lastFirstFrameRealtimeMs = SystemClock.elapsedRealtime()
    }

    private fun applyPreparedDataToPlayer(
        player: DanmakuPlayer,
        generation: Long,
        deferDuringStartup: Boolean,
        startupTraceId: String = PlaybackStartupTrace.NO_TRACE,
        startupTraceStartElapsedMs: Long = 0L,
        appliedCount: Int = -1,
        rawCount: Int = -1
    ) {
        startupDataApplyJob?.cancel()
        val delayMs = if (deferDuringStartup) resolveStartupDataDelayMs() else 0L
        if (delayMs <= 0L) {
            applyPreparedDataToPlayerNow(
                player = player,
                generation = generation,
                startupTraceId = startupTraceId,
                startupTraceStartElapsedMs = startupTraceStartElapsedMs,
                appliedCount = appliedCount,
                rawCount = rawCount
            )
            return
        }
        PlaybackStartupTrace.log(
            traceId = startupTraceId,
            startElapsedMs = startupTraceStartElapsedMs,
            step = "danmaku_player_data_deferred",
            message = "delayMs=$delayMs count=$appliedCount raw=$rawCount"
        )
        startupDataApplyJob = controllerScope.launch {
            delay(delayMs)
            withContext(Dispatchers.Main.immediate) {
                if (prepareGeneration != generation || danmakuPlayer !== player) {
                    return@withContext
                }
                applyPreparedDataToPlayerNow(
                    player = player,
                    generation = generation,
                    startupTraceId = startupTraceId,
                    startupTraceStartElapsedMs = startupTraceStartElapsedMs,
                    appliedCount = appliedCount,
                    rawCount = rawCount
                )
            }
        }
    }

    private fun applyPreparedDataToPlayerNow(
        player: DanmakuPlayer,
        generation: Long,
        startupTraceId: String = PlaybackStartupTrace.NO_TRACE,
        startupTraceStartElapsedMs: Long = 0L,
        appliedCount: Int = -1,
        rawCount: Int = -1
    ) {
        if (prepareGeneration != generation) return
        replacePlayerWindowData(
            player = player,
            data = danmakuData,
            reason = "apply",
            generation = generation
        )
        if (danmakuPositionMs > 0L) {
            seekPlayerTo(
                player = player,
                targetPositionMs = danmakuPositionMs,
                currentTimeMs = player.getCurrentTimeMs(),
                forceSeek = true,
                reason = "replace",
                bypassDedup = true
            )
        }
        PlaybackStartupTrace.log(
            traceId = startupTraceId,
            startElapsedMs = startupTraceStartElapsedMs,
            step = "danmaku_player_data_applied",
            message = "count=$appliedCount raw=$rawCount"
        )
        startPreparedPlayerIfNeeded(player)
    }

    private fun resolveStartupDataDelayMs(): Long {
        if (!isDanmakuStarted || isDanmakuPaused) {
            return 0L
        }
        val now = SystemClock.elapsedRealtime()
        val resumeDelayMs = if (lastResumeRealtimeMs > 0L) {
            STARTUP_DATA_DEFER_MS - (now - lastResumeRealtimeMs)
        } else {
            0L
        }
        return resumeDelayMs.coerceAtLeast(0L)
    }

    private fun ensurePlayer() {
        if (danmakuPlayer != null) return
        initPlayer()
    }

    private fun initPlayer(
        startupTraceId: String = PlaybackStartupTrace.NO_TRACE,
        startupTraceStartElapsedMs: Long = 0L,
        appliedCount: Int = -1,
        rawCount: Int = -1,
        deferInitialData: Boolean = true
    ) {
        val danmakuView = danmakuViewProvider() ?: return
        danmakuView.isClickable = false
        danmakuView.isFocusable = false
        syncSnapshotPosition()
        releasePlayer()

        // 根据屏幕分辨率动态调整弹幕缓存池大小
        val screenW = context.resources.displayMetrics.widthPixels
        val screenH = context.resources.displayMetrics.heightPixels
        DanmakuConfig.CACHE_POOL_MAX_MEMORY_SIZE =
            DanmakuConfig.computeCachePoolMaxMemorySize(screenW, screenH)

        danmakuPlayer = DanmakuPlayer(SimpleRenderer()).also { player ->
            player.bindView(danmakuView)
            player.updateConfig(danmakuConfig)
            val viewWidth = danmakuView.measuredWidth
            val viewHeight = danmakuView.measuredHeight
            if (viewWidth > 0 && viewHeight > 0) {
                player.notifyDisplayerSizeChanged(viewWidth, viewHeight)
            }
            val shouldDeferInitialData = deferInitialData &&
                danmakuData.isNotEmpty() &&
                resolveStartupDataDelayMs() > 0L
            val shouldStartNow = isDanmakuStarted && hasPreparedData()
            if (shouldStartNow) {
                player.start(danmakuConfig)
                if (isDanmakuPaused) {
                    player.pause()
                }
            }
            if (danmakuData.isNotEmpty()) {
                if (shouldDeferInitialData) {
                    applyPreparedDataToPlayer(
                        player = player,
                        generation = prepareGeneration,
                        deferDuringStartup = true,
                        startupTraceId = startupTraceId,
                        startupTraceStartElapsedMs = startupTraceStartElapsedMs,
                        appliedCount = appliedCount,
                        rawCount = rawCount
                    )
                } else {
                    replacePlayerWindowData(
                        player = player,
                        data = danmakuData,
                        reason = "init",
                        generation = prepareGeneration
                    )
                }
            }
            if (!shouldDeferInitialData && danmakuPositionMs > 0L) {
                seekPlayerTo(
                    player = player,
                    targetPositionMs = danmakuPositionMs,
                    currentTimeMs = null,
                    forceSeek = true,
                    reason = "init",
                    bypassDedup = true
                )
            }
            startPreparedPlayerIfNeeded(player)
        }
    }

    private fun startPreparedPlayerIfNeeded(player: DanmakuPlayer, restartDriftSync: Boolean = true) {
        if (!isDanmakuStarted || isDanmakuPaused || !hasPreparedData()) {
            return
        }
        ensureTimerFactor(player, currentPlaybackSpeed)
        player.start(danmakuConfig)
        if (restartDriftSync) {
            startDriftSync()
        }
    }

    private fun List<DmModel>.prepareDanmakuItems(
        allowVipColorful: Boolean,
        stage: String,
        startIndex: Long
    ): List<DanmakuItemData> {
        val filtered = BiliDanmakuFilterPolicy.apply(
            items = this,
            context = filterContext,
            settings = lastSettingsSnapshot,
            stage = stage
        ).applySmartFilter(level = smartFilterLevel, stage = stage)
        val preparedSource = if (mergeDuplicate) {
            DanmakuDuplicateMergePolicy.merge(filtered)
        } else {
            filtered
        }
        return preparedSource
            .mapIndexedNotNull { index, item ->
            item.toDanmakuItemData(startIndex + index, allowVipColorful)
        }
    }

    private fun replacePlayerWindowData(
        player: DanmakuPlayer,
        data: List<DanmakuItemData>,
        reason: String,
        generation: Long,
        keepLastFrame: Boolean = false
    ) {
        if (prepareGeneration != generation) return
        if (keepLastFrame) {
            player.clearDataKeepingLastFrame()
        } else {
            player.clearData()
        }
        if (data.isNotEmpty()) {
            player.replaceData(data)
        }
        lastWindowApplyRealtimeMs = SystemClock.elapsedRealtime()
        AppLog.i(
            TAG,
            "danmaku active window applied: reason=$reason position=$danmakuPositionMs " +
                "count=${data.size} rawWindow=$activeWindowRawCount rawTotal=$rawDanmakuCount " +
                "window=[$activeWindowStartMs,$activeWindowEndMs] " +
                "coveredUntil=$activeWindowCoveredUntilMs"
        )
    }

    private fun appendPlayerWindowData(
        player: DanmakuPlayer,
        window: PreparedWindow,
        signature: Long,
        reason: String,
        generation: Long
    ) {
        if (prepareGeneration != generation || window.data.isEmpty()) return
        player.updateData(window.data)
        applyAppendedWindowState(window, signature)
        startPreparedPlayerIfNeeded(player, restartDriftSync = false)
        lastWindowApplyRealtimeMs = SystemClock.elapsedRealtime()
        AppLog.i(
            TAG,
            "danmaku active window appended: reason=$reason position=$danmakuPositionMs " +
                "append=${window.data.size} submittedRaw=$activeWindowRawCount rawTotal=$rawDanmakuCount " +
                "window=[$activeWindowStartMs,$activeWindowEndMs] " +
                "coveredUntil=$activeWindowCoveredUntilMs"
        )
    }

    private fun scheduleActiveWindowRefresh(
        positionMs: Long,
        force: Boolean,
        reason: String,
        ignoreFreshWindow: Boolean = false
    ) {
        val timeline = danmakuTimeline
        if (timeline.data.isEmpty() || liveEngineStarted) return
        if (!force && !ignoreFreshWindow && isActiveWindowFreshFor(positionMs)) return
        val now = SystemClock.elapsedRealtime()
        if (!force && !ignoreFreshWindow && now - lastWindowApplyRealtimeMs < WINDOW_REFRESH_MIN_INTERVAL_MS) return
        val generation = prepareGeneration
        windowRefreshJob?.cancel()
        windowRefreshJob = controllerScope.launch {
            val allowVipColorful = isVipColorfulDanmakuAllowed()
            val fullRange = timeline.data.resolveWindowRange(
                positionMs = positionMs,
                behindMs = ACTIVE_WINDOW_BEHIND_MS,
                aheadMs = ACTIVE_WINDOW_AHEAD_MS,
                maxItems = Int.MAX_VALUE,
                anchorAtPositionWhenCapped = false
            )
            val replaceWindow = DanmakuWindowRangePolicy.shouldReplaceWindow(
                positionMs = positionMs,
                activeWindowStartMs = activeWindowStartMs,
                activeWindowEndMs = activeWindowEndMs,
                hasSubmittedWindow = activeWindowSubmittedEndIndex >= 0,
                force = force
            )
            val appendStartIndex = if (replaceWindow) {
                timeline.data.lowerBoundProgress(positionMs)
                    .coerceIn(fullRange.startIndex, fullRange.naturalEndIndex)
            } else if (activeWindowSubmittedEndIndex < 0) {
                fullRange.startIndex
            } else {
                max(activeWindowSubmittedEndIndex, fullRange.startIndex)
            }
            val appendEndIndex = fullRange.resolveAdaptiveAppendEndIndex(appendStartIndex)
            if (appendStartIndex >= appendEndIndex) {
                return@launch
            }
            val preparedWindow = timeline.data.buildPreparedRange(
                positionMs = positionMs,
                range = fullRange.copy(
                    startIndex = appendStartIndex,
                    endIndex = appendEndIndex
                ),
                allowVipColorful = allowVipColorful,
                stage = reason
            )
            val signature = preparedWindow.data.fastPreparedSignature()
            withContext(Dispatchers.Main.immediate) {
                if (prepareGeneration != generation || danmakuTimeline.signature != timeline.signature) {
                    return@withContext
                }
                if (!force && signature == preparedDanmakuSignature) {
                    AppLog.d(
                        TAG,
                        "danmaku active window unchanged: reason=$reason position=$positionMs " +
                            "count=${preparedWindow.data.size} coveredUntil=${preparedWindow.coveredUntilMs}"
                    )
                    return@withContext
                }
                val player = danmakuPlayer ?: return@withContext
                if (replaceWindow) {
                    applyPreparedWindowState(preparedWindow, signature)
                    replacePlayerWindowData(
                        player = player,
                        data = preparedWindow.data,
                        reason = reason,
                        generation = generation,
                        keepLastFrame = force
                    )
                } else {
                    appendPlayerWindowData(
                        player = player,
                        window = preparedWindow,
                        signature = signature,
                        reason = reason,
                        generation = generation
                    )
                }
                if (isDanmakuPaused) {
                    player.pause()
                }
            }
        }
    }

    private fun isActiveWindowFreshFor(positionMs: Long): Boolean {
        if (activeWindowStartMs == Long.MIN_VALUE || activeWindowEndMs == Long.MIN_VALUE) return false
        if (positionMs < activeWindowStartMs || positionMs > activeWindowEndMs) return false
        val remainingAheadMs = activeWindowCoveredUntilMs - positionMs
        if (remainingAheadMs <= 0L) return false
        val hasPendingWindowData = activeWindowSubmittedEndIndex in 0 until activeWindowNaturalEndIndex
        if (hasPendingWindowData && remainingAheadMs <= WINDOW_REFRESH_AHEAD_THRESHOLD_MS) return false
        if (remainingAheadMs > WINDOW_REFRESH_AHEAD_THRESHOLD_MS) return true
        val anchor = activeWindowAnchorMs
        if (anchor == Long.MIN_VALUE) return false
        return positionMs - anchor < WINDOW_REFRESH_MIN_PROGRESS_MS
    }

    private fun RawWindowRange.resolveAdaptiveAppendEndIndex(appendStartIndex: Int): Int {
        val remainingItems = naturalEndIndex - appendStartIndex
        if (remainingItems <= 0) return appendStartIndex
        val naturalWindowItems = naturalEndIndex - startIndex
        val submittedItems = (activeWindowSubmittedEndIndex - activeWindowSubmittedStartIndex)
            .coerceAtLeast(0)
        val batchSize = when {
            naturalWindowItems <= ACTIVE_WINDOW_FULL_SUBMIT_MAX_ITEMS -> remainingItems
            submittedItems < ACTIVE_WINDOW_MIN_WARM_ITEMS -> {
                max(ACTIVE_WINDOW_APPEND_BATCH_SIZE, ACTIVE_WINDOW_MIN_WARM_ITEMS - submittedItems)
                    .coerceAtMost(remainingItems)
            }
            else -> ACTIVE_WINDOW_APPEND_BATCH_SIZE.coerceAtMost(remainingItems)
        }
        return (appendStartIndex + batchSize).coerceAtMost(naturalEndIndex)
    }

    private fun applyPreparedWindowState(window: PreparedWindow, signature: Long) {
        danmakuPositionMs = window.positionMs.coerceAtLeast(0L)
        danmakuData = window.data
        preparedDanmakuSignature = signature
        preparedDanmakuCount = window.data.size
        activeWindowStartMs = window.range.windowStartMs
        activeWindowEndMs = window.range.windowEndMs
        activeWindowCoveredUntilMs = window.coveredUntilMs
        activeWindowRawCount = window.rawCount
        activeWindowAnchorMs = window.positionMs.coerceAtLeast(0L)
        activeWindowSubmittedStartIndex = window.range.startIndex
        activeWindowSubmittedEndIndex = window.range.endIndex
        activeWindowNaturalEndIndex = window.range.naturalEndIndex
    }

    private fun applyAppendedWindowState(window: PreparedWindow, signature: Long) {
        danmakuPositionMs = window.positionMs.coerceAtLeast(0L)
        danmakuData = danmakuData + window.data
        preparedDanmakuSignature = preparedDanmakuSignature.mix(signature)
        preparedDanmakuCount = danmakuData.size
        activeWindowStartMs = window.range.windowStartMs
        activeWindowEndMs = window.range.windowEndMs
        activeWindowSubmittedEndIndex = max(activeWindowSubmittedEndIndex, window.range.endIndex)
        activeWindowNaturalEndIndex = window.range.naturalEndIndex
        activeWindowRawCount = if (activeWindowSubmittedStartIndex >= 0) {
            activeWindowSubmittedEndIndex - activeWindowSubmittedStartIndex
        } else {
            window.rawCount
        }
        activeWindowCoveredUntilMs = resolveSubmittedCoveredUntil(
            timeline = danmakuTimeline.data,
            submittedEndIndex = activeWindowSubmittedEndIndex,
            naturalEndIndex = activeWindowNaturalEndIndex,
            windowEndMs = window.range.windowEndMs
        )
    }

    private fun clearActiveWindowState() {
        danmakuData = emptyList()
        preparedDanmakuSignature = 0L
        preparedDanmakuCount = 0
        activeWindowStartMs = Long.MIN_VALUE
        activeWindowEndMs = Long.MIN_VALUE
        activeWindowCoveredUntilMs = Long.MIN_VALUE
        activeWindowRawCount = 0
        activeWindowAnchorMs = Long.MIN_VALUE
        activeWindowSubmittedStartIndex = -1
        activeWindowSubmittedEndIndex = -1
        activeWindowNaturalEndIndex = -1
    }

    private fun syncSnapshotPosition() {
        val currentTime = danmakuPlayer?.getCurrentTimeMs() ?: return
        if (currentTime > danmakuPositionMs) {
            danmakuPositionMs = currentTime
        }
    }

    private fun resolveDriftSyncIntervalMs(): Long {
        val speed = currentPlaybackSpeed
        return when {
            speed >= 1.75f -> DRIFT_SYNC_INTERVAL_HIGH_MS
            speed >= 1.25f -> DRIFT_SYNC_INTERVAL_MEDIUM_MS
            speed <= 0.75f -> DRIFT_SYNC_INTERVAL_SLOW_MS
            else -> DRIFT_SYNC_INTERVAL_NORMAL_MS
        }
    }

    private fun resolveDriftToleranceMs(): Long {
        return if (currentPlaybackSpeed >= 1.5f) DRIFT_TOLERANCE_HIGH_SPEED_MS
        else DRIFT_TOLERANCE_NORMAL_MS
    }

    private fun startDriftSync() {
        driftSyncJob?.cancel()
        val provider = playerPositionProvider
        if (provider == null) return
        driftSyncJob = controllerScope.launch {
            while (isActive) {
                delay(resolveDriftSyncIntervalMs())
                try {
                    withContext(Dispatchers.Main.immediate) {
                        applyDriftSyncTick(provider)
                    }
                } catch (_: Exception) {
                    AppLog.w(TAG, "Drift sync tick failed, will retry")
                }
            }
        }
    }

    /**
     * 三段 drift 处理策略，避免每隔几秒就 hard seek 引发 retainer 重建：
     *  1. drift <= NEUTRAL：完全恢复正常 timer factor。
     *  2. drift <= SOFT_SYNC：通过 timer factor 软纠正（基础速度 ± 5%）。
     *  3. drift  > HARD：校准 timer 到播放器位置，但不重置 runtime/window，避免播放中清屏。
     */
    private fun applyDriftSyncTick(provider: () -> Long) {
        if (!isDanmakuStarted || isDanmakuPaused) return
        val player = danmakuPlayer ?: return
        val videoPos = provider().coerceAtLeast(0L)
        val enginePos = player.getCurrentTimeMs()
        val signedDrift = enginePos - videoPos
        val absDrift = abs(signedDrift)
        val hardThreshold = resolveDriftToleranceMs()
        val baseFactor = currentPlaybackSpeed
        when {
            absDrift > hardThreshold -> {
                ensureTimerFactor(player, baseFactor)
                player.syncTimerTo(videoPos)
            }
            absDrift > DRIFT_SOFT_SYNC_LIMIT_MS -> {
                // 软同步处理不了的中等偏差，继续观察一拍即可（下次 tick 会重新评估）。
                ensureTimerFactor(player, baseFactor)
            }
            absDrift > DRIFT_NEUTRAL_TOLERANCE_MS -> {
                val correction = if (signedDrift > 0) {
                    1f - DRIFT_SOFT_CORRECTION
                } else {
                    1f + DRIFT_SOFT_CORRECTION
                }
                ensureTimerFactor(player, baseFactor * correction)
            }
            else -> {
                ensureTimerFactor(player, baseFactor)
            }
        }
    }

    private fun ensureTimerFactor(player: DanmakuPlayer, factor: Float) {
        val safe = factor.coerceAtLeast(0.1f)
        if (abs(appliedTimerFactor - safe) < 1e-3f) return
        appliedTimerFactor = safe
        player.updatePlaySpeed(safe)
    }

    private fun stopDriftSync() {
        driftSyncJob?.cancel()
        driftSyncJob = null
        appliedTimerFactor = currentPlaybackSpeed
    }

    private fun releasePlayer() {
        startupDataApplyJob?.cancel()
        startupDataApplyJob = null
        danmakuPlayer?.release()
        danmakuPlayer = null
        liveEngineStarted = false
        liveThrottleCount = 0
        lastSeekPositionMs = Long.MIN_VALUE
        lastSeekRealtimeMs = 0L
    }

    private fun scheduleVipTexturePreload(
        styles: List<DanmakuVipGradientStyle>,
        generation: Long
    ) {
        if (styles.isEmpty()) {
            return
        }
        preloadTextureJob?.cancel()
        preloadTextureJob = controllerScope.launch(Dispatchers.IO) {
            VipDanmakuTextureCache.preloadStyles(styles)
            withContext(Dispatchers.Main.immediate) {
                if (prepareGeneration != generation) {
                    return@withContext
                }
                // Refresh cached text bitmaps after texture downloads complete.
                danmakuConfig.updateCache()
                danmakuPlayer?.updateConfig(danmakuConfig)
            }
        }
    }

    private fun updatePreparationOptions(mergeDuplicateEnabled: Boolean, smartFilterLevel: Int, screenPartValue: Float) {
        val mergeChanged = mergeDuplicate != mergeDuplicateEnabled
        val smartFilterChanged = this.smartFilterLevel != smartFilterLevel
        val screenPartChanged = screenPart != screenPartValue
        mergeDuplicate = mergeDuplicateEnabled
        screenPart = screenPartValue
        this.smartFilterLevel = smartFilterLevel
        if ((mergeChanged || smartFilterChanged || screenPartChanged) && rawDanmakuData.isNotEmpty()) {
            rebuildAndApplyData()
        }
    }

    private fun seekPlayerTo(
        player: DanmakuPlayer,
        targetPositionMs: Long,
        currentTimeMs: Long?,
        forceSeek: Boolean,
        reason: String,
        bypassDedup: Boolean = false
    ) {
        if (!bypassDedup && shouldSuppressDuplicateSeek(targetPositionMs, currentTimeMs)) {
            return
        }
        if (forceSeek && !bypassDedup && !isActiveWindowFreshFor(targetPositionMs)) {
            // 目标位置若无弹幕数据（目标分段尚未加载到位），不清空引擎，避免引擎进入
            // 空数据 transition 后无新帧可绘制而长时间卡死（active=0）。
            // 保留当前帧作为 transition 继续显示，待数据 appendData 后由下一次窗口刷新补上。
            if (danmakuTimeline.data.hasDataAround(targetPositionMs)) {
                player.clearDataKeepingLastFrame()
                clearActiveWindowState()
            }
            scheduleActiveWindowRefresh(
                positionMs = targetPositionMs,
                force = true,
                reason = "seek_$reason"
            )
        }
        player.seekTo(targetPositionMs)
        lastSeekPositionMs = targetPositionMs
        lastSeekRealtimeMs = SystemClock.elapsedRealtime()
        // AkDanmaku seekTo() will restart its timer, so we need to restore
        // the paused snapshot when the video itself is still paused.
        if (isDanmakuPaused) {
            player.pause()
        }
    }

    private fun shouldSuppressDuplicateSeek(
        targetPositionMs: Long,
        currentTimeMs: Long?
    ): Boolean {
        val lastPosition = lastSeekPositionMs
        if (lastPosition == Long.MIN_VALUE) {
            return false
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastSeekRealtimeMs > SEEK_DEDUP_WINDOW_MS) {
            return false
        }
        if (abs(lastPosition - targetPositionMs) > SEEK_DEDUP_POSITION_TOLERANCE_MS) {
            return false
        }
        if (currentTimeMs != null && abs(currentTimeMs - targetPositionMs) <= SEEK_DEDUP_POSITION_TOLERANCE_MS) {
            return true
        }
        return true
    }

    private fun updateVisibility(enabled: Boolean) {
        if (danmakuConfig.visibility == enabled) {
            return
        }
        updateConfig(danmakuConfig.copy(visibility = enabled))
    }

    private fun updateAlpha(alpha: Float) {
        updateConfig(danmakuConfig.copy(alpha = alpha.coerceIn(0.1f, 1f)))
    }

    private fun updateTextSize(size: Int) {
        updateConfig(danmakuConfig.copy(textSizeScale = size.toDanmakuTextScale()))
    }

    private fun updateSpeed(speed: Int) {
        val durationMs = speed.toDanmakuDurationMs()
        updateConfig(
            danmakuConfig.copy(
                durationMs = durationMs,
                rollingDurationMs = durationMs
            )
        )
    }

    private fun updateScreenArea(area: Int) {
        updateConfig(danmakuConfig.copy(screenPart = area.toDanmakuScreenPart()))
    }

    private fun updateAllowTop(allow: Boolean) {
        applyTypeFilterAndDispatch(DanmakuItemData.DANMAKU_MODE_CENTER_TOP, allow)
    }

    private fun updateAllowBottom(allow: Boolean) {
        applyTypeFilterAndDispatch(DanmakuItemData.DANMAKU_MODE_CENTER_BOTTOM, allow)
    }

    private fun applyTypeFilterAndDispatch(type: Int, visible: Boolean) {
        if (!applyTypeFilterState(danmakuConfig, type, visible)) {
            return
        }
        danmakuConfig.updateFilter()
        danmakuPlayer?.updateConfig(danmakuConfig)
    }

    private fun applyTypeFilterState(
        config: DanmakuConfig,
        type: Int,
        visible: Boolean
    ): Boolean {
        val typeFilter = config.dataFilter
            .filterIsInstance<TypeFilter>()
            .firstOrNull()
            ?: return false
        val isCurrentlyVisible = type !in typeFilter.filterSet
        if (isCurrentlyVisible == visible) {
            return false
        }
        if (visible) {
            typeFilter.removeFilterItem(type)
        } else {
            typeFilter.addFilterItem(type)
        }
        return true
    }

    private fun updateConfig(newConfig: DanmakuConfig) {
        if (danmakuConfig == newConfig) {
            return
        }
        danmakuConfig = newConfig
        danmakuPlayer?.updateConfig(newConfig)
    }

    private fun isVipColorfulDanmakuAllowed(): Boolean {
        return isVipColorfulDanmakuSettingAllowed()
    }

    private fun hasPreparedData(): Boolean {
        return danmakuTimeline.data.isNotEmpty() || rawDanmakuData.isNotEmpty() || danmakuData.isNotEmpty()
    }

    private fun List<DmModel>.applySmartFilter(level: Int, stage: String): List<DmModel> {
        val normalizedLevel = level.normalizeSmartFilterLevel()
        if (normalizedLevel == SMART_FILTER_LEVEL_OFF || isEmpty()) {
            return this
        }
        // Smart filter uses Bilibili danmaku weight (DanmakuElem field 9, AI-derived).
        // Aligned with BiliDanmakuFilterPolicy.shouldDropByAi: drop when weight>0 and weight<level;
        // weight==0 means un-evaluated, keep it.
        val filtered = filter { item ->
            val score = item.weight
            score <= 0 || score >= normalizedLevel
        }
        val dropped = size - filtered.size
        if (dropped > 0) {
            AppLog.i(TAG, "smart filter stage=$stage level=$normalizedLevel raw=${size} kept=${filtered.size} dropped=$dropped")
        }
        return filtered
    }

    private data class MergeDuplicateKey(
        val content: String,
        val mode: Int,
        val color: Int,
        val colorful: Int,
        val colorfulSrc: String
    )

    private data class LiveMergeEntry(
        val firstItem: DmModel,
        var count: Int,
        val createdAt: Long
    )

    private fun List<DmModel>.countInRange(startMs: Long, endMs: Long): Int {
        val from = lowerBoundProgress(startMs)
        if (from >= size) return 0
        return upperBoundProgress(endMs) - from
    }

    /**
     * 判断 [positionMs] 的活动窗口范围（前 ACTIVE_WINDOW_BEHIND_MS ~ 后 ACTIVE_WINDOW_AHEAD_MS）
     * 内是否已有弹幕数据。用于 seek 时决定是否清空引擎：无数据则不清屏，避免空转卡死。
     */
    private fun List<DmModel>.hasDataAround(positionMs: Long): Boolean {
        val behindStart = (positionMs - ACTIVE_WINDOW_BEHIND_MS).coerceAtLeast(0L)
        val aheadEnd = positionMs + ACTIVE_WINDOW_AHEAD_MS
        return countInRange(behindStart, aheadEnd) > 0
    }

    private fun List<DmModel>.buildPreparedWindow(
        positionMs: Long,
        behindMs: Long,
        aheadMs: Long,
        maxItems: Int,
        allowVipColorful: Boolean,
        stage: String
    ): PreparedWindow {
        val range = resolveWindowRange(
            positionMs = positionMs,
            behindMs = behindMs,
            aheadMs = aheadMs,
            maxItems = maxItems
        )
        val rawWindowData = if (range.startIndex < range.endIndex) {
            subList(range.startIndex, range.endIndex)
        } else {
            emptyList()
        }
        val preparedData = rawWindowData.prepareDanmakuItems(
            allowVipColorful = allowVipColorful,
            stage = stage,
            startIndex = range.startIndex.toLong()
        )
        logWindowIdStats(stage, rawWindowData, preparedData)
        val coveredUntilMs = when {
            preparedData.isEmpty() -> range.windowEndMs
            range.isCapped -> preparedData.last().position
            else -> range.windowEndMs
        }
        return PreparedWindow(
            data = preparedData,
            rawCount = rawWindowData.size,
            range = range,
            positionMs = positionMs,
            coveredUntilMs = coveredUntilMs
        )
    }

    private fun List<DanmakuItemData>.withInitialImmediateItems(
        stage: String,
        positionMs: Long
    ): List<DanmakuItemData> {
        if (stage != "initial_window" || isEmpty()) return this
        if (any { it.position <= positionMs }) return this
        val immediatePosition = positionMs.coerceAtLeast(0L)
        var adjusted = 0
        return map { item ->
            if (adjusted < INITIAL_IMMEDIATE_ITEMS) {
                adjusted++
                item.copy(position = immediatePosition)
            } else {
                item
            }
        }
    }

    private fun List<DmModel>.buildPreparedRange(
        positionMs: Long,
        range: RawWindowRange,
        allowVipColorful: Boolean,
        stage: String
    ): PreparedWindow {
        val rawWindowData = if (range.startIndex < range.endIndex) {
            subList(range.startIndex, range.endIndex)
        } else {
            emptyList()
        }
        val preparedData = rawWindowData.prepareDanmakuItems(
            allowVipColorful = allowVipColorful,
            stage = stage,
            startIndex = range.startIndex.toLong()
        )
        logWindowIdStats(stage, rawWindowData, preparedData)
        return PreparedWindow(
            data = preparedData,
            rawCount = rawWindowData.size,
            range = range,
            positionMs = positionMs,
            coveredUntilMs = resolveSubmittedCoveredUntil(
                timeline = this,
                submittedEndIndex = range.endIndex,
                naturalEndIndex = range.naturalEndIndex,
                windowEndMs = range.windowEndMs
            )
        )
    }

    private fun resolveSubmittedCoveredUntil(
        timeline: List<DmModel>,
        submittedEndIndex: Int,
        naturalEndIndex: Int,
        windowEndMs: Long
    ): Long {
        if (submittedEndIndex <= 0) return windowEndMs
        if (submittedEndIndex >= naturalEndIndex) return windowEndMs
        return timeline.getOrNull(submittedEndIndex - 1)?.progress?.toLong() ?: windowEndMs
    }

    private fun List<DmModel>.resolveWindowRange(
        positionMs: Long,
        behindMs: Long,
        aheadMs: Long,
        maxItems: Int,
        anchorAtPositionWhenCapped: Boolean = true
    ): RawWindowRange {
        return DanmakuWindowRangePolicy.resolve(
            itemCount = size,
            positionMs = positionMs,
            behindMs = behindMs,
            aheadMs = aheadMs,
            maxItems = maxItems,
            progressAt = { index -> this[index].progress.toLong() },
            anchorAtPositionWhenCapped = anchorAtPositionWhenCapped
        )
    }

    private fun List<DmModel>.lowerBoundProgress(target: Long): Int {
        var lo = 0
        var hi = size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (this[mid].progress.toLong() < target) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun List<DmModel>.upperBoundProgress(target: Long): Int {
        var lo = 0
        var hi = size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (this[mid].progress.toLong() <= target) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun DmModel.toDanmakuItemData(index: Long, allowVipColorful: Boolean): DanmakuItemData? {
        val renderContent = toRenderableContent() ?: return null
        return DanmakuItemData(
            danmakuId = index + 1L,
            position = progress.toLong().coerceAtLeast(0L),
            content = renderContent,
            mode = mode.toDanmakuMode(),
            textSize = fontSize.coerceAtLeast(12),
            textColor = color.toDanmakuColor(),
            score = weight.coerceAtLeast(0),
            renderFlags = resolveRenderFlags(allowVipColorful),
            vipGradientStyle = resolveVipGradientStyle(allowVipColorful)
        )
    }

    private fun logWindowIdStats(
        stage: String,
        rawWindowData: List<DmModel>,
        preparedData: List<DanmakuItemData>
    ) {
        if (rawWindowData.size < INITIAL_WINDOW_MAX_ITEMS && preparedData.size < INITIAL_WINDOW_MAX_ITEMS) {
            return
        }
        val serviceIds = rawWindowData.asSequence().map { it.id }.filter { it > 0L }.toList()
        val serviceDuplicateCount = serviceIds.size - serviceIds.toHashSet().size
        val serviceZeroCount = rawWindowData.count { it.id <= 0L }
        val runtimeIds = preparedData.map { it.danmakuId }
        val runtimeDuplicateCount = runtimeIds.size - runtimeIds.toHashSet().size
        if (serviceDuplicateCount <= 0 && serviceZeroCount <= 0 && runtimeDuplicateCount <= 0) {
            return
        }
        AppLog.i(
            TAG,
            "danmaku id stats: stage=$stage raw=${rawWindowData.size} prepared=${preparedData.size} " +
                "serviceZero=$serviceZeroCount serviceDup=$serviceDuplicateCount runtimeDup=$runtimeDuplicateCount"
        )
    }

    private fun DmModel.resolveRenderFlags(allowVipColorful: Boolean): Int {
        if (!allowVipColorful) {
            return DanmakuItemData.RENDER_FLAG_NONE
        }
        return if (colorful == COLORFUL_VIP_GRADIENT) {
            DanmakuItemData.RENDER_FLAG_VIP_GRADIENT
        } else {
            DanmakuItemData.RENDER_FLAG_NONE
        }
    }

    private fun DmModel.resolveVipGradientStyle(allowVipColorful: Boolean): DanmakuVipGradientStyle {
        if (!allowVipColorful || colorful != COLORFUL_VIP_GRADIENT) {
            return DanmakuVipGradientStyle.NONE
        }
        return DanmakuVipGradientStyle(
            fillTextureUrl = colorfulStyle.fillColorUrl,
            strokeTextureUrl = colorfulStyle.strokeColorUrl
        )
    }

    private fun DmModel.toRenderableContent(): String? {
        return when {
            content.isBlank() -> null
            mode == 7 -> null
            mode == 9 || content.contains("def text") -> null
            else -> content
        }
    }

    private fun Int.toDanmakuMode(): Int {
        return when (this) {
            DanmakuItemData.DANMAKU_MODE_CENTER_TOP,
            DanmakuItemData.DANMAKU_MODE_CENTER_BOTTOM -> this
            else -> DanmakuItemData.DANMAKU_MODE_ROLLING
        }
    }

    private fun Int.toDanmakuFontBorder(): Int {
        return when (this) {
            DanmakuConfig.FONT_BORDER_HEAVY,
            DanmakuConfig.FONT_BORDER_SHADOW,
            DanmakuConfig.FONT_BORDER_NONE -> this
            else -> DanmakuConfig.FONT_BORDER_DEFAULT
        }
    }

    private fun Int.toDanmakuColor(): Int {
        return if (this == 0) {
            Color.WHITE
        } else {
            this or 0xFF000000.toInt()
        }
    }

    private fun Int.normalizeSmartFilterLevel(): Int {
        return coerceIn(SMART_FILTER_LEVEL_OFF, SMART_FILTER_LEVEL_MAX)
    }

    private fun Int.toDanmakuTextScale(): Float {
        return when (this) {
            30 -> 0.55f
            31 -> 0.6f
            32 -> 0.65f
            33 -> 0.7f
            34 -> 0.75f
            35 -> 0.8f
            36 -> 0.85f
            37 -> 0.9f
            38 -> 0.95f
            39 -> 1.0f
            40 -> 1.14f
            41 -> 1.3f
            42 -> 1.4f
            43 -> 1.5f
            44 -> 1.6f
            45 -> 1.7f
            46 -> 1.8f
            47 -> 2.0f
            48 -> 2.1f
            49 -> 2.2f
            50 -> 2.3f
            51 -> 2.4f
            52 -> 2.5f
            53 -> 2.6f
            54 -> 2.7f
            55 -> 2.8f
            else -> 1.14f
        }
    }

    private fun Int.toDanmakuDurationMs(): Long {
        return when (this) {
            1 -> 12000L
            2 -> 10200L
            3 -> 8400L
            5 -> 6000L
            6 -> 4800L
            7 -> 3840L
            8 -> 3000L
            9 -> 2160L
            else -> 6600L
        }
    }

    private fun Int.toDanmakuScreenPart(): Float {
        return when (this) {
            -1 -> 1f / 8f
            0 -> 0.16f
            1 -> 1f / 4f
            3 -> 1f / 2f
            7 -> 3f / 4f
            else -> 1f
        }
    }

    /**
     * 归并两条按 progress 升序的弹幕时间线。existingData 已有序（timeline 维护的不变量），
     * incomingData 通常是小批次（可能乱序），先内部排序再归并；结果等价于
     * (existing + incoming).sortedBy { progress }，但复杂度从 O(n log n) 降到 O(n)。
     * 供 appendData 在后台线程调用，替代原先主线程的全量 sortedBy。
     */
    private fun mergeSortedTimelines(existing: List<DmModel>, incoming: List<DmModel>): List<DmModel> {
        if (incoming.isEmpty()) return existing
        if (existing.isEmpty()) return incoming.sortedBy { it.progress }
        val sortedIncoming = if (incoming.size <= 1) incoming else incoming.sortedBy { it.progress }
        val result = ArrayList<DmModel>(existing.size + sortedIncoming.size)
        var i = 0
        var j = 0
        while (i < existing.size && j < sortedIncoming.size) {
            if (existing[i].progress <= sortedIncoming[j].progress) {
                result.add(existing[i]); i++
            } else {
                result.add(sortedIncoming[j]); j++
            }
        }
        while (i < existing.size) { result.add(existing[i]); i++ }
        while (j < sortedIncoming.size) { result.add(sortedIncoming[j]); j++ }
        return result
    }

    private fun List<DmModel>.fastRawSignature(): Long {
        var acc = 1469598103934665603L
        for (item in this) {
            acc = acc.mix(item.id)
            acc = acc.mix(item.progress.toLong())
            acc = acc.mix(item.mode.toLong())
            acc = acc.mix(item.fontSize.toLong())
            acc = acc.mix(item.color.toLong())
            acc = acc.mix(item.colorful.toLong())
            acc = acc.mix(item.content.hashCode().toLong())
        }
        return acc
    }

    private fun List<DanmakuItemData>.fastPreparedSignature(): Long {
        var acc = 1469598103934665603L
        for (item in this) {
            acc = acc.mix(item.danmakuId)
            acc = acc.mix(item.position)
            acc = acc.mix(item.mode.toLong())
            acc = acc.mix(item.textSize.toLong())
            acc = acc.mix(item.textColor.toLong())
            acc = acc.mix(item.renderFlags.toLong())
            acc = acc.mix(item.content.hashCode().toLong())
        }
        return acc
    }

    private fun Long.mix(value: Long): Long {
        return (this xor value) * 1099511628211L
    }
}
