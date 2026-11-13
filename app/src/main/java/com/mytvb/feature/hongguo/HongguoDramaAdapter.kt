package com.mytvb.feature.hongguo

import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.mytvb.R
import com.mytvb.core.common.log.VideoCardPerfLogger
import com.mytvb.core.ui.base.BaseVideoAdapter
import com.mytvb.core.ui.base.BaseVideoViewHolder
import com.mytvb.core.ui.focus.VideoCardFocusHelper
import com.mytvb.core.ui.image.ImageLoader
import com.mytvb.core.ui.video.VideoCardCoverRatio
import com.mytvb.core.ui.video.VideoCardViews
import com.mytvb.core.ui.video.VideoLightCardFactory
import com.mytvb.feature.hongguo.net.HongguoClient
import com.mytvb.ui.adapter.VideoAdapter

/** 红果卡片统一 UI 模型：feed/搜索/历史/收藏/榜单各自映射成它，渲染全走 [HongguoDramaAdapter]。 */
data class DramaUi(
    val seriesId: String,
    val title: String,
    val cover: String,
    /** 副标题行（owner 位）：如「8.6 · 共80集 · 热度5216万」「看到3/80集」、榜单「玄幻 · 3D · 玄幻仙侠」。 */
    val subtitle: String = "",
    /** 封面观看进度（0-100），>0 时画进度条。 */
    val progressPercent: Int = 0,
    /** 榜单排名（>0 时左上纯数字徽章：前三名★+金银铜，第 4 起主题色）；无排名时左上显示 [statusTag]。 */
    val rank: Int = 0,
    /** 状态标签（爆剧/新剧…），rank=0 时占左上；rank>0 时仅「新剧」保留在右上（上新提示）。 */
    val statusTag: String = "",
    /** 封面底部左侧热度（如「965万」，压渐变白字）。 */
    val heatText: String = "",
    /** 封面底部右侧「共 N 集」。 */
    val episodeCount: Int = 0,
    /** 右上季数标签（如「第 1 季」，多季剧才有）。 */
    val seasonText: String = "",
    /** 评分原文（列表接口下发，详情 SSR 不下发）：详情弹窗头部评分用入口值兜底。 */
    val score: String = ""
)

/** [HongguoClient.Drama] → 卡片 UI 模型（副标题=评分/集数/热度，各列表页与长按菜单弹窗共用）。 */
fun HongguoClient.Drama.toDramaUi(): DramaUi = DramaUi(
    seriesId = seriesId,
    title = title,
    cover = cover,
    subtitle = listOf(
        score.takeIf { it.isNotBlank() },
        if (episodeCount > 0) "共${episodeCount}集" else null,
        heatText.ifEmpty { if (playCount > 0) "${playCount / 10000}万播放" else null }
    ).filterNotNull().joinToString(" · ")
)

/**
 * 红果剧卡片（复用全 App 统一 B 站卡片，2026-11-10 从自绘 cell_hongguo_drama 迁移）。
 *
 * 红果短剧封面是竖版海报（源图 700×1000），封面走 [VideoCardCoverRatio.POSTER_4_5]
 * （centerCrop 裁上下少许，与 CCTV 片库竖版同款）；16:9 横版会把海报主体裁没。
 * 文字层：标题单行（焦点长标题滚动由卡片工厂自带）+ 副标题行（无头像）。
 */
