#!/usr/bin/env python3
"""
Rezvan Mesh - Integration Test Runner
Launches Android emulators, installs APK, and runs mesh simulation tests.
"""

import argparse
import subprocess
import sys
import time
import json
import os
from pathlib import Path
from typing import List, Optional

ROOT = Path(__file__).parent.parent
APK_PATH = ROOT / "android/app/build/outputs/apk/release/app-release.apk"
TEST_CASES_DIR = ROOT / "integration-tests/test_cases"

# Default emulator AVD names
DEFAULT_AVDS = [
    "RezvanMesh_1",
    "RezvanMesh_2",
    "RezvanMesh_3",
    "RezvanMesh_4",
    "RezvanMesh_5",
]

def run_command(cmd: List[str], capture: bool = True, timeout: int = 60) -> subprocess.CompletedProcess:
    """Run a shell command and return the result."""
    print(f"  $ {' '.join(cmd)}")
    return subprocess.run(cmd, capture_output=capture, text=True, timeout=timeout)

def get_connected_devices() -> List[str]:
    """Return list of connected Android device serials."""
    result = run_command(["adb", "devices"])
    lines = result.stdout.strip().split("\n")[1:]
    devices = [line.split()[0] for line in lines if line.strip() and "device" in line]
    return devices

def launch_emulator(avd_name: str) -> Optional[str]:
    """Launch an emulator and return its serial when ready."""
    print(f"Launching emulator: {avd_name}")
    subprocess.Popen(
        ["emulator", "-avd", avd_name, "-no-snapshot", "-no-window", "-no-audio"],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    # Wait for device to appear
    for _ in range(60):
        time.sleep(2)
        devices = get_connected_devices()
        for d in devices:
            if d.startswith("emulator-"):
                # Check boot completed
                result = run_command(["adb", "-s", d, "shell", "getprop", "sys.boot_completed"])
                if result.stdout.strip() == "1":
                    print(f"  Emulator ready: {d}")
                    return d
    print(f"  Timeout waiting for {avd_name}")
    return None

def install_apk(device: str) -> bool:
    """Install APK on target device."""
    if not APK_PATH.exists():
        print(f"Error: APK not found at {APK_PATH}")
        return False
    result = run_command(["adb", "-s", device, "install", "-r", str(APK_PATH)])
    return "Success" in result.stdout

def start_app(device: str) -> bool:
    """Launch the Rezvan Mesh app."""
    result = run_command([
        "adb", "-s", device, "shell", "am", "start",
        "-n", "com.rezvani.mesh/.MainActivity"
    ])
    return "Starting" in result.stdout or result.returncode == 0

def stop_app(device: str) -> None:
    """Force stop the app."""
    run_command(["adb", "-s", device, "shell", "am", "force-stop", "com.rezvani.mesh"])

def clear_app_data(device: str) -> None:
    """Clear app data."""
    run_command(["adb", "-s", device, "shell", "pm", "clear", "com.rezvani.mesh"])

def grant_permissions(device: str) -> None:
    """Grant required permissions."""
    permissions = [
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.ACCESS_COARSE_LOCATION",
        "android.permission.BLUETOOTH",
        "android.permission.BLUETOOTH_ADMIN",
        "android.permission.BLUETOOTH_SCAN",
        "android.permission.BLUETOOTH_ADVERTISE",
        "android.permission.BLUETOOTH_CONNECT",
    ]
    for perm in permissions:
        run_command(["adb", "-s", device, "shell", "pm", "grant", "com.rezvani.mesh", perm])

def send_test_broadcast(device: str, action: str, extras: dict = None) -> None:
    """Send a test broadcast to the app."""
    cmd = ["adb", "-s", device, "shell", "am", "broadcast", "-a", action]
    if extras:
        for key, value in extras.items():
            cmd.extend(["--es", key, str(value)])
    run_command(cmd)

def get_logs(device: str, tag: str = "RezvanMesh") -> str:
    """Get filtered logcat output."""
    result = run_command(["adb", "-s", device, "logcat", "-d", "-s", tag])
    return result.stdout

def run_test_case(devices: List[str], test_file: Path) -> bool:
    """Run a single test case script."""
    print(f"\n--- Running test: {test_file.name} ---")
    env = os.environ.copy()
    env["REZVAN_DEVICES"] = ",".join(devices)
    result = subprocess.run(
        [sys.executable, str(test_file)],
        env=env,
        capture_output=True,
        text=True,
        timeout=300,
    )
    print(result.stdout)
    if result.returncode != 0:
        print(f"FAILED: {test_file.name}")
        print(result.stderr)
        return False
    print(f"PASSED: {test_file.name}")
    return True

def main():
    parser = argparse.ArgumentParser(description="Rezvan Mesh Integration Test Runner")
    parser.add_argument("--avds", nargs="+", default=DEFAULT_AVDS, help="AVD names to use")
    parser.add_argument("--devices", nargs="+", help="Use specific device serials (skip emulator launch)")
    parser.add_argument("--apk", default=str(APK_PATH), help="Path to APK")
    parser.add_argument("--test", help="Run specific test file")
    parser.add_argument("--skip-install", action="store_true", help="Skip APK installation")
    args = parser.parse_args()

    global APK_PATH
    APK_PATH = Path(args.apk)

    devices = args.devices
    if not devices:
        print("Launching emulators...")
        devices = []
        for avd in args.avds:
            dev = launch_emulator(avd)
            if dev:
                devices.append(dev)
        if not devices:
            print("No devices available.")
            return 1

    print(f"Devices: {devices}")

    if not args.skip_install:
        print("Installing APK...")
        for dev in devices:
            if not install_apk(dev):
                print(f"Failed to install on {dev}")
                return 1
            grant_permissions(dev)
            clear_app_data(dev)

    print("Starting app on all devices...")
    for dev in devices:
        start_app(dev)
    time.sleep(5)

    # Determine test files
    if args.test:
        test_files = [Path(args.test)]
    else:
        test_files = sorted(TEST_CASES_DIR.glob("test_*.py"))

    passed = 0
    failed = 0
    for test_file in test_files:
        if run_test_case(devices, test_file):
            passed += 1
        else:
            failed += 1

    print(f"\n========================================")
    print(f"Results: {passed} passed, {failed} failed")
    print(f"========================================")

    # Cleanup
    for dev in devices:
        stop_app(dev)

    return 0 if failed == 0 else 1

if __name__ == "__main__":
    sys.exit(main())
