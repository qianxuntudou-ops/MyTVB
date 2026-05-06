package com.tutu.myblbl.repository.remote

import com.tutu.myblbl.model.BaseResponse
import com.tutu.myblbl.model.common.ArchiveRelationModel
import com.tutu.myblbl.model.common.CheckGiveCoinModel
import com.tutu.myblbl.model.common.GiveCoinResultModel
import com.tutu.myblbl.model.common.TripleActionResultModel
import com.tutu.myblbl.model.recommend.RecommendListDataModel
import com.tutu.myblbl.model.video.GetVideoByChannelWrapper
import com.tutu.myblbl.model.video.VideoModel
import com.tutu.myblbl.model.video.detail.VideoDetailModel
import com.tutu.myblbl.network.api.ApiService
import com.tutu.myblbl.network.WbiGenerator
import com.tutu.myblbl.network.response.BaseBaseResponse
import com.tutu.myblbl.network.security.NetworkSecurityGateway
import com.tutu.myblbl.network.session.NetworkSessionGateway
import com.tutu.myblbl.network.response.ListDataModel
import com.tutu.myblbl.core.common.log.AppLog
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class VideoRepository(
    private val apiService: ApiService,
    private val sessionGateway: NetworkSessionGateway,
    private val securityGateway: NetworkSecurityGateway
) {

    @Volatile
    private var watchLaterCache: List<VideoModel>? = null
    @Volatile
    private var watchLaterCacheTimeMs: Long = 0L
    private val watchLaterCacheTtlMs = 5L * 60L * 1000L
    private val watchLaterMutex = Mutex()

    private fun invalidateWatchLaterCache() {
        watchLaterCache = null
        watchLaterCacheTimeMs = 0L
    }

    private suspend fun getWatchLaterList(): List<VideoModel> = watchLaterMutex.withLock {
        val now = System.currentTimeMillis()
        val cached = watchLaterCache
        if (cached != null && now - watchLaterCacheTimeMs < watchLaterCacheTtlMs) {
            return@withLock cached
        }
        val response = sessionGateway.syncAuthState(
            apiService.getLaterWatch(),
            source = "video.getWatchLaterList"
        )
        val list = if (response.isSuccess) response.data?.list.orEmpty() else emptyList()
        watchLaterCache = list
        watchLaterCacheTimeMs = now
        return@withLock list
    }

    companion object {
        private const val TAG = "VideoRepository"
        private const val FEEDBACK_APP_ID = "100"
        private const val FEEDBACK_PLATFORM = "5"
        private const val FEEDBACK_SPMID = "333.1007.0.0"
        private const val FEEDBACK_PAGE = "1"
        private const val WEB_ACTION_SPMID = "333.788.0.0"
        private const val WEB_ACTION_FROM_SPMID = "333.1007.tianma.1-2-2.click"
        private const val WEB_ACTION_STATISTICS = "{\"appId\":100,\"platform\":5}"
        private const val WEB_ACTION_SOURCE = "web_normal"
    }

    suspend fun getRecommendList(
        freshIdx: Int,
        pageSize: Int
    ): Result<BaseResponse<RecommendListDataModel<VideoModel>>> =
        runCatching {
            apiService.getRecommendList(
                freshIdx = freshIdx.coerceAtLeast(1),
                ps = pageSize
            )
        }

    suspend fun getHotList(page: Int, pageSize: Int): Result<BaseResponse<List<VideoModel>>> =
        runCatching {
            apiService.getHotList(page, pageSize).mapListData()
        }

    suspend fun getRanking(rid: Int): Result<BaseResponse<List<VideoModel>>> =
        runCatching {
            sessionGateway.executeWithRiskControlRetry(
                key = "ranking_$rid",
                source = "video.getRanking"
            ) {
                apiService.getRanking(rid)
            }.mapListData()
        }

    suspend fun getVideoByChannel(
        channelId: Long,
        offset: String,
        pageSize: Int = 30
    ): Result<BaseResponse<GetVideoByChannelWrapper>> =
        runCatching {
            apiService.getVideoByChannel(
                channelId = channelId,
                offset = offset,
                pageSize = pageSize
            )
        }

    suspend fun getVideoDetail(aid: Long?, bvid: String?): Result<BaseResponse<VideoDetailModel>> =
        runCatching {
            val first = apiService.getVideoDetail(aid, bvid)
            if (first.isSuccess || bvid?.isNotBlank() == true) {
                first
            } else {
                // bvid 为空且 detail 接口失败时，用 /view?aid= 回退
                val validAid = aid?.takeIf { it > 0L }
                if (validAid != null) {
                    AppLog.i(TAG, "getVideoDetail: /view/detail failed(${first.code}), fallback /view?aid=$validAid")
                    apiService.getVideoDetailByAid(validAid)
                } else {
                    first
                }
            }
        }

    suspend fun like(avid: Long?, bvid: String?, like: Int, csrf: String): Result<BaseResponse<Int>> =
        runCatching {
            securityGateway.ensureHealthyForPlay()
            val firstResponse: BaseResponse<Int> = sessionGateway.syncAuthState(
                apiService.like(
                    buildWebActionForm(avid, bvid, csrf, ramval = 3).apply {
                        put("like", like.toString())
                    }
                ),
                source = "video.like"
            )
            if (!sessionGateway.isRetryableError(firstResponse.code, firstResponse.message)) {
                firstResponse
            } else {
                sessionGateway.getCooldownManager().recordFailure("like_${avid ?: bvid}", firstResponse.code)
                val cooldownMs = sessionGateway.getCooldownManager().checkCooldown("like_${avid ?: bvid}")
                if (cooldownMs > 0) kotlinx.coroutines.delay(cooldownMs)
                sessionGateway.prewarmWebSession(forceUaRefresh = true)
                val freshCsrf = sessionGateway.requireCsrfToken() ?: csrf
                sessionGateway.syncAuthState(
                    apiService.like(
                        buildWebActionForm(avid, bvid, freshCsrf, ramval = 3).apply {
                            put("like", like.toString())
                        }
                    ),
                    source = "video.like.retry"
                )
            }
        }

    suspend fun hasLike(avid: Long?, bvid: String?): Result<BaseResponse<Int>> =
        runCatching {
            sessionGateway.syncAuthState(
                apiService.hasLike(avid, bvid),
                source = "video.hasLike"
            )
        }

    suspend fun getArchiveRelation(aid: Long?, bvid: String?): Result<BaseResponse<ArchiveRelationModel>> =
        runCatching {
            sessionGateway.syncAuthState(
                apiService.getArchiveRelation(aid, bvid),
                source = "video.getArchiveRelation"
            )
        }

    suspend fun giveCoin(avid: Long?, bvid: String?, multiply: Int, selectLike: Int, csrf: String): Result<BaseResponse<GiveCoinResultModel>> =
        runCatching {
            securityGateway.ensureHealthyForPlay()
            val firstResponse: BaseResponse<GiveCoinResultModel> = sessionGateway.syncAuthState(
                apiService.giveCoin(
                    buildWebActionForm(avid, bvid, csrf, ramval = 6).apply {
                        put("multiply", multiply.coerceIn(1, 2).toString())
                        put("select_like", selectLike.coerceIn(0, 1).toString())
                        put("cross_domain", "true")
                    }
                ),
                source = "video.giveCoin"
            )
            if (!sessionGateway.isRetryableError(firstResponse.code, firstResponse.message)) {
                firstResponse
            } else {
                sessionGateway.getCooldownManager().recordFailure("coin_${avid ?: bvid}", firstResponse.code)
                val cooldownMs = sessionGateway.getCooldownManager().checkCooldown("coin_${avid ?: bvid}")
                if (cooldownMs > 0) kotlinx.coroutines.delay(cooldownMs)
                sessionGateway.prewarmWebSession(forceUaRefresh = true)
                val freshCsrf = sessionGateway.requireCsrfToken() ?: csrf
                sessionGateway.syncAuthState(
                    apiService.giveCoin(
                        buildWebActionForm(avid, bvid, freshCsrf, ramval = 6).apply {
                            put("multiply", multiply.coerceIn(1, 2).toString())
                            put("select_like", selectLike.coerceIn(0, 1).toString())
                            put("cross_domain", "true")
                        }
                    ),
                    source = "video.giveCoin.retry"
                )
            }
        }

    suspend fun hasGiveCoin(avid: Long?, bvid: String?): Result<BaseResponse<CheckGiveCoinModel>> =
        runCatching {
            sessionGateway.syncAuthState(
                apiService.hasGiveCoin(avid, bvid),
                source = "video.hasGiveCoin"
            )
        }

    suspend fun tripleAction(avid: Long?, bvid: String?, csrf: String): Result<BaseResponse<TripleActionResultModel>> =
        runCatching {
            securityGateway.ensureHealthyForPlay()
            val firstResponse: BaseResponse<TripleActionResultModel> = sessionGateway.syncAuthState(
                apiService.tripleAction(buildWebActionForm(avid, bvid, csrf, ramval = 6)),
                source = "video.tripleAction"
            )
            if (!sessionGateway.isRetryableError(firstResponse.code, firstResponse.message)) {
                firstResponse
            } else {
                sessionGateway.getCooldownManager().recordFailure("triple_${avid ?: bvid}", firstResponse.code)
                val cooldownMs = sessionGateway.getCooldownManager().checkCooldown("triple_${avid ?: bvid}")
                if (cooldownMs > 0) kotlinx.coroutines.delay(cooldownMs)
                sessionGateway.prewarmWebSession(forceUaRefresh = true)
                val freshCsrf = sessionGateway.requireCsrfToken() ?: csrf
                sessionGateway.syncAuthState(
                    apiService.tripleAction(buildWebActionForm(avid, bvid, freshCsrf, ramval = 6)),
                    source = "video.tripleAction.retry"
                )
            }
        }

    suspend fun addWatchLater(aid: Long?, bvid: String?, csrf: String): Result<BaseBaseResponse> =
        runCatching {
            securityGateway.ensureHealthyForPlay()
            val result = sessionGateway.syncAuthState(
                apiService.addWatchLater(aid, bvid, csrf),
                source = "video.addWatchLater"
            )
            if (result.isSuccess) invalidateWatchLaterCache()
            result
        }

    suspend fun removeWatchLater(aid: Long?, bvid: String?, csrf: String): Result<BaseBaseResponse> =
        runCatching {
            securityGateway.ensureHealthyForPlay()
            val resolvedAid = resolveWatchLaterAid(aid, bvid)
                ?: error("缺少稍后再看视频标识")
            val result = sessionGateway.syncAuthState(
                apiService.removeWatchLater(resolvedAid, csrf),
                source = "video.removeWatchLater"
            )
            if (result.isSuccess) invalidateWatchLaterCache()
            result
        }

    suspend fun checkWatchLater(aid: Long?, bvid: String?): Result<Boolean> =
        runCatching {
            val list = getWatchLaterList()
            list.any { item ->
                matchesWatchLaterItem(item, aid, bvid)
            }
        }

    private suspend fun resolveWatchLaterAid(aid: Long?, bvid: String?): Long? {
        if ((aid ?: 0L) > 0L) {
            return aid
        }
        if (bvid.isNullOrBlank()) {
            return null
        }
        val list = getWatchLaterList()
        return list
            .firstOrNull { matchesWatchLaterItem(it, aid, bvid) }
            ?.aid
            ?.takeIf { it > 0L }
    }

    private fun matchesWatchLaterItem(item: VideoModel, aid: Long?, bvid: String?): Boolean {
        val targetAid = aid ?: 0L
        return when {
            targetAid > 0L && item.aid == targetAid -> true
            !bvid.isNullOrBlank() && item.bvid.equals(bvid, ignoreCase = true) -> true
            else -> false
        }
    }

    suspend fun dislikeFeed(video: VideoModel, reasonId: Int, csrf: String): Result<BaseBaseResponse> =
        runCatching {
            securityGateway.ensureHealthyForPlay()
            sessionGateway.retryOnRiskControl(
                key = "dislike_${video.aid}_${video.bvid}",
                source = "video.dislikeFeed",
                getCode = { it.code },
                getMessage = { it.message },
                getIsSuccess = { it.isSuccess }
            ) {
                val params = buildFeedbackWbiParams()
                val form = buildDislikeForm(video = video, reasonId = reasonId, csrf = csrf)
                sessionGateway.syncAuthState(
                    apiService.dislikeFeed(params = params, form = form),
                    source = "video.dislikeFeed"
                )
            }
        }

    private fun BaseResponse<ListDataModel<VideoModel>>.mapListData(): BaseResponse<List<VideoModel>> {
        return BaseResponse(
            code = code,
            message = message,
            msg = msg,
            data = data?.list.orEmpty()
        )
    }

    private fun buildFeedbackWbiParams(): Map<String, String> {
        val (imgKey, subKey) = sessionGateway.getWbiKeys()
        return WbiGenerator.generateWbiParams(emptyMap(), imgKey, subKey)
    }

    private fun buildWebActionForm(
        aid: Long?,
        bvid: String?,
        csrf: String,
        ramval: Int
    ): LinkedHashMap<String, String> {
        return linkedMapOf<String, String>().apply {
            val normalizedAid = aid?.takeIf { it > 0L }
            if (normalizedAid != null) {
                put("aid", normalizedAid.toString())
            } else if (!bvid.isNullOrBlank()) {
                put("bvid", bvid)
            }
            put("from_spmid", WEB_ACTION_FROM_SPMID)
            put("spmid", WEB_ACTION_SPMID)
            put("statistics", WEB_ACTION_STATISTICS)
            put("eab_x", "1")
            put("ramval", ramval.toString())
            put("source", WEB_ACTION_SOURCE)
            put("ga", "1")
            put("csrf", csrf)
        }
    }

    private fun buildDislikeForm(
        video: VideoModel,
        reasonId: Int,
        csrf: String
    ): Map<String, String> {
        return linkedMapOf(
            "app_id" to FEEDBACK_APP_ID,
            "platform" to FEEDBACK_PLATFORM,
            "from_spmid" to "",
            "spmid" to FEEDBACK_SPMID,
            "goto" to video.goto.ifBlank { "av" },
            "id" to resolveFeedbackTargetId(video).toString(),
            "mid" to (video.owner?.mid ?: 0L).toString(),
            "track_id" to video.trackId,
            "feedback_page" to FEEDBACK_PAGE,
            "reason_id" to reasonId.toString(),
            "csrf" to csrf
        )
    }

    private fun resolveFeedbackTargetId(video: VideoModel): Long {
        return when {
            video.aid > 0L -> video.aid
            video.roomId > 0L -> video.roomId
            else -> 0L
        }
    }
}
