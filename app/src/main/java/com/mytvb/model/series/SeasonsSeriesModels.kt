package com.mytvb.model.series

import com.google.gson.annotations.SerializedName
import com.mytvb.model.video.VideoModel

/**
 * UP 主的合集（season）与系列（series，含直播回放自动系列）。
 *
 * 两个接口共用本结构：
 * - 旧版 x/polymer/web-space/seasons_series_list：page_num 真翻页，page.total 是总条数
 *   （seasons+series 合计，如 32）；单页 page_size 上限 20（更大 -400）。
 * - 新版 x/polymer/web-space/home/seasons_series：**忽略 page_num**（永远返回第一页），
 *   page.total 只是请求的 page_num 回显、不可当总数用 —— 合集超过 20 个就取不全，故不再走它。
 */
data class SeasonsSeriesListResponse(
    @SerializedName("items_lists")
    private val itemsLists: SeasonsSeriesItemsLists? = null
) {
    val seasons: List<SeasonsSeriesItem>
        get() = itemsLists?.seasons.orEmpty()

    val series: List<SeasonsSeriesItem>
        get() = itemsLists?.series.orEmpty()

    /** 旧版接口为 seasons+series 总条数；新版接口该值无意义（等于请求的 page_num）。 */
    val totalCount: Int
        get() = itemsLists?.page?.total ?: 0
}

/** 跨页聚合后的 UP 主全部合集与系列。 */
data class SeasonsSeriesBundle(
    val seasons: List<SeasonsSeriesItem>,
    val series: List<SeasonsSeriesItem>,
    /** 接口给出的总条数；0 表示接口未提供（不影响已聚合结果）。 */
    val totalCount: Int = 0
)

data class SeasonsSeriesItemsLists(
    @SerializedName("page")
    val page: SeasonsSeriesPage? = null,
    @SerializedName("seasons_list")
    val seasons: List<SeasonsSeriesItem> = emptyList(),
    @SerializedName("series_list")
    val series: List<SeasonsSeriesItem> = emptyList()
)

data class SeasonsSeriesPage(
    /** 旧版接口：seasons+series 总条数；新版接口为请求 page_num 的回显，不可用。 */
    @SerializedName("total")
    val total: Int = 0
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
