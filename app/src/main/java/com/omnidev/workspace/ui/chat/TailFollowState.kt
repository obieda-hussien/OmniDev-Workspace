package com.omnidev.workspace.ui.chat

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate

/** User intent persists while new rows/streaming text change the list's geometry. */
@Stable
class TailFollowState {
    var following by mutableStateOf(true)
        private set
    fun pause() { following = false }
    fun resume() { following = true }
}

/** Coalesce bursts without cancelling/restarting an animated scroll on every token. */
@Composable
fun rememberTailFollowState(
    listState: LazyListState,
    sessionKey: Any?,
    contentRevision: Any?,
    forceFollowKey: Any? = null,
    enabled: Boolean = true
): TailFollowState {
    val follow = remember(sessionKey) { TailFollowState() }
    val revision = rememberUpdatedState(contentRevision)
    val active = rememberUpdatedState(enabled)
    LaunchedEffect(follow, listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) follow.pause()
        }
    }
    LaunchedEffect(follow, listState) {
        snapshotFlow { !listState.isScrollInProgress && !listState.canScrollForward }
            .collect { atBottom -> if (atBottom) follow.resume() }
    }
    LaunchedEffect(follow, forceFollowKey) {
        if (forceFollowKey != null) follow.resume()
    }
    LaunchedEffect(follow, listState) {
        snapshotFlow { Triple(revision.value to listState.layoutInfo.viewportEndOffset, follow.following, active.value) }
            .conflate()
            .collect {
                delay(64L)
                withFrameNanos { } // Wait for the latest rows to be measured.
                if (follow.following && active.value && !listState.isScrollInProgress) {
                    val lastIndex = listState.layoutInfo.totalItemsCount - 1
                    if (lastIndex >= 0) listState.scrollToItem(lastIndex, Int.MAX_VALUE)
                }
            }
    }
    return follow
}
