package com.mytvb.feature.favorite

import android.view.LayoutInflater
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup

import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.os.bundleOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import com.mytvb.R
import com.mytvb.databinding.FragmentFavoriteBinding
import com.mytvb.event.AppEventHub
import com.mytvb.network.session.SessionStateRepository
import com.mytvb.repository.UserRepository
import com.mytvb.repository.FavoriteRepository
import com.mytvb.ui.adapter.FavoriteFolderAdapter
import com.mytvb.core.ui.base.BaseFragment
import com.mytvb.feature.me.MeFragment
import com.mytvb.feature.me.MeTabPage
import com.mytvb.core.ui.layout.WrapContentGridLayoutManager
import com.mytvb.core.ui.decoration.GridSpacingItemDecoration
import com.mytvb.core.ui.base.RecyclerViewFocusRestoreHelper
import com.mytvb.core.ui.base.adaptiveSpanCount
import com.mytvb.core.common.settings.AppSettingsDataStore
import com.mytvb.core.common.log.AppLog
import com.mytvb.core.common.log.PagePerfLogger
import com.mytvb.core.ui.focus.SpatialFocusNavigator
import com.mytvb.core.ui.focus.tv.GridTvFocusStrategy
import com.mytvb.core.ui.focus.tv.ListAdapterTvFocusBridge
import com.mytvb.core.ui.focus.tv.TvListFocusController
import com.mytvb.core.ui.focus.hasFocusInChildren
import com.mytvb.core.ui.focus.TabContentFocusHelper
import com.mytvb.core.ui.refresh.SwipeRefreshHelper
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import com.mytvb.core.ui.base.AppToast

class FavoriteFragment : BaseFragment<FragmentFavoriteBinding>(), MeTabPage {
    companion object {
        private const val ARG_EMBEDDED = "embedded"
        private const val FOLDERS_CACHE_TTL_MS = 10 * 60 * 1000L

        fun newInstance() = FavoriteFragment()

        fun newEmbeddedInstance() = FavoriteFragment().apply {
            arguments = bundleOf(ARG_EMBEDDED to true)
        }
    }

    private val appEventHub: AppEventHub by inject()
    private val sessionGateway: SessionStateRepository by inject()
    private val favoriteRepository: FavoriteRepository by inject()
    private val userRepository: UserRepository by inject()
    private val feedPrewarmer: com.mytvb.repository.PersonalFeedPrewarmer by inject()
    private lateinit var adapter: FavoriteFolderAdapter
    private var tvFocusController: TvListFocusController? = null
    private var swipeRefreshLayout: androidx.swiperefreshlayout.widget.SwipeRefreshLayout? = null
    private var embedded = false
    private var lastFocusedPosition = RecyclerView.NO_POSITION
    private var pendingRestoreFocus = false
    private var hasRequestedInitialFocus = false
    private var coverHydrationJob: Job? = null
    private var folderLoadJob: Job? = null
    private var isLoadingFolders = false
    private var foldersLoadedAtMs = 0L
    private var folderRequestSerial = 0
    private var activeFolderRequestId = 0
    private val appSettings: AppSettingsDataStore by inject()

    override fun initArguments() {
        embedded = arguments?.getBoolean(ARG_EMBEDDED, false) == true
    }

    override fun getViewBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentFavoriteBinding {
        return FragmentFavoriteBinding.inflate(inflater, container, false)
    }

