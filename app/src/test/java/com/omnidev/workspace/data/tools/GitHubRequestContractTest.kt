package com.omnidev.workspace.data.tools

import org.junit.Assert.*
import org.junit.Test

class GitHubRequestContractTest {
    @Test fun `reported repository shorthand becomes a real repo GET`() {
        for (repo in listOf("obieda-hussien/OmniLinkSDK", "obieda-hussien/Omni-AndroidIDE-", "obieda-hussien/OmniDev-Workspace"))
            assertEquals("/repos/$repo", GitHubRequestContract.endpoint(repo, "GET"))
        assertEquals("/repos/obieda-hussien/OmniLinkSDK/contents", GitHubRequestContract.endpoint("obieda-hussien/OmniLinkSDK/contents", "GET"))
    }
    @Test fun `explicit nonrepository roots remain unchanged`() {
        for (path in listOf("/user/repos", "/users/obieda-hussien/repos", "/graphql", "/search/code?q=a"))
            assertEquals(path, GitHubRequestContract.endpoint(path, "GET"))
    }
    @Test fun `mutations do not guess endpoint prefixes`() {
        assertThrows(IllegalArgumentException::class.java) { GitHubRequestContract.endpoint("owner/repo", "DELETE") }
        assertEquals("/repos/owner/repo", GitHubRequestContract.endpoint("/repos/owner/repo", "DELETE"))
    }
    @Test fun `host escapes and encoded traversal are rejected`() {
        for (path in listOf("", "https://evil.test/repos/a/b", "//evil.test/a/b", "/repos/a/b/../c", "/repos/a/b/%2e%2e/c", "/repos/a/b/%252e%252e/c", "/repos/a/b/%2f..", "/repos/a/b\\c", "/repos/a/b#c"))
            assertThrows(path, IllegalArgumentException::class.java) { GitHubRequestContract.endpoint(path, "GET") }
    }
    @Test fun `typed paths refs and pagination are encoded without losing punctuation`() {
        val file = GitHubRequestContract.read("read_file", "owner/IDE-", "docs/ملف a.md", "feature/a", 1, 20, null)
        assertTrue(file.rawFile)
        assertTrue(file.endpoint.startsWith("/repos/owner/IDE-/contents/docs/"))
        assertTrue(file.endpoint.contains("%20a.md?ref=feature%2Fa"))
        assertEquals("/user/repos?per_page=20&page=2&sort=updated", GitHubRequestContract.read("list_repos", "", null, null, 2, 20, null).endpoint)
    }
    @Test fun `typed root and invalid paths are explicit`() {
        assertEquals("/repos/a/b/contents", GitHubRequestContract.read("list_contents", "a/b", null, null, 1, 20, null).endpoint)
        assertThrows(IllegalArgumentException::class.java) { GitHubRequestContract.read("read_file", "a/b", "", null, 1, 20, null) }
        assertThrows(IllegalArgumentException::class.java) { GitHubRequestContract.read("read_file", "a/b", "../x", null, 1, 20, null) }
        assertThrows(IllegalArgumentException::class.java) { GitHubRequestContract.read("list_repos", "", null, null, 0, 20, null) }
    }
    @Test fun `legacy and typed reads share failure identity`() {
        assertEquals(GitHubRequestContract.failureKey("api_request", mapOf("repo" to "a/b", "title" to "GET")),
            GitHubRequestContract.failureKey("get_repo", mapOf("repo" to "a/b")))
        assertNotEquals(GitHubRequestContract.failureKey("read_file", mapOf("repo" to "a/b", "path" to "README.md", "ref" to "main")),
            GitHubRequestContract.failureKey("read_file", mapOf("repo" to "a/b", "path" to "README.md", "ref" to "dev")))
    }
    @Test fun `schema no longer requires placeholder title or body`() {
        val tool = GitHubManagerTool.getToolDefinitions().single()
        val call = com.omnidev.workspace.data.model.ToolCall("one", tool.name, mapOf("action" to "get_repo", "repo" to "a/b"))
        assertNull(com.omnidev.workspace.domain.engine.ToolCallPreflight(listOf(tool)).check(call))
    }
}
