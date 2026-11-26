package com.omnidev.workspace.data.routines

import com.omnidev.workspace.data.model.ToolCall
import com.omnidev.workspace.data.model.ToolCallResult
import com.omnidev.workspace.data.tools.ToolExecutionResult
import com.omnidev.workspace.domain.engine.RunEvidenceLedger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutineVideoObservationTest {
    // A 1 × 1 grayscale JPEG.
    private val jpeg = "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4n" +
        "ICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/wAALCAABAAEBAREA/8QAFAABAAAAAAAAAAAAAAAAAAAAAP/EABQQAQAAAAAAAAAAAAAAAAAAAAD/2gAIAQEAAD8AP//Z"

    private fun frame(payload: String) = ToolExecutionResult(
        "[SCREENSHOT_BASE64]\ndata:image/jpeg;base64,$payload\n[/SCREENSHOT_BASE64]\nSparse sample"
    )

    @Test fun imageIsAttachedAndRemovedFromText() {
        val sample = RoutineVideoObservation.extract(frame(jpeg), true)
        assertEquals(jpeg, sample.image?.base64Data)
        assertEquals(159L, sample.image?.sizeBytes)
        assertFalse(sample.result.isError)
        assertFalse(sample.result.output.contains(jpeg))
        assertTrue(sample.result.output.contains("Sparse sample"))
    }

    @Test fun nonVisionModelGetsAnExplicitLimitAndNoSuccessEvidence() {
        val sample = RoutineVideoObservation.extract(frame(jpeg), false)
        assertNull(sample.image)
        assertEquals("MODEL_VISION_UNAVAILABLE", sample.result.classification)
        assertTrue(sample.result.output.contains("cannot view images"))
        assertFalse(sample.result.output.contains(jpeg))
        assertRecordedAsFailure(sample)
    }

    @Test fun excessivePayloadDoesNotEnterImageOrTextContext() {
        val sample = RoutineVideoObservation.extract(frame("A".repeat(600_001)), true)
        assertNull(sample.image)
        assertTrue(sample.result.output.length < 150)
        assertRecordedAsFailure(sample)
    }

    @Test fun missingAndMalformedPayloadsDoNotBecomeEvidence() {
        val invalidResults = listOf(
            ToolExecutionResult("Video duration is two minutes"),
            frame("A==="),
            frame("YWJjZA==")
        )
        invalidResults.forEach { result ->
            val sample = RoutineVideoObservation.extract(result, true)
            assertNull(sample.image)
            assertEquals("ATTACHMENT_CONTENT_UNAVAILABLE", sample.result.classification)
            assertRecordedAsFailure(sample)
        }
    }

    @Test fun originalToolFailureKeepsItsClassification() {
        val result = ToolExecutionResult("Frame not found", isError = true, classification = "PERMISSION_DENIED")
        assertEquals(result, RoutineVideoObservation.extract(result, true).result)
    }

    private fun assertRecordedAsFailure(sample: RoutineVideoObservation.Sample) {
        val call = ToolCall("frame-1", "learned_routine", mapOf("action" to "video_frame", "index" to "0"))
        val ledger = RunEvidenceLedger()
        val result = sample.result
        ledger.record(call, ToolCallResult(call.id, call.name, result.output, result.isError), result.classification)
        assertEquals(RunEvidenceLedger.Outcome.ERROR, ledger.snapshot().single().outcome)
    }
}
