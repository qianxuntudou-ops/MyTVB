package com.tutu.myblbl.feature.player

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.KeyEvent
import android.view.ViewOutlineProvider
import android.graphics.Outline
import androidx.recyclerview.widget.RecyclerView
import androidx.media3.common.util.UnstableApi
import com.tutu.myblbl.core.ui.image.ImageLoader
import com.tutu.myblbl.R
import com.tutu.myblbl.core.common.time.TimeUtils
import com.tutu.myblbl.core.common.format.NumberUtils
import com.tutu.myblbl.databinding.CellSeasonEpisodeItemBinding

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
            binding.clickView.setOnKeyListener { v, keyCode, event ->
                com.tutu.myblbl.core.common.log.AppLog.d(
                    "SeasonPanel",
                    "item onKey v=${v::class.java.simpleName} keyCode=$keyCode action=${event.action} pos=$bindingAdapterPosition repeat=${event.repeatCount}"
                )
                when (keyCode) {
                    KeyEvent.KEYCODE_BACK -> {
                        if (event.action == KeyEvent.ACTION_DOWN ||
                            event.action == KeyEvent.ACTION_UP
                        ) {
                            onBackToTab()
                            return@setOnKeyListener true
                        }
                        false
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        if (event.action == KeyEvent.ACTION_UP &&
                            bindingAdapterPosition == 0
                        ) {
                            onBackToTab()
                            return@setOnKeyListener true
                        }
                        false
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
            ImageLoader.load(
                binding.imageCover,
                item.cover,
                R.drawable.default_video,
                R.drawable.default_video
            )
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
            binding.textDuration.text = NumberUtils.formatDuration(item.duration)
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
