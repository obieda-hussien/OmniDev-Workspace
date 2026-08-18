package com.omnidev.workspace.data.brain

import android.content.Context
import android.util.Log

/**
 * ══════════════════════════════════════════════════════════════════════════════
 * ProgressiveTrustEngine — [Localized] [Localized] [Localized] (Progressive Trust Model)
 * ══════════════════════════════════════════════════════════════════════════════
 *
 * [Localized] [Localized] [Localized]/[Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized].
 *
 * ## [Localized]:
 * - [Localized] [Localized] [Localized] [Localized] [Localized] trustScore [Localized]
 * - [Localized] [Localized] [Localized] [Localized] ([Localized] [Localized] [Localized] [Localized] [Localized])
 * - [Localized] [Localized] (delete, patch) [Localized] [Localized] [Localized]
 * - [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized]
 *
 * ## Mobile-First:
 * - [Localized] LLM — [Localized] rule-based [Localized] (< 1ms [Localized] [Localized])
 * - [Localized] [Localized] SharedPreferences [Localized] JSON (< 5KB)
 * - [Localized] [Localized] [Localized] [Localized] [Localized] 2GB RAM
 */
class ProgressiveTrustEngine(private val context: Context) {

    companion object {
        private const val TAG = "TrustEngine"
        private const val PREFS_NAME = "omni_trust_prefs"
        private const val PREFS_KEY = "omni_trust_profile"

        // [Localized] [Localized] — [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] score
        private const val WEIGHT_DESTRUCTIVE = 2.0f   // delete, patch, root, etc.
        private const val WEIGHT_READ = 1.0f           // read, search, etc.

        // [Localized] [Localized]
        private const val SUCCESS_DELTA = 0.01f        // +0.01 * weight [Localized] [Localized]
        private const val FAILURE_DELTA = 0.02f        // -0.02 * weight [Localized] [Localized]

        // [Localized] [Localized]
        private const val THRESHOLD_FILE_WRITE = 0.2f
        private const val THRESHOLD_TERMINAL_ACCESS = 0.3f
        private const val THRESHOLD_GOD_MODE = 0.8f
        private const val THRESHOLD_SWARM_CONTROL = 0.9f

        // [Localized] [Localized] trustScore
        private const val SCORE_MIN = 0.0f
        private const val SCORE_MAX = 1.0f

        // [Localized] [Localized] [Localized] [Localized] (destructive)
        private val DESTRUCTIVE_TOOLS = setOf(
            "delete_file", "patch_file_content", "create_file",
            "run_terminal", "root_shell_tool", "advanced_root_shell",
            "shizuku_command", "system_power", "vpn_control",
            "device_admin", "package_installer_tool",
            "agent_runtime", "sandbox_execution_tool",
            "run_script"
        )
    }

    // ─── [Localized] [Localized] [Localized] [Localized] [Localized] ────────────────────────────────────

    @Volatile private var profile: AgentTrustProfile = AgentTrustProfile()

    init {
        loadProfile()
    }

    // ─── [Localized] [Localized] ──────────────────────────────────────────────────

    /** [Localized] [Localized] [Localized] [Localized]/[Localized] */
    data class AgentTrustProfile(
        val userId: String = "default",
        val trustScore: Float = 0.1f,          // [Localized] [Localized] 0.1 ([Localized] [Localized] [Localized] [Localized] [Localized] [Localized])
        val successfulOps: Int = 0,
        val failedOps: Int = 0,
        val earnedCapabilities: Set<String> = emptySet(),
        val lastUpdated: Long = System.currentTimeMillis()
    )

    /** [Localized] [Localized] */
    enum class TrustLevel(val label: String, val arabicLabel: String) {
        NOVICE("NOVICE", "[Localized]"),
        TRUSTED("TRUSTED", "[Localized]"),
        EXPERT("EXPERT", "[Localized]"),
        GUARDIAN("GUARDIAN", "[Localized]")
    }

