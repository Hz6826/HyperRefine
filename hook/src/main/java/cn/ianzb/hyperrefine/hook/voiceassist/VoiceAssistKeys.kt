package cn.ianzb.hyperrefine.hook.voiceassist

/** 超级小爱（`com.miui.voiceassist`）功能配置键。 */
object VoiceAssistKeys {

    /** 目标包：超级小爱。 */
    const val PKG_VOICE_ASSIST = "com.miui.voiceassist"

    /** 识屏开启动画圆角适配。 */
    const val SR_ANIMATION_RADIUS = ScreenRecognitionRadiusHook.KEY

    /** 长按底部小白条打开超级小爱灵感球（替代原生长按识屏）。 */
    const val LONG_PRESS_HOME_INTENT_BALL = "voice_assist_long_press_home_intent_ball"

    /** 双击底部小白条打开聊天时，补上与长按电源键相同的满血光效。 */
    const val DOUBLE_CLICK_CHAT_FULL_EFFECT = "voice_assist_double_click_chat_full_effect"
}
