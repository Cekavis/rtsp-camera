"""Offline reliability tests. Device, network and subprocess access are forbidden."""
from contextlib import ExitStack, redirect_stdout
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import soak_test as soak


CAMERA_IDLE = "Active Camera Clients:\nAllowed user IDs: 0\n"
CAMERA_ACTIVE = "Active Camera Clients:\ncom.cekavis.rtspcamera\nAllowed user IDs: 0\n"
TELEMETRY = [
    b"1234\n", b"VmRSS:\t150000 kB\nThreads:\t39\n", b"0\n1\n2\n3\n",
    b" TOTAL PSS: 123456 TOTAL RSS: 150000\n",
    b"  AC powered: false\n  USB powered: true\n  level: 80\n  temperature: 345\n",
    b"isForeground=true\n", CAMERA_IDLE.encode(),
]


class FakeClock:
    def __init__(self):
        self.value = 1000.0

    def monotonic(self):
        return self.value

    def wall(self):
        return 1800000000 + self.value

    def sleep(self, duration):
        self.value += duration


class OfflineTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.guards = ExitStack()
        self.addCleanup(self.guards.close)
        for target in ("soak_test.adb", "device.adb", "verify_rtsp.adb", "subprocess.Popen", "socket.create_connection"):
            self.guards.enter_context(patch(target, side_effect=AssertionError("Offline test attempted external access")))

    def test_telemetry_preserves_real_measurements(self):
        with patch.object(soak, "adb", side_effect=TELEMETRY) as adb:
            result = soak.telemetry()
        self.assertEqual((result["pid"], result["threads"], result["fd_count"]), (1234, 39, 4))
        self.assertEqual(result["pss_kb"], 123456)
        self.assertEqual(result["temperature_c"], 34.5)
        self.assertTrue(result["powered"])
        self.assertFalse(result["camera_active"])
        self.assertTrue(all(0 < call.kwargs["timeout"] <= 10 for call in adb.call_args_list))

    def test_missing_measurements_cannot_become_zero_or_pass(self):
        for index, replacement in ((0, b""), (1, b"VmRSS: 150000\n"), (2, b"permission denied"),
                                   (3, b"No PSS"), (4, b"  USB powered: true\n  level: 80\n"),
                                   (5, b"isForeground=false"), (6, b"unrecognized output")):
            with self.subTest(index=index):
                values = TELEMETRY.copy()
                values[index] = replacement
                with patch.object(soak, "adb", side_effect=values), self.assertRaises(RuntimeError):
                    soak.telemetry()

    def test_telemetry_total_budget_is_bounded(self):
        clock = FakeClock()
        def read(*args, **kwargs):
            clock.value += 8
            return TELEMETRY[read.index()]
        indices = iter(range(len(TELEMETRY)))
        read.index = lambda: next(indices)
        with patch.object(soak.time, "monotonic", clock.monotonic), patch.object(soak, "adb", side_effect=read), self.assertRaises(TimeoutError):
            soak.telemetry()

    def test_camera_dump_does_not_count_inactive_history(self):
        self.assertFalse(soak.camera_from_dump(CAMERA_IDLE + soak.PACKAGE))
        self.assertTrue(soak.camera_from_dump(CAMERA_ACTIVE))

    def test_progress_reads_only_complete_blocks(self):
        path = self.directory / "progress"
        path.write_text("frame=2\nout_time_us=60000\nprogress=continue\nframe=9\nout_time_us=90000\nprogress=en", encoding="utf-8")
        self.assertEqual(soak.progress(path), {"frame": "2", "out_time_us": "60000", "progress": "continue"})
        with path.open("a") as file:
            file.write("d\n")
        self.assertEqual(soak.progress(path)["frame"], "9")
        self.assertEqual(soak.progress(path)["progress"], "end")

    def test_progress_does_not_merge_incomplete_blocks(self):
        path = self.directory / "progress"
        path.write_text("frame=3\nprogress=continue\nout_time_us=90000\nprogress=end\n", encoding="utf-8")
        self.assertEqual(soak.progress(path), {})
        path.write_text("x" * 10000 + "\nframe=4\nout_time_us=80000\nprogress=end\n", encoding="utf-8")
        self.assertEqual(soak.decode_counters(soak.progress(path)), (4, 80000))

    def test_progress_invalid_or_regressed_counter_data_rejected(self):
        self.assertEqual(soak.decode_counters({"frame": "0", "out_time_us": "N/A"}), (0, 0))
        for item in ({"frame": "-1", "out_time_us": "0"}, {"frame": "1", "out_time_us": "N/A"}, {"frame": "1", "out_time_us": "-1"}):
            with self.subTest(item=item), self.assertRaises(RuntimeError):
                soak.decode_counters(item)

    def test_run_clock_accepts_observed_real_elapsed_time(self):
        clock = FakeClock()
        run = soak.RunClock(clock.monotonic, clock.wall)
        for _ in range(12):
            clock.sleep(5)
            run.check()
        self.assertEqual(run.elapsed(), 60)
        self.assertEqual(run.wall_elapsed(), 60)
        self.assertEqual(run.max_checkpoint_gap, 5)

    def test_run_clock_rejects_sleep_for_both_monotonic_models(self):
        for monotonic_delta, wall_delta in ((61, 61), (0, 61), (-1, 0), (1, -59)):
            with self.subTest(monotonic_delta=monotonic_delta, wall_delta=wall_delta):
                mono, wall = iter((0, monotonic_delta)), iter((100, 100 + wall_delta))
                run = soak.RunClock(lambda: next(mono), lambda: next(wall))
                with self.assertRaises(RuntimeError):
                    run.check()

    def test_stop_process_reaps_and_closes_stdin(self):
        child = Mock(returncode=0)
        child.poll.return_value = None
        result = soak.stop_process(child)
        self.assertEqual(result, {"returncode": 0, "forced_kill": False, "stop_method": "stdin_quit"})
        child.stdin.write.assert_called_once_with(b"q\n")
        child.wait.assert_called_once_with(timeout=10)
        child.stdin.close.assert_called_once()
        child.kill.assert_not_called()

    def test_stop_process_records_forced_kill_and_reaps(self):
        child = Mock(returncode=-9)
        child.poll.return_value = None
        child.wait.side_effect = [subprocess.TimeoutExpired("ffmpeg", 10), -9]
        result = soak.stop_process(child)
        self.assertTrue(result["forced_kill"])
        self.assertEqual(result["returncode"], -9)
        child.kill.assert_called_once()
        self.assertEqual(child.wait.call_count, 2)
        child.stdin.close.assert_called_once()

    def test_segment_rejects_early_clean_exit_and_incomplete_evidence(self):
        valid = {"stop_reason": "duration_reached", "forced_kill": False, "returncode": 0,
                 "stop_method": "stdin_quit", "elapsed_seconds": 60, "requested_wall_seconds": 60,
                 "progress": {"progress": "end", "frame": "900", "out_time_us": "60000000"}}
        self.assertIsNone(soak.segment_failure(valid))
        for override in ({"stop_method": "already_exited"}, {"stop_reason": "failure"}, {"returncode": 1},
                         {"forced_kill": True}, {"elapsed_seconds": 59.99},
                         {"progress": {"progress": "continue", "frame": "900", "out_time_us": "60000000"}},
                         {"progress": {"progress": "end", "frame": "0", "out_time_us": "0"}}):
            with self.subTest(override=override):
                self.assertIsNotNone(soak.segment_failure({**valid, **override}))

    def test_directory_reuse_cannot_mix_or_overwrite_evidence(self):
        for name in ("status.json", "samples.jsonl", "segments.jsonl", "STOP", "decode-001.log"):
            with self.subTest(name=name):
                directory = self.directory / name.replace(".", "_")
                directory.mkdir()
                (directory / name).write_text("existing evidence")
                with self.assertRaises(FileExistsError):
                    soak.reserve_directory(directory)
                self.assertEqual((directory / name).read_text(), "existing evidence")
        directory = self.directory / "new"
        soak.reserve_directory(directory)
        with self.assertRaises(FileExistsError):
            soak.reserve_directory(directory)

    def test_status_reports_stale_without_mutating_recorded_state(self):
        path = self.directory / "status.json"
        summary = {"status": "RUNNING", "updated_at_epoch": 100, "active_segment": {"pid": 321}}
        soak.atomic_status(path, summary)
        original = path.read_bytes()
        self.assertEqual(soak.inspect_status(self.directory, 110)["status"], "RUNNING")
        result = soak.inspect_status(self.directory, 146)
        self.assertEqual(result["status"], "STALE")
        self.assertEqual(result["recorded_status"], "RUNNING")
        self.assertEqual(result["active_segment"]["pid"], 321)
        self.assertEqual(path.read_bytes(), original)
        soak.atomic_status(path, {**summary, "status": "PASSED"})
        self.assertEqual(soak.inspect_status(self.directory, 1000000)["status"], "PASSED")

    def test_status_cli_never_touches_device_or_starts_process(self):
        before = list(self.directory.iterdir())
        output = io.StringIO()
        with patch.object(soak.shutil, "which", side_effect=AssertionError("Status invoked FFmpeg discovery")), redirect_stdout(output):
            soak.main(["--status", "--directory", str(self.directory)])
        self.assertEqual(json.loads(output.getvalue())["status"], "NO_STATUS")
        self.assertEqual(list(self.directory.iterdir()), before)

    def test_windows_sleep_prevention_is_temporary_and_does_not_hold_display(self):
        api = Mock(return_value=0x80000000)
        windll = Mock()
        windll.kernel32.SetThreadExecutionState = api
        with patch.object(soak.os, "name", "nt"), patch.object(soak.ctypes, "windll", windll, create=True):
            wake = soak.HostWakeLock()
            wake.acquire()
            self.assertTrue(wake.active)
            wake.release()
            wake.release()
        self.assertEqual([call.args[0] for call in api.call_args_list], [0x80000001, 0x80000000])

    def test_windows_sleep_prevention_failure_is_not_silent(self):
        windll = Mock()
        windll.kernel32.SetThreadExecutionState.return_value = 0
        with patch.object(soak.os, "name", "nt"), patch.object(soak.ctypes, "windll", windll, create=True):
            wake = soak.HostWakeLock()
            with self.assertRaises(RuntimeError):
                wake.acquire()
            self.assertFalse(wake.active)

    def run_fake_main(self, early_exit=False, final_gap=False, request_stop=False):
        """Exercise the runner with real artifact files and fully simulated I/O."""
        clock = FakeClock()
        root = self.directory / "project"
        apk = root / "app/build/outputs/apk/debug/app-debug.apk"
        apk.parent.mkdir(parents=True)
        apk.write_bytes(b"offline test artifact")
        directory = self.directory / "run"
        child = Mock(pid=222, returncode=None)
        output_handle = None
        process_started = False
        polls = 0
        samples = 0
        command = None

        def popen(args, **kwargs):
            nonlocal output_handle, process_started, command
            command = args
            output_handle = kwargs["stdout"]
            process_started = True
            return child

        def poll():
            nonlocal polls
            polls += 1
            if early_exit:
                child.returncode = 0
                return 0
            if child.returncode is None:
                # Popen owns an inherited handle even after the parent's with block exits.
                with Path(output_handle.name).open("ab") as writer:
                    writer.write(f"frame={polls}\nout_time_us={polls * 10000}\nprogress=continue\n".encode())
            return child.returncode

        def wait(timeout):
            child.returncode = 0
            with Path(output_handle.name).open("ab") as writer:
                writer.write(b"frame=100\nout_time_us=5000000\nprogress=end\n")
            clock.sleep(.25)
            return 0

        def sample():
            nonlocal samples
            samples += 1
            # Preflight time must not be included in the measured interval.
            if samples == 1:
                clock.sleep(2)
            active = process_started and child.returncode is None
            if final_gap and process_started and not active:
                clock.sleep(61)
            if request_stop and active:
                (directory / "STOP").touch()
            return {"pid": 1234, "camera_active": active, "camera_fgs": True}

        child.poll.side_effect = poll
        child.wait.side_effect = wait
        wake = Mock(active=True)
        with ExitStack() as stack:
            for target, value in (("ROOT", root), ("telemetry", sample)):
                stack.enter_context(patch.object(soak, target, value))
            stack.enter_context(patch.object(soak, "adb", return_value=CAMERA_IDLE.encode()))
            stack.enter_context(patch.object(soak.shutil, "which", return_value="offline-ffmpeg"))
            stack.enter_context(patch.object(soak.subprocess, "Popen", side_effect=popen))
            stack.enter_context(patch.object(soak, "HostWakeLock", return_value=wake))
            stack.enter_context(patch.object(soak.time, "monotonic", clock.monotonic))
            stack.enter_context(patch.object(soak.time, "time", clock.wall))
            stack.enter_context(patch.object(soak.time, "sleep", clock.sleep))
            error = None
            try:
                soak.main(["--host", "offline.invalid", "--hours", str(5 / 3600), "--directory", str(directory),
                           "--sample-seconds", "1", "--stream-seconds", "5", "--idle-seconds", "1"])
            except BaseException as failure:
                error = failure
        wake.acquire.assert_called_once()
        wake.release.assert_called_once()
        child.stdin.close.assert_called_once()
        summary = json.loads((directory / "status.json").read_text())
        segments = [json.loads(line) for line in (directory / "segments.jsonl").read_text().splitlines()]
        return summary, segments, command, error

    def test_full_runner_measures_elapsed_time_and_records_clean_shutdown(self):
        summary, segments, command, error = self.run_fake_main()
        self.assertIsNone(error)
        self.assertEqual(summary["status"], "PASSED")
        self.assertGreaterEqual(summary["elapsed_seconds"], 5)
        self.assertLess(summary["elapsed_seconds"], 6)
        self.assertEqual(summary["completed_stream_segments"], 1)
        self.assertEqual(segments[0]["status"], "COMPLETED")
        self.assertEqual(segments[0]["returncode"], 0)
        self.assertEqual(segments[0]["stop_reason"], "duration_reached")
        self.assertEqual(segments[0]["pid"], 222)
        self.assertNotIn("-t", command)
        self.assertIn("-xerror", command)
        self.assertIsNone(summary["active_segment"])

    def test_full_runner_records_early_zero_exit_as_failure(self):
        summary, segments, _, error = self.run_fake_main(early_exit=True)
        self.assertIsInstance(error, RuntimeError)
        self.assertIn("exited early with code 0", str(error))
        self.assertEqual(summary["status"], "FAILED")
        self.assertEqual(summary["completed_stream_segments"], 0)
        self.assertEqual(segments[0]["status"], "FAILED")
        self.assertEqual(segments[0]["returncode"], 0)
        self.assertEqual(segments[0]["stop_method"], "already_exited")

    def test_final_telemetry_sleep_cannot_convert_to_pass(self):
        summary, _, _, error = self.run_fake_main(final_gap=True)
        self.assertIsInstance(error, RuntimeError)
        self.assertEqual(summary["status"], "FAILED")
        self.assertEqual(summary["telemetry_failures"], 1)
        self.assertIn("gap", summary["last_sample"]["error"])

    def test_stop_request_still_reaps_decoder_and_restores_sleep_policy(self):
        summary, segments, _, error = self.run_fake_main(request_stop=True)
        self.assertIsInstance(error, InterruptedError)
        self.assertEqual(summary["status"], "STOPPED")
        self.assertEqual(segments[0]["stop_reason"], "stopped")
        self.assertEqual(segments[0]["status"], "FAILED")


if __name__ == "__main__":
    unittest.main()
