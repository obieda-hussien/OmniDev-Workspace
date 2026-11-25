package com.omnidev.workspace.data.tools

/**
 * Builds pipelines for the typed Termux read operations. The limiters drain their
 * inputs so early consumer exit cannot turn a successful read into SIGPIPE.
 */
internal class RuntimeReadPipeline private constructor(
    command: String,
    private val maxLines: Int,
    private val grepStage: Int?,
) {
    val script: String = buildString {
        appendLine("set -o pipefail || exit")
        appendLine("{")
        appendLine("  {")
        append("    $command | sed -n '1,${maxLines + 1}p'")
        if (grepStage != null) {
            appendLine(" || {")
            appendLine("      stages=(\"\${PIPESTATUS[@]}\")")
            appendLine("      for ((i=\${#stages[@]}-1; i>=0; i--)); do")
            appendLine("        status=\${stages[i]}")
            appendLine("        if ((status == 0 || (i == $grepStage && status == 1))); then continue; fi")
            appendLine("        printf 'Runtime read stage %d failed (exit %d)\\n' \"\$((i+1))\" \"\$status\" >&2")
            appendLine("        exit \"\$status\"")
            appendLine("      done")
            appendLine("      exit 1")
            append("    }")
        }
        appendLine()
        appendLine("  } 2>&1 >&3 | {")
        appendLine("    sed -n '1,${MAX_STDERR_LINES + 1}p' >&2 || {")
        appendLine("      status=\$?")
        appendLine("      printf 'Runtime stderr limiter failed (exit %d)\\n' \"\$status\" >&2")
        appendLine("      exit \"\$status\"")
        appendLine("    }")
        appendLine("  }")
        append("} 3>&1")
    }

    fun limitStdout(stdout: String): String = limitLines(stdout, maxLines)

    fun limitStderr(stderr: String): String = limitLines(stderr, MAX_STDERR_LINES)

    fun isNoMatch(exitCode: Int, stdout: String, stderr: String): Boolean =
        grepStage != null && exitCode == 1 && stdout.isEmpty() && stderr.isEmpty()

    fun failureClassification(stderr: String): String? =
        if (stderr.lineSequence().any { it.endsWith(": Permission denied") || it.endsWith(": Operation not permitted") }) {
            "FILESYSTEM_PERMISSION_DENIED"
        } else {
            null
        }

    private fun limitLines(output: String, maxLines: Int): String {
        var remaining = maxLines
        for (index in output.indices) {
            if (output[index] == '\n' && --remaining == 0) {
                return output.substring(0, index + 1)
            }
        }
        return output
    }

    companion object {
        private const val MAX_STDERR_LINES = 100

        fun read(command: String, maxLines: Int) = RuntimeReadPipeline(command, maxLines, grepStage = null)

        fun search(grep: String, maxLines: Int) = RuntimeReadPipeline(grep, maxLines, grepStage = 0)

        fun filter(command: String, grep: String, maxLines: Int) =
            RuntimeReadPipeline("$command | $grep", maxLines, grepStage = 1)
    }
}
