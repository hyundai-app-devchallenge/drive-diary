# M00 들은 노래 기록 — 설계

여행 중 경로와 음성에 더해, **언제 어떤 노래를 들었는지**를 기록한다.
기록 탭 상세에서는 지도 위에 녹음한 곳(이야기)과 그때 흐르던 노래를 함께 보여 준다.

- 구현(수집·저장·디버그 플레이어·검증): Codex. 작업마다 `talk-rec/docs/music-save/M0n-*.md` 결과 문서를 남긴다.
- UI/UX(기록 상세 탭, 지도 핀, 노래 목록, 권한 안내): Claude.

---

## 1. 단계와 순서

| 단계 | 내용 | 담당 | 진행 조건 |
|---|---|---|---|
| **M01** | 되는지 확인: 알림 접근 권한, 활성 세션 읽기, 디버그 음원 플레이어 | Codex | 없음 (먼저 함) |
| **M02** | 노래 기록 저장: DB 표, 중복 없는 기록 규칙, 여행과 함께 시작·끝 | Codex | M01에서 Pleos 실기기 확인 통과 |
| **M03** | 기록 상세 탭(지도·경로·이야기·노래)과 지도 위 핀 | Claude | M02 데이터 모양 확정 (화면은 예시 데이터로 먼저 시작 가능) |
| **M04** | 설정의 "들은 노래 기록" 항목과 권한 안내 | Claude | M01 결과(설정 화면을 여는 방법) |

M01이 막히면 M02 이후는 멈추고 다른 길(Pleos SDK 미디어 API, Pleos와 권한 협의)을 찾는다.

---

## 2. M01 — 되는지 확인 (Codex)

### 2.1 확인할 것

Android 공식 조건: `MediaSessionManager.getActiveSessions(ComponentName)`은
`MEDIA_CONTENT_CONTROL`(시스템 앱 전용) 권한이 있거나, **사용 설정된 알림 리스너(NotificationListenerService)의 ComponentName을 넘길 때**만 된다.
일반 앱은 두 번째 길뿐이다.

Pleos 실기기(그리고 AAOS 에뮬레이터)에서 아래를 차례로 확인하고 결과 문서에 표로 남긴다.

| # | 질문 | 확인 방법 |
|---|---|---|
| A | Pleos 설정에 "알림 접근"(특별한 앱 접근) 메뉴가 있고, 동반차가 목록에 보이는가 | 설정 앱 직접 확인, 화면 사진 |
| B | 앱에서 `Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS`(API 30+는 `ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS` + 컴포넌트) 인텐트로 그 화면이 열리는가 | `resolveActivity` 결과와 실제 실행 |
| C | Android 13+ "제한된 설정"(스토어 밖 설치 앱은 알림 접근 스위치가 막힘)이 걸리는가. 걸리면 앱 정보 ⋮ → "제한된 설정 허용" 뒤 켜지는가 | 직접 확인 |
| D | 설정 메뉴가 없을 때 adb로 켜면 되는가: `adb shell cmd notification allow_listener com.dongbantest.app/com.dongbantest.app.music.MusicListenerService` | `getActiveSessions`가 `SecurityException` 없이 목록을 주는지 |
| E | 실제 소리원의 메타데이터가 오는가: 블루투스 음악(폰 연결), 라디오, 설치된 음악 앱 | `adb shell dumpsys media_session` + 앱 로그의 제목·가수 |
| F | 동반차가 백그라운드(다른 앱이 앞에 있음)일 때도 콜백이 오는가 | 로그 시각 |

결과 판정:
- A 또는 B가 되면 → 일반 사용자 경로 있음. M02 진행.
- A·B는 안 되고 D만 되면 → 개발용으로만 가능. 사용자에게 알리고 M02는 보류, Pleos 쪽 길을 찾는다.
- D도 안 되면 → 이 방식은 불가.

### 2.2 만드는 것

**본 앱(main)**
- `music/MusicListenerService : NotificationListenerService`
  - 알림 내용은 **읽지 않는다**. `onNotificationPosted`를 오버라이드하지 않는다(문서와 코드 주석에 명시).
  - `onListenerConnected`/`onListenerDisconnected`만 써서 "연결됨" 상태를 알린다.
  - 매니페스트: `android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"`, `exported="true"`, intent-filter `android.service.notification.NotificationListenerService`.
