package cn.ianzb.hyperrefine.experimental.packageinstaller

import cn.ianzb.hyperrefine.hook.packageinstaller.InstallerDiagnosticContract as Contract

/** Pure validation shared by the receiver and host unit tests. No anonymous-sender fallback. */
internal object InstallerDiagnosticValidation {
    data class Envelope(
        val buildId: String?,
        val moduleVersion: Long,
        val bootCount: Int,
        val sourcePackage: String?,
        val sourceProcess: String?,
        val sourcePid: Int,
        val generatedElapsed: Long,
        val trace: List<String>,
    )

    fun accepts(
        report: Envelope,
        senderUid: Int,
        installerUid: Int?,
        currentVersion: Long,
        currentBoot: Int,
        nowElapsed: Long,
    ): Boolean {
        if (senderUid < 0 || installerUid == null || senderUid != installerUid) return false
        if (report.sourcePackage != Contract.TARGET_PACKAGE || report.sourceProcess != Contract.TARGET_PACKAGE) return false
        if (report.buildId != Contract.BUILD_ID || currentVersion <= 0 || report.moduleVersion != currentVersion) return false
        if (currentBoot < 0 || report.bootCount != currentBoot || report.sourcePid <= 0) return false
        if (report.generatedElapsed < 0 || report.generatedElapsed > nowElapsed + 2_000L) return false
        if (nowElapsed - report.generatedElapsed > Contract.MAX_REPORT_AGE_MS) return false
        return validTrace(report.trace)
    }

    fun validTrace(trace: List<String>): Boolean =
        trace.isNotEmpty() && trace.size <= Contract.MAX_TRACE_LINES && trace.all { line ->
            line.isNotBlank() && line.length <= Contract.MAX_LINE_CHARS &&
                line.none { it < ' ' || it == '\u007f' }
        }
}
