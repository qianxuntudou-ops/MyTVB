package com.tutu.myblbl.model.user

import com.google.gson.annotations.SerializedName

data class TvQrCodeData(
    @SerializedName("url")
    val url: String = "",
    @SerializedName("auth_code")
    val authCode: String = ""
)

data class TvPollData(
    @SerializedName("mid")
    val mid: Long = 0,
    @SerializedName("cookie_info")
    val cookieInfo: TvCookieInfo? = null
)

data class TvCookieInfo(
    @SerializedName("cookies")
    val cookies: List<TvCookie>? = null,
    @SerializedName("domains")
    val domains: List<String>? = null
)

data class TvCookie(
    @SerializedName("name")
    val name: String = "",
    @SerializedName("value")
    val value: String = "",
    @SerializedName("expires")
    val expires: Long = 0,
    @SerializedName("http_only")
    val httpOnly: Int = 0,
    @SerializedName("secure")
    val secure: Int = 0
)
