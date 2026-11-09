package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.data.model.*
import com.omnidev.workspace.data.tools.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class TeamDependencyRepairTest {
    @Test fun `exhausted worker is not restarted and every transitive dependent reports the actual failure`() = runTest {
        var workerRequests = 0
        val tools = object : ToolManager {
            override fun getToolDefinitions() = listOf(ToolDefinition("github_manager", "Read GitHub source"))
            override suspend fun executeTool(name: String, arguments: Map<String, String>, scopePath: String?) = error("No tool selected")
        }
        val team = SwarmOrchestrator(tools, completionProvider = { r ->
            if (r.systemPrompt.orEmpty().contains("Omni Team Orchestrator")) {
                assertTrue(r.systemPrompt.orEmpty().contains("not a verified checkout"))
                CompletionResponse("""[
                    {"id":"inspect","description":"Inspect repository owner/project","parallelSafe":true},
                    {"id":"branch","description":"Create feature branch","dependencies":["inspect"],"parallelSafe":false},
                    {"id":"implement","description":"Implement source","dependencies":["branch"],"parallelSafe":false},
                    {"id":"verify","description":"Run the unit tests","dependencies":["implement"],"parallelSafe":false}]
                """)
            } else { workerRequests++; CompletionResponse("I'll start by inspecting the repository:") }
        })
        val events = team.execute("Inspect GitHub owner/project and implement the change", "gpt-4o-mini", "gpt-4o-mini", "/tmp").toList()
        assertEquals(1, events.filterIsInstance<SwarmEvent.TaskStarted>().size)
        assertTrue(workerRequests <= 8)
        assertTrue(events.filterIsInstance<SwarmEvent.WorkerPhaseChanged>().none { it.phase == "RECOVER" })
        val skipped = events.filterIsInstance<SwarmEvent.TaskSkipped>()
        assertEquals(3, skipped.size)
        assertTrue(skipped.all { it.reason.contains("Blocked by failed/skipped dependency") && !it.reason.contains("cycle") })
        assertFalse(events.filterIsInstance<SwarmEvent.Completed>().single().completion!!.verified)
    }
}
