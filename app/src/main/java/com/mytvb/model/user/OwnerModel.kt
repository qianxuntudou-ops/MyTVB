package com.mytvb.model.user

import com.google.gson.annotations.SerializedName

data class OfficialVerifySimple(
    @SerializedName("type")
    val type: Int = -1,
    @SerializedName("desc")
    val desc: String = ""
)

data class VipSimple(
    @SerializedName("vipType")
    val vipType: Int = 0,
    @SerializedName("vipStatus")
    val vipStatus: Int = 0,
    @SerializedName("avatar_subscript")
    val avatarSubscript: Int = 0
)
