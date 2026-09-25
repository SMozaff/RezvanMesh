# sodiumoxide → replacement migration (remediation #3)

**Status: not applied. The previously proposed approach was investigated and
found to be a no-op — see "Why the original plan is wrong". Replaced with a
corrected recommendation, a higher priority than originally assessed, and an
honest cost estimate.**

No code changes have been made. Nothing here is applied.

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

## Revised recommendation

**Migrate to `sodim` (pure Rust, actively maintained, API-compatible with
`sodiumoxide`).** It addresses both liabilities — no C library, no autotools,
no `unsafe` in this codebase — and keeps the change mechanical: the module
boundaries already isolate every use behind `rezvan-crypto`.

Steps, when a build environment is available:

1. Swap `sodiumoxide` → `sodim` in `rezvan-crypto/Cargo.toml`; the call sites
   in `identity.rs`, `hkdf.rs`, `beacon_mac.rs`, `sender_key.rs`, `sign.rs`,
   `secure_store.rs`, `epoch_key.rs`, and `lib.rs` are near-identical.
2. Confirm the **existing** test suite still passes unchanged. This is the real
   safety net and it is already good: RFC 5869 HKDF vectors, Ed25519 sign/verify
   round-trips, X25519 key agreement, XChaCha20-Poly1305 round-trips, the
   beacon-MAC tests, and the on-disk state round-trip. Pure-Rust implementations
   are byte-compatible with libsodium for these primitives, so a passing suite
   is meaningful evidence — but it must be *run*, which is why this is not
   applied blind.
3. Verify the Android NDK build no longer invokes autotools (`cargo ndk` with
   `SODIUM_USE_PKG_CONFIG` unset should show no `./configure`).
4. `cargo audit` to confirm the new tree is clean.

### Fallback

If `sodim` does not hold up, the alternative is RustCrypto (`ed25519-dalek`,
`x25519-dalek`, `chacha20poly1305`, `hkdf`, `hmac`, `sha2`). That is a real
rewrite rather than a binding swap, and it would need the test vectors
re-verified against the new implementations, so it is a larger project.

### Keeping `sodiumoxide` is defensible

`sodiumoxide 0.2.7` is functionally correct and its primitives are sound. The
cost of doing nothing is: no upstream libsodium security fixes for as long as
the stand still, and a build that continues to depend on autotools working
with the NDK. If the project accepts that, the honest option is to stay put and
re-evaluate on either of these triggers:

- a libsodium advisory is published (watch upstream; `cargo audit` will not),
- an NDK or autotools change breaks the vendored C build, or
- the threat model changes to include adversaries who can act on a known
  cryptographic implementation flaw.

## Priority / effort

Priority: **Medium.** No known active exploit, and the primitives in use are
correct — so this is not a release blocker. But it is a silent, compounding
exposure in the layer that actually performs the cryptography, and the
standard dependency-scanning control does not cover it.

Effort: **Medium** (mechanical, but requires a working build environment to
verify before merge).
