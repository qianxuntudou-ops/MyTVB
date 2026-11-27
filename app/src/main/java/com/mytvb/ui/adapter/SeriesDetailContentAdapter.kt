package com.mytvb.ui.adapter

import com.mytvb.core.ui.video.resolveThemedTextColor

import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ImageSpan
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.mytvb.R
import com.mytvb.core.ui.focus.tv.LinearTvFocusStrategy
import com.mytvb.core.ui.focus.tv.TvFocusableAdapter
import com.mytvb.core.ui.focus.tv.TvListFocusController
import com.mytvb.databinding.CellEpisodeBinding
import com.mytvb.databinding.CellSeriesHeadBinding
import com.mytvb.databinding.CellSeriesLaneBinding
import com.mytvb.model.episode.EpisodeModel
import com.mytvb.model.series.EpisodesDetailModel
import com.mytvb.model.series.SectionModel
import com.mytvb.model.series.SeriesModel
import com.mytvb.model.video.VideoModel
import com.mytvb.core.ui.image.ImageLoader
import com.mytvb.core.common.format.NumberUtils
import com.mytvb.core.ui.system.ScreenUtils
import com.mytvb.core.common.time.TimeUtils
import pl.droidsonroids.gif.GifDrawable

class SeriesDetailContentAdapter(
    private val onToggleFollow: () -> Unit,
    private val onContinueWatchClick: (EpisodeModel, Long) -> Unit,
    private val onEpisodeClick: (EpisodeModel) -> Unit,
    private val onSeasonClick: (SeriesModel) -> Unit,
    private val onSectionEpisodeClick: (VideoModel) -> Unit,
    private val onContentFocused: () -> Unit = {},
    private val onContentVerticalKey: ((View, Int) -> Boolean)? = null
) : ListAdapter<SeriesDetailContentAdapter.Row, RecyclerView.ViewHolder>(RowItemCallback) {

    sealed interface Row {
        data class Header(
            val detail: EpisodesDetailModel,
            val followed: Boolean
        ) : Row

        data class Episodes(
            val items: List<EpisodeModel>,
            val currentEpId: Long = 0L
        ) : Row

        data class Section(
            val section: SectionModel,
            val currentEpId: Long = 0L
        ) : Row

        data class Seasons(
            val title: String,
            val items: List<SeriesModel>
        ) : Row

        data class Recommend(
            val items: List<SeriesModel>
        ) : Row
    }

    private var lastFocusedRowPosition = RecyclerView.NO_POSITION
    private var recommendItems: List<SeriesModel> = emptyList()

    fun submit(detail: EpisodesDetailModel?, isFollowed: Boolean) {
        val newRows = buildRows(detail, isFollowed, recommendItems)
        if (currentList == newRows) {
            return
        }
        lastFocusedRowPosition = newRows.lastIndex
            .takeIf { it >= 0 }
            ?.let { lastFocusedRowPosition.coerceAtMost(it) }
            ?: RecyclerView.NO_POSITION
        submitList(newRows)
    }

    fun setRecommendItems(items: List<SeriesModel>) {
        if (recommendItems == items) return
        recommendItems = items
    }

    fun updateHeader(detail: EpisodesDetailModel?, isFollowed: Boolean) {
        val headerIndex = currentList.indexOfFirst { it is Row.Header }
        if (detail == null || headerIndex == -1) {
            submit(detail, isFollowed)
            return
        }
        val newList = currentList.toMutableList()
        newList[headerIndex] = Row.Header(detail, isFollowed)
        submitList(newList)
    }

    fun requestLastFocusedView(recyclerView: RecyclerView): Boolean {
        val targetPosition = lastFocusedRowPosition
        if (targetPosition != RecyclerView.NO_POSITION) {
            when (val holder = recyclerView.findViewHolderForAdapterPosition(targetPosition)) {
                is EpisodeLaneViewHolder -> if (holder.requestStoredFocus()) return true
                is SectionLaneViewHolder -> if (holder.requestStoredFocus()) return true
                is SeasonLaneViewHolder -> if (holder.requestStoredFocus()) return true
                is RecommendLaneViewHolder -> if (holder.requestStoredFocus()) return true
            }
        }
        return false
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is Row.Header -> VIEW_TYPE_HEADER
            is Row.Episodes -> VIEW_TYPE_EPISODES
            is Row.Section -> VIEW_TYPE_SECTION
            is Row.Seasons -> VIEW_TYPE_SEASONS
            is Row.Recommend -> VIEW_TYPE_RECOMMEND
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_HEADER -> HeaderViewHolder(
                CellSeriesHeadBinding.inflate(inflater, parent, false),
                onToggleFollow,
                onContinueWatchClick
            )

            VIEW_TYPE_EPISODES -> EpisodeLaneViewHolder(
                CellSeriesLaneBinding.inflate(inflater, parent, false),
                onEpisodeClick
            )

            VIEW_TYPE_SECTION -> SectionLaneViewHolder(
                CellSeriesLaneBinding.inflate(inflater, parent, false),
                onSectionEpisodeClick
            )

            VIEW_TYPE_SEASONS -> SeasonLaneViewHolder(
                CellSeriesLaneBinding.inflate(inflater, parent, false),
                onSeasonClick
            )

            else -> RecommendLaneViewHolder(
                CellSeriesLaneBinding.inflate(inflater, parent, false),
                onSeasonClick
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is Row.Header -> (holder as HeaderViewHolder).bind(row.detail, row.followed)
            is Row.Episodes -> (holder as EpisodeLaneViewHolder).bind(row.items, row.currentEpId)
            is Row.Section -> (holder as SectionLaneViewHolder).bind(row.section, row.currentEpId)
            is Row.Seasons -> (holder as SeasonLaneViewHolder).bind(row.title, row.items)
            is Row.Recommend -> (holder as RecommendLaneViewHolder).bind(row.items)
        }
    }

    private class HeaderViewHolder(
        private val binding: CellSeriesHeadBinding,
        onToggleFollow: () -> Unit,
        private val onContinueWatchClick: (EpisodeModel, Long) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        private var currentContinueEpisode: EpisodeModel? = null
        private var currentContinueSeekPositionMs: Long = 0L

        init {
            binding.buttonContinueWatch.setOnClickListener {
                currentContinueEpisode?.let { episode ->
                    onContinueWatchClick(episode, currentContinueSeekPositionMs)
                }
            }
            binding.buttonFollowSeries.setOnClickListener {
                onToggleFollow()
            }
        }

        fun bind(detail: EpisodesDetailModel, followed: Boolean) {
            ImageLoader.loadSeriesCover(
                imageView = binding.imageCover,
                url = detail.cover.ifEmpty { detail.squareCover }
            )
            ImageLoader.loadCenterCrop(
                imageView = binding.imageTop,
                url = detail.squareCover.ifEmpty { detail.cover },
                placeholder = R.drawable.default_video,
                error = R.drawable.default_video
            )

            binding.textTitle.text = detail.title.ifEmpty { detail.seasonTitle }
            binding.textSubtitle.text = buildSubtitle(detail)
            binding.textSubtitle.visibility =
                if (binding.textSubtitle.text.isNullOrBlank()) View.GONE else View.VISIBLE
            binding.textDescription.text = detail.evaluate.ifBlank { detail.subtitle }
            binding.textDescription.visibility =
                if (binding.textDescription.text.isNullOrBlank()) View.GONE else View.VISIBLE

            val progress = detail.userStatus?.progress
            currentContinueEpisode = detail.episodes.orEmpty().firstOrNull {
                it.id == progress?.lastEpId
            }
                // 进度落在花絮/预告（issue#140）：继续观看必须回到花絮本身，
                // 不能回落正片第 1 集；Fragment 侧按 id 命中 section 时走 section 队列
                ?: detail.section.orEmpty().asSequence()
                    .flatMap { it.episodes.asSequence() }
                    .firstOrNull { it.epid == progress?.lastEpId }
                    ?.toEpisodeModel()
                ?: detail.episodes.orEmpty().firstOrNull()
            currentContinueSeekPositionMs = progress?.lastTime
                ?.coerceAtLeast(0L)
                ?.times(1000L)
                ?: 0L

            val hasHistoryProgress = progress != null &&
                (progress.lastEpIndex.isNotBlank() || progress.lastEpId > 0L)
            binding.textWatchInfo.text = if (hasHistoryProgress) {
                val lastTime = progress?.lastTime ?: 0L
                // 官方语义：看完上报 last_time=-1（与播放端心跳 played_time=-1 同源），
                // 直接 formatDuration 会拼出「00:-1」，对齐列表卡显示「已看完」。
                val label = resolveLastWatchLabel(detail, progress)
                val progressLabel = if (lastTime < 0L) {
                    binding.root.context.getString(R.string.adapter_watched_complete)
                } else {
                    val totalSec = label.totalSec
                    // 统一「当前/总」形态（issue#145）；接口未下发时长时退回仅当前时长
                    if (totalSec > 0L) {
                        "${NumberUtils.formatDuration(lastTime)}/${NumberUtils.formatDuration(totalSec)}"
                    } else {
                        NumberUtils.formatDuration(lastTime)
                    }
                }
                binding.root.context.getString(
                    R.string.last_watch_,
                    label.display,
                    progressLabel
                )
            } else {
                detail.newEp?.pubTime?.takeIf { it.isNotBlank() }
                    ?: detail.subtitle
            }
            binding.textWatchInfo.visibility =
                if (binding.textWatchInfo.text.isNullOrBlank()) View.GONE else View.VISIBLE

            val isAnimation = detail.type == 1
            binding.buttonFollowSeries.text = binding.root.context.getString(
                if (followed) {
                    if (isAnimation) R.string.followed_animation else R.string.followed_series
                } else {
                    if (isAnimation) R.string.follow_animation else R.string.follow_series
                }
            )
            binding.buttonFollowSeries.setBackgroundResource(
                if (followed) R.drawable.button_common_2 else R.drawable.button_common
            )
            binding.buttonContinueWatch.text = binding.root.context.getString(
                if (hasHistoryProgress) R.string.continue_watch else R.string.start_watch
            )
            binding.buttonContinueWatch.isEnabled = currentContinueEpisode != null
            binding.buttonContinueWatch.alpha = if (currentContinueEpisode != null) 1f else 0.45f
        }

        /**
         * 还原「上次观看到 …」的标签：last_ep_index 对部分剧集下发的是集标题
         * （如「寻参」）而非序号，优先用 last_ep_id 匹配分集列表还原。
         * 正片 →「第 N 集」；预告（正片 lane 的 section_type=1 条目或预告栏目）→
         * 「第 N 集预告」（集号取条目标题里的数字，提取不到退回标题原文，不硬套
         * 「第X集」句式）；花絮等栏目 →「栏目名 · 集标题」（issue#140/145）。
         * 同时带出该集总时长（秒），供「当前/总时长」拼接；接口未下发时为 0。
         */
        private fun resolveLastWatchLabel(
            detail: EpisodesDetailModel,
            progress: com.mytvb.model.series.EpisodeProgressModel?
        ): LastWatchLabel {
            val fallbackIndex = progress?.lastEpIndex.orEmpty()
            val lastEpId = progress?.lastEpId?.takeIf { it > 0L }
                ?: return LastWatchLabel(previewAwareFallback(fallbackIndex), 0L)
            val episodes = detail.episodes.orEmpty()
            val matched = episodes.firstOrNull { it.id == lastEpId }
            if (matched != null) {
                matched.title.toIntOrNull()?.let {
                    return LastWatchLabel(
                        display = if (matched.sectionType == 1) "第${it}集预告" else "第${it}集",
                        totalSec = matched.durationSec()
                    )
                }
                if (matched.sectionType == 1) {
                    // 预告条目但标题非数字（如「第202话 预告」）：提取数字拼「第N集预告」，
                    // 提取不到退回标题原文
                    val number = extractEpisodeNumber(matched.title, matched.longTitle)
                    val display = number?.let { "第${it}集预告" }
                        ?: matched.longTitle.ifBlank { matched.title }
                    return LastWatchLabel(display, matched.durationSec())
                }
                // 集标题非数字（如「寻参」）：用正片列表位置还原「第 15 集」
                val position = episodes.filter { it.sectionType == 0 }
                    .indexOfFirst { it.id == lastEpId }
                if (position >= 0) {
                    return LastWatchLabel("第${position + 1}集", matched.durationSec())
                }
            }
            detail.section.orEmpty().forEach { section ->
                section.episodes.firstOrNull { it.epid == lastEpId }?.let { episode ->
                    val sectionTitle = section.resolveDisplayTitle(binding.root)
                    if (sectionTitle.contains("预告")) {
                        val number = extractEpisodeNumber(
                            episode.title,
                            episode.bangumi?.longTitle ?: episode.title
                        )
                        val display = number?.let { "第${it}集预告" }
                            ?: episode.bangumi?.longTitle?.takeIf { it.isNotBlank() }
                            ?: episode.title
                        return LastWatchLabel(display, episode.durationSec())
                    }
                    val episodeTitle = episode.bangumi?.longTitle?.takeIf { it.isNotBlank() }
                        ?: episode.title
                    return LastWatchLabel("$sectionTitle · $episodeTitle", episode.durationSec())
                }
            }
            return LastWatchLabel(previewAwareFallback(fallbackIndex), 0L)
        }

        private fun buildSubtitle(detail: EpisodesDetailModel): String {
            val context = binding.root.context
            return buildList {
                buildStatusInfo(detail)?.let { add(it) }
                detail.rating?.score
                    ?.takeIf { it > 0 }
                    ?.let { add(context.getString(R.string.adapter_rating_format, it)) }
                detail.stat?.favorites
                    ?.takeIf { it > 0 }
                    ?.let {
                        val labelRes = if (detail.type == 1) R.string.follow_animation else R.string.follow_series
                        add(context.getString(R.string.adapter_chase_count_format, NumberUtils.formatCount(context, it), context.getString(labelRes)))
                    }
                detail.stat?.view
                    ?.takeIf { it > 0 }
                    ?.let { add(context.getString(R.string.adapter_play_count_format, NumberUtils.formatCount(context, it))) }
                detail.stat?.danmaku
                    ?.takeIf { it > 0 }
                    ?.let { add(context.getString(R.string.adapter_danmaku_count_format, NumberUtils.formatCount(context, it))) }
            }.joinToString(" | ")
        }

        private fun buildStatusInfo(detail: EpisodesDetailModel): String? {
            detail.newEp?.desc?.takeIf { it.isNotBlank() }?.let { return it }
            detail.newEp?.indexShow?.takeIf { it.isNotBlank() }?.let { return it }
            val context = binding.root.context
            val unit = context.getString(if (detail.type == 1) R.string.adapter_episode_unit_anime else R.string.adapter_episode_unit_drama)
            val publish = detail.publish
            if (publish != null) {
                val statusText = if (publish.isFinish == 1) {
                    context.getString(R.string.adapter_series_finished_total_format, detail.total, unit)
                } else {
                    val timeShow = publish.pubTimeShow.takeIf { it.isNotBlank() }
                        ?: publish.releaseDateShow.takeIf { it.isNotBlank() }
                    if (!timeShow.isNullOrBlank()) {
                        context.getString(R.string.adapter_series_ongoing_format, timeShow)
                    } else {
                        val episodeCount = detail.episodes?.size ?: 0
                        context.getString(R.string.adapter_series_ongoing_updated_format, episodeCount, unit)
                    }
                }
                return statusText
            }
            val total = detail.total
            val episodeCount = detail.episodes?.size ?: 0
            if (total > 0) {
                return if (episodeCount >= total) {
                    context.getString(R.string.adapter_series_finished_total_format, total, unit)
                } else {
                    context.getString(R.string.adapter_series_ongoing_updated_format, episodeCount, unit)
                }
            }
            if (episodeCount > 0) {
                return context.getString(R.string.adapter_series_updated_format, episodeCount, unit)
            }
            return null
        }
    }

    private inner class EpisodeLaneViewHolder(
        private val binding: CellSeriesLaneBinding,
        onEpisodeClick: (EpisodeModel) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        private var tvFocusController: TvListFocusController? = null
        private val adapter = EpisodeAdapter(
            onEpisodeClick = onEpisodeClick,
            onEpisodeFocused = ::onRowContentFocused,
            onVerticalKey = { view, keyCode ->
                if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP &&
                    binding.buttonOrder.visibility == View.VISIBLE &&
                    binding.buttonOrder.requestFocus()
                ) {
                    true
                } else {
                    onContentVerticalKey?.invoke(view, keyCode) == true
                }
            },
            onItemFocusedWithView = { view, position ->
                tvFocusController?.onItemFocused(view, position)
            },
            onItemDpad = { view, keyCode, event ->
                val controller = tvFocusController
                when {
                    controller == null -> false
                    controller.handleKey(view, keyCode, event) -> true
                    // 行边界：左右键都吞掉，防止系统 focusSearch 把焦点甩到其他地方
                    // （实测放行后正片首卡 LEFT 直跳页面左上角返回按钮——排序按钮在
                    // 卡片上方不在左边，focusSearch 找不到它）。排序按钮走 UP 直达，换行走上下键。
                    else -> true
                }
            }
        )
        private var inReverseOrder = false
        private var items: List<EpisodeModel> = emptyList()
        private var currentEpId: Long = 0L
        private var orderButtonFocused = false

        init {
            binding.recyclerView.layoutManager =
                LinearLayoutManager(binding.root.context, RecyclerView.HORIZONTAL, false)
            binding.recyclerView.adapter = adapter
            binding.recyclerView.isNestedScrollingEnabled = false
            // 切换正/倒序是整表 move，DefaultItemAnimator 的移动动画会与
            // scrollToPosition 定位竞态（锚点被动画带偏），与外层详情列表一致关掉
            binding.recyclerView.itemAnimator = null
            // 高频左右键下系统 focusSearch 会与平滑滚动动画竞态导致焦点回跳/跨行
            // （实测 38 话列表连发右键出现 32→30 回跳并跳进预告行），
            // 交给 TvListFocusController 按 position 步进（UGC 详情页合集列表同款）
            tvFocusController = TvListFocusController(
                recyclerView = binding.recyclerView,
                adapter = adapter,
                strategy = LinearTvFocusStrategy(RecyclerView.HORIZONTAL),
                canLoadMore = { false },
                loadMore = {},
                restoreAppendFocusFromOutside = true,
                restoreFocusOnFocusedDetach = true,
                debugName = "series-episode"
            )
            binding.buttonOrder.visibility = View.VISIBLE
            binding.textOrder.setText(R.string.positive_sequence)
            binding.buttonOrder.setOnClickListener {
                inReverseOrder = !inReverseOrder
                // 切换排序同样把正在看的集顶到最前（用户要求「永远第一」）：
                // 正序/倒序都定位当前集，不再回列表开头
                submitOrderedItems(locateCurrent = true)
                binding.textOrder.setText(
                    if (inReverseOrder) R.string.negative_sequence else R.string.positive_sequence
                )
            }
            binding.buttonOrder.setOnFocusChangeListener { _, hasFocus ->
                orderButtonFocused = hasFocus
                if (hasFocus) {
                    onRowContentFocused()
                }
            }
            binding.buttonOrder.setOnKeyListener { view, keyCode, event ->
                if (event.action != android.view.KeyEvent.ACTION_DOWN) {
                    return@setOnKeyListener false
                }
                when (keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_UP ->
                        onContentVerticalKey?.invoke(view, keyCode) == true

                    android.view.KeyEvent.KEYCODE_DPAD_DOWN ->
                        focusNearestEpisodeItem(view.centerXOnScreen())

                    else -> false
                }
            }
        }

        fun bind(items: List<EpisodeModel>, currentEpId: Long) {
            binding.topTitle.setText(R.string.episodes)
            this.items = items
            this.currentEpId = currentEpId
            // 有观看记录→默认倒序（最新一集排最前，追番时少翻列表直接到最新进度）；
            // 没看过→正序从第 1 集开始
            inReverseOrder = currentEpId > 0L
            binding.buttonOrder.visibility = if (items.size > 1) View.VISIBLE else View.GONE
            if (binding.buttonOrder.visibility != View.VISIBLE) {
                orderButtonFocused = false
            }
            bindEpisodes()
        }

        private fun bindEpisodes() {
            submitOrderedItems(locateCurrent = true)
            binding.textOrder.setText(
                if (inReverseOrder) R.string.negative_sequence else R.string.positive_sequence
            )
        }

        // 倒序通过提交反转列表实现：reverseLayout 会让铺不满一行的内容整体靠右
        private fun submitOrderedItems(locateCurrent: Boolean) {
            val ordered = if (inReverseOrder) items.asReversed() else items
            adapter.currentEpId = currentEpId
            // 滚动必须等 diff 提交后再执行：submitList 是异步的，提前滚会被
            // 布局锚点保持带偏（切换排序后仍停在原 index 区间）
            adapter.submitList(ordered) {
                if (locateCurrent) {
                    scrollToCurrentEpisode(ordered)
                } else {
                    binding.recyclerView.scrollToPosition(0)
                }
            }
        }

        // 看过→把上次看的那集顶到列表可视区第一个位置（用户要求：当前正在看的集
        // 永远尽量排在最前）；当前集之后剩余集数不足一屏时改为末尾对齐，即
        // 「尽量往前」的位置，右侧不会留白。
        // 没看过/进度集不在列表→回到首位（正序=第 1 集，倒序=最新一集）
        private fun scrollToCurrentEpisode(ordered: List<EpisodeModel>) {
            val index = if (currentEpId > 0L) {
                ordered.indexOfFirst { it.id == currentEpId }
            } else {
                -1
            }
            scrollItemToStart(binding.recyclerView, index, ordered.size)
        }

        fun requestStoredFocus(): Boolean {
            if (orderButtonFocused && binding.buttonOrder.isShown && binding.buttonOrder.requestFocus()) {
                return true
            }
            return adapter.requestStoredItemFocus(binding.recyclerView)
        }

        private fun onRowContentFocused() {
            updateFocusedRow(bindingAdapterPosition)
        }

        private fun focusNearestEpisodeItem(anchorX: Int): Boolean {
            val targetView = binding.recyclerView.visibleFocusableChildren()
                .minByOrNull { child -> kotlin.math.abs(child.centerXOnScreen() - anchorX) }
                ?: return false
            return targetView.requestFocus()
        }
    }

    private inner class SectionLaneViewHolder(
        private val binding: CellSeriesLaneBinding,
        onItemClick: (VideoModel) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        private var tvFocusController: TvListFocusController? = null
        private val adapter = SectionEpisodeAdapter(
            onItemClick = onItemClick,
            onItemFocused = ::onRowContentFocused,
            onVerticalKey = onContentVerticalKey,
            onItemFocusedWithView = { view, position ->
                tvFocusController?.onItemFocused(view, position)
            },
            onItemDpad = { view, keyCode, event ->
                val controller = tvFocusController
                when {
                    controller == null -> false
                    controller.handleKey(view, keyCode, event) -> true
                    // 行边界：左右键都吞掉，防止系统 focusSearch 把焦点甩到其他行
                    // （预告行左右侧均无同行控件，放行必跨行）。换行走上下键。
                    else -> true
                }
            }
        )

        init {
            binding.recyclerView.layoutManager =
                LinearLayoutManager(binding.root.context, RecyclerView.HORIZONTAL, false)
            binding.recyclerView.adapter = adapter
            binding.recyclerView.isNestedScrollingEnabled = false
            tvFocusController = TvListFocusController(
                recyclerView = binding.recyclerView,
                adapter = adapter,
                strategy = LinearTvFocusStrategy(RecyclerView.HORIZONTAL),
                canLoadMore = { false },
                loadMore = {},
                restoreAppendFocusFromOutside = true,
                restoreFocusOnFocusedDetach = true,
                debugName = "series-section"
            )
            binding.buttonOrder.visibility = View.GONE
        }

        fun bind(section: SectionModel, currentEpId: Long) {
            binding.topTitle.text = section.resolveDisplayTitle(binding.root)
            adapter.currentEpId = currentEpId
            // 滚动必须等 diff 提交后再执行（正片 lane 同款）：submitList 异步，
            // 提前滚会被布局锚点保持带偏
            adapter.setItems(section.episodes) {
                scrollToCurrentSectionEpisode(section.episodes, currentEpId)
            }
        }

        // 上次看到的花絮/预告落在本栏目→顶到可视区最左（正片 lane 同款）；
        // 不在本栏目→保持首位
        private fun scrollToCurrentSectionEpisode(items: List<VideoModel>, currentEpId: Long) {
            if (currentEpId <= 0L) return
            val index = items.indexOfFirst { it.epid == currentEpId }
            if (index <= 0) return
            scrollItemToStart(binding.recyclerView, index, items.size)
        }

        fun requestStoredFocus(): Boolean {
            return adapter.requestStoredItemFocus(binding.recyclerView)
        }

        private fun onRowContentFocused() {
            updateFocusedRow(bindingAdapterPosition)
        }
    }

    private inner class SeasonLaneViewHolder(
        private val binding: CellSeriesLaneBinding,
        onSeasonClick: (SeriesModel) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        private var tvFocusController: TvListFocusController? = null
        private val adapter = SeriesAdapter(
            onItemClick = onSeasonClick,
            enableSidebarExit = false,
            onItemFocused = ::onRowContentFocused,
            onVerticalKey = onContentVerticalKey,
            onItemFocusedWithView = { view, position ->
                tvFocusController?.onItemFocused(view, position)
            },
            onItemDpad = { view, keyCode, event ->
                val controller = tvFocusController
                when {
                    controller == null -> false
                    controller.handleKey(view, keyCode, event) -> true
                    // 行边界：左右键都吞掉，防止系统 focusSearch 把焦点甩到其他行。换行走上下键。
                    else -> true
                }
            },
            cardWidth = ScreenUtils.getScreenWidth(binding.root.context) / 7
        )

        init {
            binding.recyclerView.layoutManager =
                LinearLayoutManager(binding.root.context, RecyclerView.HORIZONTAL, false)
            binding.recyclerView.adapter = adapter
            binding.recyclerView.isNestedScrollingEnabled = false
            tvFocusController = TvListFocusController(
                recyclerView = binding.recyclerView,
                adapter = adapter,
                strategy = LinearTvFocusStrategy(RecyclerView.HORIZONTAL),
                canLoadMore = { false },
                loadMore = {},
                restoreAppendFocusFromOutside = true,
                restoreFocusOnFocusedDetach = true,
                debugName = "series-season"
            )
            binding.buttonOrder.visibility = View.GONE
        }

        fun bind(title: String, items: List<SeriesModel>) {
            binding.topTitle.text = if (title.isBlank()) {
                binding.root.context.getString(R.string.adapter_related_series)
            } else {
                binding.root.context.getString(R.string.adapter_related_series_format, title)
            }
            adapter.setData(items)
        }

        fun requestStoredFocus(): Boolean {
            return adapter.requestStoredItemFocus(binding.recyclerView)
        }

        private fun onRowContentFocused() {
            updateFocusedRow(bindingAdapterPosition)
        }
    }

    private inner class RecommendLaneViewHolder(
        private val binding: CellSeriesLaneBinding,
        onSeasonClick: (SeriesModel) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        private var tvFocusController: TvListFocusController? = null
        private val adapter = SeriesAdapter(
            onItemClick = onSeasonClick,
            enableSidebarExit = false,
            onItemFocused = ::onRowContentFocused,
            onVerticalKey = onContentVerticalKey,
            onItemFocusedWithView = { view, position ->
                tvFocusController?.onItemFocused(view, position)
            },
            onItemDpad = { view, keyCode, event ->
                val controller = tvFocusController
                when {
                    controller == null -> false
                    controller.handleKey(view, keyCode, event) -> true
                    // 行边界：左右键都吞掉，防止系统 focusSearch 把焦点甩到其他行。换行走上下键。
                    else -> true
                }
            },
            cardWidth = ScreenUtils.getScreenWidth(binding.root.context) / 7
        )

        init {
            binding.recyclerView.layoutManager =
                LinearLayoutManager(binding.root.context, RecyclerView.HORIZONTAL, false)
            binding.recyclerView.adapter = adapter
            binding.recyclerView.isNestedScrollingEnabled = false
            tvFocusController = TvListFocusController(
                recyclerView = binding.recyclerView,
                adapter = adapter,
                strategy = LinearTvFocusStrategy(RecyclerView.HORIZONTAL),
                canLoadMore = { false },
                loadMore = {},
                restoreAppendFocusFromOutside = true,
                restoreFocusOnFocusedDetach = true,
                debugName = "series-recommend"
            )
            binding.buttonOrder.visibility = View.GONE
        }

        fun bind(items: List<SeriesModel>) {
            binding.topTitle.setText(R.string.related_recommend)
            adapter.setData(items)
        }

        fun requestStoredFocus(): Boolean {
            return adapter.requestStoredItemFocus(binding.recyclerView)
        }

        private fun onRowContentFocused() {
            updateFocusedRow(bindingAdapterPosition)
        }
    }

    private class SectionEpisodeAdapter(
        private val onItemClick: (VideoModel) -> Unit,
        private val onItemFocused: (() -> Unit)? = null,
        private val onVerticalKey: ((View, Int) -> Boolean)? = null,
        onItemFocusedWithView: ((View, Int) -> Unit)? = null,
        private val onItemDpad: ((View, Int, KeyEvent) -> Boolean)? = null
    ) : ListAdapter<VideoModel, SectionEpisodeAdapter.SectionEpisodeViewHolder>(VideoItemCallback),
        TvFocusableAdapter {

        private var focusedPosition = RecyclerView.NO_POSITION
        private val itemFocusedWithView = onItemFocusedWithView

        /** 上次看到的花絮/预告：标题 accent 高亮 + 播放动图（正片 lane 同款） */
        var currentEpId: Long = 0L

        override fun focusableItemCount(): Int = itemCount

        override fun stableKeyAt(position: Int): String? {
            // PGC 预告/花絮不是普通视频，aid 可能为 0，用 cid 兜底防 key 全撞
            val item = currentList.getOrNull(position) ?: return null
            return when {
                item.aid > 0 -> "sec:aid:${item.aid}"
                item.cid > 0 -> "sec:cid:${item.cid}"
                else -> "sec:pos:$position"
            }
        }

        override fun findPositionByStableKey(key: String): Int {
            return when {
                key.startsWith("sec:aid:") -> {
                    val id = key.removePrefix("sec:aid:").toLongOrNull()
                        ?: return RecyclerView.NO_POSITION
                    currentList.indexOfFirst { it.aid == id }
                }

                key.startsWith("sec:cid:") -> {
                    val id = key.removePrefix("sec:cid:").toLongOrNull()
                        ?: return RecyclerView.NO_POSITION
                    currentList.indexOfFirst { it.cid == id }
                }

                key.startsWith("sec:pos:") -> {
                    val pos = key.removePrefix("sec:pos:").toIntOrNull()
                        ?: return RecyclerView.NO_POSITION
                    if (pos in currentList.indices) pos else RecyclerView.NO_POSITION
                }

                else -> RecyclerView.NO_POSITION
            }.takeIf { it >= 0 } ?: RecyclerView.NO_POSITION
        }

        fun setItems(data: List<VideoModel>, onCommitted: (() -> Unit)? = null) {
            focusedPosition = focusedPosition
                .takeIf { it != RecyclerView.NO_POSITION && it < data.size }
                ?: RecyclerView.NO_POSITION
            if (currentList == data) {
                // 内容没变不重提交，但定位回调仍要执行（rebind 场景）
                onCommitted?.invoke()
                return
            }
            submitList(data) { onCommitted?.invoke() }
        }

        fun requestStoredItemFocus(recyclerView: RecyclerView): Boolean {
            val position = focusedPosition
            if (position == RecyclerView.NO_POSITION) {
                return false
            }
            return (recyclerView.findViewHolderForAdapterPosition(position) as? SectionEpisodeViewHolder)
                ?.requestFocus() == true
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SectionEpisodeViewHolder {
            val binding = CellEpisodeBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return SectionEpisodeViewHolder(binding, onItemClick) { view ->
                onItemFocused?.invoke()
            }.also { holder ->
                holder.onVerticalKey = onVerticalKey
                holder.onItemDpad = onItemDpad
                holder.onFocusedViewChanged = { view ->
                    focusedPosition = recyclerPositionOf(view)
                    itemFocusedWithView?.invoke(view, focusedPosition)
                }
            }
        }

        override fun onBindViewHolder(holder: SectionEpisodeViewHolder, position: Int) {
            holder.bind(getItem(position), getItem(position).epid == currentEpId)
        }

        class SectionEpisodeViewHolder(
            private val binding: CellEpisodeBinding,
            private val onItemClick: (VideoModel) -> Unit,
            private val onFocused: (View) -> Unit
        ) : RecyclerView.ViewHolder(binding.root) {

            private var currentItem: VideoModel? = null
            var onVerticalKey: ((View, Int) -> Boolean)? = null
            var onItemDpad: ((View, Int, KeyEvent) -> Boolean)? = null
            var onFocusedViewChanged: ((View) -> Unit)? = null

            // 布局默认文字色（含 state），退出「当前集」态时还原（正片 lane 同款）
            private val defaultTitleColors = binding.textTitle.textColors
            private var playingDrawable: GifDrawable? = null
            private val playingAnimCallback = object : Drawable.Callback {
                override fun invalidateDrawable(d: Drawable) {
                    // GIF 每推进一帧回调这里，驱动 TextView 重绘下一帧
                    binding.textTitle.invalidate()
                }

                override fun scheduleDrawable(d: Drawable, what: Runnable, t: Long) {}
                override fun unscheduleDrawable(d: Drawable, what: Runnable) {}
            }

            fun requestFocus(): Boolean = binding.clickView.requestFocus()

            init {
                val coverRadiusPx = binding.imageView.resources.getDimension(R.dimen.px15)
                binding.imageView.clipToOutline = true
                binding.imageView.outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, coverRadiusPx)
                    }
                }
                binding.clickView.setOnClickListener {
                    currentItem?.let(onItemClick)
                }
            }

            fun bind(item: VideoModel, isCurrent: Boolean) {
                currentItem = item
                applyDetailCardWidth(binding.clickView)
                binding.clickView.nextFocusUpId = View.NO_ID
                binding.clickView.setOnKeyListener { view, keyCode, event ->
                    if (event.action != android.view.KeyEvent.ACTION_DOWN) {
                        return@setOnKeyListener false
                    }
                    when (keyCode) {
                        // 左右交给 TvListFocusController：按 position 步进，不受平滑滚动动画影响
                        android.view.KeyEvent.KEYCODE_DPAD_LEFT,
                        android.view.KeyEvent.KEYCODE_DPAD_RIGHT ->
                            onItemDpad?.invoke(view, keyCode, event) == true

                        android.view.KeyEvent.KEYCODE_DPAD_UP,
                        android.view.KeyEvent.KEYCODE_DPAD_DOWN -> onVerticalKey?.invoke(view, keyCode) == true
                        else -> false
                    }
                }
                ImageLoader.loadVideoCover(binding.imageView, item.cover.ifEmpty { item.pic })
                binding.viewCover.visibility = View.GONE
                binding.iconPlay.visibility = View.GONE
                binding.textBadge.visibility = View.GONE
                binding.textPosition.visibility = View.GONE
                binding.textTitle.visibility = View.VISIBLE
                bindTitle(item, isCurrent)
                binding.clickView.setOnFocusChangeListener { view, hasFocus ->
                    if (hasFocus) {
                        onFocusedViewChanged?.invoke(view)
                        onFocused(view)
                    }
                }
            }

            // 上次看到的那条：accent 高亮 + 播放动图（正片 lane EpisodeAdapter 同款）
            private fun bindTitle(item: VideoModel, isCurrent: Boolean) {
                val title = item.title.ifBlank { item.bangumi?.longTitle.orEmpty() }
                if (!isCurrent) {
                    playingDrawable?.stop()
                    binding.textTitle.setTextColor(defaultTitleColors)
                    binding.textTitle.text = title
                    return
                }
                binding.textTitle.setTextColor(
                    resolveThemedTextColor(binding.root.context, androidx.appcompat.R.attr.colorAccent, 0xFFBB86FC.toInt())
                )
                val drawable = ensurePlayingDrawable()
                if (drawable == null) {
                    // GIF 解码失败退化成纯高亮文字
                    binding.textTitle.text = title
                    return
                }
                val iconSize = binding.root.resources.getDimensionPixelSize(R.dimen.px26)
                drawable.setBounds(0, 0, iconSize, iconSize)
                drawable.stop()
                drawable.start()
                // 首字符占位画动图，第二个空格做图标与文字的间距
                val spannable = SpannableStringBuilder("  ")
                spannable.setSpan(
                    ImageSpan(drawable, ImageSpan.ALIGN_CENTER),
                    0, 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                spannable.append(title)
                binding.textTitle.text = spannable
            }

            private fun ensurePlayingDrawable(): GifDrawable? {
                playingDrawable?.let { return it }
                val drawable = runCatching {
                    GifDrawable(binding.root.resources, R.drawable.playing)
                }.getOrNull() ?: return null
                drawable.callback = playingAnimCallback
                playingDrawable = drawable
                return drawable
            }

            private fun applyDetailCardWidth(view: View) {
                val layoutParams = view.layoutParams ?: return
                val targetWidth = ScreenUtils.getScreenWidth(view.context) / DETAIL_CARDS_PER_SCREEN
                if (layoutParams.width != targetWidth) {
                    layoutParams.width = targetWidth
                    view.layoutParams = layoutParams
                }
            }
        }

        private fun recyclerPositionOf(view: View): Int {
            return (view.parent as? RecyclerView)?.getChildAdapterPosition(view) ?: RecyclerView.NO_POSITION
        }

        companion object {
            private val VideoItemCallback = object : DiffUtil.ItemCallback<VideoModel>() {
                override fun areItemsTheSame(oldItem: VideoModel, newItem: VideoModel): Boolean {
                    return when {
                        oldItem.aid > 0 && newItem.aid > 0 -> oldItem.aid == newItem.aid
                        oldItem.bvid.isNotBlank() && newItem.bvid.isNotBlank() -> oldItem.bvid == newItem.bvid
                        oldItem.cid > 0 && newItem.cid > 0 -> oldItem.cid == newItem.cid
                        else -> oldItem == newItem
                    }
                }

                override fun areContentsTheSame(oldItem: VideoModel, newItem: VideoModel): Boolean {
                    return oldItem == newItem
                }
            }
        }
    }

    companion object {
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_EPISODES = 1
        private const val VIEW_TYPE_SECTION = 2
        private const val VIEW_TYPE_SEASONS = 3
        private const val VIEW_TYPE_RECOMMEND = 4

        private val RowItemCallback = object : DiffUtil.ItemCallback<Row>() {
            override fun areItemsTheSame(oldItem: Row, newItem: Row): Boolean {
                return stableId(oldItem) == stableId(newItem)
            }

            override fun areContentsTheSame(oldItem: Row, newItem: Row): Boolean {
                return oldItem == newItem
            }

            private fun stableId(row: Row): String {
                return when (row) {
                    is Row.Header -> "header"
                    is Row.Episodes -> "episodes"
                    is Row.Section -> "section:${row.section.id}:${row.section.type}:${row.section.title}"
                    is Row.Seasons -> "seasons:${row.title}"
                    is Row.Recommend -> "recommend"
                }
            }
        }
    }

    private fun updateFocusedRow(position: Int) {
        if (position == RecyclerView.NO_POSITION) {
            return
        }
        lastFocusedRowPosition = position
        onContentFocused()
    }

    private fun buildRows(detail: EpisodesDetailModel?, isFollowed: Boolean, recommend: List<SeriesModel>): List<Row> {
        if (detail == null) {
            return emptyList()
        }
        return buildList {
            add(Row.Header(detail, isFollowed))

            detail.episodes.orEmpty()
                .takeIf { it.isNotEmpty() }
                ?.let { add(Row.Episodes(it, detail.userStatus?.progress?.lastEpId ?: 0L)) }

            detail.section.orEmpty()
                .filter { it.episodes.isNotEmpty() }
                .forEach { section ->
                    add(Row.Section(section, detail.userStatus?.progress?.lastEpId ?: 0L))
                }

            detail.seasons.orEmpty()
                .takeIf { it.isNotEmpty() }
                ?.let { seasons ->
                    add(
                        Row.Seasons(
                            title = detail.title.ifBlank { detail.seasonTitle },
                            items = seasons
                        )
                    )
                }

            if (recommend.isNotEmpty()) {
                add(Row.Recommend(recommend))
            }
        }
    }

}

