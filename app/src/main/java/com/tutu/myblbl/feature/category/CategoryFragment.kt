package com.tutu.myblbl.feature.category

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
import com.tutu.myblbl.R
import com.tutu.myblbl.databinding.FragmentCategoryBinding
import com.tutu.myblbl.model.CategoryModel
import com.tutu.myblbl.core.ui.base.BaseFragment
import com.tutu.myblbl.ui.fragment.main.MainNavigationViewModel
import com.tutu.myblbl.ui.fragment.main.MainTabFocusTarget
import com.tutu.myblbl.core.ui.tab.enableTouchNavigation
import com.tutu.myblbl.core.ui.tab.focusNearestTabTo
import com.tutu.myblbl.core.ui.tab.disableAdjacentPagePrefetch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class CategoryFragment : BaseFragment<FragmentCategoryBinding>(), MainTabFocusTarget {

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
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = adapter.getPageTitle(position)
        }.attach()
        tabLayout.enableTouchNavigation(
            viewPager = viewPager,
            onNavigateDown = ::focusCurrentPagePrimaryContent,
            onTabReselected = {
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
        val resources = requireContext().resources

        categories.add(CategoryModel(0, resources.getString(R.string.allWeb)))
        categories.add(CategoryModel(1, resources.getString(R.string.cartoon)))
        categories.add(CategoryModel(3, resources.getString(R.string.music)))
        categories.add(CategoryModel(129, resources.getString(R.string.dance)))
        categories.add(CategoryModel(4, resources.getString(R.string.game)))
        categories.add(CategoryModel(36, resources.getString(R.string.knowledge)))
        categories.add(CategoryModel(188, resources.getString(R.string.science)))
        categories.add(CategoryModel(234, resources.getString(R.string.sport)))
        categories.add(CategoryModel(223, resources.getString(R.string.auto)))
        categories.add(CategoryModel(160, resources.getString(R.string.lifestyle)))
        categories.add(CategoryModel(211, resources.getString(R.string.food)))
        categories.add(CategoryModel(217, resources.getString(R.string.pet)))
        categories.add(CategoryModel(119, resources.getString(R.string.kichiku)))
        categories.add(CategoryModel(155, resources.getString(R.string.fashion)))
        categories.add(CategoryModel(5, resources.getString(R.string.entainment)))
        categories.add(CategoryModel(181, resources.getString(R.string.film_and_television)))
        categories.add(CategoryModel(177, resources.getString(R.string.documentary)))
        categories.add(CategoryModel(23, resources.getString(R.string.movie)))
        categories.add(CategoryModel(11, resources.getString(R.string.series)))

        adapter.setCategories(categories)
        
        viewPager.currentItem = 0
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
