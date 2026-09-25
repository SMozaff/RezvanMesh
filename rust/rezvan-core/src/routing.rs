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
    /// Highest **beacon** sequence seen from each originator, for replay
    /// rejection. Populated by `process_beacon` only, and only for beacons
    /// that passed MAC verification.
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
    last_beacon_seq: HashMap<NodeId, u32>,
    /// Highest **signed-packet** sequence seen from each originator.
    /// Populated by `process_ogm`.
    ///
    /// This is deliberately a *separate* map from `last_beacon_seq`, not a
    /// shared high-water mark. Beacons carry `MeshEngine::adv_sequence` and
    /// signed packets (OGM, KeyAnnouncement, broadcast, channel, ACK) carry
    /// `MeshEngine::ogm_sequence` -- two independent counters that advance at
    /// very different rates (the beacon counter ticks every cycle; the packet
    /// counter only advances when a packet is actually built). Collapsing them
    /// into one map means whichever counter runs ahead silently starves the
    /// other: in practice a peer's OGM (sequence ~5) is rejected as "stale"
    /// moments after its beacon (sequence ~50) is accepted, which breaks
    /// multi-hop route propagation almost immediately after startup.
    last_packet_seq: HashMap<NodeId, u32>,
    /// Tick at which we last updated `last_beacon_seq` or `last_packet_seq`
    /// for a given originator. Used only to eventually bound those maps'
    /// memory growth; NOT used to gate replay rejection itself.
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
    /// section). Deliberately separate from `last_beacon_seq`/`last_packet_seq`:
    /// those fields gate REPLAY (a sequence must be strictly greater than the
    /// last one seen from that originator, for beacons/OGMs which are periodic
    /// and monotonically increasing), whereas relay dedup needs to catch an
    /// EXACT (originator, sequence) pair seen before regardless of
    /// ordering -- a flooded broadcast can legitimately arrive out of order
    /// from multiple neighbours, and rejecting anything "not strictly
    /// newer" would incorrectly drop a legitimate second copy of the same
    /// broadcast arriving via a different, slower path before drop-worthy
    /// duplication has even been established.
    ///
    /// Bounded the same way as `last_beacon_seq`/`replay_last_seen_tick`: per
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
            last_beacon_seq: HashMap::new(),
            last_packet_seq: HashMap::new(),
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

    /// Read the logical clock without needing `&mut self`.
    pub fn current_tick_value(&self) -> u64 {
        self.current_tick
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
    /// separate). The caller (`MeshEngine::process_beacon`) verifies the beacon
    /// via `rezvan_crypto::epoch_key::verify_tag` and passes the outcome as
    /// `verified`.
    ///
    /// # Unverified beacons must not advance the replay high-water mark
    ///
    /// This is the whole reason `verified` gates *every* state write below, not
    /// just the routing-table update. An earlier version recorded the sequence
    /// for unverified beacons too (to avoid re-processing the same one twice),
    /// which handed any unauthenticated attacker a trivial suppression attack:
    /// broadcast beacons claiming a victim's NodeId with a very high sequence
    /// number, and every *legitimate* beacon from that victim afterwards is
    /// rejected as "stale or replayed". The victim becomes permanently
    /// unroutable and invisible, with no ability to recover except by waiting
    /// out the replay-retention window.
    ///
    /// The original motivation for tracking unverified beacons -- "don't
    /// process the exact same one twice" -- does not actually require it:
    /// processing an unverified beacon is a pure no-op that returns `false`
    /// without touching any state, so re-processing is free. Note this does
    /// not fully close beacon spoofing by a *mesh member*, since the
    /// network-wide epoch key means any member can forge any sender's beacon
    /// (a deliberate, documented tradeoff -- see `rezvan_crypto::epoch_key`);
    /// it closes spoofing by a non-member, which is the unauthenticated
    /// attacker.
    ///
    /// Once `verified`, replay/reordering protection is enforced: a beacon
    /// whose sequence is not strictly greater than the last verified one from
    /// that originator is rejected as replayed.
    ///
    /// Returns `true` if the routing table changed such that this beacon's
    /// information is now worth reflecting in our own next OGM.
    pub fn process_beacon(&mut self, beacon: &AdvBeaconExt, rssi: i32, verified: bool) -> bool {
        if beacon.originator == self.node_id {
            return false;
        }

        // Discovery-only beacon: we have no epoch key yet, or the tag did not
        // verify. Touch nothing -- in particular, do NOT record the sequence.
        // See the doc comment above for why recording it here is a vulnerability.
        if !verified {
            return false;
        }

        let last_seq = self.last_beacon_seq.get(&beacon.originator).copied();
        if let Some(last) = last_seq {
            if beacon.sequence <= last {
                return false; // stale or replayed
            }
        }
        self.last_beacon_seq.insert(beacon.originator, beacon.sequence);
        self.replay_last_seen_tick.insert(beacon.originator, self.current_tick);

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
    /// replay check as `process_beacon`, against the *signed-packet* sequence
    /// space (`last_packet_seq`) rather than the beacon one.
    ///
    /// Because the caller has verified the signature, an OGM is always
    /// authenticated before we get here -- so unlike `process_beacon` there is
    /// no `verified` flag, and the sequence can safely advance the high-water
    /// mark.
    pub fn process_ogm(&mut self, packet: &[u8], rssi: i32) -> bool {
        let header = match MeshPacketHeader::deserialize(packet) {
            Some(h) => h,
            None => return false,
        };

        if header.originator == self.node_id {
            return false;
        }

        let last_seq = self.last_packet_seq.get(&header.originator).copied();
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

        self.last_packet_seq.insert(header.originator, header.sequence);
        self.replay_last_seen_tick.insert(header.originator, self.current_tick);

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
    /// interval). Also drops replay high-water-mark entries for any originator
    /// with no remaining routes, so memory doesn't grow unboundedly over a long
    /// mesh session as peers come and go (security audit finding #9: this
    /// function previously did nothing and was never called).
    ///
    /// Tradeoff: forgetting `last_beacon_seq` for a purged (long-silent) peer
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
        // liveness (see field docs on `last_beacon_seq` / remediation #2). A
        // peer that goes out of range and reconnects within
        // REPLAY_RETENTION_MULTIPLIER * max_age_ticks must not have its
        // sequence counter reset, or a captured old beacon becomes replayable
        // the moment its route is purged. We only evict replay state once it
        // has been quiet far longer than that -- purely to bound memory, not
        // to gate security.
        let replay_max_age = max_age_ticks.saturating_mul(Self::REPLAY_RETENTION_MULTIPLIER);
        let replay_last_seen_tick = &self.replay_last_seen_tick;
        // Both sequence spaces share one tick map, so "silent" means silent in
        // either space. An originator with an entry in only one of the two
        // maps is still correctly bounded: its eviction follows the same
        // window.
        self.last_beacon_seq.retain(|node, _| {
            replay_last_seen_tick
                .get(node)
                .map(|&t| now.saturating_sub(t) <= replay_max_age)
                .unwrap_or(false)
        });
        self.last_packet_seq.retain(|node, _| {
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
        // `last_beacon_seq`'s eviction above.
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

    // --- on-disk persistence -------------------------------------------------

    /// Snapshot the routing table so a restart doesn't have to re-learn every
    /// neighbour from scratch.
    ///
    /// Restarting with an empty table isn't merely a performance loss: without
    /// the replay-sequence history, a captured old beacon/OGM from a peer that
    /// is currently out of range becomes replayable again the moment that peer
    /// reappears, and multi-hop sends to a still-reachable peer fail until a
    /// fresh OGM has propagated. Persisting the replay high-water marks
    /// alongside the routes is therefore a security property, not just a
    /// convenience.
    pub fn export_state(&self) -> PersistedRoutingState {
        let mut routes: Vec<(NodeId, Vec<PersistedRouteEntry>)> = self
            .routes
            .iter()
            .map(|(dest, entries)| {
                let entries = entries
                    .iter()
                    .map(|e| PersistedRouteEntry {
                        next_hop: e.next_hop,
                        metric: e.metric,
                        link_quality: e.link_quality,
                        last_seen_tick: e.last_seen_tick,
                    })
                    .collect();
                (*dest, entries)
            })
            .collect();
        routes.sort_by_key(|(dest, _)| *dest);

        let mut last_beacon_seq: Vec<(NodeId, u32)> = self
            .last_beacon_seq
            .iter()
            .map(|(node, seq)| (*node, *seq))
            .collect();
        last_beacon_seq.sort_by_key(|(node, _)| *node);

        let mut last_packet_seq: Vec<(NodeId, u32)> = self
            .last_packet_seq
            .iter()
            .map(|(node, seq)| (*node, *seq))
            .collect();
        last_packet_seq.sort_by_key(|(node, _)| *node);

        let mut replay_last_seen_tick: Vec<(NodeId, u64)> = self
            .replay_last_seen_tick
            .iter()
            .map(|(node, tick)| (*node, *tick))
            .collect();
        replay_last_seen_tick.sort_by_key(|(node, _)| *node);

        let mut relayed_seen: Vec<(NodeId, Vec<u32>)> = self
            .relayed_seen
            .iter()
            .map(|(node, set)| {
                let mut seqs: Vec<u32> = set.iter().copied().collect();
                seqs.sort_unstable();
                (*node, seqs)
            })
            .collect();
        relayed_seen.sort_by_key(|(node, _)| *node);

        let mut relayed_seen_last_tick: Vec<(NodeId, u64)> = self
            .relayed_seen_last_tick
            .iter()
            .map(|(node, tick)| (*node, *tick))
            .collect();
        relayed_seen_last_tick.sort_by_key(|(node, _)| *node);

        PersistedRoutingState {
            routes,
            last_beacon_seq,
            last_packet_seq,
            replay_last_seen_tick,
            current_tick: self.current_tick,
            relayed_seen,
            relayed_seen_last_tick,
        }
    }

    /// Restore a routing table from `export_state`.
    ///
    /// The restored `current_tick` continues from where the snapshot left off
    /// rather than restarting at zero. That matters for `purge_stale`, which
    /// compares `now - last_seen_tick`: resetting the clock to 0 while keeping
    /// the saved `last_seen_tick` values would make `saturating_sub` clamp to 0
    /// and report every saved route as freshly seen, pinning routes that are
    /// actually long dead.
    pub fn import_state(&mut self, state: PersistedRoutingState) {
        self.routes = state
            .routes
            .into_iter()
            .map(|(dest, entries)| {
                let entries = entries
                    .into_iter()
                    .map(|e| RouteEntry {
                        next_hop: e.next_hop,
                        metric: e.metric,
                        link_quality: e.link_quality,
                        last_seen_tick: e.last_seen_tick,
                    })
                    .collect();
                (dest, entries)
            })
            .collect();
        self.last_beacon_seq = state.last_beacon_seq.into_iter().collect();
        self.last_packet_seq = state.last_packet_seq.into_iter().collect();
        self.replay_last_seen_tick = state.replay_last_seen_tick.into_iter().collect();
        self.current_tick = state.current_tick;
        self.relayed_seen = state
            .relayed_seen
            .into_iter()
            .map(|(node, seqs)| (node, seqs.into_iter().collect()))
            .collect();
        self.relayed_seen_last_tick = state.relayed_seen_last_tick.into_iter().collect();
    }
}

/// Serializable form of a single `RouteEntry`.
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct PersistedRouteEntry {
    pub next_hop: NodeId,
    pub metric: u32,
    pub link_quality: u8,
    pub last_seen_tick: u64,
}

/// Serializable form of a whole `RoutingTable`.
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct PersistedRoutingState {
    pub routes: Vec<(NodeId, Vec<PersistedRouteEntry>)>,
    pub last_beacon_seq: Vec<(NodeId, u32)>,
    /// Added after the first release of the state format. `default` so a state
    /// file written by the single-map version still loads -- it just starts
    /// with no packet-space high-water marks, which is exactly the
    /// pre-split (accepting) behaviour for that one field.
    #[serde(default)]
    pub last_packet_seq: Vec<(NodeId, u32)>,
    pub replay_last_seen_tick: Vec<(NodeId, u64)>,
    pub current_tick: u64,
    pub relayed_seen: Vec<(NodeId, Vec<u32>)>,
    pub relayed_seen_last_tick: Vec<(NodeId, u64)>,
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
        let peer = dummy_node_id(0xBB);
        let beacon = dummy_beacon(peer, 1, 80);
        // Even with good signal, an unverified beacon must not add a route.
        let changed = table.process_beacon(&beacon, -60, false);
        assert!(!changed);
        assert!(table.get_best_route(&peer).is_none());

        // A late-arriving *verified* copy of the same sequence MUST be
        // accepted.
        //
        // This previously asserted the opposite, on the reasoning that
        // recording the unverified sequence would stop "the same packet being
        // processed twice". That reasoning was wrong in a way that turned into
        // a denial-of-service: the unverified sequence is proof of nothing,
        // while the verified one carries a valid epoch-key MAC. Rejecting the
        // authenticated beacon because an unauthenticated one claimed the
        // same number first means anyone on the air can veto a peer's
        // advertisement.
        let changed2 = table.process_beacon(&beacon, -60, true);
        assert!(
            changed2,
            "a verified beacon must not be suppressed by an earlier unverified one"
        );
        assert!(table.get_best_route(&peer).is_some());
    }

    /// Regression test for the unauthenticated beacon-suppression attack.
    ///
    /// An off-mesh attacker who can transmit BLE advertisements (but does not
    /// hold the mesh epoch key) forges beacons claiming a real peer's NodeId
    /// with a very high sequence number. If those sequences advance the replay
    /// high-water mark, every subsequent *genuine* beacon from that peer looks
    /// like a replay and is dropped, so the peer becomes permanently
    /// unroutable and its KeyAnnouncements stop being trusted.
    #[test]
    fn test_unverified_beacons_cannot_poison_the_replay_high_water_mark() {
        let mut table = RoutingTable::new(dummy_node_id(0xAA));
        let victim = dummy_node_id(0xBB);

        // The attacker floods forged beacons for the victim's NodeId with
        // monotonically increasing, very large sequences.
        for seq in [100_000, 200_000, 900_000, u32::MAX - 1, u32::MAX] {
            let forged = dummy_beacon(victim, seq, 90);
            assert!(
                !table.process_beacon(&forged, -50, false),
                "forged beacon must never report a routing change"
            );
            assert!(
                table.get_best_route(&victim).is_none(),
                "forged beacon must not create a route"
            );
        }

        // The victim now sends a genuine, correctly-sequenced beacon. Because
        // the attacker's numbers were never recorded, this is accepted -- the
        // victim is not silenced.
        let genuine = dummy_beacon(victim, 42, 90);
        assert!(
            table.process_beacon(&genuine, -50, true),
            "a forged high-sequence flood must not silence a real peer"
        );
        assert!(table.get_best_route(&victim).is_some());

        // And replay protection still works against the *verified* stream:
        // replaying that genuine beacon is rejected.
        assert!(!table.process_beacon(&genuine, -50, true));
    }

    /// Regression test for the sequence-space collision between the beacon
    /// counter (`adv_sequence`) and the signed-packet counter
    /// (`ogm_sequence`).
    ///
    /// These are two independent counters on the sender. When they shared one
    /// high-water mark, whichever ran ahead starved the other: a peer's OGM
    /// carrying sequence 5 was rejected as "stale" immediately after its
    /// beacon carrying sequence 50 was accepted, which silently broke
    /// multi-hop route propagation shortly after startup.
    #[test]
    fn beacon_and_packet_sequences_do_not_starve_each_other() {
        let mut table = RoutingTable::new(dummy_node_id(0xAA));
        let peer = dummy_node_id(0xBB);

        // Beacon stream is well ahead (adv_sequence = 500).
        assert!(table.process_beacon(&dummy_beacon(peer, 500, 90), -55, true));

        // The signed-packet stream is still early (ogm_sequence = 7). It must
        // be judged against its own high-water mark, not the beacon's.
        assert!(
            table.process_ogm(&dummy_ogm_packet(peer, 7, 100, 0), -55),
            "a low OGM sequence must not be starved by a high beacon sequence"
        );

        // Each space still enforces its own monotonicity.
        assert!(!table.process_beacon(&dummy_beacon(peer, 499, 90), -55, true));
        assert!(!table.process_ogm(&dummy_ogm_packet(peer, 6, 100, 0), -55));
        // ...and both keep advancing independently afterwards.
        assert!(table.process_ogm(&dummy_ogm_packet(peer, 8, 100, 0), -55));
        assert!(table.process_beacon(&dummy_beacon(peer, 501, 90), -55, true));
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