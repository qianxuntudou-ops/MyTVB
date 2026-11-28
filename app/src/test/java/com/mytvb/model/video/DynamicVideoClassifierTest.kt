package com.mytvb.model.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 动态视频识别测试（issue#151）。
 *
 * 判据：动态视频不进 UP 投稿栏，所以「在动态流但不在投稿列表」即动态视频。
 * 2026-11-27 真样本（UP 亿点点不一样 BV171e76NE2E/BV1aza76HERk/BV1ffpY6nEZR）
 * 证实条目自带 badge/pub_text/结构都无法区分，故只能靠投稿列表比对。
 */
class DynamicVideoClassifierTest {

    private fun video(bvid: String, badge: String = "") = VideoModel(
        aid = 1L,
        bvid = bvid,
        title = bvid,
        dynamicBadgeText = badge
    )

    @Test
    fun `不在投稿列表的标为动态视频`() {
        val videos = listOf(video("BV1normal"), video("BV1dyn"))
        val result = DynamicVideoClassifier.markDynamicVideos(videos, setOf("BV1normal"))

        assertEquals("", result[0].dynamicBadgeText)
        assertEquals(DynamicVideoClassifier.BADGE_TEXT_DYNAMIC_VIDEO, result[1].dynamicBadgeText)
    }

    @Test
    fun `全部在投稿列表则无一被标`() {
        val videos = listOf(video("BV1a"), video("BV1b"))
        val result = DynamicVideoClassifier.markDynamicVideos(videos, setOf("BV1a", "BV1b"))

        assertTrue(result.all { it.dynamicBadgeText.isBlank() })
    }

    @Test
    fun `投稿集合为空时一律不标防误判`() {
        // 投稿列表没取到（接口失败）时不能把整页都当成动态视频
        val videos = listOf(video("BV1a", badge = "旧标"), video("BV1b"))
        val result = DynamicVideoClassifier.markDynamicVideos(videos, emptySet())

        assertTrue(result.all { it.dynamicBadgeText.isBlank() })
    }

    @Test
    fun `页签过滤只留不在投稿列表的`() {
        val videos = listOf(video("BV1normal"), video("BV1dyn1"), video("BV1dyn2"))
        val hits = DynamicVideoClassifier.filterDynamicVideos(videos, setOf("BV1normal"))

        assertEquals(listOf("BV1dyn1", "BV1dyn2"), hits.map { it.bvid })
        assertTrue(hits.all { it.dynamicBadgeText == DynamicVideoClassifier.BADGE_TEXT_DYNAMIC_VIDEO })
    }

    @Test
    fun `页签在投稿集合为空时返回空列表`() {
        val videos = listOf(video("BV1a"), video("BV1b"))
        assertTrue(DynamicVideoClassifier.filterDynamicVideos(videos, emptySet()).isEmpty())
    }

    @Test
    fun `bvid 为空的条目不参与判定`() {
        val videos = listOf(video(""), video("BV1dyn"))
        val result = DynamicVideoClassifier.markDynamicVideos(videos, setOf("BV1other"))

        assertEquals("", result[0].dynamicBadgeText)
        assertEquals(DynamicVideoClassifier.BADGE_TEXT_DYNAMIC_VIDEO, result[1].dynamicBadgeText)
    }
}
