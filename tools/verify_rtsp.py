"""Real-device RTSP regression. Requires the app running with authentication disabled for testing.

The script asserts camera ownership using dumpsys, receives actual RTP, and decodes using FFmpeg.
It records measurements; it never changes phone permissions, Wi-Fi, or battery policy.
"""
from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import shutil
import socket
import subprocess
import time

from device import ADB, ROOT, adb

PACKAGE = "com.cekavis.rtspcamera"


class RtspClient:
    def __init__(self, host, port=8554):
        self.url = f"rtsp://{host}:{port}/live"
        self.socket = socket.create_connection((host, port), 12)
        self.socket.settimeout(15)
        self.reader = self.socket.makefile("rb")
        self.sequence = 0
        self.session = None

    def read(self):
        first = self.reader.read(1)
        if not first:
            raise EOFError("RTSP peer closed")
        if first == b"$":
            head = self.reader.read(3)
            size = int.from_bytes(head[1:], "big")
            payload = self.reader.read(size)
            if len(payload) != size:
                raise EOFError("Truncated RTP")
            return "rtp", head[0], payload
        line = first + self.reader.readline(8192)
        status = int(line.split()[1])
        headers = {}
        while True:
            line = self.reader.readline(8192)
            if not line:
                raise EOFError("Truncated RTSP response")
            if line in (b"\r\n", b"\n"):
                break
            key, value = line.decode("utf-8").split(":", 1)
            headers[key.lower()] = value.strip()
        body = self.reader.read(int(headers.get("content-length", "0")))
        return status, headers, body

    def request(self, method, suffix="", headers=None, expected=200):
        self.sequence += 1
        fields = {"CSeq": str(self.sequence), "User-Agent": "RTSP-Camera-Device-Regression"}
        if self.session:
            fields["Session"] = self.session
        fields.update(headers or {})
        request = f"{method} {self.url}{suffix} RTSP/1.0\r\n" + "".join(f"{k}: {v}\r\n" for k, v in fields.items()) + "\r\n"
        self.socket.sendall(request.encode("utf-8"))
        while True:
            response = self.read()
            if response[0] == "rtp":
                continue
            status, response_headers, body = response
            assert int(response_headers["cseq"]) == self.sequence
            if "session" in response_headers:
                self.session = response_headers["session"].split(";")[0]
            if expected is not None:
                assert status == expected, f"{method}: expected {expected}, got {status}"
            return status, response_headers, body

    def describe(self):
        self.request("OPTIONS")
        return self.request("DESCRIBE", headers={"Accept": "application/sdp"})[2]

    def play(self):
        self.describe()
        self.request("SETUP", "/trackID=0", {"Transport": "RTP/AVP/TCP;unicast;interleaved=0-1"})
        self.request("PLAY", headers={"Range": "npt=0.000-"})
        return self.first_packet()

    def first_packet(self):
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            kind, channel, data = self.read()
            if kind == "rtp" and channel == 0:
                assert len(data) > 12 and data[0] >> 6 == 2
                return data
        raise AssertionError("No RTP within 10 seconds")

    def close(self, teardown=True):
        try:
            if teardown and self.session:
                self.request("TEARDOWN")
        finally:
            self.reader.close()
            self.socket.close()


def camera_active():
    dump = adb("shell", "dumpsys", "media.camera").decode("utf-8", "replace")
    active = dump.split("Active Camera Clients:", 1)[1].split("Allowed user IDs:", 1)[0]
    return PACKAGE in active


def wait_camera(expected, timeout=2.0):
    start = time.monotonic()
    while time.monotonic() - start < timeout:
        if camera_active() == expected:
            return round((time.monotonic() - start) * 1000, 2)
        time.sleep(.05)
    raise AssertionError(f"Camera active did not become {expected} within {timeout}s")


