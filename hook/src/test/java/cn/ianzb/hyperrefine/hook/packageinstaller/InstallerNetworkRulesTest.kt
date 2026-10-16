package cn.ianzb.hyperrefine.hook.packageinstaller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic role graphs contain no ROM code; real-ROM discovery is verified separately. */
class InstallerNetworkRulesTest {
    private val ids = listOf("layout_async", "layout_sync", "intercept_check", "avl_upload", "cloud_v2", "cloud_v3")

    @Test fun allNetworkRolesResolveFromIndependentBusinessEvidence() {
        val graph = fixture()
        ids.forEach { assertEquals(it, InstallerNetworkRules(graph).resolve(it).id) }
        val layout = InstallerNetworkRules(graph).resolve("layout_async")
        assertEquals("sample.Ad", layout.payloadClass)
        assertEquals("void", layout.callback?.returnType)
        val cloud = InstallerNetworkRules(graph).resolve("intercept_check")
        assertEquals("sample.CloudPayload", cloud.payloadClass)
        assertEquals("sample.Success", cloud.resultClass)
        assertEquals("sample.CloudResult", cloud.resultBaseClass)
        assertEquals("sample.UnitValue", InstallerNetworkRules(graph).resolve("cloud_v3").singletonField?.type)
    }

    @Test fun renamingEveryAppClassAndNonProtocolMethodStillResolvesAllRoles() {
        val renamed = fixture().renamed()
        ids.forEach { id ->
            val point = InstallerNetworkRules(renamed).resolve(id)
            assertEquals(id, point.id)
            assertTrue(point.method.owner.startsWith("relocated."))
            if (id == "intercept_check") assertTrue(point.payloadClass!!.startsWith("relocated."))
        }
    }

    @Test fun missingV3EndpointOnlyRejectsThatRole() {
        val graph = fixture()
        graph.replaceNamed("apiCloudV3") { it.copy(annotationStrings = setOf("/unrelated/query")) }
        assertThrows(InstallerMatchException::class.java) { InstallerNetworkRules(graph).resolve("cloud_v3") }
        ids.filterNot { it == "cloud_v3" }.forEach { InstallerNetworkRules(graph).resolve(it) }
    }

    @Test fun duplicatedBusinessCandidateIsRejectedRatherThanFirstSelected() {
        val graph = fixture()
        val original = graph.methods.values.single { it.name == "intercept" }
        graph.add(original.copy(name = "ambiguous", descriptor = original.descriptor.replace("->intercept(", "->ambiguous(")))
        val failure = assertThrows(InstallerMatchException::class.java) { InstallerNetworkRules(graph).resolve("intercept_check") }
        assertEquals(2, failure.candidateCount)
        InstallerNetworkRules(graph).resolve("cloud_v2")
    }

    @Test fun typedSuccessCannotBeAcceptedWithoutEmptyCloudPayloadConstructor() {
        val graph = fixture()
        graph.methods.entries.removeAll { it.value.owner == "sample.CloudPayload" && it.value.name == "<init>" }
        assertThrows(InstallerMatchException::class.java) { InstallerNetworkRules(graph).resolve("intercept_check") }
        InstallerNetworkRules(graph).resolve("avl_upload")
    }

    @Test fun missingNullableCallbackDoesNotDisableIndependentSyncContract() {
        val graph = fixture()
        graph.methods.values.filter { it.name == "completeEmpty" }.forEach { graph.numbers.remove(it.descriptor) }
        assertThrows(InstallerMatchException::class.java) { InstallerNetworkRules(graph).resolve("layout_async") }
        InstallerNetworkRules(graph).resolve("layout_sync")
        InstallerNetworkRules(graph).resolve("cloud_v3")
    }

    @Test fun layoutLogsAndOptionalMapFieldsAreNotCompatibilityGates() {
        val graph = fixture()
        graph.replaceNamed("loadSync") { it.copy(strings = setOf("different release diagnostics")) }
        graph.replaceNamed("builder") { it.copy(strings = it.strings - setOf("androidSdkVersion", "excludeAppIds", "screen_size")) }
        InstallerNetworkRules(graph).resolve("layout_sync")
        InstallerNetworkRules(graph).resolve("layout_async")
    }

    @Test fun primitiveInstallArgumentAndShorterRequestProfileAreSupported() {
        val graph = fixture(installType = "int", shorterRequest = true)
        assertEquals(6, InstallerNetworkRules(graph).resolve("layout_async").method.parameterTypes.size)
        InstallerNetworkRules(graph).resolve("layout_sync")
    }

    @Test fun asyncStillResolvesWhenSyncMethodDoesNotExist() {
        val graph = fixture()
        graph.methods.entries.removeAll { it.value.name == "loadSync" }
        InstallerNetworkRules(graph).resolve("layout_async")
        val error = assertThrows(InstallerMatchException::class.java) { InstallerNetworkRules(graph).resolve("layout_sync") }
        assertTrue(error.message!!.contains("stages="))
        assertTrue(error.message!!.contains("signature:0"))
    }

    @Test fun objectConstructorAndGetterDoNotImpersonateContinuation() {
        val graph = fixture()
        graph.methods.entries.removeAll { it.value.owner == "sample.Continuation" && it.value.name == "resumeWith" }
        graph.add(method("sample.Continuation", "<init>", "void", listOf("java.lang.Object")))
        val error = assertThrows(InstallerMatchException::class.java) { InstallerNetworkRules(graph).resolve("layout_sync") }
        assertTrue(error.message!!.contains("signature:0"))
        InstallerNetworkRules(graph).resolve("layout_async")
    }

