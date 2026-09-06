package com.tutu.myblbl.feature.player.danmaku.model

/**
 * 弹幕内联段：把一条弹幕文本拆成 文本片段 / 表情 / 高赞图标 的序列。
 *
 * 解析由 [com.tutu.myblbl.feature.player.danmaku.common.DanmakuInlineParser] 完成，
 * 结果缓存在 DanmakuItem.inlineSegments 上（volatile，action/cache 线程共享）。
 * 返回 null 表示纯文本（无任何内联元素），走整行 measure/draw 快路径。
 */
internal sealed interface DanmakuInlineSegment {
    /** 原文本的 [start, end) 子串。 */
    data class Text(val start: Int, val end: Int) : DanmakuInlineSegment

    /** `[doge]` 风格表情，[url] 来自表情词典。 */
    data class Emote(val url: String) : DanmakuInlineSegment

    /** 高赞弹幕头部的点赞图标（attr bit2）。 */
    object HighLikeIcon : DanmakuInlineSegment
}
