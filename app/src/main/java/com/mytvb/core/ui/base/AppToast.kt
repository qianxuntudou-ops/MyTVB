package com.mytvb.core.ui.base

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
import com.mytvb.R
import com.mytvb.core.common.log.AppLog
import java.lang.ref.WeakReference

/**
 * 全局提示浮层。
 *
 * 早先用系统 Toast 并改写 message 字号，但系统 Toast 根布局带 320dp 级宽度上限；
 * [UiScale] 把 density 钉成设计倍率后，该上限在 1080p 投影仪上只剩 320px
 * （density=1.0），36px 的 8 个汉字需要 288px + 内边距就放不下，末尾被挤断行
 * （实测 00:05:32 `rootW=320` / `textW=288`）。窗口宽度不受应用控制，故改为
 * 应用内浮层：挂当前 Activity 的 decorView，宽度上限取屏幕宽，字号仍走
 * 「设计值 × 设计倍率 × 字体档」且刻意不乘界面缩放档（提示是全局浮层而非界面
 * 内容，叠乘后在大屏上观感过大）。
 *
 * 拿不到前台 Activity（应用在后台等）时退回系统 Toast，保证提示不丢。
 */
object AppToast {

    private const val TAG = "AppToast"

    private const val SHORT_MS = 2_000L
    private const val LONG_MS = 3_500L
    private const val FADE_IN_MS = 120L
    private const val FADE_OUT_MS = 180L
    private const val MAX_LINES = 4

    private val DEFAULT_GRAVITY = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var resumedActivity: WeakReference<Activity>? = null

    private var overlay: TextView? = null
    private var overlayHide: Runnable? = null

    /**
     * 高层宿主栈：Activity 之上的独立窗口（Dialog 等，z 序高于 Activity）显示时注册、
     * 消失时注销。提示浮层挂在 Activity decor 上会被这些窗口压在下层，所以 show 时
     * 取栈顶仍 attach 的宿主——Toast 自己决定层级，与具体弹窗解耦。
     */
    private val hostStack = ArrayList<ViewGroup>()

    /** 注册高层宿主（Dialog 的 window.decorView）；传空直接忽略。 */
    fun attachHost(decor: ViewGroup?) {
        if (decor != null && !hostStack.contains(decor)) hostStack.add(decor)
    }

    /** 注销高层宿主（对应窗口 dismiss 时调用）；传空直接忽略。 */
    fun detachHost(decor: ViewGroup?) {
        if (decor == null) return
        hostStack.remove(decor)
        if (overlay?.parent === decor) dismissOverlay()
    }