private fun SectionModel.resolveDisplayTitle(view: View): String {
    return title.takeIf { it.isNotBlank() } ?: view.context.getString(R.string.extra_content)
}

private fun View.centerXOnScreen(): Int {
    val location = IntArray(2)
    getLocationOnScreen(location)
    return location[0] + width / 2
}

private fun RecyclerView.visibleFocusableChildren(): List<View> {
    return buildList {
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child != null && child.isFocusable && child.visibility == View.VISIBLE) {
                add(child)
            }
        }
    }
}

/**
 * 「上次观看到 …」显示标签：[display] 为整体槽位（第X集/第X集预告/栏目·标题），
 * [totalSec] 为该集总时长（秒，接口未下发时 0），供「当前/总时长」拼接。
 */
private data class LastWatchLabel(
    val display: String,
    val totalSec: Long
)

/** last_ep_index 兜底：纯数字（序号）才包「第X集」壳；下发集标题/完整文案时保持原文 */
internal fun previewAwareFallback(rawIndex: String): String {
    val trimmed = rawIndex.trim()
    return if (trimmed.toIntOrNull() != null) {
        "第${trimmed}集"
    } else {
        trimmed
    }
}

/**
 * 从预告条目标题提取对应正片集号：标题纯数字（实测预告条目 title=集号，如「202」）
 * 优先，其次「第N集/话」形态。提取不到返回 null（调用方退回标题原文）。
 */
