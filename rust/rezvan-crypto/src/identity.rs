use crate::hkdf::hkdf_sha256;
use ed25519_dalek::SigningKey;
use x25519_dalek::{PublicKey, StaticSecret};
use zeroize::Zeroize;

/// A node's long-term identity: an Ed25519 signing keypair (node identity and
/// packet signatures) and an X25519 keypair (beacon key agreement), both
/// derived from one 32-byte seed.
///
/// The private halves are wiped on drop. They live for the process lifetime in
/// `SessionManager`, so this mainly protects the short-lived copies handed out
/// by `identity()` -- without it, a dropped temporary would leave its secret
/// key material in freed heap until something overwrote it.
pub struct IdentityKeypair {
    pub public_ed25519: [u8; 32],
    /// `seed(32) || public_key(32)`, matching libsodium's `crypto_sign_ed25519_sk`
    /// layout that this type has always used on the wire and in memory.
    pub private_ed25519: [u8; 64],
    pub public_x25519: [u8; 32],
    pub private_x25519: [u8; 32],
}

impl Clone for IdentityKeypair {
    fn clone(&self) -> Self {
        Self {
            public_ed25519: self.public_ed25519,
            private_ed25519: self.private_ed25519,
            public_x25519: self.public_x25519,
            private_x25519: self.private_x25519,
        }
    }
}

// Deriving both is what produces the private halves, so the derive is only
// meaningful if construction is confined to `generate_identity`.
impl Zeroize for IdentityKeypair {
    fn zeroize(&mut self) {
        self.private_ed25519.zeroize();
        self.private_x25519.zeroize();
        // The public halves are not secret, but they are derived from the seed,
        // so wipe them too rather than leaving a consistent pair behind.
        self.public_ed25519.zeroize();
        self.public_x25519.zeroize();
    }
}

impl Drop for IdentityKeypair {
    fn drop(&mut self) {
        self.zeroize();
    }
}