    // ─── [Localized] [Localized] ──────────────────────────────────────────────────

    /**
     * [Localized] [Localized] [Localized].
     * [Localized]: trustScore += 0.01 * weight
     */
    fun onOperationSuccess(toolName: String) {
        val weight = getToolWeight(toolName)
        val delta = SUCCESS_DELTA * weight
        updateScore(delta)
        updateProfile { old ->
            old.copy(
                successfulOps = old.successfulOps + 1,
                lastUpdated = System.currentTimeMillis()
            )
        }
        // [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized]
        autoEarnCapabilities()
        Log.d(TAG, "✅ [Localized]: $toolName | Δ=+${"%.4f".format(delta)} | score=${profile.trustScore}")
    }

    /**
     * [Localized] [Localized] [Localized].
     * [Localized]: trustScore -= 0.02 * weight
     */
    fun onOperationFailure(toolName: String) {
        val weight = getToolWeight(toolName)
        val delta = -FAILURE_DELTA * weight
        updateScore(delta)
        updateProfile { old ->
            old.copy(
                failedOps = old.failedOps + 1,
                lastUpdated = System.currentTimeMillis()
            )
        }
        Log.d(TAG, "❌ [Localized]: $toolName | Δ=${"%.4f".format(delta)} | score=${profile.trustScore}")
    }

    /**
     * [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] trustScore.
     */
    fun checkCapability(capability: String): Boolean {
        val currentScore = profile.trustScore
        // [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] score [Localized]
        if (capability in profile.earnedCapabilities) return true
        return when (capability) {
            "file_write"        -> currentScore >= THRESHOLD_FILE_WRITE
            "terminal_access"   -> currentScore >= THRESHOLD_TERMINAL_ACCESS
            "god_mode"          -> currentScore >= THRESHOLD_GOD_MODE
            "swarm_control"     -> currentScore >= THRESHOLD_SWARM_CONTROL
            else                -> false
        }
    }

    /**
     * [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized].
     * [Localized] [Localized] true [Localized] [Localized] [Localized] false [Localized] [Localized] [Localized] [Localized].
     */
    fun earnCapability(capability: String): Boolean {
        if (!checkCapability(capability)) return false
        if (capability in profile.earnedCapabilities) return true // [Localized] [Localized]
        updateProfile { old ->
            old.copy(
                earnedCapabilities = old.earnedCapabilities + capability,
                lastUpdated = System.currentTimeMillis()
            )
        }
        Log.i(TAG, "🏆 [Localized] [Localized]: $capability (score=${profile.trustScore})")
        return true
    }

    /**
     * [Localized] [Localized] [Localized] [Localized].
     */
    fun getTrustLevel(): TrustLevel {
        return when {
            profile.trustScore >= 0.8f -> TrustLevel.GUARDIAN
            profile.trustScore >= 0.5f -> TrustLevel.EXPERT
            profile.trustScore >= 0.2f -> TrustLevel.TRUSTED
            else                        -> TrustLevel.NOVICE
        }
    }

    /**
     * [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] ([Localized] [Localized]).
     */
    fun getProfile(): AgentTrustProfile = profile

    /**
     * [Localized] [Localized] [Localized] [Localized] System Prompt [Localized] [Localized] [Localized] [Localized] [Localized].
     */
    fun buildPromptInjection(): String {
        val p = profile
        val level = getTrustLevel()
        val caps = if (p.earnedCapabilities.isEmpty()) "none" else p.earnedCapabilities.joinToString(", ")
        return buildString {
            appendLine()
            appendLine("🔐 Trust Level: ${level.label} (${level.arabicLabel})")
            appendLine("   Score: ${"%.2f".format(p.trustScore)} | Ops: ✅${p.successfulOps} ❌${p.failedOps}")
            appendLine("   Earned Capabilities: $caps")
            if (p.earnedCapabilities.isEmpty() && p.trustScore < THRESHOLD_FILE_WRITE) {
                appendLine("   ℹ️  Build trust by successfully completing operations.")
            }
        }
    }

