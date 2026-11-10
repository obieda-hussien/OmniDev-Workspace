package com.omnidev.workspace.data.tools

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class GitWorkspaceSelectorTest {
    @Test fun `verified selected git root supplies missing cwd`() {
        val dir = Files.createTempDirectory("omni-git").toFile()
        try {
            assertTrue(File(dir, ".git").mkdir())
            val actual = GitWorkspaceSelector.injectVerifiedScope(
                "git_status", mapOf("action" to "git_status"), dir.path)
            assertEquals(dir.absolutePath, actual["cwd"])
        } finally {
            File(dir, ".git").delete()
            dir.delete()
        }
    }

    @Test fun `remote identity or ordinary folder is not an implied checkout`() {
        val source = mapOf("action" to "git_status")
        assertSame(source, GitWorkspaceSelector.injectVerifiedScope("git_status", source, "owner/repository"))
        assertSame(source, GitWorkspaceSelector.injectVerifiedScope("git_status", source, "/no/such/folder"))
        assertSame(source, GitWorkspaceSelector.injectVerifiedScope("git_clone", source, "/no/such/folder"))
    }

    @Test fun `explicit working directory is never silently replaced`() {
        val source = mapOf("action" to "git_status", "cwd" to "/chosen/repo")
        assertSame(source, GitWorkspaceSelector.injectVerifiedScope("git_status", source, "/elsewhere"))
    }
}
