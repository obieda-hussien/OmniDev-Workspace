package com.omnidev.workspace.ipc;

import android.os.Bundle;

/**
 * Binder contract implemented inside a Shizuku UserService process.
 *
 * The implementation runs with the UID granted by Shizuku (normally shell/2000,
 * or root/0 when Shizuku itself is started as root). This is intentionally kept
 * tiny: command execution happens in the privileged process while orchestration,
 * routing and policy stay in the main app process.
 */
interface IPrivilegedShellService {
    Bundle execute(String command, long timeoutMs);
    int getUid();
    String ping();

    // Reserved Shizuku UserService destroy transaction. Shizuku invokes this
    // when a tagged service is replaced/removed so stale privileged processes die.
    void destroy() = 16777114;
}
