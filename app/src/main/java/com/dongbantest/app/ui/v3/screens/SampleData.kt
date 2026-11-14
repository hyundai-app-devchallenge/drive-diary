package com.dongbantest.app.ui.v3.screens

import androidx.compose.ui.geometry.Offset
import com.dongbantest.app.gleo.VoiceCommands
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
    /** 여행 시간(출발 0 → 도착 1) 안에서 이 이야기가 차지한 구간. 없으면 지도·타임라인 띠에 놓지 않는다. */
    val span: ClosedFloatingPointRange<Float>? = null,
)

/** 여행 중 들은 노래 한 곡(M00 설계 4.2·4.5). */
data class SongUi(
    val id: String,
    val start: String,
    val end: String,
    val title: String,
    val artist: String,
    /** 튼 앱 이름 (멜론, 블루투스 …) */
    val source: String,
    /** 실제로 재생된 시간(일시정지 뺌) */
    val playedMs: Long,
    /** 여행 시간(0–1) 안의 구간 */
    val span: ClosedFloatingPointRange<Float>,
)

/** 노래 기록 권한 상태. Off 면 노래 탭이 설정 안내를 보인다. */
enum class SongAccess { On, Off }

/**
 * 기록에 붙인 사진 한 장.
 * @param path 앱 저장소의 사진 파일. null 이면 사진 자리(예시 화면).
 * @param aspect 가로/세로 비율. 사진 파일이 있으면 파일에서 다시 읽는다(예시 화면은 이 값으로 그린다).
 */
