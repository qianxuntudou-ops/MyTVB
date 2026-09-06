package com.tutu.myblbl.feature.player.danmaku

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Message
import android.os.Process
import androidx.appcompat.content.res.AppCompatResources
import com.tutu.myblbl.R
import com.tutu.myblbl.core.common.log.AppLog
import com.tutu.myblbl.feature.player.danmaku.model.DanmakuInlineSegment
import com.tutu.myblbl.feature.player.danmaku.model.DanmakuItem
import com.tutu.myblbl.feature.player.danmaku.model.SharedCacheEntry
import com.tutu.myblbl.feature.player.danmaku.common.BiliDanmakuStyle
import com.tutu.myblbl.feature.player.danmaku.common.BitmapMemoryBudget
import com.tutu.myblbl.feature.player.danmaku.common.DanmakuInlineParser
import com.tutu.myblbl.feature.player.danmaku.common.VipDanmakuTextureCache
import com.tutu.myblbl.feature.player.danmaku.common.estimatedArgb8888Bytes
import com.tutu.myblbl.feature.player.danmaku.common.reclaimUntilBitmapBudgetFits
import com.tutu.myblbl.feature.player.danmaku.common.resolveDanmakuBitmapBudgetBytes
import com.tutu.myblbl.feature.player.danmaku.emote.DanmakuEmoteRepository
import com.tutu.myblbl.feature.player.danmaku.emote.EmoteBitmapLoader
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

internal data class CacheStyle(
    val textSizePx: Float,
    val fontWeight: DanmakuFontWeight,
    val strokeWidthPx: Float,
    val outlinePadPx: Float,
    val generation: Int,
    /** 高赞弹幕头部点赞图标开关（影响烘焙内容，参与样式指纹与解析缓存失效）。 */
    val showHighLikeIcon: Boolean,
)

internal data class CacheBuildResult(
    val item: DanmakuItem,
    val entry: SharedCacheEntry?,
    val generation: Int,
)