    @Test fun syncStillResolvesWhenAsyncEntryDoesNotExist() {
        val graph = fixture()
        graph.methods.entries.removeAll { it.value.name in setOf("load", "worker", "run") }
        InstallerNetworkRules(graph).resolve("layout_sync")
        assertThrows(InstallerMatchException::class.java) { InstallerNetworkRules(graph).resolve("layout_async") }
    }

    @Test fun inlinedMapAndOneEndpointWrapperPreserveSyncSemantics() {
        val graph = fixture()
        val api = graph.methods.values.single { it.name == "apiLayout" }
        val wrapper = method("sample.LayoutApiWrapper", "request", "java.lang.Object", api.parameterTypes, calls = listOf(api.descriptor))
        graph.add(wrapper)
        val builder = graph.methods.values.single { it.name == "builder" }
        graph.replaceNamed("loadSync") { it.copy(strings = builder.strings,
            invokes = (it.invokes - builder.descriptor - api.descriptor) + wrapper.descriptor + MAP_PUT) }
        InstallerNetworkRules(graph).resolve("layout_sync")
    }

    @Test fun nullableHelperToleratesOnlyHarmlessMovesAndControlEncoding() {
        val graph = fixture()
        graph.replaceNamed("completeEmpty") { it.copy(opcodes = listOf(0x00, 0x39, 0x0e, 0x07, 0x13, 0x08, 0x78, 0x0e)) }
        InstallerNetworkRules(graph).resolve("layout_async")
        graph.replaceNamed("completeEmpty") { it.copy(writtenFields = listOf("Lsample/State;->dirty:Z")) }
        val error = assertThrows(InstallerMatchException::class.java) { InstallerNetworkRules(graph).resolve("layout_async") }
        assertTrue(error.message!!.contains("nullable:0"))
        InstallerNetworkRules(graph).resolve("layout_sync")
    }

    @Test fun nullableHelperWithAllocationOrAnotherCallIsRejected() {
        val graph = fixture()
        graph.replaceNamed("completeEmpty") { it.copy(opcodes = listOf(0x22, 0x12, 0x72, 0x0e)) }
        assertThrows(InstallerMatchException::class.java) { InstallerNetworkRules(graph).resolve("layout_async") }
        graph.replaceNamed("completeEmpty") { it.copy(opcodes = listOf(0x12, 0x72, 0x0e), invokes = it.invokes + "Lsample/Other;->produce()Ljava/lang/Object;") }
        assertThrows(InstallerMatchException::class.java) { InstallerNetworkRules(graph).resolve("layout_async") }
    }

    @Test fun endpointWithoutLayoutResponseModelIsRejectedWithStageCounts() {
        val graph = fixture()
        val old = graph.fields.values.single { it.name == "adData" }
        graph.fields[old.descriptor] = old.copy(type = "sample.Unrelated")
        for (id in listOf("layout_sync", "layout_async")) {
            val error = assertThrows(InstallerMatchException::class.java) { InstallerNetworkRules(graph).resolve(id) }
            assertTrue(error.message!!.contains("endpoint_roots:"))
            assertTrue(error.message!!.contains("model:0"))
        }
    }

    @Test fun inlinedResponseFieldReadsDoNotNeedATypedV2Mapper() {
        val graph = fixture()
        inlineLayoutResponse(graph, getter = false)
        for (id in listOf("layout_sync", "layout_async")) {
            val point = InstallerNetworkRules(graph).resolve(id)
            assertEquals("sample.Ad", point.payloadClass)
            assertTrue(point.evidence.any { it.contains("field_reads:1") })
        }
        InstallerNetworkRules(graph.renamed()).resolve("layout_async")
        InstallerNetworkRules(graph.renamed()).resolve("layout_sync")
    }

    @Test fun directResponseDataGetterDoesNotNeedAdDataFieldOrMapper() {
        val graph = fixture()
        inlineLayoutResponse(graph, getter = true)
        for (id in listOf("layout_sync", "layout_async")) {
            val point = InstallerNetworkRules(graph).resolve(id)
            assertEquals("sample.Ad", point.payloadClass)
            assertTrue(point.evidence.any { it.contains("getters:1") })
        }
    }

    @Test fun serializedResponseFieldIdentitySurvivesFieldRenaming() {
        val graph = fixture()
        inlineLayoutResponse(graph, getter = false)
        val data = graph.fields.values.single { it.owner == "sample.Response" }
        graph.fields[data.descriptor] = data.copy(name = "obfuscated", annotationStrings = setOf("data"))
        InstallerNetworkRules(graph).resolve("layout_async")
        InstallerNetworkRules(graph).resolve("layout_sync")
    }

    @Test fun directPayloadInExactApiTypeTokensDoesNotNeedAnyResponseWrapper() {
        for (signature in listOf("Lsample/Call<Lsample/Ad;>;", "Lsample/Response<Lsample/Ad;>;")) {
            val graph = fixture()
            directApiPayload(graph, signature)
            for (id in listOf("layout_sync", "layout_async")) {
                val point = InstallerNetworkRules(graph).resolve(id)
                assertEquals("sample.Ad", point.payloadClass)
                assertTrue(point.evidence.any { it.contains("api_types:1") })
            }
        }
    }

