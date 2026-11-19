package com.mytvb.feature.player

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * CdnProfile 统一画像的纯逻辑验证：吞吐 EWMA、贴地档位相对沉底与滞回解禁、
 * 绝对慢吞吐兜底、事件计数半衰与 BUFFERING 节流、TTFB 惩罚窗口、排序口径。
 * 时间源经 clock 注入冻结，不依赖真实墙钟。
 */
class CdnProfileTest {

    private val hostA = "https://upos-sz-esta.bilivideo.com/upgcxcode/a.mp4"
    private val hostB = "https://upos-sz-mirrorb.bilivideo.com/upgcxcode/b.mp4"

    @Before
    fun setUp() {
        CdnProfile.resetForTest()
        CdnProfile.clock = { 1_000_000L }
    }

    @After
    fun tearDown() {
        CdnProfile.resetForTest()
        CdnProfile.clock = { System.currentTimeMillis() }
    }

    private fun advance(ms: Long) {
        val base = CdnProfile.clock()
        CdnProfile.clock = { base + ms }
    }

    // ===== 吞吐 EWMA =====

    @Test
    fun `first throughput sample replaces ewma`() {
        CdnProfile.recordThroughput(hostA, 2_000_000L)
        assertEquals(2_000_000L, CdnProfile.throughputEwmaBps(hostA))
    }

    @Test
    fun `throughput ewma converges toward sustained samples`() {
        CdnProfile.recordThroughput(hostA, 4_000_000L)
        // 11 条 1MB/s 样本后 EWMA 从 4MB/s 显著回落但未到 1MB/s（半衰 12 条：
        // 数学值 = 1 + 3×0.9433^11 ≈ 2.6MB/s，方向单调下降即可）
        repeat(11) { CdnProfile.recordThroughput(hostA, 1_000_000L) }
        val ewma = CdnProfile.throughputEwmaBps(hostA)
        assertTrue("ewma=$ewma 应回落到 1~3.2MB/s 区间", ewma in 1_000_000L..3_200_000L)
    }

    @Test
    fun `throughput unknown host returns -1`() {
        assertEquals(-1L, CdnProfile.throughputEwmaBps(hostA))
        assertEquals(-1f, CdnProfile.margin(hostA, 8_500_000L))
    }

    // ===== margin =====

    @Test
    fun `margin scales bytes per second against bits per second bitrate`() {
        // 防回归（MuMu 实测实锤）：吞吐记账是 B/s、B 站 bandwidth 是 bit/s，差 8 倍。
        // 2MB/s 吞吐喂 8Mbps 流：真实余量 = 16Mbps/8Mbps = 2 倍，不是 0.25。
        CdnProfile.recordThroughput(hostA, 2_000_000L)
        assertEquals(2f, CdnProfile.margin(hostA, 8_000_000L), 0.01f)
    }

    @Test
    fun `margin requires positive bitrate context`() {
        CdnProfile.recordThroughput(hostA, 2_000_000L)
        assertEquals(-1f, CdnProfile.margin(hostA, 0L), 0.001f)
    }

    // ===== 贴地档位：落档 / 相对沉底 / 滞回解禁 =====

    @Test
    fun `starved host sinks only when stream bitrate hits starved band`() {
        CdnProfile.markStarved(hostA, 8_500_000L)
        val ranked4k = CdnProfile.rankCandidatesInternal(
            urls = listOf(hostA, hostB),
            contextBitrateBps = 8_500_000L,
            signalsSnapshot = CdnProfile.snapshotForTest(),
            useSmartRanking = true,
            nowMs = CdnProfile.clock()
        )
        assertEquals("4K50 流码率落在贴地档位内应沉底", hostB, ranked4k.first())

        val ranked1080p = CdnProfile.rankCandidatesInternal(
            urls = listOf(hostA, hostB),
            contextBitrateBps = 2_300_000L,
            signalsSnapshot = CdnProfile.snapshotForTest(),
            useSmartRanking = true,
            nowMs = CdnProfile.clock()
        )
        assertEquals("1080P 流码率低于贴地档位×0.8 不沉底", hostA, ranked1080p.first())
    }

    @Test
    fun `starved band boundary at factor 0_8`() {
        CdnProfile.markStarved(hostA, 1_000_000L)
        val snapshot = CdnProfile.snapshotForTest()
        // bitrate = starved × 0.8 恰好命中（>= 判定）
        val atBoundary = CdnProfile.rankCandidatesInternal(
            listOf(hostA, hostB), 800_000L, snapshot, true, CdnProfile.clock()
        )
        assertEquals(hostB, atBoundary.first())
        // 略低于边界不命中
        val belowBoundary = CdnProfile.rankCandidatesInternal(
            listOf(hostA, hostB), 790_000L, snapshot, true, CdnProfile.clock()
        )
        assertEquals(hostA, belowBoundary.first())
    }

