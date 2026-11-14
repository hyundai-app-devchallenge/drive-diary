package com.dongbantest.app.ui.v3connect

import com.dongbantest.app.data.ask.QuestionUi
import com.dongbantest.app.ui.v3.screens.DrivingCard
import com.dongbantest.app.ui.v3.screens.DrivingUiState

/**
 * 대화 질문(question-suggest) 상태를 여행 중 화면에 반영한다.
 * - 이번 여행에서 먼저 묻지 않기로 했으면(무반응·거절·질문 그만) B01_QUIET 카드
 * - 동반이가 건넨 질문이 있으면 질문 카드에 그 질문
 */
internal fun DrivingUiState.withQuestion(question: QuestionUi?): DrivingUiState {
    question ?: return this
    val card = card
    return when {
        question.muted && card is DrivingCard.Question -> copy(
            card = DrivingCard.Quiet(
                "이번 여행에서는 먼저 묻지 않을게요",
                "이야기가 필요하면 ‘다른 질문 해 줘’라고 말해 주세요.",
                card.pauseCaption,
            ),
            nowText = "조용히 듣는 중",
        )
        question.lastQuestion != null && card is DrivingCard.Question -> copy(card = card.copy(question = question.lastQuestion))
        else -> this
    }
}
