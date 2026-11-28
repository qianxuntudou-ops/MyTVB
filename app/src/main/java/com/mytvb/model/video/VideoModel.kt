@file:Suppress("SpellCheckingInspection")

package com.mytvb.model.video

import com.google.gson.annotations.JsonAdapter
import com.google.gson.annotations.SerializedName
import com.mytvb.model.adapter.FlexibleBooleanAdapter
import com.mytvb.model.series.UgcSeriesModel
import com.mytvb.model.user.OfficialVerifySimple
import org.json.JSONObject

data class VideoModel(
    // 不做 alternate=["id"]：PGC 系接口（season/section 的条目）同时下发 aid 与 id
    // （id=episode id），Gson 按出现顺序后者覆盖，会把真实 aid 覆盖成 ep id，
    // 导致花絮/PV 按 UGC 稿件回落播放时主键错误（issue#135）。
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
    
    @SerializedName(value = "desc", alternate = ["description"])
    val desc: String = "",
    
    @SerializedName("duration")
    val duration: Long = 0,

    @SerializedName("length")
    val length: String = "",

    @SerializedName(value = "pubdate", alternate = ["created"])
    val pubDate: Long = 0,
    
    @SerializedName("ctime")
    val createTime: Long = 0,
    
    @SerializedName("owner")
    val owner: Owner? = null,
    
    @SerializedName("stat")
    val stat: Stat? = null,

    @SerializedName("bangumi")
    val bangumi: Bangumi? = null,

    @SerializedName("pages")
    val pages: ArrayList<VideoPvModel>? = null,

    @SerializedName("ugc_season")
    val ugcSeason: UgcSeriesModel? = null,

    @SerializedName("play")
    val play: Long = 0,

    @SerializedName("video_review")
    val videoReview: Long = 0,
    
    @SerializedName("cid")
    val cid: Long = 0,

    @SerializedName("goto")
    val goto: String = "av",

    @SerializedName("track_id")
    val trackId: String = "",

    /** 推荐理由文案（官方挑的最显著互动数："4万点赞"/"1万投币"/"已关注"），卡片角标显示 */
    @SerializedName("rcmd_reason")
    val rcmdReason: String = "",

    /**
     * 动态页右上角标文案（issue#151）：单 UP 动态流条目的 badge.text（「投稿视频/
     * 动态视频」）。B 站不下发同名 JSON 键，由 polymer feed/space 映射层运行时赋值，
     * 其余接口条目恒为空串不显角标。
     */
    val dynamicBadgeText: String = "",
    
    @SerializedName("tname")
    val typeName: String = "",
    
    @SerializedName("tid")
    val typeId: Int = 0,
    
    @SerializedName("dynamic")
    val dynamicText: String = "",
    
    @SerializedName("dimension")
    val dimension: Dimension? = null,
    
    @SerializedName("is_live")
    @JsonAdapter(FlexibleBooleanAdapter::class)
    val isLive: Boolean = false,
    
    @SerializedName("room_id")
    val roomId: Long = 0,
    
    @SerializedName("is_followed")
    @JsonAdapter(FlexibleBooleanAdapter::class)
    val isFollowed: Boolean = false,
    
    @SerializedName("is_like")
    @JsonAdapter(FlexibleBooleanAdapter::class)
    val isLike: Boolean = false,
    
    @SerializedName("is_coin")
    @JsonAdapter(FlexibleBooleanAdapter::class)
    val isCoin: Boolean = false,
    
    @SerializedName("is_favorite")
    @JsonAdapter(FlexibleBooleanAdapter::class)
    val isFavorite: Boolean = false,

    @SerializedName(value = "epid", alternate = ["ep_id", "episode_id"])
    val epid: Long = 0,

    @SerializedName(value = "sid", alternate = ["season_id", "pgc_season_id"])
    val sid: Long = 0,

    @SerializedName("season_type")
    val seasonType: Int = 0,

    @SerializedName("is_ogv")
    @JsonAdapter(FlexibleBooleanAdapter::class)
    val isOgv: Boolean = false,

    @SerializedName(value = "redirect_url", alternate = ["uri", "link", "share_url"])
    val redirectUrl: String = "",

    @SerializedName("teenage_mode")
    val teenageMode: Int = 0,

    @SerializedName("progress")
    val historyProgress: Long = 0,
    val historyViewAt: Long = 0,
    val historyBadge: String = "",
    val historyBusiness: String = "",
    val historyDevice: Int = 0,
    val historyRecordKid: String = "",

    @SerializedName("rights")
    val rights: VideoRights? = null,

    @SerializedName("is_upower_exclusive")
    @JsonAdapter(FlexibleBooleanAdapter::class)
    val isUpowerExclusive: Boolean = false,

    @SerializedName("is_chargeable_season")
    @JsonAdapter(FlexibleBooleanAdapter::class)
    val isChargeableSeason: Boolean = false,

    @SerializedName("elec_arc_type")
    val elecArcType: Int = 0,

    @SerializedName("is_charging_arc")
    @JsonAdapter(FlexibleBooleanAdapter::class)
    val isChargingArc: Boolean = false,

    @SerializedName("is_steins_gate")
    @JsonAdapter(FlexibleBooleanAdapter::class)
    val isSteinsGate: Boolean = false,

    /**
     * 联合投稿（合作视频）标记（issue#157）。字段位置随接口而异，两路取或：
     * - 顶层：搜索（is_union_video）、UP 空间投稿（is_union_video）、关注动态
     *   （desktop feed/video，is_cooperation）；
     * - rights.is_cooperation：稍后观看（x/v2/history/toview）放在 rights 子对象里。
     * 首页推荐、热门、排行、相关推荐、历史、收藏、单 UP 动态流都没有该字段，恒
     * false 不显示角标——不做逐卡请求 view 换 staff 的兜底。
     */
    @SerializedName(value = "is_union_video", alternate = ["is_cooperation"])
    @JsonAdapter(FlexibleBooleanAdapter::class)
    private val isUnionVideoFlag: Boolean = false,

    @SerializedName("privilege_type")
    val privilegeType: Int = 0,

    @SerializedName("elec_arc_badge")
    val elecArcBadge: String = ""
) {
    private val _cachedIsChargingExclusive: Boolean by lazy {
        isUpowerExclusive || privilegeType > 0 || isChargingArc
                || elecArcType == 1 || elecArcBadge == "充电专属"
    }
    val isChargingExclusive: Boolean get() = _cachedIsChargingExclusive

    /** 联合投稿（合作视频）合并判定：顶层 flag（搜索/空间/关注动态）或 rights.is_cooperation（稍后观看） */
    val isUnionVideo: Boolean
        get() = isUnionVideoFlag || rights?.isCooperation == 1

    val isPortrait: Boolean
        get() = dimension?.isPortrait == true
    val coverUrl: String
        get() = pic.ifEmpty { cover }

    val effectiveCoverUrl: String
        get() = bangumi?.cover?.takeIf { it.isNotBlank() } ?: coverUrl

    val durationValue: Long
        get() = when {
            duration > 0 -> duration
            length.isNotBlank() -> parseDuration(length)
            else -> 0
        }

    val viewCount: Long
        get() = stat?.view ?: play

    val danmakuCount: Long
        get() = stat?.danmaku ?: videoReview

    val like: Long
        get() = stat?.like ?: 0L

    val coinCount: Long
        get() = stat?.coin ?: 0L

    val authorName: String
        get() = owner?.name ?: ""

    private val _cachedPlaybackSeasonId: Long by lazy { computePlaybackSeasonId() }
    val playbackSeasonId: Long get() = _cachedPlaybackSeasonId

    private val _cachedPlaybackEpId: Long by lazy { computePlaybackEpId() }
    val playbackEpId: Long get() = _cachedPlaybackEpId

    private val _cachedIsPgc: Boolean by lazy { playbackEpId > 0L || playbackSeasonId > 0L }
    val isPgc: Boolean get() = _cachedIsPgc

    val hasPlaybackIdentity: Boolean
        get() = aid > 0L || bvid.isNotBlank() || playbackEpId > 0L || playbackSeasonId > 0L

    val isSupportedHomeVideoCard: Boolean
        get() = unsupportedHomeVideoReasons().isEmpty()

    fun unsupportedHomeVideoReasons(): List<String> {
        val reasons = mutableListOf<String>()
        if (title.isBlank()) {
            reasons += "title_blank"
        }
        if (coverUrl.isBlank()) {
            reasons += "cover_blank"
        }
        if (isLive) {
            reasons += "is_live"
        }
        if (!hasPlaybackIdentity) {
            reasons += "missing_playback_identity"
        }
        if (goto.isNotBlank() && !goto.equals("av", ignoreCase = true) && !isPgc) {
            reasons += "goto=$goto"
        }
        return reasons
    }

    private fun computePlaybackSeasonId(): Long = when {
        sid <= 0L -> parseBangumiSeasonIdFromRedirectUrl()
        epid > 0L -> sid
        isOgv -> sid
        seasonType > 0 -> sid
        redirectUrl.contains("/bangumi/play/") -> sid
        else -> parseBangumiSeasonIdFromRedirectUrl()
    }

    private fun computePlaybackEpId(): Long = when {
        epid > 0L -> epid
        else -> parseBangumiEpIdFromRedirectUrl()
    }

    private fun parseDuration(value: String): Long {
        val parts = value.split(":").mapNotNull { it.toLongOrNull() }
        if (parts.isEmpty()) {
            return 0
        }
        return when (parts.size) {
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            2 -> parts[0] * 60 + parts[1]
            else -> parts[0]
        }
    }

    private fun parseBangumiSeasonIdFromRedirectUrl(): Long {
        val url = redirectUrl
        if (!url.contains("/bangumi/play/ss")) {
            return 0L
        }
        val seasonId = url.substringAfter("/bangumi/play/ss", "")
            .takeWhile { it.isDigit() }
        return seasonId.toLongOrNull() ?: 0L
    }

    private fun parseBangumiEpIdFromRedirectUrl(): Long {
        val url = redirectUrl
        if (!url.contains("/bangumi/play/ep")) {
            return 0L
        }
        val epId = url.substringAfter("/bangumi/play/ep", "")
            .takeWhile { it.isDigit() }
        return epId.toLongOrNull() ?: 0L
    }

    companion object {
        fun fromJson(obj: JSONObject): VideoModel {
            val statObj = obj.optJSONObject("stat")
            val ownerObj = obj.optJSONObject("owner")
            val bangumiObj = obj.optJSONObject("bangumi")
            val dimensionObj = obj.optJSONObject("dimension")
            val rightsObj = obj.optJSONObject("rights")
            return VideoModel(
                aid = obj.optLong("aid", obj.optLong("id", 0)),
                bvid = obj.optString("bvid", ""),
                title = obj.optString("title", ""),
                pic = obj.optString("pic", ""),
                cover = obj.optString("cover", ""),
                desc = obj.optString("desc", obj.optString("description", "")),
                duration = obj.optLong("duration", 0),
                length = obj.optString("length", ""),
                pubDate = obj.optLong("pubdate", obj.optLong("created", 0)),
                createTime = obj.optLong("ctime", 0),
                owner = ownerObj?.let { Owner.fromJson(it) },
                stat = statObj?.let { Stat.fromJson(it) },
                bangumi = bangumiObj?.let { Bangumi.fromJson(it) },
                play = obj.optLong("play", 0),
                videoReview = obj.optLong("video_review", 0),
                cid = obj.optLong("cid", 0),
                goto = obj.optString("goto", "av"),
                trackId = obj.optString("track_id", ""),
                // web 流为对象 {content}，app 流为纯字符串
                rcmdReason = obj.optJSONObject("rcmd_reason")?.optString("content", "")
                    ?: obj.optString("rcmd_reason", ""),
                typeName = obj.optString("tname", ""),
                typeId = obj.optInt("tid", 0),
                dynamicText = obj.optString("dynamic", ""),
                dimension = dimensionObj?.let { Dimension.fromJson(it) },
                isLive = obj.opt("is_live")?.let { parseFlexibleBool(it) } ?: false,
                roomId = obj.optLong("room_id", 0),
                isFollowed = obj.opt("is_followed")?.let { parseFlexibleBool(it) } ?: false,
                isLike = obj.opt("is_like")?.let { parseFlexibleBool(it) } ?: false,
                isCoin = obj.opt("is_coin")?.let { parseFlexibleBool(it) } ?: false,
                isFavorite = obj.opt("is_favorite")?.let { parseFlexibleBool(it) } ?: false,
                epid = obj.optLong("epid", obj.optLong("ep_id", obj.optLong("episode_id", 0))),
                sid = obj.optLong("sid", obj.optLong("season_id", obj.optLong("pgc_season_id", 0))),
                seasonType = obj.optInt("season_type", 0),
                isOgv = obj.opt("is_ogv")?.let { parseFlexibleBool(it) } ?: false,
                redirectUrl = obj.optString("redirect_url", obj.optString("uri", obj.optString("link", obj.optString("share_url", "")))),
                teenageMode = obj.optInt("teenage_mode", 0),
                historyProgress = obj.optLong("progress", 0),
                historyViewAt = obj.optLong("history_view_at", 0),
                historyBadge = obj.optString("history_badge", ""),
                historyBusiness = obj.optString("history_business", ""),
                historyDevice = obj.optInt("history_device", 0),
                historyRecordKid = obj.optString("history_record_kid", ""),
                rights = rightsObj?.let { VideoRights.fromJson(it) },
                isUpowerExclusive = obj.opt("is_upower_exclusive")?.let { parseFlexibleBool(it) } ?: false,
                isChargeableSeason = obj.opt("is_chargeable_season")?.let { parseFlexibleBool(it) } ?: false,
                elecArcType = obj.optInt("elec_arc_type", 0),
                isChargingArc = obj.opt("is_charging_arc")?.let { parseFlexibleBool(it) } ?: false,
                isSteinsGate = obj.opt("is_steins_gate")?.let { parseFlexibleBool(it) } ?: false,
                privilegeType = obj.optInt("privilege_type", 0),
                elecArcBadge = obj.optString("elec_arc_badge", "")
            )
        }

        private fun parseFlexibleBool(value: Any): Boolean {
            return when (value) {
                is Boolean -> value
                is Int -> value != 0
                is Long -> value != 0L
                is String -> value == "1" || value.equals("true", ignoreCase = true)
                else -> false
            }
        }
    }
}

