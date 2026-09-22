"""Device-only camera contention, screen-off cold start and first decoded frame checks.

Configures the app's default mode, temporarily opens the system camera without taking a photo,
then turns the display off using the normal power key action. It does not bypass keyguard.
"""
import argparse
from datetime import datetime, timezone
import json
import re
import shutil
import subprocess
import time

from device import ROOT, adb
from verify_modes import configure
from verify_rtsp import RtspClient, decode, wait_camera


def first_decoded_frame(host, transport):
    started = time.monotonic()
    result = subprocess.run([shutil.which("ffmpeg"), "-hide_banner", "-loglevel", "error", "-nostdin",
        "-rtsp_transport", transport, "-timeout", "10000000", "-i", f"rtsp://{host}:8554/live",
        "-frames:v", "1", "-vf", "scale=16:16", "-pix_fmt", "gray", "-f", "rawvideo", "pipe:1"],
        capture_output=True, timeout=20)
    elapsed = time.monotonic() - started
    assert result.returncode == 0, result.stderr.decode("utf-8", "replace")
    assert len(result.stdout) == 256, "No complete decoded frame"
    assert elapsed < 5, "First decoded frame exceeded five seconds"
    return round(elapsed * 1000, 2)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", required=True)
    args = parser.parse_args()
    report = {"started_at": datetime.now(timezone.utc).isoformat(), "cases": []}
    output = ROOT / "artifacts/background-device.json"
    def record(name, **details):
        item = {"name": name, **details}
        report["cases"].append(item)
        output.write_text(json.dumps(report, indent=2), encoding="utf-8")
        print(json.dumps(item), flush=True)
    configure({"auth": False, "codec": "H264", "cameraId": "0", "width": 1920, "height": 1080,
               "fps": 30, "rotation": 0, "mirror": False, "time": False, "battery": False},
              ROOT / "artifacts/configure-background.txt")
    try:
        adb("shell", "am", "start", "-a", "android.media.action.STILL_IMAGE_CAMERA")
        time.sleep(3)
        active = adb("shell", "dumpsys", "media.camera").decode().split("Active Camera Clients:", 1)[1].split("Allowed user IDs:", 1)[0]
        conflicting = "Camera ID: 0" in active or re.search(r"Conflicting Client Devices: \{[^}]*\b0\b", active)
        if not conflicting or "com.cekavis.rtspcamera" in active:
            record("camera_contention", passed=None, reason="System camera did not open a camera conflicting with camera 0; no contention result claimed")
        else:
            client = RtspClient(args.host)
            try:
                try:
                    status = client.request("DESCRIBE", expected=None)[0]
                except EOFError:
                    status = "closed_during_recovery"
                assert status != 200, "Competing foreground camera did not produce the expected busy failure"
                record("camera_contention", passed=True, response=status)
            finally:
                client.close(False)
    finally:
        adb("shell", "am", "start", "-n", "com.cekavis.rtspcamera/.ui.MainActivity")
        time.sleep(2)
    wait_camera(False)
    record("playback_after_returning_from_system_camera", passed=True, decode=decode(args.host, 8554, "tcp", 3))
    wait_camera(False)
    adb("shell", "input", "keyevent", "223")
    time.sleep(3)
    power = adb("shell", "dumpsys", "power").decode()
    assert "mWakefulness=Asleep" in power or "mWakefulness=Dozing" in power, "Display is not asleep"
    wait_camera(False)
    for transport in ["tcp", "udp"]:
        first_ms = first_decoded_frame(args.host, transport)
        wait_camera(False)
        result = decode(args.host, 8554, transport, 5)
        released_ms = wait_camera(False)
        record("screen_off_idle_cold_start_" + transport, passed=True,
               first_decoded_frame_ms=first_ms, release_observed_ms=released_ms, decode=result)
    report["finished_at"] = datetime.now(timezone.utc).isoformat()
    report["device_left_screen_off"] = True
    output.write_text(json.dumps(report, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
