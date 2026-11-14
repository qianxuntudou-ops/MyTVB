package com.mytvb.model.user

import com.google.gson.annotations.SerializedName

data class UserDetailInfoModel(
    @SerializedName("isLogin")
    val isLogin: Boolean = false,
    
    @SerializedName("email_verified")
    val emailVerified: Int = 0,
    
    @SerializedName("face")
    val face: String = "",
    
    @SerializedName("face_nft")
    val faceNft: Int = 0,
    
    @SerializedName("level_info")
    val levelInfo: LevelInfo? = null,
    
    @SerializedName("mid")
    val mid: Long = 0,
    
    @SerializedName("mobile_verified")
    val mobileVerified: Int = 0,
    
    @SerializedName("money")
    val money: Double = 0.0,
    
    @SerializedName("moral")
    val moral: Int = 0,
    
    @SerializedName("official")
    val official: Official? = null,
    
    @SerializedName("officialVerify")
    val officialVerify: OfficialVerify? = null,
    
    @SerializedName("pendant")
    val pendant: Pendant? = null,
    
    @SerializedName("scores")
    val scores: Int = 0,
    
    @SerializedName("uname")
    val uname: String = "",
    
    @SerializedName("vipDueDate")
    val vipDueDate: Long = 0,
    
    @SerializedName("vipStatus")
    val vipStatus: Int = 0,
    
    @SerializedName("vipType")
    val vipType: Int = 0,
    
    @SerializedName("vip_pay_type")
    val vipPayType: Int = 0,
    
    @SerializedName("vip")
    val vip: Vip? = null,
    
    @SerializedName("vip_avatar_subscript")
    val vipAvatarSubscript: Int = 0,
    
    @SerializedName("vip_nickname_color")
    val vipNicknameColor: String = "",
    
    @SerializedName("vip_icon_url")
    val vipIconUrl: String = "",
    
    @SerializedName("vip_label")
    val vipLabel: VipLabel? = null,
    
    @SerializedName("wallet")
    val wallet: Wallet? = null,
    
    @SerializedName("wbi_img")
    val wbiImg: WbiImg? = null,
    
    @SerializedName("is_senior_member")
    val isSeniorMember: Int = 0,
    
    @SerializedName("recognition_image")
    val recognitionImage: String = ""
)

data class UserStatModel(
    @SerializedName("following")
    val following: Int = 0,
    
    @SerializedName("follower")
    val follower: Int = 0,
    
    @SerializedName("dynamic_count")
    val dynamicCount: Int = 0
)

data class UserSpaceInfo(
    @SerializedName("mid")
    val mid: Long = 0,
    
    @SerializedName("name")
    val name: String = "",
    
    @SerializedName("sex")
    val sex: String = "",
    
    @SerializedName("face")
    val face: String = "",
    
    @SerializedName("sign")
    val sign: String = "",
    
    @SerializedName("rank")
    val rank: Int = 0,
    
    @SerializedName("level")
    val level: Int = 0,
    
    @SerializedName("jointime")
    val joinTime: Long = 0,
    
    @SerializedName("moral")
    val moral: Int = 0,
    
    @SerializedName("silence")
    val silence: Int = 0,
    
    @SerializedName("coins")
    val coins: Double = 0.0,
    
    @SerializedName("vip")
    val vip: Vip? = null,
    
    @SerializedName("official")
    val official: Official? = null,
    
    @SerializedName("birthday")
    val birthday: String = "",
    
    @SerializedName("top_photo")
    val topPhoto: String = "",
    
    @SerializedName("top_v2")
    val topV2: List<TopV2>? = null,
    
    @SerializedName("live_room")
    val liveRoom: LiveRoom? = null,
    
    @SerializedName("pendant")
    val pendant: Pendant? = null,
    
    @SerializedName("nameplate")
    val nameplate: Nameplate? = null
)

data class Vip(
    @SerializedName("vipType")
    val vipType: Int = 0,
    
    @SerializedName("vipStatus")
    val vipStatus: Int = 0,
    
    @SerializedName("dueDate")
    val dueDate: Long = 0,
    
    @SerializedName("vip_pay_type")
    val vipPayType: Int = 0,
    
    @SerializedName("themeType")
    val themeType: Int = 0,
    
    @SerializedName("label")
    val label: VipLabel? = null,
    
    @SerializedName("avatar_subscript")
    val avatarSubscript: Int = 0,
    
    @SerializedName("nickname_color")
    val nicknameColor: String = "",
    
    @SerializedName("role")
    val role: Int = 0
)

