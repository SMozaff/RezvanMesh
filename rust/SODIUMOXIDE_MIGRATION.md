# sodiumoxide → replacement migration (remediation #3)

**Status: APPLIED. `sodiumoxide` and its vendored C `libsodium` are gone from the
dependency tree, replaced by the RustCrypto crates `vodozemac` already used.**

The previously proposed approach was investigated first and found to be a no-op
— see "Why the original plan is wrong". What was done instead, and why, is
below.

## The finding that matters: the bundled C library is ~5 years stale

`sodiumoxide` 0.2.7 is the latest release, dated **21 June 2021** (its
`CHANGELOG.md`). There has been no release since. Its 0.2.7 entry reads:

> * Update libsodium submodule to stable branch commit 8acd227

So the C library compiled into the shipped binary is the libsodium that was
current in mid-2021. **Every libsodium security fix published since then is
absent from the app**, and there is no mechanism to obtain one short of
replacing the dependency.

This is the concrete risk, and it is materially larger than "the Rust wrapper
is unmaintained":

- `cargo audit` **cannot detect it.** It inspects Rust advisories
  (`RUSTSEC-*`) in the dependency graph. A C-level advisory in the vendored
  libsodium does not appear there, so the `rust-audit` CI job will stay green
  indefinitely while the underlying library ages. The audit job documented
  above would not catch this — it is not a control for this risk.
- The C library is what actually does the cryptography. Ed25519, X25519,
  XChaCha20-Poly1305 and HMAC-SHA256 are all implemented in C underneath, so a
  defect or a fix there applies to every one of them.

Practical consequence: this should be treated as a **medium-priority** item, not
the "Low priority, no active exploit" the original assessment claimed. It is
still not a release blocker — no libsodium advisory is known to this codebase
today — but it is a standing, silent, growing exposure, and the fact that the
usual dependency-scanning control does not cover it is the reason to act.

## What the current state actually is

