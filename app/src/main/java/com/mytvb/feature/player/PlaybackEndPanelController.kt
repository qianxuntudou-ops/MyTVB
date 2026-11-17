package com.mytvb.feature.player

import android.view.Gravity
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.LinearLayoutCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mytvb.R
import com.mytvb.core.common.log.AppLog
import com.mytvb.core.ui.base.VideoRecyclerViewTuning
import com.mytvb.model.video.VideoModel
import com.mytvb.ui.adapter.VideoAdapter

/**
 * 播放结束推荐页：连播倒计时期间按返回键=取消连播并展示本页。
 * 黑底覆盖层 + 重播按钮 + 相关推荐网格（3 列，形态对标 YouTube TV 播完页）。
 * 应用内覆盖层而非 Dialog，规避电视端弹窗裁高问题；数据复用宿主已过滤的 related 流。
 */
class PlaybackEndPanelController(
    private val activity: AppCompatActivity,
    private val panel: View,
    private val recyclerView: RecyclerView,
    private val replayButton: View,
    private val relatedTitle: View,
    private val relatedProvider: () -> List<VideoModel>,
    private val maxItems: Int = 8,
    private val onReplay: () -> Unit,
    private val onPlayVideo: (VideoModel) -> Unit
) {

    private val adapter = VideoAdapter(
        titleLines = 1,
        showOwnerLine = false,
        onLeftEdge = { true },
        onRightEdge = { true },
        onBottomEdgeDown = { true }
    )
    private val contentRoot = panel.findViewById<LinearLayoutCompat>(R.id.layout_play_end_content)

    init {
        // 推荐数据只有一页（relatedProvider 快照），关掉 BaseAdapter 默认的
        // 「正在加载..」尾部占位，否则滚到底是一个永远不会消失的加载圈
        adapter.setShowLoadMore(false)
        recyclerView.layoutManager =
            GridLayoutManager(activity, END_PANEL_SPAN_COUNT, RecyclerView.VERTICAL, false)
        recyclerView.adapter = adapter
        recyclerView.itemAnimator = null
        adapter.setOnItemClickListener { _, item -> onPlayVideo(item) }
        replayButton.setOnClickListener { onReplay() }
        replayButton.nextFocusDownId = R.id.recyclerViewPlayEnd
    }

    fun isShowing(): Boolean = panel.isVisible

    fun show() {
        val items = relatedProvider().take(maxItems)
        adapter.setDataDeduplicated(items)
        recyclerView.isVisible = items.isNotEmpty()
        relatedTitle.isVisible = items.isNotEmpty()
        // 无推荐时可见内容只剩重播按钮，整页改垂直居中（按钮自身已水平居中）；
        // 有推荐时恢复默认顶部起始布局，两种形态每次 show 对称切换
        contentRoot.gravity = if (items.isEmpty()) Gravity.CENTER else Gravity.TOP
        if (!panel.isVisible) {
            panel.visibility = View.VISIBLE
        }
        // 空列表时 getItemViewType(0) 取不到 viewType，共享池设置须等数据就位后执行（幂等）
        VideoRecyclerViewTuning.apply(recyclerView, adapter)
        // 焦点固定给重播按钮：与官方播完页一致，取消连播后最可能的动作是重播或挑一个推荐。
        // 同步 requestFocus + 无条件 post 重试：触摸模式下同步请求会静默失败；若此前有
        // 面板 dismiss 调度的延迟焦点恢复 post（issue#119 追加反馈）排在本帧之后执行，
        // 会把焦点抢回控制栏，这一帧保证焦点最终落回重播按钮
        val focusTaken = replayButton.requestFocus()
        replayButton.post { replayButton.requestFocus() }
        AppLog.i(TAG, "end panel show: items=${items.size} focusTaken=$focusTaken")
    }

    fun hide() {
        if (!panel.isVisible) return
        AppLog.i(TAG, "end panel hide")
        panel.visibility = View.GONE
    }

    /**
     * 重新把焦点交给重播按钮：面板 show 时若焦点正被顶层窗口（评论/选集等 Dialog）
     * 持有，requestFocus 会静默失败；等该窗口关闭、宿主要恢复焦点前调用本方法接管
     * （issue#119 追加反馈：评论区开着播完，返回后焦点落在播放器而非重播按钮）。
     */
    fun refocusReplay() {
        val focusTaken = replayButton.requestFocus()
        // 无条件补一帧重试：可能存在面板 dismiss 调度的延迟焦点恢复 post 排在后面，
        // 其执行会把焦点抢走，这里保证最终落回重播按钮（requestFocus 幂等）
        replayButton.post { replayButton.requestFocus() }
        AppLog.i(TAG, "end panel refocusReplay focusTaken=$focusTaken")
    }

    private companion object {
        const val TAG = "PlaybackEndPanel"
        // 4 列 × 2 行 = 8 张卡完整可见（封面 16:9 硬比例下 3 列两行在 21:9 屏放不下）
        const val END_PANEL_SPAN_COUNT = 4
    }
}
