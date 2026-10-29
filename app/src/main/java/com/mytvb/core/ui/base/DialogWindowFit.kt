package com.mytvb.core.ui.base

import android.app.Dialog
import android.content.Context
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.mytvb.core.common.log.AppLog
import com.mytvb.ui.widget.NonFocusableScrollView

/**
 * 浮层弹窗尺寸钳制：布局按设计 px 池尺寸写死时（如 dialog_owner_detail 的
 * px1400×px935），在低分屏/大界面缩放系数下换算出的像素可能超过屏幕物理尺寸，
 * 浮层 window 会被系统钳到屏幕大小而内容仍按原尺寸绘制，边缘内容被裁。
 * 统一在「设计尺寸」与「屏幕 92%」之间取小值。
 *
 * 高度传 0 表示按内容自适应（WRAP_CONTENT）。
 */
object DialogWindowFit {

    private const val TAG = "DialogWindowFit"
    private const val MAX_SCREEN_RATIO = 0.92f

    /**
     * 弹窗链密度守卫：弹窗显示期间 Activity 失焦，[android.app.Activity]
     * 的 onWindowFocusChanged 补钉不跑；而 fill 弹窗本身引发的系统栏显隐/
     * 窗口 frame 变化会让系统用真实显示指标重置 DisplayMetrics——重置窗口
     * 内取的 pxN（卡片宽高、字号、间距）全部按系统原始 density 放大数倍
     * （2848x1312 手机实测下载弹窗被钳到 92% 屏宽，正常应为 37%）。
     * 在 apply/overlay 入口把 context 的 density 补钉回设计值（幂等、廉价），
     * 保证窗口尺寸与卡片钳制正确；调用方须在构造 content 取 px 值之前自行
     * 调 [UiScale.ensureApplied]（如 UpdateFlowDialog 两个入口），内容字号
     * 才能同步回正。电视上 density 恒正确，此调用零副作用。
     */
    private fun ensureDesignDensity(context: Context) {
        UiScale.ensureApplied(context.resources)
    }

