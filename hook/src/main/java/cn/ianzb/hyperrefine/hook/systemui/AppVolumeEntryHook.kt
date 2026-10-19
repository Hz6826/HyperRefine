package cn.ianzb.hyperrefine.hook.systemui

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.widget.ImageView
import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.misound.AppVolumeKeys
import cn.ianzb.hyperrefine.hook.prefs.HookPrefs
import cn.ianzb.hyperrefine.hook.status.HookStatusReporter
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.Reflect
import java.util.WeakHashMap

/**
 * 侧边音量条「多应用音量」入口。
 *
 * 直接复用官方静音 / 勿扰按钮的同款实现：inflate 官方 `miui_ringer_mode_layout` 布局，
 * 并用官方 `MiuiRingerModeLayout.RingerButtonHelper` 管理其背景 / 模糊 / 图标 / 展开尺寸，
 * 再把它接进 `VolumeShowHideAnimator` 的 `mRingerBtnLayouts`，使其拥有与原生按钮完全一致
 * 的外观与「自上而下递增延迟」的出现动画。
 *
 * 点击后拉起 MiSound 的 `VolumeUIService` 打开原生多应用音量面板。
 */
class AppVolumeEntryHook : BaseHook() {

    override val key: String = AppVolumeKeys.ENTRY

    /** 每个 ringer 父容器对应的入口视图。 */
    private val entries = WeakHashMap<ViewGroup, View>()

    /** 每个官方 `MiuiRingerModeLayout` 对应的入口 RingerButtonHelper。 */
    private val helpers = WeakHashMap<Any, Any>()

    /** 入口最近一次的显示状态，避免重复播放透明度动画。 */
    private val lastVisible = WeakHashMap<View, Boolean>()

    /** 整体居中平移：面板最近一次的上移目标，避免重复播放动画。 */
    private val lastShift = WeakHashMap<View, Float>()

    /** 本轮侧边音量条显示期间，用户是否手动操作过入口（手动操作后不再自动展开面板）。 */
    private val userToggled = WeakHashMap<View, Boolean>()

    /** 入口期望的显隐状态（延迟显隐时用于重放）。 */
    private val desiredVisible = WeakHashMap<View, Boolean>()

    /** 延迟显隐的等待中标记，避免重复排队。 */
    private val pendingShow = WeakHashMap<View, Boolean>()

    /** 「自动显示」重试中由本流程刷新入口状态，避免入口变化再次触发自动展开（递归）。 */
    private var autoRefreshing = false

    // 插件资源 / 类（loadPlugin 时解析）
    private var idDnd = 0
    private var idBlur = 0
    private var idIcon = 0
    private var layoutRingerButton = 0
    private var outerClass: Class<*>? = null
    private var helperClass: Class<*>? = null

    /** 控制中心插件 ClassLoader（用于系统界面模式反射官方 VolumeColumn）。 */
    private var pluginClassLoader: ClassLoader? = null

    /** 最近的音量面板控制器，供注入后立即刷新显隐。 */
    @Volatile
    private var lastController: Any? = null

    override fun init() {
        // 本 hook 同时实现「入口常显」，据实上报其生效状态。
        HookStatusReporter.markInstalled(AppVolumeKeys.ALWAYS_SHOW)
        val systemCl = target.classLoader ?: return
        PluginLoader.register(key, systemCl) { pluginCl ->
            loadPlugin(pluginCl)
            hookAnimator(pluginCl)
            hookRingerLayout(pluginCl)
            hookController(pluginCl)
            hookSeekBarAnim(pluginCl)
            hookRingerButtonDelay(pluginCl)
        }
    }

    // ---------------- 插件资源 / 类 ----------------

    private fun loadPlugin(pluginCl: ClassLoader) {
        pluginClassLoader = pluginCl
        outerClass = Reflect.findClassIfExists(OUTER_CLASS, pluginCl)
        helperClass = Reflect.findClassIfExists(HELPER_CLASS, pluginCl)
    }

    /** 插件资源 id 通过运行时资源解析（不同 ROM 版本数值会变，不能写死）。 */
    private fun resolveResources(context: Context) {
        if (idDnd != 0) return
        idDnd = resolveId(context, "dnd_layout")
        idBlur = resolveId(context, "bg_blur")
        idIcon = resolveId(context, "icon")
        layoutRingerButton = resolveRes(context, "miui_ringer_mode_layout", "layout")
        HookHelper.log("$tag: ids dnd=$idDnd blur=$idBlur icon=$idIcon layout=$layoutRingerButton")
    }

