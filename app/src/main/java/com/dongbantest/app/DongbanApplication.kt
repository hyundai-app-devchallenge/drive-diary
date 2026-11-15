package com.dongbantest.app

import android.app.Application
import android.content.Context
import android.util.Log
import com.dongbantest.app.data.demo.DemoSeeder
import com.dongbantest.app.data.ask.QuestionCoordinator
import com.dongbantest.app.data.ask.QuestionSettingsStore
import com.dongbantest.app.data.local.DongbanDatabase
import com.dongbantest.app.data.map.MapboxTokenStore
import com.drivediary.talkrec.ask.PleosQuestionSpeaker
import com.dongbantest.app.data.repository.MediaFileStore
import com.dongbantest.app.data.repository.TripRepository
import com.dongbantest.app.data.repository.RecordingConsentStore
import com.dongbantest.app.data.talk.TalkRecordingController
import com.dongbantest.app.data.talk.TalkSinkImpl
import com.dongbantest.app.gleo.QuestionCommand
import com.dongbantest.app.data.talk.RepositorySummaryTarget
import com.dongbantest.app.data.talk.RecordingSettingsStore
import com.drivediary.talkrec.summary.SummaryException
import com.drivediary.talkrec.summary.SummarySettingsStore
import com.drivediary.talkrec.summary.SummaryWorkScheduler
import com.drivediary.talkrec.summary.SummaryWorkerDependencies
import com.drivediary.talkrec.summary.SummaryWorkerOwner
import com.dongbantest.app.location.LocationReader
import com.dongbantest.app.location.LocationTracker
import com.dongbantest.app.navi.ArrivalManager
import com.dongbantest.app.navi.ArrivalNotifier
import com.dongbantest.app.navi.NaviDestination
import com.dongbantest.app.navi.NaviMonitor
import com.dongbantest.app.navi.TripPlaceRecorder
import com.dongbantest.app.vehicle.DrivingSafetyMonitor
import com.dongbantest.app.vehicle.VehicleSignalReader
import com.dongbantest.app.vehicle.VehicleSignalTracker
import com.dongbantest.app.vehicle.VehicleRepository
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

