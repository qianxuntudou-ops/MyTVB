package com.mytvb.core.common.ext

import android.content.Context
import com.mytvb.R

/**
 * 设置项「存储值 → 本地化显示文案」映射。
 *
 * 历史版本的设置值以中文字面量直接落盘（"开"/"关"/"低"/"黑色"…），
 * 全项目逻辑判断（== 比较、归一化）都依赖这些字面量，属稳定的存储格式，
 * 永不随界面语言改变；多语言只发生在显示层，统一经 [localizedSettingLabel]
 * 翻译。未命中的值（数字、码率、"1/8" 分数、编码名等语言无关串）原样返回。
 */
fun Context.localizedSettingLabel(stored: String): String {
    val res = settingLabelRes(stored)
    return if (res != 0) getString(res) else stored
}

private fun settingLabelRes(stored: String): Int = when (stored) {
    "开" -> R.string.on
    "关" -> R.string.off
    "低" -> R.string.setting_value_low
    "中" -> R.string.setting_value_medium
    "高" -> R.string.setting_value_high
    "自动" -> R.string.setting_value_auto
    // 红果默认画质（数字档位 1080P 等语言无关串原样返回）
    "默认" -> R.string.setting_value_default
    // 红果推荐受众 / 搜索源（"App" 语言无关串原样返回）
    "不限" -> R.string.setting_value_no_limit
    "男频" -> R.string.setting_value_male
    "女频" -> R.string.setting_value_female
    "官网" -> R.string.setting_value_official_site
    "不限制" -> R.string.setting_value_unlimited
    "全屏" -> R.string.setting_value_full_screen
    // 主题（存储值兼作 toLegacyTheme 的映射键）
    "黑色" -> R.string.setting_value_black
    "白色" -> R.string.setting_value_white
    "经典主题" -> R.string.setting_value_classic
    "粉色" -> R.string.setting_value_pink
    "蓝色" -> R.string.setting_value_blue
    "紫色" -> R.string.setting_value_purple
    "红色" -> R.string.setting_value_red
    // 默认启动页面
    "推荐" -> R.string.recommend
    "热门" -> R.string.hot
    "番剧" -> R.string.animation
    "影视" -> R.string.film_and_television
    "动态" -> R.string.dynamic
    // 数据来源（首页推荐/热门）
    "APP端" -> R.string.setting_value_source_app
    "网页端" -> R.string.setting_value_source_web
    // 图片质量
    "低尺寸" -> R.string.setting_value_image_low
    "中尺寸" -> R.string.setting_value_image_medium
    "高尺寸" -> R.string.setting_value_image_high
    // 弹幕间距 / 卡片大小 / 文字大小档位
    "紧凑" -> R.string.setting_value_compact
    "标准" -> R.string.setting_value_standard
    "宽松" -> R.string.setting_value_loose
    "特宽" -> R.string.setting_value_extra_loose
    "小" -> R.string.setting_value_small
    "大" -> R.string.setting_value_large
    "特大" -> R.string.setting_value_x_large
    "超大" -> R.string.setting_value_xx_large
    // 播放完成后动作
    "什么都不做" -> R.string.setting_after_play_none
    "播推荐视频" -> R.string.setting_after_play_recommend
    "播列表中的下一个" -> R.string.setting_after_play_next_in_list
    "播放合集中的下一个" -> R.string.setting_after_play_next_in_collection
    // 字幕默认模式
    "关闭字幕" -> R.string.setting_subtitle_off
    "开启字幕" -> R.string.setting_subtitle_on
    "自动字幕" -> R.string.setting_subtitle_auto
    // 音轨/画质中的中文名（192kbps、8K 等语言无关串原样返回）
    "杜比全景声" -> R.string.setting_audio_dolby_atmos
    "Hi-Res无损" -> R.string.setting_audio_hi_res
    "杜比视界" -> R.string.setting_quality_dolby_vision
    "智能修复" -> R.string.setting_quality_ai_restore
    // 画质档位存储值即 B 站官方名，显示名走模型层 nameRes 与播放器菜单保持同一套叫法
    "8K 超高清" -> R.string.quality_8k
    "HDR 真彩" -> R.string.quality_hdr
    "4K 超高清" -> R.string.quality_4k
    "1080P 60帧" -> R.string.quality_1080p_60
    "1080P 高码率" -> R.string.quality_1080p_plus
    "1080P 高清" -> R.string.quality_1080p
    "720P 60帧" -> R.string.quality_720p_60
    "720P 准高清" -> R.string.quality_720p
    "480P 标清" -> R.string.quality_480p
    "360P 流畅" -> R.string.quality_360p
    "240P 极速" -> R.string.quality_240p
    // 画质完整显示名走 VideoQuality/AudioQuality.displayName(context)（模型层 nameRes）
    else -> 0
}
