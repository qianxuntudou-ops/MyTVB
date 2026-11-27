package com.mytvb.feature.detail

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup

import androidx.activity.OnBackPressedCallback
import androidx.annotation.StringRes
import androidx.core.os.bundleOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mytvb.R
import com.mytvb.databinding.FragmentUserSpaceBinding
import com.mytvb.event.AppEventHub
import com.mytvb.model.user.CheckRelationModel
import com.mytvb.model.user.UserSpaceInfo
import com.mytvb.model.video.VideoModel
import com.mytvb.model.video.toVideoModel
import com.mytvb.network.session.NetworkSessionGateway
import com.mytvb.repository.UserRepository
import com.mytvb.ui.adapter.UserSpaceHeaderAdapter
import com.mytvb.ui.adapter.VideoAdapter
import com.mytvb.core.ui.base.BaseAdapter
import com.mytvb.core.ui.base.BaseFragment
import com.mytvb.core.ui.base.BaseListFragment
import com.mytvb.core.ui.base.ChoiceDialog
import com.mytvb.core.ui.base.VideoRecyclerViewTuning
import com.mytvb.core.ui.base.adaptiveSpanCount
import com.mytvb.core.ui.system.ViewUtils
import com.mytvb.feature.user.FollowUserListFragment
import com.mytvb.feature.player.progress.PlaybackProgressStore
import com.mytvb.core.ui.layout.WrapContentGridLayoutManager
import com.mytvb.core.ui.decoration.GridSpacingItemDecoration
import com.mytvb.core.common.content.ContentFilter
import com.mytvb.core.ui.focus.hasFocusInChildren
import com.mytvb.core.ui.focus.tv.GridTvFocusStrategy
import com.mytvb.core.ui.focus.tv.OffsetTvFocusableAdapter
import com.mytvb.core.ui.focus.tv.TvDataChangeReason
import com.mytvb.core.ui.focus.tv.TvListFocusController
import com.mytvb.core.navigation.VideoRouteNavigator
import com.mytvb.core.ui.refresh.SwipeRefreshHelper
import com.mytvb.core.common.log.AppLog
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import com.mytvb.core.ui.base.AppToast

class UserSpaceFragment : BaseFragment<FragmentUserSpaceBinding>(), com.mytvb.ui.activity.MainActivity.OnVideoBlockedListener {

    // 超宽屏（如 5120×1600）4 列封面过大，adaptiveSpanCount 统一放宽到 8 列
    private val spanCount by lazy { resources.adaptiveSpanCount() }

    private enum class FocusArea {
        BACK,
        FOLLOW,
        FOLLOWING,
        FOLLOWER,
        FILTER,
        CONTENT
    }

    companion object {
        private const val TAG = "UserSpace"
        private const val ARG_MID = "mid"

        // 客户端拉全排序（合集/系列最多播放）的翻页上限
        private const val MAX_SEARCH_PAGES = 10

        // 动态视频 tab 单次加载最多翻的原始动态页数（issue#151，feed/space 一页 12 条）
        private const val MAX_RAW_PAGES_PER_FETCH = 4

        // B 站把直播回放投稿归为 UP 主名下自动生成的系列，名字模板"{UP名}的直播回放"
        private const val LIVE_REPLAY_SERIES_KEYWORD = "直播回放"

        fun newInstance(mid: Long): UserSpaceFragment {
            return UserSpaceFragment().apply {
                arguments = bundleOf(ARG_MID to mid)
            }
        }
    }

    private var mid: Long = 0
    private var userSpaceInfo: UserSpaceInfo? = null

    private val appEventHub: AppEventHub by inject()
    private val sessionGateway: NetworkSessionGateway by inject()
    private val userRepository: UserRepository by inject()
    private lateinit var headerAdapter: UserSpaceHeaderAdapter
    private lateinit var videoAdapter: VideoAdapter
    private lateinit var concatAdapter: ConcatAdapter

    private var isLoading = false
    private var relationAttribute = 0
    private var lastFocusedArea = FocusArea.BACK
    private var lastFocusedVideoPosition = RecyclerView.NO_POSITION
    private var hasRequestedInitialFocus = false
    private var tvFocusController: TvListFocusController? = null
    private var swipeRefreshLayout: androidx.swiperefreshlayout.widget.SwipeRefreshLayout? = null

    /** 列表 tab 类型：投稿视频 / 动态视频 / 系列（直播回放同此）/ 合集 —— 与弹窗（OwnerDetailDialog）同款 */
    private enum class TabKind { VIDEO, DYNAMIC_VIDEO, SERIES, SEASON }

