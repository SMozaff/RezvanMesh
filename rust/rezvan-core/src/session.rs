//! SessionManager — 1:1 message encryption via vodozemac (Olm + Double Ratchet).
//!
//! This replaces the old hand-rolled X3DH + ratchet. Split of responsibility:
//!   * Mesh identity (NodeId, packet signing) stays the seed-derived Ed25519
//!     `IdentityKeypair` — unchanged.
//!   * vodozemac owns ONLY message-encryption keys: a separate Curve25519 Olm
//!     `Account`.
//!
//! Because there is no server to hand out key bundles, each peer's Olm public
//! keys arrive out-of-band (a KeyAnnouncement broadcast or a scanned QR contact)
//! and are handed in via `register_peer_keys`. Our own bundle to advertise is
//! produced by `key_bundle`.

use std::collections::{HashMap, HashSet};

use rezvan_common::NodeId;
use rezvan_crypto::{CryptoProvider, IdentityKeypair};
use vodozemac::olm::{Account, OlmMessage, Session, SessionConfig};
use vodozemac::{Curve25519PublicKey, KeyId};

#[derive(Debug)]
pub enum SessionError {
    /// We don't have this peer's Olm keys yet (need a KeyAnnouncement / QR scan).
    NoPeerKeys,
    /// A normal message arrived but no session exists to decrypt it.
    NoSession,
    /// Wire bytes could not be parsed as an Olm message.
    BadMessage,
    /// vodozemac returned an error.
    Olm(String),
}

/// A peer's advertised keys: Olm Curve25519 half (message encryption) plus
/// the seed-derived mesh identity keys (beacon MAC + packet signatures).
/// `olm_identity`/`one_time` are vodozemac's Olm account keys -- distinct
/// from `x25519_identity`, which is the same seed-derived X25519 keypair
/// used for the beacon MAC (see rezvan_crypto::identity::IdentityKeypair
/// and rezvan_crypto::beacon_mac). Keeping them separate is deliberate:
/// Olm session keys and mesh-identity keys serve different purposes and
/// must not be conflated.
struct PeerKeys {
    olm_identity: Curve25519PublicKey,
    one_time: Curve25519PublicKey,
    x25519_identity: [u8; 32],
    ed25519_identity: [u8; 32],
}

pub struct SessionManager {
    identity: IdentityKeypair,            // seed-derived; drives NodeId + signing
    account: Account,                     // our Olm account (message-encryption keys)
    sessions: HashMap<NodeId, Session>,   // established Olm sessions, per peer
    peer_keys: HashMap<NodeId, PeerKeys>, // peers' advertised key bundles
    /// Per-channel shared symmetric keys for group messaging (see
    /// rezvan_crypto::sender_key). Distribution mechanism (how members agree
    /// on this key out-of-band) is intentionally out of scope here, same as
    /// documented in sender_key.rs -- this only stores/retrieves whatever key
    /// the UI layer has already agreed on for a given channel.
    channel_keys: HashMap<u32, [u8; 32]>,
    /// Set of one-time-key IDs from the current generated batch that have
    /// already been advertised via `key_bundle()`.
    ///
    /// Bug fix (found while re-auditing security audit finding #9): the
    /// original rotation scheme tracked a positional index
    /// (`otk_advertise_index`) into `Account::one_time_keys()`, on the
    /// assumption noted in this field's old doc comment that "iteration
    /// order is stable across calls." It is NOT: vodozemac's
    /// `one_time_keys()` returns a brand-new `HashMap<KeyId,
    /// Curve25519PublicKey>` built fresh on every call (`.collect()` over
    /// its internal store), and `HashMap` iteration order is not guaranteed
    /// stable even across two calls over identical underlying data. In
    /// practice this was verified to actually repeat keys within a single
    /// 20-key batch (caught by
    /// `test_key_bundle_rotates_through_full_batch_without_repeat`), which
    /// defeats one-time-prekey forward secrecy exactly the way the original
    /// finding #9 bug did -- just non-deterministically instead of always.
    ///
    /// Fixed by tracking actual advertised `KeyId`s (vodozemac's own stable,
    /// generation-order identifier for each one-time key) in a set, and
    /// picking any not-yet-advertised key each call. This is correct
    /// regardless of `one_time_keys()`'s iteration order because membership
    /// in the set is keyed by identity, not position.
    otk_advertised: HashSet<KeyId>,
    /// Network-wide shared beacon-authentication key + its epoch number (see
    /// rezvan_crypto::epoch_key module docs for the full design rationale).
    /// `None` until either (a) this device bootstraps a brand-new mesh with
    /// no prior key from anyone, or (b) it learns one from a peer's
    /// KeyAnnouncement. Every device that has done at least one
    /// KeyAnnouncement exchange ends up holding this.
    epoch_key: Option<[u8; 32]>,
    epoch_number: u32,
}