`rezvan-crypto` depends on `sodiumoxide 0.2.7`, which is **itself a thin safe
wrapper over `libsodium-sys 0.2.7`** (confirmed in `sodiumoxide`'s own
`Cargo.toml`, and visible in this repo's `Cargo.lock` as a transitive entry).

`libsodium-sys 0.2.7` is not a pure-Rust crate. Unless `SODIUM_USE_PKG_CONFIG`
or `SODIUM_LIB_DIR` is set, its `build.rs` compiles a **vendored copy of the C
libsodium** from source by running autotools:

```text
./configure   →   make   →   make install
```

That build already runs today for the Android NDK cross-compile in CI
(`cargo ndk -t arm64-v8a -t armeabi-v7a … build --release --locked`).

## Why the original plan is wrong

The original plan recommended:

> `libsodium-sys-stable` + a thin wrapper, rather than a RustCrypto swap: keeps
> byte-for-byte identical output … This is the lowest-risk path.

It is the lowest-*mechanical-risk* path, but it does not address the problem the
migration exists to solve, and it is not low-risk overall:

| Claim | Reality |
|---|---|
| Swaps an unmaintained crate for a maintained one | **No.** The replacement is either the same `libsodium-sys 0.2.7` already in the lock, or `-stable`, which repackages the same C library — still pinned to a mid-2021 libsodium. It would not gain a single upstream security fix. |
| Keeps the build model identical | Yes — because the C build is already happening. This is the one genuine benefit, and it is also why the change buys nothing. |
| "Lowest risk" | Lowest risk of the *options*, but it converts a safe API into `unsafe` FFI across 6 files, and hand-rolled wrappers must correctly handle return codes, buffer lengths, and key zeroisation. New code to get wrong, in exchange for no reduction in risk. |

Net: a pure downside. It would replace a safe wrapper with `unsafe` FFI and
still ship the same stale C library.

## The two real liabilities

1. **A ~5-year-old C cryptography library, with no upgrade path.** See the
   finding at the top. This is the one that matters, and it is invisible to
   `cargo audit`.
2. **Build fragility.** A vendored autotools C library inside a mobile NDK
   build is the part most likely to *break* — autotools is being dropped from
   newer distributions, and NDK toolchain changes break vendored C routinely.
   Independent of issue 1, and it produces loud build failures rather than silent
   ones.

An option only worth taking is one that addresses **both**: drop the C
toolchain dependency entirely and move to maintained, pure-Rust primitives.

## What was actually done

Migrated to the **RustCrypto** crates — not `sodim`, and not the original plan.

The deciding observation is that `vodozemac` (the Olm / Double Ratchet
implementation in `rezvan-core`) **already depends on** `ed25519-dalek`,
`x25519-dalek`, `chacha20poly1305`, `hmac`, `sha2`, `hkdf`, `zeroize` and
`subtle`. Every primitive this migration needed was therefore *already compiled
into the binary and already covered by `cargo audit`* — the message encryption,
the most security-critical part of the app, was pure Rust all along. Only
`rezvan-crypto`'s own thin wrapper layer was still on the C library.

That made RustCrypto strictly better than `sodim` here: a second
libsodium-compatible implementation would have been a *new* dependency to trust
and audit, in exchange for nothing, whereas RustCrypto reused what was already
there.

| Was | Now |
|---|---|
| `sodiumoxide::crypto::sign` | `ed25519-dalek` (`SigningKey`, `VerifyingKey::verify_strict`) |
| `sodiumoxide::crypto::scalarmult` | `x25519-dalek` (`StaticSecret`, `PublicKey`) |
| `sodiumoxide::crypto::auth::hmacsha256` | `hmac` + `sha2` |
| `sodiumoxide::crypto::hash::sha256` | `sha2` |
| `sodiumoxide::crypto::aead::xchacha20poly1305_ietf` | `chacha20poly1305` (`XChaCha20Poly1305`) |
| `sodiumoxide::randombytes` | `getrandom` |

Net effect on the dependency graph: **8 fewer packages**, `sodiumoxide` and
`libsodium-sys` removed entirely, and no C toolchain anywhere in the build. The
Android NDK cross-compile no longer runs autotools.

### Byte-compatibility

The wire format and the on-disk state format are unchanged. Every primitive is
deterministic and specified:

- **Ed25519** (RFC 8032) — deterministic keygen and signing. Signature bytes
  are identical, so packets signed by an old build verify on a new one.
  Verification is *stricter* than before: `verify_strict` additionally rejects
  small torsion components in `R`, which libsodium's `verify_detached` did not
  check. Since honest signing is deterministic, no legitimate signature is
  affected -- only adversarially constructed ones. See `sign::verify`.
- **X25519** (RFC 7748) — both implementations clamp the scalar identically.
- **HMAC-SHA256 / HKDF** (RFC 2104 / 5869) — identical for every salt this
  application uses, with one documented exception. All four call sites
  (`secure_store`, `epoch_key`, `identity`, `beacon_mac`) pass an **empty**
  salt, for which every path agrees exactly. For a *hypothetical* salt of
  33–64 bytes the output does change: the boundary for hashing a key down is
  the HMAC **block size (64)**, not the hash output size (32). The old code
  hashed anything over 32 bytes, because sodiumoxide's `hmacsha256::Key` is a
  fixed 32-byte type and could not hold more. The current behaviour is what
  RFC 2104 specifies; the old boundary was an artefact of the wrapper. Pinned by
  `salt_hash_down_boundary_is_the_hmac_block_size`.
- **XChaCha20-Poly1305** — same 24-byte random nonce, same layout.

### How that is verified

The safety argument rests on output being byte-identical, so it is pinned by
tests rather than asserted in a comment:

- **RFC 8032 §7.1 test vectors** in `sign.rs` assert the exact signature bytes
  for two published (seed, public key, message, signature) tuples. These come
  from the specification, not from a value captured out of this
  implementation, so the test checks conformance rather than self-consistency.
- **RFC 5869 test vector 1** was already present and is unchanged.
- New HKDF tests pin the two properties the salt rewrite specifically relied
  on: an absent salt behaves exactly as 32 zero bytes, and the
  hash-down boundary sits precisely between 32 and 33 bytes.
- A new ECDH symmetry test asserts both directions of a key agreement agree —
  if they ever diverged, every beacon MAC would fail and the mesh would
  silently stop forming.
- `IdentityKeypair` now zeroizes its key material on drop, and there is a test
  for that plus one confirming a clone is an independent copy.

### A note on trusting documentation for pinned versions

Two of the API details here were checked against **both** the vendored crate
source and external documentation, and they disagreed in ways that mattered:

- A docs lookup for `chacha20poly1305` returned the **latest** (0.11) API --
  `AeadCore`/`Generate` traits, `Array<u8, U24>`, `.as_ref()` payloads. The
  pinned version is 0.10.1 with `aead` 0.5.2, which instead uses
  `KeyInit` + `Payload { msg, aad }`. Written from the latest docs, this code
  would not have compiled.
- The `ed25519-dalek` 2.2.0 docs revealed that `verify_strict` is documented as
  *non-RFC-8032-compliant* and performs an extra malleability check. Reading
  only the source had confirmed the method existed, not what it did.

So: for a pinned-version migration, the lock file and vendored source are the
authority, and external docs are a useful second opinion that has to be
reconciled against them rather than followed.

### Independent cross-verification (known-answer tests)

The concern with a round-trip test suite is that it proves *self-consistency*:
if a derivation were changed consistently across the codebase, every test would
still pass while the protocol had silently changed. So the derivations this
migration touches are also pinned against implementations that share no code
with the Rust one:

| Derivation | Independent reference |
|---|---|
| Ed25519 keygen + signing | RFC 8032 §7.1 vectors, and OpenSSL |
| X25519 key agreement | RFC 7748 §5.2 vector, and OpenSSL |
| HKDF-SHA256 | RFC 5869 test case 1, and Python stdlib `hmac` |
| Identity derivation (both halves) | OpenSSL + stdlib, via `scripts/generate_known_answer_vectors.py` |
| Beacon MAC tag | OpenSSL X25519 + stdlib HMAC |
| Beacon epoch tag and ratchet | stdlib HMAC |
| On-disk state key | stdlib HMAC |
| XChaCha20-Poly1305 | `draft-arciszewski-xchacha-03` A.1 (no OpenSSL equivalent — the 24-byte nonce is XChaCha-specific) |

`scripts/generate_known_answer_vectors.py` regenerates every constant from
Python's `cryptography` (OpenSSL) and stdlib, and spot-checks the three RFC
vectors first so the generator itself is anchored. If a pinned Rust constant
and the generator ever disagree, one of the two is now non-conformant -- find
out which before changing either.

Concretely, the identity known-answer tests matter most: `NodeId` is
`SHA-256(Ed25519 public key)[0..8]`, so a silent change to that derivation would
give every device a different identity and orphan every stored key bundle and
channel key, with no round-trip test failing.

### Residual risk

The unit suite now proves spec conformance *and* agreement with an independent
implementation, on the host target. It still does **not** prove
interoperability with a peer running a *released* build — the constants above
establish that the derivation is unchanged from the specification, but not that
some earlier build of this app produced them. A two-node test (old build ↔ new
build) covering a Gate 1 direct message and a channel message remains worthwhile
and needs two devices or the integration harness.

## If a C library ever comes back

The tree is now pure Rust, so the staleness problem cannot recur through this
dependency. If a C cryptography library is ever reintroduced, re-check these,
because they are the things that were missed the first time:

- **The vendored C version, not the Rust wrapper's version.** What mattered was
  that `libsodium` was pinned to a mid-2021 commit, regardless of how
  recently the wrapper crate was released. A wrapper's release date says
  nothing about the C library inside it.
- **Whether the scanning control can see it.** `cargo audit` only reads
  `RUSTSEC-*` advisories from the Cargo graph. A C advisory produces nothing,
  so a green audit job is not evidence.
- **Whether the build needs autotools.** A vendored `./configure && make` in an
  NDK cross-compile is the most likely thing to break first, and it breaks
  loudly.

## Effort, retrospectively

Originally assessed as Low priority / Medium effort. That was wrong on priority
— the exposure was silent, in the layer doing the cryptography, and invisible
to the standard scanning control. The migration itself was mechanical, and
cheaper than expected, precisely because `vodozemac` had already pulled in
every primitive needed.
