package com.mytvb.feature.category

import com.mytvb.core.ui.video.resolveThemedTextColor

import android.content.Context
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatDialog
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mytvb.R
import com.mytvb.core.ui.base.AppToast
import com.mytvb.core.ui.base.DialogWindowFit
import com.mytvb.core.ui.decoration.LinearSpacingItemDecoration
import com.mytvb.databinding.CellSettingChoiceBinding
import com.mytvb.databinding.DialogCategoryTabsBinding
import com.mytvb.model.CategoryModel

/**
 * 分类页分区多选弹窗（issue#117）：勾选要显示的分区，确认后落盘。
 *
 * 壳沿用 dialog_setting_choice 的宽面板形态（px800×px700），行布局复用
 * cell_setting_choice——多选态下 iconCheck 表示勾选、tvCurrent 徽章恒隐藏。
 * 上下键在弹窗顶层显式接管（同 [com.mytvb.core.ui.base.ChoiceDialog]：防止
 * 焦点被可聚焦的布局根吸走），并在列表与底部按钮区之间显式交接。
 */
object CategoryTabsDialog {

    fun show(context: Context, onConfirmed: () -> Unit) {
        val all = CategoryTabPrefs.allCategories(context)
        val stored = CategoryTabPrefs.selectedIds()
        // 未设置 = 默认全选（与分类页默认行为一致）
        val checked = if (stored.isEmpty()) {
            all.map { it.id }.toMutableSet()
        } else {
            all.filter { stored.contains(it.id.toString()) }.map { it.id }.toMutableSet()
        }

        val dialog = AppCompatDialog(context, R.style.DialogTheme)
        dialog.setCanceledOnTouchOutside(false)
        val binding = DialogCategoryTabsBinding.inflate(LayoutInflater.from(context), null, false)
        DialogWindowFit.overlay(
            dialog,
            binding.root,
            context.resources.getDimensionPixelSize(R.dimen.px800),
            heightPx = context.resources.getDimensionPixelSize(R.dimen.px700),
            tag = "category_tabs"
        )
        binding.topTitle.text = context.getString(R.string.category_tabs_dialog_title)

        val adapter = TabsAdapter(all, checked)
        binding.recyclerView.layoutManager = LinearLayoutManager(context)
        binding.recyclerView.adapter = adapter
        if (binding.recyclerView.itemDecorationCount == 0) {
            binding.recyclerView.addItemDecoration(
                LinearSpacingItemDecoration(
                    context.resources.getDimensionPixelSize(R.dimen.px2),
                    includeBottom = true
                )
            )
        }

        binding.buttonSelectNone.setOnClickListener {
            checked.clear()
            adapter.notifyDataSetChanged()
        }
        binding.buttonSelectAll.setOnClickListener {
            checked.clear()
            checked.addAll(all.map { it.id })
            adapter.notifyDataSetChanged()
        }
        binding.buttonConfirm.setOnClickListener {
            if (checked.isEmpty()) {
                AppToast.show(context, context.getString(R.string.category_tabs_keep_one))
                return@setOnClickListener
            }
            CategoryTabPrefs.saveSelectedIds(checked.map(Int::toString).toSet())
            dialog.dismiss()
            onConfirmed()
        }

        // 注册为 Toast 高层宿主（同 HomeTabsDialog）：「至少保留一个分区」提示才不会被弹窗压住
        var toastHost: ViewGroup? = null
        dialog.setOnShowListener {
            toastHost = dialog.window?.decorView as? ViewGroup
            AppToast.attachHost(toastHost)
            binding.recyclerView.post { adapter.requestInitialFocus(binding.recyclerView) }
        }
        dialog.setOnDismissListener {
            AppToast.detachHost(toastHost)
            toastHost = null
        }
        // 上下键显式接管：列表内移行；底行 DOWN 落到「全选」；按钮 UP 回列表
        dialog.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            val recyclerView = binding.recyclerView
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    val step = if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1
                    if (buttonBarHasFocus(binding)) {
                        if (step < 0) focusListEdge(recyclerView, adapter, last = true) else true
                    } else {
                        moveListFocus(recyclerView, adapter, step)
                    }
                }
                else -> false
            }
        }
        dialog.show()
    }

    /** 焦点是否在底部按钮区（列表外）。 */
    private fun buttonBarHasFocus(binding: DialogCategoryTabsBinding): Boolean =
        binding.buttonBar.hasFocus()

    /** 列表内行间移动；首行 UP 停住消费、末行 DOWN 交给按钮区（不消费由系统落焦）。 */
    private fun moveListFocus(
        recyclerView: RecyclerView,
        adapter: TabsAdapter,
        step: Int
    ): Boolean {
        val count = adapter.itemCount
        if (count == 0) return false
        val current = focusedListPosition(recyclerView)
        // 末行再 DOWN：不消费，让焦点自然落到底部按钮；首行再 UP：消费防焦点飞出弹窗
        if (current != RecyclerView.NO_POSITION) {
            if (step > 0 && current >= count - 1) return false
            if (step < 0 && current <= 0) return true
        }
        val target = when {
            current != RecyclerView.NO_POSITION -> current + step
            else -> firstListPosition(recyclerView).coerceIn(0, count - 1)
        }
        focusPosition(recyclerView, target)
        return true
    }

    /** 聚焦列表边缘行（按钮区按 UP 的落点）。 */
    private fun focusListEdge(recyclerView: RecyclerView, adapter: TabsAdapter, last: Boolean): Boolean {
        val count = adapter.itemCount
        if (count == 0) return false
        val manager = recyclerView.layoutManager as? LinearLayoutManager ?: return false
        val target = if (last) {
            (manager.findLastVisibleItemPosition()).coerceIn(0, count - 1)
        } else {
            (manager.findFirstVisibleItemPosition()).coerceIn(0, count - 1)
        }
        focusPosition(recyclerView, target)
        return true
    }

    private fun focusPosition(recyclerView: RecyclerView, position: Int) {
        val targetView = recyclerView.findViewHolderForAdapterPosition(position)?.itemView
        if (targetView != null) {
            targetView.requestFocus()
        } else {
            // 目标行尚未绑定（滚动/布局中）：先滚过去，下一帧再取行聚焦
            (recyclerView.layoutManager as? LinearLayoutManager)?.scrollToPosition(position)
            recyclerView.post {
                recyclerView.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus()
            }
        }
    }

    private fun focusedListPosition(recyclerView: RecyclerView): Int {
        for (index in 0 until recyclerView.childCount) {
            val child = recyclerView.getChildAt(index)
            if (child.hasFocus()) return recyclerView.getChildAdapterPosition(child)
        }
        return RecyclerView.NO_POSITION
    }

    private fun firstListPosition(recyclerView: RecyclerView): Int {
        val manager = recyclerView.layoutManager as? LinearLayoutManager
        val visible = manager?.findFirstVisibleItemPosition() ?: RecyclerView.NO_POSITION
        return if (visible != RecyclerView.NO_POSITION) visible else 0
    }

    /** 多选行：点击切勾选（不关弹窗），iconCheck 显隐即勾选态，勾选行标题提亮。 */
    private class TabsAdapter(
        private val categories: List<CategoryModel>,
        private val checked: MutableSet<Int>
    ) : RecyclerView.Adapter<TabsAdapter.TabViewHolder>() {

        fun requestInitialFocus(recyclerView: RecyclerView) {
            recyclerView.scrollToPosition(0)
            recyclerView.post {
                recyclerView.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TabViewHolder {
            val binding = CellSettingChoiceBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return TabViewHolder(binding)
        }

        override fun onBindViewHolder(holder: TabViewHolder, position: Int) {
            holder.bind(categories[position], checked.contains(categories[position].id))
        }

        override fun getItemCount(): Int = categories.size

        inner class TabViewHolder(
            private val binding: CellSettingChoiceBinding
        ) : RecyclerView.ViewHolder(binding.root) {

            init {
                binding.root.setOnClickListener {
                    val position = bindingAdapterPosition
                    if (position == RecyclerView.NO_POSITION) return@setOnClickListener
                    val id = categories[position].id
                    if (!checked.add(id)) {
                        checked.remove(id)
                    }
                    notifyItemChanged(position)
                }
                binding.root.setOnFocusChangeListener { _, hasFocus ->
                    val position = bindingAdapterPosition
                    if (position == RecyclerView.NO_POSITION) {
                        return@setOnFocusChangeListener
                    }
                    applyState(
                        isChecked = checked.contains(categories[position].id),
                        isFocused = hasFocus
                    )
                }
            }

            fun bind(category: CategoryModel, isChecked: Boolean) {
                binding.tvTitle.text = category.name
                applyState(isChecked = isChecked, isFocused = binding.root.hasFocus())
            }

            private fun applyState(isChecked: Boolean, isFocused: Boolean) {
                binding.tvCurrent.visibility = View.GONE
                binding.iconCheck.visibility = if (isChecked) View.VISIBLE else View.GONE
                binding.root.animate()
                    .scaleX(if (isFocused) 1.02f else 1f)
                    .scaleY(if (isFocused) 1.02f else 1f)
                    .setDuration(120L)
                    .start()
                // 三态统一：焦点块（粉色）上文字恒主文字色；非聚焦勾选项=强调粉字
                val titleColor = when {
                    isFocused -> resolveThemedTextColor(binding.root.context, android.R.attr.textColorPrimary, 0xFFEEEEEE.toInt())
                    isChecked -> resolveThemedTextColor(binding.root.context, androidx.appcompat.R.attr.colorAccent, 0xFFBB86FC.toInt())
                    else -> resolveThemedTextColor(binding.root.context, android.R.attr.textColorPrimary, 0xFFEEEEEE.toInt())
                }
                binding.tvTitle.setTextColor(titleColor)
            }
        }
    }
}
