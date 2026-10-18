package cn.ianzb.hyperrefine.hook.miuix

import android.view.ViewGroup

/**
 * `com.xiaomi.phone` 的 AI 通话详情页专用顶栏渐变。
 *
 * 该包是 HyperOS 4 的新电话应用，`AiCallDetailActivity` 使用 MIUIX
 * `AppCompatActivity` + `ActionBarContainer`，并在 `onCreate` 里给 ActionBar 设置
 * `ai_call_detail_sticky` 作为 sticky view、开启 `setEnableStickyViewContentInset(true)`。
 * 通用 [TopBarGradientHook] 的模糊几何按完整 `ActionBarContainer` 高度计算，能覆盖 sticky
 * 区域；这里只限制接管范围，避免把拨号、通话中等界面的顶栏一并改成渐变模糊。
 */
class AiCallDetailTopBarGradientHook : TopBarGradientHook() {

    override fun accepts(bar: ViewGroup): Boolean {
        val activity = activityName(bar) ?: return true
        return activity == TARGET_ACTIVITY
    }

    private companion object {
        const val TARGET_ACTIVITY = "com.xiaomi.phone.settings.AiCallDetailActivity"
    }
}
