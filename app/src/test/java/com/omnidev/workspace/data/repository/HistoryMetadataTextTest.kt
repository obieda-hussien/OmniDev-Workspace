package com.omnidev.workspace.data.repository

import org.junit.Assert.*
import org.junit.Test

class HistoryMetadataTextTest {
    @Test fun prefersOriginalComposerOverExpandedContext() {
        assertEquals("Fix Gradle", HistoryMetadataText.original("private source excerpt", """{"userInput":"Fix Gradle"}"""))
    }
    @Test fun scanBudgetDoesNotFallBackToQuotedContext() {
        val metadata = "{\"context\":\"" + "x".repeat(300000) + "\",\"userInput\":\"real input\"}"
        assertEquals("", HistoryMetadataText.original("Quoted private reply", metadata))
    }
    @Test fun clippingPreservesSurrogateBoundaries() {
        assertEquals(3071, HistoryMetadataText.original("x".repeat(3071) + "😀tail", "{}").length)
    }
    @Test fun malformedLegacyOrAbsentMetadataKeepsBoundedUserText() {
        assertEquals("hello", HistoryMetadataText.original("hello", "{broken"))
        assertEquals("hello", HistoryMetadataText.original("hello", "{}"))
        assertEquals(3072, HistoryMetadataText.original("x".repeat(10000), "").length)
    }
}
