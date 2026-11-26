package com.mytvb.repository

import com.google.gson.reflect.TypeToken
import com.mytvb.core.common.json.GsonHolder
import com.mytvb.model.BaseResponse
import com.mytvb.model.user.UserStatModel
import com.mytvb.model.user.UserSpaceInfo
import com.mytvb.model.user.UserDetailInfoModel
import com.mytvb.model.user.CheckRelationModel
import com.mytvb.model.user.GetFollowUserWrapper
import com.mytvb.model.video.HistoryListResponse
import com.mytvb.model.video.HistoryVideoModel
import com.mytvb.model.video.LaterWatchWrapper
import com.mytvb.model.video.UserDynamicResponse
import com.mytvb.model.video.AllDynamicResponse
import com.mytvb.model.series.SeasonArchivesResponse
import com.mytvb.model.series.SeasonsSeriesBundle
import com.mytvb.model.series.SeasonsSeriesItem
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
        val requestSession = com.mytvb.network.session.RequestSessionScope.capture()
        return apiService.getUserDetailInfo().also { response ->
            com.mytvb.network.session.RequestSessionScope.requireCurrent(requestSession)
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
    
    suspend fun getHistory(
        viewAt: Long,
        pageSize: Int,
        max: Long = 0,
        business: String = ""
    ): Result<BaseResponse<HistoryListResponse>> =
        runCatching {
            sessionGateway.executeWithRiskControlRetry(
                key = "history",
                source = "getHistory"
            ) {
                sessionGateway.syncAuthState(
                    // 游标是 max+business+view_at 三元组（官方文档"类似链表 next 指针"）：
                    // 只传 view_at 时同一秒的相邻条目无法定位边界，跨页会丢/重条目。
                    apiService.getHistory(viewAt, max, business, pageSize),
                    source = "getHistory"
                )
            }
        }

    /**
     * 历史页「继续观看」推荐。该接口 data 结构官方文档未收录（仅有 null 样本），
     * 防御式解析：data 非空时取其第一个对象数组成员按历史条目模型反序列化，
     * 只保留有 bvid 且有进度（看过一部分）的条目；解析不出则返回 null，调用方隐藏入口。
     */
    suspend fun getHistoryContinuation(): List<HistoryVideoModel>? =
        runCatching {
            val response = sessionGateway.syncAuthState(
                apiService.getHistoryContinuation(),
                source = "getHistoryContinuation"
            )
            if (!response.isSuccess) return@runCatching null
            val data = response.data ?: return@runCatching null
            val array = data.entrySet()
                .asSequence()
                .mapNotNull { it.value.asJsonArrayOrNull() }
                .firstOrNull { it.size() > 0 }
                ?: return@runCatching null
            val items = GsonHolder.DEFAULT.fromJson(
                array,
                TypeToken.getParameterized(List::class.java, HistoryVideoModel::class.java).type
            ) as List<HistoryVideoModel>
            items.filter { it.bvid.isNotBlank() && it.progress > 0L }.takeIf { it.isNotEmpty() }
        }.getOrNull()

    private fun com.google.gson.JsonElement.asJsonArrayOrNull(): com.google.gson.JsonArray? =
        if (isJsonArray) asJsonArray else null

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
        pageSize: Int = 50,
        // null=关注顺序；"attention"=最常访问（issue#142，仅自己有效）
        orderType: String? = null
    ): Result<BaseResponse<GetFollowUserWrapper>> =
        runCatching {
            sessionGateway.executeWithRiskControlRetry(
                key = "following_${mid}_${orderType ?: "default"}",
                source = "getFollowing"
            ) {
                sessionGateway.syncAuthState(
                    apiService.getFollowing(mid, page, pageSize, orderType),
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
        pageSize: Int = 20,
        order: String = "pubdate"
    ): Result<BaseResponse<UserDynamicResponse>> =
        runCatching {
            if (isLoggedIn()) {
                val params = mapOf(
                    "mid" to mid.toString(),
                    "pn" to page.toString(),
                    "ps" to pageSize.toString(),
                    "order" to order,
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
                apiService.getUserDynamic(mid, page, pageSize, order)
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

    /** UP 主单页合集/系列列表（旧版接口，page_num 真翻页；page_size 上限 20，>20 返回 -400）。 */
    suspend fun getSeasonsSeriesListPage(
        mid: Long,
        pageNum: Int = 1,
        pageSize: Int = SEASONS_SERIES_PAGE_SIZE
    ): BaseResponse<SeasonsSeriesListResponse> =
        // 网页版旧接口（seasons_series_list）无需 WBI 签名；Referer 必须是空间页，
        // HeaderInterceptor 对 web-space 路径统一处理。
        sessionGateway.syncAuthState(
            apiService.getSeasonsSeriesListLegacy(
                mapOf(
                    "mid" to mid.toString(),
                    "page_num" to pageNum.toString(),
                    "page_size" to pageSize.toString(),
                    "web_location" to "333.1387"
                )
            ),
            source = "getSeasonsSeriesList"
        )

    /**
     * UP 主的全部合集与系列（跨页聚合）。
     *
     * 单页上限 20 条（seasons+series 合计，接口 page.total 为总条数），合集多的 UP（实测 32 条）
     * 必须翻页才能取全。新版 home/seasons_series 忽略 page_num、只返回第一页（合集 >20 个即缺），
     * 因此主用旧版接口分页；旧版整体不可用时回落新版单页，保住原有的第一页合集。
     *
     * 停止条件：取满 total / 某页无新增（服务端不再翻页）/ 空页 / 页数上限。
     */
    suspend fun getSeasonsSeriesAll(mid: Long): Result<SeasonsSeriesBundle> = runCatching {
        val seasons = LinkedHashMap<Long, SeasonsSeriesItem>()
        val series = LinkedHashMap<Long, SeasonsSeriesItem>()
        var total = 0
        var fetchedPages = 0
        var legacyWorked = false
        var pageNum = 1
        while (pageNum <= SEASONS_SERIES_MAX_PAGES) {
            val response = getSeasonsSeriesListPage(mid, pageNum)
            if (response.code != 0) {
                AppLog.w("UserRepository", "seasons_series page=$pageNum code=${response.code} ${response.message}")
                break
            }
            legacyWorked = true
            val data = response.data ?: break
            total = if (data.totalCount > 0) data.totalCount else total
            val pageSeasons = data.seasons
            val pageSeries = data.series
            if (pageSeasons.isEmpty() && pageSeries.isEmpty()) break
            val before = seasons.size + series.size
            pageSeasons.forEach { item -> if (item.seasonId > 0) seasons.putIfAbsent(item.seasonId, item) }
            pageSeries.forEach { item -> if (item.seriesId > 0) series.putIfAbsent(item.seriesId, item) }
            fetchedPages++
            val after = seasons.size + series.size
            if (after == before) break // 服务端不认 page_num（同页重复），继续翻只会打空炮
            if (total > 0 && after >= total) break
            pageNum++
        }
        if (!legacyWorked) {
            // 旧版接口不可用（下线/风控）：回落新版单页
            val fallback = sessionGateway.syncAuthState(
                apiService.getSeasonsSeriesList(
                    mapOf(
                        "mid" to mid.toString(),
                        "page_num" to "1",
                        "page_size" to SEASONS_SERIES_PAGE_SIZE.toString(),
                        "web_location" to "333.1387"
                    )
                ),
                source = "getSeasonsSeriesList"
            )
            AppLog.w(
                "UserRepository",
                "seasons_series legacy unavailable, fallback new api code=${fallback.code} " +
                    "seasons=${fallback.data?.seasons?.size ?: 0} series=${fallback.data?.series?.size ?: 0}"
            )
            fallback.data?.let { data ->
                data.seasons.forEach { item -> if (item.seasonId > 0) seasons.putIfAbsent(item.seasonId, item) }
                data.series.forEach { item -> if (item.seriesId > 0) series.putIfAbsent(item.seriesId, item) }
            }
        }
        AppLog.i(
            "UserRepository",
            "seasons_series all mid=$mid pages=$fetchedPages total=$total " +
                "seasons=${seasons.size} series=${series.size}"
        )
        SeasonsSeriesBundle(seasons.values.toList(), series.values.toList(), total)
    }

    suspend fun getSeriesArchives(
        mid: Long,
        seriesId: Long,
        page: Int,
        pageSize: Int = 20,
        sortDesc: Boolean = true
    ): Result<BaseResponse<UserDynamicResponse>> =
        runCatching {
            sessionGateway.syncAuthState(
                apiService.getSeriesArchives(mid, seriesId, page, pageSize, if (sortDesc) "desc" else "asc"),
                source = "getSeriesArchives"
            )
        }

    suspend fun getSeasonArchives(
        mid: Long,
        seasonId: Long,
        page: Int,
        pageSize: Int = 20,
        sortDesc: Boolean = true
    ): Result<BaseResponse<SeasonArchivesResponse>> =
        runCatching {
            sessionGateway.syncAuthState(
                // sort_reverse 语义：true=倒序（最新在前），false=正序（最老在前，合集默认从第1集开始）
                apiService.getSeasonArchives(mid, seasonId, page = page, pageSize = pageSize, sortReverse = sortDesc),
                source = "getSeasonArchives"
            )
        }

    suspend fun refreshCurrentUserInfo(): Result<UserDetailInfoModel> =
        com.mytvb.core.common.coroutine.catchingRequest {
            val requestSession = com.mytvb.network.session.RequestSessionScope.capture()
            val response = apiService.getUserDetailInfo()
            com.mytvb.network.session.RequestSessionScope.requireCurrent(requestSession)
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

/** 合集/系列列表单页条数。接口硬上限 20，>20 返回 -400。 */
private const val SEASONS_SERIES_PAGE_SIZE = 20

/** 合集/系列翻页聚合的页数保护上限（20 页 × 20 条 = 400 条，远超正常 UP 主的合集数）。 */
private const val SEASONS_SERIES_MAX_PAGES = 20
