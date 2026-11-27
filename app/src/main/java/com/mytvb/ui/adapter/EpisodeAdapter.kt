package com.mytvb.ui.adapter

import com.mytvb.core.ui.video.resolveThemedTextColor

import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ImageSpan
import android.view.KeyEvent
import android.view.ViewOutlineProvider
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.mytvb.R
import com.mytvb.core.ui.focus.tv.TvFocusableAdapter
import com.mytvb.databinding.CellEpisodeBinding
import com.mytvb.model.episode.EpisodeModel
import com.mytvb.core.ui.image.ImageLoader
import com.mytvb.core.ui.system.ScreenUtils
import pl.droidsonroids.gif.GifDrawable

class EpisodeAdapter(
    private val onEpisodeClick: (EpisodeModel) -> Unit,
    private val onEpisodeFocused: (() -> Unit)? = null,
    private val nextFocusUpId: Int? = null,
    private val rememberFocusedItem: Boolean = true,
    private val onVerticalKey: ((View, Int) -> Boolean)? = null,
    onItemFocusedWithView: ((View, Int) -> Unit)? = null,
    private val onItemDpad: ((View, Int, KeyEvent) -> Boolean)? = null
) : ListAdapter<EpisodeModel, EpisodeAdapter.EpisodeViewHolder>(DIFF_CALLBACK), TvFocusableAdapter {

    private var focusedPosition = RecyclerView.NO_POSITION
    private val itemFocusedWithView = onItemFocusedWithView

    /** 上次看到的那集：标题高亮 + 播放动图（与 UGC 详情页合集列表显示逻辑统一） */
    var currentEpId: Long = 0L

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<EpisodeModel>() {
            override fun areItemsTheSame(oldItem: EpisodeModel, newItem: EpisodeModel): Boolean {
                return when {
                    oldItem.id > 0L && newItem.id > 0L -> oldItem.id == newItem.id
                    oldItem.cid > 0L && newItem.cid > 0L -> oldItem.cid == newItem.cid
                    oldItem.aid > 0L && newItem.aid > 0L -> oldItem.aid == newItem.aid
                    else -> oldItem == newItem
                }
            }

            override fun areContentsTheSame(oldItem: EpisodeModel, newItem: EpisodeModel): Boolean {
                return oldItem == newItem
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EpisodeViewHolder {
        val binding = CellEpisodeBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return EpisodeViewHolder(
            binding = binding,
            onFocused = { view ->
            if (rememberFocusedItem) {
                focusedPosition = recyclerPositionOf(view)
            }
            itemFocusedWithView?.invoke(view, recyclerPositionOf(view))
            onEpisodeFocused?.invoke()
            },
            onVerticalKey = onVerticalKey,
            onItemDpad = onItemDpad
        ).also { holder ->
            holder.nextFocusUpId = nextFocusUpId
        }
    }

    override fun submitList(list: List<EpisodeModel>?) {
        focusedPosition = focusedPosition
            .takeIf { it != RecyclerView.NO_POSITION && it < (list?.size ?: 0) }
            ?: RecyclerView.NO_POSITION
        super.submitList(list)
    }

    override fun focusableItemCount(): Int = itemCount

    override fun stableKeyAt(position: Int): String? {
        return "ep:${currentList.getOrNull(position)?.id ?: 0L}"
    }

    override fun findPositionByStableKey(key: String): Int {
        if (!key.startsWith("ep:")) return RecyclerView.NO_POSITION
        val id = key.removePrefix("ep:").toLongOrNull() ?: return RecyclerView.NO_POSITION
        return currentList.indexOfFirst { it.id == id }
            .takeIf { it >= 0 }
            ?: RecyclerView.NO_POSITION
    }

    fun reverse() {
        super.submitList(currentList.reversed())
    }

    fun requestStoredItemFocus(recyclerView: RecyclerView): Boolean {
        if (!rememberFocusedItem) {
            return false
        }
        val position = focusedPosition
        if (position == RecyclerView.NO_POSITION) {
            return false
        }
        return (recyclerView.findViewHolderForAdapterPosition(position) as? EpisodeViewHolder)
            ?.requestFocus() == true
    }

    override fun onBindViewHolder(holder: EpisodeViewHolder, position: Int) {
        val episode = getItem(position)
        holder.bind(episode, onEpisodeClick, episode.id == currentEpId)
    }

    private fun recyclerPositionOf(view: View): Int {
        return (view.parent as? RecyclerView)?.getChildAdapterPosition(view) ?: RecyclerView.NO_POSITION
    }

    class EpisodeViewHolder(
        private val binding: CellEpisodeBinding,
        private val onFocused: (View) -> Unit,
        private val onVerticalKey: ((View, Int) -> Boolean)?,
        private val onItemDpad: ((View, Int, KeyEvent) -> Boolean)? = null
    ) : RecyclerView.ViewHolder(binding.root) {

        var nextFocusUpId: Int? = null

        // 布局默认文字色（含 state），退出"当前集"态时还原
        private val defaultTitleColors = binding.textTitle.textColors
        private val defaultPositionColors = binding.textPosition.textColors
        private var playingDrawable: GifDrawable? = null
        private val playingAnimCallback = object : Drawable.Callback {
            override fun invalidateDrawable(d: Drawable) {
                // GIF 每推进一帧回调这里，驱动 TextView 重绘下一帧
                binding.textTitle.invalidate()
            }

            override fun scheduleDrawable(d: Drawable, what: Runnable, t: Long) {}
            override fun unscheduleDrawable(d: Drawable, what: Runnable) {}
        }

        init {
            val coverRadiusPx = binding.imageView.resources.getDimension(R.dimen.px15)
            binding.imageView.clipToOutline = true
            binding.imageView.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, coverRadiusPx)
                }
            }
            binding.root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {}

                override fun onViewDetachedFromWindow(v: View) {
                    // 离屏停掉播放动图，释放解码开销
                    playingDrawable?.stop()
                }
            })
        }

        fun requestFocus(): Boolean = binding.clickView.requestFocus()

        fun bind(episode: EpisodeModel, onClick: (EpisodeModel) -> Unit, isCurrent: Boolean) {
            applyDetailCardWidth(binding.clickView)
            binding.clickView.nextFocusUpId = nextFocusUpId ?: View.NO_ID
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
            ImageLoader.loadVideoCover(binding.imageView, episode.cover)
            binding.textPosition.text = episode.title.ifBlank {
                binding.root.context.getString(R.string.choose_episode)
            }

            bindTitle(episode, isCurrent)

            val badgeText = episode.badgeInfo?.text?.takeIf { it.isNotBlank() } ?: episode.badge
            if (badgeText.isBlank()) {
                binding.textBadge.visibility = View.GONE
                binding.textBadge.text = ""
            } else {
                binding.textBadge.visibility = View.VISIBLE
                binding.textBadge.text = badgeText
                applyBadgeBackground(episode.badgeInfo?.bgColorNight ?: episode.badgeInfo?.bgColor)
            }

            binding.iconPlay.visibility = View.GONE
            binding.clickView.setOnClickListener { onClick(episode) }
            binding.clickView.setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus) {
                    onFocused(view)
                }
            }
        }

        private fun bindTitle(episode: EpisodeModel, isCurrent: Boolean) {
            val longTitle = episode.longTitle.trim()
            if (!isCurrent) {
                playingDrawable?.stop()
                binding.textTitle.setTextColor(defaultTitleColors)
                binding.textPosition.setTextColor(defaultPositionColors)
                if (longTitle.isBlank()) {
                    binding.textTitle.visibility = View.GONE
                    binding.textTitle.text = ""
                } else {
                    binding.textTitle.visibility = View.VISIBLE
                    binding.textTitle.text = longTitle
                }
                return
            }
            val accentColor = resolveThemedTextColor(binding.root.context, androidx.appcompat.R.attr.colorAccent, 0xFFBB86FC.toInt())
            binding.textTitle.setTextColor(accentColor)
            binding.textPosition.setTextColor(accentColor)
            if (longTitle.isBlank()) {
                binding.textTitle.visibility = View.GONE
                binding.textTitle.text = ""
                return
            }
            binding.textTitle.visibility = View.VISIBLE
            val drawable = ensurePlayingDrawable()
            if (drawable != null) {
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
                spannable.append(longTitle)
                binding.textTitle.text = spannable
            } else {
                // GIF 解码失败退化成纯高亮文字
                binding.textTitle.text = longTitle
            }
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

        private fun applyBadgeBackground(colorString: String?) {
            val context = binding.textBadge.context
            val drawable = AppCompatResources.getDrawable(
                context,
                R.drawable.badge_background
            )?.mutate() ?: return
            if (colorString.isNullOrBlank()) {
                binding.textBadge.background = drawable
                return
            }
            runCatching {
                val wrapped = DrawableCompat.wrap(drawable)
                DrawableCompat.setTint(wrapped, Color.parseColor(colorString))
                binding.textBadge.background = wrapped
            }.onFailure {
                binding.textBadge.background = drawable
            }
        }

        private fun applyDetailCardWidth(view: View) {
            val layoutParams = view.layoutParams ?: return
            val targetWidth = ScreenUtils.getScreenWidth(view.context) / 5
            if (layoutParams.width != targetWidth) {
                layoutParams.width = targetWidth
                view.layoutParams = layoutParams
            }
        }
    }
}
