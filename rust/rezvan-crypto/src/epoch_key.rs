//! Network-wide shared beacon authentication key ("epoch key").
//!
//! Security audit finding (this review): the original per-pair beacon MAC
//! (see `beacon_mac.rs`) computed the sender's half of the ECDH against a
//! placeholder all-zero "peer" key instead of any real recipient's key --
//! since BLE advertisements are physically broadcast to an unknown audience,
//! there's no single recipient to target. The result was worse than
//! "unverifiable by design": it was broken even for two peers who HAD
//! exchanged real keys, since no real receiver's ECDH computation could ever
//! match what the sender produced.
//!
//! Fix (per explicit product decision: robustness over per-compromised-device
//! blast-radius limitation): every device that has completed at least one
//! KeyAnnouncement exchange holds a single shared 32-byte "epoch key". Any
//! beacon can be verified by any device holding the current (or a
//! reachable-by-ratchet) epoch key -- this proves "produced by a mesh member
//! who has this key", not "produced by this specific named sender". A
//! compromised device can forge beacons appearing to be anyone; this was an
//! accepted, deliberate tradeoff (see conversation) in exchange for a design
//! that doesn't require solving one-to-many-broadcast-recipient-targeting,
//! which isn't solvable in the general case.
//!
//! Distribution: the epoch key + epoch number ride along inside the existing,
//! already-signed KeyAnnouncement bundle (see engine.rs's 0x05 handling and
//! session.rs's key_bundle/register_peer_keys). Plaintext within that signed
//! bundle is fine: proving "you're a mesh member" isn't a secret from a
//! passive eavesdropper who can already see you broadcasting a
//! KeyAnnouncement in the first place; only message CONTENT needs
//! confidentiality, and this key never touches that.
//!
//! Rotation: a decentralized mesh has no central authority to coordinate
//! synchronized key rotation, so this uses a forward-secret RATCHET instead:
//! epoch_key[n+1] = HKDF-SHA256(epoch_key[n], info="...ratchet-v1"). Any
//! device holding epoch N can always derive epoch N+1, N+2, ... forward, but
//! never backward -- so advancing the epoch doesn't require redistributing
//! anything, and a device that's compromised at epoch N cannot recover
//! earlier epochs it never held. Devices independently advance their local
//! epoch on a time-based schedule (see `EPOCH_DURATION_SECS`); a
//! newly-joining device catches up to the mesh's current epoch via whatever
//! epoch number the KeyAnnouncement it receives declares, ratcheting forward
//! from whatever key material it has.

use crate::hkdf::hkdf_sha256;
use sodiumoxide::crypto::auth::hmacsha256;

/// How often (in seconds) a device should locally advance its epoch by one
/// ratchet step. This is a LOCAL clock-driven schedule, not a coordinated
/// broadcast -- devices with roughly-synchronized clocks converge on the
/// same epoch number over time; devices that were offline catch up via
/// `advance_to` when they next hear a KeyAnnouncement declaring a newer epoch.
pub const EPOCH_DURATION_SECS: u64 = 6 * 60 * 60; // 6 hours

/// Length of the truncated beacon tag, matching the wire budget already
/// carved out in AdvBeaconExt (see rezvan-common::AdvBeaconExt::MAC_LEN).
pub const EPOCH_MAC_LEN: usize = 7;

/// Generate a brand-new epoch key (for the first device bootstrapping a
/// mesh with no prior epoch key from any peer).
pub fn generate_seed_key() -> [u8; 32] {
    let mut key = [0u8; 32];
    let random_bytes = sodiumoxide::randombytes::randombytes(32);
    key.copy_from_slice(&random_bytes);
    key
}

/// Advance a key forward by exactly one ratchet step. Deterministic and
/// one-way: knowing `next` does not let you recover `key`.
pub fn ratchet_forward(key: &[u8; 32]) -> [u8; 32] {
    let okm = hkdf_sha256(key, &[], b"rezvan-beacon-epoch-ratchet-v1", 32);
    let mut out = [0u8; 32];
    out.copy_from_slice(&okm);
    out
}

/// Advance `key` (currently at `current_epoch`) forward to `target_epoch`,
/// returning the resulting key. Returns `None` if `target_epoch <
/// current_epoch` (ratchet is one-way; can't go backward) or if the gap is
/// implausibly large (bounds a malicious/corrupt epoch number from forcing
/// unbounded computation -- see `MAX_RATCHET_STEPS`).
///
/// `target_epoch == current_epoch` is allowed and returns `key` unchanged
/// (zero steps), which matters for the common case of a peer already on the
/// same epoch we are.
const MAX_RATCHET_STEPS: u32 = 10_000; // ~6.8 years at the default 6h cadence

