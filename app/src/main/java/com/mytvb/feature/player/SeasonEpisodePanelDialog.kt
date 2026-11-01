package com.mytvb.feature.player

import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mytvb.R
import com.mytvb.core.common.log.AppLog
import com.mytvb.core.ui.base.DialogWindowFit
import com.mytvb.core.ui.base.ScaledTextView
import com.mytvb.feature.player.VideoPlayerViewModel.PlayableEpisode

/** 合集面板分组大小：全集按此数分 tab 展示。 */
private const val SEASON_GROUP_SIZE = 50

/**
 * 合集选集弹窗。BACK 键由 AppCompat 的 OnBackPressedDispatcher 在窗口层
 * 直接消费（不经过 view 树与 onKeyDown），所以通过 [backPressedHandler]
 * 注册 callback 拦截：返回 false 表示已处理（回 tab 不关弹窗），
 * 返回 true 走默认关闭流程。
 */
class SeasonEpisodeDialog(
    context: AppCompatActivity,
    backPressedHandler: () -> Boolean
) : AppCompatDialog(context, R.style.DialogTheme) {

    init {
        onBackPressedDispatcher.addCallback(
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    AppLog.i("SeasonPanel", "dispatcher handleOnBackPressed")
                    if (backPressedHandler()) {
                        // 默认行为：关闭弹窗（等价 onBackPressed → cancel）
                        this@SeasonEpisodeDialog.cancel()
                    }
                    // 否则已由 handler 处理（焦点回 tab），弹窗保持打开
                }
            }
        )
    }
}

private fun isFocusInsideRecyclerView(focus: View?, recyclerView: RecyclerView?): Boolean {
    if (focus == null || recyclerView == null) return false
    var v: View? = focus
    while (v != null) {
        if (v === recyclerView) return true
        v = v.parent as? View
    }
    return false
}

/**
 * 贴屏幕右侧的竖版合集面板（B 站风格），普通播放器与央视影视选集共用：
 * 全集按 [SEASON_GROUP_SIZE] 个一组分 tab 展示；打开时焦点落在
 * 正在播放视频所在分组的 tab 上，列表自动定位到正在播放的视频。
 * 点击分集即 dismiss 并回调 [onPlayEpisode]（全局下标）。
 */
