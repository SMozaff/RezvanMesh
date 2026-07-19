# Rezvan Mesh – Decentralized Off-Grid Communication

[![License: AGPL v3](https://img.shields.io/badge/License-AGPL%20v3-blue.svg)](https://www.gnu.org/licenses/agpl-3.0)
![Platforms](https://img.shields.io/badge/platform-Android%208.0%2B-brightgreen)
![Language](https://img.shields.io/badge/languages-Kotlin%20%7C%20Rust%20%7C%20C-orange)
![Status](https://img.shields.io/badge/status-Beta%20(Functional%20Testing)-yellow)

> **Note:** This README has been corrected against the actual Rust source (see `RUST_ENGINE_AUDIT.md`). The previous version described identity derivation from a device MAC address; the code has never worked that way — identity is generated from a `SecureRandom` CSPRNG seed. This and a few other stale claims are fixed below.

---

## Mission

**Rezvan Mesh** is a peer-to-peer mesh communication application for Android devices. It enables resilient, off-grid messaging during nationwide internet shutdowns and infrastructure failures — without relying on cellular towers, internet connectivity, or centralized servers.

**Target Scenario:** Iranian civilians during communications blackouts, with extreme power efficiency and resilience under jamming/SIGINT threats.

**Core Principle:** Device-to-device communication only. Zero external dependencies. All encryption on-device.

---

## Features (Current Release)

### ✅ Implemented & Tested

- **Mesh Routing** – BATMAN-Adv-style protocol over BLE advertisement + GATT unicast
- **End-to-End Encryption** – X3DH + Double Ratchet via `vodozemac` (audited Rust Olm implementation), AEAD via libsodium
- **Text Messaging** – Up to 10,000 characters per message
- **Voice Broadcasting** – Opus codec @ 16 kbps, push-to-talk up to 60 seconds
- **Emergency Alerts** – SOS button with 5 severity levels, network-wide flooding
- **Offline Identity** – CSPRNG-generated seed (`SecureRandom`, 32 bytes), domain-separated HKDF-SHA256 key derivation, 12-word BIP39 backup
- **Encrypted Storage** – SQLCipher database for contacts, messages, voice logs
- **Multi-Language UI** – Farsi (primary) + English, runtime switchable
- **Power Management** – 7-state power machine (Emergency → Hibernation) with dynamic duty cycling
- **Diagnostics** – Real-time routing/radio stats, searchable log export, crash dossiers

### ⏳ In Development

- **GATT Message Delivery** – End-to-end 2-device test pending
- **Voice Playback on Receiver** – MediaPlayer integration incomplete
- **3+ Device Mesh Stability** – Multi-hop routing validation needed
- **WiFi Direct Transport** – Stubs only, deferred to v1.1
- **Group/Channel Messaging** – Sender-key crypto is implemented and tested in `rezvan-crypto`, but not yet wired to any transport layer

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
│  BATMAN-Adv-style Routing            │
│  Packet Processing & Crypto Wrapper  │
└──────────────────────────────────────┘
            ↕ FFI
┌──────────────────────────────────────┐
│   CryptoProvider (Rust)              │
│  Ed25519 Signing (libsodium)         │
│  X3DH + Double Ratchet (vodozemac)   │
│  XChaCha20-Poly1305 AEAD             │
└──────────────────────────────────────┘
```

### Technology Stack

| Layer | Language | Framework | Rationale |
|-------|----------|-----------|-----------|
| UI | Kotlin | Jetpack Compose | Modern, concise, Material 3 support |
| Radio Service | Kotlin | Android Framework APIs | Native BLE/WiFi Direct access |
| Mesh Engine | Rust | JNI | Memory safety, no GC, real-time capable |
| Cryptography | Rust | `vodozemac` (X3DH/Double Ratchet) + `sodiumoxide` (Ed25519, AEAD) | Audited ratchet implementation; constant-time primitives |
| Database | Kotlin | Room + SQLCipher | Encrypted at rest, type-safe queries |
| Build System | Gradle + Cargo | cargo-ndk | Android NDK cross-compilation |

> **Dependency note:** `sodiumoxide` is effectively unmaintained upstream. The underlying `libsodium` C library is still fine; the Rust binding itself has stagnated. Worth a migration plan (e.g. to `libsodium-sys` directly or a RustCrypto equivalent) even though there's no known active vulnerability today.

---

## Quick Start

### Prerequisites

- **Android SDK:** API 26 (Android 8.0) or higher, target API 34
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
   - Tap "Create Identity" → 32 random bytes generated via `SecureRandom`, expanded into an Ed25519 + X25519 keypair via domain-separated HKDF-SHA256
   - System displays 12-word BIP39 mnemonic (user writes it down)
   - Confirm phrase, set optional nickname
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
   - Message encrypted end-to-end (X3DH + Double Ratchet via `vodozemac`), routed via mesh
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
   - **Important:** if no encrypted session exists yet with a given recipient, emergency broadcasts fall back to **plaintext** by design, to guarantee delivery in a crisis. The UI should make this visible per-message so users know when a broadcast wasn't encrypted.

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

### Mesh Routing (BATMAN-Adv style)

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
- Staleness purge uses logical clocks (no wall-clock dependency at this layer). Note: purging a peer resets its replay-sequence tracking, so a peer that leaves and later rejoins gets a fresh sequence window — a small, documented tradeoff rather than a hidden gap.

### Encryption

**Identity Generation (actual implementation):**
```rust
Seed = SecureRandom(32 bytes)                          // generated on-device, Kotlin side
(Private_Ed25519, Private_X25519) = HKDF-SHA256(Seed, domain-separated info strings)
Public_Ed25519 = crypto_sign_ed25519_seed_keypair(Private_Ed25519)
Public_X25519  = crypto_scalarmult_curve25519_base(Private_X25519)
Node_ID = SHA-256(Public_Ed25519)[0:8]
```
Identity key material is derived **only** from a securely generated random seed — never from a MAC address, IMEI, Android ID, or any other device-identifying value.

**Unicast (Point-to-Point):**
- X3DH key exchange with signed prekeys + one-time prekeys, via `vodozemac`
- Double Ratchet: Root Key → Chain Keys → Message Keys (handled internally by `vodozemac`)
- Per-message AEAD: XChaCha20-Poly1305 (32-byte nonce, 16-byte tag)
- Forward secrecy: old chain keys deleted after use
- KeyAnnouncement messages are bound to their claimed Node ID (`SHA-256(pubkey) == NodeId` check) to prevent identity-spoofing attacks

**Group/Broadcast (implemented, not yet wired to transport):**
- Sender key: 32-byte shared symmetric key per channel
- Sign-then-encrypt: Ed25519 signature over plaintext, then XChaCha20-Poly1305 AEAD
- Rotate on membership change

**Beacon Authentication:**
- 7-byte truncated HMAC, sized to fit within the 24-byte legacy BLE beacon payload
- Provides deterrence against casual spoofing, not strong non-repudiation — deliberately documented as such in code; a determined attacker with compute resources could brute-force it

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

> Node IDs are 64 bits (SHA-256 truncated to 8 bytes). This is fine at the mesh's realistic scale; it would only become a meaningful collision risk at a global scale of billions of concurrent nodes.

---

## Security Considerations

### Threat Model

| Threat | Capability | Mitigation |
|--------|-----------|-----------|
| **Eavesdropping** | Passive RF sniffing | XChaCha20-Poly1305 AEAD encryption, forward secrecy |
| **Spoofing** | Forge packets or impersonate a Node ID | Ed25519 signatures; KeyAnnouncement bound to claimed Node ID; invalid sigs dropped |
| **Replay** | Reuse old messages | Per-originator sequence numbers, strictly enforced independent of auth |
| **Jamming** | Disrupt BLE/WiFi | Frequency hopping (channels 37/38/39), adaptive scan |
| **Device Seizure** | Physical compromise | SQLCipher at rest (key in Android Keystore), PIN/password protection |
| **Identity Loss** | Lost device | 12-word BIP39 mnemonic recovery (manual process) |

### No Backdoors

- ✅ All code open-source (AGPL v3)
- ✅ Cryptography via audited libraries (`vodozemac` for X3DH/Double Ratchet, `libsodium` for signing/AEAD), not custom-built ratchets
- ✅ No telemetry, analytics, or crash reporting to external services
- ✅ No phoning home, no implicit network calls
- ✅ Zero embedded accounts, no hardcoded keys

### Known Gaps (as of this audit — see `RUST_ENGINE_AUDIT.md` for detail)

- Emergency broadcasts (SOS) fall back to plaintext when no session exists yet with a recipient — a deliberate delivery-guarantee tradeoff that should be visible to the user, not just documented in code.
- Peer purge resets replay-sequence tracking for that peer, creating a narrow window for old-sequence replay from a rejoining peer.
- `sodiumoxide` (crypto dependency) is unmaintained upstream — no known vulnerability today, but a supply-chain risk worth a migration plan.
- No formal third-party security audit has been performed yet (see Roadmap).

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
│   ├── rezvan-common/
│   │   ├── Cargo.toml
│   │   └── src/lib.rs
│   ├── rezvan-crypto/
│   │   ├── Cargo.toml
│   │   └── src/
│   │       ├── lib.rs
│   │       ├── identity.rs
│   │       ├── hkdf.rs
│   │       ├── sign.rs
│   │       └── sender_key.rs         # group/channel crypto (not yet wired)
│   └── rezvan-core/
│       ├── Cargo.toml
│       └── src/
│           ├── lib.rs                # JNI entry points
│           ├── engine.rs
│           ├── routing.rs
│           ├── session.rs            # X3DH/Double Ratchet via vodozemac
│           ├── action.rs
│           └── power.rs
├── scripts/
│   ├── build_rust.sh
│   ├── verify_interfaces.py
│   └── sign_apk.sh
├── .github/workflows/
│   └── ci.yml
└── README.md
```

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

# 5. Run integration tests (manual 2-device test)
adb logcat -s RezvanMesh | grep -E "GATT|MESSAGE|ROUTE"
```

**CI/CD (GitHub Actions):**
```yaml
# Trigger: push to main, PR
# Jobs:
#   1. build-rust (cargo test, cargo build)
#   2. interface-check (Python signature verification)
#   3. build-apk (Gradle assembleDebug/assembleRelease)
#   4. deliver-to-bale (optional, send APK to Bale Messenger bot)
```

### Testing Strategy

**Unit Tests (Rust):** 65 tests across the workspace, including spoofing, replay, malformed-input, and cryptographic correctness cases.
```bash
cargo test -p rezvan-core
cargo test -p rezvan-crypto
cargo test -p rezvan-common
```

**Integration Tests (Manual, pending automation):**
- [ ] 2-device message delivery (GATT)
- [ ] 3-device multi-hop routing (A→B→C)
- [ ] Voice broadcast playback
- [ ] Power state transitions with battery simulation
- [ ] Emergency broadcast flooding

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

### Deferred to v1.1

- **WiFi Direct Transport** – Stubs only; low priority for initial release
- **Channel/Group Messaging** – Sender-key crypto implemented and tested; UI/DB scaffolding and routing logic exist; not yet wired end-to-end
- **File Transfer** – Design exists, not implemented
- **Satellite Mode (LoRa)** – Out of scope; BLE-only for now

### Platform Limitations

- **Min SDK 26** (Android 8.0) – Earlier versions lack required BLE APIs
- **BLE Range** – ~100 meters outdoor LoS, ~10-20 meters indoors
- **Jamming Resistance** – Frequency hopping helps, but a determined attacker with a wideband jammer can still disrupt the network
- **No Self-Destruct** – Messages don't auto-delete; encryption at rest is the primary protection

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
- **Respect for users** – Iranians relying on this during blackouts; failures = isolation

### Contributing Guidelines

1. **Fork & branch:** `git checkout -b fix/gatt-timeout`
2. **Test locally:** Build APK, test on Samsung A23 + one other device
3. **Lint & format:** `cargo fmt`, `ktlint`, Android Studio inspector
4. **PR with test report:** Attach diagnostic log (Settings → Export Diagnostics)
5. **Code review:** Two approvals before merge (one Kotlin, one Rust)

### Team Structure (Current)

- **Team A (Core):** Rust mesh engine, routing, power logic
- **Team B (Crypto):** libsodium/vodozemac integration, key exchange, Double Ratchet
- **Team C (Radio):** BLE/WiFi Direct radio control, action dispatch
- **Team D (UI):** Compose screens, SQLCipher, identity backup
- **Team E (Build):** CI/CD, Gradle, cross-compilation, signing

---

## Documentation

- **Manifest.txt** – Full technical specification (31 KB)
- **Handover_paper.txt** – Module contracts, JNI boundaries, integration plan
- **Team_*.txt** – Detailed work packages for each module
- **Appendix_*.txt** – Downstream issues, sodiumoxide API notes, debug observations
- **Debug_Appendix.html** – Development environment constraints, debug phases
- **RUST_ENGINE_AUDIT.md** – Full manual audit of the Rust engine (this repository)

---

## Roadmap

### v1.0 (Beta, Target: June 2026)
- ✅ Mesh routing (BATMAN-Adv style)
- ✅ Text messaging (encrypted)
- ✅ Voice broadcast (Opus codec)
- ✅ Emergency alerts (SOS)
- ⏳ End-to-end testing (2+ device)
- ⏳ Voice playback on receiver
- 🔲 WiFi Direct transport

### v1.1 (Production, Target: Q3 2026)
- 🔲 Channel/group messaging (wire up existing sender-key crypto)
- 🔲 File transfer (chunked, resumable)
- 🔲 WiFi Direct integration
- 🔲 Improved UI (Material 3 polish)
- 🔲 Performance optimization (routing convergence)
- 🔲 Migrate off unmaintained `sodiumoxide` dependency

### v2.0 (Long-term)
- 🔲 LoRa/Satellite integration
- 🔲 Desktop client (Linux/macOS relay)
- 🔲 Peer reputation/sybil resistance
- 🔲 Formal third-party security audit

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
- **vodozemac / Matrix.org team** – Audited X3DH + Double Ratchet implementation
- **Signal Protocol team** – Double Ratchet specification
- **BATMAN-Adv maintainers** – Routing protocol inspiration
- **Android community** – Jetpack Compose, Room, BLE best practices
- **Iranian open-source contributors** – Language support, testing feedback

---

## Disclaimer

**Use at your own risk.** While Rezvan Mesh is designed with security and privacy in mind, no software is perfect. The developers assume no liability for misuse, data loss, or communication failures. Always have a backup communication plan. Encryption is strong, but determined adversaries with physical access to devices or wideband jamming capability may still disrupt the network. This project has not yet undergone a formal third-party security audit — treat it as beta software under active hardening.

For Iranian users during internet shutdown
