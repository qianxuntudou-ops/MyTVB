package com.tutu.myblbl.core.ui.focus.tv

import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tutu.myblbl.core.common.log.AppLog

class TvListFocusController(
    private val recyclerView: RecyclerView,
    private val adapter: TvFocusableAdapter,
    private val strategy: TvFocusStrategy,
    private val canLoadMore: () -> Boolean,
    private val loadMore: () -> Unit,
    private val restoreAppendFocusFromOutside: Boolean = false,
    private val restoreFocusOnFocusedDetach: Boolean = false,
    private val debugName: String = "list"
) {
    companion object {
        private const val TAG = "TvListFocus"
    }

    private val operator = RecyclerViewFocusOperator(recyclerView, adapter)
    private var currentAnchor: TvFocusAnchor? = null
    private var capturedAnchor: TvFocusAnchor? = null
    private var pendingMoveAfterLoadMore: TvFocusAnchor? = null
    private var refreshFocusTarget: Int? = null
    private var userNavigationToken = 0
    private var restoreOutsideFocusUntilMs = 0L
    private var parkedDescendantFocusability: Int? = null
    private var parkedRecyclerFocusable: Boolean? = null
    private var parkedDefaultFocusHighlightEnabled: Boolean? = null
    private val globalFocusListener = ViewTreeObserver.OnGlobalFocusChangeListener { oldFocus, newFocus ->
        if (!restoreFocusOnFocusedDetach) {
            return@OnGlobalFocusChangeListener
        }
        if (SystemClock.uptimeMillis() > restoreOutsideFocusUntilMs) {
            return@OnGlobalFocusChangeListener
        }
        val oldFocusInsideList = oldFocus != null && isDescendantOf(oldFocus, recyclerView)
        val newFocusInsideList = newFocus != null && isDescendantOf(newFocus, recyclerView)
        logD(
            "globalFocusDuringMove: old=${describeView(oldFocus)} oldInside=$oldFocusInsideList " +
                "new=${describeView(newFocus)} newInside=$newFocusInsideList " +
                "anchor=${currentAnchor?.adapterPosition} token=$userNavigationToken"
        )
        if (!oldFocusInsideList || newFocusInsideList) {
            return@OnGlobalFocusChangeListener
        }
        val token = userNavigationToken
        recyclerView.post {
            if (token != userNavigationToken || SystemClock.uptimeMillis() > restoreOutsideFocusUntilMs) {
                return@post
            }
            logD("outsideFocusDuringMove: restoring anchor=${currentAnchor?.adapterPosition}")
            ensureValidFocus(
                reason = "outsideFocusDuringMove",
                allowWhenFocusOutside = true
            )
        }
    }
    private val childAttachListener = object : RecyclerView.OnChildAttachStateChangeListener {
        override fun onChildViewAttachedToWindow(view: View) = Unit

        override fun onChildViewDetachedFromWindow(view: View) {
            if (!restoreFocusOnFocusedDetach) {
                return
            }
            val focused = recyclerView.rootView?.findFocus()
            val focusInsideDetached = focused != null && isDescendantOf(focused, view)
            val focusOutsideList = focused != null && !isDescendantOf(focused, recyclerView)
            logD(
                "childDetached: view=${describeView(view)} focused=${describeView(focused)} " +
                    "focusInsideDetached=$focusInsideDetached focusOutsideList=$focusOutsideList " +
                    "anchor=${currentAnchor?.adapterPosition}"
            )
            if (!focusInsideDetached && !focusOutsideList) {
                return
            }
            recyclerView.post {
                if (!recyclerView.isAttachedToWindow) {
                    return@post
                }
                ensureValidFocus(
                    reason = "focusedDetach",
                    allowWhenFocusOutside = true
                )
            }
        }
    }

    init {
        if (restoreFocusOnFocusedDetach) {
            recyclerView.addOnChildAttachStateChangeListener(childAttachListener)
            recyclerView.viewTreeObserver.addOnGlobalFocusChangeListener(globalFocusListener)
        }
        logD(
            "install: rvId=${viewIdName(recyclerView)} orientation=${(recyclerView.layoutManager as? LinearLayoutManager)?.orientation} " +
                "restoreAppendFocusFromOutside=$restoreAppendFocusFromOutside " +
                "restoreFocusOnFocusedDetach=$restoreFocusOnFocusedDetach"
        )
    }

    fun onItemFocused(view: View, position: Int) {
        if (!adapter.isFocusablePosition(position)) {
            logW("onItemFocused: pos=$position NOT focusable, itemCount=${adapter.focusableItemCount()}")
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
        logD("onItemFocused: pos=$position row=${anchor?.row} col=${anchor?.column} key=${anchor?.stableKey}")
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
        userNavigationToken++
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
                logW("handleKey: $dirName view=${describeView(view)} not in this RV, releasing anchor and returning false")
                currentAnchor = null
                return false
            }
        }
        if (position != RecyclerView.NO_POSITION && adapter.isFocusablePosition(position)) {
            currentAnchor = createAnchor(view, position, TvFocusAnchor.Source.FOCUS)
        }
        logD(
            "handleKey: dir=$dirName view=${describeView(view)} pos=$position " +
                "anchor=${currentAnchor?.adapterPosition}(${currentAnchor?.row},${currentAnchor?.column}) " +
                "itemCount=${adapter.focusableItemCount()}"
        )
        return move(direction)
    }

    fun onDataChanged(reason: TvDataChangeReason = TvDataChangeReason.REPLACE_PRESERVE_ANCHOR) {
        logD(
            "onDataChanged: reason=$reason itemCount=${adapter.focusableItemCount()} " +
                "hasValidFocused=${hasValidFocusedItem()} currentAnchor=${currentAnchor?.adapterPosition} " +
                "capturedAnchor=${capturedAnchor?.adapterPosition} rootFocus=${describeView(recyclerView.rootView?.findFocus())}"
        )
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
            if (recyclerView.isInTouchMode) {
                return
            }
            val anchorBeforeAppend = currentAnchor ?: capturedAnchor
            val navigationToken = userNavigationToken
            pendingMoveAfterLoadMore = null
            // Don't auto-move focus to new items (preserves existing design).
            // But if focus was lost during a fast-scroll + loadMore cycle, recover it.
            ensureValidFocus("appendFocusRecovery")
            scheduleAppendFocusRestore(anchorBeforeAppend, navigationToken)
            return
        }

        if (hasValidFocusedItem()) {
            // Focus looks valid now, but the upcoming layout pass might detach the focused view
            // and move focus to an unexpected position. Park focus on the RecyclerView itself
            // so children can't steal focus during layout, then restore to the correct position.
            val anchor = currentAnchor ?: capturedAnchor
            if (anchor != null) {
                val resolved = resolveAnchorPosition(anchor)
                if (resolved != RecyclerView.NO_POSITION && adapter.isFocusablePosition(resolved)) {
                    val capturedResolved = resolved
                    // Park: make RV itself focusable and take focus away from children
                    if (parkedDescendantFocusability == null) {
                        parkedDescendantFocusability = recyclerView.descendantFocusability
                        recyclerView.descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
                    }
                    if (parkedRecyclerFocusable == null) {
                        parkedRecyclerFocusable = recyclerView.isFocusable
                        recyclerView.isFocusable = true
                    }
                    suppressRecyclerDefaultFocusHighlight()
                    recyclerView.requestFocus()
                    logD("parkFocus: anchor=$resolved reason=$reason")
                    // After layout completes, restore focus to the correct child
                    recyclerView.post {
                        unparkFocusInRecyclerViewIfNeeded()
                        if (!adapter.isFocusablePosition(capturedResolved)) return@post
                        requestRefreshFocus(capturedResolved)
                    }
                }
            }
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
        if (recyclerView.isInTouchMode) {
            return false
        }
        val focused = recyclerView.rootView?.findFocus()
        val focusInsideList = focused != null && isDescendantOf(focused, recyclerView)
        val focusDetachedOrHidden = focused != null && (!focused.isAttachedToWindow || !focused.isShown)
        if (focused != null && !focusInsideList && !focusDetachedOrHidden && !allowWhenFocusOutside) {
            logD("ensureValidFocus: skip reason=$reason outsideFocus=${describeView(focused)}")
            return false
        }

        val anchor = currentAnchor ?: capturedAnchor
        if (anchor != null) {
            val resolved = resolveAnchorPosition(anchor)
            if (resolved != RecyclerView.NO_POSITION) {
                logD("ensureValidFocus: restore anchor pos=$resolved reason=$reason focused=${describeView(focused)}")
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
            logD("ensureValidFocus: restore firstVisible pos=$firstVisible reason=$reason focused=${describeView(focused)}")
            return focusPosition(
                firstVisible,
                0,
                reason,
                allowOutsideFocus = allowWhenFocusOutside || focusDetachedOrHidden
            )
        }
        logW("ensureValidFocus: no focus target reason=$reason itemCount=${adapter.focusableItemCount()}")
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

    fun requestFocusPosition(position: Int, allowOutsideFocus: Boolean = false): Boolean {
        if (!adapter.isFocusablePosition(position)) {
            return false
        }
        val anchor = strategy.anchorFor(
            position = position,
            stableKey = adapter.stableKeyAt(position),
            offsetTop = 0
        )
        currentAnchor = anchor
        return focusPosition(position, anchor.offsetTop, "request", allowOutsideFocus = allowOutsideFocus)
    }

    fun captureCurrentAnchor(): Boolean {
        val focused = recyclerView.rootView?.findFocus()
        val position = focused?.let(::resolveAdapterPosition) ?: RecyclerView.NO_POSITION
        val hasRealFocus = focused != null &&
            position != RecyclerView.NO_POSITION &&
            adapter.isFocusablePosition(position)
        capturedAnchor = if (hasRealFocus) {
            createAnchor(focused!!, position, TvFocusAnchor.Source.RETURN_RESTORE)
        } else if (currentAnchor != null && resolveAnchorPosition(currentAnchor!!) != RecyclerView.NO_POSITION) {
            currentAnchor
        } else {
            anchorFromVisibleOrCurrent()
        }
        logD("captureCurrentAnchor: hasRealFocus=$hasRealFocus pos=$position focused=${describeView(focused)} capturedPos=${capturedAnchor?.adapterPosition} capturedKey=${capturedAnchor?.stableKey}")
        return hasRealFocus
    }

    fun restoreCapturedAnchor(): Boolean {
        val anchor = capturedAnchor ?: currentAnchor ?: run {
            logD("restoreCapturedAnchor: no anchor, return false")
            return false
        }
        val position = resolveAnchorPosition(anchor)
        logD("restoreCapturedAnchor: anchorKey=${anchor.stableKey} anchorPos=${anchor.adapterPosition} resolvedPos=$position")
        if (position == RecyclerView.NO_POSITION) {
            logD("restoreCapturedAnchor: resolvedPos=NO_POSITION, return false")
            return false
        }
        val result = focusPosition(position, anchor.offsetTop, "returnRestore", allowOutsideFocus = true)
        logD("restoreCapturedAnchor: focusPosition result=$result")
        return result
    }

    fun clearAnchorForUserRefresh() {
        currentAnchor = null
        capturedAnchor = null
        pendingMoveAfterLoadMore = null
        refreshFocusTarget = null
        unparkFocusInRecyclerViewIfNeeded()
        restoreAllFocus()
        operator.cancelPendingFocus()
    }

    fun release() {
        if (restoreFocusOnFocusedDetach) {
            recyclerView.removeOnChildAttachStateChangeListener(childAttachListener)
            if (recyclerView.viewTreeObserver.isAlive) {
                recyclerView.viewTreeObserver.removeOnGlobalFocusChangeListener(globalFocusListener)
            }
        }
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

    private fun scheduleAppendFocusRestore(anchor: TvFocusAnchor?, navigationToken: Int) {
        if (anchor == null || !isAnchorNearViewport(anchor)) {
            return
        }
        recyclerView.postDelayed({
            if (navigationToken != userNavigationToken) {
                return@postDelayed
            }
            val focused = recyclerView.rootView?.findFocus()
            val focusedPosition = focused?.let(::resolveAdapterPosition) ?: RecyclerView.NO_POSITION
            val resolved = resolveAnchorPosition(anchor)
            if (resolved == RecyclerView.NO_POSITION || focusedPosition == resolved) {
                return@postDelayed
            }
            val focusInsideList = focused != null && isDescendantOf(focused, recyclerView)
            if (focused != null && !focusInsideList && !restoreAppendFocusFromOutside) {
                return@postDelayed
            }
            logD("appendFocusRestore: focused=$focusedPosition focus=${describeView(focused)} -> anchor=$resolved")
            focusPosition(
                resolved,
                anchor.offsetTop,
                "appendAnchorRestore",
                allowOutsideFocus = restoreAppendFocusFromOutside
            )
        }, 80L)
    }

    private fun move(direction: Int): Boolean {
        val dirName = directionName(direction)
        val itemCount = adapter.focusableItemCount()
        if (itemCount <= 0) {
            logW("move: $dirName BLOCKED itemCount=0")
            return false
        }
        val anchor = currentAnchor ?: anchorFromFocusedOrVisible()
        if (anchor == null) {
            logW("move: $dirName BLOCKED no anchor, currentFocus=${describeView(recyclerView.rootView?.findFocus())}")
            return false
        }
        val target = strategy.nextPosition(anchor, direction, itemCount)
        logD("move: $dirName anchor=${anchor.adapterPosition}(${anchor.row},${anchor.column}) -> target=$target itemCount=$itemCount")
        if (target != null) {
            if (restoreFocusOnFocusedDetach) {
                restoreOutsideFocusUntilMs = SystemClock.uptimeMillis() + 500L
                logD("moveRestoreWindow: dir=$dirName target=$target until=$restoreOutsideFocusUntilMs token=$userNavigationToken")
                parkFocusIfTargetNeedsScroll(target)
            }
            currentAnchor = strategy.anchorFor(
                position = target,
                stableKey = adapter.stableKeyAt(target),
                offsetTop = anchor.offsetTop
            )
            return focusPosition(target, anchor.offsetTop, "move")
        }
        val shouldHandleDownAtEdge = direction == View.FOCUS_DOWN &&
            TvFocusMovePolicy.shouldHandleDownAfterStrategyMiss(
                (recyclerView.layoutManager as? LinearLayoutManager)?.orientation
            )
        if (shouldHandleDownAtEdge && canLoadMore() && pendingMoveAfterLoadMore == null) {
            logD("move: DOWN at bottom, triggering loadMore")
            pendingMoveAfterLoadMore = anchor.copy(source = TvFocusAnchor.Source.PENDING_LOAD_MORE)
            loadMore()
            return true
        }
        if (shouldHandleDownAtEdge && pendingMoveAfterLoadMore != null) {
            logD("move: DOWN pending loadMore, consuming key")
            return true
        }
        if (shouldHandleDownAtEdge) {
            logD("move: DOWN at edge with no more data, consuming key")
            return true
        }
        logD("move: $dirName at edge, returning false (not handled)")
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
            logD("focusPosition: BLOCKED reason=$reason focus outside RV on ${describeView(focused)}")
            return false
        }
        logD("focusPosition: pos=$position offset=$offsetTop reason=$reason focused=${describeView(focused)}")
        return operator.focusPosition(position, offsetTop, reason) { focusedPosition ->
            restoreOutsideFocusUntilMs = TvFocusMovePolicy.restoreWindowAfterFocused()
            unparkFocusInRecyclerViewIfNeeded()
            currentAnchor = strategy.anchorFor(
                position = focusedPosition,
                stableKey = adapter.stableKeyAt(focusedPosition),
                offsetTop = offsetTop
            )
            logD("focusPosition OK: focused=$focusedPosition row=${currentAnchor?.row} col=${currentAnchor?.column}")
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

    private fun logD(message: String) {
        AppLog.d(TAG, "[$debugName] $message")
    }

    private fun logW(message: String) {
        AppLog.w(TAG, "[$debugName] $message")
    }

    private fun describeView(view: View?): String {
        if (view == null) return "null"
        val position = resolveAdapterPosition(view)
        val idName = viewIdName(view)
        return "${view.javaClass.simpleName}(id=$idName,pos=$position,attached=${view.isAttachedToWindow},shown=${view.isShown},focusable=${view.isFocusable})"
    }

    private fun viewIdName(view: View): String {
        val id = view.id
        if (id == View.NO_ID) return "no-id"
        return runCatching { view.resources.getResourceEntryName(id) }.getOrDefault(id.toString())
    }

    private fun parkFocusIfTargetNeedsScroll(targetPosition: Int) {
        val focused = recyclerView.rootView?.findFocus()
        val focusIsOutsideList = focused != null && focused !== recyclerView && !isDescendantOf(focused, recyclerView)
        if (!TvFocusParkingPolicy.shouldParkFocusForPendingTarget(
                hasAttachedFocusableTarget = hasAttachedFocusableItem(targetPosition),
                focusIsOutsideList = focusIsOutsideList
            )
        ) {
            if (focusIsOutsideList) {
                logD("parkFocus.skipOutside: target=$targetPosition focused=${describeView(focused)}")
            }
            return
        }

        if (parkedDescendantFocusability == null) {
            parkedDescendantFocusability = recyclerView.descendantFocusability
            recyclerView.descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
        }
        if (parkedRecyclerFocusable == null) {
            parkedRecyclerFocusable = recyclerView.isFocusable
            recyclerView.isFocusable = true
        }
        suppressRecyclerDefaultFocusHighlight()

        val handled = recyclerView.isFocused || recyclerView.requestFocus()
        logD("parkFocus: target=$targetPosition handled=$handled focused=${describeView(recyclerView.rootView?.findFocus())}")
    }

    private fun unparkFocusInRecyclerViewIfNeeded() {
        parkedDescendantFocusability?.let { original ->
            recyclerView.descendantFocusability = original
            parkedDescendantFocusability = null
        }
        parkedRecyclerFocusable?.let { original ->
            recyclerView.isFocusable = original
            parkedRecyclerFocusable = null
        }
        restoreRecyclerDefaultFocusHighlightIfNeeded()
    }

    private fun hasAttachedFocusableItem(position: Int): Boolean {
        val holder = recyclerView.findViewHolderForAdapterPosition(position) ?: return false
        val itemView = holder.itemView
        return itemView.visibility == View.VISIBLE &&
            itemView.isAttachedToWindow &&
            itemView.isFocusable &&
            isPartiallyVisible(itemView)
    }

    private fun isPartiallyVisible(itemView: View): Boolean {
        val layoutManager = recyclerView.layoutManager as? LinearLayoutManager
        return if (layoutManager?.orientation == RecyclerView.HORIZONTAL) {
            val parentStart = recyclerView.paddingLeft
            val parentEnd = recyclerView.width - recyclerView.paddingRight
            itemView.right > parentStart && itemView.left < parentEnd
        } else {
            val parentTop = recyclerView.paddingTop
            val parentBottom = recyclerView.height - recyclerView.paddingBottom
            itemView.bottom > parentTop && itemView.top < parentBottom
        }
    }

    private fun suppressRecyclerDefaultFocusHighlight() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        if (parkedDefaultFocusHighlightEnabled == null) {
            parkedDefaultFocusHighlightEnabled = recyclerView.defaultFocusHighlightEnabled
        }
        if (recyclerView.defaultFocusHighlightEnabled) {
            recyclerView.defaultFocusHighlightEnabled = false
        }
    }

    private fun restoreRecyclerDefaultFocusHighlightIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val original = parkedDefaultFocusHighlightEnabled ?: return
        recyclerView.defaultFocusHighlightEnabled = original
        parkedDefaultFocusHighlightEnabled = null
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

internal object TvFocusParkingPolicy {
    fun shouldParkFocusForPendingTarget(
        hasAttachedFocusableTarget: Boolean,
        focusIsOutsideList: Boolean
    ): Boolean {
        return !hasAttachedFocusableTarget && !focusIsOutsideList
    }
}

internal object TvFocusMovePolicy {
    fun shouldHandleDownAfterStrategyMiss(orientation: Int?): Boolean {
        return orientation != RecyclerView.HORIZONTAL
    }

    fun restoreWindowAfterFocused(): Long {
        return 0L
    }
}
