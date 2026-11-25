package com.omnidev.workspace.ui.browser

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.omnidev.workspace.OmniDevApp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class BrowserNavigationEvidenceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun verifiesMainPageAndRejectsRedirectAndHttpError() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OmniDevApp
        val browser = app.headlessBrowserManager

        ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).use { server ->
            val worker = thread(name = "browser-navigation-fixture", isDaemon = true) {
                while (!server.isClosed) {
                    val socket = try {
                        server.accept()
                    } catch (closed: SocketException) {
                        if (server.isClosed) break
                        throw closed
                    }
                    try {
                        socket.use(::serve)
                    } catch (_: IOException) {
                        // WebView can abandon speculative sockets.
                    }
                }
            }
            try {
                val created = runBlocking { browser.execute("new_session", mapOf("label" to "Navigation test")) }
                assertFalse(created.output, created.isError)
                val sessionId = checkNotNull(browser.getActiveSessionId())
                try {
                    compose.setContent {
                        AndroidView(factory = { checkNotNull(browser.getWebViewForSession(sessionId)) })
                    }
                    compose.waitForIdle()

                    val origin = "http://127.0.0.1:${server.localPort}"
                    fun navigate(path: String) = runBlocking {
                        browser.executeForAgent("navigate", mapOf("session_id" to sessionId, "url" to "$origin$path"))
                    }

                    // The deliberately missing image is a subresource and must not fail the document.
                    val ready = navigate("/ready")
                    assertFalse(ready.output, ready.isError)

                    val redirected = navigate("/redirect")
                    assertTrue(redirected.output, redirected.isError)
                    assertEquals(redirected.output, "BROWSER_UNVERIFIED_REDIRECT", redirected.classification)

                    val missing = navigate("/missing")
                    assertTrue(missing.output, missing.isError)
                    assertEquals(missing.output, "BROWSER_HTTP_ERROR", missing.classification)
                } finally {
                    runBlocking { browser.execute("close_session", mapOf("session_id" to sessionId)) }
                }
            } finally {
                server.close()
                worker.join(2_000)
            }
        }
    }

    private fun serve(socket: Socket) {
        socket.soTimeout = 2_000
        val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
        val path = reader.readLine()?.split(' ')?.getOrNull(1) ?: return
        while (!reader.readLine().isNullOrEmpty()) { /* Drain request headers. */ }

        val status = when (path) {
            "/ready", "/landing" -> "200 OK"
            "/redirect" -> "302 Found"
            else -> "404 Not Found"
        }
        val image = if (path == "/ready") "<img src='/missing-image.png' alt='Fixture'>" else ""
        val body = "<!doctype html><title>Navigation test</title><p>Fixture page</p>$image"
        val location = if (path == "/redirect") "Location: /landing\r\n" else ""
        val response = "HTTP/1.1 $status\r\n" + location +
            "Content-Type: text/html; charset=utf-8\r\nContent-Length: ${body.length}\r\n" +
            "Connection: close\r\n\r\n$body"
        socket.getOutputStream().write(response.toByteArray(Charsets.US_ASCII))
    }
}
