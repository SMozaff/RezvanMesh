# Fixes Applied — Rezvan Mesh Remediation

Companion to `REMEDIATION_PROPOSAL.md`. Delivered as `remediation.patch` (apply with `git apply -p1 remediation.patch` or `patch -p1 < remediation.patch` from repo root) and as individual files in `updated_files.zip`.

**Important limitation:** this sandbox has no Rust or Kotlin/Gradle toolchain and no network access, so **none of this was compiled or run**. Every change was reviewed by hand against the actual surrounding code (types, field names, function signatures all cross-checked against the real source), but you must run `cargo build && cargo test --workspace` and a Gradle build before merging. Treat this as a carefully-written, unverified patch.

---

## Applied

### #4 — Dead `SessionState` struct → **Fixed**
Removed the leftover hand-rolled ratchet struct from `rezvan-crypto/src/lib.rs` and its re-export in `rezvan-core/src/crypto.rs` (which would otherwise have failed to compile once the struct was removed — caught this dependency by checking for all usages first). Replaced with a comment pointing to where session state actually lives now (`rezvan-core::session`, via `vodozemac`).

**Files changed:** `rust/rezvan-crypto/src/lib.rs`, `rust/rezvan-core/src/crypto.rs`

---

### #2 — Replay window on peer purge → **Fixed**
`RoutingTable::purge_stale` previously wiped `last_seen_seq` for any peer whose route was evicted, in the same pass. Decoupled the two: added a separate `replay_last_seen_tick` map so replay-sequence tracking survives route eviction and is only dropped after `REPLAY_RETENTION_MULTIPLIER` (8×) the normal route staleness window — long enough to cover a realistic reconnect, short enough to still bound memory growth.

Updated the existing test that asserted the *old* (vulnerable) behavior to instead assert the fix, and added a second test confirming replay tracking does eventually expire under prolonged silence (so this doesn't become an unbounded memory leak).

**Files changed:** `rust/rezvan-core/src/routing.rs`
**New/changed tests:** `test_purge_stale_retains_replay_tracking_after_route_eviction` (replaces the old test that expected sequence reset), `test_replay_tracking_eventually_expires_far_past_route_purge`

---

### #5 — Sender-key crypto "unwired" → **Correction, not a fix**
On closer inspection, this finding in the original audit was **wrong**. `MeshEngine::send_channel_message` and the `0x06` receive-side dispatch in `engine.rs` already fully wire `sender_key.rs` into the real transport path — sign-then-encrypt on send, independent per-sender identity verification on receive (never trusting the wire-embedded key blindly). This must have been missed on the first pass by relying on `sender_key.rs`'s own doc comment ("not yet wired") without checking `engine.rs` closely enough.

What was actually missing: engine-level integration tests exercising this path end-to-end (previous tests only covered `sender_key.rs` in isolation). Added those instead of a "fix" that wasn't needed.

**Files changed:** `rust/rezvan-core/src/engine.rs`
**New tests:** `test_channel_message_round_trip`, `test_channel_message_rejected_without_key`, `test_channel_message_forged_sender_rejected`, `test_channel_key_rotation_old_key_stops_working`

---

### #1 — SOS/emergency broadcast encryption status not surfaced → **Fixed, and corrected**
Also a correction to the original framing: `send_broadcast` (packet type `0x03`) is **always** signed-only and never encrypted — there is no "try encrypted, fall back to plaintext" branch. It's unconditional by design (an SOS needs to reach everyone immediately without a pre-established secure session with each recipient), not a fallback case as originally described.

Added:
- A notice in the confirmation dialog, shown before the user commits to sending, explaining the message will be signed but not encrypted and why.
- A persistent "sent unencrypted" badge on the success state, so the fact remains visible after sending, not just at the moment of confirmation.
- New strings added to both `values/strings.xml` (English) and `values-fa/strings.xml` (Farsi) — kept literal and conservative rather than idiomatic, since this is safety-critical text; recommend a native Farsi speaker review before shipping.

**Files changed:** `android/app/src/main/java/com/rezvani/mesh/ui/screens/EmergencyScreen.kt`, `android/app/src/main/res/values/strings.xml`, `android/app/src/main/res/values-fa/strings.xml`

---

## Not applied as code — plan only

### #3 — Unmaintained `sodiumoxide` dependency
Not migrated. This touches 6 files and changes the exact binding around every cryptographic primitive in the app (signing, HKDF, AEAD, beacon MAC, CSPRNG). Doing this without a compiler to verify against the existing test vectors is exactly the kind of change that could silently break a security guarantee instead of fixing one — not something to guess at.

Delivered instead: `rust/SODIUMOXIDE_MIGRATION.md` — exact API surface catalogued per file, recommended replacement (`libsodium-sys-stable`, byte-identical output, lowest risk), and the steps to do it properly once a build environment is available.

### #6 — Undocumented findings #5–#7
Not something fixable from the code — needs the maintainer's own memory or issue tracker. Still flagged in `RUST_ENGINE_AUDIT.md`.

### #7 — README/code drift
Already resolved in the earlier corrected `README.md` deliverable.

---

## Before merging

1. `cargo build --workspace && cargo test --workspace` — confirm everything compiles and all tests (old and new) pass.
2. Gradle build + lint for the Kotlin changes (`./gradlew assembleDebug`, `ktlint`).
3. Native Farsi review of the two new translated strings.
4. Manual on-device check that the confirmation dialog and success badge render correctly (long RTL text wrapping in particular).
5. When ready, work through `SODIUMOXIDE_MIGRATION.md` (#3) with a working compiler.
