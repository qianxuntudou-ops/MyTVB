package com.mytvb.feature.home

import com.mytvb.core.ui.focus.TabContentFocusTarget

interface HomeTabPage : TabContentFocusTarget {
    fun scrollToTop()
    fun refresh()
    fun onTabSelected() {}

    /**
     * tab 栏按 DOWN 的落点：就近聚焦当前可见的第一个内容项，列表位置保持不动；
     * 可见首项被裁半截时轻滚补齐。返回 false 时宿主回退到普通 focusPrimaryContent。
     */
    fun focusNearestVisibleContent(): Boolean = false
}
