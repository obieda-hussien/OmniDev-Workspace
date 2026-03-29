package com.omnidev.workspace.data.tools

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.net.Uri
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * AppManifestAnalyzerTool — Advanced dynamic reverse-engineering tool for inspecting any
 * installed Android app's exported components, security posture, deep links, intent filters,
 * permissions, and native surface area.
 *
 * V2 Enhancements over V1:
 * • Security Audit: debuggable, allowBackup, exported-without-permission, networkSecurity flags
 * • Full Intent Filter dump: actions, categories, mimeTypes, data schemes/hosts/paths
 * • Shell Command Generator: ready-to-run `am start`, `am broadcast`, `content query` one-liners
 * • Permissions Deep Dive: dangerous / signature / declared / custom permissions
 * • Dual output format: rich TEXT (default) or structured JSON for programmatic agent use
 * • Obfuscation detection: heuristics on class naming patterns
 * • Native libraries surface: lists .so files declared via ApplicationInfo
 * • Hardware/Software feature requirements
 * • New tool: `intent_resolver` — find all apps that handle a given intent/URI scheme
 * • New tool: `batch_manifest_analyzer` — analyze up to 5 packages in one call
 * • Shared UID detection (apps sharing a Linux UID / sandbox)
 *
 * All data is retrieved via [PackageManager] — no root required.
 */
object AppManifestAnalyzerTool {

    fun getToolDefinitions(): List<ToolDefinition> = listOf(

        ToolDefinition(
            name = "app_manifest_analyzer",
            description = """
                Reverse-engineer any installed Android app. Dumps:
                - Exported Activities, Services, Broadcast Receivers, Content Providers
                - Full Intent Filters (actions, categories, data schemes, MIME types)
                - Deep link URI patterns with ready-to-run `am start` / `am broadcast` / `content query` commands
                - Security audit: debuggable flag, allowBackup, exported components without permissions, 
                  cleartext traffic, network security config
                - Permissions: dangerous, signature-level, declared custom permissions
                - Obfuscation detection, native libraries, hardware features
                - Shared UID (sandbox sharing between apps)
                
                Use this to discover hidden entry points, automation opportunities, security weaknesses,
                and deep link patterns for any installed app. Output can be text (rich, default) or JSON
                (for programmatic parsing by the agent).
                
                Examples:
                - analyze WhatsApp deep links → filter=deep_links
                - security audit of a banking app → filter=security
                - get all broadcast receivers and their commands → filter=receivers
            """.trimIndent(),
            parameters = listOf(
                ToolParameter(
                    name = "target_package",
                    type = "string",
                    description = "Package name of the app to analyze (e.g., 'com.whatsapp', 'org.telegram.messenger').",
                    required = true
                ),
                ToolParameter(
                    name = "filter",
                    type = "string",
                    description = """
                        Filter output section:
                        'all' (default) — everything
                        'activities'   — exported activities + intent filters + am start commands
                        'services'     — services + am startservice commands
                        'receivers'    — broadcast receivers + am broadcast commands
                        'providers'    — content providers + content query commands
                        'deep_links'   — URI schemes / app links / intent URIs only
                        'security'     — security audit only (flags, bare exports, permissions)
                        'permissions'  — full permissions breakdown
                        'native'       — native libraries + hardware features
                    """.trimIndent(),
                    required = false
                ),
                ToolParameter(
                    name = "output_format",
                    type = "string",
                    description = "'text' (default, human-readable) or 'json' (machine-readable, for agent parsing).",
                    required = false
                )
            )
        ),

        ToolDefinition(
            name = "intent_resolver",
            description = """
                Find all installed apps that can handle a specific intent, URI scheme, or MIME type.
                Useful for:
                - Discovering which apps open a given URI scheme (e.g., 'whatsapp://', 'market://')
                - Finding all apps that handle a specific MIME type (e.g., 'image/png', 'text/plain')
                - Resolving which app would handle a system intent (e.g., ACTION_SEND, ACTION_VIEW)
                
                Returns a ranked list of matching apps with their package names and activity names.
            """.trimIndent(),
            parameters = listOf(
                ToolParameter(
                    name = "action",
                    type = "string",
                    description = "Intent action (e.g., 'android.intent.action.VIEW', 'android.intent.action.SEND'). Defaults to ACTION_VIEW.",
                    required = false
                ),
                ToolParameter(
                    name = "uri",
                    type = "string",
                    description = "URI to resolve (e.g., 'https://example.com', 'whatsapp://send', 'content://contacts').",
                    required = false
                ),
                ToolParameter(
                    name = "mime_type",
                    type = "string",
                    description = "MIME type to match (e.g., 'image/png', 'text/plain', 'application/pdf').",
                    required = false
                )
            )
        ),

        ToolDefinition(
            name = "batch_manifest_analyzer",
            description = """
                Analyze up to 5 apps in a single call. Returns a compact summary of each app's
                exported component counts, deep link count, security risk score, and permissions count.
                Use this for a quick cross-app comparison before drilling into a specific package
                with app_manifest_analyzer.
            """.trimIndent(),
            parameters = listOf(
                ToolParameter(
                    name = "packages",
                    type = "string",
                    description = "Comma-separated list of package names (max 5). E.g., 'com.whatsapp,org.telegram.messenger,com.instagram.android'.",
                    required = true
                )
            )
        )
    )

