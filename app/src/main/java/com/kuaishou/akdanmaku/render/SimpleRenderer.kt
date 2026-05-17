/*
 * The MIT License (MIT)
 *
 * Copyright 2021 Kwai, Inc. All rights reserved.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.kuaishou.akdanmaku.render

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import androidx.core.math.MathUtils.clamp
import com.kuaishou.akdanmaku.DanmakuConfig
import com.kuaishou.akdanmaku.data.DanmakuItem
import com.kuaishou.akdanmaku.data.DanmakuItemData
import com.kuaishou.akdanmaku.ui.DanmakuDisplayer
import com.kuaishou.akdanmaku.utils.Size
import com.tutu.myblbl.feature.player.view.VipDanmakuTextureCache
import java.util.HashMap
import kotlin.math.roundToInt

/**
 * 一个默认的，实现了简单只绘制文字和描边的弹幕渲染器
 *
 * @author Xana
 */
open class SimpleRenderer : DanmakuRenderer {

  private val textPaint = TextPaint().apply {
    color = Color.WHITE
    style = Paint.Style.FILL
    isAntiAlias = true
  }
  private val strokePaint = TextPaint().apply {
    textSize = textPaint.textSize
    color = Color.BLACK
    strokeWidth = 2f
    style = Paint.Style.STROKE
    strokeJoin = Paint.Join.ROUND
    strokeCap = Paint.Cap.ROUND
    isAntiAlias = true
  }
  private val debugPaint by lazy {
    Paint().apply {
      color = Color.RED
      style = Paint.Style.STROKE
      isAntiAlias = true
      strokeWidth = 6f
    }
  }
  private val borderPaint = Paint().apply {
    color = Color.WHITE
    style = Paint.Style.STROKE
    isAntiAlias = true
    strokeWidth = 6f
  }
  private val shaderMatrix = Matrix()

