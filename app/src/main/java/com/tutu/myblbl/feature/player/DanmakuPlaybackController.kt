package com.tutu.myblbl.feature.player

import com.tutu.myblbl.core.common.format.MediaFormatUtils
import android.os.SystemClock
import com.tutu.myblbl.core.common.log.AppLog
import com.tutu.myblbl.model.dm.DmColorfulStyleParser
import com.tutu.myblbl.model.dm.DmMaskInfo
import com.tutu.myblbl.model.dm.DmModel
import com.tutu.myblbl.model.proto.DmProtoParser
import com.tutu.myblbl.model.proto.DmWebViewReplyProto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 弹幕加载、分段管理与发布的协作对象。
 *
 * 从 VideoPlayerViewModel 拆分而来，集中所有弹幕相关逻辑：元数据拉取、分段按需加载、
 * 首段 partial 加载与尾部补全、seek 跳变补全、抖音模式预热、智能蒙版(dmMask)状态管理，
 * 以及单线程顺序发布去重。
 *
 * 播放上下文（cid/aid/位置/时长/起播 trace/切集代际等）通过 [DanmakuPlaybackContext] 回调读取，
 * 不持有可变播放状态。生命周期跟随 ViewModel 注入的 [scope]。
 *
 * 关键并发约束（近期弹幕 bug 修复核心，不可破坏）：
 * 1. [danmakuLoadGeneration] + [danmakuLoadJob] + 分段状态机集合（loaded/loading/published/pending）+
 *    [danmakuSegmentCoveredUntilMs] 必须同处一类，保证跨协程无锁竞态一致。
 * 2. 双 generation：[DanmakuPlaybackContext.videoLoadGeneration] 经 [DanmakuPlaybackContext.isActiveVideoLoad]
 *    判活（切集级），[danmakuLoadGeneration] 为本类内部的弹幕加载级；[isActiveDanmakuRequest]
 *    双重校验语义不变。
 * 3. [clear]（切集时调）不取消 preload job（preload 服务于下一条播放）。
 * 4. [danmakuPublishDispatcher]（单线程）保证发布顺序与去重原子性。
 */
