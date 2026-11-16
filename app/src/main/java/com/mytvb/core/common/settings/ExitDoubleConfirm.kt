package com.mytvb.core.common.settings

/**
 * 「退出二次确认」开关（设置页通用·交互组，issue#118）。
 * 默认开：播放页与主页按 BACK 退出前需在 exitInterval 内按第二次才真正退出
 * （六个消费点：PlayerActivity/HongguoPlayerActivity/MarmotLiveActivity/
 * CctvVodPlayerActivity/LivePlayerActivity/MainActivity，均为既有的双击退出逻辑）。
 * 关闭后单次 BACK 直接退出。BACK 链路的中间层（收面板/控制层、回主页）不受影响。
 * 存储值为中文"开/关"，与设置页其他开关同口径。
 */
object ExitDoubleConfirm {
    const val KEY = "exit_double_confirm"

    fun enabled(appSettings: AppSettingsDataStore): Boolean {
        return appSettings.getCachedString(KEY, "开") == "开"
    }

    /** 无 appSettings 注入的调用点（如 HongguoPlayerActivity）直接走 Koin。 */
    fun enabled(): Boolean = enabled(org.koin.mp.KoinPlatform.getKoin().get())
}
