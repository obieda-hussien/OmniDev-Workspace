package com.omnidev.workspace.ui.chat

import java.util.concurrent.atomic.AtomicLong

/**
 * Represents a single entry in the Agent Live Console ("The Glass Brain").
 *
 * Each entry corresponds to a significant event emitted by [AgentPipeline] during
 * the ReAct loop — reasoning, tool execution, tool results, token tracking, and errors.
 * These drive the [AgentLiveConsole] streaming terminal UI.
 */
sealed class AgentConsoleEntry {

    abstract val timestamp: Long

    /**
     * Globally unique, monotonically increasing identifier assigned at construction time.
     * Used as the stable key for [LazyColumn] items to prevent duplicate-key crashes
     * when two entries are created within the same millisecond.
     */
    abstract val id: Long

    companion object {
        private val idCounter = AtomicLong(0)
        fun nextId(): Long = idCounter.incrementAndGet()
    }

    /** Agent is reasoning: preparing a request for the model (ReAct loop iteration N). */
    data class ThinkingEntry(
        val iteration: Int,
        override val timestamp: Long = System.currentTimeMillis(),
        override val id: Long = nextId()
    ) : AgentConsoleEntry()

    /** Extended thinking / chain-of-thought block received from the model. */
    data class DeepThinkingEntry(
        val snippet: String,
        override val timestamp: Long = System.currentTimeMillis(),
        override val id: Long = nextId()
    ) : AgentConsoleEntry()

    /** The agent is invoking a tool with the given parameters. */
    data class ToolEntry(
        val toolName: String,
        val params: String,
        val iteration: Int,
        val fullParams: String = params,
        override val timestamp: Long = System.currentTimeMillis(),
        override val id: Long = nextId()
    ) : AgentConsoleEntry()

    /** A tool finished executing; includes a snippet of its output. */
    data class ResultEntry(
        val toolName: String,
        val snippet: String,
        val isError: Boolean,
        val fullOutput: String = snippet,
        val durationMs: Long = 0L,
        override val timestamp: Long = System.currentTimeMillis(),
        override val id: Long = nextId()
    ) : AgentConsoleEntry()

    /** Token usage update from the last API call. */
    data class TokenEntry(
        val totalTokens: Int,
        val budget: Int?,
        override val timestamp: Long = System.currentTimeMillis(),
        override val id: Long = nextId()
    ) : AgentConsoleEntry()

    /** The agent has finished its ReAct loop and is generating the final reply. */
    data class ReplyEntry(
        override val timestamp: Long = System.currentTimeMillis(),
        override val id: Long = nextId()
    ) : AgentConsoleEntry()

    /** An unrecoverable error occurred in the pipeline. */
    data class ErrorEntry(
        val message: String,
        override val timestamp: Long = System.currentTimeMillis(),
        override val id: Long = nextId()
    ) : AgentConsoleEntry()
}
