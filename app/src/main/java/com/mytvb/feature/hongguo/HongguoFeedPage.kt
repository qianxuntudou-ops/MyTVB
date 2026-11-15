package com.mytvb.feature.hongguo

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mytvb.R
import com.mytvb.core.ui.base.AppToast
import com.mytvb.core.ui.base.adaptiveSpanCount
import com.mytvb.core.ui.decoration.GridSpacingItemDecoration
import com.mytvb.core.ui.focus.tv.GridTvFocusStrategy
import com.mytvb.core.ui.focus.tv.TvListFocusController
import com.mytvb.core.common.log.AppLog
import com.mytvb.core.ui.layout.WrapContentGridLayoutManager
import com.mytvb.core.ui.refresh.SwipeRefreshHelper
import com.mytvb.databinding.FragmentHongguoFeedBinding
import com.mytvb.feature.hongguo.net.HongguoClient
import com.mytvb.feature.hongguo.net.HongguoCoverUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

/**
 * 红果列表页：推荐/历史/收藏/分类共用（榜单是独立横排行页不走本页）。
 * @param mode feed=在线 landpage（推荐/分类）；history/favorite=本地记录
 *
 * 2026-11-10 卡片迁移：自绘 cell_hongguo_drama → 全 App 统一 B 站卡片
 * （[HongguoDramaAdapter]，竖版 4:5，对齐 CCTV 片库网格观感）：
 * 列数统一 [R.dimen] 6 列档（原 feed 4 列/历史 3 列），焦点链 TvListFocusController 收口。
 */
class HongguoFeedPage : Fragment(), HongguoPageFocus {

    private var _binding: FragmentHongguoFeedBinding? = null
    private val binding get() = _binding!!
    private val repository by lazy { HongguoRepository.get(requireContext()) }
    private val adapter = HongguoDramaAdapter(
        onItemClick = ::openDetail,
        onItemLongPress = { item, _ ->
            HongguoCardMenuDialog.show(requireContext(), repository, item)
        }
    )
    private var tvFocusController: TvListFocusController? = null
    private var swipeRefreshLayout: SwipeRefreshLayout? = null

    // 竖版 4:5 卡片列数对齐 CCTV 片库（6 列/超宽 12 列）
    private val spanCount by lazy { resources.adaptiveSpanCount(base = 6, wide = 12) }

    /** 触底预拉阈值：末两行（原 4 个太小，append 一页 15 个后旧位置立刻脱区，续拉空窗）。 */
    private val loadMoreThreshold get() = spanCount * 2

    private var loadJob: Job? = null
    private var offset = 0
    private var sessionId = ""
    private var hasMore = true
    private var loading = false

    /** 打开详情时的卡片位置：播放器返回后焦点恢复的兜底（触摸模式无锚点可用）。 */
    private var lastOpenedPosition = RecyclerView.NO_POSITION

    /**
     * 卡片/筛选行 UP 越过本页二级 tab（筛选行）后的顶部落点钩子：
     * 独立页（推荐/历史/收藏）默认 null=宿主红果一级 tab；分类页子档注入"回分类子档 tab"。
     */
    var topEdgeExit: (() -> Boolean)? = null

    // ---- 筛选面板（selector_rows） ----
    private val filterAdapter = FilterChipAdapter()
    private var selectorRows: List<HongguoClient.SelectorRow> = emptyList()
    private val selectedFilters = HashMap<String, String>()   // type -> selector_item_id

