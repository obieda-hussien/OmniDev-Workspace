package com.omnidev.workspace.data.brain

/**
 * ══════════════════════════════════════════════════════════════════════════════
 * CausalChainPlanner — محرك التخطيط السببي متعدد الخطوات (Mobile-First)
 * ══════════════════════════════════════════════════════════════════════════════
 *
 * قبل أن يُنفّذ الوكيل سلسلة أوامر معقدة، يبني هذا المحرك "خريطة سببية" (Causal Graph)
 * تتنبأ بالتأثيرات الجانبية وتكتشف التعارضات (مثل: حذف ملف ثم محاولة قراءته).
 *
 * ## الميزات الأساسية:
 * 1. **تحليل الأدوات** (analyzeToolCall): يُحوّل أمر أداة → عقدة سببية بمتطلبات + تأثيرات
 * 2. **بناء المخطط** (buildChain): يبني DAG من قائمة خطوات مخطّطة
 * 3. **اكتشاف التعارضات** (detectConflicts): يبحث عن مشاكل منطقية (Read-After-Delete، إلخ)
 * 4. **المحاكاة الافتراضية** (simulate): يحاكي التنفيذ دون تأثير حقيقي
 * 5. **تحليل What-If** (whatIf): يُظهر الفرق لو أضفنا أو حذفنا خطوة
 * 6. **حقن الـ Prompt** (buildPromptInjection): يُنشئ نص تحذيري للـ system prompt
 *
 * ## Mobile-First (4 GB RAM أو أقل):
 * - لا نماذج LLM إضافية — كل التحليل rule-based
 * - لا قاعدة بيانات — ذاكرة الجلسة فقط (in-memory)
 * - حد أقصى [maxNodes] عقدة لحماية الذاكرة
 * - معالجة كاملة < 5ms لـ 20 خطوة
 */
