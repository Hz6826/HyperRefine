package cn.ianzb.hyperrefine.hook.packageinstaller

/** Local business capabilities. Discovery never invokes target code or changes global authorization. */
internal class InstallerLocalRules(private val index: InstallerDexIndex) {
    fun resolve(id: String): InstallerResolvedPoint = when (id) {
        "risk_scan" -> riskScan()
        "guard_tip" -> guardTip()
        "frequent_check" -> frequentCheck()
        else -> throw IllegalArgumentException("unknown local installer point: $id")
    }

    private fun riskScan(): InstallerResolvedPoint {
        val virusPreference = preferenceReader("risk_scan.virus_preference", "virus_scan_install")
        val safePreference = preferenceReader("risk_scan.safe_preference", "miui_safe_mode")
        val candidates = index.findMethods(InstallerMethodQuery(
            returnType = "boolean", parameterCount = 0, isStatic = false,
        )).filter { gate ->
            gate.writtenFields.isEmpty() &&
                virusPreference.descriptor in gate.invokes && safePreference.descriptor in gate.invokes &&
                ownerStrings(gate.owner).contains("InstallTask") &&
                hasOnlyScanConsumers(gate)
        }
        return InstallerResolvedPoint(
            id = "risk_scan", method = uniqueInstallerMatch("risk_scan.local_scan_gate", candidates),
            evidence = listOf("virus_scan_install + miui_safe_mode readers", "every consumer is a scan coroutine or pure accessor used only by scan coroutines"),
        )
    }

    private fun hasOnlyScanConsumers(gate: InstallerMethod): Boolean {
        val consumers = index.callers(gate.descriptor).distinctBy { it.descriptor }
        return consumers.isNotEmpty() && consumers.all { consumer ->
            isScanConsumer(consumer) || isPureGateAccessor(consumer, gate) &&
                index.callers(consumer.descriptor).distinctBy { it.descriptor }.let { accessorConsumers ->
                    accessorConsumers.isNotEmpty() && accessorConsumers.all(::isScanConsumer)
                }
        }
    }

    private fun isScanConsumer(caller: InstallerMethod): Boolean =
        caller.returnType == "java.lang.Object" && caller.parameterTypes == listOf("java.lang.Object") &&
            0x38 in caller.opcodes && 0x11 in caller.opcodes &&
            caller.invokes.mapNotNull(index::method).any { scan ->
                scan.returnType == "java.lang.Object" &&
                    scan.parameterTypes.firstOrNull() == "java.lang.String" && scan.parameterTypes.size >= 3 &&
                    ownerStrings(scan.owner).let { strings ->
                        "NewVirusScanner" in strings && strings.any {
                            it.startsWith("Exception when check virus:") || it == "using new VirusScanManager SDK"
                        }
                    }
            }

    private fun isPureGateAccessor(accessor: InstallerMethod, gate: InstallerMethod): Boolean =
        accessor.owner == gate.owner && accessor.isStatic && accessor.returnType == "boolean" &&
            accessor.parameterTypes == listOf(gate.owner) && accessor.invokes.distinct() == listOf(gate.descriptor) &&
            accessor.strings.isEmpty() && accessor.readFields.isEmpty() && accessor.writtenFields.isEmpty() &&
            accessor.opcodes.isNotEmpty() && accessor.opcodes.last() == 0x0f &&
            accessor.opcodes.all { it in ACCESSOR_OPCODES } &&
            accessor.opcodes.count { it in ACCESSOR_INVOKES } == 1 && accessor.opcodes.count { it == 0x0a } == 1

    private fun preferenceReader(role: String, key: String): InstallerMethod = uniqueInstallerMatch(
        role,
        index.findMethods(InstallerMethodQuery(
            strings = setOf(key), returnType = "boolean",
            parameterTypes = listOf("android.content.Context"), isStatic = true,
        )).filter { method ->
            method.invokes.any { it == "Landroid/content/Context;->getContentResolver()Landroid/content/ContentResolver;" }
        },
    )