internal fun extractEpisodeNumber(vararg texts: String): Int? {
    for (text in texts) {
        val trimmed = text.trim()
        trimmed.toIntOrNull()?.let { return it }
    }
    for (text in texts) {
        val match = Regex("第(\\d+)[集话]").find(text) ?: continue
        match.groupValues[1].toIntOrNull()?.let { return it }
    }
    return null
}

/** 正片分集总时长（秒）：pgc/view/web/season 的 episodes 下发 duration 毫秒 */
internal fun EpisodeModel.durationSec(): Long = duration.takeIf { it > 0L }?.div(1000L) ?: 0L

/** 花絮/预告栏目条目总时长（秒）：season/section 接口下发 duration 毫秒（实测） */
internal fun com.mytvb.model.video.VideoModel.durationSec(): Long =
    duration.takeIf { it > 0L }?.div(1000L) ?: 0L

/**
 * 把 [index] 条目顶到横向选集列表可视区最左（「当前正在看的集排第一个」）。
 *
 * [index] 之后剩余条目不足以填满一屏时，`scrollToPositionWithOffset(index, 0)` 会
 * 允许过度滚动、在右侧留下空白；此时改为 `scrollToPosition(lastIndex)` 末尾对齐，
 * 当前集自然落在「尽量靠前」的位置且不留白（用户明确要求的兜底：集数不够就尽量往前挪）。
 */
