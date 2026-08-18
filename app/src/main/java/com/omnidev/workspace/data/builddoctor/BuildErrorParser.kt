package com.omnidev.workspace.data.builddoctor

import java.security.MessageDigest

/**
 * ══════════════════════════════════════════════════════════════════════════════
 * BuildErrorParser — [Localized] [Localized] [Localized] (Build Doctor Pro / Brain 2.0)
 * ══════════════════════════════════════════════════════════════════════════════
 *
 * [Localized] [Localized] stdout/stderr [Localized] build:
 *   - [Localized] [Localized] [Localized] [Localized] (file:line:col)
 *   - [Localized] [Localized] (compile / link / dependency / resource / runtime / config)
 *   - [Localized] (fingerprint) [Localized] [Localized] [Localized] [Localized]
 *
 * **Mobile-first**: regex-based [Localized] [Localized] parsing [Localized] [Localized] alloc [Localized].
 * [Localized] 1 MB stdout [Localized] < 50 ms [Localized] Snapdragon 660.
 */
object BuildErrorParser {

    /** [Localized] [Localized] [Localized] [Localized] [Localized]. */
    private const val MAX_MESSAGE_LEN = 600

    /** [Localized] [Localized] [Localized] [Localized] [Localized] stdout [Localized]. */
    private const val MAX_ERRORS_PER_BUILD = 50

    data class ParsedError(
        val category: String,
        val message: String,
        val filePath: String = "",
        val lineNumber: Int = 0,
        val columnNumber: Int = 0,
        val rawLine: String = "",
        val fingerprint: String
    )

