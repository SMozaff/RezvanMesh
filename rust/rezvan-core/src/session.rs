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

use std::collections::HashMap;

use rezvan_common::NodeId;
use rezvan_crypto::{CryptoProvider, IdentityKeypair};
use vodozemac::olm::{Account, OlmMessage, Session, SessionConfig};
use vodozemac::Curve25519PublicKey;

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
    /// How many one-time keys from the current generated batch have already
    /// been advertised via `key_bundle()`. See that function's docs (security
    /// audit finding #9: one-time keys were previously advertised forever
    /// without rotation).
    otk_advertise_index: usize,
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
            otk_advertise_index: 0,
        }
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
    /// One-time-key hygiene (security audit finding #9): the original version
    /// of this function always returned `one_time_keys().values().next()`
    /// and never called `mark_keys_as_published()`. With no server-side
    /// consumption of these keys from our own account state, that meant the
    /// exact same OTK was advertised in every KeyAnnouncement forever --
    /// defeating the forward-secrecy purpose of a one-time prekey (if that
    /// key is ever compromised, it compromises every session anyone ever
    /// established using it, not just one).
    ///
    /// Fixed by rotating through the generated batch positionally
    /// (`otk_advertise_index` counts how many of the current 20-key batch
    /// we've handed out) instead of always taking the first key. Once the
    /// whole batch has been advertised, mark it published (vodozemac drops
    /// it from `one_time_keys()`) and generate a fresh batch. This avoids
    /// both the original bug (same key forever) and the simpler alternative
    /// of calling `mark_keys_as_published()` on every call, which would
    /// discard the other 19 freshly-generated-but-unused keys each cycle for
    /// no reason.
    ///
    /// Caveat: this assumes `one_time_keys()`'s iteration order is stable
    /// across calls between mutations (true for `HashMap`/`BTreeMap`-backed
    /// stores in practice, since nothing here reorders or resizes the
    /// collection between calls). It's "advertise each key in the batch
    /// roughly once before rotating," not a cryptographically-enforced
    /// exact-once guarantee -- if that stronger guarantee is ever needed,
    /// switch to an explicit per-key advertised/unadvertised set once
    /// vodozemac's key-ID API is confirmed (not done here to avoid guessing
    /// at that API's exact shape without access to its docs).
    pub fn key_bundle(&mut self) -> Vec<u8> {
        if self.account.one_time_keys().is_empty() {
            self.account.generate_one_time_keys(20);
            self.otk_advertise_index = 0;
        }

        let olm_identity = *self.account.curve25519_key().as_bytes();

        let batch_len = self.account.one_time_keys().len();
        if self.otk_advertise_index >= batch_len {
            // Whole batch advertised at least once already -- rotate.
            self.account.mark_keys_as_published();
            self.account.generate_one_time_keys(20);
            self.otk_advertise_index = 0;
        }

        let one_time = self
            .account
            .one_time_keys()
            .values()
            .nth(self.otk_advertise_index)
            .map(|k| *k.as_bytes())
            .unwrap_or([0u8; 32]);
        self.otk_advertise_index += 1;

        let mut out = Vec::with_capacity(128);
        out.extend_from_slice(&olm_identity);
        out.extend_from_slice(&one_time);
        out.extend_from_slice(&self.identity.public_x25519);
        out.extend_from_slice(&self.identity.public_ed25519);
        out
    }

    /// Store a peer's advertised bundle: olm_identity(32) ++ one_time(32) ++
    /// x25519_identity(32) ++ ed25519_identity(32) = 128 bytes.
    pub fn register_peer_keys(&mut self, peer: &NodeId, bundle: &[u8]) -> bool {
        if bundle.len() < 128 {
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

        assert_eq!(bundle1.len(), 128);
        assert_eq!(bundle2.len(), 128);
        // Olm identity key (bytes 0..32) and mesh identity keys (64..128)
        // must stay the same across calls -- only the OTK (32..64) rotates.
        assert_eq!(&bundle1[0..32], &bundle2[0..32], "olm identity key must be stable");
        assert_eq!(&bundle1[64..128], &bundle2[64..128], "mesh identity keys must be stable");
        assert_ne!(
            &bundle1[32..64], &bundle2[32..64],
            "one-time key must rotate between calls, not repeat forever (finding #9)"
        );
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
}