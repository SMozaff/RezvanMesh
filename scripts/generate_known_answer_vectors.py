#!/usr/bin/env python3
"""
Generate golden test vectors for RezvanMesh's key derivations.

WHY THIS EXISTS
---------------
The Rust test suite is mostly round-trip tests: sign then verify, encrypt then
decrypt, derive then compare against a value derived the same way. Those prove
*self-consistency* and would happily pass if a derivation were changed
consistently -- which is exactly the failure mode that matters when a
cryptography library is swapped out (as `sodiumoxide` -> RustCrypto was).

To break that circularity these vectors are computed with an implementation
that shares no code with the Rust one:

  * Ed25519 and X25519  -> OpenSSL, via the `cryptography` package
  * HMAC-SHA256 / HKDF  -> Python's stdlib `hmac` / `hashlib`

The constants pinned in the Rust tests therefore have to agree three ways:
the relevant RFC's own test vectors, OpenSSL/stdlib, and the RustCrypto
implementation. A regression in any one of them fails the suite.

Reproduce with:

    python3 scripts/generate_known_answer_vectors.py

Then paste the output into the marked test modules. If a vector here ever
*disagrees* with the Rust tests, find out which of the two is wrong before
"fixing" either -- one of them is now non-conformant.
"""

import hashlib
import hmac
import sys

try:
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric import ed25519, x25519
except ImportError:  # pragma: no cover
    sys.exit(
        "This generator needs the `cryptography` package:\n"
        "    python3 -m pip install cryptography"
    )

# ---------------------------------------------------------------------------
# The exact domain-separation labels used by the Rust code. These are load
# bearing: changing one changes every derived key in the application, which is
# why they are asserted in both places rather than shared.
# ---------------------------------------------------------------------------
X25519_IDENTITY_INFO = b"rezvan-x25519-identity-v1"
BEACON_MAC_INFO = b"rezvan-beacon-mac-v1"
EPOCH_RATCHET_INFO = b"rezvan-beacon-epoch-ratchet-v1"
STATE_KDF_INFO = b"rezvan-engine-state-v1"


def hkdf_sha256(ikm: bytes, salt: bytes, info: bytes, length: int) -> bytes:
    """RFC 5869 HKDF-SHA256.

    The extract step hands `salt` to HMAC as the key, relying on HMAC's own
    RFC 2104 key handling -- exactly what the Rust implementation does via
    `Hmac::new_from_slice`. An empty salt therefore behaves as HashLen zeros.
    """
    prk = hmac.new(salt, ikm, hashlib.sha256).digest()
    okm = b""
    block = b""
    counter = 1
    while len(okm) < length:
        block = hmac.new(prk, block + info + bytes([counter]), hashlib.sha256).digest()
        okm += block
        counter += 1
    return okm[:length]


def clamp_scalar(scalar: bytes) -> bytes:
    """RFC 7748 X25519 scalar clamping."""
    s = bytearray(scalar)
    s[0] &= 248
    s[31] &= 127
    s[31] |= 64
    return bytes(s)


def ed25519_public_from_seed(seed: bytes) -> bytes:
    key = ed25519.Ed25519PrivateKey.from_private_bytes(seed)
    return key.public_key().public_bytes(
        encoding=serialization.Encoding.Raw,
        format=serialization.PublicFormat.Raw,
    )


def x25519_public_from_scalar(scalar: bytes) -> bytes:
    key = x25519.X25519PrivateKey.from_private_bytes(scalar)
    return key.public_key().public_bytes(
        encoding=serialization.Encoding.Raw,
        format=serialization.PublicFormat.Raw,
    )


def x25519_shared(private_scalar: bytes, peer_public: bytes) -> bytes:
    private = x25519.X25519PrivateKey.from_private_bytes(private_scalar)
    peer = x25519.X25519PublicKey.from_public_bytes(peer_public)
    return private.exchange(peer)


def truncated_hmac(key: bytes, message: bytes, length: int) -> bytes:
    return hmac.new(key, message, hashlib.sha256).digest()[:length]


def hexed(data: bytes) -> str:
    return data.hex()


def banner(title: str) -> None:
    print()
    print("=" * 74)
    print(title)
    print("=" * 74)


