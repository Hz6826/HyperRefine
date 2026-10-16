package cn.ianzb.hyperrefine.hook.packageinstaller

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class InstallerHitLedgerTest {
    @Before fun reset() = InstallerHitLedger.reset()

    @Test fun registeredButUnobservedProducerIsExplicitlyZero() {
        InstallerHitLedger.register("avl_upload")
        val line = InstallerHitLedger.snapshotLines().single()
        assertTrue(line.contains("registered=true callback_calls=0 blocked=0"))
    }

    @Test fun registerCannotResetSharedPointOrCreateArbitraryKeys() {
        InstallerHitLedger.register("intercept_check")
        InstallerHitLedger.record("intercept_check", InstallerHitLedger.Event.CALL)
        InstallerHitLedger.register("intercept_check")
        InstallerHitLedger.register("untrusted-argument")
        assertFalse(InstallerHitLedger.record("untrusted-argument", InstallerHitLedger.Event.CALL))
        assertEquals(1, InstallerHitLedger.snapshotLines().size)
        assertTrue(InstallerHitLedger.snapshotLines().single().contains("callback_calls=1"))
    }

    @Test fun uncommittedPointsDoNotAppearActive() {
        assertFalse(InstallerHitLedger.record("avl_upload", InstallerHitLedger.Event.CALL))
        assertTrue(InstallerHitLedger.snapshotLines().isEmpty())
    }

    @Test fun fifoEvictionCannotEraseCumulativeObservations() {
        InstallerHitLedger.register("avl_upload")
        InstallerHitLedger.record("avl_upload", InstallerHitLedger.Event.CALL)
        InstallerHitLedger.record("avl_upload", InstallerHitLedger.Event.BLOCK)
        val snapshot = InstallerHitLedger.mergeTrace((0..90).map { "trace $it" }, 64)
        assertEquals(64, snapshot.size)
        assertFalse(snapshot.any { it == "trace 0" })
        assertTrue(snapshot.last().contains("callback_calls=1 blocked=1"))
    }

    @Test fun callbackCompletionAndCallbackFaultAreIndependentOfShortCircuit() {
        InstallerHitLedger.register("layout_async")
        listOf(InstallerHitLedger.Event.CALL, InstallerHitLedger.Event.BLOCK, InstallerHitLedger.Event.FAULT).forEach {
            InstallerHitLedger.record("layout_async", it)
        }
        var line = InstallerHitLedger.snapshotLines().single()
        assertTrue(line.contains("blocked=1 retained=0 completion=0 callback_faults=1"))
        InstallerHitLedger.record("layout_async", InstallerHitLedger.Event.COMPLETE)
        line = InstallerHitLedger.snapshotLines().single()
        assertTrue(line.contains("completion=1"))
    }

    @Test fun allKnownPointsRemainInsideExistingTransportLimits() {
        listOf("layout_async", "layout_sync", "risk_scan", "intercept_check", "cloud_v2", "cloud_v3", "guard_tip",
            "frequent_check", "avl_upload", "ads_before", "ads_after", "ads_cancel", "guard_banner").forEach(InstallerHitLedger::register)
        val lines = InstallerHitLedger.mergeTrace((0..90).map { "trace $it" }, 64)
        assertEquals(64, lines.size)
        assertEquals(13, lines.count { it.startsWith("hits point=") })
        assertTrue(lines.all { it.length <= 384 })
    }

    @Test fun repeatedCallsAccumulateButScheduleAtBoundedIntervals() {
        InstallerHitLedger.register("guard_tip")
        val scheduled = (1..9).filter { InstallerHitLedger.record("guard_tip", InstallerHitLedger.Event.CALL) }
        assertEquals(listOf(1, 2, 4, 8), scheduled)
        assertTrue(InstallerHitLedger.snapshotLines().single().contains("callback_calls=9"))
    }

    @Test fun concurrentCallbacksHaveNoLostUpdates() {
        InstallerHitLedger.register("intercept_check")
        val workers = List(4) { Thread { repeat(1_000) { InstallerHitLedger.record("intercept_check", InstallerHitLedger.Event.CALL) } } }
        workers.forEach(Thread::start)
        workers.forEach(Thread::join)
        assertTrue(InstallerHitLedger.snapshotLines().single().contains("callback_calls=4000"))
    }
}
