package com.tutu.myblbl.feature.dynamic

import android.util.LruCache
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tutu.myblbl.model.user.FollowingModel
import com.tutu.myblbl.model.video.VideoModel
import com.tutu.myblbl.repository.UserRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import android.os.SystemClock
import com.tutu.myblbl.core.common.log.AppLog

class DynamicViewModel(
    private val userRepository: UserRepository
) : ViewModel() {
    private var lastLoadedAt = 0L

    enum class ScreenState {
        Content,
        NotLoggedIn,
        Error
    }

    enum class DynamicStatus {
        Idle,
        Content,
        NotLoggedIn,
        NoFollowing,
        Empty,
        Error
    }

    companion object {
        private const val TAG = "DynamicVM"
        private const val ALL_DYNAMIC_ID = "0"
        private const val FOLLOWING_PAGE_SIZE = 50
    }

    private val _followingList = MutableStateFlow<List<FollowingModel>>(emptyList())
    val followingList: StateFlow<List<FollowingModel>> = _followingList.asStateFlow()

    private val _videos = MutableStateFlow<List<VideoModel>>(emptyList())
    val videos: StateFlow<List<VideoModel>> = _videos.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _hasMoreVideos = MutableStateFlow(true)
    val hasMoreVideos: StateFlow<Boolean> = _hasMoreVideos.asStateFlow()

    private val _status = MutableStateFlow(DynamicStatus.Idle)
    val status: StateFlow<DynamicStatus> = _status.asStateFlow()

    private val _screenState = MutableStateFlow(ScreenState.Content)
    val screenState: StateFlow<ScreenState> = _screenState.asStateFlow()

    private val _loadedPage = MutableStateFlow(0)
    val loadedPage: StateFlow<Int> = _loadedPage.asStateFlow()

    private var currentUpId: String = ""
    private var currentAllDynamicOffset: Long? = null
    private var currentPage = 0
    private var currentUserMid: Long = 0L
    private var hasFollowingUsers = false
    private var currentVideoItems: List<VideoModel> = emptyList()
    private var followingPage = 0
    private var followingTotal = 0
    private var followingHasMore = false
    private var followingLoading = false
    private val loadedFollowingMids = linkedSetOf<Long>()

    private data class CachedUpVideos(
        val videos: List<VideoModel>,
        val page: Int,
        val hasMore: Boolean
    )

    private val videoCache = LruCache<String, CachedUpVideos>(15)
    private var loadJob: Job? = null
    private var preloadJob: Job? = null
    private var followingLoadJob: Job? = null
    private var lastPageSize = 20
    private var loadGeneration = 0

    fun loadFollowingList() {
        if (followingLoadJob?.isActive == true) return
        if (!userRepository.isLoggedIn()) {
            _loading.value = false
            _error.value = null
            _followingList.value = emptyList()
            _videos.value = emptyList()
            _hasMoreVideos.value = false
            currentVideoItems = emptyList()
            currentUpId = ""
            currentAllDynamicOffset = null
            currentPage = 0
            currentUserMid = 0L
            hasFollowingUsers = false
            followingPage = 0
            followingTotal = 0
            followingHasMore = false
            followingLoading = false
            loadedFollowingMids.clear()
            _status.value = DynamicStatus.NotLoggedIn
            _screenState.value = ScreenState.NotLoggedIn
            return
        }

        followingLoadJob = viewModelScope.launch {
            val startMs = SystemClock.elapsedRealtime()
            AppLog.i(TAG, "DYN D0 loadFollowingList start")
            _loading.value = true
            _error.value = null
            _status.value = DynamicStatus.Idle
            _screenState.value = ScreenState.Content
            currentUserMid = 0L
            followingPage = 0
            followingTotal = 0
            followingHasMore = false
            followingLoading = false
            loadedFollowingMids.clear()

            val defaultItems = listOf(
                FollowingModel(
                    mid = 0,
                    uname = "全部动态"
                )
            )

            val midStartMs = SystemClock.elapsedRealtime()
            userRepository.resolveCurrentUserMid()
                .onSuccess { mid ->
                    currentUserMid = mid
                    AppLog.i(TAG, "DYN D1 resolveMid end elapsed=${SystemClock.elapsedRealtime() - midStartMs}ms mid=$mid")
                    launch { loadFollowingPage(mid, 1, defaultItems) }
                    launch { selectUp(ALL_DYNAMIC_ID, lastPageSize) }
                }
                .onFailure { exception ->
                    AppLog.w(TAG, "DYN D1 resolveMid failed elapsed=${SystemClock.elapsedRealtime() - midStartMs}ms err=${exception.message}")
                    _loading.value = false
                    _followingList.value = defaultItems
                    _videos.value = emptyList()
                    currentVideoItems = emptyList()
                    _error.value = exception.message
                    _status.value = DynamicStatus.Error
                    _screenState.value = ScreenState.Error
                }
        }
    }

    private suspend fun loadFollowingPage(
        mid: Long,
        page: Int,
        defaultItems: List<FollowingModel>
    ) {
        val startMs = SystemClock.elapsedRealtime()
        AppLog.i(TAG, "DYN D2 getFollowing start mid=$mid page=$page")
        userRepository.getFollowing(mid, page, FOLLOWING_PAGE_SIZE)
            .onSuccess { followingResponse ->
                AppLog.i(TAG, "DYN D3 getFollowing end elapsed=${SystemClock.elapsedRealtime() - startMs}ms success=${followingResponse.isSuccess}")
                _loading.value = false
                if (followingResponse.isSuccess) {
                    val wrapper = followingResponse.data
                    val list = wrapper?.list.orEmpty()
                    followingTotal = wrapper?.total ?: 0
                    hasFollowingUsers = followingTotal > 0 || list.isNotEmpty()
                    applyFollowingItems(
                        defaultItems = defaultItems,
                        items = list,
                        page = if (list.isNotEmpty()) page else 0,
                        total = followingTotal
                    )
                    lastLoadedAt = System.currentTimeMillis()
                    _status.value = DynamicStatus.Idle
                    _error.value = null
                    _screenState.value = ScreenState.Content
                } else {
                    _followingList.value = defaultItems
                    _error.value = followingResponse.errorMessage
                    _videos.value = emptyList()
                    currentVideoItems = emptyList()
                    _status.value = DynamicStatus.Error
                    _screenState.value = ScreenState.Error
                }
            }
            .onFailure { exception ->
                AppLog.w(TAG, "DYN D3 getFollowing failed elapsed=${SystemClock.elapsedRealtime() - startMs}ms err=${exception.message}")
                _loading.value = false
                    _followingList.value = defaultItems
                    hasFollowingUsers = false
                    followingTotal = 0
                    _error.value = exception.message
                    _videos.value = emptyList()
                    currentVideoItems = emptyList()
                _status.value = DynamicStatus.Error
                _screenState.value = ScreenState.Error
            }
    }

    fun loadMoreFollowingIfNeeded() {
        if (currentUserMid <= 0L || !userRepository.isLoggedIn() || followingLoading || !followingHasMore) {
            return
        }

        val nextPage = followingPage + 1
        followingLoading = true

        viewModelScope.launch {
            userRepository.getFollowing(currentUserMid, nextPage, FOLLOWING_PAGE_SIZE)
                .onSuccess { response ->
                    if (response.isSuccess) {
                        val wrapper = response.data
                        val pageItems = wrapper?.list.orEmpty()
                        followingTotal = wrapper?.total ?: followingTotal
                        followingPage = nextPage
                        if (pageItems.isNotEmpty()) {
                            val appendedItems = pageItems.filter { loadedFollowingMids.add(it.mid) }
                            if (appendedItems.isNotEmpty()) {
                                _followingList.value = _followingList.value + appendedItems
                            }
                        }
                        followingHasMore = shouldLoadMoreFollowing()
                    } else {
                        followingHasMore = false
                    }
                    followingLoading = false
                }
                .onFailure {
                    followingLoading = false
                }
        }
    }

    fun selectUp(upId: String, pageSize: Int, forceRefresh: Boolean = false) {
        if (upId.isBlank()) {
            return
        }
        if (!forceRefresh && upId == currentUpId && (currentPage > 0 || loadJob?.isActive == true)) {
            return
        }

        lastPageSize = pageSize
        loadGeneration++
        loadJob?.cancel()
        preloadJob?.cancel()
        currentUpId = upId
        currentPage = 0
        currentAllDynamicOffset = null
        _loadedPage.value = 0
        _hasMoreVideos.value = true
        _screenState.value = ScreenState.Content

        if (!forceRefresh) {
            val cached = videoCache.get(upId)
            if (cached != null) {
                AppLog.i(TAG, "DYN D4 selectUp cache hit upId=$upId items=${cached.videos.size}")
                loadJob = null
                currentVideoItems = cached.videos
                currentPage = cached.page
                _hasMoreVideos.value = cached.hasMore
                _loadedPage.value = cached.page
                _status.value = resolveStatus(upId, cached.videos)
                _videos.value = cached.videos
                _loading.value = false
                return
            }
        }

        currentVideoItems = emptyList()
        _videos.value = emptyList()
        loadNextPage(pageSize)
    }

    fun loadNextPage(pageSize: Int) {
        if (currentUpId.isBlank() || !_hasMoreVideos.value) {
            return
        }
        if (loadJob?.isActive == true) {
            return
        }

        val nextPage = currentPage + 1
        val gen = loadGeneration
        loadJob = viewModelScope.launch {
            val startMs = SystemClock.elapsedRealtime()
            AppLog.i(TAG, "DYN D4 selectUp network start upId=$currentUpId page=$nextPage")
            _loading.value = true
            _error.value = null
            if (currentPage == 0) {
                _status.value = DynamicStatus.Idle
                _screenState.value = ScreenState.Content
            }

            val isAllDynamic = currentUpId == ALL_DYNAMIC_ID

            try {
                if (isAllDynamic) {
                    userRepository.getAllDynamic(
                        page = nextPage,
                        offset = if (nextPage > 1) currentAllDynamicOffset else null
                    ).onSuccess { response ->
                        val items = response.data?.items.orEmpty()
                        AppLog.i(TAG, "DYN D5 getAllDynamic end elapsed=${SystemClock.elapsedRealtime() - startMs}ms items=${items.size} hasMore=${response.data?.hasMore}")

                        if (response.isSuccess) {
                            currentVideoItems = if (nextPage == 1) {
                                items
                            } else {
                                currentVideoItems + items
                            }
                            lastLoadedAt = System.currentTimeMillis()
                            _hasMoreVideos.value = response.data?.hasMore == true
                            currentAllDynamicOffset = response.data?.offset
                            currentPage = nextPage
                            _loadedPage.value = nextPage
                            _videos.value = items
                            _status.value = resolveStatus(currentUpId, currentVideoItems)
                            _screenState.value = ScreenState.Content
                            if (nextPage == 1) {
                                videoCache.put(currentUpId, CachedUpVideos(items, 1, response.data?.hasMore == true))
                                schedulePreload()
                            }
                        } else {
                            if (nextPage == 1) {
                                currentVideoItems = emptyList()
                                _videos.value = emptyList()
                            }
                            _hasMoreVideos.value = false
                            _error.value = response.errorMessage
                            _status.value = resolveStatus(currentUpId, currentVideoItems)
                        }
                    }.onFailure { exception ->
                        if (nextPage == 1) {
                            currentVideoItems = emptyList()
                            _videos.value = emptyList()
                        }
                        _hasMoreVideos.value = false
                        _error.value = exception.message
                        _status.value = resolveStatus(currentUpId, currentVideoItems)
                    }
                    return@launch
                }

                userRepository.getUserDynamic(currentUpId.toLongOrNull() ?: 0L, nextPage, pageSize)
                    .onSuccess { response ->
                        val items = response.data?.archives.orEmpty()
                        AppLog.i(TAG, "DYN D5 getUserDynamic end elapsed=${SystemClock.elapsedRealtime() - startMs}ms items=${items.size} hasMore=${response.data?.hasMore}")

                        if (response.isSuccess) {
                            currentVideoItems = if (nextPage == 1) {
                                items
                            } else {
                                currentVideoItems + items
                            }
                            lastLoadedAt = System.currentTimeMillis()
                            _hasMoreVideos.value = response.data?.hasMore == true
                            currentPage = nextPage
                            _loadedPage.value = nextPage
                            _videos.value = items
                            _status.value = resolveStatus(currentUpId, currentVideoItems)
                            _screenState.value = ScreenState.Content
                            if (nextPage == 1) {
                                videoCache.put(currentUpId, CachedUpVideos(items, 1, response.data?.hasMore == true))
                                schedulePreload()
                            }
                        } else {
                            if (nextPage == 1) {
                                currentVideoItems = emptyList()
                                _videos.value = emptyList()
                            }
                            _hasMoreVideos.value = false
                            _error.value = response.errorMessage
                            _status.value = resolveStatus(currentUpId, currentVideoItems)
                        }
                    }
                    .onFailure { exception ->
                        if (nextPage == 1) {
                            currentVideoItems = emptyList()
                            _videos.value = emptyList()
                        }
                        _hasMoreVideos.value = false
                        _error.value = exception.message
                        _status.value = resolveStatus(currentUpId, currentVideoItems)
                    }
            } finally {
                if (gen == loadGeneration) {
                    _loading.value = false
                }
            }
        }
    }

    private fun schedulePreload() {
        preloadJob?.cancel()
        preloadJob = viewModelScope.launch {
            val list = _followingList.value
            if (list.isEmpty()) return@launch
            val currentIndex = list.indexOfFirst { it.mid.toString() == currentUpId }
            if (currentIndex < 0) return@launch

            val candidates = mutableListOf<FollowingModel>()
            list.getOrNull(currentIndex + 1)?.let { candidates.add(it) }
            list.getOrNull(currentIndex - 1)?.takeIf { it.mid != 0L }?.let { candidates.add(it) }

            for (up in candidates) {
                val upId = up.mid.toString()
                if (upId == ALL_DYNAMIC_ID || videoCache.get(upId) != null) continue
                userRepository.getUserDynamic(up.mid, 1, lastPageSize)
                    .onSuccess { response ->
                        if (response.isSuccess) {
                            val items = response.data?.archives.orEmpty()
                            if (items.isNotEmpty()) {
                                videoCache.put(upId, CachedUpVideos(items, 1, response.data?.hasMore == true))
                            }
                        }
                    }
            }
        }
    }

    private fun resolveStatus(upId: String, items: List<VideoModel>): DynamicStatus {
        if (items.isNotEmpty()) {
            return DynamicStatus.Content
        }
        if (upId == ALL_DYNAMIC_ID && !hasFollowingUsers) {
            return DynamicStatus.NoFollowing
        }
        return DynamicStatus.Empty
    }

    private fun applyFollowingItems(
        defaultItems: List<FollowingModel>,
        items: List<FollowingModel>,
        page: Int,
        total: Int
    ) {
        loadedFollowingMids.clear()
        val uniqueItems = items.filter { loadedFollowingMids.add(it.mid) }
        _followingList.value = defaultItems + uniqueItems
        followingPage = page
        followingHasMore = when {
            total > 0 -> loadedFollowingMids.size < total
            else -> uniqueItems.size >= FOLLOWING_PAGE_SIZE
        }
    }

    fun shouldRefresh(ttlMs: Long): Boolean {
        if (_screenState.value != ScreenState.Content || _followingList.value.isEmpty()) {
            return true
        }
        return System.currentTimeMillis() - lastLoadedAt >= ttlMs
    }

    override fun onCleared() {
        super.onCleared()
        loadJob?.cancel()
        preloadJob?.cancel()
        followingLoadJob?.cancel()
        videoCache.evictAll()
        AppLog.i(TAG, "onCleared: keep global ImageLoader memory cache, only clear dynamic page cache")
        _followingList.value = emptyList()
        _videos.value = emptyList()
        currentVideoItems = emptyList()
        loadedFollowingMids.clear()
    }

    private fun shouldLoadMoreFollowing(): Boolean {
        return when {
            followingTotal > 0 -> loadedFollowingMids.size < followingTotal
            else -> loadedFollowingMids.isNotEmpty() && loadedFollowingMids.size % FOLLOWING_PAGE_SIZE == 0
        }
    }
}
