package com.omnidev.workspace.data.admin

import java.util.concurrent.atomic.AtomicBoolean

internal object CredentialInputGate {
    private val busy = AtomicBoolean(false)
    val entering get() = busy.get()
    fun acquire() = busy.compareAndSet(false, true)
    fun release() { busy.set(false) }
}

