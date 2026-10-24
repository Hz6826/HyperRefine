package cn.ianzb.hyperrefine.hook.systemui.statusbar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import cn.ianzb.hyperrefine.hook.prefs.HookPrefs
import cn.ianzb.hyperrefine.hook.xposed.Reflect
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.IdentityHashMap
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * 状态栏图标的运行时协调器。
 *
 * 只操作 SystemUI 已有的 native View / `MiuiStatusIconContainer` 的 ignoredSlots，
 * 不创建悬浮窗、不复制图标资源、不替换 SystemUI 的图标数据源。
 */
internal object StatusBarIconRuntime {

    private val hosts = WeakHashMap<ViewGroup, Host>()
    private val iconGroups = mutableListOf<WeakReference<ViewGroup>>()
    private val batteryViews = mutableListOf<WeakReference<View>>()
    private val groupHidden = WeakHashMap<View, Boolean>()

    /** 注册所有场景的 MiuiClock；1s ticker 直接调用官方 updateTime，让当前场景自行格式化。 */
    private val clockViews = WeakHashMap<TextView, Boolean>()
    private val clockHandler = Handler(Looper.getMainLooper())
    private var clockTickerRunning = false
    private val clockTicker = object : Runnable {
        override fun run() {
            if (!clockTickerRunning) return
            if (!HookPrefs.getBoolean(StatusBarIconKeys.ENABLED, false)) {
                clockTickerRunning = false
                return
            }
            val force = HookPrefs.getBoolean(StatusBarIconKeys.CLOCK_REFRESH, false)
            clockViews.keys.toList().forEach { view ->
                if (!view.isAttachedToWindow) return@forEach
                val pattern = clockPattern(view.context, view.id)
                val needsSeconds = pattern?.any { it == 's' || it == 'S' } == true
                if (force || needsSeconds) {
                    val updated = runCatching {
                        Reflect.callMethod(view, "updateTime")
                        true
                    }.getOrDefault(false)
                    if (!updated) {
                        clockText(view.context, view.id)?.let { text ->
                            if (!TextUtils.equals(view.text, text)) view.text = text
                        }
                        applyClockWeight(view)
                        applyClockLockWidth(view)
                    }
                }
            }
            clockHandler.postDelayed(this, 1000L)
        }
    }

    /** 字重覆盖前的原始 Typeface；只在当前进程内保留弱引用。 */
    private val originalTypefaces = WeakHashMap<TextView, Typeface?>()
    private val originalVariations = WeakHashMap<TextView, String?>()
    private val originalTextSizes = WeakHashMap<TextView, Float>()
    private val originalClockTexts = WeakHashMap<TextView, CharSequence?>()
    private val speedStyles = WeakHashMap<View, SpeedStyleState>()
    private val speedSlowStates = WeakHashMap<View, Boolean>()
    private val notificationDotStates = WeakHashMap<View, DotDrawState>()
    private val clockWidths = WeakHashMap<TextView, Int>()
    private val idCache = HashMap<String, Int>()
    private var restoringClock = false

    /** 最近一次 Controller；用于配置变化后不重启 SystemUI 直接重排视图。 */
    private var controllerRef: WeakReference<Any>? = null
    private var nativeSlotOrder: List<String>? = null
    private val orderHandler = Handler(Looper.getMainLooper())
    private var lastLiveOrder: String? = null
    private var rebuildingGroups = false
    private var refreshPending = false
    private val liveOrderRunnable = Runnable { reorderLive() }
    private val refreshRunnable = Runnable {
        refreshPending = false
        refreshAll()
    }

    fun attach(root: ViewGroup) {
        synchronized(hosts) {
            hosts[root]?.refresh()
                ?: runCatching { Host(root).also { hosts[root] = it } }
                    .onFailure { android.util.Log.w("HyperRefineStatusBar", "attach failed", it) }
        }
    }

    /** 注册控制器创建的每个图标组；控制中心 fake 组也必须能按自定义条件直接隐藏。 */
    fun registerIconGroup(group: ViewGroup?) {
        group ?: return
        iconGroups.removeAll { it.get() == null || it.get() === group }
        iconGroups += WeakReference(group)
        // 重建/首次添加后宿主可能还没重新测量子 View；MiuiStatusIconContainer 的
        // onLayout 依赖子 View 实际宽度，必须显式请求一次布局，否则会沿用 0 宽度位置。
        group.requestLayout()
        group.post { group.requestLayout() }
    }

    /** 电池在状态栏与控制中心可能由不同 View 承载，单独登记以便统一切换可见性。 */
    fun registerBatteryView(view: View?) {
        view ?: return
        batteryViews.removeAll { it.get() == null || it.get() === view }
        batteryViews += WeakReference(view)
    }

    fun onPreferencesChanged() {
        // 滑块/输入框会连续写偏好；合并成一次刷新，避免拖动时反复全量遍历宿主。
        if (!refreshPending) {
            refreshPending = true
            orderHandler.postDelayed(refreshRunnable, 60L)
        }
        scheduleLiveOrder()
    }

    private fun scheduleLiveOrder() {
        if (controllerRef?.get() == null) return
        orderHandler.removeCallbacks(liveOrderRunnable)
        orderHandler.postDelayed(liveOrderRunnable, 120L)
    }

    fun refreshAll() {
        ensureClockTicker()
        if (!HookPrefs.getBoolean(StatusBarIconKeys.ENABLED, false)) {
            restoreClocks()
        } else {
            refreshRegisteredClocks()
        }
        val snapshot = synchronized(hosts) { hosts.values.toList() }
        snapshot.forEach { it.postRefresh() }
    }

    fun refreshSoon(root: ViewGroup? = null) {
        if (root == null) {
            refreshAll()
        } else {
            root.post { hosts[root]?.refresh() }
        }
    }

    /** 配置变化时让已注册的场景时钟重跑官方 updateTime；清空格式后能立即回退原生。 */
    private fun refreshRegisteredClocks() {
        clockViews.keys.toList().forEach { view ->
            if (view.isAttachedToWindow) {
                runCatching { Reflect.callMethod(view, "updateTime") }
            }
        }
    }

    /** 在 Controller 首次 addIconGroup 前排序 slot，保证 IconManager 的索引与视图顺序一致。 */
    fun sortSlots(controller: Any) {
        controllerRef = WeakReference(controller)
        captureNativeOrder(controller)
        if (!HookPrefs.getBoolean(StatusBarIconKeys.ENABLED, false)) return
        val snapshot = StatusBarIconConfigReader.load()
        if (!snapshot.enabled || snapshot.order.isEmpty()) return
        val componentOrder = snapshot.reorderableOrder()
        val ordered = componentOrder.map(::slotNameForId)
        if (ordered.isEmpty()) return
        runCatching {
            val list = Reflect.getObjectField(controller, "mStatusBarIconList") ?: return
            // mViewOnlySlots 可能是 Collections.unmodifiableList，不能 clear/add；
            // 直接整体替换成新的 ArrayList。mSlots 也必须一起重排，
            // 因为 addIconGroup 用 getViewIndex() 从 mSlots 计算 onIconAdded 的插入索引。
            val slotsBefore = (Reflect.getObjectField(list, "mSlots") as? List<Any>)?.toList()
            val viewsBefore = (Reflect.getObjectField(list, "mViewOnlySlots") as? List<Any>)?.toList()
            var changed = reorderAndReplace(list, "mSlots", ordered)
            changed = reorderAndReplace(list, "mViewOnlySlots", ordered) || changed
            if (!listMatches(list, "mSlots", ordered) ||
                !listMatches(list, "mViewOnlySlots", ordered)
            ) {
                // 任一列表未写入成功都会让 addIconGroup 的索引与 View 顺序错位，
                // 直接恢复原生顺序，宁可不排序也不能把图标挤在一起。
                slotsBefore?.let { restoreList(list, "mSlots", it) }
                viewsBefore?.let { restoreList(list, "mViewOnlySlots", it) }
                return
            }
            if (changed) {
                lastLiveOrder = componentOrder.joinToString(",")
                // 仅当 slot 顺序确实变化且已有图标组时重建；首次排序发生在建组前，
                // 无条件重建会造成启动阶段图标闪空。
                rebuildGroups(controller)
            }
        }.onFailure {
            android.util.Log.w("HyperRefineStatusBar", "sort slots failed", it)
        }
    }

    private fun captureNativeOrder(controller: Any) {
        if (nativeSlotOrder != null) return
        runCatching {
            val list = Reflect.getObjectField(controller, "mStatusBarIconList") ?: return
            val raw = Reflect.getObjectField(list, "mViewOnlySlots") as? List<*> ?: return
            nativeSlotOrder = raw.mapNotNull { slot -> slot?.let(::slotName) }
        }
    }

    private fun slotName(slot: Any): String? =
        runCatching { Reflect.getObjectField(slot, "mName") as? String }.getOrNull()
            ?: runCatching { Reflect.getObjectField(slot, "name") as? String }.getOrNull()

    /** 组件 ID 不一定等于原生 slot，例如 alarm 的 slot 是 alarm_clock。 */
    private fun slotNameForId(id: String): String =
        StatusBarIconRegistry.byId(id)?.slot ?: id

    /**
     * 只交换命中顺序的 slot 在数组中的彼此位置，未暴露的 slot 保持原索引；
     * 这样实时重排不会把 volume / sound_box 等未管理图标整体推走。
     */
    private fun reorderList(source: List<Any>, ordered: List<String>): List<Any> {
        val result = source.toMutableList()
        val index = ordered.withIndex().associate { it.value to it.index }
        val expose = ordered.toSet()
        val positions = source.indices.filter { slotName(source[it]) in expose }
        val sorted = positions.map { source[it] }.sortedBy { index[slotName(it)] ?: Int.MAX_VALUE }
        positions.forEachIndexed { i, position -> result[position] = sorted[i] }
        return result
    }

