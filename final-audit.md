# RezvanMesh Comprehensive Audit Report

**Date:** 2026-09-25  
**Scope:** Full codebase — Android/Kotlin frontend, Rust core (rezvan-core, rezvan-crypto, rezvan-common), CI, docs, tests  
**Status:** Audit complete. 22 of 31 findings remediated. See *Remediation Status*.

---

## Remediation Status

| ID | Status | Where |
|----|--------|-------|
| **C01** | Fixed | `rust/rezvan-core/src/persistence.rs` (new), `session.rs`, `routing.rs`, `engine.rs`, `lib.rs`, `rezvan-crypto/src/secure_store.rs` (new), `RezvanRadioService.kt`, `MeshCore.kt` |
| **C02** | Fixed | `rust/rezvan-core/src/lib.rs` — `Arc<Mutex<MeshEngine>>` registry replaces raw `&mut` casts |
| **C03** | Fixed | `RezvanRadioService.kt`, `MainActivity.kt`, `MeshServiceConnection.kt` |
| **C04** | Fixed | `RadioControllerImpl.kt` — `NODE_ID_OFFSET = 2` |
| **C05** | Fixed | `BlePacketSender.kt` — rewritten; bounded queue, explicit success/failure |
| **C06** | Fixed | `WifiPacketSender.kt`, `RadioControllerImpl.kt` |
| **H02** | Fixed | `ChannelRepository.kt`, `ChannelQrCodec.kt` |
| **H03** | Fixed | `ChannelPasswordHasher.kt` (new), `JoinThrottle.kt` (new), `ChannelRepository.kt`, `ChannelDao.kt`, `ChannelsViewModel.kt`, `CreateChannelScreen.kt`, `ChannelsScreen.kt` |
| **H04** | Fixed | `routing.rs` — unverified beacons touch no state |
| **H05** | Fixed | `ActionDispatcher.kt`, `RadioController.kt`, `RadioControllerImpl.kt` |
| **M02** | Fixed | `engine.rs` — exact frame length for signed *and* unsigned packets |
| **M03** | Fixed | `action.rs` — action-count and payload-length caps; **also fixed a frame-desync bug** (see N2) |
| **M04** | Fixed | `hkdf.rs` — `MAX_OUTPUT_LEN` enforced |
| **M06** | Fixed | `RadioControllerImpl.kt` — `ConcurrentLinkedQueue` + `computeIfAbsent` + per-peer cap |
| **M07** | Fixed | `BleFragmenter.kt`, `BlePacketSender.kt` — shared size ceiling |
| **M08** | Fixed | `AndroidManifest.xml`, `MainActivity.kt`, `RadioControllerImpl.kt` |
| **M09** | Fixed | `MeshServiceConnection.kt` — bounded, O(1) window |
| **M10** | Fixed | `FileStorageManager.kt` — name sanitisation + containment check |
| **L02** | Fixed | `sender_key.rs` — stale "nothing calls this" doc |
| **L03** | Fixed | `AppDatabase.kt` — wipe only on wrong-key evidence; rename not delete |
| **L04** | Fixed | `data_extraction_rules.xml`, `backup_rules.xml` |
| **L05** | Fixed | `RezvanApplication.kt` — crash dossier moved out of Downloads |

**Not remediated:** H01 (partially addressed by C01), M01, M05, M06's routing-side
half (`relayed_seen` bounds), L01 (documented tradeoff), L07, L08, L09, L10.

### New findings discovered during remediation

These were not in the original 31. Each was found while fixing an adjacent
defect, confirmed by reading the code, and fixed in the same pass.

| ID | Severity | Finding | Where |
|----|----------|---------|-------|
| **N1** | **High** | **Multi-hop OGM propagation was silently broken.** `last_seen_seq` was a *single* high-water mark shared by two independent senders' counters: `adv_sequence` (beacons, every tick) and `ogm_sequence` (signed packets, only when one is built). Whichever ran ahead starved the other, so a peer's OGM carrying sequence 5 was rejected as "stale" immediately after its beacon carrying sequence 50 was accepted. Split into `last_beacon_seq` / `last_packet_seq`. | `routing.rs` |
| **N2** | **High** | **`DiagLog` action desynchronised every frame that contained one.** The serializer wrote the message *length* but never the message *bytes*. Kotlin's `offset += payloadLen` walk then overshot, so any action after a diagnostic in the same batch was silently dropped — including `NotifyUi`. Since `DiagLog` is emitted for every packet rejection, a `[DiagLog, NotifyUi]` batch (e.g. a rejected packet alongside a valid message) lost the message. | `action.rs` |
| **N3** | Medium | `pendingPacketsByMac` used `getOrPut` (not atomic on `ConcurrentHashMap`) on a `MutableList` (not thread-safe), so concurrent sends to one peer could discard each other's packets. | `RadioControllerImpl.kt` |
| **N4** | Medium | `pendingPacketsByMac` was unbounded *and* unevicted for peers that never complete service discovery — a slow OOM at up to 64 KiB per queued packet. | `RadioControllerImpl.kt` |
| **N5** | Medium | `MeshServiceConnection._receivedMessages` retained the full plaintext of every message the process ever decrypted, with no consumers, and copied the whole list on each arrival (quadratic). | `MeshServiceConnection.kt` |
| **N6** | Medium | `FileStorageManager.readFile`/`deleteFile` took an arbitrary absolute path with no validation — a general-purpose "read/delete any file the app can" primitive. | `FileStorageManager.kt` |
| **N7** | Medium | `AppDatabase.openOrRecreate` caught bare `Exception` and deleted the database unconditionally, so any unrelated bug on the open path (bad migration, SQL typo, out of space) destroyed all message history. | `AppDatabase.kt` |
| **N8** | Medium | Crash dossiers (device fingerprint, git SHA, stack trace, 200 diag lines containing peer NodeIds and MAC fragments) were written to `MediaStore.Downloads` — user-visible, media-scanner-indexed, world-readable on older releases. | `RezvanApplication.kt` |