impl SessionManager {
    /// `_crypto` is accepted only for API compatibility with the engine; it is
    /// no longer used for sessions, because vodozemac owns that now.
    pub fn new(_crypto: Box<dyn CryptoProvider>, identity: IdentityKeypair) -> Self {
        let mut account = Account::new();
        account.generate_one_time_keys(20);
        Self {
            identity,
            account,
            sessions: HashMap::new(),
            peer_keys: HashMap::new(),
            channel_keys: HashMap::new(),
            otk_advertised: HashSet::new(),
            epoch_key: None,
            epoch_number: 0,
        }
    }

    /// Ensures this device has SOME epoch key, generating a fresh one if it
    /// doesn't have one yet (bootstrapping a brand-new mesh with no prior
    /// key from any peer). Idempotent: does nothing if we already have one
    /// (from a prior bootstrap or from learning one via KeyAnnouncement).
    /// Called lazily the first time `key_bundle()` needs to advertise one.
    fn ensure_epoch_key(&mut self) {
        if self.epoch_key.is_none() {
            self.epoch_key = Some(rezvan_crypto::epoch_key::generate_seed_key());
            self.epoch_number = 0;
        }
    }

    /// Learn a peer's epoch key/number from their KeyAnnouncement bundle, and
    /// converge onto whichever one is "ahead":
    ///   - If we have no epoch key at all yet, adopt theirs outright.
    ///   - If theirs is at a later epoch than ours, ratchet OUR key forward
    ///     to catch up (this recovers the same key they have, since the
    ///     ratchet is deterministic -- see epoch_key::advance_to).
    ///   - If ours is later than theirs (they're behind, e.g. rejoining after
    ///     being offline), we keep ours -- they'll catch up when they see a
    ///     KeyAnnouncement from someone ahead, including possibly us.
    ///   - If we're at the same epoch, no-op (should already match, since
    ///     the ratchet is deterministic from a shared history).
    fn converge_epoch_key(&mut self, their_epoch: u32, their_key: [u8; 32]) {
        match self.epoch_key {
            None => {
                self.epoch_key = Some(their_key);
                self.epoch_number = their_epoch;
            }
            Some(our_key) => {
                if their_epoch > self.epoch_number {
                    if let Some(caught_up) = rezvan_crypto::epoch_key::advance_to(&our_key, self.epoch_number, their_epoch) {
                        self.epoch_key = Some(caught_up);
                        self.epoch_number = their_epoch;
                    }
                    // If advance_to returned None (implausible gap), keep our
                    // current key/epoch rather than adopting an unverified
                    // one blindly -- see epoch_key::MAX_RATCHET_STEPS.
                }
                // their_epoch <= self.epoch_number: nothing to do.
            }
        }
    }

    /// Locally advance our epoch by one ratchet step. Call periodically
    /// (see rezvan_crypto::epoch_key::EPOCH_DURATION_SECS) from the engine's
    /// tick() cadence. No-op if we don't have an epoch key yet.
    pub fn advance_epoch(&mut self) {
        if let Some(key) = self.epoch_key {
            self.epoch_key = Some(rezvan_crypto::epoch_key::ratchet_forward(&key));
            self.epoch_number += 1;
        }
    }

    /// Current epoch key + number, for computing/verifying beacon tags.
    /// `None` if we haven't bootstrapped or learned one yet (e.g. brand new
    /// install with zero peers ever seen) -- callers must treat that as "no
    /// beacon authentication possible yet", same as the old per-pair scheme's
    /// "sender unknown" case.
    pub fn epoch_key(&self) -> Option<([u8; 32], u32)> {
        self.epoch_key.map(|k| (k, self.epoch_number))
    }

    /// Generate a new random shared key for a channel and store it. Called
    /// when the local user creates a channel; the same 32 bytes must then be
    /// shared with other members out-of-band (QR code, manual entry, etc --
    /// distribution mechanism is intentionally not designed here, same scope
    /// note as sender_key.rs). Returns the generated key so the caller can
    /// display/export it.
    pub fn create_channel_key(&mut self, channel_id: u32) -> [u8; 32] {
        let key = rezvan_crypto::sender_key::generate();
        self.channel_keys.insert(channel_id, key);
        key
    }

