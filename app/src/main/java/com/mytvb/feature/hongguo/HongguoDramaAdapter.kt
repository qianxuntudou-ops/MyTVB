package com.mytvb.feature.hongguo

import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.mytvb.R
import com.mytvb.core.common.log.VideoCardPerfLogger
import com.mytvb.core.ui.base.BaseVideoAdapter
import com.mytvb.core.ui.focus.VideoCardFocusHelper
import com.mytvb.core.ui.image.ImageLoader
import com.mytvb.core.ui.video.VideoCardCoverRatio
import com.mytvb.core.ui.video.VideoCardViews
import com.mytvb.core.ui.video.VideoLightCardFactory
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
    /** 榜单排名（>0 时左上角标「第 N 名」，橙底白字）；无排名时左上显示 [statusTag]。 */
    val rank: Int = 0,
    /** 状态标签（爆剧/新剧…），左上角标位，rank 优先。 */
    val statusTag: String = "",
    /** 封面底部左侧热度（如「965万」，压渐变白字）。 */
    val heatText: String = "",
    /** 封面底部右侧「共 N 集」。 */
    val episodeCount: Int = 0,
    /** 右上季数标签（如「第 1 季」，多季剧才有）。 */
    val seasonText: String = ""
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
    private val onItemClick: (DramaUi, Int) -> Unit
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
        return ViewHolder(views)
    }

    override fun onBindContentViewHolder(holder: ViewHolder, position: Int) {
        getItem(position)?.let(holder::bind)
    }

    inner class ViewHolder(
        private val views: VideoCardViews
    ) : RecyclerView.ViewHolder(views.root) {

        init {
            views.imageView.clipToOutline = true
            views.imageView.outlineProvider =
                VideoAdapter.VideoViewHolder.coverOutlineProviderFor(views.imageView.resources)
            views.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    getItem(position)?.let { onItemClick(it, position) }
                }
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
                onTopEdgeUp = { onTopEdgeUp?.invoke() == true }
            )
        }

        fun bind(item: DramaUi) {
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
                // 对齐红果官方卡：左上 flush 角标板——排名（橙底白字）优先，无排名显状态标签
                // （爆剧红橙/新剧绿/其他紫）；右上 flush 角标链——状态标签在其左、季数贴角；
                // 底部左热度、右共 N 集（压渐变白字）
                val (leftText, leftColor) = if (item.rank > 0) {
                    "第${item.rank}名" to 0xFFFF5C1F.toInt()
                } else {
                    when (item.statusTag) {
                        "爆剧" -> item.statusTag to 0xFFFF4D26.toInt()
                        "新剧" -> item.statusTag to 0xFF3FA45B.toInt()
                        "" -> "" to 0x66000000.toInt()
                        else -> item.statusTag to 0xFF6A5AE0.toInt()
                    }
                }
                val statusTaken = item.rank > 0
                views.coverMetaOverlay.visibility = View.VISIBLE
                views.coverMetaOverlay.bind(
                    playCountText = item.heatText,
                    showPlayCount = item.heatText.isNotBlank(),
                    showDanmakuCount = false,
                    showInteractionBadge = false,
                    showChargeBadge = false,
                    durationText = if (item.episodeCount > 0) "共${item.episodeCount}集" else "",
                    rankBadgeText = leftText,
                    rankBadgeColor = leftColor,
                    seasonBadgeText = item.seasonText,
                    seasonBadgeColor = 0x66000000.toInt(),
                    statusBadgeText = if (statusTaken) item.statusTag else "",
                    statusBadgeColor = when (item.statusTag) {
                        "爆剧" -> 0xFFFF4D26.toInt()
                        "新剧" -> 0xFF3FA45B.toInt()
                        else -> 0x66000000.toInt()
                    }
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
