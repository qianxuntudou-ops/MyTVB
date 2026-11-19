package com.mytvb.feature.player.view

import com.mytvb.feature.player.mergePublishedDanmakuSnapshot
import com.mytvb.feature.player.DanmakuFilterContext
import com.mytvb.feature.player.danmakuAvailabilityState
import com.mytvb.feature.player.shouldAppendDanmakuUpdate
import com.mytvb.feature.player.shouldResetPublishedDanmakuState
import com.mytvb.feature.player.danmaku.mergeSortedDanmakuModels
import com.mytvb.feature.player.danmaku.canAppendPreparedDanmakuIncrementally
import com.mytvb.feature.player.danmaku.canInjectPreparedDanmaku
import com.mytvb.feature.player.danmaku.resolveDanmakuTailPatchStartMs
import com.mytvb.feature.player.danmaku.DanmakuTimelineOperation
import com.mytvb.feature.player.danmaku.resolveDanmakuTimelineOperation
import com.mytvb.feature.player.danmaku.rollingDurationMsForTailPatch
import com.mytvb.feature.player.danmaku.common.DanmakuDuplicateMergePolicy
import com.mytvb.feature.player.danmaku.common.nextDanmakuPreparationGeneration
import com.mytvb.model.dm.DmModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DanmakuPreparationGenerationTest {

  @Test
  fun replaceStartsANewGeneration() {
    assertEquals(8L, nextDanmakuPreparationGeneration(current = 7L, replace = true))
  }

  @Test
  fun appendKeepsTheCurrentGeneration() {
    assertEquals(7L, nextDanmakuPreparationGeneration(current = 7L, replace = false))
  }

  @Test
  fun appendMergeKeepsBothBatchesInTimelineOrder() {
    val existing = listOf(dm(1, 100), dm(3, 300))
    val incoming = listOf(dm(4, 400), dm(2, 200))

    val merged = mergeSortedDanmakuModels(existing, incoming)

    assertEquals(listOf(1L, 2L, 3L, 4L), merged.map { it.id })
    assertEquals(listOf(100, 200, 300, 400), merged.map { it.progress })
  }

  @Test
  fun liteAppendOnlyKeepsExistingPreparationWhenTailCannotMergeAcrossBatches() {
    val existing = listOf(dm(1, 1_000, "same"), dm(2, 2_000, "tail"))
    val safeIncoming = listOf(dm(4, 2_500, "different"))

    assertFalse(
      DanmakuDuplicateMergePolicy.canAppendWithoutRebuildingExisting(
        existingSorted = existing,
        incomingSorted = listOf(dm(3, 2_500, "same")),
        mergeDuplicate = true
      )
    )
    assertTrue(
      DanmakuDuplicateMergePolicy.canAppendWithoutRebuildingExisting(
        existingSorted = existing,
        incomingSorted = safeIncoming,
        mergeDuplicate = true
      )
    )
    assertTrue(
      DanmakuDuplicateMergePolicy.canAppendWithoutRebuildingExisting(
        existingSorted = existing,
        incomingSorted = listOf(dm(5, 4_001, "same")),
        mergeDuplicate = true
      )
    )
    assertFalse(
      DanmakuDuplicateMergePolicy.canAppendWithoutRebuildingExisting(
        existingSorted = existing,
        incomingSorted = listOf(dm(6, 1_500, "different")),
        mergeDuplicate = false
      )
    )
    assertEquals(
      DanmakuDuplicateMergePolicy.merge(existing + safeIncoming),
      DanmakuDuplicateMergePolicy.merge(existing) + DanmakuDuplicateMergePolicy.merge(safeIncoming)
    )
  }

  @Test
  fun liteAppendFallsBackWhenFilterContextChanges() {
    val oldContext = DanmakuFilterContext.EMPTY
    val newContext = oldContext.copy(reportFilters = listOf("blocked"))

    assertTrue(canAppendPreparedDanmakuIncrementally(true, oldContext, oldContext))
    assertFalse(canAppendPreparedDanmakuIncrementally(true, oldContext, newContext))
  }

  @Test
  fun timelineOperationOnlyResetsForNewTimelineOrFilterChange() {
    val context = DanmakuFilterContext.EMPTY

    assertEquals(
      DanmakuTimelineOperation.Append,
      resolveDanmakuTimelineOperation(true, true, context, context),
    )
    assertEquals(
      DanmakuTimelineOperation.ReplaceFutureTail,
      resolveDanmakuTimelineOperation(false, true, context, context),
    )
    assertEquals(
      DanmakuTimelineOperation.Reset,
      resolveDanmakuTimelineOperation(true, false, context, context),
    )
    assertEquals(
      DanmakuTimelineOperation.Reset,
      resolveDanmakuTimelineOperation(true, true, context, context.copy(reportFilters = listOf("blocked"))),
    )
  }

  @Test
  fun mergeConflictPatchesOnlyTheFutureTail() {
    assertEquals(
      18_000,
      resolveDanmakuTailPatchStartMs(
        firstIncomingTimeMs = 12_000,
        currentPositionMs = 12_000L,
      ),
    )
  }

  @Test
  fun tailPatchStartsAtTheMergeBoundaryWhenItIsAlreadyFuture() {
    assertEquals(
      28_000,
      resolveDanmakuTailPatchStartMs(
        firstIncomingTimeMs = 30_000,
        currentPositionMs = 5_000L,
      ),
    )
  }

  @Test
  fun tailPatchGuardMatchesTheActiveSpeedLifetime() {
    assertEquals(24_000L, rollingDurationMsForTailPatch(0.5f))
    assertEquals(16_000L, rollingDurationMsForTailPatch(0.75f))
    assertEquals(12_000L, rollingDurationMsForTailPatch(1f))
    assertEquals(6_000L, rollingDurationMsForTailPatch(4f))
    assertEquals(2_160L, rollingDurationMsForTailPatch(10f))
  }

  @Test
  fun stoppedLiteEngineOnlyAcceptsARequestedFullRestore() {
    assertFalse(canInjectPreparedDanmaku(true, false, true))
    assertFalse(canInjectPreparedDanmaku(true, true, false))
    assertTrue(canInjectPreparedDanmaku(true, true, true))
    assertTrue(canInjectPreparedDanmaku(false, false, false))
  }

  @Test
  fun publishedSnapshotSupportsTailAppendAndOutOfOrderMerge() {
    val tailAppended = mergePublishedDanmakuSnapshot(
      existing = listOf(dm(1, 100), dm(2, 200)),
      incoming = listOf(dm(3, 300), dm(4, 400))
    )
    val outOfOrderMerged = mergePublishedDanmakuSnapshot(
      existing = tailAppended,
      incoming = listOf(dm(5, 250))
    )

    assertEquals(listOf(1L, 2L, 3L, 4L), tailAppended.map { it.id })
    assertEquals(listOf(100, 200, 250, 300, 400), outOfOrderMerged.map { it.progress })
  }

  @Test
  fun danmakuAvailabilityUsesAStableMarkerInsteadOfTheTimeline() {
    val first = danmakuAvailabilityState(hasDanmaku = true)
    val second = danmakuAvailabilityState(hasDanmaku = true)

    assertSame(first, second)
    assertEquals(1, first.size)
    assertTrue(danmakuAvailabilityState(hasDanmaku = false).isEmpty())
  }

  @Test
  fun contiguousUpdateUsesIncrementalDelivery() {
    assertTrue(
      shouldAppendDanmakuUpdate(
        previousGeneration = 3L,
        previousSequence = 7L,
        currentGeneration = 3L,
        currentSequence = 8L,
        replace = false
      )
    )
  }

  @Test
  fun missedOrReplacementUpdateUsesSnapshotDelivery() {
    assertEquals(
      false,
      shouldAppendDanmakuUpdate(3L, 7L, 3L, 9L, replace = false)
    )
    assertEquals(
      false,
      shouldAppendDanmakuUpdate(3L, 7L, 3L, 8L, replace = true)
    )
    assertEquals(
      false,
      shouldAppendDanmakuUpdate(null, null, 3L, 8L, replace = false)
    )
  }

  @Test
  fun staleQueuedResetCannotRollBackANewerPublication() {
    assertEquals(
      false,
      shouldResetPublishedDanmakuState(
        queuedGeneration = 4L,
        currentGeneration = 5L,
        publishedGeneration = 5L
      )
    )
    assertTrue(
      shouldResetPublishedDanmakuState(
        queuedGeneration = 5L,
        currentGeneration = 5L,
        publishedGeneration = 4L
      )
    )
  }

  private fun dm(id: Long, progress: Int, content: String = "dm-$id") =
    DmModel(id = id, progress = progress, content = content)
}
