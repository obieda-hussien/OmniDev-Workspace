package com.omnidev.workspace.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.filters.SdkSuppress
import com.omnidev.workspace.data.repository.SettingsRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test

@SdkSuppress(minSdkVersion = 30)
class WorkspaceLockTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun settings() = SettingsRepository(compose.activity.applicationContext)

    @After
    fun clearLock() = runBlocking { settings().setWorkspaceLock(false) }

    @Test
    fun enabledLockHidesWorkspaceWithoutAuthentication() {
        val settings = settings()
        runBlocking { settings.setWorkspaceLock(true) }
        compose.setContent { WorkspaceLockGate(settings) { Text("Workspace content") } }

        compose.waitUntil(5_000) { compose.onAllNodesWithText("Workspace locked").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Workspace locked").assertIsDisplayed()
        compose.onNodeWithText("Workspace content").assertDoesNotExist()
    }

    @Test
    fun lockingPreservesScreenStateWhileHidingContent() {
        val settings = settings()
        runBlocking { settings.setWorkspaceLock(false) }
        compose.setContent {
            WorkspaceLockGate(settings) {
                var page by rememberSaveable { mutableStateOf("Home screen") }
                Button(onClick = { page = "Saved settings page" }) { Text(page) }
            }
        }

        compose.waitUntil(5_000) { compose.onAllNodesWithText("Home screen").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Home screen").performClick()
        compose.onNodeWithText("Saved settings page").assertIsDisplayed()
        runBlocking { settings.setWorkspaceLock(true) }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Workspace locked").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Saved settings page").assertDoesNotExist()
        runBlocking { settings.setWorkspaceLock(false) }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Saved settings page").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Saved settings page").assertIsDisplayed()
    }

    @Test
    fun disabledLockShowsWorkspace() {
        val settings = settings()
        runBlocking { settings.setWorkspaceLock(false) }
        compose.setContent { WorkspaceLockGate(settings) { Text("Workspace content") } }

        compose.waitUntil(5_000) { compose.onAllNodesWithText("Workspace content").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Workspace content").assertIsDisplayed()
        compose.onNodeWithText("Workspace locked").assertDoesNotExist()
    }
}
