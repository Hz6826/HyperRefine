package cn.ianzb.hyperrefine.hook.systemui.statusbar

/** 状态栏组件自身可用的状态条件；由组件能力矩阵决定，不做全局通用条件。 */
enum class StatusBarIconCondition(val value: String) {
    NONE("none"),
    CONNECTED("connected"),
    DISCONNECTED("disconnected"),
    ENABLED("enabled"),
    DISABLED("disabled"),
    DATA_ON("data_on"),
    DATA_OFF("data_off"),
    ALARM_WITHIN("alarm_within"),
    NO_ALARM("no_alarm"),
    CHARGING("charging"),
    NOT_CHARGING("not_charging"),
    TRAFFIC("traffic"),
    NO_TRAFFIC("no_traffic"),
    HAS_NOTIFICATIONS("has_notifications"),
    NO_NOTIFICATIONS("no_notifications"),
    ;

    companion object {
        fun from(value: String?): StatusBarIconCondition =
            entries.firstOrNull { it.value == value } ?: NONE
    }
}

enum class StatusBarIconMode(val value: String) {
    SYSTEM("system"),
    ALWAYS("always"),
    CC_ONLY_HIDE("cc_only_hide"),
    CC_ONLY_SHOW("cc_only_show"),
    HIDDEN("hidden"),
    ;

    companion object {
        fun from(value: String?): StatusBarIconMode =
            entries.firstOrNull { it.value == value } ?: SYSTEM
    }
}

enum class StatusBarIconLogic(val value: String) {
    DEFAULT("default"),
    INVERTED("inverted"),
    CUSTOM("custom"),
    ;

    companion object {
        fun from(value: String?): StatusBarIconLogic =
            entries.firstOrNull { it.value == value } ?: DEFAULT
    }
}

/**
 * HyperOS 4 状态栏中可由本模块安全枚举的组件。
 *
 * - [slot] 是 `StatusBarIconList` 的 slot 名；为 null 表示该组件由独立 View 承载。
 * - [side] 表示组件在原生布局中的归属；跨组移动会破坏 SystemUI 的索引和动画，
 *   因此不宣称支持跨组拖拽。
 * - [conditions] 只列出该组件自身有意义的条件。
 */
data class StatusBarIconComponent(
    val id: String,
    val slot: String?,
    val side: StatusBarIconSide,
    val conditions: List<StatusBarIconCondition> = emptyList(),
    val supportsLogic: Boolean = false,
    val reorderable: Boolean = false,
    val special: Boolean = false,
    /** 组件含由宿主绘制的文字，允许字重覆盖。 */
    val textual: Boolean = false,
    /** 控制器 hide list 使用的 slot；电池在原生 config 中是 handle_battery。 */
    val hideSlot: String? = null,
)

enum class StatusBarIconSide { LEFT, RIGHT }

object StatusBarIconRegistry {

    const val CLOCK = "clock"
    const val PAD_CLOCK = "pad_clock"
    const val OPERATOR = "operator"
    const val PRIVACY = "privacy"
    const val NOTIFICATIONS = "notifications"
    const val NETWORK_SPEED = "network_speed"
    const val WIFI = "wifi"
    const val MOBILE = "mobile"
    const val BLUETOOTH = "bluetooth"
    const val ALARM = "alarm"
    const val BATTERY = "battery"
    const val LOCATION = "location"
    const val HEADSET = "headset"
    const val VPN = "vpn"
    const val ZEN = "zen"
    const val MUTE = "mute"
    const val AIRPLANE = "airplane"
    const val HOTSPOT = "hotspot"
    const val ETHERNET = "ethernet"
    const val NFC = "nfc"
    const val CAST = "cast"
    const val IME = "ime"
    const val MANAGED_PROFILE = "managed_profile"
    const val MISSED_CALL = "missed_call"
    const val MICPHONE = "micphone"
    const val MIKEY = "mikey"
    const val PRIVACY_MODE = "privacy_mode"
    const val GPS = "gps"
    const val SECOND_SPACE = "second_space"
    const val STEALTH = "stealth"
    const val TTY = "tty"
    const val BLUETOOTH_BATTERY = "bluetooth_handsfree_battery"
    const val SOUND_BOX_GROUP = "sound_box_group"
    const val STEREO = "stereo"
    const val SOUND_BOX_SCREEN = "sound_box_screen"
    const val SOUND_BOX = "sound_box"
    const val WIRELESS_HEADSET = "wireless_headset"
    const val DIST_COMPUTE = "dist_compute"
    const val CAMERA = "camera"
    const val GLASSES = "glasses"
    const val CAR = "car"
    const val TV = "tv"
    const val PC = "pc"
    const val PAD = "pad"
    const val PHONE = "phone"
    const val NO_SIM = "no_sim"
    const val HD = "hd"

