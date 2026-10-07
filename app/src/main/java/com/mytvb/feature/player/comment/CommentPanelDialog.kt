package com.mytvb.feature.player.comment

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDialog
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mytvb.R
import com.mytvb.core.common.format.NumberUtils
import com.mytvb.core.common.log.AppLog
import com.mytvb.databinding.DialogCommentPanelBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 视频评论面板：贴屏幕右侧的竖版弹窗（对齐合集选集弹窗形态）。
 * 根评论列表与楼中楼两个 RecyclerView 复用同一 Adapter 与 RecycledViewPool，
 * 排序 tab（热门/最新）焦点联动切换；BACK 分层返回：
 * 图片查看器 → 楼中楼回根列表（聚焦原评论）→ 关面板。
 *
 * 加载语义对齐 blbl.cat3399.feature.video.comment.VideoCommentsPanelController
 * （token 竞态防护、endReached、fallbackTotalCount）；触底加载挂三处：
 * onScrolled(dy>0)、item 焦点变化、数据落地补拉（焦点滚动不产生 dy 的场景）。
 */
internal class CommentPanelDialog(
    private val activity: AppCompatActivity,
    private val aid: Long,
    private val upMid: Long,
    private val commentCount: Long,
    private val onDismissed: () -> Unit,
) : AppCompatDialog(activity, R.style.DialogTheme) {

    companion object {
        private const val TAG = "CommentPanelDialog"
        private const val LOAD_MORE_THRESHOLD = 4
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val binding = DialogCommentPanelBinding.inflate(activity.layoutInflater)
    private val dataSource = VideoCommentDataSource(upMidProvider = { upMid })
    private val imageViewer = CommentImageViewerDialog(activity)

    private var commentSort: Int = VIDEO_COMMENT_SORT_HOT
    private var commentsFetchJob: Job? = null
    private var commentsFetchToken: Int = 0
    private val commentsState = VideoCommentRootPagingState()
    private val expandedRpids = HashSet<Long>()

    private var threadFetchJob: Job? = null
    private var threadFetchToken: Int = 0
    private val threadState = VideoCommentThreadPagingState()

    private val commentsAdapter: VideoCommentsAdapter
    private val threadAdapter: VideoCommentsAdapter

    init {
        setContentView(binding.root)
        setCanceledOnTouchOutside(true)

        // BACK 分层返回（对齐合集选集弹窗惯例）：
        // 图片查看器 → 楼中楼回根列表（聚焦原评论）→ 根列表回排序 tab → 关面板
        onBackPressedDispatcher.addCallback(
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    handleBackNavigation()
                }
            }
        )

        setOnDismissListener {
            scope.cancel()
            if (imageViewer.isViewing) imageViewer.dismiss()
            onDismissed()
        }

        // 楼中楼的 X 只退评论详情回根列表（同 BACK 一层），不关整个面板
        binding.buttonClose.setOnClickListener {
            if (imageViewer.isViewing) {
                imageViewer.dismiss()
            } else {
                showRoot()
                focusRoot(threadState.consumeReturnFocusRpid())
            }
        }

        val pool = RecyclerView.RecycledViewPool()
        binding.recyclerComments.setRecycledViewPool(pool)
        binding.recyclerCommentThread.setRecycledViewPool(pool)
        binding.recyclerComments.setHasFixedSize(true)
        binding.recyclerCommentThread.setHasFixedSize(true)
        binding.recyclerComments.itemAnimator = null
        binding.recyclerCommentThread.itemAnimator = null
        val dividerDecoration = CommentDividerItemDecoration(activity)
        binding.recyclerComments.addItemDecoration(dividerDecoration)
        // 楼中楼回复之间保留细分割线（官方），但小节行（粗线）与其上下项之间不叠线
        binding.recyclerCommentThread.addItemDecoration(
            CommentDividerItemDecoration(
                activity,
                sectionTopLine = true,
                skipAfter = { adapter, position ->
                    val a = adapter as? VideoCommentsAdapter
                    a != null && (a.isSectionAt(position) || a.isSectionAt(position + 1))
                },
            )
        )

        commentsAdapter = VideoCommentsAdapter(
            expandedRpids = expandedRpids,
            onClick = { item -> onRootCommentClick(item) },
            onTouchClick = { item -> onRootCommentTouchClick(item) },
            onPictureClick = { item, index -> openItemPictures(item, index) },
            onLongClick = { item -> onRootCommentLongClick(item) },
            onItemFocused = { maybeLoadMoreCommentsFromLayout() },
            onTopEdge = { focusSelectedSortChip() },
        )
        binding.recyclerComments.adapter = commentsAdapter
        binding.recyclerComments.layoutManager = LinearLayoutManager(activity)
        binding.recyclerComments.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (dy <= 0) return
                    if (isThreadVisible()) return
                    maybeLoadMoreCommentsFromLayout()
                }
            }
        )

        threadAdapter = VideoCommentsAdapter(
            expandedRpids = expandedRpids,
            // 楼中楼面板主评论默认全文展开，不出"展开/收起"
            expandMessageFully = true,
            onClick = { item -> onThreadCommentClick(item) },
            onPictureClick = { item, index -> openItemPictures(item, index) },
            onItemFocused = { maybeLoadMoreThreadFromLayout() },
            // 主评论按"上"把焦点交给关闭按钮（对齐根列表第一项按上回排序 tab 的惯例）
            onTopEdge = { binding.buttonClose.requestFocus() },
        )
        binding.recyclerCommentThread.adapter = threadAdapter
        binding.recyclerCommentThread.layoutManager = LinearLayoutManager(activity)
        binding.recyclerCommentThread.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (dy <= 0) return
                    if (!isThreadVisible()) return
                    maybeLoadMoreThreadFromLayout()
                }
            }
        )

        // 排序 tab：焦点只高亮不切换，OK(点击)激活后才切换列表
        binding.tabSortHot.setOnClickListener { applySort(VIDEO_COMMENT_SORT_HOT) }
        binding.tabSortNew.setOnClickListener { applySort(VIDEO_COMMENT_SORT_NEW) }
        updateSortUi()
        updateTitle(commentCount)
    }

    fun showPanel() {
        if (aid <= 0L) {
            binding.tvHint.text = activity.getString(R.string.player_comment_no_aid)
            binding.tvHint.visibility = View.VISIBLE
        }
        window?.setGravity(Gravity.END or Gravity.CENTER_VERTICAL)
        // 右侧抽屉动画：开=右缘快速滑入，关=往右快速飞出
        window?.setWindowAnimations(R.style.CommentPanelWindowAnimation)
        show()
        // 宽度钳 92% 屏宽，高度贴满全屏（上下不留空隙）
        val dm = activity.resources.displayMetrics
        val width = minOf(
            activity.resources.getDimensionPixelSize(R.dimen.px740),
            (dm.widthPixels * 0.92f).toInt()
        )
        window?.setLayout(width, WindowManager.LayoutParams.MATCH_PARENT)
        if (aid > 0L) {
            reloadComments()
        }

        // 初始焦点：优先列表首项，空列表/加载失败落排序 tab；窗口焦点监听 + post 双兜底
        fun requestInitialFocus() {
            val child = binding.recyclerComments.getChildAt(0)
            when {
                child != null -> child.requestFocus()
                commentsState.items.isEmpty() -> focusSelectedSortChip()
                else -> binding.recyclerComments.requestFocus()
            }
        }
        window?.decorView?.viewTreeObserver?.addOnWindowFocusChangeListener(
            object : ViewTreeObserver.OnWindowFocusChangeListener {
                override fun onWindowFocusChanged(hasFocus: Boolean) {
                    if (hasFocus) {
                        window?.decorView?.viewTreeObserver
                            ?.removeOnWindowFocusChangeListener(this)
                        requestInitialFocus()
                    }
                }
            }
        )
        binding.recyclerComments.post {
            if (isShowing) requestInitialFocus()
        }
    }

    private fun isThreadVisible(): Boolean = binding.recyclerCommentThread.visibility == View.VISIBLE

    private fun handleBackNavigation() {
        when {
            imageViewer.isViewing -> imageViewer.dismiss()
            isThreadVisible() -> {
                showRoot()
                focusRoot(threadState.consumeReturnFocusRpid())
            }
            isFocusInsideCommentList() -> focusSelectedSortChip()
            else -> this@CommentPanelDialog.cancel()
        }
    }

    /** 焦点是否在任一评论列表（根列表/楼中楼）内。 */
    private fun isFocusInsideCommentList(): Boolean {
        var v: View? = window?.currentFocus ?: return false
        while (v != null) {
            if (v === binding.recyclerComments || v === binding.recyclerCommentThread) return true
            v = v.parent as? View
        }
        return false
    }

    private fun showRoot() {
        binding.recyclerComments.visibility = View.VISIBLE
        binding.recyclerCommentThread.visibility = View.GONE
        binding.sortRow.visibility = View.VISIBLE
        binding.buttonClose.visibility = View.GONE
        binding.topTitle.text = activity.getString(R.string.player_comment_title)
        threadState.clearRoot()
        commentsAdapter.invalidateSizing()
    }

    private fun openThread(rootRpid: Long) {
        val safeRoot = rootRpid.takeIf { it > 0L } ?: return
        threadState.open(safeRoot)
        binding.recyclerComments.visibility = View.GONE
        binding.recyclerCommentThread.visibility = View.VISIBLE
        binding.sortRow.visibility = View.GONE
        binding.buttonClose.visibility = View.VISIBLE
        binding.topTitle.text = activity.getString(R.string.player_comment_thread_title)
        threadAdapter.invalidateSizing()
        reloadThread()
        focusThread()
    }

    private fun focusRoot(targetRpid: Long? = null) {
        val safeRpid = targetRpid?.takeIf { it > 0L }
        binding.recyclerComments.post {
            if (!isShowing) return@post
            if (safeRpid != null && focusCommentInRootList(rpid = safeRpid)) {
                return@post
            }
            val child = binding.recyclerComments.getChildAt(0)
            if (child != null) {
                child.requestFocus()
                return@post
            }
            if (commentsState.items.isEmpty()) {
                focusSelectedSortChip()
            } else {
                binding.recyclerComments.requestFocus()
            }
        }
    }

    private fun focusThread() {
        binding.recyclerCommentThread.post {
            if (!isShowing) return@post
            val child = binding.recyclerCommentThread.getChildAt(0)
            (child ?: binding.recyclerCommentThread).requestFocus()
        }
    }

    private fun focusSelectedSortChip() {
        val target =
            when (commentSort) {
                VIDEO_COMMENT_SORT_NEW -> binding.tabSortNew
                else -> binding.tabSortHot
            }
        target.requestFocus()
    }

    private fun focusCommentInRootList(rpid: Long): Boolean {
        val targetPos = commentsState.items.indexOfFirst { it.rpid == rpid }
        if (targetPos !in commentsState.items.indices) return false

        val direct = binding.recyclerComments.findViewHolderForAdapterPosition(targetPos)?.itemView
        if (direct != null) {
            direct.requestFocus()
            return true
        }

        binding.recyclerComments.scrollToPosition(targetPos)
        binding.recyclerComments.post {
            binding.recyclerComments.findViewHolderForAdapterPosition(targetPos)?.itemView?.requestFocus()
        }
        return true
    }

    // ---------- 点击分流（对齐 blbl：焦点 OK 与触摸行为不同） ----------

    private fun onRootCommentClick(item: VideoCommentItem) {
        if (isThreadVisible()) return

        val hasPictures = item.pictures.isNotEmpty() || item.noteCvid > 0L
        if (hasPictures) {
            openItemPictures(item, startIndex = 0)
            return
        }

        if (item.replyCount <= 0) {
            Toast.makeText(activity, activity.getString(R.string.player_comment_thread_empty), Toast.LENGTH_SHORT).show()
            return
        }
        openThread(rootRpid = item.rpid)
    }

    private fun onRootCommentTouchClick(item: VideoCommentItem) {
        // 触摸单击正文不再进楼中楼：链接文案统一为"长按查看全部"，进楼统一走长按
        // （onRootCommentLongClick），单击保留展开/收起等既有分流
    }

    private fun onRootCommentLongClick(item: VideoCommentItem): Boolean {
        if (isThreadVisible()) return false
        // 长按进楼中楼：与"长按查看全部 N 条回复"文案一致，不再区分是否带图
        if (item.replyCount <= 0) return false
        openThread(rootRpid = item.rpid)
        return true
    }

    private fun onThreadCommentClick(item: VideoCommentItem) {
        if (!isThreadVisible()) return
        val hasPictures = item.pictures.isNotEmpty() || item.noteCvid > 0L
        if (!hasPictures) return
        openItemPictures(item, startIndex = 0)
    }

    private fun openItemPictures(item: VideoCommentItem, startIndex: Int) {
        if (item.pictures.isNotEmpty()) {
            imageViewer.open(pictures = item.pictures, startIndex = startIndex)
            return
        }
        Toast.makeText(activity, activity.getString(R.string.player_comment_note_image_loading), Toast.LENGTH_SHORT).show()
        CommentNoteImageRepository.load(item.noteCvid) { images ->
            if (!isShowing) return@load
            if (images.isEmpty()) return@load
            imageViewer.open(
                pictures = images.map { VideoCommentPicture(url = it.url, width = it.width, height = it.height) },
                startIndex = startIndex,
            )
        }
    }

    // ---------- 排序 ----------

    private fun applySort(sort: Int) {
        if (commentSort == sort) return
        commentSort = sort
        updateSortUi()
        if (!isThreadVisible()) {
            reloadComments()
        }
    }

    private fun updateSortUi() {
        val selected = ContextCompat.getColor(activity, R.color.textColor)
        val unselected = ContextCompat.getColor(activity, R.color.subTextColor)

        val hotSelected = commentSort == VIDEO_COMMENT_SORT_HOT
        binding.tabSortHot.isSelected = hotSelected
        binding.tabSortNew.isSelected = !hotSelected
        binding.tabSortHot.setTextColor(if (hotSelected) selected else unselected)
        binding.tabSortNew.setTextColor(if (hotSelected) unselected else selected)
    }

    private fun updateTitle(totalCount: Long) {
        binding.topTitle.text =
            if (totalCount > 0L) {
                activity.getString(
                    R.string.player_comment_title_with_count_format,
                    NumberUtils.formatCount(activity, totalCount)
                )
            } else {
                activity.getString(R.string.player_comment_title)
            }
    }

    // ---------- 根评论加载 ----------

    private fun reloadComments() {
        if (aid <= 0L) return

        commentsFetchJob?.cancel()
        commentsFetchJob = null
        val token = ++commentsFetchToken
        commentsState.beginReload()
        commentsAdapter.setItems(emptyList())

        binding.tvHint.text = activity.getString(R.string.player_comment_loading)
        binding.tvHint.visibility = View.VISIBLE

        commentsFetchJob =
            scope.launch {
                try {
                    val pageData = dataSource.loadRootPage(oid = aid, sort = commentSort, cursorOffset = null)
                    if (token != commentsFetchToken) return@launch

                    commentsState.replace(pageData)
                    updateTitle(pageData.totalCount.coerceAtLeast(0).toLong())

                    commentsAdapter.setItems(commentsState.items)
                    if (commentsState.items.isEmpty()) {
                        binding.tvHint.text = activity.getString(R.string.player_comment_empty)
                        binding.tvHint.visibility = View.VISIBLE
                    } else {
                        binding.tvHint.visibility = View.GONE
                    }
                    checkCommentsFillScreen()
                } catch (t: Throwable) {
                    if (t is CancellationException) return@launch
                    AppLog.w(TAG, "reloadComments failed aid=$aid sort=$commentSort", t)
                    showCommentLoadError(t)
                } finally {
                    if (token == commentsFetchToken) commentsFetchJob = null
                }
            }
    }

    private fun loadMoreComments() {
        if (commentsFetchJob?.isActive == true || commentsState.endReached) return
        val cursorOffset = commentsState.nextCursorOffset ?: return
        val token = ++commentsFetchToken

        commentsFetchJob =
            scope.launch {
                try {
                    val pageData = dataSource.loadRootPage(
                        oid = aid,
                        sort = commentSort,
                        cursorOffset = cursorOffset,
                        fallbackTotalCount = commentsState.totalCount,
                    )
                    if (token != commentsFetchToken) return@launch

                    if (!commentsState.append(pageData)) {
                        return@launch
                    }
                    commentsAdapter.appendItems(pageData.items)
                    checkCommentsFillScreen()
                } catch (t: Throwable) {
                    if (t is CancellationException) return@launch
                    AppLog.w(TAG, "loadMoreComments failed aid=$aid sort=$commentSort cursor=$cursorOffset", t)
                    showCommentLoadError(t)
                } finally {
                    if (token == commentsFetchToken) commentsFetchJob = null
                }
            }
    }

    private fun maybeLoadMoreCommentsFromLayout() {
        if (isThreadVisible()) return
        if (commentsFetchJob?.isActive == true || commentsState.endReached) return
        val lm = binding.recyclerComments.layoutManager as? LinearLayoutManager ?: return
        val lastVisible = lm.findLastVisibleItemPosition()
        val total = commentsAdapter.itemCount
        if (total <= 0 || lastVisible < 0) return
        if (total - lastVisible - 1 <= LOAD_MORE_THRESHOLD) {
            loadMoreComments()
        }
    }

    /** 首屏数据不足一屏时继续补拉（onScrolled 不触发的场景）。 */
    private fun checkCommentsFillScreen() {
        binding.recyclerComments.post { maybeLoadMoreCommentsFromLayout() }
    }

    // ---------- 楼中楼加载 ----------

    /** 楼中楼展示列表：主评论后插"相关回复共N条"小节行（对齐官方评论详情页结构）。 */
    private fun buildThreadDisplayItems(): List<VideoCommentItem> {
        val source = threadState.items
        if (source.isEmpty() || threadState.totalCount == 0) return source
        val title =
            if (threadState.totalCount > 0) {
                activity.getString(R.string.player_comment_thread_section_format, threadState.totalCount)
            } else {
                activity.getString(R.string.player_comment_thread_section)
            }
        val section = VideoCommentItem(
            key = "thread_section",
            rpid = 0L,
            oid = aid,
            type = 0,
            mid = 0L,
            userName = "",
            avatarUrl = null,
            message = "",
            ctimeSec = 0L,
            likeCount = 0L,
            replyCount = 0,
            threadSectionTitle = title,
        )
        val hasRoot = source.firstOrNull()?.key?.startsWith("thread_root:") == true
        return if (hasRoot) {
            source.toMutableList().apply { add(1, section) }
        } else {
            listOf(section) + source
        }
    }

    private fun reloadThread() {
        if (aid <= 0L) return
        val root = threadState.rootRpid.takeIf { it > 0L } ?: return

        threadFetchJob?.cancel()
        threadFetchJob = null
        val token = ++threadFetchToken
        threadState.beginReload()

        binding.tvHint.text = activity.getString(R.string.player_comment_loading)
        binding.tvHint.visibility = View.VISIBLE
        threadAdapter.setItems(emptyList())

        threadFetchJob =
            scope.launch {
                try {
                    val pageData = dataSource.loadThreadPage(oid = aid, rootRpid = root, page = 1)
                    if (token != threadFetchToken) return@launch
                    if (threadState.rootRpid != root) return@launch

                    threadState.replace(pageData)
                    threadAdapter.setItems(buildThreadDisplayItems())

                    if (threadState.items.isEmpty()) {
                        binding.tvHint.text = activity.getString(R.string.player_comment_thread_empty)
                        binding.tvHint.visibility = View.VISIBLE
                    } else {
                        binding.tvHint.visibility = View.GONE
                    }
                    checkThreadFillScreen()
                } catch (t: Throwable) {
                    if (t is CancellationException) return@launch
                    AppLog.w(TAG, "reloadThread failed aid=$aid root=$root", t)
                    showCommentLoadError(t)
                } finally {
                    if (token == threadFetchToken) threadFetchJob = null
                }
            }
    }

    private fun loadMoreThread() {
        if (aid <= 0L) return
        val root = threadState.rootRpid.takeIf { it > 0L } ?: return
        if (threadFetchJob?.isActive == true || threadState.endReached) return

        val nextPage = threadState.page + 1
        val token = ++threadFetchToken

        threadFetchJob =
            scope.launch {
                try {
                    val pageData = dataSource.loadThreadPage(
                        oid = aid,
                        rootRpid = root,
                        page = nextPage,
                        fallbackTotalCount = threadState.totalCount,
                    )
                    if (token != threadFetchToken) return@launch
                    if (threadState.rootRpid != root) return@launch

                    if (!threadState.append(nextPage = nextPage, replies = pageData.replies)) {
                        return@launch
                    }
                    threadAdapter.appendItems(pageData.replies)
                    checkThreadFillScreen()
                } catch (t: Throwable) {
                    if (t is CancellationException) return@launch
                    AppLog.w(TAG, "loadMoreThread failed aid=$aid root=$root page=$nextPage", t)
                    showCommentLoadError(t)
                } finally {
                    if (token == threadFetchToken) threadFetchJob = null
                }
            }
    }

    private fun maybeLoadMoreThreadFromLayout() {
        if (!isThreadVisible()) return
        if (threadFetchJob?.isActive == true || threadState.endReached) return
        val lm = binding.recyclerCommentThread.layoutManager as? LinearLayoutManager ?: return
        val lastVisible = lm.findLastVisibleItemPosition()
        val total = threadAdapter.itemCount
        if (total <= 0 || lastVisible < 0) return
        if (total - lastVisible - 1 <= LOAD_MORE_THRESHOLD) {
            loadMoreThread()
        }
    }

    private fun checkThreadFillScreen() {
        binding.recyclerCommentThread.post { maybeLoadMoreThreadFromLayout() }
    }

    // ---------- 错误 ----------

    private fun showCommentLoadError(t: Throwable) {
        Toast.makeText(activity, t.message ?: activity.getString(R.string.player_comment_load_failed), Toast.LENGTH_SHORT).show()
        val rootEmpty = commentsState.items.isEmpty() && !isThreadVisible()
        val threadEmpty = isThreadVisible() && threadState.items.isEmpty()
        if (rootEmpty || threadEmpty) {
            binding.tvHint.text = activity.getString(R.string.player_comment_load_failed)
            binding.tvHint.visibility = View.VISIBLE
        }
    }
}

