package com.tutu.myblbl.core.common.log

import android.os.SystemClock
import android.view.ViewTreeObserver
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.atomic.AtomicBoolean

object PagePerfLogger {
    private const val TAG = "PagePerf"
    private const val DRAW_TIMEOUT_MS = 160L

    fun now(): Long = SystemClock.elapsedRealtime()

    fun mark(page: String, event: String, startMs: Long, extra: String = "") {
        val elapsed = if (startMs > 0L) SystemClock.elapsedRealtime() - startMs else 0L
        val suffix = if (extra.isBlank()) "" else " $extra"
        AppLog.i(TAG, "$page $event elapsed=${elapsed}ms$suffix")
    }

    fun markNow(page: String, event: String, extra: String = "") {
        val suffix = if (extra.isBlank()) "" else " $extra"
        AppLog.i(TAG, "$page $event$suffix")
    }

    fun logRecyclerPreDraw(
        recyclerView: RecyclerView,
        page: String,
        event: String,
        startMs: Long,
        itemCount: Int,
        extra: String = "",
        onLogged: (() -> Unit)? = null
    ) {
        val fired = AtomicBoolean(false)
        recyclerView.post {
            if (!recyclerView.isAttachedToWindow) {
                mark(page, event, startMs, buildExtra("fire=not_attached", itemCount, extra))
                return@post
            }
            val observer = recyclerView.viewTreeObserver
            if (!observer.isAlive) {
                mark(page, event, startMs, buildExtra("fire=no_observer", itemCount, extra))
                return@post
            }
            val listener = object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (fired.compareAndSet(false, true)) {
                        removePreDrawListener(recyclerView, observer, this)
                        mark(page, event, startMs, buildExtra("fire=pre_draw", itemCount, extra))
                        onLogged?.invoke()
                    }
                    return true
                }
            }
            observer.addOnPreDrawListener(listener)
            recyclerView.postDelayed({
                if (fired.compareAndSet(false, true)) {
                    removePreDrawListener(recyclerView, observer, listener)
                    mark(page, event, startMs, buildExtra("fire=timeout", itemCount, extra))
                    onLogged?.invoke()
                }
            }, DRAW_TIMEOUT_MS)
        }
    }

    private fun removePreDrawListener(
        recyclerView: RecyclerView,
        observer: ViewTreeObserver,
        listener: ViewTreeObserver.OnPreDrawListener
    ) {
        if (observer.isAlive) {
            observer.removeOnPreDrawListener(listener)
        } else {
            recyclerView.viewTreeObserver.removeOnPreDrawListener(listener)
        }
    }

    private fun buildExtra(prefix: String, itemCount: Int, extra: String): String {
        return buildString {
            append(prefix)
            append(" items=")
            append(itemCount)
            if (extra.isNotBlank()) {
                append(' ')
                append(extra)
            }
        }
    }
}
