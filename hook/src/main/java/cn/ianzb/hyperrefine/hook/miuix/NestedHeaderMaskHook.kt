package cn.ianzb.hyperrefine.hook.miuix

import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.prefs.HookPrefs
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.Reflect
import java.util.Collections
import java.util.WeakHashMap

/**
 * 关闭与顶栏渐变重复的笔记渐变遮罩：
 *
 * 1. MIUIX `NestedHeaderLayout` 在 `onFinishInflate` 新建的滚动渐变 `View`（`onDraw` 里画 `LinearGradient`，
 *    即主页面标题下方那条渐变透明度栏）；
 * 2. 笔记「筛选 / 分类标签」栏 `OverlayMaskFrameLayout`（`sticky_view`）内置的 `SearchViewOverlayMaskImpl`
 *    叠加遮罩（在 `dispatchDraw` 里绘制）。
 *
 * 仅当「顶栏渐变」开关开启时生效；不含对应类的应用自动跳过。
 *
 * 顶栏渐变采用「单 owner 实时模糊」：被让位的栏不再有模糊，需要恢复这里的原生渐变作为降级样式。
 * 因此遮罩不是永久 GONE，而是按 ActionBar 的位置按需显示（[showMaskForBar] / [hideMaskForBar]）。
 */
class NestedHeaderMaskHook : BaseHook() {

    override val key: String = TopBarKeys.KEY

    override fun init() {
        val loader = target.classLoader ?: return

        Reflect.findClassIfExists(NESTED_HEADER, loader)?.let { cls ->
            Reflect.findMethodIfExists(cls, "onFinishInflate")?.let { inflate ->
                HookHelper.intercept(inflate) { chain ->
                    val result = chain.proceed()
                    if (enabled()) (chain.thisObject as? ViewGroup)?.let(::suppressNestedMask)
                    result
                }
            }
        }

        // 笔记专属适配（可单独关闭）。
        Reflect.findClassIfExists(NOTES_OVERLAY_MASK, loader)?.let { cls ->
            Reflect.findMethodIfExists(cls, "setOverlayMaskEnabled", java.lang.Boolean.TYPE)?.let { method ->
                HookHelper.hookBefore(method) { if (notesEnabled()) it.setArg(0, false) }
                HookHelper.log("$tag: disabled notes overlay mask")
            }
            Reflect.findMethodIfExists(cls, "setMaskColor", Integer.TYPE)?.let { method ->
                HookHelper.hookBefore(method) { if (notesEnabled()) it.setArg(0, 0) }
            }
        }
    }

    private fun enabled(): Boolean = HookPrefs.getBoolean(TopBarKeys.KEY, false)

    /** 笔记专属适配需总开关 + 应用专属适配总开关 + 笔记开关同时开启。 */
    private fun notesEnabled(): Boolean = enabled() && TopBarKeys.notesAdvancedEnabled()

    /**
     * `NestedHeaderLayout` 在 `onFinishInflate` 里 `addView` 的滚动渐变遮罩：
     * MIUIX 为 `NestedHeaderOverlayMaskView`（旧版为裸 `View`）。
     *
     * 只处理这个遮罩本身 —— 绝不能用「容器之外的普通子 View」来判断：标题等 `TextView`
     * 也可能是直接子项，误判后 [hookMaskClass] 会把该 framework 类的 `onDraw` 全局置空，
     * 导致整个进程的文本全部消失。
     */
    private fun suppressNestedMask(layout: ViewGroup) {
        for (i in 0 until layout.childCount) {
            val child = layout.getChildAt(i)
            if (!isNestedMask(child)) continue
            MaskRegistry.disable(child)
            hookMaskClass(child.javaClass)
        }
    }

    private fun isNestedMask(view: View): Boolean {
        val cls = view.javaClass
        return cls == View::class.java || cls.name == NESTED_OVERLAY_MASK
    }

