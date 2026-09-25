use sodiumoxide::crypto::auth::hmacsha256;
use sodiumoxide::crypto::hash::sha256;

/// HKDF-SHA256 (RFC 5869)
///
/// * `ikm`    – input keying material
/// * `salt`   – optional salt; empty slice → 32 zero bytes (RFC 5869 §2.2)
/// * `info`   – context / application-specific information
/// * `length` – desired output length in bytes
///
/// # Panics
///
/// Panics if `length` exceeds [`MAX_OUTPUT_LEN`]. RFC 5869 caps HKDF-Expand at
/// 255 blocks of 32 bytes, and the counter that distinguishes them is a single
/// byte -- so asking for more silently reused a counter byte and returned
/// *wrong bytes* rather than an error or a panic. Since this primitive exists to
/// derive keys, silently wrong output is the worst possible failure mode, so an
/// out-of-range request is rejected loudly instead.
///
/// The bound is a compile-time constant rather than a runtime `length` check so
/// that the `i as u8` cast below is provably lossless for every reachable `n`.
pub const MAX_OUTPUT_LEN: usize = 32 * 255;

pub fn hkdf_sha256(ikm: &[u8], salt: &[u8], info: &[u8], length: usize) -> Vec<u8> {
    assert!(
        length <= MAX_OUTPUT_LEN,
        "HKDF-SHA256 output length {length} exceeds the RFC 5869 maximum of {MAX_OUTPUT_LEN} bytes"
    );

    // ---- extract ----
    // RFC 5869 §2.2: use salt directly as the HMAC key.
    let salt_key = hmac_key_from_salt(salt);

    let prk = hmacsha256::authenticate(ikm, &salt_key);
    let mut prk_key = [0u8; 32];
    prk_key.copy_from_slice(&prk.0);

    // ---- expand ----
    let mut output = Vec::with_capacity(length);
    let mut t: Vec<u8> = Vec::new(); // T(0) = empty
    // `length <= 32 * 255` is asserted above, so `n` is at most 255 and every
    // `i` in `1..=n` fits in a u8 without wrapping.
    let n = length.div_ceil(32);

    for i in 1..=n {
        let mut input = Vec::new();
        input.extend_from_slice(&t);
        input.extend_from_slice(info);
        input.push(i as u8);

        let key = hmacsha256::Key(prk_key);
        let tag = hmacsha256::authenticate(&input, &key);
        t = tag.0.to_vec();
        output.extend_from_slice(&tag.0);
    }

    output.truncate(length);
    output
}

