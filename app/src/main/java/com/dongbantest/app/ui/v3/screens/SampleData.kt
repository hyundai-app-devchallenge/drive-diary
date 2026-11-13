package com.dongbantest.app.ui.v3.screens

import androidx.compose.ui.geometry.Offset
import com.dongbantest.app.ui.v3.components.AskedItem
import com.dongbantest.app.ui.v3.components.MapLabel
import com.dongbantest.app.ui.v3.components.MapLive
import com.dongbantest.app.ui.v3.components.MapRoute
import com.dongbantest.app.ui.v3.components.ToastKind
import com.dongbantest.app.ui.v3.components.TripTone
import com.dongbantest.app.ui.v3.components.VoiceToastUi

/** 다이어리 한 줄: 시각, 제목, 음성 길이, 동반이 질문에서 나왔는지 */
data class TalkUi(
    val time: String,
    val title: String,
    val audio: String,
    val fromQuestion: Boolean,
    // talk-rec 연동: 대화 태그(없으면 칩을 그리지 않음)
    val tags: List<String> = emptyList(),
    // 기기 태그만 있는 행은 분류 칩으로 강조하지 않는다.
    val category: String? = null,
    val id: String = "",
    val clipCount: Int = 1,
    val kind: String? = null,
    val hidden: Boolean = false,
)

/** 기록(여행) 한 건. 웹 data-v3.ts 의 TripRecord 와 같은 필드. */
data class TripRecordUi(
    val id: String,
    val title: String,
    val dateLabel: String,
    val dateShort: String,
    val tone: TripTone,
    val people: List<String>,
    val relation: String,
    val from: String,
    val startTime: String,
    val to: String,
    val arriveTime: String,
    val duration: String,
    val photos: List<String>,
    val talks: List<TalkUi>,
)

/**
 * 웹 시연 데이터(prototypes/mobile-web/src/flow/data.ts, data-v3.ts)를 그대로 옮긴 것.
 * 각 UiState 의 기본값이 여기서 온다. 실제 앱에서는 ViewModel 상태로 바꾼다.
 */
object Sample {
    const val TRIP_TITLE = "강릉 바다 여행"
    const val FROM = "서울 마포"
    const val TO = "강릉 경포"
    const val START = "09:20"
    const val ARRIVE = "12:30"
    const val TOTAL = "3시간 10분"
    const val REMAINING = "1시간 40분"
    const val RELATION = "친구"
    const val PEOPLE = "친구 4명"
    const val PROGRESS = 47
    const val REC_ON = "녹음 중 1시간 30분"
    const val REC_PAUSED = "녹음 멈춤 1시간 30분"
    const val REC_OFF = "녹음하지 않는 여행"
    const val REC_READY = "녹음 준비됨"
    const val REC_SAVED = "녹음 저장됨 3시간 10분"

    const val Q_FIRST = "우리 넷이 처음 만났던 날 기억나?"
    const val Q_NEXT = "오늘 여행에서 제일 기대되는 건 뭐야?"
    const val Q_SILENCE = "강릉 가면 제일 먼저 뭐 하고 싶어?"
    const val Q_SILENCE_TITLE = "강릉에서 제일 먼저 할 일"
    const val HINT = "답하지 않아도 괜찮아요"
    const val SAY_LABEL = "이렇게 말해 보세요"
    const val PAUSE_CAPTION = "녹음 멈춤은 ‘녹음 잠시 멈춰’"

    val SAY_ON = listOf("다른 질문 해 줘", "질문 그만")
    val SAY_PAUSED = listOf("다시 녹음해 줘", "다른 질문 해 줘")
    val SAY_OFF = listOf("녹음 시작해 줘", "다른 질문 해 줘")

    const val BUBBLE_DRIVING = "강릉 가는 길, 모두의 이야기를 담고 있어요."
    const val BUBBLE_PAUSED = "녹음을 잠시 멈췄어요. 질문은 계속할 수 있어요."
    const val BUBBLE_OFF = "이번 여행은 녹음 없이 질문만 함께해요."
    const val BUBBLE_READY = "출발하면 모두의 이야기를 담기 시작할게요."

    val ASKED_BASE = listOf(
        AskedItem("10:15", "처음 만났던 날", now = true),
        AskedItem("09:48", "휴게소에서 뭐 먹을까"),
    )
    val ASKED_AFTER_NEW = listOf(
        AskedItem("10:32", "오늘 제일 기대되는 것", now = true),
        AskedItem("10:15", "처음 만났던 날"),
        AskedItem("09:48", "휴게소에서 뭐 먹을까"),
    )
    val ASKED_QUIET = listOf(AskedItem("09:48", "휴게소에서 뭐 먹을까"))
    val ASKED_SILENCE = listOf(
        AskedItem("10:15", Q_SILENCE_TITLE, now = true),
        AskedItem("09:48", "휴게소에서 뭐 먹을까"),
    )

    val TOAST_NEW = VoiceToastUi(ToastKind.Success, "‘다른 질문 해 줘’, 알겠어요.", "새로운 대화 주제를 가져왔어요.")
    val TOAST_NOT_HEARD = VoiceToastUi(ToastKind.NotHeard, "잘 못 들었어요.", "다시 한 번 말해 주세요. 예: ‘다른 질문 해 줘’")
    val TOAST_MISHEARD = VoiceToastUi(ToastKind.Confirm, "‘녹음 잠시 멈춰’로 들었어요.", "잘못 들었다면 이렇게 말해 주세요.", say = "아니야")

