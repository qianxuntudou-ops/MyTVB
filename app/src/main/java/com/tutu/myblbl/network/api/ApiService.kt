package com.tutu.myblbl.network.api

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.tutu.myblbl.model.BaseResponse
import com.tutu.myblbl.model.common.CollectionResultModel
import com.tutu.myblbl.model.common.GiveCoinResultModel
import com.tutu.myblbl.model.common.ArchiveRelationModel
import com.tutu.myblbl.model.common.CheckGiveCoinModel
import com.tutu.myblbl.model.common.TripleActionResultModel
import com.tutu.myblbl.model.user.UserStatModel
import com.tutu.myblbl.model.user.UserSpaceInfo
import com.tutu.myblbl.model.user.UserDetailInfoModel
import com.tutu.myblbl.model.user.CheckRelationModel
import com.tutu.myblbl.model.user.GetFollowUserWrapper
import com.tutu.myblbl.model.user.ScanQrModel
import com.tutu.myblbl.model.user.SignInResultModel
import com.tutu.myblbl.model.user.SsoListModel
import com.tutu.myblbl.model.user.TvQrCodeData
import com.tutu.myblbl.model.user.TvPollData
import com.tutu.myblbl.model.video.VideoModel
import com.tutu.myblbl.model.video.detail.VideoDetailModel
import com.tutu.myblbl.model.video.VideoPvModel
import com.tutu.myblbl.model.video.HistoryListResponse
import com.tutu.myblbl.model.video.LaterWatchWrapper
import com.tutu.myblbl.model.video.RegionVideoListWrapper
import com.tutu.myblbl.model.video.UserDynamicResponse
import com.tutu.myblbl.model.video.AllDynamicResponse
import com.tutu.myblbl.model.video.GetVideoByChannelWrapper
import com.tutu.myblbl.model.player.PgcV2Result
import com.tutu.myblbl.model.player.PlayInfoModel
import com.tutu.myblbl.model.player.VideoSnapshotData
import com.tutu.myblbl.model.recommend.RecommendListDataModel
import com.tutu.myblbl.model.favorite.FavoriteFolderModel
import com.tutu.myblbl.model.favorite.CheckFavoriteModel
import com.tutu.myblbl.model.favorite.FavoriteFolderDetailWrapper
import com.tutu.myblbl.model.favorite.FavoriteFoldersWrapper
import com.tutu.myblbl.model.favorite.FolderDetailModel
import com.tutu.myblbl.model.live.*
import com.tutu.myblbl.model.interaction.InteractionInfo
import com.tutu.myblbl.model.interaction.InteractionModel
import com.tutu.myblbl.model.search.SearchAllResponseData
import com.tutu.myblbl.model.series.CheckUserSeriesResult
import com.tutu.myblbl.model.series.EpisodesDetailModel
import com.tutu.myblbl.model.series.FollowSeriesResult
import com.tutu.myblbl.model.series.MyFollowingResponseWrapper
import com.tutu.myblbl.model.series.RelatedRecommendResult
import com.tutu.myblbl.model.series.SeasonSectionResult
import com.tutu.myblbl.model.series.timeline.GetTimeLineWrapper
import com.tutu.myblbl.network.response.Base2Response
import com.tutu.myblbl.network.response.BaseBaseResponse
import com.tutu.myblbl.network.response.GetAllSeriesWrapper
import com.tutu.myblbl.network.response.GetLaneWrapper
import com.tutu.myblbl.network.response.ListDataModel
import com.tutu.myblbl.network.response.PlayerInfoDataWrapper
import okhttp3.ResponseBody
import retrofit2.http.*

interface ApiService {

    @GET("https://www.bilibili.com/")
    suspend fun getMainPage(): ResponseBody

    @GET("x/web-interface/nav")
    suspend fun getUserDetailInfo(): BaseResponse<UserDetailInfoModel>

    @GET("x/web-interface/nav/stat")
    suspend fun getUserStat(): BaseResponse<UserStatModel>

    @GET("x/relation/stat")
    suspend fun getRelationStat(
        @Query("vmid") vmid: Long
    ): BaseResponse<UserStatModel>

    @GET("x/relation/followings")
    suspend fun getFollowing(
        @Query("vmid") vmid: Long,
        @Query("pn") page: Int,
        @Query("ps") pageSize: Int
    ): BaseResponse<GetFollowUserWrapper>

