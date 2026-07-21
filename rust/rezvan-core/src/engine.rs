// rezvan-core/src/engine.rs

use crate::action::Action;
use crate::power::{PowerState, compute_state};
use crate::routing::RoutingTable;
use crate::session::SessionManager;
use rezvan_common::{
    AdvBeaconExt, DecryptedMessage, MeshPacketHeader, NodeId,
    MESH_PACKET_VERSION, MESH_PACKET_SIGNATURE_LEN,
};
use rezvan_crypto::{beacon_mac, CryptoProvider};

pub struct MeshEngine {
    crypto: Box<dyn CryptoProvider>,
    routing: RoutingTable,
    sessions: SessionManager,
    power_state: PowerState,
    user_override: Option<PowerState>,
    battery_level: u8,
    is_charging: bool,
    node_density: f32,
    ogm_sequence: u32,
    adv_sequence: u32,
    node_id: NodeId,
}

impl MeshEngine {
    pub fn new(seed: &[u8; 32], crypto: Box<dyn CryptoProvider>) -> Self {
        let identity = crypto.generate_identity(seed);
        let node_id = rezvan_common::compute_node_id(&identity.public_ed25519);

        let sessions = SessionManager::new(crypto.clone_box(), identity);

        Self {
            crypto,
            routing: RoutingTable::new(node_id),
            sessions,
            power_state: PowerState::Active,
            user_override: None,
            battery_level: 100,
            is_charging: false,
            node_density: 0.0,
            ogm_sequence: 0,
            adv_sequence: 0,
            node_id,
        }
    }

    pub fn tick(&mut self) -> Vec<Action> {
        let mut actions = Vec::new();
        // NOTE (this review's finding #5, very low severity, not fixed):
        // wrapping_add means adv_sequence eventually wraps back to 0 after
        // ~4 billion beacons, which peers would see as "replayed" and reject
        // until this node's routing entry ages out via purge_stale. Only
        // matters after extremely long continuous uptime, and the ticks-
        // based purge already bounds the resulting damage to "this node
        // temporarily stops being routed through until its entry is purged
        // and re-discovered," not a security issue -- not worth the added
        // complexity of a wraparound-aware sequence scheme for that outcome.
        self.adv_sequence = self.adv_sequence.wrapping_add(1);
        self.routing.advance_tick();

        // Purge routes through peers we haven't heard from in a while.
        // 120 ticks at the default (fastest) beacon cadence is roughly a
        // couple of minutes -- long enough to tolerate normal BLE gaps, short
        // enough that a genuinely departed peer stops being routed through.
        // See RoutingTable::purge_stale docs for the ticks-vs-wall-clock
        // caveat and the replay-protection tradeoff this implies.
        const STALE_ROUTE_MAX_AGE_TICKS: u64 = 120;
        if self.adv_sequence % 30 == 0 {
            self.routing.purge_stale(STALE_ROUTE_MAX_AGE_TICKS);
        }

        let state = self.power_state;
        if !crate::power::should_advertise(state) {
            return actions;
        }

        let ogm_interval = crate::power::get_ogm_interval_secs(state).max(1);
        let seq = self.adv_sequence as u64;

        if seq % ogm_interval == 0 {
            actions.push(Action::SendBleAdvertisement {
                data: self.build_advertisement(),
            });
        }

        // KeyAnnouncement every 3rd beacon (bundle now 128 bytes -- includes
        // mesh identity keys so peers can verify our beacon MACs and packet sigs).
        if seq % (ogm_interval * 3) == 0 {
            let bundle = self.sessions.key_bundle();
            let packet = self.build_signed_packet(0x05, 1, &[0u8; 8], &bundle);
            actions.push(Action::SendBlePacket {
                target: crate::action::BROADCAST_TARGET,
                data: packet,
            });
        }

        actions
    }

