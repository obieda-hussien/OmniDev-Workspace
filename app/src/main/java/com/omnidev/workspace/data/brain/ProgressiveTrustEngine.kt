package com.omnidev.workspace.data.brain

import android.content.Context
import android.util.Log
import kotlin.math.exp

/**
 * Progressive performance-trust model for the local Agent Brain.
 *
 * Trust here means "how much evidence do we have that this agent/tooling stack executes reliably?"
 * It is NOT an authorization model. User-controlled authority such as God Mode or automatic mode
 * switching can never be earned by accumulating successful operations.
 *
 * The score is computed from Bayesian reliability evidence plus maturity rather than monotonically
 * adding a fixed amount on every success. Failures on higher-risk tools carry more evidence than
 * harmless read failures, and one long success streak cannot create permissions.
 */
class ProgressiveTrustEngine(private val context: Context) {

    companion object {
        private const val TAG = "TrustEngine"
        private const val PREFS_NAME = "omni_trust_prefs"
        private const val PREFS_KEY = "omni_trust_profile"

        private const val SCORE_MIN = 0.0f
        private const val SCORE_MAX = 1.0f
        private const val BASELINE_SCORE = 0.10f

        // Familiarity badges only. They never bypass any permission/confirmation gate.
        private const val THRESHOLD_FILE_WRITE_FAMILIARITY = 0.45f
        private const val THRESHOLD_TERMINAL_FAMILIARITY = 0.55f

        private const val BETA_PRIOR_SUCCESS = 2.0f
        private const val BETA_PRIOR_FAILURE = 2.0f
        private const val MATURITY_HALF_LIFE_EVIDENCE = 18.0f

        private val USER_AUTHORITY_CAPABILITIES = setOf(
            "god_mode",
            "swarm_control"
        )

        private val FAMILIARITY_CAPABILITIES = setOf(
            "file_write",
            "terminal_access"
        )

        private val HIGH_RISK_TOOLS = setOf(
            "delete_file", "patch_file_content", "patch_file", "create_file", "write_file",
            "advanced_terminal", "direct_terminal", "execute_terminal_command", "run_terminal",
            "root_shell_tool", "advanced_root_shell", "shizuku_command", "system_power",
            "vpn_control", "device_admin", "package_installer_tool", "package_installer",
            "agent_runtime", "sandbox_execution_tool", "run_script"
        )

        private val READ_ONLY_HINTS = listOf(
            "read", "search", "grep", "find", "list", "inspect", "status", "info", "query",
            "fetch", "dump", "screenshot"
        )
    }

    @Volatile
    private var profile: AgentTrustProfile = AgentTrustProfile()

    init {
        loadProfile()
    }

    data class AgentTrustProfile(
        val userId: String = "default",
        val trustScore: Float = BASELINE_SCORE,
        val successfulOps: Int = 0,
        val failedOps: Int = 0,
        /** Weighted Bayesian evidence; higher-risk failures count more heavily. */
        val successEvidence: Float = 0f,
        val failureEvidence: Float = 0f,
        /** Familiarity badges only — never authority grants. */
        val earnedCapabilities: Set<String> = emptySet(),
        val lastUpdated: Long = System.currentTimeMillis()
    )

    enum class TrustLevel(val label: String) {
        LEARNING("LEARNING"),
        ESTABLISHED("ESTABLISHED"),
        RELIABLE("RELIABLE"),
        MATURE("MATURE")
    }

    fun onOperationSuccess(toolName: String) {
        val evidence = successEvidenceWeight(toolName)
        updateProfile { old ->
            val nextSuccessEvidence = old.successEvidence + evidence
            val nextFailureEvidence = old.failureEvidence
            old.copy(
                trustScore = computeTrustScore(nextSuccessEvidence, nextFailureEvidence),
                successfulOps = old.successfulOps + 1,
                successEvidence = nextSuccessEvidence,
                failureEvidence = nextFailureEvidence,
                lastUpdated = System.currentTimeMillis()
            )
        }
        refreshFamiliarityBadges()
        Log.d(
            TAG,
            "success tool=$toolName evidence=+$evidence score=${"%.3f".format(profile.trustScore)}"
        )
    }