data class PhotoUi(val id: String, val time: String, val path: String? = null, val caption: String = "", val aspect: Float = 1f)

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
    val photos: List<PhotoUi>,
    val talks: List<TalkUi>,
    val songs: List<SongUi> = emptyList(),
    val songAccess: SongAccess = SongAccess.On,
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
    const val PAUSE_CAPTION = "“${VoiceCommands.PAUSE_RECORDING}”"
    /** 녹음 중이 아니면 우리 앱이 듣지 않으므로 글레오를 부른 뒤 말한다. */
    const val SAY_LABEL_GLEO = "글레오를 부른 뒤 이렇게 말해 보세요"

    val SAY_ON = listOf(VoiceCommands.ASK_NEXT, VoiceCommands.STOP_ASKING)
    val SAY_PAUSED = listOf(VoiceCommands.ASK_NEXT)
    val SAY_OFF = listOf(VoiceCommands.ASK_NEXT)

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

    val TOAST_NEW = VoiceToastUi(ToastKind.Success, "‘${VoiceCommands.ASK_NEXT}’, 알겠어요.", "새로운 대화 주제를 가져왔어요.")
    val TOAST_NOT_HEARD = VoiceToastUi(ToastKind.NotHeard, "잘 못 들었어요.", "다시 한 번 말해 주세요. 예: ‘${VoiceCommands.ASK_NEXT}’")
    val TOAST_MISHEARD = VoiceToastUi(ToastKind.Confirm, "‘${VoiceCommands.PAUSE_RECORDING}’로 들었어요.", "잘못 들었다면 이렇게 말해 주세요.", say = "아니야")

    val GANGNEUNG = TripRecordUi(
        id = "gangneung", title = TRIP_TITLE, dateLabel = "2026년 10월 10일 토요일", dateShort = "2026.10.10",
        tone = TripTone.Gangneung, people = listOf("민지", "서준", "하린", "나"), relation = RELATION,
        from = FROM, startTime = START, to = TO, arriveTime = ARRIVE, duration = TOTAL,
        photos = listOf(
            PhotoUi("p1", "09:41", caption = "출발 전 넷이", aspect = 4f / 3f),
            PhotoUi("p2", "09:55", caption = "호두과자 논쟁", aspect = 3f / 4f),
            PhotoUi("p3", "10:31", caption = "대관령 터널 지나서", aspect = 16f / 9f),
            PhotoUi("p4", "11:20", caption = "경포 바다", aspect = 1f),
            PhotoUi("p5", "11:24", caption = "모래사장 발자국", aspect = 3f / 4f),
            PhotoUi("p6", "11:32", caption = "경포에서 넷이", aspect = 4f / 3f),
            PhotoUi("p7", "11:40", caption = "커피 거리", aspect = 2f / 3f),
        ),
        talks = listOf(
            TalkUi("09:52", "휴게소 호두과자 논쟁", "0:48", false, tags = listOf("음식", "웃음"), id = "t1", span = gn(9, 52)..gn(9, 52, 48)),
            TalkUi("10:05", "운전 교대 가위바위보", "1:05", false, tags = listOf("게임"), id = "t2", span = gn(10, 5)..gn(10, 6, 5)),
            TalkUi("10:15", "처음 만났던 날", "3:20", true, tags = listOf("추억", "연애", "첫만남"), id = "t3", span = gn(10, 15)..gn(10, 18, 20)),        ),
        songs = listOf(
            song("s1", 9, 24, 9, 28, "Hype Boy", "NewJeans"),
            song("s2", 9, 28, 9, 32, "Love wins all", "아이유"),
            song("s3", 9, 49, 9, 54, "밤편지", "아이유"),
            song("s4", 10, 2, 10, 6, "사건의 지평선", "윤하"),
            song("s5", 10, 12, 10, 18, "봄날", "방탄소년단"),
            song("s6", 10, 40, 10, 44, "Ditto", "NewJeans", "블루투스"),
            song("s7", 11, 8, 11, 12, "Supernova", "aespa", "블루투스"),
            song("s8", 11, 38, 11, 44, "바다의 왕자", "박명수"),
            song("s9", 12, 5, 12, 9, "여름밤의 꿈", "김현철"),
        ),
    )
    val JEJU = TripRecordUi(
        id = "jeju", title = "제주 가족 여행", dateLabel = "2026년 10월 3일 토요일", dateShort = "2026.10.03",
        tone = TripTone.Jeju, people = listOf("엄마", "아빠", "지우", "나"), relation = "가족",
        from = "제주공항", startTime = "10:10", to = "성산일출봉", arriveTime = "11:25", duration = "1시간 15분",
        photos = listOf(PhotoUi("p1", "11:20", caption = "성산에서 넷이", aspect = 4f / 3f), PhotoUi("p2", "11:30", caption = "귤밭 앞에서", aspect = 3f / 4f)),
        talks = listOf(TalkUi("10:24", "창밖 말 세기", "0:52", false), TalkUi("10:58", "귤 따기 노래", "1:30", true)),
    )
    val YANGPYEONG = TripRecordUi(
        id = "yangpyeong", title = "양평 드라이브", dateLabel = "2026년 9월 21일 월요일", dateShort = "2026.09.21",
        tone = TripTone.Yangpyeong, people = listOf("유진", "나"), relation = "연인",
        from = "서울 마포", startTime = "14:00", to = "양평 두물머리", arriveTime = "15:05", duration = "1시간 5분",
        photos = listOf(PhotoUi("p1", "14:50", caption = "두물머리 산책", aspect = 16f / 9f), PhotoUi("p2", "15:00", caption = "연잎 핫도그", aspect = 1f)),
        talks = listOf(TalkUi("14:20", "첫 데이트 장소", "2:10", true), TalkUi("14:45", "플레이리스트 고르기", "0:40", false)),
    )
    /** 최신순 */
    val RECORDS = listOf(GANGNEUNG, JEJU, YANGPYEONG)

    /** 강릉 예시(09:20 → 12:30, 190분) 안에서 시각의 비율 */
    private fun gn(h: Int, m: Int, s: Int = 0): Float = ((h * 60 + m - (9 * 60 + 20)) + s / 60f) / 190f

    private fun song(id: String, h1: Int, m1: Int, h2: Int, m2: Int, title: String, artist: String, source: String = "멜론") =
        SongUi(
            id, "%02d:%02d".format(h1, m1), "%02d:%02d".format(h2, m2), title, artist, source,
            ((h2 * 60 + m2) - (h1 * 60 + m1)) * 60_000L, gn(h1, m1)..gn(h2, m2),
        )

    /** 강릉 기록인데 노래 기록 권한이 꺼진 경우(노래 탭 빈 화면 확인용) */
    val GANGNEUNG_NO_SONGS get() = GANGNEUNG.copy(songs = emptyList(), songAccess = SongAccess.Off)

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
