@file:Suppress("SpellCheckingInspection")

package com.mytvb.feature.me

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewTreeObserver
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mytvb.R
import com.mytvb.databinding.FragmentMeTabListBinding
import com.mytvb.event.AppEventHub
import com.mytvb.model.video.HistoryVideoModel
import com.mytvb.model.video.VideoModel
import com.mytvb.ui.adapter.HistoryVideoAdapter
import com.mytvb.ui.adapter.VideoAdapter
import com.mytvb.ui.fragment.main.MainNavigationViewModel
import com.mytvb.core.ui.base.BaseFragment
import com.mytvb.core.ui.base.BaseListFragment
import com.mytvb.core.ui.base.VideoRecyclerViewTuning
import com.mytvb.core.ui.base.adaptiveSpanCount
import com.mytvb.core.ui.layout.WrapContentGridLayoutManager
import com.mytvb.core.common.content.ContentFilter
import com.mytvb.core.common.log.AppLog
import com.mytvb.core.common.log.PagePerfLogger
import com.mytvb.core.ui.focus.TabContentFocusHelper
import com.mytvb.core.ui.focus.tv.GridTvFocusStrategy
import com.mytvb.core.ui.focus.tv.TvDataChangeReason
import com.mytvb.core.ui.focus.tv.TvFocusableAdapter
import com.mytvb.core.ui.focus.tv.TvListFocusController
import com.mytvb.core.ui.refresh.SwipeRefreshHelper
import com.mytvb.core.navigation.VideoRouteNavigator
import com.mytvb.core.ui.render.FirstScreenRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class MeListFragment : BaseFragment<FragmentMeTabListBinding>(), MeTabPage, com.mytvb.ui.activity.MainActivity.OnVideoBlockedListener {
    companion object {
        const val TYPE_HISTORY = "history"
        const val TYPE_LATER = "later"
        private const val CACHE_TTL_MS = 10 * 60 * 1000L

        /** 性能打点请求起点的新鲜度上限：超过视为 Flow 重放旧数据，不再用作 elapsed 起点。 */
        private const val REQUEST_START_FRESH_LIMIT_MS = 60_000L

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
    private val mainNavigationViewModel: MainNavigationViewModel by activityViewModels()
    private val viewModel: MeListViewModel by viewModel()
    private var videoAdapter: VideoAdapter? = null
    private var historyAdapter: HistoryVideoAdapter? = null
    private var continuationAdapter: ContinuationCardAdapter? = null
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
    private var lastListLoadedAtMs = 0L

    /**
     * 性能打点用的请求起点（陈旧保护）：
     * 从播放器返回等场景 Flow 会重放旧数据，此时 latestRequestStartMs 仍是上一次请求
     * （可能几分钟前）的时间戳，直接用作 startMs 会打出 elapsed=115428ms 这类误导日志。
     * 超过阈值视为陈旧返回 0（mark 会记 elapsed=0ms），只反映真实请求窗口。
     */
    private fun freshRequestStartMs(): Long =
        latestRequestStartMs.takeIf { it > 0L && PagePerfLogger.now() - it < REQUEST_START_FRESH_LIMIT_MS } ?: 0L
    private var lastTabSelectedAtMs = 0L
    private var lastRenderedHistorySignature = ""
    private var lastRenderedLaterSignature = ""
    private var allowHistoryLoadMore = false
    private var returningFromPlayer = false
    // 播放器返回后的静默进度刷新：渲染时绕过 FirstScreenRenderer（分批会造成列表闪变），
    // 直接 setData 走 DiffUtil 原位更新，不回顶、不动焦点。
    // 注意触发锚点用独立标志：pendingRestoreFocus 会在返回瞬间被 skipDuplicatePayload 的
    // 焦点恢复分支提前消费清零（早于 onResume），挂在它上面永远读不到 true
    private var pendingSilentHistoryRefresh = false
    private var pendingSilentHistoryReturn = false
    private var pendingLaterSilentReturn = false
    private var pendingLaterSilentRefresh = false
    // 静默刷新刚落地的时间：loadUserInfo 网络返回后 renderLoginState 补刷的 refresh
    // 到达时静默标志可能已消费清零，靠短冷却窗口继续让位
    private var lastSilentHistoryAppliedAtMs = 0L

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
                onLeftEdge = ::switchToPrevMeTab,
                onRightEdge = ::switchToNextMeTab,
                onItemFocused = { position ->
                    lastFocusedHistoryPosition = position
                    // 焦点快速下移接近底部时提前一屏触发下一页，别等顶死行底
                    checkHistoryLoadMore()
                },
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
                onLeftEdge = ::switchToPrevMeTab,
                onRightEdge = ::switchToNextMeTab,
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

        val layoutManager = WrapContentGridLayoutManager(requireContext(), resources.adaptiveSpanCount())
        binding.recyclerView.layoutManager = layoutManager
        binding.recyclerView.adapter = historyAdapter ?: videoAdapter
        (historyAdapter ?: videoAdapter)?.let { adapter ->
            VideoRecyclerViewTuning.apply(binding.recyclerView, adapter)
        }
        if (type == TYPE_HISTORY) {
            // 「继续观看」横条：官方同款入口，接口无推荐时保持隐藏
            continuationAdapter = ContinuationCardAdapter(onItemClick = ::onHistoryVideoClick)
            binding.recyclerContinuation.adapter = continuationAdapter
            binding.recyclerContinuation.layoutManager =
                androidx.recyclerview.widget.LinearLayoutManager(
                    requireContext(),
                    androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL,
                    false,
                )
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
                if (type == TYPE_HISTORY) {
                    if (dy > 0) checkHistoryLoadMore()
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

    /**
     * 历史页触底加载统一入口：滚动(dy>0)、焦点落卡、控制器到底行 edge 三处共用。
     * 焦点导航通常 dy=0 不产生滚动事件，必须在 onItemFocused 与数据落地处补查
     * （动态页同款修法），否则长按下键要等焦点顶死行底才触发加载，等待期间
     * pendingMoveAfterLoadMore 吞键、加载链一旦无声焦点就卡死。
     */
    private fun checkHistoryLoadMore() {
        if (type != TYPE_HISTORY || !allowHistoryLoadMore) return
        if (viewModel.loading.value || !viewModel.hasMore.value) return
        val layoutManager = binding.recyclerView.layoutManager as? WrapContentGridLayoutManager ?: return
        val totalItemCount = layoutManager.itemCount
        val lastVisibleItem = layoutManager.findLastVisibleItemPosition()
        if (lastVisibleItem >= totalItemCount - 5) {
            currentPage++
            loadData()
        }
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
        if (restoringFromPlayer || type == TYPE_HISTORY) {
            AppLog.d(
                "MePerf",
                "[history] onResume: restoringFromPlayer=$restoringFromPlayer, " +
                    "pendingSilentReturn=$pendingSilentHistoryReturn, type=$type, " +
                    "hasContent=${hasContentItems()}, loading=${viewModel.loading.value}, " +
                    "pendingSilent=$pendingSilentHistoryRefresh"
            )
        }
        if (pendingRestoreFocus) {
            returningFromPlayer = true
            restoreContentFocus()
        }
        if (pendingSilentHistoryReturn && type == TYPE_HISTORY && hasContentItems()) {
            // 从播放器返回：静默重拉首页按 key 保序合并，只更新被看过卡片的进度
            pendingSilentHistoryReturn = false
            pendingSilentHistoryRefresh = true
            AppLog.d("MePerf", "[history] onResume: trigger silentRefreshHistoryProgress")
            viewModel.silentRefreshHistoryProgress()
        }
        if (pendingLaterSilentReturn && type == TYPE_LATER && hasContentItems()) {
            // 同 history：从播放器返回，静默合并只更新被看过卡片的进度
            pendingLaterSilentReturn = false
            pendingLaterSilentRefresh = true
            AppLog.d("MePerf", "[later] onResume: trigger silentRefreshLaterWatchProgress")
            viewModel.silentRefreshLaterWatchProgress()
        }
        if (type == TYPE_HISTORY || type == TYPE_LATER) {
            val isLoggedIn = viewModel.isLoggedIn()
            if (isLoggedIn && !lastKnownLoggedIn) {
                lastKnownLoggedIn = isLoggedIn
                if (!restoringFromPlayer && !pendingSilentHistoryRefresh) {
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
                viewModel.continuationVideos.collect { list ->
                    if (type != TYPE_HISTORY) return@collect
                    val adapter = continuationAdapter ?: return@collect
                    if (list.isNullOrEmpty()) {
                        binding.continuationContainer.visibility = View.GONE
                    } else {
                        adapter.submitList(list)
                        binding.continuationContainer.visibility = View.VISIBLE
                    }
                }
            }
        }
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
                        // onTabSelected 重拉前置位 FOCUS_BLOCK_DESCENDANTS 防焦点抢入
                        // 半空列表，但恢复只挂在部分渲染分支上：TTL 过期重拉走 setData
                        // 增量分支、错误空态、签名去重跳过等路径都不恢复，列表会永久
                        // 屏蔽焦点（tab 上按 DOWN 无反应）。BLOCK 的生命周期收敛为
                        // 加载窗口，此处所有路径必经，统一解锁。
                        binding.recyclerView.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
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
                                if (!shouldDeferSessionRefresh()) {
                                    refresh()
                                }
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

        // 菜单键 = 刷新（对齐首页 tab 页：MENU 即刷新键）。原 MeFragment 宿主经
        // HostEvent(KEY_MENU_PRESS) 派发的链路已移除，并入首页个人页签后由本页自订阅承接；
        // 两种宿主下行为一致（MeFragment 已删对应 dispatch，不会双发）。
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                mainNavigationViewModel.events.collectLatest { event ->
                    if (isHidden || !isVisible) {
                        return@collectLatest
                    }
                    if (event is MainNavigationViewModel.Event.MenuPressed && !viewModel.loading.value) {
                        refresh()
                    }
                }
            }
        }
    }

    private fun loadData() {
        latestRequestStartMs = PagePerfLogger.now()
        lastListLoadedAtMs = System.currentTimeMillis()
        if (type == TYPE_HISTORY && currentPage <= 1) {
            allowHistoryLoadMore = false
        }
        PagePerfLogger.markNow(
            pageTag(),
            "request_start",
            "page=$currentPage source=${if (currentOpenStartMs > 0L) "open" else "refresh"} hasContent=${hasContentItems()}"
        )
        when (type) {
            TYPE_HISTORY -> {
                viewModel.loadHistory(currentPage, pageSize)
                viewModel.loadHistoryContinuation()
            }
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
        if (type == TYPE_LATER) {
            pendingLaterSilentReturn = true
        }
        tvFocusController?.captureCurrentAnchor()
        VideoRouteNavigator.openVideo(
            context = act,
            video = video,
            playQueue = com.mytvb.ui.activity.PlayerActivity.buildPlayQueue(
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
            pendingSilentHistoryReturn = true
            pendingHistoryReturnRestore = true
            pendingHistoryScrollToTop = false
            tvFocusController?.captureCurrentAnchor()
            captureHistoryViewportAnchor()
            VideoRouteNavigator.openHistory(
                context = act,
                historyVideo = video,
                playQueue = com.mytvb.ui.activity.PlayerActivity.buildPlayQueue(
                    historyAdapter?.getItemsSnapshot().orEmpty().map { it.toVideoModel() },
                    mapped
                )
            )
        }
    }

    private suspend fun bindHistoryData(videos: List<HistoryVideoModel>) {
        AppLog.d("MeDebug", "[history] bindHistoryData: raw=${videos.size}, pendingHistoryScroll=$pendingHistoryScrollToTop, currentPage=$currentPage, adapterCount=${historyAdapter?.contentCount() ?: 0}")
        // 挂起前先捕获本次渲染意图：loading collect 的 settlePendingRefreshAfterNoop
        // 与本函数分属两条 collector 协程，ContentFilter 挂起窗口里 settle 可能抢先
        // 清掉 pending 标志，等函数恢复后取值就是 false 了（回顶/焦点恢复意图丢失）
        val requestedRestoreFocus = pendingHistoryReturnRestore
        val requestedScrollToTop = pendingHistoryScrollToTop
        if (shouldSkipInitialEmptyEmission(videos.size)) {
            PagePerfLogger.markNow(pageTag(), "skip_empty_initial_history")
            return
        }
        val bindStartMs = PagePerfLogger.now()
        PagePerfLogger.mark(
            pageTag(),
            "data_collected",
            freshRequestStartMs(),
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
        val shouldRestoreFocus = requestedRestoreFocus && filtered.isNotEmpty()
        val shouldScrollToTop = requestedScrollToTop && filtered.isNotEmpty()
        val applyStartMs = PagePerfLogger.now()
        if (pendingSilentHistoryRefresh && adapter.contentCount() > 0) {
            // 静默进度刷新：同序同 key 仅内容变，DiffUtil 只派发 onChanged 原位 rebind。
            // 不走 FirstScreenRenderer（截首批+延迟补齐会让已渲染列表闪变），不回顶、不动焦点
            pendingSilentHistoryRefresh = false
            lastSilentHistoryAppliedAtMs = android.os.SystemClock.elapsedRealtime()
            AppLog.d("MeDebug", "[history] silentRefresh applied, items=${filtered.size}, raw=${videos.size}")
            adapter.setData(filtered) {
                PagePerfLogger.mark(
                    pageTag(),
                    "adapter_commit",
                    applyStartMs,
                    "items=${adapter.contentCount()}"
                )
                tvFocusController?.onDataChanged(TvDataChangeReason.REPLACE_PRESERVE_ANCHOR)
            }
        } else if (currentPage <= 1 || adapter.contentCount() == 0) {
            FirstScreenRenderer.render(
                recyclerView = binding.recyclerView,
                page = pageTag(),
                items = filtered,
                startMs = currentOpenStartMs.takeIf { it > 0L } ?: freshRequestStartMs(),
                source = "network",
                spanCount = resources.adaptiveSpanCount(),
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
                    // 追加落地后补查：焦点若仍停在底部附近，立即续拉下一页
                    checkHistoryLoadMore()
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
            freshRequestStartMs(),
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
        if (pendingLaterSilentRefresh && adapter.contentCount() > 0) {
            // 静默进度刷新：同序同 key 仅内容变，DiffUtil 只派发 onChanged 原位 rebind。
            // 不走 FirstScreenRenderer（截首批+延迟补齐会让已渲染列表闪变），不回顶、不动焦点
            pendingLaterSilentRefresh = false
            lastSilentHistoryAppliedAtMs = android.os.SystemClock.elapsedRealtime()
            AppLog.d("MeDebug", "[later] silentRefresh applied, items=${filtered.size}, raw=${videos.size}")
            adapter.setData(filtered) {
                PagePerfLogger.mark(
                    pageTag(),
                    "adapter_commit",
                    applyStartMs,
                    "items=${adapter.contentCount()}"
                )
            }
            updateContentState(filtered.isEmpty())
            return
        }
        if (adapter.contentCount() == 0) {
            FirstScreenRenderer.render(
                recyclerView = binding.recyclerView,
                page = pageTag(),
                items = filtered,
                startMs = currentOpenStartMs.takeIf { it > 0L } ?: freshRequestStartMs(),
                source = "network",
                spanCount = resources.adaptiveSpanCount(),
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
        } else {
            adapter.setData(filtered) {
                PagePerfLogger.mark(
                    pageTag(),
                    "adapter_commit",
                    applyStartMs,
                    "items=${adapter.contentCount()}"
                )
                tvFocusController?.onDataChanged(TvDataChangeReason.REMOVE_ITEM)
            }
        }
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
        // 全量逐项签名（与 MeListViewModel.historyListSignature 同构）：
        // first/last 采样识别不到中间项的进度变化，会把静默刷新的新数据当重复丢弃
        return videos.joinToString(separator = "|") { item ->
            val key = when {
                item.bvid.isNotBlank() -> "bvid:${item.bvid}"
                (item.history?.oid ?: 0L) > 0L -> "aid:${item.history?.oid}"
                else -> "title:${item.title}|cover:${item.cover}"
            }
            "$key:${item.title.hashCode()}:${item.progress}:${item.viewAt}:${item.cover.hashCode()}"
        }
    }

    private fun videoListSignature(videos: List<VideoModel>): String {
        if (videos.isEmpty()) return "empty"
        // 全量逐项签名（与 MeListViewModel.videoListSignature 同构）：
        // first/last 采样识别不到中间项的进度变化，会把静默刷新的新数据当重复丢弃
        return videos.joinToString(separator = "|") { item ->
            val key = when {
                item.bvid.isNotBlank() -> "bvid:${item.bvid}"
                item.aid > 0L -> "aid:${item.aid}"
                else -> "title:${item.title}|cover:${item.coverUrl}"
            }
            "$key:${item.title.hashCode()}:${item.viewCount}:${item.danmakuCount}:${item.historyProgress}:${item.coverUrl.hashCode()}"
        }
    }

    private fun skipDuplicatePayload(rawSize: Int) {
        swipeRefreshLayout?.isRefreshing = false
        PagePerfLogger.markNow(pageTag(), "skip_duplicate_payload", "raw=$rawSize")
        when (type) {
            TYPE_HISTORY -> {
                // 重复 payload 被跳过：静默刷新若已应用/无 diff，标志到此为止，别劫持下次正常刷新
                pendingSilentHistoryRefresh = false
                if (pendingHistoryScrollToTop) {
                    pendingHistoryScrollToTop = false
                    scrollHistoryListToTopAfterRefresh()
                } else if (pendingHistoryReturnRestore) {
                    restoreContentFocus()
                }
            }
            TYPE_LATER -> {
                // 重复 payload 被跳过：静默刷新若已应用/无 diff，标志到此为止，别劫持下次正常刷新
                pendingLaterSilentRefresh = false
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
            strategy = GridTvFocusStrategy.from(binding.recyclerView),
            canLoadMore = {
                when (type) {
                    TYPE_HISTORY -> allowHistoryLoadMore && viewModel.hasMore.value
                    TYPE_LATER -> false
                    else -> false
                }
            },
            loadMore = {
                if (type == TYPE_HISTORY) {
                    checkHistoryLoadMore()
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

    override fun shouldDeferSessionRefresh(): Boolean =
        pendingSilentHistoryRefresh ||
            pendingLaterSilentReturn ||
            pendingLaterSilentRefresh ||
            (lastSilentHistoryAppliedAtMs > 0L && android.os.SystemClock.elapsedRealtime() - lastSilentHistoryAppliedAtMs < 2_500L)

    override fun refresh() {
        returningFromPlayer = false
        currentPage = 1
        pendingRestoreFocus = false
        pendingHistoryReturnRestore = false
        pendingSilentHistoryReturn = false
        pendingSilentHistoryRefresh = false
        pendingLaterSilentReturn = false
        pendingLaterSilentRefresh = false
        lastSilentHistoryAppliedAtMs = 0L
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
            TYPE_HISTORY -> {
                // 历史记录：每次选中都拉最新（从其它主 tab 切回 / 切到历史 tab 均触发），
                // 不再做 TTL 内保状态；播放器返回保焦点的 early-return 在上方，不受影响
                pendingSilentHistoryReturn = false
                pendingSilentHistoryRefresh = false
                pendingHistoryScrollToTop = true
                tvFocusController?.clearAnchorForUserRefresh()
                binding.recyclerView.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                AppLog.d("MeDebug", "[$type] onTabSelected: reload latest, pendingHistoryScroll=$pendingHistoryScrollToTop")
                currentPage = 1
                loadData()
            }
            TYPE_LATER -> {
                // 稍后再看：内容仍新鲜（未过 TTL）时保留滚动位置/焦点原样，不回顶重拉
                pendingLaterSilentReturn = false
                pendingLaterSilentRefresh = false
                val listFresh = hasContentItems() &&
                        System.currentTimeMillis() - lastListLoadedAtMs < CACHE_TTL_MS
                if (listFresh) {
                    PagePerfLogger.markNow(pageTag(), "tab_selected_keep_state")
                    AppLog.d("MeDebug", "[$type] onTabSelected: keep state (fresh within TTL)")
                    return
                }
                pendingLaterScrollToTop = true
                tvFocusController?.clearAnchorForUserRefresh()
                binding.recyclerView.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                AppLog.d("MeDebug", "[$type] onTabSelected: network_only loadData, pendingLaterScroll=$pendingLaterScrollToTop")
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
        // 从侧栏按右进入：恒定位第一行第一个，不再按锚点(「我的」按钮偏下)做空间
        // 搜索——空间搜索会落到与按钮同一水平线的第二行卡片（v2.1.14 反馈），
        // 与其他页面"进入即第一行第一项"的口径不一致
        return focusPrimaryContent()
    }

    private fun focusTopTab(): Boolean {
        // UP 顶行落"当前选中的二级 tab"（不切页），与首页一致
        // （宿主可能是 MeFragment 或并入个人页签的 HomeFragment，走 SecondaryTabHost 接口）
        return (parentFragment as? com.mytvb.core.ui.tab.SecondaryTabHost)?.focusSelectedTabFromContent() == true
    }

    /** tab 栏 DOWN 的落点：就近聚焦（锚点优先），位置保持不动，半截轻滚补齐。 */
    override fun focusNearestVisibleContent(): Boolean {
        if (!isAdded || view == null) return false
        val rv = binding.recyclerView
        val lm = rv.layoutManager as? LinearLayoutManager ?: return false
        val handled = focusPrimaryContent()
        com.mytvb.core.common.log.AppLog.d("DownFocus", "[me] focusPrimary=$handled")
        // 锚点恢复的滚动是 pending（会覆盖任何提前的补滚），必须布局完成后校验最终焦点卡：
        // 顶部被裁 → 列表下移补全；底部被裁 → 列表上移补全。scrollBy 同步滚动，
        // 不会被后续布局/恢复链覆盖。
        rv.post {
            if (!isAdded || view == null) return@post
            val focused = rv.findFocus()
            if (focused == null) return@post
            // 以聚焦视图自身（而非 containing item）计算补偿，与 BaseListFragment
            // 同口径：item 高于视口时按 item 补偿会把顶部内容整段顶出屏
            val focusRect = android.graphics.Rect()
            focused.getDrawingRect(focusRect)
            rv.offsetDescendantRectToMyCoords(focused, focusRect)
            val padTop = rv.paddingTop
            val visibleBottom = rv.height - rv.paddingBottom
            val delta = when {
                focusRect.top < padTop -> focusRect.top - padTop
                focusRect.bottom > visibleBottom -> focusRect.bottom - visibleBottom
                else -> 0
            }
            com.mytvb.core.common.log.AppLog.d(
                "DownFocus",
                "[me] post focusTop=${focusRect.top} focusBottom=${focusRect.bottom} padTop=$padTop visibleBottom=$visibleBottom delta=$delta"
            )
            if (delta != 0) {
                rv.scrollBy(0, delta)
                rv.post {
                    val c2 = rv.findContainingItemView(rv.findFocus() ?: return@post)
                    com.mytvb.core.common.log.AppLog.d("DownFocus", "[me] verify top=${c2?.top} bottom=${c2?.bottom}")
                }
            }
        }
        return handled
    }

    /** 重选 tab 的回顶：滚动到顶并聚焦第一项。 */
    override fun scrollToTopAndFocus(): Boolean {
        if (!isAdded || view == null) return false
        scrollListToTop(immediate = true)
        val rv = binding.recyclerView
        val controller = tvFocusController ?: return false
        rv.post {
            if (isAdded && view != null) {
                controller.requestRefreshFocus(0)
            }
        }
        return true
    }

    private fun switchToPrevMeTab(): Boolean {
        return (parentFragment as? com.mytvb.core.ui.tab.SecondaryTabHost)?.switchAdjacentTabFromContentEdge(-1) == true
    }

    private fun switchToNextMeTab(): Boolean {
        return (parentFragment as? com.mytvb.core.ui.tab.SecondaryTabHost)?.switchAdjacentTabFromContentEdge(1) == true
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
                val controller = tvFocusController
                if (controller == null) {
                    requestVisibleHistoryFocus(targetPosition)
                } else {
                    // 统一走带仲裁的恢复入口：controller 锚点（含 stableKey）优先，
                    // 转场轮询 / 数据落地等待 / 用户导航中止均由其内部处理，
                    // 且恢复窗口内 onDataChanged 不会与其互派重试链（返回焦点风暴根因）。
                    controller.restoreFocusAfterReturn(
                        onFailed = { requestVisibleHistoryFocus(targetPosition) }
                    )
                }
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
        val openStart = currentOpenStartMs.takeIf { it > 0L } ?: freshRequestStartMs()
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
