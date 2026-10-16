package cn.ianzb.hyperrefine.hook.packageinstaller

/** Session-only snapshots. A reference with no metadata must never hide a later definition. */
internal class InstallerMethodSnapshots {
    private data class Snapshot(val method: InstallerMethod, val complete: Boolean)
    private val snapshots = mutableMapOf<String, Snapshot>()

    fun complete(descriptor: String): InstallerMethod? = snapshots[descriptor]?.takeIf { it.complete }?.method

    fun remember(method: InstallerMethod, declaredWithoutCode: Boolean): InstallerMethod {
        val normalized = method.copy(
            invokes = method.invokes.distinct().sorted(),
            readFields = method.readFields.distinct().sorted(),
            writtenFields = method.writtenFields.distinct().sorted(),
        )
        val complete = declaredWithoutCode || normalized.opcodes.isNotEmpty() ||
            normalized.strings.isNotEmpty() || normalized.annotationStrings.isNotEmpty() ||
            normalized.invokes.isNotEmpty() || normalized.readFields.isNotEmpty() || normalized.writtenFields.isNotEmpty()
        val previous = snapshots[normalized.descriptor]
        if (previous?.complete == true) {
            if (complete && previous.method != normalized) {
                throw InstallerMatchException("dex_index.metadata_conflict", 2,
                    "inconsistent complete method snapshots; dependent point preserves original behavior")
            }
            return previous.method
        }
        // Access flags in an empty reference are unknown. In particular, false isStatic is not evidence.
        snapshots[normalized.descriptor] = Snapshot(normalized, complete)
        return normalized
    }

    fun clear() = snapshots.clear()
}
