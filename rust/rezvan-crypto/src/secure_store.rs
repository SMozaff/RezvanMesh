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

use sodiumoxide::crypto::aead::xchacha20poly1305_ietf::{
    gen_nonce, open as aead_open, seal as aead_seal, Key as AeadKey, Nonce as AeadNonce,
    NONCEBYTES as AEAD_NONCEBYTES,
};

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
    let nonce = gen_nonce();
    let ciphertext = aead_seal(plaintext, None, &nonce, &AeadKey(*key));
    let mut out = Vec::with_capacity(AEAD_NONCEBYTES + ciphertext.len());
    out.extend_from_slice(&nonce.0);
    out.extend_from_slice(&ciphertext);
    out
}

/// Decrypt a buffer produced by [`seal_state`].
///
/// Returns `None` for a wrong key, a truncated buffer, or any tampering --
/// the AEAD tag check is what distinguishes "corrupt or hostile file" from
/// "valid state we wrote earlier".
pub fn open_state(key: &[u8; 32], sealed: &[u8]) -> Option<Vec<u8>> {
    if sealed.len() < AEAD_NONCEBYTES {
        return None;
    }
    let (nonce_bytes, ciphertext) = sealed.split_at(AEAD_NONCEBYTES);
    let mut nonce = [0u8; AEAD_NONCEBYTES];
    nonce.copy_from_slice(nonce_bytes);
    aead_open(ciphertext, None, &AeadNonce(nonce), &AeadKey(*key)).ok()
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

        assert!(open_state(&key, &sealed[..AEAD_NONCEBYTES]).is_none());
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
}
