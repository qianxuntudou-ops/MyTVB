package com.mytvb.feature.player

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import com.mytvb.core.common.log.AppLog
import com.mytvb.core.common.settings.AppSettingsDataStore
import java.net.URI
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import org.koin.mp.KoinPlatform

/**
 * CDN host 统一画像：单 object 收拢原 CdnHealthProfile（事件计数）与 CdnLatencyProfile
 * （TTFB/吞吐）三套碎片化记账，是预测性调度（CdnAdaptiveScheduler）的数据底座。
 *
 * 每 host 一条 [CdnHostSignal]，四维信号：
 * - TTFB EWMA（时间半衰 2 分钟）——连接快不快，易变信号用短半衰；
 * - 吞吐 EWMA（样本半衰 12 条 ≈ 1 分钟）——喂得饱不饱；
 * - starvedBitrateBps 贴地档位——**余量记忆的持久化形态**：该 host 实测喂不饱的流码率。
 *   排序时"当前流码率 ≥ starved×0.8"才沉底（estgoss 喂不饱 4K ≠ 喂不饱 1080P），
 *   后续实测 margin ≥ 2.5 自动清档解禁（滞回，防反复横跳）；
 * - 事件计数（READY/BUFFERING/ERROR/FFT）——沿用旧健康分口径，30 分钟半衰，
 *   一次播放错误(-220) ≈ 4 次缓冲(-55/次)，无法靠刷 READY 洗白。
 *
 * 持久化：DataStore 单 key，跨重启保留短命记忆（吞吐/贴地档位）。落盘全字段
 * @SerializedName 钉死（v2.1.27 教训：R8 混淆字段名跨版本漂移读不回）。旧 key
 * （cdn_health_profile）不迁移直接弃——画像本就是短命记忆，弃账无感且零兼容风险。
 *
 * 时间源经 [clock] 注入，单元测试可冻结时间验证半衰/节流/惩罚过期。
 */
internal object CdnProfile {
    private const val TAG = "PlaybackCdn"
    private const val STORE_KEY = "cdn_profile_v2"
    private const val SETTING_KEY_CDN_HEALTH_ROUTING = "cdn_health_routing"

    private const val MAX_HOSTS = 32

    // ===== 事件计数衰减（沿用旧健康分口径）=====
    private const val DECAY_PERIOD_MS = 30 * 60 * 1000L
    private const val MAX_DECAY_PERIODS = 16
    internal const val BUFFERING_THROTTLE_MS = 30 * 1000L

    // ===== TTFB EWMA =====
    private const val TTFB_HALF_LIFE_MS = 2 * 60 * 1000L
    // 一次卡顿惩罚的存活时长：期间排序键按惩罚值计，过期自动恢复（不判死刑）。
    private const val TTFB_PENALTY_MS = 10 * 60 * 1000L
    internal const val TTFB_PENALTY_VALUE_MS = 10_000L

    // ===== 吞吐 EWMA 与余量 =====
    // 样本半衰 12 条：每 5s 一条样本，半衰 ≈ 1 分钟，兼顾响应速度与抗抖。
    private const val THROUGHPUT_HALF_LIFE_SAMPLES = 12.0
    // 绝对慢吞吐兜底（无码率上下文时）：低于 1MB/s 的 host 排序沉底，
    // 沉底只影响排序不影响可达性（低画质流仍可被试到）。
    internal const val SLOW_HOST_BYTES_PER_SECOND = 1_000_000L
    // 贴地档位沉底条件：排序时流码率 ≥ starved × 0.8（0.8 容差吸收码率档位边界抖动）。
    internal const val STARVED_APPLY_FACTOR = 0.8
    // margin ≥ 2.5 时清除贴地档位（滞回解禁：落档判据 1.5，解禁 2.5，中间留死区）。
    internal const val MARGIN_RECOVER = 2.5f

    internal enum class CdnProfileEvent {
        READY,
        BUFFERING,
        PLAYBACK_ERROR,
        FIRST_FRAME_TIMEOUT
    }

