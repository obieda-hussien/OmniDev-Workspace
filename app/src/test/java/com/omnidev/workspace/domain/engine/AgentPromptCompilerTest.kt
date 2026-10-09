package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.data.model.ModelTier
import com.omnidev.workspace.data.tools.ToolDefinition
import com.omnidev.workspace.data.tools.ToolParameter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPromptCompilerTest {

    @Test fun `long profile cannot truncate explicit tool focus or the end of a selected skill`() {
        val skill = "Reviewed skill step. ".repeat(700) + "FINAL_SKILL_REQUIREMENT"
        val prompt = AgentPromptCompiler.compile(
            tier = ModelTier.EXECUTOR, scopePath = "/workspace", baseOverride = null,
            workerPersona = null, userContext = "PROFILE ".repeat(1000), memoryContext = null,
            brainContext = null, toolDefinitions = emptyList(), toolAccessMode = "ON_DEMAND",
            enableDeepThinking = false, supportsThinking = true,
            mentionContext = "Prioritize mentioned tool github_manager; discover supporting tools when needed.",
            selectedSkillContext = skill
        )
        assertTrue(prompt.contains(skill))
        assertTrue(prompt.contains("Prioritize mentioned tool github_manager"))
        assertFalse(prompt.contains("PROFILE ".repeat(800)))
        assertTrue(prompt.indexOf("FINAL_SKILL_REQUIREMENT") < prompt.indexOf("USER CONTEXT"))
    }

    @Test fun `complete custom instructions fit independently of selected skill guidance`() {
        val preferences = com.omnidev.workspace.data.model.ProfilePersonalization(
            customInstructions = "C".repeat(2380) + "FINAL_PROFILE_RULE", occupation = "Developer")
        val prompt = AgentPromptCompiler.compile(
            tier = ModelTier.EXECUTOR, scopePath = "/workspace", baseOverride = null,
            workerPersona = null, userContext = preferences.prompt("Name", "B".repeat(900)), memoryContext = null,
            brainContext = null, toolDefinitions = emptyList(), toolAccessMode = "ON_DEMAND",
            enableDeepThinking = false, supportsThinking = true, selectedSkillContext = "SKILL_END_RULE"
        )
        assertTrue(prompt.contains("FINAL_PROFILE_RULE"))
        assertTrue(prompt.contains("SKILL_END_RULE"))
    }

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
