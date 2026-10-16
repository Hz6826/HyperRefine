package cn.ianzb.hyperrefine.experimental.packageinstaller

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import cn.ianzb.hyperrefine.hook.packageinstaller.InstallerDiagnosticContract as Contract

/** Only the installed installer's real UID may submit a current-build, current-boot report. */
class InstallerDiagnosticReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Contract.ACTION) return
        val sender = runCatching { sentFromUid }.getOrDefault(-1)
        val expectedUid = runCatching {
            context.packageManager.getApplicationInfo(Contract.TARGET_PACKAGE, 0).uid
        }.getOrNull()
        if (sender < 0 || expectedUid == null || sender != expectedUid) return
        // Untrusted extras may have wrong parcel/value types. Reject the whole report.
        runCatching {
            val report = InstallerDiagnosticValidation.Envelope(
                buildId = intent.getStringExtra(Contract.EXTRA_BUILD_ID),
                moduleVersion = intent.getLongExtra(Contract.EXTRA_MODULE_VERSION, 0L),
                bootCount = intent.getIntExtra(Contract.EXTRA_BOOT_COUNT, -1),
                sourcePackage = intent.getStringExtra(Contract.EXTRA_SOURCE_PACKAGE),
                sourceProcess = intent.getStringExtra(Contract.EXTRA_SOURCE_PROCESS),
                sourcePid = intent.getIntExtra(Contract.EXTRA_SOURCE_PID, 0),
                generatedElapsed = intent.getLongExtra(Contract.EXTRA_GENERATED_ELAPSED, -1L),
                trace = intent.getStringArrayListExtra(Contract.EXTRA_TRACE) ?: return,
            )
            if (InstallerDiagnosticValidation.accepts(
                    report, sender, expectedUid, InstallerDiagnosticStore.currentVersion(context),
                    InstallerDiagnosticStore.currentBoot(context), SystemClock.elapsedRealtime(),
                )
            ) {
                InstallerDiagnosticStore.record(context, report)
            }
        }
    }
}
