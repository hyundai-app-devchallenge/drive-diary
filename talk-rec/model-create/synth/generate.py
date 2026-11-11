"""Resumable four-card synthesis with existing labeling backends."""
import argparse
from copy import deepcopy
from concurrent.futures import ThreadPoolExecutor
import json
import math
from pathlib import Path
import random
import re
import sys
from threading import Lock
import time

from pipeline.label import (AGY_COMMAND_LIMIT, AgyBackend, BackendError, CommandBackend,
                            FakeBackend, UsageLimit, agy_arguments, agy_prompt,
                            windows_command_length)
from synth.io import ROOT, digest, output_lock, read_jsonl, write_json, write_jsonl
from synth.patterns import BY_ID, DEFINITIONS, LABELS

BANNED = ('삼성', '애플', '유튜브', '카카오', '현대자동차', '테슬라', '아이폰', '갤럭시',
          '포켓몬', '마인크래프트', '손흥민', '아이유', '이순신', '트럼프')
JSON_ONLY = '도구나 파일을 쓰지 말고 JSON 배열만 답한다.\n'


class BatchFailure(ValueError):
    """Only this batch exhausted its request budget; other batches can continue."""


class FailureCounts:
    def __init__(self):
        self.counts = dict(validation_errors=0, backend_errors=0)
        self.reasons = {}
        self.lock = Lock()

    def record(self, category):
        with self.lock:
            self.counts[category] += 1
            print(f'검증 실패 {self.counts["validation_errors"]}, '
                  f'백엔드 오류 {self.counts["backend_errors"]}', file=sys.stderr, flush=True)

    def snapshot(self):
        with self.lock:
            return dict(self.counts)

    def reason(self, category):
        with self.lock:
            self.reasons[category] = self.reasons.get(category, 0) + 1

    def reason_snapshot(self):
        with self.lock:
            return dict(sorted(self.reasons.items()))


def load_examples():
    return [example for path in sorted((ROOT / 'prompts/synth-examples').glob('*.json'))
            for example in json.loads(path.read_text(encoding='utf-8'))]


def length_band(card):
    if card['seconds'] >= 60 and card['n_turns'] >= 18:
        return 'long'
    if card['seconds'] <= 30 and card['n_turns'] <= 8:
        return 'short'
    return 'medium'


def select_examples(cards, seed=43, count=3):
    pool = load_examples()
    rng = random.Random(f'{seed}:' + ','.join(str(c['card']) for c in cards))
    rng.shuffle(pool)
    labels = sorted({c['label'] for c in cards})
    selected = []
    # Cover every batch label; prioritize the length/label combinations occurring
    # most often. Fill remaining slots with uncovered combinations before extras.
    needs = sorted({(c['label'], length_band(c)) for c in cards},
                   key=lambda key: (-sum((c['label'], length_band(c)) == key for c in cards), key))
    def pick(label, band):
        same = [e for e in pool if e['card']['label'] == label and e not in selected]
        return next((e for e in same if length_band(e['card']) == band), same[0] if same else None)
    for label in labels:
        band = next(band for value, band in needs if value == label)
        selected.append(pick(label, band))
    for label, band in needs:
        if len(selected) >= max(count, len(labels)):
            break
        if not any(e['card']['label'] == label and length_band(e['card']) == band for e in selected):
            example = pick(label, band)
            if example is not None:
                selected.append(example)
    for example in pool:
        if len(selected) >= max(count, len(labels)):
            break
        if example not in selected:
            selected.append(example)
    return selected


def render_prompt(cards, template=None, seed=43, example_count=3, agy_context=None):
    template = template if template is not None else (ROOT / 'prompts/synth-scene.md').read_text(encoding='utf-8')
    system = template.replace('{{DEFINITIONS}}', '\n'.join(f'- {k}: {v}' for k, v in DEFINITIONS.items()))
    system = system.replace('{{PATTERNS}}', '\n'.join(f"- {p}: {v['description']}" for p, v in BY_ID.items()))
    user = json.dumps(cards, ensure_ascii=False)
    # Hash the entire pool, including examples not selected in this batch, so
    # editing any example invalidates a resume signature.
    system += '\n예시 묶음 식별자: ' + digest(load_examples()) + f'\n예시 선택 시드: {seed}\n'
    context = agy_context or ('agy.exe', 'gemini-3.8-flash-medium')
    for count in range(example_count, 1, -1):
        examples = select_examples(cards, seed, count)
        rendered = system.replace('{{EXAMPLES}}', json.dumps(examples, ensure_ascii=False, separators=(',', ':')))
        argv = agy_arguments(context[0], context[1], 'medium', agy_prompt(rendered, user))
        if windows_command_length(argv) <= AGY_COMMAND_LIMIT:
            return rendered, user
    raise ValueError('prompt exceeds agy command budget; reduce batch size')


