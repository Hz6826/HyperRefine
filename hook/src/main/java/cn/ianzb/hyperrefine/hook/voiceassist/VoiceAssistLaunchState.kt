package cn.ianzb.hyperrefine.hook.voiceassist

import android.os.SystemClock
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.Reflect
import java.lang.reflect.Method

/**
 * 记录最近一次 `VoiceService.processIntent` 的启动来源，并复用 8.3.30 的 PowerWake 光效。
 *
 * 双击小白条时 `voice_assist_start_from_key` 会先到 VoiceService，但不一定原样带到聊天
 * Activity 的 Intent；聊天首帧读取这里即可判断来源。
 */
object VoiceAssistLaunchState {

    @Volatile
    var startFrom: String? = null
        private set

    @Volatile
    var updatedAt: Long = 0L
        private set

    @Volatile
    private var voiceService: Any? = null

    @Volatile
    private var powerWakeImpl: Any? = null

    @Volatile
    private var powerWakeStart: Method? = null

    fun record(value: String?) {
        if (value.isNullOrBlank()) return
        startFrom = value
        updatedAt = SystemClock.uptimeMillis()
    }

    fun attachService(service: Any?) {
        if (service != null) voiceService = service
    }

    /**
     * 启动官方 PowerWake。8.3.30 的电源键实现位于 `ms1.j.c`：
     * - `os1.k` 使用私有 `r()`；
     * - `os1.p` 使用私有 `h()`；
     * - 旧实现 `ns1.r` 使用 `g()`。
     */
    fun startPowerWake(): Boolean {
        val cached = powerWakeStart
        val impl = powerWakeImpl
        if (cached != null && impl != null) {
            return invoke(cached, impl)
        }

        val service = voiceService ?: return false
        val managerClass = Reflect.findClassIfExists(POWER_MANAGER_CLASS, service.javaClass.classLoader)
            ?: return false
        var manager = runCatching { Reflect.findField(managerClass, "c").get(null) }.getOrNull()
        if (manager == null) {
            runCatching {
                val singleton = Reflect.findField(managerClass, "a").get(null)
                val process = managerClass.declaredMethods.firstOrNull {
                    it.name == "process" && it.parameterCount == 2
                } ?: return@runCatching
                process.isAccessible = true
                process.invoke(singleton, service, true)
            }.onFailure { HookHelper.log("VoiceAssistLaunchState: PowerWake process failed", it) }
            manager = runCatching { Reflect.findField(managerClass, "c").get(null) }.getOrNull()
        }
        if (manager == null) {
            HookHelper.log("VoiceAssistLaunchState: PowerWake manager missing")
            return false
        }

        // OS3 的 ms1.j.c 是 os1.b，真实实现在字段 d；HyperOS 4 的 ns1.e 用字段 e。
        val target = runCatching { Reflect.findField(manager.javaClass, "d").get(manager) }.getOrNull()
            ?: runCatching { Reflect.findField(manager.javaClass, "e").get(manager) }.getOrNull()
            ?: run {
                HookHelper.log("VoiceAssistLaunchState: PowerWake impl missing on ${manager.javaClass.name}")
                return false
            }

        val methodName = when (val name = target.javaClass.name) {
            "os1.k", "os1.p" -> if (name == "os1.k") "r" else "h"
            else -> if (name.endsWith(".os1.k")) "r" else if (name.endsWith(".os1.p")) "h" else "g"
        }
        val start = target.javaClass.declaredMethods.firstOrNull {
            it.name == methodName && it.parameterCount == 0
        } ?: run {
            HookHelper.log("VoiceAssistLaunchState: PowerWake $methodName missing on ${target.javaClass.name}")
            return false
        }

        powerWakeImpl = target
        powerWakeStart = start
        return invoke(start, target)
    }

    private fun invoke(method: Method, target: Any): Boolean = runCatching {
        method.isAccessible = true
        method.invoke(target)
        true
    }.onFailure { HookHelper.log("VoiceAssistLaunchState: PowerWake invoke failed", it) }
        .getOrDefault(false)

    /**
     * 8.3.30 的 `com.xiaomi.voiceassistant.utils.x6` 保存当前启动来源；
     * VoiceService 没有把 extra 带到聊天 Activity 时，用这里兜底。
     */
    fun refreshFromApp(loader: ClassLoader?) {
        if (loader == null) return
        val value = runCatching {
            val clazz = Reflect.findClassIfExists(APP_ORIGIN_CLASS, loader) ?: return@runCatching null
            (Reflect.callStaticMethod(clazz, "getVoiceAssistStartFromKey") as? String)
                ?.takeIf { it.isNotBlank() }
                ?: (Reflect.callStaticMethod(clazz, "getLastQueryOrigin") as? String)
                    ?.takeIf { it.isNotBlank() }
        }.getOrNull() ?: return

        if (value != startFrom) {
            startFrom = value
            updatedAt = SystemClock.uptimeMillis()
        }
    }

    fun isDoubleClickGestureLine(): Boolean {
        val value = startFrom ?: return false
        if (!value.startsWith("double_click")) return false
        return value.contains("gesture_line") ||
            value.contains("fullscreen") ||
            value.contains("full_screen")
    }

    private const val APP_ORIGIN_CLASS = "com.xiaomi.voiceassistant.utils.x6"
    private const val POWER_MANAGER_CLASS = "ms1.j"
}
