package com.omnidev.workspace.domain.engine

import org.junit.Assert.*
import org.junit.Test

class AgentDraftPolicyTest {
    @Test fun `fragmented JSON proposals and fenced drafts never stream as executed actions`() {
        val s = AgentDraftStream()
        assertEquals("", s.accept(" \n"))
        assertEquals("", s.accept("{"))
        assertEquals("", s.accept("\"omni_operation\":{} } Done, payment saved"))
        assertEquals("", AgentDraftStream().accept("```json\n{}"))
    }
    @Test fun `normal prose remains intact across chunks`() {
        val s = AgentDraftStream()
        assertEquals("", s.accept(" \n"))
        assertEquals(" \nChecking", s.accept("Checking"))
        assertEquals(" the page", s.accept(" the page"))
    }
    @Test fun `checkpoint drops mixed tool prose and redacts ordinary interrupted drafts`() {
        val draft = """{"omni_operation":{"intent":"headless_browser","arguments":{"action":"screenshot"}}} Page opened and payment saved"""
        val kept = AgentDraftPolicy.checkpointDraft(draft)
        assertFalse(kept.contains("payment saved")); assertFalse(kept.contains("headless_browser"))
        assertTrue(kept.contains("unexecuted"))
        assertFalse(AgentDraftPolicy.checkpointDraft("token=secretvalue").contains("secretvalue"))
        assertTrue(AgentDraftPolicy.checkpointDraft("x".repeat(20_000)).length <= 4_000)
    }
}
