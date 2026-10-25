package cn.ianzb.hyperrefine.hook.systemui.statusbar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusBarIconRegistryTest {

    @Test
    fun componentsHaveUniqueIdsAndSlots() {
        assertEquals(47, StatusBarIconRegistry.components.size)
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
        assertEquals(
            listOf(
                "network_speed",
                "mute",
                "micphone",
                "headset",
                "mikey",
                "privacy_mode",
                "nfc",
                "gps",
                "missed_call",
                "managed_profile",
                "second_space",
                "cast",
                "location",
                "stealth",
                "tty",
                "alarm",
                "vpn",
                "ethernet",
                "bluetooth",
            ),
            right.take(19),
        )
        assertEquals("wifi", right[right.lastIndex - 2])
        assertEquals("battery", right[right.lastIndex - 1])
        assertEquals("privacy", right.last())
    }

    @Test
    fun everyNativeConfigSlotIsRepresentedOnce() {
        // config_statusBarIcons 中除 handle/handle_battery 与 demo 槽外的可见图标。
        val nativeSlots = listOf(
            "network_speed", "mute", "micphone", "headset", "mikey", "privacy_mode",
            "nfc", "gps", "missed_call", "managed_profile", "second_space", "cast",
            "location", "stealth", "tty", "alarm_clock", "vpn", "ethernet",
            "bluetooth", "bluetooth_handsfree_battery", "hotspot", "sound_box_group",
            "stereo", "sound_box_screen", "sound_box", "wireless_headset", "zen",
            "volume", "dist_compute", "camera", "glasses", "car", "tv", "pc", "pad",
            "phone", "hd", "airplane", "mobile", "no_sim", "wifi",
        )
        assertEquals(nativeSlots, StatusBarIconRegistry.right.mapNotNull { it.slot })
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