    @GET("x/relation/followers")
    suspend fun getFollower(
        @Query("vmid") vmid: Long,
        @Query("pn") page: Int,
        @Query("ps") pageSize: Int
    ): BaseResponse<GetFollowUserWrapper>

    @GET("x/space/arc/list")
    suspend fun getUserDynamic(
        @Query("mid") mid: Long,
        @Query("pn") page: Int,
        @Query("ps") pageSize: Int
    ): BaseResponse<UserDynamicResponse>

    @GET("x/space/wbi/arc/search")
    suspend fun getUserArcSearch(
        @QueryMap params: Map<String, String>
    ): BaseResponse<UserDynamicResponse>

    @GET("x/polymer/web-dynamic/desktop/v1/feed/video")
    suspend fun getAllDynamic(
        @Query("page") page: Int,
        @Query("offset") offset: Long? = null
    ): BaseResponse<AllDynamicResponse>

    @GET("x/space/wbi/acc/info")
    suspend fun getUserSpace(@QueryMap params: Map<String, String>): BaseResponse<UserSpaceInfo>

    @GET("x/space/acc/info")
    suspend fun getUserSpaceNoWbi(@Query("mid") mid: Long): BaseResponse<UserSpaceInfo>

    @GET("x/web-interface/index/top/feed/rcmd")
    suspend fun getRecommendList(
        @Query("fresh_idx") freshIdx: Int,
        @Query("ps") ps: Int,
        @Query("feed_version") feedVersion: String = "V1",
        @Query("fresh_type") freshType: Int = 3,
        @Query("plat") plat: Int = 1
    ): BaseResponse<RecommendListDataModel<VideoModel>>

    @GET("x/web-interface/popular")
    suspend fun getHotList(
        @Query("pn") page: Int,
        @Query("ps") pageSize: Int
    ): BaseResponse<ListDataModel<VideoModel>>

    @GET("x/web-interface/ranking/v2")
    suspend fun getRanking(
        @Query("rid") rid: Int,
        @Query("type") type: String = "all"
    ): BaseResponse<ListDataModel<VideoModel>>

    @GET("x/web-interface/dynamic/region")
    suspend fun getRegionLatestVideos(
        @Query("rid") rid: Int,
        @Query("pn") page: Int,
        @Query("ps") pageSize: Int
    ): BaseResponse<RegionVideoListWrapper>

    @GET("x/web-interface/view/detail")
    suspend fun getVideoDetail(
        @Query("avid") avid: Long?,
        @Query("bvid") bvid: String?
    ): BaseResponse<VideoDetailModel>

    @GET("x/web-interface/view")
    suspend fun getVideoDetailByAid(
        @Query("aid") aid: Long
    ): BaseResponse<VideoDetailModel>

    @GET("x/player/pagelist")
    suspend fun getVideoPv(
        @Query("avid") avid: Long?,
        @Query("bvid") bvid: String?
    ): BaseResponse<List<VideoPvModel>>

    @GET("x/player/playurl")
    suspend fun getVideoPlayInfo(
        @Query("avid") avid: Long?,
        @Query("bvid") bvid: String?,
        @Query("cid") cid: Long,
        @Query("qn") qn: Int = 80,
        @Query("fnval") fnval: Int = 4048,
        @Query("fourk") fourk: Int = 1,
        @Query("fnver") fnver: Int = 0,
        @Query("gaia_vtoken") gaiaVtoken: String? = null,
        @Query("try_look") tryLook: Int? = null
    ): BaseResponse<PlayInfoModel>

    @GET("x/player/wbi/playurl?voice_balance=1&gaia_source=pre-load&isGaiaAvoided=true")
    suspend fun getVideoPlayInfoWbi(
        @QueryMap params: Map<String, String>
    ): BaseResponse<PlayInfoModel>

    @GET("x/player/playurl")
    suspend fun getVideoPlayInfoTryLook(
        @Query("avid") avid: Long?,
        @Query("bvid") bvid: String?,
        @Query("cid") cid: Long,
        @Query("qn") qn: Int = 80,
        @Query("fnval") fnval: Int = 4048,
        @Query("fourk") fourk: Int = 1,
        @Query("fnver") fnver: Int = 0,
        @Query("try_look") tryLook: Int = 1
    ): BaseResponse<PlayInfoModel>

