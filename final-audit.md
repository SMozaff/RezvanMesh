# RezvanMesh Comprehensive Audit Report

**Date:** 2026-09-25  
**Scope:** Full codebase — Android/Kotlin frontend, Rust core (rezvan-core, rezvan-crypto, rezvan-common), CI, docs, tests  
**Status:** Audit complete. 8 of 31 findings remediated (see *Remediation Status*).

---

## Remediation Status

Eight findings from this report have been fixed in the working tree. Everything
below reflects the state **after** those fixes unless a row says otherwise.

| ID | Status | Where | Verified by |
|----|--------|-------|--------------|
| **C01** | **Fixed** | `rust/rezvan-core/src/persistence.rs` (new), `session.rs`, `routing.rs`, `engine.rs`, `lib.rs`, `rezvan-crypto/src/secure_store.rs` (new), `RezvanRadioService.kt`, `MeshCore.kt` | 8 new persistence unit tests + save/load round-trip asserting the Olm identity key survives a restart |
| **C02** | **Fixed** | `rust/rezvan-core/src/lib.rs` — engines now live in an `Arc<Mutex<MeshEngine>>` registry instead of raw `&mut` casts | 4 new concurrency tests (`registry_tests`), incl. destroy-while-in-use |
| **C03** | **Fixed** | `RezvanRadioService.kt`, `MainActivity.kt`, `MeshServiceConnection.kt` | Manual review; the native half is covered by the C02 tests |
| **C04** | **Fixed** | `RadioControllerImpl.kt` — `NODE_ID_OFFSET = 2` | Manual review |
| **C05** | **Fixed** | `BlePacketSender.kt` — rewritten with explicit success/failure signalling and a bounded queue | Manual review (needs a device to exercise) |
| **C06** | **Fixed** | `WifiPacketSender.kt`, `RadioControllerImpl.kt` | Manual review |
| **H02** | **Fixed** | `ChannelRepository.kt`, `ChannelQrCodec.kt` | New `ChannelQrCodecTest` (9 cases) |
| **H05** | **Fixed** | `ActionDispatcher.kt`, `RadioController.kt`, `RadioControllerImpl.kt` | New `ActionDispatcherTest` (7 cases) |

**Not yet remediated:** H01, H03, H04, and all M*/L* findings. H01 (channel-key
persistence in the Room schema) is now *substantially* addressed by C01 — the
keys now survive a restart via the engine state file — but the metadata and the
key are still stored in two unrelated places, which is a design wart worth
resolving separately.

### Verification performed

```
rust/  cargo test              118 passed, 0 failed  (16 common + 68 core + 34 crypto)
rust/  cargo clippy            clean for all new/changed code
                                  (3 pre-existing errors remain in hkdf.rs and
                                   rezvan-common/src/lib.rs; present before these
                                   changes and non-blocking in CI)
       scripts/verify_interfaces.py   passed — 20 Rust / 20 Kotlin externals matched
```

### Known gaps in the verification

- **The Android side was not compiled.** The Android SDK is absent and
  `dl.google.com` is unreachable from this environment, so
  `./gradlew :app:compileDebugKotlin` cannot resolve `com.android.application`.
  The Kotlin changes (C03–C06, H02, H05) and the two new test files are
  **unverified by a compiler**. Review them before merging.
- `cargo fmt --check` still reports 138 pre-existing diffs repo-wide. The two
  new Rust files were formatted; pre-existing files were left alone to avoid an
  unrelated 138-hunk diff. CI treats this as non-blocking.
- The C05/C06 transport fixes and the C01 persistence path have not been
  exercised on a real device. `nativeSaveState`/`nativeInit`-with-restore in
  particular need a two-session manual test.

---

## Executive Summary

RezvanMesh is an offline mesh messaging app with a **Kotlin/Android** frontend and **Rust** core (message encryption, routing, beacon auth, channel messaging). The codebase demonstrates solid cryptographic design in many areas (Olm E2EE, Gate 1 signed ACKs, sender-key group messaging, epoch-key beacon auth), but has **critical gaps in state persistence, service lifecycle safety, and transport-layer robustness** that would cause silent data loss, crashes, or security issues in production.

**Total findings: 31** (6 Critical, 5 High, 10 Medium, 10 Low/Latent)

---

## Critical Severity (Must Fix Before Release)

