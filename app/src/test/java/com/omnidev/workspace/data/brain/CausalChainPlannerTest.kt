package com.omnidev.workspace.data.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ══════════════════════════════════════════════════════════════════════════════
 * CausalChainPlannerTest — اختبارات وحدة للتخطيط السببي متعدد الخطوات
 * ══════════════════════════════════════════════════════════════════════════════
 *
 * اختبارات JUnit4 نقية (بدون أي تبعيات Android) تغطي:
 * - اكتشاف التعارضات (READ_AFTER_DELETE، MODIFY_AFTER_DELETE، DOUBLE_CREATE، DELETE_AFTER_MODIFY)
 * - المسار السعيد (بدون تعارضات)
 * - الأوامر الحرجة (CRITICAL_COMMAND)
 * - المحاكاة الافتراضية (simulate)
 * - تحليل What-If
 * - بناء المخطط (buildChain)
 * - حقن الـ Prompt (buildPromptInjection)
 */
class CausalChainPlannerTest {

    private val planner = CausalChainPlanner()

    // ──────────────────────────────────────────────────────────────────────────
    // مساعدات
    // ──────────────────────────────────────────────────────────────────────────

    private fun step(tool: String, vararg params: Pair<String, String>): Pair<String, Map<String, String>> =
        tool to mapOf(*params)

    private fun stepWithPath(tool: String, path: String) = step(tool, "path" to path)

