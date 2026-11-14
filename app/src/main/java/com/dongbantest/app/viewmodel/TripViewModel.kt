package com.dongbantest.app.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dongbantest.app.audio.VoicePlayer
import com.dongbantest.app.audio.VoiceRecorder
import com.dongbantest.app.data.repository.MediaFileStore
import com.dongbantest.app.data.repository.QuestionBank
import com.dongbantest.app.data.ask.QuestionCoordinator
import com.dongbantest.app.gleo.VoiceCommands
import com.dongbantest.app.data.repository.RecordingConsentStore
import com.dongbantest.app.data.repository.TripRepository
import com.dongbantest.app.data.talk.TalkRecordingController
import com.dongbantest.app.domain.model.TripWithMemories
import com.dongbantest.app.location.LocationTracker
import com.dongbantest.app.vehicle.DrivingSafetyMonitor
import com.dongbantest.app.vehicle.DrivingSafetyState
import com.dongbantest.app.vehicle.SafetySource
import com.dongbantest.app.vehicle.VehicleRepository
import com.dongbantest.app.vehicle.VehicleSignalTracker
import com.dongbantest.app.navi.ArrivalManager
import com.dongbantest.app.navi.TripPlaceRecorder
import com.dongbantest.app.navi.NaviMonitor
import com.dongbantest.app.domain.model.RouteRibbon
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TripUiState(
    val data: TripWithMemories? = null,
    val question: String = QuestionBank.questions.first(),
    val isRecording: Boolean = false,
    val recordingStartedAt: Long? = null,
    val playingPath: String? = null,
    val safety: DrivingSafetyState = DrivingSafetyState(SafetySource.MOCK, false),
    val message: String? = null,
    /** 진행 중인 여행의 길 리본 (출발지 → 목적지 진행). 끝난 여행이면 null. */
    val ribbon: RouteRibbon? = null,
    /** 여행별 녹음 동의와 현재 일시정지 상태. */
    val recordingMode: RecordingMode = RecordingMode.READY,
    /** "질문 그만": 이번 여행에서는 먼저 묻지 않는다 */
    val quiet: Boolean = false,
    /** 음성 명령 결과 알림 (몇 초 뒤 사라진다) */
    val voiceFeedback: VoiceFeedback? = null,
)

/** 녹음 모드: 준비됨(동의 전) / 녹음 중 / 잠시 멈춤 / 녹음하지 않는 여행 */
enum class RecordingMode { READY, ON, PAUSED, OFF }

/** 음성 명령 결과: 성공 / 못 들음 / 확인 필요(잘못 멈췄을 수 있음) */
data class VoiceFeedback(val kind: Kind, val heard: String, val result: String) {
    enum class Kind { SUCCESS, NOT_HEARD, CONFIRM }
}

private data class LocalState(
    val question: String = QuestionBank.questions.first(),
    val isRecording: Boolean = false,
    val recordingStartedAt: Long? = null,
    val message: String? = null,
    val quiet: Boolean = false,
    val voiceFeedback: VoiceFeedback? = null,
)

/**
 * 진행 중 여행 화면(TripScreen) / 추억 기록 화면(MemoryCaptureScreen) / 다이어리 상세 화면 공용 ViewModel.
 * 녹음·사진·메모 저장 로직을 한 곳에서 관리한다.
 */
