package com.tutu.myblbl.feature.search

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup

import androidx.core.view.isVisible
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tutu.myblbl.R
import com.tutu.myblbl.databinding.PageSearchResultBinding
import com.tutu.myblbl.model.search.SearchCategoryItem
import com.tutu.myblbl.model.search.SearchItemModel
import com.tutu.myblbl.model.search.SearchType
import com.tutu.myblbl.core.ui.base.BaseListFragment
import com.tutu.myblbl.core.ui.layout.WrapContentGridLayoutManager
import com.tutu.myblbl.core.common.content.ContentFilter
import com.tutu.myblbl.core.ui.focus.TabContentFocusHelper
import com.tutu.myblbl.core.ui.decoration.GridSpacingItemDecoration
import com.tutu.myblbl.core.ui.focus.tv.GridTvFocusStrategy
import com.tutu.myblbl.core.ui.focus.tv.TvDataChangeReason
import com.tutu.myblbl.core.ui.focus.tv.TvListFocusController

class SearchResultPagerAdapter(
    private val onItemClick: (SearchResultEntry) -> Unit,
    private val onLoadMore: (SearchType) -> Unit,
    private val onTopEdgeUp: ((View) -> Boolean)? = null
) : RecyclerView.Adapter<SearchResultPagerAdapter.ViewHolder>() {

    private val holders = mutableMapOf<SearchType, ViewHolder>()
    private val pendingStates = mutableMapOf<SearchType, PendingState>()
    private val pages = mutableListOf<SearchResultPage>()

    private data class PendingState(
        val items: List<SearchItemModel>,
        val loading: Boolean,
        val hasMore: Boolean
    )

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = PageSearchResultBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val page = pages[position]
        holders[page.type] = holder
        holder.bind(page)
    }

    override fun getItemCount(): Int = pages.size

    override fun onViewRecycled(holder: ViewHolder) {
        holder.release()
        holders.entries.removeAll { it.value == holder }
        super.onViewRecycled(holder)
    }

    fun getPageTitle(position: Int): String = pages.getOrNull(position)?.title.orEmpty()

    fun getPageType(position: Int): SearchType? = pages.getOrNull(position)?.type

    fun setPages(
        categories: List<SearchCategoryItem>,
        initialItems: Map<SearchType, List<SearchItemModel>> = emptyMap(),
        initialLoading: Map<SearchType, Boolean> = emptyMap(),
        initialHasMore: Map<SearchType, Boolean> = emptyMap()
    ) {
        val existing = pages.associateBy { it.type }
        val newPages = categories.map { category ->
            val existingItems = existing[category.type]?.items
            val items = initialItems[category.type]?.toMutableList()
                ?: existingItems
                ?: mutableListOf()
            SearchResultPage(
                type = category.type,
                title = category.showText,
                items = items,
                loading = initialLoading[category.type] ?: false,
                hasMore = initialHasMore[category.type] ?: true
            )
        }
        holders.clear()
        pages.clear()
        pages.addAll(newPages)
        for (page in pages) {
            val state = pendingStates.remove(page.type)
            if (state != null) {
                applyStateToPage(page, state)
            }
        }
        notifyDataSetChanged()
    }

    fun clearResults() {
        pendingStates.clear()
        pages.forEach { page ->
            page.items.clear()
            page.loading = false
        }
        holders.values.forEach { holder ->
            holder.submitEmpty()
        }
    }

    fun submitResults(type: SearchType, items: List<SearchItemModel>) {
        val page = pages.firstOrNull { it.type == type } ?: return
        page.items.clear()
        page.items.addAll(items)
        holders[type]?.submit(page) ?: notifyItemChanged(pages.indexOf(page))
    }

    fun submitState(type: SearchType, items: List<SearchItemModel>, loading: Boolean, hasMore: Boolean) {
        val state = PendingState(items, loading, hasMore)
        pendingStates[type] = state
        val page = pages.firstOrNull { it.type == type }
        if (page != null) {
            applyStateToPage(page, state)
            holders[type]?.submit(page) ?: notifyItemChanged(pages.indexOf(page))
        }
    }

    private fun applyStateToPage(page: SearchResultPage, state: PendingState) {
        page.items.clear()
        page.items.addAll(state.items)
        page.loading = state.loading
        page.hasMore = state.hasMore
    }

    fun scrollToTop(position: Int) {
        val type = getPageType(position) ?: return
        holders[type]?.scrollToTop()
    }

    fun focusPrimaryContent(type: SearchType?, anchorView: View? = null): Boolean {
        return holders[type]?.focusPrimaryContent(anchorView) == true
    }

    inner class ViewHolder(
        private val binding: PageSearchResultBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private var currentType: SearchType? = null
        private var currentAdapter: SearchItemAdapter? = null
        private var currentPage: SearchResultPage? = null
        private var tvFocusController: TvListFocusController? = null
        private var currentSpanCount: Int = 4

        fun bind(page: SearchResultPage) {
            currentPage = page
            if (currentType != page.type || currentAdapter == null) {
                currentType = page.type
                val spanCount = when (page.type) {
                    SearchType.Video,
                    SearchType.LiveRoom -> 4

                    SearchType.Animation,
                    SearchType.FilmAndTv,
                    SearchType.User -> 6
                }
                currentSpanCount = spanCount
                currentAdapter = SearchItemAdapter(
                    page.type,
                    onItemClick,
                    onTopEdgeUp,
                    onItemFocused = { view, position ->
                        tvFocusController?.onItemFocused(view, position)
                    },
                    onItemDpad = { view, keyCode, event ->
                        tvFocusController?.handleKey(view, keyCode, event) == true
                    },
                    onItemsChanged = {
                        tvFocusController?.onDataChanged(TvDataChangeReason.REMOVE_ITEM)
                    }
                )
                binding.recyclerViewResult.layoutManager =
                    WrapContentGridLayoutManager(binding.root.context, spanCount)
                binding.recyclerViewResult.adapter = currentAdapter
                binding.recyclerViewResult.setRecycledViewPool(BaseListFragment.sharedVideoPool)
                while (binding.recyclerViewResult.itemDecorationCount > 0) {
                    binding.recyclerViewResult.removeItemDecorationAt(0)
                }
                binding.recyclerViewResult.addItemDecoration(
                    GridSpacingItemDecoration(
                        spanCount,
                        binding.root.resources.getDimensionPixelSize(R.dimen.px20),
                        true
                    )
                )
                binding.recyclerViewResult.clearOnScrollListeners()
                binding.recyclerViewResult.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                    override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                        super.onScrolled(recyclerView, dx, dy)
                        val layoutManager =
                            recyclerView.layoutManager as? GridLayoutManager ?: return
                        val totalItemCount = layoutManager.itemCount
                        val lastVisibleItem = layoutManager.findLastVisibleItemPosition()
                        val pageState = currentPage ?: return
                        if (
                            totalItemCount > 0 &&
                            pageState.hasMore &&
                            !pageState.loading &&
                            lastVisibleItem >= totalItemCount - 12
                        ) {
                            pageState.loading = true
                            recyclerView.post {
                                currentType?.let(onLoadMore)
                            }
                        }
                    }
                })
                installTvFocusController()
            }
            submit(page)
        }

        fun submit(page: SearchResultPage) {
            val filteredItems = ContentFilter.filterSearchItems(binding.root.context, page.items)
            val applyUiState = {
                currentAdapter?.setItems(filteredItems)
                binding.recyclerViewResult.isVisible = filteredItems.isNotEmpty()
                binding.textEmpty.isVisible = !page.loading && filteredItems.isEmpty()
                binding.textEmpty.setText(R.string.search_empty)
                tvFocusController?.onDataChanged(TvDataChangeReason.APPEND)
                Unit
            }
            if (binding.recyclerViewResult.isComputingLayout) {
                binding.recyclerViewResult.post(applyUiState)
            } else {
                applyUiState()
            }
        }

        fun submitEmpty() {
            val applyUiState = {
                currentAdapter?.setItems(emptyList())
                binding.recyclerViewResult.isVisible = false
                binding.textEmpty.isVisible = false
            }
            if (binding.recyclerViewResult.isComputingLayout) {
                binding.recyclerViewResult.post(applyUiState)
            } else {
                applyUiState()
            }
        }

        fun release() {
            tvFocusController?.release()
            tvFocusController = null
        }

        fun scrollToTop() {
            tvFocusController?.clearAnchorForUserRefresh()
            binding.recyclerViewResult.smoothScrollToPosition(0)
        }

        fun focusPrimaryContent(anchorView: View? = null): Boolean {
            if (currentAdapter?.itemCount.orZero() <= 0) {
                return false
            }
            if (anchorView != null) {
                val handled = TabContentFocusHelper.requestSpatialOrPrimary(
                    anchorView = anchorView,
                    root = binding.recyclerViewResult,
                    direction = View.FOCUS_DOWN
                ) {
                    tvFocusController?.focusPrimary() == true
                }
                if (handled) {
                    return true
                }
            }
            return tvFocusController?.focusPrimary() == true
        }

        private fun installTvFocusController() {
            tvFocusController?.release()
            val adapter = currentAdapter ?: return
            tvFocusController = TvListFocusController(
                recyclerView = binding.recyclerViewResult,
                adapter = adapter,
                strategy = GridTvFocusStrategy { currentSpanCount },
                canLoadMore = {
                    val page = currentPage
                    page != null && page.hasMore
                },
                loadMore = loadMore@{
                    val type = currentType ?: return@loadMore
                    val page = currentPage ?: return@loadMore
                    if (!page.hasMore || page.loading) {
                        return@loadMore
                    }
                    page.loading = true
                    binding.recyclerViewResult.post {
                        onLoadMore(type)
                    }
                }
            )
        }
    }

    data class SearchResultPage(
        val type: SearchType,
        val title: String,
        val items: MutableList<SearchItemModel> = mutableListOf(),
        var loading: Boolean = false,
        var hasMore: Boolean = true
    )

    private fun Int?.orZero(): Int = this ?: 0

}
