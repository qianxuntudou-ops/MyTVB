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
}
