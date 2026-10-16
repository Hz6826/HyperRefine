package cn.ianzb.hyperrefine.experimental.packageinstaller

import cn.ianzb.hyperrefine.hook.packageinstaller.InstallerDiagnosticContract as Contract
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallerDiagnosticValidationTest {
    private val good = InstallerDiagnosticValidation.Envelope(
        Contract.BUILD_ID, 22L, 8, Contract.TARGET_PACKAGE, Contract.TARGET_PACKAGE,
        1234, 900_000L, listOf("entry sdk=37 enabled=6/6"),
    )

    private fun accept(
        report: InstallerDiagnosticValidation.Envelope = good,
        sender: Int = 10123,
        expectedUid: Int? = 10123,
        version: Long = 22L,
        boot: Int = 8,
        now: Long = 1_000_000L,
    ) = InstallerDiagnosticValidation.accepts(report, sender, expectedUid, version, boot, now)

    @Test fun acceptsCurrentTrustedReport() {
        assertTrue(accept())
        assertFalse(accept(good.copy(sourcePid = 0)))
    }

    @Test fun rejectsUnknownOrWrongSenderEvenWithCorrectPackageExtras() {
        assertFalse(accept(sender = -1))
        assertFalse(accept(sender = 10124))
        assertFalse(accept(expectedUid = null))
    }

    @Test fun rejectsOtherPackagesAndSecondaryProcesses() {
        assertFalse(accept(good.copy(sourcePackage = "android")))
        assertFalse(accept(good.copy(sourceProcess = Contract.TARGET_PACKAGE + ":worker")))
        assertFalse(accept(good.copy(sourcePackage = null)))
    }

    @Test fun rejectsOldBuildOrWrongVersion() {
        assertFalse(accept(good.copy(buildId = "installer-r2")))
        assertFalse(accept(good.copy(moduleVersion = 21L)))
        assertFalse(accept(version = 0L))
    }

    @Test fun rejectsOtherBootOrUnknownCurrentBoot() {
        assertFalse(accept(good.copy(bootCount = 7)))
        assertFalse(accept(boot = -1))
    }

    @Test fun boundsReportAgeAndRejectsImpossibleTimestamps() {
        assertTrue(accept(good.copy(generatedElapsed = 1_000_000L - Contract.MAX_REPORT_AGE_MS)))
        assertFalse(accept(good.copy(generatedElapsed = 1_000_000L - Contract.MAX_REPORT_AGE_MS - 1L)))
        assertFalse(accept(good.copy(generatedElapsed = 1_002_001L)))
        assertFalse(accept(good.copy(generatedElapsed = -1L)))
        assertFalse(accept(good.copy(sourcePid = -1)))
    }

    @Test fun rejectsEmptyOrOversizeTraceWithoutTruncatingTrustedMeaning() {
        assertFalse(accept(good.copy(trace = emptyList())))
        assertFalse(accept(good.copy(trace = List(Contract.MAX_TRACE_LINES + 1) { "entry" })))
        assertFalse(accept(good.copy(trace = listOf("x".repeat(Contract.MAX_LINE_CHARS + 1)))))
        assertTrue(accept(good.copy(trace = List(Contract.MAX_TRACE_LINES) { "x".repeat(Contract.MAX_LINE_CHARS) })))
    }

    @Test fun rejectsMultilineAndControlCharacterInjection() {
        assertFalse(accept(good.copy(trace = listOf("entry\nforged result"))))
        assertFalse(accept(good.copy(trace = listOf("entry\u0000"))))
        assertFalse(accept(good.copy(trace = listOf("entry\u007f"))))
        assertFalse(accept(good.copy(trace = listOf("   "))))
    }
}
