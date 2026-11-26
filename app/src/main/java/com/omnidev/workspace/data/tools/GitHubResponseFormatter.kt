package com.omnidev.workspace.data.tools

import kotlinx.serialization.json.*
import java.net.URI
import java.security.MessageDigest

/** Compact structured evidence, never a sliced list of incomplete JSON objects. */
object GitHubResponseFormatter {
    fun format(body: String, endpoint: String, raw: Boolean = false): String {
        if (raw) return clipped(body, 8_000)
        val parsed = runCatching { Json.parseToJsonElement(body) }.getOrNull() ?: return clipped(body, 8_000)
        val value = when (parsed) {
            is JsonArray -> list(parsed)
            is JsonObject -> if (parsed["full_name"] != null) pick(parsed, REPO) else parsed
            else -> parsed
        }
        return if (value.toString().length <= 8_000) value.toString() else clipped(value.toString(), 8_000)
    }
    private fun list(items: JsonArray): JsonObject {
        val compact = mutableListOf<JsonElement>()
        var size = 0
        for (item in items.take(100)) {
            val entry = if (item is JsonObject) when {
                item["full_name"] != null -> pick(item, REPO)
                item["path"] != null -> pick(item, setOf("name", "path", "type", "size", "sha", "html_url"))
                else -> pick(item, setOf("id", "number", "name", "title", "state", "sha", "html_url", "status", "conclusion"))
            } else item
            if (size + entry.toString().length > 6800) break
            compact += entry
            size += entry.toString().length + 1
        }
        return buildJsonObject {
            put("returned_count", items.size)
            put("included_count", compact.size)
            put("items", JsonArray(compact))
            if (compact.size < items.size) {
                put("omitted_items", items.size - compact.size)
                put("partial", true)
                put("hint", "Use smaller per_page for paginated lists, or target an exact directory/file path. Omitted items were not reviewed.")
            }
        }
    }
    private fun pick(item: JsonObject, fields: Set<String>) = JsonObject(item.filterKeys { it in fields })
    private fun clipped(text: String, limit: Int) = text.take(limit) + if (text.length > limit) "\n[Output clipped; narrow the endpoint or use a smaller page. This is partial evidence.]" else ""
    fun nextEndpoint(link: String?): String? = link?.split(',')?.firstNotNullOfOrNull { part ->
        if (!Regex("rel=\"next\"").containsMatchIn(part)) null else runCatching {
            val uri = URI(part.substringAfter('<').substringBefore('>'))
            if (uri.scheme == "https" && uri.host == "api.github.com" && uri.userInfo == null && uri.port == -1)
                GitHubRequestContract.endpoint(uri.rawPath + (uri.rawQuery?.let { "?$it" } ?: ""), "GET") else null
        }.getOrNull()
    }
    fun filePage(body: String, start: Int, maxLines: Int): String {
        require(start >= 1 && maxLines in 1..200) { "start_line must be positive and max_lines must be 1..200." }
        require(!body.contains('\u0000')) { "Requested file is binary; choose a source/text file." }
        val text = when {
            body.endsWith("\r\n") -> body.dropLast(2)
            body.endsWith('\n') || body.endsWith('\r') -> body.dropLast(1)
            else -> body
        }
        val lines = text.lineSequence().drop(start - 1).iterator()
        require(lines.hasNext()) { "start_line=$start is beyond the file's line range." }
        val output = StringBuilder()
        var included = 0
        while (included < maxLines && lines.hasNext()) {
            val numbered = "${start + included}: ${lines.next()}\n"
            if (output.length + numbered.length > 8_000) {
                if (included == 0) return "[Line $start exceeds the output budget; this page is incomplete.]"
                return output.append("[Partial file; next start_line=${start + included}]").toString()
            }
            output.append(numbered)
            included++
        }
        if (lines.hasNext()) output.append("[Partial file; next start_line=${start + included}]")
        else output.append("[End of file]")
        return output.toString()
    }

    internal fun fileRevision(body: String): String =
        MessageDigest.getInstance("SHA-256").digest(body.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private val REPO = setOf("full_name", "private", "description", "default_branch", "html_url", "permissions", "archived", "pushed_at")
}
