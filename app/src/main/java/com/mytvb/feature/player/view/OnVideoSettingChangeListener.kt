package com.mytvb.feature.player.view

interface OnVideoSettingChangeListener {
    fun onVideoQualityClick() {}
    fun onAudioQualityClick() {}
    fun onPlaybackSpeedClick() {}
    fun onSubtitleClick() {}
    fun onVideoCodecClick() {}
    fun onEpisodeClick() {}
    fun onRelatedClick() {}
    fun onUpInfo() {}
    fun onComments() {}
    fun onRelated() {}
    fun onRepeat() {}
    fun onSubtitle() {}
    fun onPrevious() {}
    fun onNext() {}
    fun onDmEnableChange(enabled: Boolean) {}
    fun onMirrorChange(enabled: Boolean) {}
    fun onChooseEpisode() {}
    fun onMore() {}
    fun onVideoInfo() {}
    fun onLiveSettings() {}
    fun onLiveLineSettings() {}
    fun onLiveQualityChange(qn: Int) {}
    fun onRefresh() {}
    fun onClose() {}
}
