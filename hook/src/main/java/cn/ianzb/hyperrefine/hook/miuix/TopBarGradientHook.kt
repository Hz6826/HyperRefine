package cn.ianzb.hyperrefine.hook.miuix

import android.app.Activity
import android.content.ContextWrapper
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.prefs.HookPrefs
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.Reflect
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.roundToInt

/**
 * MIUIX 顶栏渐变模糊。
 *
 * 移植自 HyperBackground（MIT）的 `dynamic/topbar/DynamicActionBarHook`：
 * 把 MIUIX 二级页面顶栏（`miuix.appcompat.internal.app.widget.ActionBarContainer`）随滚动
 * 淡入淡出的原生遮罩，替换为「顶部模糊 → 底部透明」的渐变模糊层。
 *
 * - 通过 MIUI 隐藏方法 `View.setMiBackgroundBlurMode/setMiViewBlurMode/setMiBackgroundBlurType/
 *   setBackgroundGradientBlurParams` 配置自加的透明子 View；
 * - 用 DexKit 反混淆定位遮罩绘制方法与其驱动的 `float` 透明度字段，使模糊层跟随原生动画；
 * - 移除原生填充背景（`getPrimaryBackground` / `setPrimaryBackground`）。
 *
 * 性能处理（不改变观感）：
 * - 配置读取按 [CONFIG_TTL_MS] 节流为内存快照，避免每帧多次远程偏好读取；
 * - 遮罩 alpha 持续归零 [RELEASE_DELAY_MS] 后释放模糊管线（回到顶部静止时不再常驻渲染，
 *   再次出现时自动重建）；
 * - 补充系统同源的 `setMiBackgroundBlurScaleRatio`（[BLUR_SCALE_RATIO]，SystemUI 实时表面同款值）。
 * - 模糊几何参数按 [HEIGHT_STEP_DP] 步长量化：滚动收缩期栏高连续变化时不会逐帧重设厂商
 *   模糊管线（HyperBackground 实证「每帧重设线性渐变模糊参数」为掉帧/ANR 根因）。
 * - 首帧预热：页面布局完成后先以 [WARMUP_ALPHA] 的透明度绘制一帧模糊，把渐变模糊管线 /
 *   着色器程序的首次创建从「第一次滚动」提前到打开页面时完成，避免打开应用立刻下滑时卡顿。
 * - 单活动节点 + 精确恢复：滚动交互期间让位侧的模糊节点暂时释放——系统 blur 节点只要
 *   可见/激活就会持续参与渲染（实测「冻结帧」保持可见同样会卡死，无法真正暂停）。
 *   恢复由 MIUIX 顶栏的嵌套滚动信号驱动：`onStopNestedScroll`（滚动含惯性真正结束）
 *   即瞬间恢复被让位节点；没有该信号的页面退回 ~1 秒无绘制帧的兜底判断。
 *
 * 目标：设置 / 短信 / 联系人等 MIUIX 应用。
 */
open class TopBarGradientHook : BaseHook() {

    override val key: String = TopBarKeys.KEY

    override fun useDexKit(): Boolean = true

    /**
     * 顶栏模糊是否**常驻**（不依赖原生滚动遮罩 alpha）。
     *
     * 与本模块页面 `TopBarBlurConfig.ScrollFadeDistance = 0`（常驻完整模糊）一致的逻辑。
     * 通用版跟随原生遮罩（滚动才出现）；计算器自带的新版 MIUIX 顶栏标题常驻收缩、
     * 原生遮罩 alpha 恒为 0，需常驻模糊。见 [CalculatorTopBarGradientHook]。
     */
    protected open val alwaysBlur: Boolean = false

    private var maskPainter: Method? = null
    private var maskAlpha: Field? = null

    private class State(val blur: View) {
        var original: Drawable? = null
        var cleared: Boolean = false
        /** 当前是否已经恢复 hook 前的 MIUIX 渐变遮罩（非活跃栏的降级样式）。 */
        var maskRestored: Boolean = false
        /** 非活跃栏：跳过本 Hook，交还原生 MIUIX 渐变遮罩绘制。 */
        var useOriginalMask: Boolean = false
        var lastRadius: Float = -1f
        var lastHeight: Int = -1
        var lastAlpha: Float = 0f
        var pendingRelease: Runnable? = null
        var warmedUp: Boolean = false
        var warmingUp: Boolean = false
        var demotedForContention: Boolean = false
        /** 实验路径使用的 AOSP 背景模糊 drawable。 */
        var selfBlur: Drawable? = null
        var windowBlurEnabled: Boolean = false
        var lastUpdateAt: Long = 0L
        var drawStreak: Int = 0
    }

    private class Config(
        val enabled: Boolean,
        val strength: Int,
        val opacity: Int,
        val timestamp: Long,
    )

    @Volatile
    private var cachedConfig: Config? = null

    /** 最近一次任意顶栏绘制的时间（用于判断"所有窗口都静止"）。 */
    private var lastGlobalActivityAt: Long = 0L

    private var pendingRestoreCheck: Runnable? = null

    /**
     * 当前唯一允许保留活跃 blur 节点的顶栏。
     *
     * 平板双栏/三栏共进程时，系统 Vulkan 驱动无法稳定同时提交多个 MIUI blur 节点；
     * 这里用一个进程级 owner 保证任意时刻只保留一个，切换栏时转移 owner 而不是两边并存。
     */
    private var ownerRef: WeakReference<ViewGroup>? = null

