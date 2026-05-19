@file:Suppress("SpellCheckingInspection")

package com.tutu.myblbl.feature.me

import android.os.Bundle
import android.view.LayoutInflater
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.reflect.TypeToken
import com.tutu.myblbl.R
import com.tutu.myblbl.databinding.FragmentMeTabListBinding
import com.tutu.myblbl.event.AppEventHub
import com.tutu.myblbl.model.video.HistoryVideoModel
import com.tutu.myblbl.model.video.VideoModel
import com.tutu.myblbl.ui.adapter.HistoryVideoAdapter
import com.tutu.myblbl.ui.adapter.VideoAdapter
import com.tutu.myblbl.core.ui.base.BaseFragment
import com.tutu.myblbl.core.common.cache.FileCacheManager
import com.tutu.myblbl.core.ui.layout.WrapContentGridLayoutManager
import com.tutu.myblbl.core.common.content.ContentFilter
import com.tutu.myblbl.core.ui.focus.SpatialFocusNavigator
import com.tutu.myblbl.core.ui.focus.TabContentFocusHelper
import com.tutu.myblbl.core.ui.focus.tv.GridTvFocusStrategy
import com.tutu.myblbl.core.ui.focus.tv.TvDataChangeReason
import com.tutu.myblbl.core.ui.focus.tv.TvFocusableAdapter
import com.tutu.myblbl.core.ui.focus.tv.TvListFocusController
import com.tutu.myblbl.core.ui.refresh.SwipeRefreshHelper
import com.tutu.myblbl.core.navigation.VideoRouteNavigator
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class MeListFragment : BaseFragment<FragmentMeTabListBinding>(), MeTabPage, com.tutu.myblbl.ui.activity.MainActivity.OnVideoBlockedListener {
    companion object {
        const val TYPE_HISTORY = "history"
        const val TYPE_LATER = "later"
        private const val CACHE_TTL_MS = 10 * 60 * 1000L
        private const val HISTORY_CACHE_KEY = "historyCacheList"
        private const val LATER_CACHE_KEY = "watchLaterCacheList"

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
    private var lastKnownLoggedIn = false
    private var tvFocusController: TvListFocusController? = null

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
        binding.recyclerView.setHasFixedSize(true)
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
        restoreCachedContent()
        loadData()
    }

    override fun onResume() {
        super.onResume()
        if (pendingRestoreFocus) {
            restoreContentFocus()
        }
        if (type == TYPE_HISTORY || type == TYPE_LATER) {
            val isLoggedIn = viewModel.isLoggedIn()
            if (isLoggedIn && !lastKnownLoggedIn) {
                lastKnownLoggedIn = isLoggedIn
                refresh()
            } else {
                lastKnownLoggedIn = isLoggedIn
            }
        }
    }

    override fun initObserver() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collectLatest { state ->
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
        when (type) {
            TYPE_HISTORY -> viewModel.loadHistory(currentPage, pageSize)
            TYPE_LATER -> viewModel.loadLaterWatch()
        }
    }

    private fun onVideoClick(video: VideoModel) {
        pendingRestoreFocus = true
        tvFocusController?.captureCurrentAnchor()
        VideoRouteNavigator.openVideo(
            context = requireContext(),
            video = video,
            playQueue = com.tutu.myblbl.ui.activity.PlayerActivity.buildPlayQueue(
                videoAdapter?.getItemsSnapshot().orEmpty(),
                video
            )
        )
    }

    private fun onHistoryVideoClick(video: HistoryVideoModel) {
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
                context = requireContext(),
                historyVideo = video,
                playQueue = com.tutu.myblbl.ui.activity.PlayerActivity.buildPlayQueue(
                    historyAdapter?.getItemsSnapshot().orEmpty().map { it.toVideoModel() },
                    mapped
                )
            )
        }
    }

    private fun bindHistoryData(videos: List<HistoryVideoModel>) {
        swipeRefreshLayout?.isRefreshing = false
        val adapter = historyAdapter ?: return
        val filtered = videos.filter {
            !ContentFilter.isVideoBlocked(
                context = requireContext(),
                typeName = it.tagName,
                title = it.title,
                authorName = it.displayAuthorName,
                aid = it.history?.oid ?: 0L,
                bvid = it.bvid,
                coverUrl = it.cover
            )
        }
        val shouldRestoreFocus = pendingHistoryReturnRestore && filtered.isNotEmpty()
        val shouldScrollToTop = pendingHistoryScrollToTop && filtered.isNotEmpty()
        adapter.setData(filtered) {
            tvFocusController?.onDataChanged(TvDataChangeReason.REPLACE_PRESERVE_ANCHOR)
            when {
                shouldRestoreFocus -> {
                    restoreContentFocus()
                }
                shouldScrollToTop -> {
                    pendingHistoryScrollToTop = false
                    scrollHistoryListToTopAfterRefresh()
                }
            }
        }
        cacheHistoryVideos(videos)
        updateContentState(filtered.isEmpty())
    }

    private fun bindLaterData(videos: List<VideoModel>) {
        swipeRefreshLayout?.isRefreshing = false
        val adapter = videoAdapter ?: return
        val filtered = ContentFilter.filterVideos(requireContext(), videos)
        adapter.setData(filtered)
        tvFocusController?.onDataChanged(TvDataChangeReason.REPLACE_PRESERVE_ANCHOR)
        cacheLaterVideos(videos)
        updateContentState(filtered.isEmpty())
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
                    TYPE_HISTORY -> viewModel.hasMore.value
                    TYPE_LATER -> false
                    else -> false
                }
            },
            loadMore = {
                if (type == TYPE_HISTORY && !viewModel.loading.value && viewModel.hasMore.value) {
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
        currentPage = 1
        pendingRestoreFocus = false
        pendingHistoryReturnRestore = false
        pendingHistoryScrollToTop = type == TYPE_HISTORY
        tvFocusController?.clearAnchorForUserRefresh()
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
        if (!isAdded || view == null || viewModel.loading.value) {
            return
        }
        when (type) {
            TYPE_HISTORY, TYPE_LATER -> {
                if (!hasContentItems()) {
                    currentPage = 1
                    loadData()
                }
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
        if (!isAdded || view == null || viewModel.loading.value) {
            return
        }
        refresh()
    }

    override fun onHostEvent(event: MeTabPage.HostEvent): Boolean {
        when (event) {
            MeTabPage.HostEvent.SELECT_TAB4 -> onTabSelected()
            MeTabPage.HostEvent.CLICK_TAB4 -> refresh()
            MeTabPage.HostEvent.BACK_PRESSED -> scrollToTop()
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
            return
        }
        pendingRestoreFocus = false
        if (type == TYPE_HISTORY) {
            pendingHistoryReturnRestore = false
            pendingHistoryScrollToTop = false
        }
        binding.recyclerView.post {
            if (!isAdded || binding.recyclerView.visibility != View.VISIBLE) {
                return@post
            }
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
                if (tvFocusController?.focusPrimary() != true) {
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
        binding.recyclerView.clearFocus()
        historyAdapter?.clearFocusMemory()
        scrollListToTop(immediate = true)
        binding.recyclerView.post {
            scrollListToTop(immediate = true)
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

    private fun restoreCachedContent() {
        if (!viewModel.isLoggedIn()) return
        when (type) {
            TYPE_HISTORY -> {
                val cachedVideos = runCatching {
                    val cacheType = object : TypeToken<List<HistoryVideoModel>>() {}.type
                    FileCacheManager.get<List<HistoryVideoModel>>(HISTORY_CACHE_KEY, cacheType).orEmpty()
                }.getOrElse { emptyList() }
                if (cachedVideos.isNotEmpty()) {
                    historyAdapter?.setData(
                        cachedVideos.filter {
                            !ContentFilter.isVideoBlocked(
                                context = requireContext(),
                                typeName = it.tagName,
                                title = it.title,
                                authorName = it.displayAuthorName,
                                aid = it.history?.oid ?: 0L,
                                bvid = it.bvid,
                                coverUrl = it.cover
                            )
                        }
                    )
                    showContent()
                }
            }

            TYPE_LATER -> {
                val cachedVideos = runCatching {
                    val cacheType = object : TypeToken<List<VideoModel>>() {}.type
                    FileCacheManager.get<List<VideoModel>>(LATER_CACHE_KEY, cacheType).orEmpty()
                }.getOrElse { emptyList() }
                if (cachedVideos.isNotEmpty()) {
                    videoAdapter?.setData(ContentFilter.filterVideos(requireContext(), cachedVideos))
                    showContent()
                }
            }
        }
    }

    private fun cacheHistoryVideos(videos: List<HistoryVideoModel>) {
        runCatching {
            FileCacheManager.put(HISTORY_CACHE_KEY, videos)
        }
    }

    private fun cacheLaterVideos(videos: List<VideoModel>) {
        if (videos.isEmpty()) {
            return
        }
        runCatching {
            FileCacheManager.put(LATER_CACHE_KEY, videos)
        }
    }

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
