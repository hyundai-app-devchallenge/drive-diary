"""Add a CT2 local candidate with one CONFIGS entry; never execute model code."""
from copy import deepcopy
from dataclasses import dataclass
from pathlib import Path
import re

from pipeline.segment import DECODING, MODEL
from tools.listen.storage import ROOT

LARGE_REPO = "Systran/faster-whisper-large-v3"
HF_CACHE = ROOT / ".local/hf"
PROMPT = "차 안에서 가족이 나누는 대화. 아이와 부모가 말한다."


@dataclass(frozen=True)
class Config:
    model: str | Path
    mode: str = "base"
    prompt: str | None = None

    def decoding(self):
        options = deepcopy(DECODING)
        if self.mode == "base":
            options["vad_parameters"] = dict(threshold=0.5, min_speech_duration_ms=0,
                                             min_silence_duration_ms=1000, speech_pad_ms=300)
        else:
            options.update(vad_filter=False, chunk_length=30, condition_on_previous_text=True,
                           temperature=[0, 0.2, 0.4])
            if self.prompt is not None:
                options["initial_prompt"] = self.prompt
        return options


CONFIGS = {
    "turbo-base": Config(MODEL),
    "large-v3-base": Config(LARGE_REPO),
    "large-v3-long": Config(LARGE_REPO, "long"),
    "large-v3-long-prompt": Config(LARGE_REPO, "long", PROMPT),
    # "ko-long": Config(Path(r"C:\models\korean-ct2"), "long"),
}


def validate_configs(configs=CONFIGS):
    for name, config in configs.items():
        if (not re.fullmatch(r"[a-z0-9][a-z0-9_-]{0,79}", name)
                or not isinstance(config, Config) or config.mode not in {"base", "long"}
                or (config.prompt is not None and (config.mode != "long" or not isinstance(config.prompt, str)))
                or not isinstance(config.model, (str, Path)) or not str(config.model)):
            raise ValueError("ASR 설정 표를 확인하세요.")
        if isinstance(config.model, str) and config.model != LARGE_REPO:
            raise ValueError("추가 모델은 CT2 로컬 Path로 지정하세요. 원격 코드는 허용하지 않습니다.")
    return configs


def model_path(config, cache=HF_CACHE):
    if isinstance(config.model, Path):
        path = config.model.resolve()
    else:
        from huggingface_hub import snapshot_download
        path = Path(snapshot_download(config.model, cache_dir=str(cache), local_files_only=True))
    if not all((path / name).is_file() for name in ("model.bin", "config.json", "tokenizer.json")):
        raise ValueError("CT2 모델 파일이 없습니다. 먼저 demo.asr.fetch를 밖에서 실행하세요.")
    return path
