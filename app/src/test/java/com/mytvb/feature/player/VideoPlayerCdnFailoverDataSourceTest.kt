package com.mytvb.feature.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VideoPlayerCdnFailoverDataSource 的 JVM 可测部分：
 * - rotate 续传的 Range 数学（position 累加 / 确定长度扣减 / UNSET 透传）；
 * - state 的 rotate 请求标志语义（置位一次消费一次，不依赖 Uri）。
 * 连接切换全流程依赖 android.net.Uri（JVM 为 stub），由 MuMu 实测覆盖
 * （debug 包临时调高 MARGIN_DEGRADE 验证 cdn_rotate 全链）。
 */
class VideoPlayerCdnFailoverDataSourceTest {

    // ===== Range 续传数学 =====

    @Test
    fun `rotated range advances position by bytes read`() {
        val (position, length) = computeRotatedRange(
            position = 1_000_000L, length = 5_000_000L, bytesSinceOpen = 1_200_000L
        )
        assertEquals(2_200_000L, position)
        assertEquals(3_800_000L, length)
    }

    @Test
    fun `rotated range passes unset length through`() {
        // 负值（LENGTH_UNSET 语义）透传：读到资源尾的请求换线后仍读到尾
        val unsetLength = Long.MIN_VALUE + 1
        val (position, length) = computeRotatedRange(
            position = 500L, length = unsetLength, bytesSinceOpen = 3_000L
        )
        assertEquals(3_500L, position)
        assertEquals(unsetLength, length)
    }

    @Test
    fun `rotated range at chunk boundary keeps zero remaining`() {
        val (position, length) = computeRotatedRange(
            position = 100L, length = 1_000L, bytesSinceOpen = 1_000L
        )
        assertEquals(1_100L, position)
        assertEquals(0L, length)
    }

    // ===== rotate 请求标志语义 =====

    @Test
    fun `rotate request consumed exactly once`() {
        val state = VideoPlayerCdnFailoverState(candidates = emptyList())
        assertFalse(state.isRotatePending())
        assertFalse(state.consumeRotateRequest())

        state.requestRotate()
        assertTrue(state.isRotatePending())
        assertTrue("首次消费返回 true", state.consumeRotateRequest())
        assertFalse("标志已清，重复消费返回 false", state.consumeRotateRequest())
        assertFalse(state.isRotatePending())
    }
}
