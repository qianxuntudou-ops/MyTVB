package com.tutu.myblbl.core.ui.base

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.os.SystemClock
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.tutu.myblbl.databinding.FragmentBaseListBinding
import com.tutu.myblbl.core.common.log.AppLog
import com.tutu.myblbl.core.ui.layout.WrapContentGridLayoutManager
import com.tutu.myblbl.core.ui.focus.RecyclerViewLoadMoreFocusController
import com.tutu.myblbl.core.ui.focus.SpatialFocusNavigator
import com.tutu.myblbl.core.ui.focus.TabContentFocusHelper
import com.tutu.myblbl.core.ui.focus.tv.GridTvFocusStrategy
import com.tutu.myblbl.core.ui.focus.tv.TvDataChangeReason
import com.tutu.myblbl.core.ui.focus.tv.TvFocusStrategy
import com.tutu.myblbl.core.ui.focus.tv.TvFocusableAdapter
import com.tutu.myblbl.core.ui.focus.tv.TvListFocusController
import com.tutu.myblbl.core.ui.refresh.SwipeRefreshHelper

abstract class BaseListFragment<MODEL> : BaseFragment<FragmentBaseListBinding>() {

    companion object {
        /**
         * 推荐/热门/分区/历史多个 Tab 共用同一个 ViewHolder 池，TV 上首屏就有 8~12 张卡片，
         * 加上 Tab 切换，原来的 20 个 slot 很容易被挤爆。一旦溢出就要重新创建
         * 视频卡 ViewHolder，首屏会有明显的"卡片逐个出现"。这里调大到 60 个。
         */
        val sharedVideoPool by lazy {
            RecyclerView.RecycledViewPool().apply {
                setMaxRecycledViews(0, 60)
            }
        }
    }

    protected var recyclerView: RecyclerView? = null
    protected var swipeRefreshLayout: SwipeRefreshLayout? = null
    protected var layoutManager: LinearLayoutManager? = null
    protected var adapter: BaseAdapter<MODEL, *>? = null

