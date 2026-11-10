package com.omnidev.workspace.domain.engine

import org.junit.Assert.*
import org.junit.Test

class ToolTypoAdvisorTest {
    @Test fun `unique close registered name is suggested but not executed`() {
        assertEquals("github_manager", ToolTypoAdvisor.suggest("github_managr", listOf("github_manager", "agent_runtime")))
        assertEquals("repo", ToolTypoAdvisor.suggest("reop", listOf("repo", "action")))
    }
    @Test fun `ambiguous or distant names do not guess`() {
        assertNull(ToolTypoAdvisor.suggest("git_rea", listOf("git_read", "git_real")))
        assertNull(ToolTypoAdvisor.suggest("dangerous_unknown_tool", listOf("github_manager", "agent_runtime")))
        assertNull(ToolTypoAdvisor.suggest("", listOf("github_manager")))
    }
}