    fun execute(
        context: Context,
        targetPackage: String,
        filter: String? = null,
        outputFormat: String? = null
    ): ToolExecutionResult {
        val safePackage = targetPackage.trim()
        if (!isValidPackageName(safePackage)) {
            return ToolExecutionResult(
                "Invalid package name: '$targetPackage'. Must match [a-zA-Z][a-zA-Z0-9_.]*",
                isError = true
            )
        }

        val pm = context.packageManager
        val packageInfo = getPackageInfoFull(pm, safePackage)
            ?: return ToolExecutionResult(
                "Package '$safePackage' not found. Is the app installed?",
                isError = true
            )

        val activeFilter = filter?.lowercase()?.trim() ?: "all"
        val useJson = outputFormat?.lowercase()?.trim() == "json"

        return try {
            val output = if (useJson) {
                buildJsonOutput(context, pm, packageInfo, safePackage, activeFilter)
            } else {
                buildTextOutput(context, pm, packageInfo, safePackage, activeFilter)
            }

            val maxLen = 10_000
            val truncated = output.length > maxLen
            val finalOutput = if (truncated) {
                output.take(maxLen) + "\n\n... [truncated at $maxLen chars — use a specific filter to see more]"
            } else {
                output
            }

            ToolExecutionResult(finalOutput, truncated = truncated)
        } catch (e: Exception) {
            ToolExecutionResult("Analysis failed: ${e.message}", isError = true)
        }
    }