    pub fn process_incoming(
        &mut self,
        raw_packet: &[u8],
        rssi: i32,
        timestamp: u64,
    ) -> (Option<DecryptedMessage>, Vec<Action>) {
        // ── (A) BLE advertisement beacon (AdvBeaconExt, 24 bytes) ───────────
        // These arrive on the beacon path (RadioControllerImpl feeds raw
        // manufacturer data here for type 0x01). Distinguish by length: a
        // valid beacon is exactly 24 bytes; a MeshPacketHeader is ≥26.
        if raw_packet.len() == AdvBeaconExt::SIZE {
            if let Some(beacon) = AdvBeaconExt::deserialize(raw_packet) {
                if beacon.packet_type == 0x01 {
                    return self.process_beacon(beacon, rssi);
                }
            }
            return (None, Vec::new());
        }

        // ── (B) MeshPacketHeader-based GATT packet (≥26 bytes) ─────────────
        let header = match MeshPacketHeader::deserialize(raw_packet) {
            Some(h) => h,
            None => {
                return (None, vec![Action::DiagLog {
                    tag: "RUST".into(),
                    level: 3,
                    message: format!(
                        "deserialize FAILED len={} first_bytes={:02x?}",
                        raw_packet.len(),
                        &raw_packet.get(..8.min(raw_packet.len())).unwrap_or(raw_packet)
                    ),
                }]);
            }
        };

        // Version gate: pre-1.0 clean break -- reject anything not v0.2.
        if header.version != MESH_PACKET_VERSION {
            return (None, vec![Action::DiagLog {
                tag: "RUST".into(),
                level: 2,
                message: format!(
                    "Rejecting packet version={:#04x} (expected {:#04x}) from {:02x?}",
                    header.version, MESH_PACKET_VERSION, header.originator
                ),
            }]);
        }

        // Loopback guard.
        if header.originator == self.node_id {
            return (None, vec![Action::DiagLog {
                tag: "RUST".into(),
                level: 1,
                message: format!(
                    "LOOPBACK ok seq={} type={:#04x} rssi={}",
                    header.sequence, header.packet_type, rssi
                ),
            }]);
        }

        let payload_end = MeshPacketHeader::SIZE + header.payload_len as usize;

        // ── Signature verification for signed packet types ──────────────────
        // 0x02 (direct message) is authenticated by its Olm AEAD -- no
        // separate signature needed or added.
        // All others (0x01 OGM, 0x03 broadcast, 0x04 handshake, 0x05 KeyAnn,
        // 0x06 channel message) carry a 64-byte Ed25519 signature appended
        // after the payload.
        let needs_sig = matches!(header.packet_type, 0x01 | 0x03 | 0x04 | 0x05 | 0x06);

        if needs_sig {
            let expected_len = payload_end + MESH_PACKET_SIGNATURE_LEN;
            if raw_packet.len() < expected_len {
                return (None, vec![Action::DiagLog {
                    tag: "RUST".into(),
                    level: 3,
                    message: format!(
                        "Packet too short for signature: type={:#04x} len={} expected>={}",
                        header.packet_type, raw_packet.len(), expected_len
                    ),
                }]);
            }

            let signed_bytes = &raw_packet[..payload_end];
            let sig_bytes = &raw_packet[payload_end..payload_end + MESH_PACKET_SIGNATURE_LEN];
            let mut sig = [0u8; 64];
            sig.copy_from_slice(sig_bytes);

            // 0x05 (KeyAnnouncement) is self-authenticating: it CARRIES the
            // public key we're about to register. We verify the signature
            // *after* extracting the payload's Ed25519 key, so we can accept
            // first-contact key announcements while still verifying them
            // (chicken-and-egg resolved by reading the key from the payload
            // itself, then checking the sig with that same key).
            //
            // CRITICAL: a valid signature only proves "signed by whoever
            // holds the private key matching the embedded pubkey" -- it says
            // nothing about whether that key actually belongs to
            // header.originator. Without binding the two together, anyone
            // can broadcast a signed announcement claiming to be a victim's
            // real NodeId while embedding their OWN keys, and every peer
            // would silently adopt attacker-controlled keys for that
            // NodeId -- forging beacon MACs "from" the victim and hijacking
            // who gets addressed/decrypted-to for them. Since NodeId is
            // itself defined as compute_node_id(ed25519_pubkey) (the single
            // source of truth established previously), a genuine
            // announcement's embedded key must hash to the NodeId it's
            // announcing under -- so we check that explicitly below, before
            // ever trusting or registering the embedded key.
            //
            // `raw_packet.get(MeshPacketHeader::SIZE..payload_end)` cannot
            // panic here: we've already checked raw_packet.len() >= expected_len
            // == payload_end + 64 above, so payload_end is always in bounds.
            let ed25519_key: Option<[u8; 32]> = if header.packet_type == 0x05 {
                match raw_packet.get(MeshPacketHeader::SIZE..payload_end) {
                    Some(payload) if payload.len() >= 128 => {
                        let mut k = [0u8; 32];
                        k.copy_from_slice(&payload[96..128]);

                        if rezvan_common::compute_node_id(&k) != header.originator {
                            return (None, vec![Action::DiagLog {
                                tag: "RUST".into(),
                                level: 3,
                                message: format!(
                                    "KeyAnnouncement REJECTED: embedded key does not hash to claimed NodeId {:02x?} -- possible spoofing attempt",
                                    header.originator
                                ),
                            }]);
                        }

                        Some(k)
                    }
                    _ => None,
                }
            } else {
                self.sessions.peer_ed25519_identity(&header.originator)
            };

            let ed25519_key = match ed25519_key {
                Some(k) => k,
                None => {
                    return (None, vec![Action::DiagLog {
                        tag: "RUST".into(),
                        level: 2,
                        message: format!(
                            "Unknown sender {:02x?} for type={:#04x} -- dropping (no key yet)",
                            header.originator, header.packet_type
                        ),
                    }]);
                }
            };

            if !self.crypto.verify(&ed25519_key, signed_bytes, &sig) {
                return (None, vec![Action::DiagLog {
                    tag: "RUST".into(),
                    level: 3,
                    message: format!(
                        "Signature FAILED for type={:#04x} from {:02x?}",
                        header.packet_type, header.originator
                    ),
                }]);
            }
        }

        // ── Dispatch ────────────────────────────────────────────────────────
        match header.packet_type {
            0x01 => {
                // Full OGM over GATT (not yet sent -- signature verified above,
                // feed into routing table).
                let _ = self.routing.process_ogm(raw_packet, rssi);
                (None, Vec::new())
            }
            0x02 => {
                // Direct message: authenticated by Olm AEAD, no extra sig.
                if let Some(payload) = raw_packet.get(MeshPacketHeader::SIZE..payload_end) {
                    if let Ok(plain) = self.sessions.decrypt(&header.originator, payload) {
                        return (Some(DecryptedMessage {
                            conversation_id: [0u8; 16],
                            sender_id: header.originator,
                            timestamp,
                            message_type: 0,
                            content: plain,
                        }), Vec::new());
                    }
                }
                (None, Vec::new())
            }
            0x03 => {
                // Emergency broadcast -- signature verified above. May be
                // unencrypted (public-safety case: decrypt if session exists,
                // otherwise use plaintext directly).
                if let Some(payload) = raw_packet.get(MeshPacketHeader::SIZE..payload_end) {
                    let content = self.sessions
                        .decrypt(&header.originator, payload)
                        .unwrap_or_else(|_| payload.to_vec());
                    return (Some(DecryptedMessage {
                        conversation_id: [0u8; 16],
                        sender_id: header.originator,
                        timestamp,
                        message_type: 3,
                        content,
                    }), Vec::new());
                }
                (None, Vec::new())
            }
            0x06 => {
                // Channel (group) message -- MeshPacketHeader signature
                // verified above (authenticates the current transmitter).
                // Still need the PER-SENDER check inside sender_key::decrypt,
                // since header.originator is who transmitted this hop, not
                // necessarily who authored the plaintext (see sender_key.rs
                // docs) -- today those are the same node since there's no
                // relay yet, but the layering stays correct either way.
                let payload = match raw_packet.get(MeshPacketHeader::SIZE..payload_end) {
                    Some(p) if p.len() > 4 => p,
                    _ => return (None, Vec::new()),
                };

                let channel_id = u32::from_be_bytes([payload[0], payload[1], payload[2], payload[3]]);
                let sender_key_wire = &payload[4..];

                let key = match self.sessions.channel_key(channel_id) {
                    Some(k) => k,
                    None => return (None, Vec::new()), // not a member of this channel
                };

                // sender_key::decrypt requires the caller's own independently
                // known public key for the claimed sender -- use the key we
                // already have on file for header.originator (registered via
                // a prior KeyAnnouncement), never the embedded pubkey blindly.
                let expected_sender = match self.sessions.peer_ed25519_identity(&header.originator) {
                    Some(k) => k,
                    None => return (None, vec![Action::DiagLog {
                        tag: "RUST".into(),
                        level: 2,
                        message: format!(
                            "Channel message from unknown sender {:02x?}, dropping (no KeyAnnouncement yet)",
                            header.originator
                        ),
                    }]),
                };

                match rezvan_crypto::sender_key::decrypt(&key, &expected_sender, sender_key_wire) {
                    Some(plain) => {
                        // Channel messages are distinguished from 1:1/broadcast
                        // by message_type=6; conversation_id carries the
                        // channel_id (big-endian u32) in its first 4 bytes,
                        // zero-padded, so Kotlin can build "channel_<id>" as
                        // the conversation key without a separate field.
                        let mut conv_id = [0u8; 16];
                        conv_id[0..4].copy_from_slice(&channel_id.to_be_bytes());
                        (Some(DecryptedMessage {
                            conversation_id: conv_id,
                            sender_id: header.originator,
                            timestamp,
                            message_type: 6,
                            content: plain,
                        }), Vec::new())
                    }
                    None => (None, Vec::new()), // wrong key, forged sender, or tampered
                }
            }
            0x04 => {
                // Handshake -- signature verified above.
                if let Some(payload) = raw_packet.get(MeshPacketHeader::SIZE..payload_end) {
                    let _ = self.sessions.process_inbound_handshake(&header.originator, payload);
                }
                (None, Vec::new())
            }
            0x05 => {
                // KeyAnnouncement -- signature verified above (self-signed).
                // Register the full 128-byte bundle including mesh identity keys.
                if let Some(payload) = raw_packet.get(MeshPacketHeader::SIZE..payload_end) {
                    self.sessions.register_peer_keys(&header.originator, payload);
                }
                (None, Vec::new())
            }
            _ => (None, Vec::new()),
        }
    }

