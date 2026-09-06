package com.tutu.myblbl.ui.activity

import android.os.Bundle
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import android.view.ViewTreeObserver
import androidx.recyclerview.widget.RecyclerView
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
import com.tutu.myblbl.network.NetworkManager
import com.tutu.myblbl.network.session.NetworkSessionGateway
import com.tutu.myblbl.repository.UserRepository
import com.tutu.myblbl.core.ui.base.BaseActivity
import com.tutu.myblbl.core.ui.base.OnBackPressedHandler
import com.tutu.myblbl.model.user.UserDetailInfoModel
import com.tutu.myblbl.feature.category.CategoryFragment
import com.tutu.myblbl.feature.cctv.CctvLiveFragment
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
import com.tutu.myblbl.feature.player.VideoPlayerFragment
import com.tutu.myblbl.core.common.log.AppLog
import com.tutu.myblbl.core.common.net.NetworkRecoveryMonitor
import com.tutu.myblbl.core.ui.navigation.TabBarView
import com.tutu.myblbl.core.common.content.ContentFilter
import com.tutu.myblbl.core.startup.AppStartupScheduler
import com.tutu.myblbl.core.ui.image.ImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
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
        private const val CCTV_TAB_INDEX = 4
        private const val SEARCH_TAB_INDEX = 6
        private const val STARTUP_TAG = "AppStartup"
        private const val TEEN_REST_DIAG_TAG = "TeenRestDiag"
    }

    /** 青少年模式：休息遮罩倒计时 Handler，每秒刷新剩余时间。 */
    private val teenRestHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val teenRestTicker = object : Runnable {
        override fun run() {
            updateTeenRestCountdown()
            // 还有剩余时间才继续，否则 updateTeenRestCountdown 内部会撤掉遮罩并停止
            if (binding.teenRestOverlay.visibility == View.VISIBLE) {
                teenRestHandler.postDelayed(this, 1000L)
            }
        }
    }

    // tab 顺序固定（与 fragmentFactories 一一对应）：
    // 0 推荐 / 1 分类 / 2 动态 / 3 直播 / 4 CCTV直播 / 5 我的 / 6 搜索。
    // 用 lazy factory 让 4 个非首屏 tab 真正点中时再构造，省去 1.1s 的 onCreate 大段时间。
    private val fragmentFactories: List<() -> Fragment> = listOf(
        { HomeFragment.newInstance() },
        { CategoryFragment.newInstance() },
        { DynamicFragment.newInstance() },
        { LiveFragment.newInstance() },
        { CctvLiveFragment.newInstance() },
        { MeFragment.newInstance() },
        { SearchNewFragment.newInstance() }
    )
    private val fragments: MutableList<Fragment?> = MutableList(fragmentFactories.size) { null }
    private val appEventHub: AppEventHub by inject()
    private val mainNavigationViewModel: MainNavigationViewModel by viewModels()
    private val sessionGateway: NetworkSessionGateway by inject()
    private val userRepository: UserRepository by inject()
    private val personalFeedPrewarmer: com.tutu.myblbl.repository.PersonalFeedPrewarmer by inject()
    private var currentFragmentIndex = -1
    private var exitTime: Long = 0
    private val exitInterval = 2000L
    private val focusRestoreAnchors = ArrayDeque<FocusRestoreAnchor>()
    private var lastBackStackEntryCount = 0
    private var pendingFocusRestoreDelayMs = 0L
    private var startupTasksScheduled = false
    private var startupShellRevealed = false
    private var startupInitialContentAttached = false
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
        (application as? MyBLBLApplication)?.ensureSessionRuntimeReady("MainActivity.onCreate/sessionState")
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
                coroutineScope {
                    launch {
                        // 订阅会话单一状态源：登录态变化（含停止期间错过的跳变）自动刷新头像，
                        // 并在登录/登出跳变时补广播给各页面
                        refreshAvatar(allowNetworkFetch = false)
                        sessionGateway.sessionState.collect { state ->
                            val loggedIn = state.isLoggedIn
                            if (loggedIn != lastKnownLoggedIn) {
                                lastKnownLoggedIn = loggedIn
                                appEventHub.dispatch(AppEventHub.Event.UserSessionChanged)
                            }
                            refreshAvatar(allowNetworkFetch = false)
                        }
                    }
                    launch {
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
            }
        }
        AppLog.i(STARTUP_TAG, "STARTUP T1c MainActivity.onCreate end elapsed=${SystemClock.elapsedRealtime() - createStartMs}ms totalFromActivityMs=${SystemClock.elapsedRealtime() - activityCreateStartMs}ms")
    }

    override fun initView() {
        val t0 = SystemClock.elapsedRealtime()
        initFragments()
        binding.myTabView.setOnTabClickListener(this)
        binding.root.viewTreeObserver.addOnGlobalFocusChangeListener { old, new ->
            AppLog.d(
                "FocusTrace",
                "globalFocusChange: old=${old?.javaClass?.simpleName}#${old?.id} " +
                    "new=${new?.javaClass?.simpleName}#${new?.id} " +
                    "inTabBar=${new?.let { isInsideTabBar(it) } ?: false}"
            )
        }
        NetworkRecoveryMonitor.start(this) {
            appEventHub.dispatch(AppEventHub.Event.NetworkRecovered)
        }
        applyBackgroundImage()
        applyCategoryEntryVisibility()
        applyLiveEntryVisibility()
        applyCctvLiveEntryVisibility()
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
                // 壳首帧已提交，立刻在 IO 线程预热网络并发出推荐首页请求。
                // 首页 Fragment 要再过两帧才会 loadInitial，loadSharedFirstPage 会直接
                // await 这条 in-flight 请求，500ms 级的网络 RTT 与 Fragment 创建并行。
                // （133b814a 壳层先行重构时误删了此调用，导致首屏请求完全串行在 UI 后面。）
                (application as? MyBLBLApplication)?.scheduleStartupFirstPagePreload(delayMillis = 0L)
                Choreographer.getInstance().postFrameCallback {
                    binding.root.post {
                        attachInitialContentAfterShellDraw()
                    }
                }
                return true
            }
        })
        // 壳层先出第一帧；首页 Fragment、网络预加载和提示弹窗下一帧再进入。
        // 这样启动页只覆盖到主壳可绘制，不再被首页 ViewPager/列表创建拖住。
        revealStartupShell("shell_initialized")
        binding.root.post {
            AppLog.i(STARTUP_TAG, "MainActivity first root post elapsed=${SystemClock.elapsedRealtime() - activityCreateStartMs}ms")
            revealStartupShell("first_root_post")
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

    private fun attachInitialContentAfterShellDraw() {
        if (startupInitialContentAttached) return
        startupInitialContentAttached = true
        val startMs = SystemClock.elapsedRealtime()
        AppLog.i(
            STARTUP_TAG,
            "STARTUP initialContentPipeline start reason=after_shell_first_frame elapsed=${startMs - activityCreateStartMs}ms"
        )
        if (!restoredFromSavedState) {
            attachInitialMainTabAfterShellDraw()
        }
        scheduleDeferredStartupTasks()
        showUsageTipIfNeeded()
        AppLog.i(
            STARTUP_TAG,
            "STARTUP initialContentPipeline end reason=after_shell_first_frame elapsed=${SystemClock.elapsedRealtime() - startMs}ms"
        )
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

    fun applyCctvLiveEntryVisibility() {
        val cctvLiveEntry = appSettings.getCachedString("cctv_live_entry", "关") ?: "关"
        val show = cctvLiveEntry == "开"
        binding.myTabView.setCctvLiveButtonVisible(show)
        if (!show && currentFragmentIndex == CCTV_TAB_INDEX) {
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
        if (index == CCTV_TAB_INDEX && !binding.myTabView.isCctvLiveButtonVisible()) return
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
        AppLog.d("FocusTrace", "showFragment done index=$index focusedAfter=${currentFocus?.javaClass?.simpleName}#${currentFocus?.id}")
    }

    private fun isInsideTabBar(view: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current === binding.myTabView) return true
            current = current.parent as? View
        }
        return false
    }

    override fun onTabSelected(index: Int) {
        AppLog.d("FocusTrace", "onTabSelected index=$index focusedBefore=${currentFocus?.javaClass?.simpleName}#${currentFocus?.id}")
        // CCTV 直播 tab：直接进 Marmot 播放器，不加载频道列表 Fragment（对标参考 StartActivity→LiveActivity）
        if (index == CCTV_TAB_INDEX) {
            MarmotLiveActivity.start(this)
            // 焦点留在 TV 直播按钮（直播返回后焦点仍在该按钮）
            binding.myTabView.focusCurrentTab()
            return
        }
        if (supportFragmentManager.backStackEntryCount > 0) {
            focusRestoreAnchors.clear()
            supportFragmentManager.popBackStackImmediate(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
        }
        showFragment(index)
        postTabSelectedEvent(index)
    }

    override fun onTabReselected(index: Int) {
        AppLog.d("FocusTrace", "onTabReselected index=$index focusedBefore=${currentFocus?.javaClass?.simpleName}#${currentFocus?.id}")
        // CCTV 直播 tab 再次点击：重新进入直播（Tab 4 不持有 Fragment，reselect 也要能进直播）
        if (index == CCTV_TAB_INDEX) {
            MarmotLiveActivity.start(this)
            return
        }
        // 其他侧边栏按钮再次点击，不触发刷新，避免抢走焦点
    }

    override fun onTabNavigateRight(index: Int): Boolean {
        if (index !in fragments.indices) {
            return false
        }
        focusCurrentMainContent(currentFocus, preferSpatialEntry = true)
        return true
    }

    override fun onSideButtonNavigateRight(): Boolean {
        val anchorView = currentFocus
        focusCurrentMainContent(anchorView, preferSpatialEntry = true)
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

    override fun onSettingClick() {
        if (isSettingsOverlayVisible()) {
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
            binding.myTabView.setAvatarUrl(cachedInfo.face)
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
            // 个人数据预取：历史/稍后观看/收藏夹列表都是点进 tab 才发请求，真机 RTT 下
            // 用户要干等整个网络往返。首屏稳定后（1.8s）后台预取第一页，进"我的"直接渲染。
            .addTask("personalFeedPrewarm", AppStartupScheduler.Phase.DELAYED, delayMs = 1800L) {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        (application as? MyBLBLApplication)?.ensureDataRuntimeReady("personalFeedPrewarm")
                    }
                    if (sessionGateway.isLoggedIn()) {
                        personalFeedPrewarmer.prewarm()
                    }
                }
            }
            // player 与 security 预热放 IDLE 阶段：首页 firstPreDraw 后的空闲间隙立即触发，
            // 远早于 DELAYED 3-3.5s。实测用户常在启动后 3s 左右点视频，DELAYED 3s/3.5s 会
            // 导致预热赶不上首播（security 仍串行阻塞 playinfo ~120ms，player 仍冷建 ~56ms）。
            // IDLE 在主线程空闲时跑，不抢首页渲染。
            .addTask("playerPrewarm", AppStartupScheduler.Phase.IDLE) {
                PlayerInstancePool.prewarm(this@MainActivity)
            }
            .addTask("securityPrewarm", AppStartupScheduler.Phase.IDLE) {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        runCatching { NetworkManager.ensureHealthyForPlay() }
                    }
                }
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

    private fun handleBackPressed() {
        if (dispatchBackPressedToVisibleFragment()) {
            schedulePostBackFocusRestore()
            return
        }

        if (supportFragmentManager.backStackEntryCount > 0) {
            pendingFocusRestoreDelayMs =
                if (isSettingsOverlayVisible()) SETTINGS_OVERLAY_EXIT_ANIM_MS else 0L
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
        schedulePostBackFocusRestore()
    }

    private fun schedulePostBackFocusRestore() {
        binding.root.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            val currentFocus = currentFocus
            if (currentFocus != null && currentFocus.isAttachedToWindow && currentFocus.isShown && currentFocus.isFocusable) {
                return@postDelayed
            }
            val handledByContent = focusCurrentMainContent(anchorView = null, preferSpatialEntry = false)
            if (!handledByContent) {
                binding.myTabView.focusCurrentTab()
            }
        }, 100L)
    }

    fun closeTopOverlayFromUi() {
        if (supportFragmentManager.backStackEntryCount > 0) {
            pendingFocusRestoreDelayMs =
                if (isSettingsOverlayVisible()) SETTINGS_OVERLAY_EXIT_ANIM_MS else 0L
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

    private fun isSettingsOverlayVisible(): Boolean {
        return supportFragmentManager.fragments
            .asReversed()
            .firstOrNull { it.isVisible }
            ?.tag == SETTINGS_OVERLAY_TAG
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

    private fun postTabSelectedEvent(index: Int) {
        mainNavigationViewModel.dispatch(MainNavigationViewModel.Event.MainTabSelected(index))
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        // 青少年模式：休息遮罩可见时，拦截除返回键外的所有按键，防止操作背后内容
        if (binding.teenRestOverlay.visibility == View.VISIBLE &&
            keyCode != android.view.KeyEvent.KEYCODE_BACK
        ) {
            return true
        }
        if (event?.action == android.view.KeyEvent.ACTION_DOWN && keyCode == android.view.KeyEvent.KEYCODE_MENU) {
            val focused = currentFocus
            if (focused != null && isInsideRecyclerView(focused)) {
                mainNavigationViewModel.dispatch(MainNavigationViewModel.Event.MenuPressed)
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun isInsideRecyclerView(view: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current is RecyclerView) return true
            current = current.parent as? View
        }
        return false
    }

    override fun onSaveInstanceState(outState: Bundle) {
        mainNavigationViewModel.onTabSelected(currentFragmentIndex)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        AppLog.i(TEEN_REST_DIAG_TAG, "onResume start")
        super.onResume()
        AppLog.i(TEEN_REST_DIAG_TAG, "onResume after super, before refresh")
        refreshTeenRestOverlay()
    }

    override fun onPause() {
        super.onPause()
        teenRestHandler.removeCallbacks(teenRestTicker)
        AppLog.i(TEEN_REST_DIAG_TAG, "onPause")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        AppLog.i(TEEN_REST_DIAG_TAG, "onWindowFocusChanged hasFocus=$hasFocus")
        dumpTeenRestOverlayState("onWindowFocusChanged($hasFocus)")
    }

    /** 检查青少年模式休息状态：休息中显示遮罩并启动倒计时，否则确保遮罩隐藏。 */
    private fun refreshTeenRestOverlay() {
        val resting = com.tutu.myblbl.core.common.content.TeenModeTimer.isResting()
        AppLog.i(TEEN_REST_DIAG_TAG, "refreshTeenRestOverlay isResting=$resting")
        if (resting) {
            showTeenRestOverlay()
        } else {
            hideTeenRestOverlay()
        }
    }

    private fun showTeenRestOverlay() {
        binding.teenRestOverlay.visibility = View.VISIBLE
        updateTeenRestCountdown()
        teenRestHandler.removeCallbacks(teenRestTicker)
        teenRestHandler.postDelayed(teenRestTicker, 1000L)
        // 延迟一帧后再 dump 一次，看 measure/layout 后的真实尺寸（排除未触发布局）
        binding.teenRestOverlay.post { dumpTeenRestOverlayState("showTeenRestOverlay+postFrame") }
        dumpTeenRestOverlayState("showTeenRestOverlay")
    }

    private fun hideTeenRestOverlay() {
        binding.teenRestOverlay.visibility = View.GONE
        teenRestHandler.removeCallbacks(teenRestTicker)
        dumpTeenRestOverlayState("hideTeenRestOverlay")
    }

    /** 用墙钟算剩余时间并刷新文字，到期则撤掉遮罩。 */
    private fun updateTeenRestCountdown() {
        val restStart = com.tutu.myblbl.core.common.content.TeenModeTimer.getRestStartMs()
        val restLimitMs = com.tutu.myblbl.core.common.content.TeenModeTimer.getRestLimitMs()
        if (restStart <= 0L || restLimitMs <= 0L) {
            AppLog.i(TEEN_REST_DIAG_TAG, "updateTeenRestCountdown early-return: restStart=$restStart restLimitMs=$restLimitMs")
            hideTeenRestOverlay()
            return
        }
        val remainingMs = restLimitMs - (System.currentTimeMillis() - restStart)
        if (remainingMs <= 0L) {
            // 休息到期：撤掉遮罩（isResting 下次调用会自动清零累计）
            AppLog.i(TEEN_REST_DIAG_TAG, "updateTeenRestCountdown rest expired, remainingMs=$remainingMs")
            hideTeenRestOverlay()
            return
        }
        val totalSec = (remainingMs / 1000L).toInt()
        val min = totalSec / 60
        val sec = totalSec % 60
        binding.teenRestCountdown.text = getString(R.string.teen_rest_countdown, min, sec)
    }

    /**
     * 诊断专用：dump 遮罩及其两个 TextView 的可见性/尺寸/绘制状态，以及它在 DecorView 中的层级。
     * 只读，不改动任何状态。用于定位"纯黑屏、倒计时不显示"问题。
     */
    private fun dumpTeenRestOverlayState(reason: String) {
        try {
            val overlay = binding.teenRestOverlay
            val title = binding.teenRestTitle
            val countdown = binding.teenRestCountdown
            val sb = StringBuilder()
            sb.append("dump[$reason]: ")
            sb.append("overlay{vis=").append(visibilityName(overlay.visibility))
                .append(",shown=").append(overlay.isShown)
                .append(",w=").append(overlay.width).append(",h=").append(overlay.height)
                .append(",alpha=").append(overlay.alpha)
                .append(",bg=").append(overlay.background?.javaClass?.simpleName)
                .append(",layer=").append(findViewLayerIndex(overlay)).append("}")
            sb.append(" title{vis=").append(visibilityName(title.visibility))
                .append(",shown=").append(title.isShown)
                .append(",w=").append(title.width).append(",h=").append(title.height)
                .append(",text=").append(title.text).append("}")
            sb.append(" countdown{vis=").append(visibilityName(countdown.visibility))
                .append(",shown=").append(countdown.isShown)
                .append(",w=").append(countdown.width).append(",h=").append(countdown.height)
                .append(",text=").append(countdown.text).append("}")
            // window 层状态
            val decor = window.decorView
            sb.append(" decor{childCount=").append((decor as? android.view.ViewGroup)?.childCount ?: -1)
                .append(",decorHasFocus=").append(decor.hasWindowFocus())
                .append(",actionBarShown=").append(supportActionBar?.isShowing ?: "null").append("}")
            AppLog.i(TEEN_REST_DIAG_TAG, sb.toString())
        } catch (t: Throwable) {
            AppLog.e(TEEN_REST_DIAG_TAG, "dump failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun visibilityName(v: Int): String = when (v) {
        View.VISIBLE -> "VISIBLE"
        View.INVISIBLE -> "INVISIBLE"
        View.GONE -> "GONE"
        else -> v.toString()
    }

    /** 返回 overlay 在 DecorView 树中"自顶向下"的 z 序索引（0=最底层）。值越大越靠上层。 */
    private fun findViewLayerIndex(target: View): Int {
        val decor = window.decorView
        val flat = ArrayList<View>()
        flattenDescendants(decor as? android.view.ViewGroup, flat)
        flat.forEachIndexed { idx, v -> if (v === target) return idx }
        return -1
    }

    private fun flattenDescendants(group: android.view.ViewGroup?, out: ArrayList<View>) {
        if (group == null) return
        for (i in 0 until group.childCount) {
            val c = group.getChildAt(i)
            out.add(c)
            if (c is android.view.ViewGroup) flattenDescendants(c, out)
        }
    }
}