    /**
     * 弹窗窗口跟随 Activity 隐藏系统栏。弹窗是独立焦点窗口，不继承 Activity 的
     * 沉浸状态：手机上 fill 弹窗一弹出，状态栏/导航栏就被系统按新焦点窗口的
     * 默认 insets 重新拉出来（表现为"弹窗一开手机 UI 就下来，关了才消失"）；
     * 电视无系统栏，此调用无副作用。BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
     * 让边缘滑出的系统栏半透明临时显示、几秒后自动收回，与 BaseActivity 同款。
     */
    private fun followHostImmersive(w: Window) {
        if (!followHostFullscreen) return
        val decor = w.decorView ?: return
        WindowCompat.getInsetsController(w, decor).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        // 挖孔/刘海屏：遮罩铺进 cutout 区，否则隐藏状态栏后屏幕顶部仍留一条未暗化区域
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            w.attributes = w.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    /** 自绘遮罩色：60% 黑，等效系统默认 dimAmount(0.6)。 */
    private const val DIM_BACKGROUND = 0x99000000.toInt()

    /**
     * 宿主全屏开关镜像（默认开=沉浸），由 BaseActivity.applyFullscreenMode
     * 随设置项同步。用户显式关掉全屏时弹窗跟随显示系统栏，不强行 hide。
     */
    @Volatile
    var followHostFullscreen: Boolean = true

    /**
     * 诊断：弹窗显示后按"首帧 / +0.8s / +2.5s"三次记录当时的 density、实测宽高与窗口
     * 请求参数（lp）。用于定位电视上"弹窗弹出后延迟变矮"：对比三次的 size——
     * size 变了而 lp 不变 = 系统/框架从外部钳窗口；size 与 lp 同时变 = 应用侧重设布局。
     *
     * [card] 非空时（overlay 载体）另记卡片实测尺寸：fill 窗口下 decor 尺寸恒为
     * 全屏，卡片是否被外部压缩才是有效观测量。
     */
    fun logDialogMetrics(window: Window?, context: Context, tag: String, card: View? = null) {
        val w = window ?: return
        val dm = context.resources.displayMetrics
        val decor = w.decorView ?: return
        val probe = {
            // isActive 要求窗口持输入焦点，弹窗显示但焦点未落其上时探针会整体静默
            // （模拟器实测三次全漏打），换 attach 判定保证日志必然落盘。
            if (decor.isAttachedToWindow) {
                val cardInfo = card?.let { " card=${it.width}x${it.height}" } ?: ""
                AppLog.i(
                    TAG,
                    "dialog[$tag] density=${dm.density} screen=${dm.widthPixels}x${dm.heightPixels} " +
                        "size=${decor.width}x${decor.height} lp=${w.attributes.width}x${w.attributes.height}$cardInfo"
                )
            }
        }
        decor.post(probe)
        decor.postDelayed(probe, 800)
        decor.postDelayed(probe, 2500)
    }

    /**
     * 把弹窗内容根包进可滚动容器。
     *
     * 部分系统（如华为 Android 10 电视）在窗口 WRAP_CONTENT 高度超限时会把
     * 内容尾部（按钮行/末项）直接裁掉——包一层滚动容器后，被钳高时内容转为
     * 可滚动、尾部控件不再丢失；内容不满可用高度时尺寸与包之前完全一致
     * （滚动容器 wrap 高 = 内容高）。适用于「LinearLayout 内容根 + 无滚动区」
     * 的手建弹窗；本身已有滚动区 + 尾部按钮的结构改用 weight=1 兜底即可。
     */
    fun wrapContentInScroll(context: Context, content: View): View =
        NonFocusableScrollView(context).apply {
            addView(
                content,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

    fun apply(
        window: Window?,
        context: Context,
        designWidthPx: Int,
        designHeightPx: Int = 0
    ) {
        val w = window ?: return
        ensureDesignDensity(context)
        val dm = context.resources.displayMetrics
        val width = minOf(designWidthPx, (dm.widthPixels * MAX_SCREEN_RATIO).toInt())
        val height = if (designHeightPx <= 0) {
            WindowManager.LayoutParams.WRAP_CONTENT
        } else {
            minOf(designHeightPx, (dm.heightPixels * MAX_SCREEN_RATIO).toInt())
        }
        w.setLayout(width, height)
        followHostImmersive(w)
        // 兜底：从播放页返回等系统 insets/config 未稳的场景弹窗，首帧绘制会被吞——
        // 表现为只有变暗遮罩、整个弹窗内容不渲染，任意按键触发重绘后立即恢复。
        // （4K 模拟器 + v2.1.2 实测必现；window/view 布局均正常，仅 Surface 首帧未提交。）
        // 布局后强制补走一帧绘制；本方法均在 show() 之后调用，decorView 此时可用。
        val decor = w.decorView ?: return
        decor.post { decor.invalidate() }
        logDialogMetrics(w, context, "fit")
    }

    /**
     * blbl 式弹窗载体（PopupHost 等效结构，2026-10 对标 blbl 引入）：
     * 窗口 fill×fill 全屏透明，内容卡片居中挂在全屏容器 view 树内。
     *
     * 华为等定制系统对「独立小尺寸 Dialog 窗口」存在窗口级裁高（延迟限高
     * 约 50% 屏高、且发生在首次布局后不触发 relayout——应用侧 weight 兜底
     * 救不回已绘制内容）与吞首帧等定制行为；fill 窗口与 Activity 同尺寸，
     * 这些行为对它不适用（项目内 CommentPanelDialog/图片查看等 fill 弹窗
     * 在华为电视上从未被裁）。内容卡片是 view 树一部分，wrap_content 的
     * 实际尺寸不再经过窗口尺寸协商，永不被窗口边裁切。
     *
     * 必须在 dialog.show() 之前调用（内部 setContentView）。
     *
     * @param widthPx 卡片设计宽（自动钳屏宽 92%）。
     * @param heightPx 卡片高度：0=按内容 wrap（超出 [maxHeightPx] 时被
     *   EXACTLY 压进钳值，内容根需自带滚动兜底——weight=1 滚动区或
     *   [wrapContentInScroll] 包裹——与 [apply] 时代的兜底结构完全复用）；
     *   >0=固定高卡片（布局根为 match_parent 的固定尺寸弹窗用此模式，
     *   自动钳屏高 92%）。
     * @param fillHeight true=卡片高度撑满 fill 窗口（上下贴屏幕边），
     *   忽略 [heightPx]/[maxHeightPx] 的钳制。窗口本身已 MATCH_PARENT，
     *   卡片高度只是容器内布局协商，不经过窗口尺寸协商。
     * @param cancelOnOutside 点击卡片外的暗区是否关闭。fill 窗口没有「窗口外」
     *   点击，setCanceledOnTouchOutside 语义失效，由此参数以容器点击实现。
     */
    fun overlay(
        dialog: Dialog,
        content: View,
        widthPx: Int,
        heightPx: Int = 0,
        cancelOnOutside: Boolean = true,
        maxHeightPx: Int = 0,
        gravity: Int = Gravity.CENTER,
        fillHeight: Boolean = false,
        tag: String = "overlay"
    ): View {
        val context = dialog.context
        ensureDesignDensity(context)
        val dm = context.resources.displayMetrics
        val width = minOf(widthPx, (dm.widthPixels * MAX_SCREEN_RATIO).toInt())
        val maxHeight = if (maxHeightPx > 0) {
            minOf(maxHeightPx, (dm.heightPixels * MAX_SCREEN_RATIO).toInt())
        } else {
            (dm.heightPixels * MAX_SCREEN_RATIO).toInt()
        }
        val height = when {
            fillHeight -> ViewGroup.LayoutParams.MATCH_PARENT
            heightPx > 0 -> minOf(heightPx, maxHeight)
            else -> ViewGroup.LayoutParams.WRAP_CONTENT
        }
        val container = ClampHeightFrameLayout(context)
        // 撑满模式下跳过钳制：MATCH_PARENT 子项实测高恒等于容器高（屏高），
        // 必大于 92% 钳值，不跳过会被压回 92% 高
        container.maxHeightPx = if (fillHeight) Int.MAX_VALUE else maxHeight
        // 遮罩由容器自绘（blbl PopupHost 同款）而非系统 FLAG_DIM_BEHIND：
        // fill 窗口下系统 dim 的实际效果在不同系统上不一致（Android 15 实测
        // 不可感知），自绘保证任何载体下视觉恒定；clear 防止与系统 dim 叠加过暗。
        container.setBackgroundColor(DIM_BACKGROUND)
        dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        container.addView(
            content,
            FrameLayout.LayoutParams(width, height, gravity)
        )
        dialog.setContentView(container)
        val w = dialog.window
        // fill 窗口默认仍被限制在系统栏以内（实测窗口 frame=[0,36][1280,720]，高度少一个
        // 状态栏），自绘遮罩容器只能铺满窗口，屏幕顶部会留一条未暗化的缝；
        // LAYOUT_IN_SCREEN 让窗口覆盖整个屏幕，遮罩才能真正铺满四边。
        w?.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
        w?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        w?.let { followHostImmersive(it) }
        if (cancelOnOutside) {
            // 只响应落在容器自身（卡片外暗区）的点击；卡片内的点击由内容消费。
            container.setOnClickListener { dialog.dismiss() }
        }
        // 吞首帧兜底沿用 apply 的补帧机制；setContentView 已 installDecor，
        // decorView 此刻可用，post 进 run queue 于首次遍历前执行。
        val decor = w?.decorView
        decor?.post { decor.invalidate() }
        logDialogMetrics(w, context, tag, content)
        return container
    }

    /**
     * 全屏容器：内容 wrap 高超过 [maxHeightPx] 时改用 EXACTLY 钳值重测——
     * 内容根的滚动区（weight=1 / ScrollView）在被钳高度下自然接管剩余空间；
     * 未超限时卡片保持内容实际高度居中，与独立窗口时代视觉一致。
     */
    private class ClampHeightFrameLayout(context: Context) : FrameLayout(context) {

        var maxHeightPx: Int = Int.MAX_VALUE

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            val child = getChildAt(0) ?: return
            if (child.measuredHeight > maxHeightPx) {
                child.measure(
                    getChildMeasureSpec(
                        widthMeasureSpec, paddingLeft + paddingRight, child.layoutParams.width
                    ),
                    MeasureSpec.makeMeasureSpec(
                        maxHeightPx - paddingTop - paddingBottom, MeasureSpec.EXACTLY
                    )
                )
                setMeasuredDimension(
                    measuredWidth,
                    maxOf(suggestedMinimumHeight, maxHeightPx)
                )
            }
        }
    }
}
