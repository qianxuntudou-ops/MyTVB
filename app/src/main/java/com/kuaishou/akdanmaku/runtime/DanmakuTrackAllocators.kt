/*
 * The MIT License (MIT)
 *
 * Copyright 2021 Kwai, Inc. All rights reserved.
 */

package com.kuaishou.akdanmaku.runtime

import com.kuaishou.akdanmaku.DanmakuConfig
import com.kuaishou.akdanmaku.data.DanmakuItem
import com.kuaishou.akdanmaku.data.DanmakuItem.Companion.ROLLING_START_TIME_UNSET
import com.kuaishou.akdanmaku.ext.isTimeout

/**
 * 滚动弹幕轨道分配器。
 *
 * 运行时保证入场基本按时间顺序，因此每条轨道只检查尾部弹幕即可，避免旧 retainer 在高密度时
 * 对整条轨道做多次碰撞扫描。
 */
internal class RollingTrackAllocator {
  private val rows = ArrayList<Row>(32)
  private val placements = HashMap<Long, RollingPlacement>(256)
  private var maxBottom = 0

  fun updateExisting(
    item: DanmakuItem,
    nowMs: Long,
    width: Int,
    height: Int,
    config: DanmakuConfig
  ): Boolean {
    return updateExisting(item, height, config)
  }

  fun updateExisting(
    item: DanmakuItem,
    height: Int,
    config: DanmakuConfig
  ): Boolean {
    if (!refreshMaxBottom(height, config)) return false
    val placement = placements[item.data.danmakuId] ?: return false
    placement.refresh(item, config.layoutGeneration)
    return true
  }

  fun layout(
    item: DanmakuItem,
    nowMs: Long,
    width: Int,
    height: Int,
    margin: Int,
    config: DanmakuConfig
  ): Boolean {
    refreshMaxBottom(height, config)
    val placement = placements[item.data.danmakuId] ?: run {
      val rollingDurationMs = config.rollingDurationMs
      val newRow = findOrCreateRow(
        item = item,
        nowMs = nowMs,
        width = width,
        margin = margin,
        rollingDurationMs = rollingDurationMs,
        allowOverlap = config.allowOverlap,
        overlapFraction = config.overlapFraction
      ) ?: return false
      if (item.rollingStartTimeMs == ROLLING_START_TIME_UNSET) {
        item.rollingStartTimeMs = nowMs.coerceAtLeast(item.timePosition)
      }
      if (item.rollingMotionWidth <= 0f) {
        item.rollingMotionWidth = item.drawState.width
      }
      val newPlacement = RollingPlacement(
        item = item,
        row = newRow,
        topFloat = newRow.top.toFloat(),
        startTimeMs = item.rollingStartTimeMs,
        width = item.drawState.width,
        motionWidth = item.rollingMotionWidth
      )
      placements[item.data.danmakuId] = newPlacement
      newRow.add(newPlacement)
      newPlacement.applyInitialLayout(item, config.layoutGeneration)
      newPlacement
    }
    placement.refresh(item, config.layoutGeneration)

    return true
  }

  private fun refreshMaxBottom(height: Int, config: DanmakuConfig): Boolean {
    val nextMaxBottom = (height * config.screenPart).toInt()
    if (maxBottom == nextMaxBottom) return true
    maxBottom = nextMaxBottom
    if (rows.isEmpty() && placements.isEmpty()) return true
    clear()
    return false
  }

  fun remove(item: DanmakuItem) {
    val placement = placements.remove(item.data.danmakuId) ?: return
    placement.row.remove(placement)
  }

  fun clear() {
    rows.clear()
    placements.clear()
  }

  private fun findOrCreateRow(
    item: DanmakuItem,
    nowMs: Long,
    width: Int,
    margin: Int,
    rollingDurationMs: Long,
    allowOverlap: Boolean,
    overlapFraction: Float
  ): Row? {
    val itemHeight = item.drawState.height.toInt().coerceAtLeast(1)
    val nextStartTime = item.predictedRollingStartTime(nowMs)
    val nextWidth = item.drawState.width
    for (row in rows) {
      row.dropExpiredOnce(nowMs, rollingDurationMs)
      if (itemHeight > row.height) continue
      val tail = row.tail
      if (allowOverlap || tail == null ||
        !tail.willCollideWith(
          nextStartTime = nextStartTime,
          nextWidth = nextWidth,
          screenWidth = width,
          nowMs = nowMs,
          durationMs = rollingDurationMs,
          overlapFraction = overlapFraction
        )) {
        return row
      }
    }

    val nextTop = if (rows.isEmpty()) 0 else rows.last().bottom + margin
    if (nextTop + itemHeight > maxBottom) return null
    return Row(nextTop, itemHeight).also { rows.add(it) }
  }

