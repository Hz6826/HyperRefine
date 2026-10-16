package cn.ianzb.hyperrefine.hook.packageinstaller

/** Process-local callback observations. No arguments, package details, text or filesystem state. */
internal object InstallerHitLedger {
    enum class Event { CALL, BLOCK, RETAIN, COMPLETE, FAULT }
    private val points = setOf("layout_async", "layout_sync", "risk_scan", "intercept_check", "cloud_v2", "cloud_v3",
        "guard_tip", "frequent_check", "avl_upload", "ads_before", "ads_after", "ads_cancel", "guard_banner")
    private val counters = linkedMapOf<String, LongArray>()

    @Synchronized fun reset() = counters.clear()

    /** Call only after the owning group's transaction commits; reuse does not reset counts. */
    @Synchronized fun register(point: String) {
        if (point in points) counters.getOrPut(point) { LongArray(Event.entries.size) }
    }

    /** Returns true at first/power-of-two observations, limiting diagnostic scheduling. */
    @Synchronized fun record(point: String, event: Event): Boolean {
        val values = counters[point] ?: return false
        val value = values[event.ordinal]
        val next = if (value == Long.MAX_VALUE) value else value + 1
        values[event.ordinal] = next
        return next == 1L || next > 0 && (next and (next - 1)) == 0L
    }

    @Synchronized fun snapshotLines(): List<String> = counters.toSortedMap().map { (point, values) ->
        "hits point=$point registered=true callback_calls=${values[0]} blocked=${values[1]} retained=${values[2]} completion=${values[3]} callback_faults=${values[4]}"
    }

    /** Counters are appended separately so a first-hit FIFO entry being evicted cannot erase them. */
    @Synchronized fun mergeTrace(trace: List<String>, maxLines: Int): List<String> {
        require(maxLines >= points.size)
        val snapshots = snapshotLines()
        return trace.takeLast(maxLines - snapshots.size) + snapshots
    }
}
