package com.tutu.myblbl.feature.dynamic

import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tutu.myblbl.R
import com.tutu.myblbl.databinding.FragmentDynamicBinding
import com.tutu.myblbl.event.AppEventHub
import com.tutu.myblbl.model.user.FollowingModel
import com.tutu.myblbl.model.video.VideoModel
import com.tutu.myblbl.network.session.NetworkSessionGateway
import com.tutu.myblbl.core.ui.base.BaseFragment
import com.tutu.myblbl.core.ui.base.RecyclerViewPoolPrewarmer
import com.tutu.myblbl.core.ui.base.VideoRecyclerViewTuning
import android.os.SystemClock
import com.tutu.myblbl.core.common.log.AppLog
import com.tutu.myblbl.core.common.log.PagePerfLogger
import com.tutu.myblbl.ui.fragment.main.MainTabFocusTarget
import com.tutu.myblbl.feature.settings.SignInFragment
import com.tutu.myblbl.ui.activity.MainActivity
import com.tutu.myblbl.core.ui.layout.WrapContentGridLayoutManager
import com.tutu.myblbl.core.ui.base.RecyclerViewFocusRestoreHelper
import com.tutu.myblbl.ui.fragment.main.MainNavigationViewModel
import com.tutu.myblbl.core.common.content.ContentFilter
import com.tutu.myblbl.core.ui.focus.SpatialFocusNavigator
import com.tutu.myblbl.core.ui.focus.TabContentFocusHelper
import com.tutu.myblbl.core.ui.focus.tv.GridTvFocusStrategy
import com.tutu.myblbl.core.ui.focus.tv.TvDataChangeReason
import com.tutu.myblbl.core.ui.focus.tv.TvListFocusController
import com.tutu.myblbl.core.ui.refresh.SwipeRefreshHelper
import com.tutu.myblbl.core.navigation.VideoRouteNavigator
import com.tutu.myblbl.core.ui.render.FirstScreenRenderer
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class DynamicFragment : BaseFragment<FragmentDynamicBinding>(), MainTabFocusTarget, com.tutu.myblbl.ui.activity.MainActivity.OnVideoBlockedListener {
    private enum class ContentFocusTarget {
        LEFT_UP_LIST,
        RIGHT_VIDEO_LIST
    }

    companion object {
        private const val TAG = "DynamicFrag"
        private const val CACHE_TTL_MS = 5 * 60 * 1000L

        fun newInstance(): DynamicFragment = DynamicFragment()
    }

    private val appEventHub: AppEventHub by inject()
    private val sessionGateway: NetworkSessionGateway by inject()
    private val viewModel: DynamicViewModel by viewModel()
    private val mainNavigationViewModel: MainNavigationViewModel by activityViewModels()
    private lateinit var upAdapter: DynamicUpAdapter
    private lateinit var videoAdapter: DynamicVideoAdapter
    private var swipeRefreshLayout: androidx.swiperefreshlayout.widget.SwipeRefreshLayout? = null
    private var currentUpId: Long = 0L
    private val pageSize = 20
    private val loadMoreThreshold = 12
    private var latestStatus: DynamicViewModel.DynamicStatus = DynamicViewModel.DynamicStatus.Idle
    private var latestScreenState: DynamicViewModel.ScreenState = DynamicViewModel.ScreenState.Content
    private var latestLoading = false
    private var lastToastMessage: String? = null
    private var lastFocusedVideoPosition = 0
    private var pendingScrollToTop = false
    private var pendingVideoFocusRestoreOnResume = false
    private var preferredContentFocusTarget = ContentFocusTarget.LEFT_UP_LIST
    private var videoFocusController: TvListFocusController? = null
    private var currentOpenStartMs = 0L
    private var latestVideoRequestStartMs = 0L
    private var latestInitialRequestStartMs = 0L
    private var lastRenderedVideoSignature: String = ""
    private var pendingInitialVideoFocusAfterFirstDraw = false

    override fun getViewBinding(
        inflater: LayoutInflater,
        container: ViewGroup?
    ): FragmentDynamicBinding {
        return FragmentDynamicBinding.inflate(inflater, container, false)
    }

    override fun initView() {
        setupUpList()
        setupVideoList()
    }

    override fun onRetryClick() {
        if (!sessionGateway.isLoggedIn()) {
            (activity as? MainActivity)?.openOverlayFragment(SignInFragment.newInstance(), "sign_in")
        } else {
            currentUpId = 0L
            loadData()
        }
    }

    private fun setupUpList() {
        upAdapter = DynamicUpAdapter(
            onItemClick = { up -> onUpClick(up) },
            onItemFocused = {
                if (!pendingVideoFocusRestoreOnResume) {
                    preferredContentFocusTarget = ContentFocusTarget.LEFT_UP_LIST
                }
            },
            onLeftEdge = { (activity as? MainActivity)?.focusLeftFunctionArea() == true },
            onRightEdge = { focusNearestVideoCard() },
            debugTag = null
        )
        binding.recyclerViewLeft.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerViewLeft.adapter = upAdapter
        binding.recyclerViewLeft.itemAnimator = null
        binding.recyclerViewLeft.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy > 0) checkLoadMoreFollowing()
            }
        })
    }

    private fun setupVideoList() {
        videoAdapter = DynamicVideoAdapter(
            onItemClick = { video -> onVideoClick(video) },
            onItemFocused = { position ->
                lastFocusedVideoPosition = position
                preferredContentFocusTarget = ContentFocusTarget.RIGHT_VIDEO_LIST
            },
            onLeftEdge = { focusNearestUpItem() },
            onItemFocusedWithView = { view, position ->
                videoFocusController?.onItemFocused(view, position)
            },
            onItemDpad = { view, keyCode, event ->
                videoFocusController?.handleKey(view, keyCode, event) == true
            },
            onItemsChanged = {
                videoFocusController?.onDataChanged(TvDataChangeReason.REMOVE_ITEM)
            }
        )
        binding.recyclerViewRight.layoutManager = WrapContentGridLayoutManager(requireContext(), 3)
        binding.recyclerViewRight.adapter = videoAdapter
        VideoRecyclerViewTuning.apply(binding.recyclerViewRight, videoAdapter)
        RecyclerViewPoolPrewarmer.prewarm(
            recyclerView = binding.recyclerViewRight,
            adapter = videoAdapter,
            source = "Dynamic.initial",
            plan = RecyclerViewPoolPrewarmer.Plan.DynamicFeed
        )
        binding.recyclerViewRight.setOnKeyListener { _, _, _ ->
            false
        }
        binding.recyclerViewRight.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy > 0) checkLoadMore()
            }
        })
        installVideoFocusController()
        swipeRefreshLayout = SwipeRefreshHelper.wrapRecyclerView(
            recyclerView = binding.recyclerViewRight,
            onRefresh = ::refreshCurrentVideoList
        ) {
            setOnChildScrollUpCallback { _, _ ->
                binding.recyclerViewRight.canScrollVertically(-1)
            }
        }
    }

    private fun onUpClick(up: FollowingModel) {
        val clickedPosition = upAdapter.getData().indexOfFirst { it.mid == up.mid }
        val wasSelected = clickedPosition >= 0 &&
            clickedPosition == upAdapter.getSelectedPosition() &&
            currentUpId == up.mid
        if (clickedPosition >= 0) {
            upAdapter.setSelectedPosition(clickedPosition)
        }
        currentUpId = up.mid
        lastFocusedVideoPosition = 0
        pendingScrollToTop = true
        preferredContentFocusTarget = ContentFocusTarget.LEFT_UP_LIST
        if (wasSelected) {
            swipeRefreshLayout?.isRefreshing = true
            lastRefreshTime = System.currentTimeMillis()
        }
        viewModel.selectUp(currentUpId.toString(), pageSize, forceRefresh = wasSelected)
    }

    private fun onVideoClick(video: VideoModel) {
        preferredContentFocusTarget = ContentFocusTarget.RIGHT_VIDEO_LIST
        pendingVideoFocusRestoreOnResume = true
        VideoRouteNavigator.openVideo(
            context = requireContext(),
            video = video,
            playQueue = com.tutu.myblbl.ui.activity.PlayerActivity.buildPlayQueue(
                videoAdapter.getItemsSnapshot(),
                video
            )
        )
    }

    override fun initData() {
        currentOpenStartMs = PagePerfLogger.now()
        PagePerfLogger.markNow("Dynamic", "initData_start")
        AppLog.i(TAG, "DYN D0 initData start")
        loadData()
        latestScreenState = viewModel.screenState.value
        latestLoading = viewModel.loading.value
        renderUiState()
    }

    private var wasLoggedInOnPause = false

    override fun onPause() {
        wasLoggedInOnPause = sessionGateway.isLoggedIn()
        pendingVideoFocusRestoreOnResume =
            rememberVideoFocusForResume() || pendingVideoFocusRestoreOnResume
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (pendingVideoFocusRestoreOnResume) {
            scheduleVideoFocusRestore()
        }
        if (sessionGateway.isLoggedIn() != wasLoggedInOnPause) {
            currentUpId = 0L
            loadData()
        }
    }

    private fun loadData() {
        val nowMs = PagePerfLogger.now()
        if (currentUpId == 0L && latestInitialRequestStartMs > 0L && nowMs - latestInitialRequestStartMs < 1000L) {
            PagePerfLogger.markNow("Dynamic", "skip_duplicate_initial_request")
            return
        }
        latestVideoRequestStartMs = nowMs
        if (currentUpId == 0L) {
            latestInitialRequestStartMs = nowMs
        }
        PagePerfLogger.markNow("Dynamic", "request_start", "upId=$currentUpId")
        pendingScrollToTop = true
        lastRefreshTime = System.currentTimeMillis()
        if (!sessionGateway.isLoggedIn()) {
            currentUpId = 0L
            viewModel.loadFollowingList()
            latestScreenState = viewModel.screenState.value
            latestLoading = viewModel.loading.value
            renderUiState()
            return
        }
        viewModel.loadFollowingList()
    }

    private fun refreshCurrentVideoList() {
        currentOpenStartMs = PagePerfLogger.now()
        latestVideoRequestStartMs = currentOpenStartMs
        PagePerfLogger.markNow("Dynamic", "refresh_start", "upId=$currentUpId")
        pendingScrollToTop = true
        lastRefreshTime = System.currentTimeMillis()
        if (!sessionGateway.isLoggedIn()) {
            currentUpId = 0L
            loadData()
            return
        }
        val targetUpId = currentUpId.takeIf { upAdapter.itemCount > 0 } ?: 0L
        currentUpId = targetUpId
        val selectedPosition = upAdapter.getData()
            .indexOfFirst { it.mid == targetUpId }
            .takeIf { it >= 0 }
            ?: 0
        if (upAdapter.itemCount > 0) {
            upAdapter.setSelectedPosition(selectedPosition)
        }
        lastFocusedVideoPosition = 0
        preferredContentFocusTarget = ContentFocusTarget.RIGHT_VIDEO_LIST
        viewModel.selectUp(targetUpId.toString(), pageSize, forceRefresh = true)
    }

    override fun initObserver() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.followingList.collectLatest { list ->
                    AppLog.i(TAG, "DYN D7 followingList collected items=${list.size}")
                    upAdapter.setData(list)
                    if (list.isNotEmpty() && currentUpId == 0L) {
                        currentUpId = list[0].mid
                        upAdapter.setSelectedPosition(0)
                        viewModel.selectUp(currentUpId.toString(), pageSize)
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.videos.collectLatest { rawVideos ->
                    val page = viewModel.loadedPage.value
                    if (rawVideos.isEmpty() && page == 0 && videoAdapter.itemCount == 0) {
                        PagePerfLogger.markNow("Dynamic", "skip_empty_initial_videos")
                        return@collectLatest
                    }
                    val rawSignature = videoListSignature(rawVideos, page)
                    if (rawSignature == lastRenderedVideoSignature && videoAdapter.contentCount() > 0) {
                        PagePerfLogger.markNow("Dynamic", "skip_duplicate_video_payload", "items=${rawVideos.size} page=$page")
                        return@collectLatest
                    }
                    val filterStartMs = SystemClock.elapsedRealtime()
                    val appContext = requireContext().applicationContext
                    val (videos, filterThread) = withContext(Dispatchers.Default) {
                        ContentFilter.filterVideos(appContext, rawVideos) to Thread.currentThread().name
                    }
                    lastRenderedVideoSignature = rawSignature
                    AppLog.i(TAG, "DYN D6 filterVideos end elapsed=${SystemClock.elapsedRealtime() - filterStartMs}ms raw=${rawVideos.size} filtered=${videos.size} filterThread=$filterThread")
                    PagePerfLogger.mark(
                        "Dynamic",
                        "filter_end",
                        filterStartMs,
                        "raw=${rawVideos.size} filtered=${videos.size} page=${viewModel.loadedPage.value}"
                    )

                    swipeRefreshLayout?.isRefreshing = false
                    if (videos.isNotEmpty()) {
                        showContent()
                        AppLog.i(TAG, "DYN D8 videos rendered count=${videos.size}")
                        val shouldScrollToTop = pendingScrollToTop
                        if (shouldScrollToTop) {
                            pendingScrollToTop = false
                            if (page <= 1) {
                                pendingInitialVideoFocusAfterFirstDraw = true
                            } else {
                                binding.recyclerViewRight.post {
                                    if (isAdded && view != null && videoAdapter.itemCount > 0) {
                                        requestPreferredContentFocus(fallbackToAlternate = true)
                                    }
                                }
                            }
                        }
                        if (page <= 1 || videoAdapter.itemCount == 0) {
                            FirstScreenRenderer.render(
                                recyclerView = binding.recyclerViewRight,
                                page = "Dynamic",
                                items = videos,
                                startMs = currentOpenStartMs.takeIf { it > 0L } ?: latestVideoRequestStartMs,
                                source = "first_screen",
                                spanCount = 3,
                                setItems = { firstBatch, onCommitted ->
                                    videoAdapter.setData(firstBatch, onCommitted)
                                },
                                appendItems = { remaining ->
                                    videoAdapter.addData(remaining)
                                },
                                onFirstBatchCommitted = {
                                    videoFocusController?.onDataChanged(TvDataChangeReason.REPLACE_PRESERVE_ANCHOR)
                                    if (shouldScrollToTop) {
                                        scrollVideoListToTop()
                                    }
                                    currentOpenStartMs = 0L
                                },
                                onFirstFrame = {
                                    onDynamicFirstFrame(page)
                                },
                                onAppendRest = {
                                    videoFocusController?.onDataChanged(TvDataChangeReason.APPEND)
                                }
                            )
                        } else {
                            val applyStartMs = PagePerfLogger.now()
                            videoAdapter.addData(videos)
                            PagePerfLogger.mark(
                                "Dynamic",
                                "adapter_apply",
                                applyStartMs,
                                "items=${videoAdapter.contentCount()} page=$page"
                            )
                            videoFocusController?.onDataChanged(TvDataChangeReason.APPEND)
                        }
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.screenState.collectLatest { state ->
                    latestScreenState = state
                    renderUiState()
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.loading.collectLatest { loading ->
                    latestLoading = loading
                    if (!loading) swipeRefreshLayout?.isRefreshing = false
                    renderUiState()
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.status.collectLatest { status ->
                    latestStatus = status
                    if (status == DynamicViewModel.DynamicStatus.Error) {
                        videoFocusController?.clearAnchorForUserRefresh()
                    }
                    renderUiState()
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                mainNavigationViewModel.events.collectLatest { event ->
                    if (isHidden) {
                        return@collectLatest
                    }
                    when (event) {
                        is MainNavigationViewModel.Event.MainTabSelected ->
                            if (event.index == 2 && shouldRefresh()) {
                                currentOpenStartMs = PagePerfLogger.now()
                                PagePerfLogger.markNow("Dynamic", "tab_selected_refresh")
                                currentUpId = 0L
                                loadData()
                            } else if (event.index == 2 && ::videoAdapter.isInitialized && videoAdapter.itemCount > 0) {
                                currentOpenStartMs = PagePerfLogger.now()
                                PagePerfLogger.markNow("Dynamic", "tab_selected_cached", "items=${videoAdapter.contentCount()}")
                                logDynamicFirstDraw(viewModel.loadedPage.value, videoAdapter.contentCount())
                            }

                        is MainNavigationViewModel.Event.MainTabReselected ->
                            if (event.index == 2) {
                                currentOpenStartMs = PagePerfLogger.now()
                                PagePerfLogger.markNow("Dynamic", "tab_reselected")
                                currentUpId = 0L
                                loadData()
                            }

                        MainNavigationViewModel.Event.MenuPressed -> {
                            currentUpId = 0L
                            loadData()
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
                    if (event == AppEventHub.Event.UserSessionChanged) {
                        currentUpId = 0L
                        loadData()
                    }
                }
            }
        }
    }

    private fun checkLoadMore() {
        val layoutManager = binding.recyclerViewRight.layoutManager as? LinearLayoutManager ?: return
        val lastVisiblePosition = layoutManager.findLastVisibleItemPosition()
        if (lastVisiblePosition >= videoAdapter.itemCount - loadMoreThreshold) {
            viewModel.loadNextPage(pageSize)
        }
    }

    private fun checkLoadMoreFollowing() {
        val layoutManager = binding.recyclerViewLeft.layoutManager as? LinearLayoutManager ?: return
        val lastVisiblePosition = layoutManager.findLastVisibleItemPosition()
        if (lastVisiblePosition >= upAdapter.itemCount - loadMoreThreshold) {
            viewModel.loadMoreFollowingIfNeeded()
        }
    }

    private fun renderUiState() {
        val showOverlay = latestScreenState != DynamicViewModel.ScreenState.Content
        val showContent = !showOverlay

        binding.recyclerViewLeft.visibility = if (showContent) View.VISIBLE else View.GONE
        binding.recyclerViewRight.visibility = if (showContent) View.VISIBLE else View.GONE
        showLoading(latestLoading && showOverlay)

        if (showOverlay) {
            when (latestScreenState) {
                DynamicViewModel.ScreenState.NotLoggedIn -> {
                    showStateOverlay(
                        imageResId = R.drawable.empty,
                        message = getString(R.string.need_sign_in),
                        retryVisible = false
                    )
                }
                DynamicViewModel.ScreenState.Error -> {
                    showStateOverlay(
                        imageResId = R.drawable.net_error,
                        message = viewModel.error.value ?: getString(R.string.net_error),
                        retryVisible = true
                    )
                }
                DynamicViewModel.ScreenState.Content -> Unit
            }
            return
        }

        val shouldToastError = latestStatus == DynamicViewModel.DynamicStatus.Error && !viewModel.error.value.isNullOrBlank()
        if (shouldToastError) {
            val message = viewModel.error.value
            if (message != null && message != lastToastMessage) {
                Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).apply {
                    setGravity(Gravity.CENTER, 0, 0)
                }.show()
                lastToastMessage = message
            }
        } else if (!latestLoading) {
            lastToastMessage = null
        }
    }

    private fun showStateOverlay(imageResId: Int, message: String, retryVisible: Boolean) {
        ensureErrorView()
        viewError?.visibility = View.VISIBLE
        imageError?.setImageResource(imageResId)
        textError?.text = message
        buttonRetry?.visibility = if (retryVisible) View.VISIBLE else View.GONE
        if (retryVisible) {
            buttonRetry?.requestFocus()
        }
        contentContainer?.visibility = View.GONE
    }

    private fun restoreVideoFocus() {
        if (viewError?.visibility == View.VISIBLE || videoAdapter.itemCount == 0) {
            return
        }
        val targetPosition = lastFocusedVideoPosition.coerceIn(0, videoAdapter.itemCount - 1)
        videoFocusController?.requestFocusPosition(targetPosition)
    }

    private fun scheduleVideoFocusRestore(retries: Int = 8) {
        binding.recyclerViewRight.post {
            if (!isAdded || view == null) {
                return@post
            }
            val currentFocus = activity?.currentFocus
            if (currentFocus != null && currentFocus.isDescendantOf(binding.recyclerViewRight)) {
                pendingVideoFocusRestoreOnResume = false
                preferredContentFocusTarget = ContentFocusTarget.RIGHT_VIDEO_LIST
                return@post
            }
            requestPreferredContentFocus(fallbackToAlternate = false)
            if (retries > 0) {
                binding.recyclerViewRight.postDelayed(
                    { scheduleVideoFocusRestore(retries - 1) },
                    48L
                )
            } else {
                pendingVideoFocusRestoreOnResume = false
            }
        }
    }

    private fun rememberVideoFocusForResume(): Boolean {
        if (!isAdded || view == null || videoAdapter.itemCount == 0) {
            return false
        }
        val currentFocusedView = activity?.currentFocus ?: return false
        val focusedChild = findRecyclerViewChild(binding.recyclerViewRight, currentFocusedView) ?: return false
        val focusedPosition = binding.recyclerViewRight.getChildAdapterPosition(focusedChild)
        if (focusedPosition == RecyclerView.NO_POSITION) {
            return false
        }
        lastFocusedVideoPosition = focusedPosition
        return true
    }

    private fun focusSelectedUpItem(): Boolean {
        if (!isAdded || view == null || upAdapter.itemCount == 0) {
            return false
        }
        val targetPosition = upAdapter.getSelectedPosition().takeIf { it >= 0 } ?: 0
        binding.recyclerViewLeft.findViewHolderForAdapterPosition(targetPosition)?.itemView?.let { itemView ->
            return itemView.requestFocus()
        }
        RecyclerViewFocusRestoreHelper.requestFocusAtPosition(
            recyclerView = binding.recyclerViewLeft,
            position = targetPosition
        )
        return true
    }

    private fun focusNearestUpItem(): Boolean {
        val anchor = activity?.currentFocus
        if (anchor != null && anchor.isDescendantOf(binding.recyclerViewRight)) {
            return SpatialFocusNavigator.requestBestDescendant(
                anchorView = anchor,
                root = binding.recyclerViewLeft,
                direction = View.FOCUS_LEFT,
                fallback = { focusSelectedUpItem() }
            )
        }
        return focusSelectedUpItem()
    }

    private fun focusRightContent(): Boolean {
        if (TabContentFocusHelper.requestVisibleFocus(buttonRetry)) {
            return true
        }
        if (videoAdapter.itemCount == 0) {
            return false
        }
        return videoFocusController?.focusPrimary() == true
    }

    private fun focusNearestVideoCard(): Boolean {
        val anchor = activity?.currentFocus
        if (anchor != null && anchor.isDescendantOf(binding.recyclerViewLeft)) {
            if (videoAdapter.itemCount == 0) {
                return false
            }
            return SpatialFocusNavigator.requestBestDescendant(
                anchorView = anchor,
                root = binding.recyclerViewRight,
                direction = View.FOCUS_RIGHT,
                fallback = { focusRightContent() }
            )
        }
        return focusRightContent()
    }

    private fun focusPrimaryContent(): Boolean {
        if (!isAdded || view == null) {
            return false
        }
        if (viewError?.visibility == View.VISIBLE) {
            return if (buttonRetry?.isShown == true) {
                buttonRetry?.requestFocus() == true
            } else {
                false
            }
        }
        return requestPreferredContentFocus(fallbackToAlternate = true)
    }

    private fun focusPrimaryContent(anchorView: View?, preferSpatialEntry: Boolean): Boolean {
        if (preferSpatialEntry) {
            if (anchorView != null) {
                when {
                    anchorView.isDescendantOf(binding.recyclerViewRight) -> {
                        if (focusRightContent()) {
                            return true
                        }
                    }

                    anchorView.isDescendantOf(binding.recyclerViewLeft) -> {
                        if (focusSelectedUpItem()) {
                            return true
                        }
                    }
                }
            }
            val handled = SpatialFocusNavigator.requestBestDescendant(
                anchorView = anchorView,
                root = binding.recyclerViewLeft,
                direction = View.FOCUS_RIGHT,
                fallback = null
            )
            if (handled) {
                return true
            }
        }
        return focusPrimaryContent()
    }

    private fun scrollVideoListToTop() {
        videoFocusController?.clearAnchorForUserRefresh()
        binding.recyclerViewRight.scrollToPosition(0)
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            val currentFocusedView = activity?.currentFocus
            if (currentFocusedView != null &&
                currentFocusedView !== binding.recyclerViewRight &&
                currentFocusedView !== binding.recyclerViewLeft &&
                !currentFocusedView.isDescendantOf(binding.recyclerViewRight) &&
                !currentFocusedView.isDescendantOf(binding.recyclerViewLeft)
            ) {
                return
            }
            requestPreferredContentFocus(fallbackToAlternate = true)
        }
    }

    override fun focusEntryFromMainTab(): Boolean {
        return focusEntryFromMainTab(anchorView = null, preferSpatialEntry = false)
    }

    override fun focusEntryFromMainTab(anchorView: View?, preferSpatialEntry: Boolean): Boolean {
        return focusPrimaryContent(anchorView, preferSpatialEntry)
    }

    private var lastRefreshTime = 0L

    private fun shouldRefresh(): Boolean {
        return videoAdapter.itemCount == 0 || upAdapter.itemCount == 0 ||
                System.currentTimeMillis() - lastRefreshTime >= CACHE_TTL_MS
    }

    private fun View.isDescendantOf(ancestor: View): Boolean {
        var current: View? = this
        while (current != null) {
            if (current === ancestor) {
                return true
            }
            current = current.parent as? View
        }
        return false
    }

    private fun findRecyclerViewChild(recyclerView: RecyclerView, view: View): View? {
        var current: View? = view
        while (current != null) {
            val parent = current.parent
            if (parent === recyclerView) {
                return current
            }
            current = parent as? View
        }
        return null
    }

    private fun requestPreferredContentFocus(fallbackToAlternate: Boolean): Boolean {
        val activeTarget = if (pendingVideoFocusRestoreOnResume) {
            ContentFocusTarget.RIGHT_VIDEO_LIST
        } else {
            preferredContentFocusTarget
        }
        return when (activeTarget) {
            ContentFocusTarget.RIGHT_VIDEO_LIST -> {
                if (focusRightContent()) {
                    true
                } else {
                    fallbackToAlternate && focusSelectedUpItem()
                }
            }

            ContentFocusTarget.LEFT_UP_LIST -> {
                if (focusSelectedUpItem()) {
                    true
                } else {
                    fallbackToAlternate && focusRightContent()
                }
            }
        }
    }

    private fun installVideoFocusController() {
        videoFocusController?.release()
        videoFocusController = TvListFocusController(
            recyclerView = binding.recyclerViewRight,
            adapter = videoAdapter,
            strategy = GridTvFocusStrategy { 3 },
            canLoadMore = { viewModel.hasMoreVideos.value },
            loadMore = {
                if (!viewModel.loading.value && viewModel.hasMoreVideos.value) {
                    viewModel.loadNextPage(pageSize)
                }
            }
        )
    }

    private fun logDynamicFirstDraw(page: Int, itemCount: Int) {
        if (page > 1) {
            return
        }
        val startMs = currentOpenStartMs.takeIf { it > 0L } ?: latestVideoRequestStartMs
        if (startMs <= 0L || itemCount <= 0) return
        FirstScreenRenderer.logFirstFrame(
            recyclerView = binding.recyclerViewRight,
            page = "Dynamic",
            startMs = startMs,
            itemCount = itemCount,
            source = "page=$page",
            onLogged = {
                onDynamicFirstFrame(page)
            }
        )
        if (page <= 1) {
            currentOpenStartMs = 0L
        }
    }

    private fun onDynamicFirstFrame(page: Int) {
        if (page <= 1 && ::upAdapter.isInitialized) {
            upAdapter.setAvatarLoadsEnabled(true)
        }
        if (pendingInitialVideoFocusAfterFirstDraw) {
            pendingInitialVideoFocusAfterFirstDraw = false
            binding.recyclerViewRight.post {
                if (isAdded && view != null && videoAdapter.itemCount > 0) {
                    val focused = activity?.currentFocus
                    if (focused != null &&
                        focused !== binding.recyclerViewRight &&
                        focused !== binding.recyclerViewLeft &&
                        !focused.isDescendantOf(binding.recyclerViewRight) &&
                        !focused.isDescendantOf(binding.recyclerViewLeft)
                    ) {
                        return@post
                    }
                    requestPreferredContentFocus(fallbackToAlternate = true)
                }
            }
        }
    }

    private fun videoListSignature(videos: List<VideoModel>, page: Int): String {
        if (videos.isEmpty()) return "p$page:empty"
        val first = videos.first()
        val last = videos.last()
        return buildString {
            append("p=")
            append(page)
            append(";n=")
            append(videos.size)
            append(";f=")
            append(first.bvid.ifBlank { first.aid.toString() })
            append(';')
            append(first.title.hashCode())
            append(";l=")
            append(last.bvid.ifBlank { last.aid.toString() })
            append(';')
            append(last.title.hashCode())
        }
    }

    override fun onDestroyView() {
        videoFocusController?.release()
        videoFocusController = null
        super.onDestroyView()
    }

    override fun onVideoBlocked(aid: Long, bvid: String) {
        if (::videoAdapter.isInitialized) {
            videoAdapter.removeByVideoId(aid, bvid)
        }
    }
}
