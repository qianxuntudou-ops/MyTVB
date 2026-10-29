package com.mytvb.feature.player

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.Player

class VideoPlayerProgressCoordinator(
    private val playerProvider: () -> Player?,
    private val publishProgressStateProvider: () -> Boolean,
    private val onProgressPublished: (positionMs: Long, durationMs: Long, publishProgressState: Boolean) -> Unit,
    private val onPlaybackPositionChanged: (positionMs: Long) -> Unit,
    private val onPlaybackStalled: (positionMs: Long, stalledMs: Long) -> Unit = { _, _ -> },
    private val onHeartbeatTick: (() -> Unit)? = null
) {
    companion object {
        private const val PROGRESS_POLL_INTERVAL_MS = 500L
        private const val PLAYBACK_STALL_THRESHOLD_MS = 2500L
        private const val PLAYBACK_STALL_RECOVERY_COOLDOWN_MS = 5000L
        private const val PLAYBACK_STALL_BUFFERED_AHEAD_MS = 1000L
        private const val HEARTBEAT_INTERVAL_MS = 2_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastPlaybackSyncPositionMs: Long = -1L
    private var lastPlaybackSyncDurationMs: Long = -1L
    private var lastPublishedProgressPositionMs: Long = -1L
    private var lastPublishedProgressDurationMs: Long = -1L
    private var stallTrackedPositionMs: Long = -1L
    private var stallStartedAtElapsedMs: Long = 0L
    private var lastStallRecoveryAtElapsedMs: Long = 0L
    private var lastHeartbeatAtElapsedMs: Long = 0L

    private val progressRunnable = object : Runnable {
        override fun run() {
            val player = playerProvider() ?: return
            sync(player, publishProgressStateProvider())
            // 心跳维持原语义（只在真正播放时报），避免缓冲中刷观看时长
            if (player.isPlaying) {
                maybeFireHeartbeat()
            }
            if (shouldKeepPolling(player)) {
                handler.postDelayed(this, PROGRESS_POLL_INTERVAL_MS)
            }
        }
    }

    /**
     * 轮询续跑条件。
     *
     * 旧实现只在 [Player.isPlaying] 时续跑，而 isPlaying 在 BUFFERING 下恒为 false，
     * 于是播放中一掉进 BUFFERING，整个卡顿监控（含心跳除外的一切）就地停摆，
     * 唯一的卡顿自救链也随之失效。改为按 playWhenReady 判断，只有暂停/空闲/播完才停。
     */
    private fun shouldKeepPolling(player: Player): Boolean {
        if (!player.playWhenReady) {
            return false
        }
        return when (player.playbackState) {
            Player.STATE_IDLE, Player.STATE_ENDED -> false
            else -> true
        }
    }

    fun restart() {
        handler.removeCallbacks(progressRunnable)
        handler.post(progressRunnable)
    }

    fun stop() {
        handler.removeCallbacks(progressRunnable)
    }

    fun syncNow(publishProgressState: Boolean = true) {
        val player = playerProvider() ?: return
        sync(player, publishProgressState)
    }

    fun reset() {
        lastPlaybackSyncPositionMs = -1L
        lastPlaybackSyncDurationMs = -1L
        lastPublishedProgressPositionMs = -1L
        lastPublishedProgressDurationMs = -1L
        stallTrackedPositionMs = -1L
        stallStartedAtElapsedMs = 0L
        lastStallRecoveryAtElapsedMs = 0L
        lastHeartbeatAtElapsedMs = 0L
    }

    private fun sync(player: Player, publishProgressState: Boolean) {
        val positionMs = player.currentPosition.coerceAtLeast(0L)
        val durationMs = player.duration.takeIf { it > 0L } ?: 0L
        maybeHandlePlaybackStall(player, positionMs, durationMs)
        val playbackChanged =
            positionMs != lastPlaybackSyncPositionMs || durationMs != lastPlaybackSyncDurationMs
        val progressStateChanged =
            positionMs != lastPublishedProgressPositionMs || durationMs != lastPublishedProgressDurationMs

        if (!playbackChanged && (!publishProgressState || !progressStateChanged)) {
            return
        }

        onProgressPublished(positionMs, durationMs, publishProgressState)
        if (publishProgressState) {
            lastPublishedProgressPositionMs = positionMs
            lastPublishedProgressDurationMs = durationMs
        }
        if (!playbackChanged) {
            return
        }
        lastPlaybackSyncPositionMs = positionMs
        lastPlaybackSyncDurationMs = durationMs
        onPlaybackPositionChanged(positionMs)
    }

    private fun maybeHandlePlaybackStall(player: Player, positionMs: Long, durationMs: Long) {
        val shouldMonitor =
            player.playWhenReady &&
                (player.playbackState == Player.STATE_READY || isDecodeStallWhileBuffering(player, positionMs)) &&
                durationMs > 0L &&
                positionMs < durationMs
        if (!shouldMonitor) {
            clearStallTracking(positionMs)
            return
        }

        val nowElapsedMs = SystemClock.elapsedRealtime()
        if (positionMs != stallTrackedPositionMs) {
            stallTrackedPositionMs = positionMs
            stallStartedAtElapsedMs = nowElapsedMs
            return
        }
        if (stallStartedAtElapsedMs == 0L) {
            stallStartedAtElapsedMs = nowElapsedMs
            return
        }
        val stalledMs = nowElapsedMs - stallStartedAtElapsedMs
        val recoveryCooldownMs = nowElapsedMs - lastStallRecoveryAtElapsedMs
        if (
            stalledMs >= PLAYBACK_STALL_THRESHOLD_MS &&
            recoveryCooldownMs >= PLAYBACK_STALL_RECOVERY_COOLDOWN_MS
        ) {
            lastStallRecoveryAtElapsedMs = nowElapsedMs
            onPlaybackStalled(positionMs, stalledMs)
        }
    }

    /**
     * 播放中掉进 BUFFERING 但完全不缺数据 = 解码器停摆，而非网络等待。
     *
     * 实例（TCL t982_ar31a8 / AV1 4K 硬解）：进度停在 9989ms 六分钟不动，
     * buffered 仍有 45s，wlan0 每 5s 仅 2-3KB 下行，cdn_health 全程 err=0。
     * 真缓冲不足时 media3 会给出 isLoading=true 且前方没有缓冲数据，
     * 两个条件任一不成立都不会进入此判据，网络性卡顿仍走既有错误链。
     */
    private fun isDecodeStallWhileBuffering(player: Player, positionMs: Long): Boolean {
        if (player.playbackState != Player.STATE_BUFFERING) {
            return false
        }
        if (positionMs <= 0L) {
            // 起播阶段（位置仍为 0）由 first_frame_timeout 看门狗负责，此处不重复接管
            return false
        }
        if (player.isLoading) {
            return false
        }
        return player.bufferedPosition - positionMs >= PLAYBACK_STALL_BUFFERED_AHEAD_MS
    }

    private fun maybeFireHeartbeat() {
        val callback = onHeartbeatTick ?: return
        val now = SystemClock.elapsedRealtime()
        if (lastHeartbeatAtElapsedMs == 0L) {
            lastHeartbeatAtElapsedMs = now
            return
        }
        if (now - lastHeartbeatAtElapsedMs >= HEARTBEAT_INTERVAL_MS) {
            lastHeartbeatAtElapsedMs = now
            callback.invoke()
        }
    }

    private fun clearStallTracking(positionMs: Long) {
        stallTrackedPositionMs = positionMs
        stallStartedAtElapsedMs = 0L
    }
}
