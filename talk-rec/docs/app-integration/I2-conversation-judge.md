# I2 ConversationJudge 구현 기록

2026-11-13. 작업 위치는 `C:\github\drive-diary-int`, 브랜치는 `feat/talk-rec-integration`이다. I2 코드 변경은 `talkrec/`에 한정했다. 참고 저장소는 읽기만 했고 커밋·푸시·브랜치 전환은 하지 않았다. 검증 도구와 다운로드한 desktop ORT jar는 git에서 무시하는 `.talkrec-notes/`에만 뒀다.

`ConversationJudge`, `WordPieceTokenizer`, `ModelInputText`, `OrtConversationModel`, `BertUnicode`를 구현했다. Android에서는 다음처럼 생성하고 캡처 수명 동안 한 인스턴스를 재사용한다.

```kotlin
val judge = ConversationJudge.fromAssets { context.assets.open(it) }
val result = judge.judge(orderedLineTexts)
// result.keepProbability, result.keep, result.kind
judge.close()
```

`fromAssets`는 vocab·threshold·모델을 한 번 읽고 세션 하나를 만든다. `ConversationModel` 경계 안에 ORT 환경·세션을 숨겼고, asset stream opener 덕분에 호스트 테스트도 같은 로더와 추론 코드를 사용한다. 추론과 `close()`는 같은 잠금으로 직렬화한다. `close()`는 중복 호출할 수 있고, 닫힌 뒤 추론하면 실패한다. 입력/출력 tensor와 실행 결과는 매번 닫는다. 프로세스 공용 `OrtEnvironment`는 다른 ORT 사용자가 공유할 수 있으므로 판정기가 닫지 않는다. 로드 실패는 호출자에게 전달하며 설계의 저장 후보 fallback 연결은 이후 캡처 작업의 범위다.

입력 글은 `train/v3/data.py`가 사용하는 `demo/trial/data.py:input_text`와 같다. 줄 객체라면 호출자가 `line.text`만 꺼내 순서대로 전달한다. 각 줄의 앞뒤에 Python 3.12 `str.strip()`과 같은 공백 집합을 적용하고, 빈 줄도 유지하여 U+000A LF로 잇는다. 별도의 마지막 구분자는 붙이지 않으며 마지막 줄이 비어 있으면 join 결과에 LF가 남는 것은 정상이다. 줄 내부 공백과 CR/LF는 그대로다. 화자·시각·라벨·다른 메타데이터를 넣지 않는다. **M1에는 `common.normalize`의 NFKC·소문자화·기호 제거를 적용하지 않는다.** 입력 글 조립과 BERT tokenizer 정규화는 서로 다른 단계다.

토크나이저는 저장된 HF 설정에 맞춘 순수 Kotlin 구현이다.

- `BertNormalizer(clean_text=true, handle_chinese_chars=true, strip_accents=null, lowercase=false)`이므로 대소문자·전각·악센트·조합형을 보존한다. Hangul을 CJK 한자처럼 글자별로 미리 나누지 않는다.
- HF의 제거 문자·문장부호 판별을 `BertUnicode`의 범위표로 고정했다. 설치된 tokenizers **0.22.1**로 측정한 제거 25개 구간, 문장부호 156개 구간이다. 일반 `Character.getType`은 Android/JDK의 Unicode 버전에 따라 HF와 달라진다. 실제로 U+0890/U+13430 제거와 U+11F43 분리에서 차이를 발견했다. 미할당 U+0378도 지우면 안 된다. 유효하지 않은 단독 UTF-16 surrogate는 제거한다.
- 공백을 모두 같은 방식으로 다루면 안 된다. Python strip, Rust Unicode White_Space, BERT clean_text의 제거 순서는 다르다. 예를 들어 BERT는 TAB/LF/CR은 공백으로 바꾸지만 다른 일부 제어 공백은 먼저 제거한다.
- `BertPreTokenizer`의 공백 분리 및 ASCII/Unicode 문장부호 단독 분리를 구현했다. `[PAD]/[UNK]/[CLS]/[SEP]/[MASK]`는 정규화 전에 원문에서 AddedToken으로 찾으며 단어 내부에서도 인식한다.
- WordPiece는 가장 긴 부분부터 찾고 후속 조각에 `##`를 붙인다. `tokenizer.json`에서 확인한 `max_input_chars_per_word`는 **100**이다. UTF-16 길이가 아니라 Unicode code point 수로 센다. 100자를 넘거나 어느 접미부라도 분절할 수 없으면 단어 전체가 `[UNK]=1`이다.
- 일반 후처리는 `[CLS]=2`, `[SEP]=3`을 추가한다. 최대 길이는 256, 본문 예산은 254이며 초과하면 앞 127개와 뒤 127개를 보존한다. 일반화한 홀수 예산은 앞 ceil/뒤 floor로 나눈다. 원문에 등장한 특수 토큰도 본문 예산에 포함한다. 오른쪽 PAD=0, 유지 토큰의 mask=1, 패딩 mask=0이다. 원문의 `[PAD]`는 mask=1이다.