# ---------------------------------------------------------------------------
# 1. Identity derivation
# ---------------------------------------------------------------------------
def emit_identity_vectors() -> None:
    banner("IDENTITY DERIVATION  (rezvan-crypto/src/identity.rs)")
    print("Mirrors `generate_identity(seed)`:")
    print("  public_ed25519  = Ed25519 public key from the raw seed")
    print("  private_ed25519 = seed || public_ed25519   (64 bytes)")
    print("  private_x25519  = clamp(HKDF-SHA256(seed, salt=[], X25519_IDENTITY_INFO, 32))")
    print("  public_x25519   = X25519 base-point multiply of private_x25519")
    print()
    for label, seed_byte in (("SEED_A", 0x07), ("SEED_B", 0x2A)):
        seed = bytes([seed_byte]) * 32
        pub_ed = ed25519_public_from_seed(seed)
        priv_ed = seed + pub_ed
        x_material = hkdf_sha256(seed, b"", X25519_IDENTITY_INFO, 32)
        xs = clamp_scalar(x_material)
        pub_x = x25519_public_from_scalar(xs)
        print(f"{label} seed          = {hexed(seed)}")
        print(f"{label} public_ed25519 = {hexed(pub_ed)}")
        print(f"{label} private_x25519 = {hexed(xs)}")
        print(f"{label} public_x25519  = {hexed(pub_x)}")
        print(f"{label} private_ed25519 = {hexed(priv_ed)}")
        print()
        assert len(priv_ed) == 64
        assert len(xs) == 32


# ---------------------------------------------------------------------------
# 2. Beacon MAC
# ---------------------------------------------------------------------------
def emit_beacon_mac_vectors() -> None:
    banner("BEACON MAC  (rezvan-crypto/src/beacon_mac.rs)")
    print("Mirrors `compute_tag(our_private_x25519, their_public_x25519, message)`:")
    print("  shared = X25519(our_private, their_public)")
    print("  key    = HKDF-SHA256(shared, salt=[], BEACON_MAC_INFO, 32)")
    print("  tag    = HMAC-SHA256(key, message)[0..7]")
    print()
    for label, a_byte, b_byte in (("PAIR_1", 0x11, 0x22), ("PAIR_2", 0x33, 0x44)):
        a = clamp_scalar(hkdf_sha256(bytes([a_byte]) * 32, b"", X25519_IDENTITY_INFO, 32))
        b = clamp_scalar(hkdf_sha256(bytes([b_byte]) * 32, b"", X25519_IDENTITY_INFO, 32))
        a_pub = x25519_public_from_scalar(a)
        b_pub = x25519_public_from_scalar(b)
        shared = x25519_shared(a, b_pub)
        # ECDH is symmetric: both directions must land on the same secret.
        assert shared == x25519_shared(b, a_pub), "ECDH is not symmetric"
        key = hkdf_sha256(shared, b"", BEACON_MAC_INFO, 32)
        message = b"beacon-payload"
        tag = truncated_hmac(key, message, 7)
        print(f"{label} a_private_x25519 = {hexed(a)}")
        print(f"{label} a_public_x25519  = {hexed(a_pub)}")
        print(f"{label} b_private_x25519 = {hexed(b)}")
        print(f"{label} b_public_x25519  = {hexed(b_pub)}")
        print(f'{label} message           = "{message.decode()}"')
        print(f"{label} tag               = {hexed(tag)}")
        print()


# ---------------------------------------------------------------------------
# 3. Epoch beacon key
# ---------------------------------------------------------------------------
def emit_epoch_key_vectors() -> None:
    banner("EPOCH BEACON TAG  (rezvan-crypto/src/epoch_key.rs)")
    print("Mirrors `compute_tag(epoch_key, message)`:")
    print("  tag = HMAC-SHA256(epoch_key, message)[0..7]")
    print()
    for label, key_byte in (("EPOCH_1", 0x5A), ("EPOCH_2", 0xC3)):
        epoch_key = bytes([key_byte]) * 32
        message = b"beacon-payload"
        tag = truncated_hmac(epoch_key, message, 7)
        print(f"{label} epoch_key = {hexed(epoch_key)}")
        print(f'{label} message   = "{message.decode()}"')
        print(f"{label} tag       = {hexed(tag)}")
        print()

    # The ratchet is a plain HKDF over a fixed label; pin one step so a change
    # to the label or the derivation cannot pass unnoticed.
    banner("EPOCH RATCHET  (rezvan-crypto/src/epoch_key.rs)")
    print("ratchet_forward(key) = HKDF-SHA256(key, salt=[], EPOCH_RATCHET_INFO, 32)")
    print()
    for label, key_byte in (("EPOCH_1", 0x5A),):
        epoch_key = bytes([key_byte]) * 32
        nxt = hkdf_sha256(epoch_key, b"", EPOCH_RATCHET_INFO, 32)
        print(f"{label} key      = {hexed(epoch_key)}")
        print(f"{label} next     = {hexed(nxt)}")
        print()
        # Two steps must not equal one.
        two = hkdf_sha256(nxt, b"", EPOCH_RATCHET_INFO, 32)
        assert nxt != two
        print(f"{label} next2    = {hexed(two)}")
        print()


