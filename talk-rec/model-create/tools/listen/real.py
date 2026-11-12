"""실제 묶음 청취 저장소. 지정 판정자 파일 하나만 연다."""
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import threading

from eval.real import (JUDGMENT_KEYS, SUGGESTION_FIELDS, human_name, judgment_path,
                       listening_order, load_suggestions, local_path, read_split, review_result,
                       read_objects, read_own_judgments, run_directory,
                       validate_judgment)
from .storage import ROOT, atomic_write, encode_lines


class RealListenStore:
    page_name = 'real.html'

    def __init__(self, run, annotator, *, root=ROOT, suggest=None):
        self.root, self.name = Path(root), human_name(annotator)
        if suggest not in (None, 'claude'):
            raise ValueError('제안 이름 오류')
        self.suggest = suggest
        self.directory = run_directory(root, run)
        self.path = judgment_path(self.directory, self.name)
        self.rows = listening_order(read_objects(self.directory / 'blocks.jsonl'))
        self.mapping = read_split(root)
        if any(r['source'][3:] not in self.mapping for r in self.rows):
            raise ValueError('출처 분할 없음: split 먼저 실행')
        source_path = self.directory / 'sources.json'
        self.sources = {r['id']: r for r in json.loads(source_path.read_text(encoding='utf-8'))}
        self.by_id = {r['id']: r for r in self.rows}
        if len(self.by_id) != len(self.rows):
            raise ValueError('묶음 중복')
        from eval.real import BLOCK
        for row in self.rows:
            match = BLOCK.fullmatch(row['id'])
            if not match or row['source'] != 'yt:' + match[1] or not 0 <= row['start'] <= row['end']:
                raise ValueError('묶음 형식 오류')
            usable = self.usable_range(row)
            if not usable['start'] <= row['start'] <= row['end'] <= usable['end']:
                raise ValueError('묶음 구간 오류')
        self.lock = threading.RLock()
        self.suggestions = self._suggestions()
        self._own_answers()

    def _suggestions(self):
        return load_suggestions(self.root, self.directory) if self.suggest else {}

    def _own_answers(self):
        return read_own_judgments(self.directory, self.name, self.by_id)

    def usable_range(self, row):
        source = self.sources[row['source'][3:]]
        ranges = source.get('ranges', [dict(start=0, end=source['seconds'], note='')])
        return ranges[row.get('range_index', 0)]

    def media(self, block_id):
        from .clips import cut
        row = self.by_id[block_id]
        usable = self.usable_range(row)
        start, end = max(usable['start'], row['start']-2), min(usable['end'], row['end']+2)
        bounds = hashlib.sha256(f'{start}:{end}'.encode()).hexdigest()[:16]
        destination = local_path(self.root, self.directory / 'clips' / (block_id.replace(':', '_').replace('#', '_') + '_' + bounds + '.wav'))
        source = local_path(self.root, self.root / '.local/audio' / (row['source'][3:] + '.webm'))
        with self.lock:
            if not destination.is_file():
                cut(source, destination, start, end)
        return destination, 0, end-start

    def state(self):
        with self.lock:
            answers = self._own_answers()
            self.suggestions = self._suggestions()
            rows = [dict(id=r['id'], source=r['source'], start=r['start'], end=r['end'],
                         split=self.mapping[r['source'][3:]],
                         range_index=r.get('range_index', 0), note=self.usable_range(r)['note'],
                         turns=[line['text'] for line in r['lines']],
                         answer=answers.get(r['id'])) for r in self.rows]
            for row in rows:
                suggestion = self.suggestions.get(row['id']) if row['split'] == 'dev' else None
                if suggestion:
                    row['suggestion'] = {k: suggestion[k] for k in SUGGESTION_FIELDS}
            return dict(name=self.name, rows=rows, total=len(rows), answered=len(answers))

    def save(self, payload):
        if not isinstance(payload, dict) or set(payload) != JUDGMENT_KEYS - {'labeler', 'saved_at'}:
            raise ValueError('판정 요청 형식 오류')
        row = validate_judgment(dict(payload, labeler='human:' + self.name,
                                     saved_at=datetime.now(timezone.utc).isoformat()), self.name)
        if row['id'] not in self.by_id:
            raise ValueError('묶음 id 오류')
        with self.lock:
            suggestion = self.suggestions.get(row['id'])
            if self.mapping[self.by_id[row['id']]['source'][3:]] == 'dev' and suggestion:
                row.update(suggested_by='claude', review=review_result(row, suggestion))
            old = self.path.read_bytes() if self.path.exists() else b''
            atomic_write(self.path, old + (b'\n' if old and not old.endswith(b'\n') else b'') + encode_lines([row]))
        return dict(saved=row)
