package cn.ianzb.hyperrefine.hook.voiceassist

import android.os.SystemClock
import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.prefs.HookPrefs
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.Reflect

/**
 * 双击小白条打开超级小爱聊天时，补上与长按电源键相同的 PowerWake 全屏光效。
 *
 * 8.3.30 中电源键长按由 `ms1.j.c` 的 PowerWake 实现启动；双击手势条只打开普通
 * 聊天编辑态，不会触发。这里在双击聊天首帧状态更新后调用同一套 PowerWake。
 */
class DoubleClickChatFullEffectHook : BaseHook() {

    override val key: String = KEY

    @Volatile
    private var handledAt = 0L

    override fun init() {
        val method = runCatching {
            Reflect.findClassIfExists(UI_CLASS, target.classLoader)
                ?.declaredMethods
                ?.firstOrNull { it.name == "playAnimForUpdateStatus" && it.parameterCount == 3 }
                ?.also { it.isAccessible = true }
        }.onFailure { HookHelper.log("$tag: resolve playAnimForUpdateStatus failed", it) }
            .getOrNull()

        if (method == null) {
            HookHelper.log("$tag: playAnimForUpdateStatus not resolved")
            return
        }

        runCatching {
            HookHelper.hookAfter(method) { _ ->
                if (!HookPrefs.getBoolean(KEY, true)) return@hookAfter
                VoiceAssistLaunchState.refreshFromApp(target.classLoader)
                if (!VoiceAssistLaunchState.isDoubleClickGestureLine()) return@hookAfter

                val stamp = VoiceAssistLaunchState.updatedAt
                if (stamp == handledAt) return@hookAfter
                if (SystemClock.uptimeMillis() - stamp > EFFECT_WINDOW_MS) return@hookAfter
                if (!VoiceAssistLaunchState.startPowerWake()) return@hookAfter

                handledAt = stamp
                HookHelper.log("$tag: PowerWake effect invoked")
            }
        }.onFailure { HookHelper.log("$tag: hook playAnimForUpdateStatus failed", it) }
    }

    companion object {
        const val KEY = VoiceAssistKeys.DOUBLE_CLICK_CHAT_FULL_EFFECT
        private const val UI_CLASS = "com.xiaomi.voiceassistant.mainui.a1"
        private const val EFFECT_WINDOW_MS = 8000L
    }
}
