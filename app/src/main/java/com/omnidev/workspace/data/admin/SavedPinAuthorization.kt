package com.omnidev.workspace.data.admin

/** Non-secret, installation-local state. The caller serializes access and persists each transition. */
internal class SavedPinAuthorization(
    private val read: () -> State,
    private val write: (State) -> Boolean
) {
    enum class State { NONE, READY, PAUSED }

    fun granted() = read() != State.NONE
    fun paused() = read() == State.PAUSED
    /** Called only after local Android identity confirmation. */
    fun rememberFromUser() = write(State.READY)
    fun revoke() = write(State.NONE)

    /** Persist the pause before decryption/input, so interruption cannot silently authorize a retry. */
    fun beginAttempt(): Boolean = read() == State.READY && write(State.PAUSED)
    fun finishAttempt(androidUnlocked: Boolean): Boolean =
        androidUnlocked && read() == State.PAUSED && write(State.READY)
}
