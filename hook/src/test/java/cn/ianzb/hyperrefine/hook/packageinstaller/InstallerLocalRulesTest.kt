package cn.ianzb.hyperrefine.hook.packageinstaller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class InstallerLocalRulesTest {
    @Test fun resolvesThreeIndependentBusinessCapabilities() {
        val f = fixture()
        val rules = InstallerLocalRules(f.index)
        assertEquals(f.risk.descriptor, rules.resolve("risk_scan").method.descriptor)
        assertEquals(f.accept.descriptor, rules.resolve("guard_tip").method.descriptor)
        assertEquals(f.tip, rules.resolve("guard_tip").guardedItemClass)
        assertEquals(f.level.descriptor, rules.resolve("frequent_check").method.descriptor)
    }

    @Test fun classMethodAndFieldRenamingKeepsSemanticCapabilities() {
        val f = fixture()
        val renamed = f.index.renamed()
        val rules = InstallerLocalRules(renamed)
        for (id in listOf("risk_scan", "guard_tip", "frequent_check")) {
            val original = InstallerLocalRules(f.index).resolve(id)
            val moved = rules.resolve(id)
            assertNotEquals(original.method.descriptor, moved.method.descriptor)
            assertEquals(original.method.returnType, moved.method.returnType)
        }
        assertNotEquals(f.tip, rules.resolve("guard_tip").guardedItemClass)
    }

    @Test fun duplicatePreferenceReaderDoesNotPickFirst() {
        val f = fixture()
        val leaf = f.index.methods.single { "virus_scan_install" in it.strings }
        val duplicate = leaf.copy(name = "duplicate", descriptor = descriptor(leaf.owner, "duplicate", leaf.returnType, leaf.parameterTypes))
        val error = assertThrows(InstallerMatchException::class.java) {
            InstallerLocalRules(f.index.withMethods(f.index.methods + duplicate)).resolve("risk_scan")
        }
        assertEquals(2, error.candidateCount)
    }

    @Test fun ambiguousLocalScanGateIsRejected() {
        val f = fixture()
        val duplicate = f.risk.copy(name = "anotherGate", descriptor = descriptor(f.risk.owner, "anotherGate", "boolean", emptyList()))
        val scannerCaller = f.index.methods.single { it.name == "scanCoroutine" }
        val changed = f.index.methods.map {
            if (it == scannerCaller) it.copy(invokes = it.invokes + duplicate.descriptor) else it
        } + duplicate
        val error = assertThrows(InstallerMatchException::class.java) {
            InstallerLocalRules(f.index.withMethods(changed)).resolve("risk_scan")
        }
        assertEquals(2, error.candidateCount)
    }

    @Test fun permissionBooleanWithSamePreferencesIsNotScanGate() {
        val f = fixture()
        val decoy = f.risk.copy(name = "sourceAuthorization", descriptor = descriptor(f.risk.owner, "sourceAuthorization", "boolean", emptyList()))
        val consumer = method("fixture.permission.Auth", "check", "void", invokes = listOf(decoy.descriptor))
        val rules = InstallerLocalRules(f.index.withMethods(f.index.methods + decoy + consumer))
        assertEquals(f.risk.descriptor, rules.resolve("risk_scan").method.descriptor)
    }

    @Test fun missingScannerRelationshipStopsRiskOnly() {
        val f = fixture()
        val index = f.index.withMethods(f.index.methods.filterNot { it.name == "scanCoroutine" })
        val rules = InstallerLocalRules(index)
        assertThrows(InstallerMatchException::class.java) { rules.resolve("risk_scan") }
        assertEquals(f.accept.descriptor, rules.resolve("guard_tip").method.descriptor)
        assertEquals(f.level.descriptor, rules.resolve("frequent_check").method.descriptor)
    }

    @Test fun gateSharedByScanAndAuthorizationIsRejected() {
        val f = fixture()
        val authorization = method("fixture.permission.Auth", "authorize", "boolean", invokes = listOf(f.risk.descriptor))
        val rules = InstallerLocalRules(f.index.withMethods(f.index.methods + authorization))
        assertThrows(InstallerMatchException::class.java) { rules.resolve("risk_scan") }
        assertEquals(f.accept.descriptor, rules.resolve("guard_tip").method.descriptor)
        assertEquals(f.level.descriptor, rules.resolve("frequent_check").method.descriptor)
    }

    @Test fun pureAccessorSharedByScanAndAuthorizationIsRejected() {
        val f = fixture()
        val accessor = f.index.methods.single { it.name == "accessGate" }
        val authorization = method("fixture.permission.Auth", "authorize", "boolean", invokes = listOf(accessor.descriptor))
        assertThrows(InstallerMatchException::class.java) {
            InstallerLocalRules(f.index.withMethods(f.index.methods + authorization)).resolve("risk_scan")
        }
    }

    @Test fun accessorWithAnotherBusinessCallIsRejected() {
        val f = fixture()
        val changed = f.index.methods.map { if (it.name == "accessGate") it.copy(invokes = it.invokes + CONTENT_RESOLVER) else it }
        assertThrows(InstallerMatchException::class.java) {
            InstallerLocalRules(f.index.withMethods(changed)).resolve("risk_scan")
        }
    }

    @Test fun globalPreferenceReaderIsNeverTheHookTarget() {
        val f = fixture()
        val result = InstallerLocalRules(f.index).resolve("risk_scan")
        assertEquals(emptyList<String>(), result.method.parameterTypes)
        assertEquals(false, result.method.isStatic)
        assertEquals(emptySet<String>(), result.method.strings)
    }

    @Test fun mapPredicateCannotReplaceAdapterAcceptance() {
        val f = fixture()
        val rules = InstallerLocalRules(f.index.withMethods(f.index.methods.filterNot { it == f.accept }))
        val error = assertThrows(InstallerMatchException::class.java) { rules.resolve("guard_tip") }
        assertEquals("guard_tip.adapter_accepts_model", error.role)
        assertEquals(0, error.candidateCount)
    }

    @Test fun fullSafeModelDoesNotBecomeGuardedItem() {
        val f = fixture()
        val fullSafe = method("fixture.ui.FullSafe", "<init>", "void", listOf("android.content.Context", f.cloud))
        val index = f.index.withMethods(f.index.methods + fullSafe).withClass(InstallerClass("fixture.ui.FullSafe", f.base, methods = listOf(fullSafe.descriptor)))
        assertEquals(f.tip, InstallerLocalRules(index).resolve("guard_tip").guardedItemClass)
    }

    @Test fun duplicateSwitchModelIsAmbiguous() {
        val f = fixture()
        val ctor = f.index.methods.single { it.owner == f.tip }
        val clone = ctor.copy(owner = "fixture.ui.OtherSwitch", descriptor = descriptor("fixture.ui.OtherSwitch", "<init>", "void", ctor.parameterTypes))
        val index = f.index.withMethods(f.index.methods + clone).withClass(InstallerClass(clone.owner, f.base, methods = listOf(clone.descriptor)))
        val error = assertThrows(InstallerMatchException::class.java) { InstallerLocalRules(index).resolve("guard_tip") }
        assertEquals(2, error.candidateCount)
    }

    @Test fun switchModelWithoutCloudFlagBuilderIsRejected() {
        val f = fixture()
        val index = f.index.withMethods(f.index.methods.map { if (it.name == "buildList") it.copy(readFields = emptyList()) else it })
        val error = assertThrows(InstallerMatchException::class.java) { InstallerLocalRules(index).resolve("guard_tip") }
        assertEquals("guard_tip.model_builder", error.role)
    }

    @Test fun guardDoesNotRequireCommonAdapterLogLiterals() {
        val f = fixture()
        val changed = f.index.methods.map { if (it.name == "insert") it.copy(strings = emptySet()) else it }
        assertEquals(f.accept.descriptor, InstallerLocalRules(f.index.withMethods(changed)).resolve("guard_tip").method.descriptor)
    }

    @Test fun guardAcceptsConcreteMapAndListInvokeOwners() {
        val f = fixture()
        val changed = f.index.methods.map { method -> method.copy(invokes = method.invokes.map {
            it.replace("Ljava/util/Map;->", "Ljava/util/HashMap;->").replace("Ljava/util/List;->add", "Ljava/util/LinkedList;->add")
        }) }
        assertEquals(f.accept.descriptor, InstallerLocalRules(f.index.withMethods(changed)).resolve("guard_tip").method.descriptor)
    }

    @Test fun guardAcceptsDeclaredCustomCollectionImplementations() {
        val f = fixture()
        val changed = f.index.methods.map { method -> method.copy(invokes = method.invokes.map {
            it.replace("Ljava/util/Map;->", "Lfixture/collections/RegistryMap;->")
                .replace("Ljava/util/List;->add", "Lfixture/collections/ModelList;->add")
        }) }
        val index = f.index.withMethods(changed)
            .withClass(InstallerClass("fixture.collections.RegistryMap", "java.util.HashMap"))
            .withClass(InstallerClass("fixture.collections.ModelList", "java.lang.Object", interfaces = listOf("java.util.List")))
        assertEquals(f.accept.descriptor, InstallerLocalRules(index).resolve("guard_tip").method.descriptor)
    }

    @Test fun guardAcceptsModelAncestorAndErasedParameter() {
        val f = fixture()
        val intermediate = "fixture.ui.IntermediateModel"
        val withAncestor = f.index.withClass(InstallerClass(intermediate, f.base))
            .withClass(checkNotNull(f.index.clazz(f.tip)).copy(superName = intermediate))
        assertEquals(f.accept.descriptor, InstallerLocalRules(withAncestor).resolve("guard_tip").method.descriptor)
        val erased = f.accept.copy(parameterTypes = listOf("java.lang.Object"),
            descriptor = descriptor(f.accept.owner, f.accept.name, "boolean", listOf("java.lang.Object")))
        val changed = f.index.methods.map { method ->
            if (method == f.accept) erased else method.copy(invokes = method.invokes.map { if (it == f.accept.descriptor) erased.descriptor else it })
        }
        assertEquals(erased.descriptor, InstallerLocalRules(f.index.withMethods(changed)).resolve("guard_tip").method.descriptor)
    }

    @Test fun guardFollowsInheritedRegistryHelpersAndErasedFluentReturn() {
        val f = fixture()
        val parent = "fixture.ui.BaseRegistry"
        val helpers = f.index.methods.filter { it.name in setOf("has", "register", "putModel") }
        val moved = helpers.associate { old ->
            old.descriptor to old.copy(owner = parent,
                returnType = if (old.returnType == f.accept.owner) "java.lang.Object" else old.returnType,
                descriptor = descriptor(parent, old.name, if (old.returnType == f.accept.owner) "java.lang.Object" else old.returnType, old.parameterTypes))
        }
        val mapField = f.index.fields.single { it.name == "delegates" }
        val movedField = field(parent, mapField.name, mapField.type)
        val changed = f.index.methods.map { original ->
            val method = moved[original.descriptor] ?: original
            method.copy(invokes = method.invokes.map { moved[it]?.descriptor ?: it },
                readFields = method.readFields.map { if (it == mapField.descriptor) movedField.descriptor else it })
        }
        val index = f.index.withMethods(changed).copy(fields = f.index.fields.filterNot { it == mapField } + movedField)
            .withClass(checkNotNull(f.index.clazz(f.accept.owner)).copy(superName = parent,
                methods = changed.filter { it.owner == f.accept.owner }.map { it.descriptor }))
            .withClass(InstallerClass(parent, "java.lang.Object", methods = changed.filter { it.owner == parent }.map { it.descriptor }, fields = listOf(movedField.descriptor)))
        assertEquals(f.accept.descriptor, InstallerLocalRules(index).resolve("guard_tip").method.descriptor)
    }

    @Test fun guardAcceptsInlinedRegistryQueryAndRegistration() {
        val f = fixture()
        val map = f.index.fields.single { it.name == "delegates" }
        val changed = f.index.methods.map {
            if (it == f.accept) it.copy(invokes = listOf(CONTAINS_KEY, MAP_PUT), readFields = listOf(map.descriptor)) else it
        }
        assertEquals(f.accept.descriptor, InstallerLocalRules(f.index.withMethods(changed)).resolve("guard_tip").method.descriptor)
    }

    @Test fun guardFollowsPureErasedBridgeButHooksBusinessBodyOnce() {
        val f = fixture()
        val bridge = method(f.accept.owner, "bridgeAccept", "boolean", listOf("java.lang.Object"), invokes = listOf(f.accept.descriptor))
            .copy(opcodes = listOf(0x1f, 0x6e, 0x0a, 0x0f))
        val changed = f.index.methods.map {
            if (it.name == "insert") it.copy(invokes = it.invokes.map { call -> if (call == f.accept.descriptor) bridge.descriptor else call }) else it
        } + bridge
        assertEquals(f.accept.descriptor, InstallerLocalRules(f.index.withMethods(changed)).resolve("guard_tip").method.descriptor)
    }

    @Test fun guardRejectsArbitraryContainsKeyAndPutMethods() {
        val f = fixture()
        val changed = f.index.methods.map { method -> method.copy(invokes = method.invokes.map {
            it.replace("Ljava/util/Map;->", "Lfixture/unrelated/Dictionary;->")
        }) }
        val error = assertThrows(InstallerMatchException::class.java) {
            InstallerLocalRules(f.index.withMethods(changed)).resolve("guard_tip")
        }
        assertEquals(0, error.candidateCount)
        assertEquals(true, error.message.orEmpty().contains("predicate=0"))
    }

    @Test fun guardRequiresActualRegistrationPutRelationship() {
        val f = fixture()
        val changed = f.index.methods.map { if (it.name == "putModel") it.copy(invokes = emptyList()) else it }
        val error = assertThrows(InstallerMatchException::class.java) {
            InstallerLocalRules(f.index.withMethods(changed)).resolve("guard_tip")
        }
        assertEquals(true, error.message.orEmpty().contains("register=0"))
    }

    @Test fun guardRejectsPutToAnUnrelatedMapField() {
        val f = fixture()
        val other = field(f.accept.owner, "unrelatedMap", "java.util.Map")
        val changed = f.index.methods.map { if (it.name == "putModel") it.copy(readFields = listOf(other.descriptor)) else it }
        val index = f.index.withMethods(changed).copy(fields = f.index.fields + other)
        assertThrows(InstallerMatchException::class.java) { InstallerLocalRules(index).resolve("guard_tip") }
    }

    @Test fun guardRejectsConsumerWithoutTypedRegistryRelationship() {
        val f = fixture()
        val changed = f.index.methods.map {
            if (it.name == "insert") it.copy(readFields = it.readFields.filterNot { field -> field == f.index.fields.single { it.name == "registry" }.descriptor }) else it
        }
        val error = assertThrows(InstallerMatchException::class.java) {
            InstallerLocalRules(f.index.withMethods(changed)).resolve("guard_tip")
        }
        assertEquals(true, error.message.orEmpty().contains("consumer=0"))
    }

    @Test fun guardRejectsUnrelatedAddMethodAndMultipleListFields() {
        val f = fixture()
        val arbitraryAdd = f.index.methods.map {
            if (it.name == "insert") it.copy(invokes = it.invokes.map { call -> call.replace("Ljava/util/List;->add", "Lfixture/unrelated/Store;->add") }) else it
        }
        assertThrows(InstallerMatchException::class.java) { InstallerLocalRules(f.index.withMethods(arbitraryAdd)).resolve("guard_tip") }
        val other = field("fixture.ui.ListAdapter", "unrelatedList", "java.util.List")
        val twoLists = f.index.methods.map { if (it.name == "insert") it.copy(readFields = it.readFields + other.descriptor) else it }
        val index = f.index.withMethods(twoLists).copy(fields = f.index.fields + other)
        val error = assertThrows(InstallerMatchException::class.java) { InstallerLocalRules(index).resolve("guard_tip") }
        assertEquals(true, error.message.orEmpty().contains("collection=0"))
    }

    @Test fun guardRejectsEquivalentAcceptanceAmbiguityAndReportsAllStages() {
        val f = fixture()
        val duplicate = f.accept.copy(name = "otherAcceptance", descriptor = descriptor(f.accept.owner, "otherAcceptance", "boolean", f.accept.parameterTypes))
        val changed = f.index.methods.map { if (it.name == "insert") it.copy(invokes = it.invokes + duplicate.descriptor) else it } + duplicate
        val error = assertThrows(InstallerMatchException::class.java) {
            InstallerLocalRules(f.index.withMethods(changed)).resolve("guard_tip")
        }
        assertEquals(2, error.candidateCount)
        for (stage in listOf("model", "construction", "builder", "adapter", "predicate", "register", "consumer", "collection")) {
            assertEquals(true, error.message.orEmpty().contains("$stage="))
        }
    }

    @Test fun frequencyNeedsTheInstallHistoryQuery() {
        val f = fixture()
        val index = f.index.withMethods(f.index.methods.map {
            if (it.name == "historyQuery") it.copy(strings = setOf("permission=?", "time>?")) else it
        })
        val rules = InstallerLocalRules(index)
        assertThrows(InstallerMatchException::class.java) { rules.resolve("frequent_check") }
        assertEquals(f.risk.descriptor, rules.resolve("risk_scan").method.descriptor)
        assertEquals(f.accept.descriptor, rules.resolve("guard_tip").method.descriptor)
    }

    @Test fun frequencyNeedsThreeAnnotatedThresholds() {
        val f = fixture()
        val missingColumn = f.index.fields.map { if ("rcsvc" in it.annotationStrings) it.copy(annotationStrings = emptySet()) else it }
        assertThrows(InstallerMatchException::class.java) {
            InstallerLocalRules(f.index.copy(fields = missingColumn)).resolve("frequent_check")
        }
    }

    @Test fun frequencyAmbiguityDoesNotPickFirst() {
        val f = fixture()
        val duplicate = f.level.copy(name = "otherLevel", descriptor = descriptor(f.level.owner, "otherLevel", "int", emptyList()))
        val methods = f.index.methods.map { if (it.name == "prepareHints") it.copy(invokes = it.invokes + duplicate.descriptor) else it } + duplicate
        val index = f.index.withMethods(methods).copy(numbers = f.index.numbers + (duplicate.descriptor to LEVEL_NUMBERS))
        val error = assertThrows(InstallerMatchException::class.java) { InstallerLocalRules(index).resolve("frequent_check") }
        assertEquals(2, error.candidateCount)
    }

    @Test fun twentyFourHourIntegerGetterWithoutDomainFieldsIsIgnored() {
        val f = fixture()
        val decoy = method("fixture.cache.Cache", "age", "int")
        val index = f.index.withMethods(f.index.methods + decoy).copy(numbers = f.index.numbers + (decoy.descriptor to setOf(86_400_000L)))
        assertEquals(f.level.descriptor, InstallerLocalRules(index).resolve("frequent_check").method.descriptor)
    }

    @Test fun frequencyWithoutZeroAndThreeLevelsIsRejected() {
        val f = fixture()
        val index = f.index.copy(numbers = f.index.numbers + (f.level.descriptor to (LEVEL_NUMBERS - 0L)))
        assertThrows(InstallerMatchException::class.java) { InstallerLocalRules(index).resolve("frequent_check") }
    }

    private data class Fixture(val index: FakeIndex, val risk: InstallerMethod, val accept: InstallerMethod,
                               val level: InstallerMethod, val tip: String, val base: String, val cloud: String)

    private fun fixture(): Fixture {
        val task = "fixture.task.Task"
        val scanner = "fixture.scan.Scanner"
        val base = "fixture.ui.Model"
        val tip = "fixture.ui.SafeSwitch"
        val cloud = "fixture.data.Cloud"
        val adapter = "fixture.ui.Registry"
        val counter = "fixture.data.Counts"
        val virus = method("fixture.settings.Reader", "virus", "boolean", listOf("android.content.Context"), true,
            setOf("virus_scan_install"), listOf(CONTENT_RESOLVER))
        val safe = method("fixture.settings.Reader", "safe", "boolean", listOf("android.content.Context"), true,
            setOf("miui_safe_mode"), listOf(CONTENT_RESOLVER))
        val risk = method(task, "localGate", "boolean", invokes = listOf(virus.descriptor, safe.descriptor))
        val access = method(task, "accessGate", "boolean", listOf(task), true, invokes = listOf(risk.descriptor))
            .copy(opcodes = listOf(0x70, 0x0a, 0x0f))
        val scan = method(scanner, "scan", "java.lang.Object", listOf("java.lang.String", "fixture.data.Apk", "fixture.coroutines.Token"),
            strings = setOf("NewVirusScanner", "Exception when check virus: "))
        val coroutine = method("fixture.task.ScanJob", "scanCoroutine", "java.lang.Object", listOf("java.lang.Object"),
            invokes = listOf(access.descriptor, scan.descriptor)).copy(opcodes = listOf(0x38, 0x11))
        val taskLog = method(task, "status", "void", strings = setOf("InstallTask"))
        val ctor = method(tip, "<init>", "void", listOf("android.content.Context", cloud),
            strings = setOf("safe_mode_switch", "safe_mode_know_btn", "switch_action"))
        val cloudFlag = field(cloud, "flag", "java.lang.Boolean")
        val builder = method("fixture.ui.Builder", "buildList", "void", invokes = listOf(ctor.descriptor, LIST_ADD), reads = listOf(cloudFlag.descriptor))
        val registryMap = field(adapter, "delegates", "java.util.Map")
        val registryField = field("fixture.ui.ListAdapter", "registry", adapter)
        val modelList = field("fixture.ui.ListAdapter", "models", "java.util.List")
        val predicate = method(adapter, "has", "boolean", listOf(base), invokes = listOf(CONTAINS_KEY), reads = listOf(registryMap.descriptor))
        val put = method(adapter, "putModel", adapter, listOf("int", base), invokes = listOf(MAP_PUT), reads = listOf(registryMap.descriptor))
        val register = method(adapter, "register", adapter, listOf(base), invokes = listOf(CONTAINS_KEY, put.descriptor), reads = listOf(registryMap.descriptor))
        val accept = method(adapter, "accept", "boolean", listOf(base), invokes = listOf(predicate.descriptor, register.descriptor, predicate.descriptor))
        val insert = method("fixture.ui.ListAdapter", "insert", "int", listOf("int", base, "boolean", "boolean"),
            strings = setOf("CommonAdapter", "add viewObject = "), invokes = listOf(accept.descriptor, INDEXED_ADD),
            reads = listOf(registryField.descriptor, modelList.descriptor))
        val packageField = field(counter, "source", "java.lang.String", setOf("package_name"))
        val thresholds = listOf("rcafvc", "rchvc", "rcsvc").mapIndexed { i, column -> field(counter, "count$i", "int", setOf(column)) }
        val history = method("fixture.db.History", "historyQuery", "java.util.List", listOf("java.lang.String", "long"),
            strings = setOf("installer_package_name= ?", "install_time> ?"))
        val level = method(counter, "currentLevel", "int", invokes = listOf(history.descriptor, LIST_SIZE),
            reads = thresholds.map { it.descriptor } + packageField.descriptor)
        val prepare = method("fixture.ui.Prepare", "prepareHints", "void", invokes = listOf(level.descriptor))
        val methods = listOf(virus, safe, risk, access, scan, coroutine, taskLog, ctor, builder, predicate, put, register, accept, insert, history, level, prepare)
        val fields = listOf(cloudFlag, packageField, registryMap, registryField, modelList) + thresholds
        val classes = methods.groupBy { it.owner }.map { (owner, members) -> InstallerClass(owner,
            when (owner) { tip -> base; "fixture.ui.Prepare" -> "android.app.Activity"; else -> "java.lang.Object" },
            methods = members.map { it.descriptor }, fields = fields.filter { it.owner == owner }.map { it.descriptor }) }
        return Fixture(FakeIndex(methods, fields, classes, mapOf(level.descriptor to LEVEL_NUMBERS)), risk, accept, level, tip, base, cloud)
    }

    private data class FakeIndex(val methods: List<InstallerMethod>, val fields: List<InstallerField>,
                                  val classes: List<InstallerClass>, val numbers: Map<String, Set<Long>>) : InstallerDexIndex {
        override fun findMethods(query: InstallerMethodQuery): List<InstallerMethod> = methods.filter { method ->
            method.strings.containsAll(query.strings) && numbers[method.descriptor].orEmpty().containsAll(query.numbers) &&
                (query.owner == null || query.owner == method.owner) && (query.returnType == null || query.returnType == method.returnType) &&
                (query.isStatic == null || query.isStatic == method.isStatic) &&
                (query.parameterCount == null || query.parameterCount == method.parameterTypes.size) &&
                (query.parameterTypes == null || query.parameterTypes.size == method.parameterTypes.size &&
                    query.parameterTypes.zip(method.parameterTypes).all { (required, actual) -> required == null || required == actual })
        }
        override fun method(descriptor: String): InstallerMethod? = methods.find { it.descriptor == descriptor }
        override fun callers(descriptor: String): List<InstallerMethod> = methods.filter { descriptor in it.invokes }
        override fun clazz(name: String): InstallerClass? = classes.find { it.name == name }
        override fun field(descriptor: String): InstallerField? = fields.find { it.descriptor == descriptor }
        fun withMethods(changed: List<InstallerMethod>): FakeIndex = copy(methods = changed, classes = classes.map { clazz ->
            clazz.copy(methods = changed.filter { it.owner == clazz.name }.map { it.descriptor })
        })
        fun withClass(clazz: InstallerClass): FakeIndex = copy(classes = classes.filterNot { it.name == clazz.name } + clazz)

        fun renamed(): FakeIndex {
            val custom = (classes.map { it.name } + classes.mapNotNull { it.superName } + methods.flatMap { it.parameterTypes + it.returnType })
                .filter { it.startsWith("fixture.") }.distinct()
            val names = custom.mapIndexed { i, name -> name to "relocated.a$i" }.toMap()
            fun type(name: String) = names[name] ?: name
            val methodRefs = methods.mapIndexed { i, m -> m.descriptor to descriptor(type(m.owner), if (m.name == "<init>") m.name else "m$i", type(m.returnType), m.parameterTypes.map(::type)) }.toMap()
            val fieldRefs = fields.mapIndexed { i, f -> f.descriptor to field(type(f.owner), "f$i", type(f.type)).descriptor }.toMap()
            val movedMethods = methods.mapIndexed { i, m -> m.copy(descriptor = methodRefs.getValue(m.descriptor), owner = type(m.owner),
                name = if (m.name == "<init>") m.name else "m$i", returnType = type(m.returnType), parameterTypes = m.parameterTypes.map(::type),
                invokes = m.invokes.map { methodRefs[it] ?: it }, readFields = m.readFields.map { fieldRefs[it] ?: it },
                writtenFields = m.writtenFields.map { fieldRefs[it] ?: it }) }
            val movedFields = fields.mapIndexed { i, f -> f.copy(descriptor = fieldRefs.getValue(f.descriptor), owner = type(f.owner), name = "f$i", type = type(f.type)) }
            return copy(methods = movedMethods, fields = movedFields, classes = classes.map { c -> c.copy(name = type(c.name),
                superName = c.superName?.let(::type), methods = c.methods.map { methodRefs[it] ?: it }, fields = c.fields.map { fieldRefs[it] ?: it }) },
                numbers = numbers.mapKeys { methodRefs[it.key] ?: it.key })
        }
    }

    companion object {
        private const val CONTENT_RESOLVER = "Landroid/content/Context;->getContentResolver()Landroid/content/ContentResolver;"
        private const val CONTAINS_KEY = "Ljava/util/Map;->containsKey(Ljava/lang/Object;)Z"
        private const val MAP_PUT = "Ljava/util/Map;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"
        private const val LIST_ADD = "Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z"
        private const val INDEXED_ADD = "Ljava/util/List;->add(ILjava/lang/Object;)V"
        private const val LIST_SIZE = "Ljava/util/List;->size()I"
        private val LEVEL_NUMBERS = setOf(86_400_000L, 0L, 1L, 2L, 3L)
        private fun dex(type: String): String = when (type) {
            "void" -> "V"; "boolean" -> "Z"; "int" -> "I"; "long" -> "J"; else -> "L${type.replace('.', '/')};"
        }
        private fun descriptor(owner: String, name: String, result: String, params: List<String>): String =
            "${dex(owner)}->$name(${params.joinToString("") { dex(it) }})${dex(result)}"
        private fun method(owner: String, name: String, result: String, params: List<String> = emptyList(), static: Boolean = false,
                           strings: Set<String> = emptySet(), invokes: List<String> = emptyList(), reads: List<String> = emptyList()): InstallerMethod =
            InstallerMethod(descriptor(owner, name, result, params), owner, name, result, params, static, strings = strings, invokes = invokes, readFields = reads)
        private fun field(owner: String, name: String, type: String, annotations: Set<String> = emptySet()): InstallerField =
            InstallerField("${dex(owner)}->$name:${dex(type)}", owner, name, type, false, annotations)
    }
}
