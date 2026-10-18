package cn.ianzb.hyperrefine.hook.miuix

import android.view.View
import android.view.ViewGroup
import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.Reflect
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap

/**
 * 修复「文件管理」开启顶栏渐变模糊后，文件夹页文件列表上方边距过小、首个文件被路径栏遮挡的问题。
 *
 * 目标：`com.android.fileexplorer`。
 *
 * 触发链路（平板双栏实测）：
 * - `FileFragment` / `PadFileDetailFragment` 把 `navigation_bar_phone_r` 设为
 *   `ActionBarContainer` 的 sticky view；ActionBar 高度从 250px 增长到 382px（含 132px 路径栏）；
 * - 文件管理的 `NestedHeaderLayout` 没有开启 `enableStickyViewContentInset`，其内部
 *   `SpringBackLayout` 仍从 250px 开始布局，列表首行被 250–382px 的路径栏盖住；
 * - `mContentInsetTop` 由 `ActionBarOverlayLayout` 在 overlay 模式下才下发，文件管理实际
 *   收不到该回调，因此不能只 hook `onContentInsetChanged`。
 *
 * 这里直接按结构修正：只要当前 ActionBar 的 sticky view 确实是路径栏
 * （`navi_bar` / `navigation_bar`，或内部含 `path_gallery`），就把
 * `NestedHeaderLayout` 的 `SpringBackLayout` 下移「ActionBar 高度 - 当前 top」。
 * 手机普通布局、平板双栏、第三方文件选择器共用同一判定，不依赖具体 Fragment 类。
 */
class FileExplorerPathBarFix : BaseHook() {

    override val key: String = TopBarKeys.ADVANCED_FILE_EXPLORER

    /** 已经处理过的 root，避免 ActionBar/NestedHeader 布局回调重复 post。 */
    private val pendingRoots = Collections.synchronizedMap(WeakHashMap<View, Boolean>())

    private var getStickyView: Method? = null

    override fun init() {
        val loader = target.classLoader ?: return
        val barClass = Reflect.findClassIfExists(BAR_CLASS, loader) ?: run {
            HookHelper.log("$tag: $BAR_CLASS not found")
            return
        }
        getStickyView = Reflect.findMethodIfExists(barClass, "getStickyView")
        if (getStickyView == null) {
            HookHelper.log("$tag: ActionBarContainer.getStickyView not found")
            return
        }

        val nestedClass = Reflect.findClassIfExists(NESTED_HEADER, loader) ?: run {
            HookHelper.log("$tag: $NESTED_HEADER not found")
            return
        }
        val nestedLayout = Reflect.findMethodIfExists(
            nestedClass,
            "onLayout",
            java.lang.Boolean.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
        )
        if (nestedLayout != null) {
            HookHelper.hookAfter(nestedLayout) { param ->
                if (!enabled()) return@hookAfter
                val nested = param.thisObject as? View ?: return@hookAfter
                scheduleApply(nested)
            }
        } else {
            HookHelper.log("$tag: NestedHeaderLayout.onLayout not found")
        }

        // sticky 路径栏通常晚于 NestedHeaderLayout 首次布局设置；ActionBar 完成布局后补一次。
        val barLayout = Reflect.findMethodIfExists(
            barClass,
            "onLayout",
            java.lang.Boolean.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
        )
        if (barLayout != null) {
            HookHelper.hookAfter(barLayout) { param ->
                if (!enabled()) return@hookAfter
                val bar = param.thisObject as? View ?: return@hookAfter
                scheduleApply(bar.rootView)
            }
        }
        HookHelper.log("$tag: installed structural path-bar spacing fix")
    }

    private fun enabled(): Boolean = TopBarKeys.fileExplorerAdvancedEnabled()

    /** 同一帧合并 root 的重复请求。 */
    private fun scheduleApply(root: View) {
        if (pendingRoots.put(root, true) != null) return
        root.post {
            pendingRoots.remove(root)
            if (!enabled()) return@post
            applyForRoot(root)
        }
    }