| # | Finding | File:Line | Impact | Remediation |
|---|---------|-----------|--------|-------------|
| **C01** | **Complete lack of native state persistence** — `SessionManager` (Olm sessions, peer keys, channel keys, epoch key, OTK state, routing table) is entirely in-memory. `nativeInit` ignores `storage_path` parameter. On service/process restart: all sessions lost, channel keys lost, epoch key regenerated, peers must re-exchange KeyAnnouncements. | `rezvan-core/src/lib.rs:46, 56-60`<br>`rezvan-core/src/session.rs:100-112` | Silent re-keying, message delivery failure after any service restart, forward secrecy break (new OTKs), peer spoofing window during re-convergence | Implement encrypted persistence layer (SQLCipher or secure file) for `SessionManager` state; use `storage_path` in `nativeInit`; serialize `sessions`, `peer_keys`, `channel_keys`, `epoch_key`, `epoch_number`, `otk_advertised`, `routing` on each `tick()` or mutation |
| **C02** | **JNI concurrent `&mut MeshEngine` access without synchronization** — `nativeProcessIncoming`, `nativeTick`, `nativeSendMessage*`, `nativeSendChannelMessage` all cast `core_ptr` to `&mut MeshEngine` with no mutex. Kotlin calls these from `serviceScope` (Default dispatcher) and `tickJob` concurrently. **Data race / UB**. | `rezvan-core/src/lib.rs:72, 104, 124, 172, 219, 249, 274, 319, 341, 379, 399, 404, 429, 443, 456`<br>`RezvanRadioService.kt:271-280` | Memory corruption, silent message loss, crashes, potential RCE | Add `parking_lot::Mutex<MeshEngine>` in Rust; expose thread-safe `tick()`, `process_incoming()`, `send_*()` that lock internally; or use `crossbeam::channel` for serialized command queue |
| **C03** | **Service lifecycle hazards** — `RezvanRadioService` uses `SupervisorJob` `serviceScope` but never cancels it or `tickJob` in `onDestroy`. `enginePtr` not zeroed after `nativeDestroy`. `MainActivity` never calls `unbindService` in `onDestroy`. `serviceStarted` flag not reset on `onServiceDisconnected` (only on bind failure). Stale static pointers → use-after-free, restart deadlock. | `RezvanRadioService.kt:125, 140, 271, 288, 306`<br>`MainActivity.kt:298`<br>`MeshServiceConnection.kt:168, 198` | Crash on service restart, leaked coroutines, native use-after-free, unable to rebind after unexpected disconnect | Cancel `serviceScope` and `tickJob` in `onDestroy`; zero `enginePtr` after `nativeDestroy`; add `unbindService` in `MainActivity.onDestroy`; reset `serviceStarted` in `onServiceDisconnected` |
| **C04** | **RadioControllerImpl beacon parsing uses wrong offset** — `NODE_ID_OFFSET = 3` but `BluetoothScanRecord.getManufacturerSpecificData()` returns manufacturer data **without** the company ID (2 bytes). Wire layout is `[version:1][type:1][originator:8]...` → originator at offset **2**, not 3. Misidentifies peers, breaks self-loop detection, prevents NodeId→MAC/GATT resolution. | `RadioControllerImpl.kt:64, 357-367, 387` | Peer discovery broken, routing table never populates, message delivery fails silently | Change `NODE_ID_OFFSET = 2`; add test with real scan record to verify |
| **C05** | **BlePacketSender silently drops packets on write failure** — `MAX_RETRIES = 3` defined but `onWriteComplete(false)` callback treats failure as success (`writeSuccess = true`), drops packet from queue, no retry, no error propagation. `queue` is unbounded `LinkedBlockingQueue`. | `BlePacketSender.kt:34, 131-137` | Silent message loss on GATT write congestion/timeout, unbounded memory growth | Fix callback to retry on failure up to `MAX_RETRIES`; add bounded queue with backpressure; propagate failure to `ActionDispatcher` |
| **C06** | **WifiPacketSender length field wraps at 65535** — `packet.size.toShort()` casts `Int` to `Short` without max-size check. Oversized frames truncate length prefix. Connection established outside output lock → race on concurrent sends. Daemon threads with 5s join can leak. | `WifiPacketSender.kt:28-31, 55-65`<br>`RadioControllerImpl.kt:890-920` | Frame corruption, connection race, thread leak, potential DoS | Add `require(packet.size <= 65535)`; move `ensureConnected` inside lock; use non-daemon threads with proper shutdown |

---

## High Severity

