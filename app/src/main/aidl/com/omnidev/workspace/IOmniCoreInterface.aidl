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
 *
 * ── Command execution ──────────────────────────────────────────────────────
 */
interface IOmniCoreInterface {

    /**
     * Execute a privileged shell command via Shizuku / root.
     * Returns true if the command exited with code 0.
     */
    boolean executeSystemCommand(String command);

    /**
     * Execute a privileged shell command and return the full stdout output.
     * Returns an error string prefixed with "ERROR:" on failure.
     */
    String executeCommandWithOutput(String command);

    // ── Device state ────────────────────────────────────────────────────

    /**
     * Query a snapshot of the device state (build fingerprint, Shizuku
     * availability, foreground package, battery level, etc.).
     *
     * @return A JSON string with device-state fields, or an error message.
     */
    String getDeviceState();

    /**
     * Read a system property via `getprop`.
     * @param key  The property key (e.g. "ro.build.version.sdk").
     * @return The property value, or empty string if not found.
     */
    String getSystemProperty(String key);

    /**
     * Set a system property via `setprop` (requires root or ADB privilege).
     * @return true on success.
     */
    boolean setSystemProperty(String key, String value);

    /**
     * Dump a system service via `dumpsys <service>`.
     * @param service  e.g. "battery", "wifi", "package", "activity".
     * @return Trimmed dumpsys output (max 8 KB).
     */
    String dumpSysInfo(String service);

    /**
     * List running processes via `ps -A`.
     * @return Multi-line process list (max 6 KB).
     */
    String listRunningProcesses();

    // ── Android settings ────────────────────────────────────────────────

    /**
     * Read an Android system setting via `settings get <namespace> <key>`.
     * @param namespace  "system", "secure", or "global".
     */
    String readSetting(String namespace, String key);

    /**
     * Write an Android system setting via `settings put <namespace> <key> <value>`.
     * @return true on success.
     */
    boolean writeSetting(String namespace, String key, String value);

    /**
     * List all keys in a settings namespace.
     * @param namespace  "system", "secure", or "global".
     * @return Newline-separated list of key=value pairs (max 6 KB).
     */
    String listSettings(String namespace);

    // ── Package management ──────────────────────────────────────────────

    /**
     * List installed packages, optionally filtered by name.
     * @param filter  Substring filter; pass empty string for all packages.
     * @return Newline-separated package name list.
     */
    String queryPackages(String filter);

    /**
     * Install an APK via `pm install`.
     * @param apkPath  Full path to the APK file.
     * @return true if installation succeeded.
     */
    boolean installApk(String apkPath);

    /**
     * Uninstall a package via `pm uninstall`.
     * @return true if uninstallation succeeded.
     */
    boolean uninstallPackage(String packageName);

    /**
     * Grant a dangerous/special permission to a package via `pm grant`.
     * @return true on success.
     */
    boolean grantPermission(String packageName, String permission);

    /**
     * Revoke a permission from a package via `pm revoke`.
     * @return true on success.
     */
    boolean revokePermission(String packageName, String permission);

    /**
     * Dump detailed package info via `pm dump <packageName>`.
     * @return Trimmed pm dump output (max 8 KB).
     */
    String getPackageInfo(String packageName);

    // ── App / activity control ──────────────────────────────────────────

    /**
     * Force-stop an application via `am force-stop <packageName>`.
     * @return true on success.
     */
    boolean forceStopApp(String packageName);

    /**
     * Start an activity or service component via `am start`.
     * @param component  Full component name (e.g. "com.pkg/.MainActivity") or
     *                   action URI like "android.intent.action.VIEW -d https://…".
     * @return true if the am command exited with code 0.
     */
    boolean launchComponent(String component);

    /**
     * Send a broadcast via `am broadcast -a <action>`.
     * @param action  Intent action string.
     * @return true on success.
     */
    boolean sendBroadcast(String action);

    // ── Input injection ─────────────────────────────────────────────────

    /**
     * Inject a [KeyEvent] via Shizuku's `input keyevent` shell command.
     * @param event The KeyEvent to inject; must not be null.
     */
    void injectInputEvent(in KeyEvent event);

    /**
     * Inject a tap gesture at the given screen coordinates.
     * @param x  X coordinate in pixels.
     * @param y  Y coordinate in pixels.
     * @return true on success.
     */
    boolean injectTap(int x, int y);

    /**
     * Inject a swipe gesture.
     * @param x1, y1        Start coordinates.
     * @param x2, y2        End coordinates.
     * @param durationMs    Swipe duration in milliseconds.
     * @return true on success.
     */
    boolean injectSwipe(int x1, int y1, int x2, int y2, int durationMs);

    /**
     * Type text via `input text "<text>"`.
     * @return true on success.
     */
    boolean injectText(String text);

    // ── Screen capture ──────────────────────────────────────────────────

    /**
     * Capture the current screen via `screencap -p <outputPath>`.
     * @param outputPath  Destination file path (e.g. /data/local/tmp/shot.png).
     * @return The resolved output path on success, or "ERROR: …" on failure.
     */
    String captureScreen(String outputPath);

    // ── Service / hardware control ──────────────────────────────────────

    /**
     * Control a hardware service via `svc <service> enable|disable`.
     * @param service  One of: wifi, data, bluetooth, nfc, power.
     * @param action   "enable" or "disable".
     * @return true on success.
     */
    boolean controlService(String service, String action);

    // ── Window manager ──────────────────────────────────────────────────

    /**
     * Get or set the display size/density via `wm size` / `wm density`.
     * @param subCommand  "size", "density", "size reset", or "density reset".
     * @param value       New value (e.g. "1080x1920" or "420"); pass empty to read.
     * @return Command output on success, or "ERROR: …" on failure.
     */
    String windowManager(String subCommand, String value);
}
