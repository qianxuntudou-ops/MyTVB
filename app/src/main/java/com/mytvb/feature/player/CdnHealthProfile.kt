package com.mytvb.feature.player

import androidx.core.net.toUri
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import com.mytvb.core.common.log.AppLog
import com.mytvb.core.common.settings.AppSettingsDataStore
import java.util.concurrent.ConcurrentHashMap
import org.koin.mp.KoinPlatform

/**
 * CDN 候选线路健康画像：进程级单例 + DataStore 持久化（host 维度）。
 *
 * 与 [CdnLatencyProfile] 的分工：Latency 记「连接快不快」（TTFB 实测，分钟级 TTL），
 * Health 记「播得稳不稳」（起播成功/中途缓冲/播放错误/首帧超时四类计数，
 * 30 分钟半衰衰减、跨重启保留）。两者在 [CdnLatencyProfile.sortUrlsByLatency]
 * 融合成最终候选顺序——只重排 playurl 已签名的候选，不改 Host。
 *
 * 只落盘 host 维度的整数计数，playurl 签名 URL 绝不落盘（会过期）。
 * 打分权重沿用 BiliPai 验证过的比例：一次播放错误(-220) ≈ 4 次缓冲(-55/次)，
 * 起播成功每次 +25 封顶 250，无法靠刷 READY 洗白。
 */
internal object CdnHealthProfile {
    private const val TAG = "PlaybackCdn"
    private const val STORE_KEY = "cdn_health_profile"
    private const val SETTING_KEY_CDN_HEALTH_ROUTING = "cdn_health_routing"

    private const val MAX_HOSTS = 32
    // 计数每 30 分钟右移一段（半衰），封顶 16 段 = 8 小时后旧账彻底清零。
    private const val DECAY_PERIOD_MS = 30 * 60 * 1000L
    private const val MAX_DECAY_PERIODS = 16
    // 同一 host 的 BUFFERING 计分节流：一次 rebuffer 事件只记一条，
    // 防止连续抖动把好 host 刷成负分（一次也只占 -55，ushr 半衰会自动愈合）。
    internal const val BUFFERING_THROTTLE_MS = 30 * 1000L

    internal enum class CdnHealthEvent {
        READY,
        BUFFERING,
        PLAYBACK_ERROR,
        FIRST_FRAME_TIMEOUT
    }

    internal data class CdnHealthRecord(
        // @SerializedName 与属性名一致看似冗余，实为钉死 JSON 字段名：
        // R8 混淆会把字段名改成 a/b/c 且跨版本漂移，导致落盘 JSON 读不回（v2.1.27 起播即崩的根因）。
        @SerializedName("host") val host: String,
        @SerializedName("readyCount") val readyCount: Int = 0,
        @SerializedName("bufferingCount") val bufferingCount: Int = 0,
        @SerializedName("playbackErrorCount") val playbackErrorCount: Int = 0,
        @SerializedName("firstFrameTimeoutCount") val firstFrameTimeoutCount: Int = 0,
        @SerializedName("lastUpdatedAtMs") val lastUpdatedAtMs: Long = 0L,
        @SerializedName("healthWindowStartedAtMs") val healthWindowStartedAtMs: Long = 0L
    )

    private val gson = Gson()
    private val recordType = object : TypeToken<MutableMap<String, CdnHealthRecord>>() {}.type

    private val records = ConcurrentHashMap<String, CdnHealthRecord>()
    private val lastBufferingAtMsByHost = ConcurrentHashMap<String, Long>()

    @Volatile
    private var loaded = false

    /** 智能选线开关（设置→通用→网络，默认开）；关闭时排序退化为纯 TTFB 口径。 */
    fun isEnabled(): Boolean {
        return appSettings()?.getCachedString(SETTING_KEY_CDN_HEALTH_ROUTING) != "关"
    }

    /**
     * 记一条播放体验事件：先按流逝时间衰减旧计数，再累加新事件，最后节流落盘。
     */
    fun recordEvent(url: String, event: CdnHealthEvent) {
        val host = extractHost(url) ?: return
        val nowMs = System.currentTimeMillis()
        if (event == CdnHealthEvent.BUFFERING) {
            val lastAtMs = lastBufferingAtMsByHost[host] ?: 0L
            if (nowMs - lastAtMs < BUFFERING_THROTTLE_MS) {
                return
            }
            lastBufferingAtMsByHost[host] = nowMs
        }
        val updated: CdnHealthRecord
        synchronized(records) {
            ensureLoaded()
            val decayed = decay(records[host], host, nowMs) ?: CdnHealthRecord(host = host)
            updated = when (event) {
                CdnHealthEvent.READY -> decayed.copy(readyCount = decayed.readyCount + 1)
                CdnHealthEvent.BUFFERING -> decayed.copy(bufferingCount = decayed.bufferingCount + 1)
                CdnHealthEvent.PLAYBACK_ERROR -> decayed.copy(playbackErrorCount = decayed.playbackErrorCount + 1)
                CdnHealthEvent.FIRST_FRAME_TIMEOUT -> decayed.copy(firstFrameTimeoutCount = decayed.firstFrameTimeoutCount + 1)
            }.copy(lastUpdatedAtMs = nowMs)
            records[host] = updated
            evictStaleHostsLocked()
        }
        persistAsync()
        AppLog.i(
            TAG,
            "cdn_health event=$event host=$host " +
                "ready=${updated.readyCount} buf=${updated.bufferingCount} " +
                "err=${updated.playbackErrorCount} fft=${updated.firstFrameTimeoutCount} score=${score(updated)}"
        )
    }

