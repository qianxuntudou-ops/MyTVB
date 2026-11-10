package com.mytvb.feature.hongguo

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mytvb.R
import com.mytvb.core.ui.base.AppToast
import com.mytvb.core.ui.base.adaptiveSpanCount
import com.mytvb.core.ui.decoration.GridSpacingItemDecoration
import com.mytvb.core.ui.focus.tv.GridTvFocusStrategy
import com.mytvb.core.ui.focus.tv.TvListFocusController
import com.mytvb.core.ui.layout.WrapContentGridLayoutManager
import com.mytvb.databinding.FragmentHongguoRankBinding
import com.mytvb.feature.hongguo.net.HongguoClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 红果四板官方榜单（bookmall cell/change）：热播榜/真人榜/漫剧榜/AI剧榜。
 * 子板 tab 切换重开会话；滚动加载。
 * 2026-11-10 卡片迁移：横排行卡（cell_hongguo_rank）→ 统一竖版卡 + 排名角标
 * （金银铜配色，角标走 coverMetaOverlay.typeBadge，与推荐页同款观感与焦点视觉）。
 */
class HongguoRankPage : Fragment(), HongguoPageFocus {

    private var _binding: FragmentHongguoRankBinding? = null
    private val binding get() = _binding!!
    private val repository by lazy { HongguoRepository.get(requireContext()) }
    private val adapter = HongguoDramaAdapter(onItemClick = ::openDetail)
    private var tvFocusController: TvListFocusController? = null

    private val spanCount by lazy { resources.adaptiveSpanCount(base = 6, wide = 12) }

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
        binding.recyclerRank.layoutManager = WrapContentGridLayoutManager(requireContext(), spanCount)
        binding.recyclerRank.adapter = adapter
        binding.recyclerRank.itemAnimator = null
        binding.recyclerRank.addItemDecoration(
            GridSpacingItemDecoration(
                spanCount,
                resources.getDimensionPixelSize(R.dimen.px16),
                false
            )
        )
        adapter.bindTopEdgeUp { focusTopTab() }
        adapter.setOnItemFocusListeners(
            onFocused = { position ->
                if (position >= adapter.contentCount() - LOAD_MORE_THRESHOLD) load()
            },
            onFocusedWithView = { view, position ->
                tvFocusController?.onItemFocused(view, position)
            }
        )
        tvFocusController = TvListFocusController(
            recyclerView = binding.recyclerRank,
            adapter = adapter,
            strategy = GridTvFocusStrategy { spanCount },
            canLoadMore = { hasMore },
            loadMore = { load() },
            debugName = "hongguo_rank"
        )
        binding.recyclerRank.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0 || !hasMore || loading) return
                val lm = rv.layoutManager as? GridLayoutManager ?: return
                if (lm.findLastVisibleItemPosition() >= adapter.contentCount() - LOAD_MORE_THRESHOLD) load()
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

    private fun focusTopTab(): Boolean {
        val host = parentFragment as? HongguoFragment ?: return false
        return host.focusTopTab()
    }

    override fun focusFirstCard(): Boolean {
        if (!isAdded || view == null) return false
        return tvFocusController?.requestFocusPosition(0, allowOutsideFocus = true) == true
    }

    private fun openDetail(item: DramaUi, position: Int) {
        HongguoDetailDialog.show(requireContext(), repository, item.seriesId)
    }

    private fun refresh() {
        offset = 0; sessionId = ""; hasMore = false
        adapter.clear()
        adapter.notifyDataSetChanged()
        load()
    }

    private fun load() {
        if (loading) return
        loading = true
        binding.progressRank.visibility = View.VISIBLE
        loadJob?.cancel()
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val page = repository.rank(board, offset, sessionId)
                sessionId = page.sessionId
                hasMore = page.hasMore
                offset = page.nextOffset
                // 全局名次 = 已有条数 + 批次内偏移 + 1
                val baseRank = adapter.contentCount()
                adapter.addData(page.items.mapIndexed { index, drama ->
                    drama.toUi(baseRank + index + 1)
                })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppToast.show(requireContext(), e.message ?: "榜单加载失败")
            } finally {
                loading = false
                binding.progressRank.visibility = View.GONE
            }
        }
    }

    private fun HongguoClient.Drama.toUi(rank: Int): DramaUi = DramaUi(
        seriesId = seriesId,
        title = title,
        cover = cover,
        // 卡片下方类型行（家庭 · 家庭温情 · 治愈…，榜单来自 category_schema），对齐红果官方榜单卡
        subtitle = tags.joinToString(" · "),
        rank = rank,
        statusTag = statusTag.ifEmpty { statusTags.firstOrNull().orEmpty() },
        heatText = heatText.ifEmpty { if (playCount > 0) "${playCount / 10000}万" else "" },
        episodeCount = episodeCount,
        seasonText = if (multiSeason && seasonIndex > 0) "第${seasonIndex}季" else ""
    )

    override fun onDestroyView() {
        loadJob?.cancel()
        tvFocusController?.release()
        tvFocusController = null
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val LOAD_MORE_THRESHOLD = 4

        fun newInstance(): HongguoRankPage = HongguoRankPage()
    }
}
