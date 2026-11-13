"""Invented phrases only; no actual transcripts, app state or model weights."""
from copy import deepcopy
import json

import pytest

from common.normalize import normalize
from policy import AppSignals, PolicyGate, gate
from policy.gate import compact, one_edit


@pytest.mark.parametrize("lines", [
    ["녹음하지 마"], ["이건 저장하지 마"], ["방금 거 지워"], ["기록 끄자"],
    ["오늘 가족 대화가 재미있네", "녹음", "하지 마", "우리끼리만 기억하자"],
    ["녹음하짖마"], ["우리 얘기는 남기지 말자"],
    ["이 얘긴 남기지 말자"], ["지금 건 녹음하지 마"],
])
def test_optout_blocks_everything_and_requests_retro_delete(lines):
    result = gate(lines)
    assert result["block"] == "block"
    assert result["retro_delete"] is True
    assert result["drop_lines"] == list(range(len(lines)))
    assert any(r.startswith("optout.") for r in result["reasons"])


@pytest.mark.parametrize("lines", [
    ["에어컨 22도로"], ["집으로 안내해 줘", "볼륨 올려"], ["글레오"],
    ["에어컨", "22도로"], ["헤이 글레오", "할머니에게 전화 걸어 줘"],
    ["글레오노래", "찾아줘"],
])
def test_command_only_and_fragments(lines):
    result = gate(lines)
    assert result["block"] == "block"
    assert result["retro_delete"] is False


def test_mixed_keeps_dialogue_and_input_indices_and_does_not_mutate():
    lines = [dict(text="어릴 때 바다 갔었지", start=0), dict(text="", start=1),
             dict(text="에어컨", start=2), dict(text="22도로", start=3),
             dict(text="모래성 쌓던 날이네", start=4), dict(text="다시 가자", start=5),
             dict(text="응 이번엔 할머니랑", start=6)]
    original = deepcopy(lines)
    result = gate(lines)
    assert result == dict(block="allow", drop_lines=[2, 3], retro_delete=False,
                          reasons=["command.climate"])
    assert lines == original
    assert [r["text"] for i, r in enumerate(lines) if i not in result["drop_lines"]][-1] == "응 이번엔 할머니랑"


def test_strict_majority_counts_nonempty_input_lines():
    assert gate(["볼륨 올려", "아빠 바다가 예쁘다", ""])['block'] == "allow"
    assert gate(["볼륨 올려", "집으로 안내해 줘", "아빠 바다가 예쁘다"])["block"] == "block"


@pytest.mark.parametrize("text", [
    "지우개 어디 있어", "사진 저장했어?", "불 꺼줘 엄마", "칠판 그림 지워 줘",
    "내비가 우회전이라고 말하더라", "집으로 안내해 주는 내비가 편해",
    "글레오라는 이름이 재미있어", "볼륨 올려 놓으니까 북소리가 들리더라",
])
def test_ambiguous_family_speech_allowed(text):
    assert gate([text]) == dict(block="allow", drop_lines=[], retro_delete=False, reasons=[])


def test_external_and_one_navigation_line():
    assert gate(["300미터 앞에서 우회전입니다"])["block"] == "block"
    assert gate(["김민수님이 보내온 사연입니다"])["block"] == "block"
    assert gate(["청취자께서 보내 주신 사연을 소개합니다"])["block"] == "block"
    result = gate(["어제 그 꽃 봤지", "300미터 앞에서 우회전입니다", "노란 꽃 예뻤어"])
    assert result["block"] == "allow" and result["drop_lines"] == [1]


def test_app_signal_unknown_and_playing_alone_do_not_identify_speaker():
    assert gate(["우리 같이 만든 케이크야"], signals=AppSignals(playback_active=True))["block"] == "allow"
    result = gate(["글만으로는 화자를 모른다"], signals=AppSignals(external_audio_dominant=True))
    assert result == dict(block="block", drop_lines=[0], retro_delete=False, reasons=["external.app_dominant"])
    assert gate(["녹음하지 마"], signals=AppSignals(external_audio_dominant=True))["retro_delete"]
    with pytest.raises(ValueError, match="signal"):
        AppSignals(external_audio_dominant="yes")


def test_shared_normalization_and_configurable_wake_words():
    assert compact(" ＡＢＣ_２２, 녹음! ") == normalize(" ＡＢＣ_２２, 녹음! ").replace(" ", "")
    assert gate(["에어컨２２도로!"])["block"] == "block"
    engine = PolicyGate(wake_words=["안녕 별이"])
    assert engine.gate(["안녕별이"])["block"] == "block"
    assert engine.gate(["글레오"])["block"] == "allow"
    assert PolicyGate(wake_words=[]).gate(["글레오"])["block"] == "allow"


def test_optout_still_seen_when_command_matched_in_overlapping_span():
    result = gate(["글레오 녹음하지", "마"])
    assert result["block"] == "block" and result["retro_delete"] is True


def test_empty_inputs_and_no_state_leak_between_blocks():
    assert gate([]) == dict(block="allow", drop_lines=[], retro_delete=False, reasons=[])
    gate(["기록 끄자"])
    assert gate(["어제 꽃이 예뻤어"])["retro_delete"] is False


@pytest.mark.parametrize("lines", [None, "창작 비밀 값", [None], [{}], [{"text": 3}]])
def test_invalid_inputs_do_not_echo_text(lines):
    with pytest.raises(ValueError) as error:
        gate(lines)
    assert "창작 비밀 값" not in str(error.value)


@pytest.mark.parametrize("a,b,want", [("abcdef", "abcdef", True), ("abcdef", "abcxef", True),
    ("abcdef", "abcef", True), ("abcdef", "abcdxef", True), ("abcdef", "abxyef", False),
    ("", "a", True), ("ab", "", False)])
def test_bounded_edit_distance(a, b, want):
    assert one_edit(a, b) is want