    @Test fun exactApiPayloadTypeStillResolvesAfterClassRelocation() {
        val graph = fixture()
        directApiPayload(graph, "Lsample/Call<Lsample/Ad;>;")
        val renamed = graph.renamed()
        val payload = renamed.fields.values.single { "channel" in it.annotationStrings }.owner
        for (name in listOf("apiLayout", "apiLayoutAsync")) {
            val original = graph.methods.values.single { it.name == name }
            val renamedApi = renamed.methods.values.single { it.parameterTypes.size == original.parameterTypes.size &&
                "/info/layout" in it.annotationStrings }
            renamed.methods[renamedApi.descriptor] = renamedApi.copy(annotationStrings = setOf("/info/layout", "Lsample/Call<${dexType(payload)}>;"))
        }
        InstallerNetworkRules(renamed).resolve("layout_async")
        InstallerNetworkRules(renamed).resolve("layout_sync")
    }

    @Test fun arbitraryObjectApiWithoutTypedResponseEvidenceIsRejected() {
        val graph = fixture()
        directApiPayload(graph, "Ljava/lang/Object;")
        for (id in listOf("layout_sync", "layout_async")) {
            val rules = InstallerNetworkRules(graph)
            val failure = assertThrows(InstallerMatchException::class.java) { rules.resolve(id) }
            assertTrue(failure.message!!.contains("empty_model:"))
            assertTrue(failure.message!!.contains("model:0"))
        }
    }

    @Test fun arraysIncompleteTokensAndSimilarClassNamesAreNotPayloadTypeMatches() {
        for (signature in listOf("[Lsample/Ad;", "Lsample/Ad", "Lsample/AdExtra;", "Lsample//Ad;", "notLsample/Ad;")) {
            val graph = fixture()
            directApiPayload(graph, signature)
            for (id in listOf("layout_sync", "layout_async")) {
                assertThrows(InstallerMatchException::class.java) { InstallerNetworkRules(graph).resolve(id) }
            }
        }
    }

    @Test fun annotationFragmentsAreNotReassembledToInventAType() {
        val graph = fixture()
        directApiPayload(graph, "Lsample/Ad")
        for (name in listOf("apiLayout", "apiLayoutAsync")) graph.replaceNamed(name) {
            it.copy(annotationStrings = it.annotationStrings + setOf(";", "Lsample/", "Ad;"))
        }
        for (id in listOf("layout_sync", "layout_async")) {
            assertThrows(InstallerMatchException::class.java) { InstallerNetworkRules(graph).resolve(id) }
        }
    }

    @Test fun twoSchemaCompatiblePayloadTypesAreAmbiguousEvenIfOneMatchesCallback() {
        val graph = fixture()
        directApiPayload(graph, "Lsample/Call<Lsample/Ad;Lsample/OtherAd;>;")
        addOtherPayload(graph)
        for (id in listOf("layout_sync", "layout_async")) {
            val failure = assertThrows(InstallerMatchException::class.java) { InstallerNetworkRules(graph).resolve(id) }
            assertTrue(failure.message!!.contains("multi_model:"))
            assertTrue(failure.message!!.contains("payload_types:2"))
        }
    }

    @Test fun exactApiTypeMustAgreeWithTheTypedCallback() {
        val graph = fixture()
        directApiPayload(graph, "Lsample/OtherAd;")
        addOtherPayload(graph)
        val rules = InstallerNetworkRules(graph)
        val failure = assertThrows(InstallerMatchException::class.java) { rules.resolve("layout_async") }
        assertTrue(failure.message!!.contains("type_mismatch:"))
        assertTrue(rules.diagnosticLines().any { "Lsample/OtherAd;" in it && "Lsample/Callback;->deliver(Lsample/Ad;)V" in it })
        // A separately typed sync response is valid; no substring matching coerces it to sample.Ad.
        assertEquals("sample.OtherAd", InstallerNetworkRules(graph).resolve("layout_sync").payloadClass)
    }

    @Test fun schemaFieldTypesAndCoreMapRemainMandatoryForDirectPayload() {
        for (missing in listOf("data", "channel", "layoutFormat", "request")) {
            val graph = fixture()
            directApiPayload(graph, "Lsample/Ad;")
            if (missing == "request") graph.replaceNamed("builder") { it.copy(strings = it.strings - "packageName") }
            else {
                val field = graph.fields.values.single { it.owner == "sample.Ad" && it.name == missing }
                graph.fields[field.descriptor] = field.copy(type = "java.lang.Object")
            }
            for (id in listOf("layout_sync", "layout_async")) {
                assertThrows(InstallerMatchException::class.java) { InstallerNetworkRules(graph).resolve(id) }
            }
        }
    }

    @Test fun typedGetterWithoutItsFieldReadDoesNotInventAResponse() {
        val graph = fixture()
        inlineLayoutResponse(graph, getter = true)
        graph.replaceNamed("responseValue") { it.copy(readFields = emptyList()) }
        for (id in listOf("layout_sync", "layout_async")) {
            assertThrows(InstallerMatchException::class.java) { InstallerNetworkRules(graph).resolve(id) }
        }
    }

