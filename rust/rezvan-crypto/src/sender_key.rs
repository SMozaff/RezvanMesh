//! Group messaging ("sender keys" / channels).
//!
//! Security audit finding #10 / Fix 10: a single shared symmetric key can
//! give confidentiality against outsiders, but it structurally cannot give
//! per-sender authentication within the group -- anyone who can decrypt a
//! channel's messages can also encrypt something indistinguishable from a
//! genuine message "from" any other member. This is true of every
//! shared-symmetric-key group scheme, including Signal's production Sender
//! Keys protocol, which solves it by pairing the shared encryption key with
//! a *per-sender* signing key that travels alongside it.
//!
//! This module does the same: each sender signs their own plaintext with
//! their existing seed-derived Ed25519 mesh identity key (the same key
//! `MeshPacketHeader`-based packets are signed with, see engine.rs) before
//! encrypting, and a receiver verifies that signature against the claimed
//! sender's known public key before trusting the content. The shared
//! `[u8; 32]` key still provides confidentiality/tamper-detection against
//! outsiders exactly as before; this only adds the missing per-sender layer.
//!
//! Nothing calls this module in production yet (channels have no send/receive
//! wiring at all as of this fix -- see ChannelRepository, which is pure local
//! metadata). This is intentionally fixed now, before any transport code is
//! built on top of it, rather than shipping the unauthenticated primitive and
//! retrofitting later.

use sodiumoxide::crypto::aead::xchacha20poly1305_ietf;
use crate::identity::IdentityKeypair;
use crate::sign;

/// Generate a random 32‑byte shared sender key for group messaging.
/// Distributed out-of-band to all channel members (e.g. alongside the
/// existing 128-byte KeyAnnouncement bundle, or a channel-specific
/// analogue) -- distribution mechanism is out of scope for this module.
pub fn generate() -> [u8; 32] {
    let mut key = [0u8; 32];
    let random_bytes = sodiumoxide::randombytes::randombytes(32);
    key.copy_from_slice(&random_bytes);
    key
}

/// Encrypt `plaintext` under the shared channel key, AND sign it with the
/// sender's own Ed25519 mesh identity key so receivers can verify who
/// actually sent it (not just that *some* channel member did).
///
/// Wire format: `[nonce:24][ciphertext:N][sender_pubkey:32][signature:64]`.
/// The signature covers `nonce ++ ciphertext`, binding the signature to this
/// exact encrypted message (not just the plaintext) so a malicious member
/// can't strip a valid signature and reattach it to a different ciphertext.
///
/// `sender_pubkey` is included in the wire format so receivers who haven't
/// yet learned this sender's key out-of-band can at least see whose key
/// would need to verify -- but see `decrypt`'s docs: callers MUST pass in
/// the *independently known* public key for that member (e.g. from a prior
/// KeyAnnouncement), never trust the embedded key blindly, or this
/// degenerates into "anyone can claim to be anyone."
pub fn encrypt(key: &[u8; 32], plaintext: &[u8], sender_identity: &IdentityKeypair) -> Vec<u8> {
    let nonce = xchacha20poly1305_ietf::gen_nonce();
    let aead_key = xchacha20poly1305_ietf::Key(*key);
    let ciphertext = xchacha20poly1305_ietf::seal(plaintext, None, &nonce, &aead_key);

    let mut signed_bytes = Vec::with_capacity(24 + ciphertext.len());
    signed_bytes.extend_from_slice(&nonce.0);
    signed_bytes.extend_from_slice(&ciphertext);

    let signature = sign::sign(sender_identity, &signed_bytes);

    let mut result = Vec::with_capacity(signed_bytes.len() + 32 + 64);
    result.extend_from_slice(&signed_bytes);
    result.extend_from_slice(&sender_identity.public_ed25519);
    result.extend_from_slice(&signature);
    result
}

