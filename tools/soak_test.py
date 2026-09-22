"""Measured long-running test, with durable records for read-only follow-up.

Each cycle combines a wall-clock FFmpeg decode, an idle interval, and a cold
reconnect. The app must already be running with authentication and local preview
disabled. The runner never alters device configuration. Interrupted runs cannot
be resumed or counted as an uninterrupted 24-hour pass; use a new directory.
"""
from __future__ import annotations

import argparse
import ctypes
from datetime import datetime, timezone
import hashlib
import json
import math
import os
from pathlib import Path
import re
import shutil
import subprocess
import time
import uuid

from device import ROOT, adb
from verify_rtsp import RtspClient

PACKAGE = "com.cekavis.rtspcamera"
TELEMETRY_TIMEOUT = 20
HEARTBEAT_SECONDS = 5
MONITOR_GAP_SECONDS = 30
STALE_AFTER_SECONDS = 45


def utc():
    return datetime.now(timezone.utc).isoformat()


def camera_from_dump(dump):
    if "Active Camera Clients:" not in dump or "Allowed user IDs:" not in dump:
        raise RuntimeError("Cannot parse active camera clients from dumpsys")
    return PACKAGE in dump.split("Active Camera Clients:", 1)[1].split("Allowed user IDs:", 1)[0]


def telemetry():
    """Missing or timed-out measurements fail the sample instead of becoming zero."""
    started = time.monotonic()

    def read(*arguments):
        remaining = TELEMETRY_TIMEOUT - (time.monotonic() - started)
        if remaining <= 0:
            raise TimeoutError("Telemetry collection exceeded its time budget")
        return adb(*arguments, timeout=min(10, remaining)).decode("utf-8", "replace")

    def value(name, pattern, source):
        match = re.search(pattern, source, re.MULTILINE)
        if not match:
            raise RuntimeError(f"Missing telemetry field: {name}")
        return int(match.group(1))

    pid = read("shell", "pidof", PACKAGE).strip()
    if not pid.isdigit() or int(pid) <= 0:
        raise RuntimeError("App process is missing or ambiguous")
    status = read("shell", "run-as", PACKAGE, "cat", f"/proc/{pid}/status")
    descriptors = read("shell", "run-as", PACKAGE, "ls", f"/proc/{pid}/fd").split()
    if not descriptors or any(not item.isdigit() for item in descriptors):
        raise RuntimeError("Cannot read application file descriptors")
    memory = read("shell", "dumpsys", "meminfo", PACKAGE)
    battery = read("shell", "dumpsys", "battery")
    service = read("shell", "dumpsys", "activity", "services", PACKAGE)
    camera = read("shell", "dumpsys", "media.camera")
    if "isForeground=true" not in service:
        raise RuntimeError("Camera foreground service is missing")
    power_states = re.findall(r"(?:AC|USB|Wireless|Dock) powered:\s*(true|false)", battery)
    if not power_states:
        raise RuntimeError("Missing telemetry field: external power")
    result = {
        "time": utc(), "pid": int(pid),
        "threads": value("threads", r"^Threads:\s+(\d+)", status),
        "rss_kb": value("RSS", r"^VmRSS:\s+(\d+)", status),
        "pss_kb": value("PSS", r"TOTAL PSS:\s*(\d+)", memory),
        "fd_count": len(descriptors),
        "battery_percent": value("battery level", r"^\s+level:\s+(\d+)", battery),
        "temperature_c": value("battery temperature", r"^\s+temperature:\s+(-?\d+)", battery) / 10,
        "powered": "true" in power_states,
        "camera_active": camera_from_dump(camera), "camera_fgs": True,
        "collection_seconds": round(time.monotonic() - started, 3),
    }
    if result["collection_seconds"] > TELEMETRY_TIMEOUT:
        raise TimeoutError("Telemetry collection exceeded its time budget")
    if any(result[field] <= 0 for field in ("threads", "rss_kb", "pss_kb")) or not 0 <= result["battery_percent"] <= 100:
        raise RuntimeError("Telemetry contains invalid process or battery measurements")
    return result


