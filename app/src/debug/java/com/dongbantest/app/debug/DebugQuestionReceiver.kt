package com.dongbantest.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.dongbantest.app.DongbanApplication
import com.drivediary.talkrec.ask.QuestionPolicy
import com.drivediary.talkrec.ask.Reaction

/**
 * 디버그 빌드 전용: 조용할 때 묻기를 adb 로 흉내 낸다. 대화 내용은 다루지 않고 동작 이름만 남긴다.
 *
 * adb shell am broadcast --user 10 -a com.dongbantest.app.debug.ASK_FORCE_QUIET -n com.dongbantest.app/.debug.DebugQuestionReceiver
 * adb shell am broadcast --user 10 -a com.dongbantest.app.debug.ASK_REACTION -n com.dongbantest.app/.debug.DebugQuestionReceiver --es kind accept
 * adb shell am broadcast --user 10 -a com.dongbantest.app.debug.ASK_SHORT_POLICY -n com.dongbantest.app/.debug.DebugQuestionReceiver --ez on true
 */
class DebugQuestionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val coordinator = (context.applicationContext as DongbanApplication).container.questionCoordinator
        val action = intent.action.orEmpty()
        Log.i(TAG, action.substringAfterLast('.'))
        when (action) {
            "com.dongbantest.app.debug.ASK_FORCE_QUIET" -> coordinator.forceCheck()
            "com.dongbantest.app.debug.ASK_FORCE_QUESTION" -> coordinator.forceAsk()
            "com.dongbantest.app.debug.ASK_REACTION" -> when (intent.getStringExtra("kind")) {
                "accept" -> Reaction.ACCEPT
                "decline" -> Reaction.DECLINE
                "other" -> Reaction.OTHER
                else -> null
            }?.let { coordinator.debugReaction(it) }
            "com.dongbantest.app.debug.ASK_SHORT_POLICY" ->
                coordinator.policyOverride = if (intent.getBooleanExtra("on", false)) SHORT_POLICY else null
        }
    }

    private companion object {
        const val TAG = "DebugQuestion"
        val SHORT_POLICY = QuestionPolicy(
            quietMs = 30_000L, prepareLeadMs = 10_000L, minTripAgeMs = 0L, minRemainSeconds = 0,
            cooldownMs = 60_000L, maxPerTrip = 10, answerWindowMs = 30_000L,
        )
    }
}
