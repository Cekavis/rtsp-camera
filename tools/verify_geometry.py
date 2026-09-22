"""Regression for geometry/native FPS. Preserves credentials and restores the supplied video profile.

The GPU instrumentation pixel oracle verifies image geometry. This complementary RTSP test
verifies hardware encoding, encoded dimensions, native FPS ranges, and resource release.
The profile contains video settings only plus non-secret auth flags; credentials are never read.
"""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path

from device import ROOT
from verify_modes import configure, probe
from verify_rtsp import decode, wait_camera


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", required=True)
    parser.add_argument("--profile", required=True, type=Path)
    parser.add_argument("--output", type=Path, default=ROOT / "artifacts/geometry-native-device.json")
    args = parser.parse_args()
    profile = json.loads(args.profile.read_text(encoding="utf-8-sig"))
    if profile["server"]["auth"] or profile["server"]["port"] != 8554:
        raise RuntimeError("This local fixture requires the existing profile to use port 8554 without authentication")
    video = profile["video"]
    original = {"preserveOtherSettings": True, "cameraId": video["camera"], "width": video["width"],
                "height": video["height"], "fps": video["fps"], "fpsMin": video.get("fpsMin", video["fps"]),
                "codec": video["codec"], "rotation": video["rotation"], "mirror": video["mirror"],
                "time": video["time"], "battery": video["battery"]}
    cases = [
        ("current_profile", {}),
        ("portrait_90_mirror", {"width": 1920, "height": 1080, "rotation": 90, "mirror": True}),
        ("landscape_180", {"rotation": 180}),
        ("portrait_270_mirror", {"width": 1920, "height": 1080, "rotation": 270, "mirror": True}),
        ("native_fixed_20", {"fpsMin": 20, "fps": 20}),
        ("native_range_15_20", {"fpsMin": 15, "fps": 20}),
        ("native_range_5_30", {"fpsMin": 5, "fps": 30}),
        ("front_h264_720p15", {"cameraId": "1", "codec": "H264", "width": 1280, "height": 720, "fpsMin": 15, "fps": 15}),
    ]
    report = {"started_at": datetime.now(timezone.utc).isoformat(), "status": "RUNNING", "cases": [],
              "scope": "Real RTSP decode/dimensions/ranges; geometric pixel oracle recorded separately in GPU instrumentation",
              "configuration_restored": False}

    def save():
        args.output.write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")

    try:
        for name, changes in cases:
            options = {**original, **changes}
            item = {"name": name, "video_options": options, "passed": False}
            report["cases"].append(item)
            save()
            print(f"Starting {name}", flush=True)
            configure(options, ROOT / "artifacts" / f"geometry-configure-{name}.txt")
            actual = probe(args.host)
            expected = (options["height"], options["width"]) if options["rotation"] % 180 else (options["width"], options["height"])
            assert (actual["width"], actual["height"]) == expected
            assert actual["codec_name"] == ("hevc" if options["codec"] == "HEVC" else "h264")
            wait_camera(False)
            item["probe"] = actual
            for transport in ("tcp", "udp"):
                item[transport] = decode(args.host, 8554, transport, 3)
                item[transport]["camera_release_ms"] = wait_camera(False)
            item["passed"] = True
            save()
            print(json.dumps(item), flush=True)
        report["status"] = "PASSED"
    except Exception as error:
        report["status"] = "FAILED"
        report["error"] = str(error)
        raise
    finally:
        try:
            configure(original, ROOT / "artifacts/geometry-restore-user-profile.txt")
            report["configuration_restored"] = True
        except Exception as restore_error:
            report["status"] = "FAILED"
            report["restore_error"] = str(restore_error)
        report["finished_at"] = datetime.now(timezone.utc).isoformat()
        save()


if __name__ == "__main__":
    main()
