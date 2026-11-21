package com.mytvb.model

import java.io.Serializable

data class SettingModel(
    /** 稳定标识（多数为 DataStore key；动作/信息类为伪 key），点击分发与状态恢复均按它寻址。 */
    val key: String = "",
    var title: String = "",
    var info: String = "",
    /** 标题下方的灰色说明小字（可选，空则不占行——多数条目无需说明）。 */
    var description: String = "",
    /** 持久化存储值（稳定中文字面量/数字，不随界面语言变化）；info 仅作本地化显示。 */
    var value: String = ""
) : Serializable