    private fun resolveId(context: Context, name: String): Int {
        for (pkg in RES_PACKAGES + context.packageName) {
            val id = runCatching { context.resources.getIdentifier(name, "id", pkg) }.getOrDefault(0)
            if (id != 0) return id
        }
        return 0
    }

    private fun resolveRes(context: Context, name: String, type: String): Int {
        for (pkg in RES_PACKAGES + context.packageName) {
            val id = runCatching { context.resources.getIdentifier(name, type, pkg) }.getOrDefault(0)
            if (id != 0) return id
        }
        return 0
    }

    // ---------------- 注入入口（在其出现动画的 animator 初始化时） ----------------

    private fun hookAnimator(pluginCl: ClassLoader) {
        val cls = Reflect.findClassIfExists(ANIMATOR_CLASS, pluginCl)
        if (cls == null) {
            HookHelper.log("$tag: $ANIMATOR_CLASS not found")
            return
        }
        cls.declaredMethods.filter { it.name == "initView" }.forEach { method ->
            method.isAccessible = true
            runCatching {
                HookHelper.hookAfter(method) { param ->
                    val volumeView = param.args.getOrNull(0) as? View ?: return@hookAfter
                    val animator = param.thisObject ?: return@hookAfter
                    runCatching { injectEntry(volumeView, animator) }
                        .onFailure { HookHelper.log("$tag: inject entry failed", it) }
                }
            }.onFailure { HookHelper.log("$tag: hook initView failed", it) }
        }
    }

    private fun injectEntry(volumeView: View, animator: Any) {
        resolveResources(volumeView.context)
        if (layoutRingerButton == 0) {
            HookHelper.log("$tag: ringer button layout not resolved")
            return
        }
        val dnd = (if (idDnd != 0) volumeView.findViewById<View>(idDnd) else null)
            ?: findDndRoot(volumeView)
            ?: return
        val parent = dnd.parent as? ViewGroup ?: return
        val existing = findEntry(parent)
        val entry = existing ?: run {
            val dndIndex = parent.indexOfChild(dnd)
            var insertAt = dndIndex + 1
            // 官方两个按钮之间有一个分隔 View（间距）。新增入口前补一个同样的分隔，
            // 保证三个按钮间距一致（否则会少一段间距、排版错位）。
            val divider = siblingDivider(parent, dnd)
            if (divider != null && findTaggedChild(parent, TAG_DIVIDER) == null) {
                val div = View(parent.context)
                div.layoutParams = copyLayoutParams(divider)
                div.tag = TAG_DIVIDER
                parent.addView(div, insertAt.coerceIn(0, parent.childCount))
                insertAt++
            }
            val inflated = runCatching {
                LayoutInflater.from(volumeView.context).inflate(layoutRingerButton, parent, false)
            }.getOrNull() ?: return
            inflated.tag = TAG_ENTRY
            inflated.isClickable = true
            inflated.isFocusable = true
            parent.addView(inflated, insertAt.coerceIn(0, parent.childCount))
            entries[parent] = inflated
            setupEntryBehavior(inflated)
            HookHelper.log("$tag: entry injected into ${parent.javaClass.simpleName}")
            attachDividerSync(parent)
            inflated
        }
        joinAnimator(animator, entry)
        lastController?.let { updateVisibility(it, entry) }
    }

    /** 仅当入口尚未加入动画数组时，把入口与对应的 X 记录一起追加。 */
    private fun joinAnimator(animator: Any, entry: View) {
        val already = runCatching {
            val field = animator.javaClass.getDeclaredField("mRingerBtnLayouts")
            field.isAccessible = true
            val current = field.get(animator) as? Array<*> ?: return@runCatching false
            current.any { it === entry }
        }.getOrDefault(false)
        if (already) return
        extendAnimator(animator, "mRingerBtnLayouts", entry)
        extendAnimator(animator, "ringerBtnLayoutsX", 0f)
    }

    /** 用官方 RingerButtonHelper 接管入口外观，并替换图标与点击回调。 */
    private fun setupEntryBehavior(entry: View) {
        val outer = ancestorOfType(entry, outerClass) ?: run {
            HookHelper.log("$tag: outer MiuiRingerModeLayout not found")
            return
        }
        runCatching {
            val ctor = helperClass?.declaredConstructors?.firstOrNull { it.parameterCount == 4 }
                ?: return@runCatching
            ctor.isAccessible = true
            val helper = ctor.newInstance(outer, entry, false, false)
            helpers[outer] = helper
            helperUpdateState(helper)
        }.onFailure { HookHelper.log("$tag: create RingerButtonHelper failed", it) }

        // 覆盖为模块图标。
        runCatching {
            val icon = entry.findViewById<ImageView>(idIcon)
            loadModuleIcon(entry.context)?.let { icon?.setImageDrawable(it) }
            icon?.imageTintList = ColorStateList.valueOf(Color.WHITE)
        }.onFailure { HookHelper.log("$tag: set entry icon failed", it) }

        // 覆盖点击（官方 helper 会把点击接到静音 / 勿扰切换，这里改为打开多应用音量）。
        entry.setOnClickListener { onEntryClick(entry) }
        runCatching {
            val blur = entry.findViewById<View>(idBlur)
            blur?.setOnClickListener { onEntryClick(entry) }
        }
    }

