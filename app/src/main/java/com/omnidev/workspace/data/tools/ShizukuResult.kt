package com.omnidev.workspace.data.tools

sealed class ShizukuResult {
    data class Success(val output: String) : ShizukuResult()
    data class PartialSuccess(val output: String, val exitCode: Int) : ShizukuResult()
    data class Failure(val reason: String) : ShizukuResult()
    data class PermissionRequired(val message: String) : ShizukuResult()
    data class Unavailable(val message: String) : ShizukuResult()

    fun toDisplayString(): String = when (this) {
        is Success -> output
        is PartialSuccess -> "Error: command exited $exitCode: $output"
        is Failure -> "Error: $reason"
        is PermissionRequired -> "Permission required: $message"
        is Unavailable -> "Unavailable: $message"
    }

    fun hasUsefulOutput(): Boolean = when (this) {
        is Success -> output.isNotBlank() && output != "(no output)"
        is PartialSuccess -> false
        else -> false
    }

    fun outputOrNull(): String? = when (this) {
        is Success -> output.takeIf { it.isNotBlank() && it != "(no output)" }
        is PartialSuccess -> null
        else -> null
    }
}
