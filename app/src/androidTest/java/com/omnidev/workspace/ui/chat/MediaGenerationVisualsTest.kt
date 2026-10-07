package com.omnidev.workspace.ui.chat

import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.omnidev.workspace.data.chatmedia.*
import com.omnidev.workspace.data.model.AttachmentMediaType
import com.omnidev.workspace.ui.motion.LocalOmniMotion
import com.omnidev.workspace.ui.motion.MotionPolicy
import org.junit.Rule
import org.junit.Test

class MediaGenerationVisualsTest {
    @get:Rule val compose = createComposeRule()
    private val progress = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)

    @Test fun failureReplacesLiveGenerationAndRemovesEveryProgressIndicator() {
        val status = mutableStateOf(MediaCardStatus(MediaStage.GENERATING, "Creating your video", "Provider is working"))
        compose.setContent { MaterialTheme {
            MediaGenerationPreview(status.value, AttachmentMediaType.VIDEO, Modifier.height(260.dp))
        } }
        compose.onNodeWithTag("media-creation-generating").assertExists()
        compose.onAllNodes(progress).assertCountEquals(2)
        compose.runOnIdle { status.value = MediaCardStatus(MediaStage.FAILED, "Creation failed", "Provider access denied", "HTTP_401") }
        compose.onNodeWithTag("media-creation-generating").assertDoesNotExist()
        compose.onNodeWithTag("media-creation-failed").assertExists()
        compose.onNodeWithText("Creation failed").assertIsDisplayed()
        compose.onAllNodes(progress).assertCountEquals(0)
    }
    @Test fun reducedMotionStillShowsTheCurrentStageWithoutAnimatedLoadingBar() {
        compose.setContent { CompositionLocalProvider(LocalOmniMotion provides MotionPolicy(reduced = true)) { MaterialTheme {
            MediaGenerationPreview(MediaCardStatus(MediaStage.DOWNLOADING, "Saving your creation", "Downloading"), AttachmentMediaType.IMAGE, Modifier.height(260.dp))
        } } }
        compose.onNodeWithText("Saving your creation").assertIsDisplayed()
        compose.onNodeWithTag("media-creation-downloading").assertExists()
        compose.onAllNodes(progress).assertCountEquals(1)
    }
    @Test fun waitingAndCancellationHaveDistinctTruthfulStaticLabels() {
        val status = mutableStateOf(MediaCardStatus(MediaStage.WAITING, "Waiting to continue", "Connection interrupted"))
        compose.setContent { MaterialTheme {
            MediaGenerationPreview(status.value, AttachmentMediaType.AUDIO, Modifier.height(260.dp))
        } }
        compose.onNodeWithText("WAITING · NO NEW GENERATION").assertIsDisplayed()
        compose.runOnIdle { status.value = MediaCardStatus(MediaStage.CANCELLED, "Creation cancelled", "Stopped") }
        compose.onNodeWithText("STOPPED ON THIS DEVICE").assertIsDisplayed()
        compose.onAllNodes(progress).assertCountEquals(0)
    }
}
