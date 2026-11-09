package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.data.tools.ToolDefinition
import org.junit.Assert.*
import org.junit.Test

class ExecutionEnvironmentContextTest {
    @Test fun `remote identity preserves punctuation and latest user repository`() {
        assertEquals("owner/repo-", ExecutionEnvironmentContext.remoteRepository("Inspect https://github.com/owner/repo-/tree/main"))
        assertEquals("new/project", ExecutionEnvironmentContext.remoteRepository("Previous https://github.com/old/project\nInspect GitHub new/project"))
        assertEquals("obieda-hussien/OmniDev-Workspace", ExecutionEnvironmentContext.remoteRepository("GOAL Inspect repository | INPUTS obieda-hussien/OmniDev-Workspace | OUTPUT HEAD"))
    }
    @Test fun `local directories are not invented GitHub identities`() {
        for (s in listOf("Inspect repository /storage/emulated/0/Android/Omni", "Inspect repository src/main", "Read repo app/src", "Inspect repo docs/README.md"))
            assertNull(s, ExecutionEnvironmentContext.remoteRepository(s))
    }
    @Test fun `remote capability is visible under a small surface budget without granting permission`() {
        val objective = "Inspect GitHub owner/project using git status and source files"
        val unrelated = (1..90).map { ToolDefinition("shell_$it", "git status source files") }
        val gh = ToolDefinition("github_manager", "Manage remote repositories")
        val c = RunToolCatalog(unrelated + gh, objective, budget = ToolSurfaceBudget(4, 2_500))
        assertTrue(c.definitions().any { it.name == gh.name })
        assertTrue(c.definitions().size <= 5)
        val defaultSurface = RunToolCatalog(unrelated + gh, objective)
        assertTrue(defaultSurface.definitions().size <= 8)
        assertTrue(defaultSurface.ensureLoaded("shell_90"))
        assertFalse(RunToolCatalog(unrelated, objective).isPermitted("github_manager"))
        assertTrue(ExecutionEnvironmentContext.guidance(objective).contains("not a verified checkout"))
    }
    @Test fun `normalized missing git output activates local recovery and prioritizes developer tools`() {
        val result = com.omnidev.workspace.data.tools.MissingGitCapability.classify("git status", 127, "", "/system/bin/sh: git: not found")!!
        val observation = "run_terminal failed: " + result.output
        assertTrue(ExecutionEnvironmentContext.missingGit(observation))
        assertTrue(ExecutionEnvironmentContext.guidance("Inspect local project", observation).contains("no git executable"))
        val definitions = (1..80).map { ToolDefinition("noise_$it", "local project inspection") } +
            listOf(ToolDefinition("git_manager", "Repository operations"), ToolDefinition("agent_runtime", "Developer environment"))
        val c = RunToolCatalog(definitions, "Inspect local project", budget = ToolSurfaceBudget(4, 2_500))
        c.prepare("Inspect local project", observation)
        assertEquals(listOf("git_manager", "agent_runtime"), c.definitions().drop(1).take(2).map { it.name })
    }
    @Test fun `missing git points at developer capabilities rather than root or an absent repository`() {
        val observation = "run_terminal failed: exit=127 /system/bin/sh: git: inaccessible or not found"
        assertTrue(ExecutionEnvironmentContext.missingGit(observation))
        assertEquals(listOf("git_manager", "agent_runtime"), ExecutionEnvironmentContext.priorityNames("Inspect local project", observation))
        assertFalse(ExecutionEnvironmentContext.missingGit("git status: fatal not a git repository"))
    }
}
