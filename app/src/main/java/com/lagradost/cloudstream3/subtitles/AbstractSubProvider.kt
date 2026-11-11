package com.lagradost.cloudstream3.subtitles

import androidx.core.net.toUri
import com.lagradost.cloudstream3.MainActivity.Companion.deleteFileOnExit
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.ui.player.SubtitleOrigin
import okio.BufferedSource
import okio.buffer
import okio.sink
import okio.source
import java.io.File
import java.util.zip.ZipInputStream

/**
 * A builder for subtitle files.
 * @see addUrl
 * @see addFile
 */
class SubtitleResource {
    fun downloadFile(source: BufferedSource): File {
        val file = File.createTempFile("temp-subtitle", ".tmp").apply {
            deleteFileOnExit(this)
        }
        val sink = file.sink().buffer()
        sink.writeAll(source)
        sink.close()
        source.close()

        return file
    }

    private fun unzip(file: File): List<Pair<String, File>> {
        val entries = mutableListOf<Pair<String, File>>()
        val maxTotalBytes = 50L * 1024 * 1024 // 50 MB total uncompressed budget
        val maxEntryBytes = 20L * 1024 * 1024 // 20 MB max per subtitle entry
        val maxEntriesCount = 50
        var totalBytesRead = 0L

        ZipInputStream(file.inputStream().buffered()).use { zipInputStream ->
            var zipEntry = zipInputStream.nextEntry
            var count = 0

            while (zipEntry != null) {
                count++
                if (count > maxEntriesCount) {
                    break
                }
                if (zipEntry.isDirectory) {
                    zipEntry = zipInputStream.nextEntry
                    continue
                }

                val entryName = zipEntry.name
                // Zip-Slip path traversal defense: reject entries with '..' or root escapes
                if (entryName.contains("..") || entryName.startsWith("/") || entryName.startsWith("\\") || entryName.contains("\u0000")) {
                    zipEntry = zipInputStream.nextEntry
                    continue
                }

                val tempFile = File.createTempFile("unzipped-subtitle", ".tmp").apply {
                    deleteFileOnExit(this)
                }

                try {
                    val buffer = ByteArray(8192)
                    var bytesForEntry = 0L
                    tempFile.outputStream().use { os ->
                        while (true) {
                            val read = zipInputStream.read(buffer)
                            if (read == -1) break
                            bytesForEntry += read
                            totalBytesRead += read
                            if (bytesForEntry > maxEntryBytes || totalBytesRead > maxTotalBytes) {
                                throw SecurityException("Zip extraction exceeded security threshold (Zip Bomb defense)")
                            }
                            os.write(buffer, 0, read)
                        }
                    }
                    entries.add(entryName to tempFile)
                } catch (t: Throwable) {
                    tempFile.delete()
                    if (t is SecurityException) throw t
                }

                zipEntry = zipInputStream.nextEntry
            }
        }
        return entries
    }

    data class SingleSubtitleResource(
        val name: String?,
        val url: String,
        val origin: SubtitleOrigin
    )

    private var resources: MutableList<SingleSubtitleResource> = mutableListOf()

    fun getSubtitles(): List<SingleSubtitleResource> {
        return resources.toList()
    }

    fun addUrl(url: String?, name: String? = null) {
        if (url == null) return
        this.resources.add(
            SingleSubtitleResource(name, url, SubtitleOrigin.URL)
        )
    }

    fun addFile(file: File, name: String? = null) {
        this.resources.add(
            SingleSubtitleResource(name, file.toUri().toString(), SubtitleOrigin.DOWNLOADED_FILE)
        )
        deleteFileOnExit(file)
    }

    suspend fun addZipUrl(
        url: String,
        nameGenerator: (String, File) -> String? = { _, _ -> null }
    ) {
        val source = app.get(url).okhttpResponse.body.source()
        val zip = downloadFile(source)
        val realFiles = unzip(zip)
        zip.deleteRecursively()
        realFiles.forEach { (name, subtitleFile) ->
            addFile(subtitleFile, nameGenerator(name, subtitleFile))
        }
    }
}

