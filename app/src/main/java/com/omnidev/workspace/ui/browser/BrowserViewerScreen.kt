package com.omnidev.workspace.ui.browser

import android.webkit.WebView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cookie
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tab
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

// ── Terminal colors reused from AgentLiveConsole ──────────────────────────────
private val BrowserBg       = Color(0xFF0D1117)
private val BrowserGreen    = Color(0xFF3FB950)
private val BrowserCyan     = Color(0xFF79C0FF)
private val BrowserRed      = Color(0xFFF85149)
private val BrowserGray     = Color(0xFF8B949E)
private val BrowserYellow   = Color(0xFFD29922)
private val BrowserBgLight  = Color(0xFF161B22)
private val IncognitoBg     = Color(0xFF1A1025)
private val IncognitoAccent = Color(0xFF9D5CDB)

/**
 * Full-screen browser viewer that lets the user inspect and control the agent's
 * headless WebView sessions in real time.
 *
 * Features:
 *  - Session tab bar — switch between open sessions or create a new one
 *  - Embedded WebView — shows the actual live page the agent is visiting
 *  - Address bar with Back / Forward / Reload controls
 *  - "🤖 LIVE" badge when the agent is actively loading a page
 *  - Collapsible Cookie Manager panel (list, add, clear cookies)
 *  - Session actions: new session, close current session, clear localStorage
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserViewerScreen(
    viewModel: BrowserViewerViewModel,
    onNavigateBack: () -> Unit
) {
    val sessions by viewModel.sessions.collectAsState()
    val activeSession = sessions.firstOrNull { it.isActive } ?: sessions.firstOrNull()

    // Pass the Activity context to the manager on every composition so that
    // WebViews created for new sessions use it for hardware-accelerated rendering.
    // Also trigger a one-time refresh of any existing sessions that were created
    // before the Activity context was available (e.g., by the background agent).
    val ctx = LocalContext.current
    // Refresh whenever context/session snapshots change. This guarantees refresh
    // runs only after the latest Activity context is set.
    // It covers:
    // - first composition
    // - sessions that were still loading when the screen opened
    // - sessions created while the screen is already visible
    LaunchedEffect(ctx, sessions) {
        viewModel.updateActivityContext(ctx)
        viewModel.refreshWebViewsForDisplay()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "Browser Viewer",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        if (activeSession != null) {
                            Text(
                                activeSession.title.ifBlank { activeSession.currentUrl.ifBlank { "No page loaded" } },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Live badge — shown when agent is actively loading a page
                    if (activeSession?.isLoading == true) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = BrowserGreen.copy(alpha = 0.15f),
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Text(
                                "🤖 LIVE",
                                color = BrowserGreen,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BrowserBg,
                    titleContentColor = Color.White,
                    navigationIconContentColor = BrowserGray,
                    actionIconContentColor = BrowserGray
                )
            )
        },
        containerColor = BrowserBg
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // ── Session tabs ──────────────────────────────────────────────────
            if (sessions.isNotEmpty()) {
                SessionTabBar(
                    sessions = sessions,
                    onSwitchSession = { viewModel.switchSession(it) },
                    onCloseSession  = { viewModel.closeSession(it) },
                    onNewSession    = { viewModel.newSession() },
                    onNewIncognitoSession = { viewModel.newIncognitoSession() }
                )
            }

            // ── Address bar ───────────────────────────────────────────────────
            AddressBar(
                url = activeSession?.currentUrl ?: "",
                onNavigate = { viewModel.navigate(it) },
                onBack     = { viewModel.back() },
                onForward  = { viewModel.forward() },
                onReload   = { viewModel.reload() }
            )

            HorizontalDivider(color = BrowserBgLight)

            // ── WebView embed OR empty state ──────────────────────────────────
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (activeSession != null) {
                    // Read the live WebView outside the key so it participates in the key
                    // expression.  When refreshWebViewsForDisplay() replaces the WebView
                    // instance for a session, the key changes and Compose fully disposes
                    // the old AndroidView and creates a fresh one with the new WebView —
                    // which is the only way to make AndroidView adopt a different view.
                    val webView = viewModel.getWebView(activeSession.id)
                    key(activeSession.id, webView) {
                        if (webView != null) {
                            EmbeddedWebView(webView = webView)
                        } else {
                            NoWebViewPlaceholder(onNewSession = { viewModel.newSession() })
                        }
                    }
                } else {
                    NoSessionsPlaceholder(onNewSession = { viewModel.newSession() })
                }
            }

            HorizontalDivider(color = BrowserBgLight)

            // ── Cookie / Storage panel ────────────────────────────────────────
            BrowserToolsPanel(
                activeSession = activeSession,
                getCookies    = { viewModel.getCookies() },
                onSetCookie   = { name, value -> viewModel.setCookie(name, value) },
                onClearCookies        = { viewModel.clearCookies() },
                onClearLocalStorage   = { viewModel.clearLocalStorage() }
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Session Tab Bar
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SessionTabBar(
    sessions: List<com.omnidev.workspace.data.tools.HeadlessBrowserManager.BrowserSessionInfo>,
    onSwitchSession: (String) -> Unit,
    onCloseSession: (String) -> Unit,
    onNewSession: () -> Unit,
    onNewIncognitoSession: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BrowserBgLight)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        sessions.forEach { session ->
            SessionTab(
                session = session,
                onSwitch = { onSwitchSession(session.id) },
                onClose  = { onCloseSession(session.id) }
            )
        }
        // Normal new tab button
        IconButton(
            onClick = onNewSession,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(Icons.Filled.Add, contentDescription = "New session",
                tint = BrowserGray, modifier = Modifier.size(16.dp))
        }
        // Incognito new tab button
        IconButton(
            onClick = onNewIncognitoSession,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(Icons.Filled.VisibilityOff, contentDescription = "New incognito session",
                tint = IncognitoAccent, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun SessionTab(
    session: com.omnidev.workspace.data.tools.HeadlessBrowserManager.BrowserSessionInfo,
    onSwitch: () -> Unit,
    onClose: () -> Unit
) {
    val bgColor = when {
        session.isIncognito && session.isActive -> IncognitoBg
        session.isIncognito                     -> IncognitoBg.copy(alpha = 0.7f)
        session.isActive                        -> BrowserBg
        else                                    -> BrowserBgLight.copy(alpha = 0.5f)
    }
    val borderColor = when {
        session.isIncognito && session.isActive -> IncognitoAccent
        session.isIncognito                     -> IncognitoAccent.copy(alpha = 0.5f)
        session.isActive                        -> BrowserCyan
        else                                    -> Color.Transparent
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(6.dp))
            .clickable(onClick = onSwitch)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .widthIn(max = 180.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (session.isLoading) {
            // Small pulsing dot for loading indicator
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(BrowserGreen, CircleShape)
            )
        } else if (session.isIncognito) {
            Icon(
                Icons.Filled.VisibilityOff,
                contentDescription = null,
                tint = IncognitoAccent,
                modifier = Modifier.size(12.dp)
            )
        } else {
            Icon(Icons.Filled.Tab, null, tint = BrowserGray, modifier = Modifier.size(12.dp))
        }
        Text(
            text = session.label,
            color = if (session.isActive) Color.White else BrowserGray,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        Icon(
            Icons.Filled.Close,
            contentDescription = "Close session",
            tint = BrowserGray,
            modifier = Modifier
                .size(12.dp)
                .clickable(onClick = onClose)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Address Bar
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun AddressBar(
    url: String,
    onNavigate: (String) -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onReload: () -> Unit
) {
    var urlInput by rememberSaveable(url) { mutableStateOf(url) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BrowserBgLight)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Filled.ArrowBack, "Back", tint = BrowserGray, modifier = Modifier.size(18.dp))
        }
        IconButton(onClick = onForward, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Filled.ArrowForward, "Forward", tint = BrowserGray, modifier = Modifier.size(18.dp))
        }
        IconButton(onClick = onReload, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Filled.Refresh, "Reload", tint = BrowserGray, modifier = Modifier.size(18.dp))
        }
        OutlinedTextField(
            value = urlInput,
            onValueChange = { urlInput = it },
            modifier = Modifier.weight(1f),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                color = Color.White
            ),
            placeholder = {
                Text("Enter URL…", fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace, color = BrowserGray)
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onNavigate(urlInput) }),
            shape = RoundedCornerShape(8.dp)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Embedded WebView
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun EmbeddedWebView(webView: WebView) {
    // Detach the WebView from whatever parent it currently has before embedding
    DisposableEffect(webView) {
        val parent = webView.parent
        if (parent is android.view.ViewGroup) {
            parent.removeView(webView)
        }
        onDispose {
            // Detach without destroying — the agent still needs the WebView
            val p = webView.parent
            if (p is android.view.ViewGroup) {
                p.removeView(webView)
            }
        }
    }

    AndroidView(
        factory = { webView },
        modifier = Modifier.fillMaxSize()
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Empty / placeholder states
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun NoSessionsPlaceholder(onNewSession: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("No browser sessions", color = BrowserGray,
                fontFamily = FontFamily.Monospace, fontSize = 14.sp)
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onNewSession,
                colors = ButtonDefaults.buttonColors(containerColor = BrowserGreen)
            ) {
                Icon(Icons.Filled.Add, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("New Session", fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun NoWebViewPlaceholder(onNewSession: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("WebView not available for this session",
            color = BrowserGray, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Browser Tools Panel (Cookies + Storage)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun BrowserToolsPanel(
    activeSession: com.omnidev.workspace.data.tools.HeadlessBrowserManager.BrowserSessionInfo?,
    getCookies: () -> List<Pair<String, String>>,
    onSetCookie: (String, String) -> Unit,
    onClearCookies: () -> Unit,
    onClearLocalStorage: () -> Unit
) {
    var panelExpanded by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableStateOf(0) }

    Column(modifier = Modifier.fillMaxWidth().animateContentSize()) {
        // Panel header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(BrowserBgLight)
                .clickable { panelExpanded = !panelExpanded }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Cookie, null, tint = BrowserYellow, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text("Browser Tools", color = BrowserYellow,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            if (activeSession != null) {
                Spacer(Modifier.width(6.dp))
                Text("(${activeSession.label})", color = BrowserGray,
                    fontFamily = FontFamily.Monospace, fontSize = 10.sp)
            }
            Spacer(Modifier.weight(1f))
            Icon(
                if (panelExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                if (panelExpanded) "Collapse" else "Expand",
                tint = BrowserGray, modifier = Modifier.size(16.dp)
            )
        }

        AnimatedVisibility(
            visible = panelExpanded,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Column(modifier = Modifier.background(BrowserBg)) {
                // Tab selector
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = BrowserBgLight,
                    contentColor = BrowserCyan
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("Cookies", fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("Storage", fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
                    )
                    Tab(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        text = { Text("Session", fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
                    )
                }

                Box(modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp)) {
                    when (selectedTab) {
                        0 -> CookiesTab(
                            getCookies       = getCookies,
                            onSetCookie      = onSetCookie,
                            onClearCookies   = onClearCookies
                        )
                        1 -> StorageTab(onClearLocalStorage = onClearLocalStorage)
                        2 -> SessionInfoTab(activeSession = activeSession)
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Cookies tab
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun CookiesTab(
    getCookies: () -> List<Pair<String, String>>,
    onSetCookie: (String, String) -> Unit,
    onClearCookies: () -> Unit
) {
    val cookies = remember(getCookies) { getCookies() }
    var showAddDialog by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }

    Column(modifier = Modifier.padding(8.dp)) {
        // Action row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { showAddDialog = true },
                colors = ButtonDefaults.buttonColors(containerColor = BrowserCyan.copy(alpha = 0.2f)),
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Filled.Add, null, tint = BrowserCyan, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("Add Cookie", color = BrowserCyan, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
            Button(
                onClick = { showClearConfirm = true },
                colors = ButtonDefaults.buttonColors(containerColor = BrowserRed.copy(alpha = 0.15f)),
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Filled.Delete, null, tint = BrowserRed, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("Clear All", color = BrowserRed, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        }

        if (cookies.isEmpty()) {
            Text("No cookies", color = BrowserGray,
                fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                modifier = Modifier.padding(8.dp))
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(cookies) { (name, value) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(BrowserBgLight, RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(name, color = BrowserCyan,
                            fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                            modifier = Modifier.widthIn(max = 130.dp),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(" = ", color = BrowserGray, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        Text(value, color = BrowserYellow,
                            fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                            modifier = Modifier.weight(1f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddCookieDialog(
            onConfirm = { name, value ->
                onSetCookie(name, value)
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false }
        )
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Clear All Cookies?") },
            text = { Text("This will delete all cookies for all sessions.") },
            confirmButton = {
                TextButton(onClick = {
                    onClearCookies()
                    showClearConfirm = false
                }) { Text("Clear", color = BrowserRed) }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun AddCookieDialog(
    onConfirm: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Cookie", fontFamily = FontFamily.Monospace) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Name") }, singleLine = true
                )
                OutlinedTextField(
                    value = value, onValueChange = { value = it },
                    label = { Text("Value") }, singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onConfirm(name.trim(), value.trim()) },
                enabled = name.isNotBlank()
            ) { Text("Add", color = BrowserCyan) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Storage tab
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun StorageTab(onClearLocalStorage: () -> Unit) {
    var showConfirm by remember { mutableStateOf(false) }

    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("localStorage", color = BrowserGray,
            fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Button(
            onClick = { showConfirm = true },
            colors = ButtonDefaults.buttonColors(containerColor = BrowserRed.copy(alpha = 0.15f))
        ) {
            Icon(Icons.Filled.Delete, null, tint = BrowserRed, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text("Clear localStorage", color = BrowserRed, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        }
        Text(
            "Note: clears localStorage for the active session's current page.",
            color = BrowserGray.copy(alpha = 0.7f),
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp
        )
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("Clear localStorage?") },
            text = { Text("This clears all localStorage data on the current page.") },
            confirmButton = {
                TextButton(onClick = {
                    onClearLocalStorage()
                    showConfirm = false
                }) { Text("Clear", color = BrowserRed) }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Session info tab
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SessionInfoTab(
    activeSession: com.omnidev.workspace.data.tools.HeadlessBrowserManager.BrowserSessionInfo?
) {
    Column(
        modifier = Modifier.padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (activeSession == null) {
            Text("No active session", color = BrowserGray, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            return@Column
        }
        SessionInfoRow("ID", activeSession.id)
        SessionInfoRow("Label", activeSession.label)
        SessionInfoRow("URL", activeSession.currentUrl.ifBlank { "(not navigated)" })
        SessionInfoRow("Title", activeSession.title.ifBlank { "(no title)" })
        SessionInfoRow("Loading", if (activeSession.isLoading) "🟡 YES" else "✅ NO")
        SessionInfoRow("Page loads", activeSession.pageLoadCount.toString())
        SessionInfoRow("Mode", if (activeSession.isIncognito) "🕵️ INCOGNITO" else "Normal")
    }
}

@Composable
private fun SessionInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(label, color = BrowserGray, fontFamily = FontFamily.Monospace,
            fontSize = 11.sp, modifier = Modifier.width(80.dp))
        Text(value, color = Color.White, fontFamily = FontFamily.Monospace,
            fontSize = 11.sp, modifier = Modifier.weight(1f),
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
