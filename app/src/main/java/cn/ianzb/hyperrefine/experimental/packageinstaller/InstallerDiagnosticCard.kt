package cn.ianzb.hyperrefine.experimental.packageinstaller

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import cn.ianzb.hyperrefine.R
import cn.ianzb.hyperrefine.hook.packageinstaller.InstallerCodeIdentity
import cn.ianzb.hyperrefine.hook.packageinstaller.InstallerDiagnosticContract as Contract
import cn.ianzb.hyperrefine.hook.packageinstaller.PackageInstallerKeys
import cn.ianzb.hyperrefine.prefs.ConfigState
import cn.ianzb.hyperrefine.prefs.PrefsStore
import cn.ianzb.hyperrefine.xposed.XposedServiceManager
import java.text.DateFormat
import java.util.Date
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Text as MiuixText

private data class InstallerLocalProbe(
    val checkedAt: Long,
    val bootCount: Int,
    val moduleVersion: String,
    val installerVersion: String,
    val localMask: String,
    val remoteMask: String,
    val identity: String,
    val viewHandlers: String,
    val installHandlers: String,
)

private fun collectInstallerHandlers(pm: PackageManager, action: String): String = runCatching {
    val intent = Intent(action).setDataAndType(
        "content://cn.ianzb.hyperrefine.diagnostic/probe.apk".toUri(),
        "application/vnd.android.package-archive",
    )
    val resolved = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
    val candidates = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        .mapNotNull { it.activityInfo?.let { activity -> "${activity.packageName}/${activity.name}".take(384) } }
        .distinct().sorted()
    val chosen = resolved?.let { "${it.packageName}/${it.name}".take(384) } ?: "none"
    "resolved=$chosen; candidates=${candidates.size}\n${candidates.take(12).joinToString("\n")}"
}.getOrElse { "unavailable (${it.javaClass.simpleName})" }

/** Read installed component metadata and resolve two fixed probe intents. Never launch or open the URI. */
private fun collectInstallerProbe(context: Context): InstallerLocalProbe {
    val pm = context.packageManager
    val module = runCatching {
        val info = pm.getPackageInfo(context.packageName, 0)
        "${info.versionName?.take(128)} (${info.longVersionCode})"
    }.getOrElse { "unavailable (${it.javaClass.simpleName})" }
    val installer = runCatching { pm.getPackageInfo(Contract.TARGET_PACKAGE, 0) }
    val version = installer.fold(
        { "${it.versionName?.take(128)} (${it.longVersionCode})" },
        { "unavailable (${it.javaClass.simpleName})" },
    )
    val identity = runCatching {
        val info = pm.getApplicationInfo(Contract.TARGET_PACKAGE, 0)
        val result = ZipFile(info.sourceDir).use { InstallerCodeIdentity().inspect(it) }
        "readable=${result.readable}; diagnostic_only=true; splits=${info.splitSourceDirs?.size ?: 0}\n${result.diagnostic.take(4_096)}"
    }.getOrElse { "unavailable (${it.javaClass.simpleName})" }
    val localMask = runCatching {
        val mask = PackageInstallerKeys.ALL.mapIndexed { index, key ->
            if (PrefsStore.getBoolean(key, false)) 1 shl index else 0
        }.fold(0, Int::or)
        "0x${mask.toString(16).padStart(2, '0')}"
    }.getOrElse { "unavailable (${it.javaClass.simpleName})" }
    val remoteMask = runCatching {
        val service = XposedServiceManager.getService() ?: return@runCatching "unavailable (service unbound)"
        val prefs = service.getRemotePreferences(PrefsStore.REMOTE_GROUP)
        val mask = PackageInstallerKeys.ALL.mapIndexed { index, key ->
            if (prefs.getBoolean(PrefsStore.key(key), false)) 1 shl index else 0
        }.fold(0, Int::or)
        val present = PackageInstallerKeys.ALL.count { prefs.contains(PrefsStore.key(it)) }
        "0x${mask.toString(16).padStart(2, '0')}; present=$present/6"
    }.getOrElse { "unavailable (${it.javaClass.simpleName})" }
    return InstallerLocalProbe(
        System.currentTimeMillis(), InstallerDiagnosticStore.currentBoot(context), module, version, localMask, remoteMask, identity,
        collectInstallerHandlers(pm, Intent.ACTION_VIEW), collectInstallerHandlers(pm, Intent.ACTION_INSTALL_PACKAGE),
    )
}

