package com.dongbantest.app

import android.app.Application
import android.content.Context
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
import com.drivediary.talkrec.summary.SummarySettingsStore
import com.drivediary.talkrec.summary.SummaryWorkScheduler
import com.drivediary.talkrec.summary.SummaryWorkerDependencies
import com.drivediary.talkrec.summary.SummaryWorkerOwner
import com.dongbantest.app.location.LocationReader
import com.dongbantest.app.location.LocationTracker
import com.dongbantest.app.navi.ArrivalManager
import com.dongbantest.app.navi.ArrivalNotifier
import com.dongbantest.app.navi.NaviMonitor
import com.dongbantest.app.navi.TripPlaceRecorder
import com.dongbantest.app.vehicle.DrivingSafetyMonitor
import com.dongbantest.app.vehicle.VehicleSignalReader
import com.dongbantest.app.vehicle.VehicleSignalTracker
import com.dongbantest.app.vehicle.VehicleRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** 간단한 수동 DI 컨테이너. */
class AppContainer(context: Context) : SummaryWorkerDependencies {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val database by lazy { DongbanDatabase.create(context) }
    val mediaFileStore by lazy { MediaFileStore(context) }
    val tripRepository by lazy { TripRepository(database.tripDao(), mediaFileStore) }
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
        TalkRecordingController(context, tripRepository, recordingConsentStore, talkSink, recordingSettingsStore)
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
            tripRepository.listPendingTalkSummaries().maxOfOrNull { it.id }?.let {
                SummaryWorkScheduler.enqueue(context, it)
            }
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
            tripRepository.reconcileOngoingTrips()
            talkRecording.observeStops(appScope)
            talkRecording.onQuestionCommand = { command ->
                when (command) {
                    QuestionCommand.ASK_NEXT -> questionCoordinator.requestQuestion("voice")
                    QuestionCommand.STOP_ASKING -> questionCoordinator.stopAsking("voice")
                }
            }
            naviMonitor.onArrived = { arrivalManager.onNaviArrived(it) }
            naviMonitor.onDestination = { arrivalManager.destination = it }
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
