package com.mytvb.model.video

import com.google.gson.annotations.SerializedName

data class UserDynamicResponse(
    @SerializedName("archives")
    private val archivesData: List<VideoModel>? = null,
    @SerializedName("list")
    private val listData: UserDynamicList? = null,
    @SerializedName("page")
    private val page: UserDynamicPage? = null,
    @SerializedName("has_more")
    private val hasMoreData: Boolean? = null
){
    val archives: List<VideoModel>
        get() = archivesData ?: listData?.vlist.orEmpty()

    val hasMore: Boolean
        get() = hasMoreData ?: page?.let { it.pageSize > 0 && it.pageNumber * it.pageSize < it.totalCount } ?: false

    val totalCount: Int
        get() = page?.totalCount ?: archives.size
}

data class UserDynamicList(
    @SerializedName("vlist")
    val vlist: List<VideoModel> = emptyList()
)

// 兼容两个同构接口的分页字段：x/space/arc/list 用 count/pn/ps，x/series/archives 用 total/num/size
data class UserDynamicPage(
    @SerializedName(value = "count", alternate = ["total"])
    val totalCount: Int = 0,
    @SerializedName(value = "pn", alternate = ["num"])
    val pageNumber: Int = 1,
    @SerializedName(value = "ps", alternate = ["size"])
    val pageSize: Int = 0
)

data class AllDynamicResponse(
    @SerializedName("items")
    val items: List<VideoModel>? = null,
    @SerializedName("has_more")
    val hasMore: Boolean = false,
    @SerializedName("offset")
    val offset: Long = 0
)
