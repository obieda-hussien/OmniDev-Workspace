package com.omnidev.workspace.data.network

/** Partial output is preserved by the UI; replaying it would duplicate content. */
class PartialCompletionException(cause: Throwable) : IllegalStateException("Response interrupted after partial output. Completed tool results were preserved.", cause)
