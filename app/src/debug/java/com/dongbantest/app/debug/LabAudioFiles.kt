package com.dongbantest.app.debug

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.FileInputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Debug input only. Names stay in Activity memory and are never logged or persisted. */
internal data class LabAudioFile(val file: File, val sizeBytes: Long, val durationMs: Long?)

internal suspend fun readLabAudioFiles(context: Context): List<LabAudioFile> {
    val folder = context.filesDir.resolve("lab")
    check(folder.isDirectory || folder.mkdirs())
    val files = checkNotNull(folder.listFiles())
        .filter { it.isFile && it.extension.lowercase() in setOf("wav", "m4a", "mp3", "ogg") }
        .sortedBy { it.name }
    return files.map { file ->
        currentCoroutineContext().ensureActive()
        LabAudioFile(file, file.length(), readDurationMs(file))
    }
}

/** Reads track headers only; no decoding or full-file scan to estimate missing duration. */
private fun readDurationMs(file: File): Long? {
    val extractor = MediaExtractor()
    return try {
        FileInputStream(file).use { extractor.setDataSource(it.fd) }
        (0 until extractor.trackCount).firstNotNullOfOrNull { index ->
            val format = extractor.getTrackFormat(index)
            if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true &&
                format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION).takeIf { it >= 0 }?.div(1_000)
            } else null
        }
    } catch (_: Exception) {
        null // Missing metadata must not prevent selection; never log the file or exception.
    } finally {
        extractor.release()
    }
}
