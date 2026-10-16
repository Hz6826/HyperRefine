package cn.ianzb.hyperrefine.hook.packageinstaller

/** Independent installer business options; all default to false in both processes. */
object PackageInstallerKeys {
    const val PACKAGE_NAME = "com.miui.packageinstaller"
    const val DISABLE_ADS = "packageinstaller_disable_ads"
    const val DISABLE_RISK = "packageinstaller_disable_risk"
    const val BLOCK_CLOUD = "packageinstaller_block_cloud"
    const val DISABLE_GUARD = "packageinstaller_disable_guard"
    const val DISABLE_FREQUENT_CHECK = "packageinstaller_disable_frequent_check"
    const val BLOCK_UPLOAD = "packageinstaller_block_upload"

    val ALL = listOf(DISABLE_ADS, DISABLE_RISK, BLOCK_CLOUD, DISABLE_GUARD, DISABLE_FREQUENT_CHECK, BLOCK_UPLOAD)
}
