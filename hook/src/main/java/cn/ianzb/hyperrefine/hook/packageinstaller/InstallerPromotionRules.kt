package cn.ianzb.hyperrefine.hook.packageinstaller

/** Display-only roles, with bounded static failure facts. No request/upload completion is inferred. */
internal class InstallerPromotionRules(private val index: InstallerDexIndex) {
    private val failureDiagnostics = linkedMapOf<String, String>()
    /** Static metadata only: one fields line per failed role, plus at most one card-schema line. */
    fun diagnosticLines(): List<String> = failureDiagnostics.values.toList()

    fun resolve(id: String): InstallerResolvedPoint = when (id) {
        "ads_before" -> before()
        "ads_after" -> after()
        "ads_cancel" -> cancel()
        else -> throw InstallerMatchException(id, 0, "unknown promotion display role")
    }

    private inner class Trace(private val role: String) {
        private val counts = linkedMapOf<String, Int>()
        private val missing = mutableMapOf<String, Int>()
        private val near = sortedMapOf<String, String>()
        private val unresolvedFields = sortedSetOf<String>()
        private var failedCard: String? = null
        fun count(stage: String, value: Int) { counts[stage] = value }
        fun miss(reason: String) { missing[reason] = (missing[reason] ?: 0) + 1 }
        fun fieldMissing(reference: String) {
            miss("field_ref")
            unresolvedFields += reference
            if (unresolvedFields.size > 2) unresolvedFields.remove(unresolvedFields.last())
        }
        fun card(constructor: InstallerMethod) {
            if (failedCard != null) return
            failedCard = runCatching {
                fun fields(type: String) = index.clazz(type)?.fields.orEmpty().distinct().mapNotNull(index::field).filterNot { it.isStatic }
                val types = fields(constructor.owner).filter { it.type in constructor.parameterTypes && it.type !in PRIMITIVES &&
                    !it.type.startsWith("java.") && !it.type.startsWith("android.") }.map { it.type }.distinct().take(4)
                val candidates = types.flatMap { type -> listOf(type) + fields(type).filter { role(it, "data") }.take(1).map { it.type } }.distinct()
                val keys = listOf("packageName", "iconUrl", "landingPageUrl", "allDownloadNum", "viewMonitorUrls", "clickMonitorUrls", "traceKv", "appId", "apkSizeV2", "data")
                val selected = candidates.maxByOrNull { type -> fields(type).count { field -> keys.any { role(field, it) } } }
                val members = selected?.let(::fields).orEmpty()
                val labels = listOf("pkg", "icon", "url", "dl", "view", "click", "trace", "app", "size", "data")
                val roles = keys.mapIndexed { ordinal, key ->
                    val matches = members.filter { role(it, key) }
                    val shapes = matches.map { when (it.type) { "java.lang.String" -> "S"; "java.lang.String[]" -> "[S"; "long" -> "J"; "int" -> "I"; "boolean" -> "Z"; else -> "O" } }.distinct().joinToString("/")
                    "${labels[ordinal]}=${matches.size}:${shapes.ifEmpty { "-" }}"
                }.joinToString(",")
                "card=L${constructor.owner.replace('.', '/')};payload=${selected?.let { "L${it.replace('.', '/')};" } ?: "none"};roles=$roles"
            }.getOrElse { "unavailable=${it.javaClass.simpleName}" }
        }
        fun near(method: InstallerMethod) {
            near[method.descriptor] = "${method.owner.substringAfterLast('.')}#${method.name}".take(32)
            if (near.size > 2) near.remove(near.lastKey())
        }
        fun <T> stage(name: String, source: List<T>, test: (T) -> Boolean): List<T> =
            source.filter(test).also { count(name, it.size) }
        fun <T> unique(candidates: List<T>): T {
            if (candidates.size == 1) return candidates.single()
            val stages = counts.entries.joinToString(",") { "${it.key}=${it.value}" }
            val reasons = missing.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .take(3).joinToString(",") { "${it.key}:${it.value}" }
            val refs = unresolvedFields.joinToString("|") { it.take(112) }
            val detail = "$stages miss=${reasons.ifEmpty { "none" }} near=${near.values.joinToString("|").ifEmpty { "none" }}"
            failureDiagnostics[role] = "probe ${role.substringBefore('.')}_fields detail=refs=${refs.ifEmpty { "none" }};stages=$stages".take(384)
            failedCard?.let { failureDiagnostics["$role.models"] = "probe ${role.substringBefore('.')}_models detail=$it".take(384) }
            throw InstallerMatchException(role, candidates.size, detail)
        }
    }

