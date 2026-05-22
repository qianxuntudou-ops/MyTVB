package com.tutu.myblbl.feature.live

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.tutu.myblbl.core.ui.image.ImageLoader
import com.tutu.myblbl.R
import com.tutu.myblbl.databinding.CellUserBinding
import com.tutu.myblbl.model.live.LiveAreaCategory
import com.tutu.myblbl.core.ui.focus.VideoCardFocusHelper

class LiveAreaAdapter(
    private val onItemClick: (LiveAreaCategory) -> Unit,
    private val onTopEdgeUp: (() -> Boolean)? = null
) : RecyclerView.Adapter<LiveAreaAdapter.ViewHolder>() {

    private val items = ArrayList<LiveAreaCategory>()

    init {
        setHasStableIds(true)
    }

    fun setData(list: List<LiveAreaCategory>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun getItemId(position: Int): Long =
        items.getOrNull(position)?.id ?: RecyclerView.NO_ID

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = CellUserBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    inner class ViewHolder(
        private val binding: CellUserBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.textSub.visibility = View.GONE
            binding.imageView.setBorderEnabled(false)
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemClick(items[position])
                }
            }
            VideoCardFocusHelper.bindSidebarExit(
                view = binding.root,
                onTopEdgeUp = onTopEdgeUp
            )
        }

        fun bind(item: LiveAreaCategory) {
            binding.textView.text = item.title.ifBlank { item.name }
            ImageLoader.loadSmallSquare(
                imageView = binding.imageView,
                url = item.pic,
                placeholder = R.drawable.default_avatar
            )
        }
    }
}
