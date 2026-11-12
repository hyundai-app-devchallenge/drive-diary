# I5b 대화 요약 설정 화면 (debug) + UI 연결

## 변경
1. 새 파일 `app/src/debug/.../debug/SummarySettingsActivity.kt`: 제공자 라디오(Gemini/agy 중계/Fake), 모델 이름(+모델 저장, 기본값으로=`setModelName("")`),
   API 키(Gemini만; 비밀번호 입력칸, "끝 4자리: ••••abcd"/"저장된 키 없음", 저장 즉시 입력칸 비움, 키 지우기=`setApiKey("")`),
   중계 서버 주소(agy만; 기본값 `http://10.0.2.2:8771`, 오류코드→짧은 한글), "주행 중 제목 보이기" 스위치,
   연결 시험(진행 표시, 성공/실패, ms, 모델, 제목·태그·종류 / 오류코드 한글), 데모 전용·유료 키 권장 안내.
   주행 중(`DrivingSafetyMonitor.state.isRestricted`)에는 모든 입력·버튼·스위치·라디오 비활성 + "정차 중에만 바꿀 수 있어요". 주행이 시작되면 입력 중인 키를 비운다.
2. `app/src/debug/AndroidManifest.xml`: 액티비티 등록(exported=false, 런처 아이콘 없음). release 병합 매니페스트에는 없음(확인함).
3. `V3CatalogActivity.kt`: 목록 맨 위에 "대화 요약 설정 열기" 버튼.
4. `V3NavHost.kt`: `summarySettingsStore.settings`를 collect해 `toDriving(...).copy(showTitlesWhileDriving = ...)` 적용(여행 중 화면 한 곳).
5. `V3Mappers.kt`: 다이어리 `talks()`는 `displayTags(llm, trip.destination, m.createdAt)`. llm은 `summaryStatus == DONE`일 때만, 아니면(PENDING/FAILED/SKIPPED) 빈 목록. talk 메타가 없는 옛 녹음은 태그 없음 그대로.
   여행 중 목록은 LLM 태그만(DONE일 때만); 2개 제한·최근 3개는 기존 화면(`DrivingParts`)이 이미 건다.

## 판단/주의
- 모니터 재시작 루프: `MainActivity.onStop`이 `drivingSafetyMonitor.stop()`을 불러서 설정 화면이 앞에 오면 CarUx 구독이 끊긴다. 그래서 설정 화면이 떠 있는 동안 2초마다 `start()`(이미 시작됐으면 무동작)를 부른다.
- viewmodel/data/talkrec/gradle은 건드리지 않았다.
- 빌드: `:app:assembleDebug :app:assembleRelease` 성공. 에뮬레이터 설치·실행은 하지 않았다.

## 열린 질문
- 기기 태그를 다이어리에서 PENDING 때도 보이는 게 맞는지(지금은 보임).
- 모델 이름/주소는 "저장" 버튼을 눌러야 반영(자동 저장 아님). 제공자 전환은 즉시 저장.
- 연결 시험 결과 화면은 제목·태그를 그대로 보여준다(지어낸 대화라 문제 없음).

## AVD 확인 (Claude, 2026-11-13)

- "동반차 디버그" → "대화 요약 설정 열기"로 화면이 열린다. 기본값: 제공자 agy 중계, 모델 `gemini-3.8-flash-medium`, 중계 주소 `http://10.0.2.2:8771`, 주행 중 제목 보이기 켬.
- 연결 시험 1회차: **실패 (15,487ms, "네트워크에 연결할 수 없어요")**. 중계 서버 로그에는 같은 요청이 200으로 15,391ms에 응답한 것으로 남았다. 앱이 응답을 읽는 중 IOException을 받은 것으로 보이며, 원인은 아직 모른다(중계는 `Connection: close`와 Content-Length를 보낸다).
- 2회차: 성공 (20,575ms). 지어낸 두 줄 대화 → 제목 12자, 태그 3개(목록 안), kind moment.
- 요약 작업(Worker)은 NETWORK 오류를 백오프로 다시 시도하므로 저장 흐름에는 영향이 작다. 리허설에서 다시 일어나면 원인을 조사한다.
- 아직 확인하지 않은 것: Gemini 실제 키 입력·끝 4자리 표시·재시작 후 유지, 주행 중 입력 잠금(DebugGearReceiver로 D/P 전환).
