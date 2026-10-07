package com.mytvb.feature.player.comment

import com.mytvb.network.api.BiliApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal data class VideoCommentRootPage(
    val totalCount: Int,
    val items: List<VideoCommentItem>,
    /** 下一页游标（cursor.pagination_reply.next_offset）；null=无下一页。 */
    val nextCursorOffset: String?,
)

internal data class VideoCommentThreadPage(
    val totalCount: Int,
    val rootItem: VideoCommentItem?,
    val replies: List<VideoCommentItem>,
)

internal class VideoCommentDataSource(
    private val upMidProvider: () -> Long,
) {
    suspend fun loadRootPage(
        oid: Long,
        sort: Int,
        cursorOffset: String?,
        fallbackTotalCount: Int = -1,
    ): VideoCommentRootPage {
        // 网页版 wbi/main：mode 3=热门（默认）、2=最新（时间）；游标分页，首页传空串
        val mode = if (sort == VIDEO_COMMENT_SORT_NEW) VIDEO_COMMENT_MODE_NEW else VIDEO_COMMENT_MODE_HOT
        val data =
            withContext(Dispatchers.IO) {
                BiliApi.commentMainPage(
                    oid = oid,
                    type = VIDEO_COMMENT_TYPE_ARCHIVE,
                    mode = mode,
                    cursorOffset = cursorOffset ?: "",
                )
            }

        return withContext(Dispatchers.Default) {
            val cursor = data.optJSONObject("cursor") ?: JSONObject()
            val totalCount = cursor.optInt("all_count", fallbackTotalCount).takeIf { it >= 0 } ?: fallbackTotalCount
            val nextOffset =
                cursor.optJSONObject("pagination_reply")
                    ?.optString("next_offset", "")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
            // 置顶评论单独下发（官方置顶于列表第1条），普通 replies 不含它
            val topReplies =
                if (cursorOffset == null) {
                    parseVideoCommentReplyList(
                        data.optJSONArray("top_replies") ?: JSONArray(),
                        oid = oid,
                        canOpenThread = true,
                        upMid = upMidProvider(),
                    ).map { it.copy(key = "top:${it.rpid}", isTop = true) }
                } else {
                    emptyList()
                }
            val replies = data.optJSONArray("replies") ?: JSONArray()
            VideoCommentRootPage(
                totalCount = totalCount,
                items = topReplies +
                    parseVideoCommentReplyList(replies, oid = oid, canOpenThread = true, upMid = upMidProvider()),
                nextCursorOffset = nextOffset,
            )
        }
    }

    suspend fun loadThreadPage(
        oid: Long,
        rootRpid: Long,
        page: Int,
        fallbackTotalCount: Int = -1,
    ): VideoCommentThreadPage {
        val data =
            withContext(Dispatchers.IO) {
                BiliApi.commentRepliesPage(
                    type = VIDEO_COMMENT_TYPE_ARCHIVE,
                    oid = oid,
                    rootRpid = rootRpid,
                    pn = page,
                    ps = VIDEO_COMMENT_PAGE_SIZE,
                )
            }

        return withContext(Dispatchers.Default) {
            val pageObj = data.optJSONObject("page") ?: JSONObject()
            val totalCount = pageObj.optInt("count", fallbackTotalCount).takeIf { it >= 0 } ?: fallbackTotalCount
            val rootItem =
                data.optJSONObject("root")
                    ?.let { parseVideoCommentReplyItem(it, oid = oid, contextTag = null, canOpenThread = false, upMid = upMidProvider()) }
                    ?.let { it.copy(key = "thread_root:${it.rpid}", isThreadRoot = true) }
            val replies = data.optJSONArray("replies") ?: JSONArray()
            val replyItems =
                parseVideoCommentReplyList(replies, oid = oid, canOpenThread = false, upMid = upMidProvider())
                    .map { it.copy(key = "thread:${it.rpid}") }

            VideoCommentThreadPage(
                totalCount = totalCount,
                rootItem = rootItem,
                replies = replyItems,
            )
        }
    }
}

private const val VIDEO_COMMENT_PAGE_SIZE = 20