HF 동작 참고: [BertNormalizer 소스](https://raw.githubusercontent.com/huggingface/tokenizers/v0.22.1/tokenizers/src/normalizers/bert.rs), [BertPreTokenizer 소스](https://raw.githubusercontent.com/huggingface/tokenizers/v0.22.1/tokenizers/src/pre_tokenizers/bert.rs). 범위표는 소스 주석의 설명을 추정해서 만들지 않고 설치된 참조 구현을 직접 측정했다.

실제 ONNX를 Python onnxruntime 1.23.2로 열어 입력 이름과 자료형을 확인했다. 입력은 `input_ids`, `attention_mask`, 각각 int64 `[batch,256]`이다. **token_type_ids 입력은 없다.** 출력은 `keep_probability` float32 `[batch]`, `kind_logits` float32 `[batch,3]`이며 종류 순서는 `none/story/moment`다. keep은 이미 sigmoid를 거친 독립 이진 head의 확률을 사용한다. 종류 head로 확률을 대체하지 않는다. 종류는 전체 argmax가 story/moment면 해당 enum, none이면 null이며 keep 결과와 독립이다. ORT는 CPU, intra/inter-op 1, sequential로 설정했다.

모듈 asset `threshold.json`의 `threshold`를 **0.9573729634284973**으로 바꿨다. 비교는 `>=`이다. 기존 **0.9783868789672852**는 `int8_recalibrated_threshold`로 보존하고 `decided: "2026-11-13 user: original T55 dev cutoff"`를 추가했다. 기존 보정 메타데이터도 남겼다. 원본 `.local/export/m1/threshold.json`은 여전히 0.9783868789672852이며 수정하지 않았다.

Android 실행 의존성은 기존 `onnxruntime-android:1.23.2` AAR이다. 호스트의 `*UnitTestCompileClasspath`와 `*UnitTestRuntimeClasspath`에서만 그 AAR을 제외하고 `testImplementation`의 desktop `com.microsoft.onnxruntime:onnxruntime:1.23.2` jar를 사용한다. 두 artifact의 `ai.onnxruntime` 중복 정의와 Android native 라이브러리를 호스트에서 읽는 문제를 피한다. 호스트용 `org.json:json:20240303`도 testImplementation으로만 추가했다. ORT 클래스 리소스가 정확히 하나인지 확인하는 테스트를 넣었다. 최종 Gradle 해석 결과는 SDK 접근이 가능한 외부 실행에서 확인해야 한다.

JUnit4 테스트는 다음을 검사한다.

- 원본 `golden.json`을 바이트 그대로 `src/test/resources/m1/golden.json`에 복사했다. `invented_only=true`, 20개임을 확인한다. 글 조립·token ids·attention masks는 완전 일치, int8 `keep_probability`는 파일의 `recommended_probability_abs_tolerance=1e-4` 이내인지 검사한다. 종류 argmax도 확인한다.
- 비공개 테스트는 `TALKREC_PRIVATE_DIR`이 가리키는 **model-create/.local** 아래 `export/m1/predictions.json`, `real/v1/blocks.jsonl`, `real/v1/judgments/vizatae.jsonl`을 읽는다. 인접한 `../data/real-split.tsv`, `../data/real-cast.tsv`로 dev·탑승 구성을 확인한다. predictions의 **dataset=dev**, 확률 필드 **int8**을 사용하며 `yt:<video_id>#<ordinal>`를 블록 id와 연결한다. human 라벨이 있고 cast가 unknown이 아닌 dev만 선택하고 unsure도 포함한다. 전체는 77개, 그중 child_family 37개이며 참고 집단 40개도 포함한다. 보정 집단만 77개인 것은 아니다. Kotlin 판정과 Python int8 판정을 같은 원래 문턱에서 모든 id별로 비교한다.
- 비공개 글은 메모리에서만 처리하며 복사·출력·저장하지 않는다. private fixture 파싱 예외의 원문/원인도 노출하지 않는다. 추론·비교 실패에는 id와 확률만 쓴다. 위치가 없으면 `Assume`으로 건너뛰고, 위치를 지정했는데 파일이나 선택 집합이 잘못되면 실패한다.
- 별도 테스트로 한국어·CJK·대소문자·악센트·제어 문자·특수 토큰·100자 경계·앞뒤 절단·패딩·문턱 포함 비교·보조 head 독립성·추론 중 close 및 중복 close를 확인한다.

SDK를 읽을 수 있는 외부 PowerShell에서 실행한다.

```powershell
Set-Location C:\github\drive-diary-int
# 비공개 위치가 없으면 해당 테스트만 Assume으로 건너뛴다.
.\gradlew.bat :talkrec:testDebugUnitTest

# 비공개 dev 77개까지 검증. 실제 데이터는 복사하지 않는다.
$env:TALKREC_PRIVATE_DIR = 'C:\github\drive-diary\talk-rec\model-create\.local'
.\gradlew.bat :talkrec:testDebugUnitTest --rerun-tasks

# 환경 변수 대신 Gradle 시스템 속성을 전달해도 된다.
.\gradlew.bat '-DTALKREC_PRIVATE_DIR=C:\github\drive-diary\talk-rec\model-create\.local' :talkrec:testDebugUnitTest --rerun-tasks
```

Gradle 설정은 시스템 속성을 우선하고 없으면 환경 변수를 읽어 테스트 JVM에 시스템 속성으로 전달한다. 테스트가 main assets를 읽는 절대 경로도 전달하며 M1 assets를 테스트 입력으로 등록했다. 비공개 자료 자체는 테스트 리소스나 Gradle 입력으로 복사하지 않는다. private 원본을 바꿨을 때에는 `--rerun-tasks`를 사용한다.

샌드박스에서는 지시대로 **Gradle을 실행하지 않았다.** Android SDK를 읽을 수 없기 때문에 Android 모듈 빌드·AGP DSL 해석·Gradle의 실제 test classpath는 아직 검증하지 못했다. 대신 캐시된 Kotlin 2.2.10 컴파일러를 Java로 직접 실행해 JVM target 11로 판정 코드와 테스트를 컴파일했고, desktop ORT 1.23.2로 **13개 테스트를 모두 통과**했다. 이 실행에는 골든 20개와 비공개 dev 77개가 포함된다. 캐시된 Android AAR의 `classes.jar`에 대해서도 판정 코드의 SDK 없는 컴파일을 따로 통과했다. JNI 임시 파일은 `.talkrec-notes/tmp`로 제한했다.

추가 Python 대조는 원본 `input_text`, `load_tokenizer`, `token_batch`를 그대로 호출했다. 골든 20개 + dev 77개 + 직접 만든 경계 입력 101개에서 **UTF-8 입력 SHA-256·토큰 ID·마스크 불일치 0건**이다. 비공개 입력은 subprocess의 stdin으로만 전달했다. 제거/문장부호 분류는 유효 Unicode scalar 1,112,064개 전체를 참조와 Kotlin에서 각각 계산한 SHA-256이 일치했다. 모델 및 골든 복사본은 원본과 바이트 해시가 같다. 새 Python 패키지·전역 설치는 없었다.

원본 export의 `load_private_dev`와 `audit_private`를 읽기 전용으로 호출해 `talkrec/`와 메모의 텍스트 파일 54개를 검사했다. dev 77개에서 얻은 20 code point 이상 전사 조각 426개와 Unicode/JSON 이스케이프 형태로 대조한 결과 **일치 0건**이다.

남은 확인 사항은 Claude의 외부 `:talkrec:testDebugUnitTest` 실행과 Android/ARM 실제 기기의 연산 지원·확률·메모리·지연 검증이다. 이 작업의 호스트 통과 결과만으로 T59 기기 성능이나 I3 이후 캡처 연결을 통과했다고 보지 않는다. 토크나이저나 HF 버전을 바꾸면 고정 Unicode 범위표와 입력 계약을 다시 검증해야 한다.

## 샌드박스 밖 검증 (Claude, 2026-11-13)

- `TALKREC_PRIVATE_DIR`를 지정하고 `.\gradlew.bat :talkrec:testDebugUnitTest :app:assembleDebug --rerun-tasks`: BUILD SUCCESSFUL.
- 테스트 6묶음 14개 모두 통과, 건너뜀 0(비공개 dev 77개 비교 포함). 팀 앱 debug 빌드도 통과.
- 남은 것: 실제 기기(ARM)에서의 확률·지연·메모리(I7).
