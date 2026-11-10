package com.mytvb.feature.hongguo

import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.view.isVisible
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
import com.mytvb.databinding.FragmentHongguoSearchBinding
import com.mytvb.feature.hongguo.net.HongguoClient
import com.mytvb.feature.hongguo.net.HongguoSearch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 红果搜索页三态（对照红果官方搜索输入页）：
 * - 发现态：搜索历史（本地 SP）+ 猜你想搜（plan/v1）+ 短剧/漫剧热搜榜两列；
 * - 联想态：输入 ≥2 字防抖拉 suggestions，竖排推荐词行；
 * - 结果态：B 站统一卡片网格（原有链路不动）。
 * 焦点：首行 UP 回输入框；输入行 DOWN 动态落第一个可见区；BACK 由宿主收层。
 * 数据失败静默隐藏对应区（官方同款降级），不阻塞搜索主链路。
 */
class HongguoSearchPage : Fragment() {

    private enum class Mode { DISCOVER, SUGGEST, RESULT }

    private var _binding: FragmentHongguoSearchBinding? = null
    private val binding get() = _binding!!
    private val repository by lazy { HongguoRepository.get(requireContext()) }
    private val resultAdapter = HongguoDramaAdapter(onItemClick = ::openDetail)
    private var tvFocusController: TvListFocusController? = null

    // 结果卡与推荐页同款竖版 4:5，列数同档
    private val spanCount by lazy { resources.adaptiveSpanCount(base = 6, wide = 12) }

    private val historyAdapter = WordChipAdapter(onClick = { doSearch(it.title) })
    private val guessAdapter = WordChipAdapter(onClick = ::onDiscoveryClick)
    private val shortBoardAdapter = HotBoardAdapter(onClick = ::openDiscoveryDetail)
    private val comicBoardAdapter = HotBoardAdapter(onClick = ::openDiscoveryDetail)
    private val suggestAdapter = SuggestRowAdapter(onClick = { doSearch(it.title) })

