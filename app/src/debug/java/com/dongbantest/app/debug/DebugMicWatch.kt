package com.dongbantest.app.debug

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.drivediary.talkrec.TalkRec
import com.drivediary.talkrec.capture.CaptureStatus
import java.lang.ref.WeakReference
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** 안내를 띄울지 정하는 순수 로직. [armed] 가 true 면 이번 녹음 구간에서 아직 안내하지 않은 상태. */
object MicSilenceDecision {
    /** 녹음을 시작하고 이만큼 지나도록 말소리 크기의 소리가 한 번도 없으면 안내한다. */
    const val QUIET_THRESHOLD_MS = 20_000L

    data class Result(val warn: Boolean, val armed: Boolean)

    fun decide(status: CaptureStatus, usingTestAudio: Boolean, quietMs: Long, armed: Boolean): Result {
        if (status != CaptureStatus.On) return Result(warn = false, armed = true)
        if (usingTestAudio) return Result(warn = false, armed = armed)
        if (quietMs >= QUIET_THRESHOLD_MS && armed) return Result(warn = true, armed = false)
        return Result(warn = false, armed = armed)
    }
}

/**
 * 디버그 빌드 전용: 에뮬레이터 마이크가 죽어 있는지(PC 마이크 사용이 꺼졌거나 가상 사운드 카드가 고장) 처음에 알려 준다.
 *
 * 에뮬레이터는 마이크가 꺼져 있어도 0 이 아닌 아주 작은 잡음을 보내므로 "완전한 무음"으로는 가릴 수 없다.
 * 그래서 녹음을 시작하고 [MicSilenceDecision.QUIET_THRESHOLD_MS] 동안 말소리 크기의 소리가 한 번도 없으면 안내한다.
 * 소리가 한 번이라도 들어오면 마이크는 살아 있는 것이므로 이번 실행에서는 더 지켜보지 않는다.
 * TalkRec 의 상태 흐름만 본다. 앱 컨테이너는 건드리지 않는다.
 */
object DebugMicWatch {
    private const val TAG = "DebugMicWatch"
    private const val MESSAGE =
        "20초 동안 마이크에 소리가 잡히지 않았어요. 말을 했는데도 이 안내가 뜨면 에뮬레이터의 PC 마이크 사용을 켜거나 " +
            "에뮬레이터를 다시 시작해 주세요. (디버그 빌드 안내)"
    private const val MAX_WARNINGS = 2
    private const val BANNER_MS = 10_000L

    private var started = false

    /** 지금 화면에 보이는 Activity. Pleos 시스템은 앱의 토스트를 보여 주지 않아서, 안내를 그 화면 아래에 직접 얹는다. */
    private var resumed = WeakReference<Activity>(null)
    private val resumedTracker = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) { resumed = WeakReference(activity) }
        override fun onActivityPaused(activity: Activity) { if (resumed.get() === activity) resumed = WeakReference(null) }
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    @Synchronized
    fun start(context: Context) {
        if (started) return
        started = true
        (context.applicationContext as? Application)?.registerActivityLifecycleCallbacks(resumedTracker)
        val handler = CoroutineExceptionHandler { _, t -> Log.w(TAG, "watch failed: ${t.javaClass.simpleName}") }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + handler)
        scope.launch {
            var armed = true
            var warnings = 0
            // speech 는 100ms 마다 갱신되고, state 도 함께 바뀌므로 둘을 묶어 본다.
            combine(TalkRec.state, TalkRec.speech) { state, speech -> state to speech }.collect { (state, speech) ->
                val now = System.currentTimeMillis()
                // 실제 마이크에서 말소리 크기의 소리가 한 번이라도 들어오면 마이크는 살아 있다. 더 지켜보지 않는다.
                if (state.status == CaptureStatus.On && !state.usingTestAudio && speech.live(now) && speech.quietSinceMs == null) {
                    Log.i(TAG, "mic input confirmed; watch ends")
                    scope.cancel()
                    return@collect
                }
                val r = MicSilenceDecision.decide(state.status, state.usingTestAudio, speech.quietMs(now), armed)
                armed = r.armed
                if (r.warn) {
                    Log.w(TAG, "no sound from the mic since capture started")
                    showBanner()
                    // 같은 안내를 계속 띄우지 않는다. 앱을 켠 뒤 [MAX_WARNINGS] 번까지만.
                    if (++warnings >= MAX_WARNINGS) scope.cancel()
                }
            }
        }
    }

    /**
     * 화면 아래 가운데에 앱의 알림 줄(Snackbar)과 비슷한 어두운 안내를 [BANNER_MS] 동안 띄운다.
     * 보이는 화면이 없으면(앱이 뒤에 있음) 로그만 남는다.
     */
    private fun showBanner() {
        val activity = resumed.get() ?: return
        // 앱 화면은 기기 밀도가 아니라 창 높이(606dp 기준)로 배율을 정한다(DongbanchaV3Theme). 안내도 같은 배율로 그려야 글자 크기가 맞는다.
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        val scale = if (root.height > 0) root.height / 606f else activity.resources.displayMetrics.density
        fun dp(value: Float) = (value * scale).toInt()
        val banner = TextView(activity).apply {
            text = MESSAGE
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 15f * scale)
            maxWidth = dp(520f)
            setPadding(dp(20f), dp(14f), dp(20f), dp(14f))
            background = GradientDrawable().apply { setColor(0xF0222222.toInt()); cornerRadius = dp(12f).toFloat() }
            elevation = dp(6f).toFloat()
        }
        val params = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
        ).apply { setMargins(dp(24f), dp(24f), dp(24f), dp(32f)) }
        activity.addContentView(banner, params)
        banner.postDelayed({ (banner.parent as? ViewGroup)?.removeView(banner) }, BANNER_MS)
    }
}