    private fun hookMaskClass(cls: Class<*>) {
        if (cls == View::class.java) return
        synchronized(hookedMaskClasses) { if (!hookedMaskClasses.add(cls)) return }
        runCatching {
            val onDraw = cls.getDeclaredMethod("onDraw", Canvas::class.java)
            // 只处理自己覆写了 onDraw 的类，避免误伤 framework View。
            if (onDraw.declaringClass == View::class.java) return
            onDraw.isAccessible = true
            HookHelper.hookBefore(onDraw) { param ->
                val view = param.thisObject as? View
                if (view != null && !MaskRegistry.allows(view)) param.setResultValue(null)
            }
            HookHelper.log("$tag: suppressed nested header gradient (${cls.name})")
        }.onFailure { HookHelper.log("$tag: suppress nested header gradient failed", it) }
    }

    private val hookedMaskClasses = Collections.synchronizedSet(HashSet<Class<*>>())

    private class MaskState(
        val originalBackground: Drawable?,
        val originalAlpha: Float,
        val originalVisibility: Int,
    ) {
        var enabled: Boolean = false
    }

    /**
     * 原生遮罩的按需开关。`NestedHeaderMaskHook` 只会在顶栏渐变开启时注册这些 View；
     * `TopBarGradientHook` 在非活跃栏上调用 [showMaskForBar]，在 owner 上调用 [hideMaskForBar]。
     */
    private object MaskRegistry {
        private val states = WeakHashMap<View, MaskState>()

        @Synchronized
        fun disable(view: View) {
            val state = states[view] ?: MaskState(view.background, view.alpha, view.visibility).also {
                states[view] = it
            }
            state.enabled = false
            view.alpha = 0f
            view.visibility = View.GONE
            view.background = null
        }

        @Synchronized
        fun enable(view: View) {
            val state = states[view] ?: return
            if (state.enabled) return
            state.enabled = true
            view.alpha = state.originalAlpha
            view.visibility = state.originalVisibility
            view.background = state.originalBackground
            view.invalidate()
        }

        @Synchronized
        fun allows(view: View): Boolean = states[view]?.enabled != false

        @Synchronized
        fun showForBar(bar: View) {
            val target = chooseMask(bar) ?: return
            enable(target)
        }

        @Synchronized
        fun hideForBar(bar: View) {
            val target = chooseMask(bar) ?: return
            disable(target)
        }

        /** 按 ActionBar 与遮罩的屏幕横向重叠度选择同一栏的遮罩。 */
        private fun chooseMask(bar: View): View? {
            if (states.isEmpty()) return null
            val barLoc = IntArray(2)
            bar.getLocationOnScreen(barLoc)
            val barLeft = barLoc[0]
            val barRight = barLeft + bar.width
            var best: View? = null
            var bestOverlap = -1
            val maskLoc = IntArray(2)
            for (mask in states.keys.toList()) {
                // 遮罩被 GONE 后自身宽度会变成 0，用它的父 NestedHeaderLayout 做横向定位。
                val anchor = (mask.parent as? View) ?: mask
                anchor.getLocationOnScreen(maskLoc)
                val left = maxOf(barLeft, maskLoc[0])
                val right = minOf(barRight, maskLoc[0] + anchor.width)
                val overlap = (right - left).coerceAtLeast(0)
                if (overlap > bestOverlap) {
                    bestOverlap = overlap
                    best = mask
                }
            }
            return best
        }
    }

    companion object {
        private const val NESTED_HEADER = "miuix.nestedheader.widget.NestedHeaderLayout"
        private const val NESTED_OVERLAY_MASK = "miuix.nestedheader.widget.NestedHeaderOverlayMaskView"
        private const val NOTES_OVERLAY_MASK =
            "com.miui.notes.notesui.feature.note.presentation.list.widget.OverlayMaskFrameLayout"

        /** 非活跃栏恢复原生渐变透明度遮罩。 */
        fun showMaskForBar(bar: View) = MaskRegistry.showForBar(bar)

        /** 当前 owner 使用实时模糊，隐藏同一栏的原生渐变遮罩避免叠加。 */
        fun hideMaskForBar(bar: View) = MaskRegistry.hideForBar(bar)
    }
}
