package com.mytvb.ui.adapter

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.mytvb.R
import com.mytvb.core.ui.image.ImageLoader
import com.mytvb.feature.marmot.CctvChannelLogo
import com.mytvb.feature.marmot.domain.MarmotModels.Vod

/**
 * 频道列表适配器（双排右侧，严格对标参考 ChannelDualAdapter）。
 *
 * OnKey 逻辑（与参考完全一致）：
 * - 只在 ACTION_DOWN 处理，ACTION_UP 返回 false
 * - 上键在第一项 → 跳到最后（环形），return true
 * - 下键在最后项 → 跳到第一（环形），return true
 * - 左键 → 焦点切到左排当前选中分组，return true
 * - 右键 → 切换收藏，return true
 * - 其他 → return false（交给系统，正常上下移动）
 */
class ChannelAdapter(
    private val onChannelClick: (Vod) -> Unit
) : ListAdapter<Vod, ChannelAdapter.VH>(DIFF) {

    var currentPlayingUrl: String? = null
        set(value) {
            field = value
            // 只刷新可见项（避免 notifyDataSetChanged 重建 View 丢焦点）
            notifyItemRangeChanged(0, itemCount)
        }

    var groupRecyclerView: RecyclerView? = null
    var groupAdapter: GroupAdapter? = null

    private val transparentPlaceholder = android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val name: TextView = itemView.findViewById(R.id.channel_name)
        val playingMark: TextView = itemView.findViewById(R.id.playing_mark)
        val favoriteIcon: ImageView = itemView.findViewById(R.id.favorite_icon)
        val channelLogo: ImageView = itemView.findViewById(R.id.channel_logo)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_marmot_channel, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        holder.name.text = item.name
        // 台标：未命中隐藏（文字顶格）；命中加载（ImageLoader LruCache 兜住焦点滚动重复 bind）。
        // 占位/失败图传透明 ColorDrawable——fanmingming 部分网络环境（DNS 解析境外节点）
        // 会超时，默认灰块占位在列表里成排出现太丑，失败就当没有台标。
        // trimTransparent：省台标源图 180x108 且留白大，固定 2:1 容器 fitCenter 下显小，
        // 裁掉透明边后字形满幅（央视标本就满幅，no-op）
        val logoUrl = CctvChannelLogo.logoUrlFor(item.name)
        if (logoUrl == null) {
            holder.channelLogo.visibility = View.GONE
        } else {
            holder.channelLogo.visibility = View.VISIBLE
            ImageLoader.load(holder.channelLogo, logoUrl, transparentPlaceholder, transparentPlaceholder, trimTransparent = true)
        }
        val isPlaying = item.url == currentPlayingUrl
        holder.playingMark.visibility = if (isPlaying) View.VISIBLE else View.GONE
        holder.favoriteIcon.visibility = if (item.favorite) View.VISIBLE else View.GONE
        holder.favoriteIcon.setImageResource(
            if (item.favorite) R.drawable.ic_marmot_favorite_filled
            else R.drawable.ic_marmot_favorite_border
        )

        holder.itemView.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onChannelClick(getItem(pos))
        }

        holder.itemView.setOnKeyListener { v, keyCode, event ->
            val pos = holder.bindingAdapterPosition
            // 严格对标参考：只 ACTION_DOWN 处理，否则返回 false
            if (event.action != KeyEvent.ACTION_DOWN || pos == RecyclerView.NO_POSITION) {
                return@setOnKeyListener false
            }
            when (keyCode) {
                // 上键在第一项 → 跳到最后（环形）
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (pos == 0) {
                        scrollToPositionAndFocus(holder.itemView, itemCount - 1)
                        return@setOnKeyListener true
                    }
                }
                // 下键在最后项 → 跳到第一（环形）
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (pos == itemCount - 1) {
                        scrollToPositionAndFocus(holder.itemView, 0)
                        return@setOnKeyListener true
                    }
                }
                // 左键 → 焦点切到左排当前选中分组
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    val groupPos = groupAdapter?.currentSelected ?: 0
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        groupRecyclerView?.post {
                            val rv = groupRecyclerView ?: return@post
                            val target = rv.layoutManager?.findViewByPosition(groupPos)
                            if (target != null) {
                                target.requestFocus()
                            } else {
                                rv.smoothScrollToPosition(groupPos)
                                rv.post { rv.layoutManager?.findViewByPosition(groupPos)?.requestFocus() }
                            }
                        }
                    }
                    return@setOnKeyListener true
                }
                // 右键 → 切换收藏
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    val newPos = holder.bindingAdapterPosition
                    if (newPos != RecyclerView.NO_POSITION) {
                        val vod = getItem(newPos)
                        vod.favorite = !vod.favorite
                        notifyItemChanged(newPos)
                    }
                    return@setOnKeyListener true
                }
            }
            // 正常上下移动：交给系统
            false
        }
    }

    private fun scrollToPositionAndFocus(itemView: View, pos: Int) {
        val rv = itemView.parent as? RecyclerView ?: return
        rv.smoothScrollToPosition(pos)
        rv.post { rv.layoutManager?.findViewByPosition(pos)?.requestFocus() }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Vod>() {
            override fun areItemsTheSame(o: Vod, n: Vod) = o.url == n.url
            override fun areContentsTheSame(o: Vod, n: Vod) = o.name == n.name && o.url == n.url && o.favorite == n.favorite
        }
    }
}
