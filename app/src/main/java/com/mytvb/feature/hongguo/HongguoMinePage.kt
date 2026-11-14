package com.mytvb.feature.hongguo

import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mytvb.R
import com.mytvb.core.ui.base.AppToast
import com.mytvb.core.ui.image.ImageLoader
import com.mytvb.databinding.CellHongguoLaneBinding
import com.mytvb.databinding.FragmentHongguoMineBinding
import com.mytvb.feature.hongguo.net.HongguoSync
import com.mytvb.ui.activity.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 红果「我的」页（2026-11-12 二次重构：未登录=整页居中登录引导（大头像+权益文案+
 * 主钮，单一焦点单一 CTA）；已登录=hero 头部（大头像/昵称/红果号/同步与统计/
 * 退出+清除描边钮）+ 我的预约横滑 lane（HongguoDramaAdapter 竖版卡，
 * 点卡片开详情弹窗、「更多」进货架网格页）。收藏/历史有独立顶部 tab 不在此重复
 * （lane 视图保留作「红果页显示历史/收藏」开关关闭时的并入位）。
 * 红果账号与 B 站完全隔离（票据在 hongguo_session，登出只清红果票据保留设备身份）。
 */
class HongguoMinePage : Fragment(), HongguoPageFocus {

    /** 一条入口 lane：cell 视图 + 独立 adapter + 对应货架页数据源。 */
    private class Lane(
        val cell: CellHongguoLaneBinding,
        val adapter: HongguoDramaAdapter,
        val source: HongguoShelfPage.Source,
        /** 数量单位：true=「共 N 部」，false=「共 N 条」（历史）。 */
        val countInSeries: Boolean
    )