  private class Row(val top: Int, val height: Int) {
    private var head: RollingPlacement? = null
    var tail: RollingPlacement? = null
      private set
    private var lastCleanupNowMs = Long.MIN_VALUE
    val bottom: Int
      get() = top + height

    fun add(placement: RollingPlacement) {
      val previousTail = tail
      placement.previous = previousTail
      placement.next = null
      placement.linked = true
      if (previousTail == null) {
        head = placement
      } else {
        previousTail.next = placement
      }
      tail = placement
    }

    fun remove(placement: RollingPlacement) {
      if (!placement.linked) return
      val previous = placement.previous
      val next = placement.next
      if (previous == null) {
        head = next
      } else {
        previous.next = next
      }
      if (next == null) {
        tail = previous
      } else {
        next.previous = previous
      }
      placement.previous = null
      placement.next = null
      placement.linked = false
    }

    fun dropExpiredOnce(nowMs: Long, durationMs: Long) {
      if (lastCleanupNowMs == nowMs) return
      lastCleanupNowMs = nowMs
      var currentHead = head
      while (currentHead != null && currentHead.isTimeout(nowMs, durationMs)) {
        remove(currentHead)
        currentHead = head
      }
    }
  }

  private class RollingPlacement(
    val item: DanmakuItem,
    val row: Row,
    private val topFloat: Float,
    var startTimeMs: Long,
    var width: Float,
    var motionWidth: Float
  ) {
    var previous: RollingPlacement? = null
    var next: RollingPlacement? = null
    var linked: Boolean = false

    fun applyInitialLayout(item: DanmakuItem, layoutGeneration: Int) {
      val drawState = item.drawState
      drawState.setRuntimePosition(0f, topFloat)
      drawState.visibility = true
      drawState.layoutGeneration = layoutGeneration
    }

    fun refresh(item: DanmakuItem, layoutGeneration: Int) {
      item.drawState.positionY = topFloat
      item.drawState.visibility = true
      width = item.drawState.width
      motionWidth = item.rollingMotionWidth.takeIf { it > 0f } ?: item.drawState.width
      if (item.rollingStartTimeMs != ROLLING_START_TIME_UNSET) {
        startTimeMs = item.rollingStartTimeMs
      }
      item.drawState.layoutGeneration = layoutGeneration
    }

    fun isTimeout(nowMs: Long, durationMs: Long, startTimeMs: Long = this.startTimeMs): Boolean =
      !item.isHolding && RollingDanmakuTiming.isTimeout(nowMs, startTimeMs, durationMs)

    fun willCollideWith(
      nextStartTime: Long,
      nextWidth: Float,
      screenWidth: Int,
      nowMs: Long,
      durationMs: Long,
      overlapFraction: Float
    ): Boolean {
      if (isTimeout(nowMs, durationMs)) return false
      val dt = nextStartTime - startTimeMs
      if (dt <= 0) return true
      if (dt >= durationMs || RollingDanmakuTiming.isTimeout(nowMs, nextStartTime, durationMs)) return false
      return RollingCollision.willCollide(
        previousStartTime = startTimeMs,
        previousWidth = width,
        previousMotionWidth = motionWidth,
        nextStartTime = nextStartTime,
        nextWidth = nextWidth,
        nextMotionWidth = nextWidth,
        screenWidth = screenWidth,
        nowMs = nowMs,
        durationMs = durationMs,
        overlapFraction = overlapFraction
      )
    }
  }

}

internal object RollingCollision {
  fun willCollide(
    previousStartTime: Long,
    previousWidth: Float,
    previousMotionWidth: Float,
    nextStartTime: Long,
    nextWidth: Float,
    nextMotionWidth: Float,
    screenWidth: Int,
    nowMs: Long,
    durationMs: Long,
    overlapFraction: Float
  ): Boolean {
    val tolerance = minOf(previousWidth, nextWidth) * overlapFraction
    val screen = screenWidth.toFloat()
    val duration = durationMs.toFloat()
    val previousDistance = screen + previousMotionWidth
    val nextDistance = screen + nextMotionWidth
    val previousRightNow = screen -
      previousDistance * ((nowMs - previousStartTime).toFloat() / duration) +
      previousWidth
    val nextLeftNow = screen - nextDistance * ((nowMs - nextStartTime).toFloat() / duration)
    if (nextLeftNow < previousRightNow - tolerance) return true
    val previousRightEnd = previousRightNow - previousDistance
    val nextLeftEnd = nextLeftNow - nextDistance
    return nextLeftEnd < previousRightEnd - tolerance
  }
}

