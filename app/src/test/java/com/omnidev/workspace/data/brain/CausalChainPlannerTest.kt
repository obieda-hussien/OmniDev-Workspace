package com.omnidev.workspace.data.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JUnit4 causal-planning tests without Android dependencies: conflict detection (READ_AFTER_DELETE, MODIFY_AFTER_DELETE, DOUBLE_CREATE, DELETE_AFTER_MODIFY), conflict-free paths, critical commands, simulation, what-if analysis, buildChain and buildPromptInjection. */
class CausalChainPlannerTest {

    private val planner = CausalChainPlanner()

    // ──────────────────────────────────────────────────────────────────────────
    // Helpers.
    // ──────────────────────────────────────────────────────────────────────────

    private fun step(tool: String, vararg params: Pair<String, String>): Pair<String, Map<String, String>> =
        tool to mapOf(*params)

    private fun stepWithPath(tool: String, path: String) = step(tool, "path" to path)

    // ──────────────────────────────────────────────────────────────────────────
    // 1. READ_AFTER_DELETE: read a deleted file.
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `READ_AFTER_DELETE conflict is fatal when reading a previously deleted path`() {
        val graph = planner.buildChain(listOf(
            stepWithPath("delete_file", "/tmp/foo.kt"),
            stepWithPath("read_file_lines", "/tmp/foo.kt")
        ))

        val conflict = graph.conflicts.find { it.type == CausalChainPlanner.ConflictType.READ_AFTER_DELETE }
        assertNotNull("Must detect conflict READ_AFTER_DELETE", conflict)
        assertTrue("READ_AFTER_DELETE must be fatal", conflict!!.isFatal)
        assertEquals("/tmp/foo.kt", conflict.path)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 2. MODIFY_AFTER_DELETE: modify a deleted file.
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `MODIFY_AFTER_DELETE conflict is fatal when patching a previously deleted path`() {
        val graph = planner.buildChain(listOf(
            stepWithPath("delete_file", "/src/A.kt"),
            stepWithPath("patch_file_content", "/src/A.kt")
        ))

        val conflict = graph.conflicts.find { it.type == CausalChainPlanner.ConflictType.MODIFY_AFTER_DELETE }
        assertNotNull("Must detect conflict MODIFY_AFTER_DELETE", conflict)
        assertTrue("MODIFY_AFTER_DELETE must be fatal", conflict!!.isFatal)
        assertEquals("/src/A.kt", conflict.path)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 3. DOUBLE_CREATE: create the same file twice without an intervening delete.
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `DOUBLE_CREATE conflict is non-fatal when creating same path twice without delete`() {
        val graph = planner.buildChain(listOf(
            stepWithPath("create_file", "/out/Report.txt"),
            stepWithPath("create_file", "/out/Report.txt")
        ))

        val conflict = graph.conflicts.find { it.type == CausalChainPlanner.ConflictType.DOUBLE_CREATE }
        assertNotNull("Must detect conflict DOUBLE_CREATE", conflict)
        assertFalse("DOUBLE_CREATE must not be fatal", conflict!!.isFatal)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 4. DELETE_AFTER_MODIFY: delete immediately after modification without reading.
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `DELETE_AFTER_MODIFY conflict is non-fatal when deleting after modify with no intervening read`() {
        val graph = planner.buildChain(listOf(
            stepWithPath("patch_file_content", "/work/data.json"),
            stepWithPath("delete_file", "/work/data.json")
        ))

        val conflict = graph.conflicts.find { it.type == CausalChainPlanner.ConflictType.DELETE_AFTER_MODIFY }
        assertNotNull("Must detect conflict DELETE_AFTER_MODIFY", conflict)
        assertFalse("DELETE_AFTER_MODIFY must not be fatal", conflict!!.isFatal)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 5. Happy path: create → patch → read → delete, without conflicts.
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

        // Reading after modification prevents DELETE_AFTER_MODIFY; expect no conflicts.
        assertTrue(
            "Happy path must not produce conflicts; found: ${graph.conflicts.map { it.type }}",
            graph.conflicts.isEmpty()
        )
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 6. CRITICAL_COMMAND: run_terminal with rm -rf.
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `run_terminal with rm -rf produces CRITICAL risk node`() {
        val node = planner.analyzeToolCall(
            stepIndex = 0,
            toolName  = "run_terminal",
            parameters = mapOf("command" to "rm -rf /data")
        )

        assertEquals(
            "rm -rf must produce CRITICAL risk",
            CausalChainPlanner.RiskLevel.CRITICAL,
            node.riskLevel
        )
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 7. Successful simulation: create then patch; all steps succeed.
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `simulate success when create_file is followed by patch_file_content`() {
        val graph = planner.buildChain(listOf(
            stepWithPath("create_file",        "/tmp/new.kt"),
            stepWithPath("patch_file_content", "/tmp/new.kt")
        ))

        val result = planner.simulate(graph)

        assertTrue("Simulation must succeed", result.overallSuccess)
        assertTrue("All steps must succeed",
            result.steps.all { it.wouldSucceed })
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 8. Failed simulation: delete then read; the read fails.
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `simulate failure when read_file_lines follows delete_file on same path`() {
        // Create the file first so delete_file is valid in the simulated state.
        val graph = planner.buildChain(listOf(
            stepWithPath("create_file",    "/tmp/gone.txt"),
            stepWithPath("delete_file",    "/tmp/gone.txt"),
            stepWithPath("read_file_lines", "/tmp/gone.txt")
        ))

        val result = planner.simulate(graph)

        assertFalse("Simulation must fail", result.overallSuccess)

        // Step index 2 (read after delete) should fail.
        val failStep = result.steps.find { !it.wouldSucceed }
        assertNotNull("There must be a failed step", failStep)
        assertEquals("First failure index must be 2", 2, result.firstFailureIndex)
        assertNotNull("Failure reason must be provided", failStep!!.failReason)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 9. What-if removal: removing delete reduces conflicts.
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `whatIf removing delete step reduces conflicts compared to baseline`() {
        // Baseline plan: patch then immediate delete without reading causes DELETE_AFTER_MODIFY.
        val path = "/cfg/app.yml"
        val baseline = planner.buildChain(listOf(
            stepWithPath("create_file",        path),
            stepWithPath("patch_file_content", path),
            stepWithPath("delete_file",        path)
        ))

        // The baseline must contain a DELETE_AFTER_MODIFY conflict.
        assertTrue(
            "Baseline must contain a DELETE_AFTER_MODIFY conflict",
            baseline.conflicts.any { it.type == CausalChainPlanner.ConflictType.DELETE_AFTER_MODIFY }
        )

        val diffText = planner.whatIf(baseline, removeStepIndex = 2)

        // The report should indicate fewer conflicts.
        assertTrue(
            "What-if report must indicate fewer conflicts",
            diffText.contains("reduces") || diffText.contains("→")
        )
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 10. buildChain: node count equals step count.
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

        assertEquals("Node count must match step count", steps.size, graph.nodes.size)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 11. buildPromptInjection: empty for empty or low-risk graphs, populated for high-risk graphs.
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `buildPromptInjection returns empty string for empty graph`() {
        val emptyGraph = planner.buildChain(emptyList())
        val injection = planner.buildPromptInjection(emptyGraph)

        assertTrue("Injection must be empty for an empty graph", injection.isEmpty())
    }

    @Test
    fun `buildPromptInjection returns empty string for low-risk graph with no conflicts`() {
        // Search only: low risk, no conflicts.
        val graph = planner.buildChain(listOf(
            step("web_search", "query" to "android jetpack compose"),
            step("web_search", "query" to "kotlin flow")
        ))

        val injection = planner.buildPromptInjection(graph)

        assertTrue(
            "Injection must be empty without high risk",
            injection.isEmpty()
        )
    }

    @Test
    fun `buildPromptInjection returns warning text for graph with fatal conflict`() {
        // Critical conflict: delete then read.
        val graph = planner.buildChain(listOf(
            stepWithPath("delete_file",    "/etc/config.json"),
            stepWithPath("read_file_lines", "/etc/config.json")
        ))

        val injection = planner.buildPromptInjection(graph)

        assertTrue("Injection must contain warning text", injection.isNotBlank())
        assertTrue(
            "Injection must mention the critical conflict",
            injection.contains("❌") || injection.contains("conflict")
        )
    }

    @Test
    fun `buildPromptInjection returns warning text for HIGH risk graph even without conflicts`() {
        // Standalone delete_file has HIGH risk without conflicts.
        val graph = planner.buildChain(listOf(
            stepWithPath("delete_file", "/important/file.db")
        ))

        val injection = planner.buildPromptInjection(graph)

        assertTrue(
            "Injection must contain warning text for HIGH risk",
            injection.isNotBlank()
        )
    }
}