    val GANGNEUNG = TripRecordUi(
        id = "gangneung", title = TRIP_TITLE, dateLabel = "2026년 10월 10일 토요일", dateShort = "2026.10.10",
        tone = TripTone.Gangneung, people = listOf("민지", "서준", "하린", "나"), relation = RELATION,
        from = FROM, startTime = START, to = TO, arriveTime = ARRIVE, duration = TOTAL,
        photos = listOf("경포에서 넷이", "호두과자 논쟁"),
        talks = listOf(
            TalkUi("09:52", "휴게소 호두과자 논쟁", "0:48", false, tags = listOf("음식", "웃음")),
            TalkUi("10:05", "운전 교대 가위바위보", "1:05", false, tags = listOf("게임")),
            TalkUi("10:15", "처음 만났던 날", "3:20", true, tags = listOf("추억", "연애", "첫만남")),
        ),
    )
    val JEJU = TripRecordUi(
        id = "jeju", title = "제주 가족 여행", dateLabel = "2026년 10월 3일 토요일", dateShort = "2026.10.03",
        tone = TripTone.Jeju, people = listOf("엄마", "아빠", "지우", "나"), relation = "가족",
        from = "제주공항", startTime = "10:10", to = "성산일출봉", arriveTime = "11:25", duration = "1시간 15분",
        photos = listOf("성산에서 넷이", "귤밭 앞에서"),
        talks = listOf(TalkUi("10:24", "창밖 말 세기", "0:52", false), TalkUi("10:58", "귤 따기 노래", "1:30", true)),
    )
    val YANGPYEONG = TripRecordUi(
        id = "yangpyeong", title = "양평 드라이브", dateLabel = "2026년 9월 21일 월요일", dateShort = "2026.09.21",
        tone = TripTone.Yangpyeong, people = listOf("유진", "나"), relation = "연인",
        from = "서울 마포", startTime = "14:00", to = "양평 두물머리", arriveTime = "15:05", duration = "1시간 5분",
        photos = listOf("두물머리 산책", "연잎 핫도그"),
        talks = listOf(TalkUi("14:20", "첫 데이트 장소", "2:10", true), TalkUi("14:45", "플레이리스트 고르기", "0:40", false)),
    )
    /** 최신순 */
    val RECORDS = listOf(GANGNEUNG, JEJU, YANGPYEONG)

    fun record(id: String) = RECORDS.first { it.id == id }
    /** 다음 장: 강릉 → 제주 → 양평 → 강릉. live 면 진행 중인 강릉을 빼고 제주 ↔ 양평 */
    fun nextRecord(id: String, live: Boolean = false): String {
        val ids = (if (live) RECORDS.filter { it.id != GANGNEUNG.id } else RECORDS).map { it.id }
        return ids[(ids.indexOf(id) + 1) % ids.size]
    }
}

/** 예시 지도 좌표(웹 RecordMap 과 같은 비율 좌표). */
object SampleMap {
    private val MAPO = Offset(0.2f, 0.3f)
    private val YANGPYEONG = Offset(0.42f, 0.34f)
    private val GANGNEUNG = Offset(0.8f, 0.24f)
    private val JEJU_AIR = Offset(0.2f, 0.86f)
    private val SEONGSAN = Offset(0.4f, 0.84f)

    fun route(r: TripRecordUi): MapRoute = when (r.id) {
        "gangneung" -> MapRoute(r.id, r.title, r.tone, MAPO, GANGNEUNG, 0.14f)
        "yangpyeong" -> MapRoute(r.id, r.title, r.tone, MAPO, YANGPYEONG, -0.35f)
        else -> MapRoute(r.id, r.title, r.tone, JEJU_AIR, SEONGSAN, 0.3f)
    }

    /** 전체 지도 라벨. live 면 강릉 라벨은 차 표식 위(MapLive.label)로 옮기므로 뺀다. */
    fun labels(live: Boolean): List<MapLabel> = listOfNotNull(
        MapLabel("서울 마포", MAPO, 0f, -22f),
        MapLabel("양평", YANGPYEONG, 6f, 24f, "yangpyeong"),
        if (live) null else MapLabel("강릉", GANGNEUNG, -6f, 26f, "gangneung"),
        MapLabel("제주", JEJU_AIR, -38f, 0f, "jeju"),
    )

    /** 한 기록만 그릴 때(다이어리): 그 기록의 출발지·도착지 라벨. 아래쪽 점은 라벨을 위로. */
    fun labelsFor(r: TripRecordUi): List<MapLabel> {
        val rt = route(r)
        fun near(at: Offset, below: Boolean) = if (at.y > 0.6f) -24f else if (below) 26f else -20f
        return listOf(MapLabel(r.from, rt.from, 0f, near(rt.from, false)), MapLabel(r.to, rt.to, 0f, near(rt.to, true)))
    }

    val LIVE = MapLive("gangneung", Sample.PROGRESS / 100f, "강릉 (진행 중)")
}
