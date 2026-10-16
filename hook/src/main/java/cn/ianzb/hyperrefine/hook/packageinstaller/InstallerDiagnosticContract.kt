package cn.ianzb.hyperrefine.hook.packageinstaller

/** Installer-only troubleshooting transport. This never grants an active feature status. */
object InstallerDiagnosticContract {
    const val MODULE_PACKAGE = "cn.ianzb.hyperrefine"
    const val TARGET_PACKAGE = "com.miui.packageinstaller"
    const val RECEIVER_CLASS = "cn.ianzb.hyperrefine.experimental.packageinstaller.InstallerDiagnosticReceiver"
    const val ACTION = "cn.ianzb.hyperrefine.action.INSTALLER_DIAGNOSTIC"
    const val BUILD_ID = "installer-r9"
    const val EXTRA_BUILD_ID = "build_id"
    const val EXTRA_MODULE_VERSION = "module_version"
    const val EXTRA_BOOT_COUNT = "boot_count"
    const val EXTRA_SOURCE_PACKAGE = "source_package"
    const val EXTRA_SOURCE_PROCESS = "source_process"
    const val EXTRA_SOURCE_PID = "source_pid"
    const val EXTRA_GENERATED_ELAPSED = "generated_elapsed"
    const val EXTRA_TRACE = "trace"
    const val MAX_TRACE_LINES = 64
    const val MAX_LINE_CHARS = 384
    const val MAX_REPORT_AGE_MS = 300_000L
}
