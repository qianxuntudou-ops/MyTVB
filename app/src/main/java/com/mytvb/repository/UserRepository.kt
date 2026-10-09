package com.mytvb.repository

import com.mytvb.model.BaseResponse
import com.mytvb.model.user.UserStatModel
import com.mytvb.model.user.UserSpaceInfo
import com.mytvb.model.user.UserDetailInfoModel
import com.mytvb.model.user.CheckRelationModel
import com.mytvb.model.user.GetFollowUserWrapper
import com.mytvb.model.video.HistoryListResponse
import com.mytvb.model.video.LaterWatchWrapper
import com.mytvb.model.video.UserDynamicResponse
import com.mytvb.model.video.AllDynamicResponse
import com.mytvb.model.series.SeasonArchivesResponse
import com.mytvb.model.series.SeasonsSeriesListResponse
import com.mytvb.network.WbiGenerator
import com.mytvb.network.api.ApiService
import com.mytvb.network.security.NetworkSecurityGateway
import com.mytvb.network.session.NetworkSessionGateway
import com.mytvb.network.response.BaseBaseResponse
import com.mytvb.core.common.log.AppLog
import com.mytvb.model.video.VideoModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class UserRepository(
    private val apiService: ApiService,
    private val sessionGateway: NetworkSessionGateway,
    private val securityGateway: NetworkSecurityGateway
) {

    private val detailCache = mutableMapOf<String, Any>()
    private val detailSemaphore = Semaphore(3)

    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 「稍后观看」列表里 PGC 番剧的弹幕数补全完成后，会通过本流推送一次更新。
     *
     * 事件结构 [LaterWatchEnrichment] 同时带上 enrich 之前的 source list 引用，
     * 订阅者用引用相等判断「这次后台补全是不是匹配当前 UI 显示的那一份数据」，
     * 避免用户已经离开/重新加载后还回写过期结果。
     *
     * 用 `extraBufferCapacity = 4` 确保 tryEmit 不会因为没人订阅而丢事件。
     */
    private val _laterWatchEnriched = MutableSharedFlow<LaterWatchEnrichment>(
        replay = 0,
        extraBufferCapacity = 4
    )
    val laterWatchEnriched: SharedFlow<LaterWatchEnrichment> = _laterWatchEnriched.asSharedFlow()

    data class LaterWatchEnrichment(
        /** enrich 启动时拿到的原始 list 引用，UI 拿来做引用相等判断。 */
        val sourceList: List<VideoModel>,
        /** stat 已经补全后的新 list；元素是浅拷贝过 stat 的 VideoModel 副本，不污染原 list。 */
        val enrichedList: List<VideoModel>
    )

    suspend fun getUserDetailInfo(): BaseResponse<UserDetailInfoModel> {
        return apiService.getUserDetailInfo().also { response ->
            sessionGateway.syncUserSession(response, source = "getUserDetailInfo")
        }
    }
    
    suspend fun getUserStat(): BaseResponse<UserStatModel> {
        return apiService.getUserStat().also { response ->
            sessionGateway.handleAuthFailureCode(response.code, source = "getUserStat")
        }
    }

    suspend fun getRelationStat(mid: Long): BaseResponse<UserStatModel> {
        return sessionGateway.syncAuthState(
            apiService.getRelationStat(mid),
            source = "getRelationStat"
        )
    }
    
    suspend fun getUserSpace(mid: Long): BaseResponse<UserSpaceInfo> {
        if (!isLoggedIn()) {
            return apiService.getUserSpaceNoWbi(mid)
        }
        val params = mapOf(
            "mid" to mid.toString(),
            "token" to "",
            "platform" to "web",
            "web_location" to "1550101"
        )
        val response = doWbiRequest(params, "getUserSpace") { signedParams ->
            sessionGateway.syncAuthState(
                apiService.getUserSpace(signedParams),
                source = "getUserSpace"
            )
        }
        return response
    }
    
    fun isLoggedIn(): Boolean {
        return sessionGateway.isLoggedIn()
    }
    
    suspend fun getHistory(viewAt: Long, pageSize: Int): Result<BaseResponse<HistoryListResponse>> =
        runCatching {
            sessionGateway.executeWithRiskControlRetry(
                key = "history",
                source = "getHistory"
            ) {
                sessionGateway.syncAuthState(
                    apiService.getHistory(viewAt, pageSize),
                    source = "getHistory"
                )
            }
        }

    /**
     * 立刻返回稍后观看的主接口数据。番剧弹幕数补全异步进行，完成后通过
     * [laterWatchEnriched] 推送同一份 list（其中 [VideoModel.stat.danmaku] 已被原地修改）。
     */
    suspend fun getLaterWatch(): Result<BaseResponse<LaterWatchWrapper>> =
        runCatching {
            val response = sessionGateway.syncAuthState(
                apiService.getLaterWatch(),
                source = "getLaterWatch"
            )
            if (response.isSuccess && response.data != null) {
                scheduleEnrichLaterWatch(response.data.list)
            }
            response
        }

    private fun scheduleEnrichLaterWatch(originalList: List<VideoModel>) {
        if (originalList.isEmpty()) return
        repoScope.launch {
            // 给每个 VideoModel 准备一份 stat 副本：enrich 改副本，原 list 完全不变。
            // 这样 UI 端 DiffUtil 比较 oldList vs enrichedList 时能感知到 stat.danmaku
            // 实际变化（否则两边指向同一个 stat 对象，contents 永远相等，UI 不刷新）。
            val refreshed = originalList.map { v ->
                val s = v.stat
                if (s != null) v.copy(stat = s.copy()) else v
            }
            enrichPgcDanmakuCount(refreshed)
            _laterWatchEnriched.tryEmit(
                LaterWatchEnrichment(sourceList = originalList, enrichedList = refreshed)
            )
        }
    }

    suspend fun getFollowing(
        mid: Long,
        page: Int = 1,
        pageSize: Int = 50
    ): Result<BaseResponse<GetFollowUserWrapper>> =
        runCatching {
            sessionGateway.executeWithRiskControlRetry(
                key = "following_$mid",
                source = "getFollowing"
            ) {
                sessionGateway.syncAuthState(
                    apiService.getFollowing(mid, page, pageSize),
                    source = "getFollowing"
                )
            }
        }

    suspend fun getFollower(
        mid: Long,
        page: Int = 1,
        pageSize: Int = 50
    ): Result<BaseResponse<GetFollowUserWrapper>> =
        runCatching {
            sessionGateway.executeWithRiskControlRetry(
                key = "follower_$mid",
                source = "getFollower"
            ) {
                sessionGateway.syncAuthState(
                    apiService.getFollower(mid, page, pageSize),
                    source = "getFollower"
                )
            }
        }

    suspend fun checkUserRelation(
        mid: Long
    ): Result<BaseResponse<CheckRelationModel>> =
        runCatching {
            sessionGateway.syncAuthState(
                apiService.checkUserRelation(mid.toString()),
                source = "checkUserRelation"
            )
        }

    suspend fun modifyRelation(
        fid: Long,
        action: Int
    ): Result<BaseBaseResponse> =
        runCatching {
            securityGateway.ensureHealthyForPlay()
            val csrf = sessionGateway.requireCsrfToken()
                ?: return Result.success(BaseBaseResponse(code = -101, message = "csrf token is blank"))
            val params = mapOf(
                "fid" to fid.toString(),
                "act" to action.toString(),
                "csrf" to csrf
            )
            sessionGateway.syncAuthState(
                apiService.userRelationModify(params),
                source = "modifyRelation"
            )
        }

    suspend fun getUserDynamic(
        mid: Long,
        page: Int,
        pageSize: Int = 20
    ): Result<BaseResponse<UserDynamicResponse>> =
        runCatching {
            if (isLoggedIn()) {
                val params = mapOf(
                    "mid" to mid.toString(),
                    "pn" to page.toString(),
                    "ps" to pageSize.toString(),
                    "order" to "pubdate",
                    "platform" to "web",
                    "web_location" to "333.1387",
                    "order_avoided" to "true",
                    "index" to "1"
                )
                doWbiRequest(params, "getUserDynamic") { signedParams ->
                    sessionGateway.syncAuthState(
                        apiService.getUserArcSearch(signedParams),
                        source = "getUserDynamic"
                    )
                }
            } else {
                apiService.getUserDynamic(mid, page, pageSize)
            }
        }

    suspend fun getAllDynamic(
        page: Int,
        offset: Long? = null
    ): Result<BaseResponse<AllDynamicResponse>> =
        runCatching {
            sessionGateway.syncAuthState(
                apiService.getAllDynamic(page, offset),
                source = "getAllDynamic"
            )
        }

    suspend fun getSeasonsSeriesList(
        mid: Long,
        pageNum: Int = 1,
        // 实测该接口 page_size 上限 20，>20 返回 -400（网页版用 10/20）
        pageSize: Int = 20
    ): Result<BaseResponse<SeasonsSeriesListResponse>> =
        runCatching {
            // 网页版新版接口（home/seasons_series）无需 WBI 签名；老 seasons_series_list 已实测 -400。
            // Referer 必须是空间页，HeaderInterceptor 对 web-space 路径统一处理。
            sessionGateway.syncAuthState(
                apiService.getSeasonsSeriesList(
                    mapOf(
                        "mid" to mid.toString(),
                        "page_num" to pageNum.toString(),
                        "page_size" to pageSize.toString(),
                        "web_location" to "333.1387"
                    )
                ),
                source = "getSeasonsSeriesList"
            )
        }

    suspend fun getSeriesArchives(
        mid: Long,
        seriesId: Long,
        page: Int,
        pageSize: Int = 20
    ): Result<BaseResponse<UserDynamicResponse>> =
        runCatching {
            sessionGateway.syncAuthState(
                apiService.getSeriesArchives(mid, seriesId, page, pageSize),
                source = "getSeriesArchives"
            )
        }

    suspend fun getSeasonArchives(
        mid: Long,
        seasonId: Long,
        page: Int,
        pageSize: Int = 20
    ): Result<BaseResponse<SeasonArchivesResponse>> =
        runCatching {
            sessionGateway.syncAuthState(
                apiService.getSeasonArchives(mid, seasonId, page = page, pageSize = pageSize),
                source = "getSeasonArchives"
            )
        }

    suspend fun refreshCurrentUserInfo(): Result<UserDetailInfoModel> =
        runCatching {
            val response = apiService.getUserDetailInfo()
            val info = sessionGateway.syncUserSession(
                response,
                source = "refreshCurrentUserInfo"
            )
                ?: throw IllegalStateException(
                    response.errorMessage.ifEmpty { "加载用户信息失败" }
                )
            info
        }

    suspend fun resolveCurrentUserMid(): Result<Long> =
        runCatching {
            sessionGateway.getUserInfo()?.mid
                ?.takeIf { it > 0L }
                ?: refreshCurrentUserInfo().getOrThrow().mid.takeIf { it > 0L }
                ?: throw IllegalStateException("未获取到当前用户信息")
        }

    private suspend fun <T> doWbiRequest(
        params: Map<String, String>,
        source: String,
        block: suspend (Map<String, String>) -> BaseResponse<T>
    ): BaseResponse<T> {
        return sessionGateway.executeWithRiskControlRetry(
            key = "wbi_$source",
            source = source
        ) {
            ensureWbiKeysIfNeeded()
            val wbiKeys = sessionGateway.getWbiKeys()
            val signedParams = WbiGenerator.generateWbiParams(params, wbiKeys.first, wbiKeys.second)
            block(signedParams)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun enrichPgcDanmakuCount(list: List<com.mytvb.model.video.VideoModel>) {
        val candidates = list.filter { it.danmakuCount == 0L && it.viewCount > 0L }
        if (candidates.isEmpty()) return
        try {
            supervisorScope {
                candidates.map { video ->
                    async {
                        val cacheKey = if (video.bvid.isNotBlank()) "bvid_${video.bvid}" else "aid_${video.aid}"
                        try {
                            // 先检查缓存
                            val cached = detailCache[cacheKey]
                            if (cached != null) {
                                val dm = cached as Long
                                if (dm > 0) {
                                    video.stat?.let { it.danmaku = dm }
                                }
                                return@async
                            }

                            detailSemaphore.withPermit {
                                val detailResponse = apiService.getVideoDetail(video.aid, video.bvid)
                                if (!detailResponse.isSuccess) {
                                    detailCache[cacheKey] = 0L
                                    return@async
                                }
                                val redirectUrl = detailResponse.data?.view?.redirectUrl.orEmpty()
                                val epId = parseEpIdFromBangumiUrl(redirectUrl)
                                if (epId <= 0L) {
                                    detailCache[cacheKey] = 0L
                                    return@async
                                }
                                val pgcResponse = apiService.getPgcEpisodeInfo(epId)
                                if (pgcResponse.isSuccess) {
                                    val dm = pgcResponse.data
                                        ?.getAsJsonObject("stat")
                                        ?.get("dm")?.asLong ?: 0L
                                    detailCache[cacheKey] = dm
                                    if (dm > 0) {
                                        video.stat?.let { it.danmaku = dm }
                                    }
                                } else {
                                    detailCache[cacheKey] = 0L
                                }
                            }
                        } catch (e: Exception) {
                            AppLog.w("UserRepository", "enrichPgcDanmakuCount failed for aid=${video.aid}: ${e.message}")
                        }
                    }
                }.forEach { it.await() }
            }
        } catch (e: Exception) {
            AppLog.w("UserRepository", "enrichPgcDanmakuCount scope failed: ${e.message}")
        }
    }

    private fun parseEpIdFromBangumiUrl(url: String): Long {
        if (!url.contains("/bangumi/play/ep")) return 0L
        return url.substringAfter("/bangumi/play/ep", "")
            .takeWhile { it.isDigit() }
            .toLongOrNull() ?: 0L
    }

    private suspend fun ensureWbiKeysIfNeeded() {
        val keys = sessionGateway.getWbiKeys()
        if (keys.first.isBlank() || keys.second.isBlank() || sessionGateway.areWbiKeysStale()) {
            runCatching { sessionGateway.ensureWbiKeys() }
        }
    }
}