    private var _binding: FragmentHongguoMineBinding? = null
    private val binding get() = _binding!!
    private val repository by lazy { HongguoRepository.get(requireContext()) }
    private val lanes = mutableListOf<Lane>()
    private var loggedIn = false
    private var subscribeItems: List<HongguoSync.SubscribeItem> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHongguoMineBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.buttonLogin.setOnClickListener { showLoginDialog() }
        binding.buttonLogout.setOnClickListener {
            repository.account.logout()
            refreshLoginState()
            AppToast.show(requireContext(), "已退出红果账号")
        }
        binding.buttonClear.setOnClickListener {
            repository.clearLocal()
            refreshLoginState()
            AppToast.show(requireContext(), "已清除本机红果数据")
        }
        // 账号区按钮 LEFT 逃逸：居中/右上布局的左侧同一水平 beam 内无可聚焦 view，
        // FocusFinder 几何搜索找不到目标会原地不动（用户实测 LEFT 失灵、UP/DOWN 反而能走），
        // 显式接侧栏当前 tab（内容区左缘→侧栏，全 App 惯例同款）
        val leftToSidebar = View.OnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                (activity as? MainActivity)?.focusSidebarCurrentTab() == true
            } else false
        }
        binding.buttonLogin.setOnKeyListener(leftToSidebar)
        binding.buttonLogout.setOnKeyListener(leftToSidebar)
        binding.buttonClear.setOnKeyListener(leftToSidebar)
        setupLanes()
        refreshLoginState()
        // ScrollView 根与 lane RV 自身不可聚焦：ScrollView 构造器 initScrollView 会
        // setFocusable(true)（XML 覆盖不了，必须代码设），DOWN 放行后它们以全页
        // 几何赢过一切子项——首次 DOWN 焦点"消失"（落在无高亮的容器上）的根因
        binding.root.isFocusable = false
    }

    /** 侧栏 RIGHT / 顶部 tab DOWN 的落点：未登录→登录按钮，已登录→第一可见 lane 首卡（空 lane 落「更多」钮）。 */
    override fun focusFirstCard(): Boolean {
        if (!isAdded || view == null) return false
        if (binding.buttonLogin.isVisible) return binding.buttonLogin.requestFocus()
        binding.root.isFocusable = false
        val lane = lanes.firstOrNull { it.cell.root.isVisible }
        if (lane == null) {
            // lane 全空：落账号区按钮，别返回 false 放行（放行=框架几何乱跳）
            return binding.buttonLogout.requestFocus() || binding.buttonClear.requestFocus()
        }
        lane.cell.rvLane.isFocusable = false
        val vh = lane.cell.rvLane.findViewHolderForAdapterPosition(0)
        return vh?.itemView?.requestFocus() == true ||
            lane.cell.rvLane.getChildAt(0)?.requestFocus() == true ||
            lane.cell.buttonLaneMore.requestFocus()
    }

    private fun showLoginDialog() {
        HongguoLoginDialog.show(requireContext(), repository) { refreshLoginState() }
    }

    private fun openShelf(source: HongguoShelfPage.Source) {
        val host = parentFragment as? HongguoFragment ?: return
        host.showShelfOverlay(source)
    }

    private fun setupLanes() {
        lanes.clear()
        fun newAdapter() = HongguoDramaAdapter(
            onItemClick = { item, _ ->
                HongguoDetailDialog.show(requireContext(), repository, item.seriesId, item.score)
            },
            onItemLongPress = { item, _ ->
                HongguoCardMenuDialog.show(requireContext(), repository, item)
            },
            // 横向单行卡根默认 MATCH_PARENT 会撑满整屏宽，必须给定实际像素（Fragment 属性初始化无 resources，走方法内）
            itemWidthPx = resources.getDimensionPixelSize(R.dimen.px135)
        )
        lanes += Lane(binding.laneSubscribe, newAdapter(), HongguoShelfPage.Source.SUBSCRIBE, true)
        lanes += Lane(binding.laneFavorite, newAdapter(), HongguoShelfPage.Source.FAVORITE, true)
        lanes += Lane(binding.laneHistory, newAdapter(), HongguoShelfPage.Source.HISTORY, false)
        lanes[0].cell.textLaneTitle.setText(R.string.hongguo_my_subscribe)
        lanes[1].cell.textLaneTitle.setText(R.string.hongguo_tab_favorite)
        lanes[2].cell.textLaneTitle.setText(R.string.hongguo_tab_history)
        lanes.forEach { lane ->
            lane.cell.rvLane.layoutManager =
                LinearLayoutManager(requireContext(), RecyclerView.HORIZONTAL, false)
            lane.cell.rvLane.adapter = lane.adapter
            lane.cell.rvLane.itemAnimator = null
            lane.cell.buttonLaneMore.setOnClickListener { openShelf(lane.source) }
        }
    }

    private fun refreshLoginState() {
        val profile = repository.account.storedProfile()
        loggedIn = profile != null
        val stats = getString(
            R.string.hongguo_mine_stats,
            repository.favorites().size,
            repository.history().size
        )
        // 两态整页切换：未登录只留居中登录引导（账号行隐藏）；已登录=hero 头部+lane
        binding.rowAccount.isVisible = loggedIn
        binding.emptyGuest.isVisible = !loggedIn
        if (profile != null) {
            binding.textNickname.text = profile.name.ifEmpty { "红果用户" }
            binding.textUid.text = "红果号：${profile.uid}"
            binding.textStats.text = getString(R.string.hongguo_cloud_sync_on) + " · " + stats
            binding.dotSync.isVisible = true
            binding.groupAccountActions.isVisible = true
            // 启动登录态校验+token 滚动（HGTV H0/g.c() scene=boot 同款；失败静默）
            lifecycleScope.launch {
                runCatching { withContext(Dispatchers.IO) { repository.sync.bootVerify() } }
            }
            applyAvatar(profile.avatar.takeIf { it.startsWith("http") })
            fetchSubscribe()
        } else {
            subscribeItems = emptyList()
        }
        refreshLanes()
    }

    /** 默认图标=圆形衬底+主题色染色；网络头像=清衬底直显方图（沿用旧版非圆行为）。 */
    private fun applyAvatar(url: String?) {
        if (url != null) {
            binding.imageAvatar.background = null
            binding.imageAvatar.setPadding(0, 0, 0, 0)
            binding.imageAvatar.imageTintList = null
            ImageLoader.load(
                imageView = binding.imageAvatar,
                url = url,
                placeholder = R.drawable.ic_hongguo,
                error = R.drawable.ic_hongguo
            )
        } else {
            binding.imageAvatar.setBackgroundResource(R.drawable.bg_hongguo_avatar)
            val pad = resources.getDimensionPixelSize(R.dimen.px16)
            binding.imageAvatar.setPadding(pad, pad, pad, pad)
            binding.imageAvatar.setImageResource(R.drawable.ic_hongguo)
            binding.imageAvatar.imageTintList = android.content.res.ColorStateList.valueOf(
                com.google.android.material.color.MaterialColors.getColor(
                    binding.imageAvatar, androidx.appcompat.R.attr.colorAccent
                )
            )
        }
    }

    /**
     * 正文 lane 填充：已登录三条恒显示（空 lane=灰字暂无行，App 空态语言）；
     * 未登录云端类隐藏、本机类仅有本机数据时显示，全部为空时显示居中空态。
     */
    private fun refreshLanes() {
        bindLane(lanes[0], loggedIn, subscribeItems.map { it.toDramaUi() })
        // 收藏/历史已有独立顶部 tab（六 tab），我的页不重复铺；lane 视图保留作
        // 「红果页显示历史/收藏」开关（三定案②，待第三波设置项）关闭=四 tab 模式的并入位
        bindLane(lanes[1], false, emptyList())
        bindLane(lanes[2], false, emptyList())
        // 卡片 UP 不拦截（isAtTopEdge 只认竖版列表，横向 lane 全 App 统一走框架几何导航：
        // 卡→「更多」钮→账号行/顶部 tab，与首页横滑 lane 行为一致）
    }

    private fun bindLane(lane: Lane, visible: Boolean, items: List<DramaUi>) {
        lane.cell.root.isVisible = visible
        lane.adapter.clear()
        if (!visible) return
        val empty = items.isEmpty()
        lane.cell.textLaneCount.isVisible = !empty
        lane.cell.textLaneEmpty.isVisible = empty
        lane.cell.rvLane.isVisible = !empty
        if (empty) return
        lane.cell.textLaneCount.text = getString(
            if (lane.countInSeries) R.string.hongguo_lane_count_series else R.string.hongguo_shelf_count,
            items.size
        )
        lane.adapter.addData(items)
    }

    /** 我的预约 lane（登录态，云端异步拉取；只刷新本 lane，避免整场刷新闪动 lane 卡焦点）。 */
    private fun fetchSubscribe() {
        lifecycleScope.launch {
            subscribeItems = try {
                withContext(Dispatchers.IO) { repository.subscribeList(onlyOnline = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (_binding == null) return@launch
                emptyList()
            }
            if (_binding == null || !loggedIn) return@launch
            bindLane(lanes[0], true, subscribeItems.map { it.toDramaUi() })
        }
    }

    // ---- 卡片数据映射（与 HongguoShelfPage 同口径） ----

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
        fun newInstance(): HongguoMinePage = HongguoMinePage()
    }
}
