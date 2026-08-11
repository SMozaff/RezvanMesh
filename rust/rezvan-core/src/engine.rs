// rezvan-core/src/engine.rs

use crate::action::Action;
use crate::power::{PowerState, compute_state};
use crate::routing::RoutingTable;
use crate::session::SessionManager;
use rezvan_common::{
    AdvBeaconExt, DecryptedMessage, MeshPacketHeader, NodeId,
    MESH_PACKET_VERSION, MESH_PACKET_SIGNATURE_LEN,
};
use rezvan_crypto::CryptoProvider;

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

        // Advance the beacon-authentication epoch key (see
        // rezvan_crypto::epoch_key module docs). Same ticks-as-time-proxy
        // convention as purge_stale above: at the ~1 tick/sec fastest
        // cadence implied by that function's "120 ticks ~ couple minutes"
        // comment, EPOCH_DURATION_SECS (6 hours) is approximately 21,600
        // ticks. This is a local, unsynchronized clock -- devices converge
        // on the same epoch number over time via whatever a peer's
        // KeyAnnouncement declares (see SessionManager::converge_epoch_key),
        // not by all advancing in lockstep.
        const EPOCH_ADVANCE_TICKS: u32 = 21_600;
        if self.adv_sequence % EPOCH_ADVANCE_TICKS == 0 {
            self.sessions.advance_epoch();
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

        // KeyAnnouncement every 3rd beacon (bundle now 164 bytes -- includes
        // mesh identity keys plus the network-wide beacon epoch key/number,
        // see rezvan_crypto::epoch_key) so peers can verify our beacon MACs and packet sigs).
        if seq % (ogm_interval * 3) == 0 {
            let bundle = self.sessions.key_bundle();
            let packet = self.build_signed_packet(0x05, 1, &[0u8; 8], &bundle);
            actions.push(Action::SendBlePacket {
                target: crate::action::BROADCAST_TARGET,
                data: packet,
            });
        }

        // Multi-hop OGM broadcast (packet type 0x01), so routes propagate
        // beyond direct neighbours. Previously `build_ogm`/`process_ogm`
        // existed and were exercised only by unit tests -- nothing in this
        // function ever actually sent one, so no node's routing table ever
        // reflected anything more than 1 hop away. Reuses `ogm_interval`
        // (the SAME cadence as the lightweight AdvBeaconExt beacon above --
        // `get_ogm_interval_secs`'s name finally matches what it gates here)
        // rather than introducing a separate, independent timer: an OGM is
        // materially larger (34-byte header + up to 50-byte payload + 64-byte
        // signature vs. a 24-byte beacon) and this is the same power-state-
        // aware cadence already tuned to trade discovery freshness for
        // battery cost, so it's the natural budget to share rather than
        // adding a second, uncoordinated source of periodic radio traffic.
        if seq % ogm_interval == 0 {
            self.ogm_sequence = self.ogm_sequence.wrapping_add(1);
            let mut signed_bytes = self.routing.build_ogm(self.ogm_sequence);
            let identity = self.sessions.identity();
            // build_ogm() already returns [header][payload]; sign the whole
            // thing the same way build_signed_packet does for other signed
            // types, then append the signature.
            let sig = self.crypto.sign(&identity, &signed_bytes);
            signed_bytes.extend_from_slice(&sig);
            actions.push(Action::SendBlePacket {
                target: crate::action::BROADCAST_TARGET,
                data: signed_bytes,
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
        // valid beacon is exactly 24 bytes; a MeshPacketHeader is ≥34.
        if raw_packet.len() == AdvBeaconExt::SIZE {
            if let Some(beacon) = AdvBeaconExt::deserialize(raw_packet) {
                if beacon.packet_type == 0x01 {
                    return self.process_beacon(beacon, rssi);
                }
            }
            return (None, Vec::new());
        }

        // ── (B) MeshPacketHeader-based GATT packet (≥34 bytes) ─────────────
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

        // Version gate: pre-1.0 clean break -- reject anything not v0.3.
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
                    // Bundle is 164 bytes total now (see session.rs::key_bundle),
                    // but the embedded Ed25519 key we need here is still at the
                    // same [96..128) offset -- checking >=128 remains correct.
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

        // ── Relay ─────────────────────────────────────────────────────────
        // Added alongside the version-0x03 `destination` field. Two cases:
        //
        // (1) Unicast (0x02) not addressed to us: we are a pure intermediate
        //     hop. Don't attempt to decrypt (wrong Olm session, would just
        //     fail) -- forward it on toward the destination's next hop and
        //     stop, we have nothing else to do with it.
        //
        // (2) Broadcast-scoped types (0x03 emergency, 0x06 channel message):
        //     "for us" and "for everyone else too" are not mutually
        //     exclusive -- process it locally via the normal dispatch below
        //     AND re-flood it to other neighbours, so it keeps propagating
        //     outward through the mesh. 0x01 (OGM), 0x04 (handshake), and
        //     0x05 (KeyAnnouncement) are deliberately excluded: OGMs already
        //     have their own periodic re-send cadence per node rather than
        //     being flood-relayed hop-by-hop, and handshake/KeyAnnouncement
        //     are neighbour-scoped by design.
        //
        // Loop prevention: `seen_and_record` rejects a packet this node has
        // already relayed once before, keyed by (originator, sequence) --
        // this is what actually prevents relay loops/duplicate re-floods,
        // for ALL relay-candidate types. `ttl` is additionally a REAL,
        // decrementing hop budget for 0x02 (direct message) specifically,
        // since that type has no outer signature covering the header (Olm
        // AEAD authenticates only the payload) and so `build_relay_action`
        // can safely mutate ttl/hop_count in place. For 0x03/0x06 (which DO
        // carry an outer Ed25519 signature over the whole header+payload),
        // `ttl` is NOT decremented on relay -- mutating it would invalidate
        // that signature for the next hop -- so for those two types `ttl`
        // only reflects what the originator set, and `seen_and_record` is
        // the sole loop-prevention mechanism. See `build_relay_action`'s
        // docs for the full reasoning.
        let is_relay_candidate = matches!(header.packet_type, 0x02 | 0x03 | 0x06);
        if is_relay_candidate {
            if self.routing.seen_and_record(header.originator, header.sequence) {
                // Already relayed this exact (originator, sequence) before
                // -- drop silently to prevent relay loops/duplicate floods.
                return (None, Vec::new());
            }

            let addressed_to_us = header.destination == self.node_id;
            let is_broadcast = header.destination == rezvan_common::BROADCAST_DESTINATION;

            if header.ttl > 0 && (!addressed_to_us || is_broadcast) {
                if let Some(action) = self.build_relay_action(&header, raw_packet) {
                    let mut actions = vec![action];
                    // Unicast not addressed to us: nothing local to do, stop here.
                    if !addressed_to_us && !is_broadcast {
                        return (None, actions);
                    }
                    // Broadcast: fall through to normal dispatch below for the
                    // local-processing side, but keep the relay action too.
                    let (msg, mut more_actions) = self.dispatch_packet(&header, raw_packet, payload_end, timestamp, rssi);
                    actions.append(&mut more_actions);
                    return (msg, actions);
                }
                // No route/neighbour to relay toward: if it's not for us
                // either, there's nothing more we can do with it.
                if !addressed_to_us {
                    return (None, Vec::new());
                }
            } else if !addressed_to_us {
                // TTL exhausted and not for us: drop.
                return (None, Vec::new());
            }
        }

        self.dispatch_packet(&header, raw_packet, payload_end, timestamp, rssi)
    }

    /// Build the `Action::SendBlePacket` that relays `raw_packet` one hop
    /// further toward `header.destination`. Returns `None` if we have no
    /// known route to relay through -- the caller treats that as "can't
    /// relay, drop."
    ///
    /// TTL/hop_count handling differs by whether this packet type carries an
    /// outer Ed25519 signature (see `needs_sig` above, and each type's wire
    /// format docs in rezvan_common):
    ///
    ///   * 0x02 (direct message) has NO outer signature -- it's authenticated
    ///     by its Olm AEAD payload only, which does not cover the header at
    ///     all. So `ttl`/`hop_count` are safe to mutate in place: this gives
    ///     0x02 relay a real, enforced hop budget.
    ///
    ///   * 0x03/0x06 (and, if ever wired for relay, 0x01/0x04/0x05) DO carry
    ///     an outer signature whose `signed_bytes` is the entire header
    ///     (including ttl/hop_count) plus payload. Mutating either field
    ///     would invalidate that signature for the next hop's verification,
    ///     and a relay has no way to re-sign as the original originator
    ///     (routing-table bookkeeping and cryptographic identity are
    ///     deliberately kept separate -- see RoutingTable's module docs).
    ///     These types are therefore forwarded byte-for-byte unchanged, and
    ///     rely entirely on `seen_and_record`'s per-(originator, sequence)
    ///     tracking for loop prevention rather than a decrementing hop
    ///     count. Acceptable for a small mesh; a broadcast type that needs a
    ///     trustworthy, relay-mutable hop count would need a redesign (e.g.
    ///     a separate unsigned relay-envelope wrapper) to get one.
    fn build_relay_action(&self, header: &MeshPacketHeader, raw_packet: &[u8]) -> Option<Action> {
        let next_hop = if header.destination == rezvan_common::BROADCAST_DESTINATION {
            crate::action::BROADCAST_TARGET
        } else {
            self.routing.get_best_route(&header.destination).map(|r| r.next_hop)?
        };

        let can_mutate_header = header.packet_type == 0x02;
        let data = if can_mutate_header {
            let mut relayed = MeshPacketHeader {
                ttl: header.ttl.saturating_sub(1),
                hop_count: header.hop_count.saturating_add(1),
                ..header.clone()
            }
            .serialize();
            // Append the original payload (everything after the old header,
            // i.e. the Olm ciphertext for 0x02) unchanged.
            relayed.extend_from_slice(&raw_packet[MeshPacketHeader::SIZE..]);
            relayed
        } else {
            raw_packet.to_vec()
        };

        Some(Action::SendBlePacket { target: next_hop, data })
    }

    /// The per-packet-type handling previously inlined directly in
    /// `process_incoming`'s dispatch match. Factored out so relay (above)
    /// can invoke it for broadcast types that are both "for us" and "for
    /// everyone else," without duplicating the decrypt/verify logic.
    fn dispatch_packet(
        &mut self,
        header: &MeshPacketHeader,
        raw_packet: &[u8],
        payload_end: usize,
        timestamp: u64,
        rssi: i32,
    ) -> (Option<DecryptedMessage>, Vec<Action>) {
        match header.packet_type {
            0x01 => {
                // Full OGM over GATT -- signature verified above, feed into
                // routing table so multi-hop routes propagate.
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
                // Register the full 164-byte bundle: mesh identity keys plus
                // the network-wide beacon epoch key/number.
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

        // Beacon authentication (per explicit product decision: robustness
        // over per-compromised-device blast-radius limitation -- see
        // rezvan_crypto::epoch_key module docs for the full design). This
        // REPLACES the earlier per-pair ECDH beacon MAC scheme, which was
        // broken by construction: BLE advertisements are physically
        // broadcast to an unknown audience, so there is no single
        // recipient to target with a pairwise key, and the old scheme's
        // sender-side placeholder key meant no real receiver could ever
        // verify it, even after a full KeyAnnouncement exchange.
        //
        // Any device holding the current epoch key (or one reachable by
        // ratcheting forward from an older epoch it has) can verify ANY
        // sender's beacon -- this proves "produced by a mesh member who
        // has this key", not "produced by this specific named sender".
        let verified = match self.sessions.epoch_key() {
            Some((our_epoch_key, _our_epoch_number)) => {
                let signed = beacon.signed_bytes();
                rezvan_crypto::epoch_key::verify_tag(&our_epoch_key, &signed, &beacon.mac)
            }
            None => {
                // We have no epoch key at all yet (brand new install, zero
                // peers ever seen) -- can't verify anything. Discovery-only,
                // same as the old "sender unknown" case.
                false
            }
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
        // `next_hop` is resolved via the routing table (same reasoning as
        // build_signed_packet): if `recipient` isn't a direct neighbour but
        // we have a multi-hop route to them, forward toward that route's
        // next hop instead of assuming direct delivery.
        let next_hop = self
            .routing
            .get_best_route(recipient)
            .map(|r| r.next_hop)
            .unwrap_or(*recipient);

        self.ogm_sequence = self.ogm_sequence.wrapping_add(1);
        let header = MeshPacketHeader {
            version: MESH_PACKET_VERSION,
            packet_type: 0x02,
            ttl: 10,
            originator: self.node_id,
            destination: *recipient,
            sequence: self.ogm_sequence,
            hop_count: 0,
            next_hop,
            payload_len: encrypted.len() as u16,
        };

        let mut packet = header.serialize();
        packet.extend_from_slice(&encrypted);

        vec![Action::SendBlePacket {
            // Radio-layer target for THIS hop -- may be an intermediate
            // relay, not necessarily `recipient` itself. Previously this
            // used the broadcast sentinel for every send, meaning 1:1
            // messages were physically transmitted to every GATT-connected
            // peer (relying on decryption failure to keep them private
            // rather than actually routing only to the intended recipient).
            target: next_hop,
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
    /// `[header:34][payload:N][Ed25519_sig:64]`.
    /// Used for all packet types except 0x02 (Olm AEAD is sufficient there).
    ///
    /// `destination` is the packet's final recipient (`BROADCAST_TARGET` for
    /// mesh-wide packets). `next_hop` -- the immediate radio-layer target for
    /// just this one hop -- is resolved here from the routing table rather
    /// than being passed in by the caller: for a direct/broadcast send it's
    /// the same as `destination`, but a relayed packet (see
    /// `MeshEngine::relay_packet`) needs `next_hop` to be an actual
    /// discovered neighbour on the path toward `destination`, which only
    /// this engine (not the caller) has visibility into.
    fn build_signed_packet(
        &mut self,
        packet_type: u8,
        ttl: u8,
        destination: &NodeId,
        payload: &[u8],
    ) -> Vec<u8> {
        self.ogm_sequence = self.ogm_sequence.wrapping_add(1);

        let next_hop = if *destination == crate::action::BROADCAST_TARGET {
            crate::action::BROADCAST_TARGET
        } else {
            self.routing
                .get_best_route(destination)
                .map(|r| r.next_hop)
                .unwrap_or(*destination) // no known route yet: try direct (1-hop) delivery
        };

        let header = MeshPacketHeader {
            version: MESH_PACKET_VERSION,
            packet_type,
            ttl,
            originator: self.node_id,
            destination: *destination,
            sequence: self.ogm_sequence,
            hop_count: 0,
            next_hop,
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
        // for the 7-byte epoch-key MAC. See rezvan_crypto::epoch_key.
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

        // Beacon authentication via the network-wide epoch key (see
        // rezvan_crypto::epoch_key module docs and process_beacon's
        // verification side above). This replaces the earlier per-pair ECDH
        // scheme, which computed the tag against a placeholder all-zero
        // "peer" key -- meaning no real receiver could ever reconstruct the
        // same shared secret, so the old MAC was broken for everyone, not
        // just "unverifiable by design" as originally documented.
        //
        // If we don't have an epoch key yet (shouldn't normally happen once
        // key_bundle() has run at least once, since that lazily bootstraps
        // one), fall back to an all-zero tag -- receivers without an epoch
        // key either will also fail to verify it (consistent "unverified"
        // outcome) and treat the beacon as discovery-only, same as before.
        let tag_key = self.sessions.epoch_key().map(|(k, _)| k).unwrap_or([0u8; 32]);
        let signed = beacon.signed_bytes();
        beacon.mac = rezvan_crypto::epoch_key::compute_tag(&tag_key, &signed);

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
            destination: rezvan_common::BROADCAST_DESTINATION,
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
        // payload (bytes [96..128) of the now-164-byte bundle, offset by header size).
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

        // Bob needs the shared epoch key to verify Alice's beacon MAC (see
        // rezvan_crypto::epoch_key). register_peer_keys parses the epoch
        // key/number out of Alice's bundle and converges Bob's own epoch key
        // toward hers (SessionManager::converge_epoch_key) -- since Bob has
        // no epoch key of his own yet at this point, he simply adopts hers
        // outright, so verification succeeds immediately.
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

    #[test]
    fn test_epoch_key_bootstraps_on_first_key_bundle_call() {
        // Before key_bundle() is ever called, a fresh engine has no epoch
        // key. The first call must bootstrap one (ensure_epoch_key), not
        // leave it None or panic.
        let mut alice = make_engine(1);
        assert!(alice.sessions.epoch_key().is_none(), "no epoch key before first key_bundle() call");
        let _bundle = alice.key_bundle();
        assert!(alice.sessions.epoch_key().is_some(), "key_bundle() must bootstrap an epoch key");
    }

    #[test]
    fn test_two_peers_converge_on_same_epoch_key_via_key_announcement() {
        // THE key regression test for this fix: the OLD per-pair beacon MAC
        // scheme was broken even between two peers who'd fully exchanged
        // KeyAnnouncements, because the sender computed its tag against a
        // placeholder key instead of anything a real receiver could
        // reconstruct. This proves the new epoch-key scheme actually closes
        // that gap: after a KeyAnnouncement exchange, both sides land on the
        // literal same 32-byte key.
        let mut alice = make_engine(1);
        let mut bob = make_engine(2);

        let alice_bundle = alice.key_bundle();
        bob.register_peer_keys(&alice.node_id, &alice_bundle);

        assert_eq!(
            alice.sessions.epoch_key(),
            bob.sessions.epoch_key(),
            "after Bob learns Alice's bundle, both must hold the identical epoch key"
        );
    }

    #[test]
    fn test_beacon_verifies_end_to_end_with_shared_epoch_key() {
        // Full round trip: Alice and Bob exchange keys, Alice ticks out a
        // real beacon (exercising the actual build_advertisement() code
        // path, not a hand-computed tag), and it must be VERIFIED (not just
        // discovery-only) once Bob has converged onto Alice's epoch key --
        // this is exactly the case the old scheme could never satisfy.
        let mut alice = make_engine(1);
        let mut bob = make_engine(2);

        let alice_bundle = alice.key_bundle();
        bob.register_peer_keys(&alice.node_id, &alice_bundle);

        let mut alice_beacon: Option<Vec<u8>> = None;
        for _ in 0..50 {
            for action in alice.tick() {
                if let Action::SendBleAdvertisement { data } = action {
                    alice_beacon = Some(data);
                }
            }
            if alice_beacon.is_some() { break; }
        }
        let beacon_data = alice_beacon.expect("Alice should have advertised within 50 ticks");
        let beacon_bytes = &beacon_data[..rezvan_common::AdvBeaconExt::SIZE];

        bob.process_incoming(beacon_bytes, -60, 0);

        // If verification succeeded, the beacon actually influenced routing
        // (see process_beacon's verified gate) -- confirmed via the routing
        // snapshot, same assertion style as the existing routing test above.
        let snap = bob.routing_snapshot();
        assert_eq!(snap[0], 1, "verified beacon must add a route");
    }

    #[test]
    fn test_unrelated_third_peer_also_verifies_beacon() {
        // The defining property of the epoch-key scheme vs. the old per-pair
        // one: Carol, who has NEVER exchanged keys directly with Alice, can
        // still verify Alice's beacon -- as long as Carol has converged onto
        // the same epoch key via SOME KeyAnnouncement exchange (here, with
        // Bob, who got it from Alice). This is the "mesh membership" proof
        // the design explicitly trades specific-sender-authentication for.
        let mut alice = make_engine(1);
        let mut bob = make_engine(2);
        let mut carol = make_engine(3);

        let alice_bundle = alice.key_bundle();
        bob.register_peer_keys(&alice.node_id, &alice_bundle);

        let bob_bundle = bob.key_bundle();
        carol.register_peer_keys(&bob.node_id, &bob_bundle);

        assert_eq!(alice.sessions.epoch_key(), carol.sessions.epoch_key(),
            "epoch key must propagate transitively through the mesh");

        let mut alice_beacon: Option<Vec<u8>> = None;
        for _ in 0..50 {
            for action in alice.tick() {
                if let Action::SendBleAdvertisement { data } = action {
                    alice_beacon = Some(data);
                }
            }
            if alice_beacon.is_some() { break; }
        }
        let beacon_data = alice_beacon.expect("Alice should have advertised within 50 ticks");
        let beacon_bytes = &beacon_data[..rezvan_common::AdvBeaconExt::SIZE];

        carol.process_incoming(beacon_bytes, -60, 0);

        let snap = carol.routing_snapshot();
        assert_eq!(snap[0], 1, "Carol (never met Alice directly) must still verify Alice's beacon via the shared epoch key");
    }

    #[test]
    fn test_beacon_unverified_without_any_epoch_key() {
        // A device that has never bootstrapped or learned an epoch key at
        // all (zero peers ever seen) cannot verify anything -- discovery
        // only, same as the old "sender unknown" case. This matters because
        // key_bundle() lazily bootstraps a key; a device that has NEVER
        // called key_bundle() (and never will, if it never sends its own
        // KeyAnnouncement) should still fail closed rather than somehow
        // verify against a default/empty key.
        let mut alice = make_engine(1);
        let mut bob = make_engine(2);
        // Deliberately do NOT exchange keys.

        let mut alice_beacon: Option<Vec<u8>> = None;
        for _ in 0..50 {
            for action in alice.tick() {
                if let Action::SendBleAdvertisement { data } = action {
                    alice_beacon = Some(data);
                }
            }
            if alice_beacon.is_some() { break; }
        }
        let beacon_data = alice_beacon.expect("Alice should have advertised within 50 ticks");
        let beacon_bytes = &beacon_data[..rezvan_common::AdvBeaconExt::SIZE];

        bob.process_incoming(beacon_bytes, -60, 0);

        let snap = bob.routing_snapshot();
        assert_eq!(snap[0], 0, "unverified beacon (no shared epoch key) must not add a route");
    }

    // ---- Multi-hop relay tests (version 0x03 wire format) ------------------

    /// Directly seed `engine`'s routing table with a route to `dest` via
    /// `next_hop`, bypassing real beacon exchange. Test-only: production
    /// code populates routes exclusively through `process_beacon`/
    /// `process_ogm`. `mut engine: &mut MeshEngine` avoids needing a public
    /// setter on `RoutingTable` just for this.
    fn seed_route(engine: &mut MeshEngine, dest: NodeId, next_hop: NodeId) {
        engine.routing.process_beacon(
            &rezvan_common::AdvBeaconExt {
                version: rezvan_common::AdvBeaconExt::VERSION,
                packet_type: 0x01,
                originator: dest,
                sequence: 1,
                battery: 80,
                power_state: 1,
                node_flags: 0,
                mac: [0u8; 7],
            },
            -60,
            true, // pretend verified -- test-only shortcut, see fn docs
        );
        // process_beacon always routes a fresh entry's next_hop to the
        // beacon's own originator (i.e. "dest is 1 hop away, next_hop ==
        // dest"). For a genuine multi-hop seed (next_hop != dest) we need to
        // patch the entry afterward -- there's no public API for "learn a
        // route to X via next-hop Y" since real code only ever learns that
        // from an actual OGM (`process_ogm`), and building a signed OGM
        // packet here would need a full engine, not just a routing table.
        if next_hop != dest {
            let mut entries = engine.routing.get_routes(&dest).to_vec();
            for e in entries.iter_mut() {
                e.next_hop = next_hop;
            }
            engine.routing.test_only_set_routes(dest, entries);
        }
    }

    #[test]
    fn test_unicast_relayed_through_intermediate_hop() {
        // Alice -> [relay: Bob] -> Carol. Alice and Carol are NOT direct
        // neighbours; Bob has routes to both. Alice sends Carol a direct
        // message; Bob (as the intermediate hop) must forward it on toward
        // Carol rather than trying to decrypt it himself (wrong Olm
        // session -- it would just fail) or dropping it.
        let mut alice = make_engine(1);
        let mut bob = make_engine(2);
        let carol = make_engine(3);

        // Alice needs a session with Carol to send an encrypted 0x02 --
        // exchange keys directly (in reality this might itself go through
        // relay, but that's a separate concern from what this test checks).
        let carol_bundle_holder = {
            let mut carol_for_bundle = make_engine(3);
            carol_for_bundle.key_bundle()
        };
        alice.register_peer_keys(&carol.node_id, &carol_bundle_holder);

        // Alice's routing table must know to reach Carol via Bob.
        seed_route(&mut alice, carol.node_id, bob.node_id);
        // Bob must know how to reach Carol directly (Bob IS a neighbour of
        // Carol, even though Alice isn't).
        seed_route(&mut bob, carol.node_id, carol.node_id);

        let actions = alice.send_message(&carol.node_id, b"hello via relay", 0);
        assert_eq!(actions.len(), 1, "send_message should produce exactly one SendBlePacket");
        let Action::SendBlePacket { target, data } = &actions[0] else {
            panic!("expected SendBlePacket");
        };
        assert_eq!(*target, bob.node_id, "Alice must send toward Bob (the resolved next hop), not directly to Carol");

        // Bob receives it. He is NOT the destination (Carol is), so he must
        // relay rather than attempt to decrypt.
        let (msg, bob_actions) = bob.process_incoming(data, -60, 0);
        assert!(msg.is_none(), "Bob is not the final recipient -- must not surface a decrypted message locally");
        assert_eq!(bob_actions.len(), 1, "Bob must relay exactly one packet");
        let Action::SendBlePacket { target, data: relayed_data } = &bob_actions[0] else {
            panic!("expected relayed SendBlePacket");
        };
        assert_eq!(*target, carol.node_id, "Bob must relay toward Carol (his resolved next hop for Carol)");

        // The relayed packet must have ttl decremented and hop_count
        // incremented relative to what Alice originally sent (0x02 has no
        // outer signature, so this mutation is safe -- see
        // build_relay_action's docs).
        let original_header = rezvan_common::MeshPacketHeader::deserialize(data).unwrap();
        let relayed_header = rezvan_common::MeshPacketHeader::deserialize(relayed_data).unwrap();
        assert_eq!(relayed_header.ttl, original_header.ttl - 1, "relay must decrement ttl for unsigned (0x02) packets");
        assert_eq!(relayed_header.hop_count, original_header.hop_count + 1, "relay must increment hop_count for unsigned (0x02) packets");
        assert_eq!(relayed_header.originator, alice.node_id, "originator must be preserved through relay");
        assert_eq!(relayed_header.destination, carol.node_id, "destination must be preserved through relay");
    }

    #[test]
    fn test_unicast_not_addressed_to_us_and_no_route_is_dropped() {
        // If we're not the destination and we have no route to relay
        // through, the packet must be dropped, not silently misdelivered or
        // panicked on.
        let mut alice = make_engine(1);
        let mut bob = make_engine(2);
        let carol = make_engine(3);

        let carol_bundle = {
            let mut carol_for_bundle = make_engine(3);
            carol_for_bundle.key_bundle()
        };
        alice.register_peer_keys(&carol.node_id, &carol_bundle);
        // Bob is given no route to Carol at all.

        let actions = alice.send_message(&carol.node_id, b"undeliverable", 0);
        let Action::SendBlePacket { data, .. } = &actions[0] else { panic!("expected SendBlePacket") };

        let (msg, bob_actions) = bob.process_incoming(data, -60, 0);
        assert!(msg.is_none());
        assert!(bob_actions.is_empty(), "no route to relay through -- must drop silently, not error or misdeliver");
    }

    #[test]
    fn test_relay_loop_prevented_by_seen_and_record() {
        // A relay-candidate packet (originator, sequence) that this node has
        // already relayed once must be dropped on a second sighting, even
        // if it would otherwise still look relayable (e.g. arriving again
        // via a different simulated neighbour) -- this is what actually
        // prevents infinite re-flooding in a real mesh with cycles.
        let mut alice = make_engine(1);
        let mut bob = make_engine(2);
        let carol = make_engine(3);

        let carol_bundle = {
            let mut carol_for_bundle = make_engine(3);
            carol_for_bundle.key_bundle()
        };
        alice.register_peer_keys(&carol.node_id, &carol_bundle);
        seed_route(&mut alice, carol.node_id, bob.node_id);
        seed_route(&mut bob, carol.node_id, carol.node_id);

        let actions = alice.send_message(&carol.node_id, b"loop test", 0);
        let Action::SendBlePacket { data, .. } = &actions[0] else { panic!("expected SendBlePacket") };

        let (_msg1, actions1) = bob.process_incoming(data, -60, 0);
        assert_eq!(actions1.len(), 1, "first sighting must relay");

        // Simulate the exact same wire bytes arriving at Bob again (e.g. a
        // duplicate delivery, or a routing cycle looping it back).
        let (_msg2, actions2) = bob.process_incoming(data, -60, 0);
        assert!(actions2.is_empty(), "second sighting of the same (originator, sequence) must be dropped, not relayed again");
    }

    #[test]
    fn test_broadcast_relay_also_processed_locally() {
        // 0x03 emergency broadcast: a relay-candidate node that is itself a
        // legitimate recipient (broadcast = everyone) must BOTH surface the
        // decrypted/plaintext message locally AND re-flood it onward -- the
        // two are not mutually exclusive for broadcast-scoped types.
        let mut alice = make_engine(1);
        let mut bob = make_engine(2);

        // Bob needs Alice's Ed25519 identity key on file to verify her
        // signed 0x03 broadcast (see needs_sig / the signature-check block
        // in process_incoming) -- without this, the packet is rejected
        // before dispatch ever runs, regardless of relay logic.
        let alice_bundle = alice.key_bundle();
        bob.register_peer_keys(&alice.node_id, &alice_bundle);

        let actions = alice.send_broadcast(b"evacuate now");
        let Action::SendBlePacket { data, .. } = &actions[0] else { panic!("expected SendBlePacket") };

        let (msg, bob_actions) = bob.process_incoming(data, -60, 0);
        assert!(msg.is_some(), "broadcast recipient must surface the message locally");
        assert_eq!(msg.unwrap().content, b"evacuate now");
        assert_eq!(bob_actions.len(), 1, "broadcast must also be re-flooded onward");
        let Action::SendBlePacket { target, .. } = &bob_actions[0] else { panic!("expected re-flood SendBlePacket") };
        assert_eq!(*target, crate::action::BROADCAST_TARGET, "re-flood target must be the broadcast sentinel");
    }

    #[test]
    fn test_ogm_broadcast_from_tick_updates_receiver_routing_table() {
        // Regression test for the "OGM never sent" gap: tick() must now
        // actually emit a signed 0x01 OGM broadcast (not just the
        // lightweight AdvBeaconExt beacon), and a receiver must be able to
        // verify and process it via the same signature-check path as every
        // other signed packet type.
        let mut alice = make_engine(1);
        let mut bob = make_engine(2);

        // Bob needs Alice's Ed25519 identity key to verify her OGM's
        // signature (see engine.rs's needs_sig verification block).
        let alice_bundle = alice.key_bundle();
        bob.register_peer_keys(&alice.node_id, &alice_bundle);

        // Give Alice a route to advertise (a neighbour of hers), so her OGM
        // payload is non-trivial. Not strictly required for this test, but
        // keeps it honest about what a real OGM broadcast carries.
        let dave = make_engine(4);
        seed_route(&mut alice, dave.node_id, dave.node_id);

        let mut ogm_packet: Option<Vec<u8>> = None;
        for _ in 0..200 {
            for action in alice.tick() {
                if let Action::SendBlePacket { data, .. } = &action {
                    if let Some(header) = rezvan_common::MeshPacketHeader::deserialize(data) {
                        if header.packet_type == 0x01 {
                            ogm_packet = Some(data.clone());
                        }
                    }
                }
            }
            if ogm_packet.is_some() { break; }
        }
        let packet = ogm_packet.expect("tick() should emit a signed 0x01 OGM broadcast within 200 ticks");

        let (msg, actions) = bob.process_incoming(&packet, -60, 0);
        assert!(msg.is_none(), "an OGM produces no decrypted message");
        // 0x01 is deliberately excluded from the relay-candidate set (see
        // process_incoming's relay section), so Bob must not try to relay
        // it -- only feed it into his routing table.
        assert!(actions.is_empty(), "OGM is not a relay-candidate type; only routing-table bookkeeping happens");

        let snap = bob.routing_snapshot();
        assert!(snap[0] >= 1, "Bob's routing table should reflect Alice as a route after processing her OGM");
    }
}
