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
import com.mytvb.core.common.log.AppLog
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

    private val historyAdapter = WordChipAdapter(
        currentRecyclerId = R.id.recycler_hongguo_history,
        onClick = { doSearch(it.title) }
    )
    private val guessAdapter = WordChipAdapter(
        currentRecyclerId = R.id.recycler_hongguo_guess,
        onClick = ::onDiscoveryClick
    )
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
        // RV 自身不可聚焦（同 HongguoFeedPage：构造器默认 focusable，拿焦无高亮，
        // 榜单行 RIGHT 放行时邻列 RV 自身会以更近距离赢过首行）
        binding.recyclerHongguoHistory.isFocusable = false
        binding.recyclerHongguoGuess.isFocusable = false
        binding.recyclerHongguoBoardShort.isFocusable = false
        binding.recyclerHongguoBoardComic.isFocusable = false
        binding.buttonHongguoHistoryClear.setOnClickListener {
            repository.clearSearchHistory()
            renderHistory()
            // 清除按钮随历史区一起 GONE，不主动归还焦点会被清焦兜底拉去侧栏
            binding.editHongguoKeyword.requestFocus()
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
            // 空榜列整列 GONE（上游常只下发一个榜），必须挑可见的那列，
            // 恒指 board_short 会在"只有漫画榜"时指向 GONE 目标必失败
            binding.boardColShort.isVisible -> R.id.recycler_hongguo_board_short
            binding.boardColComic.isVisible -> R.id.recycler_hongguo_board_comic
            else -> View.NO_ID
        }
        if (mode == Mode.DISCOVER) syncInputDownTarget()
    }

    // ---- 联想态 ----

    private fun setupSuggest() {
        binding.recyclerHongguoSuggest.adapter = suggestAdapter
        binding.recyclerHongguoSuggest.isFocusable = false
    }

    // ---- 结果态（原有链路） ----

    private fun setupResult() {
        binding.recyclerHongguoResults.layoutManager =
            WrapContentGridLayoutManager(requireContext(), spanCount)
        binding.recyclerHongguoResults.adapter = resultAdapter
        binding.recyclerHongguoResults.isFocusable = false
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
        // RIGHT 落「搜索」按钮（放行框架会几何逃逸到被覆盖的 tab 行）；
        // LEFT 落「退出」按钮——放行会被 EditText 当光标左移吞掉（TV 上光标
        // 移动无意义，方向键是导航语义，对齐主搜索页 DPAD_LEFT 显式落点）；
        // DOWN 显式落焦：EditText 会吞 SOURCE_KEYBOARD 来源的方向键
        // （TextView.doKeyDown 防键盘用户方向键跑焦；MuMu 键盘/adb 注入正是此来源），
        // nextFocusDownId 在这些设备上永远不会被框架执行，必须像主搜索页一样自己落
        binding.editHongguoKeyword.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode in 19..22) {
                AppLog.d(
                    "HgSearch",
                    "input onKey code=$keyCode source=${event.source} inTouchMode=${binding.root.isInTouchMode}"
                )
            }
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT ->
                    binding.buttonHongguoSearchExit.requestFocus() || true
                KeyEvent.KEYCODE_DPAD_RIGHT ->
                    binding.buttonHongguoSearchGo.requestFocus() || true
                KeyEvent.KEYCODE_DPAD_DOWN -> focusInputDownTarget()
                else -> false
            }
        }
        binding.editHongguoKeyword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doSearch(currentKeyword())
                true
            } else false
        }
        binding.buttonHongguoSearchGo.setOnClickListener {
            doSearch(currentKeyword())
        }
        // 左端退出钮：收起搜索覆盖层（等价 BACK 收层），焦点还原由宿主处理
        binding.buttonHongguoSearchExit.setOnClickListener {
            (parentFragment as? HongguoFragment)?.hideSearchOverlay()
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
        binding.buttonHongguoSearchExit.nextFocusDownId = target
    }

    /** 输入框 DOWN 的显式落焦：焦点直接进目标列表第一项（遥控走 nextFocusDownId 时
     *  焦点会先落在 RecyclerView 自身再由 AFTER_DESCENDANTS 转发，无高亮反馈）。
     *  候选链逐区尝试（可见且有子项），全部落空时吞键返回 true——放行框架会被
     *  EditText 吞掉 SOURCE_KEYBOARD 方向键（MuMu 键盘/adb 注入即此来源），
     *  与其无效不如保持焦点，数据到位后下一次 DOWN 即可落焦。 */
    private fun focusInputDownTarget(): Boolean {
        val candidates = when (mode) {
            Mode.DISCOVER -> listOf(
                binding.recyclerHongguoHistory,
                binding.recyclerHongguoGuess,
                binding.recyclerHongguoBoardShort,
                binding.recyclerHongguoBoardComic
            )
            Mode.SUGGEST -> listOf(binding.recyclerHongguoSuggest)
            Mode.RESULT -> listOf(binding.recyclerHongguoResults)
        }.filter { it.isShown && it.childCount > 0 }
        for (target in candidates) {
            if (target.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() == true ||
                target.getChildAt(0)?.requestFocus() == true
            ) {
                AppLog.d("HgSearch", "downTarget id=${target.id} children=${target.childCount} result=true")
                return true
            }
        }
        AppLog.d(
            "HgSearch",
            "downTarget mode=$mode empty=true children=${candidates.joinToString { "${it.id}:${it.childCount}" }}"
        )
        return true
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

    /** 当前搜索词：输入为空回退灰色推荐词（hint），官方同款——空输入点搜索直接搜推荐词。 */
    private fun currentKeyword(): String {
        val typed = binding.editHongguoKeyword.text?.toString()?.trim().orEmpty()
        if (typed.isNotEmpty()) return typed
        return binding.editHongguoKeyword.hint?.toString()?.trim().orEmpty()
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

    /** 首行 UP 回输入框；DOWN 显式接管（发现区/联想行共用）：
     *  框架 FocusFinder 评分里，左侧栏按钮纵向铺满全高、总有按钮贴着焦点底边
     *  (纵向距离 0)，beam 规则对垂直方向优势有限，放行 DOWN 必被侧栏以零距离
     *  抢走——只能像输入框一样显式落焦。 */
    private fun View.bindSectionEdgeKeys(position: Int, onDown: () -> Boolean) {
        setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP ->
                    position == 0 && (binding.editHongguoKeyword.requestFocus() || true)
                KeyEvent.KEYCODE_DPAD_DOWN -> onDown()
                else -> false
            }
        }
    }

    /** id 顺序表中 currentId 之后的第一个可见非空区，落其首行。 */
    private fun focusSectionBelow(currentId: Int): Boolean {
        val order = listOf(
            R.id.recycler_hongguo_history,
            R.id.recycler_hongguo_guess,
            R.id.recycler_hongguo_board_short,
            R.id.recycler_hongguo_board_comic
        )
        val start = order.indexOf(currentId)
        if (start < 0) return false
        for (id in order.subList(start + 1, order.size)) {
            val rv = binding.root.findViewById<RecyclerView>(id)
            // isShown：自身+全部祖先可见（isVisible 不查祖先，GONE 的 section
            // 里 RV flag 仍 VISIBLE，会混进候选链把焦点送上不可见 child）
            if (rv != null && rv.isShown && rv.childCount > 0) {
                if (rv.getChildAt(0).requestFocus()) return true
            }
        }
        return false
    }

    // ---- 词 chip（历史/猜你想搜横排；复用筛选 chip 同款 cell） ----
    private inner class WordChipAdapter(
        private val currentRecyclerId: Int,
        private val onClick: (HongguoClient.DiscoveryItem) -> Unit
    ) : RecyclerView.Adapter<WordChipAdapter.Holder>() {
        private var list: List<HongguoClient.DiscoveryItem> = emptyList()

        fun submit(items: List<HongguoClient.DiscoveryItem>) {
            list = items
            notifyDataSetChanged()
        }

        override fun getItemCount() = list.size

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
                text.bindSectionEdgeKeys(position) {
                    focusSectionBelow(currentRecyclerId)
                    true // 恒吞：无下一区时放行会被侧栏以零距离抢走
                }
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
                // 非尾行 DOWN 放行给 RV 内部导航；尾行吞（放行必逃侧栏）
                itemView.bindSectionEdgeKeys(position) { position >= itemCount - 1 }
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
            holder.itemView.bindSectionEdgeKeys(position) { position >= itemCount - 1 }
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
