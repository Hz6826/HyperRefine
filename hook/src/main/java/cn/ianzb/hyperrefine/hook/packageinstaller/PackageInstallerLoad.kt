package cn.ianzb.hyperrefine.hook.packageinstaller

import android.os.Build
import cn.ianzb.hyperrefine.hook.base.BaseLoad
import cn.ianzb.hyperrefine.hook.base.PackageTarget
import cn.ianzb.hyperrefine.hook.prefs.HookPrefs
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import java.io.IOException
import java.util.zip.ZipFile

class PackageInstallerLoad : BaseLoad() {
    override val targetPackages = listOf(PackageInstallerKeys.PACKAGE_NAME)

    override fun onPackageLoaded(target: PackageTarget) {
        InstallerDiagnosticReporter.begin()
        try {
            configure(target)
        } catch (failure: Throwable) {
            InstallerDiagnosticReporter.record("load_configuration_failed type=${failure.javaClass.simpleName}")
            throw failure
        }
    }

    private fun configure(target: PackageTarget) {
        val enabled = PackageInstallerKeys.ALL.filter { HookPrefs.getBoolean(it, false) }
        InstallerDiagnosticReporter.record("settings enabled=${enabled.size}/6 mask=${PackageInstallerKeys.ALL.joinToString("") { if (it in enabled) "1" else "0" }}")
        InstallerDiagnosticReporter.record("environment sdk=${Build.VERSION.SDK_INT} process=${target.processName} splits=${target.applicationInfo?.splitSourceDirs?.size ?: 0}")
        HookHelper.log("PackageInstaller[r9]: entry sdk=${Build.VERSION.SDK_INT} process=${target.processName} enabled=${enabled.size}/6")
        if (enabled.isEmpty()) {
            InstallerDiagnosticReporter.record("skipped no_enabled_settings")
            HookHelper.log("PackageInstaller[r9]: no enabled installer settings received; no business hooks requested")
            return
        }
        val info = target.applicationInfo
        if (info == null) {
            InstallerDiagnosticReporter.record("skipped no_application_info")
            HookHelper.log("PackageInstaller[r9]: skipped, ApplicationInfo unavailable")
            return
        }
        if (!InstallerContract.supports(
                Build.VERSION.SDK_INT, target.packageName, target.processName,
                !info.splitSourceDirs.isNullOrEmpty(),
            )
        ) {
            InstallerDiagnosticReporter.record("skipped unsupported_environment")
            HookHelper.log("PackageInstaller[r9]: skipped, environment sdk=${Build.VERSION.SDK_INT} package=${target.packageName} process=${target.processName} splits=${info.splitSourceDirs?.size ?: 0}")
            return
        }
        // Fingerprints describe the component; each feature's semantics decide compatibility.
        try {
            val identity = ZipFile(info.sourceDir).use { InstallerCodeIdentity().inspect(it) }
            InstallerDiagnosticReporter.record("fingerprint readable=${identity.readable} ${identity.diagnostic}")
        } catch (failure: IOException) {
            InstallerDiagnosticReporter.record("fingerprint unavailable type=${failure.javaClass.simpleName}; continuing capability discovery")
        } catch (failure: SecurityException) {
            InstallerDiagnosticReporter.record("fingerprint read_denied; continuing capability discovery")
        }
        InstallerDiagnosticReporter.record("discovery semantic_per_feature; fingerprint_is_not_a_gate")
        initHook(InstallerBusinessHook(enabled), true)
        // BaseLoad installs queued hooks after this callback returns; this is not installation success.
        InstallerDiagnosticReporter.record("load hooks_queued=1")
    }
}
