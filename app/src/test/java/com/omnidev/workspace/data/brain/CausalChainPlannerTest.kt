package com.omnidev.workspace.data.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ══════════════════════════════════════════════════════════════════════════════
 * CausalChainPlannerTest — System and domain documentation note System and domain documentation note System and domain documentation note System and domain documentation note System and domain documentation note System and domain documentation note
 * ══════════════════════════════════════════════════════════════════════════════
 *
 * System and domain documentation note JUnit4 System and domain documentation note (System and domain documentation note System and domain documentation note System and domain documentation note Android) System and domain documentation note:
 * - System and domain documentation note System and domain documentation note (READ_AFTER_DELETESystem and domain documentation note MODIFY_AFTER_DELETESystem and domain documentation note DOUBLE_CREATESystem and domain documentation note DELETE_AFTER_MODIFY)
 * - System and domain documentation note System and domain documentation note (System and domain documentation note System and domain documentation note)
 * - System and domain documentation note System and domain documentation note (CRITICAL_COMMAND)
 * - System and domain documentation note System and domain documentation note (simulate)
 * - System and domain documentation note What-If
 * - System and domain documentation note System and domain documentation note (buildChain)
 * - System and domain documentation note System and domain documentation note Prompt (buildPromptInjection)
 */
class CausalChainPlannerTest {

    private val planner = CausalChainPlanner()

    // ──────────────────────────────────────────────────────────────────────────
    // System and domain documentation note
    // ──────────────────────────────────────────────────────────────────────────

    private fun step(tool: String, vararg params: Pair<String, String>): Pair<String, Map<String, String>> =
        tool to mapOf(*params)

    private fun stepWithPath(tool: String, path: String) = step(tool, "path" to path)

