package com.omnidev.workspace.data.assistant

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.provider.Settings
import android.speech.RecognitionService

/** Exclude our bridge: selecting Omni as assistant may also select its recognizer. */
object SpeechRecognizerProvider {
    @Suppress("DEPRECATION")
    fun find(context: Context): ComponentName? = candidates(context).firstOrNull()

    @Suppress("DEPRECATION")
    fun candidates(context: Context): List<ComponentName> {
        val available = context.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .filter { it.serviceInfo.packageName != context.packageName && it.serviceInfo.exported }
            .sortedByDescending { it.serviceInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0 }
            .map { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
        val selected = runCatching {
            Settings.Secure.getString(context.contentResolver, "voice_recognition_service")
                ?.let(ComponentName::unflattenFromString)
        }.getOrNull()
        return listOfNotNull(selected?.takeIf { it in available }) + available.filter { it != selected }
    }
}
