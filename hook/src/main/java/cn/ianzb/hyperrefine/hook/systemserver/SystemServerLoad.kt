package cn.ianzb.hyperrefine.hook.systemserver

import cn.ianzb.hyperrefine.hook.base.BaseLoad
import cn.ianzb.hyperrefine.hook.base.PackageTarget
import cn.ianzb.hyperrefine.hook.prefs.HookPrefs

/** system_server 侧功能：定时深色模式立即生效。 */
class SystemServerLoad : BaseLoad() {

    override val targetPackages: List<String> = listOf(DarkModeScheduleKeys.TARGET)

    override fun onPackageLoaded(target: PackageTarget) {
        initHook(
            DarkModeScheduleHook(),
            HookPrefs.getBoolean(DarkModeScheduleKeys.IMMEDIATE, false),
        )
    }
}
