package com.mytvb.feature.player

import com.mytvb.core.ui.video.resolveThemedTextColor

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.mytvb.R
import com.mytvb.core.common.format.NumberUtils
import com.mytvb.core.ui.image.ImageLoader
import com.mytvb.databinding.CellChapterListBinding
import com.mytvb.model.player.ViewPoint

/**
 * 章节面板列表（view_points）：缩略图 + 标题 + 时间范围，
 * 当前章显示 playing 角标并在时间行标注「正在播放」，点击回调直接 seek 章节起点。
 */
class ChapterPanelAdapter(
    private val onClick: (ViewPoint) -> Unit
) : RecyclerView.Adapter<ChapterPanelAdapter.ChapterViewHolder>() {

    private val items = mutableListOf<ViewPoint>()
    private var currentIndex: Int = -1

    fun submitList(newItems: List<ViewPoint>, currentIndex: Int) {
        items.clear()
        items.addAll(newItems)
        this.currentIndex = currentIndex
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChapterViewHolder {
        val binding = CellChapterListBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ChapterViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ChapterViewHolder, position: Int) {
        holder.bind(
            item = items[position],
            isCurrent = position == currentIndex,
            onClick = { onClick(items[position]) }
        )
    }

    override fun getItemCount(): Int = items.size

    class ChapterViewHolder(
        private val binding: CellChapterListBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private val pinkColor = ContextCompat.getColor(binding.root.context, R.color.pink)
        private val defaultTitleColor = resolveThemedTextColor(binding.root.context, android.R.attr.textColorPrimary, 0xFFEEEEEE.toInt())

        fun bind(
            item: ViewPoint,
            isCurrent: Boolean,
            onClick: () -> Unit
        ) {
            val context = binding.root.context
            binding.textTitle.text = item.content
            val range = "${NumberUtils.formatTimeMs(item.fromMs)} - ${NumberUtils.formatTimeMs(item.toMs)}"
            binding.textRange.text = if (isCurrent) {
                "$range · ${context.getString(R.string.chapter_playing)}"
            } else {
                range
            }
            if (item.img.isNotBlank()) {
                ImageLoader.load(binding.imageChapterThumb, item.img)
            } else {
                ImageLoader.clear(binding.imageChapterThumb)
            }
            if (isCurrent) {
                binding.iconPlaying.visibility = View.VISIBLE
                ImageLoader.loadDrawableRes(binding.iconPlaying, R.drawable.playing)
                binding.textTitle.setTextColor(pinkColor)
            } else {
                binding.iconPlaying.visibility = View.GONE
                ImageLoader.clear(binding.iconPlaying)
                binding.textTitle.setTextColor(defaultTitleColor)
            }
            binding.root.isSelected = isCurrent
            binding.clickView.setOnClickListener { onClick() }
            binding.clickView.isFocusable = true
            binding.clickView.isClickable = true
            // 焦点项与当前章都开启跑马灯（对齐选集面板）
            binding.clickView.setOnFocusChangeListener { _, hasFocus ->
                binding.textTitle.isSelected = hasFocus || isCurrent
            }
            binding.textTitle.isSelected = binding.clickView.hasFocus() || isCurrent
        }
    }
}