    /// Store a channel key received out-of-band (e.g. scanned from another
    /// member's QR code, or entered manually when joining an existing
    /// channel).
    pub fn set_channel_key(&mut self, channel_id: u32, key: [u8; 32]) {
        self.channel_keys.insert(channel_id, key);
    }

    /// Returns the shared key for a channel, if we have one (i.e. we created
    /// it or joined with a key). `None` means we can't send/receive on this
    /// channel yet.
    pub fn channel_key(&self, channel_id: u32) -> Option<[u8; 32]> {
        self.channel_keys.get(&channel_id).copied()
    }

    pub fn identity(&self) -> IdentityKeypair {
        self.identity.clone()
    }

    // --- serverless key-bundle exchange -------------------------------------

    /// Our bundle to advertise: Olm identity key (32) ++ Olm one-time key (32)
    /// ++ mesh X25519 identity key (32) ++ mesh Ed25519 identity key (32) =
    /// 128 bytes total. Kotlin broadcasts this in a KeyAnnouncement and
    /// embeds it in the QR code. The mesh identity keys (last 64 bytes) are
    /// what let peers verify beacon MACs and MeshPacketHeader signatures
    /// (security audit finding #3 / Fix 3) -- they were not previously
    /// exchanged at all.
    ///
    /// One-time-key hygiene (security audit finding #9, later re-fixed): the
    /// original version of this function always returned
    /// `one_time_keys().values().next()` and never called
    /// `mark_keys_as_published()`, so the exact same OTK was advertised in
    /// every KeyAnnouncement forever -- defeating one-time-prekey forward
    /// secrecy (if that key is ever compromised, it compromises every
    /// session anyone ever established using it, not just one).
    ///
    /// That was first fixed by rotating through the generated batch via a
    /// positional index into `one_time_keys()`'s iteration order, on the
    /// documented assumption that the order was stable across calls. It
    /// was not: vodozemac's `one_time_keys()` returns a freshly-collected
    /// `HashMap` on every call, whose iteration order is not guaranteed
    /// stable even over unchanged underlying data, and this was verified in
    /// practice to intermittently repeat a key within a single batch.
    ///
    /// Fixed for real by tracking already-advertised keys by their stable
    /// `vodozemac::KeyId` (a monotonic generation-order identifier, not a
    /// position) in `otk_advertised`. Once every key in the current batch
    /// has been advertised at least once, the whole batch is marked
    /// published and a fresh one is generated -- same rotation behavior as
    /// before, just correct regardless of iteration order.
    /// Our bundle to advertise: Olm identity key (32) ++ Olm one-time key (32)
    /// ++ mesh X25519 identity key (32) ++ mesh Ed25519 identity key (32) ++
    /// beacon epoch number (4) ++ beacon epoch key (32) = 164 bytes total.
    /// Kotlin broadcasts this in a KeyAnnouncement and embeds it in the QR
    /// code. The mesh identity keys let peers verify MeshPacketHeader
    /// signatures (security audit finding #3 / Fix 3); the epoch
    /// number/key let peers verify beacon MACs via the network-wide shared
    /// key scheme (see rezvan_crypto::epoch_key -- this superseded an
    /// earlier per-pair ECDH scheme that turned out to be broken for
    /// broadcast beacons, since there's no single addressable recipient to
    /// target).
    pub fn key_bundle(&mut self) -> Vec<u8> {
        if self.account.one_time_keys().is_empty() {
            self.account.generate_one_time_keys(20);
            self.otk_advertised.clear();
        }

        let olm_identity = *self.account.curve25519_key().as_bytes();

        let available = self.account.one_time_keys();
        // Pick any key from the current batch we haven't advertised yet.
        // Iteration order over `available` is NOT assumed stable (see
        // `otk_advertised` field docs) -- correctness here comes from set
        // membership by KeyId, not from position.
        let mut unadvertised = available
            .iter()
            .find(|(id, _)| !self.otk_advertised.contains(id));

        if unadvertised.is_none() {
            // Whole batch advertised at least once already -- rotate.
            self.account.mark_keys_as_published();
            self.account.generate_one_time_keys(20);
            self.otk_advertised.clear();
        }

        // Re-borrow after any rotation above (the previous `available` may
        // now be stale / the account's key set has changed).
        let available = self.account.one_time_keys();
        if unadvertised.is_none() {
            unadvertised = available.iter().next();
        }

        let (one_time_id, one_time_key) = match unadvertised {
            Some((id, key)) => (*id, *key.as_bytes()),
            // Only reachable if `generate_one_time_keys(20)` somehow produced
            // zero keys, which vodozemac's own contract does not permit; we
            // can't construct a placeholder `KeyId` (its inner field isn't
            // public), so fail loudly instead of silently advertising an
            // all-zero key, which would be a much worse failure mode.
            None => panic!(
                "vodozemac Account::generate_one_time_keys(20) produced no keys; \
                 cannot advertise a key bundle"
            ),
        };
        self.otk_advertised.insert(one_time_id);
        let one_time = one_time_key;

        // Bootstrap our own epoch key if we've never had one from anyone --
        // every device that advertises a KeyAnnouncement must carry SOME
        // epoch key/number, since receivers need it to authenticate our
        // beacons (see rezvan_crypto::epoch_key module docs).
        self.ensure_epoch_key();
        let epoch_key = self.epoch_key.expect("ensure_epoch_key just guaranteed this is Some");
        let epoch_number = self.epoch_number;

        let mut out = Vec::with_capacity(164);
        out.extend_from_slice(&olm_identity);
        out.extend_from_slice(&one_time);
        out.extend_from_slice(&self.identity.public_x25519);
        out.extend_from_slice(&self.identity.public_ed25519);
        out.extend_from_slice(&epoch_number.to_be_bytes());
        out.extend_from_slice(&epoch_key);
        out
    }

