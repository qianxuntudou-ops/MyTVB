package com.tutu.myblbl.feature.live

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.RecyclerView
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.tutu.myblbl.R
import com.tutu.myblbl.databinding.FragmentLiveListBinding
import com.tutu.myblbl.model.live.LiveRoomItem
import com.tutu.myblbl.ui.activity.LivePlayerActivity
import com.tutu.myblbl.core.ui.base.BaseFragment
import com.tutu.myblbl.core.ui.image.ImageLoader
import com.tutu.myblbl.feature.settings.SignInFragment
import com.tutu.myblbl.core.ui.layout.WrapContentGridLayoutManager
import com.tutu.myblbl.core.common.content.ContentFilter
import com.tutu.myblbl.core.ui.focus.SpatialFocusNavigator
import com.tutu.myblbl.core.ui.focus.tv.GridTvFocusStrategy
import com.tutu.myblbl.core.ui.focus.tv.TvDataChangeReason
import com.tutu.myblbl.core.ui.focus.tv.TvListFocusController
import com.tutu.myblbl.core.common.log.AppLog
import com.tutu.myblbl.core.ui.navigation.navigateBackFromUi
import com.tutu.myblbl.core.ui.refresh.SwipeRefreshHelper
import com.tutu.myblbl.core.common.ext.toast
import com.tutu.myblbl.network.session.NetworkSessionGateway
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class LiveListFragment : BaseFragment<FragmentLiveListBinding>(), LiveTabPage {

    private val viewModel: LiveListViewModel by viewModel()
    private val sessionGateway: NetworkSessionGateway by inject()
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

    companion object {
        private const val ARG_AREA_ID = "area_id"
        private const val ARG_PARENT_AREA_ID = "parent_area_id"
        private const val ARG_TITLE = "title"
        private const val PREFETCH_COVER_COUNT = 8

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
        adapter = LiveRoomAdapter(onItemClick = ::onRoomClick)

        val layoutManager = WrapContentGridLayoutManager(requireContext(), 4)
        binding.recyclerView.layoutManager = layoutManager
        binding.recyclerView.adapter = adapter
        binding.recyclerView.itemAnimator = null
        binding.recyclerView.setHasFixedSize(true)

        tvFocusController = TvListFocusController(
            recyclerView = binding.recyclerView,
            adapter = adapter,
            strategy = GridTvFocusStrategy { 4 },
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
            binding.btnRetry.text = "登录"
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
                val layoutManager = binding.recyclerView.layoutManager as? WrapContentGridLayoutManager ?: return
                val totalItemCount = layoutManager.itemCount
                val lastVisibleItem = layoutManager.findLastVisibleItemPosition()
                
                if (!viewModel.loading.value && viewModel.hasMore.value && lastVisibleItem >= totalItemCount - 12) {
                    viewModel.loadNextPage()
                }
            }
        })
    }

    override fun initData() {
        if (!sessionGateway.isLoggedIn()) {
            needsLogin = true
            binding.emptyContainer.visibility = View.VISIBLE
            binding.recyclerView.visibility = View.GONE
            binding.progressBar.visibility = View.GONE
            binding.tvEmpty.text = getString(R.string.need_sign_in)
            binding.btnRetry.text = "登录"
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
                    val rooms = ContentFilter.filterLiveRooms(requireContext(), rawRooms)
                    if (rooms.isNotEmpty()) {
                        ImageLoader.prefetchVideoCovers(
                            requireContext(),
                            rooms.asSequence().take(PREFETCH_COVER_COUNT).map { it.cover }.toList()
                        )
                    }
                    swipeRefreshLayout?.isRefreshing = false
                    adapter.setData(rooms)
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
        tvFocusController?.restoreCapturedAnchor()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            tvFocusController?.restoreCapturedAnchor()
        }
    }

    override fun onDestroyView() {
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

    private fun focusTopTab(): Boolean {
        return (parentFragment as? LiveFragment)?.focusCurrentTab() == true
    }

    override fun scrollToTop() {
        binding.recyclerView.smoothScrollToPosition(0)
    }

    private fun reload() {
        tvFocusController?.clearAnchorForUserRefresh()
        viewModel.startArea(parentAreaId, areaId, preserveExisting = adapter.itemCount > 0)
    }

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
