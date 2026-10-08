package com.omnidev.workspace.ui.companion

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext

internal data class CompanionPreferences(val enabled: Boolean = true, val roaming: Boolean = true)
internal object CompanionPreferenceStore {
    fun preferences(context: Context): SharedPreferences = context.applicationContext.getSharedPreferences("omni_companion", Context.MODE_PRIVATE)
    fun read(prefs: SharedPreferences) = CompanionPreferences(prefs.getBoolean("enabled", true), prefs.getBoolean("roaming", true))
    fun write(context: Context, value: CompanionPreferences) {
        preferences(context).edit().putBoolean("enabled", value.enabled).putBoolean("roaming", value.roaming).apply()
    }
}

@Composable
internal fun rememberCompanionPreferences(): State<CompanionPreferences> {
    val context = LocalContext.current.applicationContext
    val prefs = remember(context) { CompanionPreferenceStore.preferences(context) }
    val state = remember(prefs) { mutableStateOf(CompanionPreferenceStore.read(prefs)) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> state.value = CompanionPreferenceStore.read(prefs) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        state.value = CompanionPreferenceStore.read(prefs)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return state
}
