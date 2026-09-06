package com.tutu.myblbl.feature.home

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.tutu.myblbl.event.AppEventHub
import com.tutu.myblbl.core.ui.base.RecyclerViewPoolPrewarmer
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class RecommendListFragment : VideoFeedFragment() {

    companion object {
        fun newInstance(): RecommendListFragment {
            return RecommendListFragment()
        }
    }

    private val appEventHub: AppEventHub by inject()
    private val viewModel: RecommendViewModel by viewModel()

    override val feedViewModel: VideoFeedViewModel
        get() = viewModel
    override val secondaryTabPosition: Int = 0
    override val dispatchHomeContentReady: Boolean = true
    override val enableTvListFocusController: Boolean = true
    override val deferInitialLoadUntilFirstDraw: Boolean = true
    override val showInitialLoadingIndicator: Boolean = true

    /**
     * 等待首屏数据的 ~500ms 里主线程基本空闲（请求已在 Application 层预加载发出），
     * 用这段空闲逐帧预建视频卡 ViewHolder：首批 16 张卡命中池子只需 bind，
     * 省掉一半 inflate。initialDelay 取 150ms 避开 Fragment 创建/首帧的忙碌期；
     * 数据到达（adapter 有数据）自动停，不会与真实渲染争时间片。
     * （133b814a 禁用是因为当时请求串行在 UI 之后、预热会抢壳首帧；预加载恢复后时序已反转。）
     */
    override val initialViewHolderPrewarmPlan: RecyclerViewPoolPrewarmer.Plan =
        RecyclerViewPoolPrewarmer.Plan(
            count = 12,
            budgetMs = 450L,
            maxPoolSize = 60,
            initialDelayMs = 150L,
            stopWhenAdapterHasItems = true
        )

    override fun initObserver() {
        super.initObserver()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                appEventHub.events.collectLatest { event ->
                    if (event == AppEventHub.Event.UserSessionChanged && isResumed && !isLoading) {
                        refresh()
                    }
                }
            }
        }
    }
}
