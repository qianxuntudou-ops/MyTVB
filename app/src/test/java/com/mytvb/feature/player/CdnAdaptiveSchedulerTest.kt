package com.mytvb.feature.player

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * CdnAdaptiveScheduler 决策核心验证：贴地持续窗口、缓冲水位门、起播豁免、
 * 更优候选判定、配额与出口瓶颈宣判、会话切换重置。时间源冻结，margin 经
 * CdnProfile 真实记账路径构造（决策吃的就是画像查询结果）。
 */
class CdnAdaptiveSchedulerTest {

    private val bitrate = 1_000_000L
    private val currentUrl = "https://host-current.bilivideo.com/v.m4s"
    private val betterUrl = "https://host-better.bilivideo.com/b.m4s"

    private lateinit var scheduler: CdnAdaptiveScheduler
    private val sessionKey = Any()
    private var rotatedCount = 0

    @Before
    fun setUp() {
        CdnProfile.resetForTest()
        CdnProfile.clock = { 1_000_000L }
        scheduler = CdnAdaptiveScheduler()
        scheduler.clock = { 1_000_000L }
        rotatedCount = 0
    }

    @After
    fun tearDown() {
        CdnProfile.resetForTest()
        CdnProfile.clock = { System.currentTimeMillis() }
    }

    private fun advance(ms: Long) {
        val base = scheduler.clock()
        val now = base + ms
        scheduler.clock = { now }
        CdnProfile.clock = { now }
    }

    /** 给 [url] 记吞吐使 margin 精确等于给定值（margin = B/s×8/bps，反推字节速率）。 */
    private fun seedMargin(url: String, margin: Float) {
        CdnProfile.recordThroughput(url, (bitrate * margin / 8f).toLong(), bitrate)
    }

    private fun snapshot(
        bufferedAheadMs: Long = 10_000L,
        candidates: List<String> = listOf(betterUrl),
        smart: Boolean = true,
        active: Boolean = true,
        session: Any? = sessionKey,
        bitrateBps: Long = bitrate
    ): CdnAdaptiveScheduler.EvalSnapshot {
        return CdnAdaptiveScheduler.EvalSnapshot(
            smartRoutingEnabled = smart,
            active = active,
            bufferedAheadMs = bufferedAheadMs,
            currentUrl = currentUrl,
            streamBitrateBps = bitrateBps,
            candidateUrlsExcludingCurrent = candidates,
            sessionKey = session,
            onRotate = { rotatedCount++ }
        )
    }

    /** 推进到可决策状态并持续贴地一个完整窗口。 */
    private fun starveForFullWindow(bufferedAheadMs: Long = 10_000L) {
        seedMargin(currentUrl, 1.2f)
        scheduler.evaluate(snapshot(bufferedAheadMs = bufferedAheadMs)) // 会话登记
        advance(CdnAdaptiveScheduler.MIN_ELAPSED_MS + 1_000L)
        scheduler.evaluate(snapshot(bufferedAheadMs = bufferedAheadMs)) // starvedSince 起算
        advance(CdnAdaptiveScheduler.SUSTAIN_MS + 1_000L)
        scheduler.evaluate(snapshot(bufferedAheadMs = bufferedAheadMs)) // decide
    }

    @Test
    fun `no rotate within startup exemption`() {
        seedMargin(currentUrl, 1.2f)
        scheduler.evaluate(snapshot())
        advance(5_000L)
        scheduler.evaluate(snapshot())
        assertEquals("起播 30s 豁免期内不决策", 0, rotatedCount)
    }

    @Test
    fun `no rotate when buffered ahead sufficient`() {
        starveForFullWindow(bufferedAheadMs = 30_000L)
        assertEquals("缓冲攒得动（>15s）不折腾", 0, rotatedCount)
    }

    @Test
    fun `no rotate on healthy margin`() {
        seedMargin(currentUrl, 2.0f)
        scheduler.evaluate(snapshot())
        advance(CdnAdaptiveScheduler.MIN_ELAPSED_MS + CdnAdaptiveScheduler.SUSTAIN_MS + 10_000L)
        repeat(3) { scheduler.evaluate(snapshot()) }
        assertEquals("margin 健康永不 rotate", 0, rotatedCount)
    }

    @Test
    fun `no rotate when smart routing disabled`() {
        seedMargin(currentUrl, 1.2f)
        scheduler.evaluate(snapshot(smart = false))
        advance(CdnAdaptiveScheduler.MIN_ELAPSED_MS + CdnAdaptiveScheduler.SUSTAIN_MS + 10_000L)
        repeat(3) { scheduler.evaluate(snapshot(smart = false)) }
        assertEquals(0, rotatedCount)
    }

    @Test
    fun `rotate dispatches after sustained starvation with blank candidate`() {
        starveForFullWindow()
        assertEquals("贴地满窗口且存在白纸候选 → rotate", 1, rotatedCount)
    }

