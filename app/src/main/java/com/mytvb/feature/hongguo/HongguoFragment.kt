package com.mytvb.feature.hongguo

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.tabs.TabLayoutMediator
import com.mytvb.R
import com.mytvb.core.ui.base.AppToast
import com.mytvb.core.ui.base.OnBackPressedHandler
import com.mytvb.core.ui.tab.enableTouchNavigation
import com.mytvb.core.ui.tab.focusSelectedTab
import com.mytvb.databinding.FragmentHongguoBinding
import com.mytvb.ui.activity.MainActivity
import com.mytvb.ui.fragment.main.MainTabFocusTarget

/**
 * 红果短剧主页面：五页签（榜单/历史/收藏/分类/我的）+ 右上搜索入口。
 *
 * 隔离约束（设计定案）：
 * - 数据层全部走 feature/hongguo 包内自建 OkHttpClient，禁止复用 NetworkManager/
 *   BiliClient（会带上 B 站代际校验与 cookie 体系）；
 * - 设备身份/登录票据存红果独立 DataStore，与 B 站 NetworkSessionStore 零共享；
 *   红果会话失效只降级游客态，不发全局账号事件。
 */
class HongguoFragment : Fragment(), MainTabFocusTarget, OnBackPressedHandler {

    private var _binding: FragmentHongguoBinding? = null
    private val binding get() = _binding!!

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
        // 搜索层/货架层显示时 BACK 先收层，不退出红果页
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    when {
                        searchShown -> hideSearchOverlay()
                        shelfShown -> hideShelfOverlay()
                        else -> {
                            // 转发给 Activity 前先短暂停用自己，转发完必须恢复，
                            // 否则回调永久失效，之后搜索层的 BACK 会穿透到退出链
                            isEnabled = false
                            requireActivity().onBackPressedDispatcher.onBackPressed()
                            isEnabled = true
                        }
                    }
                }
            }
        )
    }

    private fun setupTabsAndViewPager() {
        binding.viewPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int = PAGE_TITLES.size
            override fun createFragment(position: Int): Fragment = when (position) {
                0 -> HongguoRankPage.newInstance()
                1 -> HongguoFeedPage.newInstance(HongguoFeedPage.MODE_HISTORY)
                2 -> HongguoFeedPage.newInstance(HongguoFeedPage.MODE_FAVORITE)
                3 -> HongguoCategoryPage.newInstance()
                else -> HongguoMinePage.newInstance()
            }
        }
        binding.viewPager.offscreenPageLimit = PAGE_TITLES.size - 1
        binding.viewPager.isUserInputEnabled = false
        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = getString(PAGE_TITLES[position])
        }.attach()
        // 离屏页视图全保留，但必须不可聚焦：否则遥控按键会被不可见页的
        // 控件（筛选 chip 等）几何截走（搜索返回焦点被离屏页抢走同款问题）
        binding.viewPager.registerOnPageChangeCallback(object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                syncPageFocusability(position)
                // tab 选中即回顶：离屏页全 retain，历史/收藏切走再切回会停在上次
                // 滚动位置（用户预期=打开在最上方）
                (childFragmentManager.findFragmentByTag("f$position") as? HongguoPageFocus)
                    ?.scrollToListTop()
            }
        })
        binding.viewPager.post { syncPageFocusability(binding.viewPager.currentItem) }

        // tab 焦点导航：UP/左边缘逃侧栏（落点必须是侧栏当前主 tab 按钮，勿用几何最近）；
        // DOWN 落当前页第一张卡片（不接则框架几何搜索把焦点送进侧栏/筛选行死循环）；
        // 右边缘进同排搜索胶囊（行内移动，非页面边缘环绕）。
        // 必须用 enableTouchNavigation（同 B 站各主页签）：其 OK 键/点击走
        // setCurrentItemFromTab 瞬切+滑入动画；enableDirectTabNavigation 不接管 OK 键，
        // 切换落到 TabLayoutMediator 的 setCurrentItem(pos, true) 平滑滚动，
        // 远端页签（榜单→我的）会逐页滑过 ~800ms
        binding.tabLayout.enableTouchNavigation(
            viewPager = binding.viewPager,
            onNavigateUp = {
                (activity as? MainActivity)?.focusSidebarCurrentTab() == true
            },
            onNavigateDown = { focusCurrentPageContent() },
            onNavigateLeft = {
                (activity as? MainActivity)?.focusSidebarCurrentTab() == true
            },
            onNavigateRight = { binding.buttonHongguoSearch.requestFocus() }
        )
        // enableTouchNavigation 内部强制开启触摸滑动，恢复 TV 页面禁滑动的原设计
        binding.viewPager.isUserInputEnabled = false
    }

    /** 侧栏 RIGHT / tab DOWN 的统一入口：聚焦当前 ViewPager2 子页的第一张卡片。 */
    private fun focusCurrentPageContent(): Boolean {
        val page = childFragmentManager.findFragmentByTag("f${binding.viewPager.currentItem}")
        return (page as? HongguoPageFocus)?.focusFirstCard() == true
    }

    /**
     * BACK 三层回退（对齐 Home/Live 等主 tab 页）：
     * 内容区（子页卡片/搜索胶囊）→ 聚焦本页选中的页签；页签栏 → 回左侧主边栏
     * 当前主 tab 按钮（红果按钮）；焦点已在本页外（如侧栏）→ false 交给退出链。
     * 不实现本接口时 BACK 会一路落到 MainActivity.returnToHomeBeforeExit 的
     * focusHomeTab，焦点直接跳主页按钮（跳过本页 tab 栏与侧栏红果入口）。
     */
    override fun onBackPressed(): Boolean {
        val currentBinding = _binding ?: return false
        if (!currentBinding.root.hasFocus()) {
            return false
        }
        if (currentBinding.tabLayout.hasFocus()) {
            return (activity as? MainActivity)?.focusSidebarCurrentTab() == true
        }
        return currentBinding.tabLayout.focusSelectedTab()
    }

    /** 离屏页根容器的原 focusable 状态（恢复按原值，见 HongguoPagerFocus）。 */
    private val pageRootWasFocusable = HashMap<Int, Boolean>()

    /** 只允许当前页可聚焦（离屏页 BLOCK+根不可聚焦，详见 HongguoPagerFocus）。 */
    private fun syncPageFocusability(current: Int) {
        syncViewPagerPageFocus(childFragmentManager, binding.viewPager, PAGE_TITLES.size, pageRootWasFocusable)
    }

    /** 触摸模式盲恢复等外部焦点入口：覆盖层打开时焦点归覆盖层，不落到被盖住的货架底下。 */
    override fun focusEntryFromMainTab(): Boolean = when {
        searchShown -> {
            val page = childFragmentManager
                .findFragmentById(R.id.container_hongguo_search) as? HongguoSearchPage
            page?.requestInputFocus()
            page != null
        }
        shelfShown -> {
            val page = childFragmentManager
                .findFragmentById(R.id.container_hongguo_shelf) as? HongguoPageFocus
            page?.focusFirstCard() == true
        }
        else -> focusCurrentPageContent()
    }

    private fun setupSearchEntry() {
        binding.buttonHongguoSearch.setOnClickListener { showSearchOverlay() }
    }

    private var searchShown = false

    /** 打开搜索覆盖层（懒添加 HongguoSearchPage），返回键收起回胶囊焦点。 */
    private fun showSearchOverlay() {
        if (searchShown) return
        searchShown = true
        binding.containerHongguoSearch.visibility = View.VISIBLE
        // 焦点钳制：FocusFinder 几何搜索不看遮挡，列表边缘按键（chips 最右 RIGHT、
        // 榜单尾行 DOWN）会逃进被覆盖层盖住的 tab/胶囊/页面卡片，表现为焦点消失；
        // 打开期间屏蔽底层全部可聚焦内容（同 syncPageFocusability 离屏页 BLOCK 思路）
        binding.tabLayout.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        binding.buttonHongguoSearch.isFocusable = false
        binding.viewPager.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS)
        val existing = childFragmentManager
            .findFragmentById(R.id.container_hongguo_search) as? HongguoSearchPage
        if (existing == null) {
            childFragmentManager.beginTransaction()
                .add(R.id.container_hongguo_search, HongguoSearchPage.newInstance())
                .commitNowAllowingStateLoss()
        } else {
            // 复用已存在的实例：view onViewCreated 不会再跑，聚焦必须显式归还输入框
            existing.requestInputFocus()
        }
    }

    fun hideSearchOverlay() {
        if (!searchShown) return
        searchShown = false
        binding.containerHongguoSearch.visibility = View.GONE
        // 恢复底层焦点能力（顺序：先恢复再收焦，胶囊要先可聚焦才能接住焦点；
        // viewPager 自身的 BLOCK 必须先清，syncPageFocusability 只管各页 Fragment view）
        binding.buttonHongguoSearch.isFocusable = true
        binding.tabLayout.descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
        binding.viewPager.descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
        syncPageFocusability(binding.viewPager.currentItem)
        binding.buttonHongguoSearch.requestFocus()
    }

    // ---- 封面货架覆盖层（我的页四入口共用）----

    private var shelfShown = false

    /** 打开货架覆盖层（懒添加 HongguoShelfPage；复用实例时换数据源）。 */
    fun showShelfOverlay(source: HongguoShelfPage.Source) {
        if (shelfShown) return
        shelfShown = true
        binding.containerHongguoShelf.visibility = View.VISIBLE
        val existing = childFragmentManager
            .findFragmentById(R.id.container_hongguo_shelf) as? HongguoShelfPage
        if (existing == null) {
            childFragmentManager.beginTransaction()
                .add(R.id.container_hongguo_shelf, HongguoShelfPage.newInstance(source))
                .commitNowAllowingStateLoss()
        } else {
            existing.reopen(source)
        }
    }

    fun hideShelfOverlay() {
        if (!shelfShown) return
        shelfShown = false
        binding.containerHongguoShelf.visibility = View.GONE
        // 焦点还原到我的页功能卡（focusFirstCard：已登录=第一可见 lane 首卡，未登录=登录钮）
        val minePage = childFragmentManager.findFragmentByTag("f4") as? HongguoPageFocus
        if (minePage?.focusFirstCard() != true) binding.tabLayout.focusSelectedTab()
    }

    /** 列表卡片 UP 边缘的落点：回顶部 tab 栏（HongguoFeedPage 经 parentFragment 调用）。 */
    fun focusTopTab(): Boolean = binding.tabLayout.focusSelectedTab()

    override fun onDestroyView() {
        super.onDestroyView()
        searchShown = false
        shelfShown = false
        _binding = null
    }

    companion object {
        // 侧栏固定序：0推荐/1分类/2动态/3直播/4CCTV直播/5CCTV影视/6红果/7我的/8搜索

        private val PAGE_TITLES = intArrayOf(
            R.string.hongguo_tab_rank,
            R.string.hongguo_tab_history,
            R.string.hongguo_tab_favorite,
            R.string.hongguo_tab_category,
            R.string.hongguo_tab_mine
        )

        fun newInstance(): HongguoFragment = HongguoFragment()
    }
}
