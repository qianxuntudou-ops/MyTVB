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

@file:Suppress("SpellCheckingInspection")

package com.kuaishou.akdanmaku.cache

import android.os.*
import com.kuaishou.akdanmaku.ext.AkLog as Log
import com.kuaishou.akdanmaku.DanmakuConfig
import com.kuaishou.akdanmaku.data.DanmakuItem
import com.kuaishou.akdanmaku.data.DanmakuItemData
import com.kuaishou.akdanmaku.data.ItemState
import com.kuaishou.akdanmaku.data.state.DrawState
import com.kuaishou.akdanmaku.engine.DanmakuContext
import com.kuaishou.akdanmaku.engine.DanmakuEngine
import com.kuaishou.akdanmaku.ext.endTrace
import com.kuaishou.akdanmaku.ext.startTrace
import com.kuaishou.akdanmaku.ui.DanmakuDisplayer
import com.kuaishou.akdanmaku.utils.Size
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * 缓存管理器，用于完成后台缓存绘制与管理缓存相关对象
 *
 * Maintained by project contributors.
 * @since 2021-06-24
 */
@Suppress("unused")
internal class CacheManager(private val callbackHandler: Handler, private val context: DanmakuContext) {
  private var available = false
  private val cacheThread by lazy {
    HandlerThread(THREAD_NAME).apply {
      start()
      available = true
    }
  }
  private val cacheHandler by lazy { CacheHandler(cacheThread.looper) }
  private var cancelFlag = false

