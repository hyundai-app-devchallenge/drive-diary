from pathlib import Path

import pytest

from common.schema import validate_utterance
from pipeline.segment import (
    RATE, build_records, distribution, group_words, input_sources, make_record,
    parser, pcm_boundaries, rejection, source_name,
)


def word(start, end, text=" 말", probability=0.9):
    return dict(start=start, end=end, text=text, probability=probability)


def test_gap_and_exact_threshold():
    words = [word(0, 0.2), word(1.199, 1.4), word(2.4, 2.6), word(4, 4.2)]
    assert group_words(words) == [words[:2], words[2:3], words[3:]]


def test_cap_at_last_whole_word_and_exact_cap():
    words = [word(i, i + 1) for i in range(14)]
    assert group_words(words) == [words[:12], words[12:]]
    assert group_words(words, max_seconds=5) == [words[:5], words[5:10], words[10:]]


def test_cap_checks_end_not_only_start():
    words = [word(0, 3), word(3, 6), word(6, 9), word(9, 11), word(11, 13)]
    assert group_words(words) == [words[:4], words[4:]]


def test_overlap_cannot_hide_an_over_cap_earlier_end():
    words = [word(0, 10), word(1, 2), word(2, 3)]
    assert group_words(words) == [words]
    assert make_record("yt:test", 0, words)["end"] == 10


def test_empty_input():
    assert group_words([]) == []
    assert distribution([])["median"] is None


@pytest.mark.parametrize("words", [[word(0, 13)], [word(2, 3), word(0, 1)],
                                    [word(1, 0)], [word(-1, 1)], [word(0, float("nan"))]])
def test_grouping_rejects_unclean_words(words):
    with pytest.raises(ValueError):
        group_words(words)


def raw(words):
    return dict(vad_removed=[], audio_duration=30, audio_samples=30 * RATE,
                inference_seconds=2, decoding={}, segments=[dict(
                    start=0, end=30, text=" synthetic", no_speech_prob=0.01,
                    avg_logprob=-0.2, words=words)])


def test_long_artifact_is_removed_and_cannot_hide_a_gap():
    args = parser().parse_args(["synthetic.wav"])
    words = [word(0, 0.5), word(0.5, 8, " artifact"), word(8, 8.5)]
    records, stats = build_records(raw(words), "yt:test", args)
    assert len(records) == 2
    assert [(r["start"], r["end"]) for r in records] == [(0, 0.5), (8, 8.5)]
    assert stats["removed_counts"]["long_word_artifact"] == 1
    assert stats["removed"][0]["text"] == " artifact"


def test_invalid_timestamp_is_audited():
    records, stats = build_records(raw([word(1, 0), word(1, 2)]), "yt:test", parser().parse_args(["x"]))
    assert len(records) == 1
    assert stats["removed_counts"]["invalid_word_time"] == 1


def test_zero_duration_backchannel_is_retained_without_inventing_word_times():
    args = parser().parse_args(["x", "--export-pcm"])
    records, stats = build_records(raw([word(1, 1, " 응")]), "yt:test", args)
    assert len(records) == 1
    assert records[0]["start"] == records[0]["end"] == 1
    assert records[0]["text"] == "응"
    assert records[0]["pcm"]["start"] == 0.7
    assert records[0]["pcm"]["end"] == 1.2
    assert stats["zero_duration_words_kept"] == 1
    assert not any(stats["removed_counts"].values())


@pytest.mark.parametrize("text,probability,reason", [
    ("[음악]", 0.9, "non_speech_marker"), ("♪ ♫", 0.9, "non_speech_marker"),
    ("시청해 주셔서 감사합니다. 감사합니다.", 0.9, "korean_closing"),
    ("자막 제공: synthetic", 0.9, "korean_closing"),
    ("감사합니다.", 0.3, "korean_closing"),
    ("네 네 네 네 네 네", 0.9, "repeated_token"),
    ("합성 테스트", 0.1, "low_confidence"),
    ("네", 0.9, None), ("응 응", 0.9, None), ("아", 0.9, None),
    ("하하 하하 하하 하하 하하 하하", 0.9, None), ("[웃음]", 0.9, None),
    ("흐흐 흐흐 흐흐 흐흐 흐흐 흐흐", 0.9, None),
    ("깔깔 깔깔 깔깔 깔깔 깔깔 깔깔", 0.9, None),
    ("[웃음] [웃음] [웃음] [웃음] [웃음] [웃음]", 0.9, None),
    ("haha haha haha haha haha haha", 0.9, None),
    ("감사합니다.", 0.9, None), ("음악 좋아", 0.9, None),
    ("합성 테스트", 0.2, None),
])
def test_filters(text, probability, reason):
    assert rejection([word(0, 1, text, probability)]) == reason


