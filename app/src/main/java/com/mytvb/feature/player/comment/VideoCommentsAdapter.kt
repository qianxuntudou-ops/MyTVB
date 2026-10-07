package com.mytvb.feature.player.comment

import android.graphics.drawable.GradientDrawable
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.AbsoluteSizeSpan
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
import com.mytvb.core.common.log.AppLog
import com.mytvb.core.common.time.TimeUtils
import com.mytvb.core.ui.image.ImageLoader
import com.mytvb.databinding.ItemCommentBinding
import com.mytvb.databinding.ItemCommentSectionBinding

/**
 * 评论面板列表 Adapter（根评论与楼中楼共用）。语义对齐
 * blbl.cat3399.feature.video.comment.VideoCommentsAdapter：
 * stableIds 按 key、长评展开/收起（运行时判 ellipsis 后提示"展开"、展开后提示"收起"，触摸点文字收起、焦点模式无图无楼时整卡 OK 收起）、
 * 回复预览用户名高亮 + 大表情、图片缩略图、笔记图片异步补载、
 * 焦点 OK 与触摸点击分流（遥控 OK：带图=开图/无图有楼=进楼中楼；触摸：长按=进楼中楼（与
 * "长按查看全部"文案一致）、图片子 view 触摸开图）。
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
    // true=楼中楼面板：主评论正文恒展开、不出"展开/收起"（点进详情就是要看全文）
    private val expandMessageFully: Boolean = false,
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
            isExpanded = expandMessageFully || expandedRpids.contains(item.rpid),
            expandFully = expandMessageFully,
            onToggleExpand = { rpid ->
                // toggle：remove 成功=原本展开→收起，否则补 add=展开
                if (!expandedRpids.remove(rpid)) expandedRpids.add(rpid)
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
            expandFully: Boolean,
            onToggleExpand: (Long) -> Unit,
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
            updateExpandHint(itemRpid = item.rpid, isExpanded = isExpanded, expandFully = expandFully)
            bindPictures(item, onPictureClick)

            run {
                val previews = item.replyPreviews.take(2)
                // 链接行在框内：无预览但可开楼中楼时，框内只留"查看全部回复"一行
                val showReplyLink = item.canOpenThread && item.replyCount > 0
                binding.rowReplyPreview.visibility =
                    if (previews.isNotEmpty() || showReplyLink) View.VISIBLE else View.GONE
                // 无预览时预览行必须 GONE（仅清文本会占一行高度，框会空出一块）
                binding.tvReplyPreview1.visibility =
                    if (previews.isNotEmpty()) View.VISIBLE else View.GONE
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
                    binding.tvReplyPreview2.text = ""
                    binding.tvReplyPreview2.visibility = View.GONE
                }
                // 无预览时"查看全部"行贴框顶（免掉为预览行留的 marginTop）
                binding.tvReply.layoutParams.let { lp ->
                    if (lp is androidx.appcompat.widget.LinearLayoutCompat.LayoutParams) {
                        lp.topMargin =
                            if (previews.isEmpty()) 0
                            else ctx.resources.getDimensionPixelSize(R.dimen.px10)
                        binding.tvReply.layoutParams = lp
                    }
                }
            }

            if (item.canOpenThread && item.replyCount > 0) {
                val rc = NumberUtils.formatCount(ctx, item.replyCount.toLong())
                // 统一"长按查看全部"文案（官方 TV 触摸/焦点双路径，长按语义对全部评论一致）
                binding.tvReply.text =
                    ctx.getString(R.string.player_comment_view_all_replies_long_press_format, rc)
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
                when {
                    shouldExpand -> onToggleExpand(item.rpid)
                    // 触摸点正文 = 进楼中楼等既有行为；收起走 tv_expand 自己的点击
                    wasTouch -> onTouchClick(item)
                    // 焦点模式展开后：无图可开、无楼可进（原本只剩 toast/无动作）时 OK 收起
                    // （楼中楼面板 expandFully 恒展开，无收起语义，OK 走 onClick）
                    !expandFully && isExpanded && item.pictures.isEmpty() && item.noteCvid <= 0L &&
                        !(item.canOpenThread && item.replyCount > 0) -> onToggleExpand(item.rpid)
                    else -> onClick(item)
                }
            }
            // 收起文字按钮：触摸独立可点；TV 上不加 focusable，收起由整卡 OK 兜底（见上）
            binding.tvExpand.setOnClickListener { onToggleExpand(item.rpid) }
            binding.clickView.setOnLongClickListener {
                onLongClick(item)
            }
        }

        private fun bindLevelTag(level: Int?, isSeniorMember: Boolean) {
            binding.tvLevel.bind(level, isSeniorMember)
        }

        private fun updateExpandHint(itemRpid: Long, isExpanded: Boolean, expandFully: Boolean) {
            val ctx = binding.root.context
            if (expandFully) {
                // 楼中楼面板：正文恒展开，无展开/收起交互
                binding.tvExpand.visibility = View.GONE
                return
            }
            if (isExpanded) {
                // expandedRpids 只收点过"展开"（当时确实省略）的 rpid，收起按钮无条件显示
                binding.tvExpand.text = ctx.getString(R.string.player_comment_collapse)
                binding.tvExpand.visibility = View.VISIBLE
                return
            }
            binding.tvExpand.text = ctx.getString(R.string.player_comment_expand)
            binding.tvExpand.visibility = View.GONE

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
            // 默认 buffer：SPANNABLE 会触发 ellipsize 失效（见 bindReplyPreviewRows 注释）
            if (found) view.text = ss
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
            val ctx = view.context
            val u = preview.userName.ifBlank { "-" }
            val m = preview.message.ifBlank { "-" }
            // messagePart = 用户名(蓝色高亮) + UP徽章(UP主回复，官方同款) + "：" + 内容
            val messagePart = SpannableStringBuilder(u)
            messagePart.setSpan(ForegroundColorSpan(userColor), 0, u.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (preview.isUp) {
                val badgeStart = messagePart.length
                messagePart.append("\uFFFC")
                messagePart.setSpan(
                    CommentNoteTag.upBadgeSpan(view),
                    badgeStart,
                    badgeStart + 1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
            messagePart.append("：").append(m)
            // likePart = "｜ 👍N"（官方预览行行尾点赞，0 不显示）
            val likePart = buildPreviewLikePart(view, preview.likeCount)
            val ssb = SpannableStringBuilder(messagePart)
            if (likePart != null) ssb.append(likePart)
            view.setTag(R.id.tag_emote_text_key, messagePart)
            CommentEmoteSpannable.applyEmotes(view, ssb, start = 0, end = messagePart.length, emotes = preview.emotes)
            // 官方预览行规范：行内 @某人 与行首用户名同为蓝色链接色（同正文 highlightMentions）
            highlightMentions(view, userColor)
            // 走默认 buffer 对齐正文正常路径；实测 SPANNABLE 下（含 ImageSpan）TextView 的
            // maxLines/ellipsize 失效——layout 自然折行、高度被钳成 1 行、永无"…"
            view.text = ssb
            // 兜底：系统 ellipsize 未生效（layout 折成多行）或截到了点赞尾巴时，
            // 只对内容段手动省略，再接回点赞数——点赞恒显对齐官方
            view.post {
                if (view.getTag(R.id.tag_emote_text_key) != messagePart) return@post
                val layout = view.layout ?: return@post
                if (view.width <= 0) return@post
                val last = layout.lineCount - 1
                if (last < 0) return@post
                if (layout.lineCount <= 1 && layout.getEllipsisCount(last) <= 0) return@post
                val avail = (view.width - view.paddingLeft - view.paddingRight).toFloat()
                val likeW = likePart?.let { Layout.getDesiredWidth(it, view.paint) } ?: 0f
                val contentW = (avail - likeW).coerceAtLeast(0f)
                val finalSsb =
                    SpannableStringBuilder(
                        TextUtils.ellipsize(messagePart, view.paint, contentW, TextUtils.TruncateAt.END),
                    )
                if (likePart != null) finalSsb.append(likePart)
                view.text = finalSsb
            }
        }

        /** 预览行行尾点赞段：" ｜ [👍]N"，数字略小（官方样式）；0 赞不显示返回 null。
         *  分隔竖线比点赞数字更淡（官方同款弱分隔）。 */
        private fun buildPreviewLikePart(view: TextView, likeCount: Long): SpannableStringBuilder? {
            if (likeCount <= 0L) return null
            val ctx = view.context
            val res = view.resources
            val gray = ContextCompat.getColor(ctx, R.color.subTextColor)
            val sepColor = 0x66CCCCCC.toInt()
            val icon = ContextCompat.getDrawable(ctx, R.drawable.ic_like)?.mutate() ?: return null
            icon.setTint(gray)
            val iconSize = res.getDimensionPixelSize(R.dimen.px22)
            icon.setBounds(0, 0, iconSize, iconSize)
            val ssb = SpannableStringBuilder(" ｜ ")
            ssb.setSpan(ForegroundColorSpan(sepColor), 0, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val iconStart = ssb.length
            ssb.append("\uFFFC ")
            ssb.setSpan(CommentNoteTag.CenteredImageSpan(icon), iconStart, iconStart + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val numStart = ssb.length
            ssb.append(NumberUtils.formatCount(ctx, likeCount))
            ssb.setSpan(AbsoluteSizeSpan(res.getDimensionPixelSize(R.dimen.px18)), numStart, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            ssb.setSpan(ForegroundColorSpan(gray), numStart, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            return ssb
        }
    }
}
