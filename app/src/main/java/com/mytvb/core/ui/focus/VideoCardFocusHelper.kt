package com.mytvb.core.ui.focus

import android.content.Context
import android.content.ContextWrapper
import android.view.KeyEvent
import android.view.View
import android.view.FocusFinder
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mytvb.R
import com.mytvb.core.common.log.AppLog
import com.mytvb.core.ui.focus.tv.TvFocusableAdapter
import com.mytvb.ui.activity.MainActivity

object VideoCardFocusHelper {
    private const val TAG = "VideoCardFocus"
    private val TAG_DETACH_LISTENER = R.id.tag_focus_detach_listener
    private val TAG_LINEAR_FOCUS_TOKEN = R.id.tag_linear_focus_token

    fun bindSidebarExit(
        view: View,
        onTopEdgeUp: (() -> Boolean)? = null,
        onLeftEdge: (() -> Boolean)? = null,
        onRightEdge: (() -> Boolean)? = null,
        onBottomEdgeDown: (() -> Boolean)? = null,
        handleListDpadDown: Boolean = true,
        chainedListener: View.OnKeyListener? = null
    ) {
        installDetachProtection(view)
        view.setOnKeyListener { target, keyCode, event ->
            if (shouldOfferHorizontalKeyToChainedListener(target, keyCode, event)) {
                if (chainedListener?.onKey(target, keyCode, event) == true) {
                    return@setOnKeyListener true
                }
            }
            val handledBySidebar = handleSidebarNavigation(
                target, keyCode, event,
                onTopEdgeUp, onLeftEdge, onRightEdge, onBottomEdgeDown, handleListDpadDown
            )
            if (handledBySidebar) {
                true
            } else {
                chainedListener?.onKey(target, keyCode, event) ?: false
            }
        }
    }