def progress(path):
    """Return the last complete FFmpeg progress block, never a partial mixed block."""
    if not path.exists():
        return {}
    with path.open("rb") as file:
        offset = max(0, file.seek(0, os.SEEK_END) - 8192)
        file.seek(offset)
        text = file.read().decode("utf-8", "replace")
    if offset:
        text = text.partition("\n")[2]
    if not text.endswith("\n"):
        text = text.rpartition("\n")[0] + "\n"
    result, block = {}, {}
    for line in text.splitlines():
        if "=" not in line:
            continue
        key, value = line.split("=", 1)
        if key in ("frame", "out_time_us", "fps", "speed"):
            block[key] = value.strip()
        elif key == "progress":
            if value in ("continue", "end") and "frame" in block and "out_time_us" in block:
                result = {**block, "progress": value}
            block = {}
    return result


def decode_counters(output):
    if not output:
        return 0, 0
    try:
        frames = int(output["frame"])
        media_us = 0 if output["out_time_us"] == "N/A" and frames == 0 else int(output["out_time_us"])
    except (KeyError, ValueError) as error:
        raise RuntimeError("Invalid FFmpeg progress counters") from error
    if frames < 0 or media_us < 0:
        raise RuntimeError("Negative FFmpeg progress counters")
    return frames, media_us


class RunClock:
    """Check both clocks, including suspend on either OS monotonic-clock model."""
    def __init__(self, monotonic=None, wall=None):
        self.monotonic = monotonic or time.monotonic
        self.wall = wall or time.time
        self.start = self.previous = self.monotonic()
        self.wall_start = self.wall_previous = self.wall()
        self.max_checkpoint_gap = 0.0

    def check(self):
        current, wall = self.monotonic(), self.wall()
        delta, wall_delta = current - self.previous, wall - self.wall_previous
        self.max_checkpoint_gap = max(self.max_checkpoint_gap, delta, wall_delta)
        if delta < 0 or abs(wall_delta - delta) > 5:
            raise RuntimeError("Host sleep or a wall-clock discontinuity invalidated continuous monitoring")
        if max(delta, wall_delta) > MONITOR_GAP_SECONDS:
            raise RuntimeError("Monitoring heartbeat gap exceeded 30 seconds; unobserved time cannot pass")
        self.previous, self.wall_previous = current, wall

    def elapsed(self):
        return self.monotonic() - self.start

    def wall_elapsed(self):
        return self.wall() - self.wall_start


class HostWakeLock:
    """Keep Windows running without changing its saved power or display settings."""
    def __init__(self):
        self.active = False

    def acquire(self):
        if os.name == "nt":
            if not ctypes.windll.kernel32.SetThreadExecutionState(0x80000001):
                raise RuntimeError("Cannot prevent automatic Windows sleep during monitoring")
            self.active = True

    def release(self):
        if self.active:
            self.active = False
            if not ctypes.windll.kernel32.SetThreadExecutionState(0x80000000):
                raise RuntimeError("Cannot restore Windows execution state")


def atomic_status(path, summary):
    temporary = path.with_suffix(".tmp")
    with temporary.open("w", encoding="utf-8") as file:
        json.dump(summary, file, indent=2)
        file.flush()
        os.fsync(file.fileno())
    os.replace(temporary, path)


def append_record(path, entry):
    with path.open("a", encoding="utf-8") as file:
        file.write(json.dumps(entry) + "\n")
        file.flush()
        os.fsync(file.fileno())


def reserve_directory(directory):
    directory.mkdir(parents=True, exist_ok=True)
    if any((directory / name).exists() for name in ("status.json", "samples.jsonl", "segments.jsonl", "STOP")) or any(directory.glob("decode-*")):
        raise FileExistsError("Run directory contains earlier evidence or STOP; choose a new directory")
    # Exclusive creation also prevents two new runners from mixing their evidence.
    with (directory / "run.lock").open("x", encoding="utf-8") as file:
        file.write(json.dumps({"runner_pid": os.getpid(), "created_at": utc()}))
        file.flush()
        os.fsync(file.fileno())


def inspect_status(directory, now=None):
    """Read-only recovery of monitoring, not resumption of a stopped test."""
    path = directory / "status.json"
    if not path.exists():
        return {"status": "NO_STATUS", "directory": str(directory)}
    result = json.loads(path.read_text(encoding="utf-8"))
    updated = result.get("updated_at_epoch")
    if updated is None:
        updated = datetime.fromisoformat(result["updated_at"]).timestamp()
    age = (time.time() if now is None else now) - updated
    result["recorded_status"] = result["status"]
    result["heartbeat_age_seconds"] = round(age, 3)
    if result["status"] == "RUNNING" and (age < -5 or age > result.get("stale_after_seconds", STALE_AFTER_SECONDS)):
        result["status"] = "STALE"
        result["monitoring_note"] = "Runner progress is unverified. Do not count stale time or reuse this directory."
    return result


