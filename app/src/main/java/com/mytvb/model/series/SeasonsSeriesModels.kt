package com.mytvb.model.series

import com.google.gson.annotations.SerializedName
import com.mytvb.model.video.VideoModel

/**
 * x/polymer/web-space/seasons_series_list 返回：UP 主的合集（season）与系列（series，含直播回放自动系列）。
 * 注意 items_lists.page.total 是总页数，不是总条数。
 */
data class SeasonsSeriesListResponse(
    @SerializedName("items_lists")
    private val itemsLists: SeasonsSeriesItemsLists? = null
) {
    val seasons: List<SeasonsSeriesItem>
        get() = itemsLists?.seasons.orEmpty()

    val series: List<SeasonsSeriesItem>
        get() = itemsLists?.series.orEmpty()
}

data class SeasonsSeriesItemsLists(
    @SerializedName("page")
    val page: SeasonsSeriesPage? = null,
    @SerializedName("seasons_list")
    val seasons: List<SeasonsSeriesItem> = emptyList(),
    @SerializedName("series_list")
    val series: List<SeasonsSeriesItem> = emptyList()
)

data class SeasonsSeriesPage(
    @SerializedName("total")
    val totalPages: Int = 0
)

data class SeasonsSeriesItem(
    @SerializedName("meta")
    private val meta: SeasonsSeriesMeta? = null
) {
    val seasonId: Long get() = meta?.seasonId ?: 0L
    val seriesId: Long get() = meta?.seriesId ?: 0L
    val name: String get() = meta?.name.orEmpty()
    val total: Int get() = meta?.total ?: 0
}

data class SeasonsSeriesMeta(
    @SerializedName("season_id")
    val seasonId: Long = 0,
    @SerializedName("series_id")
    val seriesId: Long = 0,
    @SerializedName("name")
    val name: String = "",
    @SerializedName("total")
    val total: Int = 0
)

/**
 * x/polymer/web-space/seasons_archives_list：合集（season）下的视频列表。
 * 注意 page 只带 total（无 num/size/has_more），翻页由调用方按总数计算。
 */
data class SeasonArchivesResponse(
    @SerializedName("archives")
    val archives: List<VideoModel> = emptyList(),
    @SerializedName("page")
    private val page: SeasonArchivesPage? = null
) {
    val totalCount: Int get() = page?.total ?: archives.size
}

data class SeasonArchivesPage(
    @SerializedName("total")
    val total: Int = 0
)
