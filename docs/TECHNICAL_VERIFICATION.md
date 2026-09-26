# RezvanMesh — Current Technical Verification

**Status:** Engineering validation / beta  
**Repository:** `SMozaff/RezvanMesh`  
**Branch:** `main`  
**Last source audit:** 2026-09-26  
**Validated commit:** `009935d22efdf7ce3c621eff6054000b8a09e2ea`

## 1. Executive status

RezvanMesh currently has a functioning Rust mesh/crypto core, an Android application layer, BLE/GATT transport, encrypted persistence, channel messaging, emergency broadcast handling, and a Wi-Fi Direct secondary transport implementation.

The repository builds successfully in CI. The current CI pipeline verifies Rust formatting, Clippy, Rust tests, dependency advisories, Android Rust cross-compilation, Android JVM tests, and debug APK generation.

**Important:** CI is not a substitute for physical radio validation. Real two-device GATT delivery, controlled 3+ node routing, background/OEM behavior, and long-running RF soak tests require Android devices and are not currently part of the normal GitHub Actions environment.

## 2. Verified in CI

Latest verified run: GitHub Actions run `#742`.

- Rust formatting: PASS
- Rust Clippy with warnings treated as errors: PASS
- `rezvan-common` tests: PASS
- `rezvan-crypto` tests: PASS
- `rezvan-core` tests: PASS
- Rust dependency advisory scan: PASS
- Rust Android cross-compilation: PASS
- Android JVM unit tests: PASS
- Debug APK build: PASS
- Debug APK artifact upload: PASS

The final Rust verification reported:

```
fmt backlog: 0 file(s)
clippy backlog: 0 diagnostic(s)
```

## 3. Implemented subsystems

### Rust

- Mesh engine and packet processing
- Originator/routing state
- Multi-hop forwarding logic
- Replay/duplicate suppression
- Direct-message session handling
- Channel/sender-key handling
- Ed25519 signatures
- X25519 key agreement
- HKDF/HMAC/SHA-256
- XChaCha20-Poly1305
- vodozemac-backed Olm/ratchet functionality
- Power-state calculation
- JNI entry points
- Persistent native state integration

### Android

- Jetpack Compose UI
- Android foreground radio service
- BLE scanning/advertising
- GATT connections
- BLE fragmentation/reassembly
- Direct-message dispatch
- Channel-message dispatch
- Emergency broadcast dispatch
- Room + SQLCipher persistence
- Android Keystore-backed local secrets
- QR generation/scanning
- Farsi and English resource sets
- Diagnostics/logging
- Battery/power-state integration
- Wi-Fi Direct group/socket implementation

## 4. Explicitly incomplete or not hardware-verified

These are not hidden defects; they are current project limitations.

| Capability | Current state |
|---|---|
| Two-device BLE/GATT message delivery | Implemented; real end-to-end device validation still required |
| 3+ device multi-hop | Routing implementation and host tests exist; physical mesh validation required |
| Emergency flooding | Implemented; physical propagation/TTL validation required |
| Wi-Fi Direct | Group formation/socket transport implemented; independent multi-hop relay is not complete |
| Voice transmission | Deliberately disabled |
| Voice receiver/playback | Not release-ready |
| Identity backup/recovery | Not implemented |
| OEM/background reliability | Not comprehensively verified |
| Long-running RF soak test | Not performed |
| Release signing in CI | Not part of the public debug-build workflow |

## 5. Required hardware validation

Before calling the application production-ready, execute at minimum:

1. Fresh installation and onboarding on two devices.
2. BLE discovery and GATT connection.
3. Encrypted direct message A → B.
4. Return message B → A.
5. Fragmentation at multiple MTU sizes.
6. Disconnect/reconnect during queued delivery.
7. Android process death and state restoration.
8. Channel create/join/send/leave/restart.
9. Emergency broadcast propagation.
10. Controlled three-node relay.
11. Controlled five-node relay.
12. Screen-off/background/Doze testing.
13. Bluetooth off/on recovery.
14. Battery saver and OEM power-management testing.
15. Database upgrade/migration testing.
16. Extended multi-hour soak testing.

For multi-hop validation, devices must be placed so that the intended topology is actually enforced. Five devices sitting within direct radio range do not prove five-hop forwarding.

## 6. Release interpretation

A green CI build currently means:

> The source is internally consistent, compiles for the supported Android ABIs, passes automated Rust/Android JVM tests, and produces a debug APK.

It does **not** mean:

> Real BLE hardware has been proven reliable under all Android/OEM/radio conditions.

The project should therefore remain classified as engineering/beta validation until the hardware campaign is completed.

## 7. Known product limitations

### Voice

Voice transmission is intentionally blocked until the authenticated transport envelope, receiver validation, persistence/playback policy, replay handling, and physical device tests are complete.

### Identity recovery

The identity is local to the installation. There is currently no mnemonic, encrypted export, recovery phrase, or device-migration workflow.

### Wi-Fi Direct

Wi-Fi Direct is a secondary transport implementation. It is not currently an independent replacement for BLE mesh discovery/routing, and relay forwarding over Wi-Fi Direct is not complete.

## 8. Engineering conclusion

The current codebase is suitable for continued beta/device validation. The highest-value work is now real-device integration testing rather than additional generic compilation cleanup.

Any future release claim should distinguish clearly between:

- automated software verification,
- protocol/unit verification,
- Android JVM verification,
- physical radio verification, and
- production/OEM validation.
