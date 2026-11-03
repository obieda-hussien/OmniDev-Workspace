package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.data.model.*
import com.omnidev.workspace.data.tools.*
import com.omnidev.workspace.domain.engine.completion.FinishStatus
import com.omnidev.workspace.domain.engine.completion.FinishTool
import com.omnidev.workspace.domain.engine.completion.RunIdentity
import com.omnidev.workspace.domain.engine.completion.TaskKind
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Runs the real [AgentPipeline] with scripted provider responses (no network). */
class FinishProtocolPipelineTest {
    private class FileTools : ToolManager {
        val executed = mutableListOf<Pair<String, Map<String, String>>>()
        var failFirstPatch = false
        private var patches = 0
        override fun getToolDefinitions() = listOf(
            ToolDefinition("read_file", "Read a file", listOf(ToolParameter("path", "string", "Path"))),
            ToolDefinition("patch_file_content", "Patch a file", listOf(ToolParameter("path", "string", "Path"), ToolParameter("content", "string", "Content")))
        )
        override suspend fun executeTool(name: String, arguments: Map<String, String>, scopePath: String?): ToolExecutionResult {
            executed += name to arguments
            if (name == "patch_file_content" && failFirstPatch && ++patches == 1) return ToolExecutionResult("write failed", true, classification = "IO_ERROR")
            return ToolExecutionResult(if (name == "read_file") "class Foo { fixed }" else "patched")
        }
    }

    private val config = AgentConfig(maxIterations = 14, enableRetry = false, enableMemoryTrimming = false,
        enableSelfReflection = false, enableParallelToolExecution = false)
    private val task = "Edit Foo.kt to fix the crash, then run the checks and verify the change"
    private fun call(id: String, name: String, vararg args: Pair<String, String>) = ToolCall(id, name, args.toMap())
    private fun finish(id: String, vararg args: Pair<String, String>) = ToolCall(id, FinishTool.NAME, args.toMap())

    private suspend fun run(tools: FileTools, identity: RunIdentity? = null, persona: String? = null, message: String = task,
                            script: (Int, CompletionRequest) -> CompletionResponse): List<AgentEvent> {
        var round = 0
        val pipeline = AgentPipeline(tools, completionProvider = { req -> script(++round, req) }, config = config)
        return pipeline.execute(message, modelId = "gpt-4o-mini", scopePath = "/tmp",
            preferredToolNames = setOf("read_file", "patch_file_content"), workerPersona = persona, runIdentity = identity).toList()
    }

    private fun List<AgentEvent>.final() = filterIsInstance<AgentEvent.FinalAnswer>().single()

    @Test fun `task text is classified as an action task`() {
        val s = IntentClassifier.analyze(task)
        assertTrue(s.executionIntent >= 0.34f || s.mutationIntent >= 0.16f)
    }

    // 1. Claims completion without calling any tool: never published as success.
    @Test fun `claim of completion without any tool call ends incomplete`() = runTest {
        val tools = FileTools()
        val events = run(tools) { _, _ -> CompletionResponse("I fixed Foo.kt and verified it.") }
        val f = events.final()
        assertTrue(tools.executed.isEmpty())
        assertTrue(f.content.startsWith("[Incomplete]"))
        assertEquals(FinishStatus.INCOMPLETE, f.completion!!.status)
        assertFalse(f.completion!!.verified)
    }

    // 2/3/4. Edit then read back: accepted only after the read-back; failure is recovered.
    @Test fun `failed patch is recovered and finish is accepted only after read back`() = runTest {
        val tools = FileTools().apply { failFirstPatch = true }
        val events = run(tools) { round, req ->
            when (round) {
                1 -> CompletionResponse("", listOf(call("p1", "patch_file_content", "path" to "/tmp/Foo.kt", "content" to "x")))
                2 -> CompletionResponse("", listOf(call("p2", "patch_file_content", "path" to "/tmp/Foo.kt", "content" to "x")))
                3 -> CompletionResponse("", listOf(finish("f1", "status" to "completed", "summary" to "patched", "evidence" to "p2")))
                4 -> CompletionResponse("", listOf(call("r1", "read_file", "path" to "/tmp/Foo.kt")))
                else -> CompletionResponse("", listOf(finish("f2", "status" to "completed", "summary" to "Foo.kt fixed and read back", "evidence" to "p2,r1")))
            }
        }
        val f = events.final()
        val report = f.completion!!
        assertEquals(FinishStatus.COMPLETED, report.status)
        assertTrue(report.verified)
        assertEquals(1, report.stats.rejections) // the read-back was missing the first time
        assertEquals("Foo.kt fixed and read back", f.content)
        assertEquals(f.content, f.conversationHistory.last().content)
    }

    // 5. Evidence from another run / unknown ids is rejected.
    @Test fun `invented evidence id is rejected and the run ends incomplete instead of success`() = runTest {
        val tools = FileTools()
        val events = run(tools) { round, _ ->
            if (round == 1) CompletionResponse("", listOf(call("r1", "read_file", "path" to "/tmp/Foo.kt")))
            else CompletionResponse("", listOf(finish("f$round", "status" to "completed", "summary" to "done", "evidence" to "someone-elses-call")))
        }
        val f = events.final()
        assertEquals(FinishStatus.INCOMPLETE, f.completion!!.status)
        assertFalse(f.completion!!.verified)
        assertTrue(f.completion!!.stats.rejections > 3)
    }

