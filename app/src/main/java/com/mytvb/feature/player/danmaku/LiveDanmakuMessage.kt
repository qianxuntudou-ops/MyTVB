package com.mytvb.feature.player.danmaku

data class LiveDanmakuMessage(
    val type: Type,
    val content: String,
    val color: Int = 0xFFFFFF
) {
    enum class Type {
        DANMU,
        GIFT,
        SUPER_CHAT,
        WELCOME,
        GUARD_BUY,
        /** 天选/人气红包的参与口令：不是要上屏的弹幕，是本场要屏蔽的文本。 */
        LOTTERY_DANMU
    }
}
