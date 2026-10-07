package com.mytvb.model.user

import com.google.gson.annotations.SerializedName
import java.io.Serializable

data class FollowingModel(
    @SerializedName("mid")
    val mid: Long = 0,
    
    @SerializedName("attribute")
    val attribute: Int = 0,
    
    @SerializedName("mtime")
    val mtime: Long = 0,
    
    @SerializedName("tag")
    val tag: Int = 0,
    
    @SerializedName("special")
    val special: Int = 0,
    
    @SerializedName("uname")
    val uname: String = "",
    
    @SerializedName("face")
    val face: String = "",
    
    @SerializedName("sign")
    val sign: String = "",

    @SerializedName("official_verify")
    val officialVerify: OfficialVerifySimple? = null,

    @SerializedName("vip")
    val vip: VipSimple? = null
) : Serializable
