"""Sample device-wide memory: CT2 allocations are not tracked by torch peaks."""
import os
import subprocess
import threading


class MemorySampler:
    interval = 0.2

    def __init__(self):
        self.peak = None
        self.samples = 0
        self.stop = threading.Event()
        self.thread = None

    def sample(self):
        try:
            result = subprocess.run(
                ["nvidia-smi", "--id=0", "--query-gpu=memory.used", "--format=csv,noheader,nounits"],
                capture_output=True, text=True, timeout=3, check=True,
                creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
            value = float(result.stdout.strip())
            self.peak = max(value, self.peak or 0)
            self.samples += 1
        except (OSError, ValueError, subprocess.SubprocessError):
            pass

    def loop(self):
        while not self.stop.wait(self.interval):
            self.sample()

    def __enter__(self):
        self.sample()
        self.thread = threading.Thread(target=self.loop, daemon=True)
        self.thread.start()
        return self

    def __exit__(self, *_):
        self.stop.set()
        self.thread.join(timeout=4)
        self.sample()

    def stats(self):
        return dict(peak_mib=self.peak, samples=self.samples, interval_seconds=self.interval,
                    scope="GPU 0 device-wide sampled usage; includes other processes; may miss brief peaks",
                    status="sampled" if self.samples else "unavailable")
