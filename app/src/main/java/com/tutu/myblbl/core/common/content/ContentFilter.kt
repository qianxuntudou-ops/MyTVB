@file:Suppress("SpellCheckingInspection")

package com.tutu.myblbl.core.common.content

import android.content.Context
import android.os.SystemClock
import com.tutu.myblbl.core.common.log.AppLog
import com.tutu.myblbl.core.common.settings.AppSettingsDataStore
import com.tutu.myblbl.model.live.LiveRoomItem
import com.tutu.myblbl.model.search.SearchItemModel
import com.tutu.myblbl.model.video.VideoModel
import org.koin.mp.KoinPlatform

object ContentFilter {

    private const val KEY_MINOR_PROTECTION = "minor_protection"
    private const val KEY_BLOCKED_UP_NAMES = "blocked_up_names"
    private const val KEY_BLOCKED_VIDEO_KEYS = "blocked_video_keys"

    private val appSettings: AppSettingsDataStore get() = KoinPlatform.getKoin().get()

    private val VIDEO_BLOCKED_TYPE_NAMES = setOf(
        "ASMR",
        "助眠",
        "擦边",
        "擦邊",
        "福利",
        "软色情",
        "軟色情",
        "成人",
        "色情",
        "里番",
        "裏番",
        "肉番",
        "黄游",
        "黃遊",
        "黄油",
        "黃油",
        "工口",
        "本子",
        "R18",
        "R-18",
        "18禁",
        "恐怖",
        "惊悚",
        "驚悚",
        "灵异",
        "靈異",
        "鬼故事",
        "怪谈",
        "怪談",
        "都市传说",
        "都市傳說",
        "细思极恐",
        "細思極恐",
        "血腥",
        "暴力",
        "猎奇",
        "獵奇",
        "ryona",
        "鬼畜"
    )

    private val EXPLICIT_BLOCKED_KEYWORDS = setOf(
        "软色情",
        "軟色情",
        "色情",
        "成人向",
        "少儿不宜",
        "少兒不宜",
        "里番",
        "裏番",
        "肉番",
        "黄游",
        "黃遊",
        "黄油",
        "黃油",
        "工口",
        "本子",
        "本子库",
        "本子庫",
        "涩图",
        "澀圖",
        "福利图",
        "福利圖",
        "搞黄",
        "搞黃",
        "搞黄色",
        "搞黃色",
        "搞色",
        "搞涩",
        "搞澀",
        "搞涩涩",
        "搞澀澀",
        "搞颜色",
        "搞顏色",
        "涩涩",
        "澀澀",
        "色色",
        "涩涩的",
        "澀澀的",
        "色色的",
        "色魔",
        "LSP",
        "老色批",
        "约炮",
        "約炮",
        "援交",
        "一夜情",
        "文爱",
        "文愛",
        "自慰",
        "手冲",
        "手衝",
        "打胶",
        "打膠",
        "偷拍",
        "走光",
        "无码",
        "無碼",
        "潜规则",
        "潛規則",
        "炼铜",
        "煉銅",
        "三年起步",
        "JK",
        "萝莉",
        "蘿莉",
        "正太",
        "御姐",
        "少妇",
        "少婦",
        "人妻",
        "熟女",
        "制服",
        "女仆",
        "女僕",
        "女仆装",
        "女僕裝",
        "短裙",
        "低胸",
        "深V",
        "抹胸",
        "丝袜",
        "絲襪",
        "油丝",
        "油襪",
        "亮丝",
        "亮絲",
        "黑丝",
        "黑絲",
        "嘿丝",
        "嘿絲",
        "老丝",
        "老絲",
        "库里丝",
        "庫裡絲",
        "裤里丝",
        "褲裡絲",
        "白丝",
        "白絲",
        "灰丝",
        "灰絲",
        "紫丝",
        "紫絲",
        "肉丝",
        "肉絲",
        "红丝",
        "紅絲",
        "蓝丝",
        "藍絲",
        "咖丝",
        "咖絲",
        "网丝",
        "網絲",
        "蕾丝",
        "蕾絲",
        "渔网",
        "漁網",
        "过膝袜",
        "過膝襪",
        "连裤袜",
        "連褲襪",
        "吊带袜",
        "吊帶襪",
        "网袜",
        "網襪",
        "白袜",
        "白襪",
        "连体",
        "連體",
        "油亮",
        "丝足",
        "絲足",
        "丝腿",
        "絲腿",
        "丝控",
        "絲控",
        "丝模",
        "絲模",
        "丝妹",
        "絲妹",
        "丝里丝",
        "絲裡絲",
        "裸足",
        "玉足",
        "足控",
        "恋足",
        "戀足",
        "美足",
        // 擦边UP称呼套路
        "丝姐",
        "絲姐",
        // jio 谐音绕过（丝jio/脚jio/足jio，足控擦边常用）
        "jio",
        "丝jio",
        "絲jio",
        "足jio",
        "脚jio",
        "巨乳",
        "乳摇",
        "乳搖",
        "事业线",
        "事業線",
        "蜜桃臀",
        "翘臀",
        "翹臀",
        "比基尼",
        "瑜伽",
        "泳装",
        "泳裝",
        "包臀裙",
        "女团舞",
        "女團舞",
        "穿丝",
        "穿絲",
        "大摆锤",
        "大擺錘",
        "大长腿",
        "大長腿",
        "耽美",
        "合欢",
        "合歡",
        "换丝",
        "換絲",
        "尻",
        "奈子",
        "牛头人",
        "御萝",
        "御蘿",
        "纯欲",
        "純欲",
        "绅士视频",
        "紳士視頻",
        "紳士影片",
        // 美女/擦边（语义确定，全维度生效）
        "写真",
        "寫真",
        "辣妹",
        "私房照",
        "过审",
        "過審",
        "R18",
        "R-18",
        "18禁",
        "血腥",
        "暴力",
        "猎奇",
        "獵奇",
        "性感",
        "asmr",
        "ryona"
    )

