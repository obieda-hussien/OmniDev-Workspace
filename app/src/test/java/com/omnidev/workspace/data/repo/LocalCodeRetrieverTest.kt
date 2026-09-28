package com.omnidev.workspace.data.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class LocalCodeRetrieverTest {
    @Test fun `behavior question returns bounded verbatim evidence`() {
        val root = Files.createTempDirectory("repo-evidence").toFile()
        try {
            val source = root.resolve("AuthenticationGate.kt")
            source.writeText("class AuthenticationGate {\n fun checkPermission(token: String) {\n  require(token.isNotBlank())\n }\n}\n")
            val hits = LocalCodeRetriever.retrieve("Where is authentication permission checked?", root,
                listOf(LocalCodeRetriever.Candidate(source.path, "checkPermission")))
            assertEquals(1, hits.size)
            assertTrue(hits.first().excerpt.contains("checkPermission"))
            assertEquals(1, hits.first().startLine)
        } finally { root.deleteRecursively() }
    }

    @Test fun `symlink outside project never leaks source`() {
        val root = Files.createTempDirectory("repo-root").toFile()
        val outside = Files.createTempFile("private-authentication", ".kt").toFile()
        try {
            outside.writeText("fun authentication() = secret")
            val link = root.resolve("Authentication.kt")
            Files.createSymbolicLink(link.toPath(), outside.toPath())
            assertTrue(LocalCodeRetriever.retrieve("authentication", root,
                listOf(LocalCodeRetriever.Candidate(link.path))).isEmpty())
        } finally { root.deleteRecursively(); outside.delete() }
    }
}
