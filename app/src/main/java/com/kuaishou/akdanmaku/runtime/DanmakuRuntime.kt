/*
 * The MIT License (MIT)
 *
 * Copyright 2021 Kwai, Inc. All rights reserved.
 */

package com.kuaishou.akdanmaku.runtime

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.graphics.withTranslation
import com.kuaishou.akdanmaku.DanmakuConfig
import com.kuaishou.akdanmaku.cache.DrawingCache
import com.kuaishou.akdanmaku.data.DanmakuItem
import com.kuaishou.akdanmaku.data.DanmakuItem.Companion.ROLLING_START_TIME_UNSET
import com.kuaishou.akdanmaku.data.DanmakuItemData
import com.kuaishou.akdanmaku.data.ItemState
import com.kuaishou.akdanmaku.engine.DanmakuContext
import com.kuaishou.akdanmaku.engine.DanmakuEngine
import com.kuaishou.akdanmaku.ext.AkLog as Log
import com.kuaishou.akdanmaku.ext.isOutside
import com.kuaishou.akdanmaku.ext.isLate
import com.kuaishou.akdanmaku.ext.isTimeout
import com.kuaishou.akdanmaku.ui.DanmakuListener
import com.kuaishou.akdanmaku.utils.Fraction
import kotlin.math.max

/**
 * 新弹幕运行时：面向视频播放时间线，而不是面向 ECS 实体。
 *
 * 旧 ECS 会让一条普通滚动弹幕经过 Data/Layout/Cache/Render 多个系统，每帧还要遍历实体和同步组件。
 * 这里把播放态弹幕压成四段流水线：数据窗口 -> 预算准备 -> 轨道布局 -> 帧命令。
 * 普通视频弹幕走这条路径，减少 TV 4K 下 CPU 与主线程压力。
 */
internal class DanmakuRuntime(private val context: DanmakuContext) {

  var listener: DanmakuListener? = null
  var liveMode: Boolean = false
  val cacheHit: Fraction = Fraction(1, 1)

  private val callbackHandler = Handler(Looper.getMainLooper())
  private val comparator = Comparator<DanmakuItem> { a, b -> a.compareTo(b) }

  private val pendingAddItems = ArrayList<DanmakuItem>(128)
  private val sortedItems = ArrayList<DanmakuItem>(2048)
  private val timelineWindow = DanmakuTimelineWindow(sortedItems, comparator, LIVE_HISTORY_MAX) { it.timePosition }
  private val activeStates = ArrayList<ActiveItemState>(256)
  private val waitingStates = ArrayList<ActiveItemState>(128)
  private val stateById = HashMap<Long, ActiveItemState>(512)
  private val rejectedIncrementalStates = ArrayList<ActiveItemState>(8)
  private val measureCandidates = ArrayList<ActiveItemState>(128)
  private val measureQueue = ArrayDeque<MeasureQueueEntry>()
  private val measureEntryPool = ArrayList<MeasureQueueEntry>(256)
  private val statePool = ArrayList<ActiveItemState>(256)
  private val incrementalActiveStates = ArrayList<ActiveItemState>(32)
  private val incrementalCommandBuffer = CommandBuffer(32)

  private val trackLayout = DanmakuTrackLayout()
  private val rejectedMergeController = RuntimeRejectedMergeController()
  private val framePool = RuntimeFramePool(MAX_RUNTIME_FRAME_POOL_SIZE)
  private val drawPaint = Paint().apply {
    isAntiAlias = true
  }

  private var layoutGeneration = -1
  private var measureGeneration = -1
  private var cacheGeneration = -1
  private var visibilityGeneration = -1
  private var layoutProfileTick = 0
  private var loadShedLevel = 0
  private var filterCacheableGeneration = -1
  private var filterResultCacheable = false
  private var fallbackLimitLogTick = 0
  private var lastFrameStallLogAtMs = 0L
  private var lastDrawStallLogAtMs = 0L
  private var lastUpdateTimeMs = DanmakuTimelineJumpPolicy.TIME_UNSET
  private var frameReuseGeneration = 0
  private val frameReuseInvalidationCounts = IntArray(REUSE_INVALIDATE_REASON_COUNT)

  @Volatile
  private var frame: RuntimeFrame? = null
  @Volatile
  private var transitionFrame: RuntimeFrame? = null
  private val pendingReleaseFrames = ArrayList<RuntimeFrame>(3)
  private var holdingItem: DanmakuItem? = null

  fun warmUp() {
    context.cacheManager.warmUp()
  }

  @Synchronized
  fun addItems(items: Collection<DanmakuItem>) {
    pendingAddItems.addAll(items)
  }

  @Synchronized
  fun primeMeasureItems(items: List<DanmakuItem>, maxCount: Int) {
    if (items.isEmpty() || maxCount <= 0) return
    val config = context.config
    var scheduled = 0
    var cacheHits = 0
    var cachePrimed = 0
    val startedAt = SystemClock.elapsedRealtime()
    for (item in items) {
      if (item.state == ItemState.Measuring) continue
      if (item.drawState.isMeasured(config.measureGeneration) && item.state >= ItemState.Measured) {
        if (cachePrimed < MAX_PRIME_CACHE_BUILDS &&
          requestCacheBuildIfNeeded(item, config, CACHE_PRIORITY_VISIBLE)) {
          cachePrimed++
        }
        continue
      }
      val cachedSize = context.cacheManager.getDanmakuSize(item.data)
      if (cachedSize != null) {
        item.drawState.width = cachedSize.width.toFloat()
        item.drawState.height = cachedSize.height.toFloat()
        item.drawState.measureGeneration = config.measureGeneration
        item.state = ItemState.Measured
        item.pendingMeasureGeneration = -1
        cacheHits++
        if (cachePrimed < MAX_PRIME_CACHE_BUILDS &&
          requestCacheBuildIfNeeded(item, config, CACHE_PRIORITY_VISIBLE)) {
          cachePrimed++
        }
        continue
      }
      if (scheduled >= maxCount) break
      // 首窗口提交后先把测量排进缓存线程，减少第一批 action 帧里的集中测量抖动。
      item.state = ItemState.Measuring
      item.pendingMeasureGeneration = config.measureGeneration
      context.cacheManager.requestMeasure(
        item = item,
        displayer = context.displayer,
        config = config,
        priority = CACHE_PRIORITY_VISIBLE,
        buildAfterMeasure = cachePrimed < MAX_PRIME_CACHE_BUILDS
      )
      if (cachePrimed < MAX_PRIME_CACHE_BUILDS) {
        cachePrimed++
      }
      scheduled++
    }
    val costMs = SystemClock.elapsedRealtime() - startedAt
    if (items.size >= 96 || scheduled >= 24 || costMs >= 4L) {
      Log.i(
        DanmakuEngine.TAG,
        "[Runtime] prime measure scheduled=$scheduled cacheHit=$cacheHits cachePrimed=$cachePrimed " +
          "total=${items.size} cost=${costMs}ms"
      )
    }
  }

  @Synchronized
  fun primeMeasureItem(item: DanmakuItem) {
    val config = context.config
    if (item.state == ItemState.Measuring) return
    if (item.drawState.isMeasured(config.measureGeneration) && item.state >= ItemState.Measured) return
    val cachedSize = context.cacheManager.getDanmakuSize(item.data)
    if (cachedSize != null) {
      item.drawState.width = cachedSize.width.toFloat()
      item.drawState.height = cachedSize.height.toFloat()
      item.drawState.measureGeneration = config.measureGeneration
      item.state = ItemState.Measured
      item.pendingMeasureGeneration = -1
      requestCacheBuildIfNeeded(item, config, CACHE_PRIORITY_VISIBLE)
      return
    }
    item.state = ItemState.Measuring
    item.pendingMeasureGeneration = config.measureGeneration
    context.cacheManager.requestMeasure(
      item = item,
      displayer = context.displayer,
      config = config,
      priority = CACHE_PRIORITY_VISIBLE,
      buildAfterMeasure = true
    )
  }

  @Synchronized
  fun addItem(item: DanmakuItem) {
    pendingAddItems.add(item)
  }

  @Synchronized
  fun updateItem(item: DanmakuItem) {
    sortedItems.remove(item)
    pendingAddItems.add(item)
    invalidateFrameReuse(REUSE_INVALIDATE_UPDATE_ITEM)
  }

  @Synchronized
  fun clearAllData() {
    pendingAddItems.clear()
    timelineWindow.clear()
    activeStates.forEach { state ->
      state.item.cacheRecycle()
      state.item.reset()
      recycleState(state)
    }
    waitingStates.forEach { state ->
      state.item.cacheRecycle()
      state.item.reset()
      recycleState(state)
    }
    activeStates.clear()
    waitingStates.clear()
    measureCandidates.clear()
    measureQueue.clear()
    stateById.clear()
    lastUpdateTimeMs = DanmakuTimelineJumpPolicy.TIME_UNSET
    holdingItem = null
    loadShedLevel = 0
    clearTracks()
    releaseFrame(frame)
    releaseFrame(transitionFrame)
    frame = null
    transitionFrame = null
    releaseAllPendingFrames()
  }

  @Synchronized
  fun seekTo(positionMs: Long) {
    val config = context.config
    resetRuntimeWindow(positionMs, config, keepCurrentFrame = true)
    lastUpdateTimeMs = positionMs
  }

  @Synchronized
  fun hold(item: DanmakuItem?) {
    if (item == holdingItem) return
    holdingItem?.unhold()
    holdingItem = item
    item?.hold()
  }

