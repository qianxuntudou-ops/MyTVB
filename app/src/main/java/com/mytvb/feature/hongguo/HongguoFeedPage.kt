package com.mytvb.feature.hongguo

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mytvb.R
import com.mytvb.core.ui.base.AppToast
import com.mytvb.core.ui.base.adaptiveSpanCount
import com.mytvb.core.ui.decoration.GridSpacingItemDecoration
import com.mytvb.core.ui.focus.tv.GridTvFocusStrategy
import com.mytvb.core.ui.focus.tv.TvListFocusController
import com.mytvb.core.ui.image.ImageLoader
import com.mytvb.core.ui.layout.WrapContentGridLayoutManager
import com.mytvb.databinding.FragmentHongguoFeedBinding
import com.mytvb.feature.hongguo.net.HongguoClient
import com.mytvb.feature.hongguo.net.HongguoCoverUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope

/**
 * 红果列表页：推荐/历史/收藏/分类共用（榜单是独立横排行页不走本页）。
 * @param mode feed=在线 landpage（推荐/分类）；history/favorite=本地记录
 *
 * 2026-11-10 卡片迁移：自绘 cell_hongguo_drama → 全 App 统一 B 站卡片
 * （[HongguoDramaAdapter]，竖版 4:5，对齐 CCTV 片库网格观感）：
 * 列数统一 [R.dimen] 6 列档（原 feed 4 列/历史 3 列），焦点链 TvListFocusController 收口。
 */
class HongguoFeedPage : Fragment(), HongguoPageFocus {

    private var _binding: FragmentHongguoFeedBinding? = null
    private val binding get() = _binding!!
    private val repository by lazy { HongguoRepository.get(requireContext()) }
    private val adapter = HongguoDramaAdapter(onItemClick = ::openDetail)
    private var tvFocusController: TvListFocusController? = null

    // 竖版 4:5 卡片列数对齐 CCTV 片库（6 列/超宽 12 列）
    private val spanCount by lazy { resources.adaptiveSpanCount(base = 6, wide = 12) }

    private var loadJob: Job? = null
    private var offset = 0
    private var sessionId = ""
    private var hasMore = true
    private var loading = false

