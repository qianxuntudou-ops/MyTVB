package com.mytvb.feature.cctv.vod

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.LeadingMarginSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatDialog
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mytvb.R
import com.mytvb.core.common.log.AppLog
import com.mytvb.core.ui.base.AppToast
import com.mytvb.core.ui.base.DialogWindowFit
import com.mytvb.core.ui.base.UiScale
import com.mytvb.core.ui.image.ImageLoader
import com.mytvb.databinding.DialogCctvVodDetailBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 影视详情浮层：剧集信息 + 分集列表，点某一集直接交给播放器。
 * 分集来自央视详情页 HTML（服务端渲染的全集列表）。
 */
class CctvVodDetailDialog(
    context: Context,
    private val album: CctvAlbum,
    private val api: CctvVodApi,
    private val categoryCode: String,
    private val onPlayEpisode: (CctvEpisode, String) -> Unit,
    private val onFavoriteChanged: () -> Unit = {}
) : AppCompatDialog(context, R.style.DialogTheme) {

    private lateinit var binding: DialogCctvVodDetailBinding
    private val episodeAdapter = EpisodeAdapter(::handleEpisodeClick)
    private var loadJob: Job? = null
    // 弹窗生命周期自带的作用域：Fragment 传进来的 context 可能不是 LifecycleOwner
    // （被 ContextThemeWrapper 包装），不能像 Fragment 那样直接用宿主 lifecycleScope。
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var dismissed = false
    private var loadStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DialogCctvVodDetailBinding.inflate(layoutInflater)
        DialogWindowFit.overlay(
            dialog = this,
            content = binding.root,
            widthPx = context.resources.getDimensionPixelSize(R.dimen.px1400),
            heightPx = context.resources.getDimensionPixelSize(R.dimen.px900),
            tag = "cctv_vod_detail"
        )
        bindAlbum()
        setOnShowListener {
            AppLog.i(TAG, "onShow isShowing=$isShowing")
            if (!loadStarted) {
                loadStarted = true
                bindEpisodes()
            }
        }
    }

    private fun bindAlbum() {
        binding.textTitle.text = album.title
        binding.textMeta.text = buildMeta()
        if (album.actors.isNotBlank()) {
            binding.textActors.visibility = View.VISIBLE
            binding.textActors.text = context.getString(R.string.cctv_vod_actors_format, album.actors)
        } else {
            binding.textActors.visibility = View.GONE
        }
        binding.textBrief.text = if (album.brief.isBlank()) {
            context.getString(R.string.cctv_vod_brief_empty)
        } else {
            firstLineIndent(binding.textBrief, album.brief)
        }
        binding.buttonDetail.setOnClickListener {
            CctvVodBriefDialog.show(context, album)
        }
        binding.buttonFavorite.setOnClickListener {
            val favorite = CctvVodFavoriteStore.toggle(context, album, categoryCode)
            updateFavoriteButton()
            AppToast.show(
                context,
                context.getString(
                    if (favorite) R.string.cctv_vod_favorite_added
                    else R.string.cctv_vod_favorite_removed
                )
            )
            onFavoriteChanged()
        }
        updateFavoriteButton()
        // 简介区高度由固定高卡片的剩余空间决定，而 maxLines 是固定值：
        // 两者对不上时多出的那行被裁掉，省略号正好画在看不见的那一行。
        // 弹窗高度在 show 之后才确定，用布局回调按实际高度校正行数。
        // 必须 post 到布局流程之后再改：在布局回调里直接 requestLayout 会被
        // 当前这一轮布局吞掉，maxLines 改了但 TextView 仍按旧行数排版。
        binding.textBrief.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) {
                binding.textBrief.post { fitBriefLines() }
            }
        }
        ImageLoader.load(
            imageView = binding.imageCover,
            url = album.coverUrl,
            placeholder = R.drawable.default_video,
            error = R.drawable.default_video
        )
        binding.recyclerView.layoutManager = GridLayoutManager(context, EPISODE_SPAN)
        binding.recyclerView.adapter = episodeAdapter
        binding.recyclerView.itemAnimator = null
    }

    /** 收藏按钮文案随状态切换：未收藏「收藏」，已收藏「取消收藏」；已收藏用强调色区分。 */
    private fun updateFavoriteButton() {
        val favorite = CctvVodFavoriteStore.isFavorite(context, album.id)
        binding.buttonFavorite.text = context.getString(
            if (favorite) R.string.cctv_vod_unfavorite else R.string.cctv_vod_favorite
        )
        binding.buttonFavorite.setTextColor(
            if (favorite) {
                ContextCompat.getColor(context, R.color.colorAccent)
            } else {
                ContextCompat.getColor(context, R.color.textColor)
            }
        )
    }

    /**
     * 按简介控件的实际可用高度确定行数。
     *
     * 布局里写死的 maxLines 与"固定高卡片减去其余部分"的剩余高度不一定吻合，
     * 一旦行数多于可用高度，最后一行连省略号一起被裁掉——看起来就是简介
     * 戛然而止、没有「…」。
     *
     * 行高不用字体度量估算（含行距、字体上下沿留白时会差一两像素，刚好
     * 多算一行），直接读已排版 layout 的真实行底位置，逐行退到完整可见为止。
     */
    private fun fitBriefLines() {
        val view = binding.textBrief
        val layout = view.layout ?: return
        val available = view.height
        if (available <= 0 || layout.lineCount <= 0) return
        var lines = layout.lineCount
        while (lines > 1 && layout.getLineBottom(lines - 1) > available) {
            lines--
        }
        if (view.maxLines == lines) return
        view.maxLines = lines
        view.requestLayout()
    }

    /** 中文首行缩进两字（缩进宽度按当前字号实测，随字号档缩放）。 */
    private fun firstLineIndent(view: android.widget.TextView, text: String): CharSequence {
        val indent = view.paint.measureText("\u3000\u3000").toInt()
        return SpannableString(text).apply {
            setSpan(
                LeadingMarginSpan.Standard(indent, 0),
                0,
                length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    private fun buildMeta(): String {
        val episodeText = if (album.episodeCount > 0) {
            context.getString(R.string.cctv_vod_episode_count_format, album.episodeCount)
        } else {
            context.getString(R.string.cctv_vod_episode_no_count)
        }
        return listOf(album.year, album.area, album.genre, episodeText)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
    }

    private fun bindEpisodes() {
        AppLog.i(TAG, "bindEpisodes start album=${album.id} mode=${album.episodeMode}")
        loadJob = scope.launch {
            val episodes = withContext(Dispatchers.IO) {
                api.fetchEpisodes(album.id, album.episodeMode)
            }
            AppLog.i(TAG, "episodeLoad done size=${episodes.size} dismissed=$dismissed")
            if (dismissed) return@launch
            onEpisodesReady(episodes)
        }
    }

    private fun onEpisodesReady(episodes: List<CctvEpisode>) {
        if (dismissed) return
        if (episodes.isEmpty()) {
            binding.textEpisodeHint.text = context.getString(R.string.cctv_vod_load_failed)
            return
        }
        binding.textEpisodeHint.visibility = View.GONE
        binding.recyclerView.visibility = View.VISIBLE
        // 有上次观看记录就把初始焦点和滚动位置都定位到那一集，否则回第 1 集
        val lastEpisode = CctvVodProgressStore.loadLastEpisode(context, album.id)
        val focusPosition = episodes.indexOfFirst { it.index == lastEpisode }
            .takeIf { it >= 0 } ?: 0
        AppLog.i(TAG, "episodeFocus lastEpisode=$lastEpisode pos=$focusPosition size=${episodes.size}")
        episodeAdapter.submit(episodes, lastEpisode)
        if (focusPosition > 0) {
            binding.recyclerView.scrollToPosition(focusPosition)
        }
        binding.recyclerView.post {
            if (dismissed) return@post
            binding.recyclerView.findViewHolderForAdapterPosition(focusPosition)
                ?.itemView?.requestFocus()
        }
    }

    private fun handleEpisodeClick(episode: CctvEpisode) {
        val title = episode.title.ifBlank {
            context.getString(R.string.cctv_vod_episode_title_format, episode.index)
        }
        dismiss()
        onPlayEpisode(episode, title)
    }

    override fun dismiss() {
        dismissed = true
        loadJob?.cancel()
        loadJob = null
        scope.cancel()
        super.dismiss()
    }

    private class EpisodeAdapter(
        private val onClick: (CctvEpisode) -> Unit
    ) : RecyclerView.Adapter<EpisodeAdapter.ViewHolder>() {

        private val items = ArrayList<CctvEpisode>()
        private var lastEpisodeIndex = 0

        fun submit(list: List<CctvEpisode>, lastEpisodeIndex: Int) {
            items.clear()
            items.addAll(list)
            this.lastEpisodeIndex = lastEpisodeIndex
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val (root, episodeText, lastWatchedTag) = createItemView(parent)
            return ViewHolder(root, episodeText, lastWatchedTag)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position], position)
        }

        /** 集数格子：外层格子承接焦点，右上角可挂「上次观看」小字标签。 */
        private fun createItemView(
            parent: ViewGroup
        ): Triple<FrameLayout, AppCompatTextView, AppCompatTextView> {
            val context = parent.context
            // 密度守卫：格子尺寸在弹窗 show 之后的选集数据回调里才创建，若期间
            // 发生系统密度重置（弹窗引发的系统栏/窗口变化），这里取的 px80 格高、
            // px30 字号会按系统原始密度放大数倍（实测 2848x1312 手机格子高 3 倍）
            UiScale.ensureApplied(context.resources)
            val margin = context.resources.getDimensionPixelSize(R.dimen.px5)
            val episodeText = AppCompatTextView(context).apply {
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
                setTextSize(
                    TypedValue.COMPLEX_UNIT_PX,
                    context.resources.getDimension(R.dimen.px30)
                )
                setTextColor(episodeTextColors(context))
                // 焦点在格子（背景 selector）上，文字色 selector 靠复制父状态拿 focused 态
                isDuplicateParentStateEnabled = true
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            }
            val lastWatchedTag = AppCompatTextView(context).apply {
                text = context.getString(R.string.cctv_vod_last_watched)
                setTextSize(
                    TypedValue.COMPLEX_UNIT_PX,
                    context.resources.getDimension(R.dimen.px18)
                )
                setTextColor(ContextCompat.getColor(context, R.color.textColor))
                visibility = View.GONE
                // 与封面卡片右上角标同款：粉色底+右上/左下圆角，贴角无缝隙
                setBackgroundResource(R.drawable.badge_background)
                val padH = context.resources.getDimensionPixelSize(R.dimen.px7)
                val padV = context.resources.getDimensionPixelSize(R.dimen.px3)
                setPadding(padH, padV, padH, padV)
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.END
                )
            }
            val root = FrameLayout(context).apply {
                isFocusable = true
                isClickable = true
                setBackgroundResource(R.drawable.bg_cctv_vod_episode_cell)
                addView(episodeText)
                addView(lastWatchedTag)
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    context.resources.getDimensionPixelSize(R.dimen.px80)
                ).apply {
                    marginStart = margin
                    marginEnd = margin
                    topMargin = margin
                    bottomMargin = margin
                }
            }
            return Triple(root, episodeText, lastWatchedTag)
        }

        inner class ViewHolder(
            private val root: FrameLayout,
            private val episodeText: AppCompatTextView,
            private val lastWatchedTag: AppCompatTextView
        ) : RecyclerView.ViewHolder(root) {

            fun bind(episode: CctvEpisode, position: Int) {
                episodeText.text = episode.index.toString().padStart(2, '0')
                root.contentDescription = episode.title
                val isLast = episode.index == lastEpisodeIndex
                lastWatchedTag.visibility = if (isLast) View.VISIBLE else View.GONE
                // 角标胶囊占右上角，集号下移几像素避免贴碰
                episodeText.translationY = if (isLast) {
                    root.resources.getDimension(R.dimen.px6)
                } else {
                    0f
                }
                root.setOnClickListener {
                    val current = bindingAdapterPosition
                    if (current != RecyclerView.NO_POSITION) {
                        onClick(items[current])
                    }
                }
            }
        }
    }

    companion object {
        private const val EPISODE_SPAN = 6
        private const val TAG = "CctvVodDetailDialog"

        fun show(
            context: Context,
            album: CctvAlbum,
            api: CctvVodApi,
            categoryCode: String,
            onPlayEpisode: (CctvEpisode, String) -> Unit,
            onFavoriteChanged: () -> Unit = {}
        ) {
            CctvVodDetailDialog(
                context = context,
                album = album,
                api = api,
                categoryCode = categoryCode,
                onPlayEpisode = onPlayEpisode,
                onFavoriteChanged = onFavoriteChanged
            ).show()
        }
    }
}

/** 集号文字色：选中=强调色，聚焦=主文字色，其余=次要文字色。 */
private fun episodeTextColors(context: Context): ColorStateList {
    val accent = ContextCompat.getColor(context, R.color.colorAccent)
    val primary = resolveThemeColor(context, android.R.attr.textColorPrimary)
    val secondary = resolveThemeColor(context, android.R.attr.textColorSecondary)
    return ColorStateList(
        arrayOf(
            intArrayOf(android.R.attr.state_selected),
            intArrayOf(android.R.attr.state_focused),
            intArrayOf()
        ),
        intArrayOf(accent, primary, secondary)
    )
}

private fun resolveThemeColor(context: Context, attr: Int): Int {
    val value = TypedValue()
    if (!context.theme.resolveAttribute(attr, value, true)) return android.graphics.Color.WHITE
    return if (value.resourceId != 0) ContextCompat.getColor(context, value.resourceId) else value.data
}
