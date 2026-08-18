package com.omnidev.workspace.data.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ══════════════════════════════════════════════════════════════════════════════
 * CausalChainPlannerTest — Unit tests for multi-step causal planning
 * ══════════════════════════════════════════════════════════════════════════════
 *
 * Pure JUnit4 tests (without Android dependencies) covering:
 * - Conflict detection (READ_AFTER_DELETEVerified step MODIFY_AFTER_DELETEVerified step DOUBLE_CREATEVerified step DELETE_AFTER_MODIFY)
 * - Happy path (no conflicts)
 * - Critical commands (CRITICAL_COMMAND)
 * - Virtual simulation (simulate)
 * - What-If analysis
 * - Chain construction (buildChain)
 * - Prompt injection (buildPromptInjection)
 */
class CausalChainPlannerTest {

    private val planner = CausalChainPlanner()

    // ──────────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────────

    private fun step(tool: String, vararg params: Pair<String, String>): Pair<String, Map<String, String>> =
        tool to mapOf(*params)

    private fun stepWithPath(tool: String, path: String) = step(tool, "path" to path)

    // ──────────────────────────────────────────────────────────────────────────
    // 1. READ_AFTER_DELETE — Read deleted file
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `READ_AFTER_DELETE conflict is fatal when reading a previously deleted path`() {
        val graph = planner.buildChain(listOf(
            stepWithPath("delete_file", "/tmp/foo.kt"),
            stepWithPath("read_file_lines", "/tmp/foo.kt")
        ))

        val conflict = graph.conflicts.find { it.type == CausalChainPlanner.ConflictType.READ_AFTER_DELETE }
        assertNotNull("Must detect conflict READ_AFTER_DELETE", conflict)
        assertTrue("READ_AFTER_DELETE Must be fatal", conflict!!.isFatal)
        assertEquals("/tmp/foo.kt", conflict.path)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 2. MODIFY_AFTER_DELETE — Modify deleted file
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `MODIFY_AFTER_DELETE conflict is fatal when patching a previously deleted path`() {
        val graph = planner.buildChain(listOf(
            stepWithPath("delete_file", "/src/A.kt"),
            stepWithPath("patch_file_content", "/src/A.kt")
        ))

        val conflict = graph.conflicts.find { it.type == CausalChainPlanner.ConflictType.MODIFY_AFTER_DELETE }
        assertNotNull("Must detect conflict MODIFY_AFTER_DELETE", conflict)
        assertTrue("MODIFY_AFTER_DELETE Must be fatal", conflict!!.isFatal)
        assertEquals("/src/A.kt", conflict.path)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 3. DOUBLE_CREATE — Verified step Verified step Verified step Verified step Verified step Verified step Verified step
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `DOUBLE_CREATE conflict is non-fatal when creating same path twice without delete`() {
        val graph = planner.buildChain(listOf(
            stepWithPath("create_file", "/out/Report.txt"),
            stepWithPath("create_file", "/out/Report.txt")
        ))

        val conflict = graph.conflicts.find { it.type == CausalChainPlanner.ConflictType.DOUBLE_CREATE }
        assertNotNull("Must detect conflict DOUBLE_CREATE", conflict)
        assertFalse("DOUBLE_CREATE Verified step Verified step Verified step Verified step", conflict!!.isFatal)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 4. DELETE_AFTER_MODIFY — Delete modified file Verified step (Verified step Verified step Verified step)
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `DELETE_AFTER_MODIFY conflict is non-fatal when deleting after modify with no intervening read`() {
        val graph = planner.buildChain(listOf(
            stepWithPath("patch_file_content", "/work/data.json"),
            stepWithPath("delete_file", "/work/data.json")
        ))

        val conflict = graph.conflicts.find { it.type == CausalChainPlanner.ConflictType.DELETE_AFTER_MODIFY }
        assertNotNull("Must detect conflict DELETE_AFTER_MODIFY", conflict)
        assertFalse("DELETE_AFTER_MODIFY Verified step Verified step Verified step Verified step", conflict!!.isFatal)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 5. Happy path — create → patch → read → delete — Verified step Verified step
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

        // Verified step Verified step Verified step Verified step DELETE_AFTER_MODIFY — Verified step Verified step Verified step Verified step Verified step Verified step
        assertTrue(
            "Verified step Verified step Verified step Verified step Verified step Verified step Verified step Verified step: ${graph.conflicts.map { it.type }}",
            graph.conflicts.isEmpty()
        )
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 6. CRITICAL_COMMAND — run_terminal Verified step rm -rf
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `run_terminal with rm -rf produces CRITICAL risk node`() {
        val node = planner.analyzeToolCall(
            stepIndex = 0,
            toolName  = "run_terminal",
            parameters = mapOf("command" to "rm -rf /data")
        )

        assertEquals(
            "rm -rf Verified step Verified step Verified step Verified step Verified step CRITICAL",
            CausalChainPlanner.RiskLevel.CRITICAL,
            node.riskLevel
        )
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 7. simulate success — create Verified step patch → Verified step Verified step Verified step
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `simulate success when create_file is followed by patch_file_content`() {
        val graph = planner.buildChain(listOf(
            stepWithPath("create_file",        "/tmp/new.kt"),
            stepWithPath("patch_file_content", "/tmp/new.kt")
        ))

        val result = planner.simulate(graph)

        assertTrue("Verified step Verified step Verified step Verified step Verified step", result.overallSuccess)
        assertTrue("Verified step Verified step Verified step Verified step Verified step",
            result.steps.all { it.wouldSucceed })
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 8. simulate failure — delete Verified step read → Verified step Verified step Verified stepFailure
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `simulate failure when read_file_lines follows delete_file on same path`() {
        // Verified step: Verified step Verified step Verified step Verified step Verified step delete_file Verified step Verified step Verified step Verified step
        val graph = planner.buildChain(listOf(
            stepWithPath("create_file",    "/tmp/gone.txt"),
            stepWithPath("delete_file",    "/tmp/gone.txt"),
            stepWithPath("read_file_lines", "/tmp/gone.txt")
        ))

        val result = planner.simulate(graph)

        assertFalse("Verified step Verified step Verified step Verified stepFailure", result.overallSuccess)

        // Verified step Verified step 2 (read Verified step delete) Verified step Verified step Verified step Verified step Verified stepFailure
        val failStep = result.steps.find { !it.wouldSucceed }
        assertNotNull("Verified step Verified step Verified step Verified step Verified step Verified step", failStep)
        assertEquals("Verified step Verified step Failure Verified step Verified step Verified step 2", 2, result.firstFailureIndex)
        assertNotNull("Verified step Verified step Verified step Verified step Verified stepFailure", failStep!!.failReason)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 9. whatIf remove_step — Verified step Verified step delete Verified step Verified step
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `whatIf removing delete step reduces conflicts compared to baseline`() {
        // Verified step Verified step: patch Verified step delete Verified step (Verified step Verified step) → DELETE_AFTER_MODIFY
        val path = "/cfg/app.yml"
        val baseline = planner.buildChain(listOf(
            stepWithPath("create_file",        path),
            stepWithPath("patch_file_content", path),
            stepWithPath("delete_file",        path)
        ))

        // Verified step Verified step Verified step Verified step Verified step Verified step Verified step DELETE_AFTER_MODIFY
        assertTrue(
            "Verified step Verified step Verified step Verified step Verified step Verified step Verified step DELETE_AFTER_MODIFY",
            baseline.conflicts.any { it.type == CausalChainPlanner.ConflictType.DELETE_AFTER_MODIFY }
        )

        val diffText = planner.whatIf(baseline, removeStepIndex = 2)

        // Verified step Verified step Verified step Verified step Verified step Verified step Verified step Verified step
        assertTrue(
            "Verified step whatIf Verified step Verified step Verified step Verified step Verified step",
            diffText.contains("Verified step") || diffText.contains("→")
        )
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 10. buildChain — Verified step Verified step Verified step Verified step Verified step
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

        assertEquals("Verified step Verified step Verified step Verified step Verified step Verified step Verified step", steps.size, graph.nodes.size)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 11. buildPromptInjection — Verified step Verified step Verified step/Verified step Verified step Verified step Verified step
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `buildPromptInjection returns empty string for empty graph`() {
        val emptyGraph = planner.buildChain(emptyList())
        val injection = planner.buildPromptInjection(emptyGraph)

        assertTrue("Verified step Verified step Verified step Verified step Verified step Verified step Verified step", injection.isEmpty())
    }

    @Test
    fun `buildPromptInjection returns empty string for low-risk graph with no conflicts`() {
        // Verified step Verified step — Verified step Verified step Verified step Verified step
        val graph = planner.buildChain(listOf(
            step("web_search", "query" to "android jetpack compose"),
            step("web_search", "query" to "kotlin flow")
        ))

        val injection = planner.buildPromptInjection(graph)

        assertTrue(
            "Verified step Verified step Verified step Verified step Verified step Verified step Verified step Verified step Verified step Verified step",
            injection.isEmpty()
        )
    }

    @Test
    fun `buildPromptInjection returns warning text for graph with fatal conflict`() {
        // Verified step Verified step: Verified step Verified step Verified step
        val graph = planner.buildChain(listOf(
            stepWithPath("delete_file",    "/etc/config.json"),
            stepWithPath("read_file_lines", "/etc/config.json")
        ))

        val injection = planner.buildPromptInjection(graph)

        assertTrue("Verified step Verified step Verified step Verified step Verified step Verified step Verified step", injection.isNotBlank())
        assertTrue(
            "Verified step Verified step Verified step Verified step Verified step Verified step",
            injection.contains("❌") || injection.contains("Verified step")
        )
    }

    @Test
    fun `buildPromptInjection returns warning text for HIGH risk graph even without conflicts`() {
        // delete_file Verified step = HIGH riskVerified step Verified step Verified step
        val graph = planner.buildChain(listOf(
            stepWithPath("delete_file", "/important/file.db")
        ))

        val injection = planner.buildPromptInjection(graph)

        assertTrue(
            "Verified step Verified step Verified step Verified step Verified step Verified step Verified step Verified step HIGH",
            injection.isNotBlank()
        )
    }
}
