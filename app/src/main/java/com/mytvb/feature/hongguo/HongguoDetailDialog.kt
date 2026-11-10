package com.mytvb.feature.hongguo

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.Window
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
 * + 选集区（分页 chip 20集/页 + 10 列网格，正在看集白底高亮）。
 * 焦点链：按钮行 ↓ 落分页/选集；选集与分页顶行 ↑ 回按钮行；BACK 关弹窗。
 */
class HongguoDetailDialog(
    context: Context,
    private val seriesId: String,
    private val repository: HongguoRepository
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
        binding.buttonPlay.setOnClickListener { playEpisode(lastEpisodeIndex) }
        binding.buttonFavorite.setOnClickListener { toggleFavorite() }
        binding.buttonWatchLater.setOnClickListener { toggleWatchLater() }
        load()
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
                binding.textTags.text = listOf(
                    d.drama.score.takeIf { it.isNotBlank() }?.let { "评分 $it" },
                    d.drama.tags.take(4).joinToString(" · ").takeIf { it.isNotBlank() }
                ).filterNotNull().joinToString(" · ")
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
                pageChipAdapter.submit(pageRanges())
                showPage(pageForEpisode(lastEpisodeIndex))
                binding.buttonPlay.requestFocus()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppToast.show(context, e.message ?: "详情加载失败")
                dismiss()
            }
        }
    }

    // ---- 按钮行 ----

    private fun watchRecord(d: HongguoWeb.SeriesDetail) = HongguoRepository.WatchRecord(
        d.drama.seriesId, d.drama.title, d.drama.cover,
        lastEpisodeIndex - 1, d.episodes.size, 0L, 0L, System.currentTimeMillis()
    )

    private fun refreshButtons() {
        val d = detail ?: return
        binding.textButtonPlay.text = "继续播放 第${lastEpisodeIndex}集"
        binding.buttonFavorite.text =
            if (repository.isFavorite(seriesId)) R.string.hongguo_unfavorite.asText() else R.string.hongguo_favorite.asText()
        binding.buttonWatchLater.text =
            if (repository.isWatchLater(seriesId)) "已稍后看" else R.string.hongguo_watch_later.asText()
    }

    private fun toggleFavorite() {
        val d = detail ?: return
        val added = repository.toggleFavorite(watchRecord(d))
        binding.buttonFavorite.text =
            if (added) R.string.hongguo_unfavorite.asText() else R.string.hongguo_favorite.asText()
    }

    private fun toggleWatchLater() {
        val d = detail ?: return
        val added = repository.toggleWatchLater(watchRecord(d))
        binding.buttonWatchLater.text = if (added) "已稍后看" else R.string.hongguo_watch_later.asText()
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

    /** 切页：更新网格窗口 + 页范围文案；当前页 chip 高亮。 */
    private fun showPage(newStart: Int) {
        pageStart = newStart
        pageChipAdapter.notifyDataSetChanged()
        val d = detail ?: return
        val end = minOf(pageStart + PAGE_SIZE, d.episodes.size)
        binding.textPageRange.text = "第 ${pageStart + 1}-${end} 集 · 共 ${d.episodes.size} 集"
        episodeAdapter.notifyDataSetChanged()
        binding.recyclerEpisodes.scrollToPosition(0)
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
                .inflate(R.layout.cell_hongguo_episode, parent, false) as android.widget.TextView
            return EpisodeHolder(view)
        }

        override fun onBindViewHolder(holder: EpisodeHolder, position: Int) {
            val d = detail ?: return
            val episode = d.episodes.getOrNull(pageStart + position) ?: return
            val watching = episode.index == lastEpisodeIndex
            holder.cell.text = "${episode.index}"
            holder.cell.isSelected = watching
            holder.cell.setTextColor(if (watching) 0xDD000000.toInt() else Color.WHITE)
            holder.cell.setOnClickListener { playEpisode(episode.index) }
            // 顶行 ↑ 回按钮行（防框架导航逃逸）
            holder.cell.setOnKeyListener { _, keyCode, event ->
                keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP &&
                    event.action == android.view.KeyEvent.ACTION_DOWN &&
                    position < EPISODE_COLUMNS &&
                    (binding.buttonPlay.requestFocus() || true)
            }
        }
    }

    class EpisodeHolder(val cell: android.widget.TextView) : RecyclerView.ViewHolder(cell)

    // ---- 分页 chip ----

    inner class PageChipAdapter : RecyclerView.Adapter<PageChipHolder>() {
        private var ranges: List<Pair<Int, Int>> = emptyList()

        fun submit(ranges: List<Pair<Int, Int>>) {
            this.ranges = ranges
            notifyDataSetChanged()
        }

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
            holder.chip.setTextColor(if (current) 0xDD000000.toInt() else Color.WHITE)
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
        private const val EPISODE_COLUMNS = 10
        private const val PAGE_SIZE = 20

        fun show(context: Context, repository: HongguoRepository, seriesId: String) {
            HongguoDetailDialog(context, seriesId, repository).show()
        }
    }
}
