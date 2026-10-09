package com.omnidev.workspace.data.mcp

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Cancellation closes the HTTP call even while a response body or SSE stream is being read. */
internal suspend fun <T> OkHttpClient.readCancellable(request: Request, read: (Response) -> T): T =
    suspendCancellableCoroutine { continuation ->
        val call = newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                val decoded = runCatching { response.use(read) }
                decoded.fold({ continuation.resume(it) }, { continuation.resumeWithException(it) })
            }
        })
    }
