package com.tutu.myblbl.feature.player.danmaku

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.DisplayMetrics
import android.util.TypedValue
import android.content.Context
import com.tutu.myblbl.core.common.log.AppLog
import com.tutu.myblbl.feature.player.danmaku.common.BiliDanmakuStyle
import com.tutu.myblbl.feature.player.danmaku.common.DanmakuInlineParser
import com.tutu.myblbl.feature.player.danmaku.emote.DanmakuEmoteRepository
import com.tutu.myblbl.feature.player.danmaku.emote.EmoteBitmapLoader
import com.tutu.myblbl.feature.player.danmaku.model.DanmakuCacheState
import com.tutu.myblbl.feature.player.danmaku.model.DanmakuInlineSegment
import com.tutu.myblbl.feature.player.danmaku.model.DanmakuItem
import com.tutu.myblbl.feature.player.danmaku.model.DanmakuKind
import com.tutu.myblbl.feature.player.danmaku.model.RenderSnapshot
import com.tutu.myblbl.feature.player.danmaku.model.RenderSnapshotStats
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val DANMAKU_FONT_BORDER_DEFAULT = 0

internal interface DanmakuEngineMainApi {
    fun lastDrawCachedCount(): Int

    fun lastDrawCacheMissSkippedCount(): Int

    fun stepTime(positionMs: Long, uiFrameId: Int)

    /** 主线程专用：为合法回退（seek 回看）放行单调高水位。必须与 stepTime 同线程调用。 */
    fun allowClockBackwardTo(positionMs: Long)

    fun drainReleasedBitmaps(uiFrameId: Int)

    fun acquireRenderSnapshot(): RenderSnapshot

    fun releaseRenderSnapshot(snapshot: RenderSnapshot)

    fun renderSnapshotStats(): RenderSnapshotStats

    fun draw(canvas: Canvas, snapshot: RenderSnapshot, config: DanmakuConfig)
}

/**
 * 缓存结果接受条件：样式代际一致 + 条目上确实有本次在途请求（pending/rendering 匹配）。
 *
 * 注意：不再要求条目"当前在场"。时间线预取会对未入场条目提前建图；而已退场/被丢弃的
 * 条目在所有移除路径上都会把 pendingCacheGeneration 重置为 -1（releaseItemCache /
 * releasePrefetchedCache），因此 pending 匹配本身就排除了已离场条目——防"退场条目
 * 错误复活"的纪律从"查 active 列表"（O(n) 扫描）转为"移除即重置"（O(1)）。
 */
internal fun shouldApplyBlblCacheResult(
    resultGeneration: Int,
    currentGeneration: Int,
    pendingGeneration: Int,
    rendering: Boolean,
): Boolean = resultGeneration == currentGeneration &&
    pendingGeneration == resultGeneration &&
    rendering

internal fun cacheReadyStartTime(motionStarted: Boolean, currentStartTimeMs: Int, nowMs: Int): Int =
    if (motionStarted) currentStartTimeMs else nowMs

internal fun isCacheWaitExpired(motionStarted: Boolean, admittedAtMs: Int, nowMs: Int, timeoutMs: Int): Boolean =
    !motionStarted && nowMs - admittedAtMs >= timeoutMs

internal fun adjustedTimelineIndexAfterPrefixTrim(index: Int, droppedCount: Int): Int =
    (index - droppedCount).coerceAtLeast(0)

/** Whether the player needs vsync animation now, or can sleep until a timeline event. */
internal data class DanmakuFrameSchedule(
    val animate: Boolean,
    val nextWakeAtMs: Int?,
)

internal fun writeDanmakuRenderOrder(
    active: List<DanmakuItem>,
    snapshot: RenderSnapshot,
) {
    snapshot.ensureCapacity(active.size)
    var count = 0
    for (item in active) {
        if (item.kind != DanmakuKind.SCROLL) continue
        snapshot.items[count] = item
        snapshot.yTop[count] = item.layoutTopPx
        snapshot.textWidth[count] = item.textWidthPx
        count++
    }
    for (item in active) {
        if (item.kind == DanmakuKind.SCROLL) continue
        snapshot.items[count] = item
        snapshot.yTop[count] = item.layoutTopPx
        snapshot.textWidth[count] = item.textWidthPx
        count++
    }
    snapshot.count = count
}

internal interface DanmakuEngineActionApi {
    fun updateViewport(width: Int, height: Int, topInsetPx: Int, bottomInsetPx: Int)

    fun updateConfig(newConfig: DanmakuConfig)

    fun stepTime(positionMs: Long, uiFrameId: Int)

    fun currentPositionMs(): Long

    fun drainReleasedBitmaps(uiFrameId: Int)

    fun applyCacheResult(result: CacheBuildResult)

    fun preAct()

    fun act()

    fun frameSchedule(): DanmakuFrameSchedule

    fun setDanmakus(list: List<Danmaku>)

    fun appendDanmakus(list: List<Danmaku>, alreadySorted: Boolean)

    /** Replaces only the future suffix of the timeline without resetting active items. */
    fun replaceDanmakusFrom(minTimeMs: Long, list: List<Danmaku>)

    fun trimToMax(maxItems: Int)

    fun trimToTimeRange(minTimeMs: Long, maxTimeMs: Long)

    fun seekTo(positionMs: Long)

    fun clear()

    fun release()
}

/**
 * Danmaku engine:
 * - Action thread owns timeline admission and lane selection.
 * - Main thread only consumes render snapshot and computes current scroll positions.
 */
