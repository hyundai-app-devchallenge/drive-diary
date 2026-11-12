package com.dongbantest.app.data.talk

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ClipPersistenceTest {
    @get:Rule val temp = TemporaryFolder()
    private val rows = linkedMapOf<Long, ClipRow>()
    private fun store() = ClipPersistence(File(temp.root, "journal"))
    private fun clip(id: Long): ClipRow = ClipRow(id, File(temp.root, "voice_$id.m4a").apply { writeText("aac") }.path)
        .also { rows[id] = it }
    private suspend fun delete(store: ClipPersistence, refusal: String, trip: Long = 7, fail: Boolean = false): Boolean =
        store.deleteLatest(trip, refusal, latest = { rows.values.lastOrNull() }, find = { rows[it] }, delete = {
            if (fail) error("synthetic DB failure")
            rows.remove(it)
        })

    @Test fun transcriptUsesLfAndPreservesOriginalLines() {
        assertEquals("별 하나\n  둘!  \n", clipTranscript(listOf("별 하나", "  둘!  ", "")))
        assertEquals("", clipTranscript(emptyList()))
        assertEquals(64, clipKey("a").length)
        assertNotEquals(clipKey("7:a"), clipKey("8:a"))
    }

    @Test fun retryAfterRestartNeverDeletesTheNextClip() = runBlocking {
        val first = clip(1); val latest = clip(2)
        assertTrue(delete(store(), "refusal"))
        assertTrue(File(first.path!!).exists()); assertFalse(File(latest.path!!).exists())
        clip(3)
        assertFalse(delete(store(), "refusal"))
        assertEquals(setOf(1L, 3L), rows.keys)
        assertTrue(store().wasDeleted(File(latest.path)))
    }

    @Test fun noPreviousClipIsAlsoPinnedAndTripsHaveSeparateKeys() = runBlocking {
        assertFalse(delete(store(), "empty"))
        clip(1)
        assertFalse(delete(store(), "empty"))
        assertTrue(delete(store(), "empty", trip = 8))
    }

    @Test fun dbFailureRestoresFileAndRetryKeepsOriginalTarget() = runBlocking {
        val original = clip(1)
        try { delete(store(), "refusal", fail = true); fail("must fail") } catch (_: IllegalStateException) { }
        assertTrue(File(original.path!!).exists())
        clip(2)
        assertTrue(delete(store(), "refusal"))
        assertEquals(setOf(2L), rows.keys)
    }

    @Test fun recoveryFinishesAStagedDeleteAfterProcessDeath() = runBlocking {
        val original = clip(1)
        try { delete(store(), "refusal", fail = true) } catch (_: IllegalStateException) { }
        val staged = File(original.path + ".deleting")
        assertTrue(File(original.path!!).renameTo(staged))
        store().recover(find = { rows[it] }, delete = { rows.remove(it) })
        assertTrue(rows.isEmpty()); assertFalse(staged.exists())
    }

    @Test fun recoveryUnlinksStagedFileAfterDbCommit() = runBlocking {
        val original = clip(1)
        try { delete(store(), "refusal", fail = true) } catch (_: IllegalStateException) { }
        val staged = File(original.path + ".deleting")
        File(original.path!!).renameTo(staged)
        rows.clear()
        store().recover(find = { rows[it] }, delete = { rows.remove(it) })
        assertFalse(staged.exists())
    }

    @Test fun missingAudioStillDeletesMetadataAndPinsTheTarget() = runBlocking {
        File(clip(1).path!!).delete()
        assertTrue(delete(store(), "refusal"))
        clip(2)
        assertFalse(delete(store(), "refusal"))
    }

    @Test fun saveRetriesUseExistingRowWithoutEncodingAgain() = runBlocking {
        val file = File(temp.root, "voice.m4a")
        val store = store()
        var saved: Long? = null
        suspend fun save() = store.locked { store.save(file, existing = { saved },
            write = { file.writeText("aac"); 1000 }, insert = { assertEquals(1000L, it); saved = 9; 9 }) }
        assertEquals(9L, save()); assertEquals(9L, save())
        assertTrue(file.exists())
    }

    @Test fun failedInsertAndCancelledEncodeLeaveNoFile() = runBlocking {
        val file = File(temp.root, "voice.m4a")
        val store = store()
        try {
            store.save(file, existing = { null }, write = { file.writeText("aac"); 1000 }, insert = { error("DB") })
            fail("must fail")
        } catch (_: IllegalStateException) { }
        assertFalse(file.exists())
        try {
            store.save(file, existing = { null }, write = { file.writeText("partial"); throw CancellationException() },
                insert = { fail("must not insert"); 0 })
            fail("must cancel")
        } catch (_: CancellationException) { }
        assertFalse(file.exists())
    }

    @Test fun cancellationAfterEncodingBeforeCommitRemovesTheFile() = runBlocking {
        val file = File(temp.root, "voice.m4a")
        val store = store()
        val task = launch(start = CoroutineStart.UNDISPATCHED) {
            store.save(file, existing = { null }, write = {
                file.writeText("aac")
                currentCoroutineContext().cancel()
                1000
            }, insert = { fail("must not insert"); 0 })
        }
        task.join()
        assertTrue(task.isCancelled); assertFalse(file.exists())
    }

    @Test fun cancellationAtCommitKeepsTheCompletedPair() = runBlocking {
        val file = File(temp.root, "voice.m4a")
        val store = store()
        var rowExists = false
        val task = launch(start = CoroutineStart.UNDISPATCHED) {
            val owner = currentCoroutineContext()
            store.save(file, existing = { null }, write = { file.writeText("aac"); 1000 }, insert = {
                rowExists = true
                owner.cancel()
                1
            })
        }
        task.join()
        assertTrue(task.isCancelled); assertTrue(rowExists); assertTrue(file.exists())
    }
}
