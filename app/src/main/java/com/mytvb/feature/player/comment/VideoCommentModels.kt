package com.mytvb.feature.player.comment

import org.json.JSONArray
import org.json.JSONObject

internal const val VIDEO_COMMENT_TYPE_ARCHIVE = 1
internal const val VIDEO_COMMENT_SORT_NEW = 0
internal const val VIDEO_COMMENT_SORT_HOT = 1

internal fun parseVideoCommentReplyList(
    arr: JSONArray,
    oid: Long,
    canOpenThread: Boolean,
    upMid: Long,
): List<VideoCommentItem> {
    if (arr.length() <= 0) return emptyList()
    val out = ArrayList<VideoCommentItem>(arr.length())
    for (i in 0 until arr.length()) {
        val obj = arr.optJSONObject(i) ?: continue
        val item = parseVideoCommentReplyItem(obj, oid = oid, contextTag = null, canOpenThread = canOpenThread, upMid = upMid) ?: continue
        out.add(item)
    }
    return out
}

internal fun parseVideoCommentReplyItem(
    obj: JSONObject,
    oid: Long,
    contextTag: String?,
    canOpenThread: Boolean,
    upMid: Long,
): VideoCommentItem? {
    val rpid = obj.optLong("rpid", 0L).takeIf { it > 0L } ?: return null
    val member = obj.optJSONObject("member") ?: JSONObject()
    val mid =
        member.optString("mid", "").trim().toLongOrNull()
            ?: member.optLong("mid", 0L)
    val uname = member.optString("uname", "").trim()
    val avatar = member.optString("avatar", "").trim().takeIf { it.isNotBlank() }
    val userLevel =
        parseVideoCommentLevel(
            member.optJSONObject("level_info")?.opt("current_level"),
        )
    val isSeniorMember = parseVideoCommentSeniorMember(member.opt("is_senior_member"))
    // 大会员（vipStatus=1 且 vipType>0）→ 粉色用户名 + 头像"大"字角标；pendant → 头像框
    val vip = member.optJSONObject("vip")
    val isVip = vip != null && vip.optInt("vipStatus", 0) == 1 && vip.optInt("vipType", 0) > 0
    val pendantUrl =
        member.optJSONObject("pendant")
            ?.optString("image_enhance", "")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    // 粉丝勋章（粉丝牌）：佩戴粉丝团的用户头像左下挂团徽、名字旁挂团名牌。
    // fans_medal 的字段结构（有无团徽图 URL）打日志留证，便于后续补官方团徽角标。
    val fansMedal = parseVideoCommentFanMedal(member.optJSONObject("fans_medal"))
    val content = obj.optJSONObject("content") ?: JSONObject()
    val message = content.optString("message", "").trim()
    val emotes = parseVideoCommentEmoteMap(content.optJSONObject("emote"))
    val pictures = parseVideoCommentPictures(content.optJSONArray("pictures"))
    val noteCvid =
        obj.optString("note_cvid_str", "").trim().toLongOrNull()
            ?: obj.optLong("note_cvid", 0L)
    val ctime = obj.optLong("ctime", 0L).takeIf { it > 0L } ?: 0L
    val like = obj.optLong("like", 0L).coerceAtLeast(0L)
    val replyCount = obj.optInt("count", 0).coerceAtLeast(0)
    val replyPreviews =
        if (canOpenThread && replyCount > 0) {
            val replies = obj.optJSONArray("replies") ?: JSONArray()
            parseVideoCommentReplyPreviewList(replies)
        } else {
            emptyList()
        }
    val isUp = upMid > 0L && mid == upMid
    // UP 主对该评论点过赞（官方"UP主觉得很赞"标签）
    val isUpLiked = obj.optJSONObject("up_action")?.optInt("like", 0) == 1

    return VideoCommentItem(
        key = rpid.toString(),
        rpid = rpid,
        oid = oid,
        type = VIDEO_COMMENT_TYPE_ARCHIVE,
        mid = mid,
        userName = uname,
        avatarUrl = avatar,
        userLevel = userLevel,
        isSeniorMember = isSeniorMember,
        message = message,
        emotes = emotes,
        pictures = pictures,
        noteCvid = noteCvid.takeIf { it > 0L } ?: 0L,
        ctimeSec = ctime,
        likeCount = like,
        replyCount = replyCount,
        replyPreviews = replyPreviews,
        contextTag = contextTag,
        canOpenThread = canOpenThread,
        isUp = isUp,
        isVip = isVip,
        avatarPendantUrl = pendantUrl,
        fanMedal = fansMedal,
        isUpLiked = isUpLiked,
    )
}

internal data class VideoCommentFanMedal(
    val name: String,
    val level: Int,
    val colorStart: Int,
    val colorEnd: Int,
    val colorBorder: Int,
    val colorText: Int,
    val iconUrl: String?,
)

private fun parseVideoCommentFanMedal(obj: JSONObject?): VideoCommentFanMedal? {
    if (obj == null) return null
    if (!obj.optBoolean("show", false)) return null
    val name = obj.optString("medal_name", "").trim()
    if (name.isBlank()) return null
    val level = obj.optInt("level", 0).coerceAtLeast(0)
    val colorStart = obj.optLong("medal_color_start", 0L).toInt()
    val colorEnd = obj.optLong("medal_color_end", 0L).toInt()
    val colorBorder = obj.optLong("medal_color_border", 0L).toInt()
    val colorText = obj.optLong("medal_color", 0L).toInt()
    val iconUrl =
        sequenceOf("medal_icon", "icon", "medal_image")
            .map { obj.optString(it, "").trim() }
            .firstOrNull { it.startsWith("http") }
    return VideoCommentFanMedal(
        name = name,
        level = level,
        colorStart = colorStart,
        colorEnd = colorEnd,
        colorBorder = colorBorder,
        colorText = colorText,
        iconUrl = iconUrl,
    )
}

