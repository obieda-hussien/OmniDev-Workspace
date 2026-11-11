package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.data.model.ToolCall
import com.omnidev.workspace.data.tools.*
import org.junit.Assert.*
import org.junit.Test

class ToolCorrectionAdvisorTest {
    private val definition = ToolDefinition("github_manager", "GitHub", listOf(
        ToolParameter("action", "string", "Action", allowedValues = listOf("read_file", "create_issue")),
        ToolParameter("repo", "string", "Repository"),
        ToolParameter("title", "string", "Title", false, requiredForActions = listOf("create_issue")),
        ToolParameter("count", "integer", "Count", false)))
    private val advisor = ToolCorrectionAdvisor(listOf(definition))
    private fun call(args: Map<String, String> = mapOf("action" to "read_file", "repo" to "owner/repo"), name: String = definition.name) = ToolCall("call", name, args)

    @Test fun `valid exact proposal needs no corrections`() { assertTrue(advisor.review(call()).valid) }
    @Test fun `tool typo suggests only an eligible registered name`() {
        val c = call(name = "github_managr"); val before = c.copy()
        assertEquals("github_manager", advisor.review(c).issues.single().suggestion)
        assertEquals(before, c)
        assertEquals("TOOL_NOT_EXPOSED", ToolCallPreflight(listOf(definition)).check(c)!!.classification)
    }
    @Test fun `case correction still requires exact resubmission`() {
        val c = call(name = "GitHub_Manager")
        assertEquals(definition.name, advisor.review(c).issues.single().suggestion)
        assertNotNull(ToolCallPreflight(listOf(definition)).check(c))
    }
    @Test fun `absent and ambiguous names do not invent authority`() {
        assertNull(ToolCorrectionAdvisor(emptyList()).review(call()).issues.single().suggestion)
        val a = ToolCorrectionAdvisor(listOf(ToolDefinition("git_read", "Read"), ToolDefinition("git_real", "Other")))
        assertNull(a.review(call(name = "git_rea")).issues.single().suggestion)
    }
    @Test fun `action correction also exposes the required dependent fields`() {
        val c = call(mapOf("action" to "create_isse", "repo" to "owner/repo"))
        val r = advisor.review(c)
        assertEquals("create_issue", r.issues.single { it.kind == ToolContractIssueKind.INVALID_ACTION }.suggestion)
        assertTrue(r.issues.any { it.kind == ToolContractIssueKind.MISSING_PARAMETER && it.field == "title" })
        assertNotNull(ToolCallPreflight(listOf(definition)).check(c))
        assertNull(ToolCallPreflight(listOf(definition)).check(call(mapOf("action" to "create_issue", "repo" to "owner/repo", "title" to "Issue"))))
    }
    @Test fun `unknown keys missing fields and wrong types appear in one bounded report`() {
        val r = advisor.review(call(mapOf("action" to "read_file", "reop" to "owner/repo", "count" to "two")))
        assertEquals(setOf(ToolContractIssueKind.UNKNOWN_PARAMETER, ToolContractIssueKind.MISSING_PARAMETER, ToolContractIssueKind.INVALID_TYPE), r.issues.map { it.kind }.toSet())
        assertEquals("repo", r.issues.single { it.kind == ToolContractIssueKind.UNKNOWN_PARAMETER }.suggestion)
    }
    @Test fun `malformed decoded arguments never become repairable empty calls`() {
        assertEquals(ToolContractIssueKind.MALFORMED_ARGUMENTS, advisor.review(call().copy(argumentError = "private malformed payload")).issues.single().kind)
    }
    @Test fun `feedback excludes supplied keys values and credential-like input`() {
        val secret = "private-credential-do-not-render"
        val r = advisor.review(call(mapOf("action" to secret, secret to secret, "count" to secret)))
        assertFalse(r.render().contains(secret))
        assertFalse(ToolCallPreflight(listOf(definition)).check(call(mapOf("action" to secret, secret to secret)))!!.output.contains(secret))
    }
    @Test fun `large malformed maps cannot create unbounded feedback`() {
        val r = advisor.review(call((1..100).associate { "unknown-$it" to "private value" }))
        assertEquals(12, r.issues.size); assertTrue(r.moreIssues); assertTrue(r.render().length <= 2400)
    }
    @Test fun `batch correction never executes or unblocks valid peers`() {
        val guard = ToolCallPreflight(listOf(definition))
        val bad = call(mapOf("action" to "create_isse", "repo" to "owner/repo"))
        val results = guard.checkBatch(listOf(bad, call().copy(id = "second")))
        assertTrue(results.all { it?.isError == true })
        assertEquals("BATCH_PREFLIGHT_BLOCKED", results[1]!!.classification)
        assertTrue(results[0]!!.output.contains("create_issue"))
    }
    @Test fun `duplicate registry names cannot produce a correction candidate`() {
        assertNull(ToolCorrectionAdvisor(listOf(definition, definition)).review(call(name = "github_managr")).issues.single().suggestion)
    }
    @Test fun `every valid resubmission still goes through normal argument validation`() {
        val guard = ToolCallPreflight(listOf(definition))
        assertNotNull(guard.check(call(mapOf("action" to "create_issue", "repo" to "owner/repo"))))
        assertNotNull(guard.check(call(mapOf("action" to "read_file", "repo" to "owner/repo", "count" to "NaN"))))
    }
}