    private fun shouldOfferHorizontalKeyToChainedListener(
        target: View,
        keyCode: Int,
        event: KeyEvent
    ): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) {
            return false
        }
        if (keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) {
            return false
        }
        val layoutManager = target.findParentRecyclerView()?.layoutManager as? LinearLayoutManager
            ?: return false
        return layoutManager.orientation == RecyclerView.HORIZONTAL
    }

    private fun installDetachProtection(view: View) {
        tryInstallDetachProtection(view)
        if (view.parent == null) {
            view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    v.removeOnAttachStateChangeListener(this)
                    tryInstallDetachProtection(v)
                }
                override fun onViewDetachedFromWindow(v: View) = Unit
            })
        }
    }

    private fun tryInstallDetachProtection(view: View) {
        val rv = view.findParentRecyclerView() ?: return
        if (rv.getTag(TAG_DETACH_LISTENER) != null) return
        val listener = object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(v: View) = Unit

            override fun onChildViewDetachedFromWindow(detached: View) {
                val focused = detached.rootView.findFocus() ?: return
                if (focused !== detached && !focused.isDescendantOf(detached)) return
                val lm = rv.layoutManager as? LinearLayoutManager ?: return
                if (lm.orientation == RecyclerView.HORIZONTAL) {
                    return
                }
                val first = lm.findFirstVisibleItemPosition()
                val last = lm.findLastVisibleItemPosition()
                if (first == RecyclerView.NO_POSITION) return
                val action = Runnable {
                    if (!rv.isAttachedToWindow) return@Runnable
                    transferFocusAfterDetach(rv, detached, lm, first, last)
                }
                if (rv.isComputingLayout) {
                    rv.post(action)
                } else {
                    action.run()
                }
            }
        }
        rv.addOnChildAttachStateChangeListener(listener)
        rv.setTag(TAG_DETACH_LISTENER, true)
        rv.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                rv.removeOnChildAttachStateChangeListener(listener)
                rv.setTag(TAG_DETACH_LISTENER, null)
            }
        })
    }

    private fun transferFocusAfterDetach(
        rv: RecyclerView,
        detached: View,
        lm: LinearLayoutManager,
        first: Int,
        last: Int
    ) {
        val detachedPos = rv.getChildAdapterPosition(detached)
        if (lm is GridLayoutManager && detachedPos != RecyclerView.NO_POSITION) {
            val spanCount = lm.spanCount
            val column = lm.spanSizeLookup.getSpanIndex(detachedPos, spanCount)
            for (pos in last downTo first) {
                val holder = rv.findViewHolderForAdapterPosition(pos)
                if (holder != null && holder.itemView !== detached) {
                    val posColumn = lm.spanSizeLookup.getSpanIndex(pos, spanCount)
                    if (posColumn == column && holder.itemView.requestFocus()) {
                        return
                    }
                }
            }
        }
        var pos = last
        while (pos >= first) {
            val holder = rv.findViewHolderForAdapterPosition(pos)
            if (holder != null && holder.itemView !== detached && holder.itemView.requestFocus()) {
                return
            }
            pos--
        }
    }

    private fun handleSidebarNavigation(
        target: View,
        keyCode: Int,
        event: KeyEvent,
        onTopEdgeUp: (() -> Boolean)?,
        onLeftEdge: (() -> Boolean)?,
        onRightEdge: (() -> Boolean)?,
        onBottomEdgeDown: (() -> Boolean)?,
        handleListDpadDown: Boolean
    ): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) {
            return false
        }
        if (!target.isAttachedToWindow || !target.isShown) {
            AppLog.d(TAG, "ignore key=$keyCode on detached/hidden target=${target.javaClass.simpleName}")
            return false
        }
        val currentFocus = target.rootView.findFocus()
        if (currentFocus == null || (currentFocus !== target && !currentFocus.isDescendantOf(target))) {
            AppLog.d(TAG, "ignore key=$keyCode because current focus is not target: focus=${currentFocus?.javaClass?.simpleName}")
            return false
        }
        val rv = target.findParentRecyclerView()
        val pos = rv?.getChildAdapterPosition(target) ?: RecyclerView.NO_POSITION
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                val atLeftEdge = isAtLeftEdge(target)
                AppLog.d(TAG, "DPAD_LEFT pos=$pos atLeft=$atLeftEdge")
                if (handleHorizontalLinearNavigation(target, -1)) {
                    return true
                }
                if (!atLeftEdge) {
                    return false
                }
                // 页面未显式接线时的统一兜底：环绕回主边栏当前激活主 tab 对应按钮
                val result = onLeftEdge?.invoke()
                    ?: (target.context.findMainActivity()?.focusSidebarCurrentTab() == true)
                AppLog.d(TAG, "DPAD_LEFT pos=$pos edge→sidebar=$result")
                return result
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val atRightEdge = isAtRightEdge(target)
                AppLog.d(TAG, "DPAD_RIGHT pos=$pos atRight=$atRightEdge")
                if (handleHorizontalLinearNavigation(target, 1)) {
                    return true
                }
                if (!atRightEdge) {
                    return false
                }
                // 页面未显式接线时吞键停原地（对齐 blbl "No outflow on the right edge"）；
                // 不环绕去侧栏——右边缘向右按键焦点跳到左侧属反直觉（用户实测反馈）
                val result = onRightEdge?.invoke() ?: true
                AppLog.d(TAG, "DPAD_RIGHT pos=$pos edge→handled=$result")
                return result
            }

            KeyEvent.KEYCODE_DPAD_UP -> {
                RecyclerViewLoadMoreFocusController.fromView(target)
                    ?.notifyItemVerticalNavigation(target, View.FOCUS_UP)
                val atTopEdge = isAtTopEdge(target)
                AppLog.d(TAG, "DPAD_UP pos=$pos atTop=$atTopEdge")
                if (atTopEdge && onTopEdgeUp != null) {
                    val result = onTopEdgeUp()
                    AppLog.d(TAG, "DPAD_UP pos=$pos topEdge→handled=$result")
                    return result
                }
            }

            KeyEvent.KEYCODE_DPAD_DOWN -> {
                val atBottomEdge = isAtBottomEdge(target)
                AppLog.d(TAG, "DPAD_DOWN pos=$pos atBottom=$atBottomEdge handleList=$handleListDpadDown")
                if (atBottomEdge && onBottomEdgeDown != null) {
                    val result = onBottomEdgeDown()
                    AppLog.d(TAG, "DPAD_DOWN pos=$pos bottomEdge→handled=$result")
                    return result
                }
                if (!handleListDpadDown) {
                    AppLog.d(TAG, "DPAD_DOWN pos=$pos not handled by card, passing through")
                    return false
                }
                val loadMoreController = RecyclerViewLoadMoreFocusController.fromView(target)
                if (loadMoreController != null) {
                    val result = loadMoreController.handleItemDpadDown(target)
                    AppLog.d(TAG, "DPAD_DOWN pos=$pos loadMoreCtrl=$result")
                    return result
                }
                if (rv != null) {
                    // 网格：优先用“下一行同列”算法，避免退化为 FocusFinder 空间查找导致跳行不稳定。
                    // 标准网格下等价于 pos + spanCount；若未命中（非标准网格/到底部）再回退 FocusFinder。
                    val lm = rv.layoutManager
                    if (lm is GridLayoutManager && pos != RecyclerView.NO_POSITION) {
                        val targetPos = resolveGridDownPosition(
                            lm = lm,
                            position = pos,
                            itemCount = rv.adapter?.itemCount ?: 0
                        )
                        AppLog.d(TAG, "DPAD_DOWN pos=$pos grid down target=$targetPos")
                        if (targetPos != RecyclerView.NO_POSITION) {
                            if (requestGridDownFocus(rv, targetPos, lm)) {
                                return true
                            }
                        }
                    }
                    val nextFocus = FocusFinder.getInstance().findNextFocus(rv, target, View.FOCUS_DOWN)
                    AppLog.d(TAG, "DPAD_DOWN pos=$pos FocusFinder next=${
                        nextFocus?.let {
                            val nPos = rv.getChildAdapterPosition(it)
                            "${it.javaClass.simpleName}(pos=$nPos)"
                        } ?: "null"
                    }")
                    if (nextFocus != null && nextFocus.isDescendantOf(rv)) {
                        nextFocus.requestFocus()
                        return true
                    }
                }
                AppLog.d(TAG, "DPAD_DOWN pos=$pos no next focus found, returning false")
            }
        }
        return false
    }

    private fun handleHorizontalLinearNavigation(view: View, direction: Int): Boolean {
        val recyclerView = view.findParentRecyclerView() ?: return false
        val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return false
        if (layoutManager.orientation != RecyclerView.HORIZONTAL) {
            return false
        }
        val adapter = recyclerView.adapter ?: return false
        val position = recyclerView.getChildAdapterPosition(view)
        val targetPosition = LinearFocusStep.resolveTargetPosition(
            position = position,
            itemCount = (adapter as? TvFocusableAdapter)?.focusableItemCount() ?: adapter.itemCount,
            direction = direction
        )
        if (targetPosition == RecyclerView.NO_POSITION) {
            return false
        }
        val token = ((recyclerView.getTag(TAG_LINEAR_FOCUS_TOKEN) as? Int) ?: 0) + 1
        recyclerView.setTag(TAG_LINEAR_FOCUS_TOKEN, token)
        if (requestHorizontalPositionFocus(recyclerView, targetPosition)) {
            return true
        }
        layoutManager.scrollToPositionWithOffset(targetPosition, 0)
        recyclerView.post {
            if ((recyclerView.getTag(TAG_LINEAR_FOCUS_TOKEN) as? Int) != token) {
                return@post
            }
            requestHorizontalPositionFocus(recyclerView, targetPosition)
        }
        return true
    }

    private fun requestHorizontalPositionFocus(
        recyclerView: RecyclerView,
        position: Int
    ): Boolean {
        val holder = recyclerView.findViewHolderForAdapterPosition(position) ?: return false
        val itemView = holder.itemView
        if (!itemView.isAttachedToWindow || !itemView.isShown || !itemView.isFocusable) {
            return false
        }
        return itemView.requestFocus()
    }

    private fun isAtLeftEdge(view: View): Boolean {
        val recyclerView = view.findParentRecyclerView() ?: return false
        val layoutManager = recyclerView.layoutManager ?: return false
        val position = recyclerView.getChildAdapterPosition(view)
        if (position == RecyclerView.NO_POSITION) {
            return false
        }
        return when (layoutManager) {
            is GridLayoutManager -> {
                if (layoutManager.orientation == RecyclerView.HORIZONTAL) {
                    position == 0
                } else {
                    layoutManager.spanSizeLookup.getSpanIndex(position, layoutManager.spanCount) == 0
                }
            }
            is LinearLayoutManager -> layoutManager.orientation == RecyclerView.VERTICAL
            else -> false
        }
    }

    private fun isAtTopEdge(view: View): Boolean {
        val recyclerView = view.findParentRecyclerView() ?: return false
        val layoutManager = recyclerView.layoutManager ?: return false
        val position = recyclerView.getChildAdapterPosition(view)
        if (position == RecyclerView.NO_POSITION) {
            return false
        }
        return when (layoutManager) {
            is GridLayoutManager -> {
                layoutManager.spanSizeLookup.getSpanGroupIndex(position, layoutManager.spanCount) == 0
            }

            is LinearLayoutManager -> {
                layoutManager.orientation == RecyclerView.VERTICAL && position == 0
            }

            else -> false
        }
    }

    private fun isAtRightEdge(view: View): Boolean {
        val recyclerView = view.findParentRecyclerView() ?: return false
        val layoutManager = recyclerView.layoutManager ?: return false
        val adapter = recyclerView.adapter ?: return false
        val position = recyclerView.getChildAdapterPosition(view)
        if (position == RecyclerView.NO_POSITION) {
            return false
        }
        return when (layoutManager) {
            is GridLayoutManager -> {
                if (layoutManager.orientation == RecyclerView.HORIZONTAL) {
                    position == adapter.itemCount - 1
                } else {
                    val spanCount = layoutManager.spanCount
                    val spanIndex = layoutManager.spanSizeLookup.getSpanIndex(position, spanCount)
                    val spanSize = layoutManager.spanSizeLookup.getSpanSize(position)
                    if (spanIndex + spanSize == spanCount) {
                        true
                    } else {
                        val currentGroup = layoutManager.spanSizeLookup
                            .getSpanGroupIndex(position, spanCount)
                        val nextPosition = position + 1
                        nextPosition >= adapter.itemCount || layoutManager.spanSizeLookup
                            .getSpanGroupIndex(nextPosition, spanCount) != currentGroup
                    }
                }
            }
            is LinearLayoutManager -> layoutManager.orientation == RecyclerView.VERTICAL
            else -> false
        }
    }

    private fun isAtBottomEdge(view: View): Boolean {
        val recyclerView = view.findParentRecyclerView() ?: return false
        val layoutManager = recyclerView.layoutManager ?: return false
        val adapter = recyclerView.adapter ?: return false
        val position = recyclerView.getChildAdapterPosition(view)
        if (position == RecyclerView.NO_POSITION || adapter.itemCount <= 0) {
            return false
        }
        val lastPosition = adapter.itemCount - 1
        return when (layoutManager) {
            is GridLayoutManager -> {
                val currentGroup = layoutManager.spanSizeLookup.getSpanGroupIndex(position, layoutManager.spanCount)
                val lastGroup = layoutManager.spanSizeLookup.getSpanGroupIndex(lastPosition, layoutManager.spanCount)
                currentGroup == lastGroup
            }

            is LinearLayoutManager -> {
                layoutManager.orientation == RecyclerView.VERTICAL && position == lastPosition
            }

            else -> false
        }
    }

    private fun resolveGridDownPosition(
        lm: GridLayoutManager,
        position: Int,
        itemCount: Int
    ): Int {
        if (position == RecyclerView.NO_POSITION || itemCount <= 0) {
            return RecyclerView.NO_POSITION
        }
        val spanCount = lm.spanCount
        val currentGroup = lm.spanSizeLookup.getSpanGroupIndex(position, spanCount)
        val currentColumn = lm.spanSizeLookup.getSpanIndex(position, spanCount)
        var i = position + 1
        while (i < itemCount) {
            val group = lm.spanSizeLookup.getSpanGroupIndex(i, spanCount)
            if (group > currentGroup) {
                // 若跨过超过一行，说明网格存在跨列 item 导致无法精确计算，交由 FocusFinder 兜底
                if (group != currentGroup + 1) {
                    return RecyclerView.NO_POSITION
                }
                if (lm.spanSizeLookup.getSpanIndex(i, spanCount) == currentColumn) {
                    return i
                }
            }
            i++
        }
        return RecyclerView.NO_POSITION
    }

    private fun requestGridDownFocus(
        rv: RecyclerView,
        position: Int,
        lm: GridLayoutManager
    ): Boolean {
        val holder = rv.findViewHolderForAdapterPosition(position)
        if (holder != null) {
            val itemView = holder.itemView
            if (itemView.isAttachedToWindow && itemView.isShown && itemView.isFocusable) {
                return itemView.requestFocus()
            }
        }
        // 目标 ViewHolder 未附着：先滚动到目标行，再在下一帧请求焦点
        if (!rv.isComputingLayout) {
            lm.scrollToPosition(position)
        }
        rv.post {
            if (!rv.isAttachedToWindow) return@post
            val h = rv.findViewHolderForAdapterPosition(position)
            if (h != null && h.itemView.isAttachedToWindow && h.itemView.isShown && h.itemView.isFocusable) {
                h.itemView.requestFocus()
            }
        }
        return true
    }

    private fun View.findParentRecyclerView(): RecyclerView? {
        var current = parent
        while (current != null) {
            if (current is RecyclerView) {
                return current
            }
            current = current.parent
        }
        return null
    }

    private fun Context.findMainActivity(): MainActivity? {
        var current = this
        while (current is ContextWrapper) {
            if (current is MainActivity) {
                return current
            }
            current = current.baseContext
        }
        return null
    }
}

internal object LinearFocusStep {
    fun resolveTargetPosition(position: Int, itemCount: Int, direction: Int): Int {
        if (position == RecyclerView.NO_POSITION || itemCount <= 0) {
            return RecyclerView.NO_POSITION
        }
        if (direction != -1 && direction != 1) {
            return RecyclerView.NO_POSITION
        }
        val targetPosition = position + direction
        return if (targetPosition in 0 until itemCount) {
            targetPosition
        } else {
            RecyclerView.NO_POSITION
        }
    }
}
