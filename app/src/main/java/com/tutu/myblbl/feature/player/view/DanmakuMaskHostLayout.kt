package com.tutu.myblbl.feature.player.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.Rect
import android.os.SystemClock
import android.util.AttributeSet
import android.widget.FrameLayout
import com.tutu.myblbl.core.common.log.AppLog
import com.tutu.myblbl.model.dm.DmMaskTimeline
import com.tutu.myblbl.model.dm.MaskFrame

/**
 * 弹幕防挡蒙版宿主容器。
 *
 * 按 Bilibili 参考链路重构。参考 Chronos shader 是
 * `CRONDefaultShading() * mask_sample`：只让弹幕显示在当前 mask 覆盖区。
 *
 * MyBLBL 用 Android 2D 的等价近似：
 *  - 只负责渲染裁剪，不做业务逻辑
 *  - 多个 Path UNION 合并为单个 mergedPath
 *  - clipPath 到当前 mask path 覆盖区，再绘制弹幕子 View
 *  - SVG 默认 nonzero/WINDING fill rule；解析后的 webmask path 是弹幕可显示区
 *  - 防闪烁：queryAt 返回 null 时，回退到上一个有效 mergedPath
 *  - 空 paths 是明确的"无遮挡帧"，不能复用旧遮罩
 *  - 明确的缓存清除入口 `clearCachedMask()`，由 DmMaskController 在 seek/release 时调用
 */
class DanmakuMaskHostLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    var timeline: DmMaskTimeline? = null
    var ptsProvider: (() -> Long)? = null
    var videoBoundsProvider: (() -> Rect)? = null

    /**
     * 由 controller 注入：当前是否处于 seek 冻结状态。
     * SEEKING 时跳过 timeline 查询，直接使用缓存的 mergedPath。
     */
    var isSeeking: (() -> Boolean)? = null

    /**
     * 由 controller 注入：mask 数据未 ready / seek 等待首帧等场景返回 false，
     * 此时直接走 super.dispatchDraw 不裁剪。
     */
    var shouldRenderMask: (() -> Boolean)? = null

    /** 把 (queryPts, framePts) 反馈给 controller 做诊断。 */
    var frameQueryReporter: ((queryPtsMs: Long, framePtsMs: Long) -> Unit)? = null

    // ---- 渲染缓存 ----

    private val transformMatrix = Matrix()
    private val transformPath = Path()
    private val mergedPath = Path().apply { fillType = Path.FillType.WINDING }

    /** 同帧去重 + 防闪烁缓存：记录上一个有效帧及其 bounds。 */
    private var cachedFrame: MaskFrame? = null
    private var cachedBoundsLeft = 0
    private var cachedBoundsTop = 0
    private var cachedBoundsRight = 0
    private var cachedBoundsBottom = 0
    private var lastMaskStateLogMs = 0L
    private var lastMaskStateKey = ""

    /**
     * 由 DmMaskController 在 seek / release 时调用：清除缓存的遮罩，
     * 避免在新位置使用旧位置的遮罩。
     */
    fun clearCachedMask() {
        cachedFrame = null
        mergedPath.reset()
        mergedPath.fillType = Path.FillType.WINDING
        postInvalidateOnAnimation()
    }

    /** 保留调试入口：必要时可强制切软件层对比硬件合成差异。 */
    fun setHighQualityClipping(enabled: Boolean) {
        val target = if (enabled) LAYER_TYPE_SOFTWARE else LAYER_TYPE_NONE
        if (layerType != target) {
            setLayerType(target, null)
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        // 1. controller 说不要渲染 → 不裁剪（仅 IDLE 状态）
        if (shouldRenderMask?.invoke() == false) {
            maybeLogMaskState("disabled", null, null, null, false)
            super.dispatchDraw(canvas)
            return
        }

        // 2. SEEKING 状态：冻结旧遮罩，不查询 timeline
        if (isSeeking?.invoke() == true) {
            val bounds = videoBoundsProvider?.invoke()
            if (bounds == null || bounds.isEmpty || cachedFrame == null || cachedFrame!!.paths.isEmpty()) {
                maybeLogMaskState("seeking_no_cached_frame", null, cachedFrame, bounds, false)
                super.dispatchDraw(canvas)
                return
            }
            // 直接用缓存的 mergedPath，不更新。
            maybeLogMaskState("seeking_cached_frame", null, cachedFrame, bounds, true)
            drawDanmakuWithMask(canvas)
            return
        }

        val tl = timeline
        val pts = ptsProvider?.invoke()
        val bounds = videoBoundsProvider?.invoke()

        // 3. 查询当前 PTS 的 mask 帧
        val frame = if (tl != null && pts != null) tl.queryAt(pts) else null

        // 4. 上报诊断数据
        if (frame != null && pts != null) {
            frameQueryReporter?.invoke(pts, frame.presentationTimeMs)
        }

        // 5. bounds 无效 → 不裁剪
        if (bounds == null || bounds.isEmpty) {
            maybeLogMaskState("invalid_bounds", pts, frame, bounds, false)
            super.dispatchDraw(canvas)
            return
        }

        // 6. 空 paths 是明确的"当前无人物/无遮挡"，不能回退旧遮罩。
        if (frame != null && frame.paths.isEmpty()) {
            cachedFrame = null
            mergedPath.reset()
            mergedPath.fillType = Path.FillType.WINDING
            maybeLogMaskState("empty_paths", pts, frame, bounds, false)
            super.dispatchDraw(canvas)
            return
        }

        // 7. 确定本次渲染用的帧：只有 queryAt 返回 null 时才回退缓存（段未加载/短暂缺帧）。
        val currentFrame = frame
        val effectiveFrame = currentFrame ?: cachedFrame

        if (effectiveFrame == null || effectiveFrame.paths.isEmpty()) {
            maybeLogMaskState("no_frame", pts, effectiveFrame, bounds, false)
            super.dispatchDraw(canvas)
            return
        }

        // 8. 同帧 + 同 bounds → 复用缓存的 mergedPath（CPU 节省 30~50%）
        val sameAsCache = currentFrame != null &&
            currentFrame === cachedFrame &&
            bounds.left == cachedBoundsLeft && bounds.top == cachedBoundsTop &&
            bounds.right == cachedBoundsRight && bounds.bottom == cachedBoundsBottom

        if (!sameAsCache) {
            // 只在帧或 bounds 变化时才更新缓存和重建 path
            if (currentFrame != null) {
                cachedFrame = currentFrame
            }
            cachedBoundsLeft = bounds.left
            cachedBoundsTop = bounds.top
            cachedBoundsRight = bounds.right
            cachedBoundsBottom = bounds.bottom

            buildMergedPath(effectiveFrame, bounds)
        }

        // 9. 参考 shader 近似：直接把弹幕裁到 webmask 可显示区。
        maybeLogMaskState("masked", pts, effectiveFrame, bounds, true)
        drawDanmakuWithMask(canvas)
    }

    private fun drawDanmakuWithMask(canvas: Canvas) {
        if (width <= 0 || height <= 0) {
            super.dispatchDraw(canvas)
            return
        }
        val saveCount = canvas.save()
        canvas.clipPath(mergedPath)
        super.dispatchDraw(canvas)
        canvas.restoreToCount(saveCount)
    }

    /**
     * 把 effectiveFrame 的所有 path 合并到 mergedPath。
     * 每个 path 从 SVG 坐标系变换到视频 bounds 坐标系。
     *
     * 坐标映射逻辑：
     * - SVG path 坐标经过 WebmaskParser 的 * 0.1 转换后，处于 [0, vbW*0.1] 空间
     * - 当 viewBox 存在时，缩放分母用 vbW*0.1（比 svgWidth 更准确）
     * - 当 viewBox 不存在时，fallback 到 svgWidth（保持原有行为）
     * - viewBox 偏移（x/y）需要从 path 坐标中减去，再映射到 bounds
     */
    private fun buildMergedPath(frame: MaskFrame, bounds: Rect) {
        // 优先用 viewBox 尺寸（已 * 0.1），fallback 到 svgWidth
        val coordW = if (frame.viewBoxWidth > 0f) frame.viewBoxWidth
            else frame.svgWidth.toFloat().coerceAtLeast(1f)
        val coordH = if (frame.viewBoxHeight > 0f) frame.viewBoxHeight
            else frame.svgHeight.toFloat().coerceAtLeast(1f)
        val vbX = frame.viewBoxX
        val vbY = frame.viewBoxY

        val sx = bounds.width().toFloat() / coordW
        val sy = bounds.height().toFloat() / coordH
        val dx = bounds.left.toFloat() - vbX * sx
        val dy = bounds.top.toFloat() - vbY * sy

        transformMatrix.setScale(sx, sy)
        transformMatrix.postTranslate(dx, dy)

        mergedPath.reset()
        mergedPath.fillType = Path.FillType.WINDING
        for (path in frame.paths) {
            transformPath.set(path)
            transformPath.transform(transformMatrix)
            mergedPath.addPath(transformPath)
        }
    }

    private fun maybeLogMaskState(
        reason: String,
        ptsMs: Long?,
        frame: MaskFrame?,
        bounds: Rect?,
        masked: Boolean
    ) {
        val now = SystemClock.elapsedRealtime()
        val key = "$reason/${frame?.presentationTimeMs}/${frame?.paths?.size}/$bounds/$masked"
        if (key == lastMaskStateKey && now - lastMaskStateLogMs < 2000L) return
        if (now - lastMaskStateLogMs < 800L && reason == "masked") return
        lastMaskStateKey = key
        lastMaskStateLogMs = now
        AppLog.d(
            "DmMaskHost",
            "mask state=$reason masked=$masked pts=$ptsMs framePts=${frame?.presentationTimeMs} " +
                "paths=${frame?.paths?.size} bounds=$bounds cachedPts=${cachedFrame?.presentationTimeMs}"
        )
    }
}
