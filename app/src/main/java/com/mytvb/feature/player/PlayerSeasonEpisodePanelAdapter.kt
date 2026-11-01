package com.mytvb.feature.player

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.KeyEvent
import android.view.ViewOutlineProvider
import android.graphics.Outline
import androidx.recyclerview.widget.RecyclerView
import androidx.media3.common.util.UnstableApi
import com.mytvb.core.ui.image.ImageLoader
import com.mytvb.core.common.log.AppLog
import com.mytvb.R
import com.mytvb.core.common.time.TimeUtils
import com.mytvb.core.common.format.NumberUtils
import com.mytvb.databinding.CellSeasonEpisodeItemBinding

/**
 * 合集弹窗右侧列表的单组（50 个一组）视频列表。
 * 编号为全集连续编号，[startIndexOffset] 为本组第一项的全局下标（第 2 组即 50）。
 */
@UnstableApi
class PlayerSeasonEpisodePanelAdapter(
    private val onClick: (globalIndex: Int) -> Unit,
    private val onBackToTab: () -> Unit
) : RecyclerView.Adapter<PlayerSeasonEpisodePanelAdapter.SeasonEpisodeViewHolder>() {

    private val items = mutableListOf<VideoPlayerViewModel.PlayableEpisode>()
    private var startIndexOffset: Int = 0
    private var selectedGlobalIndex: Int = -1

    fun submitGroup(
        newItems: List<VideoPlayerViewModel.PlayableEpisode>,
        offset: Int,
        selectedGlobalIndex: Int
    ) {
        items.clear()
        items.addAll(newItems)
        startIndexOffset = offset
        this.selectedGlobalIndex = selectedGlobalIndex
        notifyDataSetChanged()
    }

    fun setSelectedGlobalIndex(index: Int) {
        val old = selectedGlobalIndex
        selectedGlobalIndex = index
        val oldLocal = old - startIndexOffset
        if (oldLocal in items.indices) {
            notifyItemChanged(oldLocal)
        }
        val newLocal = selectedGlobalIndex - startIndexOffset
        if (newLocal in items.indices) {
            notifyItemChanged(newLocal)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SeasonEpisodeViewHolder {
        val binding = CellSeasonEpisodeItemBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return SeasonEpisodeViewHolder(binding, onBackToTab)
    }

    override fun onBindViewHolder(holder: SeasonEpisodeViewHolder, position: Int) {
        holder.bind(
            item = items[position],
            number = startIndexOffset + position + 1,
            globalIndex = startIndexOffset + position,
            isSelected = startIndexOffset + position == selectedGlobalIndex,
            onClick = { onClick(startIndexOffset + position) }
        )
    }

    override fun getItemCount(): Int = items.size

    class SeasonEpisodeViewHolder(
        private val binding: CellSeasonEpisodeItemBinding,
        private val onBackToTab: () -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            // 封面圆角与首页卡片一致（px15）
            binding.imageCover.clipToOutline = true
            binding.imageCover.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(
                        0,
                        0,
                        view.width,
                        view.height,
                        view.resources.getDimension(R.dimen.px15)
                    )
                }
            }
            // 列表内按返回键回到分组 tab（不关闭弹窗）；DOWN/UP 都消费，
            // 防止 DOWN 穿透后 Dialog 层开始 tracking、UP 时关闭弹窗。
            // 第一项按“上”同理回 tab。
            binding.clickView.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BACK) {
                    AppLog.i(
                        "SeasonPanel",
                        "item BACK action=${event.action} repeat=${event.repeatCount} " +
                            "pos=$bindingAdapterPosition focused=${binding.clickView.hasFocus()}"
                    )
                }
                when (keyCode) {
                    // BACK 不在 view 层拦截（MuMu Android 15 实测）：BACK 的
                    // DOWN 会到达 item listener，且同一按键内 dispatcher 的
                    // handleOnBackPressed 仍触发（view 层消费与否不影响）——
                    // 若在此消费并把焦点移到 tab，dispatcher 判定时焦点已不在
                    // 列表 → 一次按键直接关弹窗。全权交 backPressedDispatcher
                    // 的 handler 判定（其时焦点仍在列表项上未被移动）：列表内
                    // 回 tab、tab 上关闭。
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        // 仅首项响应：DOWN 即回 tab（DOWN 消费本身也阻止默认
                        // 焦点查找把焦点飞到列表外）。UP 只消费不触发——从
                        // 第 2 项按“上”时焦点移动发生在 DOWN，随后的 UP 已落在
                        // 首项 listener 上，若 UP 也触发会表现为“焦点刚移到
                        // 首项就自动跳回 tab”。
                        if (bindingAdapterPosition == 0) {
                            if (event.action == KeyEvent.ACTION_DOWN &&
                                event.repeatCount == 0
                            ) {
                                onBackToTab()
                            }
                            true
                        } else {
                            false
                        }
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        // 最后一项按“下”留在原地，避免焦点飞出列表
                        if (event.action == KeyEvent.ACTION_DOWN) {
                            val count = bindingAdapter?.itemCount ?: -1
                            if (count > 0 && bindingAdapterPosition == count - 1) {
                                return@setOnKeyListener true
                            }
                        }
                        false
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        // 纵向列表内左右无移动目标，消费掉避免焦点飞到 tab
                        true
                    }
                    else -> false
                }
            }
        }

        fun bind(
            item: VideoPlayerViewModel.PlayableEpisode,
            number: Int,
            globalIndex: Int,
            isSelected: Boolean,
            onClick: () -> Unit
        ) {
            binding.textIndex.text = "$number."
            binding.textTitle.text = item.title.ifBlank {
                binding.root.context.getString(R.string.choose_episode)
            }
            binding.textPubDate.text = TimeUtils.formatPubDate(item.pubDate)
            binding.textPubDate.visibility =
                if (item.pubDate > 0L) View.VISIBLE else View.INVISIBLE
            // 必须走 loadVideoCover（16:9 规格）：普通 load 拼方图规格 @480w_480h_1c，
            // 16:9 封面先被 CDN 居中裁成 1:1、再被 5:3 框 centerCrop 二次裁切，构图严重损失
            ImageLoader.loadVideoCover(binding.imageCover, item.cover)
            // 封面底部信息条：样式与首页推荐卡片一致（播放量/弹幕/时长）
            if (item.playCount > 0L) {
                binding.groupPlay.visibility = View.VISIBLE
                binding.textPlayCount.text = NumberUtils.formatCount(item.playCount)
            } else {
                binding.groupPlay.visibility = View.GONE
            }
            if (item.danmakuCount > 0L) {
                binding.groupDanmaku.visibility = View.VISIBLE
                binding.textDanmaku.text = NumberUtils.formatCount(item.danmakuCount)
            } else {
                binding.groupDanmaku.visibility = View.GONE
            }
            // 时长缺数据（如 PGC 剧集分集）时整块隐藏，不显示假的 00:00
            if (item.duration > 0L) {
                binding.textDuration.visibility = View.VISIBLE
                binding.textDuration.text = NumberUtils.formatDuration(item.duration)
            } else {
                binding.textDuration.visibility = View.GONE
            }
            if (isSelected) {
                binding.iconPlaying.visibility = View.VISIBLE
                ImageLoader.loadDrawableRes(binding.iconPlaying, R.drawable.playing)
            } else {
                binding.iconPlaying.visibility = View.GONE
                ImageLoader.clear(binding.iconPlaying)
            }
            binding.clickView.isSelected = isSelected
            binding.root.isSelected = isSelected
            binding.clickView.setOnClickListener { onClick() }
            binding.clickView.isFocusable = true
            binding.clickView.isClickable = true
            binding.clickView.setOnFocusChangeListener { _, hasFocus ->
                binding.textTitle.isSelected = hasFocus || isSelected
            }
            binding.textTitle.isSelected = binding.clickView.hasFocus() || isSelected
        }
    }
}
