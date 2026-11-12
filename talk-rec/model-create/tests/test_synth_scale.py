"""T53 scale/operations tests: every utterance here is invented."""
from collections import Counter, defaultdict
from copy import deepcopy
import json

import pytest

from demo.trial.data import Example, LABELS, load_synth, split_scenes
from pipeline.label import UsageLimit
from synth.cards import sample_cards, main as cards_main
from synth.curate import deduplicate, export_sample, main as curate_main, sample_ids
from synth.degrade import DEFAULT_PROFILE, export, level_assignment, main as degrade_main
from synth.generate import (SceneIndex, SynthFakeBackend, fake_scene, generate, grams,
                            select_examples, similarity, validate_cards, validate_scenes)
from synth.io import read_jsonl, write_json, write_jsonl
from synth.patterns import NEGATIVE_PATTERNS, SHARED_TOPICS
from synth.shortcut import cross_validate, card_shapes, main as shortcut_main, ngram_audit


def pair_groups(cards):
    groups = defaultdict(list)
    for c in cards:
        if c.get('pair_id'):
            groups[c['pair_id']].append(c)
    return groups


def test_3000_cards_gate_topics_pairs_and_negative(tmp_path, capsys):
    path = tmp_path / 'cards.jsonl'
    assert cards_main(['--n', '3000', '--seed', '53', '--out', str(path)]) == 0
    cards = read_jsonl(path)
    validate_cards(cards)
    assert len(cards) == 3000
    assert Counter(c['label'] for c in cards) == {'story': 1200, 'moment': 600, 'none': 1200}
    assert max(Counter((c['pattern'], c['topic']) for c in cards).values()) <= 2
    topics = {label: {c['topic'] for c in cards if c['label'] == label} for label in LABELS}
    assert topics['none'] == topics['story'] == set(SHARED_TOPICS)
    assert len(topics['moment']) >= 300
    for label in LABELS:
        groups = Counter(c['topic_group'] for c in cards if c['label'] == label)
        assert len(groups) == 6
        assert max(groups.values()) / min(groups.values()) < 1.3
    pairs = pair_groups(cards)
    assert len(pairs) == 450
    paired_negative = set()
    for rows in pairs.values():
        assert len(rows) == 2 and sum(r['label'] == 'none' for r in rows) == 1
        for field in ('family', 'topic', 'situation', 'seconds', 'n_turns', 'energy', 'style'):
            assert rows[0][field] == rows[1][field]
        paired_negative.update(c['pattern'] for c in rows if c['negative'])
    assert paired_negative == NEGATIVE_PATTERNS
    for c in cards:
        assert c['negative'] == (c['pattern'] in NEGATIVE_PATTERNS)
        if c['negative']:
            assert c['label'] == 'none' and c['n_turns'] >= 5
    examples, values, _ = card_shapes(path)
    assert not cross_validate(examples, values)['fail']
    for seed in (43, 44, 45, 46, 47):
        a, b = split_scenes(examples, seed)
        assert not {r.scene for r in a} & {r.scene for r in b}
        assert not {r.pair_id for r in a if r.pair_id} & {r.pair_id for r in b if r.pair_id}
    assert shortcut_main(['--cards', str(path), '--topic-report', str(tmp_path/'topics.json')]) == 0
    topics_report = json.loads((tmp_path/'topics.json').read_text(encoding='utf-8'))
    assert len(topics_report['topics']) == 360
    assert all(r['counts']['none'] and r['counts']['story'] for r in topics_report['topics'])
    output = capsys.readouterr().out
    assert 'PASS' in output and 'shared_keep_none_topics=360' in output
    assert 'negative=' in output and 'paired_cards=900' in output


@pytest.mark.parametrize('rate', [0, .15, .5, 1])
def test_childless_option_and_ages(rate):
    cards = sample_cards(400, childless_rate=rate)
    for label in LABELS:
        rows = [c for c in cards if c['label'] == label]
        assert sum(not any(m['spk'].startswith('아이') for m in c['family']) for c in rows) == round(len(rows)*rate)
    if rate == 0:
        ages = Counter(m['age'] for c in cards for m in c['family'] if m['spk'].startswith('아이'))
        assert ages['아기(말 시작 전후)'] > ages['중고생']


@pytest.mark.parametrize('rate', [0, .3, .6])
def test_pair_rate(rate):
    cards = sample_cards(400, pair_rate=rate)
    assert sum(bool(c.get('pair_id')) for c in cards) == round(400*rate)


@pytest.mark.parametrize('kwargs', [dict(pair_rate=-.1), dict(pair_rate=1.1),
                                  dict(childless_rate=-1), dict(childless_rate=float('nan'))])
def test_invalid_card_options(kwargs):
    with pytest.raises(ValueError):
        sample_cards(**kwargs)


