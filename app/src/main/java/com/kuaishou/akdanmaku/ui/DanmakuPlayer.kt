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

package com.kuaishou.akdanmaku.ui

import android.graphics.Canvas
import android.graphics.Point
import android.graphics.RectF
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import com.kuaishou.akdanmaku.ext.AkLog as Log
import android.view.Choreographer
import androidx.core.math.MathUtils.clamp
import com.kuaishou.akdanmaku.DanmakuConfig
import com.kuaishou.akdanmaku.data.DanmakuItemData
import com.kuaishou.akdanmaku.data.DanmakuItem
import com.kuaishou.akdanmaku.data.DataSource
import com.kuaishou.akdanmaku.engine.DanmakuEngine
import com.kuaishou.akdanmaku.ext.endTrace
import com.kuaishou.akdanmaku.ext.startTrace
import com.kuaishou.akdanmaku.render.DanmakuRenderer
import com.tutu.myblbl.core.common.log.AppLog
import com.kuaishou.akdanmaku.utils.Fraction
import com.kuaishou.akdanmaku.utils.ObjectPool
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Semaphore
import kotlin.math.max

/**
 *
 * 弹幕播放器，与 [DanmakuView] 形成类似于视频播放器的弹幕播放结构
 * 此类应当在共享同一个弹幕播放的场景间进行共享，它持有着
 * - 播放上下文（计时器，渲染器，缓存管理等）
 * - 渲染引擎
 * 在与一个 [DanmakuView] 绑定后会通过 [Choreographer] 进行帧同步，通过信号量在绘制和后台计算之间进行同步
 *
 * 内部具有一个用于执行计算的线程，几乎所有对外 API 均为同步返回，具体操作在此线程中进行
 *
 * @param renderer 业务端自定义的弹幕渲染器
 */
@Suppress("unused")
class DanmakuPlayer(renderer: DanmakuRenderer, dataSource: DataSource? = null) {

  companion object {
    internal const val MSG_FRAME_UPDATE = 2101
    internal const val NOTIFY_DISPLAYER_SIZE_CHANGE = 2201

    private const val PLAYER_WIDTH = 682
    private const val RELEASE_LATCH_TIMEOUT_MS = 220L
    private const val MAX_PRIME_MEASURE_ON_UPDATE = 160
    private const val MAX_PRIME_MEASURE_ON_APPEND = 12
    private const val DRAW_WAIT_SKIP_LOG_THRESHOLD = 4
    private const val DRAW_WAIT_SKIP_LOG_INTERVAL_MS = 1000L
    const val MIN_DANMAKU_DURATION: Long = 4000
    const val MAX_DANMAKU_DURATION_HIGH_DENSITY: Long = 9000
    /**
     * 是否手动控制 Step 流程
     */
    var isManualStep = false
  }

  private var danmakuView: DanmakuView? = null
  internal val engine = DanmakuEngine.get(renderer)
  private val dataSourceListener = object : DataSource.DataChangeListener {
    override fun onDataAdded(additionalItems: List<DanmakuItem>) {
      engine.runtime.primeMeasureItems(additionalItems, MAX_PRIME_MEASURE_ON_APPEND)
      engine.runtime.addItems(additionalItems)
    }

    override fun onDataRemoved(removalItems: List<DanmakuItem>) {
    }
  }
  private val actionThread by lazy  { HandlerThread("ActionThread").apply { start() } }
  private val actionHandler by lazy { ActionHandler(actionThread.looper) }
  private val frameCallback by lazy { FrameCallback(actionHandler) }

  private var currentDisplayerWidth = 0
  private var currentDisplayerHeight = 0
  private var currentDisplayerSizeFactor = 1f
  private var config: DanmakuConfig? = null
  private var skippedDrawWaitFrames = 0
  private var lastDrawWaitSkipLogAtMs = 0L

  private val drawSemaphore = Semaphore(0)

  @Volatile
  private var started = false

  /**
   * 弹幕埋点所需的接口
   */
  var listener: DanmakuListener? = null
    set(value) {
      if (field != value) {
        field = value
        engine.runtime.listener = value
      }
    }
  @Volatile
  var isReleased: Boolean = false
    private set