- `music/MusicAccess`: 권한 켜짐 여부(`NotificationManagerCompat.getEnabledListenerPackages`), 설정 화면 여는 인텐트(되는 것 순서대로 시도).
- `music/NowPlayingWatcher`: `MediaSessionManager.addOnActiveSessionsChangedListener`로 세션 목록 변화를 받고, 세션마다 `MediaController.Callback`(`onMetadataChanged`, `onPlaybackStateChanged`, `onSessionDestroyed`)을 건다. 받은 것을 `NowPlaying` 스냅숏 흐름(Flow)으로 내보낸다. M01에서는 저장하지 않고 로그와 디버그 화면에만 보인다.

**디버그 앱(debug 소스셋)**
- `DebugMusicService`: 포그라운드 서비스(`mediaPlayback`), **`android:process=":musicprobe"`로 다른 프로세스**에서 돈다. 동반차 본 프로세스와 갈라 놓아야 "다른 앱이 틀고, 동반차는 뒤에서 듣는" 상황에 가깝다.
  - 짧은 로컬 음원 두 개(곡 A, 곡 B): 저작권 걱정 없게 **코드로 만든 20초 안팎의 톤 WAV**를 debug assets에 둔다.
  - `MediaSession`(tag `dongban-debug-music`)에 제목·아티스트·앨범·길이와 재생 상태(PLAYING/PAUSED/STOPPED, 위치)를 올린다.
  - 곡 A: 제목 "디버그 곡 A", 아티스트 "동반차 테스트". 곡 B: "디버그 곡 B", 같은 아티스트.
- `MusicLabActivity`(디버그 카탈로그에 "노래 기록 실험실" 버튼):
  - 권한 상태와 "설정 열기" 버튼, adb 명령 안내 문구.
  - 플레이어 버튼: A 재생 / 일시정지 / B 재생 / 정지 / **같은 메타데이터 다시 보내기** / **PLAYING 상태 다시 보내기**(중복 콜백 흉내).
  - "순서 자동 실행": A 재생 15초 → 일시정지 5초 → B 재생 15초 → 정지.
  - 지금 보이는 활성 세션 목록(패키지, 태그, 제목, 상태)과 받은 콜백 로그(시각 포함).
- adb로도 같은 동작: `com.dongbantest.app.debug.MUSIC_PLAY_A` / `MUSIC_PAUSE` / `MUSIC_PLAY_B` / `MUSIC_STOP` / `MUSIC_RESEND` 브로드캐스트(기존 `Debug*Receiver` 방식).

**자기 앱 세션 거르기**: 본 앱 TTS·재생이 세션을 만들 수 있으니 출시 빌드는 자기 패키지 세션을 버린다. 디버그 빌드만 tag가 `dongban-debug-music`인 자기 세션을 받아들인다(`MediaController.getTag()`).

### 2.3 M01 결과 문서
`talk-rec/docs/music-save/M01-feasibility.md`: 위 A~F 표(기기·OS 버전, 되는지, 증거), 실제 소리원별 메타데이터 예시, M02로 가도 되는지 판단.

---

## 3. M02 — 노래 기록 저장 (Codex)

### 3.1 저장 모양

```kotlin
@Entity(tableName = "track_plays", foreignKeys = [FK(trips.id, CASCADE)], indices = [Index("tripId")])
data class TrackPlayEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val startedAt: Long,          // 처음 재생된 시각 (벽시계 ms, 위치 기록과 같은 기준)
    val endedAt: Long,            // 마지막으로 재생 중이던 시각. 열려 있는 동안 계속 갱신
    val playedMs: Long,           // 실제로 재생된 시간 합 (일시정지 뺌)
    val title: String,
    val artist: String?,
    val album: String?,
    val durationMs: Long?,        // 곡 길이 (메타데이터에 있으면)
    val sourcePackage: String,    // 튼 앱 패키지
    val sourceLabel: String?,     // 튼 앱 이름 ("멜론", "블루투스" …) 기록 시점에 읽어 둠
)
```

- DB 버전 6 → 7, `MIGRATION_6_7`(표 만들기만, 다른 표는 그대로), `app/schemas/.../7.json`, `TalkMigrationTest`처럼 마이그레이션 테스트.
- 위치(위도·경도)는 **저장하지 않는다.** 화면에서 `startedAt`으로 `location_points`의 가장 가까운 점을 찾아 놓는다(이야기 핀과 같은 방식, 4.3).
- 여행을 지우거나 버리면 함께 지워진다(CASCADE). 앱 밖으로 보내지 않는다.
- 도메인 모델 `TrackPlay`, `TripWithMemories.tracks: List<TrackPlay>`(기본값 빈 목록)로 화면에 넘긴다.

