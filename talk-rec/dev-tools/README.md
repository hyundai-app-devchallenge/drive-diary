# 대화 샘플 전사 도구

Windows x64에서 `yt-dlp`로 음성 파일을 받고 `faster-whisper`로 로컬 전사한다. 기본 모델은 한국어를 지원하는 `large-v3-turbo`이며, NVIDIA GPU에서 CUDA FP16으로 실행한다. 첫 전사에서 모델을 내려받고 이후 캐시를 재사용한다. 원본과 전사본은 `.local/` 아래에 보관한다.

## 설치

저장소 루트에서 아래 명령을 실행한다.

```powershell
Set-Location './talk-rec/dev-tools'
& .\setup.ps1
```

설치 스크립트는 이 폴더에 uv, Python 3.12, Deno, 가상환경, CUDA 12 및 cuDNN 9 런타임을 준비한다. GPU 사용에는 호환되는 NVIDIA 드라이버가 필요하다. 음성 디코딩은 faster-whisper의 PyAV를 사용한다.

## 전사할 음성 준비

다운로드할 수 있는 소스에서 한국어 음성 트랙을 우선 가져온다. 로컬 원본 파일이 있으면 해당 파일을 바로 전사할 수 있다. 학습용으로 채택할 때는 소스의 사용 범위와 출처를 확인한다.

```powershell
$url = 'https://www.youtube.com/watch?v=VIDEO_ID'
$deno = (Resolve-Path .\.local\deno\deno.exe).Path
.\.venv\Scripts\python.exe -m yt_dlp --ignore-config --no-playlist `
  --js-runtimes "deno:$deno" -f 'bestaudio[language^=ko]/bestaudio' `
  -P .local/samples -o '%(id)s.%(ext)s' $url
```

## CUDA 전사

```powershell
.\.venv\Scripts\python.exe .\transcribe.py .\.local\samples\VIDEO_ID.webm `
  --clip 113 203 --clip 391 481 --output .\.local\reviews\sample.json
```

- 파일 확장자는 다운로드 결과에 맞춘다. `--clip`은 원본 영상의 시작·끝을 초로 지정한다. 생략하면 파일 전체를 전사한다.
- JSON과 SRT에는 원본 기준 타임스탬프가 들어간다. 화자 구분과 품질 검수는 별도 단계다.
- 기본 인식 언어는 한국어다. 언어 자동 감지는 `--language auto`, CPU 실행은 `--device cpu --model small`을 쓴다.
- 후보 구간을 검수할 때는 화자 사이에 여러 차례 주고받는 흐름이 있는지, 핵심 사건·부정 표현·고유명사가 전사에 남았는지 원음과 비교한다. 같은 출연자나 재편집 영상은 학습·평가 세트 사이에 나누지 않는다.
