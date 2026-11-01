package com.mytvb.feature.favorite

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup

import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import com.mytvb.R
import com.mytvb.databinding.FragmentFavoriteDetailBinding
import com.mytvb.event.AppEventHub
import com.mytvb.network.session.NetworkSessionGateway
import com.mytvb.repository.FavoriteRepository
import com.mytvb.ui.adapter.FavoriteHistoryAdapter
import com.mytvb.core.ui.base.BaseFragment
import com.mytvb.core.ui.base.VideoRecyclerViewTuning
import com.mytvb.core.ui.base.adaptiveSpanCount
import com.mytvb.core.ui.layout.WrapContentGridLayoutManager
import com.mytvb.core.ui.decoration.GridSpacingItemDecoration
import com.mytvb.core.common.content.ContentFilter
import com.mytvb.core.common.log.AppLog
import com.mytvb.core.common.log.PagePerfLogger
import com.mytvb.core.ui.focus.tv.GridTvFocusStrategy
import com.mytvb.core.ui.focus.tv.TvDataChangeReason
import com.mytvb.core.ui.focus.tv.TvListFocusController
import com.mytvb.core.navigation.VideoRouteNavigator
import com.mytvb.core.ui.refresh.SwipeRefreshHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import com.mytvb.core.ui.base.AppToast

class FavoriteDetailFragment : BaseFragment<FragmentFavoriteDetailBinding>() {

    companion object {
        private const val ARG_FOLDER_ID = "folder_id"
        private const val ARG_TITLE = "title"

        fun newInstance(folderId: Long, title: String): FavoriteDetailFragment {
            return FavoriteDetailFragment().apply {
                arguments = bundleOf(
                    ARG_FOLDER_ID to folderId,
                    ARG_TITLE to title
                )
            }
        }
    }

    private var folderId: Long = 0
    private var title: String = ""

    private val appEventHub: AppEventHub by inject()
    private val sessionGateway: NetworkSessionGateway by inject()
    private val favoriteRepository: FavoriteRepository by inject()
    private lateinit var favoriteAdapter: FavoriteHistoryAdapter

    private var currentPage = 1
    private var isLoading = false
    private var hasMore = true
    private var lastFocusedPosition = RecyclerView.NO_POSITION
    private var pendingRestoreFocus = false
    private var hasRequestedInitialFocus = false
    private var tvFocusController: TvListFocusController? = null
    private var swipeRefreshLayout: androidx.swiperefreshlayout.widget.SwipeRefreshLayout? = null
    private var videosLoadJob: Job? = null
    private var videosRequestSerial = 0
    private var activeVideosRequestId = 0
    private var pendingSilentReturn = false
    private var silentRefreshJob: Job? = null

    override fun getViewBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentFavoriteDetailBinding {
        return FragmentFavoriteDetailBinding.inflate(inflater, container, false)
    }

    override fun initArguments() {
        folderId = arguments?.getLong(ARG_FOLDER_ID) ?: 0
        title = arguments?.getString(ARG_TITLE) ?: ""
    }

