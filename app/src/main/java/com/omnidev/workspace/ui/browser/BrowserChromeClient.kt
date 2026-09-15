package com.omnidev.workspace.ui.browser

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.EditText
import androidx.core.content.ContextCompat

/**
 * Human-facing Chrome client for WebViews shown in Browser Viewer.
 *
 * Headless agent sessions deliberately stay non-interactive, but as soon as a
 * session is attached to the viewer we must stop silently accepting website
 * dialogs and browser permissions. This client keeps site permissions explicit
 * and only exposes camera/microphone/geolocation after the matching Android
 * runtime permission is already granted to OmniDev.
 */
internal class BrowserChromeClient(
    private val activity: Activity
) : WebChromeClient() {

    override fun onJsAlert(
        view: WebView?,
        url: String?,
        message: String?,
        result: JsResult?
    ): Boolean {
        if (!activity.isUsable()) return false
        AlertDialog.Builder(activity)
            .setTitle(siteLabel(url))
            .setMessage(message.orEmpty())
            .setPositiveButton("OK") { _, _ -> result?.confirm() }
            .setOnCancelListener { result?.cancel() }
            .show()
        return true
    }

    override fun onJsConfirm(
        view: WebView?,
        url: String?,
        message: String?,
        result: JsResult?
    ): Boolean {
        if (!activity.isUsable()) return false
        AlertDialog.Builder(activity)
            .setTitle(siteLabel(url))
            .setMessage(message.orEmpty())
            .setPositiveButton("OK") { _, _ -> result?.confirm() }
            .setNegativeButton("Cancel") { _, _ -> result?.cancel() }
            .setOnCancelListener { result?.cancel() }
            .show()
        return true
    }

    override fun onJsPrompt(
        view: WebView?,
        url: String?,
        message: String?,
        defaultValue: String?,
        result: JsPromptResult?
    ): Boolean {
        if (!activity.isUsable()) return false
        val input = EditText(activity).apply {
            setText(defaultValue.orEmpty())
            setSelection(text.length)
            setSingleLine(false)
        }
        AlertDialog.Builder(activity)
            .setTitle(siteLabel(url))
            .setMessage(message.orEmpty())
            .setView(input)
            .setPositiveButton("OK") { _, _ -> result?.confirm(input.text.toString()) }
            .setNegativeButton("Cancel") { _, _ -> result?.cancel() }
            .setOnCancelListener { result?.cancel() }
            .show()
        return true
    }

    override fun onPermissionRequest(request: PermissionRequest?) {
        request ?: return
        if (!activity.isUsable()) {
            request.deny()
            return
        }

        activity.runOnUiThread {
            val allowedResources = request.resources.filter { resource ->
                when (resource) {
                    PermissionRequest.RESOURCE_VIDEO_CAPTURE ->
                        hasPermission(Manifest.permission.CAMERA)
                    PermissionRequest.RESOURCE_AUDIO_CAPTURE ->
                        hasPermission(Manifest.permission.RECORD_AUDIO)
                    else -> false
                }
            }

            if (allowedResources.isEmpty()) {
                request.deny()
                val needsCamera = request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                val needsMic = request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                val missing = buildList {
                    if (needsCamera && !hasPermission(Manifest.permission.CAMERA)) add("camera")
                    if (needsMic && !hasPermission(Manifest.permission.RECORD_AUDIO)) add("microphone")
                }
                if (missing.isNotEmpty()) {
                    AlertDialog.Builder(activity)
                        .setTitle("Site permission blocked")
                        .setMessage(
                            "Grant OmniDev ${missing.joinToString(" and ")} permission in Android first, then retry the website request."
                        )
                        .setPositiveButton("OK", null)
                        .show()
                }
                return@runOnUiThread
            }

            val labels = allowedResources.map {
                when (it) {
                    PermissionRequest.RESOURCE_VIDEO_CAPTURE -> "camera"
                    PermissionRequest.RESOURCE_AUDIO_CAPTURE -> "microphone"
                    else -> "device resource"
                }
            }.distinct().joinToString(" and ")

            AlertDialog.Builder(activity)
                .setTitle("Allow website access?")
                .setMessage("${request.origin.host ?: request.origin} wants to use your $labels.")
                .setPositiveButton("Allow") { _, _ ->
                    request.grant(allowedResources.toTypedArray())
                }
                .setNegativeButton("Block") { _, _ -> request.deny() }
                .setOnCancelListener { request.deny() }
                .show()
        }
    }

    override fun onGeolocationPermissionsShowPrompt(
        origin: String?,
        callback: GeolocationPermissions.Callback?
    ) {
        if (callback == null || !activity.isUsable()) return
        val granted = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
            hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (!granted) {
            callback.invoke(origin, false, false)
            AlertDialog.Builder(activity)
                .setTitle("Location blocked")
                .setMessage("Grant OmniDev location permission in Android first, then retry the website request.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        AlertDialog.Builder(activity)
            .setTitle("Allow website location?")
            .setMessage("${origin.orEmpty()} wants to access your location for this session.")
            .setPositiveButton("Allow") { _, _ -> callback.invoke(origin, true, false) }
            .setNegativeButton("Block") { _, _ -> callback.invoke(origin, false, false) }
            .setOnCancelListener { callback.invoke(origin, false, false) }
            .show()
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED

    private fun siteLabel(url: String?): String =
        runCatching { android.net.Uri.parse(url).host }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: "Website"

    private fun Activity.isUsable(): Boolean = !isFinishing && !isDestroyed
}