    private var mode = Mode.DISCOVER
    private var suggestJob: Job? = null
    private var searchJob: Job? = null
    private var cursor: HongguoSearch.SearchPage.Cursor? = null
    private var offset = 0
    private var hasMore = false
    private var loading = false
    private var keyword = ""
    /** 程序化填词（历史/联想词回填）不触发联想拉取。 */
    private var suppressSuggest = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHongguoSearchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupDiscover()
        setupSuggest()
        setupResult()
        setupInputRow()
        binding.editHongguoKeyword.requestFocus()
        renderHistory()
        loadDiscovery()
    }

    // ---- 发现态 ----

    private fun setupDiscover() {
        binding.recyclerHongguoHistory.adapter = historyAdapter
        binding.recyclerHongguoGuess.adapter = guessAdapter
        binding.recyclerHongguoBoardShort.adapter = shortBoardAdapter
        binding.recyclerHongguoBoardComic.adapter = comicBoardAdapter
        binding.buttonHongguoHistoryClear.setOnClickListener {
            repository.clearSearchHistory()
            renderHistory()
        }
    }

    private fun renderHistory() {
        val history = repository.searchHistory()
        historyAdapter.submit(history.map { HongguoClient.DiscoveryItem(title = it) })
        binding.sectionHongguoHistory.isVisible = history.isNotEmpty()
        updateInputDownTarget()
    }

    /** 猜你想搜+热搜榜（plan/v1）：失败整区隐藏，官方同款降级。 */
    private fun loadDiscovery() {
        viewLifecycleOwner.lifecycleScope.launch {
            val data = repository.searchDiscovery() ?: return@launch
            if (_binding == null) return@launch
            guessAdapter.submit(data.guesses)
            binding.sectionHongguoGuess.isVisible = data.guesses.isNotEmpty()
            val shortBoard = data.boards.firstOrNull { it.id == "short" }
            val comicBoard = data.boards.firstOrNull { it.id == "comic" }
            shortBoardAdapter.submit(shortBoard?.items.orEmpty())
            comicBoardAdapter.submit(comicBoard?.items.orEmpty())
            // 空榜整列隐藏（上游常只下发一个榜，参考实现同口径）
            binding.boardColShort.isVisible = !shortBoard?.items.isNullOrEmpty()
            binding.boardColComic.isVisible = !comicBoard?.items.isNullOrEmpty()
            binding.sectionHongguoBoard.isVisible =
                !shortBoard?.items.isNullOrEmpty() || !comicBoard?.items.isNullOrEmpty()
            // 输入框推荐词：官方打开搜索框即带灰色推荐词，取第一条（猜你想搜优先）
            if (binding.editHongguoKeyword.text.isNullOrEmpty()) {
                val seed = data.guesses.firstOrNull()?.title
                    ?: shortBoard?.items?.firstOrNull()?.title
                    ?: comicBoard?.items?.firstOrNull()?.title
                seed?.takeIf { it.isNotBlank() }?.let { binding.editHongguoKeyword.hint = it }
            }
            updateInputDownTarget()
        }
    }

    /** 发现态 DOWN 的落点：第一个可见发现区（历史→猜你想搜→热搜榜）。 */
    private var discoverDownTargetId = View.NO_ID

    private fun updateInputDownTarget() {
        discoverDownTargetId = when {
            binding.sectionHongguoHistory.isVisible &&
                historyAdapter.itemCount > 0 -> R.id.recycler_hongguo_history
            binding.sectionHongguoGuess.isVisible &&
                guessAdapter.itemCount > 0 -> R.id.recycler_hongguo_guess
            binding.sectionHongguoBoard.isVisible -> R.id.recycler_hongguo_board_short
            else -> View.NO_ID
        }
        if (mode == Mode.DISCOVER) syncInputDownTarget()
    }

    // ---- 联想态 ----

    private fun setupSuggest() {
        binding.recyclerHongguoSuggest.adapter = suggestAdapter
    }

    // ---- 结果态（原有链路） ----

    private fun setupResult() {
        binding.recyclerHongguoResults.layoutManager =
            WrapContentGridLayoutManager(requireContext(), spanCount)
        binding.recyclerHongguoResults.adapter = resultAdapter
        binding.recyclerHongguoResults.itemAnimator = null
        binding.recyclerHongguoResults.addItemDecoration(
            GridSpacingItemDecoration(
                spanCount,
                resources.getDimensionPixelSize(R.dimen.px16),
                false
            )
        )
        resultAdapter.bindTopEdgeUp {
            binding.editHongguoKeyword.requestFocus() || true
        }
        resultAdapter.setOnItemFocusListeners(
            onFocused = { position ->
                if (position >= resultAdapter.contentCount() - LOAD_MORE_THRESHOLD) {
                    loadMore()
                }
            },
            onFocusedWithView = { view, position ->
                tvFocusController?.onItemFocused(view, position)
            }
        )
        tvFocusController = TvListFocusController(
            recyclerView = binding.recyclerHongguoResults,
            adapter = resultAdapter,
            strategy = GridTvFocusStrategy { spanCount },
            canLoadMore = { hasMore },
            loadMore = { loadMore() },
            debugName = "hongguo_search"
        )
        binding.recyclerHongguoResults.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0 || !hasMore || loading) return
                val lm = rv.layoutManager as? GridLayoutManager ?: return
                if (lm.findLastVisibleItemPosition() >= resultAdapter.contentCount() - 8) {
                    loadMore()
                }
            }
        })
    }

    // ---- 输入行 ----

    private fun setupInputRow() {
        val imm = requireContext().getSystemService(InputMethodManager::class.java)
        binding.editHongguoKeyword.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) imm?.showSoftInput(binding.editHongguoKeyword, 0)
            else imm?.hideSoftInputFromWindow(binding.editHongguoKeyword.windowToken, 0)
        }
        // RIGHT 落「搜索」按钮（放行框架会几何逃逸到被覆盖的 tab 行）
        binding.editHongguoKeyword.setOnKeyListener { _, keyCode, event ->
            keyCode == KeyEvent.KEYCODE_DPAD_RIGHT &&
                event.action == KeyEvent.ACTION_DOWN &&
                (binding.buttonHongguoSearchGo.requestFocus() || true)
        }
        binding.editHongguoKeyword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doSearch(binding.editHongguoKeyword.text.toString())
                true
            } else false
        }
        binding.buttonHongguoSearchGo.setOnClickListener {
            doSearch(binding.editHongguoKeyword.text.toString())
        }
        // 输入停顿 400ms 拉联想；清空恒回发现态（结果态下改词/清空也要能回发现页）
        binding.editHongguoKeyword.doOnTextChanged { text ->
            if (suppressSuggest) return@doOnTextChanged
            suggestJob?.cancel()
            val kw = text?.toString()?.trim().orEmpty()
            if (kw.isEmpty()) {
                suggestAdapter.submit(emptyList())
                setMode(Mode.DISCOVER)
                return@doOnTextChanged
            }
            if (kw.length < 2) return@doOnTextChanged
            suggestJob = viewLifecycleOwner.lifecycleScope.launch {
                delay(400)
                try {
                    val words = repository.suggestions(kw)
                    if (_binding == null) return@launch
                    if (words.isNotEmpty()) {
                        suggestAdapter.submit(words)
                        setMode(Mode.SUGGEST)
                    }
                } catch (_: CancellationException) {
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun setMode(newMode: Mode) {
        mode = newMode
        binding.scrollHongguoDiscover.isVisible = newMode == Mode.DISCOVER
        binding.recyclerHongguoSuggest.isVisible = newMode == Mode.SUGGEST
        binding.containerHongguoResult.isVisible = newMode == Mode.RESULT
        syncInputDownTarget()
    }

    /** 输入行 DOWN 的当前落点（随三态切换）：发现区首区 / 联想列表 / 结果网格。 */
    private fun syncInputDownTarget() {
        val target = when (mode) {
            Mode.DISCOVER -> discoverDownTargetId
            Mode.SUGGEST -> R.id.recycler_hongguo_suggest
            Mode.RESULT -> R.id.recycler_hongguo_results
        }
        binding.editHongguoKeyword.nextFocusDownId = target
        binding.buttonHongguoSearchGo.nextFocusDownId = target
    }

    /** 宿主重新打开覆盖层时归还输入框焦点（复用实例时 onViewCreated 不会重跑）。 */
    fun requestInputFocus() {
        binding?.editHongguoKeyword?.requestFocus()
    }

    private fun openDetail(item: DramaUi, position: Int) {
        HongguoDetailDialog.show(requireContext(), repository, item.seriesId)
    }

    /** 发现/联想条目统一入口：带剧 ID 的弹详情，纯词直接搜索。 */
    private fun onDiscoveryClick(item: HongguoClient.DiscoveryItem) {
        if (item.seriesId.isNotEmpty()) openDiscoveryDetail(item) else doSearch(item.title)
    }

    private fun openDiscoveryDetail(item: HongguoClient.DiscoveryItem) {
        if (item.seriesId.isEmpty()) {
            doSearch(item.title)
            return
        }
        HongguoDetailDialog.show(requireContext(), repository, item.seriesId)
    }

    private fun doSearch(kwRaw: String) {
        val kw = kwRaw.trim()
        if (kw.isEmpty()) return
        keyword = kw
        suppressSuggest = true
        binding.editHongguoKeyword.setText(kw)
        suppressSuggest = false
        setMode(Mode.RESULT)
        repository.saveSearchKeyword(kw)
        renderHistory()
        cursor = null
        offset = 0
        hasMore = false
        resultAdapter.clear()
        resultAdapter.notifyDataSetChanged()
        binding.textHongguoSearchEmpty.visibility = View.GONE
        binding.progressHongguoSearch.visibility = View.VISIBLE
        val imm = requireContext().getSystemService(InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(binding.editHongguoKeyword.windowToken, 0)
        searchJob?.cancel()
        searchJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val page = repository.search(kw, 0, null)
                cursor = page.cursor
                hasMore = page.hasMore
                offset = page.cursor?.nextOffset ?: page.items.size
                resultAdapter.setDataDeduplicated(page.items.map { it.toUi() })
                binding.textHongguoSearchEmpty.visibility =
                    if (resultAdapter.contentCount() == 0) View.VISIBLE else View.GONE
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppToast.show(requireContext(), e.message ?: "搜索失败")
            } finally {
                binding.progressHongguoSearch.visibility = View.GONE
            }
        }
    }

    private fun loadMore() {
        if (loading || !hasMore || keyword.isEmpty()) return
        loading = true
        searchJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val page = repository.search(keyword, offset, cursor)
                cursor = page.cursor
                hasMore = page.hasMore
                offset = page.cursor?.nextOffset ?: (offset + page.items.size)
                resultAdapter.addData(page.items.map { it.toUi() })
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                hasMore = false
            } finally {
                loading = false
            }
        }
    }

    private fun HongguoSearch.SearchResult.toUi(): DramaUi = DramaUi(
        seriesId = seriesId,
        title = title,
        cover = cover,
        subtitle = listOf(
            if (episodeCount > 0) "共${episodeCount}集" else null,
            when {
                playCount >= 10_000 -> "${playCount / 10000}万播放"
                playCount > 0 -> "${playCount}播放"
                else -> null
            }
        ).filterNotNull().joinToString(" · ")
    )

    override fun onDestroyView() {
        suggestJob?.cancel()
        searchJob?.cancel()
        tvFocusController?.release()
        tvFocusController = null
        super.onDestroyView()
        _binding = null
    }

    /** 首行 UP 回输入框的小工具：发现区/联想行共用（网格结果走 TvListFocusController）。 */
    private fun View.bindTopUpToInput(position: Int) {
        setOnKeyListener { _, keyCode, event ->
            keyCode == KeyEvent.KEYCODE_DPAD_UP &&
                event.action == KeyEvent.ACTION_DOWN &&
                position == 0 &&
                (binding.editHongguoKeyword.requestFocus() || true)
        }
    }

    // ---- 词 chip（历史/猜你想搜横排；复用筛选 chip 同款 cell） ----
    private inner class WordChipAdapter(
        private val onClick: (HongguoClient.DiscoveryItem) -> Unit
    ) : RecyclerView.Adapter<WordChipAdapter.Holder>() {
        private var list: List<HongguoClient.DiscoveryItem> = emptyList()

        fun submit(items: List<HongguoClient.DiscoveryItem>) {
            list = items
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = list.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.cell_hongguo_suggest, parent, false)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(list[position], position)
        }

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            fun bind(item: HongguoClient.DiscoveryItem, position: Int) {
                val text = itemView as? android.widget.TextView ?: return
                text.text = item.title
                text.setOnClickListener { onClick(item) }
                text.bindTopUpToInput(position)
            }
        }
    }

    // ---- 热搜榜行（排名+剧名+角标，整行高亮） ----
    private inner class HotBoardAdapter(
        private val onClick: (HongguoClient.DiscoveryItem) -> Unit
    ) : RecyclerView.Adapter<HotBoardAdapter.Holder>() {
        private var list: List<HongguoClient.DiscoveryItem> = emptyList()

        fun submit(items: List<HongguoClient.DiscoveryItem>) {
            list = items
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = list.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.cell_hongguo_hot, parent, false)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(list[position], position)
        }

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            private val rank: android.widget.TextView = view.findViewById(R.id.text_hongguo_hot_rank)
            private val title: android.widget.TextView = view.findViewById(R.id.text_hongguo_hot_title)
            private val tip: android.widget.TextView = view.findViewById(R.id.text_hongguo_hot_tip)

            fun bind(item: HongguoClient.DiscoveryItem, position: Int) {
                val index = position + 1
                rank.text = index.toString()
                // 前三名橙（红果品牌色），其余灰
                rank.setTextColor(
                    if (index <= 3) 0xFFFF5C1F.toInt() else requireContext().getColor(R.color.grey)
                )
                title.text = item.title
                tip.text = item.tip
                tip.isVisible = item.tip.isNotBlank()
                itemView.setOnClickListener { onClick(item) }
                itemView.bindTopUpToInput(position)
            }
        }
    }

    // ---- 联想行（放大镜+词，竖排整行） ----
    private inner class SuggestRowAdapter(
        private val onClick: (HongguoClient.DiscoveryItem) -> Unit
    ) : RecyclerView.Adapter<SuggestRowAdapter.Holder>() {
        private var list: List<String> = emptyList()

        fun submit(words: List<String>) {
            list = words
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = list.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.cell_hongguo_suggest_row, parent, false)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val word = list[position]
            holder.text.text = word
            holder.itemView.setOnClickListener {
                onClick(HongguoClient.DiscoveryItem(title = word))
            }
            holder.itemView.bindTopUpToInput(position)
        }

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val text: android.widget.TextView =
                view.findViewById(R.id.text_hongguo_suggest_word)
        }
    }

    companion object {
        private const val LOAD_MORE_THRESHOLD = 4

        fun newInstance(): HongguoSearchPage = HongguoSearchPage()
    }
}

/** 文本变化回调（避免 API 层级差异，内部小工具）。 */
private fun android.widget.EditText.doOnTextChanged(block: (CharSequence?) -> Unit) {
    addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: android.text.Editable?) = block(s)
    })
}
