/*
 * SPDX-License-Identifier: AGPL-3.0-only
 * Feature references: HyperCeiler Contributors (2023-2026),
 * ReChronoRain/HyperCeiler@861be0473847d5c020102191f2c2891acda5d628.
 * Independent matching against ROM business logic; no proprietary implementation copied.
 */
package cn.ianzb.hyperrefine.hook.packageinstaller

import android.os.Handler
import android.os.Looper
import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.rule.HookSkippedException
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import io.github.libxposed.api.XposedInterface.HookHandle
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

/** Per-feature discovery, contracts and transactions; component hashes never decide compatibility. */
internal class InstallerBusinessHook @JvmOverloads constructor(
    private val enabled: List<String>,
    private val openIndex: (String) -> InstallerDexIndex = { InstallerDexKitIndex.open(it) },
    private val bannerString: (String) -> String? = ::installedInstallerString,
    private val adCardIdentity: (View) -> Boolean = ::installedAdCardIdentity,
) : BaseHook() {
    override val key = "packageinstaller_business"
    private val installedOptions = mutableListOf<String>()
    override val keys: List<String> get() = installedOptions.toList()
    private val reported = ConcurrentHashMap.newKeySet<String>()
    private class Point(val id: String, val method: Method, val callers: List<InstallerMethod>, val install: () -> HookHandle)
    private class Group(val name: String, val points: List<Point>, val displayOnly: Boolean = false)
    private class Plan(val option: String, val expectedGroups: Int, val groups: List<Group>)

    override fun init() {
        val plans = mutableListOf<Plan>()
        val resolved = mutableMapOf<String, Result<Point>>()
        val source = target.applicationInfo?.sourceDir ?: throw HookSkippedException("installer source unavailable")
        val index = try { openIndex(source) } catch (failure: Throwable) {
            InstallerDiagnosticReporter.record("discovery_unavailable type=${failure.javaClass.simpleName}")
            throw failure
        }
        try {
            val local = InstallerLocalRules(index)
            val nullProof = InstallerNullReturnProof(source)
            val network = InstallerNetworkRules(index) { method -> nullProof.hasNullReturn(method.descriptor) }
            val promotion = InstallerPromotionRules(index)
            val banner = InstallerGuardBannerRules(index)
            fun resolveGroup(option: String, name: String, ids: List<String>, displayOnly: Boolean = false): Group? =
                try {
                    // Discover both completion styles even if the first one fails. Mounting remains
                    // atomic for the group; a matched sibling is never mistaken for a registered hook.
                    val outcomes = ids.map { id ->
                        resolved.getOrPut(id) {
                            runCatching {
                                val match = when (id) {
                                    in LOCAL_POINTS -> local.resolve(id)
                                    "ads_before", "ads_after", "ads_cancel" -> promotion.resolve(id)
                                    "guard_banner" -> banner.resolve()
                                    else -> network.resolve(id)
                                }
                                val callers = runCatching { InstallerDeoptimization.callers(index, match) }
                                    .onFailure { InstallerDiagnosticReporter.record("caller_discovery_optional point=$id type=${it.javaClass.simpleName}") }
                                    .getOrDefault(emptyList())
                                prepare(match, callers).also {
                                    InstallerDiagnosticReporter.record("matched point=$id method=${match.method.descriptor}")
                                }
                            }
                        }
                    }
                    val points = outcomes.map { it.getOrThrow() }
                    check(points.map { it.method }.distinct().size == points.size) { "different business roles matched one method" }
                    Group(name, points, displayOnly)
                } catch (failure: Throwable) {
                    val reason = if (failure is InstallerMatchException) failure.message else failure.javaClass.simpleName
                    InstallerDiagnosticReporter.record("match_failed option=$option group=$name reason=$reason")
                    HookHelper.log("PackageInstaller[r9]: capability unavailable $option/$name", failure)
                    null
                }
            enabled.forEach { option ->
                val definitions = pointGroups(option)
                val groups = definitions.mapNotNull { (name, ids) -> resolveGroup(option, name, ids) }.toMutableList()
                if (option == PackageInstallerKeys.DISABLE_ADS) {
                    // Also suppress cached recommendation displays. These optional UI groups do
                    // not claim that layout requests stopped or complete the upload option.
                    resolveGroup(option, "display_only_before", listOf("ads_before"), displayOnly = true)?.let(groups::add)
                    resolveGroup(option, "display_only_after", listOf("ads_after"), displayOnly = true)?.let(groups::add)
                    resolveGroup(option, "display_only_cancel", listOf("ads_cancel"), displayOnly = true)?.let(groups::add)
                }
                plans += Plan(option, definitions.size, groups)
            }
            runCatching { nullProof.diagnosticLines() }
                .onSuccess { it.forEach(InstallerDiagnosticReporter::record) }
                .onFailure { InstallerDiagnosticReporter.record("probe layout_null_proof detail=unavailable") }
            if (resolved.values.any { it.isFailure }) {
                listOf(network::diagnosticLines, promotion::diagnosticLines).forEach { diagnostics ->
                    runCatching { diagnostics() }
                        .onSuccess { it.forEach(InstallerDiagnosticReporter::record) }
                        .onFailure { InstallerDiagnosticReporter.record("probe rule_details detail=${it.javaClass.simpleName}") }
                }
                runCatching { InstallerDiscoveryProbe.collect(index) }
                    .onSuccess { it.forEach(InstallerDiagnosticReporter::record) }
                    .onFailure { InstallerDiagnosticReporter.record("probe unavailable detail=${it.javaClass.simpleName}") }
            }
        } finally {
            runCatching { index.close() }
                .onFailure { InstallerDiagnosticReporter.record("discovery_close_failed type=${it.javaClass.simpleName}") }
        }
        // Discovery metadata is detached before the native bridge closes; callbacks never search DEX.
        val installed = linkedMapOf<Method, String>()
        var partialOptions = 0
        plans.forEach { plan ->
            val completed = mutableListOf<String>()
            val active = mutableListOf<String>()
            plan.groups.forEach { group ->
                try {
                    group.points.forEach { check(installed[it.method]?.let { id -> id == it.id } != false) { "incompatible shared business roles" } }
                    val fresh = group.points.filterNot { it.method in installed }
                    InstallerContract.installAtomically(fresh.map { point ->
                        {
                            InstallerDeoptimization.prepare(point.id, point.callers, target.classLoader)
                            val handle = point.install()
                            val remove: () -> Unit = { handle.unhook() }
                            remove
                        }
                    })
                    fresh.forEach {
                        installed[it.method] = it.id
                        InstallerDiagnosticReporter.registered(it.id)
                        InstallerDiagnosticReporter.record("registered point=${it.id}")
                    }
                    active += group.name
                    if (!group.displayOnly) completed += group.name
                } catch (failure: Throwable) {
                    InstallerDiagnosticReporter.record("installation_failed option=${plan.option} group=${group.name} type=${failure.javaClass.simpleName}")
                    HookHelper.log("PackageInstaller[r9]: installation failed ${plan.option}/${group.name}", failure)
                }
            }
            if (completed.size == plan.expectedGroups) {
                installedOptions += plan.option
                InstallerDiagnosticReporter.record("installed option=${plan.option}")
            } else if (active.isNotEmpty()) {
                partialOptions++
                InstallerDiagnosticReporter.record("partial option=${plan.option} groups=${completed.size}/${plan.expectedGroups} active=${active.joinToString()}; full_status=false")
            }
        }
        val summary = "summary installed=${installedOptions.size}/${enabled.size} partial=$partialOptions points=${installed.values.sorted().joinToString()}"
        InstallerDiagnosticReporter.record(summary)
        HookHelper.log("PackageInstaller[r9]: $summary")
        if (installed.isEmpty()) throw HookSkippedException("no verified installer capability installed")
    }

    private fun prepare(data: InstallerResolvedPoint, callers: List<InstallerMethod>): Point {
        val method = InstallerReflection.method(data.method, target.classLoader)
        fun hit(event: InstallerHitLedger.Event) = InstallerDiagnosticReporter.hit(data.id, event)
        fun point(install: () -> HookHandle) = Point(data.id, method, callers, install)
        fun constant(value: Any?) = point {
            HookHelper.hookBefore(method) { param ->
                hit(InstallerHitLedger.Event.CALL)
                param.setResultValue(value)
                hit(InstallerHitLedger.Event.BLOCK)
                reportOnce(data.id, "blocked ${data.id}")
            }
        }
        return when (data.id) {
            "risk_scan", "ads_before", "ads_after" -> { check(method.returnType == Boolean::class.javaPrimitiveType); constant(false) }
            "frequent_check" -> { check(method.returnType == Int::class.javaPrimitiveType); constant(0) }
            "layout_sync" -> { check(method.returnType == Any::class.java); constant(null) }
            "cloud_v2", "avl_upload" -> { check(method.returnType == Void.TYPE); constant(null) }
            "layout_async" -> {
                check(method.returnType == Void.TYPE && method.parameterCount >= 2)
                val callback = InstallerReflection.method(checkNotNull(data.callback), target.classLoader)
                check(callback.returnType == Void.TYPE && callback.parameterCount == 1 &&
                    method.parameterTypes.last() == callback.declaringClass)
                val main = Handler(Looper.getMainLooper())
                point {
                    HookHelper.hookBefore(method) { param ->
                        hit(InstallerHitLedger.Event.CALL)
                        val receiver = param.args.last()
                        if (receiver == null || main.post {
                                try {
                                    callback.invoke(receiver, null)
                                    hit(InstallerHitLedger.Event.COMPLETE)
                                }
                                catch (failure: ReflectiveOperationException) {
                                    hit(InstallerHitLedger.Event.FAULT)
                                    reportOnce("layout_callback_failed", "callback failed (${failure.javaClass.simpleName})")
                                }
                            }
                        ) {
                            param.setResultValue(null)
                            hit(InstallerHitLedger.Event.BLOCK)
                            reportOnce(data.id, "blocked ${data.id}")
                        } else {
                            hit(InstallerHitLedger.Event.RETAIN)
                            reportOnce("layout_post_failed", "main queue rejected completion; original layout retained")
                        }
                    }
                }
            }
            "intercept_check" -> {
                val payload = InstallerReflection.type(checkNotNull(data.payloadClass), target.classLoader)
                val success = InstallerReflection.type(checkNotNull(data.resultClass), target.classLoader)
                val base = InstallerReflection.type(checkNotNull(data.resultBaseClass), target.classLoader)
                val payloadConstructor = payload.getDeclaredConstructor().apply { isAccessible = true }
                val successConstructor = success.getDeclaredConstructor(payload).apply { isAccessible = true }
                check(base.isAssignableFrom(success))
                check(success.isInstance(successConstructor.newInstance(payloadConstructor.newInstance())))
                point {
                    HookHelper.hookBefore(method) { param ->
                        hit(InstallerHitLedger.Event.CALL)
                        try {
                            param.setResultValue(successConstructor.newInstance(payloadConstructor.newInstance()))
                            hit(InstallerHitLedger.Event.BLOCK)
                            reportOnce(data.id, "blocked ${data.id}")
                        } catch (failure: ReflectiveOperationException) {
                            hit(InstallerHitLedger.Event.FAULT)
                            hit(InstallerHitLedger.Event.RETAIN)
                            reportOnce("intercept_fallback", "typed result unavailable; original request retained (${failure.javaClass.simpleName})")
                        }
                    }
                }
            }
            "cloud_v3" -> {
                val fieldData = checkNotNull(data.singletonField)
                val owner = InstallerReflection.type(fieldData.owner, target.classLoader)
                val field = owner.getDeclaredField(fieldData.name).apply { isAccessible = true }
                check(Modifier.isStatic(field.modifiers) && field.type == owner)
                val unit = field.get(null)
                check(owner.isInstance(unit) && method.returnType == Any::class.java)
                constant(unit)
            }
            "guard_tip" -> {
                val tip = InstallerReflection.type(checkNotNull(data.guardedItemClass), target.classLoader)
                check(method.returnType == Boolean::class.javaPrimitiveType && method.parameterCount == 1 &&
                    method.parameterTypes.single().isAssignableFrom(tip))
                point {
                    HookHelper.hookBefore(method) { param ->
                        hit(InstallerHitLedger.Event.CALL)
                        if (param.args[0]?.javaClass == tip) {
                            param.setResultValue(false)
                            hit(InstallerHitLedger.Event.BLOCK)
                            reportOnce(data.id, "blocked ${data.id}")
                        } else hit(InstallerHitLedger.Event.RETAIN)
                    }
                }
            }
            "ads_cancel" -> {
                check(method.returnType == Void.TYPE && method.parameterCount == 0)
                val holderData = checkNotNull(data.holderField)
                val owner = InstallerReflection.type(holderData.owner, target.classLoader)
                val holder = owner.getDeclaredField(holderData.name).apply { isAccessible = true }
                val container = InstallerReflection.method(checkNotNull(data.containerGetter), target.classLoader)
                check(!Modifier.isStatic(holder.modifiers) && owner.isAssignableFrom(method.declaringClass) &&
                    holder.type == InstallerReflection.type(holderData.type, target.classLoader) &&
                    container.declaringClass.isAssignableFrom(holder.type) && container.parameterCount == 0 &&
                    container.returnType == android.widget.FrameLayout::class.java)
                point {
                    // Display only: keep every original bind side effect and then hide this ad card.
                    HookHelper.hookAfter(method) { param ->
                        hit(InstallerHitLedger.Event.CALL)
                        try {
                            val bound = holder.get(param.thisObject)
                            val card = bound?.let { container.invoke(it) as? View }
                            if (card != null && adCardIdentity(card)) {
                                card.visibility = View.GONE
                                hit(InstallerHitLedger.Event.BLOCK)
                                reportOnce(data.id, "blocked ${data.id}")
                            } else {
                                hit(InstallerHitLedger.Event.RETAIN)
                                reportOnce("ads_cancel_unbound", "ads_cancel retained; ad container unavailable or resource identity unverified")
                            }
                        } catch (failure: Exception) {
                            hit(InstallerHitLedger.Event.FAULT)
                            hit(InstallerHitLedger.Event.RETAIN)
                            reportOnce("ads_cancel_retained", "ads_cancel retained (${failure.javaClass.simpleName})")
                        }
                    }
                }
            }
            "guard_banner" -> {
                val access = checkNotNull(data.guardBanner)
                val model = InstallerReflection.type(access.modelClass, target.classLoader)
                val state = InstallerReflection.method(access.stateGetter, target.classLoader)
                val texts = access.textGetters.map { InstallerReflection.method(it, target.classLoader) }
                val kind = InstallerReflection.method(access.kindGetter, target.classLoader)
                check(method.returnType.isAssignableFrom(model) && method.parameterCount == 0 &&
                    state.declaringClass.isAssignableFrom(model) && state.parameterCount == 0 &&
                    texts.size == 6 && texts.distinct().size == 6 && texts.all {
                        it.declaringClass.isAssignableFrom(state.returnType) && it.parameterCount == 0 &&
                            CharSequence::class.java.isAssignableFrom(it.returnType)
                    } && kind.declaringClass.isAssignableFrom(state.returnType) &&
                    kind.parameterCount == 0 && kind.returnType.isEnum)
                point {
                    HookHelper.hookAfter(method) { param ->
                        hit(InstallerHitLedger.Event.CALL)
                        val result = param.result
                        if (result != null && result.javaClass == model) {
                            try {
                                val value = state.invoke(result)
                                if (value == null) { hit(InstallerHitLedger.Event.RETAIN); return@hookAfter }
                                val warning = (kind.invoke(value) as? Enum<*>)?.name
                                if (warning == null) { hit(InstallerHitLedger.Event.RETAIN); return@hookAfter }
                                val title = bannerString("install_safety_hint")
                                val body = bannerString("install_hint_not_checked_hint")
                                if (title == null || body == null) {
                                    hit(InstallerHitLedger.Event.RETAIN)
                                    reportOnce("guard_banner_resources", "guard_banner retained; default resource text unavailable")
                                    return@hookAfter
                                }
                                val links = setOf("about_safe_mode", "full_safe_about_protect_mode")
                                    .mapNotNull(bannerString).toSet()
                                val values = texts.map { it.invoke(value) as? CharSequence }
                                if (InstallerGuardBannerPolicy.matches(warning, values, title, body, links)) {
                                    param.setResultValue(null)
                                    hit(InstallerHitLedger.Event.BLOCK)
                                    reportOnce(data.id, "blocked ${data.id}")
                                } else {
                                    hit(InstallerHitLedger.Event.RETAIN)
                                    reportOnce("guard_banner_preserved", "guard_banner retained; content is not the default informational banner")
                                }
                            } catch (failure: Exception) {
                                hit(InstallerHitLedger.Event.FAULT)
                                hit(InstallerHitLedger.Event.RETAIN)
                                reportOnce("guard_banner_retained", "guard_banner retained (${failure.javaClass.simpleName})")
                            }
                        } else hit(InstallerHitLedger.Event.RETAIN)
                    }
                }
            }
            else -> error("unknown verified installer point ${data.id}")
        }
    }

    private fun reportOnce(id: String, message: String) {
        if (reported.add(id)) {
            InstallerDiagnosticReporter.record("event $message")
            HookHelper.log("PackageInstaller: $message")
        }
    }

    private fun pointGroups(option: String): List<Pair<String, List<String>>> = when (option) {
        PackageInstallerKeys.DISABLE_ADS -> listOf(
            "layout_async" to listOf("layout_async"),
            "layout_sync" to listOf("layout_sync"),
        )
        PackageInstallerKeys.DISABLE_RISK -> listOf("main" to listOf("risk_scan", "intercept_check"))
        PackageInstallerKeys.BLOCK_CLOUD -> listOf("main" to listOf("intercept_check", "cloud_v2", "cloud_v3"))
        PackageInstallerKeys.DISABLE_GUARD -> listOf(
            "switch_tip" to listOf("guard_tip"),
            "default_banner" to listOf("guard_banner"),
        )
        PackageInstallerKeys.DISABLE_FREQUENT_CHECK -> listOf("main" to listOf("frequent_check"))
        // Each completion style is independent. A sync mismatch cannot discard a verified async
        // hook; all three required groups must commit before upload earns the full status.
        PackageInstallerKeys.BLOCK_UPLOAD -> listOf(
            "upload_producers" to listOf("intercept_check", "avl_upload"),
            "layout_async" to listOf("layout_async"),
            "layout_sync" to listOf("layout_sync"),
        )
        else -> error("unknown installer option $option")
    }

    private companion object { val LOCAL_POINTS = setOf("risk_scan", "guard_tip", "frequent_check") }
}

/** Called only when the target is constructing a UI banner, after its Application exists. */
@SuppressLint("DiscouragedApi") // Four fixed resource names avoid coupling to a build's numeric IDs.
private fun installedInstallerString(name: String): String? {
    val context = Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as? Context
        ?: return null
    if (context.packageName != PackageInstallerKeys.PACKAGE_NAME) return null
    val resources = context.resources
    val id = resources.getIdentifier(name, "string", PackageInstallerKeys.PACKAGE_NAME)
    return if (id == 0) null else resources.getText(id).toString()
}

/** Exact local resource identity. No UI-tree scan and no numeric resource ID compatibility gate. */
private fun installedAdCardIdentity(view: View): Boolean = runCatching {
    view.id > 0 && view.resources.getResourcePackageName(view.id) == PackageInstallerKeys.PACKAGE_NAME &&
        view.resources.getResourceTypeName(view.id) == "id" &&
        view.resources.getResourceEntryName(view.id) == "fl_ad_card"
}.getOrDefault(false)
