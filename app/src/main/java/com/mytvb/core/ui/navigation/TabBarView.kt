package com.mytvb.core.ui.navigation

import android.content.Context
import android.graphics.PorterDuff
import android.util.AttributeSet
import android.util.TypedValue
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.widget.AppCompatImageView
import com.mytvb.R
import com.mytvb.core.ui.image.AvatarBadgeView
import com.mytvb.core.ui.image.ImageLoader
import com.mytvb.core.ui.focus.SpatialFocusNavigator

class TabBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val buttonSearch: AppCompatImageView
    private val buttonHome: AppCompatImageView
    private val buttonCategory: AppCompatImageView
    private val buttonDynamic: AppCompatImageView
    private val buttonLive: AppCompatImageView
    private val buttonCctvLive: AppCompatImageView
    private val buttonCctvVod: AppCompatImageView
    private val buttonMe: AppCompatImageView
    private val buttonSetting: AppCompatImageView
    private val imageAvatar: AvatarBadgeView

    private val tabButtons = mutableListOf<AppCompatImageView>()
    private val searchIndex: Int get() = tabButtons.indexOf(buttonSearch)
    private var currentSelectedIndex = -1
    private var onTabClickListener: OnTabClickListener? = null

    private val selectedColor: Int
    private val normalColor: Int

    init {
        val view = LayoutInflater.from(context).inflate(R.layout.view_tab_bar, this, true)
        buttonSearch = view.findViewById(R.id.button_search)
        buttonHome = view.findViewById(R.id.button_home)
        buttonCategory = view.findViewById(R.id.button_category)
        buttonDynamic = view.findViewById(R.id.button_dynamic)
        buttonLive = view.findViewById(R.id.button_live)
        buttonCctvLive = view.findViewById(R.id.button_cctv_live)
        buttonCctvVod = view.findViewById(R.id.button_cctv_vod)
        buttonMe = view.findViewById(R.id.button_me)
        buttonSetting = view.findViewById(R.id.button_setting)
        imageAvatar = view.findViewById(R.id.image_avatar)

        val typedValueSelected = TypedValue()
        context.theme.resolveAttribute(R.attr.tabBarSelected, typedValueSelected, true)
        selectedColor = typedValueSelected.data

        val typedValueNormal = TypedValue()
        context.theme.resolveAttribute(R.attr.tabBarNormal, typedValueNormal, true)
        normalColor = typedValueNormal.data

        tabButtons.addAll(listOf(
            buttonHome,
            buttonCategory,
            buttonDynamic,
            buttonLive,
            buttonCctvLive,
            buttonCctvVod,
            buttonMe,
            buttonSearch
        ))

        tabButtons.forEach { button ->
            applyTabColor(button, false)
        }

        setupClickListeners()
        updateSideFocusChain()
    }

    /**
     * 侧栏竖向焦点链动态维护：XML 只给「我的」写了 nextFocusDown=头像，但按钮可见性
     * 随设置增删（个人页签开时「我的」隐藏），组内最后一个可见按钮按下键会落回几何
     * 搜索，被垂直更近的内容区卡片抢走焦点（关注页实测跳到 UP 卡片而非头像）。
     * 每次可见性变化后重算：组内最后一个可见按钮显式接到底部元素（头像可见接头像，
     * 否则接设置），头像的 nextFocusUp 同步回接，其余按钮恢复默认几何搜索。
     */
    private fun updateSideFocusChain() {
        // 视觉顺序自上而下（搜索按钮在侧栏最顶部，不属于底部组）
        val bottomButtons = listOf(
            buttonHome, buttonCategory, buttonDynamic, buttonLive,
            buttonCctvLive, buttonCctvVod, buttonMe
        )
        bottomButtons.forEach { it.nextFocusDownId = View.NO_ID }
        val lastVisible = bottomButtons.lastOrNull { it.visibility == VISIBLE }
        val downTargetId = if (imageAvatar.visibility == VISIBLE) imageAvatar.id else buttonSetting.id
        lastVisible?.nextFocusDownId = downTargetId
        imageAvatar.nextFocusUpId = lastVisible?.id ?: View.NO_ID
    }

    private fun applyTabColor(view: View, isSelected: Boolean) {
        if (view is AppCompatImageView) {
            view.setColorFilter(
                if (isSelected) selectedColor else normalColor,
                PorterDuff.Mode.SRC_IN
            )
            view.isSelected = isSelected
        }
    }

    private fun setupClickListeners() {
        tabButtons.forEachIndexed { index, button ->
            button.setOnClickListener {
                selectTab(index)
            }
            button.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) {
                    return@setOnKeyListener false
                }
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        onTabClickListener?.onTabNavigateRight(index) == true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> true
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        if (button === buttonSearch) true else false
                    }
                    else -> false
                }
            }
        }

        buttonSetting.setOnClickListener {
            onTabClickListener?.onSettingClick()
        }
        buttonSetting.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) {
                return@setOnKeyListener false
            }
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> true
                KeyEvent.KEYCODE_DPAD_RIGHT -> onTabClickListener?.onSideButtonNavigateRight() ?: false
                KeyEvent.KEYCODE_DPAD_UP -> false
                KeyEvent.KEYCODE_DPAD_DOWN -> true
                else -> false
            }
        }

        imageAvatar.setOnClickListener {
            onTabClickListener?.onAvatarClick()
        }
        imageAvatar.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) {
                return@setOnKeyListener false
            }
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> true
                KeyEvent.KEYCODE_DPAD_RIGHT -> onTabClickListener?.onSideButtonNavigateRight() ?: false
                KeyEvent.KEYCODE_DPAD_UP -> false
                KeyEvent.KEYCODE_DPAD_DOWN -> false
                else -> false
            }
        }
    }

    fun selectTab(index: Int) {
        com.mytvb.core.common.log.AppLog.d(
            "FocusTrace",
            "selectTab index=$index currentSelectedIndex=$currentSelectedIndex"
        )
        if (index < 0 || index >= tabButtons.size) return
        if (index == 1 && buttonCategory.visibility == GONE) return
        if (index == 3 && buttonLive.visibility == GONE) return
        if (index == 4 && buttonCctvLive.visibility == GONE) return
        if (index == 5 && buttonCctvVod.visibility == GONE) return
        if (index == 6 && buttonMe.visibility == GONE) return
        if (currentSelectedIndex == index) {
            if (index == searchIndex) {
                onTabClickListener?.onSearchClick()
            } else {
                onTabClickListener?.onTabReselected(index)
            }
            return
        }

        tabButtons.getOrNull(currentSelectedIndex)?.let { applyTabColor(it, false) }
        currentSelectedIndex = index
        applyTabColor(tabButtons[currentSelectedIndex], true)

        if (index == searchIndex) {
            onTabClickListener?.onSearchClick()
        } else {
            onTabClickListener?.onTabSelected(index)
        }
    }

    fun setOnTabClickListener(listener: OnTabClickListener) {
        this.onTabClickListener = listener
    }

    fun setLiveButtonVisible(visible: Boolean) {
        buttonLive.visibility = if (visible) VISIBLE else GONE
        updateSideFocusChain()
    }

    fun isLiveButtonVisible(): Boolean {
        return buttonLive.visibility == VISIBLE
    }

    fun setCctvLiveButtonVisible(visible: Boolean) {
        buttonCctvLive.visibility = if (visible) VISIBLE else GONE
        updateSideFocusChain()
    }

    fun isCctvLiveButtonVisible(): Boolean {
        return buttonCctvLive.visibility == VISIBLE
    }

    fun setCctvVodButtonVisible(visible: Boolean) {
        buttonCctvVod.visibility = if (visible) VISIBLE else GONE
        updateSideFocusChain()
    }

    fun isCctvVodButtonVisible(): Boolean {
        return buttonCctvVod.visibility == VISIBLE
    }

    fun setCategoryButtonVisible(visible: Boolean) {
        buttonCategory.visibility = if (visible) VISIBLE else GONE
        updateSideFocusChain()
    }

    fun isCategoryButtonVisible(): Boolean {
        return buttonCategory.visibility == VISIBLE
    }

    fun setMeButtonVisible(visible: Boolean) {
        buttonMe.visibility = if (visible) VISIBLE else GONE
        updateSideFocusChain()
    }

    fun isMeButtonVisible(): Boolean {
        return buttonMe.visibility == VISIBLE
    }

    fun showAvatar(show: Boolean) {
        imageAvatar.visibility = if (show) VISIBLE else GONE
        updateSideFocusChain()
    }

    fun setAvatarUrl(avatarUrl: String?) {
        if (avatarUrl.isNullOrBlank()) {
            ImageLoader.loadDrawableRes(
                imageView = imageAvatar,
                resId = R.drawable.default_avatar,
                circleCrop = true
            )
            return
        }

        ImageLoader.loadCircle(
            imageView = imageAvatar,
            url = avatarUrl,
            placeholder = R.drawable.default_avatar,
            error = R.drawable.default_avatar
        )
    }

    fun setAvatarBadge(officialVerifyType: Int, vipStatus: Int = 0, vipType: Int = 0, vipAvatarSubscript: Int = 0) {
        imageAvatar.setBadge(officialVerifyType, vipStatus, vipType, vipAvatarSubscript)
    }

    fun getCurrentSelectedIndex(): Int = currentSelectedIndex

    fun restoreTabHighlight(index: Int) {
        if (index < 0 || index >= tabButtons.size) return
        tabButtons.getOrNull(currentSelectedIndex)?.let { applyTabColor(it, false) }
        currentSelectedIndex = index
        applyTabColor(tabButtons[currentSelectedIndex], true)
    }

    fun focusCurrentTab(): Boolean {
        return tabButtons.getOrNull(currentSelectedIndex)?.requestFocus() == true
    }

    /** 只把焦点放到主页按钮，不切换页面。退出前多一步回到主页用。 */
    fun focusHomeTab(): Boolean {
        return buttonHome.requestFocus()
    }

    fun isHomeTabFocused(): Boolean = buttonHome.isFocused

    fun isHomeTabSelected(): Boolean = currentSelectedIndex == tabButtons.indexOf(buttonHome)

    fun focusNearestButtonTo(anchorView: View?): Boolean {
        val candidates = buildList {
            add(buttonSearch)
            add(buttonHome)
            add(buttonCategory)
            add(buttonDynamic)
            add(buttonLive)
            add(buttonCctvLive)
            add(buttonCctvVod)
            add(buttonMe)
            add(imageAvatar)
            add(buttonSetting)
        }.filter { it.visibility == VISIBLE && it.isFocusable }
        if (candidates.isEmpty()) {
            return false
        }
        return SpatialFocusNavigator.requestBestCandidate(
            anchorView = anchorView,
            candidates = candidates,
            direction = View.FOCUS_LEFT,
            fallback = {
                focusCurrentTab() || candidates.firstOrNull()?.requestFocus() == true
            }
        )
    }

    interface OnTabClickListener {
        fun onTabSelected(index: Int)
        fun onTabReselected(index: Int)
        fun onTabNavigateRight(index: Int): Boolean
        fun onSideButtonNavigateRight(): Boolean
        fun onSearchClick()
        fun onSettingClick()
        fun onAvatarClick()
    }
}
