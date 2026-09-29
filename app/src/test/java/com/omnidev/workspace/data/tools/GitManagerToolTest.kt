package com.omnidev.workspace.data.tools

import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.CredentialItem
import org.eclipse.jgit.transport.URIish
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GitManagerToolTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun `local Git actions work without a system git executable`() = runBlocking {
        val directory = temp.newFolder("project")
        Git.init().setDirectory(directory).call().close()
        File(directory, "note.txt").writeText("first\n")

        val status = GitManagerTool.execute("status", directory.absolutePath)
        assertTrue(status.output, status.output.contains("note.txt"))
        assertFalse(GitManagerTool.execute("add", directory.absolutePath, files = "note.txt").isError)
        val commit = GitManagerTool.execute("commit", directory.absolutePath, commitMessage = "Initial",
            authorName = "Test User", authorEmail = "test@example.com")
        assertFalse(commit.output, commit.isError)
        File(directory, "note.txt").appendText("second\n")
        val diff = GitManagerTool.execute("diff", directory.absolutePath, files = "note.txt")
        assertTrue(diff.output, diff.output.contains("+second"))
        assertTrue(GitManagerTool.execute("log", directory.absolutePath).output.contains("Initial"))
    }

    @Test fun `GitHub credentials are limited to a canonical HTTPS repository URL`() {
        assertTrue(GitManagerTool.isSafeGitHubRemote("https://github.com/obieda-hussien/OmniDev-Workspace.git"))
        assertTrue(GitManagerTool.isSafeGitHubRemote("https://github.com/owner/repo"))
        listOf(
            "git@github.com:owner/repo.git",
            "https://user:token@github.com/owner/repo.git",
            "https://github.com.evil.test/owner/repo",
            "https://github.com:444/owner/repo",
            "https://github.com/owner/repo?redirect=https://evil.test",
            "https://github.com/owner/../other",
            "https://github.com/owner/%2e%2e/other",
            "http://github.com/owner/repo"
        ).forEach { assertFalse(it, GitManagerTool.isSafeGitHubRemote(it)) }
    }

    @Test fun `branch cannot become a git option or invalid refspec`() {
        assertTrue(GitManagerTool.isSafeBranch("feature/agent-access"))
        listOf("-force", "../main", "heads/../../other", "name.lock", "bad name", "name:other", "name@{1}")
            .forEach { assertFalse(it, GitManagerTool.isSafeBranch(it)) }
    }

    @Test fun `account credential is offered only to the selected GitHub repository`() {
        val provider = GitManagerTool.scopedCredentials("https://github.com/owner/repo.git", "test-only-token")
        assertTrue(provider.get(URIish("https://github.com/owner/repo.git"),
            CredentialItem.Username(), CredentialItem.Password()))
        assertFalse(provider.get(URIish("https://example.org/owner/repo.git"),
            CredentialItem.Username(), CredentialItem.Password()))
        assertFalse(provider.get(URIish("https://github.com/other/repo.git"),
            CredentialItem.Username(), CredentialItem.Password()))
    }
}