@Composable
internal fun InstallerDiagnosticCard() {
    val context = LocalContext.current
    val resources = LocalResources.current
    var refreshToken by remember { mutableIntStateOf(0) }
    var probe by remember { mutableStateOf<InstallerLocalProbe?>(null) }
    var loading by remember { mutableStateOf(false) }
    val lastReport by InstallerDiagnosticStore.latest.collectAsState()
    val serviceBound = XposedServiceManager.isActivated
    val inScope = Contract.TARGET_PACKAGE in XposedServiceManager.scope
    val remoteAttached = PrefsStore.isRemoteAttached()
    val enabled = PackageInstallerKeys.ALL.count { ConfigState.bool(it, false) }

    LaunchedEffect(refreshToken) {
        loading = true
        InstallerDiagnosticStore.reload(context)
        XposedServiceManager.refreshScope()
        probe = withContext(Dispatchers.IO) { collectInstallerProbe(context.applicationContext) }
        loading = false
    }

    val text = buildString {
        appendLine(resources.getString(R.string.installer_diag_build, probe?.moduleVersion ?: "…", Contract.BUILD_ID))
        appendLine(resources.getString(R.string.installer_diag_service, serviceBound.toString(), remoteAttached.toString(), inScope.toString()))
        appendLine(resources.getString(R.string.installer_diag_settings, enabled, Build.VERSION.SDK_INT))
        probe?.let { local ->
            appendLine(resources.getString(R.string.installer_diag_checked_at, formatDiagnosticTime(local.checkedAt), local.bootCount))
            appendLine(resources.getString(R.string.installer_diag_masks, local.localMask, local.remoteMask))
            appendLine(resources.getString(R.string.installer_diag_installer, local.installerVersion))
            appendLine(resources.getString(R.string.installer_diag_identity, local.identity))
            appendLine(resources.getString(R.string.installer_diag_view_handlers, local.viewHandlers))
            appendLine(resources.getString(R.string.installer_diag_install_handlers, local.installHandlers))
        }
        val report = lastReport
        if (report == null) {
            appendLine(resources.getString(R.string.installer_diag_no_report))
        } else {
            appendLine(resources.getString(
                R.string.installer_diag_latest_report,
                formatDiagnosticTime(report.receivedAtMillis), report.sourcePid,
                ((System.currentTimeMillis() - report.receivedAtMillis).coerceAtLeast(0) / 1_000L),
            ))
            report.trace.forEach { appendLine(it) }
            appendLine(resources.getString(R.string.installer_diag_not_live))
        }
        append(resources.getString(R.string.installer_diag_boundary))
    }.let { if (it.length <= 32_768) it else it.take(32_384) + "\n" + resources.getString(R.string.installer_diag_truncated) }

    Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            MiuixText(text = stringResource(R.string.installer_diag_title), style = MiuixTheme.textStyles.body2)
            SelectionContainer {
                MiuixText(text = text, style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    text = stringResource(if (loading) R.string.installer_diag_loading else R.string.installer_diag_refresh),
                    onClick = { if (!loading) refreshToken++ },
                    minWidth = 0.dp,
                )
                TextButton(
                    text = stringResource(R.string.installer_diag_copy),
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("HyperRefine installer diagnostics", text))
                        Toast.makeText(context, R.string.installer_diag_copied, Toast.LENGTH_SHORT).show()
                    },
                    minWidth = 0.dp,
                )
                TextButton(
                    text = stringResource(R.string.installer_diag_clear),
                    onClick = { InstallerDiagnosticStore.clear(context) },
                    minWidth = 0.dp,
                )
            }
        }
    }
}

private fun formatDiagnosticTime(value: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(value))
