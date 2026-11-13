package com.mytvb.feature.hongguo

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.Window
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatDialog
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mytvb.R
import com.mytvb.core.ui.base.AppToast
import com.mytvb.core.ui.base.DialogWindowFit
import com.mytvb.core.ui.decoration.GridSpacingItemDecoration
import com.mytvb.core.ui.image.ImageLoader
import com.mytvb.databinding.DialogHongguoDetailBinding
import com.mytvb.feature.hongguo.net.HongguoWeb
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 红果详情弹窗（对照红果官方详情页）：左海报 + 右信息/三按钮（继续播放/收藏/稍后看）
 * + 选集区（分页 chip 20集/页 + 8 列网格，正在看集主题色高亮）。
 * 焦点链：按钮行 ↓ 落分页/选集；选集与分页顶行 ↑ 回按钮行；BACK 关弹窗。
 *
 * [scoreHint] 由入口卡片带过来：详情 SSR 的 seriesDetail 不下发评分（实测无 score 字段，
 * 列表接口才有），入口有评分时用它兜底，否则评分行隐藏。
 */
class HongguoDetailDialog(
    context: Context,
    private val seriesId: String,
    private val repository: HongguoRepository,
    private val scoreHint: String = ""
) : AppCompatDialog(context, R.style.DialogTheme) {

    private lateinit var binding: DialogHongguoDetailBinding
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var detail: HongguoWeb.SeriesDetail? = null
    private var lastEpisodeIndex = 1
    private var pageStart = 0   // 当前页首集序（0-based）
    private val episodeAdapter = EpisodeAdapter()
    private val pageChipAdapter = PageChipAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        binding = DialogHongguoDetailBinding.inflate(layoutInflater)
        DialogWindowFit.overlay(
            dialog = this,
            content = binding.root,
            widthPx = context.resources.getDimensionPixelSize(R.dimen.px1400),
            heightPx = context.resources.getDimensionPixelSize(R.dimen.px900),
            tag = "hongguo_detail"
        )
        binding.imageCover.clipToOutline = true
        binding.imageCover.outlineProvider =
            com.mytvb.ui.adapter.VideoAdapter.VideoViewHolder.coverOutlineProviderFor(binding.imageCover.resources)
        binding.recyclerEpisodes.layoutManager = GridLayoutManager(context, EPISODE_COLUMNS)
        binding.recyclerEpisodes.adapter = episodeAdapter
        binding.recyclerEpisodes.itemAnimator = null
        binding.recyclerEpisodes.addItemDecoration(
            GridSpacingItemDecoration(
                EPISODE_COLUMNS,
                context.resources.getDimensionPixelSize(R.dimen.px16),
                false
            )
        )
        binding.recyclerPageChips.layoutManager =
            LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
        binding.recyclerPageChips.adapter = pageChipAdapter
        // 换页只用 notifyItemChanged 重绑 chip；itemAnimator 的 change 动画会另建
        // holder，刚点过的 chip 焦点会跟着丢
        binding.recyclerPageChips.itemAnimator = null
        binding.buttonPlay.setOnClickListener { playEpisode(lastEpisodeIndex) }
        binding.buttonFavorite.setOnClickListener { toggleFavorite() }
        binding.buttonWatchLater.setOnClickListener { toggleWatchLater() }
        // 内容就绪前先把焦点钉在「继续播放」（不等网络，load 回调里还会再钉一次）
        requestInitialFocus()
        load()
    }

    /**
     * 触摸打开弹窗后（touch mode）整棵 view 树没有 focused view：系统只对 TAB 做焦点搜索，
     * 方向键不会自己去拉焦点，表现为「鼠标点开后按遥控焦点出不来」。
     * touch mode 下普通 requestFocus() 会因未设 focusableInTouchMode 被拒（项目铁律不允许设），
     * 这里用 requestFocusFromTouch()：先退出 touch mode 再聚焦，首键只用于唤醒焦点。
     */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (::binding.isInitialized &&
            event.action == android.view.KeyEvent.ACTION_DOWN &&
            isDirectionKey(event.keyCode) &&
            binding.root.findFocus() == null &&
            binding.buttonPlay.requestFocusFromTouch()
        ) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun isDirectionKey(keyCode: Int): Boolean = when (keyCode) {
        android.view.KeyEvent.KEYCODE_DPAD_UP,
        android.view.KeyEvent.KEYCODE_DPAD_DOWN,
        android.view.KeyEvent.KEYCODE_DPAD_LEFT,
        android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> true
        else -> false
    }

    private fun load() {
        scope.launch {
            try {
                val d = repository.detail(seriesId)
                detail = d
                repository.progressOf(seriesId)?.let {
                    lastEpisodeIndex = (it.episodeIndex + 1).coerceAtMost(d.episodes.size.coerceAtLeast(1))
                }
                binding.textTitle.text = d.drama.title
                // 评分单独强调（强调色）；无评分数据时整格隐藏，标签行不再带评分前缀
                val score = d.drama.score.ifBlank { scoreHint }.trim()
                val scoreValid = score.isNotBlank() && (score.toFloatOrNull() ?: 0f) > 0f
                binding.textScore.text = "$score 分"
                binding.textScore.visibility = if (scoreValid) View.VISIBLE else View.GONE
                binding.textTags.text = d.drama.tags.take(4).joinToString(" · ")
                binding.textIntro.text = d.drama.intro
                binding.textMeta.text = listOf(
                    d.drama.seasonIndex.takeIf { it > 0 }?.let { "第${it}季" },
                    "共${d.episodes.size}集"
                ).filterNotNull().joinToString(" · ")
                ImageLoader.load(
                    imageView = binding.imageCover,
                    url = d.drama.cover,
                    placeholder = R.drawable.default_video,
                    error = R.drawable.default_video
                )
                refreshButtons()
                refreshWatchingText()
                val ranges = pageRanges()
                // 不足一页的短剧不显示分页行（否则孤零零一个 chip 占一行）
                binding.recyclerPageChips.visibility =
                    if (ranges.size > 1) View.VISIBLE else View.GONE
                pageChipAdapter.submit(ranges)
                showPage(pageForEpisode(lastEpisodeIndex))
                applyAdaptiveHeight(d.episodes.size, ranges.size)
                requestInitialFocus()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppToast.show(context, e.message ?: "详情加载失败")
                dismiss()
            }
        }
    }

    /**
     * 初始焦点给「继续播放」。详情数据回来得早时（缓存命中）弹窗窗口可能还没 attach，
     * 一次性 requestFocus 会静默失败、焦点掉到选集格/chip 上——按帧重试到成功为止
     * （touch 打开时 requestFocus 在 touch mode 下本就无效，重试自然放弃，无副作用）。
     */
    private fun requestInitialFocus() {
        val target = binding.buttonPlay
        if (target.hasFocus()) return
        target.requestFocus()
        if (target.hasFocus()) return
        var tries = 0
        val retry = object : Runnable {
            override fun run() {
                if (!isShowing || target.hasFocus()) return
                target.requestFocus()
                if (!target.hasFocus() && ++tries < INITIAL_FOCUS_RETRIES) target.post(this)
            }
        }
        target.post(retry)
    }

    // ---- 按钮行 ----

    private fun watchRecord(d: HongguoWeb.SeriesDetail) = HongguoRepository.WatchRecord(
        d.drama.seriesId, d.drama.title, d.drama.cover,
        lastEpisodeIndex - 1, d.episodes.size, 0L, 0L, System.currentTimeMillis()
    )

    private fun refreshButtons() {
        val d = detail ?: return
        binding.textButtonPlay.text = "继续播放 第${lastEpisodeIndex}集"
        val favorite = repository.isFavorite(seriesId)
        val watchLater = repository.isWatchLater(seriesId)
        binding.buttonFavorite.text =
            if (favorite) R.string.hongguo_unfavorite.asText() else R.string.hongguo_favorite.asText()
        binding.buttonWatchLater.text =
            if (watchLater) "已稍后看" else R.string.hongguo_watch_later.asText()
        // 按钮选中态 = 已在收藏/稍后看（tab_round_background 的 tabBarHighlight 底），
        // 文案之外再给一层可扫视的状态反馈
        binding.buttonFavorite.isSelected = favorite
        binding.buttonWatchLater.isSelected = watchLater
    }

    private fun toggleFavorite() {
        val d = detail ?: return
        val added = repository.toggleFavorite(watchRecord(d))
        binding.buttonFavorite.text =
            if (added) R.string.hongguo_unfavorite.asText() else R.string.hongguo_favorite.asText()
        binding.buttonFavorite.isSelected = added
    }

    private fun toggleWatchLater() {
        val d = detail ?: return
        val added = repository.toggleWatchLater(watchRecord(d))
        binding.buttonWatchLater.text = if (added) "已稍后看" else R.string.hongguo_watch_later.asText()
        binding.buttonWatchLater.isSelected = added
        AppToast.show(context, if (added) "已加入稍后看" else "已从稍后看移除")
    }

    private fun playEpisode(index: Int) {
        val d = detail ?: return
        val episode = d.episodes.getOrNull(index - 1) ?: return
        val resumeMs = repository.progressOf(d.drama.seriesId)
            ?.takeIf { it.episodeIndex == index }?.positionMs ?: 0L
        HongguoPlayerActivity.start(
            context, d.drama.seriesId, d.drama.title,
            episode.videoId, index, d.episodes.size, resumeMs,
            cover = d.drama.cover
        )
        // 跳播放即关弹窗：退出播放直接回列表（对齐 B 站播放返回行为，不再多按一次 BACK）
        dismiss()
    }

    // ---- 选集分页 ----

    private fun pageRanges(): List<Pair<Int, Int>> {
        val d = detail ?: return emptyList()
        val total = d.episodes.size
        val out = ArrayList<Pair<Int, Int>>()
        var start = 0
        while (start < total) {
            out.add(start to minOf(start + PAGE_SIZE, total))
            start += PAGE_SIZE
        }
        return out
    }

    private fun pageForEpisode(episodeIndex: Int): Int =
        ((episodeIndex - 1).coerceAtLeast(0)) / PAGE_SIZE * PAGE_SIZE

    /**
     * 弹窗高度随集数收缩：短剧（几集/一页）不再留一大片空选集区。
     * 逐项按布局口径累加（padding + 头部 + 按钮行 + 选集标题 + 分页行 + 网格行），
     * 上限仍是设计值 px900；高度只改卡片 LayoutParams，窗口本身 fill 不受影响。
     */
    private fun applyAdaptiveHeight(total: Int, pages: Int) {
        val r = context.resources
        fun px(id: Int) = r.getDimensionPixelSize(id)
        val rows = (minOf(PAGE_SIZE, total) + EPISODE_COLUMNS - 1) / EPISODE_COLUMNS
        var h = px(R.dimen.px30) + px(R.dimen.px320) + px(R.dimen.px24) + px(R.dimen.px76) +
            px(R.dimen.px28) + px(R.dimen.px40) + px(R.dimen.px8) + px(R.dimen.px30)
        if (pages > 1) h += px(R.dimen.px16) + px(R.dimen.px60)
        h += rows * px(R.dimen.px68) + (rows - 1).coerceAtLeast(0) * px(R.dimen.px16)
        val lp = binding.root.layoutParams as? FrameLayout.LayoutParams ?: return
        val target = minOf(h, px(R.dimen.px900))
        if (lp.height == target) return
        lp.height = target
        binding.root.layoutParams = lp
    }

    /** 切页：更新网格窗口 + 页范围文案；当前页 chip 高亮。 */
    private fun showPage(newStart: Int) {
        val oldStart = pageStart
        pageStart = newStart
        // 只重绑换页涉及的两个 chip：notifyDataSetChanged 会把 chip 的 ViewHolder 整体重建，
        // 点完分页 tab 焦点跟着被回收（表现为"焦点消失"）
        val oldIdx = pageChipAdapter.indexOfPage(oldStart)
        val newIdx = pageChipAdapter.indexOfPage(newStart)
        if (oldIdx >= 0 && newIdx >= 0) {
            if (oldIdx != newIdx) pageChipAdapter.notifyItemChanged(oldIdx)
            pageChipAdapter.notifyItemChanged(newIdx)
        } else {
            pageChipAdapter.notifyDataSetChanged()
        }
        val d = detail ?: return
        val end = minOf(pageStart + PAGE_SIZE, d.episodes.size)
        binding.textPageRange.text = "第 ${pageStart + 1}-${end} 集 · 共 ${d.episodes.size} 集"
        episodeAdapter.notifyDataSetChanged()
        binding.recyclerEpisodes.scrollToPosition(0)
    }

    /** 选集区 ↑ 的落点：有分页行回当前页 chip，否则回按钮行（短剧单页形态）。 */
    private fun focusAboveEpisodes() {
        val rv = binding.recyclerPageChips
        val idx = pageChipAdapter.indexOfPage(pageStart)
        if (rv.visibility != View.VISIBLE || idx < 0) {
            binding.buttonPlay.requestFocus()
            return
        }
        val holder = rv.findViewHolderForAdapterPosition(idx)
        if (holder != null) {
            holder.itemView.requestFocus()
        } else {
            rv.scrollToPosition(idx)
            rv.post { rv.findViewHolderForAdapterPosition(idx)?.itemView?.requestFocus() }
        }
    }

    private fun refreshWatchingText() {
        val d = detail ?: return
        binding.textWatching.text = "正在看第 $lastEpisodeIndex 集 · 共 ${d.episodes.size} 集"
    }

    private fun Int.asText(): String = context.getString(this)

    // ---- 选集网格 ----

    inner class EpisodeAdapter : RecyclerView.Adapter<EpisodeHolder>() {

        override fun getItemCount(): Int {
            val d = detail ?: return 0
            return minOf(PAGE_SIZE, d.episodes.size - pageStart).coerceAtLeast(0)
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): EpisodeHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.cell_hongguo_episode, parent, false)
            return EpisodeHolder(view)
        }

        override fun onBindViewHolder(holder: EpisodeHolder, position: Int) {
            val d = detail ?: return
            val episode = d.episodes.getOrNull(pageStart + position) ?: return
            val watching = episode.index == lastEpisodeIndex
            holder.cell.text = "${episode.index}"
            // 8 列格宽（152dp）容不下「上次观看」角标 + 居中数字（CCTV 是 6 列 208dp），
            // 有角标时把数字下沉到角标下沿，避免被粉色标压住
            holder.cell.setPadding(
                0,
                if (watching) holder.root.resources.getDimensionPixelSize(R.dimen.px30) else 0,
                0,
                0
            )
            // 选中态挂在格子根上（四态背景 selector 在根），右上角同挂「上次观看」角标
            holder.root.isSelected = watching
            holder.lastWatched.visibility = if (watching) View.VISIBLE else View.GONE
            holder.root.setOnClickListener { playEpisode(episode.index) }
            // 顶行 ↑ 先回分页 chip（存在时），再往上才是按钮行——防框架导航逃逸
            holder.root.setOnKeyListener { _, keyCode, event ->
                if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP &&
                    event.action == android.view.KeyEvent.ACTION_DOWN &&
                    position < EPISODE_COLUMNS
                ) {
                    focusAboveEpisodes()
                    true
                } else {
                    false
                }
            }
        }
    }

    class EpisodeHolder(view: View) : RecyclerView.ViewHolder(view) {
        val root: View = view
        val cell: android.widget.TextView = view.findViewById(R.id.text_hongguo_episode)
        val lastWatched: android.widget.TextView = view.findViewById(R.id.text_last_watched)
    }

    // ---- 分页 chip ----

    inner class PageChipAdapter : RecyclerView.Adapter<PageChipHolder>() {
        private var ranges: List<Pair<Int, Int>> = emptyList()

        fun submit(ranges: List<Pair<Int, Int>>) {
            this.ranges = ranges
            notifyDataSetChanged()
        }

        /** 某页起始集序（0-based）对应的 chip 下标，未命中返回 -1。 */
        fun indexOfPage(start: Int): Int = ranges.indexOfFirst { it.first == start }

        override fun getItemCount(): Int = ranges.size

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): PageChipHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.cell_hongguo_page_chip, parent, false) as android.widget.TextView
            return PageChipHolder(view)
        }

        override fun onBindViewHolder(holder: PageChipHolder, position: Int) {
            val range = ranges.getOrNull(position) ?: return
            val current = pageStart == range.first
            holder.chip.text = "${range.first + 1}-${range.second}"
            holder.chip.isSelected = current
            holder.chip.setTextColor(Color.WHITE)
            holder.chip.setOnClickListener { showPage(range.first) }
            // 顶行 ↑ 回按钮行
            holder.chip.setOnKeyListener { _, keyCode, event ->
                keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP &&
                    event.action == android.view.KeyEvent.ACTION_DOWN &&
                    (binding.buttonPlay.requestFocus() || true)
            }
        }
    }

    class PageChipHolder(val chip: android.widget.TextView) : RecyclerView.ViewHolder(chip)

    companion object {
        private const val EPISODE_COLUMNS = 8
        private const val PAGE_SIZE = 20

        /** 初始聚焦按帧重试上限（约 0.3s），覆盖窗口 attach 前的失败窗口。 */
        private const val INITIAL_FOCUS_RETRIES = 20

        fun show(
            context: Context,
            repository: HongguoRepository,
            seriesId: String,
            scoreHint: String = ""
        ) {
            HongguoDetailDialog(context, seriesId, repository, scoreHint).show()
        }
    }
}
