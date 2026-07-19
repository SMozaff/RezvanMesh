#!/usr/bin/env python3
"""
Rezvan Mesh - Test Case: Emergency Broadcast Flooding
Validates that an emergency broadcast sent from one node reaches all other nodes.
"""

import sys
import os
import time

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from mesh_simulator import MeshSimulator, get_connected_devices

def main():
    # Get devices from environment or use connected devices
    devices_env = os.environ.get("REZVAN_DEVICES", "")
    if devices_env:
        devices = devices_env.split(",")
    else:
        devices = get_connected_devices()

    if len(devices) < 3:
        print("ERROR: Need at least 3 devices for this test")
        print(f"Available: {devices}")
        return 1

    # Use all available devices (up to 5)
    devices = devices[:5]
    print(f"Testing with {len(devices)} devices: {devices}")

    sim = MeshSimulator(devices)
    source_device = devices[0]
    receiver_devices = devices[1:]

    # Clear logs on all devices
    for dev in devices:
        sim.clear_logcat(dev)

    # Start apps
    sim.start_app()
    time.sleep(5)

    # Collect Node IDs
    node_ids = sim.collect_node_ids()
    if len(node_ids) < len(devices):
        print("ERROR: Failed to get all Node IDs")
        return 1

    source_node_id = node_ids[source_device]
    print(f"Source node ({source_device}): {source_node_id}")
    for dev in receiver_devices:
        print(f"Receiver ({dev}): {node_ids[dev]}")

    # Wait for discovery
    print("Waiting for mesh discovery (up to 30s)...")
    time.sleep(15)
    sim.wait_for_routing_convergence(timeout=30)

    # Trigger emergency broadcast from source
    severity = 5  # CRITICAL
    print(f"Triggering Level {severity} emergency broadcast from {source_device}...")

    if not sim.trigger_emergency(source_device, severity):
        print("ERROR: Failed to trigger emergency broadcast")
        return 1

    # Wait for broadcast to propagate
    print("Waiting for emergency broadcast propagation...")
    time.sleep(10)

    # Verify all receivers got the emergency alert
    print("Verifying emergency broadcast receipt...")
    all_received = True
    received_count = 0

    for dev in receiver_devices:
        logs = sim.capture_logs(dev)
        received = False
        for line in logs:
            if "Emergency broadcast" in line or "EMERGENCY" in line.upper():
                received = True
                break
        if received:
            print(f"  ✓ {dev} received emergency alert")
            received_count += 1
        else:
            print(f"  ✗ {dev} did NOT receive emergency alert")
            all_received = False

    print(f"\nReceipt rate: {received_count}/{len(receiver_devices)} devices")

    if not all_received:
        print("\nDetailed logs from non-receiving devices:")
        for dev in receiver_devices:
            logs = sim.capture_logs(dev)
            if not any("Emergency" in line for line in logs):
                print(f"\n--- {dev} logs (last 20) ---")
                for line in logs[-20:]:
                    print(f"  {line}")
        return 1

    print("SUCCESS: Emergency broadcast reached all devices!")
    return 0

if __name__ == "__main__":
    sys.exit(main())