    private fun guardTip(): InstallerResolvedPoint {
        val stages = linkedMapOf("model" to 0, "construction" to 0, "builder" to 0,
            "adapter" to 0, "predicate" to 0, "register" to 0, "consumer" to 0, "collection" to 0)
        // These are the actual switch/action analytics, not a class-name substring such as "Tip".
        val constructors = index.findMethods(InstallerMethodQuery(
            strings = setOf("safe_mode_switch", "safe_mode_know_btn", "switch_action"),
            returnType = "void", isStatic = false,
        )).filter { constructor ->
            constructor.name == "<init>" && constructor.parameterTypes.size >= 2 &&
                constructor.parameterTypes[0] == "android.content.Context" &&
                index.clazz(constructor.owner)?.superName.let { it != null && it != "java.lang.Object" }
        }
        stages["model"] = constructors.map { it.owner }.distinct().size
        stages["construction"] = constructors.size
        val tipOwner = guardUnique("guard_tip.switch_model", constructors.map { it.owner }.distinct(), stages)
        val anchor = guardUnique("guard_tip.switch_constructor", constructors.filter { it.owner == tipOwner }, stages)
        val dataClass = anchor.parameterTypes[1]
        val modelConstructors = classMethods(tipOwner).filter { it.name == "<init>" }
        val builders = modelConstructors.flatMap { index.callers(it.descriptor) }.distinctBy { it.descriptor }.filter { builder ->
            builder.readFields.mapNotNull(index::field).any {
                it.owner == dataClass && it.type == "java.lang.Boolean"
            } && builder.invokes.any { guardListCall(it, indexed = false) }
        }
        stages["builder"] = builders.size
        if (builders.isEmpty()) throw InstallerMatchException("guard_tip.model_builder", 0, guardStages(stages))

        val adapters = index.findMethods(InstallerMethodQuery(
            returnType = "boolean", parameterCount = 1, isStatic = false,
        )).filter { guardAssignable(tipOwner, it.parameterTypes.single()) && !guardPureForwarder(it) }
        stages["adapter"] = adapters.size
        val candidates = adapters.filter { accepts ->
            // The registry may inherit helpers, erase generic types, or inline its query/put helpers.
            // Verify its bounded call/field relationships rather than one compiler's helper prototypes.
            val family = guardRegistryClosure(accepts)
            val predicates = family.filter { call -> call.invokes.any { guardMapCall(it, "containsKey") } }
            if (predicates.isEmpty()) return@filter false
            stages["predicate"] = stages.getValue("predicate") + 1
            val queryFields = predicates.flatMap { guardMapFields(it, accepts.owner) }.toSet()
            val registrations = family.filter { call -> call.invokes.any { guardMapCall(it, "put") } }
            val registrationFields = registrations.flatMap { guardMapFields(it, accepts.owner) }.toSet()
            if (queryFields.intersect(registrationFields).isEmpty()) return@filter false
            stages["register"] = stages.getValue("register") + 1
            val consumers = guardConsumers(accepts).filter { insert ->
                insert.returnType in setOf("int", "boolean", "void") &&
                    insert.parameterTypes.count { guardAssignable(tipOwner, it) } == 1 &&
                    insert.readFields.mapNotNull(index::field).any { field ->
                        !field.isStatic && guardRelated(field.type, accepts.owner)
                    }
            }
            if (consumers.isEmpty()) return@filter false
            stages["consumer"] = stages.getValue("consumer") + 1
            // DTO metadata proves joint calls and typed fields, not register-level argument/branch dataflow.
            val insertion = consumers.any { insert ->
                val lists = insert.readFields.mapNotNull(index::field).filter { field ->
                    !field.isStatic && guardListType(field.type) && guardAssignable(insert.owner, field.owner)
                }.distinctBy { it.descriptor }
                lists.size == 1 && insert.invokes.any {
                    guardListCall(it, indexed = true) || guardListCall(it, indexed = false)
                }
            }
            if (insertion) stages["collection"] = stages.getValue("collection") + 1
            insertion
        }
        return InstallerResolvedPoint(
            id = "guard_tip", method = guardUnique("guard_tip.adapter_accepts_model", candidates, stages),
            guardedItemClass = tipOwner,
            evidence = listOf("safe mode switch model + cloud flag list builder",
                "registry query/put share a map field; typed registry consumer and list insertion",
                "joint metadata relationships, not register-level dataflow", guardStages(stages)),
        )
    }

