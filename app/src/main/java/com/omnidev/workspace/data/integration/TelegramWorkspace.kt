package com.omnidev.workspace.data.integration

internal object TelegramWorkspace {
    const val SETTINGS_PATH = "OmniDev Settings → Linked accounts → Telegram → Project folder"
    const val DOCUMENT_AUTHORITY = "com.android.externalstorage.documents"

    private val volumeId = Regex("[A-Za-z0-9_-]+")

    fun folderPath(documentId: String): String? {
        val volume = documentId.substringBefore(':')
        val folder = documentId.substringAfter(':', "")
        if (!volumeId.matches(volume) ||
            folder.split('/').any { it.isBlank() || it == "." || it == ".." }
        ) return null
        val storage = if (volume == "primary") "/storage/emulated/0" else "/storage/$volume"
        return "$storage/$folder"
    }

    fun missingScope(mode: String) =
        "⚠️ Open $SETTINGS_PATH, choose a specific device folder, then send /mode $mode again."
}
