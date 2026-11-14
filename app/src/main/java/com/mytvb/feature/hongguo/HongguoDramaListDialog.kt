package com.mytvb.feature.hongguo

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.Window
import androidx.appcompat.app.AppCompatDialog
import androidx.recyclerview.widget.GridLayoutManager
import com.mytvb.R
import com.mytvb.core.ui.base.adaptiveSpanCount
import com.mytvb.core.ui.decoration.GridSpacingItemDecoration
import com.mytvb.core.ui.layout.WrapContentGridLayoutManager
import com.mytvb.databinding.DialogHongguoDramaListBinding
import com.mytvb.feature.hongguo.net.HongguoClient

/**
 * 红果长按菜单结果列表（创作者作品/相关作品共用弹窗）：
 * 竖版 4:5 网格（[HongguoDramaAdapter]）。宽 px1800（overlay 自动钳 92% 屏宽）
 * + 列数走 [adaptiveSpanCount]（与 feed/搜索/榜单页同口径），卡片大小与
 * 列表页标准卡一致；点卡片关本弹窗进 [HongguoDetailDialog]。
 */
class HongguoDramaListDialog(
    context: Context,
    private val repository: HongguoRepository,
    private val title: String,
    private val items: List<HongguoClient.Drama>
) : AppCompatDialog(context, R.style.DialogTheme) {

    private lateinit var binding: DialogHongguoDramaListBinding
    private val adapter = HongguoDramaAdapter(onItemClick = { item, _ ->
        dismiss()
        HongguoDetailDialog.show(context, repository, item.seriesId)
    })

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        binding = DialogHongguoDramaListBinding.inflate(layoutInflater)
        com.mytvb.core.ui.base.DialogWindowFit.overlay(
            dialog = this,
            content = binding.root,
            widthPx = context.resources.getDimensionPixelSize(R.dimen.px1800),
            heightPx = context.resources.getDimensionPixelSize(R.dimen.px1000),
            tag = "hongguo_drama_list"
        )
        binding.textTitle.text = title
        // 列数与列表页同口径（含超宽屏 wide 档 + 用户界面缩放/卡片大小偏移），
        // 弹窗宽钳 92% 屏宽后卡片大小≈列表页标准卡
        val spanCount = context.resources.adaptiveSpanCount(base = 6, wide = 12)
        val lm: GridLayoutManager = WrapContentGridLayoutManager(context, spanCount)
        binding.recyclerDramas.layoutManager = lm
        binding.recyclerDramas.adapter = adapter
        binding.recyclerDramas.itemAnimator = null
        binding.recyclerDramas.addItemDecoration(
            GridSpacingItemDecoration(
                spanCount,
                context.resources.getDimensionPixelSize(R.dimen.px16),
                false
            )
        )
        // 弹窗内 RV 不参与页面焦点锚点，除名 RV 自身避免几何导航抢焦（Feed 页同款）
        binding.recyclerDramas.isFocusable = false
        if (items.isEmpty()) {
            binding.textState.visibility = View.VISIBLE
            binding.recyclerDramas.visibility = View.GONE
        } else {
            adapter.setDataDeduplicated(items.map { it.toDramaUi() })
        }
        setOnShowListener {
            // 首帧布局完成后再聚焦第 0 项（Dialog 首次布局未完成时 requestFocus 无效）
            binding.recyclerDramas.post {
                binding.recyclerDramas.findViewHolderForAdapterPosition(0)
                    ?.itemView?.requestFocus()
            }
        }
    }

}
