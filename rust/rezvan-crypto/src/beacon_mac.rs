//! Pairwise beacon authentication.
//!
//! `AdvBeaconExt` is a legacy-BLE advertisement with a hard 24-byte payload
//! budget -- there is no room for a real Ed25519 signature (64 bytes). What
//! *does* fit is a short keyed MAC, so this module derives a per-ordered-pair
//! MAC key via X25519 ECDH (reusing each identity's existing `public_x25519`/
//! `private_x25519` -- the same X25519 keypair already derived from the
//! seed in `identity.rs`, not vodozemac's Olm session keys) followed by
//! HKDF-SHA256, then computes a 7-byte truncated HMAC-SHA256 tag over the
//! beacon's fields.
//!
//! Security properties (be precise about what this does and doesn't give
//! you -- see security audit finding #3 and Fix 3):
//!   * A node that has never exchanged keys with the sender (no prior
//!     KeyAnnouncement) cannot verify this tag. That's expected: beacons are
//!     broadcast to everyone in range, including brand-new peers, so first
//!     contact is necessarily unauthenticated at the beacon layer. Callers
//!     MUST treat an unverifiable beacon (unknown sender) as
//!     informational/discovery-only, never as input to a routing decision.
//!   * Once a peer's X25519 key is known, this proves the beacon was
//!     produced by someone holding that peer's private X25519 key (i.e. the
//!     same seed-derived identity as the registered KeyAnnouncement) --
//!     forgeable only by an attacker who already compromised one of the two
//!     nodes' private key material.
//!   * This is a MAC, not a digital signature: it does not provide
//!     non-repudiation (either party who can verify the tag could also have
//!     produced it), unlike the full Ed25519 signatures used on
//!     `MeshPacketHeader`-based packets where there's room for a real one.

use crate::hkdf::hkdf_sha256;
use sodiumoxide::crypto::auth::hmacsha256;
use sodiumoxide::crypto::scalarmult;

pub const BEACON_MAC_LEN: usize = 7;

/// Derive the pairwise MAC key for authenticating beacons between two nodes.
///
/// `our_private_x25519` / `their_public_x25519` are each node's identity-seed-
/// derived X25519 keypair halves (see `IdentityKeypair`). ECDH is symmetric,
/// so both directions derive the same shared secret; `info` binds the
/// derived key to "beacon-mac" so it can never collide with a key derived
/// for a different purpose from the same ECDH secret.
fn derive_shared_key(our_private_x25519: &[u8; 32], their_public_x25519: &[u8; 32]) -> [u8; 32] {
    let scalar = scalarmult::Scalar(*our_private_x25519);
    let point = scalarmult::GroupElement(*their_public_x25519);
    // scalarmult can fail only on a small-order/degenerate input point;
    // treat that as "no usable key" by falling back to an all-zero secret,
    // which HKDF then turns into a key that will simply never match a
    // legitimately-derived tag from the other side (fails closed).
    let shared = scalarmult::scalarmult(&scalar, &point)
        .map(|g| g.0)
        .unwrap_or([0u8; 32]);

    let okm = hkdf_sha256(&shared, &[], b"rezvan-beacon-mac-v1", 32);
    let mut key = [0u8; 32];
    key.copy_from_slice(&okm);
    key
}

/// Compute the truncated beacon MAC tag over `message` (the beacon's
/// serialized fields excluding the tag itself).
pub fn compute_tag(
    our_private_x25519: &[u8; 32],
    their_public_x25519: &[u8; 32],
    message: &[u8],
) -> [u8; BEACON_MAC_LEN] {
    let key_bytes = derive_shared_key(our_private_x25519, their_public_x25519);
    let key = hmacsha256::Key(key_bytes);
    let full_tag = hmacsha256::authenticate(message, &key);

    let mut tag = [0u8; BEACON_MAC_LEN];
    tag.copy_from_slice(&full_tag.0[..BEACON_MAC_LEN]);
    tag
}

/// Verify a beacon's truncated MAC tag. Constant-time comparison is not
/// load-bearing here the way it would be for a full-length MAC: at 7 bytes
/// (56 bits) the tag is already brute-forceable offline given enough
/// captured beacons, so this is a deterrence/casual-forgery control, not a
/// cryptographic non-forgeability guarantee. Document this in any UI/threat
/// model text -- see module docs above.
pub fn verify_tag(
    our_private_x25519: &[u8; 32],
    their_public_x25519: &[u8; 32],
    message: &[u8],
    tag: &[u8; BEACON_MAC_LEN],
) -> bool {
    let expected = compute_tag(our_private_x25519, their_public_x25519, message);
    // Manual constant-time-ish comparison (XOR-fold, no early return) rather
    // than a library helper whose exact signature we'd otherwise have to
    // assume. At 7 bytes (56 bits) this tag is already brute-forceable
    // offline given enough captured beacons regardless of comparison
    // timing, so this is a minor defense-in-depth nicety, not the primary
    // protection -- see module docs above.
    let mut diff: u8 = 0;
    for i in 0..BEACON_MAC_LEN {
        diff |= expected[i] ^ tag[i];
    }
    diff == 0
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::identity::generate_identity;

    #[test]
    fn test_pairwise_tag_matches_both_directions() {
        let alice = generate_identity(&[1u8; 32]);
        let bob = generate_identity(&[2u8; 32]);

        let msg = b"beacon-fields-go-here";

        let tag_from_alice = compute_tag(&alice.private_x25519, &bob.public_x25519, msg);
        let tag_from_bob = compute_tag(&bob.private_x25519, &alice.public_x25519, msg);

        assert_eq!(tag_from_alice, tag_from_bob, "ECDH must be symmetric");
        assert!(verify_tag(&bob.private_x25519, &alice.public_x25519, msg, &tag_from_alice));
    }

    #[test]
    fn test_tag_rejects_wrong_sender() {
        let alice = generate_identity(&[1u8; 32]);
        let bob = generate_identity(&[2u8; 32]);
        let mallory = generate_identity(&[3u8; 32]);

        let msg = b"beacon-fields-go-here";
        let tag_from_alice = compute_tag(&alice.private_x25519, &bob.public_x25519, msg);

        // Bob verifying against Mallory's key (wrong claimed sender) must fail.
        assert!(!verify_tag(&bob.private_x25519, &mallory.public_x25519, msg, &tag_from_alice));
    }

    #[test]
    fn test_tag_rejects_tampered_message() {
        let alice = generate_identity(&[1u8; 32]);
        let bob = generate_identity(&[2u8; 32]);

        let tag = compute_tag(&alice.private_x25519, &bob.public_x25519, b"original");
        assert!(!verify_tag(&bob.private_x25519, &alice.public_x25519, b"tampered!", &tag));
    }
}
