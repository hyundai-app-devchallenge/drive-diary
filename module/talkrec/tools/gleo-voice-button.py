"""Connect Dongbancha's debug Gleo button to one Pleos Connect AVD.

Example: python module/talkrec/tools/gleo-voice-button.py --serial emulator-5554
"""

import argparse
import re
import secrets
import socketserver
import subprocess


DEVICE_PORT = "tcp:43192"
APP_ID = "com.dongbantest.app"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="AVD serial shown by adb devices")
    parser.add_argument("--adb", default="adb", help="Path to Android platform-tools adb")
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-[0-9]+", args.serial):
        parser.error("--serial must identify an Android Emulator")

    def adb(*command, timeout=8, check=True):
        return subprocess.run(
            [args.adb, "-s", args.serial, *command],
            capture_output=True, text=True, timeout=timeout, check=check,
        )

    if adb("shell", "getprop", "ro.kernel.qemu").stdout.strip() != "1":
        parser.error("The selected device is not an emulator")
    user = adb("shell", "am", "get-current-user").stdout.strip()
    if not re.fullmatch(r"[0-9]+", user):
        parser.error("Could not determine the active Android user")
    token = secrets.token_hex(32)
    expected = (token + "\n").encode("ascii")

    class VoiceButtonHandler(socketserver.StreamRequestHandler):
        def handle(self):
            self.request.settimeout(2)
            try:
                if not secrets.compare_digest(self.rfile.readline(len(expected) + 1), expected):
                    self.wfile.write(b"0")
                    return
                # The request can only produce this one key on the selected AVD.
                result = adb("shell", "cmd", "car_service", "inject-key", "231", timeout=3, check=False)
                sent = result.returncode == 0 and "Succeeded" in result.stdout.splitlines()
                self.wfile.write(b"1" if sent else b"0")
                if not sent:
                    print("Vehicle service did not accept KEYCODE_VOICE_ASSIST.", flush=True)
            except (OSError, subprocess.TimeoutExpired):
                print("Vehicle voice button request timed out or disconnected.", flush=True)

    with socketserver.TCPServer(("127.0.0.1", 0), VoiceButtonHandler) as server:
        adb("reverse", "--no-rebind", DEVICE_PORT, f"tcp:{server.server_address[1]}")
        try:
            # run-as requires the debug APK. The random token stays in its private files.
            run_as = f"run-as {APP_ID} --user {user}"
            adb("shell", f"{run_as} mkdir -p files")
            adb("shell", f"{run_as} sh -c 'printf %s {token} > files/gleo-voice-button.token'")
            print(f"Gleo voice button connected to {args.serial}, user {user}. Ctrl+C to stop.", flush=True)
            server.serve_forever()
        finally:
            adb("reverse", "--remove", DEVICE_PORT, check=False)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        pass
    except subprocess.CalledProcessError as error:
        raise SystemExit((error.stderr or error.stdout or "ADB command failed").strip()) from None
    except (OSError, subprocess.TimeoutExpired):
        raise SystemExit("Could not connect to ADB. Check the emulator and --adb path.") from None
