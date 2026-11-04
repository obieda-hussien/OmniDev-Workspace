package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.data.model.*
import com.omnidev.workspace.data.tools.*
import com.omnidev.workspace.domain.engine.completion.FinishTool
import com.omnidev.workspace.domain.engine.completion.RunIdentity
import com.omnidev.workspace.domain.taskstate.InMemoryTaskStateStore
import com.omnidev.workspace.domain.taskstate.InvocationStatus
import com.omnidev.workspace.domain.taskstate.StepStatus
import com.omnidev.workspace.domain.taskstate.TaskStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** The real [AgentPipeline] with persistent task state wired to an in-memory store (no network, no database). */
class PersistentTaskPipelineTest {
    private class Tools(val hang: CompletableDeferred<Unit>? = null) : ToolManager {
        override fun getToolDefinitions() = listOf(
            ToolDefinition("read_file", "Read a file", listOf(ToolParameter("path", "string", "Path"))),
            ToolDefinition("patch_file_content", "Patch a file", listOf(ToolParameter("path", "string", "Path"), ToolParameter("content", "string", "Content")))
        )
        override suspend fun executeTool(name: String, arguments: Map<String, String>, scopePath: String?): ToolExecutionResult {
            if (name == "patch_file_content") hang?.await()
            return ToolExecutionResult(if (name == "read_file") "class Foo { fixed }" else "patched")
        }
    }

    private val config = AgentConfig(maxIterations = 14, enableRetry = false, enableMemoryTrimming = false,
        enableSelfReflection = false, enableParallelToolExecution = false)
    private val task = "Edit Foo.kt to fix the crash, then run the checks and verify the change"
    private fun call(id: String, name: String, vararg args: Pair<String, String>) = ToolCall(id, name, args.toMap())

    private fun pipeline(tools: Tools, store: InMemoryTaskStateStore, script: (Int) -> CompletionResponse): AgentPipeline {
        var round = 0
        return AgentPipeline(tools, completionProvider = { script(++round) }, config = config, taskStateStore = store)
    }

    private suspend fun awaitTrue(what: String, check: suspend () -> Boolean) {
        withTimeout(10_000) { while (!check()) delay(20) }
    }

    @Test fun `a verified run is persisted as a completed task with proven steps`() = runBlocking {
        val store = InMemoryTaskStateStore()
        val events = pipeline(Tools(), store) { round ->
            when (round) {
                1 -> CompletionResponse("", listOf(call("p1", "patch_file_content", "path" to "/tmp/Foo.kt", "content" to "x")))
                2 -> CompletionResponse("", listOf(call("r1", "read_file", "path" to "/tmp/Foo.kt")))
                else -> CompletionResponse("", listOf(call("f1", FinishTool.NAME, "status" to "completed", "summary" to "fixed and read back", "evidence" to "p1,r1")))
            }
        }.execute(task, modelId = "gpt-4o-mini", scopePath = "/tmp", preferredToolNames = setOf("read_file", "patch_file_content"),
            runIdentity = RunIdentity(runId = "task-1", sessionId = 42L)).toList()
        assertEquals(true, events.filterIsInstance<AgentEvent.FinalAnswer>().single().completion?.verified)
        awaitTrue("task completed") { store.task("task-1")?.status == TaskStatus.COMPLETED }
        val snap = store.snapshot("task-1")!!
        assertEquals(task, snap.task.originalRequest)                       // immutable, as the user wrote it
        assertEquals(42L, snap.task.sessionId)
        assertEquals(listOf(InvocationStatus.SUCCEEDED, InvocationStatus.SUCCEEDED), snap.invocations.map { it.status })
        assertEquals(listOf("patch_file_content", "read_file"), snap.invocations.map { it.toolName })
        val step = snap.currentSteps.single()
        assertEquals(StepStatus.COMPLETED, step.status)
        assertTrue(step.evidenceInvocationIds.isNotEmpty())
        assertTrue(snap.cycles.size >= 3)
        assertTrue(snap.invocations.all { it.startedAt != null && it.completedAt != null && it.resultSummary.isNotBlank() })
    }

    @Test fun `plain conversation never creates a task`() = runBlocking {
        val store = InMemoryTaskStateStore()
        val events = pipeline(Tools(), store) { CompletionResponse("Doing well, thanks.") }
            .execute("hello, how are you today?", modelId = "gpt-4o-mini", scopePath = "/tmp",
                preferredToolNames = setOf("read_file"), runIdentity = RunIdentity(runId = "chat-1")).toList()
        assertNull(events.filterIsInstance<AgentEvent.FinalAnswer>().single().completion)
        delay(200)
        assertNull(store.task("chat-1"))
        assertTrue(store.tasksByStatus(TaskStatus.entries.toSet(), 10).isEmpty())
    }

    @Test fun `cancelling a run during a side effect leaves an interrupted task with an unknown outcome`() = runBlocking {
        val store = InMemoryTaskStateStore()
        val hang = CompletableDeferred<Unit>()
        val job: Job = launch(Dispatchers.Default) {
            pipeline(Tools(hang), store) { round ->
                if (round == 1) CompletionResponse("", listOf(call("p1", "patch_file_content", "path" to "/tmp/Foo.kt", "content" to "x")))
                else CompletionResponse("never reached")
            }.execute(task, modelId = "gpt-4o-mini", scopePath = "/tmp", preferredToolNames = setOf("patch_file_content"),
                runIdentity = RunIdentity(runId = "task-2")).collect { }
        }
        awaitTrue("tool marked running before it executes") {
            store.snapshot("task-2")?.invocations?.any { it.status == InvocationStatus.RUNNING } == true
        }
        job.cancel()                                                         // process death stand-in: the result is never stored
        awaitTrue("interrupted") { store.task("task-2")?.status == TaskStatus.INTERRUPTED }
        val snap = store.snapshot("task-2")!!
        assertEquals(InvocationStatus.UNKNOWN, snap.invocations.single().status)
        assertEquals("", snap.invocations.single().resultSummary)
        assertNull(snap.task.finishStatus)
        assertEquals(1, store.unresolvedSideEffects("task-2").size)
    }
}