/// Decrypt and verify a message produced by `encrypt`.
///
/// `expected_sender_pubkey` MUST come from the caller's own independently
/// verified record of that member's identity (e.g. registered via
/// KeyAnnouncement/QR contact exchange) -- NOT read from the wire's embedded
/// pubkey field and trusted blindly. This function checks that the wire's
/// embedded pubkey matches what the caller expects, then verifies the
/// signature against it, so both checks are enforced: "does this match who
/// you think sent it" and "is the signature actually valid."
///
/// Returns `None` if the message is too short, the sender pubkey doesn't
/// match what the caller expected, the signature doesn't verify, or AEAD
/// decryption/authentication fails.
pub fn decrypt(
    key: &[u8; 32],
    expected_sender_pubkey: &[u8; 32],
    wire: &[u8],
) -> Option<Vec<u8>> {
    const NONCE_LEN: usize = 24;
    const PUBKEY_LEN: usize = 32;
    const SIG_LEN: usize = 64;
    const MIN_LEN: usize = NONCE_LEN + PUBKEY_LEN + SIG_LEN; // + at least 0 bytes ciphertext tag

    if wire.len() < MIN_LEN {
        return None;
    }

    let sig_start = wire.len() - SIG_LEN;
    let pubkey_start = sig_start - PUBKEY_LEN;

    let signed_bytes = &wire[..pubkey_start]; // nonce ++ ciphertext
    let embedded_pubkey = &wire[pubkey_start..sig_start];
    let sig_bytes = &wire[sig_start..];

    if embedded_pubkey != expected_sender_pubkey.as_slice() {
        return None; // claimed sender doesn't match who the caller expected
    }

    let mut sig = [0u8; SIG_LEN];
    sig.copy_from_slice(sig_bytes);

    if !sign::verify(expected_sender_pubkey, signed_bytes, &sig) {
        return None; // signature invalid -- reject before even attempting AEAD decrypt
    }

    if signed_bytes.len() < NONCE_LEN {
        return None;
    }
    let nonce_bytes: [u8; NONCE_LEN] = signed_bytes[0..NONCE_LEN].try_into().ok()?;
    let nonce = xchacha20poly1305_ietf::Nonce(nonce_bytes);
    let encrypted = &signed_bytes[NONCE_LEN..];
    let aead_key = xchacha20poly1305_ietf::Key(*key);

    xchacha20poly1305_ietf::open(encrypted, None, &nonce, &aead_key).ok()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::identity::generate_identity;

    #[test]
    fn test_roundtrip_with_correct_sender() {
        let key = generate();
        let alice = generate_identity(&[1u8; 32]);

        let wire = encrypt(&key, b"hello channel", &alice);
        let plaintext = decrypt(&key, &alice.public_ed25519, &wire);
        assert_eq!(plaintext, Some(b"hello channel".to_vec()));
    }

    #[test]
    fn test_rejects_wrong_expected_sender() {
        let key = generate();
        let alice = generate_identity(&[1u8; 32]);
        let mallory = generate_identity(&[2u8; 32]);

        let wire = encrypt(&key, b"hello channel", &alice);
        // Receiver expected Mallory but the message came from Alice.
        let plaintext = decrypt(&key, &mallory.public_ed25519, &wire);
        assert_eq!(plaintext, None);
    }

    #[test]
    fn test_forged_sender_field_rejected_even_with_valid_key_for_message() {
        // Mallory (a real channel member with the shared key) tries to forge
        // a message "from" Alice by re-signing with her own key but claiming
        // Alice's pubkey in the sender field. This must fail: the embedded
        // pubkey check catches a mismatched claim, and even if Mallory forges
        // the embedded pubkey field to say "Alice" while signing with her own
        // key, the signature verification against Alice's real public key
        // will fail since Mallory doesn't have Alice's private key.
        let key = generate();
        let alice = generate_identity(&[1u8; 32]);
        let mallory = generate_identity(&[2u8; 32]);

        let mut forged = encrypt(&key, b"forged message", &mallory);
        // Splice in Alice's pubkey where Mallory's real one is, to see if
        // that alone lets the forgery pass.
        let len = forged.len();
        let pubkey_start = len - 64 - 32;
        forged[pubkey_start..pubkey_start + 32].copy_from_slice(&alice.public_ed25519);

        let plaintext = decrypt(&key, &alice.public_ed25519, &forged);
        assert_eq!(plaintext, None, "forged sender field without the real private key must fail verification");
    }

    #[test]
    fn test_tampered_ciphertext_rejected() {
        let key = generate();
        let alice = generate_identity(&[1u8; 32]);

        let mut wire = encrypt(&key, b"original message", &alice);
        // Flip a bit in the ciphertext region (after the 24-byte nonce).
        wire[30] ^= 0x01;

        let plaintext = decrypt(&key, &alice.public_ed25519, &wire);
        assert_eq!(plaintext, None, "tampered ciphertext must fail signature verification");
    }

    #[test]
    fn test_wrong_shared_key_rejected() {
        let key = generate();
        let wrong_key = generate();
        let alice = generate_identity(&[1u8; 32]);

        let wire = encrypt(&key, b"secret", &alice);
        // Signature still verifies (it's over nonce++ciphertext, not the
        // key), but AEAD decryption with the wrong key must fail.
        let plaintext = decrypt(&wrong_key, &alice.public_ed25519, &wire);
        assert_eq!(plaintext, None);
    }

    #[test]
    fn test_truncated_wire_rejected() {
        let key = generate();
        for len in 0..50 {
            let bytes = vec![0xAAu8; len];
            assert_eq!(decrypt(&key, &[0u8; 32], &bytes), None);
        }
    }
}
