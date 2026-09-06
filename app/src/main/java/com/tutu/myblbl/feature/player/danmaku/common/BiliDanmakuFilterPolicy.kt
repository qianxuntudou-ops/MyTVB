package com.tutu.myblbl.feature.player.danmaku.common

import com.tutu.myblbl.core.common.log.AppLog
import com.tutu.myblbl.feature.player.DanmakuFilterContext
import com.tutu.myblbl.model.dm.DmModel
import com.tutu.myblbl.model.proto.DanmuWebPlayerConfigProto
import com.tutu.myblbl.model.proto.DmRestrictPeriodProto
import kotlin.math.abs

internal object BiliDanmakuFilterPolicy {
    private const val TAG = "BiliDanmakuFilter"
    private const val PROFILE_LOG_MS = 2L
    private const val REPORT_FILTER_MAX = 64

    fun apply(
        items: List<DmModel>,
        context: DanmakuFilterContext,
        settings: DanmakuSettingsSnapshot?,
        stage: String,
        userFilter: DanmakuUserFilter = DanmakuUserFilter.EMPTY,
    ): List<DmModel> {
        if (items.isEmpty()) return items
        val startedAtNs = System.nanoTime()
        val reportMatchers = context.reportFilters
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(REPORT_FILTER_MAX)
            .map { ReportMatcher.from(it) }
            .toList()
        val restrictPeriods = context.restrictPeriods
            .filter { it.endMs > it.startMs }
        val playerConfig = context.playerConfig
        val aiEnabled = context.smartFilterConfig.resolvedEnabled ||
            playerConfig.aiSwitch
        val localAiLevel = settings?.smartFilterLevel?.coerceAtLeast(0) ?: 0
        val aiLevel = listOf(
            context.smartFilterConfig.resolvedLevel,
            playerConfig.aiLevel,
            playerConfig.resolveBiliCompatibleAiLevel()
        ).maxOrNull()?.coerceAtLeast(0) ?: 0

        if (reportMatchers.isEmpty() &&
            restrictPeriods.isEmpty() &&
            !aiEnabled &&
            playerConfig == DanmuWebPlayerConfigProto() &&
            settings == null &&
            userFilter.isEmpty()) {
            return items
        }

        var blankOrUnsupported = 0
        var reportDropped = 0
        var restrictedDropped = 0
        var aiDropped = 0
        var typeDropped = 0
        var userDropped = 0
        val userKeywords = userFilter.keywords
        val userRegexes = userFilter.regexes
        val blockedMidHashes = userFilter.blockedUserMidHashes
        val filtered = ArrayList<DmModel>(items.size)
        for (item in items) {
            val content = item.content.trim()
            if (content.isBlank() || item.mode == DanmakuProtocolMode.SCRIPT || content.contains("def text", ignoreCase = true)) {
                blankOrUnsupported++
                continue
            }
            if (isRestricted(item.progress.toLong(), restrictPeriods)) {
                restrictedDropped++
                continue
            }
            if (reportMatchers.any { it.matches(content) }) {
                reportDropped++
                continue
            }
            // 云端用户屏蔽：拉黑用户（midHash）/ 屏蔽词 / 正则（B 站账号同步规则）。
            if (blockedMidHashes.isNotEmpty() && item.midHash.isNotBlank() &&
                blockedMidHashes.contains(item.midHash.trim().lowercase())
            ) {
                userDropped++
                continue
            }
            if (userKeywords.isNotEmpty() && userKeywords.any { content.contains(it) }) {
                userDropped++
                continue
            }
            if (userRegexes.isNotEmpty() && userRegexes.any { it.containsMatchIn(content) }) {
                userDropped++
                continue
            }
            if ((aiEnabled && aiLevel > 0 && shouldDropByAi(item, aiLevel)) ||
                shouldDropByLocalAi(item, localAiLevel)
            ) {
                aiDropped++
                continue
            }
            if (!isTypeAllowedByBiliSetting(item, playerConfig, settings)) {
                typeDropped++
                continue
            }
            filtered += item
        }
        val costMs = (System.nanoTime() - startedAtNs) / 1_000_000L
        val dropped = items.size - filtered.size
        if (dropped > 0 || costMs >= PROFILE_LOG_MS) {
            AppLog.i(
                TAG,
                "stage=$stage raw=${items.size} kept=${filtered.size} dropped=$dropped " +
                    "blank=$blankOrUnsupported report=$reportDropped restrict=$restrictedDropped " +
                    "ai=$aiDropped type=$typeDropped user=$userDropped " +
                    "serviceAiLevel=$aiLevel localAiLevel=$localAiLevel cost=${costMs}ms"
            )
        }
        return filtered
    }

    private fun shouldDropByAi(item: DmModel, aiLevel: Int): Boolean {
        val scores = sequenceOf(item.weight, item.aiFlagScore)
            .filter { it != 0 }
            .map { abs(it) }
        val score = scores.maxOrNull() ?: return false
        return score < aiLevel
    }

    private fun shouldDropByLocalAi(item: DmModel, aiLevel: Int): Boolean =
        aiLevel > 0 && item.weight > 0 && item.weight < aiLevel

    private fun DanmuWebPlayerConfigProto.resolveBiliCompatibleAiLevel(): Int {
        if (dmArea in 1..99) return 3
        return when (dmDensity) {
            1 -> 3
            2, 3 -> 2
            else -> 0
        }
    }

    private fun isRestricted(progressMs: Long, periods: List<DmRestrictPeriodProto>): Boolean {
        for (period in periods) {
            if (progressMs in period.startMs until period.endMs) {
                return true
            }
        }
        return false
    }

    private fun isTypeAllowedByBiliSetting(
        item: DmModel,
        playerConfig: DanmuWebPlayerConfigProto,
        settings: DanmakuSettingsSnapshot?
    ): Boolean {
        if (!playerConfig.dmSwitch) return false
        val isColored = item.colorful != 0 ||
            item.colorfulSrc.isNotBlank() ||
            (item.color != 0 && item.color != 0xFFFFFF)
        if (isColored && !playerConfig.typeColor) return false
        return when (item.mode) {
            DanmakuProtocolMode.CENTER_TOP ->
                playerConfig.typeTop && playerConfig.typeTopBottom && settings?.allowTop != false
            DanmakuProtocolMode.CENTER_BOTTOM ->
                playerConfig.typeBottom && playerConfig.typeTopBottom && settings?.allowBottom != false
            DanmakuProtocolMode.ADVANCED -> false
            else -> playerConfig.typeScroll
        }
    }

    private sealed interface ReportMatcher {
        fun matches(content: String): Boolean

        data class Plain(private val needle: String) : ReportMatcher {
            override fun matches(content: String): Boolean =
                content.contains(needle, ignoreCase = true)
        }

        data class RegexMatcher(private val regex: Regex) : ReportMatcher {
            override fun matches(content: String): Boolean =
                regex.containsMatchIn(content)
        }

        companion object {
            fun from(raw: String): ReportMatcher {
                val pattern = raw.removeSurrounding("/")
                val looksLikeRegex = raw.startsWith("/") && raw.endsWith("/") ||
                    raw.any { it in ".+*?[](){}|\\" }
                if (looksLikeRegex) {
                    runCatching {
                        Regex(pattern, setOf(RegexOption.IGNORE_CASE))
                    }.getOrNull()?.let { return RegexMatcher(it) }
                }
                return Plain(raw)
            }
        }
    }
}
