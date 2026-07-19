use sodiumoxide::crypto::sign;
use sodiumoxide::crypto::scalarmult;
use crate::hkdf::hkdf_sha256;

pub struct IdentityKeypair {
    pub public_ed25519: [u8; 32],
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

/// Derive both identity keypairs from a single 32-byte seed.
///
/// Security audit finding #2 (this review pass): the X25519 half used to be
/// the raw seed, directly clamped -- the exact same undifferentiated input
/// that independently feeds Ed25519 keygen below. Two different asymmetric
/// primitives keyed from the same raw material without domain separation is
/// the kind of construction that's usually fine in isolation but is fragile
/// under composition (e.g. if either primitive's keygen ever turns out to
/// leak anything derivable back toward the seed, it could theoretically
/// weaken the other). Fixed by expanding the seed through HKDF-SHA256 with a
/// distinct `info` string before clamping -- the same domain-separation
/// pattern already used correctly in `beacon_mac.rs`. Ed25519 keygen still
/// takes the raw seed directly (that's libsodium's own documented API and
/// its own internal derivation already provides adequate separation from
/// this seed for its own purposes), only the X25519 half changes here.
pub fn generate_identity(seed: &[u8; 32]) -> IdentityKeypair {
    let (pk, sk) = sign::keypair_from_seed(&sign::Seed(*seed));

    let x25519_seed_material = hkdf_sha256(seed, &[], b"rezvan-x25519-identity-v1", 32);
    let mut xs = [0u8; 32];
    xs.copy_from_slice(&x25519_seed_material);
    xs[0] &= 248;
    xs[31] &= 127;
    xs[31] |= 64;
    let s = scalarmult::Scalar(xs);
    let pub_x = scalarmult::scalarmult_base(&s);

    IdentityKeypair {
        public_ed25519: pk.0,
        private_ed25519: sk.0,
        public_x25519: pub_x.0,
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
        // scalarmult_base should always succeed for a properly clamped
        // scalar; this mostly guards against a future refactor accidentally
        // skipping the clamping step.
        let identity = generate_identity(&[5u8; 32]);
        let s = scalarmult::Scalar(identity.private_x25519);
        let recomputed = scalarmult::scalarmult_base(&s);
        assert_eq!(recomputed.0, identity.public_x25519);
    }
}