private fun scrollItemToStart(rv: RecyclerView, index: Int, itemCount: Int) {
    val llm = rv.layoutManager as? LinearLayoutManager ?: return
    if (index <= 0 || itemCount <= 1) {
        llm.scrollToPosition(0)
        return
    }
    // 卡片 pitch = 卡宽 + 卡片左边距（cell_episode_list.xml 的 marginStart）；
    // 布局未就绪时按 屏宽/DETAIL_CARDS_PER_SCREEN 兜底，与 applyDetailCardWidth 同源
    val childPitch = rv.getChildAt(0)?.let { child ->
        val marginStart = (child.layoutParams as? ViewGroup.MarginLayoutParams)?.marginStart ?: 0
        (child.width + marginStart).takeIf { it > 0 }
    }
    val pitch = childPitch ?: (
        ScreenUtils.getScreenWidth(rv.context) / DETAIL_CARDS_PER_SCREEN +
            rv.resources.getDimensionPixelSize(R.dimen.px15)
        )
    val viewport = rv.width.takeIf { it > 0 } ?: ScreenUtils.getScreenWidth(rv.context)
    val perScreen = (viewport / pitch).coerceAtLeast(1)
    val lastStart = (itemCount - perScreen).coerceAtLeast(0)
    if (index > lastStart) {
        llm.scrollToPosition(itemCount - 1)
    } else {
        llm.scrollToPositionWithOffset(index, 0)
    }
}

/** 选集行一屏可见卡片数（applyDetailCardWidth 按 屏宽/该值 定卡宽）。 */
private const val DETAIL_CARDS_PER_SCREEN = 5

/** 花絮/预告条目回填「继续观看」按钮：id 用 epid，播放队列由 Fragment 侧按 id 分流到 section 队列 */
private fun VideoModel.toEpisodeModel(): EpisodeModel = EpisodeModel(
    id = epid,
    aid = aid,
    bvid = bvid,
    cid = cid,
    title = title,
    longTitle = bangumi?.longTitle.orEmpty(),
    cover = cover.ifEmpty { pic },
    sectionType = 1
)
