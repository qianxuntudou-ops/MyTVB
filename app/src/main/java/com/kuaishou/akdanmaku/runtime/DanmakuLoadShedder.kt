package com.kuaishou.akdanmaku.runtime

internal object DanmakuLoadShedder {
  fun enqueueBudget(level: Int): Int {
    return when (level.coerceIn(0, MAX_LEVEL)) {
      0 -> 32
      1 -> 24
      2 -> 12
      else -> 6
    }
  }

  fun nextLevel(currentLevel: Int, layoutCostMs: Long, rejectedCount: Int, unmeasuredCount: Int): Int {
    val level = currentLevel.coerceIn(0, MAX_LEVEL)
    return when {
      layoutCostMs >= 48L || rejectedCount >= 32 || unmeasuredCount >= 48 -> MAX_LEVEL
      layoutCostMs >= 24L || rejectedCount >= 16 || unmeasuredCount >= 24 -> maxOf(level, 2)
      layoutCostMs >= 12L || rejectedCount >= 8 || unmeasuredCount >= 12 -> maxOf(level, 1)
      level > 0 -> level - 1
      else -> 0
    }
  }

  fun shouldSkipItem(level: Int): Boolean {
    if (level <= 0) return false
    // 负载保护只缩小每帧入队预算，不再永久丢弃普通弹幕；否则一次短抖动会让后续窗口明显缺弹幕。
    return false
  }

  const val MAX_LEVEL = 3
}