    // ---- 筛选面板（selector_rows） ----
    private val filterAdapter = FilterChipAdapter()
    private var selectorRows: List<HongguoClient.SelectorRow> = emptyList()
    private val selectedFilters = HashMap<String, String>()   // type -> selector_item_id

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHongguoFeedBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val mode = requireArguments().getString(ARG_MODE, MODE_FEED)
        val genre = requireArguments().getString(ARG_GENRE, "")
        binding.recyclerFeed.layoutManager = WrapContentGridLayoutManager(requireContext(), spanCount)
        binding.recyclerFeed.adapter = adapter
        binding.recyclerFeed.itemAnimator = null
        binding.recyclerFeed.addItemDecoration(
            GridSpacingItemDecoration(
                spanCount,
                resources.getDimensionPixelSize(R.dimen.px16),
                false
            )
        )
        adapter.bindTopEdgeUp(::focusTopTab)
        adapter.setOnItemFocusListeners(
            onFocused = { position ->
                if (position >= adapter.contentCount() - LOAD_MORE_THRESHOLD) {
                    loadMore(mode, genre)
                }
            },
            onFocusedWithView = { view, position ->
                tvFocusController?.onItemFocused(view, position)
            }
        )
        tvFocusController = TvListFocusController(
            recyclerView = binding.recyclerFeed,
            adapter = adapter,
            strategy = GridTvFocusStrategy { spanCount },
            canLoadMore = { hasMore },
            loadMore = { loadMore(mode, genre) },
            debugName = "hongguo_feed"
        )
        binding.recyclerFilters.adapter = filterAdapter
        binding.recyclerFilters.layoutManager =
            androidx.recyclerview.widget.LinearLayoutManager(
                requireContext(), androidx.recyclerview.widget.RecyclerView.HORIZONTAL, false
            )
        binding.recyclerFeed.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0 || !hasMore || loading) return
                val lm = rv.layoutManager as? GridLayoutManager ?: return
                if (lm.findLastVisibleItemPosition() >= adapter.itemCount - spanCount * 2) {
                    loadMore(mode, genre)
                }
            }
        })
        refresh(mode, genre)
    }

    /** 列表 UP 边缘回红果页顶部 tab 栏（宿主 HongguoFragment）。 */
    private fun focusTopTab(): Boolean {
        val host = parentFragment as? HongguoFragment ?: return false
        return host.focusTopTab()
    }

    /** 侧栏 RIGHT / tab DOWN 的入口：聚焦第一张卡片（TvListFocusController 统一走）。 */
    override fun focusFirstCard(): Boolean {
        if (!isAdded || view == null) return false
        return tvFocusController?.requestFocusPosition(0, allowOutsideFocus = true) == true
    }

    private fun openDetail(item: DramaUi) {
        HongguoDetailDialog.show(requireContext(), repository, item.seriesId)
    }

    private fun refresh(mode: String, genre: String) {
        offset = 0; sessionId = ""; hasMore = true
        adapter.clear()
        adapter.notifyDataSetChanged()
        load(mode, genre, withPanel = selectorRows.isEmpty())
    }

    private fun loadMore(mode: String, genre: String) {
        if (loading || !hasMore) return
        load(mode, genre, withPanel = false)
    }

    private fun load(mode: String, genre: String, withPanel: Boolean) {
        when (mode) {
            MODE_HISTORY -> {
                adapter.setDataDeduplicated(repository.history().map { it.toUi() })
                hasMore = false
                binding.textEmpty.visibility = if (adapter.contentCount() == 0) View.VISIBLE else View.GONE
                return
            }
            MODE_FAVORITE -> {
                adapter.setDataDeduplicated(repository.favorites().map { it.toUi() })
                hasMore = false
                binding.textEmpty.visibility = if (adapter.contentCount() == 0) View.VISIBLE else View.GONE
                return
            }
        }
        val path = when {
            genre == "comic" -> "/category/comic-drama"
            genre == "ai" -> "/category/ai-drama"
            else -> "/category/short-drama"
        }
        loading = true
        binding.progressLoading.visibility = View.VISIBLE
        loadJob?.cancel()
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val page = repository.feed(path, offset, sessionId, selectedFilters, withPanel)
                sessionId = page.sessionId
                hasMore = page.hasMore
                offset = if (page.nextOffset > 0) page.nextOffset else offset + page.items.size
                adapter.addData(page.items.map { it.toUi() })
                binding.textEmpty.visibility = if (adapter.contentCount() == 0) View.VISIBLE else View.GONE
                if (page.selectorRows.isNotEmpty()) {
                    selectorRows = page.selectorRows
                    binding.recyclerFilters.visibility = View.VISIBLE
                    filterAdapter.notifyDataSetChanged()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppToast.show(requireContext(), e.message ?: "加载失败")
                binding.textEmpty.visibility = if (adapter.contentCount() == 0) View.VISIBLE else View.GONE
            } finally {
                loading = false
                binding.progressLoading.visibility = View.GONE
            }
        }
    }

    /** 点选某维度选项：更新选中 → 重开会话（offset=0 + panel）。 */
    private fun applyFilter(row: HongguoClient.SelectorRow, itemId: String) {
        if (itemId.isEmpty()) selectedFilters.remove(row.type)
        else selectedFilters[row.type] = itemId
        filterAdapter.notifyDataSetChanged()
        refresh(requireArguments().getString(ARG_MODE, MODE_FEED), requireArguments().getString(ARG_GENRE, ""))
    }

    // ---- 筛选 chip：每维度一枚，显示当前值，点击弹 ChoiceDialog ----
    inner class FilterChipAdapter : RecyclerView.Adapter<FilterChipAdapter.Holder>() {
        override fun getItemCount(): Int = selectorRows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.cell_hongguo_suggest, parent, false)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(selectorRows[position])
        }

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            fun bind(row: HongguoClient.SelectorRow) {
                val text = itemView as? android.widget.TextView ?: return
                val selectedId = selectedFilters[row.type]
                val selectedName = row.items.firstOrNull { it.id == selectedId }?.name
                    ?: getString(R.string.hongguo_filter_all)
                text.text = "${row.name}·$selectedName"
                text.isSelected = selectedId != null
                text.setOnClickListener {
                    val names = listOf(getString(R.string.hongguo_filter_all)) + row.items.map { it.name }
                    val current = if (selectedId == null) 0
                    else row.items.indexOfFirst { it.id == selectedId } + 1
                    com.mytvb.core.ui.base.ChoiceDialog.show(
                        requireContext(), row.name, names, current
                    ) { index ->
                        applyFilter(row, if (index == 0) "" else row.items[index - 1].id)
                    }
                }
            }
        }
    }

    private fun HongguoClient.Drama.toUi(): DramaUi = DramaUi(
        seriesId = seriesId,
        title = title,
        cover = cover,
        subtitle = listOf(
            score.takeIf { it.isNotBlank() },
            if (episodeCount > 0) "共${episodeCount}集" else null,
            heatText.ifEmpty { if (playCount > 0) "${playCount / 10000}万播放" else null }
        ).filterNotNull().joinToString(" · ")
    )

    private fun HongguoRepository.WatchRecord.toUi(): DramaUi {
        val percent = if (durationMs > 0) (positionMs * 100 / durationMs).toInt() else 0
        return DramaUi(
            seriesId = seriesId,
            title = title,
            // 存量记录可能带着已过期的签名 URL，读出时统一改写救活
            cover = HongguoCoverUrl.rewrite(cover),
            subtitle = when {
                episodeCount <= 0 -> ""
                episodeIndex >= episodeCount -> "已看完"
                else -> "看到${episodeIndex}/${episodeCount}集"
            },
            progressPercent = percent.coerceIn(0, 100)
        )
    }

    override fun onDestroyView() {
        loadJob?.cancel()
        tvFocusController?.release()
        tvFocusController = null
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_MODE = "mode"
        private const val ARG_GENRE = "genre"
        private const val LOAD_MORE_THRESHOLD = 4
        const val MODE_FEED = "feed"
        const val MODE_HISTORY = "history"
        const val MODE_FAVORITE = "favorite"

        fun newInstance(mode: String, genre: String = ""): HongguoFeedPage {
            return HongguoFeedPage().apply {
                arguments = Bundle().apply {
                    putString(ARG_MODE, mode)
                    putString(ARG_GENRE, genre)
                }
            }
        }
    }
}
