package com.mytvb.feature.home

import android.os.SystemClock
import com.mytvb.core.common.coroutine.catchingRequest
import com.mytvb.core.common.log.AppLog
import com.mytvb.core.common.log.HomeVideoCardDebugLogger
import com.mytvb.model.video.VideoModel
import com.mytvb.network.api.BiliApi
import com.mytvb.network.grpc.BiliGrpcApi
import com.mytvb.repository.cache.HomeCacheStore

class HotFeedRepository {

    companion object {
        private const val TAG = "HotFeedRepository"
        private const val CACHE_KEY = "hotCacheList"
        private const val MAX_CACHED_HOT_ITEMS = 24
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
        AppLog.i(TAG, "APP_STARTUP hot cache read start")
        val cached = HomeCacheStore.readCachedVideos(CACHE_KEY)
        AppLog.i(
            TAG,
            "APP_STARTUP hot cache read end elapsed=${SystemClock.elapsedRealtime() - startMs}ms count=${cached.items.size} ageMs=${HomeCacheStore.cacheAgeMs(cached.savedAtMs)} schema=${cached.schemaVersion}"
        )
        return CachedFeed(
            items = cached.items.take(MAX_CACHED_HOT_ITEMS),
            savedAtMs = cached.savedAtMs,
            schemaVersion = cached.schemaVersion
        )
    }

    /** app 热门游标：gRPC Popular.Index 的 idx 语义是「上次响应原始列表末项 idx」，顺序翻页连续无重叠。 */
    @Volatile
    private var appHotNextIdx = 0L

    // catchingRequest 而非 runCatching：翻页循环含多个挂起点（gRPC/HTTP），切 tab 等场景
    // 协程取消时 runCatching 会把 CancellationException 吞成 Result.failure，
    // 上层 errorMessage 弹出「Continuation was cancelled」类莫名 Toast；取消必须重抛。
    suspend fun loadNetworkPage(page: Int, pageSize: Int): Result<NetworkPage> = catchingRequest {
        val useApp = FeedSourceSetting.isAppSource(FeedSourceSetting.KEY_HOT_FEED_SOURCE)
        val rawItems: List<VideoModel>
        var hasMore: Boolean
        if (useApp) {
            // 官方热门走 gRPC（grpc.biliapi.net，Popular/Index，与官方 App 同源）。
            // 单次响应约 10 余条不足以填满一屏（12+ 格，且无滚动空间会永远触发不了
            // 下一页），用末项 idx 游标连续请求拼满 pageSize，按 aid 去重防御
            // 服务端窗口边界重复；nextIdx=-1 表示到底。刷新（page=1）重置游标。
            if (page <= 1) appHotNextIdx = 0L
            val merged = LinkedHashMap<Long, VideoModel>()
            var exhausted = false
            while (merged.size < pageSize && !exhausted) {
                val batch = BiliGrpcApi.popularIndex(appHotNextIdx)
                if (batch.nextIdx < 0 || batch.items.isEmpty() && merged.isNotEmpty()) {
                    exhausted = true
                    break
                }
                appHotNextIdx = batch.nextIdx
                if (batch.nextIdx < 0) exhausted = true
                val before = merged.size
                batch.items.forEach { item -> merged.putIfAbsent(item.aid, item) }
                if (merged.size == before && batch.nextIdx >= 0) {
                    // 游标前进但内容全重复也视为到底
                    exhausted = true
                }
            }
            rawItems = merged.values.toList()
            hasMore = !exhausted
        } else {
            rawItems = BiliApi.hotList(page, pageSize)
            hasMore = rawItems.size >= pageSize
        }
        HomeVideoCardDebugLogger.logRejectedCards(
            source = "hot(page=$page,pageSize=$pageSize,source=${if (useApp) "app-grpc" else "web"})",
            items = rawItems
        )
        NetworkPage(
            items = rawItems.filter { it.isSupportedHomeVideoCard },
            hasMore = hasMore
        )
    }

    suspend fun writeCache(items: List<VideoModel>) {
        HomeCacheStore.writeVideos(
            cacheKey = CACHE_KEY,
            videos = items.take(MAX_CACHED_HOT_ITEMS)
        )
    }

    fun trimCacheItems(items: List<VideoModel>): List<VideoModel> {
        return items.take(MAX_CACHED_HOT_ITEMS)
    }

}