    /** 把入口追加进原生动画数组，使其拥有与原生按钮一致的延迟出现动画。 */
    private fun extendAnimator(animator: Any, fieldName: String, extra: Any) {
        runCatching {
            val field = animator.javaClass.getDeclaredField(fieldName)
            field.isAccessible = true
            val current = field.get(animator) ?: return
            val length = java.lang.reflect.Array.getLength(current)
            if ((0 until length).any { java.lang.reflect.Array.get(current, it) === extra }) return
            val component = current.javaClass.componentType
            val grown = java.lang.reflect.Array.newInstance(component, length + 1)
            for (i in 0 until length) java.lang.reflect.Array.set(grown, i, java.lang.reflect.Array.get(current, i))
            java.lang.reflect.Array.set(grown, length, extra)
            field.set(animator, grown)
        }.onFailure { HookHelper.log("$tag: extend $fieldName failed", it) }
    }

    // ---------------- 跟随官方展开 / 收起状态 ----------------

    private fun hookRingerLayout(pluginCl: ClassLoader) {
        val cls = outerClass ?: return
        val methods = setOf(
            "init",
            "updateExpandedH",
            "updateExpandedStateH",
            "updateResources",
            "prepareCollapsedBlurForShowAnimation",
        )
        cls.declaredMethods.filter { it.name in methods }.forEach { method ->
            method.isAccessible = true
            runCatching {
                HookHelper.hookAfter(method) { param ->
                    val outer = param.thisObject ?: return@hookAfter
                    val helper = helpers[outer] ?: return@hookAfter
                    runCatching {
                        when (method.name) {
                            "updateExpandedH" -> helperOnExpanded(
                                helper,
                                param.args.getOrNull(0) as? Boolean ?: false,
                                false,
                            )
                            "prepareCollapsedBlurForShowAnimation" -> helperOnExpanded(helper, false, true)
                        }
                        helperUpdateState(helper)
                    }.onFailure { HookHelper.log("$tag: update entry helper failed", it) }
                }
            }.onFailure { HookHelper.log("$tag: hook ${method.name} failed", it) }
        }
    }

