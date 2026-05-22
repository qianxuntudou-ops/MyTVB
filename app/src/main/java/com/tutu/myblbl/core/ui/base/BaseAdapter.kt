package com.tutu.myblbl.core.ui.base

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.tutu.myblbl.R
import com.tutu.myblbl.core.ui.focus.tv.TvFocusableAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

abstract class BaseAdapter<MODEL, VH : RecyclerView.ViewHolder> : RecyclerView.Adapter<VH>(), TvFocusableAdapter {

    internal val items = ArrayList<MODEL>()
    internal var showLoadMore = true

    private val adapterScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pendingSetDataJob: Job? = null
    private val defaultContentViewType = this::class.java.name.hashCode()
        .let { if (it == LOAD_MORE_TYPE) CONTENT_VIEW_TYPE_FALLBACK else it }

    protected open fun areItemsSame(old: MODEL, new: MODEL): Boolean = old == new
    protected open fun areContentsSame(old: MODEL, new: MODEL): Boolean = old == new
    protected open fun getFocusStableKey(item: MODEL): String? = null
    protected open fun getContentItemViewType(position: Int): Int = defaultContentViewType

    /**
     * 子类保留这个入口给显式预取场景使用；BaseAdapter 不再自动预取。
     * 首屏按参考项目路径走可见项 onBind 加载，避免后台预取和可见封面抢网络/解码。
     */
    protected open fun coverUrlOf(item: MODEL): String? = null

    fun contentCount(): Int = items.size

    override fun focusableItemCount(): Int = contentCount()

    override fun stableKeyAt(position: Int): String? {
        return items.getOrNull(position)?.let(::getFocusStableKey)
    }

    override fun findPositionByStableKey(key: String): Int {
        return items.indexOfFirst { getFocusStableKey(it) == key }
            .takeIf { it >= 0 }
            ?: RecyclerView.NO_POSITION
    }

    fun setShowLoadMore(show: Boolean) {
        if (showLoadMore == show) {
            return
        }
        showLoadMore = show
        // When items is empty, getItemCount() returns 0 regardless of showLoadMore
        // (see getItemCount: "items.isNotEmpty()"). Emitting insert/remove against an
        // empty list would cause RecyclerView to disagree with getItemCount() → crash.
        if (items.isEmpty()) return
        if (show) {
            notifyItemInserted(items.size)
        } else {
            notifyItemRemoved(items.size)
        }
    }

    fun addAll(list: List<MODEL>) {
        pendingSetDataJob?.cancel()
        pendingSetDataJob = null
        val wasEmpty = items.isEmpty()
        val size = items.size
        items.addAll(list)
        if (list.size == 1) {
            notifyItemInserted(size)
        } else {
            notifyItemRangeInserted(size, list.size)
        }
        if (showLoadMore && wasEmpty && items.isNotEmpty()) {
            notifyItemInserted(items.size)
        }
    }

    fun getItem(position: Int): MODEL? {
        return if (position in items.indices) items[position] else null
    }

    fun getItemsSnapshot(): List<MODEL> = items.toList()

    abstract fun onCreateContentViewHolder(parent: ViewGroup, viewType: Int): VH

    open fun setData(data: List<MODEL>, onComplete: (() -> Unit)? = null) {
        pendingSetDataJob?.cancel()
        val oldItems = items.toList()
        if (oldItems.isEmpty()) {
            items.clear()
            items.addAll(data)
            if (data.isNotEmpty()) {
                notifyItemRangeInserted(0, if (showLoadMore) data.size + 1 else data.size)
            }
            onComplete?.invoke()
            return
        }
        pendingSetDataJob = adapterScope.launch {
            val diffResult = withContext(Dispatchers.Default) {
                DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                    override fun getOldListSize() = oldItems.size
                    override fun getNewListSize() = data.size
                    override fun areItemsTheSame(oldPos: Int, newPos: Int) =
                        areItemsSame(oldItems[oldPos], data[newPos])
                    override fun areContentsTheSame(oldPos: Int, newPos: Int) =
                        areContentsSame(oldItems[oldPos], data[newPos])
                })
            }
            if (!isActive) return@launch
            val oldEmpty = oldItems.isEmpty()
            items.clear()
            items.addAll(data)
            diffResult.dispatchUpdatesTo(this@BaseAdapter)
            if (showLoadMore) {
                if (oldEmpty && items.isNotEmpty()) {
                    notifyItemInserted(items.size)
                } else if (!oldEmpty && items.isEmpty()) {
                    notifyItemRemoved(0)
                }
            }
            onComplete?.invoke()
        }
    }

    /**
     * 在后台线程计算 diff 并在主线程 dispatch 更新，完成后执行 [onComplete]。
     */
    internal fun submitItemsInBackground(
        newItems: List<MODEL>,
        areItemsTheSame: (old: MODEL, new: MODEL) -> Boolean,
        areContentsTheSame: (old: MODEL, new: MODEL) -> Boolean,
        onComplete: (() -> Unit)? = null
    ) {
        pendingSetDataJob?.cancel()
        val oldItems = items.toList()
        if (oldItems.isEmpty()) {
            items.clear()
            items.addAll(newItems)
            if (newItems.isNotEmpty()) {
                notifyItemRangeInserted(0, if (showLoadMore) newItems.size + 1 else newItems.size)
            }
            onComplete?.invoke()
            return
        }
        pendingSetDataJob = adapterScope.launch {
            val diffResult = withContext(Dispatchers.Default) {
                DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                    override fun getOldListSize() = oldItems.size
                    override fun getNewListSize() = newItems.size
                    override fun areItemsTheSame(oldPos: Int, newPos: Int) =
                        areItemsTheSame(oldItems[oldPos], newItems[newPos])
                    override fun areContentsTheSame(oldPos: Int, newPos: Int) =
                        areContentsTheSame(oldItems[oldPos], newItems[newPos])
                })
            }
            if (!isActive) return@launch
            val oldEmpty = oldItems.isEmpty()
            items.clear()
            items.addAll(newItems)
            diffResult.dispatchUpdatesTo(this@BaseAdapter)
            if (showLoadMore) {
                if (oldEmpty && items.isNotEmpty()) {
                    notifyItemInserted(items.size)
                } else if (!oldEmpty && items.isEmpty()) {
                    notifyItemRemoved(0)
                }
            }
            onComplete?.invoke()
        }
    }

    fun clear() {
        items.clear()
    }

    override fun getItemCount(): Int {
        return if (showLoadMore && items.isNotEmpty()) items.size + 1 else items.size
    }

    override fun getItemViewType(position: Int): Int {
        return if (position == items.size && showLoadMore && items.isNotEmpty()) {
            LOAD_MORE_TYPE
        } else {
            getContentItemViewType(position)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        if (viewType == LOAD_MORE_TYPE) {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.view_load_more, parent, false)
            @Suppress("UNCHECKED_CAST")
            return LoadMoreViewHolder(view) as VH
        }
        return onCreateContentViewHolder(parent, viewType)
    }

    final override fun onBindViewHolder(holder: VH, position: Int) {
        if (position == items.size && showLoadMore && items.isNotEmpty()) {
            return
        }
        onBindContentViewHolder(holder, position)
    }

    abstract fun onBindContentViewHolder(holder: VH, position: Int)

    class LoadMoreViewHolder(view: View) : RecyclerView.ViewHolder(view)

    companion object {
        const val LOAD_MORE_TYPE = -1000
        private const val CONTENT_VIEW_TYPE_FALLBACK = -1001
    }
}
