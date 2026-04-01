package com.omnidev.workspace.data.tools.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.omnidev.workspace.data.tools.ShizukuCommandTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Advanced Security Analyzer - نظام التحليل الأمني المتقدم
 * 
 * يوفر:
 * - تحليل ثغرات التطبيقات
 * - فحص الأذونات الخطرة
 * - كشف البرمجيات الخبيثة
 * - تحليل الشبكة والاتصالات
 * - مراقبة سلوك التطبيقات
 */
object AdvancedSecurityAnalyzer {

    private val json = Json { 
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    // ═══════════════════════════════════════════════════════════
    // Data Models
    // ═══════════════════════════════════════════════════════════

    @Serializable
    data class SecurityReport(
        val packageName: String,
        val timestamp: Long,
        val overallRiskScore: Int, // 0-100
        val vulnerabilities: List<Vulnerability>,
        val permissions: PermissionAnalysis,
        val networkAnalysis: NetworkAnalysis,
        val behaviorAnalysis: BehaviorAnalysis,
        val malwareIndicators: List<MalwareIndicator>,
        val recommendations: List<String>
    )

    @Serializable
    data class Vulnerability(
        val id: String,
        val severity: Severity,
        val category: VulnerabilityCategory,
        val title: String,
        val description: String,
        val cveId: String? = null,
        val affectedComponent: String,
        val exploitability: ExploitLevel,
        val remediation: String
    )

    @Serializable
    enum class Severity {
        CRITICAL, HIGH, MEDIUM, LOW, INFO
    }

    @Serializable
    enum class VulnerabilityCategory {
        INJECTION, // SQL Injection, Command Injection
        AUTHENTICATION, // Weak auth, hardcoded credentials
        CRYPTO, // Weak encryption, hardcoded keys
        PERMISSION, // Over-privileged, dangerous permissions
        NETWORK, // Insecure connections, cleartext traffic
        STORAGE, // Insecure file storage, world-readable files
        IPC, // Intent spoofing, broadcast injection
        WEBVIEW, // JavaScript enabled, file access
        NATIVE, // Buffer overflow, memory corruption
        PRIVACY // Data leakage, tracking
    }

    @Serializable
    enum class ExploitLevel {
        ACTIVE_EXPLOIT, // Known exploit in the wild
        POC_AVAILABLE, // Proof of concept available
        THEORETICAL, // Theoretically exploitable
        DIFFICULT, // Hard to exploit
        NONE // Not exploitable
    }

    @Serializable
    data class PermissionAnalysis(
        val declared: List<String>,
        val dangerous: List<DangerousPermission>,
        val signature: List<String>,
        val overPrivileged: List<String>,
        val runtimeGranted: List<String>,
        val unusedPermissions: List<String>
    )

    @Serializable
    data class DangerousPermission(
        val name: String,
        val group: String,
        val riskLevel: Int, // 0-10
        val justification: String?,
        val alternatives: List<String>
    )

    @Serializable
    data class NetworkAnalysis(
        val usesCleartextTraffic: Boolean,
        val certificatePinning: Boolean,
        val trustsUserCerts: Boolean,
        val domains: List<TrackedDomain>,
        val dnsSecurity: DnsSecurityStatus,
        val tlsVersions: List<String>,
        val networkSecurityConfig: String?
    )

    @Serializable
    data class TrackedDomain(
        val domain: String,
        val category: DomainCategory,
        val firstSeen: Long,
        val requestCount: Int,
        val reputation: DomainReputation
    )

    @Serializable
    enum class DomainCategory {
        ANALYTICS, ADVERTISING, TRACKING, MALWARE, CDN, API, UNKNOWN
    }

    @Serializable
    data class DomainReputation(
        val score: Int, // 0-100
        val blacklisted: Boolean,
        val sources: List<String>
    )

    @Serializable
    enum class DnsSecurityStatus {
        SECURE, // Uses DoH/DoT
        ENCRYPTED, // Uses DNS over TLS
        INSECURE // Plain DNS
    }

    @Serializable
    data class BehaviorAnalysis(
        val autoStart: Boolean,
        val backgroundActivity: BackgroundActivityLevel,
        val dataUsage: DataUsagePattern,
        val batteryImpact: BatteryImpact,
        val fileSystemAccess: List<FileSystemAccess>,
        val processInjection: Boolean,
        val rootDetection: Boolean,
        val emulatorDetection: Boolean,
        val debuggerDetection: Boolean
    )

    @Serializable
    enum class BackgroundActivityLevel {
        NONE, LOW, MODERATE, HIGH, EXCESSIVE
    }

    @Serializable
    data class DataUsagePattern(
        val uploadedMB: Double,
        val downloadedMB: Double,
        val suspiciousPatterns: List<String>
    )

    @Serializable
    data class BatteryImpact(
        val level: ImpactLevel,
        val wakeLocks: Int,
        val cpuUsagePercent: Double
    )

    @Serializable
    enum class ImpactLevel {
        MINIMAL, LOW, MODERATE, HIGH, SEVERE
    }

    @Serializable
    data class FileSystemAccess(
        val path: String,
        val accessType: AccessType,
        val frequency: Int,
        val suspicious: Boolean
    )

    @Serializable
    enum class AccessType {
        READ, WRITE, EXECUTE, DELETE
    }

    @Serializable
    data class MalwareIndicator(
        val type: MalwareType,
        val confidence: Double, // 0.0-1.0
        val evidence: String,
        val signature: String?
    )

    @Serializable
    enum class MalwareType {
        TROJAN, SPYWARE, ADWARE, RANSOMWARE, ROOTKIT, BOTNET, BACKDOOR, UNKNOWN
    }

    // ═══════════════════════════════════════════════════════════
    // Cache
    // ═══════════════════════════════════════════════════════════

    private val reportCache = ConcurrentHashMap<String, SecurityReport>()
    private const val CACHE_TTL_MS = 3600_000L // 1 hour

    // ═══════════════════════════════════════════════════════════
    // Main Analysis Engine
    // ═══════════════════════════════════════════════════════════

    suspend fun analyzePackage(
        context: Context,
        packageName: String,
        deepScan: Boolean = false
    ): SecurityReport = withContext(Dispatchers.IO) {
        
        // Check cache first
        reportCache[packageName]?.let { cached ->
            if (System.currentTimeMillis() - cached.timestamp < CACHE_TTL_MS) {
                return@withContext cached
            }
        }

        val vulnerabilities = mutableListOf<Vulnerability>()
        val malwareIndicators = mutableListOf<MalwareIndicator>()
        val recommendations = mutableListOf<String>()

        // 1. Permission Analysis
        val permissionAnalysis = analyzePermissions(context, packageName)
        vulnerabilities.addAll(generatePermissionVulnerabilities(permissionAnalysis))

        // 2. Network Security Analysis
        val networkAnalysis = analyzeNetwork(context, packageName, deepScan)
        vulnerabilities.addAll(generateNetworkVulnerabilities(networkAnalysis))

        // 3. Behavior Analysis
        val behaviorAnalysis = analyzeBehavior(context, packageName, deepScan)
        vulnerabilities.addAll(generateBehaviorVulnerabilities(behaviorAnalysis))

        // 4. Malware Detection
        malwareIndicators.addAll(detectMalware(context, packageName, deepScan))

        // 5. Component Analysis
        vulnerabilities.addAll(analyzeComponents(context, packageName))

        // 6. Cryptography Analysis
        vulnerabilities.addAll(analyzeCryptography(context, packageName))

        // 7. Generate Recommendations
        recommendations.addAll(generateRecommendations(vulnerabilities, malwareIndicators))

        // Calculate overall risk score
        val riskScore = calculateRiskScore(vulnerabilities, malwareIndicators)

        val report = SecurityReport(
            packageName = packageName,
            timestamp = System.currentTimeMillis(),
            overallRiskScore = riskScore,
            vulnerabilities = vulnerabilities,
            permissions = permissionAnalysis,
            networkAnalysis = networkAnalysis,
            behaviorAnalysis = behaviorAnalysis,
            malwareIndicators = malwareIndicators,
            recommendations = recommendations
        )

        reportCache[packageName] = report
        report
    }

    // ═══════════════════════════════════════════════════════════
    // Permission Analysis
    // ═══════════════════════════════════════════════════════════

    private suspend fun analyzePermissions(
        context: Context,
        packageName: String
    ): PermissionAnalysis {
        val pm = context.packageManager
        val packageInfo = pm.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
        
        val declared = packageInfo.requestedPermissions?.toList() ?: emptyList()
        val dangerous = declared.filter { isDangerousPermission(it) }
            .map { permission ->
                DangerousPermission(
                    name = permission,
                    group = getPermissionGroup(permission),
                    riskLevel = calculatePermissionRisk(permission),
                    justification = null,
                    alternatives = suggestAlternatives(permission)
                )
            }

        val signature = declared.filter { isSignaturePermission(it) }
        val overPrivileged = detectOverPrivilegedPermissions(context, packageName, declared)
        val runtimeGranted = declared.filter { isPermissionGranted(context, packageName, it) }
        val unusedPermissions = detectUnusedPermissions(context, packageName, declared)

        return PermissionAnalysis(
            declared = declared,
            dangerous = dangerous,
            signature = signature,
            overPrivileged = overPrivileged,
            runtimeGranted = runtimeGranted,
            unusedPermissions = unusedPermissions
        )
    }

    private fun isDangerousPermission(permission: String): Boolean {
        val dangerousPermissions = setOf(
            "android.permission.READ_CONTACTS",
            "android.permission.WRITE_CONTACTS",
            "android.permission.READ_CALL_LOG",
            "android.permission.WRITE_CALL_LOG",
            "android.permission.READ_SMS",
            "android.permission.SEND_SMS",
            "android.permission.RECEIVE_SMS",
            "android.permission.CAMERA",
            "android.permission.RECORD_AUDIO",
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.WRITE_EXTERNAL_STORAGE"
        )
        return permission in dangerousPermissions
    }

    private fun getPermissionGroup(permission: String): String = when {
        permission.contains("LOCATION") -> "Location"
        permission.contains("CAMERA") -> "Camera"
        permission.contains("CONTACTS") -> "Contacts"
        permission.contains("SMS") || permission.contains("CALL") -> "SMS/Phone"
        permission.contains("STORAGE") || permission.contains("MEDIA") -> "Storage"
        else -> "Other"
    }

    private fun calculatePermissionRisk(permission: String): Int = when (permission) {
        "android.permission.READ_SMS", "android.permission.SEND_SMS" -> 9
        "android.permission.READ_CONTACTS" -> 8
        "android.permission.ACCESS_FINE_LOCATION" -> 8
        "android.permission.CAMERA", "android.permission.RECORD_AUDIO" -> 7
        "android.permission.READ_CALL_LOG" -> 7
        else -> 5
    }

    private fun suggestAlternatives(permission: String): List<String> = when (permission) {
        "android.permission.READ_CONTACTS" -> listOf(
            "Use contact picker intent (no permission required)",
            "Request minimal contact data only"
        )
        "android.permission.ACCESS_FINE_LOCATION" -> listOf(
            "Use ACCESS_COARSE_LOCATION for general location",
            "Use foreground service for continuous tracking"
        )
        else -> emptyList()
    }

    private fun isSignaturePermission(permission: String): Boolean {
        return permission.startsWith("android.permission.") && 
               permission.contains("PRIVILEGED")
    }

    private fun isPermissionGranted(
        context: Context,
        packageName: String,
        permission: String
    ): Boolean {
        return context.packageManager.checkPermission(permission, packageName) == 
               PackageManager.PERMISSION_GRANTED
    }

    private suspend fun detectOverPrivilegedPermissions(
        context: Context,
        packageName: String,
        declared: List<String>
    ): List<String> {
        // Analyze app behavior vs declared permissions
        return emptyList() // Placeholder
    }

    private suspend fun detectUnusedPermissions(
        context: Context,
        packageName: String,
        declared: List<String>
    ): List<String> {
        // Check which permissions are never used
        return emptyList() // Placeholder
    }

    // ═══════════════════════════════════════════════════════════
    // Network Analysis
    // ═══════════════════════════════════════════════════════════

    private suspend fun analyzeNetwork(
        context: Context,
        packageName: String,
        deepScan: Boolean
    ): NetworkAnalysis {
        // Parse network security config
        val networkSecurityConfig = extractNetworkSecurityConfig(context, packageName)
        
        return NetworkAnalysis(
            usesCleartextTraffic = checkCleartextTraffic(networkSecurityConfig),
            certificatePinning = checkCertificatePinning(networkSecurityConfig),
            trustsUserCerts = checkUserCertTrust(networkSecurityConfig),
            domains = if (deepScan) analyzeNetworkTraffic(packageName) else emptyList(),
            dnsSecurity = DnsSecurityStatus.INSECURE,
            tlsVersions = listOf("TLSv1.2", "TLSv1.3"),
            networkSecurityConfig = networkSecurityConfig
        )
    }

    private fun extractNetworkSecurityConfig(context: Context, packageName: String): String? {
        // TODO: Extract from APK resources
        return null
    }

    private fun checkCleartextTraffic(config: String?): Boolean {
        return config?.contains("cleartextTrafficPermitted=\"true\"") ?: true
    }

    private fun checkCertificatePinning(config: String?): Boolean {
        return config?.contains("<pin-set>") ?: false
    }

    private fun checkUserCertTrust(config: String?): Boolean {
        return config?.contains("user") ?: false
    }

    private suspend fun analyzeNetworkTraffic(packageName: String): List<TrackedDomain> {
        // Monitor network connections using VPN service
        return emptyList() // Placeholder
    }

    // ═══════════════════════════════════════════════════════════
    // Behavior Analysis
    // ═══════════════════════════════════════════════════════════

    private suspend fun analyzeBehavior(
        context: Context,
        packageName: String,
        deepScan: Boolean
    ): BehaviorAnalysis {
        return BehaviorAnalysis(
            autoStart = checkAutoStart(context, packageName),
            backgroundActivity = BackgroundActivityLevel.MODERATE,
            dataUsage = DataUsagePattern(0.0, 0.0, emptyList()),
            batteryImpact = BatteryImpact(ImpactLevel.LOW, 0, 0.0),
            fileSystemAccess = if (deepScan) monitorFileAccess(packageName) else emptyList(),
            processInjection = false,
            rootDetection = checkRootDetection(context, packageName),
            emulatorDetection = false,
            debuggerDetection = false
        )
    }

    private fun checkAutoStart(context: Context, packageName: String): Boolean {
        val pm = context.packageManager
        val receivers = pm.queryBroadcastReceivers(
            android.content.Intent(android.content.Intent.ACTION_BOOT_COMPLETED),
            0
        )
        return receivers.any { it.activityInfo.packageName == packageName }
    }

    private suspend fun monitorFileAccess(packageName: String): List<FileSystemAccess> {
        // Monitor file system calls using strace or similar
        return emptyList()
    }

    private fun checkRootDetection(context: Context, packageName: String): Boolean {
        // Check if app detects root
        return false
    }

    // ═══════════════════════════════════════════════════════════
    // Malware Detection
    // ═══════════════════════════════════════════════════════════

    private suspend fun detectMalware(
        context: Context,
        packageName: String,
        deepScan: Boolean
    ): List<MalwareIndicator> {
        val indicators = mutableListOf<MalwareIndicator>()

        // 1. Signature-based detection
        indicators.addAll(signatureBasedDetection(context, packageName))

        // 2. Heuristic analysis
        indicators.addAll(heuristicAnalysis(context, packageName))

        // 3. Behavior-based detection
        if (deepScan) {
            indicators.addAll(behaviorBasedDetection(context, packageName))
        }

        return indicators
    }

    private suspend fun signatureBasedDetection(
        context: Context,
        packageName: String
    ): List<MalwareIndicator> {
        // Check APK hash against known malware signatures
        return emptyList()
    }

    private suspend fun heuristicAnalysis(
        context: Context,
        packageName: String
    ): List<MalwareIndicator> {
        val indicators = mutableListOf<MalwareIndicator>()
        
        // Check for suspicious patterns
        val pm = context.packageManager
        val appInfo = pm.getApplicationInfo(packageName, 0)
        
        // Hidden app (no launcher icon)
        val hasLauncher = pm.getLaunchIntentForPackage(packageName) != null
        if (!hasLauncher) {
            indicators.add(
                MalwareIndicator(
                    type = MalwareType.TROJAN,
                    confidence = 0.6,
                    evidence = "No launcher icon - hidden app",
                    signature = null
                )
            )
        }

        return indicators
    }

    private suspend fun behaviorBasedDetection(
        context: Context,
        packageName: String
    ): List<MalwareIndicator> {
        // Monitor runtime behavior for malicious patterns
        return emptyList()
    }

    // ═══════════════════════════════════════════════════════════
    // Component Analysis
    // ═══════════════════════════════════════════════════════════

    private suspend fun analyzeComponents(
        context: Context,
        packageName: String
    ): List<Vulnerability> {
        val vulnerabilities = mutableListOf<Vulnerability>()
        
        // Check exported components
        val pm = context.packageManager
        val packageInfo = pm.getPackageInfo(
            packageName,
            PackageManager.GET_ACTIVITIES or 
            PackageManager.GET_SERVICES or 
            PackageManager.GET_RECEIVERS or 
            PackageManager.GET_PROVIDERS
        )

        // Exported activities without permission
        packageInfo.activities?.filter { it.exported }?.forEach { activity ->
            if (activity.permission == null) {
                vulnerabilities.add(
                    Vulnerability(
                        id = "VULN-${packageName}-ACT-${activity.name.hashCode()}",
                        severity = Severity.MEDIUM,
                        category = VulnerabilityCategory.IPC,
                        title = "Exported Activity Without Permission",
                        description = "Activity ${activity.name} is exported without protection",
                        affectedComponent = activity.name,
                        exploitability = ExploitLevel.POC_AVAILABLE,
                        remediation = "Add android:permission or set android:exported=\"false\""
                    )
                )
            }
        }

        return vulnerabilities
    }

    // ═══════════════════════════════════════════════════════════
    // Cryptography Analysis
    // ═══════════════════════════════════════════════════════════

    private suspend fun analyzeCryptography(
        context: Context,
        packageName: String
    ): List<Vulnerability> {
        val vulnerabilities = mutableListOf<Vulnerability>()
        
        // TODO: Analyze use of weak crypto algorithms (MD5, SHA1, DES, etc.)
        // This requires DEX analysis or runtime monitoring

        return vulnerabilities
    }

    // ═══════════════════════════════════════════════════════════
    // Vulnerability Generation
    // ═══════════════════════════════════════════════════════════

    private fun generatePermissionVulnerabilities(
        permissionAnalysis: PermissionAnalysis
    ): List<Vulnerability> {
        val vulnerabilities = mutableListOf<Vulnerability>()
        
        permissionAnalysis.dangerous.forEach { dangerous ->
            if (dangerous.riskLevel >= 8) {
                vulnerabilities.add(
                    Vulnerability(
                        id = "PERM-${dangerous.name.hashCode()}",
                        severity = Severity.HIGH,
                        category = VulnerabilityCategory.PERMISSION,
                        title = "High-Risk Permission: ${dangerous.name}",
                        description = "App requests dangerous permission ${dangerous.name}",
                        affectedComponent = "Manifest",
                        exploitability = ExploitLevel.THEORETICAL,
                        remediation = dangerous.alternatives.firstOrNull() ?: "Remove if not essential"
                    )
                )
            }
        }

        return vulnerabilities
    }

    private fun generateNetworkVulnerabilities(
        networkAnalysis: NetworkAnalysis
    ): List<Vulnerability> {
        val vulnerabilities = mutableListOf<Vulnerability>()
        
        if (networkAnalysis.usesCleartextTraffic) {
            vulnerabilities.add(
                Vulnerability(
                    id = "NET-CLEARTEXT",
                    severity = Severity.HIGH,
                    category = VulnerabilityCategory.NETWORK,
                    title = "Cleartext Traffic Allowed",
                    description = "App allows unencrypted HTTP traffic",
                    cveId = "CWE-319",
                    affectedComponent = "Network Security Config",
                    exploitability = ExploitLevel.POC_AVAILABLE,
                    remediation = "Disable cleartext traffic in network_security_config.xml"
                )
            )
        }

        if (!networkAnalysis.certificatePinning) {
            vulnerabilities.add(
                Vulnerability(
                    id = "NET-NO-PINNING",
                    severity = Severity.MEDIUM,
                    category = VulnerabilityCategory.NETWORK,
                    title = "No Certificate Pinning",
                    description = "App does not implement certificate pinning",
                    affectedComponent = "Network Layer",
                    exploitability = ExploitLevel.THEORETICAL,
                    remediation = "Implement certificate pinning for critical endpoints"
                )
            )
        }

        return vulnerabilities
    }

    private fun generateBehaviorVulnerabilities(
        behaviorAnalysis: BehaviorAnalysis
    ): List<Vulnerability> {
        val vulnerabilities = mutableListOf<Vulnerability>()
        
        if (behaviorAnalysis.backgroundActivity == BackgroundActivityLevel.EXCESSIVE) {
            vulnerabilities.add(
                Vulnerability(
                    id = "BEH-EXCESSIVE-BG",
                    severity = Severity.MEDIUM,
                    category = VulnerabilityCategory.PRIVACY,
                    title = "Excessive Background Activity",
                    description = "App shows unusual background activity patterns",
                    affectedComponent = "Background Services",
                    exploitability = ExploitLevel.NONE,
                    remediation = "Review background tasks and reduce unnecessary activity"
                )
            )
        }

        return vulnerabilities
    }

    // ═══════════════════════════════════════════════════════════
    // Risk Calculation
    // ═══════════════════════════════════════════════════════════

    private fun calculateRiskScore(
        vulnerabilities: List<Vulnerability>,
        malwareIndicators: List<MalwareIndicator>
    ): Int {
        var score = 0
        
        vulnerabilities.forEach { vuln ->
            score += when (vuln.severity) {
                Severity.CRITICAL -> 20
                Severity.HIGH -> 15
                Severity.MEDIUM -> 10
                Severity.LOW -> 5
                Severity.INFO -> 1
            }
        }

        malwareIndicators.forEach { indicator ->
            score += (indicator.confidence * 30).toInt()
        }

        return score.coerceIn(0, 100)
    }

    // ═══════════════════════════════════════════════════════════
    // Recommendations
    // ═══════════════════════════════════════════════════════════

    private fun generateRecommendations(
        vulnerabilities: List<Vulnerability>,
        malwareIndicators: List<MalwareIndicator>
    ): List<String> {
        val recommendations = mutableListOf<String>()
        
        if (malwareIndicators.isNotEmpty()) {
            recommendations.add("⚠️ CRITICAL: Malware indicators detected. Uninstall immediately.")
        }

        val critical = vulnerabilities.count { it.severity == Severity.CRITICAL }
        if (critical > 0) {
            recommendations.add("🚨 Fix $critical critical vulnerabilities immediately")
        }

        val highRisk = vulnerabilities.count { it.severity == Severity.HIGH }
        if (highRisk > 0) {
            recommendations.add("⚡ Address $highRisk high-severity issues")
        }

        if (vulnerabilities.any { it.category == VulnerabilityCategory.NETWORK }) {
            recommendations.add("🔒 Strengthen network security (enable HTTPS, certificate pinning)")
        }

        if (vulnerabilities.any { it.category == VulnerabilityCategory.PERMISSION }) {
            recommendations.add("🛡️ Review and minimize dangerous permissions")
        }

        return recommendations
    }

    // ═══════════════════════════════════════════════════════════
    // Export
    // ═══════════════════════════════════════════════════════════

    fun exportReportAsJson(report: SecurityReport): String {
        return json.encodeToString(report)
    }

    fun exportReportAsHtml(report: SecurityReport): String {
        return buildString {
            append("<!DOCTYPE html><html><head>")
            append("<meta charset='UTF-8'>")
            append("<title>Security Report - ${report.packageName}</title>")
            append("<style>")
            append("body { font-family: Arial, sans-serif; margin: 20px; }")
            append(".critical { color: #d32f2f; }")
            append(".high { color: #f57c00; }")
            append(".medium { color: #fbc02d; }")
            append(".low { color: #388e3c; }")
            append(".vuln { border: 1px solid #ccc; padding: 10px; margin: 10px 0; }")
            append("</style>")
            append("</head><body>")
            append("<h1>Security Analysis Report</h1>")
            append("<h2>Package: ${report.packageName}</h2>")
            append("<p><strong>Risk Score:</strong> ${report.overallRiskScore}/100</p>")
            append("<h3>Vulnerabilities (${report.vulnerabilities.size})</h3>")
            report.vulnerabilities.forEach { vuln ->
                val cssClass = vuln.severity.name.lowercase()
                append("<div class='vuln $cssClass'>")
                append("<h4>[${vuln.severity}] ${vuln.title}</h4>")
                append("<p>${vuln.description}</p>")
                append("<p><strong>Remediation:</strong> ${vuln.remediation}</p>")
                append("</div>")
            }
            append("</body></html>")
        }
    }
}