    @Test
    fun `recovered margin clears starved band`() {
        CdnProfile.markStarved(hostA, 8_500_000L)
        // 吞吐拉到 margin ≥ 2.5（8.5Mbps 流需 ≥2.66MB/s 供给）
        CdnProfile.recordThroughput(hostA, 2_700_000L, contextBitrateBps = 8_500_000L)
        val snapshot = CdnProfile.snapshotForTest()
        assertEquals("解禁后 starved 应清零", 0L, snapshot["upos-sz-esta.bilivideo.com"]?.starvedBitrateBps)
        val ranked = CdnProfile.rankCandidatesInternal(
            listOf(hostA, hostB), 8_500_000L, snapshot, true, CdnProfile.clock()
        )
        assertEquals(hostA, ranked.first())
    }

    @Test
    fun `dead zone margin does not clear starved band`() {
        CdnProfile.markStarved(hostA, 8_500_000L)
        // margin 落在死区（1.5~2.5 之间）不清档：8.5Mbps × 2.0 / 8 = 2.125MB/s
        CdnProfile.recordThroughput(hostA, 2_125_000L, contextBitrateBps = 8_500_000L)
        val signal = CdnProfile.snapshotForTest()["upos-sz-esta.bilivideo.com"]
        assertEquals("死区内 starved 保持", 8_500_000L, signal?.starvedBitrateBps)
    }

    @Test
    fun `mark starved keeps the severest band`() {
        CdnProfile.markStarved(hostA, 8_500_000L)
        CdnProfile.markStarved(hostA, 2_300_000L)
        val signal = CdnProfile.snapshotForTest()["upos-sz-esta.bilivideo.com"]
        assertEquals("连低码率都贴地是更严重信号，取 min", 2_300_000L, signal?.starvedBitrateBps)
    }

    // ===== 绝对慢吞吐兜底 =====

    @Test
    fun `absolute slow throughput sinks even without bitrate context`() {
        repeat(20) { CdnProfile.recordThroughput(hostA, 500_000L) }
        val ranked = CdnProfile.rankCandidatesInternal(
            listOf(hostA, hostB), 0L, CdnProfile.snapshotForTest(), true, CdnProfile.clock()
        )
        assertEquals("无码率上下文时绝对慢吞吐沉底", hostB, ranked.first())
    }

    @Test
    fun `no throughput record does not sink`() {
        val ranked = CdnProfile.rankCandidatesInternal(
            listOf(hostA, hostB), 8_500_000L, CdnProfile.snapshotForTest(), true, CdnProfile.clock()
        )
        assertEquals("无记录白纸不沉底（避免冤枉新节点）", hostA, ranked.first())
    }

    // ===== 事件计数：半衰 / 节流 / 分值口径 =====

    @Test
    fun `buffering throttled within 30s`() {
        CdnProfile.recordEvent(hostA, CdnProfile.CdnProfileEvent.BUFFERING)
        CdnProfile.recordEvent(hostA, CdnProfile.CdnProfileEvent.BUFFERING)
        val signal = CdnProfile.snapshotForTest()["upos-sz-esta.bilivideo.com"]!!
        assertEquals("30s 内第二条 BUFFERING 应被节流吞掉", 1, signal.bufferingCount)

        advance(31_000L)
        CdnProfile.recordEvent(hostA, CdnProfile.CdnProfileEvent.BUFFERING)
        assertEquals("过了节流窗后应再记一条", 2, CdnProfile.snapshotForTest()["upos-sz-esta.bilivideo.com"]!!.bufferingCount)
    }

    @Test
    fun `event counts decay after half-life period`() {
        repeat(3) {
            CdnProfile.recordEvent(hostA, CdnProfile.CdnProfileEvent.BUFFERING)
            advance(CdnProfile.BUFFERING_THROTTLE_MS + 1L)
        }
        val before = CdnProfile.snapshotForTest()["upos-sz-esta.bilivideo.com"]!!
        assertEquals(3, before.bufferingCount)

        advance(30 * 60 * 1000L + 1L)
        CdnProfile.recordEvent(hostA, CdnProfile.CdnProfileEvent.READY)
        val after = CdnProfile.snapshotForTest()["upos-sz-esta.bilivideo.com"]!!
        assertEquals("3 ushr 1 = 1", 1, after.bufferingCount)
    }