    enum class Category(val display: String) {
        COMPILE("Compile"),
        LINK("Link"),
        DEPENDENCY("Dependency"),
        RESOURCE("Resource"),
        RUNTIME("Runtime"),
        CONFIG("Config"),
        UNKNOWN("Unknown");
        companion object {
            fun fromString(s: String): Category =
                values().firstOrNull { it.display.equals(s, ignoreCase = true) } ?: UNKNOWN
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Patterns — [Localized] [Localized] [Localized] [Localized] [Localized] [Localized]
    // ──────────────────────────────────────────────────────────────────

    // Kotlin / Java: e:/path/Foo.kt:12:8 error: ...
    private val KOTLIN_ERROR = Regex(
        """^[ew]:\s*([^:]+):(\d+):(\d+)\s+error:\s*(.+)$""",
        RegexOption.IGNORE_CASE
    )
    private val JAVAC_ERROR = Regex(
        """^([^:]+\.java):(\d+):\s*error:\s*(.+)$""",
        RegexOption.IGNORE_CASE
    )
    // Gradle: > Task :module:compileXxx FAILED
    private val GRADLE_TASK_FAILED = Regex(
        """^>\s*Task\s+([:\w-]+)\s+FAILED""",
        RegexOption.IGNORE_CASE
    )
    // Resource: AAPT: error: resource ... not found
    private val AAPT_ERROR = Regex(
        """^.*?(?:AAPT|aapt2).*?error:\s*(.+)$""",
        RegexOption.IGNORE_CASE
    )
    // Dependency: Could not resolve / Could not find / unresolved reference
    private val DEPENDENCY_HINT = Regex(
        """(could\s+not\s+(?:resolve|find|download)|unresolved\s+reference|cannot\s+find\s+symbol|no\s+such\s+module)""",
        RegexOption.IGNORE_CASE
    )
    // Linker: ld: undefined / linker error
    private val LINKER_HINT = Regex(
        """(undefined\s+reference|undefined\s+symbol|linker\s+command\s+failed|ld:\s+error)""",
        RegexOption.IGNORE_CASE
    )
    // Runtime: Exception / Error during execution
    private val RUNTIME_HINT = Regex(
        """(Caused\s+by:|java\.lang\.\w+Exception|kotlin\..*Exception|FATAL\s+EXCEPTION)""",
        RegexOption.IGNORE_CASE
    )
    // Config: Plugin/SDK/version mismatch
    private val CONFIG_HINT = Regex(
        """(plugin\s+\[id:.+\]\s+was\s+not\s+found|invalid\s+version|sdk\s+location\s+not\s+found|requires\s+gradle)""",
        RegexOption.IGNORE_CASE
    )
    // Generic Python/JS: file:line: error
    private val GENERIC_ERROR = Regex(
        """^([^:\s]+):(\d+)(?::(\d+))?:\s*(?:error|fatal\s+error|SyntaxError):?\s*(.+)$""",
        RegexOption.IGNORE_CASE
    )

    // ──────────────────────────────────────────────────────────────────
    // Public
    // ──────────────────────────────────────────────────────────────────

    /**
     * [Localized] output [Localized] build [Localized] [Localized] [Localized] [Localized] (≤ MAX_ERRORS_PER_BUILD).
     */
    fun parse(buildOutput: String): List<ParsedError> {
        if (buildOutput.isBlank()) return emptyList()
        val out = ArrayList<ParsedError>()
        val lines = buildOutput.split('\n')

        for (raw in lines) {
            if (out.size >= MAX_ERRORS_PER_BUILD) break
            val line = raw.trim()
            if (line.isEmpty()) continue
            val parsed = parseLine(line) ?: continue
            out += parsed
        }
        return out
    }

    /** [Localized] [Localized] [Localized] ([Localized] [Localized] streaming). */
    fun parseLine(line: String): ParsedError? {
        // 1) Kotlin
        KOTLIN_ERROR.find(line)?.let { m ->
            val (file, ln, col, msg) = m.destructured
            return makeError(Category.COMPILE, msg, file, ln.toIntOrNull() ?: 0, col.toIntOrNull() ?: 0, line)
        }
        // 2) Javac
        JAVAC_ERROR.find(line)?.let { m ->
            val (file, ln, msg) = m.destructured
            return makeError(Category.COMPILE, msg, file, ln.toIntOrNull() ?: 0, 0, line)
        }
        // 3) AAPT (resources)
        AAPT_ERROR.find(line)?.let { m ->
            return makeError(Category.RESOURCE, m.groupValues[1], "", 0, 0, line)
        }
        // 4) Generic file:line:col
        GENERIC_ERROR.find(line)?.let { m ->
            val (file, ln, col, msg) = m.destructured
            return makeError(
                category = inferFromMessage(msg),
                message = msg,
                filePath = file,
                lineNumber = ln.toIntOrNull() ?: 0,
                columnNumber = col.toIntOrNull() ?: 0,
                rawLine = line
            )
        }
        // 5) Hints [Localized] [Localized] [Localized]
        if (DEPENDENCY_HINT.containsMatchIn(line)) {
            return makeError(Category.DEPENDENCY, line.take(MAX_MESSAGE_LEN), "", 0, 0, line)
        }
        if (LINKER_HINT.containsMatchIn(line)) {
            return makeError(Category.LINK, line.take(MAX_MESSAGE_LEN), "", 0, 0, line)
        }
        if (RUNTIME_HINT.containsMatchIn(line)) {
            return makeError(Category.RUNTIME, line.take(MAX_MESSAGE_LEN), "", 0, 0, line)
        }
        if (CONFIG_HINT.containsMatchIn(line)) {
            return makeError(Category.CONFIG, line.take(MAX_MESSAGE_LEN), "", 0, 0, line)
        }
        return null
    }

    /** [Localized] [Localized] (16 hex) [Localized] [Localized] [Localized] [Localized] [Localized] [Localized]. */
    fun fingerprint(message: String, category: String = ""): String {
        val normalized = (category.ifBlank { "" } + " " + message)
            .replace(Regex("/[\\w./_-]+"), "/PATH")
            .replace(Regex("\\d+"), "N")
            .replace(Regex("0x[0-9a-fA-F]+"), "HEX")
            .replace(Regex("\\s+"), " ")
            .trim()
            .lowercase()
        if (normalized.isEmpty()) return ""
        val md = MessageDigest.getInstance("MD5")
        val d = md.digest(normalized.toByteArray())
        return d.take(8).joinToString("") { "%02x".format(it) }
    }

    // ──────────────────────────────────────────────────────────────────
    // Internals
    // ──────────────────────────────────────────────────────────────────

    private fun makeError(
        category: Category,
        message: String,
        filePath: String,
        lineNumber: Int,
        columnNumber: Int,
        rawLine: String
    ): ParsedError {
        val msg = message.take(MAX_MESSAGE_LEN).trim()
        return ParsedError(
            category = category.display,
            message = msg,
            filePath = filePath,
            lineNumber = lineNumber,
            columnNumber = columnNumber,
            rawLine = rawLine.take(MAX_MESSAGE_LEN),
            fingerprint = fingerprint(msg, category.display)
        )
    }

    private fun inferFromMessage(msg: String): Category = when {
        DEPENDENCY_HINT.containsMatchIn(msg) -> Category.DEPENDENCY
        LINKER_HINT.containsMatchIn(msg) -> Category.LINK
        RUNTIME_HINT.containsMatchIn(msg) -> Category.RUNTIME
        CONFIG_HINT.containsMatchIn(msg) -> Category.CONFIG
        else -> Category.COMPILE
    }
}
