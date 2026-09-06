package com.tutu.myblbl.repository

import com.tutu.myblbl.core.common.log.AppLog
import com.tutu.myblbl.model.BaseResponse
import com.tutu.myblbl.model.favorite.FavoriteFoldersWrapper
import com.tutu.myblbl.model.video.HistoryListResponse
import com.tutu.myblbl.model.video.LaterWatchWrapper
import com.tutu.myblbl.network.session.NetworkSessionGateway
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import android.os.SystemClock

/**
 * 个人数据预取（历史记录 / 稍后观看 / 收藏夹列表）。
 *
 * 背景：这几个页面都是"点进 tab 才创建 Fragment 才发请求"，真机 RTT 大时
 * 用户要干等一整个网络往返。参照首页推荐流 preloadFirstPage 的思路：启动后
 * 空闲期把第一页数据预取到内存，用户点进"我的"时直接渲染。
 *
 * 消费语义：
 * - 预取已完成 → [takeHistory]/[takeLaterWatch]/[takeFolders] 一次性取走（取走后
 *   返回 null，后续进入走正常网络，避免陈旧数据反复顶掉新数据）；
 * - 预取进行中 → await 同一条 in-flight 请求（不重发），最多等 [AWAIT_TIMEOUT_MS]，
 *   超时/失败返回 null 由调用方走正常网络路径；
 * - 未登录不预取；预取失败静默（页面自己会重试并提示错误）。
 */
