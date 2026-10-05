package com.omnidev.workspace.data.auth

import org.junit.Assert.*
import org.junit.Test

class GitHubScopeCoverageTest {
    @Test fun `comma separated GitHub headers match space separated requests`() {
        assertTrue(GitHubScopeCoverage.missing("repo, read:user, user:email", "repo read:user user:email").isEmpty())
    }
    @Test fun `normalized broader grants do not cause false reauthorization warnings`() {
        assertTrue(GitHubScopeCoverage.missing("repo,user,admin:org,project", "repo read:user user:email read:org read:project").isEmpty())
    }
    @Test fun `missing scopes still require explicit user authorization`() {
        assertEquals(setOf("workflow", "delete_repo"), GitHubScopeCoverage.missing("repo", "repo workflow delete_repo"))
    }
}
