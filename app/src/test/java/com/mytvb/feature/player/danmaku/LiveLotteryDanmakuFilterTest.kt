package com.mytvb.feature.player.danmaku

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveLotteryDanmakuFilterTest {

    private val blocked = setOf("关注主播抽奖")

    @Test
    fun dropsOnlyWhenSwitchIsOn() {
        assertTrue(LiveLotteryDanmakuFilter.shouldDrop("关注主播抽奖", enabled = true, blocked = blocked))
        assertFalse(LiveLotteryDanmakuFilter.shouldDrop("关注主播抽奖", enabled = false, blocked = blocked))
    }

    @Test
    fun dropsNothingWhenNoLotteryYet() {
        assertFalse(LiveLotteryDanmakuFilter.shouldDrop("关注主播抽奖", enabled = true, blocked = emptySet()))
    }

    @Test
    fun dropsExactMatchIgnoringSurroundingWhitespace() {
        assertTrue(LiveLotteryDanmakuFilter.shouldDrop(" 关注主播抽奖 ", enabled = true, blocked = blocked))
    }

    @Test
    fun keepsAnythingOtherThanTheKeywordItself() {
        assertFalse(LiveLotteryDanmakuFilter.shouldDrop("关注主播抽奖啦", enabled = true, blocked = blocked))
        assertFalse(LiveLotteryDanmakuFilter.shouldDrop("关注主播", enabled = true, blocked = blocked))
    }

    @Test
    fun matchIsCaseSensitive() {
        assertFalse(LiveLotteryDanmakuFilter.shouldDrop("OK", enabled = true, blocked = setOf("ok")))
    }

    @Test
    fun keywordIsTrimmedAndBlankIsRejected() {
        assertEquals("口令", LiveLotteryDanmakuFilter.keywordOf("  口令 "))
        assertNull(LiveLotteryDanmakuFilter.keywordOf(null))
        assertNull(LiveLotteryDanmakuFilter.keywordOf("   "))
    }

    @Test
    fun keywordsOfReadsAnchorLotteryObject() {
        val lottery = JsonParser.parseString("""{"danmu":"口令A","award_name":"奖品"}""")

        assertEquals(setOf("口令A"), LiveLotteryDanmakuFilter.keywordsOf(lottery))
    }

    @Test
    fun keywordsOfReadsRedPocketArray() {
        val lottery = JsonParser.parseString("""[{"danmu":"口令B"},{"danmu":" 口令C "},{"danmu":""}]""")

        assertEquals(setOf("口令B", "口令C"), LiveLotteryDanmakuFilter.keywordsOf(lottery))
    }

    @Test
    fun keywordsOfToleratesNullAndUnexpectedShapes() {
        assertTrue(LiveLotteryDanmakuFilter.keywordsOf(null).isEmpty())
        assertTrue(LiveLotteryDanmakuFilter.keywordsOf(JsonParser.parseString("null")).isEmpty())
        assertTrue(LiveLotteryDanmakuFilter.keywordsOf(JsonParser.parseString("""{"danmu":null}""")).isEmpty())
        assertTrue(LiveLotteryDanmakuFilter.keywordsOf(JsonParser.parseString("""{"danmu":[]}""")).isEmpty())
        assertTrue(LiveLotteryDanmakuFilter.keywordsOf(JsonParser.parseString("[]")).isEmpty())
    }
}
