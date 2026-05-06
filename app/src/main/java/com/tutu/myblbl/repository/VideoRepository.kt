package com.tutu.myblbl.repository

import com.tutu.myblbl.model.BaseResponse
import com.tutu.myblbl.model.common.ArchiveRelationModel
import com.tutu.myblbl.model.common.CheckGiveCoinModel
import com.tutu.myblbl.model.common.GiveCoinResultModel
import com.tutu.myblbl.model.common.TripleActionResultModel
import com.tutu.myblbl.model.recommend.RecommendListDataModel
import com.tutu.myblbl.model.video.VideoModel
import com.tutu.myblbl.model.video.detail.VideoDetailModel
import com.tutu.myblbl.network.response.BaseBaseResponse
import com.tutu.myblbl.network.session.NetworkSessionGateway
import com.tutu.myblbl.repository.remote.VideoRepository as NetworkVideoRepository

data class ChannelVideoPage(
    val videos: List<VideoModel> = emptyList(),
    val offset: String = "",
    val hasMore: Boolean = false
)

class VideoRepository(
    private val delegate: NetworkVideoRepository,
    private val sessionGateway: NetworkSessionGateway
) {

    private companion object {
        private const val TAG = "VideoRepository"
    }

    suspend fun getRecommendList(
        freshIdx: Int,
        pageSize: Int
    ): BaseResponse<RecommendListDataModel<VideoModel>> {
        return delegate.getRecommendList(freshIdx, pageSize).getOrThrow()
    }

    suspend fun getHotList(page: Int, pageSize: Int): BaseResponse<List<VideoModel>> {
        return delegate.getHotList(page, pageSize).getOrThrow()
    }

    suspend fun getRanking(rid: Int): BaseResponse<List<VideoModel>> {
        return delegate.getRanking(rid).getOrThrow()
    }

    suspend fun getVideoDetail(avid: Long?, bvid: String?): BaseResponse<VideoDetailModel> {
        return delegate.getVideoDetail(avid, bvid).getOrThrow()
    }

    suspend fun like(avid: Long?, bvid: String?, like: Int): BaseResponse<Int> {
        val csrf = sessionGateway.requireCsrfToken()
            ?: return BaseResponse(code = -111, message = "csrf token is blank")
        return delegate.like(avid, bvid, like, csrf).getOrThrow()
    }

    suspend fun hasLike(avid: Long?, bvid: String?): BaseResponse<Int> {
        return delegate.hasLike(avid, bvid).getOrThrow()
    }

    suspend fun getArchiveRelation(aid: Long?, bvid: String?): BaseResponse<ArchiveRelationModel> {
        return delegate.getArchiveRelation(aid, bvid).getOrThrow()
    }

    suspend fun giveCoin(
        avid: Long?,
        bvid: String?,
        multiply: Int,
        selectLike: Int = 0
    ): BaseResponse<GiveCoinResultModel> {
        val csrf = sessionGateway.requireCsrfToken()
            ?: return BaseResponse(code = -111, message = "csrf token is blank")
        return delegate.giveCoin(avid, bvid, multiply, selectLike, csrf).getOrThrow()
    }

    suspend fun hasGiveCoin(avid: Long?, bvid: String?): BaseResponse<CheckGiveCoinModel> {
        return delegate.hasGiveCoin(avid, bvid).getOrThrow()
    }

    suspend fun tripleAction(avid: Long?, bvid: String?): BaseResponse<TripleActionResultModel> {
        val csrf = sessionGateway.requireCsrfToken()
            ?: return BaseResponse(code = -111, message = "csrf token is blank")
        return delegate.tripleAction(avid, bvid, csrf).getOrThrow()
    }

    suspend fun getChannelVideos(
        channelId: Long,
        offset: String,
        pageSize: Int = 30
    ): Result<ChannelVideoPage> {
        return delegate.getVideoByChannel(channelId, offset, pageSize).map { response ->
            val data = response.data
            ChannelVideoPage(
                videos = data?.list.orEmpty().map { it.toVideoModel() },
                offset = data?.offset.orEmpty(),
                hasMore = data?.hasMore == true
            )
        }
    }

    suspend fun addWatchLater(aid: Long?, bvid: String? = null): BaseBaseResponse {
        val csrf = sessionGateway.requireCsrfToken()
            ?: return BaseBaseResponse(code = -111, message = "csrf token is blank")
        return delegate.addWatchLater(aid, bvid, csrf).getOrThrow()
    }

    suspend fun removeWatchLater(aid: Long?, bvid: String? = null): BaseBaseResponse {
        val csrf = sessionGateway.requireCsrfToken()
            ?: return BaseBaseResponse(code = -111, message = "csrf token is blank")
        return delegate.removeWatchLater(aid, bvid, csrf).getOrThrow()
    }

    suspend fun checkWatchLater(aid: Long?, bvid: String? = null): Boolean {
        return runCatching {
            delegate.checkWatchLater(aid, bvid).getOrThrow()
        }.getOrDefault(false)
    }

    suspend fun dislikeFeed(video: VideoModel, reasonId: Int): BaseBaseResponse {
        val csrf = sessionGateway.requireCsrfToken()
            ?: return BaseBaseResponse(code = -111, message = "csrf token is blank")
        return delegate.dislikeFeed(video, reasonId, csrf).getOrThrow()
    }
}