def decode(host, port, transport, seconds=5, filename=None):
    ffmpeg = shutil.which("ffmpeg")
    assert ffmpeg, "FFmpeg is required"
    command = [ffmpeg, "-hide_banner", "-loglevel", "info", "-nostdin", "-rtsp_transport", transport,
               "-timeout", "10000000", "-i", f"rtsp://{host}:{port}/live", "-t", str(seconds), "-an"]
    if filename:
        command += ["-frames:v", "1", "-y", str(filename)]
    else:
        command += ["-f", "null", "-"]
    start = time.monotonic()
    result = subprocess.run(command, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, timeout=seconds + 25)
    log = result.stderr.decode("utf-8", "replace")
    if result.returncode:
        raise AssertionError(f"FFmpeg {transport} failed (exit {result.returncode}): {log[-1800:]}")
    frames = [int(n) for n in re.findall(r"frame=\s*(\d+)", log)]
    assert frames and max(frames) > 0, "FFmpeg decoded no frames"
    if "Error while decoding" in log or "corrupt decoded frame" in log:
        raise AssertionError("Decoder reported corrupt video")
    return {"transport": transport, "decoded_frames": max(frames), "elapsed_seconds": round(time.monotonic() - start, 3)}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", required=True)
    parser.add_argument("--port", type=int, default=8554)
    parser.add_argument("--cycles", type=int, default=100)
    parser.add_argument("--output", type=Path, default=ROOT / "artifacts/device-regression.json")
    args = parser.parse_args()
    report = {"started_at": datetime.now(timezone.utc).isoformat(), "cases": []}

    def case(name, function):
        start = time.monotonic()
        try:
            detail = function()
            item = {"name": name, "passed": True, "seconds": round(time.monotonic() - start, 3), "details": detail}
        except Exception as error:
            item = {"name": name, "passed": False, "seconds": round(time.monotonic() - start, 3), "error": str(error)}
            report["cases"].append(item)
            save()
            raise
        report["cases"].append(item)
        save()
        print(json.dumps(item), flush=True)

    def save():
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, indent=2), encoding="utf-8")

    def options_only():
        wait_camera(False)
        client = RtspClient(args.host, args.port)
        try:
            client.request("OPTIONS")
            assert not camera_active(), "OPTIONS unexpectedly opened camera"
        finally:
            client.close(False)

    case("options_does_not_open_camera", options_only)
    case("h264_or_hevc_decode_tcp", lambda: decode(args.host, args.port, "tcp"))
    wait_camera(False)
    case("h264_or_hevc_decode_udp", lambda: decode(args.host, args.port, "udp"))
    wait_camera(False)

    def negotiation_timeout():
        client = RtspClient(args.host, args.port)
        try:
            client.describe()
            wait_camera(True)
            return {"camera_release_ms": wait_camera(False, 12)}
        finally:
            client.close(False)

    case("describe_without_play_releases_camera", negotiation_timeout)

    def pause_resume():
        client = RtspClient(args.host, args.port)
        try:
            client.play()
            wait_camera(True)
            client.request("PAUSE")
            released = wait_camera(False)
            status, _, _ = client.request("PLAY", headers={"Range": "npt=now-"}, expected=None)
            if status == 455:
                client.play()
            else:
                assert status == 200
                client.first_packet()
            wait_camera(True)
            return {"pause_release_ms": released, "resume_status": status}
        finally:
            client.close()
            wait_camera(False)

    case("pause_resume", pause_resume)

    def concurrent():
        with ThreadPoolExecutor(max_workers=4) as pool:
            futures = [pool.submit(decode, args.host, args.port, "tcp" if i % 2 == 0 else "udp", 8) for i in range(4)]
            values = [future.result() for future in futures]
        wait_camera(False)
        return values

    case("four_decoding_clients", concurrent)

    def slow_peer():
        client = RtspClient(args.host, args.port)
        try:
            client.socket.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 1024)
            client.play()
            return decode(args.host, args.port, "tcp", 15)
        finally:
            client.close(False)
            wait_camera(False, 3)

    case("nonreading_client_does_not_block_decoder", slow_peer)

    def reconnects():
        cold, release = [], []
        for index in range(args.cycles):
            started = time.monotonic()
            client = RtspClient(args.host, args.port)
            try:
                client.play()
                cold.append((time.monotonic() - started) * 1000)
                assert cold[-1] < 5000, "Cold RTP start exceeded five seconds"
                wait_camera(True)
            finally:
                client.close(teardown=index % 2 == 0)
            release.append(wait_camera(False))
            if (index + 1) % 10 == 0:
                print(f"Completed {index + 1}/{args.cycles} cold reconnects", flush=True)
        return {"cycles": args.cycles, "max_first_rtp_ms": round(max(cold), 2) if cold else None,
                "average_first_rtp_ms": round(sum(cold) / len(cold), 2) if cold else None,
                "max_release_observed_ms": max(release) if release else None}

    if args.cycles:
        case("repeated_cold_connections_and_abrupt_eof", reconnects)
    report["finished_at"] = datetime.now(timezone.utc).isoformat()
    save()


if __name__ == "__main__":
    main()
