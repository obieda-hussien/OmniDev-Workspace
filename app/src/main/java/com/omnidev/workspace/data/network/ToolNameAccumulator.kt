package com.omnidev.workspace.data.network

/** Accept incremental fragments and providers that repeat the full name per chunk. */
internal fun mergeToolName(current: String, fragment: String): String = when {
    fragment.isEmpty() -> current
    current.isEmpty() -> fragment
    fragment == current -> current
    fragment.startsWith(current) -> fragment
    else -> current + fragment
}