internal class CacheManager(
    private val appContext: Context,
    private val onCacheResult: (CacheBuildResult) -> Unit,
) {
    companion object {
        private const val TAG = "DanmakuCache"

        /** 高赞图标着色（对齐 blbl / 官方的高赞弹幕点赞图标金色）。 */
        private val HIGH_LIKE_ICON_COLOR = Color.parseColor("#F6C343")

        private const val MSG_BUILD_CACHE = 2001
        private const val MSG_CLEAR = 2002
        private const val MSG_RELEASE = 2099
        private const val MSG_FLUSH_RELEASED = 2003

        private const val CACHE_POOL_MAX_COUNT: Int = 72

        // 洪峰段快照每帧重建、每次 lease ~同屏数条引用：acquire 可达 ~8500/s（50fps×170），
        // 本 drain 是唯一的释放出口——24/帧（1200/s）会让释放持续欠账数十万条，
        // 旧位图 refcount 永远 >0 → 预算无法回收 → 建图失败 → 弹幕变稀。
        // 单条出队成本极低（重活由 cache 线程在 MSG_FLUSH_RELEASED 执行），放大到 256/帧。
        private const val MAX_RELEASE_PER_DRAIN = 256
        private const val MAX_SHARED_CACHE = 256

        // FNV-1a 64-bit（与 akdanmaku sharedCacheKey 同算法）
        private const val FNV_OFFSET = -3750763034362895579L
        private const val FNV_PRIME = 1099511628211L
    }

    private val thread: HandlerThread =
        HandlerThread("Danmaku-Cache").apply {
            start()
            // 不再用 THREAD_PRIORITY_BACKGROUND：TV 盒子的 big.LITTLE 调度会把后台线程压到小核，
            // 视频解码繁忙时建图饥饿 → 弹幕缓存等待超时丢弃（"半路消失"路径之一）。
            // 建图是短促突发（每条 ~1ms 级），默认优先级不会干扰解码，却显著降低入场延迟。
            runCatching { Process.setThreadPriority(threadId, Process.THREAD_PRIORITY_DEFAULT) }
        }

    private val handler: Handler = CacheHandler(thread.looper)

    private val bitmapBudget = BitmapMemoryBudget(
        resolveDanmakuBitmapBudgetBytes(
            screenWidth = appContext.resources.displayMetrics.widthPixels,
            screenHeight = appContext.resources.displayMetrics.heightPixels,
        )
    )
    private val pool = BitmapPool(
        maxBytes = bitmapBudget.maxBytes,
        maxCount = CACHE_POOL_MAX_COUNT,
        onEvict = ::recycleBitmap,
    )

    /**
     * 共享缓存表：相同内容（text+color+textSize+stroke+typeface）的弹幕共享同一个 SharedCacheEntry。
     * accessOrder=true 实现 LRU，超 MAX_SHARED_CACHE 时淘汰最久未访问条目。
     * 只在 CacheHandler 线程访问（buildCache/查询/淘汰），无需加锁。
     * 淘汰时 release 表持有的那份引用；bitmap 是否回收由引用计数决定（item 可能还在用）。
     */
    private val sharedCacheStore = object : java.util.LinkedHashMap<Long, SharedCacheEntry>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, SharedCacheEntry>?): Boolean {
            if (size <= MAX_SHARED_CACHE) return false
            eldest?.value?.let { entry ->
                sharedTableSize.decrementAndGet()
                // 释放共享表持有的那份引用。若此时已无 item 引用，bitmap 立刻可回收；
                // 若仍有 item 在用，bitmap 等最后一个 item release 时才回收。
                if (entry.release()) {
                    recycleBitmap(entry.bitmap)
                }
            }
            return true
        }
    }
    private var sharedCacheGeneration = -1

    // 共享表条目数近似值（仅诊断用：cache 线程写、主线程读）。
    private val sharedTableSize = java.util.concurrent.atomic.AtomicInteger(0)
    private var lastBudgetLogMs = 0L
    private var buildCounter = 0
    private val sharedHit = AtomicLong(0L)

    private val queueDepth = AtomicInteger(0)

    /**
     * 延迟释放队列：SPSC 有界环形数组。
     * - 生产者：action 线程（enqueueRelease，快照发布/退场路径，密集场景每帧可达上百条）；
     * - 消费者：主线程（drainReleasedBitmaps，每帧 draw 开头）；
     * - release 时序（view detach）保证生产停止（action 队列已清+线程退出）且主线程消费停止
     *   （released 早退），cache 线程的 drainAll 独占消费。
     * 替代 ConcurrentLinkedQueue<PendingRelease>：此前每条释放都要分配 PendingRelease 对象
     * + CLQ 节点（密集场景 ~1.2 万个小对象/秒的 GC churn），环形数组零稳态分配。
     * 极端溢出（容量耗尽，约 10 帧发布量）回退到无锁 CLQ，drain 时优先处理。
     */
    private val releaseRing = PendingReleaseRing(capacity = 1024)
    private val releaseOverflowQueue: ConcurrentLinkedQueue<PendingRelease> = ConcurrentLinkedQueue()

    private val bitmapCreated = AtomicLong(0L)
    private val bitmapReused = AtomicLong(0L)
    private val bitmapPutToPool = AtomicLong(0L)
    private val bitmapRecycled = AtomicLong(0L)

    // Cache draw tools (cache thread only).
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        // 对齐 akdanmaku SimpleRenderer + DanmakuEngine.drawFill：不开 subpixel，
        // 避免 TV/OLED 上纯色文字边缘 RGB 子像素分离导致整体发灰。
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        style = Paint.Style.STROKE
        // 对齐 akdanmaku：ROUND 连接，拐角不尖刺，保留更多彩色像素。
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val fontMetrics = Paint.FontMetrics()

    // ---- 内联段（表情/高赞图标）绘制工具（cache 线程专用）----
    private val emotePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
    private val emotePlaceholderFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = (0x22 shl 24) or 0x000000
    }
    private val emotePlaceholderStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = (0x66 shl 24) or 0xFFFFFF
        strokeWidth = max(1f, appContext.resources.displayMetrics.density)
    }
    private val emoteRect = RectF()
    private val inlineLikeIcon by lazy(LazyThreadSafetyMode.NONE) {
        AppCompatResources.getDrawable(appContext, R.drawable.ic_like)?.mutate()?.apply {
            setTint(HIGH_LIKE_ICON_COLOR)
        }
    }

    fun queueDepth(): Int = queueDepth.get().coerceAtLeast(0)

    fun poolSnapshot(): PoolSnapshot = pool.snapshot()

    fun statsSnapshot(): StatsSnapshot {
        val budget = bitmapBudget.snapshot()
        val poolSnap = pool.snapshot()
        val snapshot = StatsSnapshot(
            bitmapCreated = bitmapCreated.get(),
            bitmapReused = bitmapReused.get(),
            bitmapPutToPool = bitmapPutToPool.get(),
            bitmapRecycled = bitmapRecycled.get(),
            sharedHit = sharedHit.get(),
            bitmapBytes = budget.usedBytes,
            bitmapMaxBytes = budget.maxBytes,
            bitmapCount = budget.bitmapCount,
            sharedTableSize = sharedTableSize.get(),
        )
        // 预算水位诊断（30s 限频）：定位"预算耗尽 → 建图失败 → 弹幕变稀"时
        // 36MB 到底被谁占着——表内条目 / 池 / 在外引用（item+快照 lease）。
        val nowMs = android.os.SystemClock.elapsedRealtime()
        if (budget.usedBytes * 10L >= budget.maxBytes * 9L && nowMs - lastBudgetLogMs >= 30_000L) {
            lastBudgetLogMs = nowMs
            AppLog.w(
                TAG,
                "budget HIGH used=${budget.usedBytes / 1024}KB/${budget.maxBytes / 1024}KB " +
                    "count=${budget.bitmapCount} table=${snapshot.sharedTableSize} " +
                    "poolBytes=${poolSnap.bytes / 1024}KB poolCount=${poolSnap.count} " +
                    "queue=${queueDepth.get()} created=${bitmapCreated.get()} recycled=${bitmapRecycled.get()} " +
                    "putToPool=${bitmapPutToPool.get()} " +
                    "refs=${SharedCacheEntry.acquiredTotal.get() - SharedCacheEntry.releasedTotal.get()} " +
                    "(+${SharedCacheEntry.acquiredTotal.get()}/-${SharedCacheEntry.releasedTotal.get()})"
            )
        }
        return snapshot
    }

    fun requestBuildCache(
        item: DanmakuItem,
        textWidthPx: Float,
        style: CacheStyle,
    ) {
        val payload = CacheRequest(item = item, textWidthPx = textWidthPx, style = style)
        queueDepth.incrementAndGet()
        handler.obtainMessage(MSG_BUILD_CACHE, payload).sendToTarget()
    }

    fun enqueueRelease(entry: SharedCacheEntry?, releaseAtFrameId: Int) {
        if (entry == null) return
        if (entry.isRecycled) return
        if (!releaseRing.add(entry, releaseAtFrameId)) {
            releaseOverflowQueue.add(PendingRelease(entry = entry, releaseAtFrameId = releaseAtFrameId))
        }
    }

    /**
     * 延迟回收队列：主线程（drainReleasedBitmaps）只做出队并转投本队列，
     * 真正的 pool 归还/bitmap.recycle()（触发 NativeAllocationRegistry 与驱动侧纹理释放，
     * 突发时可达每帧 24 条）由 cache 线程在 MSG_FLUSH_RELEASED 中执行，避免顶到 vsync。
     * recycleFlushPosted 去重：同时最多挂起一个 flush 消息。
     */
    private val recycleLock = Any()
    private val recycleQueue = ArrayDeque<SharedCacheEntry>()
    private val recycleFlushPosted = java.util.concurrent.atomic.AtomicBoolean(false)

    fun drainReleasedBitmaps(currentFrameId: Int) {
        var drained = 0
        // 溢出队列持有更早的条目：非空时优先处理，其头部若未到期则环形队列同样等待。
        while (drained < MAX_RELEASE_PER_DRAIN) {
            val entry: SharedCacheEntry =
                if (!releaseOverflowQueue.isEmpty()) {
                    val head = releaseOverflowQueue.peek() ?: break
                    if (head.releaseAtFrameId > currentFrameId) return
                    releaseOverflowQueue.poll().entry
                } else {
                    if (releaseRing.isEmpty()) break
                    if (releaseRing.peekReleaseAtFrameId() > currentFrameId) return
                    releaseRing.pollEntry() ?: break
                }
            drained++
            synchronized(recycleLock) { recycleQueue.addLast(entry) }
        }
        if (drained > 0) flushRecycleQueueAsync()
    }

    private fun flushRecycleQueueAsync() {
        if (!recycleFlushPosted.compareAndSet(false, true)) return
        val sent = runCatching { handler.sendEmptyMessage(MSG_FLUSH_RELEASED) }.isSuccess
        if (!sent) {
            // cache 线程已退出（release 后的迟到 drain）：主线程兜底同步回收。
            recycleFlushPosted.set(false)
            flushRecycleQueueSync()
        }
    }

    private fun flushRecycleQueueSync() {
        while (true) {
            val entry = synchronized(recycleLock) { recycleQueue.removeFirstOrNull() } ?: break
            processReleasedEntry(entry)
        }
    }

    private fun processReleasedEntry(entry: SharedCacheEntry) {
        if (entry.isRecycled) return
        // release() 归零说明没有其他持有者（item 已退场 + 共享表已淘汰），可安全回收。
        if (!entry.release()) return
        val bmp = entry.bitmap
        if (bmp.isRecycled) return
        val pooled = pool.tryPut(bmp)
        if (!pooled) {
            recycleBitmap(bmp)
            bitmapRecycled.incrementAndGet()
        } else {
            bitmapPutToPool.incrementAndGet()
        }
    }

    fun clear() {
        handler.removeCallbacksAndMessages(null)
        handler.sendEmptyMessage(MSG_CLEAR)
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        handler.sendEmptyMessage(MSG_RELEASE)
    }

    private inner class CacheHandler(looper: Looper) : Handler(looper) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MSG_BUILD_CACHE -> {
                    val req = msg.obj as? CacheRequest ?: return
                    queueDepth.decrementAndGet()
                    buildCache(req)
                }
                MSG_FLUSH_RELEASED -> {
                    recycleFlushPosted.set(false)
                    flushRecycleQueueSync()
                    // drain 与本消息竞态新入队：再挂一个 flush 兜底。
                    if (synchronized(recycleLock) { recycleQueue.isNotEmpty() }) {
                        flushRecycleQueueAsync()
                    }
                }
                MSG_CLEAR -> {
                    recycleFlushPosted.set(false)
                    queueDepth.set(0)
                    clearSharedCacheStore()
                    releaseAllPendingEntries()
                    flushRecycleQueueSync()
                    pool.clear()
                }
                MSG_RELEASE -> {
                    removeCallbacksAndMessages(null)
                    recycleFlushPosted.set(false)
                    queueDepth.set(0)
                    clearSharedCacheStore()
                    releaseAllPendingEntries()
                    flushRecycleQueueSync()
                    pool.clear()
                    runCatching { thread.quitSafely() }
                }
            }
        }
    }

    private fun buildCache(req: CacheRequest) {
        val item = req.item
        val style = req.style

        if (style.textSizePx <= 0f || !style.textSizePx.isFinite()) {
            publishResult(req, null)
            return
        }

        // 样式 generation 变化时清空共享表（旧 bitmap 内容已不匹配新样式）。
        if (sharedCacheGeneration != style.generation) {
            clearSharedCacheStore()
            sharedCacheGeneration = style.generation
        }

        // 共享缓存命中：相同内容（text+color+textSize+stroke+typeface）的弹幕复用同一 bitmap。
        val danmaku = item.data
        // 内联段（表情/高赞图标）：VIP 渐变走整行贴图，不掺内联段。
        // 表情位图未就绪的占位版本与完整版本分开存（emotePlaceholder 掺 key），
        // 避免"占位 bitmap 被共享后，表情就绪的弹幕永远命中占位版"。
        val segments =
            if (danmaku.vipGradient) null else resolveInlineSegments(item, style)
        val emotePlaceholder = segments != null && segments.any {
            it is DanmakuInlineSegment.Emote && EmoteBitmapLoader.getCached(it.url) == null
        }
        val key = sharedCacheKey(
            text = danmaku.text,
            color = danmaku.color and 0xFFFFFF,
            textSizePx = style.textSizePx,
            typefaceOrdinal = style.fontWeight.ordinal,
            strokeWidthPx = style.strokeWidthPx,
            outlinePadPx = style.outlinePadPx,
            vipGradient = danmaku.vipGradient,
            vipFillTextureUrl = danmaku.vipGradientStyle.fillTextureUrl,
            vipStrokeTextureUrl = danmaku.vipGradientStyle.strokeTextureUrl,
            vipFillTextureLoaded = danmaku.vipGradient && VipDanmakuTextureCache.getBitmap(danmaku.vipGradientStyle.fillTextureUrl) != null,
            vipStrokeTextureLoaded = danmaku.vipGradient && VipDanmakuTextureCache.getBitmap(danmaku.vipGradientStyle.strokeTextureUrl) != null,
            emotePlaceholder = emotePlaceholder,
        )
        val shared = sharedCacheStore[key]
        if (shared != null && !shared.isRecycled) {
            sharedHit.incrementAndGet()
            publishResult(req, shared)
            return
        }
        // 命中失败（bitmap 已回收）时剔除脏条目。
        if (shared != null) {
            sharedCacheStore.remove(key)
            sharedTableSize.decrementAndGet()
            if (shared.release()) {
                recycleBitmap(shared.bitmap)
            }
        }

        // ---- 未命中：构建新 bitmap（原有逻辑）----
        val outlinePad = style.outlinePadPx.coerceAtLeast(0f)
        val strokeWidth = style.strokeWidthPx.coerceAtLeast(0f)

        val desiredTypeface = style.fontWeight.typeface
        if (fill.typeface != desiredTypeface) fill.typeface = desiredTypeface
        if (stroke.typeface != desiredTypeface) stroke.typeface = desiredTypeface

        fill.textSize = style.textSizePx
        stroke.textSize = style.textSizePx
        stroke.strokeWidth = strokeWidth

        fill.getFontMetrics(fontMetrics)
        // 度量高度对齐 akdanmaku 与 DanmakuEngine.act()：descent - ascent + leading。
        val textHeightPx = (fontMetrics.descent - fontMetrics.ascent + fontMetrics.leading).coerceAtLeast(1f)
        val boxHeight = ceil(textHeightPx + outlinePad * 2f).toInt().coerceAtLeast(1)
        val boxWidth = ceil(req.textWidthPx.coerceAtLeast(outlinePad * 2f)).toInt().coerceAtLeast(1)

        val bmp =
            pool.acquire(minWidth = boxWidth, minHeight = boxHeight)
                ?.also { bitmapReused.incrementAndGet() }
                ?: createBitmapWithinBudget(boxWidth, boxHeight)
                    ?.also { bitmapCreated.incrementAndGet() }
                ?: run {
                    publishResult(req, null)
                    return
                }

        // Always clear when reusing.
        runCatching { bmp.eraseColor(0x00000000) }

        val canvas = Canvas(bmp)

        val rgb = danmaku.color and 0xFFFFFF
        // 描边规则不变：亮字配黑描边，暗字配白描边。
        // 缓存图按原始不透明样式烘焙，播放透明度由 drawBitmap 统一施加，保证缓存路径和直绘路径一致。
        stroke.color = BiliDanmakuStyle.resolveStrokeColor(rgb, opacityAlpha = 255)
        fill.color = (0xFF shl 24) or rgb

        val baseline = outlinePad - fontMetrics.ascent
        val text = danmaku.text
        val drawStrokeEnabled = strokeWidth > 0.01f
        if (text.isNotBlank()) {
            val vipDrawn = if (danmaku.vipGradient) {
                // VIP 弹幕：完全复刻 B 站，用 colorful_src 贴图。贴图未加载时返回 false。
                VipGradientRenderer.draw(
                    canvas = canvas,
                    text = text,
                    style = danmaku.vipGradientStyle,
                    startX = outlinePad,
                    baselineY = baseline,
                    textSizePx = style.textSizePx,
                    fillPaint = fill,
                    strokePaint = stroke
                )
            } else {
                false
            }
            if (!vipDrawn) {
                // 普通弹幕（或 VIP 贴图未加载时的白字兜底）：含表情/高赞图标时分段烘焙。
                drawTextSegments(
                    canvas = canvas,
                    text = text,
                    segments = segments,
                    outlinePad = outlinePad,
                    baseline = baseline,
                    textHeightPx = textHeightPx,
                    drawStrokeEnabled = drawStrokeEnabled,
                )
            }
        }

        // 构建完成后只由共享表持有。Action 线程接收结果时再申请 item 引用。
        val entry = SharedCacheEntry(bmp)
        entry.acquire()
        if (sharedCacheStore[key] == null) sharedTableSize.incrementAndGet()
        sharedCacheStore[key] = entry
        enforceSharedCacheBound()
        publishResult(req, entry)
    }

    private fun publishResult(req: CacheRequest, entry: SharedCacheEntry?) {
        runCatching {
            onCacheResult(CacheBuildResult(req.item, entry, req.style.generation))
        }.onFailure {
            AppLog.w(TAG, "cache result dispatch failed", it)
        }
    }

    /**
     * 取/解析内联段（cache 线程侧副本，与 Engine.parseInlineSegmentsFor 同逻辑）：
     * 缓存命中要求词典版本与高赞图标开关一致；词典未就绪时不缓存解析结果。
     * 与 action 线程并发写 item.inlineSegments 幂等（同输入同输出），volatile 保证可见性。
     */
    private fun resolveInlineSegments(
        item: DanmakuItem,
        style: CacheStyle,
    ): List<DanmakuInlineSegment>? {
        val emoteVersion = DanmakuEmoteRepository.version()
        val cached = item.inlineSegments
        if (cached != null && item.inlineSegmentsEmoteVersion == emoteVersion &&
            item.inlineSegmentsShowIcon == style.showHighLikeIcon
        ) return cached
        val parsed = DanmakuInlineParser.parse(
            text = item.data.text,
            isHighLiked = item.data.isHighLiked,
            showHighLikeIcon = style.showHighLikeIcon,
            canParseEmote = emoteVersion > 0,
            urlForToken = DanmakuEmoteRepository::urlForToken,
        )
        if (parsed != null && DanmakuInlineParser.shouldCacheParsedSegments(item.data.text, emoteVersion > 0)) {
            item.inlineSegments = parsed
            item.inlineSegmentsEmoteVersion = emoteVersion
            item.inlineSegmentsShowIcon = style.showHighLikeIcon
        }
        return parsed
    }

    /**
     * 分段烘焙：Text 段描边+填充，表情段画位图（缺失时圆角占位框 + prefetch），高赞段画点赞图标。
     * 行高/间隙口径与 DanmakuEngine.measureTextWidthWithInlineSegments 一致，保证不裁内容。
     */
    private fun drawTextSegments(
        canvas: Canvas,
        text: String,
        segments: List<DanmakuInlineSegment>?,
        outlinePad: Float,
        baseline: Float,
        textHeightPx: Float,
        drawStrokeEnabled: Boolean,
    ) {
        if (segments == null) {
            if (drawStrokeEnabled) canvas.drawText(text, outlinePad, baseline, stroke)
            canvas.drawText(text, outlinePad, baseline, fill)
            return
        }
        val emoteSizePx = textHeightPx.coerceAtLeast(1f)
        val emoteTop = outlinePad
        val radius = (emoteSizePx * 0.18f).coerceIn(2f, 10f)
        val iconGapPx = inlineIconGapPx(emoteSizePx)
        var cursorX = outlinePad
        for (seg in segments) {
            when (seg) {
                is DanmakuInlineSegment.Text -> {
                    if (seg.end > seg.start) {
                        if (drawStrokeEnabled) canvas.drawText(text, seg.start, seg.end, cursorX, baseline, stroke)
                        canvas.drawText(text, seg.start, seg.end, cursorX, baseline, fill)
                        cursorX += fill.measureText(text, seg.start, seg.end)
                    }
                }
                is DanmakuInlineSegment.Emote -> {
                    val emote = EmoteBitmapLoader.getCached(seg.url)
                    if (emote != null && !emote.isRecycled) {
                        emoteRect.set(cursorX, emoteTop, cursorX + emoteSizePx, emoteTop + emoteSizePx)
                        canvas.drawBitmap(emote, null, emoteRect, emotePaint)
                    } else {
                        // Engine 建图前已做就绪检查，此处缺失属极端竞态（烘焙瞬间被逐出）：
                        // 画占位框兜底，prefetch 让下一次重建拿到完整版本。
                        EmoteBitmapLoader.prefetch(seg.url)
                        emoteRect.set(cursorX, emoteTop, cursorX + emoteSizePx, emoteTop + emoteSizePx)
                        canvas.drawRoundRect(emoteRect, radius, radius, emotePlaceholderFill)
                        canvas.drawRoundRect(emoteRect, radius, radius, emotePlaceholderStroke)
                    }
                    cursorX += emoteSizePx
                }
                is DanmakuInlineSegment.HighLikeIcon -> {
                    drawInlineLikeIcon(cursorX, emoteTop, emoteSizePx, canvas)
                    cursorX += emoteSizePx + iconGapPx
                }
            }
        }
    }

    private fun inlineIconGapPx(iconSizePx: Float): Float = (iconSizePx * 0.14f).coerceAtLeast(density * 2f)

    private val density: Float = appContext.resources.displayMetrics.density.takeIf { it.isFinite() && it > 0f } ?: 1f

    private fun drawInlineLikeIcon(
        left: Float,
        top: Float,
        sizePx: Float,
        canvas: Canvas,
    ) {
        val icon = inlineLikeIcon ?: return
        val right = (left + sizePx).roundToInt()
        val bottom = (top + sizePx).roundToInt()
        icon.setBounds(left.roundToInt(), top.roundToInt(), right, bottom)
        icon.draw(canvas)
    }

    /** 清空共享表，释放每项表持有的引用（item 引用不受影响）。 */
    private fun clearSharedCacheStore() {
        if (sharedCacheStore.isEmpty()) return
        val entries = sharedCacheStore.values.toList()
        sharedCacheStore.clear()
        sharedTableSize.set(0)
        for (entry in entries) {
            if (entry.release()) {
                recycleBitmap(entry.bitmap)
            }
        }
    }

    /**
     * 共享表硬上限驱逐（cache 线程，buildCache 插入后调用）。
     *
     * 为什么不用 removeEldestEntry 回调：真机实测该回调驱动的淘汰未生效
     * （表一度涨到 1600+ 条、recycled=0、36MB 预算被撑爆 → 建图全部失败 →
     * 弹幕一批批变稀）。这里在插入后按访问序主动驱逐，把上限变成硬约束；
     * 被驱逐条目若仍有 item/快照 lease 持有，bitmap 等最后一个引用释放时才回收。
     */
    private fun enforceSharedCacheBound() {
        var evicted = 0
        while (sharedCacheStore.size > MAX_SHARED_CACHE) {
            val eldestIt = sharedCacheStore.entries.iterator()
            if (!eldestIt.hasNext()) break
            val eldest = eldestIt.next()
            eldestIt.remove()
            sharedTableSize.decrementAndGet()
            evicted++
            if (eldest.value.release()) {
                recycleBitmap(eldest.value.bitmap)
            }
        }
        if (evicted > 0 && buildCounter++ % 32 == 0) {
            AppLog.w(
                TAG,
                "shared cache evict batch=$evicted size=${sharedCacheStore.size} limit=$MAX_SHARED_CACHE"
            )
        }
    }

    private fun releaseAllPendingEntries() {
        // 仅在 MSG_RELEASE（cache 线程）执行：此刻 action 线程已停（队列为空 + quitSafely）、
        // 主线程 draw 已停（view detach → released 早退），本方法独占消费环形队列。
        while (true) {
            val pending = releaseOverflowQueue.poll() ?: break
            val entry = pending.entry
            if (!entry.isRecycled && entry.release()) recycleBitmap(entry.bitmap)
        }
        releaseRing.drainAll { entry ->
            if (!entry.isRecycled && entry.release()) recycleBitmap(entry.bitmap)
        }
    }

    private fun createBitmapWithinBudget(width: Int, height: Int): Bitmap? {
        val estimateBytes = estimatedArgb8888Bytes(width, height)
        if (!ensureBitmapCapacity(estimateBytes)) return null
        val reservation = Any()
        if (!bitmapBudget.trySetBytes(reservation, estimateBytes)) return null
        val bitmap = runCatching { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) }.getOrNull()
            ?: run {
                bitmapBudget.release(reservation)
                return null
            }
        val actualBytes = bitmap.allocationByteCount.toLong().coerceAtLeast(0L)
        if (actualBytes <= 0L || !bitmapBudget.replaceOwner(reservation, bitmap, actualBytes)) {
            bitmapBudget.release(reservation)
            runCatching { bitmap.recycle() }
            return null
        }
        return bitmap
    }

    private fun ensureBitmapCapacity(requiredBytes: Long): Boolean {
        return reclaimUntilBitmapBudgetFits(
            budget = bitmapBudget,
            requiredBytes = requiredBytes,
            evictPooled = {
                val pooled = pool.evictOne() ?: return@reclaimUntilBitmapBudgetFits false
                recycleBitmap(pooled)
                true
            },
            evictShared = {
                val iterator = sharedCacheStore.entries.iterator()
                if (!iterator.hasNext()) return@reclaimUntilBitmapBudgetFits false
                val eldest = iterator.next().value
                iterator.remove()
                if (eldest.release()) recycleBitmap(eldest.bitmap)
                true
            },
        )
    }

    private fun recycleBitmap(bitmap: Bitmap) {
        bitmapBudget.release(bitmap)
        runCatching { if (!bitmap.isRecycled) bitmap.recycle() }
    }

    /**
     * 共享缓存内容指纹（FNV-1a 块混，与 akdanmaku sharedCacheKey 同算法）。
     * 必须包含所有影响 bitmap 渲染输出的字段，漏一个会出现"不同弹幕共享同一 bitmap"的视觉错乱。
     */
    private fun sharedCacheKey(
        text: String,
        color: Int,
        textSizePx: Float,
        typefaceOrdinal: Int,
        strokeWidthPx: Float,
        outlinePadPx: Float,
        vipGradient: Boolean,
        vipFillTextureUrl: String,
        vipStrokeTextureUrl: String,
        vipFillTextureLoaded: Boolean,
        vipStrokeTextureLoaded: Boolean,
        emotePlaceholder: Boolean,
    ): Long {
        var acc = FNV_OFFSET
        acc = mix(acc, text.hashCode().toLong())
        acc = mix(acc, text.length.toLong())
        acc = mix(acc, color.toLong())
        acc = mix(acc, textSizePx.toBits().toLong())
        acc = mix(acc, typefaceOrdinal.toLong())
        acc = mix(acc, strokeWidthPx.toBits().toLong())
        acc = mix(acc, outlinePadPx.toBits().toLong())
        acc = mix(acc, if (emotePlaceholder) 1L else 0L)
        acc = mix(acc, if (vipGradient) 1L else 0L)
        if (vipGradient) {
            acc = mix(acc, vipFillTextureUrl.hashCode().toLong())
            acc = mix(acc, vipStrokeTextureUrl.hashCode().toLong())
            acc = mix(acc, if (vipFillTextureLoaded) 1L else 0L)
            acc = mix(acc, if (vipStrokeTextureLoaded) 1L else 0L)
        }
        return acc
    }

    private fun mix(acc: Long, value: Long): Long = (acc xor value) * FNV_PRIME

    private data class CacheRequest(
        val item: DanmakuItem,
        val textWidthPx: Float,
        val style: CacheStyle,
    )

    private data class PendingRelease(
        val entry: SharedCacheEntry,
        val releaseAtFrameId: Int,
    )

    /**
     * 单生产者（action 线程 add）单消费者（主线程 poll / release 时 cache 线程 drainAll）
     * 的有界环形数组队列。稳态零分配：替代 ConcurrentLinkedQueue 每条释放的节点分配。
     *
     * 内存序：生产者先写槽位再 volatile 发布 tail（AtomicInteger.set），
     * 消费者读 tail 后读槽位，借助 volatile 的 happens-before 保证内容可见。
     * capacity 必须为 2 的幂（位掩码取模）。
     */
    private class PendingReleaseRing(private val capacity: Int) {
        private val mask: Int = capacity - 1
        private val entries = arrayOfNulls<SharedCacheEntry?>(capacity)
        private val frameIds = IntArray(capacity)
        private val head = AtomicInteger(0)
        private val tail = AtomicInteger(0)

        init {
            require(capacity > 0 && (capacity and mask) == 0) { "capacity must be a power of two: $capacity" }
        }

        fun add(entry: SharedCacheEntry, releaseAtFrameId: Int): Boolean {
            val currentTail = tail.get()
            if (currentTail - head.get() >= capacity) return false
            val slot = currentTail and mask
            entries[slot] = entry
            frameIds[slot] = releaseAtFrameId
            tail.set(currentTail + 1)
            return true
        }

        fun isEmpty(): Boolean = head.get() == tail.get()

        fun peekReleaseAtFrameId(): Int {
            val currentHead = head.get()
            if (currentHead == tail.get()) return Int.MIN_VALUE
            return frameIds[currentHead and mask]
        }

        fun pollEntry(): SharedCacheEntry? {
            val currentHead = head.get()
            if (currentHead == tail.get()) return null
            val slot = currentHead and mask
            val entry = entries[slot] ?: return null
            entries[slot] = null
            head.set(currentHead + 1)
            return entry
        }

        fun drainAll(consumer: (SharedCacheEntry) -> Unit) {
            while (true) {
                val entry = pollEntry() ?: return
                consumer(entry)
            }
        }
    }

    private class BitmapPool(
        private val maxBytes: Long,
        private val maxCount: Int,
        private val onEvict: (Bitmap) -> Unit,
    ) {
        private val pool = ArrayDeque<Bitmap>()
        private var pooledBytes: Long = 0L

        @Synchronized
        fun acquire(minWidth: Int, minHeight: Int): Bitmap? {
            if (pool.isEmpty()) return null
            val it = pool.iterator()
            while (it.hasNext()) {
                val b = it.next()
                if (b.isRecycled) {
                    it.remove()
                    continue
                }
                if (!isReusable(b, minWidth, minHeight)) continue
                it.remove()
                pooledBytes -= b.allocationByteCount.toLong().coerceAtLeast(0L)
                return b
            }
            return null
        }

        @Synchronized
        fun tryPut(bitmap: Bitmap): Boolean {
            if (bitmap.isRecycled) return true
            val bytes = bitmap.allocationByteCount.toLong().coerceAtLeast(0L)
            if (bytes <= 0L) return false
            if (bytes > maxBytes) return false
            if (pool.size >= maxCount) return false
            if (pooledBytes + bytes > maxBytes) return false
            pool.addLast(bitmap)
            pooledBytes += bytes
            return true
        }

        @Synchronized
        fun clear() {
            val it = pool.iterator()
            while (it.hasNext()) {
                val b = it.next()
                it.remove()
                onEvict(b)
            }
            pooledBytes = 0L
        }

        @Synchronized
        fun evictOne(): Bitmap? {
            if (pool.isEmpty()) return null
            var largest: Bitmap? = null
            var largestBytes = Long.MIN_VALUE
            for (bitmap in pool) {
                val bytes = bitmap.allocationByteCount.toLong().coerceAtLeast(0L)
                if (bytes > largestBytes) {
                    largest = bitmap
                    largestBytes = bytes
                }
            }
            val bitmap = largest ?: return null
            pool.remove(bitmap)
            pooledBytes = (pooledBytes - largestBytes.coerceAtLeast(0L)).coerceAtLeast(0L)
            return bitmap
        }

        @Synchronized
        fun snapshot(): PoolSnapshot =
            PoolSnapshot(
                count = pool.size,
                bytes = pooledBytes,
                maxBytes = maxBytes,
            )

        private fun isReusable(bitmap: Bitmap, minWidth: Int, minHeight: Int): Boolean {
            if (bitmap.config != Bitmap.Config.ARGB_8888) return false
            if (bitmap.width < minWidth || bitmap.height < minHeight) return false
            val dw = bitmap.width - minWidth
            val dh = bitmap.height - minHeight
            return dw <= 48 && dh <= 24
        }
    }

    internal data class PoolSnapshot(
        val count: Int,
        val bytes: Long,
        val maxBytes: Long,
    )

    internal data class StatsSnapshot(
        val bitmapCreated: Long,
        val bitmapReused: Long,
        val bitmapPutToPool: Long,
        val bitmapRecycled: Long,
        val sharedHit: Long = 0L,
        val bitmapBytes: Long = 0L,
        val bitmapMaxBytes: Long = 0L,
        val bitmapCount: Int = 0,
        val sharedTableSize: Int = 0,
    )
}
