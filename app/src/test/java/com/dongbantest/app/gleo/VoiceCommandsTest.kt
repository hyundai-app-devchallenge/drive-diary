package com.dongbantest.app.gleo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCommandsTest {
    @Test fun constantsMatchTheirOwnMatchers() {
        assertTrue(isAskNextCommand(VoiceCommands.ASK_NEXT))
        assertTrue(isStopAskingCommand(VoiceCommands.STOP_ASKING))
        assertTrue(isPauseRecordingCommand(VoiceCommands.PAUSE_RECORDING))
        assertEquals("동반차 녹음 다시 시작해", GleoRecordingAction.RESUME.example)
    }

    @Test fun recognizesAskNext() {
        listOf(
            "동반차 질문 해줘",
            "동반차 질문해 줘",
            "동반차 질문 좀 해줘",
            "동반자 질문해주세요",
            "동반차 다른 질문 해줘",
            "동반차 다른 질문 해 줘.",
            "동반자 다른 질문 해주세요",
            "글레오, 동반차 새 질문 해줘",
            "동반차 새로운 질문 줘",
            "동반차 질문 하나 해줘",
            "동반차 질문 하나 더 해봐",
            "동반차 다른 질문 좀 해 주세요!",
        ).forEach { assertTrue(it, isAskNextCommand(it)) }
    }

    @Test fun askNextRequiresInvokeWord() {
        listOf("질문 해줘", "다른 질문 해줘", "새 질문 해줘", "질문 하나 해줘", "글레오 다른 질문 해줘", "아빠 다른 질문 해줘")
            .forEach { assertFalse(it, isAskNextCommand(it)) }
    }

    @Test fun recognizesStopAsking() {
        listOf(
            "동반차 질문 그만해줘",
            "동반차 질문 그만 해 줘.",
            "동반자 질문 그만",
            "동반차 질문 그만해",
            "동반차 질문 그만해주세요",
            "글레오, 동반차 질문 하지 마",
            "동반차 질문하지 마세요",
        ).forEach { assertTrue(it, isStopAskingCommand(it)) }
    }

    @Test fun stopAskingRequiresInvokeWord() {
        listOf("질문 그만해줘", "질문 그만", "질문 하지 마", "글레오 질문 그만해줘").forEach {
            assertFalse(it, isStopAskingCommand(it))
        }
    }

    @Test fun rejectsLongerSentencesAndOtherCommands() {
        listOf(
            "동반차 다른 질문",
            "동반차 다른 질문 해줘 라고 말했어",
            "아까 동반차 다른 질문 해줘 했잖아",
            "동반차 녹음 멈춰",
            "동반차 질문 그만해줘 그리고 노래 틀어줘",
            "",
        ).forEach {
            assertFalse(it, isAskNextCommand(it))
            assertFalse(it, isStopAskingCommand(it))
        }
        assertFalse(isPauseRecordingCommand("동반차 다른 질문 해줘"))
        assertFalse(isPauseRecordingCommand("동반차 질문 그만해줘"))
    }

    @Test fun parsesCommands() {
        assertEquals(QuestionCommand.ASK_NEXT, parseQuestionCommand(VoiceCommands.ASK_NEXT))
        assertEquals(QuestionCommand.STOP_ASKING, parseQuestionCommand(VoiceCommands.STOP_ASKING))
        assertNull(parseQuestionCommand("동반차 녹음 멈춰"))
    }

    @Test fun stopAskingAcknowledgementCannotBeRecognizedAsAnAppVoiceCommand() {
        val notice = VoiceCommands.STOP_ASKING_ACKNOWLEDGEMENT
        listOf(notice, "$notice.", "  “$notice!”  ", notice.replace(" ", "")).forEach { text ->
            assertFalse(isStopAskingCommand(text))
            assertFalse(isAskNextCommand(text))
            assertFalse(isPauseRecordingCommand(text))
            assertNull(parseQuestionCommand(text))
        }
    }
}
