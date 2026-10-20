package cn.ianzb.hyperrefine.hook.voiceassist

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.prefs.HookPrefs
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.Reflect
import java.util.UUID

/**
 * 长按底部小白条（手势导航条）打开「超级小爱灵感球」。
 *
 * 系统长按小白条最终会以 `android.intent.action.ASSIST` 启动超级小爱的
 * `VoiceService`，并带上 `triggerType=NavLongPress` 或
 * `voice_assist_start_from_key=long_press_fullscreen_gesture_line`。
 * 官方默认在这里进入长按识屏；本 Hook 在 [VoiceService.processIntent] 之前
 * 接管，直接调用官方的 `IntentionalBallManager.operate("home_intentional_ball", session)`，
 * 复用它自己的悬浮球窗口、录音和识屏链路，不额外绘制 UI。
 *
 * 关闭开关时完全不拦截，保持系统原生长按识屏。
 */
class LongPressHomeIntentBallHook : BaseHook() {

    override val key: String = VoiceAssistKeys.LONG_PRESS_HOME_INTENT_BALL

    @Volatile
    private var syntheticBallPending = false

    @Volatile
    private var syntheticBallAt = 0L

    override fun init() {
        val loader = target.classLoader ?: return
        val service = Reflect.findClassIfExists(SERVICE_CLASS, loader) ?: return
        val processIntent = Reflect.findMethodIfExists(service, "processIntent", Intent::class.java) ?: return

        HookHelper.hookBefore(processIntent) { param ->
            val intent = param.args.getOrNull(0) as? Intent ?: return@hookBefore
            VoiceAssistLaunchState.attachService(param.thisObject)
            VoiceAssistLaunchState.record(intent.getStringExtra(EXTRA_START_FROM))
            if (!HookPrefs.getBoolean(key, false)) return@hookBefore
            HookHelper.log(
                "$tag: processIntent start=${intent.getStringExtra(EXTRA_START_FROM)} " +
                    "trigger=${intent.getStringExtra(EXTRA_TRIGGER_TYPE)} " +
                    "function=${intent.getStringExtra(EXTRA_FUNCTION_KEY)} action=${intent.action}",
            )
            if (!isGestureLineLongPress(intent)) return@hookBefore
            val context = param.thisObject as? Context ?: return@hookBefore
            val started = runCatching { startIntentionalBall(context, intent) }
                .onFailure { HookHelper.log("$tag: start intentional ball failed", it) }
                .getOrDefault(false)
            if (started) {
                HookHelper.log("$tag: long-press gesture line -> intentional ball")
                // 跳过原本的长按识屏处理；灵感球已由官方控制器接住。
                param.setResultValue(null)
            }
        }
        installScreenRecognitionFallback(loader)
        installBallReadyFallback()
        HookHelper.log("$tag: installed")
    }

    /**
     * 某些 ROM 版本不会把长按意图交给 [VoiceService.processIntent]，而是直接调用
     * `ScreenRecognitionHelper.startScreenRecognition(bitmap, origin, ...)`。
     * 这里按 origin 兜底拦截，但只在 origin 明确是长按小白条时生效，
     * 不影响唤醒词 / 圈选等其它识屏入口。
     */
    private fun installScreenRecognitionFallback(loader: ClassLoader) {
        val helper = Reflect.findClassIfExists(SCREEN_RECOGNITION_HELPER_CLASS, loader) ?: return
        val method = helper.declaredMethods.firstOrNull {
            it.name == "startScreenRecognition" && it.parameterTypes.size == 5
        } ?: return
        runCatching {
            method.isAccessible = true
            HookHelper.hookBefore(method) { param ->
                if (!HookPrefs.getBoolean(key, false)) return@hookBefore
                val origin = param.args.getOrNull(1) as? String ?: return@hookBefore
                if (origin !in LONG_PRESS_GESTURE_LINES) return@hookBefore
                val owner = param.thisObject ?: return@hookBefore
                val context = (owner as? Context) ?: runCatching {
                    Reflect.callMethod(owner, "a") as? Context
                }.getOrNull() ?: return@hookBefore
                val started = runCatching { startIntentionalBall(context, null) }
                    .onFailure { HookHelper.log("$tag: screen recognition fallback failed", it) }
                    .getOrDefault(false)
                if (started) {
                    HookHelper.log("$tag: screen recognition origin=$origin -> intentional ball")
                    param.setResultValue(false)
                }
            }
        }.onFailure { HookHelper.log("$tag: install screen recognition fallback failed", it) }
    }

