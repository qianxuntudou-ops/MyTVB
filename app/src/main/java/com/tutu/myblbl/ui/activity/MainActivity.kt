package com.tutu.myblbl.ui.activity

import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentTransaction
import androidx.fragment.app.commit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.util.UnstableApi
import com.tutu.myblbl.R
import com.tutu.myblbl.MyBLBLApplication
import com.tutu.myblbl.databinding.ActivityMainBinding
import com.tutu.myblbl.event.AppEventHub
import com.tutu.myblbl.network.session.NetworkSessionGateway
import com.tutu.myblbl.repository.UserRepository
import com.tutu.myblbl.core.ui.base.BaseActivity
import com.tutu.myblbl.core.ui.base.OnBackPressedHandler
import com.tutu.myblbl.model.user.UserDetailInfoModel
import com.tutu.myblbl.model.video.VideoModel
import com.tutu.myblbl.feature.category.CategoryFragment
import com.tutu.myblbl.feature.dynamic.DynamicFragment
import com.tutu.myblbl.feature.home.HomeFragment
import com.tutu.myblbl.feature.live.LiveFragment
import com.tutu.myblbl.feature.me.MeFragment
import com.tutu.myblbl.ui.fragment.main.MainNavigationViewModel
import com.tutu.myblbl.ui.fragment.main.MainTabFocusTarget
import com.tutu.myblbl.feature.search.SearchNewFragment
import com.tutu.myblbl.feature.settings.SettingsFragment
import com.tutu.myblbl.feature.settings.SignInFragment
import com.tutu.myblbl.ui.dialog.UsageTipDialog
import com.tutu.myblbl.ui.dialog.UserInfoDialog
import com.tutu.myblbl.feature.player.PlayerInstancePool
import com.tutu.myblbl.feature.player.PlayerLaunchContext
import com.tutu.myblbl.feature.player.VideoPlayerFragment
import com.tutu.myblbl.core.common.log.AppLog
import com.tutu.myblbl.core.ui.navigation.TabBarView
import com.tutu.myblbl.core.common.content.ContentFilter
import com.tutu.myblbl.core.common.settings.AppSettingsDataStore
import com.tutu.myblbl.core.startup.AppStartupScheduler
import com.tutu.myblbl.core.ui.image.ImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import java.lang.ref.WeakReference

@OptIn(UnstableApi::class)
class MainActivity : BaseActivity<ActivityMainBinding>(), TabBarView.OnTabClickListener {

    private data class FocusRestoreAnchor(
        val viewRef: WeakReference<View>
    )

    companion object {
        private const val SETTINGS_OVERLAY_TAG = "settings"
        private const val SETTINGS_OVERLAY_EXIT_ANIM_MS = 275L
        private const val SEARCH_TAB_INDEX = 5
        private const val STARTUP_TAG = "AppStartup"
        // Splash 只负责覆盖系统拉起到 Activity 壳可绘制前的短窗口。
        // 推荐内容是否 ready 单独打点，不再把网络/首屏卡片 inflate 的波动转化成黑屏时间。
        private const val SPLASH_TIMEOUT_MS = 1200L
        private const val SESSION_PREWARM_DELAY_MS = 20_000L
    }

