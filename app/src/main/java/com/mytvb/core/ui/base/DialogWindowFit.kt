package com.mytvb.core.ui.base

import android.content.Context
import android.view.Window
import android.view.WindowManager

/**
 * 浮层弹窗尺寸钳制：布局按设计 px 池尺寸写死时（如 dialog_owner_detail 的
 * px1400×px935），在低分屏/大界面缩放系数下换算出的像素可能超过屏幕物理尺寸，
 * 浮层 window 会被系统钳到屏幕大小而内容仍按原尺寸绘制，边缘内容被裁。
 * 统一在「设计尺寸」与「屏幕 92%」之间取小值。
 *
 * 高度传 0 表示按内容自适应（WRAP_CONTENT）。
 */
object DialogWindowFit {

    fun apply(
        window: Window?,
        context: Context,
        designWidthPx: Int,
        designHeightPx: Int = 0
    ) {
        val w = window ?: return
        val dm = context.resources.displayMetrics
        val width = minOf(designWidthPx, (dm.widthPixels * 0.92f).toInt())
        val height = if (designHeightPx <= 0) {
            WindowManager.LayoutParams.WRAP_CONTENT
        } else {
            minOf(designHeightPx, (dm.heightPixels * 0.92f).toInt())
        }
        w.setLayout(width, height)
        // 兜底：从播放页返回等系统 insets/config 未稳的场景弹窗，首帧绘制会被吞——
        // 表现为只有变暗遮罩、整个弹窗内容不渲染，任意按键触发重绘后立即恢复。
        // （4K 模拟器 + v2.1.2 实测必现；window/view 布局均正常，仅 Surface 首帧未提交。）
        // 布局后强制补走一帧绘制；本方法均在 show() 之后调用，decorView 此时可用。
        val decor = w.decorView ?: return
        decor.post { decor.invalidate() }
    }
}