    private fun helperOnExpanded(helper: Any, expanded: Boolean, force: Boolean) {
        helper.javaClass
            .getDeclaredMethod("onExpanded", Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .invoke(helper, expanded, force)
    }

    private fun helperUpdateState(helper: Any) {
        helper.javaClass
            .getDeclaredMethod("updateState")
            .apply { isAccessible = true }
            .invoke(helper)
    }

    // ---------------- 边界（最大/最小继续调整）动画 ----------------

    /**
     * 音量到达最大/最小继续调整时，原生通过 `MiuiVolumeSeekBar` 的
     * `SeekBarAnimListener`（`SlideContainerAnim.AnimListener`）对静音 / 勿扰按钮的
     * `bg_blur` 做上下平移 + 缩放。这里包裹该监听器，使入口的 `bg_blur` 同步同一变换，
     * 从而拥有完全一致的边界动画。
     */
    private fun hookSeekBarAnim(pluginCl: ClassLoader) {
        val sliderClass = Reflect.findClassIfExists(SEEK_BAR_CLASS, pluginCl) ?: return
        val iface = Reflect.findClassIfExists(ANIM_LISTENER_IFACE, pluginCl) ?: return
        sliderClass.declaredMethods
            .filter { it.name == "setSeekBarAnimListener" && it.parameterCount == 1 }
            .forEach { method ->
                method.isAccessible = true
                runCatching {
                    HookHelper.hookBefore(method) { param ->
                        val slider = param.thisObject as? View ?: return@hookBefore
                        val delegate = param.args.getOrNull(0) ?: return@hookBefore
                        val proxy = runCatching { wrapAnimListener(iface, delegate, slider) }.getOrNull()
                            ?: return@hookBefore
                        param.setArg(0, proxy)
                    }
                }.onFailure { HookHelper.log("$tag: hook setSeekBarAnimListener failed", it) }
            }
    }

    private fun wrapAnimListener(iface: Class<*>, delegate: Any, slider: View): Any =
        java.lang.reflect.Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { _, method, args ->
            val result = runCatching { method.invoke(delegate, *(args ?: emptyArray<Any?>())) }.getOrNull()
            runCatching { mirrorAnim(slider, method.name, args) }
            result
        }

    /** 把原生对按钮 `bg_blur` 的平移 / 缩放增量同步到入口的 `bg_blur`。 */
    private fun mirrorAnim(slider: View, name: String, args: Array<out Any?>?) {
        if (idBlur == 0) return
        // 多应用音量面板的滑条不参与镜像，否则其边缘动画会带着入口 / 勿扰按钮一起动。
        if (slider.tag == OfficialVolumeColumnFactory.SLIDER_TAG) return
        val entry = findEntry(slider.rootView) ?: return
        if (entry.visibility != View.VISIBLE) return
        val blur = entry.findViewById<View>(idBlur) ?: return
        when (name) {
            // 只跟随勿扰按钮（紧邻入口）的平移，避免 setRingerY + setDndY 叠加导致幅度翻倍。
            "setDndY" -> {
                val before = args?.getOrNull(0) as? Float ?: return
                val current = args.getOrNull(1) as? Float ?: return
                blur.translationY = (current - before) + blur.translationY
            }
            "setScale" -> {
                val before = args?.getOrNull(0) as? Float ?: return
                val current = args.getOrNull(1) as? Float ?: return
                val delta = current - before
                blur.scaleX += delta
                blur.scaleY += delta
            }
            "resetView" -> {
                blur.translationY = 0f
                blur.scaleX = 1f
                blur.scaleY = 1f
            }
        }
    }

    /**
     * 追加的入口按钮在官方「静音 / 勿扰」之后出现。
     *
     * 官方 `VolumeShowHideAnimatorKt.createRingerButtonArgs` 只区分 index==0（30ms）与其余（50ms），
     * 因此入口（index≥2）与勿扰（index==1）延迟相同、几乎同步。这里对 index≥2 继续递增延迟，
     * 使其保持「自上而下递增」的出现节奏。
     */
    private fun hookRingerButtonDelay(pluginCl: ClassLoader) {
        val cls = Reflect.findClassIfExists(RINGER_BUTTON_ARGS_CLASS, pluginCl) ?: return
        val method = cls.declaredMethods.firstOrNull {
            it.name == "createRingerButtonArgs" && it.parameterCount == 3
        } ?: return
        method.isAccessible = true
        runCatching {
            HookHelper.hookAfter(method) { param ->
                // 第 1 个参数为「展开 / 出现」标志；关闭动画本无延迟，不能动。
                if (param.args.getOrNull(0) as? Boolean != true) return@hookAfter
                val index = param.args.getOrNull(1) as? Int ?: return@hookAfter
                if (index < 2) return@hookAfter
                val args = param.result ?: return@hookAfter
                // index 1 为 50ms；index ≥2 每级 +20ms。
                val delayMs = (0.05 + 0.02 * (index - 1)) * 1000.0
                runCatching { Reflect.callMethod(args, "setDelayX", delayMs.toLong()) }
            }
        }.onFailure { HookHelper.log("$tag: hook ringer button delay failed", it) }
    }

    // ---------------- 控制器：显隐 / 上移 ----------------

    private fun hookController(pluginCl: ClassLoader) {
        val controller = Reflect.findClassIfExists(CONTROLLER_CLASS, pluginCl)
        if (controller == null) {
            HookHelper.log("$tag: $CONTROLLER_CLASS not found")
            return
        }
        controller.declaredMethods
            .filter { it.name in CONTROLLER_METHODS }
            .forEach { method ->
                method.isAccessible = true
                runCatching {
                    HookHelper.hookAfter(method) { param ->
                        param.thisObject?.let {
                            lastController = it
                            onController(it, method.name)
                        }
                    }
                }.onFailure { HookHelper.log("$tag: hook ${method.name} failed", it) }
            }
    }

    private fun onController(controller: Any, methodName: String) {
        // 侧边音量条关闭时同步关闭系统界面模式面板，避免再次打开时残留 / 位置错乱。
        if (methodName == "dismissH") runCatching { AppVolumePanel.hide() }
        runCatching {
            val ringer = findRinger(controller) ?: return
            val entry = findEntry(ringer) ?: return
            (entry.parent as? ViewGroup)?.let { parent -> parent.post { syncDivider(parent) } }
            if (!HookPrefs.getBoolean(key, false)) {
                applyEntryState(entry, false)
                shiftPanel(entry, false)
                return
            }
            updateVisibility(controller, entry)
            // 侧边音量条真正显示时，按需自动展开多应用音量面板。
            if (methodName == "showVolumePanelH") maybeAutoShow(controller, entry)
        }.onFailure { HookHelper.log("$tag: onController failed", it) }
    }

    private fun findRinger(controller: Any): View? {
        callAny(controller, listOf("getRingerModeLayout", "getVolumeRingerModeLayout"))
            ?.let { return it as? View }
        val delegate = runCatching { Reflect.callMethod(controller, "getDelegate") }.getOrNull()
        if (delegate != null) {
            callAny(delegate, listOf("getRingerModeLayout", "getVolumeRingerModeLayout"))
                ?.let { return it as? View }
        }
        return null
    }

    private fun callAny(target: Any, names: List<String>): Any? {
        for (name in names) {
            runCatching { Reflect.callMethod(target, name) }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun findEntry(root: View): View? {
        if (root.tag == TAG_ENTRY) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                findEntry(root.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    /** 找到紧邻按钮之前的官方「分隔 View」（用于复制间距）。 */
    private fun siblingDivider(parent: ViewGroup, button: View): View? {
        val index = parent.indexOfChild(button)
        for (i in index - 1 downTo 0) {
            val child = parent.getChildAt(i)
            if (child.javaClass.name == "android.view.View") return child
            if (child is ViewGroup) break
        }
        return null
    }

    /**
     * 让入口前的分隔 View 与官方分隔完全同步。
     *
     * 官方分隔的高度在布局完成后才会被最终确定（展开 / 收起、双列等会变），
     * 注入时拷贝到的还是初始值，故需在布局后及状态回调里再次同步尺寸。
     */
    private val dividerSyncAttached = WeakHashMap<ViewGroup, Boolean>()

    /** 每帧同步分隔尺寸，直到入口销毁；保证与官方分隔（其高度随状态变化）始终一致。 */
    private fun attachDividerSync(parent: ViewGroup) {
        if (dividerSyncAttached[parent] == true) return
        dividerSyncAttached[parent] = true
        parent.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                syncDivider(parent)
                return true
            }
        })
        parent.post { syncDivider(parent) }
    }

    private fun syncDivider(parent: ViewGroup) {
        runCatching {
            val clone = findTaggedChild(parent, TAG_DIVIDER) ?: return@runCatching
            val cloneIndex = parent.indexOfChild(clone)
            if (cloneIndex <= 0) return@runCatching
            val dnd = parent.getChildAt(cloneIndex - 1)
            val official = siblingDivider(parent, dnd) ?: return@runCatching
            if (official === clone) return@runCatching
            val src = official.layoutParams ?: return@runCatching
            val lp = clone.layoutParams ?: return@runCatching
            if (lp.width != src.width || lp.height != src.height) {
                lp.width = src.width
                lp.height = src.height
                clone.layoutParams = lp
            }
            // 分隔尺寸最终确定后，按真实多出高度重新校正整体平移量。
            findEntry(parent)?.let { entry ->
                shiftPanel(entry, lastVisible[entry] ?: true)
            }
        }.onFailure { HookHelper.log("$tag: syncDivider failed", it) }
    }

    private fun copyLayoutParams(src: View): ViewGroup.LayoutParams {
        val lp = src.layoutParams ?: return ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        return if (lp is ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams(lp.width, lp.height).apply {
                leftMargin = lp.leftMargin
                topMargin = lp.topMargin
                rightMargin = lp.rightMargin
                bottomMargin = lp.bottomMargin
                marginStart = lp.marginStart
                marginEnd = lp.marginEnd
            }
        } else {
            ViewGroup.LayoutParams(lp.width, lp.height)
        }
    }

    private fun findTaggedChild(parent: ViewGroup, tag: String): View? {
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (child.tag == tag) return child
        }
        return null
    }

    /** 结构兜底：返回最后一个「音量按钮根」（即勿扰 dnd_layout 的根视图）。 */
    private fun findDndRoot(root: View): View? {
        var lastParent: View? = null
        fun visit(v: View) {
            if (v.tag == TAG_ENTRY) return
            if (v.javaClass.name.contains("VolumeBlurFrameLayout")) {
                lastParent = v.parent as? View
                return
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) visit(v.getChildAt(i))
        }
        visit(root)
        return lastParent
    }

    private fun ancestorOfType(view: View, cls: Class<*>?): Any? {
        val type = cls ?: return null
        var parent: ViewParent? = view.parent
        while (parent != null) {
            if (type.isInstance(parent)) return parent
            parent = (parent as? View)?.parent
        }
        return null
    }

    // ---------------- 显示 ----------------

    /**
     * 入口出现 / 消失时，把整条侧边音量条（含音量列）向上平移「多出高度的一半」，
     * 使整体视觉中心（平均高度）不变；隐藏时平移回原位。
     *
     * 「是否启用自动平衡高度」（竖屏 / 横屏分别控制）关闭时不平移，保持原高度 / 位置。
     */
    private fun shiftPanel(entry: View, visible: Boolean) {
        val parent = entry.parent as? ViewGroup ?: return
        val panel = findVolumeDialog(entry) ?: return
        val extra = runCatching {
            val entryHeight = if (entry.height > 0) {
                entry.height
            } else {
                resolveDimen(entry.resources, entry.context.packageName, "o3_miui_ringer_btn_height", entry.context.dp(48))
            }
            val dividerHeight = findTaggedChild(parent, TAG_DIVIDER)?.height ?: 0
            entryHeight + dividerHeight
        }.getOrDefault(0)
        if (extra <= 0) return
        val landscape = entry.resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val balanceKey = if (landscape) {
            VolumeBarKeys.AUTO_BALANCE_LANDSCAPE
        } else {
            VolumeBarKeys.AUTO_BALANCE_PORTRAIT
        }
        // 自动平衡：显示入口时上移「多出高度的一半」，保持整条音量条平均高度不变；
        // 关闭后不平移，高度 / 位置保持原样。
        val autoBalance = HookPrefs.getBoolean(balanceKey, true)
        // 平移落点取整到物理像素：整条内容做非整数像素平移时会被 GPU 重采样，
        // 观感即「整条渲染分辨率下降 / 发虚」，因此半高度为奇数时向上取整。
        val target = if (visible && autoBalance) -((extra + 1) / 2).toFloat() else 0f
        // 平移**整个窗口内容**（`mVolumePanelView`：对话框 `MiuiVolumeDialogView` 的父容器）：
        // 它与音量条对话框、悬浮百分比（`miui_super_volume_*`）并列包含在同一父容器下，
        // 只有平移该父容器才能让二者一起移动，避免只移动对话框导致悬浮百分比错位。
        val shiftRoot = panel.parent as? View ?: panel
        if (lastShift[shiftRoot] == target) return
        lastShift[shiftRoot] = target
        shiftRoot.animate()
            .translationY(target)
            .setDuration(SHIFT_DURATION_MS)
            .setInterpolator(android.view.animation.AccelerateDecelerateInterpolator())
            .start()
    }

    /** 找到整条侧边音量条容器（`MiuiVolumeDialogView`）。 */
    private fun findVolumeDialog(view: View): View? {
        var parent: ViewParent? = view.parent
        while (parent != null) {
            if (parent is View && parent.javaClass.name.contains("MiuiVolumeDialogView")) return parent
            parent = (parent as? View)?.parent
        }
        return null
    }

    private fun applyEntryState(entry: View, visible: Boolean, dodge: Boolean = false, deferCount: Int = 0) {
        desiredVisible[entry] = visible
        // 入口隐藏（展开态 / 面板收起）时同步关闭系统界面模式的面板，避免残留。
        // `dodge=true`（侧边二级面板展开）时用「避让」消失动画，其余情况原地淡出。
        if (!visible) {
            pendingShow[entry] = false
            runCatching { if (dodge) AppVolumePanel.hideByExpand() else AppVolumePanel.hide() }
        }
        if (lastVisible[entry] == visible) return
        if (visible) {
            // 官方出现 / 收起形变动画会缩放整条内容并锁定几何参数；此时改变窗口高度（入口显隐）
            // 会打断动画状态机，可能让整条内容停留在异常渲染状态。等动画结束再做显隐切换。
            if (panelAnimating(entry) && deferCount < MAX_DEFER_FRAMES) {
                if (pendingShow[entry] != true) {
                    pendingShow[entry] = true
                    entry.postDelayed({
                        pendingShow[entry] = false
                        applyEntryState(entry, desiredVisible[entry] ?: true, deferCount = deferCount + 1)
                    }, DEFER_WAIT_MS)
                }
                return
            }
            pendingShow[entry] = false
        }
        lastVisible[entry] = visible
        if (visible) {
            // 先让入口以完全透明参与布局，等父容器 / 窗口按新高度完成一次测量后再播出现动画：
            // 官方音量条窗口的高度跟随内容，淡入期间窗口尚未长高时入口会被裁掉一截（表现为「截断」）。
            entry.visibility = View.VISIBLE
            entry.alpha = 0f
            whenLaidOut(entry) { showEntry(entry) }
        } else {
            // 新一轮显示周期：清除上一轮的用户手动操作标记。
            userToggled.remove(entry)
            // 整条音量条先平移回原位（此时入口仍在布局中，窗口高度未收缩），淡出结束后再收起入口，
            // 避免淡出过程中窗口已经变矮、入口被裁掉一截。
            shiftPanel(entry, false)
            entry.animate().alpha(0f).setDuration(FADE_DURATION_MS).withEndAction {
                if (lastVisible[entry] == false) {
                    entry.visibility = View.GONE
                    entry.alpha = 1f
                }
            }.start()
        }
        HookHelper.log("$tag: entry visible=$visible")
    }

    /** 官方出现 / 收起动画是否仍在播放（`MiuiVolumeDialogMotion.mShowHideAnimator.isRunning()`）。 */
    private fun panelAnimating(entry: View): Boolean = runCatching {
        val panel = findVolumeDialog(entry) ?: return false
        val motion = Reflect.getObjectField(panel, "mMotion") ?: return false
        val animator = Reflect.getObjectField(motion, "mShowHideAnimator") ?: return false
        (Reflect.callMethod(animator, "isRunning") as? Boolean) ?: false
    }.getOrDefault(false)

    /** 入口完成一次测量后再执行 [action]（带上限，取不到尺寸时也会放行）。 */
    private fun whenLaidOut(entry: View, frames: Int = 0, action: (View) -> Unit) {
        if (entry.height > 0 || frames >= MAX_LAYOUT_WAIT_FRAMES || !entry.isAttachedToWindow) {
            action(entry)
            return
        }
        entry.post { whenLaidOut(entry, frames + 1, action) }
    }

    /** 播放入口出现动画；入口在本次显示周期内出现时同样按「自动显示」展开多应用音量面板。 */
    private fun showEntry(entry: View) {
        if (lastVisible[entry] != true) return
        // 清掉上一次淡出挂的结束动作，避免复用时在本次动画结束时误收起入口。
        entry.animate().alpha(1f).withEndAction(null).setDuration(FADE_DURATION_MS).start()
        shiftPanel(entry, true)
        if (!autoRefreshing) lastController?.let { maybeAutoShow(it, entry) }
    }

    private fun updateVisibility(controller: Any, entry: View) {
        val expanded = runCatching { Reflect.getObjectField(controller, "mExpanded") }.getOrNull() as? Boolean ?: false
        // 读后台探测快照：官方判定内部会同步调用音频服务，通话等场景单次可阻塞数秒，主线程不能直接调。
        val visible = !expanded &&
            (HookPrefs.getBoolean(AppVolumeKeys.ALWAYS_SHOW, false) ||
                AppVolumePlayback.snapshot().hasActive)
        applyEntryState(entry, visible, dodge = expanded)
        // 快照过期时请一次后台刷新；结果回来（播放状态可能已变化）再重算一次入口状态。
        AppVolumePlayback.refresh(entry.context) { lastController?.let { updateVisibility(it, entry) } }
    }

    /**
     * 「自动显示」：侧边音量条显示时，若开关开启且入口可见（有媒体 / 常显），自动展开面板。
     * 入口按钮的点击开关逻辑不变；已展开时、或用户本轮手动操作过入口后，不再自动展开。
     */
    private fun maybeAutoShow(controller: Any, entry: View) {
        if (!canAutoShow(entry)) return
        if (AppVolumePanel.isShowing()) return
        val root = entry.rootView as? ViewGroup ?: return
        val dialog = findVolumeDialog(entry) ?: return
        // 延后到本轮布局之后，确保侧边音量条已就位再取坐标 / 展开；再补一次延迟重试，
        // 覆盖「官方的正在播放应用列表在音量条展开后才刷新出来」的情形。
        entry.post { tryAutoShow(controller, entry, root, dialog) }
        entry.postDelayed({ tryAutoShow(controller, entry, root, dialog) }, AUTO_SHOW_RETRY_MS)
    }

    private fun tryAutoShow(controller: Any, entry: View, root: ViewGroup, dialog: View) {
        if (!canAutoShow(entry)) return
        if (AppVolumePanel.isShowing()) return
        if (lastVisible[entry] != true) {
            // 入口还没出现：按最新的「正在播放的应用」重算一次入口状态
            // （应用可能刚刚才开始播放 / 官方列表刚刷新出来）。
            autoRefreshing = true
            try {
                runCatching { updateVisibility(controller, entry) }
            } finally {
                autoRefreshing = false
            }
        }
        if (lastVisible[entry] != true) return
        // 两次延迟重试之间侧边条可能已经收回，避免在隐藏的 dialog 上创建面板。
        if (!dialog.isAttachedToWindow || !dialog.isShown) return
        runCatching { AppVolumePanel.show(root, dialog, entry.context, pluginClassLoader, entry) }
            .onFailure { HookHelper.log("$tag: auto show panel failed", it) }
    }

    /** 是否允许自动展开面板：开关开启，且用户本轮显示期间没有手动操作过入口。 */
    private fun canAutoShow(entry: View): Boolean =
        HookPrefs.getBoolean(AppVolumeKeys.AUTO_SHOW, false) && userToggled[entry] != true

    private fun onEntryClick(entry: View) {
        HookHelper.log("$tag: entry clicked")
        // 用户手动操作过入口（展开 / 关闭）后，本轮显示周期内不再自动展开。
        userToggled[entry] = true
        // 仅系统界面模式：由系统界面自行渲染面板（数值转发到「音质音效」生效 / 同步）。
        val root = entry.rootView as? ViewGroup
        val dialog = findVolumeDialog(entry)
        if (root != null && dialog != null) {
            runCatching { AppVolumePanel.toggle(root, dialog, entry.context, pluginClassLoader, entry) }
                .onFailure { HookHelper.log("$tag: toggle systemui panel failed", it) }
        }
    }

    // ---------------- 资源 ----------------

    private fun loadModuleIcon(context: Context): android.graphics.drawable.Drawable? = runCatching {
        val module = context.createPackageContext(
            AppVolumeKeys.MODULE_PACKAGE,
            Context.CONTEXT_IGNORE_SECURITY,
        )
        val id = module.resources.getIdentifier(
            AppVolumeKeys.ICON_NAME,
            "drawable",
            AppVolumeKeys.MODULE_PACKAGE,
        )
        if (id != 0) module.getDrawable(id) else null
    }.getOrNull()

    private fun resolveDimen(
        res: android.content.res.Resources,
        pkg: String,
        name: String,
        fallback: Int,
    ): Int {
        val id = runCatching { res.getIdentifier(name, "dimen", pkg) }.getOrDefault(0)
        return if (id != 0) runCatching { res.getDimensionPixelSize(id) }.getOrDefault(fallback) else fallback
    }

    private fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val CONTROLLER_CLASS =
            "com.android.systemui.miui.volume.VolumePanelViewController"
        private const val ANIMATOR_CLASS =
            "com.android.systemui.miui.volume.VolumeShowHideAnimator"
        private const val OUTER_CLASS =
            "com.android.systemui.miui.volume.MiuiRingerModeLayout"
        private const val SEEK_BAR_CLASS =
            "com.android.systemui.miui.volume.MiuiVolumeSeekBar"
        private const val ANIM_LISTENER_IFACE =
            "com.android.systemui.miui.volume.MiuiVolumeSeekBar\$SeekBarAnimListener"
        private const val RINGER_BUTTON_ARGS_CLASS =
            "com.android.systemui.miui.volume.VolumeShowHideAnimatorKt"
        private const val HELPER_CLASS =
            "com.android.systemui.miui.volume.MiuiRingerModeLayout\$RingerButtonHelper"

        /** 资源解析候选包名（插件资源可能挂在其中任一包下）。 */
        private val RES_PACKAGES = listOf("miui.systemui.plugin", "com.android.systemui")

        private const val TAG_ENTRY = "tag_app_volume_entry_root"
        private const val TAG_DIVIDER = "tag_app_volume_entry_divider"

        /** 面板上移 / 复位动画时长。 */
        private const val SHIFT_DURATION_MS = 250L

        /** 入口出现 / 消失的透明度动画时长。 */
        private const val FADE_DURATION_MS = 250L

        /** 「自动显示」的延迟重试间隔：等官方的正在播放应用列表刷新后再判定一次。 */
        private const val AUTO_SHOW_RETRY_MS = 300L

        /** 入口出现动画前，等待其完成布局的最大帧数。 */
        private const val MAX_LAYOUT_WAIT_FRAMES = 5

        /** 官方形变动画播放期间，延迟入口显隐的轮询间隔。 */
        private const val DEFER_WAIT_MS = 120L

        /** 延迟入口显隐的最大轮询次数（约 4.8s，超时后照常切换）。 */
        private const val MAX_DEFER_FRAMES = 40

        private val CONTROLLER_METHODS =
            setOf("showH", "showVolumePanelH", "prepareShow", "updateExpandedH", "dismissH")
    }
}
