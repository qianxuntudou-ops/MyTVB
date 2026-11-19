package com.mytvb.feature.player.view

import com.mytvb.feature.player.danmaku.common.BiliDanmakuFilterPolicy
import com.mytvb.feature.player.danmaku.common.DanmakuSettingsSnapshot
import com.mytvb.feature.player.DanmakuFilterContext
import com.mytvb.model.dm.DmModel
import com.mytvb.model.proto.DanmuWebPlayerConfigProto
import com.mytvb.model.proto.DmRestrictPeriodProto
import com.mytvb.model.proto.DmSmartFilterConfigProto
import org.junit.Assert.assertEquals
import org.junit.Test

class BiliDanmakuFilterPolicyTest {

    @Test
    fun apply_dropsReportFilterRestrictAndServiceAiItems() {
        val result = BiliDanmakuFilterPolicy.apply(
            items = listOf(
                dm(id = 1, progress = 100, content = "正常", weight = 10),
                dm(id = 2, progress = 200, content = "剧透内容", weight = 10),
                dm(id = 3, progress = 1_500, content = "限制时段", weight = 10),
                dm(id = 4, progress = 2_500, content = "AI低分", weight = 2)
            ),
            context = DanmakuFilterContext(
                smartFilterConfig = DmSmartFilterConfigProto(
                    playerLevel = 5,
                    playerEnabled = true
                ),
                reportFilters = listOf("剧透"),
                restrictPeriods = listOf(DmRestrictPeriodProto(startMs = 1_000, endMs = 2_000))
            ),
            settings = settings(),
            stage = "test"
        )

        assertEquals(listOf(1L), result.map { it.id })
    }

    @Test
    fun apply_respectsLocalTopBottomAndAdvancedSwitches() {
        val result = BiliDanmakuFilterPolicy.apply(
            items = listOf(
                dm(id = 1, mode = 1, content = "滚动"),
                dm(id = 2, mode = 5, content = "顶部"),
                dm(id = 3, mode = 4, content = "底部"),
                dm(id = 4, mode = 7, content = "高级")
            ),
            context = DanmakuFilterContext.EMPTY,
            settings = settings(
                allowTop = false,
                allowBottom = false
            ),
            stage = "test"
        )

        assertEquals(listOf(1L), result.map { it.id })
    }

    @Test
    fun apply_usesDmSettingTypeColorAsBiliCompatibleFilter() {
        val result = BiliDanmakuFilterPolicy.apply(
            items = listOf(dm(id = 1, color = 0xFF0000, content = "彩色")),
            context = DanmakuFilterContext(
                playerConfig = DanmuWebPlayerConfigProto(typeColor = false)
            ),
            settings = settings(),
            stage = "test"
        )

        assertEquals(0, result.size)
    }

    @Test
    fun apply_derivesAiLevelFromDmAreaLikeBili() {
        val result = BiliDanmakuFilterPolicy.apply(
            items = listOf(
                dm(id = 1, content = "低权重", weight = 2),
                dm(id = 2, content = "正常", weight = 4)
            ),
            context = DanmakuFilterContext(
                playerConfig = DanmuWebPlayerConfigProto(
                    aiSwitch = true,
                    aiLevel = 0,
                    dmArea = 50
                )
            ),
            settings = settings(),
            stage = "test"
        )

        assertEquals(listOf(2L), result.map { it.id })
    }

    @Test
    fun apply_respectsAppSmartFilterLevelWithoutServiceAiSwitch() {
        val result = BiliDanmakuFilterPolicy.apply(
            items = listOf(
                dm(id = 1, content = "未评分", weight = 0),
                dm(id = 2, content = "低权重", weight = 2),
                dm(id = 3, content = "正常", weight = 5)
            ),
            context = DanmakuFilterContext.EMPTY,
            settings = settings(smartFilterLevel = 3),
            stage = "test"
        )

        assertEquals(listOf(1L, 3L), result.map { it.id })
    }

    private fun dm(
        id: Long,
        progress: Int = 0,
        mode: Int = 1,
        color: Int = 0xFFFFFF,
        content: String,
        weight: Int = 0
    ): DmModel =
        DmModel(
            id = id,
            progress = progress,
            mode = mode,
            color = color,
            content = content,
            weight = weight
        )

    private fun settings(
        allowTop: Boolean = true,
        allowBottom: Boolean = true,
        smartFilterLevel: Int = 0
    ): DanmakuSettingsSnapshot =
        DanmakuSettingsSnapshot(
            enabled = true,
            alpha = 1f,
            textSize = 40,
            speed = 4f,
            screenArea = 3,
            allowTop = allowTop,
            allowBottom = allowBottom,
            smartFilterLevel = smartFilterLevel,
            mergeDuplicate = true
        )
}
