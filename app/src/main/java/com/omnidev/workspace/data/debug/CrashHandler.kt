package com.omnidev.workspace.data.debug

/**
 * Global uncaught-exception handler that writes a crash report before the process terminates.
 *
 * Install once in [com.omnidev.workspace.OmniDevApp.onCreate] via [install].
 * The original handler (typically the Android default crash dialog handler) is preserved
 * and invoked after the log file is flushed so that crash dialogs and ADB `crash` tags
 * still appear normally.
 */
class CrashHandler private constructor(
    private val previousHandler: Thread.UncaughtExceptionHandler?
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            // Write synchronously — the process is about to die.
            DebugLogManager.writeCrash(throwable)
        } catch (_: Throwable) {
            // Never let the crash handler itself crash silently.
        }
        // Delegate to the original handler so Android can show its own crash UI.
        previousHandler?.uncaughtException(thread, throwable)
    }

    companion object {
        /** Install this handler as the default uncaught exception handler. */
        fun install() {
            val existing = Thread.getDefaultUncaughtExceptionHandler()
            if (existing is CrashHandler) return          // already installed
            Thread.setDefaultUncaughtExceptionHandler(CrashHandler(existing))
        }
    }
}