  @Synchronized
  fun update() {
    val startedAt = SystemClock.elapsedRealtime()
    var checkpoint = startedAt
    val releaseProfile = releasePendingFrames()
    val releaseMs = SystemClock.elapsedRealtime() - checkpoint
    checkpoint = SystemClock.elapsedRealtime()
    val syncedItems = syncPendingData()
    val syncMs = SystemClock.elapsedRealtime() - checkpoint

    val config = context.config
    val now = context.timer.currentTimeMs
    checkpoint = SystemClock.elapsedRealtime()
    val timelineReset = resetForTimelineJumpIfNeeded(now, config)
    val timelineResetMs = SystemClock.elapsedRealtime() - checkpoint
    checkpoint = SystemClock.elapsedRealtime()
    if (config.layoutGeneration != layoutGeneration) {
      clearTracks()
      activeStates.forEach { it.item.drawState.layoutGeneration = -1 }
      waitingStates.forEach { it.item.drawState.layoutGeneration = -1 }
      layoutGeneration = config.layoutGeneration
      invalidateFrameReuse(REUSE_INVALIDATE_LAYOUT_GEN)
    }
    if (config.measureGeneration != measureGeneration) {
      activeStates.forEach { state ->
        val it = state.item
        it.state = ItemState.Uninitialized
        it.pendingMeasureGeneration = -1
        it.drawState.recycle()
        enqueueMeasureState(state)
      }
      waitingStates.forEach { state ->
        val it = state.item
        it.state = ItemState.Uninitialized
        it.pendingMeasureGeneration = -1
        it.drawState.recycle()
        enqueueMeasureState(state)
      }
      measureGeneration = config.measureGeneration
      invalidateFrameReuse(REUSE_INVALIDATE_MEASURE_GEN)
    }
    if (config.cacheGeneration != cacheGeneration) {
      activeStates.forEach { it.item.cacheRecycle() }
      waitingStates.forEach { it.item.cacheRecycle() }
      cacheGeneration = config.cacheGeneration
      invalidateFrameReuse(REUSE_INVALIDATE_CACHE_GEN)
    }
    visibilityGeneration = config.visibilityGeneration
    val generationMs = SystemClock.elapsedRealtime() - checkpoint

    checkpoint = SystemClock.elapsedRealtime()
    val expiredItems = removeExpired(now, config)
    val expireMs = SystemClock.elapsedRealtime() - checkpoint
    checkpoint = SystemClock.elapsedRealtime()
    incrementalActiveStates.clear()
    val enqueuedActiveItems = enqueueDueItems(now, config)
    val enqueueMs = SystemClock.elapsedRealtime() - checkpoint
    checkpoint = SystemClock.elapsedRealtime()
    val promotedItems = promoteWaitingItems(now)
    val promoteMs = SystemClock.elapsedRealtime() - checkpoint
    checkpoint = SystemClock.elapsedRealtime()
    val scheduledMeasures = scheduleMeasureActiveItems(config)
    val measureMs = SystemClock.elapsedRealtime() - checkpoint
    checkpoint = SystemClock.elapsedRealtime()
    var reuseMissReason = currentFrameReuseMissReason(
      config = config,
      promotedItems = promotedItems,
      scheduledMeasures = scheduledMeasures
    )
    var canReuseFrame = reuseMissReason == null
    val incrementalProfile = if (!canReuseFrame) {
      tryAppendPromotedCommands(
        now = now,
        config = config,
        promotedItems = promotedItems,
        expiredItems = expiredItems,
        scheduledMeasures = scheduledMeasures,
        reuseMissReason = reuseMissReason
      )
    } else {
      IncrementalFrameProfile.EMPTY
    }
    if (incrementalProfile.applied) {
      reuseMissReason = null
      canReuseFrame = true
    }
    val newFrame = if (canReuseFrame) null else layoutAndBuildFrame(now, config)
    val layoutMs = SystemClock.elapsedRealtime() - checkpoint
    checkpoint = SystemClock.elapsedRealtime()
    var replacedFrame = false
    if (newFrame != null) {
      if (shouldReplaceWithFrame(newFrame)) {
        replaceFrame(newFrame)
        replacedFrame = true
      } else {
        framePool.release(newFrame)
      }
    }
    val frameMs = SystemClock.elapsedRealtime() - checkpoint
    logFrameStallIfNeeded(
      now = now,
      config = config,
      syncedItems = syncedItems,
      enqueuedActiveItems = enqueuedActiveItems,
      promotedItems = promotedItems,
      scheduledMeasures = scheduledMeasures,
      builtCommands = newFrame?.commands?.size ?: -1,
      replacedFrame = replacedFrame,
      reuseMissReason = reuseMissReason,
      incrementalProfile = incrementalProfile
    )

    val cost = SystemClock.elapsedRealtime() - startedAt
    if (cost >= RUNTIME_OVERLOAD_MS) {
      Log.w(
        DanmakuEngine.TAG,
        "[Runtime] update overload cost=${cost}ms release=${releaseMs}ms sync=${syncMs}ms " +
          "jump=${timelineResetMs}ms resetActive=${timelineReset?.activeCount ?: 0} " +
          "resetWaiting=${timelineReset?.waitingCount ?: 0} gen=${generationMs}ms " +
          "expire=${expireMs}ms enqueue=${enqueueMs}ms enqActive=$enqueuedActiveItems " +
          "promote=${promoteMs}ms promoted=$promotedItems " +
          "measure=${measureMs}ms scheduled=$scheduledMeasures layout=${layoutMs}ms frame=${frameMs}ms " +
          "active=${activeStates.size} waiting=${waitingStates.size} draw=${frame?.commands?.size ?: 0} " +
          "built=${newFrame?.commands?.size ?: -1} replaced=${if (replacedFrame) 1 else 0} " +
          "reuse=${if (canReuseFrame) 1 else 0} reuseMiss=${reuseMissReason ?: "none"} " +
          "incr=${if (incrementalProfile.applied) 1 else 0} incrReason=${incrementalProfile.reason} " +
          "incrItems=${incrementalProfile.items} incrCommands=${incrementalProfile.commands} " +
          "incrDropped=${incrementalProfile.droppedItems} " +
          "releaseFrames=${releaseProfile.frames} releaseCaches=${releaseProfile.caches} " +
          "releasePost=${releaseProfile.postMs}ms releaseRef=${releaseProfile.refMs}ms releaseRecycle=${releaseProfile.recycleMs}ms"
      )
    }
  }

  fun draw(canvas: Canvas, onRenderReady: () -> Unit) {
    try {
      val liveFrame = frame
      val fallbackTransitionFrame = transitionFrame
      val currentFrame = liveFrame ?: fallbackTransitionFrame
      val drawingTransitionFrame = liveFrame == null && currentFrame === fallbackTransitionFrame
      val transitionElapsedMs = 0L
      val config = context.config
      if (!config.visibility || currentFrame == null ||
        currentFrame.visibilityGeneration != config.visibilityGeneration) {
        logDrawStallIfNeeded(
          reason = when {
            !config.visibility -> "hidden"
            currentFrame == null -> "noFrame"
            else -> "visibility:${currentFrame.visibilityGeneration}->${config.visibilityGeneration}"
          },
          liveFrame = liveFrame,
          transitionFrame = fallbackTransitionFrame,
          currentFrame = currentFrame,
          drawn = 0,
          skippedOutside = 0,
          commandCount = currentFrame?.commands?.size ?: 0
        )
        return
      }

      var hit = 0
      var fallbackDraws = 0
      var rollingFallbackDraws = 0
      var fixedFallbackDraws = 0
      var fallbackSkipped = 0
      var fallbackCacheMiss = 0
      var fallbackUnmeasured = 0
      var fallbackRendererFailed = 0
      var fallbackCacheBoosts = 0
      var visibleCommandCount = 0
      var skippedOutside = 0
      val now = context.timer.currentTimeMs
      val commandCount = currentFrame.commands.size
      for (index in 0 until commandCount) {
        val item = currentFrame.commands.itemAt(index)
        if (!drawingTransitionFrame && item.isRuntimeOutside(now)) {
          skippedOutside++
          continue
        }
        val fixedCommand = index >= currentFrame.fixedCommandStartIndex
        visibleCommandCount++
        val drawResult = drawCommand(
          canvas = canvas,
          frame = currentFrame,
          commands = currentFrame.commands,
          index = index,
          config = config,
          allowFallback = if (fixedCommand) {
            fixedFallbackDraws < MAX_FIXED_FALLBACK_DRAWS_PER_FRAME
          } else {
            rollingFallbackDraws < MAX_FALLBACK_DRAWS_PER_FRAME
          },
          transitionElapsedMs = transitionElapsedMs
        )
        if (drawResult.cacheHit) {
          hit++
        } else if (drawResult.fallbackDrawn) {
          fallbackDraws++
          if (fixedCommand) {
            fixedFallbackDraws++
          } else {
            rollingFallbackDraws++
          }
        } else {
          fallbackSkipped++
          when (drawResult.skipReason) {
            DrawCommandResult.SKIP_CACHE_MISS -> {
              fallbackCacheMiss++
              if (fallbackCacheBoosts < MAX_FALLBACK_CACHE_BOOSTS_PER_FRAME &&
                boostVisibleCacheIfNeeded(item, config)) {
                fallbackCacheBoosts++
              }
            }
            DrawCommandResult.SKIP_UNMEASURED -> fallbackUnmeasured++
            DrawCommandResult.SKIP_RENDERER_FAILED -> fallbackRendererFailed++
          }
        }
        dispatchShown(item, config)
      }
      if (visibleCommandCount == 0 || fallbackDraws + hit == 0) {
        logDrawStallIfNeeded(
          reason = if (visibleCommandCount == 0) "noVisibleCommand" else "noDrawnCommand",
          liveFrame = liveFrame,
          transitionFrame = fallbackTransitionFrame,
          currentFrame = currentFrame,
          drawn = fallbackDraws + hit,
          skippedOutside = skippedOutside,
          commandCount = commandCount
        )
      }
      if (fallbackSkipped > 0 && shouldLogFallbackLimit()) {
        Log.w(
          DanmakuEngine.TAG,
          "[Runtime] draw fallback limited drawn=$fallbackDraws skipped=$fallbackSkipped " +
            "rollingDrawn=$rollingFallbackDraws fixedDrawn=$fixedFallbackDraws " +
            "cacheMiss=$fallbackCacheMiss unmeasured=$fallbackUnmeasured " +
            "rendererFailed=$fallbackRendererFailed boosts=$fallbackCacheBoosts commands=$commandCount"
        )
      }
      cacheHit.num = hit
      cacheHit.den = visibleCommandCount
    } finally {
      onRenderReady()
    }
  }