data class VipLabel(
    @SerializedName("path")
    val path: String = "",
    
    @SerializedName("text")
    val text: String = "",
    
    @SerializedName("label_theme")
    val labelTheme: String = "",
    
    @SerializedName("text_color")
    val textColor: String = "",
    
    @SerializedName("bg_style")
    val bgStyle: Int = 0,
    
    @SerializedName("bg_color")
    val bgColor: String = "",
    
    @SerializedName("border_color")
    val borderColor: String = ""
)

data class Official(
    @SerializedName("role")
    val role: Int = 0,
    
    @SerializedName("title")
    val title: String = "",
    
    @SerializedName("desc")
    val desc: String = "",
    
    @SerializedName("type")
    val type: Int = 0
)

data class OfficialVerify(
    @SerializedName("type")
    val type: Int = 0,
    
    @SerializedName("desc")
    val desc: String = ""
)

data class Pendant(
    @SerializedName("pid")
    val pid: Int = 0,
    
    @SerializedName("name")
    val name: String = "",
    
    @SerializedName("image")
    val image: String = "",
    
    @SerializedName("expire")
    val expire: Int = 0,
    
    @SerializedName("image_enhance")
    val imageEnhance: String = "",
    
    @SerializedName("image_enhance_frame")
    val imageEnhanceFrame: String = ""
)

data class LevelInfo(
    @SerializedName("current_level")
    val currentLevel: Int = 0,
    
    @SerializedName("current_min")
    val currentMin: Int = 0,
    
    @SerializedName("current_exp")
    val currentExp: Int = 0,
    
    @SerializedName("next_exp")
    val nextExp: String = ""
)

data class Wallet(
    @SerializedName("mid")
    val mid: Long = 0,
    
    @SerializedName("bcoin_balance")
    val bcoinBalance: Double = 0.0,
    
    @SerializedName("coupon_balance")
    val couponBalance: Double = 0.0,
    
    @SerializedName("coupon_due_time")
    val couponDueTime: Long = 0
)

data class WbiImg(
    @SerializedName("img_url")
    val imgUrl: String = "",
    
    @SerializedName("sub_url")
    val subUrl: String = ""
)

data class TopV2(
    @SerializedName("id")
    val id: Long = 0,
    
    @SerializedName("title")
    val title: String = "",
    
    @SerializedName("type")
    val type: Int = 0,
    
    @SerializedName("url")
    val url: String = "",
    
    @SerializedName("cover")
    val cover: String = "",
    
    @SerializedName("oid")
    val oid: Long = 0
)

data class LiveRoom(
    @SerializedName("roomStatus")
    val roomStatus: Int = 0,
    
    @SerializedName("liveStatus")
    val liveStatus: Int = 0,
    
    @SerializedName("url")
    val url: String = "",
    
    @SerializedName("title")
    val title: String = "",
    
    @SerializedName("cover")
    val cover: String = "",
    
    @SerializedName("roomid")
    val roomId: Long = 0,
    
    @SerializedName("roundStatus")
    val roundStatus: Int = 0,
    
    @SerializedName("broadcast_type")
    val broadcastType: Int = 0,
    
    @SerializedName("watched_show")
    val watchedShow: WatchedShow? = null
)

data class WatchedShow(
    @SerializedName("switch")
    val switch: Boolean = false,
    
    @SerializedName("num")
    val num: Int = 0,
    
    @SerializedName("text_small")
    val textSmall: String = "",
    
    @SerializedName("text_large")
    val textLarge: String = "",
    
    @SerializedName("icon")
    val icon: String = "",
    
    @SerializedName("icon_location")
    val iconLocation: String = "",
    
    @SerializedName("icon_web")
    val iconWeb: String = ""
)

data class Nameplate(
    @SerializedName("nid")
    val nid: Int = 0,
    
    @SerializedName("name")
    val name: String = "",
    
    @SerializedName("image")
    val image: String = "",
    
    @SerializedName("image_small")
    val imageSmall: String = "",
    
    @SerializedName("level")
    val level: String = "",
    
    @SerializedName("condition")
    val condition: String = ""
)
