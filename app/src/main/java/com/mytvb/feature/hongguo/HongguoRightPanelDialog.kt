package com.mytvb.feature.hongguo

import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.appcompat.app.AppCompatDialog
import androidx.appcompat.widget.LinearLayoutCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mytvb.R
import com.mytvb.core.ui.base.DialogWindowFit
import com.mytvb.databinding.CellHongguoPanelItemBinding
import com.mytvb.databinding.DialogHongguoRightPanelBinding

/**
 * 红果播放页右侧竖版面板（画质/选集/倍速共用）：
 * - 贴屏幕右侧、上下撑满（[DialogWindowFit.overlay] gravity=END + fillHeight），
 *   右缘滑入动画与 CommentPanelDialog 同款
 * - 单列（spanCount=1）：选项行 + 当前项 ic_check 勾选（画质档位/倍速档位）
 * - 网格（spanCount>1）：纯数字 cell（选集），当前项靠 selected 底色高亮。
 *   **实现用「每行一个 item 的 LinearLayoutManager」而非 GridLayoutManager**：
 *   GridLayoutManager 在 fillHeight 右侧弹窗实测只绘制前 2 行（布局 54 个 / 屏幕仅 9 个），
 *   换 LM 实现、换 WrapContentGridLayoutManager、去 decoration 均无效；单列 LM 路径（画质面板）正常。
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
    private val cellGapPx by lazy { context.resources.getDimensionPixelSize(R.dimen.px6) }
    private val cellHeightPx by lazy { context.resources.getDimensionPixelSize(R.dimen.px100) }
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
            // 网格：一次性构建的 ScrollView（不回收、无异步布局）——RecyclerView（Grid/Linear 行 item 两种）
            // 在本环境实测只绘制首屏一小部分格子（布局树里全部正确），ScrollView 全量构建绕开该路径
            binding.recyclerOptions.visibility = View.GONE
            binding.scrollGrid.visibility = View.VISIBLE
            buildGrid()
        } else {
            // 单列选项（画质/倍速）：RecyclerView 已验证正常
            binding.recyclerOptions.layoutManager = LinearLayoutManager(context)
            binding.recyclerOptions.adapter = adapter
            binding.recyclerOptions.itemAnimator = null
        }
        // 模拟器（Android 14）吞帧兜底：布局变化后强制整窗重绘
        binding.root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            window?.decorView?.let { decor ->
                decor.invalidate()
                decor.postInvalidateOnAnimation()
            }
        }

        setOnShowListener {
            binding.root.post { scrollToSelectedItem() }
        }
        // 首次获得窗口焦点后再定位一次当前项（SeasonEpisodePanelDialog 同款兜底）
        window?.decorView?.viewTreeObserver?.addOnWindowFocusChangeListener(
            object : android.view.ViewTreeObserver.OnWindowFocusChangeListener {
                override fun onWindowFocusChanged(hasFocus: Boolean) {
                    if (hasFocus) binding.root.post { scrollToSelectedItem() }
                }
            }
        )
    }

    /**
     * 打开时把当前项滚到可视区并聚焦（ScrollView 全量构建后无 scrollToPosition，
     * 需显式滚动；行未完成布局时（height=0）逐帧重试）。
     */
    private fun scrollToSelectedItem(depth: Int = 0) {
        val index = selectedIndex.coerceAtLeast(0).coerceAtMost(items.size - 1).coerceAtLeast(0)
        if (gridMode) {
            val row = binding.gridContainer.getChildAt(index / spanCount) as? View
            if (row == null || row.height <= 0) {
                if (depth < 5) binding.root.post { scrollToSelectedItem(depth + 1) }
                return
            }
            // 该行居中显示，当前集尽量在视口中部
            val halfViewport = binding.scrollGrid.height / 2
            binding.scrollGrid.scrollTo(0, (row.top - halfViewport + row.height / 2).coerceAtLeast(0))
            (row as? ViewGroup)?.getChildAt(index % spanCount)?.requestFocus()
        } else {
            val vh = binding.recyclerOptions.findViewHolderForAdapterPosition(index)
            if (vh == null) {
                if (depth < 5) binding.root.post { scrollToSelectedItem(depth + 1) }
                return
            }
            vh.itemView.requestFocus()
        }
    }

    /** 网格一次性构建：每行一个横向 LinearLayout，cell 固定 px100 高、均分宽度。 */
    private fun buildGrid() {
        val container = binding.gridContainer
        container.removeAllViews()
        val rowCount = (items.size + spanCount - 1) / spanCount
        for (r in 0 until rowCount) {
            val row = LinearLayoutCompat(context)
            row.orientation = LinearLayoutCompat.HORIZONTAL
            row.isBaselineAligned = false
            for (col in 0 until spanCount) {
                val index = r * spanCount + col
                val cell = CellHongguoPanelItemBinding.inflate(layoutInflater, container, false)
                with(cell) {
                    // 网格内边距压到最小：cell 宽约 89px，布局默认左右各 px30 会只剩 29px，
                    // 单字符数字勉强放得下、双位数（"10" 起）直接被裁成空白（可聚焦但看不见文字）
                    val pad = context.resources.getDimensionPixelSize(R.dimen.px6)
                    clickView.setPadding(pad, 0, pad, 0)
                    clickView.gravity = Gravity.CENTER
                    textLabel.gravity = Gravity.CENTER
                    textLabel.isSingleLine = true
                    textLabel.setTextSize(
                        android.util.TypedValue.COMPLEX_UNIT_PX,
                        context.resources.getDimension(R.dimen.px31)
                    )
                    // 文字裁切兜底：单行 + 不省略，超宽时缩小而非丢弃
                    textLabel.ellipsize = null
                    iconCheck.visibility = View.GONE
                    if (index < items.size) {
                        textLabel.text = items[index]
                        clickView.isSelected = index == selectedIndex
                        clickView.setOnClickListener { onSelected(index) }
                    } else {
                        clickView.isFocusable = false
                        clickView.isClickable = false
                        clickView.isSelected = false
                        textLabel.text = ""
                    }
                }
                val lp = LinearLayoutCompat.LayoutParams(0, cellHeightPx, 1f)
                if (col > 0) lp.marginStart = cellGapPx
                row.addView(cell.root, lp)
            }
            val rowLp = LinearLayoutCompat.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            if (r > 0) rowLp.topMargin = cellGapPx
            container.addView(row, rowLp)
        }
    }

    /** 触摸滚动（鼠标滚轮/滑动）会清焦点：之后首个方向键把焦点送回当前项再导航，
     *  防止焦点恢复落在全屏遮罩容器上导致方向键失灵。 */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val host: View = if (gridMode) binding.scrollGrid else binding.recyclerOptions
        if (event.action == KeyEvent.ACTION_DOWN &&
            event.keyCode in KEYCODES_DPAD &&
            host.findFocus() == null
        ) {
            binding.root.post { scrollToSelectedItem() }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private inner class PanelAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            CellVh(CellHongguoPanelItemBinding.inflate(layoutInflater, parent, false))

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            (holder as CellVh).bindSingle(position)
        }

        /** 单列模式：一行一个选项 + 勾选图标。 */
        inner class CellVh(val rootBinding: CellHongguoPanelItemBinding) :
            RecyclerView.ViewHolder(rootBinding.root) {
            fun bindSingle(index: Int) {
                val selected = index == selectedIndex
                with(rootBinding) {
                    textLabel.text = items[index]
                    clickView.isSelected = selected
                    iconCheck.visibility = if (selected) View.VISIBLE else View.INVISIBLE
                    clickView.setOnClickListener { onSelected(index) }
                }
            }
        }
    }

    companion object {
        /** 触摸后首个方向键的焦点兜底覆盖范围（BACK/OK 不拦，走默认行为）。 */
        private val KEYCODES_DPAD = intArrayOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT
        )

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