### Verification

Verified by GitHub CI (per project instruction — no local builds or tests).

Phase 1 (C01–C06, H02, H05) went through CI and passed, which required several
follow-up commits to fix Kotlin compile errors in the new tests — so the Android
side is genuinely compiled and unit-tested in CI, and "it builds" is a real
signal here rather than an assumption.

Phase 2 (this batch) is **not yet through CI**. Rust-side reasoning and
brace/symbol/import consistency were checked by inspection, but nothing in this
batch has been compiled or run.

New tests added across both phases: 4 (JNI registry/concurrency), 8
(persistence), 6 (action frame integrity), 5 (HKDF bounds), 2 (routing
replay/sequence-space), 9 (`ChannelQrCodec`), 12 (`ChannelPasswordHasher`), 9
(`JoinThrottle`), 7 (`ActionDispatcher`), 5 (fragmenter bounds).

### Known gaps

- **This batch has not been through CI yet.** Expect the same class of follow-up
  fixes phase 1 needed. The riskiest spots, in the order I would look at them
  if CI complains:
  - `ChannelRepository.joinPrivateChannel` — the `Mutex`/`withLock` refactor
    around suspend DAO calls. A `synchronized` block was the first attempt and
    would not have compiled (non-suspend lambda).
  - `ChannelPasswordHasherTest` — Kotlin string escaping in the malformed-input
    table mixes `"$"` and `"\$"`; both are legal but worth an eyeball.
  - `action.rs` tests — the byte-comparison assertions were written without a
    compiler, so `assert_eq!` type inference is the thing to check.
- The transport and persistence fixes (C01, C05, C06) have not been exercised on
  a device. `nativeSaveState` and restore need a two-session manual test.
- `cargo fmt --check` still reports 138 pre-existing diffs repo-wide; new Rust
  files are formatted, pre-existing ones left alone. CI treats this as
  non-blocking.

---

## Executive Summary

RezvanMesh is an offline mesh messaging app with a **Kotlin/Android** frontend and **Rust** core (message encryption, routing, beacon auth, channel messaging). The codebase demonstrates solid cryptographic design in many areas (Olm E2EE, Gate 1 signed ACKs, sender-key group messaging, epoch-key beacon auth), but had **critical gaps in state persistence, service lifecycle safety, transport-layer robustness, and action-frame integrity** that caused silent data loss, crashes, and security issues.

**Total findings: 31 original + 8 discovered during remediation = 39** (6 Critical, 7 High, 17 Medium, 10 Low/Latent)

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

**Done:** C01–C06, H02–H05, M02–M04, M06–M10, L02–L05 (22 original findings),
plus 8 new findings found and fixed during the work.

### Remaining, in rough priority order

1. **Push this batch to CI.** Phase 2 is written but unverified; CI is the
   reviewer.
2. **H01** — Channel keys now survive restarts (C01), but the key and its
   metadata still live in two unrelated stores. Consolidating the key into the
   `channels` table would make "joined" and "can decrypt" the same fact instead
   of two that can disagree.
3. **L07** — `ContactsRepository` writes a plaintext `contacts.txt`. Move to Room
   (already encrypted) and delete the file.
4. **M05** — Align the BLE advertisement action to 24 bytes so it stops being
   padded to 31 and truncated on every transmit.
5. **M01 / L09** — Sequence-wrap and stale-doc items, both acceptable as-is.
   Document the wraparound rather than adding wrap-aware comparison.
6. **L10** — `sodiumoxide` → `libsodium-sys` migration, already tracked in
   `rust/SODIUMOXIDE_MIGRATION.md`.
7. **L08** — Collapse the 8-char `DiagLogger` session id to a full UUID; the
   collision risk is negligible but the fix is trivial.