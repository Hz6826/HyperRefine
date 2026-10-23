package cn.ianzb.hyperrefine.ui.screen.features

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import cn.ianzb.hyperrefine.R
import cn.ianzb.hyperrefine.hook.voiceassist.VoiceAssistImeKeys
import cn.ianzb.hyperrefine.prefs.ConfigState
import cn.ianzb.hyperrefine.ui.component.QuickActionsAction
import cn.ianzb.hyperrefine.ui.component.pref.HookOptionView
import cn.ianzb.hyperrefine.ui.component.pref.HookSection
import cn.ianzb.hyperrefine.ui.component.pref.HookSectionCard
import cn.ianzb.hyperrefine.ui.component.pref.ensureScopeFor
import cn.ianzb.hyperrefine.ui.screen.subpage.BaseSubPageActivity
import cn.ianzb.hyperrefine.ui.util.LocalSubPageScrollBehavior
import cn.ianzb.hyperrefine.ui.util.pageScrollModifiers
import cn.ianzb.hyperrefine.xposed.XposedServiceManager

/**
 * 「超级小爱输入法」二级页。
 *
 * 目前包含「全局高级材质」：所有应用都使用输入法顶部圆角与背景模糊。
 */
class VoiceAssistImeActivity : BaseSubPageActivity() {

    override val titleRes: Int = R.string.voice_assist_ime_title

    override val topBarActions: (@Composable () -> Unit)? =
        { QuickActionsAction(listOf(VoiceAssistImeKeys.PKG_VOICE_ASSIST_IME)) }

    @Composable
    override fun SubPageContent(
        isBlurEnabled: Boolean,
        contentPadding: PaddingValues,
    ) {
        val scrollBehavior = LocalSubPageScrollBehavior.current
        val spec = featureSpec(KEY_VOICE_ASSIST_IME_GLOBAL_MATERIAL)
        val activated = XposedServiceManager.isActivated
        val inScope = XposedServiceManager.isInScope(VoiceAssistImeKeys.PKG_VOICE_ASSIST_IME)

        // 开关打开但作用域被重置（例如重装模块）时，进入本页自动补一次申请。
        LaunchedEffect(activated, inScope, spec) {
            if (activated && !inScope && ConfigState.bool(spec.key, false)) {
                ensureScopeFor(spec)
            }
        }

        val section = HookSection(
            titleRes = R.string.section_voice_assist_ime_appearance,
            specs = listOf(spec),
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (scrollBehavior != null) {
                        Modifier.pageScrollModifiers(
                            showTopAppBar = true,
                            topAppBarScrollBehavior = scrollBehavior,
                        )
                    } else {
                        Modifier
                    }
                ),
            contentPadding = contentPadding,
        ) {
            item {
                HookSectionCard(section) {
                    section.specs.forEach { spec ->
                        HookOptionView(spec = spec)
                    }
                }
            }
        }
    }
}
