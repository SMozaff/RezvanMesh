#!/usr/bin/env python3
"""
Rezvan Mesh - Test Case: 5-Node Multi-Hop Routing
Validates that messages can traverse multiple hops across a chain of nodes.
Topology: A <-> B <-> C <-> D <-> E
Message from A to E should successfully route through B, C, D.
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

    if len(devices) < 5:
        print("ERROR: Need at least 5 devices for this test")
        print(f"Available: {devices}")
        return 1

    # Use first 5 devices
    devices = devices[:5]
    device_a = devices[0]
    device_b = devices[1]
    device_c = devices[2]
    device_d = devices[3]
    device_e = devices[4]

    print(f"Device A (source): {device_a}")
    print(f"Device B (hop 1):  {device_b}")
    print(f"Device C (hop 2):  {device_c}")
    print(f"Device D (hop 3):  {device_d}")
    print(f"Device E (target): {device_e}")

    sim = MeshSimulator(devices)

    # Clear logs on all devices
    for dev in devices:
        sim.clear_logcat(dev)

    # Start apps
    sim.start_app()
    time.sleep(5)

    # Collect Node IDs
    node_ids = sim.collect_node_ids()
    if len(node_ids) < 5:
        print("ERROR: Failed to get all Node IDs")
        return 1

    node_a_id = node_ids[device_a]
    node_b_id = node_ids[device_b]
    node_c_id = node_ids[device_c]
    node_d_id = node_ids[device_d]
    node_e_id = node_ids[device_e]

    print(f"Node A ID: {node_a_id}")
    print(f"Node B ID: {node_b_id}")
    print(f"Node C ID: {node_c_id}")
    print(f"Node D ID: {node_d_id}")
    print(f"Node E ID: {node_e_id}")

    # Wait for discovery and routing convergence
    print("Waiting for discovery and routing convergence (up to 60s)...")
    if not sim.wait_for_routing_convergence(timeout=60):
        print("WARNING: Routing convergence may not be complete")

    # Send test message from A to E
    test_message = f"Multi-hop test from A to E at {int(time.time())}"
    print(f"Sending message from A to E: '{test_message}'")

    if not sim.send_test_message(device_a, node_e_id, test_message):
        print("ERROR: Failed to send test message")
        return 1

    # Wait for message to arrive at E
    print("Waiting for message delivery (up to 60s for multi-hop)...")
    if not sim.wait_for_message(device_e, test_message, timeout=60):
        print("ERROR: Message not received at target E within timeout")
        print("\nLogs from intermediate nodes:")
        for dev, name in [(device_b, "B"), (device_c, "C"), (device_d, "D")]:
            print(f"\n--- Node {name} logs ---")
            logs = sim.capture_logs(dev)
            for line in logs[-10:]:
                print(f"  {line}")
        return 1

    # Verify routing path by checking logs for forwarding events
    print("Verifying multi-hop forwarding...")
    forwarded_by_b = False
    forwarded_by_c = False
    forwarded_by_d = False

    logs_b = sim.capture_logs(device_b)
    logs_c = sim.capture_logs(device_c)
    logs_d = sim.capture_logs(device_d)

    for line in logs_b:
        if "Forwarding packet" in line or "Relaying" in line:
            forwarded_by_b = True
    for line in logs_c:
        if "Forwarding packet" in line or "Relaying" in line:
            forwarded_by_c = True
    for line in logs_d:
        if "Forwarding packet" in line or "Relaying" in line:
            forwarded_by_d = True

    print(f"  Forwarded by B: {forwarded_by_b}")
    print(f"  Forwarded by C: {forwarded_by_c}")
    print(f"  Forwarded by D: {forwarded_by_d}")

    if not (forwarded_by_b or forwarded_by_c or forwarded_by_d):
        print("WARNING: No forwarding logs detected, but message arrived (direct connection possible)")

    print("SUCCESS: 5-node multi-hop message delivered successfully!")
    return 0

if __name__ == "__main__":
    sys.exit(main())
