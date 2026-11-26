package com.mytvb.repository.remote

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.mytvb.model.lane.HomeLaneHeader
import com.mytvb.model.lane.HomeLanePage
import com.mytvb.model.lane.HomeLaneSection
import com.mytvb.model.lane.LaneHeaderModel
import com.mytvb.model.lane.LaneInfoModel
import com.mytvb.model.lane.LaneItemModel
import com.mytvb.model.series.BadgeInfoModel
import com.mytvb.model.series.SeriesModel
import com.mytvb.model.series.SeriesType
import com.mytvb.model.series.timeline.SeriesTimeLineModel
import com.mytvb.model.series.timeline.TimeLineADayModel
import com.mytvb.network.api.ApiService
import com.mytvb.network.NetworkManager
import com.mytvb.network.session.NetworkSessionGateway
import com.mytvb.repository.UserRepository
import com.mytvb.core.common.log.AppLog
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

class HomeLaneRepository(
    private val apiService: ApiService,
    private val seriesRepository: SeriesRepository,
    private val userRepository: UserRepository,
    private val sessionGateway: NetworkSessionGateway
) {

    companion object {
        private const val TAG = "HomeLaneRepository"
        const val TYPE_ANIMATION = 1
        const val TYPE_CINEMA = 2
        private const val FOLLOW_SECTION_PAGE_SIZE = 12
    }

    suspend fun getHomeLanes(
        type: Int,
        cursor: Long = 0,
        isRefresh: Boolean = true
    ): Result<HomeLanePage> {
        return try {
            Result.success(fetchReferenceAlignedHomeLanes(type = type, cursor = cursor, isRefresh = isRefresh))
        } catch (throwable: CancellationException) {
            throw throwable
        } catch (throwable: Throwable) {
            AppLog.e(
                TAG,
                "getHomeLanes failure: type=$type, cursor=$cursor, isRefresh=$isRefresh, message=${throwable.message}",
                throwable
            )
            Result.failure(throwable)
        }
    }

    private suspend fun fetchReferenceAlignedHomeLanes(
        type: Int,
        cursor: Long,
        isRefresh: Boolean
    ): HomeLanePage = coroutineScope {
        val totalStartMs = SystemClock.elapsedRealtime()
        if (cursor > 0L || !isRefresh) {
            val legacyPage = fetchLegacyHomeLanes(type = type, cursor = cursor, isRefresh = isRefresh)
            AppLog.i(
                TAG,
                "homeLane fetch total type=$type cursor=$cursor refresh=$isRefresh elapsed=${SystemClock.elapsedRealtime() - totalStartMs}ms sections=${legacyPage.sections.size}"
            )
            return@coroutineScope legacyPage
        }

        val followDeferred = async {
            val startMs = SystemClock.elapsedRealtime()
            try {
                fetchMyFollowingSection(type).also { section ->
                    AppLog.i(
                        TAG,
                        "homeLane follow section type=$type elapsed=${SystemClock.elapsedRealtime() - startMs}ms has=${section != null}"
                    )
                }
            } catch (throwable: CancellationException) {
                throw throwable
            } catch (throwable: Throwable) {
                AppLog.e(
                    TAG,
                    "fetchReferenceAlignedHomeLanes follow section failure: type=$type, elapsed=${SystemClock.elapsedRealtime() - startMs}ms, message=${throwable.message}",
                    throwable
                )
                null
            }
        }

        val timelineDeferred = if (type == TYPE_ANIMATION) {
            async {
                val startMs = SystemClock.elapsedRealtime()
                try {
                    getAnimationTimelineSection().getOrNull().also { section ->
                        AppLog.i(
                            TAG,
                            "homeLane timeline section type=$type elapsed=${SystemClock.elapsedRealtime() - startMs}ms has=${section != null}"
                        )
                    }
                } catch (throwable: CancellationException) {
                    throw throwable
                } catch (throwable: Throwable) {
                    AppLog.e(
                        TAG,
                        "fetchReferenceAlignedHomeLanes timeline failure: type=$type, elapsed=${SystemClock.elapsedRealtime() - startMs}ms, message=${throwable.message}",
                        throwable
                    )
                    null
                }
            }
        } else {
            null
        }

        val legacyStartMs = SystemClock.elapsedRealtime()
        val legacyPage = fetchLegacyHomeLanes(type = type, cursor = cursor, isRefresh = isRefresh)
        AppLog.i(
            TAG,
            "homeLane legacy type=$type elapsed=${SystemClock.elapsedRealtime() - legacyStartMs}ms sections=${legacyPage.sections.size}"
        )
        val sections = legacyPage.sections.toMutableList()

        val followSection = followDeferred.await()

        sections.removeAll { it.isFollowSection() }

        if (followSection != null) {
            sections.add(0, followSection)
        }

        timelineDeferred?.await()?.let { timelineSection ->
            val insertIndex = if (followSection != null) 1 else minOf(1, sections.size)
            sections.add(insertIndex, timelineSection)
        }

        AppLog.i(
            TAG,
            "homeLane fetch total type=$type cursor=$cursor refresh=$isRefresh elapsed=${SystemClock.elapsedRealtime() - totalStartMs}ms sections=${sections.size}"
        )
        return@coroutineScope legacyPage.copy(sections = sections)
    }

    suspend fun getAnimationTimelineSection(): Result<HomeLaneSection?> {
        return try {
            coroutineScope {
                // 官方番剧频道时间表 = 日本番剧(types=1) + 国创(types=4) 两池按天合并，
                // 对标 BiliPai/官方 App；只拉 types=1 会缺国创条目，同一天数量对不上
                val animeDeferred = async {
                    apiService.getSeriesTimeLine(type = SeriesType.ANIME, before = 6, after = 6)
                }
                val guochuangDeferred = async {
                    apiService.getSeriesTimeLine(type = SeriesType.CHINA_ANIME, before = 6, after = 6)
                }
                val animeDays = animeDeferred.await()
                    .takeIf { it.code == 0 }?.result
                    ?: throw IllegalStateException("timeline(anime) failed")
                val guochuangDays = guochuangDeferred.await()
                    .takeIf { it.code == 0 }?.result
                    .orEmpty()
                // pgc/web/timeline 无有效 SESSDATA 时返回 code=0 的降级池（每天仅几条
                // 少儿/长寿番，无当季新番），与登录态数据完全不同；此处留痕便于从日志
                // 直接识别"时间表内容不对"是登录态问题而非解析问题。
                // 判定用 userInfo 而非 isLoggedIn()：后者只看 SESSDATA 在场，-101 软清后
                // cookie 留存（服务端已拒绝）会漏告警
                if (NetworkManager.getUserInfo() == null) {
                    AppLog.w(
                        TAG,
                        "timeline fetched without login session, degraded pool expected " +
                            "(today=${animeDays.firstOrNull { it.isToday == 1 }?.episodes?.size ?: 0} eps)"
                    )
                }
                val merged = mergeTimelineDays(animeDays, guochuangDays)
                AppLog.i(
                    TAG,
                    "timeline merged anime=${animeDays.sumOf { it.episodes.size }} " +
                        "guochuang=${guochuangDays.sumOf { it.episodes.size }} " +
                        "total=${merged.sumOf { it.episodes.size }}"
                )
                Result.success(buildTimelineSection(merged))
            }
        } catch (throwable: CancellationException) {
            throw throwable
        } catch (throwable: Throwable) {
            AppLog.e(TAG, "getAnimationTimelineSection failure: ${throwable.message}", throwable)
            Result.failure(throwable)
        }
    }

    /**
     * 番剧(types=1)与国创(types=4)两份时间表按天合并：date 相同的天 episodes
     * 拼接并按 episodeId+seasonId 去重（两池偶有交叉条目）、pubTs 升序；
     * 天按 dateTs 升序排列，容错语义与 BiliPai 的 mergeBangumiTimelineDays 一致。
     */
    private fun mergeTimelineDays(
        animeDays: List<TimeLineADayModel>,
        guochuangDays: List<TimeLineADayModel>
    ): List<TimeLineADayModel> {
        val daysByDate = linkedMapOf<String, TimeLineADayModel>()
        (animeDays + guochuangDays)
            .sortedWith(compareBy<TimeLineADayModel> { it.dateTs }.thenBy { it.date })
            .forEach { day ->
                val key = day.date.ifBlank { day.dateTs.toString() }
                val previous = daysByDate[key]
                if (previous == null) {
                    daysByDate[key] = day.copy(
                        episodes = day.episodes.distinctBy { it.episodeId to it.seasonId }
                    )
                } else {
                    daysByDate[key] = previous.copy(
                        dateTs = listOf(previous.dateTs, day.dateTs).filter { it > 0L }.minOrNull() ?: 0L,
                        dayOfWeek = previous.dayOfWeek.takeIf { it != 0 } ?: day.dayOfWeek,
                        isToday = maxOf(previous.isToday, day.isToday),
                        episodes = (previous.episodes + day.episodes)
                            .distinctBy { it.episodeId to it.seasonId }
                            .sortedBy { it.pubTs }
                    )
                }
            }
        return daysByDate.values.toList()
    }

    private suspend fun fetchLegacyHomeLanes(
        type: Int,
        cursor: Long,
        isRefresh: Boolean
    ): HomeLanePage {
        val apiRefresh = if (isRefresh) 0 else 1
        val response = sessionGateway.executeWithRiskControlRetry(
            key = "home_lane_$type",
            source = "homeLane.fetchLegacy"
        ) {
            when (type) {
                TYPE_CINEMA -> apiService.getCinema(
                    isRefresh = apiRefresh,
                    cursor = cursor
                )
                else -> apiService.getAnimations(
                    isRefresh = apiRefresh,
                    cursor = cursor
                )
            }
        }

        if (response.code != 0 || response.data == null) {
            throw IllegalStateException(response.message.ifEmpty { response.msg })
        }

        val modules = response.data.modules
        val hasMore = modules.size >= 20
        return HomeLanePage(
            sections = modules.mapNotNull { it.toSection() },
            nextCursor = response.data.nextCursor,
            hasMore = hasMore
        )
    }

    private suspend fun fetchMyFollowingSection(type: Int): HomeLaneSection? {
        if (!userRepository.isLoggedIn()) {
            return null
        }

        val mid = userRepository.resolveCurrentUserMid().getOrNull()
            ?.takeIf { it > 0L }
            ?: return null

        val following = seriesRepository.getMyFollowingSeries(
            type = type,
            page = 1,
            pageSize = FOLLOW_SECTION_PAGE_SIZE,
            vmid = mid
        ).getOrThrow()

        val items = following.list
            .mapNotNull { it.toFollowLaneItem() }
            .distinctBy { item -> item.seasonId to item.oid }
            .take(FOLLOW_SECTION_PAGE_SIZE)
        if (items.isEmpty()) {
            return null
        }

        return HomeLaneSection(
            title = if (type == TYPE_CINEMA) "我的追剧" else "我的追番",
            items = items,
            moreSeasonType = null,
            style = "follow",
            disableMore = true
        )
    }

    private fun LaneInfoModel.toSection(): HomeLaneSection? {
        if (items.isEmpty()) {
            return null
        }

        val moreUrl = headers.lastOrNull()?.url.orEmpty()

        return HomeLaneSection(
            title = title,
            items = items,
            headers = headers.map { it.toHeader() },
            moreSeasonType = parseMoreSeasonType(moreUrl),
            moreUrl = moreUrl,
            style = style,
            moduleId = moduleId
        )
    }

    private fun LaneHeaderModel.toHeader(): HomeLaneHeader {
        return HomeLaneHeader(
            title = title,
            url = url
        )
    }

    private fun buildTimelineSection(days: List<TimeLineADayModel>): HomeLaneSection? {
        if (days.size < 10) {
            return null
        }

        val normalizedDays = days.map { day ->
            day.copy(
                episodes = day.episodes.map { episode ->
                    episode.copy(dayOfWeek = day.dayOfWeek)
                }
            )
        }
        val recentEpisodes = normalizedDays
            .flatMap { it.episodes }
            .filter { it.published == 1 }
            .sortedByDescending { it.pubTs }

        val recentDay = TimeLineADayModel(
            episodes = recentEpisodes
        )

        return HomeLaneSection(
            title = "",
            timelineDays = listOf(recentDay) + normalizedDays,
            style = "timeline"
        )
    }

    private fun parseMoreSeasonType(url: String): Int? {
        if (!url.contains("index_type")) {
            return null
        }

        val indexType = Regex("(?<=index_type=).*?(?=&|$)")
            .find(url)
            ?.value
            ?.toIntOrNull()
            ?: return null

        if (indexType == SeriesType.ANIME) {
            val area = Regex("(?<=area=).*?(?=&|$)")
                .find(url)
                ?.value
                .orEmpty()
            if (area == "1,6,7") {
                return SeriesType.CHINA_ANIME
            }
        }
        return indexType
    }






    private fun JsonObject.toSeriesCard(): LaneItemModel? {
        val title = string("title")
        val cover = string("cover")
        if (title.isBlank() || cover.isBlank()) {
            return null
        }
        val link = string("link")
        val parsed = parseBangumiLink(link)
        val seasonId = long("season_id").takeIf { it > 0 } ?: parsed.first
        val epId = long("episode_id").takeIf { it > 0 } ?: parsed.second
        val rankTag = string("rank_tag")
        return LaneItemModel(
            seasonId = seasonId,
            title = title,
            cover = cover,
            desc = string("desc").ifBlank { string("evaluate") },
            subTitle = string("sub_title"),
            link = link,
            oid = long("oid").takeIf { it > 0 } ?: epId,
            badgeInfo = badgeInfoOrNull()
                ?: rankTag.takeIf { it.isNotBlank() }?.let { BadgeInfoModel(text = it) }
        )
    }

    private fun SeriesModel.toFollowLaneItem(): LaneItemModel? {
        val resolvedTitle = title.ifBlank { seasonTitle }
        val resolvedCover = cover.ifBlank { horizontalCover169 }
        if (resolvedTitle.isBlank() || resolvedCover.isBlank()) {
            return null
        }

        // 追番卡进度换百分比形态（issue#145，与我的追番列表卡同款规则）；
        // 已看完/解析失败/无时长时为 null，保持服务端原文
        val followProgress = progressWithPercent()
        val subtitle = followProgress ?: progress.ifBlank { seasonTitle }
        return LaneItemModel(
            seasonId = seasonId,
            oid = firstEp.takeIf { it > 0 } ?: newEp?.id ?: firstEpInfo?.id ?: 0L,
            title = resolvedTitle,
            cover = resolvedCover,
            desc = followProgress ?: progress.ifBlank { evaluate.ifBlank { summary } },
            subTitle = subtitle,
            link = url,
            newEp = newEp ?: firstEpInfo,
            badgeInfo = badgeInfo ?: badge.takeIf { it.isNotBlank() }?.let { BadgeInfoModel(text = it) }
        )
    }

    private fun JsonObject.badgeInfoOrNull(): BadgeInfoModel? {
        val badgeObject = get("badge_info")?.asJsonObjectOrNull()
            ?: get("badge")?.asJsonObjectOrNull()
            ?: return null
        val text = badgeObject.string("text")
        val bgColor = badgeObject.string("bg_color")
        val bgColorNight = badgeObject.string("bg_color_night")
        if (text.isBlank() && bgColor.isBlank() && bgColorNight.isBlank()) {
            return null
        }
        return BadgeInfoModel(
            text = text,
            bgColor = bgColor,
            bgColorNight = bgColorNight
        )
    }

    private fun parseBangumiLink(url: String): Pair<Long, Long> {
        val normalized = normalizeUrl(url)
        val seasonId = Regex("/ss(\\d+)").find(normalized)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
        val epId = Regex("/ep(\\d+)").find(normalized)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
        return seasonId to epId
    }

    private fun normalizeUrl(url: String): String {
        return when {
            url.isBlank() -> ""
            url.startsWith("https://") -> url
            url.startsWith("http://") -> "https://${url.removePrefix("http://")}"
            url.startsWith("//") -> "https:$url"
            else -> "https://www.bilibili.com${if (url.startsWith("/")) url else "/$url"}"
        }
    }

    private fun JsonElement?.asJsonObjectOrNull(): JsonObject? {
        return if (this != null && isJsonObject) asJsonObject else null
    }

    private fun JsonObject.string(key: String): String {
        val value = get(key) ?: return ""
        return if (value.isJsonPrimitive) value.asString else ""
    }

    private fun JsonObject.long(key: String): Long {
        val value = get(key) ?: return 0L
        return runCatching { value.asLong }.getOrDefault(0L)
    }

    private fun JsonObject.int(key: String): Int {
        val value = get(key) ?: return 0
        return runCatching { value.asInt }.getOrDefault(0)
    }

    private fun JsonObject.arrayOrNull(key: String): JsonArray? {
        val value = get(key) ?: return null
        return if (value.isJsonArray) value.asJsonArray else null
    }

    private fun JsonArray?.orEmpty(): List<JsonElement> {
        return this?.toList().orEmpty()
    }

    private fun HomeLaneSection.isFollowSection(): Boolean {
        if (style.equals("follow", ignoreCase = true)) {
            return true
        }

        val signals = buildList {
            add(title)
            add(style)
            headers.forEach { header ->
                add(header.title)
                add(header.url)
            }
        }.map { signal ->
            signal.replace(" ", "").trim()
        }.filter { it.isNotBlank() }

        return signals.any { signal ->
            signal.contains("我的追番") ||
                signal.contains("我的追剧") ||
                signal.contains("追番") ||
                signal.contains("追剧") ||
                signal.contains("follow", ignoreCase = true)
        }
    }
}
