package com.omnidev.workspace.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ──────────────────────────────────────────────
//  Theme / Color Constants
// ──────────────────────────────────────────────

/** Code block colours — GitHub dark theme inspired. */
private val CodeBlockBackground  = Color(0xFF0D1117)
private val CodeBlockHeader      = Color(0xFF161B22)
private val InlineCodeBackground = Color(0xFF1C2128)
private val CodeTextColor        = Color(0xFFE6EDF3)
private val CommentColor         = Color(0xFF8B949E)
private val KeywordColor         = Color(0xFFFF7B72)
private val StringColor          = Color(0xFFA5D6FF)
private val NumberColor          = Color(0xFFF2CC60)
private val FunctionColor        = Color(0xFFD2A8FF)
private val TypeColor            = Color(0xFF79C0FF)
private val OperatorColor        = Color(0xFFFF7B72)

/** Rich-content colours. */
private val BlockQuoteBarColor  = Color(0xFF3FB950)
private val BlockQuoteBgColor   = Color(0xFF0D1117).copy(alpha = 0.45f)
private val LinkColor           = Color(0xFF58A6FF)
private val TableBorderColor    = Color(0xFF30363D)
private val TableHeaderBgColor  = Color(0xFF161B22)
private val LineNumberColor     = Color(0xFF484F58)

// Blockquote bar dimensions
private val BlockQuoteBarMinHeight = 20.dp
private val BlockQuoteBarHeight    = 48.dp

/** Spaces per list indent level (2-space convention). */
private const val SPACES_PER_INDENT = 2

/** Languages with syntax highlighting. */
private val HIGHLIGHTED_LANGUAGES = setOf(
    "kotlin", "java",
    "python", "py",
    "javascript", "js", "typescript", "ts",
    "bash", "sh", "shell",
    "css",
    "html", "xml",
    "json",
    "sql",
    "c", "cpp", "c++",
    "go",
    "rust", "rs",
    "swift",
    "ruby", "rb",
    "php"
)

// ──────────────────────────────────────────────
//  RTL Detection
// ──────────────────────────────────────────────

/**
 * Returns true when the majority of alphabetic characters in [text] are Right-to-Left
 * (Arabic, Hebrew, Thaana, etc.) so that the composable can apply the correct [TextDirection].
 */
private fun isRtlText(text: String): Boolean {
    var rtl = 0
    var total = 0
    for (c in text) {
        if (!c.isLetter()) continue
        total++
        val dir = Character.getDirectionality(c)
        if (dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT ||
            dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC ||
            dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT_EMBEDDING ||
            dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT_OVERRIDE) rtl++
    }
    return total > 0 && rtl.toFloat() / total > 0.3f
}

// ──────────────────────────────────────────────
//  Public Composable
// ──────────────────────────────────────────────

