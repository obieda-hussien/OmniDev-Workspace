package com.omnidev.workspace.data.tools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import java.io.ByteArrayOutputStream
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.nio.charset.Charset
import java.util.Locale
import java.util.zip.GZIPInputStream

internal data class PageFetchResponse(
    val requestedUrl: String,
    val finalUrl: String,
    val statusCode: Int,
    val contentType: String,
    val charset: Charset,
    val body: String,
    val headers: Map<String, List<String>>,
    val redirectCount: Int,
    val elapsedMs: Long
)

/**
 * Shared, defensive HTTP fetcher for page reading/scraping.
 *
 * Important properties:
 * - validates every URL and every redirect hop;
 * - resolves DNS before connecting and rejects private/special addresses;
 * - bounds redirects, response bytes and text size;
 * - honors declared charset instead of assuming UTF-8;
 * - retries only transient failures, with small backoff;
 * - keeps JavaScript execution out of the static fetch path.
 */
internal object PageFetchEngine {
    private const val CONNECT_TIMEOUT_MS = 12_000
    private const val READ_TIMEOUT_MS = 18_000
    private const val MAX_REDIRECTS = 6
    private const val MAX_BODY_BYTES = 3_000_000
    private const val MAX_ERROR_BYTES = 24_000
    private const val RETRIES = 2

    private val SUPPORTED_MIME_PREFIXES = listOf(
        "text/",
        "application/xhtml+xml",
        "application/xml",
        "application/json",
        "application/ld+json",
        "application/rss+xml",
        "application/atom+xml"
    )

    suspend fun fetch(rawUrl: String, acceptLanguage: String? = null): PageFetchResponse {
        val requested = rawUrl.trim()
        require(isSyntacticallySafePublicUrl(requested)) { "Blocked non-public or unsupported URL." }

        var lastError: Throwable? = null
        repeat(RETRIES) { attempt ->
            try {
                return fetchOnce(requested, acceptLanguage ?: defaultAcceptLanguage())
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                lastError = error
                val retryable = error is ConnectException || error is SocketTimeoutException ||
                    error is InterruptedIOException || error.message?.contains("HTTP 429") == true ||
                    error.message?.contains("HTTP 5") == true
                if (attempt < RETRIES - 1 && retryable) {
                    delay(350L * (1L shl attempt))
                }
            }
        }
        throw IllegalStateException("Page fetch failed: ${lastError?.message ?: "unknown error"}", lastError)
    }

