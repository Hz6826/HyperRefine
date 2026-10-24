package cn.ianzb.hyperrefine.hook.systemui.statusbar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusBarIconRegistryTest {

    @Test
    fun componentsHaveUniqueIdsAndSlots() {
        val ids = StatusBarIconRegistry.components.map { it.id }
        assertEquals(StatusBarIconRegistry.components.size, ids.size)
        assertEquals(ids.size, ids.toSet().size)
        val slots = StatusBarIconRegistry.components.mapNotNull { it.slot }
        assertEquals(slots.size, slots.toSet().size)
    }

    @Test
    fun reorderableOrderKeepsKnownOrderAndAppendsMissing() {
        val snapshot = StatusBarIconSnapshot(
            enabled = true,
            order = listOf("mobile", "not-a-component", "wifi", "mobile"),
            configs = emptyMap(),
        )
        val order = snapshot.reorderableOrder()
        assertEquals("mobile", order.first())
        assertEquals("wifi", order[1])
        assertTrue("clock" !in order)
        assertTrue("battery" !in order)
        assertTrue("location" in order)
        assertEquals(order.size, order.toSet().size)
    }

    @Test
    fun sidesFollowInstalledStatusBarLayout() {
        assertEquals(
            listOf("operator", "clock", "pad_clock", "notifications"),
            StatusBarIconRegistry.left.map { it.id },
        )
        val right = StatusBarIconRegistry.right.map { it.id }
        assertEquals("network_speed", right.first())
        assertEquals("mute", right[1])
        assertEquals(listOf("mute", "headset", "nfc"), right.subList(1, 4))
        assertEquals("wifi", right[right.lastIndex - 2])
        assertEquals("battery", right[right.lastIndex - 1])
        assertEquals("privacy", right.last())
    }

    @Test
    fun modeOrderMatchesVisibilitySemantics() {
        assertEquals(
            listOf("system", "always", "cc_only_hide", "cc_only_show", "hidden"),
            StatusBarIconMode.entries.map { it.value },
        )
    }

    @Test
    fun unifiedDisplayDrivesRuntimeMode() {
        assertEquals(
            StatusBarIconMode.ALWAYS,
            normalizedMode("always", StatusBarIconMode.SYSTEM),
        )
        assertEquals(
            StatusBarIconMode.HIDDEN,
            normalizedMode("hidden", StatusBarIconMode.ALWAYS),
        )
        assertEquals(
            StatusBarIconMode.SYSTEM,
            normalizedMode("condition:connected", StatusBarIconMode.ALWAYS),
        )
        assertEquals(
            StatusBarIconMode.SYSTEM,
            normalizedMode("", StatusBarIconMode.SYSTEM),
        )
    }

    @Test
    fun modeAndLogicFallBackToSafeDefaults() {
        assertEquals(StatusBarIconMode.SYSTEM, StatusBarIconMode.from("broken"))
        assertEquals(StatusBarIconLogic.DEFAULT, StatusBarIconLogic.from(null))
        assertEquals(StatusBarIconCondition.NONE, StatusBarIconCondition.from("broken"))
    }
}
