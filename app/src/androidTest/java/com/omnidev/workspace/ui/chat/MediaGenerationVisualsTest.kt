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
import androidx.compose.ui.graphics.asAndroidBitmap
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        // The atmosphere intentionally requests frames forever. Freeze its clock
        // while asserting stage changes; motion is exercised separately below.
        compose.mainClock.autoAdvance = false
        val status = mutableStateOf(MediaCardStatus(MediaStage.GENERATING, "Creating your video", "Provider is working"))
        compose.setContent { MaterialTheme {
            MediaGenerationPreview(status.value, AttachmentMediaType.VIDEO, Modifier.height(260.dp))
        } }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("media-creation-generating").assertExists()
        compose.onAllNodes(progress).assertCountEquals(2)
        compose.runOnIdle { status.value = MediaCardStatus(MediaStage.FAILED, "Creation failed", "Provider access denied", "HTTP_401") }
        compose.mainClock.advanceTimeByFrame()
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
    @Test fun waitingAndCancellationHaveDistinctTruthfulLabels() {
        compose.mainClock.autoAdvance = false
        val status = mutableStateOf(MediaCardStatus(MediaStage.WAITING, "Waiting to continue", "Connection interrupted"))
        compose.setContent { MaterialTheme {
            MediaGenerationPreview(status.value, AttachmentMediaType.AUDIO, Modifier.height(260.dp))
        } }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("WAITING TO CONTINUE").assertIsDisplayed()
        compose.runOnIdle { status.value = MediaCardStatus(MediaStage.CANCELLED, "Creation cancelled", "Stopped") }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("STOPPED ON THIS DEVICE").assertIsDisplayed()
        compose.onAllNodes(progress).assertCountEquals(0)
    }

    @Test fun compactPhoneKeepsMusicAtmosphereMovingEvenWhileQueued() {
        compose.mainClock.autoAdvance = false
        compose.setContent { CompositionLocalProvider(LocalOmniMotion provides MotionPolicy(compact = true)) { MaterialTheme {
            MediaGenerationPreview(MediaCardStatus(MediaStage.QUEUED, "Waiting to start", "Queued"), AttachmentMediaType.AUDIO, Modifier.height(260.dp))
        } } }
        compose.mainClock.advanceTimeBy(160)
        val before = checkNotNull(compose.onNodeWithTag("media-atmosphere-audio").captureToImage().asAndroidBitmap().copy(android.graphics.Bitmap.Config.ARGB_8888, false))
        compose.mainClock.advanceTimeBy(1600)
        val after = compose.onNodeWithTag("media-atmosphere-audio").captureToImage().asAndroidBitmap()
        assertFalse("Compact media atmosphere must move", before.sameAs(after))
        compose.onNodeWithText("YOUR REQUEST IS IN THE QUEUE").assertIsDisplayed()
    }

    @Test fun reducedMotionFreezesTheAtmosphereForEveryMediaType() {
        val type = mutableStateOf(AttachmentMediaType.IMAGE)
        compose.mainClock.autoAdvance = false
        compose.setContent { CompositionLocalProvider(LocalOmniMotion provides MotionPolicy(reduced = true)) { MaterialTheme {
            MediaGenerationPreview(MediaCardStatus(MediaStage.GENERATING, "Creating", "Running"), type.value, Modifier.height(260.dp))
        } } }
        for (media in listOf(AttachmentMediaType.IMAGE, AttachmentMediaType.VIDEO, AttachmentMediaType.AUDIO)) {
            compose.runOnIdle { type.value = media }
            compose.mainClock.advanceTimeByFrame()
            val tag = "media-atmosphere-${media.name.lowercase()}"
            val before = checkNotNull(compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap().copy(android.graphics.Bitmap.Config.ARGB_8888, false))
            compose.mainClock.advanceTimeBy(1600)
            assertTrue(before.sameAs(compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()))
        }
    }
}
