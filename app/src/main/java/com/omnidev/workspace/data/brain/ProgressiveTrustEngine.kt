package com.omnidev.workspace.data.brain

import android.content.Context
import android.util.Log

/**
 * ══════════════════════════════════════════════════════════════════════════════
 * ProgressiveTrustEngine — نموذج الثقة التدريجي (Progressive Trust Model)
 * ══════════════════════════════════════════════════════════════════════════════
 *
 * يُتتبّع ثقة المستخدم/الوكيل بناءً على سجل عملياته ويمنح صلاحيات تدريجياً.
 *
 * ## المبدأ:
 * - كل عملية ناجحة تزيد الـ trustScore قليلاً
 * - كل فشل يخفضه أكثر (تكلفة الفشل ضعف تكلفة النجاح)
 * - العمليات الحساسة (delete, patch) لها وزن مضاعف
 * - الصلاحيات تُمنح فقط عند تجاوز عتبات محددة
 *
 * ## Mobile-First:
 * - بدون LLM — حسابات rule-based بالكامل (< 1ms لكل تحديث)
 * - تخزين في SharedPreferences كـ JSON (< 5KB)
 * - آمن تماماً للاستخدام على أجهزة 2GB RAM
 */
class ProgressiveTrustEngine(private val context: Context) {

    companion object {
        private const val TAG = "TrustEngine"
        private const val PREFS_NAME = "omni_trust_prefs"
        private const val PREFS_KEY = "omni_trust_profile"

        // أوزان الأدوات — العمليات الحساسة تؤثر أكثر في الـ score
        private const val WEIGHT_DESTRUCTIVE = 2.0f   // delete, patch, root, etc.
        private const val WEIGHT_READ = 1.0f           // read, search, etc.

        // معدلات التغيير
        private const val SUCCESS_DELTA = 0.01f        // +0.01 * weight عند النجاح
        private const val FAILURE_DELTA = 0.02f        // -0.02 * weight عند الفشل

        // عتبات الصلاحيات
        private const val THRESHOLD_FILE_WRITE = 0.2f
        private const val THRESHOLD_TERMINAL_ACCESS = 0.3f
        private const val THRESHOLD_GOD_MODE = 0.8f
        private const val THRESHOLD_SWARM_CONTROL = 0.9f

        // حدود الـ trustScore
        private const val SCORE_MIN = 0.0f
        private const val SCORE_MAX = 1.0f

        // الأدوات ذات الوزن المزدوج (destructive)
        private val DESTRUCTIVE_TOOLS = setOf(
            "delete_file", "patch_file_content", "create_file",
            "run_terminal", "root_shell_tool", "advanced_root_shell",
            "shizuku_command", "system_power", "vpn_control",
            "device_admin", "package_installer_tool",
            "agent_runtime", "sandbox_execution_tool",
            "run_script"
        )
    }

    // ─── حالة الملف الشخصي في الذاكرة ────────────────────────────────────

    @Volatile private var profile: AgentTrustProfile = AgentTrustProfile()

    init {
        loadProfile()
    }

    // ─── نماذج البيانات ──────────────────────────────────────────────────

    /** ملف الثقة الشخصي للمستخدم/الوكيل */
    data class AgentTrustProfile(
        val userId: String = "default",
        val trustScore: Float = 0.1f,          // يبدأ من 0.1 (ليس صفراً كي لا يُحبَط الوكيل)
        val successfulOps: Int = 0,
        val failedOps: Int = 0,
        val earnedCapabilities: Set<String> = emptySet(),
        val lastUpdated: Long = System.currentTimeMillis()
    )

    /** مستويات الثقة */
    enum class TrustLevel(val label: String, val arabicLabel: String) {
        NOVICE("NOVICE", "مبتدئ"),
        TRUSTED("TRUSTED", "موثوق"),
        EXPERT("EXPERT", "خبير"),
        GUARDIAN("GUARDIAN", "حارس")
    }

    // ─── الواجهة العامة ──────────────────────────────────────────────────

    /**
     * يُسجّل عملية ناجحة.
     * الصيغة: trustScore += 0.01 * weight
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
        // تحقق من منح صلاحيات جديدة تلقائياً بعد كل نجاح
        autoEarnCapabilities()
        Log.d(TAG, "✅ نجاح: $toolName | Δ=+${"%.4f".format(delta)} | score=${profile.trustScore}")
    }

    /**
     * يُسجّل عملية فاشلة.
     * الصيغة: trustScore -= 0.02 * weight
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
        Log.d(TAG, "❌ فشل: $toolName | Δ=${"%.4f".format(delta)} | score=${profile.trustScore}")
    }

    /**
     * يتحقق من توفر صلاحية معينة بناءً على الـ trustScore.
     */
    fun checkCapability(capability: String): Boolean {
        val currentScore = profile.trustScore
        // الصلاحيات المكتسبة مسبقاً تبقى حتى لو نزل الـ score قليلاً
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
     * يُضيف صلاحية إلى القائمة المكتسبة إذا تحققت العتبة.
     * يعود بـ true إذا تمّ المنح، false إذا لم تُستوفَ العتبة.
     */
    fun earnCapability(capability: String): Boolean {
        if (!checkCapability(capability)) return false
        if (capability in profile.earnedCapabilities) return true // مُكتسبة مسبقاً
        updateProfile { old ->
            old.copy(
                earnedCapabilities = old.earnedCapabilities + capability,
                lastUpdated = System.currentTimeMillis()
            )
        }
        Log.i(TAG, "🏆 صلاحية جديدة: $capability (score=${profile.trustScore})")
        return true
    }

    /**
     * يُعيد مستوى الثقة الحالي.
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
     * يُعيد نسخة من الملف الشخصي الحالي (للقراءة فقط).
     */
    fun getProfile(): AgentTrustProfile = profile

    /**
     * يبني نص حقن للـ System Prompt يُعرّف الوكيل بمستوى الثقة والصلاحيات.
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
     * يُعيد ملخصاً نصياً للملف الشخصي.
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
     * يُعيد الملف الشخصي إلى الحالة الافتراضية.
     */
    fun resetProfile() {
        profile = AgentTrustProfile()
        saveProfile()
        Log.i(TAG, "🔄 Trust profile reset to defaults")
    }

    // ─── تنفيذ داخلي ────────────────────────────────────────────────────

    /** يُحدّث الـ score مع التحقق من الحدود */
    private fun updateScore(delta: Float) {
        val newScore = (profile.trustScore + delta).coerceIn(SCORE_MIN, SCORE_MAX)
        updateProfile { old -> old.copy(trustScore = newScore) }
    }

    /** يُحدّث الملف الشخصي وينقله فوراً إلى SharedPreferences */
    @Synchronized
    private fun updateProfile(transform: (AgentTrustProfile) -> AgentTrustProfile) {
        profile = transform(profile)
        saveProfile()
    }

    /** يمنح الصلاحيات المستوفية تلقائياً */
    private fun autoEarnCapabilities() {
        val capabilities = listOf("file_write", "terminal_access", "god_mode", "swarm_control")
        for (cap in capabilities) {
            earnCapability(cap)
        }
    }

    /** يُعيد وزن الأداة (destructive = 2.0، read = 1.0) */
    private fun getToolWeight(toolName: String): Float {
        return if (toolName in DESTRUCTIVE_TOOLS) WEIGHT_DESTRUCTIVE else WEIGHT_READ
    }

    /** بناء تلميح للصلاحية التالية */
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

    // ─── بسيط جداً: JSON يدوي (بدون مكتبة خارجية، < 1ms) ────────────────

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
