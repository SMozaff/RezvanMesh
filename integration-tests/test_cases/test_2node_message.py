#!/usr/bin/env python3
"""
Rezvan Mesh - Test Case: 2-Node Basic Message Delivery
Validates that a text message sent from Node A is received by Node B.
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

    if len(devices) < 2:
        print("ERROR: Need at least 2 devices for this test")
        print(f"Available: {devices}")
        return 1

    device_a = devices[0]
    device_b = devices[1]

    print(f"Device A (sender): {device_a}")
    print(f"Device B (receiver): {device_b}")

    sim = MeshSimulator([device_a, device_b])

    # Clear logs on both devices
    sim.clear_logcat(device_a)
    sim.clear_logcat(device_b)

    # Start apps
    sim.start_app()
    time.sleep(5)

    # Collect Node IDs
    node_ids = sim.collect_node_ids()
    if device_a not in node_ids or device_b not in node_ids:
        print("ERROR: Failed to get Node IDs")
        return 1

    node_a_id = node_ids[device_a]
    node_b_id = node_ids[device_b]
    print(f"Node A ID: {node_a_id}")
    print(f"Node B ID: {node_b_id}")

    # Wait for discovery
    print("Waiting for mutual discovery...")
    time.sleep(10)

    # Send test message from A to B
    test_message = f"Hello from Node A at {int(time.time())}"
    print(f"Sending message: '{test_message}'")
    
    if not sim.send_test_message(device_a, node_b_id, test_message):
        print("ERROR: Failed to send test message")
        return 1

    # Wait for message to arrive
    print("Waiting for message delivery...")
    if not sim.wait_for_message(device_b, test_message, timeout=30):
        print("ERROR: Message not received within timeout")
        logs = sim.capture_logs(device_b)
        print("Receiver logs:")
        for line in logs[-20:]:
            print(f"  {line}")
        return 1

    print("SUCCESS: Message delivered successfully!")
    return 0

if __name__ == "__main__":
    sys.exit(main())