    @Test fun unrelatedFieldNameDoesNotEstablishAResponseDataLink() {
        val graph = fixture()
        inlineLayoutResponse(graph, getter = false)
        val data = graph.fields.values.single { it.owner == "sample.Response" }
        graph.fields[data.descriptor] = data.copy(name = "unrelated")
        for (id in listOf("layout_sync", "layout_async")) {
            assertThrows(InstallerMatchException::class.java) { InstallerNetworkRules(graph).resolve(id) }
        }
    }

    @Test fun directSyncStillRequiresANarrowEmptyReturnAndAsyncItsNullHelper() {
        val graph = fixture()
        directApiPayload(graph, "Lsample/Ad;")
        graph.replaceNamed("loadSync") { it.copy(opcodes = listOf(0x12, 0x6e, 0x11)) }
        val failure = assertThrows(InstallerMatchException::class.java) { InstallerNetworkRules(graph).resolve("layout_sync") }
        assertTrue(failure.message!!.contains("nullable:0"))
        InstallerNetworkRules(graph).resolve("layout_async")
        graph.methods.values.filter { it.name == "completeEmpty" }.forEach { graph.numbers.remove(it.descriptor) }
        assertThrows(InstallerMatchException::class.java) { InstallerNetworkRules(graph).resolve("layout_async") }
    }

    @Test fun operandProofOnlySupplementsTheSelectedTypedSyncBoundary() {
        val graph = fixture()
        directApiPayload(graph, "Lsample/Ad;")
        graph.replaceNamed("loadSync") { it.copy(opcodes = listOf(0x12, 0x6e, 0x11)) }
        assertThrows(InstallerMatchException::class.java) { InstallerNetworkRules(graph).resolve("layout_sync") }
        var calls = 0
        val rules = InstallerNetworkRules(graph) { method -> calls++; method.name == "loadSync" }
        val point = rules.resolve("layout_sync")
        assertEquals("sample.Ad", point.payloadClass)
        assertTrue(point.evidence.any { "operand_null:1" in it })
        assertEquals(1, calls)
        rules.resolve("layout_async")
        assertEquals(1, calls)
    }

    @Test fun proofFailuresRetainTheSyncRejectionAndCannotBypassMissingBusinessModel() {
        val graph = fixture()
        directApiPayload(graph, "Lsample/Ad;")
        graph.replaceNamed("loadSync") { it.copy(opcodes = listOf(0x12, 0x6e, 0x11)) }
        val throwing = InstallerNetworkRules(graph) { throw LinkageError("fixture") }
        assertThrows(InstallerMatchException::class.java) { throwing.resolve("layout_sync") }
        throwing.resolve("layout_async")
        for (name in listOf("apiLayout", "apiLayoutAsync")) graph.replaceNamed(name) {
            it.copy(annotationStrings = setOf("/info/layout", "Ljava/lang/Object;"))
        }
        var calls = 0
        assertThrows(InstallerMatchException::class.java) {
            InstallerNetworkRules(graph) { calls++; true }.resolve("layout_sync")
        }
        assertEquals(0, calls)
    }

    @Test fun existingMetadataContractDoesNotReadTheApkAndJavaOneArgConstructorRemains() {
        var calls = 0
        InstallerNetworkRules(fixture()) { calls++; throw AssertionError("must not be read") }.resolve("layout_sync")
        assertEquals(0, calls)
        assertTrue(InstallerNetworkRules::class.java.declaredConstructors.any {
            it.parameterTypes.contentEquals(arrayOf(InstallerDexIndex::class.java))
        })
    }

    @Test fun failedLayoutDiagnosticsAreBoundedAndClearedAfterSuccessfulRetry() {
        val graph = fixture()
        directApiPayload(graph, "Ljava/lang/Object;")
        val rules = InstallerNetworkRules(graph)
        for (id in listOf("layout_async", "layout_sync")) {
            assertThrows(InstallerMatchException::class.java) { rules.resolve(id) }
        }
        assertEquals(4, rules.diagnosticLines().size)
        assertTrue(rules.diagnosticLines().all { it.length <= 384 && it.startsWith("probe layout_") && "http" !in it })
        val restored = fixture()
        val retry = InstallerNetworkRules(restored)
        val nullHelper = restored.methods.values.single { it.name == "completeEmpty" }
        restored.numbers.remove(nullHelper.descriptor)
        assertThrows(InstallerMatchException::class.java) { retry.resolve("layout_async") }
        restored.numbers[nullHelper.descriptor] = setOf(0L)
        retry.resolve("layout_async")
        assertTrue(retry.diagnosticLines().isEmpty())
    }

    private fun removeLayoutWrapper(graph: Graph) {
        val mapper = graph.methods.values.single { it.name == "mapper" }
        graph.methods.values.toList().forEach { method ->
            graph.methods[method.descriptor] = method.copy(invokes = method.invokes - mapper.descriptor)
        }
        graph.methods.entries.removeAll { it.value.name == "mapper" || it.value.owner == "sample.LayoutData" }
        graph.fields.entries.removeAll { it.value.owner == "sample.LayoutData" }
        graph.classes.remove("sample.LayoutData")
    }

