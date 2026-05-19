package com.example.aiassistant

import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.brotli.dec.BrotliInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream

class BrotliInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            .header("Accept-Encoding", "br, gzip, identity")
            .build()

        val response = chain.proceed(request)
        val encoding = response.header("Content-Encoding")

        if (encoding == "br" || encoding == "gzip") {
            val body = response.body ?: return response
            return try {
                val bytes = body.bytes()
                val decompressed = when (encoding) {
                    "br" -> BrotliInputStream(bytes.inputStream()).use { br ->
                        val out = ByteArrayOutputStream()
                        val buf = ByteArray(8192)
                        var n: Int
                        while (br.read(buf).also { n = it } != -1) out.write(buf, 0, n)
                        out.toByteArray()
                    }
                    "gzip" -> GZIPInputStream(bytes.inputStream()).use { gz ->
                        val out = ByteArrayOutputStream()
                        val buf = ByteArray(8192)
                        var n: Int
                        while (gz.read(buf).also { n = it } != -1) out.write(buf, 0, n)
                        out.toByteArray()
                    }
                    else -> bytes
                }
                response.newBuilder()
                    .removeHeader("Content-Encoding")
                    .removeHeader("Content-Length")
                    .body(decompressed.toResponseBody(body.contentType()))
                    .build()
            } catch (_: Exception) {
                response
            }
        }
        return response
    }
}
