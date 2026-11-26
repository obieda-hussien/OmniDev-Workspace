package com.omnidev.workspace.data.ipc

import java.util.UUID

/** Correlates completion in the delegated shell with its outer Termux callback. */
internal class RishCommandReceipt {
    private val prefix = "OMNI_RISH_EXIT_${UUID.randomUUID()}="

    data class Completed(val exitCode: Int, val stdout: String)

    fun wrap(command: String): String {
        val quoted = "'" + command.replace("'", "'\"'\"'") + "'"
        return """
            sh -c $quoted
            status=${'$'}?
            printf '\n%s%s\n' '$prefix' "${'$'}status"
            exit "${'$'}status"
        """.trimIndent()
    }

    fun read(stdout: String, launcherExit: Int, transportSucceeded: Boolean): Completed? {
        if (!transportSucceeded) return null
        val output = stdout.trimEnd('\n', '\r')
        val line = output.substringAfterLast('\n')
        if (!line.startsWith(prefix)) return null
        val code = line.removePrefix(prefix).toIntOrNull()?.takeIf { it in 0..255 } ?: return null
        if (launcherExit != 0 && launcherExit != code) return null
        return Completed(code, output.substringBeforeLast('\n', "").trimEnd('\n', '\r'))
    }
}
