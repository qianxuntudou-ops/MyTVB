package com.mytvb.feature.category

import android.graphics.Rect
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.mytvb.databinding.FragmentCategoryBinding
import com.mytvb.model.CategoryModel
import com.mytvb.core.ui.base.BaseFragment
import com.mytvb.core.common.settings.AppSettingsDataStore
import com.mytvb.core.ui.focus.EdgeTabSwitch
import com.mytvb.ui.fragment.main.MainNavigationViewModel
import com.mytvb.ui.fragment.main.MainTabFocusTarget
import com.mytvb.core.ui.tab.enableTouchNavigation
import com.mytvb.core.ui.tab.focusNearestTabTo
import com.mytvb.core.ui.tab.focusSelectedTab
import com.mytvb.core.ui.base.OnBackPressedHandler
import com.mytvb.core.ui.tab.disableAdjacentPagePrefetch
import com.mytvb.core.ui.tab.retainAllPagesAfterFirstLayout
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class CategoryFragment : BaseFragment<FragmentCategoryBinding>(), MainTabFocusTarget, OnBackPressedHandler {

    companion object {
        fun newInstance(): CategoryFragment = CategoryFragment()
    }

    private lateinit var tabLayout: TabLayout
    private lateinit var viewPager: ViewPager2
    private lateinit var adapter: CategoryFragmentAdapter
    private val categories = mutableListOf<CategoryModel>()
    private var pageChangeCallback: ViewPager2.OnPageChangeCallback? = null
    private var tabSelectedListener: TabLayout.OnTabSelectedListener? = null
    private val mainNavigationViewModel: MainNavigationViewModel by activityViewModels()
    private val appSettings: AppSettingsDataStore by inject()

    override fun getViewBinding(
        inflater: LayoutInflater,
        container: ViewGroup?
    ): FragmentCategoryBinding {
        return FragmentCategoryBinding.inflate(inflater, container, false)
    }

    override fun initView() {
        tabLayout = binding.tabLayout
        viewPager = binding.viewPager

        adapter = CategoryFragmentAdapter(
            childFragmentManager,
            lifecycle
        )
        viewPager.adapter = adapter
        viewPager.disableAdjacentPagePrefetch()
        // 与首页一致：首帧后保留全部 tab 页，切 tab 不销毁 fragment——滚动进度/焦点/
        // 已加载数据切回来原样保留，不触发重拉（推荐流每次内容都变，重拉=卡片全换）
        viewPager.retainAllPagesAfterFirstLayout()
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = adapter.getPageTitle(position)
        }.attach()
        tabLayout.enableTouchNavigation(
            viewPager = viewPager,
            onNavigateDown = ::focusCurrentPageNearestContent,
            onNavigateLeft = ::focusLeftFunctionArea,
            onTabReselected = {
                // 重选当前分区 tab = 刷新（对齐首页 v2.0.9 行为/blbl refreshCurrentPageFromTabReselect）；
                // 切 tab 不刷新（retainAllPages 保页），刷新入口=重选 tab / MENU 键
                adapter.getCurrentFragment(viewPager.currentItem)?.refresh()
            }
        )
        tabSelectedListener = object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = Unit

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit

            override fun onTabReselected(tab: TabLayout.Tab) {
            }
        }.also { tabLayout.addOnTabSelectedListener(it) }

        pageChangeCallback = object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                notifyTabSelected(position)
            }
        }.also { viewPager.registerOnPageChangeCallback(it) }

        initCategories()
    }

    private fun initCategories() {
        categories.clear()
        categories.addAll(CategoryTabPrefs.allCategories(requireContext()))
        adapter.setCategories(CategoryTabPrefs.visibleCategories(categories))
        viewPager.currentItem = 0
    }

    /**
     * 按「分类页显示的分区」设置重应用 tab 集合（issue#117）：设置页改动后返回本页时刷新。
     * 幂等——集合没变直接返回；变化时尽量保留仍在显示范围内的当前分区选中位。
     */
    private fun refreshVisibleCategories() {
        if (!this::adapter.isInitialized) return
        val visible = CategoryTabPrefs.visibleCategories(categories)
        if (visible.map { it.id } == adapter.currentCategories().map { it.id }) return
        val currentId = adapter.currentCategories().getOrNull(viewPager.currentItem)?.id
        adapter.setCategories(visible)
        val restoreIndex = visible.indexOfFirst { it.id == currentId }.takeIf { it >= 0 } ?: 0
        viewPager.currentItem = restoreIndex
    }

    override fun onResume() {
        super.onResume()
        // 设置页是 overlay fragment，关闭返回时本页走 onResume；hidden 切换走 onHiddenChanged
        refreshVisibleCategories()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            refreshVisibleCategories()
        }
    }

    override fun initData() {
        binding.viewPager.post {
            adapter.getCurrentFragment(viewPager.currentItem)?.onTabSelected()
        }
    }

    override fun initObserver() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                mainNavigationViewModel.events.collectLatest { event ->
                    if (isHidden) {
                        return@collectLatest
                    }
                    when (event) {
                        is MainNavigationViewModel.Event.MainTabSelected ->
                            if (event.index == 1) {
                                adapter.getCurrentFragment(viewPager.currentItem)?.onTabSelected()
                            }

                        is MainNavigationViewModel.Event.MainTabReselected ->
                            if (event.index == 1) {
                                adapter.getCurrentFragment(viewPager.currentItem)?.refresh()
                            }

                        MainNavigationViewModel.Event.MenuPressed ->
                            adapter.getCurrentFragment(viewPager.currentItem)?.refresh()

                        MainNavigationViewModel.Event.BackPressed -> Unit

                        else -> Unit
                    }
                }
            }
        }
    }

    fun scrollToTop() {
        adapter.getCurrentFragment(viewPager.currentItem)?.scrollToTop()
    }

    private fun notifyTabSelected(position: Int, retries: Int = 5) {
        val fragment = adapter.getCurrentFragment(position)
        if (fragment != null) {
            fragment.onTabSelected()
        } else if (retries > 0) {
            viewPager.post { notifyTabSelected(position, retries - 1) }
        }
    }

    override fun onDestroyView() {
        pageChangeCallback?.let { viewPager.unregisterOnPageChangeCallback(it) }
        pageChangeCallback = null
        tabSelectedListener?.let { tabLayout.removeOnTabSelectedListener(it) }
        tabSelectedListener = null
        binding.viewPager.adapter = null
        super.onDestroyView()
    }

    fun focusCurrentTab(anchorView: View? = view?.findFocus() ?: activity?.currentFocus): Boolean {
        return binding.tabLayout.focusNearestTabTo(anchorView)
    }

    /**
     * BACK 三层分发（与 HomeFragment/MeFragment 一致）：
     * 内容区 → 当前选中的分区 tab；tab 栏 → 主边栏当前主 tab 按钮；都不是 → 放行给退出流程。
     */
    override fun onBackPressed(): Boolean {
        if (tabLayout.hasFocus()) {
            return (activity as? com.mytvb.ui.activity.MainActivity)?.focusSidebarCurrentTab() == true
        }
        if (viewPager.hasFocus()) {
            return tabLayout.focusSelectedTab()
        }
        return false
    }

    /** 首个分区 tab 上按 LEFT：聚焦左侧主边栏（与首页一致）。 */
    private fun focusLeftFunctionArea(): Boolean {
        return (activity as? com.mytvb.ui.activity.MainActivity)?.focusLeftFunctionArea() == true
    }

    /** 列表顶行 UP 的落点：聚焦当前选中的分区 tab（不切页）。 */
    fun focusSelectedTabFromContent(): Boolean {
        return tabLayout.focusSelectedTab()
    }

    /** 内容网格左右边缘切分区 tab；首/末 tab 越界方向保持原有行为。 */
    fun switchAdjacentTabFromContentEdge(delta: Int): Boolean {
        // 设置「卡片边缘切换标签」关闭：只关「切 tab」——左边缘环绕回主边栏
        // 当前主 tab 对应按钮；右边缘吞键停原地（对齐 blbl 右边缘无外流）
        if (!EdgeTabSwitch.enabled(appSettings)) {
            if (delta < 0) {
                return (activity as? com.mytvb.ui.activity.MainActivity)?.focusSidebarCurrentTab() == true
            }
            return true
        }
        val target = viewPager.currentItem + delta
        if (target < 0) {
            return (activity as? com.mytvb.ui.activity.MainActivity)?.focusLeftFunctionArea() == true
        }
        if (target >= adapter.itemCount) {
            return true
        }
        viewPager.setCurrentItem(target, true)
        // 切页后旧页焦点随页面滑出，需把焦点交接到新页；布局未稳时补一次延迟重试
        viewPager.post {
            if (isAdded && !focusCurrentPageNearestContent()) {
                viewPager.postDelayed({
                    if (isAdded) {
                        focusCurrentPageNearestContent()
                    }
                }, 120L)
            }
        }
        return true
    }

    /** 顶部 tab 栏按 DOWN：就近聚焦可见第一项（列表位置不动，半截轻滚补齐）；回顶只由重选 tab 承担。 */
    private fun focusCurrentPageNearestContent(): Boolean {
        val page = getCurrentListFragment() ?: return false
        if (page.focusNearestVisibleContent()) {
            return true
        }
        return focusCurrentPagePrimaryContent()
    }

    override fun focusEntryFromMainTab(): Boolean {
        return focusEntryFromMainTab(anchorView = null, preferSpatialEntry = false)
    }

    override fun focusEntryFromMainTab(anchorView: View?, preferSpatialEntry: Boolean): Boolean {
        if (preferSpatialEntry && anchorView != null && isVerticallyAlignedWith(anchorView, tabLayout)) {
            if (focusCurrentTab(anchorView)) return true
        }
        val handled = focusCurrentPagePrimaryContent(anchorView, preferSpatialEntry) ||
            focusCurrentTab(anchorView)
        return handled
    }

    private fun focusCurrentPagePrimaryContent(anchorView: View? = null, preferSpatialEntry: Boolean = false): Boolean {
        return getCurrentListFragment()?.focusPrimaryContent(anchorView, preferSpatialEntry) == true
    }

    private fun getCurrentListFragment(): CategoryListFragment? {
        val currentItem = viewPager.currentItem
        val itemId = adapter.getItemId(currentItem)
        val fragmentTag = "f$itemId"
        childFragmentManager.findFragmentByTag(fragmentTag)?.let { return it as? CategoryListFragment }
        for (fragment in childFragmentManager.fragments) {
            if (fragment is CategoryListFragment && fragment.isVisible) {
                return fragment
            }
        }
        return null
    }

    private fun isVerticallyAlignedWith(anchor: View, target: View): Boolean {
        val anchorRect = Rect()
        val targetRect = Rect()
        if (!anchor.getGlobalVisibleRect(anchorRect)) return false
        if (!target.getGlobalVisibleRect(targetRect)) return false
        return maxOf(anchorRect.top, targetRect.top) < minOf(anchorRect.bottom, targetRect.bottom)
    }
}
