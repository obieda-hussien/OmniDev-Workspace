package com.omnidev.workspace.ui.assistant

import com.omnidev.workspace.data.routines.*

import org.junit.Assert.*
import org.junit.Test

/** Compiles and executes the real Kotlin Regex on Android's ICU-backed Pattern implementation. */
class RoutineMatcherAndroidTest {
    @Test fun initializationAndBracedSlotsWorkOnAndroid() {
        assertEquals(setOf("query"), RoutineMatcher.variables("Search {{query}}"))
        assertEquals("Search عبيدة & café", RoutineMatcher.bind("Search {{query}}", mapOf("query" to "عبيدة & café")))
        assertTrue(RoutineMatcher.variables("{{9bad}} {{bad-name}} {single}").isEmpty())
    }
    @Test fun exactParameterizedAliasKeepsLiteralRegexCharacters() {
        val routine = LearnedRoutine("r", "Search", listOf("Search [{{query}}]."), emptyList(), enabled = true)
        assertEquals("hello+world", RoutineMatcher.match("Search [hello+world].", listOf(routine))?.second?.get("query"))
        assertNull(RoutineMatcher.match("Search hello+world", listOf(routine)))
    }
}