    protected var currentPage = 1
    protected var isLoading = false
    protected var hasMore = true
    protected val loadMoreThreshold = 12
    protected open val autoLoad: Boolean = true
    protected open val enableSwipeRefresh: Boolean = true
    protected open val deferSwipeRefreshUntilFirstDraw: Boolean = false
    protected open val enableLoadMoreFocusController: Boolean = false
    protected open val enableTvListFocusController: Boolean = false
    protected open val initialViewHolderPrewarmCount: Int = 0
    protected open val initialViewHolderPrewarmPlan: RecyclerViewPoolPrewarmer.Plan? = null
    private var pendingRecyclerIdleAction: (() -> Unit)? = null
    private var pendingSwipeRefreshInstallRoot: View? = null
    private var pendingSwipeRefreshInstallListener: ViewTreeObserver.OnPreDrawListener? = null
    protected var loadMoreFocusController: RecyclerViewLoadMoreFocusController? = null
    protected var tvFocusController: TvListFocusController? = null
    private val restoreObserver = object : RecyclerView.AdapterDataObserver() {
        override fun onChanged() = onAdapterDataChangedForFocus(TvDataChangeReason.REPLACE_PRESERVE_ANCHOR)
        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = onAdapterDataChangedForFocus(TvDataChangeReason.APPEND)
        override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = onAdapterDataChangedForFocus(TvDataChangeReason.REMOVE_ITEM)
        override fun onItemRangeChanged(positionStart: Int, itemCount: Int) = onAdapterDataChangedForFocus(TvDataChangeReason.REPLACE_PRESERVE_ANCHOR)
        override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) = onAdapterDataChangedForFocus(TvDataChangeReason.REPLACE_PRESERVE_ANCHOR)
    }

    abstract fun createAdapter(): BaseAdapter<MODEL, *>
    open fun loadData(page: Int) {}
    override fun useLightBaseContainer(): Boolean = true

    override fun getViewBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentBaseListBinding {
        return FragmentBaseListBinding.inflate(inflater, container!!)
    }

    override fun initView() {
        val className = this::class.java.simpleName
        val t0 = SystemClock.elapsedRealtime()
        recyclerView = binding.recyclerView
        adapter = createAdapter()
        val t1 = SystemClock.elapsedRealtime()
        recyclerView?.adapter = adapter
        val rvForTuning = recyclerView
        val adapterForTuning = adapter
        if (rvForTuning != null && adapterForTuning != null) {
            VideoRecyclerViewTuning.apply(rvForTuning, adapterForTuning)
        }
        adapter?.registerAdapterDataObserver(restoreObserver)
        layoutManager = createLayoutManager()
        recyclerView?.layoutManager = layoutManager
        val rvForPrewarm = recyclerView
        val adapterForPrewarm = adapter
        val prewarmPlan = initialViewHolderPrewarmPlan
            ?: initialViewHolderPrewarmCount
                .takeIf { it > 0 }
                ?.let { RecyclerViewPoolPrewarmer.Plan(count = it, budgetMs = 180L) }
        if (rvForPrewarm != null && adapterForPrewarm != null && prewarmPlan != null) {
            RecyclerViewPoolPrewarmer.prewarm(
                recyclerView = rvForPrewarm,
                adapter = adapterForPrewarm,
                source = "$className.initial",
                plan = prewarmPlan
            )
        }
        if (layoutManager is WrapContentGridLayoutManager) {
            val gridLM = layoutManager as WrapContentGridLayoutManager
            val adapterRef = adapter
            gridLM.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    if (adapterRef == null) return 1
                    return if (position == adapterRef.items.size && adapterRef.showLoadMore) {
                        getSpanCount()
                    } else {
                        1
                    }
                }
            }
        }
        installTvListFocusControllerIfNeeded()
        recyclerView?.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                if (dy > 0) {
                    checkLoadMore()
                }
                if (recyclerView.scrollState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    tvFocusController?.onUserTouchScroll()
                }
            }

            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                if (newState != RecyclerView.SCROLL_STATE_IDLE) {
                    return
                }
                recyclerView.post {
                    if (this@BaseListFragment.recyclerView === recyclerView && isAdded && view != null) {
                        tvFocusController?.ensureValidFocus("scrollIdle")
                    }
                }
                val action = pendingRecyclerIdleAction ?: return
                pendingRecyclerIdleAction = null
                recyclerView.post {
                    if (this@BaseListFragment.recyclerView === recyclerView && isAdded && view != null) {
                        action.invoke()
                    }
                }
            }
        })
        if (enableLoadMoreFocusController && !enableTvListFocusController) {
            installLoadMoreFocusController()
        }
        if (enableSwipeRefresh) {
            setupSwipeRefresh()
        }
        val t2 = SystemClock.elapsedRealtime()
        if (t2 - t0 > 10) {
            AppLog.i("STARTUP", "$className.initView adapter=${t1 - t0}ms setup=${t2 - t1}ms total=${t2 - t0}ms")
        }
    }

    override fun initData() {
        if (autoLoad) {
            refresh()
        }
    }

    override fun onPause() {
        captureListStateForReturnRestore()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        val controller = tvFocusController ?: return
        // 从播放器等外部页面返回：用 restoreFocusAfterReturn 强制拉回捕获锚点，
        // 并以 hasFocusInList 轮询确认真实焦点落点（覆盖转场动画窗口），避免焦点停在
        // 返回按钮或彻底丢失。无锚点/重试耗尽时退回 ensureValidFocus 既有逻辑。
        controller.restoreFocusAfterReturn(
            onRestored = {},
            onFailed = {
                // allowWhenFocusOutside=false：焦点已落在列表外的可见可聚焦 View（如侧边栏功能按钮）
                // 时不再抢回焦点。焦点为 null/detached/hidden 时 ensureValidFocus 内部仍会恢复。
                tvFocusController?.ensureValidFocus("resume", allowWhenFocusOutside = false)
            }
        )
    }

    private fun setupSwipeRefresh() {
        if (deferSwipeRefreshUntilFirstDraw) {
            scheduleSwipeRefreshAfterFirstDraw()
        } else {
            installSwipeRefresh()
        }
    }

    private fun scheduleSwipeRefreshAfterFirstDraw() {
        val root = view ?: rootView ?: return installSwipeRefresh()
        if (!root.viewTreeObserver.isAlive) {
            installSwipeRefresh()
            return
        }
        val listener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                removePendingSwipeRefreshInstallListener()
                root.post {
                    if (isAdded && view != null) {
                        installSwipeRefresh()
                    }
                }
                return true
            }
        }
        pendingSwipeRefreshInstallRoot = root
        pendingSwipeRefreshInstallListener = listener
        root.viewTreeObserver.addOnPreDrawListener(listener)
    }

    private fun installSwipeRefresh() {
        if (swipeRefreshLayout != null) return
        val rv = recyclerView ?: return
        swipeRefreshLayout = SwipeRefreshHelper.wrapRecyclerView(rv) {
            refresh()
        }
    }

    private fun removePendingSwipeRefreshInstallListener() {
        val root = pendingSwipeRefreshInstallRoot
        val listener = pendingSwipeRefreshInstallListener
        if (root != null && listener != null && root.viewTreeObserver.isAlive) {
            root.viewTreeObserver.removeOnPreDrawListener(listener)
        }
        pendingSwipeRefreshInstallRoot = null
        pendingSwipeRefreshInstallListener = null
    }

    protected fun setRefreshing(refreshing: Boolean) {
        swipeRefreshLayout?.isRefreshing = refreshing
    }

    open fun getSpanCount(): Int = 4

    open fun createLayoutManager(): LinearLayoutManager {
        return WrapContentGridLayoutManager(requireContext(), getSpanCount())
    }

    protected open fun createTvFocusStrategy(): TvFocusStrategy {
        return GridTvFocusStrategy { getSpanCount() }
    }

    open fun refresh() {
        currentPage = 1
        loadData(1)
    }

    open fun checkLoadMore() {
        if (isLoading || !hasMore) return
        val lm = layoutManager ?: return
        val totalItemCount = lm.itemCount
        val lastVisiblePosition = lm.findLastVisibleItemPosition()
        if (lastVisiblePosition >= totalItemCount - loadMoreThreshold) {
            currentPage++
            AppLog.i(
                "PagePerf",
                "${this::class.java.simpleName} load_more_trigger page=$currentPage last=$lastVisiblePosition total=$totalItemCount threshold=$loadMoreThreshold"
            )
            loadData(currentPage)
        }
    }

    open fun scrollToTop() {
        tvFocusController?.clearAnchorForUserRefresh()
        recyclerView?.scrollToPosition(0)
    }

    protected fun isRecyclerIdle(): Boolean {
        val rv = recyclerView ?: return true
        return rv.scrollState == RecyclerView.SCROLL_STATE_IDLE && !rv.isComputingLayout
    }

    protected fun runWhenRecyclerIdle(action: () -> Unit) {
        val rv = recyclerView
        if (rv == null || (rv.scrollState == RecyclerView.SCROLL_STATE_IDLE && !rv.isComputingLayout)) {
            action()
            return
        }
        pendingRecyclerIdleAction = action
    }

    protected fun setAdapterData(
        data: List<MODEL>,
        preserveScrollOffset: Boolean = false,
        onComplete: (() -> Unit)? = null
    ) {
        val adp = adapter ?: return
        if (!preserveScrollOffset) {
            adp.setData(data, onComplete)
            return
        }

        val rv = recyclerView
        val lm = layoutManager
        if (rv == null || lm == null || adp.contentCount() == 0) {
            adp.setData(data, onComplete)
            return
        }

        val anchorPosition = lm.findFirstVisibleItemPosition()
        val anchorView = lm.findViewByPosition(anchorPosition)
        val anchorOffset = if (anchorView != null) {
            anchorView.top - rv.paddingTop
        } else {
            0
        }

        adp.setData(data) {
            if (layoutManager === lm && anchorPosition != RecyclerView.NO_POSITION && data.isNotEmpty()) {
                val boundedAnchor = anchorPosition.coerceIn(0, data.lastIndex)
                lm.scrollToPositionWithOffset(boundedAnchor, anchorOffset)
            }
            onComplete?.invoke()
        }
    }

    open fun focusPrimaryContent(): Boolean {
        if (!isAdded || view == null) return false
        if (TabContentFocusHelper.requestVisibleFocus(buttonRetry, viewError)) {
            return true
        }
        if (tvFocusController?.focusPrimary() == true) {
            return true
        }
        val rv = recyclerView ?: return false
        val adp = adapter ?: return false

        val focusResult = TabContentFocusHelper.requestRecyclerPrimaryFocus(
            recyclerView = rv,
            itemCount = adp.contentCount()
        )
        if (focusResult.resolved) {
            return true
        }

        return false
    }

    open fun focusPrimaryContent(anchorView: View?, preferSpatialEntry: Boolean): Boolean {
        if (preferSpatialEntry) {
            val rv = recyclerView
            if (rv != null) {
                val handled = SpatialFocusNavigator.requestBestDescendant(
                    anchorView = anchorView,
                    root = rv,
                    direction = View.FOCUS_RIGHT,
                    fallback = null
                )
                if (handled) {
                    return true
                }
            }
        }
        return focusPrimaryContent()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            val controller = tvFocusController ?: return
            // tab 切换 show 回来时，焦点往往已有效落在侧边栏按钮等列表外部 view 上
            // （用户刚点过 tab 按钮），此时不应把焦点抢回列表；仅当焦点为空或无效
            // （如 overlay 关闭后焦点丢失）时才走强制恢复。
            val focused = view?.rootView?.findFocus()
            if (focused != null && focused.isShown && focused.isFocusable &&
                !controller.hasFocusInList()
            ) {
                return
            }
            controller.restoreFocusAfterReturn(
                onRestored = {},
                onFailed = { tvFocusController?.ensureValidFocus("shown") }
            )
        }
    }

    override fun onDestroyView() {
        loadMoreFocusController?.release()
        loadMoreFocusController = null
        tvFocusController?.release()
        tvFocusController = null
        adapter?.unregisterAdapterDataObserver(restoreObserver)
        adapter?.clear()
        adapter = null
        layoutManager = null
        swipeRefreshLayout = null
        recyclerView = null
        pendingRecyclerIdleAction = null
        removePendingSwipeRefreshInstallListener()
        super.onDestroyView()
    }

    private fun installLoadMoreFocusController() {
        val rv = recyclerView ?: return
        loadMoreFocusController?.release()
        loadMoreFocusController = RecyclerViewLoadMoreFocusController(
            recyclerView = rv,
            callbacks = object : RecyclerViewLoadMoreFocusController.Callbacks {
                override fun canLoadMore(): Boolean = hasMore && !isLoading

                override fun loadMore() {
                    if (isLoading || !hasMore) {
                        return
                    }
                    currentPage++
                    AppLog.i(
                        "PagePerf",
                        "${this@BaseListFragment::class.java.simpleName} focus_load_more_trigger page=$currentPage"
                    )
                    loadData(currentPage)
                }
            }
        ).also { it.install() }
    }

    private fun View.isDescendantOf(ancestor: View): Boolean {
        var current: View? = this
        while (current != null) {
            if (current === ancestor) {
                return true
            }
            current = current.parent as? View
        }
        return false
    }

    protected fun isPendingReturnRestore(): Boolean = false

    protected fun notifyTvListDataChanged(reason: TvDataChangeReason) {
        tvFocusController?.onDataChanged(reason)
    }

    protected fun clearTvFocusAnchorForUserRefresh() {
        tvFocusController?.clearAnchorForUserRefresh()
    }

    protected fun isTvListFocusEnabled(): Boolean = tvFocusController != null

    private fun onAdapterDataChangedForFocus(reason: TvDataChangeReason) {
        tvFocusController?.onDataChanged(reason)
    }

    private fun installTvListFocusControllerIfNeeded() {
        if (!enableTvListFocusController) {
            return
        }
        val rv = recyclerView ?: return
        val focusableAdapter = adapter as? TvFocusableAdapter ?: return
        tvFocusController = TvListFocusController(
            recyclerView = rv,
            adapter = focusableAdapter,
            strategy = createTvFocusStrategy(),
            canLoadMore = { hasMore },
            loadMore = {
                if (!isLoading && hasMore) {
                    currentPage++
                    AppLog.i(
                        "PagePerf",
                        "${this::class.java.simpleName} tv_load_more_trigger page=$currentPage"
                    )
                    loadData(currentPage)
                }
            }
        )
    }

    private fun captureListStateForReturnRestore() {
        tvFocusController?.captureCurrentAnchor()
    }
}