    private fun before(): InstallerResolvedPoint {
        val trace = Trace("ads_before.display_gate")
        val query = index.findMethods(InstallerMethodQuery(returnType = "boolean", parameterCount = 1, isStatic = false))
        trace.count("q", query.size)
        val cloud = trace.stage("cloud", query) { gate ->
            cloudShape(gate.parameterTypes.single(), trace).also { if (it) trace.near(gate) }
        }
        val read = trace.stage("read", cloud) { gate ->
            val fields = readFields(gate, trace)?.plus(cloudGetterReads(gate, trace))
                ?.distinctBy { it.descriptor } ?: return@stage false
            fields.count { it.owner == gate.parameterTypes.single() && semantic(it, "showAdsBefore", BOOLEAN) } == 1 &&
                fields.all { it.owner == gate.parameterTypes.single() && semantic(it, "showAdsBefore", BOOLEAN) || it.descriptor == BOOLEAN_TRUE }
        }
        val operation = trace.stage("op", read) { gate ->
            gate.writtenFields.isEmpty() && gate.strings.isEmpty() && beforeOperation(gate, trace)
        }
        val consumers = trace.stage("consumer", operation) { gate ->
            val callers = index.callers(gate.descriptor).distinctBy { it.descriptor }
            callers.isNotEmpty() && callers.all { call ->
                call.owner == gate.owner && !call.isStatic && listType(call.returnType) && call.parameterTypes.size == 2 &&
                    call.parameterTypes.first() == gate.parameterTypes.single() && call.writtenFields.isEmpty()
            }
        }
        val models = trace.stage("model", consumers) { gate ->
            index.callers(gate.descriptor).distinctBy { it.descriptor }.all { hasPromotionModels(it, trace) }
        }
        return InstallerResolvedPoint("ads_before", trace.unique(models), evidence = listOf(
            "showAdsBefore field/serialized alias and pure Boolean operation",
            "all consumers build advertisement models; display only, not request/upload blocking",
        ))
    }

    private fun beforeOperation(gate: InstallerMethod, trace: Trace): Boolean {
        val code = gate.opcodes.filter { it != 0x00 }
        if (code.lastOrNull() != RETURN || code.any { it !in BEFORE_OPCODES }) return false
        val calls = gate.invokes.distinct()
        if (calls.isEmpty()) return BOOLEAN_TRUE in gate.readFields && code.any { it == 0x32 || it == 0x33 }
        return calls.all { reference ->
            when (reference) {
                BOOLEAN_VALUE -> true
                BOOLEAN_EQUALS, OBJECT_EQUALS -> BOOLEAN_TRUE in gate.readFields
                else -> index.method(reference)?.let { called ->
                    booleanEquality(called) || called.owner == gate.parameterTypes.single() &&
                        pureGetter(called, BOOLEAN, trace)?.let { semantic(it, "showAdsBefore", BOOLEAN) } == true
                } ?: run { trace.miss("method_ref"); false }
            }
        }
    }

