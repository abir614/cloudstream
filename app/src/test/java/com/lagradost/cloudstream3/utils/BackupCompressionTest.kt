package com.lagradost.cloudstream3.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class BackupCompressionTest {

    private fun compressGzip(text: String): ByteArray {
        val bos = ByteArrayOutputStream()
        val gzip = GZIPOutputStream(bos)
        val writer = PrintWriter(OutputStreamWriter(gzip, StandardCharsets.UTF_8))
        writer.print(text)
        writer.flush()
        gzip.finish()
        return bos.toByteArray()
    }

    private fun decompressAutoDetect(input: InputStream): String {
        val buffered = if (input.markSupported()) input else BufferedInputStream(input)
        buffered.mark(2)
        val header = ByteArray(2)
        val bytesRead = buffered.read(header)
        buffered.reset()

        val decompressedStream = if (bytesRead == 2 && header[0] == 0x1f.toByte() && header[1] == 0x8b.toByte()) {
            GZIPInputStream(buffered)
        } else {
            buffered
        }
        return decompressedStream.bufferedReader(StandardCharsets.UTF_8).readText()
    }

    @Test
    fun `test gzip backup achieves high compression ratio on json data`() {
        val dummyJson = """
            {
                "datastore": {
                    "_String": {
                        "history_1": "https://example.com/stream/episode/1",
                        "history_2": "https://example.com/stream/episode/2",
                        "history_3": "https://example.com/stream/episode/3"
                    },
                    "_Int": {
                        "progress_1": 120500,
                        "progress_2": 2400000,
                        "progress_3": 54000
                    }
                },
                "settings": {
                    "_Bool": {
                        "hardware_acceleration": true,
                        "subtitles_enabled": true
                    }
                }
            }
        """.trimIndent().repeat(30)

        val rawBytes = dummyJson.toByteArray(StandardCharsets.UTF_8)
        val compressedBytes = compressGzip(dummyJson)

        // Verifies compression ratio is > 75% on typical CloudStream backup JSON
        assertTrue("Compressed bytes (${compressedBytes.size}) must be < 25% of raw bytes (${rawBytes.size})",
            compressedBytes.size < rawBytes.size / 4
        )

        // Verifies auto-detect decompressor restores the exact original JSON
        val restored = decompressAutoDetect(ByteArrayInputStream(compressedBytes))
        assertEquals(dummyJson, restored)
    }

    @Test
    fun `test auto-detect decompressor supports legacy uncompressed backups`() {
        val legacyPlainJson = """
            {"datastore":{"_String":{"key1":"val1"}},"settings":{"_Bool":{"b1":true}}}
        """.trimIndent()

        val restored = decompressAutoDetect(ByteArrayInputStream(legacyPlainJson.toByteArray(StandardCharsets.UTF_8)))
        assertEquals(legacyPlainJson, restored)
    }
}