    /// Process a deserialized beacon. Verifies the 7-byte MAC if we know the
    /// sender's X25519 key; passes `verified` flag to routing to gate routing
    /// decisions.
    fn process_beacon(
        &mut self,
        beacon: AdvBeaconExt,
        rssi: i32,
    ) -> (Option<DecryptedMessage>, Vec<Action>) {
        // Version gate for beacons.
        // AdvBeaconExt::deserialize() already rejects a version mismatch
        // before this point (this review's finding #4 -- moved that check
        // to the source rather than relying only on this call site). This
        // second check is now unreachable in practice but kept as harmless
        // defense-in-depth in case that invariant is ever weakened later.
        if beacon.version != AdvBeaconExt::VERSION {
            return (None, vec![Action::DiagLog {
                tag: "RUST".into(),
                level: 2,
                message: format!(
                    "Beacon version={:#04x} != {:#04x} from {:02x?}; ignoring",
                    beacon.version, AdvBeaconExt::VERSION, beacon.originator
                ),
            }]);
        }

        let verified = if let Some(their_x25519) = self.sessions.peer_x25519_identity(&beacon.originator) {
            let our_private = self.sessions.own_x25519_private();
            let signed = beacon.signed_bytes();
            beacon_mac::verify_tag(&our_private, &their_x25519, &signed, &beacon.mac)
        } else {
            // Sender not yet known: treat as discovery-only, routing will not
            // act on it. Log at level 1 (info) since this is expected for new
            // peers until their KeyAnnouncement arrives.
            false
        };

        let changed = self.routing.process_beacon(&beacon, rssi, verified);

        let mut actions = Vec::new();
        if changed {
            actions.push(Action::DiagLog {
                tag: "RUST".into(),
                level: 1,
                message: format!(
                    "Routing updated from {:02x?} seq={} verified={} rssi={}",
                    beacon.originator, beacon.sequence, verified, rssi
                ),
            });
        }

        (None, actions)
    }