# ---------------------------------------------------------------------------
# 4. On-disk state key
# ---------------------------------------------------------------------------
def emit_state_key_vectors() -> None:
    banner("ON-DISK STATE KEY  (rezvan-crypto/src/secure_store.rs)")
    print("derive_state_key(seed) = HKDF-SHA256(seed, salt=[], STATE_KDF_INFO, 32)")
    print()
    for label, seed_byte in (("SEED_A", 0x07), ("SEED_B", 0x2A)):
        seed = bytes([seed_byte]) * 32
        print(f"{label} seed        = {hexed(seed)}")
        print(f"{label} state_key   = {hexed(hkdf_sha256(seed, b'', STATE_KDF_INFO, 32))}")
        print()


# ---------------------------------------------------------------------------
# 5. HKDF salt boundary
# ---------------------------------------------------------------------------
def emit_salt_boundary() -> None:
    banner("HKDF SALT BOUNDARY  (rezvan-crypto/src/hkdf.rs)")
    print("The Rust test `salt_hash_down_boundary_is_the_hmac_block_size` asserts")
    print("that a salt of length L is used as-is for L <= 64 and hashed for L > 64.")
    print("This section independently confirms that, and specifically confirms the")
    print("assertions cannot pass by accident (i.e. the two sides are unequal for")
    print("short salts, which is what `assert_ne!` requires).")
    print()
    ikm, info = b"ikm", b"info"
    for length in (1, 32, 33, 64):
        salt = bytes([0xAA]) * length
        direct = hkdf_sha256(ikm, salt, info, 32)
        pre_hashed = hkdf_sha256(ikm, hashlib.sha256(salt).digest(), info, 32)
        verdict = "as-is" if direct != pre_hashed else "COLLISION (test would fail)"
        print(f"  len={length:<3} not-hashed? {direct != pre_hashed!s:<5} -> {verdict}")
        assert direct != pre_hashed, f"len={length} collided; test assumption is wrong"
    print()
    for length in (65, 128):
        salt = bytes([0xAA]) * length
        direct = hkdf_sha256(ikm, salt, info, 32)
        pre_hashed = hkdf_sha256(ikm, hashlib.sha256(salt).digest(), info, 32)
        print(f"  len={length:<3} hashed?     {direct == pre_hashed!s:<5} -> {'hashed' if direct == pre_hashed else 'MISMATCH'}")
        assert direct == pre_hashed
    print()


# ---------------------------------------------------------------------------
# 6. RFC conformance spot-checks
# ---------------------------------------------------------------------------
def emit_rfc_spot_checks() -> None:
    banner("RFC SPOT-CHECKS  (independent confirmation of the pinned values)")
    # RFC 8032 section 7.1 TEST 1
    seed = bytes.fromhex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
    expected = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"
    got = hexed(ed25519_public_from_seed(seed))
    print(f"RFC 8032 7.1 TEST 1 pubkey matches: {got == expected}")
    assert got == expected

    # RFC 5869 Test Case 1
    ikm = bytes.fromhex("0b" * 22)
    salt = bytes.fromhex("000102030405060708090a0b0c")
    info = bytes.fromhex("f0f1f2f3f4f5f6f7f8f9")
    expected_okm = "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"
    got_okm = hexed(hkdf_sha256(ikm, salt, info, 42))
    print(f"RFC 5869 Test Case 1 OKM matches:   {got_okm == expected_okm}")
    assert got_okm == expected_okm

    # RFC 7748 section 5.2 X25519 test vector
    scalar = bytes.fromhex("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4")
    u = bytes.fromhex("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c")
    expected_shared = "c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552"
    got_shared = hexed(x25519_shared(scalar, u))
    print(f"RFC 7748 5.2 X25519 matches:        {got_shared == expected_shared}")
    assert got_shared == expected_shared
    print()


def main() -> None:
    emit_rfc_spot_checks()
    emit_identity_vectors()
    emit_beacon_mac_vectors()
    emit_epoch_key_vectors()
    emit_state_key_vectors()
    emit_salt_boundary()
    print("All self-consistency assertions in this generator passed.")


if __name__ == "__main__":
    main()
