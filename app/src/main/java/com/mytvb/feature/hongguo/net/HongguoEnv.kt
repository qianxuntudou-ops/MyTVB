package com.mytvb.feature.hongguo.net

import android.os.Build

/**
 * 红果请求的设备环境参数（2026-11-10 HGTV 实测定案）：
 * **如实上报当前设备环境**，不伪装机型。
 *
 * 实证：HGTV 在 MuMu 上播放接口实时通过（frida 抓 E0 全量请求），
 * query/UA/Argus proto 三处环境完全一致且全部如实：
 * `device_type=2211133C / os_version=15 / os_api=35 / resolution=1280*720 /
 * dpi=240 / rom_version=V417IR release-keys / is_android_pad_screen=1`。
 * 此前伪装 Redmi 25053RT47C 的构造在模拟器出口全被空响应拒绝——
 * 环境字段互相矛盾（TLS/TCP 行为是 x86 模拟器、字段却报红米手机）即触发风控。
 * host_abi 固定 arm64-v8a：红果客户端无 x86_64 官方包，如实报 x86_64 反而是异常值。
 */
internal object HongguoEnv {

    val deviceType: String get() = Build.MODEL ?: "2211133C"
    val deviceBrand: String get() = (Build.BRAND ?: "Xiaomi").replaceFirstChar { it.uppercase() }
    val osApi: String get() = Build.VERSION.SDK_INT.toString()
    val osVersion: String get() = Build.VERSION.RELEASE ?: "15"

    /** `1280*720` 形态（星号，与 query 一致）。 */
    val resolution: String by lazy {
        runCatching {
            val dm = android.content.res.Resources.getSystem().displayMetrics
            "${dm.widthPixels}*${dm.heightPixels}"
        }.getOrDefault("1280*720")
    }

    val dpi: String by lazy {
        runCatching {
            android.content.res.Resources.getSystem().displayMetrics.densityDpi.toString()
        }.getOrDefault("240")
    }

    /** `V417IR release-keys`（Build.ID + release-keys，实测 UA `Build/V417IR` 同源）。 */
    val romVersion: String by lazy { "${Build.ID ?: "V417IR"} release-keys" }

    /** fingerprint 增量段（注册 payload 的 `rom` 字段，MuMu=1171）。 */
    val romIncremental: String get() = Build.VERSION.INCREMENTAL?.takeIf { it.isNotBlank() } ?: "1171"

    const val HOST_ABI = "arm64-v8a"
    const val CHANNEL = "69310258a"

    /** Argus field23 三元组（device_type/os_version/channel），与 query 环境同源。 */
    val argusProfile: Triple<String, String, String>
        get() = Triple(deviceType, osVersion, CHANNEL)

    /**
     * 请求 UA（HGTV F0.r3.c 同款模板）：POST 带 Cronet 尾串，版本号取 query 的
     * version_code（feed=73532 / 播放实测样本=71332；版本位不敏感，两形态均过）。
     */
    fun ua(versionCode: String): String =
        "com.phoenix.read/$versionCode (Linux; U; Android $osVersion; zh_CN; $deviceType; " +
            "Build/${Build.ID ?: "V417IR"}; " +
            "Cronet/TTNetVersion:8d40f833 2026-03-03 QuicVersion:21ac1950 2025-11-18)"

    /** 播放 UA（HGTV 实测形态）：版本 71332。 */
    fun playbackUa(): String = ua("71332")
}
