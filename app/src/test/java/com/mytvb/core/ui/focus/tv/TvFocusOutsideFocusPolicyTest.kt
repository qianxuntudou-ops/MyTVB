package com.mytvb.core.ui.focus.tv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 番剧页排序按钮抢焦回归：焦点在正/反序按钮（列表外可见可聚焦控件）上点 OK，
 * submitList 反转列表全量重绑必然 detach 可见 child，恢复流程不得把焦点拽回卡片。
 */
class TvFocusOutsideFocusPolicyTest {

    // ---- isLegitOutsideFocusHolder ----

    @Test
    fun visibleFocusableOutsideViewIsLegitHolder() {
        assertTrue(
            TvFocusOutsideFocusPolicy.isLegitOutsideFocusHolder(
                attached = true, shown = true, focusable = true
            )
        )
    }

    @Test
    fun hiddenOutsideViewIsNotLegitHolder() {
        assertFalse(
            TvFocusOutsideFocusPolicy.isLegitOutsideFocusHolder(
                attached = true, shown = false, focusable = true
            )
        )
    }

    @Test
    fun detachedOutsideViewIsNotLegitHolder() {
        assertFalse(
            TvFocusOutsideFocusPolicy.isLegitOutsideFocusHolder(
                attached = false, shown = true, focusable = true
            )
        )
    }

    @Test
    fun nonFocusableOutsideViewIsNotLegitHolder() {
        assertFalse(
            TvFocusOutsideFocusPolicy.isLegitOutsideFocusHolder(
                attached = true, shown = true, focusable = false
            )
        )
    }

    // ---- shouldRestoreOnChildDetached ----

    @Test
    fun focusInsideDetachedChildIsAlwaysRestored() {
        // 滚动回收聚焦 item 导致的焦点丢失：本职场景，必须恢复
        assertTrue(
            TvFocusOutsideFocusPolicy.shouldRestoreOnChildDetached(
                focusInsideDetached = true,
                focusOutsideList = false,
                outsideFocusVisibleFocusable = false
            )
        )
        // 焦点在被回收的 view 内，即使同时存在列表外持焦者也以回收丢焦为先
        assertTrue(
            TvFocusOutsideFocusPolicy.shouldRestoreOnChildDetached(
                focusInsideDetached = true,
                focusOutsideList = true,
                outsideFocusVisibleFocusable = true
            )
        )
    }

    @Test
    fun focusOnLegitOutsideHolderSurvivesChildDetach() {
        // 排序按钮点 OK 反转列表：焦点在按钮上（合法落点），child detach 不得抢焦
        assertFalse(
            TvFocusOutsideFocusPolicy.shouldRestoreOnChildDetached(
                focusInsideDetached = false,
                focusOutsideList = true,
                outsideFocusVisibleFocusable = true
            )
        )
    }

    @Test
    fun focusOnAbnormalOutsideHolderIsRestoredOnChildDetach() {
        // 列表外隐藏/不可聚焦持焦者属异常态，child detach 时恢复
        assertTrue(
            TvFocusOutsideFocusPolicy.shouldRestoreOnChildDetached(
                focusInsideDetached = false,
                focusOutsideList = true,
                outsideFocusVisibleFocusable = false
            )
        )
    }

    @Test
    fun noFocusToWorryAboutMeansNoRestore() {
        assertFalse(
            TvFocusOutsideFocusPolicy.shouldRestoreOnChildDetached(
                focusInsideDetached = false,
                focusOutsideList = false,
                outsideFocusVisibleFocusable = false
            )
        )
    }

    // ---- shouldRestoreOnGlobalFocusChange（调用方已保证 newFocus 在列表外）----

    @Test
    fun globalFocusChangeDoesNotStealFromLegitOutsideHolder() {
        // 快速按方向键后 500ms 窗口内 UP 到排序按钮：不得弹回卡片
        assertFalse(
            TvFocusOutsideFocusPolicy.shouldRestoreOnGlobalFocusChange(
                newFocusVisibleFocusable = true
            )
        )
    }

    @Test
    fun globalFocusChangeStillRestoresAbnormalOutsideHolder() {
        assertTrue(
            TvFocusOutsideFocusPolicy.shouldRestoreOnGlobalFocusChange(
                newFocusVisibleFocusable = false
            )
        )
    }
}
