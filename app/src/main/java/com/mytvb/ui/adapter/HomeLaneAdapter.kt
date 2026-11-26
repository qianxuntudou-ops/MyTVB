package com.mytvb.ui.adapter

import android.content.Context
import android.content.ContextWrapper
import android.view.LayoutInflater
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.content.res.ResourcesCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mytvb.R
import com.mytvb.core.common.log.AppLog
import com.mytvb.databinding.CellLaneSeriesTimeLineBinding
import com.mytvb.databinding.CellLaneScrollableBinding
import com.mytvb.model.lane.HomeLaneSection
import com.mytvb.model.lane.LaneItemModel
import com.mytvb.model.series.timeline.SeriesTimeLineModel
import com.mytvb.ui.activity.MainActivity
import com.mytvb.core.ui.base.BaseAdapter
import com.mytvb.core.ui.base.adaptiveSpanCount
import java.util.concurrent.atomic.AtomicInteger

class HomeLaneAdapter(
    private val onSeriesClick: (LaneItemModel) -> Unit,
    private val onMoreClick: (Int, String, String) -> Unit,
    private val onTimelineClick: (SeriesTimeLineModel) -> Unit,
    private val defaultMoreSeasonType: Int? = null,
    private val onTopEdgeUp: () -> Boolean = { false },
    private val onFollowSectionClick: ((Int) -> Unit)? = null
) : BaseAdapter<HomeLaneSection, RecyclerView.ViewHolder>() {

    private var outerRecyclerView: RecyclerView? = null
    private val viewTypeBase = nextViewTypeBase.getAndAdd(VIEW_TYPE_STRIDE)

    private val outerDetachListener = object : RecyclerView.OnChildAttachStateChangeListener {
        override fun onChildViewAttachedToWindow(view: View) = Unit

        override fun onChildViewDetachedFromWindow(detached: View) {
            val focused = detached.rootView.findFocus() ?: return
            if (!isDescendantOf(focused, detached)) return
            val rv = outerRecyclerView ?: return
            restoreFocusToVisibleLane(rv)
        }
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        outerRecyclerView = recyclerView
        recyclerView.addOnChildAttachStateChangeListener(outerDetachListener)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        recyclerView.removeOnChildAttachStateChangeListener(outerDetachListener)
        outerRecyclerView = null
    }

    companion object {
        private const val TAG = "HomeLaneFocus"
        private const val VIEW_TYPE_SCROLLABLE_OFFSET = 0
        private const val VIEW_TYPE_TIMELINE_OFFSET = 1
        private const val VIEW_TYPE_STRIDE = 4
        private val nextViewTypeBase = AtomicInteger(0x5D0100)
    }

    fun addData(newItems: List<HomeLaneSection>): Boolean {
        val existingKeys = items.map { it.deduplicateKey() }.toMutableSet()
        val deduped = newItems.filter { existingKeys.add(it.deduplicateKey()) }
        if (deduped.isEmpty()) return false
        addAll(deduped)
        return true
    }

    private fun HomeLaneSection.deduplicateKey(): String {
        if (timelineDays.isNotEmpty()) return "timeline"
        if (style == "follow") return "follow"
        return "${title.trim()}#${style}#${moreSeasonType}"
    }

    fun insertTimelineSection(section: HomeLaneSection): Boolean {
        if (items.isEmpty()) {
            return false
        }
        val existingIndex = items.indexOfFirst { it.timelineDays.isNotEmpty() }
        if (existingIndex >= 0) {
            items[existingIndex] = section
            notifyItemChanged(existingIndex)
            return true
        }
        val insertIndex = minOf(1, items.size)
        items.add(insertIndex, section)
        notifyItemInserted(insertIndex)
        return true
    }

    override fun getItemViewType(position: Int): Int {
        if (position == items.size && showLoadMore) {
            return LOAD_MORE_TYPE
        }
        return if (items[position].timelineDays.isNotEmpty()) {
            viewTypeBase + VIEW_TYPE_TIMELINE_OFFSET
        } else {
            viewTypeBase + VIEW_TYPE_SCROLLABLE_OFFSET
        }
    }

    override fun onCreateContentViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType - viewTypeBase) {
            VIEW_TYPE_TIMELINE_OFFSET -> {
                lateinit var holder: TimelineViewHolder
                val binding = CellLaneSeriesTimeLineBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
                holder = TimelineViewHolder(
                    binding = binding,
                    onTimelineClick = onTimelineClick,
                    onTopEdgeUp = onTopEdgeUp,
                    onBottomEdgeDown = { focusNextSectionFrom(holder) }
                )
                holder
            }

            else -> {
                lateinit var holder: ScrollableViewHolder
                val binding = CellLaneScrollableBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
                holder = ScrollableViewHolder(
                    binding,
                    onSeriesClick,
                    onMoreClick,
                    defaultMoreSeasonType,
                    onTopEdgeUp,
                    onFollowSectionClick,
                    onBottomEdgeDown = { focusNextSectionFrom(holder) }
                )
                holder
            }
        }
    }

    override fun onBindContentViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        when (holder) {
            is ScrollableViewHolder -> holder.bind(item)
            is TimelineViewHolder -> holder.bind(item)
        }
    }

    private fun focusNextSectionFrom(currentHolder: RecyclerView.ViewHolder): Boolean {
        val outerRV = outerRecyclerView ?: currentHolder.itemView.findParentRecyclerView() ?: return true
        val focused = outerRV.rootView.findFocus()
        if (
            focused == null ||
            !currentHolder.itemView.isAttachedToWindow ||
            !currentHolder.itemView.isShown ||
            !isDescendantOf(focused, currentHolder.itemView)
        ) {
            AppLog.d(
                TAG,
                "focusNextSection ignored: holder=${currentHolder.javaClass.simpleName} " +
                    "attached=${currentHolder.itemView.isAttachedToWindow} shown=${currentHolder.itemView.isShown} " +
                    "focus=${describeCurrentFocus(outerRV)}"
            )
            return false
        }
        val currentPosition = currentHolder.bindingAdapterPosition
        if (currentPosition == RecyclerView.NO_POSITION) {
            return restoreFocusToVisibleLane(outerRV)
        }
        val targetPosition = (currentPosition + 1 until items.size).firstOrNull() ?: return true
        val preferredChildPosition = when (currentHolder) {
            is ScrollableViewHolder -> currentHolder.focusedChildPosition()
            is TimelineViewHolder -> currentHolder.focusedChildPosition()
            else -> RecyclerView.NO_POSITION
        }
        AppLog.d(
            TAG,
            "focusNextSection: section=$currentPosition(${sectionLabel(currentPosition)}) → " +
                "$targetPosition(${sectionLabel(targetPosition)}) childCol=$preferredChildPosition " +
                "visible=${visibleRange(outerRV)} focus=${describeCurrentFocus(outerRV)}"
        )

        val immediate = requestSectionFocusAt(outerRV, targetPosition, preferredChildPosition)
        val immediateLanded = isFocusOnSection(outerRV, targetPosition)
        AppLog.d(
            TAG,
            "focusNextSection immediate: requested=$immediate landed=$immediateLanded " +
                "targetHolder=${describeSectionHolder(outerRV, targetPosition)} focus=${describeCurrentFocus(outerRV)}"
        )
        if (immediateLanded) {
            return true
        }

        scrollSectionIntoView(outerRV, targetPosition)
        postSectionFocus(outerRV, targetPosition, preferredChildPosition, retryCount = 0)
        return true
    }

    private fun postSectionFocus(
        outerRV: RecyclerView,
        targetPosition: Int,
        preferredChildPosition: Int,
        retryCount: Int
    ) {
        outerRV.post {
            AppLog.d(
                TAG,
                "focusNextSection post start: target=$targetPosition retry=$retryCount " +
                    "visible=${visibleRange(outerRV)} targetHolder=${describeSectionHolder(outerRV, targetPosition)} " +
                    "focus=${describeCurrentFocus(outerRV)}"
            )
            val requested = requestSectionFocusAt(outerRV, targetPosition, preferredChildPosition)
            outerRV.postDelayed({
                val focusLanded = isFocusOnSection(outerRV, targetPosition)
                AppLog.d(
                    TAG,
                    "focusNextSection post result: target=$targetPosition retry=$retryCount " +
                        "requested=$requested landed=$focusLanded visible=${visibleRange(outerRV)} " +
                        "targetHolder=${describeSectionHolder(outerRV, targetPosition)} focus=${describeCurrentFocus(outerRV)}"
                )
                if (!focusLanded && retryCount < 5) {
                    AppLog.d(TAG, "focusNextSection retry: section→$targetPosition attempt=${retryCount + 1}")
                    scrollSectionIntoView(outerRV, targetPosition)
                    postSectionFocus(outerRV, targetPosition, preferredChildPosition, retryCount + 1)
                }
            }, 100L)
        }
    }

    private fun requestSectionFocusAt(
        recyclerView: RecyclerView,
        position: Int,
        preferredChildPosition: Int
    ): Boolean {
        AppLog.d(
            TAG,
            "requestSectionFocusAt: position=$position(${sectionLabel(position)}) preferredChild=$preferredChildPosition " +
                "holder=${describeSectionHolder(recyclerView, position)} focusBefore=${describeCurrentFocus(recyclerView)}"
        )
        val focusedChild = requestChildFocusAt(recyclerView, position, preferredChildPosition)
        AppLog.d(
            TAG,
            "requestSectionFocusAt child: result=$focusedChild landed=${isFocusOnSection(recyclerView, position)} " +
                "focus=${describeCurrentFocus(recyclerView)}"
        )
        if (focusedChild && isFocusOnSection(recyclerView, position)) {
            return true
        }
        val focusedHeader = requestHeaderFocusAt(recyclerView, position)
        AppLog.d(
            TAG,
            "requestSectionFocusAt header: result=$focusedHeader landed=${isFocusOnSection(recyclerView, position)} " +
                "focus=${describeCurrentFocus(recyclerView)}"
        )
        if (focusedHeader && isFocusOnSection(recyclerView, position)) {
            return true
        }
        val focusedPrimary = requestPrimaryFocusAt(recyclerView, position)
        AppLog.d(
            TAG,
            "requestSectionFocusAt primary: result=$focusedPrimary landed=${isFocusOnSection(recyclerView, position)} " +
                "focus=${describeCurrentFocus(recyclerView)}"
        )
        return focusedPrimary && isFocusOnSection(recyclerView, position)
    }

    private fun scrollSectionIntoView(recyclerView: RecyclerView, position: Int) {
        AppLog.d(
            TAG,
            "scrollSectionIntoView: position=$position beforeVisible=${visibleRange(recyclerView)} " +
                "holder=${describeSectionHolder(recyclerView, position)}"
        )
        val lm = recyclerView.layoutManager as? LinearLayoutManager
        if (lm != null) {
            lm.scrollToPositionWithOffset(position, 0)
        } else {
            recyclerView.scrollToPosition(position)
        }
    }

    private fun visibleRange(recyclerView: RecyclerView): String {
        val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return "unknown"
        return "${lm.findFirstVisibleItemPosition()}..${lm.findLastVisibleItemPosition()}"
    }

    private fun describeSectionHolder(recyclerView: RecyclerView, position: Int): String {
        val holder = recyclerView.findViewHolderForAdapterPosition(position)
            ?: return "null"
        return "${holder.javaClass.simpleName}(attached=${holder.itemView.isAttachedToWindow}, " +
            "shown=${holder.itemView.isShown}, hasFocus=${holder.itemView.hasFocus()})"
    }

    private fun describeCurrentFocus(recyclerView: RecyclerView): String {
        val focused = recyclerView.rootView.findFocus() ?: return "null"
        val outerItem = recyclerView.findContainingItemView(focused)
        val outerPosition = outerItem?.let { recyclerView.getChildAdapterPosition(it) }
            ?: RecyclerView.NO_POSITION
        val innerRecycler = focused.findParentRecyclerView()
        val innerItem = innerRecycler?.findContainingItemView(focused)
        val innerPosition = if (innerRecycler != null && innerRecycler !== recyclerView && innerItem != null) {
            innerRecycler.getChildAdapterPosition(innerItem)
        } else {
            RecyclerView.NO_POSITION
        }
        val idName = runCatching {
            if (focused.id != View.NO_ID) focused.resources.getResourceEntryName(focused.id) else "no_id"
        }.getOrDefault("${focused.id}")
        return "${focused.javaClass.simpleName}($idName outer=$outerPosition inner=$innerPosition)"
    }

    private fun sectionLabel(position: Int): String {
        val section = items.getOrNull(position) ?: return "missing"
        return when {
            section.style == "follow" -> section.title.ifBlank { "follow" }
            section.timelineDays.isNotEmpty() -> "timeline"
            else -> section.title.ifBlank { section.style.ifBlank { "untitled" } }
        }.take(24)
    }

    private fun isFocusOnSection(rv: RecyclerView, position: Int): Boolean {
        val focused = rv.rootView.findFocus() ?: return false
        val sectionView = rv.findViewHolderForAdapterPosition(position)?.itemView ?: return false
        if (!sectionView.isAttachedToWindow || !sectionView.isShown) {
            return false
        }
        return isDescendantOf(focused, sectionView) &&
            focused.isAttachedToWindow &&
            focused.isShown
    }

    private fun restoreFocusToVisibleLane(rv: RecyclerView): Boolean {
        val lm = rv.layoutManager as? LinearLayoutManager ?: return true
        val first = lm.findFirstVisibleItemPosition()
        val last = lm.findLastVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION) return true
        var pos = last
        while (pos >= first) {
            if (requestPrimaryFocusAt(rv, pos)) return true
            pos--
        }
        return true
    }

    private fun requestHeaderFocusAt(recyclerView: RecyclerView, position: Int): Boolean {
        val holder = recyclerView.findViewHolderForAdapterPosition(position)
            ?.takeIf { it.itemView.isAttachedToWindow && it.itemView.isShown }
            ?: return false
        return when (holder) {
            is ScrollableViewHolder -> holder.requestHeaderFocus()
            is TimelineViewHolder -> holder.requestHeaderFocus()
            else -> false
        }
    }

    private fun requestPrimaryFocusAt(recyclerView: RecyclerView, position: Int): Boolean {
        val holder = recyclerView.findViewHolderForAdapterPosition(position)
            ?.takeIf { it.itemView.isAttachedToWindow && it.itemView.isShown }
            ?: return false
        return when (holder) {
            is ScrollableViewHolder -> holder.requestPrimaryFocus()
            is TimelineViewHolder -> holder.requestPrimaryFocus()
            else -> false
        }
    }

    fun requestFirstCardFocus(outerRV: RecyclerView) {
        for (i in 0 until items.size) {
            val holder = outerRV.findViewHolderForAdapterPosition(i) ?: continue
            val focused = when (holder) {
                is ScrollableViewHolder -> holder.requestFirstCardFocusPublic()
                is TimelineViewHolder -> holder.requestFirstCardFocusPublic()
                else -> false
            }
            if (focused) return
        }
    }

    private fun requestChildFocusAt(
        recyclerView: RecyclerView,
        position: Int,
        preferredChildPosition: Int
    ): Boolean {
        if (preferredChildPosition == RecyclerView.NO_POSITION) {
            return false
        }
        val holder = recyclerView.findViewHolderForAdapterPosition(position)
            ?.takeIf { it.itemView.isAttachedToWindow && it.itemView.isShown }
            ?: return false
        return when (holder) {
            is ScrollableViewHolder -> holder.requestChildFocus(preferredChildPosition)
            is TimelineViewHolder -> holder.requestChildFocus(preferredChildPosition)
            else -> false
        }
    }

    class ScrollableViewHolder(
        private val binding: CellLaneScrollableBinding,
        onSeriesClick: (LaneItemModel) -> Unit,
        private val onMoreClick: (Int, String, String) -> Unit,
        private val defaultMoreSeasonType: Int?,
        private val onTopEdgeUp: () -> Boolean,
        private val onFollowSectionClick: ((Int) -> Unit)? = null,
        private val onBottomEdgeDown: () -> Boolean
    ) : RecyclerView.ViewHolder(binding.root) {

        private val adapter = LaneItemAdapter(
            onItemClick = onSeriesClick,
            onEdgeBottomDown = onBottomEdgeDown
        )
        private var moreSeasonType: Int? = null
        private var currentMoreUrl: String = ""
        private var currentSection: HomeLaneSection? = null

        init {
            binding.recyclerView.layoutManager =
                GridLayoutManager(binding.root.context, binding.root.resources.adaptiveSpanCount(base = 6, wide = 8))
            binding.recyclerView.adapter = adapter
            binding.recyclerView.itemAnimator = null
            binding.recyclerView.setHasFixedSize(true)
            val sectionHeaderKeyListener = View.OnKeyListener { view, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) {
                    return@OnKeyListener false
                }
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        bindingAdapterPosition == 0 && onTopEdgeUp()
                    }

                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        view.context.findMainActivity()?.focusLeftFunctionArea(view) == true
                    }

                    KeyEvent.KEYCODE_DPAD_DOWN,
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        requestFirstCardFocusPublic()
                    }

                    else -> false
                }
            }
            val openMore = View.OnClickListener {
                val section = currentSection
                if (section?.disableMore == true && section.style == "follow") {
                    val title = section.title
                    val type = if (title.contains("追剧")) 2 else 1
                    onFollowSectionClick?.invoke(type)
                } else {
                    moreSeasonType?.let { onMoreClick(it, currentMoreUrl, binding.topTitle.text.toString()) }
                }
            }
            binding.topTitle.setOnKeyListener(sectionHeaderKeyListener)
            binding.topTitle.setOnClickListener(openMore)
        }

        fun bind(item: HomeLaneSection) {
            currentSection = item
            val isFollow = item.style == "follow" && item.disableMore
            moreSeasonType = if (item.disableMore) {
                if (isFollow) defaultMoreSeasonType else null
            } else {
                item.moreSeasonType ?: defaultMoreSeasonType
            }
            currentMoreUrl = item.moreUrl
            binding.topTitle.text = item.title
            if (moreSeasonType != null || isFollow) {
                val arrow = ResourcesCompat.getDrawable(
                    binding.root.context.resources,
                    R.drawable.ic_arrow_right,
                    binding.root.context.theme
                )
                if (arrow != null) {
                    val size = binding.topTitle.textSize.toInt()
                    arrow.setBounds(0, 0, size, size)
                    binding.topTitle.setCompoundDrawablesRelative(null, null, arrow, null)
                }
                binding.topTitle.compoundDrawablePadding = binding.root.context.resources.getDimensionPixelSize(R.dimen.px10)
            } else {
                binding.topTitle.setCompoundDrawablesRelative(null, null, null, null)
            }
            adapter.setData(item.items)
        }

        fun requestPrimaryFocus(): Boolean {
            return binding.topTitle.requestFocus() || requestFirstCardFocusPublic()
        }

        fun requestHeaderFocus(): Boolean = binding.topTitle.requestFocus()

        fun focusedChildPosition(): Int {
            val focused = binding.recyclerView.rootView?.findFocus() ?: return RecyclerView.NO_POSITION
            val child = binding.recyclerView.findContainingItemView(focused) ?: return RecyclerView.NO_POSITION
            val pos = binding.recyclerView.getChildAdapterPosition(child)
            if (pos == RecyclerView.NO_POSITION) return RecyclerView.NO_POSITION
            val lm = binding.recyclerView.layoutManager as? GridLayoutManager ?: return pos
            return lm.spanSizeLookup.getSpanIndex(pos, lm.spanCount)
        }

        fun requestChildFocus(preferredColumn: Int): Boolean {
            if (adapter.itemCount <= 0 || preferredColumn == RecyclerView.NO_POSITION) {
                return false
            }
            val lm = binding.recyclerView.layoutManager as? GridLayoutManager
            val targetPosition = if (lm != null) {
                preferredColumn.coerceIn(0, lm.spanCount - 1).coerceAtMost(adapter.itemCount - 1)
            } else {
                preferredColumn.coerceIn(0, adapter.itemCount - 1)
            }
            val holder = binding.recyclerView.findViewHolderForAdapterPosition(targetPosition)
            if (holder is LaneItemAdapter.LaneItemViewHolder && holder.requestFocus()) {
                return true
            }
            binding.recyclerView.scrollToPosition(targetPosition)
            binding.recyclerView.post {
                (binding.recyclerView.findViewHolderForAdapterPosition(targetPosition) as? LaneItemAdapter.LaneItemViewHolder)
                    ?.requestFocus()
            }
            return true
        }

        fun requestFirstCardFocusPublic(): Boolean {
            if (adapter.requestFirstItemFocus(binding.recyclerView)) {
                return true
            }
            if (adapter.itemCount <= 0) {
                return false
            }
            binding.recyclerView.scrollToPosition(0)
            binding.recyclerView.post {
                adapter.requestFirstItemFocus(binding.recyclerView)
            }
            return true
        }
    }

    class TimelineViewHolder(
        private val binding: CellLaneSeriesTimeLineBinding,
        onTimelineClick: (SeriesTimeLineModel) -> Unit,
        private val onTopEdgeUp: () -> Boolean,
        private val onBottomEdgeDown: () -> Boolean
    ) : RecyclerView.ViewHolder(binding.root) {

        private val adapter = SeriesTimelineAdapter(
            onItemClick = onTimelineClick,
            trackFocusedView = false,
            onBottomEdgeDown = onBottomEdgeDown
        )
        private var item: HomeLaneSection? = null

        init {
            binding.recyclerView.layoutManager =
                GridLayoutManager(binding.root.context, binding.root.resources.adaptiveSpanCount(base = 6, wide = 8))
            binding.recyclerView.adapter = adapter
            binding.recyclerView.itemAnimator = null
            // 高度 wrap_content 随内容条数变化，setHasFixedSize(true) 会吞掉异步 diff commit 的
            // requestLayout：切天走「有数据->空->有数据」后 GONE->VISIBLE 的首帧读到旧 itemCount=0，
            // 之后 notifyDataSetChanged 不再触发布局，列表永远塌陷在 0 高度（切天数据消失的根因）
            binding.recyclerView.setHasFixedSize(false)
            val timelineHeaderKeyListener = View.OnKeyListener { view, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) {
                    return@OnKeyListener false
                }
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        bindingAdapterPosition == 0 && onTopEdgeUp()
                    }

                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        view.context.findMainActivity()?.focusLeftFunctionArea(view) == true
                    }

                    else -> false
                }
            }
            binding.radioGroup.setOnCheckedChangeListener { _, checkedId ->
                when (checkedId) {
                    R.id.button_radio_0 -> showTimeline(0)
                    R.id.button_radio_1 -> showTimeline(1)
                    R.id.button_radio_2 -> showTimeline(2)
                    R.id.button_radio_3 -> showTimeline(3)
                    R.id.button_radio_4 -> showTimeline(4)
                    R.id.button_radio_5 -> showTimeline(5)
                    R.id.button_radio_6 -> showTimeline(6)
                    R.id.button_radio_7 -> showTimeline(7)
                }
            }
            binding.topTitle.setOnKeyListener(timelineHeaderKeyListener)
            listOf(
                binding.buttonRadio0,
                binding.buttonRadio1,
                binding.buttonRadio2,
                binding.buttonRadio3,
                binding.buttonRadio4,
                binding.buttonRadio5,
                binding.buttonRadio6,
                binding.buttonRadio7
            ).forEach { radioButton ->
                radioButton.setOnKeyListener(timelineHeaderKeyListener)
            }
        }

        fun bind(item: HomeLaneSection) {
            this.item = item
            binding.buttonRadio0.isChecked = true
            showTimeline(0)
        }

        fun requestPrimaryFocus(): Boolean {
            return binding.topTitle.requestFocus() ||
                requestSelectedFilterFocus() ||
                requestFirstCardFocusPublic()
        }

        fun requestHeaderFocus(): Boolean = binding.topTitle.requestFocus()

        fun focusedChildPosition(): Int {
            val focused = binding.recyclerView.rootView?.findFocus() ?: return RecyclerView.NO_POSITION
            val child = binding.recyclerView.findContainingItemView(focused) ?: return RecyclerView.NO_POSITION
            val pos = binding.recyclerView.getChildAdapterPosition(child)
            if (pos == RecyclerView.NO_POSITION) return RecyclerView.NO_POSITION
            val lm = binding.recyclerView.layoutManager as? GridLayoutManager ?: return pos
            return lm.spanSizeLookup.getSpanIndex(pos, lm.spanCount)
        }

        fun requestChildFocus(preferredColumn: Int): Boolean {
            val count = binding.recyclerView.adapter?.itemCount ?: 0
            if (count <= 0 || preferredColumn == RecyclerView.NO_POSITION || binding.recyclerView.visibility != View.VISIBLE) {
                return false
            }
            val lm = binding.recyclerView.layoutManager as? GridLayoutManager
            val targetPosition = if (lm != null) {
                preferredColumn.coerceIn(0, lm.spanCount - 1).coerceAtMost(count - 1)
            } else {
                preferredColumn.coerceIn(0, count - 1)
            }
            val holder = binding.recyclerView.findViewHolderForAdapterPosition(targetPosition)
            if (holder?.itemView?.requestFocus() == true) {
                return true
            }
            binding.recyclerView.scrollToPosition(targetPosition)
            binding.recyclerView.post {
                binding.recyclerView.findViewHolderForAdapterPosition(targetPosition)?.itemView?.requestFocus()
            }
            return true
        }

        private fun requestSelectedFilterFocus(): Boolean {
            val target = when (binding.radioGroup.checkedRadioButtonId) {
                R.id.button_radio_1 -> binding.buttonRadio1
                R.id.button_radio_2 -> binding.buttonRadio2
                R.id.button_radio_3 -> binding.buttonRadio3
                R.id.button_radio_4 -> binding.buttonRadio4
                R.id.button_radio_5 -> binding.buttonRadio5
                R.id.button_radio_6 -> binding.buttonRadio6
                R.id.button_radio_7 -> binding.buttonRadio7
                else -> binding.buttonRadio0
            }
            return target.requestFocus()
        }

        fun requestFirstCardFocusPublic(): Boolean {
            return adapter.requestFirstItemFocus(binding.recyclerView)
        }

        private fun showTimeline(targetDayOfWeek: Int) {
            val timelineDays = item?.timelineDays.orEmpty()
            if (timelineDays.isEmpty()) {
                showEmpty()
                return
            }

            if (targetDayOfWeek == 0) {
                updateEpisodes(timelineDays.firstOrNull()?.episodes.orEmpty())
                return
            }

            val todayIndex = timelineDays.indexOfFirst { it.isToday == 1 }
                .takeIf { it >= 0 }
                ?: 0
            if (timelineDays[todayIndex].dayOfWeek > targetDayOfWeek) {
                for (index in todayIndex downTo 0) {
                    if (timelineDays[index].dayOfWeek == targetDayOfWeek) {
                        updateEpisodes(timelineDays[index].episodes)
                        return
                    }
                }
            } else {
                for (index in todayIndex until timelineDays.size) {
                    if (timelineDays[index].dayOfWeek == targetDayOfWeek) {
                        updateEpisodes(timelineDays[index].episodes)
                        return
                    }
                }
            }
            showEmpty()
        }

        private fun updateEpisodes(episodes: List<SeriesTimeLineModel>) {
            if (episodes.isEmpty()) {
                showEmpty()
                return
            }
            binding.imageEmpty.visibility = View.GONE
            binding.recyclerView.visibility = View.VISIBLE
            adapter.setData(episodes)
        }

        private fun showEmpty() {
            binding.imageEmpty.visibility = View.VISIBLE
            binding.recyclerView.visibility = View.GONE
            adapter.setData(emptyList())
        }
    }

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

private fun isDescendantOf(view: View, ancestor: View): Boolean {
    var current: View? = view
    while (current != null) {
        if (current === ancestor) return true
        current = current.parent as? View
    }
    return false
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
