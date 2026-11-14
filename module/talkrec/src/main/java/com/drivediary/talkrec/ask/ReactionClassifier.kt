package com.drivediary.talkrec.ask

import java.util.Locale

/**
 * Sorts one finalized STT line heard during the Q01 check into accept / decline / other.
 * The caller drops the text right after this call; nothing here stores or logs it.
 *
 * A short reply is evidence that someone is awake, even when STT mishears it.
 * Explicit declines take priority, excluding negated sleep expressions.
 * Longer lines are ordinary talk (OTHER): the family started talking by itself.
 */
object ReactionClassifier {
    private val separators = Regex("[\\s\\p{Z}\\p{P}~]+")
    private const val MAX_REPLY_LENGTH = 8

    private val negatedSleep = listOf("안자", "안잔", "안잤", "안졸")
    private val decline = listOf(
        "자고있", "잔다", "자요", "자는중", "자는데", "쉿", "나중에", "됐어", "됐다", "괜찮", "그만", "하지마", "조용", "졸려", "피곤",
    )
    private val replyPrefixes = listOf("아니", "아뇨", "응", "어", "네", "예", "웅")

    enum class Rule(val logValue: String) {
        EXPLICIT_DECLINE("explicit_decline"),
        LONG_REPLY("long_reply"),
        SHORT_PREFIX("short_prefix"),
        SHORT_REPLY("short_reply"),
    }

    /** Safe to retain across dispatch and log: contains no text or matched token. */
    data class Classification(val reaction: Reaction, val rule: Rule, val normalizedLength: Int)

    fun classify(text: String): Reaction = classifyWithReason(text)?.reaction ?: Reaction.OTHER

    /** Null means no reply; callers must leave the reaction window open. */
    fun classifyWithReason(text: String): Classification? {
        val t = text.lowercase(Locale.ROOT).replace(separators, "")
        if (t.isEmpty()) return null
        // Keep a gap when masking negation so unrelated fragments cannot form a decline.
        val declineText = negatedSleep.fold(t) { remaining, word -> remaining.replace(word, " ") }
        val rule = when {
            decline.any { it in declineText } -> Rule.EXPLICIT_DECLINE
            t.length > MAX_REPLY_LENGTH -> Rule.LONG_REPLY
            replyPrefixes.any { t.startsWith(it) } -> Rule.SHORT_PREFIX
            else -> Rule.SHORT_REPLY
        }
        val reaction = when (rule) {
            Rule.EXPLICIT_DECLINE -> Reaction.DECLINE
            Rule.LONG_REPLY -> Reaction.OTHER
            Rule.SHORT_PREFIX, Rule.SHORT_REPLY -> Reaction.ACCEPT
        }
        return Classification(reaction, rule, t.length)
    }
}