class TripViewModel(
    private val tripId: Long,
    private val tripRepository: TripRepository,
    private val vehicleRepository: VehicleRepository,
    private val safetyMonitor: DrivingSafetyMonitor,
    private val files: MediaFileStore,
    private val recorder: VoiceRecorder,
    private val locationTracker: LocationTracker,
    private val vehicleSignalTracker: VehicleSignalTracker,
    private val arrivalManager: ArrivalManager,
    private val placeRecorder: TripPlaceRecorder,
    private val naviMonitor: NaviMonitor,
    private val consentStore: RecordingConsentStore,
    private val talkRecording: TalkRecordingController,
    private val questionCoordinator: QuestionCoordinator,
) : ViewModel() {

    private val player = VoicePlayer(viewModelScope) { showMessage("음성을 재생할 수 없어요.") }
    val playback = player.playback
    private val local = MutableStateFlow(LocalState())
    private val recordingMode = combine(consentStore.changes(), talkRecording.pausedTripId) { _, _ ->
        currentRecordingMode()
    }.distinctUntilChanged()

    private fun currentRecordingMode() = when (consentStore.get(tripId)) {
        true -> if (talkRecording.pausedTripId.value == tripId) RecordingMode.PAUSED else RecordingMode.ON
        false -> RecordingMode.OFF
        null -> RecordingMode.READY
    }

    /** 화면이 보이는 동안 15초마다 내비에 남은 거리·시간을 물어본다. */
    private val naviRoute = combine(
        naviMonitor.route,
        flow {
            while (true) {
                naviMonitor.refreshRoute()
                emit(Unit)
                delay(ROUTE_REFRESH_MILLIS)
            }
        },
    ) { route, _ -> route }

    val uiState: StateFlow<TripUiState> = combine(
        tripRepository.observeTripWithMemories(tripId),
        combine(local, recordingMode) { ui, mode -> ui to mode },
        playback.map { it?.takeIf { playback -> playback.isPlaying }?.path }.distinctUntilChanged(),
        safetyMonitor.state,
        naviRoute,
    ) { data, (l, mode), playing, safety, route ->
        TripUiState(
            data = data,
            question = l.question,
            isRecording = l.isRecording,
            recordingStartedAt = l.recordingStartedAt,
            playingPath = playing,
            safety = safety,
            message = l.message,
            ribbon = data?.trip?.takeIf { it.endedAt == null }?.let { trip ->
                RouteRibbon(
                    originName = trip.startPlace,
                    destinationName = route?.destinationName ?: trip.destination.ifBlank { null },
                    startedAt = trip.startedAt,
                    progress = RouteRibbon.progressOf(route?.totalMeters, route?.remainMeters),
                    remainMeters = route?.remainMeters,
                    remainSeconds = route?.remainSeconds,
                    companionCount = trip.companions.size,
                )
            },
            recordingMode = mode,
            quiet = l.quiet,
            voiceFeedback = l.voiceFeedback,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TripUiState())

    fun nextQuestion() = local.update { it.copy(question = QuestionBank.next(it.question)) }

    /** 지금 질문을 "함께 나눈 질문" 으로 다이어리에 남긴다. */
    fun saveQuestion() = viewModelScope.launch {
        tripRepository.addQuestion(tripId, local.value.question)
        showMessage("질문을 추억에 담았어요 💬")
    }

    fun addTextMemo(text: String) {
        if (text.isBlank()) return
        if (safetyMonitor.state.value.isRestricted) {
            showMessage("주행 중에는 글쓰기를 할 수 없어요. 정차 후 이용해 주세요.")
            return
        }
        viewModelScope.launch {
            tripRepository.addTextMemo(tripId, text)
            showMessage("메모를 저장했어요 ✏️")
        }
    }

    /** 사용자가 녹음 시작 버튼 + 동승자 동의를 확인한 뒤에만 호출된다. */
    fun startRecording() {
        if (consentStore.get(tripId) == true) {
            if (recorder.isRecording) saveCurrentClip()
            player.stop()
            viewModelScope.launch { talkRecording.markMoment(tripId) }
            return
        }
        if (recorder.isRecording) return
        player.stop()
        try {
            recorder.start(files.newVoiceFile())
            local.update { it.copy(isRecording = true, recordingStartedAt = System.currentTimeMillis()) }
        } catch (e: Exception) {
            showMessage("녹음을 시작할 수 없어요 (${e.javaClass.simpleName}). 마이크 상태를 확인해 주세요.")
        }
    }

    fun stopRecording() {
        if (consentStore.get(tripId) == true) {
            if (recorder.isRecording) saveCurrentClip()
            talkRecording.pause(tripId)
            return
        }
        val result = recorder.stop()
        local.update { it.copy(isRecording = false, recordingStartedAt = null) }
        if (result == null) {
            showMessage("녹음이 너무 짧거나 저장에 실패했어요.")
            return
        }
        viewModelScope.launch {
            tripRepository.addVoiceMemo(tripId, result.first.absolutePath, result.second)
            showMessage("목소리 추억을 저장했어요 🎙️")
        }
    }

    fun togglePlay(path: String) {
        if (recorder.isRecording) return
        val current = playback.value
        if (current?.path == path && current.isPlaying) player.stop()
        else player.toggle(listOf(path))
    }

    fun toggleStory(paths: List<String>) {
        if (recorder.isRecording || safetyMonitor.state.value.isRestricted) return
        player.toggle(paths)
    }

    fun seekStory(paths: List<String>, positionMs: Long) {
        if (recorder.isRecording || safetyMonitor.state.value.isRestricted) return
        player.seek(paths, positionMs)
    }

    fun addPhoto(uri: Uri) = viewModelScope.launch {
        try {
            val file = files.copyPhotoToAppStorage(uri)
            tripRepository.addPhoto(tripId, file.absolutePath)
            showMessage("사진을 붙였어요 📸")
        } catch (e: Exception) {
            showMessage("사진을 저장하지 못했어요.")
        }
    }

    fun recordArrival() = viewModelScope.launch {
        val destination = uiState.value.data?.trip?.destination.orEmpty()
        tripRepository.addArrival(tripId, "$destination 도착!")
        showMessage("도착을 기록했어요 📍 안전하게 정차한 뒤 추억을 남겨 보세요.")
    }

    fun deleteMemory(memoryId: Long) = viewModelScope.launch {
        player.stop()
        tripRepository.deleteMemory(memoryId)
    }

    /** 진행 중인 여행이면 정차 판단용 차량 신호(기어·운전석·안전벨트·문) 기록을 시작한다. */
    fun startVehicleSignalTracking() = viewModelScope.launch {
        if (!tripRepository.isTripOngoing(tripId)) return@launch
        vehicleSignalTracker.start(tripId)
        placeRecorder.recordStart(tripId)
    }

    /** 진행 중인 여행이면 GPS 위치 수집을 시작한다. 위치 권한이 있을 때만 동작한다. */
    fun startLocationTracking() = viewModelScope.launch {
        if (tripRepository.isTripOngoing(tripId)) locationTracker.start(tripId)
    }

    fun endTrip(onEnded: () -> Unit) = viewModelScope.launch {
        if (recorder.isRecording) saveCurrentClip()
        locationTracker.stop(tripId)
        vehicleSignalTracker.stop(tripId)
        tripRepository.endTrip(tripId, vehicleRepository.state.value.odometer)
        placeRecorder.recordEnd(tripId)
        onEnded()
    }

    /** S11 "아직 여행 중이에요": 자동 종료된 여행을 다시 연다. */
    fun keepTraveling(onReopened: () -> Unit) = viewModelScope.launch {
        arrivalManager.keepTraveling(tripId).join()
        onReopened()
    }

    fun deleteTrip(onDeleted: () -> Unit) = viewModelScope.launch {
        player.stop()
        locationTracker.stop(tripId)
        vehicleSignalTracker.stop(tripId)
        tripRepository.deleteTrip(tripId)
        onDeleted()
    }

    fun showMessage(text: String) = local.update { it.copy(message = text) }
    fun consumeMessage() = local.update { it.copy(message = null) }

    /**
     * 수동 VoiceRecorder 조각은 화면을 떠날 때 저장한다.
     * 동의한 여행의 TalkRec FGS는 탭 이동/화면 소멸과 관계없이 여행 종료까지 유지한다.
     */
    fun onScreenHidden() {
        if (recorder.isRecording) saveCurrentClip()
        player.stop()
    }

    /** 여행 화면이 보이면, 녹음에 동의한 여행은 녹음을 이어 간다. */
    fun onScreenShown(hasMicPermission: Boolean) = viewModelScope.launch {
        if (!tripRepository.isTripOngoing(tripId)) return@launch
        if (consentStore.get(tripId) != true) {
            talkRecording.stopForTrip(tripId)
            return@launch
        }
        if (currentRecordingMode() == RecordingMode.ON && hasMicPermission) {
            if (recorder.isRecording) saveCurrentClip()
            talkRecording.startForTrip(tripId)
        }
    }

    /** S03 녹음 동의 결과를 저장한다. */
    fun setRecordingConsent(consent: Boolean) {
        consentStore.set(tripId, consent)
        if (!consent) talkRecording.stopForTrip(tripId)
        else {
            if (recorder.isRecording) saveCurrentClip()
            viewModelScope.launch { talkRecording.startForTrip(tripId) }
        }
    }

    /** 지금 질문 카드의 질문을 "나눈 대화"로 남긴다 (메시지 없이). */
    fun askCurrentQuestion() = viewModelScope.launch {
        tripRepository.addQuestion(tripId, local.value.question)
    }

    /** 정차 중 질문 카드의 예시를 눌렀을 때 실행하는 질문 동작. */
    fun onVoiceExampleTapped(say: String) {
        if (safetyMonitor.state.value.isRestricted) return
        when (say) {
            VoiceCommands.ASK_NEXT -> {
                local.update { it.copy(quiet = false) }
                questionCoordinator.requestQuestion("screen")
                feedback(VoiceFeedback.Kind.SUCCESS, say, "새 질문을 꺼냈어요")
            }
            VoiceCommands.STOP_ASKING -> {
                local.update { it.copy(quiet = true) }
                questionCoordinator.stopAsking("screen")
                feedback(VoiceFeedback.Kind.SUCCESS, say, "이번 여행에서는 먼저 묻지 않을게요")
            }
            else -> feedback(VoiceFeedback.Kind.NOT_HEARD, say, "잘 못 들었어요. 다시 말해 주세요")
        }
    }

    fun toggleRecordingWhileStopped(resume: Boolean) {
        if (safetyMonitor.state.value.isRestricted || consentStore.get(tripId) != true) return
        if (recorder.isRecording) saveCurrentClip()
        if (resume) {
            viewModelScope.launch {
                if (talkRecording.resume(tripId)) showMessage("다시 녹음할게요")
                else showMessage("녹음을 다시 시작할 수 없어요. 마이크 상태를 확인해 주세요.")
            }
        } else {
            talkRecording.pause(tripId)
            showMessage("녹음을 잠시 멈췄어요")
        }
    }

    fun consumeVoiceFeedback() = local.update { it.copy(voiceFeedback = null) }

    private fun feedback(kind: VoiceFeedback.Kind, heard: String, result: String) =
        local.update { it.copy(voiceFeedback = VoiceFeedback(kind, heard, result)) }

    /** 녹음 중인 조각을 멈추고 저장한다 (메시지 없이). */
    private fun saveCurrentClip() {
        val result = recorder.stop()
        local.update { it.copy(isRecording = false, recordingStartedAt = null) }
        if (result != null) {
            viewModelScope.launch { tripRepository.addVoiceMemo(tripId, result.first.absolutePath, result.second) }
        }
    }

    override fun onCleared() {
        recorder.cancel()
        player.stop()
    }

    private companion object {
        const val ROUTE_REFRESH_MILLIS = 15_000L
    }
}