    @GET("x/player/wbi/playurl?voice_balance=1&gaia_source=pre-load&isGaiaAvoided=true")
    suspend fun getVideoPlayInfoWbiTryLook(
        @QueryMap params: Map<String, String>
    ): BaseResponse<PlayInfoModel>

    @GET("x/player/v2")
    suspend fun getPlayerInfo(
        @Query("aid") aid: Long?,
        @Query("bvid") bvid: String?,
        @Query("cid") cid: Long
    ): BaseResponse<PlayerInfoDataWrapper>

    @GET("x/player/videoshot")
    suspend fun getVideoSnapshot(
        @Query("aid") aid: Long?,
        @Query("bvid") bvid: String?,
        @Query("cid") cid: Long,
        @Query("index") index: Int = 1
    ): BaseResponse<VideoSnapshotData>

    @GET("x/player/wbi/v2")
    suspend fun getPlayerInfoWbi(
        @QueryMap params: Map<String, String>
    ): BaseResponse<PlayerInfoDataWrapper>

    @GET("x/web-interface/archive/related")
    suspend fun getRelated(
        @Query("avid") avid: Long?,
        @Query("bvid") bvid: String?
    ): BaseResponse<List<VideoModel>>

    @POST("x/click-interface/web/heartbeat")
    @FormUrlEncoded
    suspend fun playVideoHeartbeat(
        @FieldMap params: Map<String, String>
    ): BaseResponse<String>

    @POST("x/click-interface/web/heartbeat")
    @FormUrlEncoded
    suspend fun playVideoHeartbeatSigned(
        @QueryMap queryParams: Map<String, String>,
        @FieldMap params: Map<String, String>
    ): BaseResponse<String>

    @POST("x/click-interface/click/web/h5")
    @FormUrlEncoded
    suspend fun reportVideoClickH5(
        @QueryMap queryParams: Map<String, String>,
        @FieldMap params: Map<String, String>
    ): BaseResponse<String>

    @POST("x/web-interface/archive/like")
    @FormUrlEncoded
    suspend fun like(
        @FieldMap params: Map<String, String>
    ): BaseResponse<Int>

    @GET("x/web-interface/archive/has/like")
    suspend fun hasLike(
        @Query("avid") avid: Long?,
        @Query("bvid") bvid: String?
    ): BaseResponse<Int>

    @GET("x/web-interface/archive/relation")
    suspend fun getArchiveRelation(
        @Query("aid") aid: Long?,
        @Query("bvid") bvid: String?
    ): BaseResponse<ArchiveRelationModel>

    @POST("x/web-interface/coin/add")
    @FormUrlEncoded
    suspend fun giveCoin(
        @FieldMap params: Map<String, String>
    ): BaseResponse<GiveCoinResultModel>

    @GET("x/web-interface/archive/coins")
    suspend fun hasGiveCoin(
        @Query("avid") avid: Long?,
        @Query("bvid") bvid: String?
    ): BaseResponse<CheckGiveCoinModel>

    @POST("x/web-interface/archive/like/triple")
    @FormUrlEncoded
    suspend fun tripleAction(
        @FieldMap params: Map<String, String>
    ): BaseResponse<TripleActionResultModel>

    @GET("x/web-interface/search/all/v2")
    suspend fun searchAll(
        @Query("keyword") keyword: String,
        @Query("platform") platform: String = "pc",
        @Query("highlight") highlight: Int = 1,
        @Query("page_size") pageSize: Int = 20,
        @Query("page") page: Int = 1
    ): BaseResponse<SearchAllResponseData>

    @GET("x/web-interface/wbi/search/all/v2")
    suspend fun searchAllWbi(
        @QueryMap params: Map<String, String>
    ): BaseResponse<SearchAllResponseData>

    @GET("x/web-interface/history/cursor")
    suspend fun getHistory(
        @Query("view_at") viewAt: Long,
        @Query("ps") ps: Int = 20
    ): BaseResponse<HistoryListResponse>

    @POST("x/web-interface/feedback/dislike")
    @FormUrlEncoded
    suspend fun dislikeFeed(
        @QueryMap params: Map<String, String>,
        @FieldMap form: Map<String, String>
    ): BaseBaseResponse

    @GET("x/v2/history/toview")
    suspend fun getLaterWatch(): BaseResponse<LaterWatchWrapper>

    @POST("x/v2/history/toview/add")
    @FormUrlEncoded
    suspend fun addWatchLater(
        @Field("aid") aid: Long?,
        @Field("bvid") bvid: String?,
        @Field("csrf") csrf: String
    ): BaseBaseResponse

