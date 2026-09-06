package com.tutu.myblbl.feature.player.danmaku

import android.content.Context
import android.os.SystemClock
import com.tutu.myblbl.core.common.ext.isVipColorfulDanmakuAllowed
import com.tutu.myblbl.core.common.log.AppLog
import com.tutu.myblbl.feature.player.DanmakuFilterContext
import com.tutu.myblbl.feature.player.PlaybackStartupTrace
import com.tutu.myblbl.feature.player.danmaku.common.BiliDanmakuFilterPolicy
import com.tutu.myblbl.feature.player.danmaku.common.BiliDanmakuStyle
import com.tutu.myblbl.feature.player.danmaku.common.DanmakuDuplicateMergePolicy
import com.tutu.myblbl.feature.player.danmaku.common.DanmakuController
import com.tutu.myblbl.feature.player.danmaku.common.DanmakuSettingsSnapshot
import com.tutu.myblbl.feature.player.danmaku.common.DanmakuUserFilter
import com.tutu.myblbl.feature.player.danmaku.common.DanmakuUserFilterRepository
import com.tutu.myblbl.feature.player.danmaku.common.LiveDanmakuBatcher
import com.tutu.myblbl.feature.player.danmaku.common.LiveDanmakuController
import com.tutu.myblbl.feature.player.danmaku.common.VipDanmakuTextureCache
import com.tutu.myblbl.feature.player.danmaku.common.nextDanmakuPreparationGeneration
import com.tutu.myblbl.model.dm.DmModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * blbl 弹幕引擎适配控制器（唯一弹幕引擎）。
 *
 * 实现 [DanmakuController]/[LiveDanmakuController]，由
 * [com.tutu.myblbl.feature.player.view.MyPlayerView] 以引擎中立接口驱动。
 *
 * 职责：
 *  - 数据预处理：过滤（[BiliDanmakuFilterPolicy]）+ 合并重复（[DanmakuDuplicateMergePolicy]）
 *    + DmModel→Danmaku 转换（[toDanmakus]）。
 *  - 设置映射：把共享的 [DanmakuSettingsSnapshot] 翻译成引擎的 [DanmakuConfig]。
 *  - 播放同步：通过 positionProvider 回调让引擎自驱动，seek 时主动通知。
 *
 * 不支持：特殊/脚本弹幕、表情/高赞图标。
 * 智能过滤、重复合并和 VIP 渐变为引擎中立实现。
 * 智能防挡由引擎外层的中立宿主统一裁剪。
 */
