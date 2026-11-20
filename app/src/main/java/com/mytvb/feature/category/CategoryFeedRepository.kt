package com.mytvb.feature.category

import com.mytvb.core.common.coroutine.catchingRequest
import com.mytvb.core.common.log.AppLog
import com.mytvb.model.video.VideoModel
import com.mytvb.network.api.BiliApi

/**
 * 分类页数据源（2026-11 官网频道化改造）：与官网 /c/{route}/ 分区页同源的推荐流。
 *
 * - 频道分区（rid=官网频道 tid 1001~1031）：region/feed/rcmd，display_id 为刷新序号
 *   而非页码，同号重拉内容也会变——只有追加语义可靠，翻回去重拉没有意义；
 * - 全站（rid=0）：x/web-interface/popular 热门流，pn 为真页码，天然连续翻页。
 */
class CategoryFeedRepository {

    companion object {
        private const val TAG = "CategoryFeedRepo"

        /** 官网同款每批条数（channelConfigMap 页面抓包实测 15，放大未验证过）。 */
        private const val CHANNEL_PAGE_SIZE = 15
        private const val ALL_WEB_PAGE_SIZE = 20

        /**
         * 官网频道页 display_id 从 2 起（首屏 SSR 后客户端首批=2，滚动递增）；
         * 传 1 服务端返回 62013「暂时没有更多内容了」（MuMu 实测 2026-11-20），
         * 全站热门 pn 同步偏移：displayId-1 才是真实页码。
         */
        const val FIRST_DISPLAY_ID = 2

        data class NetworkPage(
            val items: List<VideoModel>,
            val hasMore: Boolean
        )
    }

    // catchingRequest 而非 runCatching：加载更多含挂起点，切 tab 协程取消时
    // runCatching 会把 CancellationException 吞成 Result.failure 弹莫名错误提示
    suspend fun loadPage(rid: Int, displayId: Int): Result<NetworkPage> = catchingRequest {
        val page = if (rid == 0) {
            AppLog.i(TAG, "loadPage all-web popular pn=${displayId - 1}")
            val items = BiliApi.hotList(displayId - 1, ALL_WEB_PAGE_SIZE)
            NetworkPage(items = items, hasMore = items.size >= ALL_WEB_PAGE_SIZE)
        } else {
            AppLog.i(TAG, "loadPage channel rid=$rid display_id=$displayId")
            val items = BiliApi.regionFeedRcmd(fromRegion = rid, displayId = displayId, requestCnt = CHANNEL_PAGE_SIZE)
            // 推荐流没有"到底"语义，批次非空即可继续；全重复由 ViewModel 去重后判停
            NetworkPage(items = items, hasMore = items.isNotEmpty())
        }
        page
    }
}
