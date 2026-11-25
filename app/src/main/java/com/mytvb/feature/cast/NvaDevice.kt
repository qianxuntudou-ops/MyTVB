package com.mytvb.feature.cast

import android.content.Context
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Random

/**
 * NVA 设备身份与 UPnP 描述文档。
 *
 * 手机端靠这几个字段判定"这是一台支持弹幕的官方小电视"：
 *   friendlyName / manufacturer / capability / ottVersion / UDN 前缀
 * 任一字段不符会降级成普通 DLNA 渲染器，弹幕能力随之消失。
 */
internal object NvaDevice {

    private const val PREFS = "cast_device"
    private const val KEY_USN = "usn"
    private const val KEY_NAME = "friendly_name"
    private const val KEY_BOOT_ID = "boot_id"
    private const val KEY_RECEIVE_ENABLED = "receive_enabled"

    /** 设备名最大长度；过长时部分手机端会把条目截断得难以区分。 */
    const val MAX_FRIENDLY_NAME_LENGTH = 64

    /**
     * 连续 2 个及以上的分隔符。用于把 `我的小电视--客厅` 折叠成 `我的小电视-客厅`。
     * 只匹配重复的分隔符，单个分隔符与普通空格不受影响。
     */
    private val REPEATED_SEPARATOR = Regex("[-_—·]{2,}")

    /**
     * 官方 TV 端的类型名，与 B 站客户端的前缀校验对齐（详见 [friendlyName]）。
     *
     * 本模块**只在名字尾部追加用户填写的区分名**，不添加任何能力标记
     * （"有弹幕"之类一律不加）；用户写的括号、空格、`·` 等字符原样保留，
     * 唯一会规整的是 [normalizeFriendlyName] 里的"连续重复分隔符折叠成一个"（手误清理）。
     */
    const val DEFAULT_FRIENDLY_NAME = "我的小电视"

    /** 默认型号名。手机端在多台同类设备时才展示型号，单台时不影响显示。 */
    const val DEFAULT_MODEL_NAME = "MyTVB"

    /** 默认品牌名（`<X_brandName>`）。协议里该字段是"电视品牌名"。 */
    const val DEFAULT_BRAND_NAME = "MyTVB"

    /** 型号名 / 品牌名长度上限：这两个字段会出现在手机投屏列表里，太长会被截断得难看。 */
    private const val MAX_MODEL_NAME_LENGTH = 12

    private const val USN_CHARSET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"

    /** UDN 格式为 "XY" + 35 位 [0-9A-Z]，与官方 TV 端一致。 */
    private const val USN_RANDOM_LENGTH = 35

    /** 254 = 支持弹幕；1 = 支持弹幕 + 4K。 */
    private const val CAPABILITY = 254

    // region 投屏接收开关（与设备身份同库存储，冷启动即可同步读取，不依赖 DataStore 缓存）

