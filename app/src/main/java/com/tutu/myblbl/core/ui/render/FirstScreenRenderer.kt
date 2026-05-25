package com.tutu.myblbl.core.ui.render

import android.os.SystemClock
import androidx.recyclerview.widget.RecyclerView
import com.tutu.myblbl.R
import com.tutu.myblbl.core.common.log.AppLog
import com.tutu.myblbl.core.common.log.PagePerfLogger
import kotlin.math.ceil

object FirstScreenRenderer {
    private const val TAG = "PagePerf"
    private const val APPEND_AFTER_FIRST_FRAME_DELAY_MS = 96L

    fun <T> render(
        recyclerView: RecyclerView,
        page: String,
        items: List<T>,
        startMs: Long,
        source: String,
        event: String = "first_cards_draw",
        spanCount: Int,
        setItems: (List<T>, onCommitted: () -> Unit) -> Unit,
        appendItems: ((List<T>) -> Unit)? = null,
        itemHeightPx: Int = estimateVideoCardHeight(recyclerView, spanCount),
        minRows: Int = 2,
        extraBufferRows: Int = 1,
        maxRows: Int = 4,
        onFirstBatchCommitted: ((Int) -> Unit)? = null,
        onFirstFrame: (() -> Unit)? = null,
        onAppendRest: ((Int) -> Unit)? = null
    ) {
        val renderToken = Any()
        recyclerView.setTag(R.id.tag_first_screen_render_token, renderToken)
        if (items.isEmpty()) {
            setItems(emptyList()) {}
            return
        }

        val batchStartMs = PagePerfLogger.now()
        val firstCount = firstBatchSize(
            recyclerView = recyclerView,
            totalCount = items.size,
            spanCount = spanCount,
            itemHeightPx = itemHeightPx,
            minRows = minRows,
            extraBufferRows = extraBufferRows,
            maxRows = maxRows
        )
        val canBatch = appendItems != null && firstCount in 1 until items.size
        val firstBatch = if (canBatch) items.take(firstCount) else items

        PagePerfLogger.mark(
            page,
            "first_screen_apply_start",
            batchStartMs,
            "first=${firstBatch.size} total=${items.size} source=$source"
        )
        setItems(firstBatch) {
            PagePerfLogger.mark(
                page,
                "first_screen_commit",
                batchStartMs,
                "first=${firstBatch.size} total=${items.size} source=$source"
            )
            onFirstBatchCommitted?.invoke(firstBatch.size)
            logFirstFrame(
                recyclerView = recyclerView,
                page = page,
                event = event,
                startMs = startMs,
                itemCount = firstBatch.size,
                source = source,
                onLogged = {
                    onFirstFrame?.invoke()
                    if (canBatch) {
                        scheduleAppendRest(
                            recyclerView = recyclerView,
                            page = page,
                            allItems = items,
                            firstCount = firstBatch.size,
                            renderToken = renderToken,
                            appendItems = appendItems,
                            onAppendRest = onAppendRest
                        )
                    }
                }
            )
        }
    }

    fun logFirstFrame(
        recyclerView: RecyclerView,
        page: String,
        startMs: Long,
        itemCount: Int,
        source: String,
        event: String = "first_cards_draw",
        onLogged: (() -> Unit)? = null
    ) {
        if (startMs <= 0L || itemCount <= 0) {
            onLogged?.invoke()
            return
        }
        PagePerfLogger.logRecyclerPreDraw(
            recyclerView = recyclerView,
            page = page,
            event = event,
            startMs = startMs,
            itemCount = itemCount,
            extra = "source=$source",
            onLogged = onLogged
        )
    }

    fun estimateVideoCardHeight(recyclerView: RecyclerView, spanCount: Int): Int {
        val metrics = recyclerView.resources.displayMetrics
        val availableWidth = (recyclerView.width.takeIf { it > 0 } ?: metrics.widthPixels)
            .coerceAtLeast(1)
        val columnWidth = availableWidth / spanCount.coerceAtLeast(1)
        val coverHeight = columnWidth * 9 / 16
        val verticalChrome =
            recyclerView.resources.getDimensionPixelSize(R.dimen.px20) +
                recyclerView.resources.getDimensionPixelSize(R.dimen.px10) +
                recyclerView.resources.getDimensionPixelSize(R.dimen.px31) * 2 +
                recyclerView.resources.getDimensionPixelSize(R.dimen.px5) +
                recyclerView.resources.getDimensionPixelSize(R.dimen.px25) +
                recyclerView.resources.getDimensionPixelSize(R.dimen.px15)
        return (coverHeight + verticalChrome).coerceAtLeast(1)
    }

    private fun <T> scheduleAppendRest(
        recyclerView: RecyclerView,
        page: String,
        allItems: List<T>,
        firstCount: Int,
        renderToken: Any,
        appendItems: ((List<T>) -> Unit)?,
        onAppendRest: ((Int) -> Unit)?
    ) {
        val remaining = allItems.drop(firstCount)
        if (remaining.isEmpty()) return
        recyclerView.postDelayed({
            if (recyclerView.getTag(R.id.tag_first_screen_render_token) !== renderToken) {
                PagePerfLogger.markNow(
                    page,
                    "first_screen_append_skip_stale",
                    "items=${allItems.size} appended=${remaining.size}"
                )
                return@postDelayed
            }
            val appendStartMs = PagePerfLogger.now()
            appendItems?.invoke(remaining)
            PagePerfLogger.mark(
                page,
                "first_screen_append_rest",
                appendStartMs,
                "items=${allItems.size} appended=${remaining.size}"
            )
            onAppendRest?.invoke(remaining.size)
        }, APPEND_AFTER_FIRST_FRAME_DELAY_MS)
    }

    private fun firstBatchSize(
        recyclerView: RecyclerView,
        totalCount: Int,
        spanCount: Int,
        itemHeightPx: Int,
        minRows: Int,
        extraBufferRows: Int,
        maxRows: Int
    ): Int {
        val viewportHeight = recyclerView.height
            .takeIf { it > 0 }
            ?: (recyclerView.resources.displayMetrics.heightPixels * 0.72f).toInt()
        val visibleRows = ceil(viewportHeight / itemHeightPx.toFloat()).toInt().coerceAtLeast(1)
        val rows = (visibleRows + extraBufferRows)
            .coerceAtLeast(minRows)
            .coerceAtMost(maxRows.coerceAtLeast(minRows))
        val count = rows * spanCount.coerceAtLeast(1)
        val bounded = count.coerceIn(1, totalCount)
        AppLog.i(
            TAG,
            "first_screen_batch viewport=${viewportHeight}px item=${itemHeightPx}px span=$spanCount rows=$rows first=$bounded total=$totalCount"
        )
        return bounded
    }
}
