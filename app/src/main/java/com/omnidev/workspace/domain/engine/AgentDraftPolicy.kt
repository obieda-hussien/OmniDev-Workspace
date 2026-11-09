package com.omnidev.workspace.domain.engine

/** Provider drafts are not tool observations. Never carry protocol payloads into a recovery claim. */
object AgentDraftPolicy {
    fun checkpointDraft(raw: String): String {
        if (raw.contains("\"omni_operation\"") || raw.contains("\"omni_tool_call\""))
            return "Omitted an unexecuted tool proposal. No operation in that draft is completion evidence."
        return SensitiveObservationRedactor.redact(raw).take(4_000)
    }
}

/** Hide JSON/fenced provider drafts until the runtime validates the complete response. Bounded prefix only. */
class AgentDraftStream {
    private var prefix = ""
    private var decided = false
    private var hidden = false

    fun accept(chunk: String): String {
        if (decided) return if (hidden) "" else chunk
        val combined = prefix + chunk
        prefix = combined.take(64)
        val first = combined.trimStart().firstOrNull() ?: return ""
        hidden = first == '{' || first == '[' || first == '`'
        decided = true
        prefix = ""
        return if (hidden) "" else combined
    }
}
