package com.mytvb.feature.player.controlbar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 控制条唤醒焦点落点解析（issue#150）：存储文本 → [ControlBarButton]。
 * nameOf 用固定映射替代 Android 资源解析，覆盖正常/空白/未识别/首尾空白。
 */
class PlayerWakeFocusStoreTest {

    private val names = mapOf(
        "play" to "播放/暂停",
        "more" to "更多",
        "comment" to "评论",
        "danmaku" to "弹幕开关",
    )

    private fun nameOf(button: ControlBarButton): String =
        // resolve 会遍历全部枚举项；未列出的按钮给空名（不可能匹配任何存储文本）
        names[button.key].orEmpty()

    @Test
    fun storedLabelResolvesToButton() {
        assertEquals(ControlBarButton.MORE, PlayerWakeFocusStore.resolve("更多", ::nameOf))
        assertEquals(ControlBarButton.PLAY, PlayerWakeFocusStore.resolve("播放/暂停", ::nameOf))
        assertEquals(ControlBarButton.DANMAKU, PlayerWakeFocusStore.resolve("弹幕开关", ::nameOf))
    }

    @Test
    fun blankOrNullFallsBackToNull() {
        assertNull(PlayerWakeFocusStore.resolve(null, ::nameOf))
        assertNull(PlayerWakeFocusStore.resolve("", ::nameOf))
        assertNull(PlayerWakeFocusStore.resolve("   ", ::nameOf))
    }

    @Test
    fun unknownLabelFallsBackToNull() {
        assertNull(PlayerWakeFocusStore.resolve("不存在的按钮", ::nameOf))
    }

    @Test
    fun surroundingWhitespaceIsTolerated() {
        assertEquals(ControlBarButton.MORE, PlayerWakeFocusStore.resolve(" 更多 ", ::nameOf))
    }
}
