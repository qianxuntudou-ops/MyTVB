package com.tutu.myblbl.feature.home

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tutu.myblbl.core.common.content.ContentFilter
import com.tutu.myblbl.core.common.log.AppLog
import com.tutu.myblbl.model.video.VideoModel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class RecommendViewModel(
    private val repository: RecommendFeedRepository,
    context: Context
) : ViewModel(), VideoFeedViewModel {

    companion object {
        private const val TAG = "RecommendVM"
        private const val FIRST_PAGE_SIZE = 12
        private const val NEXT_PAGE_SIZE = 24
        private const val FIRST_PAGE_PRELOAD_GRACE_MS = 180L
    }

    private val appContext = context.applicationContext
    private val _uiState = MutableStateFlow(FeedUiState<VideoModel>())
    override val uiState: StateFlow<FeedUiState<VideoModel>> = _uiState.asStateFlow()

    private val freshIndexTracker = RecommendFreshIndexTracker()
    private var currentPage = 0
    private var hasLoadedInitial = false
    private val seenBvids = mutableSetOf<String>()

    override fun loadInitial() {
        if (hasLoadedInitial) return
        hasLoadedInitial = true
        AppLog.i(TAG, "STARTUP T5 viewModel.loadInitial")
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            loadPage(page = 1, replace = true, fromInitial = true)
        }
    }

    override fun refresh() {
        viewModelScope.launch {
            loadPage(page = 1, replace = true, fromRefresh = true)
        }
    }

    override fun loadMore() {
        val state = _uiState.value
        if (state.loadingInitial || state.refreshing || state.appending || !state.hasMore) {
            return
        }
        val nextPage = (currentPage + 1).coerceAtLeast(1)
        viewModelScope.launch {
            loadPage(page = nextPage, replace = false)
        }
    }

    override fun consumeListChange() {
        val state = _uiState.value
        if (state.listChange != FeedListChange.NONE) {
            _uiState.value = state.copy(listChange = FeedListChange.NONE)
        }
    }

    private suspend fun loadPage(
        page: Int,
        replace: Boolean,
        fromInitial: Boolean = false,
        fromRefresh: Boolean = false
    ) {
        if (page == 1 && replace && fromInitial) {
            val peekStart = SystemClock.elapsedRealtime()
            var preloaded = repository.peekFirstPage()
            if (preloaded == null) {
                preloaded = repository.awaitFirstPage(timeoutMs = FIRST_PAGE_PRELOAD_GRACE_MS)
            }
            AppLog.i(TAG, "STARTUP T6 preload ${if (preloaded != null) "hit" else "miss"} grace=${FIRST_PAGE_PRELOAD_GRACE_MS}ms elapsed=${SystemClock.elapsedRealtime() - peekStart}ms items=${preloaded?.items?.size ?: 0}")
            if (preloaded != null) {
                seenBvids.clear()
                val filterStart = SystemClock.elapsedRealtime()
                val filteredItems = preloaded.items.filterForInitialPreload()
                AppLog.i(TAG, "STARTUP preload filterForInitial=${SystemClock.elapsedRealtime() - filterStart}ms")
                filteredItems.mapNotNullTo(seenBvids) { it.bvid.takeIf(String::isNotBlank) }
                freshIndexTracker.markFirstPageLoaded()
                currentPage = 1
                _uiState.value = FeedUiState(
                    items = filteredItems,
                    source = FeedSource.NETWORK,
                    listChange = FeedListChange.REPLACE,
                    hasMore = preloaded.hasMore
                )
                if (filteredItems.isNotEmpty()) {
                    repository.writeCache(repository.trimCacheItems(filteredItems))
                }
                return
            }
            AppLog.i(TAG, "STARTUP T6b preload miss -> start direct first-page request")
        }

        val current = _uiState.value
        _uiState.value = current.copy(
            loadingInitial = fromInitial,
            refreshing = fromRefresh,
            appending = !replace,
            errorMessage = null,
            listChange = FeedListChange.NONE
        )

        val freshIdx = freshIndexTracker.resolve(page)
        val pageSize = if (page == 1) FIRST_PAGE_SIZE else NEXT_PAGE_SIZE
        repository.loadNetworkPage(
            page = page,
            pageSize = pageSize,
            freshIdx = freshIdx
        ).onSuccess { pageResult ->
            val filterStart = SystemClock.elapsedRealtime()
            AppLog.i(TAG, "STARTUP T7 network page=$page ready items=${pageResult.items.size}")
            val filteredItems = pageResult.items.filterForDisplay()
            if (replace) {
                seenBvids.clear()
            }
            val dedupedItems = filteredItems.filter { it.bvid.isBlank() || it.bvid !in seenBvids }
            dedupedItems.mapNotNullTo(seenBvids) { it.bvid.takeIf(String::isNotBlank) }
            if (page == 1) {
                freshIndexTracker.markFirstPageLoaded()
            }
            val mergedItems = if (replace) {
                dedupedItems
            } else {
                current.items + dedupedItems
            }
            currentPage = page
            _uiState.value = FeedUiState(
                items = mergedItems,
                source = FeedSource.NETWORK,
                listChange = if (replace) FeedListChange.REPLACE else FeedListChange.APPEND,
                hasMore = pageResult.hasMore
            )
            AppLog.i(TAG, "STARTUP network page=$page filterDedup=${SystemClock.elapsedRealtime() - filterStart}ms final=${mergedItems.size}")
            if (mergedItems.isNotEmpty()) {
                repository.writeCache(repository.trimCacheItems(mergedItems))
            }
        }.onFailure { throwable ->
            _uiState.value = current.copy(
                loadingInitial = false,
                refreshing = false,
                appending = false,
                errorMessage = throwable.message ?: "推荐加载失败",
                listChange = FeedListChange.NONE
            )
        }
    }

    private suspend fun List<VideoModel>.filterForDisplay(): List<VideoModel> {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            ContentFilter.filterVideos(appContext, this@filterForDisplay)
        }
    }

    private fun List<VideoModel>.filterForInitialPreload(): List<VideoModel> {
        return ContentFilter.filterVideos(appContext, this)
    }
}
