package com.mytvb.feature.category

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mytvb.MyBLBLApplication
import com.mytvb.R
import com.mytvb.core.common.log.AppLog
import com.mytvb.model.video.VideoModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 分类页 ViewModel（2026-11 官网频道化改造）：排行榜一次性拉取 → 频道推荐流无限追加。
 *
 * 每个分区一份 [CategoryState]：缓存「已加载的全部批次」（回 tab 恢复列表）+ 去重集合 +
 * displayId 游标。loadFirst 刷新重置，loadMore 追加去重；全站（rid=0）displayId 即热门页码。
 * 追加失败静默（不弹 error 视图避免盖掉已加载列表），滚动会再次触发自然重试。
 */
class CategoryViewModel(
    private val feedRepository: CategoryFeedRepository
) : ViewModel() {

    private data class CategoryState(
        val videos: List<VideoModel> = emptyList(),
        val loadedAids: Set<Long> = emptySet(),
        val displayId: Int = 0,
        val endReached: Boolean = false,
        val updatedAt: Long = 0L
    ) {
        fun stale(ttlMs: Long): Boolean =
            updatedAt == 0L || System.currentTimeMillis() - updatedAt >= ttlMs
    }

    companion object {
        private const val TAG = "CategoryViewModel"

        /** 分区状态缓存（companion 静态 → 跨 Fragment/ViewModel 实例存活，同旧实现语义：
         * ViewPager2 销毁离屏页时 ViewModel 跟着销毁，回 tab 靠它免重拉）。 */
        private val categoryStates = linkedMapOf<Int, CategoryState>()
    }

    private val _videos = MutableStateFlow<List<VideoModel>>(emptyList())
    val videos: StateFlow<List<VideoModel>> = _videos.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /** 追加加载中（不弹大圈，供底部 footer/日志判断用）。 */
    private val _loadingMore = MutableStateFlow(false)
    val loadingMore: StateFlow<Boolean> = _loadingMore.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _hasLoaded = MutableStateFlow(false)
    val hasLoaded: StateFlow<Boolean> = _hasLoaded.asStateFlow()

    private var currentRid: Int = -1

    fun clearError() {
        _error.value = null
    }

    /** 首批加载：命中未过期缓存直接回放（切 tab 回来），forceRefresh 强制重拉并重置游标。 */
    fun loadCategoryVideos(rid: Int, forceRefresh: Boolean = false) {
        if (!forceRefresh) {
            categoryStates[rid]?.let { cached ->
                _error.value = null
                _hasLoaded.value = true
                _videos.value = cached.videos
                return
            }
        }
        currentRid = rid
        _loading.value = true
        _error.value = null
        viewModelScope.launch {
            feedRepository.loadPage(rid, displayId = CategoryFeedRepository.FIRST_DISPLAY_ID)
                .onSuccess { page ->
                    _loading.value = false
                    _hasLoaded.value = true
                    val state = CategoryState(
                        videos = page.items,
                        loadedAids = page.items.map { it.aid }.toSet(),
                        displayId = CategoryFeedRepository.FIRST_DISPLAY_ID,
                        endReached = !page.hasMore,
                        updatedAt = System.currentTimeMillis()
                    )
                    categoryStates[rid] = state
                    if (rid == currentRid) {
                        _videos.value = page.items
                        _error.value = null
                    }
                    AppLog.i(TAG, "loadFirst rid=$rid count=${page.items.size} hasMore=${page.hasMore}")
                }
                .onFailure { exception ->
                    _loading.value = false
                    _hasLoaded.value = true
                    if (rid == currentRid) {
                        _error.value = normalizeErrorMessage(exception.message)
                    }
                    AppLog.w(TAG, "loadFirst rid=$rid failed: ${exception.message}")
                }
        }
    }

    /** 追加下一批：displayId+1，按 aid 去重；新卡为空视为到底。失败静默可重试。 */
    fun loadMore(rid: Int) {
        val state = categoryStates[rid]
        if (state == null) {
            loadCategoryVideos(rid)
            return
        }
        if (_loadingMore.value || _loading.value || state.endReached) return
        currentRid = rid
        _loadingMore.value = true
        viewModelScope.launch {
            feedRepository.loadPage(rid, displayId = state.displayId + 1)
                .onSuccess { page ->
                    _loadingMore.value = false
                    val fresh = page.items.filter { it.aid > 0 && it.aid !in state.loadedAids }
                    val newState = state.copy(
                        videos = state.videos + fresh,
                        loadedAids = state.loadedAids + fresh.map { it.aid },
                        displayId = state.displayId + 1,
                        // 扥掉去重后无新卡：推荐流窗口抖动（同批重发）或热门翻到底
                        endReached = state.endReached || fresh.isEmpty(),
                        updatedAt = System.currentTimeMillis()
                    )
                    categoryStates[rid] = newState
                    if (rid == currentRid && fresh.isNotEmpty()) {
                        _videos.value = newState.videos
                    }
                    AppLog.i(TAG, "loadMore rid=$rid displayId=${state.displayId + 1} raw=${page.items.size} fresh=${fresh.size} end=${newState.endReached}")
                }
                .onFailure { exception ->
                    // 静默：追加失败不能弹 error 视图盖掉已加载列表；
                    // displayId 不前进，滚动再触发即重试同批
                    _loadingMore.value = false
                    AppLog.w(TAG, "loadMore rid=$rid failed: ${exception.message}")
                }
        }
    }

    /** Fragment 在列表数据变化后调用，同步基类 hasMore（endReached 后停滚触发）。 */
    fun hasMoreToLoad(rid: Int): Boolean {
        val state = categoryStates[rid] ?: return true
        return !state.endReached
    }

    private fun normalizeErrorMessage(message: String?): String {
        val raw = message.orEmpty().trim()
        val fallback = MyBLBLApplication.instance.getString(R.string.category_load_failed)
        return if (raw == "-352") {
            fallback
        } else {
            raw.ifBlank { fallback }
        }
    }

    fun isCacheStale(rid: Int, ttlMs: Long): Boolean {
        return categoryStates[rid]?.stale(ttlMs) ?: true
    }
}
