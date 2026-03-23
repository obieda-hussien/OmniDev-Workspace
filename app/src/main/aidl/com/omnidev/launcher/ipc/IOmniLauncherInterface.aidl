package com.omnidev.launcher.ipc;

interface IOmniLauncherInterface {
    boolean openAppDrawer();
    boolean closeAppDrawer();
    boolean launchPackage(String packageName);
    boolean goToHomeScreen();
    boolean performLauncherAction(String actionName);
    boolean renderOmniWidget(String widgetId, String composeJson);
}