    /**
     * 官方灵感球窗口在首帧后会向 `com.miui.home.gesture.enableState` 查询
     * `xiaoai_ball_ready` 的边/手指坐标；该坐标只存在于桌面的侧滑手势会话中，
     * 长按小白条没有这个会话，查询返回 null，窗口会立即 `releaseScene`。
     *
     * 这里直接拦截 ContentResolver 的 `xiaoai_ball_ready` 查询，给长按触发补一个
     * 右侧边缘坐标（与侧滑进入一致），让官方 `onEdgeEnterReady` 正常启动 ASR 会话。
     * 用 ContentResolver 而不是内部窗口类，避免不同超级小爱版本的混淆类名变化。
     */
    private fun installBallReadyFallback() {
        val method = runCatching {
            ContentResolver::class.java.getDeclaredMethod(
                "call",
                Uri::class.java,
                String::class.java,
                String::class.java,
                Bundle::class.java,
            )
        }.getOrNull() ?: return
        runCatching {
            HookHelper.hookBefore(method) { param ->
                if (!syntheticBallPending) return@hookBefore
                val elapsed = android.os.SystemClock.uptimeMillis() - syntheticBallAt
                if (elapsed > 2500L) {
                    syntheticBallPending = false
                    return@hookBefore
                }
                val uri = param.args.getOrNull(0) as? Uri ?: return@hookBefore
                val methodName = param.args.getOrNull(1) as? String ?: return@hookBefore
                if (uri.authority != BALL_STATE_AUTHORITY || methodName != BALL_READY_METHOD) {
                    return@hookBefore
                }
                syntheticBallPending = false
                // 长按小白条时手指在底部中间。edge 与 finger 使用同一个点，
                // 灵感球就直接在触发位置出现，不会先从右侧边缘飞进来。
                // 用 WindowMetrics 取整块屏幕尺寸，避免 Resources 的应用可用高度
                // 把球顶到导航栏上方。
                val bounds: Rect? = runCatching {
                    currentApplication()
                        ?.getSystemService(WindowManager::class.java)
                        ?.maximumWindowMetrics
                        ?.bounds
                }.getOrNull()
                val metrics = Resources.getSystem().displayMetrics
                val width = bounds?.width()?.toFloat() ?: metrics.widthPixels.toFloat()
                val height = bounds?.height()?.toFloat() ?: metrics.heightPixels.toFloat()
                val x = width / 2f
                val y = height * 0.97f
                param.setResultValue(Bundle().apply {
                    putFloat("edge_x", x)
                    putFloat("edge_y", y)
                    putFloat("finger_x", x)
                    putFloat("finger_y", y)
                })
                HookHelper.log("$tag: supplied fallback edge position for long-press ball")
            }
        }.onFailure { HookHelper.log("$tag: install ball-ready fallback failed", it) }
    }

    private fun currentApplication(): Context? = runCatching {
        Class.forName("android.app.ActivityThread")
            .getMethod("currentApplication")
            .invoke(null) as? Context
    }.getOrNull()

    private fun isGestureLineLongPress(intent: Intent): Boolean {
        val startFrom = intent.getStringExtra(EXTRA_START_FROM)
        val triggerType = intent.getStringExtra(EXTRA_TRIGGER_TYPE)
        // 系统不同版本对「长按手势条」的 startFrom 有 fullscreen / full_screen 两种写法，
        // 双击+长按组合手势也会带上组合值；triggerType=NavLongPress 是最稳定的兜底。
        return triggerType == NAV_LONG_PRESS || startFrom in LONG_PRESS_GESTURE_LINES
    }

    /**
     * 通过官方 `UiManager -> FloatManager -> IntentionalBallManager` 触发灵感球，
     * 避免复制其窗口 / 录音 / 识屏逻辑。拿不到实例时再回退到管理器单例。
     */
    private fun startIntentionalBall(context: Context, intent: Intent?): Boolean {
        val session = intent?.getStringExtra(EXTRA_SESSION)
            ?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString()
        val target = intentionalBallManager(context) ?: return false
        syntheticBallAt = android.os.SystemClock.uptimeMillis()
        syntheticBallPending = true
        runCatching {
            Reflect.callMethod(target, "operate", FUNCTION_KEY, session)
        }.getOrElse {
            syntheticBallPending = false
            HookHelper.log("$tag: IntentionalBallManager.operate failed", it)
            return false
        }
        return true
    }

    private fun intentionalBallManager(context: Context): Any? {
        val loader = context.classLoader
        val uiManagerClass = Reflect.findClassIfExists(UI_MANAGER_CLASS, loader)
        val uiManager = uiManagerClass?.let {
            Reflect.callStaticMethod(it, "getInstance", context)
        }
        val floatManager = uiManager?.let { Reflect.callMethod(it, "getFloatManager") }
        var manager = floatManager?.let {
            runCatching { Reflect.callMethod(it, "getIntentionalBallManager") }.getOrNull()
        }
        if (manager == null) {
            val managerClass = Reflect.findClassIfExists(INTENTIONAL_BALL_MANAGER_CLASS, loader)
                ?: return null
            manager = runCatching {
                Reflect.callStaticMethod(managerClass, "getInstance", context)
            }.getOrNull()
        }
        return manager
    }

    companion object {
        const val SERVICE_CLASS = "com.xiaomi.voiceassistant.VoiceService"
        const val SCREEN_RECOGNITION_HELPER_CLASS = "com.xiaomi.voiceassistant.db"
        const val UI_MANAGER_CLASS = "com.xiaomi.voiceassistant.UiManager"
        const val INTENTIONAL_BALL_MANAGER_CLASS = "fn1.f"
        const val FUNCTION_KEY = "home_intentional_ball"
        const val BALL_STATE_AUTHORITY = "com.miui.home.gesture.enableState"
        const val BALL_READY_METHOD = "xiaoai_ball_ready"
        const val EXTRA_START_FROM = "voice_assist_start_from_key"
        const val EXTRA_TRIGGER_TYPE = "triggerType"
        const val EXTRA_FUNCTION_KEY = "voice_assist_function_key"
        const val EXTRA_SESSION = "voice_assist_session"
        const val NAV_LONG_PRESS = "NavLongPress"
        val LONG_PRESS_GESTURE_LINES = setOf(
            // 系统对「长按小白条」有时按 home key 上报（含 SystemUI 的
            // start_screen_recognition 路径），因此与手势线一起识别。
            "long_press_home_key",
            // 部分 HyperOS 版本把长按小白条统一标记为 home_xiaoai。
            "home_xiaoai",
            "long_press_fullscreen_gesture_line",
            "long_press_full_screen_gesture_line",
            "double_click_and_long_click_fullscreen_gesture_line",
            "double_click_and_long_click_full_screen_gesture_line",
            "double_click_and_long_press_full_screen_gesture_line",
        )
    }
}
