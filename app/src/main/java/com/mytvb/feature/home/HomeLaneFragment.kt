package com.mytvb.feature.home

import android.view.View
import android.view.ViewTreeObserver
import androidx.core.os.bundleOf
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mytvb.R
import com.mytvb.core.common.log.AppLog
import com.mytvb.core.common.log.PagePerfLogger
import com.mytvb.core.ui.base.BaseAdapter
import com.mytvb.core.ui.base.BaseListFragment
import com.mytvb.event.AppEventHub
import com.mytvb.core.ui.focus.SpatialFocusNavigator
import com.mytvb.core.ui.focus.TabContentFocusHelper
import com.mytvb.core.ui.render.FirstScreenRenderer
import com.mytvb.feature.series.AllSeriesFragment
import com.mytvb.feature.series.SeriesDetailFragment
import com.mytvb.model.lane.HomeLaneSection
import com.mytvb.repository.HomeLaneRepository
import com.mytvb.ui.adapter.HomeLaneAdapter
import com.mytvb.ui.dialog.MyFollowingDialog
import com.mytvb.ui.fragment.main.MainNavigationViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.koin.core.parameter.parametersOf

class HomeLaneFragment : BaseListFragment<HomeLaneSection>(), HomeTabPage {

    companion object {
        private const val TAG = "HomeLaneFragment"
        private const val ARG_TYPE = "type"

        const val TYPE_ANIMATION = HomeLaneRepository.TYPE_ANIMATION
        const val TYPE_CINEMA = HomeLaneRepository.TYPE_CINEMA

        fun newInstance(type: Int): HomeLaneFragment {
            return HomeLaneFragment().apply {
                arguments = bundleOf(ARG_TYPE to type)
            }
        }
    }

    private val mainNavigationViewModel: MainNavigationViewModel by activityViewModels()
    private val appEventHub: AppEventHub by inject()
    private val viewModel: HomeLaneViewModel by viewModel { parametersOf(type) }

    private var type: Int = TYPE_ANIMATION
    private var pendingScrollToTopAfterRefresh = false
    private var latestOpenStartMs = 0L
    private var initialLoadStarted = false

    private val laneAdapter: HomeLaneAdapter?
        get() = adapter as? HomeLaneAdapter

    override val autoLoad: Boolean = false
    override val deferSwipeRefreshUntilFirstDraw: Boolean = true

    override fun initArguments() {
        type = arguments?.getInt(ARG_TYPE, TYPE_ANIMATION) ?: TYPE_ANIMATION
    }

    override fun createAdapter(): BaseAdapter<HomeLaneSection, *> {
        return HomeLaneAdapter(
            onSeriesClick = { series ->
                if (series.seasonId > 0) {
                    openInHostContainer(
                        SeriesDetailFragment.newInstance(
                            seasonId = series.seasonId
                        )
                    )
                }
            },
            onMoreClick = { seasonType, moreUrl, entryTitle ->
                openInHostContainer(AllSeriesFragment.newInstance(seasonType, moreUrl, entryTitle))
            },
            onTimelineClick = { item ->
                if (item.seasonId > 0 || item.episodeId > 0) {
                    openInHostContainer(
                        SeriesDetailFragment.newInstance(
                            seasonId = item.seasonId,
                            epId = item.episodeId
                        )
                    )
                }
            },
            onTopEdgeUp = ::focusTopTab,
            defaultMoreSeasonType = type,
            onFollowSectionClick = { followType ->
                showMyFollowingDialog(followType)
            }
        )
    }

    override fun createLayoutManager(): LinearLayoutManager {
        return LinearLayoutManager(requireContext())
    }

    override fun initView() {
        super.initView()
        recyclerView?.setHasFixedSize(true)
        adapter?.setShowLoadMore(false)
        installFocusDebugListeners()
    }

    private var globalFocusListener: ViewTreeObserver.OnGlobalFocusChangeListener? = null

    private fun installFocusDebugListeners() {
        val rootView = view ?: return
        globalFocusListener = ViewTreeObserver.OnGlobalFocusChangeListener { oldFocus, newFocus ->
            val rv = recyclerView ?: return@OnGlobalFocusChangeListener
            val oldPos = oldFocus?.let { findCardPositionInLane(it, rv) }
            val newPos = newFocus?.let { findCardPositionInLane(it, rv) }
            val oldSection = oldFocus?.let { findSectionTitle(it, rv) }
            val newSection = newFocus?.let { findSectionTitle(it, rv) }
            if (newPos != null || newSection != null) {
                val oldDesc = oldFocus?.let { viewId(it) } ?: "null"
                val newDesc = newFocus?.let { viewId(it) } ?: "null"
                AppLog.d(TAG, "focusChange: $oldDesc(section=$oldSection card=$oldPos) → $newDesc(section=$newSection card=$newPos)")
            }
        }
        rootView.viewTreeObserver.addOnGlobalFocusChangeListener(globalFocusListener)
    }

