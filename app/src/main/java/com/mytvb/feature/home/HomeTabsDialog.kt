package com.mytvb.feature.home

import com.mytvb.core.ui.video.resolveThemedTextColor

import android.content.Context
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatDialog
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mytvb.R
import com.mytvb.core.ui.base.AppToast
import com.mytvb.core.ui.base.DialogWindowFit
import com.mytvb.core.ui.decoration.LinearSpacingItemDecoration
import com.mytvb.databinding.CellHomeTabEntryBinding
import com.mytvb.databinding.DialogHomeTabsBinding

/**
 * 首页页签设置弹窗：调整页签顺序与显示/隐藏，确认后落盘并即时应用到首页。
 *
 * 交互骨架与控制条按钮排序弹窗（ControlBarOrderManageDialog）统一：
 * - 键鼠路由由 [dispatchKeyEvent] 统一接管，**不走系统焦点链**（TV 上 requestFocus
 *   受窗口/touch mode 影响，触摸后再按键焦点会丢）；选中态用 isSelected 自绘。
 * - 上下键移动选中（可到末尾「确认」按钮）；短按 OK 切换该页签显示/隐藏；
 *   **长按 OK** 拿起整行，上下键换位，再单击 OK/BACK 放下；
 * - 触摸：点击行切换显示，长按拖动排序（ItemTouchHelper）。
 * 隐藏项仍保留在列表中（否则无法再打开），顺序对隐藏项同样生效；至少保留一个可见页签。
 */