    private val TITLE_BLOCKED_KEYWORDS = EXPLICIT_BLOCKED_KEYWORDS + setOf(
        "擦边",
        "擦邊",
        "擦边球",
        "擦邊球",
        "打擦边",
        "打擦邊",
        // 裸"福利"：擦边视频标题常作"每日福利/深夜福利/福利局"，原仅组合集，提升为直命中
        "福利",
        "福利放送",
        "粉丝福利",
        "粉絲福利",
        "虎狼之词",
        "虎狼之詞",
        "懂的都懂",
        "懂的来",
        "懂的來",
        "秒懂",
        "舔耳",
        "娇喘",
        "嬌喘",
        "大尺度",
        "露骨",
        "卖肉",
        "賣肉",
        "情趣",
        "诱惑",
        "誘惑",
        "魅惑",
        "撩人",
        "钢管舞",
        "鋼管舞",
        "椅子舞",
        "艳舞",
        "艷舞",
        "抖胸舞",
        "扭胯舞",
        // 裸词提升为直命中：擦边标题常作"钢管/热舞/辣舞"，原仅组合集，单独出现也拦截
        "热舞",
        "熱舞",
        "辣舞",
        "钢管",
        "鋼管",
        "lap dance",
        "lapdance",
        "pole dance",
        "poledance",
        "chair dance",
        "chairdance",
        "booty shake",
        "bootyshake",
        "butt drop",
        "buttdrop",
        "twerk",
        "twerking",
        "恐怖",
        "惊悚",
        "驚悚",
        "灵异",
        "靈異",
        "鬼故事",
        "怪谈",
        "怪談",
        "细思极恐",
        "細思極恐",
        "SCP",
        // 美女/擦边话术（仅标题/搜索，不进 EXPLICIT 避免大面积误伤）
        "颜值",
        "顏值",
        "好身材",
        "微胖",
        "美腿",
        "锁骨",
        "鎖骨",
        "养眼",
        "養眼",
        "女友视角",
        "女友視角",
        "嫩模",
        "私房",
        "宅舞",
        "福利姬",
        "御姐音",
        "萝莉音",
        "蘿莉音",
        "妹妹音",
        // 恐怖讲解套路话术（单看任一即可判定）
        "胆小勿入",
        "膽小勿入",
        "慎入",
        "吓哭",
        "嚇哭",
        "吓尿",
        "吓坏",
        "嚇壞",
        "吓死",
        "嚇死",
        "头皮发麻",
        "頭皮發麻",
        "背后发凉",
        "背後發涼",
        "背脊发凉",
        "背脊發涼",
        "童年阴影",
        "童年陰影",
        "深夜勿看",
        "半夜别看",
        "半夜別看",
        "别在深夜看",
        "別在深夜看"
    )

