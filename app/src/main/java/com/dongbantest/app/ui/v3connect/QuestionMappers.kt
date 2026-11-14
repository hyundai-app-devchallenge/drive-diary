package com.dongbantest.app.ui.v3connect

import com.dongbantest.app.data.ask.QuestionOverlay
import com.dongbantest.app.data.ask.QuestionUi
import com.dongbantest.app.ui.v3.screens.DrivingCard
import com.dongbantest.app.ui.v3.screens.DrivingUiState

/**
 * 대화 질문(question-suggest) 상태를 여행 중 화면에 반영한다.
 * - 동반이가 지금 질문을 읽는 중이면(Q02) 창을 띄우지 않고 "우리의 여행" 질문 카드에 그 질문을 바로 보인다.
 *   먼저 묻지 않기(muted) 중에 직접 요청한 질문이어도 질문 카드로 바꿔 보인다.
 * - 이번 여행에서 먼저 묻지 않기로 했으면(무반응·거절·질문 그만) B01_QUIET 카드
 * - 동반이가 건넨 질문이 있으면 질문 카드에 그 질문
 */
internal fun DrivingUiState.withQuestion(question: QuestionUi?): DrivingUiState {
    question ?: return this
    val card = card
    val asking = question.overlay as? QuestionOverlay.Asking
    return when {
        asking != null -> copy(
            // 읽는 동안과 읽은 뒤 카드 모양이 같게: 태그·안내 문구는 지금 카드 것을 쓴다.
            card = DrivingCard.Question(
                question = asking.question,
                say = questionSay(recording),
                tag = (card as? DrivingCard.Question)?.tag ?: DrivingCard.Question("", emptyList()).tag,
                hint = asking.hint.ifBlank { (card as? DrivingCard.Question)?.hint ?: DrivingCard.Question("", emptyList()).hint },
                sayLabel = questionSayLabel(recording),
                pauseCaption = when (card) {
                    is DrivingCard.Question -> card.pauseCaption
                    is DrivingCard.Quiet -> card.pauseHint
                },
            ),
            nowText = "동반이가 묻는 중",
        )
        question.muted -> copy(
            card = DrivingCard.Quiet(
                MUTED_TITLE,
                quietAskBody(recording),
                when (card) {
                    is DrivingCard.Question -> card.pauseCaption
                    is DrivingCard.Quiet -> card.pauseHint
                },
            ),
            nowText = "조용히 듣는 중",
        )
        question.lastQuestion != null && card is DrivingCard.Question -> copy(card = card.copy(question = question.lastQuestion))
        else -> this
    }
}