    private fun inlineLayoutResponse(graph: Graph, getter: Boolean) {
        removeLayoutWrapper(graph)
        val responseField = field("sample.Response", "data", "sample.Ad"); graph.add(responseField)
        val reader = method("sample.Response", "responseValue", "sample.Ad", reads = listOf(responseField.descriptor),
            opcodes = listOf(0x54, 0x11))
        if (getter) graph.add(reader)
        for (name in listOf("loadSync", "worker")) graph.replaceNamed(name) {
            if (getter) it.copy(invokes = it.invokes + reader.descriptor)
            else it.copy(readFields = it.readFields + responseField.descriptor)
        }
    }

    private fun directApiPayload(graph: Graph, signature: String) {
        removeLayoutWrapper(graph)
        for (name in listOf("apiLayout", "apiLayoutAsync")) graph.replaceNamed(name) {
            it.copy(annotationStrings = setOf("/info/layout", signature))
        }
    }

    private fun addOtherPayload(graph: Graph) {
        addPayloadSchema(graph, "sample.OtherAd")
    }

    private fun addPayloadSchema(graph: Graph, owner: String) {
        graph.add(field(owner, "data", "java.util.List"))
        graph.add(field(owner, "channel", "java.lang.String"))
        graph.add(field(owner, "layoutFormat", "java.lang.String"))
    }

    @Test fun duplicateSyncOrAsyncEntryIsRejectedIndependently() {
        for ((name, id) in listOf("loadSync" to "layout_sync", "load" to "layout_async")) {
            val graph = fixture()
            val old = graph.methods.values.single { it.name == name }
            val duplicate = old.copy(name = "ambiguousEntry", descriptor = descriptor(old.owner, "ambiguousEntry", old.returnType, old.parameterTypes))
            graph.add(duplicate)
            graph.numbers[duplicate.descriptor] = graph.numbers[old.descriptor].orEmpty()
            val error = assertThrows(InstallerMatchException::class.java) { InstallerNetworkRules(graph).resolve(id) }
            assertEquals(2, error.candidateCount)
        }
    }

    @Test fun libraryLeafFanoutCannotCrowdOutAnAlreadyVisibleLayoutApi() {
        val graph = fixture()
        val noise = (0 until 220).map { method("sample.Library", "leaf$it", "void").also(graph::add).descriptor }
        graph.replaceNamed("loadSync") { it.copy(invokes = noise + it.invokes) }
        graph.replaceNamed("worker") { it.copy(invokes = noise + it.invokes) }
        for (id in listOf("layout_sync", "layout_async")) {
            val match = InstallerNetworkRules(graph).resolve(id)
            assertTrue(match.evidence.any { it.contains("path_methods:") })
        }
    }

    @Test fun inheritedInterfaceReferencesUseTheirUniqueDeclaredMethod() {
        val graph = fixture()
        redirectLayoutApis(graph, declaresOverride = false)
        InstallerNetworkRules(graph).resolve("layout_sync")
        InstallerNetworkRules(graph).resolve("layout_async")
        // Class and member relocation must not turn interface resolution into name matching.
        InstallerNetworkRules(graph.renamed()).resolve("layout_sync")
        InstallerNetworkRules(graph.renamed()).resolve("layout_async")
    }

    @Test fun realOverrideWithoutRouteDoesNotBorrowParentAnnotation() {
        val graph = fixture()
        redirectLayoutApis(graph, declaresOverride = true)
        for (id in listOf("layout_sync", "layout_async")) {
            val failure = assertThrows(InstallerMatchException::class.java) { InstallerNetworkRules(graph).resolve(id) }
            assertTrue(failure.message!!.contains("path_methods:0"))
        }
    }

    @Test fun ambiguousParentDeclarationsDoNotSelectTheOneWithDesiredRoute() {
        val graph = fixture()
        redirectLayoutApis(graph, declaresOverride = false, ambiguousParent = true)
        repeat(2) {
            for (id in listOf("layout_sync", "layout_async")) {
                val failure = assertThrows(InstallerMatchException::class.java) { InstallerNetworkRules(graph).resolve(id) }
                assertTrue(failure.message!!.contains("ambiguous_refs:"))
            }
            // Even a backend that hands back one annotated parent's DTO must not hide known ambiguity.
            if (it == 0) graph.methods.entries.filter { it.key.startsWith("Lsample/InheritedApi") }.toList().forEach { entry ->
                val reference = entry.value
                val parent = graph.classes.getValue(reference.owner).interfaces.first()
                graph.methods[entry.key] = graph.methods.values.first { method -> method.owner == parent &&
                    method.name == reference.name && method.parameterTypes == reference.parameterTypes }
            }
        }
    }

    @Test fun layoutRouteFamilyStillRequiresCompleteRequestAndReturnPolicies() {
        for (route in listOf("info/layout/v2", "/info/layout", "https://layout.example.invalid/info/layout/v9")) {
            val graph = fixture()
            graph.replaceNamed("apiLayout") { it.copy(annotationStrings = setOf(route)) }
            graph.replaceNamed("apiLayoutAsync") { it.copy(annotationStrings = setOf(route)) }
            InstallerNetworkRules(graph).resolve("layout_sync")
            InstallerNetworkRules(graph).resolve("layout_async")
            val ad = graph.fields.values.single { it.name == "adData" }
            graph.fields[ad.descriptor] = ad.copy(type = "sample.Unrelated")
            for (id in listOf("layout_sync", "layout_async")) {
                val failure = assertThrows(InstallerMatchException::class.java) { InstallerNetworkRules(graph).resolve(id) }
                assertTrue(failure.message!!.contains("model:0"))
            }
        }
    }

