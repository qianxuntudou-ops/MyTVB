package com.mytvb.feature.player.danmaku

import android.content.Context
import android.graphics.Canvas
import android.os.SystemClock
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import com.mytvb.core.common.log.AppLog
import com.mytvb.feature.player.danmaku.common.BiliDanmakuStyle
import com.mytvb.feature.player.danmaku.emote.DanmakuEmoteRepository
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.Locale

class DanmakuView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val player = DanmakuPlayer(this)

    private var positionProvider: (() -> Long)? = null
    private var isPlayingProvider: (() -> Boolean)? = null
    private var playWhenReadyProvider: (() -> Boolean)? = null
    private var playbackSpeedProvider: (() -> Float)? = null
    private var configProvider: (() -> DanmakuConfig)? = null

    @Volatile private var debugEnabled: Boolean = false
    private val debugStats = DebugStatsCollector()
    private val perfDrawSampleRequested = AtomicBoolean(false)

    @Volatile private var perfLastDrawMs: Float = 0f
    @Volatile private var perfLastDrawAtUptimeMs: Long = 0L

    // draw 滚动窗口统计（主线程调用；随 DanmakuPerf 输出后重置）：
    // 单次采样 perfLastDrawMs 三秒才命中一帧，看不到偶发尖峰；max/over8 用来暴露偶发长帧。
    private var perfDrawMaxMs: Float = 0f
    private var perfDrawOver8Count: Int = 0
    private var perfDrawSumMs: Float = 0f
    private var perfDrawCount: Int = 0

    // 帧级颤动探针（直播"滚动时颤一下"定位用；3s 平均 fps 掩盖单帧异常）：
    // - gap25：相邻两次 onDraw 间隔 ≥25ms 的帧数（单帧拥塞=该帧弹幕位移双倍，肉眼即"颤一下"）
    // - maxGap：窗口内最大帧间隔
    // - jit：|smooth 步进 − 真实帧间隔| ≥8ms 的帧数（时钟跳变/追赶期速度异常，弹幕 x 由 smooth 决定）
    // 两类探针把"颤动"二分为渲染拥塞 vs 时钟跳变；日志关闭时零开销。
    private var perfFrameLastAtUpMs: Long = 0L
    private var perfFrameLastSmoothMs: Long = 0L
    private var perfFrameGapCount: Int = 0
    private var perfFrameGapMaxMs: Long = 0L
    private var perfSmoothJitCount: Int = 0

    private var lastConfig: DanmakuConfig? = null
    private var lastRawPositionMs: Long = 0L
    private var lastPositionChangeUptimeMs: Long = 0L

    @Volatile private var invalidateFull: Boolean = true
    @Volatile private var invalidateTopPx: Int = 0
    @Volatile private var invalidateBottomPx: Int = 0

    // Keep danmaku anchored to the video edge; window insets made the first lane drift on 16:9 TVs.
    private val viewportTopInsetPx: Int = dp(2f)
    private val viewportBottomInsetPx: Int = dp(52f)

    private var lastViewportW: Int = 0
    private var lastViewportH: Int = 0
    private var lastViewportTopInset: Int = 0
    private var lastViewportBottomInset: Int = 0

    private var perfLastLogAtUptimeMs: Long = 0L
    private var perfFramesSinceLog: Int = 0
    private var perfLogPosted: Boolean = false
    private var perfLastFrameUpdates: Long = 0L
    private var perfLastIdleCycles: Long = 0L

    // ===== 自适应渲染帧率（低端设备自动降半频 30fps）=====
    // 实测依据（华为智慧屏 S6，4核1.5GHz）：开弹幕时"每帧 traversal"固定成本 15~30ms
    // 超出 60fps 帧预算，UI 掉到 40fps 上下、弹幕一顿一顿；关弹幕后 UI 立即恢复零慢帧
    // （对照实验 MainThreadJank 3.3条/s → 0.06条/s）。对这类设备把弹幕重绘降到 30fps：
    // traversal 次数减半、每次 33ms 预算充裕；慢速滚动弹幕 30fps 与 60fps 肉眼无差
    // （B 站 web 端弹幕本就是 30fps 渲染）。只降 invalidate 频率，坐标仍逐帧现算不失真。
    // 判定：连续 3 个 3s 窗口实际 fps<45 且绘制持续在发生（≥20fps，排除暂停/空闲）→ 降；
    // 半频下连续 10 个窗口 fps≥26 → 升回 60 并冷却 120s（防边界震荡）。独立于日志开关。
    @Volatile private var adaptiveHalfFps = false
    private var adaptiveLastAdmitUpMs = 0L
    private var adaptiveFrames = 0
    private var adaptiveWindowStartMs = 0L
    private var adaptiveFirstDrawMs = 0L
    private var adaptiveSlowWindows = 0
    private var adaptiveGoodWindows = 0
    private var adaptiveNoEnterBeforeMs = 0L
    private var adaptiveUpFailCount = 0
    private var adaptiveReturnedOnce = false
    private var perfLastIdleWakes: Long = 0L
    private var perfLastIdleResumes: Long = 0L
    private val perfLogRunnable =
        object : Runnable {
            override fun run() {
                if (!isAttachedToWindow) {
                    perfLogPosted = false
                    return
                }
                try {
                    // 日志关闭：本轮直接跳过（provider 读取也不做），
                    // 走 finally 继续维持调度，运行中重新开启日志后下一轮生效。
                    if (!AppLog.isEnabled) return
                    val cfg = runCatching { configProvider?.invoke() }.getOrNull()
                    val rawPos = runCatching { positionProvider?.invoke() }.getOrNull() ?: lastRawPositionMs
                    val isPlaying = runCatching { isPlayingProvider?.invoke() }.getOrNull() ?: false
                    val speed =
                        runCatching { playbackSpeedProvider?.invoke() }.getOrNull()
                            ?.takeIf { it.isFinite() && it > 0f }
                            ?: 1f
                    logPerfIfNeeded(cfg = cfg, rawPos = rawPos, isPlaying = isPlaying, playbackSpeed = speed, force = true)
                } catch (_: Throwable) {
                    // Ignore perf logging failures.
                } finally {
                    // Keep running while attached.
                    postDelayed(this, PERF_LOG_INTERVAL_MS)
                }
            }
        }

    data class DebugStats(
        val viewAttached: Boolean,
        val configEnabled: Boolean,
        val lastPositionMs: Long,
        val drawFps: Float,
        val lastFrameActive: Int,
        val lastFramePending: Int,
        val lastFrameCachedDrawn: Int,
        val lastFrameCacheMissSkipped: Int,
        val lastFrameRequestsActive: Int,
        val lastFrameRequestsPrefetch: Int,
        val cacheItems: Int,
        val renderingItems: Int,
        val queueDepth: Int,
        val poolItems: Int,
        val poolBytes: Long,
        val poolMaxBytes: Long,
        val bitmapCreated: Long,
        val bitmapReused: Long,
        val bitmapPutToPool: Long,
        val bitmapRecycled: Long,
        val bitmapBytes: Long,
        val bitmapMaxBytes: Long,
        val bitmapCount: Int,
        val invalidateFull: Boolean,
        val invalidateTopPx: Int,
        val invalidateBottomPx: Int,
        val updateAvgMs: Float,
        val updateMaxMs: Float,
        val drawAvgMs: Float,
        val drawMaxMs: Float,
    )

    fun setDebugEnabled(enabled: Boolean) {
        if (debugEnabled == enabled) return
        debugEnabled = enabled
        debugStats.reset()
        player.setDebugEnabled(enabled)
    }

    fun getDebugStats(): DebugStats {
        val cfg = configProvider?.invoke() ?: defaultConfig()
        val snap = player.debugSnapshot()
        val p = player.debugState()
        debugStats.lastFrameActive = snap.count
        debugStats.lastFramePending = snap.pendingCount
        debugStats.lastFrameCachedDrawn = p.cachedDrawn
        debugStats.lastFrameCacheMissSkipped = p.cacheMissSkipped
        val now = SystemClock.uptimeMillis()
        return DebugStats(
            viewAttached = isAttachedToWindow,
            configEnabled = cfg.enabled,
            lastPositionMs = lastRawPositionMs,
            drawFps = debugStats.drawFps(now),
            lastFrameActive = debugStats.lastFrameActive,
            lastFramePending = debugStats.lastFramePending,
            lastFrameCachedDrawn = debugStats.lastFrameCachedDrawn,
            lastFrameCacheMissSkipped = debugStats.lastFrameCacheMissSkipped,
            lastFrameRequestsActive = 0,
            lastFrameRequestsPrefetch = 0,
            cacheItems = p.cachedDrawn,
            renderingItems = p.cacheQueueDepth,
            queueDepth = p.cacheQueueDepth,
            poolItems = p.poolCount,
            poolBytes = p.poolBytes,
            poolMaxBytes = p.poolMaxBytes,
            bitmapCreated = p.bitmapCreated,
            bitmapReused = p.bitmapReused,
            bitmapPutToPool = p.bitmapPutToPool,
            bitmapRecycled = p.bitmapRecycled,
            bitmapBytes = p.bitmapBytes,
            bitmapMaxBytes = p.bitmapMaxBytes,
            bitmapCount = p.bitmapCount,
            invalidateFull = invalidateFull,
            invalidateTopPx = invalidateTopPx,
            invalidateBottomPx = invalidateBottomPx,
            updateAvgMs = p.updateAvgMs,
            updateMaxMs = p.updateMaxMs,
            drawAvgMs = debugStats.avgDrawMs(),
            drawMaxMs = debugStats.maxDrawMs(),
        )
    }

    fun setPositionProvider(provider: () -> Long) {
        positionProvider = provider
    }

    fun setIsPlayingProvider(provider: () -> Boolean) {
        isPlayingProvider = provider
    }

    fun setPlayWhenReadyProvider(provider: () -> Boolean) {
        playWhenReadyProvider = provider
    }

    fun setPlaybackSpeedProvider(provider: () -> Float) {
        playbackSpeedProvider = provider
    }

    fun setConfigProvider(provider: () -> DanmakuConfig) {
        configProvider = provider
    }

    fun setDanmakus(list: List<Danmaku>) {
        player.setDanmakus(list)
        invalidate()
    }

    fun appendDanmakus(list: List<Danmaku>, maxItems: Int = 0, alreadySorted: Boolean = false) {
        if (list.isEmpty()) return
        player.appendDanmakus(list, maxItems = maxItems, alreadySorted = alreadySorted)
        invalidate()
    }

    fun replaceDanmakusFrom(minTimeMs: Long, list: List<Danmaku>) {
        player.replaceDanmakusFrom(minTimeMs, list)
        invalidate()
    }

    fun trimToTimeRange(minTimeMs: Long, maxTimeMs: Long) {
        player.trimToTimeRange(minTimeMs, maxTimeMs)
        invalidate()
    }

    fun notifySeek(positionMs: Long) {
        player.seekTo(positionMs)
        lastRawPositionMs = positionMs
        lastPositionChangeUptimeMs = SystemClock.uptimeMillis()
        invalidate()
    }

    /** 漂移监督器（控制器侧三段式对表）使用的时钟治理入口，转发给内部 player。 */
    fun updateDanmakuTimeFactor(factor: Float) {
        player.updateTimeFactor(factor)
    }

    /** 时钟速率环开关：直播开、点播关（详见 DanmakuTimer.rateLoopEnabled）。 */
    fun setRateLoopEnabled(enabled: Boolean) {
        player.setRateLoopEnabled(enabled)
    }

    fun currentDanmakuPositionMs(): Long = player.currentDanmakuPositionMs()

    fun syncDanmakuTimerTo(positionMs: Long) {
        player.syncTimerTo(positionMs)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        AppLog.i(DIAG_TAG, "view ATTACH ${width}x${height}")
        // 表情词典预热（磁盘 + 网络，24h 刷新一次；异步无阻塞）。
        DanmakuEmoteRepository.warmup(context)
        // 新的播放会话：自适应降频状态归零（锁定的半频、失败计数、冷却期全部重置）
        adaptiveHalfFps = false
        adaptiveFrames = 0
        adaptiveWindowStartMs = 0L
        adaptiveFirstDrawMs = 0L
        adaptiveSlowWindows = 0
        adaptiveGoodWindows = 0
        adaptiveNoEnterBeforeMs = 0L
        adaptiveUpFailCount = 0
        adaptiveReturnedOnce = false
        updateViewportIfNeeded()
        startPerfLoggingIfNeeded()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // detach 后引擎被永久 release；若之后同实例重新 attach 而弹幕不再显示，此日志即为现场。
        AppLog.w(DIAG_TAG, "view DETACH ${width}x${height} → player.release() (irreversible)")
        stopPerfLogging()
        player.release()
        debugStats.reset()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateViewportIfNeeded()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        perfFramesSinceLog++
        adaptiveFrames++
        val nowUpMs = SystemClock.uptimeMillis()
        if (adaptiveFirstDrawMs == 0L) adaptiveFirstDrawMs = nowUpMs
        if (adaptiveWindowStartMs == 0L) adaptiveWindowStartMs = nowUpMs
        val adaptiveDeltaMs = nowUpMs - adaptiveWindowStartMs
        if (adaptiveDeltaMs >= 3000L) {
            evaluateAdaptiveFpsWindow(adaptiveFrames, adaptiveDeltaMs, nowUpMs)
            adaptiveFrames = 0
            adaptiveWindowStartMs = nowUpMs
        }
        val sampleDraw = perfDrawSampleRequested.getAndSet(false)
        val measureDraw = debugEnabled || sampleDraw || AppLog.isEnabled
        val drawStartedAtNs = if (measureDraw) System.nanoTime() else 0L

        fun finishDrawSample() {
            if (!measureDraw) return
            val drawMs = ((System.nanoTime() - drawStartedAtNs).coerceAtLeast(0L).toDouble() / 1_000_000.0).toFloat()
            if (debugEnabled) debugStats.recordDraw(nowUptimeMs = SystemClock.uptimeMillis(), drawNs = (drawMs * 1_000_000f).toLong())
            if (sampleDraw) {
                perfLastDrawMs = drawMs
                perfLastDrawAtUptimeMs = SystemClock.uptimeMillis()
            }
            if (AppLog.isEnabled) {
                perfDrawCount++
                perfDrawSumMs += drawMs
                if (drawMs > perfDrawMaxMs) perfDrawMaxMs = drawMs
                if (drawMs > 8f) perfDrawOver8Count++
            }
        }

        val cfg = configProvider?.invoke() ?: defaultConfig()
        if (cfg != lastConfig) {
            lastConfig = cfg
            player.updateConfig(cfg)
        }

        updateViewportIfNeeded()
        updateInvalidateArea(cfg)

        if (!cfg.enabled) {
            player.draw(
                canvas = canvas,
                rawPositionMs = 0L,
                isPlaying = false,
                playWhenReady = false,
                playbackSpeed = 1f,
                config = cfg,
            )
            finishDrawSample()
            return
        }

        val posProvider = positionProvider ?: run {
            finishDrawSample()
            return
        }
        val rawPos = posProvider()
        val now = SystemClock.uptimeMillis()
        if (lastPositionChangeUptimeMs == 0L) lastPositionChangeUptimeMs = now
        if (rawPos != lastRawPositionMs) lastPositionChangeUptimeMs = now
        lastRawPositionMs = rawPos

        val isPlaying =
            runCatching { isPlayingProvider?.invoke() }.getOrNull()
                ?: (now - lastPositionChangeUptimeMs < STOP_WHEN_IDLE_MS)
        // playWhenReady 表示"想播放"（可能正在 buffering）。后台返回时 isPlaying 尚未变 true，
        // 但 playWhenReady 已为 true，用它驱动渲染循环避免弹幕卡死。
        val playWhenReady =
            runCatching { playWhenReadyProvider?.invoke() }.getOrNull()
                ?: isPlaying
        val speed =
            runCatching { playbackSpeedProvider?.invoke() }.getOrNull()
                ?.takeIf { it.isFinite() && it > 0f }
                ?: 1f

        player.draw(
            canvas = canvas,
            rawPositionMs = rawPos,
            isPlaying = isPlaying,
            playWhenReady = playWhenReady,
            playbackSpeed = speed,
            config = cfg,
        )
        if (AppLog.isEnabled) {
            recordFrameJitterProbe(isPlaying = isPlaying)
        }
        finishDrawSample()
    }

    /**
     * 帧级颤动探针（主线程 onDraw 末尾调用，仅在日志开启时工作）：
     * 对比"相邻两次绘制的真实间隔"与"平滑时钟的实际步进"。
     * gap≥25ms 记一次单帧拥塞；|smoothDelta − frameDt|≥8ms 记一次时钟跳变。
     */
    private fun recordFrameJitterProbe(isPlaying: Boolean) {
        val nowUpMs = SystemClock.uptimeMillis()
        val smoothNowMs = player.currentDanmakuPositionMs()
        val lastAt = perfFrameLastAtUpMs
        if (lastAt != 0L && isPlaying) {
            val frameDtMs = nowUpMs - lastAt
            if (frameDtMs > perfFrameGapMaxMs) perfFrameGapMaxMs = frameDtMs
            if (frameDtMs >= 25L) perfFrameGapCount++
            val smoothDtMs = smoothNowMs - perfFrameLastSmoothMs
            if (kotlin.math.abs(smoothDtMs - frameDtMs) >= 8L) perfSmoothJitCount++
        }
        perfFrameLastAtUpMs = nowUpMs
        perfFrameLastSmoothMs = smoothNowMs
    }

    private fun startPerfLoggingIfNeeded() {
        // 周期任务常驻（保证运行中开启日志能生效），重活由 logPerfIfNeeded 的
        // isEnabled 早退挡住：关闭时每 3s 只剩几个 provider 调用，零字符串构建。
        if (perfLogPosted) return
        perfLogPosted = true
        perfLastLogAtUptimeMs = 0L
        perfFramesSinceLog = 0
        perfLastFrameUpdates = 0L
        perfLastIdleCycles = 0L
        perfLastIdleWakes = 0L
        perfLastIdleResumes = 0L
        removeCallbacks(perfLogRunnable)
        post(perfLogRunnable)
    }

    private fun stopPerfLogging() {
        perfLogPosted = false
        removeCallbacks(perfLogRunnable)
    }

    private fun logPerfIfNeeded(
        cfg: DanmakuConfig? = null,
        rawPos: Long = lastRawPositionMs,
        isPlaying: Boolean = false,
        playbackSpeed: Float = 1f,
        force: Boolean = false,
    ) {
        // 日志关闭时零成本跳过（不打快照、不拼字符串、不触发采样请求）。
        if (!AppLog.isEnabled) return
        val now = SystemClock.uptimeMillis()
        val lastAt = perfLastLogAtUptimeMs
        val due = lastAt == 0L || now - lastAt >= PERF_LOG_INTERVAL_MS
        if (!force && !due) return

        val config = cfg ?: (configProvider?.invoke() ?: defaultConfig())
        if (configProvider == null && cfg == null) return

        val deltaMs = if (lastAt == 0L) 0L else (now - lastAt).coerceAtLeast(0L)
        val frames = perfFramesSinceLog.coerceAtLeast(0)
        val fps =
            if (deltaMs > 0L) {
                (frames.toFloat() * 1000f) / deltaMs.toFloat()
            } else {
                0f
            }
        perfLastLogAtUptimeMs = now
        perfFramesSinceLog = 0

        val snap = player.debugSnapshot()
        val p = player.debugState()
        val sample = player.perfSample()
        val actionFrames = (sample.frameUpdates - perfLastFrameUpdates).coerceAtLeast(0L)
        val idleCycles = (sample.idleCycles - perfLastIdleCycles).coerceAtLeast(0L)
        val idleWakes = (sample.idleWakes - perfLastIdleWakes).coerceAtLeast(0L)
        val idleResumes = (sample.idleResumes - perfLastIdleResumes).coerceAtLeast(0L)
        perfLastFrameUpdates = sample.frameUpdates
        perfLastIdleCycles = sample.idleCycles
        perfLastIdleWakes = sample.idleWakes
        perfLastIdleResumes = sample.idleResumes
        val poolMb = p.poolBytes.toDouble() / (1024.0 * 1024.0)
        val poolMaxMb = p.poolMaxBytes.toDouble() / (1024.0 * 1024.0)
        val bitmapMb = p.bitmapBytes.toDouble() / (1024.0 * 1024.0)
        val bitmapMaxMb = p.bitmapMaxBytes.toDouble() / (1024.0 * 1024.0)
        val inv =
            if (invalidateFull) {
                "full"
            } else {
                "${invalidateTopPx}-${invalidateBottomPx}"
            }
        val actAgeMs = if (sample.actAtUptimeMs > 0L) (now - sample.actAtUptimeMs).coerceAtLeast(0L) else -1L
        val drawAgeMs = if (perfLastDrawAtUptimeMs > 0L) (now - perfLastDrawAtUptimeMs).coerceAtLeast(0L) else -1L
        val drawAvgMs = if (perfDrawCount > 0) perfDrawSumMs / perfDrawCount else 0f
        val drawMaxMs = perfDrawMaxMs
        val drawOver8 = perfDrawOver8Count
        val drawTotal = perfDrawCount
        val rt = Runtime.getRuntime()
        val heapUsedMb = (rt.totalMemory() - rt.freeMemory()) / (1024L * 1024L)
        val heapTotalMb = rt.totalMemory() / (1024L * 1024L)

        AppLog.i(
            "DanmakuPerf",
            buildString(320) {
                append("dm=").append(if (config.enabled) "on" else "off")
                append(" play=").append(isPlaying)
                append(" spd=").append(String.format(Locale.US, "%.2f", playbackSpeed))
                append(" raw=").append(rawPos).append("ms")
                append(" smooth=").append(snap.positionMs).append("ms")
                append(" fps=").append(String.format(Locale.US, "%.1f", fps))
                append(" af=").append(actionFrames)
                append(" act=").append(snap.count)
                append(" pend=").append(snap.pendingCount)
                append(" hit=").append(p.cachedDrawn).append('/').append(snap.count)
                append(" skip=").append(p.cacheMissSkipped)
                append(" q=").append(p.cacheQueueDepth)
                append(" pool=").append(String.format(Locale.US, "%.1f", poolMb)).append('/').append(String.format(Locale.US, "%.0f", poolMaxMb)).append("MB")
                append(" bitmap=").append(String.format(Locale.US, "%.1f", bitmapMb)).append('/').append(String.format(Locale.US, "%.0f", bitmapMaxMb)).append("MB#").append(p.bitmapCount)
                append(" actMs=").append(String.format(Locale.US, "%.2f", sample.actMs))
                append(" age=").append(actAgeMs).append("ms")
                append(" drawMs=").append(String.format(Locale.US, "%.2f", perfLastDrawMs))
                append(" drawAvg=").append(String.format(Locale.US, "%.2f", drawAvgMs))
                append(" drawMax=").append(String.format(Locale.US, "%.2f", drawMaxMs))
                append(" over8=").append(drawOver8).append('/').append(drawTotal)
                append(" heap=").append(heapUsedMb).append('/').append(heapTotalMb).append("MB")
                append(" drawAge=").append(drawAgeMs).append("ms")
                append(" idle=").append(idleCycles).append('/').append(idleWakes).append('/').append(idleResumes)
                append(" wake=").append(sample.lastIdleWakeDelayMs).append('/').append(sample.lastIdleWakeLatenessMs).append("ms")
                append(" inv=").append(inv)
                append(" gap25=").append(perfFrameGapCount)
                append('/').append(perfFrameGapMaxMs).append("ms")
                append(" jit=").append(perfSmoothJitCount)
                append(" half=").append(if (adaptiveHalfFps) 1 else 0)
                append(" rate=").append(String.format(Locale.US, "%.3f", player.currentTimerRate()))
            },
        )

        // Ask action thread to sample act cost for the next log interval.
        player.requestPerfSample()
        perfDrawSampleRequested.set(true)
        perfDrawMaxMs = 0f
        perfDrawOver8Count = 0
        perfDrawSumMs = 0f
        perfDrawCount = 0
        perfFrameGapCount = 0
        perfFrameGapMaxMs = 0L
        perfSmoothJitCount = 0
    }

    /** 自适应帧率评估（主线程，onDraw 驱动每 3s 一窗）。规则见字段注释。 */
    private fun evaluateAdaptiveFpsWindow(frames: Int, deltaMs: Long, nowUpMs: Long) {
        val fps = if (deltaMs > 0L) frames * 1000f / deltaMs.toFloat() else 0f
        if (!adaptiveHalfFps) {
            if (nowUpMs < adaptiveNoEnterBeforeMs) return
            // 跳过起播期（弹幕缓存重建、draw miss 多，fps 天然偏低，12s 内不评估）
            if (adaptiveFirstDrawMs == 0L || nowUpMs - adaptiveFirstDrawMs < 12_000L) return
            // 窗口有效 = 绘制持续在发生（≥20fps）：排除暂停/空闲（引擎停转 frames≈0）的误判
            // 触发线 50 而非 45：抖动的 45~50fps（帧间隔 16~33ms 忽长忽短）对匀速滚动弹幕的
            // 观感差于稳定 30fps（4K 场景实测 48fps/janky71% 时弹幕忽快忽慢）；52~58 的
            // 高帧率设备不受影响
            if (frames >= 60 && fps < 50f) {
                adaptiveSlowWindows++
                if (adaptiveSlowWindows >= 3) {
                    // 若 60fps 是试探升回后再次不达标：累计失败；两次即证明该设备跑不动 60fps，
                    // 本次 view 生命周期内锁定半频，不再周期性试探（每次试探的 9 秒都是肉眼可见卡顿）
                    if (adaptiveReturnedOnce) {
                        adaptiveReturnedOnce = false
                        adaptiveUpFailCount++
                        if (adaptiveUpFailCount >= 2) {
                            adaptiveNoEnterBeforeMs = Long.MAX_VALUE
                            AppLog.w("BlblDmDiag", "adaptive_half_fps LOCKED 升回试探${adaptiveUpFailCount}次失败 → 本会话保持30fps")
                        }
                    }
                    adaptiveHalfFps = true
                    adaptiveGoodWindows = 0
                    // 降帧 = 设备实测跑不动 60fps：一次性引导用户可关闭「边看边存」换流畅
                    com.mytvb.feature.player.cache.PlayerMediaCache.maybeHintDisableCacheWrite(context)
                    AppLog.w(
                        "BlblDmDiag",
                        "adaptive_half_fps ON fps=${String.format(Locale.US, "%.1f", fps)} " +
                            "frames=$frames/${deltaMs}ms 连续${adaptiveSlowWindows}窗<50 → 弹幕重绘降30fps",
                    )
                }
            } else {
                adaptiveSlowWindows = 0
            }
        } else {
            // 半频下 fps 应≈30；仍<26 说明设备更弱，维持半频（总比 60fps 好）
            if (fps >= 26f) {
                adaptiveGoodWindows++
                if (adaptiveGoodWindows >= 20) {
                    adaptiveHalfFps = false
                    adaptiveReturnedOnce = true
                    adaptiveSlowWindows = 0
                    adaptiveNoEnterBeforeMs = nowUpMs + 120_000L
                    AppLog.w(
                        "BlblDmDiag",
                        "adaptive_half_fps OFF probe fps=${String.format(Locale.US, "%.1f", fps)} " +
                            "连续${adaptiveGoodWindows}窗稳定 → 试探回升60fps，冷却120s",
                    )
                }
            } else {
                adaptiveGoodWindows = 0
            }
        }
    }

    internal fun invalidateDanmakuAreaOnAnimation() {
        // 半频模式：时间闸门放行（距上次放行 ≥22ms），期间请求丢弃（traversal 减半；
        // 坐标 draw 现算，隔帧画到的是最新位置）。不能用调用奇偶计数——本函数被
        // 主线程（onDraw 自续）与 action 线程（每 vsync 续帧）并发调用，两线程交替
        // ++ 会让某一侧恒放行，拦截完全失效（S65 实测半频后 fps 仍 38）。
        if (adaptiveHalfFps) {
            val nowUp = SystemClock.uptimeMillis()
            if (nowUp - adaptiveLastAdmitUpMs < 22L) return
            adaptiveLastAdmitUpMs = nowUp
        }
        val w = width.coerceAtLeast(0)
        val h = height.coerceAtLeast(0)
        if (w <= 0 || h <= 0) {
            postInvalidateOnAnimation()
            return
        }
        if (invalidateFull) {
            postInvalidateOnAnimation()
            return
        }
        val top = invalidateTopPx.coerceIn(0, h)
        var bottom = invalidateBottomPx.coerceIn(top, h)
        if (bottom <= top) bottom = (top + 1).coerceAtMost(h)
        postInvalidateOnAnimation(0, top, w, bottom)
    }

    private fun updateInvalidateArea(cfg: DanmakuConfig) {
        val w = width.coerceAtLeast(0)
        val h = height.coerceAtLeast(0)
        if (w <= 0 || h <= 0) {
            invalidateFull = true
            invalidateTopPx = 0
            invalidateBottomPx = 0
            return
        }
        val topInsetPx = viewportTopInsetPx
        val bottomInsetPx = viewportBottomInsetPx
        val safeTop = topInsetPx.coerceIn(0, h)
        val safeBottom = bottomInsetPx.coerceIn(0, h - safeTop)
        val availableHeight = (h - safeTop - safeBottom).coerceAtLeast(0)
        val top = safeTop
        val bottomRaw = safeTop + (availableHeight.toFloat() * cfg.area.coerceIn(0f, 1f)).toInt()
        val bottom = bottomRaw.coerceIn(top, h)
        invalidateTopPx = top
        invalidateBottomPx = bottom
        invalidateFull = top <= 0 && bottom >= h
    }

    private fun updateViewportIfNeeded() {
        val w = width.coerceAtLeast(0)
        val h = height.coerceAtLeast(0)
        val top = viewportTopInsetPx
        val bottom = viewportBottomInsetPx
        if (w == lastViewportW && h == lastViewportH && top == lastViewportTopInset && bottom == lastViewportBottomInset) return
        lastViewportW = w
        lastViewportH = h
        lastViewportTopInset = top
        lastViewportBottomInset = bottom
        player.onViewportChanged(width = w, height = h, topInsetPx = top, bottomInsetPx = bottom)
    }

    private fun dp(v: Float): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    private fun defaultConfig(): DanmakuConfig =
        DanmakuConfig(
            enabled = true,
            opacity = BiliDanmakuStyle.DEFAULT_ALPHA_FACTOR,
            textSizeSp = 18f,
            fontWeight = DanmakuFontWeight.Bold,
            strokeWidthPx = BiliDanmakuStyle.strokeWidthForCache(
                textSizePx = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP,
                    18f,
                    resources.displayMetrics
                ),
                fontBorder = 0
            ),
            speedLevel = 4,
            area = 1f,
            laneDensity = DanmakuLaneDensity.Standard,
        )

    private class DebugStatsCollector {
        private val lastDrawAtMs = AtomicLong()
        @Volatile private var smoothedDrawFps: Float = 0f

        private val drawNsTotal = AtomicLong()
        private val drawNsMax = AtomicLong()
        private val drawCount = AtomicLong()

        @Volatile var lastFrameActive: Int = 0
        @Volatile var lastFramePending: Int = 0
        @Volatile var lastFrameCachedDrawn: Int = 0
        @Volatile var lastFrameCacheMissSkipped: Int = 0

        fun reset() {
            lastDrawAtMs.set(0L)
            smoothedDrawFps = 0f
            drawNsTotal.set(0L)
            drawNsMax.set(0L)
            drawCount.set(0L)
            lastFrameActive = 0
            lastFramePending = 0
            lastFrameCachedDrawn = 0
            lastFrameCacheMissSkipped = 0
        }

        fun recordDraw(nowUptimeMs: Long, drawNs: Long) {
            updateDrawFps(nowUptimeMs)
            drawCount.incrementAndGet()
            drawNsTotal.addAndGet(drawNs)
            updateMax(drawNsMax, drawNs)
        }

        fun drawFps(nowUptimeMs: Long): Float {
            val last = lastDrawAtMs.get()
            if (last == 0L) return 0f
            if (nowUptimeMs - last > 1_000L) return 0f
            return smoothedDrawFps
        }

        fun avgDrawMs(): Float {
            val count = drawCount.get().coerceAtLeast(1L)
            val totalNs = drawNsTotal.get().coerceAtLeast(0L)
            return (totalNs.toDouble() / count.toDouble() / 1_000_000.0).toFloat()
        }

        fun maxDrawMs(): Float = (drawNsMax.get().coerceAtLeast(0L).toDouble() / 1_000_000.0).toFloat()

        private fun updateDrawFps(nowUptimeMs: Long) {
            val prev = lastDrawAtMs.getAndSet(nowUptimeMs)
            if (prev == 0L) return
            val deltaMs = nowUptimeMs - prev
            if (deltaMs <= 0L) return
            val inst = 1000f / deltaMs.toFloat()
            val cur = smoothedDrawFps
            smoothedDrawFps = if (cur <= 0f) inst else (cur * 0.85f + inst * 0.15f)
        }

        private fun updateMax(target: AtomicLong, v: Long) {
            while (true) {
                val cur = target.get()
                if (v <= cur) return
                if (target.compareAndSet(cur, v)) return
            }
        }
    }

    private companion object {
        private const val DIAG_TAG = "BlblDmDiag"

        private const val STOP_WHEN_IDLE_MS = 700L
        private const val PERF_LOG_INTERVAL_MS = 3_000L
    }
}