  private fun logDrawStallIfNeeded(
    reason: String,
    liveFrame: RuntimeFrame?,
    transitionFrame: RuntimeFrame?,
    currentFrame: RuntimeFrame?,
    drawn: Int,
    skippedOutside: Int,
    commandCount: Int
  ) {
    val elapsed = SystemClock.elapsedRealtime()
    if (elapsed - lastDrawStallLogAtMs < DRAW_STALL_LOG_INTERVAL_MS) return
    lastDrawStallLogAtMs = elapsed
    Log.w(
      DanmakuEngine.TAG,
      "[Runtime] draw stall reason=$reason now=${context.timer.currentTimeMs}ms " +
        "live=${liveFrame?.commands?.size ?: -1} transition=${transitionFrame?.commands?.size ?: -1} " +
        "current=${currentFrame?.commands?.size ?: -1} commands=$commandCount drawn=$drawn " +
        "outside=$skippedOutside active=${activeStates.size} waiting=${waitingStates.size} " +
        "pending=${pendingAddItems.size} sorted=${sortedItems.size} scan=${timelineWindow.scanIndex} " +
        "measureQueue=${measureQueue.size} visible=${context.config.visibility} " +
        "size=${context.displayer.width}x${context.displayer.height}"
    )
  }

  private fun shouldLogFallbackLimit(): Boolean {
    fallbackLimitLogTick++
    return fallbackLimitLogTick == 1 || fallbackLimitLogTick % FALLBACK_LIMIT_LOG_INTERVAL == 0
  }

  fun getDanmakus(point: Point): List<DanmakuItem>? {
    if (!context.config.visibility) return null
    val currentFrame = frame ?: return null
    val x = point.x.toFloat()
    val y = point.y.toFloat()
    val result = ArrayList<DanmakuItem>()
    val commands = currentFrame.commands
    val now = context.timer.currentTimeMs
    for (index in 0 until commands.size) {
      val item = commands.itemAt(index)
      if (item.isRuntimeOutside(now)) continue
      if (x >= commands.leftAt(index) && x <= commands.rightAt(index) &&
        y >= commands.topAt(index) && y <= commands.bottomAt(index)) {
        result.add(item)
      }
    }
    return result
  }

  fun getDanmakus(rect: RectF): List<DanmakuItem>? {
    if (!context.config.visibility) return null
    val currentFrame = frame ?: return null
    val result = ArrayList<DanmakuItem>()
    val commands = currentFrame.commands
    val now = context.timer.currentTimeMs
    for (index in 0 until commands.size) {
      val item = commands.itemAt(index)
      if (item.isRuntimeOutside(now)) continue
      if (rect.left < commands.rightAt(index) && rect.right > commands.leftAt(index) &&
        rect.top < commands.bottomAt(index) && rect.bottom > commands.topAt(index)) {
        result.add(item)
      }
    }
    return result
  }

  fun release() {
    clearAllData()
    context.cacheManager.release()
  }

  private fun syncPendingData(): Int {
    val added = timelineWindow.syncPending(pendingAddItems, liveMode)
    return added
  }

  private fun resetForTimelineJumpIfNeeded(now: Long, config: DanmakuConfig): TimelineResetProfile? {
    val previous = lastUpdateTimeMs
    lastUpdateTimeMs = now
    if (!DanmakuTimelineJumpPolicy.isJump(previous, now, config.durationMs, config.rollingDurationMs)) {
      return null
    }
    val profile = resetRuntimeWindow(now, config, keepCurrentFrame = true)
    Log.i(
      DanmakuEngine.TAG,
      "[Runtime] timeline jump reset previous=${previous}ms now=${now}ms " +
        "active=${profile.activeCount} waiting=${profile.waitingCount} frame=${profile.hadFrame}"
    )
    return profile
  }

  fun clearRuntimeData(keepCurrentFrame: Boolean) {
    val config = context.config
    resetRuntimeWindow(context.timer.currentTimeMs, config, keepCurrentFrame)
    lastUpdateTimeMs = if (keepCurrentFrame) {
      context.timer.currentTimeMs
    } else {
      DanmakuTimelineJumpPolicy.TIME_UNSET
    }
  }

  private fun resetRuntimeWindow(
    positionMs: Long,
    config: DanmakuConfig,
    keepCurrentFrame: Boolean
  ): TimelineResetProfile {
    val activeCount = activeStates.size
    val waitingCount = waitingStates.size
    val hadFrame = frame != null || pendingReleaseFrames.isNotEmpty()
    timelineWindow.reset(positionMs, config.durationMs, config.rollingDurationMs)
    activeStates.forEach { state ->
      val item = state.item
      item.cacheRecycle()
      item.drawState.layoutGeneration = -1
      recycleState(state)
    }
    waitingStates.forEach { state ->
      val item = state.item
      item.cacheRecycle()
      item.drawState.layoutGeneration = -1
      recycleState(state)
    }
    activeStates.clear()
    waitingStates.clear()
    measureCandidates.clear()
    measureQueue.clear()
    stateById.clear()
    loadShedLevel = 0
    clearTracks()
    invalidateFrameReuse(REUSE_INVALIDATE_RESET)
    if (keepCurrentFrame) {
      promoteCurrentFrameToTransition()
    } else {
      releaseFrame(frame)
      releaseFrame(transitionFrame)
      transitionFrame = null
    }
    frame = null
    releaseAllPendingFrames()
    return TimelineResetProfile(activeCount, waitingCount, hadFrame)
  }

  private fun currentFrameReuseMissReason(
    config: DanmakuConfig,
    promotedItems: Int,
    scheduledMeasures: Int
  ): String? {
    val currentFrame = frame ?: return "noFrame"
    if (transitionFrame != null) return "transition"
    if (promotedItems != 0) return "promoted:$promotedItems"
    if (scheduledMeasures != 0) return "scheduledMeasure:$scheduledMeasures"
    if (currentFrame.visibilityGeneration != visibilityGeneration) {
      return "visibility:${currentFrame.visibilityGeneration}->$visibilityGeneration"
    }
    if (currentFrame.reuseGeneration != frameReuseGeneration) {
      return "reuseGen:${currentFrame.reuseGeneration}->$frameReuseGeneration:${frameReuseInvalidationSummary()}"
    }
    if (currentFrame.activeCount != activeStates.size &&
      !canReuseWithExpiredCommands(currentFrame, promotedItems = 0)) {
      return "active:${currentFrame.activeCount}->${activeStates.size}"
    }
    if (measureQueue.isNotEmpty()) return "measureQueue:${measureQueue.size}"
    if (currentFrame.layoutGeneration != config.layoutGeneration) {
      return "layoutGen:${currentFrame.layoutGeneration}->${config.layoutGeneration}"
    }
    if (currentFrame.measureGeneration != config.measureGeneration) {
      return "measureGen:${currentFrame.measureGeneration}->${config.measureGeneration}"
    }
    if (currentFrame.cacheGeneration != config.cacheGeneration) {
      return "cacheGen:${currentFrame.cacheGeneration}->${config.cacheGeneration}"
    }
    if (currentFrame.filterGeneration != config.filterGeneration) {
      return "filterGen:${currentFrame.filterGeneration}->${config.filterGeneration}"
    }
    if (currentFrame.renderGeneration != config.renderGeneration) {
      return "renderGen:${currentFrame.renderGeneration}->${config.renderGeneration}"
    }
    return null
  }

  private fun removeExpired(now: Long, config: DanmakuConfig): Int {
    val iterator = activeStates.iterator()
    var expired = 0
    while (iterator.hasNext()) {
      val state = iterator.next()
      val item = state.item
      item.duration = if (item.data.mode == DanmakuItemData.DANMAKU_MODE_ROLLING) {
        config.rollingDurationMs
      } else {
        config.durationMs
      }
      if (!item.isHolding && item.isRuntimeTimeout(now)) {
        stateById.remove(item.data.danmakuId)
        removeFromTracks(item)
        item.cacheRecycle()
        iterator.remove()
        recycleState(state)
        expired++
      }
    }
    return expired
  }

  private fun enqueueDueItems(now: Long, config: DanmakuConfig): Int {
    var activeAdded = 0
    val startedAt = SystemClock.elapsedRealtime()
    val enqueueBudget = DanmakuLoadShedder.enqueueBudget(loadShedLevel)
    timelineWindow.enqueueDue(
      nowMs = now,
      durationMs = config.durationMs,
      rollingDurationMs = config.rollingDurationMs,
      entryAheadMs = entryAheadMs(config),
      enqueueBudget = enqueueBudget,
      shouldSkipItem = { DanmakuLoadShedder.shouldSkipItem(loadShedLevel) },
      shouldStopAfterAdded = { addedCount ->
        addedCount >= MIN_ENQUEUE_PER_FRAME &&
          SystemClock.elapsedRealtime() - startedAt >= ENQUEUE_BUDGET_MS
      }
    ) { item ->
      if (!stateById.containsKey(item.data.danmakuId)) {
        item.duration = if (item.data.mode == DanmakuItemData.DANMAKU_MODE_ROLLING) {
          config.rollingDurationMs
        } else {
          config.durationMs
        }
        item.drawState.layoutGeneration = -1
        item.rollingStartTimeMs = ROLLING_START_TIME_UNSET
        item.rollingMotionWidth = 0f
        val state = acquireState(item)
        enqueueMeasureState(state)
        if (item.timePosition <= now) {
          activeStates.add(state)
          incrementalActiveStates.add(state)
          activeAdded++
          invalidateFrameReuse(REUSE_INVALIDATE_ENQUEUE)
        } else {
          waitingStates.add(state)
        }
        stateById[item.data.danmakuId] = state
        true
      } else {
        false
      }
    }
    return activeAdded
  }