    val components: List<StatusBarIconComponent> = listOf(
        // 左侧：phone_status_bar_left_container 内是 operator、clock 与通知图标区。
        StatusBarIconComponent(OPERATOR, null, StatusBarIconSide.LEFT, special = true, textual = true),
        StatusBarIconComponent(CLOCK, null, StatusBarIconSide.LEFT, special = true, textual = true),
        StatusBarIconComponent(PAD_CLOCK, null, StatusBarIconSide.LEFT, special = true, textual = true),
        StatusBarIconComponent(NOTIFICATIONS, null, StatusBarIconSide.LEFT, special = true),

        // 右侧：statusIcons 的 slot 图标和电池；privacy_area 是根布局上 END 对齐的
        // 独立覆盖层，位于最右侧。
        // 以下 slot 顺序严格按本机 config_statusBarIcons 过滤后的出现顺序。
        StatusBarIconComponent(
            NETWORK_SPEED, "network_speed", StatusBarIconSide.RIGHT,
            conditions = listOf(StatusBarIconCondition.TRAFFIC, StatusBarIconCondition.NO_TRAFFIC),
            supportsLogic = true,
            reorderable = true,
            textual = true,
        ),
        StatusBarIconComponent(MUTE, "mute", StatusBarIconSide.RIGHT, reorderable = true),
        StatusBarIconComponent(HEADSET, "headset", StatusBarIconSide.RIGHT, reorderable = true),
        StatusBarIconComponent(NFC, "nfc", StatusBarIconSide.RIGHT, reorderable = true),
        StatusBarIconComponent(MICPHONE, "micphone", StatusBarIconSide.RIGHT, reorderable = true),
        StatusBarIconComponent(STEALTH, "stealth", StatusBarIconSide.RIGHT, reorderable = true),
        StatusBarIconComponent(LOCATION, "location", StatusBarIconSide.RIGHT, reorderable = true),
        StatusBarIconComponent(
            ALARM, "alarm_clock", StatusBarIconSide.RIGHT,
            conditions = listOf(
                StatusBarIconCondition.ALARM_WITHIN,
                StatusBarIconCondition.NO_ALARM,
            ),
            reorderable = true,
        ),
        StatusBarIconComponent(VPN, "vpn", StatusBarIconSide.RIGHT, reorderable = true),
        StatusBarIconComponent(
            BLUETOOTH, "bluetooth", StatusBarIconSide.RIGHT,
            conditions = listOf(
                StatusBarIconCondition.CONNECTED,
                StatusBarIconCondition.DISCONNECTED,
                StatusBarIconCondition.ENABLED,
                StatusBarIconCondition.DISABLED,
            ),
            supportsLogic = true,
            reorderable = true,
        ),
        StatusBarIconComponent(
            BLUETOOTH_BATTERY, "bluetooth_handsfree_battery", StatusBarIconSide.RIGHT,
            reorderable = true,
        ),
        StatusBarIconComponent(HOTSPOT, "hotspot", StatusBarIconSide.RIGHT, reorderable = true),
        StatusBarIconComponent(SOUND_BOX_GROUP, "sound_box_group", StatusBarIconSide.RIGHT, reorderable = true),
        StatusBarIconComponent(STEREO, "stereo", StatusBarIconSide.RIGHT, reorderable = true),
        StatusBarIconComponent(SOUND_BOX_SCREEN, "sound_box_screen", StatusBarIconSide.RIGHT, reorderable = true),
        StatusBarIconComponent(SOUND_BOX, "sound_box", StatusBarIconSide.RIGHT, reorderable = true),
        StatusBarIconComponent(WIRELESS_HEADSET, "wireless_headset", StatusBarIconSide.RIGHT, reorderable = true),
        StatusBarIconComponent(ZEN, "zen", StatusBarIconSide.RIGHT, reorderable = true),
        StatusBarIconComponent(DIST_COMPUTE, "dist_compute", StatusBarIconSide.RIGHT, reorderable = true),
        StatusBarIconComponent(CAMERA, "camera", StatusBarIconSide.RIGHT, reorderable = true),
        StatusBarIconComponent(GLASSES, "glasses", StatusBarIconSide.RIGHT, reorderable = true),
        StatusBarIconComponent(CAR, "car", StatusBarIconSide.RIGHT, reorderable = true),
        StatusBarIconComponent(TV, "tv", StatusBarIconSide.RIGHT, reorderable = true),
        StatusBarIconComponent(PC, "pc", StatusBarIconSide.RIGHT, reorderable = true),
        StatusBarIconComponent(PAD, "pad", StatusBarIconSide.RIGHT, reorderable = true),
        StatusBarIconComponent(PHONE, "phone", StatusBarIconSide.RIGHT, reorderable = true),
        StatusBarIconComponent(HD, "hd", StatusBarIconSide.RIGHT, reorderable = true),
        StatusBarIconComponent(AIRPLANE, "airplane", StatusBarIconSide.RIGHT, reorderable = true),
        StatusBarIconComponent(
            MOBILE, "mobile", StatusBarIconSide.RIGHT,
            conditions = listOf(
                StatusBarIconCondition.CONNECTED,
                StatusBarIconCondition.DISCONNECTED,
                StatusBarIconCondition.DATA_ON,
                StatusBarIconCondition.DATA_OFF,
            ),
            supportsLogic = true,
            reorderable = true,
        ),
        StatusBarIconComponent(NO_SIM, "no_sim", StatusBarIconSide.RIGHT, reorderable = true),
        StatusBarIconComponent(
            WIFI, "wifi", StatusBarIconSide.RIGHT,
            conditions = listOf(
                StatusBarIconCondition.CONNECTED,
                StatusBarIconCondition.DISCONNECTED,
                StatusBarIconCondition.ENABLED,
                StatusBarIconCondition.DISABLED,
            ),
            supportsLogic = true,
            reorderable = true,
        ),
        StatusBarIconComponent(
            BATTERY, null, StatusBarIconSide.RIGHT,
            conditions = listOf(StatusBarIconCondition.CHARGING, StatusBarIconCondition.NOT_CHARGING),
            supportsLogic = true,
            special = true,
            hideSlot = "handle_battery",
            // 电量百分比由 MiuiBatteryMeterView 自绘，不是 TextView；不提供无效的字重开关。
            textual = false,
        ),
        StatusBarIconComponent(PRIVACY, null, StatusBarIconSide.RIGHT, special = true),
    )

    val left: List<StatusBarIconComponent> = components.filter { it.side == StatusBarIconSide.LEFT }
    val right: List<StatusBarIconComponent> = components.filter { it.side == StatusBarIconSide.RIGHT }

    fun byId(id: String): StatusBarIconComponent? = components.firstOrNull { it.id == id }

    /** 隐私指示在控制中心由另一套视图绘制，不提供仅控制中心显示/隐藏条件。 */
    fun supportsControlCenterDisplay(id: String): Boolean = id != PRIVACY

    /** 字重滑块的推荐值：尽量贴近系统宿主默认字重，而不是用 0 表示“系统”。 */
    fun recommendedFontWeight(id: String): Int = when (id) {
        CLOCK, NETWORK_SPEED -> 500
        else -> 400
    }

    /** 字号滑块的推荐值；只在用户打开覆盖开关后生效。 */
    fun recommendedFontSize(id: String): Int = when (id) {
        CLOCK -> 14
        PAD_CLOCK -> 12
        NETWORK_SPEED -> 7
        OPERATOR -> 12
        else -> 12
    }

    fun slot(id: String): String? = byId(id)?.slot
}
