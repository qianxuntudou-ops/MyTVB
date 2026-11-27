package com.mytvb.feature.cctv.vod

import android.view.LayoutInflater

import android.content.Context
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.LeadingMarginSpan
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatDialog
import com.mytvb.R
import com.mytvb.core.ui.base.DialogWindowFit
import com.mytvb.databinding.DialogCctvVodBriefBinding

/**
 * 影视简介弹窗：剧名 + 主演 + 完整简介。
 *
 * 详情弹窗里只放得下几行摘要，长简介会被截断，故单独给一个可滚动的弹窗；
 * 简介高度按内容自适应，超过卡片最大高度时转为上下滚动（遥控器上下键也能滚）。
 */
class CctvVodBriefDialog(
    context: Context,
    private val album: CctvAlbum
) : AppCompatDialog(context, R.style.DialogTheme) {

    // Dialog.getContext()（ContextThemeWrapper→DialogTheme 恒黑）会遮蔽构造参数 context，
    // 主题化 attr 必须用宿主 Activity context 解析
    private val hostContext = context

    private lateinit var binding: DialogCctvVodBriefBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DialogCctvVodBriefBinding.inflate(LayoutInflater.from(hostContext))
        bindContent()
        applyScrollLimit()
        DialogWindowFit.overlay(
            dialog = this,
            content = binding.root,
            widthPx = context.resources.getDimensionPixelSize(R.dimen.px1000),
            tag = "cctv_vod_brief"
        )
        binding.buttonClose.setOnClickListener { dismiss() }
        // 滚动区自身不参与焦点（NonFocusableScrollView），上下键交给弹窗层驱动滚动；
        // 关闭按钮上的左右键不受影响
        setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            val step = context.resources.getDimensionPixelSize(R.dimen.px120)
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    binding.scrollBrief.smoothScrollBy(0, -step)
                    true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    binding.scrollBrief.smoothScrollBy(0, step)
                    true
                }
                else -> false
            }
        }
        setOnShowListener {
            binding.buttonClose.post { binding.buttonClose.requestFocus() }
        }
        // 项目开了 enableOnBackInvokedCallback：BACK 走 OnBackInvoked 通道，
        // 必须注册到 ComponentDialog 自带 dispatcher，否则弹窗关不掉（OwnerDetailDialog 同款）
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                dismiss()
            }
        })
    }

    private fun bindContent() {
        binding.textTitle.text = album.title
        val actors = album.actors.trim()
        binding.textActors.text = if (actors.isBlank()) {
            buildMeta()
        } else {
            context.getString(R.string.cctv_vod_actors_format, actors)
        }
        val brief = album.brief.trim()
        binding.textBrief.text = if (brief.isBlank()) {
            context.getString(R.string.cctv_vod_brief_empty)
        } else {
            firstLineIndent(binding.textBrief, brief)
        }
    }

    /**
     * 滚动区最大高度 = 卡片可用高度 − 标题/主演/分隔线/关闭按钮等固定部分。
     *
     * 卡片整体是 wrap 高度：简介短时弹窗就短，超过上限才出现滚动。
     * 固定部分逐个按实际宽度测量，避免用经验值估高在字号/缩放档下失准。
     */
    private fun applyScrollLimit() {
        val metrics = context.resources.displayMetrics
        val cardWidth = minOf(
            context.resources.getDimensionPixelSize(R.dimen.px1000),
            (metrics.widthPixels * 0.92f).toInt()
        )
        val contentWidth = (cardWidth - binding.root.paddingStart - binding.root.paddingEnd)
            .coerceAtLeast(0)
        val widthSpec = View.MeasureSpec.makeMeasureSpec(
            contentWidth,
            View.MeasureSpec.EXACTLY
        )
        val unspecified = View.MeasureSpec.makeMeasureSpec(
            0,
            View.MeasureSpec.UNSPECIFIED
        )
        binding.textTitle.measure(widthSpec, unspecified)
        binding.textActors.measure(widthSpec, unspecified)
        binding.buttonClose.measure(unspecified, unspecified)
        val res = context.resources
        val fixedHeight = binding.root.paddingTop + binding.root.paddingBottom +
            binding.textTitle.measuredHeight +
            res.getDimensionPixelSize(R.dimen.px12) +
            binding.textActors.measuredHeight +
            res.getDimensionPixelSize(R.dimen.px20) +
            res.getDimensionPixelSize(R.dimen.px2) +
            res.getDimensionPixelSize(R.dimen.px20) +
            res.getDimensionPixelSize(R.dimen.px20) +
            binding.buttonClose.measuredHeight +
            res.getDimensionPixelSize(R.dimen.px20)
        val maxCardHeight = minOf(
            res.getDimensionPixelSize(R.dimen.px900),
            (metrics.heightPixels * 0.92f).toInt()
        )
        binding.scrollBrief.maxHeight = (maxCardHeight - fixedHeight)
            .coerceAtLeast(res.getDimensionPixelSize(R.dimen.px200))
    }

    /** 中文首行缩进两字（缩进宽度按当前字号实测，随字号档缩放）。 */
    private fun firstLineIndent(view: TextView, text: String): CharSequence {
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

    companion object {
        fun show(context: Context, album: CctvAlbum) {
            CctvVodBriefDialog(context, album).show()
        }
    }
}
