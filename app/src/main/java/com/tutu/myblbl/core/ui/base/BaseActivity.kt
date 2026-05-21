package com.tutu.myblbl.core.ui.base

import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.tutu.myblbl.MyBLBLApplication
import com.tutu.myblbl.R
import com.tutu.myblbl.core.common.settings.AppSettingsDataStore
import androidx.viewbinding.ViewBinding
import org.koin.android.ext.android.inject

abstract class BaseActivity<VB : ViewBinding> : AppCompatActivity() {

    protected lateinit var binding: VB
    private var initialFullscreenModeDeferred = false

    abstract fun getViewBinding(): VB

    protected val appSettings: AppSettingsDataStore by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        (application as? MyBLBLApplication)?.ensureUiRuntimeReady("${this::class.java.simpleName}.onCreate")
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
        if (!initialFullscreenModeDeferred) {
            applyFullscreenMode()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !initialFullscreenModeDeferred) {
            applyFullscreenMode()
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
