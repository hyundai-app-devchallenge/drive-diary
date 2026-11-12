"""Pure renderers. All acoustic marker rules live in MARKERS."""
from html import escape

# event name, Korean marker; all compare score >= threshold (default 0.3).
MARKERS = (
    ('laughter', '웃음'), ('singing', '노래'), ('child_speech', '아이 목소리'),
    ('baby_cry', '아기 울음'), ('shout', '큰 소리'), ('applause', '박수·환호'),
)
DEFAULT_THRESHOLD = 0.3


def _number(value):
    return 'null' if value is None else f'{value:g}'


def _render(block, acoustic=None):
    attrs = (f'id="{block["block"]}" seconds="{_number(block["end"] - block["start"])}" '
             f'gap_before="{_number(block.get("gap_before"))}"')
    by_utt = {}
    threshold = DEFAULT_THRESHOLD
    if acoustic:
        if acoustic.get('id') != block['id']:
            raise ValueError('acoustic/block id mismatch')
        summary = acoustic.get('block', {})
        events = summary.get('events', {})
        threshold = acoustic.get('threshold', DEFAULT_THRESHOLD)
        attrs += f' speakers="{summary.get("n_speakers", 0)}"'
        for event in ('music', 'singing', 'laughter'):
            attrs += f' {event}="{events.get(event, 0):.3f}"'
        attrs += f' overlap="{summary.get("overlap_share", 0):.3f}"'
        by_utt = {row['utt']: row for row in acoustic.get('turns', [])}
    times = block.get('turn_times')
    if times is None or len(times) != len(block['turns']):
        raise ValueError('rendering requires resolved turn_times (demo.data.load_blocks)')
    lines = [f'<block {attrs}>']
    previous_end = None
    for utt, text, timing in zip(block['utts'], block['turns'], times):
        gap = 0 if previous_end is None else timing['start'] - previous_end
        previous_end = timing['end']
        sign = '+' if gap >= 0 else ''
        tag = by_utt.get(utt, {})
        speaker = tag.get('speaker', '?')
        if speaker != '?' and speaker not in tuple('ABCDEF'):
            raise ValueError('invalid acoustic speaker')
        before = f'[화자{speaker}] ' if acoustic else ''
        after = ''.join(f' ({label})' for event, label in MARKERS
                        if tag.get('events', {}).get(event, 0) >= threshold)
        # Prevent ASR text from injecting block delimiters into the data envelope.
        lines.append(f'[{sign}{_number(gap)}] {before}{escape(text)}{after}')
    return '\n'.join([*lines, '</block>'])


def render_text_only(block):
    return _render(block)


def render_with_acoustic(block, acoustic):
    return _render(block, acoustic)
