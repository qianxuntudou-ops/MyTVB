package com.kuaishou.akdanmaku.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DanmakuLoadShedderTest {

  @Test
  fun enqueueBudget_reducesAsLevelRises() {
    assertEquals(32, DanmakuLoadShedder.enqueueBudget(0))
    assertEquals(24, DanmakuLoadShedder.enqueueBudget(1))
    assertEquals(12, DanmakuLoadShedder.enqueueBudget(2))
    assertEquals(6, DanmakuLoadShedder.enqueueBudget(3))
  }

  @Test
  fun nextLevel_raisesQuicklyUnderHeavyPressure() {
    assertEquals(
      DanmakuLoadShedder.MAX_LEVEL,
      DanmakuLoadShedder.nextLevel(
        currentLevel = 0,
        layoutCostMs = 50L,
        rejectedCount = 0,
        unmeasuredCount = 0
      )
    )
    assertEquals(2, DanmakuLoadShedder.nextLevel(0, layoutCostMs = 24L, rejectedCount = 0, unmeasuredCount = 0))
    assertEquals(1, DanmakuLoadShedder.nextLevel(0, layoutCostMs = 12L, rejectedCount = 0, unmeasuredCount = 0))
  }

  @Test
  fun nextLevel_recoversOneStepWhenPressureDrops() {
    assertEquals(2, DanmakuLoadShedder.nextLevel(3, layoutCostMs = 1L, rejectedCount = 0, unmeasuredCount = 0))
    assertEquals(0, DanmakuLoadShedder.nextLevel(0, layoutCostMs = 1L, rejectedCount = 0, unmeasuredCount = 0))
  }

  @Test
  fun shouldSkipItem_keepsOrdinaryDanmakuUnderPressure() {
    assertFalse(DanmakuLoadShedder.shouldSkipItem(DanmakuLoadShedder.MAX_LEVEL))
  }
}