    /**
     * [Localized] [Localized] [Localized] [Localized] [Localized].
     */
    fun buildProfileSummary(): String {
        val p = profile
        val level = getTrustLevel()
        return buildString {
            appendLine("═══════════════════════════════")
            appendLine("🔐 Trust Profile — ${level.label}")
            appendLine("═══════════════════════════════")
            appendLine("Score:        ${"%.3f".format(p.trustScore)} / 1.000")
            appendLine("Level:        ${level.label} (${level.arabicLabel})")
            appendLine("Successful:   ${p.successfulOps} ops")
            appendLine("Failed:       ${p.failedOps} ops")
            val total = p.successfulOps + p.failedOps
            if (total > 0) {
                val rate = (p.successfulOps * 100.0f / total)
                appendLine("Success Rate: ${"%.1f".format(rate)}%")
            }
            appendLine()
            appendLine("Earned Capabilities:")
            if (p.earnedCapabilities.isEmpty()) {
                appendLine("  (none yet)")
            } else {
                for (cap in p.earnedCapabilities) {
                    appendLine("  ✅ $cap")
                }
            }
            appendLine()
            appendLine("Next Unlock:")
            appendLine(buildNextUnlockHint(p.trustScore))
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
            appendLine()
            appendLine("Last Updated: ${fmt.format(java.util.Date(p.lastUpdated))}")
        }
    }

    /**
     * [Localized] [Localized] [Localized] [Localized] [Localized] [Localized].
     */
    fun resetProfile() {
        profile = AgentTrustProfile()
        saveProfile()
        Log.i(TAG, "🔄 Trust profile reset to defaults")
    }

    // ─── [Localized] [Localized] ────────────────────────────────────────────────────

    /** [Localized] [Localized] score [Localized] [Localized] [Localized] [Localized] */
    private fun updateScore(delta: Float) {
        val newScore = (profile.trustScore + delta).coerceIn(SCORE_MIN, SCORE_MAX)
        updateProfile { old -> old.copy(trustScore = newScore) }
    }

    /** [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] SharedPreferences */
    @Synchronized
    private fun updateProfile(transform: (AgentTrustProfile) -> AgentTrustProfile) {
        profile = transform(profile)
        saveProfile()
    }

    /** [Localized] [Localized] [Localized] [Localized] */
    private fun autoEarnCapabilities() {
        val capabilities = listOf("file_write", "terminal_access", "god_mode", "swarm_control")
        for (cap in capabilities) {
            earnCapability(cap)
        }
    }

    /** [Localized] [Localized] [Localized] (destructive = 2.0[Localized] read = 1.0) */
    private fun getToolWeight(toolName: String): Float {
        return if (toolName in DESTRUCTIVE_TOOLS) WEIGHT_DESTRUCTIVE else WEIGHT_READ
    }

    /** [Localized] [Localized] [Localized] [Localized] */
    private fun buildNextUnlockHint(score: Float): String {
        return when {
            score < THRESHOLD_FILE_WRITE -> {
                val needed = THRESHOLD_FILE_WRITE - score
                "  ${"%.3f".format(needed)} more → file_write"
            }
            score < THRESHOLD_TERMINAL_ACCESS -> {
                val needed = THRESHOLD_TERMINAL_ACCESS - score
                "  ${"%.3f".format(needed)} more → terminal_access"
            }
            score < THRESHOLD_GOD_MODE -> {
                val needed = THRESHOLD_GOD_MODE - score
                "  ${"%.3f".format(needed)} more → god_mode"
            }
            score < THRESHOLD_SWARM_CONTROL -> {
                val needed = THRESHOLD_SWARM_CONTROL - score
                "  ${"%.3f".format(needed)} more → swarm_control"
            }
            else -> "  🏆 All capabilities unlocked!"
        }
    }