    /// Store a peer's advertised bundle: olm_identity(32) ++ one_time(32) ++
    /// x25519_identity(32) ++ ed25519_identity(32) ++ epoch_number(4) ++
    /// epoch_key(32) = 164 bytes. Also converges our own epoch key toward
    /// theirs if theirs is further along (see `converge_epoch_key`).
    pub fn register_peer_keys(&mut self, peer: &NodeId, bundle: &[u8]) -> bool {
        if bundle.len() < 164 {
            return false;
        }
        let mut olm_id = [0u8; 32];
        let mut ot = [0u8; 32];
        let mut x25519_id = [0u8; 32];
        let mut ed25519_id = [0u8; 32];
        olm_id.copy_from_slice(&bundle[0..32]);
        ot.copy_from_slice(&bundle[32..64]);
        x25519_id.copy_from_slice(&bundle[64..96]);
        ed25519_id.copy_from_slice(&bundle[96..128]);

        let their_epoch = u32::from_be_bytes([bundle[128], bundle[129], bundle[130], bundle[131]]);
        let mut their_epoch_key = [0u8; 32];
        their_epoch_key.copy_from_slice(&bundle[132..164]);
        self.converge_epoch_key(their_epoch, their_epoch_key);

        self.peer_keys.insert(
            *peer,
            PeerKeys {
                olm_identity: Curve25519PublicKey::from_bytes(olm_id),
                one_time: Curve25519PublicKey::from_bytes(ot),
                x25519_identity: x25519_id,
                ed25519_identity: ed25519_id,
            },
        );
        true
    }

    /// The peer's mesh X25519 identity key, for deriving a beacon MAC key.
    /// `None` if we haven't received a KeyAnnouncement from this peer yet --
    /// callers MUST treat that as "cannot verify this beacon" and not act on
    /// its contents for routing.
    pub fn peer_x25519_identity(&self, peer: &NodeId) -> Option<[u8; 32]> {
        self.peer_keys.get(peer).map(|k| k.x25519_identity)
    }

    /// The peer's mesh Ed25519 identity key, for verifying MeshPacketHeader
    /// signatures. `None` if no KeyAnnouncement has been received yet.
    pub fn peer_ed25519_identity(&self, peer: &NodeId) -> Option<[u8; 32]> {
        self.peer_keys.get(peer).map(|k| k.ed25519_identity)
    }

    /// Our own mesh X25519 private key, for deriving a beacon MAC key.
    pub fn own_x25519_private(&self) -> [u8; 32] {
        self.identity.private_x25519
    }

    // --- message encryption -------------------------------------------------

