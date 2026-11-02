package com.trailguidegps.app.map

import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.maplibre.android.module.http.HttpRequestUtil
import java.util.concurrent.TimeUnit

/**
 * The map engine's internet connection, with a guard for streamed state map files.
 *
 * Streaming reads small byte ranges of each map file. If a server ever answers a range
 * request with the whole file (or the wrong part), the map engine would load hundreds of
 * MB and crash trying to unpack it ("incorrect header check"). The guard turns any such
 * answer into an ordinary error, which just leaves a blank tile that's retried later.
 */
object MapHttp {
    fun install() {
        val client = OkHttpClient.Builder()
            .dispatcher(Dispatcher().apply { maxRequestsPerHost = 12 })
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(RangeGuard)
            .build()
        HttpRequestUtil.setOkHttpClient(client)
    }

    private val RANGE = Regex("""bytes=(\d+)-(\d+)""")

    private object RangeGuard : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val req = chain.request()
            val m = req.header("Range")?.let { RANGE.find(it) } ?: return chain.proceed(req)
            val start = m.groupValues[1].toLong()
            val end = m.groupValues[2].toLong()
            // Ask for the bytes exactly as stored, never re-compressed on the way.
            val resp = chain.proceed(req.newBuilder().header("Accept-Encoding", "identity").build())
            if (!resp.isSuccessful) return resp
            val range = resp.header("Content-Range")
            val length = resp.body?.contentLength() ?: -1L
            val good = resp.code == 206 && range != null && range.startsWith("bytes $start-") &&
                (length < 0 || length <= end - start + 1)
            if (good) {
                // Each part of a file shares one address, so don't let it be cached or revalidated
                // as if it were the whole file.
                return resp.newBuilder().removeHeader("ETag").removeHeader("Last-Modified")
                    .header("Cache-Control", "no-store").build()
            }
            resp.close()
            return Response.Builder().request(req).protocol(resp.protocol).code(503)
                .message("Map server didn't send the requested part of the file")
                .body("".toResponseBody(null)).build()
        }
    }
}
