package com.tutu.myblbl.feature.player.danmaku.common

import com.tutu.myblbl.feature.player.danmaku.model.DanmakuInlineSegment

/**
 * 弹幕内联段解析器（文本 + 表情 + 高赞图标），Engine 测量与 CacheManager 烘焙共用，
 * 保证"测量宽度"与"绘制宽度"逐段一致。
 *
 * 对齐 blbl.cat3399 的 parseInlineSegments 语义：
 * - 高赞图标（showHighLikeIcon && isHighLiked）固定作为首段；
 * - `[token]` 在词典命中（urlForToken 返回 http(s)）时替换为表情段，否则按原文本保留；
 * - 无任何内联元素时返回 null（调用方走整行快路径）；
 * - canParseEmote=false（表情词典未就绪）时只解析高赞图标，文本原样保留——
 *   词典就绪后由样式/词典版本变化触发重新解析（见 shouldCacheParsedSegments）。
 */
internal object DanmakuInlineParser {

    fun parse(
        text: String,
        isHighLiked: Boolean,
        showHighLikeIcon: Boolean,
        canParseEmote: Boolean,
        urlForToken: (String) -> String?,
    ): List<DanmakuInlineSegment>? {
        var hasInline = false
        val out = ArrayList<DanmakuInlineSegment>(8)
        if (showHighLikeIcon && isHighLiked) {
            out.add(DanmakuInlineSegment.HighLikeIcon)
            hasInline = true
        }
        var lastTextStart = 0
        if (canParseEmote && text.indexOf('[') >= 0) {
            var i = 0
            while (i < text.length) {
                val open = text.indexOf('[', startIndex = i)
                if (open < 0) break
                val close = text.indexOf(']', startIndex = open + 1)
                if (close < 0) break
                val token = text.substring(open, close + 1)
                val url = urlForToken(token)
                if (url != null && url.startsWith("http")) {
                    hasInline = true
                    if (open > lastTextStart) out.add(DanmakuInlineSegment.Text(start = lastTextStart, end = open))
                    out.add(DanmakuInlineSegment.Emote(url = url))
                    lastTextStart = close + 1
                }
                i = close + 1
            }
        }
        // 收尾文本段在 emote 块外：词典未就绪（仅图标命中）时也要补全剩余文本。
        if (hasInline && lastTextStart < text.length) {
            out.add(DanmakuInlineSegment.Text(start = lastTextStart, end = text.length))
        }
        if (!hasInline) return null
        return out
    }

    /**
     * 解析结果是否允许缓存到 item.inlineSegments：
     * 文本不含 '[' 时结果永远稳定；含 '[' 时依赖词典就绪（version>0），词典未就绪的
     * "只含高赞图标"结果不能缓存——词典就绪后会以 emoteVersion 对比失效重解析。
     */
    fun shouldCacheParsedSegments(text: String, canParseEmote: Boolean): Boolean =
        text.indexOf('[') < 0 || canParseEmote
}
