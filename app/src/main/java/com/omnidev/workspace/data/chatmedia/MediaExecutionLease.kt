package com.omnidev.workspace.data.chatmedia

/** A UI start and scheduled worker must never submit the same billable request concurrently. */
internal object MediaExecutionLease {
    private val active = mutableSetOf<String>()
    suspend fun <T> run(id: String, execute: suspend () -> T): T? {
        if (!synchronized(active) { active.add(id) }) return null
        return try { execute() } finally { synchronized(active) { active.remove(id) } }
    }
}
