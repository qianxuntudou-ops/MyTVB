package com.tutu.myblbl.feature.player.view

import com.tutu.myblbl.feature.player.settings.AfterPlayMode
import com.tutu.myblbl.model.video.quality.VideoQuality
import com.tutu.myblbl.model.video.quality.AudioQuality
import com.tutu.myblbl.model.video.quality.VideoCodecEnum

interface OnPlayerSettingChange {
    fun onVideoQualityChange(quality: VideoQuality)
    fun onAudioQualityChange(quality: AudioQuality)
    fun onPlaybackSpeedChange(speed: Float)
    fun onSubtitleChange(position: Int)
    fun onVideoCodecChange(codec: VideoCodecEnum)
    fun onAspectRatioChange(ratio: Int)
    fun onScreenMirrorChange(enabled: Boolean) {}
    fun onLiveQualityChange(qn: Int) {}
    fun onAfterPlayModeChange(mode: AfterPlayMode) {}
}
