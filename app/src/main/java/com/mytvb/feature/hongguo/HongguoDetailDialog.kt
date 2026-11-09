package com.mytvb.feature.hongguo

import android.content.Context
import android.os.Bundle
import android.view.Window
import android.widget.Button
import androidx.appcompat.app.AppCompatDialog
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mytvb.R
import com.mytvb.core.ui.base.AppToast
import com.mytvb.core.ui.base.DialogWindowFit
import com.mytvb.core.ui.image.ImageLoader
import com.mytvb.databinding.DialogHongguoDetailBinding
import com.mytvb.feature.hongguo.net.HongguoWeb
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 红果详情弹窗（对齐 CctvVodDetailDialog 结构）：封面/简介/继续播放/收藏 + 选集网格。
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
    private val episodeAdapter = EpisodeAdapter()

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
        binding.recyclerEpisodes.layoutManager = GridLayoutManager(context, 10)
        binding.recyclerEpisodes.adapter = episodeAdapter
        binding.buttonPlay.setOnClickListener { playEpisode(lastEpisodeIndex) }
        binding.buttonFavorite.setOnClickListener { toggleFavorite() }
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
                binding.textMeta.text = buildString {
                    if (d.drama.score.isNotEmpty()) append(d.drama.score).append(" · ")
                    append(d.episodes.size).append("集")
                    if (d.drama.tags.isNotEmpty()) append(" · ").append(d.drama.tags.take(3).joinToString(" "))
                }
                binding.textIntro.text = d.drama.intro
                binding.textEpisodeHeader.text = "选集 · 共${d.episodes.size}集"
                ImageLoader.load(binding.imageCover, d.drama.cover)
                episodeAdapter.notifyDataSetChanged()
                binding.buttonPlay.text = "继续播放 第${lastEpisodeIndex}集"
                binding.buttonFavorite.text = if (repository.isFavorite(seriesId)) "取消收藏" else "收藏"
                binding.buttonPlay.requestFocus()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppToast.show(context, e.message ?: "详情加载失败")
                dismiss()
            }
        }
    }

    private fun toggleFavorite() {
        val d = detail ?: return
        val record = HongguoRepository.WatchRecord(
            d.drama.seriesId, d.drama.title, d.drama.cover,
            lastEpisodeIndex - 1, d.episodes.size, 0L, 0L, System.currentTimeMillis()
        )
        val added = repository.toggleFavorite(record)
        binding.buttonFavorite.text = if (added) "取消收藏" else "收藏"
    }

    private fun playEpisode(index: Int) {
        val d = detail ?: return
        val episode = d.episodes.getOrNull(index - 1) ?: return
        val resumeMs = repository.progressOf(d.drama.seriesId)
            ?.takeIf { it.episodeIndex == index }?.positionMs ?: 0L
        HongguoPlayerActivity.start(
            context, d.drama.seriesId, d.drama.title,
            episode.videoId, index, d.episodes.size, resumeMs
        )
    }

    inner class EpisodeAdapter : RecyclerView.Adapter<EpisodeHolder>() {
        override fun getItemCount(): Int = detail?.episodes?.size ?: 0
        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): EpisodeHolder {
            val btn = Button(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT
                )
                textSize = 16f
                isAllCaps = false
            }
            return EpisodeHolder(btn)
        }

        override fun onBindViewHolder(holder: EpisodeHolder, position: Int) {
            val episode = detail?.episodes?.get(position) ?: return
            holder.button.text = "${episode.index}"
            holder.button.setOnClickListener { playEpisode(episode.index) }
        }
    }

    class EpisodeHolder(val button: Button) : RecyclerView.ViewHolder(button)

    companion object {
        fun show(context: Context, repository: HongguoRepository, seriesId: String) {
            HongguoDetailDialog(context, seriesId, repository).show()
        }
    }
}
