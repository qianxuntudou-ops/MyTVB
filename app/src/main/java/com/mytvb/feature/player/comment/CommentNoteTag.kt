package com.mytvb.feature.player.comment

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ImageSpan
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import androidx.appcompat.widget.AppCompatTextView
import com.mytvb.R

/**
 * 评论正文行首的行内小标签（官方样式，ImageSpan 内联）：
 * [TagStyle.NOTE] "笔记"：浅灰实底圆角 + 灰色笔记本图标 + 灰字；
 * [TagStyle.TOP] "置顶"：透明底 + B站粉描边圆角 + 粉字（官方置顶样式，经用户官方截图确认）；
 * [TagStyle.UP] "UP"：粉底白字圆角（楼中楼预览行用户名后，官方样式）。
 */
internal object CommentNoteTag {

    enum class TagStyle { NOTE, TOP, UP }

    /** 笔记标签（note_cvid>0）。 */
    fun prepend(textView: TextView) = prependTag(textView, TagStyle.NOTE)

    /** 置顶标签（top_replies）。 */
    fun prependTop(textView: TextView) = prependTag(textView, TagStyle.TOP)

    /**
     * UP 徽章 span（不自动拼接，由调用方插到用户名后的占位符上）。
     * 产物就是 tv_up_badge（楼中楼详情/根评论行那个 UP 徽章）本身的离屏渲染：
     * 用同款属性的真实 TextView measure+draw 出 Bitmap，字体/粗细/内边距与 TextView 渲染像素级一致。
     */
    fun upBadgeSpan(textView: TextView): ImageSpan {
        val drawable = BitmapDrawable(textView.resources, upBadgeBitmap(textView.context))
        drawable.setBounds(0, 0, drawable.bitmap.width, drawable.bitmap.height)
        return CenteredImageSpan(drawable)
    }

    private var cachedUpBadgeBitmap: Bitmap? = null

