package com.mytvb.feature.hongguo

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.mytvb.R
import com.mytvb.core.ui.base.AppToast
import com.mytvb.core.ui.base.adaptiveSpanCount
import com.mytvb.core.ui.decoration.GridSpacingItemDecoration
import com.mytvb.core.ui.layout.WrapContentGridLayoutManager
import com.mytvb.databinding.FragmentHongguoShelfBinding
import com.mytvb.feature.hongguo.net.HongguoSync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 红果封面货架页（2026-11-11 重设计）：我的页三入口共用，替代纯文字 ChoiceDialog。
 * 网格货架 + 统一竖版卡（HongguoDramaAdapter，列数同 HongguoFeedPage 6 列档，
 * 卡宽由网格均分与正常列表页一致）：历史带「看到 x/y 集」进度条；
 * 预约带上线时间；OK 打开详情弹窗。宿主 HongguoFragment 以全屏覆盖层承载，
 * BACK 收层并还原功能卡焦点。无触底加载/锚点需求，不接 TvListFocusController，
 * 焦点滚动走 RecyclerView 框架自动滚动。
 */
class HongguoShelfPage : Fragment(), HongguoPageFocus {

    private var _binding: FragmentHongguoShelfBinding? = null
    private val binding get() = _binding!!
    private val repository by lazy { HongguoRepository.get(requireContext()) }

    // 网格列数对齐正常列表页（HongguoFeedPage 同款 6 列/超宽 12 列），卡宽不固定
    private val spanCount by lazy { resources.adaptiveSpanCount(base = 6, wide = 12) }
    private val adapter by lazy {
        HongguoDramaAdapter(
            onItemClick = ::openDetail,
            onItemLongPress = { item, _ ->
                HongguoCardMenuDialog.show(requireContext(), repository, item)
            }
        )
    }

    private var source = Source.SUBSCRIBE

    /** 外部焦点入口（触摸模式盲恢复等）：空态/未显示时不接焦点，落位复用开层聚焦逻辑。 */
    override fun focusFirstCard(): Boolean {
        if (_binding == null || adapter.itemCount == 0 || !binding.root.isShown) return false
        val vh = binding.recyclerShelf.findViewHolderForAdapterPosition(0) ?: return false
        return vh.itemView.requestFocus()
    }

    /** 货架数据源：标题与拉取逻辑一一对应。 */
    enum class Source(val titleRes: Int) {
        SUBSCRIBE(R.string.hongguo_my_subscribe),
        FAVORITE(R.string.hongguo_tab_favorite),
        HISTORY(R.string.hongguo_tab_history)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHongguoShelfBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.recyclerShelf.layoutManager = WrapContentGridLayoutManager(requireContext(), spanCount)
        binding.recyclerShelf.adapter = adapter
        binding.recyclerShelf.itemAnimator = null
        binding.recyclerShelf.addItemDecoration(
            GridSpacingItemDecoration(
                spanCount,
                resources.getDimensionPixelSize(R.dimen.px16),
                false
            )
        )
        // 第一行 UP 回标题行返回钮：覆盖层全屏不透明，焦点去被盖住的顶部 tab 会「焦点消失」
        adapter.bindTopEdgeUp { binding.buttonShelfBack.requestFocus() }
        adapter.setOnItemFocusListeners(onFocused = { }, onFocusedWithView = { _, _ -> })
        binding.buttonShelfBack.setOnClickListener { host()?.hideShelfOverlay() }
        source = Source.values().getOrElse(
            arguments?.getInt(ARG_SOURCE) ?: 0
        ) { Source.SUBSCRIBE }
        load(source)
    }

    /** 复用实例换数据源（覆盖层重开时 onViewCreated 不会再跑）。 */
    fun reopen(newSource: Source) {
        if (_binding == null) return
        source = newSource
        load(source)
    }

    private fun host(): HongguoFragment? = parentFragment as? HongguoFragment

    private fun openDetail(item: DramaUi, position: Int) {
        HongguoDetailDialog.show(requireContext(), repository, item.seriesId, item.score)
    }

    private fun load(source: Source) {
        binding.textShelfTitle.setText(source.titleRes)
        binding.recyclerShelf.scrollToPosition(0)
        lifecycleScope.launch {
            val items: List<DramaUi> = try {
                withContext(Dispatchers.IO) { fetch(source) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppToast.show(requireContext(), e.message ?: "列表拉取失败")
                emptyList()
            }
            if (_binding == null) return@launch
            adapter.clear()
            adapter.addData(items)
            binding.textShelfCount.text = getString(R.string.hongguo_shelf_count, items.size)
            binding.emptyShelf.root.visibility =
                if (items.isEmpty()) View.VISIBLE else View.GONE
            if (items.isNotEmpty()) {
                // 覆盖层打开即落第一张卡（焦点滚动由框架驱动）
                binding.recyclerShelf.post {
                    _binding?.recyclerShelf
                        ?.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                }
            }
        }
    }

    private suspend fun fetch(source: Source): List<DramaUi> = when (source) {
        Source.FAVORITE -> repository.favorites().map { it.toDramaUi() }
        Source.HISTORY -> repository.history().map { it.toDramaUi() }
        Source.SUBSCRIBE -> repository.subscribeList(onlyOnline = false).map { it.toDramaUi() }
    }

    private fun HongguoRepository.WatchRecord.toDramaUi(): DramaUi {
        val seen = episodeIndex + 1
        val done = episodeCount in 1..seen
        val pct = when {
            done -> 100
            durationMs > 0 -> ((positionMs * 100) / durationMs).toInt().coerceIn(1, 99)
            else -> 0
        }
        return DramaUi(
            seriesId = seriesId,
            title = title,
            cover = cover,
            subtitle = if (done) getString(R.string.hongguo_seen_done)
            else getString(R.string.hongguo_seen_episode, seen, episodeCount),
            progressPercent = pct
        )
    }

    private fun HongguoSync.SubscribeItem.toDramaUi(): DramaUi = DramaUi(
        seriesId = seriesId,
        title = title,
        cover = cover,
        subtitle = if (onlineTimeSec > 0)
            getString(R.string.hongguo_subscribe_online_at, formatOnlineTime(onlineTimeSec))
        else
            getString(R.string.hongguo_subscribe_pending)
    )

    private fun formatOnlineTime(sec: Long): String {
        val fmt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)
        return fmt.format(java.util.Date(sec * 1000))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_SOURCE = "source"

        fun newInstance(source: Source): HongguoShelfPage = HongguoShelfPage().apply {
            arguments = Bundle().apply { putInt(ARG_SOURCE, source.ordinal) }
        }
    }
}
