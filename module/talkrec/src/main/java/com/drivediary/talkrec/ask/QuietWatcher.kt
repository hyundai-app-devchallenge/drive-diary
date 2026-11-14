package com.drivediary.talkrec.ask

/** Timing rules for "ask only when it is quiet" (question-suggest PLAN §4). Debug may shorten them. */
data class QuestionPolicy(
    /** Continuous quiet needed before the on-screen check. */
    val quietMs: Long = 8 * 60_000L,
    /** Start preparing (weather + generation) this long before [quietMs]. */
    val prepareLeadMs: Long = 2 * 60_000L,
    val minTripAgeMs: Long = 10 * 60_000L,
    /** Skip when the destination is closer than this. Ignored when there is no destination. */
    val minRemainSeconds: Int = 15 * 60,
    val cooldownMs: Long = 20 * 60_000L,
    val maxPerTrip: Int = 3,
    /** How long the on-screen check waits for a spoken reaction. */
    val reactionWindowMs: Long = 10_000L,
    /** Conversation starting this long after the question is not recorded. 0 = only the TTS itself. */
    val answerWindowMs: Long = 90_000L,
    /** Safety net when TTS never reports done. */
    val askTimeoutMs: Long = 30_000L,
) {
    init {
        require(quietMs > 0 && prepareLeadMs >= 0 && minTripAgeMs >= 0 && minRemainSeconds >= 0)
        require(cooldownMs >= 0 && maxPerTrip >= 0 && reactionWindowMs > 0 && answerWindowMs >= 0 && askTimeoutMs > 0)
    }
}

enum class AskPhase { OFF, LISTENING, PREPARING, CHECKING, ASKING, ANSWERING, MUTED }

enum class Reaction { ACCEPT, DECLINE, OTHER }

/** One-second snapshot the coordinator feeds in. No conversation text. */
data class AskInputs(
    val nowMs: Long,
    /** Capture is On and audio frames are arriving. */
    val listening: Boolean,
    /** Continuous quiet so far; 0 while someone speaks or the input is unknown. */
    val quietMs: Long,
    val tripStartedAtMs: Long,
    /** Navigation remaining time; null when no destination is set. */
    val remainSeconds: Int?,
)

sealed interface AskEvent {
    /** Fetch weather and generate a question now, so it is ready if the check is accepted. */
    data object Prepare : AskEvent
    /** Show Q01 (screen only) and open the reaction window. */
    data object ShowCheck : AskEvent
    /** Speak the question (TTS) and show Q02. Forced asks do not count toward the per-trip limit. */
    data class Ask(val forced: Boolean) : AskEvent
    /** Stop asking first for the rest of this trip. */
    data class Muted(val reason: String) : AskEvent
    /** Back to listening without asking (talk resumed, conditions changed, answer window over). */
    data class Listening(val reason: String) : AskEvent
    /** Capture stopped or paused: hide everything, stop TTS. */
    data object Stopped : AskEvent
}

/**
 * Single-owner state machine for one trip. Pure Kotlin: the caller provides time and inputs.
 *
 * LISTENING → PREPARING → CHECKING → ASKING → ANSWERING → LISTENING, with MUTED for the rest of the trip
 * after a decline or no reaction. See question-suggest DESIGN §4.
 */
class QuietWatcher(var policy: QuestionPolicy = QuestionPolicy()) {
    var phase: AskPhase = AskPhase.OFF
        private set
    var askedCount: Int = 0
        private set
    var muted: Boolean = false
        private set
    private var phaseAtMs = 0L
    private var lastAskedAtMs: Long? = null
    private var heardDuringCheck = false

