package com.dongbantest.app.debug

import com.dongbantest.app.domain.model.SummaryStatus
import com.dongbantest.app.domain.model.TalkMeta
import com.dongbantest.app.domain.model.TalkSource
import java.io.DataOutputStream
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** Pure fixture planning: positions depend on the catalog index, never the inserted DB ID. */
internal object SampleTalks {
    data class Plan(val startMs: Long, val durationMs: Long, val title: String, val tags: List<String>) {
        fun metadata(tripId: Long, index: Int): TalkMeta {
            val run = "sample-trip-$tripId"
            return TalkMeta(
                memoryId = 0, source = TalkSource.AUTO,
                blockStartMs = startMs, blockEndMs = startMs + durationMs,
                keepScore = 1.0, kind = "story", title = title, tags = tags,
                summaryStatus = SummaryStatus.DONE, summaryModel = "debug-fixture",
                runId = run, runClosed = true, storyId = "$run:$index", storyIndex = index,
                storyKind = "story", storyKeep = true,
            )
        }
    }

    private val topics = listOf(
        "휴게소 간식 고르기" to listOf("음식", "간식", "웃음"),
        "노래 맞히기 게임" to listOf("게임", "노래", "웃음"),
        "어릴 적 여행 이야기" to listOf("추억", "가족"),
        "도착하면 먹을 것" to listOf("음식", "맛집"),
        "다음에 함께 가고 싶은 곳" to listOf("여행", "약속"),
        "창밖 풍경에 붙인 별명" to listOf("웃음", "풍경", "추억"),
    )

    /** Snap to retained map-path points, so even sparse GPX routes get visible talk pins. */
    fun plan(tripIndex: Int, startedAt: Long, endedAt: Long, pathTimes: List<Long>): List<Plan> {
        require(tripIndex >= 0)
        val count = 2 + tripIndex % 3
        val span = endedAt - startedAt
        var previousEnd = startedAt
        return List(count) { index ->
            val duration = 5_000L + (tripIndex * 3 + index * 2) % 11 * 1_000L
            val jitter = ((tripIndex * 17 + index * 11) % 61 - 30) / 1_000.0
            val target = startedAt + (span * ((index + 1.0) / (count + 1) + jitter)).toLong()
            val start = requireNotNull(pathTimes.asSequence()
                .filter { it >= startedAt + 60_000 && it > previousEnd && it + duration <= endedAt - 60_000 }
                .minByOrNull { abs(it - target) }) { "Sample route has too few interior GPS points" }
            previousEnd = start + duration
            val (title, tags) = topics[(tripIndex + index) % topics.size]
            Plan(start, duration, title, tags)
        }
    }

    /** Quiet 16 kHz, mono, signed PCM16 WAV. Return duration calculated from the sample count. */
    fun writeAudio(file: File, durationMs: Long): Long {
        require(durationMs in 5_000L..15_000L)
        val sampleRate = 16_000
        val samples = (sampleRate * durationMs / 1_000).toInt()
        DataOutputStream(file.outputStream().buffered()).use { out ->
            fun le16(value: Int) { out.writeByte(value); out.writeByte(value ushr 8) }
            fun le32(value: Int) { le16(value); le16(value ushr 16) }
            out.writeBytes("RIFF"); le32(36 + samples * 2); out.writeBytes("WAVE")
            out.writeBytes("fmt "); le32(16); le16(1); le16(1)
            le32(sampleRate); le32(sampleRate * 2); le16(2); le16(16)
            out.writeBytes("data"); le32(samples * 2)
            val fadeSamples = sampleRate / 5
            repeat(samples) { i ->
                val envelope = minOf(1.0, i.toDouble() / fadeSamples, (samples - 1 - i).toDouble() / fadeSamples)
                le16((655 * envelope * sin(2 * PI * 220 * i / sampleRate)).toInt())
            }
        }
        return samples * 1_000L / sampleRate
    }
}
