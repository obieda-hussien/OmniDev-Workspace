package com.omnidev.workspace.data.assistant

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** Acceptance by Android is only the start; success requires the view's attachment signal. */
internal suspend fun awaitBubbleAttachment(
    result: CompletableDeferred<Boolean>,
    timeoutMs: Long = 3_000,
    start: () -> Boolean
): Boolean = start() && withTimeoutOrNull(timeoutMs) { result.await() } == true
