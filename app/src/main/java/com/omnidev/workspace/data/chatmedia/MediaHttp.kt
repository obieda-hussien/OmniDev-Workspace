package com.omnidev.workspace.data.chatmedia

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

/** No private-network reads and no automatic credential-bearing redirects. */
internal object MediaHttp {
    val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(180, TimeUnit.SECONDS)
        .callTimeout(200, TimeUnit.SECONDS).retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = Dns.SYSTEM.lookup(hostname).also { addresses ->
                require(addresses.isNotEmpty() && addresses.none(::privateAddress)) { "Private network media URLs are unavailable." }
            }
        }).build()
    private fun privateAddress(address: InetAddress): Boolean = address.isAnyLocalAddress || address.isLoopbackAddress ||
        address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress ||
        (address.address.size == 16 && (address.address[0].toInt() and 0xfe) == 0xfc)
    fun url(value: String): HttpUrl = value.toHttpUrl().also {
        require(it.isHttps && it.username.isEmpty() && it.password.isEmpty() && it.port == 443) { "Use a public HTTPS media URL." }
    }
    suspend fun execute(request: Request): Response = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, value, _ -> value.close() }
            }
        })
    }
    suspend fun downloadResponse(value: String, googleKey: String? = null): Response {
        var url = url(value)
        repeat(5) {
            val builder = Request.Builder().url(url)
            // A provider key can only go to the exact provider's file-download endpoint.
            if (googleKey != null && url.host == "generativelanguage.googleapis.com" && url.encodedPath.startsWith("/v1beta/files/"))
                builder.header("x-goog-api-key", googleKey)
            val response = execute(builder.build())
            if (response.code in setOf(301, 302, 303, 307, 308)) {
                val location = response.header("Location"); response.close()
                require(!location.isNullOrBlank()) { "Missing media redirect." }
                url = url(url.resolve(location)?.toString() ?: error("Invalid media redirect."))
            } else return response
        }
        error("Too many media redirects.")
    }
}