  private fun promoteWaitingItems(now: Long): Int {
    if (waitingStates.isEmpty()) return 0
    var promoteCount = 0
    while (promoteCount < waitingStates.size && waitingStates[promoteCount].item.timePosition <= now) {
      promoteCount++
    }
    if (promoteCount == 0) return 0
    repeat(promoteCount) {
      val state = waitingStates[it]
      activeStates.add(state)
      incrementalActiveStates.add(state)
    }
    waitingStates.subList(0, promoteCount).clear()
    invalidateFrameReuse(REUSE_INVALIDATE_PROMOTE)
    return promoteCount
  }

  private fun scheduleMeasureActiveItems(config: DanmakuConfig): Int {
    var scheduled = 0
    var cacheHits = 0
    var scanned = 0
    val startedAt = SystemClock.elapsedRealtime()
    measureCandidates.clear()
    while (measureQueue.isNotEmpty() && scanned < MAX_MEASURE_QUEUE_SCAN_PER_FRAME) {
      val entry = measureQueue.removeFirst()
      val state = entry.state
      val token = entry.token
      recycleMeasureEntry(entry)
      if (!state.inMeasureQueue || state.measureQueueToken != token) continue
      state.inMeasureQueue = false
      scanned++
      val item = state.item
      if (item == DanmakuItem.DANMAKU_ITEM_EMPTY) continue
      if (item.state == ItemState.Measuring) continue
      if (item.drawState.isMeasured(config.measureGeneration) && item.state >= ItemState.Measured) {
        state.awaitingMeasure = false
        continue
      }
      if (item.timePosition > context.timer.currentTimeMs + entryAheadMs(config)) {
        enqueueMeasureState(state)
        break
      }
      measureCandidates.add(state)
      if (measureCandidates.size >= MAX_MEASURE_CANDIDATES_PER_FRAME) break
    }
    val candidates = DanmakuMeasureScheduler.collectCandidates(
      items = measureCandidates,
      nowMs = context.timer.currentTimeMs,
      measureGeneration = config.measureGeneration,
      maxCount = MAX_MEASURE_PER_FRAME,
      scheduleAheadMs = entryAheadMs(config)
    )
    for (candidate in candidates) {
      if (SystemClock.elapsedRealtime() - startedAt >= MEASURE_SCHEDULE_BUDGET_MS) break
      val item = candidate.item
      val cachedSize = context.cacheManager.getDanmakuSize(item.data)
      if (cachedSize != null) {
        if (cacheHits >= MAX_MEASURE_CACHE_HITS_PER_FRAME) {
          enqueueMeasureState(candidate)
          continue
        }
        item.drawState.width = cachedSize.width.toFloat()
        item.drawState.height = cachedSize.height.toFloat()
        item.drawState.measureGeneration = config.measureGeneration
        item.state = ItemState.Measured
        item.pendingMeasureGeneration = -1
        candidate.awaitingMeasure = false
        cacheHits++
        continue
      }
      // 测量可能触发字体、描边和样式初始化，放到缓存线程，避免 action 线程一帧吃掉几百毫秒。
      item.state = ItemState.Measuring
      item.pendingMeasureGeneration = config.measureGeneration
      context.cacheManager.requestMeasure(
        item = item,
        displayer = context.displayer,
        config = config,
        priority = candidate.cachePriority(context.timer.currentTimeMs, entryAheadMs(config))
      )
      scheduled++
    }
    return scheduled
  }

  private fun entryAheadMs(config: DanmakuConfig): Long =
    max(PREPARE_AHEAD_MS, config.preCacheTimeMs)

  private fun tryAppendPromotedCommands(
    now: Long,
    config: DanmakuConfig,
    promotedItems: Int,
    expiredItems: Int,
    scheduledMeasures: Int,
    reuseMissReason: String?
  ): IncrementalFrameProfile {
    val incrementalItems = incrementalActiveStates.size
    if (incrementalItems <= 0) {
      return IncrementalFrameProfile.notApplied("noPromoted")
    }
    if (incrementalItems > MAX_INCREMENTAL_PROMOTED_PER_FRAME) {
      return IncrementalFrameProfile.notApplied("tooMany:$incrementalItems")
    }
    if (scheduledMeasures != 0) {
      return IncrementalFrameProfile.notApplied("scheduledMeasure:$scheduledMeasures")
    }
    if (transitionFrame != null) {
      return IncrementalFrameProfile.notApplied("transition")
    }
    val currentFrame = frame ?: return IncrementalFrameProfile.notApplied("noFrame")
    if (reuseMissReason != "promoted:$promotedItems" &&
      reuseMissReason?.startsWith("reuseGen:") != true) {
      return IncrementalFrameProfile.notApplied(reuseMissReason ?: "reuse")
    }
    if (expiredItems > 0) {
      currentFrame.pruneRuntimeOutsideCommands(now) { item, commandNow ->
        item.isRuntimeOutside(commandNow)
      }
    }
    val baseReuseMissReason = frameBaseReuseMissReason(currentFrame, config, incrementalItems)
    if (baseReuseMissReason != null) {
      return IncrementalFrameProfile.notApplied("baseChanged:$baseReuseMissReason")
    }

    incrementalCommandBuffer.clear()
    val incrementalFixedCommandBuffer = currentFrame.fixedCommands
    incrementalFixedCommandBuffer.clear()
    val displayer = context.displayer
    val width = displayer.width
    val height = displayer.height
    val margin = displayer.margin
    val cacheGeneration = config.cacheGeneration
    var appended = 0
    var fixedAppended = 0
    var cacheRequested = 0
    var cacheBudget = MAX_INCREMENTAL_CACHE_REQUESTS_PER_FRAME
    var dropped = 0
    rejectedIncrementalStates.clear()
    for (state in incrementalActiveStates) {
      val item = state.item
      if (item.isRuntimeOutside(now)) {
        incrementalCommandBuffer.clear()
        incrementalFixedCommandBuffer.clear()
        rejectedIncrementalStates.clear()
        return IncrementalFrameProfile.notApplied("outside")
      }
      val drawState = item.drawState
      if (item.state < ItemState.Measured || !drawState.isMeasured(config.measureGeneration)) {
        enqueueMeasureState(state)
        incrementalCommandBuffer.clear()
        incrementalFixedCommandBuffer.clear()
        rejectedIncrementalStates.clear()
        return IncrementalFrameProfile.notApplied("unmeasured")
      }
      state.awaitingMeasure = false
      if (isDataFiltered(item, config)) {
        continue
      }
      val visible = if (drawState.layoutGeneration == config.layoutGeneration) {
        trackLayout.updateExisting(item, width, height, config) ||
          trackLayout.layout(item, now, width, height, margin, config)
      } else {
        trackLayout.layout(item, now, width, height, margin, config)
      }
      if (!visible) {
        if (rejectedMergeController.tryMergeRejected(item, config, now)) {
          rejectedIncrementalStates.add(state)
          dropped++
          continue
        }
        if (DanmakuRejectionPolicy.shouldDropRejectedItem(item)) {
          rejectedIncrementalStates.add(state)
          dropped++
          continue
        }
        incrementalCommandBuffer.clear()
        incrementalFixedCommandBuffer.clear()
        rejectedIncrementalStates.clear()
        return IncrementalFrameProfile.notApplied("trackRejectedHolding")
      }
      if (cacheBudget > 0 &&
        requestCacheBuildIfNeeded(item, config, state.cachePriority(now, entryAheadMs(config)))) {
        cacheBudget--
        cacheRequested++
      }
      if (item.state >= ItemState.Rendered && drawState.cacheGeneration != cacheGeneration) {
        item.cacheRecycle()
      }
      val cache = drawState.drawingCache
      if (cache != DrawingCache.EMPTY_DRAWING_CACHE) {
        currentFrame.retainCache(cache)
      }
      val mode = item.data.mode
      val fixedMode = mode == DanmakuItemData.DANMAKU_MODE_CENTER_TOP ||
        mode == DanmakuItemData.DANMAKU_MODE_CENTER_BOTTOM
      val left = if (fixedMode) {
        drawState.positionX
      } else {
        rollingLeft(item, now, width, config.rollingDurationMs)
      }
      val top = drawState.positionY
      val targetBuffer = if (fixedMode) incrementalFixedCommandBuffer else incrementalCommandBuffer
      targetBuffer.add(
          item = item,
          cache = cache,
          cacheGeneration = drawState.cacheGeneration,
          left = left,
          top = top,
          right = left + drawState.width,
          bottom = top + drawState.height
        )
      if (fixedMode) {
        fixedAppended++
      } else {
        appended++
      }
      rejectedMergeController.registerVisible(item, config, now)
    }
    dropRejectedIncrementalStates()
    if (appended == 0 && fixedAppended == 0) {
      markFrameReuseState(currentFrame, config)
      clearFrameReuseInvalidationReason()
      return IncrementalFrameProfile(
        applied = true,
        reason = if (dropped > 0) "promotedDropped" else "filtered",
        items = incrementalItems,
        commands = 0,
        droppedItems = dropped
      )
    }
    currentFrame.appendRollingCommands(incrementalCommandBuffer)
    currentFrame.appendFixedCommands(incrementalFixedCommandBuffer)
    incrementalCommandBuffer.clear()
    incrementalFixedCommandBuffer.clear()
    markFrameReuseState(currentFrame, config)
    clearFrameReuseInvalidationReason()
    return IncrementalFrameProfile(
      applied = true,
      reason = if (dropped > 0) "promotedDropped" else "promoted",
      items = incrementalItems,
      commands = appended + fixedAppended,
      cacheRequests = cacheRequested,
      droppedItems = dropped
    )
  }

