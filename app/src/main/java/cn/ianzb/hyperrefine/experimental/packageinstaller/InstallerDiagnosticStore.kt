package cn.ianzb.hyperrefine.experimental.packageinstaller

import android.content.Context
import android.provider.Settings
import androidx.core.content.edit
import cn.ianzb.hyperrefine.hook.packageinstaller.InstallerDiagnosticContract as Contract
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

/** A single bounded report in module-private data; independent of HookStatusStore. */
internal object InstallerDiagnosticStore {
    data class Report(val receivedAtMillis: Long, val sourcePid: Int, val trace: List<String>)

    private const val PREFS = "installer_diagnostic_r3"
    private val mutableLatest = MutableStateFlow<Report?>(null)
    val latest: StateFlow<Report?> = mutableLatest.asStateFlow()

    fun currentBoot(context: Context): Int = runCatching {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    }.getOrDefault(-1)

    fun currentVersion(context: Context): Long = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
    }.getOrDefault(0L)

    fun reload(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val boot = currentBoot(context)
        val version = currentVersion(context)
        val scoped = boot >= 0 && version > 0 &&
            prefs.getInt("boot", -1) == boot && prefs.getLong("version", 0) == version &&
            prefs.getString("build", null) == Contract.BUILD_ID
        if (!scoped) {
            prefs.edit { clear() }
            mutableLatest.value = null
            return
        }
        mutableLatest.value = runCatching {
            val json = JSONArray(prefs.getString("trace", "[]"))
            check(json.length() in 1..Contract.MAX_TRACE_LINES)
            val trace = List(json.length()) { json.getString(it) }
            check(InstallerDiagnosticValidation.validTrace(trace))
            Report(prefs.getLong("received", 0), prefs.getInt("pid", 0), trace)
        }.getOrNull()
        if (mutableLatest.value == null) prefs.edit { clear() }
    }

    fun record(context: Context, report: InstallerDiagnosticValidation.Envelope) {
        val received = System.currentTimeMillis()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            clear()
            putInt("boot", report.bootCount)
            putLong("version", report.moduleVersion)
            putString("build", report.buildId)
            putLong("received", received)
            putInt("pid", report.sourcePid)
            putString("trace", JSONArray(report.trace).toString())
        }
        mutableLatest.value = Report(received, report.sourcePid, report.trace.toList())
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { clear() }
        mutableLatest.value = null
    }
}