  val cacheHit: Fraction?
    get() = engine.runtime.cacheHit

  init {
    dataSource?.setListener(dataSourceListener)
  }

  private fun postFrameCallback() {
    if (!started || isReleased || !actionThread.isAlive) {
      return
    }
    Choreographer.getInstance().postFrameCallback(frameCallback)
  }

  private fun updateFrame(deltaTimeSeconds: Float? = null) {
    if (!started) {
      return
    }

    val frameStartedAtMs = SystemClock.elapsedRealtime()
    var waitDrawMs = 0L
    if (isManualStep) {
      // Time goes one step for manual debug.
      engine.step(deltaTimeSeconds)
    } else {
      // Prepare next frameCallback.
      postFrameCallback()
      if (!drawSemaphore.tryAcquire()) {
        skippedDrawWaitFrames++
        danmakuView?.postInvalidateOnAnimation()
        logDrawWaitSkipIfNeeded(frameStartedAtMs)
        return
      }
      skippedDrawWaitFrames = 0
    }
    if (!started) {
      return
    }
    startTrace("updateFrame")
    // update entities before system update
    engine.preAct()
    // Do work in actionThread.
    val actStartedAtMs = SystemClock.elapsedRealtime()
    engine.act()
    val actMs = SystemClock.elapsedRealtime() - actStartedAtMs
    // Post invalidate view to force onDraw's call on next frame.
    startTrace("postInvalidate")
    danmakuView?.postInvalidateOnAnimation()
    endTrace()
    endTrace()
    val totalMs = SystemClock.elapsedRealtime() - frameStartedAtMs
    if (waitDrawMs >= 80L || actMs >= 12L || totalMs >= 96L) {
      AppLog.w(
        "PlaybackPerf",
        "danmaku_action waitDraw=${waitDrawMs}ms act=${actMs}ms total=${totalMs}ms manual=$isManualStep"
      )
    }
  }

  internal fun draw(canvas: Canvas) {
    if (isReleased) {
      return
    }
    if (!started) {
      // 暂停只冻结时间线，不能把当前弹幕帧清空；否则播放器暂停后 View 重绘会让弹幕瞬间消失。
      engine.draw(canvas) {
        // 暂停态没有 action 线程等待绘制信号，这里只保留最后一帧渲染。
      }
      return
    }
    if (!isManualStep) {
      // Time goes one step.
      engine.step()
    }
    drawSemaphore.tryAcquire()
    engine.draw(canvas) {
      releaseSemaphore()
    }
  }

  private fun releaseSemaphore() {
    // Acquired or on the first draw(with init permit: 0).
    if (drawSemaphore.availablePermits() == 0) {
      drawSemaphore.release()
    }
  }

  private fun logDrawWaitSkipIfNeeded(nowMs: Long) {
    if (skippedDrawWaitFrames < DRAW_WAIT_SKIP_LOG_THRESHOLD) return
    if (nowMs - lastDrawWaitSkipLogAtMs < DRAW_WAIT_SKIP_LOG_INTERVAL_MS) return
    lastDrawWaitSkipLogAtMs = nowMs
    AppLog.w(
      "PlaybackPerf",
      "danmaku_action skip_wait_draw frames=$skippedDrawWaitFrames"
    )
  }

  /**
   * For debug use, step manually.
   */
  fun step(deltaTimeMs: Int) {
    if (isManualStep) {
      actionHandler.obtainMessage(MSG_FRAME_UPDATE, deltaTimeMs, 0).sendToTarget()
    }
  }

  /**
   * 将播放器与一个 DanmakuView 绑定，前一个被绑定的会自动解锁。
   * 绑定后弹幕的绘制将在此 View 上进行
   */
  fun bindView(danmakuView: DanmakuView) {
    this.danmakuView?.danmakuPlayer = null
    this.danmakuView = danmakuView
    danmakuView.danmakuPlayer = this
    engine.context.displayer = danmakuView.displayer
    danmakuView.postInvalidate()
  }