    @Test fun similarStatisticsOrReportingPathsAreNotLayoutApis() {
        for (route in listOf("/info/layouts", "/info/layout/stat", "/info/layout/report", "/info/layout/v0",
            "/statistics/report", "file:/info/layout/v2", "/other/info/layout/v2")) {
            val graph = fixture()
            graph.replaceNamed("apiLayout") { it.copy(annotationStrings = setOf(route)) }
            graph.replaceNamed("apiLayoutAsync") { it.copy(annotationStrings = setOf(route)) }
            for (id in listOf("layout_sync", "layout_async")) {
                val failure = assertThrows(InstallerMatchException::class.java) { InstallerNetworkRules(graph).resolve(id) }
                assertTrue(failure.message!!.contains("endpoint_roots:0"))
            }
        }
    }

    @Test fun boundedExpansionReportsTruncationWhileKeepingDirectFrontierEvidence() {
        val graph = fixture()
        val leaf = method("sample.Library", "end", "void"); graph.add(leaf)
        val branches = (0 until 220).map {
            method("sample.Library", "branch$it", "void", calls = listOf(leaf.descriptor)).also(graph::add).descriptor
        }
        graph.replaceNamed("loadSync") { it.copy(invokes = branches + it.invokes) }
        val sync = InstallerNetworkRules(graph).resolve("layout_sync")
        assertTrue(sync.evidence.any { it.contains("truncated:") && !it.contains("truncated:0,") })
    }

    private fun redirectLayoutApis(graph: Graph, declaresOverride: Boolean, ambiguousParent: Boolean = false) {
        val targets = graph.methods.values.filter { "/info/layout/v2" in it.annotationStrings }
        targets.forEachIndexed { i, target ->
            val owner = "sample.InheritedApi$i"
            val reference = target.copy(owner = owner, descriptor = descriptor(owner, target.name, target.returnType, target.parameterTypes),
                annotationStrings = emptySet(), invokes = emptyList(), opcodes = emptyList())
            graph.methods[reference.descriptor] = reference
            val parents = mutableListOf(target.owner)
            if (ambiguousParent) {
                val other = "sample.OtherApi$i"
                graph.add(target.copy(owner = other, descriptor = descriptor(other, target.name, target.returnType, target.parameterTypes),
                    annotationStrings = setOf("/statistics/report")))
                parents += other
            }
            graph.classes[owner] = InstallerClass(owner, "java.lang.Object", interfaces = parents,
                methods = if (declaresOverride) listOf(reference.descriptor) else emptyList())
            graph.methods.values.toList().forEach { method ->
                if (target.descriptor in method.invokes) graph.methods[method.descriptor] = method.copy(
                    invokes = method.invokes.map { if (it == target.descriptor) reference.descriptor else it })
            }
        }
    }

    @Test fun unitLikeSingletonWithoutUnitIdentityIsRejected() {
        val graph = fixture()
        graph.replaceNamed("unitIdentity") { it.copy(strings = setOf("Unrelated.Singleton")) }
        assertThrows(InstallerMatchException::class.java) { InstallerNetworkRules(graph).resolve("cloud_v3") }
    }

    @Test fun uploadPayloadAloneWithoutUrlHolderAssociationIsRejected() {
        val graph = fixture()
        graph.replaceNamed("<clinit>") { it.copy(strings = setOf("/statistics/other")) }
        assertThrows(InstallerMatchException::class.java) { InstallerNetworkRules(graph).resolve("avl_upload") }
    }

