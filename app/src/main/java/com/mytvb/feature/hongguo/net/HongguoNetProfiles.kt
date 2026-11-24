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
 * 2026-11-23 新增 `play_routes`：取源路由顺序（v5tab / preload），服务端在形态间来回切换
 * （11-20 关 POST → 11-22 恢复 → 11-23 再关），改配置即可换首选通道。缺失或无合法项
 * =保持默认 v5tab；路由名不在白名单的剔除，且不参与整档校验。
 *
 * 2026-11-22 新增 `hg_dns`：TNC httpDns 直连表（host→IP 列表）。长窗口定案
 * 清洗挂公网 DNS 入口段（222.210.37.x/125.64.109.x），字节官方 TNC 下发的
 * 独占段（101.126.1.x/122.14.229.x）豁免——[HongguoHttp] 的 Dns 覆写读本表。
 * 内置档抄自 HGTV 运行时 TNC 表（tnc-v1.json 实抓），IP 轮换后改 version.json
 * 热更即可。校验：host 须 .fqnovel.com 子域（防投毒），IP 须 IPv4 字面量。
 */
internal object HongguoNetProfiles {

    private const val TAG = "HgNetProfiles"
    private const val PREFS = "hongguo_remote_config"

    private const val K_HOST = "net_play_host"
    private const val K_DEVICE_TYPE = "net_device_type"
    private const val K_DEVICE_BRAND = "net_device_brand"
    private const val K_OS_VERSION = "net_os_version"
    private const val K_CHANNEL = "net_channel"
    private const val K_DNS = "net_dns_table"
    private const val K_ROUTES = "net_play_routes"

    /** 取源路由名（`hg_net.play_routes` 的合法取值）：
     *  player=player/v1 POST + dr_scene=player（官方 App 起播正式通道，2026-11-24 起首选）；
     *  preload=preload/v1 POST（官方预取通道，也当起播次选；2026-11-24 服务端收紧 medusa 校验后真网放行）；
     *  v5tab=V5 签名 GET video_tab（果映TV 形态，严格期放行与否未确认，留作末选）。 */
    const val ROUTE_PLAYER = "player"
    const val ROUTE_V5TAB = "v5tab"
    const val ROUTE_PRELOAD = "preload"
    private val KNOWN_ROUTES = setOf(ROUTE_PLAYER, ROUTE_V5TAB, ROUTE_PRELOAD)

    /** 覆写档案值（与内置档案同构，仅内存态）。 */
    data class Net(
        val playHost: String,
        val deviceType: String,
        val deviceBrand: String,
        val osVersion: String,
        val channel: String,
        /** 取源路由顺序：首个返回片源的胜出，其后依次回退（空 body 视为被拒/限流，熔断后不回退）。 */
        /**
         * 取源路由：**只取首位，不做自动回退**（2026-11-24 定案——多形态串联只会让服务端
         * 在几十秒内看到同一设备两种请求形态，比单路失败更像异常；11-23 两路一起回空也实证
         * 回退无用）。要切换通道就改首位；列表里其余值仅作记录，不参与自动串联。
         * 默认 `preload`：官方预取通道，2026-11-24 00:02 真网放行已验证。
         */
        val playRoutes: List<String> = listOf(ROUTE_PRELOAD),
    )

    val current: Net get() = overrideNet ?: BUILTIN

    @Volatile private var overrideNet: Net? = null

    fun playHost(): String = current.playHost
    fun deviceType(): String = current.deviceType
    fun deviceBrand(): String = current.deviceBrand
    fun osVersion(): String = current.osVersion
    fun channel(): String = current.channel
    fun playRoutes(): List<String> = current.playRoutes

