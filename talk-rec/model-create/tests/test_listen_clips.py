import math
import struct
import wave

import pytest

from tools.listen.clips import cut


def test_pyav_exact_interval_and_eof(tmp_path):
    source = tmp_path / 'synthetic.wav'
    with wave.open(str(source),'wb') as output:
        output.setparams((1,2,16000,0,'NONE','not compressed'))
        output.writeframes(b''.join(struct.pack('<h',int(1000*math.sin(i*.1))) for i in range(80000)))
    for start,end,expected in [(1,3,2),(0,2,2),(4,7,1)]:
        destination = tmp_path / f'{start}.wav'
        assert cut(source,destination,start,end) == pytest.approx(expected,abs=.001)
        with wave.open(str(destination),'rb') as clip:
            assert clip.getframerate() == 16000 and clip.getnchannels() == 1
            assert clip.getnframes() == expected * 16000
