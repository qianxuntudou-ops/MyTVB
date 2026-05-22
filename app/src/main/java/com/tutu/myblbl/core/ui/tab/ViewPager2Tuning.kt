package com.tutu.myblbl.core.ui.tab

import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2

fun ViewPager2.disableAdjacentPagePrefetch() {
    offscreenPageLimit = ViewPager2.OFFSCREEN_PAGE_LIMIT_DEFAULT
    val recyclerView = getChildAt(0) as? RecyclerView ?: return
    recyclerView.setItemViewCacheSize(0)
    (recyclerView.layoutManager as? LinearLayoutManager)?.isItemPrefetchEnabled = false
}