def test_level_mix_labels_pairs_multiple_exports_and_partial_stability(tmp_path):
    cards = sample_cards(100)
    scenes = [fake_scene(c) for c in cards]
    assignment = level_assignment(cards, 'mixed', 53, [.2, .5, .3])
    assert Counter(assignment.values()) == {0: 20, 1: 50, 2: 30}
    assert assignment == level_assignment(cards[::-1], 'mixed', 53, [.2, .5, .3])
    export(cards, scenes, tmp_path/'a', 'mixed', 53, profile=DEFAULT_PROFILE, level_mix=[.2,.5,.3])
    export(cards, scenes, tmp_path/'b', 2, 53, profile=DEFAULT_PROFILE)
    labels = read_jsonl(tmp_path/'a/labels.jsonl')
    assert all((l['level'], l['negative'], l['pair_id']) ==
               (assignment[c['card']], c['negative'], c.get('pair_id')) for c,l in zip(cards,labels))
    examples, _ = load_synth([tmp_path/'a', tmp_path/'b'])
    for seed in range(43, 48):
        a, b = split_scenes(examples, seed)
        assert not {r.scene for r in a} & {r.scene for r in b}
        assert not {r.pair_id for r in a if r.pair_id} & {r.pair_id for r in b if r.pair_id}
    export(cards, scenes[::3], tmp_path/'partial', 'mixed', 53,
           profile=DEFAULT_PROFILE, level_mix=[.2,.5,.3])
    assert all(l['level'] == assignment[int(l['id'].split(':')[1].split('@')[0])]
               for l in read_jsonl(tmp_path/'partial/labels.jsonl'))
    write_jsonl(tmp_path/'cards.jsonl', cards)
    write_jsonl(tmp_path/'scenes.jsonl', scenes)
    assert degrade_main(['--cards', str(tmp_path/'cards.jsonl'), '--scenes', str(tmp_path/'scenes.jsonl'),
                         '--out-dir', str(tmp_path/'cli'), '--level-mix', '.2,.5,.3']) == 0


@pytest.mark.parametrize('mix', [[0,0,0], [-1,1,1], [float('nan'),1,1], [1,2], [1,float('inf'),1]])
def test_bad_level_mix(mix):
    with pytest.raises(ValueError):
        level_assignment(sample_cards(10), 'mixed', level_mix=mix)


def test_ngram_warning_uses_unique_scenes_and_same_group_splits():
    examples = []
    for i in range(90):
        label = i % 3
        phrase = ['빨간우산 절차 확인', '파란새 추억 이야기', '노란꽃 엉뚱한 노래'][label]
        pair = f'p{i//3}' if label in (0,1) else None
        examples.extend([Example(f'{level}/{i}', i, phrase, label, pair) for level in range(2)])
    result = ngram_audit(examples, top=5)
    assert result['cv_accuracy'] == 1
    assert len(result['fold_accuracy']) == 5
    assert all(any(r['warning'] for r in g['rows']) for g in result['tops'])
    assert all(max(r['support']) <= 30 for g in result['tops'] for r in g['rows'])
    assert not any(r['warning'] for g in ngram_audit(examples, 5, min_support=31)['tops'] for r in g['rows'])


def test_ngram_cli_never_prints_real_text(tmp_path, capsys):
    blocks, labels = [], []
    for i in range(60):
        label = LABELS[i%3]
        phrase = ['합성우산 확인절차', '합성바다 기억나네', '합성구름 노래하자'][i%3]
        blocks.append(dict(id=f'synth:{i+1}@0',source=f'synth:{i+1}',turns=[phrase],start=0,end=20))
        labels.append(dict(id=blocks[-1]['id'],verdict=label))
    write_jsonl(tmp_path/'blocks.jsonl', blocks)
    write_jsonl(tmp_path/'labels.jsonl', labels)
    write_json(tmp_path/'judge.json',dict(ids=['secret-id'],human={'secret-id':'none'}))
    write_jsonl(tmp_path/'real.jsonl',[dict(id='secret-id',config='pleos',lines=[dict(start=0,end=30,text='실제역할입력전용문자열')])])
    assert shortcut_main(['--synth-dir',str(tmp_path),'--real-judge',str(tmp_path/'judge.json'),
                          '--real-asr',str(tmp_path/'real.jsonl'),'--top-ngrams','4']) == 0
    output = capsys.readouterr().out
    assert 'ngram CV accuracy=1.0000' in output and 'WARN exclusive' in output
    assert '실제역할' not in output and '입력전용' not in output and 'secret-id' not in output


def test_normalized_dedup_threshold_and_cli(tmp_path):
    scene = dict(card=1,lines=[dict(text='ＡＢＣ 가상의 우산을 여기 놓았어')])
    duplicate = dict(card=2,lines=[dict(text='abc, 가상의   우산을 여기 놓았어!!!')])
    near = dict(card=3,lines=[dict(text='abc 가상의 우산을 저기 놓았어')])
    other = dict(card=4,lines=[dict(text='동그란 구름이 나무 뒤로 흘러갔지')])
    assert similarity(scene,duplicate) == 1
    assert .3 < similarity(scene,near) < 1
    rows = [scene,duplicate,near,other]
    original = deepcopy(rows)
    kept, dropped = deduplicate(rows, .3)
    assert [r['card'] for r in kept] == [1,4] and dropped == [2,3]
    assert rows == original
    assert len(deduplicate(rows, 1)[0]) == 3
    index = SceneIndex([scene])
    for threshold in (.3,.85,1):
        assert index.duplicate(near,threshold) == (similarity(scene,near) >= threshold)
    write_jsonl(tmp_path/'scenes.jsonl', rows)
    assert curate_main(['dedup','--scenes',str(tmp_path/'scenes.jsonl'),'--out',str(tmp_path/'unique.jsonl'),
                        '--threshold','.3']) == 0
    assert read_jsonl(tmp_path/'unique.jsonl') == kept


