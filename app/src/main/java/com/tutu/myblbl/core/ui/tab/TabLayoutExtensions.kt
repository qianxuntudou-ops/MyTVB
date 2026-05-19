package com.tutu.myblbl.core.ui.tab

import com.tutu.myblbl.core.ui.focus.SpatialFocusNavigator
import com.tutu.myblbl.core.common.log.AppLog

import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager.widget.ViewPager
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout

private const val TAB_SWITCH_BASE_DURATION_MS = 60
private const val TAB_SWITCH_STEP_DURATION_MS = 20
private const val TAB_SWITCH_MAX_DURATION_MS = 60
private const val TAB_DIRECT_SWITCH_OUT_DURATION_MS = 120L
private const val TAB_DIRECT_SWITCH_IN_DURATION_MS = 150L

fun TabLayout.enableTouchNavigation(
    viewPager: ViewPager2,
    tabFocusable: Boolean = true,
    smoothScrollOnSelection: Boolean = true,
    onNavigateDown: (() -> Boolean)? = null,
    onNavigateLeft: (() -> Boolean)? = null,
    onNavigateRight: (() -> Boolean)? = null
) {
    viewPager.isUserInputEnabled = true
    post {
        val tabStrip = getChildAt(0) as? ViewGroup ?: return@post
        for (index in 0 until tabStrip.childCount) {
            val tabView = tabStrip.getChildAt(index)
            tabView.isClickable = true
            tabView.isFocusable = tabFocusable
            if (tabFocusable) {
                tabView.setOnKeyListener { _, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        when (keyCode) {
                            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                                if (viewPager.currentItem != index) {
                                    viewPager.setCurrentItemFromTab(
                                        item = index,
                                        smoothScroll = smoothScrollOnSelection
                                    )
                                } else {
                                    getTabAt(index)?.select()
                                }
                                true
                            }
                            KeyEvent.KEYCODE_DPAD_DOWN -> {
                                val result = if (onNavigateDown != null) {
                                    onNavigateDown.invoke()
                                } else {
                                    focusViewPagerContent(viewPager)
                                }
                                AppLog.d("TabNav", "DPAD_DOWN tab[$index] result=$result")
                                result
                            }
                            KeyEvent.KEYCODE_DPAD_LEFT -> {
                                if (index == 0) {
                                    onNavigateLeft?.invoke() == true
                                } else {
                                    false
                                }
                            }
                            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                if (index == tabStrip.childCount - 1) {
                                    onNavigateRight?.invoke() == true
                                } else {
                                    false
                                }
                            }
                            else -> false
                        }
                    } else {
                        false
                    }
                }
            }
            tabView.setOnClickListener {
                if (viewPager.currentItem != index) {
                    viewPager.setCurrentItemFromTab(
                        item = index,
                        smoothScroll = smoothScrollOnSelection
                    )
                } else {
                    getTabAt(index)?.select()
                }
            }
        }
    }
}

fun TabLayout.enableTouchNavigation(
    viewPager: ViewPager,
    tabFocusable: Boolean = true,
    smoothScrollOnSelection: Boolean = true,
    onNavigateDown: (() -> Boolean)? = null,
    onNavigateLeft: (() -> Boolean)? = null,
    onNavigateRight: (() -> Boolean)? = null
) {
    post {
        val tabStrip = getChildAt(0) as? ViewGroup ?: return@post
        for (index in 0 until tabStrip.childCount) {
            val tabView = tabStrip.getChildAt(index)
            tabView.isClickable = true
            tabView.isFocusable = tabFocusable
            if (tabFocusable) {
                tabView.setOnKeyListener { _, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        when (keyCode) {
                            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                                if (viewPager.currentItem != index) {
                                    viewPager.setCurrentItem(index, smoothScrollOnSelection)
                                } else {
                                    getTabAt(index)?.select()
                                }
                                true
                            }
                            KeyEvent.KEYCODE_DPAD_DOWN -> {
                                val result = if (onNavigateDown != null) {
                                    onNavigateDown.invoke()
                                } else {
                                    focusViewPagerContent(viewPager)
                                }
                                AppLog.d("TabNav", "DPAD_DOWN tab[$index] result=$result")
                                result
                            }
                            KeyEvent.KEYCODE_DPAD_LEFT -> {
                                if (index == 0) {
                                    onNavigateLeft?.invoke() == true
                                } else {
                                    false
                                }
                            }
                            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                if (index == tabStrip.childCount - 1) {
                                    onNavigateRight?.invoke() == true
                                } else {
                                    false
                                }
                            }
                            else -> false
                        }
                    } else {
                        false
                    }
                }
            }
            tabView.setOnClickListener {
                if (viewPager.currentItem != index) {
                    viewPager.setCurrentItem(index, smoothScrollOnSelection)
                } else {
                    getTabAt(index)?.select()
                }
            }
        }
    }
}

fun TabLayout.focusSelectedTab(): Boolean {
    val selectedIndex = selectedTabPosition
    if (selectedIndex < 0) {
        return false
    }
    val tabStrip = getChildAt(0) as? ViewGroup ?: return false
    return tabStrip.getChildAt(selectedIndex)?.requestFocus() == true
}

fun TabLayout.focusNearestTabTo(anchorView: View?): Boolean {
    val tabStrip = getChildAt(0) as? ViewGroup ?: return false
    val candidates = (0 until tabStrip.childCount)
        .mapNotNull(tabStrip::getChildAt)
        .filter { it.visibility == View.VISIBLE && it.isFocusable }
    if (candidates.isEmpty()) {
        return false
    }
    return SpatialFocusNavigator.requestBestCandidate(
        anchorView = anchorView,
        candidates = candidates,
        direction = View.FOCUS_UP,
        fallback = {
            focusSelectedTab() || candidates.firstOrNull()?.requestFocus() == true
        }
    )
}

