package com.omnidev.workspace.data.tools

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build

/**
 * AppManifestAnalyzerTool — Dynamic reverse-engineering tool for inspecting any installed
 * Android app's exported components, deep links, and intent filters.
 *
 * The agent can use the output to:
 * - Launch specific activities in external apps via `am start`
 * - Discover deep link URIs for direct navigation (e.g., WhatsApp, Telegram)
 * - Find broadcast receivers for triggering events
 * - Understand an app's exported service landscape
 *
 * All data is retrieved via [PackageManager] — no root required.
 */
object AppManifestAnalyzerTool {

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "app_manifest_analyzer",
            description = "Reverse-engineer any installed Android app. Dumps exported Activities, " +
                "Services, Broadcast Receivers, Content Providers, deep link URIs, and Intent " +
                "Filters. Use this to discover hidden entry points for launching specific " +
                "screens via 'am start' or to find deep link URIs for direct navigation. " +
                "Example: analyze WhatsApp to find the direct chat activity.",
            parameters = listOf(
                ToolParameter(
                    name = "target_package",
                    type = "string",
                    description = "Package name of the app to analyze (e.g., 'com.whatsapp', " +
                        "'org.telegram.messenger').",
                    required = true
                ),
                ToolParameter(
                    name = "filter",
                    type = "string",
                    description = "Filter output: 'all' (default), 'activities', 'services', " +
                        "'receivers', 'providers', 'deep_links'. Use 'deep_links' to find " +
                        "only URI schemes and deep link patterns.",
                    required = false
                )
            )
        )
    )

    fun execute(context: Context, targetPackage: String, filter: String?): ToolExecutionResult {
        // Sanitize package name
        val safePackage = targetPackage.trim()
        if (!safePackage.matches(Regex("^[a-zA-Z][a-zA-Z0-9_.]*$"))) {
            return ToolExecutionResult(
                "Invalid package name: '$targetPackage'. Must be a valid Android package.",
                isError = true
            )
        }

        val pm = context.packageManager
        val flags = PackageManager.GET_ACTIVITIES or
            PackageManager.GET_SERVICES or
            PackageManager.GET_RECEIVERS or
            PackageManager.GET_PROVIDERS or
            PackageManager.GET_META_DATA

        val packageInfo: PackageInfo = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(safePackage, PackageManager.PackageInfoFlags.of(flags.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(safePackage, flags)
            }
        } catch (e: PackageManager.NameNotFoundException) {
            return ToolExecutionResult(
                "Package '$safePackage' not found. Ensure the app is installed.",
                isError = true
            )
        }

        val activeFilter = filter?.lowercase()?.trim() ?: "all"

        return try {
            val output = buildString {
                appendLine("═══ App Manifest Analysis: $safePackage ═══")

                // App metadata
                val appInfo = packageInfo.applicationInfo
                val label = appInfo?.let { pm.getApplicationLabel(it) } ?: "Unknown"
                val version = packageInfo.versionName ?: "?"
                appendLine("Label: $label")
                appendLine("Version: $version")
                appendLine("Target SDK: ${appInfo?.targetSdkVersion ?: "?"}")
                appendLine()

                // Activities
                if (activeFilter in listOf("all", "activities", "deep_links")) {
                    dumpActivities(pm, safePackage, activeFilter == "deep_links", this)
                }

                // Services
                if (activeFilter in listOf("all", "services")) {
                    dumpServices(packageInfo, this)
                }

                // Receivers
                if (activeFilter in listOf("all", "receivers")) {
                    dumpReceivers(packageInfo, this)
                }

                // Providers
                if (activeFilter in listOf("all", "providers")) {
                    dumpProviders(packageInfo, this)
                }
            }

            // Truncate if output is too large for the LLM context
            val maxLen = 8000
            val truncated = output.length > maxLen
            val finalOutput = if (truncated) {
                output.take(maxLen) + "\n\n... [truncated at $maxLen chars]"
            } else {
                output
            }

            ToolExecutionResult(finalOutput, truncated = truncated)
        } catch (e: Exception) {
            ToolExecutionResult("Analysis failed: ${e.message}", isError = true)
        }
    }

    private fun dumpActivities(
        pm: PackageManager,
        packageName: String,
        deepLinksOnly: Boolean,
        sb: StringBuilder
    ) {
        // Query with intent filters to get deep link info
        val mainIntent = Intent(Intent.ACTION_MAIN).apply { `package` = packageName }
        val viewIntent = Intent(Intent.ACTION_VIEW).apply { `package` = packageName }

        // Get all activities via queryIntentActivities for MAIN category
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PackageManager.ResolveInfoFlags.of(PackageManager.GET_RESOLVED_FILTER.toLong())
        } else {
            @Suppress("DEPRECATION")
            null
        }

        // Get all activities from PackageInfo directly with intent filter query
        val allActivities = try {
            val queryFlags = PackageManager.GET_ACTIVITIES or PackageManager.GET_META_DATA
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(queryFlags.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, queryFlags)
            }
            info.activities?.toList() ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }

        // Find deep links via VIEW intent resolution
        val deepLinkActivities = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(
                    viewIntent,
                    PackageManager.ResolveInfoFlags.of(PackageManager.GET_RESOLVED_FILTER.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(viewIntent, PackageManager.GET_RESOLVED_FILTER)
            }
        } catch (e: Exception) {
            emptyList()
        }

        if (!deepLinksOnly) {
            sb.appendLine("── Activities (${allActivities.size}) ──")
            val exported = allActivities.filter { it.exported }
            val internal = allActivities.filter { !it.exported }

            if (exported.isNotEmpty()) {
                sb.appendLine("  📤 Exported (${exported.size}):")
                exported.forEach { activity ->
                    val shortName = activity.name.removePrefix(packageName)
                    sb.appendLine("    • $shortName")
                    // Check if it has a launch intent
                    val launch = Intent().apply {
                        setClassName(packageName, activity.name)
                    }
                    val canLaunch = pm.resolveActivity(launch, 0) != null
                    if (canLaunch) {
                        sb.appendLine("      am start -n $packageName/${activity.name}")
                    }
                }
            }

            if (internal.isNotEmpty()) {
                sb.appendLine("  🔒 Internal (${internal.size}): ${internal.take(5).joinToString { it.name.substringAfterLast('.') }}${if (internal.size > 5) "..." else ""}")
            }
            sb.appendLine()
        }

        // Deep links
        if (deepLinkActivities.isNotEmpty()) {
            sb.appendLine("── Deep Links ──")
            deepLinkActivities.forEach { resolveInfo ->
                val filter = resolveInfo.filter ?: return@forEach
                val activityName = resolveInfo.activityInfo?.name?.removePrefix(packageName) ?: "?"

                for (i in 0 until filter.countDataSchemes()) {
                    val scheme = filter.getDataScheme(i) ?: continue
                    for (j in 0 until filter.countDataAuthorities()) {
                        val authority = filter.getDataAuthority(j)
                        val host = authority?.host ?: "*"
                        for (k in 0 until filter.countDataPaths()) {
                            val path = filter.getDataPath(k)?.path ?: "/"
                            sb.appendLine("  🔗 $scheme://$host$path → $activityName")
                        }
                        if (filter.countDataPaths() == 0) {
                            sb.appendLine("  🔗 $scheme://$host → $activityName")
                        }
                    }
                    if (filter.countDataAuthorities() == 0) {
                        sb.appendLine("  🔗 $scheme:// → $activityName")
                    }
                }
            }
            sb.appendLine()
        }
    }

    private fun dumpServices(packageInfo: PackageInfo, sb: StringBuilder) {
        val services = packageInfo.services ?: return
        if (services.isEmpty()) return

        sb.appendLine("── Services (${services.size}) ──")
        val exported = services.filter { it.exported }
        val internal = services.filter { !it.exported }

        if (exported.isNotEmpty()) {
            sb.appendLine("  📤 Exported (${exported.size}):")
            exported.forEach { service ->
                val shortName = service.name.substringAfterLast('.')
                val permission = service.permission
                sb.append("    • $shortName")
                if (permission != null) sb.append(" [requires: ${permission.substringAfterLast('.')}]")
                sb.appendLine()
            }
        }
        if (internal.isNotEmpty()) {
            sb.appendLine("  🔒 Internal (${internal.size}): ${internal.take(5).joinToString { it.name.substringAfterLast('.') }}${if (internal.size > 5) "..." else ""}")
        }
        sb.appendLine()
    }

    private fun dumpReceivers(packageInfo: PackageInfo, sb: StringBuilder) {
        val receivers = packageInfo.receivers ?: return
        if (receivers.isEmpty()) return

        sb.appendLine("── Broadcast Receivers (${receivers.size}) ──")
        val exported = receivers.filter { it.exported }
        val internal = receivers.filter { !it.exported }

        if (exported.isNotEmpty()) {
            sb.appendLine("  📤 Exported (${exported.size}):")
            exported.forEach { receiver ->
                val shortName = receiver.name.substringAfterLast('.')
                val permission = receiver.permission
                sb.append("    • $shortName")
                if (permission != null) sb.append(" [requires: ${permission.substringAfterLast('.')}]")
                sb.appendLine()
            }
        }
        if (internal.isNotEmpty()) {
            sb.appendLine("  🔒 Internal (${internal.size}): ${internal.take(5).joinToString { it.name.substringAfterLast('.') }}${if (internal.size > 5) "..." else ""}")
        }
        sb.appendLine()
    }

    private fun dumpProviders(packageInfo: PackageInfo, sb: StringBuilder) {
        val providers = packageInfo.providers ?: return
        if (providers.isEmpty()) return

        sb.appendLine("── Content Providers (${providers.size}) ──")
        val exported = providers.filter { it.exported }
        val internal = providers.filter { !it.exported }

        if (exported.isNotEmpty()) {
            sb.appendLine("  📤 Exported (${exported.size}):")
            exported.forEach { provider ->
                val shortName = provider.name.substringAfterLast('.')
                val authority = provider.authority
                sb.append("    • $shortName")
                if (authority != null) sb.append(" [authority: $authority]")
                sb.appendLine()
            }
        }
        if (internal.isNotEmpty()) {
            sb.appendLine("  🔒 Internal (${internal.size}): ${internal.take(5).joinToString { it.name.substringAfterLast('.') }}${if (internal.size > 5) "..." else ""}")
        }
        sb.appendLine()
    }
}