    private fun findCardPositionInLane(view: View, outerRV: RecyclerView): Int? {
        val innerRV = findParentRecyclerView(view) ?: return null
        if (innerRV === outerRV) return null
        val pos = innerRV.getChildAdapterPosition(view.parent as? View ?: view)
        return pos.takeIf { it != RecyclerView.NO_POSITION }
    }

    private fun findSectionTitle(view: View, outerRV: RecyclerView): String? {
        val innerRV = findParentRecyclerView(view) ?: return null
        if (innerRV === outerRV) return null
        val sectionView = innerRV.parent as? View ?: return null
        val outerPos = outerRV.getChildAdapterPosition(sectionView)
        if (outerPos == RecyclerView.NO_POSITION) return null
        val section = (adapter as? HomeLaneAdapter)?.items?.getOrNull(outerPos) ?: return null
        return section.title.take(10)
    }

    private fun findParentRecyclerView(view: View): RecyclerView? {
        var current = view.parent
        while (current != null) {
            if (current is RecyclerView) return current
            current = current.parent
        }
        return null
    }

    private fun viewId(view: View): String {
        val idName = try { view.context.resources.getResourceEntryName(view.id) } catch (_: Exception) { "${view.id}" }
        return "${view.javaClass.simpleName}($idName)"
    }

    override fun initData() {
        if (!isCurrentHomePage()) {
            showContent()
            showLoading(false)
            AppLog.i(TAG, "${pageTag()} initialLoad deferred reason=not_current")
            return
        }
        startInitialLoad("init")
    }

    private fun startInitialLoad(reason: String) {
        if (initialLoadStarted || isLoading) {
            return
        }
        initialLoadStarted = true
        isLoading = true
        latestOpenStartMs = PagePerfLogger.now()
        PagePerfLogger.markNow(pageTag(), "request_start", "page=1 source=$reason")
        showLoading(true)
        viewModel.loadInitial()
    }

    override fun initObserver() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collectLatest { state ->
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
                            val shouldRefresh = event.host == MainNavigationViewModel.SecondaryTabHost.HOME &&
                                (
                                    (event.position == 2 && type == TYPE_ANIMATION) ||
                                        (event.position == 3 && type == TYPE_CINEMA)
                                    )
                            if (shouldRefresh && !isLoading) {
                                refresh()
                            }
                        }

                        MainNavigationViewModel.Event.MenuPressed -> {
                            if (!isLoading) {
                                refresh()
                            }
                        }