    private fun fixture(installType: String = "java.lang.Integer", shorterRequest: Boolean = false): Graph = Graph().apply {
        val args = listOf("java.lang.String", "java.lang.String", "java.lang.String", installType, "java.lang.String") +
            if (shorterRequest) emptyList() else listOf("java.lang.String")
        add(method("sample.Continuation", "resumeWith", "void", listOf("java.lang.Object")))
        add(method("sample.Continuation", "getContext", "sample.CoroutineContext"))
        addClass("sample.Ad")
        add(field("sample.Ad", "data", "java.util.List"))
        add(field("sample.Ad", "channel", "java.lang.String"))
        add(field("sample.Ad", "layoutFormat", "java.lang.String"))
        val adField = field("sample.LayoutData", "adData", "sample.Ad"); add(adField)
        val adGetter = method("sample.LayoutData", "adValue", "sample.Ad", reads = listOf(adField.descriptor)); add(adGetter)
        val apiLayout = method("sample.Api", "apiLayout", "java.lang.Object", listOf("java.util.Map", "sample.Continuation"), annotations = setOf("/info/layout/v2"))
        val apiLayoutAsync = method("sample.Api", "apiLayoutAsync", "sample.Call", listOf("java.util.Map"), annotations = setOf("/info/layout/v2"))
        add(apiLayout); add(apiLayoutAsync)
        val builder = method("sample.Layout", "builder", "java.util.Map", args, strings = setOf("packageName", "installSource", "newInstall", "appName", "isPersonalizedAdEnabled", "androidSdkVersion", "excludeAppIds", "screen_size"), calls = listOf(MAP_PUT))
        val mapper = method("sample.Layout", "mapper", "sample.Ad", listOf("sample.LayoutData", "java.lang.String"), calls = listOf(adGetter.descriptor), opcodes = listOf(0x12, 0x11))
        add(builder); add(mapper); numbers[mapper.descriptor] = setOf(0L)
        val sync = method("sample.Layout", "loadSync", "java.lang.Object", args + "sample.Continuation", strings = setOf("NewAdDataHelper", "loadSync channel=", "loadSync error: "), calls = listOf(builder.descriptor, mapper.descriptor, apiLayout.descriptor), opcodes = listOf(0x12, 0x11))
        add(sync); numbers[sync.descriptor] = setOf(0L)
        val callback = method("sample.Callback", "deliver", "void", listOf("sample.Ad")); add(callback)
        val nullHelper = method("sample.LayoutFailure", "completeEmpty", "void", listOf("sample.Callback"), static = true, calls = listOf(callback.descriptor), opcodes = listOf(0x38, 0x12, 0x72, 0x0e))
        add(nullHelper); numbers[nullHelper.descriptor] = setOf(0L)
        val worker = method("sample.Layout", "worker", "void", args + "sample.Callback", static = true, calls = listOf(builder.descriptor, apiLayoutAsync.descriptor, mapper.descriptor)); add(worker)
        val task = method("sample.Task", "<init>", "void", args + "sample.Callback"); add(task)
        add(method("sample.Task", "run", "void", calls = listOf(worker.descriptor)))
        classes["sample.Task"] = classes.getValue("sample.Task").copy(interfaces = listOf("java.lang.Runnable"))
        add(method("sample.Layout", "load", "void", args + "sample.Callback", calls = listOf(task.descriptor)))

        val interceptApis = listOf("normal", "safemode", "enhancemode").map { role ->
            method("sample.Api", "intercept_$role", "java.lang.Object", listOf("java.util.Map", "sample.Continuation"), annotations = setOf(" /v4/game/interceptcheck/$role")).also(::add)
        }
        addClass("sample.CloudPayload"); addClass("sample.CloudResult"); addClass("sample.Success", "sample.CloudResult")
        listOf("showAdsBefore", "showAdsAfter", "showSafeModeTip").forEach { add(field("sample.CloudPayload", it, "java.lang.Boolean")) }
        add(field("sample.CloudPayload", "uiConfig", "sample.UiConfig")); add(field("sample.CloudPayload", "rc", "sample.RiskConfig")); add(field("sample.CloudPayload", "verifyAccount", "java.lang.Integer"))
        add(field("sample.Success", "data", "sample.CloudPayload"))
        add(method("sample.CloudPayload", "<init>", "void"))
        val success = method("sample.Success", "<init>", "void", listOf("sample.CloudPayload")); add(success)
        add(method("sample.Market", "intercept", "java.lang.Object", listOf("android.content.Context", "sample.Caller", "int", "sample.Apk", "java.util.HashMap", "sample.Continuation"), strings = setOf("device_type", "packageName", "installationMode", "apk_bit", "apkSignature", "androidSdkVersion", "sourceAppName"), calls = interceptApis.map { it.descriptor } + success.descriptor))

        val apiV2 = method("sample.Api", "apiCloudV2", "sample.Call", listOf("java.util.Map"), annotations = setOf("/v2/cloud_config/query")); add(apiV2)
        add(method("sample.CloudV2", "sync", "void", static = true, strings = setOf("cloudConfig", "last_sync_time", "firstboot_time", "source_auth_reset_v", "skip sync cloud config"), calls = listOf(apiV2.descriptor, "Landroid/content/SharedPreferences;->getLong(Ljava/lang/String;J)J")))
        val apiV3 = method("sample.Api", "apiCloudV3", "java.lang.Object", listOf("java.lang.String", "java.util.Map", "sample.Continuation"), annotations = setOf("/v3/cloud_config/query")); add(apiV3)
        val unit = field("sample.UnitValue", "INSTANCE", "sample.UnitValue", true); add(unit)
        add(method("sample.UnitValue", "unitIdentity", "java.lang.String", strings = setOf("kotlin.Unit")))
        add(method("sample.CloudV3", "sync", "java.lang.Object", listOf("java.lang.Runnable", "sample.Continuation"), static = true, strings = setOf("BlockApkListManager", "V3 config sync failed", "http.agent"), calls = listOf(apiV3.descriptor, "Ljava/lang/Runnable;->run()V"), reads = listOf(unit.descriptor), opcodes = listOf(0x62, 0x11)))

        val url = field("sample.Urls", "uploadUrl", "java.lang.String", true); add(url)
        add(method("sample.Urls", "<clinit>", "void", static = true, strings = setOf("/avl/upload"), writes = listOf(url.descriptor)))
        val http = method("sample.Http", "post", "java.lang.String", listOf("java.util.Map", "java.lang.String"), strings = setOf("NetUtil", "request start : "), calls = listOf("Ljava/net/URL;-><init>(Ljava/lang/String;)V", "Ljava/net/HttpURLConnection;->getResponseCode()I")); add(http)
        add(method("sample.Upload", "report", "void", listOf("sample.Caller", "sample.Apk", "boolean", "sample.Session"), static = true, strings = setOf("appSourcepackageName", "appSourceValue", "packageName", "touchInstallCnt", "installCnt", "installTime", "fingerprint", "apkHash", "apkSize", "permission", "cdMd5"), calls = listOf(http.descriptor), reads = listOf(url.descriptor)))
    }

