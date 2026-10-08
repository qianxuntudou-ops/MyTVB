package com.mytvb.ui.adapter

import android.annotation.SuppressLint
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.mytvb.R
import com.mytvb.model.video.HistoryVideoModel
import com.mytvb.core.ui.base.BaseVideoAdapter
import com.mytvb.core.ui.base.BaseVideoViewHolder
import com.mytvb.core.ui.image.ImageLoader
import com.mytvb.core.common.format.NumberUtils
import com.mytvb.core.common.log.VideoCardPerfLogger
import com.mytvb.core.common.time.TimeUtils
import com.mytvb.core.ui.focus.VideoCardFocusHelper
import com.mytvb.core.ui.video.VideoCardViews
import com.mytvb.core.ui.video.VideoLightCardFactory
import com.mytvb.ui.dialog.VideoCardMenuDialog
import java.util.concurrent.atomic.AtomicInteger

class HistoryVideoAdapter(
    private val onItemClick: (HistoryVideoModel) -> Unit,
    onTopEdgeUp: (() -> Boolean)? = null,
    onLeftEdge: (() -> Boolean)? = null,
    onRightEdge: (() -> Boolean)? = null,
    onItemFocused: ((Int) -> Unit)? = null,
    onItemFocusedWithView: ((View, Int) -> Unit)? = null,
    onItemDpad: ((View, Int, KeyEvent) -> Boolean)? = null,
    onItemsChanged: (() -> Unit)? = null,
    private val onHistoryRecordDeleted: ((HistoryVideoModel) -> Unit)? = null,
    private val onItemDisliked: ((HistoryVideoModel) -> Unit)? = null,
    private val onUpDisliked: ((String) -> Unit)? = null
) : BaseVideoAdapter<HistoryVideoModel, HistoryVideoAdapter.ViewHolder>() {

    private val contentViewType = nextViewType.getAndIncrement()

    init {
        setHasStableIds(true)
        setShowLoadMore(false)
        this.onTopEdgeUp = onTopEdgeUp
        this.onLeftEdge = onLeftEdge
        this.onRightEdge = onRightEdge
        this.onItemFocused = onItemFocused
        this.onItemFocusedWithView = onItemFocusedWithView
        this.onItemDpad = onItemDpad
        this.onItemsChanged = onItemsChanged
    }

    override fun itemKey(item: HistoryVideoModel): String {
        return when {
            item.bvid.isNotBlank() -> "bvid:${item.bvid}"
            (item.history?.oid ?: 0L) > 0L -> "aid:${item.history?.oid}"
            else -> "title:${item.title}|cover:${item.cover}"
        }
    }

    override fun areContentsSame(old: HistoryVideoModel, new: HistoryVideoModel): Boolean = old == new

    override fun getContentItemViewType(position: Int): Int = contentViewType

    override fun setData(
        newItems: List<HistoryVideoModel>,
        onCommitted: (() -> Unit)?
    ) {
        val savedPosition = focusedPosition
        setDataDeduplicated(newItems) {
            if (savedPosition != RecyclerView.NO_POSITION && savedPosition < items.size) {
                focusedPosition = savedPosition
            }
            onCommitted?.invoke()
        }
    }

    fun focusedItemPosition(): Int = focusedPosition

    fun findPositionByKey(key: String): Int = findPositionByStableKey(key)

    override fun onCreateContentViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val views = VideoCardPerfLogger.measureInflate("HistoryVideoAdapter.light") {
            VideoLightCardFactory.create(parent, source = "HistoryVideoAdapter.light")
        }
        return ViewHolder(views)
    }

    override fun onBindContentViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position) ?: return
        // touch mode（触摸设备）下不给锚点位视觉状态：focusedPosition 会被 click 记录，
        // 返回后数据刷新 rebind 时按它恢复 selected 会重现孤儿灰底（cf6ac562 补漏）
        holder.bind(item, position == focusedPosition && !holder.itemView.isInTouchMode)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        focusedPosition = RecyclerView.NO_POSITION
        super.onDetachedFromRecyclerView(recyclerView)
    }

    override fun onViewRecycled(holder: ViewHolder) {
        applyFocusState(holder.itemView, false)
        super.onViewRecycled(holder)
    }

    private fun applyFocusState(view: View?, focused: Boolean) {
        view ?: return
        view.isSelected = focused
        view.findViewById<View>(R.id.textView)?.isSelected = focused
    }

    inner class ViewHolder(
        private val views: VideoCardViews
    ) : BaseVideoViewHolder(views.root) {

        private var currentItem: HistoryVideoModel? = null

        private val keyListener = View.OnKeyListener { view, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                when (event.action) {
                    KeyEvent.ACTION_DOWN -> {
                        if (event.repeatCount == 0) {
                            startLongPress()
                        }
                    }
                    KeyEvent.ACTION_UP -> {
                        cancelLongPress()
                    }
                }
                false
            } else {
                onItemDpad?.invoke(view, keyCode, event) == true
            }
        }

        override fun showCardMenu() {
            cancelLongPress()
            val item = currentItem ?: return
            val video = item.toVideoModel()
            VideoCardMenuDialog(
                context = itemView.context,
                video = video,
                onDislikeVideo = { onItemDisliked?.invoke(item) },
                onDislikeUp = { upName -> onUpDisliked?.invoke(upName) },
                onHistoryRecordDeleted = {
                    onHistoryRecordDeleted?.invoke(item)
                }
            ).show()
        }

        init {
            views.imageView.clipToOutline = true
            views.imageView.outlineProvider = VideoAdapter.VideoViewHolder.coverOutlineProviderFor(views.imageView.resources)
            views.root.setOnClickListener {
                if (longPressTriggered) {
                    longPressTriggered = false
                    return@setOnClickListener
                }
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemFocused?.invoke(position)
                    val previous = focusedPosition
                    focusedPosition = position
                    if (previous != RecyclerView.NO_POSITION && previous != position) {
                        notifyItemChangedWhenIdle(views.root, previous)
                    }
                    // 不在 click 里手动 applyFocusState(true)：触摸设备卡片无真焦点，
                    // selected 设上后没有失焦回调可清，进播放返回后灰底永久残留
                    // （22eb28fb 去掉 focusableInTouchMode 后暴露）。遥控 OK 点击时
                    // 焦点本就在卡片上，OnFocusChangeListener 已做同样的高亮。
                    onItemFocusedWithView?.invoke(views.root, position)
                }
                currentItem?.let(onItemClick)
            }
            @SuppressLint("ClickableViewAccessibility")
            views.root.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> startLongPress()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> cancelLongPress()
                }
                false
            }
            views.root.setOnFocusChangeListener { view, hasFocus ->
                val position = bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) {
                    return@setOnFocusChangeListener
                }
                if (hasFocus) {
                    onItemFocused?.invoke(position)
                    val previous = focusedPosition
                    focusedPosition = position
                    if (previous != RecyclerView.NO_POSITION && previous != position) {
                        notifyItemChangedWhenIdle(view, previous)
                    }
                    applyFocusState(view, true)
                    onItemFocusedWithView?.invoke(view, position)
                } else {
                    applyFocusState(views.root, false)
                }
            }
            VideoCardFocusHelper.bindSidebarExit(
                view = views.root,
                onTopEdgeUp = onTopEdgeUp,
                onLeftEdge = onLeftEdge,
                onRightEdge = onRightEdge,
                handleListDpadDown = false,
                chainedListener = keyListener
            )
            views.textLayer.setOwner(ownerText = "", showAvatar = false, show = false)
            views.textLayer.clearHistoryTrailing()
            views.coverMetaOverlay.bind(
                showPlayCount = false,
                showDanmakuCount = false,
                durationText = "",
                showChargeBadge = false,
                showInteractionBadge = false
            )
        }

        fun bind(item: HistoryVideoModel, isFocused: Boolean) {
            currentItem = item

            views.root.isSelected = isFocused
            views.textLayer.isSelected = isFocused
            views.textLayer.setTitle(item.title.ifBlank { item.showTitle }, lines = 2)
            val ownerName = item.displayAuthorName
            views.textLayer.setOwner(
                ownerText = ownerName,
                showAvatar = ownerName.isNotBlank()
            )

            val durationValue = item.duration.coerceAtLeast(0L)
            val progressValue = watchedProgress(item.progress, durationValue)
            if (durationValue > 0L) {
                views.progressBar.visibility = View.VISIBLE
                views.progressBar.max = durationValue.toInt()
                views.progressBar.progress = progressValue.toInt()
            } else {
                views.progressBar.visibility = View.GONE
            }

            val durationText = when {
                item.history?.business == "live" && item.badge.isNotBlank() -> item.badge
                isWatchedComplete(progressValue, durationValue) -> views.root.context.getString(R.string.adapter_watched_complete)
                durationValue > 0L -> "${NumberUtils.formatDuration(progressValue)}/${NumberUtils.formatDuration(durationValue)}"
                item.tagName.isNotBlank() -> item.tagName
                else -> ""
            }
            views.textLayer.setHistoryTrailing(
                timeText = TimeUtils.formatHistoryViewTime(views.root.context, item.viewAt),
                deviceDrawableRes = HistoryDeviceIcon.resolve(item.history?.dt ?: 0)?.drawableRes ?: 0
            )
            views.coverMetaOverlay.bind(
                showPlayCount = false,
                showDanmakuCount = false,
                durationText = durationText,
                showChargeBadge = item.isChargingExclusive,
                showInteractionBadge = item.isSteinsGate
            )

            ImageLoader.loadVideoCover(
                imageView = views.imageView,
                url = item.cover.ifBlank { item.covers?.firstOrNull().orEmpty() },
                deferUntilPreDraw = true
            )
        }

        private fun notifyItemChangedWhenIdle(anchor: View, position: Int) {
            anchor.post {
                if (position != RecyclerView.NO_POSITION && position < itemCount) {
                    notifyItemChanged(position)
                }
            }
        }

    }

    private companion object {
        private val nextViewType = AtomicInteger(0x580100)

        fun watchedProgress(rawProgress: Long, duration: Long): Long {
            if (duration <= 0L) return 0L
            return if (rawProgress < 0L) {
                duration
            } else {
                rawProgress.coerceAtMost(duration)
            }
        }

        fun isWatchedComplete(progress: Long, duration: Long): Boolean {
            // 对齐官方"播满才算看完"：progress 到达 duration 才显示已看完，
            // 提前 3 秒标记会与官方历史记录（还差 1 秒时官方显示未看完）不一致
            return duration > 3L && progress >= duration
        }
    }
}