    // tab 顺序固定（与 fragmentFactories 一一对应）：
    // 0 推荐 / 1 分类 / 2 动态 / 3 直播 / 4 我的 / 5 搜索。
    // 用 lazy factory 让 4 个非首屏 tab 真正点中时再构造，省去 1.1s 的 onCreate 大段时间。
    private val fragmentFactories: List<() -> Fragment> = listOf(
        { HomeFragment.newInstance() },
        { CategoryFragment.newInstance() },
        { DynamicFragment.newInstance() },
        { LiveFragment.newInstance() },
        { MeFragment.newInstance() },
        { SearchNewFragment.newInstance() }
    )
    private val fragments: MutableList<Fragment?> = MutableList(fragmentFactories.size) { null }
    private val appEventHub: AppEventHub by inject()
    private val mainNavigationViewModel: MainNavigationViewModel by viewModels()
    private val sessionGateway: NetworkSessionGateway by inject()
    private val userRepository: UserRepository by inject()
    private var currentFragmentIndex = -1
    private var exitTime: Long = 0
    private val exitInterval = 2000L
    private val focusRestoreAnchors = ArrayDeque<FocusRestoreAnchor>()
    private var lastBackStackEntryCount = 0
    private var pendingFocusRestoreDelayMs = 0L
    private var startupTasksScheduled = false
    private var startupShellRevealed = false
    private var homeContentReadyLogged = false
    private var startupAvatarRefreshScheduled = false
    private var postHomeAvatarRefreshScheduled = false
    private var avatarRefreshJob: Job? = null
    private val activityCreateStartMs = SystemClock.elapsedRealtime()
    private var lastKnownLoggedIn = false
    private var pendingInitialTabIndex = -1
    private var restoredFromSavedState = false
    private var restoredTabIndex = -1

    override fun getViewBinding(): ActivityMainBinding {
        val t0 = SystemClock.elapsedRealtime()
        AppLog.i(STARTUP_TAG, "STARTUP activity_main inflate start")
        val binding = ActivityMainBinding.inflate(layoutInflater)
        AppLog.i(STARTUP_TAG, "STARTUP getViewBinding elapsed=${SystemClock.elapsedRealtime() - t0}ms")
        return binding
    }

