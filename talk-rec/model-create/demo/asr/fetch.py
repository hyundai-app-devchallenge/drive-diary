"""Explicit download command, intended to run outside the sandbox."""
import argparse

from .configs import CONFIGS, HF_CACHE, validate_configs

FILES = ["model.bin", "config.json", "tokenizer.json", "vocabulary.*", "preprocessor_config.json"]


def fetch(cache=HF_CACHE, downloader=None):
    validate_configs()
    if downloader is None:
        from huggingface_hub import snapshot_download
        downloader = snapshot_download
    for repo in sorted({c.model for c in CONFIGS.values() if isinstance(c.model, str)}):
        downloader(repo_id=repo, cache_dir=str(cache), allow_patterns=FILES)
        print(f"모델 준비 완료: {repo}")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.parse_args(argv)
    fetch()


if __name__ == "__main__":
    main()
