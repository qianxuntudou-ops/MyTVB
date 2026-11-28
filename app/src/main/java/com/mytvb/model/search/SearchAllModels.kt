package com.mytvb.model.search

import androidx.annotation.StringRes
import androidx.core.text.HtmlCompat
import com.google.gson.annotations.JsonAdapter
import com.google.gson.annotations.SerializedName
import com.mytvb.R
import com.mytvb.model.adapter.FlexibleBooleanAdapter
import com.mytvb.model.user.OfficialVerifySimple
import com.mytvb.model.video.Dimension
import java.io.Serializable

data class SearchAllResponseData(
    @SerializedName("pageinfo")
    val pageinfo: SearchAllCountWrapper? = null,
    @SerializedName("result")
    val result: List<SearchAllResult>? = null,
    @SerializedName("seid")
    val seid: String = "",
    @SerializedName("show_module_list")
    val showModuleList: List<String>? = null
) : Serializable

data class SearchAllResult(
    @SerializedName("data")
    val data: List<SearchItemModel>? = null,
    @SerializedName("result_type")
    val resultType: String = ""
) : Serializable

data class SearchAllCountWrapper(
    @SerializedName("video")
    val video: SearchAllCount? = null,
    @SerializedName("bili_user")
    val biliUser: SearchAllCount? = null,
    @SerializedName("live_room")
    val liveRoom: SearchAllCount? = null,
    @SerializedName("media_bangumi")
    val mediaBangumi: SearchAllCount? = null,
    @SerializedName("media_ft")
    val mediaFt: SearchAllCount? = null
) : Serializable

data class SearchAllCount(
    @SerializedName("numResults")
    val numResults: Int = 0,
    @SerializedName("pages")
    val pages: Int = 0,
    @SerializedName("total")
    val total: Int = 0
) : Serializable

enum class SearchType(
    val value: String,
    @StringRes val titleRes: Int
) {
    Video("video", R.string.video),
    Animation("media_bangumi", R.string.animation),
    FilmAndTv("media_ft", R.string.film_and_television),
    LiveRoom("live_room", R.string.live),
    User("bili_user", R.string.user);

    companion object {
        fun fromValue(value: String): SearchType? = values().firstOrNull { it.value == value }
    }
}

data class SearchCategoryItem(
    val type: SearchType,
    val showText: String
)

data class SearchResponseWrapper(
    @SerializedName("numPages")
    val numPages: Int = 0,
    @SerializedName("numResults")
    val numResults: Long = 0,
    @SerializedName("page")
    val page: Int = 0,
    @SerializedName("pagesize")
    val pageSize: Int = 0,
    @SerializedName("result")
    val result: List<SearchItemModel>? = null
) : Serializable

data class SearchItemModel(
    @SerializedName("type")
    val type: String = "",
    @SerializedName("id")
    val id: Long = 0,
    @SerializedName("aid")
    val aid: Long = 0,
    @SerializedName("bvid")
    val bvid: String = "",
    @SerializedName("title")
    val title: String = "",
    @SerializedName("pic")
    val pic: String = "",
    @SerializedName("cover")
    val cover: String = "",
    @SerializedName("author")
    val author: String = "",
    @SerializedName("uname")
    val uname: String = "",
    @SerializedName("mid")
    val mid: String = "",
    @SerializedName("upic")
    val upic: String = "",
    @SerializedName("usign")
    val usign: String = "",
    @SerializedName("fans")
    val fans: Long = 0,
    @SerializedName("videos")
    val videos: Long = 0,
    @SerializedName("level")
    val level: Int = 0,
    @SerializedName("play")
    val play: Long = 0,
    @SerializedName("danmaku")
    val danmaku: Long = 0,
    @SerializedName("online")
    val online: Long = 0,
    @SerializedName("duration")
    val duration: String = "",
    @SerializedName("pubdate")
    val pubDate: Long = 0,
    @SerializedName("desc")
    val desc: String = "",
    @SerializedName("index_show")
    val indexShow: String = "",
    @SerializedName("media_id")
    val mediaId: Long = 0,
    @SerializedName("pgc_season_id")
    val pgcSeasonId: Long = 0,
    @SerializedName("roomid")
    val roomId: Long = 0,

    @SerializedName("official_verify")
    val officialVerify: OfficialVerifySimple? = null,

    /** 联合投稿（合作视频，issue#157）：搜索接口原生下发 is_union_video(0/1)，封面打「合作」胶囊标 */
    @SerializedName("is_union_video")
    @JsonAdapter(FlexibleBooleanAdapter::class)
    val isUnionVideo: Boolean = false,

    @SerializedName("dimension")
    val dimension: Dimension? = null
) : Serializable {

    private var _decodedTitle: String? = null

    val decodedTitle: String
        get() = _decodedTitle ?: run {
            val result = if (title.contains('<') || title.contains('&')) {
                HtmlCompat.fromHtml(title, HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
            } else {
                title
            }
            _decodedTitle = result
            result
        }
}