  /**
   * 播放弹幕
   *
   * @param danmakuConfig 弹幕配置
   */
  fun start(danmakuConfig: DanmakuConfig? = null) {
    danmakuConfig?.let {
      updateConfig(it)
    }
    engine.start()
    if (!started) {
      started = true
      if (!isManualStep) {
        startFrameLoop()
      }
    }
  }

  fun pause() {
    stopFrameLoop()
    engine.pause()
  }

  fun stop() {
    stopFrameLoop()
    seekTo(0)
    engine.pause()
  }

  private fun stopFrameLoop() {
    if (!started) {
      return
    }
    started = false
    releaseSemaphore()
    if (actionThread.isAlive) {
      actionHandler.postAtFrontOfQueue {
        runCatching {
          Choreographer.getInstance().removeFrameCallback(frameCallback)
        }
        actionHandler.removeMessages(MSG_FRAME_UPDATE)
      }
    } else {
      actionHandler.removeMessages(MSG_FRAME_UPDATE)
    }
  }

  private fun startFrameLoop() {
    // 恢复播放时主动踢一帧。只挂 Choreographer 在部分暂停/恢复时序下可能没有立即触发 View 绘制，
    // action 线程就会停在等待绘制信号的位置，表现为弹幕停在暂停画面不再滚动。
    releaseSemaphore()
    danmakuView?.postInvalidateOnAnimation()
    if (actionThread.isAlive) {
      actionHandler.post {
        actionHandler.removeMessages(MSG_FRAME_UPDATE)
        runCatching {
          Choreographer.getInstance().removeFrameCallback(frameCallback)
        }
        postFrameCallback()
        actionHandler.sendEmptyMessage(MSG_FRAME_UPDATE)
      }
    }
  }

