package com.tutu.myblbl.core.ui.focus.tv

import android.view.KeyEvent
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tutu.myblbl.core.common.log.AppLog

class TvListFocusController(
    private val recyclerView: RecyclerView,
    private val adapter: TvFocusableAdapter,
    private val strategy: TvFocusStrategy,
    private val canLoadMore: () -> Boolean,
    private val loadMore: () -> Unit
) {
    companion object {
        private const val TAG = "TvListFocus"
    }

    private val operator = RecyclerViewFocusOperator(recyclerView, adapter)
    private var currentAnchor: TvFocusAnchor? = null
    private var capturedAnchor: TvFocusAnchor? = null
    private var pendingMoveAfterLoadMore: TvFocusAnchor? = null
    private var refreshFocusTarget: Int? = null

    fun onItemFocused(view: View, position: Int) {
        if (!adapter.isFocusablePosition(position)) {
            AppLog.w(TAG, "onItemFocused: pos=$position NOT focusable, itemCount=${adapter.focusableItemCount()}")
            return
        }
        val target = refreshFocusTarget
        if (target != null && position != target) {
            return
        }
        if (target != null && position == target) {
            val capturedTarget = target
            recyclerView.postDelayed({
                if (refreshFocusTarget != capturedTarget) return@postDelayed
                refreshFocusTarget = null
                restoreAllFocus()
            }, 200)
        }
        currentAnchor = createAnchor(view, position, TvFocusAnchor.Source.FOCUS)
        val anchor = currentAnchor
        AppLog.d(TAG, "onItemFocused: pos=$position row=${anchor?.row} col=${anchor?.column} key=${anchor?.stableKey}")
    }

    fun handleKey(view: View, keyCode: Int, event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) {
            return false
        }
        val direction = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_RIGHT
            else -> return false
        }
        val dirName = directionName(direction)
        // User pressed a key — cancel refresh suppression so navigation works normally
        if (refreshFocusTarget != null) {
            refreshFocusTarget = null
            restoreAllFocus()
        }
        if (direction != View.FOCUS_DOWN) {
            pendingMoveAfterLoadMore = null
        }
        val position = resolveAdapterPosition(view)
        if (position == RecyclerView.NO_POSITION) {
            val itemView = recyclerView.findContainingItemView(view)
            if (itemView == null) {
                AppLog.w(TAG, "handleKey: $dirName view not in this RV, releasing anchor and returning false")
                currentAnchor = null
                return false
            }
        }
        if (position != RecyclerView.NO_POSITION && adapter.isFocusablePosition(position)) {
            currentAnchor = createAnchor(view, position, TvFocusAnchor.Source.FOCUS)
        }
        AppLog.d(TAG, "handleKey: dir=$dirName pos=$position anchor=${currentAnchor?.adapterPosition}(${currentAnchor?.row},${currentAnchor?.column}) itemCount=${adapter.focusableItemCount()}")
        return move(direction)
    }

    fun onDataChanged(reason: TvDataChangeReason = TvDataChangeReason.REPLACE_PRESERVE_ANCHOR) {
        AppLog.d(TAG, "onDataChanged: reason=$reason itemCount=${adapter.focusableItemCount()} hasValidFocused=${hasValidFocusedItem()} currentAnchor=${currentAnchor?.adapterPosition} capturedAnchor=${capturedAnchor?.adapterPosition}")
        if (adapter.focusableItemCount() <= 0) {
            currentAnchor = null
            capturedAnchor = null
            pendingMoveAfterLoadMore = null
            operator.cancelPendingFocus()
            return
        }

        if (reason == TvDataChangeReason.USER_REFRESH) {
            clearAnchorForUserRefresh()
            return
        }

        if (reason == TvDataChangeReason.APPEND) {
            pendingMoveAfterLoadMore = null
            // Don't auto-move focus to new items (preserves existing design).
            // But if focus was lost during a fast-scroll + loadMore cycle, recover it.
            ensureValidFocus("appendFocusRecovery")
            return
        }

        if (hasValidFocusedItem()) {
            return
        }

        // Don't steal focus if something outside the RecyclerView currently has focus
        val focused = recyclerView.rootView?.findFocus()
        if (focused != null && !isDescendantOf(focused, recyclerView)) {
            return
        }

        val anchor = currentAnchor ?: capturedAnchor
        if (anchor != null) {
            val resolved = resolveAnchorPosition(anchor)
            if (resolved != RecyclerView.NO_POSITION) {
                focusPosition(resolved, anchor.offsetTop, reason.name)
            }
        }
    }

    fun ensureValidFocus(reason: String, allowWhenFocusOutside: Boolean = false): Boolean {
        if (hasValidFocusedItem()) {
            return true
        }
        val focused = recyclerView.rootView?.findFocus()
        val focusInsideList = focused != null && isDescendantOf(focused, recyclerView)
        val focusDetachedOrHidden = focused != null && (!focused.isAttachedToWindow || !focused.isShown)
        if (focused != null && !focusInsideList && !focusDetachedOrHidden && !allowWhenFocusOutside) {
            AppLog.d(TAG, "ensureValidFocus: skip reason=$reason outsideFocus=${focused.javaClass.simpleName}")
            return false
        }

        val anchor = currentAnchor ?: capturedAnchor
        if (anchor != null) {
            val resolved = resolveAnchorPosition(anchor)
            if (resolved != RecyclerView.NO_POSITION) {
                AppLog.d(TAG, "ensureValidFocus: restore anchor pos=$resolved reason=$reason")
                return focusPosition(
                    resolved,
                    anchor.offsetTop,
                    reason,
                    allowOutsideFocus = allowWhenFocusOutside || focusDetachedOrHidden
                )
            }
        }

        val firstVisible = firstVisibleFocusablePosition()
        if (firstVisible != RecyclerView.NO_POSITION) {
            AppLog.d(TAG, "ensureValidFocus: restore firstVisible pos=$firstVisible reason=$reason")
            return focusPosition(
                firstVisible,
                0,
                reason,
                allowOutsideFocus = allowWhenFocusOutside || focusDetachedOrHidden
            )
        }
        AppLog.w(TAG, "ensureValidFocus: no focus target reason=$reason itemCount=${adapter.focusableItemCount()}")
        return false
    }

    private fun hasValidFocusedItem(): Boolean {
        val focused = recyclerView.rootView?.findFocus() ?: return false
        val position = resolveAdapterPosition(focused)
        if (position == RecyclerView.NO_POSITION || !adapter.isFocusablePosition(position)) {
            return false
        }
        val itemView = recyclerView.findContainingItemView(focused) ?: focused
        return itemView.isAttachedToWindow && itemView.visibility == View.VISIBLE
    }

    fun focusPrimary(): Boolean {
        val itemCount = adapter.focusableItemCount()
        if (itemCount <= 0) {
            return false
        }
        val anchor = currentAnchor ?: capturedAnchor
        if (anchor != null) {
            val resolved = resolveAnchorPosition(anchor)
            if (resolved != RecyclerView.NO_POSITION) {
                return focusPosition(resolved, anchor.offsetTop, "primaryAnchor", allowOutsideFocus = true)
            }
        }
        val firstVisible = firstVisibleFocusablePosition()
        val target = if (firstVisible != RecyclerView.NO_POSITION) firstVisible else 0
        return focusPosition(target, 0, "primary", allowOutsideFocus = true)
    }

    /**
     * Requests focus at [position] for a user-initiated refresh.
     * Suppresses focus on all other items so the framework cannot steal focus
     * during subsequent layout passes. Focusability is restored after layout settles.
     */
    fun requestRefreshFocus(position: Int): Boolean {
        if (!adapter.isFocusablePosition(position)) {
            return false
        }
        refreshFocusTarget = position
        suppressOtherFocus(position)
        return focusPosition(position, 0, "refresh", allowOutsideFocus = true)
    }

    fun requestFocusPosition(position: Int): Boolean {
        if (!adapter.isFocusablePosition(position)) {
            return false
        }
        val anchor = strategy.anchorFor(
            position = position,
            stableKey = adapter.stableKeyAt(position),
            offsetTop = 0
        )
        currentAnchor = anchor
        return focusPosition(position, anchor.offsetTop, "request")
    }

    fun captureCurrentAnchor() {
        val focused = recyclerView.rootView?.findFocus()
        val position = focused?.let(::resolveAdapterPosition) ?: RecyclerView.NO_POSITION
        capturedAnchor = if (focused != null && position != RecyclerView.NO_POSITION && adapter.isFocusablePosition(position)) {
            createAnchor(focused, position, TvFocusAnchor.Source.RETURN_RESTORE)
        } else {
            anchorFromVisibleOrCurrent()
        }
    }

    fun restoreCapturedAnchor(): Boolean {
        val anchor = capturedAnchor ?: currentAnchor ?: return false
        val position = resolveAnchorPosition(anchor)
        if (position == RecyclerView.NO_POSITION) {
            return false
        }
        return focusPosition(position, anchor.offsetTop, "returnRestore")
    }

    fun clearAnchorForUserRefresh() {
        currentAnchor = null
        capturedAnchor = null
        pendingMoveAfterLoadMore = null
        refreshFocusTarget = null
        restoreAllFocus()
        operator.cancelPendingFocus()
    }

    fun release() {
        refreshFocusTarget = null
        restoreAllFocus()
        clearAnchorForUserRefresh()
    }

    private fun suppressOtherFocus(targetPosition: Int) {
        for (i in 0 until recyclerView.childCount) {
            val child = recyclerView.getChildAt(i)
            val pos = recyclerView.getChildAdapterPosition(child)
            if (pos != RecyclerView.NO_POSITION && pos != targetPosition) {
                child.isFocusable = false
            }
        }
    }

    private fun restoreAllFocus() {
        for (i in 0 until recyclerView.childCount) {
            val child = recyclerView.getChildAt(i)
            val pos = recyclerView.getChildAdapterPosition(child)
            if (pos != RecyclerView.NO_POSITION && adapter.isFocusablePosition(pos)) {
                child.isFocusable = true
            }
        }
    }

    private fun move(direction: Int): Boolean {
        val dirName = directionName(direction)
        val itemCount = adapter.focusableItemCount()
        if (itemCount <= 0) {
            AppLog.w(TAG, "move: $dirName BLOCKED — itemCount=0")
            return false
        }
        val anchor = currentAnchor ?: anchorFromFocusedOrVisible()
        if (anchor == null) {
            AppLog.w(TAG, "move: $dirName BLOCKED — no anchor, currentFocus=${recyclerView.rootView?.findFocus()?.let { resolveAdapterPosition(it) }}")
            return false
        }
        val target = strategy.nextPosition(anchor, direction, itemCount)
        AppLog.d(TAG, "move: $dirName anchor=${anchor.adapterPosition}(${anchor.row},${anchor.column}) → target=$target itemCount=$itemCount")
        if (target != null) {
            return focusPosition(target, anchor.offsetTop, "move")
        }
        if (direction == View.FOCUS_DOWN && canLoadMore() && pendingMoveAfterLoadMore == null) {
            AppLog.d(TAG, "move: DOWN at bottom, triggering loadMore")
            pendingMoveAfterLoadMore = anchor.copy(source = TvFocusAnchor.Source.PENDING_LOAD_MORE)
            loadMore()
            return true
        }
        if (direction == View.FOCUS_DOWN && pendingMoveAfterLoadMore != null) {
            AppLog.d(TAG, "move: DOWN pending loadMore, consuming key")
            return true
        }
        if (direction == View.FOCUS_DOWN) {
            AppLog.d(TAG, "move: DOWN at edge with no more data, consuming key")
            return true
        }
        AppLog.d(TAG, "move: $dirName at edge, returning false (not handled)")
        return false
    }

    private fun focusPosition(
        position: Int,
        offsetTop: Int,
        reason: String,
        allowOutsideFocus: Boolean = false
    ): Boolean {
        val focused = recyclerView.rootView?.findFocus()
        if (focused != null && !isDescendantOf(focused, recyclerView) && reason != "move" && reason != "primary" && !allowOutsideFocus) {
            AppLog.d(TAG, "focusPosition: BLOCKED reason=$reason — focus is outside RV on ${focused.javaClass.simpleName}")
            return false
        }
        AppLog.d(TAG, "focusPosition: pos=$position offset=$offsetTop reason=$reason")
        return operator.focusPosition(position, offsetTop, reason) { focusedPosition ->
            currentAnchor = strategy.anchorFor(
                position = focusedPosition,
                stableKey = adapter.stableKeyAt(focusedPosition),
                offsetTop = offsetTop
            )
            AppLog.d(TAG, "focusPosition OK: focused=$focusedPosition row=${currentAnchor?.row} col=${currentAnchor?.column}")
        }
    }

    private fun anchorFromFocusedOrVisible(): TvFocusAnchor? {
        val focused = recyclerView.rootView?.findFocus()
        val focusedPosition = focused?.let(::resolveAdapterPosition) ?: RecyclerView.NO_POSITION
        if (focused != null && focusedPosition != RecyclerView.NO_POSITION && adapter.isFocusablePosition(focusedPosition)) {
            return createAnchor(focused, focusedPosition, TvFocusAnchor.Source.FOCUS)
        }
        val visiblePosition = firstVisibleFocusablePosition()
        if (visiblePosition == RecyclerView.NO_POSITION) {
            return null
        }
        val visibleView = recyclerView.findViewHolderForAdapterPosition(visiblePosition)?.itemView
        val offset = visibleView?.let { it.top - recyclerView.paddingTop } ?: 0
        return strategy.anchorFor(
            position = visiblePosition,
            stableKey = adapter.stableKeyAt(visiblePosition),
            offsetTop = offset,
            source = TvFocusAnchor.Source.VISIBLE_ITEM
        )
    }

    private fun createAnchor(view: View, position: Int, source: TvFocusAnchor.Source): TvFocusAnchor {
        val itemView = recyclerView.findContainingItemView(view) ?: view
        val offsetTop = itemView.top - recyclerView.paddingTop
        return strategy.anchorFor(
            position = position,
            stableKey = adapter.stableKeyAt(position),
            offsetTop = offsetTop,
            source = source
        )
    }

    /**
     * Called when the user is touch-dragging the list.
     * Updates both [currentAnchor] and [capturedAnchor] to the current viewport position
     * so that subsequent restore operations (onResume, onHiddenChanged, focusPrimary)
     * return to where the user was actually looking, not to a stale focused position.
     */
    fun onUserTouchScroll() {
        val visiblePos = firstVisibleFocusablePosition()
        if (visiblePos == RecyclerView.NO_POSITION) return
        val visibleView = recyclerView.findViewHolderForAdapterPosition(visiblePos)?.itemView
        val offset = visibleView?.let { it.top - recyclerView.paddingTop } ?: 0
        val anchor = strategy.anchorFor(
            position = visiblePos,
            stableKey = adapter.stableKeyAt(visiblePos),
            offsetTop = offset,
            source = TvFocusAnchor.Source.VISIBLE_ITEM
        )
        currentAnchor = anchor
        capturedAnchor = anchor
    }

    private fun anchorFromVisibleOrCurrent(): TvFocusAnchor? {
        val visiblePos = firstVisibleFocusablePosition()
        if (visiblePos != RecyclerView.NO_POSITION) {
            val visibleView = recyclerView.findViewHolderForAdapterPosition(visiblePos)?.itemView
            val offset = visibleView?.let { it.top - recyclerView.paddingTop } ?: 0
            return strategy.anchorFor(
                position = visiblePos,
                stableKey = adapter.stableKeyAt(visiblePos),
                offsetTop = offset,
                source = TvFocusAnchor.Source.RETURN_RESTORE
            )
        }
        return currentAnchor
    }

    private fun resolveAnchorPosition(anchor: TvFocusAnchor): Int {
        val byKey = anchor.stableKey
            ?.let(adapter::findPositionByStableKey)
            ?.takeIf { it != RecyclerView.NO_POSITION && adapter.isFocusablePosition(it) }
        if (byKey != null) {
            return byKey
        }
        return anchor.adapterPosition
            .coerceIn(0, adapter.focusableItemCount() - 1)
            .takeIf(adapter::isFocusablePosition)
            ?: RecyclerView.NO_POSITION
    }

    private fun firstVisibleFocusablePosition(): Int {
        val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return RecyclerView.NO_POSITION
        val first = layoutManager.findFirstVisibleItemPosition()
        val last = layoutManager.findLastVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION || last == RecyclerView.NO_POSITION) {
            return RecyclerView.NO_POSITION
        }
        val max = adapter.focusableItemCount() - 1
        for (position in first.coerceAtLeast(0)..last.coerceAtMost(max)) {
            if (adapter.isFocusablePosition(position)) {
                return position
            }
        }
        return RecyclerView.NO_POSITION
    }

    private fun resolveAdapterPosition(view: View): Int {
        val itemView = recyclerView.findContainingItemView(view) ?: view
        val holder = recyclerView.findContainingViewHolder(itemView) ?: return RecyclerView.NO_POSITION
        return holder.absoluteAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }
            ?: holder.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }
            ?: holder.layoutPosition.takeIf { it != RecyclerView.NO_POSITION }
            ?: recyclerView.getChildAdapterPosition(itemView)
    }

    private fun isDescendantOf(view: View, ancestor: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current === ancestor) return true
            current = current.parent as? View
        }
        return false
    }

    private fun directionName(direction: Int): String = when (direction) {
        View.FOCUS_UP -> "UP"
        View.FOCUS_DOWN -> "DOWN"
        View.FOCUS_LEFT -> "LEFT"
        View.FOCUS_RIGHT -> "RIGHT"
        else -> "UNKNOWN($direction)"
    }

    /**
     * Returns true if [anchor]'s adapter position is within one screen's worth of the current
     * visible range. Used to guard APPEND focus-restore from scrolling the list back up when
     * the user has flung far past the anchor position.
     */
    private fun isAnchorNearViewport(anchor: TvFocusAnchor): Boolean {
        val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return true
        val first = lm.findFirstVisibleItemPosition()
        val last = lm.findLastVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION || last == RecyclerView.NO_POSITION) return true
        val screenSize = (last - first + 1).coerceAtLeast(1)
        val pos = anchor.adapterPosition
        return pos >= first - screenSize && pos <= last + screenSize
    }
}
