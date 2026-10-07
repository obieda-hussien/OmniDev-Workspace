package com.omnidev.workspace.data.chatmedia

import com.omnidev.workspace.data.model.AttachmentMeta
import com.omnidev.workspace.data.model.ChatMessage
import com.omnidev.workspace.data.model.MessageRole
import kotlinx.serialization.json.*

/** Shared delivery contract for Chat/Agent results and Team worker results. */
internal object MediaToolResult {
    suspend fun message(toolName: String, output: String, isError: Boolean,
        existingUris: () -> Set<String>, resolve: suspend (String, String?) -> AttachmentMeta?): ChatMessage? {
        if (toolName != "media_generation" || isError) return null
        val start = output.indexOf('{')
        if (start < 0) return null
        val payload = runCatching { Json.parseToJsonElement(output.substring(start)).jsonObject }.getOrNull() ?: return null
        val references = payload["attachments"] as? JsonArray ?: return null
        val media = references.take(10).mapNotNull { item ->
            val reference = item as? JsonObject ?: return@mapNotNull null
            val uri = (reference["uri"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val name = (reference["file_name"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            resolve(uri, name)
        }.distinctBy { it.uri }
        // Read after resolution so another worker's result cannot introduce the same card twice.
        val known = existingUris()
        val fresh = media.filterNot { it.uri in known }
        if (fresh.isEmpty()) return null
        val status = (payload["status"] as? JsonPrimitive)?.contentOrNull
        return ChatMessage(MessageRole.ASSISTANT, if (status == "attached") "File attached." else "Media generation", attachments = fresh)
    }
}