  private fun frameBaseReuseMissReason(
    currentFrame: RuntimeFrame,
    config: DanmakuConfig,
    promotedItems: Int
  ): String? {
    if (currentFrame.visibilityGeneration != visibilityGeneration) {
      return "visibility:${currentFrame.visibilityGeneration}->$visibilityGeneration"
    }
    if (!canReuseWithExpiredCommands(currentFrame, promotedItems)) {
      return "active:${currentFrame.activeCount}+${promotedItems}->${activeStates.size}"
    }
    if (currentFrame.layoutGeneration != config.layoutGeneration) {
      return "layoutGen:${currentFrame.layoutGeneration}->${config.layoutGeneration}"
    }
    if (currentFrame.measureGeneration != config.measureGeneration) {
      return "measureGen:${currentFrame.measureGeneration}->${config.measureGeneration}"
    }
    if (currentFrame.cacheGeneration != config.cacheGeneration) {
      return "cacheGen:${currentFrame.cacheGeneration}->${config.cacheGeneration}"
    }
    if (currentFrame.filterGeneration != config.filterGeneration) {
      return "filterGen:${currentFrame.filterGeneration}->${config.filterGeneration}"
    }
    if (currentFrame.renderGeneration != config.renderGeneration) {
      return "renderGen:${currentFrame.renderGeneration}->${config.renderGeneration}"
    }
    if (!isOnlyIncrementalActiveInvalidation()) {
      return "reuseInvalid:${frameReuseInvalidationSummary()}"
    }
    return null
  }

  private fun layoutAndBuildFrame(now: Long, config: DanmakuConfig): RuntimeFrame {
    val displayer = context.displayer
    val newFrame = framePool.acquire(visibilityGeneration)
    val startedAt = SystemClock.elapsedRealtime()
    val profileDetails = shouldProfileLayoutFrame()
    var filterMs = 0L
    var trackMs = 0L
    var cacheMs = 0L
    var commandMs = 0L
    var retainMs = 0L
    var fixedMergeMs = 0L
    var outsideCheckMs = 0L
    var measureCheckMs = 0L
    var filteredCount = 0
    var unmeasuredCount = 0
    var outsideCount = 0
    var trackRejectedCount = 0
    var trackFastCount = 0
    var trackLayoutCount = 0
    var cacheRequestedCount = 0
    var cacheDeferredCount = 0
    var cacheRequestBudget = MAX_CACHE_REQUESTS_PER_FRAME
    val width = displayer.width
    val height = displayer.height
    val margin = displayer.margin
    val layoutGeneration = config.layoutGeneration
    val measureGeneration = config.measureGeneration
    val cacheGeneration = config.cacheGeneration
    val rollingDurationMs = config.rollingDurationMs
    val topMode = DanmakuItemData.DANMAKU_MODE_CENTER_TOP
    val bottomMode = DanmakuItemData.DANMAKU_MODE_CENTER_BOTTOM
    val iterator = activeStates.iterator()
    while (iterator.hasNext()) {
      val state = iterator.next()
      val item = state.item
      var stepAt = if (profileDetails) SystemClock.elapsedRealtime() else 0L
      if (item.isRuntimeOutside(now)) {
        if (profileDetails) outsideCheckMs += SystemClock.elapsedRealtime() - stepAt
        outsideCount++
        continue
      }
      if (profileDetails) outsideCheckMs += SystemClock.elapsedRealtime() - stepAt

      stepAt = if (profileDetails) SystemClock.elapsedRealtime() else 0L
      val drawState = item.drawState
      if (item.state < ItemState.Measured || !drawState.isMeasured(measureGeneration)) {
        if (profileDetails) measureCheckMs += SystemClock.elapsedRealtime() - stepAt
        enqueueMeasureState(state)
        unmeasuredCount++
        continue
      }
      if (profileDetails) measureCheckMs += SystemClock.elapsedRealtime() - stepAt
      state.awaitingMeasure = false
      stepAt = if (profileDetails) SystemClock.elapsedRealtime() else 0L
      if (isDataFiltered(item, config)) {
        if (profileDetails) filterMs += SystemClock.elapsedRealtime() - stepAt
        filteredCount++
        continue
      }
      if (profileDetails) filterMs += SystemClock.elapsedRealtime() - stepAt

      stepAt = if (profileDetails) SystemClock.elapsedRealtime() else 0L
      val mode = item.data.mode
      val visible = if (drawState.layoutGeneration == layoutGeneration) {
        val updated = trackLayout.updateExisting(item, width, height, config)
        if (updated) {
          trackFastCount++
          true
        } else {
          trackLayoutCount++
          trackLayout.layout(item, now, width, height, margin, config)
        }
      } else {
        trackLayoutCount++
        trackLayout.layout(item, now, width, height, margin, config)
      }
      if (profileDetails) trackMs += SystemClock.elapsedRealtime() - stepAt
      if (!visible) {
        trackRejectedCount++
        if (rejectedMergeController.tryMergeRejected(item, config, now)) {
          stateById.remove(item.data.danmakuId)
          iterator.remove()
          dropActiveState(state)
          invalidateFrameReuse(REUSE_INVALIDATE_DROP_REJECTED)
        } else if (DanmakuRejectionPolicy.shouldDropRejectedItem(item)) {
          stateById.remove(item.data.danmakuId)
          iterator.remove()
          dropActiveState(state)
          invalidateFrameReuse(REUSE_INVALIDATE_DROP_REJECTED)
        }
        continue
      }
      if (cacheRequestBudget > 0) {
        stepAt = if (profileDetails) SystemClock.elapsedRealtime() else 0L
        val requested = requestCacheBuildIfNeeded(item, config, state.cachePriority(now, entryAheadMs(config)))
        if (profileDetails) cacheMs += SystemClock.elapsedRealtime() - stepAt
        if (requested) {
          cacheRequestBudget--
          cacheRequestedCount++
        }
      } else if (item.state < ItemState.Rendering) {
        // 首屏窗口可能瞬间出现十几条弹幕，缓存构建分帧排队；未排到的先走直接绘制兜底。
        cacheDeferredCount++
      }
      if (item.state >= ItemState.Rendered && drawState.cacheGeneration != cacheGeneration) {
        item.cacheRecycle()
      }
      stepAt = if (profileDetails) SystemClock.elapsedRealtime() else 0L
      val cache = drawState.drawingCache
      if (cache != DrawingCache.EMPTY_DRAWING_CACHE) {
        stepAt = if (profileDetails) SystemClock.elapsedRealtime() else 0L
        newFrame.retainCache(cache)
        if (profileDetails) retainMs += SystemClock.elapsedRealtime() - stepAt
      }
      stepAt = if (profileDetails) SystemClock.elapsedRealtime() else 0L
      val left = if (mode == topMode || mode == bottomMode) {
        drawState.positionX
      } else {
        rollingLeft(item, now, width, rollingDurationMs)
      }
      val top = drawState.positionY
      val right = left + drawState.width
      val bottom = top + drawState.height
      if (mode == topMode || mode == bottomMode) {
        // 悬停弹幕始终最后绘制，保证层级压在滚动弹幕上方。
        newFrame.fixedCommands.add(item, cache, drawState.cacheGeneration, left, top, right, bottom)
      } else {
        newFrame.commands.add(item, cache, drawState.cacheGeneration, left, top, right, bottom)
      }
      rejectedMergeController.registerVisible(item, config, now)
      if (profileDetails) commandMs += SystemClock.elapsedRealtime() - stepAt
    }
    val fixedMergeStartedAt = if (profileDetails) SystemClock.elapsedRealtime() else 0L
    newFrame.markFixedCommandStart(newFrame.commands.size)
    newFrame.commands.addAll(newFrame.fixedCommands)
    newFrame.fixedCommands.clear()
    if (profileDetails) fixedMergeMs = SystemClock.elapsedRealtime() - fixedMergeStartedAt
    val cost = SystemClock.elapsedRealtime() - startedAt
    markFrameReuseState(newFrame, config)
    clearFrameReuseInvalidationReason()
    updateLoadShedLevel(
      layoutCostMs = cost,
      rejectedCount = trackRejectedCount,
      unmeasuredCount = unmeasuredCount
    )
    if (cost >= LAYOUT_OVERLOAD_MS) {
      Log.w(
        DanmakuEngine.TAG,
        "[Runtime] layout profile cost=${cost}ms outside=${outsideCheckMs}ms measureCheck=${measureCheckMs}ms " +
          "filter=${filterMs}ms track=${trackMs}ms cache=${cacheMs}ms retain=${retainMs}ms " +
          "command=${commandMs}ms fixedMerge=${fixedMergeMs}ms active=${activeStates.size} waiting=${waitingStates.size} " +
          "draw=${newFrame.commands.size} outside=$outsideCount " +
          "unmeasured=$unmeasuredCount filtered=$filteredCount rejected=$trackRejectedCount " +
          "trackFast=$trackFastCount trackLayout=$trackLayoutCount cacheReq=$cacheRequestedCount cacheDef=$cacheDeferredCount"
      )
    }
    return newFrame
  }

