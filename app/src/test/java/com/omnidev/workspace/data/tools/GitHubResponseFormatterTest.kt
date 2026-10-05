package com.omnidev.workspace.data.tools

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GitHubResponseFormatterTest {
    @Test fun `repo lists keep useful evidence and drop URL template noise`() {
        val raw = JsonArray((1..30).map { buildJsonObject { put("full_name", "a/repo$it"); put("default_branch", "main"); put("keys_url", "noise".repeat(100)); put("private", true) } }).toString()
        val compact = GitHubResponseFormatter.format(raw, "/user/repos")
        assertTrue(compact.length < raw.length / 3)
        assertFalse(compact.contains("keys_url"))
        assertEquals(30, Json.parseToJsonElement(compact).jsonObject["included_count"]!!.jsonPrimitive.int)
    }
    @Test fun `large compact lists stay valid JSON and announce omitted items`() {
        val raw = JsonArray((1..1000).map { buildJsonObject { put("path", "a".repeat(400) + it); put("type", "file") } }).toString()
        val compact = GitHubResponseFormatter.format(raw, "/repos/a/b/contents")
        assertTrue(compact.length < 8000)
        assertTrue(Json.parseToJsonElement(compact).jsonObject["partial"]!!.jsonPrimitive.boolean)
    }
    @Test fun `only trusted pagination links become API endpoints`() {
        assertEquals("/user/repos?page=2", GitHubResponseFormatter.nextEndpoint("<https://api.github.com/user/repos?page=2>; rel=\"next\""))
        assertNull(GitHubResponseFormatter.nextEndpoint("<https://evil.test/user/repos>; rel=\"next\""))
        assertNull(GitHubResponseFormatter.nextEndpoint("<https://api.github.com/user/repos?page=3>; rel=\"last\""))
    }
    @Test fun `file pages preserve evidence and expose continuation`() {
        val page = GitHubResponseFormatter.filePage("one\ntwo\nthree\nfour", 2, 2)
        assertTrue(page.contains("2: two\n3: three"))
        assertTrue(page.contains("next start_line=4"))
        assertTrue(GitHubResponseFormatter.filePage("one", 1, 2).contains("End of file"))
    }
    @Test fun `binary and invalid file ranges are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { GitHubResponseFormatter.filePage("a\u0000b", 1, 20) }
        assertThrows(IllegalArgumentException::class.java) { GitHubResponseFormatter.filePage("one", 0, 20) }
    }
}
