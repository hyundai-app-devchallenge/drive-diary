package com.drivediary.talkrec.ask

import java.util.Locale

/**
 * Sorts one finalized STT line heard during the Q01 check into accept / decline / other.
 * The caller drops the text right after this call; nothing here stores or logs it.
 *
 * Accept is checked first so "안 자" is not read as the decline word "자".
 * Long lines are treated as ordinary talk (OTHER): the family started talking by itself.
 */
object ReactionClassifier {
    private val separators = Regex("[\\s\\p{Z}\\p{P}~]+")
    private const val MAX_LENGTH = 24

    private val strongAccept = listOf(
        "안자", "안잔", "안잤", "안졸", "깨어있", "깨있", "물어봐", "물어보", "질문해", "질문줘", "해줘", "해봐", "뭔데", "궁금",
    )
    private val decline = listOf(
        "자고있", "잔다", "자요", "자는중", "자는데", "쉿", "나중에", "됐어", "됐다", "괜찮", "그만", "하지마", "조용", "졸려", "피곤",
    )
    private val shortAccept = setOf(
        "응", "어", "네", "예", "아니", "아니요", "아니야", "아뇨", "아니오", "좋아", "좋아요", "그래", "그래요", "오케이",
        "ok", "okay", "웅", "ㅇㅇ", "당연하지", "물론", "좋지",
    )

    fun classify(text: String): Reaction {
        val t = text.lowercase(Locale.ROOT).replace(separators, "")
        if (t.isEmpty() || t.length > MAX_LENGTH) return Reaction.OTHER
        if (strongAccept.any { it in t }) return Reaction.ACCEPT
        if (decline.any { it in t }) return Reaction.DECLINE
        if (t in shortAccept) return Reaction.ACCEPT
        return Reaction.OTHER
    }
}