private fun focusViewPagerContent(viewPager: ViewPager2): Boolean {
    val pagerRecycler = viewPager.getChildAt(0) as? RecyclerView ?: return false
    val currentPage = pagerRecycler.layoutManager?.findViewByPosition(viewPager.currentItem)
        ?: return tryFocusFirstFocusableDescendant(pagerRecycler) || pagerRecycler.requestFocus()
    return tryFocusFirstFocusableDescendant(currentPage) || currentPage.requestFocus()
}

private fun focusViewPagerContent(viewPager: ViewPager): Boolean {
    val scrollAnchor = viewPager.scrollX + viewPager.paddingLeft
    val currentView = (0 until viewPager.childCount)
        .map(viewPager::getChildAt)
        .firstOrNull { child ->
            child.visibility == View.VISIBLE &&
                child.width > 0 &&
                child.left <= scrollAnchor &&
                child.right >= scrollAnchor
        }
        ?: (0 until viewPager.childCount)
            .map(viewPager::getChildAt)
            .filter { it.visibility == View.VISIBLE && it.width > 0 }
            .minByOrNull { kotlin.math.abs(it.left - scrollAnchor) }
        ?: return tryFocusFirstFocusableDescendant(viewPager) || viewPager.requestFocus()
    return tryFocusFirstFocusableDescendant(currentView) || currentView.requestFocus()
}

private fun tryFocusFirstFocusableDescendant(view: View): Boolean {
    if (view is ViewGroup) {
        for (i in 0 until view.childCount) {
            val child = view.getChildAt(i)
            if (child.isFocusable && child.visibility == View.VISIBLE) {
                if (child.requestFocus()) {
                    return true
                }
            }
            if (child is ViewGroup && child.visibility == View.VISIBLE) {
                if (tryFocusFirstFocusableDescendant(child)) {
                    return true
                }
            }
        }
    }
    return false
}

private fun ViewPager2.setCurrentItemFromTab(
    item: Int,
    smoothScroll: Boolean
) {
    val recyclerView = getChildAt(0) as? RecyclerView
    val adapter = adapter
    if (!smoothScroll || recyclerView == null || adapter == null) {
        setCurrentItem(item, smoothScroll)
        return
    }
    if (adapter.itemCount <= 0) {
        return
    }

    val targetItem = item.coerceIn(0, adapter.itemCount - 1)
    val pageDelta = targetItem - currentItem
    if (pageDelta == 0) {
        return
    }
    if (kotlin.math.abs(pageDelta) > 1) {
        animateDirectTabSwitch(targetItem, pageDelta)
        return
    }

    val pageSize = when (orientation) {
        ViewPager2.ORIENTATION_VERTICAL -> height - paddingTop - paddingBottom
        else -> width - paddingLeft - paddingRight
    }
    if (pageSize <= 0 || scrollState != ViewPager2.SCROLL_STATE_IDLE) {
        setCurrentItem(targetItem, true)
        return
    }

    val distance = pageSize * pageDelta * horizontalDirectionMultiplier()
    recyclerView.stopScroll()
    recyclerView.smoothScrollBy(
        if (orientation == ViewPager2.ORIENTATION_HORIZONTAL) distance else 0,
        if (orientation == ViewPager2.ORIENTATION_VERTICAL) distance else 0,
        DecelerateInterpolator(1.8f),
        calculateTabSwitchDuration(kotlin.math.abs(pageDelta))
    )
}

private fun ViewPager2.horizontalDirectionMultiplier(): Int {
    if (orientation != ViewPager2.ORIENTATION_HORIZONTAL) {
        return 1
    }
    return if (layoutDirection == View.LAYOUT_DIRECTION_RTL) -1 else 1
}

private fun calculateTabSwitchDuration(pageDistance: Int): Int {
    val duration = TAB_SWITCH_BASE_DURATION_MS +
        (pageDistance - 1).coerceAtLeast(0) * TAB_SWITCH_STEP_DURATION_MS
    return duration.coerceAtMost(TAB_SWITCH_MAX_DURATION_MS)
}

private fun ViewPager2.animateDirectTabSwitch(targetItem: Int, pageDelta: Int) {
    val pageSize = when (orientation) {
        ViewPager2.ORIENTATION_VERTICAL -> height - paddingTop - paddingBottom
        else -> width - paddingLeft - paddingRight
    }
    if (pageSize <= 0) {
        setCurrentItem(targetItem, false)
        return
    }

    animate().cancel()
    val direction = pageDelta.signForPager(this)
    val slideDistance = pageSize.toFloat()

    setCurrentItem(targetItem, false)

    val startTranslationX = if (orientation == ViewPager2.ORIENTATION_HORIZONTAL) slideDistance * direction else 0f
    val startTranslationY = if (orientation == ViewPager2.ORIENTATION_VERTICAL) slideDistance * direction else 0f
    translationX = startTranslationX
    translationY = startTranslationY

    animate()
        .setDuration(TAB_DIRECT_SWITCH_OUT_DURATION_MS + TAB_DIRECT_SWITCH_IN_DURATION_MS)
        .translationX(0f)
        .translationY(0f)
        .start()
}

private fun Int.signForPager(viewPager: ViewPager2): Float {
    val sign = if (this >= 0) 1f else -1f
    return if (
        viewPager.orientation == ViewPager2.ORIENTATION_HORIZONTAL &&
        viewPager.layoutDirection == View.LAYOUT_DIRECTION_RTL
    ) {
        -sign
    } else {
        sign
    }
}
