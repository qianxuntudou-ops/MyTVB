/*
 * The MIT License (MIT)
 *
 * Copyright 2021 Kwai, Inc. All rights reserved.
 */

package com.kuaishou.akdanmaku.runtime

import android.os.SystemClock
import com.kuaishou.akdanmaku.cache.DrawingCache
import com.kuaishou.akdanmaku.data.DanmakuItem

internal class RuntimeFrame {
  val commands = CommandBuffer(256)
  val fixedCommands = CommandBuffer(16)
  var retainedCaches = ArrayList<DrawingCache>(256)
    private set
  private var retainedCacheSet = HashSet<DrawingCache>(256)
  var visibilityGeneration: Int = -1
  var reuseGeneration: Int = -1
    private set
  var fixedCommandStartIndex: Int = 0
    private set
  var activeCount: Int = 0
    private set
  var waitingCount: Int = 0
    private set
  var layoutGeneration: Int = -1
    private set
  var measureGeneration: Int = -1
    private set
  var cacheGeneration: Int = -1
    private set
  var filterGeneration: Int = -1
    private set
  var renderGeneration: Int = -1
    private set
  private var transitionStartedAtMs: Long = 0L

  fun reset(visibilityGeneration: Int) {
    this.visibilityGeneration = visibilityGeneration
    reuseGeneration = -1
    activeCount = 0
    waitingCount = 0
    layoutGeneration = -1
    measureGeneration = -1
    cacheGeneration = -1
    filterGeneration = -1
    renderGeneration = -1
    transitionStartedAtMs = 0L
    fixedCommandStartIndex = 0
    commands.clear()
    fixedCommands.clear()
  }

  fun markReuseState(
    reuseGeneration: Int,
    activeCount: Int,
    waitingCount: Int,
    layoutGeneration: Int,
    measureGeneration: Int,
    cacheGeneration: Int,
    filterGeneration: Int,
    renderGeneration: Int
  ) {
    this.reuseGeneration = reuseGeneration
    this.activeCount = activeCount
    this.waitingCount = waitingCount
    this.layoutGeneration = layoutGeneration
    this.measureGeneration = measureGeneration
    this.cacheGeneration = cacheGeneration
    this.filterGeneration = filterGeneration
    this.renderGeneration = renderGeneration
  }

  fun markFixedCommandStart(index: Int) {
    fixedCommandStartIndex = index.coerceIn(0, commands.size)
  }

  fun appendRollingCommands(newCommands: CommandBuffer) {
    val count = newCommands.size
    if (count == 0) return
    commands.insertAll(fixedCommandStartIndex, newCommands)
    fixedCommandStartIndex += count
  }

  fun appendFixedCommands(newCommands: CommandBuffer) {
    commands.addAll(newCommands)
  }

  fun pruneRuntimeOutsideCommands(nowMs: Long, isRuntimeOutside: (DanmakuItem, Long) -> Boolean): Int {
    val oldFixedCommandStart = fixedCommandStartIndex
    var keptRolling = 0
    var dropped = 0
    commands.retainIndexed { index, item ->
      val keep = !isRuntimeOutside(item, nowMs)
      if (keep && index < oldFixedCommandStart) {
        keptRolling++
      } else if (!keep) {
        dropped++
      }
      keep
    }
    fixedCommandStartIndex = keptRolling.coerceIn(0, commands.size)
    return dropped
  }

  fun markTransition(nowMs: Long) {
    transitionStartedAtMs = nowMs
  }

  fun isTransitionAlive(): Boolean =
    transitionStartedAtMs > 0L &&
      SystemClock.elapsedRealtime() - transitionStartedAtMs <= TRANSITION_FRAME_MAX_AGE_MS

