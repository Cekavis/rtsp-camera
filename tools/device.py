"""Small ADB inspection helper. Does not print application preferences or credentials."""
import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ADB = str(Path(os.environ.get("LOCALAPPDATA", "")) / "Android/Sdk/platform-tools/adb.exe")
if not Path(ADB).exists():
    ADB = shutil.which("adb") or "adb"


def adb(*args, timeout=30):
    return subprocess.check_output([ADB, *args], timeout=timeout)


def tree():
    adb("shell", "uiautomator", "dump", "/sdcard/rtsp-camera-window.xml")
    return ET.fromstring(adb("shell", "cat", "/sdcard/rtsp-camera-window.xml"))


def tap(text):
    root = tree()
    for node in root.iter("node"):
        if node.get("text") == text or node.get("content-desc") == text:
            numbers = list(map(int, re.findall(r"\d+", node.get("bounds", ""))))
            if len(numbers) == 4 and numbers[2] > numbers[0] and numbers[3] > numbers[1]:
                adb("shell", "input", "tap", str((numbers[0] + numbers[2]) // 2), str((numbers[1] + numbers[3]) // 2))
                return
    raise RuntimeError(f"Visible control not found: {text}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=["dump", "tap", "screenshot"])
    parser.add_argument("value", nargs="?")
    args = parser.parse_args()
    if args.action == "dump":
        for node in tree().iter("node"):
            text = node.get("text") or node.get("content-desc")
            if text:
                print(node.get("bounds"), text)
    elif args.action == "tap":
        tap(args.value)
    else:
        target = Path(args.value or ROOT / "artifacts/captures/screen.png").resolve()
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(adb("exec-out", "screencap", "-p"))
        print(target)


if __name__ == "__main__":
    main()