| # | Finding | File:Line | Impact | Remediation |
|---|---------|-----------|--------|-------------|
| **H01** | **Channel key persistence gap** — `channel_keys` only in `SessionManager` memory. `ChannelEntity`/`ChannelDao` store metadata only. `ChannelsViewModel.createChannel` silently succeeds without key if no active service. Password-based private join marks joined without installing key. | `session.rs:61`<br>`ChannelEntity.kt:6-17`<br>`ChannelsViewModel.kt:70, 108`<br>`ChannelsScreen.kt:106-113` | Channel messages undecryptable after service restart; private channels joinable without key | Persist channel keys alongside metadata (encrypted with DB key); make `createChannel` require active service; install key in password-join flow |
| **H02** | **Channel ID signedness defect** — `ChannelRepository.generateChannelId` applies `and 0x7FFFFFFF` only to final byte due to infix bitwise associativity. IDs can be negative. `ChannelQrCodec` accepts signed `Int`; Rust `lib.rs:389` converts via `channel_id as u32`. | `ChannelRepository.kt:180-183`<br>`ChannelQrCodec.kt:35`<br>`lib.rs:389` | Negative channel IDs, QR code interop failure, potential UB in Rust | Fix to `(bytes[0].toInt() and 0x7F) shl 24 | ...` or use `ByteBuffer`; validate in `ChannelQrCodec` |
| **H03** | **Private channel auth weaknesses** — `joinPrivateChannel` returns success when `passwordHash` is null. Password hashing = unsalted SHA-256. No rate limiting on password attempts. | `ChannelRepository.kt:125-129, 145`<br>`ChannelsScreen.kt:106-113` | Private channels creatable/joinable without auth; rainbow-table vulnerable | Require non-null password hash on create; add salt + Argon2id; rate-limit join attempts |
| **H04** | **Routing `process_beacon` updates replay state before MAC verification** — lines 145-152 insert sequence into `last_seen_seq` for **unverified** beacons. Attacker can inject high-sequence unverified beacon → suppresses later legitimate verified beacons from same originator. | `routing.rs:145-152, 154-161` | Replay-protection bypass, routing poisoning, peer suppression | Move `last_seen_seq`/`replay_last_seen_tick` updates inside `if (verified)` block |
| **H05** | **ActionDispatcher missing `SendWifiPacket` (action type 0x02)** — `when (action.type)` handles 0x01, 0x03, 0x04, 0x05, 0x06, 0x07 but not 0x02. `SendWifiPacket` exists in Rust `action.rs:9` and is serialized, but never dispatched on Android. | `ActionDispatcher.kt:45-85`<br>`action.rs:9, 62-68` | Wi-Fi Direct send path completely non-functional (latent — no Rust code currently emits it) | Add `0x02` case calling `radioService?.sendWifiPacket(...)` |

---

## Medium Severity