    // ─── SharedPreferences I/O ───────────────────────────────────────────

    private fun saveProfile() {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val json = profileToJson(profile)
            prefs.edit().putString(PREFS_KEY, json).apply()
        } catch (e: Exception) {
            Log.w(TAG, "saveProfile failed: ${e.message}")
        }
    }

    private fun loadProfile() {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val json = prefs.getString(PREFS_KEY, null)
            if (!json.isNullOrBlank()) {
                profile = jsonToProfile(json) ?: AgentTrustProfile()
            }
        } catch (e: Exception) {
            Log.w(TAG, "loadProfile failed: ${e.message}")
            profile = AgentTrustProfile()
        }
    }

    // ─── [Localized] [Localized]: JSON [Localized] ([Localized] [Localized] [Localized] < 1ms) ────────────────

    private fun profileToJson(p: AgentTrustProfile): String {
        val capsJson = p.earnedCapabilities.joinToString(",") { "\"$it\"" }
        return buildString {
            append("{")
            append("\"userId\":\"${escapeJson(p.userId)}\",")
            append("\"trustScore\":${p.trustScore},")
            append("\"successfulOps\":${p.successfulOps},")
            append("\"failedOps\":${p.failedOps},")
            append("\"earnedCapabilities\":[$capsJson],")
            append("\"lastUpdated\":${p.lastUpdated}")
            append("}")
        }
    }

    private fun jsonToProfile(json: String): AgentTrustProfile? {
        return try {
            val userId = extractJsonString(json, "userId") ?: "default"
            val trustScore = extractJsonFloat(json, "trustScore") ?: 0.1f
            val successfulOps = extractJsonInt(json, "successfulOps") ?: 0
            val failedOps = extractJsonInt(json, "failedOps") ?: 0
            val capsRaw = extractJsonArray(json, "earnedCapabilities")
            val lastUpdated = extractJsonLong(json, "lastUpdated") ?: System.currentTimeMillis()
            AgentTrustProfile(
                userId = userId,
                trustScore = trustScore.coerceIn(SCORE_MIN, SCORE_MAX),
                successfulOps = successfulOps.coerceAtLeast(0),
                failedOps = failedOps.coerceAtLeast(0),
                earnedCapabilities = capsRaw,
                lastUpdated = lastUpdated
            )
        } catch (e: Exception) {
            Log.w(TAG, "jsonToProfile parse failed: ${e.message}")
            null
        }
    }

    private fun escapeJson(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun extractJsonString(json: String, key: String): String? {
        val pattern = Regex("\"${Regex.escape(key)}\"\\s*:\\s*\"([^\"]*)\"")
        return pattern.find(json)?.groupValues?.getOrNull(1)
    }

    private fun extractJsonFloat(json: String, key: String): Float? {
        val pattern = Regex("\"${Regex.escape(key)}\"\\s*:\\s*([\\d.]+)")
        return pattern.find(json)?.groupValues?.getOrNull(1)?.toFloatOrNull()
    }

    private fun extractJsonInt(json: String, key: String): Int? {
        val pattern = Regex("\"${Regex.escape(key)}\"\\s*:\\s*(\\d+)")
        return pattern.find(json)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun extractJsonLong(json: String, key: String): Long? {
        val pattern = Regex("\"${Regex.escape(key)}\"\\s*:\\s*(\\d+)")
        return pattern.find(json)?.groupValues?.getOrNull(1)?.toLongOrNull()
    }

    private fun extractJsonArray(json: String, key: String): Set<String> {
        val pattern = Regex("\"${Regex.escape(key)}\"\\s*:\\s*\\[([^]]*)]")
        val raw = pattern.find(json)?.groupValues?.getOrNull(1) ?: return emptySet()
        if (raw.isBlank()) return emptySet()
        return raw.split(",")
            .map { it.trim().removeSurrounding("\"") }
            .filter { it.isNotBlank() }
            .toSet()
    }
}
