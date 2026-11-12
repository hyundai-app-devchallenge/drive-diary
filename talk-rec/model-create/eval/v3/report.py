"""Korean numeric-only reports; no transcript or human comment fields."""
from .metrics import KEYS

TITLES = ('keep 재현율', 'none 걸러 내기', '정밀도', '정확도', 'AUROC', '분/시간', '3분류 정확도')


def cell(value, ci=True, ci_key='cluster_ci95'):
    if value['mean'] is None:
        return '—'
    result = f"{value['mean']:.3f} ± {value['std']:.3f}"
    interval = value.get(ci_key, {})
    if ci and interval.get('low') is not None:
        result += f" [{interval['low']:.3f}, {interval['high']:.3f}]"
    return result


def table(results, *, scope='child_family', mode='dev', unsure='included', size=None, ci_key='cluster_ci95'):
    size = size or str(results['full_size'])
    lines = ['| 모델 | ' + ' | '.join(TITLES) + ' |', '|---|' + '---:|' * len(KEYS)]
    for name in ('M0', 'M1', 'all_keep', 'gemini'):
        if name in ('M0', 'M1'):
            if name not in results['models']:
                continue
            entry = results['models'][name][str(size)][mode][scope][unsure]
        else:
            entry = results['baselines'][name][scope][unsure]
        lines.append('| ' + name + ' | ' + ' | '.join(cell(entry['metrics'][k], ci_key=ci_key) for k in KEYS) + ' |')
    return lines


