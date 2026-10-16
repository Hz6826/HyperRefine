package cn.ianzb.hyperrefine.experimental.packageinstaller

import cn.ianzb.hyperrefine.R
import cn.ianzb.hyperrefine.hook.packageinstaller.PackageInstallerKeys
import cn.ianzb.hyperrefine.prefs.OptionSpec
import cn.ianzb.hyperrefine.prefs.OptionType

/** Six independent installer options, shared by the page and its search entry. */
internal fun packageInstallerSpecs(): List<OptionSpec> = listOf(
    installerSwitch(
        PackageInstallerKeys.DISABLE_ADS,
        R.string.packageinstaller_disable_ads,
        R.string.packageinstaller_disable_ads_summary,
    ),
    installerSwitch(
        PackageInstallerKeys.DISABLE_RISK,
        R.string.packageinstaller_disable_risk,
        R.string.packageinstaller_disable_risk_summary,
    ),
    installerSwitch(
        PackageInstallerKeys.BLOCK_CLOUD,
        R.string.packageinstaller_block_cloud,
        R.string.packageinstaller_block_cloud_summary,
    ),
    installerSwitch(
        PackageInstallerKeys.DISABLE_GUARD,
        R.string.packageinstaller_disable_guard,
        R.string.packageinstaller_disable_guard_summary,
    ),
    installerSwitch(
        PackageInstallerKeys.DISABLE_FREQUENT_CHECK,
        R.string.packageinstaller_disable_frequent_check,
        R.string.packageinstaller_disable_frequent_check_summary,
    ),
    installerSwitch(
        PackageInstallerKeys.BLOCK_UPLOAD,
        R.string.packageinstaller_block_upload,
        R.string.packageinstaller_block_upload_summary,
    ),
)

private fun installerSwitch(key: String, titleRes: Int, summaryRes: Int) = OptionSpec(
    key = key,
    type = OptionType.SWITCH,
    titleRes = titleRes,
    summaryRes = summaryRes,
    defaultBoolean = false,
    targetPackages = listOf(PackageInstallerKeys.PACKAGE_NAME),
    showStatus = true,
)
