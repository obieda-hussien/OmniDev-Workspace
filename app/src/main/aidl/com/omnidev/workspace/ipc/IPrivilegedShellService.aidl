package com.omnidev.workspace.ipc;

import android.os.Bundle;

/**
 * Binder contract implemented inside a Shizuku UserService process.
 *
 * The implementation runs with the UID granted by Shizuku (normally shell/2000,
 * or root/0 when Shizuku itself is started as root). This is intentionally kept
 * tiny: command execution happens in the privileged process while orchestration,
 * routing and policy stay in the main app process.
 *
 * IMPORTANT: Shizuku reserves transaction id 16777114 for destroy(). Android's
 * AIDL compiler requires transaction ids to be specified for either every method
 * or none of them, so the regular API methods use stable low ids as well.
 */
interface IPrivilegedShellService {
    Bundle execute(String command, long timeoutMs) = 1;
    int getUid() = 2;
    String ping() = 3;

    // Reserved Shizuku UserService destroy transaction. Shizuku invokes this
    // when a tagged service is replaced/removed so stale privileged processes die.
    void destroy() = 16777114;
}
