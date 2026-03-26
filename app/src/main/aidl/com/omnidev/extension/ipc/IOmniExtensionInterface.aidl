package com.omnidev.extension.ipc;

interface IOmniExtensionInterface {
    // Returns JSON metadata describing extension capabilities.
    String getExtensionManifest();

    // Executes an extension action with JSON payload; returns JSON result/error.
    String executeAction(String actionName, String jsonPayload);
}