    private fun guardStages(stages: Map<String, Int>): String =
        "guard stages " + stages.entries.joinToString(" ") { "${it.key}=${it.value}" }

    private fun <T> guardUnique(role: String, candidates: List<T>, stages: Map<String, Int>): T {
        if (candidates.size != 1) throw InstallerMatchException(role, candidates.size, guardStages(stages))
        return candidates.single()
    }

    private fun guardAncestors(type: String): Set<String> {
        val found = linkedSetOf<String>()
        val pending = ArrayDeque<String>()
        pending.add(type)
        while (pending.isNotEmpty() && found.size < 64) {
            val next = pending.removeFirst()
            if (!found.add(next)) continue
            index.clazz(next)?.let { clazz ->
                clazz.superName?.let(pending::addLast)
                clazz.interfaces.forEach(pending::addLast)
            }
        }
        return found
    }

    private fun guardAssignable(type: String, parent: String): Boolean =
        parent == "java.lang.Object" || parent in guardAncestors(type)

    private fun guardRelated(left: String, right: String): Boolean =
        left != "java.lang.Object" && right != "java.lang.Object" &&
            (guardAssignable(left, right) || guardAssignable(right, left))

    private fun guardMapType(type: String): Boolean = guardAncestors(type).any { it in GUARD_MAP_TYPES }

    private fun guardListType(type: String): Boolean = guardAncestors(type).any { it in GUARD_LIST_TYPES }

    private fun guardMapCall(descriptor: String, name: String): Boolean {
        val suffix = when (name) {
            "containsKey" -> ";->containsKey(Ljava/lang/Object;)Z"
            "put" -> ";->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"
            else -> return false
        }
        return descriptor.startsWith("L") && descriptor.endsWith(suffix) &&
            guardMapType(descriptor.substring(1, descriptor.length - suffix.length).replace('/', '.'))
    }

    private fun guardListCall(descriptor: String, indexed: Boolean): Boolean {
        val suffix = if (indexed) ";->add(ILjava/lang/Object;)V" else ";->add(Ljava/lang/Object;)Z"
        if (!descriptor.startsWith("L") || !descriptor.endsWith(suffix)) return false
        val owner = descriptor.substring(1, descriptor.length - suffix.length).replace('/', '.')
        return guardListType(owner) || !indexed && owner == "java.util.Collection"
    }

    private fun guardMapFields(method: InstallerMethod, registry: String): List<String> =
        method.readFields.mapNotNull(index::field).filter {
            !it.isStatic && guardMapType(it.type) && guardAssignable(registry, it.owner)
        }.map { it.descriptor }

    private fun guardRegistryClosure(start: InstallerMethod): List<InstallerMethod> {
        val found = linkedMapOf<String, InstallerMethod>()
        val pending = ArrayDeque<Pair<InstallerMethod, Int>>()
        pending.add(start to 0)
        while (pending.isNotEmpty() && found.size < 32) {
            val (next, depth) = pending.removeFirst()
            if (found.putIfAbsent(next.descriptor, next) != null || depth >= 3) continue
            next.invokes.mapNotNull(index::method).filter { call ->
                !call.isStatic && call.name != "<init>" && guardAssignable(start.owner, call.owner)
            }.forEach { pending.addLast(it to depth + 1) }
        }
        return found.values.toList()
    }

    private fun guardConsumers(accepts: InstallerMethod): List<InstallerMethod> {
        val found = linkedMapOf<String, InstallerMethod>()
        val pending = ArrayDeque<Pair<InstallerMethod, Int>>()
        index.callers(accepts.descriptor).forEach { pending.add(it to 0) }
        while (pending.isNotEmpty() && found.size < 32) {
            val (next, depth) = pending.removeFirst()
            if (found.putIfAbsent(next.descriptor, next) != null || depth >= 2) continue
            if (guardPureForwarder(next)) index.callers(next.descriptor).forEach { pending.add(it to depth + 1) }
        }
        return found.values.toList()
    }

