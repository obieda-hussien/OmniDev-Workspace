package com.omnidev.workspace.data.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class ReadablePageExtractorTest {
    private fun response(html: String) = PageFetchResponse(
        requestedUrl = "https://example.com/start",
        finalUrl = "https://example.com/article",
        statusCode = 200,
        contentType = "text/html",
        charset = StandardCharsets.UTF_8,
        body = html,
        headers = emptyMap(),
        redirectCount = 1,
        elapsedMs = 42
    )

    @Test
    fun `extracts article metadata and drops navigation noise`() {
        val html = """
            <html lang="en"><head>
              <title>Fallback title</title>
              <meta property="og:title" content="Research headline">
              <meta name="description" content="Useful summary">
              <meta name="author" content="Jane Doe">
              <meta property="article:published_time" content="2026-09-17T10:00:00Z">
              <link rel="canonical" href="https://example.com/canonical">
            </head><body>
              <nav>Home Products Pricing Contact</nav>
              <div class="sidebar">Subscribe Subscribe Subscribe</div>
              <article>
                <h1>Research headline</h1>
                <p>This is the first meaningful paragraph with enough content to represent the article body accurately.</p>
                <p>This is a second paragraph containing additional evidence and explanation for the reader.</p>
              </article>
              <footer>Copyright footer</footer>
            </body></html>
        """.trimIndent()

        val page = ReadablePageExtractor.extract(response(html))
        assertEquals("Research headline", page.metadata.title)
        assertEquals("Jane Doe", page.metadata.author)
        assertEquals("2026-09-17T10:00:00Z", page.metadata.publishedAt)
        assertEquals("https://example.com/canonical", page.metadata.canonicalUrl)
        assertTrue(page.text.contains("first meaningful paragraph"))
        assertTrue(page.text.contains("second paragraph"))
        assertFalse(page.text.contains("Products Pricing Contact"))
        assertFalse(page.text.contains("Copyright footer"))
    }

    @Test
    fun `markdown preserves headings code links and tables`() {
        val html = """
            <html><body><main>
              <h2>API</h2>
              <p>Read the <a href="https://example.com/docs">documentation</a>.</p>
              <pre><code class="language-kotlin">val x = 1</code></pre>
              <table><tr><th>Name</th><th>Value</th></tr><tr><td>x</td><td>1</td></tr></table>
            </main></body></html>
        """.trimIndent()

        val page = ReadablePageExtractor.extract(response(html))
        assertTrue(page.markdown.contains("## API"))
        assertTrue(page.markdown.contains("[documentation](https://example.com/docs)"))
        assertTrue(page.markdown.contains("```kotlin"))
        assertTrue(page.markdown.contains("| Name | Value |"))
    }

    @Test
    fun `url syntax guard blocks local and credentialed destinations`() {
        assertFalse(PageFetchEngine.isSyntacticallySafePublicUrl("http://127.0.0.1/admin"))
        assertFalse(PageFetchEngine.isSyntacticallySafePublicUrl("http://192.168.1.2/"))
        assertFalse(PageFetchEngine.isSyntacticallySafePublicUrl("https://user:pass@example.com/"))
        assertFalse(PageFetchEngine.isSyntacticallySafePublicUrl("file:///etc/passwd"))
        assertTrue(PageFetchEngine.isSyntacticallySafePublicUrl("https://example.com/article"))
    }
}
