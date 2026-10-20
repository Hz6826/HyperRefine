package cn.ianzb.hyperrefine.hook.voiceassist

import cn.ianzb.hyperrefine.hook.base.BaseLoad
import cn.ianzb.hyperrefine.hook.base.PackageTarget
import cn.ianzb.hyperrefine.hook.prefs.HookPrefs

/**
 * 超级小爱（`com.miui.voiceassist`）目标 Load。
 */
class VoiceAssistLoad : BaseLoad() {

    override val targetPackages: List<String> = listOf(VoiceAssistKeys.PKG_VOICE_ASSIST)

    override fun onPackageLoaded(target: PackageTarget) {
        initHook(
            ScreenRecognitionRadiusHook(),
            HookPrefs.getBoolean(ScreenRecognitionRadiusHook.KEY, false),
        )
        // 始终安装，回调内实时读取开关；这样用户切换设置后只需重启超级小爱进程，
        // 不依赖「启动时开关已经为开」。
        initHook(LongPressHomeIntentBallHook(), true)
        initHook(DoubleClickChatFullEffectHook(), true)
    }
}