def stop_process(child):
    """Always reap and close stdin, preserving whether a force kill was needed."""
    forced, method = False, "already_exited"
    try:
        if child.poll() is None:
            method = "stdin_quit"
            try:
                child.stdin.write(b"q\n")
                child.stdin.flush()
            except (BrokenPipeError, OSError):
                pass  # An exit between poll and write is still reaped and recorded below.
            try:
                child.wait(timeout=10)
            except subprocess.TimeoutExpired:
                forced, method = True, "kill"
                child.kill()
                child.wait(timeout=5)
        else:
            child.wait(timeout=5)
        return {"returncode": child.returncode, "forced_kill": forced, "stop_method": method}
    finally:
        if child.stdin:
            try:
                child.stdin.close()
            except OSError:
                pass


def segment_failure(record):
    if record["stop_reason"] != "duration_reached":
        return "Decoder did not complete its requested wall-clock interval"
    if record["forced_kill"] or record["returncode"] != 0:
        return f"FFmpeg did not stop cleanly (exit {record['returncode']}, forced={record['forced_kill']})"
    if record["stop_method"] != "stdin_quit":
        return "FFmpeg exited before the runner requested shutdown"
    if record["elapsed_seconds"] < record["requested_wall_seconds"]:
        return "FFmpeg segment ended before its requested wall-clock interval"
    if record["progress"].get("progress") != "end":
        return "FFmpeg did not write a complete final progress block"
    frames, media_us = decode_counters(record["progress"])
    if frames <= 0 or media_us <= 0:
        return "Decode segment produced no timed video frames"
    return None


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host")
    parser.add_argument("--port", type=int, default=8554)
    parser.add_argument("--hours", type=float, default=24)
    parser.add_argument("--directory", type=Path, default=ROOT / "artifacts/soak")
    parser.add_argument("--sample-seconds", type=int, default=60)
    parser.add_argument("--stream-seconds", type=int, default=3300)
    parser.add_argument("--idle-seconds", type=int, default=60)
    parser.add_argument("--status", action="store_true", help="Read existing status without ADB, FFmpeg, or writes")
    args = parser.parse_args(argv)
    directory = args.directory.resolve()
    if args.status:
        print(json.dumps(inspect_status(directory), indent=2))
        return
    if not args.host:
        parser.error("--host is required when starting a run")
    if not math.isfinite(args.hours) or args.hours <= 0 or args.sample_seconds < 1 or args.stream_seconds < 5 or args.idle_seconds < 1 or not 1 <= args.port <= 65535:
        parser.error("Invalid duration, sampling interval, stream interval, idle interval, or port")
    ffmpeg = shutil.which("ffmpeg")
    if not ffmpeg:
        parser.error("FFmpeg is required")
    reserve_directory(directory)
    summary_path = directory / "status.json"
    samples_path = directory / "samples.jsonl"
    segments_path = directory / "segments.jsonl"
    clock = RunClock()
    summary = {
        "schema_version": 2, "run_id": uuid.uuid4().hex, "status": "RUNNING", "runner_pid": os.getpid(),
        "started_at": utc(), "measurement_started_at": None, "requested_hours": args.hours,
        "host": args.host, "port": args.port, "sample_seconds": args.sample_seconds,
        "stream_seconds": args.stream_seconds, "idle_seconds": args.idle_seconds,
        "stale_after_seconds": STALE_AFTER_SECONDS, "samples": 0, "telemetry_failures": 0,
        "completed_stream_segments": 0, "cold_reconnections": 0, "decoded_frames": 0,
        "max_cold_rtp_ms": 0, "phase": "starting", "active_segment": None,
    }
    child, active_segment, active_progress = None, None, None
    initial_pid = None
    previous_sample_at = time.monotonic()
    previous_save_at = float("-inf")
    wake_lock = HostWakeLock()

    def save():
        nonlocal previous_save_at
        summary["updated_at"] = utc()
        summary["updated_at_epoch"] = time.time()
        measuring = summary["measurement_started_at"] is not None
        summary["elapsed_seconds"] = round(clock.elapsed(), 3) if measuring else 0
        summary["wall_elapsed_seconds"] = round(clock.wall_elapsed(), 3) if measuring else 0
        summary["max_checkpoint_gap_seconds"] = round(clock.max_checkpoint_gap, 3)
        atomic_status(summary_path, summary)
        previous_save_at = time.monotonic()

    def pulse():
        clock.check()
        if (directory / "STOP").exists():
            raise InterruptedError("Stopped by request")
        if time.monotonic() - previous_save_at >= HEARTBEAT_SECONDS:
            save()

    def sample(phase, output=None, expect_idle=False):
        nonlocal initial_pid, previous_sample_at
        entry = {"time": utc(), "phase": phase}
        if output:
            entry["decode"] = output
        try:
            entry.update(telemetry())
            pulse()  # Detect time lost inside telemetry too, including the final sample.
            now = time.monotonic()
            if now - previous_sample_at > args.sample_seconds * 2 + 30:
                raise RuntimeError("Telemetry gap exceeded the limit")
            if initial_pid is None:
                initial_pid = entry["pid"]
                summary["app_pid"] = initial_pid
            if entry["pid"] != initial_pid:
                raise RuntimeError("App process restarted during the soak test")
            if expect_idle and entry["camera_active"]:
                raise RuntimeError("Camera remained active without a test client")
        except BaseException as error:
            entry["error"] = str(error)
            summary["telemetry_failures"] += 1
            summary["last_sample"] = entry
            append_record(samples_path, entry)
            save()
            raise
        previous_sample_at = time.monotonic()
        entry["elapsed_seconds"] = round(clock.elapsed(), 3) if summary["measurement_started_at"] else None
        append_record(samples_path, entry)
        summary["samples"] += 1
        summary["last_sample"] = entry
        summary["phase"] = phase
        save()
        return entry

    def wait_camera(expected, timeout=2):
        limit = time.monotonic() + timeout
        while True:
            pulse()
            remaining = limit - time.monotonic()
            if remaining <= 0:
                raise RuntimeError(f"Camera active did not become {expected} within {timeout}s")
            dump = adb("shell", "dumpsys", "media.camera", timeout=remaining).decode("utf-8", "replace")
            if camera_from_dump(dump) == expected:
                pulse()
                return
            time.sleep(min(.1, max(0, limit - time.monotonic())))

    def finish_child(reason):
        nonlocal child, active_segment, active_progress
        if child is None:
            return None
        result = stop_process(child)
        record = {**active_segment, **result, "stop_reason": reason, "finished_at": utc(),
                  "elapsed_seconds": time.monotonic() - active_segment["started_monotonic"],
                  "progress": progress(active_progress)}
        record.pop("started_monotonic")
        try:
            error = segment_failure(record)
        except RuntimeError as invalid_progress:
            error = str(invalid_progress)
        record["status"] = "FAILED" if error else "COMPLETED"
        if error:
            record["error"] = error
        append_record(segments_path, record)
        summary["last_segment"] = record
        summary["active_segment"] = None
        child, active_segment, active_progress = None, None, None
        save()
        return record

    save()
    try:
        wake_lock.acquire()
        summary["host_sleep_prevention"] = "windows_execution_state" if wake_lock.active else "not_available"
        apk = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
        summary["apk_sha256"] = hashlib.sha256(apk.read_bytes()).hexdigest()
        summary["apk_hash_source"] = "local build file; installed artifact identity is not verified"
        wait_camera(False)
        sample("baseline_idle", expect_idle=True)
        # The requested duration starts with a successful baseline, not APK/preflight work.
        clock = RunClock()
        deadline = clock.start + args.hours * 3600
        summary["measurement_started_at"] = utc()
        save()
        while time.monotonic() < deadline:
            pulse()
            segment = summary["completed_stream_segments"] + 1
            progress_path = directory / f"decode-{segment:03}.progress"
            log_path = directory / f"decode-{segment:03}.log"
            transport = "tcp" if segment % 2 else "udp"
            with progress_path.open("wb") as output, log_path.open("wb") as errors:
                command = [ffmpeg, "-hide_banner", "-loglevel", "warning", "-xerror", "-nostats", "-progress", "pipe:1",
                           "-rtsp_transport", transport, "-timeout", "15000000", "-i", f"rtsp://{args.host}:{args.port}/live",
                           "-an", "-f", "null", "-"]
                started = time.monotonic()
                seconds = min(args.stream_seconds, max(0, deadline - started))
                if seconds <= 0:
                    break
                active_segment = {"index": segment, "transport": transport, "started_at": utc(),
                                  "started_monotonic": started, "requested_wall_seconds": seconds,
                                  "progress_file": progress_path.name, "log_file": log_path.name}
                active_progress = progress_path
                child = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=output, stderr=errors,
                                         creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
                active_segment["pid"] = child.pid
                summary["active_segment"] = active_segment.copy()
                summary["phase"] = f"streaming_{transport}"
                save()
                last_frame, last_media_us = 0, 0
                last_frame_advance = last_time_advance = time.monotonic()
                next_sample = time.monotonic() + min(5, args.sample_seconds)
                while time.monotonic() < started + seconds:
                    pulse()
                    code = child.poll()
                    if code is not None:
                        raise RuntimeError(f"FFmpeg segment {segment} exited early with code {code}; see its decode log")
                    latest = progress(progress_path)
                    frame, media_us = decode_counters(latest)
                    if frame < last_frame or media_us < last_media_us:
                        raise RuntimeError("Decoder progress counters regressed")
                    now = time.monotonic()
                    if frame > last_frame:
                        last_frame, last_frame_advance = frame, now
                    if media_us > last_media_us:
                        last_media_us, last_time_advance = media_us, now
                    if now - min(last_frame_advance, last_time_advance) > 60:
                        raise RuntimeError("Decoder frames or media time stopped advancing")
                    summary["active_segment"]["decode"] = latest
                    if now >= next_sample:
                        current = sample(f"streaming_{transport}", latest)
                        if child.poll() is not None:
                            raise RuntimeError(f"FFmpeg segment {segment} exited during telemetry; see its decode log")
                        if not current["camera_active"]:
                            raise RuntimeError("Camera closed while decoder was active")
                        next_sample = time.monotonic() + args.sample_seconds
                    time.sleep(min(.5, max(0, started + seconds - time.monotonic())))
                # -t measures media timestamps, not real elapsed time. This runner owns the deadline.
                record = finish_child("duration_reached")
                if record["status"] != "COMPLETED":
                    raise RuntimeError(record["error"])
                output.flush()
                errors.flush()
                os.fsync(output.fileno())
                os.fsync(errors.fileno())
                decoded, _ = decode_counters(record["progress"])
                summary["decoded_frames"] += decoded
                summary["completed_stream_segments"] += 1
                pulse()
                save()
            wait_camera(False)
            sample("idle", expect_idle=True)
            idle_end = min(deadline, time.monotonic() + args.idle_seconds)
            next_sample = time.monotonic() + args.sample_seconds
            while time.monotonic() < idle_end:
                pulse()
                if time.monotonic() >= next_sample:
                    sample("idle", expect_idle=True)
                    next_sample = time.monotonic() + args.sample_seconds
                time.sleep(min(.5, max(0, idle_end - time.monotonic())))
            if time.monotonic() >= deadline:
                break
            summary["phase"] = "cold_reconnect"
            save()
            started = time.monotonic()
            client = None
            succeeded = False
            try:
                client = RtspClient(args.host, args.port)
                client.socket.settimeout(5)
                client.play()
                latency = (time.monotonic() - started) * 1000
                if latency >= 5000:
                    raise RuntimeError("Cold RTP connection exceeded five seconds")
                summary["max_cold_rtp_ms"] = round(max(summary["max_cold_rtp_ms"], latency), 2)
                summary["cold_reconnections"] += 1
                succeeded = True
            finally:
                if client is not None:
                    client.close(teardown=succeeded and segment % 2 == 0)
            pulse()
            wait_camera(False)
            sample("reconnected_then_idle", expect_idle=True)
        sample("final_idle", expect_idle=True)
        if clock.elapsed() < args.hours * 3600 or summary["completed_stream_segments"] == 0:
            raise RuntimeError("Requested real elapsed duration was not completed")
        summary["status"] = "PASSED"
        summary["trend_review_required"] = True
        summary["phase"] = "complete"
    except BaseException as error:
        summary["status"] = "STOPPED" if isinstance(error, (KeyboardInterrupt, InterruptedError)) else "FAILED"
        summary["error"] = str(error)
        raise
    finally:
        try:
            if child is not None:
                finish_child("stopped" if summary["status"] == "STOPPED" else "failure")
        except BaseException as error:
            summary["status"] = "FAILED"
            summary["cleanup_error"] = str(error)
        try:
            wake_lock.release()
        except BaseException as error:
            summary["status"] = "FAILED"
            summary["cleanup_error"] = str(error)
        summary["finished_at"] = utc()
        save()


if __name__ == "__main__":
    main()
