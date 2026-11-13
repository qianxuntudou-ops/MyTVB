package com.mytvb.feature.player

import androidx.core.net.toUri
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

internal object CdnLatencyProfile {
    private const val MAX_ENTRIES = 32
    private const val RECORD_TTL_MS = 10 * 60 * 1000L

    private const val CDN_PREF_BILIVIDEO = 0
    private const val CDN_PREF_MCDN = 1
    private const val CDN_PREF_OTHER = 2

    // ===== 吞吐口径（issue#114）=====
    // TTFB 只能反映"连得上、连得快"，反映不了"喂得饱"。实测：出口 700Mbps 环境下
    // estgcos 节点 700Mbps、mirrorhwb 仅 23~60Mbps 且波动剧烈——首帧快(TTFB 小)的
    // mirrorhwb 被 TTFB 排序选中，10.3Mbps 的 4K 流立刻断粮循环。DataSource 层每
    // 5s 上报一次实测速率，排序时低于阈值的 host 沉底（压在健康分之前：健康分只记
    // 坏事件计数，烂吞吐节点可以零报错但照样卡）。
    private const val THROUGHPUT_MAX_SAMPLES_PER_HOST = 3
    private const val THROUGHPUT_TTL_MS = 10 * 60 * 1000L
    // 低于 1MB/s(8Mbps) 视为慢吞吐 host：4K 高码率流(10Mbps+)在这个速率下必卡；
    // 低画质流(≤2.3Mbps)虽仍可播，但沉底只影响排序不影响可达性，无副作用。
    private const val SLOW_HOST_BYTES_PER_SECOND = 1_000_000L

    private data class LatencyRecord(
        val host: String,
        val ttfbMs: Long,
        val timestampMs: Long
    )

    private data class ThroughputRecord(
        val bytesPerSecond: Long,
        val timestampMs: Long
    )

    private val records = ConcurrentHashMap<String, MutableList<LatencyRecord>>()
    private val throughputRecords = ConcurrentHashMap<String, MutableList<ThroughputRecord>>()

    fun recordTtfb(url: String, ttfbMs: Long) {
        if (ttfbMs <= 0L) return
        val host = extractHost(url) ?: return
        val record = LatencyRecord(host = host, ttfbMs = ttfbMs, timestampMs = System.currentTimeMillis())
        synchronized(records) {
            val list = (records[host] ?: mutableListOf()).toMutableList()
            list.add(record)
            if (list.size > 5) list.removeAt(0)
            records[host] = list
        }
        if (records.size > MAX_ENTRIES) {
            val now = System.currentTimeMillis()
            records.entries
                .filter { (_, v) -> v.lastOrNull()?.let { now - it.timestampMs > RECORD_TTL_MS } ?: true }
                .forEach { (key, _) -> records.remove(key) }
            if (records.size > MAX_ENTRIES) {
                records.entries
                    .sortedBy { it.value.lastOrNull()?.timestampMs ?: Long.MIN_VALUE }
                    .take(records.size - MAX_ENTRIES)
                    .forEach { (key, _) -> records.remove(key) }
            }
        }
    }

    fun averageTtfbMs(url: String): Long {
        val host = extractHost(url) ?: return Long.MAX_VALUE
        val list = records[host] ?: return Long.MAX_VALUE
        if (list.isEmpty()) return Long.MAX_VALUE
        return list.map { it.ttfbMs }.sorted().let { sorted ->
            sorted.drop(sorted.size / 4).dropLast(sorted.size / 4).average().toLong()
                .takeIf { it > 0L } ?: sorted.median()
        }
    }

    /** 记一条实测吞吐（DataSource 层每 ~5s 上报一次增量速率）。无效值忽略。 */
    fun recordThroughput(url: String, bytesPerSecond: Long) {
        if (bytesPerSecond <= 0L) return
        val host = extractHost(url) ?: return
        val record = ThroughputRecord(
            bytesPerSecond = bytesPerSecond,
            timestampMs = System.currentTimeMillis()
        )
        synchronized(throughputRecords) {
            val list = (throughputRecords[host] ?: mutableListOf()).toMutableList()
            list.add(record)
            if (list.size > THROUGHPUT_MAX_SAMPLES_PER_HOST) list.removeAt(0)
            throughputRecords[host] = list
        }
    }

    /** 该 host 的近期平均吞吐（B/s）；无近期记录返回 -1（不参与沉底判定，避免冤枉新节点）。 */
    fun averageThroughputBytesPerSecond(url: String): Long {
        val host = extractHost(url) ?: return -1L
        val now = System.currentTimeMillis()
        val samples = synchronized(throughputRecords) {
            throughputRecords[host].orEmpty().filter { now - it.timestampMs <= THROUGHPUT_TTL_MS }
        }
        if (samples.isEmpty()) return -1L
        return samples.map { it.bytesPerSecond }.average().toLong()
    }

    /** 实测吞吐已知且低于阈值 = 喂不饱高码率流的 host，排序沉底。 */
    private fun isKnownSlowThroughput(url: String): Boolean {
        val average = averageThroughputBytesPerSecond(url)
        return average in 1 until SLOW_HOST_BYTES_PER_SECOND
    }

    fun sortUrlsByLatency(urls: List<String>): List<String> {
        if (urls.size <= 1) return urls
        // 健康分开关关闭（或无记录）时全部候选同分 1000，自然退化为纯 TTFB 口径。
        val useHealthScore = CdnHealthProfile.isEnabled()
        return urls.sortedWith(
            compareBy(
                { cdnPreference(it) },
                // 实测慢吞吐沉底先于健康分：烂吞吐节点可以零报错（健康分 970）但照样卡
                // （issue#114 mirrorhwb 实锤），坏事件计数口径压不住它。
                // 随智能选线开关一起生效：关闭时退化为纯 TTFB 口径。
                { if (useHealthScore && isKnownSlowThroughput(it)) 1 else 0 },
                { if (useHealthScore) -CdnHealthProfile.healthScore(it) else 0L },
                { averageTtfbMs(it) }
            )
        )
    }

    private fun cdnPreference(url: String): Int {
        val host = runCatching { url.toUri().host }.getOrNull()
            ?.lowercase(Locale.US) ?: return CDN_PREF_OTHER
        val isMcdn = host.contains("mcdn") && host.contains("bilivideo")
        val isBilivideo = host.contains("bilivideo") && !isMcdn
        return when {
            isBilivideo -> CDN_PREF_BILIVIDEO
            isMcdn -> CDN_PREF_MCDN
            else -> CDN_PREF_OTHER
        }
    }

    private fun extractHost(url: String): String? {
        return runCatching { url.toUri().host }.getOrNull()
    }

    private fun List<Long>.median(): Long {
        if (isEmpty()) return Long.MAX_VALUE
        val sorted = sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2 else sorted[mid]
    }
}
