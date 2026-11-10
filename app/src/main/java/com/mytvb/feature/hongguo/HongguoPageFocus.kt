package com.mytvb.feature.hongguo

/**
 * 红果子页焦点入口：侧栏 RIGHT / 顶部 tab DOWN 落到当前页第一张卡片的统一通道
 * （HongguoFragment.focusCurrentPageContent 按 ViewPager2 当前页转发）。
 */
interface HongguoPageFocus {
    fun focusFirstCard(): Boolean
}