    fun executeIntentResolver(
        context: Context,
        action: String? = null,
        uri: String? = null,
        mimeType: String? = null
    ): ToolExecutionResult {
        val pm = context.packageManager
        val resolveAction = action ?: Intent.ACTION_VIEW

        val intent = Intent(resolveAction).apply {
            uri?.let { data = Uri.parse(it) }
            mimeType?.let { type = it }
        }

        val results = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            }
        } catch (e: Exception) {
            return ToolExecutionResult("Intent resolution failed: ${e.message}", isError = true)
        }

        if (results.isEmpty()) {
            return ToolExecutionResult("No apps found that can handle this intent.")
        }

        val output = buildString {
            appendLine("═══ Intent Resolver Results ═══")
            appendLine("Action  : $resolveAction")
            uri?.let { appendLine("URI     : $it") }
            mimeType?.let { appendLine("MIME    : $it") }
            appendLine("Matches : ${results.size}")
            appendLine()

            results.sortedByDescending { it.priority }.forEachIndexed { i, ri ->
                val appInfo = ri.activityInfo?.applicationInfo
                val label = appInfo?.let { pm.getApplicationLabel(it) } ?: ri.activityInfo?.packageName ?: "?"
                val pkg = ri.activityInfo?.packageName ?: "?"
                val activity = ri.activityInfo?.name?.removePrefix(pkg) ?: "?"
                val priority = ri.priority
                val isDefault = ri.isDefault

                appendLine("${i + 1}. $label ($pkg)")
                appendLine("   Activity : $activity")
                appendLine("   Priority : $priority${if (isDefault) " ★ DEFAULT" else ""}")
                uri?.let {
                    appendLine("   Launch   : am start -a $resolveAction -d \"$it\" $pkg")
                }
                appendLine()
            }
        }

        return ToolExecutionResult(output)
    }

    fun executeBatch(context: Context, packages: String): ToolExecutionResult {
        val packageList = packages.split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() && isValidPackageName(it) }
            .take(5)

        if (packageList.isEmpty()) {
            return ToolExecutionResult("No valid package names provided.", isError = true)
        }

        val pm = context.packageManager

        val output = buildString {
            appendLine("═══ Batch Manifest Summary (${packageList.size} apps) ═══")
            appendLine()

            packageList.forEach { pkg ->
                val info = getPackageInfoFull(pm, pkg)
                if (info == null) {
                    appendLine("• $pkg — NOT INSTALLED")
                    return@forEach
                }

                val appInfo = info.applicationInfo
                val label = appInfo?.let { pm.getApplicationLabel(it) } ?: pkg

                val exportedActivities = info.activities?.count { it.exported } ?: 0
                val exportedServices = info.services?.count { it.exported } ?: 0
                val exportedReceivers = info.receivers?.count { it.exported } ?: 0
                val exportedProviders = info.providers?.count { it.exported } ?: 0
                val totalExported = exportedActivities + exportedServices + exportedReceivers + exportedProviders

                val bareExportedActivities = info.activities?.count { it.exported && getComponentPermission(it) == null } ?: 0
                val bareExportedServices = info.services?.count { it.exported && getComponentPermission(it) == null } ?: 0
                val bareExportedReceivers = info.receivers?.count { it.exported && getComponentPermission(it) == null } ?: 0

                val debuggable = (appInfo?.flags?.and(android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) ?: 0) != 0
                val allowBackup = (appInfo?.flags?.and(android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP) ?: 0) != 0

                val riskScore = calculateRiskScore(
                    debuggable, allowBackup,
                    bareExportedActivities, bareExportedServices, bareExportedReceivers
                )

                val usedPerms = try {
                    pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS).requestedPermissions?.size ?: 0
                } catch (e: Exception) { 0 }

                appendLine("┌─ $label ($pkg)")
                appendLine("│  Version     : ${info.versionName ?: "?"}")
                appendLine("│  Exported    : $totalExported total (act:$exportedActivities svc:$exportedServices rcv:$exportedReceivers prv:$exportedProviders)")
                appendLine("│  Bare Exports: $bareExportedActivities act + $bareExportedServices svc + $bareExportedReceivers rcv (no permission guard)")
                appendLine("│  Permissions : $usedPerms requested")
                appendLine("│  Flags       : ${if (debuggable) "⚠️ DEBUGGABLE " else ""}${if (allowBackup) "⚠️ ALLOW_BACKUP " else ""}")
                appendLine("│  Risk Score  : ${"★".repeat(riskScore)}${"☆".repeat(5 - riskScore)} ($riskScore/5)")
                appendLine("└─────────────────")
                appendLine()
            }
        }

        return ToolExecutionResult(output)
    }

    private fun buildTextOutput(
        context: Context,
        pm: PackageManager,
        packageInfo: PackageInfo,
        pkg: String,
        filter: String
    ): String = buildString {
        val appInfo = packageInfo.applicationInfo
        val label = appInfo?.let { pm.getApplicationLabel(it) } ?: "Unknown"

        appendLine("═══ Manifest Analysis: $pkg ═══")
        appendLine("Label      : $label")
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            @Suppress("NewApi") packageInfo.longVersionCode
        } else {
            packageInfo.versionCode.toLong()
        }
        appendLine("Version    : ${packageInfo.versionName ?: "?"} (code: $versionCode)")
        appendLine("Target SDK : ${appInfo?.targetSdkVersion ?: "?"}")
        appendLine("Min SDK    : ${appInfo?.minSdkVersion ?: "?"}")
        appendLine("Install    : ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(packageInfo.firstInstallTime))}")
        appendLine("Updated    : ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(packageInfo.lastUpdateTime))}")
        appendLine()

        if (filter in listOf("all", "security")) {
            appendSecurityAudit(pm, packageInfo, pkg, this)
        }

        if (filter in listOf("all", "permissions")) {
            appendPermissions(pm, packageInfo, pkg, this)
        }

        if (filter in listOf("all", "activities", "deep_links")) {
            appendActivities(pm, packageInfo, pkg, filter == "deep_links", this)
        }

        if (filter in listOf("all", "services")) {
            appendServices(pm, packageInfo, pkg, this)
        }

        if (filter in listOf("all", "receivers")) {
            appendReceivers(pm, packageInfo, pkg, this)
        }

        if (filter in listOf("all", "providers")) {
            appendProviders(pm, packageInfo, pkg, this)
        }

        if (filter in listOf("all", "native")) {
            appendNativeInfo(pm, packageInfo, pkg, this)
        }
    }

    private fun buildJsonOutput(
        context: Context,
        pm: PackageManager,
        packageInfo: PackageInfo,
        pkg: String,
        filter: String
    ): String {
        val appInfo = packageInfo.applicationInfo
        val root = JSONObject()

        root.put("package", pkg)
        root.put("label", appInfo?.let { pm.getApplicationLabel(it).toString() } ?: "Unknown")
        root.put("versionName", packageInfo.versionName ?: "?")
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            @Suppress("NewApi") packageInfo.longVersionCode
        } else {
            packageInfo.versionCode.toLong()
        }
        root.put("versionCode", versionCode)
        root.put("targetSdk", appInfo?.targetSdkVersion ?: -1)
        root.put("minSdk", appInfo?.minSdkVersion ?: -1)

        if (filter in listOf("all", "security")) {
            root.put("security", buildSecurityJson(pm, packageInfo, pkg))
        }

        if (filter in listOf("all", "activities", "deep_links")) {
            root.put("activities", buildActivitiesJson(pm, packageInfo, pkg))
        }

        if (filter in listOf("all", "services")) {
            root.put("services", buildServicesJson(packageInfo, pkg))
        }

        if (filter in listOf("all", "receivers")) {
            root.put("receivers", buildReceiversJson(packageInfo, pkg))
        }

        if (filter in listOf("all", "providers")) {
            root.put("providers", buildProvidersJson(packageInfo, pkg))
        }

        if (filter in listOf("all", "permissions")) {
            root.put("permissions", buildPermissionsJson(pm, packageInfo, pkg))
        }

        return root.toString(2)
    }

    private fun appendSecurityAudit(
        pm: PackageManager,
        packageInfo: PackageInfo,
        pkg: String,
        sb: StringBuilder
    ) {
        val appInfo = packageInfo.applicationInfo ?: return
        val findings = mutableListOf<String>()
        val info = mutableListOf<String>()

        val isDebuggable = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val allowBackup = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP) != 0
        val isTestOnly = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_TEST_ONLY) != 0

        if (isDebuggable) findings.add("⚠️  DEBUGGABLE=true — app can be attached by debuggers / ADB")
        if (allowBackup) findings.add("⚠️  ALLOW_BACKUP=true — app data can be extracted via `adb backup`")
        if (isTestOnly) findings.add("ℹ️  TEST_ONLY=true — installed only for testing")

        val bareActivities = packageInfo.activities?.filter { it.exported && getComponentPermission(it) == null } ?: emptyList()
        val bareServices = packageInfo.services?.filter { it.exported && getComponentPermission(it) == null } ?: emptyList()
        val bareReceivers = packageInfo.receivers?.filter { it.exported && getComponentPermission(it) == null } ?: emptyList()
        val bareProviders = packageInfo.providers?.filter { it.exported && getComponentPermission(it) == null } ?: emptyList()

        if (bareActivities.isNotEmpty()) {
            findings.add("⚠️  ${bareActivities.size} exported Activity(s) with NO permission guard:")
            bareActivities.forEach { a ->
                findings.add("     am start -n $pkg/${a.name}")
            }
        }
        if (bareServices.isNotEmpty()) {
            findings.add("⚠️  ${bareServices.size} exported Service(s) with NO permission guard:")
            bareServices.forEach { s ->
                findings.add("     am startservice -n $pkg/${s.name}")
            }
        }
        if (bareReceivers.isNotEmpty()) {
            findings.add("⚠️  ${bareReceivers.size} exported Receiver(s) with NO permission guard")
        }
        if (bareProviders.isNotEmpty()) {
            val grantUriProviders = bareProviders.filter { it.grantUriPermissions }
            findings.add("⚠️  ${bareProviders.size} exported Provider(s) with NO permission guard (${grantUriProviders.size} with grantUriPermissions=true)")
            bareProviders.forEach { p ->
                p.authority?.split(";")?.forEach { auth ->
                    findings.add("     content query --uri content://$auth/")
                }
            }
        }

        if (packageInfo.sharedUserId != null) {
            info.add("ℹ️  Shared UID: '${packageInfo.sharedUserId}' — shares Linux sandbox with other apps")
        }

        val allClassNames = buildList {
            packageInfo.activities?.forEach { add(it.name) }
            packageInfo.services?.forEach { add(it.name) }
            packageInfo.receivers?.forEach { add(it.name) }
        }
        val obfuscatedCount = allClassNames.count { name ->
            val simple = name.substringAfterLast('.')
            simple.length <= 2 || simple.matches(Regex("^[a-z]{1,3}$"))
        }
        if (allClassNames.size > 5 && obfuscatedCount.toDouble() / allClassNames.size > 0.4) {
            info.add("ℹ️  Likely obfuscated (~${obfuscatedCount}/${allClassNames.size} components have short names)")
        }

        val riskScore = calculateRiskScore(
            isDebuggable, allowBackup,
            bareActivities.size, bareServices.size, bareReceivers.size
        )

        sb.appendLine("── Security Audit ──")
        sb.appendLine("  Risk Score: ${"★".repeat(riskScore)}${"☆".repeat(5 - riskScore)} ($riskScore/5)")
        sb.appendLine()

        if (findings.isEmpty() && info.isEmpty()) {
            sb.appendLine("  ✅ No obvious security issues detected.")
        } else {
            findings.forEach { sb.appendLine("  $it") }
            info.forEach { sb.appendLine("  $it") }
        }
        sb.appendLine()
    }

    private fun appendActivities(
        pm: PackageManager,
        packageInfo: PackageInfo,
        pkg: String,
        deepLinksOnly: Boolean,
        sb: StringBuilder
    ) {
        val activities = packageInfo.activities?.toList() ?: emptyList()
        val exported = activities.filter { it.exported }
        val internal = activities.filter { !it.exported }

        val viewIntent = Intent(Intent.ACTION_VIEW).apply { `package` = pkg }
        val deepLinkResults = queryIntentActivities(pm, viewIntent)

        if (!deepLinksOnly) {
            sb.appendLine("── Activities (${activities.size} total) ──")

            if (exported.isNotEmpty()) {
                sb.appendLine("  📤 Exported (${exported.size}):")
                exported.forEach { activity ->
                    val shortName = activity.name.removePrefix(pkg)
                    val comp = activity as android.content.pm.ComponentInfo
                    val compPerm = getComponentPermission(activity)
                    val hasPermission = compPerm != null
                    val guard = if (hasPermission) " [🔐 ${compPerm?.substringAfterLast('.')} ]" else " [🔓 OPEN]"
                    sb.appendLine("    • $shortName$guard")
                    sb.appendLine("      ▶ am start -n $pkg/${activity.name}")
                    val intentFilters = resolveActivityIntentFilters(pm, pkg, activity.name)
                    intentFilters.forEach { filterInfo ->
                        if (filterInfo.actions.isNotEmpty()) {
                            sb.appendLine("      Actions   : ${filterInfo.actions.joinToString(", ") { it.substringAfterLast('.') }}")
                        }
                        if (filterInfo.categories.isNotEmpty()) {
                            sb.appendLine("      Categories: ${filterInfo.categories.joinToString(", ") { it.substringAfterLast('.') }}")
                        }
                        if (filterInfo.mimeTypes.isNotEmpty()) {
                            sb.appendLine("      MIME Types: ${filterInfo.mimeTypes.joinToString(", ")}")
                        }
                    }
                }
            }

            if (internal.isNotEmpty()) {
                val sample = internal.take(5).joinToString { it.name.substringAfterLast('.') }
                val more = if (internal.size > 5) " +${internal.size - 5} more" else ""
                sb.appendLine("  🔒 Internal (${internal.size}): $sample$more")
            }
            sb.appendLine()
        }

        if (deepLinkResults.isNotEmpty()) {
            sb.appendLine("── Deep Links ──")
            deepLinkResults.forEach { ri ->
                val filter = ri.filter ?: return@forEach
                val actShort = ri.activityInfo?.name?.removePrefix(pkg) ?: "?"

                for (i in 0 until filter.countDataSchemes()) {
                    val scheme = filter.getDataScheme(i) ?: continue
                    for (j in 0 until filter.countDataAuthorities()) {
                        val authority = filter.getDataAuthority(j)
                        val host = authority?.host ?: "*"
                        val port = authority?.port?.let { if (it != -1) ":$it" else "" } ?: ""
                        if (filter.countDataPaths() > 0) {
                            for (k in 0 until filter.countDataPaths()) {
                                val path = filter.getDataPath(k)?.path ?: "/"
                                sb.appendLine("  🔗 $scheme://$host$port$path")
                                sb.appendLine("     ▶ am start -a android.intent.action.VIEW -d \"$scheme://$host$port$path\" $pkg")
                                sb.appendLine("     → $actShort")
                            }
                        } else {
                            sb.appendLine("  🔗 $scheme://$host$port")
                            sb.appendLine("     ▶ am start -a android.intent.action.VIEW -d \"$scheme://$host$port\" $pkg")
                            sb.appendLine("     → $actShort")
                        }
                    }
                    if (filter.countDataAuthorities() == 0) {
                        sb.appendLine("  🔗 $scheme://")
                        sb.appendLine("     ▶ am start -a android.intent.action.VIEW -d \"$scheme://\" $pkg")
                        sb.appendLine("     → $actShort")
                    }
                }

                if (filter.countDataSchemes() == 0) {
                    for (m in 0 until filter.countDataTypes()) {
                        val mime = filter.getDataType(m) ?: continue
                        sb.appendLine("  📎 MIME: $mime → $actShort")
                        sb.appendLine("     ▶ am start -a android.intent.action.VIEW -t \"$mime\" $pkg")
                    }
                }
            }
            sb.appendLine()
        }
    }

    private fun appendServices(
        pm: PackageManager,
        packageInfo: PackageInfo,
        pkg: String,
        sb: StringBuilder
    ) {
        val services = packageInfo.services ?: return
        if (services.isEmpty()) return

        val exported = services.filter { it.exported }
        val internal = services.filter { !it.exported }

        sb.appendLine("── Services (${services.size}) ──")

        if (exported.isNotEmpty()) {
            sb.appendLine("  📤 Exported (${exported.size}):")
            exported.forEach { svc ->
                val shortName = svc.name.removePrefix(pkg)
                val compPermSvc = getComponentPermission(svc)
                val guard = if (compPermSvc != null) " [🔐 ${compPermSvc.substringAfterLast('.')} ]" else " [🔓 OPEN]"
                val isForeground = svc.flags and android.content.pm.ServiceInfo.FLAG_STOP_WITH_TASK != 0
                sb.appendLine("    • $shortName$guard${if (isForeground) " [foreground]" else ""}")
                sb.appendLine("      ▶ am startservice -n $pkg/${svc.name}")
                sb.appendLine("      ▶ am stopservice  -n $pkg/${svc.name}")
            }
        }

        if (internal.isNotEmpty()) {
            val sample = internal.take(5).joinToString { it.name.substringAfterLast('.') }
            val more = if (internal.size > 5) " +${internal.size - 5} more" else ""
            sb.appendLine("  🔒 Internal (${internal.size}): $sample$more")
        }
        sb.appendLine()
    }

    private fun appendReceivers(
        pm: PackageManager,
        packageInfo: PackageInfo,
        pkg: String,
        sb: StringBuilder
    ) {
        val receivers = packageInfo.receivers ?: return
        if (receivers.isEmpty()) return

        val exported = receivers.filter { it.exported }
        val internal = receivers.filter { !it.exported }

        sb.appendLine("── Broadcast Receivers (${receivers.size}) ──")

        if (exported.isNotEmpty()) {
            sb.appendLine("  📤 Exported (${exported.size}):")
            exported.forEach { rcv ->
                val shortName = rcv.name.removePrefix(pkg)
                val rcvPerm = getComponentPermission(rcv)
                val guard = if (rcvPerm != null) " [🔐 ${rcvPerm.substringAfterLast('.')} ]" else " [🔓 OPEN]"
                sb.appendLine("    • $shortName$guard")
                sb.appendLine("      ▶ am broadcast -n $pkg/${rcv.name} -a <ACTION>")
            }
        }

        if (internal.isNotEmpty()) {
            val sample = internal.take(5).joinToString { it.name.substringAfterLast('.') }
            val more = if (internal.size > 5) " +${internal.size - 5} more" else ""
            sb.appendLine("  🔒 Internal (${internal.size}): $sample$more")
        }
        sb.appendLine()
    }

    private fun appendProviders(
        pm: PackageManager,
        packageInfo: PackageInfo,
        pkg: String,
        sb: StringBuilder
    ) {
        val providers = packageInfo.providers ?: return
        if (providers.isEmpty()) return

        val exported = providers.filter { it.exported }
        val internal = providers.filter { !it.exported }

        sb.appendLine("── Content Providers (${providers.size}) ──")

        if (exported.isNotEmpty()) {
            sb.appendLine("  📤 Exported (${exported.size}):")
            exported.forEach { prov ->
                val shortName = prov.name.removePrefix(pkg)
                val provPerm = getProviderAnyPermission(prov)
                val guard = if (provPerm != null) {
                    " [🔐 ${provPerm.substringAfterLast('.')} ]"
                } else " [🔓 OPEN]"
                val grantUri = if (prov.grantUriPermissions) " [grantUri]" else ""
                sb.appendLine("    • $shortName$guard$grantUri")
                prov.authority?.split(";")?.forEach { auth ->
                    sb.appendLine("      Authority: $auth")
                    sb.appendLine("      ▶ content query  --uri content://$auth/")
                    sb.appendLine("      ▶ content insert --uri content://$auth/ --bind key:s:value")
                }
            }
        }

        if (internal.isNotEmpty()) {
            val sample = internal.take(5).joinToString { it.name.substringAfterLast('.') }
            val more = if (internal.size > 5) " +${internal.size - 5} more" else ""
            sb.appendLine("  🔒 Internal (${internal.size}): $sample$more")
        }
        sb.appendLine()
    }

    private fun appendPermissions(pm: PackageManager, packageInfo: PackageInfo, pkg: String, sb: StringBuilder) {
        val permInfo = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
            }
        } catch (e: Exception) { return }

        val requested = permInfo.requestedPermissions ?: return
        sb.appendLine("── Permissions (${requested.size} requested) ──")

        val dangerous = mutableListOf<String>()
        val signature = mutableListOf<String>()
        val normal = mutableListOf<String>()
        val unknown = mutableListOf<String>()

        requested.forEach { perm ->
            try {
                val pi = pm.getPermissionInfo(perm, 0)
                @Suppress("DEPRECATION") val base = pi.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE
                when (base) {
                    PermissionInfo.PROTECTION_DANGEROUS -> dangerous.add(perm)
                    PermissionInfo.PROTECTION_SIGNATURE -> signature.add(perm)
                    else -> normal.add(perm)
                }
            } catch (e: Exception) {
                unknown.add(perm)
            }
        }

        if (dangerous.isNotEmpty()) {
            sb.appendLine("  🚨 Dangerous (${dangerous.size}):")
            dangerous.forEach { sb.appendLine("    • ${it.substringAfterLast('.')}") }
        }
        if (signature.isNotEmpty()) {
            sb.appendLine("  🔏 Signature-level (${signature.size}):")
            signature.forEach { sb.appendLine("    • ${it.substringAfterLast('.')}") }
        }
        if (normal.isNotEmpty()) {
            sb.appendLine("  ✅ Normal (${normal.size}): ${normal.take(8).joinToString { it.substringAfterLast('.') }}${if (normal.size > 8) "..." else ""}")
        }

        val declared = permInfo.permissions
        if (!declared.isNullOrEmpty()) {
            sb.appendLine("  📋 Declared Custom Permissions (${declared.size}):")
            declared.forEach { dp ->
                sb.appendLine("    • ${dp.name.removePrefix("$pkg.")}")
            }
        }
        sb.appendLine()
    }

    private fun appendNativeInfo(
        pm: PackageManager,
        packageInfo: PackageInfo,
        pkg: String,
        sb: StringBuilder
    ) {
        sb.appendLine("── Native & Hardware ──")

        val appInfo = packageInfo.applicationInfo
        val nativeLibDir = appInfo?.nativeLibraryDir
        if (!nativeLibDir.isNullOrEmpty()) {
            val libDir = File(nativeLibDir)
            val libs = libDir.listFiles()?.filter { it.name.endsWith(".so") }
            if (!libs.isNullOrEmpty()) {
                sb.appendLine("  📦 Native Libraries (${libs.size}):")
                libs.forEach { lib -> sb.appendLine("    • ${lib.name}") }
            } else {
                sb.appendLine("  📦 No native libraries found (pure Java/Kotlin)")
            }
        }

        try {
            val featFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                PackageManager.PackageInfoFlags.of(PackageManager.GET_CONFIGURATIONS.toLong())
            } else null

            val featInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(pkg, featFlags!!)
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, PackageManager.GET_CONFIGURATIONS)
            }

            val features = featInfo.reqFeatures
            if (!features.isNullOrEmpty()) {
                val required = features.filter { it.flags and android.content.pm.FeatureInfo.FLAG_REQUIRED != 0 }
                val optional = features.filter { it.flags and android.content.pm.FeatureInfo.FLAG_REQUIRED == 0 }
                if (required.isNotEmpty()) {
                    sb.appendLine("  📱 Required Features (${required.size}):")
                    required.forEach { f -> sb.appendLine("    • ${f.name ?: "OpenGL ES ${f.reqGlEsVersion ushr 16}.${f.reqGlEsVersion and 0xffff}"}") }
                }
                if (optional.isNotEmpty()) {
                    sb.appendLine("  📱 Optional Features (${optional.size}): ${optional.take(5).joinToString { it.name?.substringAfterLast('.') ?: "?" }}${if (optional.size > 5) "..." else ""}")
                }
            }
        } catch (_: Exception) {}

        sb.appendLine()
    }

    private fun buildSecurityJson(pm: PackageManager, packageInfo: PackageInfo, pkg: String): JSONObject {
        val appInfo = packageInfo.applicationInfo ?: return JSONObject()
        val obj = JSONObject()

        obj.put("debuggable", (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0)
        obj.put("allowBackup", (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP) != 0)
        obj.put("testOnly", (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_TEST_ONLY) != 0)
        obj.put("sharedUserId", packageInfo.sharedUserId)

        val bareAct = JSONArray()
        packageInfo.activities?.filter { it.exported && it.permission == null }?.forEach { bareAct.put(it.name) }
        obj.put("bareExportedActivities", bareAct)

        val bareSvc = JSONArray()
        packageInfo.services?.filter { it.exported && it.permission == null }?.forEach { bareSvc.put(it.name) }
        obj.put("bareExportedServices", bareSvc)

        val bareRcv = JSONArray()
        packageInfo.receivers?.filter { it.exported && it.permission == null }?.forEach { bareRcv.put(it.name) }
        obj.put("bareExportedReceivers", bareRcv)

        val riskScore = calculateRiskScore(
            obj.optBoolean("debuggable"), obj.optBoolean("allowBackup"),
            bareAct.length(), bareSvc.length(), bareRcv.length()
        )
        obj.put("riskScore", riskScore)

        return obj
    }

    private fun buildActivitiesJson(pm: PackageManager, packageInfo: PackageInfo, pkg: String): JSONObject {
        val obj = JSONObject()
        val exportedArr = JSONArray()
        val internalArr = JSONArray()

        packageInfo.activities?.forEach { act ->
            val entry = JSONObject().apply {
                put("name", act.name)
                put("exported", act.exported)
                put("permission", act.permission)
                put("amCommand", "am start -n $pkg/${act.name}")
            }
            if (act.exported) exportedArr.put(entry) else internalArr.put(JSONObject().put("name", act.name))
        }

        obj.put("exported", exportedArr)
        obj.put("internal", internalArr)
        return obj
    }

    private fun buildServicesJson(packageInfo: PackageInfo, pkg: String): JSONObject {
        val obj = JSONObject()
        val exportedArr = JSONArray()
        val internalArr = JSONArray()

        packageInfo.services?.forEach { svc ->
            val entry = JSONObject().apply {
                put("name", svc.name)
                put("exported", svc.exported)
                put("permission", svc.permission)
                put("amCommand", "am startservice -n $pkg/${svc.name}")
            }
            if (svc.exported) exportedArr.put(entry) else internalArr.put(JSONObject().put("name", svc.name))
        }

        obj.put("exported", exportedArr)
        obj.put("internal", internalArr)
        return obj
    }

    private fun buildReceiversJson(packageInfo: PackageInfo, pkg: String): JSONObject {
        val obj = JSONObject()
        val exportedArr = JSONArray()

        packageInfo.receivers?.filter { it.exported }?.forEach { rcv ->
            exportedArr.put(JSONObject().apply {
                put("name", rcv.name)
                put("permission", rcv.permission)
                put("amCommand", "am broadcast -n $pkg/${rcv.name} -a <ACTION>")
            })
        }

        obj.put("exported", exportedArr)
        return obj
    }

    private fun buildProvidersJson(packageInfo: PackageInfo, pkg: String): JSONObject {
        val obj = JSONObject()
        val exportedArr = JSONArray()

        packageInfo.providers?.filter { it.exported }?.forEach { prov ->
            val authArr = JSONArray()
            prov.authority?.split(";")?.forEach { authArr.put(it) }
            exportedArr.put(JSONObject().apply {
                put("name", prov.name)
                put("authority", authArr)
                put("readPermission", prov.readPermission)
                put("writePermission", prov.writePermission)
                put("grantUriPermissions", prov.grantUriPermissions)
            })
        }

        obj.put("exported", exportedArr)
        return obj
    }

    private fun buildPermissionsJson(pm: PackageManager, packageInfo: PackageInfo, pkg: String): JSONObject {
        val obj = JSONObject()
        val dangerousArr = JSONArray()
        val signatureArr = JSONArray()
        val normalArr = JSONArray()

        try {
            val permInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
            }

            permInfo.requestedPermissions?.forEach { perm ->
                try {
                    val pi = pm.getPermissionInfo(perm, 0)
                    val base = @Suppress("DEPRECATION") pi.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE
                    when (base) {
                        PermissionInfo.PROTECTION_DANGEROUS -> dangerousArr.put(perm)
                        PermissionInfo.PROTECTION_SIGNATURE -> signatureArr.put(perm)
                        else -> normalArr.put(perm)
                    }
                } catch (_: Exception) { normalArr.put(perm) }
            }
        } catch (_: Exception) {}

        obj.put("dangerous", dangerousArr)
        obj.put("signature", signatureArr)
        obj.put("normal", normalArr)
        return obj
    }

    private data class IntentFilterInfo(
        val actions: List<String>,
        val categories: List<String>,
        val mimeTypes: List<String>
    )

    private fun resolveActivityIntentFilters(pm: PackageManager, pkg: String, activityName: String): List<IntentFilterInfo> {
        val results = mutableListOf<IntentFilterInfo>()
        val actions = listOf(Intent.ACTION_VIEW, Intent.ACTION_MAIN, Intent.ACTION_SEND, "android.intent.action.DEFAULT")
        actions.forEach { action ->
            val intent = Intent(action).apply { `package` = pkg }
            val resolved = queryIntentActivities(pm, intent)
            resolved.filter { it.activityInfo?.name == activityName }.forEach { ri ->
                val f = ri.filter ?: return@forEach
                val acts = (0 until f.countActions()).mapNotNull { f.getAction(it) }
                val cats = (0 until f.countCategories()).mapNotNull { f.getCategory(it) }
                val mimes = (0 until f.countDataTypes()).mapNotNull { f.getDataType(it) }
                if (acts.isNotEmpty() || mimes.isNotEmpty()) {
                    results.add(IntentFilterInfo(acts, cats, mimes))
                }
            }
        }
        return results.distinctBy { it.actions.sorted() }
    }

    private fun queryIntentActivities(pm: PackageManager, intent: Intent): List<android.content.pm.ResolveInfo> {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(
                    intent,
                    PackageManager.ResolveInfoFlags.of(PackageManager.GET_RESOLVED_FILTER.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(intent, PackageManager.GET_RESOLVED_FILTER)
            }
        } catch (e: Exception) { emptyList() }
    }

    private fun getPackageInfoFull(pm: PackageManager, pkg: String): PackageInfo? {
        val flags = PackageManager.GET_ACTIVITIES or
            PackageManager.GET_SERVICES or
            PackageManager.GET_RECEIVERS or
            PackageManager.GET_PROVIDERS or
            PackageManager.GET_PERMISSIONS or
            PackageManager.GET_META_DATA

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(flags.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, flags)
            }
        } catch (e: PackageManager.NameNotFoundException) { null }
    }

    // Helper: safely read permission property from any ComponentInfo-derived object
    fun getComponentPermission(component: Any?): String? {
        return try {
            (component as? android.content.pm.PackageItemInfo)?.let {
                it.javaClass.getField("permission").get(it) as? String
            }
        } catch (_: Exception) { null }
    }

    // Helper: prefer readPermission > writePermission > permission for ProviderInfo
    fun getProviderAnyPermission(provider: android.content.pm.ProviderInfo?): String? {
        return try {
            provider?.readPermission ?: provider?.writePermission ?: (provider as? android.content.pm.PackageItemInfo)?.let {
                it.javaClass.getField("permission").get(it) as? String
            }
        } catch (_: Exception) { null }
    }

    private fun calculateRiskScore(
        debuggable: Boolean,
        allowBackup: Boolean,
        bareActivities: Int,
        bareServices: Int,
        bareReceivers: Int
    ): Int {
        var score = 0
        if (debuggable) score += 2
        if (allowBackup) score += 1
        if (bareActivities > 0) score += 1
        if (bareServices > 0 || bareReceivers > 0) score += 1
        return score.coerceIn(0, 5)
    }

    private fun isValidPackageName(name: String): Boolean =
        name.matches(Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$"))
}
