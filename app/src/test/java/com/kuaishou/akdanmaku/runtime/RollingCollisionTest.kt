package com.kuaishou.akdanmaku.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RollingCollisionTest {

  @Test
  fun longItemDelayedStartDoesNotCollideWithExpiredPreviousItem() {
    assertFalse(
      RollingCollision.willCollide(
        previousStartTime = 0L,
        previousWidth = 3_200f,
        nextStartTime = 4_100L,
        nextWidth = 3_200f,
        screenWidth = 1_920,
        nowMs = 4_100L,
        durationMs = 3_800L,
        overlapFraction = 0f
      )
    )
  }

  @Test
  fun collisionChecksBothCurrentAndEndPositions() {
    assertTrue(
      RollingCollision.willCollide(
        previousStartTime = 0L,
        previousWidth = 400f,
        nextStartTime = 100L,
        nextWidth = 400f,
        screenWidth = 1_000,
        nowMs = 100L,
        durationMs = 1_000L,
        overlapFraction = 0f
      )
    )
  }

  @Test
  fun enoughTimeGapAllowsSameRowReuse() {
    assertFalse(
      RollingCollision.willCollide(
        previousStartTime = 0L,
        previousWidth = 200f,
        nextStartTime = 650L,
        nextWidth = 200f,
        screenWidth = 1_000,
        nowMs = 650L,
        durationMs = 1_000L,
        overlapFraction = 0f
      )
    )
  }
}