    private fun after(): InstallerResolvedPoint {
        val trace = Trace("ads_after.display_gate")
        val query = index.findMethods(InstallerMethodQuery(returnType = "boolean", parameterTypes = emptyList(), isStatic = false))
        trace.count("q", query.size)
        val fields = trace.stage("field", query) { gate ->
            // The gate also reads Activity, policy and framework state. Its complete consumer
            // chain below proves that its result only controls merging advertisement models.
            // Missing unrelated reference metadata must not erase verified cloud-role reads.
            val reads = (readFields(gate, trace, requireComplete = false).orEmpty() + cloudGetterReads(gate, trace))
                .distinctBy { it.descriptor }
            val cloud = reads.filter { semantic(it, "showAdsAfter", BOOLEAN) }.map { it.owner }.distinct().singleOrNull()
            if (cloud != null) trace.near(gate)
            cloud != null && cloudShape(cloud, trace) && reads.any { it.owner == cloud && semantic(it, "useSystemAppRules", BOOLEAN) }
        }
        val activities = trace.stage("activity", fields) { gate ->
            gate.writtenFields.isEmpty() && gate.opcodes.lastOrNull() == RETURN && subclass(gate.owner, "android.app.Activity", trace)
        }
        val merge = trace.stage("merge", activities) { gate ->
            val callers = index.callers(gate.descriptor).distinctBy { it.descriptor }
            callers.isNotEmpty() && callers.all { method ->
                method.owner == gate.owner && !method.isStatic && method.returnType == "void" &&
                    method.parameterTypes.size == 2 && method.parameterTypes.all(::listType) && method.writtenFields.isEmpty() &&
                    method.invokes.any { listCall(it, "addAll", "(Ljava/util/Collection;)Z") } &&
                    method.invokes.any { listCall(it, "isEmpty", "()Z") }
            }
        }
        val producers = trace.stage("producer", merge) { gate ->
            index.callers(gate.descriptor).distinctBy { it.descriptor }.all { merger ->
                val callers = index.callers(merger.descriptor).distinctBy { it.descriptor }
                callers.isNotEmpty() && callers.all { producer ->
                    if (producer.owner != gate.owner || !listType(producer.returnType) || producer.parameterTypes.isNotEmpty() || producer.isStatic) {
                        trace.miss("producer_shape")
                        false
                    } else {
                        val suppliers = producer.invokes.distinct().mapNotNull { reference ->
                            index.method(reference) ?: run { trace.miss("method_ref"); null }
                        }.filter { listType(it.returnType) && it.parameterTypes.isEmpty() && subclass(gate.owner, it.owner, trace) }
                        if (suppliers.isEmpty()) { trace.miss("supplier_shape"); false }
                        else suppliers.any { hasPromotionModels(it, trace) }.also { if (!it) trace.miss("supplier_card") }
                    }
                }
            }
        }
        return InstallerResolvedPoint("ads_after", trace.unique(producers), evidence = listOf(
            "showAdsAfter/useSystemAppRules local Activity gate",
            "all consumers merge verified advertisement-model lists; display only, not request/upload blocking",
        ))
    }

    private data class CancelSeed(val bind: InstallerMethod, val holders: List<InstallerField>)
    private data class CancelPoint(val bind: InstallerMethod, val holder: InstallerField, val container: InstallerMethod)

