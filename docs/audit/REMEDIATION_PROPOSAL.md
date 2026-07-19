# Rezvan Mesh — Remediation Proposal

Companion to `RUST_ENGINE_AUDIT.md`. Each item below is a deficiency actually found in the code, with a concrete proposed fix, priority, and rough effort.

---

## 1. Emergency Broadcast Plaintext Fallback

**Deficiency:** SOS messages send unencrypted if no session exists yet with the recipient. Content and metadata are readable by anyone listening.

**Proposal:** Keep the fallback — dropping delivery guarantees during a real emergency is worse than the exposure. Add an explicit per-message UI indicator ("⚠ sent unencrypted") whenever a broadcast goes out without an established session, so the user's trust in the system matches reality.

**Priority:** High
**Effort:** Small (UI-only; crypto/transport logic unchanged)

---

## 2. Replay Window on Peer Purge

**Deficiency:** Purging a stale peer from routing state resets its sequence-number tracking. A peer that reconnects after the purge timeout — or an attacker replaying a captured old packet — can slip old messages back in during that window.

**Proposal:** Separate liveness state from replay state. Keep routing/liveness purge as-is (short timeout, keeps the mesh table lean), but persist last-seen sequence number per NodeId in a longer-lived replay cache that isn't cleared on routing purge. Alternative/simpler fix: extend the purge timeout past realistic reconnect windows, though this only narrows the gap rather than closing it.

**Priority:** Medium
**Effort:** Small–Medium (new persistent map keyed by NodeId, tested against reconnect + replay scenarios)

---

## 3. Unmaintained `sodiumoxide` Dependency

**Deficiency:** The Rust binding around libsodium is stagnant upstream. No known live vulnerability, but no path to security patches if one surfaces.

**Proposal:** Migrate to `libsodium-sys-stable` (thinner, actively maintained binding) or a RustCrypto-native equivalent for the primitives currently used (Ed25519 sign/verify, XChaCha20-Poly1305). Should be scoped as an isolated PR since the API surface used here is small (see `sign.rs`, beacon MAC, session AEAD calls).

**Priority:** Low (no active exploit) but should be tracked, not indefinitely deferred
**Effort:** Medium (dependency swap + re-verify all crypto test vectors pass identically)

---

## 4. Dead `SessionState` Struct

**Deficiency:** Leftover fields (`root_key`, `chain_key`, `ratchet`) from the retired hand-rolled ratchet still exist in `rezvan-crypto/lib.rs`, now superseded by `vodozemac`. Not exploitable, but risks misleading a future contributor or external auditor into treating it as live logic.

**Proposal:** Delete the struct and any dead references. Confirm via `cargo build` + full test suite that nothing silently depends on it.

**Priority:** Low
**Effort:** Trivial

---

## 5. Group/Channel Crypto Unwired

**Deficiency:** `sender_key.rs` (sign-then-AEAD group messaging) is implemented and tested in isolation, but not connected to any transport. Not a bug today — but a likely source of new bugs if wired in under time pressure for v1.1 without re-validation.

**Proposal:** Before wiring channels into the transport layer, add integration tests that exercise `sender_key.rs` through the actual engine/routing path (not just unit tests in isolation), covering key rotation on membership change and multi-recipient delivery.

**Priority:** Medium (blocking for v1.1, not urgent today)
**Effort:** Medium

---

## 6. Missing Audit Findings #5–#7

**Deficiency:** In-code comments document findings #1–#4 and #8–#10 with fixes and tests. Findings #5, #6, #7 are referenced nowhere in the codebase — a traceability gap, not a code flaw.

**Proposal:** Maintainer should confirm and document one of: (a) these were fixed without a comment trail — locate and annotate them, (b) they're tracked in an external issue tracker or private notes — link them from the code, or (c) numbering was skipped — state that explicitly. This should be resolved before any third-party audit engagement, since an external auditor will ask the same question.

**Priority:** Medium (credibility/process issue, not technical)
**Effort:** Trivial (documentation only, once the answer is known)

---

## 7. README/Code Drift

**Deficiency:** Previous README described MAC-derived identity and referenced non-existent files (`x3dh.rs`, `ratchet.rs`) from a superseded design.

**Status:** ✅ Already fixed — corrected `README.md` delivered separately, verified against actual source.

**Proposal (ongoing):** Add a lightweight CI check or PR checklist item requiring README updates whenever `rezvan-crypto` or `rezvan-core` module structure changes, to prevent this drift from recurring.

**Priority:** Low (already resolved), but the process fix prevents recurrence
**Effort:** Trivial

---

## Summary Table

| # | Issue | Priority | Effort | Status |
|---|---|---|---|---|
| 1 | SOS plaintext fallback not surfaced in UI | High | Small | Open |
| 2 | Replay window on peer purge | Medium | Small–Medium | Open |
| 3 | Unmaintained `sodiumoxide` | Low | Medium | Open |
| 4 | Dead `SessionState` struct | Low | Trivial | Open |
| 5 | Sender-key crypto unwired to transport | Medium | Medium | Open (blocks v1.1) |
| 6 | Findings #5–#7 undocumented | Medium | Trivial | Open |
| 7 | README/code drift | Low | Trivial | ✅ Fixed |

**Recommended order:** #1 and #6 first (cheap, high trust value) → #4 (trivial cleanup) → #2 (real security gap, contained fix) → #5 (before any v1.1 channel work starts) → #3 (scheduled, not urgent).

None of these are "crypto is broken" findings — the core X3DH/Double Ratchet/signing path via `vodozemac` held up under review. These are hardening and hygiene items appropriate for pre-audit cleanup.