/** 간단한 수동 DI 컨테이너. */
class AppContainer(context: Context) : SummaryWorkerDependencies {
    val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate +
            CoroutineExceptionHandler { _, e -> Log.e("AppScope", "uncaught ${e.javaClass.simpleName}") },
    )
    private val database by lazy { DongbanDatabase.create(context) }
    val mediaFileStore by lazy { MediaFileStore(context) }
    val tripRepository by lazy { TripRepository(database.tripDao(), mediaFileStore) }
    val demoSeeder by lazy { DemoSeeder(context, tripRepository, mediaFileStore) }
    val mapboxTokenStore by lazy { MapboxTokenStore(context) }
    val recordingConsentStore by lazy { RecordingConsentStore(context) }
    val recordingSettingsStore by lazy { RecordingSettingsStore(context) }
    override val summarySettingsStore by lazy { SummarySettingsStore(context) }
    override val summaryTarget by lazy { RepositorySummaryTarget(tripRepository, recordingConsentStore) }
    val talkSink by lazy {
        TalkSinkImpl(tripRepository, recordingConsentStore,
            onPendingSaved = { id -> SummaryWorkScheduler.enqueue(context, id) })
    }
    val talkRecording by lazy {
        TalkRecordingController(
            context, tripRepository, recordingConsentStore, talkSink, recordingSettingsStore, appScope,
            speakPause = { questionCoordinator.speakRecordingPaused(it) },
            stopPauseSpeech = { questionCoordinator.stopRecordingPauseSpeech() },
        )
    }
    val vehicleRepository by lazy { VehicleRepository(context, appScope) }
    val drivingSafetyMonitor by lazy { DrivingSafetyMonitor(context) }
    val locationTracker by lazy { LocationTracker(LocationReader(context), tripRepository, appScope) }
    val vehicleSignalTracker by lazy { VehicleSignalTracker(VehicleSignalReader(context), tripRepository, appScope) }
    val naviMonitor by lazy { NaviMonitor(context) }
    val tripPlaceRecorder by lazy { TripPlaceRecorder(naviMonitor, tripRepository, appScope) }
    val arrivalManager by lazy {
        ArrivalManager(
            tripRepository, vehicleRepository, locationTracker, vehicleSignalTracker,
            tripPlaceRecorder, ArrivalNotifier(context), appScope,
        )
    }
    // 조용할 때만 건네는 대화 질문 (talk-rec/docs/question-suggest)
    val questionSettingsStore by lazy { QuestionSettingsStore(context) }
    val questionSpeaker by lazy { PleosQuestionSpeaker(context) }
    val questionCoordinator by lazy {
        QuestionCoordinator(
            context, appScope, tripRepository, recordingConsentStore, summarySettingsStore, questionSettingsStore,
            naviMonitor, arrivalManager, locationTracker, questionSpeaker,
        )
    }

    fun startSummaries(context: Context) {
        appScope.launch {
            seedCloudflareDefaults(context)
            tripRepository.listPendingTalkSummaries().maxOfOrNull { it.id }?.let {
                SummaryWorkScheduler.enqueue(context, it)
            }
        }
    }

    /**
     * 빌드 때 local.properties 에서 받은 Cloudflare 기본값을, 기기에 쓸 수 있는 키가 없을 때만 저장소에 넣는다.
     * 이미 저장된 키(디버그 설정 화면 등)는 덮어쓰지 않는다. 값은 로그에 남기지 않는다.
     */
    private suspend fun seedCloudflareDefaults(context: Context) {
        try {
            val accountId = context.getString(R.string.cloudflare_account_id_default).trim()
            val token = context.getString(R.string.cloudflare_api_token_default).trim()
            if (accountId.isBlank() || token.isBlank()) return
            val store = summarySettingsStore
            val hasKey = store.settings.value.cloudflareAccountId.isNotBlank() &&
                try { store.cloudflareTokenLast4() != null } catch (_: SummaryException) { false }
            if (hasKey) return
            store.setCloudflareAccountId(accountId)
            store.setCloudflareToken(token)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.e("AppContainer", "Cloudflare default seeding failed: ${failure.javaClass.simpleName}")
        }
    }

    /** 내비 연결 + 도착 자동 종료 연결: 내비 도착 신호, 내비 목적지, 주차 신호를 ArrivalManager 로 보낸다. */
    fun startNavi() {
        tripRepository.onTripEnding = {
            locationTracker.stop(it)
            vehicleSignalTracker.stop(it)
            arrivalManager.discardTrip(it)
            questionCoordinator.cancelForTrip(it)
            // Drains queued clips and closes their run through TalkSinkImpl (including summary work).
            talkRecording.finishTrip(it)
        }
        appScope.launch {
            try {
                tripRepository.recoverTalkClips()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.e("TripRepository", "Clip recovery failed: ${failure.javaClass.simpleName}")
            }
            try {
                tripRepository.reconcileOngoingTrips()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.e("TripRepository", "Trip reconcile failed: ${failure.javaClass.simpleName}")
            }
            try {
                withContext(Dispatchers.IO) { demoSeeder.seedOnce() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // No manifest text, story titles, or audio content in startup logs.
                Log.e("DemoSeeder", "Demo seeding failed: ${failure.javaClass.simpleName}")
            }
            talkRecording.observeStops(appScope)
            talkRecording.onQuestionCommand = { command ->
                when (command) {
                    QuestionCommand.ASK_NEXT -> questionCoordinator.requestQuestion("voice")
                    QuestionCommand.STOP_ASKING -> questionCoordinator.stopAsking("voice")
                }
            }
            naviMonitor.onArrived = { arrivalManager.onNaviArrived(it) }
            var lastDestination: NaviDestination? = null
            naviMonitor.onDestination = {
                // 같은 목적지는 15초마다 다시 오므로, 다른 목적지로 바뀔 때만 "아직 여행 중"을 푼다. null 은 무시한다.
                if (it != null) {
                    if (lastDestination != null && it != lastDestination) arrivalManager.onDestinationChanged()
                    lastDestination = it
                }
                arrivalManager.destination = it
            }
            vehicleSignalTracker.onParked = { arrivalManager.onParked(it) }
            naviMonitor.start()
            questionCoordinator.start()
        }
    }
}

class DongbanApplication : Application(), SummaryWorkerOwner {
    lateinit var container: AppContainer
        private set

    override val summaryWorkerDependencies: SummaryWorkerDependencies get() = container

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.mapboxTokenStore // 첫 MapView 보다 먼저 Mapbox 토큰을 적용한다
        container.startNavi()
        container.startSummaries(this)
    }
}