  fun transitionElapsedMs(): Long =
    if (transitionStartedAtMs > 0L) {
      SystemClock.elapsedRealtime() - transitionStartedAtMs
    } else {
      0L
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

  fun detachRetainedCaches(): ArrayList<DrawingCache> {
    val detached = retainedCaches
    retainedCaches = ArrayList(256)
    retainedCacheSet = HashSet(256)
    return detached
  }

  private companion object {
    const val TRANSITION_FRAME_MAX_AGE_MS = 450L
  }
}

internal class RuntimeFramePool(private val maxSize: Int) {
  private val frames = ArrayList<RuntimeFrame>(3)

  fun acquire(visibilityGeneration: Int): RuntimeFrame =
    (frames.removeLastOrNull() ?: RuntimeFrame()).also { it.reset(visibilityGeneration) }

  fun release(frame: RuntimeFrame) {
    frame.recycleCommands()
    if (frames.size < maxSize) {
      frames.add(frame)
    }
  }
}

internal class CommandBuffer(initialCapacity: Int) {
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
    insertAll(size, other)
  }

  fun insertAll(index: Int, other: CommandBuffer) {
    val count = other.size
    if (count == 0) return
    val insertIndex = index.coerceIn(0, size)
    ensureCapacity(size + count)
    for (moveIndex in size - 1 downTo insertIndex) {
      val target = moveIndex + count
      items[target] = items[moveIndex]
      caches[target] = caches[moveIndex]
      cacheGenerations[target] = cacheGenerations[moveIndex]
      lefts[target] = lefts[moveIndex]
      tops[target] = tops[moveIndex]
      rights[target] = rights[moveIndex]
      bottoms[target] = bottoms[moveIndex]
    }
    for (source in 0 until count) {
      val target = insertIndex + source
      items[target] = other.items[source]
      caches[target] = other.caches[source]
      cacheGenerations[target] = other.cacheGenerations[source]
      lefts[target] = other.lefts[source]
      tops[target] = other.tops[source]
      rights[target] = other.rights[source]
      bottoms[target] = other.bottoms[source]
    }
    size += count
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

  fun retainIndexed(shouldKeep: (Int, DanmakuItem) -> Boolean) {
    var writeIndex = 0
    for (readIndex in 0 until size) {
      val item = items[readIndex]
      if (item != null && shouldKeep(readIndex, item)) {
        if (writeIndex != readIndex) {
          items[writeIndex] = item
          caches[writeIndex] = caches[readIndex]
          cacheGenerations[writeIndex] = cacheGenerations[readIndex]
          lefts[writeIndex] = lefts[readIndex]
          tops[writeIndex] = tops[readIndex]
          rights[writeIndex] = rights[readIndex]
          bottoms[writeIndex] = bottoms[readIndex]
        }
        writeIndex++
      }
    }
    for (index in writeIndex until size) {
      items[index] = null
      caches[index] = null
    }
    size = writeIndex
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

internal class DrawCommandResult(
  val cacheHit: Boolean,
  val fallbackDrawn: Boolean,
  val skipReason: Int = SKIP_NONE
) {
  companion object {
    const val SKIP_NONE = 0
    const val SKIP_CACHE_MISS = 1
    const val SKIP_UNMEASURED = 2
    const val SKIP_RENDERER_FAILED = 3

    val CACHE_HIT = DrawCommandResult(cacheHit = true, fallbackDrawn = false)
    val FALLBACK_DRAWN = DrawCommandResult(cacheHit = false, fallbackDrawn = true)
    val CACHE_MISS_SKIPPED = DrawCommandResult(
      cacheHit = false,
      fallbackDrawn = false,
      skipReason = SKIP_CACHE_MISS
    )
    val UNMEASURED_SKIPPED = DrawCommandResult(
      cacheHit = false,
      fallbackDrawn = false,
      skipReason = SKIP_UNMEASURED
    )
    val RENDERER_FAILED_SKIPPED = DrawCommandResult(
      cacheHit = false,
      fallbackDrawn = false,
      skipReason = SKIP_RENDERER_FAILED
    )
  }
}