private fun DanmakuItem.predictedRollingStartTime(nowMs: Long): Long =
  RollingDanmakuTiming.predictedStartTime(rollingStartTimeMs, nowMs, timePosition)

/**
 * 顶部/底部固定弹幕轨道。固定弹幕同轨只需等待上一条超时，不做滚动碰撞。
 */
internal class FixedTrackAllocator(private val fromBottom: Boolean) {
  private val rows = ArrayList<Row>(16)
  private val placements = HashMap<Long, FixedPlacement>(64)
  private var lastMaxBottom = 0

  fun updateExisting(
    item: DanmakuItem,
    width: Int,
    height: Int,
    config: DanmakuConfig
  ): Boolean {
    if (!refreshMaxBottom(height, config)) return false
    val placement = placements[item.data.danmakuId] ?: return false
    updatePosition(item, placement.top, width, config)
    return true
  }

  fun layout(
    item: DanmakuItem,
    nowMs: Long,
    width: Int,
    height: Int,
    margin: Int,
    config: DanmakuConfig
  ): Boolean {
    val maxBottom = (height * config.screenPart).toInt()
    refreshMaxBottom(height, config)
    val placement = placements[item.data.danmakuId] ?: run {
      val newRow = findOrCreateRow(item, nowMs, margin, config, maxBottom) ?: return false
      val newPlacement = FixedPlacement(
        item = item,
        row = newRow,
        top = newRow.top,
        expireTimeMs = item.timePosition + config.durationMs
      )
      placements[item.data.danmakuId] = newPlacement
      newRow.placement = newPlacement
      updatePosition(item, newPlacement.top, width, config)
      newPlacement
    }
    return true
  }

  private fun refreshMaxBottom(height: Int, config: DanmakuConfig): Boolean {
    val maxBottom = (height * config.screenPart).toInt()
    if (lastMaxBottom == maxBottom) return true
    lastMaxBottom = maxBottom
    if (rows.isEmpty() && placements.isEmpty()) return true
    clear()
    return false
  }

  private fun updatePosition(
    item: DanmakuItem,
    rowTop: Int,
    width: Int,
    config: DanmakuConfig
  ) {
    val drawState = item.drawState
    drawState.setRuntimePosition(
      ((width - drawState.width) * 0.5f).coerceAtLeast(0f),
      rowTop.toFloat()
    )
    drawState.visibility = true
    drawState.layoutGeneration = config.layoutGeneration
  }

  fun remove(item: DanmakuItem) {
    val placement = placements.remove(item.data.danmakuId) ?: return
    if (placement.row.placement == placement) {
      placement.row.placement = null
    }
  }

  fun clear() {
    rows.clear()
    placements.clear()
  }

  private fun findOrCreateRow(
    item: DanmakuItem,
    nowMs: Long,
    margin: Int,
    config: DanmakuConfig,
    maxBottom: Int
  ): Row? {
    val itemHeight = item.drawState.height.toInt().coerceAtLeast(1)
    for (row in rows) {
      val current = row.placement
      if (current == null || current.isTimeout(nowMs)) {
        if (itemHeight <= row.height) return row
      }
    }
    val top = if (fromBottom) {
      val previousTop = rows.lastOrNull()?.top ?: maxBottom
      previousTop - itemHeight - margin
    } else {
      val previousBottom = rows.lastOrNull()?.bottom ?: 0
      previousBottom + margin
    }
    if (top < 0 || top + itemHeight > maxBottom) return null
    return Row(top, itemHeight).also { rows.add(it) }
  }

  private class Row(val top: Int, val height: Int) {
    var placement: FixedPlacement? = null
    val bottom: Int
      get() = top + height
  }

  private class FixedPlacement(
    val item: DanmakuItem,
    val row: Row,
    val top: Int,
    val expireTimeMs: Long
  ) {
    fun isTimeout(nowMs: Long): Boolean =
      !item.isHolding && (item.isTimeout(nowMs) || nowMs > expireTimeMs)
  }
}
