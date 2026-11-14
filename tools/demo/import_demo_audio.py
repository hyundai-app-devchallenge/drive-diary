"""Copy generated demo voice clips into app assets and record their decoded durations.

Usage: python -I tools/demo/import_demo_audio.py --source-dir PATH
PATH holds one folder per trip key (A, B, C) with files named "<key><n>_*.m4a"
(e.g. A1_차량소음.m4a). The n-th file goes to the n-th clip of that trip in
demo_trips.json. Output names use the zero-based index in trip.items,
including question entries (see DemoItem.audioAsset).
"""
import argparse
import json
import re
import shutil
from pathlib import Path

import av

ROOT = Path(__file__).resolve().parents[2]
DEMO = ROOT / "app/src/main/assets/demo"


def decoded_ms(path):
    # AAC pads the final frame. Count actual decoded samples, including that tail.
    with av.open(str(path)) as container:
        stream = container.streams.audio[0]
        samples = sum(frame.samples for frame in container.decode(stream))
        return samples * 1000 // stream.codec_context.sample_rate


def sources(folder, key):
    found = {}
    for path in folder.glob("*.m4a"):
        match = re.match(rf"{key}(\d+)_", path.name)
        if match:
            found[int(match.group(1))] = path
    return [found[n] for n in sorted(found)]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-dir", required=True, type=Path)
    args = parser.parse_args()
    manifest_path = DEMO / "demo_trips.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    audio = DEMO / "audio"
    audio.mkdir(exist_ok=True)
    for old in audio.glob("*.m4a"):
        old.unlink()
    report = []
    for trip in manifest["trips"]:
        key = trip["key"]
        clips = [(i, item) for i, item in enumerate(trip["items"]) if item["type"] == "clip"]
        files = sources(args.source_dir / key, key)
        assert len(files) == len(clips), f"trip {key}: {len(files)} files for {len(clips)} clips"
        for (index, item), source in zip(clips, files):
            name = f"{key}_{index}.m4a"
            shutil.copyfile(source, audio / name)
            item["durationMs"] = decoded_ms(audio / name)
            report.append({"asset": name, "source": source.name, "durationMs": item["durationMs"],
                           "bytes": (audio / name).stat().st_size})
            print(f"{name} <- {source.name}: {item['durationMs']}ms")
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (ROOT / "tools/demo/audio_report.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Total: {sum(item['bytes'] for item in report)} bytes")


if __name__ == "__main__":
    main()
