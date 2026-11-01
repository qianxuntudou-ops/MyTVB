package com.mytvb.feature.live

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.core.os.bundleOf
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.RecyclerView
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.mytvb.R
import com.mytvb.core.ui.focus.isDescendantOf
import com.mytvb.databinding.FragmentLiveListBinding
import com.mytvb.model.live.LiveRoomItem
import com.mytvb.ui.activity.LivePlayerActivity
import com.mytvb.core.ui.base.BaseFragment
import com.mytvb.core.ui.base.BaseListFragment
import com.mytvb.core.ui.base.RecyclerViewPoolPrewarmer
import com.mytvb.core.ui.base.adaptiveSpanCount
import com.mytvb.feature.settings.SignInFragment
import com.mytvb.core.ui.layout.WrapContentGridLayoutManager
import com.mytvb.core.common.content.ContentFilter
import com.mytvb.core.ui.focus.SpatialFocusNavigator
import com.mytvb.core.ui.focus.tv.GridTvFocusStrategy
import com.mytvb.core.ui.focus.tv.TvDataChangeReason
import com.mytvb.core.ui.focus.tv.TvListFocusController
import com.mytvb.core.common.log.AppLog
import com.mytvb.core.common.log.PagePerfLogger
import com.mytvb.core.ui.navigation.navigateBackFromUi
import com.mytvb.core.ui.refresh.SwipeRefreshHelper
import com.mytvb.core.ui.render.FirstScreenRenderer
import com.mytvb.core.common.ext.toast
import com.mytvb.network.session.SessionStateRepository
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class LiveListFragment : BaseFragment<FragmentLiveListBinding>(), LiveTabPage {

    private val viewModel: LiveListViewModel by viewModel()
    private val sessionGateway: SessionStateRepository by inject()
    private lateinit var adapter: LiveRoomAdapter
    private var swipeRefreshLayout: androidx.swiperefreshlayout.widget.SwipeRefreshLayout? = null
    private var areaId: Long = 0
    private var parentAreaId: Long = 0
    private var title: String = ""
    private var latestLoading = false
    private var latestStatus = LiveListViewModel.LiveListStatus.Idle
    private var latestError: String? = null
    private var isFirstPageLoad = true
    private var needsLogin = false
    private var tvFocusController: TvListFocusController? = null
    private var globalFocusLoadMoreListener: ViewTreeObserver.OnGlobalFocusChangeListener? = null
    private var currentOpenStartMs = 0L

    companion object {
        private const val ARG_AREA_ID = "area_id"
        private const val ARG_PARENT_AREA_ID = "parent_area_id"
        private const val ARG_TITLE = "title"

        fun newAreaInstance(areaId: Long, parentAreaId: Long, title: String): LiveListFragment {
            return LiveListFragment().apply {
                arguments = bundleOf(
                    ARG_AREA_ID to areaId,
                    ARG_PARENT_AREA_ID to parentAreaId,
                    ARG_TITLE to title
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        areaId = arguments?.getLong(ARG_AREA_ID) ?: 0
        parentAreaId = arguments?.getLong(ARG_PARENT_AREA_ID) ?: 0
        title = arguments?.getString(ARG_TITLE).orEmpty()
    }

    override fun getViewBinding(
        inflater: LayoutInflater,
        container: ViewGroup?
    ): FragmentLiveListBinding {
        return FragmentLiveListBinding.inflate(inflater, container, false)
    }

    override fun initView() {
        adapter = LiveRoomAdapter(
            onItemClick = ::onRoomClick,
            onDpadKey = { view, keyCode, event ->
                tvFocusController?.handleKey(view, keyCode, event) ?: false
            }
        )

        val layoutManager = WrapContentGridLayoutManager(requireContext(), resources.adaptiveSpanCount())
        binding.recyclerView.layoutManager = layoutManager
        binding.recyclerView.adapter = adapter
        binding.recyclerView.itemAnimator = null
        binding.recyclerView.setHasFixedSize(true)
        binding.recyclerView.setRecycledViewPool(BaseListFragment.sharedVideoPool)
        RecyclerViewPoolPrewarmer.prewarm(
            recyclerView = binding.recyclerView,
            adapter = adapter,
            count = 4,
            source = "${pageTag()}.initial"
        )

        tvFocusController = TvListFocusController(
            recyclerView = binding.recyclerView,
            adapter = adapter,
            strategy = GridTvFocusStrategy.from(binding.recyclerView),
            canLoadMore = { viewModel.hasMore.value },
            loadMore = {
                if (!viewModel.loading.value && viewModel.hasMore.value) {
                    viewModel.loadNextPage()
                }
            }
        )
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() {
                tvFocusController?.onDataChanged(TvDataChangeReason.REPLACE_PRESERVE_ANCHOR)
            }
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                tvFocusController?.onDataChanged(TvDataChangeReason.APPEND)
                // 落地即续拉：本页条数少、焦点仍贴着列表尾部时立刻预取下一页
                checkLiveLoadMore()
            }
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
                tvFocusController?.onDataChanged(TvDataChangeReason.REMOVE_ITEM)
            }
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int) {
                tvFocusController?.onDataChanged(TvDataChangeReason.REPLACE_PRESERVE_ANCHOR)
            }
        })
        binding.tvTitle.text = title
        binding.buttonBack.setOnClickListener {
            navigateBackFromUi()
        }
        setupLoadMore()
        swipeRefreshLayout = SwipeRefreshHelper.wrapRecyclerView(binding.recyclerView) {
            reload()
        }
        if (!sessionGateway.isLoggedIn()) {
            needsLogin = true
            binding.emptyContainer.visibility = View.VISIBLE
            binding.recyclerView.visibility = View.GONE
            binding.progressBar.visibility = View.GONE
            binding.tvEmpty.text = getString(R.string.need_sign_in)
            binding.btnRetry.text = getString(R.string.live_login)
            binding.btnRetry.setOnClickListener {
                openInHostContainer(SignInFragment.newInstance())
            }
            binding.btnRetry.visibility = View.VISIBLE
        } else {
            binding.btnRetry.setOnClickListener { reload() }
            renderState()
        }
    }
    
    private fun setupLoadMore() {
        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                checkLiveLoadMore()
            }
        })
        // LiveRoomAdapter 无焦点回调，焦点落定补查走全局焦点监听：焦点导航目标 item
        // 已在屏内可见时不产生滚动（onScrolled 不触发），必须焦点落卡即预查，
        // 否则要等焦点顶死最后一行才触发加载（体感"触底加载慢"，首页推荐同款修法）
        globalFocusLoadMoreListener = ViewTreeObserver.OnGlobalFocusChangeListener { _, newFocus ->
            if (newFocus != null && binding.recyclerView.isShown &&
                newFocus.isDescendantOf(binding.recyclerView)
            ) {
                checkLiveLoadMore()
            }
        }
        binding.root.viewTreeObserver.addOnGlobalFocusChangeListener(globalFocusLoadMoreListener)
    }

    /** 触底判定（滚动/焦点落定共用）：剩不足 12 个可见项即预取下一页。 */
    private fun checkLiveLoadMore() {
        if (viewModel.loading.value || !viewModel.hasMore.value) return
        val layoutManager = binding.recyclerView.layoutManager as? WrapContentGridLayoutManager ?: return
        val lastVisibleItem = layoutManager.findLastVisibleItemPosition()
        if (lastVisibleItem >= layoutManager.itemCount - 12) {
            viewModel.loadNextPage()
        }
    }

    override fun initData() {
        currentOpenStartMs = PagePerfLogger.now()
        PagePerfLogger.markNow(pageTag(), "initData_start")
        if (!sessionGateway.isLoggedIn()) {
            needsLogin = true
            binding.emptyContainer.visibility = View.VISIBLE
            binding.recyclerView.visibility = View.GONE
            binding.progressBar.visibility = View.GONE
            binding.tvEmpty.text = getString(R.string.need_sign_in)
            binding.btnRetry.text = getString(R.string.live_login)
            binding.btnRetry.setOnClickListener {
                openInHostContainer(SignInFragment.newInstance())
            }
            binding.btnRetry.visibility = View.VISIBLE
            return
        }
        viewModel.startArea(parentAreaId, areaId)
    }

    override fun initObserver() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.rooms.collectLatest { rawRooms ->
                    val collectStartMs = PagePerfLogger.now()
                    PagePerfLogger.mark(pageTag(), "data_collected", currentOpenStartMs, "raw=${rawRooms.size}")
                    val rooms = ContentFilter.filterLiveRooms(requireContext(), rawRooms)
                    PagePerfLogger.mark(
                        pageTag(),
                        "filter_end",
                        collectStartMs,
                        "raw=${rawRooms.size} filtered=${rooms.size}"
                    )
                    swipeRefreshLayout?.isRefreshing = false
                    val applyStartMs = PagePerfLogger.now()
                    if (currentOpenStartMs > 0L || adapter.itemCount == 0) {
                        FirstScreenRenderer.render(
                            recyclerView = binding.recyclerView,
                            page = pageTag(),
                            items = rooms,
                            startMs = currentOpenStartMs,
                            source = "first_screen",
                            spanCount = resources.adaptiveSpanCount(),
                            setItems = { firstBatch, onCommitted ->
                                adapter.setData(firstBatch, onCommitted)
                            },
                            appendItems = { remaining ->
                                adapter.addData(remaining)
                            },
                            onFirstBatchCommitted = {
                                PagePerfLogger.mark(pageTag(), "adapter_apply", applyStartMs, "items=${adapter.itemCount}")
                                tvFocusController?.onDataChanged(TvDataChangeReason.REPLACE_PRESERVE_ANCHOR)
                                currentOpenStartMs = 0L
                            },
                            onAppendRest = {
                                tvFocusController?.onDataChanged(TvDataChangeReason.APPEND)
                            }
                        )
                    } else {
                        adapter.setData(rooms) {
                            PagePerfLogger.mark(pageTag(), "adapter_apply", applyStartMs, "items=${adapter.itemCount}")
                            tvFocusController?.onDataChanged(TvDataChangeReason.APPEND)
                        }
                    }
                    if (isFirstPageLoad && rooms.isNotEmpty()) {
                        isFirstPageLoad = false
                        binding.recyclerView.scrollToPosition(0)
                        binding.recyclerView.post {
                            binding.recyclerView.requestFocus()
                        }
                    }
                    renderState()
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.loading.collectLatest { loading ->
                    latestLoading = loading
                    renderState()
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.status.collectLatest { status ->
                    latestStatus = status
                    renderState()
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.error.collectLatest { error ->
                    latestError = error
                    if (!error.isNullOrBlank() && adapter.itemCount > 0) {
                        requireContext().toast(error)
                    }
                    renderState()
                }
            }
        }
    }

    private fun onRoomClick(room: LiveRoomItem) {
        LivePlayerActivity.start(requireContext(), room.roomId)
    }

    override fun onPause() {
        tvFocusController?.captureCurrentAnchor()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        val controller = tvFocusController ?: return
        // 从直播播放器返回：强制拉回捕获锚点 + 轮询确认真实焦点落点，避免焦点停在返回按钮
        // 或彻底丢失（restoreCapturedAnchor 会因焦点落在列表外部 View 而跳过，且无兜底）。
        controller.restoreFocusAfterReturn(
            onRestored = {},
            onFailed = { tvFocusController?.ensureValidFocus("resume") }
        )
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            val controller = tvFocusController ?: return
            controller.restoreFocusAfterReturn(
                onRestored = {},
                onFailed = { tvFocusController?.ensureValidFocus("shown") }
            )
        }
    }

    override fun onDestroyView() {
        globalFocusLoadMoreListener?.let { listener ->
            if (binding.root.viewTreeObserver.isAlive) {
                binding.root.viewTreeObserver.removeOnGlobalFocusChangeListener(listener)
            }
        }
        globalFocusLoadMoreListener = null
        tvFocusController?.release()
        tvFocusController = null
        super.onDestroyView()
    }

    override fun focusPrimaryContent(): Boolean {
        if (!isAdded || view == null || adapter.itemCount == 0) {
            AppLog.d("LiveList", "focusPrimaryContent: skipped isAdded=$isAdded itemCount=${adapter.itemCount}")
            return false
        }
        val result = tvFocusController?.focusPrimary() ?: false
        AppLog.d("LiveList", "focusPrimaryContent: result=$result")
        return result
    }

    override fun focusPrimaryContent(anchorView: View?, preferSpatialEntry: Boolean): Boolean {
        if (preferSpatialEntry && anchorView != null) {
            val handled = SpatialFocusNavigator.requestBestDescendant(
                anchorView = anchorView,
                root = binding.recyclerView,
                direction = View.FOCUS_RIGHT,
                fallback = null
            )
            AppLog.d("LiveList", "focusPrimaryContent: spatialEntry=$handled anchor=${anchorView.javaClass.simpleName}")
            if (handled) return true
        }
        return focusPrimaryContent()
    }


    override fun scrollToTop() {
        binding.recyclerView.smoothScrollToPosition(0)
    }

    private fun reload() {
        currentOpenStartMs = PagePerfLogger.now()
        PagePerfLogger.markNow(pageTag(), "reload_start", "hasContent=${adapter.itemCount > 0}")
        tvFocusController?.clearAnchorForUserRefresh()
        viewModel.startArea(parentAreaId, areaId, preserveExisting = adapter.itemCount > 0)
    }


    private fun pageTag(): String = "LiveList/${title.ifBlank { areaId.toString() }}"

    private fun renderState() {
        if (needsLogin) return
        val hasData = adapter.itemCount > 0
        val emptyMessage = when {
            latestLoading && !hasData -> ""
            latestStatus == LiveListViewModel.LiveListStatus.Error && !hasData -> {
                latestError ?: getString(R.string.net_error)
            }

            latestStatus == LiveListViewModel.LiveListStatus.Empty -> {
                getString(R.string.empty_data)
            }

            else -> ""
        }
        val showOverlay = emptyMessage.isNotBlank() && !hasData

        binding.recyclerView.visibility = if (showOverlay) View.GONE else View.VISIBLE
        binding.progressBar.visibility = if (latestLoading && !hasData) View.VISIBLE else View.GONE
        binding.emptyContainer.visibility = if (showOverlay) View.VISIBLE else View.GONE
        binding.tvEmpty.text = emptyMessage
        binding.btnRetry.visibility = if (latestStatus == LiveListViewModel.LiveListStatus.Error) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }
}