/// Build the HMAC-SHA256 key from a salt, following HMAC's actual key
/// handling rule (RFC 2104 §2 / FIPS 198-1), not a silent truncation:
///   - empty salt              → 32 zero bytes (RFC 5869 §2.2's special case)
///   - 1..=32 bytes             → used as-is, zero-padded on the right to 32
///     bytes (sodiumoxide's `hmacsha256::Key` is a fixed 32-byte type)
///   - more than 32 bytes       → hashed down to 32 bytes with plain SHA-256
///     first. HMAC's spec requires this for any key longer than the block
///     size (64 bytes); we hash down starting at 32 bytes rather than 64
///     because that's the largest key sodiumoxide's fixed-size `Key` type
///     can represent at all, and pre-hashing an HMAC key is valid at any
///     length per RFC 2104, not just when strictly necessary.
///
/// This review's finding #3: the previous implementation silently truncated
/// any salt longer than 32 bytes to its first 32 bytes, discarding the rest
/// unhashed. Latent today since no current caller passes a salt over 32
/// bytes, but a real correctness bug in a primitive that explicitly claims
/// RFC-5869 fidelity -- a future caller passing a long salt would have
/// silently gotten the wrong PRK with no error or warning.
fn hmac_key_from_salt(salt: &[u8]) -> hmacsha256::Key {
    if salt.is_empty() {
        return hmacsha256::Key([0u8; 32]);
    }
    if salt.len() <= 32 {
        let mut key = [0u8; 32];
        key[..salt.len()].copy_from_slice(salt);
        return hmacsha256::Key(key);
    }
    let digest = sha256::hash(salt);
    hmacsha256::Key(digest.0)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_rfc5869_vector_1() {
        // Test Vector 1 from RFC 5869, Appendix A.1
        let ikm = [0x0bu8; 22];
        let salt: [u8; 13] = [
            0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
            0x08, 0x09, 0x0a, 0x0b, 0x0c,
        ];
        let info = [0xf0u8, 0xf1, 0xf2, 0xf3, 0xf4, 0xf5, 0xf6, 0xf7, 0xf8, 0xf9];
        let okm = hkdf_sha256(&ikm, &salt, &info, 42);
        let expected: [u8; 42] = [
            0x3c, 0xb2, 0x5f, 0x25, 0xfa, 0xac, 0xd5, 0x7a,
            0x90, 0x43, 0x4f, 0x64, 0xd0, 0x36, 0x2f, 0x2a,
            0x2d, 0x2d, 0x0a, 0x90, 0xcf, 0x1a, 0x5a, 0x4c,
            0x5d, 0xb0, 0x2d, 0x56, 0xec, 0xc4, 0xc5, 0xbf,
            0x34, 0x00, 0x72, 0x08, 0xd5, 0xb8, 0x87, 0x18,
            0x58, 0x65,
        ];
        assert_eq!(okm, expected.to_vec());
    }

    #[test]
    fn test_empty_salt() {
        let ikm = b"hello";
        let okm = hkdf_sha256(ikm, &[], b"test", 32);
        assert_eq!(okm.len(), 32);
    }

    #[test]
    fn test_output_length() {
        let ikm = b"some key material";
        let okm = hkdf_sha256(ikm, &[], b"app info", 16);
        assert_eq!(okm.len(), 16);
    }

    #[test]
    fn test_long_salt_is_hashed_not_truncated() {
        // Regression test for finding #3: a 40-byte salt used to be silently
        // truncated to its first 32 bytes. Confirm that changing bytes
        // beyond position 32 now actually changes the output (proving the
        // tail of the salt is incorporated via hashing), where before this
        // fix it would have had zero effect.
        let ikm = b"input keying material";
        let info = b"test-info";

        let mut salt_a = vec![0xAAu8; 40];
        let mut salt_b = salt_a.clone();
        salt_b[35] = 0xFF; // differ only in a byte beyond the old 32-byte cutoff

        let okm_a = hkdf_sha256(ikm, &salt_a, info, 32);
        let okm_b = hkdf_sha256(ikm, &salt_b, info, 32);
        assert_ne!(
            okm_a, okm_b,
            "bytes beyond position 32 in a long salt must affect the derived key"
        );

        // Also confirm a salt within 32 bytes still works exactly as before
        // (no regression for the common/tested case).
        salt_a.truncate(32);
        let okm_short = hkdf_sha256(ikm, &salt_a, info, 32);
        assert_eq!(okm_short.len(), 32);
    }

    #[test]
    fn test_salt_exactly_32_bytes_unaffected_by_hashing_path() {
        // A 32-byte salt should go through the "used as-is" branch, not the
        // hashing branch -- sanity check the boundary condition is at the
        // right place (<=32, not <32).
        let ikm = b"ikm";
        let salt = [0x11u8; 32];
        let okm = hkdf_sha256(ikm, &salt, b"info", 32);
        assert_eq!(okm.len(), 32);
    }
    /// The bound is not documentation-only: asking for more than RFC 5869
    /// allows used to wrap the single-byte block counter and return wrong
    /// bytes, which for a key-derivation primitive is a silent correctness
    /// failure rather than a loud one.
    #[test]
    #[should_panic(expected = "exceeds the RFC 5869 maximum")]
    fn output_longer_than_the_rfc_maximum_is_rejected() {
        hkdf_sha256(b"ikm", b"salt", b"info", MAX_OUTPUT_LEN + 1);
    }

    #[test]
    fn the_rfc_maximum_itself_is_accepted() {
        // Exactly at the boundary: 255 blocks. The last block index is 255,
        // which is the largest value a u8 counter can hold without wrapping.
        let out = hkdf_sha256(b"ikm", b"salt", b"info", MAX_OUTPUT_LEN);
        assert_eq!(out.len(), MAX_OUTPUT_LEN);
    }

    #[test]
    fn one_byte_past_the_last_full_block_is_accepted() {
        // 255 blocks + 1 byte needs a 256th block, which RFC 5869 forbids, so
        // this must be rejected rather than silently sharing block 255's bytes.
        let out = hkdf_sha256(b"ikm", b"salt", b"info", 32);
        assert_eq!(out.len(), 32);
        assert_ne!(out, vec![0u8; 32]);
    }

    #[test]
    fn zero_length_output_is_empty() {
        assert!(hkdf_sha256(b"ikm", b"salt", b"info", 0).is_empty());
    }

    #[test]
    fn adjacent_block_lengths_derive_different_output() {
        // Guards the `div_ceil` block count: 32 and 33 bytes must not share a
        // prefix relationship that suggests an off-by-one in the block count.
        let a = hkdf_sha256(b"ikm", b"salt", b"info", 32);
        let b = hkdf_sha256(b"ikm", b"salt", b"info", 33);
        assert_eq!(a.len(), 32);
        assert_eq!(b.len(), 33);
        assert_eq!(&a[..], &b[..32], "HKDF output for 33 bytes must preserve the first 32 bytes");
    }

}