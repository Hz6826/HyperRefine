package cn.ianzb.hyperrefine.hook.packageinstaller

/** Retains bounded discovery failures separately from the rolling event log. No target arguments. */
internal object InstallerDiagnosticSnapshot {
    private val retained = linkedMapOf<String, String>()
    @Synchronized fun reset() = retained.clear()
    @Synchronized fun observe(line: String) {
        val key = when {
            line.startsWith("match_failed option=") -> line.substringBefore(" reason=")
            line.startsWith("installation_failed option=") -> line.substringBefore(" type=")
            line.startsWith("probe ") -> line.substringBefore(" detail=")
            line.startsWith("summary ") -> "summary"
            else -> return
        }
        // More independent groups can fill the bounded probe area before mounting finishes.
        // Always retain the final registration summary without increasing transport capacity.
        if (key == "summary" && key !in retained && retained.size == 25) {
            retained.remove(retained.keys.lastOrNull { it.startsWith("probe ") } ?: retained.keys.last())
        }
        if (key in retained || retained.size < 25) retained[key] = line.take(384)
    }
    @Synchronized fun merge(trace: List<String>, maxLines: Int): List<String> {
        val pinned = retained.values.toList()
        val hits = InstallerHitLedger.snapshotLines()
        require(maxLines >= 38)
        return trace.filterNot { it in pinned }.takeLast(maxLines - pinned.size - hits.size) + pinned + hits
    }
}