    internal data class CdnHostSignal(
        // @SerializedName 与属性名一致看似冗余，实为钉死 JSON 字段名：
        // R8 混淆会把字段名改成 a/b/c 且跨版本漂移，导致落盘 JSON 读不回（v2.1.27 起播即崩的根因）。
        @SerializedName("host") val host: String,
        @SerializedName("ttfbEwmaMs") val ttfbEwmaMs: Long = 0L,
        @SerializedName("ttfbUpdatedAtMs") val ttfbUpdatedAtMs: Long = 0L,
        @SerializedName("ttfbPenalizedUntilMs") val ttfbPenalizedUntilMs: Long = 0L,
        @SerializedName("throughputEwmaBps") val throughputEwmaBps: Long = 0L,
        @SerializedName("throughputSampleCount") val throughputSampleCount: Int = 0,
        @SerializedName("starvedBitrateBps") val starvedBitrateBps: Long = 0L,
        @SerializedName("readyCount") val readyCount: Int = 0,
        @SerializedName("bufferingCount") val bufferingCount: Int = 0,
        @SerializedName("playbackErrorCount") val playbackErrorCount: Int = 0,
        @SerializedName("firstFrameTimeoutCount") val firstFrameTimeoutCount: Int = 0,
        @SerializedName("lastUpdatedAtMs") val lastUpdatedAtMs: Long = 0L,
        @SerializedName("healthWindowStartedAtMs") val healthWindowStartedAtMs: Long = 0L
    )

    private val gson = Gson()
    private val recordType = object : TypeToken<MutableMap<String, CdnHostSignal>>() {}.type

    private val signals = ConcurrentHashMap<String, CdnHostSignal>()
    private val lastBufferingAtMsByHost = ConcurrentHashMap<String, Long>()

    @Volatile
    private var loaded = false

    /** 时间源注入：生产用墙钟，单测冻结时间验证半衰/节流/惩罚过期。 */
    @Volatile
    internal var clock: () -> Long = System::currentTimeMillis

    /** 智能选线开关（设置→通用→网络，默认开）；关闭时排序退化为纯 TTFB 口径。 */
    fun isEnabled(): Boolean {
        return appSettings()?.getCachedString(SETTING_KEY_CDN_HEALTH_ROUTING) != "关"
    }

    // ===== 记账 API =====

    /**
     * 记一条播放体验事件：按流逝时间衰减旧计数后累加（语义与旧健康分完全一致）。
     */
    fun recordEvent(url: String, event: CdnProfileEvent) {
        val host = extractHost(url) ?: return
        val nowMs = clock()
        if (event == CdnProfileEvent.BUFFERING) {
            val lastAtMs = lastBufferingAtMsByHost[host] ?: 0L
            if (nowMs - lastAtMs < BUFFERING_THROTTLE_MS) {
                return
            }
            lastBufferingAtMsByHost[host] = nowMs
        }
        val updated: CdnHostSignal
        synchronized(signals) {
            ensureLoaded()
            val decayed = decayHealth(signals[host], nowMs) ?: CdnHostSignal(host = host)
            updated = when (event) {
                CdnProfileEvent.READY -> decayed.copy(readyCount = decayed.readyCount + 1)
                CdnProfileEvent.BUFFERING -> decayed.copy(bufferingCount = decayed.bufferingCount + 1)
                CdnProfileEvent.PLAYBACK_ERROR -> decayed.copy(playbackErrorCount = decayed.playbackErrorCount + 1)
                CdnProfileEvent.FIRST_FRAME_TIMEOUT -> decayed.copy(firstFrameTimeoutCount = decayed.firstFrameTimeoutCount + 1)
            }.copy(lastUpdatedAtMs = nowMs)
            signals[host] = updated
            evictStaleHostsLocked()
        }
        persistAsync()
        AppLog.i(
            TAG,
            "cdn_health event=$event host=$host " +
                "ready=${updated.readyCount} buf=${updated.bufferingCount} " +
                "err=${updated.playbackErrorCount} fft=${updated.firstFrameTimeoutCount} " +
                "score=${healthScoreOf(updated, nowMs)}"
        )
    }