pub fn advance_to(key: &[u8; 32], current_epoch: u32, target_epoch: u32) -> Option<[u8; 32]> {
    if target_epoch < current_epoch {
        return None;
    }
    let steps = target_epoch - current_epoch;
    if steps > MAX_RATCHET_STEPS {
        return None;
    }
    let mut k = *key;
    for _ in 0..steps {
        k = ratchet_forward(&k);
    }
    Some(k)
}

/// Compute the truncated tag over `message` (the beacon's signed_bytes())
/// using the given epoch key.
pub fn compute_tag(epoch_key: &[u8; 32], message: &[u8]) -> [u8; EPOCH_MAC_LEN] {
    let key = hmacsha256::Key(*epoch_key);
    let full_tag = hmacsha256::authenticate(message, &key);
    let mut tag = [0u8; EPOCH_MAC_LEN];
    tag.copy_from_slice(&full_tag.0[..EPOCH_MAC_LEN]);
    tag
}

/// Verify a beacon's tag against the given epoch key. See `compute_tag`'s
/// comparison-timing note in `beacon_mac.rs` -- same applies here: at 7
/// bytes this is a deterrence control, not a strong cryptographic
/// non-forgeability guarantee at scale, same as the per-pair scheme it
/// replaces.
pub fn verify_tag(epoch_key: &[u8; 32], message: &[u8], tag: &[u8; EPOCH_MAC_LEN]) -> bool {
    let expected = compute_tag(epoch_key, message);
    let mut diff: u8 = 0;
    for i in 0..EPOCH_MAC_LEN {
        diff |= expected[i] ^ tag[i];
    }
    diff == 0
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_ratchet_is_deterministic() {
        let key = generate_seed_key();
        let next_a = ratchet_forward(&key);
        let next_b = ratchet_forward(&key);
        assert_eq!(next_a, next_b);
    }

    #[test]
    fn test_ratchet_changes_the_key() {
        let key = generate_seed_key();
        let next = ratchet_forward(&key);
        assert_ne!(key, next);
    }

    #[test]
    fn test_advance_to_same_epoch_is_noop() {
        let key = generate_seed_key();
        let result = advance_to(&key, 5, 5).unwrap();
        assert_eq!(result, key);
    }

    #[test]
    fn test_advance_to_matches_manual_ratchet_chain() {
        let key = generate_seed_key();
        let manual = ratchet_forward(&ratchet_forward(&ratchet_forward(&key)));
        let via_advance = advance_to(&key, 0, 3).unwrap();
        assert_eq!(manual, via_advance);
    }

    #[test]
    fn test_advance_to_backward_rejected() {
        let key = generate_seed_key();
        assert!(advance_to(&key, 5, 3).is_none());
    }

    #[test]
    fn test_advance_to_excessive_gap_rejected() {
        let key = generate_seed_key();
        assert!(advance_to(&key, 0, MAX_RATCHET_STEPS + 1).is_none());
    }

    #[test]
    fn test_two_devices_converge_via_ratchet() {
        // Alice is at epoch 2 with key K2. Bob is at epoch 5 with key K5
        // (derived independently by ratcheting forward from the same
        // original seed). Alice, upon learning Bob is at epoch 5, should be
        // able to derive the exact same K5 by advancing her own K2 forward.
        let seed = generate_seed_key();
        let k1 = ratchet_forward(&seed);
        let k2 = ratchet_forward(&k1);
        let k3 = ratchet_forward(&k2);
        let k4 = ratchet_forward(&k3);
        let k5 = ratchet_forward(&k4);

        let alice_derived_k5 = advance_to(&k2, 2, 5).unwrap();
        assert_eq!(alice_derived_k5, k5, "Alice must converge to Bob's epoch 5 key");
    }

    #[test]
    fn test_tag_roundtrip() {
        let key = generate_seed_key();
        let msg = b"beacon fields";
        let tag = compute_tag(&key, msg);
        assert!(verify_tag(&key, msg, &tag));
    }

    #[test]
    fn test_tag_rejects_wrong_epoch_key() {
        let key1 = generate_seed_key();
        let key2 = generate_seed_key();
        let msg = b"beacon fields";
        let tag = compute_tag(&key1, msg);
        assert!(!verify_tag(&key2, msg, &tag));
    }

    #[test]
    fn test_tag_rejects_tampered_message() {
        let key = generate_seed_key();
        let tag = compute_tag(&key, b"original");
        assert!(!verify_tag(&key, b"tampered!", &tag));
    }
}
