package com.omnidev.workspace.data.chatmedia

import java.net.URI
import java.util.Locale

/** Bounded, deterministic reference extraction; paths are resolved locally, never executed. */
object MediaReferenceParser {
    private val markdown = Regex("""!?\[[^\]\n]*\]\(\s*(<[^>\n]+>|[^)\n]+)\s*\)""")
    private val quoted = Regex("""[`"']((?:/|file://|content://|https://)[^`"'\n]+)[`"']""")
    private val bare = Regex("""(?:https://|content://|file://|/storage/|/sdcard/|/data/user/|/data/data/)[^\s<>`"'\[\]]+""")
    fun references(text: String): List<String> {
        val input = text.take(64_000)
        val found = mutableListOf<Pair<Int, String>>()
        val occupied = mutableListOf<IntRange>()
        markdown.findAll(input).forEach {
            val value = it.groupValues[1].trim().removeSurrounding("<", ">")
            if (supported(value)) { found += it.range.first to value; occupied += it.range }
        }
        quoted.findAll(input).forEach {
            if (occupied.none { range -> it.range.first in range }) {
                found += it.range.first to it.groupValues[1]; occupied += it.range
            }
        }
        bare.findAll(input).forEach {
            if (occupied.none { range -> it.range.first in range }) found += it.range.first to it.value.trimEnd('.', ',', ';', ')', '،', '؛')
        }
        return found.sortedBy { it.first }.map { it.second }.filter(::supported).distinct().take(10)
    }
    fun supported(value: String) = value.length in 1..4096 && value.none { it.code < 32 } &&
        (value.startsWith('/') || value.startsWith("file://") || value.startsWith("content://") || value.startsWith("https://"))
    fun mime(value: String): String {
        val path = if (value.startsWith("https://")) runCatching { URI(value).path }.getOrNull().orEmpty() else value
        return when (path.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "webp" -> "image/webp"; "gif" -> "image/gif"; "heic", "heif" -> "image/heif"
            "mp4", "m4v" -> "video/mp4"; "webm" -> "video/webm"; "mkv" -> "video/x-matroska"; "mov" -> "video/quicktime"
            "mp3" -> "audio/mpeg"; "m4a", "aac" -> "audio/mp4"; "wav" -> "audio/wav"; "ogg", "opus" -> "audio/ogg"; "flac" -> "audio/flac"
            "pdf" -> "application/pdf"; "txt", "md", "kt", "java", "py", "log" -> "text/plain"; "zip" -> "application/zip"
            else -> "application/octet-stream"
        }
    }
}