    @Test
    fun `health score weights and ready cap`() {
        repeat(20) {
            CdnProfile.recordEvent(hostA, CdnProfile.CdnProfileEvent.READY)
        }
        CdnProfile.recordEvent(hostB, CdnProfile.CdnProfileEvent.PLAYBACK_ERROR)
        val scoreA = CdnProfile.healthScore(hostA)
        val scoreB = CdnProfile.healthScore(hostB)
        assertEquals("ready 封顶 10×25=250", 1_250L, scoreA)
        assertEquals("error -220", 780L, scoreB)
    }

    @Test
    fun `health score orders before ttfb when smart ranking on`() {
        repeat(20) { CdnProfile.recordEvent(hostA, CdnProfile.CdnProfileEvent.READY) }
        CdnProfile.recordEvent(hostB, CdnProfile.CdnProfileEvent.BUFFERING)
        CdnProfile.recordTtfb(hostA, 500L)
        CdnProfile.recordTtfb(hostB, 50L)
        val ranked = CdnProfile.rankCandidatesInternal(
            listOf(hostA, hostB), 0L, CdnProfile.snapshotForTest(), true, CdnProfile.clock()
        )
        assertEquals("健康分 1250 应压过更快的 TTFB", hostA, ranked.first())
    }

    // ===== TTFB 惩罚窗口 =====

    @Test
    fun `penalty sinks host until expiry`() {
        CdnProfile.recordTtfb(hostA, 50L)
        CdnProfile.recordTtfb(hostB, 200L)
        val before = CdnProfile.rankCandidatesInternal(
            listOf(hostA, hostB), 0L, CdnProfile.snapshotForTest(), true, CdnProfile.clock()
        )
        assertEquals("未惩罚时按 TTFB 排序", hostA, before.first())

        CdnProfile.penalizeHost(hostA)
        val penalized = CdnProfile.rankCandidatesInternal(
            listOf(hostA, hostB), 0L, CdnProfile.snapshotForTest(), true, CdnProfile.clock()
        )
        assertEquals("惩罚窗口内沉底", hostB, penalized.first())

        advance(10 * 60 * 1000L + 1L)
        val expired = CdnProfile.rankCandidatesInternal(
            listOf(hostA, hostB), 0L, CdnProfile.snapshotForTest(), true, CdnProfile.clock()
        )
        assertEquals("惩罚过期恢复按 TTFB 排序", hostA, expired.first())
    }

    // ===== 开关关闭退化 =====

    @Test
    fun `smart ranking off degrades to preference plus ttfb`() {
        CdnProfile.markStarved(hostA, 8_500_000L)
        repeat(20) { CdnProfile.recordThroughput(hostA, 500_000L) }
        CdnProfile.recordEvent(hostA, CdnProfile.CdnProfileEvent.PLAYBACK_ERROR)
        CdnProfile.recordTtfb(hostA, 50L)
        CdnProfile.recordTtfb(hostB, 500L)
        val ranked = CdnProfile.rankCandidatesInternal(
            listOf(hostA, hostB), 8_500_000L, CdnProfile.snapshotForTest(), false, CdnProfile.clock()
        )
        assertEquals("关闭智能选线后仅按偏好+TTFB，坏画像不参与", hostA, ranked.first())
    }

    // ===== 候选基础偏好 =====

    @Test
    fun `mcdn ranks below direct bilivideo`() {
        val direct = "https://upos-sz-esta.bilivideo.com/a.mp4"
        val mcdn = "https://mcdn.bilivideo.com/b.mp4"
        // 注意：既有口径按 host 含 "bilivideo" 判定，cnh3chain 系（含 bilivideo 字样）
        // 也算直连偏好；真正的 OTHER 是不含 bilivideo 的外部 CDN 域。
        val other = "https://upos-hz-mirrorakam.akamaized.net/c.m4s"
        val ranked = CdnProfile.rankCandidatesInternal(
            listOf(other, mcdn, direct), 0L, emptyMap(), false, CdnProfile.clock()
        )
        assertEquals(listOf(direct, mcdn, other), ranked)
    }

    // ===== 候选池合成（P1：签名地址换 host 扩池）=====

    private val signedBase =
        "https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/98/98/xx/1234567890-1-30280.m4s" +
            "?e=ut8egmr2&os=upos&tr=4sc&deadline=1732000000&gen=playsoftv2"
    private val signedBackup =
        "https://upos-sz-mirrorhw.bilivideo.com/upgcxcode/98/98/xx/1234567890-1-30280.m4s" +
            "?e=ut8egmr3&os=upos&tr=4sc&deadline=1732000001&gen=playsoftv2"
    private val mainlandPool = setOf(
        "upos-sz-mirrorali.bilivideo.com", "upos-sz-mirrorhw.bilivideo.com",
        "upos-sz-mirrorbos.bilivideo.com", "upos-sz-mirror08c.bilivideo.com",
        "upos-sz-mirrorbd.bilivideo.com", "upos-sz-mirror14b.bilivideo.com",
        "upos-sz-estgoss.bilivideo.com", "upos-sz-mirrorcos.bilivideo.com",
        "upos-sz-mirrorcoso1.bilivideo.com"
    )

