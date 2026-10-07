package com.omnidev.workspace.domain.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select

/** Owned by one run, never shared between sessions. Every consumer sees every revision. */
class RunSteering {
    data class Update(val revision: Long, val text: String)
    private val updates = MutableStateFlow<List<Update>>(emptyList())
    private var finished = false
    val revision: Long get() = updates.value.lastOrNull()?.revision ?: 0L

    @Synchronized
    fun submit(text: String): Update {
        check(!finished) { "The run has just finished. Send this as a new message." }
        require(text.isNotBlank()) { "Write a correction or additional instruction." }
        require(text.length <= 16_000) { "Keep each live instruction within 16,000 characters." }
        require(updates.value.size < 64) { "This run has reached its live instruction limit." }
        require(updates.value.sumOf { it.text.length } + text.length <= 64_000) {
            "This run has reached its live instruction context limit. Finish it before starting another task."
        }
        val update = Update(revision + 1, text.trim())
        updates.value = updates.value + update
        return update
    }

    fun after(revision: Long): List<Update> = updates.value.filter { it.revision > revision }
    fun changed(revision: Long): Boolean = this.revision != revision
    fun check(revision: Long) { if (changed(revision)) throw RunRedirected() }

    @Synchronized
    fun finish(revision: Long): Boolean {
        if (changed(revision)) return false
        finished = true
        return true
    }

    fun objective(original: String, revision: Long = this.revision): String = buildString {
        append(original)
        updates.value.filter { it.revision <= revision }.forEach {
            append("\n\nUser follow-up #${it.revision}:\n").append(it.text)
        }
        if (revision > 0) append("\n\nContinue the same task. Later user corrections supersede conflicting earlier instructions. " +
            "Keep completed work that is still relevant; verify existing state before repeating any action. " +
            "Re-plan remaining work. Do not treat partial drafts as completed work.")
    }

    /** Interrupt model calls/retry waits only. In-flight tools settle under their own timeouts. */
    suspend fun <T> reasoning(revision: Long, block: suspend () -> T): T = coroutineScope {
        check(revision)
        val work = async { block() }
        val redirect = async { updates.first { (it.lastOrNull()?.revision ?: 0L) != revision } }
        try {
            val result = select<T> {
                redirect.onAwait { throw RunRedirected() }
                work.onAwait { it }
            }
            check(revision)
            result
        } finally {
            work.cancel()
            redirect.cancel()
        }
    }
}

/** A control signal, not an API error, tool failure or user Stop. */
internal class RunRedirected : CancellationException("User redirected the active run")