    private val DESC_BLOCKED_KEYWORDS = EXPLICIT_BLOCKED_KEYWORDS + setOf(
        "擦边",
        "擦邊",
        "成人",
        "少儿不宜",
        "少兒不宜",
        "邪教"
    )

    private val TAG_BLOCKED_KEYWORDS = EXPLICIT_BLOCKED_KEYWORDS + setOf(
        "ASMR",
        "助眠",
        "擦边",
        "擦邊",
        "成人",
        "色情",
        "里番",
        "裏番",
        "肉番",
        "黄游",
        "黃遊",
        "黄油",
        "黃油",
        "工口",
        "血腥",
        "暴力",
        "猎奇",
        "獵奇",
        "恐怖",
        "惊悚",
        "驚悚",
        "性感",
        "诱惑",
        "誘惑"
    )

    private val CONTEXTUAL_SUBJECT_KEYWORDS = setOf(
        "兔女郎",
        "旗袍",
        "bikini",
        "舞蹈生",
        "竖屏舞蹈",
        "豎屏舞蹈",
        "竖屏热舞",
        "豎屏熱舞",
        // "丝"单字：单独不拦截（避免误伤螺丝/粉丝/丝瓜），仅与风险词同现时拦截（丝性感/丝写真/丝诱惑）
        "丝",
        "絲",
        // 擦边主体词
        "cosplay",
        "cos",
        "翻跳",
        "翻跳舞",
        "cover",
        "穿搭",
        "jk制服",
        // 解说/速看主体词
        "一口气看完",
        "一口氣看完",
        "一口气",
        "一口氣",
        "解说",
        "解說",
        "讲解",
        "講解",
        "解析",
        "速看",
        "混剪",
        "盘点",
        "盤點",
        "深度解读",
        "深度解讀"
    )

    private val CONTEXTUAL_RISK_KEYWORDS = setOf(
        "擦边",
        "擦邊",
        "福利",
        "福利放送",
        "性感",
        "诱惑",
        "誘惑",
        "魅惑",
        "撩人",
        "大尺度",
        "露骨",
        "卖肉",
        "賣肉",
        "涩涩",
        "澀澀",
        "色色",
        "热舞",
        "熱舞",
        "辣舞",
        "艳舞",
        "艷舞",
        "钢管",
        "鋼管",
        "椅子舞",
        "抖胸舞",
        "扭胯舞",
        "懂的都懂",
        "秒懂",
        "虎狼之词",
        "虎狼之詞",
        // 恐怖氛围风险词（"鬼/血"等宽泛单字也纳入，但仅在主体词同时命中时生效，靠组合判断规避误伤）
        "鬼",
        "血",
        "女鬼",
        "恶鬼",
        "惡鬼",
        "鬼片",
        "撞鬼",
        "见鬼",
        "見鬼",
        "丧尸",
        "喪屍",
        "丧尸片",
        "喪屍片",
        "尸体",
        "屍體",
        "阴森",
        "陰森",
        "诡异",
        "詭異",
        "邪门",
        "邪門",
        "吓人"
    )

    private val LIVE_BLOCKED_AREAS = setOf(
        "交友",
        "ASMR",
        "颜值",
        "顏值",
        "助眠",
        "唱聊",
        "舞蹈"
    )

    private val LIVE_BLOCKED_PARENT_AREAS = emptySet<String>()

    private val BLOCKED_UP_NAMES = setOf(
        "布丁奶酱团子"
    )

    private val BLOCKED_TYPE_IDS = setOf(
        129  // 舞蹈
    )

    private val TITLE_BLOCKED_KEYWORDS_LOWER = normalizeKeywords(TITLE_BLOCKED_KEYWORDS)

    private val DESC_BLOCKED_KEYWORDS_LOWER = normalizeKeywords(DESC_BLOCKED_KEYWORDS)

