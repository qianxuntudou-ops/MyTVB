package com.tutu.myblbl.model.dm

import android.graphics.Matrix
import android.graphics.Path
import android.graphics.RectF
import android.util.Base64
import com.tutu.myblbl.core.common.log.AppLog
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream

/**
 * webmask 文件解析器。
 *
 * 按 Bilibili 参考链路重构：
 *  - 文件结构：MASK 魔数 + 段索引表 + gzip 段数据（SVG path）
 *  - SVG path 坐标使用标准 SVG 坐标系（Y 轴向下），与 Android 屏幕坐标一致
 *  - 解析后的 path 代表弹幕可显示区，DanmakuMaskHostLayout 用 clipPath 保留该区域
 *
 * 关键修正：
 *  - 正确应用 SVG <g transform="translate(0,H) scale(0.1,-0.1)">
 *  - 使用 SVG 默认 nonzero/WINDING fill rule
 *  - SVG 文本 BOM 头剥离
 *  - 0 byte body 显式当失败
 */
object WebmaskParser {

    private const val TAG = "WebmaskParser"

    /** webmask 文件头长度（"MASK"4 + version4 + reserved4 + segmentCount4 = 16B）。 */
    const val HEADER_SIZE = 16

    /** 段索引表每条目大小（timeMs 8 + byteOffset 8 = 16B）。 */
    const val META_ENTRY_SIZE = 16

    /**
     * gzip 段解压后的帧头大小。
     *
     * 真实 webmask 段不是纯 base64 字符串拼接，而是：
     *   uint32 payloadLength + uint64 presentationTimeMs + payload
     * payload 才是 `data:image/svg+xml;base64,...`。
     */
    private const val FRAME_HEADER_SIZE = 12

    // ---- 文件级解析 ----

    /**
     * 解析 webmask 文件头（前 16 字节），返回段总数。
     * @return > 0 段总数；<= 0 无效
     */
    fun parseSegmentCount(headerBytes: ByteArray): Int {
        if (headerBytes.size < HEADER_SIZE) return -1
        val buf = ByteBuffer.wrap(headerBytes).order(ByteOrder.BIG_ENDIAN)
        val tag = ByteArray(4)
        buf.get(tag)
        if (!tag.contentEquals("MASK".toByteArray())) {
            AppLog.e(TAG, "Invalid webmask header")
            return -1
        }
        buf.int  // version
        buf.int  // reserved
        val count = buf.int
        return if (count in 1..10_000) count else {
            AppLog.e(TAG, "Invalid segment count: $count")
            -1
        }
    }

    /**
     * 解析段索引表（segmentCount × 16 字节），构建延迟加载 [DmMaskData]。
     */
    fun parseSegmentMeta(
        metaBytes: ByteArray,
        segmentCount: Int,
        fileTotalSize: Long,
        fps: Int,
    ): DmMaskData? {
        if (metaBytes.size < segmentCount * META_ENTRY_SIZE) {
            AppLog.e(TAG, "Meta bytes too small: ${metaBytes.size} need ${segmentCount * META_ENTRY_SIZE}")
            return null
        }
        val buf = ByteBuffer.wrap(metaBytes).order(ByteOrder.BIG_ENDIAN)
        val times = LongArray(segmentCount)
        val offsets = LongArray(segmentCount)
        for (i in 0 until segmentCount) {
            times[i] = buf.long
            offsets[i] = buf.long
        }
        val segments = (0 until segmentCount).map { i ->
            val byteEnd = if (i + 1 < segmentCount) offsets[i + 1] else fileTotalSize
            LazyMaskSegment(timeMs = times[i], byteOffset = offsets[i], byteEnd = byteEnd)
        }
        if (segments.isEmpty()) return null
        return DmMaskData(fps = fps, rawSegments = segments, totalFileSize = fileTotalSize)
    }

    /**
     * 兼容入口：从完整 webmask 文件字节构建（全量下载场景 / 测试）。
     */
    fun parse(data: ByteArray, fps: Int = 0): DmMaskData? {
        val segCount = parseSegmentCount(data)
        if (segCount <= 0) return null
        val metaEnd = HEADER_SIZE + segCount * META_ENTRY_SIZE
        if (data.size < metaEnd) return null

        val metaBytes = data.copyOfRange(HEADER_SIZE, metaEnd)
        val dmData = parseSegmentMeta(metaBytes, segCount, data.size.toLong(), fps) ?: return null
        for (seg in dmData.rawSegments) {
            val s = seg.byteOffset.toInt()
            val e = seg.byteEnd.toInt().coerceAtMost(data.size)
            if (s in 0..<e) seg.segData = data.copyOfRange(s, e)
        }
        return dmData
    }

