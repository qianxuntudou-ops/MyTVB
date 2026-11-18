package com.mytvb.feature.player

import android.net.Uri
import android.os.SystemClock
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.mytvb.core.common.log.AppLog
import java.io.IOException

/**
 * rotate 续传的 Range 数学：新 position = open 起点 + 已读字节；确定长度扣减已读，
 * UNSET（负值）透传。抽纯函数供 JVM 单测（Uri 依赖使连接切换全流程测试不可行）。
 */
internal fun computeRotatedRange(position: Long, length: Long, bytesSinceOpen: Long): Pair<Long, Long> {
    val newPosition = position + bytesSinceOpen
    val newLength = if (length >= 0L) length - bytesSinceOpen else length
    return newPosition to newLength
}

/**
 * 一条媒体流（一个 Representation / 一段 durl）的 CDN 候选状态。
 *
 * [streamBitrateBps] 携带该流码率上下文：吞吐样本上报 margin、贴地沉底判定、
 * rotate 选优都以它为分母（同一 host 对 480P 健康可能对 4K50 贴地）。
 * [isVideoStream] 区分视频主承载流：主动调度（CdnAdaptiveScheduler）只对视频流
 * 决策——音频流码率低几乎不会贴地，且音视频双流各持独立 host，混判会互相干扰。
 */
internal class VideoPlayerCdnFailoverState(
    val candidates: List<Uri>,
    val streamBitrateBps: Long = 0L,
    val isVideoStream: Boolean = false
) {
    @Volatile
    private var preferredIndex: Int = 0
    private var diagnosticOpenCount: Int = 0
    private var rotateRequested = false

    /** 本 state 是否真正 open 成功过（= 该流确实在用它传数据）。 */
    @Volatile
    private var opened = false

    @Synchronized
    fun preferredIndex(): Int {
        val lastIndex = candidates.lastIndex.coerceAtLeast(0)
        return preferredIndex.coerceIn(0, lastIndex)
    }

    /**
     * 该 state 是否承载过真实数据传输。健康分记账（首帧超时/卡顿降权等）只应归因到
     * 真正用过的流：无缝目录挂载时 cdnStates 含全部档位的 state，未 open 的 state
     * 的 currentHostUrl() 返回的是 candidates[0]（并未使用），全池记账会把好 host
     * 冤枉成负分（S65 现场：一次看门狗 fired 对 19 个 state 记 fft，hwo1 被刷到 36 次）。
     */
    fun hasOpened(): Boolean = opened

    @Synchronized
    fun markPreferred(index: Int) {
        val lastIndex = candidates.lastIndex.coerceAtLeast(0)
        preferredIndex = index.coerceIn(0, lastIndex)
        opened = true
    }

    /** 当前实际使用的候选 URL（open 成功后由 markPreferred 持续更新），供健康事件回流取 host。 */
    @Synchronized
    fun currentHostUrl(): String? {
        return candidates.getOrNull(preferredIndex())?.toString()
    }

    @Synchronized
    fun nextDiagnosticOpenCount(): Int = ++diagnosticOpenCount

    /** 请求无缝换线：只置标志，DataSource 在 read 间隙（或下次 open）执行，不中断当前传输。 */
    fun requestRotate() {
        rotateRequested = true
    }

    /** 消费 rotate 请求（读走即清）。 */
    fun consumeRotateRequest(): Boolean {
        val requested = rotateRequested
        rotateRequested = false
        return requested
    }

    /** 是否有待执行的 rotate 请求（诊断用）。 */
    @Synchronized
    fun isRotatePending(): Boolean = rotateRequested

    /** 排除当前 host 后的候选 URL 列表（决策器快照用；host 维度去重）。 */
    @Synchronized
    fun candidateUrlsExcludingCurrentHost(): List<String> {
        val currentHost = candidates.getOrNull(preferredIndex())?.host ?: return emptyList()
        val seen = mutableSetOf<String>()
        return candidates.asSequence()
            .filter { it.host != currentHost }
            .map { it.toString() }
            .filter { seen.add(it) }
            .toList()
    }

    /**
     * rotate 目标：排除当前 host 后按最新画像（含码率余量）重排取最优。
     * 无其它 host 候选返回 -1（rotate 换了个寂寞的场景由调用方跳过）。
     */
    @Synchronized
    fun rotateTargetIndex(): Int {
        val currentIndex = preferredIndex()
        val currentHost = candidates.getOrNull(currentIndex)?.host ?: return -1
        val others = candidates.withIndex().filter { it.value.host != currentHost }
        if (others.isEmpty()) return -1
        val ranked = CdnProfile.rankCandidates(others.map { it.value.toString() }, streamBitrateBps)
        val bestUrl = ranked.firstOrNull() ?: return -1
        return others.firstOrNull { it.value.toString() == bestUrl }?.index ?: -1
    }

    /**
     * 卡顿降权：对当前 host 记惩罚窗口（排序键按惩罚值计），过期自动恢复。
     */
    @Synchronized
    fun penalizeCurrentHost() {
        val index = preferredIndex()
        val url = candidates.getOrNull(index)?.toString() ?: return
        CdnProfile.penalizeHost(url)
    }
}

