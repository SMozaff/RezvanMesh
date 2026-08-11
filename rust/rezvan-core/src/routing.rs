use rezvan_common::{AdvBeaconExt, NeighborInfo, NodeId, OGMPayload, MeshPacketHeader};
use std::collections::{HashMap, HashSet};

// ---------------------------------------------------------------------------
// Routing Table
// ---------------------------------------------------------------------------

pub struct RoutingTable {
    /// Our own node id (first 8 bytes of SHA‑256(pubkey))
    pub node_id: NodeId,
    /// Map from destination NodeId → up to 3 candidate routes
    routes: HashMap<NodeId, Vec<RouteEntry>>,
    /// Highest sequence number seen from each originator, for replay
    /// rejection. Populated by `process_beacon`.
    ///
    /// Remediation #2 (see REMEDIATION_PROPOSAL.md): this used to be pruned
    /// in lockstep with route eviction in `purge_stale`, which reset replay
    /// protection for any peer that went quiet and later reconnected --
    /// letting a captured old beacon (or a rejoining peer whose sequence
    /// counter legitimately regressed) be replayed after the routing purge.
    /// Replay tracking now has its own independent lifetime
    /// (`replay_last_seen_tick`, evicted only by `max_age_ticks *
    /// REPLAY_RETENTION_MULTIPLIER`), so it deliberately outlives the
    /// routing entry itself.
    last_seen_seq: HashMap<NodeId, u32>,
    /// Tick at which we last updated `last_seen_seq` for a given originator.
    /// Used only to eventually bound `last_seen_seq`'s memory growth; NOT
    /// used to gate replay rejection itself.
    replay_last_seen_tick: HashMap<NodeId, u64>,
    /// Logical clock (advanced by the engine once per `tick()`, i.e. once
    /// per BLE-advertisement cycle). There is no wall-clock time available
    /// at this layer -- `tick()` has no timestamp parameter and adding one
    /// would mean changing the JNI signature and every Kotlin call site.
    /// Using "ticks since last seen" as a staleness proxy avoids that while
    /// still giving `purge_stale` something concrete to act on (security
    /// audit finding #9: this function existed but was never called and had
    /// no data to purge with).
    current_tick: u64,
    /// Set of (originator, sequence) pairs this node has already relayed or
    /// re-flooded, used by `seen_and_record` to prevent relay loops for
    /// 0x02/0x03/0x06 packets (see `MeshEngine::process_incoming`'s relay
    /// section). Deliberately separate from `last_seen_seq`: that field
    /// gates REPLAY (a sequence must be strictly greater than the last one
    /// seen from that originator, for beacons/OGMs which are periodic and
    /// monotonically increasing), whereas relay dedup needs to catch an
    /// EXACT (originator, sequence) pair seen before regardless of
    /// ordering -- a flooded broadcast can legitimately arrive out of order
    /// from multiple neighbours, and rejecting anything "not strictly
    /// newer" would incorrectly drop a legitimate second copy of the same
    /// broadcast arriving via a different, slower path before drop-worthy
    /// duplication has even been established.
    ///
    /// Bounded the same way as `last_seen_seq`/`replay_last_seen_tick`: per
    /// originator, evicted via `relayed_seen_last_tick` after
    /// `REPLAY_RETENTION_MULTIPLIER * max_age_ticks` of silence from that
    /// originator, so this doesn't grow unboundedly over a long session.
    relayed_seen: HashMap<NodeId, HashSet<u32>>,
    /// Tick at which `relayed_seen` was last touched for a given
    /// originator; same eviction role as `replay_last_seen_tick`.
    relayed_seen_last_tick: HashMap<NodeId, u64>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct RouteEntry {
    /// Next hop to reach the destination
    pub next_hop: NodeId,
    /// Cumulative BATMAN‑adv metric (lower is better)
    pub metric: u32,
    /// Link quality (0‑255) of the link through which this OGM was received
    pub link_quality: u8,
    /// Value of `current_tick` when this route was last confirmed by a
    /// verified beacon or OGM. Used by `purge_stale` to evict routes through
    /// peers that have gone silent.
    pub last_seen_tick: u64,
}

impl RoutingTable {
    pub fn new(node_id: NodeId) -> Self {
        Self {
            node_id,
            routes: HashMap::new(),
            last_seen_seq: HashMap::new(),
            replay_last_seen_tick: HashMap::new(),
            current_tick: 0,
            relayed_seen: HashMap::new(),
            relayed_seen_last_tick: HashMap::new(),
        }
    }

