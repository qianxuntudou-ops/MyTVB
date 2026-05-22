package com.tutu.myblbl.core.ui.base

import androidx.recyclerview.widget.RecyclerView
import com.tutu.myblbl.core.common.log.AppLog

object RecyclerViewPoolPrewarmer {
    private const val TAG = "PagePerf"

    fun prewarm(
        recyclerView: RecyclerView,
        adapter: RecyclerView.Adapter<*>,
        count: Int,
        source: String,
        pool: RecyclerView.RecycledViewPool = recyclerView.recycledViewPool,
        maxPoolSize: Int = maxOf(count * 2, 12)
    ) {
        @Suppress("UNUSED_VARIABLE")
        val ignored = adapter to pool to maxPoolSize
        AppLog.i(TAG, "$source holder_prewarm disabled count=$count")
    }
}