    private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }

    // init() 解析出的反射成员，供恢复逻辑在主线程回调里复用。
    private var fAlpha: Field? = null
    private var mSetType: Method? = null
    private var mSetMode: Method? = null
    private var mSetViewMode: Method? = null
    private var mSetGradient: Method? = null
    private var mSetScaleRatio: Method? = null
    private var mGetPrimary: Method? = null
    private var mSetPrimary: Method? = null

    private val states: MutableMap<ViewGroup, State> =
        Collections.synchronizedMap(WeakHashMap())

    /** DexKit：只解析遮罩绘制方法与遮罩 alpha 字段（此阶段不能用 [target]）。 */
    override fun initDexKit(): Boolean {
        maskPainter = optionalMember<Method>("mask_painter") { bridge ->
            val classData = bridge.getClassData(BAR) ?: throw IllegalStateException("no class data for $BAR")
            classData.methods.firstOrNull(::isMaskPainter)
                ?: throw IllegalStateException("mask painter not found")
        }
        maskAlpha = optionalMember<Field>("mask_alpha") { bridge ->
            val classData = bridge.getClassData(BAR) ?: throw IllegalStateException("no class data for $BAR")
            val painter = classData.methods.firstOrNull(::isMaskPainter)
                ?: throw IllegalStateException("mask painter not found")
            val fields = painter.usingFields.map { it.field }.filter { field ->
                field.className == BAR && field.typeName == "float" &&
                    field.writers.any { writer ->
                        writer.invokes.any {
                            it.className == VALUE_ANIMATOR && it.name == "getAnimatedValue"
                        }
                    }
            }.distinctBy { it.descriptor }
            if (fields.size != 1) throw IllegalStateException("ambiguous mask alpha: ${fields.size}")
            fields.single()
        }
        return maskPainter != null && maskAlpha != null
    }

    /**
     * 遮罩绘制方法特征：
     * - **旧版 MIUIX**：`void (Canvas)`，`Canvas.drawPath` + `ActionBarContainer.getCollapsedHeight`；
     * - **新版 MIUIX**（计算器 / 指南针等自带）：遮罩改由 `Paint` + `Path` 绘制，同样是
     *   `void (Canvas)`，但 `Canvas.drawPath` 搭配 `Path.addRect` / `Paint.setAlpha`，不再调用
     *   `getCollapsedHeight`。
     */
    protected open fun isMaskPainter(method: org.luckypray.dexkit.result.MethodData): Boolean =
        method.returnTypeName == "void" &&
            method.paramTypeNames == listOf(CANVAS) &&
            method.invokes.any { it.className == CANVAS && it.name == "drawPath" } &&
            (
                method.invokes.any { it.className == BAR && it.name == "getCollapsedHeight" } ||
                    method.invokes.any {
                        (it.className == PATH && it.name == "addRect") ||
                            (it.className == PAINT && it.name == "setAlpha")
                    }
                )

    override fun init() {
        val alphaField = maskAlpha ?: return
        val painter = maskPainter ?: return
        val loader = target.classLoader ?: return
        val barClass = Reflect.findClassIfExists(BAR, loader) ?: return

        val setType = Reflect.findMethodIfExists(View::class.java, "setMiBackgroundBlurType", Integer.TYPE)
            ?: return
        val setMode = Reflect.findMethodIfExists(View::class.java, "setMiBackgroundBlurMode", Integer.TYPE)
            ?: return
        val setViewMode = Reflect.findMethodIfExists(View::class.java, "setMiViewBlurMode", Integer.TYPE)
            ?: return
        val setGradient = Reflect.findMethodIfExists(
            View::class.java,
            "setBackgroundGradientBlurParams",
            FloatArray::class.java,
            Integer.TYPE,
        ) ?: return
        // 可选：系统同源的模糊缩放参数，部分构建可能没有该方法（自动跳过）。
        val setScaleRatio = Reflect.findMethodIfExists(
            View::class.java,
            "setMiBackgroundBlurScaleRatio",
            java.lang.Float.TYPE,
        )
        val getPrimary = Reflect.findMethodIfExists(barClass, "getPrimaryBackground")
        val setPrimary = Reflect.findMethodIfExists(barClass, "setPrimaryBackground", Drawable::class.java)
        fAlpha = alphaField
        mSetType = setType
        mSetMode = setMode
        mSetViewMode = setViewMode
        mSetGradient = setGradient
        mSetScaleRatio = setScaleRatio
        mGetPrimary = getPrimary
        mSetPrimary = setPrimary

        val painterIsOnDraw = painter.name == "onDraw" &&
            painter.parameterTypes.contentEquals(arrayOf(Canvas::class.java))

        // split 顶栏不接管（其子 View 计入测量，会撑大栏高）。
        val isSplitField = runCatching { Reflect.findField(barClass, "mIsSplit") }.getOrNull()

        val inflate = Reflect.findMethodIfExists(barClass, "onFinishInflate") ?: return
        HookHelper.intercept(inflate) { chain ->
            val result = chain.proceed()
            (chain.thisObject as? ViewGroup)?.let { bar ->
                if (isSplitField?.getBoolean(bar) != true && !isGhostBar(bar) && accepts(bar)) {
                    runCatching { ensure(bar, isSplitField != null) }
                }
            }
            result
        }

        // 嵌套滚动 = 滚动会话（含惯性）的真正起止信号：
        // 开始滚动 → 让位其它可见节点；滚动结束 → 立即恢复被让位节点（静止判定延迟为 0 且精准）。
        fun installNestedStop(method: Method?) {
            if (method == null) return
            HookHelper.intercept(method) { chain ->
                val result = chain.proceed()
                // 单 owner 模式下不在滚动结束恢复对侧节点：恢复第二个 blur 节点会
                // 再次触发 Adreno Vulkan 崩溃。owner 自身的模糊在 update 中自然重建。
                result
            }
        }
        installNestedStop(
            Reflect.findMethodIfExists(barClass, "onStopNestedScroll", View::class.java, Integer.TYPE),
        )
        installNestedStop(Reflect.findMethodIfExists(barClass, "onStopNestedScroll", View::class.java))
        Reflect.findMethodIfExists(
            barClass,
            "onNestedScrollAccepted",
            View::class.java,
            View::class.java,
            Integer.TYPE,
            Integer.TYPE,
        )?.let { accepted ->
            HookHelper.intercept(accepted) { chain ->
                val result = chain.proceed()
                (chain.thisObject as? ViewGroup)?.let { bar ->
                    val state = states[bar]
                    if (state != null && !TopBarKeys.multiBlurEnabled()) {
                        // 用户开始滚动这一栏：owner 转移给它，上一栏恢复原生遮罩。
                        forceOwner(bar)
                        state.demotedForContention = false
                        state.useOriginalMask = false
                        hideNativeMask(bar)
                        runCatching { demoteOtherActiveBars(bar, setType, setMode, setViewMode) }
                    }
                }
                result
            }
        }

        val layout = Reflect.findMethodIfExists(
            barClass,
            "onLayout",
            java.lang.Boolean.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
        ) ?: return
        HookHelper.intercept(layout) { chain ->
            val result = chain.proceed()
            (chain.thisObject as? ViewGroup)?.let { bar ->
                // 只处理已接管的 bar；blur 用 MATCH_PARENT 测量，父级已摆好，这里仅兜底。
                states[bar]?.let { state ->
                    state.blur.layout(0, 0, bar.width, bar.height)
                    runCatching {
                        update(
                            bar,
                            alphaField,
                            setType,
                            setMode,
                            setViewMode,
                            setGradient,
                            setScaleRatio,
                            getPrimary,
                            setPrimary,
                        )
                    }
                    scheduleWarmUp(bar, state, setType, setMode, setViewMode, setGradient, setScaleRatio)
                }
            }
            result
        }

        val onDraw = Reflect.findMethodIfExists(barClass, "onDraw", Canvas::class.java) ?: return
        HookHelper.intercept(onDraw) { chain ->
            val bar = chain.thisObject as? ViewGroup
            if (bar != null && states.containsKey(bar)) {
                runCatching {
                    update(
                        bar,
                        alphaField,
                        setType,
                        setMode,
                        setViewMode,
                        setGradient,
                        setScaleRatio,
                        getPrimary,
                        setPrimary,
                    )
                }
            }
            if (painterIsOnDraw && bar != null && enabled() &&
                states[bar]?.useOriginalMask != true
            ) {
                val original = runCatching { alphaField.getFloat(bar) }.getOrDefault(0f)
                try {
                    alphaField.setFloat(bar, 0f)
                    chain.proceed()
                } finally {
                    runCatching { alphaField.setFloat(bar, original) }
                }
            } else {
                chain.proceed()
            }
        }

        if (!painterIsOnDraw) {
            HookHelper.intercept(painter) { chain ->
                val bar = chain.thisObject as? ViewGroup
                if (bar != null && enabled() && states[bar]?.useOriginalMask != true) {
                    null
                } else {
                    chain.proceed()
                }
            }
        }
    }

    /** 子类可限制只接管某些 Activity 的顶栏；默认接管当前包内所有 MIUIX 顶栏。 */
    protected open fun accepts(bar: ViewGroup): Boolean = true

    /** 从 View 的 Context 链中取当前 Activity 类名（拿不到时返回 null）。 */
    protected fun activityName(view: View): String? = activityOf(view)?.javaClass?.name

    private fun activityOf(view: View): Activity? {
        var context = view.context
        while (context is ContextWrapper) {
            if (context is Activity) return context
            context = context.baseContext
        }
        return null
    }

    /** 只有笔记用 NestedHeaderLayout 的遮罩作为降级样式；其它应用用 ActionBar 自带遮罩。 */
    private fun usesNestedMask(bar: View): Boolean =
        bar.context.packageName == NOTES_PACKAGE

    private fun showNativeMask(bar: View) {
        if (usesNestedMask(bar)) NestedHeaderMaskHook.showMaskForBar(bar)
    }

    private fun hideNativeMask(bar: View) {
        if (usesNestedMask(bar)) NestedHeaderMaskHook.hideMaskForBar(bar)
    }

    /** 非 owner 栏的降级样式：透明度渐变（默认）或什么都不显示。 */
    private fun applyFallback(bar: ViewGroup, state: State) {
        if (TopBarKeys.multiBlurFallbackMask()) {
            state.useOriginalMask = true
            showNativeMask(bar)
        } else {
            state.useOriginalMask = false
            hideNativeMask(bar)
        }
    }

    /**
     * AOSP `BackgroundBlurDrawable` 只有在窗口允许 blur-behind 时才会真正采样背景；
     * 否则它只会画出黑色遮罩。MIUI 的 `setMiBackgroundBlurMode` 不需要这个窗口标志。
     */
    private fun enableWindowBlur(bar: View, state: State) {
        if (state.windowBlurEnabled) return
        val activity = activityOf(bar) ?: return
        runCatching {
            val window = activity.window
            val attrs = window.attributes
            attrs.flags = attrs.flags or android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND
            attrs.blurBehindRadius = (bar.height / 3).coerceAtLeast(20)
            window.attributes = attrs
            state.windowBlurEnabled = true
        }.onFailure { HookHelper.log("TopBarGradient: enable window blur failed", it) }
    }

    /** 导航转场 ghost 快照里也会挂 ActionBarContainer，不应成为 blur owner。 */
    private fun isGhostBar(bar: View): Boolean {
        var parent: ViewParent? = bar.parent
        while (parent is View) {
            if (parent.id != View.NO_ID) {
                val name = runCatching {
                    parent.resources.getResourceEntryName(parent.id)
                }.getOrNull()
                if (name in GHOST_VIEW_IDS) return true
            }
            parent = parent.parent
        }
        return false
    }

    private fun ensure(bar: ViewGroup, matchParent: Boolean): State = states[bar] ?: run {
        val blur = View(bar.context).apply {
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            visibility = View.INVISIBLE
        }
        // 有 mIsSplit 字段的构建用 MATCH_PARENT（父级直接摆到整栏，避免每帧尺寸变化触发 requestLayout 死循环）。
        val size = if (matchParent) ViewGroup.LayoutParams.MATCH_PARENT else 0
        bar.addView(blur, 0, ViewGroup.LayoutParams(size, size))
        State(blur).also { states[bar] = it }
    }

    @Suppress("LongParameterList")
    private fun update(
        bar: ViewGroup,
        alphaField: Field,
        setType: Method,
        setMode: Method,
        setViewMode: Method,
        setGradient: Method,
        setScaleRatio: Method?,
        getPrimary: Method?,
        setPrimary: Method?,
    ) {
        val state = states[bar] ?: return
        // 被让位的节点在恢复前不再参与绘制/让位判定，避免双栏两侧每帧互相 demote。
        if (state.demotedForContention) return
        // 非活跃栏交还原生 MIUIX 渐变遮罩绘制；不要继续改 alpha/primary。
        if (state.useOriginalMask) return
        val now = SystemClock.uptimeMillis()
        val previousDrawAt = state.lastUpdateAt
        state.lastUpdateAt = now
        // 连续绘制计数：帧间隔小于 CONTINUOUS_DRAW_MS 视为"本窗口正在持续出帧"。
        state.drawStreak = if (now - previousDrawAt <= CONTINUOUS_DRAW_MS) state.drawStreak + 1 else 0
        if (!enabled()) {
            restore(bar, state, getPrimary, setPrimary)
            hide(bar, state, setType, setMode, setViewMode)
            return
        }
        ensureOriginal(bar, state)
        if (getPrimary != null && setPrimary != null) {
            runCatching { if (getPrimary.invoke(bar) != null) setPrimary.invoke(bar, null) }
        }
        state.maskRestored = false
        hideNativeMask(bar)

        val conf = config()
        val strength = conf.strength.coerceIn(0, 100)
        val opacity = conf.opacity.coerceIn(0, 100)
        val fraction = runCatching { alphaField.getFloat(bar) }.getOrDefault(0f).coerceIn(0f, 1f)
        // 滚动收缩期 MIUIX 顶栏高度可能逐帧变化；把参与模糊几何的高度量化到步长，
        // 避免每帧重设厂商模糊管线（重设会触发 invalidateViewProperty 与渲染节点更新）。
        val density = bar.resources.displayMetrics.density
        val heightStep = (HEIGHT_STEP_DP * density).roundToInt().coerceAtLeast(MIN_HEIGHT_STEP_PX)
        val quantizedHeight = ((bar.height + heightStep / 2) / heightStep) * heightStep
        val radius = Math.min(strength * density, quantizedHeight * 0.5f)
        // 常驻模式：直接使用配置不透明度（等同本模块页面的 ScrollFadeDistance=0）；否则跟随原生遮罩。
        val alpha = (if (alwaysBlur) 1f else fraction) * opacity / 100f
        if (radius <= 0f || bar.height <= 0) {
            hide(bar, state, setType, setMode, setViewMode)
            return
        }
        if (alpha <= 0f) {
            // 预热帧尚未结束时不打断它（预热只画一帧极低透明度，随后自行淡出）。
            if (state.warmingUp) return
            // 滚动会频繁把遮罩 alpha 压到 0：先淡出；持续归零 RELEASE_DELAY_MS 后再释放模糊管线
            // （释放重置 lastRadius，模糊再次出现时自动重建），避免与底栏渐变竞态。
            if (state.lastAlpha != 0f) {
                state.blur.alpha = 0f
                state.lastAlpha = 0f
            }
            scheduleRelease(bar, state, setType, setMode, setViewMode)
            return
        }
        if (SELF_BLUR_EXPERIMENT) {
            applySelfBlur(bar, state, radius, alpha)
            return
        }
        // 双栏/三栏共进程时只允许一个活跃 blur 节点；第一个真正有 alpha 的栏成为 owner，
        // 其它栏立即让位（下一帧释放，避免在 RenderThread 提交期间销毁节点）。
        if (!claimOwner(bar)) {
            state.demotedForContention = true
            applyFallback(bar, state)
            bar.post {
                if (state.demotedForContention && bar.isAttachedToWindow) {
                    runCatching { hide(bar, state, setType, setMode, setViewMode) }
                }
            }
            return
        }
        cancelRelease(bar, state)
        if (state.lastRadius != radius || state.lastHeight != quantizedHeight) {
            setMode.invoke(state.blur, 1)
            setViewMode.invoke(state.blur, 1)
            setType.invoke(state.blur, 2)
            setGradient.invoke(state.blur, floatArrayOf(0f, 0f, radius, 0f, quantizedHeight.toFloat(), 0f), 1)
            // 系统 UI 实时表面同源的缩放参数（SystemUI 取 0.05~0.075），降低模糊渲染分辨率。
            setScaleRatio?.let { runCatching { it.invoke(state.blur, BLUR_SCALE_RATIO) } }
            state.lastRadius = radius
            state.lastHeight = quantizedHeight
        }
        val steppedAlpha = (alpha * ALPHA_STEPS).roundToInt() / ALPHA_STEPS
        // 本窗口正在淡入淡出（滚动交互）时，让位其它仍可见的节点，避免两侧同时活跃。
        if (steppedAlpha > 0f && state.lastAlpha != steppedAlpha) {
            demoteOtherActiveBars(bar, setType, setMode, setViewMode)
        }
        // 连续绘制（滚动/动画中）同样让位其它可见节点——覆盖"alpha 已稳定但仍在滑动"的场景。
        if (state.drawStreak >= ACTIVE_STREAK && state.lastAlpha > 0f) {
            demoteOtherActiveBars(bar, setType, setMode, setViewMode)
        }
        if (state.lastAlpha != steppedAlpha) {
            state.blur.alpha = steppedAlpha
            state.lastAlpha = steppedAlpha
        }
        if (state.blur.visibility != View.VISIBLE) {
            demoteOtherActiveBars(bar, setType, setMode, setViewMode)
            state.demotedForContention = false
            state.blur.visibility = View.VISIBLE
        }
    }

    private fun restore(bar: ViewGroup, state: State, getPrimary: Method?, setPrimary: Method?) {
        if (!state.cleared) return
        if (getPrimary != null && setPrimary != null) {
            runCatching { if (getPrimary.invoke(bar) == null) setPrimary.invoke(bar, state.original) }
        }
        state.cleared = false
        state.original = null
        state.maskRestored = true
    }

    /** 缓存 hook 前的 MIUIX 渐变遮罩；后续被让位时用它降级显示。 */
    private fun ensureOriginal(bar: ViewGroup, state: State) {
        if (state.cleared) return
        state.original = runCatching { mGetPrimary?.invoke(bar) as? Drawable }.getOrNull()
        state.cleared = true
    }

    private fun hide(bar: ViewGroup, state: State, setType: Method, setMode: Method, setViewMode: Method) {
        cancelRelease(bar, state)
        // MIUIX 的渐变遮罩只在「没有非透明主背景」时才绘制；这里保持 primary 为 null，
        // 让 onDraw 的原生遮罩接管，而不是恢复主背景把遮罩挡掉。
        if (enabled()) {
            ensureOriginal(bar, state)
            runCatching { mSetPrimary?.invoke(bar, null) }
        }
        if (state.useOriginalMask) showNativeMask(bar)
        if (SELF_BLUR_EXPERIMENT) {
            state.lastAlpha = 0f
            if (state.blur.visibility != View.INVISIBLE) state.blur.visibility = View.INVISIBLE
            // 移除 BackgroundBlurDrawable，让 ViewRootImpl 的 Aggregator 注销该模糊区域。
            if (state.blur.background != null) state.blur.background = null
            state.selfBlur = null
            state.lastRadius = -1f
            state.lastHeight = -1
            return
        }
        if (state.lastRadius >= 0f) {
            // 转场/窗口销毁时厂商方法可能抛异常；让位失败不应让目标进程崩溃。
            runCatching {
                setType.invoke(state.blur, 0)
                setViewMode.invoke(state.blur, 0)
                setMode.invoke(state.blur, 0)
            }
            state.lastRadius = -1f
            state.lastHeight = -1
        }
        state.lastAlpha = 0f
        if (state.blur.visibility != View.INVISIBLE) state.blur.visibility = View.INVISIBLE
    }

    /**
     * 实验路径：不使用 MIUI `setMiBackgroundBlurMode` 的 RenderNode blur 节点，
     * 改用 AOSP `View.setBackgroundBlur`（`BackgroundBlurDrawable`）。
     *
     * 后者的 `ViewRootImpl.Aggregator` 用 ArraySet 聚合同一窗口的多个模糊区域，
     * 理论上允许一窗多块；这里先验证多栏同时存在时 Adreno Vulkan 是否仍会崩。
     */
    private fun applySelfBlur(bar: ViewGroup, state: State, radius: Float, alpha: Float) {
        enableWindowBlur(bar, state)
        val r = radius.roundToInt().coerceAtLeast(1)
        if (state.selfBlur == null) {
            val drawable = runCatching {
                val viewRoot = Reflect.callMethod(bar, "getViewRootImpl")
                    ?: throw IllegalStateException("ViewRootImpl is null")
                Reflect.callMethod(viewRoot, "createBackgroundBlurDrawable") as? Drawable
                    ?: throw IllegalStateException("createBackgroundBlurDrawable is null")
            }.getOrElse {
                HookHelper.log("TopBarGradient: createBackgroundBlurDrawable failed", it)
                return
            }
            state.selfBlur = drawable
            // 这条路径只画实时模糊；颜色保持透明，避免 AOSP drawable 的遮罩路径被画成黑块。
            runCatching { Reflect.callMethod(drawable, "setColor", 0) }
            runCatching { Reflect.callMethod(drawable, "setBlurRadius", r) }
            state.blur.background = drawable
            state.lastRadius = r.toFloat()
        } else if (state.lastRadius != r.toFloat()) {
            runCatching { Reflect.callMethod(state.selfBlur!!, "setBlurRadius", r) }
            state.lastRadius = r.toFloat()
        }
        val stepped = (alpha.coerceIn(0f, 1f) * ALPHA_STEPS).roundToInt() / ALPHA_STEPS
        if (state.lastAlpha != stepped) {
            state.blur.alpha = stepped
            state.lastAlpha = stepped
        }
        if (state.blur.visibility != View.VISIBLE) state.blur.visibility = View.VISIBLE
    }

    /** 遮罩持续归零后延迟释放模糊管线；期间重新出现（alpha > 0）则取消。 */
    private fun scheduleRelease(
        bar: ViewGroup,
        state: State,
        setType: Method,
        setMode: Method,
        setViewMode: Method,
    ) {
        if (state.lastRadius < 0f || state.pendingRelease != null) return
        val hostRef = WeakReference(bar)
        val runnable = Runnable {
            state.pendingRelease = null
            val host = hostRef.get() ?: return@Runnable
            if (state.lastAlpha == 0f && enabled()) {
                runCatching { hide(host, state, setType, setMode, setViewMode) }
            }
        }
        state.pendingRelease = runnable
        bar.postDelayed(runnable, RELEASE_DELAY_MS)
    }

    private fun cancelRelease(bar: ViewGroup, state: State) {
        val pending = state.pendingRelease ?: return
        state.pendingRelease = null
        bar.removeCallbacks(pending)
    }

    /** 页面完成布局后投递一次预热（每个 bar 只做一次，真实使用过则跳过）。 */
    private fun scheduleWarmUp(
        bar: ViewGroup,
        state: State,
        setType: Method,
        setMode: Method,
        setViewMode: Method,
        setGradient: Method,
        setScaleRatio: Method?,
    ) {
        if (state.warmedUp || state.lastRadius >= 0f) return
        state.warmedUp = true
        bar.post {
            runCatching { warmUp(bar, state, setType, setMode, setViewMode, setGradient, setScaleRatio) }
                .onFailure { state.warmedUp = false }
        }
    }

    /**
     * 以 [WARMUP_ALPHA] 的透明度绘制一帧渐变模糊，提前完成首次管线 / 程序创建；
     * 随后 [endWarmUp] 只把透明度归零、保留配置，等待真实滚动接管或空闲释放。
     */
    private fun warmUp(
        bar: ViewGroup,
        state: State,
        setType: Method,
        setMode: Method,
        setViewMode: Method,
        setGradient: Method,
        setScaleRatio: Method?,
    ) {
        if (SELF_BLUR_EXPERIMENT) {
            // BackgroundBlurDrawable 没有独立管线需要预热；避免走厂商 blur 初始化。
            state.warmedUp = true
            return
        }
        if (!enabled()) {
            state.warmedUp = false
            return
        }
        val conf = config()
        if (conf.strength <= 0 || conf.opacity <= 0) {
            state.warmedUp = false
            return
        }
        // 双栏/三栏启动时只允许 owner 预热，否则多个 blur 节点会在首帧同时创建，
        // Adreno Vulkan 驱动会在 RenderThread 提交阶段崩溃。
        if (!claimOwner(bar)) {
            state.warmedUp = true
            state.demotedForContention = true
            state.blur.visibility = View.INVISIBLE
            return
        }
        val height = bar.height
        if (height <= 0 || state.lastRadius >= 0f) {
            state.warmedUp = false
            return
        }
        val density = bar.resources.displayMetrics.density
        val heightStep = (HEIGHT_STEP_DP * density).roundToInt().coerceAtLeast(MIN_HEIGHT_STEP_PX)
        val quantizedHeight = ((height + heightStep / 2) / heightStep) * heightStep
        val radius = Math.min(conf.strength * density, quantizedHeight * 0.5f)
        if (radius <= 0f) {
            state.warmedUp = false
            return
        }
        setMode.invoke(state.blur, 1)
        setViewMode.invoke(state.blur, 1)
        setType.invoke(state.blur, 2)
        setGradient.invoke(state.blur, floatArrayOf(0f, 0f, radius, 0f, quantizedHeight.toFloat(), 0f), 1)
        setScaleRatio?.let { runCatching { it.invoke(state.blur, BLUR_SCALE_RATIO) } }
        state.warmingUp = true
        state.blur.alpha = WARMUP_ALPHA
        state.blur.visibility = View.VISIBLE
        state.lastRadius = radius
        state.lastHeight = quantizedHeight
        state.lastAlpha = WARMUP_ALPHA
        bar.postDelayed({ endWarmUp(state) }, WARMUP_VISIBLE_MS)
    }

    private fun endWarmUp(state: State) {
        state.warmingUp = false
        // 真实模糊（或被其它路径改动过）已接管时不回收。
        if (state.lastAlpha != WARMUP_ALPHA) return
        state.blur.alpha = 0f
        state.lastAlpha = 0f
        if (state.blur.visibility != View.INVISIBLE) state.blur.visibility = View.INVISIBLE
    }

    /** 申领唯一 blur owner；owner 已失效或就是自己时成功。 */
    @Synchronized
    private fun claimOwner(bar: ViewGroup): Boolean {
        // 调试选项：放开 owner 限制，允许同窗口所有栏同时保留真实模糊。
        if (TopBarKeys.multiBlurEnabled()) return true
        val owner = ownerRef?.get()
        if (owner == null || owner === bar || !owner.isAttachedToWindow) {
            ownerRef = WeakReference(bar)
            return true
        }
        return false
    }

    /** 滚动会话开始时强制把 owner 转给当前栏。 */
    @Synchronized
    private fun forceOwner(bar: ViewGroup) {
        ownerRef = WeakReference(bar)
    }

    /**
     * 释放非 owner 的其它模糊节点，保证同一进程任意时刻只有一个活跃 blur 节点。
     * 切换栏时由 [forceOwner] 转移 owner，不在滚动结束恢复旧节点。
     */
    private fun demoteOtherActiveBars(
        current: ViewGroup,
        setType: Method,
        setMode: Method,
        setViewMode: Method,
    ) {
        if (TopBarKeys.multiBlurEnabled()) return
        val snapshot = synchronized(states) { states.entries.toList() }
        for (entry in snapshot) {
            if (entry.key === current) continue
            val other = entry.value
            if (other.lastAlpha <= 0f) continue
            other.demotedForContention = true
            // 按配置决定让位栏恢复原生渐变，或保持空白。
            applyFallback(entry.key, other)
            val host = entry.key
            // 不要在 onDraw 调用栈里同步切换厂商 blur 模式：RenderThread 可能仍在
            // 提交上一帧，Vulkan 驱动会把已释放的 blur 节点当作有效资源（SIGSEGV）。
            host.post {
                if (!other.demotedForContention || !host.isAttachedToWindow) return@post
                runCatching { hide(host, other, setType, setMode, setViewMode) }
                HookHelper.log("TopBarGradient: demote other active blur bar (hash=${host.hashCode()})")
            }
        }
    }

    /** 其它窗口是否仍在持续绘制（近期有绘制且模糊可见）。 */
    private fun hasRecentlyDrawingOtherBar(current: ViewGroup, now: Long): Boolean =
        synchronized(states) {
            states.any { (bar, state) ->
                bar !== current && state.lastAlpha > 0f && now - state.lastUpdateAt <= ACTIVE_DRAW_MS
            }
        }

    /**
     * 兜底恢复检查（主路径由嵌套滚动结束信号精确触发）：
     * 连续 [RESTORE_QUIET_CHECKS] 次检查内都没有出现新的绘制帧即视为已停下并恢复；
     * 只要还在出帧就继续按 [RESTORE_RECHECK_MS] 顺延。
     */
    private fun scheduleRestoreCheck() {
        if (pendingRestoreCheck != null) return
        val check = object : Runnable {
            private var lastSeenActivity = -1L
            private var quietChecks = 0
            override fun run() {
                pendingRestoreCheck = null
                val activity = lastGlobalActivityAt
                if (lastSeenActivity == -1L || activity != lastSeenActivity) {
                    // 首次检查总是先顺延一拍；之后只要有新帧就继续观察并清零静默计数。
                    lastSeenActivity = activity
                    quietChecks = 0
                    pendingRestoreCheck = this
                    mainHandler.postDelayed(this, RESTORE_RECHECK_MS)
                    return
                }
                quietChecks++
                if (quietChecks < RESTORE_QUIET_CHECKS) {
                    pendingRestoreCheck = this
                    mainHandler.postDelayed(this, RESTORE_RECHECK_MS)
                    return
                }
                restoreDemotedBars()
            }
        }
        pendingRestoreCheck = check
        mainHandler.postDelayed(check, RESTORE_FIRST_DELAY_MS)
    }

    /** 所有窗口都静止后，把被让位的节点按当前滚动状态恢复显示。 */
    private fun restoreDemotedBars() {
        pendingRestoreCheck?.let { mainHandler.removeCallbacks(it) }
        pendingRestoreCheck = null
        val alphaField = fAlpha ?: return
        val setType = mSetType ?: return
        val setMode = mSetMode ?: return
        val setViewMode = mSetViewMode ?: return
        val setGradient = mSetGradient ?: return
        val setScaleRatio = mSetScaleRatio
        val conf = config()
        val snapshot = synchronized(states) { states.entries.toList() }
        for ((bar, state) in snapshot) {
            if (!state.demotedForContention) continue
            state.demotedForContention = false
            if (!conf.enabled || conf.strength <= 0 || conf.opacity <= 0) continue
            if (bar.height <= 0 || !bar.isAttachedToWindow) continue
            val fraction = runCatching { alphaField.getFloat(bar) }.getOrDefault(0f).coerceIn(0f, 1f)
            val alpha = (if (alwaysBlur) 1f else fraction) * conf.opacity / 100f
            if (alpha <= 0f) continue
            val density = bar.resources.displayMetrics.density
            val heightStep = (HEIGHT_STEP_DP * density).roundToInt().coerceAtLeast(MIN_HEIGHT_STEP_PX)
            val quantizedHeight = ((bar.height + heightStep / 2) / heightStep) * heightStep
            val radius = Math.min(conf.strength.coerceIn(0, 100) * density, quantizedHeight * 0.5f)
            if (radius <= 0f) continue
            runCatching {
                setMode.invoke(state.blur, 1)
                setViewMode.invoke(state.blur, 1)
                setType.invoke(state.blur, 2)
                setGradient.invoke(state.blur, floatArrayOf(0f, 0f, radius, 0f, quantizedHeight.toFloat(), 0f), 1)
                setScaleRatio?.let { it.invoke(state.blur, BLUR_SCALE_RATIO) }
                val steppedAlpha = (alpha * ALPHA_STEPS).roundToInt() / ALPHA_STEPS
                state.blur.alpha = steppedAlpha
                state.lastAlpha = steppedAlpha
                state.lastRadius = radius
                state.lastHeight = quantizedHeight
                state.blur.visibility = View.VISIBLE
            }.onFailure { runCatching { hide(bar, state, setType, setMode, setViewMode) } }
        }
    }

    protected open fun enabled(): Boolean = config().enabled

    /** 配置快照：远程偏好读取按 [CONFIG_TTL_MS] 节流，热路径（每帧 onDraw）只读内存。 */
    private fun config(): Config {
        val now = SystemClock.uptimeMillis()
        val cached = cachedConfig
        if (cached != null && now - cached.timestamp < CONFIG_TTL_MS) return cached
        val fresh = Config(
            enabled = HookPrefs.getBoolean(TopBarKeys.KEY, false),
            strength = prefInt(TopBarKeys.STRENGTH, 10),
            opacity = prefInt(TopBarKeys.OPACITY, 100),
            timestamp = now,
        )
        cachedConfig = fresh
        return fresh
    }

    /** 滑块以 Float 存储，优先按 Float 读取，兼容整数存储。 */
    private fun prefInt(key: String, default: Int): Int {
        val value = HookPrefs.getFloat(key, Float.NaN)
        if (!value.isNaN()) return value.roundToInt()
        return HookPrefs.getInt(key, default)
    }

    private companion object {
        const val BAR = "miuix.appcompat.internal.app.widget.ActionBarContainer"
        const val CANVAS = "android.graphics.Canvas"
        const val PATH = "android.graphics.Path"
        const val PAINT = "android.graphics.Paint"
        const val VALUE_ANIMATOR = "android.animation.ValueAnimator"

        /** 与 SystemUI 实时表面同源的模糊缩放值（0.05~0.075 区间取上沿）。 */
        const val BLUR_SCALE_RATIO = 0.075f

        /** 遮罩持续归零后的模糊管线释放延迟（放长，避免来回滚动时反复拆除/重建）。 */
        const val RELEASE_DELAY_MS = 8_000L

        /** 配置快照有效期（读数节流）。 */
        const val CONFIG_TTL_MS = 1_000L

        /** 模糊几何量化步长（dp）：滚动收缩期栏高变化小于该步长时不重设模糊参数。 */
        const val HEIGHT_STEP_DP = 8f

        /** 量化步长下限（px），避免低密度设备步长过小。 */
        const val MIN_HEIGHT_STEP_PX = 16

        /** 预热帧透明度：几乎不可见，仅用于驱动首次模糊管线创建。 */
        const val WARMUP_ALPHA = 0.02f

        /** 预热帧保持时长。 */
        const val WARMUP_VISIBLE_MS = 64L

        /** 跟随遮罩的透明度量化级数（视觉连续，减少无意义的合成更新）。 */
        const val ALPHA_STEPS = 64f

        /** 笔记使用 NestedHeaderLayout 的原生渐变遮罩作为降级样式。 */
        const val NOTES_PACKAGE = "com.miui.notes"

        /**
         * 实验开关：用 AOSP BackgroundBlurDrawable 替代 MIUI RenderNode blur。
         *
         * 实测（笔记 3.2.7.9 / HyperOS 4）：应用窗口没有系统级 blur-behind 授权时，
         * `BackgroundBlurDrawable` 只画出黑色遮罩，不会采样背景；即使补上
         * `FLAG_BLUR_BEHIND` 也不会生效。因此默认关闭，保留 MIUI 原生模糊的单 owner 方案。
         */
        const val SELF_BLUR_EXPERIMENT = false

        /** 导航转场 ghost 快照的容器 id；其中的 ActionBar 不参与 blur。 */
        val GHOST_VIEW_IDS = setOf("ghost_nav_view", "ghost_clip_view", "ghost_fading_mask_view")

        /** 判定"另一侧窗口仍在持续绘制"的时间窗。 */
        const val ACTIVE_DRAW_MS = 800L

        /** 恢复检查的重试间隔。 */
        const val RESTORE_RECHECK_MS = 40L

        /** 恢复检查的首次延迟（首次检查总是先顺延一拍再判断）。 */
        const val RESTORE_FIRST_DELAY_MS = 0L

        /**
         * 兜底静默检查次数（×[RESTORE_RECHECK_MS] ≈ 1 秒）：
         * 正常路径由嵌套滚动结束信号精确恢复；没有该信号的页面用这个兜底。
         */
        const val RESTORE_QUIET_CHECKS = 25

        /** 帧间隔小于该值视为同一段连续绘制。 */
        const val CONTINUOUS_DRAW_MS = 40L

        /** 连续绘制达到该帧数说明窗口正在滚动/动画，需要让位其它节点。 */
        const val ACTIVE_STREAK = 8
    }
}
