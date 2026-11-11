import json
from pathlib import Path

import pytest

from common.normalize import normalize

VECTOR_PATH = Path(__file__).resolve().parents[1] / "common/normalize-vectors.json"
VECTORS = json.loads(VECTOR_PATH.read_text(encoding="utf-8"))


def test_vector_file():
    assert len(VECTORS) >= 40
    assert not VECTOR_PATH.read_bytes().startswith(b"\xef\xbb\xbf")


@pytest.mark.parametrize("vector", VECTORS)
def test_normalize(vector):
    assert normalize(vector["input"]) == vector["expected"]


def test_no_device_length_limit():
    assert normalize("A" * 600) == "a" * 600
