package com.mytvb.core.ui.base

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.mytvb.MyBLBLApplication
import com.mytvb.R
import com.mytvb.core.common.settings.AppSettingsDataStore
import androidx.viewbinding.ViewBinding
import org.koin.android.ext.android.inject

abstract class BaseActivity<VB : ViewBinding> : AppCompatActivity() {

    protected lateinit var binding: VB
    private var initialFullscreenModeDeferred = false

    abstract fun getViewBinding(): VB

    protected val appSettings: AppSettingsDataStore by inject()

    override fun attachBaseContext(newBase: Context) {
        // 首个 Activity 此刻 Koin 尚未启动，refresh 只能发生在 onCreate，
        // 此处先用缓存档位把 density 钉住，保证首个 inflate 就走正确倍率
        UiScale.apply(newBase.resources)
        super.attachBaseContext(newBase)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        (application as? MyBLBLApplication)?.ensureUiRuntimeReady("${this::class.java.simpleName}.onCreate")
        // 必须在 super.onCreate 之前安装：AppCompat 检测到已有 Factory 会跳过自装
        UiTextScale.refresh(appSettings)
        UiScale.refresh(
            appSettings,
            resources.displayMetrics.widthPixels,
            resources.displayMetrics.heightPixels
        )
        UiScale.apply(resources)
        // 自绘卡片等走 applicationContext 的 Resources，recreate 后须同步钉到新档位
        application.resources?.let { UiScale.apply(it) }
        UiCardSize.refresh(appSettings)
        UiTextScaleFactory.install(layoutInflater)
        // `LayoutInflater.from(this)` 是 ContextThemeWrapper 缓存的那份 inflater
        // （RecyclerView item、Dialog 布局等走它），与 layoutInflater 是两个实例，
        // 需单独装密度守卫，避免系统重置密度后这些 inflate 的页面放大
        UiTextScaleFactory.installDensityGuard(LayoutInflater.from(this))
        applyTheme()
        super.onCreate(savedInstanceState)
        configureWindowChrome()
        if (deferInitialFullscreenMode()) {
            initialFullscreenModeDeferred = true
        } else {
            applyFullscreenMode()
        }
        binding = getViewBinding()
        setContentView(binding.root)
        initView()
        initData()
        initObserver()
        if (initialFullscreenModeDeferred) {
            applyFullscreenModeAfterFirstDraw()
        }
    }

    override fun onResume() {
        super.onResume()
        // 栈下旧 Activity 恢复时按当前档位重钉（设置变更后返回时保持全栈一致）
        UiScale.apply(resources)
        if (!initialFullscreenModeDeferred) {
            applyFullscreenMode()
        }
    }

    /**
     * 声明 configChanges 的 Activity 在系统配置变化（系统栏显隐、方向/窗口尺寸切换）时
     * 不重建，但系统会用真实显示指标重置 DisplayMetrics，把钉住的 density 抹掉；
     * 在回调末尾补钉，否则此后新 inflate 的页面（如设置页）会整体放大数倍。
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        UiScale.apply(resources)
        application.resources?.let { UiScale.apply(it) }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            // 窗口重新获得焦点（含系统栏显隐、从播放页返回）后再补一次，缩短密度错误窗口
            UiScale.apply(resources)
            if (!initialFullscreenModeDeferred) {
                applyFullscreenMode()
            }
        }
    }

    open fun initView() {}
    open fun initData() {}
    open fun initObserver() {}
    protected open fun deferInitialFullscreenMode(): Boolean = false

    open fun applyTheme() {
        val themeIndex = appSettings.getCachedInt("theme", 1)
        setTheme(themeIndexToResId(themeIndex))
    }

    companion object {
        /**
         * 主题 index → Theme resId 映射。Application 阶段的预 inflate 也用它，避免
         * 用错 theme 导致 ?attr/xxx 解析不到。
         */
        fun themeIndexToResId(themeIndex: Int): Int {
            return when (themeIndex) {
                0 -> R.style.DarkTheme
                1 -> R.style.DarkTheme
                2 -> R.style.WhiteTheme
                3 -> R.style.ClassicsTheme
                4 -> R.style.PinkTheme
                5 -> R.style.BlueTheme
                6 -> R.style.PurpleTheme
                7 -> R.style.RedTheme
                else -> R.style.DarkTheme
            }
        }
    }

    private fun configureWindowChrome() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
    }

    private fun applyFullscreenModeAfterFirstDraw() {
        val root = binding.root
        root.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (root.viewTreeObserver.isAlive) {
                    root.viewTreeObserver.removeOnPreDrawListener(this)
                }
                root.post {
                    initialFullscreenModeDeferred = false
                    applyFullscreenMode()
                }
                return true
            }
        })
    }

    private fun applyFullscreenMode() {
        if (isFullscreenEnabled()) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            enterImmersiveFullscreen()
        } else {
            WindowCompat.setDecorFitsSystemWindows(window, true)
            showSystemBars()
        }
    }

    private fun isFullscreenEnabled(): Boolean {
        return appSettings.getCachedString("fullscreen_app") != "关"
    }

    private fun enterImmersiveFullscreen() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun showSystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.show(WindowInsetsCompat.Type.systemBars())
    }
}
