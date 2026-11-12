"""Download public model assets only; run explicitly outside the sandbox."""
import argparse

from .acoustic import AST_MODEL, SPEAKER_MODEL


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cache', default='.local/hf')
    args = parser.parse_args(argv)
    from huggingface_hub import HfApi, snapshot_download
    files = HfApi().list_repo_files(AST_MODEL)
    weights = next((name for name in ('model.safetensors', 'pytorch_model.bin') if name in files), None)
    if weights is None:
        raise ValueError('AST repository has no supported weights')
    snapshot_download(AST_MODEL, cache_dir=args.cache,
                      allow_patterns=['config.json', 'preprocessor_config.json', weights])
    snapshot_download(SPEAKER_MODEL, cache_dir=args.cache, allow_patterns=['hyperparams.yaml', '*.ckpt'])
    print('Downloaded AST and ECAPA assets; no remote code enabled.')


if __name__ == '__main__':
    main()