    private fun upBadgeBitmap(context: Context): Bitmap {
        cachedUpBadgeBitmap?.let { return it }
        val res = context.resources
        // 与 item_comment.xml 的 tv_up_badge 逐项对齐：textSize px16 + bold + 白字 +
        // padding 横 px8 纵 px2 + bg_comment_up_badge + includeFontPadding=false
        val tv = AppCompatTextView(context)
        tv.text = "UP"
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimensionPixelSize(R.dimen.px16).toFloat())
        tv.setTypeface(tv.typeface, Typeface.BOLD)
        tv.setTextColor(Color.WHITE)
        tv.includeFontPadding = false
        val padH = res.getDimensionPixelSize(R.dimen.px8)
        val padV = res.getDimensionPixelSize(R.dimen.px2)
        tv.setPadding(padH, padV, padH, padV)
        tv.setBackgroundResource(R.drawable.bg_comment_up_badge)
        val spec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        tv.measure(spec, spec)
        tv.layout(0, 0, tv.measuredWidth, tv.measuredHeight)
        val bmp = Bitmap.createBitmap(tv.measuredWidth, tv.measuredHeight, Bitmap.Config.ARGB_8888)
        tv.draw(Canvas(bmp))
        cachedUpBadgeBitmap = bmp
        return bmp
    }

    private fun prependTag(textView: TextView, style: TagStyle) {
        val drawable = NoteTagDrawable(textView.context, textView.resources, style)
        // ImageSpan 按 bounds 取尺寸绘制，必须显式设置（intrinsic 不会自动生效）
        drawable.setBounds(0, 0, drawable.intrinsicWidth, drawable.intrinsicHeight)
        // span 必须在 setText 之前挂上（对齐 CommentEmoteSpannable 的模式），
        // 之后挂会被 TextView 内部处理吞掉，占位符 \uFFFC 会渲染成 "OBJ" 破损字符
        val ssb = SpannableStringBuilder("\uFFFC ")
        ssb.append(textView.text)
        ssb.setSpan(CenteredImageSpan(drawable), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        // 默认 buffer：SPANNABLE 会令 TextView 的 maxLines/ellipsize 失效（长评截断无"展开"）
        textView.text = ssb
    }

    /** 标签与文字行垂直居中（ALIGN_BOTTOM 会让小标签沉到行底，观感偏坠）。 */
    internal class CenteredImageSpan(drawable: Drawable) : ImageSpan(drawable, ALIGN_BOTTOM) {
        override fun draw(
            canvas: Canvas,
            text: CharSequence?,
            start: Int,
            end: Int,
            x: Float,
            top: Int,
            y: Int,
            bottom: Int,
            paint: Paint,
        ) {
            val d = drawable
            val b = d.bounds
            val fm = paint.fontMetricsInt
            val lineCenterY = y + (fm.descent + fm.ascent) / 2
            val transY = lineCenterY - b.height() / 2
            canvas.save()
            canvas.translate(x, transY.toFloat())
            d.draw(canvas)
            canvas.restore()
        }
    }

    private class NoteTagDrawable(
        context: Context,
        res: Resources,
        private val style: TagStyle,
    ) : Drawable() {
        private val label =
            if (style == TagStyle.TOP) context.getString(R.string.player_comment_top_tag)
            else context.getString(R.string.player_comment_note_tag)
        private val gray = 0xFF9499A0.toInt()
        private val pink = 0xFFFB7299.toInt()
        private val textSizeDimen: Int = if (style == TagStyle.TOP) R.dimen.px14 else R.dimen.px16
        private val heightDimen: Int = if (style == TagStyle.TOP) R.dimen.px24 else R.dimen.px26
        private val padHDimen: Int = if (style == TagStyle.TOP) R.dimen.px6 else R.dimen.px8
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        // paint 配置放 init（无 apply receiver）：Paint 的合成属性 style 与构造参数 style 同名，
        // apply 块内裸引用 style 存在解析歧义，全部显式限定消灭之
        init {
            textPaint.textSize = res.getDimensionPixelSize(textSizeDimen).toFloat()
            textPaint.color = if (style == TagStyle.TOP) pink else gray

            iconPaint.color = gray
            iconPaint.style = Paint.Style.STROKE
            iconPaint.strokeWidth = res.getDimension(R.dimen.px2)

            if (style == TagStyle.TOP) {
                bgPaint.color = pink
                bgPaint.style = Paint.Style.STROKE
                bgPaint.strokeWidth = res.getDimension(R.dimen.px2)
            } else {
                bgPaint.color = 0x33FFFFFF
                bgPaint.style = Paint.Style.FILL
            }
        }
        private val heightPx = res.getDimensionPixelSize(heightDimen)
        private val padH = res.getDimensionPixelSize(padHDimen)
        private val iconSize = res.getDimensionPixelSize(R.dimen.px14)
        private val iconGap = res.getDimensionPixelSize(R.dimen.px4)
        private val cornerRadius = res.getDimension(R.dimen.px4)
        private val textWidth = textPaint.measureText(label)
        private val iconStrokeW = res.getDimensionPixelSize(R.dimen.px2)
        private val widthPx =
            (
                padH +
                    (if (style == TagStyle.TOP) 0 else iconSize + iconGap) +
                    textWidth + padH
                ).toInt()
        private val rect = RectF()

        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.isEmpty) return
            rect.set(0f, 0f, widthPx.toFloat(), heightPx.toFloat())
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, bgPaint)

            if (style == TagStyle.NOTE) {
                // 笔记本小图标：圆角矩形描边 + 两条横线（官方"卡片内含横线"样式）
                val iconTop = (heightPx - iconSize) / 2f
                val iconLeft = padH.toFloat()
                val iconRect =
                    RectF(
                        iconLeft + iconStrokeW,
                        iconTop + iconStrokeW,
                        iconLeft + iconSize - iconStrokeW,
                        iconTop + iconSize - iconStrokeW,
                    )
                canvas.drawRoundRect(iconRect, cornerRadius, cornerRadius, iconPaint)
                val lineLeft = iconRect.left + iconRect.width() * 0.25f
                val lineRight = iconRect.right - iconRect.width() * 0.25f
                val lineMidTop = iconRect.top + iconRect.height() * 0.38f
                val lineMidBottom = iconRect.top + iconRect.height() * 0.66f
                canvas.drawLine(lineLeft, lineMidTop, lineRight, lineMidTop, iconPaint)
                canvas.drawLine(lineLeft, lineMidBottom, lineRight, lineMidBottom, iconPaint)
            }

            val textX =
                padH + (if (style == TagStyle.TOP) 0f else iconSize + iconGap.toFloat())
            val baseline =
                heightPx / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(label, textX, baseline, textPaint)
        }

        override fun setAlpha(alpha: Int) {
            textPaint.alpha = alpha.coerceIn(0, 255)
            iconPaint.alpha = alpha.coerceIn(0, 255)
            val bgIntrinsic = if (style == TagStyle.TOP) 0xFF else 0x33
            bgPaint.alpha = (alpha * bgIntrinsic / 255).coerceIn(0, 255)
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            textPaint.colorFilter = colorFilter
            iconPaint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        override fun getIntrinsicWidth(): Int = widthPx

        override fun getIntrinsicHeight(): Int = heightPx
    }
}