    @POST("x/v2/history/toview/del")
    @FormUrlEncoded
    suspend fun removeWatchLater(
        @Field("aid") aid: Long,
        @Field("csrf") csrf: String
    ): BaseBaseResponse

    @POST("x/relation/modify")
    @FormUrlEncoded
    suspend fun userRelationModify(
        @FieldMap params: Map<String, String>
    ): BaseBaseResponse

    @GET("x/relation")
    suspend fun checkUserRelation(
        @Query("fid") fid: String
    ): BaseResponse<CheckRelationModel>

    @GET("https://passport.bilibili.com/x/passport-login/web/qrcode/generate")
    suspend fun getSignInQrCode(
        @Query("source") source: String = "main-fe-header",
        @Query("web_location") webLocation: String = "333.1007",
        @Query("x-bili-locale-json") localeJson: String = "{\"c_locale\":{\"language\":\"zh\",\"region\":\"CN\"},\"always_translate\":true}",
        @Query("go_url") goUrl: String = "https://www.bilibili.com/"
    ): BaseResponse<ScanQrModel>

    @GET("https://passport.bilibili.com/x/passport-login/web/qrcode/poll")
    suspend fun checkSignInResult(
        @Query("qrcode_key") qrcodeKey: String,
        @Query("source") source: String = "main-fe-header",
        @Query("web_location") webLocation: String = "333.1007",
        @Query("x-bili-locale-json") localeJson: String = "{\"c_locale\":{\"language\":\"zh\",\"region\":\"CN\"},\"always_translate\":true}",
        @Query("b_ret") bRet: String = ""
    ): BaseResponse<SignInResultModel>

    @FormUrlEncoded
    @POST("https://passport.bilibili.com/x/passport-login/web/sso/list")
    suspend fun getSsoList(
        @Field("csrf") csrf: String
    ): BaseResponse<SsoListModel>

    @POST
    suspend fun setSso(
        @Url url: String,
        @Query("x-bili-locale-json") localeJson: String = "{\"c_locale\":{\"language\":\"zh\",\"region\":\"CN\"},\"always_translate\":true}",
        @Query("b_ret") bRet: String = ""
    ): BaseResponse<Any>

    @POST("x/v3/fav/resource/deal")
    @FormUrlEncoded
    suspend fun dealFavorite(
        @FieldMap params: Map<String, String>
    ): BaseResponse<CollectionResultModel>

    @GET("x/v2/fav/video/favoured")
    suspend fun checkFavorite(
        @Query("aid") aid: Long?
    ): BaseResponse<CheckFavoriteModel>

    @GET("x/v3/fav/folder/created/list-all")
    suspend fun getFavoriteFolders(
        @Query("up_mid") upMid: Long,
        @Query("type") type: Int = 2,
        @Query("rid") rid: Long? = null
    ): BaseResponse<FavoriteFoldersWrapper>

    @GET("x/v3/fav/folder/info")
    suspend fun getFavoriteFolderInfo(
        @Query("media_id") mediaId: Long
    ): BaseResponse<FolderDetailModel>

    @GET("x/v3/fav/resource/list")
    suspend fun getFavoriteFolderDetail(
        @Query("media_id") mediaId: Long,
        @Query("pn") page: Int,
        @Query("ps") pageSize: Int,
        @Query("order") order: String = "mtime",
        @Query("type") type: Int = 0,
        @Query("platform") platform: String = "web",
        @Query("jsonp") jsonp: String = "jsonp"
    ): BaseResponse<FavoriteFolderDetailWrapper>

    // ==================== 直播模块 API ====================

    @GET("https://api.live.bilibili.com/room/v1/Area/getList")
    suspend fun getLiveAreaList(): BaseResponse<List<LiveAreaCategoryParent>>

    @GET("https://api.live.bilibili.com/room/v1/Area/getRoomList")
    suspend fun getLiveAreaRoomList(
        @Query("parent_area_id") parentAreaId: Long,
        @Query("area_id") areaId: Long,
        @Query("page") page: Int,
        @Query("page_size") pageSize: Int = 30,
        @Query("sort_type") sortType: String = "online"
    ): BaseResponse<List<LiveRoomItem>>

    @GET("https://api.live.bilibili.com/xlive/web-interface/v1/index/getWebAreaList")
    suspend fun getWebAreaList(
        @Query("source_id") sourceId: Int = 2
    ): BaseResponse<LiveWebAreaWrapper>