  private val measureSizeCache = ConcurrentHashMap<Long, Size>()
  private val pendingMeasureKeys = Collections.synchronizedSet(mutableSetOf<Long>())
  private val pendingBuildKeys = Collections.synchronizedSet(mutableSetOf<Long>())
  private var sharedCacheGeneration = -1
  private val sharedDrawingCaches = object : LinkedHashMap<Long, DrawingCache>(256, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, DrawingCache>?): Boolean {
      val shouldRemove = size > MAX_SHARED_DRAWING_CACHE
      if (shouldRemove) {
        eldest?.value?.let(::releaseSharedDrawingCache)
      }
      return shouldRemove
    }
  }

  val cachePool = DrawingCachePool(DanmakuConfig.CACHE_POOL_MAX_MEMORY_SIZE)
  var isReleased: Boolean = false
    private set

  fun warmUp() {
    if (isReleased) return
    // 提前启动缓存线程，避免第一批弹幕入场时 action 线程被 HandlerThread.looper 阻塞。
    cacheHandler.sendEmptyMessage(WORKER_MSG_WARM_UP)
  }

  fun requestBuildCache(
    item: DanmakuItem,
    displayer: DanmakuDisplayer,
    config: DanmakuConfig,
    priority: Int = CACHE_PRIORITY_VISIBLE
  ) {
    if (isReleased) return
    val key = requestKey(item.data.danmakuId, config.cacheGeneration)
    if (!pendingBuildKeys.add(key)) {
      cacheHandler.boostBuild(key, priority)
      return
    }
    cacheHandler.enqueue(PendingCacheTask.build(CacheInfo(item, displayer, config, key), priority))
  }

  fun requestMeasure(
    item: DanmakuItem,
    displayer: DanmakuDisplayer,
    config: DanmakuConfig,
    priority: Int = CACHE_PRIORITY_VISIBLE,
    buildAfterMeasure: Boolean = false
  ) {
    if (isReleased) return
    val key = requestKey(item.data.danmakuId, config.measureGeneration)
    if (!pendingMeasureKeys.add(key)) return
    cacheHandler.enqueue(PendingCacheTask.measure(CacheInfo(item, displayer, config, key, buildAfterMeasure), priority))
  }

  /**
   * 发送一个 build 结束的请求，放置在当前若干 build_cache 消息后，当此批次缓存完成后会发送一个回调消息
   */
  fun requestBuildSign() {
    if (isReleased) return
    cacheHandler.requestBuildSign()
  }

  fun cancelAllRequests() {
    if (isReleased) return
    cacheHandler.cancelQueuedRequests()
    pendingMeasureKeys.clear()
    pendingBuildKeys.clear()
    cancelFlag = true
  }

  fun requestRelease() {
    if (isReleased) return
    cancelAllRequests()
    cacheHandler.sendEmptyMessage(WORKER_MSG_RELEASE)
  }

  fun destroyCache(cache: DrawingCache) {
    if (isReleased || cache == DrawingCache.EMPTY_DRAWING_CACHE) return
    cacheHandler.obtainMessage(WORKER_MSG_DESTROY, cache).sendToTarget()
  }

  fun releaseCache(cache: DrawingCache) {
    if (isReleased || cache == DrawingCache.EMPTY_DRAWING_CACHE) return
    cacheHandler.obtainMessage(WORKER_MSG_RELEASE_ITEM, cache).sendToTarget()
  }

  fun releaseReferences(caches: List<DrawingCache>, delayMs: Long = CACHE_REFERENCE_RELEASE_DELAY_MS) {
    if (caches.isEmpty()) return
    val snapshot = ArrayList<DrawingCache>(caches.size)
    for (cache in caches) {
      if (cache != DrawingCache.EMPTY_DRAWING_CACHE) {
        snapshot.add(cache)
      }
    }
    if (snapshot.isEmpty()) return
    releaseReferenceSnapshot(snapshot, delayMs)
  }

  fun releaseReferenceSnapshot(caches: ArrayList<DrawingCache>, delayMs: Long = CACHE_REFERENCE_RELEASE_DELAY_MS) {
    if (isReleased || caches.isEmpty()) return
    val message = cacheHandler.obtainMessage(WORKER_MSG_RELEASE_REFERENCES, CacheReferenceRelease(caches))
    if (delayMs > 0L) {
      cacheHandler.sendMessageDelayed(message, delayMs)
    } else {
      message.sendToTarget()
    }
  }

  fun clearMeasureCache() {
    if (isReleased) return
    cacheHandler.obtainMessage(WORKER_MSG_CLEAR_CACHE).sendToTarget()
  }

  fun getDanmakuSize(danmaku: DanmakuItemData): Size? = measureSizeCache[danmaku.danmakuId]

  fun release() {
    if (isReleased) return
    if (available) {
      cancelAllRequests()
      isReleased = true
      try {
        cacheThread.quitSafely()
      } catch (e: Exception) {
        Log.e(DanmakuEngine.TAG, "CacheManager.release failed", e)
      }
    }
    isReleased = true
    available = false
  }

  private class CacheInfo(
    val item: DanmakuItem,
    val displayer: DanmakuDisplayer,
    val config: DanmakuConfig,
    val requestKey: Long,
    val buildAfterMeasure: Boolean = false,
    val measureGeneration: Int = config.measureGeneration,
    val cacheGeneration: Int = config.cacheGeneration
  )

  private class CacheTask(
    val what: Int,
    val info: CacheInfo,
    val priority: Int,
    val sequence: Long
  )

  private data class PendingCacheTask(
    val what: Int,
    val info: CacheInfo,
    val priority: Int
  ) {
    companion object {
      fun measure(info: CacheInfo, priority: Int): PendingCacheTask =
        PendingCacheTask(WORKER_MSG_BUILD_MEASURE, info, priority.coerceIn(0, CACHE_PRIORITY_BACKGROUND))

      fun build(info: CacheInfo, priority: Int): PendingCacheTask =
        PendingCacheTask(WORKER_MSG_BUILD_CACHE, info, priority.coerceIn(0, CACHE_PRIORITY_BACKGROUND))
    }
  }

  private class CacheReferenceRelease(
    val caches: ArrayList<DrawingCache>,
    var nextIndex: Int = 0
  )

  private class CacheBoost(
    val requestKey: Long,
    val priority: Int
  )

  private inner class CacheHandler(looper: Looper) : Handler(looper) {
    private val pendingTasks = PriorityQueue<CacheTask> { left, right ->
      if (left.priority != right.priority) {
        left.priority.compareTo(right.priority)
      } else {
        left.sequence.compareTo(right.sequence)
      }
    }
    private var sequence = 0L
    private var dispatching = false
    private var renderSignPending = false

    fun enqueue(task: PendingCacheTask) {
      val message = obtainMessage(WORKER_MSG_QUEUE_TASK, task)
      message.sendToTarget()
    }

    fun boostBuild(requestKey: Long, priority: Int) {
      val message = obtainMessage(WORKER_MSG_BOOST_BUILD, CacheBoost(requestKey, priority))
      message.sendToTarget()
    }

    fun requestBuildSign() {
      removeMessages(WORKER_MSG_RENDER_SIGN)
      sendEmptyMessage(WORKER_MSG_RENDER_SIGN)
    }

    fun cancelQueuedRequests() {
      cancelWorkMessages()
      pendingTasks.clear()
      dispatching = false
      renderSignPending = false
    }

    override fun handleMessage(msg: Message) {
      when (msg.what) {
        WORKER_MSG_QUEUE_TASK -> enqueueInternal(msg.obj as? PendingCacheTask ?: return)
        WORKER_MSG_BOOST_BUILD -> boostBuildInternal(msg.obj as? CacheBoost ?: return)
        WORKER_MSG_DRAIN_QUEUE -> drainOne()
        WORKER_MSG_SEEK -> {
          cancelWorkMessages()
          pendingTasks.clear()
          dispatching = false
          renderSignPending = false
        }
        WORKER_MSG_CLEAR_CACHE -> {
          measureSizeCache.clear()
          pendingMeasureKeys.clear()
          pendingBuildKeys.clear()
          pendingTasks.clear()
          dispatching = false
          renderSignPending = false
          clearSharedDrawingCaches()
        }
        WORKER_MSG_DESTROY -> {
          (msg.obj as? DrawingCache)?.destroy()
        }
        WORKER_MSG_RELEASE_ITEM -> {
          (msg.obj as? DrawingCache)?.let { if (!cachePool.release(it)) it.destroy() }
        }
        WORKER_MSG_RELEASE_REFERENCES -> {
          (msg.obj as? CacheReferenceRelease)?.let(::releaseReferences)
        }
        WORKER_MSG_WARM_UP -> {
          // no-op，只用于触发 cacheHandler/cacheThread 初始化。
        }
        WORKER_MSG_RENDER_SIGN -> {
          if (pendingTasks.isNotEmpty() || dispatching) {
            renderSignPending = true
          } else {
            callbackHandler.sendEmptyMessage(MSG_CACHE_RENDER)
          }
        }
        WORKER_MSG_RELEASE -> {
          pendingTasks.clear()
          dispatching = false
          renderSignPending = false
          removeMessages(WORKER_MSG_RELEASE_REFERENCES)
          cachePool.clear()
          isReleased = true
          cacheThread.quitSafely()
        }
      }
    }

    private fun cancelWorkMessages() {
      removeMessages(WORKER_MSG_QUEUE_TASK)
      removeMessages(WORKER_MSG_DRAIN_QUEUE)
      removeMessages(WORKER_MSG_RENDER_SIGN)
    }

    private fun enqueueInternal(task: PendingCacheTask) {
      if (!isTaskCurrent(task)) {
        clearPendingKey(task)
        return
      }
      pendingTasks.add(CacheTask(task.what, task.info, task.priority, sequence++))
      if (!dispatching) {
        dispatching = true
        sendEmptyMessage(WORKER_MSG_DRAIN_QUEUE)
      }
    }

    private fun boostBuildInternal(boost: CacheBoost) {
      var boostedTask: CacheTask? = null
      val retainedTasks = ArrayList<CacheTask>(pendingTasks.size)
      while (true) {
        val task = pendingTasks.poll() ?: break
        if (task.what == WORKER_MSG_BUILD_CACHE && task.info.requestKey == boost.requestKey) {
          boostedTask = task
          break
        }
        retainedTasks.add(task)
      }
      pendingTasks.addAll(retainedTasks)
      val task = boostedTask ?: return
      val boostedPriority = boost.priority.coerceIn(0, CACHE_PRIORITY_BACKGROUND)
      if (boostedPriority >= task.priority) {
        pendingTasks.add(task)
      } else {
        pendingTasks.add(CacheTask(task.what, task.info, boostedPriority, task.sequence))
      }
    }

    private fun drainOne() {
      if (cancelFlag) {
        Log.d(DanmakuEngine.TAG, "[CacheManager] cancel cache.")
        cancelFlag = false
        pendingMeasureKeys.clear()
        pendingBuildKeys.clear()
        pendingTasks.clear()
        dispatching = false
        renderSignPending = false
        return
      }
      while (true) {
        val task = pendingTasks.poll() ?: run {
          dispatching = false
          dispatchPendingRenderSign()
          return
        }
        if (!isTaskCurrent(task)) {
          clearPendingKey(task)
          continue
        }
        when (task.what) {
          WORKER_MSG_BUILD_MEASURE -> handleMeasure(task.info)
          WORKER_MSG_BUILD_CACHE -> handleBuildCache(task.info)
        }
        if (pendingTasks.isNotEmpty()) {
          sendEmptyMessage(WORKER_MSG_DRAIN_QUEUE)
        } else {
          dispatching = false
          dispatchPendingRenderSign()
        }
        return
      }
    }

    private fun dispatchPendingRenderSign() {
      if (!renderSignPending) return
      renderSignPending = false
      callbackHandler.sendEmptyMessage(MSG_CACHE_RENDER)
    }

    private fun releaseReferences(release: CacheReferenceRelease) {
      var released = 0
      while (release.nextIndex < release.caches.size && released < MAX_REFERENCE_RELEASES_PER_TICK) {
        release.caches[release.nextIndex++].decreaseReference()
        released++
      }
      if (release.nextIndex < release.caches.size) {
        if (isReleased) return
        obtainMessage(WORKER_MSG_RELEASE_REFERENCES, release).sendToTarget()
      }
    }

    private fun handleMeasure(info: CacheInfo) {
      val config = info.config
      val item = info.item

      try {
        startTrace("CacheManager_checkMeasure")
        val drawState = item.drawState
        if (!drawState.isMeasured(info.measureGeneration)) {
          try {
            val size = context.measureRenderer(item, info.displayer, config)
            drawState.width = size.width.toFloat()
            drawState.height = size.height.toFloat()
            drawState.measureGeneration = info.measureGeneration
            measureSizeCache[item.data.danmakuId] = size
            item.state = ItemState.Measured
            item.pendingMeasureGeneration = -1
            enqueueBuildAfterMeasureIfNeeded(info, priority = CACHE_PRIORITY_VISIBLE)
          } catch (e: Exception) {
            Log.e(DanmakuEngine.TAG, "CacheManager.measure failed", e)
            item.state = ItemState.Error
            item.pendingMeasureGeneration = -1
          }
        }
        endTrace()
      } finally {
        pendingMeasureKeys.remove(info.requestKey)
      }
    }

    private fun enqueueBuildAfterMeasureIfNeeded(info: CacheInfo, priority: Int) {
      if (!info.buildAfterMeasure || isReleased) return
      val item = info.item
      val drawState = item.drawState
      if (!drawState.isMeasured(info.measureGeneration)) return
      if (item.state >= ItemState.Rendering &&
        item.pendingCacheGeneration == info.cacheGeneration) return
      if (item.state >= ItemState.Rendered &&
        drawState.cacheGeneration == info.cacheGeneration &&
        drawState.drawingCache != DrawingCache.EMPTY_DRAWING_CACHE &&
        drawState.drawingCache.get() != null) return
      val buildKey = requestKey(item.data.danmakuId, info.cacheGeneration)
      if (!pendingBuildKeys.add(buildKey)) return
      item.state = ItemState.Rendering
      item.pendingCacheGeneration = info.cacheGeneration
      pendingTasks.add(
        CacheTask(
          what = WORKER_MSG_BUILD_CACHE,
          info = CacheInfo(item, info.displayer, info.config, buildKey),
          priority = priority.coerceIn(0, CACHE_PRIORITY_BACKGROUND),
          sequence = sequence++
        )
      )
    }

    private fun handleBuildCache(info: CacheInfo) {
      try {
        startTrace("CacheManager_buildCache")
        val config = info.config
        val item = info.item
        val drawState = item.drawState
        if (!drawState.isMeasured(info.measureGeneration)) {
          item.state = ItemState.Uninitialized
          item.pendingCacheGeneration = -1
          return
        }
        if (sharedCacheGeneration != info.cacheGeneration) {
          clearSharedDrawingCaches()
          sharedCacheGeneration = info.cacheGeneration
        }
        val sharedKey = sharedCacheKey(item, info.displayer, config)
        val sharedCache = sharedKey?.let { key ->
          sharedDrawingCaches[key]?.takeIf { cache ->
            cache.get() != null && isCacheSizeJustified(cache, drawState)
          } ?: run {
            removeSharedDrawingCache(key)
            null
          }
        }
        if (sharedCache != null) {
          replaceItemDrawingCache(drawState, sharedCache)
          item.state = ItemState.Rendered
          item.drawState.cacheGeneration = info.cacheGeneration
          item.pendingCacheGeneration = -1
          endTrace()
          return
        }

        startTrace("CacheManager_checkCache")
        if (drawState.drawingCache.get() == null ||
          drawState.drawingCache == DrawingCache.EMPTY_DRAWING_CACHE ||
          isSizeJustified(drawState)
        ) {
          if (drawState.drawingCache != DrawingCache.EMPTY_DRAWING_CACHE && drawState.drawingCache.get() != null) {
            drawState.drawingCache.decreaseReference()
          }
          drawState.drawingCache =
            cachePool.acquire(drawState.width.toInt(), drawState.height.toInt())
              ?: DrawingCache().build(
                drawState.width.toInt(),
                drawState.height.toInt(),
                info.displayer.densityDpi,
                checkSize = true
              )
          drawState.drawingCache.erase()
          drawState.drawingCache.increaseReference()
          drawState.drawingCache.cacheManager = this@CacheManager
        }
        endTrace()

        startTrace("CacheManager_drawCache")
        val holder = drawState.drawingCache.get()
        if (holder == null) {
          cachePool.release(drawState.drawingCache)
          drawState.drawingCache = DrawingCache.EMPTY_DRAWING_CACHE
          item.state = ItemState.Error
          item.pendingCacheGeneration = -1
          return
        }
        synchronized(drawState) {
          try {
            context.drawRenderer(item, holder.canvas, info.displayer, config)
            item.state = ItemState.Rendered
            item.drawState.cacheGeneration = info.cacheGeneration
            item.pendingCacheGeneration = -1
            if (sharedKey != null) {
              putSharedDrawingCache(sharedKey, drawState.drawingCache)
            }
          } catch (e: Exception) {
            Log.e(DanmakuEngine.TAG, "CacheManager.draw failed", e)
            item.state = ItemState.Error
            item.pendingCacheGeneration = -1
          }
        }
        endTrace()
//          callbackHandler.obtainMessage(MSG_CACHE_BUILT, info.item).sendToTarget()
        endTrace()
      } finally {
        pendingBuildKeys.remove(info.requestKey)
      }
    }

    private fun isTaskCurrent(task: CacheTask): Boolean =
      isTaskCurrent(PendingCacheTask(task.what, task.info, task.priority))

    private fun isTaskCurrent(task: PendingCacheTask): Boolean {
      val info = task.info
      val item = info.item
      return when (task.what) {
        WORKER_MSG_BUILD_MEASURE ->
          item.state == ItemState.Measuring &&
            item.pendingMeasureGeneration == info.measureGeneration &&
            !item.drawState.isMeasured(info.measureGeneration)
        WORKER_MSG_BUILD_CACHE ->
          item.state >= ItemState.Rendering &&
            item.pendingCacheGeneration == info.cacheGeneration &&
            item.drawState.isMeasured(info.measureGeneration) &&
            item.drawState.cacheGeneration != info.cacheGeneration
        else -> true
      }
    }

    private fun clearPendingKey(task: CacheTask) =
      clearPendingKey(PendingCacheTask(task.what, task.info, task.priority))

    private fun clearPendingKey(task: PendingCacheTask) {
      when (task.what) {
        WORKER_MSG_BUILD_MEASURE -> pendingMeasureKeys.remove(task.info.requestKey)
        WORKER_MSG_BUILD_CACHE -> pendingBuildKeys.remove(task.info.requestKey)
      }
    }

    private fun isSizeJustified(drawState: DrawState): Boolean =
      drawState.drawingCache.width < drawState.width ||
        drawState.drawingCache.height < drawState.height ||
        drawState.drawingCache.width - drawState.width > 5 ||
        drawState.drawingCache.height - drawState.height > 5
  }

  private fun putSharedDrawingCache(key: Long, cache: DrawingCache) {
    if (cache == DrawingCache.EMPTY_DRAWING_CACHE || cache.get() == null) return
    if (sharedDrawingCaches.containsKey(key)) return
    // sharedDrawingCaches 自己持有一份引用，避免最后一个弹幕离场后缓存立刻回池并被擦除。
    cache.increaseReference()
    sharedDrawingCaches[key] = cache
  }

  private fun removeSharedDrawingCache(key: Long) {
    val cache = sharedDrawingCaches.remove(key) ?: return
    releaseSharedDrawingCache(cache)
  }

  private fun clearSharedDrawingCaches() {
    if (sharedDrawingCaches.isEmpty()) return
    val caches = sharedDrawingCaches.values.toList()
    sharedDrawingCaches.clear()
    caches.forEach(::releaseSharedDrawingCache)
  }

  private fun releaseSharedDrawingCache(cache: DrawingCache) {
    cache.decreaseReference()
    cache.destroy()
  }

  private fun replaceItemDrawingCache(drawState: DrawState, cache: DrawingCache) {
    if (drawState.drawingCache == cache) return
    if (drawState.drawingCache != DrawingCache.EMPTY_DRAWING_CACHE) {
      drawState.drawingCache.decreaseReference()
    }
    cache.increaseReference()
    cache.cacheManager = this
    drawState.drawingCache = cache
  }

  private fun isCacheSizeJustified(cache: DrawingCache, drawState: DrawState): Boolean =
    cache.width >= drawState.width &&
      cache.height >= drawState.height &&
      cache.width - drawState.width <= 5 &&
      cache.height - drawState.height <= 5

  private fun sharedCacheKey(
    item: DanmakuItem,
    displayer: DanmakuDisplayer,
    config: DanmakuConfig
  ): Long? {
    val data = item.data
    // 先只复用最常见的普通文字弹幕；VIP/自发/特殊样式可能依赖外部贴图或画布边框。
    if (data.renderFlags != DanmakuItemData.RENDER_FLAG_NONE ||
      data.danmakuStyle != DanmakuItemData.DANMAKU_STYLE_NONE
    ) {
      return null
    }
    var acc = FNV_OFFSET
    acc = mix(acc, data.content.hashCode().toLong())
    acc = mix(acc, data.content.length.toLong())
    acc = mix(acc, data.textSize.toLong())
    acc = mix(acc, data.textColor.toLong())
    acc = mix(acc, if (config.bold) 1L else 0L)
    acc = mix(acc, config.textSizeScale.toBits().toLong())
    acc = mix(acc, displayer.density.toBits().toLong())
    acc = mix(acc, displayer.densityDpi.toLong())
    return acc
  }

  private fun mix(acc: Long, value: Long): Long = (acc xor value) * FNV_PRIME

  private fun requestKey(danmakuId: Long, generation: Int): Long =
    mix(mix(FNV_OFFSET, danmakuId), generation.toLong())

  companion object {
    const val THREAD_NAME = "AkDanmaku-Cache"

    private const val WORKER_MSG_RELEASE = -100

    private const val WORKER_MSG_RENDER_SIGN = -1
    private const val WORKER_MSG_BUILD_MEASURE = 0
    private const val WORKER_MSG_BUILD_CACHE = 1
    private const val WORKER_MSG_SEEK = 2
    private const val WORKER_MSG_CLEAR_CACHE = 3
    private const val WORKER_MSG_DESTROY = 4
    private const val WORKER_MSG_RELEASE_ITEM = 5
    private const val WORKER_MSG_WARM_UP = 6
    private const val WORKER_MSG_QUEUE_TASK = 7
    private const val WORKER_MSG_DRAIN_QUEUE = 8
    private const val WORKER_MSG_RELEASE_REFERENCES = 9
    private const val WORKER_MSG_BOOST_BUILD = 10

    const val MSG_CACHE_RENDER = -1
    const val MSG_CACHE_MEASURED = 0
    const val MSG_CACHE_BUILT = 1
    const val MSG_CACHE_FAILED = 2

    private const val MAX_SHARED_DRAWING_CACHE = 384
    private const val MAX_REFERENCE_RELEASES_PER_TICK = 4
    private const val CACHE_REFERENCE_RELEASE_DELAY_MS = 48L
    private const val CACHE_PRIORITY_VISIBLE = 0
    private const val CACHE_PRIORITY_BACKGROUND = 2
    private const val FNV_OFFSET = -3750763034362895579L
    private const val FNV_PRIME = 1099511628211L
  }
}
