package com.mytvb.repository.remote

import com.mytvb.core.common.coroutine.catchingRequest
import com.mytvb.model.BaseResponse
import com.mytvb.model.common.ArchiveRelationModel
import com.mytvb.model.common.CheckGiveCoinModel
import com.mytvb.model.common.GiveCoinResultModel
import com.mytvb.model.common.TripleActionResultModel
import com.mytvb.model.recommend.RecommendListDataModel
import com.mytvb.model.video.GetVideoByChannelWrapper
import com.mytvb.model.video.VideoModel
import com.mytvb.model.video.detail.VideoDetailModel
import com.mytvb.network.api.ApiService
import com.mytvb.network.WbiGenerator
import com.mytvb.network.response.BaseBaseResponse
import com.mytvb.network.security.NetworkSecurityGateway
import com.mytvb.network.session.NetworkSessionGateway
import com.mytvb.network.response.ListDataModel
import com.mytvb.core.common.log.AppLog
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class VideoRepository(
    private val apiService: ApiService,
    private val sessionGateway: NetworkSessionGateway,
    private val securityGateway: NetworkSecurityGateway
) {

    private val watchLaterMutex = Mutex()

    private suspend fun getWatchLaterList(): List<VideoModel> = watchLaterMutex.withLock {
        val response = sessionGateway.syncAuthState(
            apiService.getLaterWatch(),
            source = "video.getWatchLaterList"
        )
        return@withLock if (response.isSuccess) response.data?.list.orEmpty() else emptyList()
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
        catchingRequest {
            apiService.getRecommendList(
                freshIdx = freshIdx.coerceAtLeast(1),
                ps = pageSize
            )
        }

    suspend fun getHotList(page: Int, pageSize: Int): Result<BaseResponse<List<VideoModel>>> =
        catchingRequest {
            apiService.getHotList(page, pageSize).mapListData()
        }

    // 分类页 2026-11 切换官网频道推荐流（BiliApi.regionFeedRcmd，见 CategoryFeedRepository），
    // ranking/v2 链路（ApiService/门面/此处）随旧数据源一并移除


    suspend fun getVideoByChannel(
        channelId: Long,
        offset: String,
        pageSize: Int = 30
    ): Result<BaseResponse<GetVideoByChannelWrapper>> =
        catchingRequest {
            apiService.getVideoByChannel(
                channelId = channelId,
                offset = offset,
                pageSize = pageSize
            )
        }

    suspend fun getVideoDetail(aid: Long?, bvid: String?): Result<BaseResponse<VideoDetailModel>> =
        catchingRequest {
            com.mytvb.repository.cache.VideoMetadataCache.detail(aid, bvid) {
                val first = apiService.getVideoDetail(aid, bvid)
                if (first.isSuccess || bvid?.isNotBlank() == true) {
                    first
                } else {
                    // bvid 为空且 detail 接口失败时，用 /view?aid= 解析出真实 bvid，
                    // 再带 bvid 重走 view/detail 全量链（app 推荐流的超大 aid detail 接口不认）
                    val validAid = aid?.takeIf { it > 0L }
                    if (validAid != null) {
                        AppLog.i(TAG, "getVideoDetail: /view/detail failed(${first.code}), fallback /view?aid=$validAid")
                        val resolvedBvid = apiService.getVideoDetailByAid(validAid)
                            .data?.bvid?.takeIf { it.isNotBlank() }
                        if (resolvedBvid != null) {
                            apiService.getVideoDetail(validAid, resolvedBvid)
                        } else {
                            first
                        }
                    } else {
                        first
                    }
                }
            }
        }

    suspend fun like(avid: Long?, bvid: String?, like: Int, csrf: String): Result<BaseResponse<Int>> =
        catchingRequest {
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
        catchingRequest {
            sessionGateway.syncAuthState(
                apiService.hasLike(avid, bvid),
                source = "video.hasLike"
            )
        }

    suspend fun getArchiveRelation(aid: Long?, bvid: String?): Result<BaseResponse<ArchiveRelationModel>> =
        catchingRequest {
            sessionGateway.syncAuthState(
                apiService.getArchiveRelation(aid, bvid),
                source = "video.getArchiveRelation"
            )
        }

    suspend fun giveCoin(avid: Long?, bvid: String?, multiply: Int, selectLike: Int, csrf: String): Result<BaseResponse<GiveCoinResultModel>> =
        catchingRequest {
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
        catchingRequest {
            sessionGateway.syncAuthState(
                apiService.hasGiveCoin(avid, bvid),
                source = "video.hasGiveCoin"
            )
        }

    suspend fun tripleAction(avid: Long?, bvid: String?, csrf: String): Result<BaseResponse<TripleActionResultModel>> =
        catchingRequest {
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
        catchingRequest {
            securityGateway.ensureHealthyForPlay()
            val result = sessionGateway.syncAuthState(
                apiService.addWatchLater(aid, bvid, csrf),
                source = "video.addWatchLater"
            )
            result
        }

    suspend fun removeWatchLater(aid: Long?, bvid: String?, csrf: String): Result<BaseBaseResponse> =
        catchingRequest {
            securityGateway.ensureHealthyForPlay()
            val resolvedAid = resolveWatchLaterAid(aid, bvid)
                ?: error("缺少稍后再看视频标识")
            val result = sessionGateway.syncAuthState(
                apiService.removeWatchLater(resolvedAid, csrf),
                source = "video.removeWatchLater"
            )
            result
        }

    suspend fun deleteHistoryRecord(kid: String, csrf: String): Result<BaseBaseResponse> =
        catchingRequest {
            securityGateway.ensureHealthyForPlay()
            sessionGateway.syncAuthState(
                apiService.deleteHistoryRecord(kid, csrf),
                source = "video.deleteHistoryRecord"
            )
        }

    suspend fun checkWatchLater(aid: Long?, bvid: String?): Result<Boolean> =
        catchingRequest {
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
        catchingRequest {
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
