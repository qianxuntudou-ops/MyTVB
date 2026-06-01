@file:Suppress("SpellCheckingInspection")

package com.tutu.myblbl.feature.me

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewTreeObserver
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import com.tutu.myblbl.R
import com.tutu.myblbl.databinding.FragmentMeTabListBinding
import com.tutu.myblbl.event.AppEventHub
import com.tutu.myblbl.model.video.HistoryVideoModel
import com.tutu.myblbl.model.video.VideoModel
import com.tutu.myblbl.ui.adapter.HistoryVideoAdapter
import com.tutu.myblbl.ui.adapter.VideoAdapter
import com.tutu.myblbl.core.ui.base.BaseFragment
import com.tutu.myblbl.core.ui.base.BaseListFragment
import com.tutu.myblbl.core.ui.base.VideoRecyclerViewTuning
import com.tutu.myblbl.core.ui.layout.WrapContentGridLayoutManager
import com.tutu.myblbl.core.common.content.ContentFilter
import com.tutu.myblbl.core.common.log.AppLog
import com.tutu.myblbl.core.common.log.PagePerfLogger
import com.tutu.myblbl.core.ui.focus.SpatialFocusNavigator
import com.tutu.myblbl.core.ui.focus.TabContentFocusHelper
import com.tutu.myblbl.core.ui.focus.tv.GridTvFocusStrategy
import com.tutu.myblbl.core.ui.focus.tv.TvDataChangeReason
import com.tutu.myblbl.core.ui.focus.tv.TvFocusableAdapter
import com.tutu.myblbl.core.ui.focus.tv.TvListFocusController
import com.tutu.myblbl.core.ui.refresh.SwipeRefreshHelper
import com.tutu.myblbl.core.navigation.VideoRouteNavigator
import com.tutu.myblbl.core.ui.render.FirstScreenRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class MeListFragment : BaseFragment<FragmentMeTabListBinding>(), MeTabPage, com.tutu.myblbl.ui.activity.MainActivity.OnVideoBlockedListener {
    companion object {
        const val TYPE_HISTORY = "history"
        const val TYPE_LATER = "later"
        private const val CACHE_TTL_MS = 10 * 60 * 1000L

        private const val ARG_TYPE = "type"

        fun newInstance(type: String): MeListFragment {
            val fragment = MeListFragment()
            val args = Bundle()
            args.putString(ARG_TYPE, type)
            fragment.arguments = args
            return fragment
        }
    }

    private val appEventHub: AppEventHub by inject()
    private val viewModel: MeListViewModel by viewModel()
    private var videoAdapter: VideoAdapter? = null
    private var historyAdapter: HistoryVideoAdapter? = null
    private var swipeRefreshLayout: androidx.swiperefreshlayout.widget.SwipeRefreshLayout? = null
    private var type: String = TYPE_HISTORY
    private var currentPage = 1
    private val pageSize = 20
    private var lastFocusedHistoryPosition = RecyclerView.NO_POSITION
    private var lastFocusedHistoryKey: String? = null
    private var pendingRestoreFocus = false
    private var pendingHistoryAnchorPosition = RecyclerView.NO_POSITION
    private var pendingHistoryAnchorOffset = 0
    private var pendingHistoryReturnRestore = false
    private var pendingHistoryScrollToTop = false
    private var pendingLaterScrollToTop = false
    private var focusToFirstAfterScrollToTop = false
    private var lastKnownLoggedIn = false
    private var tvFocusController: TvListFocusController? = null
    private var currentOpenStartMs = 0L
    private var latestRequestStartMs = 0L
    private var lastTabSelectedAtMs = 0L
    private var lastRenderedHistorySignature = ""
    private var lastRenderedLaterSignature = ""
    private var allowHistoryLoadMore = false
    private var returningFromPlayer = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        type = arguments?.getString(ARG_TYPE) ?: TYPE_HISTORY
    }

    override fun getViewBinding(
        inflater: LayoutInflater,
        container: ViewGroup?
    ): FragmentMeTabListBinding {
        return FragmentMeTabListBinding.inflate(inflater, container, false)
    }

    override fun initView() {
        if (type == TYPE_HISTORY) {
            historyAdapter = HistoryVideoAdapter(
                onItemClick = ::onHistoryVideoClick,
                onTopEdgeUp = {
                    focusTopTab()
                },
                onItemFocused = { position -> lastFocusedHistoryPosition = position },
                onItemFocusedWithView = { view, position ->
                    tvFocusController?.onItemFocused(view, position)
                },
                onItemDpad = { view, keyCode, event ->
                    tvFocusController?.handleKey(view, keyCode, event) == true
                },
                onItemsChanged = {
                    tvFocusController?.onDataChanged(TvDataChangeReason.REMOVE_ITEM)
                },
                onHistoryRecordDeleted = { deleted ->
                    viewModel.removeHistoryVideo(deleted)
                },
                onItemDisliked = { item ->
                    viewModel.removeHistoryVideo(item)
                },
                onUpDisliked = { upName ->
                    viewModel.removeHistoryVideosByUp(upName)
                }
            )
        } else {
            videoAdapter = VideoAdapter(
                onItemClick = ::onVideoClick,
                onTopEdgeUp = {
                    focusTopTab()
                },
                onItemFocusedWithView = { view, position ->
                    tvFocusController?.onItemFocused(view, position)
                },
                onItemDpad = { view, keyCode, event ->
                    tvFocusController?.handleKey(view, keyCode, event) == true
                },
                onItemsChanged = {
                    tvFocusController?.onDataChanged(TvDataChangeReason.REMOVE_ITEM)
                }
            ).apply {
                setShowLoadMore(false)
            }
        }

        val layoutManager = WrapContentGridLayoutManager(requireContext(), 4)
        binding.recyclerView.layoutManager = layoutManager
        binding.recyclerView.adapter = historyAdapter ?: videoAdapter
        (historyAdapter ?: videoAdapter)?.let { adapter ->
            VideoRecyclerViewTuning.apply(binding.recyclerView, adapter)
        }
        binding.emptyContainer.visibility = View.GONE
        binding.progressBar.visibility = View.GONE
        binding.btnRetry.setOnClickListener {
            currentPage = 1
            loadData()
        }
        binding.btnRetry.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN &&
                keyCode == KeyEvent.KEYCODE_DPAD_UP
            ) {
                focusTopTab()
            } else {
                false
            }
        }
        setupLoadMore()
        installTvFocusControllerIfNeeded()
        swipeRefreshLayout = SwipeRefreshHelper.wrapRecyclerView(
            recyclerView = binding.recyclerView,
            onRefresh = { refresh() }
        )
    }
    
    private fun setupLoadMore() {
        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                if (type == TYPE_HISTORY && (!allowHistoryLoadMore || dy <= 0)) {
                    return
                }
                val layoutManager = binding.recyclerView.layoutManager as? WrapContentGridLayoutManager ?: return
                val totalItemCount = layoutManager.itemCount
                val lastVisibleItem = layoutManager.findLastVisibleItemPosition()
                
                if (!viewModel.loading.value && viewModel.hasMore.value && lastVisibleItem >= totalItemCount - 5) {
                    currentPage++
                    loadData()
                }
            }
        })
    }

    override fun initData() {
        lastKnownLoggedIn = viewModel.isLoggedIn()
        AppLog.d("MePerf", "MeListFragment.initData: type=$type, network_only")
        // 不在 initData 里直接 loadData()，等 onTabSelected() 触发首次加载，
        // 避免相邻 tab 被预加载时也发起网络请求。
    }

    override fun onResume() {
        super.onResume()
        val restoringFromPlayer = pendingRestoreFocus
        if (pendingRestoreFocus) {
            returningFromPlayer = true
            restoreContentFocus()
        }
        if (type == TYPE_HISTORY || type == TYPE_LATER) {
            val isLoggedIn = viewModel.isLoggedIn()
            if (isLoggedIn && !lastKnownLoggedIn) {
                lastKnownLoggedIn = isLoggedIn
                if (!restoringFromPlayer) {
                    refresh()
                }
            } else {
                lastKnownLoggedIn = isLoggedIn
            }
        }
    }

    override fun initObserver() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    if (type == TYPE_HISTORY) {
                        bindHistoryData(state.historyVideos)
                    } else {
                        bindLaterData(state.laterVideos)
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.loading.collectLatest { loading ->
                    if (!loading) {
                        swipeRefreshLayout?.isRefreshing = false
                        settlePendingRefreshAfterNoop()
                    }
                    val hasData = hasContentItems()
                    binding.progressBar.visibility = if (loading && !hasData) View.VISIBLE else View.GONE
                    if (loading && !hasData) {
                        binding.emptyContainer.visibility = View.GONE
                    }
                    if (loading || hasData || !viewModel.error.value.isNullOrBlank()) {
                        updateContentState(!hasData)
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.error.collectLatest { error ->
                    error?.let {
                        if (!hasContentItems()) {
                            showState(it, showRetry = shouldShowRetry(it))
                        }
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                appEventHub.events.collectLatest { event ->
                    if (isHidden || !isVisible) {
                        return@collectLatest
                    }
                    when (event) {


                        AppEventHub.Event.UserSessionChanged -> {
                            if (type == TYPE_HISTORY || type == TYPE_LATER) {
                                refresh()
                            }
                        }

                        is AppEventHub.Event.WatchLaterVideoRemoved -> {
                            if (type == TYPE_LATER) {
                                viewModel.removeLaterVideo(event.aid, event.bvid)
                            }
                        }

                        else -> Unit
                    }
                }
            }
        }
    }

    private fun loadData() {
        latestRequestStartMs = PagePerfLogger.now()
        if (type == TYPE_HISTORY && currentPage <= 1) {
            allowHistoryLoadMore = false
        }
        PagePerfLogger.markNow(
            pageTag(),
            "request_start",
            "page=$currentPage source=${if (currentOpenStartMs > 0L) "open" else "refresh"} hasContent=${hasContentItems()}"
        )
        when (type) {
            TYPE_HISTORY -> viewModel.loadHistory(currentPage, pageSize)
            TYPE_LATER -> viewModel.loadLaterWatch()
        }
    }

    private fun onVideoClick(video: VideoModel) {
        val act = activity ?: (context as? androidx.appcompat.app.AppCompatActivity)
        if (act == null || act.isFinishing || act.isDestroyed) {
            AppLog.w("MeList", "onVideoClick dropped: activity=$act finishing=${act?.isFinishing} destroyed=${act?.isDestroyed} bvid=${video.bvid}")
            return
        }
        pendingRestoreFocus = true
        tvFocusController?.captureCurrentAnchor()
        VideoRouteNavigator.openVideo(
            context = act,
            video = video,
            playQueue = com.tutu.myblbl.ui.activity.PlayerActivity.buildPlayQueue(
                videoAdapter?.getItemsSnapshot().orEmpty(),
                video
            )
        )
    }

    private fun onHistoryVideoClick(video: HistoryVideoModel) {
        val act = activity ?: (context as? androidx.appcompat.app.AppCompatActivity)
        if (act == null || act.isFinishing || act.isDestroyed) {
            AppLog.w("MeList", "onHistoryVideoClick dropped: activity=$act finishing=${act?.isFinishing} destroyed=${act?.isDestroyed}")
            return
        }
        val mapped = video.toVideoModel()
        if (mapped.aid != 0L || mapped.bvid.isNotEmpty()) {
            lastFocusedHistoryPosition = historyAdapter?.focusedItemPosition() ?: RecyclerView.NO_POSITION
            lastFocusedHistoryKey = historyItemKey(video)
            pendingRestoreFocus = true
            pendingHistoryReturnRestore = true
            pendingHistoryScrollToTop = false
            tvFocusController?.captureCurrentAnchor()
            captureHistoryViewportAnchor()
            VideoRouteNavigator.openHistory(
                context = act,
                historyVideo = video,
                playQueue = com.tutu.myblbl.ui.activity.PlayerActivity.buildPlayQueue(
                    historyAdapter?.getItemsSnapshot().orEmpty().map { it.toVideoModel() },
                    mapped
                )
            )
        }
    }

    private suspend fun bindHistoryData(videos: List<HistoryVideoModel>) {
        AppLog.d("MeDebug", "[history] bindHistoryData: raw=${videos.size}, pendingHistoryScroll=$pendingHistoryScrollToTop, currentPage=$currentPage, adapterCount=${historyAdapter?.contentCount() ?: 0}")
        if (shouldSkipInitialEmptyEmission(videos.size)) {
            PagePerfLogger.markNow(pageTag(), "skip_empty_initial_history")
            return
        }
        val bindStartMs = PagePerfLogger.now()
        PagePerfLogger.mark(
            pageTag(),
            "data_collected",
            latestRequestStartMs,
            "raw=${videos.size} page=$currentPage"
        )
        val rawSignature = historyListSignature(videos)
        if (rawSignature == lastRenderedHistorySignature && (historyAdapter?.contentCount() ?: 0) > 0) {
            skipDuplicatePayload(videos.size)
            return
        }
        swipeRefreshLayout?.isRefreshing = false
        val adapter = historyAdapter ?: return
        val ctx = context ?: return
        val filtered = withContext(Dispatchers.Default) {
            videos.filter {
                !ContentFilter.isVideoBlocked(
                    context = ctx,
                    typeName = it.tagName,
                    title = it.title,
                    authorName = it.displayAuthorName,
                    aid = it.history?.oid ?: 0L,
                    bvid = it.bvid,
                    coverUrl = it.cover
                )
            }
        }
        PagePerfLogger.mark(
            pageTag(),
            "filter_end",
            bindStartMs,
            "raw=${videos.size} filtered=${filtered.size}"
        )
        lastRenderedHistorySignature = rawSignature
        val shouldRestoreFocus = pendingHistoryReturnRestore && filtered.isNotEmpty()
        val shouldScrollToTop = pendingHistoryScrollToTop && filtered.isNotEmpty()
        val applyStartMs = PagePerfLogger.now()
        if (currentPage <= 1 || adapter.contentCount() == 0) {
            FirstScreenRenderer.render(
                recyclerView = binding.recyclerView,
                page = pageTag(),
                items = filtered,
                startMs = currentOpenStartMs.takeIf { it > 0L } ?: latestRequestStartMs,
                source = "network",
                spanCount = 4,
                setItems = { firstBatch, onCommitted ->
                    adapter.setData(firstBatch, onCommitted)
                },
                appendItems = { remaining ->
                    adapter.addData(remaining)
                },
                onFirstBatchCommitted = {
                    PagePerfLogger.mark(
                        pageTag(),
                        "adapter_commit",
                        applyStartMs,
                        "items=${adapter.contentCount()}"
                    )
                    tvFocusController?.onDataChanged(TvDataChangeReason.REPLACE_PRESERVE_ANCHOR)
                    AppLog.d("MeDebug", "[history] onFirstBatchCommitted: shouldRestoreFocus=$shouldRestoreFocus, shouldScrollToTop=$shouldScrollToTop, pendingHistoryScroll=$pendingHistoryScrollToTop, filtered=${filtered.size}")
                    when {
                        shouldRestoreFocus -> {
                            restoreContentFocus()
                        }
                        shouldScrollToTop -> {
                            pendingHistoryScrollToTop = false
                            scrollHistoryListToTopAfterRefresh()
                        }
                    }
                    currentOpenStartMs = 0L
                },
                onFirstFrame = {
                    if (type == TYPE_HISTORY) {
                        allowHistoryLoadMore = true
                    }
                },
                onAppendRest = {
                    tvFocusController?.onDataChanged(TvDataChangeReason.APPEND)
                }
            )
        } else {
            adapter.setData(filtered) {
                PagePerfLogger.mark(
                    pageTag(),
                    "adapter_commit",
                    applyStartMs,
                    "items=${adapter.contentCount()}"
                )
                tvFocusController?.onDataChanged(TvDataChangeReason.APPEND)
                if (type == TYPE_HISTORY) {
                    allowHistoryLoadMore = true
                }
            }
        }
        updateContentState(filtered.isEmpty())
    }

    private suspend fun bindLaterData(videos: List<VideoModel>) {
        AppLog.d("MeDebug", "[later] bindLaterData: raw=${videos.size}, pendingLaterScroll=$pendingLaterScrollToTop, currentPage=$currentPage, adapterCount=${videoAdapter?.contentCount() ?: 0}")
        if (shouldSkipInitialEmptyEmission(videos.size)) {
            AppLog.d("MeDebug", "[later] bindLaterData: SKIPPED (empty emission)")
            PagePerfLogger.markNow(pageTag(), "skip_empty_initial_later")
            return
        }
        val bindStartMs = PagePerfLogger.now()
        PagePerfLogger.mark(
            pageTag(),
            "data_collected",
            latestRequestStartMs,
            "raw=${videos.size}"
        )
        val rawSignature = videoListSignature(videos)
        if (rawSignature == lastRenderedLaterSignature && (videoAdapter?.contentCount() ?: 0) > 0) {
            AppLog.d("MeDebug", "[later] bindLaterData: SKIPPED (duplicate signature)")
            skipDuplicatePayload(videos.size)
            return
        }
        swipeRefreshLayout?.isRefreshing = false
        val adapter = videoAdapter ?: return
        val ctx = context ?: return
        val filtered = withContext(Dispatchers.Default) {
            ContentFilter.filterVideos(ctx, videos)
        }
        PagePerfLogger.mark(
            pageTag(),
            "filter_end",
            bindStartMs,
            "raw=${videos.size} filtered=${filtered.size}"
        )
        lastRenderedLaterSignature = rawSignature
        val applyStartMs = PagePerfLogger.now()
        FirstScreenRenderer.render(
            recyclerView = binding.recyclerView,
            page = pageTag(),
            items = filtered,
            startMs = currentOpenStartMs.takeIf { it > 0L } ?: latestRequestStartMs,
            source = "network",
            spanCount = 4,
            setItems = { firstBatch, onCommitted ->
                adapter.setData(firstBatch, onCommitted)
            },
            appendItems = { remaining ->
                adapter.addData(remaining)
            },
            onFirstBatchCommitted = {
                PagePerfLogger.mark(
                    pageTag(),
                    "adapter_commit",
                    applyStartMs,
                    "items=${adapter.contentCount()}"
                )
                AppLog.d("MeDebug", "[later] onFirstBatchCommitted: pendingLaterScroll=$pendingLaterScrollToTop, filtered=${filtered.size}, adapterCount=${adapter.contentCount()}")
                tvFocusController?.onDataChanged(TvDataChangeReason.REPLACE_PRESERVE_ANCHOR)
                if (pendingLaterScrollToTop && filtered.isNotEmpty()) {
                    AppLog.d("MeDebug", "[later] SCROLLING TO TOP")
                    pendingLaterScrollToTop = false
                    val shouldFocusFirst = focusToFirstAfterScrollToTop
                    focusToFirstAfterScrollToTop = false
                    scrollListToTop(immediate = true)
                    if (shouldFocusFirst) {
                        val rv = binding.recyclerView
                        val focused = activity?.currentFocus
                        if (focused != null && rv.findContainingItemView(focused) != null) {
                            rv.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                                override fun onPreDraw(): Boolean {
                                    rv.viewTreeObserver.removeOnPreDrawListener(this)
                                    if (isAdded && view != null) {
                                        tvFocusController?.requestRefreshFocus(0)
                                    }
                                    return true
                                }
                            })
                        }
                    } else {
                        binding.recyclerView.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                    }
                } else {
                    AppLog.d("MeDebug", "[later] NOT scrolling to top: pendingLaterScroll=$pendingLaterScrollToTop, filteredEmpty=${filtered.isEmpty()}")
                }
                currentOpenStartMs = 0L
            },
            onAppendRest = {
                tvFocusController?.onDataChanged(TvDataChangeReason.APPEND)
            }
        )
        updateContentState(filtered.isEmpty())
    }

    private fun shouldSkipInitialEmptyEmission(rawSize: Int): Boolean {
        if (rawSize != 0 || hasContentItems()) {
            return false
        }
        return latestRequestStartMs <= 0L || viewModel.loading.value
    }

    private fun historyListSignature(videos: List<HistoryVideoModel>): String {
        if (videos.isEmpty()) return "empty"
        val first = videos.first()
        val last = videos.last()
        return "${videos.size}:${first.bvid}:${first.title.hashCode()}:${last.bvid}:${last.title.hashCode()}:${last.viewAt}"
    }

    private fun videoListSignature(videos: List<VideoModel>): String {
        if (videos.isEmpty()) return "empty"
        val first = videos.first()
        val last = videos.last()
        return "${videos.size}:${first.bvid.ifBlank { first.aid.toString() }}:${first.title.hashCode()}:${last.bvid.ifBlank { last.aid.toString() }}:${last.title.hashCode()}"
    }

    private fun skipDuplicatePayload(rawSize: Int) {
        swipeRefreshLayout?.isRefreshing = false
        PagePerfLogger.markNow(pageTag(), "skip_duplicate_payload", "raw=$rawSize")
        when (type) {
            TYPE_HISTORY -> {
                if (pendingHistoryScrollToTop) {
                    pendingHistoryScrollToTop = false
                    scrollHistoryListToTopAfterRefresh()
                } else if (pendingHistoryReturnRestore) {
                    restoreContentFocus()
                }
            }
            TYPE_LATER -> {
                if (pendingLaterScrollToTop) {
                    pendingLaterScrollToTop = false
                    val shouldFocusFirst = focusToFirstAfterScrollToTop
                    focusToFirstAfterScrollToTop = false
                    scrollListToTop(immediate = true)
                    if (shouldFocusFirst) {
                        val rv = binding.recyclerView
                        val focused = activity?.currentFocus
                        if (focused != null && rv.findContainingItemView(focused) != null) {
                            rv.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                                override fun onPreDraw(): Boolean {
                                    rv.viewTreeObserver.removeOnPreDrawListener(this)
                                    if (isAdded && view != null) {
                                        tvFocusController?.requestRefreshFocus(0)
                                    }
                                    return true
                                }
                            })
                        }
                    } else {
                        binding.recyclerView.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                    }
                }
            }
        }
        currentOpenStartMs = 0L
        updateContentState(isEmpty = false)
    }

    private fun installTvFocusControllerIfNeeded() {
        if (type != TYPE_HISTORY && type != TYPE_LATER) {
            return
        }
        val focusableAdapter = (historyAdapter ?: videoAdapter) as? TvFocusableAdapter ?: return
        tvFocusController?.release()
        tvFocusController = TvListFocusController(
            recyclerView = binding.recyclerView,
            adapter = focusableAdapter,
            strategy = GridTvFocusStrategy { 4 },
            canLoadMore = {
                when (type) {
                    TYPE_HISTORY -> allowHistoryLoadMore && viewModel.hasMore.value
                    TYPE_LATER -> false
                    else -> false
                }
            },
            loadMore = {
                if (type == TYPE_HISTORY && allowHistoryLoadMore && !viewModel.loading.value && viewModel.hasMore.value) {
                    currentPage++
                    loadData()
                }
            }
        )
    }

    private fun updateContentState(isEmpty: Boolean) {
        if (isEmpty && !viewModel.loading.value) {
            val errorMessage = viewModel.error.value
            if (!errorMessage.isNullOrBlank()) {
                showState(errorMessage, showRetry = shouldShowRetry(errorMessage))
            } else {
                showState(getEmptyMessage(), showRetry = false)
            }
        } else {
            showContent()
        }
    }

    private fun settlePendingRefreshAfterNoop() {
        if (!hasContentItems()) {
            return
        }
        when (type) {
            TYPE_HISTORY -> {
                if (pendingHistoryScrollToTop) {
                    pendingHistoryScrollToTop = false
                    scrollHistoryListToTopAfterRefresh()
                }
            }
            TYPE_LATER -> {
                if (pendingLaterScrollToTop) {
                    pendingLaterScrollToTop = false
                    scrollListToTop(immediate = true)
                    binding.recyclerView.post {
                        scrollListToTop(immediate = true)
                    }
                }
            }
        }
    }

    override fun showContent() {
        binding.recyclerView.visibility = View.VISIBLE
        binding.emptyContainer.visibility = View.GONE
    }

    private fun showState(message: String, showRetry: Boolean) {
        binding.recyclerView.visibility = View.GONE
        binding.emptyContainer.visibility = View.VISIBLE
        binding.tvEmpty.text = message
        binding.btnRetry.visibility = if (showRetry) View.VISIBLE else View.GONE
        if (showRetry) {
            binding.btnRetry.post { binding.btnRetry.requestFocus() }
        }
    }

    override fun scrollToTop() {
        scrollListToTop(immediate = false)
    }

    override fun refresh() {
        returningFromPlayer = false
        currentPage = 1
        pendingRestoreFocus = false
        pendingHistoryReturnRestore = false
        pendingHistoryScrollToTop = type == TYPE_HISTORY
        pendingLaterScrollToTop = type == TYPE_LATER
        focusToFirstAfterScrollToTop = true
        tvFocusController?.clearAnchorForUserRefresh()
        swipeRefreshLayout?.isRefreshing = true
        pendingHistoryAnchorPosition = RecyclerView.NO_POSITION
        pendingHistoryAnchorOffset = 0
        if (type == TYPE_HISTORY) {
            lastFocusedHistoryPosition = RecyclerView.NO_POSITION
            lastFocusedHistoryKey = null
            historyAdapter?.clearFocusMemory()
        }
        loadData()
    }

    override fun onTabSelected() {
        AppLog.d("MeDebug", "[$type] onTabSelected: isAdded=$isAdded, hasView=${view != null}, loading=${viewModel.loading.value}, hasContent=${hasContentItems()}, returningFromPlayer=$returningFromPlayer")
        if (!isAdded || view == null) {
            AppLog.d("MeDebug", "[$type] onTabSelected: EARLY RETURN (isAdded/view)")
            return
        }
        if (pendingRestoreFocus && hasContentItems()) {
            AppLog.d("MeDebug", "[$type] onTabSelected: restore pending, keep current anchor")
            restoreContentFocus()
            return
        }
        if (returningFromPlayer && hasContentItems()) {
            AppLog.d("MeDebug", "[$type] onTabSelected: returning from player, skip reload")
            restoreContentFocus()
            return
        }
        val now = PagePerfLogger.now()
        val hasContent = hasContentItems()
        if (!hasContent && now - lastTabSelectedAtMs < 250L) {
            PagePerfLogger.markNow(pageTag(), "skip_duplicate_tab_selected")
            return
        }
        lastTabSelectedAtMs = now
        currentOpenStartMs = now
        PagePerfLogger.markNow(pageTag(), "tab_selected", "hasContent=$hasContent")
        if (hasContent) {
            logMeFirstDraw(currentContentCount(), source = "visible_content")
        }
        when (type) {
            TYPE_HISTORY, TYPE_LATER -> {
                pendingHistoryScrollToTop = type == TYPE_HISTORY
                pendingLaterScrollToTop = type == TYPE_LATER
                tvFocusController?.clearAnchorForUserRefresh()
                binding.recyclerView.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                AppLog.d("MeDebug", "[$type] onTabSelected: network_only loadData, pendingLaterScroll=$pendingLaterScrollToTop, pendingHistoryScroll=$pendingHistoryScrollToTop")
                currentPage = 1
                loadData()
            }
            else -> {
                if (!hasContentItems() || viewModel.shouldRefresh(CACHE_TTL_MS)) {
                    currentPage = 1
                    loadData()
                }
            }
        }
    }

    override fun onTabReselected() {
        if (!isAdded || view == null) {
            return
        }
        currentOpenStartMs = PagePerfLogger.now()
        PagePerfLogger.markNow(pageTag(), "tab_reselected", "hasContent=${hasContentItems()}")
        refresh()
    }

    override fun onHostEvent(event: MeTabPage.HostEvent): Boolean {
        when (event) {
            MeTabPage.HostEvent.SELECT_TAB4 -> onTabSelected()
            MeTabPage.HostEvent.CLICK_TAB4 -> refresh()
            MeTabPage.HostEvent.BACK_PRESSED -> Unit
            MeTabPage.HostEvent.KEY_MENU_PRESS -> refresh()
        }
        return true
    }

    override fun focusPrimaryContent(): Boolean {
        if (!isAdded || view == null) {
            return false
        }
        if (binding.emptyContainer.isVisible && binding.btnRetry.isShown) {
            val handled = TabContentFocusHelper.requestVisibleFocus(binding.btnRetry)
            return handled
        }
        val itemCount = binding.recyclerView.adapter?.itemCount ?: 0
        if (itemCount == 0) {
            return false
        }
        if (tvFocusController?.focusPrimary() == true) {
            return true
        }
        val result = TabContentFocusHelper.requestRecyclerPrimaryFocus(
            recyclerView = binding.recyclerView,
            itemCount = itemCount
        )
        return result.resolved
    }

    override fun focusPrimaryContent(anchorView: View?, preferSpatialEntry: Boolean): Boolean {
        if (preferSpatialEntry) {
            val handled = SpatialFocusNavigator.requestBestDescendant(
                anchorView = anchorView,
                root = binding.recyclerView,
                direction = View.FOCUS_RIGHT,
                fallback = null
            )
            if (handled) {
                return true
            }
        }
        return focusPrimaryContent()
    }

    private fun focusTopTab(): Boolean {
        return (parentFragment as? MeFragment)?.focusCurrentTab() == true
    }

    private fun restoreContentFocus() {
        if (!isAdded || view == null || binding.recyclerView.visibility != View.VISIBLE) {
            pendingRestoreFocus = false
            returningFromPlayer = false
            if (type == TYPE_HISTORY) {
                pendingHistoryReturnRestore = false
                pendingHistoryScrollToTop = false
            }
            return
        }
        pendingRestoreFocus = false
        if (type == TYPE_HISTORY) {
            pendingHistoryReturnRestore = false
            pendingHistoryScrollToTop = false
        }
        binding.recyclerView.post {
            if (!isAdded || binding.recyclerView.visibility != View.VISIBLE) {
                returningFromPlayer = false
                return@post
            }
            returningFromPlayer = false
            if (type == TYPE_HISTORY) {
                val adapter = historyAdapter ?: return@post
                restoreHistoryViewportAnchor()
                val targetPosition = lastFocusedHistoryKey
                    ?.let(adapter::findPositionByKey)
                    ?.takeIf { it != RecyclerView.NO_POSITION }
                    ?: lastFocusedHistoryPosition
                        .takeIf { it != RecyclerView.NO_POSITION }
                        ?.coerceIn(0, adapter.itemCount - 1)
                    ?: 0
                val handled = tvFocusController?.requestFocusPosition(targetPosition) == true ||
                    requestVisibleHistoryFocus(targetPosition)
                binding.recyclerView.post {
                    restoreHistoryViewportAnchor()
                }
            } else {
                if (tvFocusController?.restoreCapturedAnchor() != true &&
                    tvFocusController?.focusPrimary() != true
                ) {
                    focusPrimaryContent()
                }
            }
        }
    }

    private fun getEmptyMessage(): String {
        return when (type) {
            TYPE_HISTORY -> getString(R.string.history_empty)
            TYPE_LATER -> getString(R.string.later_watch_empty)
            else -> getString(R.string.empty)
        }
    }

    private fun shouldShowRetry(message: String): Boolean {
        return message != getString(R.string.need_sign_in)
    }

    private fun hasContentItems(): Boolean {
        return when (type) {
            TYPE_HISTORY -> (historyAdapter?.itemCount ?: 0) > 0
            TYPE_LATER -> (videoAdapter?.contentCount() ?: 0) > 0
            else -> (binding.recyclerView.adapter?.itemCount ?: 0) > 0
        }
    }

    private fun currentContentCount(): Int {
        return when (type) {
            TYPE_HISTORY -> historyAdapter?.itemCount ?: 0
            TYPE_LATER -> videoAdapter?.contentCount() ?: 0
            else -> binding.recyclerView.adapter?.itemCount ?: 0
        }
    }

    private fun requestItemFocus(position: Int, retries: Int = 6) {
        if (tvFocusController?.requestFocusPosition(position) == true || retries <= 0) {
            return
        }
        binding.recyclerView.post { requestItemFocus(position, retries - 1) }
    }

    private fun requestVisibleHistoryFocus(position: Int, retries: Int = 6): Boolean {
        val holder = binding.recyclerView.findViewHolderForAdapterPosition(position)
        if (holder?.itemView?.requestFocus() == true) {
            return true
        }
        if (retries <= 0) {
            return false
        }
        binding.recyclerView.post {
            requestVisibleHistoryFocus(position, retries - 1)
        }
        return false
    }

    private fun captureHistoryViewportAnchor() {
        val layoutManager = binding.recyclerView.layoutManager as? WrapContentGridLayoutManager ?: return
        val firstVisiblePosition = layoutManager.findFirstVisibleItemPosition()
        if (firstVisiblePosition == RecyclerView.NO_POSITION) {
            return
        }
        val firstVisibleView = layoutManager.findViewByPosition(firstVisiblePosition) ?: return
        pendingHistoryAnchorPosition = firstVisiblePosition
        pendingHistoryAnchorOffset = layoutManager.getDecoratedTop(firstVisibleView) - binding.recyclerView.paddingTop
    }

    private fun restoreHistoryViewportAnchor() {
        val layoutManager = binding.recyclerView.layoutManager as? WrapContentGridLayoutManager ?: return
        if (pendingHistoryAnchorPosition == RecyclerView.NO_POSITION) {
            return
        }
        layoutManager.scrollToPositionWithOffset(pendingHistoryAnchorPosition, pendingHistoryAnchorOffset)
    }

    private fun scrollHistoryListToTopAfterRefresh() {
        pendingHistoryAnchorPosition = RecyclerView.NO_POSITION
        pendingHistoryAnchorOffset = 0
        binding.recyclerView.stopScroll()
        historyAdapter?.clearFocusMemory()
        val shouldFocusFirst = focusToFirstAfterScrollToTop
        focusToFirstAfterScrollToTop = false
        scrollListToTop(immediate = true)
        if (shouldFocusFirst) {
            val rv = binding.recyclerView
            val focused = activity?.currentFocus
            if (focused != null && rv.findContainingItemView(focused) != null) {
                rv.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                    override fun onPreDraw(): Boolean {
                        rv.viewTreeObserver.removeOnPreDrawListener(this)
                        if (isAdded && view != null) {
                            tvFocusController?.requestRefreshFocus(0)
                        }
                        return true
                    }
                })
            }
        } else {
            binding.recyclerView.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
    }

    private fun scrollListToTop(immediate: Boolean) {
        val layoutManager = binding.recyclerView.layoutManager as? WrapContentGridLayoutManager
        if (layoutManager != null) {
            if (immediate) {
                layoutManager.scrollToPositionWithOffset(0, 0)
            } else {
                binding.recyclerView.smoothScrollToPosition(0)
                binding.recyclerView.post {
                    layoutManager.scrollToPositionWithOffset(0, 0)
                }
            }
            return
        }
        if (immediate) {
            tvFocusController?.clearAnchorForUserRefresh()
            binding.recyclerView.scrollToPosition(0)
        } else {
            tvFocusController?.clearAnchorForUserRefresh()
            binding.recyclerView.smoothScrollToPosition(0)
        }
    }

    private fun historyItemKey(item: HistoryVideoModel): String {
        return when {
            item.bvid.isNotBlank() -> "bvid:${item.bvid}"
            (item.history?.oid ?: 0L) > 0L -> "aid:${item.history?.oid}"
            else -> "title:${item.title}|cover:${item.cover}"
        }
    }

    private fun logMeFirstDraw(itemCount: Int, source: String = "network") {
        val openStart = currentOpenStartMs.takeIf { it > 0L } ?: latestRequestStartMs
        if (openStart <= 0L || itemCount <= 0) return
        FirstScreenRenderer.logFirstFrame(
            recyclerView = binding.recyclerView,
            page = pageTag(),
            startMs = openStart,
            itemCount = itemCount,
            source = source
        )
        currentOpenStartMs = 0L
    }

    private fun pageTag(): String = "Me/$type"

    override fun onDestroyView() {
        tvFocusController?.release()
        tvFocusController = null
        super.onDestroyView()
    }

    override fun onVideoBlocked(aid: Long, bvid: String) {
        historyAdapter?.removeByVideoId(aid, bvid)
        videoAdapter?.removeByVideoId(aid, bvid)
    }
}
