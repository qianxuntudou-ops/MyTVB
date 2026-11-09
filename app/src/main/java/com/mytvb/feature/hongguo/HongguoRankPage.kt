package com.mytvb.feature.hongguo

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mytvb.R
import com.mytvb.core.ui.base.AppToast
import com.mytvb.core.ui.image.ImageLoader
import com.mytvb.databinding.CellHongguoRankBinding
import com.mytvb.databinding.FragmentHongguoFeedBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 红果榜单页：landpage 热度序（综合榜近似；四板切换 P2 接官方 rank 网页路由）。 */
class HongguoRankPage : Fragment() {

    private var _binding: FragmentHongguoFeedBinding? = null
    private val binding get() = _binding!!
    private val repository by lazy { HongguoRepository.get(requireContext()) }
    private val adapter = RankAdapter()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHongguoFeedBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.recyclerFeed.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerFeed.adapter = adapter
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val page = repository.feed("/category/short-drama", 0, "")
                adapter.items = page.items
                adapter.notifyDataSetChanged()
                binding.textEmpty.visibility =
                    if (page.items.isEmpty()) View.VISIBLE else View.GONE
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppToast.show(requireContext(), e.message ?: "榜单加载失败")
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    inner class RankAdapter : RecyclerView.Adapter<RankAdapter.Holder>() {
        var items: List<com.mytvb.feature.hongguo.net.HongguoClient.Drama> = emptyList()

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            return Holder(
                CellHongguoRankBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            )
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(items[position], position + 1)
        }

        inner class Holder(private val cell: CellHongguoRankBinding) :
            RecyclerView.ViewHolder(cell.root) {
            fun bind(item: com.mytvb.feature.hongguo.net.HongguoClient.Drama, rank: Int) {
                cell.textRank.text = "$rank"
                cell.textRank.setTextColor(
                    when (rank) {
                        1 -> 0xFFFF5C1F.toInt()
                        2 -> 0xFFC9A86A.toInt()
                        3 -> 0xFF9AA8B8.toInt()
                        else -> 0xFF9A9AA6.toInt()
                    }
                )
                cell.textTitle.text = item.title
                cell.textMeta.text = item.tags.joinToString(" · ")
                cell.textHeat.text = item.heatText
                ImageLoader.load(cell.imageCover, item.cover)
                cell.root.setOnClickListener {
                    HongguoDetailDialog.show(cell.root.context, repository, item.seriesId)
                }
            }
        }
    }

    companion object {
        fun newInstance(): HongguoRankPage = HongguoRankPage()
    }
}
