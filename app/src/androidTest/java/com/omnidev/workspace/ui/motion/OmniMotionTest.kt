package com.omnidev.workspace.ui.motion

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class OmniMotionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun reducedMotionDisclosureCompletesWithoutWaitingForAnimation() {
        val visible = mutableStateOf(false)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalOmniMotion provides MotionPolicy(reduced = true)) {
                MaterialTheme {
                    OmniAnimatedVisibility(visible.value) { Text("Details", Modifier.testTag("details")) }
                }
            }
        }
        compose.runOnIdle { visible.value = true }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("details").assertExists()
        compose.runOnIdle { visible.value = false }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("details").assertDoesNotExist()
    }

    @Test fun pressFeedbackPreservesClicksAndDisabledActions() {
        var clicks = 0
        compose.setContent {
            MaterialTheme {
                Column {
                    OmniIconButton(onClick = { clicks++ }, modifier = Modifier.testTag("enabled")) { Text("Go") }
                    OmniIconButton(onClick = { clicks++ }, enabled = false,
                        modifier = Modifier.testTag("disabled")) { Text("Disabled") }
                }
            }
        }
        compose.onNodeWithTag("enabled").performClick()
        compose.onNodeWithTag("disabled").performClick()
        compose.runOnIdle { assertEquals(1, clicks) }
    }
}
