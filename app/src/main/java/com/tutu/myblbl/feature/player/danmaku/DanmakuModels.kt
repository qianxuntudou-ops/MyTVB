package com.tutu.myblbl.feature.player.danmaku

import android.graphics.Typeface

/** 轨道密度 prefValue 字面量（原 blbl AppPrefs 常量，内联避免依赖 AppPrefs）。 */
private const val LANE_DENSITY_SPARSE = "sparse"
private const val LANE_DENSITY_DENSE = "dense"
private const val FONT_WEIGHT_NORMAL = "normal"
private const val FONT_WEIGHT_BOLD = "bold"

/**
 * 旧轨道密度枚举（保留向后兼容，不再参与行高计算）。新逻辑统一使用 [DanmakuTrackSpacing]。
 */
enum class DanmakuLaneDensity(
    val prefValue: String,
    val laneHeightFactor: Float,
) {
    Sparse(LANE_DENSITY_SPARSE, 1.25f),
    Standard("standard", 1.0f),
    Dense(LANE_DENSITY_DENSE, 0.85f),
    ;

    companion object {
        fun fromPrefValue(value: String): DanmakuLaneDensity =
            when (value.trim()) {
                LANE_DENSITY_SPARSE -> Sparse
                LANE_DENSITY_DENSE -> Dense
                else -> Standard
            }
    }
}

/**
 * 弹幕行间距档位。[factor] 为行高倍率，统一算法：行间空白 = 度量高度 × (factor - 1)
 * （与已移除的 akdanmaku 引擎 margin 语义对齐）。
 *
 * factor 基准为 fontMetrics 度量高度（descent-ascent，含字体设计留白），故：
 * - factor = 1.0 → 度量层零空白（但字形间仍有 fontMetrics 内部留白，视觉略松）
 * - factor < 1.0 → 吃掉度量留白，字形贴近（紧凑档）
 * - factor > 1.0 → 行间额外留白
 *
 * factor 下限由 allocator 的 coerceIn(-itemHeight*0.35, ...) 兜底，保证字形绝不重叠。
 */
enum class DanmakuTrackSpacing(
    val prefValue: String,
    /** 设置页显示名。 */
    val displayName: String,
    /** 行高倍率。laneHeight = textBoxHeight × factor（akdanmaku 遗留语义：margin = itemHeight × (factor - 1)）。 */
    val factor: Float,
) {
    Compact("compact", "紧凑", 0.80f),
    Standard("standard", "标准", 0.95f),
    Loose("loose", "宽松", 1.10f),
    ExtraLoose("extra_loose", "特宽", 1.30f);

    companion object {
        val DEFAULT: DanmakuTrackSpacing = Standard

        fun fromPrefValue(value: String?): DanmakuTrackSpacing {
            if (value.isNullOrBlank()) return DEFAULT
            return when (value.trim()) {
                Compact.prefValue -> Compact
                Loose.prefValue -> Loose
                ExtraLoose.prefValue -> ExtraLoose
                Standard.prefValue -> Standard
                // 兼容用户可见的显示名回写
                Compact.displayName -> Compact
                Loose.displayName -> Loose
                ExtraLoose.displayName -> ExtraLoose
                Standard.displayName -> Standard
                else -> DEFAULT
            }
        }

        /** 显示名 → prefValue，供设置页选择后存盘用。 */
        fun prefValueFromDisplayName(displayName: String): String =
            values().firstOrNull { it.displayName == displayName }?.prefValue ?: DEFAULT.prefValue
    }
}

enum class DanmakuFontWeight(
    val prefValue: String,
    val typeface: Typeface,
) {
    Normal(FONT_WEIGHT_NORMAL, Typeface.DEFAULT),
    Bold(FONT_WEIGHT_BOLD, Typeface.DEFAULT_BOLD),
    ;

    companion object {
        fun fromPrefValue(value: String): DanmakuFontWeight =
            when (value.trim()) {
                FONT_WEIGHT_NORMAL -> Normal
                else -> Bold
            }
    }
}

data class DanmakuConfig(
    val enabled: Boolean,
    val opacity: Float,
    val textSizeSp: Float,
    val fontWeight: DanmakuFontWeight,
    val strokeWidthPx: Int,
    val speedLevel: Int,
    val area: Float,
    val laneDensity: DanmakuLaneDensity,
    val trackSpacing: DanmakuTrackSpacing = DanmakuTrackSpacing.DEFAULT,
    /** 同屏弹幕数上限（并发渲染性能约束；满员即弃、不排队）。0 = 自适应：下限 100、按渲染帧率自动增减（初始 TV 100 / 其他 160）。 */
    val maxOnScreen: Int = 0,
    /** 高赞弹幕头部显示点赞图标（attr bit2）。 */
    val showHighLikeIcon: Boolean = true,
)

data class DanmakuSessionSettings(
    val enabled: Boolean,
    val opacity: Float,
    val textSizeSp: Float,
    val fontWeight: DanmakuFontWeight,
    val strokeWidthPx: Int,
    val speedLevel: Int,
    val area: Float,
    val laneDensity: DanmakuLaneDensity,
    val followBiliShield: Boolean = true,
    val aiShieldEnabled: Boolean = false,
    val aiShieldLevel: Int = 3,
    val allowScroll: Boolean = true,
    val allowTop: Boolean = true,
    val allowBottom: Boolean = true,
    val allowColor: Boolean = true,
    val allowSpecial: Boolean = true,
    val trackSpacing: DanmakuTrackSpacing = DanmakuTrackSpacing.DEFAULT,
) {
    fun toConfig(): DanmakuConfig =
        DanmakuConfig(
            enabled = enabled,
            opacity = opacity,
            textSizeSp = textSizeSp,
            fontWeight = fontWeight,
            strokeWidthPx = strokeWidthPx,
            speedLevel = speedLevel,
            area = area,
            laneDensity = laneDensity,
            trackSpacing = trackSpacing,
        )
}
