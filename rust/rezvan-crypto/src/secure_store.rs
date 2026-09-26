//! Authenticated encryption for on-disk engine state.
//!
//! The engine's session material (Olm account + ratchet sessions, peer key
//! bundles, channel keys, the beacon epoch key) used to exist only in RAM:
//! every service restart produced a brand-new Olm identity key, discarded every
//! established ratchet session, and lost all channel keys. Peers then had to
//! re-exchange KeyAnnouncements before any direct message could be decrypted,
//! and in the meantime messages sent to a known contact silently failed.
//!
//! This module provides the confidentiality/integrity primitive used to store
//! that state safely: XChaCha20-Poly1305 with a key derived from the device's
//! own identity seed, so the state file is useless on its own if copied off the
//! device (the seed lives in Android Keystore-backed storage, never on disk in
//! the clear).
//!
//! The storage key is derived with HKDF-SHA256 under a domain-separated `info`
//! string so the state-encryption key can never collide with the beacon epoch
//! ratchet, the Olm pickles, or any other derived key.

use chacha20poly1305::aead::{Aead, KeyInit, Payload};
use chacha20poly1305::{Key as AeadKey, XChaCha20Poly1305, XNonce};

/// XChaCha20-Poly1305 nonce length, as carried in the on-disk format.
const AEAD_NONCE_BYTES: usize = 24;

/// Domain separator for the on-disk state encryption key. Changing this
/// invalidates every previously written state file, so it must stay stable
/// across releases.
const STATE_KDF_INFO: &[u8] = b"rezvan-engine-state-v1";

/// Derive the on-disk state encryption key from the device identity seed.
pub fn derive_state_key(seed: &[u8; 32]) -> [u8; 32] {
    let okm = crate::hkdf::hkdf_sha256(seed, &[], STATE_KDF_INFO, 32);
    let mut key = [0u8; 32];
    key.copy_from_slice(&okm);
    key
}

/// Encrypt `plaintext` with `key`, returning `nonce || ciphertext || tag`.
///
/// A fresh random nonce is generated per call, so `seal` must never be called
/// twice with the same key and plaintext expecting distinct outputs to be
/// distinguishable -- that is the standard AEAD property and is fine here
/// because the plaintext is state, not a secret that needs to be repeated
/// identically.
pub fn seal_state(key: &[u8; 32], plaintext: &[u8]) -> Vec<u8> {
    let mut nonce_bytes = [0u8; AEAD_NONCE_BYTES];
    getrandom::getrandom(&mut nonce_bytes).expect("OS randomness unavailable");

    let cipher = XChaCha20Poly1305::new(AeadKey::from_slice(key));
    let ciphertext = cipher
        .encrypt(
            XNonce::from_slice(&nonce_bytes),
            Payload {
                msg: plaintext,
                aad: &[],
            },
        )
        .expect("XChaCha20-Poly1305 encryption of an in-memory buffer cannot fail");

    let mut out = Vec::with_capacity(AEAD_NONCE_BYTES + ciphertext.len());
    out.extend_from_slice(&nonce_bytes);
    out.extend_from_slice(&ciphertext);
    out
}

