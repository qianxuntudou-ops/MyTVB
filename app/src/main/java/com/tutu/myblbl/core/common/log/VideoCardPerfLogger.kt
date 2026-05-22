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

    fun <T> measureInflate(source: String, block: () -> T): T {
        val startMs = SystemClock.elapsedRealtime()
        return block().also {
            val elapsed = SystemClock.elapsedRealtime() - startMs
            val stats = inflateStats.getOrPut(source) { Stats() }
            val count = stats.count.incrementAndGet()
            val total = stats.totalMs.addAndGet(elapsed)
            stats.maxMs.updateAndGet { old -> maxOf(old, elapsed) }
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
}
