package cn.ianzb.hyperrefine.hook.packageinstaller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallerGuardBannerRulesTest {
    @Test fun resolvesOnlyInformationFactoryAndPureStateAccess() {
        val f = fixture()
        val point = InstallerGuardBannerRules(f).resolve()
        assertEquals("guard_banner", point.id)
        assertEquals("information", point.method.name)
        val access = checkNotNull(point.guardBanner)
        assertEquals("fixture.Banner", access.modelClass)
        assertEquals("state", access.stateGetter.name)
        assertEquals(6, access.textGetters.size)
        assertEquals("kind", access.kindGetter.name)
        assertTrue((access.textGetters + access.stateGetter + access.kindGetter).all { it.invokes.isEmpty() && it.writtenFields.isEmpty() })
    }

    @Test fun renamedClassesMethodsAndAnnotatedFieldKeysStillResolve() {
        val original = fixture()
        val changed = original.renamed()
        val point = InstallerGuardBannerRules(changed).resolve()
        assertNotEquals(InstallerGuardBannerRules(original).resolve().method.descriptor, point.method.descriptor)
        assertTrue(checkNotNull(point.guardBanner).modelClass.startsWith("relocated."))
    }

    @Test fun modelNameAloneWithoutConstructionDoesNotIdentifyBanner() {
        val f = fixture()
        val changed = f.copy(methods = f.methods.map { if (it.name == "information") it.copy(invokes = emptyList()) else it })
        assertThrows(InstallerMatchException::class.java) { InstallerGuardBannerRules(changed).resolve() }
    }

    @Test fun absentCloudFieldsDoNotBlockUniqueStructuralFactory() {
        val f = fixture()
        val changed = f.copy(methods = f.methods.map { if (it.name == "information" || it.name == "choose") it.copy(readFields = emptyList()) else it },
            fields = f.fields.filterNot { it.owner == "fixture.Cloud" || it.owner == "fixture.CloudTip" })
        assertEquals("information", InstallerGuardBannerRules(changed).resolve().method.name)
    }

    @Test fun absentOptionalCooperationFieldDoesNotBlockFactory() {
        val f = fixture()
        val changed = f.copy(fields = f.fields.filterNot { it.name == "cooperationExemptRiskTip" })
        assertEquals("information", InstallerGuardBannerRules(changed).resolve().method.name)
    }

    @Test fun incompleteOptionalServerPayloadDoesNotBlockUniqueStructure() {
        val f = fixture()
        val changed = f.copy(fields = f.fields.filterNot { it.owner == "fixture.CloudTip" && it.name == "jumpText" })
        assertEquals("information", InstallerGuardBannerRules(changed).resolve().method.name)
    }

    @Test fun pureCloudGetterCanDisambiguateSeveralVerifiedFactories() {
        val f = fixture()
        val tip = f.fields.single { it.name == "securityTip" }
        val getter = method(tip.owner, "readTip", tip.type, reads = listOf(tip.descriptor), code = PURE)
        val constructor = f.methods.single { it.name == "<init>" }
        val changed = f.copy(methods = f.methods.map { when (it.name) {
            "information" -> it.copy(readFields = emptyList(), invokes = it.invokes + getter.descriptor)
            "risk" -> it.copy(invokes = listOf(constructor.descriptor), opcodes = listOf(0x12, 0x11))
            else -> it
        } }).addMethod(getter)
        assertEquals("information", InstallerGuardBannerRules(changed).resolve().method.name)
    }

    @Test fun ambiguousStructuresWithoutCloudHintFailWithRealStageCounts() {
        val f = fixture()
        val constructor = f.methods.single { it.name == "<init>" }
        val changed = f.copy(methods = f.methods.map { when (it.name) {
            "information" -> it.copy(readFields = emptyList())
            "risk" -> it.copy(invokes = listOf(constructor.descriptor), opcodes = listOf(0x12, 0x11))
            else -> it
        } })
        val error = assertThrows(InstallerMatchException::class.java) { InstallerGuardBannerRules(changed).resolve() }
        assertEquals(2, error.candidateCount)
        assertTrue(error.message!!.contains("construction=2"))
        assertTrue(error.message!!.contains("verified_models=2"))
        assertTrue(error.message!!.contains("structural=2 security_tip=0"))
    }

    @Test fun extraNonnullableFactoryConsumerPreventsNullReturningHook() {
        val f = fixture()
        val factory = f.methods.single { it.name == "information" }
        val unsafe = method(factory.owner, "unconditionalUse", "void", calls = listOf(factory.descriptor), code = listOf(0x0e))
        val error = assertThrows(InstallerMatchException::class.java) { InstallerGuardBannerRules(f.addMethod(unsafe)).resolve() }
        assertEquals(0, error.candidateCount)
        assertTrue(error.message!!.contains("consumers=2 nullable_consumers=1"))
    }

    @Test fun extraNonnullableOuterConsumerIsAlsoRejected() {
        val f = fixture()
        val consumer = f.methods.single { it.name == "choose" }
        val unsafe = method(consumer.owner, "unconditionalAppend", "void", calls = listOf(consumer.descriptor,
            "Ljava/util/List;->add(Ljava/lang/Object;)Z"), code = listOf(0x0e))
        assertThrows(InstallerMatchException::class.java) { InstallerGuardBannerRules(f.addMethod(unsafe)).resolve() }
    }

    @Test fun getterWithTargetCallsOrWritesIsRejected() {
        for (write in listOf(false, true)) {
            val f = fixture()
            val changed = f.copy(methods = f.methods.map { if (it.owner == "fixture.State" && it.name == "text0") {
                if (write) it.copy(writtenFields = it.readFields) else it.copy(invokes = listOf("Lfixture/Other;->run()V"))
            } else it })
            assertThrows(InstallerMatchException::class.java) { InstallerGuardBannerRules(changed).resolve() }
        }
    }

    @Test fun duplicateStateAccessorAndMissingEnumKindAreRejected() {
        val f = fixture()
        val getter = f.methods.single { it.owner == "fixture.Banner" && it.name == "state" }
        val duplicate = getter.copy(name = "anotherState", descriptor = descriptor(getter.owner, "anotherState", getter.returnType))
        assertThrows(InstallerMatchException::class.java) { InstallerGuardBannerRules(f.addMethod(duplicate)).resolve() }
        val notEnum = f.copy(classes = f.classes.map { if (it.name == "fixture.Kind") it.copy(superName = "java.lang.Object") else it })
        assertThrows(InstallerMatchException::class.java) { InstallerGuardBannerRules(notEnum).resolve() }
    }

    @Test fun incompleteOrExtraStateTextIsRejected() {
        val f = fixture()
        val missing = f.copy(methods = f.methods.filterNot { it.owner == "fixture.State" && it.name == "text5" })
        assertThrows(InstallerMatchException::class.java) { InstallerGuardBannerRules(missing).resolve() }
        val extra = field("fixture.State", "additionalRisk", "java.lang.String")
        val changed = f.copy(fields = f.fields + extra,
            classes = f.classes.map { if (it.name == "fixture.State") it.copy(fields = it.fields + extra.descriptor) else it })
        assertThrows(InstallerMatchException::class.java) { InstallerGuardBannerRules(changed).resolve() }
    }

    @Test fun absentNullableCallerOrListInsertionIsRejected() {
        for (name in listOf("choose", "build")) {
            val f = fixture()
            val changed = f.copy(methods = f.methods.map { if (it.name == name) it.copy(opcodes = emptyList()) else it })
            assertThrows(InstallerMatchException::class.java) { InstallerGuardBannerRules(changed).resolve() }
        }
    }

    @Test fun arbitraryAddCannotReplaceModelListConsumer() {
        val f = fixture()
        val changed = f.copy(methods = f.methods.map { if (it.name == "build") it.copy(invokes = it.invokes.map { call ->
            call.replace("Ljava/util/ArrayList;", "Lfixture/UnrelatedStore;")
        }) else it })
        assertThrows(InstallerMatchException::class.java) { InstallerGuardBannerRules(changed).resolve() }
    }

    @Test fun ambiguousInformationFactoriesDoNotChooseFirst() {
        val f = fixture()
        val factory = f.methods.single { it.name == "information" }
        val duplicate = factory.copy(name = "anotherInformation", descriptor = descriptor(factory.owner, "anotherInformation", factory.returnType))
        val changed = f.copy(methods = f.methods.map { if (it.name == "choose") it.copy(invokes = it.invokes + duplicate.descriptor) else it }).addMethod(duplicate)
        val error = assertThrows(InstallerMatchException::class.java) { InstallerGuardBannerRules(changed).resolve() }
        assertEquals(2, error.candidateCount)
    }

    @Test fun defaultBannerMatchesOnlyTwoNonemptyTextsAndKnownLinks() {
        assertTrue(matches(texts(TITLE, BODY)))
        assertTrue(matches(texts(TITLE, "$BODY $LINK")))
        assertTrue(matches(texts(TITLE, "$BODY 了解 应用安装防护")))
        assertTrue(matches(listOf(null, BODY, "", TITLE, null, " ")))
    }

    @Test fun dangerAndUnknownServerTextArePreserved() {
        assertFalse(matches(texts(TITLE, BODY), kind = "DANGER"))
        assertFalse(matches(texts("检测到高风险应用", BODY)))
        assertFalse(matches(texts(TITLE, "$BODY 检测到其他风险")))
        assertFalse(matches(texts(TITLE, "$BODY 未知服务器链接")))
        assertFalse(matches(listOf(TITLE, BODY, "附加确认", null, null, null)))
    }

    @Test fun bottomConfirmationTextIsNeverDefaultInformationBody() {
        assertFalse(matches(texts(TITLE, "已了解此安装包未经安全检测，可能存在风险")))
        assertFalse(matches(listOf(TITLE, BODY, "已了解此安装包未经安全检测，可能存在风险", null, null, null)))
    }

    @Test fun policyUsesSuppliedLocaleResourcesWithoutChineseFallback() {
        val title = "Safety reminder"
        val body = "This app has not been reviewed. Get apps from the store."
        val link = "Learn about protection"
        assertTrue(InstallerGuardBannerPolicy.matches("WARNING", texts(title, "$body $link"), title, body, setOf(link)))
        assertFalse(InstallerGuardBannerPolicy.matches("WARNING", texts(TITLE, BODY), title, body, setOf(link)))
    }

    @Test fun onlyFormattingWhitespaceAndProvenZeroWidthSpaceAreIgnored() {
        assertTrue(matches(texts(" 安全提醒\n", "$BODY\u200b 了解 安全守护")))
        assertFalse(matches(texts(TITLE, BODY.replace("。", "!"))))
        assertFalse(matches(texts(TITLE, "$BODY\u2060$LINK")))
    }

    @Test fun absentResourcesWrongStateShapeAndOversizeValuesArePreserved() {
        assertFalse(InstallerGuardBannerPolicy.matches("WARNING", texts(TITLE, BODY), "", BODY, setOf(LINK)))
        assertFalse(InstallerGuardBannerPolicy.matches("WARNING", texts(TITLE, BODY), TITLE, "", setOf(LINK)))
        assertFalse(matches(listOf(TITLE, BODY)))
        assertFalse(matches(texts(TITLE, "x".repeat(4_097))))
        assertFalse(InstallerGuardBannerPolicy.matches("WARNING", texts(TITLE, BODY), TITLE, BODY, setOf("x".repeat(513))))
    }

    private fun matches(values: List<CharSequence?>, kind: String = "WARNING"): Boolean =
        InstallerGuardBannerPolicy.matches(kind, values, TITLE, BODY, setOf(LINK, "了解应用安装防护"))

    private fun texts(title: String, body: String): List<CharSequence?> = listOf(title, body, null, null, null, null)

    private fun fixture(): Index {
        val cloud = "fixture.Cloud"
        val payload = "fixture.CloudTip"
        val model = "fixture.Banner"
        val state = "fixture.State"
        val kind = "fixture.Kind"
        val base = "fixture.Model"
        val owner = "fixture.Factory"
        val exemption = field(cloud, "cooperationExemptRiskTip", "java.lang.Integer")
        val tip = field(cloud, "securityTip", payload)
        val system = field(cloud, "useSystemAppRules", "java.lang.Boolean")
        val payloadFields = listOf("title", "text", "jumpText").map { field(payload, it, "java.lang.String") }
        val textFields = (0..5).map { field(state, "value$it", "java.lang.CharSequence") }
        val kindField = field(state, "mode", kind)
        val stateField = field(model, "data", state)
        val stateGetter = method(model, "state", state, reads = listOf(stateField.descriptor), code = PURE)
        val textGetters = textFields.mapIndexed { i, f -> method(state, "text$i", f.type, reads = listOf(f.descriptor), code = PURE) }
        val kindGetter = method(state, "kind", kind, reads = listOf(kindField.descriptor), code = PURE)
        val formatter = method(state, "describe", "java.lang.String", strings = LABELS, reads = textFields.map { it.descriptor } + kindField.descriptor)
        val constructor = method(model, "<init>", "void", listOf("android.content.Context", kind) + List(6) { "java.lang.CharSequence" })
        val factory = method(owner, "information", model, calls = listOf(constructor.descriptor), reads = listOf(exemption.descriptor, tip.descriptor), code = listOf(0x12, 0x11))
        val risk = method(owner, "risk", model)
        val source = method(owner, "sourceAuthorization", model)
        val consumer = method(owner, "choose", base, listOf(cloud, "fixture.Apk"),
            calls = listOf(risk.descriptor, source.descriptor, factory.descriptor), reads = listOf(system.descriptor), code = listOf(0x38, 0x11))
        val outer = method(owner, "build", "void", calls = listOf(consumer.descriptor, "Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z"), code = listOf(0x38, 0x0e))
        val methods = listOf(stateGetter, kindGetter, formatter, constructor, factory, risk, source, consumer, outer) + textGetters
        val fields = listOf(exemption, tip, system, stateField, kindField) + payloadFields + textFields
        val names = (methods.map { it.owner } + fields.map { it.owner } + base + kind).distinct()
        val classes = names.map { name -> InstallerClass(name, when (name) { model -> base; kind -> "java.lang.Enum"; else -> "java.lang.Object" },
            methods = methods.filter { it.owner == name }.map { it.descriptor }, fields = fields.filter { it.owner == name }.map { it.descriptor }) }
        return Index(methods, fields, classes)
    }

    private data class Index(val methods: List<InstallerMethod>, val fields: List<InstallerField>, val classes: List<InstallerClass>) : InstallerDexIndex {
        override fun findMethods(query: InstallerMethodQuery): List<InstallerMethod> = methods.filter {
            it.strings.containsAll(query.strings) && query.numbers.isEmpty() && (query.owner == null || query.owner == it.owner) &&
                (query.returnType == null || query.returnType == it.returnType) && (query.isStatic == null || query.isStatic == it.isStatic) &&
                (query.parameterCount == null || query.parameterCount == it.parameterTypes.size) &&
                (query.parameterTypes == null || query.parameterTypes.size == it.parameterTypes.size && query.parameterTypes.zip(it.parameterTypes).all { (wanted, actual) -> wanted == null || wanted == actual })
        }
        override fun method(descriptor: String): InstallerMethod? = methods.find { it.descriptor == descriptor }
        override fun field(descriptor: String): InstallerField? = fields.find { it.descriptor == descriptor }
        override fun clazz(name: String): InstallerClass? = classes.find { it.name == name }
        override fun callers(descriptor: String): List<InstallerMethod> = methods.filter { descriptor in it.invokes }
        fun addMethod(method: InstallerMethod): Index = copy(methods = methods + method,
            classes = classes.map { if (it.name == method.owner) it.copy(methods = it.methods + method.descriptor) else it })
        fun renamed(): Index {
            val types = classes.mapIndexed { i, c -> c.name to "relocated.Type$i" }.toMap()
            fun type(name: String) = types[name] ?: name
            val methodRefs = methods.mapIndexed { i, m -> m.descriptor to descriptor(type(m.owner), if (m.name == "<init>") m.name else "method$i", type(m.returnType), m.parameterTypes.map(::type)) }.toMap()
            val fieldRefs = fields.mapIndexed { i, f -> f.descriptor to field(type(f.owner), "field$i", type(f.type)).descriptor }.toMap()
            val changedMethods = methods.mapIndexed { i, m -> m.copy(descriptor = methodRefs.getValue(m.descriptor), owner = type(m.owner),
                name = if (m.name == "<init>") m.name else "method$i", returnType = type(m.returnType), parameterTypes = m.parameterTypes.map(::type),
                invokes = m.invokes.map { methodRefs[it] ?: it }, readFields = m.readFields.map { fieldRefs[it] ?: it }, writtenFields = m.writtenFields.map { fieldRefs[it] ?: it }) }
            val changedFields = fields.mapIndexed { i, f -> f.copy(descriptor = fieldRefs.getValue(f.descriptor), owner = type(f.owner), name = "field$i", type = type(f.type), annotationStrings = f.annotationStrings + f.name) }
            return Index(changedMethods, changedFields, classes.map { c -> c.copy(name = type(c.name), superName = c.superName?.let(::type),
                methods = c.methods.map { methodRefs[it] ?: it }, fields = c.fields.map { fieldRefs[it] ?: it }) })
        }
    }

    private companion object {
        private const val TITLE = "安全提醒"
        private const val BODY = "该应用未经小米安全审核，建议优先使用应用商店下载。"
        private const val LINK = "了解安全守护"
        private val PURE = listOf(0x54, 0x11)
        private val LABELS = setOf("Data(type=", ", title1=", ", content1=", ", title2=", ", content2=", ", content2extra=", ", content3=")
        private fun dex(type: String): String = when (type) { "void" -> "V"; else -> "L${type.replace('.', '/')};" }
        private fun descriptor(owner: String, name: String, result: String, params: List<String> = emptyList()): String = "${dex(owner)}->$name(${params.joinToString("") { dex(it) }})${dex(result)}"
        private fun method(owner: String, name: String, result: String, params: List<String> = emptyList(), strings: Set<String> = emptySet(),
                           calls: List<String> = emptyList(), reads: List<String> = emptyList(), code: List<Int> = emptyList()): InstallerMethod =
            InstallerMethod(descriptor(owner, name, result, params), owner, name, result, params, false, strings = strings, invokes = calls, readFields = reads, opcodes = code)
        private fun field(owner: String, name: String, type: String): InstallerField = InstallerField("${dex(owner)}->$name:${dex(type)}", owner, name, type, false)
    }
}