    /// How much longer replay-sequence tracking is retained relative to
    /// route liveness. Routes are evicted quickly (peer went out of range),
    /// but replay history needs to survive a plausible reconnect window --
    /// otherwise a purged-then-rejoining peer's old sequence numbers become
    /// valid again. This is a memory/security tradeoff, not a hard
    /// guarantee: an attacker who can wait longer than this multiplier can
    /// still eventually replay. See REMEDIATION_PROPOSAL.md #2.
    const REPLAY_RETENTION_MULTIPLIER: u64 = 8;

    /// Advance the logical clock. Call once per `MeshEngine::tick()`.
    pub fn advance_tick(&mut self) {
        self.current_tick = self.current_tick.wrapping_add(1);
    }

    /// Records that `(originator, sequence)` has been seen for relay
    /// purposes, returning `true` if it was ALREADY recorded (i.e. this is
    /// a duplicate that should be dropped rather than relayed again) or
    /// `false` if this is the first time (i.e. safe to relay/re-flood).
    /// See the `relayed_seen` field docs for why this is intentionally
    /// separate from the replay-rejection tracking used by
    /// `process_beacon`/`process_ogm`.
    pub fn seen_and_record(&mut self, originator: NodeId, sequence: u32) -> bool {
        self.relayed_seen_last_tick.insert(originator, self.current_tick);
        let set = self.relayed_seen.entry(originator).or_default();
        !set.insert(sequence)
    }

    // -----------------------------------------------------------------------
    // Beacon Processing (the real, live 1-hop discovery path)
    // -----------------------------------------------------------------------

