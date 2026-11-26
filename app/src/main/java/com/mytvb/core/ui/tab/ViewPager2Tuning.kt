package com.mytvb.core.ui.tab

import android.view.ViewTreeObserver
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2

/**
 * @param retainAllPages true 时保留全部页面（offscreenPageLimit = pageCount - 1）：切 tab
 *   不再销毁 fragment，ViewModel、列表滚动位置、焦点锚点切回来原样保留（默认
 *   OFFSCREEN_PAGE_LIMIT_DEFAULT 只保留当前页，切走的 tab 连同 ViewModel 一起销毁重建，
 *   滚动进度丢失）。未浏览过的 tab 依赖各页面自身的"非当前页不加载"逻辑保持空壳，
 *   额外成本只有轻量视图。仅适合 tab 数量少且固定的宿主页（如首页 4 个 tab）。
 */
fun ViewPager2.disableAdjacentPagePrefetch(retainAllPages: Boolean = false) {
    offscreenPageLimit = if (retainAllPages) {
        retainedPageLimit()
    } else {
        ViewPager2.OFFSCREEN_PAGE_LIMIT_DEFAULT
    }
    val recyclerView = getChildAt(0) as? RecyclerView ?: return
    recyclerView.setItemViewCacheSize(0)
    (recyclerView.layoutManager as? LinearLayoutManager)?.isItemPrefetchEnabled = false
}

/**
 * 首帧绘制前保持按需创建（不拖慢冷启动首屏），首帧 preDraw 时再放开为保留全部
 * 页面：此后创建过的 tab 不再销毁，切 tab 滚动进度/焦点原样保留。页面创建的
 * 成本落在首帧之后，用户此时尚未开始切 tab。
 */
fun ViewPager2.retainAllPagesAfterFirstLayout() {
    viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
        override fun onPreDraw(): Boolean {
            if (viewTreeObserver.isAlive) {
                viewTreeObserver.removeOnPreDrawListener(this)
            }
            offscreenPageLimit = retainedPageLimit()
            // 动态放大 offscreenPageLimit 会让 ViewPager2 重排页面容器：冷启动直接落在
            // 非首项页时（如「默认首页」= 热门/历史），当前页内容可能停在未布局状态、
            // 整页空白，切走再切回才恢复。补一次无动画重选，强制当前页重新 bind/布局。
            if (currentItem != 0) {
                post { setCurrentItem(currentItem, false) }
            }
            return true
        }
    })
}

private fun ViewPager2.retainedPageLimit(): Int {
    val pageCount = adapter?.itemCount ?: 0
    return if (pageCount > 1) pageCount - 1 else ViewPager2.OFFSCREEN_PAGE_LIMIT_DEFAULT
}
