package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.data.model.ToolCall
import com.omnidev.workspace.data.tools.ToolExecutionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentStagnationDetectorTest {

    @Test
    fun `persistent backend failure is infrastructure block not decomposition`() {
        val detector = AgentStagnationDetector(abortThreshold = 0.70f)

        var snapshot: AgentStagnationDetector.Snapshot? = null
        repeat(3) { index ->
            snapshot = detector.observe(
                toolCalls = listOf(call("shizuku_command", mapOf("command" to "settings get secure foo"))),
                results = listOf(
                    ToolExecutionResult(
                        output = "Permission denied on Shizuku backend attempt $index",
                        isError = true,
                        classification = "PERMISSION_DENIED",
                        backend = "shizuku-user-service",
                        retryable = false,
                        persistentFailure = true
                    )
                )
            )
        }

        val final = requireNotNull(snapshot)
        assertEquals(AgentStagnationDetector.Kind.INFRASTRUCTURE_BLOCK, final.kind)
        assertTrue(final.shouldAbort)
        assertFalse(final.canBenefitFromDecomposition)
    }

    @Test
    fun `repeated read only observations become strategy stagnation`() {
        val detector = AgentStagnationDetector(abortThreshold = 0.68f)

        var snapshot: AgentStagnationDetector.Snapshot? = null
        repeat(4) {
            snapshot = detector.observe(
                toolCalls = listOf(call("read_file", mapOf("path" to "app/Main.kt"))),
                results = listOf(
                    ToolExecutionResult(
                        output = "class Main { fun unchanged() = Unit }",
                        isError = false,
                        classification = "OK",
                        backend = "filesystem"
                    )
                )
            )
        }

        val final = requireNotNull(snapshot)
        assertEquals(AgentStagnationDetector.Kind.STRATEGY_STAGNATION, final.kind)
        assertTrue(final.shouldAbort)
        assertTrue(final.canBenefitFromDecomposition)
        assertTrue(final.noActionStreak >= 3)
        assertTrue(final.reasons.any { it.contains("repeating") || it.contains("read-only") })
    }

    @Test
    fun `new observations with actions remain healthy`() {
        val detector = AgentStagnationDetector(abortThreshold = 0.68f)

        repeat(5) { index ->
            val snapshot = detector.observe(
                toolCalls = listOf(
                    call(
                        "patch_file",
                        mapOf("path" to "app/File$index.kt", "patch" to "change-$index")
                    )
                ),
                results = listOf(
                    ToolExecutionResult(
                        output = "Patched app/File$index.kt successfully",
                        isError = false,
                        classification = "OK",
                        backend = "filesystem",
                        verification = "read-back-$index"
                    )
                )
            )
            assertEquals(AgentStagnationDetector.Kind.HEALTHY, snapshot.kind)
            assertFalse(snapshot.shouldAbort)
        }
    }

    @Test
    fun `retryable transient failures are less likely to be infrastructure locked`() {
        val detector = AgentStagnationDetector(abortThreshold = 0.68f)

        repeat(2) { index ->
            detector.observe(
                toolCalls = listOf(call("fetch_page", mapOf("url" to "https://example.com/$index"))),
                results = listOf(
                    ToolExecutionResult(
                        output = "Temporary timeout $index",
                        isError = true,
                        classification = "TIMEOUT",
                        backend = "http",
                        retryable = true,
                        persistentFailure = false
                    )
                )
            )
        }
        val recovered = detector.observe(
            toolCalls = listOf(call("fetch_page", mapOf("url" to "https://example.com/final"))),
            results = listOf(
                ToolExecutionResult(
                    output = "Fresh successful content",
                    isError = false,
                    classification = "OK",
                    backend = "http"
                )
            )
        )

        assertFalse(recovered.kind == AgentStagnationDetector.Kind.INFRASTRUCTURE_BLOCK)
        assertFalse(recovered.shouldAbort)
    }

    private fun call(name: String, arguments: Map<String, String>): ToolCall = ToolCall(
        id = "id-${name}-${arguments.hashCode()}",
        name = name,
        arguments = arguments
    )
}
