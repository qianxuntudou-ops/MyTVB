package com.mytvb.feature.player.comment

import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.mytvb.R
import com.mytvb.core.common.format.NumberUtils
import com.mytvb.core.common.time.TimeUtils
import com.mytvb.core.ui.image.ImageLoader
import com.mytvb.databinding.ItemCommentBinding
import com.mytvb.databinding.ItemCommentSectionBinding

/**
 * 评论面板列表 Adapter（根评论与楼中楼共用）。语义对齐
 * blbl.cat3399.feature.video.comment.VideoCommentsAdapter：
 * stableIds 按 key、长评展开/收起（运行时判 ellipsis 后提示"展开"）、
 * 回复预览用户名高亮 + 大表情、图片缩略图、笔记图片异步补载、
 * 焦点 OK 与触摸点击分流（带图评论：OK=开图、触摸=进楼中楼，图片子 view 触摸开图）。
 *
 * 焦点交互按 MyBLBL 合集列表惯例：第一项按"上"回 [onTopEdge]
 * （根列表=排序 tab），最后一项按"下"消费防飞出；[onItemFocused]
 * 是触底加载的挂点之一（焦点滚动不产生 dy 的场景）。
 */
internal class VideoCommentsAdapter(
    private val expandedRpids: MutableSet<Long>,
    private val onClick: (VideoCommentItem) -> Unit,
    private val onTouchClick: (VideoCommentItem) -> Unit = {},
    private val onPictureClick: (VideoCommentItem, Int) -> Unit,
    private val onLongClick: (VideoCommentItem) -> Boolean = { false },
    private val onItemFocused: () -> Unit = {},
    private val onTopEdge: () -> Unit = {},
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private val items = ArrayList<VideoCommentItem>()
    private val requestedNotePictures = HashSet<Long>()

    init {
        setHasStableIds(true)
    }

    fun invalidateSizing() {
        if (itemCount <= 0) return
        notifyItemRangeChanged(0, itemCount)
    }

    fun setItems(list: List<VideoCommentItem>) {
        items.clear()
        items.addAll(list)
        requestedNotePictures.clear()
        notifyDataSetChanged()
    }

    fun appendItems(list: List<VideoCommentItem>) {
        if (list.isEmpty()) return
        val start = items.size
        items.addAll(list)
        notifyItemRangeInserted(start, list.size)
    }

    fun updatePictures(rpid: Long, pictures: List<VideoCommentPicture>) {
        if (pictures.isEmpty()) return
        val idx = items.indexOfFirst { it.rpid == rpid }
        if (idx !in items.indices) return
        val current = items[idx]
        if (current.pictures == pictures) return
        items[idx] = current.copy(pictures = pictures)
        notifyItemChanged(idx)
    }

    override fun getItemCount(): Int = items.size

    override fun getItemId(position: Int): Long = items[position].key.hashCode().toLong()

    override fun getItemViewType(position: Int): Int =
        if (items[position].threadSectionTitle != null) TYPE_SECTION else TYPE_ITEM

    fun isSectionAt(position: Int): Boolean =
        items.getOrNull(position)?.threadSectionTitle != null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        if (viewType == TYPE_SECTION) {
            val binding = ItemCommentSectionBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return SectionVh(binding.root, binding.tvSectionTitle)
        }
        val binding = ItemCommentBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return Vh(binding, onTopEdge)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        if (item.threadSectionTitle != null) {
            val sectionHolder = holder as? SectionVh ?: return
            sectionHolder.text.text = item.threadSectionTitle
            return
        }
        maybeRequestNotePictures(item)
        (holder as Vh).bind(
            item = item,
            isExpanded = expandedRpids.contains(item.rpid),
            onExpand = { rpid ->
                if (!expandedRpids.add(rpid)) return@bind
                val pos =
                    holder.bindingAdapterPosition
                        .takeIf { it != RecyclerView.NO_POSITION }
                        ?: position
                notifyItemChanged(pos)
            },
            onClick = onClick,
            onTouchClick = onTouchClick,
            onPictureClick = onPictureClick,
            onLongClick = onLongClick,
            onItemFocused = onItemFocused,
        )
    }

    private fun maybeRequestNotePictures(item: VideoCommentItem) {
        if (item.pictures.isNotEmpty()) return
        if (item.noteCvid <= 0L) return
        if (!requestedNotePictures.add(item.rpid)) return
        CommentNoteImageRepository.load(item.noteCvid) { images ->
            updatePictures(
                item.rpid,
                images.map { img ->
                    VideoCommentPicture(url = img.url, width = img.width, height = img.height)
                },
            )
        }
    }

    private companion object {
        private const val TYPE_ITEM = 0
        private const val TYPE_SECTION = 1
    }

    /** 楼中楼小节行（粗分隔线 + "相关回复共N条"），不可聚焦不可点击，焦点自动跳过。 */
    class SectionVh(root: View, val text: TextView) : RecyclerView.ViewHolder(root)

    class Vh(
        private val binding: ItemCommentBinding,
        private val onTopEdge: () -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {
        private var boundRpid: Long = 0L
        private var rootClickFromTouch = false

        init {
            // 焦点模式边缘导航（对齐合集列表惯例）：第一项按"上"交由面板回排序 tab，
            // 最后一项按"下"消费防焦点飞出，纵向列表内左右键消费。
            // 注意必须在 ACTION_DOWN 拦截：一次"上"键的 ACTION_UP 会派发给移入的新焦点，
            // 若在 UP 拦截，焦点从第二项移入第一项时就会被误判为"第一项按上"而弹去 tab。
            binding.clickView.setOnKeyListener { _, keyCode, event ->
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        if (event.action == KeyEvent.ACTION_DOWN && bindingAdapterPosition == 0) {
                            onTopEdge()
                            true
                        } else {
                            false
                        }
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (event.action == KeyEvent.ACTION_DOWN) {
                            val count = bindingAdapter?.itemCount ?: -1
                            if (count > 0 && bindingAdapterPosition == count - 1) {
                                true
                            } else {
                                false
                            }
                        } else {
                            false
                        }
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> true
                    else -> false
                }
            }
        }

        fun bind(
            item: VideoCommentItem,
            isExpanded: Boolean,
            onExpand: (Long) -> Unit,
            onClick: (VideoCommentItem) -> Unit,
            onTouchClick: (VideoCommentItem) -> Unit,
            onPictureClick: (VideoCommentItem, Int) -> Unit,
            onLongClick: (VideoCommentItem) -> Boolean,
            onItemFocused: () -> Unit,
        ) {
            boundRpid = item.rpid
            rootClickFromTouch = false
            val ctx = binding.root.context
            val previewUserColor = ContextCompat.getColor(ctx, R.color.biliBlue)

            binding.tvUser.text = item.userName.ifBlank { "-" }
            bindLevelTag(item.userLevel, item.isSeniorMember)
            binding.tvUpBadge.visibility = if (item.isUp) View.VISIBLE else View.GONE
            binding.tvTime.text = TimeUtils.formatRelativeTime(ctx, item.ctimeSec)
            val hasLikes = item.likeCount > 0L
            binding.ivLike.visibility = if (hasLikes) View.VISIBLE else View.GONE
            binding.tvLike.text = if (hasLikes) NumberUtils.formatCount(ctx, item.likeCount) else ""
            binding.tvLike.visibility = if (hasLikes) View.VISIBLE else View.GONE
            binding.tvMessage.maxLines = if (isExpanded) Int.MAX_VALUE else 6
            val blankFallback = if (item.pictures.isNotEmpty() || item.noteCvid > 0L) "" else "-"
            CommentEmoteSpannable.setText(binding.tvMessage, item.message, item.emotes, blankFallback = blankFallback)
            // 置顶（官方：粉描边胶囊，正文行首）、笔记（note_cvid>0：灰底标签）
            if (item.isTop) {
                CommentNoteTag.prependTop(binding.tvMessage)
            }
            if (item.noteCvid > 0L) {
                CommentNoteTag.prepend(binding.tvMessage)
            }
            // 官方楼中楼规范：@用户名 用蓝色链接色高亮
            highlightMentions(binding.tvMessage, previewUserColor)
            updateExpandHint(itemRpid = item.rpid, isExpanded = isExpanded)
            bindPictures(item, onPictureClick)

            run {
                val previews = item.replyPreviews.take(2)
                // 链接行在框内：无预览但可开楼中楼时，框内只留"查看全部回复"一行
                val showReplyLink = item.canOpenThread && item.replyCount > 0
                binding.rowReplyPreview.visibility =
                    if (previews.isNotEmpty() || showReplyLink) View.VISIBLE else View.GONE
                if (previews.isNotEmpty()) {
                    bindReplyPreviewText(binding.tvReplyPreview1, previews[0], previewUserColor)
                    if (previews.size >= 2) {
                        bindReplyPreviewText(binding.tvReplyPreview2, previews[1], previewUserColor)
                        binding.tvReplyPreview2.visibility = View.VISIBLE
                    } else {
                        binding.tvReplyPreview2.text = ""
                        binding.tvReplyPreview2.visibility = View.GONE
                    }
                } else {
                    binding.tvReplyPreview1.text = ""
                    binding.tvReplyPreview2.text = ""
                    binding.tvReplyPreview2.visibility = View.GONE
                }
            }

            if (item.canOpenThread && item.replyCount > 0) {
                val rc = NumberUtils.formatCount(ctx, item.replyCount.toLong())
                val hasPictures = item.pictures.isNotEmpty() || item.noteCvid > 0L
                binding.tvReply.text =
                    if (hasPictures) {
                        ctx.getString(R.string.player_comment_view_all_replies_long_press_format, rc)
                    } else {
                        ctx.getString(R.string.player_comment_view_all_replies_format, rc)
                    }
                binding.tvReply.visibility = View.VISIBLE
            } else {
                binding.tvReply.text = ""
                binding.tvReply.visibility = View.GONE
            }

            // 大会员：粉色用户名 + 官方"大"字角标；普通用户名官方为蓝色链接色
            binding.tvUser.setTextColor(
                ContextCompat.getColor(
                    ctx,
                    if (item.isVip) R.color.biliPink else R.color.biliBlue,
                )
            )
            binding.tvVipBadge.visibility = if (item.isVip) View.VISIBLE else View.GONE
            binding.tvUpLiked.visibility = if (item.isUpLiked) View.VISIBLE else View.GONE
            if (item.avatarPendantUrl != null) {
                binding.ivAvatarPendant.visibility = View.VISIBLE
                ImageLoader.loadOriginal(binding.ivAvatarPendant, item.avatarPendantUrl)
            } else {
                binding.ivAvatarPendant.visibility = View.GONE
                ImageLoader.clear(binding.ivAvatarPendant)
            }

            // 粉丝勋章团徽（头像左下角，官方位置）：有团徽图加载图，否则粉丝牌配色圆点
            val fanMedal = item.fanMedal
            if (fanMedal != null) {
                binding.ivFanMedal.visibility = View.VISIBLE
                if (fanMedal.iconUrl != null) {
                    ImageLoader.loadOriginal(binding.ivFanMedal, fanMedal.iconUrl)
                } else {
                    ImageLoader.clear(binding.ivFanMedal)
                    binding.ivFanMedal.background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(fanMedal.colorStart)
                        setStroke(
                            binding.root.resources.getDimensionPixelSize(R.dimen.px2),
                            fanMedal.colorBorder,
                        )
                    }
                }
            } else {
                binding.ivFanMedal.visibility = View.GONE
                ImageLoader.clear(binding.ivFanMedal)
            }

            ImageLoader.loadCircle(binding.ivAvatar, item.avatarUrl)

            binding.clickView.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) onItemFocused()
            }
            binding.clickView.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> rootClickFromTouch = true
                    MotionEvent.ACTION_CANCEL -> rootClickFromTouch = false
                }
                false
            }
            binding.clickView.setOnClickListener {
                val wasTouch = rootClickFromTouch
                rootClickFromTouch = false
                val shouldExpand = !isExpanded && isMessageEllipsized(binding.tvMessage)
                if (shouldExpand) {
                    onExpand(item.rpid)
                } else if (wasTouch) {
                    onTouchClick(item)
                } else {
                    onClick(item)
                }
            }
            binding.clickView.setOnLongClickListener {
                onLongClick(item)
            }
        }

        private fun bindLevelTag(level: Int?, isSeniorMember: Boolean) {
            binding.tvLevel.bind(level, isSeniorMember)
        }

        private fun updateExpandHint(itemRpid: Long, isExpanded: Boolean) {
            binding.tvExpand.visibility = View.GONE
            if (isExpanded) return

            // 只有运行时真的省略了才显示"展开"：post 等布局完成再判。
            binding.tvMessage.post {
                if (boundRpid != itemRpid) return@post
                val shouldShow = isMessageEllipsized(binding.tvMessage)
                binding.tvExpand.visibility = if (shouldShow) View.VISIBLE else View.GONE
            }
        }

        private fun isMessageEllipsized(view: TextView): Boolean {
            val layout = view.layout ?: return false
            val last = layout.lineCount - 1
            if (last < 0) return false
            return layout.getEllipsisCount(last) > 0
        }

        private val mentionPattern = Regex("@[\\w\\u4e00-\\u9fa5·]+")

        /** @用户名 蓝色链接色（官方楼中楼规范）；SpannableString 构造会复制已有 span，笔记/表情不受影响。 */
        private fun highlightMentions(view: TextView, color: Int) {
            val text = view.text
            if (text.isBlank() || !text.contains('@')) return
            val ss = SpannableString(text)
            var found = false
            for (m in mentionPattern.findAll(text)) {
                ss.setSpan(ForegroundColorSpan(color), m.range.first, m.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                found = true
            }
            if (found) view.setText(ss, TextView.BufferType.SPANNABLE)
        }

        private fun bindPictures(
            item: VideoCommentItem,
            onPictureClick: (VideoCommentItem, Int) -> Unit,
        ) {
            val pictures = item.pictures.take(3).filter { it.url.isNotBlank() }
            if (pictures.isEmpty()) {
                binding.rowPictures.visibility = View.GONE
                bindPictureView(binding.ivPicture1, null, item, 0, onPictureClick)
                bindPictureView(binding.ivPicture2, null, item, 1, onPictureClick)
                bindPictureView(binding.ivPicture3, null, item, 2, onPictureClick)
                return
            }

            binding.rowPictures.visibility = View.VISIBLE
            bindPictureView(binding.ivPicture1, pictures.getOrNull(0), item, 0, onPictureClick)
            bindPictureView(binding.ivPicture2, pictures.getOrNull(1), item, 1, onPictureClick)
            bindPictureView(binding.ivPicture3, pictures.getOrNull(2), item, 2, onPictureClick)
        }

        private fun bindPictureView(
            view: android.widget.ImageView,
            picture: VideoCommentPicture?,
            item: VideoCommentItem,
            index: Int,
            onPictureClick: (VideoCommentItem, Int) -> Unit,
        ) {
            if (picture != null) {
                view.visibility = View.VISIBLE
                // GIF 走 GifDrawable 直接上屏、不吃 transform 圆角，统一用 outline 裁剪兜住全部图源
                ensureRoundedClip(view)
                ImageLoader.loadCommentPicture(view, picture.url)
                view.setOnClickListener { onPictureClick(item, index) }
            } else {
                view.visibility = View.GONE
                ImageLoader.clear(view)
                view.setOnClickListener(null)
            }
        }

        private fun ensureRoundedClip(view: android.widget.ImageView) {
            if (view.clipToOutline) return
            val radius = view.resources.getDimensionPixelSize(R.dimen.px10).toFloat()
            view.outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, v.width, v.height, radius)
                }
            }
            view.clipToOutline = true
            view.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> v.invalidateOutline() }
        }

        private fun bindReplyPreviewText(view: TextView, preview: VideoCommentReplyPreview, userColor: Int) {
            val u = preview.userName.ifBlank { "-" }
            val m = preview.message.ifBlank { "-" }
            val s = "$u：$m"
            val ssb = SpannableStringBuilder(s)
            ssb.setSpan(ForegroundColorSpan(userColor), 0, u.length.coerceAtMost(s.length), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            view.setTag(R.id.tag_emote_text_key, s)
            CommentEmoteSpannable.applyEmotes(view, ssb, start = 0, end = ssb.length, emotes = preview.emotes)
            view.setText(ssb, TextView.BufferType.SPANNABLE)
        }
    }
}