class CausalChainPlanner(
    /** أقصى عدد عقد في خريطة سببية واحدة (حماية من OOM). */
    val maxNodes: Int = 50
) {

    // ──────────────────────────────────────────────────────────────────────────
    // Data classes & enums (public so CausalChainPlannerTool can read them)
    // ──────────────────────────────────────────────────────────────────────────

    enum class EffectType {
        CREATE,   // ينشئ ملفاً أو موردًا جديداً
        DELETE,   // يحذف ملفاً أو مورداً
        MODIFY,   // يعدّل ملفاً موجوداً
        READ,     // يقرأ فقط (لا جانب آمن)
        EXECUTE,  // ينفّذ أمراً في Shell أو وقت التشغيل
        SYSTEM,   // يغيّر إعداداً في النظام (شبكة، صلاحيات، Git، إلخ)
        NETWORK   // يُرسل طلباً عبر الشبكة
    }

    enum class RiskLevel(val score: Int) {
        LOW(1), MEDIUM(2), HIGH(3), CRITICAL(4);

        fun label(): String = when (this) {
            LOW      -> "🟢 منخفض"
            MEDIUM   -> "🟡 متوسط"
            HIGH     -> "🔴 مرتفع"
            CRITICAL -> "💥 حرج"
        }
    }

    enum class ConflictType {
        READ_AFTER_DELETE,      // محاولة قراءة ملف محذوف
        MODIFY_AFTER_DELETE,    // محاولة تعديل ملف محذوف
        DOUBLE_CREATE,          // إنشاء نفس الملف مرتين
        OVERWRITE_UNREAD,       // تعديل ملف لم يُقرأ بعد إنشائه (احتمال ضياع بيانات)
        DELETE_AFTER_MODIFY,    // حذف ملف بعد تعديله (عمل ضائع)
        CRITICAL_COMMAND,       // أمر بمستوى خطر حرج (rm -rf, git reset --hard)
        CIRCULAR_DEPENDENCY,    // A يعتمد على B و B يعتمد على A
    }

    data class CausalEffect(
        val type: EffectType,
        /** المسار أو المورد المتأثر (null إذا كان التأثير عاماً). */
        val targetPath: String?,
        val description: String
    )

    data class CausalNode(
        val id: String,
        val stepIndex: Int,
        val toolName: String,
        val parameters: Map<String, String>,
        val preconditions: List<String>,
        val effects: List<CausalEffect>,
        val riskLevel: RiskLevel,
        val humanSummary: String
    )

    data class CausalConflict(
        val type: ConflictType,
        val stepA: Int,       // فهرس الخطوة الأولى
        val stepB: Int,       // فهرس الخطوة الثانية (-1 إذا كانت واحدة)
        val path: String?,
        val message: String,
        val isFatal: Boolean  // هل يجب إيقاف التنفيذ؟
    )

    data class CausalGraph(
        val nodes: List<CausalNode>,
        val conflicts: List<CausalConflict>
    ) {
        val highestRisk: RiskLevel
            get() = nodes.maxByOrNull { it.riskLevel.score }?.riskLevel ?: RiskLevel.LOW
        val hasFatalConflict: Boolean
            get() = conflicts.any { it.isFatal }
    }

    data class SimulationStep(
        val stepIndex: Int,
        val toolName: String,
        val humanSummary: String,
        val riskLevel: RiskLevel,
        val wouldSucceed: Boolean,
        val failReason: String?,
        val virtualStateAfter: VirtualState
    )

    data class SimulationResult(
        val steps: List<SimulationStep>,
        val overallSuccess: Boolean,
        val firstFailureIndex: Int,   // -1 إذا نجح الكل
        val warningMessages: List<String>
    )

    /**
     * الحالة الافتراضية للجهاز/النظام أثناء المحاكاة.
     * خفيفة جداً — فقط sets من المسارات.
     */
    data class VirtualState(
        val createdPaths: Set<String>,
        val deletedPaths: Set<String>,
        val modifiedPaths: Set<String>,
        val readPaths: Set<String>
    ) {
        fun exists(path: String): Boolean = path in createdPaths && path !in deletedPaths
        fun wasDeleted(path: String): Boolean = path in deletedPaths
        companion object {
            val EMPTY = VirtualState(emptySet(), emptySet(), emptySet(), emptySet())
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * يُحلّل أمر أداة واحد ويُنشئ [CausalNode] بالمتطلبات والتأثيرات.
     * يعمل 100% rule-based — لا LLM، لا شبكة، سرعة < 1ms.
     */
    fun analyzeToolCall(
        stepIndex: Int,
        toolName: String,
        parameters: Map<String, String>
    ): CausalNode {
        val rule = findRule(toolName, parameters)
        return CausalNode(
            id = "${stepIndex}_${toolName}",
            stepIndex = stepIndex,
            toolName = toolName,
            parameters = parameters,
            preconditions = rule.preconditions,
            effects = rule.effects,
            riskLevel = rule.riskLevel,
            humanSummary = rule.humanSummary
        )
    }

    /**
     * يبني [CausalGraph] من قائمة من الخطوات المخطّطة.
     * يكتشف التعارضات تلقائياً بعد البناء.
     * إذا تجاوزت الخطوات [maxNodes]، يُقلّص بأمان.
     */
    fun buildChain(steps: List<Pair<String, Map<String, String>>>): CausalGraph {
        val bounded = steps.take(maxNodes)
        val nodes = bounded.mapIndexed { idx, (toolName, params) ->
            analyzeToolCall(idx, toolName, params)
        }
        val conflicts = detectConflicts(nodes)
        return CausalGraph(nodes, conflicts)
    }

    /**
     * يكتشف التعارضات المنطقية في قائمة عقد مُرتَّبة زمنياً.
     */
    fun detectConflicts(nodes: List<CausalNode>): List<CausalConflict> {
        val conflicts = mutableListOf<CausalConflict>()
        // تتبع: path → آخر عملية عليه
        val lastCreate  = mutableMapOf<String, Int>()
        val lastDelete  = mutableMapOf<String, Int>()
        val lastModify  = mutableMapOf<String, Int>()
        val lastRead    = mutableMapOf<String, Int>()

        for (node in nodes) {
            val i = node.stepIndex
            for (eff in node.effects) {
                val path = eff.targetPath ?: continue
                when (eff.type) {
                    EffectType.READ -> {
                        lastDelete[path]?.let { delIdx ->
                            conflicts += CausalConflict(
                                type = ConflictType.READ_AFTER_DELETE,
                                stepA = delIdx, stepB = i, path = path,
                                message = "⚠️ الخطوة $i تقرأ '$path' الذي حُذف في الخطوة $delIdx.",
                                isFatal = true
                            )
                        }
                        lastRead[path] = i
                    }
                    EffectType.MODIFY -> {
                        lastDelete[path]?.let { delIdx ->
                            conflicts += CausalConflict(
                                type = ConflictType.MODIFY_AFTER_DELETE,
                                stepA = delIdx, stepB = i, path = path,
                                message = "❌ الخطوة $i تُعدّل '$path' الذي حُذف في الخطوة $delIdx.",
                                isFatal = true
                            )
                        }
                        lastModify[path] = i
                    }
                    EffectType.CREATE -> {
                        lastCreate[path]?.let { prevIdx ->
                            if (lastDelete[path] == null || (lastDelete[path] ?: -1) < prevIdx) {
                                conflicts += CausalConflict(
                                    type = ConflictType.DOUBLE_CREATE,
                                    stepA = prevIdx, stepB = i, path = path,
                                    message = "⚠️ الخطوة $i تُنشئ '$path' مرة ثانية (أُنشئ أول مرة في الخطوة $prevIdx).",
                                    isFatal = false
                                )
                            }
                        }
                        lastCreate[path] = i
                    }
                    EffectType.DELETE -> {
                        lastModify[path]?.let { modIdx ->
                            val readAfterMod = (lastRead[path] ?: -1) > modIdx
                            if (!readAfterMod) {
                                conflicts += CausalConflict(
                                    type = ConflictType.DELETE_AFTER_MODIFY,
                                    stepA = modIdx, stepB = i, path = path,
                                    message = "⚠️ الخطوة $i تحذف '$path' بعد تعديله في الخطوة $modIdx — عمل ضائع.",
                                    isFatal = false
                                )
                            }
                        }
                        lastDelete[path] = i
                    }
                    EffectType.EXECUTE -> {
                        // CRITICAL risk يعني أمر خطير (rm -rf, git reset --hard, etc.)
                        if (node.riskLevel == RiskLevel.CRITICAL) {
                            conflicts += CausalConflict(
                                type = ConflictType.CRITICAL_COMMAND,
                                stepA = i, stepB = -1, path = path,
                                message = "💥 الخطوة $i تُنفّذ أمراً حرجاً: ${node.humanSummary}",
                                isFatal = false // تحذير ليس خطأ فادح
                            )
                        }
                    }
                    else -> {}
                }
            }
        }
        return conflicts
    }

    /**
     * يُحاكي تنفيذ المخطط دون أي تأثير حقيقي على الجهاز.
     * يبني "نظام ملفات افتراضي" ويُتحقق من كل خطوة ضده.
     */
    fun simulate(graph: CausalGraph): SimulationResult {
        val warnings = mutableListOf<String>()
        var state = VirtualState.EMPTY
        val steps = mutableListOf<SimulationStep>()
        var firstFailure = -1

        for (node in graph.nodes) {
            val (wouldSucceed, failReason, newState) = simulateStep(node, state)
            if (!wouldSucceed && firstFailure == -1) firstFailure = node.stepIndex
            steps += SimulationStep(
                stepIndex = node.stepIndex,
                toolName = node.toolName,
                humanSummary = node.humanSummary,
                riskLevel = node.riskLevel,
                wouldSucceed = wouldSucceed,
                failReason = failReason,
                virtualStateAfter = newState
            )
            state = newState
        }

        if (graph.highestRisk >= RiskLevel.HIGH) {
            warnings += "⚠️ هذه السلسلة تحتوي على عمليات عالية الخطورة — تأكد من وجود نسخة احتياطية (rollback group)."
        }
        if (graph.nodes.count { it.riskLevel == RiskLevel.CRITICAL } > 0) {
            warnings += "💥 تحذير: يوجد ${graph.nodes.count { it.riskLevel == RiskLevel.CRITICAL }} أمر(أوامر) حرج(ة) في المخطط."
        }
        for (c in graph.conflicts) {
            if (c.isFatal) warnings += c.message
        }

        return SimulationResult(
            steps = steps,
            overallSuccess = firstFailure == -1,
            firstFailureIndex = firstFailure,
            warningMessages = warnings
        )
    }

    /**
     * تحليل What-If: "ماذا يحدث لو أضفنا/حذفنا هذه الخطوة؟"
     * يُرجع نصاً يصف الفرق بين خطتين.
     */
    fun whatIf(
        baseline: CausalGraph,
        insertStep: Pair<String, Map<String, String>>? = null,
        removeStepIndex: Int? = null
    ): String {
        val modifiedSteps = baseline.nodes
            .filter { removeStepIndex == null || it.stepIndex != removeStepIndex }
            .map { it.toolName to it.parameters }
            .toMutableList()

        insertStep?.let { modifiedSteps += it }
        val modifiedGraph = buildChain(modifiedSteps)
        val baseSim = simulate(baseline)
        val modSim = simulate(modifiedGraph)

        return buildString {
            appendLine("🔬 تحليل What-If:")
            appendLine()
            when {
                removeStepIndex != null -> {
                    val removed = baseline.nodes.find { it.stepIndex == removeStepIndex }
                    appendLine("❌ لو حذفنا الخطوة $removeStepIndex (${removed?.toolName ?: "?"}):")
                }
                insertStep != null ->
                    appendLine("➕ لو أضفنا خطوة جديدة (${insertStep.first}):")
                else ->
                    appendLine("📊 مقارنة المخططين:")
            }
            appendLine()

            val baseConflicts = baseline.conflicts.size
            val modConflicts = modifiedGraph.conflicts.size
            when {
                modConflicts < baseConflicts ->
                    appendLine("✅ التعديل يُقلّل التعارضات: $baseConflicts → $modConflicts")
                modConflicts > baseConflicts ->
                    appendLine("⚠️ التعديل يُضيف تعارضات: $baseConflicts → $modConflicts")
                else ->
                    appendLine("ℹ️ عدد التعارضات لم يتغير: $modConflicts")
            }

            val baseRisk = baseline.highestRisk
            val modRisk = modifiedGraph.highestRisk
            if (modRisk.score > baseRisk.score)
                appendLine("⬆️ الخطر يرتفع: ${baseRisk.label()} → ${modRisk.label()}")
            else if (modRisk.score < baseRisk.score)
                appendLine("⬇️ الخطر ينخفض: ${baseRisk.label()} → ${modRisk.label()}")

            val baseSuccess = baseSim.overallSuccess
            val modSuccess = modSim.overallSuccess
            when {
                !baseSuccess && modSuccess  -> appendLine("🎉 التعديل يُصلح سلسلة فاشلة!")
                baseSuccess && !modSuccess  -> appendLine("💔 التعديل يُسبّب فشلاً جديداً!")
                else                         -> appendLine("ℹ️ نتيجة التنفيذ لم تتغير (${if (modSuccess) "ناجح" else "فاشل"})")
            }

            if (modifiedGraph.conflicts.isNotEmpty()) {
                appendLine()
                appendLine("📋 التعارضات في المخطط المُعدَّل:")
                for (c in modifiedGraph.conflicts.take(5)) {
                    appendLine("  • ${c.message}")
                }
            }
        }
    }

    /**
     * يُنشئ نص حقن للـ system prompt يُلخّص التحذيرات الحرجة.
     * مُحدود بـ [maxChars] لتوفير context window.
     */
    fun buildPromptInjection(graph: CausalGraph, maxChars: Int = 700): String {
        if (graph.nodes.isEmpty()) return ""
        val fatalConflicts = graph.conflicts.filter { it.isFatal }
        val warnings = graph.conflicts.filter { !it.isFatal }
        if (fatalConflicts.isEmpty() && warnings.isEmpty() && graph.highestRisk < RiskLevel.HIGH) return ""

        return buildString {
            appendLine("\n🗺️ تحليل السلسلة السببية (Causal Chain):")
            appendLine("  خطوات: ${graph.nodes.size} | مستوى خطر: ${graph.highestRisk.label()}")

            if (fatalConflicts.isNotEmpty()) {
                appendLine("❌ تعارضات حرجة:")
                for (c in fatalConflicts.take(3)) {
                    val line = "  • ${c.message.take(120)}"
                    if (length + line.length > maxChars) return@buildString
                    appendLine(line)
                }
            }
            if (warnings.isNotEmpty()) {
                appendLine("⚠️ تحذيرات:")
                for (c in warnings.take(3)) {
                    val line = "  • ${c.message.take(100)}"
                    if (length + line.length > maxChars) return@buildString
                    appendLine(line)
                }
            }
        }.trimEnd()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ──────────────────────────────────────────────────────────────────────────

    private data class ToolRule(
        val effects: List<CausalEffect>,
        val preconditions: List<String>,
        val riskLevel: RiskLevel,
        val humanSummary: String
    )

    /** يُطابق اسم الأداة + معاملاتها لاستخراج القاعدة السببية. */
    private fun findRule(toolName: String, params: Map<String, String>): ToolRule {
        val path = params["path"]?.trim()
            ?: params["file_path"]?.trim()
            ?: params["target"]?.trim()

        return when (toolName) {
            // ── File Read Operations (LOW risk) ──────────────────────────────
            "read_file_lines", "read_file" ->
                readRule(path, "قراءة ملف")
            "multi_read" ->
                readRule(path, "قراءة عدة ملفات")
            "search_codebase" ->
                ToolRule(
                    effects = listOf(CausalEffect(EffectType.READ, null, "بحث في قاعدة الكود")),
                    preconditions = emptyList(),
                    riskLevel = RiskLevel.LOW,
                    humanSummary = "🔍 بحث في الكود: ${params["query"]?.take(40) ?: "?"}"
                )

            // ── File Create Operations ────────────────────────────────────────
            "create_file" ->
                ToolRule(
                    effects = listOf(CausalEffect(EffectType.CREATE, path, "إنشاء ملف جديد")),
                    preconditions = if (path != null) listOf("'$path' غير موجود") else emptyList(),
                    riskLevel = RiskLevel.LOW,
                    humanSummary = "📄 إنشاء: ${path ?: "?"}"
                )

            // ── File Modify Operations ────────────────────────────────────────
            "patch_file_content", "multi_patch_file_content",
            "delete_text", "delete_lines", "insert_lines",
            "replace_lines", "append_to_file" ->
                ToolRule(
                    effects = listOf(CausalEffect(EffectType.MODIFY, path, "تعديل ملف")),
                    preconditions = if (path != null) listOf("'$path' موجود") else emptyList(),
                    riskLevel = RiskLevel.MEDIUM,
                    humanSummary = "✏️ تعديل: ${path ?: "?"}"
                )

            // ── Destructive: clear_file ───────────────────────────────────────
            "clear_file" ->
                ToolRule(
                    effects = listOf(CausalEffect(EffectType.MODIFY, path, "مسح محتوى الملف بالكامل")),
                    preconditions = if (path != null) listOf("'$path' موجود") else emptyList(),
                    riskLevel = RiskLevel.HIGH,
                    humanSummary = "🗑️ مسح محتوى الملف: ${path ?: "?"}"
                )

            // ── Delete Operations ─────────────────────────────────────────────
            "delete_file" ->
                ToolRule(
                    effects = listOf(CausalEffect(EffectType.DELETE, path, "حذف ملف")),
                    preconditions = if (path != null) listOf("'$path' موجود") else emptyList(),
                    riskLevel = RiskLevel.HIGH,
                    humanSummary = "🗑️ حذف ملف: ${path ?: "?"}"
                )

            // ── Terminal / Shell ──────────────────────────────────────────────
            "run_terminal", "python_runner" ->
                analyzeShellCommand(toolName, params)

            // ── Git Operations ────────────────────────────────────────────────
            "git_manager" ->
                analyzeGitAction(params)

            // ── Web / Network (LOW risk read-only) ────────────────────────────
            "web_search", "web_search_deep", "web_scraper", "scrape_multiple",
            "network_request" ->
                ToolRule(
                    effects = listOf(CausalEffect(EffectType.NETWORK, null, "طلب شبكي")),
                    preconditions = emptyList(),
                    riskLevel = RiskLevel.LOW,
                    humanSummary = "🌐 طلب شبكي: $toolName"
                )

            // ── Memory Operations (LOW risk) ──────────────────────────────────
            "remember_fact", "update_memory", "delete_memory",
            "vector_store", "brain_record_episode" ->
                ToolRule(
                    effects = listOf(CausalEffect(EffectType.MODIFY, null, "تعديل الذاكرة")),
                    preconditions = emptyList(),
                    riskLevel = RiskLevel.LOW,
                    humanSummary = "🧠 تحديث الذاكرة: $toolName"
                )

            // ── System Tools ──────────────────────────────────────────────────
            "hardware_toggle_tool", "vpn_control", "system_power" ->
                ToolRule(
                    effects = listOf(CausalEffect(EffectType.SYSTEM, null, "تغيير إعداد نظام")),
                    preconditions = emptyList(),
                    riskLevel = RiskLevel.MEDIUM,
                    humanSummary = "⚙️ إعداد نظام: $toolName"
                )

            // ── Default: unknown tool treated as low-risk read ────────────────
            else ->
                ToolRule(
                    effects = listOf(CausalEffect(EffectType.READ, null, "أداة غير محددة")),
                    preconditions = emptyList(),
                    riskLevel = RiskLevel.LOW,
                    humanSummary = "🔧 $toolName"
                )
        }
    }

    private fun readRule(path: String?, label: String): ToolRule = ToolRule(
        effects = listOf(CausalEffect(EffectType.READ, path, label)),
        preconditions = if (path != null) listOf("'$path' موجود") else emptyList(),
        riskLevel = RiskLevel.LOW,
        humanSummary = "📖 $label: ${path ?: "?"}"
    )

    /** يُحلّل أوامر الـ Shell لتحديد تأثيراتها السببية. */
    private fun analyzeShellCommand(toolName: String, params: Map<String, String>): ToolRule {
        val command = (params["command"] ?: params["code"] ?: "").lowercase()
        val (riskLevel, description) = when {
            "rm -rf" in command || "rm -r" in command ->
                RiskLevel.CRITICAL to "حذف تعاودي — خطر فقدان بيانات"
            Regex("""^rm\s""").containsMatchIn(command) || "unlink" in command ->
                RiskLevel.HIGH to "حذف ملف(ات)"
            "git reset --hard" in command || "git clean -fd" in command ->
                RiskLevel.HIGH to "إعادة ضبط Git الصارمة"
            "git push --force" in command || "git push -f" in command ->
                RiskLevel.HIGH to "Git force push — لا يمكن التراجع على الخادم"
            "chmod 777" in command || "chmod -r" in command.replace(" ", "").replace("--", "-") ->
                RiskLevel.MEDIUM to "تغيير صلاحيات الملفات"
            "apt install" in command || "pkg install" in command || "pip install" in command ->
                RiskLevel.LOW to "تثبيت حزمة"
            "mkfs" in command || "fdisk" in command || "dd if=" in command ->
                RiskLevel.CRITICAL to "عملية تهيئة/نسخ قرص — خطر بالغ"
            else ->
                RiskLevel.MEDIUM to "تنفيذ أمر: ${command.take(60)}"
        }
        val effectPath = extractPathFromCommand(command)

        // أوامر الحذف (rm, unlink) تُنتج تأثير DELETE حتى يعمل اكتشاف READ_AFTER_DELETE/MODIFY_AFTER_DELETE
        // كل الأوامر الأخرى تُنتج تأثير EXECUTE (git reset, mkfs, dd، إلخ)
        val effectType = when {
            "rm -rf" in command || "rm -r" in command -> EffectType.DELETE
            Regex("""^rm\s""").containsMatchIn(command) || "unlink" in command -> EffectType.DELETE
            else -> EffectType.EXECUTE
        }

        return ToolRule(
            effects = listOf(
                CausalEffect(
                    type = effectType,
                    targetPath = effectPath,
                    description = description
                )
            ),
            preconditions = emptyList(),
            riskLevel = riskLevel,
            humanSummary = "${riskLevel.label()} تنفيذ: ${command.take(80)}"
        )
    }

    /** يُحلّل عمليات Git لتحديد مستوى الخطر. */
    private fun analyzeGitAction(params: Map<String, String>): ToolRule {
        val action = params["action"]?.lowercase() ?: ""
        val (risk, desc, effType) = when {
            action in listOf("push", "force_push") ->
                Triple(RiskLevel.MEDIUM, "رفع تغييرات", EffectType.NETWORK)
            action in listOf("reset", "clean") ->
                Triple(RiskLevel.HIGH, "إعادة ضبط Git", EffectType.SYSTEM)
            action in listOf("merge", "rebase") ->
                Triple(RiskLevel.MEDIUM, "دمج/إعادة بناء فروع", EffectType.MODIFY)
            action in listOf("commit", "add", "stage") ->
                Triple(RiskLevel.LOW, "تسجيل تغييرات", EffectType.SYSTEM)
            action in listOf("clone", "fetch", "pull") ->
                Triple(RiskLevel.LOW, "جلب بيانات", EffectType.NETWORK)
            action in listOf("branch_delete", "tag_delete") ->
                Triple(RiskLevel.HIGH, "حذف فرع/وسم", EffectType.DELETE)
            else ->
                Triple(RiskLevel.LOW, "عملية Git: $action", EffectType.READ)
        }
        return ToolRule(
            effects = listOf(CausalEffect(effType, null, desc)),
            preconditions = emptyList(),
            riskLevel = risk,
            humanSummary = "🔀 Git $action: $desc"
        )
    }

    /**
     * يُحاكي خطوة واحدة ضد الحالة الافتراضية الحالية.
     * يُرجع (wouldSucceed, failReason, newState).
     */
    private fun simulateStep(
        node: CausalNode,
        state: VirtualState
    ): Triple<Boolean, String?, VirtualState> {
        // فحص المتطلبات ضد الحالة الافتراضية
        for (precondition in node.preconditions) {
            // استخراج المسار من نص المتطلب (مثل: "'path/file' موجود")
            val pathMatch = Regex("'([^']+)'").find(precondition)
            val requiredPath = pathMatch?.groupValues?.get(1) ?: continue
            if (precondition.contains("موجود") && state.wasDeleted(requiredPath)) {
                return Triple(false, "الملف '$requiredPath' محذوف ولا يمكن الوصول إليه.", state)
            }
        }

        // تطبيق التأثيرات على الحالة الافتراضية
        var created = state.createdPaths.toMutableSet()
        var deleted = state.deletedPaths.toMutableSet()
        var modified = state.modifiedPaths.toMutableSet()
        var read = state.readPaths.toMutableSet()

        for (eff in node.effects) {
            val p = eff.targetPath ?: continue
            when (eff.type) {
                EffectType.CREATE  -> { created += p; deleted -= p }
                EffectType.DELETE  -> { deleted += p; created -= p; modified -= p }
                EffectType.MODIFY  -> modified += p
                EffectType.READ    -> read += p
                else               -> {}
            }
        }

        return Triple(
            true, null,
            VirtualState(created, deleted, modified, read)
        )
    }

    /** يستخرج أول مسار ملف من أمر shell بطريقة بسيطة. */
    private fun extractPathFromCommand(command: String): String? {
        val pathRegex = Regex("""[/~][^\s'"]+|'([^']+)'|"([^"]+)"""")
        return pathRegex.find(command)?.let {
            it.groupValues.drop(1).firstOrNull { g -> g.isNotBlank() } ?: it.value
        }
    }
}
