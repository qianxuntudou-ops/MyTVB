package com.mytvb.core.ui.base

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AppCompatViewInflater

/**
 * UI 文字缩放工厂：把 XML inflate 出来的 TextView 替换为 [ScaledTextView]，
 * 其余类名委托 [AppCompatViewInflater]（保持 AppCompat 组件转换行为），
 * 全限定类名返回 null 走系统默认机制。
 *
 * 必须在 Activity 的 super.onCreate 之前 [install]——此后 AppCompat 检测到已有
 * Factory 会跳过自装，AppCompat 组件转换由本工厂的委托补齐。
 */
object UiTextScaleFactory {

    private const val APPCOMPAT_TEXT_VIEW =
        "androidx.appcompat.widget.AppCompatTextView"

    private val viewInflater = ExposedAppCompatViewInflater()

    /**
     * AppCompatViewInflater.createView 是 protected（供 Material 主题子类扩展），
     * 用空子类公开它，以保持短名组件（ImageView/Button 等）的 AppCompat 转换行为。
     */
    private class ExposedAppCompatViewInflater : AppCompatViewInflater() {
        public override fun createView(context: Context, name: String, attrs: AttributeSet): View? {
            return super.createView(context, name, attrs)
        }
    }

    fun install(inflater: LayoutInflater) {
        if (inflater.factory2 != null) return
        inflater.factory2 = object : LayoutInflater.Factory2 {
            override fun onCreateView(
                parent: View?,
                name: String,
                context: Context,
                attrs: AttributeSet
            ): View? = onCreateView(name, context, attrs)

            override fun onCreateView(
                name: String,
                context: Context,
                attrs: AttributeSet
            ): View? = createView(context, attrs, name)
        }
    }

    /**
     * 仅安装"密度守卫"的 Factory：校验/补钉 density 后返回 null，把 View 创建完全
     * 交还系统（不影响组件替换行为）。
     *
     * 需要单独安装的原因：`LayoutInflater.from(activity)` 返回的是 ContextThemeWrapper
     * 自己缓存的 inflater（AOSP ContextThemeWrapper#getSystemService），与
     * `activity.layoutInflater`（PhoneWindow 的）是两个实例；RecyclerView 的 item、
     * 代码构造的 Dialog 布局多走前者，[install] 装不到，密度被系统重置时这些页面
     * 仍会放大。两个实例都装、守卫逻辑幂等，覆盖所有 XML inflate。
     */
    fun installDensityGuard(inflater: LayoutInflater) {
        if (inflater.factory2 != null) return
        inflater.factory2 = object : LayoutInflater.Factory2 {
            override fun onCreateView(
                parent: View?,
                name: String,
                context: Context,
                attrs: AttributeSet
            ): View? {
                UiScale.ensureApplied(context.resources)
                return null
            }

            override fun onCreateView(
                name: String,
                context: Context,
                attrs: AttributeSet
            ): View? {
                UiScale.ensureApplied(context.resources)
                return null
            }
        }
    }

    private fun createView(context: Context, attrs: AttributeSet, name: String): View? {
        // 每个 View 创建前校验密度：系统 config 变化（系统栏显隐等）会重置 displayMetrics，
        // 若不管，本次 inflate 的尺寸解析会整体按系统 density 放大数倍（density 已改、View 未建）
        UiScale.ensureApplied(context.resources)
        if (name == "TextView" || name == APPCOMPAT_TEXT_VIEW) {
            return ScaledTextView(context, attrs).apply { syncFromInflation() }
        }
        // 短名（ImageView/Button 等）交给 AppCompat 转换；带包名的全限定类走系统反射
        if ('.' !in name) {
            return viewInflater.createView(context, name, attrs)
        }
        return null
    }
}
