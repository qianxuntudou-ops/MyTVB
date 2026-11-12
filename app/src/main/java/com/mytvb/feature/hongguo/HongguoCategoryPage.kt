package com.mytvb.feature.hongguo

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.mytvb.core.ui.tab.enableTouchNavigation
import com.mytvb.core.ui.tab.focusSelectedTab
import com.mytvb.databinding.FragmentHongguoCategoryBinding
import com.mytvb.ui.activity.MainActivity

/** 红果分类页：全部/漫剧/AI剧 三子档（landpage genre 切换）。 */
class HongguoCategoryPage : Fragment(), HongguoPageFocus {

    private var _binding: FragmentHongguoCategoryBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHongguoCategoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val genres = listOf("全部" to "", "漫剧" to "comic", "AI剧" to "ai")
        binding.viewPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int = genres.size
            override fun createFragment(position: Int): Fragment =
                HongguoFeedPage.newInstance(HongguoFeedPage.MODE_FEED, genres[position].second).apply {
                    // 子档页卡片/筛选行 UP 的顶部落点=本页子档 tab（非宿主一级 tab）
                    topEdgeExit = { focusGenreTab() }
                }
        }
        binding.viewPager.isUserInputEnabled = false
        TabLayoutMediator(binding.tabGenre, binding.viewPager) { tab, position ->
            tab.text = genres[position].first
        }.attach()
        // 子档离屏页焦点域管理（与一级六页同款，见 HongguoPagerFocus）：
        // 不关的话离屏子档的筛选 chip/卡片/页根都参与几何搜索，当前子档芯片
        // 边缘按键焦点会被屏外控件截走（用户实测：ScaledTextView→屏外 RecyclerView）
        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = syncFocus()
        })
        binding.viewPager.post { syncFocus() }
        // 子档 tab 焦点导航：UP 回红果一级 tab、DOWN 落当前子档第一张卡、LEFT 到侧栏
        // （用 enableTouchNavigation：OK 键瞬切+滑入，与一级 tab 同款；再关掉触摸滑动）
        binding.tabGenre.enableTouchNavigation(
            viewPager = binding.viewPager,
            onNavigateUp = ::focusHostTopTab,
            onNavigateDown = { focusFirstCard() },
            onNavigateLeft = { (activity as? MainActivity)?.focusSidebarCurrentTab() == true },
            // 右边缘无去处，吞键停住（放行会被 FocusFinder 几何搜索送到不可预期位置）
            onNavigateRight = { true }
        )
        binding.viewPager.isUserInputEnabled = false
    }

    /** 子档 tab（二级）：卡片/筛选行 UP 的顶部落点。 */
    private fun focusGenreTab(): Boolean {
        val b = _binding ?: return false
        return b.tabGenre.focusSelectedTab()
    }

    private val pageRootWasFocusable = HashMap<Int, Boolean>()

    private fun syncFocus() {
        val b = _binding ?: return
        syncViewPagerPageFocus(childFragmentManager, b.viewPager, GENRE_COUNT, pageRootWasFocusable)
    }

    /** 子档 tab UP 的逃逸：宿主红果一级 tab。 */
    private fun focusHostTopTab(): Boolean {
        val host = parentFragment as? HongguoFragment ?: return false
        return host.focusTopTab()
    }

    /** 一级 tab DOWN / 卡片 UP 的二级 tab 落点：分类子档 tab。 */
    override fun focusSecondaryTab(): Boolean = focusGenreTab()

    /** 侧栏 RIGHT / 红果 tab DOWN 的落点：当前子档第一张卡片。 */
    override fun focusFirstCard(): Boolean {
        if (!isAdded || view == null) return false
        val page = childFragmentManager.findFragmentByTag("f${binding.viewPager.currentItem}")
        return (page as? HongguoPageFocus)?.focusFirstCard() == true
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val GENRE_COUNT = 3
        fun newInstance(): HongguoCategoryPage = HongguoCategoryPage()
    }
}
