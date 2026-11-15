package com.drivediary.talkrec.block

/** Receive time is authoritative for silence; SDK line/EPD times are deliberately unused. */
class FinalLine(val text: String, val receivedMs: Long) {
    override fun toString() = "FinalLine(receivedMs=$receivedMs, chars=${text.length})"
}

enum class CaptureSource { AUTO, MANUAL }
enum class CloseReason { SILENCE, LENGTH, MANUAL }

class ConversationBlock(
    val lines: List<FinalLine>, val startMs: Long, val endMs: Long,
    val source: CaptureSource, val reason: CloseReason,
) {
    override fun toString() = "ConversationBlock(lines=${lines.size}, startMs=$startMs, endMs=$endMs, source=$source)"
}

/** Single-owner state machine. Feed every contiguous 100 ms PCM frame, including silence. */
class BlockAssembler(
    private val silenceMs: Long = 20_000,
    private val maxMs: Long = 90_000,
    private val silenceRms: Double = 140.0,
) {
    private val lines = mutableListOf<FinalLine>()
    private var onset: Long? = null
    private var start: Long? = null
    private var quietSince: Long? = null
    private var audioUntil: Long? = null
    private var voiceUntil: Long? = null

    init { require(silenceMs > 0 && maxMs > 0 && silenceRms.isFinite() && silenceRms >= 0) }

    fun onEnergy(startMs: Long, endMs: Long, rms: Double): List<ConversationBlock> {
        require(endMs > startMs && rms.isFinite() && rms >= 0)
        // Missing PCM is unknown, never evidence of continuous silence.
        if (audioUntil != startMs) quietSince = null
        audioUntil = endMs
        if (rms < silenceRms) {
            if (quietSince == null) quietSince = startMs
        } else {
            quietSince = null
            if (onset == null) onset = startMs
            voiceUntil = endMs
        }
        return advance(endMs)
    }

    fun onFinal(line: FinalLine): List<ConversationBlock> {
        require(lines.isEmpty() || line.receivedMs >= lines.last().receivedMs) { "Unordered final time" }
        val closed = advance(line.receivedMs)
        if (line.text.isBlank()) return closed
        // A noise long before the line must not stretch the block start back; look back at most one silence window.
        if (lines.isEmpty()) start = minOf(line.receivedMs, maxOf(onset ?: line.receivedMs, line.receivedMs - maxMs + 1, line.receivedMs - silenceMs))
        lines += line
        return closed
    }

    private fun advance(nowMs: Long): List<ConversationBlock> {
        val first = start ?: return emptyList()
        val last = lines.lastOrNull()?.receivedMs ?: return emptyList()
        val quiet = quietSince
        val evidenceUntil = minOf(nowMs, audioUntil ?: nowMs)
        if (quiet != null && evidenceUntil - maxOf(quiet, last) >= silenceMs) {
            return listOf(close(maxOf(first + 1, last, voiceUntil ?: last), CloseReason.SILENCE))
        }
        if (nowMs - first >= maxMs) return listOf(close(first + maxMs, CloseReason.LENGTH))
        return emptyList()
    }

    fun forceClose(nowMs: Long = System.currentTimeMillis(), reason: CloseReason = CloseReason.MANUAL): ConversationBlock? = start?.let {
        close(maxOf(it + 1, minOf(nowMs, it + maxMs), lines.last().receivedMs), reason)
    }

    private fun close(endMs: Long, reason: CloseReason): ConversationBlock {
        val block = ConversationBlock(lines.toList(), start!!, endMs,
            if (reason == CloseReason.MANUAL) CaptureSource.MANUAL else CaptureSource.AUTO, reason)
        lines.clear(); start = null; onset = null; voiceUntil = null
        return block
    }

    fun reset() {
        lines.clear(); start = null; onset = null; quietSince = null; audioUntil = null; voiceUntil = null
    }
}