    /** 当前生效的唯一取源路由（不做回退）。 */
    fun activeRoute(): String = playRoutes().firstOrNull() ?: ROUTE_PRELOAD
    fun dnsTable(): Map<String, List<String>> = dnsTable

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
                    // 路由有序，落盘用逗号串（StringSet 不保序）
                    prefs.getString(K_ROUTES, null)?.let { saved ->
                        put("play_routes", org.json.JSONArray(saved.split(',').filter { it.isNotBlank() }))
                    }
                }
            )
            if (net != null && net != overrideNet) {
                overrideNet = net
                AppLog.i(TAG, "restore host=${net.playHost} type=${net.deviceType} routes=${net.playRoutes}")
            }
            prefs.getString(K_DNS, null)?.let { saved ->
                parseDnsTable(JSONObject(saved))?.let {
                    dnsTable = it
                    AppLog.i(TAG, "restore dns ${it.size} hosts")
                }
            }
        }.onFailure { AppLog.w(TAG, "restore failed: ${it.message}") }
    }

    /**
     * 远程下发入口（version.json `hg_net`/`hg_dns`）：合法整档覆写内存+落盘，非法忽略。
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
                    // 路由有序，落盘用逗号串（StringSet 不保序）
                    .putString(K_ROUTES, net.playRoutes.joinToString(","))
                    .apply()
        }.onFailure { AppLog.w(TAG, "persist failed: ${it.message}") }
        AppLog.i(TAG, "applyFrom host=${net.playHost} type=${net.deviceType} routes=${net.playRoutes}")

        // hg_dns 可选：null/缺失不动现有表；空对象 {} 显式清空回落内置
        if (json.has("hg_dns")) {
            val dnsJson = json.optJSONObject("hg_dns")
            if (dnsJson == null) {
                AppLog.w(TAG, "applyFrom: hg_dns 非 JSON 对象，忽略")
            } else {
                parseDnsTable(dnsJson)?.let { table ->
                    dnsTable = table
                    runCatching {
                        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                            .edit().putString(K_DNS, table.toJsonString()).apply()
                    }
                    AppLog.i(TAG, "applyFrom dns ${table.size} hosts")
                }
            }
        }
    }

    /** 整档解析+校验：显式写了但格式非法返回 null（不部分采用）；字段缺失回落内置档。纯函数，可单测。
     *  `play_routes` 可选：缺失或无合法项=保持默认（v5tab），未知路由名与重复项剔除；
     *  它不参与整档校验，配置写错不至于连 host/画像一起回落。 */
    fun parseNet(obj: JSONObject?): Net? {
        if (obj == null) return null
        // 缺省 = 不覆写，回落内置档（画像即运行设备真实值）。远程档**不该钉死机型**：
        // 2026-11-24 之前 version.json 写着 Redmi 25053RT47C，等于让所有设备冒充同一台红米，
        // 与各自注册时上报的真实机型自相矛盾。显式写了但格式非法的值仍然整档丢弃。
        val net = Net(
            playHost = obj.optString("play_host").trim().ifEmpty { BUILTIN.playHost },
            // 画像**不接受远程覆写**：必须与本机注册时上报的真实设备一致。
            // 2026-11-24 之前 hg_net 里的 Redmi 25053RT47C 让所有设备冒充同一台红米，
            // 与各自注册/详情请求自相矛盾（Release 附件里的旧档还会把新 APK 一起带歪）。
            // 远程只能调 host / channel / play_routes；要换设备画像只能改注册链路（HongguoEnv）。
            deviceType = BUILTIN.deviceType,
            deviceBrand = BUILTIN.deviceBrand,
            osVersion = BUILTIN.osVersion,
            channel = obj.optString("channel").trim().ifEmpty { BUILTIN.channel },
            playRoutes = obj.optJSONArray("play_routes")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optString(it).trim() }
                    .filter { it in KNOWN_ROUTES }.distinct()
            }?.takeIf { it.isNotEmpty() } ?: BUILTIN.playRoutes,
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

    /**
     * dns 表解析+校验：host 须 .fqnovel.com 子域（防投毒——非红果域名不许注入 IP），
     * 值须非空 IPv4 字面量列表；任一条目非法整表丢弃（不部分采用）。纯函数，可单测。
     */
    fun parseDnsTable(obj: JSONObject): Map<String, List<String>>? {
        val table = linkedMapOf<String, List<String>>()
        val hosts = obj.keys().asSequence().toList()
        for (host in hosts) {
            if (!host.matches(Regex("[a-z0-9.-]+\\.fqnovel\\.com"))) return null
            val ips = obj.optJSONArray(host)?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optString(it) }
            } ?: return null
            if (ips.isEmpty() || ips.any { !it.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}")) }) return null
            table[host] = ips
        }
        // 空表合法（显式清空语义），但至少不能带任何非法条目
        return table
    }

    private fun Map<String, List<String>>.toJsonString(): String {
        val obj = JSONObject()
        forEach { (h, ips) -> obj.put(h, org.json.JSONArray(ips)) }
        return obj.toString()
    }

    /**
     * 内置档案：**画像按运行设备如实生成**（[HongguoEnv]，与注册/详情等接口同源），
     * 不再固定伪装 Redmi 25053RT47C——那份模板会让同一 device_id 在注册（真实机型）
     * 与播放（红米）之间自相矛盾，而官方 App 在同一台机器上上报的就是真实机型
     * （2026-11-13 抓包：device_type=2211133C / brand=Xiaomi / os_api=35 / os_version=15 /
     * resolution=1280*720 / dpi=240）。host 与渠道仍保留原验证值，可用 `hg_net` 热更覆盖。
     */
    private val BUILTIN = Net(
        playHost = "https://api5-normal-sinfonlineb.fqnovel.com",
        deviceType = HongguoEnv.deviceType,
        deviceBrand = HongguoEnv.deviceBrand,
        osVersion = HongguoEnv.osVersion,
        channel = "update_64",
    )

    /**
     * 内置 TNC httpDns 表：抄自 HGTV 1.3.8.12 运行时 TNC 下发（MuMu 实抓
     * tnc-v1.json，2026-11-22 定案=豁免入口段）。api3 系列与 log/i 域不在播放
     * 链路上，不占表；表只服务 sinfonline/lf/lq/hl 六个 api5 播放 host。
     */
    private val BUILTIN_DNS: Map<String, List<String>> = linkedMapOf(
        "api5-normal-sinfonlinea.fqnovel.com" to listOf("101.126.1.155", "101.126.1.160"),
        "api5-normal-sinfonlineb.fqnovel.com" to listOf("101.126.1.161", "101.126.1.156"),
        "api5-normal-sinfonlinec.fqnovel.com" to listOf("101.126.1.162", "101.126.1.157"),
        "api5-normal-lf.fqnovel.com" to listOf("122.14.229.102", "122.14.229.7"),
        "api5-normal-lq.fqnovel.com" to listOf("122.14.229.103", "101.126.58.177"),
        "api5-normal-hl.fqnovel.com" to listOf("101.126.58.175", "101.126.58.176"),
    )

    /** TNC httpDns 直连表（内存态；restore/applyFrom 载入，置于 BUILTIN_DNS 之后初始化）。 */
    @Volatile private var dnsTable: Map<String, List<String>> = BUILTIN_DNS

    /** 仅测试用：清空内存覆写，回落内置档案。 */
    internal fun resetForTest() {
        overrideNet = null
        dnsTable = BUILTIN_DNS
    }
}
