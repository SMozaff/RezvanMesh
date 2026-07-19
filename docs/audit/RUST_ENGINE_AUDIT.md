# Rezvan Mesh — Rust Engine Audit

**Scope:** Full manual review of `rezvan-common`, `rezvan-crypto`, `rezvan-core` (3,720 lines, 65 unit tests traced against logic).
**Not covered:** Kotlin/Android layer, BLE/GATT transport implementation, build/CI pipeline, formal cryptographic proof review.
**Status:** Informal internal-style audit. Does not replace a third-party professional security audit.

---

## Summary

The Rust engine is in materially better shape than the project's README suggests. It has already been through at least one self-review cycle: 8 numbered "security audit finding" comments (`#1`–`#4`, `#8`–`#10`) are documented and fixed directly in the code, each backed by a regression test. This is not first-draft code.

The single biggest issue found is **documentation drift, not implementation weakness** — several `README.md` claims describe an earlier, less secure design that the code has since moved past.

---

## 1. Encryption

| Component | Finding |
|---|---|
| 1:1 messaging | Delegates to **`vodozemac`** (audited Rust Olm/Double Ratchet library used by Matrix), not a hand-rolled ratchet. Strong, correct choice — hand-rolled Double Ratchets are the most common failure point in projects like this. |
| Identity keys | Ed25519 (signing) + X25519 (ECDH), both derived from one CSPRNG seed via **domain-separated HKDF-SHA256** (finding #2). Correct construction; prevents cross-primitive key leakage. |
| HKDF-SHA256 | Correct RFC 5869 implementation, verified against the official test vector. Includes a real bug fix for long-salt truncation (finding #3), with regression test. |
| Group messaging (`sender_key.rs`) | Sign-then-AEAD-encrypt (XChaCha20-Poly1305 + Ed25519). Correctly avoids trusting a wire-embedded public key blindly (finding #10). **Not yet wired to any transport** — documented as such in its own comments, not a live feature. |
| Beacon authentication | 7-byte truncated HMAC (finding #3) — the only size that fits a legacy 24-byte BLE beacon. Code honestly documents this as brute-forceable and offering no non-repudiation; a deterrence control, not strong crypto, and not oversold as such. |

## 2. Identity & Anti-Spoofing

- **Seed generation is `SecureRandom` (Kotlin), not MAC-derived.** Traced end-to-end: `OnboardingViewModel.kt` generates 32 random bytes via `SecureRandom().nextBytes(seed)`; the in-code comment directly above it states *"Identity always comes from a securely generated random seed"* — this reads as a fix note left in place after moving off an earlier MAC-based design. No code path anywhere derives identity key material from a MAC address, IMEI, or Android ID.
- **KeyAnnouncement spoofing is defended against and tested** (finding #1): a node cannot claim another node's NodeId while embedding its own public keys. The engine checks `SHA256(embedded_pubkey) == claimed_NodeId` before trusting any announcement. Covered by `test_spoofed_key_announcement_is_rejected`.

## 3. Networking & Robustness

- **Replay protection**: strict per-originator sequence-number monotonicity, enforced independently of authentication, tested against both exact-replay and reordering.
- **All wire parsers are bounds-checked**, with fuzz-style tests confirming no panics on truncated or garbage input — important since this data comes straight from untrusted BLE radio input.
- **Protocol version gate**: clean breaking bump (0x01→0x02) enforced at parse time rather than left to each caller to check (finding #4).
- **Routing**: BATMAN-adv-style path metric with battery-aware link weighting; staleness purge uses logical clocks (no wall clock at this layer). One disclosed tradeoff: purge resets replay-sequence tracking for that peer, so a purged-then-rejoining attacker could replay old sequence numbers within a bounded window. This is a real (small) gap but is acknowledged in the code, not hidden.
- **Emergency broadcasts (opcode 0x03)** fall back to **plaintext** if no session exists with the recipient — a deliberate public-safety tradeoff per code comments. **Recommend surfacing this in the UI**, not just in code, so a user under this threat model always knows whether a given message is encrypted.

## 4. Findings Already Fixed In-Code

| # | Area | Status |
|---|---|---|
| 1 | KeyAnnouncement spoofing | Fixed, tested |
| 2 | Domain-separated key derivation | Fixed, tested |
| 3 | HKDF long-salt truncation / beacon MAC size | Fixed, tested |
| 4 | Protocol version confusion | Fixed, tested |
| 5–7 | *(no comment trail found anywhere in the codebase)* | **Unknown — see below** |
| 8 | *(engine.rs)* | Fixed, tested |
| 9 | *(engine.rs)* | Fixed, tested |
| 10 | Sender-key trust-on-wire-key issue | Fixed, tested |

**Open question for the maintainer:** findings #5, #6, and #7 are never referenced anywhere in the code. Worth confirming whether they were fixed without a comment, tracked elsewhere (issue tracker, private notes), or the numbering simply skipped ahead. Not a code defect by itself, but a documentation/traceability gap.

## 5. Other Notes

- **`sodiumoxide` (crypto dependency) is effectively unmaintained upstream.** Not a known vulnerability today, but a supply-chain risk worth a migration plan given the threat model — libsodium itself (the underlying C library) is fine; it's the Rust binding that's stagnant.
- **NodeId is 64 bits** (SHA-256 truncated to 8 bytes). Fine at the expected scale of a regional mesh; would need revisiting only if the network ever grew to roughly billions of concurrent nodes (birthday-bound risk).
- **Dead code**: a `SessionState` struct (root_key/chain_key/ratchet fields) survives in `rezvan-crypto/lib.rs` from a retired hand-rolled ratchet design, now superseded by `vodozemac`. Harmless but could mislead a future reader into thinking it's the active session logic. Recommend removing.
- `adv_sequence` wraps at `u32::MAX` — acknowledged in-code as low severity; self-heals via purge/rediscovery.

## 6. Bottom Line

For pre-1.0 beta software without a formal external audit, this codebase reflects real threat modeling: tested attack scenarios (spoofing, replay, malformed input), an audited third-party ratchet instead of custom crypto, and honest in-code documentation of what each primitive does *not* guarantee.

The README is the weak link — it describes an earlier, less secure identity scheme that the code no longer implements. A third-party professional cryptographic audit is still warranted before wide deployment, especially given the stated threat model (state-level surveillance, device seizure risk), but the underlying engine is a sound foundation rather than a cause for concern.
