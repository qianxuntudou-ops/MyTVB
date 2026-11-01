package com.mytvb.feature.me

import android.os.Bundle
import android.view.LayoutInflater
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.core.os.bundleOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.reflect.TypeToken
import com.mytvb.R
import com.mytvb.databinding.FragmentMeTabListBinding
import com.mytvb.event.AppEventHub
import com.mytvb.network.session.NetworkSessionGateway
import com.mytvb.repository.SeriesRepository
import com.mytvb.repository.UserRepository
import com.mytvb.ui.adapter.SeriesAdapter
import com.mytvb.core.ui.base.BaseFragment
import com.mytvb.core.ui.base.RecyclerViewFocusRestoreHelper
import com.mytvb.core.ui.base.adaptiveSpanCount
import com.mytvb.feature.series.SeriesDetailFragment
import com.mytvb.core.ui.layout.WrapContentGridLayoutManager
import com.mytvb.core.common.cache.FileCacheManager
import com.mytvb.core.ui.focus.RecyclerViewLoadMoreFocusController
import com.mytvb.core.ui.focus.isDescendantOf
import com.mytvb.core.ui.focus.SpatialFocusNavigator
import com.mytvb.core.ui.focus.TabContentFocusHelper
import com.mytvb.core.ui.refresh.SwipeRefreshHelper
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class MeSeriesFragment : BaseFragment<FragmentMeTabListBinding>(), MeTabPage {

    companion object {
        private const val ARG_TYPE = "type"
        private const val FOLLOWING_ANIMATION_CACHE_KEY = "followingAnimationCacheList"
        private const val FOLLOWING_SERIES_CACHE_KEY = "followingSeriesCacheList"

        const val TYPE_ANIMATION = 1
        const val TYPE_SERIES = 2

        fun newInstance(type: Int): MeSeriesFragment {
            return MeSeriesFragment().apply {
                arguments = bundleOf(ARG_TYPE to type)
            }
        }
    }

    private val appEventHub: AppEventHub by inject()
    private val sessionGateway: NetworkSessionGateway by inject()
    private val repository: SeriesRepository by inject()
    private val userRepository: UserRepository by inject()

    private var type: Int = TYPE_ANIMATION
    private var currentPage = 1
    private val pageSize = 20
    private var hasMore = true
    private var isLoading = false
    private var lastRefreshTime = 0L
    private var pendingRestoreFocus = false

    private lateinit var adapter: SeriesAdapter
    private var swipeRefreshLayout: androidx.swiperefreshlayout.widget.SwipeRefreshLayout? = null
    private var loadMoreFocusController: RecyclerViewLoadMoreFocusController? = null
    private var globalFocusLoadMoreListener: ViewTreeObserver.OnGlobalFocusChangeListener? = null

    override fun initArguments() {
        type = arguments?.getInt(ARG_TYPE, TYPE_ANIMATION) ?: TYPE_ANIMATION
    }

    override fun getViewBinding(
        inflater: LayoutInflater,
        container: ViewGroup?
    ): FragmentMeTabListBinding {
        return FragmentMeTabListBinding.inflate(inflater, container, false)
    }

    override fun initView() {
        adapter = SeriesAdapter(onItemClick = { series ->
            if (series.seasonId > 0) {
                pendingRestoreFocus = true
                openInHostContainer(SeriesDetailFragment.newInstance(series.seasonId))
            }
        }, onTopEdgeUp = ::focusTopTab)

        binding.recyclerView.layoutManager =
            WrapContentGridLayoutManager(requireContext(), resources.adaptiveSpanCount(base = 6, wide = 8))
        binding.recyclerView.adapter = adapter
        binding.recyclerView.setHasFixedSize(true)
        binding.emptyContainer.visibility = View.GONE
        binding.progressBar.visibility = View.GONE
        binding.btnRetry.setOnClickListener { refresh() }
        binding.btnRetry.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN &&
                keyCode == KeyEvent.KEYCODE_DPAD_UP
            ) {
                focusTopTab()
            } else {
                false
            }
        }
        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                checkSeriesLoadMore()
            }
        })
        // SeriesAdapter 无焦点回调，焦点落定补查走全局焦点监听：焦点导航目标 item
        // 已在屏内可见时不产生滚动（onScrolled 不触发），必须焦点落卡即预查，
        // 否则要等焦点顶死最后一行才触发加载（体感"触底加载慢"，首页推荐同款修法）
        installFocusChangeLoadMoreHook()
        installLoadMoreFocusController()
        swipeRefreshLayout = SwipeRefreshHelper.wrapRecyclerView(
            recyclerView = binding.recyclerView,
            onRefresh = { refresh() }
        )
    }

    override fun initData() {
        restoreCachedContent()
        loadData()
    }

    override fun onResume() {
        super.onResume()
        if (pendingRestoreFocus) {
            pendingRestoreFocus = false
            restoreContentFocus()
        }
    }

    override fun initObserver() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                appEventHub.events.collectLatest { event ->
                    if (event == AppEventHub.Event.UserSessionChanged && !isHidden && isVisible) {
                        refresh()
                    }
                }
            }
        }
    }

    /** 触底判定（滚动/焦点落定共用）：剩不足 6 个可见项即预取下一页。 */
    private fun checkSeriesLoadMore() {
        if (isLoading || !hasMore) return
        val layoutManager =
            binding.recyclerView.layoutManager as? WrapContentGridLayoutManager ?: return
        val lastVisibleItem = layoutManager.findLastVisibleItemPosition()
        if (lastVisibleItem >= layoutManager.itemCount - 6) {
            currentPage++
            loadData()
        }
    }

    private fun installFocusChangeLoadMoreHook() {
        globalFocusLoadMoreListener = ViewTreeObserver.OnGlobalFocusChangeListener { _, newFocus ->
            if (newFocus != null && binding.recyclerView.isShown &&
                newFocus.isDescendantOf(binding.recyclerView)
            ) {
                checkSeriesLoadMore()
            }
        }
        binding.root.viewTreeObserver.addOnGlobalFocusChangeListener(globalFocusLoadMoreListener)
    }

    private fun loadData() {
        if (isLoading) return

        if (!sessionGateway.isLoggedIn()) {
            showState(getString(R.string.need_sign_in), retryVisible = false)
            return
        }

        lifecycleScope.launch {
            val mid = userRepository.resolveCurrentUserMid().getOrNull() ?: 0L
            if (mid <= 0) {
                showState(getString(R.string.need_sign_in), retryVisible = false)
                return@launch
            }

            isLoading = true
            if (currentPage == 1 && adapter.itemCount == 0) {
                showLoading()
            }

            repository.getMyFollowingSeries(type, currentPage, pageSize, mid)
                .onSuccess { result ->
                    isLoading = false
                    swipeRefreshLayout?.isRefreshing = false
                    val list = result.list
                    if (currentPage == 1) {
                        adapter.setData(list)
                        cacheSeries(list)
                        lastRefreshTime = System.currentTimeMillis()
                    } else if (list.isNotEmpty()) {
                        adapter.addData(list)
                    }
                    loadMoreFocusController?.consumePendingFocusAfterLoadMore()
                    hasMore = list.size >= pageSize
                    if (currentPage == 1 && list.isEmpty()) {
                        showState(getEmptyMessage(), retryVisible = false)
                    } else {
                        showContent()
                        // 落地即续拉：本页条数少、焦点仍贴着列表尾部时立刻预取下一页
                        checkSeriesLoadMore()
                    }
                }
                .onFailure { exception ->
                    isLoading = false
                    swipeRefreshLayout?.isRefreshing = false
                    if (currentPage > 1) {
                        currentPage--
                        loadMoreFocusController?.clearPendingFocusAfterLoadMore()
                        if (adapter.itemCount > 0) {
                            showContent()
                            return@onFailure
                        }
                    }
                    showState(
                        exception.message ?: getString(R.string.net_error),
                        retryVisible = true
                    )
                }
        }
    }

    private fun showLoading() {
        binding.progressBar.visibility = View.VISIBLE
        binding.recyclerView.visibility = View.INVISIBLE
        binding.emptyContainer.visibility = View.GONE
    }

    override fun showContent() {
        binding.progressBar.visibility = View.GONE
        binding.recyclerView.visibility = View.VISIBLE
        binding.emptyContainer.visibility = View.GONE
    }

    private fun showState(message: String, retryVisible: Boolean) {
        binding.progressBar.visibility = View.GONE
        binding.recyclerView.visibility = View.GONE
        binding.emptyContainer.visibility = View.VISIBLE
        binding.tvEmpty.text = message
        binding.btnRetry.visibility = if (retryVisible) View.VISIBLE else View.GONE
        if (retryVisible) {
            binding.btnRetry.post { binding.btnRetry.requestFocus() }
        }
    }

    private fun getEmptyMessage(): String {
        return when (type) {
            TYPE_ANIMATION -> getString(R.string.following_animation_empty)
            TYPE_SERIES -> getString(R.string.following_series_empty)
            else -> getString(R.string.empty)
        }
    }

    override fun scrollToTop() {
        binding.recyclerView.smoothScrollToPosition(0)
    }

    override fun refresh() {
        currentPage = 1
        hasMore = true
        loadData()
    }

    override fun onTabSelected() {
        if (!isAdded || view == null || isLoading) {
            return
        }
        if (adapter.itemCount == 0) {
            currentPage = 1
            hasMore = true
            loadData()
        }
    }

    override fun onTabReselected() {
        if (!isAdded || view == null || isLoading) {
            return
        }
        refresh()
    }

    override fun onHostEvent(event: MeTabPage.HostEvent): Boolean {
        when (event) {
            MeTabPage.HostEvent.SELECT_TAB4 -> onTabSelected()
            MeTabPage.HostEvent.CLICK_TAB4 -> refresh()
            MeTabPage.HostEvent.BACK_PRESSED -> Unit
            MeTabPage.HostEvent.KEY_MENU_PRESS -> refresh()
        }
        return true
    }

    override fun focusPrimaryContent(): Boolean {
        if (!isAdded || view == null) {
            return false
        }
        if (binding.emptyContainer.visibility == View.VISIBLE && binding.btnRetry.isShown) {
            val handled = TabContentFocusHelper.requestVisibleFocus(binding.btnRetry)
            return handled
        }
        if (adapter.itemCount == 0) {
            return false
        }
        val result = TabContentFocusHelper.requestRecyclerPrimaryFocus(
            recyclerView = binding.recyclerView,
            itemCount = adapter.itemCount
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
        binding.recyclerView.post {
            if (!isAdded || binding.recyclerView.visibility != View.VISIBLE) {
                return@post
            }
            if (!adapter.requestStoredItemFocus(binding.recyclerView)) {
                focusPrimaryContent()
            }
        }
    }

    private fun restoreCachedContent() {
        if (!sessionGateway.isLoggedIn()) return
        val cachedSeries = runCatching {
            val cacheType = object : TypeToken<List<com.mytvb.model.series.SeriesModel>>() {}.type
            FileCacheManager.get<List<com.mytvb.model.series.SeriesModel>>(cacheKey(), cacheType).orEmpty()
        }.getOrElse { emptyList() }
        if (cachedSeries.isEmpty()) {
            return
        }
        adapter.setData(cachedSeries)
        showContent()
    }

    private fun cacheSeries(series: List<com.mytvb.model.series.SeriesModel>) {
        if (series.isEmpty()) {
            return
        }
        runCatching {
            FileCacheManager.put(cacheKey(), series)
        }
    }

    private fun cacheKey(): String {
        return when (type) {
            TYPE_SERIES -> FOLLOWING_SERIES_CACHE_KEY
            else -> FOLLOWING_ANIMATION_CACHE_KEY
        }
    }

    private fun installLoadMoreFocusController() {
        loadMoreFocusController?.release()
        loadMoreFocusController = RecyclerViewLoadMoreFocusController(
            recyclerView = binding.recyclerView,
            callbacks = object : RecyclerViewLoadMoreFocusController.Callbacks {
                override fun canLoadMore(): Boolean = !isLoading && hasMore

                override fun loadMore() {
                    if (!canLoadMore()) {
                        return
                    }
                    currentPage++
                    loadData()
                }
            }
        ).also { it.install() }
    }

    override fun onDestroyView() {
        globalFocusLoadMoreListener?.let { listener ->
            if (binding.root.viewTreeObserver.isAlive) {
                binding.root.viewTreeObserver.removeOnGlobalFocusChangeListener(listener)
            }
        }
        globalFocusLoadMoreListener = null
        loadMoreFocusController?.release()
        loadMoreFocusController = null
        super.onDestroyView()
    }
}
