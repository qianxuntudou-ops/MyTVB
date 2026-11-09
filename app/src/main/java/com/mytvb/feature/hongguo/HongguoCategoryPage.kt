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
import com.mytvb.databinding.FragmentHongguoCategoryBinding

/** 红果分类页：全部/漫剧/AI剧 三子档（landpage genre 切换）。 */
class HongguoCategoryPage : Fragment() {

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
                HongguoFeedPage.newInstance(HongguoFeedPage.MODE_FEED, genres[position].second)
        }
        binding.viewPager.isUserInputEnabled = false
        TabLayoutMediator(binding.tabGenre, binding.viewPager) { tab, position ->
            tab.text = genres[position].first
        }.attach()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance(): HongguoCategoryPage = HongguoCategoryPage()
    }
}
