//! Pairwise beacon authentication.
//!
//! **SUPERSEDED**: `engine.rs` no longer calls this module. It was replaced
//! by `epoch_key.rs`'s network-wide shared key scheme (explicit product
//! decision: robustness/verifiability over limiting a compromised device's
//! blast radius -- see that module's docs for the full design and
//! rationale). The scheme documented below turned out to be broken in
//! practice, not just "unverifiable by design": the sender computed its half
//! of the ECDH against a placeholder all-zero key rather than any real
//! recipient's key (BLE broadcasts have no single addressable recipient to
//! target), so no real receiver's independently-computed shared secret could
//! ever match what the sender produced -- verification failed for everyone,
//! always, even between two peers who'd fully exchanged keys.
//!
//! Left in the codebase (not deleted) as a reference for the per-pair
//! authentication pattern, which IS the right approach for genuinely
//! addressed (non-broadcast) communication -- see how the same
//! ECDH+HKDF+truncated-HMAC shape could still apply to a future point-to-point
//! transport. Do not wire this back into beacon handling without fixing the
//! fundamental broadcast-recipient-targeting problem described above first.
//!
//! ---
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
use hmac::{Hmac, Mac};
use sha2::Sha256;
use x25519_dalek::{PublicKey, StaticSecret};

type HmacSha256 = Hmac<Sha256>;

pub const BEACON_MAC_LEN: usize = 7;

/// Derive the pairwise MAC key for authenticating beacons between two nodes.
///
/// `our_private_x25519` / `their_public_x25519` are each node's identity-seed-
/// derived X25519 keypair halves (see `IdentityKeypair`). ECDH is symmetric,
/// so both directions derive the same shared secret; `info` binds the
/// derived key to "beacon-mac" so it can never collide with a key derived
/// for a different purpose from the same ECDH secret.
fn derive_shared_key(our_private_x25519: &[u8; 32], their_public_x25519: &[u8; 32]) -> [u8; 32] {
    // Both libraries clamp the scalar internally per RFC 7748, so passing the
    // already-clamped private key through unchanged reproduces the same shared
    // secret the previous implementation produced.
    let secret = StaticSecret::from(*our_private_x25519);
    let their_public = PublicKey::from(*their_public_x25519);
    let shared = secret.diffie_hellman(&their_public).to_bytes();

    // A small-order ("degenerate") input point yields the identity as the
    // Montgomery-ladder output, i.e. all zeros. That is the same
    // "no usable key" result the previous `scalarmult(..).unwrap_or([0u8; 32])`
    // fallback produced, and HKDF turns it into a key whose tag will never
    // match anything a legitimate peer computes, so the forgery attempt fails
    // closed either way.

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
    let mut mac = HmacSha256::new_from_slice(&key_bytes).expect("HMAC accepts keys of any length");
    mac.update(message);
    let full_tag = mac.finalize().into_bytes();

    let mut tag = [0u8; BEACON_MAC_LEN];
    tag.copy_from_slice(&full_tag[..BEACON_MAC_LEN]);
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
        assert!(verify_tag(
            &bob.private_x25519,
            &alice.public_x25519,
            msg,
            &tag_from_alice
        ));
    }

    #[test]
    fn test_tag_rejects_wrong_sender() {
        let alice = generate_identity(&[1u8; 32]);
        let bob = generate_identity(&[2u8; 32]);
        let mallory = generate_identity(&[3u8; 32]);

        let msg = b"beacon-fields-go-here";
        let tag_from_alice = compute_tag(&alice.private_x25519, &bob.public_x25519, msg);

        // Bob verifying against Mallory's key (wrong claimed sender) must fail.
        assert!(!verify_tag(
            &bob.private_x25519,
            &mallory.public_x25519,
            msg,
            &tag_from_alice
        ));
    }

    #[test]
    fn test_tag_rejects_tampered_message() {
        let alice = generate_identity(&[1u8; 32]);
        let bob = generate_identity(&[2u8; 32]);

        let tag = compute_tag(&alice.private_x25519, &bob.public_x25519, b"original");
        assert!(!verify_tag(
            &bob.private_x25519,
            &alice.public_x25519,
            b"tampered!",
            &tag
        ));
    }

    // --- known-answer test ---------------------------------------------------
    //
    // A round-trip here would pass even if the whole derivation changed
    // consistently, which would leave two devices unable to authenticate each
    // other's beacons with no test failing. These constants come from an
    // independent implementation (OpenSSL X25519 + stdlib HMAC) -- see
    // `scripts/generate_known_answer_vectors.py`.
    #[test]
    fn known_answer_beacon_mac_tag() {
        const A_PRIVATE: &str = "c0412d67179c5095ba87f8f73ad0f66e831cacf8dd4b960161841dfe03f33f59";
        const B_PUBLIC: &str = "286b97fb1c25462e3aeda0d0944f35345e34591b6e5cd2f4b5e69aeaaa3fa06a";
        const EXPECTED_TAG: &str = "d8b621e9bf8594";

        let a_private = crate::test_util::hex_array::<32>(A_PRIVATE);
        let b_public = crate::test_util::hex_array::<32>(B_PUBLIC);
        let message = b"beacon-payload";

        assert_eq!(
            compute_tag(&a_private, &b_public, message).to_vec(),
            crate::test_util::hex(EXPECTED_TAG),
            "beacon MAC derivation changed; peers would stop authenticating each other"
        );
        assert!(verify_tag(
            &a_private,
            &b_public,
            message,
            &crate::test_util::hex_array::<7>(EXPECTED_TAG)
        ));
    }
}
