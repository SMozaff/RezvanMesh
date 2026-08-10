# Rezvan Mesh – Decentralized Off-Grid Communication

[![License: AGPL v3](https://img.shields.io/badge/License-AGPL%20v3-blue.svg)](https://www.gnu.org/licenses/agpl-3.0)
![Platforms](https://img.shields.io/badge/platform-Android%208.0%2B-brightgreen)
![Language](https://img.shields.io/badge/languages-Kotlin%20%7C%20Rust%20%7C%20C-orange)
![Status](https://img.shields.io/badge/status-Beta%20(Functional%20Testing)-yellow)

---

## Mission

**Rezvan Mesh** is a peer-to-peer mesh communication application for Android devices. It enables resilient, off-grid messaging during nationwide internet shutdowns and infrastructure failures—without relying on cellular towers, internet connectivity, or centralized servers.

**Target Scenario:** Iranian civilians during communications blackouts, with extreme power efficiency and resilience under jamming/SIGINT threats.

**Core Principle:** Device-to-device communication only. Zero external dependencies. All encryption on-device.

---

## Features (Current Release)

### ✅ Implemented & Tested

- **Mesh Routing** – BATMAN-Adv protocol over BLE advertisement + GATT unicast
- **End-to-End Encryption** – Signal Protocol (X3DH + Double Ratchet) via libsodium
- **Text Messaging** – Up to 10,000 characters per message
- **Voice Broadcasting** – Opus codec @ 16 kbps, push-to-talk up to 60 seconds
- **Emergency Alerts** – SOS button with 5 severity levels, network-wide flooding
- **Offline Identity** – 32-byte `SecureRandom` seed, stored via Android Keystore-backed `EncryptedSharedPreferences` (no backup/recovery phrase exists yet — see Known Issues)
- **Encrypted Storage** – SQLCipher database for contacts, messages, voice logs
- **Multi-Language UI** – Farsi (primary) + English, runtime switchable
- **Power Management** – 7-state power machine (Emergency → Hibernation) with dynamic duty cycling
- **Diagnostics** – Real-time routing/radio stats, searchable log export, crash dossiers

### ⏳ In Development

- **GATT Message Delivery** – End-to-end 2-device test pending
- **Voice Playback on Receiver** – MediaPlayer integration incomplete
- **3+ Device Mesh Stability** – Multi-hop routing validation needed
- **WiFi Direct Transport** – Stubs only, deferred to v1.1

---

## System Architecture

```
┌──────────────────────────────────────┐
│     Jetpack Compose UI (Kotlin)      │
│  Status│SOS│PTT│Messages│Contacts   │
└──────────────────────────────────────┘
            ↕ JNI Bridge
┌──────────────────────────────────────┐
│   RezvanRadioService (Kotlin)        │
│  BLE Scanning/Advertising/GATT       │
│  WiFi Direct (stubs)                 │
└──────────────────────────────────────┘
            ↕ Native Interface
┌──────────────────────────────────────┐
│   MeshEngine (Rust)                  │
│  BATMAN-Adv Routing                  │
│  Packet Processing & Crypto Wrapper  │
└──────────────────────────────────────┘
            ↕ FFI
┌──────────────────────────────────────┐
│   CryptoProvider (Rust + libsodium)  │
│  Ed25519 Signing                     │
│  X3DH Key Exchange                   │
│  XChaCha20-Poly1305 AEAD             │
└──────────────────────────────────────┘
```

### Technology Stack

| Layer | Language | Framework | Rationale |
|-------|----------|-----------|-----------|
| UI | Kotlin | Jetpack Compose | Modern, concise, Material 3 support |
| Radio Service | Kotlin | Android Framework APIs | Native BLE/WiFi Direct access |
| Mesh Engine | Rust | JNI | Memory safety, no GC, real-time capable |
| Cryptography | Rust/C | libsodium (sodiumoxide) | Industry-standard, constant-time |
| Database | Kotlin | Room + SQLCipher | Encrypted at rest, type-safe queries |
| Build System | Gradle + Cargo | cargo-ndk | Android NDK cross-compilation |

---

## Quick Start

### Prerequisites

- **Android SDK:** API 26 (Android 8.0) minimum, compile/target API 35
- **Android NDK:** 25.2.9519653
- **Rust:** 1.75+ with Android targets:
  ```bash
  rustup target add aarch64-linux-android armv7-linux-androideabi
  cargo install cargo-ndk
  ```
- **Build Machine:** Linux (Ubuntu 22.04+) or macOS with 8+ GB RAM
- **Device:** Samsung A23, Xiaomi Redmi, or other modern Android phone (BLE required)

### Build from Source

```bash
git clone https://github.com/muzaff-beep/RezvanMesh.git
cd RezvanMesh

# Build Rust libraries for Android targets
./scripts/build_rust.sh

# Build debug APK (includes Rust binaries)
./gradlew assembleDebug

# APK output: android/app/build/outputs/apk/debug/app-debug.apk
```

### Install & Run

```bash
# Install on connected device
adb install -r android/app/build/outputs/apk/debug/app-debug.apk

# Launch app
adb shell am start -n com.rezvani.mesh/.MainActivity

# View diagnostic logs
adb logcat -s RezvanMesh
```

### First Launch

1. **Onboarding Flow:**
   - Welcome screen explains offline mesh capability
   - Tap "Create Identity" → app generates a 32-byte `SecureRandom` seed and derives an Ed25519/X25519 keypair from it
   - Seed is saved to Keystore-backed encrypted storage (no mnemonic/backup phrase is shown — there is currently no way to recover an identity if the app's storage is lost; see Known Issues)
   - Land in Status screen (scanning for neighbors)

2. **Verify Installation:**
   - Status screen shows "Node ID: RV-XXXXXXXX"
   - "Listening for devices…" indicates BLE scanning active
   - Battery level, RSSI, radio stats visible

---

## Usage Guide

### Messaging

1. **Text Message:**
   - Status → "New Message" button → select contact (or enter Node ID) → type text → Send
   - Message encrypted with Signal Protocol, routed via mesh
   - Delivery confirmed in ChatDetailScreen (checkmark = received)

2. **Voice Broadcast (Push-to-Talk):**
   - Tap PTT tab → hold record button → speak up to 60 seconds
   - Release to send
   - Opus codec @ 16 kbps (~120 KB/min) automatically selected
   - Reception toggle in Settings controls auto-play on receive

3. **Emergency Alert (SOS):**
   - Tap SOS tab → select severity (1=Advisory, 5=Critical)
   - Red button triggers emergency broadcast
   - Floods through mesh with TTL=10, bypasses rate limiting
   - All devices wake from Doze mode if severity ≥ 4

### Contacts

- **Add Contact:** Manual Node ID entry (e.g., "RV-A1B2C3D4")
- **Verify Identity:** QR code of your own ID (share via screenshot/print)
- **Scan QR:** Camera scan to add verified contact
- **Persistent:** Contacts saved to contacts.txt (encrypted via EncryptedSharedPreferences)

### Settings

- **Theme:** Light/Dark mode toggle
- **Language:** English/Farsi runtime switch
- **Power Profile:** Override auto-computed state (Emergency/Active/Balanced/PowerSaver/Minimal/Hibernation)
- **Voice Retention:** Log storage (0/1/6/12/24 hours)
- **Storage:** Clear all data (nuclear option)
- **About:** Version, build info, crash dossier viewer

### Diagnostics

- **Status Screen:** Real-time mesh state, RSSI, packet counters, routing table
- **Diagnostic Log:** Searchable text log with filters (type, severity)
- **Export Log:** Share diagnostic snapshot via email/messaging
- **Loopback Test:** Self-test harness for manual mesh verification
- **Force Crash:** Intentional crash trigger for dossier generation

---

## Architecture Deep Dive

### Mesh Routing (BATMAN-Adv)

**OGM (Originator Message) Flooding:**
- Every 5 seconds, each node broadcasts an OGM with routing table snapshot
- OGM contains: timestamp, link quality to neighbors, cumulative path metric
- Receivers update routing tables based on lowest metric (hop penalty + link quality)

**Path Metric Calculation:**
```
Metric = Σ(Hop_Penalty) + Route_Length_Penalty

Hop_Penalty = (1000 × (256 / LQ)²) × Battery_Weight
Battery_Weight = 1.0 (battery > 50%), 1.5 (20%-50%), 2.5 (<20%)

Link_Quality = RSSI → Quality mapping:
  RSSI > -65 dBm  → 255 (excellent)
  RSSI < -85 dBm  → 0 (unreliable)
  -85 to -65 dBm  → interpolated
```

**Routing Table:**
- Up to 3 routes per destination (primary, backup, experimental)
- Best route = lowest metric
- Converges within 10-30 seconds in stable topology

### Encryption

**Identity Generation:**
```rust
Seed = SecureRandom(32 bytes)   // generated on-device in Kotlin, NOT derived from
                                 // any hardware identifier (MAC address, etc.)
Public_Ed25519 = crypto_sign_ed25519_seed_keypair(seed)          // raw seed, libsodium's own API
Private_X25519 = clamp(HKDF-SHA256(seed, info="rezvan-x25519-identity-v1"))  // domain-separated, not the raw seed
Public_X25519  = crypto_scalarmult_curve25519_base(Private_X25519)
Node_ID = SHA-256(Public_Ed25519)[0:8]
```
The seed itself is stored only inside Android Keystore-backed `EncryptedSharedPreferences` (see `IdentityBackupHelper.kt`); there is no plaintext fallback path.

**Unicast (Point-to-Point):**
- Session/ratchet state is handled by [`vodozemac`](https://github.com/matrix-org/vodozemac) (audited Rust Olm/Megolm implementation), not a hand-rolled Double Ratchet
- X3DH-style key exchange with signed prekeys + one-time prekeys, Double Ratchet forward secrecy (Root Key → Chain Keys → Message Keys) — all inside `vodozemac`
- Per-message AEAD as implemented by `vodozemac`'s Olm message format

**Group/Broadcast (sender keys):**
- Shared 32-byte symmetric key per channel, encrypted with XChaCha20-Poly1305 (24-byte nonce)
- Each message is additionally signed with the sender's own Ed25519 mesh identity key, so receivers can verify *which* member actually sent it (not just that some channel member did) — see `rezvan-crypto/src/sender_key.rs`
- **Not yet wired to any transport** — channels currently have no send/receive path; this module exists and is tested but isn't called from production code yet

### Power Management

**7 Power States:**

| State | Scan Interval | Scan Window | Use Case | Battery Threshold |
|-------|---------------|-------------|----------|-------------------|
| Emergency | 1000 ms | 500 ms | Crisis response | Any (user override) |
| Active | 1000 ms | 250 ms | High performance | > 80% |
| Balanced | 5000 ms | 250 ms | Recommended default | 51-80% |
| PowerSaver | 30000 ms | 100 ms | Extended operation | 31-50% |
| Minimal | 120000 ms | 50 ms | Survival mode | 16-30% |
| Hibernation | (off) | (off) | Radio sleeps | 6-15% |
| Dead | N/A | N/A | App non-functional | < 5% |

**Adaptive Scan Interval:**
- Rust engine computes state based on battery level + charging status
- Kotlin RadioService applies scan params via `BluetoothLeScanner.startScan(ScanSettings)`
- Duty cycle reduces power drain from ~5% per hour (Active) to <0.5% (Hibernation)

### BLE Advertisement Format (31 Bytes Exact)

```
Offset │ Size │ Field              │ Value
───────┼──────┼────────────────────┼──────────────────────
0-1    │ 2B   │ Protocol ID        │ 0x52 0x56 ("RV")
2-9    │ 8B   │ Node ID Hash       │ SHA-256(PubKey)[0:8]
10     │ 1B   │ Flags              │ [V]oice [F]ile [R]elay [W]iFi
11     │ 1B   │ Battery Level      │ 0-100 (255=charging)
12-13  │ 2B   │ Sequence Number    │ LE counter (dup detection)
14-17  │ 4B   │ Channel Mask       │ Bitmask (up to 32 channels)
18-19  │ 2B   │ Reserved           │ 0x0000
20-30  │ 11B  │ Padding            │ 0x00...
───────┴──────┴────────────────────┴──────────────────────
```

---

## Security Considerations

### Threat Model

| Threat | Capability | Mitigation |
|--------|-----------|-----------|
| **Eavesdropping** | Passive RF sniffing | XChaCha20-Poly1305 AEAD encryption, forward secrecy |
| **Spoofing** | Forge packets | Ed25519 signatures, invalid sigs dropped |
| **Replay** | Reuse old messages | Sequence numbers, timestamps in OGMs |
| **Jamming** | Disrupt BLE/WiFi | Frequency hopping (channels 37/38/39), adaptive scan |
| **Device Seizure** | Physical compromise | SQLCipher at rest (key in Android Keystore), PIN/password protection |
| **Identity Loss** | Lost/wiped device | **Unmitigated today** — no backup/recovery mechanism exists; losing the device or clearing app data permanently loses the identity |

### No Backdoors

- ✅ All code open-source (AGPL v3)
- ✅ Cryptography via libsodium (through the `sodiumoxide` Rust binding) and `vodozemac` (audited Olm/Megolm), not custom crypto primitives
- ⚠️ `sodiumoxide` itself is an unmaintained Rust crate; a scoped migration plan to `libsodium-sys-stable` exists (`rust/SODIUMOXIDE_MIGRATION.md`) but has not been applied
- ✅ No telemetry, analytics, or crash reporting to external services
- ✅ No phoning home, no implicit network calls in application code (note: `INTERNET` is currently declared in `AndroidManifest.xml`; no corresponding network call was found in the Kotlin source — origin/purpose not yet confirmed)
- ✅ Zero embedded accounts, no hardcoded keys

---

## Development Workflow

### Project Structure

```
RezvanMesh/
├── android/
│   ├── app/
│   │   ├── src/main/
│   │   │   ├── java/com/rezvani/mesh/
│   │   │   │   ├── MainActivity.kt
│   │   │   │   ├── MeshCore.kt              # JNI wrapper
│   │   │   │   ├── MeshServiceConnection.kt
│   │   │   │   ├── radio/
│   │   │   │   │   ├── RezvanRadioService.kt
│   │   │   │   │   ├── RadioController.kt
│   │   │   │   │   └── ActionDispatcher.kt
│   │   │   │   ├── ui/
│   │   │   │   │   ├── screens/
│   │   │   │   │   ├── components/
│   │   │   │   │   └── theme/
│   │   │   │   ├── data/
│   │   │   │   │   ├── AppDatabase.kt       # SQLCipher
│   │   │   │   │   ├── dao/
│   │   │   │   │   └── entities/
│   │   │   │   └── utils/
│   │   │   └── res/
│   │   │       ├── values/strings.xml
│   │   │       └── values-fa/strings.xml
│   │   └── build.gradle.kts
│   └── build.gradle.kts
├── rust/
│   ├── Cargo.toml                    # Workspace
│   ├── SODIUMOXIDE_MIGRATION.md      # scoped, not-yet-applied dependency migration plan
│   ├── rezvan-common/
│   │   ├── Cargo.toml
│   │   └── src/lib.rs
│   ├── rezvan-crypto/
│   │   ├── Cargo.toml
│   │   └── src/
│   │       ├── lib.rs
│   │       ├── identity.rs
│   │       ├── sign.rs
│   │       ├── sender_key.rs         # group/channel encryption (not yet wired to transport)
│   │       ├── hkdf.rs
│   │       ├── beacon_mac.rs
│   │       └── epoch_key.rs          # network-wide beacon authentication
│   └── rezvan-core/
│       ├── Cargo.toml
│       └── src/
│           ├── lib.rs                # JNI entry points
│           ├── engine.rs
│           ├── routing.rs
│           ├── session.rs            # vodozemac-backed 1:1 session management
│           ├── action.rs
│           ├── crypto.rs
│           └── power.rs
├── integration-tests/
│   ├── mesh_simulator.py
│   └── test_cases/
│       ├── test_2node_message.py
│       ├── test_5node_routing.py
│       └── test_emergency_broadcast.py
├── scripts/
│   ├── build_rust.sh
│   ├── verify_interfaces.py
│   └── sign_apk.sh
├── .github/workflows/
│   └── ci.yml
└── README.md
```

Note: no `Cargo.lock` is currently committed to the repository, so exact dependency versions (including `vodozemac` and `sodiumoxide`) are not pinned across builds.

### Build Pipeline

**Local Development:**
```bash
# 1. Compile Rust core + crypto
./scripts/build_rust.sh

# 2. Verify JNI interface consistency
python3 scripts/verify_interfaces.py

# 3. Build debug APK
./gradlew assembleDebug

# 4. Install on device
adb install -r app-debug.apk

# 5a. Run automated integration tests (simulator-based, no physical devices)
python3 scripts/run_integration_test.py
# or individually:
python3 integration-tests/test_cases/test_2node_message.py
python3 integration-tests/test_cases/test_5node_routing.py
python3 integration-tests/test_cases/test_emergency_broadcast.py

# 5b. For on-device verification, tail the log instead:
adb logcat -s RezvanMesh | grep -E "GATT|MESSAGE|ROUTE"
```

**CI/CD (GitHub Actions):** see `.github/workflows/ci.yml`
```yaml
# Trigger: push to main, PR, manual dispatch
# Single job ("build"), steps run in order:
#   1. cargo test -p rezvan-crypto           (rezvan-core / rezvan-common tests are NOT run in CI)
#   2. cargo ndk build --release -p rezvan-core   (cross-compile for arm64-v8a + armeabi-v7a)
#   3. ./gradlew assembleDebug
#   4. upload the debug APK as a workflow artifact
#
# Not currently present in CI: scripts/verify_interfaces.py, cargo clippy,
# cargo audit/deny, release signing, or delivery to any messaging bot.
```

### Testing Strategy

**Unit Tests (Rust):**
```bash
cargo test -p rezvan-core      # available locally; NOT currently run in CI
cargo test -p rezvan-crypto    # run in CI on every push/PR
```

**Integration Tests (automated, simulator-based — see `integration-tests/`):**
- [x] `test_2node_message.py` – 2-node message delivery, exercised via `mesh_simulator.py`
- [x] `test_5node_routing.py` – multi-hop routing across 5 simulated nodes
- [x] `test_emergency_broadcast.py` – SOS/emergency flooding behavior
- [ ] Real 2-device GATT delivery (physical hardware) — still pending, see Known Issues
- [ ] Voice broadcast playback on a real receiver — still pending, see Known Issues
- [ ] Power state transitions with real battery simulation on-device

**Diagnostics:**
- Real-time Status screen (mesh state, radio stats)
- Exportable diagnostic log (tap "Export" → Share)
- Crash dossier (rezvan-crash-TIMESTAMP.txt in Downloads)

---

## Known Issues & Limitations

### Critical Path (v1.0 Beta)

| Issue | Severity | Status | ETA |
|-------|----------|--------|-----|
| GATT message delivery (2 devices) | HIGH | Under test | This week |
| Voice playback on receiver | HIGH | Implementation pending | This week |
| 3+ device mesh stability | MEDIUM | Validation pending | This week |
| No identity backup/recovery | HIGH | Not implemented — no mnemonic, no export path exists | Unscheduled |
| No `Cargo.lock` committed | MEDIUM | Dependency versions (incl. `vodozemac`, `sodiumoxide`) are not pinned across builds | Unscheduled |
| `sodiumoxide` dependency unmaintained upstream | LOW-MEDIUM | Migration plan documented in `rust/SODIUMOXIDE_MIGRATION.md`, not yet applied | Unscheduled |

### Deferred to v1.1

- **WiFi Direct Transport** – Stubs only; low priority for initial release
- **Channel/Group Messaging** – UI/DB scaffolding exists, routing logic works, not yet wired
- **File Transfer** – Design exists, not implemented
- **Satellite Mode (LoRa)** – Out of scope; BLE-only for now

### Platform Limitations

- **Min SDK 26** (Android 8.0) – Earlier versions lack required BLE APIs
- **BLE Range** – ~100 meters outdoor LoS, ~10-20 meters indoors
- **Jamming Resistance** – Frequency hopping helps, but determined attacker with wideband jammer can disrupt
- **No Self-Destruct** – Messages don't auto-delete; encryption at rest is primary protection

---

## Performance Targets (Spec vs. Current)

| Metric | Target | Current | Status |
|--------|--------|---------|--------|
| 1-hop text delivery | <500 ms | TBD | Pending 2-device test |
| 5-hop delivery | <3 seconds | TBD | Pending 3-device test |
| Voice latency | <300 ms | TBD | Pending voice RX impl |
| Active mesh nodes | 50-100 | TBD | Pending 3+ device test |
| Battery drain (Balanced) | <5% per hour | ~3-4%/hr | Good, under budget |
| RAM usage | <150 MB | ~80-120 MB | Good |
| APK size | <20 MB | ~18 MB | ✅ Hit target |

---

## Deployment & Distribution

### Offline Distribution (No App Store)

1. **Initial Seed:**
   - APK hosted on USB drives, local NAS, or pre-installed on community devices

2. **Peer-to-Peer Sideload:**
   - Within app: Settings → "Share App"
   - Creates WiFi Direct hotspot or BLE transfer of APK to nearby device
   - Receiver prompted to enable "Install Unknown Apps" permission

3. **Integrity Verification:**
   - SHA-256 hash of APK published on trusted out-of-band channels (radio, posters, SMS)
   - User can verify hash in Settings before install

### APK Signing

```bash
# Generate keystore (one-time)
keytool -genkey -v -keystore rezvan.keystore -keyalg RSA -keysize 2048 -validity 10000

# Sign release APK
./scripts/sign_apk.sh android/app/build/outputs/apk/release/app-release.apk \
  -keystore rezvan.keystore \
  -storepass "$KEYSTORE_PASSWORD" \
  -alias rezvan_key \
  -keypass "$KEY_PASSWORD"

# Verify signature
jarsigner -verify -verbose rezvan.apk
```

---

## Contributing

### Code of Conduct

- **Security first** – Crypto bugs are life-threatening in this context
- **Simplicity over cleverness** – Maintainability is critical for long-term audits
- **Privacy by default** – No telemetry, no external calls, no shortcuts
- **Respect for users** – iranians relying on this during blackouts; failures = isolation

### Contributing Guidelines

1. **Fork & branch:** `git checkout -b fix/gatt-timeout`
2. **Test locally:** Build APK, test on Samsung A23 + one other device
3. **Lint & format:** `cargo fmt`, `ktlint`, Android Studio inspector
4. **PR with test report:** Attach diagnostic log (Settings → Export Diagnostics)
5. **Code review:** Two approvals before merge (one Kotlin, one Rust)

### Team Structure (Current)

- **Team A (Core):** Rust mesh engine, routing, power logic
- **Team B (Crypto):** libsodium integration, key exchange, Double Ratchet
- **Team C (Radio):** BLE/WiFi Direct radio control, action dispatch
- **Team D (UI):** Compose screens, SQLCipher, identity backup
- **Team E (Build):** CI/CD, Gradle, cross-compilation, signing

---

## Documentation

As of this writing, the following are the actual documentation files present in the repository (the previous list here referenced files — `Manifest.txt`, `Handover_paper.txt`, `Team_*.txt`, `Appendix_*.txt`, `Debug_Appendix.html` — that do not exist in this repo and have been removed from this section):

- **README.md** – This file
- **rust/SODIUMOXIDE_MIGRATION.md** – Scoped plan for replacing the unmaintained `sodiumoxide` dependency (not yet applied)
- Inline module-level doc comments in `rust/rezvan-crypto/src/*.rs` — several modules (`sender_key.rs`, `epoch_key.rs`, `identity.rs`) document specific security-audit findings and fixes directly above the relevant code

---

## Roadmap

### v1.0 (Beta, Target: June 2026)
- ✅ Mesh routing (BATMAN-Adv)
- ✅ Text messaging (encrypted)
- ✅ Voice broadcast (Opus codec)
- ✅ Emergency alerts (SOS)
- ⏳ End-to-end testing (2+ device)
- ⏳ Voice playback on receiver
- 🔲 WiFi Direct transport

### v1.1 (Production, Target: Q3 2026)
- 🔲 Channel/group messaging
- 🔲 File transfer (chunked, resumable)
- 🔲 WiFi Direct integration
- 🔲 Improved UI (Material 3 polish)
- 🔲 Performance optimization (routing convergence)

### v2.0 (Long-term)
- 🔲 LoRa/Satellite integration
- 🔲 Desktop client (Linux/macOS relay)
- 🔲 Peer reputation/sybil resistance
- 🔲 Formal security audit

---

## License

**AGPL v3** – This project is free and open-source software. Any derivative works must also be open-source and give credit to original authors.

**Why AGPL?** To ensure that anyone using Rezvan Mesh infrastructure (including centralized relay servers if ever deployed) must share improvements back to the community.

---

## Support & Contact

- **Issues:** GitHub Issues (public, searchable)
- **Security Concerns:** Email security review (contact maintainer privately)
- **Farsi Support:** Questions in Farsi welcome
- **Offline Help:** Build diagnostic log (Settings → Export), attach to issue

---

## Acknowledgments

- **libsodium authors** – Cryptographic foundation
- **Signal Protocol team** – Double Ratchet specification
- **BATMAN-Adv maintainers** – Routing protocol inspiration
- **Android community** – Jetpack Compose, Room, BLE best practices
- **Iranian open-source contributors** – Language support, testing feedback

---

## Disclaimer

**Use at your own risk.** While Rezvan Mesh is designed with security and privacy in mind, no software is perfect. The developers assume no liability for misuse, data loss, or communication failures. Always have a backup communication plan. Encryption is strong, but determined adversaries with physical access to devices or wideband jamming capability may still disrupt the network.

For Iranian users during internet shutdown
