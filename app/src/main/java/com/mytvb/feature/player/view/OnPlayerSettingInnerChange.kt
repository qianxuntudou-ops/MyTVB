package com.mytvb.feature.player.view

interface OnPlayerSettingInnerChange {
    fun onPlaybackSpeedChange(speed: Float)
    fun onDmEnableChange(enabled: Boolean)
    fun onDmAlpha(alpha: Float)
    fun onDmTextSize(size: Int)
    fun onDmScreenArea(area: Int)
    fun onDmSpeed(speed: Float)
    fun onDmAllowTop(allow: Boolean)
    fun onDmAllowBottom(allow: Boolean)
    fun onDmMergeDuplicate(merge: Boolean)
    fun onDmSmartShield(enabled: Boolean)
    fun onAspectRatioChange(ratio: Int)
}