| # | Finding | File:Line | Impact | Remediation |
|---|---------|-----------|--------|-------------|
| **M01** | **`adv_sequence` / `ogm_sequence` wrap without wrap-safe replay comparison** — `wrapping_add` on `u32`; routing replay check uses `sequence <= last` (not wrap-aware). After ~4B beacons (~136 years at 1/sec), sequence wraps to 0 and all new beacons rejected until route purged (120 ticks ≈ 2 min). | `engine.rs:53-60, 129`<br>`routing.rs:146-148, 338-340` | Permanent routing blackhole for that peer until purge (acceptable given timeframe, but design flaw) | Document as accepted limitation; or implement wrap-aware comparison (e.g., `(sequence - last) < 2^31`) |
| **M02** | **Signed packet parser accepts trailing bytes** — `process_incoming` checks `raw_packet.len() < expected_len` but not `!=`. Trailing bytes after signature silently accepted. | `engine.rs:216-226` | Protocol strictness gap; potential smuggling | Change to `if raw_packet.len() != expected_len { reject }` |
| **M03** | **Action serialization truncates counts/lengths** — `actions.len() as u8`, `data.len() as u16`. Rust sender can emit >255 actions or >64KB payloads; Kotlin parser `parseActions` validates but Rust has no cap. | `action.rs:47, 118`<br>`ActionDispatcher.kt:90-120` | Serialization mismatch, potential panic on Kotlin side | Add `require(actions.len() <= 255)` in `serialize_actions`; cap payload sizes |
| **M04** | **HKDF no output-length enforcement** — `hkdf.rs` documents max 32×255=8160 bytes but doesn't enforce; `i as u8` counter wraps silently for larger outputs. | `hkdf.rs:10-37` | Silent wrong output for oversized requests | Add `require(length <= 32 * 255, "HKDF output too long")` |
| **M05** | **BLE advertisement payload 31 vs 24 byte mismatch** — `action.rs:110-115` pads to 31 bytes; `RadioControllerImpl.kt:457` truncates to 24 for legacy advertising. `AdvBeaconExt` is 24 bytes. Not a wire bug (Android truncates) but confusing. | `action.rs:110-115`<br>`RadioControllerImpl.kt:457` | Wasted CPU, log noise | Align to 24 bytes in Rust; remove padding |
| **M06** | **Unbounded `pendingPacketsByMac` and `relayed_seen`** — `ConcurrentHashMap<String, MutableList<ByteArray>>` and `HashMap<NodeId, HashSet<u32>>` grow without bounds. No eviction for peer queues; `relayed_seen` only evicted with replay retention (8× route TTL). | `RadioControllerImpl.kt:70`<br>`routing.rs:57, 446-456` | Memory exhaustion under sustained traffic/attacks | Add per-peer queue caps; evict oldest on overflow; add global memory budget |
| **M07** | **GATT reassembly `u16` fragment count can wrap** — `BleFragmenter` uses `u16` for `total` fragments; sender doesn't enforce receiver's 64KB cap. Fragmented packet >64KB wraps `total`. | `BleFragmenter.kt:13, 45-55`<br>`BleReassembler.kt:70` | Reassembly corruption, silent data loss | Enforce max packet size at sender (64KB); reject oversized |
| **M08** | **Wi-Fi Direct permission gap on Android 13+** — `MainActivity` requests location perms only. `RadioControllerImpl.hasLocationPermission()` ignores `NEARBY_WIFI_DEVICES`. Discovery/connect fails with `SecurityException`. | `MainActivity.kt:280-290`<br>`RadioControllerImpl.kt:560-570` | Wi-Fi Direct non-functional on API 33+ | Add `NEARBY_WIFI_DEVICES` to manifest + runtime request; update `hasLocationPermission` |
| **M09** | **`MeshServiceConnection._receivedMessages` unbounded append-only** — `MutableList<ReceivedMessage>` grows forever. No consumer found; only used in tests. | `MeshServiceConnection.kt:105, 120` | Memory leak in long sessions | Add size cap + eviction; or remove if unused |
| **M10** | **FileStorageManager path traversal latent** — `saveFile` uses `File(path)` without canonicalization check. No production callers found, but latent if ever exposed. | `FileStorageManager.kt:45-60` | Path traversal if attacker controls input | Add `canonicalPath` validation against allowed roots |

---

## Low / Latent / Design Tradeoffs

| # | Finding | File:Line | Notes |
|---|---------|-----------|-------|
| **L01** | **Epoch key design: single compromised device can forge any peer's beacons** | `epoch_key.rs:12-21` | **Documented accepted tradeoff** — explicit product decision for robustness over blast-radius limitation. Not a defect. |
| **L02** | **`sender_key.rs` docs claim "channels have no send/receive wiring"** | `sender_key.rs:20-24` | **Stale documentation** — production wiring exists in `engine.rs:900-921`, `RezvanRadioService.kt`, `ChannelsViewModel.kt`, `ChannelDetailViewModel.kt` |
| **L03** | **Database recreation on broad exception** | `AppDatabase.kt:21-47` | `openOrRecreate` catches `Throwable`, deletes DB file, recreates. Silent data loss risk on corruption |
| **L04** | **Backup policy inconsistency** | `AndroidManifest.xml`, `backup_rules.xml`, `data_extraction_rules.xml`, `file_paths.xml` | Manifest `allowBackup=false` but XML rules include broad data sets; FileProvider grants full filesystem trees |
| **L05** | **Crash dossier in user-visible Downloads** | `RezvanApplication.kt:180-220` | `writeCrashDossier` writes device/build/stack to MediaStore Downloads — privacy exposure |
| **L06** | **`joinPrivateChannel` null password returns success** | `ChannelRepository.kt:125-129` | Overlaps with H03; also null password accepted |
| **L07** | **`ContactsRepository` uses plaintext file `contacts.txt`** | `ContactsRepository.kt:17-45` | No encryption; contacts readable by other apps with storage access |
| **L08** | **`DiagLogger` session ID reused across app runs** | `DiagLogger.kt:35` | `UUID.randomUUID().take(8)` — 8-char collision possible but low risk |
| **L09** | **Diagnostics test `test_one_time_key_rotation` validates bundle length 169 but `key_bundle()` includes capability extension** | `RadioControllerImpl.kt:630-656` | Test correct; docs should note 169 = 164 + 5 capability bytes |
| **L10** | **`sodiumoxide` deprecated → migration to `libsodium-sys` planned** | `SODIUMOXIDE_MIGRATION.md` | Tracked separately; not a runtime bug |

