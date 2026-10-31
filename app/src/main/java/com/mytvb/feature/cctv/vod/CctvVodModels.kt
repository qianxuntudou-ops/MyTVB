package com.mytvb.feature.cctv.vod

/** CCTV 点播片库的分类（对应片库页顶部四个 tab）。 */
data class CctvVodCategory(
    val code: String,
    val title: String,
    val channelId: String,
    /** 分集接口的 mode 参数：电视剧/特别节目用 0，动画片/纪录片用 1（央视页面自身的选择）。 */
    val episodeMode: Int
)

/** 片库列表里的一部剧/一个专辑。 */
data class CctvAlbum(
    /** 专辑 id（分集接口入参）。 */
    val id: String,
    val title: String,
    val coverUrl: String,
    val brief: String,
    val episodeCount: Int,
    val area: String,
    val year: String,
    val genre: String,
    val actors: String,
    /** 分集接口的 mode 参数（随所属分类）。 */
    val episodeMode: Int
)

/** 单个分集。 */
data class CctvEpisode(
    val index: Int,
    val title: String,
    val coverUrl: String,
    /** 央视取流标识。 */
    val guid: String,
    /** 该集在央视站的详情页地址（官方网页播放器就挂在这个页面上）。 */
    val pageUrl: String
)

/** 取流结果。 */
data class CctvPlayInfo(
    val hlsUrl: String,
    /** h5e 加密版主清单（720P 四档）；原生播放模式用，WebView 模式不用。 */
    val h5eUrl: String = "",
    val title: String,
    /** master m3u8 里的清晰度档位（低→高）。为空表示只有单档，播放器不做清晰度菜单。 */
    val qualities: List<CctvQuality> = emptyList()
)

/** 一档清晰度。 */
data class CctvQuality(
    val width: Int,
    val height: Int,
    val bandwidth: Int,
    val url: String
)

object CctvVodCategories {
    val ALL: List<CctvVodCategory> = listOf(
        CctvVodCategory("dsj", "电视剧", "CHAL1460955853485115", episodeMode = 0),
        CctvVodCategory("dhp", "动画片", "CHAL1460955899450127", episodeMode = 1),
        CctvVodCategory("jlp", "纪录片", "CHAL1460955924871139", episodeMode = 1),
        CctvVodCategory("tbjm", "特别节目", "CHAL1460955953877151", episodeMode = 0)
    )
}

/** 筛选维度选项（与央视片库页一致）。 */
object CctvVodFilters {
    val ALL_LABEL = "全部"

    val GENRES = listOf(
        ALL_LABEL, "谍战", "悬疑", "刑侦", "历史", "古装", "武侠", "军旅", "战争",
        "喜剧", "青春", "言情", "偶像", "家庭", "年代", "革命", "农村", "都市", "其他"
    )

    val AREAS = listOf(ALL_LABEL, "内地（大陆）", "港澳台", "欧美", "其他")

    val LETTERS = listOf(ALL_LABEL) + ('A'..'Z').map { it.toString() }

    fun years(today: Int): List<String> =
        listOf(ALL_LABEL) + (today downTo (today - 29)).map { it.toString() }

    fun valueOf(label: String): String = if (label == ALL_LABEL) "" else label
}
