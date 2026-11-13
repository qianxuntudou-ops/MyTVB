package com.mytvb.feature.hongguo

import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.Window
import androidx.appcompat.app.AppCompatDialog
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mytvb.R
import com.mytvb.core.ui.base.DialogWindowFit
import com.mytvb.core.ui.decoration.GridSpacingItemDecoration
import com.mytvb.databinding.CellHongguoPanelItemBinding
import com.mytvb.databinding.DialogHongguoRightPanelBinding

/**
 * 红果播放页右侧竖版面板（画质/选集/倍速共用）：
 * - 贴屏幕右侧、上下撑满（[DialogWindowFit.overlay] gravity=END + fillHeight），
 *   右缘滑入动画与 CommentPanelDialog 同款
 * - 单列（spanCount=1）：选项行 + 当前项 ic_check 勾选（画质档位/倍速档位）
 * - 网格（spanCount>1）：纯数字 cell（选集），当前项靠 selected 底色高亮
 *
 * 焦点：打开即聚焦当前项（scrollToPosition + show/windowFocus 双 post 兜底，
 * SeasonEpisodePanelDialog.requestInitialSeasonFocus 同款）；RV 根 isFocusable=false
 * 防几何导航抢焦（HongguoDramaListDialog 同款坑）；item 不拦 DPAD，默认导航。
 * 选中与关闭的收尾（dismiss/回焦来源按钮）由宿主在 [onSelected]/setOnDismissListener 处理。
 */
class HongguoRightPanelDialog private constructor(
    context: Context,
    private val title: String,
    private val items: List<String>,
    private val selectedIndex: Int,
    private val spanCount: Int,
    private val onSelected: (Int) -> Unit
) : AppCompatDialog(context, R.style.DialogTheme) {

    private lateinit var binding: DialogHongguoRightPanelBinding
    private val gridMode get() = spanCount > 1
    private val adapter = PanelAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        binding = DialogHongguoRightPanelBinding.inflate(layoutInflater)
        DialogWindowFit.overlay(
            dialog = this,
            content = binding.root,
            widthPx = context.resources.getDimensionPixelSize(R.dimen.px600),
            fillHeight = true,
            gravity = Gravity.END,
            tag = "hongguo_right_panel"
        )
        // 右侧抽屉动画：开=右缘滑入，关=右飞出（CommentPanelDialog 同款）
        window?.setWindowAnimations(R.style.CommentPanelWindowAnimation)

        binding.textTitle.text = title
        binding.recyclerOptions.isFocusable = false
        if (gridMode) {
            binding.recyclerOptions.layoutManager = GridLayoutManager(context, spanCount)
            binding.recyclerOptions.addItemDecoration(
                GridSpacingItemDecoration(
                    spanCount,
                    context.resources.getDimensionPixelSize(R.dimen.px12),
                    false
                )
            )
        } else {
            binding.recyclerOptions.layoutManager = LinearLayoutManager(context)
        }
        binding.recyclerOptions.adapter = adapter
        binding.recyclerOptions.itemAnimator = null

        setOnShowListener {
            if (selectedIndex >= 0) binding.recyclerOptions.scrollToPosition(selectedIndex)
            binding.recyclerOptions.post { focusSelectedItem() }
        }
        // 首次获得窗口焦点后再聚焦一次当前项（SeasonEpisodePanelDialog 同款兜底）
        window?.decorView?.viewTreeObserver?.addOnWindowFocusChangeListener(
            object : android.view.ViewTreeObserver.OnWindowFocusChangeListener {
                override fun onWindowFocusChanged(hasFocus: Boolean) {
                    if (hasFocus) binding.recyclerOptions.post { focusSelectedItem() }
                }
            }
        )
    }

    /** 聚焦当前项；scrollToPosition 触发的二次布局未完成时再 post 一轮兜底。 */
    private fun focusSelectedItem() {
        val pos = selectedIndex.coerceAtLeast(0)
        focusItemAt(pos) ?: binding.recyclerOptions.post { focusItemAt(pos) }
    }

    private fun focusItemAt(position: Int): View? =
        binding.recyclerOptions.findViewHolderForAdapterPosition(position)
            ?.itemView?.apply { requestFocus() }

    private inner class PanelAdapter : RecyclerView.Adapter<PanelAdapter.Vh>() {

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): Vh =
            Vh(CellHongguoPanelItemBinding.inflate(layoutInflater, parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Vh, position: Int) {
            val selected = position == selectedIndex
            with(holder.b) {
                textLabel.text = items[position]
                clickView.isSelected = selected
                if (gridMode) {
                    // 纯数字居中短 cell：高亮只靠 selected 底色，勾选图标隐藏
                    clickView.gravity = Gravity.CENTER
                    val lp = textLabel.layoutParams as? android.widget.LinearLayout.LayoutParams
                    if (lp != null) {
                        lp.width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                        lp.weight = 0f
                        textLabel.gravity = Gravity.CENTER
                        textLabel.layoutParams = lp
                    }
                    iconCheck.visibility = View.GONE
                } else {
                    iconCheck.visibility = if (selected) View.VISIBLE else View.INVISIBLE
                }
            }
        }

        inner class Vh(val b: CellHongguoPanelItemBinding) : RecyclerView.ViewHolder(b.root) {
            init {
                b.clickView.setOnClickListener {
                    val pos = bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) onSelected(pos)
                }
            }
        }
    }

    companion object {
        /**
         * @param selectedIndex 当前项下标（打开时滚动到并聚焦；<0 视为第 0 项）
         * @param spanCount 1=单列选项行；>1=纯数字网格（选集）
         */
        fun show(
            context: Context,
            title: String,
            items: List<String>,
            selectedIndex: Int,
            spanCount: Int = 1,
            onSelected: (Int) -> Unit
        ): HongguoRightPanelDialog {
            val dialog = HongguoRightPanelDialog(
                context, title, items, selectedIndex, spanCount, onSelected
            )
            dialog.show()
            return dialog
        }
    }
}