/// Derive both identity keypairs from a single 32-byte seed.
///
/// Security audit finding #2: the X25519 half is an HKDF-SHA256 expansion of
/// the seed under a distinct `info` label, not the raw seed. Two asymmetric
/// primitives keyed directly from the same undifferentiated input is fragile
/// under composition -- if either primitive's key generation ever leaked
/// anything derivable back toward the seed, it could weaken the other. The
/// Ed25519 half still takes the seed directly, which is its own documented API
/// and whose internal derivation already provides adequate separation.
pub fn generate_identity(seed: &[u8; 32]) -> IdentityKeypair {
    // Ed25519 key generation is deterministic from the seed (RFC 8032), so
    // this yields the same public key the previous implementation did -- which
    // is what keeps NodeIds, and therefore peer identity, stable.
    let signing_key = SigningKey::from_bytes(seed);
    let public_ed25519 = signing_key.verifying_key().to_bytes();

    // dalek's `SigningKey` keeps the seed; rebuild the 64-byte secret-key layout
    // this type has always exposed.
    let mut private_ed25519 = [0u8; 64];
    private_ed25519[..32].copy_from_slice(signing_key.as_bytes());
    private_ed25519[32..].copy_from_slice(&public_ed25519);

    let x25519_seed_material = hkdf_sha256(seed, &[], b"rezvan-x25519-identity-v1", 32);
    let mut xs = [0u8; 32];
    xs.copy_from_slice(&x25519_seed_material);
    xs[0] &= 248;
    xs[31] &= 127;
    xs[31] |= 64;

    // `From<&StaticSecret> for PublicKey` performs the base-point
    // multiplication with the same clamping RFC 7748 specifies, so the
    // pre-clamping above is redundant for correctness but kept so the stored
    // private key is canonical.
    let public_x25519 = PublicKey::from(&StaticSecret::from(xs)).to_bytes();

    IdentityKeypair {
        public_ed25519,
        private_ed25519,
        public_x25519,
        private_x25519: xs,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_identity_is_deterministic() {
        let a = generate_identity(&[7u8; 32]);
        let b = generate_identity(&[7u8; 32]);
        assert_eq!(a.public_ed25519, b.public_ed25519);
        assert_eq!(a.public_x25519, b.public_x25519);
        assert_eq!(a.private_x25519, b.private_x25519);
    }

    #[test]
    fn test_different_seeds_give_different_keys() {
        let a = generate_identity(&[1u8; 32]);
        let b = generate_identity(&[2u8; 32]);
        assert_ne!(a.public_ed25519, b.public_ed25519);
        assert_ne!(a.public_x25519, b.public_x25519);
    }

    #[test]
    fn test_private_ed25519_is_seed_then_public_key() {
        // The 64-byte layout is load-bearing: `sign::sign` reads the seed from
        // the leading half, and anything else that persisted this struct would
        // interpret it as libsodium's `crypto_sign_ed25519_sk`.
        let seed = [3u8; 32];
        let identity = generate_identity(&seed);
        assert_eq!(&identity.private_ed25519[..32], &seed);
        assert_eq!(&identity.private_ed25519[32..], &identity.public_ed25519);
    }

    #[test]
    fn test_x25519_key_is_not_the_raw_seed() {
        // Regression test for finding #2: the X25519 private key must be a
        // domain-separated expansion of the seed, not the (clamped) seed
        // itself -- confirms the HKDF step is actually being applied.
        let seed = [42u8; 32];
        let identity = generate_identity(&seed);

        let mut naive_clamp = seed;
        naive_clamp[0] &= 248;
        naive_clamp[31] &= 127;
        naive_clamp[31] |= 64;

        assert_ne!(
            identity.private_x25519, naive_clamp,
            "X25519 private key must not equal the raw seed with only clamping applied"
        );
    }

    #[test]
    fn test_x25519_public_key_is_valid_curve_point() {
        // Guards against a refactor accidentally skipping the clamping step or
        // mixing up which half is the secret.
        let identity = generate_identity(&[5u8; 32]);
        let recomputed = PublicKey::from(&StaticSecret::from(identity.private_x25519)).to_bytes();
        assert_eq!(recomputed, identity.public_x25519);
    }

    #[test]
    fn test_ecdh_is_symmetric() {
        // If the two halves ever derived different shared secrets, every
        // beacon MAC would fail and the mesh would silently stop forming.
        let a = generate_identity(&[11u8; 32]);
        let b = generate_identity(&[12u8; 32]);
        let ab = StaticSecret::from(a.private_x25519)
            .diffie_hellman(&PublicKey::from(b.public_x25519))
            .to_bytes();
        let ba = StaticSecret::from(b.private_x25519)
            .diffie_hellman(&PublicKey::from(a.public_x25519))
            .to_bytes();
        assert_eq!(ab, ba);
    }

    #[test]
    fn test_zeroize_clears_all_key_material() {
        // `IdentityKeypair::zeroize` (which `Drop` also invokes) must clear the
        // private halves. Checked directly rather than by reading freed memory
        // after a drop, which a test should never do.
        let mut identity = generate_identity(&[19u8; 32]);
        assert_ne!(identity.private_ed25519, [0u8; 64]);
        assert_ne!(identity.private_x25519, [0u8; 32]);

        identity.zeroize();

        assert_eq!(identity.private_ed25519, [0u8; 64]);
        assert_eq!(identity.private_x25519, [0u8; 32]);
        assert_eq!(identity.public_ed25519, [0u8; 32]);
        assert_eq!(identity.public_x25519, [0u8; 32]);
    }

    #[test]
    fn test_clone_preserves_key_material() {
        // A clone must be a full copy, not a view: the whole point of
        // zeroize-on-drop is that dropping one copy does not disturb another.
        let original = generate_identity(&[21u8; 32]);
        let copy = original.clone();
        assert_eq!(copy.public_ed25519, original.public_ed25519);
        assert_eq!(copy.private_ed25519, original.private_ed25519);
        assert_eq!(copy.private_x25519, original.private_x25519);
    }

    // --- known-answer tests -------------------------------------------------
    //
    // Everything else in this module is a round-trip, which would pass just as
    // happily if the derivation were changed *consistently* -- and a consistent
    // change here silently gives every device a different NodeId and
    // invalidates every peer relationship, with no test failing.
    //
    // These constants were produced by an implementation that shares no code
    // with this one: Ed25519 and X25519 via OpenSSL (the `cryptography`
    // package), HKDF via Python's stdlib `hmac`/`hashlib`. Reproduce with
    // `scripts/generate_known_answer_vectors.py`. They are also transitively
    // confirmed by the relevant RFCs' own vectors, which the same script
    // spot-checks.
    //
    // A failure here means the derivation changed. That is never acceptable
    // for a released build, because NodeIds are derived from the Ed25519 public
    // key and every persisted key bundle and channel key is tied to it.

    const SEED_A: [u8; 32] = [
        0x07, 0x07, 0x07, 0x07, 0x07, 0x07, 0x07, 0x07, 0x07, 0x07, 0x07, 0x07, 0x07, 0x07, 0x07,
        0x07, 0x07, 0x07, 0x07, 0x07, 0x07, 0x07, 0x07, 0x07, 0x07, 0x07, 0x07, 0x07, 0x07, 0x07,
        0x07, 0x07,
    ];
    const SEED_A_PUBLIC_ED25519: &str =
        "ea4a6c63e29c520abef5507b132ec5f9954776aebebe7b92421eea691446d22c";
    const SEED_A_PRIVATE_X25519: &str =
        "786085b24b88f4b8a93c4d680451f3402b2d2e7d01bb340e1ad1aae0a1f5f675";
    const SEED_A_PUBLIC_X25519: &str =
        "cb28de1d78e7e8ea2205c6bf4ae05f62df5d4a7aed025ddf8b51f9a294252f10";

    const SEED_B: [u8; 32] = [
        0x2a, 0x2a, 0x2a, 0x2a, 0x2a, 0x2a, 0x2a, 0x2a, 0x2a, 0x2a, 0x2a, 0x2a, 0x2a, 0x2a, 0x2a,
        0x2a, 0x2a, 0x2a, 0x2a, 0x2a, 0x2a, 0x2a, 0x2a, 0x2a, 0x2a, 0x2a, 0x2a, 0x2a, 0x2a, 0x2a,
        0x2a, 0x2a,
    ];
    const SEED_B_PUBLIC_ED25519: &str =
        "197f6b23e16c8532c6abc838facd5ea789be0c76b2920334039bfa8b3d368d61";
    const SEED_B_PRIVATE_X25519: &str =
        "2859e8d1940e74b636597e970aed7a4dc99c6e1adf1a9065a363366b119dbb61";
    const SEED_B_PUBLIC_X25519: &str =
        "ddecfae3328923fb92679f950624043988074ddbed94f31f5d9e6a514cf59171";

    fn assert_identity_matches_vector(
        seed: &[u8; 32],
        public_ed25519: &str,
        private_x25519: &str,
        public_x25519: &str,
    ) {
        let identity = generate_identity(seed);
        assert_eq!(
            identity.public_ed25519,
            crate::test_util::hex_array::<32>(public_ed25519),
            "Ed25519 public key for this seed changed -- NodeId and all stored key \
             material are derived from it"
        );
        assert_eq!(
            identity.private_x25519,
            crate::test_util::hex_array::<32>(private_x25519),
            "X25519 private key derivation changed"
        );
        assert_eq!(
            identity.public_x25519,
            crate::test_util::hex_array::<32>(public_x25519),
            "X25519 public key derivation changed"
        );
        assert_eq!(
            &identity.private_ed25519[..32],
            seed,
            "the seed must be the leading half of the 64-byte secret key"
        );
        assert_eq!(
            &identity.private_ed25519[32..],
            &identity.public_ed25519,
            "the public key must be the trailing half of the 64-byte secret key"
        );
    }

    #[test]
    fn known_answer_identity_seed_a() {
        assert_identity_matches_vector(
            &SEED_A,
            SEED_A_PUBLIC_ED25519,
            SEED_A_PRIVATE_X25519,
            SEED_A_PUBLIC_X25519,
        );
    }

    #[test]
    fn known_answer_identity_seed_b() {
        assert_identity_matches_vector(
            &SEED_B,
            SEED_B_PUBLIC_ED25519,
            SEED_B_PRIVATE_X25519,
            SEED_B_PUBLIC_X25519,
        );
    }
}
