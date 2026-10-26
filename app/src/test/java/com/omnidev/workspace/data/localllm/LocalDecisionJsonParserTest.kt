package com.omnidev.workspace.data.localllm

import com.omnidev.workspace.domain.engine.OmniMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalDecisionJsonParserTest {
    @Test fun contextBudgetShrinksForSmallNativeContexts() {
        assertEquals(144, LocalDecisionMaker.maxInputCharsForContext(512))
        assertEquals(640, LocalDecisionMaker.maxInputCharsForContext(1_024))
        assertEquals(2_048, LocalDecisionMaker.maxInputCharsForContext(4_096))
        assertEquals(0, LocalDecisionMaker.maxInputCharsForContext(-1))
        assertEquals(48, LocalDecisionMaker.outputTokensForContext(512))
        assertEquals(64, LocalDecisionMaker.outputTokensForContext(1_024))
        assertEquals(96, LocalDecisionMaker.outputTokensForContext(4_096))
        assertEquals(0, LocalDecisionMaker.outputTokensForContext(0))
        assertTrue(LocalDecisionMaker.fitsContext(320, 48, 512))
        assertFalse(LocalDecisionMaker.fitsContext(465, 48, 512))
        assertFalse(LocalDecisionMaker.fitsContext(0, 48, 512))
        assertFalse(LocalDecisionMaker.fitsContext(320, 0, 512))
    }

    @Test fun acceptsOnlyTheVersionedClosedSchema() {
        val parsed = LocalDecisionJsonParser.parse(validJson())
        assertEquals(OmniMode.AGENT, parsed?.mode)
        assertTrue(parsed?.needsCode == true)
        assertFalse(parsed?.needsDevice == true)
        assertTrue(parsed?.needsResearch == true)
        assertFalse(parsed?.parallelizable == true)
        assertEquals("One executable code task.", parsed?.reason)
    }

    @Test fun acceptsOneFencedJsonObjectButNothingAroundIt() {
        val fence = String(charArrayOf(96, 96, 96))
        assertEquals(OmniMode.AGENT, LocalDecisionJsonParser.parse(fence + "json\n" + validJson() + "\n" + fence)?.mode)
        assertNull(LocalDecisionJsonParser.parse("Here is the result:\n" + validJson()))
        assertNull(LocalDecisionJsonParser.parse(validJson() + "\nDone."))
        assertNull(LocalDecisionJsonParser.parse(fence + "json\n" + validJson() + "\n" + fence + " trailing"))
    }

    @Test fun rejectsMalformedOrWrongTypedFields() {
        assertNull(LocalDecisionJsonParser.parse("{not-json}"))
        assertNull(LocalDecisionJsonParser.parse(validJson().replace("\"needs_code\": true", "\"needs_code\": \"true\"")))
        assertNull(LocalDecisionJsonParser.parse(validJson().replace("\"schema_version\": 1", "\"schema_version\": 2")))
        assertNull(LocalDecisionJsonParser.parse(validJson().replace("\"schema_version\": 1", "\"schema_version\": \"1\"")))
        assertNull(LocalDecisionJsonParser.parse(validJson().replace("\"mode\": \"AGENT\"", "\"mode\": \"AUTO\"")))
        assertNull(LocalDecisionJsonParser.parse(validJson().replace("\"reason\": \"One executable code task.\"",
            "\"reason\": \"" + "x".repeat(161) + "\"")))
    }

    @Test fun rejectsUnknownKeysEvenWhenTheRestIsValid() {
        val extra = validJson().replace("\"reason\":", "\"approved\":true,\"reason\":")
        assertNull(LocalDecisionJsonParser.parse(extra))
    }

    @Test fun parserDoesNotConfuseModeSuggestionsWithAuthorization() {
        val text = validJson().replace("\"reason\":", "\"permission_granted\":true,\"reason\":")
        assertNull(LocalDecisionJsonParser.parse(text))
    }

    @Test fun rejectsNestedObjectsWhereScalarFieldsAreRequired() {
        val nestedMode = validJson().replace("\"mode\": \"AGENT\"", "\"mode\": {\"value\":\"AGENT\"}")
        val nestedReason = validJson().replace("\"reason\": \"One executable code task.\"", "\"reason\": {\"text\":\"okay\"}")
        assertNull(LocalDecisionJsonParser.parse(nestedMode))
        assertNull(LocalDecisionJsonParser.parse(nestedReason))
    }

    @Test fun rejectsTrailingCommaAndDuplicateKeys() {
        val trailingComma = validJson().replace("\"parallelizable\": false,", "\"parallelizable\": false,")
            .replace("\"reason\": \"One executable code task.\"", "\"reason\": \"One executable code task.\",")
        val duplicateMode = validJson().replace("\"mode\": \"AGENT\",", "\"mode\": \"CHAT\", \"mode\": \"AGENT\",")
        assertNull(LocalDecisionJsonParser.parse(trailingComma))
        // Duplicate object keys must be rejected before parser normalization.
        assertNull(LocalDecisionJsonParser.parse(duplicateMode))
    }

    private fun validJson(mode: String = "AGENT"): String = """
        {
          "schema_version": 1,
          "mode": "$mode",
          "needs_code": true,
          "needs_device": false,
          "needs_research": true,
          "parallelizable": false,
          "reason": "One executable code task."
        }
    """.trimIndent()
}
