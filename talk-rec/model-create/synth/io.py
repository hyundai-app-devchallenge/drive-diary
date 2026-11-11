"""Small JSONL utilities; temporary and lock files never live in demo/."""
from contextlib import contextmanager
import hashlib
import json
import os
from pathlib import Path
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def read_jsonl(path):
    with Path(path).open(encoding='utf-8') as stream:
        return [json.loads(line) for line in stream if line.strip()]


def digest(value):
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True).encode()).hexdigest()


def write_text(path, content):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    scratch = ROOT / '.local/syntht/tmp'
    scratch.mkdir(parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(dir=scratch)
    try:
        with os.fdopen(fd, 'w', encoding='utf-8', newline='\n') as stream:
            stream.write(content)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def write_json(path, value):
    write_text(path, json.dumps(value, ensure_ascii=False, indent=2) + '\n')


def write_jsonl(path, rows):
    write_text(path, ''.join(json.dumps(row, ensure_ascii=False) + '\n' for row in rows))


@contextmanager
def output_lock(path):
    """OS advisory lock releases after crashes. Persistent lock lives in syntht/."""
    directory = ROOT / '.local/syntht/locks'
    directory.mkdir(parents=True, exist_ok=True)
    lockpath = directory / (digest(str(Path(path).resolve()).casefold()) + '.lock')
    with lockpath.open('a+b') as stream:
        if os.name == 'nt':
            import msvcrt
            stream.seek(0)
            stream.write(b'0')
            stream.flush()
            stream.seek(0)
            msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
        else:
            import fcntl
            fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
        try:
            yield
        finally:
            if os.name == 'nt':
                stream.seek(0)
                msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)
