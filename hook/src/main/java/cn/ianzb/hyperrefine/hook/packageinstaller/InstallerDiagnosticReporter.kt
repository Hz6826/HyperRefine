package cn.ianzb.hyperrefine.hook.packageinstaller

import android.app.Application
import android.app.BroadcastOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import cn.ianzb.hyperrefine.hook.xposed.HookHelper

/** Bounded, best-effort diagnostics for this installer only. Never receives installation arguments. */
internal object InstallerDiagnosticReporter {
    private val lines = ArrayDeque<String>()
    private var applicationContext: Context? = null
    private var attachAttempted = false
    private var scheduled = false
    private var active = false

    /** Starts only from this feature's Load, after the host has completed its shared entry checks. */
    fun begin() = safely {
        val firstLoad = synchronized(this) {
            if (active) false else { active = true; true }
        }
        if (firstLoad) { InstallerHitLedger.reset(); InstallerDiagnosticSnapshot.reset() }
        record("ready stage=load sdk=${Build.VERSION.SDK_INT} pid=${Process.myPid()}")
        val context = currentApplication()
        if (context != null) {
            synchronized(this) { applicationContext = context.applicationContext ?: context }
            schedule()
        } else if (!attachAttempted) {
            attachAttempted = true
            val attach = Application::class.java.getDeclaredMethod("attach", Context::class.java)
                .apply { isAccessible = true }
            HookHelper.hookAfter(attach) { param ->
                safely {
                    val attached = param.thisObject as? Application
                    val base = param.args.firstOrNull() as? Context
                    val ready = attached?.applicationContext ?: base?.applicationContext ?: base
                    if (ready != null) {
                        synchronized(this) { applicationContext = ready }
                        schedule()
                    }
                }
            }
        }
    }

    /** Call only with component metadata, static point names and exception classes. */
    fun record(line: String) = safely {
        synchronized(this) {
            if (!active) return@safely
            InstallerDiagnosticSnapshot.observe(line)
            if (lines.size == InstallerDiagnosticContract.MAX_TRACE_LINES) lines.removeFirst()
            lines.addLast(line.take(InstallerDiagnosticContract.MAX_LINE_CHARS))
        }
        schedule()
    }

    fun registered(point: String) = safely {
        InstallerHitLedger.register(point)
        schedule()
    }

    fun hit(point: String, event: InstallerHitLedger.Event) = safely {
        if (InstallerHitLedger.record(point, event)) schedule()
    }

    private fun schedule() {
        val shouldPost = synchronized(this) {
            if (applicationContext == null || scheduled) false else { scheduled = true; true }
        }
        if (!shouldPost) return
        val posted = try {
            Handler(Looper.getMainLooper()).post {
                safely {
                    val snapshot = synchronized(this) {
                        scheduled = false
                        applicationContext to ArrayList(lines)
                    }
                    snapshot.first?.let { send(it, snapshot.second) }
                }
            }
        } catch (failure: Throwable) {
            synchronized(this) { scheduled = false }
            throw failure
        }
        if (!posted) synchronized(this) { scheduled = false }
    }

    private fun send(context: Context, trace: ArrayList<String>) {
        val contract = InstallerDiagnosticContract
        val snapshot = ArrayList(InstallerDiagnosticSnapshot.merge(trace, contract.MAX_TRACE_LINES))
        val version = context.packageManager.getPackageInfo(contract.MODULE_PACKAGE, 0).longVersionCode
        val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        val intent = Intent(contract.ACTION)
            .setComponent(ComponentName(contract.MODULE_PACKAGE, contract.RECEIVER_CLASS))
            .putExtra(contract.EXTRA_BUILD_ID, contract.BUILD_ID)
            .putExtra(contract.EXTRA_MODULE_VERSION, version)
            .putExtra(contract.EXTRA_BOOT_COUNT, boot)
            .putExtra(contract.EXTRA_SOURCE_PACKAGE, context.packageName)
            .putExtra(contract.EXTRA_SOURCE_PROCESS, Application.getProcessName())
            .putExtra(contract.EXTRA_SOURCE_PID, Process.myPid())
            .putExtra(contract.EXTRA_GENERATED_ELAPSED, SystemClock.elapsedRealtime())
            .putStringArrayListExtra(contract.EXTRA_TRACE, snapshot)
            .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES or Intent.FLAG_RECEIVER_FOREGROUND)
        val options = BroadcastOptions.makeBasic().apply { setShareIdentityEnabled(true) }
        context.sendBroadcast(intent, null, options.toBundle())
    }

    private fun currentApplication(): Context? = runCatching {
        Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as? Context
    }.getOrNull()

    /** Diagnostic failures must not stop registration or change a business callback result. */
    private inline fun safely(block: () -> Unit) {
        try { block() } catch (failure: Throwable) {
            runCatching { HookHelper.log("PackageInstaller[r9]: diagnostic unavailable (${failure.javaClass.simpleName})") }
        }
    }
}