    /** 推荐受众默认注入只做一次（面板拿到后注入并重开一次会话；用户手动选择不再覆盖）。 */
    private var genderInjected = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHongguoFeedBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val mode = requireArguments().getString(ARG_MODE, MODE_FEED)
        val genre = requireArguments().getString(ARG_GENRE, "")
        binding.recyclerFeed.layoutManager = WrapContentGridLayoutManager(requireContext(), spanCount)
        binding.recyclerFeed.adapter = adapter
        binding.recyclerFeed.itemAnimator = null
        binding.recyclerFeed.addItemDecoration(
            GridSpacingItemDecoration(
                spanCount,
                resources.getDimensionPixelSize(R.dimen.px16),
                false
            )
        )
        // 卡片第一行 UP：筛选行可见先回筛选行（二级 tab），再 UP 经 topEdgeExit 回顶部 tab
        adapter.bindTopEdgeUp { focusAboveContent() }
        adapter.setOnItemFocusListeners(
            onFocused = { position ->
                if (position >= adapter.contentCount() - loadMoreThreshold) {
                    loadMore(mode, genre)
                }
            },
            onFocusedWithView = { view, position ->
                tvFocusController?.onItemFocused(view, position)
            }
        )
        tvFocusController = TvListFocusController(
            recyclerView = binding.recyclerFeed,
            adapter = adapter,
            strategy = GridTvFocusStrategy { spanCount },
            canLoadMore = { hasMore },
            loadMore = { loadMore(mode, genre) },
            debugName = "hongguo_feed"
        )
        binding.recyclerFilters.adapter = filterAdapter
        binding.recyclerFilters.layoutManager =
            androidx.recyclerview.widget.LinearLayoutManager(
                requireContext(), androidx.recyclerview.widget.RecyclerView.HORIZONTAL, false
            )
        // RV 自身不可聚焦：RecyclerView 构造器自带 focusable=true，但它拿焦无高亮
        // （视觉=焦点消失）。筛选 chip 竖向逃逸失败放行时，几何搜索里 RV 自身以
        // 最近距离赢过一切子项（实测 chip DOWN→recycler_feed 自身），必须除名。
        // 全部入口都显式落子项（TvListFocusController/chip 绑定），无人依赖 RV 自身。
        binding.recyclerFeed.isFocusable = false
        binding.recyclerFilters.isFocusable = false
        // 登录后云端数据落库事件：历史/收藏页未登录时已被预载成空列表，这里重拉
        viewLifecycleOwner.lifecycleScope.launch {
            repository.loginEvents.collect {
                if (mode == MODE_HISTORY || mode == MODE_FAVORITE) load(mode, genre, withPanel = false)
            }
        }
        binding.recyclerFeed.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy > 0) checkLoadMore(mode, genre)
            }
        })
        // 触摸下拉刷新（B 站首页推荐同款；SwipeRefreshLayout 只拦触摸手势，遥控焦点链不受影响；
        // 转圈色取全 app 高亮统一粉，与本页中央圈一致）
        swipeRefreshLayout = SwipeRefreshHelper.wrapRecyclerView(
            binding.recyclerFeed,
            onRefresh = {
                AppLog.d("Hongguo", "swipe refresh mode=$mode")
                refresh()
            }
        ) {
            setColorSchemeResources(R.color.tab_highlightClassic)
        }
        reload(mode, genre)
    }

    /** 列表 UP 边缘回红果页顶部 tab 栏（宿主 HongguoFragment）。 */
    private fun focusTopTab(): Boolean {
        val host = parentFragment as? HongguoFragment ?: return false
        return host.focusTopTab()
    }

    /** 卡片第一行 UP 的落点：筛选行可见先回筛选行（二级 tab），否则顶部 tab。 */
    private fun focusAboveContent(): Boolean {
        if (focusSecondaryTab()) return true
        return topEdgeExit?.invoke() ?: focusTopTab()
    }

    /** 二级 tab 落点：筛选 chip 行（仅在线 feed 有 selector_rows 时可见）。 */
    override fun focusSecondaryTab(): Boolean {
        if (_binding == null || binding.recyclerFilters.visibility != View.VISIBLE) return false
        // 优先回用户当前选中的筛选维度 chip，无选中维度落第一枚
        val selectedPos = selectorRows.indexOfFirst { selectedFilters.containsKey(it.type) }
        val holder = binding.recyclerFilters
            .findViewHolderForAdapterPosition(if (selectedPos >= 0) selectedPos else 0)
        return holder?.itemView?.requestFocus() == true
    }

    /** BACK 分层回退：卡片 → 筛选行；已在筛选行/筛选行不可见返回 false 交上层。 */
    override fun backToParentLayer(): Boolean {
        if (_binding == null) return false
        if (binding.recyclerFilters.hasFocus()) return false
        return focusSecondaryTab()
    }

    /** 侧栏 RIGHT / tab DOWN 的入口：聚焦第一张卡片（TvListFocusController 统一走）。 */
    override fun focusFirstCard(): Boolean {
        if (!isAdded || view == null) return false
        return tvFocusController?.requestFocusPosition(0, allowOutsideFocus = true) == true
    }

    /** tab 首次选中回顶（retain/进程重启恢复的旧滚动位置）；之后切换保持位置。 */
    private var topRestoredOnce = false

    override fun scrollToListTop() {
        if (_binding == null || topRestoredOnce) return
        topRestoredOnce = true
        binding.recyclerFeed.scrollToPosition(0)
    }

    private fun openDetail(item: DramaUi, position: Int) {
        lastOpenedPosition = position
        HongguoDetailDialog.show(requireContext(), repository, item.seriesId, item.score, item)
    }

    /** 焦点是否落在 tab 行（一级页签 TabLayout / 分类子档 tab / 榜单分段控件）。 */
    private fun isFocusInsideTabBar(): Boolean {
        var cur = view?.rootView?.findFocus() ?: return false
        while (true) {
            if (cur is com.google.android.material.tabs.TabLayout || cur is HongguoSegmentedTabs) {
                return true
            }
            cur = cur.parent as? View ?: return false
        }
    }

    /**
     * 菜单键/重选一级 tab/触摸下拉三入口的统一刷新（宿主 HongguoFragment 转发）。
     * 重拉 + 回顶：feed 模式清列表后自然回顶，历史/收藏本地数据同步秒显后
     * scrollToPosition 立即生效，云端合并静默不打断（loadJob 自取消重入安全）。
     */
    override fun refresh() {
        val mode = requireArguments().getString(ARG_MODE, MODE_FEED)
        val genre = requireArguments().getString(ARG_GENRE, "")
        AppLog.d("Hongguo", "feed refresh enter mode=$mode itemsBefore=${adapter.itemCount}")
        reload(mode, genre)
        binding.recyclerFeed.scrollToPosition(0)
    }

    private fun reload(mode: String, genre: String) {
        AppLog.d("Hongguo", "reload mode=$mode clearFrom=${adapter.itemCount}")
        // 历史/收藏刷新不清列表：当前已是本地+云端合并视图，clear+本地秒显会先闪回
        // 纯本机列表（缺云端条目且顺序不同）再跳回 merged（日志实锤两次内容跳变）；
        // 保留旧视图直接重拉云端合并，DiffUtil 平滑落地。feed 仍需清（重开会话分页流）
        if (mode == MODE_HISTORY || mode == MODE_FAVORITE) {
            offset = 0; sessionId = ""; hasMore = false
            load(mode, genre, withPanel = false)
            return
        }
        offset = 0; sessionId = ""; hasMore = true
        // 取消在飞的翻页请求并复位标志：否则 loading 早退会把刷新的 load() 整个吞掉
        // （列表已清、无新请求、无空态），旧轮完成后还会把旧 sessionId/offset 写回污染新会话
        loadJob?.cancel()
        loading = false
        adapter.clear()
        adapter.notifyDataSetChanged()
        load(mode, genre, withPanel = selectorRows.isEmpty())
    }

    private fun loadMore(mode: String, genre: String) {
        if (loading || !hasMore) return
        load(mode, genre, withPanel = false)
    }

    /**
     * 触底加载统一判定（feed/分类模式）：贴尾阈值 或 已滚到硬底即拉。
     * 硬底判定是触摸场景的关键：滑到列表最底后不再产生滚动事件（onScrolled 收不到）；
     * 首页 15 条不满一屏时列表不可滚动、滚动事件全程为零，只能靠"到底即拉"+数据
     * 落地后续拉补查驱动（B 站列表"落地即续拉"同款修法）。历史/收藏 hasMore=false 直通。
     */
    private fun checkLoadMore(mode: String, genre: String) {
        if (loading || !hasMore) return
        if (_binding == null) return
        val rv = binding.recyclerFeed
        val lm = rv.layoutManager as? GridLayoutManager ?: return
        val nearTail = lm.findLastVisibleItemPosition() >= adapter.contentCount() - loadMoreThreshold
        val atBottom = !rv.canScrollVertically(1)
        if (nearTail || atBottom) {
            AppLog.d("Hongguo", "feed load_more trigger mode=$mode offset=$offset " +
                "items=${adapter.contentCount()} nearTail=$nearTail atBottom=$atBottom")
            loadMore(mode, genre)
        }
    }

    private fun load(mode: String, genre: String, withPanel: Boolean) {
        when (mode) {
            MODE_HISTORY -> {
                // 首屏（列表空）本地记录秒显；刷新时保留当前视图直接重拉云端合并
                if (adapter.contentCount() == 0) {
                    val localItems = repository.history().map { it.toUi() }
                    AppLog.d("Hongguo", "local shown mode=history count=${localItems.size} " +
                        "top3=${localItems.take(3).joinToString { it.seriesId }}")
                    adapter.setDataDeduplicated(localItems)
                    binding.textEmpty.visibility =
                        if (adapter.contentCount() == 0) View.VISIBLE else View.GONE
                }
                hasMore = false
                loadJob?.cancel()
                loadJob = viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        // 云端合并静默进行；仅列表空（未登录/无记录）时给中央圈反馈
                        val wasEmpty = adapter.contentCount() == 0
                        if (wasEmpty) {
                            binding.progressLoading.visibility = View.VISIBLE
                        }
                        val merged = repository.mergedHistory()
                        val mergedItems = merged.map { it.toUi() }
                        AppLog.d("Hongguo", "merged shown mode=history count=${mergedItems.size} " +
                            "top3=${mergedItems.take(3).joinToString { it.seriesId }}")
                        adapter.setDataDeduplicated(mergedItems)
                        binding.textEmpty.visibility = if (adapter.contentCount() == 0) View.VISIBLE else View.GONE
                        // 页面预载（未登录空表）→ 登录后才填进云端 30 条：首次填充回顶，
                        // 否则切到本 tab 时停在预载期的滚动/焦点残留位置
                        if (wasEmpty && adapter.contentCount() > 0) {
                            binding.recyclerFeed.scrollToPosition(0)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } finally {
                        binding.progressLoading.visibility = View.GONE
                        swipeRefreshLayout?.isRefreshing = false
                    }
                }
                return
            }
            MODE_FAVORITE -> {
                // 首屏（列表空）本地秒显；刷新时保留当前视图直接重拉云端书架合并
                // （2026-11-14 补云端；当日再修刷新闪卡：不再 clear+本地秒显中间态）
                if (adapter.contentCount() == 0) {
                    val localItems = repository.favorites().map { it.toUi() }
                    AppLog.d("Hongguo", "local shown mode=favorite count=${localItems.size} " +
                        "top3=${localItems.take(3).joinToString { it.seriesId }}")
                    adapter.setDataDeduplicated(localItems)
                    binding.textEmpty.visibility =
                        if (adapter.contentCount() == 0) View.VISIBLE else View.GONE
                }
                hasMore = false
                loadJob?.cancel()
                loadJob = viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        val wasEmpty = adapter.contentCount() == 0
                        if (wasEmpty) {
                            binding.progressLoading.visibility = View.VISIBLE
                        }
                        val merged = repository.mergedFavorites()
                        val mergedItems = merged.map { it.toUi() }
                        AppLog.d("Hongguo", "merged shown mode=favorite count=${mergedItems.size} " +
                            "top3=${mergedItems.take(3).joinToString { it.seriesId }}")
                        adapter.setDataDeduplicated(mergedItems)
                        binding.textEmpty.visibility = if (adapter.contentCount() == 0) View.VISIBLE else View.GONE
                        if (wasEmpty && adapter.contentCount() > 0) {
                            binding.recyclerFeed.scrollToPosition(0)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } finally {
                        binding.progressLoading.visibility = View.GONE
                        swipeRefreshLayout?.isRefreshing = false
                    }
                }
                return
            }
        }
        val path = when {
            genre == "comic" -> "/category/comic-drama"
            genre == "ai" -> "/category/ai-drama"
            else -> "/category/short-drama"
        }
        loading = true
        // 加载更多静默追加（对齐 B 站列表 BaseListFragment 行为），
        // 中央圈只在首屏/筛选刷新（列表为空）时出现，下滑途中不再盖屏
        if (adapter.contentCount() == 0) {
            binding.progressLoading.visibility = View.VISIBLE
        }
        loadJob?.cancel()
        val job = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val page = repository.feed(path, offset, sessionId, selectedFilters, withPanel)
                sessionId = page.sessionId
                // 空页=数据枯竭兜底：服务端 has_more 偶发不归零，防落地续拉原地空转
                hasMore = page.hasMore && page.items.isNotEmpty()
                offset = if (page.nextOffset > 0) page.nextOffset else offset + page.items.size
                adapter.addData(page.items.map { it.toUi() })
                binding.textEmpty.visibility = if (adapter.contentCount() == 0) View.VISIBLE else View.GONE
                if (page.selectorRows.isNotEmpty()) {
                    selectorRows = page.selectorRows
                    binding.recyclerFilters.visibility = View.VISIBLE
                    filterAdapter.notifyDataSetChanged()
                    maybeInjectGenderFilter(mode, genre)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppToast.show(requireContext(), e.message ?: getString(R.string.hongguo_load_failed))
                binding.textEmpty.visibility = if (adapter.contentCount() == 0) View.VISIBLE else View.GONE
            } finally {
                // 代际校验：被 reload 取消的旧轮 finally 仍会异步跑（loading 复位/收尾），
                // 只有仍是最新一轮时才收尾，避免污染新轮的 loading 标志
                if (loadJob === coroutineContext[Job]) {
                    loading = false
                    binding.progressLoading.visibility = View.GONE
                    swipeRefreshLayout?.isRefreshing = false
                    // 刷新清列表会让卡片焦点悬空（数据异步回来前）：落地后校一次焦点，
                    // 无效则回锚点/首卡；loadMore 时焦点有效，ensureValidFocus 零开销直通
                    tvFocusController?.ensureValidFocus("feedLoad")
                    // 落地即续拉：post 一帧等 layout 完成，视口仍贴硬底/阈值区
                    // （首屏不满屏、滑到底停手）时立即预取下一页，消除"到底干等"空窗
                    binding.recyclerFeed.post {
                        if (_binding != null) checkLoadMore(mode, genre)
                    }
                }
            }
        }
        loadJob = job
    }

    /** 点选某维度选项：更新选中 → 重开会话（offset=0 + panel）。 */
    private fun applyFilter(row: HongguoClient.SelectorRow, itemId: String) {
        if (itemId.isEmpty()) selectedFilters.remove(row.type)
        else selectedFilters[row.type] = itemId
        filterAdapter.notifyDataSetChanged()
        reload(requireArguments().getString(ARG_MODE, MODE_FEED), requireArguments().getString(ARG_GENRE, ""))
    }

    /**
     * 推荐受众设置注入（对照 HGTV recommendGender：设置改受众，下次进推荐生效）。
     * gender 面板的 selector_item_id 由接口下发（"0"/"1"），按「男/女」字面匹配选项，
     * 避免硬编码 id 语义漂移；匹配不到（面板缺失/改名）静默跳过不注入。
     */
    private fun maybeInjectGenderFilter(mode: String, genre: String) {
        if (genderInjected || mode != MODE_FEED || selectedFilters.containsKey("gender")) {
            genderInjected = true
            return
        }
        val stored = repository.recommendGenderStored()
        genderInjected = true
        if (stored == "不限") return
        val genderRow = selectorRows.firstOrNull { it.type == "gender" } ?: return
        // 面板选项名以接口下发为准：先精确匹配设置文案，退化按「男/女」字面（兼容「男生/女生」等变体）
        val want = if (stored == "男频") "男" else "女"
        val matched = genderRow.items.firstOrNull { it.name == stored }
            ?: genderRow.items.firstOrNull { it.name.contains(want) } ?: return
        selectedFilters["gender"] = matched.id
        filterAdapter.notifyDataSetChanged()
        // 面板已就位，reload 走 withPanel=false 只重拉内容
        reload(mode, genre)
    }

    // ---- 筛选 chip：每维度一枚，显示当前值，点击弹 ChoiceDialog ----
    inner class FilterChipAdapter : RecyclerView.Adapter<FilterChipAdapter.Holder>() {
        override fun getItemCount(): Int = selectorRows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.cell_hongguo_suggest, parent, false)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(selectorRows[position])
        }

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            fun bind(row: HongguoClient.SelectorRow) {
                val text = itemView as? android.widget.TextView ?: return
                val selectedId = selectedFilters[row.type]
                val selectedName = row.items.firstOrNull { it.id == selectedId }?.name
                    ?: getString(R.string.hongguo_filter_all)
                text.text = "${row.name}·$selectedName"
                text.isSelected = selectedId != null
                // 筛选 chip 竖向逃逸：UP 直接回顶部 tab（topEdgeExit 分流分类页子档），
                // DOWN 落第一张卡；左右走横向列表框架自然导航。
                // 竖向恒吞：落点失败时（数据未就绪等）放行会被几何搜索送去
                // RV 自身/侧栏（满高侧栏零距离陷阱同族），宁可停住等下次按键
                text.setOnKeyListener { _, keyCode, event ->
                    if (event.action != android.view.KeyEvent.ACTION_DOWN) {
                        return@setOnKeyListener false
                    }
                    when (keyCode) {
                        android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                            topEdgeExit?.invoke() ?: focusTopTab()
                            true
                        }
                        android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                            focusFirstCard()
                            true
                        }
                        else -> false
                    }
                }
                text.setOnClickListener {
                    val names = listOf(getString(R.string.hongguo_filter_all)) + row.items.map { it.name }
                    val current = if (selectedId == null) 0
                    else row.items.indexOfFirst { it.id == selectedId } + 1
                    com.mytvb.core.ui.base.ChoiceDialog.show(
                        requireContext(), row.name, names, current
                    ) { index ->
                        applyFilter(row, if (index == 0) "" else row.items[index - 1].id)
                    }
                }
            }
        }
    }

    // 分类页卡片副标题=评分 + 类型行（8.6 · 玄幻 · 科幻…，官方副标题同构）；tags 缺失时
    // 退回 toDramaUi 的评分/集数行兜底
    private fun HongguoClient.Drama.toUi(): DramaUi =
        toDramaUi(requireContext()).let {
            if (tags.isNotEmpty()) {
                val line = (listOf(score.takeIf { s -> s.isNotBlank() }) + tags)
                    .filterNotNull().joinToString(" · ")
                it.copy(subtitle = line)
            } else {
                it
            }
        }

    private fun HongguoRepository.WatchRecord.toUi(): DramaUi {
        val percent = if (durationMs > 0) (positionMs * 100 / durationMs).toInt() else 0
        return DramaUi(
            seriesId = seriesId,
            title = title,
            // 存量记录可能带着已过期的签名 URL，读出时统一改写救活
            cover = HongguoCoverUrl.rewrite(cover),
            subtitle = when {
                episodeCount <= 0 -> ""
                // WatchRecord.episodeIndex 0-based（与 Mine/Shelf 的 seen=e+1 同口径）
                episodeIndex + 1 >= episodeCount -> getString(R.string.hongguo_seen_done)
                else -> getString(R.string.hongguo_seen_episode, episodeIndex + 1, episodeCount)
            },
            // 封面右下共 N 集 + 右上形态标签（AI剧/漫剧/真人剧，与货架页同口径；
            // contentType 来自云端历史行 content_type，本地记录由 fillFrom 云端互补）
            episodeCount = episodeCount,
            statusTag = if (contentType.isNotEmpty()) formLabelOf(requireContext(), contentType) else "",
            progressPercent = percent.coerceIn(0, 100)
        )
    }

    override fun onPause() {
        // 与各列表页同款：离开（切页签/进播放器）前捕获锚点，返回时恢复
        tvFocusController?.captureCurrentAnchor()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        val controller = tvFocusController ?: return
        // ViewPager2 换页同样触发 onResume：此时焦点属 tab 行（用户正在 tab 上切页），
        // 而 restoreFocusAfterReturn 会把焦点从 tab 强拉回锚点卡片、随后又被 TabLayout
        // 的切页校正抢回——正是「切 tab 焦点先闪到卡片再回 tab」的根源。
        // 焦点在 tab 行时跳过恢复，恢复逻辑只服务播放器/弹窗返回场景。
        if (isFocusInsideTabBar()) return
        // 从播放器/弹窗返回：锚点恢复优先；触摸模式无锚点时退回打开详情时的卡片
        controller.restoreFocusAfterReturn(
            onFailed = {
                if (lastOpenedPosition != RecyclerView.NO_POSITION &&
                    controller.requestFocusPosition(lastOpenedPosition, allowOutsideFocus = true)
                ) {
                    return@restoreFocusAfterReturn
                }
                controller.ensureValidFocus("resume")
            }
        )
    }

    override fun onDestroyView() {
        loadJob?.cancel()
        lastOpenedPosition = RecyclerView.NO_POSITION
        tvFocusController?.release()
        tvFocusController = null
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_MODE = "mode"
        private const val ARG_GENRE = "genre"
        const val MODE_FEED = "feed"
        const val MODE_HISTORY = "history"
        const val MODE_FAVORITE = "favorite"

        fun newInstance(mode: String, genre: String = ""): HongguoFeedPage {
            return HongguoFeedPage().apply {
                arguments = Bundle().apply {
                    putString(ARG_MODE, mode)
                    putString(ARG_GENRE, genre)
                }
            }
        }
    }
}