    /**
     * 全局排序（弹窗同款四项）：
     * 最新发布=视频 pubdate+合集系列最新在前；最早发布=合集系列正序、投稿流末页倒翻（接口无正序）；
     * 最多播放=视频 click，合集系列接口不支持→客户端拉全按 stat.view 排；最多收藏仅视频支持。
     */
    private enum class OwnerListOrder(@StringRes val labelRes: Int) {
        NEWEST(R.string.model_search_order_pub_date),
        MOST_VIEWED(R.string.space_order_most_played),
        MOST_FAVORITE(R.string.model_search_order_most_collection),
        OLDEST(R.string.space_sort_oldest_first)
    }

    /** 每个 tab 独立的列表状态：切 tab 铺回已加载数据（不恢复滚动位置，列表回顶）。 */
    private inner class TabState(
        val kind: TabKind,
        val title: String,
        val seriesId: Long = 0L,
        val seasonId: Long = 0L
    ) {
        var items: MutableList<VideoModel> = mutableListOf()
        var page: Int = 1
        var hasMore: Boolean = true
        var loading: Boolean = false
        var loadedFirstPage: Boolean = false
        // 投稿 tab「最早发布」倒翻模式缓存的总投稿数
        var totalCount: Int = 0
        // 动态视频 tab（issue#151）的 feed/space 翻页游标：接口只认 offset，page 不参与
        var dynamicOffset: Long? = null
    }

    private val tabStates = mutableListOf<TabState>()
    private val videoTabState: TabState get() = tabStates[0]
    private var currentIndex = 0
    private var order = OwnerListOrder.NEWEST

    // 刷新代际：切排序/tab 时作废在飞请求与过期响应，防止旧回调把数据写进新列表或吞掉新请求
    private var loadGeneration = 0

    private fun activeState(): TabState = tabStates.getOrNull(currentIndex) ?: videoTabState

    private fun needsClientSideSort(state: TabState): Boolean =
        state.kind != TabKind.VIDEO && state.kind != TabKind.DYNAMIC_VIDEO &&
            order == OwnerListOrder.MOST_VIEWED

    private fun oldestMode(state: TabState): Boolean =
        state.kind == TabKind.VIDEO && order == OwnerListOrder.OLDEST

    /** 有服务端进度（历史入口等）则尊重原值，否则用本地 bvid 兜底键进度（ms→秒）。 */
    private fun fillLocalWatchProgress(video: VideoModel): VideoModel {
        if (video.historyProgress > 0L || video.bvid.isBlank()) return video
        val localProgressSec = PlaybackProgressStore.getPosition(video.bvid) / 1000L
        if (localProgressSec <= 0L) return video
        return video.copy(historyProgress = localProgressSec)
    }

    /** 触底翻页方向：倒翻模式页码递减，其余递增。 */
    private fun advancePage() {
        val state = activeState()
        if (oldestMode(state)) state.page-- else state.page++
    }

    override fun onResume() {
        super.onResume()
        restoreFocus()
    }

    override fun getViewBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentUserSpaceBinding {
        return FragmentUserSpaceBinding.inflate(inflater, container, false)
    }

    override fun initArguments() {
        mid = arguments?.getLong(ARG_MID) ?: 0
    }