    private fun restoreList(target: Any, fieldName: String, values: List<Any>) {
        runCatching {
            Reflect.findField(target.javaClass, fieldName).set(target, ArrayList(values))
        }
    }

    private fun reorderAndReplace(target: Any, fieldName: String, ordered: List<String>): Boolean {
        val source = Reflect.getObjectField(target, fieldName) as? List<Any>
            ?: throw IllegalStateException("$fieldName is not a list")
        val after = reorderList(source, ordered)
        if (source.mapNotNull(::slotName) == after.mapNotNull(::slotName)) return false
        val field = Reflect.findField(target.javaClass, fieldName)
        // mSlots 是宿主持有的 ArrayList，原地重排可保留引用；mViewOnlySlots 可能是
        // 过滤/不可变视图，清空它可能连带清空 mSlots，因此只做整体替换。
        val mutable = source as? MutableList<Any>
        if (fieldName == "mSlots" && mutable != null) {
            runCatching {
                mutable.clear()
                mutable.addAll(after)
            }
        }
        if (Reflect.getObjectField(target, fieldName)
                .let { (it as? List<Any>)?.mapNotNull(::slotName) } != after.mapNotNull(::slotName)
        ) {
            field.set(target, ArrayList(after))
        }
        return true
    }

    private fun reorderLive() {
        val controller = controllerRef?.get() ?: return
        if (!HookPrefs.getBoolean(StatusBarIconKeys.ENABLED, false)) {
            // 关闭总开关时把已经实时重排过的视图恢复到启动时捕获的原生顺序。
            if (lastLiveOrder != null) {
                val native = nativeSlotOrder.orEmpty()
                if (native.isNotEmpty()) {
                    runCatching { reorderControllerViews(controller, native) }
                    lastLiveOrder = null
                }
            }
            return
        }
        val snapshot = StatusBarIconConfigReader.load()
        val desired = if (snapshot.order.isEmpty()) {
            nativeSlotOrder.orEmpty()
        } else {
            snapshot.reorderableOrder()
        }
        if (desired.isEmpty()) return
        val key = desired.joinToString(",")
        if (key == lastLiveOrder) return
        runCatching {
            if (reorderControllerViews(controller, desired)) lastLiveOrder = key
        }.onFailure {
            android.util.Log.w("HyperRefineStatusBar", "live order failed", it)
        }
    }

    /**
     * 已存在的图标组必须按新 slot 顺序重建，否则列表索引和 View 顺序会错位。
     * 走官方 remove/add，避免直接搬 View 触发退场动画。
     */
    private fun rebuildGroups(controller: Any) {
        if (rebuildingGroups) return
        val groups = Reflect.getObjectField(controller, "mIconGroups") as? List<*> ?: return
        if (groups.isEmpty()) return
        rebuildingGroups = true
        try {
            groups.toList().forEach { manager ->
                if (manager == null) return@forEach
                Reflect.callMethod(controller, "removeIconGroup", manager)
                Reflect.callMethod(controller, "addIconGroup", manager)
            }
        } finally {
            rebuildingGroups = false
        }
    }

    private fun reorderControllerViews(controller: Any, desired: List<String>): Boolean {
        val list = Reflect.getObjectField(controller, "mStatusBarIconList") ?: return false
        val desiredSlots = desired.map(::slotNameForId)
        val slotsBefore = (Reflect.getObjectField(list, "mSlots") as? List<Any>)?.toList()
        val viewsBefore = (Reflect.getObjectField(list, "mViewOnlySlots") as? List<Any>)?.toList()
        // 索引和视图顺序必须同时成功；字段不可写时失败关闭，绝不能只挪 View。
        reorderAndReplace(list, "mViewOnlySlots", desiredSlots)
        reorderAndReplace(list, "mSlots", desiredSlots)
        if (!listMatches(list, "mViewOnlySlots", desiredSlots) ||
            !listMatches(list, "mSlots", desiredSlots)
        ) {
            slotsBefore?.let { restoreList(list, "mSlots", it) }
            viewsBefore?.let { restoreList(list, "mViewOnlySlots", it) }
            return false
        }
        rebuildGroups(controller)
        return true
    }

    private fun listMatches(target: Any, fieldName: String, desired: List<String>): Boolean {
        val names = (Reflect.getObjectField(target, fieldName) as? List<Any>)
            ?.mapNotNull(::slotName) ?: return false
        val expose = desired.toSet()
        return names.filter { it in expose } == desired.filter { it in names }
    }

    /** 刷新间隔只改控制器下一次采样 delay；显示层不再二次节流。 */
    fun applyNetworkSpeed(view: View) {
        if (!HookPrefs.getBoolean(StatusBarIconKeys.ENABLED, false)) return
        applyNetworkSpeed(view, StatusBarIconConfigReader.loadComponent(StatusBarIconRegistry.NETWORK_SPEED))
    }

    fun applyNetworkSpeed(view: View, config: StatusBarIconComponentConfig) {
        applySpeedContent(view, config)
        applySpeedFontSize(view, config)
        applySpeedLayout(view, config)
        val slow = isSlowSpeed(view, config)
        if (speedSlowStates[view] != slow) {
            speedSlowStates[view] = slow
            // 跨过阈值时让宿主重新走一遍显隐和 ignored 状态。
            if (view.isAttachedToWindow) refreshSoon()
        }
    }

    private fun applySpeedContent(view: View, config: StatusBarIconComponentConfig) {
        runCatching {
            val numberText = Reflect.getObjectField(view, "mNetworkSpeedNumberText") as? TextView
                ?: return
            val unitText = Reflect.getObjectField(view, "mNetworkSpeedUnitText") as? TextView
                ?: return
            val number = Reflect.getObjectField(view, "mNetworkSpeedNumber")?.toString().orEmpty()
            val unit = Reflect.getObjectField(view, "mNetworkSpeedUnit")?.toString().orEmpty()
            numberText.text = if (number.isEmpty()) "" else "$number "
            unitText.text = if (unit.isEmpty()) "" else "$unit "
            when {
                config.speedUnit == "mb" -> {
                    val value = number.trim().toFloatOrNull()
                    when {
                        value != null && (
                            unit.contains("KB", ignoreCase = true) ||
                                unit.contains("K/s", ignoreCase = true)
                            ) -> {
                            numberText.text = String.format(Locale.US, "%.2f ", value / 1024f)
                            unitText.text = "MB/s "
                        }
                        value != null && unit.contains("B/s", ignoreCase = true) &&
                            !unit.contains("KB", ignoreCase = true) &&
                            !unit.contains("MB", ignoreCase = true) -> {
                            numberText.text = String.format(Locale.US, "%.2f ", value / 1048576f)
                            unitText.text = "MB/s "
                        }
                    }
                }
            }
        }.onFailure {
            android.util.Log.w("HyperRefineStatusBar", "speed content failed", it)
        }
    }

    private fun applySpeedFontSize(view: View, config: StatusBarIconComponentConfig) {
        val state = speedStyles[view] ?: captureSpeedStyle(view)?.also { speedStyles[view] = it } ?: return
        val numberText = state.numberText
        val unitText = state.unitText
        val size = config.speedFontSize
        if (!config.fontSizeEnabled || size <= 0f) {
            numberText.setTextSize(TypedValue.COMPLEX_UNIT_PX, state.numberTextSize)
            unitText.setTextSize(TypedValue.COMPLEX_UNIT_PX, state.unitTextSize)
            return
        }
        val ratio = if (state.numberTextSize > 0f) {
            state.unitTextSize / state.numberTextSize
        } else {
            0.91f
        }
        numberText.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        unitText.setTextSize(TypedValue.COMPLEX_UNIT_SP, (size * ratio).coerceAtLeast(4f))
    }

    private fun applySpeedLayout(view: View, config: StatusBarIconComponentConfig) {
        val state = speedStyles[view] ?: captureSpeedStyle(view)?.also { speedStyles[view] = it } ?: return
        val container = state.container ?: return
        val numberText = state.numberText
        val unitText = state.unitText

        var showNumber = config.speedStyle != "unit_only"
        var showUnit = config.speedShowUnit &&
            config.speedUnit != "hide_unit" &&
            config.speedStyle != "number_only"
        if (!showNumber && !showUnit) showNumber = true
        numberText.visibility = if (showNumber) View.VISIBLE else View.GONE
        unitText.visibility = if (showUnit) View.VISIBLE else View.GONE

        val horizontal = Gravity.CENTER_HORIZONTAL
        val numberGravity: Int
        val unitGravity: Int
        val numberTranslation: Float
        val unitTranslation: Float
        var minWidth = 0
        when (config.speedStyle) {
            "number_top" -> {
                numberGravity = Gravity.TOP or horizontal
                unitGravity = Gravity.BOTTOM or horizontal
                numberTranslation = 0f
                unitTranslation = 0f
            }
            "unit_top" -> {
                numberGravity = Gravity.BOTTOM or horizontal
                unitGravity = Gravity.TOP or horizontal
                numberTranslation = 0f
                unitTranslation = 0f
            }
            "inline" -> {
                numberGravity = Gravity.START or Gravity.CENTER_VERTICAL
                unitGravity = Gravity.END or Gravity.CENTER_VERTICAL
                numberText.translationX = state.numberTranslationX
                unitText.translationX = state.unitTranslationX
                numberTranslation = 0f
                unitTranslation = 0f
                if (showNumber && showUnit) {
                    val gap = (2f * view.resources.displayMetrics.density).roundToInt()
                    minWidth = numberText.paint.measureText(numberText.text.toString()).roundToInt() +
                        unitText.paint.measureText(unitText.text.toString()).roundToInt() + gap
                }
            }
            "number_only", "unit_only" -> {
                numberGravity = horizontal or Gravity.CENTER_VERTICAL
                unitGravity = horizontal or Gravity.CENTER_VERTICAL
                numberTranslation = 0f
                unitTranslation = 0f
            }
            else -> {
                numberGravity = state.numberGravity
                unitGravity = state.unitGravity
                numberTranslation = state.numberTranslation
                unitTranslation = state.unitTranslation
            }
        }

        setFrameGravity(numberText, numberGravity)
        setFrameGravity(unitText, unitGravity)
        numberText.translationY = numberTranslation
        unitText.translationY = unitTranslation
        if (container.minimumWidth != minWidth) container.minimumWidth = minWidth

        // 样式/字号变化后清掉宿主缓存的空宽度，避免网速仍按旧尺寸占位。
        val cachedEmptyWidth = runCatching {
            Reflect.getObjectField(view, "mEmptyWidth") as? Int
        }.getOrNull()
        if (cachedEmptyWidth != -1) {
            runCatching { Reflect.setObjectField(view, "mEmptyWidth", -1) }
            view.requestLayout()
        }
        container.requestLayout()
    }