/**
 * Renders a Markdown string using a custom inline Compose parser.
 *
 * Supported syntax:
 * - Headers: `#` … `######`
 * - Bold: `**text**`, Italic: `*text*` / `_text_`, Bold+Italic: `***text***`
 * - Strikethrough: `~~text~~`
 * - Inline code: `` `code` ``
 * - Fenced code blocks with language label, copy button, and line numbers
 * - Blockquotes: `> text` with coloured side bar (flipped for RTL)
 * - Unordered lists: `- `, `* `, `+ ` (with optional indentation)
 * - Ordered lists: `1. `, `2. ` …
 * - Task lists: `- [x] done`, `- [ ] todo`
 * - Tables: `| col | col |` with header row
 * - Clickable hyperlinks: `[text](url)`
 * - Horizontal rules: `---`
 * - Auto RTL/LTR layout per Arabic / Hebrew content
 * - Extended syntax highlighting: Kotlin, Java, Python, JS/TS, Bash, CSS, HTML/XML,
 *   JSON, SQL, C/C++, Go, Rust, Swift, Ruby, PHP
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium
) {
    Column(modifier = modifier) {
        val segments = parseMarkdownSegments(text)
        segments.forEach { segment ->
            when (segment) {
                is MarkdownSegment.Code -> {
                    CodeBlock(code = segment.code, language = segment.language)
                    Spacer(Modifier.height(8.dp))
                }
                is MarkdownSegment.InlineContent -> {
                    if (segment.annotated.text.isNotBlank()) {
                        val textDir = if (segment.isRtl) TextDirection.Rtl else TextDirection.Ltr
                        Text(
                            text = segment.annotated,
                            style = style.copy(
                                fontSize = segment.fontSize ?: style.fontSize,
                                fontWeight = segment.fontWeight ?: style.fontWeight,
                                textDirection = textDir
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (segment.addSpacingAfter) Spacer(Modifier.height(4.dp))
                    }
                }
                is MarkdownSegment.BlockQuote -> {
                    BlockQuoteBlock(content = segment.content, isRtl = segment.isRtl, baseStyle = style)
                    Spacer(Modifier.height(4.dp))
                }
                is MarkdownSegment.ListItem -> {
                    ListItemBlock(segment = segment, baseStyle = style)
                }
                is MarkdownSegment.Table -> {
                    TableBlock(segment = segment, isRtl = segment.isRtl)
                    Spacer(Modifier.height(8.dp))
                }
                is MarkdownSegment.Divider -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant)
                    )
                    Spacer(Modifier.height(8.dp))
                }
                is MarkdownSegment.BlankLine -> {
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }
}

// ──────────────────────────────────────────────
//  Block Composables
// ──────────────────────────────────────────────

/** Dark-themed fenced code block with language label, line numbers, and copy button. */
@Composable
private fun CodeBlock(code: String, language: String?) {
    val clipboard = LocalClipboardManager.current
    val lines = code.split('\n')
    val showLineNumbers = lines.size > 1

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(CodeBlockBackground)
    ) {
        // Header — language label + copy button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(CodeBlockHeader)
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = language?.lowercase() ?: "code",
                style = MaterialTheme.typography.labelSmall,
                color = CommentColor,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = { clipboard.setText(AnnotatedString(code)) },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.ContentCopy,
                    contentDescription = "Copy code",
                    tint = CommentColor,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        // Code content with horizontal scroll
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(12.dp)
        ) {
            if (showLineNumbers) {
                Row {
                    // Line numbers column
                    Column(modifier = Modifier.padding(end = 12.dp)) {
                        lines.forEachIndexed { idx, _ ->
                            Text(
                                text = "${idx + 1}",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                lineHeight = 20.sp,
                                color = LineNumberColor
                            )
                        }
                    }
                    // Code column
                    Text(
                        text = buildSyntaxHighlightedAnnotatedString(code, language),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 20.sp,
                        color = CodeTextColor
                    )
                }
            } else {
                Text(
                    text = buildSyntaxHighlightedAnnotatedString(code, language),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 20.sp,
                    color = CodeTextColor
                )
            }
        }
    }
}

/** Blockquote with a coloured vertical bar; bar on left for LTR, right for RTL. */
@Composable
private fun BlockQuoteBlock(content: String, isRtl: Boolean, baseStyle: TextStyle) {
    val annotated = parseInlineMarkdown(content)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(BlockQuoteBgColor)
    ) {
        if (!isRtl) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(BlockQuoteBarHeight.coerceAtLeast(BlockQuoteBarMinHeight))
                    .background(BlockQuoteBarColor)
            )
            Spacer(Modifier.width(10.dp))
        }
        Text(
            text = annotated,
            style = baseStyle.copy(
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                textDirection = if (isRtl) TextDirection.Rtl else TextDirection.Ltr
            ),
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 8.dp)
        )
        if (isRtl) {
            Spacer(Modifier.width(10.dp))
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(BlockQuoteBarHeight.coerceAtLeast(BlockQuoteBarMinHeight))
                    .background(BlockQuoteBarColor)
            )
        }
    }
}

/** Renders one list item: bullet, ordered number, or task-list checkbox. */
@Composable
private fun ListItemBlock(
    segment: MarkdownSegment.ListItem,
    baseStyle: TextStyle
) {
    val indentDp = (segment.indent * 12).dp
    val textDir = if (segment.isRtl) TextDirection.Rtl else TextDirection.Ltr
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = indentDp, bottom = 2.dp),
        verticalAlignment = Alignment.Top
    ) {
        // Bullet / number / checkbox
        when {
            segment.checked != null -> {
                Icon(
                    imageVector = if (segment.checked) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
                    contentDescription = null,
                    tint = if (segment.checked) BlockQuoteBarColor else MaterialTheme.colorScheme.onSurface.copy(0.5f),
                    modifier = Modifier
                        .size(18.dp)
                        .padding(top = 2.dp, end = 6.dp)
                )
            }
            segment.ordered -> {
                Text(
                    text = "${segment.number}.",
                    style = baseStyle,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .width(24.dp)
                        .padding(top = 1.dp)
                )
            }
            else -> {
                Text(
                    text = "•",
                    style = baseStyle,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .width(16.dp)
                        .padding(top = 1.dp)
                )
            }
        }
        Spacer(Modifier.width(4.dp))
        Text(
            text = segment.content,
            style = baseStyle.copy(
                textDecoration = if (segment.checked == true) TextDecoration.LineThrough else null,
                color = if (segment.checked == true)
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                else MaterialTheme.colorScheme.onSurface,
                textDirection = textDir
            ),
            modifier = Modifier.weight(1f)
        )
    }
}