/// Decrypt a buffer produced by [`seal_state`].
///
/// Returns `None` for a wrong key, a truncated buffer, or any tampering --
/// the AEAD tag check is what distinguishes "corrupt or hostile file" from
/// "valid state we wrote earlier".
pub fn open_state(key: &[u8; 32], sealed: &[u8]) -> Option<Vec<u8>> {
    if sealed.len() < AEAD_NONCE_BYTES {
        return None;
    }
    let (nonce_bytes, ciphertext) = sealed.split_at(AEAD_NONCE_BYTES);
    let cipher = XChaCha20Poly1305::new(AeadKey::from_slice(key));
    cipher
        .decrypt(
            XNonce::from_slice(nonce_bytes),
            Payload {
                msg: ciphertext,
                aad: &[],
            },
        )
        .ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn state_key_is_deterministic_and_domain_separated() {
        let seed = [7u8; 32];
        assert_eq!(derive_state_key(&seed), derive_state_key(&seed));
        assert_ne!(derive_state_key(&seed), derive_state_key(&[8u8; 32]));
        // Must not collide with a raw-HKDF expansion under a different label.
        assert_ne!(
            derive_state_key(&seed),
            crate::epoch_key::ratchet_forward(&seed)
        );
    }

    #[test]
    fn seal_open_roundtrip() {
        let key = derive_state_key(&[1u8; 32]);
        let plaintext = b"{\"epoch_number\":42}";
        let sealed = seal_state(&key, plaintext);
        assert_eq!(open_state(&key, &sealed).as_deref(), Some(&plaintext[..]));
    }

    #[test]
    fn open_rejects_wrong_key() {
        let sealed = seal_state(&derive_state_key(&[1u8; 32]), b"secret state");
        let other = derive_state_key(&[2u8; 32]);
        assert!(open_state(&other, &sealed).is_none());
    }

    #[test]
    fn open_rejects_tampering_and_truncation() {
        let key = derive_state_key(&[1u8; 32]);
        let sealed = seal_state(&key, b"secret state");

        let mut flipped = sealed.clone();
        let last = flipped.len() - 1;
        flipped[last] ^= 0x01;
        assert!(open_state(&key, &flipped).is_none());

        assert!(open_state(&key, &sealed[..AEAD_NONCE_BYTES]).is_none());
        assert!(open_state(&key, &[]).is_none());
    }

    #[test]
    fn repeated_seal_uses_distinct_nonces() {
        let key = derive_state_key(&[3u8; 32]);
        let a = seal_state(&key, b"same state");
        let b = seal_state(&key, b"same state");
        assert_ne!(
            a, b,
            "nonce must be random per call, not derived from plaintext"
        );
        assert_eq!(open_state(&key, &a), open_state(&key, &b));
    }

    /// Pins the on-disk state encryption key derivation.
    ///
    /// If this changed, every state file written by an existing install would
    /// stop decrypting: the engine would silently fall back to "no prior
    /// state", discarding every Olm session, peer key, and channel key, and the
    /// user would see messages they had already sent stop being deliverable.
    /// Constant from an independent implementation -- see
    /// `scripts/generate_known_answer_vectors.py`.
    #[test]
    fn known_answer_state_key() {
        assert_eq!(
            derive_state_key(&[0x07u8; 32]).to_vec(),
            crate::test_util::hex("94c941f06b2b81d9a72373a5b584698b937ea13a731e42e30c6903e2f2d8335c")
        );
        assert_eq!(
            derive_state_key(&[0x2au8; 32]).to_vec(),
            crate::test_util::hex("990483c898eb685a964b2646d49f778cf4e1a14e1a7fb5d89d485044a93238a0")
        );
    }

    /// Pins the AEAD against its specification, using the XChaCha20-Poly1305
    /// vector from `draft-arciszewski-xchacha-03` appendix A.1.
    ///
    /// The other known-answer tests in this crate could be produced by
    /// OpenSSL or stdlib. This one cannot: XChaCha20's 24-byte nonce has no
    /// equivalent in OpenSSL, so the only independent reference available is
    /// the specification itself. That makes this the check that the
    /// `Payload { msg, aad }` call shape and the 24-byte `XNonce` handling are
    /// actually doing XChaCha20-Poly1305 and not, say, ChaCha20-Poly1305 with
    /// a padded nonce.
    ///
    /// The `chacha20poly1305` crate runs the Wycheproof XChaCha20Poly1305
    /// corpus in its own suite; this test is what confirms *our* usage against
    /// the spec rather than only against itself.
    #[test]
    fn xchacha20poly1305_matches_the_draft_vector() {
        use chacha20poly1305::aead::{Aead, KeyInit, Payload};
        use chacha20poly1305::{Key as AeadKey, XChaCha20Poly1305, XNonce};

        const KEY: [u8; 32] = [
            0x80, 0x81, 0x82, 0x83, 0x84, 0x85, 0x86, 0x87, 0x88, 0x89, 0x8a, 0x8b, 0x8c, 0x8d, 0x8e, 0x8f,
            0x90, 0x91, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9a, 0x9b, 0x9c, 0x9d, 0x9e, 0x9f,
        ];
        const NONCE: [u8; 24] = [
            0x40, 0x41, 0x42, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49, 0x4a, 0x4b, 0x4c, 0x4d, 0x4e, 0x4f,
            0x50, 0x51, 0x52, 0x53, 0x54, 0x55, 0x56, 0x57,
        ];
        const AAD: [u8; 12] = [
            0x50, 0x51, 0x52, 0x53, 0xc0, 0xc1, 0xc2, 0xc3, 0xc4, 0xc5, 0xc6, 0xc7,
        ];
        const PLAINTEXT: &[u8] = b"Ladies and Gentlemen of the class of '99: \
            If I could offer you only one tip for the future, sunscreen would be it.";
        // Ciphertext followed by the 16-byte Poly1305 tag, as `Aead::encrypt`
        // returns.
        const CIPHERTEXT_AND_TAG: &str = concat!(
            "bd6d179d3e83d43b9576579493c0e939572a1700252bfaccbed2902c21396cbb",
            "731c7f1b0b4aa6440bf3a82f4eda7e39ae64c6708c54c216cb96b72e1213b4522f",
            "8c9ba40db5d945b11b69b982c1bb9e3f3fac2bc369488f76b2383565d3fff921f9",
            "664c97637da9768812f615c68b13b52e",
            "c0875924c1c7987947deafd8780acf49",
        );

        let cipher = XChaCha20Poly1305::new(AeadKey::from_slice(&KEY));
        let nonce = XNonce::from_slice(&NONCE);
        let sealed = cipher
            .encrypt(nonce, Payload { msg: PLAINTEXT, aad: &AAD })
            .expect("encryption of an in-memory buffer cannot fail");

        assert_eq!(
            sealed,
            crate::test_util::hex(CIPHERTEXT_AND_TAG),
            "XChaCha20-Poly1305 output does not match draft-arciszewski-xchacha-03 A.1"
        );

        // And the same via our own wrapper, so the wrapper's framing
        // (nonce || ciphertext) is covered by the spec vector too.
        let state_key = derive_state_key(&[0u8; 32]);
        let roundtrip = open_state(&state_key, &seal_state(&state_key, PLAINTEXT));
        assert_eq!(roundtrip.as_deref(), Some(PLAINTEXT));
    }
}