data class Owner(
    @SerializedName("mid")
    val mid: Long = 0,

    @SerializedName("name")
    val name: String = "",

    @SerializedName("face")
    val face: String = "",

    @SerializedName("official_verify")
    val officialVerify: OfficialVerifySimple? = null
) {
    companion object {
        fun fromJson(obj: JSONObject): Owner {
            val verifyObj = obj.optJSONObject("official_verify")
            return Owner(
                mid = obj.optLong("mid", 0),
                name = obj.optString("name", ""),
                face = obj.optString("face", ""),
                officialVerify = verifyObj?.let {
                    OfficialVerifySimple(it.optInt("type", 0), it.optString("desc", ""))
                }
            )
        }
    }
}

data class Bangumi(
    @SerializedName("long_title")
    val longTitle: String = "",

    @SerializedName("cover")
    val cover: String = ""
) {
    companion object {
        fun fromJson(obj: JSONObject): Bangumi {
            return Bangumi(
                longTitle = obj.optString("long_title", ""),
                cover = obj.optString("cover", "")
            )
        }
    }
}

data class Stat(
    @SerializedName("aid")
    val aid: Long = 0,

    @SerializedName("view")
    val view: Long = 0,

    @SerializedName(value = "danmaku", alternate = ["dm", "danmakus"])
    var danmaku: Long = 0,

    @SerializedName("reply")
    val reply: Long = 0,

    @SerializedName("favorite")
    val favorite: Long = 0,

    @SerializedName("coin")
    val coin: Long = 0,

    @SerializedName("share")
    val share: Long = 0,

    @SerializedName("now_rank")
    val nowRank: Long = 0,

    @SerializedName("his_rank")
    val hisRank: Long = 0,

    @SerializedName("like")
    val like: Long = 0,

    @SerializedName("dislike")
    val dislike: Long = 0
) {
    companion object {
        fun fromJson(obj: JSONObject): Stat {
            return Stat(
                aid = obj.optLong("aid", 0),
                view = obj.optLong("view", 0),
                danmaku = obj.optLong("danmaku", obj.optLong("dm", obj.optLong("danmakus", 0))),
                reply = obj.optLong("reply", 0),
                favorite = obj.optLong("favorite", 0),
                coin = obj.optLong("coin", 0),
                share = obj.optLong("share", 0),
                nowRank = obj.optLong("now_rank", 0),
                hisRank = obj.optLong("his_rank", 0),
                like = obj.optLong("like", 0),
                dislike = obj.optLong("dislike", 0)
            )
        }
    }
}