    private fun setFrameGravity(view: TextView, gravity: Int) {
        val params = view.layoutParams as? FrameLayout.LayoutParams ?: return
        if (params.gravity != gravity) {
            params.gravity = gravity
            view.layoutParams = params
        }
    }

    private fun captureSpeedStyle(view: View): SpeedStyleState? {
        val container = runCatching {
            Reflect.getObjectField(view, "mContainer") as? FrameLayout
        }.getOrNull() ?: return null
        val numberText = runCatching {
            Reflect.getObjectField(view, "mNetworkSpeedNumberText") as? TextView
        }.getOrNull() ?: return null
        val unitText = runCatching {
            Reflect.getObjectField(view, "mNetworkSpeedUnitText") as? TextView
        }.getOrNull() ?: return null
        return SpeedStyleState(
            container = container,
            numberText = numberText,
            unitText = unitText,
            numberGravity = (numberText.layoutParams as? FrameLayout.LayoutParams)?.gravity ?: Gravity.CENTER,
            unitGravity = (unitText.layoutParams as? FrameLayout.LayoutParams)?.gravity ?: Gravity.CENTER,
            numberTranslationX = numberText.translationX,
            unitTranslationX = unitText.translationX,
            numberTranslation = numberText.translationY,
            unitTranslation = unitText.translationY,
            numberTextSize = numberText.textSize,
            unitTextSize = unitText.textSize,
        )
    }

    private fun isSlowSpeed(view: View, config: StatusBarIconComponentConfig): Boolean {
        if (!config.speedSlowHide || config.speedSlowThreshold <= 0) return false
        val number = runCatching {
            Reflect.getObjectField(view, "mNetworkSpeedNumber")?.toString()
        }.getOrNull()?.trim()?.toFloatOrNull() ?: return false
        val unit = runCatching {
            Reflect.getObjectField(view, "mNetworkSpeedUnit")?.toString()
        }.getOrNull().orEmpty()
        val multiplier = when {
            unit.contains("MB", ignoreCase = true) ||
                unit.contains("M/s", ignoreCase = true) -> 1024f * 1024f
            unit.contains("KB", ignoreCase = true) ||
                unit.contains("K/s", ignoreCase = true) -> 1024f
            else -> 1f
        }
        val kbPerSecond = number * multiplier / 1024f
        return kbPerSecond < config.speedSlowThreshold
    }

    private fun restoreSpeedStyles() {
        speedStyles.forEach { (view, state) ->
            runCatching {
                state.numberText.setTextSize(TypedValue.COMPLEX_UNIT_PX, state.numberTextSize)
                state.unitText.setTextSize(TypedValue.COMPLEX_UNIT_PX, state.unitTextSize)
                state.numberText.visibility = View.VISIBLE
                state.unitText.visibility = View.VISIBLE
                state.numberText.translationX = state.numberTranslationX
                state.unitText.translationX = state.unitTranslationX
                state.numberText.translationY = state.numberTranslation
                state.unitText.translationY = state.unitTranslation
                (state.numberText.layoutParams as? FrameLayout.LayoutParams)?.let {
                    it.gravity = state.numberGravity
                    state.numberText.layoutParams = it
                }
                (state.unitText.layoutParams as? FrameLayout.LayoutParams)?.let {
                    it.gravity = state.unitGravity
                    state.unitText.layoutParams = it
                }
                state.container?.minimumWidth = 0
                Reflect.setObjectField(view, "mEmptyWidth", -1)
                view.requestLayout()
            }
        }
        speedStyles.clear()
        speedSlowStates.clear()
    }

    /**
     * 原生 `StatusBarIconView` 的溢出点固定画三个圆。开启逐图标圆点后，
     * 在 before 中临时关掉原生绘制，after 再按隐藏图标数量补画 1..3 个。
     * 不新增 View / 悬浮窗，仍使用原生溢出点的位置、颜色与动画进度。
     */
    fun beforeNotificationDotDraw(view: View) {
        if (!HookPrefs.getBoolean(StatusBarIconKeys.ENABLED, false)) return
        if (!HookPrefs.getBoolean(
                StatusBarIconKeys.notificationMultiDot(StatusBarIconRegistry.NOTIFICATIONS),
                false,
            )
        ) {
            return
        }
        val state = notificationDotState(view) ?: return
        notificationDotStates[view] = state
        runCatching { Reflect.setObjectField(view, "mDotAppearAmount", 0f) }
    }

