package cn.ianzb.hyperrefine.prefs

import cn.ianzb.hyperrefine.hook.systemui.statusbar.StatusBarIconKeys

/**
 * 一次性配置迁移。应用启动时执行，已执行的迁移由标记键去重，不会重复改写用户配置。
 */
object PrefsMigrations {

    /** 百分比「显示位置」的各个位置前缀（与 `PercentLocation` 保持一致）。 */
    private val POSITION_LOCATIONS = listOf("cc_volume", "cc_brightness", "side_volume")

    /** 旧版本默认值。 */
    private const val OLD_DEFAULT_POSITION = 100f

    /** 新版本默认值。 */
    private const val NEW_DEFAULT_POSITION = 90f

    private const val MARKER_POSITION_90 = "migration_position_default_90"
    private const val MARKER_HORIZONTAL_CLOCK = "migration_horizontal_clock_empty"

    /** 执行所有迁移。 */
    fun run() {
        migratePositionDefaultTo90()
        migrateHorizontalClockDefault()
    }

    /**
     * 百分比「显示位置」默认值由 100 调整为 90。
     *
     * 旧版本默认值 100（紧贴上边缘）；新版本默认 90。把仍为旧默认值 100 的存量用户改为 90，
     * 用户主动调整过的值不受影响。
     */
    private fun migratePositionDefaultTo90() {
        if (PrefsStore.getBoolean(MARKER_POSITION_90, false)) return
        POSITION_LOCATIONS.forEach { location ->
            val key = "${location}_position"
            if (PrefsStore.getFloat(key, Float.NaN) == OLD_DEFAULT_POSITION) {
                PrefsStore.put(key, NEW_DEFAULT_POSITION)
            }
        }
        PrefsStore.put(MARKER_POSITION_90, true)
    }

    /**
     * 旧版本把 `HH:mm EEE MMMd` 当成横屏默认值并可能写入配置；
     * 新版本要求留空跟随系统，这里只清除仍等于该旧默认值的存量配置。
     */
    private fun migrateHorizontalClockDefault() {
        if (PrefsStore.getBoolean(MARKER_HORIZONTAL_CLOCK, false)) return
        val key = StatusBarIconKeys.CLOCK_PATTERN_HORIZONTAL
        if (PrefsStore.getString(key, "") == StatusBarIconKeys.LEGACY_HORIZONTAL_CLOCK_PATTERN) {
            PrefsStore.remove(key)
        }
        PrefsStore.put(MARKER_HORIZONTAL_CLOCK, true)
    }
}
