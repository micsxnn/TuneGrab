package com.tunegrab.app.security

import okhttp3.ResponseBody
import okhttp3.Interceptor
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import java.io.ByteArrayOutputStream
import java.io.IOException

/** Bound decoded bytes too: Content-Length alone does not protect against chunked/gzip bombs. */
fun ResponseBody.boundedText(maxBytes: Int): String {
    require(maxBytes > 0)
    if (contentLength() > maxBytes) throw IOException("resposta excede o limite permitido")
    val output = ByteArrayOutputStream(minOf(maxBytes, 8192))
    byteStream().use { input ->
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (n > maxBytes - total) throw IOException("resposta excede o limite permitido")
            output.write(buffer, 0, n)
            total += n
        }
    }
    return String(output.toByteArray(), contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8)
}

/** Applied after transparent decompression, so Retrofit and raw clients share the same bound. */
class BoundedBodyInterceptor(private val maxBytes: Long) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val response = chain.proceed(chain.request())
        val body = response.body ?: return response
        if (body.contentLength() > maxBytes) {
            response.close()
            throw IOException("resposta excede o limite permitido")
        }
        val limited = object : ResponseBody() {
            private val bounded: BufferedSource by lazy {
                object : ForwardingSource(body.source()) {
                    private var total = 0L
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        val count = super.read(sink, minOf(byteCount, maxBytes - total + 1))
                        if (count > 0) {
                            total += count
                            if (total > maxBytes) throw IOException("resposta excede o limite permitido")
                        }
                        return count
                    }
                }.buffer()
            }
            override fun contentType() = body.contentType()
            override fun contentLength() = body.contentLength()
            override fun source(): BufferedSource = bounded
        }
        return response.newBuilder().body(limited).build()
    }
}