    private val TAG_BLOCKED_KEYWORDS_LOWER = normalizeKeywords(TAG_BLOCKED_KEYWORDS)

    private val CONTEXTUAL_SUBJECT_KEYWORDS_LOWER = normalizeKeywords(CONTEXTUAL_SUBJECT_KEYWORDS)

    private val CONTEXTUAL_RISK_KEYWORDS_LOWER = normalizeKeywords(CONTEXTUAL_RISK_KEYWORDS)

    // 关键词匹配走 Aho-Corasick 自动机：数百个词对每条标题/简介逐个 contains
    // 实测 22 条视频 50~60ms（每次刷新/翻页都付），自动机一遍扫描 <1ms。
    // 词表为编译期常量，首次使用时构建一次（by lazy），语义与逐词 contains 等价。
    private val TITLE_AUTOMATON by lazy { AhoCorasick(TITLE_BLOCKED_KEYWORDS_LOWER) }
    private val DESC_AUTOMATON by lazy { AhoCorasick(DESC_BLOCKED_KEYWORDS_LOWER) }
    private val TAG_AUTOMATON by lazy { AhoCorasick(TAG_BLOCKED_KEYWORDS_LOWER) }
    private val CONTEXTUAL_SUBJECT_AUTOMATON by lazy { AhoCorasick(CONTEXTUAL_SUBJECT_KEYWORDS_LOWER) }
    private val CONTEXTUAL_RISK_AUTOMATON by lazy { AhoCorasick(CONTEXTUAL_RISK_KEYWORDS_LOWER) }

    /**
     * 提前构建关键词自动机（首启一次性 ~20ms）。启动预加载协程在等首屏接口
     * 响应的空闲期调用，避免首次 filterVideos 在数据返回后的关键路径上付这笔构建成本。
     */
    fun prewarm() {
        TITLE_AUTOMATON
        DESC_AUTOMATON
        TAG_AUTOMATON
        CONTEXTUAL_SUBJECT_AUTOMATON
        CONTEXTUAL_RISK_AUTOMATON
    }