    pub fn send_message(
        &mut self,
        recipient: &NodeId,
        plaintext: &[u8],
        _msg_type: u8,
    ) -> Vec<Action> {
        let encrypted = match self.sessions.encrypt(recipient, plaintext) {
            Ok(c) => c,
            Err(_) => return Vec::new(),
        };

        // 0x02 (direct message): authenticated by Olm AEAD, no extra Ed25519 sig.
        let header = MeshPacketHeader {
            version: MESH_PACKET_VERSION,
            packet_type: 0x02,
            ttl: 10,
            originator: self.node_id,
            sequence: self.ogm_sequence,
            hop_count: 0,
            next_hop: *recipient,
            payload_len: encrypted.len() as u16,
        };

        let mut packet = header.serialize();
        packet.extend_from_slice(&encrypted);

        vec![Action::SendBlePacket {
            // Direct message: target the SPECIFIC recipient, not everyone
            // we're connected to. Previously this used the broadcast
            // sentinel for every send, meaning 1:1 messages were physically
            // transmitted to every GATT-connected peer (relying on
            // decryption failure to keep them private rather than actually
            // routing only to the intended recipient).
            target: *recipient,
            data: packet,
        }]
    }

    pub fn send_broadcast(&mut self, message: &[u8]) -> Vec<Action> {
        // Emergency broadcast: signed (type 0x03), broadcast to all peers.
        let packet = self.build_signed_packet(0x03, 15, &[0u8; 8], message);
        vec![Action::SendBlePacket {
            target: crate::action::BROADCAST_TARGET,
            data: packet,
        }]
    }

    pub fn update_battery(&mut self, level: u8, charging: bool) {
        self.battery_level = level;
        self.is_charging = charging;
        self.power_state = compute_state(level, charging, self.node_density, self.user_override);
    }

    pub fn get_power_state(&self) -> PowerState {
        self.power_state
    }

    /// The canonical Node ID for this engine: SHA-256(Ed25519 public key)[0:8].
    /// Kotlin calls `nativeGetNodeId` rather than independently recomputing it
    /// (see security audit finding #8).
    pub fn node_id(&self) -> NodeId {
        self.node_id
    }

