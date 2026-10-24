package cn.ianzb.hyperrefine.hook.systemui.statusbar

import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.Reflect
import cn.ianzb.hyperrefine.hook.xposed.HookParam
import io.github.libxposed.api.XposedInterface.HookHandle
import java.lang.reflect.Field
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

internal object StatusBarMembers {
    fun after(executable: Executable, callback: (HookParam) -> Unit): HookHandle {
        HookHelper.deoptimize(executable)
        return HookHelper.hookAfter(executable, callback = callback)
    }

    fun before(executable: Executable, callback: (HookParam) -> Unit): HookHandle {
        HookHelper.deoptimize(executable)
        return HookHelper.hookBefore(executable, callback = callback)
    }
    fun field(type: Class<*>, name: String): Field {
        var current: Class<*>? = type
        while (current != null) {
            current.declaredFields.firstOrNull { it.name == name }?.let {
                return it.apply { isAccessible = true }
            }
            current = current.superclass
        }
        throw NoSuchFieldException(type.name + "#" + name)
    }

    fun methods(type: Class<*>, name: String, predicate: (Method) -> Boolean): List<Method> {
        val result = linkedMapOf<List<Class<*>>, Method>()
        var current: Class<*>? = type
        while (current != null) {
            current.declaredMethods.filter { it.name == name && predicate(it) }
                .forEach { result.putIfAbsent(it.parameterTypes.toList(), it.apply { isAccessible = true }) }
            current = current.superclass
        }
        check(result.isNotEmpty()) { type.name + "#" + name + ": no compatible method" }
        return result.values.toList()
    }

    fun install(installers: List<() -> HookHandle>) {
        val handles = mutableListOf<HookHandle>()
        try { installers.forEach { handles += it() } }
        catch (error: Throwable) {
            handles.asReversed().forEach { runCatching { it.unhook() } }
            throw error
        }
    }

    /** R8 can remove ReadonlyStateFlow's unused Job argument in the host APK. */
    fun readonlyFlow(wrapper: Class<*>, flow: Any): Any {
        val constructors = wrapper.declaredConstructors.filter {
            it.parameterCount in 1..2 && it.parameterTypes[0].isInstance(flow) &&
                (it.parameterCount == 1 || !it.parameterTypes[1].isPrimitive)
        }
        val constructor = constructors.singleOrNull { it.parameterCount == 1 }
            ?: constructors.singleOrNull { it.parameterCount == 2 }
            ?: throw NoSuchMethodException(wrapper.name + ": no compatible read-only flow constructor")
        constructor.isAccessible = true
        return if (constructor.parameterCount == 1) constructor.newInstance(flow)
        else constructor.newInstance(flow, null)
    }

    /** The host Pair comes from its own Kotlin loader; do not cast it to the module's Pair. */
    fun mobileVisibility(value: Any?): StatusBarPolicy.Visibility? {
        if (value is Boolean) return StatusBarPolicy.Visibility(value, value)
        if (value?.javaClass?.name != "kotlin.Pair") return null
        val root = Reflect.callMethod(value, "getFirst") as? Boolean ?: return null
        val signal = Reflect.callMethod(value, "getSecond") as? Boolean ?: return null
        return StatusBarPolicy.Visibility(root, root && signal)
    }
}

internal class StatusBarGuard(private val label: String) {
    private val warned = AtomicBoolean()
    fun run(block: () -> Unit) {
        try { block() } catch (error: Throwable) {
            if (warned.compareAndSet(false, true)) {
                HookHelper.log(label + ": unsupported ROM member", error)
            }
        }
    }
}

/** Use the host's Kotlin flow classes; no bundled coroutine dependency or polling. */
internal class StatusBarFlows(private val loader: ClassLoader?) {
    private val factory = Reflect.findClass("kotlinx.coroutines.flow.StateFlowKt", loader)
        .getDeclaredMethod("MutableStateFlow", Any::class.java).apply { isAccessible = true }

    fun mutable(value: Any): Any = checkNotNull(factory.invoke(null, value))
    fun replace(owner: Any, field: Field, flow: Any) {
        val assigned = if (field.type.isInstance(flow)) flow else {
            val wrapper = Reflect.findClass("kotlinx.coroutines.flow.ReadonlyStateFlow", loader)
            StatusBarMembers.readonlyFlow(wrapper, flow)
        }
        require(field.type.isInstance(assigned)) { field.name + ": unexpected flow type" }
        field.set(owner, assigned)
    }
}
