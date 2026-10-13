package com.omnidev.workspace.data.admin

object LocalSpokenUnlock {
    fun visibleKind(): com.omnidev.workspace.data.voice.SpokenCredential.Kind? = null
    suspend fun attempt(context: android.content.Context, credential: com.omnidev.workspace.data.voice.SpokenCredential): Boolean { credential.close(); return false }
}
