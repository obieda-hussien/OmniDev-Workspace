package com.omnidev.workspace.ui.lifecycle

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * True only while the current Compose host is resumed and can safely own Android windows.
 * Keep dialogs/popups out of composition while their Activity is paused or stopped.
 */
@Composable
fun rememberHostResumed(): Boolean {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state by lifecycle.currentStateFlow.collectAsState()
    return state.isAtLeast(Lifecycle.State.RESUMED)
}