---

## Complete File Reference Map (All Modified/Examined)

| File | Role | Key Findings |
|------|------|--------------|
| `rust/rezvan-core/src/lib.rs:41-61, 72-467` | JNI boundary | C02, C03, M03, H05 |
| `rust/rezvan-core/src/engine.rs:1-1695` | MeshEngine core | C01, M01, M02, H04 (via routing) |
| `rust/rezvan-core/src/session.rs:1-592` | Olm + channel keys | C01, H01 |
| `rust/rezvan-core/src/routing.rs:1-776` | Routing + replay | C01, H04, M01, M06 |
| `rust/rezvan-core/src/action.rs:1-218` | Action serialization | M03, M05, H05 |
| `rust/rezvan-crypto/src/epoch_key.rs:1-211` | Beacon auth | L01 |
| `rust/rezvan-crypto/src/sender_key.rs:1-215` | Channel encryption | H01 (docs), L02 |
| `rust/rezvan-crypto/src/hkdf.rs:1-148` | Key derivation | M04 |
| `android/app/src/main/java/com/rezvani/mesh/radio/RezvanRadioService.kt:1-320` | Foreground service | C03 |
| `android/app/src/main/java/com/rezvani/mesh/radio/RadioControllerImpl.kt:1-1020` | BLE/WiFi transport | C04, C05, C06, M06, M07, M08 |
| `android/app/src/main/java/com/rezvani/mesh/radio/ActionDispatcher.kt:1-100` | Action → transport | H05 |
| `android/app/src/main/java/com/rezvani/mesh/radio/BlePacketSender.kt:1-150` | GATT write queue | C05 |
| `android/app/src/main/java/com/rezvani/mesh/radio/WifiPacketSender.kt:1-100` | WiFi Direct send | C06 |
| `android/app/src/main/java/com/rezvani/mesh/radio/BleFragmenter.kt:1-120` | GATT fragmentation | M07 |
| `android/app/src/main/java/com/rezvani/mesh/MeshCore.kt:1-70` | JNI loader | C02, C03 |
| `android/app/src/main/java/com/rezvani/mesh/MeshServiceConnection.kt:1-210` | Service binding | C03, M09 |
| `android/app/src/main/java/com/rezvani/mesh/MainActivity.kt:1-320` | App lifecycle | C03, M08 |
| `android/app/src/main/java/com/rezvani/mesh/data/ChannelRepository.kt:1-200` | Channel metadata | H02, H03 |
| `android/app/src/main/java/com/rezvani/mesh/data/ChannelEntity.kt:1-20` | Room entity | H01 |
| `android/app/src/main/java/com/rezvani/mesh/data/ChannelsViewModel.kt:1-150` | Channel UI logic | H01 |
| `android/app/src/main/java/com/rezvani/mesh/ui/screens/ChannelsScreen.kt:1-150` | Channel list UI | H03 |
| `android/app/src/main/java/com/rezvani/mesh/utils/ChannelQrCodec.kt:1-80` | QR encode/decode | H02 |
| `android/app/src/main/java/com/rezvani/mesh/storage/FileStorageManager.kt:1-100` | File storage | L10 |
| `android/app/src/main/java/com/rezvani/mesh/RezvanApplication.kt:1-250` | Crash logging | L05 |
| `android/app/src/main/AndroidManifest.xml` | Permissions/backup | L04, M08 |
| `android/app/src/main/res/xml/backup_rules.xml` | Backup config | L04 |
| `android/app/src/main/res/xml/data_extraction_rules.xml` | Data extraction | L04 |
| `android/app/src/main/res/xml/file_paths.xml` | FileProvider | L04 |
| `android/app/src/test/...` | Unit/integration tests | Validation of fixes |

---

## Remediation Priority Order (Execution Sequence)

