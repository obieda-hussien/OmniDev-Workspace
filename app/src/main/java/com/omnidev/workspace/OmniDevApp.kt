package com.omnidev.workspace

import android.app.Application
import com.omnidev.workspace.data.debug.CrashHandler
import com.omnidev.workspace.data.debug.DebugLogManager

/**
 * OmniDev Workspace Application class.
 * Initializes application-wide dependencies and services.
 */
class OmniDevApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Initialise the debug log directory before installing the crash handler
        // so that the first crash can be written to disk immediately.
        DebugLogManager.init(applicationContext)
        CrashHandler.install()
    }

    companion object {
        /** Application singleton for accessing context where DI isn't available. */
        lateinit var instance: OmniDevApp
            private set
    }
}
