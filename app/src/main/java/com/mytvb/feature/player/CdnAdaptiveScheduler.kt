package com.mytvb.feature.player

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Player
import com.mytvb.core.common.log.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * CDN 预测性调度决策器：播放中持续评估「吞吐余量 = 吞吐EWMA / 当前流码率」，
 * 长期贴地且存在更优候选时，在缓冲耗尽**前**指挥执行层无缝换线（rotate），
 * 不等打穿后由三判据救援兜底（issue#114 场景的前置防线）。
 *
 * 触发主动换线（全部满足，带滞回防抖）：
 * - 智能选线开、播放中（READY 且 playWhenReady）、起播 >30s（缓冲冲刺期样本失真豁免）；
 * - margin < [MARGIN_DEGRADE] 且缓冲前方 < [BUFFERED_AHEAD_GATE_MS] 持续 ≥ [SUSTAIN_MS]
 *   （吞吐贴地 + 缓冲攒不起来双证据，单瞬时抖动不算）；
 * - 存在更优候选：margin ≥ [MARGIN_BETTER_CANDIDATE] 或无吞吐记录白纸；
 * - 距上次 rotate ≥ [ROTATE_COOLDOWN_MS]，本视频 rotate < [MAX_ROTATES_PER_VIDEO]。
 *
 * 用尽配额或全池候选皆贴地 → 宣判出口瓶颈（cdn_bottleneck），本轮不再折腾，
 * 三判据救援链仍可用。贴地 host 落档 starvedBitrateBps（跨视频沉底，滞回解禁）。
 *
 * 纯逻辑类：状态快照由宿主（VideoPlayerFragment）喂入，rotate 经 [EvalSnapshot.onRotate]
 * 回调执行——决策核心不触碰 ExoPlayer/Uri，JVM 单测直接覆盖。
 */
internal class CdnAdaptiveScheduler {

    /**
     * @param sessionKey 会话标识（切集/换画质重建后变化）：变化即自动重置持续窗口与配额
     * @param onRotate 决策命中时回调（宿主调 VideoPlayerCdnFailoverState.requestRotate）
     */
    data class EvalSnapshot(
        val smartRoutingEnabled: Boolean,
        val active: Boolean,
        val bufferedAheadMs: Long,
        val currentUrl: String?,
        val streamBitrateBps: Long,
        val candidateUrlsExcludingCurrent: List<String>,
        val sessionKey: Any?,
        val onRotate: () -> Unit
    )

    companion object {
        // 贴地判定：吞吐/码率余量低于此值持续 SUSTAIN_MS 视为喂不饱。
        internal const val MARGIN_DEGRADE = 1.5f
        // 更优候选门槛：margin 达标或无吞吐记录（白纸允许尝试）。
        internal const val MARGIN_BETTER_CANDIDATE = 2.5f
        // 贴地持续窗口：5s 粒度样本，60s ≈ 12 条样本中持续贴地。
        internal const val SUSTAIN_MS = 60_000L
        // 起播豁免：缓冲冲刺期吞吐样本失真（一次性全速下载不代表稳态供给）。
        internal const val MIN_ELAPSED_MS = 30_000L
        // 缓冲水位门：缓冲前方高于此值说明还攒得动，不折腾；低于此值时 rotate 的
        // 一次建连（~200ms）也被缓冲盖住，播放无感。
        internal const val BUFFERED_AHEAD_GATE_MS = 15_000L
        internal const val ROTATE_COOLDOWN_MS = 60_000L
        internal const val MAX_ROTATES_PER_VIDEO = 2
        /** 宿主 tick 周期：与吞吐样本粒度（~5s/条）对齐。 */
        internal const val EVALUATE_INTERVAL_MS = 5_000L
        private const val DECISION_LOG_INTERVAL_MS = 10_000L
        private const val TAG = "PlaybackCdn"
    }

    /** 时间源注入：单测冻结时间验证持续窗口/冷却。 */
    @Volatile
    internal var clock: () -> Long = System::currentTimeMillis

    private var sessionStartedAtMs = -1L
    private var lastSessionKey: Any? = Any() // 初值占位：首帧快照即视为新会话
    private var starvedSinceMs = -1L
    private var lastRotateAtMs = -1L
    private var rotateCount = 0
    private var bottleneckDeclared = false
    private var lastDecisionLogAtMs = -1L

    /** 单测隔离。 */
    internal fun resetForTest() {
        sessionStartedAtMs = -1L
        lastSessionKey = Any()
        starvedSinceMs = -1L
        lastRotateAtMs = -1L
        rotateCount = 0
        bottleneckDeclared = false
        lastDecisionLogAtMs = -1L
    }

    fun evaluate(snapshot: EvalSnapshot) {
        val nowMs = clock()
        if (snapshot.sessionKey !== lastSessionKey) {
            // 切集/换画质重建产生新 state：重置持续窗口与配额（出口瓶颈结论不跨视频）
            lastSessionKey = snapshot.sessionKey
            sessionStartedAtMs = nowMs
            starvedSinceMs = -1L
            lastRotateAtMs = -1L
            rotateCount = 0
            bottleneckDeclared = false
        }
        val url = snapshot.currentUrl
        if (!snapshot.smartRoutingEnabled || !snapshot.active || url.isNullOrBlank()) {
            resetTracking()
            return
        }
        if (sessionStartedAtMs < 0L || nowMs - sessionStartedAtMs < MIN_ELAPSED_MS) {
            resetTracking()
            return
        }
        val bitrate = snapshot.streamBitrateBps
        if (bitrate <= 0L) {
            // 无码率上下文不预测：绝对慢吞吐兜底仍由排序口径负责
            resetTracking()
            return
        }
        val margin = CdnProfile.margin(url, bitrate)
        if (margin < 0f) {
            // 吞吐白纸（新 host 或样本未攒够）：不预测，等实测沉淀
            resetTracking()
            return
        }

        val starving = margin < MARGIN_DEGRADE && snapshot.bufferedAheadMs < BUFFERED_AHEAD_GATE_MS
        if (!starving) {
            starvedSinceMs = -1L
            return
        }
        if (starvedSinceMs < 0L) {
            starvedSinceMs = nowMs
            return
        }
        if (nowMs - starvedSinceMs < SUSTAIN_MS) {
            return
        }
        decideRotate(snapshot, url, bitrate, margin, nowMs)
    }

