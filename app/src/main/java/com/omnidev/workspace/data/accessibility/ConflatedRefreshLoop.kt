package com.omnidev.workspace.data.accessibility

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One refresh in flight and at most one pending refresh, even during continuous event storms. */
internal class ConflatedRefreshLoop(
    scope: CoroutineScope,
    private val intervalMs: Long = 500,
    refresh: suspend () -> Unit
) {
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val job = scope.launch {
        for (request in requests) {
            refresh()
            // Throttle after completion, rather than debounce: continuous events cannot starve us.
            delay(intervalMs)
        }
    }

    fun request() { requests.trySend(Unit) }
    fun close() { requests.cancel(); job.cancel() }
}