                        MainNavigationViewModel.Event.BackPressed -> Unit
                        else -> Unit
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                appEventHub.events.collectLatest { event ->
                    if (event == AppEventHub.Event.UserSessionChanged && isResumed && !isLoading) {
                        refresh()
                    }
                }
            }
        }
    }

    private fun renderState(state: FeedUiState<HomeLaneSection>) {
        isLoading = state.loadingInitial || state.refreshing || state.appending
        hasMore = state.hasMore
        setRefreshing(state.refreshing)
        laneAdapter?.setShowLoadMore(state.hasMore)

        if (state.loadingInitial && state.items.isEmpty()) {
            showLoading(true)
            return
        }

        state.errorMessage?.let { message ->
            isLoading = false
            setRefreshing(false)
            showLoading(false)
            AppLog.e(TAG, "renderState error: type=$type, message=$message")
            if ((adapter?.contentCount() ?: 0) == 0) {
                laneAdapter?.setShowLoadMore(false)
                showError(message.ifBlank { getString(R.string.net_error) })
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
            FeedListChange.REPLACE -> applyReplacedSections(state.items)
            FeedListChange.APPEND -> applyAppendedSections(state.items)
        }
    }

    private fun applyReplacedSections(sections: List<HomeLaneSection>) {
        isLoading = false
        setRefreshing(false)
        showLoading(false)
        laneAdapter?.setShowLoadMore(hasMore)
        val rv = recyclerView
        val adp = adapter
        if (rv != null && adp != null && (latestOpenStartMs > 0L || adp.contentCount() == 0)) {
            val laneHeight = FirstScreenRenderer.estimateVideoCardHeight(rv, spanCount = 4) +
                resources.getDimensionPixelSize(R.dimen.px70)
            FirstScreenRenderer.render(
                recyclerView = rv,
                page = pageTag(),
                items = sections,
                startMs = latestOpenStartMs,
                source = "replace",
                event = "first_sections_draw",
                spanCount = 1,
                itemHeightPx = laneHeight,
                minRows = 2,
                extraBufferRows = 1,
                maxRows = 4,
                setItems = { firstBatch, onCommitted ->
                    adp.setData(firstBatch, onCommitted)
                },
                appendItems = { remaining ->
                    adp.addAll(remaining)
                },
                onFirstBatchCommitted = {
                    latestOpenStartMs = 0L
                }
            )
        } else {
            adapter?.setData(sections)
        }
        if (sections.isNotEmpty()) {
            showContent()
            if (pendingScrollToTopAfterRefresh) {
                recyclerView?.post {
                    scrollToTop()
                    val rv = recyclerView ?: return@post
                    val adp = laneAdapter ?: return@post
                    val focused = activity?.currentFocus
                    if (focused != null && rv.findContainingItemView(focused) != null) {
                        adp.requestFirstCardFocus(rv)
                    }
                }
            }
        } else {
            laneAdapter?.setShowLoadMore(false)
            showEmpty()
        }
        pendingScrollToTopAfterRefresh = false
        viewModel.consumeListChange()
    }

    private fun applyAppendedSections(sections: List<HomeLaneSection>) {
        isLoading = false
        setRefreshing(false)
        showLoading(false)
        viewModel.consumeListChange()
        if (sections.isEmpty()) {
            hasMore = false
            laneAdapter?.setShowLoadMore(false)
            return
        }
        showContent()
        adapter?.setData(sections)
    }

    override fun onRetryClick() {
        refresh()
    }

    override fun loadData(page: Int) {
        if (isLoading || !isAdded || view == null) {
            return
        }
        isLoading = true
        if (page == 1 && adapter?.contentCount() == 0) {
            showLoading(true)
        }
        latestOpenStartMs = PagePerfLogger.now()
        PagePerfLogger.markNow(pageTag(), "request_start", "page=$page source=loadData hasContent=${adapter?.contentCount() ?: 0}")
        if (page == 1) {
            viewModel.refresh()
        } else {
            viewModel.loadMore()
        }
    }

    override fun focusNearestVisibleContent(): Boolean {
        return focusNearestVisibleListItem()
    }

    override fun scrollToTopAndFocus(): Boolean {
        scrollToTop()
        recyclerView?.post {
            if (isAdded && view != null) {
                focusPrimaryContent()
            }
        }
        return true
    }

    override fun focusPrimaryContent(): Boolean {
        if (!isAdded || view == null) {
            return false
        }
        if (TabContentFocusHelper.requestVisibleFocus(buttonRetry, viewError)) {
            return true
        }
        val recycler = recyclerView ?: return false
        if ((adapter?.contentCount() ?: 0) == 0) {
            return false
        }
        val result = TabContentFocusHelper.requestRecyclerPrimaryFocus(
            recyclerView = recycler,
            itemCount = adapter?.contentCount() ?: 0,
            focusRequester = { holder ->
                when (holder) {
                    is HomeLaneAdapter.ScrollableViewHolder -> holder.requestPrimaryFocus()
                    is HomeLaneAdapter.TimelineViewHolder -> holder.requestPrimaryFocus()
                    else -> false
                }
            }
        )
        return result.resolved
    }

    override fun focusPrimaryContent(anchorView: View?, preferSpatialEntry: Boolean): Boolean {
        if (preferSpatialEntry) {
            val recycler = recyclerView ?: return false
            val handled = SpatialFocusNavigator.requestBestDescendant(
                anchorView = anchorView,
                root = recycler,
                direction = View.FOCUS_RIGHT,
                fallback = null
            )
            if (handled) {
                return true
            }
        }
        return focusPrimaryContent()
    }

    override fun refresh() {
        currentPage = 1
        hasMore = true
        pendingScrollToTopAfterRefresh = true
        isLoading = true
        latestOpenStartMs = PagePerfLogger.now()
        PagePerfLogger.markNow(pageTag(), "request_start", "page=1 source=refresh hasContent=${adapter?.contentCount() ?: 0}")
        viewModel.refresh()
    }

    override fun onTabSelected() {
        if (!isAdded || view == null || isLoading) {
            return
        }
        if ((adapter?.contentCount() ?: 0) == 0) {
            startInitialLoad("tabSelected")
        }
    }

    override fun checkLoadMore() {
        if (isLoading || !hasMore) return
        val lm = layoutManager ?: return
        val totalItemCount = lm.itemCount
        val lastVisiblePosition = lm.findLastVisibleItemPosition()
        if (lastVisiblePosition >= totalItemCount - 2) {
            currentPage++
            loadData(currentPage)
        }
    }

    private fun focusTopTab(): Boolean {
        // UP 顶行落"当前选中的二级 tab"（不切页），与 VideoFeedFragment 一致
        return (parentFragment as? HomeFragment)?.focusSelectedTabFromContent() == true
    }

    private fun pageTag(): String = "HomeLane/$type"

    override val shouldPrewarmInitialViewHolders: Boolean
        get() = isCurrentHomePage()

    private fun isCurrentHomePage(): Boolean {
        return (parentFragment as? HomeFragment)?.isCurrentPage(homePageIndex()) != false
    }

    private fun homePageIndex(): Int {
        return if (type == TYPE_ANIMATION) 2 else 3
    }


    private fun showMyFollowingDialog(followType: Int) {
        if (!isAdded || view == null) return
        val ctx = context ?: return
        val dialog = MyFollowingDialog(
            context = ctx,
            type = followType,
            onSeriesClick = { series ->
                if (series.seasonId > 0) {
                    openInHostContainer(
                        SeriesDetailFragment.newInstance(
                            seasonId = series.seasonId
                        )
                    )
                }
            }
        )
        dialog.show()
    }

    override fun onDestroyView() {
        globalFocusListener?.let {
            view?.viewTreeObserver?.removeOnGlobalFocusChangeListener(it)
        }
        globalFocusListener = null
        super.onDestroyView()
    }
}