  private fun rollingLeft(
    item: DanmakuItem,
    nowMs: Long,
    screenWidth: Int,
    durationMs: Long
  ): Float {
    val startTime = RollingDanmakuTiming.resolvedStartTime(item.rollingStartTimeMs, item.timePosition)
    return RollingDanmakuTiming.positionX(
      screenWidth = screenWidth,
      itemWidth = item.rollingMotionWidth.takeIf { it > 0f } ?: item.drawState.width,
      nowMs = nowMs,
      startTimeMs = startTime,
      durationMs = durationMs
    )
  }

  private fun isDataFiltered(item: DanmakuItem, config: DanmakuConfig): Boolean {
    val filters = context.filter
    if (filterCacheableGeneration != config.filterGeneration) {
      filterCacheableGeneration = config.filterGeneration
      filterResultCacheable = filters.isDataFilterResultCacheable
    }
    if (!filterResultCacheable) {
      return filters.isDataFiltered(item, context.timer, config)
    }
    if (item.filterGeneration == config.filterGeneration) {
      return item.filteredInGeneration
    }
    val filtered = filters.isDataFiltered(item, context.timer, config)
    item.filterGeneration = config.filterGeneration
    item.filteredInGeneration = filtered
    return filtered
  }

  private fun requestCacheBuildIfNeeded(
    item: DanmakuItem,
    config: DanmakuConfig,
    priority: Int
  ): Boolean {
    val drawState = item.drawState
    if (!drawState.isMeasured(config.measureGeneration)) return false
    if (item.state >= ItemState.Rendering &&
      item.pendingCacheGeneration == config.cacheGeneration) return false
    if (item.state >= ItemState.Rendered &&
      drawState.cacheGeneration == config.cacheGeneration &&
      drawState.drawingCache != DrawingCache.EMPTY_DRAWING_CACHE &&
      drawState.drawingCache.get() != null) return false
    item.state = ItemState.Rendering
    item.pendingCacheGeneration = config.cacheGeneration
    context.cacheManager.requestBuildCache(
      item = item,
      displayer = context.displayer,
      config = config,
      priority = priority
    )
    return true
  }

  private fun logFrameStallIfNeeded(
    now: Long,
    config: DanmakuConfig,
    syncedItems: Int,
    enqueuedActiveItems: Int,
    promotedItems: Int,
    scheduledMeasures: Int,
    builtCommands: Int,
    replacedFrame: Boolean,
    reuseMissReason: String?,
    incrementalProfile: IncrementalFrameProfile
  ) {
    val commandCount = frame?.commands?.size ?: 0
    if (commandCount > 0) return
    if (activeStates.isEmpty() && waitingStates.isEmpty() && pendingAddItems.isEmpty()) return
    val elapsed = SystemClock.elapsedRealtime()
    if (elapsed - lastFrameStallLogAtMs < FRAME_STALL_LOG_INTERVAL_MS) return
    lastFrameStallLogAtMs = elapsed

    var uninitialized = 0
    var measuring = 0
    var measuredOrAbove = 0
    var error = 0
    fun countState(state: ActiveItemState) {
      when (state.item.state) {
        ItemState.Uninitialized -> uninitialized++
        ItemState.Measuring -> measuring++
        ItemState.Error -> error++
        else -> measuredOrAbove++
      }
    }
    activeStates.forEach(::countState)
    waitingStates.forEach(::countState)
    val displayer = context.displayer
    Log.w(
      DanmakuEngine.TAG,
      "[Runtime] frame stall now=${now}ms active=${activeStates.size} waiting=${waitingStates.size} " +
        "pending=${pendingAddItems.size} sorted=${sortedItems.size} scan=${timelineWindow.scanIndex} " +
        "commands=$commandCount built=$builtCommands replaced=${if (replacedFrame) 1 else 0} " +
        "synced=$syncedItems enqActive=$enqueuedActiveItems promoted=$promotedItems " +
        "scheduledMeasure=$scheduledMeasures measureQueue=${measureQueue.size} " +
        "stateU=$uninitialized stateM=$measuring stateReady=$measuredOrAbove stateErr=$error " +
        "reuseMiss=${reuseMissReason ?: "none"} incr=${if (incrementalProfile.applied) 1 else 0} " +
        "incrReason=${incrementalProfile.reason} incrItems=${incrementalProfile.items} " +
        "incrCommands=${incrementalProfile.commands} loadShed=$loadShedLevel " +
        "visible=${config.visibility} size=${displayer.width}x${displayer.height} screenPart=${config.screenPart}"
    )
  }

  private fun drawCommand(
    canvas: Canvas,
    frame: RuntimeFrame,
    commands: CommandBuffer,
    index: Int,
    config: DanmakuConfig,
    allowFallback: Boolean,
    transitionElapsedMs: Long
  ): DrawCommandResult {
    val item = commands.itemAt(index)
    val drawState = item.drawState
    if (item.state < ItemState.Measured || !drawState.isMeasured(config.measureGeneration)) {
      return DrawCommandResult.UNMEASURED_SKIPPED
    }
    val cache = resolveCommandCache(frame, commands, index, item, config)
    val left = resolveCommandLeft(commands, index, config, transitionElapsedMs)
    val top = commands.topAt(index)
    if (cache != DrawingCache.EMPTY_DRAWING_CACHE) {
      val bitmap = cache.get()?.bitmap
      if (bitmap != null && !bitmap.isRecycled) {
        drawPaint.alpha = (config.alpha * 255).toInt().coerceIn(0, 255)
        canvas.drawBitmap(bitmap, left, top, drawPaint)
        return DrawCommandResult.CACHE_HIT
      }
    }
    if (!allowFallback) return DrawCommandResult.CACHE_MISS_SKIPPED
    var drawn = false
    canvas.withTranslation(left, top) {
      drawn = context.tryDrawRenderer(item, canvas, context.displayer, config)
    }
    return if (drawn) DrawCommandResult.FALLBACK_DRAWN else DrawCommandResult.RENDERER_FAILED_SKIPPED
  }

  private fun boostVisibleCacheIfNeeded(item: DanmakuItem, config: DanmakuConfig): Boolean {
    val drawState = item.drawState
    if (!drawState.isMeasured(config.measureGeneration)) return false
    if (item.state >= ItemState.Rendered &&
      drawState.cacheGeneration == config.cacheGeneration &&
      drawState.drawingCache != DrawingCache.EMPTY_DRAWING_CACHE &&
      drawState.drawingCache.get() != null) return false
    if (item.state >= ItemState.Rendering &&
      item.pendingCacheGeneration == config.cacheGeneration) {
      context.cacheManager.requestBuildCache(
        item = item,
        displayer = context.displayer,
        config = config,
        priority = CACHE_PRIORITY_VISIBLE
      )
      return true
    }
    return requestCacheBuildIfNeeded(item, config, CACHE_PRIORITY_VISIBLE)
  }

  private fun resolveCommandCache(
    frame: RuntimeFrame,
    commands: CommandBuffer,
    index: Int,
    item: DanmakuItem,
    config: DanmakuConfig
  ): DrawingCache {
    val commandCache = commands.cacheAt(index)
    if (commandCache != DrawingCache.EMPTY_DRAWING_CACHE &&
      commands.cacheGenerationAt(index) == config.cacheGeneration) {
      return commandCache
    }
    val drawState = item.drawState
    val latestCache = drawState.drawingCache
    if (latestCache == DrawingCache.EMPTY_DRAWING_CACHE ||
      drawState.cacheGeneration != config.cacheGeneration ||
      latestCache.get() == null) {
      return DrawingCache.EMPTY_DRAWING_CACHE
    }
    frame.retainCache(latestCache)
    return latestCache
  }

  private fun resolveCommandLeft(
    commands: CommandBuffer,
    index: Int,
    config: DanmakuConfig,
    transitionElapsedMs: Long
  ): Float {
    val left = commands.leftAt(index)
    val item = commands.itemAt(index)
    if (item.data.mode != DanmakuItemData.DANMAKU_MODE_ROLLING) return left
    val durationMs = item.duration.takeIf { it > 0L } ?: config.rollingDurationMs
    if (durationMs <= 0L) return left
    return if (transitionElapsedMs > 0L) {
      val width = commands.rightAt(index) - left
      val distance = context.displayer.width + width
      left - transitionElapsedMs.toFloat() / durationMs * distance
    } else {
      rollingLeft(item, context.timer.currentTimeMs, context.displayer.width, durationMs)
    }
  }

  private fun dispatchShown(item: DanmakuItem, config: DanmakuConfig) {
    val target = listener ?: return
    if (item.shownGeneration == config.firstShownGeneration) return
    item.shownGeneration = config.firstShownGeneration
    callbackHandler.post { target.onDanmakuShown(item) }
  }

  private fun replaceFrame(newFrame: RuntimeFrame) {
    frame?.let { pendingReleaseFrames.add(it) }
    transitionFrame?.let { pendingReleaseFrames.add(it) }
    transitionFrame = null
    frame = newFrame
  }

  private fun shouldReplaceWithFrame(newFrame: RuntimeFrame): Boolean {
    if (newFrame.commands.size > 0) return true
    val liveFrame = frame
    if (liveFrame != null && liveFrame.commands.size > 0 &&
      (activeStates.isNotEmpty() || waitingStates.isNotEmpty())) {
      return false
    }
    val transition = transitionFrame
    if (transition != null && transition.isTransitionAlive() &&
      transition.commands.size > 0 && (activeStates.isNotEmpty() || waitingStates.isNotEmpty())) {
      return false
    }
    return transitionFrame == null
  }

  private fun promoteCurrentFrameToTransition() {
    val current = frame ?: return
    current.markTransition(SystemClock.elapsedRealtime())
    transitionFrame?.let { pendingReleaseFrames.add(it) }
    transitionFrame = current
  }