    fun afterNotificationDotDraw(view: View, canvas: Canvas?) {
        val state = notificationDotStates.remove(view) ?: return
        runCatching {
            Reflect.setObjectField(view, "mDotAppearAmount", state.progress)
            if (canvas == null) return@runCatching
            val alpha = (Color.alpha(state.color) / 255f *
                if (state.progress <= 1f) 1f else (2f - state.progress).coerceIn(0f, 1f))
            if (alpha <= 0f) return@runCatching
            val radius = if (state.progress <= 1f) {
                state.radius * state.progress
            } else {
                state.radius + (view.width / 4f - state.radius) * (state.progress - 1f)
            }
            if (radius <= 0.1f) return@runCatching
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = state.color
                this.alpha = (alpha * 255f).roundToInt().coerceIn(0, 255)
            }
            val centerX = view.width / 2f
            val centerY = view.height / 2f
            val step = radius * 2f + state.padding
            val firstOffset = -((state.count - 1) / 2f)
            for (i in 0 until state.count) {
                canvas.drawCircle(
                    centerX + (firstOffset + i) * step,
                    centerY,
                    radius,
                    paint,
                )
            }
        }
    }

    private fun notificationDotState(view: View): DotDrawState? {
        val parent = view.parent as? ViewGroup ?: return null
        if (parent.javaClass.simpleName != "NotificationIconContainer") return null
        val first = runCatching {
            Reflect.getObjectField(parent, "mFirstOverflowIndex") as? Int
        }.getOrNull() ?: return null
        if (first < 0 || parent.indexOfChild(view) < first) return null
        val visibleState = runCatching {
            Reflect.getObjectField(view, "mVisibleState") as? Int
        }.getOrNull() ?: return null
        if (visibleState != 1) return null
        val progress = runCatching {
            Reflect.getObjectField(view, "mDotAppearAmount") as? Float
        }.getOrNull() ?: return null
        if (progress <= 0f) return null
        val radius = runCatching {
            Reflect.getObjectField(view, "mDotRadius") as? Float
        }.getOrNull() ?: return null
        val color = runCatching {
            Reflect.getObjectField(view, "mDecorColor") as? Int
        }.getOrNull() ?: return null
        val padding = runCatching {
            val ex = Reflect.getObjectField(view, "mExParams") ?: return@runCatching 0f
            Reflect.getObjectField(ex, "dotPadding") as? Float ?: 0f
        }.getOrDefault(0f)
        val count = (parent.childCount - first).coerceIn(1, 3)
        return DotDrawState(count, progress, radius, color, padding)
    }

    /**
     * 只对已知系统场景应用自定义格式；未知 MiuiClock 返回 null，避免把通知中心/控制中心
     * 的时钟误改成状态栏样式。格式为空时也返回 null，保持宿主原生格式。
     */
    fun clockText(context: Context, viewId: Int): CharSequence? {
        if (!HookPrefs.getBoolean(StatusBarIconKeys.ENABLED, false)) return null
        val pattern = clockPattern(context, viewId) ?: return null
        return runCatching {
            SimpleDateFormat(normalizeClockPattern(pattern), Locale.getDefault()).format(Date())
        }.getOrNull()
    }

    private fun clockPattern(context: Context, viewId: Int): String? {
        val scene = clockScene(context, viewId) ?: return null
        val stored = HookPrefs.getString(clockPatternKey(scene), "").orEmpty().trim()
        // 横屏系统默认是“系统时间 + 小标题同款月日星期”；留空时动态生成，
        // 而不是让宿主只显示 HH:mm。旧版本写死的错误默认值也走这里迁移。
        if (scene == "horizontal" &&
            (stored.isEmpty() || stored == StatusBarIconKeys.LEGACY_HORIZONTAL_CLOCK_PATTERN)
        ) {
            return horizontalClockPattern(context)
        }
        return if (stored.isEmpty()) null else stored
    }

    /** 与小标题日期格式保持一致，使用当前 Locale 的最佳 EMMMd 模式。 */
    private fun horizontalClockPattern(context: Context): String {
        val time = runCatching {
            (android.text.format.DateFormat.getTimeFormat(context) as? SimpleDateFormat)?.toPattern()
        }.getOrNull().orEmpty().ifBlank { "HH:mm" }
        val date = runCatching {
            android.text.format.DateFormat.getBestDateTimePattern(
                Locale.getDefault(),
                "EMMMd",
            )
        }.getOrNull().orEmpty()
        return listOf(time, date).filter { it.isNotBlank() }.joinToString(" ")
    }

    /**
     * 用户常按 HyperHelper 示例填写 HH:MM:SS；在纯时间格式里把大写 M/S 视为分/秒。
     * 含日期（yyyy/dd/MMM）时保持 SimpleDateFormat 原语义，避免把月份改成分钟。
     */
    private fun normalizeClockPattern(pattern: String): String {
        if (pattern.any { it == 'm' || it == 's' }) return pattern
        if (pattern.contains("yy") || pattern.contains("dd") || pattern.contains("MMM")) return pattern
        return pattern.replace("MM", "mm").replace("SS", "ss")
    }

    fun registerClock(view: TextView) {
        if (!HookPrefs.getBoolean(StatusBarIconKeys.ENABLED, false)) return
        if (clockScene(view.context, view.id) == null) return
        clockViews[view] = true
        ensureClockTicker()
    }

    fun rememberClockText(view: TextView) {
        if (!originalClockTexts.containsKey(view)) {
            originalClockTexts[view] = view.text
        }
    }

    /** 只对状态栏时钟应用字重；通知中心/控制中心时钟不被组件字重影响。 */
    fun applyClockWeight(view: TextView) {
        if (restoringClock) return
        if (!HookPrefs.getBoolean(StatusBarIconKeys.ENABLED, false)) return
        if (clockScene(view.context, view.id) != "status") return
        val enabled = HookPrefs.getBoolean(
            StatusBarIconKeys.fontWeightEnabled(StatusBarIconRegistry.CLOCK),
            false,
        )
        val weight = if (enabled) {
            HookPrefs.getIntFlexible(
                StatusBarIconKeys.fontWeight(StatusBarIconRegistry.CLOCK),
                StatusBarIconRegistry.recommendedFontWeight(StatusBarIconRegistry.CLOCK),
            ).coerceIn(100, 900)
        } else {
            0
        }
        applyTextWeight(view, weight)
    }

    /** 状态栏时钟固定宽度；开关关闭时恢复宿主原布局宽度。 */
    fun applyClockLockWidth(view: TextView) {
        if (restoringClock) return
        if (!HookPrefs.getBoolean(StatusBarIconKeys.ENABLED, false)) {
            if (clockScene(view.context, view.id) == "status") restoreClock(view)
            return
        }
        if (clockScene(view.context, view.id) != "status") return
        val params = view.layoutParams ?: return
        if (!clockWidths.containsKey(view)) {
            clockWidths[view] = params.width
        }
        val base = clockWidths[view] ?: params.width
        if (!HookPrefs.getBoolean(StatusBarIconKeys.CLOCK_LOCK_WIDTH, false)) {
            restoreClockWidth(view, base)
            return
        }
        val widthDp = HookPrefs.getIntFlexible(StatusBarIconKeys.CLOCK_WIDTH, 60)
            .coerceIn(20, 240)
        val target = (widthDp * view.resources.displayMetrics.density).roundToInt()
        if (params.width != target) {
            params.width = target
            view.layoutParams = params
            view.requestLayout()
        }
    }

    private fun restoreClockWidth(view: TextView, base: Int) {
        val params = view.layoutParams ?: return
        if (params.width != base) {
            params.width = base
            view.layoutParams = params
            view.requestLayout()
        }
    }

    fun restoreClock(view: TextView) {
        clockWidths.remove(view)?.let { restoreClockWidth(view, it) }
        if (originalClockTexts.containsKey(view)) {
            val original = originalClockTexts.remove(view)
            if (view.text != original) view.text = original
        }
        restoreTextWeight(view)
        clockViews.remove(view)
        if (!restoringClock) {
            restoringClock = true
            runCatching { Reflect.callMethod(view, "updateTime") }
                .onFailure {
                    // Clock 没有 updateTime 时只能恢复字重/宽度，文本由原生下一次刷新恢复。
                }
            restoringClock = false
        }
    }

    fun restoreClocks() {
        clockViews.keys.toList().forEach(::restoreClock)
        clockViews.clear()
    }

    fun applyTextWeight(root: View, weight: Int) {
        val views = ArrayList<TextView>()
        collectTextViews(root, views)
        views.forEach { view ->
            if (!originalTypefaces.containsKey(view)) {
                originalTypefaces[view] = view.typeface
            }
            val base = originalTypefaces[view]
            val desiredWeight = weight.coerceIn(100, 900)
            val desired = if (weight > 0) {
                Typeface.create(base ?: Typeface.DEFAULT, desiredWeight, base?.isItalic == true)
            } else {
                base
            }
            if (view.typeface != desired) view.typeface = desired
            // 静态字体只能就近取字重；可变字体通过 wght 轴拿到 100..900 的连续字重，
            // 否则细体/中等字重会统一回落到 Regular，看起来只有“更粗”有效。
            runCatching {
                if (!originalVariations.containsKey(view)) {
                    originalVariations[view] = view.paint.fontVariationSettings
                }
                val variation = if (weight > 0) {
                    "'wght' $desiredWeight"
                } else {
                    originalVariations[view]
                }
                if (view.paint.fontVariationSettings != variation) {
                    view.paint.fontVariationSettings = variation
                    view.invalidate()
                }
            }
        }
    }

    fun applyTextSize(root: View, sizeSp: Float) {
        val views = ArrayList<TextView>()
        collectTextViews(root, views)
        views.forEach { view ->
            if (!originalTextSizes.containsKey(view)) {
                originalTextSizes[view] = view.textSize
            }
            if (sizeSp > 0f) {
                view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp.coerceIn(8f, 32f))
            } else {
                originalTextSizes.remove(view)?.let { base ->
                    view.setTextSize(TypedValue.COMPLEX_UNIT_PX, base)
                }
            }
        }
    }

    fun restoreTextWeight(root: View) {
        val views = ArrayList<TextView>()
        collectTextViews(root, views)
        views.forEach { view ->
            if (originalTypefaces.containsKey(view)) {
                val base = originalTypefaces.remove(view)
                if (view.typeface != base) view.typeface = base
            }
            runCatching {
                if (originalVariations.containsKey(view)) {
                    val base = originalVariations.remove(view)
                    if (view.paint.fontVariationSettings != base) {
                        view.paint.fontVariationSettings = base
                        view.invalidate()
                    }
                }
            }
            originalTextSizes.remove(view)?.let { base ->
                view.setTextSize(TypedValue.COMPLEX_UNIT_PX, base)
            }
        }
    }

    private fun ensureClockTicker() {
        if (!HookPrefs.getBoolean(StatusBarIconKeys.ENABLED, false)) {
            if (clockTickerRunning) {
                clockTickerRunning = false
                clockHandler.removeCallbacks(clockTicker)
            }
            return
        }
        // 格式含秒时自动按 1s 刷新，避免用户忘记打开开关时秒数停在 00；
        // 开关本身仍可用于强制刷新不含秒的格式。
        val patternHasSeconds = listOf(
            StatusBarIconKeys.CLOCK_PATTERN_STATUS,
            StatusBarIconKeys.CLOCK_PATTERN_NOTIFICATION_BIG,
            StatusBarIconKeys.CLOCK_PATTERN_NOTIFICATION_SMALL,
            StatusBarIconKeys.CLOCK_PATTERN_CONTROL_CENTER,
            StatusBarIconKeys.CLOCK_PATTERN_HORIZONTAL,
            StatusBarIconKeys.CLOCK_PATTERN_PAD,
        ).any { key ->
            HookPrefs.getString(key, "").orEmpty().any { it == 's' || it == 'S' }
        }
        val shouldRun = patternHasSeconds ||
            HookPrefs.getBoolean(StatusBarIconKeys.CLOCK_REFRESH, false)
        if (shouldRun == clockTickerRunning) return
        clockTickerRunning = shouldRun
        if (shouldRun) clockHandler.post(clockTicker) else clockHandler.removeCallbacks(clockTicker)
    }

    private fun clockScene(context: Context, viewId: Int): String? {
        if (viewId == 0) return null
        return when (viewId) {
            id(context, "clock") -> "status"
            id(context, "big_time") -> "notification_big"
            id(context, "date_time") -> "notification_small"
            id(context, "normal_control_center_date_view") -> "control_center"
            id(context, "horizontal_time") -> "horizontal"
            // pad_clock 只在平板由 ViewStub 展开，独立显示“月份 日期 星期”。
            id(context, "pad_clock") -> if (isTablet(context)) "pad_clock" else null
            else -> null
        }
    }

    private fun clockPatternKey(scene: String): String = when (scene) {
        "notification_big" -> StatusBarIconKeys.CLOCK_PATTERN_NOTIFICATION_BIG
        "notification_small" -> StatusBarIconKeys.CLOCK_PATTERN_NOTIFICATION_SMALL
        "control_center" -> StatusBarIconKeys.CLOCK_PATTERN_CONTROL_CENTER
        "horizontal" -> StatusBarIconKeys.CLOCK_PATTERN_HORIZONTAL
        "pad_clock" -> StatusBarIconKeys.CLOCK_PATTERN_PAD
        else -> StatusBarIconKeys.CLOCK_PATTERN_STATUS
    }

    private fun isTablet(context: Context): Boolean =
        context.resources.configuration.smallestScreenWidthDp >= 600

    private fun id(context: Context, name: String): Int = synchronized(idCache) {
        idCache.getOrPut(name) {
            context.resources.getIdentifier(name, "id", StatusBarIconKeys.TARGET)
        }
    }

    private fun collectTextViews(view: View, out: MutableList<TextView>) {
        if (view is TextView) out.add(view)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) collectTextViews(view.getChildAt(i), out)
        }
    }

    private class Host(root: ViewGroup) {
        private val rootRef = WeakReference(root)
        private val context: Context = root.context
        private val density = context.resources.displayMetrics.density
        private val base = IdentityHashMap<View, BaseView>()
        private val trackedIds = IdentityHashMap<View, String>()
        private val lastHidden = IdentityHashMap<View, Boolean>()
        private val forcedVisible = IdentityHashMap<View, Boolean>()
        /** 强制取消原生 blocked 前的状态；离开强制可见后必须恢复，避免留下空槽位。 */
        private val originalBlocked = IdentityHashMap<View, Boolean>()
        /** 首次接管前 MiuiStatusIconContainer 的真实 ignoredSlots；RIGHT_BLOCK_LIST 并不完整。 */
        private var capturedNativeIgnored: List<String>? = null
        /** 每个 slot 在模块首次接管前的 ignored 归属；用于从自定义模式回到 SYSTEM 时还原。 */
        private val nativeIgnoredBySlot = HashMap<String, Boolean>()
        /** 控制器级 mIconHideList 的原生归属；它同时作用于状态栏和 CC fake 图标组。 */
        private val nativeHideBySlot = HashMap<String, Boolean>()
        private val transformed = IdentityHashMap<View, Boolean>()
        private val spaced = IdentityHashMap<View, Boolean>()
        private val aligned = IdentityHashMap<View, String>()
        private val attached = AtomicBoolean(false)
        private var lastIgnored: List<String>? = null
        private var lastCc = false
        private var lastMaxNotifications = -1
        private var lastNotificationCount = -1
        private var currentSnapshot: StatusBarIconSnapshot? = null
        private var preDraw: ViewTreeObserver.OnPreDrawListener? = null
        private var refreshPosted = false

        private val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                refresh()
            }
        }

        private val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(ConnectivityManager.CONNECTIVITY_ACTION)
            addAction(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(android.app.AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED)
        }

        init {
            register()
            root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = register()
                override fun onViewDetachedFromWindow(v: View) = unregister()
            })
            preDraw = ViewTreeObserver.OnPreDrawListener {
                val cc = controlCenterExpanded()
                if (cc != lastCc) refresh()
                reconcileSpecialVisibility()
                true
            }.also { root.viewTreeObserver.addOnPreDrawListener(it) }
            refresh()
        }

        fun postRefresh() {
            if (refreshPosted) return
            val root = rootRef.get() ?: return
            refreshPosted = true
            root.post {
                refreshPosted = false
                refresh()
            }
        }

        fun refresh() {
            val root = rootRef.get() ?: return
            val snapshot = StatusBarIconConfigReader.load()
            ensureClockTicker()
            if (!snapshot.enabled) {
                restore(root)
                return
            }
            val cc = controlCenterExpanded()
            lastCc = cc
            currentSnapshot = snapshot
            val statusIcons = find(root, "statusIcons") as? ViewGroup
            val notificationContainer = notificationArea(root)?.let {
                find(it, "notificationIcons") ?: firstChildOfType(it, "NotificationIconContainer")
            } as? ViewGroup
            val evaluator = StatusBarIconConditionEvaluator(
                context = context,
                notificationCount = {
                    notificationContainer?.let { container ->
                        (0 until container.childCount).count {
                            container.getChildAt(it).visibility == View.VISIBLE
                        }
                    } ?: 0
                },
                speedText = {
                    statusIcons?.let { icons ->
                        findSpeedView(icons)?.let { speed ->
                            val n = runCatching {
                                Reflect.getObjectField(speed, "mNetworkSpeedNumber")?.toString()
                            }.getOrNull().orEmpty()
                            val u = runCatching {
                                Reflect.getObjectField(speed, "mNetworkSpeedUnit")?.toString()
                            }.getOrNull().orEmpty()
                            n + u
                        }
                    }.orEmpty()
                },
            )

            // 控制器级隐藏列表是状态栏与控制中心 fake 图标组共用的入口。
            applyGlobalHidden(snapshot, cc, evaluator)
            applySystemIcons(statusIcons, snapshot, cc, evaluator)
            applyGroupVisibility(snapshot, cc, evaluator)
            applySpecial(root, snapshot, cc, evaluator)
            applyClockText()
            applyNotificationMax(notificationContainer, snapshot)
            statusIcons?.let { icons ->
                findSpeedView(icons)?.let {
                    applyNetworkSpeed(it, snapshot.config(StatusBarIconRegistry.NETWORK_SPEED))
                }
            }
            ensureClockTicker()
        }

        /**
         * 同步 StatusBarIconControllerImpl.mIconHideList。
         *
         * 状态栏和展开的控制中心各有一组 MiuiStatusIconContainer/IconManager；
         * 只改 statusIcons 的 ignoredSlots 时，控制中心 fake 组仍会显示 VPN。
         * 控制器级 hide list 由 refreshIconGroup 应用到所有组。
         */
        private fun applyGlobalHidden(
            snapshot: StatusBarIconSnapshot,
            cc: Boolean,
            evaluator: StatusBarIconConditionEvaluator,
        ) {
            val controller = controllerRef?.get() ?: return
            val hideList = runCatching {
                Reflect.getObjectField(controller, "mIconHideList")
            }.getOrNull() ?: return
            var changed = false
            StatusBarIconRegistry.right.forEach { component ->
                val slot = component.slot ?: component.hideSlot ?: return@forEach
                val config = snapshot.config(component.id)
                val customDisplay = config.display.isNotBlank() &&
                    config.display != StatusBarIconMode.SYSTEM.value ||
                    config.mode != StatusBarIconMode.SYSTEM ||
                    config.logic != StatusBarIconLogic.DEFAULT
                val nativeHidden = nativeHideBySlot.getOrPut(slot) {
                    hideListContains(hideList, slot)
                }
                // SYSTEM 恢复原生成员关系；自定义模式由 display 决定，隐藏/显示都覆盖原生。
                val desired = if (customDisplay) {
                    hidden(component, config, cc, evaluator)
                } else {
                    nativeHidden
                }
                val current = hideListContains(hideList, slot)
                if (desired && !current) {
                    runCatching { Reflect.callMethod(hideList, "add", slot) }
                    changed = true
                } else if (!desired && current) {
                    runCatching { Reflect.callMethod(hideList, "remove", slot) }
                    changed = true
                }
            }
            if (!changed) return
            refreshIconGroups(controller)
        }

        private fun hideListContains(hideList: Any, slot: String): Boolean = runCatching {
            Reflect.callMethod(hideList, "contains", slot) as? Boolean ?: false
        }.getOrDefault(false)

        /**
         * `ModernStatusBarWifiView` 等新图标把 `setBlocked` 实现成空方法，控制器 hide list
         * 对它们无效。这里直接遍历控制器注册的每个图标组（包括控制中心 fake 组），
         * 只对显式自定义显示模式的组件按当前展开状态设置 View 可见性。
         */
        private fun applyGroupVisibility(
            snapshot: StatusBarIconSnapshot,
            cc: Boolean,
            evaluator: StatusBarIconConditionEvaluator,
        ) {
            val liveGroups = iconGroups.mapNotNull { it.get() }.filter { it.isAttachedToWindow }
            if (liveGroups.isEmpty()) return
            liveGroups.forEach { group ->
                for (i in 0 until group.childCount) {
                    val child = group.getChildAt(i)
                    val slot = runCatching {
                        Reflect.callMethod(child, "getSlot") as? String
                    }.getOrNull() ?: continue
                    val component = StatusBarIconRegistry.right.firstOrNull { it.slot == slot }
                        ?: continue
                    val config = snapshot.config(component.id)
                    // 控制中心 fake 组与状态栏组共用同一份几何/字重配置。
                    applyGeometry(child, config)
                    if (component.textual) {
                        applyTextWeight(child, if (config.fontWeightEnabled) config.fontWeight else 0)
                        if (component.id != StatusBarIconRegistry.NETWORK_SPEED) {
                            applyTextSize(child, if (config.fontSizeEnabled) config.fontSize else 0f)
                        }
                    }
                    if (!hasCustomDisplay(config)) {
                        if (groupHidden.remove(child) == true) {
                            child.visibility = if (nativeIconVisible(child)) {
                                View.VISIBLE
                            } else {
                                View.GONE
                            }
                        }
                        continue
                    }
                    val hide = hidden(component, config, cc, evaluator)
                    if (hide) {
                        groupHidden[child] = true
                        if (child.visibility != View.GONE) child.visibility = View.GONE
                    } else {
                        val forceVisible = config.mode == StatusBarIconMode.ALWAYS ||
                            (config.mode == StatusBarIconMode.CC_ONLY_SHOW && cc) ||
                            (config.mode == StatusBarIconMode.CC_ONLY_HIDE && !cc) ||
                            config.display.startsWith("condition:")
                        if ((groupHidden.remove(child) == true || forceVisible) &&
                            nativeIconVisible(child)
                        ) {
                            if (child.visibility != View.VISIBLE) child.visibility = View.VISIBLE
                        }
                    }
                }
            }
        }

        private fun hasCustomDisplay(config: StatusBarIconComponentConfig): Boolean =
            (config.display.isNotBlank() && config.display != StatusBarIconMode.SYSTEM.value) ||
                config.mode != StatusBarIconMode.SYSTEM ||
                config.logic != StatusBarIconLogic.DEFAULT

        private fun restoreGlobalHidden() {
            if (nativeHideBySlot.isEmpty()) return
            val controller = controllerRef?.get() ?: return
            val hideList = runCatching {
                Reflect.getObjectField(controller, "mIconHideList")
            }.getOrNull() ?: return
            var changed = false
            nativeHideBySlot.forEach { (slot, nativeHidden) ->
                val current = hideListContains(hideList, slot)
                if (nativeHidden != current) {
                    runCatching {
                        Reflect.callMethod(
                            hideList,
                            if (nativeHidden) "add" else "remove",
                            slot,
                        )
                    }
                    changed = true
                }
            }
            if (changed) refreshIconGroups(controller)
        }

        private fun refreshIconGroups(controller: Any) {
            val groups = runCatching {
                Reflect.getObjectField(controller, "mIconGroups") as? List<*>
            }.getOrNull().orEmpty()
            groups.forEach { manager ->
                runCatching { Reflect.callMethod(controller, "refreshIconGroup", manager) }
            }
        }

        private fun applySystemIcons(
            group: ViewGroup?,
            snapshot: StatusBarIconSnapshot,
            cc: Boolean,
            evaluator: StatusBarIconConditionEvaluator,
        ) {
            group ?: return
            val currentIgnored = currentIgnoredSlots(group)
            if (capturedNativeIgnored == null && currentIgnored.isNotEmpty()) {
                capturedNativeIgnored = currentIgnored
            }
            val ignored = currentIgnored.toMutableList()
            StatusBarIconRegistry.right.forEach { component ->
                val slot = component.slot ?: return@forEach
                // 只对已经存在的原生 View 记录归属；vpn 等 View 可能在连接后才出现。
                val child = findChildBySlot(group, slot) ?: return@forEach
                val config = snapshot.config(component.id)
                val nativeWasIgnored = nativeIgnoredBySlot.getOrPut(slot) {
                    slot in currentIgnored
                }
                val customDisplay = config.display.isNotBlank() &&
                    config.display != StatusBarIconMode.SYSTEM.value ||
                    config.mode != StatusBarIconMode.SYSTEM ||
                    config.logic != StatusBarIconLogic.DEFAULT
                when {
                    hidden(component, config, cc, evaluator) -> {
                        if (slot !in ignored) ignored += slot
                    }
                    !customDisplay -> {
                        // SYSTEM 完全还原原生归属，不能把模块加/删过的 slot 留在当前列表。
                        if (nativeWasIgnored) {
                            if (slot !in ignored) ignored += slot
                        } else {
                            ignored.remove(slot)
                        }
                    }
                    else -> {
                        // 原生没有图标内容时保留 ignored，避免“有占位但不可见”。
                        if (isIconVisible(child)) {
                            ignored.remove(slot)
                        } else if (nativeWasIgnored) {
                            if (slot !in ignored) ignored += slot
                        }
                    }
                }
            }
            if (ignored != lastIgnored) {
                runCatching {
                    Reflect.callMethod(group, "setIgnoredSlots", ignored.toList())
                    lastIgnored = ignored.toList()
                    // MiuiStatusIconContainer.setIgnoredSlots 只改 List，不主动 requestLayout。
                    group.requestLayout()
                }
            }
            for (i in 0 until group.childCount) {
                val child = group.getChildAt(i) ?: continue
                val slot = runCatching { Reflect.callMethod(child, "getSlot") as? String }.getOrNull()
                    ?: continue
                val component = StatusBarIconRegistry.right.firstOrNull { it.slot == slot } ?: continue
                val config = snapshot.config(component.id)
                trackedIds[child] = component.id
                val wasHidden = lastHidden[child] == true
                val hide = hidden(component, config, cc, evaluator) ||
                    (component.id == StatusBarIconRegistry.NETWORK_SPEED && isSlowSpeed(child, config))
                lastHidden[child] = hide
                val iconVisible = isIconVisible(child)
                val forceVisible = !hide && iconVisible && (
                    config.mode == StatusBarIconMode.ALWAYS ||
                        (config.mode == StatusBarIconMode.CC_ONLY_SHOW && cc) ||
                        (config.mode == StatusBarIconMode.CC_ONLY_HIDE && !cc) ||
                        config.display.startsWith("condition:")
                    )
                val wasForced = forcedVisible[child] == true
                forcedVisible[child] = forceVisible
                if (forceVisible) {
                    if (!originalBlocked.containsKey(child)) {
                        originalBlocked[child] = runCatching {
                            Reflect.getObjectField(child, "mBlocked") as? Boolean ?: false
                        }.getOrDefault(false)
                    }
                    // always / cc_only / condition 的可见态由本模块接管；解除原生 blocked。
                    runCatching { Reflect.callMethod(child, "setBlocked", false) }
                }
                var blockedRestored = false
                if (!forceVisible && wasForced) {
                    originalBlocked.remove(child)?.let { blocked ->
                        runCatching {
                            Reflect.callMethod(child, "setBlocked", blocked)
                            blockedRestored = blocked
                        }
                    }
                }
                when {
                    hide -> if (child.visibility != View.GONE) child.visibility = View.GONE
                    forceVisible -> if (child.visibility != View.VISIBLE) child.visibility = View.VISIBLE
                    wasForced -> {
                        // 原生 blocked=true 时 setBlocked 已经会切到 GONE，不能再按 base 强行显示。
                        if (!blockedRestored) {
                            child.visibility = if (nativeIconVisible(child)) {
                                capture(child).visibility
                            } else {
                                View.GONE
                            }
                        }
                    }
                    wasHidden -> {
                        child.visibility = if (nativeIconVisible(child)) {
                            capture(child).visibility
                        } else {
                            View.GONE
                        }
                    }
                }
                applyGeometry(child, config)
                if (component.textual) {
                    applyTextWeight(
                        child,
                        if (config.fontWeightEnabled) config.fontWeight else 0,
                    )
                    if (component.id != StatusBarIconRegistry.NETWORK_SPEED) {
                        applyTextSize(child, if (config.fontSizeEnabled) config.fontSize else 0f)
                    }
                }
            }
            // NetworkSpeedView 不是 StatusIconDisplayable，没有 getSlot；仍需进入
            // trackedIds，OnPreDraw 才能应用偏移、缩放、透明度和文字对齐。
            findSpeedView(group)?.let { speed ->
                if (trackedIds[speed] == null) {
                    val config = snapshot.config(StatusBarIconRegistry.NETWORK_SPEED)
                    trackedIds[speed] = StatusBarIconRegistry.NETWORK_SPEED
                    applyGeometry(speed, config)
                    applyTextWeight(speed, if (config.fontWeightEnabled) config.fontWeight else 0)
                }
            }
        }

        private fun applySpecial(
            root: ViewGroup,
            snapshot: StatusBarIconSnapshot,
            cc: Boolean,
            evaluator: StatusBarIconConditionEvaluator,
        ) {
            val clock = find(root, "clock")
            val padClock = find(root, "pad_clock")
            val battery = find(root, "battery")
            val batteryIndicator = find(root, "battery_indicator")
            val notifications = find(root, "notification_icon_area")
            val fullscreenNotifications = find(root, "fullscreen_notification_icon_area")
            val privacy = find(root, "privacy_area")
            val operator = find(root, "operator_name")

            applySpecialView(clock, StatusBarIconRegistry.CLOCK, snapshot, cc, evaluator)
            applySpecialView(padClock, StatusBarIconRegistry.PAD_CLOCK, snapshot, cc, evaluator)
            applySpecialView(battery, StatusBarIconRegistry.BATTERY, snapshot, cc, evaluator)
            batteryViews.mapNotNull { it.get() }.forEach { extra ->
                if (extra !== battery) {
                    applySpecialView(extra, StatusBarIconRegistry.BATTERY, snapshot, cc, evaluator)
                }
            }
            if (batteryIndicator !== battery) {
                applySpecialView(batteryIndicator, StatusBarIconRegistry.BATTERY, snapshot, cc, evaluator)
            }
            applySpecialView(notifications, StatusBarIconRegistry.NOTIFICATIONS, snapshot, cc, evaluator)
            if (fullscreenNotifications !== notifications) {
                applySpecialView(
                    fullscreenNotifications,
                    StatusBarIconRegistry.NOTIFICATIONS,
                    snapshot,
                    cc,
                    evaluator,
                )
            }
            applySpecialView(privacy, StatusBarIconRegistry.PRIVACY, snapshot, cc, evaluator)
            applySpecialView(operator, StatusBarIconRegistry.OPERATOR, snapshot, cc, evaluator)

            listOfNotNull(clock, padClock).forEach { clockView ->
                val config = snapshot.config(
                    if (clockView === padClock) {
                        StatusBarIconRegistry.PAD_CLOCK
                    } else {
                        StatusBarIconRegistry.CLOCK
                    },
                )
                val previous = aligned[clockView]
                if (config.align != "system" || previous != null) {
                    val baseView = capture(clockView)
                    (clockView as? TextView)?.gravity = when (config.align) {
                        "start" -> Gravity.START or Gravity.CENTER_VERTICAL
                        "end" -> Gravity.END or Gravity.CENTER_VERTICAL
                        "center" -> Gravity.CENTER
                        else -> baseView.gravity
                    }
                    (clockView as? TextView)?.textAlignment = when (config.align) {
                        "start" -> View.TEXT_ALIGNMENT_VIEW_START
                        "end" -> View.TEXT_ALIGNMENT_VIEW_END
                        "center" -> View.TEXT_ALIGNMENT_CENTER
                        else -> baseView.textAlignment
                    }
                    aligned[clockView] = config.align
                }
                (clockView as? TextView)?.let(::applyClockLockWidth)
            }
        }

        private fun applySpecialView(
            view: View?,
            id: String,
            snapshot: StatusBarIconSnapshot,
            cc: Boolean,
            evaluator: StatusBarIconConditionEvaluator,
        ) {
            view ?: return
            val config = snapshot.config(id)
            trackedIds[view] = id
            val baseView = capture(view)
            val hide = hidden(StatusBarIconRegistry.byId(id)!!, config, cc, evaluator)
            val wasHidden = lastHidden[view] == true
            lastHidden[view] = hide
            val forceVisible = !hide && hasVisibleContent(view, id) && (
                config.mode == StatusBarIconMode.ALWAYS ||
                    (config.mode == StatusBarIconMode.CC_ONLY_SHOW && cc) ||
                    (config.mode == StatusBarIconMode.CC_ONLY_HIDE && !cc) ||
                    config.display.startsWith("condition:")
                )
            val wasForced = forcedVisible[view] == true
            forcedVisible[view] = forceVisible
            when {
                hide -> {
                    if (view.visibility != View.GONE) view.visibility = View.GONE
                }
                forceVisible -> {
                    if (view.visibility != View.VISIBLE) view.visibility = View.VISIBLE
                }
                wasForced -> view.visibility = baseView.visibility
                wasHidden -> view.visibility = baseView.visibility
                config.mode == StatusBarIconMode.SYSTEM &&
                    config.logic == StatusBarIconLogic.DEFAULT -> Unit
                else -> view.visibility = baseView.visibility
            }
            applyGeometry(view, config)
            val component = StatusBarIconRegistry.byId(id)
            if (component?.textual == true) {
                applyTextWeight(view, if (config.fontWeightEnabled) config.fontWeight else 0)
                applyTextSize(view, if (config.fontSizeEnabled) config.fontSize else 0f)
            }
        }

        private fun applyGeometry(view: View, config: StatusBarIconComponentConfig) {
            applyTransforms(view, config)
            val baseView = capture(view)
            val gapStart = (config.gapStart.coerceIn(-16f, 32f) * density).toInt()
            val gapEnd = (config.gapEnd.coerceIn(-16f, 32f) * density).toInt()
            val gapCustom = gapStart != 0 || gapEnd != 0
            val wasSpaced = spaced[view] == true
            if (!gapCustom && !wasSpaced) return
            spaced[view] = gapCustom
            // SystemUI 的状态栏图标使用自绘 onLayout，margin 不参与定位；
            // padding 会同时进入子 View 宽度与父容器的手动布局计算，左右间距才真正生效。
            val start = (baseView.paddingStart + gapStart).coerceAtLeast(0)
            val end = (baseView.paddingEnd + gapEnd).coerceAtLeast(0)
            if (view.paddingStart != start || view.paddingEnd != end) {
                view.setPaddingRelative(start, view.paddingTop, end, view.paddingBottom)
                view.requestLayout()
            }
        }

        private fun applyTransforms(view: View, config: StatusBarIconComponentConfig) {
            val baseView = capture(view)
            val offset = config.offsetY.coerceIn(-24f, 24f) * density
            val scale = config.scale.coerceIn(0.3f, 3f)
            val alpha = config.alpha.coerceIn(0f, 1f)
            val custom = offset != 0f || scale != 1f || alpha != 1f
            val wasCustom = transformed[view] == true
            if (!custom && !wasCustom) return
            transformed[view] = custom
            if (view.translationY != baseView.translationY + offset) {
                view.translationY = baseView.translationY + offset
            }
            if (view.scaleX != baseView.scaleX * scale) view.scaleX = baseView.scaleX * scale
            if (view.scaleY != baseView.scaleY * scale) view.scaleY = baseView.scaleY * scale
            if (view.alpha != baseView.alpha * alpha) view.alpha = baseView.alpha * alpha
        }

        private fun capture(view: View): BaseView = base.getOrPut(view) {
            val params = view.layoutParams as? ViewGroup.MarginLayoutParams
            BaseView(
                visibility = view.visibility,
                translationX = view.translationX,
                translationY = view.translationY,
                scaleX = view.scaleX,
                scaleY = view.scaleY,
                alpha = view.alpha,
                marginStart = params?.marginStart ?: 0,
                marginEnd = params?.marginEnd ?: 0,
                paddingStart = view.paddingStart,
                paddingEnd = view.paddingEnd,
                gravity = (view as? TextView)?.gravity ?: 0,
                textAlignment = (view as? TextView)?.textAlignment ?: View.TEXT_ALIGNMENT_GRAVITY,
            )
        }

        private fun restore(root: ViewGroup) {
            restoreSpeedStyles()
            base.forEach { (view, value) ->
                runCatching {
                    view.visibility = value.visibility
                    view.translationX = value.translationX
                    view.translationY = value.translationY
                    view.scaleX = value.scaleX
                    view.scaleY = value.scaleY
                    view.alpha = value.alpha
                    if (view.layoutParams is ViewGroup.MarginLayoutParams) {
                        val params = view.layoutParams as ViewGroup.MarginLayoutParams
                        params.marginStart = value.marginStart
                        params.marginEnd = value.marginEnd
                        view.layoutParams = params
                    }
                    view.setPaddingRelative(
                        value.paddingStart,
                        view.paddingTop,
                        value.paddingEnd,
                        view.paddingBottom,
                    )
                    if (view is TextView) {
                        view.gravity = value.gravity
                        view.textAlignment = value.textAlignment
                    }
                }
            }
            base.keys.toList().forEach { view ->
                if (view is TextView && clockScene(view.context, view.id) != null) {
                    restoreClock(view)
                } else {
                    restoreTextWeight(view)
                }
            }
            restoreClocks()
            // 恢复被强制解除的原生 blocked，避免模块关闭后留下永久空槽位。
            originalBlocked.forEach { (view, blocked) ->
                runCatching { Reflect.callMethod(view, "setBlocked", blocked) }
            }
            originalBlocked.clear()
            restoreGlobalHidden()
            groupHidden.forEach { (view, _) ->
                runCatching {
                    view.visibility = if (nativeIconVisible(view)) View.VISIBLE else View.GONE
                }
            }
            groupHidden.clear()
            nativeHideBySlot.clear()
            nativeIgnoredBySlot.clear()
            aligned.clear()
            transformed.clear()
            spaced.clear()
            forcedVisible.clear()
            lastHidden.clear()
            val native = nativeIgnoredSlots()
            (find(root, "statusIcons") as? ViewGroup)?.let { group ->
                runCatching {
                    Reflect.callMethod(group, "setIgnoredSlots", native)
                    group.requestLayout()
                }
            }
            lastIgnored = native
            val group = find(root, "statusIcons") as? ViewGroup
            group?.let {
                val native = nativeIgnoredSlots()
                if (native != lastIgnored) {
                    runCatching { Reflect.callMethod(it, "setIgnoredSlots", native) }
                    lastIgnored = native
                }
            }
        }

        private fun reconcileSpecialVisibility() {
            val root = rootRef.get() ?: return
            if (!HookPrefs.getBoolean(StatusBarIconKeys.ENABLED, false)) return
            if (!attached.get()) return
            // 只在控制中心状态 / 通知数量变化时做完整刷新；不逐帧重写，避免与原生动画冲突。
            if (controlCenterExpanded() != lastCc) refresh()
            val area = notificationArea(root)
            val count = area?.let { outer ->
                val container = find(outer, "notificationIcons") ?: firstChildOfType(outer, "NotificationIconContainer")
                (container as? ViewGroup)?.let { inner ->
                    (0 until inner.childCount).count { inner.getChildAt(it).visibility == View.VISIBLE }
                }
            } ?: 0
            if (count != lastNotificationCount) {
                lastNotificationCount = count
                refresh()
            }
            lastHidden.forEach { (view, hidden) ->
                if (hidden && view.visibility != View.GONE) view.visibility = View.GONE
            }
            forcedVisible.forEach { (view, force) ->
                if (force && view.visibility != View.VISIBLE) view.visibility = View.VISIBLE
            }
            val snapshot = currentSnapshot ?: return
            trackedIds.forEach { (view, id) ->
                applyTransforms(view, snapshot.config(id))
                applyAlignmentOffset(view, id, snapshot.config(id))
            }
        }

        /** 组件宽度通常由内容决定；用水平位移把文字真正放到组件内部的对齐位置。 */
        private fun applyAlignmentOffset(view: View, id: String, config: StatusBarIconComponentConfig) {
            // 只有时间/网速支持“组件内对齐”。右侧 slot 图标的 translationX 由
            // MiuiStatusIconContainer/动画控制器负责，若这里按 base 重置会把新布局
            // 拉回旧位置，多个图标就会叠在一起。
            if (id != StatusBarIconRegistry.CLOCK &&
                id != StatusBarIconRegistry.PAD_CLOCK &&
                id != StatusBarIconRegistry.NETWORK_SPEED
            ) {
                return
            }
            if (id == StatusBarIconRegistry.NETWORK_SPEED) {
                // 网速的对齐只改容器内两行文字，不能重置整个 NetworkSpeedView 的
                // translationX；否则重排后它仍停在旧槽位，压到蓝牙/信号上。
                if (config.align == "system") {
                    speedStyles[view]?.let { state ->
                        state.numberText.translationX = state.numberTranslationX
                        state.unitText.translationX = state.unitTranslationX
                    }
                    return
                }
                val sign = when (config.align) {
                    "start" -> -1f
                    "end" -> 1f
                    else -> return
                }
                val state = speedStyles[view] ?: return
                if (config.speedStyle == "inline") return
                val container = state.container ?: return
                if (container.width <= 0) return
                for (text in listOf(state.numberText, state.unitText)) {
                    if (text.visibility != View.VISIBLE) continue
                    val spare = (container.width -
                        text.paint.measureText(text.text.toString())).coerceAtLeast(0f)
                    text.translationX = sign * spare / 2f
                }
                return
            }
            if (config.align == "system") {
                val baseView = base[view]
                if (baseView != null && view.translationX != baseView.translationX) {
                    view.translationX = baseView.translationX
                }
                return
            }
            val baseView = base[view] ?: return
            val sign = when (config.align) {
                "start" -> -1f
                "end" -> 1f
                else -> 0f
            }
            if (sign == 0f) {
                view.translationX = baseView.translationX
                return
            }
            if (id == StatusBarIconRegistry.CLOCK || id == StatusBarIconRegistry.PAD_CLOCK) {
                val text = view as? TextView ?: return
                val spare = (view.width - text.paint.measureText(text.text.toString())).coerceAtLeast(0f)
                view.translationX = baseView.translationX + sign * spare / 2f
                return
            }
        }

        private fun hidden(
            component: StatusBarIconComponent,
            config: StatusBarIconComponentConfig,
            cc: Boolean,
            evaluator: StatusBarIconConditionEvaluator,
        ): Boolean {
            val display = when {
                config.display.isNotBlank() -> config.display
                config.logic == StatusBarIconLogic.CUSTOM &&
                    config.condition != StatusBarIconCondition.NONE ->
                    "condition:${config.condition.value}"
                else -> config.mode.value
            }
            return when {
                display == StatusBarIconMode.HIDDEN.value -> true
                display == StatusBarIconMode.ALWAYS.value -> false
                display == StatusBarIconMode.CC_ONLY_SHOW.value -> !cc
                display == StatusBarIconMode.CC_ONLY_HIDE.value -> cc
                display.startsWith("condition:") -> {
                    val condition = StatusBarIconCondition.from(display.removePrefix("condition:"))
                    if (condition == StatusBarIconCondition.NONE) {
                        false
                    } else {
                        evaluator.matches(component, condition, config.alarmWithin) == false
                    }
                }
                else -> false
            }
        }

        private fun applyClockText() {
            val root = rootRef.get() ?: return
            val clock = find(root, "clock") as? TextView ?: return
            registerClock(clock)
            rememberClockText(clock)
            val text = clockText(context, clock.id)
            if (text != null) {
                if (!TextUtils.equals(clock.text, text)) clock.text = text
            } else {
                // 从自定义格式切回系统格式时，先恢复进入自定义前的文本，再让宿主重算。
                if (originalClockTexts.containsKey(clock)) {
                    val original = originalClockTexts[clock]
                    if (!TextUtils.equals(clock.text, original)) clock.text = original
                }
                runCatching { Reflect.callMethod(clock, "updateTime") }
            }
            applyClockLockWidth(clock)
        }

        private fun nativeIconVisible(view: View): Boolean = runCatching {
            Reflect.callMethod(view, "isIconVisible") as? Boolean
        }.getOrNull() ?: (view.visibility == View.VISIBLE)

        private fun applyNotificationMax(container: ViewGroup?, snapshot: StatusBarIconSnapshot) {
            container ?: return
            val max = snapshot.config(StatusBarIconRegistry.NOTIFICATIONS).maxNotifications
            runCatching {
                val current = (Reflect.getObjectField(container, "mMaxIcons") as? Int) ?: -1
                if (current == max) {
                    lastMaxNotifications = max
                    return
                }
                Reflect.callMethod(container, "setMaxIconsAmount", max)
                lastMaxNotifications = max
            }
        }

        private fun controlCenterExpanded(): Boolean {
            val root = rootRef.get() ?: return false
            // NotificationIconContainerInject 在 bind/状态回调中维护控制中心展开状态；
            // animatorController.controlPanelExpand 仅作为老版本回退。
            val area = notificationArea(root)
            val container = area?.let {
                find(it, "notificationIcons") ?: firstChildOfType(it, "NotificationIconContainer")
            }
            if (container != null) {
                runCatching {
                    val inject = Reflect.callMethod(container, "getInject") ?: return@runCatching
                    val controlCenter = Reflect.getObjectField(
                        inject,
                        "controlCenterPanelExpanded",
                    ) as? Boolean
                    if (controlCenter == true) return true
                    // 部分 HyperOS 版本只维护通知面板展开标记；控制中心属于通知面板。
                    val notificationPanel = Reflect.getObjectField(
                        inject,
                        "notificationPanelExpanded",
                    ) as? Boolean
                    if (notificationPanel == true) return true
                    if (controlCenter != null) return false
                }
            }
            val group = find(root, "statusIcons") ?: return false
            return runCatching {
                val controller = Reflect.getObjectField(group, "animatorController") ?: return false
                Reflect.getObjectField(controller, "controlPanelExpand") as? Boolean ?: false
            }.getOrDefault(false)
        }

        private fun nativeIgnoredSlots(group: ViewGroup? = null): List<String> {
            if (capturedNativeIgnored == null) {
                val current = group?.let(::currentIgnoredSlots).orEmpty()
                val blocked = runCatching {
                    val clazz = Reflect.findClass(
                        "com.android.systemui.statusbar.phone.MiuiIconManagerUtils",
                        context.classLoader,
                    )
                    @Suppress("UNCHECKED_CAST")
                    (Reflect.getStaticObjectField(clazz, "RIGHT_BLOCK_LIST") as? List<String>).orEmpty()
                }.getOrDefault(emptyList())
                // 当前容器列表才包含 vpn 等运行时动态项；静态表只作为兜底。
                capturedNativeIgnored = (current + blocked).distinct()
            }
            return capturedNativeIgnored.orEmpty()
        }

        private fun currentIgnoredSlots(group: ViewGroup): List<String> =
            runCatching {
                @Suppress("UNCHECKED_CAST")
                (Reflect.getObjectField(group, "ignoredSlots") as? List<String>).orEmpty()
            }.getOrDefault(emptyList())

        private fun findChildBySlot(group: ViewGroup, slot: String): View? {
            for (i in 0 until group.childCount) {
                val child = group.getChildAt(i) ?: continue
                val childSlot = runCatching {
                    Reflect.callMethod(child, "getSlot") as? String
                }.getOrNull()
                if (childSlot == slot) return child
            }
            return null
        }

        private fun isIconVisible(view: View): Boolean = runCatching {
            Reflect.callMethod(view, "isIconVisible") as? Boolean ?: true
        }.getOrDefault(true)

        /** 特殊宿主没有 isIconVisible；空 TextView/空容器强制显示只会留下空位。 */
        private fun hasVisibleContent(view: View, id: String): Boolean = when {
            id == StatusBarIconRegistry.BATTERY -> true
            view is TextView -> !view.text.isNullOrEmpty()
            view is ViewGroup -> view.childCount > 0
            else -> view.visibility == View.VISIBLE
        }

        /**
         * 普通状态栏把 NotificationIconContainer 放在 fullscreen_notification_icon_area，
         * 刘海/挖孔形态才放到 notification_icon_area；两处都按实际承载容器选择。
         */
        private fun notificationArea(root: ViewGroup): ViewGroup? {
            val drip = find(root, "notification_icon_area") as? ViewGroup
            val fullscreen = find(root, "fullscreen_notification_icon_area") as? ViewGroup
            fun hasContainer(area: ViewGroup?): Boolean {
                area ?: return false
                return find(area, "notificationIcons") != null ||
                    firstChildOfType(area, "NotificationIconContainer") != null
            }
            return when {
                hasContainer(fullscreen) -> fullscreen
                hasContainer(drip) -> drip
                fullscreen != null -> fullscreen
                else -> drip
            }
        }

        private fun find(root: View, name: String): View? {
            val id = root.resources.getIdentifier(name, "id", StatusBarIconKeys.TARGET)
            return if (id == 0) null else root.findViewById(id)
        }

        private fun firstChildOfType(root: ViewGroup, simpleName: String): View? {
            for (i in 0 until root.childCount) {
                val child = root.getChildAt(i)
                if (child.javaClass.simpleName == simpleName) return child
                if (child is ViewGroup) firstChildOfType(child, simpleName)?.let { return it }
            }
            return null
        }

        private fun findSpeedView(root: ViewGroup): View? {
            for (i in 0 until root.childCount) {
                val child = root.getChildAt(i)
                if (child.javaClass.simpleName == "NetworkSpeedView") return child
            }
            return null
        }

        private fun register() {
            if (!attached.compareAndSet(false, true)) return
            runCatching {
                context.registerReceiver(receiver, filter)
            }.recoverCatching {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            }.onFailure {
                android.util.Log.w("HyperRefineStatusBar", "receiver register failed", it)
            }
        }

        private fun unregister() {
            if (!attached.compareAndSet(true, false)) return
            runCatching { context.unregisterReceiver(receiver) }
        }

        private fun recyclePreDraw() {
            preDraw?.let { rootRef.get()?.viewTreeObserver?.removeOnPreDrawListener(it) }
            preDraw = null
        }
    }

    private data class SpeedStyleState(
        val container: FrameLayout?,
        val numberText: TextView,
        val unitText: TextView,
        val numberGravity: Int,
        val unitGravity: Int,
        val numberTranslationX: Float,
        val unitTranslationX: Float,
        val numberTranslation: Float,
        val unitTranslation: Float,
        val numberTextSize: Float,
        val unitTextSize: Float,
    )

    private data class DotDrawState(
        val count: Int,
        val progress: Float,
        val radius: Float,
        val color: Int,
        val padding: Float,
    )

    private data class BaseView(
        val visibility: Int,
        val translationX: Float,
        val translationY: Float,
        val scaleX: Float,
        val scaleY: Float,
        val alpha: Float,
        val marginStart: Int,
        val marginEnd: Int,
        val paddingStart: Int,
        val paddingEnd: Int,
        val gravity: Int,
        val textAlignment: Int,
    )
}