    fun onOperationFailure(toolName: String) {
        val evidence = failureEvidenceWeight(toolName)
        updateProfile { old ->
            val nextSuccessEvidence = old.successEvidence
            val nextFailureEvidence = old.failureEvidence + evidence
            old.copy(
                trustScore = computeTrustScore(nextSuccessEvidence, nextFailureEvidence),
                failedOps = old.failedOps + 1,
                successEvidence = nextSuccessEvidence,
                failureEvidence = nextFailureEvidence,
                lastUpdated = System.currentTimeMillis()
            )
        }
        refreshFamiliarityBadges()
        Log.d(
            TAG,
            "failure tool=$toolName evidence=+$evidence score=${"%.3f".format(profile.trustScore)}"
        )
    }

    /**
     * Compatibility API.
     *
     * Returns true only for advisory familiarity badges. User-authority capabilities are always
     * false here and must be decided by their dedicated explicit permission systems.
     */
    fun checkCapability(capability: String): Boolean {
        val normalized = capability.trim().lowercase()
        if (normalized in USER_AUTHORITY_CAPABILITIES) return false
        if (normalized in profile.earnedCapabilities) return true

        return when (normalized) {
            "file_write" -> profile.trustScore >= THRESHOLD_FILE_WRITE_FAMILIARITY
            "terminal_access" -> profile.trustScore >= THRESHOLD_TERMINAL_FAMILIARITY
            else -> false
        }
    }

    /**
     * Compatibility API for familiarity badges. Reserved user authority can never be earned.
     */
    fun earnCapability(capability: String): Boolean {
        val normalized = capability.trim().lowercase()
        if (normalized !in FAMILIARITY_CAPABILITIES) return false
        if (!checkCapability(normalized)) return false
        if (normalized in profile.earnedCapabilities) return true

        updateProfile { old ->
            old.copy(
                earnedCapabilities = sanitizeCapabilities(old.earnedCapabilities + normalized),
                lastUpdated = System.currentTimeMillis()
            )
        }
        return true
    }

    fun getTrustLevel(): TrustLevel = when {
        profile.trustScore >= 0.82f -> TrustLevel.MATURE
        profile.trustScore >= 0.68f -> TrustLevel.RELIABLE
        profile.trustScore >= 0.48f -> TrustLevel.ESTABLISHED
        else -> TrustLevel.LEARNING
    }

    fun getProfile(): AgentTrustProfile = profile

    fun reliabilityPosterior(): Float {
        val p = profile
        return posteriorReliability(p.successEvidence, p.failureEvidence)
    }

    fun evidenceMaturity(): Float {
        val total = profile.successEvidence + profile.failureEvidence
        return maturity(total)
    }

    /**
     * Prompt injection is deliberately explicit that trust is advisory and grants no authority.
     */
    fun buildPromptInjection(): String {
        val p = profile
        val level = getTrustLevel()
        val posterior = reliabilityPosterior()
        val maturity = evidenceMaturity()
        val badges = p.earnedCapabilities
            .filter { it in FAMILIARITY_CAPABILITIES }
            .sorted()
            .ifEmpty { listOf("none") }
            .joinToString(", ")

        return buildString {
            appendLine()
            appendLine("Execution reliability profile (advisory only — never permission):")
            appendLine(
                "Level=${level.label} score=${pct(p.trustScore)} posterior=${pct(posterior)} " +
                    "maturity=${pct(maturity)} ops=${p.successfulOps}/${p.failedOps}"
            )
            appendLine("Familiarity badges: $badges")
            appendLine(
                "God Mode, privileged access, confirmations, and mode switching always require " +
                    "their explicit user-controlled authority; this score cannot grant them."
            )
        }
    }

    fun buildProfileSummary(): String {
        val p = profile
        val posterior = reliabilityPosterior()
        val maturity = evidenceMaturity()
        return buildString {
            appendLine("Execution Reliability Profile")
            appendLine("────────────────────────────")
            appendLine("Score: ${"%.3f".format(p.trustScore)} / 1.000")
            appendLine("Level: ${getTrustLevel().label}")
            appendLine("Posterior reliability: ${pct(posterior)}")
            appendLine("Evidence maturity: ${pct(maturity)}")
            appendLine("Successful operations: ${p.successfulOps}")
            appendLine("Failed operations: ${p.failedOps}")
            appendLine("Success evidence: ${"%.2f".format(p.successEvidence)}")
            appendLine("Failure evidence: ${"%.2f".format(p.failureEvidence)}")
            appendLine()
            appendLine("Familiarity badges (not permissions):")
            val badges = p.earnedCapabilities.filter { it in FAMILIARITY_CAPABILITIES }.sorted()
            if (badges.isEmpty()) appendLine("  none") else badges.forEach { appendLine("  • $it") }
            appendLine()
            appendLine("User-controlled authority:")
            appendLine("  • god_mode: never granted by trust score")
            appendLine("  • swarm_control/mode switching: never granted by trust score")
            appendLine("  • destructive-operation confirmations: unchanged")
        }
    }