internal class DanmakuPlaybackController(
    private val playInfoGateway: VideoPlayerPlayInfoGateway,
    private val scope: CoroutineScope,
    private val context: DanmakuPlaybackContext,
    private val defaultFilterContext: DanmakuFilterContext = DanmakuFilterContext.EMPTY
) {

    /** 弹幕加载读取的播放上下文，由 ViewModel 实现。 */
    interface DanmakuPlaybackContext {
        val currentCid: Long
        val currentAid: Long?
        val hasReachedFirstFrame: Boolean
        val pendingSeekPositionMs: Long
        val currentPositionMs: Long
        val durationMs: Long
        val startupTraceId: String
        val startupTraceStartElapsedMs: Long
        val videoLoadGeneration: Long
        fun isActiveVideoLoad(loadGeneration: Long): Boolean
    }

    /** 单次弹幕发布事件（替代/增量 + 智能过滤上下文）。 */
    data class DanmakuUpdate(
        val items: List<DmModel>,
        val replace: Boolean,
        val filterContext: DanmakuFilterContext = DanmakuFilterContext.EMPTY
    )

    private data class PublishedDanmakuState(
        val generation: Long,
        val sequence: Long,
        val snapshotItems: List<DmModel>,
        val deltaItems: List<DmModel>,
        val replace: Boolean,
        val filterContext: DanmakuFilterContext
    )

    /** 智能蒙版加载状态。 */
    sealed class DmMaskState {
        object Idle : DmMaskState()
        object Loading : DmMaskState()
        data class Ready(val maskUrl: String, val cid: Long, val fps: Int) : DmMaskState()
        object Unavailable : DmMaskState()
    }

    private data class SpecialDanmakuPayload(
        val regularItems: List<DmModel>
    )

    companion object {
        private const val FIRST_FRAME_DM_MASK_LOAD_DELAY_MS = 2_500L
        private const val FIRST_DANMAKU_INITIAL_PARSE_END_MS = 60_000L
        private const val FIRST_DANMAKU_INITIAL_RANGE_END_MS = FIRST_DANMAKU_INITIAL_PARSE_END_MS
        private const val FIRST_DANMAKU_TAIL_PREFETCH_AHEAD_MS = 10_000L
        const val DANMAKU_SEGMENT_DURATION_MS = 360_000L
        private const val DANMAKU_PUBLISH_DIAG_THRESHOLD_MS = 4L
        // seek 跳变阈值：播放位置前进超过此值视为用户快进，需主动补全目标位置弹幕数据。
        const val DANMAKU_SEEK_JUMP_THRESHOLD_MS = 3_000L
        // seek 补全时，从已覆盖位置加载到「目标位置 + 此提前量」的范围，保证窗口内有缓冲弹幕。
        private const val DANMAKU_SEEK_RANGE_AHEAD_MS = 30_000L
        // 预取窗口：自然播放预加载时，单次至少加载此大小的范围（避免每次 position 微增都触发小请求）。
        private const val DANMAKU_PREFETCH_WINDOW_MS = 120_000L
        private val verboseDanmakuCandidateLog =
            java.lang.Boolean.getBoolean("myblbl.verbose_danmaku_candidate_log")

        // 弹幕元数据缓存：跨 ViewModel 实例复用，避免每次重建都重新请求
        private val danmakuViewCache = object : LinkedHashMap<Long, Pair<DmWebViewReplyProto?, Long>>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Pair<DmWebViewReplyProto?, Long>>): Boolean {
                return size > 8
            }
        }
        private var danmakuViewCacheTtlMs: Long = 300_000L
        private val douyinDanmakuSegmentCache = object : LinkedHashMap<String, Pair<SpecialDanmakuPayload, Long>>(4, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<SpecialDanmakuPayload, Long>>): Boolean {
                return size > 4
            }
        }
        private const val DOUYIN_DANMAKU_SEGMENT_CACHE_TTL_MS = 120_000L
    }

    // ===== 对外暴露的状态流 =====
    private val _danmaku = MutableStateFlow<List<DmModel>>(emptyList())
    val danmaku: StateFlow<List<DmModel>> = _danmaku

    // StateFlow 保存完整恢复点，同时保留最新增量。连续消费者只收增量；新页面或漏批消费者
    // 自动回退到完整快照，因此不再为了可恢复性让每一批都触发全量引擎重建。
    private val _publishedDanmakuState = MutableStateFlow(
        PublishedDanmakuState(
            generation = 0L,
            sequence = 0L,
            snapshotItems = emptyList(),
            deltaItems = emptyList(),
            replace = true,
            filterContext = defaultFilterContext
        )
    )
    val danmakuUpdates: Flow<DanmakuUpdate> = flow {
        var previousGeneration: Long? = null
        var previousSequence: Long? = null
        _publishedDanmakuState.collect { state ->
            val append = shouldAppendDanmakuUpdate(
                previousGeneration = previousGeneration,
                previousSequence = previousSequence,
                currentGeneration = state.generation,
                currentSequence = state.sequence,
                replace = state.replace
            )
            emit(
                DanmakuUpdate(
                    items = if (append) state.deltaItems else state.snapshotItems,
                    replace = !append,
                    filterContext = state.filterContext
                )
            )
            previousGeneration = state.generation
            previousSequence = state.sequence
        }
    }

    private val _dmMaskState = MutableStateFlow<DmMaskState>(DmMaskState.Idle)
    val dmMaskState: StateFlow<DmMaskState> = _dmMaskState

    var onDmMaskReady: ((maskUrl: String, cid: Long, fps: Int) -> Unit)? = null
    var onDmMaskReset: (() -> Unit)? = null

    // ===== 弹幕加载状态字段（整体迁入，保证跨协程一致性） =====
    private var loadedDanmakuCid: Long = 0L
    /** 当前已加载弹幕的 cid，供 ViewModel 判断首帧是否需要重新加载。 */
    val loadedCid: Long get() = loadedDanmakuCid

    // 上一次同步给弹幕分段的播放位置，用于检测 seek 跳变。
    private var lastDanmakuSyncPositionMs: Long = 0L
    // 每个弹幕分段已加载覆盖到的时间点（毫秒），用于 seek 时判断目标位置是否已有数据。
    private val danmakuSegmentCoveredUntilMs = mutableMapOf<Int, Long>()
    private var danmakuLoadJob: Job? = null
    private var danmakuLoadGeneration: Long = 0L

    // 弹幕片段缓存：按片段索引存储弹幕数据，用于淘汰远距离片段释放内存
    private val danmakuSegmentPayloads = LinkedHashMap<Int, SpecialDanmakuPayload>()
    private val danmakuLoadedSegments = linkedSetOf<Int>()
    private val danmakuLoadingSegments = linkedSetOf<Int>()
    private val danmakuPublishedSegments = linkedSetOf<Int>()
    private val danmakuPublishPendingSegments = linkedSetOf<Int>()
    // replace 时整体替换（主线程 O(1) 引用赋值，避免万级 HashSet addAll 卡帧）。
    private var publishedRegularDanmakuKeys = HashSet<String>()
    // 弹幕发布专用单线程后台 dispatcher：保证多次发布的计算/emit 顺序（replace 先于 append），
    // 同时把去重、排序、identityKey 预算移出主线程（P1：init 发布 3000 条曾主线程耗时 37ms）。
    private val danmakuPublishDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val danmakuPublishMutex = Mutex()
    private var publishedDanmakuStateGeneration: Long = 0L
    private var publishedDanmakuSequence: Long = 0L
    private var publishedDanmakuSnapshot: List<DmModel> = emptyList()
    @Volatile
    private var danmakuPublishGeneration: Long = 0L
    private var currentDanmakuSegmentIndex: Int = -1
    private var danmakuTotalSegments: Int = 0
    private var currentDanmakuFilterContext: DanmakuFilterContext = defaultFilterContext

    // 弹幕 view 预加载：在拿到 cid+aid 后立即启动，与 PlayInfo 并行
    private var danmakuViewPreloadJob: Job? = null
    private var preloadedDanmakuViewCid: Long = 0L
    private var preloadedDanmakuView: DmWebViewReplyProto? = null
    private var danmakuSegmentPreloadJob: Job? = null
    private var preloadedDanmakuSegmentCid: Long = 0L
    private var preloadedDanmakuSegmentAid: Long = 0L
    private var preloadedDanmakuSegmentIndex: Int = 0
    private var preloadedDanmakuSegmentPayload: SpecialDanmakuPayload? = null

    private var firstDanmakuTraceLoggedId: String = PlaybackStartupTrace.NO_TRACE
    private var firstDanmakuSegmentTraceLoggedId: String = PlaybackStartupTrace.NO_TRACE

    /** 新一次播放开始时重置 trace 标记（避免上一视频的 trace 误命中）。 */
    fun resetStartupTraceState() {
        invalidatePendingPublishes()
        firstDanmakuTraceLoggedId = PlaybackStartupTrace.NO_TRACE
        firstDanmakuSegmentTraceLoggedId = PlaybackStartupTrace.NO_TRACE
    }

    // ===== 公开方法（供 ViewModel 调用） =====

    fun loadDanmaku(cid: Long, aid: Long, durationMs: Long) {
        danmakuLoadJob?.cancel()
        if (cid <= 0L || aid <= 0L) {
            clear()
            return
        }
        val loadGeneration = ++danmakuLoadGeneration
        val seekPositionMs = context.pendingSeekPositionMs
        // 标记当前 cid 已进入弹幕加载（与原 VM 在调用 loadDanmaku 前置 loadedDanmakuCid 的语义一致），
        // 使首帧/切集判断立即反映本次加载，避免协程启动窗口内被重复触发。
        loadedDanmakuCid = cid
        danmakuLoadJob = scope.launch {
            val fallbackSegmentCount = maxOf(1, ((durationMs.coerceAtLeast(1L) - 1L) / DANMAKU_SEGMENT_DURATION_MS + 1L).toInt())
            val eagerInitialSegment = ((seekPositionMs.coerceAtLeast(0L) / DANMAKU_SEGMENT_DURATION_MS) + 1L)
                .toInt()
                .coerceIn(1, fallbackSegmentCount)
            // 1. 获取弹幕 view 元数据（优先：预加载 > 缓存 > 网络）
            var danmakuViewSource = "none"
            val danmakuView = if (preloadedDanmakuViewCid == cid) {
                danmakuViewSource = "preloaded"
                preloadedDanmakuView.also {
                    preloadedDanmakuViewCid = 0L
                    preloadedDanmakuView = null
                }
            } else {
                val cacheEntry = danmakuViewCache[cid]
                val cacheAge = cacheEntry?.second?.let { System.currentTimeMillis() - it }
                val cached = cacheEntry
                    ?.takeIf { System.currentTimeMillis() - it.second < danmakuViewCacheTtlMs }
                    ?.first
                if (cached != null) {
                    danmakuViewSource = "cache"
                    cached
                } else {
                    playInfoGateway.requestDanmakuViewBytes(
                        cid = cid,
                        aid = aid
                    )?.let { bytes ->
                        danmakuViewSource = "network"
                        runCatching { DmProtoParser.parseView(bytes) }.getOrNull()
                    }
                }
            }
            // 只缓存非 null 的元数据
            if (danmakuView != null) {
                danmakuViewCache[cid] = danmakuView to System.currentTimeMillis()
            }
            currentDanmakuFilterContext = DanmakuFilterContext.fromView(danmakuView)
            val smartFilter = danmakuView?.smartFilterConfig
            PlaybackStartupTrace.log(
                traceId = context.startupTraceId,
                startElapsedMs = context.startupTraceStartElapsedMs,
                step = "danmaku_view_ready",
                message = "cid=$cid source=$danmakuViewSource segments=${danmakuView?.totalSegments ?: 0} totalCount=${danmakuView?.totalCount ?: 0} filterLevel=${smartFilter?.resolvedLevel ?: 0} filterEnabled=${smartFilter?.resolvedEnabled ?: false} cloudLvl=${smartFilter?.cloudLevel ?: 0} cloudSw=${smartFilter?.cloudSwitch ?: 0} playerLvl=${smartFilter?.playerLevel ?: 0} playerOn=${smartFilter?.playerEnabled ?: false}"
            )

            val segmentCount = danmakuView?.totalSegments
                ?.takeIf { it > 0 }
                ?: fallbackSegmentCount
            val expectedSegmentCount = resolveExpectedDanmakuSegmentCount(
                totalCount = danmakuView?.totalCount ?: 0L,
                totalSegments = segmentCount
            )
            logDanmakuMeta(
                cid = cid,
                aid = aid,
                durationMs = durationMs,
                segmentCount = segmentCount,
                danmakuView = danmakuView
            )

            // 初始化片段缓存
            danmakuSegmentPayloads.clear()
            danmakuLoadedSegments.clear()
            danmakuLoadingSegments.clear()
            danmakuPublishedSegments.clear()
            danmakuPublishPendingSegments.clear()
            danmakuSegmentCoveredUntilMs.clear()
            danmakuTotalSegments = segmentCount

            // 2. 计算初始段（根据 seekPosition，使用协程启动前的快照）
            val initialSegment = resolveDanmakuSegmentIndex(seekPositionMs)
                .takeIf { it > 0 } ?: eagerInitialSegment
            currentDanmakuSegmentIndex = initialSegment
            PlaybackStartupTrace.log(
                traceId = context.startupTraceId,
                startElapsedMs = context.startupTraceStartElapsedMs,
                step = "danmaku_initial_segment_selected",
                message = "cid=$cid seekMs=$seekPositionMs segment=$initialSegment total=$segmentCount"
            )

            // 3. 加载整段弹幕（与 B 站一致：一个 segment 一次请求，6 分钟数据一次拿全）
            danmakuLoadingSegments.add(initialSegment)
            val currentPayload = try {
                val preloaded = if (initialSegment == eagerInitialSegment) {
                    awaitPreloadedDanmakuSegment(
                        cid = cid,
                        aid = aid,
                        segmentIndex = eagerInitialSegment
                    )
                } else {
                    null
                }
                if (preloaded != null) {
                    PlaybackStartupTrace.log(
                        traceId = context.startupTraceId,
                        startElapsedMs = context.startupTraceStartElapsedMs,
                        step = "danmaku_segment_preload_hit",
                        message = "cid=$cid segment=$initialSegment regular=${preloaded.regularItems.size}"
                    )
                    preloaded
                } else {
                    val douyinCached = takeDouyinDanmakuSegmentCache(cid, aid, initialSegment)
                    if (douyinCached != null) {
                        PlaybackStartupTrace.log(
                            traceId = context.startupTraceId,
                            startElapsedMs = context.startupTraceStartElapsedMs,
                            step = "danmaku_segment_preload_hit",
                            message = "cid=$cid segment=$initialSegment source=douyin_cache regular=${douyinCached.regularItems.size}"
                        )
                        douyinCached
                    } else {
                        loadDanmakuSegmentPayload(
                            cid = cid,
                            aid = aid,
                            segmentIndices = listOf(initialSegment),
                            expectedSegmentCount = expectedSegmentCount
                        )
                    }
                }
            } finally {
                danmakuLoadingSegments.remove(initialSegment)
            }
            if (!isActiveDanmakuRequest(loadGeneration)) {
                return@launch
            }
            if (firstDanmakuSegmentTraceLoggedId != context.startupTraceId) {
                firstDanmakuSegmentTraceLoggedId = context.startupTraceId
                PlaybackStartupTrace.log(
                    traceId = context.startupTraceId,
                    startElapsedMs = context.startupTraceStartElapsedMs,
                    step = "danmaku_initial_segment_ready",
                    message = "segment=$initialSegment regular=${currentPayload.regularItems.size}"
                )
            }
            // 缓存当前段
            danmakuSegmentPayloads[initialSegment] = currentPayload
            danmakuLoadedSegments.add(initialSegment)
            danmakuSegmentCoveredUntilMs[initialSegment] = initialSegment.toLong() * DANMAKU_SEGMENT_DURATION_MS
            danmakuPublishedSegments.clear()
            danmakuPublishedSegments.add(initialSegment)
            publishDanmaku(currentPayload.regularItems, replace = true)
            logDanmakuDiagnostics(
                label = "segment-$initialSegment",
                items = currentPayload.regularItems,
                danmakuView = danmakuView
            )
            trimDistantDanmakuSegments()

        }
    }

    /**
     * 播放位置变化时同步弹幕分段：seek 跳变检测 → 补全目标位置弹幕；更新 lastSync；分段切换加载。
     * 由 ViewModel.updatePlaybackPosition 在写入 pendingSeekPositionMs 之后调用。
     */
    fun onPositionChanged(positionMs: Long) {
        // seek 跳变检测：位置前进超过阈值视为快进，主动补全目标位置所在分段的弹幕数据，
        // 避免目标位置落在尚未加载的分段尾部时引擎空转卡死。
        if (positionMs - lastDanmakuSyncPositionMs > DANMAKU_SEEK_JUMP_THRESHOLD_MS) {
            ensureDanmakuDataForPosition(positionMs)
        }
        lastDanmakuSyncPositionMs = positionMs
        onDanmakuSegmentChanged(positionMs)
    }

    /** 在 loadVideoInfo 入口立即启动弹幕元数据预加载（最早时机） */
    fun preloadView(cid: Long, aid: Long, loadGeneration: Long) {
        if (cid <= 0L || aid <= 0L || preloadedDanmakuViewCid == cid) {
            return
        }
        val cacheEntry = danmakuViewCache[cid]
        val cacheAge = cacheEntry?.second?.let { System.currentTimeMillis() - it }
        val cached = cacheEntry
            ?.takeIf { System.currentTimeMillis() - it.second < danmakuViewCacheTtlMs }
            ?.first
        if (cached != null) {
            preloadedDanmakuViewCid = cid
            preloadedDanmakuView = cached
            return
        }
        danmakuViewPreloadJob?.cancel()
        danmakuViewPreloadJob = scope.launch(Dispatchers.IO) {
            if (!context.isActiveVideoLoad(loadGeneration)) return@launch
            val view = playInfoGateway.requestDanmakuViewBytes(cid = cid, aid = aid)
                ?.let { runCatching { DmProtoParser.parseView(it) }.getOrNull() }
            if (view != null && context.isActiveVideoLoad(loadGeneration)) {
                preloadedDanmakuViewCid = cid
                preloadedDanmakuView = view
                danmakuViewCache[cid] = view to System.currentTimeMillis()
            }
        }
    }

    /** 在 getVideoDetail 拿到 cid+aid 后调用（兼容内部路径） */
    fun preloadViewIfNeeded(loadGeneration: Long) {
        val cid = context.currentCid
        val aid = context.currentAid ?: 0L
        if (cid <= 0L || aid <= 0L) return
        preloadView(cid = cid, aid = aid, loadGeneration = loadGeneration)
    }

    fun preloadInitialSegment(cid: Long, aid: Long, segmentIndex: Int) {
        if (cid <= 0L || aid <= 0L || segmentIndex <= 0) {
            return
        }
        if (
            preloadedDanmakuSegmentCid == cid &&
            preloadedDanmakuSegmentAid == aid &&
            preloadedDanmakuSegmentIndex == segmentIndex
        ) {
            return
        }
        danmakuSegmentPreloadJob?.cancel()
        preloadedDanmakuSegmentCid = cid
        preloadedDanmakuSegmentAid = aid
        preloadedDanmakuSegmentIndex = segmentIndex
        preloadedDanmakuSegmentPayload = null
        PlaybackStartupTrace.log(
            traceId = context.startupTraceId,
            startElapsedMs = context.startupTraceStartElapsedMs,
            step = "danmaku_segment_preload_started",
            message = "cid=$cid segment=$segmentIndex"
        )
        danmakuSegmentPreloadJob = scope.launch(Dispatchers.IO) {
            val startedAt = SystemClock.elapsedRealtime()
            val payload = loadDanmakuSegmentPayload(
                cid = cid,
                aid = aid,
                segmentIndices = listOf(segmentIndex),
                expectedSegmentCount = resolveExpectedDanmakuSegmentCount(
                    totalCount = preloadedDanmakuView?.totalCount ?: danmakuViewCache[cid]?.first?.totalCount ?: 0L,
                    totalSegments = preloadedDanmakuView?.totalSegments ?: danmakuViewCache[cid]?.first?.totalSegments ?: 0
                )
            )
            withContext(Dispatchers.Main) {
                preloadedDanmakuSegmentCid = cid
                preloadedDanmakuSegmentAid = aid
                preloadedDanmakuSegmentIndex = segmentIndex
                preloadedDanmakuSegmentPayload = payload
                danmakuSegmentPreloadJob = null
                PlaybackStartupTrace.log(
                    traceId = context.startupTraceId,
                    startElapsedMs = context.startupTraceStartElapsedMs,
                    step = "danmaku_segment_preload_ready",
                    message = "cid=$cid segment=$segmentIndex elapsedMs=${SystemClock.elapsedRealtime() - startedAt} regular=${payload.regularItems.size}"
                )
            }
        }
    }

    /**
     * 清空当前播放的弹幕状态（切集时调）。
     * 不取消 preload job：preload 服务于下一条播放，新 preload 启动时自行取消旧 job。
     */
    fun clear() {
        danmakuLoadJob?.cancel()
        invalidatePendingPublishes()
        // segment preload 和 view preload 不在此取消：
        // 它们是为下一条播放预加载的，clear 只是清理当前播放的弹幕状态。
        // 新的 preload 启动时会自行取消旧 job（见 preloadInitialSegment）。
        _danmaku.value = emptyList()
        danmakuSegmentPayloads.clear()
        danmakuLoadedSegments.clear()
        danmakuLoadingSegments.clear()
        danmakuPublishedSegments.clear()
        danmakuPublishPendingSegments.clear()
        currentDanmakuSegmentIndex = -1
        danmakuTotalSegments = 0
        currentDanmakuFilterContext = defaultFilterContext
        lastDanmakuSyncPositionMs = 0L
        loadedDanmakuCid = 0L
    }

    private fun invalidatePendingPublishes() {
        val generation = ++danmakuPublishGeneration
        _danmaku.value = emptyList()
        _publishedDanmakuState.value = PublishedDanmakuState(
            generation = generation,
            sequence = 0L,
            snapshotItems = emptyList(),
            deltaItems = emptyList(),
            replace = true,
            filterContext = defaultFilterContext
        )
        scope.launch(danmakuPublishDispatcher) {
            danmakuPublishMutex.withLock {
                if (shouldResetPublishedDanmakuState(
                        queuedGeneration = generation,
                        currentGeneration = danmakuPublishGeneration,
                        publishedGeneration = publishedDanmakuStateGeneration
                    )
                ) {
                    resetPublishedDanmakuState(generation)
                }
            }
        }
    }

    /**
     * 取消 view/segment/douyin 弹幕预加载 job 并清空预加载字段。
     * 注意：不清 douyinWarmupJob（该 job 由 ViewModel 持有并编排）。
     */
    fun cancelPreload() {
        danmakuSegmentPreloadJob?.cancel()
        danmakuSegmentPreloadJob = null
        danmakuViewPreloadJob?.cancel()
        danmakuViewPreloadJob = null
        preloadedDanmakuViewCid = 0L
        preloadedDanmakuView = null
        preloadedDanmakuSegmentCid = 0L
        preloadedDanmakuSegmentAid = 0L
        preloadedDanmakuSegmentIndex = 0
        preloadedDanmakuSegmentPayload = null
    }

    /** loadPlayerExtras 解析到 dmMask 后落地状态并延迟回调；dmMask 为 null/空表示不可用。 */
    fun applyDmMask(dmMask: DmMaskInfo?) {
        if (dmMask != null && dmMask.maskUrl.isNotBlank()) {
            AppLog.d("DanmakuMask", "dm_mask ready: cid=${dmMask.cid} fps=${dmMask.fps}")
            _dmMaskState.value = DmMaskState.Ready(
                maskUrl = dmMask.maskUrl,
                cid = dmMask.cid,
                fps = dmMask.fps
            )
            scheduleDmMaskReadyCallback(dmMask)
        } else {
            AppLog.d("DanmakuMask", "dm_mask unavailable for this video")
            _dmMaskState.value = DmMaskState.Unavailable
        }
    }

    /** 重置 dmMask 状态为 Idle 并通知外部（切集时调）。 */
    fun resetDmMask() {
        _dmMaskState.value = DmMaskState.Idle
        onDmMaskReset?.invoke()
    }

    /** 仅将 dmMask 状态置为 Idle，不触发回调（保留原 reset 路径不带回调的语义）。 */
    fun markDmMaskIdle() {
        _dmMaskState.value = DmMaskState.Idle
    }

    /** 抖音模式弹幕分段预热：提前拉取并缓存首段 partial 数据。 */
    suspend fun warmupDouyinDanmakuSegment(cid: Long, aid: Long, segmentIndex: Int) {
        val cacheKey = douyinDanmakuCacheKey(cid, aid, segmentIndex)
        synchronized(douyinDanmakuSegmentCache) {
            val cached = douyinDanmakuSegmentCache[cacheKey]
            if (cached != null && System.currentTimeMillis() - cached.second < DOUYIN_DANMAKU_SEGMENT_CACHE_TTL_MS) {
                return
            }
        }
        val payload = loadDanmakuSegmentPayload(
            cid = cid,
            aid = aid,
            segmentIndices = listOf(segmentIndex),
            expectedSegmentCount = 0
        )
        synchronized(douyinDanmakuSegmentCache) {
            douyinDanmakuSegmentCache[cacheKey] = payload to System.currentTimeMillis()
        }
    }

    // ===== 内部实现 =====

    private fun takeDouyinDanmakuSegmentCache(
        cid: Long,
        aid: Long,
        segmentIndex: Int
    ): SpecialDanmakuPayload? {
        val cacheKey = douyinDanmakuCacheKey(cid, aid, segmentIndex)
        return synchronized(douyinDanmakuSegmentCache) {
            val cached = douyinDanmakuSegmentCache[cacheKey] ?: return@synchronized null
            if (System.currentTimeMillis() - cached.second >= DOUYIN_DANMAKU_SEGMENT_CACHE_TTL_MS) {
                douyinDanmakuSegmentCache.remove(cacheKey)
                null
            } else {
                douyinDanmakuSegmentCache.remove(cacheKey)?.first
            }
        }
    }

    private fun douyinDanmakuCacheKey(cid: Long, aid: Long, segmentIndex: Int): String {
        return "$aid#$cid#$segmentIndex"
    }



    private suspend fun loadDanmakuSegmentPayload(
        cid: Long,
        aid: Long,
        segmentIndices: List<Int>,
        expectedSegmentCount: Int = 0,
        rangeStartMs: Long? = null,
        rangeEndMs: Long? = null,
        parseStartMs: Long? = null,
        parseEndMs: Long? = null
    ): SpecialDanmakuPayload = withContext(Dispatchers.IO) {
        val regularItems = mutableListOf<DmModel>()
        segmentIndices.forEach { segmentIndex ->
            val bytes = playInfoGateway.requestDanmakuSegmentBytes(
                cid = cid,
                aid = aid,
                segmentIndex = segmentIndex,
                expectedSegmentCount = expectedSegmentCount,
                rangeStartMs = rangeStartMs,
                rangeEndMs = rangeEndMs
            ) ?: return@forEach
            PlaybackStartupTrace.log(
                traceId = context.startupTraceId,
                startElapsedMs = context.startupTraceStartElapsedMs,
                step = "danmaku_segment_bytes_loaded",
                message = "cid=$cid segment=$segmentIndex bytes=${bytes.size} range=${MediaFormatUtils.formatDanmakuRange(rangeStartMs, rangeEndMs)}"
            )
            val regularStartIndex = regularItems.size
            val scanResult = runCatching {
                DmProtoParser.forEachSegmentElemWithMetaInProgressRange(bytes, parseStartMs, parseEndMs) { elem ->
                    // mode==7(代码弹幕)/mode==9(脚本弹幕)为特殊弹幕，TV 端不支持，直接丢弃。
                    if (elem.mode == 7 || elem.mode == 9) {
                        return@forEachSegmentElemWithMetaInProgressRange
                    }
                    if (!isDanmakuElemInParseRange(elem.progress, parseStartMs, parseEndMs)) {
                        return@forEachSegmentElemWithMetaInProgressRange
                    }
                    regularItems += DmModel(
                        id = elem.id,
                        color = elem.color,
                        colorful = elem.colorful,
                        content = elem.content,
                        mode = elem.mode,
                        progress = elem.progress,
                        fontSize = elem.fontSize,
                        weight = elem.weight,
                        pool = elem.pool,
                        attr = elem.attr,
                        aiFlagScore = 0,
                        midHash = elem.midHash,
                        ctime = elem.ctime,
                        action = elem.action,
                        idStr = elem.idStr,
                        animation = elem.animation
                    )
                }
            }.getOrNull() ?: return@forEach
            val meta = scanResult.meta
            val aiFlagsById = meta.aiFlag.dmFlags.associate { it.dmid to it.flag }
            val colorfulSrcByType = meta.colorfulSrc
                .filter { it.type != 0 && it.src.isNotBlank() }
                .associate { it.type to it.src }
            if (aiFlagsById.isNotEmpty() || colorfulSrcByType.isNotEmpty()) {
                for (index in regularStartIndex until regularItems.size) {
                    val item = regularItems[index]
                    val rawColorfulSrc = colorfulSrcByType[item.colorful].orEmpty()
                    regularItems[index] = item.copy(
                        aiFlagScore = aiFlagsById[item.id] ?: 0,
                        colorfulSrc = rawColorfulSrc,
                        colorfulStyle = if (rawColorfulSrc.isBlank()) {
                            item.colorfulStyle
                        } else {
                            DmColorfulStyleParser.parse(rawColorfulSrc)
                        }
                    )
                }
            }
            PlaybackStartupTrace.log(
                traceId = context.startupTraceId,
                startElapsedMs = context.startupTraceStartElapsedMs,
                step = "danmaku_segment_parsed",
                message = "cid=$cid segment=$segmentIndex elems=${scanResult.elemCount} regular=${regularItems.size - regularStartIndex}"
            )
        }
        SpecialDanmakuPayload(
            regularItems = regularItems
        )
    }

    private fun logDanmakuMeta(
        cid: Long,
        aid: Long,
        durationMs: Long,
        segmentCount: Int,
        danmakuView: DmWebViewReplyProto?
    ) {
        if (danmakuView == null) {
            return
        }
        val filter = danmakuView.smartFilterConfig
        AppLog.d(
            "DanmakuMeta",
            "cid=$cid aid=$aid duration=${durationMs}ms segments=$segmentCount totalCount=${danmakuView.totalCount} filterLevel=${filter.resolvedLevel} filterEnabled=${filter.resolvedEnabled} cloudLvl=${filter.cloudLevel} cloudSw=${filter.cloudSwitch} playerLvl=${filter.playerLevel} playerOn=${filter.playerEnabled} defaultLvl=${filter.defaultLevel} defaultOn=${filter.defaultEnabled}"
        )
    }

    private fun resolveExpectedDanmakuSegmentCount(totalCount: Long, totalSegments: Int): Int {
        if (totalCount <= 0L || totalSegments <= 0) return 0
        return ((totalCount + totalSegments - 1L) / totalSegments)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
    }

    private fun logDanmakuDiagnostics(
        label: String,
        items: List<DmModel>,
        danmakuView: DmWebViewReplyProto?
    ) {
        if (items.isEmpty()) {
            return
        }
        val totalCount = danmakuView?.totalCount ?: 0L
        val totalSegments = danmakuView?.totalSegments ?: 0
        val expectedPerSegment = if (totalSegments > 0 && totalCount > 0) totalCount / totalSegments else 0L
        val filter = danmakuView?.smartFilterConfig
        AppLog.d(
            "DanmakuDiag",
            "$label: received=${items.size} totalCount=$totalCount totalSegments=$totalSegments expectedPerSeg=$expectedPerSegment filterLevel=${filter?.resolvedLevel ?: 0} filterOn=${filter?.resolvedEnabled ?: false}"
        )
        val unsupportedCount = items.count { it.mode !in setOf(1, 4, 5, 6) }
        if (unsupportedCount > 0) {
        }
        logSpecialColorCandidates(label, items)
    }

    private fun logSpecialColorCandidates(
        label: String,
        items: List<DmModel>
    ) {
        val candidates = items.filter { item ->
            item.mode in setOf(1, 6) && (
                item.action.isNotBlank() ||
                    item.animation.isNotBlank() ||
                    item.colorful != 0 ||
                    item.colorfulSrc.isNotBlank() ||
                    item.attr != 0 ||
                    item.pool != 0 ||
                    item.aiFlagScore > 0
                )
        }
        if (candidates.isEmpty()) {
            return
        }
        val colorfulSummary = candidates.groupingBy { it.colorful }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .joinToString(separator = ",") { "${it.key}:${it.value}" }
        val attrSummary = candidates.groupingBy {
            "${it.attr}/${it.colorful}/${if (it.colorfulSrc.isNotBlank()) "src" else "nosrc"}"
        }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .joinToString(separator = ",") { "${it.key}:${it.value}" }
        if (!verboseDanmakuCandidateLog) {
            return
        }
        candidates
            .sortedWith(
                compareByDescending<DmModel> { it.colorfulSrc.isNotBlank() }
                    .thenByDescending { it.colorful != 0 }
                    .thenByDescending { it.attr != 0 }
                    .thenBy { it.progress }
            )
            .distinctBy { Triple("${it.attr}/${it.colorful}", it.color, it.content.take(12)) }
            .take(8)
            .forEachIndexed { index, item ->
            }
    }

    @Suppress("unused")
    private fun Int.toColorHex(): String {
        return "0x" + toUInt().toString(16).uppercase().padStart(8, '0')
    }

    @Suppress("unused")
    private fun String.toPreview(limit: Int): String {
        val normalized = replace('\n', ' ').replace('\r', ' ').trim()
        if (normalized.isEmpty()) {
            return "<empty>"
        }
        return if (normalized.length <= limit) {
            normalized
        } else {
            normalized.take(limit) + "..."
        }
    }

    private fun publishDanmaku(items: List<DmModel>, replace: Boolean) {
        val publishGeneration = danmakuPublishGeneration
        scope.launch(danmakuPublishDispatcher) {
            danmakuPublishMutex.withLock {
                if (publishGeneration != danmakuPublishGeneration) return@withLock
                if (publishedDanmakuStateGeneration != publishGeneration) {
                    resetPublishedDanmakuState(publishGeneration)
                }

                val startedAtMs = SystemClock.elapsedRealtime()
                // 去重时同时保留 identity key，避免万级弹幕在发布阶段重复构造大量字符串。
                val normalizedBatch = items.distinctRegularDanmakuBatch()
                val normalizedItems = normalizedBatch.items
                val normalizedKeys = normalizedBatch.identityKeys
                val snapshotAdditions = normalizedItems.filterIndexed { index, _ ->
                    normalizedKeys[index] !in publishedRegularDanmakuKeys
                }
                val emittedItems = if (replace) {
                    publishedRegularDanmakuKeys.addAll(normalizedKeys)
                    normalizedItems
                } else {
                    normalizedItems.filterIndexed { index, _ ->
                        publishedRegularDanmakuKeys.add(normalizedKeys[index])
                    }
                }
                if (!replace && emittedItems.isEmpty()) return@withLock

                // 完整恢复点仍然保留，但线性归并在发布后台串行完成，不再占用主线程。
                val snapshotItems = mergePublishedDanmakuSnapshot(publishedDanmakuSnapshot, snapshotAdditions)
                if (publishGeneration != danmakuPublishGeneration) return@withLock
                publishedDanmakuSnapshot = snapshotItems
                val sequence = ++publishedDanmakuSequence
                val totalPublishedKeys = publishedRegularDanmakuKeys.size

                withContext(Dispatchers.Main.immediate) {
                    if (publishGeneration != danmakuPublishGeneration) return@withContext
                    if (replace && normalizedItems.isNotEmpty() && firstDanmakuTraceLoggedId != context.startupTraceId) {
                        firstDanmakuTraceLoggedId = context.startupTraceId
                        PlaybackStartupTrace.log(
                            traceId = context.startupTraceId,
                            startElapsedMs = context.startupTraceStartElapsedMs,
                            step = "first_danmaku_published",
                            message = "count=${normalizedItems.size} cid=${context.currentCid}"
                        )
                    }
                    if (!replace && emittedItems.size != normalizedItems.size) {
                        PlaybackStartupTrace.log(
                            traceId = context.startupTraceId,
                            startElapsedMs = context.startupTraceStartElapsedMs,
                            step = "danmaku_publish_dedup",
                            message = "input=${normalizedItems.size} emitted=${emittedItems.size} dropped=${normalizedItems.size - emittedItems.size}"
                        )
                    }
                    // 该流只驱动开关可见性；完整恢复时间线由 _publishedDanmakuState 持有。
                    // 使用固定标记避免 StateFlow 在主线程比较不断增长的历史列表。
                    _danmaku.value = danmakuAvailabilityState(snapshotItems.isNotEmpty())
                    logDanmakuPublishPerf(
                        type = "regular",
                        replace = replace,
                        inputCount = items.size,
                        normalizedCount = normalizedItems.size,
                        emittedCount = emittedItems.size,
                        totalPublishedKeys = totalPublishedKeys,
                        startedAtMs = startedAtMs
                    )
                    _publishedDanmakuState.value = PublishedDanmakuState(
                        generation = publishGeneration,
                        sequence = sequence,
                        snapshotItems = snapshotItems,
                        deltaItems = emittedItems,
                        replace = replace,
                        filterContext = currentDanmakuFilterContext
                    )
                }
            }
        }
    }

    private fun resetPublishedDanmakuState(generation: Long) {
        publishedDanmakuStateGeneration = generation
        publishedDanmakuSequence = 0L
        publishedDanmakuSnapshot = emptyList()
        publishedRegularDanmakuKeys = HashSet()
    }

    private fun logDanmakuPublishPerf(
        type: String,
        replace: Boolean,
        inputCount: Int,
        normalizedCount: Int,
        emittedCount: Int,
        totalPublishedKeys: Int,
        startedAtMs: Long
    ) {
        val costMs = SystemClock.elapsedRealtime() - startedAtMs
        val sameBatchDropped = inputCount - normalizedCount
        val historicalDropped = normalizedCount - emittedCount
        if (costMs < DANMAKU_PUBLISH_DIAG_THRESHOLD_MS &&
            inputCount < 512 &&
            sameBatchDropped == 0 &&
            historicalDropped == 0
        ) {
            return
        }
        AppLog.d(
            "PlaybackPerf",
            "danmaku_publish type=$type replace=$replace cost=${costMs}ms input=$inputCount " +
                "normalized=$normalizedCount emitted=$emittedCount sameBatchDropped=$sameBatchDropped " +
                "historyDropped=$historicalDropped publishedKeys=$totalPublishedKeys cid=${context.currentCid} " +
                "segment=$currentDanmakuSegmentIndex/$danmakuTotalSegments"
        )
    }

    private fun scheduleDmMaskReadyCallback(dmMask: DmMaskInfo) {
        val callbackGeneration = context.videoLoadGeneration
        scope.launch {
            delay(FIRST_FRAME_DM_MASK_LOAD_DELAY_MS)
            if (!context.isActiveVideoLoad(callbackGeneration) || context.currentCid != dmMask.cid) return@launch
            onDmMaskReady?.invoke(dmMask.maskUrl, dmMask.cid, dmMask.fps)
        }
    }

    private fun isDanmakuElemInParseRange(progressMs: Int, parseStartMs: Long?, parseEndMs: Long?): Boolean {
        if (parseStartMs != null && progressMs < parseStartMs) return false
        if (parseEndMs != null && progressMs >= parseEndMs) return false
        return true
    }


    private suspend fun awaitPreloadedDanmakuSegment(
        cid: Long,
        aid: Long,
        segmentIndex: Int
    ): SpecialDanmakuPayload? {
        if (
            preloadedDanmakuSegmentCid == cid &&
            preloadedDanmakuSegmentAid == aid &&
            preloadedDanmakuSegmentIndex == segmentIndex &&
            preloadedDanmakuSegmentPayload == null
        ) {
            danmakuSegmentPreloadJob?.join()
        }
        if (
            preloadedDanmakuSegmentCid != cid ||
            preloadedDanmakuSegmentAid != aid ||
            preloadedDanmakuSegmentIndex != segmentIndex
        ) {
            return null
        }
        return preloadedDanmakuSegmentPayload.also {
            preloadedDanmakuSegmentCid = 0L
            preloadedDanmakuSegmentAid = 0L
            preloadedDanmakuSegmentIndex = 0
            preloadedDanmakuSegmentPayload = null
        }
    }

    private fun isActiveDanmakuRequest(loadGeneration: Long): Boolean {
        return danmakuLoadGeneration == loadGeneration && context.currentCid == loadedDanmakuCid
    }

    /**
     * 根据播放位置计算当前弹幕片段索引（片段从1开始，每个片段6分钟）
     */
    private fun resolveDanmakuSegmentIndex(positionMs: Long): Int {
        if (danmakuTotalSegments <= 0) return -1
        return ((positionMs.coerceAtLeast(0L) / DANMAKU_SEGMENT_DURATION_MS) + 1L).toInt().coerceIn(1, danmakuTotalSegments)
    }

    /**
     * 清理距离当前播放片段过远的弹幕数据，保留当前片段 +/- 1 个片段
     */
    private fun trimDistantDanmakuSegments() {
        if (currentDanmakuSegmentIndex <= 0 || danmakuSegmentPayloads.isEmpty()) return
        val keepRange = (currentDanmakuSegmentIndex - 1)..(currentDanmakuSegmentIndex + 1)
        val keysToRemove = danmakuSegmentPayloads.keys.filter { it !in keepRange }
        if (keysToRemove.isEmpty()) return
        keysToRemove.forEach { key ->
            danmakuSegmentPayloads.remove(key)
            danmakuLoadedSegments.remove(key)
            danmakuPublishedSegments.remove(key)
            danmakuPublishPendingSegments.remove(key)
        }
    }

    /**
     * 当播放位置变化导致片段切换时，更新当前片段索引、清理远距离片段、动态加载新段
     */
    private fun onDanmakuSegmentChanged(positionMs: Long) {
        val newIndex = resolveDanmakuSegmentIndex(positionMs)
        if (newIndex <= 0) return
        if (newIndex == currentDanmakuSegmentIndex) {
            // 重试/补发当前段：如果之前的加载失败、返回空数据，或只预加载未发布，定期重试
            if (!danmakuPublishedSegments.contains(newIndex) && !danmakuLoadingSegments.contains(newIndex)) {
                loadDanmakuSegmentIfNeeded(newIndex, publishWhenReady = true)
            }
            // 邻近预加载：距离下个 segment 边界不足 2 分钟时提前加载
            if (newIndex < danmakuTotalSegments) {
                val segmentEndMs = newIndex.toLong() * DANMAKU_SEGMENT_DURATION_MS
                val remainingMs = segmentEndMs - positionMs
                if (remainingMs in 0..120_000L) {
                    loadDanmakuSegmentIfNeeded(newIndex + 1, publishWhenReady = false)
                }
            }
            // 当前段内部预加载：自然播放接近已加载覆盖边界时补全后续范围，
            // 覆盖 segment 1 partial 尾部等「已发布但未加载完整」盲区，避免越过边界后断档。
            ensureDanmakuDataForPosition(positionMs)
            return
        }
        if (!context.hasReachedFirstFrame && currentDanmakuSegmentIndex > 1 && newIndex == 1 && positionMs < 1_000L) {
            PlaybackStartupTrace.log(
                traceId = context.startupTraceId,
                startElapsedMs = context.startupTraceStartElapsedMs,
                step = "danmaku_segment_change_ignored",
                message = "positionMs=$positionMs from=$currentDanmakuSegmentIndex to=$newIndex reason=startup_zero_before_first_frame"
            )
            return
        }
        val previousIndex = currentDanmakuSegmentIndex
        currentDanmakuSegmentIndex = newIndex
        PlaybackStartupTrace.log(
            traceId = context.startupTraceId,
            startElapsedMs = context.startupTraceStartElapsedMs,
            step = "danmaku_segment_changed",
            message = "positionMs=$positionMs from=$previousIndex to=$newIndex total=$danmakuTotalSegments"
        )
        trimDistantDanmakuSegments()
        // 动态加载新段
        loadDanmakuSegmentIfNeeded(newIndex, publishWhenReady = true)
        // 预加载下一段
        if (newIndex < danmakuTotalSegments) {
            loadDanmakuSegmentIfNeeded(newIndex + 1, publishWhenReady = false)
        }
    }

    /**
     * seek 到 [positionMs] 时，确保该位置所在分段的数据已加载并发布。
     *
     * 解决两类盲区：
     * 1. seek 落在 segment 1 已发布（partial）但未加载的尾部（如只加载了 0-60s，seek 到 262s）；
     * 2. seek 跨到尚未加载的分段。
     *
     * 通过 [danmakuSegmentCoveredUntilMs] 跟踪每段已覆盖到的时间点，仅加载缺失范围并 append 发布，
     * 避免重复加载与清屏闪烁。
     */
    private fun ensureDanmakuDataForPosition(positionMs: Long) {
        val targetSegment = resolveDanmakuSegmentIndex(positionMs)
        if (targetSegment <= 0) return
        // init 首段尚未发布、或仍在初始范围内（由 init + tail 覆盖）时不介入，
        // 避免 appendData 与 init 的 setData 竞争、抢占 prepareGeneration 导致引擎未初始化、弹幕完全不显示。
        if (danmakuPublishedSegments.isEmpty()) return
        if (positionMs <= FIRST_DANMAKU_INITIAL_RANGE_END_MS) return
        val cid = context.currentCid
        val aid = context.currentAid ?: 0L
        if (cid <= 0L || aid <= 0L) return
        val segmentEndMs = targetSegment.toLong() * DANMAKU_SEGMENT_DURATION_MS
        val coveredUntil = danmakuSegmentCoveredUntilMs[targetSegment] ?: 0L
        // 提前预加载：position 距已覆盖边界不足 DANMAKU_SEEK_RANGE_AHEAD_MS 时即触发，
        // 使自然播放/seek 到达边界前数据已就绪，避免越过 coveredUntil 后断档空窗。
        if (positionMs + DANMAKU_SEEK_RANGE_AHEAD_MS <= coveredUntil) return
        // 跨到尚未发布的分段：走标准分段加载通道，保证分段状态机（loaded/published）一致。
        // seek 跨段时用 replace 清除旧段弹幕，避免旧弹幕堆积导致 active 虚高。
        if (targetSegment != currentDanmakuSegmentIndex &&
            !danmakuPublishedSegments.contains(targetSegment)
        ) {
            loadDanmakuSegmentIfNeeded(targetSegment, publishWhenReady = true, replace = true)
            return
        }
        // 当前分段（含 segment 1 的 partial 尾部）补全：从已覆盖处续加载。
        // 范围至少覆盖 positionMs + 提前量，且至少预取一个 PREFETCH_WINDOW（避免每次 position 微增
        // 都触发一次小范围请求），取两者较大值，受段尾限制。
        val rangeStartMs = coveredUntil
        val rangeEndMs = minOf(
            maxOf(positionMs + DANMAKU_SEEK_RANGE_AHEAD_MS, rangeStartMs + DANMAKU_PREFETCH_WINDOW_MS),
            segmentEndMs
        )
        if (rangeEndMs <= rangeStartMs) return
        // 乐观标记已覆盖到 rangeEndMs：在异步加载完成前，避免 onDanmakuSegmentChanged 每帧
        // 因 position 微增而重复触发同一范围的请求；加载失败/作废时回滚。
        danmakuSegmentCoveredUntilMs[targetSegment] = rangeEndMs
        val loadGeneration = danmakuLoadGeneration
        PlaybackStartupTrace.log(
            traceId = context.startupTraceId,
            startElapsedMs = context.startupTraceStartElapsedMs,
            step = "danmaku_seek_range_load",
            message = "segment=$targetSegment position=$positionMs range=${MediaFormatUtils.formatDanmakuRange(rangeStartMs, rangeEndMs)} covered=$coveredUntil"
        )
        scope.launch(Dispatchers.IO) {
            val payload = runCatching {
                loadDanmakuSegmentPayload(
                    cid = cid,
                    aid = aid,
                    segmentIndices = listOf(targetSegment),
                    expectedSegmentCount = 0,
                    rangeStartMs = rangeStartMs,
                    rangeEndMs = rangeEndMs,
                    parseEndMs = rangeEndMs
                )
            }.getOrNull()
            withContext(Dispatchers.Main) {
                if (!isActiveDanmakuRequest(loadGeneration) || context.currentCid != cid || payload == null) {
                    // 加载作废或失败：回滚乐观标记，下次重试。
                    if (danmakuSegmentCoveredUntilMs[targetSegment] == rangeEndMs) {
                        danmakuSegmentCoveredUntilMs[targetSegment] = rangeStartMs
                    }
                    return@withContext
                }
                if (rangeEndMs >= segmentEndMs) {
                    danmakuLoadedSegments.add(targetSegment)
                }
                val rangeItems = payload.regularItems
                    .filter { it.progress >= rangeStartMs && it.progress < rangeEndMs }
                    .sortedBy { it.progress }
                if (rangeItems.isNotEmpty()) {
                    publishDanmaku(rangeItems, replace = false)
                }
            }
        }
    }

    /**
     * 按需加载指定弹幕片段（如果尚未加载）
     */
    private fun loadDanmakuSegmentIfNeeded(segmentIndex: Int, publishWhenReady: Boolean, replace: Boolean = false) {
        if (danmakuLoadedSegments.contains(segmentIndex)) {
            if (publishWhenReady && !danmakuPublishedSegments.contains(segmentIndex)) {
                danmakuSegmentPayloads[segmentIndex]?.let { cachedPayload ->
                    PlaybackStartupTrace.log(
                        traceId = context.startupTraceId,
                        startElapsedMs = context.startupTraceStartElapsedMs,
                        step = "danmaku_segment_republish",
                        message = "cid=${context.currentCid} segment=$segmentIndex regular=${cachedPayload.regularItems.size}"
                    )
                    publishDanmakuSegmentPayload(segmentIndex, cachedPayload, replace = replace)
                }
            }
            return
        }
        if (danmakuLoadingSegments.contains(segmentIndex)) {
            if (publishWhenReady) {
                danmakuPublishPendingSegments.add(segmentIndex)
            }
            return
        }
        val cid = context.currentCid
        val aid = context.currentAid ?: 0L
        if (cid <= 0L || aid <= 0L) return
        danmakuSegmentPayloads[segmentIndex]?.let { cachedPayload ->
            danmakuLoadedSegments.add(segmentIndex)
            PlaybackStartupTrace.log(
                traceId = context.startupTraceId,
                startElapsedMs = context.startupTraceStartElapsedMs,
                step = "danmaku_segment_cache_hit",
                message = "cid=$cid segment=$segmentIndex publish=$publishWhenReady regular=${cachedPayload.regularItems.size}"
            )
            if (publishWhenReady) {
                publishDanmakuSegmentPayload(segmentIndex, cachedPayload, replace = replace)
            }
            return
        }
        danmakuLoadingSegments.add(segmentIndex)
        val loadGeneration = danmakuLoadGeneration
        PlaybackStartupTrace.log(
            traceId = context.startupTraceId,
            startElapsedMs = context.startupTraceStartElapsedMs,
            step = "danmaku_segment_load_started",
            message = "cid=$cid segment=$segmentIndex publish=$publishWhenReady"
        )
        scope.launch(Dispatchers.IO) {
            val expectedSegmentCount = resolveExpectedDanmakuSegmentCount(
                totalCount = danmakuViewCache[cid]?.first?.totalCount ?: 0L,
                totalSegments = danmakuTotalSegments
            )
            var payload = runCatching {
                loadDanmakuSegmentPayload(
                    cid = cid,
                    aid = aid,
                    segmentIndices = listOf(segmentIndex),
                    expectedSegmentCount = expectedSegmentCount
                )
            }.getOrNull()
            if (payload == null) {
                delay(2000L)
                payload = runCatching {
                    loadDanmakuSegmentPayload(
                        cid = cid,
                        aid = aid,
                        segmentIndices = listOf(segmentIndex),
                        expectedSegmentCount = expectedSegmentCount
                    )
                }.getOrNull()
            }
            withContext(Dispatchers.Main) {
                danmakuLoadingSegments.remove(segmentIndex)
                if (!isActiveDanmakuRequest(loadGeneration) || context.currentCid != cid) {
                    PlaybackStartupTrace.log(
                        traceId = context.startupTraceId,
                        startElapsedMs = context.startupTraceStartElapsedMs,
                        step = "danmaku_segment_load_discarded",
                        message = "cid=$cid segment=$segmentIndex currentCid=${context.currentCid} active=${isActiveDanmakuRequest(loadGeneration)}"
                    )
                    return@withContext
                }
                val shouldPublish = publishWhenReady || danmakuPublishPendingSegments.remove(segmentIndex)
                if (payload == null) {
                    PlaybackStartupTrace.log(
                        traceId = context.startupTraceId,
                        startElapsedMs = context.startupTraceStartElapsedMs,
                        step = "danmaku_segment_load_failed",
                        message = "cid=$cid segment=$segmentIndex attempts=2"
                    )
                    return@withContext
                }
                if (payload.regularItems.isEmpty()) {
                    // 真空段（该 6 分钟确实无弹幕）与风控空段在此不可区分，但必须按
                    // "已加载"落账终止重试：否则 onDanmakuSegmentChanged 每次位置回调都会
                    // 重拉同一空段，而网关把 1..64 字节空段判为可疑后每次都 forceUaRefresh
                    // 刷 UA——高频 UA 抖动本身会触发 B 站风控升级，把"局部真空段"放大成
                    // "全会话弹幕断供"（长视频播着播着全没弹幕的根因之一）。
                    // 空段无数据可发布；也不能走 replace 发布路径（会把已发布快照清空）。
                    danmakuSegmentPayloads[segmentIndex] = payload
                    danmakuLoadedSegments.add(segmentIndex)
                    danmakuSegmentCoveredUntilMs[segmentIndex] = segmentIndex.toLong() * DANMAKU_SEGMENT_DURATION_MS
                    if (shouldPublish) {
                        danmakuPublishedSegments.add(segmentIndex)
                    }
                    PlaybackStartupTrace.log(
                        traceId = context.startupTraceId,
                        startElapsedMs = context.startupTraceStartElapsedMs,
                        step = "danmaku_segment_empty_finalized",
                        message = "cid=$cid segment=$segmentIndex publish=$shouldPublish"
                    )
                    return@withContext
                }
                danmakuSegmentPayloads[segmentIndex] = payload
                danmakuLoadedSegments.add(segmentIndex)
                danmakuSegmentCoveredUntilMs[segmentIndex] = segmentIndex.toLong() * DANMAKU_SEGMENT_DURATION_MS
                PlaybackStartupTrace.log(
                    traceId = context.startupTraceId,
                    startElapsedMs = context.startupTraceStartElapsedMs,
                    step = "danmaku_segment_load_ready",
                    message = "cid=$cid segment=$segmentIndex publish=$shouldPublish regular=${payload.regularItems.size}"
                )
                if (shouldPublish) {
                    publishDanmakuSegmentPayload(segmentIndex, payload, replace = replace)
                }
            }
        }
    }

    private fun publishDanmakuSegmentPayload(segmentIndex: Int, payload: SpecialDanmakuPayload, replace: Boolean) {
        if (danmakuPublishedSegments.contains(segmentIndex)) {
            return
        }
        PlaybackStartupTrace.log(
            traceId = context.startupTraceId,
            startElapsedMs = context.startupTraceStartElapsedMs,
            step = "danmaku_segment_published",
            message = "segment=$segmentIndex regular=${payload.regularItems.size} replace=$replace"
        )
        danmakuPublishedSegments.add(segmentIndex)
        if (payload.regularItems.isNotEmpty()) {
            publishDanmaku(payload.regularItems, replace = replace)
        }
    }
}

