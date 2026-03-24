package com.omnidev.launcher.ipc;

/**
 * OmniDev Launcher IPC Contract — v2.
 *
 * All methods are one-way synchronous calls returning a boolean to indicate
 * whether the launcher accepted and dispatched the command.
 *
 * Changelog:
 *   v2  — added openWidgetPicker() for the AGI Brain Widget Picker integration.
 */
interface IOmniLauncherInterface {
    boolean openAppDrawer();
    boolean closeAppDrawer();
    boolean launchPackage(String packageName);
    boolean goToHomeScreen();
    boolean performLauncherAction(String actionName);
    boolean renderOmniWidget(String widgetId, String composeJson);
    boolean removeOmniWidget(String widgetId);
    boolean clearAllOmniWidgets();

    /**
     * Opens the system/launcher Widget Picker overlay, allowing the user (or agent)
     * to browse and add home-screen widgets.
     *
     * @return true  if the launcher successfully dispatched the widget-picker intent.
     *         false if the launcher does not support this action or an error occurred.
     */
    boolean openWidgetPicker();
}
