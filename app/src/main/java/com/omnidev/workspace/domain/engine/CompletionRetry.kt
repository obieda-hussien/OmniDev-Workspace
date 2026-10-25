package com.omnidev.workspace.domain.engine

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Retries only the current completion. Tool dispatch and run history stay outside this boundary. */
internal object CompletionRetry {
    suspend fun <T> run(onRetry: suspend (Int) -> Unit = {}, block: suspend () -> T): T {
        for (attempt in 0..2) {
            try { return block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (exhausted: com.omnidev.workspace.data.network.AutoRoutingExhaustedException) { throw exhausted }
            catch (error: IOException) {
                val message = error.message.orEmpty()
                val transient = Regex("\\b(500|502|503|504)\\b").containsMatchIn(message) ||
                    error is java.net.SocketTimeoutException || error is java.net.ConnectException
                if (!transient || attempt == 2) throw error
                onRetry(attempt + 1)
                delay(1_000L shl attempt)
            }
        }
        error("Unreachable")
    }
}