    @GET("https://api.live.bilibili.com/xlive/web-interface/v1/second/getList")
    suspend fun getLiveCategoryDetailListSigned(
        @QueryMap params: Map<String, String>
    ): BaseResponse<LiveCategoryDetailListWrapper>

    @GET("x/live/web-interface/v1/second/recommend")
    suspend fun getRecommendLive(
        @Query("page") page: Int,
        @Query("page_size") pageSize: Int
    ): BaseResponse<LiveListWrapper>

    @GET("https://api.live.bilibili.com/xlive/web-interface/v1/index/getList")
    suspend fun getLiveHomeList(
        @Query("platform") platform: String = "web"
    ): BaseResponse<LiveListWrapper>
    
    @GET("https://api.live.bilibili.com/room/v1/Room/get_info")
    suspend fun getLiveRoomInfo(
        @Query("room_id") roomId: Long
    ): BaseResponse<JsonObject>

    @GET("https://api.live.bilibili.com/xlive/web-room/v2/index/getRoomPlayInfo")
    suspend fun getLiveRoomPlayInfoV2(
        @QueryMap params: Map<String, String>
    ): BaseResponse<JsonObject>

    @GET("https://api.live.bilibili.com/xlive/web-room/v1/index/getDanmuInfo")
    suspend fun getLiveChatRoomUrl(
        @Query("id") id: Long
    ): BaseResponse<ChatRoomWrapper>

    @GET("https://api.live.bilibili.com/xlive/web-room/v1/index/getDanmuInfo")
    suspend fun getLiveDanmuInfoSigned(
        @QueryMap params: Map<String, String>
    ): BaseResponse<ChatRoomWrapper>

    @POST("https://api.live.bilibili.com/xlive/web-room/v1/index/roomEntryAction")
    @FormUrlEncoded
    suspend fun liveRoomEntryAction(
        @QueryMap queryParams: Map<String, String>,
        @Field("room_id") roomId: String,
        @Field("platform") platform: String = "pc"
    ): BaseResponse<String>

    @POST("https://live-trace.bilibili.com/xlive/data-interface/v1/x25Kn/E")
    suspend fun getLiveHeartbeatKey(
        @QueryMap params: Map<String, String>
    ): BaseResponse<JsonObject>

    @POST("https://live-trace.bilibili.com/xlive/data-interface/v1/x25Kn/X")
    suspend fun sendLiveHeartbeatX(
        @QueryMap params: Map<String, String>
    ): BaseResponse<JsonObject>

    @GET("https://api.live.bilibili.com/xlive/web-room/v1/index/getIpInfo")
    suspend fun getLiveIpInfo(): BaseResponse<JsonObject>

    @GET("https://api.live.bilibili.com/xlive/web-room/v1/index/getInfoByUser")
    suspend fun getLiveInfoByUser(
        @Query("room_id") roomId: Long
    ): BaseResponse<JsonObject>

    @GET("https://api.live.bilibili.com/xlive/web-room/v1/dM/gethistory")
    suspend fun getLiveDanmuHistory(
        @Query("roomid") roomId: Long,
        @Query("room_type") roomType: Int = 0
    ): BaseResponse<JsonObject>

    // ==================== 互动视频 API ====================
    
    @GET("x/stein/edgeinfo_v2")
    suspend fun getInteractionVideoInfo(
        @Query("bvid") bvid: String,
        @Query("aid") aid: Long,
        @Query("graph_version") graphVersion: Long,
        @Query("edge_id") edgeId: Long = 0
    ): BaseResponse<InteractionModel>

    // ==================== 番剧模块 API ====================
    
    @GET("pgc/view/web/season")
    suspend fun getVideoEpisodes(
        @Query("season_id") seasonId: Long?,
        @Query("ep_id") epId: Long?
    ): Base2Response<EpisodesDetailModel>

    @GET("pgc/season/episode/web/info")
    suspend fun getPgcEpisodeInfo(
        @Query("ep_id") epId: Long
    ): BaseResponse<JsonObject>

    @GET("pgc/view/web/season/user/status")
    suspend fun getSeriesUserStatus(
        @Query("season_id") seasonId: Long?,
        @Query("ep_id") epId: Long?,
        @Query("ts") ts: Long = System.currentTimeMillis()
    ): Base2Response<CheckUserSeriesResult>

