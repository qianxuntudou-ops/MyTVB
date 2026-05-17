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

/*******************************************************************************
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
 ******************************************************************************/
package com.kuaishou.akdanmaku

import com.kuaishou.akdanmaku.ext.AkLog as Log
import com.kuaishou.akdanmaku.ecs.DanmakuEngine
import com.kuaishou.akdanmaku.ecs.component.filter.DanmakuDataFilter
import com.kuaishou.akdanmaku.ecs.component.filter.DanmakuLayoutFilter
import com.kuaishou.akdanmaku.ext.RETAINER_AKDANMAKU

/**
 * 弹幕场景设置参数
 */
data class DanmakuConfig(

  var retainerPolicy: Int = RETAINER_AKDANMAKU,

  /**
   * 预加载缓存的时间提前量
   */
  var preCacheTimeMs: Long = 100L,

  /**
   * 弹幕的显示时长，滚动类型的弹幕为从屏幕一端出现到屏幕另一端完全移出的时间
   */
  var durationMs: Long = DEFAULT_DURATION,

  /**
   * 滚动弹幕持续时间
   */
  var rollingDurationMs: Long = durationMs,

  /**
   * 文本缩放倍数
   */
  var textSizeScale: Float = 1f,

  /**
   * 播放速率
   */
  var timeFactor: Float = 1f,

  /**
   * 滚动弹幕屏幕的显示区域
   */
  var screenPart: Float = 1f,

  /**
   * 弹幕显示的透明度（不会影响选中的）
   */
  var alpha: Float = 1f,

  /**
   * 弹幕是否以粗体渲染
   */
  var bold: Boolean = true,

  /**
   * 绘图 Bitmap 的密度
   */
  var density: Int = 160,

  /**
   * 弹幕是否可见
   */
  var visibility: Boolean = true,

  /**
   * 是否允许重叠
   */
  var allowOverlap: Boolean = false,

  /**
   * 同行弹幕允许的重叠比例，0 = 不允许重叠，0.5 = 允许重叠一半宽度
   */
  var overlapFraction: Float = 0f,

  /**
   * 可见性标记，当可见性发生变化时更新
   */
  var visibilityGeneration: Int = 0,

  /**
   * 布局变化标记位，当需要对弹幕重新布局时更新此值
   */
  var layoutGeneration: Int = 0,

  /**
   * 缓存标记位，当弹幕本身样式发生变化需要对绘制样式与缓存进行更新时更新此值
   */
  var cacheGeneration: Int = 0,

  /**
   * 测量标记位，与缓存类似
   */
  var measureGeneration: Int = 0,

  /**
   * 过滤器标记位，当过滤器发生变动时（个数或具体值）更新此值
   */
  var filterGeneration: Int = 0,

  /**
   * 排布标记位，当需要清空排布器，重新高度排布时更新此值
   */
  var retainerGeneration: Int = 0,

  /**
   * 渲染标记位，一般意义上每一次 Update 后都会更新此值，用于计算跳帧与绘制
   */
  var renderGeneration: Int = 0,

  /**
   * 首次显示标记位，主要用于埋点
   */
  internal var firstShownGeneration: Int = 0,
  var dataFilter: List<DanmakuDataFilter> = emptyList(),
  var layoutFilter: List<DanmakuLayoutFilter> = emptyList()
) {

  var allGeneration =
    visibilityGeneration +
      layoutGeneration +
      cacheGeneration +
      measureGeneration +
      filterGeneration +
      retainerGeneration +
      renderGeneration
    private set

  fun updateVisibility() {
    visibilityGeneration++
    allGeneration++
    logGeneration("visibility", visibilityGeneration)
  }

  fun updateCache() {
    cacheGeneration++
    allGeneration++
    logGeneration("cache", cacheGeneration)
  }

  fun updateFilter() {
    filterGeneration++
    allGeneration++
    logGeneration("filter", filterGeneration)
  }

  fun updateMeasure() {
    measureGeneration++
    allGeneration++
    logGeneration("measure", measureGeneration)
  }

  fun updateLayout() {
    layoutGeneration++
    allGeneration++
    logGeneration("layout", layoutGeneration)
  }

  fun updateRetainer() {
    retainerGeneration++
    allGeneration++
    logGeneration("retainer", retainerGeneration)
  }

  fun updateRender() {
    renderGeneration++
    allGeneration++
  }

  fun updateFirstShown() {
    firstShownGeneration++
  }

  companion object {
    private fun logGeneration(type: String, generation: Int) {
      Log.d(DanmakuEngine.TAG, "Generation[$type] update to $generation")
    }

    // 单次释放缓存的最大数量
    const val MAX_RELEASE_PER_DRAIN = 48

    const val DEFAULT_DURATION = 3800L

    /**
     * 根据屏幕分辨率和设备可用内存动态计算弹幕缓存池大小。
     *
     * - 低分辨率 (<=720p): 32MB
     * - 中分辨率 (720p < x <= 1080p): 50MB
     * - 高分辨率 (>1080p): 72MB
     *
     * 在设备可用内存较低 (<512MB) 时，以上值减半。
     */
    fun computeCachePoolMaxMemorySize(screenWidth: Int, screenHeight: Int): Int {
      val mb = 1024 * 1024
      val maxPixels = screenWidth * screenHeight

      val baseSize = when {
        maxPixels <= 1280 * 720 -> 32 * mb   // <=720p
        maxPixels <= 1920 * 1080 -> 50 * mb  // <=1080p
        else -> 72 * mb                       // >1080p
      }

      // 低内存设备减半
      val runtime = Runtime.getRuntime()
      val availableMb = (runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())) / mb
      return if (availableMb < 512) baseSize / 2 else baseSize
    }

    /**
     * 全局缓存池最大内存（字节），默认 50MB，可由 [computeCachePoolMaxMemorySize] 动态设置。
     */
    var CACHE_POOL_MAX_MEMORY_SIZE = 1024 * 1024 * 50
  }
}