class BlblDanmakuController(
    private val context: Context,
    private val viewProvider: () -> DanmakuView?
) : DanmakuController, LiveDanmakuController {

    companion object {
        private const val TAG = "BlblDmCtrl"
        /**
         * B站弹幕基准字号。protobuf 协议(DmProtoParser)默认 fontSize=25，绝大多数弹幕都是这个值。
         * 对齐 AkDanmaku 的 clamp(biliFontSize, 12, 25) —— blbl 引擎是全局字号（不读 per-item），
         * 用 25 作基准值才能和 AkDanmaku 视觉一致。
         */
        private const val BILI_BASE_FONT_SIZE = 25f
        private const val DANMAKU_FONT_BORDER_DEFAULT = 0
        private const val LIVE_HISTORY_MAX_ITEMS = 2_000
        private const val LIVE_EMIT_BATCH_MS = 50L
        private const val TAIL_PATCH_MERGE_WINDOW_MS = 2_000

        // ---- 漂移监督器（三段式对表，阈值沿用已移除功能向引擎的线上值）----
        /** 对表周期：电视端 1.5s，软同步收敛速率 ≈ 33ms/s（5% × 1.5s 拍间隔内持续生效）。 */
        private const val DRIFT_SYNC_INTERVAL_MS = 1_500L
        /** 死区：偏差在此以内完全忽略，弹幕按原速走。 */
        private const val DRIFT_NEUTRAL_TOLERANCE_MS = 250L
        /** 硬阈值：超过才考虑硬校准（DanmakuTimer 自身的 2s 硬重锚作为最后保险）。 */
        private const val DRIFT_HARD_SYNC_THRESHOLD_MS = 2_000L
        /** 软同步修正幅度。 */
        private const val DRIFT_SOFT_CORRECTION = 0.05f
        /** 硬校准去抖：连续 N 拍超阈值才执行，排除卡顿期偶发偏差。 */
        private const val DRIFT_HARD_SYNC_DEBOUNCE = 3
    }

    /** 屏幕密度，用于对齐 AkDanmaku 字号公式。 */
    private val density: Float = context.resources.displayMetrics.density.takeIf { it > 0f } ?: 1f

    var playerPositionProvider: (() -> Long)? = null

    private var rawItems: List<DmModel> = emptyList()
    private var filterContext: DanmakuFilterContext = DanmakuFilterContext.EMPTY
    private var appliedFilterContext: DanmakuFilterContext = DanmakuFilterContext.EMPTY
    private var lastSnapshot: DanmakuSettingsSnapshot? = null

    // 缓存的 DanmakuConfig（由 applySettings 计算，通过 configProvider 喂给引擎）
    @Volatile
    private var currentConfig: DanmakuConfig = defaultConfig()

    // isPlaying 状态：用 volatile 字段而不是每次替换 lambda，
    // 避免 notifyPlaybackStateChanged 和 notifyIsPlayingChanged 的事件顺序竞争导致状态错乱。
    @Volatile
    private var isPlaying = false

    // playWhenReady：用户"想播放"的意图，独立于 isPlaying（是否真在解码）。
    // 后台返回恢复时 ExoPlayer 可能还在 buffering，isPlaying 尚未变 true，但 playWhenReady 已为 true。
    // 用它驱动渲染循环，避免弹幕卡死直到首帧。
    @Volatile
    private var playWhenReady = false

    // 真实播放倍速（updatePlaybackSpeed 落地，timer 积分与漂移对表共用）。
    @Volatile
    private var currentPlaybackSpeed: Float = 1f

    // ---- 漂移监督器：三段式对表（沿用已移除功能向引擎的线上策略）----
    // 平滑时钟独立积分后与视频位置的偏差必须被持续治理，否则积累到 DanmakuTimer 的
    // 2s 硬重锚阈值时一步回拉，在屏弹幕位置倒跳重滚（"同一条弹幕再滚一遍"）。
    private var driftSyncJob: Job? = null
    private var consecutiveHardSyncCount = 0

    /**
     * 数据预处理协程作用域：把排序/过滤/合并/转换丢到后台线程，避免阻塞主线程。
     */
    private val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** replace 换代，append 继承当前代际并串行等待，避免连续增量互相作废。 */
    private val prepareGeneration = java.util.concurrent.atomic.AtomicLong(0L)
    private var prepareJob: Job? = null
    private var preloadTextureJob: Job? = null
    private var preloadedVipTextureKeys: Set<String> = emptySet()
    private val liveBatcher = LiveDanmakuBatcher()
    private var liveFlushJob: Job? = null
    private var liveEmissionJob: Job? = null
    private val liveEmissionBuffer = ArrayList<DmModel>(32)
    private var liveMode: Boolean = false
    private var livePaused: Boolean = false

    /**
     * 引擎数据是否已被 stop 清空（切后台）。stop() 置 true；重新喂数据后置 false。
     * 切回前台首帧时若为 true 且 rawItems 非空，用 rawItems 恢复弹幕。
     */
    @Volatile
    private var dataStopped: Boolean = false
    @Volatile
    private var renderingStopped: Boolean = false
    @Volatile
    private var resumeDataRequested: Boolean = false

    init {
        installProviders()
    }

    private fun installProviders() {
        val view = viewProvider() ?: return
        view.setPositionProvider { playerPositionProvider?.invoke()?.coerceAtLeast(0L) ?: 0L }
        view.setIsPlayingProvider { isPlaying }
        view.setPlayWhenReadyProvider { playWhenReady }
        // 平滑时钟按该速率积分；倍速变化由 updatePlaybackSpeed 落地。
        // 此前固定 1f：倍速播放时平滑时钟必然落后视频位置，只能靠漂移监督器反复纠正。
        view.setPlaybackSpeedProvider { currentPlaybackSpeed }
        view.setConfigProvider { currentConfig }
    }

    override fun setData(
        data: List<DmModel>,
        filterContext: DanmakuFilterContext,
        @Suppress("UNUSED_PARAMETER") startupTraceId: String,
        @Suppress("UNUSED_PARAMETER") startupTraceStartElapsedMs: Long
    ) {
        resetLiveState()
        this.filterContext = filterContext
        val taskFilterContext = filterContext
        // 排序 + 过滤/合并/转换丢到后台线程，避免大数据（可达 2 万条）阻塞主线程。
        // 代际校验防止"旧数据处理完时新数据已到"的竞态。
        val generation = nextDanmakuPreparationGeneration(prepareGeneration.get(), replace = true)
        prepareGeneration.set(generation)
        prepareJob?.cancel()
        prepareJob = controllerScope.launch {
            val sorted = data.sortedBy { it.progress }
            val prepared = preprocess(sorted, append = false, filterContext = taskFilterContext)
            withContext(Dispatchers.Main.immediate) {
                if (prepareGeneration.get() != generation) return@withContext
                rawItems = sorted
                appliedFilterContext = taskFilterContext
                if (canInjectPreparedDanmaku(
                        renderingStopped = renderingStopped,
                        restoreStoppedRendering = true,
                        resumeDataRequested = resumeDataRequested
                    ) && renderingStopped
                ) {
                    injectToView(prepared, append = false)
                    renderingStopped = false
                    resumeDataRequested = false
                } else if (renderingStopped) {
                    dataStopped = sorted.isNotEmpty()
                } else {
                    injectToView(prepared, append = false)
                }
            }
        }
    }

    override fun appendData(
        data: List<DmModel>,
        filterContext: DanmakuFilterContext
    ) {
        if (data.isEmpty()) return
        this.filterContext = filterContext
        val taskFilterContext = filterContext
        val previousJob = prepareJob
        val generation = nextDanmakuPreparationGeneration(prepareGeneration.get(), replace = false)
        prepareJob = controllerScope.launch {
            previousJob?.join()
            if (prepareGeneration.get() != generation) return@launch
            // 合并需要读 rawItems，在主线程快照避免并发修改。
            val existing = withContext(Dispatchers.Main.immediate) {
                if (prepareGeneration.get() != generation) return@withContext null
                rawItems to appliedFilterContext
            } ?: return@launch
            val existingItems = existing.first
            val existingFilterContext = existing.second
            val sortedIncoming = if (data.size <= 1) data else data.sortedBy { it.progress }
            val mergeDuplicate = lastSnapshot?.mergeDuplicate ?: true
            val mergeSafe = DanmakuDuplicateMergePolicy.canAppendWithoutRebuildingExisting(
                existingSorted = existingItems,
                incomingSorted = sortedIncoming,
                mergeDuplicate = mergeDuplicate
            )
            val timelineOperation = resolveDanmakuTimelineOperation(
                mergeSafe = mergeSafe,
                hasExistingItems = existingItems.isNotEmpty(),
                existingFilterContext = existingFilterContext,
                incomingFilterContext = taskFilterContext
            )
            val merged = mergeSortedDanmakuModels(existingItems, sortedIncoming, incomingAlreadySorted = true)
            val prepared = preprocess(
                items = if (timelineOperation == DanmakuTimelineOperation.Append) sortedIncoming else merged,
                append = timelineOperation == DanmakuTimelineOperation.Append,
                filterContext = taskFilterContext
            )
            withContext(Dispatchers.Main.immediate) {
                if (prepareGeneration.get() != generation) return@withContext
                rawItems = merged
                appliedFilterContext = taskFilterContext
                if (renderingStopped) {
                    dataStopped = merged.isNotEmpty()
                } else {
                    when (timelineOperation) {
                        DanmakuTimelineOperation.Append -> injectToView(prepared, append = true)
                        DanmakuTimelineOperation.ReplaceFutureTail ->
                            replaceFutureTailInView(
                                danmakus = prepared,
                                firstIncomingTimeMs = sortedIncoming.firstOrNull()?.progress ?: Int.MAX_VALUE,
                            )
                        DanmakuTimelineOperation.Reset -> injectToView(prepared, append = false)
                    }
                }
            }
        }
    }

    override fun startLive() {
        prepareJob?.cancel()
        prepareGeneration.incrementAndGet()
        rawItems = emptyList()
        appliedFilterContext = DanmakuFilterContext.EMPTY
        resetLiveState()
        liveMode = true
        livePaused = false
        renderingStopped = false
        dataStopped = false
        resumeDataRequested = false
        viewProvider()?.setDanmakus(emptyList())
        viewProvider()?.invalidate()
    }

    override fun addLiveDanmaku(dm: DmModel) {
        if (!liveMode) startLive()
        if (livePaused) return
        val snapshot = lastSnapshot ?: return
        if (!snapshot.enabled) return
        val nowMs = SystemClock.uptimeMillis()
        emitLiveDanmakus(
            liveBatcher.offer(
                item = dm,
                nowMs = nowMs,
                mergeEnabled = snapshot.mergeDuplicate,
                displayCapacity = estimateLiveDisplayCapacity(),
            )
        )
        if (snapshot.mergeDuplicate && liveBatcher.pendingCount() > 0) {
            scheduleLiveFlush()
        }
    }

    override fun applySettings(snapshot: DanmakuSettingsSnapshot) {
        if (lastSnapshot == snapshot) return
        val old = lastSnapshot
        lastSnapshot = snapshot
        currentConfig = buildConfig(snapshot)
        viewProvider()?.visibility = if (snapshot.enabled) android.view.View.VISIBLE else android.view.View.GONE
        if (liveMode) {
            if (!snapshot.enabled) {
                clearLiveQueues()
            } else if (old != null && old.mergeDuplicate != snapshot.mergeDuplicate) {
                liveFlushJob?.cancel()
                liveFlushJob = null
                emitLiveDanmakus(
                    liveBatcher.flushAll(
                        nowMs = SystemClock.uptimeMillis(),
                        displayCapacity = estimateLiveDisplayCapacity(),
                    )
                )
            }
        }
        if (old == null) {
            // 首次设置也进入串行重建，避免绕过停播门禁或覆盖在途 append。
            if (rawItems.isNotEmpty() || prepareJob?.isActive == true) rebuildDataForSettings()
            return
        }
        // 字段级 diff：区分"config 级"与"数据级"设置。
        // config 级（alpha/textSize/speed/screenArea/enabled/trackSpacing）只改渲染参数，
        //   引擎 updateConfig 已正确处理——opacity/speed/area 不失效已缓存的文字 bitmap，
        //   仅 textSize/strokeWidth 变化才失效 bitmap（引擎内部按需分帧重建）。
        //   所以这类设置变化只需通过 configProvider 下发新 config，无需重跑过滤/合并/转换。
        // 数据级（allowTop/allowBottom/mergeDuplicate/smartFilterLevel）影响
        //   过滤/合并结果，必须重新预处理已有数据并重新注入，否则开关不生效。
        val dataLevelChanged = old.allowTop != snapshot.allowTop ||
            old.allowBottom != snapshot.allowBottom ||
            old.mergeDuplicate != snapshot.mergeDuplicate ||
            old.smartFilterLevel != snapshot.smartFilterLevel
        if (dataLevelChanged && (rawItems.isNotEmpty() || prepareJob?.isActive == true)) {
            rebuildDataForSettings()
        }
    }

    override fun updatePlaybackSpeed(speed: Float) {
        // 平滑时钟按真实倍速积分（provider 直接读该字段）。滚动时长仍由引擎按
        // durationMs 推进，不受影响；此处只保证时钟积分速率与媒体钟一致。
        currentPlaybackSpeed = speed.takeIf { it.isFinite() && it > 0f } ?: 1f
    }

    override fun notifyPlaybackStateChanged(@Suppress("UNUSED_PARAMETER") playbackState: Int, playWhenReady: Boolean) {
        // playWhenReady 只表达"想播放"（用于渲染循环启停），绝不写入 isPlaying：
        // BUFFERING(pwr=true) 期间媒体时钟冻结，若此时 isPlaying=true，DanmakuTimer 会
        // 按墙上时钟继续积分 → 平滑时钟系统性超前（开播解码预热即注入 +1.2s，卡顿期持续
        // 累积），漂移越过 2s 阈值被硬回拉时在屏弹幕位置倒跳重滚/按超前时钟提前退场。
        // isPlaying 的唯一权威来源是 notifyIsPlayingChanged（ExoPlayer 实际解码中）。
        val wasPlaying = isPlaying
        this.playWhenReady = playWhenReady
        // 播放意图恢复（后台返回/buffering 想播）时主动 invalidate，让渲染循环重启
        // （循环启停看 isPlaying || playWhenReady，时钟推进只看 isPlaying）。
        if (!wasPlaying && playWhenReady) {
            viewProvider()?.invalidate()
        }
        if (playWhenReady) startDriftSync()
    }

    override fun notifyIsPlayingChanged(playing: Boolean) {
        // onIsPlayingChanged 是 ExoPlayer 对"实际解码播放中"的权威信号，优先级高于 playWhenReady。
        val wasPlaying = isPlaying
        isPlaying = playing
        if (!wasPlaying && playing) {
            viewProvider()?.invalidate()
        }
    }

    @Suppress("UNUSED_PARAMETER")
    override fun notifyPlaybackFirstFrame() {
        // 首帧渲染是镜像切换/surface 重建后恢复弹幕的关键时机。
        // 此时 isPlaying 可能还是 false（READY/onIsPlayingChanged 尚未回调），
        // 主动 invalidate 触发一次 onDraw，让引擎有机会重启渲染循环；
        // 同时兜底设 isPlaying=true（首帧出来说明解码器已就绪，弹幕应跟随播放）。
        val wasPlaying = isPlaying
        if (!wasPlaying) {
            playWhenReady = true
            isPlaying = true
        }
        // 修复 bug2：切后台 stop 清空了引擎数据，切回前台首帧时用保留的 rawItems 恢复弹幕。
        if (dataStopped && rawItems.isNotEmpty()) {
            requestDataResume()
        }
        viewProvider()?.invalidate()
    }

    override fun setEnabled(enabled: Boolean) {
        val snap = lastSnapshot ?: return
        applySettings(snap.copy(enabled = enabled))
    }

    override fun pause() {
        playWhenReady = false
        isPlaying = false
        if (liveMode) {
            livePaused = true
            clearLiveQueues()
        }
    }

    override fun resume() {
        val wasPlaying = isPlaying
        // 只表达"想播放"。不写 isPlaying：后台返回后 ExoPlayer 可能还要 buffering 数百毫秒，
        // 此时强行 isPlaying=true 会让平滑时钟在媒体钟冻结期继续积分（漂移注入）。
        // 实际开始推进由 notifyIsPlayingChanged(true) / notifyPlaybackFirstFrame() 兜底。
        playWhenReady = true
        renderingStopped = false
        livePaused = false
        if (dataStopped && rawItems.isNotEmpty()) {
            renderingStopped = true
            requestDataResume()
        }
        if (!wasPlaying) viewProvider()?.invalidate()
        startDriftSync()
    }

    override fun stop() {
        resetLiveState()
        playWhenReady = false
        isPlaying = false
        renderingStopped = true
        resumeDataRequested = false
        stopDriftSync()
        // 仅清引擎 active 数据，保留 rawItems：切后台 stop 后，切回前台播放时
        // notifyIsPlayingChanged/notifyPlaybackFirstFrame 会用 rawItems 重新喂数据恢复弹幕。
        // （此前清 rawItems 导致切后台再回来弹幕永久消失，直到重新播放。）
        viewProvider()?.setDanmakus(emptyList())
        preloadedVipTextureKeys = emptySet()
        dataStopped = rawItems.isNotEmpty()
    }

    override fun resetForPlaybackStart(positionMs: Long) {
        prepareJob?.cancel()
        val generation = nextDanmakuPreparationGeneration(prepareGeneration.get(), replace = true)
        prepareGeneration.set(generation)
        rawItems = emptyList()
        appliedFilterContext = DanmakuFilterContext.EMPTY
        stop()
        renderingStopped = false
        dataStopped = false
        resumeDataRequested = false
        viewProvider()?.notifySeek(positionMs.coerceAtLeast(0L))
    }

    override fun syncPosition(positionMs: Long, forceSeek: Boolean) {
        if (forceSeek) {
            // seek 后通知引擎重建场景（清旧弹幕，从新位置重新分配）
            viewProvider()?.notifySeek(positionMs.coerceAtLeast(0L))
        }
        // 非 seek 时靠 positionProvider 自动跟，无需处理
    }

    /**
     * 漂移监督器：周期比较引擎平滑时钟与视频位置，三段式治理
     * （策略沿用已移除功能向引擎的线上实现）：
     *  1. |drift| ≤ 死区(250ms)：factor=1，弹幕按原速走；
     *  2. 死区 < |drift| ≤ 硬阈值(2000ms)：factor ±5% 软同步，数秒内无感收敛；
     *  3. |drift| > 硬阈值：连续 3 拍去抖确认后按方向硬校准——
     *     - 引擎落后（视频前跳）：走 notifySeek 完整重建，与用户 seek 同路径；
     *     - 引擎超前（卡顿期积分过多）：只轻量移动时钟指针，回退由引擎单调钳制
     *       吸收为"冻结等 raw 追上"，绝不能 notifySeek——回看路径会清防重放历史，
     *       最近一个滚动窗口重放，正是"同一条弹幕再滚一遍"的根因。
     */
    private fun startDriftSync() {
        if (driftSyncJob?.isActive == true) return
        driftSyncJob = controllerScope.launch {
            while (isActive) {
                delay(DRIFT_SYNC_INTERVAL_MS)
                withContext(Dispatchers.Main.immediate) {
                    applyDriftSyncTick()
                }
            }
        }
    }

    private fun stopDriftSync() {
        driftSyncJob?.cancel()
        driftSyncJob = null
        consecutiveHardSyncCount = 0
        viewProvider()?.updateDanmakuTimeFactor(1f)
    }

    private fun applyDriftSyncTick() {
        if (!isPlaying) {
            // 暂停/buffering 期时钟冻结，不会产生新漂移；恢复原速等播放继续。
            consecutiveHardSyncCount = 0
            viewProvider()?.updateDanmakuTimeFactor(1f)
            return
        }
        val view = viewProvider() ?: return
        val videoPos = playerPositionProvider?.invoke()?.coerceAtLeast(0L) ?: return
        val enginePos = view.currentDanmakuPositionMs()
        val signedDrift = enginePos - videoPos
        val absDrift = abs(signedDrift)
        when {
            absDrift > DRIFT_HARD_SYNC_THRESHOLD_MS -> {
                consecutiveHardSyncCount++
                if (consecutiveHardSyncCount >= DRIFT_HARD_SYNC_DEBOUNCE) {
                    AppLog.w(
                        TAG,
                        "drift hard sync engine=${enginePos}ms video=${videoPos}ms " +
                            "delta=${signedDrift}ms backward=${signedDrift > 0}"
                    )
                    consecutiveHardSyncCount = 0
                    view.updateDanmakuTimeFactor(1f)
                    if (signedDrift > 0) {
                        // 引擎超前：轻量校准，回退被引擎单调钳制吸收（在屏弹幕冻结等追上）。
                        view.syncDanmakuTimerTo(videoPos)
                    } else {
                        // 引擎落后：完整 seek 重建（与用户 seek 同路径）。
                        view.notifySeek(videoPos)
                    }
                } else {
                    // 未达去抖阈值：先软纠正过渡，给卡顿恢复留时间，避免偶发偏差直接硬校准。
                    view.updateDanmakuTimeFactor(softCorrectionFor(signedDrift))
                }
            }
            absDrift > DRIFT_NEUTRAL_TOLERANCE_MS -> {
                consecutiveHardSyncCount = 0
                view.updateDanmakuTimeFactor(softCorrectionFor(signedDrift))
            }
            else -> {
                consecutiveHardSyncCount = 0
                view.updateDanmakuTimeFactor(1f)
            }
        }
    }

    /** 引擎超前 → 放慢 5%；落后 → 加快 5%。只作用于 timer 的软同步因子，不触发重锚。 */
    private fun softCorrectionFor(signedDrift: Long): Float =
        if (signedDrift > 0) 1f - DRIFT_SOFT_CORRECTION else 1f + DRIFT_SOFT_CORRECTION

    override fun release() {
        stop()
        stopDriftSync()
        prepareJob?.cancel()
        preloadTextureJob?.cancel()
        controllerScope.cancel()
    }

    private fun scheduleLiveFlush() {
        if (liveFlushJob?.isActive == true) return
        val delayMs = liveBatcher.nextFlushDelayMs(SystemClock.uptimeMillis()) ?: return
        liveFlushJob = controllerScope.launch(Dispatchers.Main.immediate) {
            delay(delayMs)
            liveFlushJob = null
            if (!liveMode) return@launch
            emitLiveDanmakus(
                liveBatcher.flushExpired(
                    nowMs = SystemClock.uptimeMillis(),
                    displayCapacity = estimateLiveDisplayCapacity(),
                )
            )
            scheduleLiveFlush()
        }
    }

    private fun emitLiveDanmakus(items: List<DmModel>) {
        if (items.isEmpty() || !liveMode || !currentConfig.enabled) return
        liveEmissionBuffer.addAll(items)
        if (liveEmissionJob?.isActive == true) return
        liveEmissionJob = controllerScope.launch(Dispatchers.Main.immediate) {
            delay(LIVE_EMIT_BATCH_MS)
            liveEmissionJob = null
            flushLiveEmissionBuffer()
        }
    }

    private fun flushLiveEmissionBuffer() {
        if (liveEmissionBuffer.isEmpty()) return
        if (!liveMode || !currentConfig.enabled) {
            liveEmissionBuffer.clear()
            return
        }
        val positionMs = (playerPositionProvider?.invoke() ?: 0L)
            .coerceAtLeast(0L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        val danmakus = ArrayList<Danmaku>(liveEmissionBuffer.size)
        for (item in liveEmissionBuffer) {
            item.copy(
                progress = positionMs,
                mode = com.tutu.myblbl.feature.player.danmaku.common.DanmakuProtocolMode.ROLLING,
            ).toDanmaku(allowVipColorful = false)?.let(danmakus::add)
        }
        liveEmissionBuffer.clear()
        viewProvider()?.appendDanmakus(
            list = danmakus,
            maxItems = LIVE_HISTORY_MAX_ITEMS,
            alreadySorted = true,
        )
    }

    private fun estimateLiveDisplayCapacity(): Int {
        val visibleHeight = context.resources.displayMetrics.heightPixels * currentConfig.area
        val trackHeight = (currentConfig.textSizeSp * density * currentConfig.trackSpacing.factor)
            .coerceAtLeast(24f)
        val tracks = (visibleHeight / trackHeight).toInt().coerceAtLeast(3)
        return (tracks * 2).coerceIn(6, 160)
    }

    private fun resetLiveState() {
        liveMode = false
        livePaused = false
        clearLiveQueues()
    }

    private fun clearLiveQueues() {
        liveFlushJob?.cancel()
        liveFlushJob = null
        liveEmissionJob?.cancel()
        liveEmissionJob = null
        liveEmissionBuffer.clear()
        liveBatcher.clear()
    }

    /**
     * 切后台 stop 后，切回前台首帧时用保留的 rawItems 恢复弹幕。
     * 对齐 ak 引擎：ak 的 stop 不碰 danmakuTimeline，恢复时靠 rebuildAndApplyData 重建窗口；
     * lite 的 stop 此前会清 rawItems 导致切回后弹幕永久消失，现改为保留 rawItems 并在此恢复。
     */
    private fun resumeDataFromBackground() {
        if (rawItems.isEmpty()) return
        val previousJob = prepareJob
        val generation = nextDanmakuPreparationGeneration(prepareGeneration.get(), replace = false)
        prepareJob = controllerScope.launch {
            previousJob?.join()
            if (prepareGeneration.get() != generation) return@launch
            applyDataToViewAsync(generation, append = false, restoreStoppedRendering = true)
        }
    }

    private fun requestDataResume() {
        if (resumeDataRequested) return
        resumeDataRequested = true
        resumeDataFromBackground()
    }

    private fun rebuildDataForSettings() {
        val previousJob = prepareJob
        val generation = nextDanmakuPreparationGeneration(prepareGeneration.get(), replace = false)
        prepareJob = controllerScope.launch {
            previousJob?.join()
            if (prepareGeneration.get() != generation) return@launch
            applyDataToViewAsync(generation, append = false)
        }
    }

    // ---- 内部 ----

    /**
     * 异步预处理并注入（设置重建和后台恢复走这里）。
     * [generation] 用于代际校验，过期结果丢弃。
     */
    private suspend fun applyDataToViewAsync(
        generation: Long,
        append: Boolean,
        restoreStoppedRendering: Boolean = false
    ) {
        // 快照当前数据（在主线程读，避免与 setData/appendData 并发修改 rawItems）。
        val snapshot = withContext(Dispatchers.Main.immediate) {
            if (prepareGeneration.get() != generation) return@withContext null
            if (rawItems.isEmpty()) return@withContext (emptyList<DmModel>() to filterContext)
            rawItems.toList() to filterContext
        } ?: return
        val snapshotItems = snapshot.first
        val taskFilterContext = snapshot.second
        if (snapshotItems.isEmpty()) {
            withContext(Dispatchers.Main.immediate) {
                if (prepareGeneration.get() != generation) return@withContext
                if (!append) viewProvider()?.setDanmakus(emptyList())
            }
            return
        }
        // 后台线程：过滤 + 合并 + 转换（重活，可能上万条）。
        val prepared = preprocess(snapshotItems, append, taskFilterContext)
        // 回主线程注入引擎（setDanmakus/appendDanmakus 操作 View，必须主线程）。
        withContext(Dispatchers.Main.immediate) {
            if (prepareGeneration.get() != generation) return@withContext
            val canInject = canInjectPreparedDanmaku(
                renderingStopped = renderingStopped,
                restoreStoppedRendering = restoreStoppedRendering,
                resumeDataRequested = resumeDataRequested
            )
            if (restoreStoppedRendering) {
                if (!canInject) return@withContext
                injectToView(prepared, append = false)
                appliedFilterContext = taskFilterContext
                renderingStopped = false
                resumeDataRequested = false
            } else if (!canInject) {
                dataStopped = snapshotItems.isNotEmpty()
            } else {
                injectToView(prepared, append)
                appliedFilterContext = taskFilterContext
            }
        }
    }

    /**
     * 数据预处理：过滤 + 合并重复。纯 CPU 计算，无 View/主线程依赖，可安全在后台线程执行。
     */
    private suspend fun preprocess(
        items: List<DmModel>,
        append: Boolean,
        filterContext: DanmakuFilterContext
    ): List<Danmaku> {
        // 0. 云端用户屏蔽（登录态，B 站账号同步的屏蔽词/正则/拉黑用户）。
        //    非阻塞读取：命中 10min 缓存零开销；未命中返回 EMPTY 并后台刷新（不卡弹幕首帧）。
        val userFilter = runCatching { DanmakuUserFilterRepository.get() }
            .getOrDefault(DanmakuUserFilter.EMPTY)
        // 1. 过滤（复用现有策略，engine 无关）
        val filtered = BiliDanmakuFilterPolicy.apply(
            items = items,
            context = filterContext,
            settings = lastSnapshot,
            stage = if (append) "blbl_append" else "blbl",
            userFilter = userFilter,
        )
        // 2. 合并重复（复用现有策略）
        val mergeDuplicate = lastSnapshot?.mergeDuplicate ?: true
        val prepared = if (mergeDuplicate) DanmakuDuplicateMergePolicy.merge(filtered) else filtered
        if (mergeDuplicate && prepared.size < filtered.size) {
            AppLog.i(
                TAG,
                "merge reduced: filtered=${filtered.size} merged=${prepared.size} " +
                    "dropped=${filtered.size - prepared.size}"
            )
        }
        // 3. 转 Danmaku（读 VIP 渐变开关，关闭时 vipGradient 全 false，走普通路径零开销）
        val allowVipColorful = isVipColorfulDanmakuAllowed()
        val danmakus = prepared.toDanmakus(allowVipColorful = allowVipColorful)
        scheduleVipTexturePreload(danmakus)
        return danmakus
    }

    private fun scheduleVipTexturePreload(danmakus: List<Danmaku>) {
        val styles = danmakus.asSequence()
            .filter { it.vipGradient }
            .map { it.vipGradientStyle }
            .filter { it.hasTexture }
            .distinct()
            .toList()
        if (styles.isEmpty()) return
        val keys = styles.mapTo(LinkedHashSet()) { it.textureKey }
        val missingKeys = keys - preloadedVipTextureKeys
        if (missingKeys.isEmpty()) return
        val missingStyles = styles.filter { it.textureKey in missingKeys }
        val generation = prepareGeneration.get()
        preloadTextureJob?.cancel()
        preloadTextureJob = controllerScope.launch(Dispatchers.IO) {
            VipDanmakuTextureCache.preloadStyles(missingStyles)
            withContext(Dispatchers.Main.immediate) {
                if (prepareGeneration.get() != generation) return@withContext
                // 仅记录已预加载的纹理，不重跑 applyDataToView()。
                // 弹幕在首次 injectToView 时已注入引擎，渲染层会按需查询纹理缓存
                // （未命中走降级渲染），重跑只会把整批弹幕再注入一遍，导致每条弹幕
                // 在两个轨道上重复出现。
                preloadedVipTextureKeys = preloadedVipTextureKeys + missingKeys
            }
        }
    }

    /** 把预处理结果注入引擎（操作 View，必须在主线程调用）。 */
    private fun injectToView(danmakus: List<Danmaku>, append: Boolean) {
        val view = viewProvider() ?: return
        if (append) {
            view.appendDanmakus(danmakus, alreadySorted = true)
        } else {
            view.setDanmakus(danmakus)
        }
        // 数据已重新注入引擎，清除"切后台 stop"标记。
        dataStopped = false
        AppLog.i(
            TAG,
            "applied danmakus=${danmakus.size} merge=${lastSnapshot?.mergeDuplicate ?: true} append=$append"
        )
    }

    /**
     * 跨批去重需要重算旧尾部时，只替换尚未进入播放窗口的时间线后缀。
     * 当前/即将显示的条目保持原样，避免数据合并把正在滚动的弹幕重置或改写。
     */
    private fun replaceFutureTailInView(danmakus: List<Danmaku>, firstIncomingTimeMs: Int) {
        val currentPositionMs = playerPositionProvider?.invoke()?.coerceAtLeast(0L) ?: 0L
        val replaceFromMs = resolveDanmakuTailPatchStartMs(
            firstIncomingTimeMs = firstIncomingTimeMs,
            currentPositionMs = currentPositionMs,
            mergeWindowMs = TAIL_PATCH_MERGE_WINDOW_MS,
            activeGuardMs = rollingDurationMsForTailPatch(currentConfig.speedLevel),
        )
        val replacement = danmakus.filter { it.timeMs >= replaceFromMs }
        viewProvider()?.replaceDanmakusFrom(replaceFromMs.toLong(), replacement)
        AppLog.i(
            TAG,
            "replace tail from=${replaceFromMs}ms incoming=$firstIncomingTimeMs prepared=${danmakus.size} replacement=${replacement.size}",
        )
    }


    private fun buildConfig(snapshot: DanmakuSettingsSnapshot): DanmakuConfig {
        // 字号对齐 AkDanmaku SimpleRenderer.updatePaint 公式：
        //   AkDanmaku textSizePx = clamp(biliFontSize, 12, 25) × (density - 0.6) × textSizeScale
        // blbl 引擎内部 textSizePx = sp(textSizeSp) = textSizeSp × density
        // 反推: textSizeSp = AkDanmakuPx / density
        val textSizeScale = snapshot.textSize.toBlblTextScale()
        val akDanmakuPx = BILI_BASE_FONT_SIZE * (density - 0.6f).coerceAtLeast(0.4f) * textSizeScale
        val textSizeSp = (akDanmakuPx / density).coerceAtLeast(1f)

        val strokeWidthPx = BiliDanmakuStyle.strokeWidthForCache(
            textSizePx = akDanmakuPx,
            fontBorder = DANMAKU_FONT_BORDER_DEFAULT
        )

        return DanmakuConfig(
            enabled = snapshot.enabled,
            opacity = snapshot.alpha.coerceIn(0.1f, 1f),
            textSizeSp = textSizeSp,
            // 对齐 akdanmaku：DanmakuConfig.bold 默认 true → Typeface.DEFAULT_BOLD。
            // （原注释误写为"bold=false"，与 DanmakuConfig.kt:106 实际默认值矛盾，已修正。）
            fontWeight = DanmakuFontWeight.Bold,
            strokeWidthPx = strokeWidthPx,
            speedLevel = snapshot.speed.toBlblSpeedLevel(),
            area = snapshot.screenArea.toBlblArea(),
            laneDensity = DanmakuLaneDensity.Standard,
            trackSpacing = DanmakuTrackSpacing.fromPrefValue(snapshot.trackSpacing),
        )
    }

    private fun defaultConfig(): DanmakuConfig =
        DanmakuConfig(
            enabled = true,
            opacity = BiliDanmakuStyle.DEFAULT_ALPHA_FACTOR,
            textSizeSp = 18f,
            fontWeight = DanmakuFontWeight.Bold,
            strokeWidthPx = BiliDanmakuStyle.strokeWidthForCache(
                textSizePx = 18f * density,
                fontBorder = DANMAKU_FONT_BORDER_DEFAULT
            ),
            speedLevel = 4,
            area = 0.5f,
            laneDensity = DanmakuLaneDensity.Standard,
            trackSpacing = DanmakuTrackSpacing.DEFAULT,
        )

    /**
     * 设置面板 textSize(30-55) → textSizeScale。
     *
     * 映射表沿用已移除功能向引擎的 toDanmakuTextScale，保证档位视觉不变。
     */
    private fun Int.toBlblTextScale(): Float = when (this) {
        30 -> 0.55f; 31 -> 0.6f; 32 -> 0.65f; 33 -> 0.7f; 34 -> 0.75f
        35 -> 0.8f; 36 -> 0.85f; 37 -> 0.9f; 38 -> 0.95f; 39 -> 1.0f
        40 -> 1.14f; 41 -> 1.3f; 42 -> 1.4f; 43 -> 1.5f; 44 -> 1.6f
        45 -> 1.7f; 46 -> 1.8f; 47 -> 2.0f; 48 -> 2.1f; 49 -> 2.2f
        50 -> 2.3f; 51 -> 2.4f; 52 -> 2.5f; 53 -> 2.6f; 54 -> 2.7f; 55 -> 2.8f
        else -> 1.14f
    }

    /** speed(1-9) → blbl speedLevel(1-10)。直接对应，9 以上不动（不使用 level 10）。 */
    private fun Int.toBlblSpeedLevel(): Int = coerceIn(1, 10)

    /**
     * screenArea → blbl area(0-1)。
     * 映射沿用已移除功能向引擎的 toDanmakuScreenPart。
     */
    private fun Int.toBlblArea(): Float = when (this) {
        -1 -> 1f / 8f
        0 -> 0.16f
        1 -> 1f / 4f
        3 -> 1f / 2f
        7 -> 3f / 4f
        else -> 1f
    }
}

/** 归并两条弹幕时间线；existing 必须已按 progress 升序。 */
internal fun mergeSortedDanmakuModels(
    existing: List<DmModel>,
    incoming: List<DmModel>,
    incomingAlreadySorted: Boolean = false
): List<DmModel> {
    if (incoming.isEmpty()) return existing
    if (existing.isEmpty()) return incoming.sortedBy { it.progress }
    val sortedIncoming = if (incomingAlreadySorted || incoming.size <= 1) incoming else incoming.sortedBy { it.progress }
    if (existing.last().progress <= sortedIncoming.first().progress) {
        return BlblChunkedDmList.append(existing, sortedIncoming)
    }
    val result = ArrayList<DmModel>(existing.size + sortedIncoming.size)
    var i = 0
    var j = 0
    while (i < existing.size && j < sortedIncoming.size) {
        if (existing[i].progress <= sortedIncoming[j].progress) {
            result.add(existing[i++])
        } else {
            result.add(sortedIncoming[j++])
        }
    }
    while (i < existing.size) result.add(existing[i++])
    while (j < sortedIncoming.size) result.add(sortedIncoming[j++])
    return result
}

internal enum class DanmakuTimelineOperation {
    Reset,
    Append,
    ReplaceFutureTail,
}

internal fun resolveDanmakuTimelineOperation(
    mergeSafe: Boolean,
    hasExistingItems: Boolean,
    existingFilterContext: DanmakuFilterContext,
    incomingFilterContext: DanmakuFilterContext,
): DanmakuTimelineOperation =
    when {
        !hasExistingItems || existingFilterContext != incomingFilterContext -> DanmakuTimelineOperation.Reset
        mergeSafe -> DanmakuTimelineOperation.Append
        else -> DanmakuTimelineOperation.ReplaceFutureTail
    }

internal fun canAppendPreparedDanmakuIncrementally(
    mergeSafe: Boolean,
    existingFilterContext: DanmakuFilterContext,
    incomingFilterContext: DanmakuFilterContext,
): Boolean =
    resolveDanmakuTimelineOperation(
        mergeSafe = mergeSafe,
        hasExistingItems = true,
        existingFilterContext = existingFilterContext,
        incomingFilterContext = incomingFilterContext,
    ) == DanmakuTimelineOperation.Append

/** Returns the earliest future point that a cross-batch merge may safely rewrite. */
internal fun resolveDanmakuTailPatchStartMs(
    firstIncomingTimeMs: Int,
    currentPositionMs: Long,
    mergeWindowMs: Int = 2_000,
    activeGuardMs: Long = 6_000L,
): Int {
    val mergeBoundary = (firstIncomingTimeMs.toLong() - mergeWindowMs).coerceAtLeast(0L)
    val activeBoundary = currentPositionMs.coerceAtLeast(0L) + activeGuardMs.coerceAtLeast(0L)
    return maxOf(mergeBoundary, activeBoundary).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

/** Keep the tail patch outside the longest rolling lifetime for the active speed setting. */
internal fun rollingDurationMsForTailPatch(speedLevel: Int): Long =
    when (speedLevel.coerceIn(1, 10)) {
        1 -> 12_000L
        2 -> 10_200L
        3 -> 8_400L
        4, 5 -> 6_000L
        6 -> 4_800L
        7 -> 3_840L
        8 -> 3_000L
        else -> 2_160L
    }

internal fun canInjectPreparedDanmaku(
    renderingStopped: Boolean,
    restoreStoppedRendering: Boolean,
    resumeDataRequested: Boolean
): Boolean = !renderingStopped || (restoreStoppedRendering && resumeDataRequested)

private class BlblChunkedDmList private constructor(
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

    override fun iterator(): Iterator<DmModel> = chunks.asSequence().flatten().iterator()

    companion object {
        fun append(existing: List<DmModel>, incoming: List<DmModel>): List<DmModel> {
            val chunks = ArrayList<List<DmModel>>(
                (if (existing is BlblChunkedDmList) existing.chunks.size else 1) + 1
            )
            if (existing is BlblChunkedDmList) chunks.addAll(existing.chunks) else chunks.add(existing)
            chunks.add(incoming)
            val cumulativeSizes = IntArray(chunks.size)
            var totalSize = 0
            chunks.forEachIndexed { index, chunk ->
                totalSize += chunk.size
                cumulativeSizes[index] = totalSize
            }
            return BlblChunkedDmList(chunks, cumulativeSizes, totalSize)
        }
    }
}