def validate_cards(cards):
    if not cards:
        raise ValueError('cards must not be empty')
    seen = set()
    for c in cards:
        if type(c.get('card')) is not int or c['card'] < 1 or c['card'] in seen:
            raise ValueError('invalid/duplicate card number')
        seen.add(c['card'])
        if c.get('label') not in LABELS or c.get('pattern') not in BY_ID or BY_ID[c['pattern']]['label'] != c['label']:
            raise ValueError('invalid label/pattern')
        if type(c.get('boundary')) is not bool or type(c.get('n_turns')) is not int or not 1 <= c['n_turns'] <= 26:
            raise ValueError('invalid card targets')
        if type(c.get('seconds')) not in (int, float) or not math.isfinite(c['seconds']) or not 15 <= c['seconds'] <= 90:
            raise ValueError('invalid card seconds')
        members = c.get('family', [])
        if not 2 <= len(members) <= 5 or len({m['spk'] for m in members}) != len(members):
            raise ValueError('invalid family')
        if sum(m['role'] == '운전자' for m in members) != 1:
            raise ValueError('family needs one driver')


def grams(scene):
    text = re.sub(r'\s+', '', ''.join(line['text'] for line in scene['lines']))
    return {text[i:i+4] for i in range(max(1, len(text)-3))}


def similarity(a, b):
    x, y = grams(a), grams(b)
    return len(x & y) / len(x | y) if x | y else 1.0


def validate_scenes(items, cards, existing=(), threshold=.85):
    if not isinstance(items, list) or len(items) != len(cards):
        raise ValueError('scene count mismatch')
    checked = list(existing) + load_examples()
    for item, card in zip(items, cards):
        if not isinstance(item, dict) or set(item) != {'card', 'lines', 'why'} or type(item['card']) is not int or item['card'] != card['card']:
            raise ValueError('scene fields/card mismatch')
        if not isinstance(item['why'], str) or not item['why'].strip():
            raise ValueError('why missing')
        lines = item['lines']
        if not isinstance(lines, list) or not .6 * card['n_turns'] <= len(lines) <= 1.4 * card['n_turns']:
            raise ValueError('line count outside 40%')
        previous = -1
        for line in lines:
            if not isinstance(line, dict) or set(line) != {'t', 'dur', 'spk', 'text', 'overlap'}:
                raise ValueError('line fields mismatch')
            for field in ('t', 'dur'):
                if type(line[field]) not in (int, float) or not math.isfinite(line[field]):
                    raise ValueError('invalid time')
            if line['t'] < 0 or line['t'] < previous or line['dur'] <= 0:
                raise ValueError('invalid time order/duration')
            previous = line['t']
            allowed = {m['spk'] for m in card['family']} | {'내비'}
            if card['pattern'] == 'phone_work':
                allowed.add('통화상대')
            if not isinstance(line['spk'], str) or line['spk'] not in allowed:
                raise ValueError('speaker outside family')
            if type(line['overlap']) is not bool or not isinstance(line['text'], str) or not line['text'].strip():
                raise ValueError('invalid line content')
        if lines[0]['t'] != 0:
            raise ValueError('scene must start at zero')
        span = max(line['t'] + line['dur'] for line in lines)
        if not .5 * card['seconds'] <= span <= 1.5 * card['seconds']:
            raise ValueError('duration outside 50%')
        if any(word.casefold() in json.dumps(item, ensure_ascii=False).casefold() for word in BANNED):
            raise ValueError('banned name/brand')
        if any(similarity(item, old) >= threshold for old in checked):
            raise ValueError('duplicate scene (character 4-gram)')
        checked.append(item)
    return items


