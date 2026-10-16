package cn.ianzb.hyperrefine.hook.packageinstaller

import java.net.URI

/** Business-role discovery. No obfuscated owner or method name is an input to these rules. */
internal class InstallerNetworkRules @JvmOverloads constructor(private val index: InstallerDexIndex,
    private val nullReturnProof: (InstallerMethod) -> Boolean = { false }) {
    fun resolve(id: String): InstallerResolvedPoint {
        failedLayoutDiagnostics.remove(id)
        pendingLayoutDiagnostics.remove(id)
        if (id == "layout_async" || id == "layout_sync") {
            pendingLayoutDiagnostics[id] = listOf("probe ${id}_0 detail=response_model_stage_not_reached")
        }
        return try {
            when (id) {
                "layout_async" -> layoutAsync()
                "layout_sync" -> layoutSync()
                "intercept_check" -> intercept()
                "avl_upload" -> upload()
                "cloud_v2" -> cloudV2()
                "cloud_v3" -> cloudV3()
                else -> throw InstallerMatchException(id, 0, "unknown network business role")
            }
        } catch (failure: InstallerMatchException) {
            pendingLayoutDiagnostics[id]?.let { failedLayoutDiagnostics[id] = it }
            throw failure
        }
    }

    /** Static metadata only. A failed role contributes at most two bounded lines. */
    internal fun diagnosticLines(): List<String> = failedLayoutDiagnostics.values.flatten()

    private data class LayoutSource(val method: InstallerMethod, val requestTypes: List<String>)
    private data class LayoutRoot(val method: InstallerMethod, val requestTypes: List<String>)
    private data class LayoutGraph(val sources: List<LayoutSource>, val roots: List<LayoutRoot>)
    private data class LayoutClosure(val methods: List<InstallerMethod>, val visited: Int,
        val truncated: Boolean, val edges: Int, val depthStops: Int)
    private data class LayoutPayloadEvidence(val payloads: Set<String>, val fields: Set<String>,
        val getters: Set<String>, val apiTypes: Set<String>, val nullablePayloads: Set<String>)
    private data class LayoutModelCandidate(val root: LayoutRoot, val callback: InstallerMethod?,
        val evidence: LayoutPayloadEvidence)
    private val layoutGraph by lazy { discoverLayoutGraph() }
    private val layoutCoreKeys = setOf("packageName", "installSource", "newInstall", "appName", "isPersonalizedAdEnabled")
    private val layoutClosures = mutableMapOf<String, LayoutClosure>()
    private val layoutReferences = mutableMapOf<String, InstallerMethod?>()
    private val ambiguousLayoutReferences = mutableSetOf<String>()
    private val pendingLayoutDiagnostics = linkedMapOf<String, List<String>>()
    private val failedLayoutDiagnostics = linkedMapOf<String, List<String>>()
    private val layoutSchemas = mutableMapOf<String, Boolean>()

    /** Logs, optional request fields and the other completion style are not compatibility gates. */
    private fun layoutSync(): InstallerResolvedPoint {
        val stages = layoutStages()
        val endpoint = layoutEndpointRoots(stages)
        val signature = endpoint.filter { root ->
            root.method.returnType == "java.lang.Object" && requestShape(root) &&
                layoutContinuation(root.method.parameterTypes.last())
        }.distinctBy { it.method.descriptor }
        stages["signature"] = signature.size
        val evaluated = signature.map { LayoutModelCandidate(it, null, layoutPayload(it.method)) }
        recordLayoutModels("layout_sync", stages, evaluated)
        val model = evaluated.mapNotNull { candidate -> candidate.evidence.payloads.singleOrNull()?.let { candidate to it } }
        stages["model"] = model.size
        val metadataNull = model.mapNotNull { (candidate, payload) ->
            val method = candidate.root.method
            method.descriptor.takeIf { hasZero(method) && method.opcodes.any { it in NULL_CONSTANTS } &&
                RETURN_OBJECT in method.opcodes && (hasEmptyObjectReturn(method) || payload in candidate.evidence.nullablePayloads) }
        }.toSet()
        val operandNull = model.mapNotNull { (candidate, _) ->
            val method = candidate.root.method
            val proven = method.descriptor !in metadataNull && try { nullReturnProof(method) } catch (_: Throwable) { false }
            method.descriptor.takeIf { proven }
        }.toSet()
        stages["operand_null"] = operandNull.size
        val nullable = model.filter { (candidate, _) ->
            val method = candidate.root.method
            // The producer may keep its null register across logging calls. The typed response mapper
            // can independently prove the narrow empty return. An inlined/direct response needs that
            // narrow empty return on the producer itself; an erased Object return is insufficient.
            method.descriptor in operandNull || method.descriptor in metadataNull
        }
        stages["nullable"] = nullable.size
        val outer = outermostLayoutRoots(nullable.map { it.first.root })
        stages["entry"] = outer.size
        val root = uniqueLayout("layout_sync", outer, stages)
        val payload = nullable.single { it.first.root.method.descriptor == root.method.descriptor }.second
        return InstallerResolvedPoint("layout_sync", root.method, payloadClass = payload, evidence = listOf(
            "layout endpoint + core request-map keys + matching request argument roles",
            "unique layout payload schema; metadata empty completion or bounded operand-CFG zero return (not full path feasibility)",
            "routes=${layoutRoutes(root.method).joinToString()}; route alone never satisfies the response/null policy",
            "sync discovery is independent of callback API and diagnostic log text; ${stageText(stages)}",
        ))
    }

    private fun layoutAsync(): InstallerResolvedPoint {
        val stages = layoutStages()
        val endpoint = layoutEndpointRoots(stages)
        val signature = endpoint.filter { root ->
            root.method.returnType == "void" && requestShape(root) &&
                !layoutContinuation(root.method.parameterTypes.last())
        }.distinctBy { it.method.descriptor }
        stages["signature"] = signature.size
        val callbacks = signature.mapNotNull { root ->
            val contracts = index.findMethods(InstallerMethodQuery(owner = root.method.parameterTypes.last(),
                returnType = "void", parameterCount = 1, isStatic = false)).filter { it.name != "<init>" }
            contracts.singleOrNull()?.let { root to it }
        }
        stages["callback"] = callbacks.size
        val evaluated = callbacks.map { (root, callback) ->
            LayoutModelCandidate(root, callback, layoutPayload(root.method, callback.owner))
        }
        recordLayoutModels("layout_async", stages, evaluated)
        val model = evaluated.filter { candidate ->
            val callback = checkNotNull(candidate.callback)
            callback.owner != callback.parameterTypes.single() &&
                candidate.evidence.payloads.singleOrNull() == callback.parameterTypes.single()
        }
        stages["model"] = model.size
        val nullable = model.filter { hasNullCallbackFailure(checkNotNull(it.callback)) }
        stages["nullable"] = nullable.size
        val outer = outermostLayoutRoots(nullable.map { it.root })
        stages["entry"] = outer.size
        val root = uniqueLayout("layout_async", outer, stages)
        val callback = checkNotNull(nullable.single { it.root.method.descriptor == root.method.descriptor }.callback)
        return InstallerResolvedPoint("layout_async", root.method, callback = callback,
            payloadClass = callback.parameterTypes.single(), evidence = listOf(
                "layout endpoint + core request map + explicit call/Runnable execution chain",
                "callback agrees with unique layout response payload schema; existing null-only completion helper",
                "routes=${layoutRoutes(root.method).joinToString()}; route alone never satisfies the callback policy",
                "async discovery is independent of sync method and diagnostic log text; ${stageText(stages)}",
            ))
    }

    private fun discoverLayoutGraph(): LayoutGraph {
        val sources = index.findMethods(InstallerMethodQuery(strings = layoutCoreKeys)).mapNotNull { method ->
            val types = if (method.returnType in MAP_TYPES) method.parameterTypes else method.parameterTypes.dropLast(1)
            if (!requestRoles(types) || method.invokes.none { it in MAP_PUTS }) null else LayoutSource(method, types)
        }
        val roots = sources.flatMap { source ->
            reverseExecution(source.method).map { LayoutRoot(it, source.requestTypes) }
        }.distinctBy { it.method.descriptor to it.requestTypes }
        return LayoutGraph(sources, roots)
    }

    // Model counters come first so a bounded diagnostic retains the first failing response contract.
    private fun layoutStages() = linkedMapOf("payload_types" to 0, "field_reads" to 0, "getters" to 0,
        "api_types" to 0, "empty_model" to 0, "multi_model" to 0, "type_mismatch" to 0,
        "map_sources" to layoutGraph.sources.size,
        "producer_roots" to layoutGraph.roots.distinctBy { it.method.descriptor }.size)
    private fun stageText(stages: Map<String, Int>) = "stages=" + stages.entries.joinToString(",") { "${it.key}:${it.value}" }
    private fun <T> uniqueLayout(role: String, values: List<T>, stages: Map<String, Int>): T {
        if (values.size != 1) throw InstallerMatchException(role, values.size, stageText(stages))
        return values.single()
    }

    // Current business role: textual app/channel values and one integer new-install value.
    // These are scenario constraints, not a claim about all future installer protocols.
    private fun requestRoles(types: List<String>) = types.count { it == "java.lang.String" } >= 3 &&
        types.any { it == "java.lang.Integer" || it == "int" }
    private fun requestShape(root: LayoutRoot): Boolean = root.method.parameterTypes.size == root.requestTypes.size + 1 &&
        root.method.parameterTypes.dropLast(1).map(::requestType).sorted() == root.requestTypes.map(::requestType).sorted()
    private fun requestType(type: String) = if (type == "java.lang.Integer") "int" else type
    private fun layoutEndpointRoots(stages: MutableMap<String, Int>): List<LayoutRoot> {
        val roots = layoutGraph.roots.filter { layoutRoutes(it.method).isNotEmpty() }
        val closures = layoutGraph.roots.distinctBy { it.method.descriptor }.map { layoutClosure(it.method) }
        stages["endpoint_roots"] = roots.distinctBy { it.method.descriptor }.size
        stages["visited"] = closures.sumOf { it.visited }
        stages["truncated"] = closures.count { it.truncated }
        stages["depth_stops"] = closures.sumOf { it.depthStops }
        stages["path_methods"] = closures.flatMap { it.methods }.filter { layoutApiRoutes(it).isNotEmpty() }
            .distinctBy { it.descriptor }.size
        stages["ambiguous_refs"] = ambiguousLayoutReferences.size
        return roots
    }

    private fun layoutRoutes(method: InstallerMethod) = executionClosure(method).flatMap(::layoutApiRoutes).toSortedSet()
    private fun layoutApiRoutes(method: InstallerMethod): Set<String> =
        if (method.returnType in PRIMITIVES || method.parameterTypes.none { it in MAP_TYPES }) emptySet()
        else method.annotationStrings.mapNotNull(::normalizeLayoutRoute).toSet()

    /** Constant route equivalence only; similar statistics/reporting paths are not layout APIs. */
    private fun normalizeLayoutRoute(value: String): String? = runCatching {
        val uri = URI(value.trim())
        if (uri.isOpaque || uri.rawQuery != null || uri.rawFragment != null || uri.rawUserInfo != null) return@runCatching null
        if (uri.scheme != null && (uri.scheme.lowercase() !in setOf("http", "https") || uri.host.isNullOrEmpty())) return@runCatching null
        val path = uri.rawPath?.let { if (it.startsWith('/')) it else "/$it" }?.trimEnd('/') ?: return@runCatching null
        path.takeIf { LAYOUT_ROUTE.matches(it) }
    }.getOrNull()

    /** Resolve an inherited reference only when its owner has no same-signature declaration. */
    private fun layoutMethod(reference: String): InstallerMethod? {
        if (layoutReferences.containsKey(reference)) return layoutReferences[reference]
        val direct = index.method(reference)
        val marker = reference.indexOf(";->")
        if (!reference.startsWith('L') || marker < 1 || direct?.let {
                it.descriptor == reference && (it.invokes.isNotEmpty() || it.opcodes.isNotEmpty())
            } == true) {
            layoutReferences[reference] = direct
            return direct
        }
        val owner = reference.substring(1, marker).replace('/', '.')
        val signature = reference.substring(marker + 3)
        val clazz = index.clazz(owner)
        if (clazz == null || clazz.methods.any { it.substringAfter(";->", "") == signature }) {
            layoutReferences[reference] = direct
            return direct
        }
        val queue = ArrayDeque<String>()
        queue.addAll(clazz.interfaces)
        clazz.superName?.takeIf { it != "java.lang.Object" }?.let(queue::addLast)
        val seen = mutableSetOf<String>()
        val declarations = linkedMapOf<String, InstallerMethod>()
        while (queue.isNotEmpty() && seen.size < 32) {
            val type = queue.removeFirst()
            if (!seen.add(type)) continue
            val parent = index.clazz(type) ?: continue
            val own = parent.methods.filter { it.substringAfter(";->", "") == signature }
            if (own.isNotEmpty()) own.mapNotNull(index::method).forEach { declarations[it.descriptor] = it }
            else {
                queue.addAll(parent.interfaces)
                parent.superName?.takeIf { it != "java.lang.Object" }?.let(queue::addLast)
            }
        }
        val result = when {
            declarations.size > 1 || queue.isNotEmpty() -> null
            declarations.size == 1 -> declarations.values.single()
            else -> direct
        }
        if (declarations.size > 1 || queue.isNotEmpty()) ambiguousLayoutReferences += reference
        layoutReferences[reference] = result
        return result
    }

    /** Inverse edges are explicit invokes, plus Runnable.run -> callers of that Runnable's constructor. */
    private fun reverseExecution(start: InstallerMethod): List<InstallerMethod> {
        val found = linkedMapOf<String, InstallerMethod>()
        val queue = ArrayDeque<Pair<InstallerMethod, Int>>()
        queue.addLast(start to 0)
        while (queue.isNotEmpty() && found.size < 128) {
            val (method, depth) = queue.removeFirst()
            if (found.putIfAbsent(method.descriptor, method) != null || depth >= 5) continue
            index.callers(method.descriptor).forEach { queue.addLast(it to depth + 1) }
            if (method.name == "run" && runnable(method.owner)) {
                index.clazz(method.owner)?.methods.orEmpty().mapNotNull(index::method)
                    .filter { it.name == "<init>" }.flatMap { index.callers(it.descriptor) }
                    .forEach { queue.addLast(it to depth + 1) }
            }
        }
        return found.values.toList()
    }

    private fun outermostLayoutRoots(roots: List<LayoutRoot>): List<LayoutRoot> = roots.filter { root ->
        val ancestors = reverseExecution(root.method).drop(1).map { it.descriptor }.toSet()
        roots.none { it.method.descriptor in ancestors }
    }

    private fun layoutPayload(entry: InstallerMethod, callbackType: String? = null): LayoutPayloadEvidence {
        val body = executionClosure(entry).toMutableList()
        if (callbackType != null) {
            // Retrofit completion objects capture the public callback. Inspect only those constructed objects.
            val replies = body.filter { it.name == "<init>" && classFields(it.owner).any { field -> field.type == callbackType } }
            replies.flatMap { index.clazz(it.owner)?.methods.orEmpty() }.mapNotNull(index::method)
                .filter { it.name != "<init>" && it.name != "<clinit>" }
                .forEach { body += executionClosure(it) }
        }
        val methods = body.distinctBy { it.descriptor }
        val fieldReads = linkedMapOf<String, InstallerField>()
        val getters = linkedMapOf<String, InstallerMethod>()
        val nullablePayloads = mutableSetOf<String>()
        for (method in methods) {
            val reads = layoutResponseReads(method)
            reads.forEach { fieldReads[it.descriptor] = it }
            if (reads.any { pureLayoutGetter(method, it) }) getters[method.descriptor] = method
            // A mapper can inline the response field read or delegate to a pure accessor. Its
            // parameter type/name is deliberately irrelevant: the v2 wrapper is not the protocol.
            val accessedTypes = reads.map { it.type }.toMutableSet()
            method.invokes.mapNotNull(::layoutMethod).forEach { called ->
                layoutResponseReads(called).filter { field ->
                    pureLayoutGetter(called, field) ||
                        // Preserve the previously verified typed wrapper-mapper contract even when
                        // the bounded closure observes its accessor only on the direct frontier.
                        method.returnType == field.type && field.owner in method.parameterTypes &&
                        called.owner == field.owner && called.parameterTypes.isEmpty() && called.returnType == field.type
                }.forEach { field ->
                    fieldReads[field.descriptor] = field
                    if (pureLayoutGetter(called, field)) getters[called.descriptor] = called
                    accessedTypes += field.type
                }
            }
            if (method.returnType in accessedTypes && hasEmptyObjectReturn(method)) nullablePayloads += method.returnType
        }
        // Annotation values are flattened by the index. Do not join fragments or pretend to parse a
        // complete Signature annotation. Only complete class tokens on an actual layout API count,
        // and only classes with the same concrete advertisement schema can supply a payload type.
        val apiTypes = methods.filter { layoutApiRoutes(it).isNotEmpty() }.flatMap { api ->
            (layoutAnnotationTypes(api.annotationStrings) + api.returnType).filter(::layoutPayloadSchema)
        }.toSet()
        return LayoutPayloadEvidence(fieldReads.values.map { it.type }.toSet() + apiTypes,
            fieldReads.keys, getters.keys, apiTypes, nullablePayloads)
    }

    private fun layoutPayloadSchema(type: String): Boolean = layoutSchemas.getOrPut(type) {
        if (type in PRIMITIVES || type == "java.lang.Object" || type.endsWith("[]")) false
        else classFields(type).let { payload ->
            payload.any { named(it, "data") && it.type in setOf("java.util.List", "java.util.ArrayList") } &&
                payload.any { named(it, "channel") && it.type == "java.lang.String" } &&
                payload.any { (named(it, "layoutFormat") || named(it, "layoutVersionType")) && it.type == "java.lang.String" }
        }
    }

    private fun layoutResponseReads(method: InstallerMethod): List<InstallerField> = method.readFields.mapNotNull(index::field)
        .filter { !it.isStatic && (named(it, "data") || named(it, "adData")) && layoutPayloadSchema(it.type) }

    private fun pureLayoutGetter(method: InstallerMethod, field: InstallerField): Boolean {
        val opcodes = method.opcodes.filter { it != NOP && it !in OBJECT_MOVES }
        return !method.isStatic && method.owner == field.owner && method.parameterTypes.isEmpty() &&
            method.returnType == field.type && method.readFields == listOf(field.descriptor) &&
            method.writtenFields.isEmpty() && method.invokes.isEmpty() && opcodes == listOf(IGET_OBJECT, RETURN_OBJECT)
    }

    private fun layoutAnnotationTypes(values: Set<String>): Set<String> = values.flatMap { value ->
        LAYOUT_CLASS_TOKEN.findAll(value).map { it.groupValues[1].replace('/', '.') }.toList()
    }.toSet()

    private fun recordLayoutModels(role: String, stages: MutableMap<String, Int>, candidates: List<LayoutModelCandidate>) {
        fun types(candidate: LayoutModelCandidate) = candidate.evidence.payloads
        val payloads = candidates.flatMap(::types).toSortedSet()
        stages["payload_types"] = payloads.size
        stages["field_reads"] = candidates.flatMap { it.evidence.fields }.toSet().size
        stages["getters"] = candidates.flatMap { it.evidence.getters }.toSet().size
        stages["api_types"] = candidates.flatMap { it.evidence.apiTypes }.toSet().size
        stages["empty_model"] = candidates.count { types(it).isEmpty() }
        stages["multi_model"] = candidates.count { types(it).size > 1 }
        stages["type_mismatch"] = candidates.count { candidate -> candidate.callback?.let { callback ->
            val payload = types(candidate).singleOrNull()
            payload != null && (callback.owner == callback.parameterTypes.single() || payload != callback.parameterTypes.single())
        } == true }
        val counts = listOf("payload_types", "field_reads", "getters", "api_types", "empty_model", "multi_model", "type_mismatch")
            .joinToString(",") { "$it:${stages.getValue(it)}" }
        val callbackTypes = candidates.mapNotNull { it.callback?.parameterTypes?.single() }.distinct().sorted()
        val schemas = callbackTypes.joinToString(",") { "${layoutTypeDescriptor(it)}:${layoutPayloadSchema(it)}" }
        val callbackDescriptors = candidates.mapNotNull { it.callback?.descriptor }.distinct().sorted()
        pendingLayoutDiagnostics[role] = listOf(
            "probe ${role}_0 detail=candidates=${candidates.size};$counts;callback_schema=$schemas",
            "probe ${role}_1 detail=payloads=${payloads.joinToString(",", transform = ::layoutTypeDescriptor)};callbacks=${callbackDescriptors.joinToString(",")}",
        ).map { it.take(384) }
    }

    private fun layoutTypeDescriptor(type: String) = "L${type.replace('.', '/')};"

    private fun named(field: InstallerField, name: String) = !field.isStatic && (field.name == name || name in field.annotationStrings)
    private fun hasZero(method: InstallerMethod) = index.findMethods(InstallerMethodQuery(owner = method.owner,
        numbers = setOf(0L))).any { it.descriptor == method.descriptor }
    private fun hasEmptyObjectReturn(method: InstallerMethod): Boolean {
        val opcodes = method.opcodes.filter { it != NOP && it !in OBJECT_MOVES }
        return hasZero(method) && opcodes.zipWithNext().any { (first, second) -> first in NULL_CONSTANTS && second == RETURN_OBJECT }
    }

    private fun layoutContinuation(type: String): Boolean {
        val pending = ArrayDeque<String>()
        val seen = mutableSetOf<String>()
        val methods = mutableListOf<InstallerMethod>()
        pending.addLast(type)
        while (pending.isNotEmpty() && seen.size < 16) {
            val current = pending.removeFirst()
            if (!seen.add(current)) continue
            if (current == "kotlin.coroutines.Continuation") return true
            methods += index.findMethods(InstallerMethodQuery(owner = current, isStatic = false))
                .filter { it.name != "<init>" && it.name != "<clinit>" }
            index.clazz(current)?.let { clazz ->
                pending.addAll(clazz.interfaces)
                clazz.superName?.takeIf { it != "java.lang.Object" }?.let(pending::addLast)
            }
        }
        return methods.any { it.returnType == "void" && it.parameterTypes == listOf("java.lang.Object") } &&
            methods.any { it.parameterTypes.isEmpty() && it.returnType !in PRIMITIVES && it.returnType != "java.lang.Object" }
    }

    private fun hasNullCallbackFailure(callback: InstallerMethod): Boolean = index.findMethods(InstallerMethodQuery(
        numbers = setOf(0L), returnType = "void", parameterTypes = listOf(callback.owner), isStatic = true,
    )).any { helper ->
        val opcodes = helper.opcodes.filter { it != NOP && it !in OBJECT_MOVES }
        // The DTO has no register operands. Keep a narrow zero-only helper: no field access,
        // allocation, cast, other invoke or payload producer; tolerate only control/move encoding changes.
        helper.readFields.isEmpty() && helper.writtenFields.isEmpty() && helper.invokes == listOf(callback.descriptor) &&
            opcodes.size in 3..7 && opcodes.all { it in EMPTY_CALLBACK_OPS } &&
            opcodes.count { it in NULL_CONSTANTS } == 1 && opcodes.count { it in CALLBACK_INVOKES } == 1 &&
            opcodes.count { it == RETURN_VOID } in 1..2 && opcodes.count { it == IF_EQZ || it == IF_NEZ } <= 1 &&
            opcodes.last() == RETURN_VOID
    }

    private fun intercept(): InstallerResolvedPoint {
        val method = uniqueInstallerMatch("intercept_check", index.findMethods(InstallerMethodQuery(
            strings = setOf("device_type", "packageName", "installationMode", "apk_bit", "apkSignature",
                "androidSdkVersion", "sourceAppName"), returnType = "java.lang.Object", parameterCount = 6,
            isStatic = false,
        )).filter {
            it.parameterTypes[0] == "android.content.Context" && it.parameterTypes[2] == "int" &&
                it.parameterTypes[4] == "java.util.HashMap" && continuation(it.parameterTypes[5]) &&
                it.invokes.mapNotNull(index::method).map { api -> api.annotationStrings.map(String::trim).toSet() }
                    .let { paths -> paths.any { "/v4/game/interceptcheck/normal" in it } &&
                        paths.any { "/v4/game/interceptcheck/safemode" in it } &&
                        paths.any { "/v4/game/interceptcheck/enhancemode" in it } }
        })
        val constructors = method.invokes.mapNotNull(index::method).filter { constructor ->
            constructor.name == "<init>" && constructor.parameterTypes.size == 1 &&
                cloudPayload(constructor.parameterTypes.single()) &&
                index.clazz(constructor.owner)?.superName?.let { it != "java.lang.Object" } == true &&
                classFields(constructor.owner).any { it.type == constructor.parameterTypes.single() && !it.isStatic }
        }.distinctBy { it.descriptor }
        val success = uniqueInstallerMatch("intercept_success_constructor", constructors)
        val payload = success.parameterTypes.single()
        requireInstallerMatch("intercept_payload_constructor", index.findMethods(InstallerMethodQuery(
            owner = payload, returnType = "void", parameterTypes = emptyList(), isStatic = false,
        )).count { it.name == "<init>" } == 1, "empty CloudParams role requires one no-arg constructor")
        val resultBase = index.clazz(success.owner)?.superName
        requireInstallerMatch("intercept_success_base", resultBase != null && index.clazz(resultBase) != null,
            "typed success result must extend a target-loader result base")
        return InstallerResolvedPoint("intercept_check", method, payloadClass = payload,
            resultClass = success.owner, resultBaseClass = resultBase, evidence = listOf(
                "seven installation/device payload keys + three exact intercept API route annotations",
                "typed one-payload success constructor + cloud fields + no-arg payload constructor",
            ))
    }

    private fun cloudPayload(type: String): Boolean {
        val fields = classFields(type)
        fun has(name: String, expectedType: String? = null) = fields.any {
            (it.name == name || name in it.annotationStrings) && (expectedType == null || it.type == expectedType)
        }
        return has("showAdsBefore", "java.lang.Boolean") && has("showAdsAfter", "java.lang.Boolean") &&
            has("showSafeModeTip", "java.lang.Boolean") && has("uiConfig") && has("rc") && has("verifyAccount", "java.lang.Integer")
    }

    private fun cloudV2(): InstallerResolvedPoint {
        // Use the actual void producer, before data gathering, rather than a name-dependent scheduler wrapper.
        val method = uniqueInstallerMatch("cloud_v2", index.findMethods(InstallerMethodQuery(
            strings = setOf("cloudConfig", "last_sync_time", "firstboot_time", "source_auth_reset_v",
                "skip sync cloud config"), returnType = "void", parameterTypes = emptyList(), isStatic = true,
        )).filter { directEndpoint(it, "/v2/cloud_config/query") &&
            "Landroid/content/SharedPreferences;->getLong(Ljava/lang/String;J)J" in it.invokes
        })
        return InstallerResolvedPoint("cloud_v2", method, evidence = listOf(
            "v2 endpoint annotation + cloudConfig/last_sync_time + firstboot/source-auth request fields",
            "actual zero-arg static void producer; SharedPreferences freshness reader",
        ))
    }

    private fun cloudV3(): InstallerResolvedPoint {
        val method = uniqueInstallerMatch("cloud_v3", index.findMethods(InstallerMethodQuery(
            strings = setOf("BlockApkListManager", "V3 config sync failed", "http.agent"),
            returnType = "java.lang.Object", parameterCount = 2, isStatic = true,
        )).filter { it.parameterTypes[0] == "java.lang.Runnable" && continuation(it.parameterTypes[1]) &&
            directEndpoint(it, "/v3/cloud_config/query") && "Ljava/lang/Runnable;->run()V" in it.invokes
        })
        val singleton = uniqueInstallerMatch("cloud_v3_unit", method.readFields.mapNotNull(index::field).filter { field ->
            field.isStatic && field.owner == field.type && index.findMethods(InstallerMethodQuery(
                owner = field.type, strings = setOf("kotlin.Unit"), returnType = "java.lang.String",
                parameterTypes = emptyList(), isStatic = false,
            )).size == 1
        }.distinctBy { it.descriptor })
        requireInstallerMatch("cloud_v3_return", method.opcodes.lastOrNull() == RETURN_OBJECT,
            "suspend producer requires an object completion")
        return InstallerResolvedPoint("cloud_v3", method, singletonField = singleton,
            evidence = listOf("v3 endpoint + failure diagnostic + Runnable success callback",
                "target-loader Unit singleton read with literal kotlin.Unit identity"))
    }

    private fun upload(): InstallerResolvedPoint {
        val method = uniqueInstallerMatch("avl_upload", index.findMethods(InstallerMethodQuery(
            strings = setOf("appSourcepackageName", "appSourceValue", "packageName", "touchInstallCnt",
                "installCnt", "installTime", "fingerprint", "apkHash", "apkSize", "permission", "cdMd5"),
            returnType = "void", parameterCount = 4, isStatic = true,
        )).filter { candidate ->
            candidate.parameterTypes[2] == "boolean" && candidate.parameterTypes.all { it != "void" } &&
                candidate.invokes.mapNotNull(index::method).any { call ->
                    call.returnType == "java.lang.String" && call.parameterTypes.size == 2 &&
                        call.parameterTypes[0] in MAP_TYPES + "java.lang.Object" &&
                        call.parameterTypes[1] == "java.lang.String" &&
                        call.strings.containsAll(setOf("NetUtil", "request start : ")) &&
                        "Ljava/net/URL;-><init>(Ljava/lang/String;)V" in call.invokes &&
                        "Ljava/net/HttpURLConnection;->getResponseCode()I" in call.invokes
                } && candidate.readFields.mapNotNull(index::field).any { field ->
                    field.type == "java.lang.String" && field.isStatic && index.findMethods(InstallerMethodQuery(
                        owner = field.owner, strings = setOf("/avl/upload"), returnType = "void",
                        parameterTypes = emptyList(), isStatic = true,
                    )).any { it.name == "<clinit>" && field.descriptor in it.writtenFields }
                }
        })
        return InstallerResolvedPoint("avl_upload", method, evidence = listOf(
            "eleven exact install-upload payload keys + static four-argument void producer + boolean role",
            "payload/String HTTP helper (generic body may erase to Object) + real URL/HTTP calls",
            "read URL-holder field; its class initializer contains /avl/upload",
            "URL association is class-level, not a claimed field-to-literal dataflow proof",
        ))
    }

    private fun directEndpoint(method: InstallerMethod, path: String): Boolean = method.invokes.mapNotNull(index::method)
        .any { path in it.annotationStrings.map(String::trim) }

    private fun classFields(owner: String): List<InstallerField> = index.clazz(owner)?.fields.orEmpty().mapNotNull(index::field)

    private fun continuation(type: String): Boolean {
        val methods = index.findMethods(InstallerMethodQuery(owner = type, isStatic = false)).filter { it.name != "<init>" }
        return methods.any { it.returnType == "void" && it.parameterTypes == listOf("java.lang.Object") } &&
            methods.any { it.parameterTypes.isEmpty() && it.returnType !in PRIMITIVES && it.returnType != "java.lang.Object" }
    }

    private fun runnable(type: String): Boolean = index.clazz(type)?.interfaces?.contains("java.lang.Runnable") == true

    private fun executionClosure(start: InstallerMethod): List<InstallerMethod> = layoutClosure(start).methods

    /** Observe every direct frontier before spending the expansion budget. Bodyless leaves cannot crowd out an API. */
    private fun layoutClosure(start: InstallerMethod): LayoutClosure = layoutClosures.getOrPut(start.descriptor) {
        val queue = ArrayDeque<Pair<InstallerMethod, Int>>()
        val found = linkedMapOf<String, InstallerMethod>()
        val expanded = mutableSetOf<String>()
        var edges = 0
        var depthStops = 0
        queue.addLast(start to 0)
        while (queue.isNotEmpty() && expanded.size < 192 && edges < 8_192) {
            val (method, depth) = queue.removeFirst()
            if (!expanded.add(method.descriptor)) continue
            found.putIfAbsent(method.descriptor, method)
            for (reference in method.invokes) {
                if (edges++ >= 8_192) break
                val callee = layoutMethod(reference) ?: continue
                found.putIfAbsent(callee.descriptor, callee)
                if (callee.invokes.isNotEmpty()) {
                    if (depth < 6) queue.addLast(callee to depth + 1) else depthStops++
                }
                if (callee.name == "<init>" && runnable(callee.owner)) {
                    index.findMethods(InstallerMethodQuery(owner = callee.owner, returnType = "void",
                        parameterTypes = emptyList(), isStatic = false)).filter { it.name == "run" }
                        .forEach {
                            found.putIfAbsent(it.descriptor, it)
                            if (depth < 6) queue.addLast(it to depth + 1) else depthStops++
                        }
                }
            }
        }
        LayoutClosure(found.values.toList(), expanded.size, queue.isNotEmpty() || edges >= 8_192 || depthStops > 0,
            edges, depthStops)
    }

    companion object {
        private val MAP_TYPES = setOf("java.util.Map", "java.util.HashMap")
        private val LAYOUT_ROUTE = Regex("/info/layout(?:/v[1-9][0-9]*)?")
        private val PRIMITIVES = setOf("void", "boolean", "byte", "short", "char", "int", "long", "float", "double")
        private const val NOP = 0x00
        private const val RETURN_VOID = 0x0e
        private const val RETURN_OBJECT = 0x11
        private const val CONST_4 = 0x12
        private const val IGET_OBJECT = 0x54
        private const val IF_EQZ = 0x38
        private const val IF_NEZ = 0x39
        private const val INVOKE_INTERFACE = 0x72
        private const val INVOKE_INTERFACE_RANGE = 0x78
        private val OBJECT_MOVES = setOf(0x07, 0x08, 0x09)
        private val NULL_CONSTANTS = setOf(CONST_4, 0x13, 0x14)
        private val CALLBACK_INVOKES = setOf(INVOKE_INTERFACE, INVOKE_INTERFACE_RANGE, 0x6e, 0x74)
        private val EMPTY_CALLBACK_OPS = NULL_CONSTANTS + CALLBACK_INVOKES + setOf(RETURN_VOID, IF_EQZ, IF_NEZ, 0x28, 0x29, 0x2a)
        private val MAP_PUTS = setOf("Ljava/util/Map;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
            "Ljava/util/HashMap;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;")
        // A complete class token ends in ';' or opens a generic '<'. Arrays are not direct payloads.
        private val LAYOUT_CLASS_TOKEN = Regex("(?<![A-Za-z0-9_$/\\[])L((?:[A-Za-z_$][A-Za-z0-9_$]*/)*[A-Za-z_$][A-Za-z0-9_$]*)(?=[;<])")
    }
}