    override fun deferInitialFullscreenMode(): Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        val createStartMs = SystemClock.elapsedRealtime()
        AppLog.i(STARTUP_TAG, "STARTUP T1b MainActivity.onCreate start")
        if (savedInstanceState == null && shouldFinishDuplicateLauncherLaunch()) {
            super.onCreate(savedInstanceState)
            finish()
            return
        }
        restoredFromSavedState = savedInstanceState != null
        val t0 = SystemClock.elapsedRealtime()
        super.onCreate(savedInstanceState)
        AppLog.i(STARTUP_TAG, "STARTUP super.onCreate elapsed=${SystemClock.elapsedRealtime() - t0}ms")
        lastKnownLoggedIn = sessionGateway.isLoggedIn()
        restoredTabIndex = mainNavigationViewModel.getSavedTabIndex()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBackPressed()
            }
        })
        supportFragmentManager.addOnBackStackChangedListener {
            val currentCount = supportFragmentManager.backStackEntryCount
            if (currentCount < lastBackStackEntryCount) {
                restoreFocusAfterOverlayPop()
            }
            lastBackStackEntryCount = currentCount
            updateNavigationVisibility()
        }
        lastBackStackEntryCount = supportFragmentManager.backStackEntryCount
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                mainNavigationViewModel.events.collect { event ->
                    if (event == MainNavigationViewModel.Event.HomeContentReady) {
                        onHomeContentReady()
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val currentLoggedIn = sessionGateway.isLoggedIn()
                if (currentLoggedIn != lastKnownLoggedIn) {
                    lastKnownLoggedIn = currentLoggedIn
                    appEventHub.dispatch(AppEventHub.Event.UserSessionChanged)
                }
                refreshAvatar(allowNetworkFetch = false)
                appEventHub.events.collect { event ->
                    if (event == AppEventHub.Event.UserSessionChanged) {
                        lastKnownLoggedIn = sessionGateway.isLoggedIn()
                        refreshAvatar()
                    }
                    if (event is AppEventHub.Event.VideoBlockedByMinorProtection) {
                        dispatchVideoBlockedEventToCurrentFragment(event)
                    }
                }
            }
        }
        AppLog.i(STARTUP_TAG, "STARTUP T1c MainActivity.onCreate end elapsed=${SystemClock.elapsedRealtime() - createStartMs}ms totalFromActivityMs=${SystemClock.elapsedRealtime() - activityCreateStartMs}ms")
    }

    override fun initView() {
        val t0 = SystemClock.elapsedRealtime()
        initFragments()
        binding.myTabView.setOnTabClickListener(this)
        applyBackgroundImage()
        applyCategoryEntryVisibility()
        applyLiveEntryVisibility()
        AppLog.i(STARTUP_TAG, "STARTUP MainActivity.initView elapsed=${SystemClock.elapsedRealtime() - t0}ms")
    }

    override fun initData() {
        val startMs = SystemClock.elapsedRealtime()
        AppLog.i(STARTUP_TAG, "MainActivity.initData start")
        if (restoredFromSavedState) {
            restoreUiStateAfterRecreation()
        } else {
            pendingInitialTabIndex = resolveDefaultMainTabIndex()
            binding.myTabView.restoreTabHighlight(pendingInitialTabIndex)
            AppLog.i(STARTUP_TAG, "STARTUP deferInitialMainTab index=$pendingInitialTabIndex")
        }
        updateNavigationVisibility()
        scheduleSplashTimeout()
        binding.root.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (binding.root.viewTreeObserver.isAlive) {
                    binding.root.viewTreeObserver.removeOnPreDrawListener(this)
                }
                AppLog.i(
                    STARTUP_TAG,
                    "STARTUP T3a firstPreDraw elapsed=${SystemClock.elapsedRealtime() - activityCreateStartMs}ms"
                )
                revealStartupShell("first_pre_draw")
                scheduleFastStartupAvatarRefresh()
                return true
            }
        })
        binding.root.post {
            AppLog.i(STARTUP_TAG, "MainActivity first root post elapsed=${SystemClock.elapsedRealtime() - activityCreateStartMs}ms")
            revealStartupShell("first_root_post")
            scheduleDeferredStartupTasks()
            (application as? MyBLBLApplication)?.scheduleStartupFirstPagePreload(delayMillis = 0L)
            attachInitialMainTabAfterShellDraw()
            showUsageTipIfNeeded()
        }
        AppLog.i(STARTUP_TAG, "MainActivity.initData end elapsed=${SystemClock.elapsedRealtime() - startMs}ms")
    }

    private fun resolveDefaultMainTabIndex(): Int {
        return if (appSettings.getCachedString("default_start_page") == "动态") {
            2
        } else {
            0
        }
    }

    private fun attachInitialMainTabAfterShellDraw() {
        val index = pendingInitialTabIndex
        if (index !in fragments.indices) return
        pendingInitialTabIndex = -1
        val startMs = SystemClock.elapsedRealtime()
        AppLog.i(STARTUP_TAG, "STARTUP attachInitialMainTab start index=$index elapsed=${startMs - activityCreateStartMs}ms")
        showFragment(index)
        postTabSelectedEvent(index)
        AppLog.i(STARTUP_TAG, "STARTUP attachInitialMainTab end index=$index elapsed=${SystemClock.elapsedRealtime() - startMs}ms")
    }

    private fun restoreUiStateAfterRecreation() {
        val resolvedTabIndex = when {
            restoredTabIndex in fragments.indices -> restoredTabIndex
            else -> inferCurrentMainTabIndex()
        }
        if (resolvedTabIndex in fragments.indices) {
            currentFragmentIndex = resolvedTabIndex
            binding.myTabView.restoreTabHighlight(resolvedTabIndex)
        } else {
            pendingInitialTabIndex = 0
            binding.myTabView.restoreTabHighlight(0)
        }
    }

    private fun inferCurrentMainTabIndex(): Int {
        val visibleMainIndex = fragments.indices.firstOrNull { index ->
            supportFragmentManager.findFragmentByTag("fragment_$index")?.isVisible == true
        }
        if (visibleMainIndex != null) {
            return visibleMainIndex
        }
        return fragments.indices.lastOrNull { index ->
            supportFragmentManager.findFragmentByTag("fragment_$index")?.isAdded == true
        } ?: -1
    }

    private fun applyBackgroundImage() {
        val themeIndex = appSettings.getCachedInt("theme", 1)
        if (themeIndex == 3) {
            binding.mainBackgroundImage.apply {
                setBackgroundResource(R.drawable.background_image)
                visibility = View.VISIBLE
            }
        } else {
            binding.mainBackgroundImage.apply {
                background = null
                visibility = View.GONE
            }
        }
    }

    fun applyCategoryEntryVisibility() {
        val enabled = ContentFilter.isMinorProtectionEnabled(this)
        binding.myTabView.setCategoryButtonVisible(!enabled)
        if (enabled && currentFragmentIndex == 1) {
            binding.myTabView.selectTab(0)
        }
    }

    fun applyLiveEntryVisibility() {
        val liveEntry = appSettings.getCachedString("live_entry", "关") ?: "关"
        val show = liveEntry == "开"
        binding.myTabView.setLiveButtonVisible(show)
        if (!show && currentFragmentIndex == 3) {
            binding.myTabView.selectTab(0)
        }
    }

    private fun initFragments() {
        // 不再在这里同步 new 6 个 Fragment，全部走 [getOrCreateFragment] 按需 lazy 构造。
        // savedInstanceState 恢复路径下，FragmentManager 自带 restoredFragment，下面 showFragment
        // 里的 findFragmentByTag 会优先命中，不会触发 factory。
    }

    private fun getOrCreateFragment(index: Int): Fragment {
        return fragments[index] ?: fragmentFactories[index]().also { fragments[index] = it }
    }

    private fun showFragment(index: Int) {
        if (index < 0 || index >= fragments.size) return
        if (index == 1 && !binding.myTabView.isCategoryButtonVisible()) return
        if (index == 3 && !binding.myTabView.isLiveButtonVisible()) return
        if (currentFragmentIndex == index) return

        val fragmentTag = "fragment_$index"
        val previousIndex = currentFragmentIndex
        val currentFragment = supportFragmentManager.findFragmentByTag("fragment_$previousIndex")
        val targetFragment = supportFragmentManager.findFragmentByTag(fragmentTag)
            ?: getOrCreateFragment(index)

        supportFragmentManager.commit {
            setReorderingAllowed(true)

            if (currentFragment != null) {
                hide(currentFragment)
            }

            if (!targetFragment.isAdded) {
                add(R.id.container, targetFragment, fragmentTag)
            } else {
                show(targetFragment)
            }
        }

        currentFragmentIndex = index
        mainNavigationViewModel.onTabSelected(index)
    }

    override fun onTabSelected(index: Int) {
        if (supportFragmentManager.backStackEntryCount > 0) {
            focusRestoreAnchors.clear()
            supportFragmentManager.popBackStackImmediate(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
        }
        showFragment(index)
        postTabSelectedEvent(index)
    }

    override fun onTabReselected(index: Int) {
        postTabClickEvent(index)
    }

    override fun onTabNavigateRight(index: Int): Boolean {
        if (index !in fragments.indices) {
            return false
        }
        val anchorView = currentFocus
        val handled = focusCurrentMainContent(anchorView, preferSpatialEntry = true)
        return true
    }

    override fun onSearchClick() {
        val anchorView = currentFocus ?: binding.myTabView
        if (supportFragmentManager.backStackEntryCount > 0) {
            focusRestoreAnchors.clear()
            supportFragmentManager.popBackStackImmediate(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
        }
        showFragment(SEARCH_TAB_INDEX)
        if (currentFragmentIndex == SEARCH_TAB_INDEX) {
            postTabSelectedEvent(SEARCH_TAB_INDEX)
            binding.root.post {
                focusCurrentMainContent(anchorView = anchorView, preferSpatialEntry = false)
            }
        }
    }

    fun openSearch(keyword: String? = null) {
        binding.myTabView.selectTab(SEARCH_TAB_INDEX)
        keyword
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { normalizedKeyword ->
                val searchFragment = supportFragmentManager
                    .findFragmentByTag("fragment_$SEARCH_TAB_INDEX") as? SearchNewFragment
                    ?: getOrCreateFragment(SEARCH_TAB_INDEX) as? SearchNewFragment
                binding.root.post {
                    searchFragment?.openKeyword(normalizedKeyword)
                }
            }
    }

    override fun onSettingClick() {
        if (isOverlayVisible(SETTINGS_OVERLAY_TAG)) {
            return
        }
        openOverlayFragment(SettingsFragment.newInstance(), SETTINGS_OVERLAY_TAG)
    }

    override fun onAvatarClick() {
        if (!sessionGateway.isLoggedIn()) {
            openOverlayFragment(SignInFragment.newInstance(), "sign_in")
            return
        }
        showUserInfoDialog()
    }

    private fun revealStartupShell(reason: String) {
        if (startupShellRevealed) return
        startupShellRevealed = true
        AppLog.i(
            STARTUP_TAG,
            "STARTUP T3 revealShell reason=$reason elapsed=${SystemClock.elapsedRealtime() - activityCreateStartMs}ms"
        )
        window.setBackgroundDrawableResource(R.color.systemBackgroundColor)
    }

    private fun onHomeContentReady() {
        if (!homeContentReadyLogged) {
            homeContentReadyLogged = true
            AppLog.i(
                STARTUP_TAG,
                "STARTUP T4 homeContentReady elapsed=${SystemClock.elapsedRealtime() - activityCreateStartMs}ms"
            )
        }
        revealStartupShell("home_content_ready")
        schedulePostHomeAvatarRefresh()
    }

    private fun scheduleSplashTimeout() {
        lifecycleScope.launch {
            delay(SPLASH_TIMEOUT_MS)
            if (!startupShellRevealed) {
                AppLog.i(STARTUP_TAG, "Splash shell reveal timeout fallback")
                revealStartupShell("timeout")
            }
        }
    }

    private fun refreshAvatar(
        allowNetworkFetch: Boolean = true,
        forceNetworkFetch: Boolean = false
    ) {
        if (!sessionGateway.isLoggedIn()) {
            avatarRefreshJob?.cancel()
            binding.myTabView.setAvatarUrl(null)
            binding.myTabView.setAvatarBadge(officialVerifyType = -1)
            return
        }

        val cachedInfo = sessionGateway.getUserInfo()
        if (!cachedInfo?.face.isNullOrBlank()) {
            binding.myTabView.setAvatarUrl(cachedInfo?.face)
            setTabBarBadge(cachedInfo)
            if (!forceNetworkFetch) return
        }

        if (!allowNetworkFetch) return

        if (avatarRefreshJob?.isActive == true) return
        avatarRefreshJob = lifecycleScope.launch {
            val startMs = SystemClock.elapsedRealtime()
            AppLog.i(STARTUP_TAG, "STARTUP avatarRefresh start force=$forceNetworkFetch")
            val refreshed = userRepository.refreshCurrentUserInfo().getOrNull()
            if (sessionGateway.isLoggedIn()) {
                binding.myTabView.setAvatarUrl(refreshed?.face)
                setTabBarBadge(refreshed)
            }
            AppLog.i(
                STARTUP_TAG,
                "STARTUP avatarRefresh end elapsed=${SystemClock.elapsedRealtime() - startMs}ms hasFace=${!refreshed?.face.isNullOrBlank()}"
            )
        }
    }

    private fun scheduleFastStartupAvatarRefresh() {
        if (startupAvatarRefreshScheduled) return
        startupAvatarRefreshScheduled = true
        binding.root.postDelayed({
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    (application as? MyBLBLApplication)?.ensureSessionRuntimeReady("startupAvatarCache")
                }
                refreshAvatar(allowNetworkFetch = false)
            }
        }, 300L)
    }

    private fun schedulePostHomeAvatarRefresh() {
        if (postHomeAvatarRefreshScheduled) return
        postHomeAvatarRefreshScheduled = true
        binding.root.postDelayed({
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    (application as? MyBLBLApplication)?.ensureDataRuntimeReady("postHomeAvatarNetwork")
                }
                refreshAvatar(allowNetworkFetch = true, forceNetworkFetch = true)
            }
        }, 250L)
    }

    private fun setTabBarBadge(info: UserDetailInfoModel?) {
        if (info == null) {
            binding.myTabView.setAvatarBadge(officialVerifyType = -1)
            return
        }
        val oType = info.officialVerify?.type ?: info.official?.let { if (it.role > 0) it.type else -1 } ?: -1
        val vStatus = info.vipStatus.coerceAtLeast(info.vip?.vipStatus ?: 0)
        val vType = info.vipType.coerceAtLeast(info.vip?.vipType ?: 0)
        binding.myTabView.setAvatarBadge(
            officialVerifyType = oType,
            vipStatus = vStatus,
            vipType = vType
        )
    }

    private fun scheduleDeferredStartupTasks() {
        if (startupTasksScheduled) {
            return
        }
        startupTasksScheduled = true
        AppStartupScheduler()
            .addTask("refreshAvatar", AppStartupScheduler.Phase.DELAYED, delayMs = 4000L) {
                lifecycleScope.launch {
                    if (postHomeAvatarRefreshScheduled) {
                        return@launch
                    }
                    withContext(Dispatchers.IO) {
                        (application as? MyBLBLApplication)?.ensureDataRuntimeReady("refreshAvatar")
                    }
                    refreshAvatar(allowNetworkFetch = true)
                }
            }
            .addTask("imageCdnPrewarm", AppStartupScheduler.Phase.DELAYED, delayMs = 2500L) {
                ImageLoader.prewarmCdn()
            }
            .addTask("playerPrewarm", AppStartupScheduler.Phase.DELAYED, delayMs = 10000L) {
                PlayerInstancePool.prewarm(this@MainActivity)
            }
            .addTask("sessionPrewarm", AppStartupScheduler.Phase.DELAYED, delayMs = SESSION_PREWARM_DELAY_MS) {
                MyBLBLApplication.instance.scheduleDeferredSessionPrewarm(delayMillis = 0L)
            }
            .addTask("wbiKeys", AppStartupScheduler.Phase.IDLE) {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        (application as? MyBLBLApplication)?.ensureDataRuntimeReady("wbiKeys")
                    }
                    runCatching { sessionGateway.ensureWbiKeys() }
                }
            }
            .execute()
    }

    fun focusLeftFunctionArea(sourceView: View? = currentFocus): Boolean {
        if (binding.myTabView.visibility != View.VISIBLE) {
            return false
        }
        return binding.myTabView.focusNearestButtonTo(sourceView)
    }

    fun showTabBar(show: Boolean) {
        binding.myTabView.visibility = if (show) View.VISIBLE else View.GONE
        binding.divide.visibility = if (show) View.VISIBLE else View.GONE
    }

    fun animateTabBar(show: Boolean) {
        if (show) {
            binding.myTabView.animate()
                .translationX(0f)
                .setDuration(200)
                .start()
        } else {
            binding.myTabView.animate()
                .translationX(-binding.myTabView.width.toFloat())
                .setDuration(200)
                .start()
        }
    }

    private fun handleBackPressed() {
        if (dispatchBackPressedToVisibleFragment()) {
            return
        }

        if (supportFragmentManager.backStackEntryCount > 0) {
            pendingFocusRestoreDelayMs =
                if (isOverlayVisible(SETTINGS_OVERLAY_TAG)) SETTINGS_OVERLAY_EXIT_ANIM_MS else 0L
            supportFragmentManager.popBackStack()
            return
        }

        mainNavigationViewModel.dispatch(MainNavigationViewModel.Event.BackPressed)

        if (System.currentTimeMillis() - exitTime <= exitInterval) {
            finish()
            return
        }

        exitTime = System.currentTimeMillis()
        Toast.makeText(applicationContext, R.string.app_exit, Toast.LENGTH_SHORT).show()
    }

    fun closeTopOverlayFromUi() {
        if (supportFragmentManager.backStackEntryCount > 0) {
            pendingFocusRestoreDelayMs =
                if (isOverlayVisible(SETTINGS_OVERLAY_TAG)) SETTINGS_OVERLAY_EXIT_ANIM_MS else 0L
            supportFragmentManager.popBackStack()
            return
        }
        handleBackPressed()
    }

    private fun dispatchBackPressedToVisibleFragment(): Boolean {
        val topFragment = supportFragmentManager.fragments
            .asReversed()
            .firstOrNull { it.isVisible }
            ?: return false
        return topFragment.findBackPressedHandler()?.onBackPressed() == true
    }

    private fun Fragment.findBackPressedHandler(): OnBackPressedHandler? {
        childFragmentManager.fragments
            .asReversed()
            .firstOrNull { it.isVisible }
            ?.findBackPressedHandler()
            ?.let { return it }
        return this as? OnBackPressedHandler
    }

    fun openOverlayFragment(fragment: Fragment, tag: String) {
        openFragmentWithReferenceBehavior(fragment, tag, addToBackStack = true)
    }

    fun openInHostContainer(fragment: Fragment, addToBackStack: Boolean = true) {
        openFragmentWithReferenceBehavior(
            fragment = fragment,
            tag = fragment::class.java.name,
            addToBackStack = addToBackStack
        )
    }

    fun openVideoPlayer(
        launchContext: PlayerLaunchContext,
        addToBackStack: Boolean = true
    ) {
        PlayerActivity.start(
            context = this,
            aid = launchContext.aid,
            bvid = launchContext.bvid,
            cid = launchContext.cid,
            epId = launchContext.epId,
            seasonId = launchContext.seasonId,
            seekPositionMs = launchContext.seekPositionMs,
            startEpisodeIndex = launchContext.startEpisodeIndex
        )
    }

    fun openVideoPlayer(
        aid: Long = 0L,
        bvid: String = "",
        cid: Long = 0L,
        epId: Long = 0L,
        seasonId: Long = 0L,
        seekPositionMs: Long = 0L,
        startEpisodeIndex: Int = -1,
        addToBackStack: Boolean = true
    ) {
        PlayerActivity.start(
            context = this,
            aid = aid,
            bvid = bvid,
            cid = cid,
            epId = epId,
            seasonId = seasonId,
            seekPositionMs = seekPositionMs,
            startEpisodeIndex = startEpisodeIndex
        )
    }

    private fun openFragmentWithReferenceBehavior(
        fragment: Fragment,
        tag: String,
        addToBackStack: Boolean
    ) {
        if (addToBackStack) {
            currentFocus?.let { activeFocus ->
                focusRestoreAnchors.addLast(
                    FocusRestoreAnchor(
                        viewRef = WeakReference(activeFocus)
                    )
                )
            }
        }
        val isSettingsOverlay = isSettingsOverlay(fragment = fragment, tag = tag)
        val isVideoPlayerOverlay = isVideoPlayerOverlay(fragment = fragment, tag = tag)

        if (!isVideoPlayerOverlay) {
            showTabBar(false)
            supportFragmentManager.fragments
                .asReversed()
                .firstOrNull { it.isVisible }
                ?.view
                ?.clearFocus()
        }

        supportFragmentManager.commit {
            if (isSettingsOverlay) {
                setCustomAnimations(
                    R.anim.m3_side_sheet_enter_from_right,
                    0,
                    0,
                    R.anim.m3_side_sheet_exit_to_right
                )
            } else {
                setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
            }

            supportFragmentManager.fragments
                .filter { it.isAdded && it.isVisible }
                .forEach { visibleFragment ->
                    hide(visibleFragment)
                }

            add(R.id.container, fragment, tag)
            if (addToBackStack) {
                addToBackStack(tag)
            }
        }
        if (isVideoPlayerOverlay) {
            showTabBar(false)
        }
    }

    fun showUserInfoDialog() {
        if (isFinishing || isDestroyed) {
            return
        }
        UserInfoDialog(this).show()
    }

    private fun showUsageTipIfNeeded() {
        if (restoredFromSavedState) return
        if (appSettings.getCachedBoolean("usage_tip_shown")) return
        if (isFinishing || isDestroyed) return
        appSettings.putBooleanAsync("usage_tip_shown", true)
        UsageTipDialog(this).show()
    }

    private fun dispatchVideoBlockedEventToCurrentFragment(event: AppEventHub.Event.VideoBlockedByMinorProtection) {
        supportFragmentManager.fragments.forEach { fragment ->
            if (fragment is OnVideoBlockedListener) {
                (fragment as OnVideoBlockedListener).onVideoBlocked(event.aid, event.bvid)
            }
            fragment.childFragmentManager.fragments.forEach { child ->
                if (child is OnVideoBlockedListener) {
                    (child as OnVideoBlockedListener).onVideoBlocked(event.aid, event.bvid)
                }
            }
        }
    }

    interface OnVideoBlockedListener {
        fun onVideoBlocked(aid: Long, bvid: String)
    }

    private fun updateNavigationVisibility() {
        val shouldShowTabBar = supportFragmentManager.backStackEntryCount == 0
        if (shouldShowTabBar) {
            focusRestoreAnchors.clear()
        }
        showTabBar(shouldShowTabBar)
    }

    private fun restoreFocusAfterOverlayPop() {
        val anchor = focusRestoreAnchors.removeLastOrNull()
        val delayMs = pendingFocusRestoreDelayMs
        pendingFocusRestoreDelayMs = 0L
        binding.root.postDelayed({
            val target = anchor?.viewRef?.get()
            if (target?.isAttachedToWindow == true && target.isShown && target.isFocusable) {
                val result = target.requestFocus()
                if (result) {
                    return@postDelayed
                }
            }
            val handledByContent = focusCurrentMainContent(
                anchorView = target,
                preferSpatialEntry = target != null
            )
            if (handledByContent) {
                return@postDelayed
            }
            val handledByTabBar = binding.myTabView.focusCurrentTab()
            if (handledByTabBar) {
                return@postDelayed
            }
            supportFragmentManager.fragments
                .asReversed()
                .firstOrNull { it.isVisible }
                ?.view
                ?.let { visibleRoot ->
                    val currentFocus = visibleRoot.findFocus()
                    currentFocus?.requestFocus()
                }
        }, delayMs)
    }

    fun skipNextFocusRestore() {
        if (focusRestoreAnchors.isNotEmpty()) {
            focusRestoreAnchors.removeLastOrNull()
        }
    }

    private fun isSettingsOverlay(fragment: Fragment, tag: String): Boolean {
        return tag == SETTINGS_OVERLAY_TAG || fragment is SettingsFragment
    }

    private fun isVideoPlayerOverlay(fragment: Fragment, tag: String): Boolean {
        return fragment is VideoPlayerFragment || tag.startsWith("video_player:")
    }

    private fun isOverlayVisible(tag: String): Boolean {
        return supportFragmentManager.fragments
            .asReversed()
            .firstOrNull { it.isVisible }
            ?.tag == tag
    }

    private fun focusCurrentMainContent(
        anchorView: View? = currentFocus,
        preferSpatialEntry: Boolean = false
    ): Boolean {
        val currentFragment = supportFragmentManager.findFragmentByTag("fragment_$currentFragmentIndex")
        return (currentFragment as? MainTabFocusTarget)
            ?.focusEntryFromMainTab(anchorView, preferSpatialEntry) == true
    }

    private fun shouldFinishDuplicateLauncherLaunch(): Boolean {
        val launchIntent = intent
        return !isTaskRoot &&
            launchIntent?.action == android.content.Intent.ACTION_MAIN &&
            launchIntent.hasCategory(android.content.Intent.CATEGORY_LAUNCHER)
    }

    private fun postTabClickEvent(index: Int) {
        mainNavigationViewModel.dispatch(MainNavigationViewModel.Event.MainTabReselected(index))
    }

    private fun postTabSelectedEvent(index: Int) {
        mainNavigationViewModel.dispatch(MainNavigationViewModel.Event.MainTabSelected(index))
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (event?.action == android.view.KeyEvent.ACTION_DOWN && keyCode == android.view.KeyEvent.KEYCODE_MENU) {
            mainNavigationViewModel.dispatch(MainNavigationViewModel.Event.MenuPressed)
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        mainNavigationViewModel.onTabSelected(currentFragmentIndex)
        super.onSaveInstanceState(outState)
    }
}
