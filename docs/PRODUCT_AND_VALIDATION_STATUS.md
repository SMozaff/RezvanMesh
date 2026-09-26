# RezvanMesh — Product and Validation Status

**Status:** Current state  
**Last reviewed:** 2026-09-26

## Implemented

- Offline peer-to-peer Android communication architecture
- BLE advertisement discovery
- BLE GATT transport
- Multi-hop routing implementation
- Encrypted one-to-one messaging
- Encrypted channel messaging
- Emergency broadcast protocol
- Local encrypted persistence
- Android Keystore-backed identity storage
- QR-based identity/contact exchange
- Power-management state machine
- Diagnostics and logging
- Wi-Fi Direct group/socket transport implementation
- Farsi and English resource sets

## In engineering validation

### Direct messaging

The software path exists from Compose/UI → Android service → action dispatcher → BLE transport → Rust packet processing → session/crypto layer.

Physical two-device delivery remains a validation requirement.

### Multi-hop routing

Rust routing and relay behavior are implemented and covered by automated tests.

Physical validation must establish an enforced topology rather than merely placing several devices in the same room.

### Emergency broadcast

The protocol supports authenticated flooding, TTL, and duplicate suppression.

Physical propagation under packet loss and node churn remains to be measured.

### Persistence and lifecycle

The application has encrypted persistence and native-state restoration.

Real Android process death, force-stop, service restart, reboot, and database migration scenarios require device validation.

## Intentionally unavailable

### Voice

Voice send/receive is currently withheld. The service rejects voice broadcast requests until the authenticated receive/send transport, fragmentation, persistence, playback, replay handling, and hardware validation are complete.

This is intentional safety behavior, not a broken fallback.

### Identity recovery

There is currently no backup/recovery workflow for the device identity.

## Wi-Fi Direct

Wi-Fi Direct currently provides group formation and socket-based packet transport.

It is not yet an independent multi-hop mesh. BLE-derived peer addressing is still part of the current discovery model, and Wi-Fi Direct relay forwarding is not complete.

## Validation levels

RezvanMesh uses five distinct validation levels:

1. **Static/source review** — architecture and implementation inspection.
2. **Rust automated tests** — crypto, routing, packet/session behavior.
3. **Android JVM tests** — Android-side deterministic behavior.
4. **Physical-device integration tests** — BLE/GATT/Wi-Fi/radio behavior.
5. **Production/OEM validation** — background execution, battery restrictions, OS versions, device manufacturers, long-running operation.

Only the first three are currently part of the standard CI gate.

## Current release posture

The project is suitable for controlled beta/engineering validation.

It should not currently be described as fully production-validated because physical radio behavior and OEM/background behavior remain open verification areas.
