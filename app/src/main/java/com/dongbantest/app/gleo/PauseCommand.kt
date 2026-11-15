package com.dongbantest.app.gleo

import java.util.Locale

private val separators = Regex("[\\s\\p{Z}\\p{P}]+")
private const val FILLER = "(?:지금|이제|일단|잠시|잠깐|제발|좀)"

// With the (fuzzy) wake word, any lead-in is fine. Without it, only a short lead-in (up to 4 syllables) is
// tolerated, for a wake word misheard beyond the fuzzy class. Negation syllables (안/못/말/마) cannot sit in
// that lead-in, so it never turns a rejected "안 ... 녹음 멈춰" style sentence into a command.
private const val LEAD_IN = "(?:.*$WAKE_WORD_PATTERN|(?:(?![안못말마])[가-힣]){0,4})"
private val pauseCommand = Regex(
    "(?:글레오|클레오|gleo)?$LEAD_IN$FILLER*(?:여행|대화|음성)?녹음(?:을|은)?$FILLER*" +
        "(?:멈(?:춰|추어|춤)|(?:일시)?정지|중지|중단|꺼|끄|그만)" +
        "(?:(?:해|해줘|해줘요|해주세요|해주실래요|해줄래|해봐|줘|줘요|주세요|주실래요|줄래|자|세요|십시오|요))?",
)

/** Match a whole finalized utterance, including common spacing, particles and polite endings. */
internal fun isPauseRecordingCommand(text: String): Boolean =
    pauseCommand.matches(text.lowercase(Locale.ROOT).replace(separators, ""))
