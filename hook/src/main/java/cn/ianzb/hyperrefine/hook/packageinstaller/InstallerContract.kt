package cn.ianzb.hyperrefine.hook.packageinstaller

import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Exact contract for the supplied ROM. Unknown builds preserve their own behaviour. */
internal object InstallerContract {
    fun supports(sdk: Int, packageName: String, processName: String, hasSplits: Boolean): Boolean =
        sdk == 37 && packageName == PackageInstallerKeys.PACKAGE_NAME &&
            processName == PackageInstallerKeys.PACKAGE_NAME && !hasSplits

    fun method(
        owner: Class<*>,
        name: String,
        returnType: String,
        isStatic: Boolean,
        vararg parameters: String,
    ): Method {
        val matches = owner.declaredMethods.filter {
            it.name == name && it.returnType.name == returnType &&
                Modifier.isStatic(it.modifiers) == isStatic &&
                it.parameterTypes.map(Class<*>::getName) == parameters.toList()
        }
        check(matches.size == 1) { "${owner.name}#$name: expected one exact method, found ${matches.size}" }
        return matches.single().apply { isAccessible = true }
    }

    /** Roll back this feature's handles if any install fails; other features keep running. */
    fun installAtomically(installers: List<() -> (() -> Unit)>) {
        val removers = mutableListOf<() -> Unit>()
        try {
            installers.forEach { removers += it() }
        } catch (failure: Throwable) {
            removers.asReversed().forEach { remove ->
                try {
                    remove()
                } catch (rollback: Throwable) {
                    failure.addSuppressed(rollback)
                }
            }
            throw failure
        }
    }
}
