package com.mytvb.core.ui.base

import android.content.Context
import android.content.res.Resources
import com.mytvb.core.common.log.AppLog
import com.mytvb.core.common.settings.AppSettingsDataStore

/**
 * 全局界面缩放（UI DPI 适配）。
 *
 * 布局尺寸统一走 @dimen/pxN 池且已 dip 化（单位 dip、值 = N），由本类在每个
 * Context 挂载时把 density 钉死为「设计倍率 × 用户系数」，让 pxN、代码 dp
 * 换算、Toast/Dialog 全部经同一通道等比缩放：
 *
 * - 设计倍率沿用历史 values-WxH 七档的档位倍率（两维都 ≤ 屏幕取最大档），
 *   常规 16:9 屏与旧版像素级一致；
 * - 屏幕比 16:9 更宽（带鱼屏车机等）时提升到 min(宽/1920, 高/1008)，
 *   修复旧 WxH 匹配「高度卡档」导致的 UI 显小（如 5120x1600 只能命中
 *   2560x1440 档、倍率腰斩）；
 * - 用户系数来自设置「界面缩放」档位；首次安装无设定值时按分辨率自动
 *   推荐一档并落盘（超宽屏 125%，其余 100%），之后以用户设定为准。
 *
 * 与 [UiTextScale]（字号系数，ScaledTextView 环节叠加）正交互不干扰。
 */
object UiScale {

    private const val TAG = "UiScale"

    const val KEY_UI_SCALE = "ui_scale"
    const val DEFAULT_PERCENT = 100

    /** 档位百分比；显示为纯数字（语言无关），无需 SettingLabels 映射。 */
    val PERCENTS = intArrayOf(80, 90, 100, 110, 125, 150)

    /** 历史 values-WxH 档位及其设计倍率（低分屏三档为历史手工调大值）。 */
    private class Tier(val w: Int, val h: Int, val scale: Float)

    private val TIERS = listOf(
        Tier(577, 400, 0.455f),
        Tier(800, 480, 0.533f),
        Tier(1024, 600, 0.667f),
        Tier(1280, 672, 0.667f),
        Tier(1920, 1008, 1.0f),
        Tier(2560, 1440, 1.333f),
        Tier(3840, 2160, 2.0f),
    )

    @Volatile
    private var cachedPercent = DEFAULT_PERCENT

    /**
     * 从设置缓存刷新档位；BaseActivity 每次创建时调用，读内存 cache 无 IO。
     *
     * 没有用户设定值（首次安装/清数据/换屏）时按分辨率自适应推荐档并异步落盘，
     * 之后以用户设定为准；同分辨率重复写入幂等。
     */
    fun refresh(settings: AppSettingsDataStore, screenW: Int, screenH: Int) {
        val stored = settings.getCachedString(KEY_UI_SCALE)?.toIntOrNull()
        cachedPercent = if (stored != null) {
            normalize(stored)
        } else {
            val recommended = recommendedPercent(screenW, screenH)
            settings.putStringAsync(KEY_UI_SCALE, recommended.toString())
            recommended
        }
    }

    fun scale(): Float = cachedPercent / 100f

    fun indexOf(percent: Int): Int = PERCENTS.indexOf(normalize(percent)).coerceAtLeast(0)

    private fun normalize(percent: Int): Int =
        if (PERCENTS.contains(percent)) percent else DEFAULT_PERCENT

    /**
     * 按分辨率推荐初始档位：常规 16:9 屏档位倍率已与历史视觉一致，取 100%；
     * 超宽屏（宽高比 ≥ 2.8，如 5120×1600 车机）高度卡档即使有增强仍整体偏小，
     * 自动提一档到 125%（宁保守，用户可继续手调）。
     */
    fun recommendedPercent(screenW: Int, screenH: Int): Int =
        if (screenH > 0 && screenW >= screenH * 2.8f) 125 else DEFAULT_PERCENT

    /**
     * 设计倍率：档位匹配倍率保底（常规屏与旧版一致），超宽屏按高边基准增强，
     * 避免带鱼屏纵向溢出（按宽 5120/1920=2.667 会把纵向 UI 撑到设计的 1.7 倍）。
     *
     * 无分配实现（保持 filter+maxByOrNull 语义）：[ensureApplied] 会在每次 XML
     * inflate 的 View 创建前调用，不能在此产生临时对象。
     */
    fun designScale(screenW: Int, screenH: Int): Float {
        var tierScale = TIERS.first().scale
        var bestW = -1
        for (tier in TIERS) {
            if (screenW >= tier.w && screenH >= tier.h && tier.w > bestW) {
                bestW = tier.w
                tierScale = tier.scale
            }
        }
        val fit = minOf(screenW / 1920f, screenH / 1008f)
        return maxOf(tierScale, fit)
    }

