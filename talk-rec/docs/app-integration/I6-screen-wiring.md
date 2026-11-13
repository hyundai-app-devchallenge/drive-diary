# I6 화면 연결 요약

변경 파일 (app/src/main/java/com/dongbantest/app/...)
- ui/v3connect/V3Mappers.kt: 녹음 칩(TalkRec 상태), 지금까지 나눈 대화 병합, S14 제목/태그, S11 saved, S12 단계
- ui/v3connect/V3NavHost.kt: TalkRec.state 를 collect 해서 toDriving/toArrival 에 전달 (3줄)
- ui/components/Components.kt: RecordVoiceButton 에 momentMode 추가 ("이 순간 담기")
- ui/screens/TripScreen.kt, MemoryCaptureScreen.kt: momentMode 전달 (예전 화면, 각 1줄)
- viewmodel/** 수정 없음. data/talkrec 등 상대 영역 수정 없음.

## 동작
1. 녹음 칩: 기록 동의 여행(recordingMode ON/PAUSED)은 TalkRec.state 로 표시. On "녹음 중 N분", Paused "녹음 멈춤 N분",
   그 외(시작 전/서비스 꺼짐/마이크 권한 대기) Ready "녹음 준비됨", 여행 끝 Saved "녹음 저장됨 N분".
   N분은 누적 캡처 시간(끝난 뒤에는 max(누적, AUTO 조각 합)). 동의 없음/미결정 여행은 기존 동작 그대로.
2. 지금까지 나눈 대화: QUESTION + (VOICE && talk!=null) 를 createdAt 최신순으로 합침. 조각: 시각 HH:mm, 제목 talk.title 없으면 "대화 저장됨",
   태그 talk.tags(없으면 빈 목록). 전사 글 미사용. 질문은 태그 없음. "지금" 표시는 최신 항목이 질문일 때만. 주행 규칙(3개, 한 줄, 태그 2개, 숨김 토글)은 I6a 구현 그대로.
3. S14: 제목 = talk.title ?: "목소리 HH:mm", 전사 글은 어디에도 안 씀. tags = talk.tags. displayTags TODO 표시(V3Mappers.talks()).
4. S11 saved: AUTO 조각 durationMs 합 -> "녹음 저장됨 N분", 0이면 "저장된 대화가 없어요", 미동의 OFF "녹음하지 않은 여행".
   S12: 대화 조각이 있으면 "녹음 저장" 다음에 단계 삽입. PENDING 있으면 "대화를 글로 옮기는 중 · N개 남음"(진행), 없으면 같은 이름 완료(FAILED/SKIPPED 포함). 조각이 없으면 기존 3단계.
5. 이 순간 담기: 현재 v3 화면에는 수동 녹음 버튼이 없다(음성 명령만). 버튼이 있는 예전 화면(TripScreen, MemoryCaptureScreen)의 RecordVoiceButton 에
   momentMode(상시 캡처 ON)일 때 "이 순간 담기"(동의창 없이 onStart -> TripViewModel.startRecording -> markMoment). 동의 없는 여행은 "목소리 남기기" 그대로.
6. 샘플/카탈로그: 새 매개변수는 모두 기본값이라 카탈로그 수정 불필요(I6a 샘플에 이미 태그 있음).

빌드: :app:assembleDebug 성공.

## 열린 질문
- S12 화면은 0.9초 간격 애니메이션 뒤 자동으로 S13 으로 넘어간다. PENDING 이 남아도 기다리지 않는다(요약은 네트워크 의존). 기다리게 할지 결정 필요.
- 캡처 상태(TalkRec.state)는 프로세스 전역이라 trip id 가 없다. 동의 여행 + 상태가 Off/Ready 면 "준비됨"으로 표시한다.
- v3 화면에 "이 순간 담기" 버튼 자체를 새로 만들지(디자인 필요), 음성 명령("이 순간 담아 줘")으로 할지.
- 조각 제목이 없을 때 "대화 저장됨"(여행 중), "목소리 HH:mm"(다이어리) 문구 확정.

## 검토 메모 (Claude, 2026-11-13)

- v3 화면과 mapper에서 전사 글을 제목·목록에 쓰는 곳은 없다.
- 예전(v3 이전) 공용 컴포넌트 `ui/components/Components.kt:449`는 VOICE 기록의 `transcript`를 따옴표로 보여 준다. 팀 앱이 원래 만들어 둔 표시이고 v3 흐름에서 쓰이는지는 리허설 때 확인한다. 기기 안 표시라 규약 위반은 아니지만, STT 품질이 낮아 그대로 보일지 사람이 정해야 한다.
- 빌드 확인은 Codex I5 결과와 합친 뒤 Claude가 다시 한다.