    /** open 成功记一条 TTFB：时间半衰 EWMA，新样本权重随距上次采样时间增长。 */
    fun recordTtfb(url: String, ttfbMs: Long) {
        if (ttfbMs <= 0L) return
        val host = extractHost(url) ?: return
        val nowMs = clock()
        synchronized(signals) {
            ensureLoaded()
            val current = signals[host] ?: CdnHostSignal(host = host)
            val ewma = ewmaByTime(
                old = current.ttfbEwmaMs,
                sample = ttfbMs,
                lastSampleAtMs = current.ttfbUpdatedAtMs,
                nowMs = nowMs,
                halfLifeMs = TTFB_HALF_LIFE_MS
            )
            signals[host] = current.copy(
                ttfbEwmaMs = ewma,
                ttfbUpdatedAtMs = nowMs,
                lastUpdatedAtMs = nowMs
            )
        }
        persistAsync()
    }

    /** 卡顿降权：惩罚窗口内排序键按惩罚值计，过期自动恢复。 */
    fun penalizeHost(url: String) {
        val host = extractHost(url) ?: return
        val nowMs = clock()
        synchronized(signals) {
            ensureLoaded()
            val current = signals[host] ?: CdnHostSignal(host = host)
            signals[host] = current.copy(
                ttfbPenalizedUntilMs = nowMs + TTFB_PENALTY_MS,
                lastUpdatedAtMs = nowMs
            )
        }
        persistAsync()
        AppLog.i(TAG, "cdn_penalize host=$host until=+${TTFB_PENALTY_MS / 60_000}min")
    }

    /**
     * 记一条实测吞吐（视频主承载流传码率上下文参与余量与解禁，音频流传 0 只记账）。
     * [contextBitrateBps] > 0 时计算 margin 并在 ≥ [MARGIN_RECOVER] 时清除贴地档位（滞回解禁）。
     */
    fun recordThroughput(url: String, bytesPerSecond: Long, contextBitrateBps: Long = 0L) {
        if (bytesPerSecond <= 0L) return
        val host = extractHost(url) ?: return
        val nowMs = clock()
        val updated: CdnHostSignal
        synchronized(signals) {
            ensureLoaded()
            val current = signals[host] ?: CdnHostSignal(host = host)
            val alpha = 1.0 - Math.pow(0.5, 1.0 / THROUGHPUT_HALF_LIFE_SAMPLES)
            val ewma = if (current.throughputSampleCount <= 0) {
                bytesPerSecond
            } else {
                (bytesPerSecond * alpha + current.throughputEwmaBps * (1.0 - alpha)).toLong()
            }
            var starved = current.starvedBitrateBps
            if (starved > 0L && contextBitrateBps > 0L) {
                val margin = marginOf(ewma, contextBitrateBps)
                if (margin >= MARGIN_RECOVER) {
                    starved = 0L
                    AppLog.i(TAG, "cdn_starve_clear host=$host margin=${"%.2f".format(margin)}")
                }
            }
            updated = current.copy(
                throughputEwmaBps = ewma,
                throughputSampleCount = current.throughputSampleCount + 1,
                starvedBitrateBps = starved,
                lastUpdatedAtMs = nowMs
            )
            signals[host] = updated
        }
        persistAsync()
        val margin = if (contextBitrateBps > 0L) {
            marginOf(updated.throughputEwmaBps, contextBitrateBps)
        } else {
            -1f
        }
        AppLog.i(
            TAG,
            "cdn_sample host=$host rate=${"%.2f".format(updated.throughputEwmaBps / 1_000_000.0)}MB/s " +
                "margin=${if (margin >= 0f) "%.2f".format(margin) else "n/a"} " +
                "starved=${updated.starvedBitrateBps}"
        )
    }

    /** 贴地落档：记下该 host 喂不饱的流码率。多档取 min（连低码率都贴地是更严重的信号）。 */
    fun markStarved(url: String, bitrateBps: Long) {
        if (bitrateBps <= 0L) return
        val host = extractHost(url) ?: return
        val nowMs = clock()
        synchronized(signals) {
            ensureLoaded()
            val current = signals[host] ?: CdnHostSignal(host = host)
            val previous = current.starvedBitrateBps
            val next = if (previous <= 0L) bitrateBps else minOf(previous, bitrateBps)
            signals[host] = current.copy(starvedBitrateBps = next, lastUpdatedAtMs = nowMs)
            AppLog.i(TAG, "cdn_starve host=$host bitrate=${bitrateBps}bps starved=$next")
        }
        persistAsync()
    }

    // ===== 查询 API =====