    pub fn encrypt(&mut self, peer: &NodeId, plaintext: &[u8]) -> Result<Vec<u8>, SessionError> {
        if !self.sessions.contains_key(peer) {
            // Copy the keys out so the peer_keys borrow ends before we touch account.
            let (identity, one_time) = {
                let keys = self.peer_keys.get(peer).ok_or(SessionError::NoPeerKeys)?;
                (keys.olm_identity, keys.one_time)
            };
            let session = self
                .account
                .create_outbound_session(SessionConfig::version_1(), identity, one_time)
                .map_err(|e| SessionError::Olm(e.to_string()))?;
            self.sessions.insert(*peer, session);
        }

        let session = self.sessions.get_mut(peer).ok_or(SessionError::NoSession)?;
        let olm = session
            .encrypt(plaintext)
            .map_err(|e| SessionError::Olm(e.to_string()))?;
        Ok(encode_olm(olm))
    }

    pub fn decrypt(&mut self, peer: &NodeId, wire: &[u8]) -> Result<Vec<u8>, SessionError> {
        let olm = decode_olm(wire)?;

        // Existing session: just decrypt.
        if let Some(session) = self.sessions.get_mut(peer) {
            return session
                .decrypt(&olm)
                .map_err(|e| SessionError::Olm(e.to_string()));
        }

        // No session yet: only a PreKey message can establish one.
        match olm {
            OlmMessage::PreKey(prekey) => {
                let identity = self.peer_keys.get(peer).ok_or(SessionError::NoPeerKeys)?.olm_identity;
                let result = self
                    .account
                    .create_inbound_session(SessionConfig::version_1(), identity, &prekey)
                    .map_err(|e| SessionError::Olm(e.to_string()))?;
                self.sessions.insert(*peer, result.session);
                Ok(result.plaintext)
            }
            OlmMessage::Normal(_) => Err(SessionError::NoSession),
        }
    }

    /// Engine calls this for an explicit handshake packet (type 0x04). With Olm
    /// the first data message already carries the pre-key, so this just ensures
    /// a session exists; any recovered plaintext is ignored here.
    pub fn process_inbound_handshake(
        &mut self,
        peer: &NodeId,
        wire: &[u8],
    ) -> Result<(), SessionError> {
        let _ = self.decrypt(peer, wire)?;
        Ok(())
    }

    pub fn has_session(&self, peer: &NodeId) -> bool {
        self.sessions.contains_key(peer)
    }

    pub fn remove_session(&mut self, peer: &NodeId) {
        self.sessions.remove(peer);
    }
}

// --- wire framing:  [ olm_type : 1 byte ][ ciphertext ... ] -----------------

fn encode_olm(msg: OlmMessage) -> Vec<u8> {
    let (msg_type, ciphertext) = msg.to_parts();
    let mut out = Vec::with_capacity(1 + ciphertext.len());
    out.push(msg_type as u8);
    out.extend_from_slice(&ciphertext);
    out
}

fn decode_olm(wire: &[u8]) -> Result<OlmMessage, SessionError> {
    if wire.is_empty() {
        return Err(SessionError::BadMessage);
    }
    OlmMessage::from_parts(wire[0] as usize, &wire[1..]).map_err(|_| SessionError::BadMessage)
}

#[cfg(test)]
mod tests {
    use super::*;
    use rezvan_crypto::{identity::generate_identity, SodiumCryptoProvider};

    #[test]
    fn test_key_bundle_rotates_one_time_key() {
        let identity = generate_identity(&[9u8; 32]);
        let mut mgr = SessionManager::new(Box::new(SodiumCryptoProvider), identity);

        let bundle1 = mgr.key_bundle();
        let bundle2 = mgr.key_bundle();

        assert_eq!(bundle1.len(), 164, "bundle grew to 164 bytes with the epoch key/number appended");
        assert_eq!(bundle2.len(), 164);
        // Olm identity key (bytes 0..32) and mesh identity keys (64..128)
        // must stay the same across calls -- only the OTK (32..64) rotates.
        assert_eq!(&bundle1[0..32], &bundle2[0..32], "olm identity key must be stable");
        assert_eq!(&bundle1[64..128], &bundle2[64..128], "mesh identity keys must be stable");
        assert_ne!(
            &bundle1[32..64], &bundle2[32..64],
            "one-time key must rotate between calls, not repeat forever (finding #9)"
        );
        // Epoch number/key (bytes 128..164) must ALSO stay stable across
        // calls within the same session -- key_bundle() should not
        // re-bootstrap a new epoch key every time it's called.
        assert_eq!(&bundle1[128..164], &bundle2[128..164], "epoch key/number must be stable across key_bundle() calls");
    }

