# I6b 이 순간 담기 버튼 (v3 주행 화면)

## 바뀐 파일 (drive-diary-int)
- ui/v3/screens/DrivingScreens.kt: DrivingUiState에 momentButton·momentDone(기본 false), DrivingEvents에 onMoment, 버튼 그리기, 카탈로그용 S05_MOMENT
- ui/v3/screens/ScreenCatalog.kt: "S05-moment" 항목 추가
- ui/v3connect/V3Mappers.kt: toDriving에서 momentButton = !ended && recordingMode == ON
- ui/v3connect/V3NavHost.kt: TRIP 화면에서 2초 막음 상태, onMoment 연결

## 위치
주행 화면 동반이 말풍선 바로 아래, 질문 카드 위. 가로 가득, 최소 높이 76dp, 외곽선 버튼, 글자 "이 순간 담기".

## 보이는 때
기록 동의 여행(recordingMode ON)이고 여행이 안 끝났을 때. 녹음 멈춤(PAUSED)·동의 안 함·준비 중·도착 후에는 숨김.

## 누르면
TripViewModel.startRecording() 호출(동의 여행이라 talkRecording.markMoment로 연결, 뷰모델 변경 없음) + 기존 스낵바로 "지금 나눈 대화를 담았어요". 버튼은 2초간 "담았어요"로 바뀌며 눌리지 않음. 전사 글은 어디에도 안 보임.

## 빌드
:app:assembleDebug 결과는 아래 보고 참조(성공 시 EXIT=0).

## 열린 질문
- PAUSED 상태에서도 담기를 보일지(지금은 숨김).
- 스낵바와 버튼 문구가 겹치므로 한쪽만 써도 됨.