  /**
   * 释放弹幕播放器，释放后弹幕播放器将不再可用。
   */
  fun release() {
    if (isReleased) {
      return
    }
    isReleased = true
    started = false
    releaseSemaphore()
    val releaseLatch = CountDownLatch(1)
    if (actionThread.isAlive) {
      actionHandler.postAtFrontOfQueue {
        runCatching {
          Choreographer.getInstance().removeFrameCallback(frameCallback)
        }
        actionHandler.removeMessages(MSG_FRAME_UPDATE)
        actionHandler.removeCallbacksAndMessages(null)
        releaseLatch.countDown()
      }
      releaseLatch.await(RELEASE_LATCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    } else {
      actionHandler.removeCallbacksAndMessages(null)
    }
    runCatching {
      // Best-effort cleanup when release() is called on main thread.
      Choreographer.getInstance().removeFrameCallback(frameCallback)
    }
    actionThread.quitSafely()
    actionThread.join(50L)
    engine.release()
  }

  fun seekTo(positionMs: Long) {
    Log.d(DanmakuEngine.TAG, "[Player] SeekTo($positionMs)")
    getConfig()?.updateFirstShown()
    engine.seekTo(max(positionMs, 0L))
  }

  fun getCurrentTimeMs(): Long {
    return engine.getCurrentTimeMs()
  }

  fun updatePlaySpeed(speed: Float) {
    engine.updateTimerFactor(speed)
  }

  fun clearData() {
    engine.runtime.clearAllData()
  }

  fun updateData(dataList: List<DanmakuItemData>): List<DanmakuItem> {
    val items = ArrayList<DanmakuItem>(dataList.size)
    for (data in dataList) {
      items.add(obtainItem(data))
    }
    engine.runtime.primeMeasureItems(items, MAX_PRIME_MEASURE_ON_UPDATE)
    engine.runtime.addItems(items)
    return items
  }

  /**
   * 弹幕目前统一的数据结构就是 DanmakuItem，他是 DanmakuItemData 的超集，也是被定义为
   * 可以进行扩展的
   */
  fun updateItems(items: List<DanmakuItem>) {
    engine.runtime.primeMeasureItems(items, MAX_PRIME_MEASURE_ON_APPEND)
    engine.runtime.addItems(items)
  }

  fun send(danmaku: DanmakuItemData): DanmakuItem {
    val item = obtainItem(danmaku)
    engine.runtime.primeMeasureItem(item)
    engine.runtime.addItem(item)
    return item
  }

  fun send(item: DanmakuItem) {
    engine.runtime.primeMeasureItem(item)
    engine.runtime.addItem(item)
  }

  fun setLiveMode(enabled: Boolean) {
    engine.runtime.liveMode = enabled
  }

  /**
   * 更新一个弹幕
   */
  fun updateItem(item: DanmakuItem) {
    engine.runtime.updateItem(item)
  }

  fun updateConfig(danmakuConfig: DanmakuConfig?) {
    config = danmakuConfig
    val config = danmakuConfig ?: return
    if (started) {
      engine.updateConfig(config)
    } else {
      // 启动前配置是初始状态，不应在第一帧再从默认值切换，避免首屏弹幕重布局。
      engine.setInitialConfig(config)
    }
  }

  fun getConfig(): DanmakuConfig? = engine.getConfig()

  fun getDanmakusAtPoint(point: Point): List<DanmakuItem>? {
    return engine.runtime.getDanmakus(point)
  }

  fun getDanmakusInRect(hitRect: RectF): List<DanmakuItem>? {
    return engine.runtime.getDanmakus(hitRect)
  }

  fun hold(item: DanmakuItem?) {
    engine.runtime.hold(item)
  }

  fun obtainItem(danmaku: DanmakuItemData): DanmakuItem =
    ObjectPool.obtainItem(danmaku, this)

  fun releaseItem(item: DanmakuItem) {
    ObjectPool.releaseItem(item)
  }

  internal fun notifyDisplayerSizeChanged(width: Int, height: Int) {
    val displayer = engine.context.displayer
    updateViewportState(width, height, displayer.getViewportSizeFactor())
    updateMaxDanmakuDuration()
    if (displayer.width != width || displayer.height != height) {
      Log.d(DanmakuEngine.TAG, "notifyDisplayerSizeChanged($width, $height)")
      displayer.width = width
      displayer.height = height
      actionHandler.obtainMessage(NOTIFY_DISPLAYER_SIZE_CHANGE).sendToTarget()
    }
  }

  private fun updateViewportState(width: Int, height: Int, viewportSizeFactor: Float) {
    val config = this.config ?: return
    if (currentDisplayerWidth != width ||
      currentDisplayerHeight != height ||
      currentDisplayerSizeFactor != viewportSizeFactor) {
      val duration = clamp(
        (config.rollingDurationMs * (viewportSizeFactor * width / PLAYER_WIDTH)).toLong(),
        MIN_DANMAKU_DURATION,
        MAX_DANMAKU_DURATION_HIGH_DENSITY
      )
      if (config.durationMs != duration) {
        config.durationMs = duration
        config.updateRetainer()
        config.updateLayout()
        config.updateVisibility()
      }
      currentDisplayerWidth = width
      currentDisplayerHeight = height
      currentDisplayerSizeFactor = viewportSizeFactor
    }
  }

  private fun updateMaxDanmakuDuration() {
    // FIXME distinguish differ danmaku type duration
  }

  private inner class ActionHandler(looper: Looper) : Handler(looper) {
    override fun handleMessage(msg: Message) {
      when (msg.what) {
        MSG_FRAME_UPDATE -> {
          val deltaTimeSeconds = if (msg.arg1 > 0) {
            msg.arg1 / 1000.0f
          } else null
          updateFrame(deltaTimeSeconds)
        }
        NOTIFY_DISPLAYER_SIZE_CHANGE -> {
          val newConfig = engine.context.config
          if (started) {
            newConfig.updateLayout()
            newConfig.updateMeasure()
            newConfig.updateCache()
            newConfig.updateRetainer()
          }
        }
      }
    }
  }

  private class FrameCallback(private val handler: Handler) : Choreographer.FrameCallback {

    override fun doFrame(frameTimeNanos: Long) {
      handler.removeMessages(MSG_FRAME_UPDATE)
      handler.sendEmptyMessage(MSG_FRAME_UPDATE)
    }
  }
}
