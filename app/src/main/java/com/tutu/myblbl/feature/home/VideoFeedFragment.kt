package com.tutu.myblbl.feature.home

import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.tutu.myblbl.R
import com.tutu.myblbl.core.common.ext.toast
import com.tutu.myblbl.core.common.log.AppLog
import com.tutu.myblbl.core.navigation.VideoRouteNavigator
import com.tutu.myblbl.core.ui.base.BaseListFragment
import com.tutu.myblbl.core.ui.focus.tv.TvDataChangeReason
import com.tutu.myblbl.model.video.VideoModel
import com.tutu.myblbl.ui.activity.MainActivity
import com.tutu.myblbl.ui.adapter.VideoAdapter
import com.tutu.myblbl.ui.fragment.main.MainNavigationViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

abstract class VideoFeedFragment : BaseListFragment<VideoModel>(), HomeTabPage, MainActivity.OnVideoBlockedListener {

    companion object {
        private const val INITIAL_REST_BATCH_DELAY_MS = 80L
    }

    protected abstract val feedViewModel: VideoFeedViewModel
    protected abstract val secondaryTabPosition: Int
    protected open val dispatchHomeContentReady: Boolean = false
    protected open val toastNonEmptyError: Boolean = false
    protected open val deferInitialLoadUntilFirstDraw: Boolean = true

    private val mainNavigationViewModel: MainNavigationViewModel by activityViewModels()
    private var pendingScrollToTopAfterRefresh = false
    private var initialLoadStarted = false
    private var initialLoadAfterFirstDrawArmed = false
    private var contentReadyDispatchScheduled = false
    private var contentReadyDispatched = false

    override val autoLoad: Boolean = false
    // TV 项目无触摸下拉刷新，下拉刷新由 MainTabReselected/MenuPressed/BackPressed 等键盘事件触发。
    // 关掉可省 setupSwipeRefresh 里的 view tree 重排（removeView + addView 两次）+ 多一层 measure。
    override val enableSwipeRefresh: Boolean = false
    override fun createAdapter(): VideoAdapter {
        return VideoAdapter(
            onItemClick = ::onVideoClick,
            onTopEdgeUp = ::focusTopTab,
            onItemFocusedWithView = { view, position ->
                tvFocusController?.onItemFocused(view, position)
            },
            onItemDpad = { view, keyCode, event ->
                tvFocusController?.handleKey(view, keyCode, event) == true
            },
            onItemsChanged = {
                notifyTvListDataChanged(TvDataChangeReason.REMOVE_ITEM)
            },
            detectPortraitFromCover = false
        )
    }

    override fun getSpanCount(): Int = 4

    override fun loadData(page: Int) {
        isLoading = true
        feedViewModel.loadMore()
    }

    override fun refresh() {
        currentPage = 1
        hasMore = true
        pendingScrollToTopAfterRefresh = true
        clearTvFocusAnchorForUserRefresh()
        isLoading = true
        feedViewModel.refresh()
    }

    override fun initView() {
        super.initView()
        adapter?.setShowLoadMore(true)
    }

    override fun initData() {
        val t0 = SystemClock.elapsedRealtime()
        if (deferInitialLoadUntilFirstDraw && (adapter?.contentCount() ?: 0) == 0) {
            showContent()
            showLoading(false)
            scheduleInitialLoadAfterFirstDraw()
        } else {
            startInitialLoad(showLoading = true, reason = "immediate")
        }
        AppLog.i("STARTUP", "${this::class.java.simpleName}.initData elapsed=${SystemClock.elapsedRealtime() - t0}ms")
    }

