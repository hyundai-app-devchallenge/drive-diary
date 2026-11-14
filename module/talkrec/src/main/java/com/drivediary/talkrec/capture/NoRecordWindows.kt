package com.drivediary.talkrec.capture

/** Time-only guards shared by question TTS and command acknowledgements. No audio or text is kept. */
class NoRecordWindows {
    private class Window(val fromMs: Long, var untilMs: Long)
    private val windows = mutableListOf<Window>()

    fun mark(fromMs: Long, untilMs: Long) {
        require(untilMs >= fromMs)
        add(Window(fromMs, untilMs))
    }

    /** Protects an utterance of unknown duration immediately. Finish with its actual end plus a tail. */
    fun begin(fromMs: Long): (untilMs: Long) -> Unit {
        val window = Window(fromMs, Long.MAX_VALUE)
        add(window)
        return { untilMs ->
            require(untilMs >= fromMs)
            synchronized(windows) {
                if (window.untilMs == Long.MAX_VALUE) window.untilMs = untilMs
            }
        }
    }

    private fun add(window: Window) = synchronized(windows) {
        windows.removeAll { it.untilMs < window.fromMs - WINDOW_KEEP_MS }
        windows += window
    }

    fun overlaps(startMs: Long, endMs: Long): Boolean = synchronized(windows) {
        windows.any { startMs <= it.untilMs && endMs >= it.fromMs }
    }

    /** A saved clip includes PCM padding: its transcript alone may not overlap the spoken notice. */
    fun overlapsCapture(startMs: Long, endMs: Long): Boolean =
        overlaps(startMs - CLIP_LEAD_MS, endMs + CLIP_TAIL_MS)

    companion object {
        const val CLIP_LEAD_MS = 3_000L
        const val CLIP_TAIL_MS = 2_000L
        private const val WINDOW_KEEP_MS = 30 * 60_000L
    }
}
