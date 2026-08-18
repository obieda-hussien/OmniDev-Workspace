package com.omnidev.workspace.data.repo

import com.omnidev.workspace.data.db.entities.RepoSymbolEntry

/**
 * ══════════════════════════════════════════════════════════════════════════════
 * RepoSymbolExtractor — [Localized] [Localized] [Localized] (Live Repository Context Engine)
 * ══════════════════════════════════════════════════════════════════════════════
 *
 * Mobile-first: [Localized] Tree-sitter[Localized] [Localized] Compiler[Localized] [Localized] ANTLR. [Localized] regex [Localized] [Localized]
 * [Localized] tokens [Localized] [Localized] [Localized] [Localized]. [Localized] [Localized]:
 *   - [Localized] [Localized] classes / objects / interfaces / enums
 *   - [Localized] [Localized] functions / methods / lambdas [Localized]
 *   - [Localized] [Localized] properties / variables / constants [Localized] top-level
 *
 * [Localized] [Localized] Snapdragon 660 [Localized] ~1 MB/s[Localized] [Localized] [Localized] 50 KB [Localized] 50ms.
 * [Localized] 2-4 GB RAM [Localized] streaming [Localized] [Localized] AST [Localized] [Localized].
 */
object RepoSymbolExtractor {

    /** [Localized] [Localized] [Localized] snippet [Localized]. */
    private const val MAX_SNIPPET_LEN = 240

    /** [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] ([Localized] [Localized] [Localized]). */
    private const val MAX_SYMBOLS_PER_FILE = 400

    // ──────────────────────────────────────────────────────────────────
    // Language detection
    // ──────────────────────────────────────────────────────────────────

