package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.data.model.ModelTier
import com.omnidev.workspace.data.tools.ToolDefinition
import com.omnidev.workspace.data.tools.ToolParameter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPromptCompilerTest {

    private val verboseTool = ToolDefinition(
        name = "read_file_lines",
        description = "Read a precise line range from a project file. " + "detail ".repeat(100),
        parameters = listOf(ToolParameter("path", "string", "Project file path"))
    )

    @Test
    fun `on demand prompt omits verbose schema prose`() {
        val prompt = AgentPromptCompiler.compile(
            tier = ModelTier.EXECUTOR,
            scopePath = "/workspace",
            baseOverride = null,
            workerPersona = null,
            userContext = null,
            memoryContext = null,
            brainContext = null,
            toolDefinitions = listOf(verboseTool),
            toolAccessMode = "ON_DEMAND",
            enableDeepThinking = false,
            supportsThinking = true
        )

        assertFalse(prompt.contains(verboseTool.description))
        assertTrue(prompt.contains("native function schemas"))
        assertTrue(prompt.length < 8_000)
    }

    @Test
    fun `normal prompt exposes compact tool index not full schema`() {
        val prompt = AgentPromptCompiler.compile(
            tier = ModelTier.EXECUTOR,
            scopePath = "/workspace",
            baseOverride = null,
            workerPersona = "Android specialist",
            userContext = null,
            memoryContext = "previous migration failed",
            brainContext = "prefer read_file_lines after search_codebase",
            toolDefinitions = listOf(verboseTool),
            toolAccessMode = "AUTO",
            enableDeepThinking = false,
            supportsThinking = true
        )

        assertTrue(prompt.contains("read_file_lines"))
        assertFalse(prompt.contains("detail ".repeat(40)))
        assertTrue(prompt.contains("previous migration failed"))
        assertTrue(prompt.length < 10_000)
    }
}
