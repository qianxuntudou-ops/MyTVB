package com.mytvb.feature.category

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.mytvb.model.CategoryModel

class CategoryFragmentAdapter(
    private val fragmentManager: FragmentManager,
    lifecycle: Lifecycle
) : FragmentStateAdapter(fragmentManager, lifecycle) {

    private val categories = mutableListOf<CategoryModel>()

    /** 当前 tab 集合（过滤后），供与设置变更后的期望集合比对。 */
    fun currentCategories(): List<CategoryModel> = categories.toList()

    fun setCategories(list: List<CategoryModel>) {
        categories.clear()
        categories.addAll(list)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = categories.size

    override fun createFragment(position: Int): Fragment {
        val category = categories[position]
        return CategoryListFragment.newInstance(category.id, category.name)
    }

    override fun getItemId(position: Int): Long = categories[position].id.toLong()

    override fun containsItem(itemId: Long): Boolean = categories.any { it.id.toLong() == itemId }

    fun getPageTitle(position: Int): CharSequence? {
        return if (position < categories.size) categories[position].name else null
    }

    fun getCurrentFragment(position: Int): CategoryListFragment? {
        if (position < 0 || position >= categories.size) return null
        val fragmentTag = "f${getItemId(position)}"
        return fragmentManager.findFragmentByTag(fragmentTag) as? CategoryListFragment
    }
}