### Phase 1: Critical Safety (Weeks 1-2)
1. **C02** — Add `Mutex<MeshEngine>` in Rust JNI layer; serialize all native calls
2. **C03** — Fix service lifecycle: cancel scope, zero pointer, unbind, reset flag
3. **C04** — Fix `NODE_ID_OFFSET = 2` in `RadioControllerImpl`
4. **C05** — Fix `BlePacketSender` retry logic + bounded queue
5. **C06** — Fix `WifiPacketSender` length check + lock ordering + thread cleanup
6. **C01** — Design persistence schema; implement `SessionManager::save/load` + wire to `storage_path`

### Phase 2: Correctness & Auth (Weeks 2-3)
7. **H01** — Add `channelKey` column to `ChannelEntity`; encrypt with DB key; persist on create/join
8. **H02** — Fix `generateChannelId` bitwise; add validation in `ChannelQrCodec`
9. **H03** — Require password on private channel create; Argon2id + salt; rate-limit join
10. **H04** — Move replay-state update inside `verified` block in `routing.rs:145-161`
11. **H05** — Add `SendWifiPacket` dispatch in `ActionDispatcher`

### Phase 3: Protocol Hardening (Week 3-4)
12. **M02** — Exact-length check in `process_incoming`
13. **M03** — Caps in `serialize_actions`
14. **M04** — HKDF length enforcement
15. **M01** — Document or fix wrap-safe sequence comparison
16. **M05** — Align BLE adv payload to 24 bytes in Rust

### Phase 4: Resource Bounds & Permissions (Week 4)
17. **M06** — Per-peer queue caps + global budget for `pendingPacketsByMac` and `relayed_seen`
18. **M07** — Enforce 64KB max packet at `BleFragmenter` sender
19. **M08** — Add `NEARBY_WIFI_DEVICES` perm + runtime request
20. **M09** — Cap/remove `_receivedMessages`
21. **M10** — Canonical path validation in `FileStorageManager`

### Phase 5: Privacy & Hygiene (Week 5)
22. **L03** — Narrow `openOrRecreate` catch; user confirmation before DB delete
23. **L04** — Align backup rules with `allowBackup=false` or remove XML
24. **L05** — Move crash dossier to app-private storage; add user consent
25. **L07** — Encrypt `contacts.txt` or move to Room
26. **L02** — Update `sender_key.rs` docs to reflect production wiring

---

## Deferred / Requires Runtime Verification

| Item | Why Deferred | Verification Method |
|------|--------------|---------------------|
| Multi-device BLE/Wi-Fi interop | Requires physical devices | Integration test suite on 3+ phones |
| Battery/power-state behavior | Hardware-dependent | Field testing with `DiagnosticsScreen` |
| Olm session recovery after persistence | Needs C01 complete | Unit test: serialize → restart → decrypt |
| Epoch key convergence across restarts | Needs C01 complete | Simulate offline → online with ratchet gap |
| Wi-Fi Direct throughput/stability | Real-world RF | Measure with `scripts/benchmark.sh` |

---

## Test & CI Gaps to Address

| Gap | Location | Action |
|-----|----------|--------|
| No JNI concurrency test | `rezvan-core/src/lib.rs` | Add `test_concurrent_native_calls` using `std::thread` |
| No persistence round-trip test | `session.rs`, `engine.rs` | Add `test_engine_persistence_restart` |
| No beacon offset test | `RadioControllerImpl.kt` | Mock `ScanRecord` with known manufacturer data |
| No Wi-Fi Direct permission test | `MainActivity.kt` + `RadioControllerImpl.kt` | Robolectric test with API 33+ context |
| CI runs only `cargo test` + `./gradlew test` | `.github/workflows/ci.yml` | Add `cargo clippy`, `cargo audit`, `detekt`, `./gradlew connectedAndroidTest` on emulator |

---

## Next Steps (Build Mode)

This audit is complete. The findings above are evidence-backed with exact file:line references.

**Already done:** C01–C06, H02, H05 (8 findings). Remaining: H01, H03, H04, and
all M*/L* findings — see the *Remediation Priority Order* section, which is
still accurate for what's left.

### Immediate Actions Available:
1. **Compile the Android side** — the Kotlin changes are unverified; this is the
   highest-value next step and is blocked only on an environment with the Android SDK.
2. **H01** — Persist channel keys in the Room schema so the key and its metadata
   live in one place (C01 makes them durable, but they're still split across two stores).
3. **H04** — Move the replay-state update inside the `verified` block in `routing.rs`.
4. **H03** — Require a password on private-channel creation; add salt + Argon2id.
5. **Quick wins** — H05 is done; M02/M03/M04 are each a few lines in the Rust core.