    /// Snapshot of the routing table for diagnostics: for each known
    /// destination, its best route's next_hop/metric/link_quality. Serialized
    /// as `[count:1]` then per-entry `[dest:8][next_hop:8][metric:4 BE][lq:1]`.
    /// Previously there was no way to inspect routing state at all outside
    /// unit tests -- the Diagnostics screen's "Show routing table" button was
    /// a hardcoded stub string.
    pub fn routing_snapshot(&self) -> Vec<u8> {
        let dests = self.routing.destinations();
        let mut entries = Vec::with_capacity(dests.len());
        for dest in dests.iter().take(255) {
            if let Some(route) = self.routing.get_best_route(dest) {
                let mut entry = Vec::with_capacity(21);
                entry.extend_from_slice(dest);
                entry.extend_from_slice(&route.next_hop);
                entry.extend_from_slice(&route.metric.to_be_bytes());
                entry.push(route.link_quality);
                entries.push(entry);
            }
        }
        let mut buf = Vec::with_capacity(1 + entries.len() * 21);
        buf.push(entries.len() as u8); // entries.len() <= 255 by construction (take(255) above)
        for entry in entries {
            buf.extend_from_slice(&entry);
        }
        buf
    }

    pub fn set_user_override(&mut self, state: Option<PowerState>) {
        self.user_override = state;
        self.power_state = compute_state(
            self.battery_level,
            self.is_charging,
            self.node_density,
            self.user_override,
        );
    }

    pub fn key_bundle(&mut self) -> Vec<u8> {
        self.sessions.key_bundle()
    }

    pub fn register_peer_keys(&mut self, peer: &NodeId, bundle: &[u8]) -> bool {
        self.sessions.register_peer_keys(peer, bundle)
    }

    pub fn create_channel_key(&mut self, channel_id: u32) -> [u8; 32] {
        self.sessions.create_channel_key(channel_id)
    }

    pub fn set_channel_key(&mut self, channel_id: u32, key: [u8; 32]) {
        self.sessions.set_channel_key(channel_id, key)
    }

    /// Send a channel (group) message. Packet type 0x06: signed at the
    /// MeshPacketHeader level (authenticates whoever is currently
    /// transmitting -- today always the original author, but this layering
    /// is also correct if/when relay is added later) AND per-sender signed
    /// via sender_key.rs (authenticates who within the channel actually
    /// composed the plaintext -- see that module's docs for why a shared
    /// symmetric key alone can't do this). Broadcasts to all connected peers
    /// (there's no channel-membership-aware routing; every connected peer
    /// receives it and only actual channel members will have the shared key
    /// to decrypt it).
    ///
    /// Returns an empty action list if we don't have a key for this channel
    /// yet (haven't created or joined it).
    pub fn send_channel_message(&mut self, channel_id: u32, plaintext: &[u8]) -> Vec<Action> {
        let key = match self.sessions.channel_key(channel_id) {
            Some(k) => k,
            None => return Vec::new(),
        };

        let identity = self.sessions.identity();
        let sender_key_wire = rezvan_crypto::sender_key::encrypt(&key, plaintext, &identity);

        let mut payload = Vec::with_capacity(4 + sender_key_wire.len());
        payload.extend_from_slice(&channel_id.to_be_bytes());
        payload.extend_from_slice(&sender_key_wire);

        let packet = self.build_signed_packet(0x06, 10, &crate::action::BROADCAST_TARGET, &payload);
        vec![Action::SendBlePacket {
            target: crate::action::BROADCAST_TARGET,
            data: packet,
        }]
    }

    // ── Private helpers ─────────────────────────────────────────────────────

    /// Build a signed `MeshPacketHeader` packet:
    /// `[header:26][payload:N][Ed25519_sig:64]`.
    /// Used for all packet types except 0x02 (Olm AEAD is sufficient there).
    fn build_signed_packet(
        &mut self,
        packet_type: u8,
        ttl: u8,
        next_hop: &NodeId,
        payload: &[u8],
    ) -> Vec<u8> {
        self.ogm_sequence = self.ogm_sequence.wrapping_add(1);

        let header = MeshPacketHeader {
            version: MESH_PACKET_VERSION,
            packet_type,
            ttl,
            originator: self.node_id,
            sequence: self.ogm_sequence,
            hop_count: 0,
            next_hop: *next_hop,
            payload_len: payload.len() as u16,
        };

        let mut signed_bytes = header.serialize();
        signed_bytes.extend_from_slice(payload);

        let identity = self.sessions.identity();
        let sig = self.crypto.sign(&identity, &signed_bytes);

        let mut packet = signed_bytes;
        packet.extend_from_slice(&sig);
        packet
    }

