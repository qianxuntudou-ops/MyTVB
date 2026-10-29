package com.mytvb.ui.dialog

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatDialog
import com.mytvb.BuildConfig
import com.mytvb.R
import com.mytvb.core.common.log.AppLog
import com.mytvb.core.common.update.ApkUpdater
import com.mytvb.core.ui.base.AppToast
import com.mytvb.core.ui.base.DialogWindowFit
import com.mytvb.core.ui.base.ScaledTextView
import com.mytvb.ui.widget.NonFocusableScrollView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 更新流程弹窗：发现新版本确认弹窗 + 下载进度弹窗。
 *
 * 从 SettingsFragment 提取的共享组件，设置页手动检查与启动后自动检查两处复用。
 * 下载协程挂在 [downloadScope]（object 级、随进程存活）上：进度弹窗可「转后台」
 * 关闭后继续下载，宿主页面销毁也不中断，App 进程退出才随之终止；下载完成
 * 自动拉起系统安装界面。
 */
object UpdateFlowDialog {

    /** 宿主侧状态复位回调：下载被用户取消或失败后，检查条目状态应回到 Idle。 */
    fun interface Listener {
        fun onResetCheckState()
    }

    @Volatile
    var isConfirmShowing = false
        private set

    @Volatile
    var isDownloading = false
        private set

    /** 确认弹窗或下载弹窗任一进行中：启动自动检查据此避让，防止与手动检查双弹。 */
    val isBusy: Boolean
        get() = isConfirmShowing || isDownloading

