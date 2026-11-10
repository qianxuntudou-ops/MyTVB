package com.mytvb.feature.hongguo

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mytvb.R
import com.mytvb.core.ui.image.ImageLoader
import com.mytvb.core.ui.base.AppToast
import com.mytvb.databinding.CellHongguoDramaBinding
import com.mytvb.databinding.FragmentHongguoFeedBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope

/**
 * 红果列表页：推荐/历史/收藏/榜单/分类共用。
 * @param mode feed=在线 landpage（推荐/分类）；history/favorite=本地记录；rank=feed 热度榜近似
 */
class HongguoFeedPage : Fragment() {

    private var _binding: FragmentHongguoFeedBinding? = null
    private val binding get() = _binding!!
    private val repository by lazy { HongguoRepository.get(requireContext()) }
    private val adapter = DramaAdapter()

    private var loadJob: Job? = null
    private var offset = 0
    private var sessionId = ""
    private var hasMore = true
    private var loading = false

    // ---- 筛选面板（selector_rows） ----
    private val filterAdapter = FilterChipAdapter()
    private var selectorRows: List<com.mytvb.feature.hongguo.net.HongguoClient.SelectorRow> = emptyList()
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
        val span = if (mode == MODE_HISTORY || mode == MODE_FAVORITE) 3 else 4
        binding.recyclerFeed.layoutManager = GridLayoutManager(requireContext(), span)
        binding.recyclerFeed.adapter = adapter
        binding.recyclerFilters.adapter = filterAdapter
        binding.recyclerFilters.layoutManager =
            androidx.recyclerview.widget.LinearLayoutManager(
                requireContext(), androidx.recyclerview.widget.RecyclerView.HORIZONTAL, false
            )
        binding.recyclerFeed.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0 || !hasMore || loading) return
                val lm = rv.layoutManager as? GridLayoutManager ?: return
                if (lm.findLastVisibleItemPosition() >= adapter.itemCount - span * 2) {
                    loadMore(mode, genre)
                }
            }
        })
        refresh(mode, genre)
    }

    private fun refresh(mode: String, genre: String) {
        offset = 0; sessionId = ""; hasMore = true
        adapter.submit(null)
        load(mode, genre, withPanel = selectorRows.isEmpty())
    }

    private fun loadMore(mode: String, genre: String) = load(mode, genre, withPanel = false)

    private fun load(mode: String, genre: String, withPanel: Boolean) {
        when (mode) {
            MODE_HISTORY -> {
                adapter.submit(repository.history().map { it.toUi() })
                hasMore = false
                binding.textEmpty.visibility = if (adapter.itemCount == 0) View.VISIBLE else View.GONE
                return
            }
            MODE_FAVORITE -> {
                adapter.submit(repository.favorites().map { it.toUi() })
                hasMore = false
                binding.textEmpty.visibility = if (adapter.itemCount == 0) View.VISIBLE else View.GONE
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
                val list = (adapter.currentList() ?: emptyList()) + page.items.map { it.toUi() }
                adapter.submit(list)
                binding.textEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                if (page.selectorRows.isNotEmpty()) {
                    selectorRows = page.selectorRows
                    binding.recyclerFilters.visibility = View.VISIBLE
                    filterAdapter.notifyDataSetChanged()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppToast.show(requireContext(), e.message ?: "加载失败")
                binding.textEmpty.visibility = if (adapter.itemCount == 0) View.VISIBLE else View.GONE
            } finally {
                loading = false
                binding.progressLoading.visibility = View.GONE
            }
        }
    }

    /** 点选某维度选项：更新选中 → 重开会话（offset=0 + panel）。 */
    private fun applyFilter(row: com.mytvb.feature.hongguo.net.HongguoClient.SelectorRow, itemId: String) {
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
            fun bind(row: com.mytvb.feature.hongguo.net.HongguoClient.SelectorRow) {
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

    private fun com.mytvb.feature.hongguo.net.HongguoClient.Drama.toUi(): DramaUi = DramaUi(
        seriesId = seriesId, title = title, cover = cover,
        heat = heatText.ifEmpty { if (playCount > 0) "${playCount / 10000}万播放" else "" },
        episodes = if (episodeCount > 0) "共${episodeCount}集" else "",
        tags = tags.joinToString(" · "),
        episodeCount = episodeCount
    )

    private fun HongguoRepository.WatchRecord.toUi(): DramaUi = DramaUi(
        seriesId = seriesId, title = title, cover = cover,
        heat = if (durationMs > 0) "${positionMs * 100 / durationMs}%" else "",
        episodes = if (episodeCount > 0) {
            if (episodeIndex >= episodeCount) "已看完" else "看到${episodeIndex}/${episodeCount}集"
        } else "",
        tags = "", episodeCount = episodeCount
    )

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ---- adapter ----
    data class DramaUi(
        val seriesId: String, val title: String, val cover: String,
        val heat: String, val episodes: String, val tags: String, val episodeCount: Int
    )

    inner class DramaAdapter : RecyclerView.Adapter<DramaAdapter.Holder>() {
        private var list: List<DramaUi> = emptyList()

        fun submit(newList: List<DramaUi>?) {
            list = newList ?: emptyList()
            notifyDataSetChanged()
        }

        fun currentList(): List<DramaUi>? = list

        override fun getItemCount(): Int = list.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val binding = CellHongguoDramaBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return Holder(binding)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = list[position]
            holder.bind(item)
        }

        inner class Holder(private val cell: CellHongguoDramaBinding) :
            RecyclerView.ViewHolder(cell.root) {

            fun bind(item: DramaUi) {
                cell.textTitle.text = item.title
                cell.textHeat.text = item.heat
                cell.textEpisodes.text = item.episodes
                cell.textTags.text = item.tags
                cell.textBadgeSeason.visibility = View.GONE
                ImageLoader.load(
                    imageView = cell.imageCover,
                    url = item.cover,
                    placeholder = R.drawable.bg_cover_meta_gradient,
                    error = R.drawable.bg_cover_meta_gradient
                )
                cell.root.setOnClickListener {
                    HongguoDetailDialog.show(cell.root.context, repository, item.seriesId)
                }
            }
        }
    }

    companion object {
        private const val ARG_MODE = "mode"
        private const val ARG_GENRE = "genre"
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
