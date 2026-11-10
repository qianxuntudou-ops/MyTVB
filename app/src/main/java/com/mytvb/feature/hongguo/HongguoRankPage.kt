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
import com.mytvb.databinding.FragmentHongguoRankBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 红果四板官方榜单（bookmall cell/change）：热播榜/真人榜/漫剧榜/AI剧榜。
 * 子板 tab 切换重开会话；滚动加载；rank 行大卡排名号（金银铜高亮）。
 */
class HongguoRankPage : Fragment() {

    private var _binding: FragmentHongguoRankBinding? = null
    private val binding get() = _binding!!
    private val repository by lazy { HongguoRepository.get(requireContext()) }
    private val adapter = RankAdapter()

    private var board = 0
    private var offset = 0
    private var sessionId = ""
    private var hasMore = false
    private var loading = false
    private var loadJob: Job? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHongguoRankBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.recyclerRank.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerRank.adapter = adapter
        binding.recyclerRank.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0 || !hasMore || loading) return
                val lm = rv.layoutManager as? LinearLayoutManager ?: return
                if (lm.findLastVisibleItemPosition() >= adapter.itemCount - 4) load()
            }
        })
        binding.tabRankBoards.setOnCheckedChangeListener { _, checkedId ->
            val index = when (checkedId) {
                R.id.radio_rank_real -> 1
                R.id.radio_rank_comic -> 2
                R.id.radio_rank_ai -> 3
                else -> 0
            }
            if (index != board) {
                board = index
                refresh()
            }
        }
        refresh()
    }

    private fun refresh() {
        offset = 0; sessionId = ""; hasMore = false
        adapter.items = emptyList()
        adapter.notifyDataSetChanged()
        binding.textRankEmpty.visibility = View.GONE
        load()
    }

    private fun load() {
        loading = true
        binding.progressRank.visibility = View.VISIBLE
        loadJob?.cancel()
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val page = repository.rank(board, offset, sessionId)
                sessionId = page.sessionId
                hasMore = page.hasMore
                offset = page.nextOffset
                val list = adapter.items + page.items
                adapter.items = list
                adapter.notifyDataSetChanged()
                binding.textRankEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppToast.show(requireContext(), e.message ?: "榜单加载失败")
                binding.textRankEmpty.visibility = if (adapter.items.isEmpty()) View.VISIBLE else View.GONE
            } finally {
                loading = false
                binding.progressRank.visibility = View.GONE
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
