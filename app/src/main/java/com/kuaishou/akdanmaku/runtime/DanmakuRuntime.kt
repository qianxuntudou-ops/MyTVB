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
  private val activeStates = ArrayList<ActiveItemState>(256)
  private val waitingStates = ArrayList<ActiveItemState>(128)
  private val stateById = HashMap<Long, ActiveItemState>(512)
  private val measureCandidates = ArrayList<ActiveItemState>(128)
  private val measureQueue = ArrayDeque<MeasureQueueEntry>()
  private val measureEntryPool = ArrayList<MeasureQueueEntry>(256)
  private val statePool = ArrayList<ActiveItemState>(256)

  private val rollingTracks = RollingTrackAllocator()
  private val topTracks = FixedTrackAllocator(fromBottom = false)
  private val bottomTracks = FixedTrackAllocator(fromBottom = true)
  private val framePool = RuntimeFramePool()
  private val drawPaint = Paint().apply {
    isAntiAlias = true
  }

  private var dataDirty = false
  private var scanIndex = 0
  private var layoutGeneration = -1
  private var measureGeneration = -1
  private var cacheGeneration = -1
  private var visibilityGeneration = -1
  private var layoutProfileTick = 0
  private var loadShedLevel = 0
  private var filterCacheableGeneration = -1
  private var filterResultCacheable = false

  @Volatile
  private var frame: RuntimeFrame? = null
  private val pendingReleaseFrames = ArrayList<RuntimeFrame>(3)
  private var holdingItem: DanmakuItem? = null

  fun warmUp() {
    context.cacheManager.warmUp()
  }

  @Synchronized
  fun addItems(items: Collection<DanmakuItem>) {
    pendingAddItems.addAll(items)
    dataDirty = true
  }

  @Synchronized
  fun primeMeasureItems(items: List<DanmakuItem>, maxCount: Int) {
    if (items.isEmpty() || maxCount <= 0) return
    val config = context.config
    var scheduled = 0
    var cacheHits = 0
    val startedAt = SystemClock.elapsedRealtime()
    for (item in items) {
      if (scheduled >= maxCount) break
      if (item.state == ItemState.Measuring) continue
      if (item.drawState.isMeasured(config.measureGeneration) && item.state >= ItemState.Measured) continue
      val cachedSize = context.cacheManager.getDanmakuSize(item.data)
      if (cachedSize != null) {
        item.drawState.width = cachedSize.width.toFloat()
        item.drawState.height = cachedSize.height.toFloat()
        item.drawState.measureGeneration = config.measureGeneration
        item.state = ItemState.Measured
        item.pendingMeasureGeneration = -1
        cacheHits++
        continue
      }
      // 首窗口提交后先把测量排进缓存线程，减少第一批 action 帧里的集中测量抖动。
      item.state = ItemState.Measuring
      item.pendingMeasureGeneration = config.measureGeneration
      context.cacheManager.requestMeasure(
        item = item,
        displayer = context.displayer,
        config = config,
        priority = CACHE_PRIORITY_VISIBLE
      )
      scheduled++
    }
    val costMs = SystemClock.elapsedRealtime() - startedAt
    if (items.size >= 96 || scheduled >= 24 || costMs >= 4L) {
      Log.i(
        DanmakuEngine.TAG,
        "[Runtime] prime measure scheduled=$scheduled cacheHit=$cacheHits total=${items.size} cost=${costMs}ms"
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
      return
    }
    item.state = ItemState.Measuring
    item.pendingMeasureGeneration = config.measureGeneration
    context.cacheManager.requestMeasure(
      item = item,
      displayer = context.displayer,
      config = config,
      priority = CACHE_PRIORITY_VISIBLE
    )
  }

  @Synchronized
  fun addItem(item: DanmakuItem) {
    pendingAddItems.add(item)
    dataDirty = true
  }

  @Synchronized
  fun updateItem(item: DanmakuItem) {
    sortedItems.remove(item)
    pendingAddItems.add(item)
    dataDirty = true
  }

  @Synchronized
  fun clearAllData() {
    pendingAddItems.clear()
    sortedItems.clear()
    dataDirty = false
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
    scanIndex = 0
    holdingItem = null
    loadShedLevel = 0
    clearTracks()
    releaseFrame(frame)
    frame = null
    releaseAllPendingFrames()
  }

  @Synchronized
  fun seekTo(positionMs: Long) {
    val config = context.config
    val start = positionMs - max(config.durationMs, config.rollingDurationMs)
    scanIndex = lowerBound(start)
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
    releaseFrame(frame)
    frame = null
    releaseAllPendingFrames()
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
    syncPendingData()
    val syncMs = SystemClock.elapsedRealtime() - checkpoint

    val config = context.config
    checkpoint = SystemClock.elapsedRealtime()
    if (config.layoutGeneration != layoutGeneration) {
      clearTracks()
      activeStates.forEach { it.item.drawState.layoutGeneration = -1 }
      waitingStates.forEach { it.item.drawState.layoutGeneration = -1 }
      layoutGeneration = config.layoutGeneration
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
    }
    if (config.cacheGeneration != cacheGeneration) {
      activeStates.forEach { it.item.cacheRecycle() }
      waitingStates.forEach { it.item.cacheRecycle() }
      cacheGeneration = config.cacheGeneration
    }
    visibilityGeneration = config.visibilityGeneration
    val generationMs = SystemClock.elapsedRealtime() - checkpoint

    val now = context.timer.currentTimeMs
    checkpoint = SystemClock.elapsedRealtime()
    removeExpired(now, config)
    val expireMs = SystemClock.elapsedRealtime() - checkpoint
    checkpoint = SystemClock.elapsedRealtime()
    enqueueDueItems(now, config)
    val enqueueMs = SystemClock.elapsedRealtime() - checkpoint
    checkpoint = SystemClock.elapsedRealtime()
    val promotedItems = promoteWaitingItems(now)
    val promoteMs = SystemClock.elapsedRealtime() - checkpoint
    checkpoint = SystemClock.elapsedRealtime()
    val scheduledMeasures = scheduleMeasureActiveItems(config)
    val measureMs = SystemClock.elapsedRealtime() - checkpoint
    checkpoint = SystemClock.elapsedRealtime()
    val newFrame = layoutAndBuildFrame(now, config)
    val layoutMs = SystemClock.elapsedRealtime() - checkpoint
    checkpoint = SystemClock.elapsedRealtime()
    replaceFrame(newFrame)
    val frameMs = SystemClock.elapsedRealtime() - checkpoint

    val cost = SystemClock.elapsedRealtime() - startedAt
    if (cost >= RUNTIME_OVERLOAD_MS) {
      Log.w(
        DanmakuEngine.TAG,
        "[Runtime] update overload cost=${cost}ms release=${releaseMs}ms sync=${syncMs}ms gen=${generationMs}ms " +
          "expire=${expireMs}ms enqueue=${enqueueMs}ms promote=${promoteMs}ms promoted=$promotedItems " +
          "measure=${measureMs}ms scheduled=$scheduledMeasures layout=${layoutMs}ms frame=${frameMs}ms " +
          "active=${activeStates.size} waiting=${waitingStates.size} draw=${newFrame.commands.size} " +
          "releaseFrames=${releaseProfile.frames} releaseCaches=${releaseProfile.caches} " +
          "releasePost=${releaseProfile.postMs}ms releaseRef=${releaseProfile.refMs}ms releaseRecycle=${releaseProfile.recycleMs}ms"
      )
    }
  }

  fun draw(canvas: Canvas, onRenderReady: () -> Unit) {
    val currentFrame = frame
    onRenderReady()
    val config = context.config
    if (!config.visibility || currentFrame == null ||
      currentFrame.visibilityGeneration != config.visibilityGeneration) {
      return
    }

    var hit = 0
    val commandCount = currentFrame.commands.size
    for (index in 0 until commandCount) {
      if (drawCommand(canvas, currentFrame.commands, index, config)) {
        hit++
      }
      dispatchShown(currentFrame.commands.itemAt(index), config)
    }
    cacheHit.num = hit
    cacheHit.den = commandCount
  }

  fun getDanmakus(point: Point): List<DanmakuItem>? {
    if (!context.config.visibility) return null
    val currentFrame = frame ?: return null
    val x = point.x.toFloat()
    val y = point.y.toFloat()
    val result = ArrayList<DanmakuItem>()
    val commands = currentFrame.commands
    for (index in 0 until commands.size) {
      if (x >= commands.leftAt(index) && x <= commands.rightAt(index) &&
        y >= commands.topAt(index) && y <= commands.bottomAt(index)) {
        result.add(commands.itemAt(index))
      }
    }
    return result
  }

  fun getDanmakus(rect: RectF): List<DanmakuItem>? {
    if (!context.config.visibility) return null
    val currentFrame = frame ?: return null
    val result = ArrayList<DanmakuItem>()
    val commands = currentFrame.commands
    for (index in 0 until commands.size) {
      if (rect.left < commands.rightAt(index) && rect.right > commands.leftAt(index) &&
        rect.top < commands.bottomAt(index) && rect.bottom > commands.topAt(index)) {
        result.add(commands.itemAt(index))
      }
    }
    return result
  }

  fun release() {
    clearAllData()
    context.cacheManager.release()
  }

  private fun syncPendingData() {
    if (pendingAddItems.isEmpty()) return
    val pending = ArrayList(pendingAddItems)
    pendingAddItems.clear()
    val canAppendInOrder = canAppendWithoutSort(pending)
    sortedItems.addAll(pending)
    if (!canAppendInOrder) {
      sortedItems.sortWith(comparator)
    }
    dataDirty = false
    if (liveMode) {
      trimLiveHistory()
    }
  }

  private fun canAppendWithoutSort(pending: List<DanmakuItem>): Boolean {
    if (pending.isEmpty()) return true
    var previousTime = sortedItems.lastOrNull()?.timePosition ?: Long.MIN_VALUE
    for (item in pending) {
      if (item.timePosition < previousTime) {
        return false
      }
      previousTime = item.timePosition
    }
    return true
  }

  private fun removeExpired(now: Long, config: DanmakuConfig) {
    val iterator = activeStates.iterator()
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
      }
    }
  }

  private fun enqueueDueItems(now: Long, config: DanmakuConfig) {
    if (sortedItems.isEmpty()) return
    val maxDuration = max(config.durationMs, config.rollingDurationMs)
    val windowStart = now - maxDuration
    if (scanIndex >= sortedItems.size || sortedItems.getOrNull(scanIndex)?.timePosition ?: Long.MAX_VALUE < windowStart) {
      scanIndex = lowerBound(windowStart)
    }

    val entryEnd = now + entryAheadMs(config)
    var added = 0
    val startedAt = SystemClock.elapsedRealtime()
    val enqueueBudget = DanmakuLoadShedder.enqueueBudget(loadShedLevel)
    while (scanIndex < sortedItems.size && added < enqueueBudget) {
      val item = sortedItems[scanIndex]
      if (item.timePosition > entryEnd) break
      scanIndex++
      if (item.timePosition < windowStart) continue
      if (DanmakuLoadShedder.shouldSkipItem(loadShedLevel)) continue
      if (!stateById.containsKey(item.data.danmakuId)) {
        item.duration = if (item.data.mode == DanmakuItemData.DANMAKU_MODE_ROLLING) {
          config.rollingDurationMs
        } else {
          config.durationMs
        }
        item.drawState.layoutGeneration = -1
        item.rollingStartTimeMs = ROLLING_START_TIME_UNSET
        val state = acquireState(item)
        enqueueMeasureState(state)
        if (item.timePosition <= now) {
          activeStates.add(state)
        } else {
          waitingStates.add(state)
        }
        stateById[item.data.danmakuId] = state
        added++
        if (added >= MIN_ENQUEUE_PER_FRAME &&
          SystemClock.elapsedRealtime() - startedAt >= ENQUEUE_BUDGET_MS) {
          break
        }
      }
    }
  }

  private fun promoteWaitingItems(now: Long): Int {
    if (waitingStates.isEmpty()) return 0
    var promoteCount = 0
    while (promoteCount < waitingStates.size && waitingStates[promoteCount].item.timePosition <= now) {
      promoteCount++
    }
    if (promoteCount == 0) return 0
    repeat(promoteCount) {
      activeStates.add(waitingStates[it])
    }
    waitingStates.subList(0, promoteCount).clear()
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
      if (item.state < ItemState.Measured || !item.drawState.isMeasured(config.measureGeneration)) {
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
      val visible = if (item.drawState.layoutGeneration == config.layoutGeneration) {
        val updated = when (mode) {
          DanmakuItemData.DANMAKU_MODE_CENTER_TOP -> topTracks.updateExisting(item, width, height, config)
          DanmakuItemData.DANMAKU_MODE_CENTER_BOTTOM -> bottomTracks.updateExisting(item, width, height, config)
          else -> rollingTracks.updateExisting(item, now, width, height, config)
        }
        if (updated) {
          trackFastCount++
          true
        } else {
          trackLayoutCount++
          layoutTrack(item, now, width, height, margin, config)
        }
      } else {
        trackLayoutCount++
        layoutTrack(item, now, width, height, margin, config)
      }
      if (profileDetails) trackMs += SystemClock.elapsedRealtime() - stepAt
      if (!visible) {
        trackRejectedCount++
        if (DanmakuRejectionPolicy.shouldDropRejectedItem(item)) {
          stateById.remove(item.data.danmakuId)
          removeFromTracks(item)
          item.cacheRecycle()
          iterator.remove()
          recycleState(state)
        }
        continue
      }
      if (item.state < ItemState.Rendering) {
        if (cacheRequestBudget > 0) {
          item.state = ItemState.Rendering
          item.pendingCacheGeneration = config.cacheGeneration
          stepAt = if (profileDetails) SystemClock.elapsedRealtime() else 0L
          context.cacheManager.requestBuildCache(
            item = item,
            displayer = displayer,
            config = config,
            priority = state.cachePriority(now, entryAheadMs(config))
          )
          if (profileDetails) cacheMs += SystemClock.elapsedRealtime() - stepAt
          cacheRequestBudget--
          cacheRequestedCount++
        } else {
          // 首屏窗口可能瞬间出现十几条弹幕，缓存构建分帧排队；未排到的先走直接绘制兜底。
          cacheDeferredCount++
        }
      }
      if (item.state >= ItemState.Rendered && item.drawState.cacheGeneration != config.cacheGeneration) {
        item.cacheRecycle()
      }
      stepAt = if (profileDetails) SystemClock.elapsedRealtime() else 0L
      val drawState = item.drawState
      val cache = drawState.drawingCache
      if (cache != DrawingCache.EMPTY_DRAWING_CACHE) {
        stepAt = if (profileDetails) SystemClock.elapsedRealtime() else 0L
        newFrame.retainCache(cache)
        if (profileDetails) retainMs += SystemClock.elapsedRealtime() - stepAt
      }
      stepAt = if (profileDetails) SystemClock.elapsedRealtime() else 0L
      val left = drawState.positionX
      val top = drawState.positionY
      val right = left + drawState.width
      val bottom = top + drawState.height
      if (mode == DanmakuItemData.DANMAKU_MODE_CENTER_TOP ||
        mode == DanmakuItemData.DANMAKU_MODE_CENTER_BOTTOM) {
        // 悬停弹幕始终最后绘制，保证层级压在滚动弹幕上方。
        newFrame.fixedCommands.add(item, cache, drawState.cacheGeneration, left, top, right, bottom)
      } else {
        newFrame.commands.add(item, cache, drawState.cacheGeneration, left, top, right, bottom)
      }
      if (profileDetails) commandMs += SystemClock.elapsedRealtime() - stepAt
    }
    val fixedMergeStartedAt = if (profileDetails) SystemClock.elapsedRealtime() else 0L
    newFrame.commands.addAll(newFrame.fixedCommands)
    newFrame.fixedCommands.clear()
    if (profileDetails) fixedMergeMs = SystemClock.elapsedRealtime() - fixedMergeStartedAt
    val cost = SystemClock.elapsedRealtime() - startedAt
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

  private fun layoutTrack(
    item: DanmakuItem,
    now: Long,
    width: Int,
    height: Int,
    margin: Int,
    config: DanmakuConfig
  ): Boolean =
    when (item.data.mode) {
      DanmakuItemData.DANMAKU_MODE_CENTER_TOP -> topTracks.layout(item, now, width, height, margin, config)
      DanmakuItemData.DANMAKU_MODE_CENTER_BOTTOM -> bottomTracks.layout(item, now, width, height, margin, config)
      else -> rollingTracks.layout(item, now, width, height, margin, config)
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

  private fun drawCommand(canvas: Canvas, commands: CommandBuffer, index: Int, config: DanmakuConfig): Boolean {
    val cache = commands.cacheAt(index)
    if (cache != DrawingCache.EMPTY_DRAWING_CACHE &&
      commands.cacheGenerationAt(index) == config.cacheGeneration) {
      val bitmap = cache.get()?.bitmap
      if (bitmap != null && !bitmap.isRecycled) {
        drawPaint.alpha = (config.alpha * 255).toInt().coerceIn(0, 255)
        canvas.drawBitmap(bitmap, commands.leftAt(index), commands.topAt(index), drawPaint)
        return true
      }
    }
    canvas.withTranslation(commands.leftAt(index), commands.topAt(index)) {
      context.renderer.draw(commands.itemAt(index), canvas, context.displayer, config)
    }
    return false
  }

  private fun dispatchShown(item: DanmakuItem, config: DanmakuConfig) {
    val target = listener ?: return
    if (item.shownGeneration == config.firstShownGeneration) return
    item.shownGeneration = config.firstShownGeneration
    callbackHandler.post { target.onDanmakuShown(item) }
  }

  private fun replaceFrame(newFrame: RuntimeFrame) {
    frame?.let { pendingReleaseFrames.add(it) }
    frame = newFrame
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
      context.cacheManager.releaseReferences(oldFrame.retainedCaches, FRAME_CACHE_RELEASE_DELAY_MS)
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
    context.cacheManager.releaseReferences(oldFrame.retainedCaches, delayMs)
    framePool.release(oldFrame)
  }

  private fun shouldProfileLayoutFrame(): Boolean {
    layoutProfileTick++
    return true
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

  private fun acquireState(item: DanmakuItem): ActiveItemState =
    statePool.removeLastOrNull()?.also { it.reset(item) } ?: ActiveItemState(item)

  private fun recycleState(state: ActiveItemState) {
    state.clear()
    if (statePool.size < MAX_ACTIVE_STATE_POOL_SIZE) {
      statePool.add(state)
    }
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
    rollingTracks.clear()
    topTracks.clear()
    bottomTracks.clear()
  }

  private fun removeFromTracks(item: DanmakuItem) {
    rollingTracks.remove(item)
    topTracks.remove(item)
    bottomTracks.remove(item)
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

  private fun lowerBound(timeMs: Long): Int {
    var low = 0
    var high = sortedItems.size
    while (low < high) {
      val mid = (low + high).ushr(1)
      if (sortedItems[mid].timePosition < timeMs) {
        low = mid + 1
      } else {
        high = mid
      }
    }
    return low
  }

  private fun trimLiveHistory() {
    if (sortedItems.size <= LIVE_HISTORY_MAX) return
    val removeCount = sortedItems.size - LIVE_HISTORY_MAX
    repeat(removeCount) {
      sortedItems.removeAt(0)
    }
    scanIndex = (scanIndex - removeCount).coerceAtLeast(0)
  }

  private class RuntimeFrame {
    val commands = CommandBuffer(256)
    val fixedCommands = CommandBuffer(16)
    val retainedCaches = ArrayList<DrawingCache>(256)
    private val retainedCacheSet = HashSet<DrawingCache>(256)
    var visibilityGeneration: Int = -1

    fun reset(visibilityGeneration: Int) {
      this.visibilityGeneration = visibilityGeneration
      commands.clear()
      fixedCommands.clear()
    }

    fun retainCache(cache: DrawingCache) {
      if (retainedCacheSet.add(cache)) {
        cache.increaseReference()
        retainedCaches.add(cache)
      }
    }

    fun recycleCommands() {
      commands.clear()
      fixedCommands.clear()
      retainedCaches.clear()
      retainedCacheSet.clear()
    }
  }

  private class RuntimeFramePool {
    private val frames = ArrayList<RuntimeFrame>(3)

    fun acquire(visibilityGeneration: Int): RuntimeFrame =
      (frames.removeLastOrNull() ?: RuntimeFrame()).also { it.reset(visibilityGeneration) }

    fun release(frame: RuntimeFrame) {
      frame.recycleCommands()
      if (frames.size < MAX_RUNTIME_FRAME_POOL_SIZE) {
        frames.add(frame)
      }
    }
  }

  private class CommandBuffer(initialCapacity: Int) {
    private var items = arrayOfNulls<DanmakuItem>(initialCapacity)
    private var caches = arrayOfNulls<DrawingCache>(initialCapacity)
    private var cacheGenerations = IntArray(initialCapacity)
    private var lefts = FloatArray(initialCapacity)
    private var tops = FloatArray(initialCapacity)
    private var rights = FloatArray(initialCapacity)
    private var bottoms = FloatArray(initialCapacity)
    var size: Int = 0
      private set

    fun add(
      item: DanmakuItem,
      cache: DrawingCache,
      cacheGeneration: Int,
      left: Float,
      top: Float,
      right: Float,
      bottom: Float
    ) {
      ensureCapacity(size + 1)
      val index = size++
      items[index] = item
      caches[index] = cache
      cacheGenerations[index] = cacheGeneration
      lefts[index] = left
      tops[index] = top
      rights[index] = right
      bottoms[index] = bottom
    }

    fun addAll(other: CommandBuffer) {
      val count = other.size
      if (count == 0) return
      ensureCapacity(size + count)
      for (index in 0 until count) {
        val target = size++
        items[target] = other.items[index]
        caches[target] = other.caches[index]
        cacheGenerations[target] = other.cacheGenerations[index]
        lefts[target] = other.lefts[index]
        tops[target] = other.tops[index]
        rights[target] = other.rights[index]
        bottoms[target] = other.bottoms[index]
      }
    }

    fun itemAt(index: Int): DanmakuItem = items[index] ?: DanmakuItem.DANMAKU_ITEM_EMPTY
    fun cacheAt(index: Int): DrawingCache = caches[index] ?: DrawingCache.EMPTY_DRAWING_CACHE
    fun cacheGenerationAt(index: Int): Int = cacheGenerations[index]
    fun leftAt(index: Int): Float = lefts[index]
    fun topAt(index: Int): Float = tops[index]
    fun rightAt(index: Int): Float = rights[index]
    fun bottomAt(index: Int): Float = bottoms[index]

    fun clear() {
      for (index in 0 until size) {
        items[index] = null
        caches[index] = null
      }
      size = 0
    }

    private fun ensureCapacity(required: Int) {
      if (required <= items.size) return
      var nextCapacity = items.size * 2
      if (nextCapacity < required) nextCapacity = required
      items = items.copyOf(nextCapacity)
      caches = caches.copyOf(nextCapacity)
      cacheGenerations = cacheGenerations.copyOf(nextCapacity)
      lefts = lefts.copyOf(nextCapacity)
      tops = tops.copyOf(nextCapacity)
      rights = rights.copyOf(nextCapacity)
      bottoms = bottoms.copyOf(nextCapacity)
    }
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
    private const val MAX_CACHE_REQUESTS_PER_FRAME = 4
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
    private const val FRAME_CACHE_RELEASE_DELAY_MS = 48L
    private const val MIN_ENQUEUE_PER_FRAME = 4
    private const val ENQUEUE_BUDGET_MS = 2L
    private const val CACHE_PRIORITY_VISIBLE = 0
    private val EMPTY_ACTIVE_STATE = ActiveItemState(DanmakuItem.DANMAKU_ITEM_EMPTY)
  }
}
