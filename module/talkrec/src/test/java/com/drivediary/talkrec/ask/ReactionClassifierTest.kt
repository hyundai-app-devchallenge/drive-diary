package com.drivediary.talkrec.ask

import org.junit.Assert.assertEquals
import org.junit.Test

class ReactionClassifierTest {
    private fun check(expected: Reaction, vararg lines: String) =
        lines.forEach { assertEquals(it, expected, ReactionClassifier.classify(it)) }

    @Test fun accept() = check(Reaction.ACCEPT, "아니, 안 자", "응", "물어봐", "안 자요", "네", "아니 안자!")

    @Test fun decline() = check(Reaction.DECLINE, "자고 있어", "쉿", "나중에", "질문 그만", "지금 자는 중")

    @Test fun other() = check(Reaction.OTHER, "", "   ",
        "어제 저녁에 먹은 고기가 너무 맛있었는데 다음에 또 가자고 엄마가 그랬잖아",
        "저기 휴게소 보인다", "날씨 좋다")
}
