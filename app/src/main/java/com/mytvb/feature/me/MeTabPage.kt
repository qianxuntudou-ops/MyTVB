package com.mytvb.feature.me

import com.mytvb.core.ui.tab.HomeTabPage

/**
 * 「我的」页子 tab（历史/收藏/稍后观看）协议：HomeTabPage 的严格超集，
 * 额外携带重选回顶/会话刷新让位/宿主事件三个语义。
 * 这些页面同时可作为首页个人页签复用（见 HomeFragmentStateAdapter），
 * 届时仅按 HomeTabPage 使用，扩展方法不被调用。
 */
interface MeTabPage : HomeTabPage {
    fun onTabReselected() {}

    /**
     * 列表回顶并聚焦首个内容项（重选当前 tab 的快捷回顶，与 HomeTabPage 一致）。
     * 返回 false 表示页面无法接手，宿主回退到普通 scrollToTop。
     */
    fun scrollToTopAndFocus(): Boolean = false

    /**
     * 会话跳变（UserSessionChanged）触发的全量刷新是否让位：
     * 历史页从播放器返回时静默刷新进行中（同接口数据在途），回顶重排只会把它挤掉，
     * 此时返回 true 让宿主跳过 refresh；真实登出跳变由下次 tab 重选/手动刷新兜底。
     */
    fun shouldDeferSessionRefresh(): Boolean = false

    enum class HostEvent {
        SELECT_TAB4,
        CLICK_TAB4,
        BACK_PRESSED,
        KEY_MENU_PRESS
    }

    fun onHostEvent(event: HostEvent): Boolean = false
}
