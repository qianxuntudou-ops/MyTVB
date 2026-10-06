package com.mytvb.feature.me

import com.mytvb.core.ui.focus.TabContentFocusTarget

interface MeTabPage : TabContentFocusTarget {
    fun scrollToTop()
    fun refresh()
    fun onTabSelected() {}
    fun onTabReselected() {}

    /**
     * 列表回顶并聚焦首个内容项（重选当前 tab 的快捷回顶，与 HomeTabPage 一致）。
     * 返回 false 表示页面无法接手，宿主回退到普通 scrollToTop。
     */
    fun scrollToTopAndFocus(): Boolean = false

    /**
     * tab 栏按 DOWN 的落点：就近聚焦当前可见的第一个内容项，列表位置保持不动；
     * 可见首项被裁半截时轻滚补齐。返回 false 时宿主回退到普通 focusPrimaryContent。
     */
    fun focusNearestVisibleContent(): Boolean = false

    enum class HostEvent {
        SELECT_TAB4,
        CLICK_TAB4,
        BACK_PRESSED,
        KEY_MENU_PRESS
    }

    fun onHostEvent(event: HostEvent): Boolean = false
}
