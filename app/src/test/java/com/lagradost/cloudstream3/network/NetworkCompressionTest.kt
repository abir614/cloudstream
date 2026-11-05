package com.lagradost.cloudstream3.network

import com.lagradost.cloudstream3.services.NativeCoreBridge
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.GZIPOutputStream

class NetworkCompressionTest {

    @Test
    fun `test NativeCoreBridge deflate compression roundtrip`() {
        val original = "CloudStream Zero-Trust Network Compression Payload: 1234567890".toByteArray(Charsets.UTF_8)
        val compressed = NativeCoreBridge.compressDeflate(original)
        assertTrue(compressed.isNotEmpty())
        val restored = NativeCoreBridge.decompressDeflate(compressed)
        assertEquals(String(original, Charsets.UTF_8), String(restored, Charsets.UTF_8))
    }

    @Test
    fun `test NativeCoreBridge zlib compression roundtrip`() {
        val original = "CloudStream High-Performance Zlib Stream Verification".toByteArray(Charsets.UTF_8)
        val compressed = NativeCoreBridge.compressZlib(original)
        assertTrue(compressed.isNotEmpty())
        val restored = NativeCoreBridge.decompressZlib(compressed)
        assertEquals(String(original, Charsets.UTF_8), String(restored, Charsets.UTF_8))
    }

    @Test
    fun `test NetworkCompressionInterceptor adds Accept-Encoding to standard requests`() {
        val interceptor = NetworkCompressionInterceptor()
        var capturedRequest: Request? = null

        val fakeChain = object : Interceptor.Chain {
            override fun request(): Request = Request.Builder().url("https://example.com/api/catalog").build()
            override fun proceed(request: Request): Response {
                capturedRequest = request
                return Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("{}".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            override fun connection() = null
            override fun call(): okhttp3.Call = throw NotImplementedError()
            override fun connectTimeoutMillis() = 1000
            override fun withConnectTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
            override fun readTimeoutMillis() = 1000
            override fun withReadTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
            override fun writeTimeoutMillis() = 1000
            override fun withWriteTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
        }

        interceptor.intercept(fakeChain)
        assertEquals("gzip, deflate", capturedRequest?.header("Accept-Encoding"))
    }

    @Test
    fun `test NetworkCompressionInterceptor does not inject Accept-Encoding on media streams`() {
        val interceptor = NetworkCompressionInterceptor()
        var capturedRequest: Request? = null

        val fakeChain = object : Interceptor.Chain {
            override fun request(): Request = Request.Builder().url("https://example.com/video/segment_001.ts").build()
            override fun proceed(request: Request): Response {
                capturedRequest = request
                return Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("video_bytes".toResponseBody("video/mp2t".toMediaType()))
                    .build()
            }
            override fun connection() = null
            override fun call(): okhttp3.Call = throw NotImplementedError()
            override fun connectTimeoutMillis() = 1000
            override fun withConnectTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
            override fun readTimeoutMillis() = 1000
            override fun withReadTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
            override fun writeTimeoutMillis() = 1000
            override fun withWriteTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
        }

        interceptor.intercept(fakeChain)
        assertTrue(capturedRequest?.header("Accept-Encoding") == null)
    }

    @Test
    fun `test NetworkCompressionInterceptor transparently decompresses gzip response`() {
        val originalText = "{\"status\":\"ok\",\"channels\":[{\"id\":1,\"name\":\"Discovery\"}]}"
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(originalText.toByteArray(Charsets.UTF_8)) }
        val gzippedBytes = bos.toByteArray()

        val interceptor = NetworkCompressionInterceptor()
        val fakeChain = object : Interceptor.Chain {
            override fun request(): Request = Request.Builder().url("https://example.com/api/channels").build()
            override fun proceed(request: Request): Response {
                return Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Encoding", "gzip")
                    .header("Content-Length", gzippedBytes.size.toString())
                    .body(gzippedBytes.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            override fun connection() = null
            override fun call(): okhttp3.Call = throw NotImplementedError()
            override fun connectTimeoutMillis() = 1000
            override fun withConnectTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
            override fun readTimeoutMillis() = 1000
            override fun withReadTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
            override fun writeTimeoutMillis() = 1000
            override fun withWriteTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
        }

        val response = interceptor.intercept(fakeChain)
        assertTrue(response.header("Content-Encoding") == null)
        assertTrue(response.header("Content-Length") == null)
        val decodedText = response.body?.string()
        assertEquals(originalText, decodedText)
    }

    @Test
    fun `test NetworkCompressionInterceptor transparently decompresses deflate response`() {
        val originalText = "#EXTM3U\n#EXTINF:-1,Channel 1\nhttp://example.com/ch1.m3u8\n"
        val deflater = Deflater()
        deflater.setInput(originalText.toByteArray(Charsets.UTF_8))
        deflater.finish()
        val bos = ByteArrayOutputStream()
        val buf = ByteArray(1024)
        while (!deflater.finished()) {
            val count = deflater.deflate(buf)
            bos.write(buf, 0, count)
        }
        deflater.end()
        val deflatedBytes = bos.toByteArray()

        val interceptor = NetworkCompressionInterceptor()
        val fakeChain = object : Interceptor.Chain {
            override fun request(): Request = Request.Builder().url("https://example.com/playlist.m3u8").build()
            override fun proceed(request: Request): Response {
                return Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Encoding", "deflate")
                    .body(deflatedBytes.toResponseBody("application/x-mpegurl".toMediaType()))
                    .build()
            }
            override fun connection() = null
            override fun call(): okhttp3.Call = throw NotImplementedError()
            override fun connectTimeoutMillis() = 1000
            override fun withConnectTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
            override fun readTimeoutMillis() = 1000
            override fun withReadTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
            override fun writeTimeoutMillis() = 1000
            override fun withWriteTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
        }

        val response = interceptor.intercept(fakeChain)
        assertTrue(response.header("Content-Encoding") == null)
        val decodedText = response.body?.string()
        assertEquals(originalText, decodedText)
    }
}
