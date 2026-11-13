package com.mytvb.feature.hongguo.net

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * big_thumbs 官方格位公式单测。
 * 基准 = 2026-11-13 Frida 官方抓包样例（player/v1 响应）：
 * img_num=79, interval=1s, 10×10 网格 240×136，desktop 裁格实测 0s/30s/78s 画面正确。
 */
class HongguoBigThumbsTest {

    private val sample = HongguoPlay.BigThumbs(
        imageUrl = "https://p5-ex-gddgtc-sign.douyinpic.com/x~tplv-noop.heif",
        cellWidth = 240, cellHeight = 136, xLen = 10,
        intervalSec = 1, frameCount = 79
    )

    @Test
    fun cellIndexFollowsOfficialFormula() {
        assertEquals(0, sample.cellIndexFor(0L))          // 第 0 秒 → 第 0 格（row0 col0）
        assertEquals(30, sample.cellIndexFor(30_000L))    // 30s → idx30（row3 col0，实测吻合）
        assertEquals(78, sample.cellIndexFor(78_000L))    // 78s → idx78（row7 col8，实测吻合）
    }

    @Test
    fun cellIndexQuantizesDownWithinInterval() {
        // interval=1s：槽位内向下取整（995ms 属第 0 格）
        assertEquals(0, sample.cellIndexFor(995L))
        assertEquals(5, sample.cellIndexFor(5_499L))
    }

    @Test
    fun cellIndexClampsBeyondLastFrame() {
        // 100s 超出 img_num=79 → 钳到最后一格 78
        assertEquals(78, sample.cellIndexFor(100_000L))
        assertEquals(78, sample.cellIndexFor(Long.MAX_VALUE / 2))
    }

    @Test
    fun cellIndexRejectsNegativePosition() {
        assertEquals(0, sample.cellIndexFor(-5_000L))
    }

    @Test
    fun cellIndexHonorsTwoSecondInterval() {
        val sparse = sample.copy(intervalSec = 2, frameCount = 50)
        assertEquals(0, sparse.cellIndexFor(1_999L))
        assertEquals(1, sparse.cellIndexFor(2_000L))
        assertEquals(25, sparse.cellIndexFor(51_000L))
    }

    @Test
    fun cellIndexHandlesMissingFrameCount() {
        // img_num 缺失（=0）时钳 0：只看第 0 格，宁缺勿越界
        val noCount = sample.copy(frameCount = 0)
        assertEquals(0, noCount.cellIndexFor(30_000L))
    }
}