### 3.2 중복 없는 기록 규칙 (`TrackPlayLog`, 순수 Kotlin)

안드로이드에 기대지 않는 상태 기계로 만들고 단위 테스트한다. 입력은 `(시각, 패키지, 메타데이터, 재생 상태)` 스냅숏, 출력은 "새 행 넣기 / 이 행 고치기 / 이 행 지우기" 명령.

- **곡 열쇠** = 패키지 + 제목 + 아티스트 (앞뒤 공백 제거, 대소문자 무시). 앨범·길이·앨범아트는 열쇠에 넣지 않는다(앱이 나중에 채워 보내는 일이 잦다).
- 제목이 비어 있으면 무시한다(광고, 불러오는 중).
- 여러 세션이 있으면 **PLAYING인 것 중 목록 순서가 가장 앞인 하나**만 따라간다.

| 지금 열린 행 | 받은 것 | 할 일 |
|---|---|---|
| 없음 | PLAYING 곡 X | 새 행 X |
| X 재생 중 | 같은 X 메타데이터·상태 다시 옴 | 아무것도 안 함 (`endedAt`만 필요하면 갱신) |
| X 재생 중 | BUFFERING / CONNECTING (같은 X) | 재생 중으로 본다. 나누지 않음 |
| X 재생 중 | PAUSED / STOPPED / NONE / ERROR / 세션 사라짐 | X를 "멈춤"으로: `playedMs` 더하고 `endedAt` = 지금 |
| X 멈춤 | 10분 안에 같은 X PLAYING | **같은 행 X 이어 감** (새 행 아님) |
| X 멈춤 | 10분 지나 같은 X PLAYING | X 닫고 새 행 X |
| X 재생/멈춤 | 다른 곡 Y PLAYING | X 닫고 새 행 Y |
| 무엇이든 | 여행 끝 / 수집 멈춤 | 열린 행 닫기 |

- **짧게 넘긴 곡**: 닫을 때 `playedMs < 10초`면 그 행을 지운다(곡 넘기기로 생긴 잡음).
- **한 곡 반복**: 같은 X가 길이의 80% 넘게 재생된 뒤 위치가 3초 안쪽으로 돌아가면 새 행 X(두 번 들음). 위치 정보가 없으면 이 규칙은 쓰지 않는다.
- DB 쓰기는 상태가 바뀔 때만 한다(위치 틱마다 쓰지 않음). 열린 행은 30초마다 한 번 `endedAt`/`playedMs`를 저장해, 앱이 죽어도 거의 맞게 남는다.

### 3.3 언제 수집하나

- `MusicTracker`(앱 범위, `LocationTracker`와 같은 모양): `start(tripId)` / `stop(tripId)`.
  - 여행 시작 때 `TripViewModel`에서 위치·차량 신호와 같이 켜고, `TripRepository.onTripEnding`에서 끈다.
  - 화면이 바뀌거나 동반차가 뒤로 가도 끊기지 않는다. 알림 리스너가 켜져 있으면 시스템이 서비스를 묶어 두어 프로세스가 잘 살아 있다.
- 켜는 조건: 알림 접근 허용됨 **그리고** 설정 "들은 노래 기록"이 켜짐(`MusicSettingsStore`, 기본 켜짐). 녹음 멈춤(Gleo/음성 명령)은 노래 기록과 상관없다.
- 진행 중 여행이 없을 때는 아무것도 기록하지 않는다.

### 3.4 확인 순서 (M02 완료 조건)

여행을 시작한 뒤 홈으로 나가 동반차를 백그라운드로 두고, 노래 기록 실험실(또는 adb)로:

1. 곡 A 재생 15초 → 그사이 "메타데이터 다시 보내기" 3번, "PLAYING 다시 보내기" 3번
2. 일시정지 5초
3. 곡 B 재생 15초
4. 정지 → 여행 끝

기대: `track_plays`에 **정확히 2행**. A(`playedMs` ≈ 15초, 일시정지 시간 빠짐), B(≈ 15초). 실험실 화면의 "이 여행의 노래 행" 표와 `adb shell run-as ... sqlite3`(또는 디버그 화면 덤프)로 확인.
추가로: 일시정지 뒤 **A를 다시** 틀면 행이 늘지 않고 A가 이어지는지, 곡 넘기기 5초는 남지 않는지.