private val DANMAKU_AVAILABLE_MARKER = listOf(DmModel(content = "__danmaku_available__"))

internal fun danmakuAvailabilityState(hasDanmaku: Boolean): List<DmModel> =
    if (hasDanmaku) DANMAKU_AVAILABLE_MARKER else emptyList()

private class ChunkedDanmakuList private constructor(
    private val chunks: List<List<DmModel>>,
    private val cumulativeSizes: IntArray,
    override val size: Int
) : AbstractList<DmModel>() {

    override fun get(index: Int): DmModel {
        if (index < 0 || index >= size) throw IndexOutOfBoundsException("index=$index size=$size")
        var low = 0
        var high = cumulativeSizes.lastIndex
        while (low < high) {
            val middle = (low + high) ushr 1
            if (index < cumulativeSizes[middle]) high = middle else low = middle + 1
        }
        val previousSize = if (low == 0) 0 else cumulativeSizes[low - 1]
        return chunks[low][index - previousSize]
    }

    override fun iterator(): Iterator<DmModel> = object : Iterator<DmModel> {
        private var chunkIndex = 0
        private var itemIndex = 0

        override fun hasNext(): Boolean = chunkIndex < chunks.size

        override fun next(): DmModel {
            if (!hasNext()) throw NoSuchElementException()
            val item = chunks[chunkIndex][itemIndex++]
            if (itemIndex >= chunks[chunkIndex].size) {
                chunkIndex++
                itemIndex = 0
            }
            return item
        }
    }

    companion object {
        fun append(existing: List<DmModel>, incoming: List<DmModel>): List<DmModel> {
            val chunks = ArrayList<List<DmModel>>(
                (if (existing is ChunkedDanmakuList) existing.chunks.size else 1) + 1
            )
            if (existing is ChunkedDanmakuList) {
                chunks.addAll(existing.chunks)
            } else {
                chunks.add(existing)
            }
            chunks.add(incoming)
            val cumulativeSizes = IntArray(chunks.size)
            var totalSize = 0
            chunks.forEachIndexed { index, chunk ->
                totalSize += chunk.size
                cumulativeSizes[index] = totalSize
            }
            return ChunkedDanmakuList(chunks, cumulativeSizes, totalSize)
        }
    }
}

