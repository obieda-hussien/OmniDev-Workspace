package com.omnidev.workspace.data.network

import com.omnidev.workspace.data.db.dao.ChatSessionDao
import com.omnidev.workspace.data.db.entities.AutoModelPinEntity
import com.omnidev.workspace.data.model.CompletionRequest
import com.omnidev.workspace.data.model.CompletionResponse
import com.omnidev.workspace.domain.engine.WholeResponseEnvelope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import com.omnidev.workspace.data.model.ModelRole
import java.io.IOException

/** Independent scopes keep parallel workers from inheriting each other's model. */
data class AutoModelPinScope(val sessionId: Long, val key: String) {
    init { require(sessionId > 0 && key.isNotBlank() && key.length <= 200) }

    companion object {
        fun forSession(sessionId: Long?, role: ModelRole?, workerRunId: String? = null): AutoModelPinScope? {
            if (sessionId == null || sessionId <= 0 || role == null) return null
            val workerKey = workerRunId?.let { id ->
                java.security.MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
            }
            return AutoModelPinScope(sessionId, role.name + (workerKey?.let { ":worker:$it" } ?: ""))
        }
    }
}

interface AutoModelPinStore {
    suspend fun read(scope: AutoModelPinScope): String?
    /** Atomically returns the first committed model. Never replaces an existing pin. */
    suspend fun claim(scope: AutoModelPinScope, modelId: String): String
}

class RoomAutoModelPinStore(private val dao: ChatSessionDao) : AutoModelPinStore {
    override suspend fun read(scope: AutoModelPinScope): String? = dao.autoModelPin(scope.sessionId, scope.key)
    override suspend fun claim(scope: AutoModelPinScope, modelId: String): String =
        dao.claimAutoModelPin(AutoModelPinEntity(scope.sessionId, scope.key, modelId))
}

/** Resolve only trustworthy provenance. Tool IDs alone are neither unique nor durable. */
internal object AutoModelAffinity {
    fun requiredModel(request: CompletionRequest, persisted: String?): String? {
        val allCalls = request.messages.flatMap { it.toolCalls }
        val nativeCalls = allCalls.filterNot { it.textProtocol }
        val callIds = allCalls.mapTo(hashSetOf()) { it.id }
        val orphanResults = request.messages.any { message -> message.toolResults.any { it.toolCallId !in callIds } }
        val owners = allCalls.mapNotNull { it.originModelId?.takeIf(String::isNotBlank) }.toSet()
        if (owners.size > 1 || persisted != null && owners.any { it != persisted })
            throw IOException("Auto tool history has conflicting model identities. Start a new conversation or restore the original model.")
        val owner = persisted ?: owners.singleOrNull()
        if (persisted == null && (nativeCalls.any { it.originModelId.isNullOrBlank() } || orphanResults))
            throw IOException("Auto cannot identify the original model for existing tool history. Start a new conversation or explicitly select the original model.")
        return owner
    }

    /** Text proposals also lead to tool dispatch in Agent mode, after the provider returns. */
    fun proposesTools(request: CompletionRequest, response: CompletionResponse): Boolean {
        if (response.toolCalls.isNotEmpty()) return true
        if (request.autoRouteRole == ModelRole.CHAT || request.autoRouteRole == null) return false
        val root = runCatching { Json.parseToJsonElement(WholeResponseEnvelope.normalize(response.content)) as? JsonObject }.getOrNull()
        return root?.keys == setOf("omni_tool_call") || root?.keys == setOf("omni_operation")
    }

    fun select(required: String?, eligibleIds: List<String>, preferred: String): String {
        if (required != null) {
            if (required !in eligibleIds) throw IOException("Pinned Auto model $required is unavailable or not permitted by the current Auto policy. Restore its access, or start a new conversation.")
            return required
        }
        return preferred.takeIf { it in eligibleIds } ?: eligibleIds.firstOrNull()
            ?: throw IOException("Auto has no eligible connected model.")
    }
}
