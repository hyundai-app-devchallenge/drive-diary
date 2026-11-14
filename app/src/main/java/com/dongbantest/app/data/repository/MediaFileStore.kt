package com.dongbantest.app.data.repository

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import com.dongbantest.app.data.talk.clipKey

/**
 * 앱 전용 내부 저장소(filesDir) 에 음성/사진 파일을 관리한다.
 * - 외부 저장소 권한이 필요 없다.
 * - 사진은 Photo Picker 로 받은 URI 를 앱 전용 폴더로 복사한다.
 *   (임시 URI 권한이 만료되어도 재실행 후 계속 볼 수 있도록)
 */
class MediaFileStore private constructor(private val rootDir: File, private val appContext: Context?) {
    constructor(context: Context) : this(context.applicationContext.filesDir, context.applicationContext)
    internal constructor(rootDir: File) : this(rootDir, null)

    private val voiceDir = File(rootDir, "voice").apply { mkdirs() }
    private val photoDir = File(rootDir, "photos").apply { mkdirs() }

    fun newVoiceFile(): File = File(voiceDir, "voice_${System.currentTimeMillis()}.m4a")

    /** Stable per block for save retries, in the same folder/format as VoiceRecorder. */
    fun talkVoiceFile(tripId: Long, blockId: String): File =
        File(voiceDir, "voice_talk_trip_${tripId}_${clipKey("$tripId:$blockId")}.m4a")

    /** Debug fixtures use PCM WAV; keep the trip prefix for recovery and deletion. */
    internal fun sampleTalkVoiceFile(tripId: Long, storyId: String): File =
        File(voiceDir, "voice_talk_trip_${tripId}_sample_${clipKey(storyId)}.wav")

    internal val talkJournalDir = File(rootDir, "talkrec-journal")

    /** ClipWriter in :talkrec writes only here: final AAC and same-folder .part files.
     * Include the older hashed paths from Room, their staged deletions and encoder temp files.
     */
    internal suspend fun deleteTalkFiles(tripId: Long, knownPaths: List<String>) {
        val legacyNames = knownPaths.map { File(it).name }.filter { it.startsWith("voice_talk_") }
        voiceDir.listFiles()?.filter { file ->
            file.name.startsWith("voice_talk_trip_${tripId}_") || legacyNames.any {
                file.name == it || file.name == "$it.deleting" ||
                    (file.name.startsWith("$it-") && file.extension == "part")
            }
        }?.forEach { delete(it.absolutePath) }
    }

    internal fun talkFiles(): List<File> = voiceDir.listFiles { file ->
        file.name.startsWith("voice_talk_")
    }?.toList().orEmpty()

    suspend fun copyPhotoToAppStorage(source: Uri): File = withContext(Dispatchers.IO) {
        val target = File(photoDir, "photo_${UUID.randomUUID()}.jpg")
        val input = requireNotNull(appContext).contentResolver.openInputStream(source)
            ?: error("사진을 열 수 없습니다: $source")
        input.use { inp -> target.outputStream().use { out -> inp.copyTo(out) } }
        target
    }

    /** 앱 전용 저장소 내부 파일만 삭제한다 (안전장치). */
    suspend fun delete(path: String?) = withContext(Dispatchers.IO) {
        if (path.isNullOrBlank()) return@withContext
        val file = File(path)
        val root = rootDir.canonicalPath
        if (file.canonicalPath.startsWith(root + File.separator) && file.exists()) {
            check(file.delete()) { "Cannot delete media file" }
        }
    }
}