def parse_response(text):
    text = text.strip()
    if text.startswith('```') and text.endswith('```'):
        text = re.sub(r'^```(?:json)?\s*', '', text)[:-3].strip()
    return json.loads(text)


# Fixed categories only: exception messages and generated speech never enter metadata.
VALIDATION_REASONS = {
    message: message.replace(' ', '_').replace('/', '_').replace('(', '').replace(')', '').replace('%', 'pct')
    for message in ('scene fields/card mismatch', 'why missing', 'line count outside 40%',
                    'line fields mismatch', 'invalid time', 'invalid time order/duration',
                    'speaker outside family', 'invalid line content', 'scene must start at zero',
                    'duration outside 50%', 'banned name/brand', 'duplicate scene (character 4-gram)')
}


def stretch_duration(scene, card):
    """Expand valid compressed timing to the target, without changing speech text.

    Scale both t and dur so ordering, silence and overlaps retain their ratios.
    Malformed time structures still go through the ordinary rejection checks.
    """
    lines = scene.get('lines')
    if not isinstance(lines, list) or not lines:
        return scene
    previous = -1
    for line in lines:
        if not isinstance(line, dict) or any(type(line.get(k)) not in (int, float)
                or not math.isfinite(line[k]) for k in ('t', 'dur')):
            return scene
        if line['t'] < 0 or line['t'] < previous or line['dur'] <= 0:
            return scene
        previous = line['t']
    span = max(line['t'] + line['dur'] for line in lines)
    if lines[0]['t'] != 0 or span >= card['seconds']:
        return scene
    result = deepcopy(scene)
    for line in result['lines']:
        line['t'] *= card['seconds'] / span
        line['dur'] *= card['seconds'] / span
    return result


def checked_subset(items, cards, existing=(), initial=(), partial=False, stretch_short=True):
    """Keep valid cards even when other cards in the response fail validation.

    Record the first failed check per card, plus envelope failures. Previously
    accepted cards survive retries; they are not compared against themselves.
    """
    accepted = {s['card']: s for s in initial}
    reasons = []
    if not isinstance(items, list):
        return list(accepted.values()), ['response_not_list']
    if not partial and len(items) != len(cards):
        reasons.append('scene_count_mismatch')
    expected = {c['card'] for c in cards}
    if any(not isinstance(s, dict) or type(s.get('card')) is not int or s['card'] not in expected for s in items):
        reasons.append('unexpected_scene_identity')
    for card in cards:
        number = card['card']
        if number in accepted:
            continue
        candidates = [s for s in items if isinstance(s, dict) and type(s.get('card')) is int and s['card'] == number]
        if len(candidates) != 1:
            if candidates or not partial:
                reasons.append('scene_missing_or_duplicate_identity')
            continue
        if stretch_short:
            candidates = [stretch_duration(candidates[0], card)]
        try:
            validate_scenes(candidates, [card], list(existing) + list(accepted.values()))
        except (ValueError, TypeError, KeyError, IndexError) as error:
            reasons.append(VALIDATION_REASONS.get(str(error), 'invalid_scene_structure'))
        else:
            accepted[number] = candidates[0]
    return [accepted[c['card']] for c in cards if c['card'] in accepted], reasons


def record_validation(failures, reasons):
    failures.record('validation_errors')
    for reason in reasons:
        failures.reason(reason)


def fake_scene(card):
    """Format fixture only; deliberately unsuitable for judging realism."""
    rng = random.Random(card['card'])
    words = ['여기', '잠깐', '그거', '아까', '다음에', '왜', '아니', '아직', '이쪽', '음',
             '맞아', '봤어', '그래', '알았어', '지금', '아마', '근데', '그러면', '저쪽', '이거',
             '생각나', '또', '조금', '나도', '글쎄', '있어', '했어', '가만', '그랬나', '저기']
    n, seconds = card['n_turns'], card['seconds']
    step = seconds / n
    lines = []
    for i in range(n):
        text = card['topic'] + ' ' + ' '.join(rng.sample(words, 8))
        t = round(i * step, 4)
        dur = round((seconds if i == n-1 else (i + .8) * step) - t, 4)
        lines.append(dict(t=t, dur=dur, spk=card['family'][i % len(card['family'])]['spk'], text=text, overlap=False))
    return dict(card=card['card'], lines=lines, why='가짜 백엔드의 형식 검증용 장면')


