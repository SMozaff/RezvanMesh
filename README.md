# RezvanMesh

**Decentralized, encrypted, off-grid communication for Android.**

[![License: AGPL v3](https://img.shields.io/badge/license-AGPL%20v3-blue.svg)](https://www.gnu.org/licenses/agpl-3.0)
[![Platform: Android](https://img.shields.io/badge/platform-Android%208.0%2B-brightgreen.svg)]()
[![Status: Beta](https://img.shields.io/badge/status-engineering%20%2F%20beta-yellow.svg)]()

RezvanMesh is an Android peer-to-peer communication system designed to operate without cellular service, the public Internet, or a centralized server. Its primary transport is Bluetooth Low Energy (BLE), with multi-hop routing implemented in the Rust core and a Wi-Fi Direct transport available as a secondary path.

> **Current status:** the software stack builds and passes automated Rust/Android verification, but physical radio validation is still required before the application should be described as production-ready.

---

## Contents

- [Current State](#current-state)
- [What Works](#what-works)
- [What Is Not Complete](#what-is-not-complete)
- [Architecture](#architecture)
- [Security and Cryptography](#security-and-cryptography)
- [Messaging and Routing](#messaging-and-routing)
- [Storage and Identity](#storage-and-identity)
- [Power Management](#power-management)
- [Android Support](#android-support)
- [Build From Source](#build-from-source)
- [Testing](#testing)
- [CI](#ci)
- [Physical Device Validation](#physical-device-validation)
- [Project Structure](#project-structure)
- [Development Rules](#development-rules)
- [Known Limitations](#known-limitations)
- [Roadmap](#roadmap)
- [License](#license)

---

## Current State

### Automated verification

The latest validated commit is:

```
009935d22efdf7ce3c621eff6054000b8a09e2ea
```

The latest CI run verified:

- Rust formatting
- Rust Clippy with warnings treated as errors
- Rust unit/integration tests for all workspace crates
- Rust dependency advisory scan
- Rust Android cross-compilation
- Android JVM unit tests
- Android debug APK assembly
- APK artifact upload

The final Rust verification reported:

```
fmt backlog: 0 file(s)
clippy backlog: 0 diagnostic(s)
```

See [docs/TECHNICAL_VERIFICATION.md](docs/TECHNICAL_VERIFICATION.md) for the complete verification boundary.

### The important distinction

A green CI run proves software consistency and automated tests. It does **not** prove that arbitrary Android devices will maintain reliable BLE communication under real RF conditions.

The following require physical-device testing:

- two-device GATT message delivery
- controlled multi-hop routing
- disconnect/reconnect behavior
- Android process death and state restoration
- background/Doze/OEM power behavior
- Wi-Fi Direct interoperability
- long-running radio soak tests

---

## What Works

### Core

- Rust mesh engine
- BLE advertisement discovery
- BLE GATT transport
- Packet fragmentation/reassembly
- Multi-hop route calculation and forwarding
- Duplicate/replay handling
- Direct-message session management
- Encrypted channel messaging
- Emergency broadcast/flooding protocol
- Native state persistence
- Power-state calculation
- JNI integration

### Android

- Jetpack Compose UI
- Foreground radio service
- BLE scanning and advertising
- GATT connection management
- Room + SQLCipher encrypted database
- Android Keystore-backed local secrets
- QR identity/contact exchange
- Farsi and English resources
- Diagnostics and log export
- Battery/power management
- Wi-Fi Direct group/socket transport implementation

### Cryptography

The current implementation uses maintained Rust cryptographic crates plus vodozemac. The previously used `sodiumoxide`/vendored libsodium dependency has been removed.

Current primitives include:

| Purpose | Implementation |
|---|---|
| Signatures | Ed25519 / `ed25519-dalek` |
| Key agreement | X25519 / `x25519-dalek` |
| Hashing | SHA-256 / `sha2` |
| MAC/KDF | HMAC-SHA256 + HKDF |
| AEAD | XChaCha20-Poly1305 |
| 1:1 sessions | vodozemac / Olm-style ratchet |
| Secret cleanup | `zeroize` |
| Randomness | secure platform/Rust randomness |

See [docs/CRYPTOGRAPHY.md](docs/CRYPTOGRAPHY.md).

---

## What Is Not Complete

These items are deliberately not presented as finished features.

| Capability | State |
|---|---|
| Two-device physical GATT delivery | Implemented in software; hardware validation pending |
| 3+ device physical mesh stability | Routing implemented; hardware validation pending |
| Emergency physical propagation | Protocol implemented; hardware validation pending |
| Voice transmission | Disabled |
| Voice receiver/playback | Not release-ready |
| Wi-Fi Direct independent mesh relay | Not complete |
| Identity backup/recovery | Not implemented |
| OEM-specific background reliability | Not comprehensively tested |
| Long-running RF soak test | Not performed |
| Production release signing pipeline | Not part of the standard CI build |

Voice is intentionally blocked in the radio service until the authenticated receive/send path, replay handling, persistence/playback policy, and physical validation are complete.

---

## Architecture

```
┌─────────────────────────────────────────────────────┐
│                 Jetpack Compose UI                  │
│  Status · Messages · Channels · Contacts · SOS      │
└──────────────────────────┬──────────────────────────┘
                           │
                           │ Kotlin service API
                           ▼
┌─────────────────────────────────────────────────────┐
│               RezvanRadioService                    │
│ BLE scan · advertise · GATT · packet queues         │
│ Wi-Fi Direct group/socket transport                 │
└──────────────────────────┬──────────────────────────┘
                           │ JNI
                           ▼
┌─────────────────────────────────────────────────────┐
│                   MeshEngine                        │
│ routing · forwarding · packet validation · power    │
│ sessions · channel messaging · native persistence   │
└──────────────────────────┬──────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────┐
│                 Crypto Layer                        │
│ Ed25519 · X25519 · HKDF/HMAC · XChaCha20-Poly1305  │
│ vodozemac sessions · sender keys · beacon auth      │
└─────────────────────────────────────────────────────┘

                    Local persistence
                           │
             ┌─────────────┴─────────────┐
             ▼                           ▼
      Room + SQLCipher          Native encrypted state
             │                           │
             └──────── Android Keystore ┘
```

### Design principles

1. **Offline first.** The communication path does not require a cloud backend.
2. **Transport separation.** Rust works with stable mesh identities; Kotlin resolves those identities to current radio addresses.
3. **Cryptographic separation.** Identity, session, channel, and network-authentication keys have separate roles.
4. **Persistence across process death.** State required for continued encrypted communication is persisted rather than treated as UI state.
5. **Explicit failure.** Unsupported or unsafe capabilities should fail closed instead of pretending that a message was delivered.

---

## Messaging and Routing

### Direct messaging

The intended path is:

```
Compose
  ↓
ViewModel / repository
  ↓
RezvanRadioService
  ↓
ActionDispatcher
  ↓
BLE/GATT packet transport
  ↓
MeshEngine
  ↓
session / encryption
  ↓
recipient
```

A local queue acceptance or GATT write result is **not** a protocol-level delivery receipt.

A future delivery state must be backed by an authenticated protocol acknowledgement rather than inferred from a successful local write.

### Multi-hop routing

The Rust engine supports destination-aware forwarding.

Conceptually:

```
A ── BLE ── B ── BLE ── C ── BLE ── D
                                   route state
```

A node forwards toward the selected next hop rather than broadcasting every direct message to every connected peer.

Routing state includes originator information, link quality/metrics, sequence/replay handling, and route expiration.

### Important validation rule

A five-device test only proves multi-hop routing if the physical topology prevents a direct A → E path. Several devices placed next to one another do not constitute a five-hop mesh.

---

## Emergency Broadcasts

Emergency messages use a flooding/relay model with:

- severity
- TTL
- duplicate suppression
- authenticated protocol data
- relay processing

Emergency behavior still needs real-device tests covering:

- propagation
- duplicate suppression
- packet loss
- node disappearance
- TTL exhaustion
- background/Doze behavior

---

## Channel Messaging

Channel messaging uses sender-key style symmetric encryption with authenticated sender identity.

The current implementation supports:

- channel state
- sender keys
- encrypted channel messages
- transport dispatch
- relay-capable packet processing
- persistence of channel state

Required device validation:

```
create channel
    ↓
B joins
    ↓
A sends
    ↓
B decrypts
    ↓
B leaves
    ↓
B loses access
    ↓
restart B
    ↓
B remains excluded
```

---

## Storage and Identity

### Identity

A fresh installation generates a cryptographically random 32-byte identity seed.

The stable Node ID is derived from the Ed25519 public key:

```
NodeId = SHA-256(Ed25519PublicKey)[0..8]
```

The identity is not derived from a Bluetooth MAC address or other hardware identifier.

### Local secrets

Sensitive application data is protected with Android Keystore-backed material.

The application database uses:

- Room
- SQLCipher
- explicit database migrations
- encrypted database key handling

Native session/key state is persisted separately from UI state so that Android process/service restarts do not automatically invalidate encrypted communication.

### Identity recovery

There is currently no recovery phrase, encrypted identity export, mnemonic, or device-migration mechanism.

Losing or clearing the installation can therefore result in permanent loss of the local identity.

---

## Power Management

The application contains a seven-state power model:

| State | Purpose |
|---|---|
| Emergency | Maximum responsiveness |
| Active | High communication availability |
| Balanced | Normal operating mode |
| PowerSaver | Reduced duty cycle |
| Minimal | Survival-oriented operation |
| Hibernation | Radio largely disabled |
| Dead | No radio operation |

The Rust engine calculates state from battery/charging conditions and Kotlin applies the resulting radio configuration.

Exact battery consumption depends heavily on:

- Android version
- OEM firmware
- BLE chipset
- neighboring-device density
- scan duty cycle
- screen state
- battery health

The repository does not currently have enough physical-device measurements to claim a universal battery-consumption number.

---

## Android Support

### SDK

Current application configuration:

- Minimum SDK: **26**
- Compile SDK: **35**
- Target SDK: **35**
- Java/JVM target: **17**
- Rust Android ABIs:
  - `arm64-v8a`
  - `armeabi-v7a`

The application is intended for modern Android devices with BLE support.

### Permissions

Depending on Android version and enabled transport features, the application may require permissions for:

- Bluetooth scanning
- Bluetooth advertising
- Bluetooth connections
- camera access for QR scanning
- nearby Wi-Fi devices for Wi-Fi Direct

Permission behavior must be validated across Android versions and OEMs.

---

## Build From Source

### Prerequisites

Install:

- Android SDK
- Android SDK Build Tools/platform tools
- Android NDK compatible with the current Gradle project
- JDK 17
- Rust toolchain
- `cargo-ndk`
- Python 3 for repository verification/integration scripts

Rust Android targets:

```bash
rustup target add aarch64-linux-android armv7-linux-androideabi
cargo install cargo-ndk
```

### Build Rust libraries

```bash
./scripts/build_rust.sh
```

### Verify JNI interfaces

```bash
python3 scripts/verify_interfaces.py
```

### Build debug APK

```bash
./gradlew assembleDebug
```

Output:

```
android/app/build/outputs/apk/debug/app-debug.apk
```

### Install

```adb
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

The debug build uses an application ID suffix, so its package is:

```
com.rezvani.mesh.debug
```

The release variant uses:

```
com.rezvani.mesh
```

Launch the appropriate package for the variant you installed.

### Logs

```bash
adb logcat -s RezvanMesh
```

---

## Testing

### Rust

Run the complete workspace:

```bash
cd rust
cargo fmt --all -- --check
cargo clippy --workspace --all-targets -- -D warnings
cargo test --workspace
```

Individual crates:

```bash
cargo test -p rezvan-common
cargo test -p rezvan-crypto
cargo test -p rezvan-core
```

### Android JVM tests

```bash
./gradlew :android:app:testDebugUnitTest
```

### Debug build

```bash
./gradlew :android:app:assembleDebug
```

### Known-answer crypto vectors

```bash
python3 scripts/generate_known_answer_vectors.py
```

### Integration tests

The integration test suite drives Android devices/emulators through ADB. It is **not** a pure software protocol simulator.

Available cases:

```
integration-tests/test_cases/test_2node_message.py
integration-tests/test_cases/test_5node_routing.py
integration-tests/test_cases/test_emergency_broadcast.py
```

The runner is:

```bash
python3 scripts/run_integration_test.py
```

For physical radio validation, provide the required devices explicitly and use an APK/package configuration appropriate to the installed variant.

Do not treat an emulator-only run as equivalent to physical BLE RF validation.

---

## CI

The GitHub Actions workflow is in:

```
.github/workflows/ci.yml
```

The current pipeline contains three principal jobs:

### Rust verification

- format check
- Clippy
- workspace tests

Formatting and Clippy are **blocking**.

### Dependency advisory scan

Runs `cargo audit` against the Rust dependency graph.

A clean RustSec result is useful but does not constitute a complete security audit of the application.

### Android build

- configures Java
- installs Rust
- installs `cargo-ndk`
- locates the Android NDK
- aligns NDK environment variables
- builds Rust libraries for Android
- runs Android JVM tests
- assembles the debug APK
- uploads the APK artifact

The workflow also uses current Node-compatible GitHub Actions versions.

### What CI does not currently do

CI does not provide:

- real BLE RF testing
- multi-phone topology testing
- OEM battery testing
- physical Bluetooth disconnect/reconnect testing
- production signing
- long-running radio soak testing

---

## Physical Device Validation

The following campaign is the next major verification step.

### Two devices

- onboarding
- identity creation
- BLE discovery
- GATT connection
- direct encrypted message A → B
- direct encrypted message B → A
- fragmented message
- reconnect
- process death
- state restoration

### Three devices

Force:

```
A ↔ B ↔ C
```

and verify:

```
A → C
```

must traverse B.

### Five devices

Force:

```
A ↔ B ↔ C ↔ D ↔ E
```

and measure:

- route convergence
- hop count
- delivery latency
- duplicate rate
- packet loss
- behavior after removing a relay

### Lifecycle

Test:

- screen off
- app backgrounded
- Doze
- battery saver
- Bluetooth disabled/enabled
- service restart
- process kill
- device reboot
- database upgrade

### Soak

Run several devices for an extended period while generating normal and emergency traffic and record:

- memory
- CPU
- battery
- packet loss
- reconnect frequency
- route convergence
- crashes
- ANRs
- database failures

---

## Project Structure

```
RezvanMesh/
├── android/
│   └── app/
│       └── src/main/
│           ├── java/com/rezvani/mesh/
│           │   ├── MainActivity.kt
│           │   ├── MeshCore.kt
│           │   ├── MeshServiceConnection.kt
│           │   ├── radio/
│           │   │   ├── RezvanRadioService.kt
│           │   │   ├── RadioControllerImpl.kt
│           │   │   ├── BlePacketSender.kt
│           │   │   ├── BleFragmenter.kt
│           │   │   ├── WifiPacketSender.kt
│           │   │   └── ActionDispatcher.kt
│           │   ├── data/
│           │   │   ├── AppDatabase.kt
│           │   │   ├── dao/
│           │   │   └── entities/
│           │   └── ui/
│           └── res/
├── rust/
│   ├── Cargo.toml
│   ├── rezvan-common/
│   ├── rezvan-crypto/
│   └── rezvan-core/
├── integration-tests/
├── scripts/
├── docs/
├── .github/workflows/
└── README.md
```

### Documentation

| Document | Purpose |
|---|---|
| [README.md](README.md) | Current project overview, build, architecture, testing, limitations |
| [docs/TECHNICAL_VERIFICATION.md](docs/TECHNICAL_VERIFICATION.md) | Current engineering verification boundary and release posture |
| [docs/CRYPTOGRAPHY.md](docs/CRYPTOGRAPHY.md) | Current cryptographic architecture and verification |
| [docs/PRODUCT_AND_VALIDATION_STATUS.md](docs/PRODUCT_AND_VALIDATION_STATUS.md) | Current feature completeness and validation status |
| [docs/GATE1_MESSAGE_ID_SIGNED_ACK_PROTOCOL.md](docs/GATE1_MESSAGE_ID_SIGNED_ACK_PROTOCOL.md) | Message-ID and signed acknowledgement protocol |

Historical remediation reports are intentionally not retained as current project documentation.

---

## Development Rules

1. Do not describe a feature as implemented if the runtime path is disabled or incomplete.
2. Do not treat a successful local queue/write as proof of remote delivery.
3. Do not treat host/unit tests as proof of BLE hardware behavior.
4. Security-sensitive changes require tests and explicit protocol review.
5. Keep identity, session, channel, and transport addresses conceptually separate.
6. Prefer fail-closed behavior for unsupported security-sensitive features.
7. Update documentation in the same change when architecture or feature state changes.
8. Keep CI gates truthful: blocking checks must actually fail the build.

---

## Known Limitations

### Radio

BLE reliability is affected by Android Bluetooth stacks, OEM firmware, radio interference, device placement, and power-management policies.

### Background execution

Android and OEMs may restrict background activity. A mesh application cannot guarantee identical behavior across every vendor without device-specific validation.

### Jamming

BLE frequency hopping and adaptive scanning can improve resilience but cannot guarantee communication against a capable RF jammer.

### Identity recovery

There is currently no identity backup/recovery mechanism.

### Voice

Voice is intentionally disabled pending completion of the authenticated end-to-end transport and receiver path.

### Wi-Fi Direct

Wi-Fi Direct is not yet an independent multi-hop mesh transport.

### Traffic analysis

Encryption protects message content but does not automatically hide radio metadata such as timing, packet volume, device presence, or RF activity.

---

## Roadmap

### Current engineering phase

- [x] Rust core implementation
- [x] Cryptographic implementation and known-answer testing
- [x] BLE transport implementation
- [x] Multi-hop routing implementation
- [x] Encrypted local persistence
- [x] Android JVM/CI verification
- [ ] Physical two-device GATT validation
- [ ] Controlled 3+ node routing validation
- [ ] Lifecycle/background/OEM validation
- [ ] Database migration validation on real installations

### Next feature phase

- [ ] Authenticated message delivery acknowledgements
- [ ] Identity backup/recovery
- [ ] Complete Wi-Fi Direct mesh relay
- [ ] Complete authenticated voice transport
- [ ] Voice receiver/playback
- [ ] File transfer
- [ ] Broader device compatibility matrix

### Longer term

- [ ] Formal independent security audit
- [ ] More extensive RF resilience testing
- [ ] Additional transport technologies if justified by the product requirements

Roadmap items are deliberately not assigned dates until the physical validation baseline is established.

---

## License

RezvanMesh is licensed under the **GNU Affero General Public License v3.0 (AGPL-3.0)**.

See the repository license file for the complete terms.

---

## Disclaimer

RezvanMesh is experimental communication software. No wireless system can guarantee delivery, availability, privacy against a compromised device, or resistance to a capable jammer.

Do not rely on RezvanMesh as your only emergency communication method until the physical-device and production validation program has been completed.