    override fun initView() {
        headerAdapter = UserSpaceHeaderAdapter(
            onFollowClick = ::doFollow,
            onFollowingClick = { openFollowList(FollowUserListFragment.TYPE_FOLLOWING) },
            onFollowerClick = { openFollowList(FollowUserListFragment.TYPE_FOLLOWER) },
            onHeaderFocusChanged = { target ->
                lastFocusedArea = when (target) {
                    UserSpaceHeaderAdapter.FocusTarget.FOLLOW -> FocusArea.FOLLOW
                    UserSpaceHeaderAdapter.FocusTarget.FOLLOWING -> FocusArea.FOLLOWING
                    UserSpaceHeaderAdapter.FocusTarget.FOLLOWER -> FocusArea.FOLLOWER
                    UserSpaceHeaderAdapter.FocusTarget.FILTER -> FocusArea.FILTER
                }
            },
            onMoveToContent = {
                requestContentFocus(lastFocusedVideoPosition)
            },
            onOrderClick = ::showOrderDialog,
            onTabSelected = { index -> performSwitch(index) }
        )
        videoAdapter = VideoAdapter(
            onTopEdgeUp = ::focusHeaderFromContent,
            onItemFocused = { position ->
                lastFocusedArea = FocusArea.CONTENT
                lastFocusedVideoPosition = position
            },
            onItemFocusedWithView = { view, position ->
                tvFocusController?.onItemFocused(view, headerAdapter.itemCount + position)
            },
            onItemDpad = { view, keyCode, event ->
                tvFocusController?.handleKey(view, keyCode, event) == true
            },
            onItemsChanged = {
                tvFocusController?.onDataChanged(TvDataChangeReason.REMOVE_ITEM)
            }
        )
        concatAdapter = ConcatAdapter(
            ConcatAdapter.Config.Builder()
                .setIsolateViewTypes(false)
                .build(),
            headerAdapter,
            videoAdapter
        )

        val layoutManager = WrapContentGridLayoutManager(requireContext(), spanCount)
        layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                return when {
                    position == 0 -> spanCount
                    concatAdapter.getItemViewType(position) == BaseAdapter.LOAD_MORE_TYPE -> spanCount
                    else -> 1
                }
            }
        }
        binding.recyclerViewVideos.layoutManager = layoutManager
        binding.recyclerViewVideos.adapter = concatAdapter
        VideoRecyclerViewTuning.apply(binding.recyclerViewVideos, videoAdapter)
        if (binding.recyclerViewVideos.itemDecorationCount == 0) {
            binding.recyclerViewVideos.addItemDecoration(
                GridSpacingItemDecoration(
                    spanCount,
                    resources.getDimensionPixelSize(R.dimen.px20),
                    includeEdge = true
                )
            )
        }
        binding.recyclerViewVideos.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                val gridLayoutManager = recyclerView.layoutManager as? WrapContentGridLayoutManager ?: return
                val lastVisibleItem = gridLayoutManager.findLastVisibleItemPosition()
                if (!isLoading && activeState().hasMore && lastVisibleItem >= concatAdapter.itemCount - 6) {
                    advancePage()
                    loadTabPage(activeState())
                }
            }
        })
        installTvFocusController()
        swipeRefreshLayout = SwipeRefreshHelper.wrapRecyclerView(
            recyclerView = binding.recyclerViewVideos,
            onRefresh = ::refreshUserSpace
        )

        videoAdapter.setOnItemClickListener { _, item ->
            if (item.aid != 0L || item.bvid.isNotBlank()) {
                VideoRouteNavigator.openVideo(
                    context = requireContext(),
                    video = item,
                    playQueue = com.mytvb.ui.activity.PlayerActivity.buildPlayQueue(
                        videoAdapter.getItemsSnapshot(),
                        item
                    )
                )
            }
        }

        binding.buttonBack.setOnClickListener {
            parentFragmentManager.popBackStack()
        }
        binding.buttonBack.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) {
                return@setOnKeyListener false
            }
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_RIGHT -> focusHeaderFromContent()
                else -> false
            }
        }
        binding.buttonBack.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                lastFocusedArea = FocusArea.BACK
            }
        }

        // BACK 两层：焦点不在列表 tab 上时先回 tab；已在 tab 上才退出页面
        // （Fragment 的 lifecycle callback 晚于 Activity 兜底注册，优先拦截）
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (headerAdapter.isTabsFocused()) {
                        parentFragmentManager.popBackStack()
                    } else {
                        focusTabsForBackExit(retries = 6)
                    }
                }
            }
        )
    }

    override fun initData() {
        tabStates.add(TabState(TabKind.VIDEO, getString(R.string.video)))
        tabStates.add(TabState(TabKind.DYNAMIC_VIDEO, getString(R.string.dynamic_video)))
        headerAdapter.submitTabs(listOf(videoTabState.title, tabStates[1].title))
        loadUserInfo()
        loadUserStat()
        loadTabPage(videoTabState)
        loadCollectionTabs()
        requestHeaderFocus(FocusArea.BACK)
    }

    /**
     * 探测 UP 主的系列与合集（x/polymer/web-space/seasons_series_list，跨页聚合取全），
     * 每个系列/合集各建一个 tab：直播回放自动系列排最前，其后系列与合集（弹窗同款）。
     */
    private fun loadCollectionTabs() {
        if (mid == 0L) return
        lifecycleScope.launch {
            userRepository.getSeasonsSeriesAll(mid)
                .onSuccess { bundle ->
                    val seriesList = bundle.series
                    val seasonsList = bundle.seasons
                    AppLog.i(
                        "UserSpace",
                        "collections: total=${bundle.totalCount} seasons=${seasonsList.size} series=${seriesList.size}"
                    )
                    val replay = seriesList.firstOrNull {
                        it.seriesId > 0 && it.name.contains(LIVE_REPLAY_SERIES_KEYWORD)
                    }
                    val newTitles = mutableListOf<String>()
                    if (replay != null && replay.seriesId > 0) {
                        tabStates.add(TabState(TabKind.SERIES, replay.name, seriesId = replay.seriesId))
                        newTitles.add(replay.name)
                    }
                    seriesList.forEach { item ->
                        if (item.seriesId > 0 && item !== replay) {
                            tabStates.add(TabState(TabKind.SERIES, item.name, seriesId = item.seriesId))
                            newTitles.add(item.name)
                        }
                    }
                    seasonsList.forEach { item ->
                        if (item.seasonId > 0) {
                            tabStates.add(TabState(TabKind.SEASON, item.name, seasonId = item.seasonId))
                            newTitles.add(item.name)
                        }
                    }
                    if (newTitles.isNotEmpty()) {
                        headerAdapter.submitTabs(tabStates.map { it.title })
                    }
                }
                .onFailure {
                    AppLog.w("UserSpace", "collections probe failed: ${it.message}")
                }
        }
    }

    /** 用户点 tab 切换：焦点留在 tab 上、列表铺回已加载数据并回顶（DOWN 落新列表第一个）。 */
    private fun performSwitch(index: Int) {
        if (index == currentIndex || index < 0 || index >= tabStates.size) return
        currentIndex = index
        headerAdapter.selectTab(index)
        // 焦点口径切到筛选区：丢弃旧 tab 的卡片焦点缓存（DOWN 进列表落第一个），
        // 并阻止首屏加载完成回调把焦点拉进列表
        lastFocusedVideoPosition = RecyclerView.NO_POSITION
        lastFocusedArea = FocusArea.FILTER
        val state = activeState()
        videoAdapter.setShowLoadMore(state.hasMore)
        videoAdapter.setData(state.items) {
            tvFocusController?.onDataChanged(TvDataChangeReason.REPLACE_PRESERVE_ANCHOR)
        }
        binding.recyclerViewVideos.scrollToPosition(0)
        updateEmptyState()
        maybeLoadActiveTabFirstPage()
    }

    /** 空 tab 提示：当前 tab 首屏加载完成且无内容时显示（加载中/失败/客户端排序拉全中/有内容都隐藏）。 */
    private fun updateEmptyState() {
        val state = activeState()
        // 动态视频 tab 用专属文案（「暂无内容」在该语境下会让人分不清是没视频还是加载失败）
        binding.tvEmpty.setText(
            if (state.kind == TabKind.DYNAMIC_VIDEO) R.string.no_dynamic_video else R.string.empty
        )
        binding.tvEmpty.visibility = if (
            state.loadedFirstPage && state.items.isEmpty() &&
            !(needsClientSideSort(state) && state.hasMore)
        ) View.VISIBLE else View.GONE
    }

    /** 当前 tab 首屏还没加载就补发。 */
    private fun maybeLoadActiveTabFirstPage() {
        val state = activeState()
        if (state.loading || !state.hasMore || state.loadedFirstPage || state.items.isNotEmpty()) return
        loadTabPage(state)
    }

    override fun initObserver() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                appEventHub.events.collectLatest { event ->
                    if (event == AppEventHub.Event.UserSessionChanged && !isHidden) {
                        loadUserInfo()
                        loadUserStat()
                        resetAllTabsForOrderChange(scrollToTop = false)
                    }
                }
            }
        }
    }

    private fun loadUserInfo() {
        if (mid == 0L) return

        binding.progressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val response = userRepository.getUserSpace(mid)
                binding.progressBar.visibility = View.GONE
                if (response.isSuccess) {
                    response.data?.let { info ->
                        userSpaceInfo = info
                        updateUserInfo(info)
                        if (sessionGateway.isLoggedIn() && sessionGateway.getUserInfo()?.mid != mid) {
                            loadRelationState()
                        }
                    }
                } else if (response.code != -101) {
                    AppToast.show(requireContext(), response.errorMessage)
                }
            } catch (e: Exception) {
                binding.progressBar.visibility = View.GONE
                AppToast.show(requireContext(), getString(R.string.load_failed_format, e.message))
            }
        }
    }

    /** 一页数据的解析结果（含翻页判定与投稿总数）。 */
    private data class TabPage(
        val videos: List<VideoModel>,
        val hasMore: Boolean,
        val totalCount: Int = 0
    )

    private suspend fun fetchTabPage(state: TabState, page: Int): TabPage =
        when (state.kind) {
            TabKind.VIDEO -> {
                // 「最早发布」：投稿接口无正序参数，跳末页倒着翻（页内反转在回调处理）
                if (oldestMode(state)) {
                    // 首屏 = 总数尚未探得。不能用 page<=1 判首屏：倒翻推进到第 1 页时
                    // 会被换成末页重拉，页码在末页与次页间震荡，列表反复 REPLACE 回顶
                    val probing = state.totalCount <= 0
                    if (probing) {
                        runCatching {
                            userRepository.getUserDynamic(mid, 1, 20).getOrThrow().data
                        }.getOrNull()?.let { state.totalCount = it.totalCount }
                    }
                    val maxPage = ((state.totalCount + 19) / 20).coerceAtLeast(1)
                    val pn = if (probing) maxPage else page.coerceAtMost(maxPage)
                    state.page = pn
                    AppLog.d("UserSpace", "fetch oldest pn=$pn total=${state.totalCount}")
                    val data = userRepository.getUserDynamic(mid, pn, 20).getOrThrow().data
                    TabPage(data?.archives.orEmpty(), pn > 1, data?.totalCount ?: 0)
                } else {
                    val orderValue = when (order) {
                        OwnerListOrder.MOST_VIEWED -> "click"
                        OwnerListOrder.MOST_FAVORITE -> "stow"
                        else -> "pubdate"
                    }
                    AppLog.d("UserSpace", "fetch video pn=$page order=$orderValue")
                    val data = userRepository.getUserDynamic(mid, page, 20, orderValue).getOrThrow().data
                    TabPage(data?.archives.orEmpty(), data?.hasMore ?: false, data?.totalCount ?: 0)
                }
            }
            TabKind.DYNAMIC_VIDEO -> {
                // 动态视频稀疏（动态流里图文/转发占绝大多数）：单次调用内连续翻原始页，
                // 直到命中一批或吸收 MAX_RAW_PAGES_PER_FETCH 页；游标存 state.dynamicOffset
                // 供触底续翻（feed/space 只认 offset，state.page 不参与请求）
                var collected: List<VideoModel> = emptyList()
                var hasMore = true
                var rawPages = 0
                while (rawPages < MAX_RAW_PAGES_PER_FETCH) {
                    val offset = state.dynamicOffset
                    if (rawPages > 0 && (offset == null || offset <= 0L)) {
                        // 游标缺失即到头：不带 offset 的请求会重复下发第一页
                        hasMore = false
                        break
                    }
                    val data = userRepository.getUpDynamicFeed(mid, page = state.page, offset = offset)
                        .getOrThrow().data ?: break
                    rawPages++
                    val hits = data.items.mapNotNull { it.toVideoModel() }
                        .filter { it.dynamicBadgeText.isNotBlank() }
                    collected = collected + hits
                    hasMore = data.hasMore && data.offset > 0L
                    if (hasMore) state.dynamicOffset = data.offset
                    if (collected.isNotEmpty()) break
                }
                AppLog.d(TAG, "fetch dynamic-video rawPages=$rawPages hits=${collected.size} hasMore=$hasMore")
                TabPage(collected, hasMore)
            }
            TabKind.SERIES -> {
                val data = userRepository.getSeriesArchives(
                    mid, seriesId = state.seriesId, page = page, pageSize = 20,
                    sortDesc = order != OwnerListOrder.OLDEST
                ).getOrThrow().data ?: error("empty data")
                AppLog.d(TAG, "fetch series pn=$page seriesId=${state.seriesId} archives=${data.archives.size}")
                TabPage(data.archives, data.hasMore)
            }
            TabKind.SEASON -> {
                // seasons_archives_list 的 page 只带 total（无 has_more），按满页判定翻页
                val response = userRepository.getSeasonArchives(
                    mid, seasonId = state.seasonId, page = page, pageSize = 20,
                    sortDesc = order != OwnerListOrder.OLDEST
                )
                val base = response.getOrThrow()
                val data = base.data
                val videos = data?.archives.orEmpty()
                AppLog.d(
                    "UserSpace",
                    "fetch season pn=$page seasonId=${state.seasonId} code=${base.code} " +
                        "total=${data?.totalCount} archives=${videos.size}"
                )
                TabPage(videos, videos.size >= 20)
            }
        }

    private fun loadTabPage(state: TabState, force: Boolean = false) {
        if (mid == 0L || !isAdded) return
        if (force) {
            // 刷新作废在飞请求：代际 +1，旧回调回来直接丢弃（否则会吞掉本次刷新或污染新列表）
            loadGeneration++
            isLoading = false
            state.loading = false
        } else if (state.loading || !state.hasMore) return

        val pageToLoad = state.page
        state.loading = true
        isLoading = true
        binding.progressBar.visibility = View.VISIBLE
        val gen = loadGeneration

        lifecycleScope.launch {
            val result = runCatching { fetchTabPage(state, pageToLoad) }
            val stillCurrent = tabStates.indexOf(state) == currentIndex
            result.onSuccess { pageData ->
                if (gen != loadGeneration) return@onSuccess
                binding.progressBar.visibility = View.GONE
                swipeRefreshLayout?.isRefreshing = false
                isLoading = false
                state.loading = false

                val videos = ContentFilter.filterVideos(requireContext(), pageData.videos)
                    .let { if (oldestMode(state)) it.reversed() else it }
                    // 本地播放进度填充（不发请求的本地易判断项）：空间接口不带进度，
                    // 用 PlaybackProgressStore 的 bvid 兜底键显示「看到哪了」（>95% 已看完的由 Store 清除不显示）
                    .map(::fillLocalWatchProgress)
                state.hasMore = pageData.hasMore
                // 首批判定只看列表是否已空：倒翻末批页码是 1，若按页码判会走 REPLACE 重置滚动位置
                val isFirstBatch = state.items.isEmpty()
                if (isFirstBatch) {
                    state.loadedFirstPage = true
                    state.items = videos.toMutableList()
                } else {
                    state.items.addAll(videos)
                }
                if (state.kind == TabKind.VIDEO && pageData.totalCount > 0) {
                    headerAdapter.updateVideoCount(pageData.totalCount)
                }
                headerAdapter.updateOrderLabel(order.labelRes)
                // 合集/系列 + 最多播放：接口不支持播放排序，翻页拉全后客户端按 stat.view 排序
                if (needsClientSideSort(state)) {
                    if (state.hasMore && state.page < MAX_SEARCH_PAGES) {
                        state.page++
                        loadTabPage(state)
                        return@onSuccess
                    }
                    val sorted = state.items.sortedByDescending { it.stat?.view ?: it.play }
                    state.items = sorted.toMutableList()
                    state.hasMore = false
                if (stillCurrent) {
                    videoAdapter.setData(state.items) {
                        tvFocusController?.onDataChanged(TvDataChangeReason.REPLACE_PRESERVE_ANCHOR)
                    }
                    videoAdapter.setShowLoadMore(false)
                    updateEmptyState()
                }
                return@onSuccess
            }
            if (stillCurrent) {
                if (isFirstBatch) {
                    videoAdapter.setShowLoadMore(state.hasMore)
                    videoAdapter.setData(state.items) {
                        tvFocusController?.onDataChanged(TvDataChangeReason.REPLACE_PRESERVE_ANCHOR)
                    }
                } else {
                    videoAdapter.addData(videos)
                    videoAdapter.setShowLoadMore(state.hasMore)
                    tvFocusController?.onDataChanged(TvDataChangeReason.APPEND)
                }
                updateEmptyState()
                if (!hasRequestedInitialFocus && isFirstBatch) {
                    hasRequestedInitialFocus = true
                    requestHeaderFocus(FocusArea.BACK)
                } else if (lastFocusedArea == FocusArea.CONTENT) {
                    requestContentFocus(lastFocusedVideoPosition)
                }
            }
            }.onFailure { e ->
                if (gen != loadGeneration) return@onFailure
                binding.progressBar.visibility = View.GONE
                swipeRefreshLayout?.isRefreshing = false
                isLoading = false
                state.loading = false
                rollbackPage(state)
                AppLog.w("UserSpace", "tab=${state.title} page=$pageToLoad load failed: ${e.message}")
                AppToast.show(requireContext(), getString(R.string.load_failed_format, e.message))
                maybeLoadActiveTabFirstPage()
            }
        }
    }

    private fun updateUserInfo(info: UserSpaceInfo) {
        val isSelf = sessionGateway.getUserInfo()?.mid == mid
        headerAdapter.updateUserInfo(info, showFollow = !isSelf)
        if (isSelf || !sessionGateway.isLoggedIn()) {
            relationAttribute = 0
            renderFollowButton()
        }
    }

    private fun loadUserStat() {
        if (mid == 0L) {
            headerAdapter.updateStat(null, null)
            return
        }

        lifecycleScope.launch {
            try {
                val isSelf = sessionGateway.getUserInfo()?.mid == mid
                val response = if (isSelf) {
                    userRepository.getUserStat()
                } else {
                    userRepository.getRelationStat(mid)
                }
                val stat = response.data
                if (response.isSuccess && stat != null) {
                    headerAdapter.updateStat(stat.following, stat.follower)
                } else {
                    headerAdapter.updateStat(null, null)
                }
            } catch (_: Exception) {
                headerAdapter.updateStat(null, null)
            }
        }
    }

    private fun openFollowList(type: Int) {
        if (mid > 0L) {
            openInHostContainer(FollowUserListFragment.newInstance(mid, type))
        }
    }

    /** 全局排序变化/整页刷新：重置所有 tab 的列表状态，当前 tab 立即重载，其余切入时补发首屏。 */
    private fun resetAllTabsForOrderChange(scrollToTop: Boolean) {
        lastFocusedVideoPosition = RecyclerView.NO_POSITION
        tabStates.forEach { s ->
            s.items.clear()
            s.page = 1
            s.hasMore = true
            s.loadedFirstPage = false
            s.totalCount = 0
            s.dynamicOffset = null
        }
        // 清空旧列表：倒翻首批页码非 1，首批判定依赖列表已清空（否则末页数据被追加到旧列表尾部）
        videoAdapter.setData(emptyList())
        tvFocusController?.clearAnchorForUserRefresh()
        updateEmptyState()
        if (scrollToTop) {
            binding.recyclerViewVideos.scrollToPosition(0)
        }
        loadTabPage(activeState(), force = true)
    }

    private fun showOrderDialog() {
        val orders = OwnerListOrder.values()
        val labels = orders.map { getString(it.labelRes) }
        ChoiceDialog.show(
            context = requireContext(),
            title = getString(R.string.video_order),
            options = labels,
            selectedIndex = orders.indexOf(order)
        ) { which ->
            val newOrder = orders[which]
            if (newOrder != order) {
                order = newOrder
                headerAdapter.updateOrderLabel(newOrder.labelRes)
                resetAllTabsForOrderChange(scrollToTop = true)
            }
        }
        activity?.let { ViewUtils.hideSystemBars(it) }
    }

    private fun refreshUserSpace() {
        loadUserInfo()
        loadUserStat()
        resetAllTabsForOrderChange(scrollToTop = false)
    }

    private fun rollbackPage(state: TabState) {
        // 请求失败回滚页码（与 advancePage 反向）
        if (oldestMode(state)) {
            state.page++
        } else if (state.page > 1) {
            state.page--
        }
    }

    private fun loadRelationState() {
        if (!sessionGateway.isLoggedIn() || sessionGateway.getUserInfo()?.mid == mid) {
            AppLog.i(TAG, "loadRelationState skip: loggedIn=${sessionGateway.isLoggedIn()} selfMid=${sessionGateway.getUserInfo()?.mid} targetMid=$mid")
            return
        }
        lifecycleScope.launch {
            userRepository.checkUserRelation(mid)
                .onSuccess { response ->
                    AppLog.i(TAG, "loadRelationState result: code=${response.code} attr=${response.data?.attribute}")
                    if (response.isSuccess && response.data != null) {
                        updateRelationState(response.data)
                    }
                }
                .onFailure {
                    AppLog.w(TAG, "loadRelationState failed: ${it.message}")
                }
        }
    }

    private fun updateRelationState(relation: CheckRelationModel) {
        relationAttribute = relation.attribute
        renderFollowButton()
    }

    private fun renderFollowButton() {
        val following = isFollowing()
        val labelRes = when {
            relationAttribute == 6 -> R.string.follow_as_friend
            relationAttribute == 2 -> R.string.followed
            else -> R.string.follow
        }
        headerAdapter.updateFollowState(
            labelRes = labelRes,
            backgroundRes = if (following) {
                R.drawable.bg_follow_space_btn_followed
            } else {
                R.drawable.bg_hongguo_btn_detail
            },
            iconRes = if (following) R.drawable.ic_check else R.drawable.ic_plus,
            dimmed = following
        )
    }

    private fun doFollow() {
        if (!checkLogin()) return

        val action = if (isFollowing()) 2 else 1

        lifecycleScope.launch {
            val result = userRepository.modifyRelation(mid, action)
            result.onSuccess { response ->
                if (response.isSuccess) {
                    relationAttribute = if (action == 1) 2 else 0
                    renderFollowButton()
                    loadRelationState()
                    loadUserStat()
                    AppToast.show(requireContext(),if (action == 1) getString(R.string.toast_follow_success)
                        else getString(R.string.toast_cancel_follow_success))
                } else {
                    AppToast.show(requireContext(), response.message)
                }
            }.onFailure { e ->
                AppToast.show(requireContext(), getString(R.string.toast_operation_failed_format, e.message))
            }
        }
    }

    private fun checkLogin(): Boolean {
        if (!sessionGateway.isLoggedIn()) {
            return false
        }
        return true
    }

    private fun isFollowing(): Boolean {
        return relationAttribute == 2 || relationAttribute == 6
    }

    private fun focusHeaderFromContent(): Boolean {
        // 上行纵向链与 tab 一致：列表顶行 → 投稿行排序按钮 → 头部行 → 返回键，
        // 不从列表直接跳头部行（会跳过投稿行导致焦点链断层）
        if (requestHeaderFocus(FocusArea.FILTER, retries = 6, fallbackToBack = false)) {
            return true
        }
        val orderedAreas = buildList {
            if (headerAdapter.hasVisibleFollowAction()) {
                add(FocusArea.FOLLOW)
            }
            add(FocusArea.FOLLOWING)
            add(FocusArea.FOLLOWER)
        }
        for (area in orderedAreas) {
            if (requestHeaderFocus(area, retries = 6, fallbackToBack = false)) {
                return true
            }
        }
        return binding.buttonBack.requestFocus()
    }

    /** BACK 落点：回列表 tab；header 被滚出屏时滚顶后重试，重试耗尽才退出页面。 */
    private fun focusTabsForBackExit(retries: Int) {
        if (!isAdded || rootView == null) return
        if (headerAdapter.focusSelectedTab()) {
            AppLog.d(TAG, "backToTabs: focused, retries left=$retries")
            lastFocusedArea = FocusArea.FILTER
            // 刚滚回顶的 RV 布局还在稳定期，tab 焦点可能被后续布局抖掉，落位后再校验拉回；
            // 同时补滚到 0 让头部完整露出（requestChildFocus 只保证焦点可见即止）
            binding.recyclerViewVideos.post {
                if (isAdded && !headerAdapter.isTabsFocused()) {
                    AppLog.d(TAG, "backToTabs: focus lost after settle, re-request")
                    binding.recyclerViewVideos.scrollToPosition(0)
                    headerAdapter.focusSelectedTab()
                }
            }
            return
        }
        AppLog.d(TAG, "backToTabs: retry (retries=$retries rvFocus=${binding.recyclerViewVideos.hasFocus()})")
        if (retries > 0) {
            binding.recyclerViewVideos.scrollToPosition(0)
            binding.recyclerViewVideos.post { focusTabsForBackExit(retries - 1) }
            return
        }
        parentFragmentManager.popBackStack()
    }

    private fun restoreFocus() {
        if (!isAdded) return
        binding.root.post {
            if (!isAdded) return@post
            val controller = tvFocusController
            if (lastFocusedArea == FocusArea.CONTENT &&
                controller != null &&
                controller.hasCapturedAnchor()
            ) {
                // 统一走带仲裁的返回恢复：controller 锚点即点击时的视频卡片
                // （含 header offset 的稳定 key 重解析），失败退 header 区域/返回键
                controller.restoreFocusAfterReturn(
                    onFailed = {
                        if (!binding.recyclerViewVideos.hasFocusInChildren()) {
                            requestHeaderFocus(lastFocusedArea)
                        }
                    }
                )
                return@post
            }
            if (lastFocusedArea == FocusArea.CONTENT && requestContentFocus(lastFocusedVideoPosition)) {
                return@post
            }
            requestHeaderFocus(lastFocusedArea)
        }
    }

    private fun requestHeaderFocus(
        area: FocusArea,
        retries: Int = 6,
        fallbackToBack: Boolean = true
    ): Boolean {
        if (area == FocusArea.BACK) {
            return binding.buttonBack.requestFocus()
        }
        val target = when (area) {
            FocusArea.FOLLOW -> UserSpaceHeaderAdapter.FocusTarget.FOLLOW
            FocusArea.FOLLOWING -> UserSpaceHeaderAdapter.FocusTarget.FOLLOWING
            FocusArea.FOLLOWER -> UserSpaceHeaderAdapter.FocusTarget.FOLLOWER
            FocusArea.FILTER -> UserSpaceHeaderAdapter.FocusTarget.FILTER
            else -> null
        } ?: return binding.buttonBack.requestFocus()
        if (headerAdapter.requestFocus(target)) {
            return true
        }
        if (retries > 0) {
            binding.recyclerViewVideos.scrollToPosition(0)
            binding.recyclerViewVideos.post {
                requestHeaderFocus(area, retries - 1, fallbackToBack)
            }
            return false
        }
        return if (fallbackToBack) {
            binding.buttonBack.requestFocus()
        } else {
            false
        }
    }

    /**
     * 聚焦内容区视频卡片（header offset 已换算为绝对位置）。布局未就绪时的等待与
     * 重试由 controller 的 RVFocusOp（PreDraw + 重试链）承担，不再手写递归轮询。
     */
    private fun requestContentFocus(position: Int): Boolean {
        // 视图已销毁（onDestroyView 后 binding 为 null）时，Adapter 的遥控器按键
        // 回调可能晚到，直接放弃，避免 NPE 崩溃。
        if (!isAdded || rootView == null) return false
        val itemCount = videoAdapter.contentCount()
        if (itemCount == 0) {
            return false
        }
        val targetPosition = position
            .takeIf { it != RecyclerView.NO_POSITION }
            ?.coerceIn(0, itemCount - 1)
            ?: 0

        val absolutePosition = headerAdapter.itemCount + targetPosition
        if (tvFocusController?.requestFocusPosition(absolutePosition) == true) {
            return true
        }
        return binding.recyclerViewVideos.findViewHolderForAdapterPosition(absolutePosition)
            ?.itemView?.requestFocus() == true
    }

    private fun installTvFocusController() {
        tvFocusController?.release()
        tvFocusController = TvListFocusController(
            recyclerView = binding.recyclerViewVideos,
            adapter = OffsetTvFocusableAdapter(videoAdapter) { headerAdapter.itemCount },
            strategy = GridTvFocusStrategy { spanCount },
            canLoadMore = { activeState().hasMore },
            loadMore = {
                if (!isLoading && activeState().hasMore) {
                    advancePage()
                    loadTabPage(activeState())
                }
            }
        )
    }

    override fun onDestroyView() {
        tvFocusController?.release()
        tvFocusController = null
        swipeRefreshLayout = null
        super.onDestroyView()
    }

    override fun onVideoBlocked(aid: Long, bvid: String) {
        if (::videoAdapter.isInitialized) {
            videoAdapter.removeByVideoId(aid, bvid)
        }
    }
}
