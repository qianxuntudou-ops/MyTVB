package com.tutu.myblbl.feature.home

import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import androidx.viewpager2.widget.ViewPager2
import com.tutu.myblbl.databinding.FragmentHomeBinding
import com.tutu.myblbl.ui.activity.MainActivity
import com.tutu.myblbl.ui.fragment.main.MainNavigationViewModel
import com.tutu.myblbl.ui.fragment.main.MainTabFocusTarget
import com.tutu.myblbl.core.ui.tab.enableTouchNavigation
import com.tutu.myblbl.core.ui.tab.focusNearestTabTo
import com.tutu.myblbl.core.ui.tab.disableAdjacentPagePrefetch
import com.tutu.myblbl.core.ui.focus.SpatialFocusNavigator
import com.tutu.myblbl.core.common.ext.getHomeDefaultStartPageIndex
import com.tutu.myblbl.core.common.log.AppLog
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class HomeFragment : Fragment(), MainTabFocusTarget {

    companion object {
        fun newInstance(): HomeFragment {
            return HomeFragment()
        }
    }

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val mainNavigationViewModel: MainNavigationViewModel by activityViewModels()
    private lateinit var adapter: HomeFragmentStateAdapter
    private var tabMediator: TabLayoutMediator? = null
    private var pageChangeCallback: ViewPager2.OnPageChangeCallback? = null
    private var tabSelectedListener: TabLayout.OnTabSelectedListener? = null
    private var lastTabSelectedPosition = -1
    private var lastTabSelectedTime = 0L

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val t0 = SystemClock.elapsedRealtime()
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        AppLog.i("STARTUP", "HomeFragment.onCreateView elapsed=${SystemClock.elapsedRealtime() - t0}ms")
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val t0 = SystemClock.elapsedRealtime()
        super.onViewCreated(view, savedInstanceState)
        adapter = HomeFragmentStateAdapter(childFragmentManager, viewLifecycleOwner.lifecycle)
        binding.viewPager.adapter = adapter
        binding.viewPager.disableAdjacentPagePrefetch()
        tabMediator = TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = adapter.getPageTitle(position)
        }.also { it.attach() }
        binding.tabLayout.enableTouchNavigation(
            viewPager = binding.viewPager,
            onNavigateDown = ::focusCurrentPagePrimaryContent,
            onNavigateLeft = ::focusLeftFunctionArea
        )

        tabSelectedListener = object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                lastTabSelectedPosition = tab.position
                lastTabSelectedTime = System.currentTimeMillis()
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {
            }

            override fun onTabReselected(tab: TabLayout.Tab) {
                val elapsed = System.currentTimeMillis() - lastTabSelectedTime
                if (tab.position == lastTabSelectedPosition && elapsed < 300) {
                    return
                }
                lastTabSelectedPosition = tab.position
                lastTabSelectedTime = System.currentTimeMillis()
                postTopTabEvent(tab.position)
            }
        }.also { binding.tabLayout.addOnTabSelectedListener(it) }
        pageChangeCallback = object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                notifyTabSelected(position)
            }
        }.also { callback ->
            binding.viewPager.registerOnPageChangeCallback(callback)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                mainNavigationViewModel.events.collectLatest { event ->
                    val currentBinding = _binding ?: return@collectLatest
                    if (isHidden) {
                        return@collectLatest
                    }
                    if (event is MainNavigationViewModel.Event.MainTabSelected && event.index == 0) {
                        (adapter.getCurrentFragment(currentBinding.viewPager.currentItem) as? HomeTabPage)
                            ?.onTabSelected()
                    }
                }
            }
        }

        binding.viewPager.currentItem = getDefaultTabIndex()
        AppLog.i("STARTUP", "HomeFragment.onViewCreated elapsed=${SystemClock.elapsedRealtime() - t0}ms")
    }

    override fun onDestroyView() {
        pageChangeCallback?.let { binding.viewPager.unregisterOnPageChangeCallback(it) }
        pageChangeCallback = null
        tabSelectedListener?.let { binding.tabLayout.removeOnTabSelectedListener(it) }
        tabSelectedListener = null
        tabMediator?.detach()
        tabMediator = null
        binding.viewPager.adapter = null
        super.onDestroyView()
        _binding = null
    }

    private fun getDefaultTabIndex(): Int {
        return requireContext().getHomeDefaultStartPageIndex(
            maxIndex = adapter.itemCount - 1,
            defaultIndex = 0
        )
    }

    private fun postTopTabEvent(position: Int) {
        mainNavigationViewModel.dispatch(
            MainNavigationViewModel.Event.SecondaryTabReselected(
                host = MainNavigationViewModel.SecondaryTabHost.HOME,
                position = position
            )
        )
    }

    fun focusCurrentTab(anchorView: View? = view?.findFocus() ?: activity?.currentFocus): Boolean {
        val currentBinding = _binding ?: return false
        return currentBinding.tabLayout.focusNearestTabTo(anchorView)
    }

    override fun focusEntryFromMainTab(): Boolean {
        return focusEntryFromMainTab(anchorView = null, preferSpatialEntry = false)
    }

    override fun focusEntryFromMainTab(anchorView: View?, preferSpatialEntry: Boolean): Boolean {
        if (preferSpatialEntry && anchorView != null) {
            val currentBinding = _binding ?: return false
            if (isVerticallyAlignedWith(anchorView, currentBinding.tabLayout)) {
                if (focusCurrentTab(anchorView)) return true
            }
            val handled = SpatialFocusNavigator.requestBestDescendant(
                anchorView = anchorView,
                root = currentBinding.root,
                direction = View.FOCUS_RIGHT,
                fallback = null
            )
            if (handled) return true
        }
        val handled = focusCurrentPagePrimaryContent(anchorView, preferSpatialEntry) ||
            focusCurrentTab(anchorView)
        return handled
    }

    private fun isVerticallyAlignedWith(anchor: View, target: View): Boolean {
        val anchorRect = Rect()
        val targetRect = Rect()
        if (!anchor.getGlobalVisibleRect(anchorRect)) return false
        if (!target.getGlobalVisibleRect(targetRect)) return false
        return maxOf(anchorRect.top, targetRect.top) < minOf(anchorRect.bottom, targetRect.bottom)
    }

    private fun focusCurrentPagePrimaryContent(anchorView: View? = null, preferSpatialEntry: Boolean = false): Boolean {
        return getCurrentTabPage()?.focusPrimaryContent(anchorView, preferSpatialEntry) == true
    }

    private fun focusLeftFunctionArea(): Boolean {
        return (activity as? MainActivity)?.focusLeftFunctionArea() == true
    }

    private fun getCurrentTabPage(): HomeTabPage? {
        val currentItem = binding.viewPager.currentItem
        val fragmentTag = "f${adapter.getItemId(currentItem)}"
        return childFragmentManager.findFragmentByTag(fragmentTag) as? HomeTabPage
    }

    private fun notifyTabSelected(position: Int, retries: Int = 5) {
        val page = adapter.getCurrentFragment(position) as? HomeTabPage
        if (page != null) {
            page.onTabSelected()
        } else if (retries > 0) {
            binding.viewPager.post { notifyTabSelected(position, retries - 1) }
        }
    }
}
