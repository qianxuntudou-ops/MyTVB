package com.mytvb.feature.hongguo.net

/**
 * 红果签名链版本档案（**2026-11-25 起内置常量，取消云端热更**）。
 *
 * [code] 进 query 五处 + UA，[versionName] 进 Ladon/Argus/Medusa 签名；
 * [HongguoClient] 的签名链参数按 [HongguoVersionRef] 收口。
 *
 * 取消热更的理由见 [HongguoNetProfiles]：服务端改版总是整套变，热更改不动形态，
 * 反而让已发布的 Release 附件把旧值推给新 APK。现在改版本号就发版。
 */
interface HongguoVersionRef {
    val code: String
    val versionName: String
}

internal object HongguoVersionProfiles {

    data class Profile(override val code: String, override val versionName: String) : HongguoVersionRef

    /** 全通道默认（官方 7.4.5.32）。 */
    val default: HongguoVersionRef = Profile("74532", "7.4.5.32")

    /** plan/v1 相关作品通道：服务端按 version_code 下发不同 cell 结构，72232 才回
     *  「相关作品」（73532 只回「猜你喜欢」），故独立钉版。 */
    val plan: HongguoVersionRef = Profile("72232", "7.2.2.32")
}