    /** 该 host 近期吞吐 EWMA（B/s）；无记录返回 -1（排序沉底与决策都不掺和，避免冤枉新节点）。 */
    fun throughputEwmaBps(url: String): Long {
        val host = extractHost(url) ?: return -1L
        return signals[host]?.throughputEwmaBps?.takeIf { it > 0L } ?: -1L
    }

    /** 吞吐余量 = 吞吐供给能力 / 流码率需求；无吞吐记录或无码率上下文返回 -1。 */
    fun margin(url: String, contextBitrateBps: Long): Float {
        if (contextBitrateBps <= 0L) return -1f
        val ewma = throughputEwmaBps(url)
        if (ewma <= 0L) return -1f
        return marginOf(ewma, contextBitrateBps)
    }

    /**
     * 余量换算（单位对齐的核心）：吞吐记账单位是 B/s（字节，日志 MB/s 直观），
     * B 站 bandwidth 字段是 bit/s——差 8 倍，直接相除会把 margin 低估 8 倍，
     * 健康节点（真实余量 3~5 倍）会被误判贴地（MuMu 实测 28.53MB/s/margin=34 实锤）。
     */
    internal fun marginOf(throughputBytesPerSecond: Long, streamBitrateBps: Long): Float {
        if (streamBitrateBps <= 0L) return -1f
        return throughputBytesPerSecond * 8f / streamBitrateBps
    }

    /** 健康分（只读不推进衰减）：1000 + ready×25(封顶250) − error×220 − fft×180 − buffering×55。 */
    fun healthScore(url: String): Long {
        val host = extractHost(url) ?: return NEUTRAL_SCORE
        val signal = signals[host] ?: return NEUTRAL_SCORE
        return healthScoreOf(signal, clock())
    }

    // ===== 候选池合成 =====

    /**
     * 大陆 upos 节点池：B 站签名在 path+query 上，兼容节点认同一份签名——拿 playurl
     * 下发的普通地址当 donor，把 host 换成本池任一节点即得新候选（BTR 同款思路，
     * 顺序沿用其社区实测偏好序；mirrorcoso1 近核心机房——BTR issue#44 验证可用）。
     * playurl 常只下发 2~4 个地址，扩池让 rotate 的"更优候选"从碰运气变成大概率。
     */
    private val MAINLAND_CDN_HOSTS = listOf(
        "upos-sz-mirrorali.bilivideo.com",
        "upos-sz-mirrorhw.bilivideo.com",
        "upos-sz-mirrorbos.bilivideo.com",
        "upos-sz-mirror08c.bilivideo.com",
        "upos-sz-mirrorbd.bilivideo.com",
        "upos-sz-mirror14b.bilivideo.com",
        "upos-sz-estgoss.bilivideo.com",
        "upos-sz-mirrorcos.bilivideo.com",
        "upos-sz-mirrorcoso1.bilivideo.com"
    )

    private data class MediaUrlParts(val host: String, val path: String, val query: String?)

    /** 智能选线开着才扩池；关闭时与旧行为逐字节一致。 */
    internal fun expandCandidates(urls: List<String>): List<String> =
        expandCandidatesInternal(urls, isEnabled())

    /**
     * 候选池合成纯函数（单测直接喂开关状态）：原始地址在前，合成候选垫后——
     * 排序时合成候选健康分中性、TTFB 无记录自然沉底，不干扰既有偏好；实测 403
     * 会被 FailoverDataSource 的 attempt 循环跳过并记健康分沉底，无机制冲突。
     * 幂等：已合成过的列表再次合成，distinct 兜底不膨胀（rotate 重排重入安全）。
     */
    internal fun expandCandidatesInternal(urls: List<String>, enabled: Boolean): List<String> {
        if (!enabled || urls.isEmpty()) return urls
        // donor 准入：普通（非中继）的 bilivideo 系或 akamai DASH 分片地址。直播流
        //（.flv 路径）、progressive mp4、其他域都不合成——直播签名是另一套，upos 节点不认。
        val donors = urls.mapNotNull { url ->
            val parts = parseMediaUrlParts(url) ?: return@mapNotNull null
            if ((isBilivideoMediaHost(parts.host) || isAkamaiHost(parts.host)) &&
                !isRelayMediaUrl(parts.host, parts.path, parts.query) &&
                parts.path.endsWith(".m4s", ignoreCase = true)
            ) url to parts.host else null
        }
        val donor = donors.firstOrNull { !isAkamaiHost(it.second) } ?: donors.firstOrNull() ?: return urls
        // akamai 签名兼容节点同样认可（BTR issue#9：海外账号只发 akamaized.net 时的唯一出路），
        // 但仅在没有任何普通 donor 时才退而求其次。
        val synthetic = MAINLAND_CDN_HOSTS.asSequence()
            .filter { it != donor.second }
            .mapNotNull { host -> swapUrlAuthority(donor.first, host) }
            .toList()
        if (synthetic.isEmpty()) return urls
        val expanded = (urls + synthetic).distinct()
        if (expanded.size > urls.size) {
            AppLog.i(
                TAG,
                "cdn_expand from=${urls.size} to=${expanded.size} donor=${donor.second} " +
                    "added=" + (expanded.size - urls.size)
            )
        }
        return expanded
    }