    @Test
    fun `starved host gets marked on rotate`() {
        starveForFullWindow()
        assertEquals(1, rotatedCount)
        val signal = CdnProfile.snapshotForTest()["host-current.bilivideo.com"]
        assertEquals("rotate 时对当前 host 落档贴地码率", bitrate, signal?.starvedBitrateBps)
    }

    @Test
    fun `no better candidate declares bottleneck`() {
        // 唯一其它候选实测 margin 1.2（<2.5 且有记录）→ 不算更优 → 出口瓶颈
        seedMargin(currentUrl, 1.2f)
        seedMargin(betterUrl, 1.2f)
        scheduler.evaluate(snapshot())
        advance(CdnAdaptiveScheduler.MIN_ELAPSED_MS + 1_000L)
        scheduler.evaluate(snapshot())
        advance(CdnAdaptiveScheduler.SUSTAIN_MS + 1_000L)
        scheduler.evaluate(snapshot())
        assertEquals("全池贴地不 rotate", 0, rotatedCount)
        // 瓶颈宣判后换白纸候选出现也不再生效（本视频内）
        scheduler.evaluate(snapshot(candidates = listOf("https://host-paper.bilivideo.com/p.m4s")))
        assertEquals("bottleneck 后停手", 0, rotatedCount)
    }

    @Test
    fun `rotate quota exhausts to bottleneck`() {
        // 第一轮 rotate
        starveForFullWindow()
        assertEquals(1, rotatedCount)
        // 第二轮：贴地重现满窗口（新候选 host-current 仍贴地）
        advance(CdnAdaptiveScheduler.SUSTAIN_MS + 1_000L)
        scheduler.evaluate(snapshot())
        advance(CdnAdaptiveScheduler.SUSTAIN_MS + 1_000L)
        scheduler.evaluate(snapshot())
        assertEquals("第二次 rotate 用掉配额", 2, rotatedCount)
        // 第三轮：配额用尽 → bottleneck，不再 rotate
        advance(CdnAdaptiveScheduler.SUSTAIN_MS + 1_000L)
        scheduler.evaluate(snapshot())
        advance(CdnAdaptiveScheduler.SUSTAIN_MS + 1_000L)
        scheduler.evaluate(snapshot())
        assertEquals("配额用尽后停手", 2, rotatedCount)
    }

    @Test
    fun `session change resets quota and bottleneck`() {
        starveForFullWindow()
        assertEquals(1, rotatedCount)
        // 新会话（切集/重建产生新 state）：豁免期 → 贴地起算 → 满窗口
        val newSession = Any()
        seedMargin(currentUrl, 1.2f)
        scheduler.evaluate(snapshot(session = newSession))
        advance(CdnAdaptiveScheduler.MIN_ELAPSED_MS + 1_000L)
        scheduler.evaluate(snapshot(session = newSession))
        advance(CdnAdaptiveScheduler.SUSTAIN_MS + 1_000L)
        scheduler.evaluate(snapshot(session = newSession))
        assertEquals("会话切换重置配额", 2, rotatedCount)
    }

    @Test
    fun `better candidate requires measured margin`() {
        // 白纸候选（无吞吐记录）允许尝试
        seedMargin(currentUrl, 1.2f)
        scheduler.evaluate(snapshot(candidates = listOf("https://host-paper.bilivideo.com/p.m4s")))
        advance(CdnAdaptiveScheduler.MIN_ELAPSED_MS + 1_000L)
        scheduler.evaluate(snapshot(candidates = listOf("https://host-paper.bilivideo.com/p.m4s")))
        advance(CdnAdaptiveScheduler.SUSTAIN_MS + 1_000L)
        scheduler.evaluate(snapshot(candidates = listOf("https://host-paper.bilivideo.com/p.m4s")))
        assertEquals(1, rotatedCount)
    }

    @Test
    fun `no bitrate context never rotates`() {
        seedMargin(currentUrl, 1.2f)
        scheduler.evaluate(snapshot(bitrateBps = 0L))
        advance(CdnAdaptiveScheduler.MIN_ELAPSED_MS + CdnAdaptiveScheduler.SUSTAIN_MS + 10_000L)
        repeat(3) { scheduler.evaluate(snapshot(bitrateBps = 0L)) }
        assertEquals("无码率上下文不预测", 0, rotatedCount)
    }

    @Test
    fun `throughput blank paper current host never rotates`() {
        // 当前 host 吞吐白纸（margin=-1）不预测，等实测沉淀
        scheduler.evaluate(snapshot())
        advance(CdnAdaptiveScheduler.MIN_ELAPSED_MS + CdnAdaptiveScheduler.SUSTAIN_MS + 10_000L)
        repeat(3) { scheduler.evaluate(snapshot()) }
        assertEquals(0, rotatedCount)
    }
}