    private suspend fun fetchOnce(requested: String, acceptLanguage: String): PageFetchResponse {
        val started = System.nanoTime()
        var current = requested
        var redirects = 0

        while (true) {
            currentCoroutineContext().ensureActive()
            require(isSyntacticallySafePublicUrl(current)) { "Blocked redirect target." }
            validateResolvedHost(current)

            val connection = (URL(current).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                useCaches = false
                setRequestProperty("User-Agent", KeylessSearchHttp.userAgent())
                setRequestProperty("Accept-Language", acceptLanguage)
                setRequestProperty(
                    "Accept",
                    "text/html,application/xhtml+xml,text/plain,application/json,application/xml," +
                        "application/rss+xml,application/atom+xml;q=0.9,*/*;q=0.4"
                )
                setRequestProperty("Accept-Encoding", "gzip")
                setRequestProperty("Cache-Control", "no-cache")
            }

            try {
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location")?.trim().orEmpty()
                    if (location.isBlank()) throw IllegalStateException("HTTP $code without Location header")
                    if (redirects >= MAX_REDIRECTS) throw IllegalStateException("Too many redirects")
                    current = URI(current).resolve(location).toString()
                    redirects++
                    continue
                }

                val declaredType = connection.contentType.orEmpty().substringBefore(';').trim().lowercase(Locale.ROOT)
                val contentLength = connection.contentLengthLong
                if (contentLength > MAX_BODY_BYTES) {
                    throw IllegalStateException("Page is too large (${contentLength} bytes; max $MAX_BODY_BYTES)")
                }

                if (code !in 200..299) {
                    val message = readBytes(connection, MAX_ERROR_BYTES).toString(Charsets.UTF_8).take(500)
                    throw IllegalStateException("HTTP $code${if (message.isBlank()) "" else ": $message"}")
                }

                val bytes = readBytes(connection, MAX_BODY_BYTES)
                val sniffedType = declaredType.ifBlank { sniffContentType(bytes) }
                if (!isSupportedContentType(sniffedType)) {
                    throw IllegalStateException("Unsupported content type: ${sniffedType.ifBlank { "unknown" }}")
                }
                val charset = resolveCharset(connection.contentType, bytes)
                val body = String(bytes, charset)
                val elapsed = (System.nanoTime() - started) / 1_000_000
                val headers = connection.headerFields.entries
                    .filter { it.key != null }
                    .associate { it.key!! to (it.value ?: emptyList()) }

                return PageFetchResponse(
                    requestedUrl = requested,
                    finalUrl = current,
                    statusCode = code,
                    contentType = sniffedType,
                    charset = charset,
                    body = body,
                    headers = headers,
                    redirectCount = redirects,
                    elapsedMs = elapsed
                )
            } finally {
                connection.disconnect()
            }
        }
    }

    private suspend fun readBytes(connection: HttpURLConnection, limit: Int): ByteArray {
        val raw = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        val stream = if (connection.contentEncoding?.contains("gzip", ignoreCase = true) == true && raw != null) {
            GZIPInputStream(raw)
        } else raw
        if (stream == null) return ByteArray(0)

        return stream.use { input ->
            val out = ByteArrayOutputStream(minOf(limit, 64 * 1024))
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > limit) throw IllegalStateException("Response exceeded $limit bytes")
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        }
    }

    private fun resolveCharset(contentType: String?, bytes: ByteArray): Charset {
        val headerName = Regex("charset\\s*=\\s*[\\\"']?([^;\\\"'\\s]+)", RegexOption.IGNORE_CASE)
            .find(contentType.orEmpty())?.groupValues?.getOrNull(1)
        val prefix = bytes.take(8192).toByteArray().toString(Charsets.ISO_8859_1)
        val metaName = Regex("charset\\s*=\\s*[\\\"']?([A-Za-z0-9._-]+)", RegexOption.IGNORE_CASE)
            .find(prefix)?.groupValues?.getOrNull(1)
        return sequenceOf(headerName, metaName, "UTF-8")
            .filterNotNull()
            .mapNotNull { runCatching { Charset.forName(it) }.getOrNull() }
            .first()
    }

    private fun sniffContentType(bytes: ByteArray): String {
        val prefix = bytes.take(512).toByteArray().toString(Charsets.UTF_8).trimStart()
        return when {
            prefix.startsWith("<!doctype html", ignoreCase = true) ||
                prefix.startsWith("<html", ignoreCase = true) -> "text/html"
            prefix.startsWith("{") || prefix.startsWith("[") -> "application/json"
            prefix.startsWith("<?xml", ignoreCase = true) -> "application/xml"
            else -> "text/plain"
        }
    }

    private fun isSupportedContentType(type: String): Boolean =
        SUPPORTED_MIME_PREFIXES.any { type.startsWith(it, ignoreCase = true) }

    fun isSyntacticallySafePublicUrl(raw: String): Boolean = runCatching {
        val uri = URI(raw.trim())
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") return false
        if (!uri.userInfo.isNullOrBlank()) return false
        val host = uri.host?.trim()?.lowercase(Locale.ROOT) ?: return false
        if (host.isBlank() || host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") ||
            host.endsWith(".internal") || host == "metadata.google.internal") return false
        !isBlockedLiteralHost(host)
    }.getOrDefault(false)

    private fun validateResolvedHost(raw: String) {
        val host = URI(raw).host ?: throw IllegalArgumentException("URL has no host")
        val addresses = InetAddress.getAllByName(host)
        require(addresses.isNotEmpty()) { "Host did not resolve" }
        require(addresses.none(::isBlockedAddress)) { "Blocked private/special network destination" }
    }

    private fun isBlockedLiteralHost(host: String): Boolean {
        val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return false
        // Only treat it as a literal if the host itself looks numeric. Domain names are resolved later.
        val looksLiteral = host.contains(':') || host.all { it.isDigit() || it == '.' }
        return looksLiteral && isBlockedAddress(address)
    }

    private fun isBlockedAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress) return true

        val bytes = address.address
        if (address is Inet4Address && bytes.size == 4) {
            val a = bytes[0].toInt() and 0xff
            val b = bytes[1].toInt() and 0xff
            return a == 0 || a >= 224 ||
                (a == 100 && b in 64..127) ||
                (a == 192 && b == 0) ||
                (a == 198 && b in 18..19)
        }
        if (address is Inet6Address && bytes.size == 16) {
            val first = bytes[0].toInt() and 0xff
            // fc00::/7 unique-local and fe80::/10 link-local (the latter is also covered above).
            if (first and 0xfe == 0xfc) return true
        }
        return false
    }

    private fun defaultAcceptLanguage(): String =
        "${Locale.getDefault().toLanguageTag()},en-US;q=0.8,en;q=0.7"
}
