package com.mytvb.model.episode

import com.google.gson.annotations.SerializedName
import com.mytvb.model.series.BadgeInfoModel
import java.io.Serializable

data class EpisodeModel(
    @SerializedName("id")
    val id: Long = 0,
    @SerializedName("aid")
    val aid: Long = 0,
    @SerializedName("bvid")
    val bvid: String = "",
    @SerializedName("cid")
    val cid: Long = 0,
    @SerializedName("title")
    val title: String = "",
    @SerializedName("long_title")
    val longTitle: String = "",
    @SerializedName("cover")
    val cover: String = "",
    @SerializedName("badge")
    val badge: String = "",
    @SerializedName("badge_info")
    val badgeInfo: BadgeInfoModel? = null,
    @SerializedName("link")
    val link: String = "",
    @SerializedName("share_url")
    val shareUrl: String = "",
    @SerializedName("short_link")
    val shortLink: String = "",
    @SerializedName("subtitle")
    val subtitle: String = "",
    @SerializedName("vid")
    val vid: String = "",
    /**
     * 本集总时长，毫秒。pgc/view/web/season 的 episodes 每集下发
     * （实测 1451000 形态），详情页「上次观看」拼 当前/总 时长用。
     */
    @SerializedName("duration")
    val duration: Long = 0,
    @SerializedName("pub_time")
    val pubTime: Long = 0,
    /**
     * 0=正片，1=预告。pgc/view/web/season 的 episodes 会把预告集混进来；
     * pgc/web/season/section 的正片分区不下发该字段，保持默认 0。
     */
    @SerializedName("section_type")
    val sectionType: Int = 0
) : Serializable
