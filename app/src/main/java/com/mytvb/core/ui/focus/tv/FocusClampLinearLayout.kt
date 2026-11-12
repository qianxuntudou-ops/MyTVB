package com.mytvb.core.ui.focus.tv

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.widget.LinearLayout

/**
 * 覆盖层根容器专用的焦点钳制：框架几何搜索（FocusFinder）不看遮挡与层级，
 * 列表边缘按键在层内无候选时会 fallback 命中覆盖层外的控件（侧栏功能按钮、
 * 被盖住的底层页面），表现为"焦点消失/乱跳"。这里把越出本容器子树的候选
 * 改为 null（ViewRootImpl 收到 null 不动焦点 → 边缘停住）。
 *
 * LEFT 默认放行：侧栏在覆盖层左侧且真实可见，横向离开覆盖层是合法路径
 * （回程由侧栏 RIGHT 的 focusEntryFromMainTab 归还焦点）。
 */
class FocusClampLinearLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    override fun focusSearch(focused: View?, direction: Int): View? {
        val next = super.focusSearch(focused, direction)
        if (direction == View.FOCUS_LEFT || next == null || next === focused) {
            return next
        }
        var p: ViewParent? = next.parent
        while (p != null) {
            if (p === this) return next
            p = p.parent
        }
        // 候选在容器外：钳掉，焦点停在当前 view
        return null
    }
}
