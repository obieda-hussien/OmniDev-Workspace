package com.omnidev.workspace.data.tools

import java.io.Closeable
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** One in-flight system probe, no queue. Cancellation never frees a still-blocked worker. */
internal class BoundedRuntimeProbe : Closeable {
    private val worker = ThreadPoolExecutor(0, 1, 30L, TimeUnit.SECONDS, SynchronousQueue<Runnable>()) { task ->
        Thread(task, "OmniRuntimeProbe").apply { isDaemon = true }
    }

    suspend fun check(operation: () -> Boolean): Boolean = suspendCancellableCoroutine { continuation ->
        if (!continuation.isActive) return@suspendCancellableCoroutine
        try {
            worker.execute {
                try {
                    val ready = operation()
                    if (continuation.isActive) continuation.resume(ready)
                } catch (error: Throwable) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        } catch (_: RejectedExecutionException) {
            continuation.resume(false)
        }
        // Do not cancel/interrupt the worker on timeout: synchronous Binder may ignore it.
        // Keeping its slot occupied bounds unfinished work; late results cannot resume callers.
    }

    override fun close() {
        worker.shutdownNow()
    }
}
