package com.mytvb.feature.player.comment

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ImageSpan
import android.widget.TextView
import com.mytvb.R
import com.mytvb.core.ui.emote.EmoteBitmapLoader

/**
 * 评论正文的 B 站大表情渲染：把 [xx] 文本替换为行内 ImageSpan。
 * 位图经共享 [EmoteBitmapLoader] 异步加载，回调里校验 TextView 的
 * tag 防止回收复用后错位更新。
 *
 * 对齐 blbl.cat3399.core.emote.EmoteSpannable。
 */
internal object CommentEmoteSpannable {
    private const val MAX_EMOTES_PER_TEXT = 64

    fun setText(
        textView: TextView,
        rawText: String,
        emotes: Map<String, String>,
        blankFallback: String = "-",
    ) {
        val text = rawText.ifBlank { blankFallback }
        textView.setTag(R.id.tag_emote_text_key, text)

        if (emotes.isEmpty() || !text.contains('[')) {
            textView.text = text
            return
        }

        val ssb = SpannableStringBuilder(text)
        applyEmotes(textView, ssb, start = 0, end = ssb.length, emotes = emotes)
        textView.setText(ssb, TextView.BufferType.SPANNABLE)
    }

    fun applyEmotes(
        textView: TextView,
        ssb: SpannableStringBuilder,
        start: Int,
        end: Int,
        emotes: Map<String, String>,
    ) {
        if (emotes.isEmpty()) return
        if (start !in 0..ssb.length || end !in 0..ssb.length || end <= start) return

        val expectedKey = textView.getTag(R.id.tag_emote_text_key)

        val sizePx = emoteSizePx(textView)
        if (sizePx <= 0) return

        val sub = ssb.subSequence(start, end).toString()
        var i = 0
        var replaced = 0
        while (i < sub.length && replaced < MAX_EMOTES_PER_TEXT) {
            val open = sub.indexOf('[', i)
            if (open < 0) break
            val close = sub.indexOf(']', startIndex = open + 1)
            if (close < 0) break

            val token = sub.substring(open, close + 1)
            val url = emotes[token]?.trim().orEmpty()
            if (!url.startsWith("http")) {
                i = close + 1
                continue
            }

            val spanStart = start + open
            val spanEnd = start + close + 1
            val drawable = EmoteDrawable(sizePx = sizePx)
            drawable.setBounds(0, 0, sizePx, sizePx)
            val span = CenterAlignedImageSpan(drawable)
            ssb.setSpan(span, spanStart, spanEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            replaced++

            EmoteBitmapLoader.load(url) { bmp ->
                if (bmp == null) return@load
                if (textView.getTag(R.id.tag_emote_text_key) != expectedKey) return@load
                drawable.setBitmap(bmp)
                textView.invalidate()
            }

            i = close + 1
        }
    }

    private fun emoteSizePx(textView: TextView): Int {
        val fm = textView.paint.fontMetricsInt
        return (fm.descent - fm.ascent).coerceAtLeast(1)
    }

    private class EmoteDrawable(
        private val sizePx: Int,
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }

        @Volatile
        private var bitmap: Bitmap? = null

        fun setBitmap(bmp: Bitmap) {
            bitmap = bmp
            invalidateSelf()
        }

        override fun draw(canvas: Canvas) {
            val bmp = bitmap ?: return
            if (bmp.isRecycled) return
            val b = bounds
            if (b.isEmpty) return
            canvas.drawBitmap(bmp, null, b, paint)
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha.coerceIn(0, 255)
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        override fun getIntrinsicWidth(): Int = sizePx

        override fun getIntrinsicHeight(): Int = sizePx
    }

    private class CenterAlignedImageSpan(drawable: Drawable) : ImageSpan(drawable, ALIGN_BOTTOM) {
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
            val b: Rect = d.bounds
            val fm = paint.fontMetricsInt
            val lineCenterY = y + (fm.descent + fm.ascent) / 2
            val transY = lineCenterY - b.height() / 2

            canvas.save()
            canvas.translate(x, transY.toFloat())
            d.draw(canvas)
            canvas.restore()
        }
    }
}
