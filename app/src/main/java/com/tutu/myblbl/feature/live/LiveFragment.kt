package com.tutu.myblbl.feature.live

import android.graphics.Rect
import android.os.Bundle
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
import com.tutu.myblbl.R
import com.tutu.myblbl.databinding.FragmentLiveBinding
import com.tutu.myblbl.model.live.LiveAreaCategoryParent
import com.tutu.myblbl.core.ui.base.BaseFragment
import com.tutu.myblbl.ui.fragment.main.MainNavigationViewModel
import com.tutu.myblbl.ui.fragment.main.MainTabFocusTarget
import com.tutu.myblbl.core.ui.tab.enableTouchNavigation
import com.tutu.myblbl.core.ui.tab.focusNearestTabTo
import com.tutu.myblbl.core.common.ext.toast
import com.tutu.myblbl.core.common.log.AppLog
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel

class LiveFragment : BaseFragment<FragmentLiveBinding>(), MainTabFocusTarget {
    companion object {
        private const val CATEGORY_CACHE_TTL_MS = 20 * 60 * 1000L

        fun newInstance(): LiveFragment = LiveFragment()
    }

    private val viewModel: LiveViewModel by viewModel()
    private val mainNavigationViewModel: MainNavigationViewModel by activityViewModels()
    private lateinit var tabLayout: TabLayout
    private lateinit var viewPager: ViewPager2
    private lateinit var adapter: LiveFragmentAdapter
    private val categories = mutableListOf<LiveAreaCategoryParent>()
    private var tabSelectedListener: TabLayout.OnTabSelectedListener? = null

    override fun getViewBinding(
        inflater: LayoutInflater,
        container: ViewGroup?
    ): FragmentLiveBinding {
        return FragmentLiveBinding.inflate(inflater, container, false)
    }

    override fun initView() {
        tabLayout = binding.tabLayout
        viewPager = binding.viewPager

        adapter = LiveFragmentAdapter(
            childFragmentManager,
            lifecycle
        )
        viewPager.adapter = adapter
        viewPager.offscreenPageLimit = 1
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = adapter.getPageTitle(position)
        }.attach()
        tabLayout.enableTouchNavigation(
            viewPager = viewPager,
            onNavigateDown = ::focusCurrentPagePrimaryContent
        )
        tabSelectedListener = object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = Unit

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit

            override fun onTabReselected(tab: TabLayout.Tab) {
                mainNavigationViewModel.dispatch(
                    MainNavigationViewModel.Event.SecondaryTabReselected(
                        host = MainNavigationViewModel.SecondaryTabHost.LIVE,
                        position = tab.position
                    )
                )
            }
        }.also { tabLayout.addOnTabSelectedListener(it) }
    }

    override fun initData() {
        viewModel.loadLiveAreas()
    }

    override fun initObserver() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.categories.collectLatest { list ->
                    if (list.isNotEmpty()) {
                        val previousItem = viewPager.currentItem
                        categories.clear()
                        categories.addAll(list)
                        adapter.setCategories(categories)
                        tabLayout.enableTouchNavigation(
                            viewPager = viewPager,
                            onNavigateDown = ::focusCurrentPagePrimaryContent
                        )
                        viewPager.currentItem = previousItem.coerceIn(0, categories.lastIndex)
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.error.collectLatest { error ->
                    if (!error.isNullOrBlank()) {
                        requireContext().toast(error)
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                mainNavigationViewModel.events.collectLatest { event ->
                    if (isHidden) {
                        return@collectLatest
                    }
                    when (event) {
                        is MainNavigationViewModel.Event.MainTabSelected ->
                            if (event.index == 3) {
                                if (viewModel.shouldRefresh(CATEGORY_CACHE_TTL_MS)) {
                                    viewModel.loadLiveAreas()
                                }
                                adapter.getCurrentFragment(viewPager.currentItem)?.onTabSelected()
                            }

                        is MainNavigationViewModel.Event.MainTabReselected ->
                            if (event.index == 3) {
                                adapter.getCurrentFragment(viewPager.currentItem)?.onExplicitRefresh()
                            }

                        MainNavigationViewModel.Event.MenuPressed ->
                            adapter.getCurrentFragment(viewPager.currentItem)?.onExplicitRefresh()

                        MainNavigationViewModel.Event.BackPressed -> scrollToTop()
                        else -> Unit
                    }
                }
            }
        }
    }

    fun scrollToTop() {
        adapter.getCurrentFragment(viewPager.currentItem)?.scrollToTop()
    }

    fun focusCurrentTab(anchorView: View? = view?.findFocus() ?: activity?.currentFocus): Boolean {
        return binding.tabLayout.focusNearestTabTo(anchorView)
    }

    override fun focusEntryFromMainTab(): Boolean {
        AppLog.d("LiveFocus", "focusEntryFromMainTab: noAnchor")
        return focusEntryFromMainTab(anchorView = null, preferSpatialEntry = false)
    }

    override fun focusEntryFromMainTab(anchorView: View?, preferSpatialEntry: Boolean): Boolean {
        AppLog.d("LiveFocus", "focusEntryFromMainTab: anchor=${anchorView?.javaClass?.simpleName} spatial=$preferSpatialEntry page=${viewPager.currentItem}")
        if (preferSpatialEntry && anchorView != null && isVerticallyAlignedWith(anchorView, tabLayout)) {
            if (focusCurrentTab(anchorView)) {
                AppLog.d("LiveFocus", "focusEntryFromMainTab: spatial→tab OK")
                return true
            }
        }
        val contentHandled = focusCurrentPagePrimaryContent(anchorView, preferSpatialEntry)
        AppLog.d("LiveFocus", "focusEntryFromMainTab: contentHandled=$contentHandled")
        val handled = contentHandled || focusCurrentTab(anchorView)
        AppLog.d("LiveFocus", "focusEntryFromMainTab: final=$handled")
        return handled
    }

    private fun focusCurrentPagePrimaryContent(anchorView: View? = null, preferSpatialEntry: Boolean = false): Boolean {
        val page = adapter.getCurrentFragment(viewPager.currentItem)
        AppLog.d("LiveFocus", "focusCurrentPagePrimaryContent: page=${page?.javaClass?.simpleName} anchor=${anchorView?.javaClass?.simpleName} spatial=$preferSpatialEntry")
        return page?.focusPrimaryContent(anchorView, preferSpatialEntry) == true
    }

    override fun onDestroyView() {
        tabSelectedListener?.let { tabLayout.removeOnTabSelectedListener(it) }
        tabSelectedListener = null
        binding.viewPager.adapter = null
        super.onDestroyView()
    }

    private fun isVerticallyAlignedWith(anchor: View, target: View): Boolean {
        val anchorRect = Rect()
        val targetRect = Rect()
        if (!anchor.getGlobalVisibleRect(anchorRect)) return false
        if (!target.getGlobalVisibleRect(targetRect)) return false
        return maxOf(anchorRect.top, targetRect.top) < minOf(anchorRect.bottom, targetRect.bottom)
    }
}
