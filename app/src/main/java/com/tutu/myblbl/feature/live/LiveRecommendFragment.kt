package com.tutu.myblbl.feature.live

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tutu.myblbl.R
import com.tutu.myblbl.databinding.FragmentLiveBaseListBinding
import com.tutu.myblbl.model.live.LiveListWrapper
import com.tutu.myblbl.model.live.LiveRecommendSection
import com.tutu.myblbl.ui.activity.LivePlayerActivity
import com.tutu.myblbl.core.ui.base.BaseFragment
import com.tutu.myblbl.ui.fragment.main.MainNavigationViewModel
import com.tutu.myblbl.core.common.content.ContentFilter
import com.tutu.myblbl.core.ui.focus.SpatialFocusNavigator
import com.tutu.myblbl.core.ui.focus.TabContentFocusHelper
import com.tutu.myblbl.core.ui.refresh.SwipeRefreshHelper
import com.tutu.myblbl.core.common.ext.toast
import com.tutu.myblbl.core.common.log.AppLog
import com.tutu.myblbl.core.common.log.PagePerfLogger
import com.tutu.myblbl.core.ui.render.FirstScreenRenderer
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel

class LiveRecommendFragment : BaseFragment<FragmentLiveBaseListBinding>(), LiveTabPage {
    companion object {
        private const val CACHE_TTL_MS = 10 * 60 * 1000L
        private const val INITIAL_SECTION_BATCH_SIZE = 5

        fun newInstance(): LiveRecommendFragment = LiveRecommendFragment()
    }

    private val viewModel: LiveRecommendViewModel by viewModel()
    private val mainNavigationViewModel: MainNavigationViewModel by activityViewModels()
    private lateinit var adapter: LiveRecommendAdapter
    private var swipeRefreshLayout: androidx.swiperefreshlayout.widget.SwipeRefreshLayout? = null
    private var capturedRoomId: Long? = null
    private var hasLoadedData = false
    private var currentOpenStartMs = 0L

    override fun getViewBinding(
        inflater: LayoutInflater,
        container: ViewGroup?
    ): FragmentLiveBaseListBinding {
        return FragmentLiveBaseListBinding.inflate(inflater, container ?: android.widget.FrameLayout(inflater.context))
    }