    override fun initView() {
        binding.tvTitle.text = title

        favoriteAdapter = FavoriteHistoryAdapter(
            onItemClick = { item ->
                val video = item.toVideoModel()
                if (video.aid != 0L || video.bvid.isNotEmpty()) {
                    lastFocusedPosition = favoriteAdapter.getFocusedPosition()
                    pendingRestoreFocus = true
                    pendingSilentReturn = true
                    tvFocusController?.captureCurrentAnchor()
                    VideoRouteNavigator.openHistory(
                        context = requireContext(),
                        historyVideo = item,
                        playQueue = com.mytvb.ui.activity.PlayerActivity.buildPlayQueue(
                            favoriteAdapter.getItemsSnapshot().map { it.toVideoModel() },
                            video
                        )
                    )
                }
            },
            onItemFocused = { position ->
                lastFocusedPosition = position
            },
            onItemFavoriteRemoved = { _ ->
                if (!isAdded) return@FavoriteHistoryAdapter
                if (favoriteAdapter.itemCount <= 1) {
                    parentFragmentManager.popBackStack()
                }
            },
            onItemFocusedWithView = { view, position ->
                tvFocusController?.onItemFocused(view, position)
                // 焦点导航下 RV 常不滚动（dy=0），onScrolled 收不到事件，必须在焦点
                // 落定时补查触底，否则要等焦点顶死最后一行才触发加载（体感"触底加载慢"）
                checkLoadMoreVideos()
            },
            onItemDpad = { view, keyCode, event ->
                tvFocusController?.handleKey(view, keyCode, event) == true
            },
            onItemsChanged = {
                tvFocusController?.onDataChanged(TvDataChangeReason.REMOVE_ITEM)
            }
        )
        val spanCount = resources.adaptiveSpanCount()
        binding.recyclerViewVideos.layoutManager = WrapContentGridLayoutManager(requireContext(), spanCount)
        binding.recyclerViewVideos.adapter = favoriteAdapter
        VideoRecyclerViewTuning.apply(binding.recyclerViewVideos, favoriteAdapter)
        if (binding.recyclerViewVideos.itemDecorationCount == 0) {
            binding.recyclerViewVideos.addItemDecoration(
                GridSpacingItemDecoration(spanCount, resources.getDimensionPixelSize(com.mytvb.R.dimen.px20), true)
            )
        }
        binding.recyclerViewVideos.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                checkLoadMoreVideos()
            }
        })
        installTvFocusController()
        swipeRefreshLayout = SwipeRefreshHelper.wrapRecyclerView(
            recyclerView = binding.recyclerViewVideos,
            onRefresh = ::refreshFavoriteVideos
        )

        binding.buttonBack.setOnClickListener {
            parentFragmentManager.popBackStack()
        }
        binding.buttonBack.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                lastFocusedPosition = RecyclerView.NO_POSITION
            }
        }
    }

    override fun initData() {
        loadFavoriteInfo()
        loadFavoriteVideos()
    }

    override fun onResume() {
        super.onResume()
        if (pendingRestoreFocus) {
            pendingRestoreFocus = false
            restoreFocus()
        }
        if (pendingSilentReturn && favoriteAdapter.itemCount > 0 && !isLoading) {
            // 从播放器返回：静默重拉第一页按 key 保序合并，只更新被看过卡片的进度
            pendingSilentReturn = false
            silentRefreshFavoriteProgress()
        }
    }

    /**
     * 静默刷新观看进度（从播放器返回时调用），对齐历史页 silentRefreshHistoryProgress：
     * 重拉第一页后按 itemKey 保序合并进现有列表——只覆盖匹配项的进度等字段，
     * 顺序不变、不增不删（不回顶不动焦点，Diff 只刷变化的卡片）。失败静默吞。
     */
    private fun silentRefreshFavoriteProgress() {
        if (folderId == 0L) return
        if (favoriteAdapter.itemCount == 0) return
        if (isLoading) return
        silentRefreshJob?.cancel()
        silentRefreshJob = lifecycleScope.launch {
            try {
                val t0 = System.currentTimeMillis()
                AppLog.d("FavDetail", "silentRefreshFavorite: request first page")
                val result = favoriteRepository.getFavoriteFolderDetail(folderId, 1, 20)
                // 期间正常加载已启动则丢弃，正常加载的结果更权威
                if (isLoading) {
                    AppLog.d("FavDetail", "silentRefreshFavorite: drop, normal load took over")
                    return@launch
                }
                val freshMedias = result.getOrNull()?.data?.medias.orEmpty()
                val progressSample = freshMedias.take(6).joinToString(",") {
                    "${it.bvid.takeLast(8)}:${it.progress}s/${it.duration}s"
                }
                AppLog.d("FavDetail", "silentRefreshFavorite: fresh progress sample=[$progressSample]")
                result.onSuccess { response ->
                    if (!response.isSuccess) {
                        AppLog.e("FavDetail", "silentRefreshFavorite: code=${response.code}")
                        return@onSuccess
                    }
                    val freshByKey = response.data?.medias.orEmpty()
                        .associateBy(::favoriteItemKey)
                    if (freshByKey.isEmpty()) {
                        AppLog.d("FavDetail", "silentRefreshFavorite: drop, empty first page")
                        return@onSuccess
                    }
                    val current = favoriteAdapter.getItemsSnapshot()
                    if (current.isEmpty()) return@onSuccess
                    val merged = current.map { freshByKey[favoriteItemKey(it)] ?: it }
                    if (merged == current) {
                        AppLog.d("FavDetail", "silentRefreshFavorite: no diff, items=${merged.size}, cost=${System.currentTimeMillis() - t0}ms")
                        return@onSuccess
                    }
                    val changed = merged.zip(current).count { (new, old) -> new != old }
                    AppLog.d("FavDetail", "silentRefreshFavorite: applied, items=${merged.size}, changed=$changed, cost=${System.currentTimeMillis() - t0}ms")
                    favoriteAdapter.setData(merged)
                    tvFocusController?.onDataChanged(TvDataChangeReason.REPLACE_PRESERVE_ANCHOR)
                }.onFailure { e ->
                    if (e is CancellationException) {
                        throw e
                    }
                    AppLog.e("FavDetail", "silentRefreshFavorite: failed, ${e.message}")
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                AppLog.e("FavDetail", "silentRefreshFavorite: failed, ${t.message}")
            }
        }
    }

    private fun favoriteItemKey(item: com.mytvb.model.video.HistoryVideoModel): String {
        return when {
            item.bvid.isNotBlank() -> "bvid:${item.bvid}"
            (item.history?.oid ?: 0L) > 0L -> "aid:${item.history?.oid}"
            else -> "title:${item.title}|cover:${item.cover}"
        }
    }

    override fun initObserver() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                appEventHub.events.collectLatest { event ->
                    if (event == AppEventHub.Event.UserSessionChanged && !isHidden && isVisible) {
                        currentPage = 1
                        hasMore = true
                        loadFavoriteInfo()
                        loadFavoriteVideos()
                    }
                }
            }
        }
    }

    private fun loadFavoriteInfo() {
        if (folderId == 0L) return

        lifecycleScope.launch {
            favoriteRepository.getFavoriteFolderInfo(folderId)
                .onSuccess { response ->
                    if (response.isSuccess && response.data != null) {
                        binding.tvTitle.text = response.data.title.ifEmpty { title }
                    }
                }
        }
    }

    /** 触底判定（滚动/焦点落定共用）：剩不足 5 个可见项即预取下一页。 */
    private fun checkLoadMoreVideos() {
        if (isLoading || !hasMore) return
        val layoutManager =
            binding.recyclerViewVideos.layoutManager as? WrapContentGridLayoutManager ?: return
        val lastVisibleItem = layoutManager.findLastVisibleItemPosition()
        if (lastVisibleItem >= layoutManager.itemCount - 5) {
            currentPage++
            loadFavoriteVideos()
        }
    }

    private fun loadFavoriteVideos() {
        if (folderId == 0L || !hasMore) return
        val restartFirstPage = currentPage <= 1
        if (isLoading && !restartFirstPage) {
            AppLog.d("FavoriteDetail", "skip load-more while loading, page=$currentPage")
            return
        }
        val requestId = ++videosRequestSerial
        activeVideosRequestId = requestId
        val requestStartMs = PagePerfLogger.now()
        if (restartFirstPage) {
            videosLoadJob?.cancel()
        }
        PagePerfLogger.markNow(
            "Favorite/detail",
            "request_start",
            "request=$requestId page=$currentPage hasContent=${favoriteAdapter.itemCount > 0}"
        )

        if (!sessionGateway.isLoggedIn()) {
            hasMore = false
            swipeRefreshLayout?.isRefreshing = false
            favoriteAdapter.setData(emptyList())
            tvFocusController?.onDataChanged(TvDataChangeReason.REPLACE_PRESERVE_ANCHOR)
            showEmpty(getString(R.string.need_sign_in))
            requestBackFocus()
            return
        }

        isLoading = true
        val hasExistingItems = favoriteAdapter.itemCount > 0
        if (currentPage == 1 && !hasExistingItems) {
            binding.progressBar.visibility = View.VISIBLE
        }

        val requestPage = currentPage
        videosLoadJob = lifecycleScope.launch {
            val result = try {
                favoriteRepository.getFavoriteFolderDetail(folderId, requestPage, 20)
            } catch (e: CancellationException) {
                throw e
            }

            if (!isActiveVideosRequest(requestId)) {
                AppLog.d("FavoriteDetail", "drop stale videos result request=$requestId page=$requestPage")
                return@launch
            }

            binding.progressBar.visibility = android.view.View.GONE
            swipeRefreshLayout?.isRefreshing = false
            finishVideosRequest(requestId)

            result.onSuccess { response ->
                PagePerfLogger.mark(
                    "Favorite/detail",
                    "data_collected",
                    requestStartMs,
                    "request=$requestId page=$requestPage success=${response.isSuccess}"
                )
                if (response.isSuccess) {
                    val detail = response.data
                    val medias = detail?.medias.orEmpty()
                    hasMore = detail?.hasMore == true
                    detail?.info?.title
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { binding.tvTitle.text = it }

                    if (requestPage == 1 && medias.isEmpty()) {
                        favoriteAdapter.setData(emptyList())
                        showEmpty(getString(R.string.favorite_folder_content_empty))
                    } else {
                        showListContent()
                        val filtered = medias.filter { !ContentFilter.isVideoBlocked(requireContext(), it.tagName, it.title, authorName = it.displayAuthorName) }
                        if (requestPage == 1) {
                            favoriteAdapter.setData(filtered)
                            tvFocusController?.onDataChanged(TvDataChangeReason.REPLACE_PRESERVE_ANCHOR)
                        } else if (filtered.isNotEmpty()) {
                            favoriteAdapter.addData(filtered)
                            tvFocusController?.onDataChanged(TvDataChangeReason.APPEND)
                        }
                        PagePerfLogger.mark(
                            "Favorite/detail",
                            "adapter_commit",
                            requestStartMs,
                            "request=$requestId page=$requestPage items=${favoriteAdapter.itemCount}"
                        )
                        if (!hasRequestedInitialFocus && requestPage == 1) {
                            hasRequestedInitialFocus = true
                            requestBackFocus()
                        } else if (lastFocusedPosition != RecyclerView.NO_POSITION) {
                            restoreFocus()
                        }
                        // 落地即续拉：本页条数少、焦点仍贴着列表尾部时立刻预取下一页
                        checkLoadMoreVideos()
                    }
                } else {
                    rollbackPage()
                    handleLoadError(response.errorMessage)
                }
            }.onFailure { e ->
                rollbackPage()
                handleLoadError(getString(R.string.load_failed_format, e.message))
            }
        }
    }

    private fun isActiveVideosRequest(requestId: Int): Boolean {
        return requestId == activeVideosRequestId
    }

    private fun finishVideosRequest(requestId: Int) {
        if (isActiveVideosRequest(requestId)) {
            isLoading = false
        }
    }

    private fun showListContent() {
        binding.tvEmpty.visibility = View.GONE
        binding.recyclerViewVideos.visibility = View.VISIBLE
    }

    private fun showEmpty(message: String) {
        binding.tvEmpty.visibility = View.VISIBLE
        binding.tvEmpty.text = message
        binding.recyclerViewVideos.visibility = View.GONE
    }

    private fun handleLoadError(message: String) {
        if (currentPage == 1 && favoriteAdapter.itemCount == 0) {
            showEmpty(message)
            return
        }
        showListContent()
        AppToast.show(requireContext(), message)
    }

    private fun rollbackPage() {
        if (currentPage > 1) {
            currentPage--
        }
    }

    private fun refreshFavoriteVideos() {
        currentPage = 1
        hasMore = true
        lastFocusedPosition = RecyclerView.NO_POSITION
        tvFocusController?.clearAnchorForUserRefresh()
        loadFavoriteVideos()
    }

    private fun restoreFocus() {
        if (!isAdded) return
        binding.recyclerViewVideos.post {
            if (!isAdded) return@post
            if (binding.recyclerViewVideos.isVisible && favoriteAdapter.itemCount > 0) {
                val controller = tvFocusController
                // 恢复优先级：
                // ① 点击进入播放时捕获的锚点（capturedAnchor）——独立于 lastFocusedPosition，
                //    不会被"返回按钮获得焦点"的监听器清空，是从播放器返回后恢复原视频最可靠的依据；
                // ② 退而求其次用 lastFocusedPosition。
                // 注意：返回时 buttonBack 的 setOnFocusChangeListener 会把 lastFocusedPosition
                // 重置为 NO_POSITION，故不能单独依赖 lastFocusedPosition。
                if (controller != null &&
                    (controller.hasCapturedAnchor() || lastFocusedPosition != RecyclerView.NO_POSITION)
                ) {
                    // 统一走带仲裁的恢复入口（强制拉回 + 真实落点轮询 + 数据落地等待，
                    // 且恢复窗口内 onDataChanged 不会与其互派重试链），手写 retry 轮询已删除。
                    controller.restoreFocusAfterReturn(onFailed = { restoreFallbackFocus() })
                } else {
                    requestBackFocus()
                }
            } else {
                requestBackFocus()
            }
        }
    }

    /**
     * 锚点恢复失败后的兜底：lastFocusedPosition 仍有效则聚焦该位置，
     * 仍失败（以 [TvListFocusController.hasFocusInList] 真实落点为准，
     * `requestFocusPosition` 返回值不可靠——内部失败也返回 true）再退返回键。
     */
    private fun restoreFallbackFocus() {
        if (!isAdded) return
        val target = lastFocusedPosition
            .takeIf { it != RecyclerView.NO_POSITION }
            ?.coerceIn(0, favoriteAdapter.itemCount - 1)
        if (target == null ||
            tvFocusController?.requestFocusPosition(target) != true ||
            tvFocusController?.hasFocusInList() != true
        ) {
            requestBackFocus()
        }
    }

    private fun requestBackFocus() {
        if (!isAdded) return
        binding.buttonBack.post {
            if (isAdded && !binding.buttonBack.hasFocus()) {
                binding.buttonBack.requestFocus()
            }
        }
    }

    private fun installTvFocusController() {
        tvFocusController?.release()
        tvFocusController = TvListFocusController(
            recyclerView = binding.recyclerViewVideos,
            adapter = favoriteAdapter,
            strategy = GridTvFocusStrategy.from(binding.recyclerViewVideos),
            canLoadMore = { hasMore },
            loadMore = {
                if (!isLoading && hasMore) {
                    currentPage++
                    loadFavoriteVideos()
                }
            }
        )
    }

    override fun onDestroyView() {
        tvFocusController?.release()
        tvFocusController = null
        swipeRefreshLayout = null
        super.onDestroyView()
    }
}
