package com.mytvb.feature.home

import android.content.Context
import android.os.SystemClock
import com.mytvb.R
import com.mytvb.core.common.coroutine.catchingRequest
import com.mytvb.core.common.log.AppLog
import com.mytvb.model.video.VideoModel
import kotlinx.coroutines.CoroutineStart

class RecommendViewModel(
    private val repository: RecommendFeedRepository,
    dislikeFeedback: RecommendDislikeFeedback,
    context: Context
) : BaseVideoFeedViewModel(context, dislikeFeedback) {

    companion object {
        private const val TAG = "RecommendVM"
        private const val FIRST_PAGE_SIZE = 24
        private const val NEXT_PAGE_SIZE = 24
    }

    private var nextRecommendFetchRow = 1
    private val seenVideoIds = mutableSetOf<String>()

    override val initialLaunchStart: CoroutineStart = CoroutineStart.UNDISPATCHED

    override fun onInitialLaunched() {
        AppLog.i(TAG, "STARTUP T5 viewModel.loadInitial")
    }

    override suspend fun fetchPage(
        page: Int,
        replace: Boolean,
        fromInitial: Boolean,
        fromRefresh: Boolean
    ): Result<FetchedPage> {
        if (page == 1 && replace && fromInitial) {
            return fetchSharedFirstPage()
        }

        val freshIdx = page.coerceAtLeast(1)
        val fetchRow = if (page == 1 && replace) {
            1
        } else {
            nextRecommendFetchRow.coerceAtLeast(1)
        }
        val pageSize = if (page == 1) FIRST_PAGE_SIZE else NEXT_PAGE_SIZE
        return repository.loadNetworkPage(
            page = page,
            pageSize = pageSize,
            freshIdx = freshIdx,
            fetchRow = fetchRow
        ).map { result ->
            AppLog.i(
                TAG,
                "STARTUP T7 network page=$page freshIdx=$freshIdx fetchRow=$fetchRow source=${result.source} raw=${result.rawCount} ready items=${result.items.size}"
            )
            if (page == 1 && replace) {
                // 同步内存预加载首页，避免下次 loadInitial（切 tab 重建）回退到旧数据
                repository.updatePreloadedFirstPage(result)
            }
            val filteredItems = filterForDisplay(result.items)
            if (replace) {
                seenVideoIds.clear()
            }
            val dedupedItems = filteredItems.filter { it.bvid.isBlank() || it.bvid !in seenVideoIds }
            dedupedItems.mapNotNullTo(seenVideoIds) { it.bvid.takeIf(String::isNotBlank) }
            nextRecommendFetchRow = nextFetchRowAfter(result)
            FetchedPage(items = dedupedItems, hasMore = result.hasMore)
        }
    }

    override fun errorMessage(throwable: Throwable): String {
        return throwable.message ?: appContext.getString(R.string.home_recommend_load_failed)
    }

    override suspend fun writeCache(items: List<VideoModel>) {
        repository.writeCache(repository.trimCacheItems(items))
    }

    override fun onPageFailed(page: Int, throwable: Throwable) {
        if (page == 1) {
            AppLog.w(TAG, "STARTUP T6 sharedFirstPage failed: ${throwable.message}")
        }
    }

    private suspend fun fetchSharedFirstPage(): Result<FetchedPage> {
        val sharedStart = SystemClock.elapsedRealtime()
        // catchingRequest 而非 runCatching：loadSharedFirstPage 的 requireCurrent 会抛
        // StaleSessionException（CancellationException 子类）。扫码登录推进会话代际瞬间，
        // 游客态在途首屏请求作废属取消语义，必须重抛让 loadPage 协程静默终止；
        // runCatching 会把它吞成 Result.failure → errorMessage=英文原文 → 首页弹
        // "Request belongs to an obsolete session" Toast（后台 Fragment 全局可见）。
        return catchingRequest {
            repository.loadSharedFirstPage(pageSize = FIRST_PAGE_SIZE, reason = "viewModelInitial")
        }.map { firstPage ->
            AppLog.i(
                TAG,
                "STARTUP T6 sharedFirstPage hit elapsed=${SystemClock.elapsedRealtime() - sharedStart}ms items=${firstPage.items.size}"
            )
            val filterStart = SystemClock.elapsedRealtime()
            val filteredItems = filterForDisplay(firstPage.items)
            AppLog.i(TAG, "STARTUP sharedFirstPage filterForInitial=${SystemClock.elapsedRealtime() - filterStart}ms")
            seenVideoIds.clear()
            filteredItems.mapNotNullTo(seenVideoIds) { it.bvid.takeIf(String::isNotBlank) }
            nextRecommendFetchRow = nextFetchRowAfter(firstPage)
            FetchedPage(items = filteredItems, hasMore = firstPage.hasMore)
        }
    }

    private fun nextFetchRowAfter(page: RecommendFeedRepository.NetworkPage): Int {
        val step = page.rawCount.coerceAtLeast(page.items.size).coerceAtLeast(1)
        return page.requestFetchRow + step
    }
}