internal class DanmakuEngine(
    private val appContext: Context,
    private val displayMetrics: DisplayMetrics,
    private val cacheManager: CacheManager,
) : DanmakuEngineMainApi, DanmakuEngineActionApi {
    private val density: Float = displayMetrics.density.takeIf { it.isFinite() && it > 0f } ?: 1f

    /** TV 盒子 GPU 合成能力弱，同屏上限自动档比手机更紧。 */
    private val isTvDevice: Boolean =
        (appContext.getSystemService(Context.UI_MODE_SERVICE) as? android.app.UiModeManager)
            ?.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION

    /** 主屏刷新率（自适应上限的判定基准），取不到时按 60Hz。 */
    private val displayRefreshHz: Float =
        runCatching {
            (appContext.getSystemService(Context.DISPLAY_SERVICE) as? android.hardware.display.DisplayManager)
                ?.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.refreshRate
        }.getOrNull()?.takeIf { it >= 30f } ?: 60f

    /**
     * 自适应同屏上限：下限 [ADAPTIVE_FLOOR]，按渲染帧率自动增减，硬顶 [ADAPTIVE_CEILING]。
     * 用户显式配置 DanmakuConfig.maxOnScreen > 0 时固定不走自适应。
     */
    private val adaptiveOnScreenLimit = AdaptiveOnScreenLimit(
        seed = if (isTvDevice) DEFAULT_TV_MAX_ON_SCREEN else DEFAULT_MAX_ON_SCREEN,
        floor = ADAPTIVE_FLOOR,
        ceiling = ADAPTIVE_CEILING,
        refreshHz = displayRefreshHz,
    )

    private fun maxOnScreenLimit(cfg: DanmakuConfig): Int =
        when {
            cfg.maxOnScreen > 0 -> cfg.maxOnScreen
            else -> adaptiveOnScreenLimit.limit
        }

    // ---- Data ----
    private val actionStateLock = Any()
    private var items: MutableList<DanmakuItem> = mutableListOf()
    private var index: Int = 0
    private val active: ArrayList<DanmakuItem> = ArrayList(64)
    private val pending: ArrayDeque<PendingSpawn> = ArrayDeque()
    // 最近一个重建窗口内已实际入场的条目。全量替换/乱序重排会生成新 DanmakuItem，
    // 不能只依赖 active 或新实例上的 consumed 标记来防止已离屏条目再次入场。
    private val admissionHistory = DanmakuAdmissionHistory()
    private var lastAdmissionHistoryPruneBeforeMs: Int = 0

    // Monotonic time within a session (action thread).
    private var lastNowMs: Int = 0

    // ---- Viewport / Config (action thread writes; main reads) ----
    @Volatile private var viewportWidth: Int = 0
    @Volatile private var viewportHeight: Int = 0
    @Volatile private var viewportTopInsetPx: Int = 0
    @Volatile private var viewportBottomInsetPx: Int = 0

    @Volatile
    private var config: DanmakuConfig =
        DanmakuConfig(
            enabled = true,
            opacity = BiliDanmakuStyle.DEFAULT_ALPHA_FACTOR,
            textSizeSp = 18f,
            fontWeight = DanmakuFontWeight.Bold,
            strokeWidthPx = BiliDanmakuStyle.strokeWidthForCache(
                textSizePx = sp(18f),
                fontBorder = DANMAKU_FONT_BORDER_DEFAULT
            ),
            speedLevel = 4,
            area = 1f,
            laneDensity = DanmakuLaneDensity.Standard,
        )

    @Volatile private var textSizePx: Float = sp(18f)
    @Volatile private var strokeWidthPx: Float = BiliDanmakuStyle.strokeWidthForCache(
        textSizePx = sp(18f),
        fontBorder = DANMAKU_FONT_BORDER_DEFAULT
    ).toFloat()
    @Volatile private var outlinePadPx: Float = 2f

    @Volatile private var cacheStyleGeneration: Int = 0
    @Volatile private var measureGeneration: Int = 0
    /** 表情词典版本快照：词典加载/刷新后递增 measureGeneration 使宽度缓存全部失效。 */
    @Volatile private var engineEmoteVersion: Int = 0
    @Volatile private var debugPendingCount: Int = 0
    @Volatile private var debugNextAtMs: Int? = null

    @Volatile private var lastDrawCachedCount: Int = 0
    @Volatile private var lastDrawCacheMissSkippedCount: Int = 0

    override fun lastDrawCachedCount(): Int = lastDrawCachedCount

    override fun lastDrawCacheMissSkippedCount(): Int = lastDrawCacheMissSkippedCount

    // ---- Time (main writes; action reads) ----
    @Volatile private var currentPositionMs: Long = 0L
    @Volatile private var currentUiFrameId: Int = 0

    // 时钟单调高水位（主线程 stepTime 维护）：位置回退（平滑时钟硬校准/raw 抖动）时
    // 拒绝回退值。此前 act() 用 clamp 后的时钟判退场、draw() 用未 clamp 的原始位置算 x，
    // 时钟回退后两轨分叉 → 条目按"超前时钟"被 prune 时观众看到的位置还在屏中（半路消失）、
    // 或整屏位置倒跳重滚（"同一条弹幕再滚一遍"）。钳制上移到 stepTime 后两轨读同一份数据，
    // 分叉类消失不再可能。合法回退（用户 seek 回看）由 [allowClockBackwardTo] 在主线程放行，
    // 必须与 stepTime 同线程以避免跨线程重置/写入交错把高水位卡在旧位置。
    @Volatile private var monotonicClockMs: Long = 0L

    // ---- Render snapshot (double buffer) ----
    private val snapshots = Array(3) { RenderSnapshot() }
    @Volatile private var latestSnapshot: RenderSnapshot = snapshots[0]
    private var snapshotDirty: Boolean = true
    private var rebuildRequested: Boolean = true
    // rebuildScene 触发来源（配合诊断日志定位"弹幕重复入场/半路消失"根因）。
    private var rebuildReason: String? = "init"

    /** 统一标记需要 rebuild，并记录来源，便于日志区分是 viewport/config/数据/几何哪类变化触发。 */
    private fun requestRebuild(reason: String) {
        rebuildRequested = true
        rebuildReason = reason
    }

    // ---- Layout state (action thread only) ----
    private val actionFontMetrics = Paint.FontMetrics()
    private val actionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
    // actionPaint 度量是否已算（字号/描边变化后置 false，强制下帧重算）。
    @Volatile private var actionMetricsValid = false
    private var configuredLaneCount: Int = 0
    private var configuredLaneHeightPx: Float = 0f
    private var configuredTopInsetPx: Int = 0
    private var configuredUsableHeightPx: Int = 0
    private var configuredMaxYTopPx: Float = 0f
    private var laneLastScroll: Array<DanmakuItem?> = emptyArray()
    private var laneLastTop: Array<DanmakuItem?> = emptyArray()
    private var laneLastBottom: Array<DanmakuItem?> = emptyArray()

    // ---- Cache scheduling (action thread only) ----
    // 入场未缓存 FIFO：activate() 时入队，poll 时按 inActive/缓存有效性懒校验。
    // 替代旧的轮转游标扫描（active>16 时发现一个未缓存条目最坏要等 ceil(n/16) 帧）。
    private val uncachedActive = ArrayDeque<DanmakuItem>()
    // 时间线预取游标：只前进不后退（rebuild/替换时重置），位于 [index, items.size]。
    // 预取把建图提前到入场前完成，消除 miss-跳帧与 cacheWaitTimeout 丢字。
    private var prefetchCursor: Int = 0
    // CacheStyle 复用：样式代际/字号/描边/边距未变时不重复分配（此前 act 每帧 new 一个）。
    private var cachedStyle: CacheStyle? = null
    private var cachedStyleGeneration: Int = -1
    private var cachedStyleOutlinePadPx: Float = -1f

    // admit 日志采样计数（action 线程私有）。
    private var admitLogCounter: Int = 0

    // 满员丢弃计数与限频日志打点（action 线程私有）：满员即弃是"弹幕变稀"的直接上游证据。
    private var capDropTotal: Int = 0
    private var lastCapDropLogMs: Int = 0

    // ---- Draw (main thread only) ----
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // 对齐 akdanmaku drawPaint：关闭 isFilterBitmap，避免 drawBitmap 双线性滤波糊化烘焙好的
        // 锐利文字边缘（边缘被混合稀释 → 颜色发浅/不饱满）。弹幕 bitmap 按整数像素 1:1 绘制，无需滤波。
    }

    override fun updateViewport(width: Int, height: Int, topInsetPx: Int, bottomInsetPx: Int) {
        viewportWidth = width.coerceAtLeast(0)
        viewportHeight = height.coerceAtLeast(0)
        viewportTopInsetPx = topInsetPx.coerceAtLeast(0)
        viewportBottomInsetPx = bottomInsetPx.coerceAtLeast(0)
        requestRebuild("viewport")
    }

    override fun updateConfig(newConfig: DanmakuConfig) {
        synchronized(actionStateLock) {
            config = newConfig
            val tsPx = sp(newConfig.textSizeSp).coerceAtLeast(1f)
            val newStrokeWidthPx = newConfig.strokeWidthPx.coerceAtLeast(0).toFloat()
            val newTypeface = newConfig.fontWeight.typeface

            val oldTs = textSizePx
            val oldStrokeWidthPx = strokeWidthPx
            val oldTypeface = actionPaint.typeface
            val oldShowHighLikeIcon = config.showHighLikeIcon

            textSizePx = tsPx
            strokeWidthPx = newStrokeWidthPx
            outlinePadPx = max(1f, newStrokeWidthPx / 2f)

            actionPaint.textSize = tsPx
            if (actionPaint.typeface != newTypeface) actionPaint.typeface = newTypeface

            val styleChanged = oldTs != tsPx || oldStrokeWidthPx != newStrokeWidthPx ||
                oldTypeface != newTypeface || oldShowHighLikeIcon != newConfig.showHighLikeIcon
            if (styleChanged) {
                cacheStyleGeneration++
                measureGeneration++
                // 样式代际变化后 actionPaint 度量需重算（act 帧循环里按此标志判断）。
                actionMetricsValid = false
                if (AppLog.isEnabled) {
                    AppLog.w(
                        TAG,
                        "styleChanged gen=${cacheStyleGeneration} → all ${active.size} active caches invalidated " +
                            "(rebuild throttled 8/frame, expect temporary draw miss)"
                    )
                }
                // Invalidate current caches to avoid mixing styles.
                val releaseAt = currentUiFrameId + 1
                val size = active.size
                for (i in 0 until size) {
                    val a = active[i]
                    val entry = a.cacheEntry
                    if (entry != null) {
                        cacheManager.enqueueRelease(entry, releaseAtFrameId = releaseAt)
                        a.cacheEntry = null
                    }
                    a.cacheState = DanmakuCacheState.Init
                    a.cacheGeneration = -1
                    a.pendingCacheGeneration = -1
                    // 条目仍 active：重新排队等建图（poll 时按新代际校验）。
                    uncachedActive.addLast(a)
                }
                releaseStalePrefetchCaches(releaseAt)
            }
            requestRebuild("config")
        }
    }

    private fun sp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, displayMetrics)

    override fun stepTime(positionMs: Long, uiFrameId: Int) {
        val pos = positionMs.coerceAtLeast(0L)
        val floor = monotonicClockMs
        // 单调钳制：只允许前进。回退（漂移硬校准等）被吸收为"冻结等 raw 追上"，
        // 而不是位置倒跳。合法回退（用户 seek 回看）走 allowClockBackwardTo 放行。
        val effective = if (pos >= floor) pos else floor
        monotonicClockMs = effective
        currentPositionMs = effective
        currentUiFrameId = uiFrameId
    }

    override fun allowClockBackwardTo(positionMs: Long) {
        monotonicClockMs = positionMs.coerceAtLeast(0L)
    }

    override fun currentPositionMs(): Long = currentPositionMs

    override fun drainReleasedBitmaps(uiFrameId: Int) {
        cacheManager.drainReleasedBitmaps(uiFrameId)
    }

    override fun applyCacheResult(result: CacheBuildResult) {
        val item = result.item
        if (!shouldApplyBlblCacheResult(
                resultGeneration = result.generation,
                currentGeneration = cacheStyleGeneration,
                pendingGeneration = item.pendingCacheGeneration,
                rendering = item.cacheState == DanmakuCacheState.Rendering,
            )) {
            if (item.pendingCacheGeneration == result.generation) {
                item.cacheState = DanmakuCacheState.Init
                item.pendingCacheGeneration = -1
                // 在场条目的在途请求被拒（多为样式代际刚切换）：重新排队等新代际建图。
                if (item.inActive) uncachedActive.addLast(item)
            }
            // 结果被拒（generation过期/条目已被移除），缓存白建了。
            if (AppLog.isEnabled) {
                AppLog.w(
                    TAG,
                    "cacheResult REJECT t=${item.timeMs()}ms text='${item.data.text.take(12)}' " +
                        "gen=${result.generation}/${cacheStyleGeneration} inActive=${item.inActive} " +
                        "state=${item.cacheState} pending=${item.pendingCacheGeneration}"
                )
            }
            return
        }
        val entry = result.entry
        if (entry == null || !entry.tryAcquire()) {
            item.cacheState = DanmakuCacheState.Init
            item.pendingCacheGeneration = -1
            // 在场条目建图失败（内存不足/位图被回收）：重新排队重试，避免直接掉进超时丢弃。
            // 加退避：预算耗尽时每帧重试只会反复失败（实测刷数百条 FAIL 日志），
            // 退避让位图池有时间被释放路径回填。
            item.cacheRetryNotBeforeMs =
                currentPositionMs.coerceAtMost((Int.MAX_VALUE - CACHE_FAIL_RETRY_BACKOFF_MS).toLong()).toInt() +
                    CACHE_FAIL_RETRY_BACKOFF_MS
            if (item.inActive) uncachedActive.addLast(item)
            // entry 为空=内存不足建图失败；tryAcquire 失败=bitmap已被回收。
            if (AppLog.isEnabled) {
                AppLog.w(
                    TAG,
                    "cacheResult FAIL t=${item.timeMs()}ms text='${item.data.text.take(12)}' " +
                        "entryNull=${entry == null} recycled=${entry?.isRecycled}"
                )
            }
            return
        }
        val old = item.cacheEntry
        if (item.inActive && !item.motionStarted) {
            val prevStart = item.startTimeMs
            item.startTimeMs = cacheReadyStartTime(
                motionStarted = false,
                currentStartTimeMs = item.startTimeMs,
                nowMs = currentPositionMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            )
            item.motionStarted = true
            // 缓存就绪，startTimeMs 重锚到当前播放位置。admit→ready 的等待时长若接近 MAX_CACHE_WAIT_MS，
            // 说明缓存构建接近超时边缘，下一步可能被 pruneExpired 误杀。
            if (AppLog.isEnabled) {
                AppLog.w(
                    TAG,
                    "cacheReady t=${item.timeMs()}ms text='${item.data.text.take(12)}' " +
                        "admit=${prevStart}ms→start=${item.startTimeMs}ms waited=${item.startTimeMs - prevStart}ms lane=${item.lane}"
                )
            }
        }
        // 预取条目（未入场）只挂图；motion 留到 activate() 时按缓存命中判定，
        // 入场即有图 → motionStarted=true → 从入场时刻准时开始运动。
        if (old === entry) {
            entry.release()
            item.cacheGeneration = result.generation
            item.pendingCacheGeneration = -1
            item.cacheState = DanmakuCacheState.Rendered
            return
        }
        item.cacheEntry = entry
        item.cacheGeneration = result.generation
        item.pendingCacheGeneration = -1
        item.cacheState = DanmakuCacheState.Rendered
        if (old != null && old !== entry) {
            cacheManager.enqueueRelease(old, releaseAtFrameId = currentUiFrameId + 1)
        }
        if (item.inActive) snapshotDirty = true
    }

    override fun preAct() {
        // No-op: action thread owns state, draw thread only consumes snapshots.
    }

    override fun act() {
        synchronized(actionStateLock) {
            val cfg = config
            if (!cfg.enabled) {
                clearActives()
                pending.clear()
                resetLaneState()
                debugPendingCount = 0
                debugNextAtMs = null
                publishEmptySnapshot()
                return
            }

            val width = viewportWidth
            val height = viewportHeight
            if (width <= 0 || height <= 0) {
                // viewport 短暂变 0（View 尺寸变化/窗口动画）会瞬间清空整屏弹幕——
                // 这是"半路消失"的无日志路径，必须记录。
                if (active.isNotEmpty() && AppLog.isEnabled) {
                    AppLog.w(
                        TAG,
                        "CLEAR-ALL viewport=${width}x${height} active=${active.size} pending=${pending.size} → screen emptied"
                    )
                }
                clearActives()
                pending.clear()
                resetLaneState()
                debugPendingCount = 0
                debugNextAtMs = null
                publishEmptySnapshot()
                return
            }

            // 表情词典版本变化（首载/24h 刷新）：表情 token 命中状态改变 → 宽度与
            // 内联段解析结果全部失效重算（分段解析结果本身还带 item 级版本对比双保险）。
            val emoteVersion = DanmakuEmoteRepository.version()
            if (emoteVersion != engineEmoteVersion) {
                engineEmoteVersion = emoteVersion
                measureGeneration++
            }

            val outlinePad = outlinePadPx
            val rawNowMs = currentPositionMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            // 位置单调化：小幅回退（播放器上报抖动）钳制不回退，保证滚动不倒退；
            // 大幅回退（超过 REPLAY_BACK_THRESHOLD_MS，如起播时续播位置残留被真实位置覆盖）
            // 必须跟随，否则引擎时间被锁死在旧位置——滚动弹幕全部定位在右边缘外不可见、
            // 顶部弹幕永不过期钉在屏幕上（"首次进入弹幕卡死"的根因）。
            val nowMs = if (rawNowMs >= lastNowMs || lastNowMs - rawNowMs > REPLAY_BACK_THRESHOLD_MS) {
                rawNowMs
            } else {
                lastNowMs
            }
            // 弹幕时钟有前进 ≈ 正在播放（暂停/缓冲时时钟冻结），供自适应上限判定用。
            val clockAdvanced = nowMs != lastNowMs
            lastNowMs = nowMs

            val topInset = viewportTopInsetPx.coerceIn(0, height)
            val bottomInset = viewportBottomInsetPx.coerceIn(0, height - topInset)
            val availableHeight = (height - topInset - bottomInset).coerceAtLeast(0)

            // actionPaint 度量只随字号/描边变化，缓存比对避免每帧 getFontMetrics。
            if (actionPaint.textSize != textSizePx || !actionMetricsValid) {
                actionPaint.textSize = textSizePx
                actionPaint.getFontMetrics(actionFontMetrics)
                actionMetricsValid = true
            }
            // 度量高度对齐 akdanmaku SimpleRenderer.getCacheHeight：descent - ascent + leading。
            // leading 对 CJK 字体通常为 0，但西文/混排时可能有值，补上保证行高基准与视觉档位一致。
            val textBoxHeight = (actionFontMetrics.descent - actionFontMetrics.ascent + actionFontMetrics.leading) + outlinePad * 2f
            // 统一行高倍率：laneHeight = textBoxHeight × factor，factor 来自 DanmakuTrackSpacing。
            // 与 akdanmaku（margin = itemHeight × (factor-1)）语义一致，保留原有视觉间距。
            // factor<1 时 laneHeight<textBoxHeight，吃掉 fontMetrics 度量留白使同屏容纳更多行；
            // 下限 0.65×textBoxHeight 保证相邻 lane 字形不重叠。
            val laneHeight = (textBoxHeight * cfg.trackSpacing.factor).coerceAtLeast(textBoxHeight * 0.65f)
            val usableHeight = (availableHeight * cfg.area.coerceIn(0f, 1f)).toInt().coerceAtLeast(0)
            val laneCount = max(1, (usableHeight / laneHeight).toInt())
            val maxYTop = (topInset + usableHeight - textBoxHeight).toFloat().coerceAtLeast(topInset.toFloat())

            val geometryChanged =
                configuredLaneCount != laneCount ||
                    configuredTopInsetPx != topInset ||
                    configuredUsableHeightPx != usableHeight ||
                    kotlin.math.abs(configuredLaneHeightPx - laneHeight) > 0.01f ||
                    kotlin.math.abs(configuredMaxYTopPx - maxYTop) > 0.01f

            if (geometryChanged) {
                configuredLaneCount = laneCount
                configuredLaneHeightPx = laneHeight
                configuredTopInsetPx = topInset
                configuredUsableHeightPx = usableHeight
                configuredMaxYTopPx = maxYTop
                requestRebuild("geometry")
            }

            val rollingDurationMs = computeRollingDurationMs(speedLevel = cfg.speedLevel)
            val fixedDurationMs = FIXED_DURATION_MS

            pruneAdmissionHistory(
                (nowMs - max(rollingDurationMs, fixedDurationMs)).coerceAtLeast(0),
            )

            if (rebuildRequested) {
                rebuildScene(
                    nowMs = nowMs,
                    width = width,
                    outlinePad = outlinePad,
                    rollingDurationMs = rollingDurationMs,
                    fixedDurationMs = fixedDurationMs,
                    laneCount = laneCount,
                    laneHeight = laneHeight,
                    topInset = topInset,
                    maxYTop = maxYTop,
                    reason = rebuildReason ?: "rebuildFlag",
                )
            } else {
                pruneExpired(width = width, nowMs = nowMs)
                processPendingItems(
                    nowMs = nowMs,
                    width = width,
                    outlinePad = outlinePad,
                    rollingDurationMs = rollingDurationMs,
                    fixedDurationMs = fixedDurationMs,
                    laneCount = laneCount,
                    laneHeight = laneHeight,
                    topInset = topInset,
                    maxYTop = maxYTop,
                )
                spawnNewItems(
                    nowMs = nowMs,
                    width = width,
                    outlinePad = outlinePad,
                    rollingDurationMs = rollingDurationMs,
                    fixedDurationMs = fixedDurationMs,
                    laneCount = laneCount,
                    laneHeight = laneHeight,
                    topInset = topInset,
                    maxYTop = maxYTop,
                )
            }

            requestCacheBuilds(outlinePad = outlinePad, cfg = cfg)
            debugPendingCount = pending.size
            debugNextAtMs = items.getOrNull(index)?.timeMs()
            publishSnapshotIfDirty(nowMs)

            // 自适应同屏上限：按渲染帧率窗口化增减（详见 AdaptiveOnScreenLimit）。
            val adjustedLimit = adaptiveOnScreenLimit.onFrame(
                wallNowMs = android.os.SystemClock.elapsedRealtime(),
                activeCount = active.size,
                clockAdvanced = clockAdvanced,
            )
            if (adjustedLimit != null) {
                AppLog.i(
                    TAG,
                    "adaptive on-screen limit -> $adjustedLimit " +
                        "fps=${"%.1f".format(adaptiveOnScreenLimit.lastWindowFps)} " +
                        "maxAct=${adaptiveOnScreenLimit.lastWindowMaxAct} refresh=${displayRefreshHz}Hz"
                )
            }
        }
    }

    override fun acquireRenderSnapshot(): RenderSnapshot {
        while (true) {
            val candidate = latestSnapshot
            if (!candidate.tryAcquireRead()) continue
            if (candidate === latestSnapshot) return candidate
            candidate.releaseRead()
        }
    }

    override fun releaseRenderSnapshot(snapshot: RenderSnapshot) {
        snapshot.releaseRead()
    }

    override fun renderSnapshotStats(): RenderSnapshotStats {
        val snapshot = acquireRenderSnapshot()
        return try {
            RenderSnapshotStats(
                positionMs = currentPositionMs,
                count = snapshot.count,
                pendingCount = snapshot.pendingCount,
                nextAtMs = snapshot.nextAtMs,
            )
        } finally {
            releaseRenderSnapshot(snapshot)
        }
    }

    // draw 缺失日志聚合（主线程私有）：按 500ms 聚合输出，避免 60fps 刷屏冲掉环形日志缓冲。
    private var drawMissLogLastAtMs: Long = 0L
    private var drawMissLogFrames: Int = 0
    private var drawMissLogSkipped: Int = 0
    private var drawMissLogWait: Int = 0
    private var drawMissLogNoEntry: Int = 0
    private var drawMissLogRecycled: Int = 0
    private var drawMissLogGenMismatch: Int = 0

    override fun draw(canvas: Canvas, snapshot: RenderSnapshot, config: DanmakuConfig) {
        val cfg = config
        if (!cfg.enabled) return

        val opacityAlpha = (cfg.opacity * 255f).roundToInt().coerceIn(0, 255)
        bitmapPaint.alpha = opacityAlpha
        val styleGen = cacheStyleGeneration
        val width = viewportWidth.coerceAtLeast(0)
        val nowMs = currentPositionMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

        var cachedDrawn = 0
        var cacheMissSkipped = 0
        // 缓存缺失分类统计：定位"半路消失"到底是无缓存、bitmap 被回收还是样式代际不匹配。
        var missNoEntry = 0
        var missRecycled = 0
        var missGenMismatch = 0
        var missMotionWait = 0
        for (i in 0 until snapshot.count) {
            val item = snapshot.items[i] ?: continue
            // x 坐标在主线程 draw 时现算（用 draw 当前的 nowMs），保证滚动位置与画面同步。
            // 此前曾在 act 线程预算填入 snapshot.x，但 act 线程的 nowMs 滞后于 draw，
            // 导致弹幕位置抖动/卡顿。对齐参考实现（blbl.cat3399）。
            val x =
                when (item.kind) {
                    DanmakuKind.SCROLL -> scrollX(width = width, nowMs = nowMs, startTimeMs = item.startTimeMs, pxPerMs = item.pxPerMs)
                    DanmakuKind.TOP -> centerX(width = width, contentWidth = item.textWidthPx)
                    DanmakuKind.BOTTOM -> centerX(width = width, contentWidth = item.textWidthPx)
                }
            val yTop = snapshot.yTop[i]
            // The action thread may replace item.cacheEntry while this snapshot is still
            // being drawn. Read the snapshot-owned lease instead of mutable item state.
            val entry = snapshot.cacheEntries[i]
            if (entry != null && !entry.isRecycled && snapshot.cacheGenerations[i] == styleGen) {
                // x 量化到 0.5px：部分 TV GPU 对任意浮点坐标的位图采样/合成走慢路径，
                // 0.5px 步进视觉不可感知（滚动速度 ~0.35px/ms，即每 ~1.4ms 移动一格）。
                val drawX = (x * 2f).roundToInt() * 0.5f
                canvas.drawBitmap(entry.bitmap, drawX, yTop, bitmapPaint)
                cachedDrawn++
                continue
            }
            // 性能优先模式不在主线程直接绘制文字。缓存未完成时跳过本帧，
            // Action 线程会继续提高可见条目的缓存优先级。
            cacheMissSkipped++
            when {
                entry == null && !item.motionStarted -> missMotionWait++
                entry == null -> missNoEntry++
                entry.isRecycled -> missRecycled++
                else -> missGenMismatch++
            }
        }
        lastDrawCachedCount = cachedDrawn
        lastDrawCacheMissSkippedCount = cacheMissSkipped
        // 仅在有缓存缺失时输出（正常帧不刷屏）。missing>0 说明弹幕在屏幕上但没画出来 → "半路消失"。
        // 门控 + 500ms 聚合：字符串构建本身有成本，日志关闭时不做任何额外工作；
        // 开启时按窗口汇总，避免 60fps 刷屏冲掉环形日志缓冲里的其他诊断。
        if (cacheMissSkipped > 0 && AppLog.isEnabled) {
            drawMissLogFrames++
            drawMissLogSkipped += cacheMissSkipped
            drawMissLogWait += missMotionWait
            drawMissLogNoEntry += missNoEntry
            drawMissLogRecycled += missRecycled
            drawMissLogGenMismatch += missGenMismatch
            val nowUptimeMs = android.os.SystemClock.uptimeMillis()
            if (nowUptimeMs - drawMissLogLastAtMs >= DRAW_MISS_LOG_INTERVAL_MS) {
                AppLog.w(
                    TAG,
                    "draw miss(agg ${DRAW_MISS_LOG_INTERVAL_MS}ms) frames=$drawMissLogFrames " +
                        "miss=$drawMissLogSkipped wait=$drawMissLogWait noEntry=$drawMissLogNoEntry " +
                        "recycled=$drawMissLogRecycled genMismatch=$drawMissLogGenMismatch " +
                        "gen=$styleGen now=${nowMs}ms"
                )
                drawMissLogLastAtMs = nowUptimeMs
                drawMissLogFrames = 0
                drawMissLogSkipped = 0
                drawMissLogWait = 0
                drawMissLogNoEntry = 0
                drawMissLogRecycled = 0
                drawMissLogGenMismatch = 0
            }
        }
    }

    override fun setDanmakus(list: List<Danmaku>) {
        synchronized(actionStateLock) {
            if (list.isEmpty()) clearAdmissionHistory("setDanmakus-empty")
            val newItems =
                list
                    .sortedBy { it.timeMs }
                    .mapTo(ArrayList(list.size.coerceAtLeast(0))) { DanmakuItem(it) }
            pending.clear()
            // 空时间线 = 停播/切后台清屏语义，必须整屏清空；仅非空替换且屏幕上有在播弹幕时走保留路径。
            if (list.isEmpty() || items.isEmpty() || active.isEmpty()) {
                clearActives()
                resetLaneState()
            } else {
                // 全量替换时间线但屏幕上有在播弹幕（appendData 合并冲突回退/设置重建时出现）：
                // 不整屏清空，把新时间线里与在场条目相同（同发送时间+同内容）的实例标记 consumed，
                // rebuildScene 会把它们跳过 → 在播弹幕保持运动状态与缓存，不再"清屏+近 6 秒重放"。
                markMatchedNewItemsConsumed(newItems)
            }
            // 整体替换：旧时间线中不在场的条目若被预取挂图，替换后永远不会入场，
            // 必须释放（在场条目实例相同，缓存由退场路径管理，跳过）。
            val releaseAt = currentUiFrameId + 1
            for (old in items) {
                if (!old.inActive) releaseItemCache(old, releaseAtFrameId = releaseAt)
            }
            items = newItems
            index = 0
            prefetchCursor = 0
            lastNowMs = 0
            requestRebuild("setDanmakus")
            debugPendingCount = 0
            debugNextAtMs = items.firstOrNull()?.timeMs()
            if (AppLog.isEnabled) {
                AppLog.w(TAG, "setDanmakus count=${items.size} onScreenPreserved=${active.size}")
            }
            if (active.isEmpty()) publishEmptySnapshot()
        }
    }

    /**
     * 把新时间线中与当前在场条目值相等（Danmaku data class 按 发送时间+内容+样式 判等）的实例
     * 标记 consumed。使用计数表处理同一条弹幕在时间线里出现多次的情况（逐个匹配，不重复占用）。
     *
     * 只对 [now - 最长滚动/固定时长, ∞) 窗口内的新条目建表：更早的条目不可能与在场条目值相等
     * （timeMs 是判等字段之一），也必被 rebuildScene 的 admitSinceMs 窗口跳过——
     * 全量时间线（可达 2 万条）逐条建 HashMap 的哈希/装箱成本由此省掉 95% 以上。
     */
    private fun markMatchedNewItemsConsumed(newItems: List<DanmakuItem>) {
        if (active.isEmpty()) return
        val nowMs = currentPositionMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val floorMs = (
            nowMs - maxOf(computeRollingDurationMs(config.speedLevel), FIXED_DURATION_MS)
            ).coerceAtLeast(0)
        val byData = HashMap<Danmaku, ArrayList<DanmakuItem>>(64)
        for (item in newItems) {
            if (item.timeMs() < floorMs) continue
            byData.getOrPut(item.data) { ArrayList(2) }.add(item)
        }
        for (a in active) {
            val candidates = byData[a.data] ?: continue
            if (candidates.isEmpty()) continue
            candidates.removeAt(candidates.lastIndex).consumed = true
        }
    }

    override fun appendDanmakus(list: List<Danmaku>, alreadySorted: Boolean) {
        synchronized(actionStateLock) {
        if (list.isEmpty()) return
        if (items.isEmpty()) {
            setDanmakus(list)
            return
        }
        val newItems =
            if (alreadySorted) {
                list
            } else {
                list.sortedBy { it.timeMs }
            }
        val lastTime = items.lastOrNull()?.timeMs() ?: Int.MIN_VALUE
        val firstIncoming = newItems.firstOrNull()?.timeMs ?: Int.MIN_VALUE
        if (firstIncoming >= lastTime) {
            for (d in newItems) items.add(DanmakuItem(d))
            debugNextAtMs = items.getOrNull(index)?.timeMs()
            if (AppLog.isEnabled) {
                AppLog.w(
                    TAG,
                    "append(ok) add=${newItems.size} firstIn=$firstIncoming lastExist=$lastTime items=${items.size}"
                )
            }
            return
        }
        // 时间倒序：新批次首条早于已注入末条（分段补全/seek回看时出现）。
        // 只改写当前滚动寿命之外的未来尾段，绝不能 sort 全量时间线再 seek，
        // 否则在场条目和最近已入场条目都会被重新投放。
        val rollingDurationMs = computeRollingDurationMs(config.speedLevel)
        val patchFrom = resolveOutOfOrderAppendPatchStartMs(
            firstIncomingTimeMs = firstIncoming,
            currentPositionMs = currentPositionMs,
            rollingDurationMs = rollingDurationMs,
        )
        val replaceIndex = lowerBound(patchFrom)
        val replacement = mergeDanmakuFutureTail(
            existing = items.subList(replaceIndex, items.size).map { it.data },
            incoming = newItems,
            minTimeMs = patchFrom,
        )
        if (AppLog.isEnabled) {
            AppLog.w(
                TAG,
                "append(out-of-order!) add=${newItems.size} firstIn=$firstIncoming lastExist=$lastTime " +
                    "items=${items.size} → patchFuture from=${patchFrom}ms replace=${replacement.size}"
            )
        }
        if (replaceIndex < items.size) {
            // 被替换的尾段里可能有预取挂图的未入场条目，清空前释放。
            val releaseAt = currentUiFrameId + 1
            for (i in replaceIndex until items.size) {
                val dropped = items[i]
                if (!dropped.inActive) releaseItemCache(dropped, releaseAtFrameId = releaseAt)
            }
            items.subList(replaceIndex, items.size).clear()
        }
        items.addAll(replacement.map(::DanmakuItem))
        index = minOf(index, replaceIndex)
        // 尾段被新实例替换：预取游标回退到替换点，让新实例重新走预取。
        prefetchCursor = minOf(prefetchCursor, replaceIndex).coerceIn(index, items.size)
        requestRebuild("append-out-of-order-tail-patch")
        debugNextAtMs = items.getOrNull(index)?.timeMs()
        }
    }

    override fun frameSchedule(): DanmakuFrameSchedule {
        // This method is called only from the action thread, immediately after act().
        if (active.isNotEmpty() || pending.isNotEmpty()) {
            return DanmakuFrameSchedule(animate = true, nextWakeAtMs = null)
        }
        return DanmakuFrameSchedule(
            animate = false,
            nextWakeAtMs = items.getOrNull(index)?.timeMs(),
        )
    }

    override fun replaceDanmakusFrom(minTimeMs: Long, list: List<Danmaku>) {
        synchronized(actionStateLock) {
            val min = minTimeMs.coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val replacement =
                list
                    .asSequence()
                    .filter { it.timeMs >= min }
                    .sortedBy { it.timeMs }
                    .map { DanmakuItem(it) }
                    .toList()
            val replaceIndex = lowerBound(min)
            markMatchedNewItemsConsumed(replacement)
            if (replaceIndex < items.size) {
                // 被替换的尾段里可能有预取挂图的未入场条目，清空前释放。
                val releaseAt = currentUiFrameId + 1
                for (i in replaceIndex until items.size) {
                    val dropped = items[i]
                    if (!dropped.inActive) releaseItemCache(dropped, releaseAtFrameId = releaseAt)
                }
                items.subList(replaceIndex, items.size).clear()
            }
            items.addAll(replacement)
            index = minOf(index, replaceIndex)
            // 尾段被新实例替换：预取游标回退到替换点，让新实例重新走预取。
            prefetchCursor = minOf(prefetchCursor, replaceIndex).coerceIn(index, items.size)
            requestRebuild("replace-from")
            debugNextAtMs = items.getOrNull(index)?.timeMs()
            if (AppLog.isEnabled) {
                AppLog.w(
                    TAG,
                    "replaceFrom min=${min}ms replace=${replacement.size} prefix=$replaceIndex items=${items.size} active=${active.size}",
                )
            }
        }
    }

    override fun trimToMax(maxItems: Int) {
        synchronized(actionStateLock) {
        if (maxItems <= 0) return
        val drop = items.size - maxItems
        if (drop <= 0) return
        // 前缀条目已播放过：不在场的若被预取挂图，删除前释放。
        val releaseAt = currentUiFrameId + 1
        for (i in 0 until drop) {
            val dropped = items[i]
            if (!dropped.inActive) releaseItemCache(dropped, releaseAtFrameId = releaseAt)
        }
        items.subList(0, drop).clear()
        index = adjustedTimelineIndexAfterPrefixTrim(index, drop)
        prefetchCursor = adjustedTimelineIndexAfterPrefixTrim(prefetchCursor, drop)
        debugNextAtMs = items.getOrNull(index)?.timeMs()
        }
    }

    override fun trimToTimeRange(minTimeMs: Long, maxTimeMs: Long) {
        synchronized(actionStateLock) {
        if (items.isEmpty()) return
        val min = minTimeMs.coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val max = maxTimeMs.coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        if (max <= min) return

        val start = lowerBound(min)
        val end = lowerBound(max)
        if (start <= 0 && end >= items.size) return
        if (start >= end) {
            // 区间整体清空：所有非在场条目的预取缓存一并释放（clearActives 处理在场条目）。
            val releaseAt = currentUiFrameId + 1
            for (dropped in items) {
                if (!dropped.inActive) releaseItemCache(dropped, releaseAtFrameId = releaseAt)
            }
            items.clear()
            index = 0
            prefetchCursor = 0
            clearActives()
            resetLaneState()
            pending.clear()
            lastNowMs = 0
            requestRebuild("trim-clear")
            publishEmptySnapshot()
            return
        }
        // 前缀 [0,start) 与后缀 [end,size) 被移除：非在场条目的预取缓存释放。
        val releaseAt = currentUiFrameId + 1
        for (i in 0 until start) {
            val dropped = items[i]
            if (!dropped.inActive) releaseItemCache(dropped, releaseAtFrameId = releaseAt)
        }
        for (i in end until items.size) {
            val dropped = items[i]
            if (!dropped.inActive) releaseItemCache(dropped, releaseAtFrameId = releaseAt)
        }
        items = items.subList(start, end).toMutableList()
        index = (index - start).coerceIn(0, items.size)
        prefetchCursor = (prefetchCursor - start).coerceIn(0, items.size)
        requestRebuild("trim-range")
        seekTo(currentPositionMs, reason = "trim-range")
        }
    }

    override fun seekTo(positionMs: Long) {
        seekTo(positionMs, reason = "seek")
    }

    private fun seekTo(positionMs: Long, reason: String) {
        synchronized(actionStateLock) {
            val pos = positionMs.coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            // wentBack=true 时 rebuildScene 会清空防重放历史并允许最近一个滚动窗口重放。
            // 若用户没有主动 seek 回看却出现此日志，即为"弹幕滚完又出现"的根因现场。
            if (AppLog.isEnabled) {
                AppLog.w(
                    TAG,
                    "seekTo pos=${pos}ms reason=$reason lastNowMs=${lastNowMs}ms " +
                        "wentBack=${pos < lastNowMs} active=${active.size} items=${items.size}"
                )
            }
            rebuildScene(
                nowMs = pos,
                width = viewportWidth.coerceAtLeast(0),
                outlinePad = outlinePadPx,
                rollingDurationMs = computeRollingDurationMs(config.speedLevel),
                fixedDurationMs = FIXED_DURATION_MS,
                laneCount = configuredLaneCount.coerceAtLeast(1),
                laneHeight = configuredLaneHeightPx.takeIf { it > 0f } ?: max(18f, textSizePx * 1.15f),
                topInset = configuredTopInsetPx,
                maxYTop = configuredMaxYTopPx.coerceAtLeast(configuredTopInsetPx.toFloat()),
                reason = reason,
            )
            publishSnapshotIfDirty(pos)
        }
    }

    override fun clear() {
        synchronized(actionStateLock) {
            clearActives()
            pending.clear()
            clearAdmissionHistory("clear")
            resetLaneState()
            requestRebuild("clear")
            debugPendingCount = 0
            debugNextAtMs = null
            publishEmptySnapshot()
        }
    }

    override fun release() {
        synchronized(actionStateLock) {
            clear()
            releaseAllSnapshotCacheLeases()
        }
    }

    private fun clearActives() {
        if (active.isEmpty()) return
        val releaseAt = currentUiFrameId + 1
        for (i in active.size - 1 downTo 0) {
            val a = active.removeAt(i)
            a.inActive = false
            releaseItemCache(a, releaseAtFrameId = releaseAt)
        }
        snapshotDirty = true
    }

    private fun clearAdmissionHistory(reason: String) {
        admissionHistory.clear()
        lastAdmissionHistoryPruneBeforeMs = 0
        if (AppLog.isEnabled) {
            AppLog.w(TAG, "admissionHistory CLEAR reason=$reason")
        }
    }

    /** History is only needed for the rolling rebuild window; avoid retaining an entire long video. */
    private fun pruneAdmissionHistory(minimumTimeMs: Int, force: Boolean = false) {
        if (!force && minimumTimeMs - lastAdmissionHistoryPruneBeforeMs < ADMISSION_HISTORY_PRUNE_INTERVAL_MS) return
        admissionHistory.pruneBefore(minimumTimeMs)
        lastAdmissionHistoryPruneBeforeMs = minimumTimeMs
    }

    private fun resetLaneState() {
        if (laneLastScroll.isNotEmpty()) java.util.Arrays.fill(laneLastScroll, null)
        if (laneLastTop.isNotEmpty()) java.util.Arrays.fill(laneLastTop, null)
        if (laneLastBottom.isNotEmpty()) java.util.Arrays.fill(laneLastBottom, null)
    }

    private fun publishEmptySnapshot() {
        val out = writableSnapshot()
        try {
            releaseSnapshotCacheLeases(out)
            out.clear()
            latestSnapshot = out
            snapshotDirty = false
        } finally {
            out.endWrite()
        }
    }

    private fun writableSnapshot(): RenderSnapshot {
        while (true) {
            val published = latestSnapshot
            for (candidate in snapshots) {
                if (candidate !== published && candidate.tryBeginWrite()) return candidate
            }
            Thread.yield()
        }
    }

    private fun publishSnapshotIfDirty(nowMs: Int) {
        if (!snapshotDirty) return
        val out = writableSnapshot()
        try {
            releaseSnapshotCacheLeases(out)
            out.clear()
            out.positionMs = nowMs.toLong()
            out.pendingCount = pending.size
            out.nextAtMs = items.getOrNull(index)?.timeMs()
            writeDanmakuRenderOrder(active, out)
            retainSnapshotCacheLeases(out)
            latestSnapshot = out
            snapshotDirty = false
        } finally {
            out.endWrite()
        }
    }

    /** The writable snapshot has no UI reader, so its old cache leases may now be returned. */
    private fun releaseSnapshotCacheLeases(snapshot: RenderSnapshot) {
        val releaseAt = currentUiFrameId + 1
        for (i in 0 until snapshot.count) {
            val entry = snapshot.cacheEntries[i] ?: continue
            cacheManager.enqueueRelease(entry, releaseAtFrameId = releaseAt)
        }
    }

    /** Keep bitmap ownership stable for the lifetime of a published render snapshot. */
    private fun retainSnapshotCacheLeases(snapshot: RenderSnapshot) {
        for (i in 0 until snapshot.count) {
            val item = snapshot.items[i] ?: continue
            val entry = item.cacheEntry
            if (entry != null && entry.tryAcquire()) {
                snapshot.cacheEntries[i] = entry
                snapshot.cacheGenerations[i] = item.cacheGeneration
            }
        }
    }

    /**
     * Player release stops future UI drawing before this action runs, so no
     * published snapshot can still be consumed. Return leases retained by the
     * two buffers that would otherwise never become writable again.
     */
    private fun releaseAllSnapshotCacheLeases() {
        for (snapshot in snapshots) {
            if (!snapshot.tryBeginWrite()) continue
            try {
                releaseSnapshotCacheLeases(snapshot)
                snapshot.clear()
            } finally {
                snapshot.endWrite()
            }
        }
    }

    private fun rebuildScene(
        nowMs: Int,
        width: Int,
        outlinePad: Float,
        rollingDurationMs: Int,
        fixedDurationMs: Int,
        laneCount: Int,
        laneHeight: Float,
        topInset: Int,
        maxYTop: Float,
        reason: String,
    ) {
        val activeBefore = active.size
        // 位置倒退（用户 seek 回更早位置）时允许把 consumed 标记的条目重新入场（回看本来就是重放）；
        // 同位置/前进的重建必须跳过，否则"已滚过/正在滚"的弹幕会再次从右侧入场。
        // 回退量必须超过容差才算真回看：实测 seek 时 ExoPlayer 上报位置与平滑位置存在
        // 几十毫秒的固有偏差（日志实测 backMs=33ms），零容差判定会把这种噪声误判为回看，
        // 清空防重放历史后最近一个滚动窗口全部重放——即"弹幕滚完又出现一遍"的根因。
        val backMs = lastNowMs - nowMs
        val positionWentBack = backMs > REPLAY_BACK_THRESHOLD_MS
        if (positionWentBack) {
            // 误判现场：非用户回看时出现此行，说明平滑位置被回拉后触发了 rebuild
            // → 防重放历史被清 → 最近 6 秒弹幕可重入（"滚完又出现"）。
            if (AppLog.isEnabled) {
                AppLog.w(
                    TAG,
                    "rebuildScene WENT-BACK reason=$reason now=${nowMs}ms lastNowMs=${lastNowMs}ms " +
                        "backMs=${backMs}ms → admissionHistory cleared, replay window re-opens"
                )
            }
        }
        if (positionWentBack) clearAdmissionHistory("wentBack($reason)")
        pending.clear()
        ensureLaneBuffers(laneCount)
        rebuildRequested = false
        lastNowMs = nowMs
        if (width <= 0 || laneCount <= 0) {
            clearActives()
            resetLaneState()
            index = lowerBound(nowMs)
            prefetchCursor = index
            debugPendingCount = 0
            debugNextAtMs = items.getOrNull(index)?.timeMs()
            snapshotDirty = true
            return
        }
        // 保留仍在场的条目（运动状态/缓存/轨道原样，仅重算布局坐标）：
        // 此前每次重建都整屏清空再重放近一个滚动时长，导致"滚动弹幕半路消失"与"同一条弹幕重复入场"。
        preserveActiveForRebuild(
            nowMs = nowMs,
            width = width,
            outlinePad = outlinePad,
            rollingDurationMs = rollingDurationMs,
            laneCount = laneCount,
            laneHeight = laneHeight,
            topInset = topInset,
            maxYTop = maxYTop,
        )
        val preserved = active.size
        // 重新入场的起点：nowMs 往前回退一个最长滚动/固定时长，这部分弹幕会重新从右侧入场。
        // 仅未在场的（含新注入/替换时间线的未消费条目）会被投放，避免重复。
        val admitSinceMs = (nowMs - max(rollingDurationMs, fixedDurationMs)).coerceAtLeast(0)
        pruneAdmissionHistory(admitSinceMs, force = true)
        val priorAdmissions = if (positionWentBack) null else admissionHistory.replayBudget()
        index = lowerBound(admitSinceMs)
        // 重建后从窗口起点重新预取（回看/前进都会重扫这个窗口）。
        prefetchCursor = index
        val releaseAt = currentUiFrameId + 1
        var admitted = 0
        var skippedPreviouslyAdmitted = 0
        val onScreenLimit = maxOnScreenLimit(config)
        while (index < items.size && items[index].timeMs() <= nowMs) {
            val item = items[index]
            // 超过同屏上限：停止补放扫描，条目留给 spawnNewItems 在腾出空间后处理。
            if (active.size >= onScreenLimit) break
            index++
            if (item.data.text.isBlank()) continue
            if (item.timeMs() < admitSinceMs) {
                // 早于重放窗口：预取若已挂图则释放（不会再入场）。
                if (!item.inActive) releaseItemCache(item, releaseAtFrameId = releaseAt)
                continue
            }
            // 时间线整体替换后：新实例与在场条目相同 → 已标记，跳过（除非用户回看）。
            if (!positionWentBack && (item.inActive || item.consumed)) {
                priorAdmissions?.consume(item.data)
                if (!item.inActive && item.cacheEntry != null) {
                    // consumed 的替换实例不会入场：释放其预取缓存。
                    releaseItemCache(item, releaseAtFrameId = releaseAt)
                }
                continue
            }
            if (priorAdmissions?.consume(item.data) == true) {
                skippedPreviouslyAdmitted++
                if (!item.inActive && item.cacheEntry != null) {
                    // 曾入场且已退场/不在场的条目：释放其预取缓存。
                    releaseItemCache(item, releaseAtFrameId = releaseAt)
                }
                continue
            }
            if (tryAdmitItem(
                    item = item,
                    nowMs = nowMs,
                    width = width,
                    outlinePad = outlinePad,
                    rollingDurationMs = rollingDurationMs,
                    fixedDurationMs = fixedDurationMs,
                    laneCount = laneCount,
                    laneHeight = laneHeight,
                    topInset = topInset,
                    maxYTop = maxYTop,
                    // 入场失败进受控 pending 队列（有上限+重试），不再静默丢弃。
                    allowPending = true,
                )) {
                admitted++
                // 重建扫描窗口内"迟到超过 1 秒"才入场的条目 = 补放/重放，
                // 与 admit 日志同 t= 对照可确认同一条是否被二次投放。
                if (nowMs - item.timeMs() > 1_000 && AppLog.isEnabled) {
                    AppLog.w(
                        TAG,
                        "rebuildAdmit(LATE) t=${item.timeMs()}ms dmid=${item.data.dmid ?: "-"} " +
                            "text='${item.data.text.take(12)}' " +
                            "${nowMs - item.timeMs()}ms ago reason=$reason"
                    )
                }
            }
        }
        debugPendingCount = pending.size
        debugNextAtMs = items.getOrNull(index)?.timeMs()
        snapshotDirty = true
        if (AppLog.isEnabled) {
            AppLog.w(
                TAG,
                "rebuildScene reason=$reason now=${nowMs}ms width=$width lanes=$laneCount " +
                    "items=${items.size} activeBefore=$activeBefore preserved=$preserved activeAfter=${active.size} " +
                    "reAdmit=$admitted skippedHistory=$skippedPreviouslyAdmitted since=${admitSinceMs}ms"
            )
        }
    }

    /**
     * 重建场景时保留仍在场的条目：
     * - 运动状态（startTimeMs/pxPerMs/motionStarted）、缓存（cacheEntry）、轨道原样保留；
     * - 仅重算布局坐标（laneHeight/maxYTop 随 config/geometry 变化）并重建轨道引用；
     * - 清理三类条目：已过期（含缓存等待超时）、发送时间超出新位置（回看时属于未来）、
     *   轨道越界（lane 数变小，释放缓存后交给窗口循环重新入场）。
     * 这样 viewport/config/数据替换等重建不再整屏清空，也不再有"滚到一半消失"。
     */
    private fun preserveActiveForRebuild(
        nowMs: Int,
        width: Int,
        outlinePad: Float,
        rollingDurationMs: Int,
        laneCount: Int,
        laneHeight: Float,
        topInset: Int,
        maxYTop: Float,
    ) {
        resetLaneState()
        val releaseAt = currentUiFrameId + 1
        var write = 0
        var droppedFuture = 0
        var droppedLane = 0
        var droppedExpired = 0
        for (read in 0 until active.size) {
            val a = active[read]
            if (a.timeMs() > nowMs) {
                droppedFuture++
                a.inActive = false
                releaseItemCache(a, releaseAtFrameId = releaseAt)
                snapshotDirty = true
                continue
            }
            if (a.lane >= laneCount) {
                droppedLane++
                a.inActive = false
                releaseItemCache(a, releaseAtFrameId = releaseAt)
                snapshotDirty = true
                continue
            }
            if (expireReason(a, width = width, nowMs = nowMs) != null) {
                droppedExpired++
                a.inActive = false
                releaseItemCache(a, releaseAtFrameId = releaseAt)
                snapshotDirty = true
                continue
            }
            // 样式/度量代际变化后重算文本宽度与滚动时长，保证保留条目的运动参数与当前样式一致。
            if (a.measureGeneration != measureGeneration) {
                a.textWidthPx = measureTextWidth(a, outlinePad)
                if (a.kind == DanmakuKind.SCROLL) {
                    val distancePx = (width.toFloat() + a.textWidthPx).coerceAtLeast(0f)
                    val rawPx = distancePx / rollingDurationMs.toFloat()
                    val shortPx = width.toFloat() / rollingDurationMs.toFloat()
                    val maxPx = shortPx * MAX_LONG_SCROLL_SPEED_RATIO
                    // 与入场路径同一扰动因子（确定性），保证样式重建前后速度不变。
                    a.pxPerMs = (min(rawPx, maxPx) * scrollSpeedJitterFactor(a)).coerceAtMost(maxPx)
                    a.durationMs = computeScrollDurationMs(distancePx, a.pxPerMs, rollingDurationMs)
                }
            }
            // 重算布局坐标：config/geometry 变化后 laneHeight/maxYTop 可能已变，保留条目必须跟随。
            a.layoutTopPx =
                when (a.kind) {
                    DanmakuKind.TOP -> (topInset.toFloat() + laneHeight * a.lane).coerceAtMost(maxYTop)
                    DanmakuKind.BOTTOM -> (maxYTop - laneHeight * a.lane).coerceAtLeast(topInset.toFloat())
                    DanmakuKind.SCROLL -> (topInset.toFloat() + laneHeight * a.lane).coerceAtMost(maxYTop)
                }
            when (a.kind) {
                DanmakuKind.SCROLL -> laneLastScroll[a.lane] = a
                DanmakuKind.TOP -> laneLastTop[a.lane] = a
                DanmakuKind.BOTTOM -> laneLastBottom[a.lane] = a
            }
            if (write != read) active[write] = a
            write++
        }
        if (write < active.size) {
            active.subList(write, active.size).clear()
            snapshotDirty = true
            // 重建时的三类丢弃：future=条目发送时间在新位置之后（seek 前进）、lane=轨道越界
            // （字号/区域变化后 lane 数变少）、expired=已到退场条件。
            // lane 类丢弃会把正在滚动的弹幕清掉——"半路消失"候选路径之一。
            if (AppLog.isEnabled) {
                AppLog.w(
                    TAG,
                    "preserveDrop future=$droppedFuture lane=$droppedLane expired=$droppedExpired " +
                        "remain=${active.size} laneCount=$laneCount"
                )
            }
        }
    }

    private fun pruneExpired(width: Int, nowMs: Int) {
        if (active.isEmpty()) return
        val size = active.size
        var write = 0
        val releaseAt = currentUiFrameId + 1
        var droppedTimeout = 0
        var droppedNormal = 0
        for (read in 0 until size) {
            val a = active[read]
            val reason = expireReason(a, width = width, nowMs = nowMs)
            if (reason != null) {
                if (reason == "cacheWaitTimeout") droppedTimeout++ else droppedNormal++
                // cacheWaitTimeout = 缓存超时丢弃（弹幕从未可见），是"半路消失"的可疑来源，单独详记。
                if (reason == "cacheWaitTimeout") {
                    if (AppLog.isEnabled) {
                        AppLog.w(
                            TAG,
                            "pruneExpired DROP cacheTimeout t=${a.timeMs()}ms text='${a.data.text.take(12)}' " +
                                "start=${a.startTimeMs}ms now=${nowMs}ms age=${nowMs - a.startTimeMs}ms " +
                                "motion=${a.motionStarted} lane=${a.lane} dur=${a.durationMs}ms"
                        )
                    }
                } else if (reason == "duration" && a.kind == DanmakuKind.SCROLL) {
                    // duration 退场时 x 理应 ≈ -textWidth（刚好完全出屏）。
                    // x 还在屏幕右侧 25% 区域内就到期 = "滚到一半消失"的直接现场：
                    // 要么 startTimeMs 被位置前跳拉大（elapsed 虚增），要么 durationMs 算短了。
                    val x = scrollX(width = width, nowMs = nowMs, startTimeMs = a.startTimeMs, pxPerMs = a.pxPerMs)
                    if (x + a.textWidthPx > width * 0.25f && AppLog.isEnabled) {
                        AppLog.w(
                            TAG,
                            "pruneExpired EARLY-EXIT t=${a.timeMs()}ms text='${a.data.text.take(12)}' " +
                                "start=${a.startTimeMs}ms now=${nowMs}ms elapsed=${nowMs - a.startTimeMs}ms " +
                                "dur=${a.durationMs}ms x=${x.toInt()}px tailX=${(x + a.textWidthPx).toInt()}px width=${width}px"
                        )
                    }
                }
                clearLaneReferenceIfMatch(a)
                a.inActive = false
                releaseItemCache(a, releaseAtFrameId = releaseAt)
                snapshotDirty = true
                continue
            }
            if (write != read) active[write] = a
            write++
        }
        if (write < size) {
            active.subList(write, size).clear()
            if ((droppedTimeout > 0 || droppedNormal > 0) && AppLog.isEnabled) {
                AppLog.w(
                    TAG,
                    "pruneExpired removed=${size - write} timeout=$droppedTimeout normal=$droppedNormal " +
                        "now=${nowMs}ms remain=${active.size}"
                )
            }
        }
    }

    private fun processPendingItems(
        nowMs: Int,
        width: Int,
        outlinePad: Float,
        rollingDurationMs: Int,
        fixedDurationMs: Int,
        laneCount: Int,
        laneHeight: Float,
        topInset: Int,
        maxYTop: Float,
    ) {
        if (pending.isEmpty()) return
        // 同屏上限内不再重试 pending：条目会按 age 上限自然放弃，避免高密度时队列空转。
        if (active.size >= maxOnScreenLimit(config)) return
        val pendingCount = pending.size
        var processed = 0
        var indexInQueue = 0
        while (indexInQueue < pendingCount && pending.isNotEmpty()) {
            val entry = pending.removeFirst()
            indexInQueue++
            if (entry.nextTryMs > nowMs) {
                pending.addLast(entry)
                continue
            }
            if (processed >= MAX_PENDING_RETRY_PER_FRAME) {
                pending.addLast(entry)
                continue
            }
            processed++
            val admitted =
                tryAdmitItem(
                    item = entry.item,
                    nowMs = nowMs,
                    width = width,
                    outlinePad = outlinePad,
                    rollingDurationMs = rollingDurationMs,
                    fixedDurationMs = fixedDurationMs,
                    laneCount = laneCount,
                    laneHeight = laneHeight,
                    topInset = topInset,
                    maxYTop = maxYTop,
                    allowPending = false,
                )
            if (admitted) continue
            val age = nowMs - entry.firstTryMs
            if (entry.retryCount >= MAX_PENDING_RETRY_COUNT || age >= MAX_DELAY_MS) {
                // 永久放弃：条目不再会入场，若预取挂过图则释放。
                if (entry.item.cacheEntry != null) {
                    releaseItemCache(entry.item, releaseAtFrameId = currentUiFrameId + 1)
                }
                continue
            }
            entry.retryCount += 1
            entry.nextTryMs = nowMs + DELAY_STEP_MS
            pending.addLast(entry)
        }
    }

    private fun spawnNewItems(
        nowMs: Int,
        width: Int,
        outlinePad: Float,
        rollingDurationMs: Int,
        fixedDurationMs: Int,
        laneCount: Int,
        laneHeight: Float,
        topInset: Int,
        maxYTop: Float,
    ) {
        skipOld(nowMs, rollingDurationMs)
        dropIfLagging(nowMs)
        val onScreenLimit = maxOnScreenLimit(config)
        var spawnAttempts = 0
        var droppedByCap = 0
        while (index < items.size && items[index].timeMs() <= nowMs) {
            if (spawnAttempts >= MAX_SPAWN_PER_FRAME) break
            val item = items[index]
            index++
            spawnAttempts++
            if (item.data.text.isBlank()) continue
            // 同屏上限语义 = 并发数上限，不是"排队等位"：满员（含硬顶余量）时当场放弃该条，
            // 入场时刻永远等于弹幕自身的发送时间。此前满员条目留在时间线上等退场腾位
            //（lag 窗口 MAX_CATCH_UP_LAG_MS），在高密度等宽文本视频（如满屏单字：速度/时长
            // 全相同）里退场时刻高度相关，形成"整批退场 → 积压整批迟到入场"的极限环：
            // 批间数十帧无槽位释放、积压被 dropIfLagging 整段丢弃，观感为
            // "弹幕一批一批出现、中间断层"。改为满员即弃后，退场时刻由发送时间自然错开，
            // 槽位释放连续，极限环无法成形；上限本身完整保留（并发渲染数的性能约束不变）。
            if (active.size >= onScreenLimit + ON_SCREEN_OVERSHOOT) {
                droppedByCap++
                capDropTotal++
                if (!item.inActive && item.cacheEntry != null) {
                    releaseItemCache(item, releaseAtFrameId = currentUiFrameId + 1)
                }
                continue
            }
            tryAdmitItem(
                item = item,
                nowMs = nowMs,
                width = width,
                outlinePad = outlinePad,
                rollingDurationMs = rollingDurationMs,
                fixedDurationMs = fixedDurationMs,
                laneCount = laneCount,
                laneHeight = laneHeight,
                topInset = topInset,
                maxYTop = maxYTop,
                // 软上限之上不再排队重试：要么当场有轨道要么放弃，杜绝时间性积压。
                allowPending = active.size < onScreenLimit,
            )
        }
        if (droppedByCap > 0 && AppLog.isEnabled && nowMs - lastCapDropLogMs >= 1_000) {
            // 满员丢弃是"弹幕变稀"的直接上游证据，限频采样留痕。
            lastCapDropLogMs = nowMs
            AppLog.w(
                TAG,
                "spawn capDrop frame=$droppedByCap total=$capDropTotal " +
                    "active=${active.size} limit=$onScreenLimit now=${nowMs}ms"
            )
        }
    }

    private fun tryAdmitItem(
        item: DanmakuItem,
        nowMs: Int,
        width: Int,
        outlinePad: Float,
        rollingDurationMs: Int,
        fixedDurationMs: Int,
        laneCount: Int,
        laneHeight: Float,
        topInset: Int,
        maxYTop: Float,
        allowPending: Boolean,
    ): Boolean {
        // dmid 级防重（Danmaku 判等含 dmid，同文本不同用户不会误伤）：
        // 时间线重复注入（分段重复发布/追加竞态）时，正常入场路径此前不查防重历史，
        // 同一条的两行会先后入场不同轨道——"同一条隔一两秒再滚一遍"。此处兜底拦截，
        // 并留 DUPLICATE-ADMIT 日志作为上游注入路径的定位证据。
        // 回看重放不受影响：rebuildScene 判定 wentBack 时已清空历史。
        if (admissionHistory.wasRecorded(item.data)) {
            if (AppLog.isEnabled) {
                AppLog.w(
                    TAG,
                    "DUPLICATE-ADMIT suppressed t=${item.timeMs()}ms dmid=${item.data.dmid ?: "-"} " +
                        "text='${item.data.text.take(12)}' now=${nowMs}ms"
                )
            }
            return true
        }
        val textWidth = measureTextWidth(item, outlinePad)
        val kind = kindOf(item.data)
        val marginPx = max(12f, (textSizePx + outlinePad * 2f) * 0.6f)
        val admitted =
            when (kind) {
                DanmakuKind.SCROLL ->
                    trySpawnScroll(
                        item = item,
                        nowMs = nowMs,
                        width = width,
                        textWidth = textWidth,
                        rollingDurationMs = rollingDurationMs,
                        laneCount = laneCount,
                        laneHeight = laneHeight,
                        topInset = topInset,
                        maxYTop = maxYTop,
                        marginPx = marginPx,
                    )
                DanmakuKind.TOP ->
                    trySpawnFixed(
                        item = item,
                        kind = DanmakuKind.TOP,
                        nowMs = nowMs,
                        textWidth = textWidth,
                        durationMs = fixedDurationMs,
                        laneCount = laneCount,
                        laneHeight = laneHeight,
                        topInset = topInset,
                        maxYTop = maxYTop,
                    )
                DanmakuKind.BOTTOM ->
                    trySpawnFixed(
                        item = item,
                        kind = DanmakuKind.BOTTOM,
                        nowMs = nowMs,
                        textWidth = textWidth,
                        durationMs = fixedDurationMs,
                        laneCount = laneCount,
                        laneHeight = laneHeight,
                        topInset = topInset,
                        maxYTop = maxYTop,
                    )
            }
        if (!admitted && allowPending) {
            enqueuePending(item = item, nowMs = nowMs)
        }
        return admitted
    }

    private fun trySpawnScroll(
        item: DanmakuItem,
        nowMs: Int,
        width: Int,
        textWidth: Float,
        rollingDurationMs: Int,
        laneCount: Int,
        laneHeight: Float,
        topInset: Int,
        maxYTop: Float,
        marginPx: Float,
    ): Boolean {
        val distancePx = (width.toFloat() + textWidth).coerceAtLeast(0f)
        val rawPx = distancePx / rollingDurationMs.toFloat()
        val shortPx = width.toFloat() / rollingDurationMs.toFloat()
        val maxPx = shortPx * MAX_LONG_SCROLL_SPEED_RATIO
        // 每条弹幕叠加确定性速度扰动：等宽文本（如满屏单字）原始速度完全相同 → 时长相同 →
        // 退场时刻相同，与同屏上限叠加形成"整批进出场"极限环；扰动使退场时刻散开，
        // 槽位释放恢复连续。速度差异 ±12% 与文本宽度带来的自然差异（±11%）同量级，不可感知。
        val pxNew = (min(rawPx, maxPx) * scrollSpeedJitterFactor(item)).coerceAtMost(maxPx)
        val durationMs =
            computeScrollDurationMs(
                distancePx = distancePx,
                pxPerMs = pxNew,
                fallbackDurationMs = rollingDurationMs,
            )
        for (lane in 0 until laneCount) {
            val prev = laneLastScroll[lane]
            if (prev != null && isExpired(prev, width = width, nowMs = nowMs)) {
                laneLastScroll[lane] = null
            }
            val rear = laneLastScroll[lane]
            if (rear == null) {
                activate(
                    item = item,
                    kind = DanmakuKind.SCROLL,
                    lane = lane,
                    textWidth = textWidth,
                    pxPerMs = pxNew,
                    durationMs = durationMs,
                    startTimeMs = nowMs,
                    layoutTopPx = (topInset.toFloat() + laneHeight * lane).coerceAtMost(maxYTop),
                )
                laneLastScroll[lane] = item
                return true
            }
            val tailPrev = scrollX(width = width, nowMs = nowMs, startTimeMs = rear.startTimeMs, pxPerMs = rear.pxPerMs) + rear.textWidthPx
            if (isScrollLaneAvailable(width.toFloat(), nowMs, rear, tailPrev, pxNew, marginPx)) {
                activate(
                    item = item,
                    kind = DanmakuKind.SCROLL,
                    lane = lane,
                    textWidth = textWidth,
                    pxPerMs = pxNew,
                    durationMs = durationMs,
                    startTimeMs = nowMs,
                    layoutTopPx = (topInset.toFloat() + laneHeight * lane).coerceAtMost(maxYTop),
                )
                laneLastScroll[lane] = item
                return true
            }
        }
        return false
    }

    private fun trySpawnFixed(
        item: DanmakuItem,
        kind: DanmakuKind,
        nowMs: Int,
        textWidth: Float,
        durationMs: Int,
        laneCount: Int,
        laneHeight: Float,
        topInset: Int,
        maxYTop: Float,
    ): Boolean {
        val lanes =
            when (kind) {
                DanmakuKind.TOP -> laneLastTop
                DanmakuKind.BOTTOM -> laneLastBottom
                DanmakuKind.SCROLL -> return false
            }
        for (lane in 0 until laneCount) {
            val prev = lanes[lane]
            if (prev != null && isExpired(prev, width = viewportWidth, nowMs = nowMs)) {
                lanes[lane] = null
            }
            if (lanes[lane] != null) continue
            activate(
                item = item,
                kind = kind,
                lane = lane,
                textWidth = textWidth,
                pxPerMs = 0f,
                durationMs = durationMs,
                startTimeMs = nowMs,
                layoutTopPx =
                    when (kind) {
                        DanmakuKind.TOP -> (topInset.toFloat() + laneHeight * lane).coerceAtMost(maxYTop)
                        DanmakuKind.BOTTOM -> (maxYTop - laneHeight * lane).coerceAtLeast(topInset.toFloat())
                        DanmakuKind.SCROLL -> topInset.toFloat()
                    },
            )
            lanes[lane] = item
            return true
        }
        return false
    }

    /**
     * 缓存请求调度（action 线程每帧）：
     * 1) 入场优先 FIFO——最老等待者先建，消除旧轮转游标的发现延迟；
     * 2) 时间线预取——对 [now, now+PREFETCH_WINDOW_MS] 内未入场条目提前建图，
     *    入场即有图（activate 判定缓存命中后 motionStarted 直接置 true），
     *    消除 miss-跳帧与 cacheWaitTimeout 丢弃，并把建图负载从突发摊平到空闲期。
     * 共享缓存表（相同内容同 bitmap）让重复文本的预取零成本命中。
     */
    private fun requestCacheBuilds(
        outlinePad: Float,
        cfg: DanmakuConfig,
    ) {
        val style = ensureCacheStyle(outlinePad, cfg)
        var requested = 0

        // ---- 1) 在场未缓存条目（最老等待者优先）----
        // scanBudget 限定单帧扫描圈数：退避未到期的条目放回队尾，避免空转循环。
        val nowMsForRetry = currentPositionMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        var scanBudget = uncachedActive.size
        while (uncachedActive.isNotEmpty() && requested < MAX_CACHE_REQUESTS_PER_FRAME && scanBudget-- > 0) {
            if (cacheManager.queueDepth() >= MAX_CACHE_QUEUE_DEPTH) return
            val item = uncachedActive.removeFirst()
            if (!item.inActive) continue // 已退场，stale 条目直接丢弃
            if (item.cacheRetryNotBeforeMs > nowMsForRetry) {
                uncachedActive.addLast(item) // 退避未到期：放回队尾下轮再看
                continue
            }
            if (hasValidCache(item, style.generation)) continue // 预取已送达
            if (item.cacheState == DanmakuCacheState.Rendering) continue // 已在途
            if (!inlineImagesReadyOrPrefetch(item)) {
                // 表情未就绪：放回队尾下轮重查（prefetch 已在检查中触发）。
                uncachedActive.addLast(item)
                continue
            }
            enqueueCacheRequest(item, style)
            requested++
        }

        // ---- 2) 时间线预取（独立预算，不与在场条目抢占）----
        if (prefetchCursor < index) prefetchCursor = index
        if (prefetchCursor > items.size) prefetchCursor = items.size
        val nowMs = currentPositionMs.coerceAtMost(Int.MAX_VALUE.toLong() - PREFETCH_WINDOW_MS).toInt()
        val prefetchDeadlineMs = nowMs + PREFETCH_WINDOW_MS
        var prefetchRequested = 0
        while (prefetchCursor < items.size && prefetchRequested < MAX_PREFETCH_REQUESTS_PER_FRAME) {
            if (cacheManager.queueDepth() >= MAX_CACHE_QUEUE_DEPTH) return
            val item = items[prefetchCursor]
            if (item.timeMs() > prefetchDeadlineMs) break
            prefetchCursor++
            if (item.data.text.isBlank()) continue
            if (item.inActive) continue // 在场条目由 FIFO 管
            if (item.consumed) continue // 替换标记的条目不会再入场（回看会重建游标）
            if (hasValidCache(item, style.generation)) continue
            if (item.cacheState == DanmakuCacheState.Rendering) continue // 已在途
            if (!inlineImagesReadyOrPrefetch(item)) continue // 表情未就绪：prefetch 已触发，入场后 FIFO 兜底建图
            enqueueCacheRequest(item, style)
            prefetchRequested++
        }
    }

    /** CacheStyle 复用：样式参数未变时返回同一实例，避免每帧分配。 */
    private fun ensureCacheStyle(outlinePad: Float, cfg: DanmakuConfig): CacheStyle {
        val gen = cacheStyleGeneration
        val cached = cachedStyle
        if (cached != null &&
            cachedStyleGeneration == gen &&
            cachedStyleOutlinePadPx == outlinePad &&
            cached.textSizePx == textSizePx &&
            cached.strokeWidthPx == strokeWidthPx &&
            cached.fontWeight == cfg.fontWeight &&
            cached.showHighLikeIcon == cfg.showHighLikeIcon
        ) {
            return cached
        }
        val style =
            CacheStyle(
                textSizePx = textSizePx,
                fontWeight = cfg.fontWeight,
                strokeWidthPx = strokeWidthPx,
                outlinePadPx = outlinePad,
                generation = gen,
                showHighLikeIcon = cfg.showHighLikeIcon,
            )
        cachedStyle = style
        cachedStyleGeneration = gen
        cachedStyleOutlinePadPx = outlinePad
        return style
    }

    private fun hasValidCache(item: DanmakuItem, generation: Int): Boolean {
        val entry = item.cacheEntry
        return entry != null && !entry.isRecycled && item.cacheGeneration == generation
    }

    private fun enqueueCacheRequest(item: DanmakuItem, style: CacheStyle) {
        item.cacheState = DanmakuCacheState.Rendering
        item.pendingCacheGeneration = style.generation
        cacheManager.requestBuildCache(
            item = item,
            textWidthPx = measureTextWidth(item, style.outlinePadPx),
            style = style,
        )
    }

    /**
     * 样式代际切换后，清理预取窗口内未入场条目上的旧代际缓存
     * （入场判定按代际比较会拒绝旧图，不清则白白占住位图引用直到条目被丢弃）。
     */
    private fun releaseStalePrefetchCaches(releaseAt: Int) {
        val from = minOf(index, prefetchCursor)
        val to = minOf(prefetchCursor, items.size)
        for (i in from until to) {
            val item = items[i]
            if (item.inActive) continue
            if (item.cacheGeneration != -1 || item.cacheEntry != null) {
                releaseItemCache(item, releaseAtFrameId = releaseAt)
            }
        }
    }

    private fun releaseItemCache(item: DanmakuItem, releaseAtFrameId: Int) {
        val entry = item.cacheEntry
        if (entry != null) {
            cacheManager.enqueueRelease(entry, releaseAtFrameId = releaseAtFrameId)
            item.cacheEntry = null
        }
        item.cacheState = DanmakuCacheState.Init
        item.cacheGeneration = -1
        item.pendingCacheGeneration = -1
        item.cacheRetryNotBeforeMs = 0
    }

    private fun clearLaneReferenceIfMatch(item: DanmakuItem) {
        when (item.kind) {
            DanmakuKind.SCROLL -> if (item.lane in laneLastScroll.indices && laneLastScroll[item.lane] === item) laneLastScroll[item.lane] = null
            DanmakuKind.TOP -> if (item.lane in laneLastTop.indices && laneLastTop[item.lane] === item) laneLastTop[item.lane] = null
            DanmakuKind.BOTTOM -> if (item.lane in laneLastBottom.indices && laneLastBottom[item.lane] === item) laneLastBottom[item.lane] = null
        }
    }

    private fun isExpired(
        item: DanmakuItem,
        width: Int,
        nowMs: Int,
    ): Boolean = expireReason(item, width, nowMs) != null

    /**
     * 返回过期原因，null 表示未过期。拆出来是为了让 pruneExpired 的日志能区分：
     * - cacheWaitTimeout：缓存等待超时（MAX_CACHE_WAIT_MS 内没拿到缓存）→ 弹幕从未可见就被丢
     * - duration：已运动满 durationMs（正常退场）
     * - scrolledOut：滚动弹幕已完全离开屏幕左侧（正常退场）
     */
    private fun expireReason(
        item: DanmakuItem,
        width: Int,
        nowMs: Int,
    ): String? {
        if (isCacheWaitExpired(
                motionStarted = item.motionStarted,
                admittedAtMs = item.startTimeMs,
                nowMs = nowMs,
                timeoutMs = MAX_CACHE_WAIT_MS,
            )) {
            return "cacheWaitTimeout"
        }
        if (!item.motionStarted) return null
        val elapsed = nowMs - item.startTimeMs
        if (elapsed >= item.durationMs) return "duration"
        if (item.kind != DanmakuKind.SCROLL) return null
        return if (scrollX(width = width, nowMs = nowMs, startTimeMs = item.startTimeMs, pxPerMs = item.pxPerMs) + item.textWidthPx < 0f) {
            "scrolledOut"
        } else {
            null
        }
    }

    private fun scrollX(width: Int, nowMs: Int, startTimeMs: Int, pxPerMs: Float): Float {
        val elapsed = (nowMs - startTimeMs).coerceAtLeast(0)
        return width.toFloat() - elapsed * pxPerMs
    }

    private fun isScrollLaneAvailable(
        width: Float,
        nowMs: Int,
        front: DanmakuItem,
        tailPrev: Float,
        pxNew: Float,
        marginPx: Float,
    ): Boolean {
        // 尚未有可绘制缓存的前一条弹幕仍占用轨道；它开始运动前不能在后面塞新弹幕。
        if (!front.motionStarted) return false
        val elapsedPrev = nowMs - front.startTimeMs
        val prevRemaining = front.durationMs - elapsedPrev
        if (prevRemaining <= 0) return true
        if (tailPrev + marginPx > width) return false
        val pxPrev = front.pxPerMs
        if (pxNew <= pxPrev) return true
        val gap0 = (width - tailPrev - marginPx).coerceAtLeast(0f)
        val maxSafe = (pxNew - pxPrev) * prevRemaining
        return gap0 >= maxSafe
    }

    private fun activate(
        item: DanmakuItem,
        kind: DanmakuKind,
        lane: Int,
        textWidth: Float,
        pxPerMs: Float,
        durationMs: Int,
        startTimeMs: Int,
        layoutTopPx: Float,
    ) {
        item.kind = kind
        item.lane = lane
        item.textWidthPx = textWidth
        item.pxPerMs = pxPerMs
        item.durationMs = durationMs
        item.startTimeMs = startTimeMs
        item.layoutTopPx = layoutTopPx
        item.inActive = true
        // 预取命中：入场即有可用缓存，直接开始运动——startTimeMs 即入场时刻，
        // 弹幕从右侧准时入场，跳过"等缓存→重锚→迟到入场"的整段延迟。
        if (hasValidCache(item, cacheStyleGeneration)) {
            item.motionStarted = true
        } else {
            item.motionStarted = false
            uncachedActive.addLast(item)
        }
        active.add(item)
        admissionHistory.record(item.data)
        snapshotDirty = true
        // 弹幕入场记录。若同一 t=ms + text 出现两次 activate，即为"重复入场"。
        // dmid = 弹幕唯一 ID（B 站协议 dmid）：同 dmid 两次 admit = 引擎重放；
        // dmid 不同但内容相同 = 数据里真实存在多条（合并策略问题）。
        // 弹幕入场记录（1/8 采样：高密度下每条都拼字符串+format，日志开销本身成为卡顿源）。
        // 采样不破坏"重复入场"排查：同 dmid 重复出现仍有概率被抓到。
        if (AppLog.isEnabled && admitLogCounter++ % ADMIT_LOG_SAMPLE == 0) {
            AppLog.w(
                TAG,
                "admit kind=$kind t=${item.timeMs()}ms dmid=${item.data.dmid ?: item.data.midHash?.takeLast(6) ?: "-"} " +
                    "text='${item.data.text.take(12)}' " +
                    "lane=$lane pxPerMs=${String.format("%.4f", pxPerMs)} dur=${durationMs}ms start=$startTimeMs active=${active.size}"
            )
        }
    }

    private fun computeScrollDurationMs(distancePx: Float, pxPerMs: Float, fallbackDurationMs: Int): Int {
        val safeFallback = fallbackDurationMs.coerceAtLeast(1)
        if (!distancePx.isFinite() || distancePx <= 0f) return safeFallback
        if (!pxPerMs.isFinite() || pxPerMs <= 0f) return safeFallback
        val travel = ceil((distancePx / pxPerMs).toDouble()).toLong().coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
        return max(safeFallback, travel)
    }

    private fun skipOld(nowMs: Int, rollingDurationMs: Int) {
        val ignoreBefore = nowMs - rollingDurationMs
        val releaseAt = currentUiFrameId + 1
        while (index < items.size && items[index].timeMs() < ignoreBefore) {
            val skipped = items[index]
            // 被跳过的条目不再入场：若预取挂过图则释放。
            if (!skipped.inActive && skipped.cacheEntry != null) {
                releaseItemCache(skipped, releaseAtFrameId = releaseAt)
            }
            index++
        }
    }

    private fun dropIfLagging(nowMs: Int) {
        val dropBefore = nowMs - MAX_CATCH_UP_LAG_MS
        val releaseAt = currentUiFrameId + 1
        while (index < items.size && items[index].timeMs() < dropBefore) {
            val dropped = items[index]
            if (!dropped.inActive && dropped.cacheEntry != null) {
                releaseItemCache(dropped, releaseAtFrameId = releaseAt)
            }
            index++
        }
    }

    private fun enqueuePending(item: DanmakuItem, nowMs: Int) {
        if (pending.size >= MAX_PENDING) pending.removeFirst()
        pending.addLast(
            PendingSpawn(
                item = item,
                nextTryMs = nowMs + DELAY_STEP_MS,
                firstTryMs = nowMs,
                retryCount = 0,
            ),
        )
    }

    private data class PendingSpawn(
        val item: DanmakuItem,
        var nextTryMs: Int,
        val firstTryMs: Int,
        var retryCount: Int,
    )

    private fun ensureLaneBuffers(laneCount: Int) {
        if (laneLastScroll.size != laneCount) laneLastScroll = arrayOfNulls(laneCount)
        if (laneLastTop.size != laneCount) laneLastTop = arrayOfNulls(laneCount)
        if (laneLastBottom.size != laneCount) laneLastBottom = arrayOfNulls(laneCount)
    }

    private fun kindOf(d: Danmaku): DanmakuKind =
        when (d.mode) {
            5 -> DanmakuKind.TOP
            4 -> DanmakuKind.BOTTOM
            else -> DanmakuKind.SCROLL
        }

    private fun measureTextWidth(item: DanmakuItem, outlinePad: Float): Float {
        if (item.measureGeneration == measureGeneration && item.measuredWidthPx.isFinite() && item.measuredWidthPx >= 0f) {
            return item.measuredWidthPx
        }
        val text = item.data.text
        val width = when {
            text.isBlank() -> outlinePad * 2f
            !needsInlineSegments(item) -> actionPaint.measureText(text) + outlinePad * 2f
            else -> measureTextWidthWithInlineSegments(item, outlinePad)
        }
        item.measuredWidthPx = width
        item.measureGeneration = measureGeneration
        return width
    }

    /** 是否需要分段解析：高赞图标（开关开 + attr 命中）或文本可能含表情 token；VIP 渐变走整行贴图渲染。 */
    private fun needsInlineSegments(item: DanmakuItem): Boolean {
        val data = item.data
        if (data.vipGradient) return false
        return (config.showHighLikeIcon && data.isHighLiked) || data.text.indexOf('[') >= 0
    }

    /**
     * 分段测量：Text 段用 paint.measureText(start,end)，表情/高赞图标按行高方块计。
     * 行高口径（descent-ascent+leading）与 act() 的 textBoxHeight 及 CacheManager 烘焙一致，
     * 保证测量宽度 ≥ 烘焙内容宽度，位图不裁字。
     */
    private fun measureTextWidthWithInlineSegments(item: DanmakuItem, outlinePad: Float): Float {
        actionPaint.getFontMetrics(actionFontMetrics)
        val emoteSizePx = (actionFontMetrics.descent - actionFontMetrics.ascent + actionFontMetrics.leading)
            .coerceAtLeast(1f)
        val text = item.data.text
        val segments = parseInlineSegmentsFor(item)
        var w = 0f
        if (segments == null) {
            w = actionPaint.measureText(text)
        } else {
            for (seg in segments) {
                when (seg) {
                    is DanmakuInlineSegment.Text ->
                        if (seg.end > seg.start) w += actionPaint.measureText(text, seg.start, seg.end)
                    is DanmakuInlineSegment.Emote -> w += emoteSizePx
                    is DanmakuInlineSegment.HighLikeIcon -> w += emoteSizePx + inlineIconGapPx(emoteSizePx)
                }
            }
        }
        return w + outlinePad * 2f
    }

    /**
     * 取/解析内联段。缓存命中要求 item 记录的词典版本与当前一致（词典刷新后表情
     * 命中状态可能变化）；未就绪词典（version=0）下解析出的"仅高赞图标"结果不缓存。
     */
    private fun parseInlineSegmentsFor(item: DanmakuItem): List<DanmakuInlineSegment>? {
        val emoteVersion = DanmakuEmoteRepository.version()
        val showIcon = config.showHighLikeIcon
        val cached = item.inlineSegments
        if (cached != null && item.inlineSegmentsEmoteVersion == emoteVersion &&
            item.inlineSegmentsShowIcon == showIcon
        ) return cached
        val parsed = DanmakuInlineParser.parse(
            text = item.data.text,
            isHighLiked = item.data.isHighLiked,
            showHighLikeIcon = showIcon,
            canParseEmote = emoteVersion > 0,
            urlForToken = DanmakuEmoteRepository::urlForToken,
        )
        if (parsed != null && DanmakuInlineParser.shouldCacheParsedSegments(item.data.text, emoteVersion > 0)) {
            item.inlineSegments = parsed
            item.inlineSegmentsEmoteVersion = emoteVersion
            item.inlineSegmentsShowIcon = showIcon
        }
        return parsed
    }

    /** 表情/图标与相邻内容的间隙。 */
    private fun inlineIconGapPx(iconSizePx: Float): Float = (iconSizePx * 0.14f).coerceAtLeast(density * 2f)

    /**
     * 建图前置检查：所有表情段位图已就绪才允许烘焙（保证烘焙产物完整、可直接入共享表）；
     * 未就绪则触发 prefetch 返回 false，调用方下轮重查（加载完成无需回调通知——
     * requestCacheBuilds 每帧轮询 getCached 自然发现就绪）。
     */
    private fun inlineImagesReadyOrPrefetch(item: DanmakuItem): Boolean {
        if (!needsInlineSegments(item)) return true
        val segments = parseInlineSegmentsFor(item) ?: return true
        var ready = true
        for (seg in segments) {
            if (seg !is DanmakuInlineSegment.Emote) continue
            val bmp = EmoteBitmapLoader.getCached(seg.url)
            if (bmp != null && !bmp.isRecycled) continue
            ready = false
            EmoteBitmapLoader.prefetch(seg.url)
        }
        return ready
    }

    private fun lowerBound(pos: Int): Int {
        var l = 0
        var r = items.size
        while (l < r) {
            val m = (l + r) ushr 1
            if (items[m].timeMs() < pos) l = m + 1 else r = m
        }
        return l
    }

    private fun centerX(width: Int, contentWidth: Float): Float {
        if (width <= 0) return 0f
        val x = (width.toFloat() - contentWidth) / 2f
        return x.coerceAtLeast(0f)
    }

    private fun computeRollingDurationMs(speedLevel: Int): Int {
        // Keep the speed scale aligned with the project's previous implementation.
        // (User feedback: new 10 ~= old 4 was too slow.)
        val speed = speedMultiplier(speedLevel)
        val duration = (DEFAULT_ROLLING_DURATION_MS / speed).toInt()
        return duration.coerceIn(MIN_ROLLING_DURATION_MS, MAX_ROLLING_DURATION_MS)
    }

    private fun speedMultiplier(level: Int): Float =
        // 对齐 akdanmaku toDanmakuDurationMs 各档时长：multiplier = 6000 / akMs。
        // （akdanmaku 档位 1→12000ms ... 9→2160ms，缺 4 档补 6000ms 对齐 level 4/5。）
        when (min(10, max(1, level))) {
            1 -> 0.50f   // 6000/12000
            2 -> 0.588f  // 6000/10200
            3 -> 0.714f  // 6000/8400
            4 -> 1.0f    // 6000/6000（akdanmaku 原 else 回退，此处显式对齐）
            5 -> 1.0f    // 6000/6000
            6 -> 1.25f   // 6000/4800
            7 -> 1.5625f // 6000/3840
            8 -> 2.0f    // 6000/3000
            9 -> 2.778f  // 6000/2160
            else -> 2.778f
        }

    private companion object {
        private const val TAG = "BlblDmEngine"
        private const val DEFAULT_ROLLING_DURATION_MS = 6_000f
        private const val MIN_ROLLING_DURATION_MS = 2_000
        private const val MAX_ROLLING_DURATION_MS = 20_000

        private const val FIXED_DURATION_MS = 4_000
        private const val MAX_LONG_SCROLL_SPEED_RATIO = 1.5f

        private const val DELAY_STEP_MS = 220
        private const val MAX_DELAY_MS = 1_600
        private const val MAX_PENDING = 260
        private const val MAX_SPAWN_PER_FRAME = 48
        private const val MAX_PENDING_RETRY_PER_FRAME = 48
        private const val MAX_PENDING_RETRY_COUNT = 1
        private const val MAX_CATCH_UP_LAG_MS = 1_200

        private const val MAX_CACHE_REQUESTS_PER_FRAME = 8
        private const val MAX_PREFETCH_REQUESTS_PER_FRAME = 8
        private const val MAX_CACHE_QUEUE_DEPTH = 48
        private const val MAX_CACHE_WAIT_MS = 1_600

        /** 建图失败（预算耗尽）后的重试退避：给释放路径时间回填位图池。 */
        private const val CACHE_FAIL_RETRY_BACKOFF_MS = 250

        /**
         * 同屏弹幕数上限自适应档：种子值（自适应会按帧率在 [ADAPTIVE_FLOOR]～
         * [ADAPTIVE_CEILING] 间自动增减，见 AdaptiveOnScreenLimit）。
         */
        private const val DEFAULT_TV_MAX_ON_SCREEN = 100
        private const val DEFAULT_MAX_ON_SCREEN = 160

        /** 自适应同屏上限的下限/硬顶。 */
        private const val ADAPTIVE_FLOOR = 100
        private const val ADAPTIVE_CEILING = 400

        /**
         * 同屏软上限之上允许的即时入场余量：满员时若轨道恰好可用，仍可入场至该硬顶。
         * 硬顶随自适应上限一起浮动（自适应值 + 本余量）。
         */
        private const val ON_SCREEN_OVERSHOOT = 16

        /** admit 日志采样步长（每 N 条输出 1 条）。 */
        private const val ADMIT_LOG_SAMPLE = 8
        private const val ADMISSION_HISTORY_PRUNE_INTERVAL_MS = 1_000
        private const val DRAW_MISS_LOG_INTERVAL_MS = 500L

        /** 时间线预取窗口：提前为未来 2 秒内将入场的条目建图。 */
        private const val PREFETCH_WINDOW_MS = 2_000

        /**
         * rebuildScene 的"回看"判定容差：回退量超过此值才清防重放历史并允许重放。
         * 覆盖 seek 上报位置与平滑位置的固有偏差（实测 33ms 级）；
         * 真实的用户回看（进度条拖回）至少是数百毫秒到数秒。
         */
        private const val REPLAY_BACK_THRESHOLD_MS = 500

    }

}

