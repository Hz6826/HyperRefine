package cn.ianzb.hyperrefine.hook.systemui

import android.view.MotionEvent
import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.device.DeviceType
import cn.ianzb.hyperrefine.hook.rule.HookSkippedException
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.Reflect

/**
 * 禁用全屏应用底部角落的无极小窗手势。
 *
 * 平板 / 折叠屏上，从全屏应用左下角或右下角向内拖动会直接拉起无极小窗。这个入口与
 * 小窗自身的「四角拖拽缩放」共用 [MiuiInfinityModeThermalArea]：
 * - `onTouchEvent` 先走 `freeformHotSpotDetection()`（已有小窗的角）；
 * - 只有它返回 false 时才走 `fullscreenHotSpotDetection()`（全屏应用的底部角落）。
 *
 * 因此只让 `fullscreenHotSpotDetection()` 返回 false，即可关闭「全屏 → 无极小窗」，
 * 小窗的四角缩放仍然走前一条路径，完全不受影响。
 *
 * 目标类位于小米 WMShell（`com.android.systemui` 进程）。
 */
class DisableCornerFreeformHook : BaseHook() {

    override val key: String = SplitScreenKeys.DISABLE_CORNER_FREEFORM

    /** 平板 / 折叠屏专属：手机保持官方行为。 */
    override val deviceScope: Set<DeviceType> = setOf(DeviceType.PAD, DeviceType.FOLD)

    override fun init() {
        val loader = target.classLoader ?: throw HookSkippedException("no classloader")
        // WMShell 代码在 Miui-WindowManager-Shell.jar 中，优先用目标进程的加载器；
        // 个别 ROM 会把它挂到父加载器 / 上下文加载器，再补两次兜底。
        val cls = Reflect.findClassIfExists(THERMAL_AREA, loader)
            ?: Reflect.findClassIfExists(THERMAL_AREA)
            ?: Reflect.findClassIfExists(THERMAL_AREA, loader.parent)
            ?: throw HookSkippedException("$THERMAL_AREA not found")
        val method = cls.declaredMethods.firstOrNull {
            it.name == "fullscreenHotSpotDetection" &&
                it.parameterCount == 1 &&
                it.parameterTypes[0] == MotionEvent::class.java
        } ?: throw HookSkippedException("$THERMAL_AREA#fullscreenHotSpotDetection not found")
        method.isAccessible = true
        // fullscreenHotSpotDetection 很小，可能已被内联进 onTouchEvent；按 libxposed 的
        // 说明反优化调用方，确保触摸时仍会真正调用到被 Hook 的方法。
        cls.declaredMethods.firstOrNull {
            it.name == "onTouchEvent" &&
                it.parameterCount == 2 &&
                it.parameterTypes[0] == MotionEvent::class.java &&
                it.parameterTypes[1].name == "android.view.InputMonitor"
        }?.let {
            it.isAccessible = true
            HookHelper.deoptimize(it)
        }
        HookHelper.deoptimize(method)
        HookHelper.hookBefore(method) { param -> param.setResultValue(false) }
        HookHelper.log("$tag: fullscreen corner freeform disabled")
    }

    companion object {
        private const val THERMAL_AREA =
            "com.android.wm.shell.multitasking.miuiinfinitymode.MiuiInfinityModeThermalArea"
    }
}
