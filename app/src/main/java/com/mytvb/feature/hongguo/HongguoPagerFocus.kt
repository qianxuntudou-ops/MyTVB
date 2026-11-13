package com.mytvb.feature.hongguo

import android.view.ViewGroup
import androidx.fragment.app.FragmentManager
import androidx.viewpager2.widget.ViewPager2

/**
 * ViewPager2 子页焦点域管理（一级六页与分类二级子档共用）：
 * - 离屏页 BLOCK + 根不可聚焦：页根若是 ScrollView/RecyclerView 系（构造器自带
 *   focusable=true），ViewPager 把离屏页摆在屏外坐标上，屏外根仍是方向键的
 *   合法几何候选（FocusFinder.isCandidate 对 RIGHT 宽松：src.right <= dest.left），
 *   胶囊/筛选 chip 边缘按键焦点凭空消失的根因；仅 descendantFocusability=BLOCK
 *   挡不住"根自己"（BLOCK 只跳过子项，容器自身仍会进候选）。
 * - 当前页恢复原值：state 记录各页根的原 focusable（rank 页根 FrameLayout
 *   本就不可聚焦，不能一律恢复 true）。
 * - 调用时机：onPageSelected + 初始 post（子页 view 需已创建）。
 */
fun syncViewPagerPageFocus(
    fragmentManager: FragmentManager,
    pager: ViewPager2,
    pageCount: Int,
    state: MutableMap<Int, Boolean>
) {
    val current = pager.currentItem
    for (i in 0 until pageCount) {
        val page = fragmentManager.findFragmentByTag("f$i") ?: continue
        val pageView = page.view as? ViewGroup ?: continue
        val isCurrent = i == current
        pageView.descendantFocusability =
            if (isCurrent) ViewGroup.FOCUS_AFTER_DESCENDANTS else ViewGroup.FOCUS_BLOCK_DESCENDANTS
        if (isCurrent) {
            val wasFocusable = state[i]
            if (wasFocusable != null) pageView.isFocusable = wasFocusable
        } else {
            if (i !in state) state[i] = pageView.isFocusable
            pageView.isFocusable = false
        }
    }
}
