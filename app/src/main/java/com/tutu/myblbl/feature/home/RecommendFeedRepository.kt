package com.tutu.myblbl.feature.home

import android.content.Context
import android.os.SystemClock
import com.tutu.myblbl.core.common.log.AppLog
import com.tutu.myblbl.core.common.log.HomeVideoCardDebugLogger
import com.tutu.myblbl.model.video.VideoModel
import com.tutu.myblbl.network.api.BiliApi
import com.tutu.myblbl.repository.cache.HomeCacheStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

class RecommendFeedRepository(
    context: Context
) {

    private val appContext = context.applicationContext

    companion object {
        private const val TAG = "RecommendFeedRepository"
        private const val CACHE_KEY = "recommendCacheList"
        private const val MAX_CACHED_RECOMMEND_ITEMS = 24
        private const val PRELOAD_PAGE_SIZE = 24
    }

    private val firstPageDeferred = CompletableDeferred<NetworkPage>()
    @Volatile
    private var preloadedFirstPage: NetworkPage? = null

    suspend fun preloadFirstPage() {
        val startMs = SystemClock.elapsedRealtime()
        AppLog.i(TAG, "STARTUP T2 preloadFirstPage start")
        val preloadSize = PRELOAD_PAGE_SIZE
        val result = loadNetworkPage(page = 1, pageSize = preloadSize, freshIdx = 1, fetchRow = 1)
        val page = result.getOrNull()
        if (page != null) {
            AppLog.i(TAG, "STARTUP T3 preload api done items=${page.items.size} hasMore=${page.hasMore}")
            preloadedFirstPage = page
            firstPageDeferred.complete(page)
            writeCache(page.items)
        } else {
            firstPageDeferred.completeExceptionally(
                result.exceptionOrNull() ?: IllegalStateException("preload failed")
            )
        }
        AppLog.i(TAG, "STARTUP T3b preload end elapsed=${SystemClock.elapsedRealtime() - startMs}ms")
    }

    fun peekFirstPage(): NetworkPage? {
        val page = preloadedFirstPage
        AppLog.i(TAG, "STARTUP peekFirstPage ${if (page != null) "hit" else "miss"} items=${page?.items?.size ?: 0}")
        return page
    }

    suspend fun awaitFirstPage(timeoutMs: Long): NetworkPage? {
        return try {
            withTimeout(timeoutMs) { firstPageDeferred.await() }
        } catch (e: TimeoutCancellationException) {
            preloadedFirstPage.also { page ->
                AppLog.w(
                    TAG,
                    "STARTUP awaitFirstPage timeout after ${timeoutMs}ms fallback=${if (page != null) "hit" else "miss"}"
                )
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "STARTUP awaitFirstPage failed: ${e.message}")
            preloadedFirstPage
        }
    }

    data class CachedFeed(
        val items: List<VideoModel>,
        val savedAtMs: Long,
        val schemaVersion: Int
    )

    data class NetworkPage(
        val items: List<VideoModel>,
        val hasMore: Boolean,
        val rawCount: Int,
        val requestFreshIdx: Int,
        val requestFetchRow: Int,
        val source: String
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
        freshIdx: Int,
        fetchRow: Int
    ): Result<NetworkPage> = runCatching {
        loadWebFeedPage(page, pageSize, freshIdx, fetchRow)
    }

    private suspend fun loadWebFeedPage(
        page: Int,
        pageSize: Int,
        freshIdx: Int,
        fetchRow: Int
    ): NetworkPage {
        val t0 = SystemClock.elapsedRealtime()
        val source = "recommend_v8(page=$page,freshIdx=$freshIdx,fetchRow=$fetchRow,ps=$pageSize)"
        val recommendResult = runCatching {
            BiliApi.recommendV8(freshIdx = freshIdx, ps = pageSize, fetchRow = fetchRow)
        }.getOrElse { throwable ->
            AppLog.w(TAG, "$source failed: ${throwable.message}; fallback V1")
            val fallbackItems = BiliApi.recommend(freshIdx, pageSize)
            BiliApi.RecommendPageResult(
                items = fallbackItems,
                rawCount = fallbackItems.size,
                source = "V1_FALLBACK"
            )
        }.let { result ->
            if (result.items.isNotEmpty()) {
                result
            } else {
                AppLog.w(TAG, "$source empty; fallback V1")
                val fallbackItems = BiliApi.recommend(freshIdx, pageSize)
                BiliApi.RecommendPageResult(
                    items = fallbackItems,
                    rawCount = fallbackItems.size,
                    source = "V1_FALLBACK_EMPTY"
                )
            }
        }
        val rawItems = recommendResult.items
        val t1 = SystemClock.elapsedRealtime()
        AppLog.i(TAG, "$source apiSource=${recommendResult.source} raw=${recommendResult.rawCount} parsed=${rawItems.size} api=${t1 - t0}ms")
        HomeVideoCardDebugLogger.logRejectedCards(
            source = "$source apiSource=${recommendResult.source}",
            items = rawItems
        )
        val filtered = rawItems.filter { it.isSupportedHomeVideoCard }
        val t2 = SystemClock.elapsedRealtime()
        AppLog.i(TAG, "$source filter=${t2 - t1}ms api=${t1 - t0}ms raw=${recommendResult.rawCount} parsed=${rawItems.size}->${filtered.size}")
        return NetworkPage(
            items = filtered,
            hasMore = recommendResult.rawCount >= pageSize,
            rawCount = recommendResult.rawCount,
            requestFreshIdx = freshIdx,
            requestFetchRow = fetchRow,
            source = recommendResult.source
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