class SynthFakeBackend(FakeBackend):
    def complete(self, system_prompt, user_message):
        self.responses = iter([json.dumps([fake_scene(c) for c in json.loads(user_message)], ensure_ascii=False)])
        return super().complete(system_prompt, user_message)


def request_batch(cards, factory, existing, wait_minutes=0, attempts=3, failures=None, seed=43, initial=(), stretch_short=True):
    failures = failures if failures is not None else FailureCounts()
    accepted = list(initial)
    for attempt in range(attempts):
        backend_failed = False
        while True:
            try:
                backend = factory()
                context = (backend.executable or 'agy.exe', backend.model) if isinstance(backend, AgyBackend) else None
                system, user = render_prompt(cards, seed=seed, agy_context=context)
                response = backend.complete(system, user)
                break
            except UsageLimit:
                if wait_minutes <= 0:
                    raise
                print('사용량 제한: 지정한 분 후 같은 카드 호출을 재개합니다.', file=sys.stderr, flush=True)
                remaining = wait_minutes * 60
                while remaining > 0:
                    delay = min(60, remaining)
                    time.sleep(delay)
                    remaining -= delay
            except BackendError:
                failures.record('backend_errors')
                failures.reason('backend_error')
                backend_failed = True
                break
        if backend_failed:
            continue
        try:
            items = parse_response(response)
        except (ValueError, TypeError, KeyError):
            record_validation(failures, ['invalid_json'])
            continue
        accepted, reasons = checked_subset(items, cards, existing, accepted, stretch_short=stretch_short)
        if reasons:
            record_validation(failures, reasons)
        if len(accepted) == len(cards):
            return accepted, attempt + 1
        # Retry the same request but retain every already validated card.
    if accepted:
        return accepted, attempts
    raise BatchFailure('batch failed after request budget exhausted') from None


