package com.mytvb.feature.hongguo

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.tabs.TabLayoutMediator
import com.mytvb.R
import com.mytvb.core.ui.base.AppToast
import com.mytvb.core.ui.tab.enableDirectTabNavigation
import com.mytvb.core.ui.tab.focusSelectedTab
import com.mytvb.databinding.FragmentHongguoBinding
import com.mytvb.ui.activity.MainActivity
import com.mytvb.ui.fragment.main.MainNavigationViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest

/**
 * 红果短剧主页面：六页签（推荐/历史/收藏/榜单/分类/我的）+ 右上搜索入口。
 *
 * 隔离约束（设计定案）：
 * - 数据层全部走 feature/hongguo 包内自建 OkHttpClient，禁止复用 NetworkManager/
 *   BiliClient（会带上 B 站代际校验与 cookie 体系）；
 * - 设备身份/登录票据存红果独立 DataStore，与 B 站 NetworkSessionStore 零共享；
 *   红果会话失效只降级游客态，不发全局账号事件。
 */
class HongguoFragment : Fragment() {

    private var _binding: FragmentHongguoBinding? = null
    private val binding get() = _binding!!
    private val mainNavigationViewModel: MainNavigationViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHongguoBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupTabsAndViewPager()
        setupSearchEntry()
        observeTabSelected()
    }

    private fun setupTabsAndViewPager() {
        binding.viewPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int = PAGE_TITLES.size
            override fun createFragment(position: Int): Fragment = when (position) {
                0 -> HongguoFeedPage.newInstance(HongguoFeedPage.MODE_FEED)
                1 -> HongguoFeedPage.newInstance(HongguoFeedPage.MODE_HISTORY)
                2 -> HongguoFeedPage.newInstance(HongguoFeedPage.MODE_FAVORITE)
                3 -> HongguoRankPage.newInstance()
                4 -> HongguoCategoryPage.newInstance()
                else -> HongguoMinePage.newInstance()
            }
        }
        binding.viewPager.offscreenPageLimit = PAGE_TITLES.size - 1
        binding.viewPager.isUserInputEnabled = false
        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = getString(PAGE_TITLES[position])
        }.attach()

        // tab 焦点导航：UP/左边缘逃侧栏（落点必须是侧栏当前主 tab 按钮，勿用几何最近）
        binding.tabLayout.enableDirectTabNavigation(
            onNavigateUp = {
                (activity as? MainActivity)?.focusSidebarCurrentTab() == true
            },
            onEdgeLeft = {
                (activity as? MainActivity)?.focusSidebarCurrentTab() == true
            }
        )
    }

    private fun setupSearchEntry() {
        // P1 骨架：搜索入口占位，P2 接红果搜索（suggestion + search/tab 接口）
        binding.buttonHongguoSearch.setOnClickListener {
            AppToast.show(requireContext(), getString(R.string.hongguo_coming_soon))
        }
    }

    private fun observeTabSelected() {
        // 侧栏从其他主 tab 切回红果时，焦点回顶部 tab（对齐 CCTV 影视行为）
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                mainNavigationViewModel.events.collectLatest { event ->
                    if (event is MainNavigationViewModel.Event.MainTabSelected &&
                        event.index == TAB_INDEX
                    ) {
                        binding.tabLayout.focusSelectedTab()
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        // 侧栏固定序：0推荐/1分类/2动态/3直播/4CCTV直播/5CCTV影视/6红果/7我的/8搜索
        private const val TAB_INDEX = 6

        private val PAGE_TITLES = intArrayOf(
            R.string.hongguo_tab_recommend,
            R.string.hongguo_tab_history,
            R.string.hongguo_tab_favorite,
            R.string.hongguo_tab_rank,
            R.string.hongguo_tab_category,
            R.string.hongguo_tab_mine
        )

        fun newInstance(): HongguoFragment = HongguoFragment()
    }
}
