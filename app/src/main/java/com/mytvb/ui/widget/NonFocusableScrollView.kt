package com.mytvb.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.widget.ScrollView

class NonFocusableScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ScrollView(context, attrs, defStyleAttr) {

    /**
     * 内容最大高度（像素）。ScrollView 原生不处理 android:maxHeight，
     * 由这里在 onMeasure 中钳制，超出的内容转为滚动。
     */
    var maxHeight: Int = Int.MAX_VALUE

    /**
     * 常驻自绘滚动条开关：内容可滚时右侧显示一条 thumb，不随时间淡出。
     * 不用系统滚动条的常驻绘制——纯代码构造的 view 没有 XML scrollbars 初始化，
     * ScrollBarDrawable 保持 null，部分系统版本（API 34 实测）的 onDrawScrollBars
     * 引用它不判空，一绘制就 NPE；这里同时关掉系统条 flag 让该路径彻底不走。
     */
    var persistentScrollBar = false
        set(value) {
            field = value
            isVerticalScrollBarEnabled = !value
            invalidate()
        }

    /** 自绘 thumb 宽度（像素）。 */
    var scrollBarWidth: Int = resources.displayMetrics.densityDpi / 40

    /** 自绘 thumb 颜色。 */
    var scrollBarColor: Int = 0x59FFFFFF

    private val scrollBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val scrollBarRect = RectF()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (maxHeight in 1 until Int.MAX_VALUE) {
            val mode = MeasureSpec.getMode(heightMeasureSpec)
            val size = MeasureSpec.getSize(heightMeasureSpec)
            val newSpec = when (mode) {
                MeasureSpec.UNSPECIFIED ->
                    MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST)
                MeasureSpec.AT_MOST ->
                    MeasureSpec.makeMeasureSpec(minOf(maxHeight, size), MeasureSpec.AT_MOST)
                else -> heightMeasureSpec
            }
            super.onMeasure(widthMeasureSpec, newSpec)
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!persistentScrollBar || childCount == 0 || height == 0) return
        val child = getChildAt(0)
        val range = child.height + paddingTop + paddingBottom - height
        if (range <= 0) return
        val trackH = height - paddingTop - paddingBottom
        val thumbH = maxOf(scrollBarWidth * 3, trackH * trackH / (trackH + range))
        val thumbY = paddingTop + (trackH - thumbH) * (scrollY.toFloat() / range)
        scrollBarPaint.color = scrollBarColor
        scrollBarRect.set(
            (width - paddingRight - scrollBarWidth).toFloat(),
            thumbY,
            (width - paddingRight).toFloat(),
            thumbY + thumbH
        )
        canvas.drawRoundRect(scrollBarRect, scrollBarWidth / 2f, scrollBarWidth / 2f, scrollBarPaint)
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        if (persistentScrollBar) invalidate()
    }

    override fun onDrawForeground(canvas: Canvas) {
        // 系统实现第一站就是 onDrawScrollBars：部分系统版本（API 34 实测）对
        // 纯代码构造（无 XML scrollbars 初始化）的 view 引用未创建的
        // ScrollBarDrawable 不判空，常驻滚动条一绘制就 NPE 闪退。
        // 滚动条已由 persistentScrollBar 自绘接管，这里只补 foreground
        // （ViewOverlay.draw 是隐藏 API 且本类各使用处未用 overlay）；
        // fading edges 在 draw() 内部独立步骤绘制，不经过本方法，渐隐边缘不受影响。
        foreground?.draw(canvas)
    }

    override fun addFocusables(views: ArrayList<View>, direction: Int, focusableMode: Int) {
        // 自身不参与焦点搜索（避免 ScrollView 抢方向键焦点），
        // 但必须透传子项：清空实现会让滚动区内的按钮对 DPAD 焦点完全不可见。
        for (i in 0 until childCount) {
            getChildAt(i).addFocusables(views, direction, focusableMode)
        }
    }
}
