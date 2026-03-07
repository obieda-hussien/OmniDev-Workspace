package com.omnidev.workspace.data.tools

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Headless browser engine using an invisible Android WebView.
 *
 * Allows the AI agent to navigate to dynamic websites (SPAs, login pages),
 * wait for JavaScript to execute, then extract DOM content or run arbitrary JS.
 * This is the Android equivalent of Puppeteer/Computer Use.
 *
 * The WebView is created on the main thread (required by Android) but kept invisible —
 * it never attaches to any visible layout.
 */
class HeadlessBrowserManager(context: Context) {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var webView: WebView? = null

    /** Navigation timeout in milliseconds. */
    private val NAVIGATE_TIMEOUT_MS = 30_000L

    /** JS evaluation timeout in milliseconds. */
    private val JS_TIMEOUT_MS = 10_000L

    /** Maximum output characters from JS evaluation. */
    private val MAX_JS_OUTPUT = 8_000

    /**
     * Lazily initializes the WebView on the main thread.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun ensureWebView(): WebView {
        webView?.let { return it }
        return withContext(Dispatchers.Main) {
            val wv = WebView(appContext).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.blockNetworkImage = true // faster loading
            }
            webView = wv
            wv
        }
    }

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "browser_navigate",
            description = "Navigates the headless browser to a URL and waits for the page to fully load " +
                "(including JavaScript). Returns the page title and a text summary of the DOM content. " +
                "Use this for dynamic websites (SPAs, login pages) that require JS execution.",
            parameters = listOf(
                ToolParameter("url", "string", "The URL to navigate to", required = true)
            )
        ),
        ToolDefinition(
            name = "browser_execute_js",
            description = "Executes arbitrary JavaScript in the headless browser's current page context. " +
                "Returns the string result of the script evaluation. Use for interacting with " +
                "dynamic web pages: filling forms, clicking buttons, extracting DOM content. " +
                "Example: document.querySelector('#search').value = 'AI'; document.querySelector('.btn').click();",
            parameters = listOf(
                ToolParameter("js_code", "string", "JavaScript code to execute in the page context", required = true)
            )
        ),
        ToolDefinition(
            name = "browser_get_dom",
            description = "Returns a text-only snapshot of the current page's DOM content (innerText). " +
                "Useful for reading page content after navigation or JS execution.",
            parameters = listOf()
        )
    )

    /**
     * Navigates to a URL and waits for the page to fully load.
     */
    suspend fun navigate(url: String): ToolExecutionResult {
        if (url.isBlank() || (!url.startsWith("http://") && !url.startsWith("https://"))) {
            return ToolExecutionResult("Invalid URL: must start with http:// or https://", isError = true)
        }

        return try {
            withTimeout(NAVIGATE_TIMEOUT_MS) {
                val wv = ensureWebView()
                val deferred = CompletableDeferred<String>()

                withContext(Dispatchers.Main) {
                    wv.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                            if (!deferred.isCompleted) {
                                deferred.complete(loadedUrl ?: url)
                            }
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: WebResourceError?
                        ) {
                            if (request?.isForMainFrame == true && !deferred.isCompleted) {
                                deferred.complete("ERROR: ${error?.description ?: "Unknown error"}")
                            }
                        }
                    }
                    wv.loadUrl(url)
                }

                val result = deferred.await()
                val title = withContext(Dispatchers.Main) { wv.title ?: "(no title)" }

                ToolExecutionResult(
                    output = "✅ Navigated to: $result\nPage title: $title\nUse `browser_execute_js` to interact or `browser_get_dom` to read content."
                )
            }
        } catch (e: Exception) {
            ToolExecutionResult("Failed to navigate to '$url': ${e.message}", isError = true)
        }
    }

    /**
     * Executes JavaScript in the current page context.
     */
    suspend fun executeJs(jsCode: String): ToolExecutionResult {
        val wv = webView ?: return ToolExecutionResult(
            "No page loaded. Call `browser_navigate` first.", isError = true
        )

        if (jsCode.isBlank()) {
            return ToolExecutionResult("Empty JavaScript code.", isError = true)
        }

        return try {
            withTimeout(JS_TIMEOUT_MS) {
                val result = suspendCancellableCoroutine<String> { continuation ->
                    mainHandler.post {
                        wv.evaluateJavascript(jsCode) { value ->
                            val output = value ?: "null"
                            if (continuation.isActive) {
                                continuation.resume(output)
                            }
                        }
                    }
                }

                val truncated = result.length > MAX_JS_OUTPUT
                val output = if (truncated) {
                    result.take(MAX_JS_OUTPUT) + "\n[TRUNCATED — ${result.length} chars total]"
                } else {
                    result
                }

                ToolExecutionResult(output = "JS Result:\n$output")
            }
        } catch (e: Exception) {
            ToolExecutionResult("Failed to execute JS: ${e.message}", isError = true)
        }
    }

    /**
     * Returns a text-only snapshot of the current page's DOM.
     */
    suspend fun getDom(): ToolExecutionResult {
        val wv = webView ?: return ToolExecutionResult(
            "No page loaded. Call `browser_navigate` first.", isError = true
        )

        return executeJs(
            "(function() { " +
                "var body = document.body.innerText || document.body.textContent; " +
                "return body ? body.substring(0, $MAX_JS_OUTPUT) : '(empty page)'; " +
            "})()"
        )
    }

    /**
     * Destroys the WebView and releases resources.
     */
    fun destroy() {
        mainHandler.post {
            webView?.destroy()
            webView = null
        }
    }
}