    private fun decideRotate(
        snapshot: EvalSnapshot,
        url: String,
        bitrateBps: Long,
        margin: Float,
        nowMs: Long
    ) {
        if (nowMs - lastDecisionLogAtMs >= DECISION_LOG_INTERVAL_MS) {
            lastDecisionLogAtMs = nowMs
            AppLog.i(
                TAG,
                "cdn_margin_check host=${CdnProfile.extractHost(url)} margin=${"%.2f".format(margin)} " +
                    "bufferedAheadMs=${snapshot.bufferedAheadMs} sustained=${nowMs - starvedSinceMs}ms " +
                    "rotates=$rotateCount bottleneck=$bottleneckDeclared"
            )
        }
        if (bottleneckDeclared) {
            return
        }
        val betterExists = hasBetterCandidate(snapshot.candidateUrlsExcludingCurrent, bitrateBps)
        if (rotateCount >= MAX_ROTATES_PER_VIDEO || !betterExists) {
            // 配额用尽仍贴地，或全池候选皆贴地：出口瓶颈，换谁都一样，
            // 落档后停止主动换线（救援链仍可用），避免无意义的连接抖动。
            bottleneckDeclared = true
            CdnProfile.markStarved(url, bitrateBps)
            AppLog.w(
                TAG,
                "cdn_bottleneck bitrate=${bitrateBps / 1000}kbps margin=${"%.2f".format(margin)} " +
                    "reason=${if (betterExists) "rotate_exhausted" else "no_better_candidate"} " +
                    "candidates=${snapshot.candidateUrlsExcludingCurrent.size + 1}"
            )
            starvedSinceMs = -1L
            return
        }
        if (lastRotateAtMs > 0L && nowMs - lastRotateAtMs < ROTATE_COOLDOWN_MS) {
            return
        }
        CdnProfile.markStarved(url, bitrateBps)
        snapshot.onRotate()
        lastRotateAtMs = nowMs
        rotateCount += 1
        starvedSinceMs = -1L
        AppLog.w(
            TAG,
            "cdn_rotate_dispatch host=${CdnProfile.extractHost(url)} margin=${"%.2f".format(margin)} " +
                "bitrate=${bitrateBps / 1000}kbps rotate=$rotateCount/$MAX_ROTATES_PER_VIDEO"
        )
    }

    /** 更优候选：margin 达标，或无吞吐记录（白纸允许尝试——实测过的贴地候选不算）。 */
    private fun hasBetterCandidate(candidateUrls: List<String>, bitrateBps: Long): Boolean {
        if (candidateUrls.isEmpty()) return false
        return candidateUrls.any { candidate ->
            val ewma = CdnProfile.throughputEwmaBps(candidate)
            ewma < 0L || CdnProfile.margin(candidate, bitrateBps) >= MARGIN_BETTER_CANDIDATE
        }
    }

    private fun resetTracking() {
        starvedSinceMs = -1L
    }
}

/**
 * [CdnAdaptiveScheduler] 的宿主接线（双宿主共用：VideoPlayerFragment 与 PlayerActivity）：
 * 5s tick 采集快照喂决策器。TV 宿主（PlayerActivity）与 Fragment 宿主是并行的两套
 * 播放 UI（MuMu/电视走 PlayerActivity，曾因只接 Fragment 导致决策静默不跑的实锤），
 * 状态源（player+viewModel）两宿主同名，provider 注入。
 */
internal class CdnAdaptiveSchedulerHost(
    private val lifecycleOwner: LifecycleOwner,
    private val playerProvider: () -> Player?,
    private val viewModelProvider: () -> VideoPlayerViewModel
) {
    private val scheduler = CdnAdaptiveScheduler()
    private var job: Job? = null

    fun start() {
        job?.cancel()
        job = lifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                delay(CdnAdaptiveScheduler.EVALUATE_INTERVAL_MS)
                runCatching { evaluate() }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun evaluate() {
        val player = playerProvider() ?: return
        val videoState = viewModelProvider().primaryVideoCdnState() ?: return
        val currentUrl = videoState.currentHostUrl() ?: return
        scheduler.evaluate(
            CdnAdaptiveScheduler.EvalSnapshot(
                smartRoutingEnabled = CdnProfile.isEnabled(),
                active = player.playbackState == Player.STATE_READY && player.playWhenReady,
                bufferedAheadMs = (player.bufferedPosition - player.currentPosition).coerceAtLeast(0L),
                currentUrl = currentUrl,
                streamBitrateBps = videoState.streamBitrateBps,
                candidateUrlsExcludingCurrent = videoState.candidateUrlsExcludingCurrentHost(),
                sessionKey = videoState,
                onRotate = { videoState.requestRotate() }
            )
        )
    }
}