/**
 * 每条弹幕的确定性滚动速度扰动因子（1 ± [SCROLL_SPEED_JITTER]）。
 * 以 dmid/midHash/text/时间为种子，同一条弹幕在任何重放、重建、样式代际下速度一致。
 *
 * 为什么需要：滚动速度按文本宽度计算，等宽文本（如满屏单字）速度/时长完全相同，
 * 退场时刻高度相关；同屏上限满员时若退场成批，入场也随之成批。速度扰动让退场时刻
 * 由发送时间自然错开，槽位释放保持连续。±12% 与文本宽度本身带来的速度差异同量级，
 * 视觉上不可感知。
 */
internal const val SCROLL_SPEED_JITTER = 0.12f

internal fun scrollSpeedJitterFactor(item: DanmakuItem): Float {
    var h = (item.data.dmid?.hashCode() ?: 0) xor
        (item.data.midHash?.hashCode() ?: 0) xor
        (item.data.text.hashCode() * 31) xor
        item.timeMs()
    h *= 0x5BD1E995
    h = h xor (h ushr 15)
    val unit = (h and 0xFFFF) / 65535f
    return 1f + (unit - 0.5f) * 2f * SCROLL_SPEED_JITTER
}

/**
 * 仅保存当前重建窗口内已经真正 activate 过的弹幕，用计数处理同一数据在同一时点的多条实例。
 * 每次重建使用独立预算；消费预算不会改写历史，下一次前进重建仍会继续跳过同一条目。
 */
