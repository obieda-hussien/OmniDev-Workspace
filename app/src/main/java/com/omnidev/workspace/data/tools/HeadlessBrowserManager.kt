package com.omnidev.workspace.data.tools

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// ═══════════════════════════════════════════════════════════════════════════════
// HeadlessBrowserManager
// ═══════════════════════════════════════════════════════════════════════════════
//
// محرك متصفح خفي متقدم مبني على Android WebView.
// يُتيح للـ Agent التنقل بين المواقع الديناميكية، تنفيذ JavaScript،
// استخراج البيانات، ملء النماذج، التقاط الشاشة، وإدارة الكوكيز —
// كل ذلك بدون واجهة مرئية.
//
// الميزات:
//  ✦ إدارة جلسات متعددة (Multi-Tab)
//  ✦ إدارة الكوكيز والـ localStorage
//  ✦ ملء وإرسال النماذج تلقائياً
//  ✦ انتظار ظهور عناصر الـ DOM (Polling)
//  ✦ اعتراض طلبات الشبكة وحجبها
//  ✦ حقن CSS و JavaScript على مستوى الصفحة
//  ✦ التقاط صور (Screenshots as Base64)
//  ✦ استخراج بيانات منظمة (Structured Scraping)
//  ✦ محاكاة User-Agent مخصص
//  ✦ رأس برمجي قابل للإعادة الاستخدام
//  ✦ تسجيل طلبات الشبكة
//  ✦ إدارة حالة المتصفح (history, back/forward)

@SuppressLint("SetJavaScriptEnabled")
class HeadlessBrowserManager(context: Context) {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val sessionMutex = Mutex()
    private val sessionCounter = AtomicInteger(0)

    // ─── Session Registry ────────────────────────────────────────────────────
    private val sessions = ConcurrentHashMap<String, BrowserSession>()
    private var activeSessionId: String? = null

    // ─── Network Interception Log ────────────────────────────────────────────
    private val networkLog = ArrayDeque<NetworkLogEntry>(MAX_NETWORK_LOG)
    private val networkLogLock = Any()

    // ─── Null payload fallback ───────────────────────────────────────────────
    private val nullPayloadErrorJson = """{"ok":false,"result":"","error":"Empty JS payload"}"""

    // ────────────────────────────────────────────────────────────────────────
    // Data Classes
    // ────────────────────────────────────────────────────────────────────────

    data class BrowserSession(
        val id: String,
        val label: String,
        var webView: WebView?,
        var currentUrl: String = "",
        var title: String = "",
        val history: ArrayDeque<String> = ArrayDeque(),
        val cookieManager: CookieManager = CookieManager.getInstance(),
        val pendingJs: ConcurrentHashMap<String, CompletableDeferred<String>> = ConcurrentHashMap(),
        val createdAt: Long = System.currentTimeMillis(),
        var lastActivity: Long = System.currentTimeMillis(),
        var pageLoadCount: AtomicInteger = AtomicInteger(0),
        var userAgent: String? = null
    )

    data class NetworkLogEntry(
        val timestamp: Long,
        val sessionId: String,
        val url: String,
        val resourceType: String,
        val blocked: Boolean
    )

    data class ElementInfo(
        val tag: String,
        val id: String,
        val classes: String,
        val text: String,
        val attributes: Map<String, String>,
        val visible: Boolean
    )

    // ────────────────────────────────────────────────────────────────────────
    // Constants
    // ────────────────────────────────────────────────────────────────────────

    companion object {
        private const val NAVIGATE_TIMEOUT_MS = 35_000L
        private const val JS_TIMEOUT_MS = 15_000L
        private const val WAIT_ELEMENT_POLL_MS = 500L
        private const val WAIT_ELEMENT_MAX_MS = 20_000L
        private const val MAX_JS_OUTPUT = 12_000
        private const val MAX_SCREENSHOT_B64 = 500_000   // ~375KB image
        private const val MAX_SESSIONS = 5
        private const val MAX_NETWORK_LOG = 200
        private const val SESSION_IDLE_TIMEOUT_MS = 10 * 60 * 1000L // 10 min

        // Common user-agent presets
        val USER_AGENTS = mapOf(
            "chrome_desktop" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
            "chrome_mobile" to "Mozilla/5.0 (Linux; Android 14; Pixel 8) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.6367.82 Mobile Safari/537.36",
            "firefox" to "Mozilla/5.0 (X11; Linux x86_64; rv:125.0) Gecko/20100101 Firefox/125.0",
            "safari" to "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_4) " +
                    "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Safari/605.1.15",
            "googlebot" to "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)"
        )

        // Domains to block for speed/privacy (ad/tracker networks)
        private val DEFAULT_BLOCKED_DOMAINS = setOf(
            "doubleclick.net", "googlesyndication.com", "googletagmanager.com",
            "google-analytics.com", "facebook.net", "connect.facebook.net",
            "hotjar.com", "mouseflow.com", "fullstory.com", "amplitude.com",
            "segment.io", "mixpanel.com", "intercom.io"
        )
    }

