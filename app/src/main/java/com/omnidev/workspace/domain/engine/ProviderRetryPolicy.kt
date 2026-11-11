package com.omnidev.workspace.domain.engine

import java.net.UnknownHostException

/** Name resolution failure is not helped by repeating the same LLM request. */
internal object ProviderRetryPolicy {
    fun isDnsUnavailable(error: Throwable): Boolean {
        var current: Throwable? = error
        repeat(8) {
            val cause = current ?: return false
            if (cause is UnknownHostException) return true
            val message = cause.message.orEmpty().lowercase()
            if (listOf("unable to resolve host", "no address associated with hostname",
                    "name or service not known", "temporary failure in name resolution",
                    "err_name_not_resolved").any { it in message }) return true
            current = cause.cause
        }
        return false
    }
}
