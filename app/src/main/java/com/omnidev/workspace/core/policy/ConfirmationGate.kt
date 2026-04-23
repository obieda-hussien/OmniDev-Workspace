package com.omnidev.workspace.core.policy

/**
 * Tier-agnostic gate that decides whether a privileged action may proceed.
 *
 * This is the *policy* layer of the Confirmation system. The *UI* layer
 * (the Compose dialog in [com.omnidev.workspace.ui.chat.ConfirmationGateDialog]
 * plus its `PendingConfirmation` state) is wired in `ChatViewModel` and adapted
 * into this interface so that tier policies can replace or wrap it.
 *
 * Every privileged callsite (Shizuku execution, God-mode file write/patch/delete,
 * Android Intent fire) calls [request] and proceeds only when it returns `true`.
 *
 * ## Flavor-specific behaviour
 *
 * | Tier  | Default gate behaviour                                                       |
 * |-------|------------------------------------------------------------------------------|
 * | LITE  | Always denies. Privileged tools are not even registered on Lite.             |
 * | NORM  | Delegates to the UI gate (user sees the Compose dialog).                     |
 * | PRO   | Delegates to the UI gate (user sees the Compose dialog).                     |
 * | OEM   | ALWAYS AUTO-APPROVES. Every call is written to the audit log and returns `true`. |
 *
 * @see TierPolicy.confirmationGate for how the per-tier policy swaps gates.
 */
fun interface ConfirmationGate {

    /**
     * Request approval for a privileged action.
     *
     * @param kind    The category of action (drives the dialog title/icon).
     * @param preview Human-readable description of EXACTLY what will execute
     *                (e.g. the shell command, the intent details, the file path).
     *                This text is shown verbatim to the user in the UI gate.
     * @param diffContent When non-null, a unified-diff string that will be
     *                    rendered by the Compose DiffViewer. Used for file patches.
     *
     * @return `true` if the action may proceed, `false` if it is denied.
     */
    suspend fun request(
        kind: ConfirmationKind,
        preview: String,
        diffContent: String?
    ): Boolean
}

/**
 * Taxonomy of privileged actions that pass through the confirmation gate.
 *
 * Kept in `:core` (policy layer) and mapped to the UI-layer
 * [com.omnidev.workspace.ui.chat.ConfirmationType] at the
 * `ChatViewModel` boundary. We deliberately duplicate the enum rather than
 * share it across layers because the policy layer MUST NOT depend on Compose.
 */
enum class ConfirmationKind {
    /** A Shizuku-issued shell command about to run with shell UID. */
    SHIZUKU_COMMAND,

    /** An Android Intent about to be fired. */
    ANDROID_INTENT,

    /** God-mode create-file outside the user's Target Context scope. */
    GOD_MODE_FILE_WRITE,

    /** God-mode delete-file outside the user's Target Context scope. */
    GOD_MODE_FILE_DELETE,

    /** God-mode file patch — a visual diff is shown before approval. */
    GOD_MODE_FILE_PATCH
}
