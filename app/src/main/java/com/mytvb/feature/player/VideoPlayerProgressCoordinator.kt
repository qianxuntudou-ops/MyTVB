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
    private val onHeartbeatTick: (() -> Unit)? = null,
    private val onNetworkStalled: (positionMs: Long, stalledMs: Long) -> Unit = { _, _ -> }
) {
    companion object {
        private const val PROGRESS_POLL_INTERVAL_MS = 500L
        private const val PLAYBACK_STALL_THRESHOLD_MS = 2500L
        private const val PLAYBACK_STALL_RECOVERY_COOLDOWN_MS = 5000L
        private const val PLAYBACK_STALL_BUFFERED_AHEAD_MS = 1000L
        private const val HEARTBEAT_INTERVAL_MS = 2_000L

        // ===== 网络性卡顿救援判据（issue#114）=====
        // 症状形态："卡 2 秒 → 播几秒 → 再卡 2~5 秒"的高频短 rebuffer 循环——单次卡顿
        // 永远达不到既有 stall 阈值，且每次都自行恢复到不了错误链，两条旧救援都够不着。
        // 病理 = 吞吐长期低于码率：每次"恢复"只是攒下 2~3 秒缓冲又立刻耗光，等是等不好的。
        // v2.1.26 实测标定：水位 10s 生效后形态变为"2~4 分钟卡一次 3~5.6s"，首版阈值
        // （3次/60s、12s单次）全部够不着零触发——放宽为 2次/90s + 单次7s。
        private const val NETWORK_STALL_WINDOW_MS = 90_000L
        // 判据一(频次)：窗口内 rebuffer ≥2 次（实测 21s 间隔双卡形态命中）
        private const val NETWORK_STALL_EVENT_COUNT = 2
        // 判据二(累计)：窗口内 rebuffer 累计 ≥10s——低频长卡的特征指纹
        private const val NETWORK_STALL_TOTAL_MS = 10_000L
        // 判据三(单次)：单次网络性 BUFFERING ≥7s 无恢复——卡 7 秒还在等就是干耗，
        // 换线重建代价仅 ~1.5s，越早换越划算（实测最长单次 5.6s 恰在旧阈值 12s 之下漏网）
        private const val NETWORK_STALL_SINGLE_MS = 7_000L
        // 救援冷却：换线重建后给新节点攒水位的时间，窗口期内不重复触发
        private const val NETWORK_STALL_RESCUE_COOLDOWN_MS = 20_000L
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

    // ===== 网络性卡顿监控状态 =====
    private data class RebufferEvent(val endedAtElapsedMs: Long, val durationMs: Long)
    private var lastObservedPlaybackState = Player.STATE_IDLE
    private var networkRebufferStartedAtMs = 0L
    private val recentRebufferEvents = ArrayDeque<RebufferEvent>()
    private var lastNetworkRescueAtMs = 0L

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
        lastObservedPlaybackState = Player.STATE_IDLE
        networkRebufferStartedAtMs = 0L
        recentRebufferEvents.clear()
        lastNetworkRescueAtMs = 0L
    }

    private fun sync(player: Player, publishProgressState: Boolean) {
        val positionMs = player.currentPosition.coerceAtLeast(0L)
        val durationMs = player.duration.takeIf { it > 0L } ?: 0L
        maybeHandlePlaybackStall(player, positionMs, durationMs)
        maybeHandleNetworkStall(player, positionMs)
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
     * 网络性卡顿监控（issue#114）：与 [maybeHandlePlaybackStall] 的分工——那条链管
     * "画面停摆"（READY 冻结 / BUFFERING 但不缺数据的解码停摆），本链管"网络断粮循环"
     * （BUFFERING + isLoading 真缺数据，高频短卡、每次自行恢复，两条旧链都够不着）。
     *
     * 判定基于 READY→BUFFERING 迁移事件在 60s 滑动窗口内的频次/累计时长，加单次超时兜底。
     * 救援动作在 Fragment/VM 层：只换 CDN 不降画质（用户约束）。
     */
    private fun maybeHandleNetworkStall(player: Player, positionMs: Long) {
        val state = player.playbackState
        val nowMs = SystemClock.elapsedRealtime()

        // 事件检测：播放中（READY 态、非起播）掉进 BUFFERING 才算 rebuffer。
        // 换线重建引起的 IDLE→BUFFERING 不算（lastState 是 IDLE 不是 READY）；
        // 起播阶段（position=0）由 first_frame_timeout 看门狗负责。
        if (state == Player.STATE_BUFFERING &&
            lastObservedPlaybackState == Player.STATE_READY &&
            player.playWhenReady &&
            positionMs > 0L
        ) {
            networkRebufferStartedAtMs = nowMs
        } else if (state == Player.STATE_READY && networkRebufferStartedAtMs != 0L) {
            recentRebufferEvents.addLast(
                RebufferEvent(endedAtElapsedMs = nowMs, durationMs = nowMs - networkRebufferStartedAtMs)
            )
            networkRebufferStartedAtMs = 0L
        }
        if (state == Player.STATE_IDLE || state == Player.STATE_ENDED) {
            // 重建/结束：清事件窗口（重建后的缓冲期不该继承旧窗口的焦虑）
            networkRebufferStartedAtMs = 0L
            recentRebufferEvents.clear()
        }
        lastObservedPlaybackState = state

        while (recentRebufferEvents.isNotEmpty() &&
            nowMs - recentRebufferEvents.first().endedAtElapsedMs > NETWORK_STALL_WINDOW_MS
        ) {
            recentRebufferEvents.removeFirst()
        }

        if (recentRebufferEvents.isEmpty() && networkRebufferStartedAtMs == 0L) return
        if (!player.playWhenReady) return
        if (lastNetworkRescueAtMs != 0L &&
            nowMs - lastNetworkRescueAtMs < NETWORK_STALL_RESCUE_COOLDOWN_MS
        ) {
            return
        }

        val currentStalledMs = if (networkRebufferStartedAtMs != 0L) nowMs - networkRebufferStartedAtMs else 0L
        val windowTotalMs = recentRebufferEvents.sumOf { it.durationMs } + currentStalledMs
        // 单次判据排除解码停摆（!isLoading 且前方有数据）——那是另一条链的辖区
        val singleNetworkStall = currentStalledMs >= NETWORK_STALL_SINGLE_MS &&
            !isDecodeStallWhileBuffering(player, positionMs)
        val fired = when {
            recentRebufferEvents.size >= NETWORK_STALL_EVENT_COUNT -> true
            windowTotalMs >= NETWORK_STALL_TOTAL_MS -> true
            singleNetworkStall -> true
            else -> false
        }
        if (fired) {
            lastNetworkRescueAtMs = nowMs
            onNetworkStalled(positionMs, maxOf(currentStalledMs, windowTotalMs))
        }
    }

    /** 救援成功后调用：清窗口并立即进入冷却，避免重建后的 BUFFERING 再次触发。 */
    fun clearNetworkStallStats() {
        networkRebufferStartedAtMs = 0L
        recentRebufferEvents.clear()
        lastNetworkRescueAtMs = SystemClock.elapsedRealtime()
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
