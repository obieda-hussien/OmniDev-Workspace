package com.omnidev.workspace.ui.chat

import com.omnidev.workspace.data.model.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionTranscriptExporterTest {
    private val first = SessionTranscriptTurn(MessageRole.USER, "أول سؤال", 1000L,
        attachmentNames = listOf("photo.png"))
    private val second = SessionTranscriptTurn(MessageRole.ASSISTANT, "الرد النهائي", 1000L,
        consoleEntries = listOf(
            AgentConsoleEntry.PhaseEntry("Analyze", timestamp = 1001L),
            AgentConsoleEntry.ToolEntry("search", "api_key=very-private", 1,
                "api_key=very-private", timestamp = 1002L),
            AgentConsoleEntry.ResultEntry("search", "found", false,
                fullOutput = "full tool output", timestamp = 1003L),
            AgentConsoleEntry.ReplyEntry(timestamp = 1004L)
        ))

    @Test fun exportsEveryTurnIncludingConsoleBeforeItsAiReply() {
        val snapshot = SessionTranscriptSnapshot("Season A", 500L, listOf(first, second,
            SessionTranscriptTurn(MessageRole.USER, "آخر رسالة", 1000L)))
        val out = SessionTranscriptExporter.render(snapshot)
        assertEquals(3, Regex("========== [123]\\. ").findAll(out).count())
        assertTrue(out.indexOf("أول سؤال") < out.indexOf("RUN    search"))
        assertTrue(out.indexOf("RUN    search") < out.indexOf("AI RESPONSE"))
        assertTrue(out.indexOf("AI RESPONSE") < out.indexOf("الرد النهائي"))
        assertTrue(out.indexOf("الرد النهائي") < out.indexOf("آخر رسالة"))
        assertTrue(out.contains("full tool output"))
        assertTrue(out.contains("photo.png"))
        assertFalse(out.contains("very-private"))
        assertTrue(out.contains("[REDACTED]"))
    }

    @Test fun optionalConsoleToggleDoesNotRemoveAnswersOrAttachments() {
        val out = SessionTranscriptExporter.render(
            SessionTranscriptSnapshot("Title", 0L, listOf(first, second)), includeConsole = false)
        assertTrue(out.contains("أول سؤال"))
        assertTrue(out.contains("الرد النهائي"))
        assertTrue(out.contains("photo.png"))
        assertFalse(out.contains("AGENT CONSOLE"))
        assertFalse(out.contains("RUN    search"))
    }

    @Test fun markdownUsesFenceLongerThanEmbeddedToolOutput() {
        val turn = second.copy(consoleEntries = listOf(
            AgentConsoleEntry.ResultEntry("shell", "short", false,
                fullOutput = "before ```` after", timestamp = 1000L)))
        val out = SessionTranscriptExporter.render(
            SessionTranscriptSnapshot("Markdown", 0L, listOf(turn)),
            format = SessionExportFormat.MARKDOWN)
        assertTrue(out.contains("# Markdown"))
        assertTrue(out.contains("## 1. Omni AI"))
        assertTrue(out.contains("`````text"))
        assertTrue(out.contains("before ```` after"))
        assertTrue(out.contains("**AI response**"))
    }

    @Test fun doesNotLimitConsoleToVisibleWindowOrLastTurn() {
        val events = (1..250).map {
            AgentConsoleEntry.ToolEntry("tool$it", "arg=$it", it, timestamp = it.toLong())
        }
        val out = SessionTranscriptExporter.render(
            SessionTranscriptSnapshot("Long", 0L, listOf(
                SessionTranscriptTurn(MessageRole.ASSISTANT, "first", 0L, consoleEntries = events),
                SessionTranscriptTurn(MessageRole.ASSISTANT, "last", 1L, consoleEntries = events)
            )))
        assertEquals(2, Regex("RUN    tool250\\(").findAll(out).count())
        assertTrue(out.indexOf("first") < out.lastIndexOf("RUN    tool250"))
        assertTrue(out.contains("last"))
    }

    @Test fun missingSavedConsoleIsLabeledInsteadOfSilentlyDropped() {
        val out = SessionTranscriptExporter.render(
            SessionTranscriptSnapshot("Broken", 0L, listOf(second.copy(consoleEntries = emptyList(),
                consoleUnavailable = true))))
        assertTrue(out.contains("saved console data could not be decoded"))
    }
}
