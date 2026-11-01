package com.mytvb.ui.dialog

import android.content.Context
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import android.view.LayoutInflater
import android.view.Window
import com.mytvb.core.ui.base.ChoiceDialog
import com.mytvb.core.ui.base.DialogWindowFit

import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDialog
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentActivity
import com.google.android.material.tabs.TabLayout
import com.mytvb.core.ui.tab.enableDirectTabNavigation
import com.mytvb.core.ui.tab.focusNearestTabTo
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
import com.mytvb.core.common.coroutine.catchingRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import com.mytvb.core.ui.base.AppToast

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
            // 焦点导航下 RV 常不滚动（dy=0），onScrolled 收不到事件，必须在焦点
            // 落定时补查触底，否则要等焦点顶死最后一行才触发加载（体感"触底加载慢"）
            maybeTriggerLoadMore()
        },
        onItemDpad = { view, keyCode, event ->
            tvFocusController?.handleKey(view, keyCode, event) == true
        }
    ).also {
        it.currentPlayingAid = currentAid
    }

    private enum class TabKind { VIDEO, SERIES, SEASON }

    /**
     * 全局列表排序（对所有 tab 生效）：
     * 最新发布=视频 pubdate+合集系列最新在前；最早发布=合集系列正序（投稿接口无正序，视频仍最新发布）；
     * 最多播放=视频 click 排序，合集系列接口不支持播放排序→翻页拉全后客户端按 stat.view 降序；
     * 最多收藏仅视频投稿接口支持，合集系列保持最新在前（其响应无收藏数）。
     */
    private enum class OwnerListOrder(@StringRes val labelRes: Int) {
        NEWEST(R.string.model_search_order_pub_date),
        MOST_VIEWED(R.string.space_order_most_played),
        MOST_FAVORITE(R.string.model_search_order_most_collection),
        OLDEST(R.string.space_sort_oldest_first)
    }

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

        // 视频 tab「最早发布」倒翻模式缓存的总投稿数（末页跳转用）
        var totalCount: Int = 0
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

    // 全局排序：一次选择控制所有 tab 的列表顺序
    private var order = OwnerListOrder.NEWEST

    // 两级返回：焦点在视频列表 → 第一次返回焦点上移到当前 tab；焦点已在 tab/关注等 header 区 → 直接关闭
    // （回调注册在 Dialog 自己的 onBackPressedDispatcher 上，随 dialog lifecycle 自动移除）

    /** 是否允许自动跳 tab：用户焦点已在内容卡片或 tab 栏上时（正手动操作），一律不抢。 */
    private fun shouldAutoJump(): Boolean =
        !userEngagedContent && !binding.tabLayout.hasFocus()

    init {
        supportRequestWindowFeature(Window.FEATURE_NO_TITLE)
        setCanceledOnTouchOutside(true)
        // 设计尺寸 px1400×px935 固定卡片：fill 载体自动钳屏幕 92%，布局内部用约束
        // 自适应卡片实际大小；不再经独立窗口尺寸协商（华为电视会延迟裁高独立小窗）。
        DialogWindowFit.overlay(
            this, binding.root,
            context.resources.getDimensionPixelSize(R.dimen.px1400),
            heightPx = context.resources.getDimensionPixelSize(R.dimen.px935),
            tag = "ownerDetail"
        )
        // 点击关闭只由 DialogWindowFit 容器的暗区监听承担（点卡片外关闭）；
        // 不给内容根挂 dismiss——那会把卡片内空白点击也变成关闭。
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
        binding.buttonOrder.setOnClickListener { showOrderDialog() }
        binding.buttonOrder.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) {
                return@setOnKeyListener false
            }
            when (keyCode) {
                // 头部行（关注按钮左侧）：下进 tab 栏、右到关注、上方无控件消费防乱跳
                KeyEvent.KEYCODE_DPAD_DOWN -> binding.tabLayout.focusSelectedTab()
                KeyEvent.KEYCODE_DPAD_RIGHT -> binding.buttonFollow.requestFocus()
                KeyEvent.KEYCODE_DPAD_UP -> true
                else -> false
            }
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
            iconRes = R.drawable.ic_plus
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
                    if (currentSeasonId > 0L) {
                        // 已知归属（播放页 ugcSeason.id）：该合集/系列 tab 直接并行翻页搜索，
                        // 不必等投稿流烧完 10 页——投稿按最新排序，老视频页数很深。
                        tabStates.drop(1).firstOrNull {
                            it.seasonId == currentSeasonId || it.seriesId == currentSeasonId
                        }?.let { target ->
                            target.searching = true
                            AppLog.i(
                                TAG,
                                "locate: parallel search on known collection tab=${target.title} id=$currentSeasonId"
                            )
                        }
                    }
                    // UP 有直播回放系列时 fallback 链迟早要搜它：与投稿流并行翻页，
                    // 回放视频（不在投稿流里）的定位从"先烧完 10 页投稿"缩短为回放流自身翻页耗时。
                    replayState?.searching = true
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
                tvFocusController?.focusPrimary() == true
            },
            onNavigateUp = {
                // tab 正上方最近的可达控件是头部行排序按钮（全屏页同款：tab UP → 排序按钮）
                if (binding.buttonOrder.isVisible) binding.buttonOrder.requestFocus() else false
            },
            onEdgeRight = {
                // 最右 tab 右键：聚焦同行右端的排序按钮
                if (binding.buttonOrder.isVisible) binding.buttonOrder.requestFocus() else false
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
                iconRes = R.drawable.ic_check
            )
            relationAttribute == 2 -> updateFollowUi(
                labelRes = R.string.followed,
                iconRes = R.drawable.ic_check
            )
            else -> updateFollowUi(
                labelRes = R.string.follow,
                iconRes = R.drawable.ic_plus
            )
        }
    }

    private fun activeState(): TabState = tabStates[currentIndex]

    /** 合集/系列接口只支持时间方向：最多播放时改由客户端拉全量后按 stat.view 排序。 */
    private fun needsClientSideSort(state: TabState): Boolean =
        state.kind != TabKind.VIDEO && order == OwnerListOrder.MOST_VIEWED

    /**
     * 视频 tab「最早发布」：投稿接口无正序参数，改为跳到末页（最老的一页）倒着翻、
     * 页内反转（onSuccess），对用户呈现老→新顺序。
     */
    private fun videoOldestMode(state: TabState): Boolean =
        state.kind == TabKind.VIDEO && order == OwnerListOrder.OLDEST

    /** 触底翻页方向：倒翻模式页码递减，其余递增。 */
    private fun advancePage(state: TabState) {
        if (videoOldestMode(state)) state.page-- else state.page++
    }

    /** 预取的“下一页”页码（倒翻模式为当前页-1；已到第1页边缘时返回0禁预取，
     *  避免“页码1”被 fetchTabPage 当成首次请求再次跳到末页）。 */
    private fun nextPageOf(state: TabState): Int =
        if (videoOldestMode(state)) {
            if (state.page > 2) state.page - 1 else 0
        } else {
            state.page + 1
        }

    /** 请求失败回滚页码（与 advancePage 反向）。 */
    private fun rollbackPage(state: TabState) {
        if (videoOldestMode(state)) state.page++ else state.page--
    }

    private suspend fun fetchTabPage(state: TabState, page: Int): TabPage =
        when (state.kind) {
            TabKind.VIDEO -> {
                // 「最早发布」：投稿接口无正序参数，跳末页倒着翻（fetchTabPage 内换算页码）
                if (videoOldestMode(state)) {
                    // 首屏 = 总数尚未探得。不能用 page<=1 判首屏：倒翻推进到第 1 页时
                    // 会被换成末页重拉，页码在末页与次页间震荡，列表反复 REPLACE 回顶
                    val probing = state.totalCount <= 0
                    if (probing) {
                        val probe = userRepository.getUserDynamic(
                            owner.mid, page = 1, pageSize = PAGE_SIZE
                        ).getOrThrow().data ?: error("empty data")
                        state.totalCount = probe.totalCount
                    }
                    val maxPage = ((state.totalCount + PAGE_SIZE - 1) / PAGE_SIZE).coerceAtLeast(1)
                    val pn = if (probing) maxPage else page.coerceAtMost(maxPage)
                    AppLog.d(TAG, "fetch video oldest pn=$pn total=${state.totalCount}")
                    val data = userRepository.getUserDynamic(
                        owner.mid, page = pn, pageSize = PAGE_SIZE
                    ).getOrThrow().data ?: error("empty data")
                    state.page = pn
                    TabPage(data.archives, pn > 1)
                } else {
                    val orderValue = when (order) {
                        OwnerListOrder.MOST_VIEWED -> "click"
                        OwnerListOrder.MOST_FAVORITE -> "stow"
                        else -> "pubdate"
                    }
                    AppLog.d(TAG, "fetch video page=$page order=$orderValue")
                    val data = userRepository.getUserDynamic(
                        owner.mid, page = page, pageSize = PAGE_SIZE,
                        order = orderValue
                    ).getOrThrow().data ?: error("empty data")
                    TabPage(data.archives, data.hasMore)
                }
            }
            TabKind.SERIES -> {
                val data = userRepository.getSeriesArchives(
                    owner.mid, seriesId = state.seriesId, page = page, pageSize = PAGE_SIZE,
                    sortDesc = order != OwnerListOrder.OLDEST
                ).getOrThrow().data ?: error("empty data")
                TabPage(data.archives, data.hasMore)
            }
            TabKind.SEASON -> {
                // seasons_archives_list 的 page 只带 total（无 num/size/has_more），按总数算翻页
                val data = userRepository.getSeasonArchives(
                    owner.mid, seasonId = state.seasonId, page = page, pageSize = PAGE_SIZE,
                    sortDesc = order != OwnerListOrder.OLDEST
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
            val result = catchingRequest {
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
                // 倒翻模式页内反转：接口返回仍是新→老，反转后呈现老→新
                val videos = ContentFilter.filterVideos(binding.root.context, pageData.videos)
                    .let { if (videoOldestMode(state)) it.reversed() else it }
                state.hasMore = pageData.hasMore
                // 首批判定只看列表是否已空：倒翻末批页码是 1，若按页码判会走 REPLACE 重置滚动位置
                val isFirstBatch = state.items.isEmpty()
                if (isFirstBatch) {
                    state.loadedFirstPage = true
                    state.items = videos.toMutableList()
                } else {
                    state.items.addAll(videos)
                }
                // 合集/系列 + 最多播放：接口不支持播放排序，翻页拉全后客户端按播放量排序，
                // 中途不铺 UI（footer 转圈示意加载中）
                if (needsClientSideSort(state)) {
                    if (stillCurrent && pageToLoad == 1) {
                        videoAdapter.setData(emptyList())
                        videoAdapter.setShowLoadMore(true)
                    }
                    if (state.hasMore && state.page < MAX_SEARCH_PAGES) {
                        state.page++
                        loadTabPage(state)
                        return@onSuccess
                    }
                    val sorted = state.items.sortedByDescending { it.stat?.view ?: it.play }
                    state.items = sorted.toMutableList()
                    state.hasMore = false
                    if (state.locatedIndex >= 0) {
                        state.locatedIndex = sorted.indexOfFirst { matchesCurrentVideo(it) }
                    }
                    AppLog.i(TAG, "client sort done: ${state.title} items=${sorted.size} top=${sorted.firstOrNull()?.stat?.view}")
                    if (stillCurrent) {
                        videoAdapter.setData(state.items) {
                            tvFocusController?.onDataChanged(TvDataChangeReason.REPLACE_PRESERVE_ANCHOR)
                        }
                        videoAdapter.setShowLoadMore(false)
                        updateEmptyState()
                    }
                    return@onSuccess
                }
                if (isFirstBatch) {
                    if (stillCurrent) {
                        videoAdapter.setData(state.items) {
                            tvFocusController?.onDataChanged(TvDataChangeReason.REPLACE_PRESERVE_ANCHOR)
                        }
                    }
                } else {
                    if (stillCurrent) {
                        videoAdapter.addData(videos)
                        tvFocusController?.onDataChanged(TvDataChangeReason.APPEND)
                    }
                }
                if (stillCurrent) {
                    videoAdapter.setShowLoadMore(state.hasMore)
                    updateEmptyState()
                }
                handleSearchProgress(state, pageToLoad, stillCurrent)
                // 当前页渲染成功后，立即在后台预取下一页，把网络往返藏进用户浏览过程。
                // 递归搜索路径也会消费它，让"从旧视频打开"的串行循环每步都更快；
                // 搜索中的后台 tab 同样预取（并行定位线的翻页间隔也砍半）。
                if (stillCurrent || state.searching) {
                    schedulePrefetch(state, nextPageOf(state))
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
                rollbackPage(state)
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
        if (nextPage < 1) return
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
                    advancePage(s)
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
        // 客户端排序未完成（还有页未拉）：铺空列表+转圈，拉全排序后由回调统一铺
        val showItems = if (needsClientSideSort(state) && state.hasMore) emptyList() else state.items
        videoAdapter.setData(showItems) {
            tvFocusController?.onDataChanged(TvDataChangeReason.REPLACE_PRESERVE_ANCHOR)
        }
        if (autoFocus && state.locatedIndex >= 0) {
            // 自动定位/fallback 切换：滚到定位视频并聚焦（焦点从 tab 栏/弹窗外接手过来）
            scrollToCurrentVideo(state.items)
        } else if (state.scrollPos > 0) {
            // 用户手动 OK 切换：只恢复离开时的滚动位置，焦点留在 tab 栏不抢
            binding.recyclerView.scrollToPosition(state.scrollPos)
        }
        updateEmptyState()
        maybeLoadActiveTabFirstPage()
    }

    /** 空 tab 提示：当前 tab 首屏加载完成且无内容时显示（加载中/失败/客户端排序拉全中/有内容都隐藏）。 */
    private fun updateEmptyState() {
        val state = activeState()
        binding.tvEmpty.visibility = if (
            state.loadedFirstPage && state.items.isEmpty() &&
            !(needsClientSideSort(state) && state.hasMore)
        ) View.VISIBLE else View.GONE
    }

    /** 当前 tab 首屏还没加载就补发（切 tab 时若该 tab 请求在飞，state.loading 会挡住，回调里兜底）。 */
    private fun maybeLoadActiveTabFirstPage() {
        val state = activeState()
        // 客户端排序进行到一半（后台预载只拉了首页）：接续拉剩余页
        if (needsClientSideSort(state) && state.loadedFirstPage && state.hasMore && !state.loading) {
            state.page++
            loadTabPage(state)
            return
        }
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
                tvFocusController?.focusPrimary() == true
            },
            onNavigateUp = {
                // tab 正上方最近的可达控件是头部行排序按钮（全屏页同款：tab UP → 排序按钮）
                if (binding.buttonOrder.isVisible) binding.buttonOrder.requestFocus() else false
            },
            onEdgeRight = {
                // 最右 tab 右键：聚焦头部行（关注按钮左侧）的排序按钮
                binding.buttonOrder.requestFocus()
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

    /** 全局排序变化：重置所有 tab 的列表状态，当前 tab 立即重载，其余切入时补发首屏。 */
    private fun resetAllTabsForOrderChange() {
        tabStates.forEach { s ->
            s.items.clear()
            s.page = 1
            s.hasMore = true
            s.loadedFirstPage = false
            s.searching = false
            s.searchDone = false
            s.scrollPos = -1
            s.totalCount = 0
            s.prefetchJob?.cancel()
            s.prefetchJob = null
        }
        videoAdapter.setData(emptyList())
        videoAdapter.setShowLoadMore(true)
        binding.recyclerView.scrollToPosition(0)
        updateEmptyState()
        loadTabPage(activeState())
    }

    private fun showOrderDialog() {
        val orders = OwnerListOrder.values()
        ChoiceDialog.show(
            context = context,
            title = context.getString(R.string.video_order),
            options = orders.map { context.getString(it.labelRes) },
            selectedIndex = orders.indexOf(order)
        ) { which ->
            val newOrder = orders[which]
            if (newOrder != order) {
                order = newOrder
                binding.textOrder.setText(newOrder.labelRes)
                resetAllTabsForOrderChange()
            }
        }
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

    /** 白描边胶囊按钮样式：文字/图标统一白色，状态差异仅靠文案与 +/✓ 图标区分。 */
    private fun updateFollowUi(
        labelRes: Int,
        iconRes: Int
    ) {
        binding.textFollow.text = context.getString(labelRes)
        binding.textFollow.setTextColor(ContextCompat.getColor(context, R.color.white))
        binding.iconFollow.setImageResource(iconRes)
        binding.iconFollow.imageTintList =
            ContextCompat.getColorStateList(context, R.color.white)
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
                    if (it is CancellationException) return@onFailure
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
        AppToast.show(context, message)
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
                    advancePage(state)
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