class HomeTabsDialog(
    context: Context,
    private val onConfirmed: () -> Unit = {},
) : AppCompatDialog(context, R.style.DialogTheme) {

    private val entries = HomeTabPrefs.loadEntries().toMutableList()
    private var adapter: TabsAdapter? = null
    private var recyclerView: RecyclerView? = null
    private var confirmButton: View? = null
    private var hintView: TextView? = null

    /** 选中位：0..entries.lastIndex = 行；entries.size = 底部「确认」按钮。 */
    private var selectedPosition = 0

    /** 键盘移动态（长按拿起）行位；-1 = 非移动态。 */
    private var movingPosition = -1

    /** 触摸拖动中的行位（仅视觉标记）。 */
    private var dragPosition = -1

    /** 短按 OK 的 DOWN 登记位：UP 未进移动态才执行动作，DOWN 时不执行（否则长按先误触发）。 */
    private var pendingConfirmDownTime = -1L

    private var toastHost: ViewGroup? = null

    init {
        setCanceledOnTouchOutside(false)
        val binding = DialogHomeTabsBinding.inflate(LayoutInflater.from(context), null, false)
        DialogWindowFit.overlay(
            this,
            binding.root,
            context.resources.getDimensionPixelSize(R.dimen.px800),
            heightPx = context.resources.getDimensionPixelSize(R.dimen.px700),
            tag = "home_tabs",
        )
        binding.topTitle.text = context.getString(R.string.home_tabs_dialog_title)
        hintView = binding.tvHint
        confirmButton = binding.buttonConfirm.also { button ->
            button.setOnClickListener { confirmAndDismiss() }
        }
        recyclerView = binding.recyclerView.also { view ->
            view.layoutManager = LinearLayoutManager(context)
            view.adapter = TabsAdapter().also { adapter = it }
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
        // 注册为 Toast 高层宿主：AppToast 挂 Activity decor，会被本弹窗的独立 Window 压住
        setOnShowListener {
            toastHost = window?.decorView as? ViewGroup
            AppToast.attachHost(toastHost)
            refreshVisual()
        }
        setOnDismissListener {
            AppToast.detachHost(toastHost)
            toastHost = null
        }
        refreshVisual()
    }

    override fun onBackPressed() {
        // 移动态中 BACK = 放下（不关弹窗）
        if (movingPosition >= 0) {
            exitMoving()
            return
        }
        super.onBackPressed()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val isConfirmKey = event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            event.keyCode == KeyEvent.KEYCODE_ENTER ||
            event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER

        // 移动态：上下键换位，短按 OK / BACK 放下。UP 全部吞掉——长按松手那次若流入
        // 行 View 会触发行的 click（切换显示）。
        if (movingPosition >= 0) {
            if (event.action == KeyEvent.ACTION_UP) return true
            if (event.action != KeyEvent.ACTION_DOWN) return false
            return when {
                event.keyCode == KeyEvent.KEYCODE_DPAD_UP -> {
                    moveRow(movingPosition, movingPosition - 1)
                    true
                }
                event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> {
                    moveRow(movingPosition, movingPosition + 1)
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

        // 短按动作必须落在 UP：DOWN 时就切换的话，长按会先误触发一次切换/确认。
        // DOWN 仅登记，长按（repeat>0 / FLAG_LONG_PRESS）转移动态时作废。
        if (event.action == KeyEvent.ACTION_UP) {
            if (isConfirmKey && pendingConfirmDownTime != -1L &&
                event.downTime == pendingConfirmDownTime
            ) {
                pendingConfirmDownTime = -1L
                onConfirmKey()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        if (isConfirmKey) {
            if (event.repeatCount > 0 || event.isLongPress) {
                pendingConfirmDownTime = -1L
                if (selectedPosition in entries.indices) {
                    enterMoving()
                    return true
                }
                return false
            }
            pendingConfirmDownTime = event.downTime
            return true
        }
        return when {
            event.keyCode == KeyEvent.KEYCODE_DPAD_UP -> {
                moveSelection(-1)
                true
            }
            event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> {
                moveSelection(+1)
                true
            }
            else -> super.dispatchKeyEvent(event)
        }
    }

    /** 上下键移动选中高亮；末行再向下选中「确认」按钮。 */
    private fun moveSelection(delta: Int) {
        if (entries.isEmpty()) return
        val next = (selectedPosition + delta).coerceIn(0, entries.size)
        if (next == selectedPosition) return
        selectedPosition = next
        refreshVisual()
        scrollToSelection()
    }

    /** OK 短按：选中「确认」= 保存；选中行 = 切换显示/隐藏。 */
    private fun onConfirmKey() {
        if (selectedPosition == entries.size) {
            confirmAndDismiss()
            return
        }
        if (selectedPosition in entries.indices) {
            toggleAt(selectedPosition)
        }
    }

    /** 长按 OK：把当前选中行拿起来，上下键换位、单击 OK/BACK 放下。 */
    private fun enterMoving() {
        if (movingPosition >= 0 || selectedPosition !in entries.indices) return
        movingPosition = selectedPosition
        refreshVisual()
    }

    private fun exitMoving() {
        if (movingPosition < 0) return
        selectedPosition = movingPosition
        movingPosition = -1
        refreshVisual()
    }

    private fun moveRow(from: Int, to: Int) {
        if (from !in entries.indices || to !in entries.indices || from == to) return
        entries.add(to, entries.removeAt(from))
        movingPosition = to
        selectedPosition = to
        adapter?.notifyItemMoved(from, to)
        scrollToSelection()
    }

    private fun toggleAt(position: Int) {
        if (position !in entries.indices) return
        if (movingPosition >= 0 || dragPosition >= 0) return
        val entry = entries[position]
        if (entry.visible && entries.count { it.visible } <= 1) {
            AppToast.show(context, context.getString(R.string.home_tabs_keep_one))
            return
        }
        selectedPosition = position
        entries[position] = entry.copy(visible = !entry.visible)
        refreshVisual()
    }

    private fun confirmAndDismiss() {
        HomeTabPrefs.saveEntries(entries)
        dismiss()
        onConfirmed()
    }

    private fun refreshVisual() {
        adapter?.notifyDataSetChanged()
        confirmButton?.isSelected = selectedPosition == entries.size && movingPosition < 0
        updateHint()
    }

    private fun scrollToSelection() {
        val view = recyclerView ?: return
        val target = selectedPosition.coerceAtMost(entries.size - 1)
        if (target < 0) return
        view.post { view.scrollToPosition(target) }
    }

    private fun updateHint() {
        hintView?.setText(
            if (movingPosition >= 0 || dragPosition >= 0) R.string.home_tabs_drag_hint
            else R.string.home_tabs_dialog_hint
        )
    }

    private inner class TabsAdapter : RecyclerView.Adapter<TabsAdapter.TabViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TabViewHolder =
            TabViewHolder(
                CellHomeTabEntryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            )

        override fun getItemCount(): Int = entries.size

        override fun onBindViewHolder(holder: TabViewHolder, position: Int) {
            holder.bind(position)
        }

        inner class TabViewHolder(
            private val binding: CellHomeTabEntryBinding
        ) : RecyclerView.ViewHolder(binding.root) {

            init {
                // 触摸点击 = 选中该行并切换显示（键盘 OK 由 dispatchKeyEvent 接管）
                binding.root.setOnClickListener {
                    val position = bindingAdapterPosition
                    if (position == RecyclerView.NO_POSITION) return@setOnClickListener
                    selectedPosition = position
                    toggleAt(position)
                }
            }

            fun bind(position: Int) {
                val context = binding.root.context
                val entry = entries[position]
                val moving = position == movingPosition || position == dragPosition
                binding.root.isSelected = position == selectedPosition && !moving
                binding.root.isActivated = moving
                binding.tvIndex.text = "${position + 1}."
                binding.tvName.text = context.getString(entry.titleRes)
                binding.tvArrow.visibility = if (moving) View.VISIBLE else View.INVISIBLE

                val highlighted = binding.root.isSelected || moving
                val hiddenColor = ContextCompat.getColor(context, R.color.gray_600)
                // 选中/移动高亮块=粉色，块上文字恒主文字色（白主题黑字），激活标识走 ✓ 粉色
                val normalColor = resolveThemedTextColor(context, android.R.attr.textColorPrimary, 0xFFEEEEEE.toInt())
                val nameColor = when {
                    highlighted -> normalColor
                    entry.visible -> normalColor
                    else -> hiddenColor
                }
                binding.tvName.setTextColor(nameColor)
                binding.tvIndex.setTextColor(if (entry.visible || highlighted) normalColor else hiddenColor)

                if (entry.visible) {
                    binding.tvState.text = "✓"
                    binding.tvState.setTextColor(resolveThemedTextColor(context, androidx.appcompat.R.attr.colorAccent, 0xFFBB86FC.toInt()))
                } else {
                    binding.tvState.text = context.getString(R.string.home_tabs_state_hidden)
                    binding.tvState.setTextColor(if (highlighted) normalColor else hiddenColor)
                }
            }
        }
    }

    /** 触摸长按拖动重排：拖动中实时换位（草稿），松手刷新视觉。 */
    private inner class DragCallback : ItemTouchHelper.SimpleCallback(
        ItemTouchHelper.UP or ItemTouchHelper.DOWN,
        0,
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
            if (from !in entries.indices || to !in entries.indices) return false
            entries.add(to, entries.removeAt(from))
            dragPosition = to
            adapter?.notifyItemMoved(from, to)
            return true
        }

        override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(viewHolder, actionState)
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
                dragPosition = viewHolder.bindingAdapterPosition
                viewHolder.itemView.isSelected = false
                viewHolder.itemView.isActivated = true
                updateHint()
            }
        }

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            dragPosition = -1
            selectedPosition = selectedPosition.coerceIn(0, entries.size)
            // 拖动中的 notifyItemMoved 不重绑，行首序号会停留旧值：全量刷新
            refreshVisual()
        }
    }
}
