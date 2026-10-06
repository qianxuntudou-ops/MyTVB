package com.mytvb.feature.player

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.exoplayer.ExoPlayer

class SeekSession(
    private val coordinator: PlaybackUiCoordinator,
    private val playerProvider: () -> ExoPlayer?,
    private val seekPreviewRenderer: (targetPositionMs: Long, durationMs: Long) -> Unit,
    private val danmakuSync: (Long) -> Unit,
    private val holdSeekOverlayRenderer: ((targetPositionMs: Long, durationMs: Long, deltaMs: Long) -> Unit)? = null
) {
    enum class Mode {
        NONE,
        TAP,
        HOLD,
        SWIPE,
        DOUBLE_TAP
    }

    private val handler = Handler(Looper.getMainLooper())
    private var mode = Mode.NONE
    private var isForward = true
    private var seekMs = 10_000L

    // Hold seek state: preview-only with progressive acceleration
    private var targetPositionMs = 0L
    private var holdStartPositionMs = 0L
    private var startMs = 0L
    private var tickRunnable: Runnable? = null

    // Swipe seek state
    private var swipeStartPositionMs = 0L
    private var swipeTargetPositionMs = 0L

    fun startTapSeek(forward: Boolean, seekMs: Long) {
        val player = playerProvider() ?: return
        if (player.playbackState == androidx.media3.common.Player.STATE_ENDED ||
            player.playbackState == androidx.media3.common.Player.STATE_IDLE
        ) return
        if (!player.isCurrentMediaItemSeekable || player.duration <= 0L) return

        mode = Mode.TAP
        isForward = forward
        this.seekMs = seekMs
        val deltaMs = seekMs * if (forward) 1 else -1
        val targetMs = (player.currentPosition + deltaMs).coerceIn(0L, player.duration)
        player.seekTo(targetMs)
        danmakuSync(targetMs)
        coordinator.updateSeekPreview(targetMs, player.duration)
        coordinator.transition(UiEvent.SeekStarted)
        coordinator.transition(UiEvent.SeekTypeChanged(SeekType.TAP))
        seekPreviewRenderer(targetMs, player.duration)
        mode = Mode.NONE
        coordinator.transition(UiEvent.SeekFinished)
    }

    fun startHoldSeek(forward: Boolean) {
        val player = playerProvider() ?: return
        if (player.playbackState == androidx.media3.common.Player.STATE_ENDED ||
            player.playbackState == androidx.media3.common.Player.STATE_IDLE
        ) return
        if (!player.isCurrentMediaItemSeekable || player.duration <= 0L) return

        mode = Mode.HOLD
        isForward = forward
        startMs = 0L
        targetPositionMs = 0L
        holdStartPositionMs = player.currentPosition

        coordinator.transition(UiEvent.SeekStarted)
        coordinator.transition(UiEvent.SeekTypeChanged(SeekType.HOLD))
    }

    fun beginHoldTickLoop() {
        if (mode != Mode.HOLD) return
        cancelTickRunnable()
        // 首跳不立即执行：先按 startHoldSeek 时记录的位置渲染预览，40ms 后才开始推进
        // （对齐 blbl startHoldScrubSeek 的手感：起手画面静止，不凭空多跳一格）
        scheduleNextTick()
    }

    fun startSwipeSeek(startPositionMs: Long) {
        mode = Mode.SWIPE
        swipeStartPositionMs = startPositionMs
        swipeTargetPositionMs = startPositionMs

        coordinator.transition(UiEvent.SeekStarted)
        coordinator.transition(UiEvent.SeekTypeChanged(SeekType.SWIPE))
    }

    fun updateSwipeTarget(deltaX: Float, width: Float, durationMs: Long) {
        if (mode != Mode.SWIPE) return
        val offsetRatio = (deltaX / width.coerceAtLeast(1f)).coerceIn(-1f, 1f)
        swipeTargetPositionMs = (swipeStartPositionMs + (durationMs * offsetRatio).toLong())
            .coerceIn(0L, durationMs)
        coordinator.updateSeekPreview(swipeTargetPositionMs, durationMs)
        seekPreviewRenderer(swipeTargetPositionMs, durationMs)
    }

    fun commitSwipe() {
        if (mode != Mode.SWIPE) return
        val player = playerProvider() ?: return
        player.seekTo(swipeTargetPositionMs)
        danmakuSync(swipeTargetPositionMs)
        mode = Mode.NONE
        coordinator.transition(UiEvent.SeekFinished)
    }

    fun changeDirection(forward: Boolean) {
        if (mode == Mode.HOLD) {
            cancelTickRunnable()
            isForward = forward
            // Reset acceleration curve without clearing targetPositionMs.
            // Setting startMs to a non-zero value prevents doTick() from
            // re-reading player.currentPosition and losing the preview position.
            startMs = SystemClock.uptimeMillis()
            doTick()
            scheduleNextTick()
        }
    }

    fun finishSeek() {
        cancelTickRunnable()
        if (mode == Mode.HOLD) {
            val player = playerProvider()
            if (player != null && player.isCurrentMediaItemSeekable) {
                player.seekTo(targetPositionMs)
                danmakuSync(targetPositionMs)
            }
        }
        mode = Mode.NONE
        coordinator.transition(UiEvent.SeekFinished)
    }

    fun cancel() {
        cancelTickRunnable()
        mode = Mode.NONE
        coordinator.transition(UiEvent.SeekCancelled)
    }

    /** Reset mode to NONE without emitting coordinator events (e.g. for tap accumulation). */
    fun resetSilently() {
        cancelTickRunnable()
        mode = Mode.NONE
    }

    fun isActive(): Boolean = mode != Mode.NONE

    fun isSeeking(): Boolean = mode != Mode.NONE

    fun isForwardDirection(): Boolean = isForward

    private fun doTick() {
        val player = playerProvider() ?: return
        val duration = player.duration
        if (duration <= 0L || !player.isCurrentMediaItemSeekable) return

        if (startMs == 0L) {
            targetPositionMs = player.currentPosition
            holdStartPositionMs = player.currentPosition
            startMs = SystemClock.uptimeMillis()
        }

        val step = holdScrubStepMs(durationMs = duration, tickMs = HOLD_SCRUB_TICK_MS) *
            if (isForward) 1 else -1
        targetPositionMs = (targetPositionMs + step).coerceIn(0L, duration)

        val deltaMs = targetPositionMs - holdStartPositionMs
        coordinator.updateSeekPreview(targetPositionMs, duration)
        seekPreviewRenderer(targetPositionMs, duration)
        holdSeekOverlayRenderer?.invoke(targetPositionMs, duration, deltaMs)
    }

    /**
     * 长按拖动步长按片长自适应：长按 HOLD_SCRUB_TRAVERSE_MS 走完全片。
     * 30 分钟剧约 7.2s/tick（180s/s），2 小时电影约 28.8s/tick——片越长单步越远，
     * 走完整个时间轴所需的长按时长恒定（blbl 同款模型，替代原"固定 10s 步长 +
     * 200/120/60/30ms 加速曲线"的末端过猛问题）。
     */
    private fun holdScrubStepMs(durationMs: Long, tickMs: Long): Long {
        val duration = durationMs.coerceAtLeast(0L)
        if (duration <= 0L) return 0L
        val step = (duration.toDouble() * tickMs.toDouble() / HOLD_SCRUB_TRAVERSE_MS.toDouble())
        return kotlin.math.round(step).toLong().coerceAtLeast(1L)
    }

    private fun scheduleNextTick() {
        cancelTickRunnable()
        val interval = getIntervalMs()
        val runnable = Runnable {
            if (mode == Mode.HOLD) {
                doTick()
                scheduleNextTick()
            }
        }
        tickRunnable = runnable
        handler.postDelayed(runnable, interval)
    }

    private fun getIntervalMs(): Long = HOLD_SCRUB_TICK_MS

    private fun cancelTickRunnable() {
        tickRunnable?.let { handler.removeCallbacks(it) }
        tickRunnable = null
    }

    companion object {
        /** 长按拖动 tick 间隔，恒定不加速 */
        private const val HOLD_SCRUB_TICK_MS = 40L

        /** 长按走完全片所需时长（10 秒 traverse，blbl 默认值） */
        private const val HOLD_SCRUB_TRAVERSE_MS = 10_000L
    }
}