/** Simple bordered table with a header row. */
@Composable
private fun TableBlock(segment: MarkdownSegment.Table, isRtl: Boolean) {
    val borderMod = Modifier.border(0.5.dp, TableBorderColor)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, TableBorderColor, RoundedCornerShape(6.dp))
    ) {
        // Header row
        if (segment.headers.isNotEmpty()) {
            Row(modifier = Modifier.fillMaxWidth().background(TableHeaderBgColor)) {
                segment.headers.forEach { header ->
                    Text(
                        text = header.trim(),
                        modifier = Modifier
                            .weight(1f)
                            .then(borderMod)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = CodeTextColor
                    )
                }
            }
        }
        // Data rows
        segment.rows.forEachIndexed { rowIdx, row ->
            val rowBg = if (rowIdx % 2 == 0) Color.Transparent else Color(0xFF161B22)
            Row(modifier = Modifier.fillMaxWidth().background(rowBg)) {
                val cellCount = maxOf(segment.headers.size, row.size)
                for (colIdx in 0 until cellCount) {
                    val cell = row.getOrNull(colIdx)?.trim() ?: ""
                    Text(
                        text = cell,
                        modifier = Modifier
                            .weight(1f)
                            .then(borderMod)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

// ──────────────────────────────────────────────
//  Syntax Highlighting
// ──────────────────────────────────────────────

private fun keywordsForLanguage(lang: String): Set<String> = when (lang) {
    "kotlin" -> setOf(
        "fun", "val", "var", "class", "object", "interface", "data", "sealed",
        "suspend", "override", "private", "public", "internal", "protected",
        "return", "if", "else", "when", "for", "while", "import", "package",
        "null", "true", "false", "this", "super", "is", "as", "in", "by",
        "companion", "enum", "typealias", "init", "constructor", "abstract",
        "open", "final", "const", "lateinit", "lazy", "reified", "inline",
        "crossinline", "noinline", "operator", "infix", "external", "expect", "actual"
    )
    "java" -> setOf(
        "public", "private", "protected", "class", "interface", "extends",
        "implements", "return", "void", "int", "long", "float", "double", "byte",
        "short", "char", "boolean", "String", "null", "new", "static", "final",
        "if", "else", "for", "while", "do", "switch", "case", "break", "continue",
        "import", "package", "true", "false", "this", "super", "abstract", "enum",
        "try", "catch", "finally", "throw", "throws", "instanceof", "synchronized"
    )
    "python", "py" -> setOf(
        "def", "class", "import", "from", "return", "if", "else", "elif", "for",
        "while", "in", "not", "and", "or", "None", "True", "False", "self", "super",
        "with", "as", "try", "except", "finally", "raise", "pass", "lambda", "yield",
        "async", "await", "del", "global", "nonlocal", "assert", "is", "break", "continue"
    )
    "javascript", "js", "typescript", "ts" -> setOf(
        "const", "let", "var", "function", "class", "return", "if", "else", "for",
        "while", "do", "switch", "case", "break", "continue", "import", "export",
        "default", "null", "undefined", "true", "false", "this", "new", "typeof",
        "instanceof", "async", "await", "from", "interface", "type", "extends",
        "implements", "enum", "readonly", "private", "public", "protected", "static",
        "abstract", "try", "catch", "finally", "throw", "of", "in", "delete", "void",
        "never", "any", "string", "number", "boolean", "object", "unknown"
    )
    "bash", "sh", "shell" -> setOf(
        "if", "then", "else", "elif", "fi", "for", "in", "do", "done", "while",
        "until", "case", "esac", "function", "return", "exit", "echo", "export",
        "local", "readonly", "declare", "source", "shift", "set", "unset", "trap",
        "break", "continue", "true", "false", "test", "select"
    )
    "css" -> setOf(
        "important", "auto", "inherit", "none", "block", "inline", "flex", "grid",
        "absolute", "relative", "fixed", "sticky", "static", "bold", "normal",
        "center", "left", "right", "top", "bottom", "solid", "dashed", "dotted",
        "transparent", "initial", "unset", "revert", "var", "calc", "url"
    )
    "html", "xml" -> emptySet()
    "json" -> setOf("true", "false", "null")
    "sql" -> setOf(
        "SELECT", "FROM", "WHERE", "INSERT", "INTO", "VALUES", "UPDATE", "SET",
        "DELETE", "CREATE", "TABLE", "DROP", "ALTER", "ADD", "COLUMN", "INDEX",
        "PRIMARY", "KEY", "FOREIGN", "REFERENCES", "NOT", "NULL", "UNIQUE",
        "DEFAULT", "AUTO_INCREMENT", "JOIN", "INNER", "LEFT", "RIGHT", "OUTER",
        "ON", "AND", "OR", "IN", "LIKE", "BETWEEN", "ORDER", "BY", "GROUP",
        "HAVING", "LIMIT", "OFFSET", "UNION", "ALL", "DISTINCT", "AS", "CASE",
        "WHEN", "THEN", "ELSE", "END", "IF", "EXISTS", "CONSTRAINT", "BEGIN",
        "COMMIT", "ROLLBACK", "TRANSACTION", "DATABASE", "SCHEMA", "VIEW",
        "select", "from", "where", "insert", "into", "values", "update", "set",
        "delete", "create", "table", "drop", "alter", "add", "column", "index",
        "primary", "key", "foreign", "references", "not", "null", "unique",
        "default", "join", "inner", "left", "right", "outer", "on", "and",
        "or", "in", "like", "between", "order", "by", "group", "having", "limit"
    )
    "c", "cpp", "c++" -> setOf(
        "int", "long", "short", "char", "float", "double", "void", "bool", "unsigned",
        "signed", "const", "static", "extern", "auto", "register", "volatile",
        "struct", "union", "enum", "typedef", "sizeof", "return", "if", "else",
        "for", "while", "do", "switch", "case", "break", "continue", "goto",
        "default", "NULL", "true", "false", "nullptr", "class", "public", "private",
        "protected", "virtual", "override", "final", "new", "delete", "template",
        "typename", "namespace", "using", "try", "catch", "throw", "inline",
        "#include", "#define", "#ifdef", "#ifndef", "#endif", "#pragma"
    )
    "go" -> setOf(
        "func", "var", "const", "type", "struct", "interface", "map", "chan",
        "package", "import", "return", "if", "else", "for", "range", "switch",
        "case", "default", "break", "continue", "goto", "fallthrough", "defer",
        "go", "select", "nil", "true", "false", "make", "new", "len", "cap",
        "append", "copy", "delete", "close", "panic", "recover", "error"
    )
    "rust", "rs" -> setOf(
        "fn", "let", "mut", "const", "static", "struct", "enum", "trait", "impl",
        "pub", "use", "mod", "crate", "self", "Self", "super", "where", "type",
        "return", "if", "else", "match", "for", "while", "loop", "break", "continue",
        "in", "ref", "move", "box", "unsafe", "async", "await", "dyn", "extern",
        "true", "false", "None", "Some", "Ok", "Err"
    )
    "swift" -> setOf(
        "func", "var", "let", "class", "struct", "enum", "protocol", "extension",
        "import", "return", "if", "else", "guard", "for", "in", "while", "repeat",
        "switch", "case", "default", "break", "continue", "fallthrough", "defer",
        "do", "catch", "throw", "throws", "try", "init", "deinit", "self", "Self",
        "super", "nil", "true", "false", "public", "private", "internal", "open",
        "fileprivate", "static", "final", "override", "lazy", "weak", "unowned",
        "inout", "async", "await", "actor", "nonisolated", "some", "any"
    )
    "ruby", "rb" -> setOf(
        "def", "end", "class", "module", "do", "if", "else", "elsif", "unless",
        "then", "begin", "rescue", "ensure", "raise", "return", "yield", "self",
        "super", "nil", "true", "false", "and", "or", "not", "in", "for", "while",
        "until", "loop", "break", "next", "redo", "retry", "puts", "print",
        "require", "require_relative", "include", "extend", "attr_reader",
        "attr_writer", "attr_accessor"
    )
    "php" -> setOf(
        "function", "class", "interface", "trait", "extends", "implements",
        "public", "private", "protected", "static", "final", "abstract",
        "return", "if", "else", "elseif", "for", "foreach", "while", "do",
        "switch", "case", "break", "continue", "new", "null", "true", "false",
        "echo", "print", "var", "const", "namespace", "use", "require", "include",
        "require_once", "include_once", "try", "catch", "finally", "throw",
        "this", "self", "parent", "array", "list", "match", "fn", "yield"
    )
    else -> emptySet()
}

/**
 * Applies syntax colouring to [code] for supported languages.
 */
private fun buildSyntaxHighlightedAnnotatedString(code: String, language: String?): AnnotatedString {
    val lang = language?.lowercase()
    if (lang == null || lang !in HIGHLIGHTED_LANGUAGES) return AnnotatedString(code)

    // HTML/XML: simple tag highlighting
    if (lang == "html" || lang == "xml") {
        return buildAnnotatedString {
            val tagRegex = Regex("</?[a-zA-Z][^>]*>|<!--.*?-->")
            var lastEnd = 0
            tagRegex.findAll(code).forEach { m ->
                append(code.substring(lastEnd, m.range.first))
                withStyle(SpanStyle(color = KeywordColor)) { append(m.value) }
                lastEnd = m.range.last + 1
            }
            append(code.substring(lastEnd))
        }
    }

    // JSON: strings, numbers, keywords, keys
    if (lang == "json") {
        return buildAnnotatedString {
            var pos = 0
            while (pos < code.length) {
                when {
                    code[pos] == '"' -> {
                        val end = code.indexOf('"', pos + 1).let { if (it < 0) code.length - 1 else it }
                        val s = code.substring(pos, minOf(end + 1, code.length))
                        val isKey = code.substring(minOf(end + 1, code.length)).trimStart().startsWith(":")
                        withStyle(SpanStyle(color = if (isKey) TypeColor else StringColor)) { append(s) }
                        pos = minOf(end + 1, code.length)
                    }
                    code[pos].isDigit() || (code[pos] == '-' && pos + 1 < code.length && code[pos + 1].isDigit()) -> {
                        var end = pos + 1
                        while (end < code.length && (code[end].isDigit() || code[end] == '.' || code[end] == 'e' || code[end] == 'E')) end++
                        withStyle(SpanStyle(color = NumberColor)) { append(code.substring(pos, end)) }
                        pos = end
                    }
                    code.startsWith("true", pos) || code.startsWith("false", pos) || code.startsWith("null", pos) -> {
                        val kw = if (code.startsWith("true", pos)) "true" else if (code.startsWith("false", pos)) "false" else "null"
                        withStyle(SpanStyle(color = KeywordColor, fontWeight = FontWeight.SemiBold)) { append(kw) }
                        pos += kw.length
                    }
                    else -> { append(code[pos]); pos++ }
                }
            }
        }
    }

    val keywords = keywordsForLanguage(lang)
    val isCssLike = lang == "css"

    return buildAnnotatedString {
        val lines = code.split('\n')
        // Languages that use '#' for line comments (not block/preprocessor '#')
        val hashCommentLangs = setOf("python", "py", "bash", "sh", "shell", "ruby", "rb")
        // Languages that do NOT support C-style /* */ block comments
        val noBlockCommentLangs = hashCommentLangs
        lines.forEachIndexed { lineIdx, line ->
            // Identify comment start position for this language
            val commentStart = when {
                lang in hashCommentLangs -> {
                    // Find '#' that is not a preprocessor '##' prefix
                    val idx = line.indexOf('#')
                    if (idx >= 0 && (idx == 0 || line[idx - 1] != '#')) idx else -1
                }
                lang == "sql" -> {
                    // SQL line comment starts with '--'
                    val idx = line.indexOf('-')
                    if (idx >= 0 && idx + 1 < line.length && line[idx + 1] == '-') idx else -1
                }
                else -> line.indexOf("//")
            }
            val processUntil = if (commentStart >= 0) commentStart else line.length
            var charPos = 0

            while (charPos < processUntil) {
                val ch = line[charPos]
                when {
                    // Block comments /* … */ — not available in hash-comment languages
                    lang !in noBlockCommentLangs &&
                    charPos + 1 < processUntil && ch == '/' && line[charPos + 1] == '*' -> {
                        val endBlock = line.indexOf("*/", charPos + 2)
                        val end = if (endBlock >= 0) endBlock + 2 else processUntil
                        withStyle(SpanStyle(color = CommentColor, fontStyle = FontStyle.Italic)) {
                            append(line.substring(charPos, minOf(end, line.length)))
                        }
                        charPos = minOf(end, processUntil)
                    }
                    // String literals (single or double quote)
                    ch == '"' || ch == '\'' || (ch == '`' && (lang == "javascript" || lang == "js" || lang == "typescript" || lang == "ts" || lang == "kotlin")) -> {
                        val quote = ch
                        var end = charPos + 1
                        while (end < processUntil) {
                            // Guard against backslash at end-of-processUntil
                            if (line[end] == '\\' && end + 1 < processUntil) { end += 2; continue }
                            if (line[end] == quote) { end++; break }
                            end++
                        }
                        withStyle(SpanStyle(color = StringColor)) { append(line.substring(charPos, minOf(end, processUntil))) }
                        charPos = minOf(end, processUntil)
                    }
                    // CSS property: colon-terminated identifier
                    isCssLike && charPos > 0 && ch == ':' -> {
                        append(ch); charPos++
                    }
                    // Number literals
                    ch.isDigit() && (charPos == 0 || !line[charPos - 1].isLetterOrDigit()) -> {
                        var end = charPos + 1
                        while (end < processUntil && (line[end].isDigit() || line[end] == '.' || line[end] == 'x' || line[end] == 'X' || line[end] == '_')) end++
                        withStyle(SpanStyle(color = NumberColor)) { append(line.substring(charPos, end)) }
                        charPos = end
                    }
                    // Identifiers / keywords
                    ch.isLetter() || ch == '_' || (ch == '#' && (lang == "c" || lang == "cpp" || lang == "c++")) -> {
                        val start = charPos
                        if (ch == '#') charPos++ // consume #
                        while (charPos < processUntil && (line[charPos].isLetterOrDigit() || line[charPos] == '_')) charPos++
                        val word = line.substring(start, charPos)
                        val afterWord = line.substring(charPos).trimStart()
                        when {
                            word in keywords -> withStyle(SpanStyle(color = KeywordColor, fontWeight = FontWeight.SemiBold)) { append(word) }
                            afterWord.startsWith("(") -> withStyle(SpanStyle(color = FunctionColor)) { append(word) }
                            word[0].isUpperCase() && lang !in setOf("bash", "sh", "shell", "css", "sql") ->
                                withStyle(SpanStyle(color = TypeColor)) { append(word) }
                            else -> append(word)
                        }
                    }
                    else -> { append(ch); charPos++ }
                }
            }

            // Trailing comment
            if (commentStart in 0 until line.length) {
                withStyle(SpanStyle(color = CommentColor, fontStyle = FontStyle.Italic)) {
                    append(line.substring(commentStart))
                }
            }
            if (lineIdx < lines.lastIndex) append('\n')
        }
    }
}

// ──────────────────────────────────────────────
//  Markdown Parsing
// ──────────────────────────────────────────────

private sealed class MarkdownSegment {
    data class Code(val code: String, val language: String?) : MarkdownSegment()
    data class InlineContent(
        val annotated: AnnotatedString,
        val fontSize: TextUnit? = null,
        val fontWeight: FontWeight? = null,
        val addSpacingAfter: Boolean = false,
        val isRtl: Boolean = false
    ) : MarkdownSegment()
    data class BlockQuote(val content: String, val isRtl: Boolean = false) : MarkdownSegment()
    data class ListItem(
        val content: AnnotatedString,
        val ordered: Boolean,
        val number: Int = 0,
        val indent: Int = 0,
        val checked: Boolean? = null,
        val isRtl: Boolean = false
    ) : MarkdownSegment()
    data class Table(
        val headers: List<String>,
        val rows: List<List<String>>,
        val isRtl: Boolean = false
    ) : MarkdownSegment()
    data object Divider : MarkdownSegment()
    data object BlankLine : MarkdownSegment()
}

private val orderedListRegex  = Regex("""^(\s*)(\d+)\.\s+(.+)$""")
private val unorderedListRegex = Regex("""^(\s*)[-*+]\s+(.+)$""")
private val taskListRegex      = Regex("""^(\s*)[-*+]\s+\[([xX ])\]\s+(.+)$""")
private val headerRegex        = Regex("""^(#{1,6})\s+(.+)$""")
private val tableSepRegex      = Regex("""^\|?[\s:-]+(\|[\s:-]+)+\|?$""")
private val tableRowRegex      = Regex("""^\|.+\|$""")

private fun parseMarkdownSegments(text: String): List<MarkdownSegment> {
    val segments = mutableListOf<MarkdownSegment>()
    val lines = text.split('\n')
    var i = 0

    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trimStart()

        // ── Fenced code block ──────────────────────────────────────────
        if (trimmed.startsWith("```")) {
            val lang = trimmed.removePrefix("```").trim().ifBlank { null }
            val codeLines = mutableListOf<String>()
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                codeLines.add(lines[i]); i++
            }
            segments.add(MarkdownSegment.Code(codeLines.joinToString("\n"), lang))
            i++ // skip closing ```
            continue
        }

        // ── Table ──────────────────────────────────────────────────────
        if (tableRowRegex.matches(line.trim()) &&
            i + 1 < lines.size && tableSepRegex.matches(lines[i + 1].trim())) {
            val headers = line.trim().split("|").map { it.trim() }.filter { it.isNotEmpty() }
            i += 2 // skip header + separator
            val rows = mutableListOf<List<String>>()
            while (i < lines.size && tableRowRegex.matches(lines[i].trim())) {
                rows.add(lines[i].trim().split("|").map { it.trim() }.filter { it.isNotEmpty() })
                i++
            }
            val isRtl = isRtlText(headers.joinToString())
            segments.add(MarkdownSegment.Table(headers, rows, isRtl))
            continue
        }

        // ── Horizontal rule ────────────────────────────────────────────
        if (line.trim().matches(Regex("-{3,}|\\*{3,}|_{3,}"))) {
            segments.add(MarkdownSegment.Divider); i++; continue
        }

        // ── Header ────────────────────────────────────────────────────
        val headerMatch = headerRegex.find(line)
        if (headerMatch != null) {
            val level   = headerMatch.groupValues[1].length
            val content = headerMatch.groupValues[2]
            val isRtl   = isRtlText(content)
            val (fs, fw) = when (level) {
                1    -> 26.sp to FontWeight.ExtraBold
                2    -> 22.sp to FontWeight.Bold
                3    -> 19.sp to FontWeight.Bold
                4    -> 17.sp to FontWeight.SemiBold
                5    -> 15.sp to FontWeight.SemiBold
                else -> 14.sp to FontWeight.Medium
            }
            segments.add(MarkdownSegment.InlineContent(
                annotated = parseInlineMarkdown(content),
                fontSize = fs, fontWeight = fw,
                addSpacingAfter = true, isRtl = isRtl
            ))
            i++; continue
        }

        // ── Blockquote ────────────────────────────────────────────────
        if (trimmed.startsWith("> ") || trimmed == ">") {
            val quoteLines = mutableListOf<String>()
            while (i < lines.size && (lines[i].trimStart().startsWith("> ") || lines[i].trimStart() == ">")) {
                quoteLines.add(lines[i].trimStart().removePrefix(">").removePrefix(" "))
                i++
            }
            val content = quoteLines.joinToString("\n")
            segments.add(MarkdownSegment.BlockQuote(content, isRtlText(content)))
            continue
        }

        // ── Task list item ─────────────────────────────────────────────
        val taskMatch = taskListRegex.find(line)
        if (taskMatch != null) {
            val indent  = taskMatch.groupValues[1].length / SPACES_PER_INDENT
            val checked = taskMatch.groupValues[2].lowercase() == "x"
            val content = taskMatch.groupValues[3]
            val annotated = parseInlineMarkdown(content)
            segments.add(MarkdownSegment.ListItem(
                content = annotated, ordered = false, indent = indent,
                checked = checked, isRtl = isRtlText(content)
            ))
            i++; continue
        }

        // ── Unordered list item ────────────────────────────────────────
        val ulMatch = unorderedListRegex.find(line)
        if (ulMatch != null) {
            val indent  = ulMatch.groupValues[1].length / SPACES_PER_INDENT
            val content = ulMatch.groupValues[2]
            segments.add(MarkdownSegment.ListItem(
                content = parseInlineMarkdown(content), ordered = false,
                indent = indent, isRtl = isRtlText(content)
            ))
            i++; continue
        }

        // ── Ordered list item ──────────────────────────────────────────
        val olMatch = orderedListRegex.find(line)
        if (olMatch != null) {
            val indent  = olMatch.groupValues[1].length / SPACES_PER_INDENT
            val number  = olMatch.groupValues[2].toIntOrNull() ?: 1
            val content = olMatch.groupValues[3]
            segments.add(MarkdownSegment.ListItem(
                content = parseInlineMarkdown(content), ordered = true,
                number = number, indent = indent, isRtl = isRtlText(content)
            ))
            i++; continue
        }

        // ── Blank line ─────────────────────────────────────────────────
        if (line.isBlank()) {
            if (segments.isNotEmpty() && segments.last() !is MarkdownSegment.BlankLine) {
                segments.add(MarkdownSegment.BlankLine)
            }
            i++; continue
        }

        // ── Regular paragraph line ─────────────────────────────────────
        val isRtl = isRtlText(line)
        segments.add(MarkdownSegment.InlineContent(
            annotated = parseInlineMarkdown(line),
            addSpacingAfter = false,
            isRtl = isRtl
        ))
        i++
    }
    return segments
}

/**
 * Parses inline Markdown within a single line.
 * Supports: `***bold+italic***`, `**bold**`, `*italic*`, `_italic_`,
 * `~~strikethrough~~`, `` `code` ``, `[text](url)`, and leading list bullets.
 */
private fun parseInlineMarkdown(text: String): AnnotatedString {
    return buildAnnotatedString {
        var pos = 0
        while (pos < text.length) {
            when {
                // Bold + Italic: ***text***
                text.startsWith("***", pos) -> {
                    val end = text.indexOf("***", pos + 3)
                    if (end > pos + 3) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)) {
                            append(text.substring(pos + 3, end))
                        }
                        pos = end + 3
                    } else { append(text[pos]); pos++ }
                }
                // Bold: **text**
                text.startsWith("**", pos) -> {
                    val end = text.indexOf("**", pos + 2)
                    if (end > pos + 2) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(text.substring(pos + 2, end))
                        }
                        pos = end + 2
                    } else { append(text[pos]); pos++ }
                }
                // Strikethrough: ~~text~~
                text.startsWith("~~", pos) -> {
                    val end = text.indexOf("~~", pos + 2)
                    if (end > pos + 2) {
                        withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                            append(text.substring(pos + 2, end))
                        }
                        pos = end + 2
                    } else { append(text[pos]); pos++ }
                }
                // Italic: *text* or _text_
                (text[pos] == '*' || text[pos] == '_') && pos + 1 < text.length -> {
                    val delim = text[pos]
                    val end = text.indexOf(delim, pos + 1)
                    if (end > pos + 1) {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            append(text.substring(pos + 1, end))
                        }
                        pos = end + 1
                    } else { append(text[pos]); pos++ }
                }
                // Inline code: `code`
                text[pos] == '`' -> {
                    val end = text.indexOf('`', pos + 1)
                    if (end > pos + 1) {
                        withStyle(SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = InlineCodeBackground,
                            color = CodeTextColor,
                            fontSize = 12.sp
                        )) {
                            append(text.substring(pos + 1, end))
                        }
                        pos = end + 1
                    } else { append(text[pos]); pos++ }
                }
                // Link: [text](url)
                text[pos] == '[' -> {
                    val closeBracket = text.indexOf(']', pos + 1)
                    if (closeBracket > pos &&
                        closeBracket + 1 < text.length &&
                        text[closeBracket + 1] == '(') {
                        val closeParen = text.indexOf(')', closeBracket + 2)
                        if (closeParen > closeBracket + 2) {
                            val linkText = text.substring(pos + 1, closeBracket)
                            val url = text.substring(closeBracket + 2, closeParen)
                            val startIdx = length
                            withStyle(SpanStyle(
                                color = LinkColor,
                                textDecoration = TextDecoration.Underline
                            )) { append(linkText) }
                            addLink(LinkAnnotation.Url(url), startIdx, length)
                            pos = closeParen + 1
                        } else { append(text[pos]); pos++ }
                    } else { append(text[pos]); pos++ }
                }
                else -> { append(text[pos]); pos++ }
            }
        }
    }
}