  private fun releasePendingFrames(): ReleaseProfile {
    if (pendingReleaseFrames.isEmpty()) return ReleaseProfile.EMPTY
    val startedAt = SystemClock.elapsedRealtime()
    var frameCount = 0
    var cacheCount = 0
    var releaseRefMs = 0L
    var recycleMs = 0L
    for (oldFrame in pendingReleaseFrames) {
      frameCount++
      cacheCount += oldFrame.retainedCaches.size
      val releaseStartedAt = SystemClock.elapsedRealtime()
      val retainedCaches = oldFrame.detachRetainedCaches()
      context.cacheManager.releaseReferenceSnapshot(retainedCaches, FRAME_CACHE_RELEASE_DELAY_MS)
      releaseRefMs += SystemClock.elapsedRealtime() - releaseStartedAt
      val recycleStartedAt = SystemClock.elapsedRealtime()
      framePool.release(oldFrame)
      recycleMs += SystemClock.elapsedRealtime() - recycleStartedAt
    }
    pendingReleaseFrames.clear()
    val postMs = SystemClock.elapsedRealtime() - startedAt
    return ReleaseProfile(frameCount, cacheCount, postMs, releaseRefMs, recycleMs)
  }

  private fun releaseAllPendingFrames() {
    if (pendingReleaseFrames.isEmpty()) return
    for (oldFrame in pendingReleaseFrames) {
      releaseFrame(oldFrame, delayMs = 0L)
    }
    pendingReleaseFrames.clear()
  }

  private fun releaseFrame(oldFrame: RuntimeFrame?, delayMs: Long = 0L) {
    oldFrame ?: return
    context.cacheManager.releaseReferenceSnapshot(oldFrame.detachRetainedCaches(), delayMs)
    framePool.release(oldFrame)
  }

  private fun shouldProfileLayoutFrame(): Boolean {
    layoutProfileTick++
    return layoutProfileTick == 1 || layoutProfileTick % LAYOUT_PROFILE_DETAIL_INTERVAL == 0
  }

  private class ReleaseProfile(
    val frames: Int,
    val caches: Int,
    val postMs: Long,
    val refMs: Long,
    val recycleMs: Long
  ) {
    companion object {
      val EMPTY = ReleaseProfile(0, 0, 0L, 0L, 0L)
    }
  }

  private class TimelineResetProfile(
    val activeCount: Int,
    val waitingCount: Int,
    val hadFrame: Boolean
  )

  private class IncrementalFrameProfile(
    val applied: Boolean,
    val reason: String,
    val items: Int,
    val commands: Int,
    val cacheRequests: Int = 0,
    val droppedItems: Int = 0
  ) {
    companion object {
      val EMPTY = IncrementalFrameProfile(false, "none", 0, 0)

      fun notApplied(reason: String): IncrementalFrameProfile =
        IncrementalFrameProfile(false, reason, 0, 0)
    }
  }

  private inner class RuntimeRejectedMergeController {
    private val targets = HashMap<MergeKey, MergeTarget>(256)
    private var sequence = 0L

    fun registerVisible(item: DanmakuItem, config: DanmakuConfig, nowMs: Long) {
      if (!isMergeCandidate(item)) return
      prune(nowMs)
      val key = item.mergeKey()
      targets[key] = MergeTarget(item, countOf(item.data.content).coerceAtLeast(1), nowMs, nowMs)
    }

    fun tryMergeRejected(item: DanmakuItem, config: DanmakuConfig, nowMs: Long): Boolean {
      if (!isMergeCandidate(item)) return false
      prune(nowMs)
      val target = targets[item.mergeKey()] ?: return false
      val targetItem = target.item
      if (targetItem == item || targetItem.isRuntimeOutside(nowMs)) {
        targets.remove(item.mergeKey())
        return false
      }
      val nextCount = target.count + 1
      val previousContent = targetItem.data.content
      val previousWidth = targetItem.drawState.width
      val previousTextSize = targetItem.data.textSize
      target.count = nextCount
      target.lastUpdatedMs = nowMs
      val baseContent = previousContent.substringBeforeLast(" ×")
      val mergedContent = "$baseContent ×$nextCount"
      targetItem.data = targetItem.data.copy(
        content = mergedContent,
        textSize = mergedTextSize(targetItem, item),
        mergedType = DanmakuItemData.MERGED_TYPE_MERGED
      )
      targetItem.cacheRecycle()
      applyMergedMeasurement(
        targetItem = targetItem,
        previousContent = previousContent,
        previousWidth = previousWidth,
        previousTextSize = previousTextSize,
        mergedContent = mergedContent,
        config = config
      )
      targetItem.drawState.layoutGeneration = config.layoutGeneration
      requestCacheBuildIfNeeded(targetItem, config, CACHE_PRIORITY_VISIBLE)
      invalidateFrameReuse(REUSE_INVALIDATE_DROP_REJECTED)
      if (nextCount == 2 || nextCount % 10 == 0) {
        sequence++
        Log.i(
          DanmakuEngine.TAG,
          "[Runtime] rejected merge #$sequence noTrack=1 merged=1 count=$nextCount " +
            "width=${previousWidth.toInt()}->${targetItem.drawState.width.toInt()} " +
            "target=${targetItem.data.danmakuId} rejected=${item.data.danmakuId}"
        )
      }
      return true
    }

    private fun prune(nowMs: Long) {
      val iterator = targets.iterator()
      while (iterator.hasNext()) {
        val target = iterator.next().value
        if (nowMs - target.createdAtMs > MERGE_DUPLICATE_WINDOW_MS ||
          target.item.isRuntimeOutside(nowMs)
        ) {
          iterator.remove()
        }
      }
    }

    private fun isMergeCandidate(item: DanmakuItem): Boolean {
      return item.data.mode == DanmakuItemData.DANMAKU_MODE_ROLLING &&
        item.data.content.isNotBlank()
    }

    private fun DanmakuItem.mergeKey(): MergeKey {
      val data = this.data
      return MergeKey(
        content = data.content.substringBeforeLast(" ×").trim().lowercase(),
        mode = data.mode,
        color = data.textColor,
        renderFlags = data.renderFlags,
        fillTextureUrl = data.vipGradientStyle.fillTextureUrl,
        strokeTextureUrl = data.vipGradientStyle.strokeTextureUrl
      )
    }

    private fun countOf(content: String): Int {
      val suffix = content.substringAfterLast(" ×", missingDelimiterValue = "")
      return suffix.toIntOrNull() ?: 1
    }

    private fun applyMergedMeasurement(
      targetItem: DanmakuItem,
      previousContent: String,
      previousWidth: Float,
      previousTextSize: Int,
      mergedContent: String,
      config: DanmakuConfig
    ) {
      val drawState = targetItem.drawState
      val baseWidth = previousWidth.coerceAtLeast(drawState.width)
      val textSizePx = mergedTextSizePx(targetItem)
      val previousTextSizePx = textSizePx(previousTextSize)
      val textScale = if (previousTextSizePx > 0f) {
        (textSizePx / previousTextSizePx).coerceAtLeast(1f)
      } else {
        1f
      }
      val suffixCharCount = (mergedContent.length - previousContent.length).coerceAtLeast(0)
      val suffixWidth = suffixCharCount * textSizePx * MERGE_SUFFIX_WIDTH_FACTOR
      val safetyPadding = textSizePx * MERGE_SUFFIX_SAFETY_EM + MERGE_CANVAS_PADDING
      val firstMergedSuffix = !previousContent.contains(" ×")
      val safetyGrowth = if (firstMergedSuffix || suffixCharCount > 0) safetyPadding else 0f
      drawState.width = max(baseWidth * textScale + suffixWidth + safetyGrowth, drawState.width)
      drawState.height = max(drawState.height * textScale, textSizePx + MERGE_CANVAS_PADDING)
      drawState.measureGeneration = config.measureGeneration
      targetItem.state = ItemState.Measured
      targetItem.pendingMeasureGeneration = -1
      targetItem.pendingCacheGeneration = -1
    }

    private fun mergedTextSize(targetItem: DanmakuItem, rejectedItem: DanmakuItem): Int {
      val targetTextSize = if (targetItem.data.mergedType == DanmakuItemData.MERGED_TYPE_MERGED) {
        targetItem.data.textSize - MERGE_TEXT_SIZE_BONUS
      } else {
        targetItem.data.textSize
      }
      return max(targetTextSize, rejectedItem.data.textSize) + MERGE_TEXT_SIZE_BONUS
    }

    private fun mergedTextSizePx(item: DanmakuItem): Float {
      return textSizePx(item.data.textSize)
    }

    private fun textSizePx(textSizeSp: Int): Float {
      val textSize = textSizeSp.toFloat().coerceIn(12f, 25f)
      val densityScale = (context.displayer.density - 0.6f).coerceAtLeast(0.4f)
      return (textSize * densityScale * context.config.textSizeScale).coerceAtLeast(12f)
    }
  }

  private data class MergeKey(
    val content: String,
    val mode: Int,
    val color: Int,
    val renderFlags: Int,
    val fillTextureUrl: String,
    val strokeTextureUrl: String
  )

  private data class MergeTarget(
    val item: DanmakuItem,
    var count: Int,
    val createdAtMs: Long,
    var lastUpdatedMs: Long
  )

  private fun acquireState(item: DanmakuItem): ActiveItemState =
    statePool.removeLastOrNull()?.also { it.reset(item) } ?: ActiveItemState(item)

  private fun recycleState(state: ActiveItemState) {
    state.clear()
    if (statePool.size < MAX_ACTIVE_STATE_POOL_SIZE) {
      statePool.add(state)
    }
  }

  private fun dropRejectedIncrementalStates() {
    if (rejectedIncrementalStates.isEmpty()) return
    for (state in rejectedIncrementalStates) {
      activeStates.remove(state)
      stateById.remove(state.item.data.danmakuId)
      dropActiveState(state)
    }
    rejectedIncrementalStates.clear()
    invalidateFrameReuse(REUSE_INVALIDATE_DROP_REJECTED)
  }