internal fun mergePublishedDanmakuSnapshot(
    existing: List<DmModel>,
    incoming: List<DmModel>
): List<DmModel> {
    if (incoming.isEmpty()) return existing
    if (existing.isEmpty()) return incoming
    if (existing.last().progress <= incoming.first().progress) {
        return ChunkedDanmakuList.append(existing, incoming)
    }
    val result = ArrayList<DmModel>(existing.size + incoming.size)
    var existingIndex = 0
    var incomingIndex = 0
    while (existingIndex < existing.size && incomingIndex < incoming.size) {
        if (existing[existingIndex].progress <= incoming[incomingIndex].progress) {
            result.add(existing[existingIndex++])
        } else {
            result.add(incoming[incomingIndex++])
        }
    }
    while (existingIndex < existing.size) result.add(existing[existingIndex++])
    while (incomingIndex < incoming.size) result.add(incoming[incomingIndex++])
    return result
}

internal fun shouldAppendDanmakuUpdate(
    previousGeneration: Long?,
    previousSequence: Long?,
    currentGeneration: Long,
    currentSequence: Long,
    replace: Boolean
): Boolean = !replace &&
    previousGeneration == currentGeneration &&
    previousSequence != null &&
    currentSequence == previousSequence + 1L

internal fun shouldResetPublishedDanmakuState(
    queuedGeneration: Long,
    currentGeneration: Long,
    publishedGeneration: Long
): Boolean = queuedGeneration == currentGeneration && publishedGeneration < queuedGeneration
