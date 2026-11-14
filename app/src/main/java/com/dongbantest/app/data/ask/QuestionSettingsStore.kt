package com.dongbantest.app.data.ask

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 대화 질문 사용자 설정 (question-suggest DESIGN §7.2).
 * - 조용할 때 질문하기 켬/끔
 * - 질문 뒤 녹음하지 않는 시간(초). 0 = 빼지 않음(질문 소리만 뺀다)
 */
class QuestionSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("question_settings", Context.MODE_PRIVATE)
    private val enabledState = MutableStateFlow(prefs.getBoolean(ENABLED, true))
    private val answerState = MutableStateFlow(prefs.getInt(ANSWER_WINDOW, DEFAULT_ANSWER_WINDOW).let(::checked))
    val enabled = enabledState.asStateFlow()
    val answerWindowSeconds = answerState.asStateFlow()

    fun setEnabled(value: Boolean) {
        prefs.edit().putBoolean(ENABLED, value).apply()
        enabledState.value = value
    }

    fun setAnswerWindowSeconds(seconds: Int) {
        val value = checked(seconds)
        prefs.edit().putInt(ANSWER_WINDOW, value).apply()
        answerState.value = value
    }

    private fun checked(seconds: Int) = if (seconds in ANSWER_WINDOW_CHOICES) seconds else DEFAULT_ANSWER_WINDOW

    companion object {
        private const val ENABLED = "enabled"
        private const val ANSWER_WINDOW = "answer_window_seconds"
        const val DEFAULT_ANSWER_WINDOW = 90
        /** 설정 화면의 선택지와 같다(SettingsScreen.AnswerWindowChoices). */
        val ANSWER_WINDOW_CHOICES = listOf(0, 30, 60, 90, 180, 300)
    }
}