def report(results):
    s = results['settings']
    counts = results['real_counts']
    first_model = next(iter(results['models']))
    main = results['models'][first_model][str(results['full_size'])]['dev']['child_family']['included']
    lines = ['# T55 학습·평가 v3', '',
             f"실행: `{results['command']}`", '',
             f"합성 {results['full_size']}장. child_family dev {main['n']}묶음·{main['sources']}영상, "
             f"keep {main['keep']}묶음, unsure {main['unsure']}묶음. unknown cast {counts['unknown_cast_blocks']}묶음 제외.",
             f"시드 {s['seeds']}; 부분표본 시드 {s['subset_seed']}; train/val 분할 시드 {s['split_seed']}.",
             '기본 표는 시드 평균 ± 표본 SD [출처 단위 cluster bootstrap 95% 구간]. 표본이 작아 구간이 넓다.',
             f"bootstrap {s['bootstrap']}회, 시드 55. 묶음 단위 구간은 results.json의 block_ci95에도 있다.",
             '구간은 학습된 모델과 원래 dev/LOSO 문턱을 고정한 조건부 구간이다. 재학습·문턱 재추정 불확실성을 포함하지 않는다.',
             '', '## 주 결과: child_family dev, unsure 포함', '',
             '문턱: child_family dev keep 재현율 ≥0.85 중 특이도 최대. 동률이면 재현율, 0.5에 가까움, 낮은 값 순.',
             '조건을 만족하지 못하면 재현율 최대, 이어서 특이도 최대. 해당 클래스가 없으면 그 지표는 —.', '']
    lines += table(results)
    lines += ['', '## 영상별 leave-one-source-out', '',
              '각 영상의 문턱은 그 영상을 뺀 child_family dev에서만 선택한다. 확률 AUROC·보조 head는 문턱과 독립이다.', '']
    lines += table(results, mode='loso')
    lines += ['', results['selection']['rule_result'], '',
              '탑재 후보는 미리 정한 seed=43의 전체 규모 모델이다. 최종 문턱은 전체 child_family dev로 고정했으며 candidate.json에 저장했다.',
              '', '| 모델 | 규모 | seed 43 문턱 | seed 44 문턱 | seed 45 문턱 |', '|---|---:|---:|---:|---:|']
    for name, sizes in results['models'].items():
        for size, entry in sizes.items():
            lines.append('| ' + name + ' | ' + size + ' | ' + ' | '.join(
                f"{entry['per_seed'][str(seed)]['threshold']:.9g}" if str(seed) in entry['per_seed'] else '—'
                for seed in (43, 44, 45)) + ' |')
    lines += [
              '', '## unsure 제외 (포함 평가에서 고른 문턱 고정)', '']
    lines += table(results, unsure='excluded')
    lines += ['', '## unsure 제외 LOSO', '']
    lines += table(results, mode='loso', unsure='excluded')
    lines += ['', '## 보조: 묶음 단위 bootstrap', '',
              '이 절의 대괄호만 묶음 단위 95% 구간이다. 출처 사용 초는 고정하며, 주 구간은 위의 영상 단위 구간이다.', '']
    lines += table(results, ci_key='block_ci95')
    lines += ['', '### 묶음 bootstrap: LOSO', '']
    lines += table(results, mode='loso', ci_key='block_ci95')
    for scope in ('all_family', 'friends'):
        lines += ['', f'## 참고: {scope} dev', '', '주 집단에서 고른 동일 문턱을 적용한다.', '']
        lines += table(results, scope=scope)
        lines += ['', f'### {scope}: unsure 제외', '']
        lines += table(results, scope=scope, unsure='excluded')
    lines += ['', '## 학습 곡선과 합성↔실제 차이', '',
              '| 모델 | 전체 규모 (train/val) | 합성 val 정확도 @0.5 | 합성 val 정확도 @dev 문턱 | 실제 dev 정확도 | 차이 (%p) | LOSO 재현율 | LOSO 걸러 내기 |',
              '|---|---:|---:|---:|---:|---:|---:|---:|']
    for name in ('M0', 'M1'):
        if name not in results['models']:
            continue
        for size, entry in results['models'][name].items():
            v = entry['validation']
            d = entry['dev']['child_family']['included']['metrics']
            l = entry['loso']['child_family']['included']['metrics']
            n = results['synthetic_counts'][size]
            lines.append(f"| {name} | {size} ({n['train']['blocks']}/{n['val']['blocks']}) | "
                         + ' | '.join([cell(v['accuracy_at_half'], False), cell(v['accuracy_at_dev'], False),
                                       cell(d['accuracy']), cell(v['gap_pp']),
                                       cell(l['keep_recall']), cell(l['none_specificity'])]) + ' |')
        lines.append(f'| {name} | 10,000 | 데이터 없음 | 데이터 없음 | 데이터 없음 | 데이터 없음 | 데이터 없음 | 데이터 없음 |')
    lines += ['', '차이는 같은 dev 문턱에서 합성 val 정확도 − 실제 child_family dev 정확도이며 DESIGN §10의 15%p와 비교한다.',
              'M1 하이퍼파라미터는 시험 기본값(epochs=3, lr=3e-5, batch=8, max_length=256, both)에서 바꾸지 않았다.',
              '손실은 이진 BCE + 0.2×종류 CE. M0는 공유 학습 파라미터가 없는 독립 회귀 두 개이므로 종류 목적함수 전체의 0.2배가 각 최적점과 이진 학습에 영향을 주지 않는다.',
              '줄 수·길이 같은 숫자 특징, 화자·시각·라벨 메타데이터는 입력에서 제외하며 합성과 실제 모두 demo.trial.data.input_text 하나를 쓴다.',
              '', '## 크기와 속도', '',
              '| 모델 | 규모 | 저장 MB | CPU ms/묶음 | 학습 초 |', '|---|---:|---:|---:|---:|']
    for name in ('M0', 'M1'):
        if name not in results['models']:
            continue
        for size, entry in results['models'][name].items():
            lines.append('| ' + name + ' | ' + size + ' | ' + ' | '.join(
                cell(entry['resources'][k], False) for k in ('storage_mb', 'cpu_ms', 'training_seconds')) + ' |')
    lines += ['', 'MB=10^6 bytes. 저장 크기는 가중치와 tokenizer/config/architecture를 포함한 실제 파일 합계다.',
              'CPU 단일 스레드·batch=1·창작 입력·준비 3회 후 15회 중앙값. 특징 추출/토큰화 포함, M1은 256토큰 고정 패딩.',
              '학습 초는 모델 로드와 fit을 포함한다. 실제 기기 성능·양자화 후 크기는 T58/T59에서 확인해야 한다.',
              '시간당 저장 분량은 keep 예측 묶음 초 합 / 선택된 출처 usable_seconds 합 ×60이다. 저장 상한 정책 적용 전이다.',
              '출처 bootstrap은 출처가 중복되면 분자와 사용 초를 함께 중복한다. 묶음 bootstrap은 원래 출처 사용 초를 고정한다.',
              'all_keep와 Gemini는 hard label이라 AUROC는 —. Gemini는 세 반복을 이진 우선 다수결한 뒤 keep 안의 종류를 정한다.',
              '', '## 한계', '',
              '주 평가가 7영상·37묶음이며 판정자는 vizatae 한 명이다. dev 제안 확인 기록과 listened=False도 원래 판정대로 포함한다.',
              '같은 가족의 여러 영상은 서로 독립이 아닐 수 있다. 영상별 LOSO는 가족별 외부 검증을 대신하지 않는다.',
              'dev 문턱을 같은 dev에 적용한 주 표는 낙관적이며 LOSO도 반복적인 모델 선택 편향을 모두 없애지는 못한다.',
              'friends는 rubric v3에서 모두 none이어야 하므로 재현율·AUROC가 정의되지 않을 수 있다.',
              'test는 실행하지 않았다. 별도 eval.v3 test --open-test 명령만 고정된 candidate를 한 번 평가하며 재학습·문턱 선택을 하지 않는다.',
              f"총 실행 초: {results.get('elapsed_seconds', 0):.1f}.", '']
    return '\n'.join(lines)
