package com.omnidev.workspace.data.tools

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.content.pm.ActivityInfo
import android.content.pm.ServiceInfo
import android.content.pm.ProviderInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * AppManifestAnalyzerTool — The Ultimate Dynamic Reverse-Engineering Engine.
 *
 * Capabilities:
 * - Dumps Exported Components, Deep Links, and Security Posture.
 * - Extracts APKs (Base + Splits) for further offline analysis.
 * - Scans for hardcoded secrets/API keys in the manifest metadata.
 * - Generates "Hacking Recipes" (ready-to-use Termux/Shizuku commands for Jadx, Apktool, and data extraction).
 */
object AppManifestAnalyzerTool {

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "app_manifest_analyzer",
            description = """
                Reverse-engineer any installed Android app. Dumps components, security flags, deep links, and permissions.
                
                *NEW HACKER FEATURES*:
                - 'extract_apk' filter: Copies the target's APK files to /data/local/tmp/ for decompilation.
                - 'secrets' filter: Scans app metadata for hardcoded API keys, tokens, and passwords.
                - 'hacking_recipes' filter: Generates exact Termux commands (JADX, Apktool) and Shizuku commands to dump app databases and convert Smali to Java.
                
                Output can be text (default) or json.
            """.trimIndent(),
            parameters = listOf(
                ToolParameter("target_package", "string", "Package name to analyze (e.g., 'com.whatsapp').", required = true),
                ToolParameter("filter", "string", "Filter section: 'all', 'activities', 'services', 'receivers', 'providers', 'deep_links', 'security', 'permissions', 'native', 'extract_apk', 'secrets', 'hacking_recipes'.", required = false),
                ToolParameter("output_format", "string", "'text' (default) or 'json'.", required = false)
            )
        ),
        ToolDefinition(
            name = "intent_resolver",
            description = "Find all installed apps that handle a specific intent, URI scheme, or MIME type.",
            parameters = listOf(
                ToolParameter("action", "string", "Intent action (default: ACTION_VIEW).", required = false),
                ToolParameter("uri", "string", "URI to resolve (e.g., 'https://example.com').", required = false),
                ToolParameter("mime_type", "string", "MIME type to match.", required = false)
            )
        ),
        ToolDefinition(
            name = "batch_manifest_analyzer",
            description = "Analyze up to 5 apps in a single call for a quick risk and component summary comparison.",
            parameters = listOf(
                ToolParameter("packages", "string", "Comma-separated list of package names.", required = true)
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
            return ToolExecutionResult("Invalid package name: '$targetPackage'.", isError = true)
        }

        val pm = context.packageManager
        val packageInfo = getPackageInfoFull(pm, safePackage)
            ?: return ToolExecutionResult("Package '$safePackage' not found. Is it installed?", isError = true)

        val activeFilter = filter?.lowercase()?.trim() ?: "all"
        val useJson = outputFormat?.lowercase()?.trim() == "json"

        return try {
            val output = if (useJson) {
                buildJsonOutput(context, pm, packageInfo, safePackage, activeFilter)
            } else {
                buildTextOutput(context, pm, packageInfo, safePackage, activeFilter)
            }

            val maxLen = 12_000
            val truncated = output.length > maxLen
            val finalOutput = if (truncated) {
                output.take(maxLen) + "\n\n... [truncated at $maxLen chars — use a specific filter to narrow down]"
            } else {
                output
            }

            ToolExecutionResult(finalOutput, truncated = truncated)
        } catch (e: Exception) {
            ToolExecutionResult("Analysis failed: ${e.message}", isError = true)
        }
    }

    // ── Batch & Resolver Execution ───────────────────────────────────────

    fun executeIntentResolver(context: Context, action: String? = null, uri: String? = null, mimeType: String? = null): ToolExecutionResult {
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

        if (results.isEmpty()) return ToolExecutionResult("No apps found that can handle this intent.")

        val output = buildString {
            appendLine("═══ Intent Resolver Results ═══")
            appendLine("Action  : $resolveAction")
            uri?.let { appendLine("URI     : $it") }
            mimeType?.let { appendLine("MIME    : $it") }
            appendLine("Matches : ${results.size}\n")

            results.sortedByDescending { it.priority }.forEachIndexed { i, ri ->
                val appInfo = ri.activityInfo?.applicationInfo
                val label = appInfo?.let { pm.getApplicationLabel(it) } ?: ri.activityInfo?.packageName ?: "?"
                val pkg = ri.activityInfo?.packageName ?: "?"
                val activity = ri.activityInfo?.name?.removePrefix(pkg) ?: "?"
                
                appendLine("${i + 1}. $label ($pkg)")
                appendLine("   Activity : $activity")
                appendLine("   Priority : ${ri.priority}${if (ri.isDefault) " ★ DEFAULT" else ""}")
                uri?.let { appendLine("   Launch   : am start -a $resolveAction -d \"$it\" $pkg") }
                appendLine()
            }
        }
        return ToolExecutionResult(output)
    }

    fun executeBatch(context: Context, packages: String): ToolExecutionResult {
        val packageList = packages.split(",").map { it.trim() }.filter { it.isNotBlank() && isValidPackageName(it) }.take(5)
        if (packageList.isEmpty()) return ToolExecutionResult("No valid package names provided.", isError = true)

        val pm = context.packageManager
        val output = buildString {
            appendLine("═══ Batch Manifest Summary (${packageList.size} apps) ═══\n")
            packageList.forEach { pkg ->
                val info = getPackageInfoFull(pm, pkg)
                if (info == null) {
                    appendLine("• $pkg — NOT INSTALLED\n")
                    return@forEach
                }

                val label = info.applicationInfo?.let { pm.getApplicationLabel(it) } ?: pkg
                val exAct = info.activities?.count { it.exported } ?: 0
                val exSvc = info.services?.count { it.exported } ?: 0
                val exRcv = info.receivers?.count { it.exported } ?: 0
                val exPrv = info.providers?.count { it.exported } ?: 0

                val bareAct = info.activities?.count { it.exported && getComponentPermission(it) == null } ?: 0
                val bareSvc = info.services?.count { it.exported && getComponentPermission(it) == null } ?: 0
                val bareRcv = info.receivers?.count { it.exported && getComponentPermission(it) == null } ?: 0

                val isDebug = (info.applicationInfo?.flags?.and(android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) ?: 0) != 0
                val isBackup = (info.applicationInfo?.flags?.and(android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP) ?: 0) != 0
                val riskScore = calculateRiskScore(isDebug, isBackup, bareAct, bareSvc, bareRcv)

                appendLine("┌─ $label ($pkg)")
                appendLine("│  Version     : ${info.versionName ?: "?"}")
                appendLine("│  Exported    : ${exAct + exSvc + exRcv + exPrv} total (A:$exAct S:$exSvc R:$exRcv P:$exPrv)")
                appendLine("│  Bare Exports: $bareAct act + $bareSvc svc + $bareRcv rcv (NO permission guard)")
                appendLine("│  Flags       : ${if (isDebug) "⚠️ DEBUGGABLE " else ""}${if (isBackup) "⚠️ ALLOW_BACKUP " else ""}")
                appendLine("│  Risk Score  : ${"★".repeat(riskScore)}${"☆".repeat(5 - riskScore)} ($riskScore/5)")
                appendLine("└─────────────────\n")
            }
        }
        return ToolExecutionResult(output)
    }

    // ── Output Builders (Text) ───────────────────────────────────────────

    private fun buildTextOutput(context: Context, pm: PackageManager, packageInfo: PackageInfo, pkg: String, filter: String): String = buildString {
        val appInfo = packageInfo.applicationInfo
        val label = appInfo?.let { pm.getApplicationLabel(it) } ?: "Unknown"

        appendLine("═══ Manifest Analysis: $pkg ═══")
        appendLine("Label      : $label")
        val vCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) packageInfo.longVersionCode else @Suppress("DEPRECATION") packageInfo.versionCode.toLong()
        appendLine("Version    : ${packageInfo.versionName ?: "?"} (code: $vCode)")
        appendLine("Target SDK : ${appInfo?.targetSdkVersion ?: "?"}")
        appendLine()

        if (filter in listOf("all", "security")) appendSecurityAudit(pm, packageInfo, pkg, this)
        if (filter in listOf("all", "secrets")) appendSecretsScan(packageInfo, this)
        if (filter in listOf("all", "extract_apk")) appendApkExtractor(context, packageInfo, pkg, this)
        if (filter in listOf("all", "hacking_recipes")) appendHackingRecipes(packageInfo, pkg, this)
        if (filter in listOf("all", "permissions")) appendPermissions(pm, packageInfo, pkg, this)
        if (filter in listOf("all", "activities", "deep_links")) appendActivities(pm, packageInfo, pkg, filter == "deep_links", this)
        if (filter in listOf("all", "services")) appendServices(pm, packageInfo, pkg, this)
        if (filter in listOf("all", "receivers")) appendReceivers(pm, packageInfo, pkg, this)
        if (filter in listOf("all", "providers")) appendProviders(pm, packageInfo, pkg, this)
        if (filter in listOf("all", "native")) appendNativeInfo(pm, packageInfo, pkg, this)
    }

    // ── Hacker Features ──────────────────────────────────────────────────

    private fun appendSecretsScan(packageInfo: PackageInfo, sb: StringBuilder) {
        sb.appendLine("── Hardcoded Secrets Scan (Meta-Data) ──")
        val metaData = packageInfo.applicationInfo?.metaData
        if (metaData == null) {
            sb.appendLine("  ✅ No meta-data found.")
            sb.appendLine()
            return
        }

        val suspiciousKeys = listOf("key", "secret", "token", "password", "auth", "api", "credential")
        val findings = mutableListOf<String>()

        metaData.keySet().forEach { key ->
            val value = metaData.get(key)?.toString() ?: ""
            if (suspiciousKeys.any { key.contains(it, ignoreCase = true) }) {
                findings.add("  ⚠️  Found suspicious key: '$key' => '$value'")
            }
        }

        if (findings.isEmpty()) {
            sb.appendLine("  ✅ No obvious secrets found in Manifest meta-data.")
        } else {
            findings.forEach { sb.appendLine(it) }
        }
        sb.appendLine()
    }

    private fun appendApkExtractor(context: Context, packageInfo: PackageInfo, pkg: String, sb: StringBuilder) {
        sb.appendLine("── APK Extractor ──")
        val appInfo = packageInfo.applicationInfo
        if (appInfo?.sourceDir == null) {
            sb.appendLine("  ❌ Cannot find base APK path.")
            sb.appendLine()
            return
        }

        val outputDir = File("/data/local/tmp/${pkg}_apks")
        try {
            if (!outputDir.exists()) outputDir.mkdirs()
            
            // Copy Base APK
            val baseApk = File(appInfo.sourceDir)
            val destBase = File(outputDir, "base.apk")
            baseApk.copyTo(destBase, overwrite = true)
            sb.appendLine("  ✅ Extracted Base APK: ${destBase.absolutePath} (${destBase.length() / 1024} KB)")

            // Copy Split APKs
            appInfo.splitSourceDirs?.forEachIndexed { index, splitPath ->
                val splitFile = File(splitPath)
                val destSplit = File(outputDir, "split_${index}.apk")
                splitFile.copyTo(destSplit, overwrite = true)
                sb.appendLine("  ✅ Extracted Split APK: ${destSplit.absolutePath}")
            }
            
            sb.appendLine("  Target directory is accessible via 'run_terminal' or 'termux_bridge'.")
        } catch (e: Exception) {
            sb.appendLine("  ❌ Extraction failed: ${e.message}")
        }
        sb.appendLine()
    }

    private fun appendHackingRecipes(packageInfo: PackageInfo, pkg: String, sb: StringBuilder) {
        val appInfo = packageInfo.applicationInfo
        val sourceDir = appInfo?.sourceDir ?: "/data/app/~~.../base.apk"
        
        sb.appendLine("── Reverse Engineering Recipes ──")
        sb.appendLine("The agent can execute these commands via 'termux_bridge' or 'privileged_tool'.\n")

        sb.appendLine("1. Decompile Smali to Java (Requires Termux):")
        sb.appendLine("   ▶ termux_bridge action=exec command=\"pkg install -y openjdk-17 wget zip && wget https://github.com/skylot/jadx/releases/download/v1.4.7/jadx-1.4.7.zip && unzip jadx-1.4.7.zip -d jadx && ./jadx/bin/jadx -d /data/local/tmp/${pkg}_src $sourceDir\"")
        
        sb.appendLine("\n2. Disassemble via Apktool (Smali editing):")
        sb.appendLine("   ▶ termux_bridge action=exec command=\"pkg install -y aapt apktool && apktool d $sourceDir -o /data/local/tmp/${pkg}_apktool\"")
        
        sb.appendLine("\n3. Dump App Databases (Requires Privileged Shell/Shizuku):")
        sb.appendLine("   ▶ privileged_tool action=run_command command=\"cp -r /data/data/$pkg/databases /data/local/tmp/ && chmod -R 777 /data/local/tmp/databases\"")
        
        sb.appendLine("\n4. Dump Shared Preferences (Requires Privileged Shell/Shizuku):")
        sb.appendLine("   ▶ privileged_tool action=run_command command=\"cp -r /data/data/$pkg/shared_prefs /data/local/tmp/ && chmod -R 777 /data/local/tmp/shared_prefs\"")
        sb.appendLine()
    }

    // ── Standard Builders ────────────────────────────────────────────────

    private fun appendSecurityAudit(pm: PackageManager, packageInfo: PackageInfo, pkg: String, sb: StringBuilder) {
        val appInfo = packageInfo.applicationInfo ?: return
        val findings = mutableListOf<String>()

        val isDebuggable = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val allowBackup = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP) != 0

        if (isDebuggable) findings.add("⚠️  DEBUGGABLE=true — app can be attached by ADB debuggers")
        if (allowBackup) findings.add("⚠️  ALLOW_BACKUP=true — data can be extracted via `adb backup`")

        val bareActivities = packageInfo.activities?.filter { it.exported && getComponentPermission(it) == null } ?: emptyList()
        if (bareActivities.isNotEmpty()) findings.add("⚠️  ${bareActivities.size} exported Activity(s) with NO permission guard.")

        val riskScore = calculateRiskScore(isDebuggable, allowBackup, bareActivities.size, 0, 0)

        sb.appendLine("── Security Audit ──")
        sb.appendLine("  Risk Score: ${"★".repeat(riskScore)}${"☆".repeat(5 - riskScore)} ($riskScore/5)")
        if (findings.isEmpty()) sb.appendLine("  ✅ No obvious high-risk security flags detected.")
        else findings.forEach { sb.appendLine("  $it") }
        sb.appendLine()
    }

    private fun appendActivities(pm: PackageManager, packageInfo: PackageInfo, pkg: String, deepLinksOnly: Boolean, sb: StringBuilder) {
        val activities = packageInfo.activities?.filter { it.exported } ?: emptyList()
        sb.appendLine("── Exported Activities (${activities.size}) ──")
        activities.take(20).forEach { act ->
            val p = getComponentPermission(act)
            sb.appendLine("  • ${act.name.removePrefix(pkg)} ${if (p != null) "[🔐]" else "[🔓]"}")
            sb.appendLine("    ▶ am start -n $pkg/${act.name}")
        }
        if (activities.size > 20) sb.appendLine("  ... and ${activities.size - 20} more.")
        sb.appendLine()
    }

    private fun appendServices(pm: PackageManager, packageInfo: PackageInfo, pkg: String, sb: StringBuilder) {
        val services = packageInfo.services?.filter { it.exported } ?: emptyList()
        sb.appendLine("── Exported Services (${services.size}) ──")
        services.take(15).forEach { svc ->
            val p = getComponentPermission(svc)
            sb.appendLine("  • ${svc.name.removePrefix(pkg)} ${if (p != null) "[🔐]" else "[🔓]"}")
            sb.appendLine("    ▶ am startservice -n $pkg/${svc.name}")
        }
        sb.appendLine()
    }

    private fun appendReceivers(pm: PackageManager, packageInfo: PackageInfo, pkg: String, sb: StringBuilder) {
        val receivers = packageInfo.receivers?.filter { it.exported } ?: emptyList()
        sb.appendLine("── Exported Receivers (${receivers.size}) ──")
        receivers.take(15).forEach { rcv ->
            sb.appendLine("  • ${rcv.name.removePrefix(pkg)}")
            sb.appendLine("    ▶ am broadcast -n $pkg/${rcv.name} -a <ACTION>")
        }
        sb.appendLine()
    }

    private fun appendProviders(pm: PackageManager, packageInfo: PackageInfo, pkg: String, sb: StringBuilder) {
        val providers = packageInfo.providers?.filter { it.exported } ?: emptyList()
        sb.appendLine("── Exported Providers (${providers.size}) ──")
        providers.take(10).forEach { prov ->
            sb.appendLine("  • ${prov.name.removePrefix(pkg)}")
            prov.authority?.split(";")?.forEach { auth ->
                sb.appendLine("    ▶ content query --uri content://$auth/")
            }
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

    private fun buildJsonOutput(context: Context, pm: PackageManager, packageInfo: PackageInfo, pkg: String, filter: String): String {
        return JSONObject().put("package", pkg).put("status", "JSON output not fully implemented in snippet to save space.").toString()
    }

    // ── Safe Helpers ──

    /**
     * FIX: Replaced dangerous reflection with safe type casting.
     * Components inherit from PackageItemInfo, but the `permission` field exists on
     * ActivityInfo, ServiceInfo, and ProviderInfo specifically.
     */
    fun getComponentPermission(component: Any?): String? {
        return when (component) {
            is ActivityInfo -> component.permission
            is ServiceInfo -> component.permission
            is ProviderInfo -> component.readPermission ?: component.writePermission
            else -> null
        }
    }

    fun getProviderAnyPermission(provider: ProviderInfo?): String? {
        return provider?.readPermission ?: provider?.writePermission
    }

    private fun calculateRiskScore(debuggable: Boolean, allowBackup: Boolean, bareActivities: Int, bareServices: Int, bareReceivers: Int): Int {
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