    override fun initView() {
        adapter = LiveRecommendAdapter(
            onRoomClick = ::onRoomClick,
            onTopEdgeUp = ::focusTopTab,
            onLeftEdge = ::focusLeftNav
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
        binding.recyclerView.itemAnimator = null
        swipeRefreshLayout = SwipeRefreshHelper.wrapRecyclerView(binding.recyclerView) {
            onExplicitRefresh()
        }
        binding.recyclerView.setHasFixedSize(true)
    }

    override fun initData() {
        currentOpenStartMs = PagePerfLogger.now()
        PagePerfLogger.markNow("LiveRecommend", "initData_start")
        AppLog.d("LivePerf", "LiveRecommendFragment.initData: 触发加载最新推荐")
        viewModel.loadData(forceRefresh = true)
    }

    override fun initObserver() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.recommendData.collect { data ->
                    swipeRefreshLayout?.isRefreshing = false
                    if (data == null) {
                        PagePerfLogger.markNow(
                            "LiveRecommend",
                            "skip_empty_initial",
                            "hasContent=${adapter.itemCount > 0}"
                        )
                        return@collect
                    }
                    val t0 = System.currentTimeMillis()
                    val collectStartMs = PagePerfLogger.now()
                    PagePerfLogger.mark(
                        "LiveRecommend",
                        "data_collected",
                        currentOpenStartMs,
                        "data=true"
                    )
                    AppLog.d("LivePerf", "LiveRecommendFragment: 推荐数据到达UI, data=true")
                    val sections = buildSections(data)
                    if (sections.isEmpty()) return@collect
                    hasLoadedData = true
                    PagePerfLogger.mark(
                        "LiveRecommend",
                        "build_sections_end",
                        collectStartMs,
                        "sections=${sections.size}"
                    )
                    AppLog.d("LivePerf", "LiveRecommendFragment: buildSections完成, section数=${sections.size}, 耗时=${System.currentTimeMillis() - t0}ms")

                    applySections(sections)
                    AppLog.d("LivePerf", "LiveRecommendFragment: setData完成, 耗时=${System.currentTimeMillis() - t0}ms")
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.error.collectLatest { error ->
                    if (!error.isNullOrBlank()) {
                        requireContext().toast(error)
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                mainNavigationViewModel.events.collectLatest { event ->
                    if (isHidden || !isVisible) {
                        return@collectLatest
                    }
                    if (event is MainNavigationViewModel.Event.SecondaryTabReselected &&
                        event.host == MainNavigationViewModel.SecondaryTabHost.LIVE &&
                        event.position == 0 &&
                        !viewModel.loading.value
                    ) {
                        onExplicitRefresh()
                    }
                }
            }
        }
    }

    override fun scrollToTop() {
        binding.recyclerView.smoothScrollToPosition(0)
    }

    override fun focusPrimaryContent(): Boolean {
        if (!isAdded || view == null || adapter.itemCount == 0) {
            return false
        }
        val result = TabContentFocusHelper.requestRecyclerPrimaryFocus(
            recyclerView = binding.recyclerView,
            itemCount = adapter.itemCount,
            focusRequester = { holder ->
                (holder as? LiveRecommendAdapter.ViewHolder)?.requestPrimaryFocus() == true
            }
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

    override fun onReselected() {
        scrollToTop()
    }

    override fun onExplicitRefresh() {
        currentOpenStartMs = PagePerfLogger.now()
        PagePerfLogger.markNow("LiveRecommend", "refresh_start", "hasContent=${adapter.itemCount > 0}")
        capturedRoomId = null
        swipeRefreshLayout?.isRefreshing = true
        viewModel.loadData(forceRefresh = true)
    }

    override fun onTabSelected() {
        if (!isAdded || view == null || viewModel.loading.value) {
            return
        }
        if (adapter.itemCount == 0 || viewModel.shouldRefresh(CACHE_TTL_MS)) {
            currentOpenStartMs = PagePerfLogger.now()
            PagePerfLogger.markNow("LiveRecommend", "tab_selected_refresh", "hasContent=${adapter.itemCount > 0}")
            viewModel.loadData()
        } else {
            currentOpenStartMs = PagePerfLogger.now()
            PagePerfLogger.markNow("LiveRecommend", "tab_selected_cached", "sections=${adapter.itemCount}")
            logLiveRecommendFirstDraw(adapter.itemCount)
        }
    }

    private fun logLiveRecommendFirstDraw(itemCount: Int) {
        val startMs = currentOpenStartMs
        if (startMs <= 0L || itemCount <= 0) return
        FirstScreenRenderer.logFirstFrame(
            recyclerView = binding.recyclerView,
            page = "LiveRecommend",
            event = "first_sections_draw",
            startMs = startMs,
            itemCount = itemCount,
            source = "cache"
        )
        currentOpenStartMs = 0L
    }

    private fun applySections(sections: List<LiveRecommendSection>) {
        val applyStartMs = PagePerfLogger.now()
        val laneHeight = FirstScreenRenderer.estimateVideoCardHeight(binding.recyclerView, spanCount = 4) +
            resources.getDimensionPixelSize(R.dimen.px70)
        FirstScreenRenderer.render(
            recyclerView = binding.recyclerView,
            page = "LiveRecommend",
            items = sections,
            startMs = currentOpenStartMs,
            source = "first_screen",
            event = "first_sections_draw",
            spanCount = 1,
            itemHeightPx = laneHeight,
            minRows = 2,
            extraBufferRows = 1,
            maxRows = INITIAL_SECTION_BATCH_SIZE,
            setItems = { firstBatch, onCommitted ->
                adapter.setData(firstBatch, onCommitted)
            },
            appendItems = { remaining ->
                adapter.addData(remaining)
            },
            onFirstBatchCommitted = {
                PagePerfLogger.mark(
                    "LiveRecommend",
                    "adapter_apply",
                    applyStartMs,
                    "sections=${adapter.itemCount}"
                )
                currentOpenStartMs = 0L
            }
        )
    }

    private fun onRoomClick(room: com.tutu.myblbl.model.live.LiveRoomItem) {
        LivePlayerActivity.start(requireContext(), room.roomId)
    }

    private fun focusTopTab(): Boolean {
        return (parentFragment as? LiveFragment)?.focusCurrentTab() == true
    }

    private fun focusLeftNav(): Boolean {
        return (activity as? com.tutu.myblbl.ui.activity.MainActivity)?.focusLeftFunctionArea() == true
    }

    override fun onPause() {
        captureFocus()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        restoreFocus()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            restoreFocus()
        }
    }

    override fun onDestroyView() {
        capturedRoomId = null
        super.onDestroyView()
    }

    private fun captureFocus() {
        val focused = activity?.currentFocus ?: return
        capturedRoomId = findFocusedRoomId(focused)
    }

    private fun findFocusedRoomId(focused: View): Long? {
        var view: View? = focused
        while (view != null) {
            val parent = view.parent
            if (parent is RecyclerView) {
                val holder = parent.findContainingViewHolder(view)
                if (holder != null && parent.adapter is LiveRoomAdapter) {
                    val position = holder.bindingAdapterPosition
                    if (position != RecyclerView.NO_POSITION) {
                        return (parent.adapter as LiveRoomAdapter)
                            .currentList.getOrNull(position)?.roomId
                    }
                }
            }
            view = parent as? View
        }
        return null
    }

    private fun restoreFocus() {
        val roomId = capturedRoomId ?: return
        capturedRoomId = null
        if (!isAdded || view == null) return

        val sections = adapter.currentList
        for ((sectionIndex, section) in sections.withIndex()) {
            val roomIndex = section.rooms.indexOfFirst { it.roomId == roomId }
            if (roomIndex >= 0) {
                binding.recyclerView.post {
                    scrollToSectionAndFocusRoom(sectionIndex, roomIndex)
                }
                return
            }
        }
    }

    private fun scrollToSectionAndFocusRoom(sectionIndex: Int, roomIndex: Int) {
        if (!isAdded || view == null) return
        val sectionHolder = binding.recyclerView.findViewHolderForAdapterPosition(sectionIndex)
        if (sectionHolder is LiveRecommendAdapter.ViewHolder) {
            sectionHolder.focusRoomAt(roomIndex)
            return
        }
        binding.recyclerView.scrollToPosition(sectionIndex)
        binding.recyclerView.post {
            if (!isAdded || view == null) return@post
            val holder = binding.recyclerView.findViewHolderForAdapterPosition(sectionIndex)
            if (holder is LiveRecommendAdapter.ViewHolder) {
                holder.focusRoomAt(roomIndex)
            }
        }
    }

    private fun buildSections(data: LiveListWrapper?): List<LiveRecommendSection> {
        if (data == null) {
            return emptyList()
        }

        val sections = mutableListOf<LiveRecommendSection>()
        val hotRooms = ContentFilter.filterLiveRooms(requireContext(), data.recommendRoomList.orEmpty())
        if (hotRooms.isNotEmpty()) {
            sections += LiveRecommendSection(
                title = getString(R.string.hot_live),
                rooms = hotRooms
            )
        }
        sections += data.roomList.orEmpty()
            .mapNotNull { wrapper ->
                val rooms = ContentFilter.filterLiveRooms(requireContext(), wrapper.list.orEmpty())
                if (rooms.isEmpty()) {
                    null
                } else {
                    LiveRecommendSection(
                        title = wrapper.moduleInfo?.title.orEmpty(),
                        rooms = rooms
                    )
                }
            }
        return sections
    }

}
