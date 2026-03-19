// IOmniCoreInterface.aidl
// Secure AIDL interface for OmniCoreService.
// Access is restricted to callers holding com.omnidev.permission.CONTROL_CORE.
package com.omnidev.workspace;

import android.view.KeyEvent;

/**
 * Core IPC interface exposed by OmniCoreService to trusted companion apps.
 *
 * All methods are synchronous from the binder-thread perspective; the
 * implementation dispatches them onto Coroutine scopes internally.
 */
interface IOmniCoreInterface {

    /**
     * Execute a privileged shell command via Shizuku / root.
     *
     * @param command The shell command to run (e.g. "pm list packages").
     * @return true if the command was dispatched successfully; false on error.
     */
    boolean executeSystemCommand(String command);

    /**
     * Query a snapshot of the device state (build fingerprint, Shizuku
     * availability, foreground package, battery level, etc.).
     *
     * @return A JSON string with device-state fields, or an error message.
     */
    String getDeviceState();

    /**
     * Inject a KeyEvent into the system input dispatcher (requires the
     * INJECT_EVENTS signature permission granted via Shizuku).
     *
     * @param event The KeyEvent to inject; must not be null.
     */
    void injectInputEvent(in KeyEvent event);
}
