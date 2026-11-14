package com.drivediary.talkrec.ask

import java.util.Calendar
import org.json.JSONArray
import org.json.JSONObject

/**
 * What is sent to the external model to make one question (question-suggest DESIGN §5, §7).
 * Never coordinates, passenger names, transcripts or audio. Only place names and coarse values.
 */
data class QuestionContext(
    val destination: String?,
    /** City/district level, e.g. "강원 평창군". */
    val area: String?,
    /** Rounded to 10 minutes. */
    val remainMinutes: Int?,
    val timeOfDay: String,
    val weekday: String,
    val weatherNow: String?,
    val weatherDestination: String?,
    /** For now always "가족" (PLAN §10-5). */
    val relation: String = DEFAULT_RELATION,
    val previousQuestions: List<String> = emptyList(),
) {
    fun toJson(): String = JSONObject()
        .put("relation", relation)
        .put("destination", destination ?: JSONObject.NULL)
        .put("area", area ?: JSONObject.NULL)
        .put("remain_minutes", remainMinutes ?: JSONObject.NULL)
        .put("time_of_day", timeOfDay)
        .put("weekday", weekday)
        .put("weather_now", weatherNow ?: JSONObject.NULL)
        .put("weather_destination", weatherDestination ?: JSONObject.NULL)
        .put("previous_questions", JSONArray(previousQuestions.takeLast(MAX_PREVIOUS)))
        .toString(2)

    companion object {
        const val DEFAULT_RELATION = "가족"
        const val MAX_PREVIOUS = 5
        private val detailToken = Regex(".*(동|읍|면|리|로|길|가)$|.*\\d.*")

        /** "경기 성남시 수정구 시흥동 123" → "경기 성남시". Keeps at most two coarse tokens. */
        fun coarseArea(address: String?): String? {
            val tokens = address?.trim()?.split(Regex("\\s+"))?.filter { it.isNotBlank() }.orEmpty()
            val coarse = tokens.takeWhile { !detailToken.matches(it) }.take(2)
            return coarse.joinToString(" ").takeIf { it.isNotBlank() }
        }

        fun roundedMinutes(remainSeconds: Int?): Int? =
            remainSeconds?.takeIf { it > 0 }?.let { s -> maxOf(10, ((s / 60.0) / 10).let(Math::round).toInt() * 10) }

        fun timeOfDay(hour: Int): String = when (hour) {
            in 0..4 -> "새벽"
            in 5..9 -> "아침"
            in 10..11 -> "오전"
            in 12..13 -> "점심"
            in 14..17 -> "오후"
            in 18..20 -> "저녁"
            else -> "밤"
        }

        fun weekday(calendar: Calendar): String =
            listOf("일요일", "월요일", "화요일", "수요일", "목요일", "금요일", "토요일")[calendar.get(Calendar.DAY_OF_WEEK) - 1]
    }
}