    private fun scheduleInitialLoadAfterFirstDraw() {
        if (initialLoadStarted || initialLoadAfterFirstDrawArmed) return
        val root = view ?: rootView ?: return startInitialLoad(showLoading = true, reason = "no_root")
        val className = this::class.java.simpleName
        val scheduledAtMs = SystemClock.elapsedRealtime()
        initialLoadAfterFirstDrawArmed = true
        AppLog.i("STARTUP", "$className.initialLoad deferUntilFirstDraw armed")

        var fired = false
        lateinit var listener: ViewTreeObserver.OnPreDrawListener

        fun fire(reason: String) {
            if (fired) return
            fired = true
            if (root.viewTreeObserver.isAlive) {
                root.viewTreeObserver.removeOnPreDrawListener(listener)
            }
            root.post {
                if (!isAdded || view == null) return@post
                AppLog.i(
                    "STARTUP",
                    "$className.initialLoad afterFirstDraw reason=$reason wait=${SystemClock.elapsedRealtime() - scheduledAtMs}ms"
                )
                startInitialLoad(showLoading = true, reason = reason)
            }
        }

        listener = ViewTreeObserver.OnPreDrawListener {
            fire("first_pre_draw")
            true
        }
        root.viewTreeObserver.addOnPreDrawListener(listener)
        root.postDelayed({ fire("fallback") }, 700L)
    }

    private fun startInitialLoad(showLoading: Boolean, reason: String) {
        if (initialLoadStarted) return
        initialLoadStarted = true
        initialLoadAfterFirstDrawArmed = false
        val t0 = SystemClock.elapsedRealtime()
        if (showLoading) {
            showLoading(true)
        }
        AppLog.i("STARTUP", "${this::class.java.simpleName}.initialLoad start reason=$reason")
        feedViewModel.loadInitial()
        AppLog.i("STARTUP", "${this::class.java.simpleName}.initialLoad invoked elapsed=${SystemClock.elapsedRealtime() - t0}ms")
    }

