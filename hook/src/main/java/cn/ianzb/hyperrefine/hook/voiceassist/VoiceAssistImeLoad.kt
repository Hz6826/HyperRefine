package cn.ianzb.hyperrefine.hook.voiceassist

import cn.ianzb.hyperrefine.hook.base.BaseLoad
import cn.ianzb.hyperrefine.hook.base.PackageTarget

/**
 * 超级小爱输入法（`com.xiaomi.type`）目标 Load。
 *
 * Hook 始终安装，回调内实时读取开关；用户切换设置后无需重启输入法进程。
 */
class VoiceAssistImeLoad : BaseLoad() {

    override val targetPackages: List<String> = listOf(VoiceAssistImeKeys.PKG_VOICE_ASSIST_IME)

    override fun onPackageLoaded(target: PackageTarget) {
        initHook(VoiceAssistImeMaterialHook(), true)
    }
}
