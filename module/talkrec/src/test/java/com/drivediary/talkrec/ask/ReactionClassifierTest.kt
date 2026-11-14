package com.drivediary.talkrec.ask

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReactionClassifierTest {
    private fun check(expected: Reaction, vararg lines: String) =
        lines.forEach { assertEquals(it, expected, ReactionClassifier.classify(it)) }

    @Test fun accept() = check(Reaction.ACCEPT, "아니, 안 자", "응", "물어봐", "안 자요", "네", "아니 안자!")

    @Test fun decline() = check(Reaction.DECLINE, "자고 있어", "쉿", "나중에", "질문 그만", "지금 자는 중")

    @Test fun shortMisheardRepliesStillAccept() = check(Reaction.ACCEPT,
        "아니 앉아", "아니 안 짜", "아니 안짜", "아니 안 자", "아니 난 자", "아니 한자",
        "네 깨어 있어요")

    @Test fun shortRepliesWithEveryPrefixAccept() = check(Reaction.ACCEPT,
        "아니 별이야", "아뇨 별이야", "아니요 별이야", "응 별이야", "어 별이야",
        "네 별이야", "예 별이야", "웅 별이야")

    @Test fun otherShortSpeechIsEvidenceOfBeingAwake() = check(Reaction.ACCEPT,
        "별", "별빛이야", "저기 휴게소 보인다", "날씨 좋다", "좋아요", "오케이", "OK")

    @Test fun explicitDeclinesOverrideReplyPrefixesAndAcceptWords() = check(Reaction.DECLINE,
        "아니 자고 있어", "아니요 잔다", "아뇨 자요", "응 쉿", "어 나중에", "네 됐어",
        "예 괜찮아", "웅 그만", "하지 마", "조용히", "졸려", "피곤해", "자는 중", "자는데",
        "물어봐 나중에", "안 자 그만", "안 졸려도 피곤해", "안 자요, 자고 있어요",
        "네 별 이야기는 나중에 해 주세요")

    @Test fun negatedSleepDoesNotDecline() = check(Reaction.ACCEPT,
        "안 자", "안자", "안 자요", "안 잔다", "안 잤어", "안 졸려", "안 자고 있어", "아니 안 자고 있어요")

    @Test fun shortReplyLimitUsesNormalizedLength() {
        check(Reaction.ACCEPT, "별빛따라길을가요", "별빛, 따라 길을 가요!", "네오늘별빛이밝네")
        check(Reaction.OTHER, "별빛따라길을가볼까", "네오늘별빛이참밝네")
        assertEquals(8, ReactionClassifier.classifyWithReason("별빛, 따라 길을 가요!")?.normalizedLength)
        assertEquals(9, ReactionClassifier.classifyWithReason("별빛따라길을가볼까")?.normalizedLength)
    }

    @Test fun other() = check(Reaction.OTHER, "", "   ",
        "어제 저녁에 먹은 고기가 너무 맛있었는데 다음에 또 가자고 엄마가 그랬잖아",
        "네 별빛이 예뻐서 계속 보고 있어요", "궁금해서 별 이야기를 해 줘")

    @Test fun emptyAndSeparatorOnlyFinalsAreNotReplies() {
        listOf("", "   ", "\t\r\n", "\u00a0\u3000", "...?! ~", "，。！？").forEach {
            assertNull(ReactionClassifier.classifyWithReason(it))
            assertEquals(Reaction.OTHER, ReactionClassifier.classify(it))
        }
    }

    @Test fun reasonsContainOnlyRuleAndNormalizedLength() {
        fun checkReason(text: String, reaction: Reaction, rule: ReactionClassifier.Rule, length: Int) {
            assertEquals(ReactionClassifier.Classification(reaction, rule, length),
                ReactionClassifier.classifyWithReason(text))
        }
        checkReason("아니 앉아", Reaction.ACCEPT, ReactionClassifier.Rule.SHORT_PREFIX, 4)
        checkReason("네,\u00a0별!", Reaction.ACCEPT, ReactionClassifier.Rule.SHORT_PREFIX, 2)
        checkReason("별빛이야", Reaction.ACCEPT, ReactionClassifier.Rule.SHORT_REPLY, 4)
        checkReason("아니, 자고 있어!", Reaction.DECLINE, ReactionClassifier.Rule.EXPLICIT_DECLINE, 6)
        checkReason("별빛따라길을가볼까", Reaction.OTHER, ReactionClassifier.Rule.LONG_REPLY, 9)
    }

    @Test fun emptyFinalLeavesCheckOpenThenShortReplyAsks() {
        val watcher = QuietWatcher()
        watcher.forceCheck(0)
        fun reply(text: String, now: Long): AskEvent? = ReactionClassifier.classifyWithReason(text)?.let {
            watcher.onReaction(it.reaction, now)
        }
        assertNull(reply("", 100))
        assertNull(reply("...", 200))
        assertEquals(AskPhase.CHECKING, watcher.phase)
        assertEquals(0, watcher.askedCount)
        assertEquals(AskEvent.Ask(false), reply("아니 앉아", 300))
        assertEquals(AskPhase.ASKING, watcher.phase)
        assertEquals(1, watcher.askedCount)
    }
}