    private val BLOCKED_TYPE_NAMES_LOWER = VIDEO_BLOCKED_TYPE_NAMES
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }
        .toSet()

    private val LIVE_BLOCKED_AREAS_LOWER = LIVE_BLOCKED_AREAS
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }
        .toSet()

    private val LIVE_BLOCKED_PARENT_AREAS_LOWER = LIVE_BLOCKED_PARENT_AREAS
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }
        .toSet()

    @Suppress("UNUSED_PARAMETER")
    fun isMinorProtectionEnabled(context: Context): Boolean {
        return appSettings.getCachedString(KEY_MINOR_PROTECTION) != "关"
    }

    @Suppress("unused", "UNUSED_PARAMETER")
    fun addBlockedUpName(context: Context, name: String) {
        val existing = appSettings.getCachedStringSet(KEY_BLOCKED_UP_NAMES).toMutableSet()
        existing.add(name)
        appSettings.putStringSetAsync(KEY_BLOCKED_UP_NAMES, existing)
    }

    @Suppress("UNUSED_PARAMETER")
    fun addBlockedVideo(
        context: Context,
        aid: Long = 0,
        bvid: String = "",
        title: String = "",
        coverUrl: String = ""
    ) {
        val keys = buildVideoBlockKeys(aid, bvid, title, coverUrl)
        if (keys.isEmpty()) return
        val existing = appSettings.getCachedStringSet(KEY_BLOCKED_VIDEO_KEYS).toMutableSet()
        existing.addAll(keys)
        appSettings.putStringSetAsync(KEY_BLOCKED_VIDEO_KEYS, existing)
    }

    @Suppress("unused")
    fun addBlockedVideo(context: Context, video: VideoModel) {
        addBlockedVideo(
            context = context,
            aid = video.aid,
            bvid = video.bvid,
            title = video.title,
            coverUrl = video.coverUrl
        )
    }

    @Suppress("unused", "UNUSED_PARAMETER")
    fun removeBlockedUpName(context: Context, name: String) {
        val existing = appSettings.getCachedStringSet(KEY_BLOCKED_UP_NAMES).toMutableSet()
        existing.remove(name)
        appSettings.putStringSetAsync(KEY_BLOCKED_UP_NAMES, existing)
    }

    @Suppress("UNUSED_PARAMETER")
    fun getBlockedUpNames(context: Context): Set<String> {
        return appSettings.getCachedStringSet(KEY_BLOCKED_UP_NAMES)
    }

    @Suppress("UNUSED_PARAMETER")
    fun getBlockedVideoKeys(context: Context): Set<String> {
        return appSettings.getCachedStringSet(KEY_BLOCKED_VIDEO_KEYS)
    }

    private fun isUpNameBlocked(context: Context, name: String): Boolean {
        if (name.isEmpty()) return false
        val trimmed = name.trim()
        if (BLOCKED_UP_NAMES.any { it.equals(trimmed, ignoreCase = true) }) return true
        val dynamicList = getBlockedUpNames(context)
        return dynamicList.any { it.equals(trimmed, ignoreCase = true) }
    }

    private fun isVideoKeyBlocked(
        context: Context,
        aid: Long = 0,
        bvid: String = "",
        title: String = "",
        coverUrl: String = ""
    ): Boolean {
        val blockedKeys = getBlockedVideoKeys(context)
        if (blockedKeys.isEmpty()) return false
        return buildVideoBlockKeys(aid, bvid, title, coverUrl).any(blockedKeys::contains)
    }

    fun isVideoBlocked(
        context: Context,
        typeName: String?,
        title: String? = "",
        teenageMode: Int = 0,
        desc: String? = "",
        authorName: String? = "",
        aid: Long = 0,
        bvid: String = "",
        coverUrl: String = "",
        typeId: Int = 0
    ): Boolean {
        if (isVideoKeyBlocked(context, aid, bvid, title.orEmpty(), coverUrl)) {
            return true
        }
        if (teenageMode != 0) return true
        val safeAuthorName = authorName.orEmpty()
        if (safeAuthorName.isNotEmpty() && isUpNameBlocked(context, safeAuthorName)) {
            return true
        }
        if (!isMinorProtectionEnabled(context)) return false
        if (typeId in BLOCKED_TYPE_IDS) return true
        val trimmedTypeName = typeName.orEmpty().trim().lowercase()
        if (trimmedTypeName.isNotEmpty() && trimmedTypeName in BLOCKED_TYPE_NAMES_LOWER) {
            return true
        }
        val safeTitleLower = title.orEmpty().lowercase()
        val safeDescLower = desc.orEmpty().lowercase()
        if (shouldBlockTitle(safeTitleLower)) {
            return true
        }
        if (shouldBlockDesc(safeDescLower)) {
            return true
        }
        return false
    }

    private fun isVideoBlockedFast(
        blockedVideoKeys: Set<String>,
        blockedUpNames: Set<String>,
        minorProtectionEnabled: Boolean,
        typeName: String?,
        title: String? = "",
        teenageMode: Int = 0,
        desc: String? = "",
        authorName: String? = "",
        aid: Long = 0,
        bvid: String = "",
        coverUrl: String = "",
        typeId: Int = 0
    ): Boolean {
        if (blockedVideoKeys.isNotEmpty() &&
            buildVideoBlockKeys(aid, bvid, title.orEmpty(), coverUrl).any(blockedVideoKeys::contains)
        ) {
            return true
        }
        if (teenageMode != 0) return true
        val safeAuthorName = authorName.orEmpty()
        if (safeAuthorName.isNotEmpty()) {
            val trimmed = safeAuthorName.trim()
            if (BLOCKED_UP_NAMES.any { it.equals(trimmed, ignoreCase = true) }) return true
            if (blockedUpNames.any { it.equals(trimmed, ignoreCase = true) }) return true
        }
        if (!minorProtectionEnabled) return false
        if (typeId in BLOCKED_TYPE_IDS) return true
        val trimmedTypeName = typeName.orEmpty().trim().lowercase()
        if (trimmedTypeName.isNotEmpty() && trimmedTypeName in BLOCKED_TYPE_NAMES_LOWER) {
            return true
        }
        val safeTitleLower = title.orEmpty().lowercase()
        val safeDescLower = desc.orEmpty().lowercase()
        if (shouldBlockTitle(safeTitleLower)) return true
        if (shouldBlockDesc(safeDescLower)) return true
        return false
    }

    @Suppress("unused")
    fun isLiveRoomBlocked(context: Context, areaName: String, parentAreaName: String, anchorName: String = "", title: String = ""): Boolean {
        if (anchorName.isNotEmpty() && isUpNameBlocked(context, anchorName)) {
            return true
        }
        if (!isMinorProtectionEnabled(context)) return false
        val areaLower = areaName.trim().lowercase()
        if (areaLower.isNotEmpty() && areaLower in LIVE_BLOCKED_AREAS_LOWER) {
            return true
        }
        val parentAreaLower = parentAreaName.trim().lowercase()
        if (parentAreaLower.isNotEmpty() && parentAreaLower in LIVE_BLOCKED_PARENT_AREAS_LOWER) {
            return true
        }
        if (title.isNotEmpty()) {
            if (shouldBlockTitle(title.lowercase())) {
                return true
            }
        }
        return false
    }

    fun filterVideos(context: Context, videos: List<VideoModel>): List<VideoModel> {
        val t0 = SystemClock.elapsedRealtime()
        val blockedVideoKeys = getBlockedVideoKeys(context)
        val blockedUpNames = getBlockedUpNames(context)
        val minorProtectionEnabled = isMinorProtectionEnabled(context)
        val t1 = SystemClock.elapsedRealtime()
        var titleChars = 0
        var descChars = 0
        val result = videos.filter { video ->
            titleChars += video.title?.length ?: 0
            descChars += video.desc?.length ?: 0
            !isVideoBlockedFast(
                blockedVideoKeys = blockedVideoKeys,
                blockedUpNames = blockedUpNames,
                minorProtectionEnabled = minorProtectionEnabled,
                typeName = video.typeName,
                title = video.title,
                teenageMode = video.teenageMode,
                desc = video.desc,
                authorName = video.authorName,
                aid = video.aid,
                bvid = video.bvid,
                coverUrl = video.coverUrl,
                typeId = video.typeId
            )
        }
        val elapsed = SystemClock.elapsedRealtime() - t0
        if (elapsed > 5) {
            AppLog.i(
                "ContentFilter",
                "filterVideos ${videos.size}→${result.size} elapsed=${elapsed}ms " +
                    "prepare=${t1 - t0}ms scan=${SystemClock.elapsedRealtime() - t1}ms " +
                    "minor=$minorProtectionEnabled keys=${blockedVideoKeys.size} ups=${blockedUpNames.size} " +
                    "titleChars=$titleChars descChars=$descChars"
            )
        }
        return result
    }

    fun filterLiveRooms(context: Context, rooms: List<LiveRoomItem>): List<LiveRoomItem> {
        val t0 = SystemClock.elapsedRealtime()
        val blockedUpNames = getBlockedUpNames(context)
        val minorProtectionEnabled = isMinorProtectionEnabled(context)
        val result = rooms.filter { room ->
            val anchorName = room.uname.trim()
            if (anchorName.isNotEmpty()) {
                if (BLOCKED_UP_NAMES.any { it.equals(anchorName, ignoreCase = true) }) return@filter false
                if (blockedUpNames.any { it.equals(anchorName, ignoreCase = true) }) return@filter false
            }
            if (!minorProtectionEnabled) return@filter true
            val areaLower = room.areaV2Name.ifEmpty { room.areaName }.trim().lowercase()
            if (areaLower.isNotEmpty() && areaLower in LIVE_BLOCKED_AREAS_LOWER) return@filter false
            val parentAreaLower = room.parentAreaName.trim().lowercase()
            if (parentAreaLower.isNotEmpty() && parentAreaLower in LIVE_BLOCKED_PARENT_AREAS_LOWER) return@filter false
            !shouldBlockTitle(room.title.lowercase())
        }
        val elapsed = SystemClock.elapsedRealtime() - t0
        if (elapsed > 5) {
            AppLog.i("ContentFilter", "filterLiveRooms ${rooms.size}→${result.size} elapsed=${elapsed}ms")
        }
        return result
    }

    fun isSearchKeywordBlocked(context: Context, keyword: String): Boolean {
        if (!isMinorProtectionEnabled(context)) return false
        val keywordLower = keyword.trim().lowercase()
        if (keywordLower.isEmpty()) return false
        return TITLE_AUTOMATON.containsAny(keywordLower)
    }

    fun isSearchItemBlocked(context: Context, item: SearchItemModel): Boolean {
        val authorName = item.author.ifBlank { item.uname }
        // 用 decodedTitle 而非原始 title：B 站搜索结果标题带 <em class="keyword"> 高亮标签，
        // 会把关键词拆开或混入标签字符，导致子串匹配失效；decodedTitle 已是去标签的纯文本，与显示一致。
        return isVideoBlocked(
            context = context,
            typeName = "",
            title = item.decodedTitle,
            desc = item.desc,
            authorName = authorName,
            aid = item.aid,
            bvid = item.bvid,
            coverUrl = item.pic.ifBlank { item.cover }
        )
    }

    fun filterSearchItems(context: Context, items: List<SearchItemModel>): List<SearchItemModel> {
        return items.filter { !isSearchItemBlocked(context, it) }
    }

    fun isBlockedByTags(context: Context, tags: List<com.tutu.myblbl.model.video.detail.Tag>?): Boolean {
        if (!isMinorProtectionEnabled(context)) return false
        if (tags.isNullOrEmpty()) return false
        return tags.any { tag ->
            val tagName = tag.tagName.trim().lowercase()
            tagName.isNotEmpty() && TAG_AUTOMATON.containsAny(tagName)
        }
    }

    private fun shouldBlockTitle(titleLower: String): Boolean {
        if (titleLower.isEmpty()) return false
        // 归一化后再匹配：让"丝 袜""ｓｅｘｙ""胆小 勿入"等变体失效。
        // 仅做安全的字符变换（去空白/全角转半角/小写），不做拼音谐音模糊匹配，避免误伤。
        val normalized = normalizeForMatch(titleLower)
        if (TITLE_AUTOMATON.containsAny(normalized)) return true
        return containsContextualRiskFast(normalized)
    }

    private fun shouldBlockDesc(descLower: String): Boolean {
        if (descLower.isEmpty()) return false
        val normalized = normalizeForMatch(descLower)
        if (DESC_AUTOMATON.containsAny(normalized)) return true
        return containsContextualRiskFast(normalized)
    }

    private fun containsContextualRiskFast(valueLower: String): Boolean {
        return CONTEXTUAL_SUBJECT_AUTOMATON.containsAny(valueLower) &&
            CONTEXTUAL_RISK_AUTOMATON.containsAny(valueLower)
    }

    /**
     * 安全区归一化：去所有空白、全角空格、全角字符转半角、统一小写。
     * 故意不做拼音/谐音/形近/编辑距离等模糊变换，避免大面积误伤正常内容。
     * 作用：击穿"美 女""ｓｅｘｙ""胆小 勿入"这类插空格/全角的绕过变体。
     */
    private fun normalizeForMatch(value: String): String {
        if (value.isEmpty()) return value
        val sb = StringBuilder(value.length)
        for (c in value) {
            when {
                c.isWhitespace() || c.code == 0x3000 -> Unit // 去普通空白与全角空格
                c.code in 0xFF01..0xFF5E -> sb.append((c.code - 0xFEE0).toChar()) // 全角→半角
                else -> sb.append(c.lowercaseChar())
            }
        }
        return sb.toString()
    }

    private fun normalizeKeywords(keywords: Collection<String>): List<String> {
        return keywords
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    private fun buildVideoBlockKeys(
        aid: Long = 0,
        bvid: String = "",
        title: String = "",
        coverUrl: String = ""
    ): Set<String> {
        val keys = linkedSetOf<String>()
        val normalizedBvid = normalizeVideoKeyPart(bvid)
        if (normalizedBvid.isNotEmpty()) {
            keys.add("bvid:$normalizedBvid")
        }
        if (aid > 0L) {
            keys.add("aid:$aid")
        }
        val normalizedTitle = normalizeVideoKeyPart(title)
        if (normalizedTitle.isNotEmpty()) {
            keys.add("title:$normalizedTitle|cover:${normalizeVideoKeyPart(coverUrl)}")
        }
        return keys
    }

    private fun normalizeVideoKeyPart(value: String): String {
        return value.trim().lowercase()
    }
}
