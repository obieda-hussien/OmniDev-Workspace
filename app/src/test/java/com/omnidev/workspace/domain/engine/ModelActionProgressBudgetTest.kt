package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.data.model.ToolCall
import com.omnidev.workspace.data.tools.ToolExecutionResult
import org.junit.Assert.*
import org.junit.Test

class ModelActionProgressBudgetTest {
    @Test fun `thinking without tools is bounded`() {
        val budget = ModelActionProgressBudget(2000)
        assertFalse(budget.onModelResponse(900, emptyList(), "", true))
        assertFalse(budget.onModelResponse(999, emptyList(), "", true))
        assertTrue(budget.onModelResponse(101, emptyList(), "", true))
    }

    @Test fun `real progress resets budget but failed and discovery calls do not`() {
        val budget = ModelActionProgressBudget(1000)
        val call = ToolCall("call1", "github_manager", mapOf("action" to "get_repo"))
        assertFalse(budget.onModelResponse(900, emptyList(), "", true))
        budget.onToolResults(listOf(call), listOf(ToolExecutionResult("error", true)))
        assertTrue(budget.onModelResponse(100, emptyList(), "", true))
        budget.onToolResults(listOf(call), listOf(ToolExecutionResult("verified remote info")))
        assertFalse(budget.onModelResponse(100, emptyList(), "", true))
    }

    @Test fun `actual answer and tool proposals are not prematurely killed`() {
        val budget = ModelActionProgressBudget(1000)
        assertFalse(budget.onModelResponse(2000, emptyList(), "The verified result is ready.", true))
        assertFalse(budget.onModelResponse(2000, listOf(
            ToolCall("t", "github_manager", mapOf("action" to "get_repo"))), "", true))
        assertFalse(budget.onModelResponse(2000, emptyList(), "", false))
    }
}
