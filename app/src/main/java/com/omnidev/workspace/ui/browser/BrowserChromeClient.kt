package com.omnidev.workspace.ui.browser

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.webkit.ConsoleMessage
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.webkit.WebViewRenderProcess
import androidx.webkit.WebViewRenderProcessClient
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Human-facing Chrome client for WebViews shown in Browser Viewer.
 *
 * Headless sessions are deliberately non-interactive. Once a session is attached
 * to Browser Viewer this client enables the browser features that require a
 * human boundary: file/image upload, JS dialogs, user-gesture popups,
 * camera/microphone, location and protected-media permission decisions.
 *
 * Site permissions are never silently granted. The Android runtime permission
 * must exist first, then the user still gets an origin-level prompt.
 */
internal class BrowserChromeClient(
    private val activity: Activity
) : WebChromeClient() {

    companion object {
        const val REQUEST_WEB_MEDIA_PERMISSIONS = 0x4F50
        const val REQUEST_WEB_LOCATION_PERMISSIONS = 0x4F51
    }

    private var rendererWatchdogInstalled = false
    private var rendererDialog: AlertDialog? = null

    override fun onProgressChanged(view: WebView?, newProgress: Int) {
        super.onProgressChanged(view, newProgress)
        val webView = view ?: return

        // target=_blank/window.open is enabled only so onCreateWindow can route
        // explicit user-gesture popups. JavaScript may not spawn windows silently.
        webView.settings.setSupportMultipleWindows(true)
        webView.settings.javaScriptCanOpenWindowsAutomatically = false
        installRendererWatchdog(webView)
    }

    private fun installRendererWatchdog(webView: WebView) {
        if (rendererWatchdogInstalled) return
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_VIEW_RENDERER_CLIENT_BASIC_USAGE)) return
        rendererWatchdogInstalled = true

        runCatching {
            WebViewCompat.setWebViewRenderProcessClient(
                webView,
                object : WebViewRenderProcessClient() {
                    override fun onRenderProcessUnresponsive(
                        view: WebView,
                        renderer: WebViewRenderProcess?
                    ) {
                        if (!BrowserDialogSafety.isUsable(activity, view) || rendererDialog != null) return
                        val dialog = AlertDialog.Builder(activity)
                            .setTitle("Page isn't responding")
                            .setMessage("The WebView renderer stopped responding. You can wait or reload the page without closing the tab.")
                            .setPositiveButton("Reload") { _, _ ->
                                view.stopLoading()
                                view.reload()
                            }
                            .setNegativeButton("Wait", null)
                            .create()
                        dialog.setOnDismissListener {
                            if (rendererDialog === dialog) rendererDialog = null
                        }
                        rendererDialog = dialog
                        BrowserDialogSafety.show(activity, dialog, view, onUnavailable = {
                            if (rendererDialog === dialog) rendererDialog = null
                        })
                    }

                    override fun onRenderProcessResponsive(
                        view: WebView,
                        renderer: WebViewRenderProcess?
                    ) {
                        BrowserDialogSafety.dismiss(rendererDialog)
                        rendererDialog = null
                    }
                }
            )
        }
    }

    override fun onJsAlert(
        view: WebView?,
        url: String?,
        message: String?,
        result: JsResult?
    ): Boolean {
        val settled = AtomicBoolean(false)
        val confirm = { if (settled.compareAndSet(false, true)) runCatching { result?.confirm() } }
        val cancel = { if (settled.compareAndSet(false, true)) runCatching { result?.cancel() } }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(siteLabel(url))
            .setMessage(message.orEmpty())
            .setPositiveButton("OK") { _, _ -> confirm() }
            .setOnCancelListener { cancel() }
            .create()
        BrowserDialogSafety.show(activity, dialog, view, onUnavailable = { cancel() })
        true
    }

    override fun onJsConfirm(
        view: WebView?,
        url: String?,
        message: String?,
        result: JsResult?
    ): Boolean {
        val settled = AtomicBoolean(false)
        val confirm = { if (settled.compareAndSet(false, true)) runCatching { result?.confirm() } }
        val cancel = { if (settled.compareAndSet(false, true)) runCatching { result?.cancel() } }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(siteLabel(url))
            .setMessage(message.orEmpty())
            .setPositiveButton("OK") { _, _ -> confirm() }
            .setNegativeButton("Cancel") { _, _ -> cancel() }
            .setOnCancelListener { cancel() }
            .create()
        BrowserDialogSafety.show(activity, dialog, view, onUnavailable = { cancel() })
        true
    }

    override fun onJsPrompt(
        view: WebView?,
        url: String?,
        message: String?,
        defaultValue: String?,
        result: JsPromptResult?
    ): Boolean {
        val input = EditText(activity).apply {
            setText(defaultValue.orEmpty())
            setSelection(text.length)
            setSingleLine(false)
        }
        val settled = AtomicBoolean(false)
        val confirm = { if (settled.compareAndSet(false, true)) runCatching {
            result?.confirm(input.text.toString())
        } }
        val cancel = { if (settled.compareAndSet(false, true)) runCatching { result?.cancel() } }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(siteLabel(url))
            .setMessage(message.orEmpty())
            .setView(input)
            .setPositiveButton("OK") { _, _ -> confirm() }
            .setNegativeButton("Cancel") { _, _ -> cancel() }
            .setOnCancelListener { cancel() }
            .create()
        BrowserDialogSafety.show(activity, dialog, view, onUnavailable = { cancel() })
        true
    }

    /** Enables <input type=file> / image attachment flows through Android SAF. */
    override fun onShowFileChooser(
        webView: WebView?,
        filePathCallback: ValueCallback<Array<Uri>>?,
        fileChooserParams: FileChooserParams?
    ): Boolean {
        val callback = filePathCallback ?: return false
        if (!BrowserDialogSafety.isUsable(activity, webView)) {
            callback.onReceiveValue(null)
            return true
        }
        val launched = BrowserFileChooserBridge.launch(activity, callback, fileChooserParams)
        if (!launched) {
            Toast.makeText(activity, "Could not open the Android file picker", Toast.LENGTH_LONG).show()
        }
        return true
    }

    /**
     * Handles target=_blank/window.open without allowing invisible script popups.
     * A user-gesture popup is resolved in a disposable child WebView and then
     * moved into the current visible tab, preserving the WebView cookie/profile.
     */
    override fun onCreateWindow(
        view: WebView?,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: Message?
    ): Boolean {
        val parent = view ?: return false
        val message = resultMsg ?: return false
        if (!isUserGesture || !BrowserDialogSafety.isUsable(activity, parent)) return false

        val transport = message.obj as? WebView.WebViewTransport ?: return false
        val popup = WebView(activity)
        var transferred = false

        fun transfer(url: String?): Boolean {
            if (!BrowserDialogSafety.isUsable(activity, parent)) {
                runCatching { popup.stopLoading(); popup.destroy() }
                return false
            }
            val target = url?.takeIf {
                it.startsWith("http://") || it.startsWith("https://")
            } ?: return false
            if (transferred) return true
            transferred = true
            parent.loadUrl(target)
            popup.stopLoading()
            popup.destroy()
            return true
        }

        popup.settings.javaScriptEnabled = false
        popup.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView?, request: WebResourceRequest?): Boolean {
                return transfer(request?.url?.toString())
            }

            @Deprecated("Deprecated in WebViewClient")
            override fun shouldOverrideUrlLoading(v: WebView?, url: String?): Boolean {
                return transfer(url)
            }

            override fun onPageStarted(v: WebView?, url: String?, favicon: Bitmap?) {
                if (url != null && url != "about:blank") transfer(url)
            }
        }
        transport.webView = popup
        message.sendToTarget()
        return true
    }

    override fun onCloseWindow(window: WebView?) {
        window?.let { candidate ->
            runCatching {
                candidate.stopLoading()
                candidate.destroy()
            }
        }
    }

    override fun onPermissionRequest(request: PermissionRequest?) {
        request ?: return
        if (!BrowserDialogSafety.isUsable(activity)) {
            denyPermissionRequest(request)
            return
        }
        synchronized(permissionLock) { pendingPermissionRequests.add(request) }

        activity.runOnUiThread {
            if (!isPermissionRequestPending(request) || !BrowserDialogSafety.isUsable(activity)) {
                finishPermissionRequest(request)
                denyPermissionRequest(request)
                return@runOnUiThread
            }
            val requested = request.resources.toSet()
            val androidPermissions = buildList {
                if (PermissionRequest.RESOURCE_VIDEO_CAPTURE in requested && !hasPermission(Manifest.permission.CAMERA)) {
                    add(Manifest.permission.CAMERA)
                }
                if (PermissionRequest.RESOURCE_AUDIO_CAPTURE in requested && !hasPermission(Manifest.permission.RECORD_AUDIO)) {
                    add(Manifest.permission.RECORD_AUDIO)
                }
            }

            if (androidPermissions.isNotEmpty()) {
                finishPermissionRequest(request)
                denyPermissionRequest(request)
                ActivityCompat.requestPermissions(
                    activity,
                    androidPermissions.distinct().toTypedArray(),
                    REQUEST_WEB_MEDIA_PERMISSIONS
                )
                Toast.makeText(
                    activity,
                    "Grant the Android permission, then retry the website action.",
                    Toast.LENGTH_LONG
                ).show()
                return@runOnUiThread
            }

            val allowedResources = request.resources.filter { resource ->
                when (resource) {
                    PermissionRequest.RESOURCE_VIDEO_CAPTURE -> hasPermission(Manifest.permission.CAMERA)
                    PermissionRequest.RESOURCE_AUDIO_CAPTURE -> hasPermission(Manifest.permission.RECORD_AUDIO)
                    PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID -> true
                    else -> false
                }
            }

            if (allowedResources.isEmpty()) {
                finishPermissionRequest(request)
                denyPermissionRequest(request)
                Toast.makeText(activity, "Unsupported website permission request blocked", Toast.LENGTH_SHORT).show()
                return@runOnUiThread
            }

            val labels = allowedResources.map {
                when (it) {
                    PermissionRequest.RESOURCE_VIDEO_CAPTURE -> "camera"
                    PermissionRequest.RESOURCE_AUDIO_CAPTURE -> "microphone"
                    PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID -> "protected media identity"
                    else -> "device resource"
                }
            }.distinct().joinToString(" and ")

            val dialog = AlertDialog.Builder(activity)
                .setTitle("Allow website access?")
                .setMessage("${request.origin.host ?: request.origin} wants to use your $labels.")
                .setPositiveButton("Allow") { _, _ ->
                    if (finishPermissionRequest(request)) {
                        runCatching { request.grant(allowedResources.toTypedArray()) }
                    }
                }
                .setNegativeButton("Block") { _, _ ->
                    if (finishPermissionRequest(request)) denyPermissionRequest(request)
                }
                .setOnCancelListener {
                    if (finishPermissionRequest(request)) denyPermissionRequest(request)
                }
                .create()
            synchronized(permissionLock) {
                if (request in pendingPermissionRequests) pendingPermissionDialogs[request] = dialog
            }
            BrowserDialogSafety.show(activity, dialog, onUnavailable = {
                if (finishPermissionRequest(request)) denyPermissionRequest(request)
            })
        }
    }

    override fun onPermissionRequestCanceled(request: PermissionRequest?) {
        request?.let { finishPermissionRequest(it) }
    }

    override fun onGeolocationPermissionsShowPrompt(
        origin: String?,
        callback: GeolocationPermissions.Callback?
    ) {
        if (callback == null) return
        val settled = AtomicBoolean(false)
        val allow = {
            if (settled.compareAndSet(false, true)) runCatching { callback.invoke(origin, true, false) }
        }
        val deny = {
            if (settled.compareAndSet(false, true)) runCatching { callback.invoke(origin, false, false) }
        }
        if (!BrowserDialogSafety.isUsable(activity)) {
            deny()
            return
        }
        val granted = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
            hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (!granted) {
            deny()
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                REQUEST_WEB_LOCATION_PERMISSIONS
            )
            Toast.makeText(activity, "Grant location permission, then retry the website request.", Toast.LENGTH_LONG).show()
            return
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle("Allow website location?")
            .setMessage("${origin.orEmpty()} wants to access your location for this session.")
            .setPositiveButton("Allow") { _, _ -> allow() }
            .setNegativeButton("Block") { _, _ -> deny() }
            .setOnCancelListener { deny() }
            .create()
        BrowserDialogSafety.show(activity, dialog, onUnavailable = { deny() })
    }

    override fun onGeolocationPermissionsHidePrompt() = Unit

    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
        return false
    }

    private val permissionLock = Any()
    private val pendingPermissionRequests: MutableSet<PermissionRequest> =
        Collections.newSetFromMap(WeakHashMap<PermissionRequest, Boolean>())
    private val pendingPermissionDialogs: MutableMap<PermissionRequest, AlertDialog> =
        WeakHashMap()

    private fun isPermissionRequestPending(request: PermissionRequest): Boolean =
        synchronized(permissionLock) { request in pendingPermissionRequests }

    private fun finishPermissionRequest(request: PermissionRequest): Boolean {
        val dialog = synchronized(permissionLock) {
            if (!pendingPermissionRequests.remove(request)) return false
            pendingPermissionDialogs.remove(request)
        }
        BrowserDialogSafety.dismiss(dialog)
        return true
    }

    private fun denyPermissionRequest(request: PermissionRequest) {
        runCatching { request.deny() }
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED

    private fun siteLabel(url: String?): String =
        runCatching { Uri.parse(url).host }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: "Website"
}
