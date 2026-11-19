package com.mytvb.feature.player.danmaku.common

import com.mytvb.feature.player.DanmakuFilterContext
import com.mytvb.model.dm.DmModel
import java.io.Serializable

internal interface DanmakuController {
    fun setData(
        data: List<DmModel>,
        filterContext: DanmakuFilterContext,
        startupTraceId: String,
        startupTraceStartElapsedMs: Long,
    )

    fun appendData(data: List<DmModel>, filterContext: DanmakuFilterContext)
    fun applySettings(snapshot: DanmakuSettingsSnapshot)
    fun updatePlaybackSpeed(speed: Float)
    fun notifyPlaybackStateChanged(playbackState: Int, playWhenReady: Boolean)
    fun notifyIsPlayingChanged(isPlaying: Boolean)
    fun notifyPlaybackFirstFrame()
    fun setEnabled(enabled: Boolean)
    fun pause()
    fun resume()
    fun resetForPlaybackStart(positionMs: Long)
    fun stop()
    fun syncPosition(positionMs: Long, forceSeek: Boolean)
    fun release()
}

internal interface LiveDanmakuController {
    fun startLive()
    fun addLiveDanmaku(dm: DmModel)
}

/** Protocol modes understood by both engines and by engine-neutral filtering. */
internal object DanmakuProtocolMode {
    const val ROLLING = 1
    const val CENTER_BOTTOM = 4
    const val CENTER_TOP = 5
    const val ADVANCED = 7
    const val SCRIPT = 9
}

/** Engine-neutral settings consumed by both danmaku implementations. */
data class DanmakuSettingsSnapshot(
    val enabled: Boolean,
    val alpha: Float,
    val textSize: Int,
    val speed: Float,
    val screenArea: Int,
    val allowTop: Boolean,
    val allowBottom: Boolean,
    val smartFilterLevel: Int,
    val mergeDuplicate: Boolean,
    val trackSpacing: String = "standard",
)

/** VIP gradient resources shared by both renderers. */
data class DanmakuVipGradientStyle(
    val fillTextureUrl: String = "",
    val strokeTextureUrl: String = "",
) : Serializable {
    val hasTexture: Boolean
        get() = fillTextureUrl.isNotBlank() || strokeTextureUrl.isNotBlank()

    val textureKey: String
        get() = "$fillTextureUrl#$strokeTextureUrl"

    companion object {
        val NONE = DanmakuVipGradientStyle()
    }
}

internal fun nextDanmakuPreparationGeneration(current: Long, replace: Boolean): Long =
    if (replace) current + 1L else current
