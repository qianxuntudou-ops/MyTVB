package com.mytvb.core.ui.base

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewStub
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.appcompat.widget.LinearLayoutCompat
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.fragment.app.Fragment
import androidx.viewbinding.ViewBinding
import androidx.core.content.ContextCompat
import com.mytvb.R
import com.mytvb.core.common.log.AppLog
import com.mytvb.core.ui.navigation.navigateBackFromUi
import com.mytvb.ui.activity.MainActivity

abstract class BaseFragment<VB : ViewBinding> : Fragment() {

    private var _binding: VB? = null
    protected val binding: VB get() = _binding!!

    protected var rootView: View? = null
    protected var contentContainer: FrameLayout? = null
    protected var viewError: ConstraintLayout? = null
    protected var textError: AppCompatTextView? = null
    protected var imageError: AppCompatImageView? = null
    protected var buttonRetry: AppCompatTextView? = null
    protected var topBar: LinearLayoutCompat? = null
    protected var buttonBack: AppCompatImageView? = null
    protected var textMainTitle: AppCompatTextView? = null
    protected var loadingProgressBar: ProgressBar? = null
    protected var mainActivity: MainActivity? = null
    private var viewErrorStub: android.view.ViewStub? = null

    abstract fun getViewBinding(inflater: LayoutInflater, container: ViewGroup?): VB
    abstract fun initView()
    open fun initData() {}
    open fun initObserver() {}
    open fun initArguments() {}
    protected open fun useLightBaseContainer(): Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (activity is MainActivity) {
            mainActivity = activity as MainActivity
        }
        initArguments()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        if (rootView == null) {
            val className = this::class.java.simpleName
            val t0 = SystemClock.elapsedRealtime()
            // inflate 前快照：新页"自己变小"须证明 inflate 时 density 已被污染
            UiScale.probe("fragCreate:$className", inflater.context)
            val view = if (useLightBaseContainer() && !isTopBarVisible()) {
                createLightBaseView(inflater.context)
            } else {
                inflater.inflate(R.layout.fragment_base, container, false)
            }
            val t1 = SystemClock.elapsedRealtime()
            rootView = view
            contentContainer = view.findViewById(R.id.contentContainer)
            viewErrorStub = view.findViewById(R.id.view_error_stub)
            topBar = view.findViewById(R.id.top_bar)
            buttonBack = view.findViewById(R.id.button_back)
            textMainTitle = view.findViewById(R.id.text_main_title)
            loadingProgressBar = view.findViewById(R.id.loading_progress_bar)
            topBar?.visibility = if (isTopBarVisible()) View.VISIBLE else View.GONE
            _binding = getViewBinding(inflater, contentContainer)
            val t2 = SystemClock.elapsedRealtime()
            if (binding.root.parent == null) {
                contentContainer?.addView(binding.root)
            }
            initView()
            val t3 = SystemClock.elapsedRealtime()
            val totalMs = t3 - t0
            if (totalMs > 10) {
                AppLog.i("STARTUP", "$className.onCreateView base_inflate=${t1 - t0}ms binding=${t2 - t1}ms initView=${t3 - t2}ms total=${totalMs}ms")
            }
        }
        return rootView
    }

    private fun createLightBaseView(context: Context): View {
        return FrameLayout(context).apply {
            id = R.id.base_container
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(resolveThemeColor(context, R.attr.backgroundColor))

            addView(FrameLayout(context).apply {
                id = R.id.contentContainer
                descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            })

            addView(ViewStub(context).apply {
                id = R.id.view_error_stub
                layoutResource = R.layout.fragment_base_error
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            })

        }
    }

    private fun createLoadingProgressBar(context: Context): ProgressBar {
        val loadingSize = context.resources.getDimensionPixelSize(R.dimen.px200)
        return ProgressBar(context).apply {
            id = R.id.loading_progress_bar
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(loadingSize, loadingSize, Gravity.CENTER)
        }
    }

    private fun resolveThemeColor(context: Context, attr: Int): Int {
        val value = TypedValue()
        if (!context.theme.resolveAttribute(attr, value, true)) {
            return android.graphics.Color.TRANSPARENT
        }
        return if (value.resourceId != 0) {
            ContextCompat.getColor(context, value.resourceId)
        } else {
            value.data
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initObserver()
        initData()
    }

    open fun isTopBarVisible(): Boolean = false

    open fun onRetryClick() {}

    protected fun ensureErrorView() {
        if (viewError != null) return
        val stub = viewErrorStub ?: return
        val inflated = stub.inflate()
        viewError = inflated.findViewById(R.id.view_error)
        textError = inflated.findViewById(R.id.text_error)
        imageError = inflated.findViewById(R.id.image_error)
        buttonRetry = inflated.findViewById(R.id.button_retry)
        buttonRetry?.setOnClickListener { onRetryClick() }
        viewErrorStub = null
    }

    protected open fun showContent() {
        if (!isAdded) return
        viewError?.visibility = View.GONE
        contentContainer?.visibility = View.VISIBLE
    }

    protected fun showLoading(show: Boolean) {
        if (!isAdded) return
        val progress = loadingProgressBar ?: if (show) {
            createLoadingProgressBar(requireContext()).also { created ->
                (rootView as? ViewGroup)?.addView(created)
                loadingProgressBar = created
            }
        } else {
            null
        }
        progress?.visibility = if (show) View.VISIBLE else View.GONE
    }

    protected fun showNetError() {
        if (!isAdded) return
        showErrorImage(R.drawable.net_error, getString(R.string.net_error))
    }

    protected fun showEmpty() {
        if (!isAdded) return
        showErrorImage(R.drawable.empty, getString(R.string.empty))
    }

    protected fun showError(message: String?) {
        if (!isAdded) return
        showErrorImage(R.drawable.net_error, friendlyErrorText(message))
    }

    /**
     * 把底层异常文案翻译成用户可读的提示。DNS 解析失败/网络不可达（盒子开机网络
     * 未就绪的典型表现）显示统一的网络不可用文案，其余原样透出。
     */
    private fun friendlyErrorText(message: String?): String {
        val raw = message.orEmpty()
        return when {
            raw.isBlank() -> getString(R.string.net_error)
            raw.contains("Unable to resolve host", ignoreCase = true) ||
                raw.contains("UnknownHost", ignoreCase = true) ||
                raw.contains("No address associated", ignoreCase = true) ||
                raw.contains("Network is unreachable", ignoreCase = true) -> getString(R.string.net_unavailable)
            else -> raw
        }
    }

    private fun showErrorImage(imageResId: Int, text: String) {
        ensureErrorView()
        viewError?.visibility = View.VISIBLE
        imageError?.setImageResource(imageResId)
        textError?.text = text
        buttonRetry?.requestFocus()
        contentContainer?.visibility = View.GONE
    }

    fun setMainTitle(title: String) {
        textMainTitle?.text = title
    }

    protected open fun openInHostContainer(fragment: Fragment, addToBackStack: Boolean = true) {
        mainActivity?.openInHostContainer(fragment, addToBackStack)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        mainActivity = null
        viewError = null
        textError = null
        imageError = null
        buttonRetry = null
        loadingProgressBar = null
        topBar = null
        buttonBack = null
        textMainTitle = null
        viewErrorStub = null
        contentContainer?.removeAllViews()
        contentContainer = null
        _binding = null
        rootView = null
    }
}
