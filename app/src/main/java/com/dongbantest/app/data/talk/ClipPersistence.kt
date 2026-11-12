package com.dongbantest.app.data.talk

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal fun clipKey(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

/** Preserve STT lines exactly and use LF on every platform. Never log the result. */
internal fun clipTranscript(lines: List<String>): String = lines.joinToString("\n")

internal data class ClipRow(val id: Long, val path: String?)

/**
 * Serializes file/DB mutations in the app's single repository. A refusal receipt pins the target
 * BEFORE any deletion, including an empty target, and is retained across process restarts.
 * Only IDs and internal file paths enter the journal; never audio or transcript.
 */
internal class ClipPersistence(private val journalDir: File) {
    private val mutex = Mutex()

    suspend fun <T> locked(action: suspend () -> T): T = mutex.withLock { action() }

    /** A retried save must never resurrect a clip already targeted by refusal/deletion. */
    fun wasDeleted(file: File): Boolean = journalDir.listFiles { it.extension == "receipt" }
        ?.any { read(it)?.path == file.absolutePath } == true

    /** The insert callback must transactionally insert memory + metadata. Caller holds the lock. */
    suspend fun save(
        file: File,
        existing: suspend () -> Long?,
        write: suspend () -> Long,
        insert: suspend (Long) -> Long,
    ): Long {
        requireOwned(file)
        existing()?.let { return it }
        // An unreferenced final file can remain after process death between rename and DB commit.
        remove(file)
        var committed = false
        try {
            val duration = write()
            currentCoroutineContext().ensureActive()
            return withContext(NonCancellable) {
                insert(duration).also { committed = true }
            }
        } finally {
            if (!committed) remove(file)
        }
    }

    suspend fun deleteLatest(
        tripId: Long, refusalId: String,
        latest: suspend () -> ClipRow?,
        find: suspend (Long) -> ClipRow?,
        delete: suspend (Long) -> Unit,
    ): Boolean = locked {
        require(refusalId.isNotBlank())
        val receipt = File(journalDir, clipKey("$tripId:$refusalId") + ".receipt")
        val target = if (receipt.exists()) read(receipt) else latest().also { pin(receipt, it) }
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable) { finishDelete(target, find, delete) }
    }

    /** Replay pinned deletions before accepting another save. Caller holds the lock. */
    suspend fun recover(find: suspend (Long) -> ClipRow?, delete: suspend (Long) -> Unit) {
        journalDir.listFiles { file -> file.extension == "receipt" }?.forEach {
            withContext(NonCancellable) { finishDelete(read(it), find, delete) }
        }
        journalDir.listFiles { file -> file.extension == "part" }?.forEach(::remove)
    }

    private suspend fun finishDelete(
        target: ClipRow?, find: suspend (Long) -> ClipRow?, delete: suspend (Long) -> Unit,
    ): Boolean {
        target ?: return false
        val row = find(target.id)
        val original = target.path?.let(::File)
        original?.let(::requireOwned)
        val staged = original?.let { File(it.path + ".deleting") }
        if (row != null) {
            check(row.path == target.path) { "Clip identity changed" }
            if (original?.exists() == true) {
                check(staged?.exists() == false && original.renameTo(staged)) { "Cannot stage clip deletion" }
            }
            try { delete(target.id) }
            catch (error: Throwable) {
                if (staged?.exists() == true) check(staged.renameTo(original)) { "Cannot restore clip" }
                throw error
            }
        }
        // A crash or unlink error after DB commit is completed using the same pinned receipt.
        if (staged != null) remove(staged)
        if (row == null && original != null) remove(original)
        return row != null
    }

    private fun pin(receipt: File, target: ClipRow?) {
        check(journalDir.isDirectory || journalDir.mkdirs()) { "Cannot create clip journal" }
        val temp = File.createTempFile("receipt-", ".part", journalDir)
        try {
            FileOutputStream(temp).use { stream ->
                val data = DataOutputStream(stream)
                data.writeLong(target?.id ?: 0)
                data.writeUTF(target?.path.orEmpty())
                data.flush()
                stream.fd.sync()
            }
            check(temp.renameTo(receipt)) { "Cannot pin clip deletion" }
        } finally { temp.delete() }
    }

    private fun read(file: File): ClipRow? = DataInputStream(file.inputStream()).use {
        val id = it.readLong()
        val path = it.readUTF().ifEmpty { null }
        if (id == 0L) null else ClipRow(id, path)
    }

    private fun requireOwned(file: File) {
        val root = requireNotNull(journalDir.parentFile).canonicalPath + File.separator
        check(file.canonicalPath.startsWith(root) && !file.isDirectory) { "Invalid clip path" }
    }

    companion object {
        fun remove(file: File) { check(!file.exists() || file.delete()) { "Cannot remove clip file" } }
    }
}
