package cn.ianzb.hyperrefine.hook.packageinstaller

/** Detached accessors for the information banner's own state, never for the bottom confirmation UI. */
internal data class InstallerGuardBannerAccess(
    val modelClass: String,
    val stateGetter: InstallerMethod,
    val textGetters: List<InstallerMethod>,
    val kindGetter: InstallerMethod,
)

/** Finds only the nullable default-audit-information factory; the callback must still validate its result. */
internal class InstallerGuardBannerRules(private val index: InstallerDexIndex) {
    fun resolve(): InstallerResolvedPoint {
        val factories = index.findMethods(InstallerMethodQuery(parameterCount = 0, isStatic = false)).filter { method ->
            method.returnType !in PRIMITIVES && method.writtenFields.isEmpty() &&
                RETURN_OBJECT in method.opcodes
        }
        val stats = Stats()
        val accessesByModel = mutableMapOf<String, List<InstallerGuardBannerAccess>>()
        val structural = factories.mapNotNull { factory ->
            val models = factory.invokes.mapNotNull(index::method).filter { constructor ->
                constructor.name == "<init>" && constructor.parameterTypes.firstOrNull() == "android.content.Context" &&
                    constructor.parameterTypes.count { it in TEXT_TYPES } == 6 &&
                    factory.returnType in ancestors(constructor.owner)
            }.map { it.owner }.distinct()
            stats.construction += models.size
            val accesses = models.flatMap { model -> accessesByModel.getOrPut(model) { modelAccess(model) } }
            stats.verifiedModels += accesses.size
            if (accesses.size != 1) return@mapNotNull null
            // Factories assembling their own detail lists represent composite risk information.
            if (factory.invokes.any(::listAppend)) {
                stats.internalCollections++
                return@mapNotNull null
            }
            if (!nullableInformationConsumers(factory, stats)) null else factory to accesses.single()
        }
        // Cloud layout varies. It is a positive discriminator only when several independently
        // verified factories share the model; it is never required for a unique structural match.
        val cloud = structural.filter { informationHint(it.first) }
        val candidates = when {
            structural.size == 1 -> structural
            cloud.size == 1 -> cloud
            else -> structural
        }
        if (candidates.size != 1) throw InstallerMatchException("guard_banner.default_information_factory", candidates.size,
            "guard_banner structural=${structural.size} security_tip=${cloud.size} factory_inputs=${factories.size} construction=${stats.construction} model_classes=${accessesByModel.size} " +
                "verified_models=${stats.verifiedModels} internal_collections=${stats.internalCollections} " +
                "consumers=${stats.consumers} nullable_consumers=${stats.nullableConsumers} " +
                "outer_consumers=${stats.outerConsumers} list_consumers=${stats.listConsumers}; unknown/ambiguous structures preserve original result")
        val (factory, access) = candidates.single()
        return InstallerResolvedPoint("guard_banner", factory, guardBanner = access, evidence = listOf(
            "six-text model constructor + pure state access + observed nullable fallback/list consumers",
            "all observed direct consumers satisfy the nullable/list relationship; optional securityTip only disambiguates",
            "one pure state getter + six distinct pure text getters + pure enum kind getter",
            "after callback must match exact model class, WARNING and current-locale default texts; no fullsafe class filter",
        ))
    }

    private fun informationHint(method: InstallerMethod): Boolean {
        val reads = method.readFields.toMutableSet()
        // A field access can be compiled as a getter. Only traverse already proven pure getters,
        // with a hard cap; never execute them or traverse arbitrary producer calls.
        if (method.invokes.distinct().size > 64) return false
        method.invokes.distinct().mapNotNull(index::method).filter(::pureObjectGetter).forEach { reads += it.readFields }
        return reads.mapNotNull(index::field).any { !it.isStatic && key(it, "securityTip") && payloadShape(it.type) }
    }

    private fun payloadShape(type: String): Boolean {
        val fields = index.clazz(type)?.fields.orEmpty().mapNotNull(index::field)
        return listOf("title", "text", "jumpText").all { name ->
            fields.count { !it.isStatic && it.type == "java.lang.String" && key(it, name) } == 1
        }
    }

    private fun key(field: InstallerField, name: String): Boolean = field.name == name || name in field.annotationStrings

    private fun modelAccess(model: String): List<InstallerGuardBannerAccess> = methods(model).filter(::pureObjectGetter).mapNotNull { getter ->
        val state = getter.returnType
        val stateFields = index.clazz(state)?.fields.orEmpty().mapNotNull(index::field).filterNot { it.isStatic }
        val textFields = stateFields.filter { it.type in TEXT_TYPES }
        if (stateFields.size != 7 || textFields.size != 6) return@mapNotNull null
        val stateMethods = methods(state)
        if (stateMethods.none { it.parameterTypes.isEmpty() && it.returnType == "java.lang.String" &&
                it.strings.containsAll(STATE_LABELS) && it.readFields.containsAll(textFields.map { field -> field.descriptor }) }) return@mapNotNull null
        val texts = stateMethods.filter { pureObjectGetter(it) && it.returnType in TEXT_TYPES }
        if (texts.size != 6 || texts.map { it.readFields.single() }.toSet() != textFields.map { it.descriptor }.toSet()) return@mapNotNull null
        val kinds = stateMethods.filter { pureObjectGetter(it) && index.clazz(it.returnType)?.superName == "java.lang.Enum" }
        if (kinds.size != 1) return@mapNotNull null
        InstallerGuardBannerAccess(model, getter, texts.sortedBy { it.descriptor }, kinds.single())
    }

