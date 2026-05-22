package com.tutu.myblbl.core.ui.video

import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import com.tutu.myblbl.databinding.CellVideoBinding
import com.tutu.myblbl.databinding.CellVideoLightBinding

data class VideoCardViews(
    val root: View,
    val imageView: ImageView,
    val progressBar: ProgressBar,
    val iconPlayCount: ImageView,
    val textPlayCount: TextView,
    val iconDanmaku: ImageView,
    val textDanmakuCount: TextView,
    val textDuration: TextView,
    val textInteractionBadge: TextView,
    val textChargeBadge: TextView,
    val iconPlaying: ImageView,
    val textView: TextView,
    val textOverflow: TextView,
    val imageAvatar: ImageView,
    val textBadge: TextView,
    val textViewOwner: TextView,
    val iconHistoryDevice: ImageView?,
    val textHistoryViewTime: TextView?
) {
    companion object {
        fun from(binding: CellVideoBinding): VideoCardViews = VideoCardViews(
            root = binding.root,
            imageView = binding.imageView,
            progressBar = binding.progressBar,
            iconPlayCount = binding.iconPlayCount,
            textPlayCount = binding.textPlayCount,
            iconDanmaku = binding.iconDanmaku,
            textDanmakuCount = binding.textDanmakuCount,
            textDuration = binding.textDuration,
            textInteractionBadge = binding.textInteractionBadge,
            textChargeBadge = binding.textChargeBadge,
            iconPlaying = binding.iconPlaying,
            textView = binding.textView,
            textOverflow = binding.textOverflow,
            imageAvatar = binding.imageAvatar,
            textBadge = binding.textBadge,
            textViewOwner = binding.textViewOwner,
            iconHistoryDevice = binding.iconHistoryDevice,
            textHistoryViewTime = binding.textHistoryViewTime
        )

        fun from(binding: CellVideoLightBinding): VideoCardViews = VideoCardViews(
            root = binding.root,
            imageView = binding.imageView,
            progressBar = binding.progressBar,
            iconPlayCount = binding.iconPlayCount,
            textPlayCount = binding.textPlayCount,
            iconDanmaku = binding.iconDanmaku,
            textDanmakuCount = binding.textDanmakuCount,
            textDuration = binding.textDuration,
            textInteractionBadge = binding.textInteractionBadge,
            textChargeBadge = binding.textChargeBadge,
            iconPlaying = binding.iconPlaying,
            textView = binding.textView,
            textOverflow = binding.textOverflow,
            imageAvatar = binding.imageAvatar,
            textBadge = binding.textBadge,
            textViewOwner = binding.textViewOwner,
            iconHistoryDevice = null,
            textHistoryViewTime = null
        )
    }
}