    private fun parseMediaUrlParts(url: String): MediaUrlParts? = runCatching {
        val uri = URI(url)
        val host = uri.host?.lowercase(Locale.US) ?: return null
        MediaUrlParts(host = host, path = uri.path ?: "", query = uri.rawQuery)
    }.getOrNull()

    private fun isBilivideoMediaHost(host: String): Boolean =
        host.endsWith(".bilivideo.com") || host.endsWith(".bilivideo.cn") || host.endsWith(".bilivideo.net")

    private fun isAkamaiHost(host: String): Boolean = host.endsWith(".akamaized.net")

    /** P2P/PCDN 中继（mcdn 系）的签名只在relay上有效，搬到普通节点必 403（BTR 实锤）。 */
    private fun isRelayMediaUrl(host: String, path: String, query: String?): Boolean {
        if (host.startsWith("mcdn.") || host.contains(".mcdn.")) return true
        if (path.startsWith("/v1/resource/", ignoreCase = true)) return true
        if (query != null) {
            for (pair in query.split('&')) {
                if (pair.startsWith("os=", ignoreCase = true) &&
                    pair.substringAfter('=').equals("mcdn", ignoreCase = true)
                ) return true
            }
        }
        return false
    }

    /**
     * authority 整段（host[:port]）字符串手术替换：java.net.URI 重建会二次编码已编码的
     * path/query；顺带剥掉 peer CDN 携带的非标端口（如 :4483）。
     */
    private fun swapUrlAuthority(url: String, newHost: String): String? {
        val schemeIndex = url.indexOf("://")
        if (schemeIndex < 0) return null
        val authorityStart = schemeIndex + 3
        if (authorityStart >= url.length) return null
        val authorityEnd = url.indexOfAny(charArrayOf('/', '?', '#'), authorityStart)
        val tailStart = if (authorityEnd < 0) url.length else authorityEnd
        return url.substring(0, authorityStart) + newHost + url.substring(tailStart)
    }

    // ===== 排序 =====

    /**
     * 候选池合成 + 排序（原 sortUrlsByLatency 的升级）：
     * 先把 playurl 已签名地址经 [expandCandidatesInternal] 换 host 扩池（原始地址优先、
     * 合成候选垫后），再按 cdn 基础偏好 → 贴地档位沉底（相对当前流码率）→ 绝对慢吞吐
     * 沉底 → 健康分 → TTFB 重排。[contextBitrateBps] 为当前流码率，0 时退化为绝对阈值口径。
     */
    fun rankCandidates(urls: List<String>, contextBitrateBps: Long): List<String> {
        val expanded = expandCandidates(urls)
        if (expanded.size <= 1) return expanded
        val nowMs = clock()
        val useSmartRanking = isEnabled()
        val ranked = rankCandidatesInternal(
            urls = expanded,
            contextBitrateBps = contextBitrateBps,
            signalsSnapshot = synchronized(signals) { signals.toMap() },
            useSmartRanking = useSmartRanking,
            nowMs = nowMs
        )
        if (useSmartRanking && expanded.any { throughputEwmaBps(it) >= 0L }) {
            AppLog.i(
                TAG,
                "cdn_rank bitrate=${if (contextBitrateBps > 0L) "${contextBitrateBps / 1000}kbps" else "n/a"} " +
                    "hosts=" + ranked.joinToString(" -> ") { url ->
                        val host = extractHost(url) ?: "?"
                        val signal = synchronized(signals) { signals[host] }
                        buildString {
                            append(host)
                            val ewma = signal?.throughputEwmaBps?.takeIf { it > 0L }
                            if (ewma != null) {
                                append("(").append("%.1f".format(ewma / 1_000_000.0)).append("MB/s")
                                if (contextBitrateBps > 0L) {
                                    append(" m=").append("%.1f".format(ewma.toFloat() / contextBitrateBps))
                                }
                                if (signal.starvedBitrateBps > 0L) {
                                    append(" starved=").append(signal.starvedBitrateBps / 1000).append("k")
                                }
                                append(")")
                            }
                        }
                    }
            )
        }
        return ranked
    }

