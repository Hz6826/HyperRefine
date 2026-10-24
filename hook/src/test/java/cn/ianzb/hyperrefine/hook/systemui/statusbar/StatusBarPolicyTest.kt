package cn.ianzb.hyperrefine.hook.systemui.statusbar

import org.junit.Assert.*
import org.junit.Test

class StatusBarPolicyTest {
    @Test fun retainedOptionsHaveUniqueKeysAndOnlySystemUiIsTargeted() {
        assertEquals(listOf(StatusBarKeys.WIFI_ACTIVITY, StatusBarKeys.HIDE_TYPE) + StatusBarKeys.DUAL_KEYS, StatusBarKeys.ALL)
        assertEquals(6, StatusBarKeys.ALL.distinct().size)
        assertFalse(StatusBarKeys.ALL.any { it.contains("style") || it.contains("bold") })
        assertEquals("com.android.systemui", StatusBarKeys.TARGET)
    }

    open class Parent { private var field = 1; open fun build(value: String) {}; fun build(value: Int) {} }
    class Child : Parent() { override fun build(value: String) {} }
    @Test fun memberResolutionValidatesSignaturesAndSupportsInheritedPrivateFields() {
        val child = Child()
        assertEquals(1, StatusBarMembers.field(Child::class.java, "field").getInt(child))
        val methods = StatusBarMembers.methods(Child::class.java, "build") {
            it.parameterTypes.contentEquals(arrayOf(String::class.java)) && it.returnType == Void.TYPE
        }
        assertEquals(1, methods.size)
        assertEquals(Child::class.java, methods.single().declaringClass)
    }

    @Test(expected = IllegalStateException::class)
    fun missingCompatibleHookMethodIsRejected() {
        StatusBarMembers.methods(Child::class.java, "build") { it.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType)) }
    }

    class MutableFlow(val value: Boolean)
    class OptimizedReadonly private constructor(val flow: MutableFlow)
    class UnoptimizedReadonly(val flow: MutableFlow, val job: Any?)
    class InvalidReadonly(val value: Int)

    @Test fun singleSimUnknownStrengthAndInvisibleCellKeepNativeLayout() {
        val first = StatusBarPolicy.Signal(10, 101, true)
        assertNull(StatusBarPolicy.pair(listOf(first), 10))
        assertNull(StatusBarPolicy.pair(listOf(first, StatusBarPolicy.Signal(20, 0, true)), 10))
        assertNull(StatusBarPolicy.pair(listOf(first, StatusBarPolicy.Signal(20, 102, false)), 10))
    }

    @Test fun satelliteAndVoWifiOnlyRowsDoNotBecomeCellularBars() {
        val first = StatusBarPolicy.Signal(10, 101, true)
        val second = StatusBarPolicy.Signal(20, 102, true)
        assertNull(StatusBarPolicy.pair(listOf(first, second.copy(satellite = true)), 10))
        assertNull(StatusBarPolicy.pair(listOf(first, second.copy(cellularMode = false)), 10))
        assertNull(StatusBarPolicy.pair(listOf(first, second.copy(visible = false, rootVisible = true)), 10))
    }

    @Test fun dataSimChangesTheNativeCarrierButPhysicalSimStrengthOrderStaysFixed() {
        val first = StatusBarPolicy.Signal(10, 101, true)
        val second = StatusBarPolicy.Signal(20, 102, true)
        assertEquals(first, StatusBarPolicy.pair(listOf(first, second), 10)?.primary)
        assertEquals(second, StatusBarPolicy.pair(listOf(first, second), 20)?.primary)
        assertEquals(DualSimArtworkPolicy.levels(0, 1, 5, 2), DualSimArtworkPolicy.levels(1, 0, 2, 5))
    }

    @Test fun hostPairVisibilityDistinguishesCellularSignalFromTheRoot() {
        assertEquals(StatusBarPolicy.Visibility(true, false), StatusBarMembers.mobileVisibility(true to false))
        assertEquals(StatusBarPolicy.Visibility(false, false), StatusBarMembers.mobileVisibility(false to true))
        assertEquals(StatusBarPolicy.Visibility(true, true), StatusBarMembers.mobileVisibility(true))
        assertNull(StatusBarMembers.mobileVisibility(true to "unknown"))
    }

    @Test fun romOptimizedReadonlyFlowConstructorRetainsTheReplacementFlow() {
        val source = MutableFlow(false)
        val wrapped = StatusBarMembers.readonlyFlow(OptimizedReadonly::class.java, source) as OptimizedReadonly
        assertSame(source, wrapped.flow)
        assertFalse(wrapped.flow.value)
    }

    @Test fun unoptimizedReadonlyFlowConstructorRemainsSupported() {
        val source = MutableFlow(false)
        val wrapped = StatusBarMembers.readonlyFlow(UnoptimizedReadonly::class.java, source) as UnoptimizedReadonly
        assertSame(source, wrapped.flow)
        assertNull(wrapped.job)
    }

    @Test(expected = NoSuchMethodException::class)
    fun unknownReadonlyFlowSignatureIsRejectedInsteadOfConstructingInvalidState() {
        StatusBarMembers.readonlyFlow(InvalidReadonly::class.java, MutableFlow(false))
    }
}