    // ──────────────────────────────────────────────────────────────────────────
    // 1. READ_AFTER_DELETE — قراءة ملف محذوف
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `READ_AFTER_DELETE conflict is fatal when reading a previously deleted path`() {
        val graph = planner.buildChain(listOf(
            stepWithPath("delete_file", "/tmp/foo.kt"),
            stepWithPath("read_file_lines", "/tmp/foo.kt")
        ))

        val conflict = graph.conflicts.find { it.type == CausalChainPlanner.ConflictType.READ_AFTER_DELETE }
        assertNotNull("يجب اكتشاف تعارض READ_AFTER_DELETE", conflict)
        assertTrue("READ_AFTER_DELETE يجب أن يكون فادحاً", conflict!!.isFatal)
        assertEquals("/tmp/foo.kt", conflict.path)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 2. MODIFY_AFTER_DELETE — تعديل ملف محذوف
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `MODIFY_AFTER_DELETE conflict is fatal when patching a previously deleted path`() {
        val graph = planner.buildChain(listOf(
            stepWithPath("delete_file", "/src/A.kt"),
            stepWithPath("patch_file_content", "/src/A.kt")
        ))

        val conflict = graph.conflicts.find { it.type == CausalChainPlanner.ConflictType.MODIFY_AFTER_DELETE }
        assertNotNull("يجب اكتشاف تعارض MODIFY_AFTER_DELETE", conflict)
        assertTrue("MODIFY_AFTER_DELETE يجب أن يكون فادحاً", conflict!!.isFatal)
        assertEquals("/src/A.kt", conflict.path)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 3. DOUBLE_CREATE — إنشاء نفس الملف مرتين بدون حذف بينهما
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `DOUBLE_CREATE conflict is non-fatal when creating same path twice without delete`() {
        val graph = planner.buildChain(listOf(
            stepWithPath("create_file", "/out/Report.txt"),
            stepWithPath("create_file", "/out/Report.txt")
        ))

        val conflict = graph.conflicts.find { it.type == CausalChainPlanner.ConflictType.DOUBLE_CREATE }
        assertNotNull("يجب اكتشاف تعارض DOUBLE_CREATE", conflict)
        assertFalse("DOUBLE_CREATE يجب ألا يكون فادحاً", conflict!!.isFatal)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 4. DELETE_AFTER_MODIFY — حذف ملف بعد تعديله مباشرةً (بدون قراءة بينهما)
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `DELETE_AFTER_MODIFY conflict is non-fatal when deleting after modify with no intervening read`() {
        val graph = planner.buildChain(listOf(
            stepWithPath("patch_file_content", "/work/data.json"),
            stepWithPath("delete_file", "/work/data.json")
        ))

        val conflict = graph.conflicts.find { it.type == CausalChainPlanner.ConflictType.DELETE_AFTER_MODIFY }
        assertNotNull("يجب اكتشاف تعارض DELETE_AFTER_MODIFY", conflict)
        assertFalse("DELETE_AFTER_MODIFY يجب ألا يكون فادحاً", conflict!!.isFatal)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 5. Happy path — create → patch → read → delete — لا تعارضات
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `no conflicts for happy-path create then patch then read then delete sequence`() {
        val path = "/project/Main.kt"
        val graph = planner.buildChain(listOf(
            stepWithPath("create_file",        path),
            stepWithPath("patch_file_content", path),
            stepWithPath("read_file_lines",    path),
            stepWithPath("delete_file",        path)
        ))

        // قراءة بعد التعديل تمنع DELETE_AFTER_MODIFY — يجب ألا يكون هناك أي تعارضات
        assertTrue(
            "المسار السعيد يجب ألا ينتج أي تعارضات، وجد: ${graph.conflicts.map { it.type }}",
            graph.conflicts.isEmpty()
        )
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 6. CRITICAL_COMMAND — run_terminal مع rm -rf
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `run_terminal with rm -rf produces CRITICAL risk node`() {
        val node = planner.analyzeToolCall(
            stepIndex = 0,
            toolName  = "run_terminal",
            parameters = mapOf("command" to "rm -rf /data")
        )

        assertEquals(
            "rm -rf يجب أن ينتج مستوى خطر CRITICAL",
            CausalChainPlanner.RiskLevel.CRITICAL,
            node.riskLevel
        )
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 7. simulate success — create ثم patch → كل الخطوات ستنجح
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `simulate success when create_file is followed by patch_file_content`() {
        val graph = planner.buildChain(listOf(
            stepWithPath("create_file",        "/tmp/new.kt"),
            stepWithPath("patch_file_content", "/tmp/new.kt")
        ))

        val result = planner.simulate(graph)

        assertTrue("المحاكاة يجب أن تنجح بالكامل", result.overallSuccess)
        assertTrue("جميع الخطوات يجب أن تنجح",
            result.steps.all { it.wouldSucceed })
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 8. simulate failure — delete ثم read → الخطوة الثانية تفشل
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `simulate failure when read_file_lines follows delete_file on same path`() {
        // ملاحظة: نبدأ بإنشاء الملف حتى تكون delete_file مشروعة في الحالة الافتراضية
        val graph = planner.buildChain(listOf(
            stepWithPath("create_file",    "/tmp/gone.txt"),
            stepWithPath("delete_file",    "/tmp/gone.txt"),
            stepWithPath("read_file_lines", "/tmp/gone.txt")
        ))

        val result = planner.simulate(graph)

        assertFalse("المحاكاة يجب أن تفشل", result.overallSuccess)

        // الخطوة بفهرس 2 (read بعد delete) هي التي يجب أن تفشل
        val failStep = result.steps.find { !it.wouldSucceed }
        assertNotNull("يجب أن تكون هناك خطوة فاشلة", failStep)
        assertEquals("فهرس أول فشل يجب أن يكون 2", 2, result.firstFailureIndex)
        assertNotNull("يجب أن تُوضّح سبب الفشل", failStep!!.failReason)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 9. whatIf remove_step — حذف خطوة delete يُقلّل التعارضات
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `whatIf removing delete step reduces conflicts compared to baseline`() {
        // خطة أساسية: patch ثم delete مباشرةً (بدون قراءة) → DELETE_AFTER_MODIFY
        val path = "/cfg/app.yml"
        val baseline = planner.buildChain(listOf(
            stepWithPath("create_file",        path),
            stepWithPath("patch_file_content", path),
            stepWithPath("delete_file",        path)
        ))

        // الخطة الأساسية يجب أن تحتوي على تعارض DELETE_AFTER_MODIFY
        assertTrue(
            "الخطة الأساسية يجب أن تحتوي على تعارض DELETE_AFTER_MODIFY",
            baseline.conflicts.any { it.type == CausalChainPlanner.ConflictType.DELETE_AFTER_MODIFY }
        )

        val diffText = planner.whatIf(baseline, removeStepIndex = 2)

        // النص يجب أن يشير إلى أن التعارضات انخفضت
        assertTrue(
            "تقرير whatIf يجب أن يُشير لانخفاض التعارضات",
            diffText.contains("يُقلّل") || diffText.contains("→")
        )
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 10. buildChain — عدد العقد يُطابق عدد الخطوات
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `buildChain node count matches input steps count`() {
        val steps = listOf(
            stepWithPath("create_file",     "/a/b.kt"),
            stepWithPath("patch_file_content", "/a/b.kt"),
            step("web_search", "query" to "kotlin coroutines"),
            stepWithPath("delete_file",     "/a/b.kt")
        )
        val graph = planner.buildChain(steps)

        assertEquals("عدد العقد يجب أن يساوي عدد الخطوات", steps.size, graph.nodes.size)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 11. buildPromptInjection — فارغ للمخطط الفارغ/المنخفض الخطر، نص للمرتفع
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `buildPromptInjection returns empty string for empty graph`() {
        val emptyGraph = planner.buildChain(emptyList())
        val injection = planner.buildPromptInjection(emptyGraph)

        assertTrue("الحقن يجب أن يكون فارغاً للمخطط الفارغ", injection.isEmpty())
    }

    @Test
    fun `buildPromptInjection returns empty string for low-risk graph with no conflicts`() {
        // بحث فقط — مخاطر منخفضة، لا تعارضات
        val graph = planner.buildChain(listOf(
            step("web_search", "query" to "android jetpack compose"),
            step("web_search", "query" to "kotlin flow")
        ))

        val injection = planner.buildPromptInjection(graph)

        assertTrue(
            "الحقن يجب أن يكون فارغاً للمخطط الخالي من المخاطر العالية",
            injection.isEmpty()
        )
    }

    @Test
    fun `buildPromptInjection returns warning text for graph with fatal conflict`() {
        // تعارض حرج: حذف ثم قراءة
        val graph = planner.buildChain(listOf(
            stepWithPath("delete_file",    "/etc/config.json"),
            stepWithPath("read_file_lines", "/etc/config.json")
        ))

        val injection = planner.buildPromptInjection(graph)

        assertTrue("الحقن يجب أن يحتوي على نص تحذيري", injection.isNotBlank())
        assertTrue(
            "الحقن يجب أن يذكر التعارض الحرج",
            injection.contains("❌") || injection.contains("تعارض")
        )
    }

    @Test
    fun `buildPromptInjection returns warning text for HIGH risk graph even without conflicts`() {
        // delete_file وحده = HIGH risk، بدون تعارضات
        val graph = planner.buildChain(listOf(
            stepWithPath("delete_file", "/important/file.db")
        ))

        val injection = planner.buildPromptInjection(graph)

        assertTrue(
            "الحقن يجب أن يحتوي على نص تحذيري للمستوى HIGH",
            injection.isNotBlank()
        )
    }
}
