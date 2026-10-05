package com.omnidev.workspace.data.voice

/** Owns one platform speech connection, including close racing with asynchronous initialization. */
internal class SpeechOutputLifecycle<T : Any>(
    private val stopEngine: (T) -> Unit,
    private val shutdownEngine: (T) -> Unit,
    private val onCleanupFailure: (RuntimeException) -> Unit = {}
) {
    private val lock = Any()
    private var engine: T? = null
    private var closed = false
    private var initializationStarted = false

    fun beginInitialization(): Boolean = synchronized(lock) {
        if (closed || initializationStarted) false else { initializationStarted = true; true }
    }

    fun install(candidate: T): Boolean = synchronized(lock) {
        if (closed) { cleanup(candidate); false }
        else { check(engine == null); engine = candidate; true }
    }

    fun current(): T? = synchronized(lock) { engine }

    fun <R> withCurrent(expected: T, block: (T) -> R): R? = synchronized(lock) {
        if (closed || engine !== expected) null else block(expected)
    }

    fun stop() = synchronized(lock) { engine?.let { safeCleanup { stopEngine(it) } } }
    fun stopIfCurrent(expected: T) = synchronized(lock) {
        if (!closed && engine === expected) safeCleanup { stopEngine(expected) }
    }

    fun close() = synchronized(lock) {
        if (closed) return@synchronized
        closed = true
        val owned = engine
        engine = null // Detach before Android unbind; repeated or reentrant close cannot release it again.
        if (owned != null) cleanup(owned)
    }

    private fun cleanup(owned: T) {
        safeCleanup { stopEngine(owned) }
        safeCleanup { shutdownEngine(owned) }
    }

    private inline fun safeCleanup(block: () -> Unit) {
        try { block() }
        catch (error: IllegalArgumentException) { onCleanupFailure(error) }
        catch (error: IllegalStateException) { onCleanupFailure(error) }
    }
}