def test_duplicate_index_matches_bruteforce_for_invented_random_text():
    import random
    rng = random.Random(53)
    scenes = [dict(card=i, lines=[dict(text=''.join(rng.choices('abcdefghi', k=rng.randrange(8,70))))])
              for i in range(80)]
    for threshold in (.1, .3, .5, .85, 1):
        index = SceneIndex(scenes[:40])
        for scene in scenes[20:]:
            assert index.duplicate(scene,threshold) == any(similarity(scene,old) >= threshold for old in scenes[:40])


def test_failure_reason_table_and_negative_report(tmp_path, capsys):
    from pipeline.label import FakeBackend
    from synth.report import metrics, build_report
    cards = sample_cards(4)
    assert generate(cards,tmp_path/'failed.jsonl',lambda:FakeBackend(['invalid json']*3),{}) == []
    metadata = json.loads((tmp_path/'failed.meta.json').read_text(encoding='utf-8'))
    assert metadata['failure_reasons'] == {'invalid_json':3}
    assert '| invalid_json | 3 |' in capsys.readouterr().out
    labels = {'invented':dict(verdict='none',pattern='argument',negative=True,pair_id='pair',level=1)}
    reviews = [dict(id='invented',real='yes',verdict='none')]
    stats = metrics(reviews,labels)
    assert stats['groups']['negative']['True']['n'] == 1
    assert stats['groups']['paired']['True']['n'] == 1
    assert '| 부정 | True | 1 |' in build_report(reviews,labels,[],[])


def test_stratified_sample_whole_pairs_and_skim_compatibility(tmp_path):
    from tools.skim.serve import SkimStore
    cards = sample_cards(200)
    export(cards,[fake_scene(c) for c in cards],tmp_path/'source',profile=DEFAULT_PROFILE)
    labels = read_jsonl(tmp_path/'source/labels.jsonl')
    ids = sample_ids(labels, 60, 53)
    assert ids == sample_ids(labels,60,53)
    assert 60 <= len(ids) <= 61
    selected = [r for r in labels if r['id'] in ids]
    assert {r['verdict'] for r in selected} == set(LABELS)
    assert {r['negative'] for r in selected} == {True, False}
    assert {bool(r.get('pair_id')) for r in selected} == {True, False}
    for pair in {r.get('pair_id') for r in selected} - {None}:
        assert {r['id'] for r in labels if r.get('pair_id') == pair} <= set(ids)
    assert len({r['pattern'] for r in selected}) >= 20
    assert export_sample(tmp_path/'source',tmp_path/'sample',60,53) == len(ids)
    store = SkimStore(tmp_path/'sample','t53',limit=1000)
    assert set(store.ids) == set(ids)
    assert 'verdict' not in store.item(0)
    assert curate_main(['sample','--synth-dir',str(tmp_path/'source'),'--out-dir',str(tmp_path/'cli'),
                        '--limit','60','--seed','53']) == 0


def test_large_resume_skips_completed_calls(tmp_path, capsys):
    # 3,000 saved scenes + a small tail exercises actual bulk resume validation.
    # Avoid an expensive 750-checkpoint fake run: write a valid signed checkpoint
    # using generate itself, then expand its cards hash and saved fixture only.
    from synth.generate import render_prompt
    from synth.io import digest
    cards = sample_cards(3000, seed=53)
    scenes = [fake_scene(c) for c in cards]
    out = tmp_path/'scenes.jsonl'
    signature = dict(backend='fake',cards_hash=digest(cards),prompt_hash=digest(render_prompt([])[0]),
                     version=1,stretch_short=True)
    write_json(out.with_suffix('.meta.json'),signature)
    write_jsonl(out,scenes[:-4])
    backend = SynthFakeBackend()
    assert len(generate(cards,out,lambda:backend,dict(backend='fake'),concurrency=2)) == 3000
    assert len(backend.calls) == 1
    assert len(generate(cards,out,lambda:backend,dict(backend='fake'))) == 3000
    assert len(backend.calls) == 1
    assert '| 실패 사유 (이번 실행) |' in capsys.readouterr().out
    with pytest.raises(ValueError, match='configuration'):
        generate(cards,out,lambda:backend,dict(backend='fake'),duplicate_threshold=.9)


def test_negative_example_selection_and_baby_prompt():
    from synth.generate import render_prompt
    cards = sample_cards(200)
    negative = next(c for c in cards if c['negative'])
    assert any(e['card'].get('negative') for e in select_examples([negative]))
    prompt, _ = render_prompt([negative])
    assert '아기(말 시작 전후)' in prompt and 'pair_id' in prompt and '현재 분위기' in prompt