    private fun guardPureForwarder(method: InstallerMethod): Boolean =
        method.returnType == "boolean" && method.parameterTypes.size == 1 &&
            method.strings.isEmpty() && method.readFields.isEmpty() && method.writtenFields.isEmpty() &&
            method.invokes.size == 1 && method.opcodes.isNotEmpty() && method.opcodes.last() == 0x0f &&
            method.opcodes.all { it in GUARD_FORWARDER_OPCODES } &&
            method.opcodes.count { it in GUARD_FORWARDER_INVOKES } == 1

    private fun frequentCheck(): InstallerResolvedPoint {
        val candidates = index.findMethods(InstallerMethodQuery(
            numbers = setOf(86_400_000L, 0L, 1L, 2L, 3L), returnType = "int", parameterCount = 0, isStatic = false,
        )).filter { level ->
            val ownFields = level.readFields.mapNotNull(index::field).filter { it.owner == level.owner }
                .distinctBy { it.descriptor }
            val countFields = ownFields.filter { it.type == "int" && !it.isStatic }
            val packageFields = ownFields.filter { it.type == "java.lang.String" && !it.isStatic }
            val countColumns = countFields.flatMap { it.annotationStrings }.toSet()
            val packageColumns = packageFields.flatMap { it.annotationStrings }.toSet()
            countFields.size == 3 && packageFields.size == 1 &&
                countColumns.containsAll(setOf("rcafvc", "rchvc", "rcsvc")) && "package_name" in packageColumns &&
                level.writtenFields.isEmpty() && LIST_SIZE in level.invokes &&
                level.invokes.mapNotNull(index::method).any { history ->
                    history.parameterTypes == listOf("java.lang.String", "long") &&
                        history.returnType == "java.util.List" &&
                        history.strings.map(::compactSql).let { sql ->
                            "installer_package_name=?" in sql && "install_time>?" in sql
                        }
                } && index.callers(level.descriptor).any { caller ->
                    caller.returnType == "void" && caller.parameterTypes.isEmpty() &&
                        isSubclassOf(caller.owner, "android.app.Activity")
                }
        }
        return InstallerResolvedPoint(
            id = "frequent_check", method = uniqueInstallerMatch("frequent_check.install_history_level", candidates),
            evidence = listOf("24h install history + three annotated frequency thresholds", "pre-install Activity consumes level"),
        )
    }

    private fun classMethods(owner: String): List<InstallerMethod> =
        index.clazz(owner)?.methods.orEmpty().mapNotNull(index::method)

    private fun ownerStrings(owner: String): Set<String> = classMethods(owner).flatMap { it.strings }.toSet()

    private fun isSubclassOf(type: String, parent: String): Boolean {
        val visited = mutableSetOf<String>()
        var current: String? = type
        while (current != null && visited.add(current)) {
            if (current == parent) return true
            current = index.clazz(current)?.superName
        }
        return false
    }

    private fun compactSql(value: String): String = value.filterNot(Char::isWhitespace).lowercase()

    companion object {
        private const val LIST_SIZE = "Ljava/util/List;->size()I"
        private val GUARD_MAP_TYPES = setOf("java.util.Map", "java.util.HashMap", "java.util.LinkedHashMap",
            "java.util.TreeMap", "java.util.AbstractMap", "java.util.concurrent.ConcurrentMap", "java.util.concurrent.ConcurrentHashMap")
        private val GUARD_LIST_TYPES = setOf("java.util.List", "java.util.ArrayList", "java.util.LinkedList",
            "java.util.AbstractList", "java.util.AbstractSequentialList", "java.util.Vector", "java.util.concurrent.CopyOnWriteArrayList")
        private val GUARD_FORWARDER_OPCODES = setOf(0x00, 0x01, 0x02, 0x03, 0x07, 0x08, 0x09, 0x0a,
            0x0f, 0x1f, 0x6e, 0x70, 0x72, 0x74, 0x76, 0x78)
        private val GUARD_FORWARDER_INVOKES = setOf(0x6e, 0x70, 0x72, 0x74, 0x76, 0x78)
        private val ACCESSOR_INVOKES = setOf(0x6e, 0x70, 0x74, 0x76) // virtual/direct, ordinary/range
        private val ACCESSOR_OPCODES = ACCESSOR_INVOKES + setOf(0x01, 0x02, 0x03, 0x07, 0x08, 0x09, 0x0a, 0x0f)
    }
}