단위 테스트(`TrackPlayLogTest`): 위 표의 모든 줄, 짧은 곡 지우기, 10분 경계, 반복 재생, 여러 세션 중 고르기.

결과 문서: `talk-rec/docs/music-save/M02-track-log.md`.

---

## 4. M03 — 기록 상세 화면 (Claude)

### 4.1 탭 구성

상세(S14 다이어리 한 장) 머리줄 아래에 탭: **지도(기본) · 경로 · 이야기 N · 노래 N**

- 날짜·제목·함께한 사람은 탭 위 공통 머리에 남긴다.
- **경로**: 지금의 `DiaryInfo`(지도·함께 달린 시간·길 리본·사진 자리) 그대로.
- **이야기**: 지금의 `DiaryTalks` 그대로(재생·숨긴 대화 포함).
- **노래**: 새 목록(4.5).
- 고른 탭은 화면을 다시 열어도 기억하지 않는다(늘 지도에서 시작). 노래가 0곡이고 권한도 꺼져 있으면 노래 탭 숫자 대신 "꺼짐".

### 4.2 지도 탭

넓은 화면(Expanded/Full): 왼쪽 지도 + 그 아래 타임라인 띠, 오른쪽 360dp 칸에 "고른 항목 카드 + 시간순 목록".
좁은 화면(Compact): 지도 260dp → 타임라인 띠 → 고른 항목 카드 → 시간순 목록, 위에서 아래로.

**지도 위 표시** (지금 `RouteMap`의 세 겹 경로 위에 얹는다)
- 이야기 핀: 지름 30dp, 여행 색으로 채움, 흰 말풍선 아이콘, 흰 테두리 2dp. 숨긴 대화는 핀을 찍지 않는다.
- 노래 핀: 지름 22dp, 흰 바탕에 음표 아이콘, 노래 색(따뜻한 베리색 계열 한 가지) 테두리. 곡이 **시작된 곳**에 찍는다.
- 노래가 흐른 길: 노래가 재생되던 시간대의 경로를 경로 옆(line-offset)에 가는 점선으로 한 줄 더 그린다. "음악과 함께 달린 길"이 한눈에 보인다.
- 고른 핀: 1.3배로 커지고 위에 제목 말풍선("밤편지 · 아이유"). 나머지 핀은 그대로 두되 말풍선은 하나만.
- 멀리서 볼 때 핀이 겹치면 숫자 묶음(GeoJSON cluster)으로. 누르면 그 묶음으로 확대.
- 그리는 순서: 경로 → 노래 길 점선 → 노래 핀 → 이야기 핀 → 고른 핀(맨 위). 이야기가 노래보다 위에 온다.

**타임라인 띠** (출발~도착 시간을 가로로)
- 두 줄: 위는 이야기 막대(여행 색), 아래는 노래 막대(베리색 옅은 색, 고른 곡은 진하게). 정차 구간은 바탕에 옅은 회색.
- 막대를 누르면 그 항목을 고르고 지도가 그곳으로 움직인다. 지도 핀을 눌러도 띠와 목록이 같이 바뀐다(고른 것 하나를 세 곳이 같이 씀).
- 눈금은 시작·끝 시각과 그 사이 정시 두세 개만.

**고른 항목 카드**
- 이야기: 시각, 제목, 태그, 재생 버튼과 재생 막대(지금 것 재사용), 그 시간에 노래가 흐르고 있었으면 "♪ 그때 흐르던 노래 · 제목 – 가수"(누르면 그 노래를 고름).
- 노래: "10:12 – 10:16 · 4분 들음", 제목(크게), 가수 · 튼 앱, "이 노래 들으며 나눈 이야기 N개"(누르면 그 이야기를 고름). 노래 자체는 재생하지 않는다(소리 파일이 없음).
- 처음 열 때는 아무것도 고르지 않고, 카드 자리에 요약("이야기 3개 · 노래 12곡 · 3시간 10분")을 보인다.

**시간순 목록**: 이야기와 노래를 시각순으로 섞어 한 줄씩. 이야기는 진하게, 노래는 보조 글자색. 고른 줄은 바탕을 옅게 칠한다.

### 4.3 시각 → 지도 위치