    /**
     * 该 URL 所属 host 的健康分（只读，不推进衰减窗口）：
     * 1000(中性) + ready×25(封顶250) − error×220 − firstFrameTimeout×180 − buffering×55。
     * 无记录返回 1000，不干扰仅有 TTFB 数据时的排序。
     */
    fun healthScore(url: String): Long {
        val host = extractHost(url) ?: return NEUTRAL_SCORE
        val record = records[host] ?: return NEUTRAL_SCORE
        return score(record)
    }

    private const val NEUTRAL_SCORE = 1_000L

    private fun score(record: CdnHealthRecord): Long {
        val nowMs = System.currentTimeMillis()
        val periods = decayPeriods(record, nowMs)
        val ready = (record.readyCount ushr periods).coerceAtMost(10)
        val buffering = record.bufferingCount ushr periods
        val error = record.playbackErrorCount ushr periods
        val firstFrameTimeout = record.firstFrameTimeoutCount ushr periods
        return NEUTRAL_SCORE +
            ready * 25L -
            error * 220L -
            firstFrameTimeout * 180L -
            buffering * 55L
    }

    // ===== 衰减与持久化 =====

    /** 按流逝时间把计数右移（半衰），推进衰减窗口并返回新记录；无记录返回 null。 */
    private fun decay(record: CdnHealthRecord?, host: String, nowMs: Long): CdnHealthRecord? {
        if (record == null) return null
        val periods = decayPeriods(record, nowMs)
        if (periods <= 0) return record
        return record.copy(
            readyCount = record.readyCount ushr periods,
            bufferingCount = record.bufferingCount ushr periods,
            playbackErrorCount = record.playbackErrorCount ushr periods,
            firstFrameTimeoutCount = record.firstFrameTimeoutCount ushr periods,
            healthWindowStartedAtMs = nowMs
        )
    }

    private fun decayPeriods(record: CdnHealthRecord, nowMs: Long): Int {
        val baselineMs = record.healthWindowStartedAtMs.takeIf { it > 0L }
            ?: record.lastUpdatedAtMs.takeIf { it > 0L }
            ?: return 0
        val elapsedMs = (nowMs - baselineMs).coerceAtLeast(0L)
        return (elapsedMs / DECAY_PERIOD_MS).toInt().coerceAtMost(MAX_DECAY_PERIODS)
    }

    private fun evictStaleHostsLocked() {
        if (records.size <= MAX_HOSTS) return
        records.entries
            .sortedBy { it.value.lastUpdatedAtMs }
            .take(records.size - MAX_HOSTS)
            .forEach { (key, _) -> records.remove(key) }
    }

    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val stored = runCatching {
            appSettings()?.getCachedString(STORE_KEY)
        }.getOrNull()
        if (stored.isNullOrBlank()) return
        val nowMs = System.currentTimeMillis()
        runCatching {
            val parsed: MutableMap<String, CdnHealthRecord> = gson.fromJson(stored, recordType)
            parsed.forEach { (host, record) ->
                if (host.isBlank() || record == null || record.host != host) {
                    // 旧混淆包落盘字段名是 b~g 缩写，全 miss 时 Gson 用 Unsafe 绕过非空检查
                    // 注入 host=null 的残废记录，留到 copy() 上必崩（v2.1.27 起播即崩）。
                    // 判据：正常运行记录的 host 恒等于 map key；不符即脏数据，丢弃（旧格式计数全 0，无损）。
                    return@forEach
                }
                // 读盘时先衰减再入内存，与 BiliPai 口径一致。
                records[host] = decay(record, host, nowMs) ?: record
            }
            AppLog.i(TAG, "cdn_health loaded hosts=${records.size}")
        }.onFailure { error ->
            AppLog.w(TAG, "cdn_health load failed, reset: ${error.javaClass.simpleName}:${error.message}")
            records.clear()
        }
    }

    private fun persistAsync() {
        val snapshot: String
        synchronized(records) {
            snapshot = gson.toJson(records.toMap())
        }
        runCatching {
            appSettings()?.putStringAsync(STORE_KEY, snapshot)
        }.onFailure { error ->
            AppLog.w(TAG, "cdn_health persist failed: ${error.javaClass.simpleName}:${error.message}")
        }
    }

    private fun appSettings(): AppSettingsDataStore? {
        return runCatching { KoinPlatform.getKoin().get<AppSettingsDataStore>() }.getOrNull()
    }

    private fun extractHost(url: String): String? {
        return runCatching { url.toUri().host?.lowercase() }.getOrNull()
    }
}
