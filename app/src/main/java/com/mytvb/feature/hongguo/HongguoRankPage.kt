package com.mytvb.feature.hongguo

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.tabs.TabLayout
import com.mytvb.R
import com.mytvb.core.common.log.AppLog
import com.mytvb.core.ui.base.AppToast
import com.mytvb.core.ui.base.adaptiveSpanCount
import com.mytvb.core.ui.decoration.GridSpacingItemDecoration
import com.mytvb.core.ui.focus.tv.GridTvFocusStrategy
import com.mytvb.core.ui.focus.tv.TvListFocusController
import com.mytvb.core.ui.layout.WrapContentGridLayoutManager
import com.mytvb.core.ui.refresh.SwipeRefreshHelper
import com.mytvb.core.ui.tab.enableDirectTabNavigation
import com.mytvb.core.ui.tab.focusSelectedTab
import com.mytvb.databinding.FragmentHongguoRankBinding
import com.mytvb.feature.hongguo.net.HongguoClient
import com.mytvb.ui.activity.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 红果四板官方榜单（bookmall cell/change）：热播榜/真人榜/漫剧榜/AI剧榜。
 * 子板切换重开会话；滚动/焦点触底加载。
 * 2026-11-10 卡片迁移：横排行卡 → 统一竖版卡 + 排名角标。
 * 2026-11-11 重设计：前三名金银铜徽章（colors_hongguo 独立语义色）；
 * 空态组件（图标+文案+重试）替代灰字。
 * 2026-11-12 二次重构：四板 RadioButton/分段控件 → App 一贯 TabLayout 文字+accent
 * 下划线（同分类子档 tab，enableDirectTabNavigation 接线），说明行删除。
 */
class HongguoRankPage : Fragment(), HongguoPageFocus {

    private var _binding: FragmentHongguoRankBinding? = null
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

    private val spanCount by lazy { resources.adaptiveSpanCount(base = 6, wide = 12) }

    /** 触底预拉阈值：末两行（原 4 个太小，append 一页 20 个后旧位置立刻脱区，续拉空窗）。 */
    private val LOAD_MORE_THRESHOLD get() = spanCount * 2

