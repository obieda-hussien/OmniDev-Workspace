package com.omnidev.workspace.ui.settings

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import com.omnidev.workspace.BuildConfig
import com.omnidev.workspace.data.background.BackgroundServiceSupervisor
import com.omnidev.workspace.data.background.IntegrationServiceRecovery
import com.omnidev.workspace.data.debug.DebugLogManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RuntimeDiagnosticsRegressionTest {
    @Test fun diagnosticsAreAvailableInDeviceStorageAndReadableFromConsole() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val id = "runtime-test-${System.nanoTime()}"
        DebugLogManager.writeDiagnostic(id, "ERROR", "Diagnostic regression", "bounded evidence")
        try {
            val entry = DebugLogManager.readAll().single { it.filename == "error_$id.log" }
            assertEquals("ERROR", entry.level)
            assertEquals("Diagnostic regression", entry.title)
            assertTrue(entry.body.contains("bounded evidence"))
            val folder = java.io.File(context.createDeviceProtectedStorageContext().filesDir, "debug_logs")
            assertTrue(java.io.File(folder, entry.filename).isFile)
            DebugLogManager.writeDiagnostic(id, "ERROR", "Duplicate", "must not replace evidence")
            assertEquals(entry.body, DebugLogManager.readAll().single { it.filename == entry.filename }.body)
        } finally {
            java.io.File(context.createDeviceProtectedStorageContext().filesDir, "debug_logs/error_$id.log").delete()
        }
    }

    @Test fun liteDoesNotResurrectRemovedSyncOrIntegrationServices() = runBlocking {
        if (BuildConfig.ALLOW_AGENT) return@runBlocking
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("omnidev_background_supervisor", Context.MODE_PRIVATE)
        val previous = preferences.getBoolean("sync_desired", true)
        try {
            preferences.edit().putBoolean("sync_desired", true).commit()
            assertFalse(BackgroundServiceSupervisor.isSyncDesired(context))
            BackgroundServiceSupervisor.setSyncDesired(context, true)
            IntegrationServiceRecovery.recoverEnabledNow(context, "regression")
            assertFalse(BackgroundServiceSupervisor.isSyncDesired(context))
        } finally { preferences.edit().putBoolean("sync_desired", previous).commit() }
    }
}
