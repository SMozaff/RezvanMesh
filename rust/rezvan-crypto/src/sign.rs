use crate::identity::IdentityKeypair;
use ed25519_dalek::{Signature, Signer, SigningKey, VerifyingKey};

/// Produce a detached Ed25519 signature over `message`.
///
/// Ed25519 signing is deterministic (RFC 8032): the nonce is derived from the
/// private key and the message, never from a random source. The signature
/// bytes are therefore identical to what the previous libsodium-backed
/// implementation produced for the same key and message -- which matters here
/// because mesh packets are signed at the originator and verified at every hop,
/// so a change in the bytes would have been an interoperability break rather
/// than a silent internal detail.
pub fn sign(identity: &IdentityKeypair, message: &[u8]) -> [u8; 64] {
    // `private_ed25519` is laid out `seed(32) || public_key(32)`, matching
    // libsodium's `crypto_sign_ed25519_sk`. dalek's `SigningKey::from_bytes`
    // wants just the 32-byte seed, which is the leading half.
    let mut seed = [0u8; 32];
    seed.copy_from_slice(&identity.private_ed25519[..32]);
    let signing_key = SigningKey::from_bytes(&seed);

    let signature = signing_key.sign(message);
    signature.to_bytes()
}

/// Verify a detached Ed25519 signature.
///
/// Uses `verify_strict`, which performs **two** checks: scalar malleability (the
/// `S` component is fully reduced) and point malleability (no small torsion
/// component in `R`).
///
/// This is deliberately *stricter* than the previous implementation, and the
/// difference is worth being precise about rather than calling it a no-op.
/// The old path was a thin wrapper over libsodium's
/// `crypto_sign_ed25519_verify_detached`, which rejects a non-canonical `S` but
/// performs no explicit torsion check on `R`. `verify_strict` rejects a small
/// set of signatures that libsodium accepted.
///
/// That set is not reachable by an honest signer:
///  * Ed25519 signing is deterministic (RFC 8032), so the same key and message
///    always yield the same bytes -- a legitimate peer never produces a
///    malleable variant, and this is pinned by the RFC 8032 vectors below.
///  * So the only signatures newly rejected are adversarially constructed, and
///    rejecting them is the point.
///
/// Two consequences worth stating explicitly:
///  * Interoperability is unaffected. Peers on the previous build emit
///    non-malleable signatures, which `verify_strict` accepts.
///  * `verify_strict` is documented as *non-RFC-8032-compliant* in the sense
///    that it rejects some signatures the RFC permits. That is a deliberate
///    trade here: a mesh router re-verifies the same packet at every hop and
///    relays it onward, so accepting a malleable encoding would let one valid
///    packet be presented to different hops under different byte encodings.
pub fn verify(public_key: &[u8; 32], message: &[u8], signature: &[u8; 64]) -> bool {
    let Ok(verifying_key) = VerifyingKey::from_bytes(public_key) else {
        // Not a well-formed curve point. A rejected key can never verify, so
        // this is the same outcome as a failed signature check.
        return false;
    };
    verifying_key
        .verify_strict(message, &Signature::from_bytes(signature))
        .is_ok()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::identity::IdentityKeypair;

    fn hex(s: &str) -> Vec<u8> {
        (0..s.len())
            .step_by(2)
            .map(|i| u8::from_str_radix(&s[i..i + 2], 16).expect("valid hex in test vector"))
            .collect()
    }

    fn keypair_from_seed_and_public(seed_hex: &str, public_hex: &str) -> IdentityKeypair {
        let seed = hex(seed_hex);
        let public = hex(public_hex);
        let mut private = [0u8; 64];
        private[..32].copy_from_slice(&seed);
        private[32..].copy_from_slice(&public);
        IdentityKeypair {
            public_ed25519: public.try_into().expect("32-byte public key"),
            private_ed25519: private,
            public_x25519: [0u8; 32],
            private_x25519: [0u8; 32],
        }
    }

    // RFC 8032 section 7.1, "Test Vectors" for Ed25519.
    //
    // These pin the exact signature bytes this implementation produces. That is
    // the property the whole migration rests on: mesh packets are signed at the
    // originator and verified at every hop and by peers running a different
    // build, so a change in the output bytes is an interoperability break, not
    // an internal detail. Asserting against vectors from the specification --
    // rather than against a value captured from this implementation -- means
    // the test is checking conformance, not just self-consistency.
    const RFC8032_TEST1_SEED: &str =
        "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60";
    const RFC8032_TEST1_PUBLIC: &str =
        "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a";
    const RFC8032_TEST1_SIG: &str = "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b";

    const RFC8032_TEST2_SEED: &str =
        "4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb";
    const RFC8032_TEST2_PUBLIC: &str =
        "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c";
    const RFC8032_TEST2_SIG: &str = "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00";

    #[test]
    fn rfc8032_test_vector_1_empty_message() {
        let keypair = keypair_from_seed_and_public(RFC8032_TEST1_SEED, RFC8032_TEST1_PUBLIC);
        let signature = sign(&keypair, &[]);
        assert_eq!(signature.to_vec(), hex(RFC8032_TEST1_SIG), "empty message");
        assert!(verify(&keypair.public_ed25519, &[], &signature));
    }

    #[test]
    fn rfc8032_test_vector_2_single_byte_message() {
        let keypair = keypair_from_seed_and_public(RFC8032_TEST2_SEED, RFC8032_TEST2_PUBLIC);
        let signature = sign(&keypair, &[0x72]);
        assert_eq!(signature.to_vec(), hex(RFC8032_TEST2_SIG), "one-byte message");
        assert!(verify(&keypair.public_ed25519, &[0x72], &signature));
    }

    #[test]
    fn signing_is_deterministic() {
        // RFC 8032 derives the nonce from the key and message, so the same
        // inputs must always give the same bytes. If this ever became
        // randomised, two peers signing identical payloads would produce
        // different bytes and the wire format would stop being reproducible.
        let keypair = keypair_from_seed_and_public(RFC8032_TEST2_SEED, RFC8032_TEST2_PUBLIC);
        let a = sign(&keypair, b"mesh");
        let b = sign(&keypair, b"mesh");
        assert_eq!(a, b);
    }

    #[test]
    fn round_trip_varies_with_the_message() {
        let keypair = keypair_from_seed_and_public(RFC8032_TEST1_SEED, RFC8032_TEST1_PUBLIC);
        let a = sign(&keypair, b"one");
        let b = sign(&keypair, b"two");
        assert_ne!(a, b);
        assert!(verify(&keypair.public_ed25519, b"one", &a));
        assert!(verify(&keypair.public_ed25519, b"two", &b));
    }

    #[test]
    fn a_signature_does_not_verify_for_a_different_message() {
        let keypair = keypair_from_seed_and_public(RFC8032_TEST1_SEED, RFC8032_TEST1_PUBLIC);
        let signature = sign(&keypair, b"original");
        assert!(!verify(&keypair.public_ed25519, b"tampered", &signature));
    }

    #[test]
    fn a_signature_does_not_verify_under_a_different_key() {
        let a = keypair_from_seed_and_public(RFC8032_TEST1_SEED, RFC8032_TEST1_PUBLIC);
        let b = keypair_from_seed_and_public(RFC8032_TEST2_SEED, RFC8032_TEST2_PUBLIC);
        let signature = sign(&a, b"message");
        assert!(verify(&a.public_ed25519, b"message", &signature));
        assert!(!verify(&b.public_ed25519, b"message", &signature));
    }

    #[test]
    fn a_malformed_public_key_is_rejected_rather_than_panicking() {
        let keypair = keypair_from_seed_and_public(RFC8032_TEST1_SEED, RFC8032_TEST1_PUBLIC);
        let signature = sign(&keypair, b"message");
        // Not a valid curve point; must return false, not unwind.
        assert!(!verify(&[0xFF; 32], b"message", &signature));
        assert!(!verify(&[0u8; 32], b"message", &signature));
    }

    #[test]
    fn a_malformed_signature_is_rejected_rather_than_panicking() {
        let keypair = keypair_from_seed_and_public(RFC8032_TEST1_SEED, RFC8032_TEST1_PUBLIC);
        assert!(!verify(&keypair.public_ed25519, b"message", &[0u8; 64]));
        assert!(!verify(&keypair.public_ed25519, b"message", &[0xFF; 64]));
    }

    #[test]
    fn a_flipped_signature_bit_is_rejected() {
        let keypair = keypair_from_seed_and_public(RFC8032_TEST1_SEED, RFC8032_TEST1_PUBLIC);
        let mut signature = sign(&keypair, b"message");
        signature[0] ^= 0x01;
        assert!(!verify(&keypair.public_ed25519, b"message", &signature));
    }
}