    private var board = 0
    private var offset = 0
    private var sessionId = ""
    private var hasMore = false
    private var loading = false
    private var loadJob: Job? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHongguoRankBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.recyclerRank.layoutManager = WrapContentGridLayoutManager(requireContext(), spanCount)
        binding.recyclerRank.adapter = adapter
        binding.recyclerRank.itemAnimator = null
        binding.recyclerRank.addItemDecoration(
            GridSpacingItemDecoration(
                spanCount,
                resources.getDimensionPixelSize(R.dimen.px16),
                false
            )
        )
        // 卡片第一行 UP 先回二级档位 tab，再 UP 才回一级 tab（无卡时可聚焦态直接走 focusTopTab）
        adapter.bindTopEdgeUp {
            binding.tabRankBoards.focusSelectedTab() || focusTopTab()
        }
        adapter.setOnItemFocusListeners(
            onFocused = { position ->
                if (position >= adapter.contentCount() - LOAD_MORE_THRESHOLD) load()
            },
            onFocusedWithView = { view, position ->
                tvFocusController?.onItemFocused(view, position)
            }
        )
        tvFocusController = TvListFocusController(
            recyclerView = binding.recyclerRank,
            adapter = adapter,
            strategy = GridTvFocusStrategy { spanCount },
            canLoadMore = { hasMore },
            loadMore = { load() },
            debugName = "hongguo_rank"
        )
        binding.recyclerRank.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy > 0) checkLoadMore()
            }
        })
        // 四板档位：App 一贯 TabLayout 文字+accent 下划线（同分类子档 tab），OK 点击切换
        listOf(
            getString(R.string.hongguo_rank_hot),
            getString(R.string.hongguo_rank_real),
            getString(R.string.hongguo_rank_comic),
            getString(R.string.hongguo_rank_ai)
        ).forEach { binding.tabRankBoards.addTab(binding.tabRankBoards.newTab().setText(it)) }
        binding.tabRankBoards.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                if (tab.position != board) {
                    board = tab.position
                    refresh()
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}

            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
        binding.tabRankBoards.getTabAt(0)?.select()
        binding.emptyRank.buttonEmptyRetry.setOnClickListener { refresh() }
        // 二级 tab 焦点导航：UP 回一级 tab、DOWN 落第一张卡、首端 LEFT 去侧栏、末端 RIGHT 吞键
        binding.tabRankBoards.enableDirectTabNavigation(
            onNavigateDown = { focusFirstCard() },
            onNavigateUp = ::focusTopTab,
            onEdgeLeft = { (activity as? MainActivity)?.focusSidebarCurrentTab() == true },
            onEdgeRight = { true }
        )
        // 触摸下拉刷新（转圈色与本页中央圈同款统一粉）
        swipeRefreshLayout = SwipeRefreshHelper.wrapRecyclerView(
            binding.recyclerRank,
            onRefresh = { refresh() }
        ) {
            setColorSchemeResources(R.color.tab_highlightClassic)
        }
        refresh()
    }

    /** 外部/卡片 UP 的二级 tab 落点：榜单四板档位 tab。 */
    override fun focusSecondaryTab(): Boolean {
        if (_binding == null) return false
        return binding.tabRankBoards.focusSelectedTab()
    }

    /** BACK 分层回退：卡片 → 档位 tab；已在档位 tab 上返回 false 交宿主回一级 tab。 */
    override fun backToParentLayer(): Boolean {
        if (_binding == null) return false
        if (binding.tabRankBoards.hasFocus()) return false
        return focusSecondaryTab()
    }

    private fun focusTopTab(): Boolean {
        val host = parentFragment as? HongguoFragment ?: return false
        return host.focusTopTab()
    }

    override fun focusFirstCard(): Boolean {
        if (!isAdded || view == null) return false
        return tvFocusController?.requestFocusPosition(0, allowOutsideFocus = true) == true
    }

    /** tab 首次选中回顶；之后切换保持位置。 */
    private var topRestoredOnce = false

    override fun scrollToListTop() {
        if (_binding == null || topRestoredOnce) return
        topRestoredOnce = true
        binding.recyclerRank.scrollToPosition(0)
    }

    private fun openDetail(item: DramaUi, position: Int) {
        HongguoDetailDialog.show(requireContext(), repository, item.seriesId, item.score, item)
    }

    /** 菜单键/重选一级 tab/触摸下拉/空态重试的统一刷新；clear 后列表自然回顶。 */
    override fun refresh() {
        offset = 0; sessionId = ""; hasMore = false
        // 取消在飞的翻页请求并复位标志：否则 loading 早退会把刷新的 load() 整个吞掉
        // （列表已清、无新请求、无空态），旧轮完成后还会把旧 sessionId/offset 写回污染新会话
        loadJob?.cancel()
        loading = false
        adapter.clear()
        adapter.notifyDataSetChanged()
        load()
    }

    /**
     * 触底加载统一判定：贴尾阈值 或 已滚到硬底即拉。
     * 硬底判定是触摸场景的关键：滑到列表最底后不再产生滚动事件（onScrolled 收不到），
     * 只能靠"到底即拉"+数据落地后续拉补查驱动（B 站列表"落地即续拉"同款修法）。
     */
    private fun checkLoadMore() {
        if (loading || !hasMore) return
        if (_binding == null) return
        val rv = binding.recyclerRank
        val lm = rv.layoutManager as? GridLayoutManager ?: return
        val nearTail = lm.findLastVisibleItemPosition() >= adapter.contentCount() - LOAD_MORE_THRESHOLD
        val atBottom = !rv.canScrollVertically(1)
        if (nearTail || atBottom) {
            AppLog.d("Hongguo", "rank load_more trigger offset=$offset items=${adapter.contentCount()} " +
                "nearTail=$nearTail atBottom=$atBottom")
            load()
        }
    }

    private fun load() {
        if (loading) return
        loading = true
        // 首屏中央圈，翻页静默追加（对齐历史/分类页与 B 站列表）
        if (adapter.contentCount() == 0) showLoading(true)
        loadJob?.cancel()
        // LAZY 启动（HongguoFeedPage 同款定案）：Main.immediate 下普通 launch 的协程体会在
        // launch 调用点同步执行到第一个挂起点；缓存命中时 repository.rank 全程无挂起点，
        // 协程体（含 finally 的代际校验）一口气跑完才轮到下面的 loadJob = job——gen 判
        // false 收尾全跳过，loading 永挂中央圈不复位（切主题 recreate 后榜单一直转圈的
        // 实锤根因，2026-11-28 探针 gen=false 日志）。LAZY 保证先登记 loadJob 再 start。
        val job = viewLifecycleOwner.lifecycleScope.launch(start = CoroutineStart.LAZY) {
            try {
                val page = repository.rank(board, offset, sessionId)
                sessionId = page.sessionId
                // 空页=数据枯竭兜底：服务端 has_more 偶发不归零，防落地续拉原地空转
                hasMore = page.hasMore && page.items.isNotEmpty()
                offset = page.nextOffset
                // 全局名次 = 已有条数 + 批次内偏移 + 1
                val baseRank = adapter.contentCount()
                adapter.addData(page.items.mapIndexed { index, drama ->
                    drama.toUi(baseRank + index + 1)
                })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppToast.show(requireContext(), e.message ?: getString(R.string.hongguo_rank_load_failed))
            } finally {
                // 代际校验：被 refresh 取消的旧轮 finally 仍会异步跑（loading 复位/收尾），
                // 只有仍是最新一轮时才收尾，避免污染新轮的 loading 标志
                if (loadJob === coroutineContext[Job]) {
                    loading = false
                    showLoading(false)
                    swipeRefreshLayout?.isRefreshing = false
                    // 刷新清列表会让卡片焦点悬空：落地后校一次（焦点有效时零开销直通）
                    tvFocusController?.ensureValidFocus("rankLoad")
                    syncEmptyState()
                    // 落地即续拉：post 一帧等 layout 完成，视口仍贴硬底/阈值区
                    // （首屏不满屏、滑到底停手）时立即预取下一页，消除"到底干等"空窗
                    binding.recyclerRank.post { if (_binding != null) checkLoadMore() }
                }
            }
        }
        loadJob = job
        job.start()
    }

    /** 中央加载圈显隐（load_data.gif 帧动画自驱动，不依赖系统动画设置）。 */
    private fun showLoading(show: Boolean) {
        binding.progressLoading.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun syncEmptyState() {
        val empty = !loading && adapter.contentCount() == 0
        binding.emptyRank.root.visibility = if (empty) View.VISIBLE else View.GONE
    }

    private fun HongguoClient.Drama.toUi(rank: Int): DramaUi = DramaUi(
        seriesId = seriesId,
        title = title,
        cover = cover,
        // 卡片下方类型行（8.6 · 家庭 · 治愈…，榜单来自 category_schema），评分前置对齐分类卡
        subtitle = (listOf(score.takeIf { it.isNotBlank() }) + tags)
            .filterNotNull().joinToString(" · "),
        rank = rank,
        statusTag = statusTag.ifEmpty { statusTags.firstOrNull().orEmpty() },
        heatText = heatText.ifEmpty { if (playCount > 0) getString(R.string.hongguo_heat_wan, playCount / 10000) else "" },
        episodeCount = episodeCount,
        seasonText = seasonText.ifEmpty { if (multiSeason && seasonIndex > 0) getString(R.string.hongguo_season_format, seasonIndex) else "" },
        score = score
    )

    override fun onDestroyView() {
        loadJob?.cancel()
        tvFocusController?.release()
        tvFocusController = null
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance(): HongguoRankPage = HongguoRankPage()
    }
}