class HongguoDramaAdapter(
    /** position 供页面记录来源卡片（播放器返回后焦点恢复兜底）。 */
    private val onItemClick: (DramaUi, Int) -> Unit,
    /** 长按（遥控 OK 或触摸长按）弹出卡片菜单；null=宿主页面未接长按。 */
    private val onItemLongPress: ((DramaUi, Int) -> Unit)? = null,
    /** 固定卡宽（px）：网格页留空由 LayoutManager 均分；货架页横滑单行必须给定（卡根默认 MATCH_PARENT，横向列表会撑满整屏宽）。 */
    private val itemWidthPx: Int? = null
) : BaseVideoAdapter<DramaUi, HongguoDramaAdapter.ViewHolder>() {

    override fun itemKey(item: DramaUi): String = item.seriesId

    init {
        // 触底加载由页面滚动/焦点判定驱动，不用 BaseAdapter 的 footer cell
        setShowLoadMore(false)
    }

    /** 卡片顶行再往上时的落点（回红果 tab 栏）。 */
    fun bindTopEdgeUp(callback: () -> Boolean) {
        onTopEdgeUp = callback
    }

    /** 焦点落到卡片时的回调（TvListFocusController 记锚点/触发触底加载）。 */
    fun setOnItemFocusListeners(
        onFocused: (Int) -> Unit,
        onFocusedWithView: (View, Int) -> Unit
    ) {
        onItemFocused = onFocused
        onItemFocusedWithView = onFocusedWithView
    }

    override fun onCreateContentViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val views = VideoCardPerfLogger.measureInflate("HongguoDramaAdapter.light") {
            VideoLightCardFactory.create(
                parent = parent,
                source = "HongguoDramaAdapter.light",
                coverRatio = VideoCardCoverRatio.POSTER_4_5
            )
        }
        itemWidthPx?.let { width ->
            views.root.layoutParams = ViewGroup.LayoutParams(
                width, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        return ViewHolder(views)
    }

    override fun onBindContentViewHolder(holder: ViewHolder, position: Int) {
        getItem(position)?.let(holder::bind)
    }

    inner class ViewHolder(
        private val views: VideoCardViews
    ) : BaseVideoViewHolder(views.root) {

        private var currentItem: DramaUi? = null

        /** 遥控 OK 长按检测（与 VideoAdapter.VideoViewHolder 同款：经 bindSidebarExit 尾部透传收非边缘键）。 */
        private val keyListener = View.OnKeyListener { _, keyCode, event ->
            if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                keyCode == android.view.KeyEvent.KEYCODE_ENTER
            ) {
                when (event.action) {
                    android.view.KeyEvent.ACTION_DOWN -> {
                        if (event.repeatCount == 0) startLongPress()
                    }
                    android.view.KeyEvent.ACTION_UP -> cancelLongPress()
                }
                false
            } else {
                false
            }
        }

        override fun showCardMenu() {
            cancelLongPress()
            val item = currentItem ?: return
            val position = bindingAdapterPosition
            onItemLongPress?.invoke(item, position)
        }

        init {
            views.imageView.clipToOutline = true
            views.imageView.outlineProvider =
                VideoAdapter.VideoViewHolder.coverOutlineProviderFor(views.imageView.resources)
            views.root.setOnClickListener {
                // 长按已触发时吞掉紧随的点击（VideoAdapter 同款）
                if (longPressTriggered) {
                    longPressTriggered = false
                    return@setOnClickListener
                }
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    getItem(position)?.let { onItemClick(it, position) }
                }
            }
            @android.annotation.SuppressLint("ClickableViewAccessibility")
            views.root.setOnTouchListener { _, event ->
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN -> startLongPress()
                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL -> cancelLongPress()
                }
                false
            }
            views.root.onFocusChangeListener = View.OnFocusChangeListener { view, hasFocus ->
                if (!hasFocus) return@OnFocusChangeListener
                val position = bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) return@OnFocusChangeListener
                onItemFocused?.invoke(position)
                onItemFocusedWithView?.invoke(view, position)
            }
            VideoCardFocusHelper.bindSidebarExit(
                view = views.root,
                onTopEdgeUp = { onTopEdgeUp?.invoke() == true },
                chainedListener = keyListener
            )
        }

        fun bind(item: DramaUi) {
            currentItem = item
            views.textLayer.setTitle(item.title, lines = 1)
            views.textLayer.setOwner(
                ownerText = item.subtitle,
                showAvatar = false,
                show = true
            )
            views.textLayer.clearHistoryTrailing()
            if (item.progressPercent > 0) {
                views.progressBar.max = 100
                views.progressBar.progress = item.progressPercent.coerceAtMost(100)
                views.progressBar.visibility = View.VISIBLE
            } else {
                views.progressBar.visibility = View.GONE
            }
            val hasCoverMeta = item.rank > 0 || item.statusTag.isNotBlank() ||
                item.heatText.isNotBlank() || item.episodeCount > 0 || item.seasonText.isNotBlank()
            if (hasCoverMeta) {
                // 槽位口径（2026-11-11 重设计定案①③）：左上=名次/状态二选一，右上=季/更新，
                // 底部左热度右集数（去「共」字）。名次徽章纯数字保障与右上角标并列不超卡宽；
                // 前三名金银铜浅底深字（独立语义色），第 4 名起主题强调色底深字。
                // 状态标（爆剧/漫剧紫）让位给名次徽章：榜单卡右上仅保留「新剧」（上新提示语义），
                // 其余状态只用于非榜单场景（rank=0 的 feed/搜索/我的页）。
                val ctx = views.root.context
                val accent = com.google.android.material.color.MaterialColors.getColor(
                    views.root, androidx.appcompat.R.attr.colorAccent
                )
                val gold = androidx.core.content.ContextCompat.getColor(ctx, R.color.hongguo_rank_gold)
                val silver = androidx.core.content.ContextCompat.getColor(ctx, R.color.hongguo_rank_silver)
                val bronze = androidx.core.content.ContextCompat.getColor(ctx, R.color.hongguo_rank_bronze)
                    val (leftText, leftColor, leftTextColor) = if (item.rank > 0) {
                        when (item.rank) {
                            1 -> Triple("★1", gold, androidx.core.content.ContextCompat.getColor(ctx, R.color.hongguo_badge_text_gold))
                            2 -> Triple("★2", silver, androidx.core.content.ContextCompat.getColor(ctx, R.color.hongguo_badge_text_silver))
                            3 -> Triple("★3", bronze, androidx.core.content.ContextCompat.getColor(ctx, R.color.hongguo_badge_text_bronze))
                            // 第 4 名起主题色实底白字（对齐主钮白字惯例；深字对比不足已废弃）
                            else -> Triple(item.rank.toString(), accent, android.graphics.Color.WHITE)
                        }
                    } else {
                    when (item.statusTag) {
                        "爆剧" -> Triple(item.statusTag, 0xFFFF4D26.toInt(), android.graphics.Color.WHITE)
                        "新剧" -> Triple(item.statusTag, 0xFF3FA45B.toInt(), android.graphics.Color.WHITE)
                        "" -> Triple("", 0x66000000.toInt(), android.graphics.Color.WHITE)
                        else -> Triple(item.statusTag, 0xFF6A5AE0.toInt(), android.graphics.Color.WHITE)
                    }
                }
                val rightStatus = if (item.rank > 0 && item.statusTag == "新剧") item.statusTag else ""
                views.coverMetaOverlay.visibility = View.VISIBLE
                views.coverMetaOverlay.bind(
                    playCountText = item.heatText,
                    showPlayCount = item.heatText.isNotBlank(),
                    showDanmakuCount = false,
                    showInteractionBadge = false,
                    showChargeBadge = false,
                    durationText = if (item.episodeCount > 0) "${item.episodeCount}集" else "",
                    rankBadgeText = leftText,
                    rankBadgeColor = leftColor,
                    rankBadgeTextColor = leftTextColor,
                    seasonBadgeText = item.seasonText,
                    seasonBadgeColor = 0x66000000.toInt(),
                    statusBadgeText = rightStatus,
                    statusBadgeColor = if (rightStatus == "新剧") 0xFF3FA45B.toInt() else 0x66000000.toInt()
                )
            } else {
                views.coverMetaOverlay.visibility = View.GONE
            }
            views.imageView.clearColorFilter()
            // 竖版剧海报用 series 通道：自带 px15 圆角 outline 裁剪（load 无圆角参数）
            ImageLoader.loadSeriesCover(
                imageView = views.imageView,
                url = item.cover,
                placeholder = R.drawable.default_video,
                error = R.drawable.default_video,
                priority = ImageLoader.Priority.VISIBLE_HIGH
            )
        }
    }
}
