package com.dongbantest.app.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Embedded
import androidx.room.Relation

@Entity(tableName = "trips")
data class TripEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val destination: String,
    /** 쉼표(,)로 구분된 동승자 이름 */
    val companions: String,
    val startedAt: Long,
    val endedAt: Long?,
    val startOdometer: Float?,
    val endOdometer: Float?,
    /** DataSourceType.name 또는 null */
    val odometerSource: String?,
    val isSample: Boolean,
    /** 출발한 곳 이름 (내비 주소). 모르면 null */
    val startPlace: String? = null,
    /** 도착한 곳 이름 (내비 목적지 또는 주소). 모르면 null */
    val endPlace: String? = null,
)

@Entity(
    tableName = "memories",
    foreignKeys = [
        ForeignKey(
            entity = TripEntity::class,
            parentColumns = ["id"],
            childColumns = ["tripId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("tripId")],
)
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    /** MemoryType.name */
    val type: String,
    val createdAt: Long,
    val text: String?,
    /** 녹음/사진 파일 경로 (앱 전용 저장소 내부) */
    val filePath: String?,
    /** 음성 → 텍스트 변환 결과. 녹음 원본과 별도로 관리한다. */
    val transcript: String?,
    val durationMs: Long?,
)

@Entity(
    tableName = "talk_meta",
    foreignKeys = [ForeignKey(entity = MemoryEntity::class, parentColumns = ["id"],
        childColumns = ["memoryId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("summaryStatus")],
)
data class TalkMetaEntity(
    @PrimaryKey val memoryId: Long,
    val source: String,
    val blockStartMs: Long,
    val blockEndMs: Long,
    val keepScore: Double?,
    val kind: String?,
    val title: String? = null,
    val tags: String? = null,
    val summaryStatus: String,
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

data class MemoryWithTalk(
    @Embedded val memory: MemoryEntity,
    @Relation(parentColumn = "id", entityColumn = "memoryId") val talk: TalkMetaEntity?,
)

/** 여행 중 GPS 로 받은 위치. 여행을 지우면 함께 지워진다. */
@Entity(
    tableName = "location_points",
    foreignKeys = [
        ForeignKey(
            entity = TripEntity::class,
            parentColumns = ["id"],
            childColumns = ["tripId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("tripId")],
)
data class LocationPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val recordedAt: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float?,
)

/** 여행 중 차량 신호(기어·운전석·안전벨트·문) 기록. 여행을 지우면 함께 지워진다. */
@Entity(
    tableName = "vehicle_signals",
    foreignKeys = [
        ForeignKey(
            entity = TripEntity::class,
            parentColumns = ["id"],
            childColumns = ["tripId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("tripId")],
)
data class VehicleSignalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val recordedAt: Long,
    /** VehicleSignalType 이름 */
    val type: String,
)
