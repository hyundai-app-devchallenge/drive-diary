package com.dongbantest.app.music

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicSessionFilterTest {
    private val own = "com.dongbantest.app"

    @Test fun releaseRejectsAllOwnSessionsIncludingProbe() {
        listOf(null, "tts", "dongban-debug-music").forEach { tag ->
            assertFalse(MusicSessionFilter.accepts(own, own, tag, debug = false))
        }
    }

    @Test fun debugOnlyAcceptsExactProbeTagForOwnPackage() {
        assertTrue(MusicSessionFilter.accepts(own, own, "dongban-debug-music", debug = true))
        listOf(null, "tts", "", "Dongban-debug-music", "dongban-debug-music ").forEach { tag ->
            assertFalse(MusicSessionFilter.accepts(own, own, tag, debug = true))
        }
    }

    @Test fun otherPackagesAreAcceptedInBothBuildsWithoutTagRequirement() {
        listOf(false, true).forEach { debug ->
            listOf(null, "music", "dongban-debug-music").forEach { tag ->
                assertTrue(MusicSessionFilter.accepts(own, "example.player", tag, debug))
            }
        }
    }
}
