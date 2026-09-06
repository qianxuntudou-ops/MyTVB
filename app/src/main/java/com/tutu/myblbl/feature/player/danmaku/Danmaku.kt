package com.tutu.myblbl.feature.player.danmaku

import com.tutu.myblbl.feature.player.danmaku.common.DanmakuVipGradientStyle

/**
 * 弹幕引擎内部数据模型（迁移自 blbl.cat3399.core.model.Danmaku）。
 *
 * 与 [com.tutu.myblbl.model.dm.DmModel] 的桥接见 [toDanmaku] 扩展函数。
 */
data class Danmaku(
    val timeMs: Int,
    val mode: Int,
    val text: String,
    val color: Int,
    val fontSize: Int,
    val weight: Int,
    val midHash: String? = null,
    val dmid: Long? = null,
    val attr: Int = 0,
    /** VIP 渐变弹幕标记（colorful == 0xEA61 且 allowVipColorful 开启时为 true）。 */
    val vipGradient: Boolean = false,
    val vipGradientStyle: DanmakuVipGradientStyle = DanmakuVipGradientStyle.NONE,
)

/** 高赞弹幕（attr bit2）：官方客户端在弹幕头部显示点赞图标。 */
val Danmaku.isHighLiked: Boolean
    get() = (attr and (1 shl 2)) != 0