/** 评论列表分隔线：每条评论底部一条细线（最后一项不画），撑满弹窗宽度。
 *  楼中楼面板（[sectionTopLine]=true）另在小节行顶部画撑满粗线——布局里的线 View
 *  会被列表 padding 缩进（负 margin + clipChildren 也不可靠），Decoration 画必贴边。 */
private class CommentDividerItemDecoration(
    context: Context,
    // 返回 true 表示该 position 与下一项之间不画线（楼中楼小节行粗线区域不叠细线）
    private val skipAfter: (RecyclerView.Adapter<*>, Int) -> Boolean = { _, _ -> false },
    private val sectionTopLine: Boolean = false,
) : RecyclerView.ItemDecoration() {

    private val paint = Paint().apply { color = 0xFF6B6B6B.toInt() }
    private val heightPx = context.resources.getDimensionPixelSize(R.dimen.px1)
    private val sectionPaint = Paint().apply { color = 0x66666666.toInt() }
    private val sectionLineHeightPx = context.resources.getDimensionPixelSize(R.dimen.px8)
    private val sectionLineTopOffsetPx = context.resources.getDimensionPixelSize(R.dimen.px20)

    override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        if (parent.layoutManager !is LinearLayoutManager) return
        val adapter = parent.adapter ?: return
        // 撑满弹窗：无视 RecyclerView 自身的 paddingStart/End（那是卡片内容边距，clipToPadding=false）
        val left = 0f
        val right = parent.width.toFloat()
        val sectionAdapter = adapter as? VideoCommentsAdapter
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            val position = parent.getChildAdapterPosition(child)
            if (position == RecyclerView.NO_POSITION) continue
            if (sectionTopLine && sectionAdapter?.isSectionAt(position) == true) {
                val lineTop = (child.top + sectionLineTopOffsetPx).toFloat()
                c.drawRect(left, lineTop, right, lineTop + sectionLineHeightPx, sectionPaint)
            }
            if (position == adapter.itemCount - 1) continue
            if (skipAfter(adapter, position)) continue
            val top = child.bottom.toFloat()
            c.drawRect(left, top, right, top + heightPx, paint)
        }
    }
}
