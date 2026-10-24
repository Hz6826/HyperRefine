package cn.ianzb.hyperrefine.hook.systemui.statusbar

object StatusBarKeys {
    const val TARGET = "com.android.systemui"
    const val WIFI_ACTIVITY = "statusbar_wifi_hide_activity"
    const val HIDE_TYPE = "statusbar_mobile_hide_type"
    const val DUAL_ROWS = "statusbar_mobile_dual_rows"
    const val DUAL_SCALE = "statusbar_mobile_dual_scale"
    const val DUAL_LEFT_GAP = "statusbar_mobile_dual_left_gap"
    const val DUAL_RIGHT_GAP = "statusbar_mobile_dual_right_gap"
    const val DUAL_OFFSET = "statusbar_mobile_dual_offset"
    // Deliberately do not read the retired style/bold preference, even from an older installation.
    // DUAL_OFFSET 仍保留给旧安装读取，但不再属于当前 UI 键集合。
    val DUAL_KEYS = listOf(DUAL_ROWS, DUAL_SCALE, DUAL_LEFT_GAP, DUAL_RIGHT_GAP)
    val ALL = listOf(WIFI_ACTIVITY, HIDE_TYPE) + DUAL_KEYS
}
