package com.dongbantest.app.gleo

import java.util.Locale

/** User-facing command sentences. Screen copy and the screen-tap path compare against these. */
object VoiceCommands {
    const val PAUSE_RECORDING = "동반차 녹음 멈춰"
    const val PAUSE_RECORDING_ACKNOWLEDGEMENT = "네, 동반차 녹음 중단할게요"
    const val STOP_ASKING_ACKNOWLEDGEMENT = "네, 이번 여행에서는 먼저 질문하지 않을게요"
    const val RESUME_RECORDING = "동반차 녹음 다시 시작해"
    const val ASK_NEXT = "동반차 질문 해줘"
    const val STOP_ASKING = "동반차 질문 그만해줘"
}

enum class QuestionCommand { ASK_NEXT, STOP_ASKING }

private val commandSeparators = Regex("[\\s\\p{Z}\\p{P}]+")
private const val CMD_FILLER = "(?:지금|이제|일단|잠시|잠깐|제발|좀)"

/**
 * "동반차" as on-device STT may hear it. It is a made-up word, so the recognizer often swaps a syllable
 * for a phonetic neighbour (동방차, 돈반차, 동반자 ...) and may append a particle (통반차야). Matching the
 * neighbours keeps the spoken command working without making the wake word any shorter.
 */
internal const val WAKE_WORD_PATTERN = "(?:[동돈통똥][반방밤박발판빵][차자짜챠체채처](?:가|야|아|는|은)?)"

// Anything may precede the wake word: a final utterance is often merged with words spoken just before it.
private const val CMD_PREFIX = "(?:.*$WAKE_WORD_PATTERN)$CMD_FILLER*"
private const val DO_IT =
    "(?:해줘|해줘요|해주세요|해주실래요|해줄래|해줄래요|해봐|해봐요|해요|해|줘|줘요|주세요|주실래요|줄래|줄래요)"

// The invoke word "동반차" is mandatory: plain "질문 해줘" is normal family talk.
// "동반차 질문 해줘" is the main phrase; "다른/새 질문", "질문 하나 (더)" also work.
private val askNextCommand = Regex(
    "$CMD_PREFIX(?:(?:다른|새|새로운)?질문(?:을|하나)?(?:더)?(?:를)?)$CMD_FILLER*$DO_IT",
)
private val stopAskingCommand = Regex(
    "${CMD_PREFIX}질문(?:은|을|도)?$CMD_FILLER*" +
        "(?:그만(?:$DO_IT|둬|두자|두세요)?|하지(?:마|마요|마세요|말아줘|말아요|말아주세요|말래))",
)

private fun normalizeCommand(text: String) = text.lowercase(Locale.ROOT).replace(commandSeparators, "")

internal fun isAskNextCommand(text: String): Boolean = askNextCommand.matches(normalizeCommand(text))

internal fun isStopAskingCommand(text: String): Boolean = stopAskingCommand.matches(normalizeCommand(text))

internal fun parseQuestionCommand(text: String): QuestionCommand? = when {
    isAskNextCommand(text) -> QuestionCommand.ASK_NEXT
    isStopAskingCommand(text) -> QuestionCommand.STOP_ASKING
    else -> null
}
