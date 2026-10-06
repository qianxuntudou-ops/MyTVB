package com.mytvb.core.ui.base

import android.view.KeyEvent
import android.view.View
import androidx.recyclerview.widget.RecyclerView

abstract class BaseVideoAdapter<T : Any, VH : RecyclerView.ViewHolder> : BaseAdapter<T, VH>() {

    protected var focusedPosition = RecyclerView.NO_POSITION

    protected var onItemFocused: ((Int) -> Unit)? = null
    protected var onItemFocusedWithView: ((View, Int) -> Unit)? = null
    protected var onTopEdgeUp: (() -> Boolean)? = null
    protected var onBottomEdgeDown: (() -> Boolean)? = null
    protected var onLeftEdge: (() -> Boolean)? = null
    protected var onRightEdge: (() -> Boolean)? = null
    protected var onItemDpad: ((View, Int, KeyEvent) -> Boolean)? = null
    protected var onItemsChanged: (() -> Unit)? = null

    protected abstract fun itemKey(item: T): String

    override fun getFocusStableKey(item: T): String = itemKey(item)

    override fun areItemsSame(old: T, new: T): Boolean = itemKey(old) == itemKey(new)

    override fun getItemId(position: Int): Long {
        return if (position < items.size) itemKey(items[position]).hashCode().toLong()
        else super.getItemId(position)
    }

    fun addData(newItems: List<T>) {
        val existingKeys = items.mapTo(HashSet(items.size)) { itemKey(it) }
        val deduplicated = deduplicate(newItems).filterNot { existingKeys.contains(itemKey(it)) }
        if (deduplicated.isEmpty()) return
        addAll(deduplicated)
    }

    fun setDataDeduplicated(list: List<T>, onCommitted: (() -> Unit)? = null) {
        val deduplicated = deduplicate(list)
        focusedPosition = RecyclerView.NO_POSITION
        setDataDirect(deduplicated, onCommitted)
    }

    protected fun setDataDirect(newItems: List<T>, onCommitted: (() -> Unit)? = null) {
        submitItemsInBackground(
            newItems = newItems,
            areItemsTheSame = { old, new -> itemKey(old) == itemKey(new) },
            areContentsTheSame = { old, new -> areContentsSame(old, new) },
            onComplete = { onItemsChanged?.invoke(); onCommitted?.invoke() }
        )
    }

    protected fun removeItems(predicate: (T) -> Boolean) {
        val filtered = items.filterNot(predicate)
        if (filtered.size == items.size) return
        submitItemsInBackground(
            newItems = filtered,
            areItemsTheSame = { old, new -> itemKey(old) == itemKey(new) },
            areContentsTheSame = { old, new -> areContentsSame(old, new) },
            onComplete = { onItemsChanged?.invoke() }
        )
    }

    fun clearFocusMemory() {
        val previous = focusedPosition
        focusedPosition = RecyclerView.NO_POSITION
        if (previous != RecyclerView.NO_POSITION) {
            notifyItemChanged(previous)
        }
    }

    fun removeByVideoId(aid: Long, bvid: String) {
        removeItems { itemKey(it) == buildVideoKey(aid, bvid) }
    }

    private fun buildVideoKey(aid: Long, bvid: String): String {
        return if (bvid.isNotBlank()) "bvid:$bvid" else if (aid > 0) "aid:$aid" else ""
    }

    protected fun deduplicate(source: List<T>): List<T> {
        if (source.isEmpty()) return emptyList()
        val seenKeys = LinkedHashSet<String>(source.size)
        return source.filter { seenKeys.add(itemKey(it)) }
    }
}