  override fun updatePaint(
    item: DanmakuItem,
    displayer: DanmakuDisplayer,
    config: DanmakuConfig
  ) {
    val danmakuItemData = item.data
    // update textPaint
    val textSize = clamp(danmakuItemData.textSize.toFloat(), 12f, 25f) * (displayer.density - 0.6f)
    textPaint.shader = null
    textPaint.clearShadowLayer()
    textPaint.color = danmakuItemData.textColor or Color.argb(255, 0, 0, 0)
    textPaint.textSize = textSize * config.textSizeScale
    textPaint.typeface = if (config.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    textPaint.style = Paint.Style.FILL
    // update strokePaint
    strokePaint.shader = null
    strokePaint.clearShadowLayer()
    strokePaint.textSize = textPaint.textSize
    strokePaint.typeface = textPaint.typeface
    strokePaint.color = resolveStandardStrokeColor(textPaint.color)
    strokePaint.style = Paint.Style.STROKE
    strokePaint.strokeWidth = (textPaint.textSize * 0.06f).coerceIn(1.2f, 2.5f)
    strokePaint.strokeJoin = Paint.Join.ROUND
    strokePaint.strokeCap = Paint.Cap.ROUND
  }

  override fun measure(
    item: DanmakuItem,
    displayer: DanmakuDisplayer,
    config: DanmakuConfig
  ): Size {
    updatePaint(item, displayer, config)
    val danmakuItemData = item.data
    val textWidth = textPaint.measureText(danmakuItemData.content)
    val textHeight = getCacheHeight(textPaint)
    return Size(textWidth.roundToInt() + CANVAS_PADDING, textHeight.roundToInt() + CANVAS_PADDING)
  }

  override fun draw(
    item: DanmakuItem,
    canvas: Canvas,
    displayer: DanmakuDisplayer,
    config: DanmakuConfig
  ) {
    updatePaint(item, displayer, config)
    val danmakuItemData = item.data
    val x = CANVAS_PADDING * 0.5f
    val y = CANVAS_PADDING * 0.5f - textPaint.ascent()
    if ((danmakuItemData.renderFlags and DanmakuItemData.RENDER_FLAG_VIP_GRADIENT) != 0) {
      drawVipGradientText(canvas, danmakuItemData, x, y)
    } else {
      canvas.drawText(danmakuItemData.content, x, y, strokePaint)
      canvas.drawText(danmakuItemData.content, x, y, textPaint)
    }
    textPaint.shader = null
    textPaint.clearShadowLayer()
    strokePaint.clearShadowLayer()
    if (danmakuItemData.danmakuStyle == DanmakuItemData.DANMAKU_STYLE_SELF_SEND) {
      canvas.drawRect(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat(), borderPaint)
    }
  }

  private fun drawVipGradientText(
    canvas: Canvas,
    danmakuItemData: DanmakuItemData,
    startX: Float,
    baselineY: Float
  ) {
    val text = danmakuItemData.content
    if (text.isBlank()) {
      return
    }
    val textWidth = textPaint.measureText(text).coerceAtLeast(1f)
    val top = baselineY + textPaint.ascent()
    val bottom = baselineY + textPaint.descent()
    val textHeight = (bottom - top).coerceAtLeast(1f)
    val fillBitmap = VipDanmakuTextureCache.getBitmap(danmakuItemData.vipGradientStyle.fillTextureUrl)
    val strokeBitmap = VipDanmakuTextureCache.getBitmap(danmakuItemData.vipGradientStyle.strokeTextureUrl)
    if (fillBitmap != null || strokeBitmap != null) {
      drawVipTextureText(
        canvas = canvas,
        text = text,
        startX = startX,
        baselineY = baselineY,
        top = top,
        textWidth = textWidth,
        textHeight = textHeight,
        fillBitmap = fillBitmap,
        strokeBitmap = strokeBitmap,
        fallbackColor = danmakuItemData.textColor
      )
      return
    }
    val palette = resolveVipPalette(danmakuItemData.textColor)
    val leadingColor = lightenColor(palette.first(), 0.35f)
    val trailingColor = darkenColor(palette.last(), 0.05f)
    // 旧实现用 setShadowLayer 制造光晕，但 setShadowLayer 内部会强制走 BlurMaskFilter 软件路径，
    // 在 TV 上 buildCache 时单条弹幕可能要 1~3ms，弹幕高峰期累加直接打爆 act 线程。
    // 改成"先画一层更宽的暗色外描边 + 一层基础描边"做廉价描边光晕近似。
    strokePaint.style = Paint.Style.STROKE
    strokePaint.strokeJoin = Paint.Join.ROUND
    strokePaint.strokeCap = Paint.Cap.ROUND
    strokePaint.shader = null
    strokePaint.clearShadowLayer()

    val outerStrokeWidth = (textPaint.textSize * 0.22f).coerceAtLeast(3f)
    val innerStrokeWidth = (textPaint.textSize * 0.14f).coerceAtLeast(2f)

    val shaderKey = "${leadingColor}_${trailingColor}_${textWidth}_${textHeight}"
    textPaint.shader = vipShaderCache.getOrPut(shaderKey) {
      LinearGradient(
        startX,
        top,
        startX + textWidth,
        bottom,
        intArrayOf(
          Color.WHITE,
          lightenColor(leadingColor, 0.55f),
          leadingColor,
          trailingColor
        ),
        VIP_TEXT_GRADIENT_POSITIONS,
        Shader.TileMode.CLAMP
      )
    }
    textPaint.clearShadowLayer()

    // 外发光层：宽且半透明，模拟 setShadowLayer 的柔和光晕。
    strokePaint.color = withAlpha(darkenColor(trailingColor, 0.45f), 96)
    strokePaint.strokeWidth = outerStrokeWidth
    canvas.drawText(text, startX, baselineY, strokePaint)
    // 主描边层：实色不透明，决定边缘清晰度。
    strokePaint.color = withAlpha(darkenColor(leadingColor, 0.55f), 220)
    strokePaint.strokeWidth = innerStrokeWidth
    canvas.drawText(text, startX, baselineY, strokePaint)

    canvas.drawText(text, startX, baselineY, textPaint)
    textPaint.shader = null
    strokePaint.clearShadowLayer()
  }

  private fun drawVipTextureText(
    canvas: Canvas,
    text: String,
    startX: Float,
    baselineY: Float,
    top: Float,
    textWidth: Float,
    textHeight: Float,
    fillBitmap: Bitmap?,
    strokeBitmap: Bitmap?,
    fallbackColor: Int
  ) {
    strokePaint.style = Paint.Style.STROKE
    strokePaint.strokeWidth = (textPaint.textSize * 0.12f).coerceAtLeast(1.8f)
    strokePaint.strokeJoin = Paint.Join.ROUND
    strokePaint.strokeCap = Paint.Cap.ROUND
    strokePaint.color = Color.BLACK
    strokePaint.shader = strokeBitmap?.createTextShader(startX, top, textWidth, textHeight)

    textPaint.style = Paint.Style.FILL
    textPaint.color = fallbackColor or Color.argb(255, 0, 0, 0)
    textPaint.shader = fillBitmap?.createTextShader(startX, top, textWidth, textHeight)

    canvas.drawText(text, startX, baselineY, strokePaint)
    canvas.drawText(text, startX, baselineY, textPaint)

    strokePaint.shader = null
    textPaint.shader = null
  }

  private fun Bitmap.createTextShader(
    left: Float,
    top: Float,
    width: Float,
    height: Float
  ): BitmapShader {
    return BitmapShader(this, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).also { shader ->
      shaderMatrix.reset()
      shaderMatrix.setScale(
        width / this.width.coerceAtLeast(1).toFloat(),
        height / this.height.coerceAtLeast(1).toFloat()
      )
      shaderMatrix.postTranslate(left, top)
      shader.setLocalMatrix(shaderMatrix)
    }
  }

  private fun resolveVipPalette(textColor: Int): IntArray {
    val resolved = textColor or Color.argb(255, 0, 0, 0)
    if ((resolved and 0x00FFFFFF) == 0x00FFFFFF) {
      return DEFAULT_VIP_GRADIENT_COLORS
    }
    vipPaletteCache[resolved]?.let { return it }
    val result = IntArray(DEFAULT_VIP_GRADIENT_COLORS.size) { i ->
      blendColor(DEFAULT_VIP_GRADIENT_COLORS[i], resolved, 0.26f)
    }
    vipPaletteCache[resolved] = result
    return result
  }

  private fun resolveStandardStrokeColor(textColor: Int): Int {
    val luminance = (0.2126f * Color.red(textColor) +
      0.7152f * Color.green(textColor) +
      0.0722f * Color.blue(textColor)) / 255f
    return if (luminance > 0.5f) {
      withAlpha(Color.BLACK, 230)
    } else {
      withAlpha(Color.WHITE, 210)
    }
  }

  private fun lightenColor(color: Int, amount: Float): Int {
    val safeAmount = amount.coerceIn(0f, 1f)
    val alpha = Color.alpha(color)
    val red = Color.red(color)
    val green = Color.green(color)
    val blue = Color.blue(color)
    return Color.argb(
      alpha,
      (red + (255 - red) * safeAmount).roundToInt().coerceIn(0, 255),
      (green + (255 - green) * safeAmount).roundToInt().coerceIn(0, 255),
      (blue + (255 - blue) * safeAmount).roundToInt().coerceIn(0, 255)
    )
  }

  private fun darkenColor(color: Int, amount: Float): Int {
    val safeAmount = (1f - amount.coerceIn(0f, 1f)).coerceIn(0f, 1f)
    val alpha = Color.alpha(color)
    return Color.argb(
      alpha,
      (Color.red(color) * safeAmount).roundToInt().coerceIn(0, 255),
      (Color.green(color) * safeAmount).roundToInt().coerceIn(0, 255),
      (Color.blue(color) * safeAmount).roundToInt().coerceIn(0, 255)
    )
  }

  private fun blendColor(start: Int, end: Int, progress: Float): Int {
    val p = progress.coerceIn(0f, 1f)
    return Color.argb(
      (Color.alpha(start) + (Color.alpha(end) - Color.alpha(start)) * p).roundToInt().coerceIn(0, 255),
      (Color.red(start) + (Color.red(end) - Color.red(start)) * p).roundToInt().coerceIn(0, 255),
      (Color.green(start) + (Color.green(end) - Color.green(start)) * p).roundToInt().coerceIn(0, 255),
      (Color.blue(start) + (Color.blue(end) - Color.blue(start)) * p).roundToInt().coerceIn(0, 255)
    )
  }

  private fun withAlpha(color: Int, alpha: Int): Int {
    return Color.argb(
      alpha.coerceIn(0, 255),
      Color.red(color),
      Color.green(color),
      Color.blue(color)
    )
  }

  companion object {
    private val DEFAULT_DARK_COLOR: Int = Color.argb(255, 0x22, 0x22, 0x22)
    private val DEFAULT_VIP_GRADIENT_COLORS = intArrayOf(
      Color.parseColor("#FF6AA8"),
      Color.parseColor("#FFD86E"),
      Color.parseColor("#7EE1C7"),
      Color.parseColor("#86B9FF"),
      Color.parseColor("#C18EFF")
    )
    private val VIP_TEXT_GRADIENT_POSITIONS = floatArrayOf(0f, 0.38f, 0.7f, 1f)

    private const val CANVAS_PADDING: Int = 6

    private val sTextHeightCache: MutableMap<Float, Float> = HashMap()
    private val vipPaletteCache = HashMap<Int, IntArray>()
    private val vipShaderCache = HashMap<String, LinearGradient>()

    private fun getCacheHeight(paint: Paint): Float {
      val textSize = paint.textSize
      return sTextHeightCache[textSize] ?: let {
        val fontMetrics = paint.fontMetrics
        val textHeight = fontMetrics.descent - fontMetrics.ascent + fontMetrics.leading
        sTextHeightCache[textSize] = textHeight
        textHeight
      }
    }
  }
}