"""Connect Dongbancha's debug Gleo button to one Pleos Connect AVD.

Example: python module/talkrec/tools/gleo-voice-button.py --serial emulator-5554

The app connects to 10.0.2.2, which the emulator maps to this PC's loopback, so the bridge
keeps working across AVD reboots and ADB reconnects without `adb reverse`.
"""

import argparse
import re
import secrets
import socketserver
import subprocess
import threading


PORT = 43192
APP_ID = "com.dongbantest.app"
TOKEN_PATH = "files/gleo-voice-button.token"


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

    token = secrets.token_hex(32)
    expected = (token + "\n").encode("ascii")
    lock = threading.Lock()

    def write_token():
        """Store the token in the debug APK's private files. Returns False if that is not possible yet."""
        try:
            user = adb("shell", "am", "get-current-user", check=False).stdout.strip()
            if not re.fullmatch(r"[0-9]+", user):
                return False
            # run-as requires the debug APK. The random token stays in its private files.
            run_as = f"run-as {APP_ID} --user {user}"
            result = adb("shell", f"{run_as} sh -c 'mkdir -p files && printf %s {token} > {TOKEN_PATH}'", check=False)
            return result.returncode == 0
        except (OSError, subprocess.TimeoutExpired):
            return False

    class VoiceButtonHandler(socketserver.StreamRequestHandler):
        def handle(self):
            self.request.settimeout(2)
            try:
                if not secrets.compare_digest(self.rfile.readline(len(expected) + 1), expected):
                    # Reinstall or cleared app data removes the token. Write it again and let the app retry.
                    with lock:
                        refreshed = write_token()
                    self.wfile.write(b"t")
                    if not refreshed:
                        print("Could not write the token. Is the debug APK installed?", flush=True)
                    return
                # The request can only produce this one key on the selected AVD.
                result = adb("shell", "cmd", "car_service", "inject-key", "231", timeout=3, check=False)
                sent = result.returncode == 0 and "Succeeded" in result.stdout.splitlines()
                self.wfile.write(b"1" if sent else b"0")
                print("Voice button sent." if sent else "Vehicle service did not accept KEYCODE_VOICE_ASSIST.", flush=True)
            except (OSError, subprocess.TimeoutExpired):
                print("Vehicle voice button request timed out or disconnected.", flush=True)

    # Loopback only. 10.0.2.2 inside the emulator reaches this address.
    with socketserver.ThreadingTCPServer(("127.0.0.1", PORT), VoiceButtonHandler) as server:
        # Clean up the forwarding used by older versions of this script.
        adb("reverse", "--remove", f"tcp:{PORT}", check=False)
        if not write_token():
            print("Debug APK not reachable yet. The token is written on the first button press.", flush=True)
        print(f"Gleo voice button bridge for {args.serial} on 127.0.0.1:{PORT}. Ctrl+C to stop.", flush=True)
        server.serve_forever()


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        pass
    except subprocess.CalledProcessError as error:
        raise SystemExit((error.stderr or error.stdout or "ADB command failed").strip()) from None
    except subprocess.TimeoutExpired:
        raise SystemExit("Could not connect to ADB. Check the emulator and --adb path.") from None
    except OSError as error:
        raise SystemExit(f"Could not start the bridge: {error}") from None