    // ---- 段级帧解析 ----

    /**
     * 解析单个段：gzip 解压 → base64 解码 → SVG path 提取。
     * 必须在 IO 线程调用。
     */
    fun parseSegmentFrames(segment: LazyMaskSegment, fps: Int, segDurationMs: Long = 0L): List<MaskFrame>? {
        val segBytes = segment.segData ?: return null
        if (segBytes.isEmpty()) return null

        val decompressed = try {
            GZIPInputStream(segBytes.inputStream()).use { gzip ->
                val out = ByteArrayOutputStream()
                gzip.copyTo(out)
                out.toByteArray()
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "GZIP decompress failed: ${e.message}")
            return null
        }

        parseBinaryFrameRecords(decompressed)?.let { return it.takeIf { frames -> frames.isNotEmpty() } }

        // 兼容兜底：如果未来遇到没有二进制帧头的旧格式，再按分隔符解析。
        val separator = SVG_BASE64_PREFIX.toByteArray()
        val parts = splitBy(decompressed, separator)
        if (parts.size <= 1) return null

        val totalFrames = parts.size - 1
        val frames = mutableListOf<MaskFrame>()

        for (frameIdx in 1 until parts.size) {
            val localIdx = frameIdx - 1
            val ptsMs = calculateFramePts(segment.timeMs, localIdx, totalFrames, fps, segDurationMs)

            val b64Data = parts[frameIdx]
            val svgBytes = try {
                Base64.decode(trimBase64Payload(b64Data), Base64.DEFAULT)
            } catch (e: Exception) {
                AppLog.w(TAG, "SVG base64 decode failed at pts=$ptsMs: ${e.message}")
                continue
            }

            val svgText = stripBom(svgBytes.toString(Charsets.UTF_8))
            val parsed = parseSvgPaths(svgText)
            frames.add(
                MaskFrame(
                    presentationTimeMs = ptsMs,
                    paths = parsed.paths,
                    svgWidth = parsed.width,
                    svgHeight = parsed.height,
                    viewBoxX = parsed.viewBoxX,
                    viewBoxY = parsed.viewBoxY,
                    viewBoxWidth = parsed.viewBoxWidth,
                    viewBoxHeight = parsed.viewBoxHeight,
                )
            )
        }

        return frames.takeIf { it.isNotEmpty() }
    }

    private const val SVG_BASE64_PREFIX = "data:image/svg+xml;base64,"

    /**
     * 参考 webmask 的真实帧流格式：
     *   4B payloadLength(BE) + 8B presentationTimeMs(BE) + payload
     *
     * 之前按字符串分隔会把下一帧的 12B header 混到上一帧 base64 尾部。
     * 在 Android Base64 上这会间歇解码失败，并被错误伪造成空 paths，
     * 视觉结果就是人物区域“遮住-露出-遮住”。
     */
    private fun parseBinaryFrameRecords(decompressed: ByteArray): List<MaskFrame>? {
        if (decompressed.size <= FRAME_HEADER_SIZE) return null

        val frames = mutableListOf<MaskFrame>()
        var offset = 0
        var recordIndex = 0

        while (offset + FRAME_HEADER_SIZE <= decompressed.size) {
            val payloadLength = readIntBe(decompressed, offset)
            val ptsMs = readLongBe(decompressed, offset + 4)
            val payloadStart = offset + FRAME_HEADER_SIZE
            val payloadEnd = payloadStart + payloadLength

            if (payloadLength <= 0 || payloadEnd > decompressed.size) {
                return if (recordIndex == 0) null else {
                    AppLog.w(TAG, "Truncated webmask record index=$recordIndex len=$payloadLength offset=$offset total=${decompressed.size}")
                    frames
                }
            }

            val payload = decompressed.copyOfRange(payloadStart, payloadEnd)
            val prefixBytes = SVG_BASE64_PREFIX.toByteArray()
            if (!payload.startsWith(prefixBytes)) {
                return if (recordIndex == 0) null else {
                    AppLog.w(TAG, "Invalid webmask record prefix index=$recordIndex offset=$offset")
                    frames
                }
            }

            val b64Data = payload.copyOfRange(prefixBytes.size, payload.size)
            val svgBytes = try {
                Base64.decode(trimBase64Payload(b64Data), Base64.DEFAULT)
            } catch (e: Exception) {
                AppLog.w(TAG, "SVG base64 decode failed index=$recordIndex pts=$ptsMs: ${e.message}")
                offset = payloadEnd
                recordIndex++
                continue
            }

            val svgText = stripBom(svgBytes.toString(Charsets.UTF_8))
            val parsed = parseSvgPaths(svgText)
            frames.add(
                MaskFrame(
                    presentationTimeMs = ptsMs,
                    paths = parsed.paths,
                    svgWidth = parsed.width,
                    svgHeight = parsed.height,
                    viewBoxX = parsed.viewBoxX,
                    viewBoxY = parsed.viewBoxY,
                    viewBoxWidth = parsed.viewBoxWidth,
                    viewBoxHeight = parsed.viewBoxHeight,
                )
            )

            offset = payloadEnd
            recordIndex++
        }

        return frames.takeIf { it.isNotEmpty() }
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (i in prefix.indices) {
            if (this[i] != prefix[i]) return false
        }
        return true
    }

