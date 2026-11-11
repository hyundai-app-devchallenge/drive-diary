"""Check the installed CUDA build without downloading a model or dataset."""

import warnings

import torch


def main() -> None:
    # Compatibility warnings must fail the check even without Python's -W flag.
    warnings.filterwarnings("error", category=UserWarning, module=r"torch\.cuda")
    print(f"torch: {torch.__version__}")
    print(f"CUDA runtime: {torch.version.cuda}")
    print(f"CUDA available: {torch.cuda.is_available()}")
    if not torch.cuda.is_available():
        raise RuntimeError("CUDA GPU is unavailable")
    print(f"device: {torch.cuda.get_device_name(0)}")
    capability = torch.cuda.get_device_capability(0)
    print(f"device capability: {capability}")
    print(f"compiled architectures: {torch.cuda.get_arch_list()}")
    arch = f"sm_{capability[0]}{capability[1]}"
    if arch not in torch.cuda.get_arch_list():
        raise RuntimeError(f"The installed torch wheel does not include {arch}")
    x = torch.arange(4, dtype=torch.float32, device="cuda").reshape(2, 2)
    result = (x @ x.T).sum().item()
    if result != 20.0:
        raise RuntimeError(f"Unexpected GPU result: {result}")
    print(f"GPU tensor result: {result}")


if __name__ == "__main__":
    main()
