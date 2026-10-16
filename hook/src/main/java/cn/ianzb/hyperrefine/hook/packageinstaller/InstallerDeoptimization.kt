package cn.ianzb.hyperrefine.hook.packageinstaller

import cn.ianzb.hyperrefine.hook.xposed.HookHelper

/** Derive bounded callers from discovered methods, never from a version's obfuscated names. */
internal object InstallerDeoptimization {
    fun callers(index: InstallerDexIndex, point: InstallerResolvedPoint): List<InstallerMethod> {
        val found = linkedMapOf<String, InstallerMethod>()
        var frontier = listOf(point.method.descriptor)
        repeat(2) {
            val next = mutableListOf<String>()
            frontier.forEach { descriptor ->
                index.callers(descriptor).forEach { caller ->
                    if (found.size < 64 && caller.name != "<init>" && caller.name != "<clinit>" &&
                        caller.descriptor != point.method.descriptor && caller.descriptor !in found
                    ) {
                        found[caller.descriptor] = caller
                        next += caller.descriptor
                    }
                }
            }
            frontier = next
        }
        return found.values.toList()
    }

    fun prepare(id: String, callers: List<InstallerMethod>, loader: ClassLoader?) {
        var success = 0
        callers.forEach { caller ->
            try {
                if (HookHelper.deoptimize(InstallerReflection.method(caller, loader))) success++
            } catch (failure: ReflectiveOperationException) {
                HookHelper.log("PackageInstaller[r9]: optional caller unavailable ($id, ${failure.javaClass.simpleName})")
            } catch (failure: LinkageError) {
                HookHelper.log("PackageInstaller[r9]: optional caller linkage unavailable ($id, ${failure.javaClass.simpleName})")
            } catch (failure: IllegalStateException) {
                HookHelper.log("PackageInstaller[r9]: optional caller contract changed ($id)")
            } catch (failure: SecurityException) {
                HookHelper.log("PackageInstaller[r9]: optional caller access denied ($id)")
            }
        }
        InstallerDiagnosticReporter.record("deoptimization point=$id success=$success/${callers.size}")
    }
}
