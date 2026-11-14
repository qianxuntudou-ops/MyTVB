package com.mytvb.feature.hongguo

/**
 * 红果子页焦点入口：侧栏 RIGHT / 顶部 tab DOWN 落到当前页第一张卡片的统一通道
 * （HongguoFragment.focusCurrentPageContent 按 ViewPager2 当前页转发）。
 */
interface HongguoPageFocus {
    fun focusFirstCard(): Boolean

    /**
     * 卡片 UP 的中间层落点：筛选 chip 行/榜单分段/分类子档 tab 等二级 tab。
     * 无二级 tab 的页（历史/收藏/我的）用默认 false，卡片 UP 直接回顶部一级 tab。
     */
    fun focusSecondaryTab(): Boolean = false

    /**
     * tab **首次**选中回顶：离屏页全 retain + 进程重启会恢复旧滚动位置（表现为
     * 「打开停在半路」），首次选中强制回第一行；之后切换保持用户滚动位置
     * （用户口径：首次打开在顶部，来回切 tab 不丢位置）。
     */
    fun scrollToListTop() {}
}
