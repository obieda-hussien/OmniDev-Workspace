package com.omnidev.workspace.data.tools

import com.omnidev.workspace.data.auth.GitHubAgentAccessStore
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class GitHubManagerToolContractTest {
    private val policy = GitHubAgentAccessStore.Policy(
        enabled = true, writeEnabled = false, destructiveEnabled = false, organizationAdminEnabled = false,
        authMethod = GitHubAgentAccessStore.AuthMethod.PERSONAL_ACCESS_TOKEN, accountLogin = "owner",
        oauthClientId = "", token = "secret-token", requestedScopes = "", grantedScopes = "")
    private fun client(code: Int, body: String, link: String? = null, inspect: (okhttp3.Request) -> Unit = {}) =
        OkHttpClient.Builder().addInterceptor { chain ->
            inspect(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
                .body(body.toResponseBody()).apply { link?.let { header("Link", it) } }.build()
        }.build()

    @Test fun `reported shorthand sends correct authenticated GET without leaking token`() {
        val result = GitHubManagerTool.apiRequest(policy, "secret-token", "GET", "owner/IDE-", null,
            client = client(200, """{"full_name":"owner/IDE-","default_branch":"dev"}""") { request ->
                assertEquals("https://api.github.com/repos/owner/IDE-", request.url.toString())
                assertEquals("Bearer secret-token", request.header("Authorization"))
                assertEquals("GET", request.method)
            })
        assertFalse(result.isError)
        assertTrue(result.output.contains("default_branch"))
        assertFalse(result.output.contains("secret-token"))
    }
    @Test fun `404 does not falsely diagnose missing authorization`() {
        val result = GitHubManagerTool.apiRequest(policy, "secret-token", "GET", "/repos/a/b", null,
            client = client(404, """{"message":"Not Found"}"""))
        assertEquals("GITHUB_RESOURCE_NOT_FOUND", result.classification)
        assertTrue(result.output.contains("404 alone cannot distinguish"))
        assertFalse(result.output.contains("cannot expand its own authorization"))
    }
    @Test fun `successful listing carries actual pagination continuation`() {
        val result = GitHubManagerTool.apiRequest(policy, "secret-token", "GET", "/user/repos", null,
            client = client(200, "[]", "<https://api.github.com/user/repos?page=2>; rel=\"next\""))
        assertTrue(result.output.contains("Next page endpoint: /user/repos?page=2"))
    }
    @Test fun `raw file read negotiates correct media type and returns text pages`() {
        val result = GitHubManagerTool.apiRequest(policy, "secret-token", "GET", "/repos/a/b/contents/README.md", null,
            rawFile = true, maxLines = 1, client = client(200, "first\nsecond") {
                assertEquals("application/vnd.github.raw+json", it.header("Accept"))
            })
        assertTrue(result.output.contains("1: first"))
        assertTrue(result.output.contains("next start_line=2"))
    }
    @Test fun `write and delete gates reject before HTTP even with destructive flag alone`() {
        var sent = false
        val fake = client(200, "{}") { sent = true }
        assertThrows(IllegalArgumentException::class.java) {
            GitHubManagerTool.apiRequest(policy, "secret-token", "POST", "/repos/a/b/issues", "{}", client = fake)
        }
        assertThrows(IllegalArgumentException::class.java) {
            GitHubManagerTool.apiRequest(policy.copy(destructiveEnabled = true), "secret-token", "DELETE", "/repos/a/b", null, client = fake)
        }
        assertFalse(sent)
    }
    @Test fun `429 without optional headers is still rate limiting`() {
        val result = GitHubManagerTool.apiRequest(policy, "secret-token", "GET", "/user", null, client = client(429, "{}"))
        assertEquals("GITHUB_RATE_LIMITED", result.classification)
        assertTrue(result.persistentFailure)
        assertFalse(result.output.contains("Check token repository selection"))
        assertEquals("GITHUB_PERMISSION_DENIED", GitHubManagerTool.apiRequest(policy, "secret-token", "GET", "/user", null,
            client = client(403, "{}")).classification)
    }
    @Test fun `raw percent filename reaches the correctly encoded GitHub request`() {
        val endpoint = GitHubRequestContract.read("read_file", "a/b", "docs/100%.md", null, 1, 20, null).endpoint
        val result = GitHubManagerTool.apiRequest(policy, "secret-token", "GET", endpoint, null, rawFile = true,
            client = client(200, "file text") { assertTrue(it.url.encodedPath.endsWith("/docs/100%25.md")) })
        assertFalse(result.isError)
        assertTrue(result.output.contains("file text"))
    }

    @Test fun `401 and rate limits have distinct actionable classes`() {
        assertEquals("GITHUB_AUTH_REQUIRED", GitHubManagerTool.apiRequest(policy, "secret-token", "GET", "/user", null,
            client = client(401, "{}")).classification)
        val limited = OkHttpClient.Builder().addInterceptor { chain -> Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
            .code(403).message("limited").header("X-RateLimit-Remaining", "0").body("{}".toResponseBody()).build() }.build()
        assertEquals("GITHUB_RATE_LIMITED", GitHubManagerTool.apiRequest(policy, "secret-token", "GET", "/user", null, client = limited).classification)
    }
}