  private fun dropActiveState(state: ActiveItemState) {
    val item = state.item
    removeFromTracks(item)
    item.cacheRecycle()
    recycleState(state)
  }

  private fun enqueueMeasureState(state: ActiveItemState) {
    if (state.inMeasureQueue) return
    state.awaitingMeasure = true
    state.inMeasureQueue = true
    state.measureQueueToken++
    val entry = measureEntryPool.removeLastOrNull() ?: MeasureQueueEntry()
    entry.state = state
    entry.token = state.measureQueueToken
    measureQueue.addLast(entry)
  }

  private fun recycleMeasureEntry(entry: MeasureQueueEntry) {
    if (measureEntryPool.size >= MAX_MEASURE_ENTRY_POOL_SIZE) return
    entry.clear()
    measureEntryPool.add(entry)
  }

  private fun clearTracks() {
    trackLayout.clear()
  }

  private fun removeFromTracks(item: DanmakuItem) {
    trackLayout.remove(item)
  }

  private fun invalidateFrameReuse(reasonIndex: Int) {
    frameReuseGeneration++
    if (reasonIndex in frameReuseInvalidationCounts.indices) {
      frameReuseInvalidationCounts[reasonIndex]++
    }
  }

  private fun frameReuseInvalidationSummary(): String {
    val builder = StringBuilder()
    for (index in frameReuseInvalidationCounts.indices) {
      val count = frameReuseInvalidationCounts[index]
      if (count == 0) continue
      if (builder.isNotEmpty()) builder.append('+')
      builder.append(REUSE_INVALIDATE_REASON_NAMES[index])
      if (count > 1) {
        builder.append('*').append(count)
      }
    }
    return if (builder.isEmpty()) "unknown" else builder.toString()
  }

  private fun clearFrameReuseInvalidationReason() {
    frameReuseInvalidationCounts.fill(0)
  }

  private fun isOnlyIncrementalActiveInvalidation(): Boolean {
    for (index in frameReuseInvalidationCounts.indices) {
      val count = frameReuseInvalidationCounts[index]
      if (index == REUSE_INVALIDATE_PROMOTE || index == REUSE_INVALIDATE_ENQUEUE) {
        if (count < 0) return false
      } else if (count != 0) {
        return false
      }
    }
    return true
  }

  private fun markFrameReuseState(frame: RuntimeFrame, config: DanmakuConfig) {
    frame.markReuseState(
      reuseGeneration = frameReuseGeneration,
      activeCount = activeStates.size,
      waitingCount = waitingStates.size,
      layoutGeneration = config.layoutGeneration,
      measureGeneration = config.measureGeneration,
      cacheGeneration = config.cacheGeneration,
      filterGeneration = config.filterGeneration,
      renderGeneration = config.renderGeneration
    )
  }

  private fun canReuseWithExpiredCommands(currentFrame: RuntimeFrame, promotedItems: Int): Boolean {
    val expectedActiveUpperBound = currentFrame.activeCount + promotedItems
    if (activeStates.size > expectedActiveUpperBound) return false
    val staleCommands = currentFrame.commands.size + promotedItems - activeStates.size
    return staleCommands <= MAX_STALE_COMMANDS_BEFORE_REBUILD
  }

  private fun updateLoadShedLevel(layoutCostMs: Long, rejectedCount: Int, unmeasuredCount: Int) {
    val oldLevel = loadShedLevel
    loadShedLevel = DanmakuLoadShedder.nextLevel(
      currentLevel = loadShedLevel,
      layoutCostMs = layoutCostMs,
      rejectedCount = rejectedCount,
      unmeasuredCount = unmeasuredCount
    )
    if (loadShedLevel != oldLevel) {
      Log.i(
        DanmakuEngine.TAG,
        "[Runtime] load shed level=$loadShedLevel cost=${layoutCostMs}ms rejected=$rejectedCount unmeasured=$unmeasuredCount"
      )
    }
  }

  private fun DanmakuItem.isRuntimeTimeout(now: Long): Boolean {
    if (data.mode != DanmakuItemData.DANMAKU_MODE_ROLLING) {
      return isTimeout(now)
    }
    val startTime = RollingDanmakuTiming.resolvedStartTime(rollingStartTimeMs, timePosition)
    return RollingDanmakuTiming.isTimeout(now, startTime, duration)
  }

  private fun DanmakuItem.isRuntimeOutside(now: Long): Boolean {
    if (data.mode != DanmakuItemData.DANMAKU_MODE_ROLLING) {
      return isOutside(now)
    }
    return isLate(now) || isRuntimeTimeout(now)
  }

  private class ActiveItemState(
    var item: DanmakuItem
  ) : DanmakuMeasureScheduler.MeasureCandidate {
    var awaitingMeasure: Boolean = true
    var inMeasureQueue: Boolean = false
    var measureQueueToken: Int = 0

    fun reset(item: DanmakuItem) {
      this.item = item
      awaitingMeasure = true
      inMeasureQueue = false
      measureQueueToken = 0
    }

    fun clear() {
      item = DanmakuItem.DANMAKU_ITEM_EMPTY
      awaitingMeasure = false
      inMeasureQueue = false
      measureQueueToken++
    }

    override val timePositionMs: Long
      get() = item.timePosition
    override val contentLength: Int
      get() = item.data.content.length
    override val measureState: ItemState
      get() = item.state

    override fun isMeasured(measureGeneration: Int): Boolean =
      item.drawState.isMeasured(measureGeneration)

    fun cachePriority(nowMs: Long, scheduleAheadMs: Long): Int {
      val distance = (item.timePosition - nowMs).coerceAtLeast(0L)
      return when {
        distance <= 0L -> 0
        distance <= scheduleAheadMs -> 1
        else -> 2
      }
    }
  }

  private class MeasureQueueEntry {
    lateinit var state: ActiveItemState
    var token: Int = 0

    fun clear() {
      state = EMPTY_ACTIVE_STATE
      token = 0
    }
  }

  companion object {
    private const val PREPARE_AHEAD_MS = 300L
    private const val MAX_MEASURE_PER_FRAME = 12
    private const val MAX_CACHE_REQUESTS_PER_FRAME = 8
    private const val MAX_INCREMENTAL_CACHE_REQUESTS_PER_FRAME = 4
    private const val MAX_PRIME_CACHE_BUILDS = 24
    private const val MAX_INCREMENTAL_PROMOTED_PER_FRAME = 16
    private const val MAX_FALLBACK_DRAWS_PER_FRAME = 12
    private const val MAX_FIXED_FALLBACK_DRAWS_PER_FRAME = 8
    private const val MAX_FALLBACK_CACHE_BOOSTS_PER_FRAME = 4
    private const val FALLBACK_LIMIT_LOG_INTERVAL = 30
    private const val MEASURE_SCHEDULE_BUDGET_MS = 2L
    private const val LIVE_HISTORY_MAX = 2000
    private const val RUNTIME_OVERLOAD_MS = 12L
    private const val LAYOUT_OVERLOAD_MS = 12L
    private const val LAYOUT_PROFILE_DETAIL_INTERVAL = 30
    private const val MAX_ACTIVE_STATE_POOL_SIZE = 512
    private const val MAX_MEASURE_ENTRY_POOL_SIZE = 512
    private const val MAX_RUNTIME_FRAME_POOL_SIZE = 3
    private const val MAX_MEASURE_QUEUE_SCAN_PER_FRAME = 24
    private const val MAX_MEASURE_CANDIDATES_PER_FRAME = 12
    private const val MAX_MEASURE_CACHE_HITS_PER_FRAME = 4
    private const val MAX_STALE_COMMANDS_BEFORE_REBUILD = 32
    private const val MERGE_DUPLICATE_WINDOW_MS = 2_000L
    private const val MERGE_TEXT_SIZE_BONUS = 2
    private const val MERGE_SUFFIX_WIDTH_FACTOR = 0.7f
    private const val MERGE_SUFFIX_SAFETY_EM = 0.8f
    private const val MERGE_CANVAS_PADDING = 6f
    private const val FRAME_CACHE_RELEASE_DELAY_MS = 48L
    private const val FRAME_STALL_LOG_INTERVAL_MS = 1_000L
    private const val DRAW_STALL_LOG_INTERVAL_MS = 1_000L
    private const val MIN_ENQUEUE_PER_FRAME = 4
    private const val ENQUEUE_BUDGET_MS = 2L
    private const val CACHE_PRIORITY_VISIBLE = 0
    private const val REUSE_INVALIDATE_ADD_ITEMS = 0
    private const val REUSE_INVALIDATE_ADD_ITEM = 1
    private const val REUSE_INVALIDATE_UPDATE_ITEM = 2
    private const val REUSE_INVALIDATE_LAYOUT_GEN = 3
    private const val REUSE_INVALIDATE_MEASURE_GEN = 4
    private const val REUSE_INVALIDATE_CACHE_GEN = 5
    private const val REUSE_INVALIDATE_RESET = 6
    private const val REUSE_INVALIDATE_ENQUEUE = 7
    private const val REUSE_INVALIDATE_PROMOTE = 8
    private const val REUSE_INVALIDATE_DROP_REJECTED = 9
    private val REUSE_INVALIDATE_REASON_NAMES = arrayOf(
      "addItems",
      "addItem",
      "updateItem",
      "layoutGen",
      "measureGen",
      "cacheGen",
      "reset",
      "enqueue",
      "promote",
      "dropRejected"
    )
    private val REUSE_INVALIDATE_REASON_COUNT = REUSE_INVALIDATE_REASON_NAMES.size
    private val EMPTY_ACTIVE_STATE = ActiveItemState(DanmakuItem.DANMAKU_ITEM_EMPTY)
  }
}
