package com.mytvb.feature.player.shortcut

import android.content.Context
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mytvb.R
import com.mytvb.core.ui.base.AppToast
import com.mytvb.core.ui.base.DialogWindowFit
import com.mytvb.core.ui.decoration.LinearSpacingItemDecoration
import com.mytvb.ui.adapter.SettingSelectionDialogAdapter

/**
 * 快捷键相关弹窗：
 * - [showShortcutChoiceDialog]：设置页 showChoiceDialog 的 Context 版（播放器宿主内弹选择，
 *   倍速/字幕三态共用），样式同为宽面板 + SettingSelectionDialogAdapter。
 * - [ShortcutKeyCaptureDialog]：按键学习弹窗，按下任意非禁绑键即回调并关闭，BACK 取消。
 */

private const val TAG_CHOICE = "shortcutChoice"

fun showShortcutChoiceDialog(
    context: Context,
    title: String,
    options: Array<String>,
    currentIndex: Int,
    onSelected: (Int) -> Unit,
) {
    val dialog = AppCompatDialog(context, R.style.DialogTheme)
    dialog.setCanceledOnTouchOutside(true)
    DialogWindowFit.overlay(
        dialog,
        LayoutInflater.from(context).inflate(R.layout.dialog_setting_choice, null),
        context.resources.getDimensionPixelSize(R.dimen.px800),
        heightPx = context.resources.getDimensionPixelSize(R.dimen.px615),
        tag = TAG_CHOICE,
    )
    dialog.findViewById<View>(R.id.dialog_root)?.setOnClickListener { dialog.dismiss() }
    dialog.findViewById<TextView>(R.id.top_title)?.text = title

    val recyclerView = dialog.findViewById<RecyclerView>(R.id.recyclerView)
    val adapter = SettingSelectionDialogAdapter(
        options = options.toList(),
        selectedIndex = currentIndex.coerceIn(0, (options.size - 1).coerceAtLeast(0)),
    ) { selectedIndex ->
        onSelected(selectedIndex)
        dialog.dismiss()
    }
    recyclerView?.layoutManager = object : LinearLayoutManager(context) {
        override fun calculateExtraLayoutSpace(state: RecyclerView.State, extraLayoutSpace: IntArray) {
            extraLayoutSpace[0] = context.resources.getDimensionPixelSize(R.dimen.px100)
            extraLayoutSpace[1] = context.resources.getDimensionPixelSize(R.dimen.px100)
        }

        // 列表首尾按上/下键时焦点留在原地，不甩出弹窗外
        override fun onInterceptFocusSearch(focused: View, direction: Int): View? {
            val position = (focused.parent as? RecyclerView)?.getChildAdapterPosition(focused)
                ?: RecyclerView.NO_POSITION
            if (position != RecyclerView.NO_POSITION) {
                if (direction == View.FOCUS_DOWN && position == itemCount - 1) return focused
                if (direction == View.FOCUS_UP && position == 0) return focused
            }
            return null
        }
    }
    recyclerView?.adapter = adapter
    if (recyclerView != null && recyclerView.itemDecorationCount == 0) {
        recyclerView.addItemDecoration(
            LinearSpacingItemDecoration(context.resources.getDimensionPixelSize(R.dimen.px2), includeBottom = true),
        )
    }
    dialog.show()
    // 初始焦点：post 重试（f83b2c55 同款机制），窗口 attach 前单次 requestFocus 必失败
    adapter.requestInitialFocus(recyclerView)
}

class ShortcutKeyCaptureDialog(
    context: Context,
    actionLabel: String,
    private val onCaptured: (Int) -> Unit,
) : AppCompatDialog(context, R.style.DialogTheme) {

    init {
        DialogWindowFit.overlay(
            this,
            LayoutInflater.from(context).inflate(R.layout.dialog_shortcut_capture, null),
            context.resources.getDimensionPixelSize(R.dimen.px800),
            heightPx = context.resources.getDimensionPixelSize(R.dimen.px400),
            tag = "shortcutCapture",
        )
        findViewById<TextView>(R.id.top_title)?.text = context.getString(R.string.shortcut_capture_title)
        findViewById<TextView>(R.id.capture_hint)?.text = context.getString(R.string.shortcut_capture_hint)
        findViewById<TextView>(R.id.captured_key)?.text = actionLabel
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return true
        val keyCode = event.keyCode
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            dismiss()
            return true
        }
        if (PlayerShortcutStore.isForbiddenKeyCode(keyCode)) {
            AppToast.show(context, R.string.shortcut_key_forbidden)
            return true
        }
        onCaptured(keyCode)
        dismiss()
        return true
    }
}