    private fun applyForRoot(root: View) {
        val bar = findPathBarActionBar(root) ?: run {
            // 离开文件夹页时恢复，避免残留位移。
            resetForRoot(root)
            return
        }
        val pathHeight = bar.height
        if (pathHeight <= 0) return
        val nested = ArrayList<View>(2)
        collectByClassName(root, NESTED_HEADER, nested)
        for (view in nested) {
            if (!view.isAttachedToWindow) continue
            val spring = findSpringBack(view) ?: continue
            val shift = (pathHeight - spring.top).coerceAtLeast(0)
            if (spring.translationY != shift.toFloat()) {
                spring.translationY = shift.toFloat()
                HookHelper.log(
                    "$tag: path bar ${pathHeight}px, spring top ${spring.top}px, shift=${shift}px",
                )
            }
        }
    }

    private fun resetForRoot(root: View) {
        val nested = ArrayList<View>(2)
        collectByClassName(root, NESTED_HEADER, nested)
        for (view in nested) {
            val spring = findSpringBack(view) ?: continue
            if (spring.translationY != 0f) spring.translationY = 0f
        }
    }

    /** 在 root 下寻找「sticky view 是路径栏」的可见 ActionBar。 */
    private fun findPathBarActionBar(root: View): View? {
        val id = root.resources.getIdentifier(ACTION_BAR_CONTAINER, "id", root.context.packageName)
        if (id == 0) return null
        val candidates = ArrayList<View>(2)
        collectViewsWithId(root, id, candidates)
        var best: View? = null
        for (bar in candidates) {
            if (!bar.isShown || bar.height <= 0) continue
            if (!isPathBar(stickyView(bar))) continue
            if (bar.height > (best?.height ?: 0)) best = bar
        }
        return best
    }

    private fun stickyView(bar: View): View? =
        runCatching { getStickyView?.invoke(bar) as? View }.getOrNull()

    /** 路径栏判定：id 为 navi_bar / navigation_bar，或内部含 PathGallery。 */
    private fun isPathBar(view: View?): Boolean {
        if (view == null || !view.isShown || view.visibility != View.VISIBLE || view.height <= 0) {
            return false
        }
        if (view.id != View.NO_ID) {
            val name = runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull()
            if (name == "navigation_bar" || name == "navi_bar") return true
        }
        val galleryId = view.resources.getIdentifier("path_gallery", "id", view.context.packageName)
        return galleryId != 0 && view.findViewById<View>(galleryId) != null
    }

    private fun findSpringBack(nested: View): View? {
        if (nested !is ViewGroup) return null
        val id = nested.resources.getIdentifier("springbacklayout", "id", nested.context.packageName)
        if (id != 0) {
            nested.findViewById<View>(id)?.let { return it }
        }
        for (i in 0 until nested.childCount) {
            val child = nested.getChildAt(i)
            if (child.javaClass.name == SPRING_BACK) return child
        }
        return null
    }

    private fun collectViewsWithId(view: View, id: Int, out: MutableList<View>) {
        if (view.id == id) out.add(view)
        if (view !is ViewGroup) return
        for (i in 0 until view.childCount) {
            collectViewsWithId(view.getChildAt(i), id, out)
        }
    }

    private fun collectByClassName(view: View, className: String, out: MutableList<View>) {
        if (view.javaClass.name == className) out.add(view)
        if (view !is ViewGroup) return
        for (i in 0 until view.childCount) {
            collectByClassName(view.getChildAt(i), className, out)
        }
    }

    private companion object {
        const val NESTED_HEADER = "miuix.nestedheader.widget.NestedHeaderLayout"
        const val BAR_CLASS = "miuix.appcompat.internal.app.widget.ActionBarContainer"
        const val SPRING_BACK = "miuix.springback.view.SpringBackLayout"
        const val ACTION_BAR_CONTAINER = "action_bar_container"
    }
}
