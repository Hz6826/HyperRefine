package cn.ianzb.hyperrefine.ui.screen.features

import android.content.Intent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import cn.ianzb.hyperrefine.R
import cn.ianzb.hyperrefine.hook.miuix.MiuixAppLoad
import cn.ianzb.hyperrefine.ui.component.QuickActionsAction
import cn.ianzb.hyperrefine.ui.component.pref.HookOptionView
import cn.ianzb.hyperrefine.ui.component.pref.HookSection
import cn.ianzb.hyperrefine.ui.component.pref.HookSectionCard
import cn.ianzb.hyperrefine.ui.screen.subpage.BaseSubPageActivity
import cn.ianzb.hyperrefine.ui.util.LocalSubPageScrollBehavior
import cn.ianzb.hyperrefine.ui.util.pageScrollModifiers

/**
 * 「应用美化」二级页。
 *
 * 收纳针对各系统应用（MIUIX）的外观类功能。
 */
class AppBeautifyActivity : BaseSubPageActivity() {

    override val titleRes: Int = R.string.feature_app_beautify

    override val topBarActions: (@Composable () -> Unit)? =
        { QuickActionsAction(MiuixAppLoad.MIUIX_PACKAGES) }

    @Composable
    override fun SubPageContent(
        isBlurEnabled: Boolean,
        contentPadding: PaddingValues,
    ) {
        val context = LocalContext.current
        val scrollBehavior = LocalSubPageScrollBehavior.current
        val blurSection = HookSection(
            titleRes = R.string.section_app_blur,
            specs = listOf(
                featureSpec(KEY_TOP_BAR_BLUR),
            ),
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
                HookSectionCard(blurSection) {
                    blurSection.specs.forEach { spec ->
                        HookOptionView(spec = spec) {
                            context.startActivity(Intent(context, TopBarBlurActivity::class.java))
                        }
                    }
                }
            }
        }
    }
}