@UnstableApi
fun showSeasonEpisodePanelDialog(
    activity: AppCompatActivity,
    title: String,
    episodes: List<PlayableEpisode>,
    selectedEpisodeIndex: Int,
    onPlayEpisode: (globalIndex: Int) -> Unit,
    onDismiss: () -> Unit
) {
    // BACK 拦截：AppCompat 的 OnBackPressedDispatcher 会在窗口层截走 BACK、
    // 不经过 view 树与 onKeyDown，必须用 callback 注册才能拦截。
    // 焦点在视频列表内 → 回分组 tab 不关弹窗；否则走默认关闭。
    var seasonDialogRef: SeasonEpisodeDialog? = null
    var recyclerViewRef: RecyclerView? = null
    var backToTabAction: (() -> Unit)? = null
    fun isFocusInsideSeasonList(): Boolean =
        isFocusInsideRecyclerView(seasonDialogRef?.window?.currentFocus, recyclerViewRef)
    val dialog = SeasonEpisodeDialog(
        activity,
        backPressedHandler = {
            val focus = seasonDialogRef?.window?.currentFocus
            val inList = isFocusInsideSeasonList()
            AppLog.i(
                "SeasonPanel",
                "backPressed focus=${focus?.javaClass?.simpleName}#${focus?.id} inList=$inList"
            )
            if (inList) {
                backToTabAction?.invoke()
                false
            } else {
                true
            }
        }
    ).also { seasonDialogRef = it }
    dialog.setCanceledOnTouchOutside(true)
    // 贴屏幕右侧、上下撑满整屏；宽 px600 固定，高走 fill 载体撑满
    // （不钳 92%），列表随屏高获得最大可视区
    DialogWindowFit.overlay(
        dialog,
        LayoutInflater.from(activity).inflate(R.layout.dialog_choose_episode_season, null),
        activity.resources.getDimensionPixelSize(R.dimen.px600),
        fillHeight = true,
        gravity = Gravity.END,
        tag = "seasonEpisode"
    )

    val titleView = dialog.findViewById<TextView>(R.id.top_title)
    val recyclerView = dialog.findViewById<RecyclerView>(R.id.recyclerView)
    val tabBar = dialog.findViewById<LinearLayout>(R.id.tab_bar)
    if (recyclerView == null || tabBar == null) {
        dialog.dismiss()
        return
    }
    recyclerViewRef = recyclerView

    titleView?.text = title

    val groups = episodes.chunked(SEASON_GROUP_SIZE)
    var activeGroup = (selectedEpisodeIndex / SEASON_GROUP_SIZE).coerceIn(0, groups.lastIndex)
    val tabViews = mutableListOf<TextView>()

    fun backToSeasonTab() {
        tabViews.getOrNull(activeGroup)?.requestFocus()
    }
    backToTabAction = { backToSeasonTab() }

    val panelAdapter = PlayerSeasonEpisodePanelAdapter(
        onClick = { globalIndex ->
            dialog.dismiss()
            onPlayEpisode(globalIndex)
        },
        onBackToTab = { backToSeasonTab() }
    )
    recyclerView.layoutManager = LinearLayoutManager(activity)
    recyclerView.adapter = panelAdapter

    fun selectSeasonGroup(index: Int) {
        activeGroup = index
        tabViews.forEachIndexed { i, tab -> tab.isSelected = i == index }
        val offset = index * SEASON_GROUP_SIZE
        panelAdapter.submitGroup(groups[index], offset, selectedEpisodeIndex)
        // 默认定位到正在播放的视频；该组没有播放项则回到组首
        val localIndex = selectedEpisodeIndex - offset
        recyclerView.scrollToPosition(
            if (localIndex in groups[index].indices) localIndex else 0
        )
    }

    groups.forEachIndexed { index, group ->
        val start = index * SEASON_GROUP_SIZE + 1
        val end = index * SEASON_GROUP_SIZE + group.size
        val tab = buildSeasonTab(activity, "$start-$end")
        tab.setOnFocusChangeListener { _, hasFocus ->
            tab.setTextColor(
                ContextCompat.getColor(
                    activity,
                    if (hasFocus) R.color.colorAccent else R.color.textColor
                )
            )
            // 焦点在 tab 间移动即联动切换对应分组列表
            if (hasFocus) selectSeasonGroup(index)
        }
        tab.setOnClickListener { tab.requestFocus() }
        // 边缘处的左右键直接消费，焦点停在边界 tab 上
        tab.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (index > 0) tabViews[index - 1].requestFocus()
                    true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (index < tabViews.lastIndex) tabViews[index + 1].requestFocus()
                    true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    // 落在视口最上方可见项（视觉上紧邻 tab 的那项），与
                    // “首项按上回 tab”对称往返；不定位正在播放项——播放项
                    // 非组首时，从首项回 tab 再按下会表现为焦点越过第 1 项
                    // 直接落到播放项上
                    val lm = recyclerView.layoutManager as? LinearLayoutManager
                    focusSeasonEpisode(
                        recyclerView,
                        lm?.findFirstVisibleItemPosition()?.takeIf { it >= 0 } ?: 0
                    )
                    true
                }
                else -> false
            }
        }
        tabBar.addView(tab)
        tabViews.add(tab)
    }

    selectSeasonGroup(activeGroup)

    // 初始焦点：优先落在正在播放的视频上；拿不到播放信息时才落分组 tab
    fun requestInitialSeasonFocus() {
        val localIndex = selectedEpisodeIndex - activeGroup * SEASON_GROUP_SIZE
        if (localIndex in groups[activeGroup].indices) {
            focusSeasonEpisode(recyclerView, localIndex)
        } else {
            tabViews.getOrNull(activeGroup)?.requestFocus()
        }
    }

    dialog.window?.decorView?.viewTreeObserver?.addOnWindowFocusChangeListener(
        object : ViewTreeObserver.OnWindowFocusChangeListener {
            override fun onWindowFocusChanged(hasFocus: Boolean) {
                if (hasFocus) {
                    dialog.window?.decorView?.viewTreeObserver
                        ?.removeOnWindowFocusChangeListener(this)
                    requestInitialSeasonFocus()
                }
            }
        }
    )

    dialog.setOnDismissListener {
        onDismiss()
    }
    dialog.show()
    // 布局完成后兜底设初始焦点；窗口焦点监听在部分路径（触摸唤起）下时机不稳
    recyclerView.post {
        if (dialog.isShowing) {
            requestInitialSeasonFocus()
        }
    }
}

private fun buildSeasonTab(activity: AppCompatActivity, label: String): ScaledTextView {
    val res = activity.resources
    return ScaledTextView(activity).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.px24))
        setTextColor(ContextCompat.getColor(activity, R.color.textColor))
        setBackgroundResource(R.drawable.cell_background)
        setPadding(
            res.getDimensionPixelSize(R.dimen.px25),
            res.getDimensionPixelSize(R.dimen.px15),
            res.getDimensionPixelSize(R.dimen.px25),
            res.getDimensionPixelSize(R.dimen.px15)
        )
        isFocusable = true
        isClickable = true
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            marginEnd = res.getDimensionPixelSize(R.dimen.px15)
        }
    }
}

/**
 * 面板内目标项聚焦：同步命中直接聚焦；未布局时 scrollToPosition+双 post 兜底。
 * （面板为纵向线性列表，无网格版 focusTargetEpisode 的居中逻辑。）
 */
private fun focusSeasonEpisode(recyclerView: RecyclerView?, targetIndex: Int) {
    val rv = recyclerView ?: return
    val holder = rv.findViewHolderForAdapterPosition(targetIndex)
    if (holder?.itemView != null) {
        holder.itemView.requestFocus()
        return
    }
    if (targetIndex in 0 until (rv.adapter?.itemCount ?: 0)) {
        rv.scrollToPosition(targetIndex)
    }
    rv.post {
        // First post: layout pass after scrollToPosition
        rv.post {
            // Second post: ViewHolder should now be available
            val h = rv.findViewHolderForAdapterPosition(targetIndex)
            h?.itemView?.requestFocus()
        }
    }
}