    /**
     * 把 density 三件套钉到目标值。须在该 Context 首次 inflate 之前调用：
     * BaseActivity/SplashActivity/GaiaVgateActivity 的 attachBaseContext（用缓存值
     * 先钉一次）+ BaseActivity.onCreate 的 refresh 之后（设置变更后的新值）。
     * 幂等，重复调用安全。
     */
    @Suppress("DEPRECATION")
    fun apply(resources: Resources, reason: String = "direct") {
        val metrics = resources.displayMetrics
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels
        val target = designScale(screenW, screenH) * scale()
        val fontScale = resources.configuration.fontScale.takeIf { it > 0f } ?: 1f
        val before = metrics.density
        metrics.density = target
        metrics.densityDpi = (target * 160).toInt()
        metrics.scaledDensity = target * fontScale
        // before->target：before≠target 即"本次真的改了密度"；同一弹窗生命周期内出现多组
        // 不同 target（如 2.0 后又 1.0）就是"两阶段密度"（弹窗先大后小的根因线索）
        // D 级：华为/荣耀默认拦三方 I/W/E 级日志，密度是"页面自己变小"类问题的命门，必须 D 级必出
        AppLog.d(
            TAG,
            "apply[$reason] screen=${screenW}x${screenH} " +
                "density $before->$target design=${designScale(screenW, screenH)} " +
                "percent=$cachedPercent dpi=${metrics.densityDpi} fontScale=$fontScale"
        )
    }

    /**
     * 廉价守卫：density 与当前设计值不一致才重钉，一致时零副作用。
     *
     * 系统在 config 变化（系统栏显隐、多窗口、分辨率切换等）的无重建路径上会用
     * 真实显示指标重置 DisplayMetrics，抹掉这里钉的 density——从播放页返回等窗口
     * 状态切换后，已布局视图不受影响、新 inflate 的页面却会按系统 density 放大
     * 数倍。故在 inflate 入口（UiTextScaleFactory）与配置/窗口回调处补钉。
     */
    fun ensureApplied(resources: Resources) {
        val metrics = resources.displayMetrics
        val target = designScale(metrics.widthPixels, metrics.heightPixels) * scale()
        val fontScale = resources.configuration.fontScale.takeIf { it > 0f } ?: 1f
        @Suppress("DEPRECATION")
        val scaledTarget = target * fontScale
        @Suppress("DEPRECATION")
        val scaledCurrent = metrics.scaledDensity
        if (metrics.density != target || scaledCurrent != scaledTarget) {
            // D 级：W 级在华为/荣耀可能被拦，守卫报警是密度污染的第一现场，必须必出
            AppLog.d(
                TAG,
                "density reset by system: density ${metrics.density}->$target " +
                    "scaledDensity $scaledCurrent->$scaledTarget " +
                    "screen=${metrics.widthPixels}x${metrics.heightPixels} percent=$cachedPercent"
            )
            apply(resources, "guard")
        }
    }

    /**
     * 密度探针（D 级：华为/荣耀对三方 I/W 级默认拦截，D 级必出）：打印 context 与
     * application 两路 Resources 的 metrics 快照。用于定位"页面自己变小"——判读法：
     * ctx 的屏幕尺寸若变成竖屏向（1312x2848 这类），design 会跟着算小，即"污染自洽"
     * （守卫按污染后的 w/h 算 target，静默不报警），新 inflate 的页面按小 density 渲染。
     */
    fun probe(where: String, context: Context) {
        val ctx = context.resources.displayMetrics
        val app = context.applicationContext.resources.displayMetrics
        AppLog.d(
            TAG,
            "probe[$where] ctx=${ctx.widthPixels}x${ctx.heightPixels}@${ctx.density} " +
                "design=${designScale(ctx.widthPixels, ctx.heightPixels)} " +
                "app=${app.widthPixels}x${app.heightPixels}@${app.density} percent=$cachedPercent"
        )
    }
}
