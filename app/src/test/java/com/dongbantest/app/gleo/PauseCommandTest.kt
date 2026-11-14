package com.dongbantest.app.gleo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PauseCommandTest {
    @Test fun recognizesPauseCommands() {
        listOf(
            "동반차 녹음 멈춰",
            "동반자 녹음 멈춰.",
            "글레오, 동반차 녹음 꺼줘",
            "녹음 멈춰",
            "녹음 정지",
            "녹음을 중단해 주세요",
            "녹음 일시 정지",
            "녹음 잠시 멈춰",
            "동반차 녹음 그만해 주세요",
            "지금 음성 녹음 꺼 줄래?",
        ).forEach { assertTrue(it, isPauseRecordingCommand(it)) }
    }

    @Test fun recognizesEveryStopVerb() {
        listOf("멈춰", "멈춤", "멈추어", "정지", "중지", "중단", "꺼", "끄", "그만").forEach {
            assertTrue(it, isPauseRecordingCommand("녹음 $it"))
        }
    }

    @Test fun allowsLongCommandsWithEveryCue() {
        listOf("동반차", "동반자", "글레오", "클레오", "GLEO").forEach {
            val text = "$it 지금 여행 녹음 좀 멈춰 주세요"
            assertTrue(text, isPauseRecordingCommand(text))
        }
    }

    @Test fun removesWhitespaceAndPunctuation() {
        assertTrue(isPauseRecordingCommand("  “G L E O”,\t녹음\n멈춰!  "))
        assertTrue(isPauseRecordingCommand("녹음\u00a0정지。"))
    }

    @Test fun acceptsPoliteCommandsWithoutWakeWord() {
        assertTrue(isPauseRecordingCommand("녹음 좀 지금 멈춰 주세요"))
        assertTrue(isPauseRecordingCommand("녹음 좀 지금 멈춰 주실래요"))
    }

    @Test fun rejectsNegationEvenWithCue() {
        listOf(
            "녹음 멈추지 마",
            "녹음 안 꺼도 돼",
            "동반차 녹음 멈추지 마세요",
            "글레오, 녹음 끄지 말아 줘",
            "동반자 녹음 정지하지 말고 계속해",
            "녹음 중지하지 않아도 돼",
            "녹음 꺼서는 안 돼",
            "녹음 끄면 안 되지",
            "녹음 안 멈춰도 돼",
            "녹음 안 정지해도 돼",
            "녹음 안 중지해도 돼",
            "녹음 안 그만해도 돼",
        ).forEach { assertFalse(it, isPauseRecordingCommand(it)) }
    }

    @Test fun rejectsOtherTopicsAndLongConversationWithoutCue() {
        listOf(
            "아까 녹음 꺼놨던 거 기억나?",
            "오늘 동반차 타고 바다 갔잖아",
            "음악 꺼줘",
            "동반차 음악 꺼줘",
            "동반차 녹음 다시 시작해",
            "여행 중 녹음 멈추는 방법을 이야기했어",
            "동반차가 녹음 정지라고 말하면 멈추더라",
            "동반자 녹음 중단 방법",
            "녹음 정지 버튼",
            "녹음 다시 시작하고 음악 꺼줘",
            "",
        ).forEach { assertFalse(it, isPauseRecordingCommand(it)) }
    }
}