    fun tick(i: AskInputs): AskEvent? {
        if (!i.listening) {
            if (phase == AskPhase.OFF) return null
            go(AskPhase.OFF, i.nowMs)
            return AskEvent.Stopped
        }
        return when (phase) {
            AskPhase.OFF -> { go(if (muted) AskPhase.MUTED else AskPhase.LISTENING, i.nowMs); null }
            AskPhase.LISTENING ->
                if (eligible(i) && i.quietMs >= policy.quietMs - policy.prepareLeadMs) {
                    go(AskPhase.PREPARING, i.nowMs); AskEvent.Prepare
                } else null
            AskPhase.PREPARING -> when {
                i.quietMs == 0L -> { go(AskPhase.LISTENING, i.nowMs); AskEvent.Listening("talk") }
                !eligible(i) -> { go(AskPhase.LISTENING, i.nowMs); AskEvent.Listening("conditions") }
                i.quietMs >= policy.quietMs -> startCheck(i.nowMs)
                else -> null
            }
            AskPhase.CHECKING -> {
                if (i.quietMs == 0L) heardDuringCheck = true
                if (i.nowMs - phaseAtMs < policy.reactionWindowMs) null
                // Voice without a recognized reaction: the talk resumed by itself.
                else if (heardDuringCheck) { go(AskPhase.LISTENING, i.nowMs); AskEvent.Listening("talk") }
                else mute(i.nowMs, "no_reaction")
            }
            AskPhase.ASKING ->
                if (i.nowMs - phaseAtMs >= policy.askTimeoutMs) { spoken(i.nowMs); null } else null
            AskPhase.ANSWERING ->
                if (i.nowMs - phaseAtMs >= policy.answerWindowMs) {
                    go(AskPhase.LISTENING, i.nowMs); AskEvent.Listening("answer_window")
                } else null
            AskPhase.MUTED -> null
        }
    }

    /** A recognized reaction while the check is shown. Ignored in other phases. */
    fun onReaction(reaction: Reaction, nowMs: Long): AskEvent? {
        if (phase != AskPhase.CHECKING) return null
        return when (reaction) {
            Reaction.ACCEPT -> { muted = false; ask(nowMs, forced = false) }
            Reaction.DECLINE -> mute(nowMs, "declined")
            Reaction.OTHER -> { go(AskPhase.LISTENING, nowMs); AskEvent.Listening("talk") }
        }
    }

    /** TTS finished (or failed): the answer window starts. */
    fun spoken(nowMs: Long) {
        if (phase == AskPhase.ASKING) go(AskPhase.ANSWERING, nowMs)
    }

    /** Debug "pretend it is quiet": show the check now, ignoring time conditions and mute. */
    fun forceCheck(nowMs: Long): AskEvent = startCheck(nowMs)

    /** Debug or stopped-screen "ask now": skips the check and does not count toward the limit. */
    fun forceAsk(nowMs: Long): AskEvent = ask(nowMs, forced = true)

    /** "질문 그만" from the screen, or an external mute. */
    fun mute(nowMs: Long, reason: String): AskEvent {
        muted = true
        go(if (phase == AskPhase.OFF) AskPhase.OFF else AskPhase.MUTED, nowMs)
        return AskEvent.Muted(reason)
    }

    fun unmute(nowMs: Long) {
        muted = false
        if (phase == AskPhase.MUTED) go(AskPhase.LISTENING, nowMs)
    }

    private fun startCheck(nowMs: Long): AskEvent {
        heardDuringCheck = false
        go(AskPhase.CHECKING, nowMs)
        return AskEvent.ShowCheck
    }

    private fun ask(nowMs: Long, forced: Boolean): AskEvent {
        if (!forced) askedCount++
        lastAskedAtMs = nowMs
        go(AskPhase.ASKING, nowMs)
        return AskEvent.Ask(forced)
    }

    private fun eligible(i: AskInputs): Boolean =
        !muted && askedCount < policy.maxPerTrip &&
            i.nowMs - i.tripStartedAtMs >= policy.minTripAgeMs &&
            (i.remainSeconds == null || i.remainSeconds >= policy.minRemainSeconds) &&
            lastAskedAtMs.let { it == null || i.nowMs - it >= policy.cooldownMs }

    private fun go(next: AskPhase, nowMs: Long) { phase = next; phaseAtMs = nowMs }
}
