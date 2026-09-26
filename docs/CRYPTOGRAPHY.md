# RezvanMesh Cryptography — Current State

**Status:** Current implementation  
**Last reviewed:** 2026-09-26

## Overview

RezvanMesh uses maintained Rust cryptographic crates and vodozemac for Olm/ratchet functionality. The current implementation does not depend on the previously used `sodiumoxide`/vendored libsodium stack.

The cryptographic design separates:

- long-term mesh identity
- authenticated packet signatures
- X25519 key agreement
- one-to-one session/ratchet state
- channel sender keys
- AEAD message protection
- beacon/network authentication
- encrypted local persistence

## Primitive map

| Function | Current implementation |
|---|---|
| Ed25519 signatures | `ed25519-dalek` |
| X25519 | `x25519-dalek` |
| SHA-256 | `sha2` |
| HMAC-SHA256 | `hmac` + `sha2` |
| HKDF-SHA256 | `hkdf` |
| XChaCha20-Poly1305 | `chacha20poly1305` |
| 1:1 Olm/ratchet | `vodozemac` |
| Secure randomness | `getrandom` / platform-secure sources |
| Secret zeroization | `zeroize` |

## Identity

A fresh installation generates a random 32-byte identity seed.

The Ed25519 identity public key is derived from the seed. A domain-separated HKDF derivation produces the X25519 identity private key rather than reusing the Ed25519 secret scalar.

The stable mesh Node ID is derived from the Ed25519 public key:

```
NodeId = SHA-256(Ed25519PublicKey)[0..8]
```

Identity material is stored locally using Android Keystore-backed encrypted storage.

There is currently **no identity backup/recovery mechanism**.

## Direct messages

One-to-one sessions use vodozemac-backed Olm/ratchet functionality.

The application does not implement a custom Double Ratchet primitive. Session state is maintained by the Rust session layer and persisted through the native state mechanism.

Direct-message acknowledgement is distinct from transport-level write completion. A local GATT write success does not by itself mean the recipient received, decrypted, displayed, or acted on a message.

## Channel messages

Channels use sender-key style symmetric encryption with authenticated sender identity.

A channel message is protected using an XChaCha20-Poly1305 key and is additionally associated with the sender's Ed25519 identity so that channel membership alone is not treated as proof of sender identity.

Channel messages use the mesh packet/relay path.

## Packet authentication

Mesh control/data frames can carry authenticated signatures/MAC material appropriate to their protocol layer.

Replay and duplicate handling is performed by protocol state rather than trusting timestamps alone.

## Local persistence

Sensitive native state is encrypted before being persisted. Android application data uses Room with SQLCipher for database storage, with the database key protected by Android Keystore-backed material.

Persistence must be tested across:

- process death
- service restart
- device reboot
- database migration
- interrupted writes

## Independent verification

The cryptographic test suite includes known-answer vectors and standards-based tests rather than relying exclusively on round-trip self-consistency.

Important properties covered include:

- Ed25519 RFC vectors
- X25519 agreement
- HKDF/HMAC behavior
- identity derivation
- beacon authentication
- encrypted-state key derivation
- XChaCha20-Poly1305 behavior
- secret zeroization behavior

## Security boundaries

The cryptographic layer does not solve:

- radio jamming
- compromised Android OS
- compromised device hardware
- user disclosure of identity keys
- loss of the only device
- traffic-analysis resistance
- guaranteed message delivery

Those are system-level properties and must be evaluated separately.

## Current dependency position

The old `sodiumoxide` migration report is obsolete. The dependency was removed from the current implementation.

If a native cryptography library is ever reintroduced, its actual bundled C/native version and security-advisory coverage must be reviewed independently of the Rust wrapper version.