def generate(cards, out, factory, signature, concurrency=1, wait_minutes=0, seed=43, stretch_short=True):
    validate_cards(cards)
    if concurrency < 1 or not math.isfinite(wait_minutes) or wait_minutes < 0:
        raise ValueError('invalid concurrency/wait')
    out = Path(out)
    meta = out.with_suffix('.meta.json')
    signature = dict(signature, cards_hash=digest(cards), prompt_hash=digest(render_prompt([], seed=seed)[0]), version=1)
    signature['stretch_short'] = stretch_short
    with output_lock(out):
        if out.exists() and not meta.exists():
            raise ValueError('existing scenes have no resume metadata')
        saved = json.loads(meta.read_text(encoding='utf-8')) if meta.exists() else {}
        saved_signature = {k: v for k, v in saved.items() if k not in ('failed_cards', 'failure_counts', 'failure_reasons')}
        if stretch_short and 'stretch_short' not in saved_signature:
            saved_signature['stretch_short'] = True
        if meta.exists() and saved_signature != signature:
            # T43 outputs predate the JSON-only guard. Accept exactly that old
            # prompt, with all other resume fields still matching.
            prompt = render_prompt([], seed=seed)[0]
            legacy = dict(signature, prompt_hash=digest(prompt.removeprefix(JSON_ONLY)))
            if not prompt.startswith(JSON_ONLY) or saved_signature != legacy:
                raise ValueError('resume configuration changed; choose a new output')
        old = read_jsonl(out) if out.exists() else []
        card_by_id = {c['card']: c for c in cards}
        if len({s['card'] for s in old}) != len(old) or any(s['card'] not in card_by_id for s in old):
            raise ValueError('invalid saved card identities')
        validate_scenes(old, [card_by_id[s['card']] for s in old]) if old else None
        done = {s['card'] for s in old}
        context = None
        if signature.get('backend') == 'agy':
            backend = factory()
            context = (backend.executable or 'agy.exe', backend.model)
        batches, offset = [], 0
        while offset < len(cards):
            size = min(4, len(cards)-offset)
            while True:
                batch = cards[offset:offset+size]
                try:
                    render_prompt(batch, seed=seed, agy_context=context)
                    break
                except ValueError:
                    if size == 1:
                        raise
                    size -= 1
            batches.append(batch)
            offset += size
        pending = [[c for c in b if c['card'] not in done] for b in batches]
        pending = [b for b in pending if b]
        failed = set(saved.get('failed_cards', [])) - done
        failures = FailureCounts()
        def save_meta():
            write_json(meta, dict(signature, failed_cards=sorted(failed), failure_counts=failures.snapshot(),
                                 failure_reasons=failures.reason_snapshot()))
        save_meta()
        # Small waves bound in-flight calls on interruption. Commit in card order.
        try:
            with ThreadPoolExecutor(max_workers=concurrency) as pool:
                for start in range(0, len(pending), concurrency):
                    wave = pending[start:start+concurrency]
                    futures = [pool.submit(request_batch, b, factory, tuple(old), wait_minutes,
                                           failures=failures, seed=seed, stretch_short=stretch_short) for b in wave]
                    for batch, future in zip(wave, futures):
                        try:
                            result, used = future.result()
                            result, reasons = checked_subset(result, batch, old, partial=True, stretch_short=stretch_short)
                            if reasons:
                                record_validation(failures, reasons)
                                if used >= 3 and not result:
                                    raise BatchFailure('cross-batch validation budget exhausted') from None
                                if used < 3:
                                    result, _ = request_batch(batch, factory, old, wait_minutes,
                                                              3-used, failures, seed, initial=result, stretch_short=stretch_short)
                        except BatchFailure:
                            failed.update(c['card'] for c in batch)
                            save_meta()
                            continue
                        old.extend(result)
                        old.sort(key=lambda s: s['card'])
                        write_jsonl(out, old)
                        failed.update(c['card'] for c in batch)
                        failed.difference_update(s['card'] for s in result)
                        save_meta()
                        print(f'{len(old)}/{len(cards)} 장면 저장', flush=True)
        finally:
            save_meta()
        print('실패한 카드 번호: ' + json.dumps(sorted(failed)), flush=True)
        return old


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--cards', required=True)
    p.add_argument('--backend', choices=['agy', 'fake', 'command'], required=True)
    p.add_argument('--model', default='gemini-3.8-flash-medium')
    p.add_argument('--command', default='')
    p.add_argument('--agy-path')
    p.add_argument('--timeout', type=float, default=600)
    p.add_argument('--out', default='.local/synth/demo/scenes.jsonl')
    p.add_argument('--wait-on-limit', type=float, default=0, metavar='분')
    p.add_argument('--concurrency', type=int, default=1)
    p.add_argument('--seed', type=int, default=43, help='example selection seed')
    p.add_argument('--no-stretch-short', action='store_true', help='disable expansion of compressed scene times')
    args = p.parse_args(argv)
    if args.backend == 'fake' and 'demo' in [s.casefold() for s in Path(args.out).resolve().parts]:
        p.error('가짜 결과는 demo 경로에 저장할 수 없습니다. .local/synth/fake/를 사용하세요.')
    def factory():
        if args.backend == 'agy':
            return AgyBackend(args.model, 'medium', args.timeout, args.agy_path)
        if args.backend == 'command':
            return CommandBackend(args.command, args.model, 'medium', args.timeout)
        return SynthFakeBackend()
    try:
        signature = dict(backend=args.backend, model=args.model, command_hash=digest(args.command),
                         agy_path_hash=digest(args.agy_path))
        cards = read_jsonl(args.cards)
        scenes = generate(cards, args.out, factory, signature, args.concurrency, args.wait_on_limit, args.seed,
                          stretch_short=not args.no_stretch_short)
        return 0 if len(scenes) == len(cards) else 4
    except UsageLimit:
        print('사용량 제한. 같은 명령으로 재개하거나 --wait-on-limit 분을 지정하세요.', file=sys.stderr)
        return 3
    except (OSError, ValueError, BackendError):
        print('생성 실패: 입력·응답·재개 설정·백엔드를 확인하세요. 완료 호출은 보존되었습니다.', file=sys.stderr)
        return 2
    except KeyboardInterrupt:
        print('중단했습니다. 같은 명령으로 재개하세요.', file=sys.stderr)
        return 130


if __name__ == '__main__':
    raise SystemExit(main())
