package com.omnidev.workspace.data.network

import com.omnidev.workspace.data.model.*
import com.omnidev.workspace.domain.engine.completion.ToolCallIdentity
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class AutoModelAffinityTest {
    private val first = "GEMINI::gemini-flash"
    private val second = "OPENAI::gpt-4o-mini"
    private fun request(vararg history: ChatMessage) = CompletionRequest(
        modelId = second, messages = history.toList(), autoRouteRole = ModelRole.AGENT
    )
    private fun call(owner: String?, id: String = "call_1") =
        ToolCall(id, "read_file", mapOf("path" to "test"), originModelId = owner)
    private fun assistant(vararg calls: ToolCall) =
        ChatMessage(MessageRole.ASSISTANT, "", toolCalls = calls.toList())

    @Test fun freshConversationCanSelectAnyEligibleModel() {
        val req = request(ChatMessage(MessageRole.USER, "Hello"))
        assertNull(AutoModelAffinity.requiredModel(req, null))
        assertEquals(second, AutoModelAffinity.select(null, listOf(first, second), second))
    }

    @Test fun restoredPinWinsEvenWhenHistoryContainsOnlySavedText() {
        val req = request(ChatMessage(MessageRole.ASSISTANT, "Earlier results"))
        val owner = AutoModelAffinity.requiredModel(req, first)
        assertEquals(first, AutoModelAffinity.select(owner, listOf(second, first), second))
    }

    @Test fun unavailablePinDoesNotSilentlySelectFallback() {
        val failure = runCatching { AutoModelAffinity.select(first, listOf(second), second) }.exceptionOrNull()
        assertTrue(failure is java.io.IOException)
        assertTrue(failure!!.message.orEmpty().contains("Pinned Auto model"))
    }

    @Test fun identicalProviderCallIdsDoNotCrossContaminateIndependentRequests() {
        assertEquals(first, AutoModelAffinity.requiredModel(request(assistant(call(first))), null))
        assertEquals(second, AutoModelAffinity.requiredModel(request(assistant(call(second))), null))
    }

    @Test fun stabilizedCallIdAndSerializationPreserveModelProvenance() {
        val tool = ToolCallIdentity("run").stabilize(listOf(call(first, ""))).single()
        assertTrue(tool.id.isNotBlank())
        val encoded = Json.encodeToString(ToolCall.serializer(), tool)
        val restored = Json.decodeFromString(ToolCall.serializer(), encoded)
        assertEquals(first, AutoModelAffinity.requiredModel(request(assistant(restored)), null))
    }

    @Test fun conflictingNativeHistoryOrStoredIdentityFailsClosed() {
        assertTrue(runCatching { AutoModelAffinity.requiredModel(request(assistant(call(first), call(second))), null) }.isFailure)
        assertTrue(runCatching { AutoModelAffinity.requiredModel(request(assistant(call(second))), first) }.isFailure)
        assertTrue(runCatching { AutoModelAffinity.requiredModel(request(assistant(call(null))), null) }.isFailure)
    }

    @Test fun toolResultsRequireProvenanceWhenOriginalCallsAreMissing() {
        val req = request(ChatMessage(MessageRole.TOOL, "result", toolResults =
            listOf(ToolCallResult("orphan", "read_file", "result"))))
        assertTrue(runCatching { AutoModelAffinity.requiredModel(req, null) }.isFailure)
    }

    @Test fun textEnvelopeAndNativeProposalsAreRecognizedBeforeDispatch() {
        val req = request()
        assertTrue(AutoModelAffinity.proposesTools(req, CompletionResponse("", listOf(call(first)))))
        assertTrue(AutoModelAffinity.proposesTools(req, CompletionResponse("""{"omni_tool_call":{"name":"read_file","arguments":{"path":"a"}}}""")))
        assertTrue(AutoModelAffinity.proposesTools(req, CompletionResponse("""{"omni_operation":{"intent":"read file","arguments":{"path":"a"}}}""")))
        assertFalse(AutoModelAffinity.proposesTools(req, CompletionResponse("plain answer")))
    }

    @Test fun sessionAndWorkerScopesAreIndependentAndNotSentInRequests() {
        val chat = AutoModelPinScope.forSession(1L, ModelRole.CHAT)!!
        val agent = AutoModelPinScope.forSession(1L, ModelRole.AGENT)!!
        val worker1 = AutoModelPinScope.forSession(1L, ModelRole.SWARM_WORKER, "worker-1")!!
        val worker2 = AutoModelPinScope.forSession(1L, ModelRole.SWARM_WORKER, "worker-2")!!
        assertEquals(4, setOf(chat, agent, worker1, worker2).size)
        assertNotEquals(chat, AutoModelPinScope.forSession(2L, ModelRole.CHAT))
        assertNull(AutoModelPinScope.forSession(null, ModelRole.CHAT))
        val encoded = Json.encodeToString(CompletionRequest.serializer(), request().copy(autoRoutePinScope = worker1))
        assertFalse(encoded.contains("autoRoutePinScope"))
        assertFalse(encoded.contains(worker1.key))
    }
}