    private fun cancel(): InstallerResolvedPoint {
        val trace = Trace("ads_cancel.ad_grid_bind")
        // Discover from a declared, pure getter for the exact advertisement container. Analytics
        // calls/strings may move into lambdas or helpers and are not the component's identity.
        val getters = index.findMethods(InstallerMethodQuery(returnType = FRAME,
            parameterTypes = emptyList(), isStatic = false)).distinctBy { it.descriptor }
        trace.count("frame_getters", getters.size)
        val adGetters = trace.stage("pure_frame", getters) { getter -> pureGetter(getter, FRAME, trace) != null }
        val query = adGetters.flatMap { index.callers(it.descriptor) }.distinctBy { it.descriptor }.sortedBy { it.descriptor }
        trace.count("callers", query.size)
        query.take(2).forEach(trace::near)
        val frames = trace.stage("frame", query) { bind -> !bind.isStatic && bind.returnType == "void" &&
            bind.parameterTypes.isEmpty() && bind.writtenFields.isEmpty() && bind.invokes.any { it in FRAME_VISIBILITY } }
        val holders = frames.mapNotNull { bind ->
            val fields = readFields(bind, trace)?.filter { it.owner == bind.owner && !it.isStatic } ?: return@mapNotNull null
            if (fields.count { it.type == "boolean" } != 1) { trace.miss("bool_role"); return@mapNotNull null }
            val possible = fields.filter { holder ->
                if (holder.type in PRIMITIVES) false
                else if (index.clazz(holder.type) == null) { trace.miss("holder_class"); false }
                else index.findMethods(InstallerMethodQuery(owner = holder.type, returnType = FRAME,
                    parameterTypes = emptyList(), isStatic = false)).any { it.descriptor in bind.invokes }
            }
            if (possible.isEmpty()) { trace.miss("getter_absent"); null } else CancelSeed(bind, possible)
        }
        trace.count("holder", holders.size)
        val selectedGetters = holders.mapNotNull { seed ->
            val points = seed.holders.flatMap { holder ->
                index.findMethods(InstallerMethodQuery(owner = holder.type, returnType = FRAME,
                    parameterTypes = emptyList(), isStatic = false)).filter { getter ->
                    getter.descriptor in seed.bind.invokes && pureGetter(getter, FRAME, trace)?.let { field ->
                        getterRole(getter, field, "flAdCard") || readFields(seed.bind, trace).orEmpty().any {
                            it.owner == seed.bind.owner && adCollection(it.type, trace)
                        }
                    } == true
                }.map { CancelPoint(seed.bind, holder, it) }
            }.distinctBy { it.holder.descriptor to it.container.descriptor }
            if (points.size != 1) { trace.miss("getter_role"); null } else points.single()
        }
        trace.count("getter", selectedGetters.size)
        val binders = trace.stage("binder", selectedGetters) { selected ->
            val callers = index.callers(selected.bind.descriptor).distinctBy { it.descriptor }
            if (callers.size != 1) return@stage false
            val binder = callers.single()
            if (binder.owner != selected.bind.owner || binder.isStatic || binder.returnType != "void" ||
                binder.parameterTypes != listOf(selected.holder.type) || binder.writtenFields.distinct() != listOf(selected.holder.descriptor) ||
                "Landroid/widget/TextView;->setText(Ljava/lang/CharSequence;)V" !in binder.invokes) return@stage false
            val titles = binder.invokes.distinct().mapNotNull { reference ->
                val method = index.method(reference)
                if (method == null) { trace.miss("method_ref"); null }
                else if (method.owner == selected.holder.type) pureGetter(method, "android.widget.TextView", trace)?.let { method to it } else null
            }.distinctBy { it.second.descriptor }
            val namedTitles = listOf("tvCancelTitle", "tvCancelSubtitle").all { key -> titles.count { getterRole(it.first, it.second, key) } == 1 }
            namedTitles || titles.size == 2 && readFields(selected.bind, trace).orEmpty().any {
                it.owner == selected.bind.owner && adCollection(it.type, trace)
            }
        }
        val selected = trace.unique(binders)
        return InstallerResolvedPoint("ads_cancel", selected.bind, holderField = selected.holder, containerGetter = selected.container,
            evidence = listOf("pure unique FrameLayout getter + local holder and independent title/subtitle binder",
                "requires runtime resource identity com.miui.packageinstaller:id/fl_ad_card before after-bind hiding; display only"))
    }

    private fun pureGetter(method: InstallerMethod, result: String, trace: Trace): InstallerField? {
        val code = method.opcodes.filter { it != 0x00 }
        if (method.isStatic || method.returnType != result || method.parameterTypes.isNotEmpty() || method.invokes.isNotEmpty() ||
            method.writtenFields.isNotEmpty() || method.strings.isNotEmpty() || code.lastOrNull() != 0x11 ||
            code.count { it == 0x54 } != 1 || code.count { it == 0x11 } != 1 || code.any { it !in GETTER_OPCODES }) return null
        val descriptor = method.readFields.distinct().singleOrNull() ?: return null
        val field = index.field(descriptor) ?: run { trace.fieldMissing(descriptor); return null }
        return field.takeIf { !it.isStatic && it.owner == method.owner && it.type == result }
    }

    /** Explicit same-owner list-factory calls only; unknown constructors and consumers remain rejected. */
    private fun hasPromotionModels(start: InstallerMethod, trace: Trace): Boolean {
        val queue = ArrayDeque<Pair<InstallerMethod, Int>>()
        val seen = mutableSetOf<String>()
        var found = false
        queue.addLast(start to 0)
        while (queue.isNotEmpty() && seen.size < 24) {
            val (method, depth) = queue.removeFirst()
            if (!seen.add(method.descriptor)) continue
            if (method.writtenFields.isNotEmpty()) return false
            for (reference in method.invokes.distinct()) {
                val called = index.method(reference)
                if (called == null) {
                    trace.miss("method_ref")
                    if (";-><init>(" in reference && !reference.startsWith("Ljava/")) return false
                    continue
                }
                if (called.name == "<init>" && !called.owner.startsWith("java.")) {
                    if (!promotionModel(called, trace)) return false
                    found = true
                }
                if (depth < 2 && called.owner == start.owner && listType(called.returnType)) queue.addLast(called to depth + 1)
            }
        }
        return queue.isEmpty() && found
    }

