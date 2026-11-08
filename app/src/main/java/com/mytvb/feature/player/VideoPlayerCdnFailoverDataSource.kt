package com.mytvb.feature.player

import android.net.Uri
import android.os.SystemClock
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.mytvb.core.common.log.AppLog
import java.io.IOException

internal class VideoPlayerCdnFailoverState(
    val candidates: List<Uri>
) {
    @Volatile
    private var preferredIndex: Int = 0
    private var diagnosticOpenCount: Int = 0

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

    /**
     * 卡顿降权：把当前正在用的 CDN host 记一条惩罚性 TTFB 到延迟画像。
     * 借助 CdnLatencyProfile "每 host 保留最近 5 条" 的淘汰机制自动稀释——
     * 一次卡顿只占 1/5，后续正常 open 会把均值拉回，不会判死刑。
     */
    @Synchronized
    fun penalizeCurrentHost() {
        val index = preferredIndex()
        val url = candidates.getOrNull(index)?.toString() ?: return
        CdnLatencyProfile.recordTtfb(url, PENALTY_TTFB_MS)
    }

    companion object {
        // 远大于正常 TTFB(~200ms)，足以把卡顿过的 host 排到 candidates 末尾。
        private const val PENALTY_TTFB_MS = 10_000L
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

        val startIndex = state.preferredIndex()
        val openCount = state.nextDiagnosticOpenCount()
        val logOpen = openCount <= 2
        val openStartedAtMs = SystemClock.elapsedRealtime()
        if (logOpen) {
            AppLog.i(
                "PlaybackCdn",
                "playback_diag cdn_open started sequence=$openCount candidates=${candidates.size} " +
                    "startIndex=$startIndex position=${dataSpec.position}"
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
                // 采集 TTFB 到延迟画像：open 成功 = 该 CDN 在当前网络下确实可达。
                // 数据沉淀进 CdnLatencyProfile（进程级单例），跨视频复用，让后续播放的
                // sortUrlsByLatency 能把实测快的 host 排到 candidates 前面。
                CdnLatencyProfile.recordTtfb(
                    candidateUri.toString(),
                    SystemClock.elapsedRealtime() - candidateStartedAtMs
                )
                if (logOpen) {
                    AppLog.i(
                        "PlaybackCdn",
                        "playback_diag cdn_open ready sequence=$openCount attempt=${attempt + 1} host=${candidateUri.host.orEmpty()} " +
                            "durationMs=${SystemClock.elapsedRealtime() - openStartedAtMs}"
                    )
                }
                return openedLength
            } catch (error: IOException) {
                runCatching { upstreamSource.close() }
                lastException = error
                AppLog.w(
                    "PlaybackCdn",
                    "playback_diag cdn_open failed sequence=$openCount attempt=${attempt + 1} host=${candidateUri.host.orEmpty()} " +
                        "durationMs=${SystemClock.elapsedRealtime() - openStartedAtMs} error=${error.javaClass.simpleName}:${error.message}"
                )
            }
        }

        throw lastException ?: IOException("Failed to open any CDN candidate")
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val activeUpstream = upstream ?: throw IllegalStateException("read() before open()")
        return activeUpstream.read(buffer, offset, length)
    }

    override fun getUri(): Uri? = upstream?.uri

    override fun close() {
        closeQuietly()
    }

    private fun closeQuietly() {
        runCatching { upstream?.close() }
        upstream = null
    }
}