    /// Process an incoming, ALREADY-AUTHENTICATED beacon.
    ///
    /// Security audit finding #3 / Fix 3: this function does NOT verify the
    /// beacon's MAC itself -- that requires the sender's X25519 identity key
    /// from `SessionManager`, which `RoutingTable` deliberately has no
    /// access to (keeping crypto verification and routing-table bookkeeping
    /// separate). The caller (`MeshEngine::process_incoming`) MUST verify
    /// the beacon via `rezvan_crypto::epoch_key::verify_tag` -- or confirm
    /// the sender is not yet known and treat this as discovery-only -- before
    /// calling this function with `verified = true`.
    ///
    /// Independent of authentication, this always enforces replay/reordering
    /// protection: a beacon whose sequence number is not strictly greater
    /// than the last one seen from this originator is rejected outright,
    /// authenticated or not (a replayed OLD beacon is never useful even if
    /// its MAC is valid).
    ///
    /// Returns `true` if the routing table changed such that this beacon's
    /// information is now worth reflecting in our own next OGM.
    pub fn process_beacon(&mut self, beacon: &AdvBeaconExt, rssi: i32, verified: bool) -> bool {
        if beacon.originator == self.node_id {
            return false;
        }

        let last_seq = self.last_seen_seq.get(&beacon.originator).copied();
        if let Some(last) = last_seq {
            if beacon.sequence <= last {
                return false; // stale or replayed
            }
        }
        self.last_seen_seq.insert(beacon.originator, beacon.sequence);
        self.replay_last_seen_tick.insert(beacon.originator, self.current_tick);

        // Unverified beacons (sender's key not yet known -- first contact)
        // update replay tracking above so we don't process the exact same
        // unverified beacon twice, but must NOT be allowed to influence
        // routing decisions. Once the sender's KeyAnnouncement arrives and
        // later beacons verify, routing starts reflecting them normally.
        if !verified {
            return false;
        }

        let lq = rssi_to_lq(rssi);
        if lq == 0 {
            return false; // link too weak to consider
        }

        // Battery weight: a low-battery neighbour is a worse relay even at
        // good signal quality, since it may die mid-transmission.
        let battery_weight = if beacon.battery > 50 {
            1.0
        } else if beacon.battery > 20 {
            1.5
        } else {
            2.5
        };

        let hop_penalty = compute_hop_penalty(lq, battery_weight);
        // This is a direct (1-hop) beacon, so the path metric to the
        // originator is just this link's penalty.
        let new_metric = hop_penalty;

        let entries = self.routes.entry(beacon.originator).or_default();

        if let Some(existing) = entries.iter_mut().find(|e| e.next_hop == beacon.originator) {
            // Bug fix: this branch used to return `true` only when the
            // metric strictly improved, and `false` otherwise -- even
            // though `last_seen_tick` (line above) was unconditionally
            // refreshed either way. That meant a perfectly healthy, stable
            // link (same RSSI/battery beacon after beacon) was reported to
            // the caller as "nothing changed," even though the *liveness*
            // of this route absolutely did change: it's the only thing
            // standing between this route and eviction by `purge_stale`.
            // Caught by `test_process_beacon_replay_rejected`, which
            // expects a validly-sequenced beacon from an already-known peer
            // to be reported as accepted/processed regardless of whether
            // its metric happens to tie the existing one.
            //
            // A beacon that reaches this point has already passed replay
            // rejection, verification, and the weak-link cutoff (`lq == 0`
            // returns early above) -- it is real, current information about
            // this route, so it is always routing-table-relevant.
            existing.last_seen_tick = self.current_tick;
            if new_metric < existing.metric {
                existing.metric = new_metric;
                existing.link_quality = lq;
            }
            return true;
        }

        entries.push(RouteEntry {
            next_hop: beacon.originator,
            metric: new_metric,
            link_quality: lq,
            last_seen_tick: self.current_tick,
        });
        entries.sort_by(|a, b| a.metric.cmp(&b.metric));
        entries.truncate(3);

        true
    }

    // -----------------------------------------------------------------------
    // Route Lookup
    // -----------------------------------------------------------------------

    /// Return the best route (lowest metric) for a destination.
    pub fn get_best_route(&self, dest: &NodeId) -> Option<&RouteEntry> {
        self.routes.get(dest)?.first()
    }

    /// Return all known routes for a destination (up to 3).
    pub fn get_routes(&self, dest: &NodeId) -> &[RouteEntry] {
        self.routes.get(dest).map(|v| v.as_slice()).unwrap_or(&[])
    }

    /// Test-only: directly mutate the routes known for `dest`. Production
    /// code must never call this -- routes are populated exclusively
    /// through `process_beacon`/`process_ogm`, which enforce replay
    /// rejection, verification, and link-quality gating that this bypasses
    /// entirely. Exists so `engine.rs`'s relay tests can seed a multi-hop
    /// route (`next_hop != dest`) without needing to construct a full,
    /// signed OGM packet just to set up test fixtures.
    #[cfg(test)]
    pub fn test_only_set_routes(&mut self, dest: NodeId, entries: Vec<RouteEntry>) {
        self.routes.insert(dest, entries);
    }

    /// Return a list of all known destinations.
    pub fn destinations(&self) -> Vec<NodeId> {
        self.routes.keys().cloned().collect()
    }

    // -----------------------------------------------------------------------
    // Multi-hop OGM construction (MeshPacketHeader-based)
    // -----------------------------------------------------------------------
    //
    // UPDATE: multi-hop relay landed alongside the version-0x03 wire format
    // (`MeshPacketHeader::destination`). `build_ogm` is now called
    // periodically from `MeshEngine::tick()`, and `process_ogm` is called
    // from `MeshEngine::process_incoming` for received 0x01 packets -- both
    // signed the same way KeyAnnouncement/broadcast/handshake packets are,
    // per the note this comment used to warn about. See
    // `MeshEngine::process_incoming`'s "Relay" section for how 0x02/0x03/0x06
    // packets are forwarded toward a destination that isn't a direct
    // neighbour, using this table's routes.