    /** 记录前台 Activity，供浮层挂载；须在主线程调用一次。 */
    fun install(application: Application) {
        application.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

                override fun onActivityStarted(activity: Activity) = Unit

                override fun onActivityResumed(activity: Activity) {
                    resumedActivity = WeakReference(activity)
                }

                override fun onActivityPaused(activity: Activity) {
                    if (resumedActivity?.get() === activity) {
                        resumedActivity = null
                        dismissOverlay()
                    }
                }

                override fun onActivityStopped(activity: Activity) = Unit

                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

                override fun onActivityDestroyed(activity: Activity) {
                    if (resumedActivity?.get() === activity) {
                        resumedActivity = null
                        dismissOverlay()
                    }
                }
            }
        )
    }

    fun show(context: Context, text: String, duration: Int = Toast.LENGTH_SHORT) {
        show(context, text, duration, DEFAULT_GRAVITY)
    }

    fun show(context: Context, @StringRes resId: Int, duration: Int = Toast.LENGTH_SHORT) {
        show(context, context.getString(resId), duration, DEFAULT_GRAVITY)
    }

    /** 完整版：需要非默认位置时使用（gravity 只识别 TOP / CENTER / 底部三种）。 */
    fun show(
        context: Context,
        text: String,
        duration: Int,
        gravity: Int,
        yOffsetPx: Int = designPx(context, R.dimen.px80)
    ) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            showInternal(context, text, duration, gravity, yOffsetPx)
        } else {
            mainHandler.post { showInternal(context, text, duration, gravity, yOffsetPx) }
        }
    }

    private fun showInternal(
        context: Context,
        text: String,
        duration: Int,
        gravity: Int,
        yOffsetPx: Int
    ) {
        val metrics = context.resources.displayMetrics
        val appliedPx = appliedTextPx(context)

        val activity = resumedActivity?.get()
        val topHost = hostStack.lastOrNull { it.isAttachedToWindow }
        val decor = topHost ?: (activity?.window?.decorView as? ViewGroup)
        val hostReady = decor != null && decor.isAttachedToWindow &&
            (topHost != null || (activity != null && !activity.isFinishing && !activity.isDestroyed))
        val hostContext = decor?.context ?: context

        AppLog.i(
            TAG,
            "show render=${if (hostReady) "overlay" else "systemToast"} " +
                "host=${if (topHost != null) "window" else "activity"} " +
                "text=\"$text\" " +
                "screen=${metrics.widthPixels}x${metrics.heightPixels} " +
                "density=${metrics.density} scaledDensity=${metrics.density * context.resources.configuration.fontScale} " +
                "userScale=${UiScale.scale()} textScale=${UiTextScale.scale()} " +
                "fontScale=${context.resources.configuration.fontScale} " +
                "appliedPx=$appliedPx"
        )

        val target = decor?.takeIf { hostReady }
        if (target != null) {
            showOverlay(hostContext, target, text, appliedPx, duration, gravity, yOffsetPx)
        } else {
            showSystemToast(context, text, appliedPx, duration, gravity, yOffsetPx, metrics)
        }
    }

    private fun showOverlay(
        context: Context,
        decor: ViewGroup,
        text: String,
        textPx: Float,
        duration: Int,
        gravity: Int,
        yOffsetPx: Int
    ) {
        dismissOverlay()
        val padH = designPx(context, R.dimen.px24)
        val padV = designPx(context, R.dimen.px16)
        val screenW = context.resources.displayMetrics.widthPixels
        val view = TextView(context).apply {
            setText(text)
            setTextColor(Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, textPx)
            includeFontPadding = false
            setGravity(Gravity.CENTER)
            maxLines = MAX_LINES
            ellipsize = TextUtils.TruncateAt.END
            setPadding(padH, padV, padH, padV)
            background = GradientDrawable().apply {
                cornerRadius = designPx(context, R.dimen.px16).toFloat()
                setColor(0xFFF5F6F8.toInt())
            }
            alpha = 0f
            isFocusable = false
            isFocusableInTouchMode = false
            isClickable = false
            // 浮层宽度上限取屏幕宽：系统 Toast 的 320dp 上限在 density 钉死后只剩
            // 320px，会把长提示挤成多行；这里彻底绕开。
            maxWidth = screenW - padH * 2 - designPx(context, R.dimen.px48) * 2
        }
        val overlayGravity = overlayGravity(gravity)
        val params = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            overlayGravity
        )
        // TOP(0x30) 与 BOTTOM(0x50) 共享 0x10 位，不能用 and TOP 判别，否则底部
        // 恒被判成顶部（提示全部飞到屏幕顶端）；必须取 VERTICAL_GRAVITY_MASK 再比对
        when (overlayGravity and Gravity.VERTICAL_GRAVITY_MASK) {
            Gravity.TOP -> params.topMargin = yOffsetPx
            Gravity.CENTER_VERTICAL -> Unit
            else -> params.bottomMargin = yOffsetPx
        }
        decor.addView(view, params)
        overlay = view
        view.animate()
            .alpha(1f)
            .setDuration(FADE_IN_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()
        view.post {
            AppLog.i(
                TAG,
                "overlay w=${view.width} h=${view.height} lines=${view.lineCount} " +
                    "textW=${view.paint.measureText(text)} textPx=$textPx " +
                    "decorW=${decor.width} decorH=${decor.height} gravity=$overlayGravity"
            )
        }
        val hide = Runnable {
            overlayHide = null
            if (overlay === view) overlay = null
            view.animate()
                .alpha(0f)
                .setDuration(FADE_OUT_MS)
                .setInterpolator(AccelerateInterpolator())
                .withEndAction { (view.parent as? ViewGroup)?.removeView(view) }
                .start()
        }
        overlayHide = hide
        mainHandler.postDelayed(hide, if (duration == Toast.LENGTH_LONG) LONG_MS else SHORT_MS)
    }

    private fun dismissOverlay() {
        overlayHide?.let { mainHandler.removeCallbacks(it) }
        overlayHide = null
        val view = overlay ?: return
        overlay = null
        view.animate().cancel()
        (view.parent as? ViewGroup)?.removeView(view)
    }

    private fun overlayGravity(gravity: Int): Int = when (gravity and Gravity.VERTICAL_GRAVITY_MASK) {
        Gravity.TOP -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
        Gravity.CENTER_VERTICAL -> Gravity.CENTER
        else -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
    }

    /** 兜底路径：没有前台 Activity（如应用在后台）时仍用系统 Toast。 */
    /**
     * 提示字号：设计值 × 设计倍率 × 字体档（不乘界面缩放档）。
     * 系统会在 WebView 初始化、窗口切换等无重建路径上把 DisplayMetrics 重置回面板
     * density（投影仪实测 1.0→2.0），而 UiScale 钉的是 Activity resources；提示常由
     * applicationContext 发出，getDimension 会直接拿到翻倍值（36dip→72px）。
     * 故先除掉当前 density 还原设计值，再乘设计倍率，绕开被污染的 density。
     */
    private fun appliedTextPx(context: Context): Float {
        val metrics = context.resources.displayMetrics
        val density = metrics.density.takeIf { it > 0f } ?: 1f
        val design = UiScale.designScale(metrics.widthPixels, metrics.heightPixels)
        val rawPx36 = context.resources.getDimension(R.dimen.px36)
        return rawPx36 / density * design * UiTextScale.scale()
    }

    private fun showSystemToast(
        context: Context,
        text: String,
        appliedPx: Float,
        duration: Int,
        gravity: Int,
        yOffsetPx: Int,
        metrics: android.util.DisplayMetrics
    ) {
        val toast = Toast.makeText(context, text, duration)
        // 系统 text toast 的 message 字号被钉死 scaledDensity 劫持，直写 px 池字号。
        // getView 在 API 30+ 已废弃（text toast 恒返回 null），但 API<30 上仍能拿到
        // message TextView 完成字号改写，minSdk 23 前这段必须保留
        @Suppress("DEPRECATION")
        val messageView = toast.view?.findViewById<TextView>(android.R.id.message)
        messageView?.setTextSize(TypedValue.COMPLEX_UNIT_PX, appliedPx)
        @Suppress("DEPRECATION")
        messageView?.post {
            AppLog.i(
                TAG,
                "systemToast layout msgW=${messageView.width} lines=${messageView.lineCount} " +
                    "textW=${messageView.paint.measureText(text)} rootW=${toast.view?.width} " +
                    "metrics=${metrics.widthPixels}x${metrics.heightPixels} density=${metrics.density}"
            )
        }
        // API 30+ 会忽略自定义 toast 的位置，退回系统默认底部，视觉仍成立
        toast.setGravity(gravity, 0, yOffsetPx)
        toast.show()
    }

    /** px 池的 dip 值换算成「设计像素 × 设计倍率」，同样绕开可能被系统重置的 density。 */
    private fun designPx(context: Context, resId: Int): Int {
        val metrics = context.resources.displayMetrics
        val density = metrics.density.takeIf { it > 0f } ?: 1f
        return (
            context.resources.getDimension(resId) / density *
                UiScale.designScale(metrics.widthPixels, metrics.heightPixels)
            ).toInt()
    }
}
