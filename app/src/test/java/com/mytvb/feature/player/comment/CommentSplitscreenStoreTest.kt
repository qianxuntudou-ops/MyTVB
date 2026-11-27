package com.mytvb.feature.player.comment

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 评论面板分屏开关解析（issue#150）：存储文本 → 是否分屏。
 * 与设置页 toggle 开关存储形态（"开"/"关"）一致，非「开」一律按关。
 */
class CommentSplitscreenStoreTest {

    @Test
    fun storedOnResolvesToEnabled() {
        assertTrue(CommentSplitscreenStore.isStoredEnabled("开"))
    }

    @Test
    fun storedOffOrNullResolvesToDisabled() {
        assertFalse(CommentSplitscreenStore.isStoredEnabled("关"))
        assertFalse(CommentSplitscreenStore.isStoredEnabled(null))
        assertFalse(CommentSplitscreenStore.isStoredEnabled(""))
    }

    @Test
    fun unknownOrWhitespaceResolvesToDisabled() {
        assertFalse(CommentSplitscreenStore.isStoredEnabled("开 "))
        assertFalse(CommentSplitscreenStore.isStoredEnabled("enabled"))
    }
}