    fun resetProfile() {
        profile = AgentTrustProfile()
        saveProfile()
        Log.i(TAG, "Reliability profile reset to defaults")
    }

    private fun computeTrustScore(successEvidence: Float, failureEvidence: Float): Float {
        val posterior = posteriorReliability(successEvidence, failureEvidence)
        val maturity = maturity(successEvidence + failureEvidence)

        // New agents stay near baseline despite lucky early successes. As evidence matures, the
        // posterior dominates. Score can decrease again after failures; it is not an unlock meter.
        val calibrated = BASELINE_SCORE +
            (0.67f * posterior + 0.23f * maturity) * (1f - BASELINE_SCORE)
        val failurePenalty = (
            failureEvidence / (successEvidence + failureEvidence + 6f)
            ).coerceIn(0f, 0.35f) * 0.22f
        return (calibrated - failurePenalty).coerceIn(SCORE_MIN, SCORE_MAX)
    }

    private fun posteriorReliability(successEvidence: Float, failureEvidence: Float): Float =
        ((BETA_PRIOR_SUCCESS + successEvidence) /
            (BETA_PRIOR_SUCCESS + BETA_PRIOR_FAILURE + successEvidence + failureEvidence))
            .coerceIn(0f, 1f)

    private fun maturity(totalEvidence: Float): Float {
        if (totalEvidence <= 0f) return 0f
        return (1.0 - exp(-totalEvidence.toDouble() / MATURITY_HALF_LIFE_EVIDENCE.toDouble()))
            .toFloat()
            .coerceIn(0f, 1f)
    }

    private fun successEvidenceWeight(toolName: String): Float = when {
        toolName in HIGH_RISK_TOOLS -> 1.10f
        isReadOnly(toolName) -> 0.35f
        else -> 0.65f
    }

    private fun failureEvidenceWeight(toolName: String): Float = when {
        toolName in HIGH_RISK_TOOLS -> 2.20f
        isReadOnly(toolName) -> 0.80f
        else -> 1.35f
    }

    private fun isReadOnly(toolName: String): Boolean {
        val lower = toolName.lowercase()
        return READ_ONLY_HINTS.any(lower::contains) &&
            listOf("write", "patch", "delete", "execute", "terminal", "shell").none(lower::contains)
    }

    private fun refreshFamiliarityBadges() {
        val next = buildSet {
            if (profile.trustScore >= THRESHOLD_FILE_WRITE_FAMILIARITY) add("file_write")
            if (profile.trustScore >= THRESHOLD_TERMINAL_FAMILIARITY) add("terminal_access")
        }
        if (next != profile.earnedCapabilities) {
            updateProfile { old -> old.copy(earnedCapabilities = next) }
        }
    }

    private fun sanitizeCapabilities(values: Set<String>): Set<String> = values
        .map { it.trim().lowercase() }
        .filter { it in FAMILIARITY_CAPABILITIES }
        .toSet()

    @Synchronized
    private fun updateProfile(transform: (AgentTrustProfile) -> AgentTrustProfile) {
        profile = transform(profile).let { updated ->
            updated.copy(
                trustScore = updated.trustScore.coerceIn(SCORE_MIN, SCORE_MAX),
                successfulOps = updated.successfulOps.coerceAtLeast(0),
                failedOps = updated.failedOps.coerceAtLeast(0),
                successEvidence = updated.successEvidence.coerceAtLeast(0f),
                failureEvidence = updated.failureEvidence.coerceAtLeast(0f),
                earnedCapabilities = sanitizeCapabilities(updated.earnedCapabilities)
            )
        }
        saveProfile()
    }