    @Test
    fun `expand synthesizes mainland hosts keeping originals first`() {
        val expanded = CdnProfile.expandCandidatesInternal(listOf(signedBase, signedBackup), enabled = true)
        // 2 原始 + 8 合成（池 9 个 host 减去 donor 自身 mirrorcos）
        assertEquals(10, expanded.size)
        assertEquals("原始地址保持在前，不被合成候选挤掉", listOf(signedBase, signedBackup), expanded.take(2))
        assertEquals("合成列表无重复", expanded.size, expanded.distinct().size)
        assertTrue(
            "新增候选全部来自大陆池且 mirrorcoso1 已入池",
            expanded.drop(2).all { CdnProfile.extractHost(it) in mainlandPool } &&
                expanded.any { CdnProfile.extractHost(it) == "upos-sz-mirrorcoso1.bilivideo.com" }
        )
        // 合成 URL 保留 donor 的 path+query（签名原样，只换了 authority）
        val synthesized = expanded.first { CdnProfile.extractHost(it) == "upos-sz-mirrorali.bilivideo.com" }
        assertTrue(synthesized.endsWith("?e=ut8egmr2&os=upos&tr=4sc&deadline=1732000000&gen=playsoftv2"))
    }

    @Test
    fun `expand is idempotent for already expanded list`() {
        val once = CdnProfile.expandCandidatesInternal(listOf(signedBase, signedBackup), enabled = true)
        val twice = CdnProfile.expandCandidatesInternal(once, enabled = true)
        assertEquals("rotate 重排重入合成不膨胀", once, twice)
    }

    @Test
    fun `relay mcdn address never becomes donor`() {
        // 中继三判据全覆盖：mcdn host / /v1/resource/ 路径 / os=mcdn 参数
        val mcdnHost = "https://mcdn.bilivideo.com/upgcxcode/xx.m4s?os=upos"
        val resourcePath = "https://upos-sz-mirrorcos.bilivideo.com/v1/resource/xx.m4s?os=upos"
        val osParam = "https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/xx.m4s?os=mcdn&tr=4sc"
        listOf(mcdnHost, resourcePath, osParam).forEach { url ->
            assertEquals(
                "中继签名搬到普通节点必 403，不参与合成",
                listOf(url), CdnProfile.expandCandidatesInternal(listOf(url), enabled = true)
            )
        }
    }

    @Test
    fun `akamai only address falls back to akamai donor`() {
        val akamai = "https://upos-sz-akamai.akamaized.net/upgcxcode/98/98/xx/1234567890-1-30280.m4s?e=xxx&os=akam"
        val expanded = CdnProfile.expandCandidatesInternal(listOf(akamai), enabled = true)
        assertEquals("akamai 不在池内，9 个池 host 全部合成", 10, expanded.size)
        assertEquals(akamai, expanded.first())
        assertTrue(expanded.drop(1).all { CdnProfile.extractHost(it) in mainlandPool })
    }

    @Test
    fun `live and non bilivideo urls are not expanded`() {
        val liveFlv = "https://cn-gotcha203.bilivideo.com/live-bld/6789/live.flv?expires=1732000000&len=0"
        val hdslb = "https://i0.hdslb.com/bfs/archive/cover.jpg"
        val progressiveMp4 = "https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/xx/30280.mp4?e=xxx"
        listOf(liveFlv, hdslb, progressiveMp4).forEach { url ->
            assertEquals(listOf(url), CdnProfile.expandCandidatesInternal(listOf(url), enabled = true))
        }
    }

    @Test
    fun `peer cdn port is stripped in synthetic urls`() {
        val ported = "https://upos-sz-mirrorcos.bilivideo.com:4483/upgcxcode/xx/1234567890-1-30280.m4s?e=xxx&os=upos"
        val expanded = CdnProfile.expandCandidatesInternal(listOf(ported), enabled = true)
        assertTrue(
            "authority 整段替换顺带剥掉非标端口",
            expanded.drop(1).none { it.contains(":4483") } && expanded.size == 9
        )
    }

    @Test
    fun `disabled switch keeps urls untouched`() {
        assertEquals(listOf(signedBase), CdnProfile.expandCandidatesInternal(listOf(signedBase), enabled = false))
    }
}
