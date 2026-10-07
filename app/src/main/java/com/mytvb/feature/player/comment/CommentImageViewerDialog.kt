package com.mytvb.feature.player.comment

import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDialog
import androidx.core.view.isVisible
import com.mytvb.R
import com.mytvb.core.common.format.NumberUtils
import com.mytvb.core.ui.image.ImageLoader
import com.mytvb.databinding.DialogCommentImageViewerBinding

/**
 * 评论图片全屏查看器（Dialog 形态）：
 * 遥控器 OK=切换 2x 缩放、缩放后方向键平移、左右键翻页；BACK 关闭；
 * 触屏双指缩放/拖拽/横滑翻页/边缘点击翻页/空白点击关闭。
 * 按键经 [dispatchKeyEvent] 分发（BACK 例外，由 OnBackPressedDispatcher 处理）。
 *
 * 控制逻辑对齐 blbl.cat3399.feature.video.comment.VideoCommentImageViewerController。
 */
internal class CommentImageViewerDialog(
    private val activity: AppCompatActivity,
) : AppCompatDialog(activity, R.style.DialogTheme) {

    private val binding = DialogCommentImageViewerBinding.inflate(activity.layoutInflater)
    private var pictures: List<VideoCommentPicture> = emptyList()
    private var index: Int = 0

    val isViewing: Boolean
        get() = isShowing

    init {
        setContentView(binding.root)
        setCanceledOnTouchOutside(true)

        binding.viewerRoot.setOnClickListener { dismiss() }
        binding.buttonPrevious.setOnClickListener {
            if (!binding.ivImage.isZoomed()) previous()
        }
        binding.buttonNext.setOnClickListener {
            if (!binding.ivImage.isZoomed()) next()
        }
        binding.ivImage.onNavigatePrevious = {
            if (!binding.ivImage.isZoomed()) previous()
        }
        binding.ivImage.onNavigateNext = {
            if (!binding.ivImage.isZoomed()) next()
        }
        binding.ivImage.onBlankAreaTap = { dismiss() }
        binding.ivImage.onZoomStateChanged = { updateNavigationUi() }
    }

    fun open(pictures: List<VideoCommentPicture>, startIndex: Int = 0): Boolean {
        val safePictures =
            pictures.mapNotNull { picture ->
                val url = picture.url.trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
                if (url == picture.url) picture else picture.copy(url = url)
            }
        if (safePictures.isEmpty()) return false

        this.pictures = safePictures
        index = startIndex.coerceIn(0, safePictures.lastIndex)
        window?.setGravity(Gravity.CENTER)
        window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        show()
        render()
        return true
    }

    override fun dismiss() {
        super.dismiss()
        pictures = emptyList()
        index = 0
        binding.ivImage.setSourceDimensions(width = null, height = null)
        ImageLoader.clear(binding.ivImage)
        binding.ivImage.resetViewport()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!isShowing) return super.dispatchKeyEvent(event)

        val keyCode = event.keyCode
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (keyCode) {
                KeyEvent.KEYCODE_MENU,
                KeyEvent.KEYCODE_SETTINGS,
                KeyEvent.KEYCODE_INFO,
                KeyEvent.KEYCODE_GUIDE,
                -> return true

                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER,
                -> {
                    binding.ivImage.toggleDpadZoom()
                    return true
                }

                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (binding.ivImage.isZoomed()) {
                        binding.ivImage.panLeft()
                    } else {
                        previous()
                    }
                    return true
                }

                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (binding.ivImage.isZoomed()) {
                        binding.ivImage.panRight()
                    } else {
                        next()
                    }
                    return true
                }

                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (binding.ivImage.isZoomed()) {
                        binding.ivImage.panUp()
                    }
                    return true
                }

                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (binding.ivImage.isZoomed()) {
                        binding.ivImage.panDown()
                    }
                    return true
                }
            }
        }

        if (event.action == KeyEvent.ACTION_UP) {
            when (keyCode) {
                KeyEvent.KEYCODE_MENU,
                KeyEvent.KEYCODE_SETTINGS,
                KeyEvent.KEYCODE_INFO,
                KeyEvent.KEYCODE_GUIDE,
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER,
                -> return true
            }
        }

        return super.dispatchKeyEvent(event)
    }

    private fun render() {
        val currentPictures = pictures
        if (currentPictures.isEmpty()) {
            dismiss()
            return
        }

        val idx = index.coerceIn(0, currentPictures.lastIndex)
        index = idx
        val picture = currentPictures[idx]
        binding.ivImage.resetViewport()
        binding.ivImage.setSourceDimensions(width = picture.width, height = picture.height)
        ImageLoader.loadOriginal(binding.ivImage, picture.url)
        binding.tvIndex.text = "${idx + 1}/${currentPictures.size}"
        binding.tvIndex.isVisible = currentPictures.size > 1
        updateNavigationUi()
    }

    private fun previous() {
        if (pictures.size <= 1) return
        if (index <= 0) return
        index -= 1
        render()
    }

    private fun next() {
        if (pictures.size <= 1) return
        if (index >= pictures.lastIndex) return
        index += 1
        render()
    }

    private fun updateNavigationUi() {
        val showNavigation = pictures.size > 1 && !binding.ivImage.isZoomed()
        binding.buttonPrevious.isVisible = showNavigation && index > 0
        binding.buttonNext.isVisible = showNavigation && index < pictures.lastIndex
    }
}
