package com.mytvb.feature.player.view

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.appcompat.widget.AppCompatImageView

/**
 * Player controls on TV are mainly focus-driven. Suppressing the pressed-state
 * fill keeps long-press seek from flashing a full overlay that diverges from
 * the reference player's visual feedback.
 */
class PlayerControlButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private var touchPressed = false

    /**
     * 触摸按下时的按压变色反馈（同设置页按钮：按下整块变 colorPrimary）。
     *
     * [setPressed] 被压制（避免长按快进闪背景色），且触摸模式下按钮不即时获焦、
     * 无 focused 高亮，触摸点击因此毫无视觉反馈——一次性动作按钮（如返回）开启
     * 此开关，按下直接把背景 drawable 置 pressed 态（不经过 view 的 pressed 属性，
     * 长按快进等按钮的压制行为不受影响），抬起按 view 真实状态还原。
     */
    var touchPressFeedback: Boolean = false

    private fun applyTouchPressedVisual(pressed: Boolean) {
        if (!touchPressFeedback) return
        val bg = background ?: return
        bg.state = if (pressed) {
            intArrayOf(android.R.attr.state_pressed, android.R.attr.state_enabled)
        } else {
            drawableState
        }
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(false)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || !isClickable) {
            return super.onTouchEvent(event)
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchPressed = true
                applyTouchPressedVisual(pressed = true)
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_UP -> {
                val shouldClick = touchPressed && event.x >= 0f && event.x <= width && event.y >= 0f && event.y <= height
                touchPressed = false
                applyTouchPressedVisual(pressed = false)
                parent?.requestDisallowInterceptTouchEvent(false)
                if (shouldClick) {
                    performClick()
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                touchPressed = false
                applyTouchPressedVisual(pressed = false)
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (isClickable && isEnabled) {
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (isClickable && isEnabled) {
                performClick()
                return true
            }
        }
        return super.onKeyUp(keyCode, event)
    }
}
