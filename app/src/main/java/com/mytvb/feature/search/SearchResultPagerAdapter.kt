package com.mytvb.feature.search

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup

import androidx.core.view.isVisible
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mytvb.R
import com.mytvb.databinding.PageSearchResultBinding
import com.mytvb.model.search.SearchCategoryItem
import com.mytvb.model.search.SearchItemModel
import com.mytvb.model.search.SearchType
import com.mytvb.core.ui.base.BaseListFragment
import com.mytvb.core.ui.base.VideoRecyclerViewTuning
import com.mytvb.core.ui.base.adaptiveSpanCount
import com.mytvb.core.ui.layout.WrapContentGridLayoutManager
import com.mytvb.core.common.content.ContentFilter
import com.mytvb.core.common.log.AppLog
import com.mytvb.core.ui.focus.TabContentFocusHelper
import com.mytvb.core.ui.decoration.GridSpacingItemDecoration
import com.mytvb.core.ui.focus.tv.GridTvFocusStrategy
import com.mytvb.core.ui.focus.tv.TvDataChangeReason
import com.mytvb.core.ui.focus.tv.TvListFocusController

class SearchResultPagerAdapter(
    private val onItemClick: (SearchResultEntry) -> Unit,
    private val onLoadMore: (SearchType) -> Unit,
    private val onTopEdgeUp: ((View) -> Boolean)? = null,
    private val onCachedRelation: ((Long) -> Boolean?)? = null,
    private val onQueryRelation: ((Long) -> Unit)? = null
) : RecyclerView.Adapter<SearchResultPagerAdapter.ViewHolder>() {

    private companion object {
        const val TAG = "SearchFocus"
    }

    private val holders = mutableMapOf<SearchType, ViewHolder>()
    private val pendingStates = mutableMapOf<SearchType, PendingState>()
    private val pages = mutableListOf<SearchResultPage>()
    private var focusedPageType: SearchType? = null

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

    /** 关注状态查询完成后，把结果分发到各页的条目适配器做局部刷新。 */
    fun updateRelation(mid: Long) {
        holders.values.forEach { holder ->
            holder.dispatchRelationUpdate(mid)
        }
    }

    /** 缓存失效后整页重绑（重查关注状态）。 */
    fun refreshRelations() {
        holders.values.forEach { holder ->
            holder.dispatchRelationRefresh()
        }
    }

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

    fun captureFocusAnchors() {
        focusedPageType = null
        holders.forEach { (type, holder) ->
            // 焦点真实落在本页列表内即认定页归属，即使 holder 暂时解析不出 position
            // （点击后启动播放器、itemView 处于 rebind 中间态 pos=-1），否则
            // focusedPageType 丢失会导致恢复时 fallback 到别的页抢焦点。
            if (holder.hasFocusInList() || holder.captureFocusAnchor()) {
                focusedPageType = type
                AppLog.d(TAG, "captureFocusAnchors: focusedPage=$type")
            }
        }
        AppLog.d(TAG, "captureFocusAnchors: done, focusedPageType=$focusedPageType holders=${holders.keys}")
    }

    fun restoreFocusAnchors() {
        AppLog.d(TAG, "restoreFocusAnchors: focusedPageType=$focusedPageType holders=${holders.keys}")
        // 已有焦点落在某页列表内（如 TvListFocusController 已自恢复）时不再移动焦点，
        // 避免 fallback 逻辑把焦点抢到别的页。
        if (holders.values.any { it.hasFocusInList() }) {
            AppLog.d(TAG, "restoreFocusAnchors: focus already inside a page list, skip")
            return
        }
        // 离开时焦点就不在搜索结果页（focusedPageType=null，如焦点在底层 tab 页列表上
        // 直接进播放）：焦点恢复职责不归搜索页，继续走 fallback 会把底层页面已恢复好
        // 的焦点抢到结果页。
        if (focusedPageType == null) {
            AppLog.d(TAG, "restoreFocusAnchors: focusedPageType=null, skip")
            return
        }
        // 结果面板整体不可见（搜索浮层未随本页显示）：对不可见面板恢复焦点等于把
        // 焦点藏进看不见的列表，表现为返回后焦点消失、按 BACK 才重新出现。
        if (holders.values.none { it.isPageVisible() }) {
            AppLog.d(TAG, "restoreFocusAnchors: result panel not shown, skip")
            return
        }
        val focusedType = focusedPageType
        // 离屏的 focused 页同样不可见，不直接恢复，交给下方可见页 fallback。
        val focusedHolder = focusedType?.let { holders[it] }?.takeIf { it.isPageVisible() }
        if (focusedHolder != null) {
            AppLog.d(TAG, "restoreFocusAnchors: trying focused page=$focusedType")
            if (focusedHolder.restoreFocusAnchor()) {
                AppLog.d(TAG, "restoreFocusAnchors: SUCCESS on focused page=$focusedType")
                return
            }
            AppLog.d(TAG, "restoreFocusAnchors: FAILED on focused page=$focusedType, trying others")
        }
        // fallback 只考虑可见页：ViewPager 离屏页（isShown=false）不可见却仍可获焦，
        // 被它"恢复成功"等于把焦点抢走，表现为返回搜索结果后焦点消失。
        val restored = holders.entries
            .filter { it.value.isPageVisible() }
            .any { (type, holder) ->
                val ok = holder.restoreFocusAnchor()
                AppLog.d(TAG, "restoreFocusAnchors: fallback page=$type result=$ok")
                ok
            }
        if (!restored) {
            AppLog.d(TAG, "restoreFocusAnchors: all FAILED, falling back to focusPrimaryContent")
            // 兜底与上方 fallback 同一可见性约束：不可见页的 focusPrimaryContent 一样
            // 能聚焦成功，会让焦点落在看不见的列表上。
            holders.values.filter { it.isPageVisible() }.forEach { it.focusPrimaryContent() }
        }
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
                    SearchType.LiveRoom -> binding.root.resources.adaptiveSpanCount()

                    // 用户条目三行文字（昵称/粉丝·视频·认证/签名），窄列下文字区不足会截断，
                    // 对齐官方 TV 端 2 列大条目
                    SearchType.User -> binding.root.resources.adaptiveSpanCount(base = 2, wide = 4)

                    SearchType.Animation,
                    SearchType.FilmAndTv -> binding.root.resources.adaptiveSpanCount(base = 6, wide = 8)
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
                    },
                    onCachedRelation = onCachedRelation,
                    onQueryRelation = onQueryRelation
                )
                binding.recyclerViewResult.layoutManager =
                    WrapContentGridLayoutManager(binding.root.context, spanCount)
                binding.recyclerViewResult.adapter = currentAdapter
                currentAdapter?.let { adapter ->
                    VideoRecyclerViewTuning.apply(binding.recyclerViewResult, adapter)
                }
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
                val changed = currentAdapter?.setItems(filteredItems) ?: false
                AppLog.d(TAG, "submit: type=$currentType itemCount=${filteredItems.size} changed=$changed")
                binding.recyclerViewResult.isVisible = filteredItems.isNotEmpty()
                binding.textEmpty.isVisible = !page.loading && filteredItems.isEmpty()
                binding.textEmpty.setText(R.string.search_empty)
                if (changed) {
                    tvFocusController?.onDataChanged(TvDataChangeReason.APPEND)
                }
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

        fun captureFocusAnchor(): Boolean {
            return tvFocusController?.captureCurrentAnchor() ?: false
        }

        fun hasFocusInList(): Boolean {
            return tvFocusController?.hasFocusInList() == true
        }

        fun dispatchRelationUpdate(mid: Long) {
            if (currentType == SearchType.User) {
                currentAdapter?.updateRelation(mid)
            }
        }

        fun dispatchRelationRefresh() {
            if (currentType == SearchType.User) {
                currentAdapter?.refreshAllRelations()
            }
        }

        fun isPageVisible(): Boolean {            return itemView.isShown
        }

        fun restoreFocusAnchor(): Boolean {
            return tvFocusController?.restoreCapturedAnchor() == true
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