    @GET("pgc/web/season/section")
    suspend fun getVideoEpisodeSections(
        @Query("season_id") seasonId: Long
    ): Base2Response<SeasonSectionResult>

    @GET("pgc/season/web/related/recommend")
    suspend fun getRelatedRecommend(
        @Query("season_id") seasonId: Long
    ): BaseResponse<RelatedRecommendResult>

    @GET("pgc/player/web/v2/playurl")
    suspend fun getVideoPlayPgcInfo(
        @QueryMap params: Map<String, String>
    ): Base2Response<PgcV2Result>

    @GET("pgc/page/pc/bangumi/tab")
    suspend fun getAnimations(
        @Query("is_refresh") isRefresh: Int = 0,
        @Query("cursor") cursor: Long = 0
    ): BaseResponse<GetLaneWrapper>

    @GET("pgc/page/pc/cinema/tab")
    suspend fun getCinema(
        @Query("is_refresh") isRefresh: Int = 0,
        @Query("cursor") cursor: Long = 0
    ): BaseResponse<GetLaneWrapper>

    @GET("pgc/season/index/result")
    suspend fun getAllSeries(
        @QueryMap params: Map<String, String>
    ): BaseResponse<GetAllSeriesWrapper>

    @GET("x/v2/dm/web/seg.so")
    suspend fun getVideoComment(
        @Query("type") type: Int = 1,
        @Query("oid") oid: Long,
        @Query("pid") pid: Long,
        @Query("segment_index") segmentIndex: Int
    ): ResponseBody

    @GET("x/v2/dm/web/view")
    suspend fun getVideoCommentView(
        @Query("type") type: Int = 1,
        @Query("oid") oid: Long,
        @Query("pid") pid: Long
    ): ResponseBody

    @GET("x/v2/dm/wbi/web/seg.so")
    suspend fun getVideoCommentWbi(
        @QueryMap params: Map<String, String>
    ): ResponseBody

    @GET("pgc/web/timeline")
    suspend fun getSeriesTimeLine(
        @Query("types") type: Int = 1,
        @Query("before") before: Int = 6,
        @Query("after") after: Int = 6
    ): GetTimeLineWrapper

    @GET("x/space/bangumi/follow/list")
    suspend fun getMyFollowingSeries(
        @Query("type") type: Int,
        @Query("follow_status") followStatus: Int = 0,
        @Query("pn") page: Int,
        @Query("ps") pageSize: Int,
        @Query("vmid") vmid: Long,
        @Query("ts") ts: Long
    ): BaseResponse<MyFollowingResponseWrapper>

    @POST("pgc/web/follow/add")
    @FormUrlEncoded
    suspend fun followSeries(
        @Field("season_id") seasonId: Long,
        @Field("csrf") csrf: String
    ): BaseBaseResponse

    @POST("pgc/web/follow/del")
    @FormUrlEncoded
    suspend fun cancelFollowSeries(
        @Field("season_id") seasonId: Long,
        @Field("csrf") csrf: String
    ): BaseBaseResponse

    @GET("x/web-interface/web/channel/featured/list")
    suspend fun getVideoByChannel(
        @Query("channel_id") channelId: Long,
        @Query("filter_type") filterType: Int = 0,
        @Query("offset") offset: String = "",
        @Query("page_size") pageSize: Int = 30
    ): BaseResponse<GetVideoByChannelWrapper>

    // ==================== TV 登录 API ====================

    @FormUrlEncoded
    @POST("https://passport.bilibili.com/x/passport-tv-login/qrcode/auth_code")
    suspend fun generateTvQrCode(@FieldMap params: Map<String, String>): BaseResponse<TvQrCodeData>

    @FormUrlEncoded
    @POST("https://passport.bilibili.com/x/passport-tv-login/qrcode/poll")
    suspend fun pollTvQrCode(@FieldMap params: Map<String, String>): BaseResponse<TvPollData>

    @FormUrlEncoded
    @POST("https://passport.bilibili.com/x/passport-tv-login/h5/refresh")
    suspend fun refreshTvToken(@FieldMap params: Map<String, String>): BaseResponse<TvPollData>

    // ==================== APP 播放 API（access_key 签名） ====================

    @GET("https://api.bilibili.com/x/player/playurl")
    suspend fun getPlayUrlApp(@QueryMap params: Map<String, String>): Base2Response<PlayInfoModel>
}
