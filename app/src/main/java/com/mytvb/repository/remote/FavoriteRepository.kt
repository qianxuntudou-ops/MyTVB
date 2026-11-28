package com.mytvb.repository.remote

import com.mytvb.model.BaseResponse
import com.mytvb.model.common.CollectionResultModel
import com.mytvb.model.favorite.CheckFavoriteModel
import com.mytvb.model.favorite.FavoriteFolderDetailWrapper
import com.mytvb.model.favorite.FavoriteFoldersWrapper
import com.mytvb.model.favorite.FolderDetailModel
import com.mytvb.network.api.ApiService
import com.mytvb.network.security.NetworkSecurityGateway
import com.mytvb.network.session.NetworkSessionGateway

@Suppress("SpellCheckingInspection")
class FavoriteRepository(
    private val apiService: ApiService,
    private val sessionGateway: NetworkSessionGateway,
    private val securityGateway: NetworkSecurityGateway
) {

    private companion object {
        const val WEB_ACTION_SPMID = "333.788.0.0"
        const val WEB_ACTION_FROM_SPMID = "333.1007.tianma.1-2-2.click"
        const val WEB_ACTION_STATISTICS = "{\"appId\":100,\"platform\":5}"
    }

    @Suppress("unused")
    suspend fun checkFavorite(aid: Long?): Result<BaseResponse<CheckFavoriteModel>> =
        runCatching {
            sessionGateway.executeWithRiskControlRetry(
                key = "check_fav_$aid",
                source = "favorite.checkFavorite"
            ) {
                sessionGateway.syncAuthState(
                    apiService.checkFavorite(aid),
                    source = "favorite.checkFavorite"
                )
            }
        }

    suspend fun getFavoriteFolders(upMid: Long, rid: Long? = null): Result<BaseResponse<FavoriteFoldersWrapper>> =
        runCatching {
            sessionGateway.executeWithRiskControlRetry(
                key = "fav_folders_$upMid",
                source = "favorite.getFavoriteFolders"
            ) {
                sessionGateway.syncAuthState(
                    apiService.getFavoriteFolders(upMid, rid = rid),
                    source = "favorite.getFavoriteFolders"
                )
            }
        }

    suspend fun getFavoriteFolderInfo(mediaId: Long): Result<BaseResponse<FolderDetailModel>> =
        runCatching {
            sessionGateway.executeWithRiskControlRetry(
                key = "fav_info_$mediaId",
                source = "favorite.getFavoriteFolderInfo"
            ) {
                sessionGateway.syncAuthState(
                    apiService.getFavoriteFolderInfo(mediaId),
                    source = "favorite.getFavoriteFolderInfo"
                )
            }
        }

    suspend fun getFavoriteFolderDetail(
        mediaId: Long,
        page: Int,
        pageSize: Int
    ): Result<BaseResponse<FavoriteFolderDetailWrapper>> =
        runCatching {
            sessionGateway.executeWithRiskControlRetry(
                key = "fav_detail_${mediaId}_$page",
                source = "favorite.getFavoriteFolderDetail"
            ) {
                sessionGateway.syncAuthState(
                    apiService.getFavoriteFolderDetail(mediaId, page, pageSize),
                    source = "favorite.getFavoriteFolderDetail"
                )
            }
        }

    suspend fun addFavorite(rid: Long, addMediaIds: String): Result<BaseResponse<CollectionResultModel>> =
        updateFavorite(rid, addMediaIds = addMediaIds, delMediaIds = null)

    suspend fun removeFavorite(rid: Long, delMediaIds: String): Result<BaseResponse<CollectionResultModel>> =
        updateFavorite(rid, addMediaIds = null, delMediaIds = delMediaIds)

    /**
     * 一次请求完成加夹/移夹（换夹 = add 新夹 + del 旧夹），参数由 [com.mytvb.model.favorite.planFavoriteDeal] 计算。
     * 必须用视频真实所在的夹做 del 目标：issue#156 的旧实现拿「用户点的目标夹」当删除目标，
     * 服务端对「删除一个本来就不在的收藏」幂等返回成功，客户端误判成功、用户反复重试，
     * 短时间内连续 deal 触发收藏接口账号级风控（HTTP 412，约 1 小时解封）。
     */
    suspend fun updateFavorite(
        rid: Long,
        addMediaIds: String?,
        delMediaIds: String?
    ): Result<BaseResponse<CollectionResultModel>> {
        if (addMediaIds.isNullOrBlank() && delMediaIds.isNullOrBlank()) {
            return Result.success(BaseResponse(code = 0, message = "OK"))
        }
        return runCatching {
            securityGateway.ensureHealthyForPlay()
            val csrf = sessionGateway.requireCsrfToken()
                ?: return Result.success(BaseResponse(code = -111, message = "csrf token is blank"))
            sessionGateway.executeWithRiskControlRetry(
                key = "fav_deal_$rid",
                source = "favorite.updateFavorite"
            ) {
                sessionGateway.syncAuthState(
                    apiService.dealFavorite(buildFavoriteDealForm(
                        rid = rid,
                        addMediaIds = addMediaIds,
                        delMediaIds = delMediaIds,
                        csrf = csrf
                    )),
                    source = "favorite.updateFavorite"
                )
            }
        }
    }

    private fun buildFavoriteDealForm(
        rid: Long,
        addMediaIds: String?,
        delMediaIds: String?,
        csrf: String
    ): Map<String, String> {
        return linkedMapOf(
            "rid" to rid.toString(),
            "type" to "2",
            "add_media_ids" to addMediaIds.orEmpty(),
            "del_media_ids" to delMediaIds.orEmpty(),
            "platform" to "web",
            "from_spmid" to WEB_ACTION_FROM_SPMID,
            "spmid" to WEB_ACTION_SPMID,
            "statistics" to WEB_ACTION_STATISTICS,
            "csrf" to csrf
        )
    }
}
