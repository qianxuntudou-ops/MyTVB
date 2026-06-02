package com.tutu.myblbl.core.common.log

import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

object VideoCardPerfLogger {

    private const val TAG = "VideoCardPerf"
    private data class Stats(
        val count: AtomicInteger = AtomicInteger(0),
        val totalMs: AtomicLong = AtomicLong(0),
        val maxMs: AtomicLong = AtomicLong(0)
    )

    private val inflateStats = ConcurrentHashMap<String, Stats>()
    private val bindStats = ConcurrentHashMap<String, Stats>()
    private val phaseStats = ConcurrentHashMap<String, Stats>()

    private fun AtomicLong.updateMax(value: Long) {
        var old = get()
        while (value > old && !compareAndSet(old, value)) {
            old = get()
        }
    }

    fun <T> measureInflate(source: String, block: () -> T): T {
        val startMs = SystemClock.elapsedRealtime()
        return block().also {
            val elapsed = SystemClock.elapsedRealtime() - startMs
            val stats = inflateStats.getOrPut(source) { Stats() }
            val count = stats.count.incrementAndGet()
            val total = stats.totalMs.addAndGet(elapsed)
            stats.maxMs.updateMax(elapsed)
            if (count <= 12 || count % 20 == 0 || elapsed >= 8) {
                AppLog.i(TAG, "cell_video inflate source=$source count=$count elapsed=${elapsed}ms")
            }
            if (count == 4 || count == 12 || count % 20 == 0) {
                AppLog.i(
                    TAG,
                    "cell_video summary source=$source count=$count total=${total}ms avg=${total / count}ms max=${stats.maxMs.get()}ms"
                )
            }
        }
    }

    fun measureBind(source: String, block: () -> Unit) {
        val startMs = SystemClock.elapsedRealtime()
        block()
        val elapsed = SystemClock.elapsedRealtime() - startMs
        val stats = bindStats.getOrPut(source) { Stats() }
        val count = stats.count.incrementAndGet()
        val total = stats.totalMs.addAndGet(elapsed)
        stats.maxMs.updateMax(elapsed)
        if (count <= 12 || count % 20 == 0 || elapsed >= 4) {
            AppLog.i(TAG, "cell_video bind source=$source count=$count elapsed=${elapsed}ms")
        }
        if (count == 4 || count == 12 || count % 20 == 0) {
            AppLog.i(
                TAG,
                "cell_video bind_summary source=$source count=$count total=${total}ms avg=${total / count}ms max=${stats.maxMs.get()}ms"
            )
        }
    }

    fun recordPhase(source: String, phase: String, elapsedNs: Long) {
        val elapsedUs = (elapsedNs / 1_000L).coerceAtLeast(0L)
        val stats = phaseStats.getOrPut("$source/$phase") { Stats() }
        val count = stats.count.incrementAndGet()
        val total = stats.totalMs.addAndGet(elapsedUs)
        stats.maxMs.updateMax(elapsedUs)
        if (count == 4 || count == 12 || count % 20 == 0 || elapsedUs >= 8_000L) {
            AppLog.i(
                TAG,
                "cell_video phase source=$source phase=$phase count=$count elapsedUs=${elapsedUs} totalUs=${total} avgUs=${total / count} maxUs=${stats.maxMs.get()}"
            )
        }
    }
}