    override fun initView() {
        adapter = FavoriteFolderAdapter(
            onItemClick = { _, item ->
                lastFocusedPosition = adapter.getFocusedPosition()
                pendingRestoreFocus = true
                tvFocusController?.captureCurrentAnchor()
                openInHostContainer(FavoriteDetailFragment.newInstance(item.id, item.title))
            },
            onItemFocused = { position ->
                lastFocusedPosition = position
            },
            onTopEdgeUp = ::focusTopTab,
            onLeftEdge = ::switchToPrevMeTab,
            onRightEdge = ::switchToNextMeTab
        )

        binding.buttonBack.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                lastFocusedPosition = RecyclerView.NO_POSITION
            }
        }

        val spanCount = resources.adaptiveSpanCount()
        binding.recyclerViewFavorite.layoutManager = WrapContentGridLayoutManager(requireContext(), spanCount)
        binding.recyclerViewFavorite.adapter = adapter
        binding.recyclerViewFavorite.setHasFixedSize(true)
        if (binding.recyclerViewFavorite.itemDecorationCount == 0) {
            binding.recyclerViewFavorite.addItemDecoration(
                GridSpacingItemDecoration(
                    spanCount,
                    resources.getDimensionPixelSize(R.dimen.px20),
                    true
                )
            )
        }

        binding.buttonBack.setOnClickListener {
            parentFragmentManager.popBackStack()
        }
        binding.tvEmpty.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                focusTopTab()
            } else {
                false
            }
        }

        if (embedded) {
            binding.buttonBack.visibility = View.GONE
            binding.tvTitle.visibility = View.GONE
            (binding.recyclerViewFavorite.layoutParams as? ConstraintLayout.LayoutParams)?.let { params ->
                params.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                params.topToBottom = ConstraintLayout.LayoutParams.UNSET
                binding.recyclerViewFavorite.layoutParams = params
            }
        }
        swipeRefreshLayout = SwipeRefreshHelper.wrapRecyclerView(binding.recyclerViewFavorite, onRefresh = {
            refresh()
        }) {
            post {
                val topOffset = if (embedded) {
                    resources.getDimensionPixelSize(R.dimen.px20)
                } else {
                    binding.buttonBack.bottom + resources.getDimensionPixelSize(R.dimen.px20)
                }
                val endOffset = topOffset + resources.getDimensionPixelSize(R.dimen.px120)
                setProgressViewOffset(false, topOffset, endOffset)
            }
        }
        installTvFocusController()
    }

    /**
     * 只接「锚点捕获 + 返回恢复」的轻量 controller：D-pad 导航、数据变化停泊
     * 均不接线（本页按键行为保持框架默认），FolderAdapter 经
     * [ListAdapterTvFocusBridge] 提供稳定 key（folder id）。
     */
    private fun installTvFocusController() {
        tvFocusController?.release()
        tvFocusController = TvListFocusController(
            recyclerView = binding.recyclerViewFavorite,
            adapter = ListAdapterTvFocusBridge(adapter) { it.id.toString() },
            strategy = GridTvFocusStrategy.from(binding.recyclerViewFavorite),
            canLoadMore = { false },
            loadMore = {}
        )
    }

    override fun onDestroyView() {
        tvFocusController?.release()
        tvFocusController = null
        super.onDestroyView()
    }

    override fun initData() {
        // 不在 initData 里直接 loadFavoriteFolders()，等 onTabSelected() 触发首次加载。
    }

    override fun onResume() {
        super.onResume()
        if (pendingRestoreFocus) {
            pendingRestoreFocus = false
            restoreFocus()
        }
    }

    override fun initObserver() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                appEventHub.events.collectLatest { event ->
                    if (event == AppEventHub.Event.UserSessionChanged && !isHidden && isVisible) {
                        loadFavoriteFolders()
                    }
                }
            }
        }
    }

    private fun loadFavoriteFolders() {
        val requestId = ++folderRequestSerial
        activeFolderRequestId = requestId
        val requestStartMs = PagePerfLogger.now()
        foldersLoadedAtMs = System.currentTimeMillis()
        folderLoadJob?.cancel()
        coverHydrationJob?.cancel()
        PagePerfLogger.markNow(
            "Me/favorite",
            "request_start",
            "request=$requestId hasContent=${adapter.itemCount > 0}"
        )
        if (!sessionGateway.isLoggedIn()) {
            binding.progressBar.visibility = View.GONE
            binding.tvEmpty.visibility = View.VISIBLE
            binding.tvEmpty.text = getString(R.string.need_sign_in)
            binding.recyclerViewFavorite.visibility = View.GONE
            requestFallbackFocus()
            isLoadingFolders = false
            return
        }

        binding.progressBar.visibility = if (adapter.itemCount > 0) View.GONE else View.VISIBLE
        isLoadingFolders = true

        folderLoadJob = viewLifecycleOwner.lifecycleScope.launch {
            val mid = try {
                userRepository.resolveCurrentUserMid().getOrNull()
            } catch (e: CancellationException) {
                throw e
            }
            if (!isActiveFolderRequest(requestId)) {
                AppLog.d("MeDebug", "[favorite] drop stale mid result request=$requestId")
                return@launch
            }
            if (mid == null || mid <= 0L) {
                finishFolderRequest(requestId)
                if (!isAdded || view == null) return@launch
                binding.progressBar.visibility = View.GONE
                binding.tvEmpty.visibility = View.VISIBLE
                binding.tvEmpty.text = getString(R.string.need_sign_in)
                binding.recyclerViewFavorite.visibility = View.GONE
                requestFallbackFocus()
                return@launch
            }

            val result = try {
                feedPrewarmer.takeFolders()
                    ?.let { Result.success(it) }
                    ?: favoriteRepository.getFavoriteFolders(mid)
            } catch (e: CancellationException) {
                throw e
            }
            if (!isActiveFolderRequest(requestId)) {
                AppLog.d("MeDebug", "[favorite] drop stale folder result request=$requestId")
                return@launch
            }
            finishFolderRequest(requestId)
            if (!isAdded || view == null) return@launch
            binding.progressBar.visibility = View.GONE
            swipeRefreshLayout?.isRefreshing = false

            result.onSuccess { response ->
                PagePerfLogger.mark(
                    "Me/favorite",
                    "data_collected",
                    requestStartMs,
                    "request=$requestId success=${response.isSuccess}"
                )
                if (response.isSuccess) {
                    val folders = response.data?.list.orEmpty().map(::applySavedCover).toList()
                    if (folders.isEmpty()) {
                        binding.tvEmpty.visibility = View.VISIBLE
                        binding.tvEmpty.text = getString(R.string.favorite_folder_empty)
                        binding.recyclerViewFavorite.visibility = View.GONE
                        requestFallbackFocus()
                    } else {
                        binding.tvEmpty.visibility = View.GONE
                        binding.recyclerViewFavorite.visibility = View.VISIBLE
                        adapter.setData(folders)
                        PagePerfLogger.mark(
                            "Me/favorite",
                            "adapter_commit",
                            requestStartMs,
                            "request=$requestId items=${folders.size}"
                        )
                        hydrateMissingFolderCovers(folders)
                        AppLog.d("MeDebug", "[favorite] loadFolders done: folders=${folders.size}, hasRequestedInitialFocus=$hasRequestedInitialFocus, pendingRestore=$pendingRestoreFocus, lastFocusedPos=$lastFocusedPosition")
                        if (!embedded && !hasRequestedInitialFocus) {
                            hasRequestedInitialFocus = true
                            requestBackFocus()
                        } else if (pendingRestoreFocus || lastFocusedPosition != RecyclerView.NO_POSITION) {
                            restoreFocus()
                        }
                    }
                } else {
                    binding.tvEmpty.visibility = View.VISIBLE
                    binding.tvEmpty.text = response.errorMessage
                    binding.recyclerViewFavorite.visibility = View.GONE
                    requestFallbackFocus()
                    AppToast.show(requireContext(), response.errorMessage)
                }
            }.onFailure { e ->
                binding.tvEmpty.visibility = View.VISIBLE
                binding.tvEmpty.text = e.message ?: getString(R.string.net_error)
                binding.recyclerViewFavorite.visibility = View.GONE
                requestFallbackFocus()
                AppToast.show(requireContext(), getString(R.string.load_failed_format, e.message))
            }
        }
    }

    private fun isActiveFolderRequest(requestId: Int): Boolean {
        return requestId == activeFolderRequestId
    }

    private fun finishFolderRequest(requestId: Int) {
        if (isActiveFolderRequest(requestId)) {
            isLoadingFolders = false
        }
    }

    private fun hydrateMissingFolderCovers(folders: List<com.mytvb.model.favorite.FavoriteFolderModel>) {
        val pendingFolders = folders.filter { folder ->
            folder.id > 0L && folder.mediaCount > 0 && folder.cover.isBlank()
        }
        if (pendingFolders.isEmpty()) {
            return
        }
        coverHydrationJob = lifecycleScope.launch {
            pendingFolders.forEach { folder ->
                if (!isActive) {
                    return@launch
                }
                favoriteRepository.getFavoriteFolderDetail(folder.id, 1, 1)
                    .onSuccess { response ->
                        if (!response.isSuccess) {
                            return@onSuccess
                        }
                        val detail = response.data
                        val latestMedia = detail?.medias
                            ?.maxByOrNull { maxOf(it.favTime, it.viewAt) }
                        val coverUrl = detail?.info?.cover?.takeIf { it.isNotBlank() }
                            ?: latestMedia?.cover?.takeIf { it.isNotBlank() }
                            ?: latestMedia?.covers?.firstOrNull()?.takeIf { it.isNotBlank() }
                        if (!coverUrl.isNullOrBlank()) {
                            saveFolderCover(folder.id, coverUrl)
                            adapter.updateCover(folder.id, coverUrl)
                        }
                    }
            }
        }
    }

    override fun scrollToTop() {
        binding.recyclerViewFavorite.smoothScrollToPosition(0)
    }

    override fun refresh() {
        swipeRefreshLayout?.isRefreshing = true
        loadFavoriteFolders()
    }

    override fun onTabSelected() {
        if (!isAdded || view == null) {
            return
        }
        com.mytvb.core.common.log.AppLog.d("MeDebug", "[favorite] onTabSelected: lastFocusedPos=$lastFocusedPosition, adapterCount=${adapter.itemCount}")
        // 内容仍新鲜（未过 TTL）：保留滚动位置/焦点，不再回顶重拉
        // （切 tab 保进度；重复点击 tab / 菜单键走 onTabReselected 仍是显式刷新回顶）
        if (adapter.itemCount > 0 && System.currentTimeMillis() - foldersLoadedAtMs < FOLDERS_CACHE_TTL_MS) {
            com.mytvb.core.common.log.AppLog.d("MeDebug", "[favorite] onTabSelected: keep state (fresh within TTL)")
            return
        }
        lastFocusedPosition = RecyclerView.NO_POSITION
        binding.recyclerViewFavorite.scrollToPosition(0)
        loadFavoriteFolders()
    }

    override fun onTabReselected() {
        if (!isAdded || view == null) {
            return
        }
        scrollToTop()
        loadFavoriteFolders()
    }

    override fun onHostEvent(event: MeTabPage.HostEvent): Boolean {
        when (event) {
            MeTabPage.HostEvent.SELECT_TAB4 -> onTabSelected()
            MeTabPage.HostEvent.CLICK_TAB4 -> onTabReselected()
            MeTabPage.HostEvent.BACK_PRESSED -> Unit
            MeTabPage.HostEvent.KEY_MENU_PRESS -> loadFavoriteFolders()
        }
        return true
    }

    override fun focusPrimaryContent(): Boolean {
        if (!isAdded || view == null) {
            return false
        }
        if (binding.recyclerViewFavorite.visibility == View.VISIBLE && adapter.itemCount > 0) {
            val result = TabContentFocusHelper.requestRecyclerPrimaryFocus(
                recyclerView = binding.recyclerViewFavorite,
                itemCount = adapter.itemCount
            )
            return result.resolved
        }
        if (binding.tvEmpty.visibility == View.VISIBLE) {
            return requestEmptyStateFocus()
        }
        return false
    }

    override fun focusPrimaryContent(anchorView: View?, preferSpatialEntry: Boolean): Boolean {
        if (preferSpatialEntry) {
            if (binding.recyclerViewFavorite.visibility == View.VISIBLE) {
                val handled = SpatialFocusNavigator.requestBestDescendant(
                    anchorView = anchorView,
                    root = binding.recyclerViewFavorite,
                    direction = View.FOCUS_RIGHT,
                    fallback = null
                )
                if (handled) {
                    return true
                }
            }
            if (binding.tvEmpty.visibility == View.VISIBLE) {
                val handled = SpatialFocusNavigator.requestBestCandidate(
                    anchorView = anchorView,
                    candidates = listOf(binding.tvEmpty),
                    direction = View.FOCUS_RIGHT,
                    fallback = null
                )
                if (handled) {
                    return true
                }
            }
        }
        return focusPrimaryContent()
    }

    private fun restoreFocus() {
        if (!isAdded || embedded && !pendingRestoreFocus && !binding.recyclerViewFavorite.isShown) {
            return
        }
        binding.recyclerViewFavorite.post {
            if (!isAdded || binding.recyclerViewFavorite.visibility != View.VISIBLE || adapter.itemCount == 0) {
                if (!embedded) {
                    requestBackFocus()
                }
                if (binding.tvEmpty.visibility == View.VISIBLE) {
                    requestEmptyStateFocus()
                }
                return@post
            }
            val controller = tvFocusController
            if (controller != null && controller.hasCapturedAnchor()) {
                // 统一走带仲裁的返回恢复：锚点即点击时的文件夹卡片（稳定 key 经
                // ListAdapterTvFocusBridge 重解析），转场轮询/数据落地等待内置，
                // 手写 retry 轮询已删除。失败走 lastFocusedPosition 兜底。
                controller.restoreFocusAfterReturn(onFailed = { restoreFallbackFocus() })
            } else {
                restoreFallbackFocus()
            }
        }
    }

    /** 锚点恢复失败后的兜底：lastFocusedPosition 单次聚焦（返回值不可靠，以真实落点为准），再退空态/返回键。 */
    private fun restoreFallbackFocus() {
        if (!isAdded) return
        val targetPosition = lastFocusedPosition
            .takeIf { it != RecyclerView.NO_POSITION }
            ?.coerceIn(0, adapter.itemCount - 1)
            ?: 0
        RecyclerViewFocusRestoreHelper.requestFocusAtPosition(
            recyclerView = binding.recyclerViewFavorite,
            position = targetPosition
        )
        if (!binding.recyclerViewFavorite.hasFocusInChildren()) {
            requestFallbackFocus()
        }
    }

    private fun requestBackFocus() {
        if (!isAdded || embedded) {
            return
        }
        binding.buttonBack.post {
            if (isAdded && !binding.buttonBack.hasFocus()) {
                binding.buttonBack.requestFocus()
            }
        }
    }

    private fun requestFallbackFocus() {
        if (embedded && binding.tvEmpty.visibility == View.VISIBLE) {
            requestEmptyStateFocus()
            return
        }
        requestBackFocus()
    }

    private fun focusTopTab(): Boolean {
        return (parentFragment as? MeFragment)?.focusCurrentTab() == true
    }

    private fun switchToPrevMeTab(): Boolean {
        return (parentFragment as? MeFragment)?.switchAdjacentTabFromContentEdge(-1) == true
    }

    private fun switchToNextMeTab(): Boolean {
        return (parentFragment as? MeFragment)?.switchAdjacentTabFromContentEdge(1) == true
    }

    private fun requestEmptyStateFocus(): Boolean {
        return binding.tvEmpty.requestFocus()
    }

    private fun applySavedCover(folder: com.mytvb.model.favorite.FavoriteFolderModel): com.mytvb.model.favorite.FavoriteFolderModel {
        if (folder.id <= 0L || folder.displayImageUrl.isNotBlank()) {
            return folder
        }
        val cachedCover = appSettings.getCachedString("fav${folder.id}").orEmpty()
        return if (cachedCover.isBlank()) {
            folder
        } else {
            folder.copy(imageUrl = cachedCover)
        }
    }

    private fun saveFolderCover(folderId: Long, coverUrl: String) {
        if (folderId <= 0L || coverUrl.isBlank()) {
            return
        }
        appSettings.putStringAsync("fav$folderId", coverUrl)
    }
}
