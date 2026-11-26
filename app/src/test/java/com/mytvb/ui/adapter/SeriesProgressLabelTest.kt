package com.mytvb.ui.adapter

import com.mytvb.model.episode.EpisodeModel
import com.mytvb.model.series.AnimEpisodeInfoModel
import com.mytvb.model.series.SeriesModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** issue#145：追番卡进度百分比 + 详情页「上次观看到」标签拼接的纯函数部分 */
class SeriesProgressLabelTest {

    private fun series(progress: String, newEpDurationMs: Long = 0L) = SeriesModel(
        seasonId = 1L,
        progress = progress,
        newEp = AnimEpisodeInfoModel(id = 100L, duration = newEpDurationMs)
    )

    // region 追番/追剧卡：当前时长换百分比

    @Test
    fun progressPercent_replacesTrailingClockWithPercent() {
        // 08:12=492s，总 20:24=1224s → 四舍五入 40%
        val result = series(progress = "看到第201集 08:12", newEpDurationMs = 1224000L)
            .progressWithPercent()
        assertEquals("看到第201集 40%", result)
    }

    @Test
    fun progressPercent_supportsHourClockAndHua() {
        // 1:00:00=3600s，总 5400s → 67%
        val result = series(progress = "看到第3话 1:00:00", newEpDurationMs = 5400000L)
            .progressWithPercent()
        assertEquals("看到第3话 67%", result)
    }

    @Test
    fun progressPercent_roundsToNearest() {
        // 00:30=30s / 总 121s → 3000+60=3060/121=25.29 → 25%
        val result = series(progress = "看到第1集 00:30", newEpDurationMs = 121000L)
            .progressWithPercent()
        assertEquals("看到第1集 25%", result)
    }

    @Test
    fun progressPercent_watchedKeepsOriginalText() {
        assertNull(
            series(progress = "已看完第201集", newEpDurationMs = 1224000L).progressWithPercent()
        )
    }

    @Test
    fun progressPercent_blankOrUnparsableFallsBack() {
        assertNull(series(progress = "", newEpDurationMs = 1224000L).progressWithPercent())
        // 无集数前缀的原文不硬解析
        assertNull(series(progress = "08:12", newEpDurationMs = 1224000L).progressWithPercent())
        // 无时长可用（new_ep.duration=0）保持原文
        assertNull(
            series(progress = "看到第201集 08:12", newEpDurationMs = 0L).progressWithPercent()
        )
    }

    // endregion

    // region 详情页：last_ep_index 兜底 / 预告集号提取 / 总时长换算

    @Test
    fun fallbackIndex_wrapsOnlyPureNumbers() {
        assertEquals("第201集", previewAwareFallback("201"))
        // last_ep_index 可能下发集标题（如「寻参」），包壳会拼出「第寻参集」
        assertEquals("寻参", previewAwareFallback("寻参"))
        assertEquals("", previewAwareFallback(""))
    }

    @Test
    fun extractEpisodeNumber_prefersBareNumberTitle() {
        // 实测预告条目形态：title=正片集号「202」
        assertEquals(202, extractEpisodeNumber("202", "第202话 预告"))
        assertEquals(202, extractEpisodeNumber("预告", "第202话 预告"))
        assertEquals(3, extractEpisodeNumber("3", "准备工作尝试"))
    }

    @Test
    fun extractEpisodeNumber_ignoresNumbersInsideTitles() {
        // 「2026年发布会PV」里的年份不是集号，标题非纯数字且无「第N集/话」→ null
        assertNull(extractEpisodeNumber("2026年发布会PV", ""))
    }

    @Test
    fun durationSec_convertsMillis() {
        assertEquals(1451L, EpisodeModel(duration = 1451000L).durationSec())
        assertEquals(0L, EpisodeModel(duration = 0L).durationSec())
    }

    // endregion
}
