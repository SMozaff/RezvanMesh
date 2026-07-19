# sodiumoxide → replacement migration plan (remediation #3)

**Not applied as code in this patch set.** This migration touches 6 files and
changes the exact bytes produced by cryptographic primitives; without a
working Rust toolchain to compile and run `cargo test -p rezvan-crypto`
against real test vectors, applying it blind risks silently breaking a
security guarantee rather than fixing one. Below is the exact scope so it
can be done properly with a compiler available.

## Exact API surface in use today

| File | sodiumoxide API | Purpose |
|---|---|---|
| `identity.rs` | `crypto::sign` (Ed25519 seed keypair) | Identity signing key |
| `identity.rs` | `crypto::scalarmult` (X25519 base point mult) | Identity ECDH key |
| `hkdf.rs` | `crypto::auth::hmacsha256`, `crypto::hash::sha256` | HKDF-SHA256 |
| `beacon_mac.rs` | `crypto::auth::hmacsha256`, `crypto::scalarmult` | Beacon MAC |
| `sender_key.rs` | `crypto::aead::xchacha20poly1305_ietf` | Group message AEAD |
| `sign.rs` | `crypto::sign` (detached sign/verify) | Message signing |
| `lib.rs` | `randombytes::randombytes` | CSPRNG |

## Recommended replacement

`libsodium-sys-stable` + a thin wrapper, rather than a RustCrypto swap:
keeps byte-for-byte identical output (same underlying C libsodium), so
**no test vectors change** — only the Rust binding layer does. This is the
lowest-risk path. A RustCrypto migration (e.g. `ed25519-dalek`,
`chacha20poly1305`, `hkdf` crate) is a viable alternative but changes the
implementation, not just the binding, and would need every existing test
vector re-verified against the new crate's output.

## Steps

1. Add `libsodium-sys-stable` to `rezvan-crypto/Cargo.toml`, remove `sodiumoxide`.
2. Replace each call site above with the equivalent `libsodium-sys` FFI call
   (thin `unsafe` wrapper functions, one per primitive — keep them in a
   single new `sodium_ffi.rs` module so the `unsafe` surface is auditable
   in one place rather than scattered across 6 files).
3. Run the full existing test suite (`cargo test -p rezvan-crypto`) —
   every existing test vector (HKDF RFC 5869 vector, spoofing tests, sign/
   verify round-trips) must still pass unchanged, since the underlying
   algorithm is identical.
4. Run `cargo audit` to confirm the new dependency tree has no known
   advisories.

## Priority / effort (unchanged from original proposal)

Priority: Low (no active exploit today). Effort: Medium — mechanical but
requires a working build environment to verify correctness before merge.
