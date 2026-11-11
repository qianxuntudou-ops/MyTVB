package com.mytvb.feature.hongguo

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatTextView
import com.google.android.material.color.MaterialColors
import com.mytvb.R

/**
 * 红果统一 TV 分段控件（2026-11-11 重设计定案：榜单四板/登录三页签/分类子 tab 一套形态）。
 *
 * 形态：高 px56、圆角 px10、字 px26；选中=主题强调色实底+深字，聚焦=白描边+面提亮
 * （叠加态=实底上叠白描边，全部走 bg_hongguo_segment_tab selector，不硬编码色）。
 * 焦点：子项 focusable，遥控左右在子项间自然移动；点击/OK 切换并回调。
 */
class HongguoSegmentedTabs @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private var onSelected: ((Int) -> Unit)? = null
    private val tabs = mutableListOf<AppCompatTextView>()
    private var selectedIndex = 0
    private var suppressCallback = false

    init {
        orientation = HORIZONTAL
    }

    /** 构建页签（重复调用会先清空）；回调仅在用户切换到不同项时触发。 */
    fun setup(titles: List<String>, selectedIndex: Int = 0, onSelected: ((Int) -> Unit)? = null) {
        removeAllViews()
        tabs.clear()
        this.onSelected = onSelected
        this.selectedIndex = selectedIndex.coerceIn(0, (titles.size - 1).coerceAtLeast(0))
        titles.forEachIndexed { index, title ->
            val pad = resources.getDimensionPixelSize(R.dimen.px26)
            val tab = AppCompatTextView(context).apply {
                // 动态 id：宿主可用 nextFocusUp/DownId 与面板控件互相接线
                id = View.generateViewId()
                text = title
                // setTextSize(float) 是 sp 单位，px 池口径必须走 PX 显式单位
                setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimensionPixelSize(R.dimen.px26).toFloat())
                setTextColor(Color.WHITE)
                background = context.getDrawable(R.drawable.bg_hongguo_segment_tab)
                gravity = Gravity.CENTER
                isFocusable = true
                isClickable = true
                setPadding(pad, 0, pad, 0)
                layoutParams = LayoutParams(0, resources.getDimensionPixelSize(R.dimen.px56), 1f).apply {
                    if (index > 0) marginStart = resources.getDimensionPixelSize(R.dimen.px12)
                }
                setOnClickListener { select(index, fireCallback = true) }
            }
            addView(tab)
            tabs.add(tab)
        }
        select(this.selectedIndex, fireCallback = false)
    }

    /** 切换选中项；[fireCallback]=true 时按用户切换（值变化才回调）。 */
    fun select(index: Int, fireCallback: Boolean = false) {
        val changed = index != selectedIndex
        selectedIndex = index.coerceIn(0, tabs.size - 1)
        val onAccent = context.getColor(R.color.hongguo_on_accent)
        val grey = context.getColor(R.color.grey)
        tabs.forEachIndexed { i, tab ->
            val selected = i == selectedIndex
            tab.isSelected = selected
            tab.setTextColor(if (selected) onAccent else grey)
            tab.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
        }
        if (fireCallback && changed && !suppressCallback) onSelected?.invoke(selectedIndex)
    }

    fun selectedIndex(): Int = selectedIndex

    fun tabAt(index: Int): View? = tabs.getOrNull(index)

    /** 页面程序化换板（如登录弹窗切通道）时避免误触发用户回调。 */
    fun withSuppressCallback(block: () -> Unit) {
        suppressCallback = true
        try {
            block()
        } finally {
            suppressCallback = false
        }
    }
}
