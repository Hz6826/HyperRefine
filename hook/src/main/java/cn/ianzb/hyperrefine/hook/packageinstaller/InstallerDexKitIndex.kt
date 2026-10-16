package cn.ianzb.hyperrefine.hook.packageinstaller

import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.enums.AnnotationEncodeValueType
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.result.AnnotationData
import org.luckypray.dexkit.result.AnnotationEncodeValue
import org.luckypray.dexkit.result.FieldUsingType
import org.luckypray.dexkit.result.MethodData
import org.luckypray.dexkit.wrap.DexMethod
import java.lang.reflect.Modifier

/**
 * Installer-only, read-only DexKit session. This does not initialize the shared cache manager,
 * create a cache directory, export DEX, or retain native-backed data after close.
 */
internal class InstallerDexKitIndex private constructor(private val bridge: DexKitBridge) : InstallerDexIndex {
    private var closed = false
    private val methods = InstallerMethodSnapshots()
    private val classes = mutableMapOf<String, InstallerClass?>()
    private val fields = mutableMapOf<String, InstallerField?>()

    @Synchronized
    override fun findMethods(query: InstallerMethodQuery): List<InstallerMethod> {
        requireOpen()
        val types = query.parameterTypes
        if (types != null && query.parameterCount != null && types.size != query.parameterCount) return emptyList()
        val matcher = MethodMatcher.create()
        if (query.strings.isNotEmpty()) matcher.usingEqStrings(query.strings)
        query.owner?.let { matcher.declaredClass(it, StringMatchType.Equals) }
        query.returnType?.let { matcher.returnType(it, StringMatchType.Equals) }
        (query.parameterCount ?: types?.size)?.let { matcher.paramCount(it) }
        // The model describes integral values, independent of const versus const-wide encoding.
        // Match either native Int or Long encoding when the requested value fits both.
        query.numbers.forEach { number ->
            val alternatives = mutableListOf(MethodMatcher.create().usingNumbers(number))
            if (number in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
                alternatives += MethodMatcher.create().usingNumbers(number.toInt())
            }
            matcher.addAllOf(MethodMatcher.create().anyOf(alternatives))
        }
        val found = bridge.findMethod(FindMethod.create().matcher(matcher)).asSequence()
            .filter { data ->
                (query.isStatic == null || Modifier.isStatic(data.modifiers) == query.isStatic) &&
                    (types == null || data.paramTypeNames.size == types.size &&
                        types.indices.all { index -> types[index] == null || types[index] == data.paramTypeNames[index] })
            }
            .map(::materialize)
            .toList()
        return canonicalSnapshots(found)
    }

    @Synchronized
    override fun method(descriptor: String): InstallerMethod? {
        requireOpen()
        methods.complete(descriptor)?.let { return it }
        // A descriptor lookup may identify a reference. Ask the definition search with its full
        // signature before accepting metadata-free results. Missing results are not cached.
        val definitions = exactMethods(descriptor).map(::materialize)
        return methods.complete(descriptor) ?: definitions.firstOrNull() ?: bridge.getMethodData(descriptor)?.let(::materialize)
    }

    @Synchronized
    override fun callers(descriptor: String): List<InstallerMethod> {
        requireOpen()
        val targets = exactMethods(descriptor).ifEmpty { listOfNotNull(bridge.getMethodData(descriptor)) }
        return canonicalSnapshots(targets.flatMap { it.callers }.map(::materialize))
    }

    @Synchronized
    override fun clazz(name: String): InstallerClass? {
        requireOpen()
        if (classes.containsKey(name)) return classes[name]
        val result = bridge.getClassData(name)?.let { data ->
            InstallerClass(
                name = data.name,
                superName = data.superClass?.name,
                interfaces = data.interfaces.map { it.name }.distinct().sorted(),
                methods = data.methods.map { it.descriptor }.distinct().sorted(),
                fields = data.fields.map { it.descriptor }.distinct().sorted(),
                annotationStrings = annotationStrings(data.annotations),
            )
        }
        classes[name] = result
        return result
    }

    @Synchronized
    override fun field(descriptor: String): InstallerField? {
        requireOpen()
        if (fields.containsKey(descriptor)) return fields[descriptor]
        val result = bridge.getFieldData(descriptor)?.let { data ->
            InstallerField(
                descriptor = data.descriptor,
                owner = data.className,
                name = data.name,
                type = data.typeName,
                isStatic = Modifier.isStatic(data.modifiers),
                annotationStrings = annotationStrings(data.annotations),
            )
        }
        fields[descriptor] = result
        return result
    }

    private fun materialize(data: MethodData): InstallerMethod {
        val usingFields = data.usingFields
        val result = InstallerMethod(
            descriptor = data.descriptor,
            owner = data.className,
            name = data.name,
            returnType = data.returnTypeName,
            parameterTypes = data.paramTypeNames.toList(),
            isStatic = Modifier.isStatic(data.modifiers),
            strings = data.usingStrings.toSet(),
            annotationStrings = annotationStrings(data.annotations),
            invokes = data.invokes.map { it.descriptor },
            readFields = usingFields.filter { it.usingType == FieldUsingType.Read }.map { it.field.descriptor },
            writtenFields = usingFields.filter { it.usingType == FieldUsingType.Write }.map { it.field.descriptor },
            opcodes = data.opCodes.toList(),
        )
        return methods.remember(result, Modifier.isAbstract(data.modifiers) || Modifier.isNative(data.modifiers))
    }

    private fun exactMethods(descriptor: String): List<MethodData> {
        val signature = DexMethod(descriptor)
        val matcher = MethodMatcher.create()
            .declaredClass(signature.className, StringMatchType.Equals)
            .name(signature.name, StringMatchType.Equals)
            .returnType(signature.returnTypeName, StringMatchType.Equals)
            .paramTypes(signature.paramTypeNames)
        return bridge.findMethod(FindMethod.create().matcher(matcher)).filter { it.descriptor == descriptor }
    }

    private fun canonicalSnapshots(found: List<InstallerMethod>): List<InstallerMethod> = found
        .map { methods.complete(it.descriptor) ?: it }.distinctBy { it.descriptor }.sortedBy { it.descriptor }

    private fun annotationStrings(annotations: List<AnnotationData>): Set<String> {
        val values = linkedSetOf<String>()
        annotations.forEach { annotation -> annotation.elements.forEach { flatten(it.value, values, 0) } }
        return values
    }

    private fun flatten(value: AnnotationEncodeValue, strings: MutableSet<String>, depth: Int) {
        check(depth <= 32) { "installer annotation nesting exceeds limit" }
        when (value.type) {
            AnnotationEncodeValueType.StringValue -> strings += value.stringValue()
            AnnotationEncodeValueType.ArrayValue -> value.arrayValue().values.forEach { flatten(it, strings, depth + 1) }
            AnnotationEncodeValueType.AnnotationValue -> value.annotationValue().elements.forEach { flatten(it.value, strings, depth + 1) }
            else -> Unit
        }
    }

    private fun requireOpen() = check(!closed) { "installer DEX index is closed" }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        try {
            bridge.close()
        } finally {
            methods.clear()
            classes.clear()
            fields.clear()
        }
    }

    companion object {
        fun open(sourceDir: String): InstallerDexKitIndex {
            System.loadLibrary("dexkit")
            return InstallerDexKitIndex(DexKitBridge.create(sourceDir))
        }
    }
}
