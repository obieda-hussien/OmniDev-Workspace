package com.omnidev.workspace.domain.engine

import org.junit.Assert.*
import org.junit.Test

class MentionFocusTest {
    @Test fun `multiple explicit tools and skills preserve exact identities`() {
        val focus = MentionFocus.parse("صلح ده @tool:github_manager @skill:omnidev-quality-gate\n@tool:mcp_docs.read @tool:github_manager")
        assertEquals(setOf("github_manager", "mcp_docs.read"), focus.tools)
        assertEquals(setOf("omnidev-quality-gate"), focus.skills)
        assertFalse(focus.permitsTool("web_search"))
    }

    @Test fun `email handles urls and quoted code do not select capabilities`() {
        val text = "a@tool:foo @obieda https://example/@tool:bar `@skill:test`\n```kotlin\n@tool:delete_file\n```"
        assertFalse(MentionFocus.parse(text).active)
    }

    @Test fun `scope does not leak from a previous turn`() {
        assertTrue(MentionFocus.parse("@tool:github_manager review").active)
        assertFalse(MentionFocus.parse("Explain the result").active)
    }

    @Test fun `skills alone leave tools available`() {
        assertTrue(MentionFocus.parse("@skill:quality-gate check").permitsTool("read_file"))
    }

    @Test fun `disabled and unknown tools cannot be restored by a mention`() {
        try {
            MentionFocus.parse("@tool:delete_file").validateTools(setOf("web_search"))
            fail("Unavailable selection must stop before completion")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun `autocomplete keeps suffix and replaces complete token at caret`() {
        val text = "راجع @tool:git_manager\nوبعدين كمل"
        val query = MentionFocus.query(text, text.indexOf("git") + 3)!!
        assertEquals("tool:git", query.text)
        assertEquals("راجع @tool:github_manager \nوبعدين كمل", text.replaceRange(query.start, query.end, "@tool:github_manager "))
        assertNull(MentionFocus.query("user@example.com", 8))
    }

    @Test fun `removing a chip does not alter another name or quoted example`() {
        assertEquals(" @tool:read_file_lines `@tool:read_file`", MentionFocus.remove(
            "@tool:read_file @tool:read_file_lines `@tool:read_file`", MentionCandidate("tool", "read_file", "")))
    }

    @Test fun `incomplete and oversized selections are rejected`() {
        for (text in listOf("@tool: ", (1..13).joinToString(" ") { "@tool:t$it" }, (1..5).joinToString(" ") { "@skill:s$it" })) {
            try { MentionFocus.parse(text); fail("Expected invalid selection") } catch (_: IllegalArgumentException) { }
        }
    }
}
