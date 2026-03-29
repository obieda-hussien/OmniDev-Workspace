package com.omnidev.workspace.data.tools

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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
    private val pendingJsResults = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private val jsBridge = JsBridge()
    private val nullPayloadErrorJson = """{"ok":false,"result":"","error":"Empty JS payload"}"""

    companion object {
        private const val TAG = "HeadlessBrowser"
        
        /** Navigation timeout in milliseconds. */
        private const val NAVIGATE_TIMEOUT_MS = 30_000L

        /** JS evaluation timeout in milliseconds. */
        private const val JS_TIMEOUT_MS = 10_000L

        /** Maximum output characters from JS evaluation. */
        private const val MAX_JS_OUTPUT = 8_000
    }

    // ─────────────────────────────────────────────────────────────────────
    // Tool Definition & Routing
    // ─────────────────────────────────────────────────────────────────────

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "headless_browser",
            description = "A hidden browser engine (like Puppeteer) that allows navigating to URLs, " +
                "waiting for modern SPA/JS rendering, and extracting or interacting with the DOM via JavaScript. " +
                "Always call 'navigate' first before attempting to 'execute_js' or 'get_dom'.",
            parameters = listOf(
                ToolParameter(
                    name = "action",
                    type = "string",
                    description = "Action to perform: 'navigate' (loads a URL), 'execute_js' (runs JS code), 'get_dom' (gets text content), 'destroy' (cleans up).",
                    required = true
                ),
                ToolParameter(
                    name = "url",
                    type = "string",
                    description = "The URL to navigate to (required for 'navigate' action).",
                    required = false
                ),
                ToolParameter(
                    name = "js_code",
                    type = "string",
                    description = "JavaScript code to execute in the page context (required for 'execute_js' action).",
                    required = false
                )
            )
        )
    )

    /**
     * Executes the requested action from the AI Agent's tool call.
     */
    suspend fun execute(action: String, args: Map<String, String>): ToolExecutionResult {
        return when (action.lowercase().trim()) {
            "navigate" -> {
                val url = args["url"] ?: return ToolExecutionResult("Missing 'url' parameter for navigate.", isError = true)
                navigate(url)
            }
            "execute_js" -> {
                val code = args["js_code"] ?: return ToolExecutionResult("Missing 'js_code' parameter for execute_js.", isError = true)
                executeJs(code)
            }
            "get_dom" -> {
                getDom()
            }
            "destroy" -> {
                destroy()
                ToolExecutionResult("✅ Headless browser destroyed and resources freed.")
            }
            else -> ToolExecutionResult(
                "Unknown headless_browser action: '$action'. Supported: navigate, execute_js, get_dom, destroy.",
                isError = true
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Core Engine Methods
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Lazily initializes the WebView strictly on the main thread.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun ensureWebView(): WebView = withContext(Dispatchers.Main) {
        webView?.let { return@withContext it }
        
        val wv = WebView(appContext).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            // Block images to vastly improve invisible loading speed
            settings.blockNetworkImage = true 
            addJavascriptInterface(jsBridge, "OmniDevJsBridge")
        }
        webView = wv
        wv
    }

    /**
     * Navigates to a URL and waits for the page to fully load.
     */
    private suspend fun navigate(url: String): ToolExecutionResult {
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
                                deferred.completeExceptionally(
                                    RuntimeException("ERROR: ${error?.description ?: "Unknown error"}")
                                )
                            }
                        }
                    }
                    wv.loadUrl(url)
                }

                // Wait for the onPageFinished callback
                val resultUrl = deferred.await()
                
                // Allow a small buffer for modern JS frameworks (React/Vue) to render their DOM
                delay(1500)
                
                val title = withContext(Dispatchers.Main) { wv.title ?: "(no title)" }

                ToolExecutionResult(
                    output = "✅ Navigated to: $resultUrl\nPage title: $title\nUse `browser_execute_js` to interact or `browser_get_dom` to read content."
                )
            }
        } catch (e: Exception) {
            ToolExecutionResult("Failed to navigate to '$url': ${e.message}", isError = true)
        }
    }

    /**
     * Executes JavaScript in the current page context.
     */
    private suspend fun executeJs(jsCode: String): ToolExecutionResult {
        if (webView == null) {
            return ToolExecutionResult("No page loaded. Call `browser_navigate` first.", isError = true)
        }

        if (jsCode.isBlank()) {
            return ToolExecutionResult("Empty JavaScript code.", isError = true)
        }

        return try {
            withTimeout(JS_TIMEOUT_MS) {
                val token = UUID.randomUUID().toString()
                val deferred = CompletableDeferred<String>()
                pendingJsResults[token] = deferred

                val wrappedJs = buildAsyncWrapper(jsCode, token)
                
                withContext(Dispatchers.Main) {
                    webView?.evaluateJavascript(wrappedJs, null)
                }

                // Wait for the JS Bridge to return the payload
                val result = deferred.await()
                pendingJsResults.remove(token)
                
                val parsed = runCatching { JSONObject(result) }.getOrNull()
                    ?: return@withTimeout ToolExecutionResult(
                        output = "JS ERROR: Invalid async result payload.",
                        isError = true
                    )
                    
                if (!parsed.optBoolean("ok")) {
                    return@withTimeout ToolExecutionResult(
                        output = "JS ERROR: ${parsed.optString("error", "Unknown JavaScript error")}",
                        isError = true
                    )
                }
                
                val value = parsed.optString("result", "")
                val truncated = value.length > MAX_JS_OUTPUT
                val output = if (truncated) {
                    value.take(MAX_JS_OUTPUT) + "\n[TRUNCATED — ${value.length} chars total]"
                } else {
                    value
                }

                ToolExecutionResult(output = "JS Result:\n$output")
            }
        } catch (e: Exception) {
            ToolExecutionResult(
                output = "JS ERROR: ${e.message ?: e.javaClass.simpleName}. Fix your JS code and retry.",
                isError = true
            )
        }
    }

    /**
     * Returns a text-only snapshot of the current page's DOM.
     */
    private suspend fun getDom(): ToolExecutionResult {
        if (webView == null) {
            return ToolExecutionResult("No page loaded. Call `browser_navigate` first.", isError = true)
        }

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
    private fun destroy() {
        mainHandler.post {
            pendingJsResults.values.forEach { deferred ->
                if (!deferred.isCompleted) deferred.completeExceptionally(
                    IllegalStateException("Browser destroyed before JS result returned.")
                )
            }
            pendingJsResults.clear()
            webView?.destroy()
            webView = null
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // JS Bridge & Wrapper
    // ─────────────────────────────────────────────────────────────────────

    private fun buildAsyncWrapper(jsCode: String, token: String): String {
        val quotedCode = JSONObject.quote(jsCode)
        val quotedToken = JSONObject.quote(token)
        return """
            (function() {
                const __token = $quotedToken;
                const __source = $quotedCode;
                const __extractError = function(err) {
                    return err && (err.stack || err.message) ? (err.stack || err.message) : err;
                };
                const __send = function(ok, value) {
                    try {
                        const payload = JSON.stringify({
                            ok: !!ok,
                            result: ok ? (value == null ? "" : String(value)) : "",
                            error: ok ? "" : (value == null ? "Unknown JavaScript error" : String(value))
                        });
                        OmniDevJsBridge.deliver(__token, payload);
                    } catch (bridgeErr) {}
                };
                try {
                    // Intentionally evaluates agent-supplied code in the currently loaded page
                    // context (the core purpose of browser_execute_js). This tool must remain
                    // user-gated by the agent pipeline confirmation flow.
                    const __result = (0, eval)(__source);
                    Promise.resolve(__result)
                        .then(function(v) { __send(true, v); })
                        .catch(function(err) { __send(false, __extractError(err)); });
                } catch (err) {
                    __send(false, __extractError(err));
                }
                return true;
            })();
        """.trimIndent()
    }

    private inner class JsBridge {
        @JavascriptInterface
        fun deliver(token: String, payload: String?) {
            val deferred = pendingJsResults.remove(token) ?: return
            if (!deferred.isCompleted) deferred.complete(payload ?: nullPayloadErrorJson)
        }
    }
}