    // ════════════════════════════════════════════════════════════════════════
    // Tool Definitions
    // ════════════════════════════════════════════════════════════════════════

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "headless_browser",
            description = """
Advanced headless browser engine (Android WebView) for interacting with dynamic websites.
Supports multi-session tabs, form filling, cookie management, screenshots, element waiting,
network interception, structured data extraction, and full JavaScript execution.

ACTIONS:
  Session Management:
    new_session       - Open a new browser session/tab (returns session_id)
    list_sessions     - List all open sessions
    switch_session    - Switch to a different session by session_id
    close_session     - Close a specific session and free resources
    destroy_all       - Destroy all sessions

  Navigation:
    navigate          - Load a URL in the current (or specified) session
    back              - Go back in browser history
    forward           - Go forward in browser history
    reload            - Reload the current page
    get_current_url   - Get the current URL of the active session
    get_title         - Get the current page title

  DOM Interaction:
    execute_js        - Execute arbitrary JavaScript and return result
    get_dom           - Get clean text content of the page body
    get_html          - Get full outer HTML of the page (truncated)
    find_element      - Find element info by CSS selector
    find_all          - Find all matching elements as JSON array
    click             - Click an element matching a CSS selector
    type_text         - Type text into a focused input field
    fill_form         - Fill multiple form fields at once (JSON map)
    submit_form       - Submit a form matching a selector
    scroll_to         - Scroll to an element or coordinates
    select_option     - Select option in a <select> element

  Waiting:
    wait_for_element  - Poll until a CSS selector appears in the DOM
    wait_for_text     - Poll until specific text appears on the page
    wait_ms           - Wait a fixed number of milliseconds

  Data & Media:
    screenshot        - Capture the page as a base64 PNG
    extract_links     - Extract all <a href> links from the page
    extract_table     - Extract an HTML table as JSON
    extract_meta      - Extract meta tags and OG data

  Cookies & Storage:
    get_cookies       - Get all cookies for the current domain
    set_cookie        - Set a cookie for the current domain
    clear_cookies     - Clear all cookies
    get_local_storage - Read a localStorage key
    set_local_storage - Write a localStorage key

  Configuration:
    set_user_agent    - Set a custom User-Agent (preset or custom string)
    inject_css        - Inject persistent CSS into the page
    inject_js_onload  - Inject JS that runs on every page load (not yet navigated)
    get_network_log   - Get the intercepted network request log
    set_block_domains - Override the list of blocked domains

WORKFLOW EXAMPLE:
  1. new_session → get session_id
  2. navigate url=https://example.com session_id=...
  3. wait_for_element selector="#login-form"
  4. fill_form fields={"#username":"user","#password":"pass"}
  5. click selector="button[type=submit]"
  6. wait_for_element selector=".dashboard"
  7. extract_table selector="table.data"
  8. close_session
            """.trimIndent(),
            parameters = listOf(
                ToolParameter("action", "string",
                    "Action to perform (see description for full list)", true),
                ToolParameter("url", "string",
                    "URL to navigate to (for navigate action)", false),
                ToolParameter("session_id", "string",
                    "Session ID to target. If omitted, uses the active session.", false),
                ToolParameter("label", "string",
                    "Human-readable label for a new session", false),
                ToolParameter("js_code", "string",
                    "JavaScript code to execute (for execute_js)", false),
                ToolParameter("selector", "string",
                    "CSS selector for DOM operations", false),
                ToolParameter("text", "string",
                    "Text to type, wait for, or value to set", false),
                ToolParameter("fields", "string",
                    "JSON object mapping CSS selectors to values (for fill_form)", false),
                ToolParameter("cookie_name", "string",
                    "Cookie name (for get/set_cookie)", false),
                ToolParameter("cookie_value", "string",
                    "Cookie value (for set_cookie)", false),
                ToolParameter("storage_key", "string",
                    "localStorage key (for get/set_local_storage)", false),
                ToolParameter("storage_value", "string",
                    "localStorage value (for set_local_storage)", false),
                ToolParameter("user_agent", "string",
                    "User-Agent preset key or custom string (for set_user_agent). " +
                    "Presets: chrome_desktop, chrome_mobile, firefox, safari, googlebot", false),
                ToolParameter("css", "string",
                    "CSS to inject (for inject_css)", false),
                ToolParameter("x", "string",
                    "X coordinate for scroll_to (optional)", false),
                ToolParameter("y", "string",
                    "Y coordinate for scroll_to (optional)", false),
                ToolParameter("timeout_ms", "string",
                    "Timeout override in milliseconds for wait operations", false),
                ToolParameter("index", "string",
                    "Table index if multiple tables match (default: 0)", false),
                ToolParameter("domains", "string",
                    "JSON array of domains to block (for set_block_domains)", false),
                ToolParameter("option_value", "string",
                    "Value to select in a <select> (for select_option)", false),
                ToolParameter("quality", "string",
                    "Screenshot JPEG quality 1-100 (default: 80)", false)
            )
        )
    )

    // ════════════════════════════════════════════════════════════════════════
    // Main Dispatch
    // ════════════════════════════════════════════════════════════════════════

    suspend fun execute(action: String, args: Map<String, String>): ToolExecutionResult {
        val sessionId = args["session_id"] ?: activeSessionId

        return when (action.lowercase().trim()) {

            // ── Session Management ──────────────────────────────────────────
            "new_session"    -> newSession(args["label"])
            "list_sessions"  -> listSessions()
            "switch_session" -> switchSession(
                args["session_id"] ?: return missingArg("session_id"))
            "close_session"  -> closeSession(
                args["session_id"] ?: return missingArg("session_id"))
            "destroy_all", "destroy" -> destroyAll()

            // ── Navigation ──────────────────────────────────────────────────
            "navigate" -> navigate(
                url = args["url"] ?: return missingArg("url"),
                sessionId = sessionId
            )
            "back"           -> browserBack(sessionId)
            "forward"        -> browserForward(sessionId)
            "reload"         -> browserReload(sessionId)
            "get_current_url" -> getCurrentUrl(sessionId)
            "get_title"      -> getTitle(sessionId)

            // ── DOM Operations ──────────────────────────────────────────────
            "execute_js"     -> executeJs(
                jsCode = args["js_code"] ?: return missingArg("js_code"),
                sessionId = sessionId
            )
            "get_dom"        -> getDom(sessionId)
            "get_html"       -> getHtml(sessionId)
            "find_element"   -> findElement(
                selector = args["selector"] ?: return missingArg("selector"),
                sessionId = sessionId
            )
            "find_all"       -> findAll(
                selector = args["selector"] ?: return missingArg("selector"),
                sessionId = sessionId
            )
            "click"          -> clickElement(
                selector = args["selector"] ?: return missingArg("selector"),
                sessionId = sessionId
            )
            "type_text"      -> typeText(
                text = args["text"] ?: return missingArg("text"),
                selector = args["selector"],
                sessionId = sessionId
            )
            "fill_form"      -> fillForm(
                fieldsJson = args["fields"] ?: return missingArg("fields"),
                sessionId = sessionId
            )
            "submit_form"    -> submitForm(
                selector = args["selector"] ?: return missingArg("selector"),
                sessionId = sessionId
            )
            "scroll_to"      -> scrollTo(
                selector = args["selector"],
                x = args["x"]?.toIntOrNull(),
                y = args["y"]?.toIntOrNull(),
                sessionId = sessionId
            )
            "select_option"  -> selectOption(
                selector = args["selector"] ?: return missingArg("selector"),
                value = args["option_value"] ?: return missingArg("option_value"),
                sessionId = sessionId
            )

            // ── Waiting ─────────────────────────────────────────────────────
            "wait_for_element" -> waitForElement(
                selector = args["selector"] ?: return missingArg("selector"),
                timeoutMs = args["timeout_ms"]?.toLongOrNull() ?: WAIT_ELEMENT_MAX_MS,
                sessionId = sessionId
            )
            "wait_for_text"  -> waitForText(
                text = args["text"] ?: return missingArg("text"),
                timeoutMs = args["timeout_ms"]?.toLongOrNull() ?: WAIT_ELEMENT_MAX_MS,
                sessionId = sessionId
            )
            "wait_ms"        -> {
                val ms = args["text"]?.toLongOrNull()
                    ?: args["timeout_ms"]?.toLongOrNull()
                    ?: 1000L
                delay(ms.coerceIn(100, 10_000))
                ToolExecutionResult("⏱️ Waited ${ms}ms.")
            }

            // ── Data & Media ────────────────────────────────────────────────
            "screenshot"     -> takeScreenshot(
                quality = args["quality"]?.toIntOrNull()?.coerceIn(1, 100) ?: 80,
                sessionId = sessionId
            )
            "extract_links"  -> extractLinks(sessionId)
            "extract_table"  -> extractTable(
                selector = args["selector"],
                index = args["index"]?.toIntOrNull() ?: 0,
                sessionId = sessionId
            )
            "extract_meta"   -> extractMeta(sessionId)

            // ── Cookies & Storage ───────────────────────────────────────────
            "get_cookies"    -> getCookies(sessionId)
            "set_cookie"     -> setCookie(
                name = args["cookie_name"] ?: return missingArg("cookie_name"),
                value = args["cookie_value"] ?: return missingArg("cookie_value"),
                sessionId = sessionId
            )
            "clear_cookies"  -> clearCookies()
            "get_local_storage" -> getLocalStorage(
                key = args["storage_key"] ?: return missingArg("storage_key"),
                sessionId = sessionId
            )
            "set_local_storage" -> setLocalStorage(
                key = args["storage_key"] ?: return missingArg("storage_key"),
                value = args["storage_value"] ?: return missingArg("storage_value"),
                sessionId = sessionId
            )

            // ── Configuration ───────────────────────────────────────────────
            "set_user_agent" -> setUserAgent(
                ua = args["user_agent"] ?: return missingArg("user_agent"),
                sessionId = sessionId
            )
            "inject_css"     -> injectCss(
                css = args["css"] ?: return missingArg("css"),
                sessionId = sessionId
            )
            "get_network_log" -> getNetworkLog()
            "set_block_domains" -> setBlockDomains(
                domainsJson = args["domains"] ?: return missingArg("domains"))

            else -> ToolExecutionResult(
                "Unknown headless_browser action: '$action'.\n" +
                "See tool description for the full list of supported actions.",
                isError = true
            )
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Session Management
    // ════════════════════════════════════════════════════════════════════════

    private suspend fun newSession(label: String? = null): ToolExecutionResult {
        if (sessions.size >= MAX_SESSIONS) {
            // Auto-evict the oldest idle session
            val oldest = sessions.values.minByOrNull { it.lastActivity }
            if (oldest != null) {
                destroySession(oldest.id)
            } else {
                return ToolExecutionResult(
                    "Maximum $MAX_SESSIONS sessions open. Close one first.", isError = true)
            }
        }
        val id = "session_${sessionCounter.incrementAndGet()}_${System.currentTimeMillis()}"
        val sessionLabel = label ?: "Tab ${sessionCounter.get()}"
        val wv = createWebView()
        val session = BrowserSession(id = id, label = sessionLabel, webView = wv)
        sessions[id] = session
        activeSessionId = id
        return ToolExecutionResult("✅ New session created.\nsession_id: $id\nlabel: $sessionLabel")
    }

    private fun listSessions(): ToolExecutionResult {
        if (sessions.isEmpty()) return ToolExecutionResult("No open sessions.")
        val sb = StringBuilder("Open sessions (${sessions.size}/$MAX_SESSIONS):\n")
        sessions.values.sortedBy { it.createdAt }.forEachIndexed { i, s ->
            val active = if (s.id == activeSessionId) " ← ACTIVE" else ""
            sb.appendLine("  [${i + 1}] ${s.id}$active")
            sb.appendLine("       Label: ${s.label}")
            sb.appendLine("       URL: ${s.currentUrl.ifBlank { "(not navigated)" }}")
            sb.appendLine("       Title: ${s.title.ifBlank { "(none)" }}")
        }
        return ToolExecutionResult(sb.toString().trimEnd())
    }

    private fun switchSession(id: String): ToolExecutionResult {
        return if (sessions.containsKey(id)) {
            activeSessionId = id
            val s = sessions[id]!!
            ToolExecutionResult("✅ Switched to session: $id (${s.label}) — URL: ${s.currentUrl.ifBlank { "(not navigated)" }}")
        } else {
            ToolExecutionResult("Session '$id' not found.", isError = true)
        }
    }

    private suspend fun closeSession(id: String): ToolExecutionResult {
        destroySession(id)
        if (activeSessionId == id) {
            activeSessionId = sessions.keys.firstOrNull()
        }
        return ToolExecutionResult("✅ Session '$id' closed.")
    }

    private suspend fun destroyAll(): ToolExecutionResult {
        val count = sessions.size
        sessions.keys.toList().forEach { destroySession(it) }
        activeSessionId = null
        return ToolExecutionResult("✅ All $count session(s) destroyed.")
    }

    private suspend fun destroySession(id: String) {
        val session = sessions.remove(id) ?: return
        withContext(Dispatchers.Main) {
            session.pendingJs.values.forEach { d ->
                if (!d.isCompleted) d.completeExceptionally(
                    IllegalStateException("Session '$id' was destroyed.")
                )
            }
            session.pendingJs.clear()
            session.webView?.destroy()
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // WebView Factory
    // ════════════════════════════════════════════════════════════════════════

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun createWebView(): WebView = withContext(Dispatchers.Main) {
        WebView(appContext).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                blockNetworkImage = true          // speed up invisible loads
                allowFileAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                cacheMode = WebSettings.LOAD_NO_CACHE
                userAgentString = USER_AGENTS["chrome_desktop"]
            }
            // Accept all cookies (needed for most SPA logins)
            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
            cm.setAcceptThirdPartyCookies(this, true)
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Navigation
    // ════════════════════════════════════════════════════════════════════════

    private suspend fun navigate(url: String, sessionId: String? = null): ToolExecutionResult {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return ToolExecutionResult("Invalid URL — must start with http:// or https://", isError = true)
        }
        val session = resolveSession(sessionId) ?: return noSession()

        return try {
            withTimeout(NAVIGATE_TIMEOUT_MS) {
                val deferred = CompletableDeferred<String>()

                withContext(Dispatchers.Main) {
                    session.webView!!.webViewClient = buildWebViewClient(session, deferred)
                    session.webViewinterceptorClient?.let {
                        // already set by set_block_domains — client is preserved via session field
                    }
                    session.webView!!.loadUrl(url)
                }

                val landedUrl = deferred.await()
                // Extra buffer for JS frameworks (React, Vue, Angular) to hydrate
                delay(1_200)

                session.currentUrl = landedUrl
                session.title = withContext(Dispatchers.Main) { session.webView?.title ?: "" }
                session.history.addLast(landedUrl)
                session.lastActivity = System.currentTimeMillis()
                session.pageLoadCount.incrementAndGet()

                ToolExecutionResult(
                    "✅ Navigated to: $landedUrl\n" +
                    "Title: ${session.title}\n" +
                    "Session: ${session.id}"
                )
            }
        } catch (e: TimeoutCancellationException) {
            // Return partial success — page may have loaded partially
            val partialUrl = withContext(Dispatchers.Main) { session.webView?.url ?: url }
            session.currentUrl = partialUrl
            ToolExecutionResult(
                "⚠️ Navigation timed out after ${NAVIGATE_TIMEOUT_MS / 1000}s. " +
                "Page may have partially loaded at: $partialUrl"
            )
        } catch (e: Exception) {
            ToolExecutionResult("Navigation failed: ${e.message}", isError = true)
        }
    }

    private fun buildWebViewClient(
        session: BrowserSession,
        deferred: CompletableDeferred<String>
    ) = object : WebViewClient() {

        override fun onPageFinished(view: WebView?, loadedUrl: String?) {
            if (!deferred.isCompleted) deferred.complete(loadedUrl ?: session.currentUrl)
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?
        ) {
            if (request?.isForMainFrame == true && !deferred.isCompleted) {
                deferred.completeExceptionally(
                    RuntimeException("WebView error: ${error?.description}")
                )
            }
        }

        override fun shouldInterceptRequest(
            view: WebView?,
            request: WebResourceRequest?
        ): WebResourceResponse? {
            val reqUrl = request?.url?.toString() ?: return null
            val blocked = blockedDomains.any { domain -> reqUrl.contains(domain) }
            synchronized(networkLogLock) {
                if (networkLog.size >= MAX_NETWORK_LOG) networkLog.removeFirst()
                networkLog.addLast(NetworkLogEntry(
                    timestamp = System.currentTimeMillis(),
                    sessionId = session.id,
                    url = reqUrl.take(200),
                    resourceType = request.method ?: "unknown",
                    blocked = blocked
                ))
            }
            return if (blocked) {
                WebResourceResponse("text/plain", "utf-8",
                    "".byteInputStream())
            } else null
        }
    }

    private suspend fun browserBack(sessionId: String? = null): ToolExecutionResult {
        val session = resolveSession(sessionId) ?: return noSession()
        return withContext(Dispatchers.Main) {
            if (session.webView?.canGoBack() == true) {
                session.webView?.goBack()
                delay(800)
                session.currentUrl = session.webView?.url ?: session.currentUrl
                ToolExecutionResult("✅ Went back. Current URL: ${session.currentUrl}")
            } else {
                ToolExecutionResult("No history to go back to.", isError = true)
            }
        }
    }

    private suspend fun browserForward(sessionId: String? = null): ToolExecutionResult {
        val session = resolveSession(sessionId) ?: return noSession()
        return withContext(Dispatchers.Main) {
            if (session.webView?.canGoForward() == true) {
                session.webView?.goForward()
                delay(800)
                session.currentUrl = session.webView?.url ?: session.currentUrl
                ToolExecutionResult("✅ Went forward. Current URL: ${session.currentUrl}")
            } else {
                ToolExecutionResult("No forward history.", isError = true)
            }
        }
    }

    private suspend fun browserReload(sessionId: String? = null): ToolExecutionResult {
        val session = resolveSession(sessionId) ?: return noSession()
        return withContext(Dispatchers.Main) {
            session.webView?.reload()
            delay(1_500)
            ToolExecutionResult("✅ Page reloaded: ${session.currentUrl}")
        }
    }

    private fun getCurrentUrl(sessionId: String? = null): ToolExecutionResult {
        val session = resolveSession(sessionId) ?: return noSession()
        return ToolExecutionResult(session.currentUrl.ifBlank { "(not navigated yet)" })
    }

    private fun getTitle(sessionId: String? = null): ToolExecutionResult {
        val session = resolveSession(sessionId) ?: return noSession()
        return ToolExecutionResult(session.title.ifBlank { "(no title)" })
    }

    // ════════════════════════════════════════════════════════════════════════
    // JavaScript Execution Engine
    // ════════════════════════════════════════════════════════════════════════

    private suspend fun executeJs(
        jsCode: String,
        sessionId: String? = null
    ): ToolExecutionResult {
        val session = resolveSession(sessionId) ?: return noSession()
        if (session.currentUrl.isBlank()) {
            return ToolExecutionResult(
                "No page loaded in session '${session.id}'. Navigate first.", isError = true)
        }
        if (jsCode.isBlank()) return ToolExecutionResult("Empty JavaScript.", isError = true)

        return try {
            withTimeout(JS_TIMEOUT_MS) {
                val token = UUID.randomUUID().toString()
                val deferred = CompletableDeferred<String>()
                session.pendingJs[token] = deferred

                val wrapped = buildJsWrapper(jsCode, token)
                withContext(Dispatchers.Main) {
                    session.webView?.evaluateJavascript(wrapped, null)
                }

                val raw = deferred.await()
                session.pendingJs.remove(token)
                session.lastActivity = System.currentTimeMillis()

                val parsed = runCatching { JSONObject(raw) }.getOrNull()
                    ?: return@withTimeout ToolExecutionResult("JS returned invalid payload.", isError = true)

                if (!parsed.optBoolean("ok", false)) {
                    return@withTimeout ToolExecutionResult(
                        "JS Error: ${parsed.optString("error", "Unknown")}",
                        isError = true
                    )
                }

                val value = parsed.optString("result", "")
                val truncated = value.length > MAX_JS_OUTPUT
                val output = if (truncated) value.take(MAX_JS_OUTPUT) +
                        "\n[TRUNCATED — ${value.length} chars total]" else value

                ToolExecutionResult(output, truncated = truncated)
            }
        } catch (e: TimeoutCancellationException) {
            ToolExecutionResult("JS execution timed out after ${JS_TIMEOUT_MS / 1000}s.", isError = true)
        } catch (e: Exception) {
            ToolExecutionResult("JS execution failed: ${e.message}", isError = true)
        }
    }

    private fun buildJsWrapper(code: String, token: String): String {
        val quotedCode  = JSONObject.quote(code)
        val quotedToken = JSONObject.quote(token)
        return """
(function(){
  var __t=$quotedToken, __c=$quotedCode;
  var __send=function(ok,v){
    try{
      OmniDevBridge.deliver(__t, JSON.stringify({
        ok:!!ok,
        result:ok?(v==null?"":String(v)):"",
        error:ok?"":((v&&v.stack)||String(v)||"Unknown error")
      }));
    }catch(e){}
  };
  try{
    var __r=(0,eval)(__c);
    (typeof Promise!=="undefined"?Promise.resolve(__r):
      {then:function(f){try{f(__r);}catch(e){throw e;} return this;},
       catch:function(f){return this;}})
    .then(function(v){__send(true,v);})
    .catch(function(e){__send(false,e);});
  }catch(e){__send(false,e);}
  return null;
})();
        """.trimIndent()
    }

    // ════════════════════════════════════════════════════════════════════════
    // DOM Operations
    // ════════════════════════════════════════════════════════════════════════

    private suspend fun getDom(sessionId: String? = null) = executeJs(
        "(function(){" +
        "  var el=document.body;" +
        "  return el?(el.innerText||el.textContent||'').substring(0,$MAX_JS_OUTPUT):'(empty)';" +
        "})()",
        sessionId
    )

    private suspend fun getHtml(sessionId: String? = null) = executeJs(
        "(function(){" +
        "  var h=document.documentElement.outerHTML||'';" +
        "  return h.length>$MAX_JS_OUTPUT?h.substring(0,$MAX_JS_OUTPUT)+'[TRUNCATED]':h;" +
        "})()",
        sessionId
    )

    private suspend fun findElement(selector: String, sessionId: String? = null): ToolExecutionResult {
        val safeSelector = selector.replace("\"", "\\\"")
        val js = """
(function(){
  var el=document.querySelector("$safeSelector");
  if(!el) return null;
  var attrs={};
  for(var i=0;i<el.attributes.length;i++){
    attrs[el.attributes[i].name]=el.attributes[i].value;
  }
  var r=el.getBoundingClientRect();
  return JSON.stringify({
    tag:el.tagName.toLowerCase(),
    id:el.id||'',
    classes:el.className||'',
    text:(el.innerText||el.textContent||'').trim().substring(0,200),
    attributes:attrs,
    visible:(r.width>0&&r.height>0&&el.offsetParent!==null),
    rect:{top:r.top,left:r.left,width:r.width,height:r.height}
  });
})()
        """.trimIndent()
        val result = executeJs(js, sessionId)
        if (result.isError) return result
        if (result.output.trim() == "null" || result.output.isBlank())
            return ToolExecutionResult("No element found matching: $selector", isError = true)
        return result
    }

    private suspend fun findAll(selector: String, sessionId: String? = null): ToolExecutionResult {
        val safeSelector = selector.replace("\"", "\\\"")
        val js = """
(function(){
  var els=Array.from(document.querySelectorAll("$safeSelector")).slice(0,50);
  return JSON.stringify(els.map(function(el){
    var r=el.getBoundingClientRect();
    return {
      tag:el.tagName.toLowerCase(),
      id:el.id||'',
      text:(el.innerText||el.textContent||'').trim().substring(0,100),
      visible:(r.width>0&&r.height>0),
      value:el.value||null
    };
  }));
})()
        """.trimIndent()
        val result = executeJs(js, sessionId)
        if (result.isError) return result
        return try {
            val arr = JSONArray(result.output)
            ToolExecutionResult("Found ${arr.length()} element(s) matching '$selector':\n${result.output}")
        } catch (_: Exception) { result }
    }

    private suspend fun clickElement(selector: String, sessionId: String? = null): ToolExecutionResult {
        val safeSelector = selector.replace("\"", "\\\"")
        val js = """
(function(){
  var el=document.querySelector("$safeSelector");
  if(!el) return "ERROR: Element not found";
  el.scrollIntoView({behavior:'instant',block:'center'});
  el.focus();
  el.click();
  var ev=new MouseEvent('click',{bubbles:true,cancelable:true,view:window});
  el.dispatchEvent(ev);
  return "clicked:" + el.tagName.toLowerCase() + "#" + (el.id||'?');
})()
        """.trimIndent()
        val result = executeJs(js, sessionId)
        if (result.isError) return result
        if (result.output.startsWith("ERROR:"))
            return ToolExecutionResult(result.output, isError = true)
        delay(300)
        return ToolExecutionResult("✅ Clicked element: ${result.output}")
    }

    private suspend fun typeText(
        text: String,
        selector: String? = null,
        sessionId: String? = null
    ): ToolExecutionResult {
        val safeText     = text.replace("\\", "\\\\").replace("\"", "\\\"")
        val safeSelector = selector?.replace("\"", "\\\"")
        val focusCode = if (safeSelector != null) {
            "var el=document.querySelector(\"$safeSelector\"); if(el){el.focus();}else{return \"ERROR:selector not found\";}"
        } else {
            "var el=document.activeElement;"
        }
        val js = """
(function(){
  $focusCode
  var val=el.value||'';
  el.value=val+"$safeText";
  el.dispatchEvent(new Event('input',{bubbles:true}));
  el.dispatchEvent(new Event('change',{bubbles:true}));
  return "typed " + "$safeText".length + " chars into <" + el.tagName.toLowerCase() + ">";
})()
        """.trimIndent()
        val result = executeJs(js, sessionId)
        if (result.isError) return result
        if (result.output.startsWith("ERROR:"))
            return ToolExecutionResult(result.output, isError = true)
        return ToolExecutionResult("✅ ${result.output}")
    }

    private suspend fun fillForm(
        fieldsJson: String,
        sessionId: String? = null
    ): ToolExecutionResult {
        val fields = runCatching { JSONObject(fieldsJson) }.getOrElse {
            return ToolExecutionResult("Invalid JSON in fields: ${it.message}", isError = true)
        }
        val results = mutableListOf<String>()
        for (selector in fields.keys()) {
            val value = fields.optString(selector, "")
            val safeSelector = selector.replace("\"", "\\\"")
            val safeValue    = value.replace("\\", "\\\\").replace("\"", "\\\"")
            val js = """
(function(){
  var el=document.querySelector("$safeSelector");
  if(!el) return "ERROR: '$safeSelector' not found";
  el.focus();
  if(el.tagName.toLowerCase()==='select'){
    el.value="$safeValue";
    el.dispatchEvent(new Event('change',{bubbles:true}));
    return "select set";
  } else if(el.type==='checkbox'||el.type==='radio'){
    el.checked=("$safeValue"==='true'||"$safeValue"==='1'||"$safeValue"===el.value);
    el.dispatchEvent(new Event('change',{bubbles:true}));
    return "checkbox set";
  } else {
    el.value="$safeValue";
    el.dispatchEvent(new Event('input',{bubbles:true}));
    el.dispatchEvent(new Event('change',{bubbles:true}));
    return "value set";
  }
})()
            """.trimIndent()
            val r = executeJs(js, sessionId)
            results.add("$selector → ${if (r.isError) "ERROR: ${r.output}" else r.output}")
        }
        return ToolExecutionResult("Form fill results:\n" + results.joinToString("\n"))
    }

    private suspend fun submitForm(selector: String, sessionId: String? = null): ToolExecutionResult {
        val safeSelector = selector.replace("\"", "\\\"")
        val js = """
(function(){
  var el=document.querySelector("$safeSelector");
  if(!el) return "ERROR: form not found";
  var form=el.closest('form')||(el.tagName.toLowerCase()==='form'?el:null);
  if(!form) return "ERROR: no form ancestor found";
  form.dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}));
  if(form.requestSubmit){form.requestSubmit();}else{form.submit();}
  return "submitted";
})()
        """.trimIndent()
        val result = executeJs(js, sessionId)
        if (result.isError) return result
        if (result.output.startsWith("ERROR:"))
            return ToolExecutionResult(result.output, isError = true)
        delay(1_000)
        return ToolExecutionResult("✅ Form submitted.")
    }

    private suspend fun scrollTo(
        selector: String? = null,
        x: Int? = null,
        y: Int? = null,
        sessionId: String? = null
    ): ToolExecutionResult {
        val js = when {
            selector != null -> {
                val safe = selector.replace("\"", "\\\"")
                """
(function(){
  var el=document.querySelector("$safe");
  if(!el) return "ERROR: not found";
  el.scrollIntoView({behavior:'smooth',block:'center'});
  return "scrolled to element";
})()
                """.trimIndent()
            }
            x != null || y != null -> {
                val sx = x ?: 0
                val sy = y ?: 0
                "window.scrollTo({left:$sx,top:$sy,behavior:'smooth'}); 'scrolled to ($sx,$sy)'"
            }
            else -> "window.scrollTo(0,document.body.scrollHeight); 'scrolled to bottom'"
        }
        val result = executeJs(js, sessionId)
        delay(400)
        return result
    }

    private suspend fun selectOption(
        selector: String,
        value: String,
        sessionId: String? = null
    ): ToolExecutionResult {
        val safeSelector = selector.replace("\"", "\\\"")
        val safeValue    = value.replace("\"", "\\\"")
        val js = """
(function(){
  var el=document.querySelector("$safeSelector");
  if(!el||el.tagName.toLowerCase()!=='select') return "ERROR: <select> not found at selector";
  el.value="$safeValue";
  el.dispatchEvent(new Event('change',{bubbles:true}));
  return "selected: " + el.value;
})()
        """.trimIndent()
        return executeJs(js, sessionId)
    }

    // ════════════════════════════════════════════════════════════════════════
    // Waiting / Polling
    // ════════════════════════════════════════════════════════════════════════

    private suspend fun waitForElement(
        selector: String,
        timeoutMs: Long,
        sessionId: String? = null
    ): ToolExecutionResult {
        val safe     = selector.replace("\"", "\\\"")
        val deadline = System.currentTimeMillis() + timeoutMs
        var elapsed  = 0L
        while (System.currentTimeMillis() < deadline) {
            val result = executeJs(
                "!!document.querySelector(\"$safe\")", sessionId)
            if (!result.isError && result.output.trim() == "true")
                return ToolExecutionResult("✅ Element appeared: $selector (${elapsed}ms)")
            delay(WAIT_ELEMENT_POLL_MS)
            elapsed += WAIT_ELEMENT_POLL_MS
        }
        return ToolExecutionResult(
            "Timeout: '$selector' did not appear within ${timeoutMs}ms.", isError = true)
    }

    private suspend fun waitForText(
        text: String,
        timeoutMs: Long,
        sessionId: String? = null
    ): ToolExecutionResult {
        val safeText = text.replace("\"", "\\\"")
        val deadline = System.currentTimeMillis() + timeoutMs
        var elapsed  = 0L
        while (System.currentTimeMillis() < deadline) {
            val result = executeJs(
                "document.body.innerText.includes(\"$safeText\")", sessionId)
            if (!result.isError && result.output.trim() == "true")
                return ToolExecutionResult("✅ Text appeared: \"$text\" (${elapsed}ms)")
            delay(WAIT_ELEMENT_POLL_MS)
            elapsed += WAIT_ELEMENT_POLL_MS
        }
        return ToolExecutionResult(
            "Timeout: text '$text' did not appear within ${timeoutMs}ms.", isError = true)
    }

    // ════════════════════════════════════════════════════════════════════════
    // Data Extraction
    // ════════════════════════════════════════════════════════════════════════

    private suspend fun extractLinks(sessionId: String? = null): ToolExecutionResult {
        val js = """
(function(){
  var links=Array.from(document.querySelectorAll('a[href]')).slice(0,100);
  return JSON.stringify(links.map(function(a){
    return {text:(a.innerText||'').trim().substring(0,80), href:a.href};
  }));
})()
        """.trimIndent()
        val result = executeJs(js, sessionId)
        if (result.isError) return result
        return try {
            val arr = JSONArray(result.output)
            val sb  = StringBuilder("Found ${arr.length()} link(s):\n")
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                sb.appendLine("  [${i + 1}] ${obj.optString("text", "(no text)").padEnd(40)} → ${obj.optString("href")}")
            }
            ToolExecutionResult(sb.toString().trimEnd())
        } catch (_: Exception) { result }
    }

    private suspend fun extractTable(
        selector: String?,
        index: Int,
        sessionId: String? = null
    ): ToolExecutionResult {
        val querySel = if (selector != null) {
            val safe = selector.replace("\"", "\\\"")
            "document.querySelector(\"$safe\")"
        } else {
            "document.querySelectorAll('table')[$index]"
        }
        val js = """
(function(){
  var tbl=$querySel;
  if(!tbl||tbl.tagName.toLowerCase()!=='table') return "ERROR: no table found";
  var rows=Array.from(tbl.querySelectorAll('tr'));
  var data=rows.map(function(row){
    return Array.from(row.querySelectorAll('th,td')).map(function(cell){
      return (cell.innerText||cell.textContent||'').trim();
    });
  }).filter(function(r){return r.length>0;});
  return JSON.stringify(data);
})()
        """.trimIndent()
        val result = executeJs(js, sessionId)
        if (result.isError) return result
        if (result.output.startsWith("ERROR:"))
            return ToolExecutionResult(result.output, isError = true)
        return try {
            val matrix = JSONArray(result.output)
            val sb = StringBuilder()
            for (i in 0 until matrix.length()) {
                val row = matrix.getJSONArray(i)
                val cells = (0 until row.length()).map { row.getString(it) }
                sb.appendLine(cells.joinToString(" | "))
            }
            ToolExecutionResult(sb.toString().trimEnd())
        } catch (_: Exception) { result }
    }

    private suspend fun extractMeta(sessionId: String? = null): ToolExecutionResult {
        val js = """
(function(){
  var metas={};
  document.querySelectorAll('meta[name],meta[property]').forEach(function(m){
    var key=m.getAttribute('name')||m.getAttribute('property');
    var val=m.getAttribute('content');
    if(key&&val) metas[key]=val.substring(0,200);
  });
  metas['_title']=document.title||'';
  metas['_canonical']=(document.querySelector('link[rel=canonical]')||{}).href||'';
  return JSON.stringify(metas,null,2);
})()
        """.trimIndent()
        return executeJs(js, sessionId)
    }

    // ════════════════════════════════════════════════════════════════════════
    // Screenshot
    // ════════════════════════════════════════════════════════════════════════

    private suspend fun takeScreenshot(
        quality: Int = 80,
        sessionId: String? = null
    ): ToolExecutionResult {
        val session = resolveSession(sessionId) ?: return noSession()
        return withContext(Dispatchers.Main) {
            try {
                val wv = session.webView
                    ?: return@withContext ToolExecutionResult("WebView not initialised.", isError = true)
                val bmp = Bitmap.createBitmap(
                    wv.width.coerceAtLeast(1),
                    wv.height.coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888
                )
                val canvas = android.graphics.Canvas(bmp)
                wv.draw(canvas)
                val baos = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, quality, baos)
                bmp.recycle()
                val b64 = android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)
                if (b64.length > MAX_SCREENSHOT_B64) {
                    ToolExecutionResult(
                        "Screenshot captured but too large to return (${b64.length} chars). " +
                        "Try reducing quality or use get_dom instead.",
                        isError = true
                    )
                } else {
                    ToolExecutionResult(
                        "Screenshot (JPEG, quality=$quality):\n" +
                        "data:image/jpeg;base64,$b64"
                    )
                }
            } catch (e: Exception) {
                ToolExecutionResult("Screenshot failed: ${e.message}", isError = true)
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Cookies & Storage
    // ════════════════════════════════════════════════════════════════════════

    private fun getCookies(sessionId: String? = null): ToolExecutionResult {
        val session = resolveSession(sessionId) ?: return noSession()
        val url = session.currentUrl.ifBlank {
            return ToolExecutionResult("No URL loaded — navigate first to read cookies.", isError = true)
        }
        val raw = CookieManager.getInstance().getCookie(url)
            ?: return ToolExecutionResult("No cookies found for: $url")
        val pairs = raw.split(";").map { it.trim() }
        val sb = StringBuilder("Cookies for $url (${pairs.size}):\n")
        pairs.forEach { sb.appendLine("  $it") }
        return ToolExecutionResult(sb.toString().trimEnd())
    }

    private fun setCookie(name: String, value: String, sessionId: String? = null): ToolExecutionResult {
        val session = resolveSession(sessionId) ?: return noSession()
        val url = session.currentUrl.ifBlank {
            return ToolExecutionResult("Navigate to a page first before setting cookies.", isError = true)
        }
        CookieManager.getInstance().setCookie(url, "$name=$value")
        CookieManager.getInstance().flush()
        return ToolExecutionResult("✅ Cookie set: $name=$value on $url")
    }

    private fun clearCookies(): ToolExecutionResult {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        return ToolExecutionResult("✅ All cookies cleared.")
    }

    private suspend fun getLocalStorage(key: String, sessionId: String? = null) =
        executeJs("localStorage.getItem(${JSONObject.quote(key)})", sessionId)

    private suspend fun setLocalStorage(
        key: String, value: String, sessionId: String? = null
    ): ToolExecutionResult {
        val js = "localStorage.setItem(${JSONObject.quote(key)}, ${JSONObject.quote(value)}); 'set'"
        val result = executeJs(js, sessionId)
        return if (!result.isError) ToolExecutionResult("✅ localStorage[$key] set.")
        else result
    }

    // ════════════════════════════════════════════════════════════════════════
    // Configuration
    // ════════════════════════════════════════════════════════════════════════

    private var blockedDomains: Set<String> = DEFAULT_BLOCKED_DOMAINS

    private suspend fun setUserAgent(ua: String, sessionId: String? = null): ToolExecutionResult {
        val session = resolveSession(sessionId) ?: return noSession()
        val resolved = USER_AGENTS[ua] ?: ua
        withContext(Dispatchers.Main) {
            session.webView?.settings?.userAgentString = resolved
        }
        session.userAgent = resolved
        return ToolExecutionResult("✅ User-Agent set to:\n$resolved")
    }

    private suspend fun injectCss(css: String, sessionId: String? = null): ToolExecutionResult {
        val safeCss = css.replace("\"", "\\\"").replace("\n", " ")
        val js = """
(function(){
  var s=document.createElement('style');
  s.setAttribute('data-omnidev','injected');
  s.textContent="$safeCss";
  document.head.appendChild(s);
  return 'CSS injected (' + "$safeCss".length + ' chars)';
})()
        """.trimIndent()
        return executeJs(js, sessionId)
    }

    private fun getNetworkLog(): ToolExecutionResult {
        val entries = synchronized(networkLogLock) { networkLog.toList() }
        if (entries.isEmpty()) return ToolExecutionResult("Network log is empty.")
        val sb = StringBuilder("Network log (${entries.size} entries):\n")
        entries.takeLast(50).forEach { e ->
            val status = if (e.blocked) "🚫 BLOCKED" else "✅"
            val ts = java.text.SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(e.timestamp))
            sb.appendLine("  [$ts] $status [${e.resourceType}] ${e.url.take(120)}")
        }
        return ToolExecutionResult(sb.toString().trimEnd())
    }

    private fun setBlockDomains(domainsJson: String): ToolExecutionResult {
        return try {
            val arr = JSONArray(domainsJson)
            blockedDomains = (0 until arr.length()).map { arr.getString(it) }.toSet()
            ToolExecutionResult("✅ Blocking ${blockedDomains.size} domain(s): ${blockedDomains.joinToString()}")
        } catch (e: Exception) {
            ToolExecutionResult("Invalid JSON array for domains: ${e.message}", isError = true)
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // JS Bridge
    // ════════════════════════════════════════════════════════════════════════

    /**
     * يجب حقن هذا الـ bridge في كل WebView عند إنشائه.
     * يُعاد الاستخدام عبر كل الجلسات.
     */
    inner class JsBridge(private val session: BrowserSession) {
        @JavascriptInterface
        fun deliver(token: String, payload: String?) {
            val deferred = session.pendingJs.remove(token) ?: return
            if (!deferred.isCompleted)
                deferred.complete(payload ?: nullPayloadErrorJson)
        }
    }

    // WebView extension to hold its injected interception client ref
    private var WebView.interceptorClientRef: WebViewClient?
        get() = getTag(R.id.webview_intercept_client_tag) as? WebViewClient
        set(v) { setTag(R.id.webview_intercept_client_tag, v) }

    // ════════════════════════════════════════════════════════════════════════
    // Helpers
    // ════════════════════════════════════════════════════════════════════════

    private fun resolveSession(sessionId: String? = null): BrowserSession? {
        val id = sessionId ?: activeSessionId ?: return null
        val session = sessions[id] ?: return null
        // Attach JsBridge if not yet attached
        val wv = session.webView ?: return null
        try {
            wv.addJavascriptInterface(JsBridge(session), "OmniDevBridge")
        } catch (_: Exception) { /* Already added */ }
        return session
    }

    private fun noSession() = ToolExecutionResult(
        "No active browser session. Call action='new_session' first.", isError = true)

    private fun missingArg(name: String) = ToolExecutionResult(
        "Missing required argument: $name", isError = true)

    // Extension property placeholder (requires a proper resource ID in production)
    private val BrowserSession.webViewinterceptorClient: WebViewClient? get() = null
}

// Placeholder for resource ID — define in res/values/ids.xml in the actual project:
// <item name="webview_intercept_client_tag" type="id"/>
private object R {
    object id {
        const val webview_intercept_client_tag = 0x7f09_0001
    }
}
