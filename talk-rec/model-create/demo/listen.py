"""Reserved C arm interface. No real audio LLM adapter is implemented."""
from typing import Protocol


class ListenBackend(Protocol):
    def judge(self, audio_path: str, start: float, end: float, prompt: str) -> dict: ...


class FakeListenBackend:
    """Deterministic plumbing fixture; does not open or understand audio."""
    usage = {}

    def judge(self, audio_path, start, end, prompt):
        return {'verdict': 'none', 'note': '가짜 음성 백엔드의 고정 판정이다.'}
