package com.mytvb.feature.category

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewTreeObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import com.mytvb.model.video.VideoModel
import com.mytvb.ui.adapter.VideoAdapter
import com.mytvb.core.ui.base.BaseListFragment
import com.mytvb.core.ui.base.adaptiveSpanCount
import com.mytvb.core.common.content.ContentFilter
import com.mytvb.core.common.log.AppLog
import com.mytvb.core.ui.focus.tv.TvDataChangeReason
import com.mytvb.core.navigation.VideoRouteNavigator
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel

class CategoryListFragment : BaseListFragment<VideoModel>(), com.mytvb.ui.activity.MainActivity.OnVideoBlockedListener {
    companion object {
        private const val TAG = "CategoryFocus"
        private const val ARG_CATEGORY_ID = "category_id"
        private const val ARG_CATEGORY_NAME = "category_name"
        private const val CACHE_TTL_MS = 20 * 60 * 1000L

        fun newInstance(categoryId: Int, categoryName: String): CategoryListFragment {
            val fragment = CategoryListFragment()
            val args = Bundle()
            args.putInt(ARG_CATEGORY_ID, categoryId)
            args.putString(ARG_CATEGORY_NAME, categoryName)
            fragment.arguments = args
            return fragment
        }
    }

    private val viewModel: CategoryViewModel by viewModel()
    private var categoryId: Int = 0
    override val deferSwipeRefreshUntilFirstDraw: Boolean = true
    override val autoLoad: Boolean = false
    override val enableLoadMoreFocusController: Boolean = false
    override val enableTvListFocusController: Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        categoryId = arguments?.getInt(ARG_CATEGORY_ID) ?: 0
    }

    override fun createAdapter(): VideoAdapter {
        return VideoAdapter(
            onItemClick = ::onVideoClick,
            onTopEdgeUp = ::focusTopTab,
            onLeftEdge = ::switchToPrevCategoryTab,
            onRightEdge = ::switchToNextCategoryTab,
            onBottomEdgeDown = ::keepCurrentFocus,
            onItemFocusedWithView = { view, position ->
                tvFocusController?.onItemFocused(view, position)
            },
            onItemDpad = { view, keyCode, event ->
                tvFocusController?.handleKey(view, keyCode, event) == true
            },
            onItemsChanged = {
                notifyTvListDataChanged(TvDataChangeReason.REMOVE_ITEM)
            }
        )
    }

    override fun getSpanCount(): Int = resources.adaptiveSpanCount()

    override fun loadData(page: Int) {
        if (viewModel.loading.value) {
            return
        }
        hasMore = false
        isLoading = true
        showLoading(true)
        viewModel.clearError()
        viewModel.loadCategoryVideos(categoryId, forceRefresh = true)
    }

    override fun checkLoadMore() {
    }

    override fun initView() {
        super.initView()
        adapter?.showLoadMore = false
        installFocusDebugListeners()
    }

    private var globalFocusListener: ViewTreeObserver.OnGlobalFocusChangeListener? = null

    private fun installFocusDebugListeners() {
        val rootView = view ?: return
        globalFocusListener = ViewTreeObserver.OnGlobalFocusChangeListener { oldFocus, newFocus ->
            val rv = recyclerView ?: return@OnGlobalFocusChangeListener
            val oldPos = oldFocus?.let { rv.findContainingItemView(it) }?.let { rv.getChildAdapterPosition(it) }
            val newPos = newFocus?.let { rv.findContainingItemView(it) }?.let { rv.getChildAdapterPosition(it) }
            val oldDesc = oldFocus?.let { viewId(it) } ?: "null"
            val newDesc = newFocus?.let { viewId(it) } ?: "null"
            if (oldPos != null || newPos != null) {
                AppLog.d(TAG, "focusChange: $oldDesc(pos=$oldPos) → $newDesc(pos=$newPos)")
            }
        }
        rootView.viewTreeObserver.addOnGlobalFocusChangeListener(globalFocusListener)
    }

    private var childDetachListener: RecyclerView.OnChildAttachStateChangeListener? = null


    private fun isDescendantOf(view: android.view.View, ancestor: android.view.View): Boolean {
        var current: android.view.View? = view
        while (current != null) {
            if (current === ancestor) return true
            current = current.parent as? android.view.View
        }
        return false
    }

    private fun viewId(view: android.view.View): String {
        val idName = try { view.context.resources.getResourceEntryName(view.id) } catch (_: Exception) { "${view.id}" }
        return "${view.javaClass.simpleName}($idName)"
    }


    override fun onDestroyView() {
        globalFocusListener?.let {
            view?.viewTreeObserver?.removeOnGlobalFocusChangeListener(it)
        }
        globalFocusListener = null
        childDetachListener?.let {
            recyclerView?.removeOnChildAttachStateChangeListener(it)
        }
        childDetachListener = null
        super.onDestroyView()
    }

    override fun initObserver() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.videos.collectLatest { rawVideos ->
                    val videos = ContentFilter.filterVideos(requireContext(), rawVideos)
                    isLoading = false
                    setRefreshing(false)
                    val currentItemCount = (adapter as? VideoAdapter)?.items?.size ?: 0
                    if (currentItemCount == videos.size && currentItemCount > 0) {
                        return@collectLatest
                    }
                    adapter?.setData(videos)
                    notifyTvListDataChanged(TvDataChangeReason.REPLACE_PRESERVE_ANCHOR)
                    if (videos.isEmpty() && viewModel.hasLoaded.value && !viewModel.loading.value) {
                        showEmpty()
                    } else {
                        showContent()
                        showLoading(false)
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.loading.collectLatest { loading ->
                    if (loading && adapter?.itemCount == 0) {
                        showContent()
                        showLoading(true)
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.error.collectLatest { error ->
                    if (!error.isNullOrBlank()) {
                        isLoading = false
                        setRefreshing(false)
                        showError(error)
                        viewModel.clearError()
                    }
                }
            }
        }
    }

    private fun onVideoClick(video: VideoModel) {
        VideoRouteNavigator.openVideo(
            context = requireContext(),
            video = video,
            playQueue = com.mytvb.ui.activity.PlayerActivity.buildPlayQueue(
                (adapter as? VideoAdapter)?.getItemsSnapshot().orEmpty(),
                video
            )
        )
    }

    override fun onRetryClick() {
        refresh()
    }

    fun onTabSelected() {
        if (!isAdded || view == null || viewModel.loading.value || isLoading) {
            return
        }
        if (adapter?.itemCount == 0 || viewModel.isCacheStale(categoryId, CACHE_TTL_MS)) {
            loadData(1)
        }
    }

    override fun focusPrimaryContent(): Boolean {
        if (!isAdded || view == null) {
            return false
        }
        if (viewError?.visibility == View.VISIBLE && buttonRetry?.isShown == true) {
            val handled = buttonRetry?.requestFocus() == true
            return handled
        }
        val handled = super.focusPrimaryContent()
        return handled
    }

    private fun focusTopTab(): Boolean {
        // UP 顶行落"当前选中的分区 tab"（不切页），与首页一致
        return (parentFragment as? CategoryFragment)?.focusSelectedTabFromContent() == true
    }

    /** tab 栏 DOWN 的落点：就近聚焦可见第一项，位置保持不动，半截轻滚补齐。 */
    fun focusNearestVisibleContent(): Boolean {
        return focusNearestVisibleListItem()
    }

    /** 重选 tab 的回顶：滚动到顶并聚焦第一项。 */
    fun scrollToTopAndFocus(): Boolean {
        if (!isAdded || view == null) return false
        scrollToTop()
        val rv = recyclerView ?: return false
        val controller = tvFocusController ?: return false
        rv.post {
            if (isAdded && view != null) {
                controller.requestRefreshFocus(0)
            }
        }
        return true
    }

    private fun switchToPrevCategoryTab(): Boolean {
        return (parentFragment as? CategoryFragment)?.switchAdjacentTabFromContentEdge(-1) == true
    }

    private fun switchToNextCategoryTab(): Boolean {
        return (parentFragment as? CategoryFragment)?.switchAdjacentTabFromContentEdge(1) == true
    }

    private fun keepCurrentFocus(): Boolean {
        val rv = recyclerView ?: return true
        val focused = activity?.currentFocus
        if (focused != null && focused.isAttachedToWindow && isDescendantOf(focused, rv)) {
            return true
        }
        rv.post {
            if (!isAdded || view == null) return@post
            val currentFocus = activity?.currentFocus
            if (currentFocus != null && isDescendantOf(currentFocus, rv)) return@post
            val lm = layoutManager ?: return@post
            val first = lm.findFirstVisibleItemPosition()
            val last = lm.findLastVisibleItemPosition()
            if (first == RecyclerView.NO_POSITION) return@post
            for (pos in last downTo first) {
                val holder = rv.findViewHolderForAdapterPosition(pos)
                if (holder?.itemView?.requestFocus() == true) return@post
            }
        }
        return true
    }

    override fun onVideoBlocked(aid: Long, bvid: String) {
        (adapter as? VideoAdapter)?.removeByVideoId(aid, bvid)
    }
}
