package com.omnidev.workspace.ui.files

import com.omnidev.workspace.BuildConfig
import com.omnidev.workspace.OmniDevApp
import com.omnidev.workspace.data.repository.SettingsRepository
import com.omnidev.workspace.data.tools.FileToolManager
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Exercise Lite's actual Android storage/confirmation boundary, independent of advanced raw-file tools. */
class LiteFileBoundaryTest {
    @Test fun privateFilesRequireConsentAndCannotEscapeThroughPathsOrGodMode() = runBlocking {
        assumeTrue(BuildConfig.TIER == "LITE")
        val context = OmniDevApp.instance
        val settings = SettingsRepository(context)
        val previousUri = settings.observeTargetContextUri().first()
        val previousPath = settings.observeTargetContext().first()
        val root = File(context.filesDir, "assistant_workspace/lite-test-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val outside = File(context.cacheDir, "lite-outside-${java.util.UUID.randomUUID()}.txt").apply { writeText("private") }
        try {
            settings.setTargetContextUri(null, null)
            val tools = FileToolManager(godModeEnabled = true)
            assertFalse(tools.godModeEnabled)
            val create = mapOf("filePath" to "note.txt", "content" to "original")
            assertTrue(tools.executeTool("create_file", create, root.path).isError)
            assertFalse(File(root, "note.txt").exists())
            tools.confirmationGate = { _, _ -> true }
            assertFalse(tools.executeTool("create_file", create, root.path).isError)
            assertEquals("original", tools.executeTool("read_file", mapOf("filePath" to "note.txt"), root.path).output)
            assertTrue(tools.executeTool("read_file", mapOf("filePath" to outside.path), outside.parent).isError)
            assertTrue(tools.executeTool("read_file", mapOf("filePath" to "../escape.txt"), root.path).isError)
            assertFalse(tools.executeTool("patch_file_content", mapOf("filePath" to "note.txt", "searchSnippet" to "original", "replaceSnippet" to "updated"), root.path).isError)
            assertEquals("updated", File(root, "note.txt").readText())
            assertFalse(tools.executeTool("delete_file", mapOf("filePath" to "note.txt"), root.path).isError)
            assertFalse(File(root, "note.txt").exists())
            assertEquals("private", outside.readText())
        } finally {
            settings.setTargetContextUri(previousUri, previousPath)
            root.deleteRecursively()
            outside.delete()
        }
    }
}