    /** 纯函数排序核心（单测直接喂快照，不碰 object 状态与设置开关）。 */
    internal fun rankCandidatesInternal(
        urls: List<String>,
        contextBitrateBps: Long,
        signalsSnapshot: Map<String, CdnHostSignal>,
        useSmartRanking: Boolean,
        nowMs: Long
    ): List<String> {
        fun signalOf(url: String): CdnHostSignal? = extractHost(url)?.let { signalsSnapshot[it] }
        return urls.sortedWith(
            compareBy(
                { cdnPreference(it) },
                // 贴地档位沉底：当前流码率落在该 host 实测喂不饱的档位内（×0.8 容差）。
                {
                    val signal = signalOf(it)
                    val starved = signal?.starvedBitrateBps ?: 0L
                    if (useSmartRanking && starved > 0L && contextBitrateBps > 0L &&
                        contextBitrateBps >= starved * STARVED_APPLY_FACTOR
                    ) 1 else 0
                },
                // 绝对慢吞吐兜底：零码率上下文时仍能压住烂吞吐节点（issue#114 mirrorhwb 实锤）。
                {
                    val ewma = signalOf(it)?.throughputEwmaBps ?: -1L
                    if (useSmartRanking && ewma in 1 until SLOW_HOST_BYTES_PER_SECOND) 1 else 0
                },
                { if (useSmartRanking) -healthScoreOf(signalOf(it), nowMs) else 0L },
                { effectiveTtfbMs(signalOf(it), nowMs) }
            )
        )
    }

    // ===== 内部实现 =====

    private const val NEUTRAL_SCORE = 1_000L

    private fun healthScoreOf(signal: CdnHostSignal?, nowMs: Long): Long {
        if (signal == null) return NEUTRAL_SCORE
        val periods = decayPeriods(signal, nowMs)
        val ready = (signal.readyCount ushr periods).coerceAtMost(10)
        val buffering = signal.bufferingCount ushr periods
        val error = signal.playbackErrorCount ushr periods
        val firstFrameTimeout = signal.firstFrameTimeoutCount ushr periods
        return NEUTRAL_SCORE +
            ready * 25L -
            error * 220L -
            firstFrameTimeout * 180L -
            buffering * 55L
    }

    /** 排序用 TTFB：惩罚窗口内按惩罚值计，无样本返回 MAX（新 host 靠前面几键分胜负）。 */
    private fun effectiveTtfbMs(signal: CdnHostSignal?, nowMs: Long): Long {
        if (signal == null || signal.ttfbEwmaMs <= 0L) return Long.MAX_VALUE
        if (signal.ttfbPenalizedUntilMs > nowMs) return TTFB_PENALTY_VALUE_MS
        return signal.ttfbEwmaMs
    }

    private fun ewmaByTime(
        old: Long,
        sample: Long,
        lastSampleAtMs: Long,
        nowMs: Long,
        halfLifeMs: Long
    ): Long {
        if (old <= 0L || lastSampleAtMs <= 0L) return sample
        val elapsedMs = (nowMs - lastSampleAtMs).coerceAtLeast(0L)
        val alpha = 1.0 - Math.pow(0.5, elapsedMs.toDouble() / halfLifeMs)
        return (sample * alpha + old * (1.0 - alpha)).toLong().coerceAtLeast(1L)
    }

