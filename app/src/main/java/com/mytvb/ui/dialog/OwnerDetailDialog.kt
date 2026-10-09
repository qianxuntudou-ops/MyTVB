package com.mytvb.ui.dialog

import android.content.Context
import android.os.SystemClock
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import android.view.LayoutInflater
import android.view.Window
import android.widget.Toast
import androidx.appcompat.app.AppCompatDialog
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentActivity
import com.google.android.material.tabs.TabLayout
import com.mytvb.core.ui.tab.enableDirectTabNavigation
import com.mytvb.core.ui.tab.focusSelectedTab
import com.mytvb.core.ui.user.UserBadgeText
import com.mytvb.core.ui.user.UserBadgesStore
import com.mytvb.R
import com.mytvb.core.common.log.AppLog
import com.mytvb.databinding.DialogOwnerDetailBinding
import com.mytvb.event.AppEventHub
import com.mytvb.model.user.CheckRelationModel
import com.mytvb.model.video.Owner
import com.mytvb.model.video.VideoModel
import com.mytvb.network.session.SessionStateRepository
import com.mytvb.repository.UserRepository
import com.mytvb.ui.activity.PlayerActivity
import com.mytvb.ui.adapter.VideoAdapter
import com.mytvb.core.ui.base.VideoRecyclerViewTuning
import com.mytvb.core.ui.layout.WrapContentGridLayoutManager
import com.mytvb.core.ui.decoration.GridSpacingItemDecoration
import com.mytvb.core.common.content.ContentFilter
import com.mytvb.core.ui.focus.tv.GridTvFocusStrategy
import com.mytvb.core.ui.focus.tv.TvDataChangeReason
import com.mytvb.core.ui.focus.tv.TvListFocusController
import com.mytvb.core.ui.image.ImageLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class OwnerDetailDialog(
    context: Context,
    private val owner: Owner,
    private val onPlayVideo: (VideoModel, List<VideoModel>) -> Unit,
    private val currentAid: Long = 0L,
    private val currentVideoId: String = "",
    // 当前视频所属合集（播放页 view.ugcSeason.id）：合集老视频在投稿流里页数很深，
    // 已知归属时直接在该合集 tab 并行搜索定位，不必等投稿流翻页。
    private val currentSeasonId: Long = 0L
) : AppCompatDialog(context, R.style.DialogTheme), KoinComponent {

    private companion object {
        const val TAG = "OwnerDetailDialog"
        const val PAGE_SIZE = 20

        // 定位当前视频的翻页搜索上限（页）。超过即放弃，走直播回放 fallback 或默认焦点。
        const val MAX_SEARCH_PAGES = 10

        // B 站把直播回放投稿归为 UP 主名下自动生成的系列，名字模板"{UP名}的直播回放"。
        const val LIVE_REPLAY_SERIES_KEYWORD = "直播回放"
    }

    private val binding = DialogOwnerDetailBinding.inflate(LayoutInflater.from(context))
    private val userRepository: UserRepository by inject()
    private val sessionGateway: SessionStateRepository by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val videoAdapter = VideoAdapter(
        onTopEdgeUp = { focusTabFromContent() },
        onItemFocusedWithView = { view, position ->
            userEngagedContent = true
            tvFocusController?.onItemFocused(view, position)
        },
        onItemDpad = { view, keyCode, event ->
            tvFocusController?.handleKey(view, keyCode, event) == true
        }
    ).also {
        it.currentPlayingAid = currentAid
    }

    private enum class TabKind { VIDEO, SERIES, SEASON }

    /**
     * 每个 tab 独立的列表状态。切换 tab 时把 items 铺回 adapter，切回不重载；
     * 后台翻页/预取也落在各自 state 上（loading 也按 tab 独立），跨 tab 的在飞请求不会互相污染。
     */
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
        var searching: Boolean = false

        // 定位翻页搜索已结束且未命中（fallback 链推进时跳过已搜尽的 tab）
        var searchDone: Boolean = false
        var loadedFirstPage: Boolean = false
        var locatedIndex: Int = -1
        var scrollPos: Int = -1
        var prefetchJob: Deferred<TabPage>? = null
        var prefetchPage: Int = 0
    }

    /** 一页数据的解析结果：过滤后的视频 + 翻页判定。 */
    private data class TabPage(val videos: List<VideoModel>, val hasMore: Boolean)

    private val tabStates = mutableListOf<TabState>()
    private val videoTabState: TabState get() = tabStates[0]
    private var currentIndex = 0
    private var replayTabState: TabState? = null
    private var relationAttribute = 0
    private var tvFocusController: TvListFocusController? = null
    private var progressBarRequestIndex: Int = -1

    // 视频投稿流搜不到当前视频（直播回放不在 x/space 投稿流里）时，
    // 置位等待直播回放系列就绪后自动切 tab 定位。
    private var pendingReplayFallback = false

    // 定位竞态裁决：多个 tab 并行预载时，第一个命中者赢，其余命中结果作废，
    // 避免先切到合集又被打回视频 tab 的来回跳。
    private var currentLocated: TabState? = null

    // selectTabIndex（自动定位/fallback）置位：切换后允许把焦点拉到定位视频；
    // 用户在 tab 上按 OK 切换不走这里，焦点留在 tab 栏。
    private var pendingAutoSwitch = false

    // 用户焦点进过内容卡片（在浏览列表）后不再自动跳 tab，避免打断浏览。
    private var userEngagedContent = false

    // 两级返回：焦点在视频列表 → 第一次返回焦点上移到当前 tab；焦点已在 tab/关注等 header 区 → 直接关闭
    // （回调注册在 Dialog 自己的 onBackPressedDispatcher 上，随 dialog lifecycle 自动移除）

    /** 是否允许自动跳 tab：用户焦点已在内容卡片或 tab 栏上时（正手动操作），一律不抢。 */
    private fun shouldAutoJump(): Boolean =
        !userEngagedContent && !binding.tabLayout.hasFocus()

    init {
        supportRequestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(binding.root)
        setCanceledOnTouchOutside(true)
        // 设计尺寸 px1400×px935，但在低分屏/大缩放系数下换算后的像素可能超过屏幕物理尺寸，
        // 浮层 window 会被系统钳到屏幕大小而内容仍按原尺寸绘制，左右卡片内容被裁；
        // 这里取设计尺寸与屏幕 92% 的较小值，布局内部用约束自适应 window 实际大小。
        val dm = context.resources.displayMetrics
        val designWidth = context.resources.getDimensionPixelSize(R.dimen.px1400)
        val designHeight = context.resources.getDimensionPixelSize(R.dimen.px935)
        window?.setLayout(
            minOf(designWidth, (dm.widthPixels * 0.92f).toInt()),
            minOf(designHeight, (dm.heightPixels * 0.92f).toInt())
        )
        binding.root.setOnClickListener { dismiss() }
        initView()
        bindOwnerHeader()
        loadData()
    }

    private fun initView() {
        val spacing = context.resources.getDimensionPixelSize(R.dimen.px10)
        binding.recyclerView.layoutManager = WrapContentGridLayoutManager(context, 3)
        binding.recyclerView.adapter = videoAdapter
        VideoRecyclerViewTuning.apply(binding.recyclerView, videoAdapter)
        binding.recyclerView.setPadding(0, -spacing, 0, binding.recyclerView.paddingBottom)
        if (binding.recyclerView.itemDecorationCount == 0) {
            binding.recyclerView.addItemDecoration(
                GridSpacingItemDecoration(3, spacing, true)
            )
        }
        videoAdapter.setOnItemClickListener { _, item ->
            dismiss()
            onPlayVideo(
                item,
                PlayerActivity.buildPlayQueue(videoAdapter.getItemsSnapshot(), item)
            )
        }
        binding.buttonFollow.setOnClickListener {
            if (!checkLogin()) return@setOnClickListener
            toggleFollow()
        }
        binding.recyclerView.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                // 不限制 dy>0：定位当前视频的 jump 滚动 dy=0，若拦掉会让列表停在
                // 尾部却不翻页，footer“正在加载..”没有任何请求在飞、永远空转。
                maybeTriggerLoadMore()
            }
        })
        setupTabBar()
        installTvListFocusController()
        installBackInterceptor()
    }

    private fun bindOwnerHeader() {
        UserBadgeText.bind(binding.textName, owner.name, isVip = false)
        ImageLoader.loadCircle(
            imageView = binding.imageAvatar,
            url = owner.face,
            placeholder = R.drawable.default_avatar,
            error = R.drawable.default_avatar
        )
        binding.imageAvatar.setBadge(
            officialVerifyType = owner.officialVerify?.type ?: -1
        )
        binding.imageAvatar.setPendant(null)
        // owner 接口不带会员/头像框，按 mid 补齐（dialog 单实例无复用错位）
        UserBadgesStore.enqueue(owner.mid) { badges ->
            binding.imageAvatar.setPendant(badges.pendantUrl)
            if (badges.isVip) {
                binding.imageAvatar.setBadge(
                    officialVerifyType = owner.officialVerify?.type ?: -1,
                    vipStatus = 1,
                    vipType = 2,
                )
            }
            UserBadgeText.bind(binding.textName, owner.name, badges.isVip)
        }

        val isSelf = sessionGateway.getUserInfo()?.mid == owner.mid
        binding.buttonFollow.isVisible = !isSelf
        updateFollowUi(
            labelRes = R.string.follow,
            textColorRes = R.color.colorAccent,
            iconRes = R.drawable.ic_plus,
            iconTintRes = R.color.colorAccent
        )
    }

    private fun loadData() {
        loadRelationState()
        loadTabPage(videoTabState)
        loadCollectionTabs()
    }

    /**
     * 探测 UP 主的系列与合集（x/polymer/web-space/home/seasons_series，2025+ 网页版新版接口），
     * 每个系列/合集各建一个 tab：series_list 里的直播回放自动系列（name 含"直播回放"）排最前，
     * 其后是其余系列与全部合集。探测失败静默（tab 栏只剩"视频"）。
     */
    private fun loadCollectionTabs() {
        scope.launch {
            val result = userRepository.getSeasonsSeriesList(owner.mid)
            result.onSuccess { response ->
                val data = response.data
                val seriesList = data?.series.orEmpty()
                val seasonsList = data?.seasons.orEmpty()
                val replay = seriesList.firstOrNull {
                    it.seriesId > 0 && it.name.contains(LIVE_REPLAY_SERIES_KEYWORD)
                }
                AppLog.i(
                    TAG,
                    "collections: code=${response.code} seasons=${seasonsList.joinToString("|") { it.name }} " +
                        "series=${seriesList.joinToString("|") { it.name }} replay=${replay?.seriesId}"
                )
                var replayState: TabState? = null
                if (replay != null && replay.seriesId > 0) {
                    replayState = appendCollectionTab(
                        TabState(TabKind.SERIES, replay.name, seriesId = replay.seriesId)
                    )
                }
                seriesList.forEach { item ->
                    if (item.seriesId > 0 && item !== replay) {
                        appendCollectionTab(TabState(TabKind.SERIES, item.name, seriesId = item.seriesId))
                    }
                }
                seasonsList.forEach { item ->
                    if (item.seasonId > 0) {
                        appendCollectionTab(TabState(TabKind.SEASON, item.name, seasonId = item.seasonId))
                    }
                }
                this@OwnerDetailDialog.replayTabState = replayState
                if (pendingReplayFallback && replayState != null) {
                    // 视频流已搜尽在等回放：切换触发首屏加载与搜索
                    pendingReplayFallback = false
                    replayState.searching = true
                    selectTabIndex(tabStates.indexOf(replayState))
                } else if (hasCurrentVideoToLocate() && !userEngagedContent) {
                    // 后台并行预载回放+全部合集的第一页：命中即切（不用等视频流翻完 10 页）。
                    // 合集正序排列（老视频在前），从合集里点进来的老视频大概率第一页就命中；
                    // 不在任何合集/回放里的视频，由视频流搜索兜底。
                    tabStates.drop(1).forEach { state ->
                        if (!state.loadedFirstPage && state.items.isEmpty()) loadTabPage(state)
                    }
                }
            }.onFailure {
                AppLog.w(TAG, "collections probe failed: ${it.message}")
                consumePendingReplayFallback()
            }
        }
    }

    /** 追加一个合集类 tab（去重），返回新 state；已存在返回 null。 */
    private fun appendCollectionTab(state: TabState): TabState? {
        if (tabStates.any { it.kind == state.kind && it.seriesId == state.seriesId && it.seasonId == state.seasonId }) {
            return null
        }
        tabStates.add(state)
        binding.tabLayout.addTab(
            binding.tabLayout.newTab().setText(state.title)
        )
        // 动态追加的 tab 不会经过 setupTabBar 时的初始化，重新遍历补齐
        // 独立背景/焦点文字色/DPAD 上下导航（该扩展幂等，重复绑定无害）。
        binding.tabLayout.enableDirectTabNavigation(
            onNavigateDown = {
                AppLog.d(TAG, "tab navigate down -> content")
                tvFocusController?.focusPrimary() == true
            },
            onNavigateUp = {
                if (binding.buttonFollow.isVisible) binding.buttonFollow.requestFocus() else false
            }
        )
        AppLog.i(TAG, "tab added: ${state.kind} ${state.title} seriesId=${state.seriesId} seasonId=${state.seasonId}")
        return state
    }

    /** 视频流搜索落空但确认没有直播回放系列：放弃 fallback，回默认焦点。 */
    private fun consumePendingReplayFallback() {
        if (!pendingReplayFallback) return
        pendingReplayFallback = false
        focusDefault()
    }

    private fun loadRelationState() {
        if (!sessionGateway.isLoggedIn() || sessionGateway.getUserInfo()?.mid == owner.mid) {
            return
        }
        scope.launch {
            userRepository.checkUserRelation(owner.mid)
                .onSuccess { response ->
                    if (response.isSuccess && response.data != null) {
                        updateRelationState(response.data)
                    }
                }
        }
    }

    private fun updateRelationState(relation: CheckRelationModel) {
        relationAttribute = relation.attribute
        when {
            relation.isMutualFollow -> updateFollowUi(
                labelRes = R.string.follow_as_friend,
                textColorRes = R.color.grey,
                iconRes = R.drawable.ic_check,
                iconTintRes = R.color.grey
            )
            relationAttribute == 2 -> updateFollowUi(
                labelRes = R.string.followed,
                textColorRes = R.color.grey,
                iconRes = R.drawable.ic_check,
                iconTintRes = R.color.grey
            )
            else -> updateFollowUi(
                labelRes = R.string.follow,
                textColorRes = R.color.colorAccent,
                iconRes = R.drawable.ic_plus,
                iconTintRes = R.color.colorAccent
            )
        }
    }

    private fun activeState(): TabState = tabStates[currentIndex]

    private suspend fun fetchTabPage(state: TabState, page: Int): TabPage =
        when (state.kind) {
            TabKind.VIDEO -> {
                val data = userRepository.getUserDynamic(owner.mid, page = page, pageSize = PAGE_SIZE)
                    .getOrThrow().data ?: error("empty data")
                TabPage(data.archives, data.hasMore)
            }
            TabKind.SERIES -> {
                val data = userRepository.getSeriesArchives(
                    owner.mid, seriesId = state.seriesId, page = page, pageSize = PAGE_SIZE
                ).getOrThrow().data ?: error("empty data")
                TabPage(data.archives, data.hasMore)
            }
            TabKind.SEASON -> {
                // seasons_archives_list 的 page 只带 total（无 num/size/has_more），按总数算翻页
                val data = userRepository.getSeasonArchives(
                    owner.mid, seasonId = state.seasonId, page = page, pageSize = PAGE_SIZE
                ).getOrThrow().data ?: error("empty data")
                val loaded = state.items.size + data.archives.size
                TabPage(data.archives, if (data.totalCount > 0) loaded < data.totalCount else data.archives.size >= PAGE_SIZE)
            }
        }

    private fun loadTabPage(state: TabState) {
        if (state.loading || !state.hasMore) return
        val stateIndex = tabStates.indexOf(state)
        val pageToLoad = state.page
        // 若后台已有这一页的预取在飞，直接消费它，省掉一整次网络往返。
        val prefetchHit = state.prefetchJob != null && state.prefetchPage == pageToLoad
        val isCurrent = stateIndex == currentIndex
        state.loading = true
        if (pageToLoad == 1 && isCurrent) {
            binding.progressBar.isVisible = true
            progressBarRequestIndex = stateIndex
        }
        scope.launch {
            val startMs = SystemClock.elapsedRealtime()
            val existingPrefetch = state.prefetchJob
            if (!prefetchHit) {
                // 页码不符或没有预取：丢弃陈旧预取，本次直接发请求。
                state.prefetchJob = null
                existingPrefetch?.cancel()
            }
            val result = runCatching {
                if (prefetchHit && existingPrefetch != null) {
                    state.prefetchJob = null
                    existingPrefetch.await()
                } else {
                    fetchTabPage(state, pageToLoad)
                }
            }
            state.loading = false
            if (progressBarRequestIndex == stateIndex) {
                binding.progressBar.isVisible = false
                progressBarRequestIndex = -1
            }
            result.onSuccess { pageData ->
                AppLog.i(
                    TAG,
                    "tab=${state.title} page=$pageToLoad end elapsed=${SystemClock.elapsedRealtime() - startMs}ms " +
                        "items=${pageData.videos.size} hasMore=${pageData.hasMore} prefetchHit=$prefetchHit"
                )
                // 回调时重判：发起后用户可能已切 tab，UI 操作只允许落在当前显示的 tab 上，
                // 否则旧 tab 的数据会 addData 进新 tab 的列表。
                val stillCurrent = tabStates.indexOf(state) == currentIndex
                val videos = ContentFilter.filterVideos(binding.root.context, pageData.videos)
                state.hasMore = pageData.hasMore
                if (pageToLoad == 1) {
                    state.loadedFirstPage = true
                    state.items = videos.toMutableList()
                    if (stillCurrent) {
                        videoAdapter.setData(state.items) {
                            tvFocusController?.onDataChanged(TvDataChangeReason.REPLACE_PRESERVE_ANCHOR)
                        }
                    }
                } else {
                    state.items.addAll(videos)
                    if (stillCurrent) {
                        videoAdapter.addData(videos)
                        tvFocusController?.onDataChanged(TvDataChangeReason.APPEND)
                    }
                }
                if (stillCurrent) {
                    videoAdapter.setShowLoadMore(state.hasMore)
                }
                handleSearchProgress(state, pageToLoad, stillCurrent)
                // 当前页渲染成功后，立即在后台预取下一页，把网络往返藏进用户浏览过程。
                // 递归搜索路径也会消费它，让"从旧视频打开"的串行循环每步都更快。
                if (stillCurrent) {
                    schedulePrefetch(state, state.page + 1)
                }
                if (state.searching && state.hasMore && state.page < MAX_SEARCH_PAGES) {
                    state.page++
                    loadTabPage(state)
                }
                maybeLoadActiveTabFirstPage()
            }.onFailure {
                AppLog.w(
                    TAG,
                    "tab=${state.title} page=$pageToLoad failed elapsed=${SystemClock.elapsedRealtime() - startMs}ms " +
                        "prefetchHit=$prefetchHit err=${it.message}"
                )
                state.page--
                toast(it.message ?: context.getString(R.string.dialog_load_failed))
                maybeLoadActiveTabFirstPage()
            }
        }
    }

    /**
     * 当前视频定位的翻页搜索推进。命中即记录 [TabState.locatedIndex]（当前 tab 顺带滚动聚焦）；
     * 后台 tab 命中且用户尚未交互 → 立即切换过去（预载并行搜索的快速路径）；
     * 搜尽（无更多或达上限）未命中：视频 tab → 回放 → 逐个合集，沿 fallback 链推进。
     */
    private fun handleSearchProgress(state: TabState, pageToLoad: Int, isCurrent: Boolean) {
        if (state.locatedIndex >= 0 || currentLocated != null) return
        if (!hasCurrentVideoToLocate()) {
            // 没有可定位的目标（如从详情页打开）：首屏落默认焦点即可。
            if (pageToLoad == 1 && isCurrent) focusDefault()
            return
        }
        val idx = state.items.indexOfFirst { matchesCurrentVideo(it) }
        if (idx >= 0) {
            currentLocated = state
            state.locatedIndex = idx
            state.searching = false
            // 停掉其他 tab 的搜索（视频流/回放/合集内容互斥，继续翻页纯属浪费）
            tabStates.forEach { if (it !== state) it.searching = false }
            if (isCurrent) {
                scrollToCurrentVideo(state.items)
            } else if (shouldAutoJump()) {
                // 后台 tab（预载并行搜索）先命中：直接切过去定位，不必等前面的链路搜尽
                selectTabIndex(tabStates.indexOf(state))
            }
            return
        }
        if (pageToLoad == 1) {
            // 首页未命中才进入搜索态。只对视频 tab 自动开启——手动/预载切到合集类 tab 是浏览意图，
            // 不能翻 10 页找一个大概率不在里面的视频；fallback 会在切入前主动置 searching。
            if (state.kind == TabKind.VIDEO) state.searching = true
        }
        // 首页即无更多（投稿≤1页）或翻页达上限：搜索结束。page1 提前判搜尽，
        // 否则 searching 挂着不翻页也不落焦点，打开面板后焦点悬空。
        if (!state.hasMore || state.page >= MAX_SEARCH_PAGES) {
            state.searching = false
            state.searchDone = true
            when {
                state.kind == TabKind.VIDEO -> maybeFallbackToReplaySearch()
                isCurrent -> advanceFallbackSearch(state)
                // 后台预载的合集 tab 搜尽：不切 tab 不抢焦点，等前面的链路推进时跳过它
            }
        }
    }

    private fun hasCurrentVideoToLocate(): Boolean = currentAid > 0L || currentVideoId.isNotBlank()

    private fun matchesCurrentVideo(video: VideoModel): Boolean =
        (currentAid > 0L && video.aid == currentAid) ||
            (currentVideoId.isNotBlank() && video.bvid == currentVideoId)

    /**
     * 投稿流里搜不到当前视频且直播回放 tab 已就绪 → 切过去继续搜；
     * 回放 tab 还没探测完（pendingReplayFallback 已置位）则等 loadCollectionTabs 结果决定。
     * 用户已在浏览投稿列表时不自动跳转（避免打断），只保留手动切 tab 的路径。
     */
    private fun maybeFallbackToReplaySearch() {
        if (!shouldAutoJump()) return
        val replay = replayTabState ?: run {
            pendingReplayFallback = true
            return
        }
        resumeFallbackSearch(replay)
    }

    /**
     * 切到 fallback 链上的下一个 tab 并接续搜索。关键：目标 tab 若已预载过第一页但未命中
     * （如旧回放排在第 3 页），翻页搜索没人推进——这里从下一页继续翻，否则 searching
     * 挂着永远不动，用户看到的就是"没定位"。
     */
    private fun resumeFallbackSearch(state: TabState) {
        state.searching = true
        selectTabIndex(tabStates.indexOf(state))
        if (state.loadedFirstPage && state.hasMore && state.page < MAX_SEARCH_PAGES) {
            state.page++
            loadTabPage(state)
        }
    }

    /**
     * fallback 链推进：回放/某个合集搜尽后，切到下一个未搜尽的合集 tab 继续搜
     * （合集正序排列，目标视频越老越靠前，通常第一二页就命中）；
     * 链上全部搜尽才回默认焦点。
     */
    private fun advanceFallbackSearch(from: TabState) {
        from.searchDone = true
        if (!shouldAutoJump()) {
            if (tabStates.indexOf(from) == currentIndex) focusDefault()
            return
        }
        val next = tabStates.drop(tabStates.indexOf(from) + 1)
            .firstOrNull { it.kind != TabKind.VIDEO && !it.searchDone && it.locatedIndex < 0 }
        if (next == null) {
            if (tabStates.indexOf(from) == currentIndex) focusDefault()
            return
        }
        resumeFallbackSearch(next)
    }

    /**
     * 后台预取下一页，结果暂存在 [state.prefetchJob]。下一次 [loadTabPage] 触底时优先消费它，
     * 命中即可省掉一整次网络往返。预取失败对当前列表无影响（消费时才暴露为失败）。
     */
    private fun schedulePrefetch(state: TabState, nextPage: Int) {
        if (!state.hasMore) return
        if (nextPage == state.prefetchPage && state.prefetchJob != null) return
        state.prefetchJob?.cancel()
        state.prefetchPage = nextPage
        state.prefetchJob = scope.async {
            // fetchTabPage 内部已 getOrThrow，失败时 job 以异常完成，消费侧 runCatching 兜住
            fetchTabPage(state, nextPage)
        }
    }

    /**
     * 提前约一屏触发下一页：3 列网格一屏约 6 卡，提前消费已在飞的下一页预取。
     * 必须 post 到下一帧：预取命中时数据几乎同步返回，若在 onScrolled 里直接
     * addData 会撞 RecyclerView 的 assertNotInLayoutOrScroll 检查。
     */
    private fun maybeTriggerLoadMore() {
        val layoutManager = binding.recyclerView.layoutManager as? androidx.recyclerview.widget.GridLayoutManager
            ?: return
        val totalItems = binding.recyclerView.adapter?.itemCount ?: return
        val state = activeState()
        val lastVisibleItem = layoutManager.findLastVisibleItemPosition()
        if (!state.loading && state.hasMore && lastVisibleItem >= totalItems - 6) {
            binding.recyclerView.post {
                val s = activeState()
                if (!s.loading && s.hasMore) {
                    s.page++
                    loadTabPage(s)
                }
            }
        }
    }

    private fun scrollToCurrentVideo(videos: List<VideoModel>): Boolean {
        val targetIndex = videos.indexOfFirst { video -> matchesCurrentVideo(video) }
        AppLog.d(TAG, "scrollToCurrentVideo: targetIndex=$targetIndex, currentAid=$currentAid, currentVideoId=$currentVideoId, videos=${videos.size}")
        if (targetIndex < 0) return false

        if (videoAdapter.currentPlayingAid <= 0L) {
            val targetAid = videos[targetIndex].aid
            if (targetAid > 0L) {
                videoAdapter.currentPlayingAid = targetAid
            }
        }
        binding.recyclerView.post {
            binding.recyclerView.scrollToPosition(targetIndex)
            focusVideoAt(targetIndex)
            binding.recyclerView.post {
                val layoutManager =
                    binding.recyclerView.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager
                val vh = binding.recyclerView.findViewHolderForAdapterPosition(targetIndex)
                AppLog.d(TAG, "centerScroll: layoutManager=$layoutManager, vh=$vh, targetIndex=$targetIndex")
                if (layoutManager != null && vh != null) {
                    val rvHeight = binding.recyclerView.height
                    val itemHeight = vh.itemView.height
                    val centerOffset = (rvHeight - itemHeight) / 2
                    AppLog.d(TAG, "centerScroll: rvHeight=$rvHeight, itemHeight=$itemHeight, centerOffset=$centerOffset")
                    layoutManager.scrollToPositionWithOffset(
                        targetIndex, centerOffset.coerceAtLeast(0)
                    )
                    // jump 滚动不保证回调 onScrolled，停在尾部时显式补一次触底检查，
                    // 否则 footer 转圈会一直空转（见 maybeTriggerLoadMore 注释）。
                    binding.recyclerView.post { maybeTriggerLoadMore() }
                }
            }
        }
        return true
    }

    /** 程序化切换 tab（自动定位/fallback 用）：select 会触发 OnTabSelectedListener → [performSwitch]。 */
    private fun selectTabIndex(index: Int) {
        if (index == currentIndex || index < 0 || index >= tabStates.size) return
        pendingAutoSwitch = true
        binding.tabLayout.getTabAt(index)?.select()
    }

    private fun performSwitch(index: Int) {
        if (index == currentIndex || index < 0 || index >= tabStates.size) return
        AppLog.i(TAG, "switchTab: ${tabStates[currentIndex].title} -> ${tabStates[index].title}")
        // 记住离开 tab 的滚动位置，切回时恢复
        val lm = binding.recyclerView.layoutManager as? androidx.recyclerview.widget.GridLayoutManager
        activeState().scrollPos = lm?.findFirstVisibleItemPosition() ?: -1
        val autoFocus = pendingAutoSwitch
        pendingAutoSwitch = false
        currentIndex = index
        applyTabState(activeState(), autoFocus)
    }

    /** 把 state 的缓存数据铺回 adapter，必要时补发该 tab 的首屏加载。 */
    private fun applyTabState(state: TabState, autoFocus: Boolean) {
        videoAdapter.setShowLoadMore(state.hasMore)
        videoAdapter.setData(state.items) {
            tvFocusController?.onDataChanged(TvDataChangeReason.REPLACE_PRESERVE_ANCHOR)
        }
        if (autoFocus && state.locatedIndex >= 0) {
            // 自动定位/fallback 切换：滚到定位视频并聚焦（焦点从 tab 栏/弹窗外接手过来）
            scrollToCurrentVideo(state.items)
        } else if (state.scrollPos > 0) {
            // 用户手动 OK 切换：只恢复离开时的滚动位置，焦点留在 tab 栏不抢
            binding.recyclerView.scrollToPosition(state.scrollPos)
        }
        maybeLoadActiveTabFirstPage()
    }

    /** 当前 tab 首屏还没加载就补发（切 tab 时若该 tab 请求在飞，state.loading 会挡住，回调里兜底）。 */
    private fun maybeLoadActiveTabFirstPage() {
        val state = activeState()
        if (state.loading || !state.hasMore || state.loadedFirstPage || state.items.isNotEmpty()) return
        loadTabPage(state)
    }

    /**
     * tab 栏常显：默认只有"视频"，探测到系列/合集后由 [appendCollectionTab] 追加。
     * tab 背景每 tab 独立实例，避开 material XML tabBackground 全 tab 共享串台的坑。
     */
    private fun setupTabBar() {
        val tabLayout = binding.tabLayout
        tabStates.add(TabState(TabKind.VIDEO, context.getString(R.string.video)))
        tabLayout.addTab(tabLayout.newTab().setText(tabStates[0].title))
        tabLayout.getTabAt(0)?.select()
        tabLayout.visibility = View.VISIBLE
        tabLayout.enableDirectTabNavigation(
            onNavigateDown = {
                AppLog.d(TAG, "tab navigate down -> content")
                tvFocusController?.focusPrimary() == true
            },
            onNavigateUp = {
                if (binding.buttonFollow.isVisible) binding.buttonFollow.requestFocus() else false
            }
        )
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                performSwitch(tab.position)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit

            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
    }

    /** 内容首行按上键：tab 栏可见则焦点回当前选中 tab。 */
    private fun focusTabFromContent(): Boolean {
        if (binding.tabLayout.visibility != View.VISIBLE) return false
        return binding.tabLayout.focusSelectedTab()
    }

    private fun focusDefault() {
        binding.recyclerView.post {
            if (binding.buttonFollow.isVisible) {
                binding.buttonFollow.requestFocus()
            } else {
                tvFocusController?.focusPrimary()
            }
        }
    }

    private fun focusVideoAt(targetIndex: Int, retries: Int = 6) {
        // allowOutsideFocus=true：对话框刚打开时焦点还在外层容器上，
        // 不放开这个开关 focusPosition 会被“焦点在 RV 外”直接拦截。
        if (tvFocusController?.requestFocusPosition(targetIndex, allowOutsideFocus = true) == true) {
            return
        }
        if (retries > 0) {
            binding.recyclerView.post { focusVideoAt(targetIndex, retries - 1) }
        } else {
            // 重试耗尽仍未聚焦成功：回退到第一个可见卡片，保证打开时焦点一定落在列表上。
            tvFocusController?.focusPrimary()
        }
    }

    private fun updateFollowUi(
        labelRes: Int,
        textColorRes: Int,
        iconRes: Int,
        iconTintRes: Int
    ) {
        binding.textFollow.text = context.getString(labelRes)
        binding.textFollow.setTextColor(ContextCompat.getColor(context, textColorRes))
        binding.iconFollow.setImageResource(iconRes)
        binding.iconFollow.imageTintList =
            ContextCompat.getColorStateList(context, iconTintRes)
    }

    private fun toggleFollow() {
        if (sessionGateway.requireCsrfToken() == null) {
            toast(context.getString(R.string.dialog_credential_error_retry_later))
            return
        }
        val action = if (isFollowing()) 2 else 1
        scope.launch {
            userRepository.modifyRelation(owner.mid, action)
                .onSuccess { response ->
                    if (response.isSuccess) {
                        relationAttribute = if (action == 1) 2 else 0
                        updateRelationState(
                            CheckRelationModel(
                                attribute = relationAttribute
                            )
                        )
                        toast(
                            if (action == 1) context.getString(R.string.dialog_follow_success)
                            else context.getString(R.string.dialog_unfollowed)
                        )
                    } else {
                        toast(response.errorMessage)
                    }
                }
                .onFailure {
                    AppLog.e(TAG, "toggleFollow failed", it)
                    toast(it.message ?: context.getString(R.string.dialog_action_failed))
                }
        }
    }

    private fun isFollowing(): Boolean {
        return relationAttribute == 2 || relationAttribute == 6
    }

    private fun checkLogin(): Boolean = sessionGateway.isLoggedIn()

    private fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    /**
     * 两级返回：焦点在视频列表 → 第一次返回把焦点上移到当前 tab（不关弹窗），再按一次才关闭；
     * 焦点已在 tab 栏/关注按钮等 header 区 → 返回直接关闭。
     * 项目开了 enableOnBackInvokedCallback，Dialog 的 KeyEvent/onBackPressed 拦截全是死代码；
     * 且弹窗是独立 window，Activity 的 dispatcher 收不到弹窗内的返回——
     * 必须注册到 ComponentDialog（AppCompatDialog 1.6+）自带的 onBackPressedDispatcher。
     */
    private fun installBackInterceptor() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.recyclerView.hasFocus() && focusTabFromContent()) return
                dismiss()
            }
        })
    }

    private fun installTvListFocusController() {        tvFocusController?.release()
        tvFocusController = TvListFocusController(
            recyclerView = binding.recyclerView,
            adapter = videoAdapter,
            strategy = GridTvFocusStrategy { 3 },
            canLoadMore = { activeState().hasMore },
            loadMore = {
                val state = activeState()
                if (!state.loading && state.hasMore) {
                    state.page++
                    loadTabPage(state)
                }
            }
        )
    }

    override fun dismiss() {
        tvFocusController?.release()
        tvFocusController = null
        videoTabState.prefetchJob = null
        replayTabState?.prefetchJob = null
        scope.cancel()
        super.dismiss()
    }
}
