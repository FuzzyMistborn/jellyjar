package com.fuzzymistborn.jellyjar.api

import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException

/**
 * Coil's image interceptor: reads each body in full and retries a truncated one.
 *
 * When two requests for the same not-yet-resized image size reach Jellyfin together, the later
 * one is served the resize-cache file while the first is still writing it — full Content-Length,
 * partial (or empty) body. Coil fails that with "unexpected end of stream" and never retries, so
 * e.g. the Detail backdrop (fetched at once by the backdrop and the accent-colour extractor) stayed
 * blank until the next visit. Reproduced against the server with curl; a short wait is enough for
 * the cache file to be complete.
 */
class ImageRetryInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        var attempt = 0
        while (true) {
            val response = chain.proceed(chain.request())
            val body = response.body
            if (!response.isSuccessful || body == null) return response
            try {
                val bytes = body.bytes() // throws if the stream ends short of Content-Length
                if (bytes.isEmpty() && attempt < MAX_RETRIES) throw IOException("Empty image body")
                return response.newBuilder().body(bytes.toResponseBody(body.contentType())).build()
            } catch (e: IOException) {
                response.close()
                if (attempt++ >= MAX_RETRIES) throw e
                Thread.sleep(RETRY_DELAY_MS * attempt)
            }
        }
    }

    private companion object {
        const val MAX_RETRIES = 2
        const val RETRY_DELAY_MS = 300L
    }
}
