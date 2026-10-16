package cn.ianzb.hyperrefine.experimental.packageinstaller

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import cn.ianzb.hyperrefine.R
import cn.ianzb.hyperrefine.hook.packageinstaller.PackageInstallerKeys
import cn.ianzb.hyperrefine.ui.component.QuickActionsAction
import cn.ianzb.hyperrefine.ui.component.pref.HookOptionView
import cn.ianzb.hyperrefine.ui.component.pref.HookSection
import cn.ianzb.hyperrefine.ui.component.pref.HookSectionCard
import cn.ianzb.hyperrefine.ui.screen.subpage.BaseSubPageActivity
import cn.ianzb.hyperrefine.ui.util.LocalSubPageScrollBehavior
import cn.ianzb.hyperrefine.ui.util.pageScrollModifiers
import top.yukonga.miuix.kmp.basic.BasicComponent

class PackageInstallerActivity : BaseSubPageActivity() {

    override val titleRes: Int = R.string.section_packageinstaller

    override val topBarActions: (@Composable () -> Unit)? =
        { QuickActionsAction(listOf(PackageInstallerKeys.PACKAGE_NAME)) }

    @Composable
    override fun SubPageContent(isBlurEnabled: Boolean, contentPadding: PaddingValues) {
        val scrollBehavior = LocalSubPageScrollBehavior.current
        val section = HookSection(specs = packageInstallerSpecs())
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
                HookSectionCard(HookSection(specs = emptyList())) {
                    BasicComponent(title = stringResource(R.string.installer_experiment_notice))
                }
            }
            item {
                HookSectionCard(section) {
                    section.specs.forEach { HookOptionView(it) }
                }
            }
            item { InstallerDiagnosticCard() }
        }
    }
}
