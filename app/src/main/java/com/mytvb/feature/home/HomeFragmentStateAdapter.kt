package com.mytvb.feature.home

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.mytvb.MyBLBLApplication
import com.mytvb.R
import com.mytvb.feature.favorite.FavoriteFragment
import com.mytvb.feature.me.MeListFragment

/**
 * 首页二级 tab 配置项。
 * itemId 全局稳定（与位置无关）：「首页个人页签」开关切换时走 FragmentStateAdapter
 * 的 itemId diff，推荐/热门/番剧/影视四页及其数据/滚动位置原样保留，
 * 三个个人页（历史/稍后观看/收藏）按需创建/销毁。
 */
data class HomeTabSpec(
    val itemId: Long,
    val titleRes: Int,
    val factory: () -> Fragment
)

class HomeFragmentStateAdapter(
    private val fragmentManager: FragmentManager,
    lifecycle: Lifecycle,
    initialSpecs: List<HomeTabSpec> = DEFAULT_SPECS
) : FragmentStateAdapter(fragmentManager, lifecycle) {

    private var currentSpecs: List<HomeTabSpec> = initialSpecs

    val specs: List<HomeTabSpec> get() = currentSpecs

    override fun getItemCount(): Int = currentSpecs.size

    override fun createFragment(position: Int): Fragment {
        return currentSpecs[position].factory()
    }

    override fun getItemId(position: Int): Long = currentSpecs[position].itemId

    override fun containsItem(itemId: Long): Boolean = currentSpecs.any { it.itemId == itemId }

    fun getCurrentFragment(position: Int): Fragment? {
        if (position < 0 || position >= currentSpecs.size) return null
        val fragmentTag = "f${getItemId(position)}"
        return fragmentManager.findFragmentByTag(fragmentTag)
    }

    fun getPageTitle(position: Int): CharSequence? {
        return currentSpecs.getOrNull(position)?.titleRes?.let {
            MyBLBLApplication.instance.getString(it)
        }
    }

    /** 换 tab 配置（开关切换）：itemId 未变的页保留，移除的销毁，新增的按需创建。 */
    fun updateSpecs(newSpecs: List<HomeTabSpec>) {
        if (newSpecs == currentSpecs) return
        currentSpecs = newSpecs
        notifyDataSetChanged()
    }

    companion object {
        /** 「首页个人页签」开关存储键（值中文「开/关」，默认开，与 live_entry 同风格） */
        const val PERSONAL_TABS_KEY = "home_personal_tabs"

        /** 默认配置（开关关）：与历史静态 tab 表完全一致 */
        val DEFAULT_SPECS = listOf(
            HomeTabSpec(0, R.string.recommend) { RecommendListFragment.newInstance() },
            HomeTabSpec(1, R.string.hot) { HotListFragment.newInstance() },
            HomeTabSpec(2, R.string.animation) { HomeLaneFragment.newInstance(HomeLaneFragment.TYPE_ANIMATION) },
            HomeTabSpec(3, R.string.film_and_television) { HomeLaneFragment.newInstance(HomeLaneFragment.TYPE_CINEMA) }
        )

        /** 开关开：历史/稍后观看插在热门后、番剧前，收藏放最后 */
        val PERSONAL_SPECS = listOf(
            DEFAULT_SPECS[0],
            DEFAULT_SPECS[1],
            HomeTabSpec(4, R.string.history) { MeListFragment.newInstance(MeListFragment.TYPE_HISTORY) },
            HomeTabSpec(5, R.string.later_watch) { MeListFragment.newInstance(MeListFragment.TYPE_LATER) },
            DEFAULT_SPECS[2],
            DEFAULT_SPECS[3],
            HomeTabSpec(6, R.string.collection) { FavoriteFragment.newEmbeddedInstance() }
        )

        /** 读开关（DataStore 无值视为默认开） */
        fun personalTabsEnabled(appSettings: com.mytvb.core.common.settings.AppSettingsDataStore): Boolean {
            return (appSettings.getCachedString(PERSONAL_TABS_KEY) ?: "开") == "开"
        }
    }
}