`TripMoments`(순수 함수, 단위 테스트): 이야기(`TalkWithPath.startMs`)와 노래(`startedAt`)의 시각마다 위치 기록에서 시각이 가장 가까운 점을 찾는다(이분 탐색). 가장 가까운 점이 2분보다 멀면 위치 없음 → 목록·띠에만 나오고 핀은 없다. 노래 길 점선은 `[startedAt, endedAt]` 안의 위치 점들로 만든다.
이건 화면 밖 로직이라 M02와 함께 Codex에 맡겨도 된다.

### 4.4 RouteMap 확장

`RouteMap(routes, pins = emptyList(), musicPaths = emptyList(), selectedPinId = null, onPinClick = null)`
- 핀 아이콘은 `DbIcons` 벡터를 비트맵으로 바꿔 스타일 이미지로 넣고 SymbolLayer로 그린다(뷰 주석보다 가볍다).
- 고르면 카메라를 핀으로 부드럽게 옮기되, 넓은 화면에선 오른쪽 칸만큼 여백(padding)을 둔다.
- 기록 탭 전체 지도(R01)는 바꾸지 않는다(`pins` 기본값 빈 목록).

### 4.5 노래 탭

- 맨 위 요약: "노래 12곡 · 48분 들음", 그리고 **"이번 여행의 노래"** 한 곡(가장 오래 들은 곡)을 크게.
- 그 아래 시각순 목록: 시각, 제목, 가수, 들은 시간, 튼 앱. 줄을 누르면 지도 탭으로 가서 그 곡을 고른다.
- 비었을 때:
  - 권한 꺼짐: "노래 기록이 꺼져 있어요. 설정에서 켜면 다음 여행부터 들은 노래가 남아요." + "설정 열기"(정차 중에만).
  - 권한 켜짐, 0곡: "이번 여행에선 기록된 노래가 없어요."

### 4.6 주행 중

상세 화면은 지금처럼 주행 중이면 정차 안내(P01)로 바뀐다. 따로 할 일 없음.

---

## 5. M04 — 권한 안내 (Claude)

- 설정 화면에 "들은 노래 기록" 줄: 켜기 스위치 + 상태("알림 접근 필요" / "켜짐").
- 스위치를 켤 때 권한이 없으면 한 장짜리 안내: "Pleos 설정에서 동반차의 알림 접근을 허용하면, 여행 중 재생된 곡의 제목과 가수만 기록해요. 알림 내용은 읽지 않고, 기록은 이 차 안에만 남아요." → "설정 열기". M01에서 찾은 인텐트를 쓴다. 제한된 설정(2.1 C)이 걸리면 그 방법도 한 줄로.
- 돌아왔을 때 권한이 켜졌는지 다시 확인해 스위치 상태를 맞춘다.
- 여행 시작 화면 등에서 먼저 조르지 않는다. 노래 탭 빈 화면과 설정에서만 안내한다.

---

## 6. 위험과 열린 질문

- **Pleos가 알림 접근 메뉴를 숨겼을 수 있다.** M01의 A·B가 핵심. 안 되면 Pleos SDK에 미디어(지금 재생 중) API가 있는지, 앱 심사에서 알림 리스너가 허용되는지 확인 필요.
- 알림 리스너는 강한 권한이라 심사·사용자 거부감이 있을 수 있다. 문구와 "알림 내용은 읽지 않음"을 코드로 지킨다.
- 블루투스 음악은 폰 앱이 AVRCP로 보내는 메타데이터에 달려 있어 제목이 비거나 늦게 올 수 있다(M01 E에서 확인).
- 라디오는 제목이 방송 이름일 수 있다. 그대로 기록한다(들은 것이 맞으므로).
- 시각 기준: 노래·위치·녹음 모두 벽시계 ms. 차 시계가 바뀌는 경우는 다루지 않는다.

---

## 변경 기록

- 2026-11-14 (M03 연결): 노래 색을 코랄(#D85A30)에서 **베리색(#C2387F)**으로 바꿨다. 여행 색 셋 중 주황(#C26A00)과 코랄이 실제 지도에서 구분되지 않았다. 핀·말풍선은 예시 지도와 실제 지도(RouteMap)가 같은 그리기 함수(drawMomentPins)를 쓴다. 노래가 흐른 길은 Mapbox 선 레이어(점선, 경로 왼쪽으로 비켜 그림)로 그린다. 바탕 지도는 땅 mapLand·바다 mapSea로 칠하고 가게·정류장·건물·시도 경계 표시는 숨긴다.
