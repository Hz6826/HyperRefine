package cn.ianzb.hyperrefine.hook.systemserver

import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.Reflect

/**
 * 定时深色模式到点立即生效。
 *
 * HyperOS 的 `UiModeManagerService` 在自动深色/浅色切换前会调用
 * `shouldApplyAutomaticChangesImmediately()`：屏幕亮着时返回 false，先挂起配置，
 * 等用户第一次息屏（`mDeviceInactiveListener`）后再应用。这里把该判定固定为 true，
 * 时间一到就直接下发配置，不再等待息屏。手动切换与系统其它逻辑不变。
 *
 * 目标进程：system_server（`system`）。开关变更需重启设备生效。
 */
class DarkModeScheduleHook : BaseHook() {

    override val key: String = DarkModeScheduleKeys.IMMEDIATE

    override fun init() {
        val clazz = Reflect.findClassIfExists(
            "com.android.server.UiModeManagerService",
            target.classLoader,
        )
        if (clazz == null) {
            HookHelper.log("$tag: UiModeManagerService not found")
            return
        }
        val method = clazz.declaredMethods.firstOrNull {
            it.name == "shouldApplyAutomaticChangesImmediately" && it.parameterCount == 0
        }
        if (method == null) {
            HookHelper.log("$tag: shouldApplyAutomaticChangesImmediately not found")
            return
        }
        runCatching {
            method.isAccessible = true
            HookHelper.deoptimize(method)
            HookHelper.hookReplace(method) { true }
        }.onFailure {
            HookHelper.log("$tag: hook shouldApplyAutomaticChangesImmediately failed", it)
            return
        }
        HookHelper.log("$tag: automatic night mode applies immediately")
    }
}
