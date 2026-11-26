package com.mytvb.feature.player.controlbar

import android.content.Context
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatDialog
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mytvb.R
import com.mytvb.core.ui.base.DialogWindowFit
import com.mytvb.core.ui.decoration.LinearSpacingItemDecoration
import com.mytvb.databinding.CellControlOrderEntryBinding
import com.mytvb.feature.player.shortcut.showShortcutChoiceDialog
import java.util.Collections

/**
 * 控制条按钮排序管理弹窗（issue#144 建议）。
 * 按键路由由本弹窗 dispatchKeyEvent 统一接管，**不走系统焦点链**（TV 上子 view
 * requestFocus 受窗口/touch mode 影响假失败，触摸后再按键焦点丢失）：
 * - 正常态：上下键移动选中高亮（-1=顶部「恢复默认排序」钮），OK 进入移动态，BACK 关弹窗；
 * - 移动态：上下键直接换位（实时落盘），OK/BACK 结束移动并保持选中该行；
 * - 触摸：tap 行=选中；长按行拖动重排（[ItemTouchHelper]），拖完落盘。
 * 每次调整即时保存 [ControlBarOrderStore.save]；生效时机=重进播放页（控制条构造期读取）。
 */
class ControlBarOrderManageDialog(
    context: Context,
    private val onChanged: () -> Unit = {},
) : AppCompatDialog(context, R.style.DialogTheme) {

    private val rows = mutableListOf<ControlBarButton>()
    private val adapter = OrderAdapter()
    private var recyclerView: RecyclerView? = null
    private var hintView: TextView? = null
    private var resetButton: View? = null

    /** 键盘选中位：-1=「恢复默认排序」钮，0..rows-1=列表行。触摸 tap 行同样驱动它。 */
    private var selectedPosition = 0

    /** 键盘移动态行位；-1=非移动态。视觉与触摸拖动共享行的 activated 态（见拖动回调）。 */
    private var movingPosition = -1

    /** 触摸拖动中的行位（仅视觉标记，落盘在 clearView）。 */
    private var dragPosition = -1

    /** 短按 OK 的 DOWN 登记位：UP 未进移动态才执行动作（否则长按会先误触发）。 */
    private var pendingConfirmDownTime = -1L

    init {
        DialogWindowFit.overlay(
            this,
            LayoutInflater.from(context).inflate(R.layout.dialog_control_order_manage, null),
            context.resources.getDimensionPixelSize(R.dimen.px800),
            heightPx = context.resources.getDimensionPixelSize(R.dimen.px615),
            tag = "controlOrderManage",
        )
        findViewById<View>(R.id.dialog_root)?.setOnClickListener { dismiss() }
        resetButton = findViewById<View>(R.id.btn_reset)?.also { button ->
            button.setOnClickListener { confirmReset() }
        }
        hintView = findViewById<TextView>(R.id.order_hint)

        recyclerView = findViewById<RecyclerView>(R.id.recyclerView)?.also { view ->
            view.layoutManager = LinearLayoutManager(context)
            view.adapter = adapter
            if (view.itemDecorationCount == 0) {
                view.addItemDecoration(
                    LinearSpacingItemDecoration(
                        context.resources.getDimensionPixelSize(R.dimen.px2),
                        includeBottom = true,
                    ),
                )
            }
            ItemTouchHelper(DragCallback()).attachToRecyclerView(view)
        }
        refresh()
        updateHint()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val isConfirmKey = event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            event.keyCode == KeyEvent.KEYCODE_ENTER ||
            event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER

        // 移动态：上下键换位；短按 OK / BACK 放下。UP 全部吞掉——长按松手那次若流入
        // 行 View 会触发行 tap（选中跳到别处）。
        if (movingPosition >= 0) {
            if (event.action == KeyEvent.ACTION_UP) return true
            if (event.action != KeyEvent.ACTION_DOWN) return false
            return when {
                event.keyCode == KeyEvent.KEYCODE_DPAD_UP -> {
                    moveRow(movingPosition, -1)
                    true
                }
                event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> {
                    moveRow(movingPosition, 1)
                    true
                }
                isConfirmKey && event.repeatCount == 0 -> {
                    exitMoving()
                    true
                }
                event.keyCode == KeyEvent.KEYCODE_BACK -> {
                    exitMoving()
                    true
                }
                else -> true
            }
        }

        // 短按动作必须落在 UP：DOWN 时就执行的话，长按会先误触发一次（恢复默认确认框）。
        // DOWN 仅登记，长按（repeat>0 / FLAG_LONG_PRESS）转移动态时作废。
        if (event.action == KeyEvent.ACTION_UP) {
            if (isConfirmKey && pendingConfirmDownTime != -1L &&
                event.downTime == pendingConfirmDownTime
            ) {
                pendingConfirmDownTime = -1L
                confirmSelection()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        // 长按 OK = 进入移动（与「首页页签」弹窗统一）：短按不再进入，长按由系统 repeat
        // 事件驱动（View.onLongClick 不响应遥控器长按）。
        if (isConfirmKey) {
            if (event.repeatCount > 0 || event.isLongPress) {
                pendingConfirmDownTime = -1L
                if (selectedPosition >= 0) {
                    enterMoving()
                    return true
                }
                return false
            }
            pendingConfirmDownTime = event.downTime
            return true
        }
        when {
            event.keyCode == KeyEvent.KEYCODE_DPAD_UP -> {
                moveSelection(-1)
                return true
            }
            event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> {
                moveSelection(+1)
                return true
            }
            event.keyCode == KeyEvent.KEYCODE_BACK -> {
                if (movingPosition >= 0) {
                    exitMoving()
                    return true
                }
                // 正常态走默认 BACK=关弹窗
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** 上下键：移动态=换位；正常态=移动选中高亮（顶部再向上=选中「恢复默认排序」钮）。 */
    private fun moveSelection(delta: Int) {
        if (movingPosition >= 0) {
            moveRow(movingPosition, delta)
            return
        }
        val next = (selectedPosition + delta).coerceIn(-1, rows.lastIndex)
        if (next == selectedPosition) return
        selectedPosition = next
        refreshVisual()
        scrollToSelection()
    }

    /** OK 短按：「恢复默认排序」钮=确认重置；选中行不再进入移动（改为长按，见 dispatchKeyEvent）。 */
    private fun confirmSelection() {
        if (movingPosition >= 0) {
            exitMoving()
            return
        }
        if (selectedPosition < 0) {
            confirmReset()
        }
    }

    /** 长按 OK：把当前选中行拿起来（移动态），上下键换位、短按 OK/BACK 放下。 */
    private fun enterMoving() {
        if (movingPosition >= 0) return
        if (selectedPosition !in rows.indices) return
        movingPosition = selectedPosition
        refreshVisual()
        updateHint()
    }

    private fun exitMoving() {
        if (movingPosition < 0) return
        selectedPosition = movingPosition
        movingPosition = -1
        refreshVisual()
        updateHint()
    }

    private fun moveRow(position: Int, delta: Int) {
        val target = position + delta
        if (target !in rows.indices) return
        val moved = rows.removeAt(position)
        rows.add(target, moved)
        ControlBarOrderStore.save(rows)
        movingPosition = target
        selectedPosition = target
        refreshVisual()
        scrollToSelection()
        onChanged()
    }

    /** 恢复默认=清掉自定义相对顺序，破坏性较低但仍二次确认（TV 焦点误按兜底）。 */
    private fun confirmReset() {
        if (!ControlBarOrderStore.isCustomized()) return
        showShortcutChoiceDialog(
            context,
            context.getString(R.string.control_bar_order_reset_confirm),
            arrayOf(
                context.getString(R.string.control_bar_order_reset),
                context.getString(R.string.cancel),
            ),
            0,
        ) { index ->
            if (index == 0) {
                ControlBarOrderStore.clear()
                selectedPosition = 0
                movingPosition = -1
                refresh()
                updateHint()
                onChanged()
            }
        }
    }

    private fun refresh() {
        rows.clear()
        rows.addAll(ControlBarOrderStore.resolveOrder())
        refreshVisual()
    }

    private fun refreshVisual() {
        adapter.notifyDataSetChanged()
        resetButton?.isSelected = selectedPosition < 0
    }

    private fun scrollToSelection() {
        val view = recyclerView ?: return
        view.post {
            if (selectedPosition < 0) {
                view.scrollToPosition(0)
            } else {
                view.scrollToPosition(selectedPosition)
            }
        }
    }

    private fun updateHint() {
        hintView?.text = context.getString(
            if (movingPosition >= 0) R.string.control_bar_order_hint_moving
            else R.string.control_bar_order_hint_normal,
        )
    }

    private inner class OrderAdapter : RecyclerView.Adapter<OrderViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): OrderViewHolder =
            OrderViewHolder(CellControlOrderEntryBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = rows.size

        override fun onBindViewHolder(holder: OrderViewHolder, position: Int) {
            holder.binding.tvIndex.text = "${position + 1}."
            holder.binding.tvName.text = context.getString(rows[position].labelRes)
            val moving = position == movingPosition || position == dragPosition
            holder.binding.root.isSelected = position == selectedPosition && !moving
            holder.binding.root.isActivated = moving
            holder.binding.tvArrow.visibility = if (moving) View.VISIBLE else View.GONE
            holder.binding.root.setOnClickListener {
                // 触摸 tap=选中（键盘高亮跟随），与拖动/按键共用同一选中态
                selectedPosition = holder.bindingAdapterPosition
                refreshVisual()
            }
        }
    }

    private class OrderViewHolder(val binding: CellControlOrderEntryBinding) : RecyclerView.ViewHolder(binding.root)

    /** 触摸长按拖动重排：拖动中实时换位（不落盘），松手 clearView 统一落盘+修正序号。 */
    private inner class DragCallback : ItemTouchHelper.SimpleCallback(
        ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0,
    ) {
        override fun isLongPressDragEnabled(): Boolean = true

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
            // 仅支持拖动重排，不支持滑动删除
        }

        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder,
        ): Boolean {
            val from = viewHolder.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            if (from !in rows.indices || to !in rows.indices) return false
            rows.add(to, rows.removeAt(from))
            adapter.notifyItemMoved(from, to)
            return true
        }

        override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(viewHolder, actionState)
            dragPosition = viewHolder?.bindingAdapterPosition ?: -1
            viewHolder?.itemView?.isActivated = dragPosition >= 0
        }

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            dragPosition = -1
            selectedPosition = viewHolder.bindingAdapterPosition.coerceAtLeast(0)
            movingPosition = -1
            ControlBarOrderStore.save(rows)
            // 全量刷新：拖动过程中的 notifyItemMoved 不重 bind，行首序号会停留旧位置
            refreshVisual()
            updateHint()
            onChanged()
        }
    }
}
