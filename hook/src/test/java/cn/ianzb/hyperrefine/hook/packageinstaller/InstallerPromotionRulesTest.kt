package cn.ianzb.hyperrefine.hook.packageinstaller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallerPromotionRulesTest {
    @Test fun resolvesOnlyBeforeAndAfterDisplayGates() {
        val f = Fixture()
        assertEquals(f.before.descriptor, InstallerPromotionRules(f).resolve("ads_before").method.descriptor)
        assertEquals(f.after.descriptor, InstallerPromotionRules(f).resolve("ads_after").method.descriptor)
        assertEquals(f.cancel.descriptor, InstallerPromotionRules(f).resolve("ads_cancel").method.descriptor)
    }

    @Test fun renamedClassesMethodsAndSerializedFieldsStillResolve() {
        val f = Fixture()
        val moved = f.renamed()
        for (id in listOf("ads_before", "ads_after", "ads_cancel")) {
            assertNotEquals(InstallerPromotionRules(f).resolve(id).method.descriptor,
                InstallerPromotionRules(moved).resolve(id).method.descriptor)
        }
    }

    @Test fun publicCloudFieldNamesAreValidBusinessIdentityWithoutAnnotations() {
        val f = Fixture()
        f.fields.replaceAll { _, field -> field.copy(annotationStrings = emptySet()) }
        assertEquals(f.before.descriptor, InstallerPromotionRules(f).resolve("ads_before").method.descriptor)
        assertEquals(f.after.descriptor, InstallerPromotionRules(f).resolve("ads_after").method.descriptor)
    }

    @Test fun frameworkTrueReferenceNeedsNoApkFieldDefinition() {
        val f = Fixture()
        f.fields.remove(TRUE)
        assertEquals(f.before.descriptor, InstallerPromotionRules(f).resolve("ads_before").method.descriptor)
        assertEquals(f.after.descriptor, InstallerPromotionRules(f).resolve("ads_after").method.descriptor)
    }

    @Test fun missingArbitraryBeforeReferenceStillRejectsPureGate() {
        val f = Fixture()
        f.fields.remove(TRUE)
        f.replace(f.before.copy(readFields = f.before.readFields + "Lfixture/Unknown;->permission:Z"))
        rejects(f, "ads_before")
    }

    @Test fun afterIgnoresMissingUnrelatedReadButNeverMissingAdRole() {
        val f = Fixture()
        f.fields.remove(TRUE)
        f.replace(f.after.copy(readFields = f.after.readFields + "Lfixture/ActivityState;->unresolved:Z"))
        assertEquals(f.after.descriptor, InstallerPromotionRules(f).resolve("ads_after").method.descriptor)
        f.fields.remove(f.afterField)
        rejects(f, "ads_after")
    }

    @Test fun unresolvedExternalReferenceCannotWeakenAfterConsumers() {
        val f = Fixture()
        f.fields.remove(TRUE)
        f.replace(f.after.copy(readFields = f.after.readFields + "Lfixture/ActivityState;->unresolved:Z"))
        f.add(method("fixture.Auth", "authorize", "boolean", invokes = listOf(f.after.descriptor)))
        rejects(f, "ads_after")
    }

    @Test fun pureCloudPropertyGettersReplaceDirectFieldReads() {
        val f = Fixture()
        val beforeGetter = method(f.cloud, "beforeProperty", BOOLEAN, reads = listOf(f.beforeField), opcodes = listOf(0x54, 0x11))
        val afterGetter = method(f.cloud, "afterProperty", BOOLEAN, reads = listOf(f.afterField), opcodes = listOf(0x54, 0x11))
        val rulesGetter = method(f.cloud, "rulesProperty", BOOLEAN, reads = listOf(f.rulesField), opcodes = listOf(0x54, 0x11))
        listOf(beforeGetter, afterGetter, rulesGetter).forEach(f::add)
        f.replace(f.before.copy(readFields = listOf(TRUE), invokes = f.before.invokes + beforeGetter.descriptor))
        f.replace(f.after.copy(readFields = listOf(TRUE), invokes = listOf(afterGetter.descriptor, rulesGetter.descriptor)))
        assertEquals(f.before.descriptor, InstallerPromotionRules(f).resolve("ads_before").method.descriptor)
        assertEquals(f.after.descriptor, InstallerPromotionRules(f).resolve("ads_after").method.descriptor)
        f.replace(beforeGetter.copy(invokes = listOf("Lfixture/Auth;->check()Z")))
        rejects(f, "ads_before")
        f.replace(afterGetter.copy(writtenFields = listOf(f.afterField)))
        rejects(f, "ads_after")
    }

    @Test fun anotherBooleanPropertyCannotReplaceTheBeforeRole() {
        val f = Fixture()
        val getter = method(f.cloud, "safeProperty", BOOLEAN, reads = listOf(f.safeField), opcodes = listOf(0x54, 0x11))
        f.add(getter)
        f.replace(f.before.copy(readFields = listOf(TRUE), invokes = f.before.invokes + getter.descriptor))
        rejects(f, "ads_before")
    }

    @Test fun missingBeforeEvidenceDoesNotDisableAfter() {
        val f = Fixture()
        f.replace(f.before.copy(readFields = listOf(TRUE)))
        rejects(f, "ads_before")
        assertEquals(f.after.descriptor, InstallerPromotionRules(f).resolve("ads_after").method.descriptor)
    }

    @Test fun duplicateBeforeGateIsAmbiguous() { val f = Fixture(); duplicate(f, f.before); assertEquals(2, rejects(f, "ads_before").candidateCount) }
    @Test fun duplicateAfterGateIsAmbiguous() { val f = Fixture(); duplicate(f, f.after); assertEquals(2, rejects(f, "ads_after").candidateCount) }

    @Test fun beforeSharedWithAuthorizationIsRejected() {
        val f = Fixture()
        f.add(method("fixture.auth.Source", "authorize", "boolean", invokes = listOf(f.before.descriptor)))
        rejects(f, "ads_before")
    }

    @Test fun afterSharedWithAuthorizationIsRejected() {
        val f = Fixture()
        f.add(method("fixture.auth.Signature", "verify", "boolean", invokes = listOf(f.after.descriptor)))
        rejects(f, "ads_after")
    }

    @Test fun afterMergeSharedByUnknownCallerIsRejected() {
        val f = Fixture()
        f.add(method(f.activity, "unknown", "void", invokes = listOf(f.merge.descriptor)))
        rejects(f, "ads_after")
    }

    @Test fun standardCollectionIsEmptyOwnerIsAccepted() {
        val f = Fixture()
        f.replace(f.merge.copy(invokes = f.merge.invokes.map {
            if (it == IS_EMPTY) "Ljava/util/Collection;->isEmpty()Z" else it
        }))
        assertEquals(f.after.descriptor, InstallerPromotionRules(f).resolve("ads_after").method.descriptor)
    }

    @Test fun unknownListConsumerOfBeforeIsRejected() {
        val f = Fixture()
        f.add(method(f.factory, "authorizationList", LIST, listOf(f.cloud, "fixture.Apk"), invokes = listOf(f.before.descriptor)))
        rejects(f, "ads_before")
    }

    @Test fun fieldWritesMakeBeforeGateUnsafe() {
        val f = Fixture()
        f.replace(f.before.copy(writtenFields = listOf(f.beforeField)))
        rejects(f, "ads_before")
    }

    @Test fun comparatorWithOtherBusinessCallsIsRejected() {
        val f = Fixture()
        f.replace(f.equal.copy(invokes = f.equal.invokes + "Lfixture/Auth;->check()Z"))
        rejects(f, "ads_before")
    }

    @Test fun promotionFactoryWithNonPromotionModelIsRejected() {
        val f = Fixture()
        val foreign = method("fixture.PermissionDialog", "<init>", "void")
        f.add(foreign)
        f.replace(f.beforeConsumer.copy(invokes = f.beforeConsumer.invokes + foreign.descriptor))
        rejects(f, "ads_before")
    }

    @Test fun typedAdvertisementModelDoesNotNeedAnalyticsStrings() {
        val f = Fixture()
        f.replace(f.analytics.copy(strings = setOf("normal_app_info")))
        assertEquals(f.before.descriptor, InstallerPromotionRules(f).resolve("ads_before").method.descriptor)
        assertEquals(f.after.descriptor, InstallerPromotionRules(f).resolve("ads_after").method.descriptor)
    }

    @Test fun ordinaryRiskDataWithoutMonitorUrlsIsNotAnAdvertisement() {
        val f = Fixture()
        f.fields.values.filter { it.owner == f.adPayload && it.name.endsWith("MonitorUrls") }.map { it.descriptor }.forEach(f.fields::remove)
        rejects(f, "ads_before")
        rejects(f, "ads_after")
    }

    @Test fun failedCardSchemaProbeIsBoundedStaticAndUsesStableRoleKey() {
        val f = Fixture()
        f.fields.values.filter { it.owner == f.adPayload && it.name == "viewMonitorUrls" }.map { it.descriptor }.forEach(f.fields::remove)
        val rules = InstallerPromotionRules(f)
        assertThrows(InstallerMatchException::class.java) { rules.resolve("ads_before") }
        assertThrows(InstallerMatchException::class.java) { rules.resolve("ads_after") }
        val models = rules.diagnosticLines().filter { "_models detail=" in it }
        assertEquals(2, models.size)
        assertTrue(models.all { it.length <= 384 && "card=Lfixture/AdCard;" in it && "payload=Lfixture/AdPayload;" in it })
        assertTrue(models.all { "view=0:-" in it && "click=1:[S" in it && "pkg=1:S" in it })
        assertTrue(models.any { it.startsWith("probe ads_before_models detail=") } && models.any { it.startsWith("probe ads_after_models detail=") })
    }

    @Test fun typedPayloadMustBeActuallyStoredAndReadByTheCard() {
        val f = Fixture()
        f.replace(f.constructor.copy(writtenFields = emptyList()))
        rejects(f, "ads_before")
        val g = Fixture()
        g.replace(g.analytics.copy(readFields = emptyList()))
        rejects(g, "ads_before")
        val h = Fixture()
        h.replace(h.constructor.copy(parameterTypes = listOf("android.content.Context", h.cloud)))
        rejects(h, "ads_before")
    }

    @Test fun syntheticConstructorMayDelegateToTheTypedStore() {
        val f = Fixture()
        val bridge = method(f.constructor.owner, "<init>", "void", f.constructor.parameterTypes + listOf("int", "fixture.DefaultMarker"),
            invokes = listOf(f.constructor.descriptor))
        f.add(bridge)
        f.replace(f.beforeConsumer.copy(invokes = f.beforeConsumer.invokes.map { if (it == f.constructor.descriptor) bridge.descriptor else it }))
        assertEquals(f.before.descriptor, InstallerPromotionRules(f).resolve("ads_before").method.descriptor)
        f.replace(bridge.copy(invokes = listOf(bridge.descriptor)))
        rejects(f, "ads_before")
    }

    @Test fun quickGamePayloadHasItsOwnExplicitBusinessSchema() {
        val f = Fixture()
        f.fields.values.filter { it.owner == f.adPayload && it.name.endsWith("MonitorUrls") }.map { it.descriptor }.forEach(f.fields::remove)
        val additions = listOf("traceKv" to "java.lang.String", "appId" to "long", "apkSizeV2" to "long").map { (name, type) ->
            InstallerField(field(f.adPayload, name, type), f.adPayload, name, type, false, setOf(name))
        }
        additions.forEach { f.fields[it.descriptor] = it }
        f.classes[f.adPayload] = f.classes.getValue(f.adPayload).copy(fields = f.classes.getValue(f.adPayload).fields + additions.map { it.descriptor })
        assertEquals(f.before.descriptor, InstallerPromotionRules(f).resolve("ads_before").method.descriptor)
        f.fields.remove(additions.first().descriptor)
        rejects(f, "ads_before")
    }

    @Test fun mixedAuthorizationModelConstructorStillRejectsBothGates() {
        val f = Fixture()
        val auth = method("fixture.RiskConfirmation", "<init>", "void", listOf("android.content.Context", f.adEntry))
        f.add(auth)
        f.classes[auth.owner] = InstallerClass(auth.owner, "fixture.ViewModel", methods = listOf(auth.descriptor))
        f.replace(f.beforeConsumer.copy(invokes = f.beforeConsumer.invokes + auth.descriptor))
        f.replace(f.promotion.copy(invokes = f.promotion.invokes + auth.descriptor))
        rejects(f, "ads_before")
        rejects(f, "ads_after")
    }

    @Test fun multipleTypedPayloadKindsOnOneCardAreAmbiguous() {
        val f = Fixture()
        val other = field(f.constructor.owner, "otherPayload", f.adPayload)
        f.fields[other] = InstallerField(other, f.constructor.owner, "otherPayload", f.adPayload, false)
        f.classes[f.constructor.owner] = f.classes.getValue(f.constructor.owner).copy(fields = listOf(f.cardPayloadField, other))
        f.replace(f.constructor.copy(parameterTypes = f.constructor.parameterTypes + f.adPayload, writtenFields = f.constructor.writtenFields + other))
        f.replace(f.analytics.copy(readFields = f.analytics.readFields + other))
        rejects(f, "ads_before")
        rejects(f, "ads_after")
    }

    @Test fun afterNeedsActivityAndVerifiedAdConfigProducer() {
        val f = Fixture()
        f.classes[f.activity] = f.classes.getValue(f.activity).copy(superName = "java.lang.Object")
        rejects(f, "ads_after")
        val g = Fixture()
        g.replace(g.layout.copy(strings = setOf("layout")))
        assertEquals(g.after.descriptor, InstallerPromotionRules(g).resolve("ads_after").method.descriptor)
    }

    @Test fun globalPreferenceAndOtherCloudBooleanAreNeverTargets() {
        val f = Fixture()
        f.add(method("fixture.global.Settings", "virusScan", "boolean", listOf("android.content.Context"), true,
            strings = setOf("virus_scan_install")))
        f.add(method(f.factory, "sourceAuth", "boolean", listOf(f.cloud), reads = listOf(f.beforeField)))
        val rules = InstallerPromotionRules(f)
        assertEquals(f.before.descriptor, rules.resolve("ads_before").method.descriptor)
        assertEquals(f.after.descriptor, rules.resolve("ads_after").method.descriptor)
        assertThrows(InstallerMatchException::class.java) { rules.resolve("avl_upload") }
    }

    @Test fun cancelUsesOnlyVerifiedHolderAndAdContainer() {
        val f = Fixture()
        val point = InstallerPromotionRules(f).resolve("ads_cancel")
        assertEquals(f.holderField, point.holderField?.descriptor)
        assertEquals(f.container.descriptor, point.containerGetter?.descriptor)
        assertNotEquals(f.binder.descriptor, point.method.descriptor)
    }

    @Test fun cancelDiagnosticLogsAreNotRequired() {
        val f = Fixture()
        f.replace(f.cancel.copy(strings = f.cancel.strings.filterNot { it.startsWith("setupAdGrid") }.toSet()))
        assertEquals(f.cancel.descriptor, InstallerPromotionRules(f).resolve("ads_cancel").method.descriptor)
    }

    @Test fun cancelAnalyticsCanMoveOutOfTheAdBindingBody() {
        val f = Fixture()
        f.replace(f.cancel.copy(strings = emptySet()))
        f.replace(f.analytics.copy(strings = emptySet()))
        assertEquals(f.cancel.descriptor, InstallerPromotionRules(f).resolve("ads_cancel").method.descriptor)
    }

    @Test fun cancelSemanticGetterAliasSurvivesFieldRenaming() {
        val f = Fixture()
        renameField(f, f.fields.getValue(f.adContainerField), "obfuscated", emptySet())
        val old = f.methods.getValue(f.container.descriptor)
        val renamed = old.copy(name = "getFlAdCard", descriptor = desc(f.holder, "getFlAdCard", old.returnType, emptyList()))
        f.methods.remove(old.descriptor)
        f.add(renamed)
        f.replace(f.cancel.copy(readFields = f.cancel.readFields - f.cancelAdField,
            invokes = f.cancel.invokes.map { if (it == old.descriptor) renamed.descriptor else it }))
        assertEquals(renamed.descriptor, InstallerPromotionRules(f).resolve("ads_cancel").containerGetter?.descriptor)
    }

    @Test fun fullyObfuscatedLocalContainerNeedsRuntimeResourceIdentity() {
        val f = Fixture()
        for (id in listOf(f.adContainerField, f.titleField, f.subtitleField)) renameField(f, f.fields.getValue(id), "slot" + id.hashCode().toString().replace('-', 'n'), emptySet())
        val point = InstallerPromotionRules(f).resolve("ads_cancel")
        assertEquals(f.container.descriptor, point.containerGetter?.descriptor)
        assertTrue(point.evidence.any { it.contains("com.miui.packageinstaller:id/fl_ad_card") })
        f.replace(f.cancel.copy(readFields = f.cancel.readFields - f.cancelAdField))
        rejects(f, "ads_cancel")
    }

    @Test fun localAdCollectionAllowsExplicitDataListAndLayoutVersionRoles() {
        val f = Fixture()
        for ((old, replacement) in listOf("data" to "dataList", "layoutFormat" to "layoutVersionType")) {
            val member = f.fields.values.single { it.owner == f.adCollection && it.name == old }
            renameField(f, member, replacement, emptySet())
        }
        renameField(f, f.fields.getValue(f.adContainerField), "obfuscated", emptySet())
        assertEquals(f.cancel.descriptor, InstallerPromotionRules(f).resolve("ads_cancel").method.descriptor)
    }

    @Test fun multipleLocalAdCollectionListRolesAreNotGuessed() {
        val f = Fixture()
        renameField(f, f.fields.getValue(f.adContainerField), "obfuscated", emptySet())
        val other = field(f.adCollection, "dataList", LIST)
        f.fields[other] = InstallerField(other, f.adCollection, "dataList", LIST, false)
        f.classes[f.adCollection] = f.classes.getValue(f.adCollection).copy(fields = f.classes.getValue(f.adCollection).fields + other)
        rejects(f, "ads_cancel")
    }

    @Test fun afterFailureExplainsProducerShapeSupplierAndCardSeparately() {
        val f = Fixture()
        f.replace(f.layout.copy(returnType = "void"))
        assertTrue(rejects(f, "ads_after").message.orEmpty().contains("producer_shape"))
        val g = Fixture()
        g.replace(g.layout.copy(invokes = listOf(g.merge.descriptor)))
        assertTrue(rejects(g, "ads_after").message.orEmpty().contains("supplier_shape"))
        val h = Fixture()
        h.replace(h.promotion.copy(invokes = emptyList()))
        assertTrue(rejects(h, "ads_after").message.orEmpty().contains("supplier_card"))
    }

    @Test fun multipleLocalFrameGettersAreNeverGuessed() {
        val f = Fixture()
        val other = field(f.holder, "riskFrame", "android.widget.FrameLayout")
        f.fields[other] = InstallerField(other, f.holder, "riskFrame", "android.widget.FrameLayout", false)
        val getter = method(f.holder, "otherFrame", "android.widget.FrameLayout", reads = listOf(other), opcodes = listOf(0x54, 0x11))
        f.add(getter)
        f.replace(f.cancel.copy(invokes = f.cancel.invokes + getter.descriptor))
        rejects(f, "ads_cancel")
    }

    @Test fun unlabelledContainerWithoutTypedAdModelCannotBeSelected() {
        for (name in listOf("riskCard", "authorizationPanel", "continueButtonContainer", "wholeScreen")) {
            val f = Fixture()
            renameField(f, f.fields.getValue(f.adContainerField), name, emptySet())
            f.replace(f.cancel.copy(strings = emptySet(), readFields = f.cancel.readFields - f.cancelAdField))
            rejects(f, "ads_cancel")
        }
    }

    @Test fun cancelRequiresAdContainerOnTheSameBoundHolder() {
        val f = Fixture()
        f.replace(f.cancel.copy(readFields = listOf(f.showAdsField)))
        rejects(f, "ads_cancel")
    }

    @Test fun cancelAfterBindingTargetMayKeepOriginalRiskSideEffects() {
        val f = Fixture()
        val risk = method(f.cancel.owner, "keepRiskConfirmation", "boolean", strings = setOf("source_authorization"))
        f.add(risk)
        f.replace(f.cancel.copy(strings = emptySet(), invokes = f.cancel.invokes + risk.descriptor))
        val point = InstallerPromotionRules(f).resolve("ads_cancel")
        assertEquals(f.cancel.descriptor, point.method.descriptor)
        assertEquals(f.container.descriptor, point.containerGetter?.descriptor)
        assertTrue(point.evidence.any { it.contains("runtime resource identity") })
    }

    @Test fun cancelExtraAuthorizationConsumerIsRejected() {
        val f = Fixture()
        f.add(method(f.cancel.owner, "authorize", "boolean", invokes = listOf(f.cancel.descriptor)))
        rejects(f, "ads_cancel")
    }

    @Test fun cancelNonAdContainerAndImpureGetterAreRejected() {
        val f = Fixture()
        val field = f.fields.getValue(f.adContainerField)
        f.fields[f.adContainerField] = field.copy(name = "wholeScreen", annotationStrings = emptySet())
        f.replace(f.cancel.copy(readFields = f.cancel.readFields - f.cancelAdField))
        rejects(f, "ads_cancel")
        val g = Fixture()
        g.replace(g.container.copy(invokes = listOf("Lfixture/Auth;->check()V")))
        rejects(g, "ads_cancel")
    }

    @Test fun cancelBinderMustPreserveSeparateTitleAndSubtitle() {
        val f = Fixture()
        f.replace(f.binder.copy(invokes = f.binder.invokes.filterNot { it == f.subtitle.descriptor }))
        rejects(f, "ads_cancel")
    }

    @Test fun cancelAmbiguousBindIsRejected() { val f = Fixture(); duplicate(f, f.cancel); assertEquals(2, rejects(f, "ads_cancel").candidateCount) }

    @Test fun cancelMayNotWriteBusinessFields() {
        val f = Fixture()
        f.replace(f.cancel.copy(writtenFields = listOf(f.holderField)))
        rejects(f, "ads_cancel")
    }

    @Test fun duplicateNativeRelationsDoNotChangeRoles() {
        val f = Fixture()
        f.methods.replaceAll { _, m -> m.copy(readFields = m.readFields + m.readFields,
            writtenFields = m.writtenFields + m.writtenFields, invokes = m.invokes + m.invokes) }
        f.classes.replaceAll { _, c -> c.copy(fields = c.fields + c.fields, methods = c.methods + c.methods) }
        for (id in listOf("ads_before", "ads_after", "ads_cancel")) InstallerPromotionRules(f).resolve(id)
    }

    @Test fun directBooleanValueUsesOnlyTheVerifiedWrapperField() {
        val f = Fixture()
        f.replace(f.before.copy(readFields = listOf(f.beforeField), invokes = listOf("Ljava/lang/Boolean;->booleanValue()Z"),
            opcodes = listOf(0x38, 0x54, 0x6e, 0x0a, 0x0f)))
        assertEquals(f.before.descriptor, InstallerPromotionRules(f).resolve("ads_before").method.descriptor)
    }

    @Test fun directBooleanOrObjectEqualsKeepTrueConstantContract() {
        for (owner in listOf("java/lang/Boolean", "java/lang/Object")) {
            val f = Fixture()
            f.replace(f.before.copy(invokes = listOf("L$owner;->equals(Ljava/lang/Object;)Z"),
                opcodes = listOf(0x38, 0x54, 0x62, 0x6e, 0x0a, 0x0f)))
            assertEquals(f.before.descriptor, InstallerPromotionRules(f).resolve("ads_before").method.descriptor)
            f.replace(f.methods.getValue(f.before.descriptor).copy(readFields = listOf(f.beforeField)))
            rejects(f, "ads_before")
        }
    }

    @Test fun inlinedIdentityComparisonNeedsTrueField() {
        val f = Fixture()
        f.replace(f.before.copy(invokes = emptyList(), opcodes = listOf(0x54, 0x62, 0x32, 0x12, 0x0f)))
        InstallerPromotionRules(f).resolve("ads_before")
        f.replace(f.methods.getValue(f.before.descriptor).copy(readFields = listOf(f.beforeField)))
        rejects(f, "ads_before")
    }

    @Test fun arbitraryBooleanCallCannotStandInForUnboxing() {
        val f = Fixture()
        val auth = method("fixture.SourceAuth", "booleanValue", "boolean")
        f.add(auth)
        f.replace(f.before.copy(invokes = listOf(auth.descriptor), opcodes = listOf(0x54, 0x6e, 0x0a, 0x0f)))
        assertEquals(0, rejects(f, "ads_before").candidateCount)
    }

    @Test fun noopsAndObjectMovesKeepPureGetterContract() {
        val f = Fixture()
        for (m in listOf(f.container, f.title, f.subtitle)) f.replace(m.copy(opcodes = listOf(0x00, 0x54, 0x07, 0x00, 0x11)))
        InstallerPromotionRules(f).resolve("ads_cancel")
        f.replace(f.container.copy(opcodes = listOf(0x54, 0x54, 0x11)))
        rejects(f, "ads_cancel")
    }

    @Test fun knownListInheritanceKeepsProducerAndMergeRelations() {
        val f = Fixture()
        retype(f, f.beforeConsumer, "java.util.ArrayList", f.beforeConsumer.parameterTypes)
        retype(f, f.promotion, "java.util.LinkedList", emptyList())
        retype(f, f.layout, "java.util.ArrayList", emptyList())
        val merge = retype(f, f.merge, "void", listOf("java.util.ArrayList", "java.util.LinkedList"))
        f.replace(merge.copy(invokes = merge.invokes.map {
            when (it) {
                ADD_ALL -> "Ljava/util/ArrayList;->addAll(Ljava/util/Collection;)Z"
                IS_EMPTY -> "Ljava/util/LinkedList;->isEmpty()Z"
                else -> it
            }
        }))
        InstallerPromotionRules(f).resolve("ads_before")
        InstallerPromotionRules(f).resolve("ads_after")
    }

    @Test fun serializedSnakeCaseAliasesSurviveFieldRenaming() {
        val f = Fixture()
        f.fields.values.toList().filter { it.annotationStrings.isNotEmpty() }.forEachIndexed { n, field ->
            val aliases = field.annotationStrings.map { it.replace(Regex("([a-z])([A-Z])"), "$1_$2").lowercase() }.toSet()
            renameField(f, field, "obfuscated$n", aliases)
        }
        for (id in listOf("ads_before", "ads_after", "ads_cancel")) InstallerPromotionRules(f).resolve(id)
    }

    @Test fun standardViewGroupVisibilityIsAccepted() {
        val f = Fixture()
        f.replace(f.cancel.copy(invokes = f.cancel.invokes.map { it.replace("Landroid/widget/FrameLayout;->setVisibility", "Landroid/view/ViewGroup;->setVisibility") }))
        InstallerPromotionRules(f).resolve("ads_cancel")
    }

    @Test fun missingCloudClassAndFieldHaveDistinctStageReasons() {
        val f = Fixture()
        f.classes.remove(f.cloud)
        val missingClass = rejects(f, "ads_before").message.orEmpty()
        assertTrue(missingClass.contains("cloud=0") && missingClass.contains("cloud_class"))
        val g = Fixture()
        g.fields.remove(g.beforeField)
        val missingField = rejects(g, "ads_before").message.orEmpty()
        assertTrue(missingField.contains("cloud=0") && missingField.contains("field_ref"))
    }

    @Test fun missingHolderClassAndGetterRoleExplainDifferentStops() {
        val f = Fixture()
        f.classes.remove(f.holder)
        val missingClass = rejects(f, "ads_cancel").message.orEmpty()
        assertTrue(missingClass.contains("holder=0") && missingClass.contains("holder_class"))
        val g = Fixture()
        renameField(g, g.fields.getValue(g.adContainerField), "unidentified", emptySet())
        g.replace(g.cancel.copy(readFields = g.cancel.readFields - g.cancelAdField))
        val missingRole = rejects(g, "ads_cancel").message.orEmpty()
        assertTrue(missingRole, missingRole.contains("getter=0") && missingRole.contains("getter_role"))
    }

    @Test fun missingModelAndUnknownConstructorStillFailClosed() {
        val f = Fixture()
        f.classes.remove("fixture.AdCard")
        val error = rejects(f, "ads_before").message.orEmpty()
        assertTrue(error.contains("model=0") && error.contains("model_class"))
        val g = Fixture()
        g.replace(g.beforeConsumer.copy(invokes = g.beforeConsumer.invokes + "Lfixture/UnknownBusiness;-><init>()V"))
        rejects(g, "ads_before")
    }

    @Test fun missingAnalyticsAnchorDoesNotRemoveVerifiedAdContainerIdentity() {
        val f = Fixture()
        f.replace(f.cancel.copy(strings = f.cancel.strings - "advertise_more_btn"))
        assertEquals(f.cancel.descriptor, InstallerPromotionRules(f).resolve("ads_cancel").method.descriptor)
    }

    @Test fun failureDiagnosticKeepsAtMostTwoStaticFieldReferences() {
        val f = Fixture()
        val refs = (0..7).map { "Lfixture/StaticType;->field$it:Ljava/lang/Boolean;" }
        f.replace(f.before.copy(readFields = f.before.readFields + refs, strings = setOf("private.user.apk.path")))
        val rules = InstallerPromotionRules(f)
        assertThrows(InstallerMatchException::class.java) { rules.resolve("ads_before") }
        assertThrows(InstallerMatchException::class.java) { rules.resolve("ads_before") }
        val lines = rules.diagnosticLines()
        assertEquals(1, lines.size)
        assertTrue(lines.single().startsWith("probe ads_before_fields detail=refs="))
        assertTrue(lines.single().length <= 384)
        assertTrue(lines.single().contains(refs[0]) && lines.single().contains(refs[1]))
        assertTrue(!lines.single().contains(refs[2]) && !lines.single().contains("private.user.apk.path"))
    }

    @Test fun failureDetailsBoundNearNamesAndNeverPrintArgumentLikeStrings() {
        val f = Fixture()
        f.replace(f.before.copy(strings = setOf("private.user.apk.path")))
        repeat(12) { n -> f.add(f.before.copy(name = "near$n", descriptor = desc(f.factory, "near$n", "boolean", listOf(f.cloud)), readFields = listOf(TRUE))) }
        val error = rejects(f, "ads_before").message.orEmpty()
        assertTrue(!error.contains("private.user.apk.path"))
        assertTrue(error.substringAfter(" near=").split('|').size <= 2)
        assertTrue(error.length < 384)
    }

    private fun retype(f: Fixture, original: InstallerMethod, result: String, params: List<String>): InstallerMethod {
        val current = f.methods.getValue(original.descriptor)
        val id = desc(current.owner, current.name, result, params)
        val changed = current.copy(descriptor = id, returnType = result, parameterTypes = params)
        f.methods.remove(current.descriptor)
        f.add(changed)
        f.methods.replaceAll { _, method -> method.copy(invokes = method.invokes.map { if (it == current.descriptor) id else it }) }
        f.classes.replaceAll { _, clazz -> clazz.copy(methods = clazz.methods.map { if (it == current.descriptor) id else it }.distinct()) }
        return f.methods.getValue(id)
    }

    private fun renameField(f: Fixture, original: InstallerField, name: String, annotations: Set<String>) {
        val id = field(original.owner, name, original.type)
        f.fields.remove(original.descriptor)
        f.fields[id] = original.copy(descriptor = id, name = name, annotationStrings = annotations)
        f.methods.replaceAll { _, method -> method.copy(readFields = method.readFields.map { if (it == original.descriptor) id else it }, writtenFields = method.writtenFields.map { if (it == original.descriptor) id else it }) }
        f.classes.replaceAll { _, clazz -> clazz.copy(fields = clazz.fields.map { if (it == original.descriptor) id else it }) }
    }

    private fun rejects(index: InstallerDexIndex, id: String): InstallerMatchException =
        assertThrows(InstallerMatchException::class.java) { InstallerPromotionRules(index).resolve(id) }

    private fun duplicate(f: Fixture, original: InstallerMethod) {
        val copy = original.copy(name = "duplicate", descriptor = desc(original.owner, "duplicate", original.returnType, original.parameterTypes))
        f.add(copy)
        f.methods.values.toList().filter { original.descriptor in it.invokes }.forEach { f.replace(it.copy(invokes = it.invokes + copy.descriptor)) }
    }

    private class Fixture : InstallerDexIndex {
        val cloud = "fixture.Payload"
        val factory = "fixture.Recommendations"
        val activity = "fixture.Page"
        val methods = linkedMapOf<String, InstallerMethod>()
        val fields = linkedMapOf<String, InstallerField>()
        val classes = linkedMapOf<String, InstallerClass>()
        val beforeField = field(cloud, "showAdsBefore", BOOLEAN)
        val afterField = field(cloud, "showAdsAfter", BOOLEAN)
        val rulesField = field(cloud, "useSystemAppRules", BOOLEAN)
        val safeField = field(cloud, "showSafeModeTip", BOOLEAN)
        val equal = method("fixture.Equal", "same", "boolean", listOf("java.lang.Object", "java.lang.Object"), true,
            invokes = listOf("Ljava/lang/Object;->equals(Ljava/lang/Object;)Z"), opcodes = listOf(0x39, 0x6e, 0x0a, 0x0f))
        val before = method(factory, "enabled", "boolean", listOf(cloud),
            reads = listOf(beforeField, TRUE), invokes = listOf(equal.descriptor), opcodes = listOf(0x38, 0x54, 0x62, 0x71, 0x0a, 0x0f))
        val adPayload = "fixture.AdPayload"
        val adEntry = "fixture.AdEntry"
        val cardPayloadField = field("fixture.AdCard", "payload", adEntry)
        val constructor = method("fixture.AdCard", "<init>", "void", listOf("android.content.Context", adEntry))
            .copy(writtenFields = listOf(cardPayloadField))
        val analytics = method("fixture.AdCard", "track", "void", strings = setOf("advertise_app_install_btn", "advertise_package_name", "advertise_type"),
            reads = listOf(cardPayloadField))
        val beforeConsumer = method(factory, "build", LIST, listOf(cloud, "fixture.Apk"),
            invokes = listOf(before.descriptor, constructor.descriptor, "Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z"))
        val after = method(activity, "enabled", "boolean", reads = listOf(afterField, rulesField, TRUE), opcodes = listOf(0x38, 0x0f))
        val merge = method(activity, "merge", "void", listOf(LIST, LIST), invokes = listOf(after.descriptor, ADD_ALL, IS_EMPTY))
        val promotion = method(activity, "recommendations", LIST, invokes = listOf(constructor.descriptor))
        val layout = method(activity, "layout", LIST, strings = setOf("adConfig", "adp"), invokes = listOf(merge.descriptor, promotion.descriptor))
        val holder = "fixture.CancelHolder"
        val cancelOwner = "fixture.CancelCard"
        val holderField = field(cancelOwner, "holder", holder)
        val adContainerField = field(holder, "flAdCard", "android.widget.FrameLayout")
        val titleField = field(holder, "tvCancelTitle", "android.widget.TextView")
        val subtitleField = field(holder, "tvCancelSubtitle", "android.widget.TextView")
        val showAdsField = field(cancelOwner, "showAds", "boolean")
        val adCollection = "fixture.AdCollection"
        val cancelAdField = field(cancelOwner, "ads", adCollection)
        val container = method(holder, "adContainer", "android.widget.FrameLayout", reads = listOf(adContainerField), opcodes = listOf(0x54, 0x11))
        val title = method(holder, "title", "android.widget.TextView", reads = listOf(titleField), opcodes = listOf(0x54, 0x11))
        val subtitle = method(holder, "subtitle", "android.widget.TextView", reads = listOf(subtitleField), opcodes = listOf(0x54, 0x11))
        val cancel = method(cancelOwner, "advertisementGrid", "void", strings = setOf("advertise_more_btn", "advertise_pacakge_btn", "advertise_app_install_btn", "setupAdGrid: some diagnostic"),
            reads = listOf(holderField, showAdsField, cancelAdField), invokes = listOf(container.descriptor, "Landroid/widget/FrameLayout;->setVisibility(I)V"))
        val binder = method(cancelOwner, "bindTitles", "void", listOf(holder), invokes = listOf(cancel.descriptor, title.descriptor, subtitle.descriptor, "Landroid/widget/TextView;->setText(Ljava/lang/CharSequence;)V")).copy(writtenFields = listOf(holderField))
        init {
            fields[TRUE] = InstallerField(TRUE, "java.lang.Boolean", "TRUE", BOOLEAN, true)
            for (name in listOf("showAdsBefore", "showAdsAfter", "useSystemAppRules", "showSafeModeTip")) {
                val id = field(cloud, name, BOOLEAN)
                fields[id] = InstallerField(id, cloud, name, BOOLEAN, false, setOf(name))
            }
            classes[cloud] = InstallerClass(cloud, "java.lang.Object", fields = listOf(beforeField, afterField, rulesField, safeField))
            classes[activity] = InstallerClass(activity, "android.app.Activity")
            classes[factory] = InstallerClass(factory, "java.lang.Object")
            classes["fixture.AdCard"] = InstallerClass("fixture.AdCard", "fixture.ViewModel")
            classes["fixture.ViewModel"] = InstallerClass("fixture.ViewModel", "java.lang.Object")
            fun schema(type: String, roles: List<Pair<String, String>>) {
                val members = roles.map { (name, shape) -> InstallerField(field(type, name, shape), type, name, shape, false, setOf(name)) }
                members.forEach { fields[it.descriptor] = it }
                classes[type] = InstallerClass(type, "java.lang.Object", fields = members.map { it.descriptor })
            }
            schema(adPayload, listOf("packageName" to "java.lang.String", "iconUrl" to "java.lang.String", "landingPageUrl" to "java.lang.String",
                "allDownloadNum" to "long", "viewMonitorUrls" to "java.lang.String[]", "clickMonitorUrls" to "java.lang.String[]"))
            schema(adEntry, listOf("data" to adPayload))
            schema(adCollection, listOf("data" to LIST, "channel" to "java.lang.String", "layoutFormat" to "java.lang.String"))
            fields[cardPayloadField] = InstallerField(cardPayloadField, "fixture.AdCard", "payload", adEntry, false)
            classes["fixture.AdCard"] = classes.getValue("fixture.AdCard").copy(fields = listOf(cardPayloadField))
            listOf(
                InstallerField(holderField, cancelOwner, "holder", holder, false),
                InstallerField(adContainerField, holder, "flAdCard", "android.widget.FrameLayout", false, setOf("flAdCard")),
                InstallerField(titleField, holder, "tvCancelTitle", "android.widget.TextView", false, setOf("tvCancelTitle")),
                InstallerField(subtitleField, holder, "tvCancelSubtitle", "android.widget.TextView", false, setOf("tvCancelSubtitle")),
                InstallerField(showAdsField, cancelOwner, "showAds", "boolean", false),
                InstallerField(cancelAdField, cancelOwner, "ads", adCollection, false),
            ).forEach { fields[it.descriptor] = it }
            classes[holder] = InstallerClass(holder, "java.lang.Object", fields = listOf(adContainerField, titleField, subtitleField))
            classes[cancelOwner] = InstallerClass(cancelOwner, "fixture.ViewModel", fields = listOf(holderField, showAdsField, cancelAdField))
            listOf(equal, before, constructor, analytics, beforeConsumer, after, merge, promotion, layout, container, title, subtitle, cancel, binder).forEach(::add)
        }
        fun add(m: InstallerMethod) { methods[m.descriptor] = m; classes[m.owner]?.let { classes[m.owner] = it.copy(methods = (it.methods + m.descriptor).distinct()) } }
        fun replace(m: InstallerMethod) { methods[m.descriptor] = m }
        override fun findMethods(query: InstallerMethodQuery): List<InstallerMethod> = methods.values.filter { m ->
            (query.owner == null || query.owner == m.owner) && (query.returnType == null || query.returnType == m.returnType) &&
                (query.isStatic == null || query.isStatic == m.isStatic) && (query.parameterCount == null || query.parameterCount == m.parameterTypes.size) &&
                (query.parameterTypes == null || query.parameterTypes.size == m.parameterTypes.size && query.parameterTypes.zip(m.parameterTypes).all { (a, b) -> a == null || a == b }) &&
                m.strings.containsAll(query.strings) && query.numbers.isEmpty()
        }
        override fun method(descriptor: String) = methods[descriptor]
        override fun clazz(name: String) = classes[name]
        override fun field(descriptor: String) = fields[descriptor]
        override fun callers(descriptor: String) = methods.values.filter { descriptor in it.invokes }

        fun renamed(): InstallerDexIndex {
            fun text(value: String): String = value.replace("fixture.", "relocated.").replace("fixture/", "relocated/")
            val methodIds = methods.mapValues { (_, m) -> desc(text(m.owner), if (m.name == "<init>") m.name else "m${methods.keys.indexOf(m.descriptor)}", text(m.returnType), m.parameterTypes.map(::text)) }
            val fieldIds = fields.mapValues { (_, f) -> if (f.owner.startsWith("fixture.")) field(text(f.owner), "f${fields.keys.indexOf(f.descriptor)}", text(f.type)) else f.descriptor }
            val movedMethods = methods.values.map { m -> m.copy(descriptor = methodIds.getValue(m.descriptor), owner = text(m.owner), name = if (m.name == "<init>") m.name else "m${methods.keys.indexOf(m.descriptor)}", returnType = text(m.returnType), parameterTypes = m.parameterTypes.map(::text), invokes = m.invokes.map { methodIds[it] ?: it }, readFields = m.readFields.map { fieldIds[it] ?: it }, writtenFields = m.writtenFields.map { fieldIds[it] ?: it }) }.associateBy { it.descriptor }
            val movedFields = fields.values.map { f -> f.copy(descriptor = fieldIds.getValue(f.descriptor), owner = text(f.owner), name = if (f.owner.startsWith("fixture.")) "f${fields.keys.indexOf(f.descriptor)}" else f.name, type = text(f.type)) }.associateBy { it.descriptor }
            val movedClasses = classes.values.map { c -> c.copy(name = text(c.name), superName = c.superName?.let(::text), methods = c.methods.map { methodIds.getValue(it) }, fields = c.fields.map { fieldIds.getValue(it) }) }.associateBy { it.name }
            return object : InstallerDexIndex {
                override fun findMethods(query: InstallerMethodQuery) = movedMethods.values.filter { m -> (query.owner == null || query.owner == m.owner) && (query.returnType == null || query.returnType == m.returnType) && (query.parameterCount == null || query.parameterCount == m.parameterTypes.size) && (query.isStatic == null || query.isStatic == m.isStatic) && (query.parameterTypes == null || query.parameterTypes == m.parameterTypes) && m.strings.containsAll(query.strings) }
                override fun method(descriptor: String) = movedMethods[descriptor]
                override fun field(descriptor: String) = movedFields[descriptor]
                override fun clazz(name: String) = movedClasses[name]
                override fun callers(descriptor: String) = movedMethods.values.filter { descriptor in it.invokes }
            }
        }
    }

    private companion object {
        const val BOOLEAN = "java.lang.Boolean"
        const val LIST = "java.util.List"
        const val TRUE = "Ljava/lang/Boolean;->TRUE:Ljava/lang/Boolean;"
        const val ADD_ALL = "Ljava/util/List;->addAll(Ljava/util/Collection;)Z"
        const val IS_EMPTY = "Ljava/util/List;->isEmpty()Z"
        fun dex(type: String): String = if (type.endsWith("[]")) "[" + dex(type.removeSuffix("[]")) else when (type) {
            "void" -> "V"; "boolean" -> "Z"; "long" -> "J"; "int" -> "I"; else -> "L${type.replace('.', '/')};"
        }
        fun desc(owner: String, name: String, result: String, params: List<String>) = "${dex(owner)}->$name(${params.joinToString("") { dex(it) }})${dex(result)}"
        fun field(owner: String, name: String, type: String) = "${dex(owner)}->$name:${dex(type)}"
        fun method(owner: String, name: String, result: String, params: List<String> = emptyList(), static: Boolean = false,
            strings: Set<String> = emptySet(), invokes: List<String> = emptyList(), reads: List<String> = emptyList(), opcodes: List<Int> = emptyList()) =
            InstallerMethod(desc(owner, name, result, params), owner, name, result, params, static, strings = strings, invokes = invokes, readFields = reads, opcodes = opcodes)
    }
}