    fun detectLanguage(filePath: String): String {
        val ext = filePath.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "kt", "kts" -> "kotlin"
            "java" -> "java"
            "py" -> "python"
            "js", "mjs", "cjs" -> "javascript"
            "ts", "tsx" -> "typescript"
            "jsx" -> "javascript"
            "go" -> "go"
            "rs" -> "rust"
            "swift" -> "swift"
            "c", "h" -> "c"
            "cpp", "cc", "cxx", "hpp", "hxx" -> "cpp"
            "rb" -> "ruby"
            "php" -> "php"
            "cs" -> "csharp"
            "sh", "bash" -> "shell"
            "xml" -> "xml"
            "json" -> "json"
            "yml", "yaml" -> "yaml"
            "gradle" -> "gradle"
            "md" -> "markdown"
            else -> "other"
        }
    }

    fun shouldExtract(language: String): Boolean = language in EXTRACTABLE

    private val EXTRACTABLE = setOf(
        "kotlin", "java", "python", "javascript", "typescript",
        "go", "rust", "swift", "c", "cpp", "ruby", "php", "csharp"
    )

    // ──────────────────────────────────────────────────────────────────
    // Public: extract
    // ──────────────────────────────────────────────────────────────────

    /**
     * [Localized] [Localized] [Localized] [Localized] [Localized] [Localized]. [Localized] [Localized] top-level + nested [Localized] [Localized]
     * indent ([Localized] [Localized] retrieval[Localized] [Localized] [Localized] AST [Localized]).
     */
    fun extract(
        scopePath: String,
        filePath: String,
        content: String,
        language: String,
        fileMtime: Long
    ): List<RepoSymbolEntry> {
        if (!shouldExtract(language)) return emptyList()
        if (content.isBlank()) return emptyList()

        val lines = content.split('\n')
        val out = ArrayList<RepoSymbolEntry>()
        var packageOrModule = ""

        for ((idx, rawLine) in lines.withIndex()) {
            if (out.size >= MAX_SYMBOLS_PER_FILE) break
            val line = rawLine.trimStart()
            if (line.isEmpty() || line.startsWith("//") || line.startsWith("#") || line.startsWith("*")) continue

            // package / module [Localized] qualifiedName
            extractPackage(line, language)?.let { packageOrModule = it }

            val matches = matchSymbols(line, language)
            for ((kind, name, visibility) in matches) {
                if (name.isBlank() || name.length > 120) continue
                val qname = if (packageOrModule.isNotBlank()) "$packageOrModule.$name" else name
                out += RepoSymbolEntry(
                    scopePath = scopePath,
                    symbolKind = kind,
                    symbolName = name,
                    qualifiedName = qname,
                    filePath = filePath,
                    lineNumber = idx + 1,
                    snippet = rawLine.trim().take(MAX_SNIPPET_LEN),
                    language = language,
                    visibility = visibility,
                    fileMtime = fileMtime
                )
            }
        }
        return out
    }

    // ──────────────────────────────────────────────────────────────────
    // Internals — patterns
    // ──────────────────────────────────────────────────────────────────

    private data class SymbolMatch(val kind: String, val name: String, val visibility: String)

    /** [Localized] package/module declaration. */
    private fun extractPackage(line: String, language: String): String? {
        return when (language) {
            "kotlin", "java", "scala" -> {
                Regex("""^package\s+([\w.]+)""").find(line)?.groupValues?.get(1)
            }
            "python" -> null // python module = filepath
            "go" -> Regex("""^package\s+(\w+)""").find(line)?.groupValues?.get(1)
            "rust" -> Regex("""^mod\s+(\w+)""").find(line)?.groupValues?.get(1)
            else -> null
        }
    }

    private fun matchSymbols(line: String, language: String): List<SymbolMatch> {
        return when (language) {
            "kotlin" -> matchKotlin(line)
            "java" -> matchJava(line)
            "python" -> matchPython(line)
            "javascript", "typescript" -> matchJsTs(line)
            "go" -> matchGo(line)
            "rust" -> matchRust(line)
            "swift" -> matchSwift(line)
            "c", "cpp" -> matchCFamily(line)
            "ruby" -> matchRuby(line)
            "php" -> matchPhp(line)
            "csharp" -> matchCSharp(line)
            else -> emptyList()
        }
    }

    // ── Kotlin ─────────────────────────────────────────────────────────
    private val K_VIS = """(?:(public|private|internal|protected)\s+)?(?:(?:abstract|final|open|sealed|inner|data|enum|annotation|inline|suspend|override|operator)\s+)*"""
    private val K_CLASS = Regex("""^$K_VIS(class|object|interface|enum class)\s+([A-Za-z_][\w]*)""")
    private val K_FUN = Regex("""^$K_VIS\s*fun\s+(?:<[^>]+>\s+)?(?:[A-Za-z_][\w.<>?]*\.)?([A-Za-z_][\w]*)\s*\(""")
    private val K_VAL = Regex("""^$K_VIS\s*(val|var|const\s+val)\s+([A-Za-z_][\w]*)\s*[:=]""")
    private fun matchKotlin(line: String): List<SymbolMatch> {
        val out = ArrayList<SymbolMatch>(2)
        K_CLASS.find(line)?.let { out += SymbolMatch(it.groupValues[2].replace(" ", "_"), it.groupValues[3], it.groupValues[1]) }
        K_FUN.find(line)?.let { out += SymbolMatch("function", it.groupValues[2], it.groupValues[1]) }
        K_VAL.find(line)?.let { out += SymbolMatch("property", it.groupValues[3], it.groupValues[1]) }
        return out
    }

    // ── Java ───────────────────────────────────────────────────────────
    private val J_VIS = """(?:(public|private|protected)\s+)?(?:(?:static|final|abstract|synchronized|native|default)\s+)*"""
    private val J_CLASS = Regex("""^$J_VIS(class|interface|enum|record)\s+([A-Za-z_][\w]*)""")
    private val J_METHOD = Regex("""^$J_VIS(?:<[^>]+>\s+)?[\w<>\[\],\s.?]+\s+([A-Za-z_][\w]*)\s*\([^;]*\)\s*(?:throws[\w,\s]+)?\{?\s*$""")
    private val J_FIELD = Regex("""^$J_VIS[\w<>\[\],\s.]+\s+([A-Z_][A-Z0-9_]*)\s*=""")
    private fun matchJava(line: String): List<SymbolMatch> {
        val out = ArrayList<SymbolMatch>(2)
        J_CLASS.find(line)?.let { out += SymbolMatch(it.groupValues[2], it.groupValues[3], it.groupValues[1]) }
        if (out.isEmpty()) J_METHOD.find(line)?.let { m ->
            val name = m.groupValues[2]
            if (name !in JAVA_KEYWORDS) out += SymbolMatch("method", name, m.groupValues[1])
        }
        J_FIELD.find(line)?.let { out += SymbolMatch("constant", it.groupValues[2], it.groupValues[1]) }
        return out
    }
    private val JAVA_KEYWORDS = setOf("if", "for", "while", "switch", "return", "new", "throw")

    // ── Python ─────────────────────────────────────────────────────────
    private val PY_DEF = Regex("""^(\s*)(async\s+)?def\s+([A-Za-z_][\w]*)\s*\(""")
    private val PY_CLASS = Regex("""^(\s*)class\s+([A-Za-z_][\w]*)\s*[:(]""")
    private fun matchPython(line: String): List<SymbolMatch> {
        val out = ArrayList<SymbolMatch>(2)
        PY_CLASS.find(line)?.let { out += SymbolMatch("class", it.groupValues[2], "") }
        PY_DEF.find(line)?.let { out += SymbolMatch("function", it.groupValues[3], "") }
        return out
    }

    // ── JavaScript / TypeScript ────────────────────────────────────────
    private val JS_FUN = Regex("""^\s*(?:export\s+)?(?:async\s+)?function\s*\*?\s*([A-Za-z_$][\w$]*)\s*\(""")
    private val JS_CLASS = Regex("""^\s*(?:export\s+)?(?:abstract\s+)?class\s+([A-Za-z_$][\w$]*)""")
    private val JS_INTERFACE = Regex("""^\s*(?:export\s+)?interface\s+([A-Za-z_$][\w$]*)""")
    private val JS_CONST_FUN = Regex("""^\s*(?:export\s+)?(?:const|let|var)\s+([A-Za-z_$][\w$]*)\s*[:=].*?(?:=>|function)""")
    private val JS_TYPE = Regex("""^\s*(?:export\s+)?type\s+([A-Za-z_$][\w$]*)\s*=""")
    private fun matchJsTs(line: String): List<SymbolMatch> {
        val out = ArrayList<SymbolMatch>(2)
        JS_CLASS.find(line)?.let { out += SymbolMatch("class", it.groupValues[1], "") }
        JS_INTERFACE.find(line)?.let { out += SymbolMatch("interface", it.groupValues[1], "") }
        JS_FUN.find(line)?.let { out += SymbolMatch("function", it.groupValues[1], "") }
        JS_CONST_FUN.find(line)?.let { out += SymbolMatch("function", it.groupValues[1], "") }
        JS_TYPE.find(line)?.let { out += SymbolMatch("type", it.groupValues[1], "") }
        return out
    }

    // ── Go ─────────────────────────────────────────────────────────────
    private val GO_FUN = Regex("""^func\s+(?:\([^)]+\)\s+)?([A-Za-z_][\w]*)\s*\(""")
    private val GO_TYPE = Regex("""^type\s+([A-Za-z_][\w]*)\s+(struct|interface|=)""")
    private fun matchGo(line: String): List<SymbolMatch> {
        val out = ArrayList<SymbolMatch>(2)
        GO_FUN.find(line)?.let { out += SymbolMatch("function", it.groupValues[1], "") }
        GO_TYPE.find(line)?.let {
            val k = if (it.groupValues[2] == "interface") "interface" else "struct"
            out += SymbolMatch(k, it.groupValues[1], "")
        }
        return out
    }

    // ── Rust ───────────────────────────────────────────────────────────
    private val RS_FN = Regex("""^\s*(?:pub\s+(?:\([^)]+\)\s+)?)?(?:async\s+|const\s+|unsafe\s+)*fn\s+([A-Za-z_][\w]*)""")
    private val RS_STRUCT = Regex("""^\s*(?:pub\s+)?(?:struct|enum|trait|union)\s+([A-Za-z_][\w]*)""")
    private fun matchRust(line: String): List<SymbolMatch> {
        val out = ArrayList<SymbolMatch>(2)
        RS_STRUCT.find(line)?.let { out += SymbolMatch("type", it.groupValues[1], "") }
        RS_FN.find(line)?.let { out += SymbolMatch("function", it.groupValues[1], "") }
        return out
    }

    // ── Swift ──────────────────────────────────────────────────────────
    private val SWIFT_FUN = Regex("""^\s*(?:public|private|internal|fileprivate|open)?\s*func\s+([A-Za-z_][\w]*)""")
    private val SWIFT_CLASS = Regex("""^\s*(?:public|private|internal|open)?\s*(class|struct|enum|protocol)\s+([A-Za-z_][\w]*)""")
    private fun matchSwift(line: String): List<SymbolMatch> {
        val out = ArrayList<SymbolMatch>(2)
        SWIFT_CLASS.find(line)?.let { out += SymbolMatch(it.groupValues[1], it.groupValues[2], "") }
        SWIFT_FUN.find(line)?.let { out += SymbolMatch("function", it.groupValues[1], "") }
        return out
    }

    // ── C / C++ ────────────────────────────────────────────────────────
    private val CPP_CLASS = Regex("""^\s*(class|struct|union)\s+([A-Za-z_][\w]*)\s*[:{]""")
    private val CPP_FUN = Regex("""^\s*[\w:&*<>\s]+\s+([A-Za-z_][\w]*)\s*\([^;]*\)\s*\{?\s*$""")
    private fun matchCFamily(line: String): List<SymbolMatch> {
        val out = ArrayList<SymbolMatch>(2)
        CPP_CLASS.find(line)?.let { out += SymbolMatch(it.groupValues[1], it.groupValues[2], "") }
        if (out.isEmpty()) CPP_FUN.find(line)?.let { m ->
            val name = m.groupValues[1]
            if (name !in setOf("if", "for", "while", "switch", "return")) {
                out += SymbolMatch("function", name, "")
            }
        }
        return out
    }

    // ── Ruby ───────────────────────────────────────────────────────────
    private val RB_DEF = Regex("""^\s*def\s+(?:self\.)?([A-Za-z_][\w?!=]*)""")
    private val RB_CLASS = Regex("""^\s*(class|module)\s+([A-Z][\w]*)""")
    private fun matchRuby(line: String): List<SymbolMatch> {
        val out = ArrayList<SymbolMatch>(2)
        RB_CLASS.find(line)?.let { out += SymbolMatch(it.groupValues[1], it.groupValues[2], "") }
        RB_DEF.find(line)?.let { out += SymbolMatch("method", it.groupValues[1], "") }
        return out
    }

    // ── PHP ────────────────────────────────────────────────────────────
    private val PHP_FUN = Regex("""^\s*(?:public|private|protected)?\s*(?:static\s+)?function\s+([A-Za-z_][\w]*)""")
    private val PHP_CLASS = Regex("""^\s*(?:abstract|final)?\s*(class|interface|trait)\s+([A-Za-z_][\w]*)""")
    private fun matchPhp(line: String): List<SymbolMatch> {
        val out = ArrayList<SymbolMatch>(2)
        PHP_CLASS.find(line)?.let { out += SymbolMatch(it.groupValues[1], it.groupValues[2], "") }
        PHP_FUN.find(line)?.let { out += SymbolMatch("function", it.groupValues[1], "") }
        return out
    }

    // ── C# ─────────────────────────────────────────────────────────────
    private val CS_CLASS = Regex("""^\s*(?:public|private|internal|protected)?\s*(?:static|abstract|sealed)?\s*(class|interface|struct|record|enum)\s+([A-Za-z_][\w]*)""")
    private val CS_METHOD = Regex("""^\s*(?:public|private|internal|protected)?\s*(?:static\s+|virtual\s+|override\s+|async\s+)*[\w<>\[\],\s.?]+\s+([A-Za-z_][\w]*)\s*\(""")
    private fun matchCSharp(line: String): List<SymbolMatch> {
        val out = ArrayList<SymbolMatch>(2)
        CS_CLASS.find(line)?.let { out += SymbolMatch(it.groupValues[1], it.groupValues[2], "") }
        if (out.isEmpty()) CS_METHOD.find(line)?.let { m ->
            val name = m.groupValues[1]
            if (name !in setOf("if", "for", "while", "switch", "return", "new")) {
                out += SymbolMatch("method", name, "")
            }
        }
        return out
    }
}