    /** 下载专用应用级 scope：脱离宿主页面生命周期，转后台/离开设置页都不中断下载。 */
    private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun showConfirmDialog(
        context: Context,
        releaseInfo: ApkUpdater.ReleaseInfo,
        cancelTextRes: Int = R.string.cancel,
        onDismissedWithoutDownload: (() -> Unit)? = null,
        listener: Listener? = null,
    ) {
        if (isBusy) return
        isConfirmShowing = true

        val resources = context.resources
        val px40 = resources.getDimensionPixelSize(R.dimen.px40)
        val px35 = resources.getDimensionPixelSize(R.dimen.px35)
        val px20 = resources.getDimensionPixelSize(R.dimen.px20)
        val px18 = resources.getDimensionPixelSize(R.dimen.px18)
        val px16 = resources.getDimensionPixelSize(R.dimen.px16)
        val px14 = resources.getDimensionPixelSize(R.dimen.px14)
        val px10 = resources.getDimensionPixelSize(R.dimen.px10)
        val textColor = resources.getColor(R.color.textColor, null)

        val dialog = AppCompatDialog(context, R.style.DialogTheme)
        dialog.setCanceledOnTouchOutside(true)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.dialog_background)
            isClickable = true
            isFocusable = false
            isFocusableInTouchMode = false
            setOnClickListener { dialog.dismiss() }
        }

        root.addView(ScaledTextView(context).apply {
            text = context.getString(R.string.update_found_new)
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.px36))
            setTypeface(null, android.graphics.Typeface.BOLD)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(px40, px35, px40, px20)
            layoutParams = lp
        })

        root.addView(View(context).apply {
            setBackgroundColor(0x1FFFFFFF)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, resources.getDimensionPixelSize(R.dimen.px2))
            lp.setMargins(px18, 0, px18, 0)
            layoutParams = lp
        })

        // 更新日志可能很长：正文钳到 55% 屏高转为滚动，避免整个弹窗高度超屏把按钮顶出屏幕。
        // weight=1：部分系统（如华为 Android 10 电视）window WRAP_CONTENT 时会把超限内容的
        // 尾部（按钮行）直接裁掉——weight 让正文吃剩余空间，标题与按钮行永远优先保留
        val notesScroll = NonFocusableScrollView(context).apply {
            maxHeight = (resources.displayMetrics.heightPixels * 0.55f).toInt()
            // 滚动条常驻且加粗（内容不满 maxHeight 不显示，不会误显示），
            // 上下渐隐边缘提示还有未滚到的内容
            persistentScrollBar = true
            // ScrollView 构造默认 focusable，不关掉会抢走弹窗初始焦点，按钮永远落不上
            isFocusable = false
            scrollBarWidth = resources.getDimensionPixelSize(R.dimen.px8)
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(resources.getDimensionPixelSize(R.dimen.px30))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(px40, px20, px40, 0)
            lp.weight = 1f
            layoutParams = lp
        }
        // 滚动条位置诊断：滚动过程记录 thumb 几何（ScrollBarDebug）
        notesScroll.scrollDebugTag = "otaNotes"
        notesScroll.addView(ScaledTextView(context).apply {
            val notes = if (releaseInfo.releaseNotes.isNotBlank()) {
                context.getString(R.string.update_release_notes_format, releaseInfo.releaseNotes)
            } else ""
            text = context.getString(
                R.string.update_confirm_message_format,
                BuildConfig.VERSION_NAME,
                releaseInfo.versionName,
                notes
            )
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.px30))
            setLineSpacing(resources.getDimension(R.dimen.px6), 1f)
        })
        root.addView(notesScroll)

        val actionContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(px18, px20, px18, px18)
            layoutParams = lp
        }

        var cancelButton: ScaledTextView? = null
        // 以任何非下载途径关闭（稍后/点卡片空白/BACK）都视为「本次不更新」：
        // 自动检查场景据此记录忽略版本，下次启动不再弹同一版本
        var launchDownload = false
        dialog.setOnDismissListener {
            isConfirmShowing = false
            if (!launchDownload) onDismissedWithoutDownload?.invoke()
        }
        listOf(context.getString(cancelTextRes) to { dialog.dismiss() }, context.getString(R.string.update_download_action) to {
            launchDownload = true
            dialog.dismiss()
            startDownloadApk(context, releaseInfo, listener)
        }).forEach { (text, action) ->
            val button = ScaledTextView(context).apply {
                this.text = text
                setTextColor(textColor)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.px32))
                setPadding(px16, px14, px16, px14)
                isClickable = true
                isFocusable = true
                setOnClickListener { action() }
                setBackgroundResource(R.drawable.bg_dialog_button)
            }
            if (text == context.getString(cancelTextRes)) cancelButton = button
            actionContainer.addView(button, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(px10, 0, px10, 0)
            })
        }

        root.addView(actionContainer)
        DialogWindowFit.overlay(
            dialog,
            DialogWindowFit.wrapContentInScroll(context, root),
            // 更新日志单行动辄 20+ 字，px800 档正文区仅约 24 字宽必然折行破碎，
            // 用使用技巧弹窗同款 px1100
            resources.getDimensionPixelSize(R.dimen.px1100),
            cancelOnOutside = false,
            tag = "riskControl"
        )
        // 焦点在按钮上时 DPAD 上/下不会路由进 ScrollView，由弹窗层转发驱动正文滚动；
        // 左右/确认/返回不拦，按钮焦点切换与关闭行为不受影响
        dialog.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            val step = resources.getDimensionPixelSize(R.dimen.px120)
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    notesScroll.smoothScrollBy(0, -step)
                    true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    notesScroll.smoothScrollBy(0, step)
                    true
                }
                else -> false
            }
        }
        // Activity 恰在此时销毁等极端场景 show 会抛 BadToken，需复位守卫，别让自动检查从此哑掉
        if (!showSafely(dialog)) {
            isConfirmShowing = false
            return
        }
        // TV 弹窗惯例显式落焦点（其余弹窗均有）：默认在「取消/稍后」安全侧，防误触直接开下载；
        // 遥控器左右在两按钮间切换，上下滚正文。show 后立即调会被 ViewRootImpl 的
        // 初始焦点分配覆盖，必须等首次布局完成
        root.post {
            cancelButton?.requestFocus()
            // 布局稳定后的滚动区几何快照：内层正文区 + 外层 wrap 滚动容器是否可滚
            val notesChild = notesScroll.getChildAt(0)
            val outerScroll = root.parent as? com.mytvb.ui.widget.NonFocusableScrollView
            outerScroll?.scrollDebugTag = "otaOuter"
            com.mytvb.core.common.log.AppLog.i(
                "ScrollBarDebug",
                "[otaNotes] settled notesH=${notesScroll.height} childH=${notesChild?.height} " +
                    "maxHeight=${notesScroll.maxHeight} scrollable=${notesScroll.height < (notesChild?.height ?: 0)} | " +
                    "outerH=${outerScroll?.height} outerChildH=${root.height} outerScrollable=${((outerScroll?.height ?: 0) < root.height)}"
            )
        }
    }

    /** 宿主 Activity 已销毁等极端场景 show 抛 BadToken，吞掉并返回 false 由调用方复位守卫 */
    private fun showSafely(dialog: AppCompatDialog): Boolean {
        return try {
            dialog.show()
            true
        } catch (t: Throwable) {
            AppLog.w("UpdateFlowDialog", "show dialog failed: ${t.message}")
            false
        }
    }

    fun startDownloadApk(
        context: Context,
        releaseInfo: ApkUpdater.ReleaseInfo,
        listener: Listener? = null,
    ) {
        if (isDownloading) {
            AppToast.show(context, context.getString(R.string.update_in_progress_toast), Toast.LENGTH_SHORT)
            return
        }

        // 下载可能转后台跨页面存活：网络/安装/Toast 一律走 applicationContext，
        // 避免持有已销毁的宿主 Activity
        val appContext = context.applicationContext

        // 同一会话内已有同版本完整包（如下载完但安装界面被关掉没装）：
        // 直接复用弹安装界面，不重新下载。放在 isDownloading 守卫之后，
        // 下载进行中先挡重复入口，不会与后台 job 完成时的安装重叠
        ApkUpdater.findDownloadedApk(appContext, releaseInfo.versionName)?.let {
            ApkUpdater.installApk(appContext, it)
            return
        }
        isDownloading = true

        val resources = context.resources
        val px40 = resources.getDimensionPixelSize(R.dimen.px40)
        val px35 = resources.getDimensionPixelSize(R.dimen.px35)
        val px20 = resources.getDimensionPixelSize(R.dimen.px20)
        val px18 = resources.getDimensionPixelSize(R.dimen.px18)
        val px16 = resources.getDimensionPixelSize(R.dimen.px16)
        val px14 = resources.getDimensionPixelSize(R.dimen.px14)
        val px10 = resources.getDimensionPixelSize(R.dimen.px10)
        val textColor = resources.getColor(R.color.textColor, null)

        val dialog = AppCompatDialog(context, R.style.DialogTheme)
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.dialog_background)
        }

        val titleView = ScaledTextView(context).apply {
            text = context.getString(R.string.update_downloading_title)
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.px36))
            setTypeface(null, android.graphics.Typeface.BOLD)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(px40, px35, px40, px20)
            layoutParams = lp
        }
        root.addView(titleView)

        root.addView(View(context).apply {
            setBackgroundColor(0x1FFFFFFF)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, resources.getDimensionPixelSize(R.dimen.px2))
            lp.setMargins(px18, 0, px18, 0)
            layoutParams = lp
        })

        val progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, resources.getDimensionPixelSize(R.dimen.px20))
            lp.setMargins(px40, px20, px40, 0)
            layoutParams = lp
        }
        root.addView(progressBar)

        val progressText = ScaledTextView(context).apply {
            text = context.getString(R.string.connecting)
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.px26))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(px40, px14, px40, 0)
            layoutParams = lp
        }
        root.addView(progressText)

        var downloadJob: Job? = null
        val actionContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(px18, px20, px18, px18)
            layoutParams = lp
        }

        // 转后台：只关进度弹窗，不动下载 job、不复位 isDownloading（继续避让
        // 自动检查/重复下载）；完成/失败/取消由下载 job 收尾统一复位
        val backgroundButton = ScaledTextView(context).apply {
            text = context.getString(R.string.update_download_background)
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.px32))
            setPadding(px16, px14, px16, px14)
            isClickable = true
            isFocusable = true
            setBackgroundResource(R.drawable.bg_dialog_button)
            setOnClickListener {
                dialog.dismiss()
                AppToast.show(appContext, appContext.getString(R.string.update_background_toast), Toast.LENGTH_LONG)
            }
        }

        val cancelButton = ScaledTextView(context).apply {
            text = context.getString(R.string.cancel)
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.px32))
            setPadding(px16, px14, px16, px14)
            isClickable = true
            isFocusable = true
            setBackgroundResource(R.drawable.bg_dialog_button)
            setOnClickListener {
                downloadJob?.cancel()
                dialog.dismiss()
                listener?.onResetCheckState()
            }
        }

        listOf(backgroundButton, cancelButton).forEach { button ->
            actionContainer.addView(button, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(px10, 0, px10, 0)
            })
        }
        root.addView(actionContainer)

        DialogWindowFit.overlay(
            dialog,
            DialogWindowFit.wrapContentInScroll(context, root),
            resources.getDimensionPixelSize(R.dimen.px800),
            tag = "apkDownload"
        )
        if (!showSafely(dialog)) {
            isDownloading = false
            return
        }
        // 默认聚焦「后台下载」安全侧（非破坏性操作），与确认弹窗同款 post 等待首次布局
        root.post { backgroundButton.requestFocus() }

        // isDownloading 的复位统一放 finally：进度弹窗转后台关闭后 dismiss 不再
        // 触发任何复位，完成/失败/取消三条路径都收口到这里
        downloadJob = downloadScope.launch {
            try {
                val apkFile = withContext(Dispatchers.IO) {
                    ApkUpdater.downloadApkToCache(appContext, releaseInfo.apkUrl) { progress ->
                        progressText.post {
                            if (!dialog.isShowing) return@post
                            when (progress) {
                                is ApkUpdater.Progress.Connecting -> {
                                    progressText.text = appContext.getString(R.string.connecting)
                                    progressBar.isIndeterminate = true
                                }
                                is ApkUpdater.Progress.Downloading -> {
                                    progressBar.isIndeterminate = false
                                    progress.percent?.let { progressBar.progress = it }
                                    progressText.text = progress.hint
                                }
                                is ApkUpdater.Progress.Done -> {}
                                is ApkUpdater.Progress.Retrying -> {
                                    progressText.text = appContext.getString(R.string.retrying_format, progress.attempt, progress.maxAttempts)
                                    progressBar.isIndeterminate = true
                                }
                            }
                        }
                    }
                }
                dialog.dismiss()
                ApkUpdater.installApk(appContext, apkFile)
            } catch (e: Exception) {
                if (e is CancellationException) return@launch
                dialog.dismiss()
                AppLog.e("UpdateFlowDialog", "download apk failed", e)
                val hint = when (e) {
                    is java.net.SocketTimeoutException -> appContext.getString(R.string.net_timeout_toast)
                    is java.net.UnknownHostException -> appContext.getString(R.string.net_unavailable_toast)
                    is java.io.IOException -> appContext.getString(R.string.net_error_toast)
                    else -> appContext.getString(R.string.download_failed_toast)
                }
                AppToast.show(appContext, hint, Toast.LENGTH_LONG)
                listener?.onResetCheckState()
            } finally {
                isDownloading = false
            }
        }
    }
}