    private fun pureObjectGetter(method: InstallerMethod): Boolean {
        if (method.isStatic || method.parameterTypes.isNotEmpty() || method.invokes.isNotEmpty() || method.strings.isNotEmpty() ||
            method.writtenFields.isNotEmpty() || method.readFields.size != 1 || method.returnType in PRIMITIVES) return false
        val field = index.field(method.readFields.single()) ?: return false
        val code = method.opcodes.filter { it != 0x00 }
        return !field.isStatic && field.owner == method.owner && field.type == method.returnType &&
            code.lastOrNull() == RETURN_OBJECT && code.count { it == IGET_OBJECT } == 1 &&
            code.all { it in PURE_GETTER_OPCODES }
    }

    private fun nullableInformationConsumers(factory: InstallerMethod, stats: Stats): Boolean {
        val consumers = index.callers(factory.descriptor).distinctBy { it.descriptor }
        stats.consumers += consumers.size
        if (consumers.isEmpty() || consumers.size > 32) return false
        var accepted = true
        for (consumer in consumers) {
            val matches = consumer.owner == factory.owner && consumer.returnType in ancestors(factory.returnType) &&
                consumer.opcodes.any { it in NULL_BRANCHES } &&
                consumer.invokes.distinct().mapNotNull(index::method).count { alternative ->
                    alternative.owner == factory.owner && alternative.descriptor != factory.descriptor && alternative.returnType == factory.returnType
                } >= 2
            if (!matches) {
                accepted = false
                continue
            }
            stats.nullableConsumers++
            val outers = index.callers(consumer.descriptor).distinctBy { it.descriptor }
            stats.outerConsumers += outers.size
            if (outers.isEmpty() || outers.size > 32) accepted = false
            for (outer in outers) {
                if (outer.returnType == "void" && outer.opcodes.any { it in NULL_BRANCHES } && outer.invokes.any(::listAppend)) {
                    stats.listConsumers++
                } else accepted = false
            }
        }
        return accepted
    }

    private fun ancestors(type: String): Set<String> {
        val found = linkedSetOf(type)
        var next = index.clazz(type)?.superName
        while (next != null && found.size < 32 && found.add(next)) next = index.clazz(next)?.superName
        return found
    }

    private fun methods(owner: String): List<InstallerMethod> = index.clazz(owner)?.methods.orEmpty().mapNotNull(index::method)

    private fun listAppend(descriptor: String): Boolean {
        val suffix = ";->add(Ljava/lang/Object;)Z"
        if (!descriptor.startsWith("L") || !descriptor.endsWith(suffix)) return false
        val owner = descriptor.substring(1, descriptor.length - suffix.length).replace('/', '.')
        return owner in setOf("java.util.List", "java.util.ArrayList", "java.util.LinkedList", "java.util.Collection")
    }

    private companion object {
        private val TEXT_TYPES = setOf("java.lang.CharSequence", "java.lang.String")
        private val PRIMITIVES = setOf("void", "boolean", "byte", "short", "char", "int", "long", "float", "double")
        private val STATE_LABELS = setOf("Data(type=", ", title1=", ", content1=", ", title2=", ", content2=", ", content2extra=", ", content3=")
        private val PURE_GETTER_OPCODES = setOf(0x07, 0x08, 0x09, 0x54, 0x11)
        private val NULL_BRANCHES = setOf(0x38, 0x39)
        private const val IGET_OBJECT = 0x54
        private const val RETURN_OBJECT = 0x11
    }

    private class Stats {
        var construction = 0
        var verifiedModels = 0
        var internalCollections = 0
        var consumers = 0
        var nullableConsumers = 0
        var outerConsumers = 0
        var listConsumers = 0
    }
}

/** No prefix/substring acceptance: unknown server text, extra rows and risk kinds retain the original result. */
internal object InstallerGuardBannerPolicy {
    fun matches(kind: String, texts: List<CharSequence?>, title: String, body: String, links: Set<String>): Boolean {
        if (kind != "WARNING" || texts.size != 6 || title.length > 4_096 || body.length > 4_096 ||
            texts.any { it != null && it.length > 4_096 } || links.any { it.length > 512 }) return false
        val expectedTitle = normalize(title)
        val expectedBody = normalize(body)
        if (expectedTitle.isEmpty() || expectedBody.isEmpty() || expectedTitle == expectedBody) return false
        val present = texts.map { normalize(it?.toString().orEmpty()) }.filter { it.isNotEmpty() }
        if (present.size != 2 || present.count { it == expectedTitle } != 1) return false
        val bodies = setOf(expectedBody) + links.map(::normalize).filter { it.isNotEmpty() }.map { expectedBody + it }
        return present.count { it in bodies } == 1
    }

    private fun normalize(text: String): String = text.filterNot { it.isWhitespace() || it == '\u200b' }
}