    /// Build an OGM packet that reflects our current view of the network.
    /// Signature is appended externally by the engine (Ed25519, 64 bytes).
    /// `timestamp` in the payload is `self.current_tick` (this table's
    /// existing ticks-as-time proxy, advanced once per `MeshEngine::tick()`)
    /// rather than a caller-supplied wall-clock value -- there is no
    /// wall-clock time available at this layer, same reasoning as
    /// `RouteEntry::last_seen_tick` and `purge_stale`'s docs. Consumers of
    /// this OGM's timestamp field must treat it the same way: relative
    /// ticks, not epoch time.
    pub fn build_ogm(&self, sequence: u32) -> Vec<u8> {
        let mut neighbors = [NeighborInfo::default(); 9];
        let mut count = 0u8;

        for (dest, entries) in &self.routes {
            if count >= 9 {
                break;
            }
            if let Some(best) = entries.first() {
                let mut prefix = [0u8; 3];
                prefix.copy_from_slice(&dest[..3]);
                neighbors[count as usize] = NeighborInfo {
                    node_id_prefix: prefix,
                    link_quality: best.link_quality,
                };
                count += 1;
            }
        }

        let ogm = OGMPayload {
            timestamp: self.current_tick,
            link_quality: 0,
            path_metric: 0,
            neighbor_count: count,
            neighbors,
        };

        let payload = ogm.serialize();
        let header = MeshPacketHeader {
            version: rezvan_common::MESH_PACKET_VERSION,
            packet_type: 0x01,
            ttl: 10,
            originator: self.node_id,
            destination: rezvan_common::BROADCAST_DESTINATION,
            sequence,
            hop_count: 0,
            next_hop: [0u8; 8],
            payload_len: payload.len() as u16,
        };

        let mut packet = header.serialize();
        packet.extend_from_slice(&payload);
        packet
    }

    /// Parse a MeshPacketHeader-framed OGM packet (NOT the live AdvBeaconExt
    /// beacon format -- see module note above). Caller must have already
    /// verified the trailing Ed25519 signature before calling this; this
    /// function only handles routing-table bookkeeping and enforces the same
    /// replay check as `process_beacon`.
    pub fn process_ogm(&mut self, packet: &[u8], rssi: i32) -> bool {
        let header = match MeshPacketHeader::deserialize(packet) {
            Some(h) => h,
            None => return false,
        };

        if header.originator == self.node_id {
            return false;
        }

        let last_seq = self.last_seen_seq.get(&header.originator).copied();
        if let Some(last) = last_seq {
            if header.sequence <= last {
                return false;
            }
        }

        let payload_data = match packet.get(MeshPacketHeader::SIZE..) {
            Some(p) => p,
            None => return false,
        };

        let ogm = match OGMPayload::deserialize(payload_data) {
            Some(o) => o,
            None => return false,
        };

        let lq = rssi_to_lq(rssi);
        if lq == 0 {
            return false;
        }

        self.last_seen_seq.insert(header.originator, header.sequence);

        let battery_weight = 1.0;
        let hop_penalty = compute_hop_penalty(lq, battery_weight);
        let new_metric = ogm.path_metric + hop_penalty;

        let entries = self.routes.entry(header.originator).or_default();

        if let Some(existing) = entries.iter_mut().find(|e| e.next_hop == header.next_hop) {
            // Same fix as process_beacon's equivalent branch: liveness
            // refresh (line above) is always routing-table-relevant, not
            // just a metric improvement -- see that function's comment for
            // the full reasoning.
            existing.last_seen_tick = self.current_tick;
            if new_metric < existing.metric {
                existing.metric = new_metric;
                existing.link_quality = lq;
            }
            return true;
        }

        entries.push(RouteEntry {
            next_hop: header.originator,
            metric: new_metric,
            link_quality: lq,
            last_seen_tick: self.current_tick,
        });
        entries.sort_by(|a, b| a.metric.cmp(&b.metric));
        entries.truncate(3);

        true
    }