    override fun initObserver() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                feedViewModel.uiState.collectLatest { state ->
                    renderState(state)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                mainNavigationViewModel.events.collectLatest { event ->
                    if (!isResumed || view == null) {
                        return@collectLatest
                    }
                    when (event) {
                        is MainNavigationViewModel.Event.MainTabReselected -> {
                            if (event.index == 0 && !isLoading) {
                                refresh()
                            }
                        }

                        is MainNavigationViewModel.Event.SecondaryTabReselected -> {
                            if (event.host == MainNavigationViewModel.SecondaryTabHost.HOME &&
                                event.position == secondaryTabPosition &&
                                !isLoading
                            ) {
                                refresh()
                            }
                        }

                        MainNavigationViewModel.Event.MenuPressed -> {
                            if (!isLoading) {
                                refresh()
                            }
                        }

                        MainNavigationViewModel.Event.BackPressed -> scrollToTop()
                        else -> Unit
                    }
                }
            }
        }
    }

    override fun onRetryClick() {
        refresh()
    }

    override fun focusPrimaryContent(): Boolean {
        if (!isAdded || view == null) {
            return false
        }
        if (viewError?.visibility == View.VISIBLE && buttonRetry?.isShown == true) {
            return buttonRetry?.requestFocus() == true
        }
        return super<BaseListFragment>.focusPrimaryContent()
    }

    override fun focusPrimaryContent(anchorView: View?, preferSpatialEntry: Boolean): Boolean {
        return super<BaseListFragment>.focusPrimaryContent(anchorView, preferSpatialEntry)
    }

    private fun renderState(state: FeedUiState<VideoModel>) {
        isLoading = state.loadingInitial || state.refreshing || state.appending
        hasMore = state.hasMore
        setRefreshing(state.refreshing)
        adapter?.setShowLoadMore(state.hasMore)

        if (state.loadingInitial && state.items.isEmpty()) {
            showLoading(true)
            return
        }

        state.errorMessage?.let { message ->
            isLoading = false
            setRefreshing(false)
            if ((adapter?.contentCount() ?: 0) == 0) {
                showLoading(false)
                showError(message.ifBlank { getString(R.string.net_error) })
            } else {
                if (toastNonEmptyError && message.isNotBlank()) {
                    requireContext().toast(message)
                }
            }
            return
        }

        val listChange = if (
            state.listChange == FeedListChange.NONE &&
            state.items.isNotEmpty() &&
            (adapter?.contentCount() ?: 0) == 0
        ) {
            FeedListChange.REPLACE
        } else {
            state.listChange
        }

        when (listChange) {
            FeedListChange.NONE -> Unit
            FeedListChange.REPLACE -> applyReplacedVideos(state.items)
            FeedListChange.APPEND -> applyAppendedVideos(state.items)
        }
    }

    private fun applyReplacedVideos(videos: List<VideoModel>) {
        val shouldDeferApply = (adapter?.contentCount() ?: 0) > 0 &&
            !pendingScrollToTopAfterRefresh &&
            !isRecyclerIdle()
        if (shouldDeferApply) {
            runWhenRecyclerIdle {
                if (!isAdded || view == null) {
                    return@runWhenRecyclerIdle
                }
                applyReplacedVideosNow(videos)
            }
            return
        }
        applyReplacedVideosNow(videos)
    }

    private fun applyReplacedVideosNow(videos: List<VideoModel>) {
        val t0 = SystemClock.elapsedRealtime()
        AppLog.i("STARTUP", "T8 applyReplacedVideosNow count=${videos.size}")
        isLoading = false
        setRefreshing(false)
        val wasPendingScrollToTop = pendingScrollToTopAfterRefresh
        val isInitialPopulation = (adapter?.contentCount() ?: 0) == 0 && !wasPendingScrollToTop
        if (isInitialPopulation && videos.size > getSpanCount().coerceAtLeast(1)) {
            applyInitialVideosInBatches(videos, t0)
            return
        }
        val shouldPreserveScroll = (adapter?.contentCount() ?: 0) > 0 &&
            !pendingScrollToTopAfterRefresh &&
            !isTvListFocusEnabled()
        setAdapterData(
            videos,
            preserveScrollOffset = shouldPreserveScroll,
            onComplete = {
                val reason = if (wasPendingScrollToTop) {
                    TvDataChangeReason.USER_REFRESH
                } else {
                    TvDataChangeReason.REPLACE_PRESERVE_ANCHOR
                }
                notifyTvListDataChanged(reason)
                if (wasPendingScrollToTop && !isPendingReturnRestore()) {
                    scrollToTop()
                    tvFocusController?.requestFocusPosition(0)
                }
            }
        )
        if (videos.isNotEmpty()) {
            showContent()
            showLoading(false)
            dispatchContentReadyAfterRecyclerDrawIfNeeded("replace")
        } else {
            showEmpty()
        }
        pendingScrollToTopAfterRefresh = false
        feedViewModel.consumeListChange()
        AppLog.i("STARTUP", "T8 applyReplacedVideosNow done count=${videos.size} elapsed=${SystemClock.elapsedRealtime() - t0}ms")
    }

    private fun applyInitialVideosInBatches(videos: List<VideoModel>, startMs: Long) {
        val firstBatchSize = getSpanCount().coerceAtLeast(1).coerceAtMost(videos.size)
        val firstBatch = videos.take(firstBatchSize)
        val restBatch = videos.drop(firstBatchSize)
        AppLog.i(
            "STARTUP",
            "T8 initialBatch first=${firstBatch.size} rest=${restBatch.size} total=${videos.size}"
        )
        adapter?.setShowLoadMore(false)
        setAdapterData(
            firstBatch,
            preserveScrollOffset = false,
            onComplete = {
                notifyTvListDataChanged(TvDataChangeReason.REPLACE_PRESERVE_ANCHOR)
            }
        )
        showContent()
        showLoading(false)
        pendingScrollToTopAfterRefresh = false
        feedViewModel.consumeListChange()
        dispatchContentReadyAfterRecyclerDrawIfNeeded("initial_batch_first_row")
        rootView?.postDelayed({
            if (!isAdded || view == null || restBatch.isEmpty()) {
                return@postDelayed
            }
            val appendStart = SystemClock.elapsedRealtime()
            AppLog.i("STARTUP", "T8 initialBatch appendRest count=${restBatch.size}")
            val videoAdapter = adapter as? VideoAdapter
            if (videoAdapter != null) {
                videoAdapter.addData(restBatch)
            } else {
                setAdapterData(videos, preserveScrollOffset = false)
            }
            adapter?.setShowLoadMore(hasMore)
            notifyTvListDataChanged(TvDataChangeReason.APPEND)
            AppLog.i(
                "STARTUP",
                "T8 initialBatch appendRest done elapsed=${SystemClock.elapsedRealtime() - appendStart}ms"
            )
        }, INITIAL_REST_BATCH_DELAY_MS)
        AppLog.i(
            "STARTUP",
            "T8 applyReplacedVideosNow done initialBatch total=${videos.size} elapsed=${SystemClock.elapsedRealtime() - startMs}ms"
        )
    }

    private fun applyAppendedVideos(items: List<VideoModel>) {
        isLoading = false
        setRefreshing(false)
        val currentCount = adapter?.contentCount() ?: 0
        val newItems = items.drop(currentCount)
        if (newItems.isNotEmpty()) {
            (adapter as? VideoAdapter)?.addData(newItems)
            showContent()
            showLoading(false)
            dispatchContentReadyAfterRecyclerDrawIfNeeded("append")
        }
        if (isTvListFocusEnabled()) {
            notifyTvListDataChanged(TvDataChangeReason.APPEND)
        }
        feedViewModel.consumeListChange()
    }

    private fun dispatchContentReadyIfNeeded() {
        if (dispatchHomeContentReady && !contentReadyDispatched) {
            contentReadyDispatched = true
            mainNavigationViewModel.dispatch(MainNavigationViewModel.Event.HomeContentReady)
        }
    }

    private fun dispatchContentReadyAfterRecyclerDrawIfNeeded(reason: String) {
        if (!dispatchHomeContentReady || contentReadyDispatched || contentReadyDispatchScheduled) return
        val rv = recyclerView ?: return dispatchContentReadyIfNeeded()
        val className = this::class.java.simpleName
        val scheduledAtMs = SystemClock.elapsedRealtime()
        contentReadyDispatchScheduled = true
        var fired = false
        lateinit var listener: ViewTreeObserver.OnPreDrawListener

        fun fire(fireReason: String) {
            if (fired) return
            fired = true
            if (rv.viewTreeObserver.isAlive) {
                rv.viewTreeObserver.removeOnPreDrawListener(listener)
            }
            rv.post {
                if (!isAdded || view == null) return@post
                contentReadyDispatchScheduled = false
                AppLog.i(
                    "STARTUP",
                    "$className.contentReady afterRecyclerDraw reason=$reason fire=$fireReason wait=${SystemClock.elapsedRealtime() - scheduledAtMs}ms"
                )
                dispatchContentReadyIfNeeded()
            }
        }

        listener = ViewTreeObserver.OnPreDrawListener {
            fire("pre_draw")
            true
        }
        rv.viewTreeObserver.addOnPreDrawListener(listener)
        rv.postDelayed({ fire("fallback") }, 600L)
    }

    private fun focusTopTab(): Boolean {
        return (parentFragment as? HomeFragment)?.focusCurrentTab() == true
    }

    private fun onVideoClick(video: VideoModel) {
        val ctx = context ?: return
        VideoRouteNavigator.openVideo(
            context = ctx,
            video = video,
            playQueue = com.tutu.myblbl.ui.activity.PlayerActivity.buildPlayQueue(
                (adapter as? VideoAdapter)?.getItemsSnapshot().orEmpty(),
                video
            )
        )
    }

    override fun onVideoBlocked(aid: Long, bvid: String) {
        (adapter as? VideoAdapter)?.removeByVideoId(aid, bvid)
    }

    override fun onDestroyView() {
        initialLoadAfterFirstDrawArmed = false
        contentReadyDispatchScheduled = false
        super.onDestroyView()
    }
}
