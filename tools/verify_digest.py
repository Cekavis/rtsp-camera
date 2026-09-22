"""Real Digest authentication test using only the instrumentation APK's synthetic fixture.

No credentials, Authorization headers, RTSP URIs with credentials, or FFmpeg logs are emitted.
Run after configuring auth=true with DeviceHarnessTest, or pass --configure to do so.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import time

from device import ROOT
from verify_modes import configure
from verify_rtsp import RtspClient, camera_active, wait_camera


def fixture():
    return "rtsp-device-harness", hashlib.sha256(b"rtsp-camera-test-apk-temporary-credential-v1").hexdigest()[:24]


def signature(challenge, method, uri, count, incorrect=False):
    fields = dict(re.findall(r'(\w+)="([^"]+)"', challenge))
    user, password = fixture()
    if incorrect:
        password += "-invalid"
    nonce, realm, opaque = fields["nonce"], fields["realm"], fields["opaque"]
    cnonce, nc = secrets.token_hex(12), f"{count:08x}"
    def md5(s):
        return hashlib.md5(s.encode()).hexdigest()
    response = md5(f"{md5(f'{user}:{realm}:{password}')}:{nonce}:{nc}:{cnonce}:auth:{md5(f'{method}:{uri}')}")
    return (f'Digest username="{user}", realm="{realm}", nonce="{nonce}", uri="{uri}", '
            f'opaque="{opaque}", response="{response}", qop=auth, nc={nc}, cnonce="{cnonce}", algorithm=MD5')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", required=True)
    parser.add_argument("--configure", action="store_true")
    parser.add_argument("--seconds", type=int, default=325)
    parser.add_argument("--output", type=Path, default=ROOT / "artifacts/digest-device.json")
    args = parser.parse_args()
    report = {"started_at": datetime.now(timezone.utc).isoformat(), "cases": []}
    def record(name, **details):
        report["cases"].append({"name": name, "passed": True, **details})
        args.output.write_text(json.dumps(report, indent=2), encoding="utf-8")
        print(name + ": passed", flush=True)
    if args.configure:
        configure({"auth": True, "codec": "H264", "cameraId": "0", "width": 1920, "height": 1080,
                   "fps": 30, "rotation": 0, "mirror": False, "time": False, "battery": False},
                  ROOT / "artifacts/configure-digest.txt")
    wait_camera(False)
    client = RtspClient(args.host)
    try:
        for method in ["OPTIONS", "DESCRIBE", "SETUP", "PLAY", "PAUSE", "GET_PARAMETER", "TEARDOWN"]:
            client.request(method, expected=401)
            assert not camera_active(), "Unauthenticated request activated camera"
        record("every_method_requires_authentication")
        challenge = client.request("DESCRIBE", expected=401)[1]["www-authenticate"]
        client.request("DESCRIBE", headers={"Authorization": signature(challenge, "DESCRIBE", client.url, 1, True)}, expected=401)
        assert not camera_active()
        record("wrong_password_does_not_activate_camera")
        signed = signature(challenge, "DESCRIBE", client.url, 1)
        client.request("DESCRIBE", headers={"Authorization": signed})
        wait_camera(True)
        client.request("DESCRIBE", headers={"Authorization": signed}, expected=401)
        client.request("OPTIONS", headers={"Authorization": signature(challenge, "PLAY", client.url, 2)}, expected=401)
        record("replay_and_method_substitution_rejected")
    finally:
        client.close(False)
    wait_camera(False)
    user, password = fixture()
    url = f"rtsp://{user}:{password}@{args.host}:8554/live"
    started = time.monotonic()
    try:
        result = subprocess.run([shutil.which("ffmpeg"), "-hide_banner", "-loglevel", "info", "-nostdin",
            "-rtsp_transport", "tcp", "-timeout", "15000000", "-i", url, "-t", str(args.seconds),
            "-an", "-f", "null", "-"], stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, timeout=args.seconds + 30)
    except subprocess.TimeoutExpired:
        raise AssertionError("Authenticated FFmpeg decode timed out") from None
    log = result.stderr.decode("utf-8", "replace")
    frames = [int(n) for n in re.findall(r"frame=\s*(\d+)", log)]
    assert result.returncode == 0, "Authenticated FFmpeg decode failed; credential-bearing log withheld"
    assert frames and max(frames) >= args.seconds * 27, "Authenticated stream ended early or dropped below the acceptance rate"
    assert "Error while decoding" not in log and "corrupt decoded frame" not in log
    wait_camera(False)
    record("authenticated_ffmpeg_decode_across_nonce_expiry", seconds=args.seconds,
           decoded_frames=max(frames), wall_seconds=round(time.monotonic()-started, 3),
           crosses_five_minute_nonce_expiry=args.seconds > 300)
    report["finished_at"] = datetime.now(timezone.utc).isoformat()
    args.output.write_text(json.dumps(report, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
