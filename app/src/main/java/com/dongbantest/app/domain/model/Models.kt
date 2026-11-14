package com.dongbantest.app.domain.model

/**
 * 동반차 앱의 도메인 모델.
 * UI / ViewModel 은 이 모델만 사용하며 Room Entity 나 Pleos SDK 클래스에 직접 의존하지 않는다.
 */

data class Trip(
    val id: Long,
    val title: String,
    val destination: String,
    val companions: List<String>,
    val startedAt: Long,
    val endedAt: Long?,
    /** 여행 시작 시점의 누적 주행거리 (SDK 값이 있을 때만). */
    val startOdometer: Float?,
    /** 여행 종료 시점의 누적 주행거리 (SDK 값이 있을 때만). */
    val endOdometer: Float?,
    /** 주행거리 값의 출처 (실제 차량 / Mock). */
    val odometerSource: DataSourceType?,
    /** 예시(테스트) 데이터 여부 — 실제 사용자 기록과 구분한다. */
    val isSample: Boolean,
    val coverPhotoPath: String? = null,
    /** 출발한 곳 이름 (PRD S14 소요 시간). 모르면 null */
    val startPlace: String? = null,
    /** 도착한 곳 이름. 모르면 null */
    val endPlace: String? = null,
) {
    val isOngoing: Boolean get() = endedAt == null

    /** 주행거리 차이. 두 값이 모두 있을 때만 계산한다. */
    val drivenDistance: Float?
        get() = if (startOdometer != null && endOdometer != null && endOdometer >= startOdometer) {
            endOdometer - startOdometer
        } else null
}

enum class MemoryType { TEXT, VOICE, PHOTO, ARRIVAL, QUESTION }

/**
 * 여행 중 남긴 하나의 기록.
 * - TEXT: [text] 에 메모
 * - VOICE: [filePath] 에 녹음 원본, [transcript] 에 STT 결과
 * - PHOTO: [filePath] 에 앱 전용 저장소로 복사한 사진
 * - ARRIVAL: 도착 기록 (수동 버튼)
 * - QUESTION: 함께 나눈 질문
 */
data class Memory(
    val id: Long,
    val tripId: Long,
    val type: MemoryType,
    val createdAt: Long,
    val text: String? = null,
    val filePath: String? = null,
    val transcript: String? = null,
    val durationMs: Long? = null,
    val talk: TalkMeta? = null,
)

enum class TalkSource { AUTO, MANUAL }
enum class SummaryStatus { PENDING, DONE, FAILED, SKIPPED }

data class TalkMeta(
    val memoryId: Long,
    val source: TalkSource,
    val blockStartMs: Long,
    val blockEndMs: Long,
    val keepScore: Double?,
    val kind: String?,
    val title: String? = null,
    val tags: List<String>? = null,
    val summaryStatus: SummaryStatus,
    val summaryModel: String? = null,
    val runId: String? = null,
    val closeReason: String? = null,
    val runClosed: Boolean? = null,
    val storyId: String? = null,
    val storyIndex: Int? = null,
    val storyKind: String? = null,
    val storyKeep: Boolean? = null,
    val storyReason: String? = null,
)

data class TripWithMemories(
    val trip: Trip,
    val memories: List<Memory>,
    /** GPS 기록으로 계산한 주행 요약. 위치 기록이 없으면 빈 값. */
    val drive: DriveSummary = DriveSummary.EMPTY,
    val tracks: List<TrackPlay> = emptyList(),
)

enum class DataSourceType { PLEOS_VEHICLE, MOCK }

/** 여행 중 GPS 로 받은 위치 한 점. */
data class LocationPoint(
    val tripId: Long,
    val recordedAt: Long,
    val latitude: Double,
    val longitude: Double,
    /** GPS 오차(미터). 값이 없으면 null. */
    val accuracyMeters: Float? = null,
)

/** 여행 중 차량에서 받은 정차 관련 신호. 정차 판단(타임라인)에 쓴다. */
enum class VehicleSignalType {
    /** 기어 P */
    PARKED,
    /** 기어 P 가 아닌 상태 (D·R·N) */
    GEAR_NOT_PARKED,
    DRIVER_SEAT_EMPTY,
    DRIVER_SEAT_OCCUPIED,
    DRIVER_BELT_OFF,
    DRIVER_BELT_ON,
    DOOR_OPEN,
    DOOR_CLOSED,
}

data class VehicleSignal(
    val tripId: Long,
    val recordedAt: Long,
    val type: VehicleSignalType,
)

enum class TimelineType(val label: String) {
    START("출발"),
    STOP("정차"),
    RESUME("다시 출발"),
    END("도착"),
}

/** 위치 기록에서 자동으로 뽑은 이벤트 (출발·정차·다시 출발·도착). */
data class TimelineEvent(
    val time: Long,
    val type: TimelineType,
    val latitude: Double,
    val longitude: Double,
)

/** GPS 기록 기반 주행 요약. 차량 주행거리계(odometer)와 별개로 계산한다. */
data class DriveSummary(
    val pointCount: Int,
    val distanceMeters: Double,
    val timeline: List<TimelineEvent>,
    /** 지도에 그릴 경로. 위치가 튄 곳에서 끊긴 구간들. */
    val path: List<List<LocationPoint>> = emptyList(),
) {
    companion object {
        val EMPTY = DriveSummary(pointCount = 0, distanceMeters = 0.0, timeline = emptyList())
    }
}