class PersonalFeedPrewarmer(
    private val userRepository: UserRepository,
    private val favoriteRepository: FavoriteRepository,
    private val sessionGateway: NetworkSessionGateway
) {

    companion object {
        private const val TAG = "PersonalPrewarm"
        private const val HISTORY_PAGE_SIZE = 20
        /** 预取仅覆盖"启动后马上进页面"的场景，等太久不如自己发请求。 */
        private const val AWAIT_TIMEOUT_MS = 1500L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    private var historyResult: BaseResponse<HistoryListResponse>? = null
    private var historyInFlight: CompletableDeferred<BaseResponse<HistoryListResponse>?>? = null
    private var laterResult: BaseResponse<LaterWatchWrapper>? = null
    private var laterInFlight: CompletableDeferred<BaseResponse<LaterWatchWrapper>?>? = null
    private var foldersResult: BaseResponse<FavoriteFoldersWrapper>? = null
    private var foldersInFlight: CompletableDeferred<BaseResponse<FavoriteFoldersWrapper>?>? = null

    var prewarmStarted = false
        private set

    /** 启动空闲期调用：登录态下并行预取三份数据，幂等。 */
    fun prewarm() {
        synchronized(lock) {
            if (prewarmStarted) return
            prewarmStarted = true
        }
        if (!sessionGateway.isLoggedIn()) {
            AppLog.i(TAG, "prewarm skip loggedOut")
            return
        }
        val startMs = SystemClock.elapsedRealtime()
        AppLog.i(TAG, "prewarm start")
        scope.launch { prewarmHistory() }
        scope.launch { prewarmLater() }
        scope.launch { prewarmFolders() }
        AppLog.i(TAG, "prewarm scheduled elapsed=${SystemClock.elapsedRealtime() - startMs}ms")
    }

    private suspend fun prewarmHistory() {
        val deferred = CompletableDeferred<BaseResponse<HistoryListResponse>?>()
        synchronized(lock) { historyInFlight = deferred }
        val startMs = SystemClock.elapsedRealtime()
        val response = runCatching {
            userRepository.getHistory(0L, HISTORY_PAGE_SIZE).getOrNull()
        }.getOrNull()
        AppLog.i(TAG, "prewarm history done elapsed=${SystemClock.elapsedRealtime() - startMs}ms code=${response?.code}")
        synchronized(lock) {
            historyResult = response
            historyInFlight = null
        }
        deferred.complete(response)
    }

    private suspend fun prewarmLater() {
        val deferred = CompletableDeferred<BaseResponse<LaterWatchWrapper>?>()
        synchronized(lock) { laterInFlight = deferred }
        val startMs = SystemClock.elapsedRealtime()
        val response = runCatching {
            userRepository.getLaterWatch().getOrNull()
        }.getOrNull()
        AppLog.i(TAG, "prewarm later done elapsed=${SystemClock.elapsedRealtime() - startMs}ms code=${response?.code}")
        synchronized(lock) {
            laterResult = response
            laterInFlight = null
        }
        deferred.complete(response)
    }

    private suspend fun prewarmFolders() {
        val mid = runCatching {
            userRepository.resolveCurrentUserMid().getOrNull()
        }.getOrNull()
        if (mid == null || mid <= 0L) {
            AppLog.w(TAG, "prewarm folders skip noMid")
            return
        }
        val deferred = CompletableDeferred<BaseResponse<FavoriteFoldersWrapper>?>()
        synchronized(lock) { foldersInFlight = deferred }
        val startMs = SystemClock.elapsedRealtime()
        val response = runCatching {
            favoriteRepository.getFavoriteFolders(mid).getOrNull()
        }.getOrNull()
        AppLog.i(TAG, "prewarm folders done mid=$mid elapsed=${SystemClock.elapsedRealtime() - startMs}ms code=${response?.code}")
        synchronized(lock) {
            foldersResult = response
            foldersInFlight = null
        }
        deferred.complete(response)
    }

    /** 命中预取的历史首页；未命中（未预取/失败/超时）返回 null，调用方走正常网络。 */
    suspend fun takeHistory(): BaseResponse<HistoryListResponse>? {
        // 锁内只取引用，await 放锁外：挂起等待不释放 monitor，
        // 否则预取协程完成时拿不到锁写结果，双方互等。
        val await: CompletableDeferred<BaseResponse<HistoryListResponse>?>?
        val cached: BaseResponse<HistoryListResponse>?
        synchronized(lock) {
            await = historyInFlight
            cached = historyResult
            if (await == null && cached != null) historyResult = null
        }
        if (await != null) {
            AppLog.i(TAG, "takeHistory await inFlight")
            return awaitPrewarm(await, "History")
        }
        AppLog.i(TAG, "takeHistory ${if (cached != null) "hit" else "miss"}")
        return cached
    }

    suspend fun takeLaterWatch(): BaseResponse<LaterWatchWrapper>? {
        val await: CompletableDeferred<BaseResponse<LaterWatchWrapper>?>?
        val cached: BaseResponse<LaterWatchWrapper>?
        synchronized(lock) {
            await = laterInFlight
            cached = laterResult
            if (await == null && cached != null) laterResult = null
        }
        if (await != null) {
            AppLog.i(TAG, "takeLaterWatch await inFlight")
            return awaitPrewarm(await, "LaterWatch")
        }
        AppLog.i(TAG, "takeLaterWatch ${if (cached != null) "hit" else "miss"}")
        return cached
    }

    suspend fun takeFolders(): BaseResponse<FavoriteFoldersWrapper>? {
        val await: CompletableDeferred<BaseResponse<FavoriteFoldersWrapper>?>?
        val cached: BaseResponse<FavoriteFoldersWrapper>?
        synchronized(lock) {
            await = foldersInFlight
            cached = foldersResult
            if (await == null && cached != null) foldersResult = null
        }
        if (await != null) {
            AppLog.i(TAG, "takeFolders await inFlight")
            return awaitPrewarm(await, "Folders")
        }
        AppLog.i(TAG, "takeFolders ${if (cached != null) "hit" else "miss"}")
        return cached
    }

    private suspend fun <T> awaitPrewarm(
        inFlight: CompletableDeferred<T?>,
        name: String
    ): T? {
        val response = withTimeoutOrNull(AWAIT_TIMEOUT_MS) { inFlight.await() }
        AppLog.i(TAG, "take$name await done result=${response != null}")
        return response
    }
}
