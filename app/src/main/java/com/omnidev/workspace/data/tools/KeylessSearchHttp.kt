package com.omnidev.workspace.data.tools

import kotlinx.coroutines.delay
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

internal object KeylessSearchHttp {
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000
    private const val HTTP_RETRIES = 2
    private const val MAX_BODY_CHARS = 2_000_000

    suspend fun get(url: String, acceptLanguage: String): String {
        require(isSafePublicUrl(url)) { "Blocked non-public URL" }
        var last: Throwable? = null
        repeat(HTTP_RETRIES) { attempt ->
            try {
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", userAgent())
                    setRequestProperty("Accept-Language", acceptLanguage)
                    setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.7")
                }
                return connection.readBody()
            } catch (error: Throwable) {
                last = error
                val retryable = error is ConnectException || error is SocketTimeoutException ||
                    error is InterruptedIOException || error.message?.contains("HTTP 429") == true ||
                    error.message?.contains("HTTP 5") == true
                if (attempt < HTTP_RETRIES - 1 && retryable) delay(300L * (1L shl attempt))
            }
        }
        throw IllegalStateException("HTTP failed: ${last?.message ?: "unknown error"}", last)
    }

    fun isSafePublicUrl(raw: String): Boolean = runCatching {
        val uri = URI(raw.trim())
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") return false
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") || host == "::1" ||
            host == "0.0.0.0" || host.startsWith("127.") || host.startsWith("10.") ||
            host.startsWith("192.168.") || host.startsWith("169.254.")) return false
        val ipv4 = host.split('.').mapNotNull { it.toIntOrNull() }
        if (ipv4.size == 4 && ipv4[0] == 172 && ipv4[1] in 16..31) return false
        true
    }.getOrDefault(false)

    fun absolute(base: String, href: String): String = runCatching { URI(base).resolve(href).toString() }.getOrDefault("")

    fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    fun googleHref(href: String): String {
        if (href.startsWith("http://") || href.startsWith("https://")) return href
        val encoded = Regex("""[?&](?:q|url)=([^&]+)""").find(href)?.groupValues?.getOrNull(1) ?: return ""
        return runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrDefault("")
    }

    fun duckHref(href: String): String {
        val encoded = Regex("""[?&]uddg=([^&]+)""").find(href)?.groupValues?.getOrNull(1)
        if (encoded != null) return runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrDefault(href)
        return if (href.startsWith("http://") || href.startsWith("https://")) href else ""
    }

    fun userAgent(): String = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36"

    private fun HttpURLConnection.readBody(): String = try {
        val code = responseCode
        val stream = if (code in 200..299) inputStream else errorStream
        val body = stream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
            val out = StringBuilder()
            val buffer = CharArray(8192)
            while (out.length < MAX_BODY_CHARS) {
                val read = reader.read(buffer, 0, minOf(buffer.size, MAX_BODY_CHARS - out.length))
                if (read <= 0) break
                out.append(buffer, 0, read)
            }
            out.toString()
        }.orEmpty()
        if (code !in 200..299) throw IllegalStateException("HTTP $code: ${body.take(400)}")
        body
    } finally {
        disconnect()
    }
}
