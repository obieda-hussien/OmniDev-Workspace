package com.omnidev.workspace

import android.app.Application

/**
 * OmniDev Workspace Application class.
 * Initializes application-wide dependencies and services.
 */
class OmniDevApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        /** Application singleton for accessing context where DI isn't available. */
        lateinit var instance: OmniDevApp
            private set
    }
}