    private fun promotionModel(constructor: InstallerMethod, trace: Trace): Boolean {
        fun reject(reason: String): Boolean { trace.miss(reason); trace.card(constructor); return false }
        val clazz = index.clazz(constructor.owner) ?: return reject("model_class")
        if (clazz.superName == null || clazz.superName == "java.lang.Object") return reject("card_base")
        val payloads = declaredFields(clazz.name, trace).filter { advertisementPayload(it.type, trace) }
        if (payloads.isEmpty() || payloads.map { it.type }.distinct().size != 1) return reject("typed_card")
        val methods = clazz.methods.distinct().mapNotNull(index::method)
        val used = payloads.filter { field -> methods.any { field.descriptor in it.readFields } }
        if (used.isEmpty()) return reject("payload_use")
        val seen = mutableSetOf<String>()
        var current = constructor
        repeat(4) {
            if (!seen.add(current.descriptor) || current.isStatic || current.returnType != "void" ||
                current.parameterTypes.firstOrNull() != "android.content.Context" ||
                used.none { it.type in current.parameterTypes }) return reject("constructor_role")
            if (used.any { it.descriptor in current.writtenFields }) return true
            val delegated = current.invokes.distinct().mapNotNull(index::method).filter {
                it.owner == constructor.owner && it.name == "<init>"
            }.singleOrNull() ?: return reject("constructor_store")
            current = delegated
        }
        return reject("constructor_depth")
    }

    private fun declaredFields(type: String, trace: Trace): List<InstallerField> = index.clazz(type)?.fields.orEmpty()
        .distinct().mapNotNull { reference -> index.field(reference) ?: run { trace.fieldMissing(reference); null } }.filterNot { it.isStatic }

    private fun advertisementPayload(type: String, trace: Trace, depth: Int = 0): Boolean {
        if (type in PRIMITIVES || type.startsWith("java.") || type.startsWith("android.") || depth > 1) return false
        val fields = declaredFields(type, trace)
        fun has(name: String, shape: String) = fields.count { semantic(it, name, shape) } == 1
        val common = listOf("packageName", "iconUrl", "landingPageUrl").all { has(it, "java.lang.String") } && has("allDownloadNum", "long")
        val monitored = common && has("viewMonitorUrls", "java.lang.String[]") && has("clickMonitorUrls", "java.lang.String[]")
        val game = common && has("traceKv", "java.lang.String") && has("appId", "long") && has("apkSizeV2", "long")
        if (monitored || game) return true
        if (depth == 0) {
            val data = fields.filter { role(it, "data") }.singleOrNull()
            if (data != null && advertisementPayload(data.type, trace, 1)) return true
        }
        return false
    }

    private fun adCollection(type: String, trace: Trace): Boolean {
        val fields = declaredFields(type, trace)
        val data = fields.filter { listType(it.type) && (role(it, "data") || role(it, "dataList")) }
        val formats = listOf("layoutFormat", "layoutVersionType").map { key -> fields.count { semantic(it, key, "java.lang.String") } }
        return data.size == 1 && fields.count { semantic(it, "channel", "java.lang.String") } == 1 &&
            formats.any { it == 1 } && formats.all { it <= 1 }
    }

    private fun getterRole(method: InstallerMethod, field: InstallerField, key: String): Boolean = role(field, key) ||
        normalizeRole(method.name) == normalizeRole("get$key")

    private fun cloudShape(type: String, trace: Trace): Boolean {
        val clazz = index.clazz(type) ?: run { trace.miss("cloud_class"); return false }
        val fields = clazz.fields.distinct().mapNotNull { descriptor -> index.field(descriptor) ?: run { trace.fieldMissing(descriptor); null } }
        if (fields.size != clazz.fields.distinct().size) return false
        val matches = listOf("showAdsBefore", "showAdsAfter", "showSafeModeTip").all { key -> fields.count { semantic(it, key, BOOLEAN) } == 1 }
        if (!matches) trace.miss("cloud_role")
        return matches
    }

