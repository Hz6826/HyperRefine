package cn.ianzb.hyperrefine.hook.packageinstaller

/** Pure metadata: no target code is invoked while discovering a business capability. */
internal data class InstallerMethod(
    val descriptor: String,
    val owner: String,
    val name: String,
    val returnType: String,
    val parameterTypes: List<String>,
    val isStatic: Boolean,
    val strings: Set<String> = emptySet(),
    val annotationStrings: Set<String> = emptySet(),
    val invokes: List<String> = emptyList(),
    val readFields: List<String> = emptyList(),
    val writtenFields: List<String> = emptyList(),
    val opcodes: List<Int> = emptyList(),
)

internal data class InstallerField(
    val descriptor: String,
    val owner: String,
    val name: String,
    val type: String,
    val isStatic: Boolean,
    val annotationStrings: Set<String> = emptySet(),
)

internal data class InstallerClass(
    val name: String,
    val superName: String?,
    val interfaces: List<String> = emptyList(),
    val methods: List<String> = emptyList(),
    val fields: List<String> = emptyList(),
    val annotationStrings: Set<String> = emptySet(),
)

internal data class InstallerMethodQuery(
    val strings: Set<String> = emptySet(),
    val numbers: Set<Long> = emptySet(),
    val owner: String? = null,
    val returnType: String? = null,
    val parameterTypes: List<String?>? = null,
    val parameterCount: Int? = null,
    val isStatic: Boolean? = null,
)

internal interface InstallerDexIndex : AutoCloseable {
    fun findMethods(query: InstallerMethodQuery): List<InstallerMethod>
    fun method(descriptor: String): InstallerMethod?
    fun callers(descriptor: String): List<InstallerMethod>
    fun clazz(name: String): InstallerClass?
    fun field(descriptor: String): InstallerField?
    override fun close() {}
}

/** An independently matched point plus only the extra types needed by its return policy. */
internal data class InstallerResolvedPoint(
    val id: String,
    val method: InstallerMethod,
    val callback: InstallerMethod? = null,
    val payloadClass: String? = null,
    val resultClass: String? = null,
    val resultBaseClass: String? = null,
    val singletonField: InstallerField? = null,
    val guardedItemClass: String? = null,
    val evidence: List<String> = emptyList(),
    val holderField: InstallerField? = null,
    val containerGetter: InstallerMethod? = null,
    val guardBanner: InstallerGuardBannerAccess? = null,
)

internal class InstallerMatchException(val role: String, val candidateCount: Int, detail: String) :
    IllegalStateException("$role: candidates=$candidateCount; $detail")

internal fun <T> uniqueInstallerMatch(role: String, candidates: List<T>): T {
    if (candidates.size != 1) throw InstallerMatchException(role, candidates.size, "expected one verified business boundary")
    return candidates.single()
}

internal fun requireInstallerMatch(role: String, condition: Boolean, detail: String) {
    if (!condition) throw InstallerMatchException(role, 0, detail)
}