    /// Remove routes that haven't been confirmed by a beacon/OGM in more than
    /// `max_age_ticks` ticks (see `current_tick` docs -- there is no
    /// wall-clock time at this layer, so "ticks" stands in for elapsed time;
    /// at the default beacon cadence this is roughly `max_age_ticks` seconds,
    /// but the exact mapping depends on the current power state's OGM
    /// interval). Also drops `last_seen_seq` entries for any originator with
    /// no remaining routes, so memory doesn't grow unboundedly over a long
    /// mesh session as peers come and go (security audit finding #9: this
    /// function previously did nothing and was never called).
    ///
    /// Tradeoff: forgetting `last_seen_seq` for a purged (long-silent) peer
    /// means that if that peer reappears, its next beacon is accepted even
    /// if its sequence number happens to be lower than one we saw a long
    /// time ago -- an attacker who captured that peer's old beacon could
    /// replay it after the entry ages out. This is judged acceptable: a peer
    /// silent long enough to be purged is being re-discovered anyway, so
    /// treating its next beacon as "new" is the correct behavior, not just
    /// an accepted weakness. If tighter replay protection across purges is
    /// ever needed, keep a separate (originator, max-seq-ever-seen) map that
    /// is never purged -- at the cost of the unbounded growth this was
    /// meant to avoid.
    pub fn purge_stale(&mut self, max_age_ticks: u64) {
        let now = self.current_tick;
        self.routes.retain(|_, entries| {
            entries.retain(|e| now.saturating_sub(e.last_seen_tick) <= max_age_ticks);
            !entries.is_empty()
        });

        // Replay-sequence tracking is intentionally NOT tied to route
        // liveness (see field docs on `last_seen_seq` / remediation #2). A
        // peer that goes out of range and reconnects within
        // REPLAY_RETENTION_MULTIPLIER * max_age_ticks must not have its
        // sequence counter reset, or a captured old beacon becomes replayable
        // the moment its route is purged. We only evict replay state once it
        // has been quiet far longer than that -- purely to bound memory, not
        // to gate security.
        let replay_max_age = max_age_ticks.saturating_mul(Self::REPLAY_RETENTION_MULTIPLIER);
        let replay_last_seen_tick = &self.replay_last_seen_tick;
        self.last_seen_seq.retain(|node, _| {
            replay_last_seen_tick
                .get(node)
                .map(|&t| now.saturating_sub(t) <= replay_max_age)
                .unwrap_or(false)
        });
        self.replay_last_seen_tick
            .retain(|_, &mut t| now.saturating_sub(t) <= replay_max_age);

        // Same bounded-eviction pattern as replay tracking above, applied to
        // relay-loop-dedup tracking (`seen_and_record`). Once an originator
        // has been silent for `replay_max_age` ticks, forget which of their
        // sequence numbers we've relayed -- if they resurface after that
        // long, treating their next packet as "not yet relayed" is correct
        // (they're being freshly rediscovered), same reasoning as
        // `last_seen_seq`'s eviction above.
        let relayed_seen_last_tick = &self.relayed_seen_last_tick;
        self.relayed_seen.retain(|node, _| {
            relayed_seen_last_tick
                .get(node)
                .map(|&t| now.saturating_sub(t) <= replay_max_age)
                .unwrap_or(false)
        });
        self.relayed_seen_last_tick
            .retain(|_, &mut t| now.saturating_sub(t) <= replay_max_age);
    }
}

// ---------------------------------------------------------------------------
// Metric Helpers
// ---------------------------------------------------------------------------

/// Map RSSI to a 0‑255 link quality value.
pub fn rssi_to_lq(rssi: i32) -> u8 {
    if rssi > -65 {
        255
    } else if rssi < -85 {
        0
    } else {
        ((rssi + 85) * 255 / 20) as u8
    }
}

/// Compute the BATMAN‑adv hop penalty for a single link.
///
/// `battery_weight` is derived from the neighbour's battery level:
///   - > 50 % → 1.0
///   - > 20 % → 1.5
///   - else   → 2.5
pub fn compute_hop_penalty(lq: u8, battery_weight: f32) -> u32 {
    let lq_f = lq.max(1) as f32;
    (1000.0 * (256.0 / lq_f).powi(2) * battery_weight) as u32
}

/// Compute the route length penalty (discourages excessively long paths).
pub fn route_length_penalty(hop_count: u8) -> u32 {
    100 * (hop_count.saturating_sub(1) as f32).powf(1.5) as u32
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    fn dummy_node_id(byte: u8) -> NodeId {
        [byte; 8]
    }

    fn dummy_ogm_packet(originator: NodeId, seq: u32, path_metric: u32, hop_count: u8) -> Vec<u8> {
        let ogm = OGMPayload {
            timestamp: 0,
            link_quality: 200,
            path_metric,
            neighbor_count: 0,
            neighbors: [NeighborInfo::default(); 9],
        };
        let payload = ogm.serialize();
        let header = MeshPacketHeader {
            version: rezvan_common::MESH_PACKET_VERSION,
            packet_type: 0x01,
            ttl: 10,
            originator,
            destination: rezvan_common::BROADCAST_DESTINATION,
            sequence: seq,
            hop_count,
            next_hop: originator,
            payload_len: payload.len() as u16,
        };
        let mut pkt = header.serialize();
        pkt.extend_from_slice(&payload);
        pkt
    }

    fn dummy_beacon(originator: NodeId, seq: u32, battery: u8) -> AdvBeaconExt {
        AdvBeaconExt {
            version: AdvBeaconExt::VERSION,
            packet_type: 0x01,
            originator,
            sequence: seq,
            battery,
            power_state: 1,
            node_flags: 0,
            mac: [0u8; 7],
        }
    }

    #[test]
    fn test_rssi_to_lq_boundaries() {
        assert_eq!(rssi_to_lq(-50), 255);
        assert_eq!(rssi_to_lq(-65), 255);
        assert_eq!(rssi_to_lq(-85), 0);
        assert_eq!(rssi_to_lq(-90), 0);
    }

    #[test]
    fn test_hop_penalty() {
        let penalty = compute_hop_penalty(255, 1.0);
        assert!(penalty > 900 && penalty < 1100, "penalty={}", penalty);
    }

    // --- process_beacon tests -----------------------------------------------

    #[test]
    fn test_process_beacon_adds_route_when_verified() {
        let mut table = RoutingTable::new(dummy_node_id(0xAA));
        let beacon = dummy_beacon(dummy_node_id(0xBB), 1, 80);
        let changed = table.process_beacon(&beacon, -60, true);
        assert!(changed);
        assert!(table.get_best_route(&dummy_node_id(0xBB)).is_some());
    }

    #[test]
    fn test_process_beacon_own_packet_ignored() {
        let our_id = dummy_node_id(0xAA);
        let mut table = RoutingTable::new(our_id);
        let beacon = dummy_beacon(our_id, 1, 80);
        assert!(!table.process_beacon(&beacon, -60, true));
    }

    #[test]
    fn test_process_beacon_unverified_does_not_influence_routing() {
        let mut table = RoutingTable::new(dummy_node_id(0xAA));
        let beacon = dummy_beacon(dummy_node_id(0xBB), 1, 80);
        // Even with good signal, an unverified beacon must not add a route.
        let changed = table.process_beacon(&beacon, -60, false);
        assert!(!changed);
        assert!(table.get_best_route(&dummy_node_id(0xBB)).is_none());
        // But it DOES advance last_seen_seq to prevent the same packet from
        // being processed a second time if a late-arriving verified copy
        // shows up -- confirmed by sending seq=1 again and checking rejection.
        let changed2 = table.process_beacon(&beacon, -60, true);
        assert!(!changed2, "stale seq must be rejected even if now verified");
    }

    #[test]
    fn test_process_beacon_replay_rejected() {
        let mut table = RoutingTable::new(dummy_node_id(0xAA));
        let peer = dummy_node_id(0xBB);
        let b1 = dummy_beacon(peer, 10, 80);
        assert!(table.process_beacon(&b1, -60, true));
        // Same sequence again: must be rejected.
        assert!(!table.process_beacon(&b1, -60, true));
        // Older sequence: must be rejected.
        let b_old = dummy_beacon(peer, 9, 80);
        assert!(!table.process_beacon(&b_old, -60, true));
        // Next sequence: must be accepted.
        let b_next = dummy_beacon(peer, 11, 80);
        assert!(table.process_beacon(&b_next, -60, true));
    }

    #[test]
    fn test_process_beacon_weak_link_not_added() {
        let mut table = RoutingTable::new(dummy_node_id(0xAA));
        let beacon = dummy_beacon(dummy_node_id(0xBB), 1, 80);
        // rssi < -85 → lq == 0 → should not add route
        let changed = table.process_beacon(&beacon, -90, true);
        assert!(!changed);
        assert!(table.get_best_route(&dummy_node_id(0xBB)).is_none());
    }

    #[test]
    fn test_process_beacon_low_battery_worse_metric() {
        let our_id = dummy_node_id(0xAA);
        let peer_a = dummy_node_id(0xBB);
        let peer_b = dummy_node_id(0xCC);

        let mut table = RoutingTable::new(our_id);
        let b_high_battery = dummy_beacon(peer_a, 1, 80);
        let b_low_battery = dummy_beacon(peer_b, 1, 10);

        table.process_beacon(&b_high_battery, -70, true);
        table.process_beacon(&b_low_battery, -70, true);

        let metric_a = table.get_best_route(&peer_a).unwrap().metric;
        let metric_b = table.get_best_route(&peer_b).unwrap().metric;
        assert!(metric_a < metric_b, "low-battery peer should have worse metric");
    }

    // --- process_ogm tests (MeshPacketHeader-based, not yet wired in engine) -

    #[test]
    fn test_process_ogm_new_route() {
        let mut table = RoutingTable::new(dummy_node_id(0xAA));
        let pkt = dummy_ogm_packet(dummy_node_id(0xBB), 1, 500, 1);
        let rebroadcast = table.process_ogm(&pkt, -60);
        assert!(rebroadcast);
        assert!(table.get_best_route(&dummy_node_id(0xBB)).is_some());
    }

    #[test]
    fn test_process_ogm_own_packet_ignored() {
        let our_id = dummy_node_id(0xAA);
        let mut table = RoutingTable::new(our_id);
        let pkt = dummy_ogm_packet(our_id, 1, 0, 0);
        assert!(!table.process_ogm(&pkt, -60));
    }

    #[test]
    fn test_process_ogm_replay_rejected() {
        let mut table = RoutingTable::new(dummy_node_id(0xAA));
        let pkt = dummy_ogm_packet(dummy_node_id(0xBB), 5, 200, 1);
        assert!(table.process_ogm(&pkt, -70));
        // Same seq: rejected.
        assert!(!table.process_ogm(&pkt, -70));
        // Older seq: rejected.
        let old = dummy_ogm_packet(dummy_node_id(0xBB), 4, 200, 1);
        assert!(!table.process_ogm(&old, -70));
    }

    #[test]
    fn test_build_ogm() {
        let mut table = RoutingTable::new(dummy_node_id(0xAA));
        let pkt = dummy_ogm_packet(dummy_node_id(0xBB), 1, 200, 1);
        table.process_ogm(&pkt, -70);
        let ogm = table.build_ogm(1);
        assert!(ogm.len() > MeshPacketHeader::SIZE);
    }

    // --- purge_stale / tick tests --------------------------------------------

    #[test]
    fn test_purge_stale_evicts_old_routes() {
        let mut table = RoutingTable::new(dummy_node_id(0xAA));
        let peer = dummy_node_id(0xBB);
        let beacon = dummy_beacon(peer, 1, 80);
        table.process_beacon(&beacon, -60, true);
        assert!(table.get_best_route(&peer).is_some());

        // Advance far past the max age without hearing from the peer again.
        for _ in 0..150 {
            table.advance_tick();
        }
        table.purge_stale(120);
        assert!(table.get_best_route(&peer).is_none(), "stale route should be purged");
    }

    #[test]
    fn test_purge_stale_keeps_recently_seen_routes() {
        let mut table = RoutingTable::new(dummy_node_id(0xAA));
        let peer = dummy_node_id(0xBB);
        let beacon = dummy_beacon(peer, 1, 80);
        table.process_beacon(&beacon, -60, true);

        for _ in 0..50 {
            table.advance_tick();
        }
        table.purge_stale(120);
        assert!(table.get_best_route(&peer).is_some(), "recent route should survive purge");
    }

    #[test]
    fn test_purge_stale_retains_replay_tracking_after_route_eviction() {
        // Remediation #2: route eviction (peer went out of range) must NOT
        // reset replay-sequence tracking for that peer. A captured old
        // beacon must still be rejected even after the route itself is gone.
        let mut table = RoutingTable::new(dummy_node_id(0xAA));
        let peer = dummy_node_id(0xBB);
        let b1 = dummy_beacon(peer, 5, 80);
        table.process_beacon(&b1, -60, true);

        for _ in 0..150 {
            table.advance_tick();
        }
        table.purge_stale(120);
        assert!(table.get_best_route(&peer).is_none(), "route should be purged");

        // Old/replayed sequence must still be rejected even though the
        // route was purged -- replay tracking outlives route liveness.
        let b_old_seq = dummy_beacon(peer, 1, 80);
        assert!(
            !table.process_beacon(&b_old_seq, -60, true),
            "replay tracking must survive route purge (remediation #2)"
        );

        // A genuinely new (higher) sequence number is still accepted and
        // re-establishes the route normally.
        let b_new_seq = dummy_beacon(peer, 6, 80);
        assert!(
            table.process_beacon(&b_new_seq, -60, true),
            "legitimate reconnect with advancing sequence should work"
        );
    }

    #[test]
    fn test_replay_tracking_eventually_expires_far_past_route_purge() {
        // Memory-bound eviction: after REPLAY_RETENTION_MULTIPLIER *
        // max_age_ticks of total silence, replay state is finally dropped
        // and the counter resets -- this is a deliberate, documented
        // memory/security tradeoff, not an oversight.
        let mut table = RoutingTable::new(dummy_node_id(0xAA));
        let peer = dummy_node_id(0xBB);
        table.process_beacon(&dummy_beacon(peer, 5, 80), -60, true);

        // Advance well past max_age * REPLAY_RETENTION_MULTIPLIER (120 * 8).
        for _ in 0..1000 {
            table.advance_tick();
            table.purge_stale(120);
        }

        let b_old_seq = dummy_beacon(peer, 1, 80);
        assert!(
            table.process_beacon(&b_old_seq, -60, true),
            "after extended silence, replay tracking should eventually expire"
        );
    }

    #[test]
    fn test_refreshed_route_survives_purge() {
        let mut table = RoutingTable::new(dummy_node_id(0xAA));
        let peer = dummy_node_id(0xBB);

        table.process_beacon(&dummy_beacon(peer, 1, 80), -60, true);
        for _ in 0..100 {
            table.advance_tick();
        }
        // Peer sends another beacon before going stale -- route should refresh.
        table.process_beacon(&dummy_beacon(peer, 2, 80), -60, true);
        for _ in 0..100 {
            table.advance_tick();
        }
        table.purge_stale(120);
        assert!(table.get_best_route(&peer).is_some(), "refreshed route should survive");
    }
}