    private fun readFields(method: InstallerMethod, trace: Trace, requireComplete: Boolean = true): List<InstallerField>? {
        // This exact comparison constant is part of the framework, not an APK declaration.
        // Its identity is checked from the DEX reference; no FieldData placeholder is required.
        val descriptors = method.readFields.distinct().filterNot { it == BOOLEAN_TRUE }
        val fields = descriptors.mapNotNull { reference -> index.field(reference) ?: run { trace.fieldMissing(reference); null } }
        return fields.takeIf { !requireComplete || it.size == descriptors.size }
    }

    /** Direct pure property accessors are equivalent to iget; no arbitrary call graph is followed. */
    private fun cloudGetterReads(method: InstallerMethod, trace: Trace): List<InstallerField> =
        method.invokes.distinct().mapNotNull { reference ->
            val called = index.method(reference) ?: return@mapNotNull null
            pureGetter(called, BOOLEAN, trace)?.takeIf { field ->
                listOf("showAdsBefore", "showAdsAfter", "useSystemAppRules").any { semantic(field, it, BOOLEAN) }
            }
        }.distinctBy { it.descriptor }

    private fun semantic(field: InstallerField, key: String, type: String) = !field.isStatic && field.type == type && role(field, key)
    private fun role(field: InstallerField, key: String): Boolean =
        sequenceOf(field.name).plus(field.annotationStrings.asSequence()).any { normalizeRole(it) == normalizeRole(key) }
    private fun normalizeRole(value: String) = value.replace("_", "").replace("-", "").lowercase()
    private fun listType(type: String) = type in LIST_TYPES
    private fun listCall(reference: String, name: String, suffix: String): Boolean =
        (LIST_TYPES + "java.util.Collection").any { reference == "L${it.replace('.', '/')};->$name$suffix" }

    private fun booleanEquality(method: InstallerMethod): Boolean = method.isStatic && method.returnType == "boolean" &&
        method.parameterTypes == listOf("java.lang.Object", "java.lang.Object") && method.writtenFields.isEmpty() &&
        method.readFields.isEmpty() && method.strings.isEmpty() && method.invokes.distinct() == listOf(OBJECT_EQUALS) &&
        method.opcodes.lastOrNull() == RETURN

    private fun subclass(type: String, parent: String, trace: Trace): Boolean {
        val seen = mutableSetOf<String>()
        var current: String? = type
        while (current != null && seen.add(current)) {
            if (current == parent) return true
            val clazz = index.clazz(current) ?: run { trace.miss("super_class"); return false }
            current = clazz.superName
        }
        return false
    }

    private companion object {
        const val BOOLEAN = "java.lang.Boolean"
        const val FRAME = "android.widget.FrameLayout"
        const val RETURN = 0x0f
        const val BOOLEAN_TRUE = "Ljava/lang/Boolean;->TRUE:Ljava/lang/Boolean;"
        const val BOOLEAN_VALUE = "Ljava/lang/Boolean;->booleanValue()Z"
        const val BOOLEAN_EQUALS = "Ljava/lang/Boolean;->equals(Ljava/lang/Object;)Z"
        const val OBJECT_EQUALS = "Ljava/lang/Object;->equals(Ljava/lang/Object;)Z"
        val LIST_TYPES = setOf("java.util.List", "java.util.ArrayList", "java.util.LinkedList", "java.util.AbstractList", "java.util.AbstractSequentialList")
        val FRAME_VISIBILITY = setOf("android.view.View", "android.view.ViewGroup", "android.widget.FrameLayout", "android.widget.LinearLayout", "android.widget.RelativeLayout")
            .map { "L${it.replace('.', '/')};->setVisibility(I)V" }.toSet()
        val GETTER_OPCODES = setOf(0x07, 0x08, 0x09, 0x54, 0x11)
        val BEFORE_OPCODES = setOf(0x00, 0x01, 0x02, 0x03, 0x07, 0x08, 0x09, 0x0a, 0x0f,
            0x12, 0x28, 0x29, 0x2a, 0x32, 0x33, 0x38, 0x39, 0x54, 0x62, 0x6e, 0x71, 0x74, 0x77)
        val PRIMITIVES = setOf("void", "boolean", "byte", "short", "char", "int", "long", "float", "double")
    }
}