    // ──────────────────────────────────────────────────────────────────────────
    // 1. READ_AFTER_DELETE — System and domain documentation note System and domain documentation note System and domain documentation note
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `READ_AFTER_DELETE conflict is fatal when reading a previously deleted path`() {
        val graph = planner.buildChain(listOf(
            stepWithPath("delete_file", "/tmp/foo.kt"),
            stepWithPath("read_file_lines", "/tmp/foo.kt")
        ))

        val conflict = graph.conflicts.find { it.type == CausalChainPlanner.ConflictType.READ_AFTER_DELETE }
        assertNotNull("System component status System component status System component status READ_AFTER_DELETE", conflict)
        assertTrue("READ_AFTER_DELETE System component status System component status System component status System component status", conflict!!.isFatal)
        assertEquals("/tmp/foo.kt", conflict.path)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 2. MODIFY_AFTER_DELETE — System and domain documentation note System and domain documentation note System and domain documentation note
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `MODIFY_AFTER_DELETE conflict is fatal when patching a previously deleted path`() {
        val graph = planner.buildChain(listOf(
            stepWithPath("delete_file", "/src/A.kt"),
            stepWithPath("patch_file_content", "/src/A.kt")
        ))

        val conflict = graph.conflicts.find { it.type == CausalChainPlanner.ConflictType.MODIFY_AFTER_DELETE }
        assertNotNull("System component status System component status System component status MODIFY_AFTER_DELETE", conflict)
        assertTrue("MODIFY_AFTER_DELETE System component status System component status System component status System component status", conflict!!.isFatal)
        assertEquals("/src/A.kt", conflict.path)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 3. DOUBLE_CREATE — System and domain documentation note System and domain documentation note System and domain documentation note System and domain documentation note System and domain documentation note System and domain documentation note System and domain documentation note
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `DOUBLE_CREATE conflict is non-fatal when creating same path twice without delete`() {
        val graph = planner.buildChain(listOf(
            stepWithPath("create_file", "/out/Report.txt"),
            stepWithPath("create_file", "/out/Report.txt")
        ))

        val conflict = graph.conflicts.find { it.type == CausalChainPlanner.ConflictType.DOUBLE_CREATE }
        assertNotNull("System component status System component status System component status DOUBLE_CREATE", conflict)
        assertFalse("DOUBLE_CREATE System component status System component status System component status System component status", conflict!!.isFatal)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 4. DELETE_AFTER_MODIFY — System and domain documentation note System and domain documentation note System and domain documentation note System and domain documentation note System and domain documentation note (System and domain documentation note System and domain documentation note System and domain documentation note)
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `DELETE_AFTER_MODIFY conflict is non-fatal when deleting after modify with no intervening read`() {
        val graph = planner.buildChain(listOf(
            stepWithPath("patch_file_content", "/work/data.json"),
            stepWithPath("delete_file", "/work/data.json")
        ))

        val conflict = graph.conflicts.find { it.type == CausalChainPlanner.ConflictType.DELETE_AFTER_MODIFY }
        assertNotNull("System component status System component status System component status DELETE_AFTER_MODIFY", conflict)
        assertFalse("DELETE_AFTER_MODIFY System component status System component status System component status System component status", conflict!!.isFatal)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 5. Happy path — create → patch → read → delete — System and domain documentation note System and domain documentation note
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

        // System and domain documentation note System and domain documentation note System and domain documentation note System and domain documentation note DELETE_AFTER_MODIFY — System and domain documentation note System and domain documentation note System and domain documentation note System and domain documentation note System and domain documentation note System and domain documentation note
        assertTrue(
            "System component status System component status System component status System component status System component status System component status System component status System component status: ${graph.conflicts.map { it.type }}",
            graph.conflicts.isEmpty()
        )
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 6. CRITICAL_COMMAND — run_terminal System and domain documentation note rm -rf
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `run_terminal with rm -rf produces CRITICAL risk node`() {
        val node = planner.analyzeToolCall(
            stepIndex = 0,
            toolName  = "run_terminal",
            parameters = mapOf("command" to "rm -rf /data")
        )

        assertEquals(
            "rm -rf System component status System component status System component status System component status System component status CRITICAL",
            CausalChainPlanner.RiskLevel.CRITICAL,
            node.riskLevel
        )
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 7. simulate success — create System and domain documentation note patch → System and domain documentation note System and domain documentation note System and domain documentation note
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `simulate success when create_file is followed by patch_file_content`() {
        val graph = planner.buildChain(listOf(
            stepWithPath("create_file",        "/tmp/new.kt"),
            stepWithPath("patch_file_content", "/tmp/new.kt")
        ))

        val result = planner.simulate(graph)

        assertTrue("System component status System component status System component status System component status System component status", result.overallSuccess)
        assertTrue("System component status System component status System component status System component status System component status",
            result.steps.all { it.wouldSucceed })
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 8. simulate failure — delete System and domain documentation note read → System and domain documentation note System and domain documentation note System and domain documentation note
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `simulate failure when read_file_lines follows delete_file on same path`() {
        // System and domain documentation note: System and domain documentation note System and domain documentation note System and domain documentation note System and domain documentation note System and domain documentation note delete_file System and domain documentation note System and domain documentation note System and domain documentation note System and domain documentation note
        val graph = planner.buildChain(listOf(
            stepWithPath("create_file",    "/tmp/gone.txt"),
            stepWithPath("delete_file",    "/tmp/gone.txt"),
            stepWithPath("read_file_lines", "/tmp/gone.txt")
        ))

        val result = planner.simulate(graph)

        assertFalse("System component status System component status System component status System component status", result.overallSuccess)

        // System and domain documentation note System and domain documentation note 2 (read System and domain documentation note delete) System and domain documentation note System and domain documentation note System and domain documentation note System and domain documentation note System and domain documentation note
        val failStep = result.steps.find { !it.wouldSucceed }
        assertNotNull("System component status System component status System component status System component status System component status System component status", failStep)
        assertEquals("System component status System component status System component status System component status System component status System component status 2", 2, result.firstFailureIndex)
        assertNotNull("System component status System component status System component status System component status System component status", failStep!!.failReason)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 9. whatIf remove_step — System and domain documentation note System and domain documentation note delete System and domain documentation note System and domain documentation note
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `whatIf removing delete step reduces conflicts compared to baseline`() {
        // System and domain documentation note System and domain documentation note: patch System and domain documentation note delete System and domain documentation note (System and domain documentation note System and domain documentation note) → DELETE_AFTER_MODIFY
        val path = "/cfg/app.yml"
        val baseline = planner.buildChain(listOf(
            stepWithPath("create_file",        path),
            stepWithPath("patch_file_content", path),
            stepWithPath("delete_file",        path)
        ))

        // System and domain documentation note System and domain documentation note System and domain documentation note System and domain documentation note System and domain documentation note System and domain documentation note System and domain documentation note DELETE_AFTER_MODIFY
        assertTrue(
            "System component status System component status System component status System component status System component status System component status System component status DELETE_AFTER_MODIFY",
            baseline.conflicts.any { it.type == CausalChainPlanner.ConflictType.DELETE_AFTER_MODIFY }
        )

        val diffText = planner.whatIf(baseline, removeStepIndex = 2)

        // System and domain documentation note System and domain documentation note System and domain documentation note System and domain documentation note System and domain documentation note System and domain documentation note System and domain documentation note System and domain documentation note
        assertTrue(
            "System component status whatIf System component status System component status System component status System component status System component status",
            diffText.contains("System component status") || diffText.contains("→")
        )
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 10. buildChain — System and domain documentation note System and domain documentation note System and domain documentation note System and domain documentation note System and domain documentation note
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

        assertEquals("System component status System component status System component status System component status System component status System component status System component status", steps.size, graph.nodes.size)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 11. buildPromptInjection — System and domain documentation note System and domain documentation note System and domain documentation note/System and domain documentation note System and domain documentation note System and domain documentation note System and domain documentation note
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `buildPromptInjection returns empty string for empty graph`() {
        val emptyGraph = planner.buildChain(emptyList())
        val injection = planner.buildPromptInjection(emptyGraph)

        assertTrue("System component status System component status System component status System component status System component status System component status System component status", injection.isEmpty())
    }

    @Test
    fun `buildPromptInjection returns empty string for low-risk graph with no conflicts`() {
        // System and domain documentation note System and domain documentation note — System and domain documentation note System and domain documentation note System and domain documentation note System and domain documentation note
        val graph = planner.buildChain(listOf(
            step("web_search", "query" to "android jetpack compose"),
            step("web_search", "query" to "kotlin flow")
        ))

        val injection = planner.buildPromptInjection(graph)

        assertTrue(
            "System component status System component status System component status System component status System component status System component status System component status System component status System component status System component status",
            injection.isEmpty()
        )
    }

    @Test
    fun `buildPromptInjection returns warning text for graph with fatal conflict`() {
        // System and domain documentation note System and domain documentation note: System and domain documentation note System and domain documentation note System and domain documentation note
        val graph = planner.buildChain(listOf(
            stepWithPath("delete_file",    "/etc/config.json"),
            stepWithPath("read_file_lines", "/etc/config.json")
        ))

        val injection = planner.buildPromptInjection(graph)

        assertTrue("System component status System component status System component status System component status System component status System component status System component status", injection.isNotBlank())
        assertTrue(
            "System component status System component status System component status System component status System component status System component status",
            injection.contains("❌") || injection.contains("System component status")
        )
    }

    @Test
    fun `buildPromptInjection returns warning text for HIGH risk graph even without conflicts`() {
        // delete_file System and domain documentation note = HIGH riskSystem and domain documentation note System and domain documentation note System and domain documentation note
        val graph = planner.buildChain(listOf(
            stepWithPath("delete_file", "/important/file.db")
        ))

        val injection = planner.buildPromptInjection(graph)

        assertTrue(
            "System component status System component status System component status System component status System component status System component status System component status System component status HIGH",
            injection.isNotBlank()
        )
    }
}