internal class DanmakuAdmissionHistory {
    private val counts = HashMap<Danmaku, Int>()

    fun record(data: Danmaku) {
        counts[data] = (counts[data] ?: 0) + 1
    }

    /** 该条目（dmid 级判等）是否已在本窗口内真正入场过。供正常入场路径做防重兜底。 */
    fun wasRecorded(data: Danmaku): Boolean = counts.containsKey(data)

    fun clear() {
        counts.clear()
    }

    fun pruneBefore(minimumTimeMs: Int) {
        val iterator = counts.keys.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().timeMs < minimumTimeMs) iterator.remove()
        }
    }

    fun replayBudget(): ReplayBudget = ReplayBudget(HashMap(counts))

    internal class ReplayBudget(private val remaining: MutableMap<Danmaku, Int>) {
        /** Returns true only once for each previously admitted matching instance. */
        fun consume(data: Danmaku): Boolean {
            val count = remaining[data] ?: return false
            if (count <= 1) remaining.remove(data) else remaining[data] = count - 1
            return true
        }
    }
}

/**
 * Direct engine callers may deliver an old segment after a later one. The part
 * inside the active rolling lifetime is deliberately left untouched; changing
 * it would restart visible comments or replay items admitted in this pass.
 */
internal fun resolveOutOfOrderAppendPatchStartMs(
    firstIncomingTimeMs: Int,
    currentPositionMs: Long,
    rollingDurationMs: Int,
): Int = maxOf(
    firstIncomingTimeMs.toLong(),
    currentPositionMs.coerceAtLeast(0L) + rollingDurationMs.coerceAtLeast(0),
).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

/** Builds the replacement suffix for a protected out-of-order append. */
internal fun mergeDanmakuFutureTail(
    existing: List<Danmaku>,
    incoming: List<Danmaku>,
    minTimeMs: Int,
): List<Danmaku> =
    (existing.asSequence() + incoming.asSequence())
        .filter { it.timeMs >= minTimeMs }
        .sortedBy { it.timeMs }
        .toList()
