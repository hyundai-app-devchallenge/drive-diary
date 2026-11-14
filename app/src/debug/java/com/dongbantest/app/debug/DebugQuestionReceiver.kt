package com.dongbantest.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.dongbantest.app.DongbanApplication
import com.dongbantest.app.gleo.QuestionCommand
import com.dongbantest.app.gleo.performGleoAskQuestion
import com.drivediary.talkrec.ask.QuestionPolicy
import com.drivediary.talkrec.ask.Reaction
import kotlinx.coroutines.launch

/**
 * 디버그 빌드 전용: 조용할 때 묻기를 adb 로 흉내 낸다. 대화 내용은 다루지 않고 동작 이름만 남긴다.
 *
 * adb shell am broadcast --user 10 -a com.dongbantest.app.debug.ASK_FORCE_QUIET -n com.dongbantest.app/.debug.DebugQuestionReceiver
 * adb shell am broadcast --user 10 -a com.dongbantest.app.debug.ASK_REACTION -n com.dongbantest.app/.debug.DebugQuestionReceiver --es kind accept
 * adb shell am broadcast --user 10 -a com.dongbantest.app.debug.ASK_SHORT_POLICY -n com.dongbantest.app/.debug.DebugQuestionReceiver --ez on true
 * adb shell am broadcast --user 10 -a com.dongbantest.app.debug.ASK_VOICE -n com.dongbantest.app/.debug.DebugQuestionReceiver --es cmd next   (or stop / pause)
 * adb shell am broadcast --user 10 -a com.dongbantest.app.debug.ASK_GLEO -n com.dongbantest.app/.debug.DebugQuestionReceiver
 */
class DebugQuestionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as DongbanApplication).container
        val coordinator = container.questionCoordinator
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
            // Same app STT dispatch/feedback; never invokes Gleo or logs command text.
            "com.dongbantest.app.debug.ASK_VOICE" -> when (intent.getStringExtra("cmd")) {
                "next" -> container.talkRecording.dispatchQuestionCommand(QuestionCommand.ASK_NEXT)
                "stop" -> container.talkRecording.dispatchQuestionCommand(QuestionCommand.STOP_ASKING)
                "pause" -> container.talkRecording.dispatchVoicePauseCommand()
                else -> Unit
            }
            // Same code path as the Gleo ASK_NEW_QUESTION receiver, without the APP_ACTION_RESPONSE callback.
            "com.dongbantest.app.debug.ASK_GLEO" -> {
                val pending = goAsync()
                container.appScope.launch {
                    try {
                        val result = performGleoAskQuestion(container)
                        Log.i(TAG, "ASK_GLEO success=${result.success}")
                    } finally {
                        pending.finish()
                    }
                }
            }
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
