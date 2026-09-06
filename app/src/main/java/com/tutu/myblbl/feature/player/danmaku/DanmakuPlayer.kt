package com.tutu.myblbl.feature.player.danmaku

import android.graphics.Canvas
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.view.Choreographer
import com.tutu.myblbl.core.common.log.AppLog
import com.tutu.myblbl.feature.player.danmaku.Danmaku
import com.tutu.myblbl.feature.player.danmaku.model.RenderSnapshotStats
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger

/**
 * AkDanmaku-style player loop:
 * - Choreographer drives frame pacing (vsync).
 * - ActionThread does per-frame act/update.
 * - Main thread does draw.
 * - A leased triple-buffer snapshot keeps draw and act independent without reusing an in-flight frame.
 */
internal class DanmakuPlayer(
    private val view: DanmakuView,
) {
    companion object {
        private const val TAG = "DanmakuPlayer"
        private const val DIAG_TAG = "BlblDmDiag"

        /** 设置变化合并窗口：滑条拖动期间只落地最后一次 config。 */
        private const val CONFIG_UPDATE_DEBOUNCE_MS = 120L

        private const val MSG_FRAME_UPDATE = 2101
        private const val MSG_IDLE_WAKE = 2102
        private const val MSG_RESUME_FROM_IDLE = 2103
        private const val MSG_OP_SET = 3101
        private const val MSG_OP_APPEND = 3102
        private const val MSG_OP_TRIM_RANGE = 3103
        private const val MSG_OP_TRIM_MAX = 3104
        private const val MSG_OP_SEEK = 3105
        private const val MSG_OP_CLEAR = 3106
        private const val MSG_OP_REPLACE_FROM = 3107
        private const val MSG_OP_VIEWPORT = 3201
        private const val MSG_OP_CONFIG = 3202
        private const val MSG_OP_CACHE_RESULT = 3203
        private const val MSG_OP_RELEASE = 3999
    }

    private val cacheManager =
        CacheManager(
            appContext = view.context.applicationContext,
            onCacheResult = { result ->
                actionHandler.obtainMessage(MSG_OP_CACHE_RESULT, result).sendToTarget()
            },
        )

    private val engineMain: DanmakuEngineMainApi
    private val engineAction: DanmakuEngineActionApi
    private val timer = DanmakuTimer()

    private val actionThread = HandlerThread("Danmaku-Action").apply { start() }
    private val actionHandler = ActionHandler(actionThread.looper)
    private val frameCallback = FrameCallback(actionHandler)

    private val seekSerial = AtomicInteger(0)
    private val uiFrameId = AtomicInteger(0)

    private val perfSampleRequested = AtomicBoolean(false)
    private val idleWakeDrawRequested = AtomicBoolean(false)
    private val frameUpdateCount = AtomicLong(0L)
    private val idleCycleCount = AtomicLong(0L)
    private val idleWakeCount = AtomicLong(0L)
    private val idleResumeCount = AtomicLong(0L)

    @Volatile
    private var perfLastActMs: Float = 0f

    @Volatile
    private var perfLastActAtUptimeMs: Long = 0L

    @Volatile
    private var lastIdleWakeDelayMs: Long = -1L

    @Volatile
    private var lastIdleWakeLatenessMs: Long = -1L

    @Volatile
    private var debugEnabled: Boolean = false

    private val updateNsTotal = AtomicLong(0L)
    private val updateNsMax = AtomicLong(0L)
    private val updateCount = AtomicLong(0L)

    @Volatile
    private var started: Boolean = false

    @Volatile
    private var released: Boolean = false

    @Volatile
    private var viewportWidth: Int = 0

    @Volatile
    private var viewportHeight: Int = 0

    @Volatile
    private var viewportTopInsetPx: Int = 0

    @Volatile
    private var viewportBottomInsetPx: Int = 0

    @Volatile
    private var latestConfig: DanmakuConfig? = null

    @Volatile
    private var latestPlaybackSpeed: Float = 1f

    // Accessed only on the action thread. The main thread is notified through idleWakeDrawRequested.
    private var frameLoopIdle: Boolean = false
    private var scheduledIdleWakeAtUptimeMs: Long = 0L

    internal fun debugSnapshot(): RenderSnapshotStats = engineMain.renderSnapshotStats()

    private var lastEnabled: Boolean = true

    init {
        val engine =
            DanmakuEngine(
                appContext = view.context.applicationContext,
                displayMetrics = view.resources.displayMetrics,
                cacheManager = cacheManager,
            )
        engineMain = engine
        engineAction = engine
    }

    fun startIfNeeded() {
        if (released) return
        if (started) return
        started = true
        actionHandler.post {
            frameLoopIdle = false
            idleWakeDrawRequested.set(false)
            actionHandler.removeMessages(MSG_IDLE_WAKE)
            postFrameCallback()
        }
        view.postInvalidateOnAnimation()
    }

    fun setDebugEnabled(enabled: Boolean) {
        if (debugEnabled == enabled) return
        debugEnabled = enabled
        updateNsTotal.set(0L)
        updateNsMax.set(0L)
        updateCount.set(0L)
    }

    data class DebugState(
        val updateAvgMs: Float,
        val updateMaxMs: Float,
        val cachedDrawn: Int,
        val cacheMissSkipped: Int,
        val cacheQueueDepth: Int,
        val poolCount: Int,
        val poolBytes: Long,
        val poolMaxBytes: Long,
        val bitmapCreated: Long,
        val bitmapReused: Long,
        val bitmapPutToPool: Long,
        val bitmapRecycled: Long,
        val bitmapBytes: Long,
        val bitmapMaxBytes: Long,
        val bitmapCount: Int,
    )

    fun debugState(): DebugState {
        val count = updateCount.get().coerceAtLeast(1L)
        val avgMs = (updateNsTotal.get().toDouble() / count.toDouble() / 1_000_000.0).toFloat()
        val maxMs = (updateNsMax.get().toDouble() / 1_000_000.0).toFloat()
        val pool = cacheManager.poolSnapshot()
        val stats = cacheManager.statsSnapshot()
        return DebugState(
            updateAvgMs = avgMs,
            updateMaxMs = maxMs,
            cachedDrawn = engineMain.lastDrawCachedCount(),
            cacheMissSkipped = engineMain.lastDrawCacheMissSkippedCount(),
            cacheQueueDepth = cacheManager.queueDepth(),
            poolCount = pool.count,
            poolBytes = pool.bytes,
            poolMaxBytes = pool.maxBytes,
            bitmapCreated = stats.bitmapCreated,
            bitmapReused = stats.bitmapReused,
            bitmapPutToPool = stats.bitmapPutToPool,
            bitmapRecycled = stats.bitmapRecycled,
            bitmapBytes = stats.bitmapBytes,
            bitmapMaxBytes = stats.bitmapMaxBytes,
            bitmapCount = stats.bitmapCount,
        )
    }

    data class PerfSample(
        val actMs: Float,
        val actAtUptimeMs: Long,
        val frameUpdates: Long,
        val idleCycles: Long,
        val idleWakes: Long,
        val idleResumes: Long,
        val lastIdleWakeDelayMs: Long,
        val lastIdleWakeLatenessMs: Long,
    )

    fun requestPerfSample() {
        perfSampleRequested.set(true)
    }

    fun perfSample(): PerfSample =
        PerfSample(
            actMs = perfLastActMs,
            actAtUptimeMs = perfLastActAtUptimeMs,
            frameUpdates = frameUpdateCount.get(),
            idleCycles = idleCycleCount.get(),
            idleWakes = idleWakeCount.get(),
            idleResumes = idleResumeCount.get(),
            lastIdleWakeDelayMs = lastIdleWakeDelayMs,
            lastIdleWakeLatenessMs = lastIdleWakeLatenessMs,
        )

    fun stop() {
        if (!started) return
        started = false
        actionHandler.post {
            frameLoopIdle = false
            idleWakeDrawRequested.set(false)
            actionHandler.removeMessages(MSG_IDLE_WAKE)
            Choreographer.getInstance().removeFrameCallback(frameCallback)
        }
    }

    fun release() {
        if (released) return
        released = true
        started = false
        if (AppLog.isEnabled) {
            AppLog.w(DIAG_TAG, "player RELEASE (engine permanently stopped; view detach/replay requires new instance)")
        }
        runCatching {
            actionHandler.obtainMessage(MSG_OP_RELEASE).sendToTarget()
        }
    }

    fun onViewportChanged(width: Int, height: Int, topInsetPx: Int, bottomInsetPx: Int) {
        viewportWidth = width.coerceAtLeast(0)
        viewportHeight = height.coerceAtLeast(0)
        viewportTopInsetPx = topInsetPx.coerceAtLeast(0)
        viewportBottomInsetPx = bottomInsetPx.coerceAtLeast(0)
        actionHandler.removeMessages(MSG_OP_VIEWPORT)
        actionHandler.sendEmptyMessage(MSG_OP_VIEWPORT)
    }

    fun updateConfig(config: DanmakuConfig) {
        latestConfig = config
        // 防抖：设置面板滑条（字号/透明度）会以 ~60 事件/s 触发 config 变化，
        // 每次样式变化都会全量作废在场缓存并重建。合并 120ms 窗口内的连续更新，
        // 把一次拖动产生的二三十次全量失效压成一两次。released 后消息会被清除，无泄漏。
        actionHandler.removeMessages(MSG_OP_CONFIG)
        actionHandler.sendEmptyMessageDelayed(MSG_OP_CONFIG, CONFIG_UPDATE_DEBOUNCE_MS)
    }

    fun setDanmakus(list: List<Danmaku>) {
        actionHandler.obtainMessage(MSG_OP_SET, list).sendToTarget()
    }

    fun appendDanmakus(list: List<Danmaku>, maxItems: Int, alreadySorted: Boolean) {
        val payload = AppendPayload(list = list, maxItems = maxItems, alreadySorted = alreadySorted)
        actionHandler.obtainMessage(MSG_OP_APPEND, payload).sendToTarget()
    }

    fun replaceDanmakusFrom(minTimeMs: Long, list: List<Danmaku>) {
        actionHandler.obtainMessage(MSG_OP_REPLACE_FROM, ReplaceFromPayload(minTimeMs, list)).sendToTarget()
    }

    fun trimToTimeRange(minTimeMs: Long, maxTimeMs: Long) {
        actionHandler.obtainMessage(MSG_OP_TRIM_RANGE, TrimRangePayload(minTimeMs, maxTimeMs)).sendToTarget()
    }

    fun seekTo(positionMs: Long) {
        // 主线程放行单调高水位（seek 回看是合法回退）。必须与 stepTime 同线程，
        // 否则 action 线程重置与主线程写入交错可能把高水位卡在旧位置。
        engineMain.allowClockBackwardTo(positionMs.coerceAtLeast(0L))
        seekSerial.incrementAndGet()
        actionHandler.obtainMessage(MSG_OP_SEEK, positionMs).sendToTarget()
    }

    /** 漂移监督器软同步：微调平滑时钟推进速率（±5% 量级），不触发任何重锚。 */
    fun updateTimeFactor(factor: Float) {
        timer.softSyncFactor = factor.toDouble()
    }

    /** 引擎当前消费的（已单调钳制的）平滑位置，供漂移监督器与视频位置对表。 */
    fun currentDanmakuPositionMs(): Long = engineAction.currentPositionMs()

    /** 漂移监督器硬同步：轻量移动平滑时钟，不做场景重建（重建由单调钳制吸收回退）。 */
    fun syncTimerTo(positionMs: Long) {
        timer.syncTo(positionMs)
    }

    fun draw(
        canvas: Canvas,
        rawPositionMs: Long,
        isPlaying: Boolean,
        playWhenReady: Boolean,
        playbackSpeed: Float,
        config: DanmakuConfig,
    ) {
        if (released) return
        latestPlaybackSpeed = playbackSpeed
        if (!config.enabled) {
            if (lastEnabled || started) {
                stop()
            }
            if (lastEnabled) {
                requestClear()
            }
            lastEnabled = false
            return
        }
        lastEnabled = true
        // 渲染循环启停只看"是否想播放"：isPlaying（真在解码）或 playWhenReady（想播但可能在 buffering）。
        // 修复 bug2：后台返回时 ExoPlayer 还在 buffering，isPlaying 尚未变 true，
        // 但 playWhenReady 已为 true，此时必须保持渲染循环，否则弹幕卡死直到首帧。
        if (isPlaying || playWhenReady) {
            if (!started) {
                if (AppLog.isEnabled) {
                    AppLog.i(DIAG_TAG, "loop START play=$isPlaying pwr=$playWhenReady pos=${rawPositionMs}ms")
                }
            }
            startIfNeeded()
        } else if (started) {
            // Freeze danmaku on pause: no need to keep 60fps update loop running.
            if (AppLog.isEnabled) {
                AppLog.i(DIAG_TAG, "loop STOP play=$isPlaying pwr=$playWhenReady pos=${rawPositionMs}ms")
            }
            stop()
        }

        val frameId = uiFrameId.incrementAndGet()
        engineMain.drainReleasedBitmaps(frameId)
        val nowNanos = System.nanoTime()
        val smoothPos =
            timer.step(
                nowNanos = nowNanos,
                rawPositionMs = rawPositionMs,
                isPlaying = isPlaying,
                playbackSpeed = playbackSpeed,
                seekSerial = seekSerial.get(),
            )

        engineMain.stepTime(positionMs = smoothPos, uiFrameId = frameId)
        if (idleWakeDrawRequested.compareAndSet(true, false)) {
            actionHandler.sendEmptyMessage(MSG_RESUME_FROM_IDLE)
        }

        val snapshot = engineMain.acquireRenderSnapshot()
        try {
            engineMain.draw(canvas, snapshot, config)
        } finally {
            engineMain.releaseRenderSnapshot(snapshot)
        }
    }

    private fun postFrameCallback() {
        if (released) return
        if (!started) return
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun requestClear() {
        if (released) return
        actionHandler.removeMessages(MSG_OP_CLEAR)
        actionHandler.sendEmptyMessage(MSG_OP_CLEAR)
    }

    private inner class ActionHandler(looper: Looper) : Handler(looper) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MSG_FRAME_UPDATE -> {
                    if (released || !started) return
                    // 先注册下一帧 vsync 回调再执行 act（对齐 blbl.cat3399 的注册顺序）：
                    // act 偶发超过一个 vsync 周期（TV 小核、洪峰测量/轨道分配）时，
                    // 下一帧仍准时回调；此前 act 完成后才注册，act 慢一拍就错过下一个
                    // vsync → 实际帧率减半。慢速弹幕（速度档1，12s 滚一屏）被人眼平滑
                    // 跟踪，帧率不足直接表现为"一顿一顿"。doFrame 内 remove+send 保证
                    // MSG 不重入；进入空闲时在 runFrameUpdate 里注销回调。
                    postFrameCallback()
                    runFrameUpdate()
                }

                MSG_IDLE_WAKE -> {
                    if (released || !started || !frameLoopIdle) return
                    // Let main draw advance DanmakuTimer before action evaluates this timeline point.
                    idleWakeCount.incrementAndGet()
                    lastIdleWakeLatenessMs =
                        if (scheduledIdleWakeAtUptimeMs > 0L) {
                            (SystemClock.uptimeMillis() - scheduledIdleWakeAtUptimeMs).coerceAtLeast(0L)
                        } else {
                            -1L
                        }
                    // lateness 大 = 空闲唤醒迟到（低性能设备定时器/主线程阻塞），
                    // 醒来时播放位置已越过弹幕时刻 → dropIfLagging 可能丢弃。
                    if (AppLog.isEnabled) {
                        AppLog.i(
                            DIAG_TAG,
                            "idle WAKE lateness=${lastIdleWakeLatenessMs}ms pos=${engineAction.currentPositionMs()}ms"
                        )
                    }
                    idleWakeDrawRequested.set(true)
                    view.postInvalidateOnAnimation()
                }

                MSG_RESUME_FROM_IDLE -> {
                    if (released || !started || !frameLoopIdle) return
                    idleResumeCount.incrementAndGet()
                    if (AppLog.isEnabled) {
                        AppLog.i(
                            DIAG_TAG,
                            "idle RESUME pos=${engineAction.currentPositionMs()}ms"
                        )
                    }
                    frameLoopIdle = false
                    // 统一走标准帧入口（MSG_FRAME_UPDATE 开头会注册下一帧 vsync 回调）；
                    // 此前直接 runFrameUpdate，animate=true 分支注册回调的逻辑已上移，
                    // 不经标准入口会导致帧循环只跑一帧就停。
                    sendEmptyMessage(MSG_FRAME_UPDATE)
                }

                MSG_OP_SET -> {
                    @Suppress("UNCHECKED_CAST")
                    engineAction.setDanmakus(msg.obj as? List<Danmaku> ?: emptyList())
                    renderAfterOperation()
                }

                MSG_OP_APPEND -> {
                    val p = msg.obj as? AppendPayload ?: return
                    engineAction.appendDanmakus(p.list, alreadySorted = p.alreadySorted)
                    if (p.maxItems > 0) engineAction.trimToMax(p.maxItems)
                    renderAfterOperation()
                }

                MSG_OP_REPLACE_FROM -> {
                    val p = msg.obj as? ReplaceFromPayload ?: return
                    engineAction.replaceDanmakusFrom(p.minTimeMs, p.list)
                    renderAfterOperation()
                }

                MSG_OP_TRIM_RANGE -> {
                    val p = msg.obj as? TrimRangePayload ?: return
                    engineAction.trimToTimeRange(p.minTimeMs, p.maxTimeMs)
                    renderAfterOperation()
                }

                MSG_OP_SEEK -> {
                    val pos = (msg.obj as? Long) ?: 0L
                    engineAction.seekTo(pos)
                    renderAfterOperation(positionMs = pos)
                }

                MSG_OP_TRIM_MAX -> {
                    val maxItems = msg.arg1
                    engineAction.trimToMax(maxItems)
                    renderAfterOperation()
                }

                MSG_OP_CLEAR -> {
                    engineAction.clear()
                }

                MSG_OP_VIEWPORT -> {
                    engineAction.updateViewport(
                        width = viewportWidth,
                        height = viewportHeight,
                        topInsetPx = viewportTopInsetPx,
                        bottomInsetPx = viewportBottomInsetPx,
                    )
                    // 不附带 seekTo(currentPositionMs)：updateViewport 已置重建标记，下一帧
                    // rebuildScene 自行处理。此前用 currentPositionMs 强制重建会把起播竞态窗口内
                    // 的续播残留位置（如 43625ms）回写引擎，参与"首次进入弹幕卡死"的位置污染。
                    renderAfterOperation()
                }

                MSG_OP_CONFIG -> {
                    latestConfig?.let {
                        engineAction.updateConfig(it)
                        // updateConfig 已置重建标记（requestRebuild("config")），无需额外 seekTo。
                        renderAfterOperation()
                    }
                }

                MSG_OP_CACHE_RESULT -> {
                    val result = msg.obj as? CacheBuildResult ?: return
                    engineAction.applyCacheResult(result)
                    renderAfterOperation()
                }

                MSG_OP_RELEASE -> {
                    removeCallbacksAndMessages(null)
                    Choreographer.getInstance().removeFrameCallback(frameCallback)
                    started = false
                    frameLoopIdle = false
                    idleWakeDrawRequested.set(false)
                    runCatching { actionThread.quitSafely() }
                    engineAction.release()
                    cacheManager.release()
                }
            }
        }

        private fun renderAfterOperation(positionMs: Long? = null) {
            if (released) return
            if (started && frameLoopIdle) {
                frameLoopIdle = false
                idleWakeDrawRequested.set(false)
                removeMessages(MSG_IDLE_WAKE)
                sendEmptyMessage(MSG_FRAME_UPDATE)
                return
            }
            renderOnceIfPaused(positionMs)
        }

        private fun renderOnceIfPaused(positionMs: Long? = null) {
            if (released || started) return
            val pos = positionMs ?: engineAction.currentPositionMs()
            engineAction.stepTime(positionMs = pos, uiFrameId = uiFrameId.get())
            val sampleAct = perfSampleRequested.getAndSet(false)
            val shouldMeasure = debugEnabled || sampleAct
            val t0 = if (shouldMeasure) System.nanoTime() else 0L
            runCatching { engineAction.act() }
            if (shouldMeasure) {
                val t1 = System.nanoTime()
                val ns = (t1 - t0).coerceAtLeast(0L)
                if (debugEnabled) {
                    updateCount.incrementAndGet()
                    updateNsTotal.addAndGet(ns)
                    updateMax(updateNsMax, ns)
                }
                if (sampleAct) {
                    perfLastActMs = (ns.toDouble() / 1_000_000.0).toFloat()
                    perfLastActAtUptimeMs = SystemClock.uptimeMillis()
                }
            }
            view.invalidateDanmakuAreaOnAnimation()
        }

        private fun runFrameUpdate() {
            try {
                engineAction.preAct()
                if (released || !started) return
                frameUpdateCount.incrementAndGet()
                val sampleAct = perfSampleRequested.getAndSet(false)
                val shouldMeasure = debugEnabled || sampleAct
                val t0 = if (shouldMeasure) System.nanoTime() else 0L
                engineAction.act()
                if (shouldMeasure) {
                    val t1 = System.nanoTime()
                    val ns = (t1 - t0).coerceAtLeast(0L)
                    if (debugEnabled) {
                        updateCount.incrementAndGet()
                        updateNsTotal.addAndGet(ns)
                        updateMax(updateNsMax, ns)
                    }
                    if (sampleAct) {
                        perfLastActMs = (ns.toDouble() / 1_000_000.0).toFloat()
                        perfLastActAtUptimeMs = SystemClock.uptimeMillis()
                    }
                }
                view.invalidateDanmakuAreaOnAnimation()
                val schedule = engineAction.frameSchedule()
                if (schedule.animate) {
                    frameLoopIdle = false
                    // 下一帧 vsync 回调已在 MSG_FRAME_UPDATE 开头注册，无需重复。
                } else {
                    frameLoopIdle = true
                    idleCycleCount.incrementAndGet()
                    // 进入空闲：注销 vsync 回调（省电），改用 Handler 定时唤醒。
                    Choreographer.getInstance().removeFrameCallback(frameCallback)
                    schedule.nextWakeAtMs?.let { nextWakeAtMs ->
                        val delayMs = resolveDanmakuIdleWakeDelayMs(
                            nextWakeAtMs = nextWakeAtMs,
                            currentPositionMs = engineAction.currentPositionMs(),
                            playbackSpeed = latestPlaybackSpeed,
                        )
                        lastIdleWakeDelayMs = delayMs
                        scheduledIdleWakeAtUptimeMs = SystemClock.uptimeMillis() + delayMs
                        // 空闲进入：active/pending 全空。若此日志后长时间没有 idle WAKE/RESUME
                        // 而播放仍在推进，弹幕会停更（对应"引擎哑死"现场）。
                        if (AppLog.isEnabled) {
                            AppLog.i(
                                DIAG_TAG,
                                "idle ENTER pos=${engineAction.currentPositionMs()}ms nextWakeAt=${nextWakeAtMs}ms delay=${delayMs}ms"
                            )
                        }
                        removeMessages(MSG_IDLE_WAKE)
                        sendEmptyMessageDelayed(MSG_IDLE_WAKE, delayMs)
                    }
                }
            } catch (ie: InterruptedException) {
                // Ignore.
            } catch (t: Throwable) {
                AppLog.w(TAG, "updateFrame crashed", t)
            }
        }
    }

    private class FrameCallback(
        private val handler: Handler,
    ) : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            handler.removeMessages(MSG_FRAME_UPDATE)
            handler.sendEmptyMessage(MSG_FRAME_UPDATE)
        }
    }

    private fun updateMax(target: AtomicLong, v: Long) {
        while (true) {
            val cur = target.get()
            if (v <= cur) return
            if (target.compareAndSet(cur, v)) return
        }
    }

    private data class AppendPayload(
        val list: List<Danmaku>,
        val maxItems: Int,
        val alreadySorted: Boolean,
    )

    private data class ReplaceFromPayload(
        val minTimeMs: Long,
        val list: List<Danmaku>,
    )

    private data class TrimRangePayload(
        val minTimeMs: Long,
        val maxTimeMs: Long,
    )
}

/**
 * Keep sparse timelines asleep while still rechecking at most once per second
 * for playback-rate changes that were not accompanied by a View invalidation.
 */
internal fun resolveDanmakuIdleWakeDelayMs(
    nextWakeAtMs: Int,
    currentPositionMs: Long,
    playbackSpeed: Float,
    maxRecheckMs: Long = 1_000L,
): Long {
    val mediaDelayMs = (nextWakeAtMs.toLong() - currentPositionMs).coerceAtLeast(0L)
    val speed = playbackSpeed.takeIf { it.isFinite() && it > 0f }?.toDouble() ?: 1.0
    val wallDelayMs = kotlin.math.ceil(mediaDelayMs.toDouble() / speed).toLong()
    return wallDelayMs.coerceIn(1L, maxRecheckMs.coerceAtLeast(1L))
}
