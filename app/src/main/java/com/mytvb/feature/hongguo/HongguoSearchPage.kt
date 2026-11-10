package com.mytvb.feature.hongguo

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
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
import com.mytvb.feature.hongguo.net.HongguoSearch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 红果搜索页：输入框（焦点呼出 IME）+ 联想词横排 + 结果网格（统一 B 站卡片）。
 * 数据走 HongguoSearch（search/tab 72232 + 无签名 suggestion）；返回键收起覆盖层。
 */
class HongguoSearchPage : Fragment() {

    private var _binding: FragmentHongguoSearchBinding? = null
    private val binding get() = _binding!!
    private val repository by lazy { HongguoRepository.get(requireContext()) }
    private val suggestAdapter = SuggestAdapter()
    private val resultAdapter = HongguoDramaAdapter(onItemClick = ::openDetail)
    private var tvFocusController: TvListFocusController? = null

    // 结果卡与推荐页同款竖版 4:5，列数同档
    private val spanCount by lazy { resources.adaptiveSpanCount(base = 6, wide = 12) }

    private var suggestJob: Job? = null
    private var searchJob: Job? = null
    private var cursor: HongguoSearch.SearchPage.Cursor? = null
    private var offset = 0
    private var hasMore = false
    private var loading = false
    private var keyword = ""

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHongguoSearchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.recyclerHongguoSuggest.adapter = suggestAdapter
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

        // 焦点呼出 IME（项目搜索页同款：focus→show、失焦→hide）
        val imm = requireContext().getSystemService(InputMethodManager::class.java)
        binding.editHongguoKeyword.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) imm?.showSoftInput(binding.editHongguoKeyword, 0)
            else imm?.hideSoftInputFromWindow(binding.editHongguoKeyword.windowToken, 0)
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
        // 输入停顿 400ms 拉联想
        binding.editHongguoKeyword.doOnTextChanged { text ->
            suggestJob?.cancel()
            val kw = text?.toString()?.trim().orEmpty()
            if (kw.length < 2) {
                suggestAdapter.submit(emptyList())
                return@doOnTextChanged
            }
            suggestJob = viewLifecycleOwner.lifecycleScope.launch {
                delay(400)
                try {
                    suggestAdapter.submit(repository.suggestions(kw))
                } catch (_: CancellationException) {
                } catch (_: Exception) {
                }
            }
        }
        binding.editHongguoKeyword.requestFocus()
    }

    private fun openDetail(item: DramaUi) {
        HongguoDetailDialog.show(requireContext(), repository, item.seriesId)
    }

    private fun doSearch(kwRaw: String) {
        val kw = kwRaw.trim()
        if (kw.isEmpty()) return
        keyword = kw
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
        searchJob?.cancel()
        tvFocusController?.release()
        tvFocusController = null
        super.onDestroyView()
        _binding = null
    }

    // ---- 联想词横排 chip ----
    private inner class SuggestAdapter : RecyclerView.Adapter<SuggestAdapter.Holder>() {
        private var list: List<String> = emptyList()

        fun submit(newList: List<String>) {
            list = newList
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = list.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.cell_hongguo_suggest, parent, false)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(list[position])
        }

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            fun bind(word: String) {
                val text = itemView as? android.widget.TextView ?: return
                text.text = word
                text.setOnClickListener {
                    binding.editHongguoKeyword.setText(word)
                    doSearch(word)
                }
            }
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