    // 6. Malformed finish data.
    @Test fun `malformed finish arguments are rejected and counted`() = runTest {
        val tools = FileTools()
        val events = run(tools) { round, _ ->
            when (round) {
                1 -> CompletionResponse("", listOf(call("r1", "read_file", "path" to "/tmp/Foo.kt")))
                2 -> CompletionResponse("", listOf(finish("f1", "status" to "done!!", "summary" to "x")))
                3 -> CompletionResponse("", listOf(call("p1", "patch_file_content", "path" to "/tmp/Foo.kt", "content" to "fixed")))
                4 -> CompletionResponse("", listOf(call("r2", "read_file", "path" to "/tmp/Foo.kt")))
                else -> CompletionResponse("", listOf(finish("f2", "status" to "completed", "summary" to "patched and verified", "evidence" to "p1,r2")))
            }
        }
        val report = events.final().completion!!
        assertEquals(1, report.stats.malformed)
        assertEquals(FinishStatus.COMPLETED, report.status)
    }

    // 7. Approval required.
    @Test fun `awaiting approval is published as such and not as success`() = runTest {
        val tools = FileTools()
        val events = run(tools) { _, _ ->
            CompletionResponse("", listOf(finish("f1", "status" to "awaiting_approval", "summary" to "Need your approval to overwrite Foo.kt")))
        }
        val f = events.final()
        assertEquals(FinishStatus.AWAITING_APPROVAL, f.completion!!.status)
        assertTrue(f.content.startsWith("[Awaiting approval]"))
        assertFalse(f.completion!!.verified)
    }

    // 8. Normal conversation: no finish tool exposed, no completion report, no interception.
    @Test fun `plain conversation is not bound by the protocol`() = runTest {
        val tools = FileTools()
        var sawFinishTool = false
        val events = run(tools, message = "hello, how are you today?") { _, req ->
            if (req.tools.orEmpty().any { it.name == FinishTool.NAME }) sawFinishTool = true
            CompletionResponse("Doing well, thanks for asking.")
        }
        val f = events.final()
        assertFalse(sawFinishTool)
        assertNull(f.completion)
        assertEquals("Doing well, thanks for asking.", f.content)
    }

    @Test fun `finish_task is exposed as a control tool and never executed by the tool manager`() = runTest {
        val tools = FileTools()
        var exposed = false
        run(tools) { round, req ->
            if (req.tools.orEmpty().any { it.name == FinishTool.NAME }) exposed = true
            when (round) {
                1 -> CompletionResponse("", listOf(call("r1", "read_file", "path" to "/tmp/Foo.kt")))
                2 -> CompletionResponse("", listOf(call("p1", "patch_file_content", "path" to "/tmp/Foo.kt", "content" to "fixed")))
                3 -> CompletionResponse("", listOf(call("r2", "read_file", "path" to "/tmp/Foo.kt")))
                else -> CompletionResponse("", listOf(finish("f1", "status" to "completed", "summary" to "ok", "evidence" to "p1,r2")))
            }
        }
        assertTrue(exposed)
        assertTrue(tools.executed.none { it.first == FinishTool.NAME })
    }

    // 9. Long run: many real pipeline cycles with a tiny context budget so compaction runs.
    @Test fun `forty cycles survive compaction and state is still rendered at the end`() = runTest {
        val tools = FileTools()
        var lastState: String? = null
        val long = config.copy(maxIterations = 60)
        val readOnlyTask = "Analyze and verify 40 files and report findings"
        var round = 0
        val pipeline = AgentPipeline(tools, completionProvider = { req ->
            round++
            lastState = req.messages.lastOrNull { it.content.startsWith("[RUN STATE") }?.content ?: lastState
            if (round <= 40) CompletionResponse("", listOf(call("c$round", "read_file", "path" to "/tmp/F$round.kt")))
            else CompletionResponse("", listOf(finish("f", "status" to "completed", "summary" to "read 40 files", "evidence" to "c40")))
        }, config = long)
        val events = pipeline.execute(readOnlyTask, modelId = "gpt-4o-mini", scopePath = "/tmp",
            preferredToolNames = setOf("read_file", "patch_file_content"),
            runIdentity = RunIdentity(taskKind = TaskKind.ANALYSIS)).toList()
        val report = events.final().completion!!
        assertEquals(FinishStatus.COMPLETED, report.status)
        assertTrue(lastState!!.contains("GOAL: $readOnlyTask"))
        assertTrue(lastState!!.contains("c39") || lastState!!.contains("c40"))
    }

    // 10 (pipeline side). Worker identity is carried into the report.
    @Test fun `worker run reports its own run and worker id and needs real evidence`() = runTest {
        val tools = FileTools()
        val id = RunIdentity(runId = "run-w1", workerId = "w1", taskKind = TaskKind.IMPLEMENTATION)
        val events = run(tools, identity = id, persona = "implementer", message = "## Assigned Team Task\nPatch Foo.kt") { round, _ ->
            when (round) {
                1 -> CompletionResponse("", listOf(finish("f1", "status" to "completed", "summary" to "done")))
                2 -> CompletionResponse("", listOf(call("p1", "patch_file_content", "path" to "/tmp/Foo.kt", "content" to "x")))
                3 -> CompletionResponse("", listOf(call("r1", "read_file", "path" to "/tmp/Foo.kt")))
                else -> CompletionResponse("", listOf(finish("f2", "status" to "completed", "summary" to "patched", "evidence" to "p1,r1")))
            }
        }
        val report = events.final().completion!!
        assertEquals("run-w1", report.runId)
        assertEquals("w1", report.workerId)
        assertTrue(report.verified)
        assertTrue(report.evidence.all { it.runId == "run-w1" && it.workerId == "w1" })
    }
}