    /**
     * 投屏接收是否开启，**默认关**（原为默认开）。
     *
     * 接收端要在 Application.onCreate 里起常驻线程（SSDP 组播 + socket 监听）才能被手机搜到，
     * 而 2026-11-25 实测：Fengmi 小明投影（Android 9 / API 28）装 R8 release 包后，该线程类加载
     * 失败抛 NoClassDefFoundError: Invalid descriptor: .，未捕获异常直接把进程带走，表现为每次
     * 冷启动秒退、首页都看不到（覆盖安装可临时恢复但会复发）。
     *
     * 投屏是低频功能，默认关还有两个好处：省掉 1Hz 主线程轮询与周期 SSDP 组播；
     * 把这条常驻路径整体移出冷启动。需要在 设置→浏览与投屏 里手动打开。
     * 老用户此前显式开过（存储里已有 true）不受影响。
     */
    fun isReceiveEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_RECEIVE_ENABLED, false)

    /** 设置投屏接收开关，返回新值。 */
    fun setReceiveEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_RECEIVE_ENABLED, enabled).apply()
    }

    /** 开关的存储值（与设置页「开/关」中文存储口径一致）。 */
    fun receiveEnabledStored(context: Context): String = if (isReceiveEnabled(context)) "开" else "关"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // endregion

    fun usn(context: Context): String {
        val prefs = prefs(context)
        prefs.getString(KEY_USN, null)?.takeIf { it.isValidUsn() }?.let { return it }
        val generated = buildString(USN_RANDOM_LENGTH + 2) {
            append("XY")
            val random = Random()
            repeat(USN_RANDOM_LENGTH) {
                append(USN_CHARSET[random.nextInt(USN_CHARSET.length)])
            }
        }
        prefs.edit().putString(KEY_USN, generated).apply()
        return generated
    }

    /**
     * 设备序列号：从 UDN 派生，保证每台设备唯一。
     *
     * 不能写死同一值（照抄官方设备）：两台电视的 modelName / modelNumber /
     * serialNumber 三件套完全相同时，客户端做设备指纹会把两台当成"同一型号的多个实例"，
     * 丢弃 friendlyName、改用「通用名-型号-短码」合成名展示。
     *
     * serialNumber / modelNumber 不参与 Nirvana 能力识别（识别锚点是 friendlyName /
     * manufacturer / capability / ottVersion / NirvanaControl 服务），因此唯一化是安全的。
     */
    fun serialNumber(context: Context): String = usn(context).substring(2, 14)

    /** 型号编号：同样从 UDN 派生，避免与 serialNumber 一起构成"同型号"指纹。 */
    fun modelNumber(context: Context): String = usn(context).substring(14, 18)

    /**
     * 型号名（`<modelName>`）—— 填用户区分名（`客厅` / `sw2493t`）。
     *
     * 两台电视同时在线时，客户端会拼出「类型名-品牌/型号-短码」的合成名；
     * 型号/品牌写死同一值时无法区分。取值：名字去掉 `我的小电视` 前缀后的部分；
     * 名字保持默认时退回 [DEFAULT_MODEL_NAME]。
     */
    fun modelName(context: Context): String = customSuffix(context).ifBlank { DEFAULT_MODEL_NAME }

    /**
     * 品牌名（`<X_brandName>`）—— 与 [modelName] 取同一个值。
     *
     * 协议语义是"电视品牌名"（官方 TV：Xiaomi / Meizu …），多台设备时客户端
     * 无论读哪一个都能区分。不参与哔哩必连能力识别。
     */
    fun brandName(context: Context): String = customSuffix(context).ifBlank { DEFAULT_BRAND_NAME }

    /**
     * 用户区分名：名字去掉 `我的小电视` 前缀与前导分隔符后的部分。
     *
     * `我的小电视-客厅` → `客厅`；`客厅` → `客厅`；`我的小电视` → 空串。
     * 长度上限 12：多台时它会进合成名，过长会被客户端截断得难看。
     */
    private fun customSuffix(context: Context): String =
        savedName(context)
            .removePrefix(DEFAULT_FRIENDLY_NAME)
            .trim()
            .trimStart('-', '_', ' ', '—', '·')
            .trim()
            .take(MAX_MODEL_NAME_LENGTH)

    /**
     * 名字规范化：只折叠**连续重复**的分隔符（`--`、`——`、`-_-` 等 → 单个 `-`），
     * 除此之外一个字符都不动。单个 `-` / `·` / 空格（如 `客厅 · 电视`）保持原样。
     *
     * 读取和写入都走这里：老数据里已经存了 `--` 的，升级后无需重新设置即自动修正。
     */
    fun normalizeFriendlyName(raw: String): String = raw.trim().replace(REPEATED_SEPARATOR, "-")

    /**
     * 用户在设置里填写的名字（规范化后，可能含 `客厅` 这种纯位置名）。
     *
     * 注意：这**不是**发出去的 `<friendlyName>`，只是"区分名"的原料。
     */
    fun savedName(context: Context): String {
        return prefs(context)
            .getString(KEY_NAME, null)
            ?.let { normalizeFriendlyName(it) }
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_FRIENDLY_NAME
    }

    /**
     * 设备描述里的 `<friendlyName>`，也就是手机投屏列表里显示的名字。
     *
     * 两条实测结论决定了它的形态：
     *
     * 1) **必须以 `我的小电视` 开头。** 把名字改成 `客厅`，该设备在手机
     *    投屏列表里**直接消失**；改回 `我的小电视-客厅` 又不重启就立刻出现。
     *    ⇒ 客户端对 friendlyName 做前缀校验，不含类型名的设备被当成非哔哩必连设备过滤掉。
     *
     * 2) **类型名后面不能再跟 `-`。** 两台同时在线时，客户端会把名字按 `-` 重排，
     *    `我的小电视-客厅` 被显示成 `我的小电视--客厅`（多出一条杠）。去掉 `-` 就不构成切分点。
     *
     * 所以最终形态是「类型名 + 区分名」直接拼接：`我的小电视客厅`、`我的小电视sw2493t`。
     * 既保住前缀校验，又保持每台唯一，也不会被客户端插杠。
     */
    fun friendlyName(context: Context): String = displayName(context)

    /**
     * 投屏列表里的完整名：`我的小电视` + 用户填写的区分名（无分隔符）。
     *
     * 设置页、日志、Toast 统一用它，便于用户一眼看到手机端会显示成什么。
     */
    fun displayName(context: Context): String {
        val suffix = customSuffix(context)
        return if (suffix.isBlank()) DEFAULT_FRIENDLY_NAME else DEFAULT_FRIENDLY_NAME + suffix
    }

    fun setFriendlyName(context: Context, name: String) {
        prefs(context)
            .edit()
            .putString(
                KEY_NAME,
                normalizeFriendlyName(name).take(MAX_FRIENDLY_NAME_LENGTH).ifBlank { DEFAULT_FRIENDLY_NAME },
            )
            .apply()
    }

    /**
     * UPnP 的 BOOTID。设备重启（这里是 SSDP 服务重启）时递增，
     * 控制点看到 BOOTID 变化会丢弃缓存的设备描述重新拉取。
     *
     * 改名后必须让这个值变一下，否则手机端可能按 LOCATION 缓存旧的 friendlyName，
     * 投屏列表里仍显示旧名字（max-age 是 30 分钟，等缓存过期太久）。
     */
    fun bootId(context: Context): Int {
        return prefs(context).getInt(KEY_BOOT_ID, 1).takeIf { it > 0 } ?: 1
    }

    /** 递增 BOOTID 并返回新值。规范要求是 31 位无符号整数，从 1 开始。 */
    fun bumpBootId(context: Context): Int {
        val prefs = prefs(context)
        val next = (prefs.getInt(KEY_BOOT_ID, 1) + 1) and Int.MAX_VALUE
        val safe = next.coerceAtLeast(1)
        prefs.edit().putInt(KEY_BOOT_ID, safe).apply()
        return safe
    }

    /**
     * 取本机局域网 IPv4。优先无线网卡，其次有线，避免多网卡时取到 VPN 或蜂窝地址。
     */
    fun localIpv4(): String? {
        val candidates =
            runCatching {
                NetworkInterface.getNetworkInterfaces()
                    .toList()
                    .filter { it.isUp && !it.isLoopback }
                    .flatMap { nic ->
                        nic.inetAddresses.toList()
                            .filterIsInstance<Inet4Address>()
                            .filter { it.isSiteLocalAddress && !it.isLoopbackAddress }
                            .map { nic.name to it.hostAddress.orEmpty() }
                    }
            }.getOrDefault(emptyList())

        val preferred =
            candidates.firstOrNull { (name, _) -> name.startsWith("wlan") }
                ?: candidates.firstOrNull { (name, _) -> name.startsWith("eth") }
                ?: candidates.firstOrNull()

        return preferred?.second
    }

    /**
     * 判断某个地址是否属于本机（有线、无线、回环都算）。
     * 用来区分"自己的组播被回环回来"和"另一台设备在广播"。
     */
    fun isLocalAddress(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val localAddresses =
            runCatching {
                NetworkInterface.getNetworkInterfaces()
                    .toList()
                    .filter { it.isUp }
                    .flatMap { nic -> nic.inetAddresses.toList().mapNotNull { it.hostAddress } }
            }.getOrDefault(emptyList())
        return localAddresses.any { it == host }
    }

    private fun String.isValidUsn(): Boolean =
        length == USN_RANDOM_LENGTH + 2 &&
            startsWith("XY") &&
            drop(2).all { it in USN_CHARSET }

    /**
     * UPnP 设备描述。Location 指向本机 HTTP 服务（与 NVA 长连接共用 9958 端口）。
     */
    fun deviceDescriptionXml(context: Context, locationBaseUrl: String): String {
        val usn = usn(context)
        val name = friendlyName(context)
        return buildString(2048) {
            append("""<?xml version="1.0" encoding="utf-8"?>""")
            append("""<root xmlns="urn:schemas-upnp-org:device-1-0" xmlns:dlna="urn:schemas-dlna-org:device-1-0">""")
            append("<specVersion><major>1</major><minor>0</minor></specVersion>")
            append("<device>")
            append("<deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>")
            append("<friendlyName>").append(escapeXml(name)).append("</friendlyName>")
            append("<manufacturer>Bilibili Inc.</manufacturer>")
            append("<manufacturerURL>https://bilibili.com/</manufacturerURL>")
            append("<modelDescription>云视听小电视</modelDescription>")
            append("<modelName>").append(escapeXml(modelName(context))).append("</modelName>")
            append("<modelNumber>").append(modelNumber(context)).append("</modelNumber>")
            append("<modelURL>https://app.bilibili.com/</modelURL>")
            append("<serialNumber>").append(serialNumber(context)).append("</serialNumber>")
            append("<UDN>uuid:").append(usn).append("</UDN>")
            // 品牌名跟随自定义名：多台设备时手机端用它区分条目（见 brandName 注释）。
            append("<X_brandName>").append(escapeXml(brandName(context))).append("</X_brandName>")
            append("<hostVersion>25</hostVersion>")
            append("<ottVersion>106400</ottVersion>")
            append("<channelName>master</channelName>")
            append("<capability>").append(CAPABILITY).append("</capability>")
            append("<dlna:X_DLNADOC>DMR-1.50</dlna:X_DLNADOC>")
            append("<dlna:X_DLNACAP>playcontainer-1-0</dlna:X_DLNACAP>")
            append("<serviceList>")
            appendService(
                serviceType = "urn:schemas-upnp-org:service:AVTransport:1",
                serviceId = "urn:upnp-org:serviceId:AVTransport",
                controlUrl = "AVTransport/action",
                eventUrl = "AVTransport/event",
                scpdUrl = "dlna/AVTransport.xml",
            )
            appendService(
                serviceType = "urn:schemas-upnp-org:service:RenderingControl:1",
                serviceId = "urn:upnp-org:serviceId:RenderingControl",
                controlUrl = "RenderingControl/action",
                eventUrl = "RenderingControl/event",
                scpdUrl = "dlna/RenderingControl.xml",
            )
            appendService(
                serviceType = "urn:schemas-upnp-org:service:ConnectionManager:1",
                serviceId = "urn:upnp-org:serviceId:ConnectionManager",
                controlUrl = "ConnectionManager/action",
                eventUrl = "ConnectionManager/event",
                scpdUrl = "dlna/ConnectionManager.xml",
            )
            appendService(
                serviceType = "urn:app-bilibili-com:service:NirvanaControl:3",
                serviceId = "urn:app-bilibili-com:serviceId:NirvanaControl",
                controlUrl = "NirvanaControl/action",
                eventUrl = "NirvanaControl/event",
                scpdUrl = "dlna/NirvanaControl.xml",
            )
            append("</serviceList>")
            append("<presentationURL>").append(escapeXml(locationBaseUrl)).append("</presentationURL>")
            append("</device>")
            append("</root>")
        }
    }

    private fun StringBuilder.appendService(
        serviceType: String,
        serviceId: String,
        controlUrl: String,
        eventUrl: String,
        scpdUrl: String,
    ) {
        append("<service>")
        append("<serviceType>").append(serviceType).append("</serviceType>")
        append("<serviceId>").append(serviceId).append("</serviceId>")
        append("<controlURL>").append(controlUrl).append("</controlURL>")
        append("<eventSubURL>").append(eventUrl).append("</eventSubURL>")
        append("<SCPDURL>").append(scpdUrl).append("</SCPDURL>")
        append("</service>")
    }

    /**
     * SCPD 描述。现代手机端只读 AVTransport/NirvanaControl 的存在性，
     * 不会真的按服务描述调用 SOAP，因此这里给最小合法文档即可。
     */
    fun scpdXml(path: String): String {
        val actions =
            when {
                path.endsWith("NirvanaControl.xml") ->
                    listOf(
                        "GetAppInfo",
                        "LoginWithCode",
                        "PrepareForMirrorProjection",
                        "SetDanmakuSwitch",
                        "AppendDanmaku",
                        "GetPlayInfo",
                        "GetAccountInfo",
                        "SwitchQuality",
                    )

                path.endsWith("AVTransport.xml") ->
                    listOf("SetAVTransportURI", "Play", "Pause", "Stop", "Seek", "GetTransportInfo")

                path.endsWith("RenderingControl.xml") ->
                    listOf("GetVolume", "SetVolume", "GetMute", "SetMute")

                else -> listOf("GetProtocolInfo")
            }

        return buildString(1024) {
            append("""<?xml version="1.0" encoding="utf-8"?>""")
            append("""<scpd xmlns="urn:schemas-upnp-org:service-1-0">""")
            append("<specVersion><major>1</major><minor>0</minor></specVersion>")
            append("<actionList>")
            actions.forEach { action ->
                append("<action><name>").append(action).append("</name>")
                append("<argumentList></argumentList></action>")
            }
            append("</actionList>")
            append("<serviceStateTable></serviceStateTable>")
            append("</scpd>")
        }
    }

    private fun escapeXml(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
}