    #[test]
    fn test_key_bundle_rotates_through_full_batch_without_repeat() {
        let identity = generate_identity(&[3u8; 32]);
        let mut mgr = SessionManager::new(Box::new(SodiumCryptoProvider), identity);

        // Batch size is 20; collect OTKs across a full batch and confirm no
        // immediate repeats within that batch.
        let mut seen = std::collections::HashSet::new();
        for _ in 0..20 {
            let bundle = mgr.key_bundle();
            let otk: [u8; 32] = bundle[32..64].try_into().unwrap();
            assert!(seen.insert(otk), "one-time key repeated within a single batch");
        }
    }

    // ---- Epoch key (network-wide beacon authentication) tests ----

    #[test]
    fn test_no_epoch_key_before_bootstrap() {
        let identity = generate_identity(&[1u8; 32]);
        let mgr = SessionManager::new(Box::new(SodiumCryptoProvider), identity);
        assert!(mgr.epoch_key().is_none(), "fresh SessionManager has no epoch key until key_bundle() or converge_epoch_key() runs");
    }

    #[test]
    fn test_key_bundle_bootstraps_epoch_key_at_zero() {
        let identity = generate_identity(&[2u8; 32]);
        let mut mgr = SessionManager::new(Box::new(SodiumCryptoProvider), identity);
        mgr.key_bundle();
        let (_, epoch) = mgr.epoch_key().expect("key_bundle() must bootstrap an epoch key");
        assert_eq!(epoch, 0, "a freshly bootstrapped epoch key starts at epoch 0");
    }

    #[test]
    fn test_converge_adopts_peer_key_when_we_have_none() {
        let identity_a = generate_identity(&[3u8; 32]);
        let identity_b = generate_identity(&[4u8; 32]);
        let mut alice = SessionManager::new(Box::new(SodiumCryptoProvider), identity_a);
        let mut bob = SessionManager::new(Box::new(SodiumCryptoProvider), identity_b);

        let alice_bundle = alice.key_bundle(); // bootstraps Alice's epoch key
        bob.register_peer_keys(&[1u8; 8], &alice_bundle);

        assert_eq!(alice.epoch_key(), bob.epoch_key(), "Bob (no prior key) must adopt Alice's outright");
    }

    #[test]
    fn test_converge_ratchets_forward_when_peer_is_ahead() {
        let identity_a = generate_identity(&[5u8; 32]);
        let identity_b = generate_identity(&[6u8; 32]);
        let mut alice = SessionManager::new(Box::new(SodiumCryptoProvider), identity_a);
        let mut bob = SessionManager::new(Box::new(SodiumCryptoProvider), identity_b);

        alice.key_bundle(); // Alice bootstraps at epoch 0
        bob.register_peer_keys(&[1u8; 8], &alice.key_bundle()); // Bob adopts epoch 0

        // Advance Alice forward several epochs; Bob is now behind.
        alice.advance_epoch();
        alice.advance_epoch();
        alice.advance_epoch();
        let (_, alice_epoch) = alice.epoch_key().unwrap();
        assert_eq!(alice_epoch, 3);

        // Bob learns Alice is at epoch 3 via a fresh bundle -- must catch up
        // by ratcheting forward, landing on the EXACT same key, not just
        // adopting a number.
        bob.register_peer_keys(&[1u8; 8], &alice.key_bundle());
        assert_eq!(alice.epoch_key(), bob.epoch_key(), "Bob must ratchet forward to match Alice's epoch 3 key exactly");
    }

    #[test]
    fn test_converge_keeps_our_key_when_we_are_ahead() {
        let identity_a = generate_identity(&[7u8; 32]);
        let identity_b = generate_identity(&[8u8; 32]);
        let mut alice = SessionManager::new(Box::new(SodiumCryptoProvider), identity_a);
        let mut bob = SessionManager::new(Box::new(SodiumCryptoProvider), identity_b);

        bob.key_bundle(); // Bob bootstraps at epoch 0
        bob.advance_epoch();
        bob.advance_epoch();
        let bob_key_before = bob.epoch_key();

        // Alice is behind (still at epoch 0 from her own bootstrap) --
        // Bob receiving Alice's bundle must NOT regress backward.
        let alice_bundle = alice.key_bundle();
        bob.register_peer_keys(&[2u8; 8], &alice_bundle);

        assert_eq!(bob.epoch_key(), bob_key_before, "Bob must not regress to a peer's older epoch");
    }
}