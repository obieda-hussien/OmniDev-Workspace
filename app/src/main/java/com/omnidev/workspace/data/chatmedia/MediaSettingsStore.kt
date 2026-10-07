package com.omnidev.workspace.data.chatmedia

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

class MediaSettingsStore(context: Context) {
    private val context = context.applicationContext
    private val prefs = this.context.getSharedPreferences("chat-media-settings", Context.MODE_PRIVATE)
    fun get() = MediaPreferencesCodec.decode(prefs.getString("selections", null))
    fun observe() = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key == "selections") trySend(get()) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(get())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()
    fun update(kind: MediaKind, value: MediaConfig) = synchronized(gate) {
        MediaRequestPolicy.validate(kind, value)
        val updated = get().with(kind, value)
        check(prefs.edit().putString("selections", MediaPreferencesCodec.encode(updated)).commit()) { "Could not save media settings." }
        if (!value.enabled) MediaJobStore(context).active().filter { it.kind == kind.action }.forEach {
            MediaGenerationWorker.cancel(context, it.id)
        }
        updated
    }
    companion object { private val gate = Any() }
}
