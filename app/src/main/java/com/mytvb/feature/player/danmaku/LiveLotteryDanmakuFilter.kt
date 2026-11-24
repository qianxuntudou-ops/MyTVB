package com.mytvb.feature.player.danmaku

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive

/**
 * 直播抽奖口令屏蔽（issue#122）。
 *
 * 天选时刻 / 人气红包开启时带一个参与口令（`danmu`），观众点参与时代发的就是这条弹幕，
 * 于是全场跟着刷。口令记进 [blocked] 后，本场内容与之全等的弹幕一律不上屏，退出直播清空。
 * 只做全等比较：带口令再加字的弹幕不动。
 */
internal object LiveLotteryDanmakuFilter {

    /** 口令与弹幕都先去掉首尾空白再比。 */
    fun normalize(raw: String): String = raw.trim()

    fun shouldDrop(content: String, enabled: Boolean, blocked: Set<String>): Boolean =
        enabled && blocked.isNotEmpty() && normalize(content) in blocked

    /** 抽奖数据里的口令；空的返回 null（天选可以不带口令）。 */
    fun keywordOf(danmu: String?): String? = danmu?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * 进房补拉的抽奖信息里取口令：天选是单个对象，人气红包是数组。
     * 文档只给了 null 示例、没给非空结构，两种形态都收。
     */
    fun keywordsOf(lottery: JsonElement?): Set<String> = when (lottery) {
        is JsonObject -> setOfNotNull(keywordOf(danmuOf(lottery)))
        is JsonArray -> lottery.mapNotNull { (it as? JsonObject)?.let(::danmuOf) }
            .mapNotNull(::keywordOf)
            .toSet()
        else -> emptySet()
    }

    private fun danmuOf(lottery: JsonObject): String? =
        (lottery.get("danmu") as? JsonPrimitive)?.asString
}
