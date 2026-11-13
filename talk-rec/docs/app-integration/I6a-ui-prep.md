# I6a UI 준비 요약

변경 파일 (app/src/main/java/com/dongbantest/app/...)
- ui/v3/components/UiModels.kt: AskedItem 에 tags 추가 (기본 빈 목록)
- ui/v3/screens/SampleData.kt: TalkUi 에 tags 추가, 샘플 데이터에 태그
- ui/v3/screens/DiaryScreens.kt: 이야기 제목 한 줄 말줄임, 태그 칩(Tag small) 표시
- ui/v3/components/DrivingParts.kt: AskedPanel 에 driving/hideTitles/hiddenLabel. 주행 중 제목 한 줄, 태그 2개, 숨김 시 "나눈 대화" 중립 문구. 애니메이션·소리·진동 없음, 목록은 스크롤/클릭 없음
- ui/v3/screens/DrivingScreens.kt: DrivingUiState.showTitlesWhileDriving(기본 true). 주행 중(stopped=false)엔 최근 3개만, 끄면 제목·태그·"지금: 제목" 숨김
- ui/v3/screens/HomeScreens.kt: S03 동의를 3선택으로 (라디오 2 + AI 전송 체크박스, 기록 선택 시에만 활성). 문구는 초안. onStart(record, sendToAi)
- data/repository/RecordingConsentStore.kt: AI 전송 동의 별도 키(trip_N_ai), 기존 값 호환, set(id, consent, sendToAi)/getSendToAi
- ui/v3connect/V3NavHost.kt: 새 ConsentScreen 콜백 연결 (소규모)
- V3Mappers 는 기본값(빈 태그)으로 컴파일되어 수정 없음. 카탈로그(S03, S05/S06, S14)는 기본 샘플 데이터라 새 UI가 그대로 보임.

빌드: :app:assembleDebug 성공.

설계 질문
1. 제목 숨김 시 중립 표기로 "시각 + 나눈 대화"를 썼음. 괜찮은지.
2. 주행 판정은 기존 DrivingUiState.stopped(=!CarUx restricted)를 사용.
3. S03 기본 선택을 "기록"으로 둠(AI 전송은 기본 해제). 기본 선택 없음으로 할지.
4. 폰 폭 레이아웃은 "지금까지 나눈 대화" 목록이 없고 요약 줄만 있어 태그는 넓은 화면 패널에만 보임.

## 검토 메모 (Claude, 2026-11-13)

- 빌드 통과(Sonnet 실행). diff를 읽어 확인했다. 주행 중 최근 3개, 제목 한 줄, 태그 2개, 숨김 토글 모두 설계 §6과 맞다.
- **연결 시 주의**: 팀 앱의 "지금까지 나눈 대화"(`V3Mappers.toDriving`)는 지금 `MemoryType.QUESTION`, 즉 동반이가 던진 **질문 목록**이다. 저장된 대화 조각(VOICE + talk_meta)을 여기에 어떻게 합칠지(질문과 섞어 시각순, 또는 대화 조각만) I6 연결 때 정해야 한다. 초안: 시각순으로 섞고, 대화 조각만 태그가 붙는다.
- 샘플 태그 일부("연애", "기대", "설렘")는 화제 태그 초안 목록 밖이다. 미리보기용이라 그대로 둔다.