    /** 按流逝时间右移事件计数（半衰），推进衰减窗口；无记录返回 null。 */
    private fun decayHealth(signal: CdnHostSignal?, nowMs: Long): CdnHostSignal? {
        if (signal == null) return null
        val periods = decayPeriods(signal, nowMs)
        if (periods <= 0) return signal
        return signal.copy(
            readyCount = signal.readyCount ushr periods,
            bufferingCount = signal.bufferingCount ushr periods,
            playbackErrorCount = signal.playbackErrorCount ushr periods,
            firstFrameTimeoutCount = signal.firstFrameTimeoutCount ushr periods,
            healthWindowStartedAtMs = nowMs
        )
    }

    private fun decayPeriods(signal: CdnHostSignal, nowMs: Long): Int {
        val baselineMs = signal.healthWindowStartedAtMs.takeIf { it > 0L }
            ?: signal.lastUpdatedAtMs.takeIf { it > 0L }
            ?: return 0
        val elapsedMs = (nowMs - baselineMs).coerceAtLeast(0L)
        return (elapsedMs / DECAY_PERIOD_MS).toInt().coerceAtMost(MAX_DECAY_PERIODS)
    }

    private fun evictStaleHostsLocked() {
        if (signals.size <= MAX_HOSTS) return
        signals.entries
            .sortedBy { it.value.lastUpdatedAtMs }
            .take(signals.size - MAX_HOSTS)
            .forEach { (key, _) -> signals.remove(key) }
    }

    // ===== 持久化 =====

    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val stored = runCatching {
            appSettings()?.getCachedString(STORE_KEY)
        }.getOrNull()
        if (stored.isNullOrBlank()) return
        runCatching {
            // 值声明为可空：Gson 不认 Kotlin 的非空类型，脏档案里出现 null 时下面 signal == null 才拦得住
            val parsed: MutableMap<String, CdnHostSignal?> = gson.fromJson(stored, recordType)
            parsed.forEach { (host, signal) ->
                // 脏数据判据：正常运行记录的 host 恒等于 map key（v2.1.27 起播即崩防线）。
                if (host.isBlank() || signal == null || signal.host != host) {
                    return@forEach
                }
                signals[host] = signal
            }
            AppLog.i(TAG, "cdn_profile loaded hosts=${signals.size}")
        }.onFailure { error ->
            AppLog.w(TAG, "cdn_profile load failed, reset: ${error.javaClass.simpleName}:${error.message}")
            signals.clear()
        }
    }

    private fun persistAsync() {
        val snapshot: String
        synchronized(signals) {
            snapshot = gson.toJson(signals.toMap())
        }
        runCatching {
            appSettings()?.putStringAsync(STORE_KEY, snapshot)
        }.onFailure { error ->
            AppLog.w(TAG, "cdn_profile persist failed: ${error.javaClass.simpleName}:${error.message}")
        }
    }

    private fun appSettings(): AppSettingsDataStore? {
        return runCatching { KoinPlatform.getKoin().get<AppSettingsDataStore>() }.getOrNull()
    }

    private fun cdnPreference(url: String): Int {
        val host = extractHost(url) ?: return CDN_PREF_OTHER
        val isMcdn = host.contains("mcdn") && host.contains("bilivideo")
        val isBilivideo = host.contains("bilivideo") && !isMcdn
        return when {
            isBilivideo -> CDN_PREF_BILIVIDEO
            isMcdn -> CDN_PREF_MCDN
            else -> CDN_PREF_OTHER
        }
    }

    /** 纯字符串解析 host（java.net.URI），不依赖 android.net.Uri——JVM 单测可用。 */
    internal fun extractHost(url: String): String? {
        return runCatching { URI(url).host?.lowercase(Locale.US) }.getOrNull()
    }

    /** 单测隔离：清空内存与加载标记（生产代码不调用）。 */
    internal fun resetForTest() {
        synchronized(signals) {
            signals.clear()
            lastBufferingAtMsByHost.clear()
            loaded = false
        }
    }

    /** 单测快照：rankCandidatesInternal 需要纯数据输入（生产代码不调用）。 */
    internal fun snapshotForTest(): Map<String, CdnHostSignal> {
        synchronized(signals) { return signals.toMap() }
    }

    private const val CDN_PREF_BILIVIDEO = 0
    private const val CDN_PREF_MCDN = 1
    private const val CDN_PREF_OTHER = 2
}