    private class Graph : InstallerDexIndex {
        val methods = linkedMapOf<String, InstallerMethod>()
        val classes = linkedMapOf<String, InstallerClass>()
        val fields = linkedMapOf<String, InstallerField>()
        val numbers = mutableMapOf<String, Set<Long>>()
        fun addClass(name: String, parent: String = "java.lang.Object") { classes.putIfAbsent(name, InstallerClass(name, parent)) }
        fun add(method: InstallerMethod) { methods[method.descriptor] = method; addClass(method.owner); classes[method.owner] = classes.getValue(method.owner).copy(methods = methods.values.filter { it.owner == method.owner }.map { it.descriptor }) }
        fun add(field: InstallerField) { fields[field.descriptor] = field; addClass(field.owner); classes[field.owner] = classes.getValue(field.owner).copy(fields = fields.values.filter { it.owner == field.owner }.map { it.descriptor }) }
        fun replaceNamed(name: String, transform: (InstallerMethod) -> InstallerMethod) { val old = methods.values.single { it.name == name }; methods[old.descriptor] = transform(old) }
        override fun method(descriptor: String) = methods[descriptor]
        override fun clazz(name: String) = classes[name]
        override fun field(descriptor: String) = fields[descriptor]
        override fun callers(descriptor: String) = methods.values.filter { descriptor in it.invokes }
        override fun findMethods(query: InstallerMethodQuery) = methods.values.filter { m ->
            (query.owner == null || query.owner == m.owner) && (query.returnType == null || query.returnType == m.returnType) &&
                (query.isStatic == null || query.isStatic == m.isStatic) && (query.parameterCount == null || query.parameterCount == m.parameterTypes.size) &&
                (query.parameterTypes == null || query.parameterTypes.size == m.parameterTypes.size && query.parameterTypes.indices.all { query.parameterTypes[it] == null || query.parameterTypes[it] == m.parameterTypes[it] }) &&
                m.strings.containsAll(query.strings) && numbers[m.descriptor].orEmpty().containsAll(query.numbers)
        }
        fun renamed(): Graph {
            val result = Graph()
            val types = classes.keys.associateWith { "relocated.Type${classes.keys.indexOf(it)}" }
            fun type(name: String) = types[name] ?: name
            val sharedNames = mutableMapOf<String, String>()
            val methodNames = methods.values.associate { m -> m.descriptor to
                if (m.name in setOf("<init>", "<clinit>", "run")) m.name
                else sharedNames.getOrPut(m.name + m.parameterTypes.joinToString(",") + ":" + m.returnType) { "method${sharedNames.size}" }
            }
            val methodIds = methods.values.associate { m -> m.descriptor to descriptor(type(m.owner), methodNames.getValue(m.descriptor), m.returnType.let(::type), m.parameterTypes.map(::type)) }
            val fieldIds = fields.values.mapIndexed { i, f -> f.descriptor to (dexType(type(f.owner)) + "->field$i:" + dexType(type(f.type))) }.toMap()
            classes.values.forEach { c -> result.classes[type(c.name)] = c.copy(name = type(c.name), superName = c.superName?.let(::type), interfaces = c.interfaces.map(::type), methods = c.methods.map { methodIds[it] ?: it }, fields = c.fields.map { fieldIds[it] ?: it }) }
            fields.values.forEachIndexed { i, f -> result.fields[fieldIds.getValue(f.descriptor)] = f.copy(descriptor = fieldIds.getValue(f.descriptor), owner = type(f.owner), name = "field$i", type = type(f.type), annotationStrings = f.annotationStrings + f.name) }
            methods.values.forEach { m -> val renamed = m.copy(descriptor = methodIds.getValue(m.descriptor), owner = type(m.owner), name = methodNames.getValue(m.descriptor), returnType = type(m.returnType), parameterTypes = m.parameterTypes.map(::type), invokes = m.invokes.map { methodIds[it] ?: it }, readFields = m.readFields.map { fieldIds[it] ?: it }, writtenFields = m.writtenFields.map { fieldIds[it] ?: it }); result.methods[renamed.descriptor] = renamed; result.numbers[renamed.descriptor] = numbers[m.descriptor].orEmpty() }
            return result
        }
    }

    companion object {
        private const val MAP_PUT = "Ljava/util/Map;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"
        private fun method(owner: String, name: String, result: String, parameters: List<String> = emptyList(), static: Boolean = false, strings: Set<String> = emptySet(), annotations: Set<String> = emptySet(), calls: List<String> = emptyList(), reads: List<String> = emptyList(), writes: List<String> = emptyList(), opcodes: List<Int> = emptyList()) = InstallerMethod(descriptor(owner, name, result, parameters), owner, name, result, parameters, static, strings, annotations, calls, reads, writes, opcodes)
        private fun field(owner: String, name: String, type: String, static: Boolean = false) = InstallerField(dexType(owner) + "->$name:" + dexType(type), owner, name, type, static)
        private fun descriptor(owner: String, name: String, result: String, parameters: List<String>) = dexType(owner) + "->$name(" + parameters.joinToString("") { dexType(it) } + ")" + dexType(result)
        private fun dexType(type: String) = when (type) { "void" -> "V"; "boolean" -> "Z"; "int" -> "I"; "long" -> "J"; else -> "L" + type.replace('.', '/') + ";" }
    }
}