internal class VideoPlayerCdnFailoverDataSourceFactory(
    private val upstreamFactory: DataSource.Factory,
    private val state: VideoPlayerCdnFailoverState
) : DataSource.Factory {
    override fun createDataSource(): DataSource {
        return VideoPlayerCdnFailoverDataSource(
            upstreamFactory = upstreamFactory,
            state = state
        )
    }
}

internal class VideoPlayerCdnFailoverDataSource(
    private val upstreamFactory: DataSource.Factory,
    private val state: VideoPlayerCdnFailoverState
) : DataSource {

    private var upstream: DataSource? = null
    private val transferListeners = ArrayList<TransferListener>(2)
    private var currentDataSpec: DataSpec? = null

    // ===== 吞吐计量 =====
    // 每 5s 结算一次增量速率上报 CdnProfile（带流码率上下文算 margin）：
    // 打点而非只在 close 结算——durl 直链整段视频一个长 open，中途换线决策等不到 close。
    private var throughputWindowBytes = 0L
    private var throughputWindowStartedAtMs = 0L
    // 本次 open 以来累计读取字节：rotate 续传时换算新 DataSpec 的 position/length。
    private var totalBytesSinceOpen = 0L

    private companion object {
        private const val TAG = "PlaybackCdn"
        private const val THROUGHPUT_SAMPLE_INTERVAL_MS = 5_000L
        // 窗口内至少 256KB 才上报：避免 BUFFERING 尾段"只读了几十 KB"的窗口把速率记成 0.0xMB/s
        private const val THROUGHPUT_MIN_SAMPLE_BYTES = 256 * 1024L
    }

    private fun reportThroughputSample(force: Boolean) {
        val source = upstream ?: return
        val nowMs = SystemClock.elapsedRealtime()
        val windowMs = nowMs - throughputWindowStartedAtMs
        if (windowMs <= 0L) return
        if (windowMs < THROUGHPUT_SAMPLE_INTERVAL_MS && !force) return
        if (throughputWindowBytes < THROUGHPUT_MIN_SAMPLE_BYTES) {
            // 数据量太小不代表稳态速率，丢弃本窗口重新累计（不污染画像）
            throughputWindowBytes = 0L
            throughputWindowStartedAtMs = nowMs
            return
        }
        val hostUrl = source.uri?.toString()
        if (hostUrl != null) {
            CdnProfile.recordThroughput(
                hostUrl,
                throughputWindowBytes * 1000L / windowMs,
                state.streamBitrateBps
            )
        }
        throughputWindowBytes = 0L
        throughputWindowStartedAtMs = nowMs
    }

    override fun addTransferListener(transferListener: TransferListener) {
        transferListeners += transferListener
        upstream?.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        closeQuietly()
        val candidates = state.candidates
        if (candidates.isEmpty()) {
            throw IOException("No CDN candidates available")
        }

        // rotate 请求在连接自然结束（EOF/close）后由下次 open 消费：
        // 起点直接切到重排后的最优候选，等效于"下一个 chunk 从新 host 拉"。
        val pendingRotate = state.consumeRotateRequest()
        val startIndex = if (pendingRotate) {
            state.rotateTargetIndex().takeIf { it >= 0 } ?: state.preferredIndex()
        } else {
            state.preferredIndex()
        }
        val openCount = state.nextDiagnosticOpenCount()
        val logOpen = openCount <= 2 || pendingRotate
        val openStartedAtMs = SystemClock.elapsedRealtime()
        if (logOpen) {
            AppLog.i(
                TAG,
                "playback_diag cdn_open started sequence=$openCount candidates=${candidates.size} " +
                    "startIndex=$startIndex position=${dataSpec.position}" +
                    (if (pendingRotate) " rotate=pending" else "")
            )
        }
        var lastException: IOException? = null
        for (attempt in candidates.indices) {
            val index = (startIndex + attempt) % candidates.size
            val candidateUri = candidates[index]
            val upstreamSource = upstreamFactory.createDataSource()
            transferListeners.forEach(upstreamSource::addTransferListener)
            val candidateSpec = dataSpec.buildUpon()
                .setUri(candidateUri)
                .build()
            // 单候选计时：TTFB 只计本次尝试的 open 耗时，不含前面失败候选的累计时间。
            val candidateStartedAtMs = SystemClock.elapsedRealtime()
            try {
                val openedLength = upstreamSource.open(candidateSpec)
                upstream = upstreamSource
                state.markPreferred(index)
                currentDataSpec = candidateSpec
                totalBytesSinceOpen = 0L
                throughputWindowBytes = 0L
                throughputWindowStartedAtMs = SystemClock.elapsedRealtime()
                // 采集 TTFB 到统一画像：open 成功 = 该 CDN 在当前网络下确实可达，
                // 跨视频复用，让后续播放的候选排序把实测快的 host 排到前面。
                CdnProfile.recordTtfb(
                    candidateUri.toString(),
                    SystemClock.elapsedRealtime() - candidateStartedAtMs
                )
                if (logOpen) {
                    AppLog.i(
                        TAG,
                        "playback_diag cdn_open ready sequence=$openCount attempt=${attempt + 1} host=${candidateUri.host.orEmpty()} " +
                            "durationMs=${SystemClock.elapsedRealtime() - openStartedAtMs}"
                    )
                }
                return openedLength
            } catch (error: IOException) {
                runCatching { upstreamSource.close() }
                lastException = error
                AppLog.w(
                    TAG,
                    "playback_diag cdn_open failed sequence=$openCount attempt=${attempt + 1} host=${candidateUri.host.orEmpty()} " +
                        "durationMs=${SystemClock.elapsedRealtime() - openStartedAtMs} error=${error.javaClass.simpleName}:${error.message}"
                )
            }
        }

        throw lastException ?: IOException("Failed to open any CDN candidate")
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val activeUpstream = upstream ?: throw IllegalStateException("read() before open()")
        val bytesRead = activeUpstream.read(buffer, offset, length)
        if (bytesRead > 0) {
            throughputWindowBytes += bytesRead
            totalBytesSinceOpen += bytesRead
            reportThroughputSample(force = false)
        }
        // read 间隙消费 rotate：当前数据已读走，在下一个字节请求前切换连接。
        // 失败自动回退旧连接续读，绝不因主动换线把播放搞挂。
        if (state.consumeRotateRequest()) {
            rotateUpstream()
        }
        return bytesRead
    }

    /**
     * 无缝换线（CdnAdaptiveScheduler 指挥）：Range 续传——新连接从「本次 open 起点 +
     * 已读字节」继续，已缓冲内容全程保留，播放无感，代价仅一次新连接 TTFB。
     * 新候选 open 失败时保留旧连接续读（rotate 放弃，等下个决策周期）。
     */
    private fun rotateUpstream() {
        val spec = currentDataSpec ?: return
        val oldUpstream = upstream ?: return
        val targetIndex = state.rotateTargetIndex()
        val currentIndex = state.preferredIndex()
        if (targetIndex < 0 || targetIndex == currentIndex) {
            AppLog.i(TAG, "cdn_rotate skipped: no better candidate hosts=${state.candidates.size}")
            return
        }
        val fromHost = state.candidates.getOrNull(currentIndex)?.host.orEmpty()
        val targetUri = state.candidates[targetIndex]
        val (newPosition, newLength) = computeRotatedRange(spec.position, spec.length, totalBytesSinceOpen)
        val rotatedSpec = spec.buildUpon()
            .setUri(targetUri)
            .setPosition(newPosition)
            .setLength(newLength)
            .build()
        // 旧连接的吞吐窗口先结算（force），避免最后一截样本丢账
        if (throughputWindowBytes > 0L) {
            reportThroughputSample(force = true)
        }
        val newSource = upstreamFactory.createDataSource()
        transferListeners.forEach(newSource::addTransferListener)
        val startedAtMs = SystemClock.elapsedRealtime()
        try {
            newSource.open(rotatedSpec)
            // 新连接成功后才关旧连接：失败路径保留旧连接继续读
            runCatching { oldUpstream.close() }
            upstream = newSource
            state.markPreferred(targetIndex)
            currentDataSpec = rotatedSpec
            totalBytesSinceOpen = 0L
            throughputWindowBytes = 0L
            throughputWindowStartedAtMs = SystemClock.elapsedRealtime()
            CdnProfile.recordTtfb(targetUri.toString(), SystemClock.elapsedRealtime() - startedAtMs)
            AppLog.i(
                TAG,
                "cdn_rotate from=$fromHost to=${targetUri.host.orEmpty()} reason=margin " +
                    "resumeAt=${newPosition} durationMs=${SystemClock.elapsedRealtime() - startedAtMs}"
            )
        } catch (error: IOException) {
            runCatching { newSource.close() }
            AppLog.w(
                TAG,
                "cdn_rotate failed keep=$fromHost error=${error.javaClass.simpleName}:${error.message}"
            )
            // 旧连接不动，重置吞吐窗口起点继续（open 尝试期间旧连接静止，窗口时间失真）
            throughputWindowStartedAtMs = SystemClock.elapsedRealtime()
        }
    }

    override fun getUri(): Uri? = upstream?.uri

    override fun close() {
        closeQuietly()
    }

    private fun closeQuietly() {
        if (upstream != null && throughputWindowBytes > 0L) {
            reportThroughputSample(force = true)
        }
        runCatching { upstream?.close() }
        upstream = null
    }
}
