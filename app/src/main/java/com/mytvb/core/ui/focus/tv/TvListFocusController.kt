package com.mytvb.core.ui.focus.tv

import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewTreeObserver
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mytvb.core.common.log.AppLog
import com.mytvb.core.ui.focus.isDescendantOf

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

        /** 返回焦点恢复：RV 未就绪（not shown / 无 holder）阶段的等待轮次上限（×120ms ≈ 3s）。 */
        private const val RETURN_FOCUS_WAIT_LIMIT = 25

        /** 刷新焦点抑制的兜底解除时限：目标未获得焦点则强制恢复全部 item 可聚焦。 */
        private const val REFRESH_SUPPRESS_TIMEOUT_MS = 1_000L

        /**
         * pendingMoveAfterLoadMore 的兜底时限：pending 的正常解除依赖 APPEND 数据落地，
         * 但加载链可能整段无声（loadMore NO-OP/失败/上游签名跳过 payload），没有
         * APPEND 到来时 DOWN 键会被永久吞掉（焦点卡死在最后一行）。超时清 pending
         * 并重试一次 loadMore。
         */
        private const val PENDING_LOAD_MORE_TIMEOUT_MS = 2_000L
    }

    private val operator = RecyclerViewFocusOperator(recyclerView, adapter)
    private var currentAnchor: TvFocusAnchor? = null
    private var capturedAnchor: TvFocusAnchor? = null
    private var pendingMoveAfterLoadMore: TvFocusAnchor? = null
    private var pendingMoveSinceUptimeMs = 0L
    private var refreshFocusTarget: Int? = null
    private var userNavigationToken = 0

    // restoreFocusAfterReturn 轮询单飞行标志：同一 controller 同时只允许一轮恢复轮询。
    private var returnFocusPollingActive = false

    // 返回恢复仲裁：轮询窗口期间「正在恢复的目标位置」。窗口内 onDataChanged 只作为
    // 「数据已落地」事件（挂起停泊/锚点/append 恢复——它们与轮询互派 RVFocusOp 重试链、
    // focusToken 互踩 STALE，是返回焦点风暴的挤兑源）；外部恢复类请求
    // （Fragment 层旧轮询的 restoreCapturedFocusPosition/requestFocusPosition）与该目标
    // 不一致时并入当前意图、一致时幂等跳过，不再重复派生。
    private var activeReturnRestorePosition: Int? = null

    // onDataChanged 触发的「数据已落地」恢复去重：一次数据变化只 post 一帧恢复一次。
    private var pendingRestoreAfterDataChange = false
    private var restoreOutsideFocusUntilMs = 0L
    private val focusParking = RvFocusParking(recyclerView, overrideRecyclerFocusable = true)
    private val globalFocusListener = ViewTreeObserver.OnGlobalFocusChangeListener { oldFocus, newFocus ->
        if (!restoreFocusOnFocusedDetach) {
            return@OnGlobalFocusChangeListener
        }
        if (SystemClock.uptimeMillis() > restoreOutsideFocusUntilMs) {
            return@OnGlobalFocusChangeListener
        }
        val oldFocusInsideList = oldFocus != null && oldFocus.isDescendantOf(recyclerView)
        val newFocusInsideList = newFocus != null && newFocus.isDescendantOf(recyclerView)
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
            val focusInsideDetached = focused != null && focused.isDescendantOf(view)
            val focusOutsideList = focused != null && !focused.isDescendantOf(recyclerView)
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
            clearAnchorsAndPendingOps()
            operator.cancelPendingFocus()
            endReturnRestorePolling("dataCleared")
            return
        }

        // 返回恢复窗口期间，数据变化的处置由仲裁器决策：数据落地事件 post 一帧后单次
        // 恢复（停泊/锚点/append 恢复挂起，否则与恢复轮询互派重试链、focusToken 互踩
        // STALE——返回焦点风暴根因）；用户主动刷新则中止恢复，交由刷新焦点流程接管。
        if (returnFocusPollingActive) {
            if (ReturnRestoreArbiter.shouldAbortForDataChange(reason)) {
                clearAnchorForUserRefresh()
                endReturnRestorePolling("userRefresh")
            } else {
                scheduleRestoreAfterDataLanded()
            }
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
                    focusParking.applyParkOverrides()
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

        // Don't steal focus if something outside the RecyclerView currently has focus;
        // likewise for focus inside the RV's non-focusable header area (e.g. filter tabs
        // hosted as a list header item) — anchor restore must not pull focus off them
        val focused = recyclerView.rootView?.findFocus()
        if (focused != null && !focused.isDescendantOf(recyclerView)) {
            return
        }
        if (focused != null) {
            val focusedChild = generateSequence(focused) { it.parent as? View }
                .firstOrNull { it.parent === recyclerView }
            val focusedPosition = focusedChild
                ?.let { recyclerView.getChildAdapterPosition(it) }
                ?: RecyclerView.NO_POSITION
            if (focusedPosition == RecyclerView.NO_POSITION || !adapter.isFocusablePosition(focusedPosition)) {
                return
            }
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
        val focusInsideList = focused != null && focused.isDescendantOf(recyclerView)
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
        // 兜底：抑制的解除依赖目标卡片触发 onItemFocused（200ms 清理）或用户按键。
        // 目标 holder 未就绪/焦点请求被拒时两条路都不会走，其他 item 将永久
        // isFocusable=false（现场：pos=1 not focusable 重试到耗尽）。超时强制解除。
        recyclerView.removeCallbacks(refreshSuppressTimeout)
        recyclerView.postDelayed(refreshSuppressTimeout, REFRESH_SUPPRESS_TIMEOUT_MS)
        return focusPosition(position, 0, "refresh", allowOutsideFocus = true)
    }

    private val refreshSuppressTimeout = Runnable {
        if (refreshFocusTarget != null) {
            refreshFocusTarget = null
            restoreAllFocus()
        }
    }

    fun requestFocusPosition(position: Int, allowOutsideFocus: Boolean = false): Boolean {
        if (!adapter.isFocusablePosition(position)) {
            return false
        }
        if (mergeIntoActiveReturnRestore(position, "requestFocusPosition")) {
            return true
        }
        val anchor = strategy.anchorFor(
            position = position,
            stableKey = adapter.stableKeyAt(position),
            offsetTop = 0
        )
        currentAnchor = anchor
        return focusPosition(position, anchor.offsetTop, "request", allowOutsideFocus = allowOutsideFocus)
    }

    /**
     * 列表内当前是否已有真实焦点（含子项）。
     * 用于"从播放器等外部页面返回后"判断焦点恢复是否真正成功：
     * - `requestFocusPosition` 的返回值不可靠（内部失败时也会返回 true），
     *   因此恢复成功与否需以真实焦点落点为准。
     * - 返回 true 表示焦点已在列表内（用户可继续 D-pad 导航），
     *   即使未精确落在目标卡片上也算恢复成功；否则上层应兜底重试/聚焦返回键。
     */
    fun hasFocusInList(): Boolean {
        val focused = recyclerView.rootView?.findFocus() ?: return false
        return focused.isDescendantOf(recyclerView)
    }

    /**
     * 是否已捕获有效的返回锚点（`capturedAnchor`）。
     * 点击卡片进入播放时会调用 [captureCurrentAnchor] 记录锚点；该锚点独立于
     * 上层 Fragment 的 `lastFocusedPosition`，不会因"返回按钮获得焦点"等动作被清空，
     * 是从播放器返回后恢复焦点的最可靠依据。
     */
    fun hasCapturedAnchor(): Boolean {
        if (capturedAnchor != null) return true
        return currentAnchor != null && resolveAnchorPosition(currentAnchor!!) != RecyclerView.NO_POSITION
    }

    fun captureCurrentAnchor(): Boolean {
        val focused = recyclerView.rootView?.findFocus()
        val position = focused?.let(::resolveAdapterPosition) ?: RecyclerView.NO_POSITION
        val hasRealFocus = focused != null &&
            position != RecyclerView.NO_POSITION &&
            adapter.isFocusablePosition(position)
        // 触屏（touch mode）下根本没有焦点，不应伪造锚点，否则返回时会拿着假锚点去
        // requestFocus 而必然失败（一连串 returned FALSE）。此时老老实实返回 false，
        // 让上层走 ensureValidFocus 的既有 touch mode 让路逻辑。
        if (recyclerView.isInTouchMode && !hasRealFocus) {
            capturedAnchor = null
            logD("captureCurrentAnchor: touchMode without focus, skip fabricating anchor")
            return false
        }
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

    /**
     * 从播放器等外部页面返回时，将焦点恢复到点击时捕获的锚点位置。
     *
     * 区别于 [restoreCapturedAnchor]：
     * - [restoreCapturedAnchor] 会因"焦点已落在列表外部可见 View 上"（如返回按钮）而跳过——
     *   该逻辑是为侧边栏场景设计的；但"从播放器返回"时，焦点若落在返回按钮等外部控件上
     *   恰恰是需要拉回列表的，跳过会直接导致焦点停在返回按钮。
     * - 本方法强制把焦点拉回捕获锚点位置（`allowOutsideFocus = true` 绕过外部焦点 BLOCKED 检查），
     *   是返回恢复焦点最可靠的入口。
     */
    fun restoreCapturedFocusPosition(): Boolean {
        val anchor = capturedAnchor ?: currentAnchor ?: return false
        val position = resolveAnchorPosition(anchor)
        if (position == RecyclerView.NO_POSITION || !adapter.isFocusablePosition(position)) {
            logD("restoreCapturedFocusPosition: pos=$position NOT focusable, skip")
            return false
        }
        if (mergeIntoActiveReturnRestore(position, "restoreCapturedFocusPosition")) {
            return true
        }
        logD("restoreCapturedFocusPosition: pos=$position offset=${anchor.offsetTop}")
        return focusPosition(position, anchor.offsetTop, "returnFocus", allowOutsideFocus = true)
    }

    /**
     * 从播放器等外部页面返回时，将焦点恢复到列表内的统一入口（供各 Fragment 的 onResume/onHiddenChanged 复用）。
     *
     * 背景：`restoreCapturedAnchor` 会因"焦点已落在列表外部可见 View（如返回按钮）"而跳过，且其
     * 注释错误地假设"从播放器返回的焦点恢复走 MainActivity.restoreFocusAfterOverlayPop"——但播放器
     * （PlayerActivity / LivePlayerActivity / CctvPlayerActivity）是独立 Activity，返回走 finish()，
     * `restoreFocusAfterOverlayPop` 并不触发，导致焦点停在返回按钮或彻底丢失。
     *
     * 本方法：
     * 1. 用 [restoreCapturedFocusPosition] 强制把焦点拉回捕获锚点位置（绕过外部焦点 BLOCKED 检查）；
     * 2. 以 [hasFocusInList] 轮询**真实焦点落点**，覆盖 Activity 转场动画 / 布局未就绪窗口
     *    （真机大屏动画可能 >250ms，原 `scheduleAttachRetry` 250ms 窗口不足）；
     * 3. 成功回调 [onRestored]，无锚点或重试耗尽回调 [onFailed]（由上层决定兜底）。
     *
     * 安全性：一旦用户开始导航（[userNavigationToken] 变化）或列表 detached，轮询立即终止，不会抢焦点。
     */
    fun restoreFocusAfterReturn(
        retryTimes: Int = 6,
        retryDelayMs: Long = 120L,
        onRestored: () -> Unit = {},
        onFailed: () -> Unit = {}
    ) {
        // 单飞行保护：上一轮恢复轮询还在跑时（转场/刷新未就绪期间会拉长），
        // 新调用直接并入旧轮询，避免多路轮询并发各派生 RVFocusOp 重试链、
        // 互相顶掉 token（STALE 刷屏）。
        if (returnFocusPollingActive) {
            logD("restoreFocusAfterReturn: polling already active, skip duplicate call")
            return
        }
        if (isHiddenBehindVisibleOwner()) {
            // 被盖住的列表没有可恢复的焦点：不启动轮询（轮询窗口会把该页后续 onDataChanged
            // 全部导入恢复仲裁），也不回调 onFailed（各页兜底同样会被 focusPosition 拦下）
            logD("restoreFocusAfterReturn: list hidden behind visible owner, skip")
            return
        }
        val anchor = capturedAnchor ?: currentAnchor ?: run {
            logD("restoreFocusAfterReturn: no anchor, onFailed")
            onFailed()
            return
        }
        val position = resolveAnchorPosition(anchor)
        if (position == RecyclerView.NO_POSITION || !adapter.isFocusablePosition(position)) {
            logD("restoreFocusAfterReturn: pos=$position NOT focusable, onFailed")
            onFailed()
            return
        }
        // 立即强制恢复
        restoreCapturedFocusPosition()
        if (hasFocusInList()) {
            logD("restoreFocusAfterReturn: restored immediately, pos=$position")
            onRestored()
            return
        }
        // 转场动画 / 布局未就绪：轮询重试
        val token = userNavigationToken
        var attempts = 0
        var waits = 0
        returnFocusPollingActive = true
        activeReturnRestorePosition = position
        val runnable = object : Runnable {
            override fun run() {
                if (token != userNavigationToken) {
                    logD("restoreFocusAfterReturn: abort, user navigated")
                    endReturnRestorePolling("userNavigated")
                    return
                }
                if (recyclerView.rootView == null || !recyclerView.isAttachedToWindow) {
                    logD("restoreFocusAfterReturn: abort, not attached")
                    endReturnRestorePolling("notAttached")
                    return
                }
                if (hasFocusInList()) {
                    logD("restoreFocusAfterReturn: restored on retry $attempts, pos=$position")
                    endReturnRestorePolling("restored")
                    onRestored()
                    return
                }
                // 返回与网络刷新并发时 RV 可能长时间 not shown / 无 holder（真机实测
                // >1.1s），固定 720ms 窗口会在就绪前耗尽。未就绪阶段不消耗重试次数、
                // 也不调 restoreCapturedFocusPosition（每次调用都会派生 RVFocusOp 的
                // 5×50ms 重试链，多 tick 并发即重试风暴），单独等待，上限 3s 兜底退出。
                val holderReady =
                    recyclerView.isShown && recyclerView.findViewHolderForAdapterPosition(position) != null
                if (!holderReady) {
                    waits++
                    if (waits > RETURN_FOCUS_WAIT_LIMIT) {
                        logD("restoreFocusAfterReturn: RV not ready within limit, onFailed (pos=$position)")
                        endReturnRestorePolling("notReadyLimit")
                        onFailed()
                        return
                    }
                    recyclerView.postDelayed(this, retryDelayMs)
                    return
                }
                attempts++
                if (attempts > retryTimes) {
                    logD("restoreFocusAfterReturn: retries exhausted, onFailed (pos=$position)")
                    endReturnRestorePolling("retriesExhausted")
                    onFailed()
                    return
                }
                restoreCapturedFocusPosition()
                recyclerView.postDelayed(this, retryDelayMs)
            }
        }
        recyclerView.postDelayed(runnable, retryDelayMs)
        logD("restoreFocusAfterReturn: scheduled retry, pos=$position")
    }

    /**
     * 列表已 attach 但不可见，且焦点正由列表外的**可见**控件持有：典型为宿主 Fragment 被
     * overlay（详情页等）`hide()` 盖住——hide 不改生命周期，onResume 照常触发返回恢复，
     * `allowOutsideFocus` 的强制拉回会把焦点拽进不可见列表，可见页表现为「焦点丢失」且
     * 方向键全被隐藏列表吃掉（issue#131 动态页→详情页→播放器返回）。
     *
     * 列表仅因自身刷新/加载态暂不可见、且没有可见持焦者时不算被盖住，保持旧行为
     * （盲恢复后内容显示即落焦）。
     */
    private fun isHiddenBehindVisibleOwner(): Boolean {
        if (!recyclerView.isAttachedToWindow || recyclerView.isShown) return false
        val focused = recyclerView.rootView?.findFocus() ?: return false
        return !focused.isDescendantOf(recyclerView) && focused.isShown
    }

    /** 结束返回恢复轮询窗口，清空仲裁槽位。 */
    private fun endReturnRestorePolling(reason: String) {
        if (returnFocusPollingActive || activeReturnRestorePosition != null) {
            logD("endReturnRestorePolling: reason=$reason")
        }
        returnFocusPollingActive = false
        activeReturnRestorePosition = null
    }

    /**
     * 返回恢复窗口内的仲裁：恢复类焦点请求不与轮询并发派生。返回 true 表示已并入
     * 当前恢复意图、调用方无需再自行派生（其轮询以 hasFocusInList 真实落点为准）。
     */
    private fun mergeIntoActiveReturnRestore(position: Int, caller: String): Boolean {
        val active = activeReturnRestorePosition
        val merged = ReturnRestoreArbiter.shouldMergeRequest(
            activePosition = active,
            requestedPosition = position,
            hasPendingFocusForActive = active?.let(operator::hasPendingFocusFor) == true
        )
        if (!merged) {
            return false
        }
        if (active != null && active != position) {
            logD("$caller: merged into active restore pos=$active, skip pos=$position")
        } else {
            logD("$caller: identical restore pos=$position already pending, skip")
        }
        return true
    }

    private fun clearAnchorsAndPendingOps() {
        currentAnchor = null
        capturedAnchor = null
        pendingMoveAfterLoadMore = null
    }

    /**
     * 返回恢复窗口内 onDataChanged 的「数据已落地」处理：post 一帧（布局完成后）
     * 执行一次强制恢复，结果由轮询 tick 校验。去重保证一次数据变化只恢复一次。
     */
    private fun scheduleRestoreAfterDataLanded() {
        if (pendingRestoreAfterDataChange) {
            return
        }
        pendingRestoreAfterDataChange = true
        recyclerView.post {
            pendingRestoreAfterDataChange = false
            if (!returnFocusPollingActive) {
                return@post
            }
            val anchor = capturedAnchor ?: currentAnchor ?: return@post
            val position = resolveAnchorPosition(anchor)
            if (position == RecyclerView.NO_POSITION || !adapter.isFocusablePosition(position)) {
                logD("restoreAfterDataLanded: anchor invalid, wait for polling")
                return@post
            }
            // 数据重建后锚点（stableKey）可能解析到新位置，先同步仲裁槽位再单次恢复
            activeReturnRestorePosition = position
            logD("restoreAfterDataLanded: single restore pos=$position")
            restoreCapturedFocusPosition()
        }
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
        // touch mode 下框架不维护焦点（典型：触屏进播放器再返回），此时去 requestFocus
        // 必然失败。与 ensureValidFocus 的 touch mode 让路保持一致，直接返回 false。
        if (recyclerView.isInTouchMode) {
            logD("restoreCapturedAnchor: skip — touchMode (no focus to restore)")
            return false
        }
        // 焦点已落在列表外部一个可见、可聚焦的 View 上（典型场景：侧边栏功能按钮），
        // 说明用户正停留在侧边栏，不应把焦点拉回视频列表。
        // 注意：本"跳过外部焦点"仅适用于"用户主动聚焦侧边栏"的场景；"从播放器返回"时
        // 焦点若停在返回按钮等外部控件上恰恰是需要拉回的，且此时并不走
        // MainActivity.restoreFocusAfterOverlayPop（播放器是独立 Activity，返回走 finish()）。
        // 播放器返回应改用 [restoreFocusAfterReturn]（强制拉回 + 轮询校验）。
        val focused = recyclerView.rootView?.findFocus()
        if (focused != null &&
            !focused.isDescendantOf(recyclerView) &&
            focused.isAttachedToWindow &&
            focused.isShown &&
            focused.isFocusable
        ) {
            logD("restoreCapturedAnchor: skip — focus already on outside view ${describeView(focused)}")
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
        endReturnRestorePolling("release")
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
            val focusInsideList = focused != null && focused.isDescendantOf(recyclerView)
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
        if (shouldHandleDownAtEdge && pendingMoveAfterLoadMore != null &&
            SystemClock.uptimeMillis() - pendingMoveSinceUptimeMs > PENDING_LOAD_MORE_TIMEOUT_MS
        ) {
            logW("move: DOWN pending loadMore TIMEOUT, clearing and retrying loadMore")
            pendingMoveAfterLoadMore = null
        }
        if (shouldHandleDownAtEdge && canLoadMore() && pendingMoveAfterLoadMore == null) {
            logD("move: DOWN at bottom, triggering loadMore")
            pendingMoveSinceUptimeMs = SystemClock.uptimeMillis()
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
        if (focused != null && !focused.isDescendantOf(recyclerView) && reason != "move" && reason != "primary" && !allowOutsideFocus) {
            logD("focusPosition: BLOCKED reason=$reason focus outside RV on ${describeView(focused)}")
            return false
        }
        if (focused != null && isHiddenBehindVisibleOwner()) {
            logD("focusPosition: BLOCKED reason=$reason list hidden, visible owner=${describeView(focused)}")
            return false
        }
        logD("focusPosition: pos=$position offset=$offsetTop reason=$reason focused=${describeView(focused)}")
        return operator.focusPosition(position, offsetTop, reason) { focusedPosition ->
            // 焦点落定即关闭「外部焦点恢复窗口」：目标已获焦，无需再防外部抢焦
            restoreOutsideFocusUntilMs = 0L
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
        val focusIsOutsideList = focused != null && focused !== recyclerView && !focused.isDescendantOf(recyclerView)
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

        focusParking.applyParkOverrides()

        val handled = recyclerView.isFocused || recyclerView.requestFocus()
        logD("parkFocus: target=$targetPosition handled=$handled focused=${describeView(recyclerView.rootView?.findFocus())}")
    }

    private fun unparkFocusInRecyclerViewIfNeeded() {
        focusParking.clearParkOverrides()
        // 调用点均为「焦点即将/已经落到 child」，此时恢复高亮安全
        focusParking.restoreDefaultFocusHighlightIfNeeded()
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
}

/**
 * 返回恢复窗口（returnFocusPollingActive）内的仲裁决策，纯函数以便单元测试。
 *
 * 背景（返回焦点风暴）：多路恢复并发时各自派生 RVFocusOp 重试链、focusToken 互踩
 * STALE，约 1 秒内数十次 requestFocus 全失败。仲裁目标：窗口内同一时刻只有一个
 * 恢复意图、一次数据落地只恢复一次。
 */
internal object ReturnRestoreArbiter {

    /**
     * 外部恢复类请求（restoreCapturedFocusPosition / requestFocusPosition）是否
     * 并入当前恢复意图（不派生新重试链）：
     * - 无当前意图（窗口未开）：不并入，走正常路径；
     * - 目标与当前意图不一致：并入——两路并发正是风暴来源，以窗口意图为准；
     * - 目标一致且 RVFocusOp 已有同位置待聚焦意图：幂等跳过，避免重复排队。
     */
    fun shouldMergeRequest(
        activePosition: Int?,
        requestedPosition: Int,
        hasPendingFocusForActive: Boolean
    ): Boolean {
        val active = activePosition ?: return false
        if (active != requestedPosition) {
            return true
        }
        return hasPendingFocusForActive
    }

    /**
     * 恢复窗口内 onDataChanged 是否应中止恢复：用户主动刷新意味着锚点即将被清、
     * 用户意图已变（刷新后有专门的刷新焦点流程），恢复继续只会互踩；其余数据变化
     * 属于「数据已落地」，等待布局完成后单次恢复即可。
     */
    fun shouldAbortForDataChange(reason: TvDataChangeReason): Boolean {
        return reason == TvDataChangeReason.USER_REFRESH
    }
}
