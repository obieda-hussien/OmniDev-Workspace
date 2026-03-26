package com.omnidev.workspace.ipc;

interface IOmniResponseCallback {
    void onToken(String text);
    void onComplete(String fullResponse);
    void onError(String errorMessage);
}
