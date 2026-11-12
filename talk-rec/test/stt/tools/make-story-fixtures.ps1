param([Parameter(Mandatory)][string]$Output)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Speech
$format = [System.Speech.AudioFormat.SpeechAudioFormatInfo]::new(
    16000, [System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen,
    [System.Speech.AudioFormat.AudioChannel]::Mono)
$ko = 'Microsoft Heami Desktop'
$en = 'Microsoft Zira Desktop'
$rows = Import-Csv (Join-Path $PSScriptRoot 'story-benchmark.tsv') -Delimiter "`t" -Encoding utf8
# Double brackets mark English TTS. Other parts use the Korean voice, except English-only lines.
$fixtures = [ordered]@{
    e01 = '제주도에서 [[Bluetooth]]가 끊겨서 아빠가 반주 없이 노래를 불렀잖아.'
    e02 = '제주도 가기 전에 [[Bluetooth]] 연결하고 음악 틀어 줘.'
    e03 = '엄마가 첫 [[drive through]]에서 앞 차 사람 커피까지 사 줬어.'
    e04 = '[[drive through]] 가려면 이 차선에서 유턴 가능해?'
    e05 = '할머니랑 피크닉 갔을 때 비 오니까 우산 아래서 김밥을 먹었지.'
    e06 = '피크닉 가는 날 비 오면 장소를 실내로 바꿔 줘.'
    e15 = '버스 놓친 날 스피커에서 [[Happy Birthday]]가 나오니까 다 같이 나한테 불러 줬어.'
    e16 = '[[Happy Birthday]] 플레이리스트 틀고 볼륨 두 칸 줄여 줘.'
    e17 = ($rows | Where-Object id -eq 'e17').text
    e18 = ($rows | Where-Object id -eq 'e18').text
    e29 = ($rows | Where-Object id -eq 'e29').text
    e30 = ($rows | Where-Object id -eq 'e30').text
}
New-Item -ItemType Directory -Path $Output -Force | Out-Null
foreach ($entry in $fixtures.GetEnumerator()) {
    $synth = [System.Speech.Synthesis.SpeechSynthesizer]::new()
    $outputStream = [System.IO.MemoryStream]::new()
    try {
        $parts = [regex]::Split($entry.Value, '\[\[(.*?)\]\]')
        for ($i = 0; $i -lt $parts.Count; $i++) {
            if ([string]::IsNullOrWhiteSpace($parts[$i])) { continue }
            $stream = [System.IO.MemoryStream]::new()
            try {
                $synth.SetOutputToAudioStream($stream, $format)
                $synth.SelectVoice($(if ($i % 2 -eq 1 -or $entry.Key -in 'e29','e30') { $en } else { $ko }))
                $synth.Speak($parts[$i])
                $synth.SetOutputToNull()
                $pcm = $stream.ToArray()
                if ($parts.Count -eq 1) {
                    $outputStream.Write($pcm, 0, $pcm.Length)
                    continue
                }
                # Keep 30 ms of context at each edge, and remove pauses created by separate synthesis calls.
                $first = 0
                $last = $pcm.Length - 2
                while ($first -lt $last -and [Math]::Abs([int][BitConverter]::ToInt16($pcm, $first)) -lt 90) { $first += 2 }
                while ($last -gt $first -and [Math]::Abs([int][BitConverter]::ToInt16($pcm, $last)) -lt 90) { $last -= 2 }
                $start = [Math]::Max(0, $first - 960)
                $end = [Math]::Min($pcm.Length, $last + 962)
                $outputStream.Write($pcm, $start, $end - $start)
            } finally {
                $stream.Dispose()
            }
        }
        [System.IO.File]::WriteAllBytes((Join-Path (Resolve-Path $Output) ($entry.Key + '.pcm')), $outputStream.ToArray())
    } finally {
        $synth.Dispose()
        $outputStream.Dispose()
    }
}
