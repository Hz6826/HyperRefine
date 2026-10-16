package cn.ianzb.hyperrefine.hook.packageinstaller

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class InstallerDiagnosticSnapshotTest {
    @Before fun reset() { InstallerDiagnosticSnapshot.reset(); InstallerHitLedger.reset() }

    @Test fun discoveryFailureAndZeroHitSurviveEventEviction() {
        val failure = "match_failed option=packageinstaller_disable_ads group=main reason=layout_async: candidates=0"
        InstallerDiagnosticSnapshot.observe(failure)
        InstallerHitLedger.register("avl_upload")
        val lines = InstallerDiagnosticSnapshot.merge((1..100).map { "event $it" }, 64)
        assertEquals(64, lines.size)
        assertEquals(1, lines.count { it == failure })
        assertTrue(lines.last().contains("callback_calls=0 blocked=0"))
    }

    @Test fun stableFailureKeysUpdateWithoutUnboundedGrowthOrRawArguments() {
        repeat(100) { InstallerDiagnosticSnapshot.observe("match_failed option=test group=main reason=$it") }
        InstallerDiagnosticSnapshot.observe("runtime target argument text")
        val lines = InstallerDiagnosticSnapshot.merge(emptyList(), 64)
        assertEquals(listOf("match_failed option=test group=main reason=99"), lines)
    }

    @Test fun pinnedRecordsHaveStrictCountAndLengthBounds() {
        repeat(100) { InstallerDiagnosticSnapshot.observe("probe role_$it detail=${"x".repeat(500)}") }
        assertEquals(25, InstallerDiagnosticSnapshot.merge(emptyList(), 64).size)
        assertTrue(InstallerDiagnosticSnapshot.merge(emptyList(), 64).all { it.length == 384 })
    }

    @Test fun existingTransportCapacityHoldsPinnedRecordsAndAllThirteenCounters() {
        repeat(25) { InstallerDiagnosticSnapshot.observe("probe role_$it detail=fixed") }
        listOf("layout_async", "layout_sync", "risk_scan", "intercept_check", "cloud_v2", "cloud_v3", "guard_tip",
            "frequent_check", "avl_upload", "ads_before", "ads_after", "ads_cancel", "guard_banner").forEach(InstallerHitLedger::register)
        val lines = InstallerDiagnosticSnapshot.merge((1..100).map { "event $it" }, 64)
        assertEquals(64, lines.size)
        assertEquals(13, lines.count { it.startsWith("hits point=") })
        assertEquals(25, lines.count { it.startsWith("probe ") })
    }

    @Test fun finalSummarySurvivesIndependentGroupAndProbeSaturation() {
        repeat(14) { InstallerDiagnosticSnapshot.observe("match_failed option=test group=$it reason=unavailable") }
        repeat(20) { InstallerDiagnosticSnapshot.observe("probe role_$it detail=fixed") }
        InstallerDiagnosticSnapshot.observe("summary installed=4/6 partial=2")
        InstallerDiagnosticSnapshot.observe("summary installed=5/6 partial=1")
        val lines = InstallerDiagnosticSnapshot.merge(emptyList(), 64)
        assertEquals(25, lines.size)
        assertEquals(14, lines.count { it.startsWith("match_failed ") })
        assertEquals(1, lines.count { it.startsWith("summary ") })
        assertTrue(lines.contains("summary installed=5/6 partial=1"))
    }
}
