package com.omnidev.workspace.data.tools

object ContextCompressionTool {
    fun definition() = ToolDefinition("compress_context",
        "Locally compact completed tool observations in this run without a model call. Original chat and tool logs remain available. Keeps call/result pairs, user instructions and error status; excerpts are partial evidence, not a full summary.")
    fun result() = ToolExecutionResult("Local context compaction requested. Older tool observations will use partial excerpts; original logs and chat history remain available. No model tokens spent on compaction.")
}
