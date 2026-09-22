"""Configure the connected test device, decode each requested mode and record dimensions.

Uses the debug instrumentation APK, and deliberately uses no authentication for these cases.
Captured camera images stay in the ignored artifacts/captures directory.
"""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import shutil
import subprocess
import time

from device import ROOT, adb, tap
from verify_rtsp import decode, wait_camera

RUNNER = "com.cekavis.rtspcamera.test/androidx.test.runner.AndroidJUnitRunner"
HARNESS = "com.cekavis.rtspcamera.device.DeviceHarnessTest"


def configure(options, record):
    args = ["shell", "am", "instrument", "-w", "-r", "-e", "class", HARNESS + "#configureOnlyForExternalPlayback"]
    for key, value in options.items():
        args += ["-e", key, str(value).lower() if isinstance(value, bool) else str(value)]
    result = adb(*args, RUNNER, timeout=90).decode("utf-8", "replace")
    record.write_text(result, encoding="utf-8")
    assert "OK (1 test)" in result, "Device configuration failed; see instrumentation record"
    adb("shell", "am", "start", "-n", "com.cekavis.rtspcamera/.ui.MainActivity")
    time.sleep(1)
    tap("启动服务")
    time.sleep(1)
    wait_camera(False)


def probe(host):
    result = subprocess.run([shutil.which("ffprobe"), "-v", "error", "-rtsp_transport", "tcp",
        "-timeout", "10000000", "-i", f"rtsp://{host}:8554/live", "-select_streams", "v:0",
        "-show_entries", "stream=codec_name,width,height,r_frame_rate", "-of", "json"],
        capture_output=True, timeout=25)
    assert result.returncode == 0, result.stderr.decode("utf-8", "replace")
    return json.loads(result.stdout)["streams"][0]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", required=True)
    parser.add_argument("--output", type=Path, default=ROOT / "artifacts/video-modes.json")
    args = parser.parse_args()
    cases = [
        {"name": "hevc_1080p", "codec": "HEVC", "cameraId": "0", "width": 1920, "height": 1080, "fps": 30},
        {"name": "rotate90_mirror_overlay", "rotation": 90, "mirror": True},
        {"name": "rotate180_overlay", "rotation": 180, "mirror": False},
        {"name": "rotate270_mirror_overlay", "rotation": 270, "mirror": True},
        {"name": "front_720p15_overlay", "cameraId": "1", "width": 1280, "height": 720, "fps": 15},
        {"name": "back_720p30_overlay", "cameraId": "0", "width": 1280, "height": 720, "fps": 30},
    ]
    report = {"started_at": datetime.now(timezone.utc).isoformat(), "cases": []}
    captures = ROOT / "artifacts/captures"
    captures.mkdir(parents=True, exist_ok=True)
    for case in cases:
        name = case.pop("name")
        options = {"codec": "H264", "cameraId": "0", "width": 1920, "height": 1080, "fps": 30,
                   "rotation": 0, "mirror": False, "time": True, "battery": True, "auth": False, **case}
        item = {"name": name, "configuration": options, "passed": False}
        report["cases"].append(item)
        try:
            configure(options, ROOT / "artifacts" / f"configure-{name}.txt")
            actual = probe(args.host)
            expected = (options["height"], options["width"]) if options["rotation"] % 180 else (options["width"], options["height"])
            assert (actual["width"], actual["height"]) == expected, "Unexpected encoded dimensions"
            assert actual["codec_name"] == ("hevc" if options["codec"] == "HEVC" else "h264")
            wait_camera(False)
            item["probe"] = actual
            item["tcp"] = decode(args.host, 8554, "tcp", 3)
            wait_camera(False)
            item["udp"] = decode(args.host, 8554, "udp", 3)
            wait_camera(False)
            decode(args.host, 8554, "tcp", 3, captures / f"{name}.png")
            wait_camera(False)
            item["passed"] = True
        except Exception as error:
            item["error"] = str(error)
            raise
        finally:
            args.output.write_text(json.dumps(report, indent=2), encoding="utf-8")
            print(json.dumps(item), flush=True)
    report["finished_at"] = datetime.now(timezone.utc).isoformat()
    args.output.write_text(json.dumps(report, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
