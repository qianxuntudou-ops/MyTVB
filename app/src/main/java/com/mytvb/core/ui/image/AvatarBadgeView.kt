package com.mytvb.core.ui.image

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.content.res.ResourcesCompat
import com.mytvb.R

class AvatarBadgeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    companion object {
        const val BADGE_NONE = 0
        const val BADGE_YELLOW_V = 1
        const val BADGE_BLUE_V = 2
        const val BADGE_VIP = 3
    }

    private var badgeDrawable: Drawable? = null
    private var badgeType: Int = BADGE_NONE
    private var borderEnabled = true
    private var pendantDrawable: android.graphics.drawable.BitmapDrawable? = null
    private var pendantUrl: String? = null

    private val borderWidthPx = 1f
    private val gapPx = 1f
    private val avatarClipPath = Path()

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = borderWidthPx
        color = 0xFF666666.toInt()
        isAntiAlias = true
    }

    init {
        val extra = (gapPx + borderWidthPx).toInt().coerceAtLeast(1)
        setPadding(
            paddingLeft + extra,
            paddingTop + extra,
            paddingRight + extra,
            paddingBottom + extra
        )
    }

    fun setBadge(officialVerifyType: Int, vipStatus: Int = 0, vipType: Int = 0, vipAvatarSubscript: Int = 0) {
        val newBadgeType = resolveBadgeType(officialVerifyType, vipStatus, vipType, vipAvatarSubscript)
        if (newBadgeType == badgeType) return
        badgeType = newBadgeType
        badgeDrawable = when (badgeType) {
            BADGE_YELLOW_V -> ResourcesCompat.getDrawable(resources, R.drawable.ic_avatar_badge_yellow_v, context.theme)
            BADGE_BLUE_V -> ResourcesCompat.getDrawable(resources, R.drawable.ic_avatar_badge_blue_v, context.theme)
            BADGE_VIP -> ResourcesCompat.getDrawable(resources, R.drawable.ic_comment_vip_badge, context.theme)
            else -> null
        }
        invalidate()
    }

    fun setBadgeType(type: Int) {
        if (type == badgeType) return
        badgeType = type
        badgeDrawable = when (badgeType) {
            BADGE_YELLOW_V -> ResourcesCompat.getDrawable(resources, R.drawable.ic_avatar_badge_yellow_v, context.theme)
            BADGE_BLUE_V -> ResourcesCompat.getDrawable(resources, R.drawable.ic_avatar_badge_blue_v, context.theme)
            BADGE_VIP -> ResourcesCompat.getDrawable(resources, R.drawable.ic_comment_vip_badge, context.theme)
            else -> null
        }
        invalidate()
    }

    fun setBorderEnabled(enabled: Boolean) {
        if (borderEnabled == enabled) return
        borderEnabled = enabled
        invalidate()
    }

    /** 头像框（pendant 装扮图，画布外圈环）。有框时头像内缩到框内径（对齐官方 0.78 比例）。 */
    fun setPendant(url: String?) {
        if (url == pendantUrl) return
        val hadPendant = pendantDrawable != null
        pendantUrl = url
        pendantDrawable = null
        if (url.isNullOrBlank()) {
            if (hadPendant) invalidate()
            return
        }
        ImageLoader.loadBitmap(
            context,
            url,
            onSuccess = { bmp ->
                if (url != pendantUrl) return@loadBitmap
                pendantDrawable = android.graphics.drawable.BitmapDrawable(resources, bmp)
                invalidate()
            },
        )
    }

    override fun onDraw(canvas: Canvas) {
        // 头像框环铺满整个 view（框图外圈为环、中部透明），画在头像下层
        pendantDrawable?.let { pendant ->
            pendant.setBounds(0, 0, width, height)
            pendant.draw(canvas)
        }
        val saveCount = canvas.save()
        avatarClipPath.reset()
        // 有框时头像内缩（框环内径约 0.78，对齐官方头像/框比例）
        val pendantScale = if (pendantDrawable != null) 0.78f else 1f
        val contentWidth = (width - paddingLeft - paddingRight) * pendantScale
        val contentHeight = (height - paddingTop - paddingBottom) * pendantScale
        val radius = Math.min(contentWidth, contentHeight) / 2f
        avatarClipPath.addCircle(
            paddingLeft + (width - paddingLeft - paddingRight) / 2f,
            paddingTop + (height - paddingTop - paddingBottom) / 2f,
            radius,
            Path.Direction.CW
        )
        canvas.clipPath(avatarClipPath)
        super.onDraw(canvas)
        canvas.restoreToCount(saveCount)
        if (borderEnabled) {
            drawCircleBorder(canvas)
        }
        badgeDrawable?.let { badge ->
            val ratio = if (badgeType == BADGE_VIP) 0.36f else 0.4f
            val badgeSize = (width * ratio).toInt()
            val left = width - badgeSize
            val top = height - badgeSize
            badge.setBounds(left, top, left + badgeSize, top + badgeSize)
            badge.draw(canvas)
        }
    }

    private fun drawCircleBorder(canvas: Canvas) {
        if (pendantDrawable != null) return // 有头像框时不再画默认灰描边
        val contentWidth = width - paddingLeft - paddingRight
        val contentHeight = height - paddingTop - paddingBottom
        val cx = paddingLeft + contentWidth / 2f
        val cy = paddingTop + contentHeight / 2f
        val contentRadius = Math.min(contentWidth, contentHeight) / 2f
        val ringRadius = contentRadius + gapPx + borderWidthPx / 2f
        canvas.drawCircle(cx, cy, ringRadius, borderPaint)
    }

    private fun resolveBadgeType(officialVerifyType: Int, vipStatus: Int, vipType: Int, vipAvatarSubscript: Int): Int {
        if (officialVerifyType == 0) return BADGE_YELLOW_V
        if (officialVerifyType == 1) return BADGE_BLUE_V
        if (vipAvatarSubscript == 1 || (vipType > 0 && vipStatus == 1)) return BADGE_VIP
        return BADGE_NONE
    }
}
