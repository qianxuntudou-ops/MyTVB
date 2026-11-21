package com.mytvb.feature.hongguo.net

import android.content.Context
import com.mytvb.core.common.log.AppLog
import org.json.JSONObject

/**
 * 红果网络画像热更（2026-11-21，对标 HGTV TNC 动态下发的自建轻量版）：
 * version.json 可选 `hg_net` 字段捎带下发播放 host 与设备画像四元组——服务端
 * 收窄/封 host 后改这份 JSON 即可不发版切换，随 App 启动检查更新的三级容错
 * 链送达（与 [HongguoVersionProfiles] 同机制同 prefs，零新增下载链路）。
 *
 * 覆盖范围仅播放通道（HongguoPlay）：query/UA 画像同源改读本对象；登录
 * （HongguoAccount）与环境（HongguoEnv）链路画像独立，不受影响。
 *
 * 校验：host 必须是 https 且 fqnovel.com 子域（防投毒，对齐 HGTV T3.d 的
 * .fqnovel.com 域过滤思路）；画像四元组各有格式约束，任一非法整档丢弃
 * （画像三处同源自洽是放行前提，禁止半新半旧部分采用）。
 */
internal object HongguoNetProfiles {

    private const val TAG = "HgNetProfiles"
    private const val PREFS = "hongguo_remote_config"

    private const val K_HOST = "net_play_host"
    private const val K_DEVICE_TYPE = "net_device_type"
    private const val K_DEVICE_BRAND = "net_device_brand"
    private const val K_OS_VERSION = "net_os_version"
    private const val K_CHANNEL = "net_channel"

    /** 覆写档案值（与内置档案同构，仅内存态）。 */
    data class Net(
        val playHost: String,
        val deviceType: String,
        val deviceBrand: String,
        val osVersion: String,
        val channel: String,
    )

    val current: Net get() = overrideNet ?: BUILTIN

    @Volatile private var overrideNet: Net? = null

    fun playHost(): String = current.playHost
    fun deviceType(): String = current.deviceType
    fun deviceBrand(): String = current.deviceBrand
    fun osVersion(): String = current.osVersion
    fun channel(): String = current.channel

    /**
     * 启动恢复（红果首请求前，挂 HongguoRepository init，与 VersionProfiles.restore
     * 同点）：载入上次远程下发的档案。读失败/无值 = 保持内置。
     */
    fun restore(context: Context) {
        runCatching {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val net = parseNet(
                JSONObject().apply {
                    put("play_host", prefs.getString(K_HOST, "") ?: "")
                    put("device_type", prefs.getString(K_DEVICE_TYPE, "") ?: "")
                    put("device_brand", prefs.getString(K_DEVICE_BRAND, "") ?: "")
                    put("os_version", prefs.getString(K_OS_VERSION, "") ?: "")
                    put("channel", prefs.getString(K_CHANNEL, "") ?: "")
                }
            )
            if (net != null && net != overrideNet) {
                overrideNet = net
                AppLog.i(TAG, "restore host=${net.playHost} type=${net.deviceType}")
            }
        }.onFailure { AppLog.w(TAG, "restore failed: ${it.message}") }
    }

    /**
     * 远程下发入口（version.json `hg_net`）：合法整档覆写内存+落盘，非法忽略。
     * 缺失/null 不动现有值（可选字段，老 version.json 兼容）。
     */
    fun applyFrom(context: Context, json: JSONObject?) {
        if (json == null) return
        val net = parseNet(json) ?: run {
            AppLog.w(TAG, "applyFrom: hg_net 无合法档案，忽略")
            return
        }
        overrideNet = net
        runCatching {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(K_HOST, net.playHost)
                .putString(K_DEVICE_TYPE, net.deviceType)
                .putString(K_DEVICE_BRAND, net.deviceBrand)
                .putString(K_OS_VERSION, net.osVersion)
                .putString(K_CHANNEL, net.channel)
                .apply()
        }.onFailure { AppLog.w(TAG, "persist failed: ${it.message}") }
        AppLog.i(TAG, "applyFrom host=${net.playHost} type=${net.deviceType}")
    }

    /** 整档解析+校验：字段缺失/格式非法返回 null（不部分采用）。纯函数，可单测。 */
    fun parseNet(obj: JSONObject?): Net? {
        if (obj == null) return null
        val net = Net(
            playHost = obj.optString("play_host").trim(),
            deviceType = obj.optString("device_type").trim(),
            deviceBrand = obj.optString("device_brand").trim(),
            osVersion = obj.optString("os_version").trim(),
            channel = obj.optString("channel").trim(),
        )
        return net.takeIf { isValid(it) }
    }

    /** host：https 且 fqnovel.com 子域；画像四元组各有形态约束（三处同源自洽前提）。 */
    private fun isValid(n: Net): Boolean =
        n.playHost.matches(Regex("https://[a-z0-9-]+\\.fqnovel\\.com")) &&
            n.deviceType.matches(Regex("[A-Za-z0-9-]{4,20}")) &&
            n.deviceBrand.matches(Regex("[A-Za-z]{2,20}")) &&
            n.osVersion.matches(Regex("\\d{1,3}")) &&
            n.channel.matches(Regex("[a-z0-9_]{2,40}"))

    private val BUILTIN = Net(
        playHost = "https://api5-normal-sinfonlineb.fqnovel.com",
        deviceType = "25053RT47C",
        deviceBrand = "Redmi",
        osVersion = "16",
        channel = "update_64",
    )

    /** 仅测试用：清空内存覆写，回落内置档案。 */
    internal fun resetForTest() {
        overrideNet = null
    }
}