    private fun saveProfile() {
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(PREFS_KEY, profileToJson(profile))
                .apply()
        } catch (e: Exception) {
            Log.w(TAG, "saveProfile failed: ${e.message}")
        }
    }

    private fun loadProfile() {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(PREFS_KEY, null)
            profile = if (raw.isNullOrBlank()) {
                AgentTrustProfile()
            } else {
                jsonToProfile(raw) ?: AgentTrustProfile()
            }

            // Migrate legacy linear-score profiles. Factual success/failure counts are preserved;
            // stale God Mode / swarm capabilities are stripped unconditionally.
            val hasExplicitEvidence = profile.successEvidence > 0f || profile.failureEvidence > 0f
            if (!hasExplicitEvidence && (profile.successfulOps > 0 || profile.failedOps > 0)) {
                val migratedSuccessEvidence = profile.successfulOps * 0.55f
                val migratedFailureEvidence = profile.failedOps * 1.20f
                profile = profile.copy(
                    successEvidence = migratedSuccessEvidence,
                    failureEvidence = migratedFailureEvidence,
                    trustScore = computeTrustScore(migratedSuccessEvidence, migratedFailureEvidence),
                    earnedCapabilities = sanitizeCapabilities(profile.earnedCapabilities)
                )
            } else {
                profile = profile.copy(
                    trustScore = computeTrustScore(profile.successEvidence, profile.failureEvidence),
                    earnedCapabilities = sanitizeCapabilities(profile.earnedCapabilities)
                )
            }
            refreshFamiliarityBadges()
            saveProfile()
        } catch (e: Exception) {
            Log.w(TAG, "loadProfile failed: ${e.message}")
            profile = AgentTrustProfile()
        }
    }

    private fun profileToJson(p: AgentTrustProfile): String {
        val capsJson = p.earnedCapabilities.joinToString(",") { "\"${escapeJson(it)}\"" }
        return buildString {
            append("{")
            append("\"userId\":\"${escapeJson(p.userId)}\",")
            append("\"trustScore\":${p.trustScore},")
            append("\"successfulOps\":${p.successfulOps},")
            append("\"failedOps\":${p.failedOps},")
            append("\"successEvidence\":${p.successEvidence},")
            append("\"failureEvidence\":${p.failureEvidence},")
            append("\"earnedCapabilities\":[$capsJson],")
            append("\"lastUpdated\":${p.lastUpdated}")
            append("}")
        }
    }

    private fun jsonToProfile(json: String): AgentTrustProfile? = try {
        AgentTrustProfile(
            userId = extractJsonString(json, "userId") ?: "default",
            trustScore = (extractJsonFloat(json, "trustScore") ?: BASELINE_SCORE)
                .coerceIn(SCORE_MIN, SCORE_MAX),
            successfulOps = (extractJsonInt(json, "successfulOps") ?: 0).coerceAtLeast(0),
            failedOps = (extractJsonInt(json, "failedOps") ?: 0).coerceAtLeast(0),
            successEvidence = (extractJsonFloat(json, "successEvidence") ?: 0f).coerceAtLeast(0f),
            failureEvidence = (extractJsonFloat(json, "failureEvidence") ?: 0f).coerceAtLeast(0f),
            earnedCapabilities = sanitizeCapabilities(extractJsonArray(json, "earnedCapabilities")),
            lastUpdated = extractJsonLong(json, "lastUpdated") ?: System.currentTimeMillis()
        )
    } catch (e: Exception) {
        Log.w(TAG, "jsonToProfile parse failed: ${e.message}")
        null
    }

    private fun escapeJson(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun extractJsonString(json: String, key: String): String? =
        Regex("\"${Regex.escape(key)}\"\\s*:\\s*\"([^\"]*)\"")
            .find(json)?.groupValues?.getOrNull(1)

    private fun extractJsonFloat(json: String, key: String): Float? =
        Regex("\"${Regex.escape(key)}\"\\s*:\\s*([-+]?[\\d.]+)")
            .find(json)?.groupValues?.getOrNull(1)?.toFloatOrNull()

    private fun extractJsonInt(json: String, key: String): Int? =
        Regex("\"${Regex.escape(key)}\"\\s*:\\s*(\\d+)")
            .find(json)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun extractJsonLong(json: String, key: String): Long? =
        Regex("\"${Regex.escape(key)}\"\\s*:\\s*(\\d+)")
            .find(json)?.groupValues?.getOrNull(1)?.toLongOrNull()

    private fun extractJsonArray(json: String, key: String): Set<String> {
        val raw = Regex("\"${Regex.escape(key)}\"\\s*:\\s*\\[([^]]*)]")
            .find(json)?.groupValues?.getOrNull(1) ?: return emptySet()
        if (raw.isBlank()) return emptySet()
        return raw.split(',')
            .map { it.trim().removeSurrounding("\"") }
            .filter(String::isNotBlank)
            .toSet()
    }

    private fun pct(value: Float): String = "${(value.coerceIn(0f, 1f) * 100f).toInt()}%"
}
