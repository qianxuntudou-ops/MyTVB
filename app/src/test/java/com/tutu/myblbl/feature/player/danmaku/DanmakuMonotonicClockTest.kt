package com.tutu.myblbl.feature.player.danmaku

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 引擎单调时钟钳制判定回归：
 * - 前进跟随；小幅回退（≤500ms，漂移硬校准回拉）吸收冻结；
 * - 大幅回退（>500ms，seek 回看/换视频起播）放行——修复换视频时序竞态：
 *   新视频位置 0 被旧视频残留高水位钳住，引擎时钟冻结导致"进入播放没弹幕
 *   + 在场弹幕卡住不动"。
 */
class DanmakuMonotonicClockTest {

    private val threshold = 500L

    @Test
    fun `forward position follows`() {
        assertEquals(1000L, resolveMonotonicClockMs(1000L, 500L, threshold))
        assertEquals(501L, resolveMonotonicClockMs(501L, 500L, threshold))
    }

    @Test
    fun `small backward drift is absorbed to floor`() {
        assertEquals(500L, resolveMonotonicClockMs(499L, 500L, threshold))
        assertEquals(500L, resolveMonotonicClockMs(0L, 500L, threshold))
        // 恰好等于阈值：仍属小幅回退（与 act 的 > 判定一致）。
        assertEquals(1000L, resolveMonotonicClockMs(500L, 1000L, threshold))
    }

    @Test
    fun `large backward jump releases floor`() {
        // 换视频：旧视频残留 598820ms 高水位，新视频从 0 播放。
        assertEquals(0L, resolveMonotonicClockMs(0L, 598820L, threshold))
        // 超过阈值 1ms 即放行。
        assertEquals(499L, resolveMonotonicClockMs(499L, 1000L, threshold))
    }

    @Test
    fun `release is idempotent self healing`() {
        // BUFFERING 期旧位置回灌抬回高水位后，下一帧新位置再次放行。
        var floor = 0L
        floor = resolveMonotonicClockMs(126417L, floor, threshold)
        floor = resolveMonotonicClockMs(0L, floor, threshold) // seek 放行
        floor = resolveMonotonicClockMs(126417L, floor, threshold) // 旧 raw 回灌
        floor = resolveMonotonicClockMs(0L, floor, threshold) // 新 raw 再次放行
        assertEquals(0L, floor)
    }
}
