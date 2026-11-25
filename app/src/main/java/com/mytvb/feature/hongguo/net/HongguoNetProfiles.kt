package com.mytvb.feature.hongguo.net

/**
 * 红果播放网络常量（**2026-11-25 起全部内置，取消云端热更**）。
 *
 * 这些值（host / channel / 取源路由 / DNS 直连表）曾经由 version.json 的
 * `hg_net` / `hg_dns` 远程覆写，理由是不发版就能换。实际用下来收益为负：
 * 服务端每次收紧都是「域名 + 路径 + 参数集 + 画像 + UA」整套一起变，热更只能改
 * 其中几个字段、改不动形态；而且**已发布的 Release 附件会把旧值推给新 APK**
 * （2026-11-25 播放全空，旧档 `["player"]` 把默认盖回已失效通道就是路径之一）。
 * 所以直接内置：红果改版就发版，签名版本档案同理见 [HongguoVersionProfiles]。
 */
internal object HongguoNetProfiles {

    /** 取源路由名（[HongguoPlay.fetchEntry] 的分支键）。 */
    const val ROUTE_PLAYER = "player"
    const val ROUTE_V5TAB = "v5tab"
    const val ROUTE_PRELOAD = "preload"
    const val ROUTE_VIDEO_MODEL = "video_model"

    /** 内置默认取源路由：`video_model`=官方当前客户端单集直取（2026-11-25 真网验证放行）。
     *  只取首位、不做自动回退——多形态串联会让服务端在几十秒内看到同一设备两种请求形态。 */
    private val BUILTIN_ROUTES = listOf(ROUTE_VIDEO_MODEL)

    /** 内置播放 host（player/preload/v5tab 与 applog 上报使用；`video_model` 路由自带
     *  `reading.snssdk.com`，不走这里）。 */
    private const val BUILTIN_HOST = "https://api5-normal-sinfonlineb.fqnovel.com"

    private const val BUILTIN_CHANNEL = "update_64"

    fun playHost(): String = BUILTIN_HOST
    fun channel(): String = BUILTIN_CHANNEL
    fun playRoutes(): List<String> = BUILTIN_ROUTES
    fun activeRoute(): String = BUILTIN_ROUTES.first()

    /** 播放 query 的设备画像：如实取运行设备（与注册/详情同源）。**`video_model` 路由例外**：
     *  它固定报官方手机画像（Redmi 25053RT47C / Android 16 / os_api 36），见 [HongguoPlay]。 */
    fun deviceType(): String = HongguoEnv.deviceType
    fun deviceBrand(): String = HongguoEnv.deviceBrand
    fun osVersion(): String = HongguoEnv.osVersion

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

    fun dnsTable(): Map<String, List<String>> = BUILTIN_DNS
}
