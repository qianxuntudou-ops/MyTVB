package com.tutu.myblbl.feature.home

import android.content.Context
import android.os.SystemClock
import com.tutu.myblbl.core.common.log.AppLog
import com.tutu.myblbl.core.common.log.HomeVideoCardDebugLogger
import com.tutu.myblbl.core.ui.image.ImageLoader
import com.tutu.myblbl.model.video.VideoModel
import com.tutu.myblbl.repository.VideoRepository
import com.tutu.myblbl.repository.cache.HomeCacheStore

class RecommendFeedRepository(
    private val videoRepository: VideoRepository,
    context: Context
) {

    private val appContext = context.applicationContext

    companion object {
        private const val TAG = "RecommendFeedRepository"
        private const val CACHE_KEY = "recommendCacheList"
        private const val MAX_CACHED_RECOMMEND_ITEMS = 24
        private const val PRELOAD_PAGE_SIZE = 12
        private const val COVER_PREFETCH_COUNT = 8
    }

    @Volatile
    private var preloadedFirstPage: NetworkPage? = null

    suspend fun preloadFirstPage() {
        val startMs = SystemClock.elapsedRealtime()
        AppLog.i(TAG, "APP_STARTUP recommend preload start")
        val preloadSize = PRELOAD_PAGE_SIZE
        loadNetworkPage(page = 1, pageSize = preloadSize, freshIdx = 0)
            .getOrNull()?.let { page ->
                AppLog.i(TAG, "APP_STARTUP recommend preload got ${page.items.size} items, hasMore=${page.hasMore}")
                preloadedFirstPage = page
                writeCache(page.items)
                prefetchCovers(page.items)
            }
        AppLog.i(TAG, "APP_STARTUP recommend preload end elapsed=${SystemClock.elapsedRealtime() - startMs}ms")
    }

    private fun prefetchCovers(items: List<VideoModel>) {
        if (items.isEmpty()) return
        val urls = items.asSequence()
            .take(COVER_PREFETCH_COUNT)
            .map { it.bangumi?.cover?.takeIf { c -> c.isNotBlank() } ?: it.coverUrl }
            .toList()
        ImageLoader.prefetchVideoCovers(appContext, urls)
    }

    fun takePreloadedFirstPage(): NetworkPage? {
        val result = preloadedFirstPage
        preloadedFirstPage = null
        return result
    }

    data class CachedFeed(
        val items: List<VideoModel>,
        val savedAtMs: Long,
        val schemaVersion: Int
    )

    data class NetworkPage(
        val items: List<VideoModel>,
        val hasMore: Boolean
    )

    suspend fun readCachedFeed(): CachedFeed {
        val startMs = SystemClock.elapsedRealtime()
        AppLog.i(TAG, "APP_STARTUP recommend cache read start")
        val cached = HomeCacheStore.readCachedVideos(CACHE_KEY)
        AppLog.i(
            TAG,
            "APP_STARTUP recommend cache read end elapsed=${SystemClock.elapsedRealtime() - startMs}ms count=${cached.items.size} ageMs=${formatCacheAge(cached.savedAtMs)} schema=${cached.schemaVersion}"
        )
        return CachedFeed(
            items = cached.items.take(MAX_CACHED_RECOMMEND_ITEMS),
            savedAtMs = cached.savedAtMs,
            schemaVersion = cached.schemaVersion
        )
    }

    suspend fun loadNetworkPage(
        page: Int,
        pageSize: Int,
        freshIdx: Int
    ): Result<NetworkPage> = runCatching {
        loadWebFeedPage(page, pageSize, freshIdx)
    }

    private suspend fun loadWebFeedPage(
        page: Int,
        pageSize: Int,
        freshIdx: Int
    ): NetworkPage {
        val response = videoRepository.getRecommendList(freshIdx, pageSize)
        if (!response.isSuccess) {
            error(response.errorMessage.ifBlank { response.message.ifBlank { "推荐加载失败" } })
        }
        val rawItems = response.data?.items.orEmpty()
        AppLog.i(TAG, "recommend_web(page=$page,freshIdx=$freshIdx) raw=${rawItems.size}")
        HomeVideoCardDebugLogger.logRejectedCards(
            source = "recommend_web(page=$page,freshIdx=$freshIdx)",
            items = rawItems
        )
        return NetworkPage(
            items = rawItems.filter { it.isSupportedHomeVideoCard },
            hasMore = rawItems.size >= pageSize
        )
    }

    suspend fun writeCache(items: List<VideoModel>) {
        HomeCacheStore.writeVideos(
            cacheKey = CACHE_KEY,
            videos = items.take(MAX_CACHED_RECOMMEND_ITEMS)
        )
    }

    fun trimCacheItems(items: List<VideoModel>): List<VideoModel> {
        return items.take(MAX_CACHED_RECOMMEND_ITEMS)
    }

    private fun formatCacheAge(savedAtMs: Long): Long {
        return if (savedAtMs > 0L) {
            System.currentTimeMillis() - savedAtMs
        } else {
            -1L
        }
    }
}
