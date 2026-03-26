package com.omnidev.workspace.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Dark surface color for code blocks — inspired by GitHub dark theme. */
private val CodeBlockBackground = Color(0xFF0D1117)
private val InlineCodeBackground = Color(0xFF1C2128)
private val CodeTextColor = Color(0xFFE6EDF3)
private val CommentColor = Color(0xFF8B949E)
private val KeywordColor = Color(0xFFFF7B72)
private val StringColor = Color(0xFFA5D6FF)
private val NumberColor = Color(0xFFF2CC60)
private val FunctionColor = Color(0xFFD2A8FF)

/** Languages that receive basic syntax highlighting. Kept as a set for O(1) look-up. */
private val HIGHLIGHTED_LANGUAGES = setOf(
    "kotlin", "java", "python", "py",
    "javascript", "typescript", "js", "ts"
)

/**
 * Renders a Markdown string using a custom inline Compose parser.
 *
 * Supported syntax:
 * - Headers: `#`, `##`, `###`
 * - Bold: `**text**`
 * - Italic: `*text*` or `_text_`
 * - Inline code: `` `code` ``
 * - Fenced code blocks: ` ```lang\ncode\n``` `
 * - Unordered lists: `- item` / `* item`
 * - Horizontal rules: `---`
 *
 * Code blocks render with a dark background, language label, and copy button.
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
                        Text(
                            text = segment.annotated,
                            style = style.copy(
                                fontSize = segment.fontSize ?: style.fontSize,
                                fontWeight = segment.fontWeight ?: style.fontWeight
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (segment.addSpacingAfter) {
                            Spacer(Modifier.height(4.dp))
                        }
                    }
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
            }
        }
    }
}

/**
 * A dark-themed fenced code block with language label and copy-to-clipboard button.
 */
@Composable
private fun CodeBlock(code: String, language: String?) {
    val clipboard: ClipboardManager = LocalClipboardManager.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(CodeBlockBackground)
    ) {
        // Header row — language label + copy button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF161B22))
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
                modifier = Modifier.padding(0.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.ContentCopy,
                    contentDescription = "Copy code",
                    tint = CommentColor
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
            Text(
                text = buildSyntaxHighlightedAnnotatedString(code, language),
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                color = CodeTextColor
            )
        }
    }
}

/**
 * Applies basic syntax coloring for common keywords.
 * This is intentionally lightweight — a full parser would need a grammar per language.
 */
