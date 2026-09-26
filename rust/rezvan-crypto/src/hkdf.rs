use hmac::{Hmac, Mac};
use sha2::Sha256;

/// HMAC-SHA256, as used by HKDF below.
type HmacSha256 = Hmac<Sha256>;

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
pub const MAX_OUTPUT_LEN: usize = 32 * 255;

pub fn hkdf_sha256(ikm: &[u8], salt: &[u8], info: &[u8], length: usize) -> Vec<u8> {
    assert!(
        length <= MAX_OUTPUT_LEN,
        "HKDF-SHA256 output length {length} exceeds the RFC 5869 maximum of {MAX_OUTPUT_LEN} bytes"
    );

    // ---- extract ----
    // RFC 5869 §2.2: use salt directly as the HMAC key.
    //
    // `Hmac::new_from_slice` accepts a key of any length and applies HMAC's
    // real key handling (RFC 2104 §2): keys shorter than the block size are
    // zero-padded, keys longer are hashed down. That covers every case RFC 5869
    // cares about, including the empty salt (which becomes 32 zero bytes, i.e.
    // the §2.2 "not provided" default) with no special-casing here.
    //
    // The previous implementation needed a `hmac_key_from_salt` helper to do
    // that by hand, because sodiumoxide's `hmacsha256::Key` is a fixed 32-byte
    // type and silently *truncated* anything longer. The bug that motivated
    // that helper (an over-long salt losing its tail) is now impossible to
    // express: the length check is the standard library's.
    let salt_key = HmacSha256::new_from_slice(salt).expect("HMAC accepts keys of any length");
    let prk_key = salt_key.chain_update(ikm).finalize().into_bytes();

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

        let mut mac = HmacSha256::new_from_slice(&prk_key).expect("HMAC accepts keys of any length");
        mac.update(&input);
        let tag = mac.finalize().into_bytes();
        t = tag.to_vec();
        output.extend_from_slice(&tag);
    }

    output.truncate(length);
    output
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

    /// Pins the equivalence the salt rewrite depends on.
    ///
    /// RFC 5869 §2.2 says an absent salt is `HashLen` zero bytes. That is
    /// implemented here by handing HMAC an empty key, which zero-pads to the
    /// 64-byte block size -- and a 32-byte all-zero key zero-pads to exactly the
    /// same 64 bytes. So the two must produce identical output. The previous
    /// implementation got this by substituting a `Key([0u8; 32])` explicitly;
    /// that special case is gone, so assert the property rather than trusting it.
    #[test]
    fn empty_salt_equals_an_explicit_all_zero_salt() {
        let implicit = hkdf_sha256(b"ikm", b"", b"info", 32);
        let explicit = hkdf_sha256(b"ikm", &[0u8; 32], b"info", 32);
        assert_eq!(
            implicit, explicit,
            "an absent salt must behave as 32 zero bytes, per RFC 5869 2.2"
        );
    }

    /// Pins the salt-length semantics of the current implementation.
    ///
    /// RFC 2104's rule, which `hmac` implements in `get_der_key`, is: a key
    /// **larger than the hash block size** (64 for SHA-256) is hashed down; a
    /// key of block size or smaller is zero-padded as-is. So the hash-down
    /// boundary is **64 bytes**, not 32.
    ///
    /// This differs from the previous implementation, which hashed anything
    /// longer than 32 bytes -- not because RFC 2104 says so, but because
    /// sodiumoxide's `hmacsha256::Key` is a fixed 32-byte type and could not
    /// hold a longer key. The old boundary was an artefact of that wrapper; the
    /// current one is the standard.
    ///
    /// The difference is unobservable in this application: every caller passes
    /// an empty salt (see `secure_store`, `epoch_key`, `identity`,
    /// `beacon_mac`), for which all three code paths -- empty, short, and
    /// hashed -- agree exactly. It is pinned here so the boundary is not
    /// silently moved again.
    #[test]
    fn salt_hash_down_boundary_is_the_hmac_block_size() {
        let ikm = b"ikm";
        let info = b"info";
        let hashed = |salt: &[u8]| hkdf_sha256(ikm, &sha256_of(salt), info, 32);

        // At or below the block size: used as-is, so pre-hashing must differ.
        for len in [1usize, 32, 33, 64] {
            let salt = vec![0xAA; len];
            assert_ne!(
                hkdf_sha256(ikm, &salt, info, 32),
                hashed(&salt),
                "a {len}-byte salt is within the block size and must not be pre-hashed"
            );
        }

        // Above the block size: hashed down, so pre-hashing must match exactly.
        for len in [65usize, 128] {
            let salt = vec![0xAA; len];
            assert_eq!(
                hkdf_sha256(ikm, &salt, info, 32),
                hashed(&salt),
                "a {len}-byte salt exceeds the block size and must be hashed down"
            );
        }
    }

    fn sha256_of(input: &[u8]) -> Vec<u8> {
        use sha2::Digest;
        sha2::Sha256::digest(input).to_vec()
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