internal data class VideoCommentReplyPreview(
    val userName: String,
    val message: String,
    val emotes: Map<String, String> = emptyMap(),
)

internal data class VideoCommentPicture(
    val url: String,
    val width: Int? = null,
    val height: Int? = null,
) {
    val dimensionRatio: String?
        get() = imageDimensionRatio(width = width, height = height)
}

internal fun imageDimensionRatio(width: Int?, height: Int?): String? {
    val safeWidth = width?.takeIf { it > 0 } ?: return null
    val safeHeight = height?.takeIf { it > 0 } ?: return null
    return "$safeWidth:$safeHeight"
}

internal data class VideoCommentItem(
    val key: String,
    val rpid: Long,
    val oid: Long,
    val type: Int,
    val mid: Long,
    val userName: String,
    val avatarUrl: String?,
    val userLevel: Int? = null,
    val isSeniorMember: Boolean = false,
    val message: String,
    val emotes: Map<String, String> = emptyMap(),
    val pictures: List<VideoCommentPicture> = emptyList(),
    val noteCvid: Long = 0L,
    val ctimeSec: Long,
    val likeCount: Long,
    val replyCount: Int,
    val replyPreviews: List<VideoCommentReplyPreview> = emptyList(),
    val contextTag: String? = null,
    val canOpenThread: Boolean = false,
    val isThreadRoot: Boolean = false,
    val isUp: Boolean = false,
    val isVip: Boolean = false,
    val avatarPendantUrl: String? = null,
    val fanMedal: VideoCommentFanMedal? = null,
    val isUpLiked: Boolean = false,
    val isTop: Boolean = false,
    /** 非空时该项渲染为楼中楼小节行（"相关回复共N条"），不参与点击/焦点。 */
    val threadSectionTitle: String? = null,
)

private fun parseVideoCommentReplyPreviewList(arr: JSONArray, limit: Int = 2): List<VideoCommentReplyPreview> {
    if (arr.length() <= 0) return emptyList()
    val max = minOf(limit.coerceAtLeast(0), arr.length())
    if (max <= 0) return emptyList()
    val out = ArrayList<VideoCommentReplyPreview>(max)
    for (i in 0 until max) {
        val obj = arr.optJSONObject(i) ?: continue
        val member = obj.optJSONObject("member") ?: JSONObject()
        val uname = member.optString("uname", "").trim()
        val content = obj.optJSONObject("content") ?: JSONObject()
        val message = content.optString("message", "").trim()
        val emotes = parseVideoCommentEmoteMap(content.optJSONObject("emote"))
        if (uname.isBlank() && message.isBlank()) continue
        out.add(VideoCommentReplyPreview(userName = uname, message = message, emotes = emotes))
    }
    return out
}

private fun parseVideoCommentEmoteMap(obj: JSONObject?): Map<String, String> {
    if (obj == null || obj.length() <= 0) return emptyMap()
    val out = HashMap<String, String>(obj.length().coerceAtLeast(0))
    val keys = obj.keys()
    while (keys.hasNext()) {
        val key = keys.next().trim()
        if (key.isBlank()) continue
        val value = obj.optJSONObject(key) ?: continue
        val url = value.optString("url", "").trim()
        if (!url.startsWith("http")) continue
        out[key] = url
    }
    return out
}

private fun parseVideoCommentPictures(arr: JSONArray?): List<VideoCommentPicture> {
    if (arr == null || arr.length() <= 0) return emptyList()
    val out = ArrayList<VideoCommentPicture>(arr.length().coerceAtMost(6))
    for (i in 0 until arr.length()) {
        val obj = arr.optJSONObject(i) ?: continue
        val rawUrl = obj.optString("img_src", "").trim()
        val url =
            when {
                rawUrl.startsWith("http") -> rawUrl
                rawUrl.startsWith("//") -> "https:$rawUrl"
                else -> continue
            }
        out.add(
            VideoCommentPicture(
                url = url,
                width = obj.optInt("img_width", 0).takeIf { it > 0 },
                height = obj.optInt("img_height", 0).takeIf { it > 0 },
            ),
        )
        if (out.size >= 6) break
    }
    return out
}

private fun parseVideoCommentLevel(value: Any?): Int? {
    val level =
        when (value) {
            is Number -> value.toInt()
            is String -> value.trim().toIntOrNull()
            else -> null
        }
    return level?.takeIf { it in VIDEO_COMMENT_LEVEL_RANGE }
}

private fun parseVideoCommentSeniorMember(value: Any?): Boolean =
    when (value) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        is String -> {
            val normalized = value.trim()
            when {
                normalized.equals("true", ignoreCase = true) -> true
                normalized.equals("false", ignoreCase = true) -> false
                else -> normalized.toIntOrNull()?.let { it != 0 } ?: false
            }
        }
        else -> false
    }

/** Lv.N 标签背景配色（0-6 级，B 站惯例）。 */
internal fun videoCommentLevelColor(level: Int): Int =
    when (level) {
        0, 1 -> 0xFFC0C0C0.toInt()
        2 -> 0xFF8BD29B.toInt()
        3 -> 0xFF7BCDEF.toInt()
        4 -> 0xFFFEBB8B.toInt()
        5 -> 0xFFEE672A.toInt()
        else -> 0xFFF04C49.toInt()
    }

private val VIDEO_COMMENT_LEVEL_RANGE = 0..6