private fun buildSyntaxHighlightedAnnotatedString(code: String, language: String?): AnnotatedString {
    val lang = language?.lowercase()
    if (lang == null || lang !in HIGHLIGHTED_LANGUAGES) {
        return AnnotatedString(code)
    }

    val keywords = when (lang) {
        "kotlin" -> setOf("fun", "val", "var", "class", "object", "interface", "data", "sealed",
            "suspend", "override", "private", "public", "internal", "protected",
            "return", "if", "else", "when", "for", "while", "import", "package",
            "null", "true", "false", "this", "super", "is", "as", "in", "by",
            "companion", "enum", "typealias", "init", "constructor", "abstract")
        "java" -> setOf("public", "private", "protected", "class", "interface", "extends",
            "implements", "return", "void", "int", "String", "boolean", "null", "new",
            "static", "final", "if", "else", "for", "while", "import", "package",
            "true", "false", "this", "super", "abstract", "override", "enum")
        "python", "py" -> setOf("def", "class", "import", "from", "return", "if", "else",
            "elif", "for", "while", "in", "not", "and", "or", "None", "True", "False",
            "self", "super", "with", "as", "try", "except", "finally", "raise", "pass",
            "lambda", "yield", "async", "await")
        else -> setOf("const", "let", "var", "function", "class", "return", "if", "else",
            "for", "while", "import", "export", "default", "null", "undefined", "true",
            "false", "this", "new", "typeof", "instanceof", "async", "await", "from",
            "interface", "type", "extends", "implements", "enum", "readonly", "private",
            "public", "protected", "static", "abstract")
    }

    return buildAnnotatedString {
        val lines = code.split('\n')
        lines.forEachIndexed { lineIdx, line ->
            // Comments
            val commentStart = when (lang) {
                "python", "py" -> line.indexOf('#')
                else -> line.indexOf("//")
            }
            val processUntil = if (commentStart >= 0) commentStart else line.length
            var charPos = 0
            while (charPos < processUntil) {
                // String literals
                if (line[charPos] == '"' || line[charPos] == '\'') {
                    val quote = line[charPos]
                    val end = line.indexOf(quote, charPos + 1).let { if (it < 0) processUntil - 1 else it }
                    withStyle(SpanStyle(color = StringColor)) {
                        append(line.substring(charPos, minOf(end + 1, processUntil)))
                    }
                    charPos = minOf(end + 1, processUntil)
                    continue
                }
                // Number literals
                if (charPos < processUntil && line[charPos].isDigit() && (charPos == 0 || !line[charPos - 1].isLetterOrDigit())) {
                    var numEnd = charPos
                    while (numEnd < processUntil && (line[numEnd].isDigit() || line[numEnd] == '.')) numEnd++
                    withStyle(SpanStyle(color = NumberColor)) { append(line.substring(charPos, numEnd)) }
                    charPos = numEnd
                    continue
                }
                // Keywords / identifiers
                if (line[charPos].isLetter() || line[charPos] == '_') {
                    var wordEnd = charPos
                    while (wordEnd < processUntil && (line[wordEnd].isLetterOrDigit() || line[wordEnd] == '_')) wordEnd++
                    val word = line.substring(charPos, wordEnd)
                    // Check if followed by '(' → function call
                    val nextNonSpace = line.substring(wordEnd).trimStart()
                    when {
                        word in keywords -> withStyle(SpanStyle(color = KeywordColor, fontWeight = FontWeight.SemiBold)) { append(word) }
                        nextNonSpace.startsWith("(") -> withStyle(SpanStyle(color = FunctionColor)) { append(word) }
                        else -> append(word)
                    }
                    charPos = wordEnd
                    continue
                }
                append(line[charPos])
                charPos++
            }
            // Comment portion
            if (commentStart >= 0 && commentStart < line.length) {
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
        val fontSize: androidx.compose.ui.unit.TextUnit? = null,
        val fontWeight: FontWeight? = null,
        val addSpacingAfter: Boolean = false
    ) : MarkdownSegment()
    data object Divider : MarkdownSegment()
}

private fun parseMarkdownSegments(text: String): List<MarkdownSegment> {
    val segments = mutableListOf<MarkdownSegment>()
    val lines = text.split('\n')
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        // Fenced code block
        if (line.trimStart().startsWith("```")) {
            val lang = line.trimStart().removePrefix("```").trim().ifBlank { null }
            val codeLines = mutableListOf<String>()
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                codeLines.add(lines[i])
                i++
            }
            segments.add(MarkdownSegment.Code(codeLines.joinToString("\n"), lang))
            i++ // skip closing ```
            continue
        }
        // Horizontal rule
        if (line.trim().matches(Regex("-{3,}|\\*{3,}|_{3,}"))) {
            segments.add(MarkdownSegment.Divider)
            i++
            continue
        }
        // Header
        val headerMatch = Regex("^(#{1,3})\\s+(.+)$").find(line)
        if (headerMatch != null) {
            val level = headerMatch.groupValues[1].length
            val content = headerMatch.groupValues[2]
            val (fs, fw) = when (level) {
                1 -> Pair(24.sp, FontWeight.Bold)
                2 -> Pair(20.sp, FontWeight.Bold)
                else -> Pair(17.sp, FontWeight.SemiBold)
            }
            segments.add(MarkdownSegment.InlineContent(
                annotated = parseInlineMarkdown(content),
                fontSize = fs,
                fontWeight = fw,
                addSpacingAfter = true
            ))
            i++
            continue
        }
        // Regular line (with inline Markdown)
        if (line.isNotBlank()) {
            segments.add(MarkdownSegment.InlineContent(
                annotated = parseInlineMarkdown(line),
                addSpacingAfter = false
            ))
        } else if (segments.isNotEmpty() && segments.last() !is MarkdownSegment.InlineContent) {
            // keep blank line as spacing between blocks
        }
        i++
    }
    return segments
}

/**
 * Parses inline Markdown within a single line: `**bold**`, `*italic*`, `` `code` ``.
 */
private fun parseInlineMarkdown(text: String): AnnotatedString {
    // Strip leading list markers
    val stripped = text.removePrefix("- ").removePrefix("* ").removePrefix("• ")
    val prefix = if (stripped != text) "• " else ""

    return buildAnnotatedString {
        append(prefix)
        var pos = 0
        while (pos < stripped.length) {
            when {
                // Bold: **text**
                stripped.startsWith("**", pos) -> {
                    val end = stripped.indexOf("**", pos + 2)
                    if (end > pos + 2) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(stripped.substring(pos + 2, end))
                        }
                        pos = end + 2
                    } else { append(stripped[pos]); pos++ }
                }
                // Italic: *text* or _text_
                (stripped[pos] == '*' || stripped[pos] == '_') && pos + 1 < stripped.length -> {
                    val delim = stripped[pos]
                    val end = stripped.indexOf(delim, pos + 1)
                    if (end > pos + 1) {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            append(stripped.substring(pos + 1, end))
                        }
                        pos = end + 1
                    } else { append(stripped[pos]); pos++ }
                }
                // Inline code: `code`
                stripped[pos] == '`' -> {
                    val end = stripped.indexOf('`', pos + 1)
                    if (end > pos + 1) {
                        withStyle(SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = InlineCodeBackground,
                            color = CodeTextColor,
                            fontSize = 12.sp
                        )) {
                            append(stripped.substring(pos + 1, end))
                        }
                        pos = end + 1
                    } else { append(stripped[pos]); pos++ }
                }
                else -> { append(stripped[pos]); pos++ }
            }
        }
    }
}
