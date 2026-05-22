package com.tutu.myblbl.feature.home

import android.view.View
import android.view.ViewTreeObserver
import androidx.core.os.bundleOf
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tutu.myblbl.R
import com.tutu.myblbl.core.common.log.AppLog
import com.tutu.myblbl.core.ui.base.BaseAdapter
import com.tutu.myblbl.core.ui.base.BaseListFragment
import com.tutu.myblbl.event.AppEventHub
import com.tutu.myblbl.core.ui.focus.SpatialFocusNavigator
import com.tutu.myblbl.core.ui.focus.TabContentFocusHelper
import com.tutu.myblbl.feature.series.AllSeriesFragment
import com.tutu.myblbl.feature.series.SeriesDetailFragment
import com.tutu.myblbl.model.lane.HomeLaneSection
import com.tutu.myblbl.repository.HomeLaneRepository
import com.tutu.myblbl.ui.adapter.HomeLaneAdapter
import com.tutu.myblbl.ui.dialog.MyFollowingDialog
import com.tutu.myblbl.ui.fragment.main.MainNavigationViewModel
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

    private val laneAdapter: HomeLaneAdapter?
        get() = adapter as? HomeLaneAdapter

    override val autoLoad: Boolean = false
    override val enableSwipeRefresh: Boolean = false

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

                        MainNavigationViewModel.Event.BackPressed -> scrollToTop()
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
        adapter?.setData(sections)
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
        if (page == 1) {
            viewModel.refresh()
        } else {
            viewModel.loadMore()
        }
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
        viewModel.refresh()
    }

    override fun onTabSelected() {
        if (!isAdded || view == null || isLoading) {
            return
        }
        if ((adapter?.contentCount() ?: 0) == 0) {
            viewModel.loadInitial()
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
        return (parentFragment as? HomeFragment)?.focusCurrentTab() == true
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
