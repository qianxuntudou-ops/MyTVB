package com.mytvb.feature.home

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.mytvb.MyBLBLApplication

/**
 * 首页二级 tab 配置项，由 [HomeTabPrefs] 按用户配置（显示/隐藏 + 排序）生成。
 * itemId 全局稳定（与位置无关）：改配置时走 FragmentStateAdapter 的 itemId diff，
 * 未移除的页及其数据/滚动位置原样保留，隐藏/新增的页按需创建或销毁。
 */
data class HomeTabSpec(
    val itemId: Long,
    val titleRes: Int,
    val factory: () -> Fragment
)

class HomeFragmentStateAdapter(
    private val fragmentManager: FragmentManager,
    lifecycle: Lifecycle,
    initialSpecs: List<HomeTabSpec>
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

    /** 换 tab 配置（设置弹窗确认后）：itemId 未变的页保留，移除的销毁，新增的按需创建。 */
    fun updateSpecs(newSpecs: List<HomeTabSpec>) {
        if (newSpecs == currentSpecs) return
        currentSpecs = newSpecs
        notifyDataSetChanged()
    }
}