data class VideoRights(
    @SerializedName("elec")
    val elec: Int = 0,
    @SerializedName("autoplay")
    val autoplay: Int = 1,
    @SerializedName("pay")
    val pay: Int = 0,
    @SerializedName("ugc_pay")
    val ugcPay: Int = 0,
    @SerializedName("arc_pay")
    val arcPay: Int = 0,
    /** 联合投稿标记（issue#157）：稍后观看 toview 等接口把 is_cooperation 放在 rights 子对象 */
    @SerializedName("is_cooperation")
    val isCooperation: Int = 0
) {
    companion object {
        fun fromJson(obj: JSONObject): VideoRights {
            return VideoRights(
                elec = obj.optInt("elec", 0),
                autoplay = obj.optInt("autoplay", 1),
                pay = obj.optInt("pay", 0),
                ugcPay = obj.optInt("ugc_pay", 0),
                arcPay = obj.optInt("arc_pay", 0),
                isCooperation = obj.optInt("is_cooperation", 0)
            )
        }
    }
}

data class Dimension(
    @SerializedName("width")
    val width: Int = 0,

    @SerializedName("height")
    val height: Int = 0,

    @SerializedName("rotate")
    val rotate: Int = 0
) {
    val isPortrait: Boolean
        get() {
            if (width == 0 || height == 0) return false
            return when (rotate) {
                90, 270 -> width > height
                else -> height > width
            }
        }

    companion object {
        fun fromJson(obj: JSONObject): Dimension {
            return Dimension(
                width = obj.optInt("width", 0),
                height = obj.optInt("height", 0),
                rotate = obj.optInt("rotate", 0)
            )
        }
    }
}
