package com.omnidev.workspace.ipc;

import com.omnidev.workspace.ipc.IOmniResponseCallback;

interface IOmniCoreInterface {
    int getSystemStatus();
    void executeSystemCommand(String command, String contextData);
    void askAgentSilent(String prompt);
    void streamAgentResponse(String prompt, IOmniResponseCallback callback);
}
