"""미리 설치된 PyAV로 앞 2초·뒤 1초를 포함한 16kHz mono WAV 생성."""
import argparse
import math
import os
from pathlib import Path
import tempfile
import wave

from .storage import ROOT, audio_path, clip_name, read_jsonl


def cut(source, destination, start, end):
    import av
    destination.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix=destination.stem + '.', suffix='.tmp.wav', dir=destination.parent)
    os.close(fd)
    samples = 0
    try:
        with av.open(str(source)) as container, wave.open(temporary, 'wb') as output:
            stream = container.streams.audio[0]
            container.seek(int(start * av.time_base), backward=True)
            resampler = av.AudioResampler(format='s16', layout='mono', rate=16000)
            output.setparams((1, 2, 16000, 0, 'NONE', 'not compressed'))
            def write(frame):
                nonlocal samples
                if frame.time is None:
                    raise ValueError('오디오 타임스탬프가 없습니다.')
                lo = max(0, math.ceil((start - frame.time) * 16000 - 1e-6))
                hi = min(frame.samples, math.ceil((end - frame.time) * 16000 - 1e-6))
                if hi > lo:
                    output.writeframesraw(bytes(frame.planes[0])[lo * 2:hi * 2])
                    samples += hi - lo
            for frame in container.decode(stream):
                for converted in resampler.resample(frame):
                    write(converted)
                if frame.time is not None and frame.time > end + .1:
                    break
            for converted in resampler.resample(None):
                write(converted)
        if not samples:
            raise ValueError('잘라낸 오디오가 비어 있습니다.')
        os.replace(temporary, destination)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)
    return samples / 16000


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sample', type=Path, default=ROOT / '.local/demo/sample.jsonl')
    args = parser.parse_args(argv)
    rows = read_jsonl(args.sample)
    if not rows:
        parser.error('표본 파일이 없거나 비어 있습니다.')
    for i, row in enumerate(rows, 1):
        destination = ROOT / '.local/demo/clips' / clip_name(row['id'])
        seconds = cut(audio_path(ROOT, row), destination, max(0, row['start'] - 2), row['end'] + 1)
        print(f'{i}/{len(rows)} {row["id"]} {seconds:.3f}s')


if __name__ == '__main__':
    main()
