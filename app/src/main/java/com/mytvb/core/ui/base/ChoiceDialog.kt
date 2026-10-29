package com.mytvb.core.ui.base

import android.content.Context
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AppCompatDialog
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mytvb.R
import com.mytvb.core.ui.decoration.LinearSpacingItemDecoration
import com.mytvb.ui.adapter.SettingSelectionDialogAdapter

/**
 * 通用单选弹窗：统一替换系统 AlertDialog 的排序/清晰度等选择菜单。
 * 系统 AlertDialog 文字小且不跟随 px 池与界面缩放，这里沿用 dialog_setting_choice
 * 布局与全 App 弹窗一致的字号和尺寸钳制。
 *
 * 上下键在本弹窗顶层显式接管（[dialog.setOnKeyListener] 先于窗口内焦点链）：
 * 布局根与钳制容器均可聚焦，系统 FocusFinder 偶发把焦点移出列表落到这些
 * 不可见容器上，表现为"行高亮消失、按确定无反应"。接管后焦点要么在行上、
 * 要么被下一次上下键拉回列表，不再悬空。
 */
object ChoiceDialog {

    fun show(
        context: Context,
        title: String,
        options: List<String>,
        selectedIndex: Int = 0,
        onDismiss: (() -> Unit)? = null,
        onSelected: (Int) -> Unit
    ) {
        val dialog = AppCompatDialog(context, R.style.DialogTheme)
        dialog.setCanceledOnTouchOutside(true)
        // 固定尺寸卡片（布局根 match_parent 依赖窗口定尺寸），fill 载体下显式传高
        DialogWindowFit.overlay(
            dialog,
            LayoutInflater.from(context).inflate(R.layout.dialog_setting_choice, null),
            context.resources.getDimensionPixelSize(R.dimen.px800),
            heightPx = context.resources.getDimensionPixelSize(R.dimen.px615),
            tag = "choice"
        )
        dialog.findViewById<View>(R.id.dialog_root)?.setOnClickListener { dialog.dismiss() }

        val titleView = dialog.findViewById<android.widget.TextView>(R.id.top_title)
        val recyclerView = dialog.findViewById<RecyclerView>(R.id.recyclerView)
        titleView?.text = title

        val adapter = SettingSelectionDialogAdapter(
            options = options,
            selectedIndex = selectedIndex
        ) { index ->
            dialog.dismiss()
            onSelected(index)
        }
        recyclerView?.layoutManager = LinearLayoutManager(context)
        recyclerView?.adapter = adapter
        if (recyclerView != null && recyclerView.itemDecorationCount == 0) {
            recyclerView.addItemDecoration(
                LinearSpacingItemDecoration(
                    context.resources.getDimensionPixelSize(R.dimen.px2),
                    includeBottom = true
                )
            )
        }

        dialog.setOnShowListener {
            recyclerView?.post {
                adapter.requestInitialFocus(recyclerView)
            }
        }
        dialog.setOnDismissListener { onDismiss?.invoke() }
        // 上下键显式移动列表焦点；OK 交给焦点行自己点击，BACK 交还系统关闭流程
        dialog.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN &&
                (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) &&
                recyclerView != null
            ) {
                val step = if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1
                moveListFocus(recyclerView, adapter, step, selectedIndex)
            } else {
                false
            }
        }
        dialog.show()
    }

    /**
     * 焦点在列表行之间显式移动：无焦点行（初始聚焦失败/被容器吸走）时从第一个
     * 可见行落回列表，越界钳制，目标行不可见时滚动到它。始终消费上下键，
     * 避免系统导航把焦点再丢进容器。
     */
    private fun moveListFocus(
        recyclerView: RecyclerView,
        adapter: SettingSelectionDialogAdapter,
        step: Int,
        fallbackPosition: Int
    ): Boolean {
        val count = adapter.itemCount
        if (count == 0) return false
        val last = count - 1
        val current = focusedListPosition(recyclerView)
        val target = when {
            current != RecyclerView.NO_POSITION -> (current + step).coerceIn(0, last)
            else -> firstListPosition(recyclerView, fallbackPosition).coerceIn(0, last)
        }
        val targetView = recyclerView.findViewHolderForAdapterPosition(target)?.itemView
        if (targetView != null) {
            targetView.requestFocus()
        } else {
            // 目标行尚未绑定（滚动/布局中）：先滚过去，下一帧再取行聚焦
            (recyclerView.layoutManager as? LinearLayoutManager)?.scrollToPosition(target)
            recyclerView.post {
                recyclerView.findViewHolderForAdapterPosition(target)?.itemView?.requestFocus()
            }
        }
        return true
    }

    /** 当前列表项中持有焦点的位置，无则返回 [RecyclerView.NO_POSITION]。 */
    private fun focusedListPosition(recyclerView: RecyclerView): Int {
        for (index in 0 until recyclerView.childCount) {
            val child = recyclerView.getChildAt(index)
            if (child.hasFocus()) return recyclerView.getChildAdapterPosition(child)
        }
        return RecyclerView.NO_POSITION
    }

    /** 第一个可见项位置；列表未布局时退回 [fallbackPosition]（当前选中项），保证落点总有行。 */
    private fun firstListPosition(recyclerView: RecyclerView, fallbackPosition: Int): Int {
        val manager = recyclerView.layoutManager as? LinearLayoutManager
        val visible = manager?.findFirstVisibleItemPosition() ?: RecyclerView.NO_POSITION
        return if (visible != RecyclerView.NO_POSITION) visible else fallbackPosition
    }
}
