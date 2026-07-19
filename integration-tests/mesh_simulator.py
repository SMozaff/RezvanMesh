#!/usr/bin/env python3
"""
Rezvan Mesh - Mesh Simulator Core
Provides utilities for managing multiple Android devices and simulating mesh traffic.
"""

import subprocess
import time
import json
import re
import os
from typing import List, Dict, Optional, Tuple
from dataclasses import dataclass
from datetime import datetime

@dataclass
class MeshNode:
    """Represents a single mesh node (Android device/emulator)."""
    serial: str
    node_id: Optional[str] = None
    ip_address: Optional[str] = None

class MeshSimulator:
    """Manages a set of Android devices for mesh testing."""

    def __init__(self, devices: List[str]):
        self.nodes = [MeshNode(serial=d) for d in devices]
        self.logs: Dict[str, List[str]] = {d: [] for d in devices}

    def run_adb(self, device: str, cmd: List[str], timeout: int = 30) -> Tuple[int, str, str]:
        """Run ADB command on a specific device."""
        full_cmd = ["adb", "-s", device] + cmd
        proc = subprocess.run(full_cmd, capture_output=True, text=True, timeout=timeout)
        return proc.returncode, proc.stdout, proc.stderr

    def wait_for_boot(self, timeout: int = 120) -> bool:
        """Wait for all devices to complete boot."""
        for node in self.nodes:
            for _ in range(timeout):
                code, out, _ = self.run_adb(node.serial, ["shell", "getprop", "sys.boot_completed"])
                if out.strip() == "1":
                    break
                time.sleep(1)
            else:
                print(f"Device {node.serial} boot timeout")
                return False
        return True

    def install_apk(self, apk_path: str) -> bool:
        """Install APK on all devices."""
        for node in self.nodes:
            code, out, err = self.run_adb(node.serial, ["install", "-r", apk_path])
            if "Success" not in out:
                print(f"Failed to install on {node.serial}: {err}")
                return False
            print(f"Installed on {node.serial}")
        return True

    def grant_permissions(self) -> None:
        """Grant required permissions on all devices."""
        permissions = [
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.BLUETOOTH_SCAN",
            "android.permission.BLUETOOTH_ADVERTISE",
            "android.permission.BLUETOOTH_CONNECT",
        ]
        for node in self.nodes:
            for perm in permissions:
                self.run_adb(node.serial, ["shell", "pm", "grant", "com.rezvani.mesh", perm])

    def start_app(self) -> None:
        """Start the Rezvan Mesh app on all devices."""
        for node in self.nodes:
            self.run_adb(node.serial, [
                "shell", "am", "start", "-n", "com.rezvani.mesh/.MainActivity"
            ])

    def stop_app(self) -> None:
        """Force stop the app on all devices."""
        for node in self.nodes:
            self.run_adb(node.serial, ["shell", "am", "force-stop", "com.rezvani.mesh"])

    def clear_app_data(self) -> None:
        """Clear app data on all devices."""
        for node in self.nodes:
            self.run_adb(node.serial, ["shell", "pm", "clear", "com.rezvani.mesh"])

    def send_test_message(self, sender_device: str, recipient_id: str, message: str) -> bool:
        """Send a test message via broadcast intent."""
        code, _, _ = self.run_adb(sender_device, [
            "shell", "am", "broadcast",
            "-a", "com.rezvani.mesh.TEST_SEND_MESSAGE",
            "--es", "recipient", recipient_id,
            "--es", "message", message
        ])
        return code == 0

    def trigger_emergency(self, device: str, severity: int = 5) -> bool:
        """Trigger an emergency broadcast on a device."""
        code, _, _ = self.run_adb(device, [
            "shell", "am", "broadcast",
            "-a", "com.rezvani.mesh.TEST_EMERGENCY",
            "--ei", "severity", str(severity)
        ])
        return code == 0

    def get_node_id(self, device: str) -> Optional[str]:
        """Retrieve the Node ID from the device's shared preferences."""
        code, out, _ = self.run_adb(device, [
            "shell", "run-as", "com.rezvani.mesh",
            "cat", "/data/data/com.rezvani.mesh/shared_prefs/rezvan_identity.xml"
        ])
        if code != 0:
            return None
        match = re.search(r'<string name="node_id">([A-F0-9]{8})</string>', out)
        return match.group(1) if match else None

    def collect_node_ids(self) -> Dict[str, str]:
        """Collect Node IDs from all devices."""
        ids = {}
        for node in self.nodes:
            nid = self.get_node_id(node.serial)
            if nid:
                node.node_id = nid
                ids[node.serial] = nid
                print(f"  {node.serial} -> {nid}")
        return ids

    def clear_logcat(self, device: str) -> None:
        """Clear logcat buffer on a device."""
        self.run_adb(device, ["logcat", "-c"])

    def capture_logs(self, device: str, tag: str = "RezvanMesh") -> List[str]:
        """Capture filtered logcat lines for a device."""
        code, out, _ = self.run_adb(device, ["logcat", "-d", "-s", tag])
        if code == 0:
            return out.strip().split("\n") if out.strip() else []
        return []

    def wait_for_log(self, device: str, pattern: str, timeout: int = 30) -> bool:
        """Wait for a log line matching pattern on device."""
        start = time.time()
        while time.time() - start < timeout:
            logs = self.capture_logs(device)
            for line in logs:
                if re.search(pattern, line, re.IGNORECASE):
                    return True
            time.sleep(1)
        return False

    def wait_for_message(self, device: str, expected_content: str, timeout: int = 30) -> bool:
        """Wait for a message with specific content to arrive."""
        return self.wait_for_log(device, f"Received.*{re.escape(expected_content)}", timeout)

    def wait_for_routing_convergence(self, timeout: int = 60) -> bool:
        """Wait for routing tables to converge across all devices."""
        start = time.time()
        while time.time() - start < timeout:
            all_converged = True
            for node in self.nodes:
                logs = self.capture_logs(node.serial)
                if not any("Routing table updated" in line for line in logs):
                    all_converged = False
                    break
            if all_converged:
                return True
            time.sleep(2)
        return False

    def get_battery_level(self, device: str) -> int:
        """Get current battery level of device."""
        code, out, _ = self.run_adb(device, ["shell", "dumpsys", "battery"])
        match = re.search(r'level:\s*(\d+)', out)
        return int(match.group(1)) if match else -1

    def set_battery_level(self, device: str, level: int) -> bool:
        """Set simulated battery level (emulator only)."""
        code, _, _ = self.run_adb(device, ["shell", "dumpsys", "battery", "set", "level", str(level)])
        return code == 0

    def set_charging_state(self, device: str, charging: bool) -> bool:
        """Set simulated charging state (emulator only)."""
        status = "2" if charging else "1"  # 2=charging, 1=discharging
        code, _, _ = self.run_adb(device, ["shell", "dumpsys", "battery", "set", "status", status])
        return code == 0

    def reset_battery(self, device: str) -> bool:
        """Reset battery simulation to hardware values."""
        code, _, _ = self.run_adb(device, ["shell", "dumpsys", "battery", "reset"])
        return code == 0

    def simulate_low_battery(self, device: str) -> None:
        """Simulate low battery condition (15%)."""
        self.set_battery_level(device, 15)
        self.set_charging_state(device, False)

    def simulate_charging(self, device: str) -> None:
        """Simulate charging state."""
        self.set_charging_state(device, True)

    def setup_mesh(self, apk_path: str, skip_install: bool = False) -> bool:
        """Complete mesh setup: install, grant, start, collect IDs."""
        print("Setting up mesh...")
        if not skip_install:
            if not self.install_apk(apk_path):
                return False
        self.grant_permissions()
        self.clear_app_data()
        self.start_app()
        time.sleep(5)
        print("Collecting Node IDs...")
        self.collect_node_ids()
        print("Waiting for routing convergence...")
        return self.wait_for_routing_convergence(timeout=30)

    def teardown(self) -> None:
        """Clean up after tests."""
        self.stop_app()
        for node in self.nodes:
            self.reset_battery(node.serial)
            self.clear_logcat(node.serial)


def get_connected_devices() -> List[str]:
    """Get list of connected Android device serials."""
    proc = subprocess.run(["adb", "devices"], capture_output=True, text=True)
    lines = proc.stdout.strip().split("\n")[1:]
    return [line.split()[0] for line in lines if line.strip() and "device" in line]


if __name__ == "__main__":
    # Quick test of simulator
    devices = get_connected_devices()
    if not devices:
        print("No devices connected.")
        exit(1)
    print(f"Found devices: {devices}")
    sim = MeshSimulator(devices[:2])  # Use first two for quick test
    sim.wait_for_boot()
    sim.start_app()
    time.sleep(3)
    sim.collect_node_ids()
    sim.stop_app()
