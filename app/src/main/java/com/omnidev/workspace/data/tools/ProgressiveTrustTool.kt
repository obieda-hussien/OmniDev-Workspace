package com.omnidev.workspace.data.tools

import com.omnidev.workspace.data.brain.ProgressiveTrustEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ══════════════════════════════════════════════════════════════════════════════
 * ProgressiveTrustTool — أدوات الثقة التدريجية
 * ══════════════════════════════════════════════════════════════════════════════
 *
 * تُعرّض [ProgressiveTrustEngine] للوكيل عبر ثلاث أدوات:
 *
 *   - **get_trust_profile**: يعرض الملف الشخصي الكامل (score، level، capabilities)
 *   - **reset_trust**: يُعيد الملف إلى الحالة الافتراضية (يطلب تأكيداً)
 *   - **list_earned_capabilities**: يسرد الصلاحيات المكتسبة والمتاحة قريباً
 *
 * ## Mobile-First:
 * - بدون LLM، بدون DB — يعمل من SharedPreferences مباشرة
 * - كل استدعاء < 1ms
 */
class ProgressiveTrustTool(
    private val trustEngine: ProgressiveTrustEngine
) {

    // ──────────────────────────────────────────────────────────────────────────
    // Tool Definitions
    // ──────────────────────────────────────────────────────────────────────────

    fun getDefinitions(): List<ToolDefinition> = listOf(

        ToolDefinition(
            name = "get_trust_profile",
            description = """عرض ملف الثقة التدريجي للوكيل.
يُظهر:
- trustScore (0.0 → 1.0): مستوى الثقة المتراكمة
- TrustLevel: NOVICE / TRUSTED / EXPERT / GUARDIAN
- عدد العمليات الناجحة والفاشلة
- الصلاحيات المكتسبة (earned capabilities)
- العتبة التالية للارتقاء

الثقة تُبنى تلقائياً مع كل عملية ناجحة.
""",
            parameters = emptyList()
        ),

        ToolDefinition(
            name = "reset_trust",
            description = """إعادة ملف الثقة إلى الحالة الافتراضية.
⚠️ هذا الإجراء لا يمكن التراجع عنه — سيُفقد كل تاريخ العمليات والصلاحيات المكتسبة.
يتطلب تمرير confirm=true للتأكيد.
""",
            parameters = listOf(
                ToolParameter(
                    name = "confirm",
                    type = "string",
                    description = "يجب أن تكون 'true' للتأكيد. أي قيمة أخرى ستلغي العملية.",
                    required = true
                )
            )
        ),

        ToolDefinition(
            name = "list_earned_capabilities",
            description = """سرد الصلاحيات المكتسبة وتلك التي يمكن كسبها.
الصلاحيات المتاحة:
- file_write: الكتابة على الملفات (trustScore >= 0.2)
- terminal_access: تشغيل أوامر الطرفية (trustScore >= 0.3)
- god_mode: وضع القوة الكاملة (trustScore >= 0.8)
- swarm_control: التحكم في عمليات متعددة (trustScore >= 0.9)

تُمنح الصلاحيات تلقائياً عند تجاوز العتبة.
""",
            parameters = listOf(
                ToolParameter(
                    name = "capability",
                    type = "string",
                    description = "اسم صلاحية معينة للتحقق منها فقط (اختياري). مثال: 'god_mode'",
                    required = false
                )
            )
        )
    )

    // ──────────────────────────────────────────────────────────────────────────
    // Execution
    // ──────────────────────────────────────────────────────────────────────────

    /** يُعيد null إذا الأداة ليست مملوكة لهذا الـ wrapper. */
    suspend fun execute(name: String, args: Map<String, String>): ToolExecutionResult? {
        if (name !in HANDLED) return null
        return try {
            withContext(Dispatchers.IO) {
                when (name) {
                    "get_trust_profile"         -> doGetProfile()
                    "reset_trust"               -> doResetTrust(args)
                    "list_earned_capabilities"  -> doListCapabilities(args)
                    else -> ToolExecutionResult("Unknown trust tool: $name", isError = true)
                }
            }
        } catch (t: Throwable) {
            ToolExecutionResult("ProgressiveTrustTool error: ${t.message}", isError = true)
        }
    }

    fun handles(name: String): Boolean = name in HANDLED

    // ──────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ──────────────────────────────────────────────────────────────────────────

    private fun doGetProfile(): ToolExecutionResult {
        return ToolExecutionResult(trustEngine.buildProfileSummary())
    }

    private fun doResetTrust(args: Map<String, String>): ToolExecutionResult {
        val confirm = args["confirm"]?.trim()?.lowercase()
        if (confirm != "true") {
            return ToolExecutionResult(
                "⚠️ إعادة ضبط الثقة لم تتم — يتطلب confirm=true.\n" +
                "هذا الإجراء سيُفقد كل تاريخ العمليات والصلاحيات المكتسبة.",
                isError = false
            )
        }
        trustEngine.resetProfile()
        return ToolExecutionResult(
            "✅ تم إعادة ملف الثقة إلى الحالة الافتراضية.\n" +
            "trustScore = 0.100 | NOVICE | No capabilities"
        )
    }

    private fun doListCapabilities(args: Map<String, String>): ToolExecutionResult {
        val specificCap = args["capability"]?.trim()
        val p = trustEngine.getProfile()
        val level = trustEngine.getTrustLevel()

        // لو طُلبت صلاحية بعينها
        if (!specificCap.isNullOrBlank()) {
            val isEarned = specificCap in p.earnedCapabilities
            val isAvailable = trustEngine.checkCapability(specificCap)
            return ToolExecutionResult(buildString {
                appendLine("🔍 فحص الصلاحية: $specificCap")
                appendLine("   مكتسبة: ${if (isEarned) "✅ نعم" else "❌ لا"}")
                appendLine("   متوفرة بالـ score الحالي: ${if (isAvailable) "✅ نعم" else "❌ لا"}")
                appendLine("   trustScore الحالي: ${"%.3f".format(p.trustScore)}")
            })
        }

        // قائمة كاملة
        return ToolExecutionResult(buildString {
            appendLine("🏆 الصلاحيات التدريجية (Progressive Capabilities)")
            appendLine("Score الحالي: ${"%.3f".format(p.trustScore)} | المستوى: ${level.label}")
            appendLine()
            appendLine("الصلاحية           | العتبة | الحالة")
            appendLine("─────────────────────────────────────")
            appendCapabilityRow(this, "file_write",      0.2f, p.trustScore, p.earnedCapabilities)
            appendCapabilityRow(this, "terminal_access", 0.3f, p.trustScore, p.earnedCapabilities)
            appendCapabilityRow(this, "god_mode",        0.8f, p.trustScore, p.earnedCapabilities)
            appendCapabilityRow(this, "swarm_control",   0.9f, p.trustScore, p.earnedCapabilities)
            appendLine()
            if (p.earnedCapabilities.isEmpty()) {
                appendLine("💡 لا توجد صلاحيات مكتسبة بعد — ابدأ بتنفيذ عمليات ناجحة.")
            } else {
                appendLine("✅ الصلاحيات المكتسبة: ${p.earnedCapabilities.joinToString(", ")}")
            }
        })
    }

    private fun appendCapabilityRow(
        sb: StringBuilder,
        cap: String,
        threshold: Float,
        score: Float,
        earned: Set<String>
    ) {
        val statusIcon = when {
            cap in earned             -> "✅ مكتسبة"
            score >= threshold        -> "🔓 متوفرة"
            else -> {
                val remaining = threshold - score
                "🔒 تحتاج +${"%.3f".format(remaining)}"
            }
        }
        sb.appendLine("%-20s | %-6.1f | %s".format(cap, threshold, statusIcon))
    }

    companion object {
        val HANDLED = setOf(
            "get_trust_profile",
            "reset_trust",
            "list_earned_capabilities"
        )
    }
}
