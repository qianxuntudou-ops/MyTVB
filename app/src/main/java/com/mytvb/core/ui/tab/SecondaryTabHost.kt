package com.mytvb.core.ui.tab

/**
 * 二级 tab 宿主接口：子 tab 页（历史/收藏/稍后观看等）跨宿主复用时，
 * 通过它回调宿主的 tab 栏交互，避免硬编码转型某个具体 Fragment。
 * 当前实现：MeFragment（「我的」页）与 HomeFragment（首页并入个人页签时）。
 */
interface SecondaryTabHost {
    /** 列表顶行 UP 的落点：聚焦当前选中的二级 tab（不切页）。 */
    fun focusSelectedTabFromContent(): Boolean

    /** 内容网格左右边缘切二级 tab；越界方向由宿主决定（回主边栏/吞键）。 */
    fun switchAdjacentTabFromContentEdge(delta: Int): Boolean
}