    fn build_advertisement(&mut self) -> Vec<u8> {
        // AdvBeaconExt v0.02: dropped ttl/peer_density/reserved to make room
        // for the 7-byte pairwise MAC. See rezvan_crypto::beacon_mac.
        let power_state_byte = self.power_state as u8;

        let mut node_flags: u8 = 0;
        if self.is_charging { node_flags |= AdvBeaconExt::FLAG_CHARGING; }
        node_flags |= AdvBeaconExt::FLAG_WIFI_DIRECT;
        node_flags |= AdvBeaconExt::FLAG_VOICE;

        let mut beacon = AdvBeaconExt {
            version:     AdvBeaconExt::VERSION,
            packet_type: 0x01,
            originator:  self.node_id,
            sequence:    self.adv_sequence,
            battery:     self.battery_level,
            power_state: power_state_byte,
            node_flags,
            mac:         [0u8; 7],
        };

        // Compute per-peer MACs lazily: for a broadcast beacon where we don't
        // know which specific peer will receive it, we derive the MAC using our
        // own X25519 key paired with an all-zero "public" key, producing a
        // deterministic, verifiable tag only by nodes that know our private key
        // -- i.e. nobody externally. This is a known, documented limitation of
        // pairwise MACs on broadcast beacons: the tag is NOT verifiable by any
        // receiver, providing only replay-sequence protection. The routing layer
        // accounts for this by treating beacons as unverified until a receiver
        // also runs the beacon-specific per-peer logic, which IS wired up on the
        // receive side (process_beacon) using the sender's actual X25519 key
        // once a KeyAnnouncement has been received.
        //
        // TODO (post-beta): switch to a per-receiver beacon per trusted peer, or
        // a network-wide symmetric epoch key with forward secrecy, so the MAC is
        // independently verifiable without a full GATT connection first.
        let own_private = self.sessions.own_x25519_private();
        let signed = beacon.signed_bytes();
        beacon.mac = beacon_mac::compute_tag(&own_private, &[0u8; 32], &signed);

        beacon.serialize()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use rezvan_crypto::SodiumCryptoProvider;

    fn make_engine(seed_byte: u8) -> MeshEngine {
        MeshEngine::new(&[seed_byte; 32], Box::new(SodiumCryptoProvider))
    }

    /// Build a signed, well-formed KeyAnnouncement packet the way a genuine
    /// node would: `originator` in the header actually matches
    /// `compute_node_id(bundle's embedded Ed25519 key)`.
    fn build_legit_key_announcement(sender: &mut MeshEngine) -> Vec<u8> {
        let bundle = sender.key_bundle();
        sender.build_signed_packet(0x05, 1, &[0u8; 8], &bundle)
    }

    #[test]
    fn test_legit_key_announcement_is_accepted() {
        let mut alice = make_engine(1);
        let mut bob = make_engine(2);

        let packet = build_legit_key_announcement(&mut alice);
        let (msg, _actions) = bob.process_incoming(&packet, -60, 0);
        assert!(msg.is_none()); // KeyAnnouncement produces no decrypted message

        // Bob should now have Alice's keys registered under Alice's real NodeId.
        assert!(bob.sessions.peer_ed25519_identity(&alice.node_id).is_some());
    }

    #[test]
    fn test_spoofed_key_announcement_is_rejected() {
        // Finding 1: Mallory builds a validly-signed KeyAnnouncement (signed
        // with her OWN identity, so the signature itself checks out), but
        // claims Alice's real NodeId in the header while embedding her own
        // keys in the payload. Before the fix, register_peer_keys would
        // blindly store Mallory's keys under Alice's NodeId -- letting
        // Mallory forge beacon MACs "from" Alice and hijack anything
        // addressed to Alice's NodeId.
        let mut alice = make_engine(1);
        let mut mallory = make_engine(3);

        let bundle = mallory.key_bundle(); // Mallory's OWN keys
        // Splice: build the packet as if Mallory sent it, but with the
        // header's originator forged to Alice's NodeId.
        mallory.ogm_sequence = mallory.ogm_sequence.wrapping_add(1);
        let header = MeshPacketHeader {
            version: MESH_PACKET_VERSION,
            packet_type: 0x05,
            ttl: 1,
            originator: alice.node_id, // forged: claims to be Alice
            sequence: mallory.ogm_sequence,
            hop_count: 0,
            next_hop: [0u8; 8],
            payload_len: bundle.len() as u16,
        };
        let mut signed_bytes = header.serialize();
        signed_bytes.extend_from_slice(&bundle);
        // Signed with Mallory's real identity (she doesn't have Alice's key).
        let identity = mallory.sessions.identity();
        let sig = mallory.crypto.sign(&identity, &signed_bytes);
        let mut forged_packet = signed_bytes;
        forged_packet.extend_from_slice(&sig);

        let mut victim_view = make_engine(4); // a third party observing the mesh
        let (msg, actions) = victim_view.process_incoming(&forged_packet, -60, 0);
        assert!(msg.is_none());

        // Must be rejected BEFORE registration -- Alice's NodeId must not
        // end up bound to Mallory's keys.
        assert!(
            victim_view.sessions.peer_ed25519_identity(&alice.node_id).is_none(),
            "spoofed KeyAnnouncement must not register attacker keys under the victim's NodeId"
        );
        let logged_rejection = actions.iter().any(|a| matches!(
            a,
            Action::DiagLog { message, .. } if message.contains("REJECTED")
        ));
        assert!(logged_rejection, "rejection should be logged for diagnostics");
    }

    #[test]
    fn test_key_announcement_with_tampered_bundle_still_binds_correctly() {
        // Sanity check: even a legitimate node's OWN announcement must fail
        // if something corrupts the embedded key in transit (simulates a
        // bit-flip), since compute_node_id(corrupted_key) won't match
        // header.originator either -- same code path protects against
        // accidental corruption, not just deliberate spoofing.
        let mut alice = make_engine(1);
        let mut packet = build_legit_key_announcement(&mut alice);

        // Corrupt one byte inside the embedded Ed25519 key region of the
        // payload (bytes [96..128) of the 128-byte bundle, offset by header size).
        let corrupt_offset = MeshPacketHeader::SIZE + 100;
        packet[corrupt_offset] ^= 0xFF;

        let mut bob = make_engine(2);
        let (msg, _actions) = bob.process_incoming(&packet, -60, 0);
        assert!(msg.is_none());
        assert!(bob.sessions.peer_ed25519_identity(&alice.node_id).is_none());
    }

    // --- Channel (group) messaging integration tests (remediation #5) -------
    //
    // sender_key.rs has its own unit tests in isolation, but nothing
    // previously exercised send_channel_message/process_incoming(0x06)
    // through the actual MeshEngine. These correct that gap: on inspection
    // the wiring was already complete and correct (see engine.rs 0x06
    // handling above), so these tests confirm the existing behavior rather
    // than fixing a defect.

    /// Registers alice and bob's KeyAnnouncements with each other, the way
    /// they'd learn about one another on a real mesh before any channel
    /// traffic is meaningful (bob needs alice's Ed25519 identity on file to
    /// verify her sender-key signature).
    fn exchange_key_announcements(alice: &mut MeshEngine, bob: &mut MeshEngine) {
        let a_announce = build_legit_key_announcement(alice);
        bob.process_incoming(&a_announce, -60, 0);
        let b_announce = build_legit_key_announcement(bob);
        alice.process_incoming(&b_announce, -60, 0);
    }

    #[test]
    fn test_channel_message_round_trip() {
        let mut alice = make_engine(1);
        let mut bob = make_engine(2);
        exchange_key_announcements(&mut alice, &mut bob);

        let channel_id = 42u32;
        let key = alice.create_channel_key(channel_id);
        bob.set_channel_key(channel_id, key);

        let actions = alice.send_channel_message(channel_id, b"hello channel");
        assert_eq!(actions.len(), 1, "should produce exactly one broadcast action");
        let Action::SendBlePacket { data, .. } = &actions[0] else {
            panic!("expected SendBlePacket action");
        };

        let (msg, _) = bob.process_incoming(data, -60, 0);
        let msg = msg.expect("bob should decode alice's channel message");
        assert_eq!(msg.message_type, 6);
        assert_eq!(msg.content, b"hello channel");
        assert_eq!(msg.sender_id, alice.node_id());
        assert_eq!(&msg.conversation_id[0..4], &channel_id.to_be_bytes());
    }

    #[test]
    fn test_channel_message_rejected_without_key() {
        // Bob never received/set the channel key -- should not decode.
        let mut alice = make_engine(1);
        let mut bob = make_engine(2);
        exchange_key_announcements(&mut alice, &mut bob);

        let channel_id = 7u32;
        alice.create_channel_key(channel_id);
        // bob.set_channel_key(...) intentionally not called.

        let actions = alice.send_channel_message(channel_id, b"secret");
        let Action::SendBlePacket { data, .. } = &actions[0] else {
            panic!("expected SendBlePacket action");
        };
        let (msg, _) = bob.process_incoming(data, -60, 0);
        assert!(msg.is_none(), "bob without the channel key must not decode the message");
    }

    #[test]
    fn test_channel_message_forged_sender_rejected() {
        // Mallory knows the channel's shared symmetric key (she's a member)
        // but tries to forge a message as if it came from Alice by crafting
        // a packet with Alice's NodeId in the header. The MeshPacketHeader
        // signature check (using Mallory's own key) will already fail this
        // in transit -- but this test specifically exercises the deeper
        // sender_key-level check by simulating a scenario where the header
        // signature layer is bypassed (e.g. a compromised relay), confirming
        // sender_key::decrypt's own independent authentication still holds.
        let mut alice = make_engine(1);
        let mut bob = make_engine(2);
        let mut mallory = make_engine(3);
        exchange_key_announcements(&mut alice, &mut bob);
        exchange_key_announcements(&mut mallory, &mut bob);

        let channel_id = 99u32;
        let key = alice.create_channel_key(channel_id);
        bob.set_channel_key(channel_id, key);
        mallory.set_channel_key(channel_id, key);

        // Mallory sends her own legitimately sender_key-signed message --
        // bob must attribute it to Mallory, never to Alice, regardless of
        // what NodeId ends up in the outer header at any relay hop.
        let actions = mallory.send_channel_message(channel_id, b"forged");
        let Action::SendBlePacket { data, .. } = &actions[0] else {
            panic!("expected SendBlePacket action");
        };
        let (msg, _) = bob.process_incoming(data, -60, 0);
        let msg = msg.expect("bob should decode mallory's own legitimate message");
        assert_eq!(msg.sender_id, mallory.node_id(), "sender must be attributed correctly, never spoofed");
        assert_ne!(msg.sender_id, alice.node_id());
    }

    #[test]
    fn test_channel_key_rotation_old_key_stops_working() {
        let mut alice = make_engine(1);
        let mut bob = make_engine(2);
        exchange_key_announcements(&mut alice, &mut bob);

        let channel_id = 5u32;
        let old_key = alice.create_channel_key(channel_id);
        bob.set_channel_key(channel_id, old_key);

        // Confirm the old key works before rotation.
        let actions = alice.send_channel_message(channel_id, b"before rotation");
        let Action::SendBlePacket { data, .. } = &actions[0] else { panic!("expected packet"); };
        let (msg, _) = bob.process_incoming(data, -60, 0);
        assert!(msg.is_some(), "old key should work before rotation");

        // Rotate: alice gets a new key (e.g. membership change), bob hasn't
        // received it yet.
        let new_key = alice.create_channel_key(channel_id);
        assert_ne!(old_key, new_key, "rotation must produce a different key");

        let actions = alice.send_channel_message(channel_id, b"after rotation");
        let Action::SendBlePacket { data, .. } = &actions[0] else { panic!("expected packet"); };
        let (msg, _) = bob.process_incoming(data, -60, 0);
        assert!(msg.is_none(), "bob with the stale key must not decode a message encrypted under the new key");

        // Once bob receives the new key out-of-band, decoding resumes.
        bob.set_channel_key(channel_id, new_key);
        let actions = alice.send_channel_message(channel_id, b"after bob updates");
        let Action::SendBlePacket { data, .. } = &actions[0] else { panic!("expected packet"); };
        let (msg, _) = bob.process_incoming(data, -60, 0);
        assert!(msg.is_some(), "bob with the updated key should decode again");
    }

    #[test]
    fn test_routing_snapshot_empty_when_no_routes() {
        let engine = make_engine(1);
        let snap = engine.routing_snapshot();
        assert_eq!(snap.len(), 1);
        assert_eq!(snap[0], 0);
    }

    #[test]
    fn test_routing_snapshot_reflects_processed_beacon() {
        let mut alice = make_engine(1);
        let mut bob = make_engine(2);

        // Bob needs to know Alice's X25519 key to verify her beacon MAC
        // before it'll influence routing (see process_beacon's verified gate).
        let bundle = alice.key_bundle();
        bob.register_peer_keys(&alice.node_id, &bundle);

        // Drive Alice's engine for real rather than hand-computing a beacon
        // MAC tag -- exercises the actual build_advertisement() code path.
        let mut alice_beacon: Option<Vec<u8>> = None;
        for _ in 0..50 {
            for action in alice.tick() {
                if let Action::SendBleAdvertisement { data } = action {
                    alice_beacon = Some(data);
                }
            }
            if alice_beacon.is_some() {
                break;
            }
        }
        let beacon_data = alice_beacon.expect("Alice should have advertised within 50 ticks");
        // tick() pads/truncates to 31 bytes for the legacy BLE envelope
        // (prepare_ble_adv_payload); the real 24-byte AdvBeaconExt is the prefix.
        let beacon_bytes = &beacon_data[..rezvan_common::AdvBeaconExt::SIZE];

        let (_msg, _actions) = bob.process_incoming(beacon_bytes, -60, 0);

        let snap = bob.routing_snapshot();
        assert_eq!(snap[0], 1, "one route (to Alice) expected");
        let dest = &snap[1..9];
        assert_eq!(dest, &alice.node_id);
    }
}