def test_filter_thresholds_are_parameters():
    words = [word(0, 1, "네 네 네", 0.4)]
    assert rejection(words) is None
    assert rejection(words, repeat_count=3) == "repeated_token"
    assert rejection(words, min_probability=0.5) == "low_confidence"
    assert rejection([word(0, 1, "감사합니다.", 0.4)], closing_probability=0.3) is None


def test_no_speech_segments_and_missing_timestamps_are_audited():
    data = raw([word(0, 1)])
    data["segments"][0].update(no_speech_prob=0.9, avg_logprob=-2)
    records, stats = build_records(data, "yt:test", parser().parse_args(["x"]))
    assert not records
    assert stats["removed_counts"]["segment_no_speech"] == 1
    data["segments"][0].update(no_speech_prob=0, words=[])
    _, stats = build_records(data, "yt:test", parser().parse_args(["x"]))
    assert stats["removed_counts"]["missing_word_times"] == 1


def test_records_preserve_raw_text_and_validate_schema():
    words = [word(0, 0.5, " 안녕,"), word(0.6, 1, "  테스트!")]
    record = make_record("yt:test", 0, words)
    validate_utterance(record)
    assert record["text"] == "안녕,  테스트!"
    assert record["asr"] == "whisper" and record["speaker"] is None


def test_records_convert_timestamp_scalars_to_schema_number_types():
    class Scalar(float):
        pass
    record = make_record("yt:test", 0, [word(Scalar(0), Scalar(1))])
    validate_utterance(record)
    assert type(record["start"]) is float and type(record["end"]) is float


@pytest.mark.parametrize("start,end,total,expected", [
    (1, 2, 10, (0.7, 2.2)), (0.1, 1, 10, (0, 1.2)),
    (1, 2, 2, (0.7, 2)), (1, 12.7, 20, (0.7, 12.7)),
    (1, 12.6, 20, (0.7, 12.7)),
])
def test_pcm_boundary_arithmetic(start, end, total, expected):
    first, last = pcm_boundaries(start, end, total * RATE)
    assert (first / RATE, last / RATE) == pytest.approx(expected)
    assert last - first <= 12 * RATE
    assert last >= round(end * RATE)


def test_pcm_refuses_speech_truncation():
    with pytest.raises(ValueError, match="pre-roll exceeds"):
        pcm_boundaries(1, 13, 20 * RATE)


def test_pcm_export_grouping_reserves_preroll():
    args = parser().parse_args(["x", "--export-pcm"])
    words = [word(i, i + 1, f" 합성{i}") for i in range(14)]
    records, stats = build_records(raw(words), "yt:test", args)
    assert len(records) == 2
    assert stats["effective_word_span_cap"] == 11.7
    for record in records:
        validate_utterance(record)
        assert record["pcm"]["end_sample"] - record["pcm"]["start_sample"] <= 12 * RATE
        assert record["pcm"]["end"] >= record["end"]


def test_distribution_interpolates():
    assert distribution([1, 2, 3, 4]) == dict(min=1, median=2.5, p90=3.7, max=4)


@pytest.mark.parametrize("source", ["yt:../bad", "yt:bad?", "..", "CON", "", "yt:bad\\file"])
def test_unsafe_source(source):
    with pytest.raises(ValueError):
        source_name(source)


def test_sanitized_source_and_single_input_contract():
    assert source_name("yt:VIDEO_ID") == "yt_VIDEO_ID"
    with pytest.raises(ValueError):
        input_sources(Path(__file__), None)
