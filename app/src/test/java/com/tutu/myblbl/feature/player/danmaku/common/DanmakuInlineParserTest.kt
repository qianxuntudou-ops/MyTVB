package com.tutu.myblbl.feature.player.danmaku.common

import com.tutu.myblbl.feature.player.danmaku.model.DanmakuInlineSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 弹幕内联段解析回归：
 * - 纯文本（无词典命中、无高赞）返回 null 走整行快路径
 * - 表情 token 命中拆 Text/Emote 段，未命中原样保留
 * - 词典未就绪（canParseEmote=false）只解析高赞图标且结果不缓存
 * - 高赞图标固定首段
 */
class DanmakuInlineParserTest {

    private fun parse(
        text: String,
        isHighLiked: Boolean = false,
        showHighLikeIcon: Boolean = true,
        canParseEmote: Boolean = true,
        dict: Map<String, String> = emptyMap(),
    ) = DanmakuInlineParser.parse(
        text = text,
        isHighLiked = isHighLiked,
        showHighLikeIcon = showHighLikeIcon,
        canParseEmote = canParseEmote,
        urlForToken = { dict[it] },
    )

    @Test
    fun `plain text without emote or icon returns null`() {
        assertNull(parse("普通弹幕"))
        assertNull(parse("没有表情[未知token]文本", dict = mapOf("[doge]" to "https://a/b.png")))
    }

    @Test
    fun `emote token splits into text and emote segments`() {
        val segments = parse("前[doge]后", dict = mapOf("[doge]" to "https://a/doge.png"))!!
        assertEquals(
            listOf(
                DanmakuInlineSegment.Text(0, 1),
                DanmakuInlineSegment.Emote("https://a/doge.png"),
                DanmakuInlineSegment.Text(7, 8),
            ),
            segments,
        )
    }

    @Test
    fun `unmatched token stays as plain text`() {
        // 字典有 [doge] 但文本里是 [doge2]，未命中 → 整条按纯文本处理。
        assertNull(parse("看这个[doge2]", dict = mapOf("[doge]" to "https://a/doge.png")))
    }

    @Test
    fun `non http url is not treated as emote`() {
        assertNull(parse("[x]", dict = mapOf("[x]" to "ftp://bad")))
    }

    @Test
    fun `unbalanced bracket stays plain`() {
        assertNull(parse("半截[doge", dict = mapOf("[doge]" to "https://a/doge.png")))
        assertNull(parse("反了]doge[", dict = mapOf("[doge]" to "https://a/doge.png")))
    }

    @Test
    fun `high like icon is first segment`() {
        val segments = parse("[doge]", isHighLiked = true, dict = mapOf("[doge]" to "https://a/doge.png"))!!
        assertEquals(DanmakuInlineSegment.HighLikeIcon, segments.first())
        assertEquals(DanmakuInlineSegment.Emote("https://a/doge.png"), segments.last())
        assertEquals(2, segments.size)
    }

    @Test
    fun `icon hidden when showHighLikeIcon false`() {
        assertNull(parse("文本", isHighLiked = true, showHighLikeIcon = false))
    }

    @Test
    fun `emote disabled keeps icon-only result`() {
        // 词典未就绪：含表情 token 的文本只出图标段（且调用方约定不缓存该结果）。
        val segments = parse("[doge]好看", isHighLiked = true, canParseEmote = false)!!
        assertEquals(listOf(DanmakuInlineSegment.HighLikeIcon, DanmakuInlineSegment.Text(0, 8)), segments)
        // 不含 '[' 的文本在高赞时也只出图标段。
        val iconOnly = parse("高赞弹幕", isHighLiked = true, canParseEmote = false)!!
        assertEquals(listOf(DanmakuInlineSegment.HighLikeIcon, DanmakuInlineSegment.Text(0, 4)), iconOnly)
    }

    @Test
    fun `blank text with high like returns icon only`() {
        val segments = parse("", isHighLiked = true)!!
        assertEquals(listOf(DanmakuInlineSegment.HighLikeIcon), segments)
    }

    @Test
    fun `continuous emotes split correctly`() {
        val segments = parse("[a][b]", dict = mapOf("[a]" to "https://a/a.png", "[b]" to "https://a/b.png"))!!
        assertEquals(
            listOf(
                DanmakuInlineSegment.Emote("https://a/a.png"),
                DanmakuInlineSegment.Emote("https://a/b.png"),
            ),
            segments,
        )
    }

    @Test
    fun `shouldCacheParsedSegments follows emote readiness`() {
        assertTrue(DanmakuInlineParser.shouldCacheParsedSegments("纯文本", canParseEmote = false))
        assertFalse(DanmakuInlineParser.shouldCacheParsedSegments("含[表情]", canParseEmote = false))
        assertTrue(DanmakuInlineParser.shouldCacheParsedSegments("含[表情]", canParseEmote = true))
    }
}
