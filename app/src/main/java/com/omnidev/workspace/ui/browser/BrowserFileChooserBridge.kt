package com.omnidev.workspace.ui.browser

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import java.lang.ref.WeakReference

/**
 * Bridges WebView <input type=file> requests to Android's system document picker.
 *
 * Why this exists instead of keeping the callback inside WebChromeClient:
 * MainActivity owns activity-result delivery, while BrowserChromeClient can be
 * replaced whenever a headless session becomes visible. The bridge keeps exactly
 * one pending chooser alive and always completes/cancels stale callbacks.
 */
object BrowserFileChooserBridge {
    const val REQUEST_CODE = 0x4F4D // "OM"

    @Volatile
    private var pendingCallback: ValueCallback<Array<Uri>>? = null

    @Volatile
    private var ownerActivity = WeakReference<Activity>(null)

    fun launch(
        activity: Activity,
        callback: ValueCallback<Array<Uri>>,
        params: WebChromeClient.FileChooserParams?
    ): Boolean {
        pendingCallback?.onReceiveValue(null)
        pendingCallback = callback
        ownerActivity = WeakReference(activity)

        val primaryIntent = runCatching { params?.createIntent() }.getOrNull()
            ?: Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = bestMimeType(params)
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params?.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE)
            }

        primaryIntent.addCategory(Intent.CATEGORY_OPENABLE)
        primaryIntent.putExtra(
            Intent.EXTRA_ALLOW_MULTIPLE,
            params?.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE
        )
        val accepted = params?.acceptTypes
            ?.filter { it.isNotBlank() }
            ?.toTypedArray()
            .orEmpty()
        if (accepted.size == 1) {
            primaryIntent.type = accepted.first()
        } else if (accepted.size > 1) {
            primaryIntent.type = "*/*"
            primaryIntent.putExtra(Intent.EXTRA_MIME_TYPES, accepted)
        }

        return runCatching {
            activity.startActivityForResult(primaryIntent, REQUEST_CODE)
            true
        }.getOrElse {
            pendingCallback?.onReceiveValue(null)
            pendingCallback = null
            false
        }
    }

    fun handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != REQUEST_CODE) return false

        val callback = pendingCallback
        pendingCallback = null
        val activity = ownerActivity.get()
        ownerActivity.clear()

        if (callback == null) return true
        if (resultCode != Activity.RESULT_OK) {
            callback.onReceiveValue(null)
            return true
        }

        val uris = collectUris(data)
        if (activity != null) {
            uris.forEach { uri ->
                runCatching {
                    activity.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
            }
        }
        callback.onReceiveValue(uris.ifEmpty { null })
        return true
    }

    fun cancelPending() {
        pendingCallback?.onReceiveValue(null)
        pendingCallback = null
        ownerActivity.clear()
    }

    private fun collectUris(data: Intent?): Array<Uri> {
        if (data == null) return emptyArray()
        val result = LinkedHashSet<Uri>()
        data.data?.let(result::add)
        val clipData: ClipData? = data.clipData
        if (clipData != null) {
            for (i in 0 until clipData.itemCount) {
                clipData.getItemAt(i).uri?.let(result::add)
            }
        }
        return result.toTypedArray()
    }

    private fun bestMimeType(params: WebChromeClient.FileChooserParams?): String {
        val accepted = params?.acceptTypes?.filter { it.isNotBlank() }.orEmpty()
        return if (accepted.size == 1) accepted.first() else "*/*"
    }
}