    private fun readIntBe(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xff) shl 24) or
            ((data[offset + 1].toInt() and 0xff) shl 16) or
            ((data[offset + 2].toInt() and 0xff) shl 8) or
            (data[offset + 3].toInt() and 0xff)
    }

    private fun readLongBe(data: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 8) {
            value = (value shl 8) or (data[offset + i].toLong() and 0xffL)
        }
        return value
    }

    private fun trimBase64Payload(data: ByteArray): ByteArray {
        var end = data.size
        while (end > 0) {
            val b = data[end - 1]
            if (b == '\n'.code.toByte() || b == '\r'.code.toByte() || b == ' '.code.toByte() || b == '\t'.code.toByte()) {
                end--
            } else {
                break
            }
        }
        return if (end == data.size) data else data.copyOf(end)
    }

    // ---- 帧内 PTS 计算 ----

    private fun calculateFramePts(
        segStartMs: Long,
        localIdx: Int,
        totalFrames: Int,
        fps: Int,
        segDurationMs: Long,
    ): Long {
        return if (totalFrames > 1 && segDurationMs > 0L) {
            segStartMs + (localIdx.toLong() * segDurationMs) / totalFrames
        } else if (fps > 0) {
            segStartMs + localIdx.toLong() * 1000L / fps
        } else {
            segStartMs
        }
    }

    // ---- SVG 解析 ----

    private data class ParsedSvg(
        val paths: List<Path>,
        val width: Int,
        val height: Int,
        val viewBoxX: Float,
        val viewBoxY: Float,
        /** viewBox 宽度 × 0.1，用于坐标映射分母。0 表示未提取到。 */
        val viewBoxWidth: Float,
        /** viewBox 高度 × 0.1，用于坐标映射分母。0 表示未提取到。 */
        val viewBoxHeight: Float,
    )

    private fun parseSvgPaths(svgText: String): ParsedSvg {
        // 正则兼容 width="320" 和 width="320px" 两种格式
        var viewWidth = extractFloat(svgText, """width="([\d.]+)(?:px)?"""")
        var viewHeight = extractFloat(svgText, """height="([\d.]+)(?:px)?"""")

        // 解析完整 viewBox（4 个值）
        val viewBox = extractViewBox(svgText)

        // fallback：如果 width/height 缺失，用 viewBox 的 w/h
        if (viewWidth == null || viewWidth <= 0f) {
            viewWidth = viewBox?.w
            if (viewWidth == null || viewWidth <= 0f) {
                AppLog.w(TAG, "SVG parse skip: no width/viewBox, header=${svgText.take(200)}")
                return ParsedSvg(emptyList(), 0, 0, 0f, 0f, 0f, 0f)
            }
        }
        if (viewHeight == null || viewHeight <= 0f) {
            viewHeight = viewBox?.h
            if (viewHeight == null || viewHeight <= 0f) {
                AppLog.w(TAG, "SVG parse skip: no height/viewBox, header=${svgText.take(200)}")
                return ParsedSvg(emptyList(), 0, 0, 0f, 0f, 0f, 0f)
            }
        }

        // viewBox 各分量都不需要 × 0.1（viewBox 与 width/height 同坐标空间）
        val vbX = viewBox?.x ?: 0f
        val vbY = viewBox?.y ?: 0f
        val vbW = viewBox?.w ?: 0f
        val vbH = viewBox?.h ?: 0f

        // 解析 <g> transform，检测 Y 轴翻转
        // webmask SVG 标准格式：<g transform="translate(0,H) scale(0.1,-0.1)">
        // 我们的解析器已内联应用 × 0.1，需要补上 Y 翻转修正：y → viewHeight - y
        val yFlipHeight = detectYFlipHeight(svgText, viewHeight)

        // 匹配 <path> 标签，提取 d 属性和可选的 transform 属性
        val pathRegex = Regex("""<path\s+([^>]*?)d="([^"]+)"([^>]*?)?/?>""")
        val results = mutableListOf<Path>()

        for (match in pathRegex.findAll(svgText)) {
            val preAttrs = match.groupValues[1]
            val d = match.groupValues[2]
            val postAttrs = match.groupValues[3]
            val allAttrs = "$preAttrs $postAttrs"

            val path = svgPathToAndroidPath(d, viewWidth, viewHeight)
            if (path != null) {
                // 应用 <g> transform 的 Y 翻转修正
                if (yFlipHeight > 0f) {
                    val flipMatrix = Matrix()
                    flipMatrix.setScale(1f, -1f)
                    flipMatrix.postTranslate(0f, yFlipHeight)
                    path.transform(flipMatrix)
                }
                // 解析并应用 path 自身的 transform 属性
                val transform = extractTransform(allAttrs)
                if (transform != null) {
                    path.transform(transform)
                }
                results.add(path)
            }
        }
        return ParsedSvg(results, viewWidth.toInt(), viewHeight.toInt(), vbX, vbY, vbW, vbH)
    }

    /**
     * 从 SVG 文本中提取 viewBox 的全部 4 个值。
     * 格式：viewBox="x y width height"
     */
    private fun extractViewBox(svgText: String): ViewBox? {
        val match = Regex(
            """viewBox="([\d.eE+-]+)\s+([\d.eE+-]+)\s+([\d.eE+-]+)\s+([\d.eE+-]+)""""
        ).find(svgText) ?: return null
        val x = match.groupValues[1].toFloatOrNull() ?: return null
        val y = match.groupValues[2].toFloatOrNull() ?: return null
        val w = match.groupValues[3].toFloatOrNull() ?: return null
        val h = match.groupValues[4].toFloatOrNull() ?: return null
        return ViewBox(x, y, w, h)
    }

    /** viewBox 的四个分量（原始值，未 × 0.1）。 */
    private data class ViewBox(val x: Float, val y: Float, val w: Float, val h: Float)

    /**
     * 从 SVG <g> transform 中检测 Y 轴翻转。
     * webmask 标准格式：<g transform="translate(0,H) scale(0.1,-0.1)">
     * 如果 scale Y 分量为负，返回 viewHeight（用于 Y 翻转修正）；否则返回 0。
     */
    private fun detectYFlipHeight(svgText: String, viewHeight: Float): Float {
        val gTransform = Regex("""<g\s+[^>]*?transform="([^"]+)"""")
            .find(svgText)?.groupValues?.get(1) ?: return 0f
        val scaleMatch = Regex("""scale\s*\(\s*([^\s,]+)\s*[,\s]\s*([^)]+)\s*\)""").find(gTransform)
        val scaleY = scaleMatch?.groupValues?.get(2)?.trim()?.toFloatOrNull() ?: return 0f
        return if (scaleY < 0f) viewHeight else 0f
    }

    /**
     * 从 <path> 属性字符串中解析 transform，构建 Android Matrix。
     * 支持的变换函数：translate(tx [, ty])、scale(sx [, sy])、rotate(angle)
     * 多个变换函数从左到右依次应用（postConcat）。
     */
    private fun extractTransform(attrs: String): Matrix? {
        val transformMatch = Regex("""transform="([^"]+)"""").find(attrs) ?: return null
        val value = transformMatch.groupValues[1].trim()
        if (value.isBlank()) return null

        val result = Matrix()
        val funcRegex = Regex("""(translate|scale|rotate)\s*\(([^)]+)\)""")
        var anyApplied = false

        for (funcMatch in funcRegex.findAll(value)) {
            val func = funcMatch.groupValues[1]
            val args = funcMatch.groupValues[2]
                .split("""[\s,]+""".toRegex())
                .mapNotNull { it.toFloatOrNull() }

            val m = Matrix()
            when (func) {
                "translate" -> {
                    if (args.size >= 2) {
                        m.setTranslate(args[0] * 0.1f, args[1] * 0.1f)
                    } else if (args.size == 1) {
                        m.setTranslate(args[0] * 0.1f, 0f)
                    } else continue
                }
                "scale" -> {
                    if (args.size >= 2) {
                        m.setScale(args[0], args[1])
                    } else if (args.size == 1) {
                        m.setScale(args[0], args[0])
                    } else continue
                }
                "rotate" -> {
                    if (args.size >= 1) {
                        m.setRotate(args[0])
                    } else continue
                }
                else -> continue
            }
            result.postConcat(m)
            anyApplied = true
        }
        return if (anyApplied) result else null
    }

    /**
     * SVG path d 属性 → Android Path。
     *
     * 坐标系：webmask SVG 使用标准 SVG 坐标系（Y 轴向下），与 Android 屏幕坐标一致。
     * 坐标值 × 0.1 转为 SVG 像素（webmask 用 10× 精度编码，如 3200 = 320px）。
     *
     * SVG 默认 fill rule 是 nonzero，对应 Android Path.FillType.WINDING。
     * 当前解析后的 webmask path 表示弹幕可显示区，由 HostLayout 用 clipPath 保留。
     *
     * 椭圆弧（A/a）命令：完整参数化弧线实现，将 SVG arc 端点参数转为 Android arcTo 格式。
     */
    private fun svgPathToAndroidPath(d: String, viewWidth: Float, viewHeight: Float): Path? {
        try {
            val path = Path().apply { fillType = Path.FillType.WINDING }
            val tokens = tokenizeSvgPath(d.trim())
            var i = 0
            var currentCommand = 'M'
            var currentX = 0f
            var currentY = 0f
            var lastCubicCtrlX: Float? = null
            var lastCubicCtrlY: Float? = null
            var lastQuadCtrlX: Float? = null
            var lastQuadCtrlY: Float? = null

            fun resetSmoothControls() {
                lastCubicCtrlX = null
                lastCubicCtrlY = null
                lastQuadCtrlX = null
                lastQuadCtrlY = null
            }

            // 坐标转换：标准 SVG（Y 向下），× 0.1 缩放
            fun absoluteX(value: String): Float = value.toFloat() * 0.1f
            fun absoluteY(value: String): Float = value.toFloat() * 0.1f
            fun relativeX(value: String): Float = value.toFloat() * 0.1f
            fun relativeY(value: String): Float = value.toFloat() * 0.1f

            while (i < tokens.size) {
                val token = tokens[i]
                when {
                    token.length == 1 && token[0] in "MLmlCcSsQqTtAaHhVv" -> {
                        currentCommand = token[0]
                        i++
                        continue
                    }
                    token == "z" || token == "Z" -> {
                        path.close()
                        resetSmoothControls()
                        i++
                        continue
                    }
                    token[0].isDigit() || token[0] == '-' || token[0] == '.' -> { /* keep currentCommand */ }
                    else -> { i++; continue }
                }

                when (currentCommand) {
                    'M' -> {
                        if (i + 1 >= tokens.size) break
                        currentX = absoluteX(tokens[i])
                        currentY = absoluteY(tokens[i + 1])
                        path.moveTo(currentX, currentY)
                        resetSmoothControls()
                        i += 2
                        currentCommand = 'L'
                    }
                    'L' -> {
                        if (i + 1 >= tokens.size) break
                        currentX = absoluteX(tokens[i])
                        currentY = absoluteY(tokens[i + 1])
                        path.lineTo(currentX, currentY)
                        resetSmoothControls()
                        i += 2
                    }
                    'H' -> {
                        currentX = absoluteX(tokens[i])
                        path.lineTo(currentX, currentY)
                        resetSmoothControls()
                        i += 1
                    }
                    'V' -> {
                        currentY = absoluteY(tokens[i])
                        path.lineTo(currentX, currentY)
                        resetSmoothControls()
                        i += 1
                    }
                    'm' -> {
                        if (i + 1 >= tokens.size) break
                        val dx = relativeX(tokens[i])
                        val dy = relativeY(tokens[i + 1])
                        path.rMoveTo(dx, dy)
                        currentX += dx
                        currentY += dy
                        resetSmoothControls()
                        i += 2
                        currentCommand = 'l'
                    }
                    'l' -> {
                        if (i + 1 >= tokens.size) break
                        val dx = relativeX(tokens[i])
                        val dy = relativeY(tokens[i + 1])
                        path.rLineTo(dx, dy)
                        currentX += dx
                        currentY += dy
                        resetSmoothControls()
                        i += 2
                    }
                    'h' -> {
                        val dx = relativeX(tokens[i])
                        path.rLineTo(dx, 0f)
                        currentX += dx
                        resetSmoothControls()
                        i += 1
                    }
                    'v' -> {
                        val dy = relativeY(tokens[i])
                        path.rLineTo(0f, dy)
                        currentY += dy
                        resetSmoothControls()
                        i += 1
                    }
                    'C' -> {
                        if (i + 5 >= tokens.size) break
                        val x1 = absoluteX(tokens[i])
                        val y1 = absoluteY(tokens[i + 1])
                        val x2 = absoluteX(tokens[i + 2])
                        val y2 = absoluteY(tokens[i + 3])
                        val x = absoluteX(tokens[i + 4])
                        val y = absoluteY(tokens[i + 5])
                        path.cubicTo(x1, y1, x2, y2, x, y)
                        lastCubicCtrlX = x2
                        lastCubicCtrlY = y2
                        lastQuadCtrlX = null
                        lastQuadCtrlY = null
                        currentX = x
                        currentY = y
                        i += 6
                    }
                    'S' -> {
                        if (i + 3 >= tokens.size) break
                        val x1 = if (lastCubicCtrlX != null && lastCubicCtrlY != null) {
                            2f * currentX - lastCubicCtrlX!!
                        } else currentX
                        val y1 = if (lastCubicCtrlX != null && lastCubicCtrlY != null) {
                            2f * currentY - lastCubicCtrlY!!
                        } else currentY
                        val x2 = absoluteX(tokens[i])
                        val y2 = absoluteY(tokens[i + 1])
                        val x = absoluteX(tokens[i + 2])
                        val y = absoluteY(tokens[i + 3])
                        path.cubicTo(x1, y1, x2, y2, x, y)
                        lastCubicCtrlX = x2
                        lastCubicCtrlY = y2
                        lastQuadCtrlX = null
                        lastQuadCtrlY = null
                        currentX = x
                        currentY = y
                        i += 4
                    }
                    'c' -> {
                        if (i + 5 >= tokens.size) break
                        val x1 = currentX + relativeX(tokens[i])
                        val y1 = currentY + relativeY(tokens[i + 1])
                        val x2 = currentX + relativeX(tokens[i + 2])
                        val y2 = currentY + relativeY(tokens[i + 3])
                        val dx = relativeX(tokens[i + 4])
                        val dy = relativeY(tokens[i + 5])
                        path.rCubicTo(
                            relativeX(tokens[i]), relativeY(tokens[i + 1]),
                            relativeX(tokens[i + 2]), relativeY(tokens[i + 3]),
                            dx, dy
                        )
                        currentX += dx
                        currentY += dy
                        lastCubicCtrlX = x2
                        lastCubicCtrlY = y2
                        lastQuadCtrlX = null
                        lastQuadCtrlY = null
                        i += 6
                    }
                    's' -> {
                        if (i + 3 >= tokens.size) break
                        val x1 = if (lastCubicCtrlX != null && lastCubicCtrlY != null) {
                            2f * currentX - lastCubicCtrlX!!
                        } else currentX
                        val y1 = if (lastCubicCtrlX != null && lastCubicCtrlY != null) {
                            2f * currentY - lastCubicCtrlY!!
                        } else currentY
                        val x2 = currentX + relativeX(tokens[i])
                        val y2 = currentY + relativeY(tokens[i + 1])
                        val dx = relativeX(tokens[i + 2])
                        val dy = relativeY(tokens[i + 3])
                        val x = currentX + dx
                        val y = currentY + dy
                        path.cubicTo(x1, y1, x2, y2, x, y)
                        currentX = x
                        currentY = y
                        lastCubicCtrlX = x2
                        lastCubicCtrlY = y2
                        lastQuadCtrlX = null
                        lastQuadCtrlY = null
                        i += 4
                    }
                    'Q' -> {
                        if (i + 3 >= tokens.size) break
                        val x1 = absoluteX(tokens[i])
                        val y1 = absoluteY(tokens[i + 1])
                        val x = absoluteX(tokens[i + 2])
                        val y = absoluteY(tokens[i + 3])
                        path.quadTo(x1, y1, x, y)
                        lastQuadCtrlX = x1
                        lastQuadCtrlY = y1
                        lastCubicCtrlX = null
                        lastCubicCtrlY = null
                        currentX = x
                        currentY = y
                        i += 4
                    }
                    'q' -> {
                        if (i + 3 >= tokens.size) break
                        val x1 = currentX + relativeX(tokens[i])
                        val y1 = currentY + relativeY(tokens[i + 1])
                        val dx = relativeX(tokens[i + 2])
                        val dy = relativeY(tokens[i + 3])
                        val x = currentX + dx
                        val y = currentY + dy
                        path.quadTo(x1, y1, x, y)
                        lastQuadCtrlX = x1
                        lastQuadCtrlY = y1
                        lastCubicCtrlX = null
                        lastCubicCtrlY = null
                        currentX = x
                        currentY = y
                        i += 4
                    }
                    'T' -> {
                        if (i + 1 >= tokens.size) break
                        val x1 = if (lastQuadCtrlX != null && lastQuadCtrlY != null) {
                            2f * currentX - lastQuadCtrlX!!
                        } else currentX
                        val y1 = if (lastQuadCtrlX != null && lastQuadCtrlY != null) {
                            2f * currentY - lastQuadCtrlY!!
                        } else currentY
                        val x = absoluteX(tokens[i])
                        val y = absoluteY(tokens[i + 1])
                        path.quadTo(x1, y1, x, y)
                        lastQuadCtrlX = x1
                        lastQuadCtrlY = y1
                        lastCubicCtrlX = null
                        lastCubicCtrlY = null
                        currentX = x
                        currentY = y
                        i += 2
                    }
                    't' -> {
                        if (i + 1 >= tokens.size) break
                        val x1 = if (lastQuadCtrlX != null && lastQuadCtrlY != null) {
                            2f * currentX - lastQuadCtrlX!!
                        } else currentX
                        val y1 = if (lastQuadCtrlX != null && lastQuadCtrlY != null) {
                            2f * currentY - lastQuadCtrlY!!
                        } else currentY
                        val dx = relativeX(tokens[i])
                        val dy = relativeY(tokens[i + 1])
                        val x = currentX + dx
                        val y = currentY + dy
                        path.quadTo(x1, y1, x, y)
                        lastQuadCtrlX = x1
                        lastQuadCtrlY = y1
                        lastCubicCtrlX = null
                        lastCubicCtrlY = null
                        currentX = x
                        currentY = y
                        i += 2
                    }
                    'A' -> {
                        if (i + 6 >= tokens.size) break
                        val rx = tokens[i].toFloat() * 0.1f
                        val ry = tokens[i + 1].toFloat() * 0.1f
                        val angle = tokens[i + 2].toFloat()
                        val largeArc = tokens[i + 3].toInt() != 0
                        val sweep = tokens[i + 4].toInt() != 0
                        val endX = absoluteX(tokens[i + 5])
                        val endY = absoluteY(tokens[i + 6])
                        drawArc(path, currentX, currentY, rx, ry, angle, largeArc, sweep, endX, endY)
                        currentX = endX
                        currentY = endY
                        resetSmoothControls()
                        i += 7
                    }
                    'a' -> {
                        if (i + 6 >= tokens.size) break
                        val rx = tokens[i].toFloat() * 0.1f
                        val ry = tokens[i + 1].toFloat() * 0.1f
                        val angle = tokens[i + 2].toFloat()
                        val largeArc = tokens[i + 3].toInt() != 0
                        val sweep = tokens[i + 4].toInt() != 0
                        val endX = currentX + relativeX(tokens[i + 5])
                        val endY = currentY + relativeY(tokens[i + 6])
                        drawArc(path, currentX, currentY, rx, ry, angle, largeArc, sweep, endX, endY)
                        currentX = endX
                        currentY = endY
                        resetSmoothControls()
                        i += 7
                    }
                    else -> i++
                }
            }
            return path
        } catch (e: Exception) {
            AppLog.e(TAG, "SVG path parse error: ${e.message}")
            return null
        }
    }

    // ---- 工具方法 ----

    private fun tokenizeSvgPath(d: String): List<String> {
        val tokens = mutableListOf<String>()
        val sb = StringBuilder()
        val commands = "MmLlCcSsQqTtAaHhVvZz"

        for (ch in d) {
            if (ch in commands) {
                if (sb.isNotEmpty()) {
                    tokens.add(sb.toString())
                    sb.clear()
                }
                tokens.add(ch.toString())
            } else if (ch.isWhitespace() || ch == ',') {
                if (sb.isNotEmpty()) {
                    tokens.add(sb.toString())
                    sb.clear()
                }
            } else {
                sb.append(ch)
            }
        }
        if (sb.isNotEmpty()) {
            tokens.add(sb.toString())
        }
        return tokens
    }

    private fun extractFloat(text: String, pattern: String): Float? {
        return Regex(pattern).find(text)?.groupValues?.get(1)?.toFloatOrNull()
    }

    /** 剥离 UTF-8 BOM 头（`﻿`），防止 Base64 解码失败。 */
    private fun stripBom(text: String): String {
        return if (text.startsWith("﻿")) text.substring(1) else text
    }

    /**
     * 将 SVG 端点参数弧线（rx, ry, rotation, largeArc, sweep, endX, endY）
     * 转换为 Android Path.arcTo 所需的中心参数格式。
     *
     * 算法基于 W3C SVG 规范的端点参数到中心参数转换（F.6.5 / F.6.6）。
     * 如果半径为 0 或起点等于终点，退化为 lineTo。
     */
    private fun drawArc(
        path: Path,
        startX: Float, startY: Float,
        rx: Float, ry: Float,
        rotationDeg: Float,
        largeArc: Boolean, sweep: Boolean,
        endX: Float, endY: Float,
    ) {
        // 退化情况
        if ((rx == 0f || ry == 0f) || (startX == endX && startY == endY)) {
            path.lineTo(endX, endY)
            return
        }

        val phi = Math.toRadians(rotationDeg.toDouble())
        val cosPhi = Math.cos(phi).toFloat()
        val sinPhi = Math.sin(phi).toFloat()

        // F.6.5.1: 计算 (x1', y1')
        val dx = (startX - endX) / 2f
        val dy = (startY - endY) / 2f
        val x1p = cosPhi * dx + sinPhi * dy
        val y1p = -sinPhi * dx + cosPhi * dy

        // F.6.5.2: 修正半径
        val x1pSq = x1p * x1p
        val y1pSq = y1p * y1p
        val rxSq = rx * rx
        val rySq = ry * ry
        val lambda = x1pSq / rxSq + y1pSq / rySq
        val (effectiveRx, effectiveRy) = if (lambda > 1f) {
            val sqrtLambda = kotlin.math.sqrt(lambda)
            Pair(rx * sqrtLambda, ry * sqrtLambda)
        } else {
            Pair(rx, ry)
        }
        val eRxSq = effectiveRx * effectiveRx
        val eRySq = effectiveRy * effectiveRy

        // F.6.5.3: 计算 (cx', cy')
        val sqrtNumerator = (eRxSq * eRySq - eRxSq * y1pSq - eRySq * x1pSq)
            .coerceAtLeast(0f)
        val sqrtDenominator = eRxSq * y1pSq + eRySq * x1pSq
        var s = 1f
        if (largeArc == sweep) s = -1f
        val sq = if (sqrtDenominator > 0f) kotlin.math.sqrt(sqrtNumerator / sqrtDenominator) else 0f
        val cxp = s * sq * (effectiveRx * y1p / effectiveRy)
        val cyp = s * sq * -(effectiveRy * x1p / effectiveRx)

        // F.6.5.5: 计算 (cx, cy)
        val cx = cosPhi * cxp - sinPhi * cyp + (startX + endX) / 2f
        val cy = sinPhi * cxp + cosPhi * cyp + (startY + endY) / 2f

        // F.6.5.6: 计算起始角和扫描角
        fun vecAngle(ux: Float, uy: Float, vx: Float, vy: Float): Float {
            val dot = ux * vx + uy * vy
            val len = kotlin.math.sqrt((ux * ux + uy * uy) * (vx * vx + vy * vy))
            var angle = kotlin.math.acos((dot / len).coerceIn(-1f, 1f).toDouble())
            if (ux * vy - uy * vx < 0) angle = -angle
            return angle.toFloat()
        }

        val theta1 = vecAngle(1f, 0f, (x1p - cxp) / effectiveRx, (y1p - cyp) / effectiveRy)
        val dTheta = vecAngle(
            (x1p - cxp) / effectiveRx, (y1p - cyp) / effectiveRy,
            (-x1p - cxp) / effectiveRx, (-y1p - cyp) / effectiveRy
        )
        var sweepAngle = dTheta
        if (sweep && sweepAngle < 0) {
            sweepAngle += (2 * Math.PI).toFloat()
        } else if (!sweep && sweepAngle > 0) {
            sweepAngle -= (2 * Math.PI).toFloat()
        }

        // 使用 Android Path.arcTo 的 oval 方式
        val oval = RectF(
            cx - effectiveRx, cy - effectiveRy,
            cx + effectiveRx, cy + effectiveRy
        )
        val startAngleDeg = Math.toDegrees(theta1.toDouble()).toFloat()
        val sweepAngleDeg = Math.toDegrees(sweepAngle.toDouble()).toFloat()
        path.arcTo(oval, startAngleDeg, sweepAngleDeg, false)
    }

    /** 按分隔符切分 byte 数组。 */
    private fun splitBy(data: ByteArray, delimiter: ByteArray): List<ByteArray> {
        val result = mutableListOf<ByteArray>()
        var start = 0
        var i = 0
        while (i <= data.size - delimiter.size) {
            var match = true
            for (j in delimiter.indices) {
                if (data[i + j] != delimiter[j]) {
                    match = false
                    break
                }
            }
            if (match) {
                result.add(data.copyOfRange(start, i))
                start = i + delimiter.size
                i = start
            } else {
                i++
            }
        }
        result.add(data.copyOfRange(start, data.size))
        return result
    }
}
