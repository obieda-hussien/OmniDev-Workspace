package com.omnidev.workspace.domain.engine

import org.junit.Assert.*
import org.junit.Test

class FinalAnswerSanitizerTest {
    @Test fun `removes tool envelopes and run log lines`() {
        val raw = "تمام\n[+120ms] PHASE  ANALYZE — x\n```json\n{\"omni_tool_call\":{\"name\":\"a\",\"arguments\":{}}}\n```\nbefore {\"omni_tool_call\":{\"name\":\"b\",\"arguments\":{\"s\":\"}\"}}} after"
        val clean = FinalAnswerSanitizer.clean(raw)
        assertFalse(clean.contains("omni_tool_call"))
        assertFalse(clean.contains("PHASE"))
        assertTrue(clean.contains("before") && clean.contains("after"))
    }

    @Test fun `closes unbalanced fences and strips hebrew and thai letters`() {
        val clean = FinalAnswerSanitizer.clean("النتيجה \u05D1\u05E6\u05D5\u05E8\u05D4 تمام\nكود:\n```kotlin\nval x = 1")
        assertFalse(clean.any { it in '\u0590'..'\u05FF' })
        assertTrue(clean.trimEnd().endsWith("```"))
        assertTrue(clean.contains("\n\n```kotlin"))
    }
}
